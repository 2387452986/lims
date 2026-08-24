package com.lanchuan.lims.workbench;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class QualityLedgerExportService {
    private static final Set<String> TYPES = Set.of("OVERDUE_SPECIMEN", "REBOUND", "FIELD_TEST");
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final ObjectMapper json;
    private final QualityLedgerService ledgers;

    public QualityLedgerExportService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                      ObjectMapper json, QualityLedgerService ledgers) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.json = json;
        this.ledgers = ledgers;
    }

    public record ExportRequest(String ledgerType, LocalDate from, LocalDate to, String region,
                                String detectionProject, String commissionNumber, String sampleNumber,
                                String reportNumber, String projectNumber, String projectName,
                                String customerName, String conclusion, String taskState,
                                String curingMethod, String strengthGrade, String progress,
                                String taskNumber, String testerName) {}
    public record JobQueued(UUID jobId, String jobType, String state) {}

    @Transactional
    public JobQueued enqueue(ExportRequest request, String idempotencyKey) {
        if (request == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                "QUALITY_EXPORT_REQUEST_REQUIRED", "导出请求不能为空");
        String type = request.ledgerType() == null ? "" : request.ledgerType().trim().toUpperCase();
        if (!TYPES.contains(type)) throw new ApiException(HttpStatus.BAD_REQUEST,
                "QUALITY_EXPORT_TYPE_INVALID", "质量台账导出类型无效");
        validate(type, request);
        AuthenticatedUser user = actor.require();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actorId", user.id()); payload.put("personId", user.personId());
        payload.put("organizationId", user.organizationId()); payload.put("departmentId", user.departmentId());
        payload.put("dataScope", user.dataScope()); payload.put("request", request);
        UUID jobId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO background_job(id,job_type,payload,idempotency_key,state,created_by,resource_type)
                VALUES (:id,'EXPORT_QUALITY_LEDGER_XLSX',CAST(:payload AS jsonb),:key,'READY',:actor,:resource)
                """).param("id", jobId).param("payload", encode(payload)).param("key", idempotencyKey)
                .param("actor", user.id()).param("resource", "QUALITY_LEDGER_" + type).update();
        JobQueued queued = new JobQueued(jobId, "EXPORT_QUALITY_LEDGER_XLSX", "READY");
        audit.record("QUALITY_LEDGER_EXPORT_QUEUED", "BACKGROUND_JOB", jobId, null,
                Map.of("job", queued, "ledgerType", type), null, "受控质量台账导出已排队");
        return queued;
    }

    private void validate(String type, ExportRequest request) {
        if (type.equals("OVERDUE_SPECIMEN")) {
            ledgers.specimens(new QualityLedgerService.SpecimenFilters("OVERDUE", request.from(), request.to(),
                    request.region(), request.detectionProject(), request.commissionNumber(), request.sampleNumber(),
                    request.reportNumber(), request.projectNumber(), request.projectName(), request.customerName(),
                    request.conclusion(), request.taskState(), request.curingMethod(), request.strengthGrade(), "", 1));
        } else if (type.equals("REBOUND")) {
            ledgers.rebounds(new QualityLedgerService.ReboundFilters(request.from(), request.to(),
                    request.commissionNumber(), request.sampleNumber(), request.projectNumber(), request.projectName(),
                    request.customerName(), "", 1));
        } else {
            ledgers.fieldTests(new QualityLedgerService.FieldTestFilters(request.from(), request.to(), request.progress(),
                    request.detectionProject(), request.taskNumber(), request.projectName(), request.testerName(), "", 1));
        }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("无法编码质量台账导出条件", exception); }
    }
}
