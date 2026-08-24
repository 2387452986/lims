package com.lanchuan.lims.report;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.RecentPasswordService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class SigningAssetService {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final String encodedKey;
    private final boolean requireAssets;
    private final RecentPasswordService recentPassword;
    private final SecureRandom random = new SecureRandom();

    public SigningAssetService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                               @Value("${lims.signing.encryption-key:}") String encodedKey,
                               @Value("${lims.signing.require-assets:false}") boolean requireAssets,
                               RecentPasswordService recentPassword) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.encodedKey = encodedKey;
        this.requireAssets = requireAssets;
        this.recentPassword = recentPassword;
    }

    public record Upload(String mediaType, String sha256, byte[] content, int width, int height) {}
    public record Create(String assetType, String name, UUID ownerPersonId, String engineeringType,
                         String sealType, LocalDate validFrom, LocalDate validUntil) {}
    public record Update(String name, UUID ownerPersonId, String engineeringType, String sealType,
                         LocalDate validFrom, LocalDate validUntil, long expectedVersion) {}
    public record Transition(String action, long expectedVersion, String reasonCode, String comment) {}
    public record View(UUID id, String assetType, UUID ownerPersonId, String ownerName, String name,
                       String engineeringType, String sealType, LocalDate validFrom, LocalDate validUntil,
                       String mediaType, String sha256, int pixelWidth, int pixelHeight,
                       String verificationState, boolean active, long version,
                       OffsetDateTime verifiedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                       List<String> allowedActions) {}
    public record AssetContent(String mediaType, byte[] content) {}

    public Upload readUpload(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNING_ASSET_SIZE_INVALID",
                    "签名或印章素材须为不超过2MB的图片");
        }
        String mediaType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        try {
            byte[] content = file.getBytes();
            SigningAssetImageValidator.Dimensions dimensions = SigningAssetImageValidator.validate(content, mediaType);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            return new Upload(mediaType, sha, content, dimensions.width(), dimensions.height());
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNING_ASSET_READ_FAILED", "无法读取签名或印章素材");
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SIGNING_ASSET_HASH_FAILED", "签章素材校验失败");
        }
    }

    @Transactional
    public View store(Create command, Upload upload) {
        requireRecentPassword();
        Create value = validate(command);
        UUID ownerAccountId = value.assetType().equals("SIGNATURE") ? ownerAccount(value.ownerPersonId()) : null;
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            byte[] encrypted = encrypt(upload.content(), nonce);
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO signing_asset(id, asset_type, organization_id, owner_id, owner_person_id, name,
                      engineering_type, seal_type, valid_from, valid_until, media_type, content_sha256,
                      encrypted_content, nonce, active, verification_state, pixel_width, pixel_height, created_by)
                    VALUES (:id, :type, :organization, :owner, :ownerPerson, :name, :engineeringType, :sealType,
                      :validFrom, :validUntil, :mediaType, :sha, :content, :nonce, false, 'DRAFT', :width, :height, :actor)
                    """).param("id", id).param("type", value.assetType())
                    .param("organization", actor.require().organizationId()).param("owner", ownerAccountId)
                    .param("ownerPerson", value.ownerPersonId()).param("name", value.name())
                    .param("engineeringType", value.engineeringType()).param("sealType", value.sealType())
                    .param("validFrom", value.validFrom()).param("validUntil", value.validUntil())
                    .param("mediaType", upload.mediaType()).param("sha", upload.sha256())
                    .param("content", encrypted).param("nonce", nonce)
                    .param("width", upload.width()).param("height", upload.height())
                    .param("actor", actor.require().id()).update();
            View stored = require(id);
            audit.record("SIGNING_ASSET_CREATED", "SIGNING_ASSET", id, null, auditView(stored), null, value.name());
            return stored;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SIGNING_ASSET_ENCRYPTION_FAILED",
                    "签名或印章素材加密失败");
        }
    }

    public List<View> list(String assetType, String query, String verificationState, Boolean active,
                           LocalDate createdFrom, LocalDate createdTo) {
        String type = assetType == null || assetType.isBlank() ? null : assetType.trim().toUpperCase();
        if (type != null && !List.of("SIGNATURE", "SEAL").contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNING_ASSET_TYPE_INVALID", "签名或印章素材类型无效");
        }
        String q = query == null || query.isBlank() ? null : "%" + query.trim() + "%";
        String state = verificationState == null || verificationState.isBlank()
                ? null : verificationState.trim().toUpperCase();
        if (createdFrom != null && createdTo != null && createdTo.isBefore(createdFrom)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNING_ASSET_DATE_RANGE_INVALID", "创建日期结束值不能早于开始值");
        }
        return jdbc.sql(baseSelect() + """
                 WHERE asset.organization_id=:organization
                   AND (CAST(:type AS varchar) IS NULL OR asset.asset_type=CAST(:type AS varchar))
                   AND (CAST(:query AS varchar) IS NULL OR asset.name ILIKE CAST(:query AS varchar)
                        OR person.name ILIKE CAST(:query AS varchar))
                   AND (CAST(:state AS varchar) IS NULL OR asset.verification_state=CAST(:state AS varchar))
                   AND (CAST(:active AS boolean) IS NULL OR asset.active=CAST(:active AS boolean))
                   AND (CAST(:createdFrom AS date) IS NULL OR asset.created_at>=CAST(:createdFrom AS date))
                   AND (CAST(:createdTo AS date) IS NULL OR asset.created_at<CAST(:createdTo AS date)+interval '1 day')
                 ORDER BY asset.created_at DESC, asset.id DESC
                """).param("organization", actor.require().organizationId()).param("type", type)
                .param("query", q).param("state", state).param("active", active)
                .param("createdFrom", createdFrom).param("createdTo", createdTo)
                .query(this::map).list();
    }

    @Transactional
    public View update(UUID id, Update command) {
        requireRecentPassword();
        View before = require(id);
        if (!before.verificationState().equals("DRAFT") || before.active()) {
            throw new ApiException(HttpStatus.CONFLICT, "SIGNING_ASSET_IMMUTABLE",
                    "仅未认证草稿可以修改，已认证素材须新建版本");
        }
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "签章素材已被其他用户更新", before.version());
        }
        Create value = validate(new Create(before.assetType(), command.name(), command.ownerPersonId(),
                command.engineeringType(), command.sealType(), command.validFrom(), command.validUntil()));
        UUID ownerAccountId = value.assetType().equals("SIGNATURE") ? ownerAccount(value.ownerPersonId()) : null;
        int updated = jdbc.sql("""
                UPDATE signing_asset SET owner_id=:owner, owner_person_id=:ownerPerson, name=:name,
                  engineering_type=:engineeringType, seal_type=:sealType, valid_from=:validFrom,
                  valid_until=:validUntil, version=version+1, updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version AND verification_state='DRAFT'
                """).param("owner", ownerAccountId).param("ownerPerson", value.ownerPersonId())
                .param("name", value.name()).param("engineeringType", value.engineeringType())
                .param("sealType", value.sealType()).param("validFrom", value.validFrom())
                .param("validUntil", value.validUntil()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", before.version()).update();
        if (updated == 0) throw ApiException.conflict("VERSION_CONFLICT", "签章素材已被其他用户更新", require(id).version());
        View after = require(id);
        audit.record("SIGNING_ASSET_UPDATED", "SIGNING_ASSET", id, auditView(before), auditView(after), null, null);
        return after;
    }

    @Transactional
    public TransitionResult transition(UUID id, Transition command) {
        requireRecentPassword();
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "签章素材已被其他用户更新", before.version());
        }
        String action = command.action().trim().toUpperCase();
        String state = before.verificationState();
        boolean active = before.active();
        switch (action) {
            case "VERIFY" -> {
                if (!state.equals("DRAFT")) invalidTransition(action);
                state = "VERIFIED";
            }
            case "REJECT" -> {
                if (!state.equals("DRAFT")) invalidTransition(action);
                requireReason(command.comment(), "认证驳回必须填写原因");
                state = "REJECTED";
                active = false;
            }
            case "ACTIVATE" -> {
                if (!state.equals("VERIFIED") || active) invalidTransition(action);
                requireCurrentlyValid(before.validFrom(), before.validUntil());
                if (before.assetType().equals("SIGNATURE")) {
                    long activeSchemeReferences = jdbc.sql("""
                            SELECT count(*) FROM signature_scheme_position position
                            JOIN signature_scheme scheme ON scheme.id=position.scheme_id AND scheme.state='ACTIVE'
                            JOIN signing_asset asset ON asset.id=position.signing_asset_id
                            WHERE scheme.organization_id=:organization AND position.position_type='SIGNATURE'
                              AND asset.owner_person_id=:owner AND asset.active AND asset.id<>:id
                            """).param("organization", actor.require().organizationId())
                            .param("owner", before.ownerPersonId()).param("id", id).query(Long.class).single();
                    if (activeSchemeReferences > 0) {
                        throw ApiException.blocked("SIGNING_ASSET_ACTIVE_SCHEME_CONFLICT",
                                "现用签名已绑定启用中的签章方案", before.version(),
                                List.of("请先停用当前签章方案，再启用新签名并创建方案修订"));
                    }
                    jdbc.sql("""
                            UPDATE signing_asset SET active=false, version=version+1, updated_at=now()
                            WHERE organization_id=:organization AND asset_type='SIGNATURE'
                              AND owner_person_id=:owner AND active AND id<>:id
                            """).param("organization", actor.require().organizationId())
                            .param("owner", before.ownerPersonId()).param("id", id).update();
                }
                active = true;
            }
            case "DEACTIVATE" -> {
                if (!active) invalidTransition(action);
                requireReason(command.comment(), "停用签章素材必须填写原因");
                active = false;
            }
            default -> invalidTransition(action);
        }
        int updated = jdbc.sql("""
                UPDATE signing_asset SET verification_state=:state, active=:active, version=version+1,
                  verified_by=CASE WHEN :action='VERIFY' THEN :actor ELSE verified_by END,
                  verified_at=CASE WHEN :action='VERIFY' THEN now() ELSE verified_at END,
                  verification_comment=CASE WHEN :action IN ('VERIFY','REJECT') THEN :comment ELSE verification_comment END,
                  updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("state", state).param("active", active).param("action", action)
                .param("actor", actor.require().id()).param("comment", command.comment()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", before.version()).update();
        if (updated == 0) throw ApiException.conflict("VERSION_CONFLICT", "签章素材已被其他用户更新", require(id).version());
        View after = require(id);
        audit.record("SIGNING_ASSET_" + action, "SIGNING_ASSET", id, auditView(before), auditView(after),
                command.reasonCode(), command.comment());
        return new TransitionResult(after.verificationState() + (after.active() ? "_ACTIVE" : ""),
                after.version(), after.allowedActions());
    }

    public String signatureBlocker() {
        if (!requireAssets) return null;
        if (!keyConfigured()) return "签名素材加密密钥未配置";
        return activeSignatureId() == null ? "当前签发人尚未配置已认证且在有效期内的签名素材" : null;
    }

    public String sealBlocker() {
        if (!requireAssets) return null;
        if (!keyConfigured()) return "印章素材加密密钥未配置";
        return activeSealId() == null ? "当前机构尚未配置已认证且在有效期内的印章素材" : null;
    }

    public boolean assetsRequired() { return requireAssets; }

    public UUID activeSignatureId() {
        UUID personId = actor.require().personId();
        return jdbc.sql("""
                SELECT id FROM signing_asset WHERE asset_type='SIGNATURE' AND organization_id=:organization
                  AND active AND verification_state='VERIFIED'
                  AND valid_from<=(now() AT TIME ZONE 'Asia/Shanghai')::date
                  AND valid_until>=(now() AT TIME ZONE 'Asia/Shanghai')::date
                  AND ((CAST(:person AS uuid) IS NOT NULL AND owner_person_id=CAST(:person AS uuid)) OR owner_id=:actor)
                ORDER BY created_at DESC LIMIT 1
                """).param("organization", actor.require().organizationId()).param("person", personId)
                .param("actor", actor.require().id()).query(UUID.class).optional().orElse(null);
    }

    public UUID activeSealId() {
        return jdbc.sql("""
                SELECT id FROM signing_asset WHERE asset_type='SEAL' AND organization_id=:organization
                  AND active AND verification_state='VERIFIED'
                  AND valid_from<=(now() AT TIME ZONE 'Asia/Shanghai')::date
                  AND valid_until>=(now() AT TIME ZONE 'Asia/Shanghai')::date
                ORDER BY created_at DESC LIMIT 1
                """).param("organization", actor.require().organizationId()).query(UUID.class).optional().orElse(null);
    }

    AssetContent decrypt(UUID id) {
        if (id == null) return null;
        EncryptedAsset asset = jdbc.sql("""
                SELECT media_type, encrypted_content, nonce FROM signing_asset WHERE id=:id
                """).param("id", id).query((rs, row) -> new EncryptedAsset(rs.getString("media_type"),
                        rs.getBytes("encrypted_content"), rs.getBytes("nonce"))).optional().orElse(null);
        if (asset == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, asset.nonce()));
            return new AssetContent(asset.mediaType(), cipher.doFinal(asset.encryptedContent()));
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SIGNING_ASSET_DECRYPTION_FAILED",
                    "签名或印章素材解密失败");
        }
    }

    public AssetContent content(UUID id) {
        require(id);
        AssetContent content = decrypt(id);
        if (content == null) throw ApiException.notFound("SIGNING_ASSET_CONTENT_NOT_FOUND", "签章素材内容不存在");
        return content;
    }

    public View require(UUID id) {
        return jdbc.sql(baseSelect() + " WHERE asset.id=:id AND asset.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId()).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("SIGNING_ASSET_NOT_FOUND", "签名或印章素材不存在"));
    }

    private Create validate(Create command) {
        String type = text(command.assetType(), 20, "签章素材类型不能为空").toUpperCase();
        if (!List.of("SIGNATURE", "SEAL").contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNING_ASSET_TYPE_INVALID", "签名或印章素材类型无效");
        }
        String name = text(command.name(), 160, "签章素材名称不能为空");
        if (command.validFrom() == null || command.validUntil() == null || command.validUntil().isBefore(command.validFrom())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNING_ASSET_VALIDITY_INVALID", "请填写正确的有效期起止日期");
        }
        UUID ownerPerson = type.equals("SIGNATURE") ? command.ownerPersonId() : null;
        if (type.equals("SIGNATURE") && ownerPerson == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNATURE_OWNER_REQUIRED", "人员签名必须指定所属人员");
        }
        if (ownerPerson != null) requirePerson(ownerPerson);
        String engineeringType = type.equals("SEAL") ? text(command.engineeringType(), 80, "机构章必须填写工程类型") : null;
        String sealType = type.equals("SEAL") ? text(command.sealType(), 40, "机构章必须填写印章类型") : null;
        return new Create(type, name, ownerPerson, engineeringType, sealType, command.validFrom(), command.validUntil());
    }

    private void requirePerson(UUID id) {
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM person person JOIN department department ON department.id=person.department_id
                  WHERE person.id=:id AND department.organization_id=:organization AND person.active)
                """).param("id", id).param("organization", actor.require().organizationId()).query(Boolean.class).single();
        if (!exists) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNATURE_PERSON_INVALID", "签名所属人员不存在或已停用");
    }

    private UUID ownerAccount(UUID personId) {
        return jdbc.sql("""
                SELECT id FROM app_user WHERE person_id=:person AND organization_id=:organization
                ORDER BY enabled DESC, created_at LIMIT 1
                """).param("person", personId).param("organization", actor.require().organizationId())
                .query(UUID.class).optional().orElse(null);
    }

    private void requireCurrentlyValid(LocalDate validFrom, LocalDate validUntil) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        if (validFrom == null || validUntil == null || validFrom.isAfter(today) || validUntil.isBefore(today)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNING_ASSET_OUTSIDE_VALIDITY", "签章素材当前不在有效期内");
        }
    }

    private void requireRecentPassword() {
        if (!recentPassword.isVerified()) throw ApiException.blocked("PASSWORD_REVERIFICATION_REQUIRED",
                "管理签名或印章素材前须完成密码复核", 0, List.of("请在5分钟内重新验证当前密码"));
    }

    private SecretKeySpec key() {
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SIGNING_KEY_NOT_CONFIGURED",
                    "签名与印章加密密钥尚未正确配置");
        }
    }

    private boolean keyConfigured() {
        try { return Base64.getDecoder().decode(encodedKey).length == 32; }
        catch (IllegalArgumentException exception) { return false; }
    }

    private byte[] encrypt(byte[] content, byte[] nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(content);
    }

    private String baseSelect() {
        return """
                SELECT asset.id, asset.asset_type, asset.owner_person_id, person.name owner_name, asset.name,
                  asset.engineering_type, asset.seal_type, asset.valid_from, asset.valid_until, asset.media_type,
                  asset.content_sha256, asset.pixel_width, asset.pixel_height, asset.verification_state, asset.active,
                  asset.version, asset.verified_at, asset.created_at, asset.updated_at
                FROM signing_asset asset LEFT JOIN person ON person.id=asset.owner_person_id
                """;
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String state = rs.getString("verification_state");
        boolean active = rs.getBoolean("active");
        return new View(rs.getObject("id", UUID.class), rs.getString("asset_type"),
                rs.getObject("owner_person_id", UUID.class), rs.getString("owner_name"), rs.getString("name"),
                rs.getString("engineering_type"), rs.getString("seal_type"),
                rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_until", LocalDate.class),
                rs.getString("media_type"), rs.getString("content_sha256"), rs.getInt("pixel_width"),
                rs.getInt("pixel_height"), state, active, rs.getLong("version"),
                rs.getObject("verified_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), allowedActions(state, active));
    }

    private List<String> allowedActions(String state, boolean active) {
        if (state.equals("DRAFT")) return List.of("VERIFY", "REJECT");
        if (state.equals("VERIFIED")) return active ? List.of("DEACTIVATE") : List.of("ACTIVATE");
        return List.of();
    }

    private java.util.Map<String, Object> auditView(View view) {
        java.util.Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("assetType", view.assetType());
        value.put("ownerPersonId", view.ownerPersonId());
        value.put("name", view.name());
        value.put("engineeringType", view.engineeringType());
        value.put("sealType", view.sealType());
        value.put("validFrom", view.validFrom());
        value.put("validUntil", view.validUntil());
        value.put("sha256", view.sha256());
        value.put("verificationState", view.verificationState());
        value.put("active", view.active());
        value.put("version", view.version());
        return value;
    }

    private String text(String value, int maxLength, String message) {
        if (value == null || value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "FIELD_REQUIRED", message);
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) throw new ApiException(HttpStatus.BAD_REQUEST, "FIELD_TOO_LONG", message);
        return trimmed;
    }

    private void requireReason(String value, String message) {
        if (value == null || value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", message);
    }

    private void invalidTransition(String action) {
        throw new ApiException(HttpStatus.CONFLICT, "SIGNING_ASSET_TRANSITION_INVALID", "当前签章素材不能执行 " + action);
    }

    private record EncryptedAsset(String mediaType, byte[] encryptedContent, byte[] nonce) {}
}
