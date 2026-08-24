package com.lanchuan.lims.commission;

import com.lanchuan.lims.storedfile.StoredFileService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class CommissionJobWorker {
    private static final Logger log = LoggerFactory.getLogger(CommissionJobWorker.class);
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Set<String> DOCUMENT_FIELDS = Set.of(
            "number", "precommissionNumber", "projectNumber", "projectName", "customerName",
            "contactName", "contactPhone", "commissionedOn", "dueOn", "state", "testingPlace",
            "detectionProjects", "projectPart", "monomer", "remark");
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final StoredFileService files;
    private final String workerId;
    private final String executable;

    public CommissionJobWorker(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper json,
                               StoredFileService files,
                               @Value("${lims.jobs.worker-id:${HOSTNAME:local}-commission-worker}") String workerId,
                               @Value("${lims.reports.chromium-executable:}") String executable) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.files = files;
        this.workerId = workerId;
        this.executable = executable;
    }

    @Scheduled(fixedDelayString = "${lims.jobs.commission-poll-delay-ms:1500}")
    void poll() {
        Job job = transactions.execute(status -> claim());
        if (job == null) return;
        try {
            JobResult result = switch (job.jobType()) {
                case "EXPORT_COMMISSION_XLSX" -> export(job);
                case "RENDER_COMMISSION_DOCUMENT" -> renderDocument(job);
                default -> throw new IllegalStateException("unsupported commission job type");
            };
            transactions.executeWithoutResult(status -> complete(job, result));
        } catch (Exception exception) {
            log.error("Commission job {} ({}) failed", job.id(), job.jobType(), exception);
            transactions.executeWithoutResult(status -> fail(job.id(), exception));
        }
    }

    private Job claim() {
        return jdbc.sql("""
                WITH candidate AS (
                  SELECT id FROM background_job WHERE job_type IN ('EXPORT_COMMISSION_XLSX','RENDER_COMMISSION_DOCUMENT')
                    AND state IN ('READY','RETRY') AND available_at<=now()
                  ORDER BY available_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE background_job job SET state='RUNNING',locked_by=:worker,locked_at=now(),
                  attempt_count=attempt_count+1,version=version+1
                FROM candidate WHERE job.id=candidate.id
                RETURNING job.id,job.job_type,job.payload::text,job.attempt_count
                """).param("worker", workerId).query((rs, row) -> new Job(
                        rs.getObject("id", UUID.class), rs.getString("job_type"),
                        rs.getString("payload"), rs.getInt("attempt_count")))
                .optional().orElse(null);
    }

    private JobResult export(Job job) throws Exception {
        Map<String, Object> payload = payload(job);
        UUID actorId = uuid(payload, "actorId");
        UUID organizationId = uuid(payload, "organizationId");
        List<UUID> ids = uuidList(payload, "commissionIds", 200);
        List<ExportRow> rows = jdbc.sql("""
                SELECT c.id,c.number,c.precommission_number,c.management_sequence,c.internal_sequence,
                  c.external_number,p.number project_number,p.name project_name,customer.name customer_name,
                  c.commissioned_on,c.state,c.testing_place,
                  COALESCE(string_agg(DISTINCT selection.project_name_snapshot,'、'
                    ORDER BY selection.project_name_snapshot),'') detection_projects,
                  (SELECT count(*) FROM commission_sample_group sample_group
                    WHERE sample_group.commission_id=c.id) sample_group_count,
                  (SELECT count(*) FROM commission_parameter_selection parameter
                    JOIN commission_detection_selection detection ON detection.id=parameter.detection_selection_id
                    WHERE detection.commission_id=c.id) parameter_count,c.created_at
                FROM commission c JOIN project p ON p.id=c.project_id
                JOIN customer ON customer.id=c.customer_id
                LEFT JOIN commission_detection_selection selection ON selection.commission_id=c.id
                WHERE c.organization_id=:organization AND c.id IN (:ids)
                GROUP BY c.id,p.number,p.name,customer.name
                ORDER BY c.created_at DESC,c.id DESC
                """).param("organization", organizationId).param("ids", ids)
                .query((rs, row) -> new ExportRow(rs.getObject("id", UUID.class), rs.getString("number"),
                        rs.getString("precommission_number"), rs.getLong("management_sequence"),
                        rs.getObject("internal_sequence", Long.class), rs.getString("external_number"),
                        rs.getString("project_number"), rs.getString("project_name"), rs.getString("customer_name"),
                        rs.getObject("commissioned_on", LocalDate.class), rs.getString("state"),
                        rs.getString("testing_place"), rs.getString("detection_projects"),
                        rs.getLong("sample_group_count"), rs.getLong("parameter_count"),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
        if (rows.size() != ids.size()) throw new IllegalStateException("export scope changed after the job was queued");
        StoredFileService.View stored = files.storeGenerated(workbook(rows),
                "委托清单-" + OffsetDateTime.now().toLocalDate() + ".xlsx", XLSX, "COMMISSION_EXPORT", actorId);
        String details = json.writeValueAsString(
                Map.of("fileId", stored.id(), "rowCount", rows.size(), "sha256", stored.sha256()));
        return new JobResult(stored.id(), null, null, actorId, organizationId,
                "COMMISSION_EXPORT_COMPLETED", details);
    }

    private JobResult renderDocument(Job job) throws Exception {
        Map<String, Object> payload = payload(job);
        UUID actorId = uuid(payload, "actorId");
        UUID organizationId = uuid(payload, "organizationId");
        UUID commissionId = uuid(payload, "commissionId");
        UUID templateId = uuid(payload, "templateVersionId");
        Template template = jdbc.sql("""
                SELECT id,name,version_number,content_json::text FROM configuration_version
                WHERE id=:id AND configuration_type='COMMISSION_TEMPLATE' AND state IN ('PUBLISHED','RETIRED')
                """).param("id", templateId).query((rs, row) -> new Template(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("version_number"),
                        rs.getString("content_json"))).optional()
                .orElseThrow(() -> new IllegalStateException("queued commission template version no longer exists"));
        Map<String, Object> definition = json.readValue(template.content(), new TypeReference<>() {});
        List<Block> blocks = blocks(definition);
        CommissionDocument data = jdbc.sql("""
                SELECT c.id,c.number,c.precommission_number,p.number project_number,p.name project_name,
                  customer.name customer_name,c.contact_name,c.contact_phone,c.commissioned_on,c.due_on,c.state,
                  c.testing_place,c.project_part,c.monomer,c.remark,
                  COALESCE((SELECT string_agg(DISTINCT selection.project_name_snapshot,'、'
                    ORDER BY selection.project_name_snapshot) FROM commission_detection_selection selection
                    WHERE selection.commission_id=c.id),'') detection_projects
                FROM commission c JOIN project p ON p.id=c.project_id JOIN customer ON customer.id=c.customer_id
                WHERE c.id=:id AND c.organization_id=:organization
                """).param("id", commissionId).param("organization", organizationId)
                .query((rs, row) -> new CommissionDocument(
                        rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("precommission_number"),
                        rs.getString("project_number"), rs.getString("project_name"), rs.getString("customer_name"),
                        rs.getString("contact_name"), rs.getString("contact_phone"),
                        rs.getObject("commissioned_on", LocalDate.class), rs.getObject("due_on", LocalDate.class),
                        rs.getString("state"), rs.getString("testing_place"), rs.getString("detection_projects"),
                        rs.getString("project_part"), rs.getString("monomer"), rs.getString("remark")))
                .optional().orElseThrow(() -> new IllegalStateException("commission is outside the queued scope"));
        StoredFileService.View stored = files.storeGenerated(pdf(template, blocks, data),
                data.number() + "-电子委托单.pdf", "application/pdf", "COMMISSION_DOCUMENT", actorId);
        String details = json.writeValueAsString(Map.of("commissionId", commissionId,
                "templateVersionId", templateId, "fileId", stored.id(), "sha256", stored.sha256()));
        return new JobResult(stored.id(), commissionId, templateId, actorId, organizationId,
                "COMMISSION_DOCUMENT_RENDERED", details);
    }

    private byte[] pdf(Template template, List<Block> blocks, CommissionDocument data) {
        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(true)
                    .setArgs(List.of("--disable-dev-shm-usage", "--no-sandbox", "--disable-gpu"));
            if (executable != null && !executable.isBlank()) launch.setExecutablePath(Path.of(executable));
            try (Browser browser = playwright.chromium().launch(launch)) {
                Page page = browser.newPage();
                page.setContent(documentHtml(template, blocks, data),
                        new Page.SetContentOptions().setWaitUntil(WaitUntilState.LOAD));
                return page.pdf(new Page.PdfOptions().setFormat("A4").setPrintBackground(true)
                        .setMargin(new Margin().setTop("16mm").setRight("15mm").setBottom("18mm").setLeft("15mm")));
            }
        }
    }

    private String documentHtml(Template template, List<Block> blocks, CommissionDocument data) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("number", data.number()); values.put("precommissionNumber", data.precommissionNumber());
        values.put("projectNumber", data.projectNumber()); values.put("projectName", data.projectName());
        values.put("customerName", data.customerName()); values.put("contactName", data.contactName());
        values.put("contactPhone", data.contactPhone()); values.put("commissionedOn", text(data.commissionedOn()));
        values.put("dueOn", text(data.dueOn())); values.put("state", data.state());
        values.put("testingPlace", data.testingPlace()); values.put("detectionProjects", data.detectionProjects());
        values.put("projectPart", data.projectPart()); values.put("monomer", data.monomer());
        values.put("remark", data.remark());
        StringBuilder body = new StringBuilder();
        for (Block block : blocks) {
            if (block.type().equals("HEADING")) body.append("<h2>").append(escape(block.label())).append("</h2>");
            else body.append("<div class=\"field\"><dt>").append(escape(block.label())).append("</dt><dd>")
                    .append(escape(values.getOrDefault(block.key(), ""))).append("</dd></div>");
        }
        return """
                <!doctype html><html><head><meta charset="utf-8"><style>
                @page{size:A4;margin:0}*{box-sizing:border-box}body{font-family:'Noto Sans CJK SC','Microsoft YaHei',sans-serif;color:#15243b;font-size:12px}
                header{text-align:center;border-bottom:2px solid #2265d8;padding:8px 0 15px;margin-bottom:18px}header p{margin:3px;color:#5e6b7e}h1{font-size:22px;margin:8px 0}h2{font-size:14px;color:#174fa8;border-left:3px solid #2f80ed;padding-left:8px;margin:20px 0 8px}
                .field{display:grid;grid-template-columns:32%% 68%%;border:1px solid #d9e1ec;border-bottom:0}.field:last-of-type{border-bottom:1px solid #d9e1ec}dt,dd{margin:0;padding:9px 10px;min-height:34px}dt{background:#f3f7fc;font-weight:600;border-right:1px solid #d9e1ec}dd{white-space:pre-wrap;overflow-wrap:anywhere}
                footer{margin-top:28px;border-top:1px solid #c5cfdd;padding-top:10px;color:#68778b;display:flex;justify-content:space-between;font-size:10px}
                </style></head><body><header><p>澜川智检科技有限公司</p><h1>%s</h1><p>委托编号：%s　模板版本：V%d</p></header>%s<footer><span>内部受控文件</span><span>由试验检测信息管理系统生成</span></footer></body></html>
                """.formatted(escape(template.name()), escape(data.number()), template.version(), body);
    }

    private List<Block> blocks(Map<String, Object> definition) {
        if (!"1".equals(String.valueOf(definition.get("rendererVersion")))) {
            throw new IllegalArgumentException("unsupported commission template rendererVersion");
        }
        Object value = definition.get("blocks");
        if (!(value instanceof List<?> raw) || raw.isEmpty() || raw.size() > 200) {
            throw new IllegalArgumentException("commission template must contain 1 to 200 blocks");
        }
        return raw.stream().map(item -> {
            if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("commission template block is invalid");
            String type = text(map.get("type")).toUpperCase();
            String label = text(map.get("label")).trim();
            String key = text(map.get("key")).trim();
            if (!List.of("HEADING", "FIELD").contains(type) || label.isBlank() || label.length() > 120
                    || (type.equals("FIELD") && !DOCUMENT_FIELDS.contains(key))) {
                throw new IllegalArgumentException("commission template contains an unsupported block");
            }
            return new Block(type, label, key);
        }).toList();
    }

    private byte[] workbook(List<ExportRow> rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("委托清单"); sheet.createFreezePane(0, 1);
            String[] headers = {"委托编号", "预委托编号", "管理序号", "内部流水", "外部编号", "工程编号", "工程名称",
                    "委托单位", "委托日期", "状态", "检测地点", "检测项目", "样品组数", "参数数", "创建时间"};
            CellStyle header = workbook.createCellStyle(); var font = workbook.createFont(); font.setBold(true);
            header.setFont(font); header.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND); Row first = sheet.createRow(0);
            for (int column = 0; column < headers.length; column++) {
                first.createCell(column).setCellValue(headers[column]); first.getCell(column).setCellStyle(header);
            }
            int index = 1;
            for (ExportRow value : rows) {
                Row row = sheet.createRow(index++);
                String[] cells = {value.number(), value.precommissionNumber(), String.valueOf(value.managementSequence()),
                        value.internalSequence() == null ? "" : String.valueOf(value.internalSequence()),
                        value.externalNumber(), value.projectNumber(), value.projectName(), value.customerName(),
                        text(value.commissionedOn()), value.state(), value.testingPlace(), value.detectionProjects(),
                        String.valueOf(value.sampleGroupCount()), String.valueOf(value.parameterCount()), text(value.createdAt())};
                for (int column = 0; column < cells.length; column++) {
                    row.createCell(column).setCellValue(cells[column] == null ? "" : cells[column]);
                }
            }
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rows.size()), 0, headers.length - 1));
            int[] widths = {22, 22, 12, 12, 18, 18, 28, 28, 14, 14, 14, 36, 12, 12, 24};
            for (int column = 0; column < widths.length; column++) sheet.setColumnWidth(column, widths[column] * 256);
            workbook.write(output); return output.toByteArray();
        }
    }

    private void complete(Job job, JobResult result) {
        if (job.jobType().equals("RENDER_COMMISSION_DOCUMENT")) {
            int revision = jdbc.sql("SELECT COALESCE(max(revision),0)+1 FROM commission_electronic_document WHERE commission_id=:commission")
                    .param("commission", result.commissionId()).query(Integer.class).single();
            jdbc.sql("""
                    INSERT INTO commission_electronic_document(id,commission_id,revision,template_version_id,
                      file_id,job_id,created_by) VALUES (:id,:commission,:revision,:template,:file,:job,:actor)
                    """).param("id", UUID.randomUUID()).param("commission", result.commissionId())
                    .param("revision", revision).param("template", result.templateId()).param("file", result.fileId())
                    .param("job", job.id()).param("actor", result.actorId()).update();
        }
        int changed = jdbc.sql("""
                UPDATE background_job SET state='COMPLETED',result_file_id=:file,completed_at=now(),
                  last_error=NULL,locked_by=NULL,locked_at=NULL,version=version+1
                WHERE id=:id AND locked_by=:worker AND state='RUNNING'
                """).param("file", result.fileId()).param("id", job.id()).param("worker", workerId).update();
        if (changed != 1) throw new IllegalStateException("commission job ownership changed before completion");
        jdbc.sql("""
                INSERT INTO audit_event(id,request_id,actor_id,actor_name,action,resource_type,organization_id,after_json)
                SELECT :id,:requestId,user_account.id,user_account.display_name,:action,
                  'BACKGROUND_JOB',:organization,CAST(:afterJson AS jsonb)
                FROM app_user user_account WHERE user_account.id=:actor
                """).param("id", UUID.randomUUID()).param("requestId", "job:" + job.id())
                .param("action", result.auditAction()).param("afterJson", result.detailsJson())
                .param("actor", result.actorId()).param("organization", result.organizationId()).update();
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

    private Map<String, Object> payload(Job job) throws Exception { return json.readValue(job.payload(), new TypeReference<>() {}); }
    private UUID uuid(Map<String, Object> payload, String key) { return UUID.fromString(String.valueOf(payload.get(key))); }
    private List<UUID> uuidList(Map<String, Object> payload, String key, int max) {
        Object values = payload.get(key);
        if (!(values instanceof List<?> raw) || raw.isEmpty() || raw.size() > max) {
            throw new IllegalArgumentException(key + " must contain 1 to " + max + " values");
        }
        List<UUID> ids = raw.stream().map(String::valueOf).map(UUID::fromString).distinct().toList();
        if (ids.size() != raw.size()) throw new IllegalArgumentException(key + " contains duplicates");
        return ids;
    }
    private String escape(String value) { return text(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private record Job(UUID id, String jobType, String payload, int attempt) {}
    private record JobResult(UUID fileId, UUID commissionId, UUID templateId, UUID actorId, UUID organizationId,
                             String auditAction, String detailsJson) {}
    private record Template(UUID id, String name, int version, String content) {}
    private record Block(String type, String label, String key) {}
    private record CommissionDocument(UUID id, String number, String precommissionNumber, String projectNumber,
                                      String projectName, String customerName, String contactName, String contactPhone,
                                      LocalDate commissionedOn, LocalDate dueOn, String state, String testingPlace,
                                      String detectionProjects, String projectPart, String monomer, String remark) {}
    private record ExportRow(UUID id, String number, String precommissionNumber, long managementSequence,
                             Long internalSequence, String externalNumber, String projectNumber, String projectName,
                             String customerName, LocalDate commissionedOn, String state, String testingPlace,
                             String detectionProjects, long sampleGroupCount, long parameterCount,
                             OffsetDateTime createdAt) {}
}
