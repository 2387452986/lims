package com.lanchuan.lims.workbench;

import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.storedfile.StoredFileService;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class QualityLedgerExportWorker {
    private static final Logger log = LoggerFactory.getLogger(QualityLedgerExportWorker.class);
    private static final int MAX_ROWS = 50_000;
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final StoredFileService files;
    private final QualityLedgerService ledgers;
    private final String workerId;

    public QualityLedgerExportWorker(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper json,
                                     StoredFileService files, QualityLedgerService ledgers,
                                     @Value("${lims.jobs.worker-id:${HOSTNAME:local}-quality-worker}") String workerId) {
        this.jdbc = jdbc; this.transactions = transactions; this.json = json; this.files = files;
        this.ledgers = ledgers; this.workerId = workerId;
    }

    @Scheduled(fixedDelayString = "${lims.jobs.quality-poll-delay-ms:1500}")
    void poll() {
        Job job = transactions.execute(status -> claim());
        if (job == null) return;
        try {
            Result result = export(job);
            transactions.executeWithoutResult(status -> complete(job, result));
        } catch (Exception exception) {
            log.error("Quality ledger export job {} failed", job.id(), exception);
            transactions.executeWithoutResult(status -> fail(job.id(), exception));
        }
    }

    private Job claim() {
        return jdbc.sql("""
                WITH candidate AS (
                  SELECT id FROM background_job WHERE job_type='EXPORT_QUALITY_LEDGER_XLSX'
                    AND state IN ('READY','RETRY') AND available_at<=now()
                  ORDER BY available_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE background_job job SET state='RUNNING',locked_by=:worker,locked_at=now(),
                  attempt_count=attempt_count+1,version=version+1
                FROM candidate WHERE job.id=candidate.id RETURNING job.id,job.payload::text
                """).param("worker", workerId).query((rs, row) ->
                new Job(rs.getObject("id", UUID.class), rs.getString("payload"))).optional().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Result export(Job job) throws Exception {
        Map<String, Object> payload = json.readValue(job.payload(), new TypeReference<>() {});
        UUID actorId = uuid(payload.get("actorId"));
        UUID personId = optionalUuid(payload.get("personId"));
        UUID organizationId = uuid(payload.get("organizationId"));
        UUID departmentId = optionalUuid(payload.get("departmentId"));
        String dataScope = text(payload.get("dataScope"));
        Map<String, Object> request = (Map<String, Object>) payload.get("request");
        String type = text(request.get("ledgerType")).toUpperCase();
        String actorName = jdbc.sql("SELECT display_name FROM app_user WHERE id=:id AND organization_id=:organization")
                .param("id", actorId).param("organization", organizationId).query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("queued quality export actor no longer exists"));
        AuthenticatedUser principal = new AuthenticatedUser(actorId, personId, organizationId, departmentId,
                "background-job", actorName, false, 0, dataScope, List.of(), List.of());
        ExportData data = switch (type) {
            case "OVERDUE_SPECIMEN" -> specimenData(principal, request);
            case "REBOUND" -> reboundData(principal, request);
            case "FIELD_TEST" -> fieldData(principal, request);
            default -> throw new IllegalArgumentException("unsupported quality ledger export type");
        };
        byte[] content = workbook(data, actorName);
        StoredFileService.View stored = files.storeGenerated(content,
                data.sheetName() + "-" + LocalDate.now() + ".xlsx", XLSX, "QUALITY_EXPORT", actorId);
        return new Result(stored.id(), actorId, organizationId, type, data.rows().size(), stored.sha256());
    }

    private ExportData specimenData(AuthenticatedUser user, Map<String, Object> request) {
        List<String[]> rows = new ArrayList<>(); String cursor = "";
        do {
            CursorPage<QualityLedgerService.SpecimenRow> page = ledgers.specimensFor(user,
                    new QualityLedgerService.SpecimenFilters("OVERDUE", date(request.get("from")), date(request.get("to")),
                            text(request.get("region")), text(request.get("detectionProject")),
                            text(request.get("commissionNumber")), text(request.get("sampleNumber")),
                            text(request.get("reportNumber")), text(request.get("projectNumber")),
                            text(request.get("projectName")), text(request.get("customerName")),
                            text(request.get("conclusion")), text(request.get("taskState")),
                            text(request.get("curingMethod")), text(request.get("strengthGrade")), cursor, 100));
            for (var row : page.items()) rows.add(new String[]{row.sampleNumber(),row.commissionNumber(),
                    row.detectionProject(),row.customerName(),row.projectNumber(),row.projectName(),row.structurePart(),
                    value(row.commissionedOn()),value(row.moldingDate()),value(row.ageDays()),
                    value(row.plannedTestDate()),value(row.chipCode())});
            ensureLimit(rows); cursor = page.hasMore() ? page.nextCursor() : "";
        } while (!cursor.isEmpty());
        return new ExportData("试块逾期台账", List.of("样品编号","委托编号","检测项目","委托单位","工程编号",
                "工程名称","工程部位","委托日期","成型日期","龄期","计划检测日期","芯片码"), rows);
    }

    private ExportData reboundData(AuthenticatedUser user, Map<String, Object> request) {
        List<String[]> rows = new ArrayList<>(); String cursor = "";
        do {
            CursorPage<QualityLedgerService.ReboundRow> page = ledgers.reboundsFor(user,
                    new QualityLedgerService.ReboundFilters(date(request.get("from")),date(request.get("to")),
                            text(request.get("commissionNumber")),text(request.get("sampleNumber")),
                            text(request.get("projectNumber")),text(request.get("projectName")),
                            text(request.get("customerName")),cursor,100));
            for (var row : page.items()) rows.add(new String[]{row.sampleNumber(),row.commissionNumber(),
                    value(row.componentCount()),value(row.measurementAreaCount()),value(row.commissionedOn()),
                    row.detectionProject(),row.specification(),row.strengthGrade(),value(row.ageDays()),
                    row.structurePart(),row.customerName(),row.projectNumber(),row.projectName()});
            ensureLimit(rows); cursor = page.hasMore() ? page.nextCursor() : "";
        } while (!cursor.isEmpty());
        return new ExportData("回弹评定单", List.of("样品编号","委托单编号","构件总数","测区总数","委托日期",
                "检测项目","规格","等级","龄期","结构部位","委托单位","工程编号","工程名称"), rows);
    }

    private ExportData fieldData(AuthenticatedUser user, Map<String, Object> request) {
        List<String[]> rows = new ArrayList<>(); String cursor = "";
        do {
            CursorPage<QualityLedgerService.FieldTestRow> page = ledgers.fieldTestsFor(user,
                    new QualityLedgerService.FieldTestFilters(date(request.get("from")),date(request.get("to")),
                            text(request.get("progress")),text(request.get("detectionProject")),
                            text(request.get("taskNumber")),text(request.get("projectName")),
                            text(request.get("testerName")),cursor,100));
            for (var row : page.items()) rows.add(new String[]{row.taskNumber(),row.projectName(),
                    row.detectionProject(),value(row.startedAt()),value(row.completedAt()),row.testerName(),row.progress()});
            ensureLimit(rows); cursor = page.hasMore() ? page.nextCursor() : "";
        } while (!cursor.isEmpty());
        return new ExportData("现场检测记录", List.of("任务单号","工程名称","检测项目","开始时间","结束时间",
                "检测人员","检测进度"), rows);
    }

    private byte[] workbook(ExportData data, String actorName) throws Exception {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            var sheet = workbook.createSheet(data.sheetName());
            sheet.addMergedRegion(new CellRangeAddress(0,0,0,data.headers().size()-1));
            var watermark = sheet.createRow(0).createCell(0);
            watermark.setCellValue("内部受控导出 · " + actorName + " · " + LOCAL_TIME.format(OffsetDateTime.now()));
            CellStyle watermarkStyle = workbook.createCellStyle();
            var watermarkFont = workbook.createFont(); watermarkFont.setBold(true); watermarkFont.setColor(IndexedColors.RED.getIndex());
            watermarkStyle.setFont(watermarkFont); watermark.setCellStyle(watermarkStyle);
            var header = sheet.createRow(1); CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for (int index=0; index<data.headers().size(); index++) {
                var cell=header.createCell(index); cell.setCellValue(data.headers().get(index)); cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(index, Math.min(36,Math.max(14,data.headers().get(index).length()+8))*256);
            }
            for (int rowIndex=0; rowIndex<data.rows().size(); rowIndex++) {
                var row=sheet.createRow(rowIndex+2); String[] values=data.rows().get(rowIndex);
                for (int column=0; column<values.length; column++) row.createCell(column).setCellValue(value(values[column]));
            }
            workbook.write(output); workbook.dispose(); return output.toByteArray();
        }
    }

    private void complete(Job job, Result result) {
        int changed = jdbc.sql("""
                UPDATE background_job SET state='COMPLETED',result_file_id=:file,completed_at=now(),
                  last_error=NULL,locked_by=NULL,locked_at=NULL,version=version+1
                WHERE id=:id AND locked_by=:worker AND state='RUNNING'
                """).param("file",result.fileId()).param("id",job.id()).param("worker",workerId).update();
        if (changed!=1) throw new IllegalStateException("quality export job ownership changed before completion");
        try {
            String details=json.writeValueAsString(Map.of("fileId",result.fileId(),"ledgerType",result.ledgerType(),
                    "rowCount",result.rowCount(),"sha256",result.sha256()));
            jdbc.sql("""
                    INSERT INTO audit_event(id,request_id,actor_id,actor_name,action,resource_type,
                      resource_id,organization_id,after_json)
                    SELECT :audit,:request,user_account.id,user_account.display_name,
                      'QUALITY_LEDGER_EXPORT_COMPLETED','BACKGROUND_JOB',:resource,:organization,CAST(:details AS jsonb)
                    FROM app_user user_account WHERE user_account.id=:actor
                    """).param("audit",UUID.randomUUID()).param("request","job:"+job.id())
                    .param("resource",job.id().toString()).param("organization",result.organizationId())
                    .param("details",details).param("actor",result.actorId()).update();
        } catch (Exception exception) { throw new IllegalStateException("quality export audit encoding failed",exception); }
    }

    private void fail(UUID id, Exception exception) {
        String message=exception.getClass().getSimpleName()+": "+String.valueOf(exception.getMessage());
        if (message.length()>1900) message=message.substring(0,1900);
        jdbc.sql("""
                UPDATE background_job SET state=CASE WHEN attempt_count>=3 THEN 'FAILED' ELSE 'RETRY' END,
                  available_at=now()+(attempt_count*interval '1 minute'),last_error=:error,
                  locked_by=NULL,locked_at=NULL,version=version+1 WHERE id=:id AND locked_by=:worker
                """).param("error",message).param("id",id).param("worker",workerId).update();
    }

    private void ensureLimit(List<String[]> rows) { if (rows.size()>MAX_ROWS) throw new IllegalStateException("导出结果超过 50000 行，请缩小查询范围"); }
    private UUID uuid(Object value) { return UUID.fromString(String.valueOf(value)); }
    private UUID optionalUuid(Object value) { return value==null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value)) ? null : uuid(value); }
    private LocalDate date(Object value) { return value==null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value)) ? null : LocalDate.parse(String.valueOf(value)); }
    private String text(Object value) { return value==null || "null".equals(String.valueOf(value)) ? "" : String.valueOf(value); }
    private String value(Object value) { return value==null ? "" : value instanceof OffsetDateTime time ? LOCAL_TIME.format(time) : String.valueOf(value); }
    private record Job(UUID id,String payload) {}
    private record ExportData(String sheetName,List<String> headers,List<String[]> rows) {}
    private record Result(UUID fileId,UUID actorId,UUID organizationId,String ledgerType,int rowCount,String sha256) {}
}
