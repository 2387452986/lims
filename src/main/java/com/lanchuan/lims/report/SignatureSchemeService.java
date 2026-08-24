package com.lanchuan.lims.report;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.RecentPasswordService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SignatureSchemeService {
    private static final BigDecimal PAGE_WIDTH = new BigDecimal("180");
    private static final BigDecimal PAGE_HEIGHT = new BigDecimal("250");
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final RecentPasswordService recentPassword;
    private final SigningAssetService assets;

    public SignatureSchemeService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                  RecentPasswordService recentPassword, SigningAssetService assets) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.recentPassword = recentPassword;
        this.assets = assets;
    }

    public record PositionWrite(String positionType, UUID signingAssetId, String label, String pageRule,
                                BigDecimal xMm, BigDecimal yMm, BigDecimal widthMm, BigDecimal heightMm,
                                int sortOrder) {}
    public record Create(String name, String remark, List<PositionWrite> positions) {}
    public record Update(String name, String remark, List<PositionWrite> positions, long expectedVersion) {}
    public record Transition(String action, long expectedVersion, String reasonCode, String comment) {}
    public record PositionView(UUID id, String positionType, UUID signingAssetId, String assetName,
                               String label, String pageRule, BigDecimal xMm, BigDecimal yMm,
                               BigDecimal widthMm, BigDecimal heightMm, int sortOrder) {}
    public record View(UUID id, UUID seriesId, UUID predecessorId, int revisionNumber, String name,
                       String remark, String state, long version, String createdBy,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt,
                       List<PositionView> positions, List<String> allowedActions) {}
    public record SealContext(UUID schemeId, UUID sealAssetId) {}
    public record RenderPosition(String positionType, UUID signingAssetId, String label, String pageRule,
                                 BigDecimal xMm, BigDecimal yMm, BigDecimal widthMm, BigDecimal heightMm) {}

    public List<View> list() {
        return jdbc.sql(baseSelect() + " WHERE scheme.organization_id=:organization ORDER BY scheme.updated_at DESC, scheme.id DESC")
                .param("organization", actor.require().organizationId()).query(this::map).list().stream()
                .map(this::withPositions).toList();
    }

    public View require(UUID id) {
        View view = jdbc.sql(baseSelect() + " WHERE scheme.id=:id AND scheme.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId())
                .query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("SIGNATURE_SCHEME_NOT_FOUND", "签章方案不存在"));
        return withPositions(view);
    }

    @Transactional
    public View create(Create command) {
        Validated value = validate(command.name(), command.remark(), command.positions());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO signature_scheme(id, organization_id, series_id, revision_number, name, remark,
                  state, created_by, updated_by) VALUES (:id, :organization, :series, 1, :name, :remark,
                  'DRAFT', :actor, :actor)
                """).param("id", id).param("organization", actor.require().organizationId())
                .param("series", id).param("name", value.name()).param("remark", value.remark())
                .param("actor", actor.require().id()).update();
        replacePositions(id, value.positions());
        View created = require(id);
        audit.record("SIGNATURE_SCHEME_CREATED", "SIGNATURE_SCHEME", id, null, auditView(created), null, value.name());
        return created;
    }

    @Transactional
    public View update(UUID id, Update command) {
        View before = require(id);
        if (!before.state().equals("DRAFT")) {
            throw new ApiException(HttpStatus.CONFLICT, "SIGNATURE_SCHEME_IMMUTABLE", "已启用或停用的方案须创建新修订");
        }
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "签章方案已被其他用户更新", before.version());
        }
        Validated value = validate(command.name(), command.remark(), command.positions());
        int updated = jdbc.sql("""
                UPDATE signature_scheme SET name=:name, remark=:remark, version=version+1,
                  updated_by=:actor, updated_at=now()
                WHERE id=:id AND organization_id=:organization AND state='DRAFT' AND version=:version
                """).param("name", value.name()).param("remark", value.remark()).param("actor", actor.require().id())
                .param("id", id).param("organization", actor.require().organizationId())
                .param("version", before.version()).update();
        if (updated == 0) throw ApiException.conflict("VERSION_CONFLICT", "签章方案已被其他用户更新", require(id).version());
        replacePositions(id, value.positions());
        View after = require(id);
        audit.record("SIGNATURE_SCHEME_UPDATED", "SIGNATURE_SCHEME", id, auditView(before), auditView(after), null, null);
        return after;
    }

    @Transactional
    public View revise(UUID id) {
        View before = require(id);
        if (before.state().equals("DRAFT")) {
            throw new ApiException(HttpStatus.CONFLICT, "SIGNATURE_SCHEME_REVISION_INVALID", "草稿方案可直接修改，无需修订");
        }
        jdbc.sql("SELECT id FROM signature_scheme WHERE id=:id FOR UPDATE").param("id", id).query(UUID.class).single();
        int nextRevision = jdbc.sql("""
                SELECT max(revision_number)+1 FROM signature_scheme WHERE organization_id=:organization AND series_id=:series
                """).param("organization", actor.require().organizationId()).param("series", before.seriesId())
                .query(Integer.class).single();
        UUID revisionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO signature_scheme(id, organization_id, series_id, predecessor_id, revision_number,
                  name, remark, state, created_by, updated_by)
                VALUES (:id, :organization, :series, :predecessor, :revision, :name, :remark,
                  'DRAFT', :actor, :actor)
                """).param("id", revisionId).param("organization", actor.require().organizationId())
                .param("series", before.seriesId()).param("predecessor", before.id()).param("revision", nextRevision)
                .param("name", before.name()).param("remark", before.remark()).param("actor", actor.require().id()).update();
        List<PositionWrite> copied = before.positions().stream().map(item -> new PositionWrite(item.positionType(),
                item.signingAssetId(), item.label(), item.pageRule(), item.xMm(), item.yMm(), item.widthMm(),
                item.heightMm(), item.sortOrder())).toList();
        replacePositions(revisionId, copied);
        View revision = require(revisionId);
        audit.record("SIGNATURE_SCHEME_REVISED", "SIGNATURE_SCHEME", revisionId, auditView(before),
                auditView(revision), "CONTROLLED_REVISION", "从版本 " + before.revisionNumber() + " 创建修订");
        return revision;
    }

    @Transactional
    public TransitionResult transition(UUID id, Transition command) {
        requireRecentPassword();
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "签章方案已被其他用户更新", before.version());
        }
        String action = command.action().trim().toUpperCase();
        String next;
        if (action.equals("ACTIVATE") && List.of("DRAFT", "INACTIVE").contains(before.state())) {
            lockOrganization();
            validateActivation(before);
            UUID active = jdbc.sql("""
                    SELECT id FROM signature_scheme WHERE organization_id=:organization AND state='ACTIVE' AND id<>:id
                    """).param("organization", actor.require().organizationId()).param("id", id)
                    .query(UUID.class).optional().orElse(null);
            if (active != null) throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_SIGNATURE_SCHEME_EXISTS",
                    "已有启用中的签章方案，请先停用后再启用当前方案");
            next = "ACTIVE";
        } else if (action.equals("DEACTIVATE") && before.state().equals("ACTIVE")) {
            requireReason(command.comment(), "停用签章方案必须填写原因");
            next = "INACTIVE";
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "SIGNATURE_SCHEME_TRANSITION_INVALID", "当前方案不能执行 " + action);
        }
        int updated = jdbc.sql("""
                UPDATE signature_scheme SET state=:state, version=version+1, updated_by=:actor, updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("state", next).param("actor", actor.require().id()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", before.version()).update();
        if (updated == 0) throw ApiException.conflict("VERSION_CONFLICT", "签章方案已被其他用户更新", require(id).version());
        View after = require(id);
        audit.record("SIGNATURE_SCHEME_" + action, "SIGNATURE_SCHEME", id, auditView(before), auditView(after),
                command.reasonCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    public String blocker(UUID issuerSignatureAssetId) {
        UUID activeId = activeSchemeId();
        if (activeId == null) return assets.assetsRequired() ? "当前机构尚未启用签章方案" : null;
        long matchingSignature = jdbc.sql("""
                SELECT count(*) FROM signature_scheme_position position JOIN signing_asset asset ON asset.id=position.signing_asset_id
                WHERE position.scheme_id=:scheme AND position.position_type='SIGNATURE'
                  AND position.signing_asset_id=:signature AND asset.active AND asset.verification_state='VERIFIED'
                  AND asset.valid_from<=(now() AT TIME ZONE 'Asia/Shanghai')::date
                  AND asset.valid_until>=(now() AT TIME ZONE 'Asia/Shanghai')::date
                """).param("scheme", activeId).param("signature", issuerSignatureAssetId).query(Long.class).single();
        if (matchingSignature == 0) return "启用中的签章方案未配置当前签发人的有效签名位置";
        long validSeals = jdbc.sql("""
                SELECT count(*) FROM signature_scheme_position position JOIN signing_asset asset ON asset.id=position.signing_asset_id
                WHERE position.scheme_id=:scheme AND position.position_type='SEAL'
                  AND asset.active AND asset.verification_state='VERIFIED'
                  AND asset.valid_from<=(now() AT TIME ZONE 'Asia/Shanghai')::date
                  AND asset.valid_until>=(now() AT TIME ZONE 'Asia/Shanghai')::date
                """).param("scheme", activeId).query(Long.class).single();
        return validSeals == 0 ? "启用中的签章方案没有在有效期内的机构章" : null;
    }

    public SealContext sealContext(UUID issuerSignatureAssetId) {
        UUID activeId = activeSchemeId();
        if (activeId == null) return null;
        String blocker = blocker(issuerSignatureAssetId);
        if (blocker != null) throw ApiException.blocked("SIGNATURE_SCHEME_BLOCKED", "签章方案不可用于当前报告", 0, List.of(blocker));
        UUID seal = jdbc.sql("""
                SELECT position.signing_asset_id FROM signature_scheme_position position
                WHERE position.scheme_id=:scheme AND position.position_type='SEAL'
                ORDER BY position.sort_order, position.id LIMIT 1
                """).param("scheme", activeId).query(UUID.class).single();
        return new SealContext(activeId, seal);
    }

    public List<RenderPosition> renderPositions(UUID schemeId) {
        if (schemeId == null) return List.of();
        return jdbc.sql("""
                SELECT position_type, signing_asset_id, label, page_rule, x_mm, y_mm, width_mm, height_mm
                FROM signature_scheme_position WHERE scheme_id=:scheme ORDER BY sort_order, id
                """).param("scheme", schemeId).query((rs, row) -> new RenderPosition(rs.getString("position_type"),
                        rs.getObject("signing_asset_id", UUID.class), rs.getString("label"), rs.getString("page_rule"),
                        rs.getBigDecimal("x_mm"), rs.getBigDecimal("y_mm"), rs.getBigDecimal("width_mm"),
                        rs.getBigDecimal("height_mm"))).list();
    }

    private UUID activeSchemeId() {
        return jdbc.sql("SELECT id FROM signature_scheme WHERE organization_id=:organization AND state='ACTIVE'")
                .param("organization", actor.require().organizationId()).query(UUID.class).optional().orElse(null);
    }

    private void validateActivation(View scheme) {
        if (scheme.positions().stream().noneMatch(item -> item.positionType().equals("SIGNATURE")) ||
                scheme.positions().stream().noneMatch(item -> item.positionType().equals("SEAL"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATURE_SCHEME_INCOMPLETE",
                    "签章方案至少需要一个人员签名位置和一个机构章位置");
        }
        long invalid = jdbc.sql("""
                SELECT count(*) FROM signature_scheme_position position
                JOIN signing_asset asset ON asset.id=position.signing_asset_id
                WHERE position.scheme_id=:scheme AND (
                  asset.organization_id<>:organization OR asset.asset_type<>position.position_type OR NOT asset.active
                  OR asset.verification_state<>'VERIFIED'
                  OR asset.valid_from>(now() AT TIME ZONE 'Asia/Shanghai')::date
                  OR asset.valid_until<(now() AT TIME ZONE 'Asia/Shanghai')::date)
                """).param("scheme", scheme.id()).param("organization", actor.require().organizationId())
                .query(Long.class).single();
        if (invalid > 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATURE_SCHEME_ASSET_INVALID",
                "方案包含未认证、未启用、过期或类型不匹配的签章素材");
    }

    private Validated validate(String name, String remark, List<PositionWrite> positions) {
        if (name == null || name.isBlank() || name.trim().length() > 160) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNATURE_SCHEME_NAME_INVALID", "请填写不超过160字的方案名称");
        }
        if (remark != null && remark.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNATURE_SCHEME_REMARK_INVALID", "方案备注不能超过1000字");
        }
        List<PositionWrite> source = positions == null ? List.of() : positions;
        if (source.size() > 20) throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNATURE_SCHEME_POSITIONS_EXCEEDED", "单个方案最多配置20个签章位置");
        List<PositionWrite> checked = new ArrayList<>();
        java.util.Set<String> orderKeys = new java.util.HashSet<>();
        for (PositionWrite item : source) {
            String type = item.positionType() == null ? "" : item.positionType().trim().toUpperCase();
            if (!List.of("SIGNATURE", "SEAL").contains(type) || item.signingAssetId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNATURE_SCHEME_POSITION_INVALID", "签章位置类型或素材无效");
            }
            SigningAssetService.View asset = assets.require(item.signingAssetId());
            if (!asset.assetType().equals(type)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATURE_SCHEME_ASSET_TYPE_MISMATCH", "签章位置与素材类型不一致");
            }
            String label = item.label() == null ? "" : item.label().trim();
            String pageRule = item.pageRule() == null ? "" : item.pageRule().trim().toUpperCase();
            if (label.isBlank() || label.length() > 120 || !List.of("FIRST", "LAST", "EVERY").contains(pageRule)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNATURE_SCHEME_POSITION_INVALID", "请填写正确的位置名称和页规则");
            }
            requireRectangle(item);
            if (!orderKeys.add(type + ":" + item.sortOrder())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNATURE_SCHEME_ORDER_DUPLICATED", "同类签章位置排序号不能重复");
            }
            checked.add(new PositionWrite(type, item.signingAssetId(), label, pageRule, item.xMm(), item.yMm(),
                    item.widthMm(), item.heightMm(), item.sortOrder()));
        }
        return new Validated(name.trim(), remark == null || remark.isBlank() ? null : remark.trim(), List.copyOf(checked));
    }

    private void requireRectangle(PositionWrite item) {
        if (item.xMm() == null || item.yMm() == null || item.widthMm() == null || item.heightMm() == null ||
                item.xMm().signum() < 0 || item.yMm().signum() < 0 ||
                item.widthMm().compareTo(BigDecimal.TEN) < 0 || item.widthMm().compareTo(new BigDecimal("80")) > 0 ||
                item.heightMm().compareTo(BigDecimal.TEN) < 0 || item.heightMm().compareTo(new BigDecimal("80")) > 0 ||
                item.xMm().add(item.widthMm()).compareTo(PAGE_WIDTH) > 0 ||
                item.yMm().add(item.heightMm()).compareTo(PAGE_HEIGHT) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNATURE_SCHEME_POSITION_OUTSIDE_PAGE", "签章位置超出受控 A4 内容区域");
        }
    }

    private void replacePositions(UUID schemeId, List<PositionWrite> positions) {
        jdbc.sql("DELETE FROM signature_scheme_position WHERE scheme_id=:scheme").param("scheme", schemeId).update();
        for (PositionWrite item : positions) {
            jdbc.sql("""
                    INSERT INTO signature_scheme_position(id, scheme_id, position_type, signing_asset_id, label,
                      page_rule, x_mm, y_mm, width_mm, height_mm, sort_order)
                    VALUES (:id, :scheme, :type, :asset, :label, :pageRule, :x, :y, :width, :height, :sort)
                    """).param("id", UUID.randomUUID()).param("scheme", schemeId).param("type", item.positionType())
                    .param("asset", item.signingAssetId()).param("label", item.label()).param("pageRule", item.pageRule())
                    .param("x", item.xMm()).param("y", item.yMm()).param("width", item.widthMm())
                    .param("height", item.heightMm()).param("sort", item.sortOrder()).update();
        }
    }

    private View withPositions(View view) {
        List<PositionView> positions = jdbc.sql("""
                SELECT position.id, position.position_type, position.signing_asset_id, asset.name asset_name,
                  position.label, position.page_rule, position.x_mm, position.y_mm, position.width_mm,
                  position.height_mm, position.sort_order
                FROM signature_scheme_position position JOIN signing_asset asset ON asset.id=position.signing_asset_id
                WHERE position.scheme_id=:scheme ORDER BY position.sort_order, position.id
                """).param("scheme", view.id()).query((rs, row) -> new PositionView(rs.getObject("id", UUID.class),
                        rs.getString("position_type"), rs.getObject("signing_asset_id", UUID.class),
                        rs.getString("asset_name"), rs.getString("label"), rs.getString("page_rule"),
                        rs.getBigDecimal("x_mm"), rs.getBigDecimal("y_mm"), rs.getBigDecimal("width_mm"),
                        rs.getBigDecimal("height_mm"), rs.getInt("sort_order"))).list();
        return new View(view.id(), view.seriesId(), view.predecessorId(), view.revisionNumber(), view.name(),
                view.remark(), view.state(), view.version(), view.createdBy(), view.createdAt(), view.updatedAt(),
                positions, view.allowedActions());
    }

    private String baseSelect() {
        return """
                SELECT scheme.id, scheme.series_id, scheme.predecessor_id, scheme.revision_number, scheme.name,
                  scheme.remark, scheme.state, scheme.version, creator.display_name created_by,
                  scheme.created_at, scheme.updated_at
                FROM signature_scheme scheme JOIN app_user creator ON creator.id=scheme.created_by
                """;
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String state = rs.getString("state");
        return new View(rs.getObject("id", UUID.class), rs.getObject("series_id", UUID.class),
                rs.getObject("predecessor_id", UUID.class), rs.getInt("revision_number"), rs.getString("name"),
                rs.getString("remark"), state, rs.getLong("version"), rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                List.of(), allowedActions(state));
    }

    private List<String> allowedActions(String state) {
        return switch (state) {
            case "DRAFT" -> List.of("ACTIVATE");
            case "ACTIVE" -> List.of("DEACTIVATE", "REVISE");
            case "INACTIVE" -> List.of("ACTIVATE", "REVISE");
            default -> List.of();
        };
    }

    private Map<String, Object> auditView(View view) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("seriesId", view.seriesId());
        value.put("revisionNumber", view.revisionNumber());
        value.put("name", view.name());
        value.put("state", view.state());
        value.put("version", view.version());
        value.put("positions", view.positions().stream().map(item -> Map.of(
                "type", item.positionType(), "assetId", item.signingAssetId(), "pageRule", item.pageRule(),
                "xMm", item.xMm(), "yMm", item.yMm(), "widthMm", item.widthMm(), "heightMm", item.heightMm())).toList());
        return value;
    }

    private void requireRecentPassword() {
        if (!recentPassword.isVerified()) throw ApiException.blocked("PASSWORD_REVERIFICATION_REQUIRED",
                "启停签章方案前须完成密码复核", 0, List.of("请在5分钟内重新验证当前密码"));
    }

    private void lockOrganization() {
        jdbc.sql("SELECT id FROM organization WHERE id=:id FOR UPDATE")
                .param("id", actor.require().organizationId()).query(UUID.class).single();
    }

    private void requireReason(String value, String message) {
        if (value == null || value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", message);
    }

    private record Validated(String name, String remark, List<PositionWrite> positions) {}
}
