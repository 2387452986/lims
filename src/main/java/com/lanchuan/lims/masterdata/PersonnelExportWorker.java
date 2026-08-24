package com.lanchuan.lims.masterdata;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PersonnelExportWorker {
    private static final Logger log = LoggerFactory.getLogger(PersonnelExportWorker.class);
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final StoredFileService files;
    private final String workerId;

    public PersonnelExportWorker(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper json,
                                 StoredFileService files,
                                 @Value("${lims.jobs.worker-id:${HOSTNAME:local}-personnel-worker}") String workerId) {
        this.jdbc = jdbc; this.transactions = transactions; this.json = json; this.files = files; this.workerId = workerId;
    }

    @Scheduled(fixedDelayString = "${lims.jobs.personnel-poll-delay-ms:1500}")
    void poll() {
        Job job = transactions.execute(status -> claim());
        if (job == null) return;
        try {
            Result result = export(job);
            transactions.executeWithoutResult(status -> complete(job, result));
        } catch (Exception exception) {
            log.error("Personnel export job {} failed", job.id(), exception);
            transactions.executeWithoutResult(status -> fail(job.id(), exception));
        }
    }

    private Job claim() {
        return jdbc.sql("""
                WITH candidate AS (
                  SELECT id FROM background_job WHERE job_type='EXPORT_PERSONNEL_XLSX'
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
        UUID actorId = uuid(payload.get("actorId")); UUID organizationId = uuid(payload.get("organizationId"));
        Map<String, Object> filters = (Map<String, Object>) payload.get("filters");
        String actorName = jdbc.sql("SELECT display_name FROM app_user WHERE id=:actor AND organization_id=:organization")
                .param("actor", actorId).param("organization", organizationId).query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("queued personnel export actor no longer exists"));
        List<String[]> rows = rows(organizationId, filters == null ? Map.of() : filters);
        byte[] content = workbook(rows, actorName);
        StoredFileService.View stored = files.storeGenerated(content, "人员信息-脱敏-" + LocalDate.now() + ".xlsx",
                XLSX, "PERSON_EXPORT", actorId);
        return new Result(stored.id(), actorId, organizationId, rows.size(), stored.sha256());
    }

    private List<String[]> rows(UUID organization, Map<String, Object> filter) {
        String account = upper(filter.get("accountState")); String application = upper(filter.get("applicationState"));
        String type = upper(filter.get("personType")); String name = like(filter.get("name"));
        String position = like(filter.get("position"));
        List<String[]> result = jdbc.sql("""
                SELECT p.employee_number,p.name,d.name department_name,u.username,u.enabled,u.locked_until,
                  p.gender,p.phone,p.national_id_last4,p.birth_date,p.title,p.position,p.person_type,
                  p.application_state,p.identity_state,p.electronic_signature_state,p.active,
                  (SELECT count(*) FROM person_qualification q WHERE q.person_id=p.id) qualification_count,
                  (SELECT count(*) FROM person_detection_capability c WHERE c.person_id=p.id) capability_count,
                  COALESCE((SELECT string_agg(r.code, ',' ORDER BY r.code) FROM app_role r
                    JOIN user_role ur ON ur.role_id=r.id WHERE ur.user_id=u.id),'') role_codes
                FROM person p JOIN department d ON d.id=p.department_id LEFT JOIN app_user u ON u.person_id=p.id
                WHERE d.organization_id=:organization
                  AND (:account='' OR (:account='NO_ACCOUNT' AND u.id IS NULL)
                    OR (:account='LOCKED' AND u.locked_until>now())
                    OR (:account='ENABLED' AND u.enabled AND (u.locked_until IS NULL OR u.locked_until<=now()))
                    OR (:account='DISABLED' AND NOT u.enabled))
                  AND (:application='' OR p.application_state=:application)
                  AND (:personType='' OR p.person_type=:personType)
                  AND (:name='%%' OR p.name ILIKE :name ESCAPE '\\' OR p.employee_number ILIKE :name ESCAPE '\\'
                    OR COALESCE(u.username,'') ILIKE :name ESCAPE '\\')
                  AND (:position='%%' OR COALESCE(p.position,'') ILIKE :position ESCAPE '\\'
                    OR COALESCE(p.title,'') ILIKE :position ESCAPE '\\')
                ORDER BY p.employee_number,p.id LIMIT 50001
                """).param("organization", organization).param("account", account).param("application", application)
                .param("personType", type).param("name", name).param("position", position)
                .query((rs, row) -> new String[]{rs.getString("employee_number"), rs.getString("name"),
                        rs.getString("department_name"), value(rs.getString("username")), rs.getString("role_codes"),
                        accountState(rs.getString("username"), rs.getBoolean("enabled"),
                                rs.getObject("locked_until", OffsetDateTime.class)),
                        rs.getString("gender"), maskPhone(rs.getString("phone")),
                        rs.getString("national_id_last4") == null ? "" : "**************" + rs.getString("national_id_last4"),
                        value(rs.getObject("birth_date", LocalDate.class)), value(rs.getString("title")),
                        value(rs.getString("position")), rs.getString("person_type"), rs.getString("application_state"),
                        rs.getString("identity_state"), rs.getString("electronic_signature_state"),
                        rs.getBoolean("active") ? "ACTIVE" : "INACTIVE", String.valueOf(rs.getLong("qualification_count")),
                        String.valueOf(rs.getLong("capability_count"))}).list();
        if (result.size() > 50_000) throw new IllegalStateException("导出结果超过50000行，请缩小查询范围");
        return result;
    }

    private byte[] workbook(List<String[]> rows, String actorName) throws Exception {
        List<String> headers = List.of("工号","姓名","部门","登录账号","角色","账号状态","性别","联系电话（脱敏）",
                "身份证件（脱敏）","出生日期","职称","职务","人员类型","申报状态","身份核验","电子签名",
                "在岗状态","资格数","检测能力数");
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true); var sheet = workbook.createSheet("人员信息");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, headers.size() - 1));
            var watermark = sheet.createRow(0).createCell(0);
            watermark.setCellValue("内部受控脱敏导出 · " + actorName + " · " + LOCAL_TIME.format(OffsetDateTime.now()));
            CellStyle watermarkStyle = workbook.createCellStyle(); var watermarkFont = workbook.createFont();
            watermarkFont.setBold(true); watermarkFont.setColor(IndexedColors.RED.getIndex());
            watermarkStyle.setFont(watermarkFont); watermark.setCellStyle(watermarkStyle);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var header = sheet.createRow(1);
            for (int column = 0; column < headers.size(); column++) {
                var cell = header.createCell(column); cell.setCellValue(headers.get(column)); cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(column, Math.min(34, Math.max(14, headers.get(column).length() + 7)) * 256);
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                var row = sheet.createRow(rowIndex + 2); String[] values = rows.get(rowIndex);
                for (int column = 0; column < values.length; column++) row.createCell(column).setCellValue(value(values[column]));
            }
            sheet.createFreezePane(0, 2); workbook.write(output); workbook.dispose(); return output.toByteArray();
        }
    }

    private void complete(Job job, Result result) {
        int changed = jdbc.sql("""
                UPDATE background_job SET state='COMPLETED',result_file_id=:file,completed_at=now(),
                  last_error=NULL,locked_by=NULL,locked_at=NULL,version=version+1
                WHERE id=:id AND locked_by=:worker AND state='RUNNING'
                """).param("file", result.fileId()).param("id", job.id()).param("worker", workerId).update();
        if (changed != 1) throw new IllegalStateException("personnel export job ownership changed before completion");
        try {
            String details = json.writeValueAsString(Map.of("fileId", result.fileId(), "rowCount", result.rowCount(),
                    "sha256", result.sha256(), "masked", true));
            jdbc.sql("""
                    INSERT INTO audit_event(id,request_id,actor_id,actor_name,action,resource_type,
                      resource_id,organization_id,after_json)
                    SELECT :audit,:request,user_account.id,user_account.display_name,
                      'PERSONNEL_EXPORT_COMPLETED','BACKGROUND_JOB',:resource,:organization,CAST(:details AS jsonb)
                    FROM app_user user_account WHERE user_account.id=:actor
                    """).param("audit", UUID.randomUUID()).param("request", "job:" + job.id())
                    .param("resource", job.id().toString()).param("organization", result.organizationId())
                    .param("details", details).param("actor", result.actorId()).update();
        } catch (Exception exception) { throw new IllegalStateException("personnel export audit encoding failed", exception); }
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

    private String upper(Object value) { return value == null ? "" : String.valueOf(value).trim().toUpperCase(); }
    private String like(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
    private UUID uuid(Object value) { return UUID.fromString(String.valueOf(value)); }
    private String accountState(String username, boolean enabled, OffsetDateTime locked) {
        return username == null ? "NO_ACCOUNT" : locked != null && locked.isAfter(OffsetDateTime.now())
                ? "LOCKED" : enabled ? "ENABLED" : "DISABLED";
    }
    private String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return "";
        return phone.length() <= 7 ? "***" + phone.substring(Math.max(0, phone.length() - 2))
                : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
    private String value(Object value) { return value == null ? "" : String.valueOf(value); }
    private record Job(UUID id, String payload) {}
    private record Result(UUID fileId, UUID actorId, UUID organizationId, int rowCount, String sha256) {}
}
