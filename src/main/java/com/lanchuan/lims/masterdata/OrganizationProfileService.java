package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.configuration.AdministrativeDivisionService;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.storedfile.StoredFileService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class OrganizationProfileService {
    private static final List<String> LICENSE_MEDIA = List.of("image/png", "image/jpeg", "image/bmp");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final AdministrativeDivisionService divisions;
    private final StoredFileService files;

    public OrganizationProfileService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                      AdministrativeDivisionService divisions, StoredFileService files) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.divisions = divisions;
        this.files = files;
    }

    public record PersonCard(UUID id, String name, String title, String position, String phone,
                             UUID avatarFileId) {}

    public record Profile(UUID id, String code, String fullName, String shortName, String kind,
                          String organizationNature, String unifiedCreditCode, LocalDate registeredOn,
                          String registrationAuthority, String postalCode, String province, String city,
                          String district, String detailedAddress, BigDecimal registeredCapital,
                          String telephone, String fax, String email, UUID legalRepresentativeId,
                          String frequentContactName, String frequentContactPhone,
                          UUID primaryResponsiblePersonId, UUID qualityResponsiblePersonId,
                          UUID technicalResponsiblePersonId, Integer declaredEquipmentCount,
                          BigDecimal declaredEquipmentOriginalValue, BigDecimal testingArea,
                          BigDecimal workplaceArea, String remark, UUID businessLicenseFileId,
                          String businessLicenseFileName, PersonCard legalRepresentative,
                          PersonCard primaryResponsiblePerson, PersonCard qualityResponsiblePerson,
                          PersonCard technicalResponsiblePerson, boolean active, long version,
                          OffsetDateTime updatedAt) {}

    public record ProfileWrite(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Size(max = 120) String shortName,
            @NotBlank @Pattern(regexp = "STATE_OWNED|PRIVATE") String organizationNature,
            @NotBlank @Pattern(regexp = "[0-9A-Z]{18}") String unifiedCreditCode,
            @NotNull LocalDate registeredOn,
            @NotBlank @Size(max = 200) String registrationAuthority,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String postalCode,
            @NotBlank @Size(max = 80) String province,
            @NotBlank @Size(max = 80) String city,
            @NotBlank @Size(max = 80) String district,
            @NotBlank @Size(max = 500) String detailedAddress,
            @NotNull @DecimalMin("0.00") BigDecimal registeredCapital,
            @NotBlank @Size(max = 40) String telephone,
            @NotBlank @Size(max = 40) String fax,
            @Email @Size(max = 200) String email,
            @NotNull UUID legalRepresentativeId,
            @NotBlank @Size(max = 120) String frequentContactName,
            @NotBlank @Size(max = 40) String frequentContactPhone,
            @NotNull UUID primaryResponsiblePersonId,
            @NotNull UUID qualityResponsiblePersonId,
            @NotNull UUID technicalResponsiblePersonId,
            @NotNull @PositiveOrZero Integer declaredEquipmentCount,
            @NotNull @DecimalMin("0.00") BigDecimal declaredEquipmentOriginalValue,
            @NotNull @DecimalMin("0.00") BigDecimal testingArea,
            @NotNull @DecimalMin("0.00") BigDecimal workplaceArea,
            @NotBlank @Size(max = 2000) String remark,
            @NotNull @PositiveOrZero Long expectedVersion) {}

    public record BusinessLicenseWrite(@NotNull UUID fileId,
                                       @NotNull @PositiveOrZero Long expectedVersion) {}

    public record ElectronicSignatureEnrollment(@NotNull Boolean certificationAgreement,
                                                @NotNull Boolean digitalCertificateAgreement,
                                                @NotNull Boolean privacyPolicy,
                                                @NotNull @PositiveOrZero Long expectedVersion) {}

    public record OfficeAddressView(UUID id, String officeName, String province, String city,
                                    String district, String detailedAddress, String fullAddress,
                                    String postalCode, LocalDate changedOn, boolean correspondenceAddress,
                                    boolean active, int revisionNumber, long version,
                                    OffsetDateTime updatedAt, List<String> allowedActions) {}

    public record OfficeAddressWrite(@NotBlank @Size(max = 160) String officeName,
                                     @NotBlank @Size(max = 80) String province,
                                     @NotBlank @Size(max = 80) String city,
                                     @NotBlank @Size(max = 80) String district,
                                     @NotBlank @Size(max = 500) String detailedAddress,
                                     @NotBlank @Pattern(regexp = "[0-9]{6}") String postalCode,
                                     @NotNull LocalDate changedOn,
                                     boolean correspondenceAddress,
                                     @PositiveOrZero Long expectedVersion) {}

    public record OfficeAddressRevision(UUID id, int revisionNumber, String officeName,
                                        String province, String city, String district,
                                        String detailedAddress, String fullAddress, String postalCode,
                                        LocalDate changedOn, boolean correspondenceAddress,
                                        OffsetDateTime createdAt) {}

    public Profile profile() {
        AuthenticatedUser user = actor.require();
        boolean fullContact = user.permissions().contains("SYSTEM_ADMIN")
                || user.permissions().contains("PERSON_SENSITIVE_READ");
        return jdbc.sql("""
                SELECT o.id,o.code,o.name,o.short_name,o.kind,o.organization_nature,o.unified_credit_code,
                  o.registered_on,o.registration_authority,o.postal_code,o.province,o.city,o.district,
                  o.detailed_address,o.registered_capital,o.telephone,o.fax,o.email,
                  o.legal_representative_id,o.frequent_contact_name,o.frequent_contact_phone,
                  o.primary_responsible_person_id,o.quality_responsible_person_id,
                  o.technical_responsible_person_id,o.declared_equipment_count,
                  o.declared_equipment_original_value,o.testing_area,o.workplace_area,o.profile_remark,
                  o.business_license_file_id,license.original_name business_license_file_name,
                  legal.name legal_name,legal.title legal_title,legal.position legal_position,
                  legal.phone legal_phone,legal.avatar_file_id legal_avatar,
                  primary_person.name primary_name,primary_person.title primary_title,
                  primary_person.position primary_position,primary_person.phone primary_phone,
                  primary_person.avatar_file_id primary_avatar,
                  quality.name quality_name,quality.title quality_title,quality.position quality_position,
                  quality.phone quality_phone,quality.avatar_file_id quality_avatar,
                  technical.name technical_name,technical.title technical_title,
                  technical.position technical_position,technical.phone technical_phone,
                  technical.avatar_file_id technical_avatar,o.active,o.version,o.updated_at
                FROM organization o
                LEFT JOIN stored_file license ON license.id=o.business_license_file_id
                LEFT JOIN person legal ON legal.id=o.legal_representative_id
                LEFT JOIN person primary_person ON primary_person.id=o.primary_responsible_person_id
                LEFT JOIN person quality ON quality.id=o.quality_responsible_person_id
                LEFT JOIN person technical ON technical.id=o.technical_responsible_person_id
                WHERE o.id=:organization
                """).param("organization", user.organizationId()).query((rs, row) -> new Profile(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("short_name"), rs.getString("kind"), rs.getString("organization_nature"),
                rs.getString("unified_credit_code"), rs.getObject("registered_on", LocalDate.class),
                rs.getString("registration_authority"), rs.getString("postal_code"), rs.getString("province"),
                rs.getString("city"), rs.getString("district"), rs.getString("detailed_address"),
                rs.getBigDecimal("registered_capital"), rs.getString("telephone"), rs.getString("fax"),
                rs.getString("email"), rs.getObject("legal_representative_id", UUID.class),
                rs.getString("frequent_contact_name"), contact(rs.getString("frequent_contact_phone"), fullContact),
                rs.getObject("primary_responsible_person_id", UUID.class),
                rs.getObject("quality_responsible_person_id", UUID.class),
                rs.getObject("technical_responsible_person_id", UUID.class),
                (Integer) rs.getObject("declared_equipment_count"), rs.getBigDecimal("declared_equipment_original_value"),
                rs.getBigDecimal("testing_area"), rs.getBigDecimal("workplace_area"), rs.getString("profile_remark"),
                rs.getObject("business_license_file_id", UUID.class), rs.getString("business_license_file_name"),
                person(rs, "legal", rs.getObject("legal_representative_id", UUID.class), fullContact),
                person(rs, "primary", rs.getObject("primary_responsible_person_id", UUID.class), fullContact),
                person(rs, "quality", rs.getObject("quality_responsible_person_id", UUID.class), fullContact),
                person(rs, "technical", rs.getObject("technical_responsible_person_id", UUID.class), fullContact),
                rs.getBoolean("active"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class))).optional()
                .orElseThrow(() -> ApiException.notFound("ORGANIZATION_NOT_FOUND", "单位不存在"));
    }

    @Transactional
    public Profile update(ProfileWrite body) {
        Profile before = profile();
        divisions.validate(body.province(), body.city(), body.district());
        requirePeople(List.of(body.legalRepresentativeId(), body.primaryResponsiblePersonId(),
                body.qualityResponsiblePersonId(), body.technicalResponsiblePersonId()));
        try {
            int changed = jdbc.sql("""
                    UPDATE organization SET name=:name,short_name=:shortName,organization_nature=:nature,
                      unified_credit_code=:creditCode,registered_on=:registeredOn,
                      registration_authority=:authority,postal_code=:postalCode,province=:province,
                      city=:city,district=:district,detailed_address=:address,
                      registered_capital=:capital,telephone=:telephone,fax=:fax,email=:email,
                      legal_representative_id=:legal,frequent_contact_name=:contactName,
                      frequent_contact_phone=:contactPhone,primary_responsible_person_id=:primary,
                      quality_responsible_person_id=:quality,technical_responsible_person_id=:technical,
                      declared_equipment_count=:equipmentCount,
                      declared_equipment_original_value=:equipmentValue,testing_area=:testingArea,
                      workplace_area=:workplaceArea,profile_remark=:remark,version=version+1,updated_at=now()
                    WHERE id=:organization AND version=:version
                    """).param("name", clean(body.fullName())).param("shortName", clean(body.shortName()))
                    .param("nature", body.organizationNature()).param("creditCode", body.unifiedCreditCode().trim().toUpperCase(Locale.ROOT))
                    .param("registeredOn", body.registeredOn()).param("authority", clean(body.registrationAuthority()))
                    .param("postalCode", body.postalCode().trim()).param("province", clean(body.province()))
                    .param("city", clean(body.city())).param("district", clean(body.district()))
                    .param("address", clean(body.detailedAddress())).param("capital", body.registeredCapital())
                    .param("telephone", clean(body.telephone())).param("fax", clean(body.fax()))
                    .param("email", clean(body.email())).param("legal", body.legalRepresentativeId())
                    .param("contactName", clean(body.frequentContactName()))
                    .param("contactPhone", clean(body.frequentContactPhone()))
                    .param("primary", body.primaryResponsiblePersonId()).param("quality", body.qualityResponsiblePersonId())
                    .param("technical", body.technicalResponsiblePersonId())
                    .param("equipmentCount", body.declaredEquipmentCount())
                    .param("equipmentValue", body.declaredEquipmentOriginalValue())
                    .param("testingArea", body.testingArea()).param("workplaceArea", body.workplaceArea())
                    .param("remark", clean(body.remark())).param("organization", actor.require().organizationId())
                    .param("version", body.expectedVersion()).update();
            if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "单位资料已被其他操作更新", profile().version());
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "ORGANIZATION_CREDIT_CODE_CONFLICT", "统一社会信用代码已存在");
        }
        Profile after = profile();
        audit.record("ORGANIZATION_PROFILE_UPDATED", "ORGANIZATION", after.id(), before, after, null, null);
        return after;
    }

    @Transactional
    public Profile bindBusinessLicense(BusinessLicenseWrite body) {
        Profile before = profile();
        StoredFileService.View file = files.requireCurrentUpload(body.fileId());
        if (!file.purpose().equals("ORGANIZATION_BUSINESS_LICENSE") || !LICENSE_MEDIA.contains(file.mediaType())
                || file.sizeBytes() > 10L * 1024 * 1024) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_LICENSE_FILE_INVALID",
                    "营业执照只接受当前账号上传且不超过10MB的 JPG、PNG 或 BMP 图片");
        }
        int changed = jdbc.sql("""
                UPDATE organization SET business_license_file_id=:file,version=version+1,updated_at=now()
                WHERE id=:organization AND version=:version
                """).param("file", body.fileId()).param("organization", actor.require().organizationId())
                .param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "单位资料已被其他操作更新", profile().version());
        Profile after = profile();
        audit.record("ORGANIZATION_BUSINESS_LICENSE_BOUND", "ORGANIZATION", after.id(), before, after, null, null);
        return after;
    }

    public List<OfficeAddressView> officeAddresses(boolean includeRetired) {
        return jdbc.sql(addressSelect() + """
                WHERE address.organization_id=:organization AND (:includeRetired OR address.active)
                ORDER BY address.active DESC,address.correspondence_address DESC,address.updated_at DESC,address.id DESC
                """).param("organization", actor.require().organizationId()).param("includeRetired", includeRetired)
                .query((rs, row) -> address(rs)).list();
    }

    public OfficeAddressView officeAddress(UUID id) {
        return jdbc.sql(addressSelect() + " WHERE address.id=:id AND address.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId())
                .query((rs, row) -> address(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("ORGANIZATION_ADDRESS_NOT_FOUND", "办公场所不存在"));
    }

    @Transactional
    public OfficeAddressView createOfficeAddress(OfficeAddressWrite body) {
        validateAddress(body, null);
        lockOrganization();
        ensureAddressNameAvailable(body.officeName(), null);
        if (body.correspondenceAddress()) ensureCorrespondenceAvailable(null);
        UUID id = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        AuthenticatedUser user = actor.require();
        jdbc.sql("""
                INSERT INTO organization_office_address(id,organization_id,current_revision_id,
                  correspondence_address,created_by) VALUES (:id,:organization,:revision,:correspondence,:actor)
                """).param("id", id).param("organization", user.organizationId()).param("revision", revisionId)
                .param("correspondence", body.correspondenceAddress()).param("actor", user.id()).update();
        insertRevision(revisionId, id, 1, body);
        OfficeAddressView created = officeAddress(id);
        audit.record("ORGANIZATION_ADDRESS_CREATED", "ORGANIZATION_ADDRESS", id, null, created, null, null);
        return created;
    }

    @Transactional
    public OfficeAddressView updateOfficeAddress(UUID id, OfficeAddressWrite body) {
        if (body.expectedVersion() == null) throw invalid("ADDRESS_VERSION_REQUIRED", "修改办公场所必须携带当前版本");
        validateAddress(body, id);
        lockOrganization();
        OfficeAddressView before = lockAddress(id);
        if (before.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "办公场所已被其他操作更新", before.version());
        }
        if (!before.active()) throw ApiException.blocked("ORGANIZATION_ADDRESS_RETIRED",
                "已停用办公场所不可修改", before.version(), List.of("请新建办公场所或恢复该记录"));
        ensureAddressNameAvailable(body.officeName(), id);
        if (body.correspondenceAddress() && !before.correspondenceAddress()) ensureCorrespondenceAvailable(id);
        UUID revisionId = UUID.randomUUID();
        insertRevision(revisionId, id, before.revisionNumber() + 1, body);
        int changed = jdbc.sql("""
                UPDATE organization_office_address SET current_revision_id=:revision,
                  correspondence_address=:correspondence,version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("revision", revisionId).param("correspondence", body.correspondenceAddress())
                .param("id", id).param("organization", actor.require().organizationId())
                .param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "办公场所已被其他操作更新", officeAddress(id).version());
        OfficeAddressView after = officeAddress(id);
        audit.record("ORGANIZATION_ADDRESS_REVISED", "ORGANIZATION_ADDRESS", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TransitionResult transitionOfficeAddress(UUID id, TransitionCommand command) {
        lockOrganization();
        OfficeAddressView before = lockAddress(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "办公场所已被其他操作更新", before.version());
        }
        String action = command.action().trim().toUpperCase(Locale.ROOT);
        boolean target;
        if (action.equals("RETIRE") && before.active()) target = false;
        else if (action.equals("RESTORE") && !before.active()) target = true;
        else throw new ApiException(HttpStatus.CONFLICT, "ORGANIZATION_ADDRESS_ACTION_INVALID", "当前状态不允许执行 " + action);
        String reason = clean(command.comment());
        if (!target && (reason == null || reason.isBlank())) {
            throw invalid("ORGANIZATION_ADDRESS_RETIRE_REASON_REQUIRED", "停用办公场所必须填写原因");
        }
        if (target && before.correspondenceAddress()) ensureCorrespondenceAvailable(id);
        int changed = jdbc.sql("""
                UPDATE organization_office_address SET active=:active,retired_reason=:reason,
                  version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("active", target).param("reason", target ? null : reason).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", command.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "办公场所已被其他操作更新", officeAddress(id).version());
        OfficeAddressView after = officeAddress(id);
        audit.record("ORGANIZATION_ADDRESS_" + action, "ORGANIZATION_ADDRESS", id, before, after,
                command.reasonCode(), reason);
        return new TransitionResult(target ? "ACTIVE" : "RETIRED", after.version(),
                target ? List.of("RETIRE") : List.of("RESTORE"));
    }

    public List<OfficeAddressRevision> officeAddressHistory(UUID id) {
        officeAddress(id);
        return jdbc.sql("""
                SELECT id,revision_number,office_name,province,city,district,detailed_address,postal_code,
                  changed_on,correspondence_address,created_at
                FROM organization_office_address_revision WHERE address_id=:address
                ORDER BY revision_number DESC,id DESC
                """).param("address", id).query((rs, row) -> new OfficeAddressRevision(
                rs.getObject("id", UUID.class), rs.getInt("revision_number"), rs.getString("office_name"),
                rs.getString("province"), rs.getString("city"), rs.getString("district"),
                rs.getString("detailed_address"), fullAddress(rs.getString("province"), rs.getString("city"),
                rs.getString("district"), rs.getString("detailed_address")), rs.getString("postal_code"),
                rs.getObject("changed_on", LocalDate.class), rs.getBoolean("correspondence_address"),
                rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    public void requestElectronicSignatureEnrollment(ElectronicSignatureEnrollment body) {
        Profile current = profile();
        if (current.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "单位资料已被其他操作更新", current.version());
        }
        if (!Boolean.TRUE.equals(body.certificationAgreement())
                || !Boolean.TRUE.equals(body.digitalCertificateAgreement())
                || !Boolean.TRUE.equals(body.privacyPolicy())) {
            throw invalid("ORGANIZATION_ESIGN_AGREEMENTS_REQUIRED", "必须逐项阅读并同意全部协议");
        }
        List<String> profileBlockers = profileBlockers(current);
        if (!profileBlockers.isEmpty()) {
            audit.recordRejected("ORGANIZATION_ESIGN_ENROLLMENT_BLOCKED", "ORGANIZATION", current.id(),
                    Map.of("agreementsAccepted", true, "missingFields", profileBlockers),
                    "ORGANIZATION_PROFILE_INCOMPLETE", "单位资料不完整，未向外部服务发送任何数据");
            throw ApiException.blocked("ORGANIZATION_PROFILE_INCOMPLETE", "请先完善单位资料后再申请电子签章开户",
                    current.version(), profileBlockers);
        }
        audit.recordRejected("ORGANIZATION_ESIGN_ENROLLMENT_BLOCKED", "ORGANIZATION", current.id(),
                Map.of("agreementsAccepted", true), "EXTERNAL_PROVIDER_UNAVAILABLE",
                "电子签章供应商协议、证书和联调沙箱未配置，未生成模拟开户结果");
        throw ApiException.blocked("EXTERNAL_PROVIDER_UNAVAILABLE", "电子签章开户依赖尚未就绪",
                current.version(), List.of("缺少供应商协议", "缺少生产证书与凭据", "缺少联调沙箱与验收适配器"));
    }

    private String addressSelect() {
        return """
                SELECT address.id,revision.office_name,revision.province,revision.city,revision.district,
                  revision.detailed_address,revision.postal_code,revision.changed_on,
                  address.correspondence_address,address.active,revision.revision_number,address.version,
                  address.updated_at
                FROM organization_office_address address
                JOIN organization_office_address_revision revision ON revision.id=address.current_revision_id
                """;
    }

    private OfficeAddressView address(java.sql.ResultSet rs) throws java.sql.SQLException {
        boolean active = rs.getBoolean("active");
        return new OfficeAddressView(rs.getObject("id", UUID.class), rs.getString("office_name"),
                rs.getString("province"), rs.getString("city"), rs.getString("district"),
                rs.getString("detailed_address"), fullAddress(rs.getString("province"), rs.getString("city"),
                rs.getString("district"), rs.getString("detailed_address")), rs.getString("postal_code"),
                rs.getObject("changed_on", LocalDate.class), rs.getBoolean("correspondence_address"), active,
                rs.getInt("revision_number"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class),
                active ? List.of("REVISE", "RETIRE") : List.of("RESTORE"));
    }

    private void validateAddress(OfficeAddressWrite body, UUID id) {
        divisions.validate(body.province(), body.city(), body.district());
        if (body.changedOn().isAfter(LocalDate.now())) {
            throw invalid("ORGANIZATION_ADDRESS_DATE_INVALID", "办公场所变更日期不能晚于今天");
        }
    }

    private void insertRevision(UUID revisionId, UUID addressId, int revision, OfficeAddressWrite body) {
        jdbc.sql("""
                INSERT INTO organization_office_address_revision(id,address_id,revision_number,office_name,
                  province,city,district,detailed_address,postal_code,changed_on,correspondence_address,created_by)
                VALUES (:id,:address,:revision,:office,:province,:city,:district,:detail,:postal,:changedOn,
                  :correspondence,:actor)
                """).param("id", revisionId).param("address", addressId).param("revision", revision)
                .param("office", clean(body.officeName())).param("province", clean(body.province()))
                .param("city", clean(body.city())).param("district", clean(body.district()))
                .param("detail", clean(body.detailedAddress())).param("postal", body.postalCode().trim())
                .param("changedOn", body.changedOn()).param("correspondence", body.correspondenceAddress())
                .param("actor", actor.require().id()).update();
    }

    private void requirePeople(List<UUID> people) {
        long count = jdbc.sql("""
                SELECT count(*) FROM person p JOIN department d ON d.id=p.department_id
                WHERE p.id IN (:people) AND p.active AND d.organization_id=:organization
                """).param("people", people).param("organization", actor.require().organizationId())
                .query(Long.class).single();
        if (count != people.stream().distinct().count()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ORGANIZATION_RESPONSIBLE_PERSON_INVALID",
                    "负责人必须来自当前机构的启用人员档案");
        }
    }

    private void ensureAddressNameAvailable(String officeName, UUID excluded) {
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM organization_office_address address
                  JOIN organization_office_address_revision revision ON revision.id=address.current_revision_id
                  WHERE address.organization_id=:organization AND address.active
                    AND upper(revision.office_name)=upper(:name) AND address.id<>:excluded)
                """).param("organization", actor.require().organizationId()).param("name", clean(officeName))
                .param("excluded", excluded == null ? new UUID(0, 0) : excluded).query(Boolean.class).single();
        if (exists) throw new ApiException(HttpStatus.CONFLICT, "ORGANIZATION_ADDRESS_NAME_CONFLICT", "办公场所名称已存在");
    }

    private void ensureCorrespondenceAvailable(UUID excluded) {
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM organization_office_address
                  WHERE organization_id=:organization AND active AND correspondence_address AND id<>:excluded)
                """).param("organization", actor.require().organizationId())
                .param("excluded", excluded == null ? new UUID(0, 0) : excluded).query(Boolean.class).single();
        if (exists) throw ApiException.blocked("ORGANIZATION_CORRESPONDENCE_ADDRESS_CONFLICT",
                "当前收件地址只能有一个", 0, List.of("请先把现有收件地址修改为否，再设置新的收件地址"));
    }

    private void lockOrganization() {
        jdbc.sql("SELECT id FROM organization WHERE id=:organization FOR UPDATE")
                .param("organization", actor.require().organizationId()).query(UUID.class).single();
    }

    private OfficeAddressView lockAddress(UUID id) {
        jdbc.sql("""
                SELECT id FROM organization_office_address
                WHERE id=:id AND organization_id=:organization FOR UPDATE
                """).param("id", id).param("organization", actor.require().organizationId())
                .query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("ORGANIZATION_ADDRESS_NOT_FOUND", "办公场所不存在"));
        return officeAddress(id);
    }

    private PersonCard person(java.sql.ResultSet rs, String prefix, UUID id, boolean fullContact)
            throws java.sql.SQLException {
        if (id == null) return null;
        return new PersonCard(id, rs.getString(prefix + "_name"), rs.getString(prefix + "_title"),
                rs.getString(prefix + "_position"), contact(rs.getString(prefix + "_phone"), fullContact),
                rs.getObject(prefix + "_avatar", UUID.class));
    }

    private String contact(String value, boolean full) {
        if (value == null || value.isBlank() || full) return value;
        String clean = value.trim();
        if (clean.length() <= 7) return "***";
        return clean.substring(0, 3) + "****" + clean.substring(clean.length() - 4);
    }

    private String fullAddress(String province, String city, String district, String detail) {
        return String.join(" · ", List.of(province, city, district)) + " · " + detail;
    }

    private List<String> profileBlockers(Profile profile) {
        java.util.ArrayList<String> blockers = new java.util.ArrayList<>();
        if (profile.shortName() == null) blockers.add("缺少单位简称");
        if (profile.organizationNature() == null) blockers.add("缺少单位性质");
        if (profile.unifiedCreditCode() == null) blockers.add("缺少统一社会信用代码");
        if (profile.registeredOn() == null) blockers.add("缺少注册时间");
        if (profile.registrationAuthority() == null) blockers.add("缺少发证机关");
        if (profile.postalCode() == null) blockers.add("缺少邮政编码");
        if (profile.province() == null || profile.city() == null || profile.district() == null
                || profile.detailedAddress() == null) blockers.add("缺少注册地址");
        if (profile.registeredCapital() == null) blockers.add("缺少注册资本");
        if (profile.legalRepresentativeId() == null || profile.primaryResponsiblePersonId() == null
                || profile.qualityResponsiblePersonId() == null || profile.technicalResponsiblePersonId() == null) {
            blockers.add("缺少机构负责人配置");
        }
        if (profile.telephone() == null || profile.fax() == null || profile.frequentContactName() == null
                || profile.frequentContactPhone() == null) blockers.add("缺少机构联系信息");
        if (profile.declaredEquipmentCount() == null || profile.declaredEquipmentOriginalValue() == null
                || profile.testingArea() == null || profile.workplaceArea() == null) blockers.add("缺少能力声明");
        if (profile.remark() == null) blockers.add("缺少备注说明");
        return List.copyOf(blockers);
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
