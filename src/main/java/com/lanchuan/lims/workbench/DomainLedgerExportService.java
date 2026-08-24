package com.lanchuan.lims.workbench;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.report.ReportQueueService;
import com.lanchuan.lims.sample.SampleLedgerService;
import com.lanchuan.lims.task.TaskQueueService;
import com.lanchuan.lims.test.TestLedgerService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class DomainLedgerExportService {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final ObjectMapper json;
    private final SampleLedgerService samples;
    private final TaskQueueService tasks;
    private final TestLedgerService tests;
    private final ReportQueueService reports;

    public DomainLedgerExportService(JdbcClient jdbc, CurrentActor actor, AuditService audit, ObjectMapper json,
                                     SampleLedgerService samples, TaskQueueService tasks,
                                     TestLedgerService tests, ReportQueueService reports) {
        this.jdbc = jdbc; this.actor = actor; this.audit = audit; this.json = json;
        this.samples = samples; this.tasks = tasks; this.tests = tests; this.reports = reports;
    }

    public record JobQueued(UUID jobId, String jobType, String state) {}

    @Transactional
    public JobQueued enqueueSample(SampleLedgerService.Filters request, String key) {
        samples.list(new SampleLedgerService.Filters(request.view(), request.q(), request.state(), request.from(),
                request.to(), "", 1));
        return enqueue("SAMPLE_LEDGER", request, key);
    }

    @Transactional
    public JobQueued enqueueTask(TaskQueueService.Filters request, String key) {
        tasks.list(new TaskQueueService.Filters(request.view(), request.q(), request.state(), request.dueFrom(),
                request.dueTo(), "", 1));
        return enqueue("TASK_QUEUE", request, key);
    }

    @Transactional
    public JobQueued enqueueTest(TestLedgerService.Filters request, String key) {
        tests.list(new TestLedgerService.Filters(request.view(), request.q(), request.state(), request.result(),
                request.from(), request.to(), "", 1));
        return enqueue("TEST_LEDGER", request, key);
    }

    @Transactional
    public JobQueued enqueueReport(ReportQueueService.Filters request, String key) {
        reports.list(new ReportQueueService.Filters(request.view(), request.q(), request.states(), request.from(),
                request.to(), "", 1));
        return enqueue("REPORT_QUEUE", request, key);
    }

    private JobQueued enqueue(String domain, Object request, String key) {
        AuthenticatedUser user = actor.require();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("domain", domain); payload.put("actorId", user.id()); payload.put("personId", user.personId());
        payload.put("organizationId", user.organizationId()); payload.put("departmentId", user.departmentId());
        payload.put("dataScope", user.dataScope()); payload.put("request", request);
        UUID id = UUID.randomUUID();
        String encoded = encode(payload);
        int inserted = jdbc.sql("""
                INSERT INTO background_job(id,job_type,payload,idempotency_key,state,created_by,resource_type)
                VALUES (:id,'EXPORT_DOMAIN_LEDGER_XLSX',CAST(:payload AS jsonb),:key,'READY',:actor,:resource)
                ON CONFLICT (idempotency_key) DO NOTHING
                """).param("id", id).param("payload", encoded).param("key", key)
                .param("actor", user.id()).param("resource", domain).update();
        if (inserted == 0) {
            return jdbc.sql("""
                    SELECT id,job_type,state FROM background_job
                    WHERE idempotency_key=:key AND created_by=:actor AND resource_type=:resource
                      AND payload=CAST(:payload AS jsonb)
                    """).param("key", key).param("actor", user.id()).param("resource", domain)
                    .param("payload", encoded).query((rs, row) -> new JobQueued(
                            rs.getObject("id", UUID.class), rs.getString("job_type"), rs.getString("state")))
                    .optional().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                            "IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同的导出请求"));
        }
        JobQueued queued = new JobQueued(id, "EXPORT_DOMAIN_LEDGER_XLSX", "READY");
        audit.record("DOMAIN_LEDGER_EXPORT_QUEUED", "BACKGROUND_JOB", id, null,
                Map.of("job", queued, "domain", domain), null, "领域台账受控导出已排队");
        return queued;
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("无法编码领域导出条件", exception); }
    }
}
