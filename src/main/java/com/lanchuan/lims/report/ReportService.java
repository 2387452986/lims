package com.lanchuan.lims.report;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.configuration.NumberingService;
import com.lanchuan.lims.configuration.DocumentControlSnapshotService;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.DataScopeService;
import com.lanchuan.lims.identity.RecentPasswordService;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReportService {
    private static final SecureRandom TOKENS = new SecureRandom();
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final NumberingService numbering;
    private final AuditService audit;
    private final ObjectMapper json;
    private final DataScopeService access;
    private final SigningAssetService signingAssets;
    private final SignatureSchemeService signatureSchemes;
    private final RecentPasswordService recentPassword;
    private final ReportRevisionPolicy revisionPolicy;
    private final DocumentControlSnapshotService documentControls;

    public ReportService(JdbcClient jdbc, CurrentActor actor, NumberingService numbering,
                         AuditService audit, ObjectMapper json, DataScopeService access,
                         SigningAssetService signingAssets, SignatureSchemeService signatureSchemes,
                         RecentPasswordService recentPassword, ReportRevisionPolicy revisionPolicy,
                         DocumentControlSnapshotService documentControls) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.numbering = numbering;
        this.audit = audit;
        this.json = json;
        this.access = access;
        this.signingAssets = signingAssets;
        this.signatureSchemes = signatureSchemes;
        this.recentPassword = recentPassword;
        this.revisionPolicy = revisionPolicy;
        this.documentControls = documentControls;
    }

    public record Create(UUID commissionId, String title, Map<String, Object> content) {}
    public record Update(Map<String, Object> content, long expectedVersion) {}
    public record Transition(String action, long expectedVersion, String reasonCode, String comment) {}
    public record Distribution(String recipient, String method, String copyNumber, long expectedVersion) {}
    public record Acknowledgement(String acknowledgedBy, long expectedVersion) {}
    public record Revision(long expectedVersion, String reasonCode, String comment) {}
    public record PrintAuthorization(UUID reportId, UUID reportVersionId, UUID fileId, String downloadPath,
                                     String downloadToken, OffsetDateTime expiresAt, long usedCount,
                                     Integer remainingCount) {}
    public record DistributionView(UUID id, UUID reportVersionId, String recipient, String method,
                                   String copyNumber, OffsetDateTime distributedAt, String acknowledgedBy,
                                   OffsetDateTime acknowledgedAt, long version) {}
    public record View(UUID id, UUID commissionId, String number, String title, int currentRevision,
                       String revisionLabel, ReportState state, Map<String, Object> content, UUID pdfFileId,
                       boolean pdfFinalized, boolean signed, boolean sealed, long version, OffsetDateTime updatedAt) {}

    @Transactional
    public View create(Create body) {
        access.requireCommission(body.commissionId());
        UUID id = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO report(id, commission_id, number, title, state, created_by)
                SELECT :id, c.id, :number, :title, 'DRAFT', :actor FROM commission c
                WHERE c.id=:commission AND c.organization_id=:organization AND c.state='REPORTING'
                """).param("id", id).param("number", numbering.next("REPORT", "BG"))
                .param("title", body.title()).param("actor", actor.require().id())
                .param("commission", body.commissionId()).param("organization", actor.require().organizationId()).update();
        if (inserted == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "REPORT_COMMISSION_STATE_INVALID", "委托完成检测后才能编制报告");
        }
        jdbc.sql("""
                INSERT INTO report_version(id, report_id, revision, revision_label, content_json)
                VALUES (:id, :report, 0, 'R0', CAST(:content AS jsonb))
                """).param("id", versionId).param("report", id).param("content", encode(body.content())).update();
        documentControls.snapshotReport(versionId, body.commissionId());
        View created = require(id);
        audit.record("REPORT_CREATED", "REPORT", id, null, created, null, null);
        return created;
    }

    public List<View> list(UUID commissionId) {
        if (commissionId != null) access.requireCommission(commissionId);
        if (commissionId == null) {
            List<View> views = jdbc.sql(baseSelect() + " WHERE c.organization_id=:organization ORDER BY r.updated_at DESC")
                    .param("organization", actor.require().organizationId()).query(this::map).list();
            if (List.of("ALL", "ORGANIZATION").contains(actor.require().dataScope())) return views;
            return views.stream().filter(view -> {
                try { access.requireCommission(view.commissionId()); return true; }
                catch (ApiException ignored) { return false; }
            }).toList();
        }
        return jdbc.sql(baseSelect() + " WHERE c.organization_id=:organization AND r.commission_id=:commission ORDER BY r.updated_at DESC")
                .param("organization", actor.require().organizationId()).param("commission", commissionId).query(this::map).list();
    }

    public List<DistributionView> distributions(UUID reportId) {
        require(reportId);
        return jdbc.sql("""
                SELECT d.id, d.report_version_id, d.recipient, d.method, d.copy_number, d.distributed_at,
                  d.acknowledged_by, d.acknowledged_at, d.version
                FROM report_distribution d JOIN report_version v ON v.id=d.report_version_id
                JOIN report r ON r.id=v.report_id JOIN commission c ON c.id=r.commission_id
                WHERE r.id=:report AND v.revision=r.current_revision AND c.organization_id=:organization
                ORDER BY d.distributed_at DESC
                """).param("report", reportId).param("organization", actor.require().organizationId())
                .query((rs, row) -> new DistributionView(rs.getObject("id", UUID.class),
                        rs.getObject("report_version_id", UUID.class), rs.getString("recipient"),
                        rs.getString("method"), rs.getString("copy_number"),
                        rs.getObject("distributed_at", OffsetDateTime.class), rs.getString("acknowledged_by"),
                        rs.getObject("acknowledged_at", OffsetDateTime.class), rs.getLong("version"))).list();
    }

    public View require(UUID id) {
        View view = jdbc.sql(baseSelect() + " WHERE r.id=:id AND c.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId()).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("REPORT_NOT_FOUND", "报告不存在"));
        access.requireCommission(view.commissionId());
        return view;
    }

    @Transactional
    public PrintAuthorization authorizePrint(UUID id, long expectedVersion) {
        View report = require(id);
        long lockedVersion = jdbc.sql("SELECT version FROM report WHERE id=:id FOR UPDATE")
                .param("id", id).query(Long.class).single();
        if (lockedVersion != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT", "报告已被其他用户更新", lockedVersion);
        }
        if (report.pdfFileId() == null) throw ApiException.blocked("REPORT_PDF_PENDING",
                "受控 PDF 尚未生成", report.version(), List.of("请等待后台 PDF 作业完成"));
        if (report.state() == ReportState.VOID) throw ApiException.blocked("REPORT_PRINT_VOID",
                "已作废报告不能打印或下载", report.version(), List.of("请打开有效修订版本"));
        PrintPolicy policy = jdbc.sql("""
                SELECT commission.report_print_restricted,commission.report_print_limit
                FROM commission WHERE commission.id=:commission FOR UPDATE
                """).param("commission", report.commissionId()).query((rs, row) -> new PrintPolicy(
                        rs.getBoolean("report_print_restricted"), rs.getObject("report_print_limit", Integer.class)))
                .single();
        UUID versionId = currentVersionId(id, report.currentRevision());
        long used = jdbc.sql("""
                SELECT count(*) FROM report_print_authorization authorization
                WHERE authorization.report_id=:report AND authorization.report_version_id=:version
                  AND (authorization.consumed_at IS NOT NULL OR authorization.expires_at>now())
                """).param("report", id).param("version", versionId).query(Long.class).single();
        if (policy.restricted() && (policy.limit() == null || used >= policy.limit())) {
            String detail = policy.limit() == null ? "委托已设置禁止打印" : "允许次数已用完：" + used + "/" + policy.limit();
            throw ApiException.blocked("REPORT_PRINT_LIMIT_REACHED", "报告打印受到委托策略限制",
                    report.version(), List.of(detail));
        }
        byte[] random = new byte[32];
        TOKENS.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(2);
        jdbc.sql("""
                INSERT INTO report_print_authorization(id,report_id,report_version_id,file_id,token_hash,
                  authorized_by,expires_at) VALUES (:id,:report,:version,:file,:token,:actor,:expiresAt)
                """).param("id", UUID.randomUUID()).param("report", id).param("version", versionId)
                .param("file", report.pdfFileId()).param("token", sha256(token))
                .param("actor", actor.require().id()).param("expiresAt", expiresAt).update();
        Integer remaining = policy.restricted() ? policy.limit() - Math.toIntExact(used) - 1 : null;
        PrintAuthorization result = new PrintAuthorization(id, versionId, report.pdfFileId(),
                "/api/files/" + report.pdfFileId(), token, expiresAt, used, remaining);
        Map<String, Object> auditDetails = new java.util.LinkedHashMap<>();
        auditDetails.put("reportVersionId", versionId);
        auditDetails.put("fileId", report.pdfFileId());
        auditDetails.put("expiresAt", expiresAt);
        auditDetails.put("usedCount", used);
        auditDetails.put("remainingCount", remaining);
        audit.record("REPORT_PRINT_AUTHORIZED", "REPORT", id, null, auditDetails, null,
                policy.restricted() ? "委托打印限制已执行" : "受控报告下载");
        return result;
    }

    @Transactional
    public View update(UUID id, Update body) {
        View before = require(id);
        if (!List.of(ReportState.DRAFT, ReportState.RETURNED).contains(before.state())) {
            throw new ApiException(HttpStatus.CONFLICT, "REPORT_CONTENT_IMMUTABLE", "当前状态不能修改报告内容");
        }
        if (before.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "报告已被其他用户更新", before.version());
        }
        int updated = jdbc.sql("""
                UPDATE report SET version=version+1, updated_at=now() WHERE id=:id AND version=:version
                """).param("id", id).param("version", before.version()).update();
        if (updated == 0) throw ApiException.conflict("VERSION_CONFLICT", "报告已被其他用户更新", require(id).version());
        jdbc.sql("""
                UPDATE report_version SET content_json=CAST(:content AS jsonb)
                WHERE report_id=:report AND revision=:revision AND signed_at IS NULL
                """).param("content", encode(body.content())).param("report", id).param("revision", before.currentRevision()).update();
        View after = require(id);
        audit.record("REPORT_CONTENT_UPDATED", "REPORT", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TransitionResult transition(UUID id, Transition command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "报告已被其他用户更新", before.version());
        }
        String action = command.action().trim().toUpperCase();
        if (action.equals("DISTRIBUTE")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DISTRIBUTION_DETAILS_REQUIRED",
                    "请通过报告发放接口提交收件人和受控副本信息");
        }
        checkPermission(action);
        ReportState next = before.state().transition(action);
        List<String> blockers = blockers(before, action);
        if (!blockers.isEmpty()) throw ApiException.blocked("REPORT_BLOCKED", "报告尚不满足迁移条件", before.version(), blockers);
        if (action.equals("VOID") && (command.comment() == null || command.comment().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VOID_REASON_REQUIRED", "作废报告必须填写原因");
        }
        int updated = jdbc.sql("""
                UPDATE report SET state=:state, version=version+1, updated_at=now()
                WHERE id=:id AND version=:version
                """).param("state", next.name()).param("id", id).param("version", before.version()).update();
        if (updated == 0) throw ApiException.conflict("VERSION_CONFLICT", "报告已被其他用户更新", require(id).version());
        UUID versionId = currentVersionId(id, before.currentRevision());
        SignatureSchemeService.SealContext sealContext = action.equals("SEAL")
                ? signatureSchemes.sealContext(issuedSignatureAssetId(versionId)) : null;
        UUID signingAssetId = action.equals("ISSUE") ? signingAssets.activeSignatureId()
                : action.equals("SEAL") ? sealContext == null ? signingAssets.activeSealId() : sealContext.sealAssetId() : null;
        if (List.of("APPROVE", "RETURN", "ISSUE", "SEAL").contains(action)) {
            approval(versionId, action, command.comment(), signingAssetId);
        }
        if (action.equals("APPROVE")) enqueuePdf(versionId, false);
        if (action.equals("ISSUE")) {
            String content = encode(before.content());
            jdbc.sql("""
                    UPDATE report_version SET signed_at=now(), signed_by=:actor, content_sha256=:sha
                    WHERE id=:id AND signed_at IS NULL
                    """).param("actor", actor.require().id()).param("sha", sha256(content)).param("id", versionId).update();
        }
        if (action.equals("SEAL")) {
            jdbc.sql("""
                    UPDATE report_version SET sealed_at=now(), signature_scheme_id=:scheme
                    WHERE id=:id AND sealed_at IS NULL
                    """).param("scheme", sealContext == null ? null : sealContext.schemeId())
                    .param("id", versionId).update();
            enqueuePdf(versionId, true);
        }
        if (action.equals("ARCHIVE")) archive(id, versionId);
        View after = require(id);
        audit.record("REPORT_" + action, "REPORT", id, before, after, command.reasonCode(), command.comment());
        return new TransitionResult(after.state().name(), after.version(), after.state().allowedActions(after.sealed()));
    }

    @Transactional
    public View distribute(UUID id, Distribution body) {
        View before = require(id);
        if (before.version() != body.expectedVersion()) throw ApiException.conflict("VERSION_CONFLICT", "报告已被更新", before.version());
        if (before.state() != ReportState.ISSUED || !before.sealed()) {
            throw new ApiException(HttpStatus.CONFLICT, "REPORT_NOT_DISTRIBUTABLE", "报告须完成签发和签章后才能发放");
        }
        if (!before.pdfFinalized()) {
            throw ApiException.blocked("FINAL_PDF_PENDING", "签名盖章版 PDF 尚未生成，暂不能发放",
                    before.version(), List.of("请等待后台作业完成最终受控 PDF 生成"));
        }
        if (!List.of("IN_PERSON", "INTERNAL_DOWNLOAD", "CONTROLLED_COPY").contains(body.method())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DISTRIBUTION_METHOD_INVALID", "发放方式无效");
        }
        UUID versionId = currentVersionId(id, before.currentRevision());
        jdbc.sql("""
                INSERT INTO report_distribution(id, report_version_id, recipient, method, copy_number, distributed_by)
                VALUES (:id, :version, :recipient, :method, :copy, :actor)
                """).param("id", UUID.randomUUID()).param("version", versionId).param("recipient", body.recipient())
                .param("method", body.method()).param("copy", body.copyNumber()).param("actor", actor.require().id()).update();
        jdbc.sql("UPDATE report SET state='DISTRIBUTED', version=version+1, updated_at=now() WHERE id=:id AND version=:version")
                .param("id", id).param("version", before.version()).update();
        View after = require(id);
        audit.record("REPORT_DISTRIBUTED", "REPORT", id, before, after, null, body.recipient());
        return after;
    }

    @Transactional
    public DistributionView acknowledge(UUID reportId, UUID distributionId, Acknowledgement command) {
        View report = require(reportId);
        DistributionView before = distributions(reportId).stream().filter(item -> item.id().equals(distributionId))
                .findFirst().orElseThrow(() -> ApiException.notFound("DISTRIBUTION_NOT_FOUND", "报告发放记录不存在"));
        if (before.acknowledgedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "DISTRIBUTION_ALREADY_ACKNOWLEDGED", "该报告发放记录已签收");
        }
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "发放记录已被其他用户更新", before.version());
        }
        int updated = jdbc.sql("""
                UPDATE report_distribution SET acknowledged_by=:acknowledgedBy, acknowledged_at=now(), version=version+1
                WHERE id=:id AND version=:version AND acknowledged_at IS NULL
                """).param("acknowledgedBy", command.acknowledgedBy().trim()).param("id", distributionId)
                .param("version", before.version()).update();
        if (updated == 0) {
            DistributionView current = distributions(reportId).stream().filter(item -> item.id().equals(distributionId))
                    .findFirst().orElseThrow(() -> ApiException.notFound("DISTRIBUTION_NOT_FOUND", "报告发放记录不存在"));
            throw ApiException.conflict("VERSION_CONFLICT", "发放记录已被其他用户更新", current.version());
        }
        DistributionView after = distributions(reportId).stream().filter(item -> item.id().equals(distributionId))
                .findFirst().orElseThrow();
        audit.record("REPORT_DISTRIBUTION_ACKNOWLEDGED", "REPORT_DISTRIBUTION", distributionId,
                before, after, null, "报告 " + report.number());
        return after;
    }

    @Transactional
    public View revise(UUID id, Revision command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict("VERSION_CONFLICT", "报告已被更新", before.version());
        if (!List.of(ReportState.ISSUED, ReportState.DISTRIBUTED, ReportState.ARCHIVED, ReportState.VOID).contains(before.state())) {
            throw new ApiException(HttpStatus.CONFLICT, "REPORT_NOT_REVISABLE", "仅已签发、已归档或已作废报告可以发起修订");
        }
        if (command.comment() == null || command.comment().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REVISION_REASON_REQUIRED", "修订必须填写原因");
        }
        int revision = before.currentRevision() + 1;
        UUID versionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO report_version(id, report_id, revision, revision_label, content_json)
                VALUES (:id, :report, :revision, :label, CAST(:content AS jsonb))
                """).param("id", versionId).param("report", id).param("revision", revision)
                .param("label", revisionPolicy.label(revision)).param("content", encode(before.content())).update();
        documentControls.snapshotReport(versionId, before.commissionId());
        jdbc.sql("""
                UPDATE report SET current_revision=:revision, state='DRAFT', version=version+1, updated_at=now()
                WHERE id=:id AND version=:version
                """).param("revision", revision).param("id", id).param("version", before.version()).update();
        View after = require(id);
        audit.record("REPORT_REVISION_CREATED", "REPORT", id, before, after, command.reasonCode(), command.comment());
        return after;
    }

    private List<String> blockers(View before, String action) {
        List<String> blockers = new ArrayList<>();
        if (List.of("SUBMIT_REVIEW", "RESUBMIT").contains(action)) {
            long unfinished = jdbc.sql("SELECT count(*) FROM test_task WHERE commission_id=:id AND state <> 'COMPLETED'")
                    .param("id", before.commissionId()).query(Long.class).single();
            long openNc = jdbc.sql("SELECT count(*) FROM nonconformance WHERE commission_id=:id AND state NOT IN ('CLOSED','REJECTED')")
                    .param("id", before.commissionId()).query(Long.class).single();
            if (unfinished > 0) blockers.add("存在未完成检测任务：" + unfinished);
            if (openNc > 0) blockers.add("存在未关闭不符合项：" + openNc);
        }
        if (action.equals("APPROVE")) {
            UUID creator = jdbc.sql("SELECT created_by FROM report WHERE id=:id")
                    .param("id", before.id()).query(UUID.class).single();
            if (creator.equals(actor.require().id())) blockers.add("报告编制人不能审核本人编制的报告");
        }
        if (action.equals("ISSUE")) {
            if (before.pdfFileId() == null) blockers.add("受控 PDF 尚未生成");
            if (!passwordRecentlyVerified()) blockers.add("签发前须在5分钟内完成密码复核");
            long ownReviews = jdbc.sql("""
                    SELECT count(*) FROM report_approval a JOIN report_version v ON v.id=a.report_version_id
                    WHERE v.report_id=:report AND a.step='REVIEW' AND a.decision='APPROVED' AND a.actor_id=:actor
                    """).param("report", before.id()).param("actor", actor.require().id()).query(Long.class).single();
            if (ownReviews > 0) blockers.add("报告审核人不能签发自己审核的报告");
            String signingBlocker = signingAssets.signatureBlocker();
            if (signingBlocker != null) blockers.add(signingBlocker);
        }
        if (action.equals("SEAL") && !passwordRecentlyVerified()) {
            blockers.add("签章前须在5分钟内完成密码复核");
        }
        if (action.equals("SEAL")) {
            String sealBlocker = signingAssets.sealBlocker();
            if (sealBlocker != null) blockers.add(sealBlocker);
            UUID versionId = currentVersionId(before.id(), before.currentRevision());
            String schemeBlocker = signatureSchemes.blocker(issuedSignatureAssetId(versionId));
            if (schemeBlocker != null) blockers.add(schemeBlocker);
        }
        if (action.equals("ARCHIVE")) {
            long unacknowledged = jdbc.sql("""
                    SELECT count(*) FROM report_distribution d JOIN report_version v ON v.id=d.report_version_id
                    WHERE v.report_id=:report AND v.revision=:revision
                      AND d.method IN ('IN_PERSON','CONTROLLED_COPY') AND d.acknowledged_at IS NULL
                    """).param("report", before.id()).param("revision", before.currentRevision())
                    .query(Long.class).single();
            if (unacknowledged > 0) blockers.add("存在未签收的受控发放记录：" + unacknowledged);
        }
        return blockers;
    }

    private void checkPermission(String action) {
        AuthenticatedUser user = actor.require();
        String required = switch (action) {
            case "APPROVE", "RETURN" -> "REPORT_REVIEW"; case "ISSUE" -> "REPORT_ISSUE"; case "SEAL" -> "REPORT_SEAL";
            default -> "BUSINESS_TRANSITION";
        };
        if (!user.permissions().contains(required)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REPORT_ACTION_FORBIDDEN", "当前账号无权执行该报告动作");
        }
    }

    private boolean passwordRecentlyVerified() {
        return recentPassword.isVerified();
    }

    private void approval(UUID versionId, String action, String comment, UUID signingAssetId) {
        String step = action.equals("APPROVE") || action.equals("RETURN") ? "REVIEW" : action;
        String decision = action.equals("RETURN") ? "RETURNED" : "APPROVED";
        jdbc.sql("""
                INSERT INTO report_approval(id, report_version_id, step, decision, actor_id, comment,
                  signing_asset_id, decided_at)
                VALUES (:id, :version, :step, :decision, :actor, :comment, :asset, now())
                """).param("id", UUID.randomUUID()).param("version", versionId).param("step", step)
                .param("decision", decision).param("actor", actor.require().id()).param("comment", comment)
                .param("asset", signingAssetId).update();
    }

    private void enqueuePdf(UUID versionId, boolean finalDocument) {
        jdbc.sql("""
                INSERT INTO background_job(id, job_type, payload, idempotency_key, state)
                VALUES (:id, 'GENERATE_REPORT_PDF', jsonb_build_object('reportVersionId', CAST(:version AS text),
                        'finalDocument', :finalDocument),
                        :key, 'READY') ON CONFLICT(idempotency_key) DO NOTHING
                """).param("id", UUID.randomUUID()).param("version", versionId)
                .param("finalDocument", finalDocument)
                .param("key", "report-pdf:" + versionId + (finalDocument ? ":final" : ":draft")).update();
    }

    private void archive(UUID reportId, UUID versionId) {
        View report = require(reportId);
        String manifest = sha256(report.number() + "\n" + report.revisionLabel() + "\n" + encode(report.content()));
        jdbc.sql("""
                INSERT INTO archive_package(id, report_id, report_version_id, archive_number, manifest_sha256, archived_by, retention_until)
                VALUES (:id, :report, :version, :number, :manifest, :actor, current_date+interval '30 years')
                ON CONFLICT(report_version_id) DO NOTHING
                """).param("id", UUID.randomUUID()).param("report", reportId)
                .param("version", versionId)
                .param("number", numbering.next("ARCHIVE", "DA")).param("manifest", manifest)
                .param("actor", actor.require().id()).update();
    }

    private UUID currentVersionId(UUID reportId, int revision) {
        return jdbc.sql("SELECT id FROM report_version WHERE report_id=:report AND revision=:revision")
                .param("report", reportId).param("revision", revision).query(UUID.class).single();
    }

    private record PrintPolicy(boolean restricted, Integer limit) {}

    private UUID issuedSignatureAssetId(UUID versionId) {
        return jdbc.sql("""
                SELECT signing_asset_id FROM report_approval
                WHERE report_version_id=:version AND step='ISSUE' AND decision='APPROVED'
                ORDER BY decided_at DESC LIMIT 1
                """).param("version", versionId).query(UUID.class).optional().orElse(null);
    }

    private String baseSelect() {
        return """
                SELECT r.id, r.commission_id, r.number, r.title, r.current_revision, v.revision_label,
                  r.state, v.content_json::text, v.pdf_file_id, (v.final_pdf_at IS NOT NULL) pdf_finalized,
                  (v.signed_at IS NOT NULL) signed,
                  (v.sealed_at IS NOT NULL) sealed, r.version, r.updated_at
                FROM report r JOIN commission c ON c.id=r.commission_id
                  JOIN report_version v ON v.report_id=r.id AND v.revision=r.current_revision
                """;
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        try {
            Map<String, Object> content = json.readValue(rs.getString("content_json"), new TypeReference<>() {});
            return new View(rs.getObject("id", UUID.class), rs.getObject("commission_id", UUID.class),
                    rs.getString("number"), rs.getString("title"), rs.getInt("current_revision"),
                    rs.getString("revision_label"), ReportState.valueOf(rs.getString("state")), content,
                    rs.getObject("pdf_file_id", UUID.class), rs.getBoolean("pdf_finalized"),
                    rs.getBoolean("signed"), rs.getBoolean("sealed"),
                    rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class));
        } catch (JacksonException exception) { throw new java.sql.SQLException("Invalid report JSON", exception); }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("报告内容无法序列化", exception); }
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
