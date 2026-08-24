package com.lanchuan.lims.finance;

import com.lanchuan.lims.storedfile.StoredFileService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class FinanceJobWorker {
    private static final Logger log = LoggerFactory.getLogger(FinanceJobWorker.class);
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_ROWS = 50_000;
    private static final UUID NO_ID = new UUID(0, 0);
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final StoredFileService files;
    private final String workerId;

    public FinanceJobWorker(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper json,
                            StoredFileService files,
                            @Value("${lims.jobs.worker-id:${HOSTNAME:local}-finance-worker}") String workerId) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.files = files;
        this.workerId = workerId;
    }

    @Scheduled(fixedDelayString = "${lims.jobs.finance-poll-delay-ms:1500}")
    void poll() {
        Job job = transactions.execute(status -> claim());
        if (job == null) return;
        try {
            Result result = export(job);
            transactions.executeWithoutResult(status -> complete(job, result));
        } catch (Exception exception) {
            log.error("Finance export job {} failed", job.id(), exception);
            transactions.executeWithoutResult(status -> fail(job.id(), exception));
        }
    }

    private Job claim() {
        return jdbc.sql("""
                WITH candidate AS (
                  SELECT id FROM background_job WHERE job_type='EXPORT_FINANCE_XLSX'
                    AND state IN ('READY','RETRY') AND available_at<=now()
                  ORDER BY available_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE background_job job SET state='RUNNING',locked_by=:worker,locked_at=now(),
                  attempt_count=attempt_count+1,version=version+1
                FROM candidate WHERE job.id=candidate.id
                RETURNING job.id,job.payload::text
                """).param("worker", workerId).query((rs, row) ->
                new Job(rs.getObject("id", UUID.class), rs.getString("payload"))).optional().orElse(null);
    }

    private Result export(Job job) throws Exception {
        Map<String, Object> payload = json.readValue(job.payload(), new TypeReference<>() {});
        UUID actorId = requiredUuid(payload, "actorId");
        UUID organizationId = requiredUuid(payload, "organizationId");
        String type = text(payload.get("resourceType")).toUpperCase(Locale.ROOT);
        ExportData data = switch (type) {
            case "CONTRACT" -> contracts(payload);
            case "FEE_STATEMENT" -> fees(payload);
            case "RECEIPT" -> receipts(payload);
            case "PAYMENT" -> payments(payload);
            default -> throw new IllegalArgumentException("unsupported finance export type");
        };
        if (data.rows().size() > MAX_ROWS) throw new IllegalStateException("导出结果超过 50000 行，请缩小查询范围");
        String actorName = jdbc.sql("SELECT display_name FROM app_user WHERE id=:id AND organization_id=:organization")
                .param("id", actorId).param("organization", organizationId).query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("queued finance export actor no longer exists"));
        byte[] content = workbook(data, actorName);
        String fileName = data.sheetName() + "-" + OffsetDateTime.now().toLocalDate() + ".xlsx";
        StoredFileService.View stored = files.storeGenerated(content, fileName, XLSX, "FINANCE_EXPORT", actorId);
        return new Result(stored.id(), actorId, organizationId, type, data.rows().size(), stored.sha256());
    }

    private ExportData contracts(Map<String, Object> payload) {
        Map<String, Object> params = params(payload);
        List<String[]> rows = jdbc.sql("""
                SELECT b.number,b.name,b.contract_type,customer.name customer_name,p.number project_number,
                  p.name project_name,b.counterparty_name,b.signed_on,b.delivery_deadline,b.currency,
                  b.signed_amount,b.contract_price,b.state,b.updated_at
                FROM business_contract b JOIN customer ON customer.id=b.customer_id JOIN project p ON p.id=b.project_id
                """ + contractScope("b.organization_id") + """
                  AND (:state='' OR b.state=:state)
                  AND (CAST(:contract AS uuid) IS NULL OR b.id=CAST(:contract AS uuid))
                  AND (CAST(:project AS uuid) IS NULL OR p.id=CAST(:project AS uuid))
                  AND (:query='' OR lower(b.number) LIKE :pattern OR lower(b.name) LIKE :pattern
                    OR lower(customer.name) LIKE :pattern OR lower(p.name) LIKE :pattern)
                ORDER BY b.updated_at DESC,b.id DESC LIMIT :fetch
                """).params(params).query((rs, row) -> values(rs, "number", "name", "contract_type",
                        "customer_name", "project_number", "project_name", "counterparty_name", "signed_on",
                        "delivery_deadline", "currency", "signed_amount", "contract_price", "state", "updated_at"))
                .list();
        return new ExportData("合同清单", List.of("合同编号", "合同名称", "合同类别", "客户", "工程编号", "工程名称",
                "交易单位", "签订日期", "交付期限", "币种", "签订金额", "合同价", "状态", "更新时间"), rows);
    }

    private ExportData fees(Map<String, Object> payload) {
        Map<String, Object> params = params(payload);
        List<String[]> rows = jdbc.sql("""
                SELECT b.number contract_number,p.number project_number,p.name project_name,
                  commission.number commission_number,sample.number sample_number,report.number report_number,
                  f.item_name,f.sample_name,f.parameter_name,f.quantity,f.unit,f.unit_price,f.amount,f.paid_amount,
                  (f.amount-f.paid_amount) outstanding_amount,settler.display_name settlement_owner,f.state,f.updated_at
                FROM fee_statement f JOIN business_contract b ON b.id=f.contract_id JOIN project p ON p.id=f.project_id
                LEFT JOIN commission ON commission.id=f.commission_id LEFT JOIN sample ON sample.id=f.sample_id
                LEFT JOIN report ON report.id=f.report_id LEFT JOIN app_user settler ON settler.id=f.settlement_owner_id
                """ + contractScope("f.organization_id") + """
                  AND (:state='' OR f.state=:state)
                  AND (CAST(:contract AS uuid) IS NULL OR f.contract_id=CAST(:contract AS uuid))
                  AND (CAST(:project AS uuid) IS NULL OR f.project_id=CAST(:project AS uuid))
                  AND (:query='' OR lower(b.number) LIKE :pattern OR lower(p.name) LIKE :pattern
                    OR lower(f.item_name) LIKE :pattern OR lower(COALESCE(f.parameter_name,'')) LIKE :pattern)
                ORDER BY f.updated_at DESC,f.id DESC LIMIT :fetch
                """).params(params).query((rs, row) -> values(rs, "contract_number", "project_number", "project_name",
                        "commission_number", "sample_number", "report_number", "item_name", "sample_name",
                        "parameter_name", "quantity", "unit", "unit_price", "amount", "paid_amount",
                        "outstanding_amount", "settlement_owner", "state", "updated_at")).list();
        return new ExportData("费用清单", List.of("合同编号", "工程编号", "工程名称", "委托编号", "样品编号", "报告编号",
                "费用项目", "样品", "参数", "数量", "单位", "单价", "应收", "已收", "未收", "结算人", "状态", "更新时间"), rows);
    }

    private ExportData receipts(Map<String, Object> payload) {
        Map<String, Object> params = params(payload);
        List<String[]> rows = jdbc.sql("""
                SELECT r.number,b.number contract_number,p.number project_number,p.name project_name,
                  r.receivable_amount,r.actual_amount,r.received_on,r.payment_method,collector.display_name collector_name,
                  r.payer_name,r.invoice_number,r.state,r.confirmed_at,r.updated_at
                FROM finance_receipt r JOIN business_contract b ON b.id=r.contract_id JOIN project p ON p.id=b.project_id
                JOIN app_user collector ON collector.id=r.collector_id
                """ + contractScope("r.organization_id") + """
                  AND (:state='' OR r.state=:state)
                  AND (CAST(:contract AS uuid) IS NULL OR r.contract_id=CAST(:contract AS uuid))
                  AND (CAST(:project AS uuid) IS NULL OR p.id=CAST(:project AS uuid))
                  AND (:query='' OR lower(r.number) LIKE :pattern OR lower(b.number) LIKE :pattern
                    OR lower(r.payer_name) LIKE :pattern OR lower(COALESCE(r.invoice_number,'')) LIKE :pattern)
                ORDER BY r.updated_at DESC,r.id DESC LIMIT :fetch
                """).params(params).query((rs, row) -> values(rs, "number", "contract_number", "project_number",
                        "project_name", "receivable_amount", "actual_amount", "received_on", "payment_method",
                        "collector_name", "payer_name", "invoice_number", "state", "confirmed_at", "updated_at"))
                .list();
        return new ExportData("收款清单", List.of("收款单号", "合同编号", "工程编号", "工程名称", "应收", "实收",
                "收款日期", "收款方式", "收款人", "付款方", "发票号", "状态", "确认时间", "更新时间"), rows);
    }

    private ExportData payments(Map<String, Object> payload) {
        Map<String, Object> params = params(payload);
        List<String[]> rows = jdbc.sql("""
                SELECT py.number,b.number contract_number,p.number project_number,p.name project_name,
                  py.payable_amount,py.actual_amount,py.planned_on,py.paid_on,py.payment_method,py.payee_name,
                  py.invoice_number,py.purpose,py.state,py.decision_comment,py.updated_at
                FROM finance_payment py JOIN business_contract b ON b.id=py.contract_id JOIN project p ON p.id=b.project_id
                """ + contractScope("py.organization_id") + """
                  AND (:state='' OR py.state=:state)
                  AND (CAST(:contract AS uuid) IS NULL OR py.contract_id=CAST(:contract AS uuid))
                  AND (CAST(:project AS uuid) IS NULL OR p.id=CAST(:project AS uuid))
                  AND (:query='' OR lower(py.number) LIKE :pattern OR lower(b.number) LIKE :pattern
                    OR lower(py.payee_name) LIKE :pattern OR lower(COALESCE(py.invoice_number,'')) LIKE :pattern)
                ORDER BY py.updated_at DESC,py.id DESC LIMIT :fetch
                """).params(params).query((rs, row) -> values(rs, "number", "contract_number", "project_number",
                        "project_name", "payable_amount", "actual_amount", "planned_on", "paid_on", "payment_method",
                        "payee_name", "invoice_number", "purpose", "state", "decision_comment", "updated_at"))
                .list();
        return new ExportData("付款清单", List.of("付款单号", "合同编号", "工程编号", "工程名称", "应付", "实付",
                "计划付款日", "实际付款日", "付款方式", "收款方", "发票号", "用途", "状态", "审批意见", "更新时间"), rows);
    }

    private String contractScope(String organizationColumn) {
        return " WHERE " + organizationColumn + "=:organization AND ("
                + " :scope IN ('ALL','ORGANIZATION')"
                + " OR (:scope='PROJECT' AND EXISTS(SELECT 1 FROM project_member member WHERE member.project_id=p.id AND member.user_id=:actor))"
                + " OR (:scope='DEPARTMENT' AND EXISTS(SELECT 1 FROM app_user creator WHERE creator.id=p.created_by AND creator.department_id=:department))"
                + " OR (:scope='SELF' AND (b.owner_id=:actor OR p.created_by=:actor OR EXISTS(SELECT 1 FROM project_member member WHERE member.project_id=p.id AND member.user_id=:actor)))"
                + ")";
    }

    private Map<String, Object> params(Map<String, Object> payload) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("organization", requiredUuid(payload, "organizationId"));
        params.put("actor", requiredUuid(payload, "actorId"));
        params.put("scope", text(payload.get("dataScope")));
        params.put("department", optionalUuid(payload.get("departmentId"), NO_ID));
        String query = text(payload.get("query"));
        params.put("query", query);
        params.put("pattern", "%" + query.toLowerCase(Locale.ROOT) + "%");
        params.put("state", text(payload.get("state")).toUpperCase(Locale.ROOT));
        params.put("contract", optionalUuid(payload.get("contractId"), null));
        params.put("project", optionalUuid(payload.get("projectId"), null));
        params.put("fetch", MAX_ROWS + 1);
        return params;
    }

    private byte[] workbook(ExportData data, String actorName) throws Exception {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(data.sheetName());
            Row watermark = sheet.createRow(0);
            watermark.createCell(0).setCellValue("内部受控导出 · 导出人：" + actorName + " · 导出时间："
                    + LOCAL_TIME.format(OffsetDateTime.now()));
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, data.headers().size() - 1));
            Row header = sheet.createRow(1);
            CellStyle style = workbook.createCellStyle();
            style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setWrapText(true);
            for (int column = 0; column < data.headers().size(); column++) {
                header.createCell(column).setCellValue(data.headers().get(column));
                header.getCell(column).setCellStyle(style);
                sheet.setColumnWidth(column, Math.min(42, Math.max(12, data.headers().get(column).length() * 3)) * 256);
            }
            int rowIndex = 2;
            for (String[] values : data.rows()) {
                Row row = sheet.createRow(rowIndex++);
                for (int column = 0; column < values.length; column++) row.createCell(column).setCellValue(values[column]);
            }
            sheet.createFreezePane(0, 2);
            sheet.setAutoFilter(new CellRangeAddress(1, Math.max(1, rowIndex - 1), 0, data.headers().size() - 1));
            workbook.write(output);
            return output.toByteArray();
        } finally {
            workbook.dispose();
            workbook.close();
        }
    }

    private void complete(Job job, Result result) {
        int changed = jdbc.sql("""
                UPDATE background_job SET state='COMPLETED',result_file_id=:file,completed_at=now(),
                  last_error=NULL,locked_by=NULL,locked_at=NULL,version=version+1
                WHERE id=:id AND locked_by=:worker AND state='RUNNING'
                """).param("file", result.fileId()).param("id", job.id()).param("worker", workerId).update();
        if (changed != 1) throw new IllegalStateException("finance job ownership changed before completion");
        Map<String, Object> details = Map.of("fileId", result.fileId(), "resourceType", result.resourceType(),
                "rowCount", result.rowCount(), "sha256", result.sha256());
        try {
            jdbc.sql("""
                    INSERT INTO audit_event(id,request_id,actor_id,actor_name,action,resource_type,organization_id,after_json)
                    SELECT :id,:requestId,user_account.id,user_account.display_name,'FINANCE_EXPORT_COMPLETED',
                      'BACKGROUND_JOB',:organization,CAST(:afterJson AS jsonb)
                    FROM app_user user_account WHERE user_account.id=:actor
                    """).param("id", UUID.randomUUID()).param("requestId", "job:" + job.id())
                    .param("afterJson", json.writeValueAsString(details)).param("actor", result.actorId())
                    .param("organization", result.organizationId()).update();
        } catch (Exception exception) {
            throw new IllegalStateException("finance export audit encoding failed", exception);
        }
    }

    private void fail(UUID id, Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage());
        if (message.length() > 1900) message = message.substring(0, 1900);
        jdbc.sql("""
                UPDATE background_job SET state=CASE WHEN attempt_count>=3 THEN 'FAILED' ELSE 'RETRY' END,
                  available_at=now()+(attempt_count*interval '1 minute'),last_error=:error,
                  locked_by=NULL,locked_at=NULL,version=version+1 WHERE id=:id AND locked_by=:worker
                """).param("error", message).param("id", id).param("worker", workerId).update();
    }

    private String[] values(ResultSet rs, String... columns) throws SQLException {
        List<String> values = new ArrayList<>(columns.length);
        for (String column : columns) values.add(text(rs.getObject(column)));
        return values.toArray(String[]::new);
    }

    private UUID requiredUuid(Map<String, Object> payload, String key) {
        UUID value = optionalUuid(payload.get(key), null);
        if (value == null) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    private UUID optionalUuid(Object value, UUID fallback) {
        if (value == null || String.valueOf(value).isBlank() || String.valueOf(value).equals("null")) return fallback;
        return UUID.fromString(String.valueOf(value));
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private record Job(UUID id, String payload) {}
    private record ExportData(String sheetName, List<String> headers, List<String[]> rows) {}
    private record Result(UUID fileId, UUID actorId, UUID organizationId, String resourceType,
                          int rowCount, String sha256) {}
}
