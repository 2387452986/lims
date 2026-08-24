package com.lanchuan.lims.commission;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class CustomerDirectoryService {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;

    public CustomerDirectoryService(JdbcClient jdbc, CurrentActor actor, AuditService audit) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
    }

    public record Summary(UUID id, String code, String shortName, String name, String unitType,
                          String unifiedCreditCode, boolean commissioningUnit, String settlementName,
                          String settlementPhone, String contactName, String contactPhone,
                          long commissionedProjectCount, long participatingProjectCount, long accountCount,
                          boolean active, long version, OffsetDateTime updatedAt) {}
    public record SettlementContactView(UUID id, String name, String phone, boolean otherContact,
                                        boolean primaryContact, boolean active, long version,
                                        OffsetDateTime updatedAt) {}
    public record PersonView(UUID id, String name, String gender, String personType, String phone,
                             String certificateNumber, String remark, UUID avatarFileId, boolean active,
                             long version, OffsetDateTime updatedAt) {}
    public record AccountView(UUID userId, String username, String displayName, String relationship,
                              OffsetDateTime createdAt) {}
    public record Detail(Summary summary, String landline, String address, String fax, String email,
                         String remark, List<SettlementContactView> settlementContacts,
                         List<PersonView> people, List<AccountView> accounts) {}
    public record Write(String code, String shortName, String name, String unitType,
                        String unifiedCreditCode, boolean commissioningUnit, String contactName,
                        String contactPhone, String landline, String address, String fax, String email,
                        String remark, boolean active, Long expectedVersion) {}
    public record SettlementContactWrite(String name, String phone, boolean otherContact,
                                         boolean primaryContact, boolean active, Long expectedVersion) {}
    public record PersonWrite(String name, String gender, String personType, String phone,
                              String certificateNumber, String remark, UUID avatarFileId,
                              boolean active, Long expectedVersion) {}

    public CursorPage<Summary> list(String query, String unitType, String code, String name,
                                    String settlementName, String settlementPhone, String contactName,
                                    String contactPhone, Boolean active, String cursorValue, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        PageCursor cursor = PageCursor.decode(cursorValue);
        OffsetDateTime cursorAt = cursor == null ? OffsetDateTime.parse("9999-12-31T23:59:59Z") : cursor.updatedAt();
        UUID cursorId = cursor == null ? new UUID(-1L, -1L) : cursor.id();
        String pattern = "%" + text(query).toLowerCase() + "%";
        String sql = baseSelect() + """
                WHERE c.organization_id=:organization
                  AND (:unitType='' OR c.unit_type=:unitType)
                  AND (:code='' OR lower(c.code) LIKE :code)
                  AND (:name='' OR lower(c.name) LIKE :name OR lower(c.short_name) LIKE :name)
                  AND (:settlementNameQuery='' OR EXISTS(SELECT 1 FROM customer_settlement_contact sc
                      WHERE sc.customer_id=c.id AND sc.active AND lower(sc.name) LIKE :settlementName))
                  AND (:settlementPhoneQuery='' OR EXISTS(SELECT 1 FROM customer_settlement_contact sc
                      WHERE sc.customer_id=c.id AND sc.active AND lower(COALESCE(sc.phone,'')) LIKE :settlementPhone))
                  AND (:contactName='' OR lower(COALESCE(c.contact_name,'')) LIKE :contactName)
                  AND (:contactPhone='' OR lower(COALESCE(c.contact_phone,'')) LIKE :contactPhone)
                  AND (CAST(:active AS boolean) IS NULL OR c.active=CAST(:active AS boolean))
                  AND (:query='' OR lower(c.code) LIKE :pattern OR lower(c.short_name) LIKE :pattern
                      OR lower(c.name) LIKE :pattern OR lower(COALESCE(c.unified_credit_code,'')) LIKE :pattern
                      OR lower(COALESCE(c.contact_name,'')) LIKE :pattern
                      OR lower(COALESCE(c.contact_phone,'')) LIKE :pattern)
                  AND (c.updated_at, c.id) < (:cursorAt, :cursorId)
                ORDER BY c.updated_at DESC, c.id DESC LIMIT :limit
                """;
        var params = jdbc.sql(sql).param("organization", actor.require().organizationId())
                .param("unitType", upper(unitType)).param("code", like(code)).param("name", like(name))
                .param("settlementNameQuery", text(settlementName)).param("settlementName", like(settlementName))
                .param("settlementPhoneQuery", text(settlementPhone)).param("settlementPhone", like(settlementPhone))
                .param("contactName", like(contactName)).param("contactPhone", like(contactPhone))
                .param("active", active).param("query", text(query)).param("pattern", pattern)
                .param("cursorAt", cursorAt).param("cursorId", cursorId).param("limit", limit + 1);
        List<Summary> fetched = params.query(this::mapSummary).list();
        boolean hasMore = fetched.size() > limit;
        List<Summary> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        long total = jdbc.sql("""
                SELECT count(*) FROM customer c WHERE c.organization_id=:organization
                  AND (:unitType='' OR c.unit_type=:unitType)
                  AND (:code='' OR lower(c.code) LIKE :code)
                  AND (:name='' OR lower(c.name) LIKE :name OR lower(c.short_name) LIKE :name)
                  AND (:settlementNameQuery='' OR EXISTS(SELECT 1 FROM customer_settlement_contact sc
                      WHERE sc.customer_id=c.id AND sc.active AND lower(sc.name) LIKE :settlementName))
                  AND (:settlementPhoneQuery='' OR EXISTS(SELECT 1 FROM customer_settlement_contact sc
                      WHERE sc.customer_id=c.id AND sc.active AND lower(COALESCE(sc.phone,'')) LIKE :settlementPhone))
                  AND (:contactName='' OR lower(COALESCE(c.contact_name,'')) LIKE :contactName)
                  AND (:contactPhone='' OR lower(COALESCE(c.contact_phone,'')) LIKE :contactPhone)
                  AND (CAST(:active AS boolean) IS NULL OR c.active=CAST(:active AS boolean))
                  AND (:query='' OR lower(c.code) LIKE :pattern OR lower(c.short_name) LIKE :pattern
                    OR lower(c.name) LIKE :pattern OR lower(COALESCE(c.unified_credit_code,'')) LIKE :pattern
                    OR lower(COALESCE(c.contact_name,'')) LIKE :pattern
                    OR lower(COALESCE(c.contact_phone,'')) LIKE :pattern)
                """).param("organization", actor.require().organizationId()).param("unitType", upper(unitType))
                .param("code", like(code)).param("name", like(name))
                .param("settlementNameQuery", text(settlementName)).param("settlementName", like(settlementName))
                .param("settlementPhoneQuery", text(settlementPhone)).param("settlementPhone", like(settlementPhone))
                .param("contactName", like(contactName)).param("contactPhone", like(contactPhone))
                .param("active", active).param("query", text(query)).param("pattern", pattern)
                .query(Long.class).single();
        return new CursorPage<>(items, next, hasMore, total);
    }

    public Detail detail(UUID id) {
        Summary summary = require(id);
        record Extra(String landline, String address, String fax, String email, String remark) {}
        Extra extra = jdbc.sql("SELECT landline,address,fax,email,remark FROM customer WHERE id=:id")
                .param("id", id).query((rs, row) -> new Extra(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5))).single();
        return new Detail(summary, extra.landline(), extra.address(), extra.fax(), extra.email(), extra.remark(),
                settlementContacts(id), people(id), actor.require().permissions().contains("SYSTEM_ADMIN") ? accounts(id) : List.of());
    }

    @Transactional
    public Detail create(Write body) {
        validate(body);
        ensureCodeAvailable(body.code(), null);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer(id, organization_id, code, short_name, name, unit_type,
                  unified_credit_code, commissioning_unit, contact_name, contact_phone, landline,
                  address, fax, email, remark, active)
                VALUES (:id,:organization,:code,:shortName,:name,:unitType,:credit,:commissioning,
                  :contact,:phone,:landline,:address,:fax,:email,:remark,:active)
                """).param("id", id).param("organization", actor.require().organizationId())
                .param("code", body.code().trim()).param("shortName", body.shortName().trim())
                .param("name", body.name().trim()).param("unitType", upper(body.unitType()))
                .param("credit", nullable(body.unifiedCreditCode())).param("commissioning", body.commissioningUnit())
                .param("contact", nullable(body.contactName())).param("phone", nullable(body.contactPhone()))
                .param("landline", nullable(body.landline())).param("address", nullable(body.address()))
                .param("fax", nullable(body.fax())).param("email", nullable(body.email()))
                .param("remark", nullable(body.remark())).param("active", body.active()).update();
        Detail created = detail(id);
        audit.record("CUSTOMER_CREATED", "CUSTOMER", id, null, created, null, null);
        return created;
    }

    @Transactional
    public Detail update(UUID id, Write body) {
        validate(body);
        Detail before = detail(id);
        ensureCodeAvailable(body.code(), id);
        if (body.expectedVersion() == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                "EXPECTED_VERSION_REQUIRED", "更新客户必须携带当前版本");
        int changed = jdbc.sql("""
                UPDATE customer SET code=:code, short_name=:shortName, name=:name, unit_type=:unitType,
                  unified_credit_code=:credit, commissioning_unit=:commissioning, contact_name=:contact,
                  contact_phone=:phone, landline=:landline, address=:address, fax=:fax, email=:email,
                  remark=:remark, active=:active, version=version+1, updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("code", body.code().trim()).param("shortName", body.shortName().trim())
                .param("name", body.name().trim()).param("unitType", upper(body.unitType()))
                .param("credit", nullable(body.unifiedCreditCode())).param("commissioning", body.commissioningUnit())
                .param("contact", nullable(body.contactName())).param("phone", nullable(body.contactPhone()))
                .param("landline", nullable(body.landline())).param("address", nullable(body.address()))
                .param("fax", nullable(body.fax())).param("email", nullable(body.email()))
                .param("remark", nullable(body.remark())).param("active", body.active()).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "客户信息已被其他用户更新", require(id).version());
        Detail after = detail(id);
        audit.record("CUSTOMER_UPDATED", "CUSTOMER", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TransitionResult transition(UUID id, TransitionCommand command) {
        Summary before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "客户信息已被其他用户更新", before.version());
        }
        String action = upper(command.action());
        boolean active = switch (action) {
            case "ACTIVATE" -> true;
            case "DEACTIVATE" -> false;
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CUSTOMER_ACTION_INVALID", "当前客户不支持该操作");
        };
        if (active == before.active()) throw new ApiException(HttpStatus.CONFLICT,
                "CUSTOMER_STATE_UNCHANGED", active ? "客户已经启用" : "客户已经停用");
        jdbc.sql("UPDATE customer SET active=:active, version=version+1, updated_at=now() WHERE id=:id AND version=:version")
                .param("active", active).param("id", id).param("version", before.version()).update();
        Summary after = require(id);
        audit.record("CUSTOMER_" + action, "CUSTOMER", id, before, after, command.reasonCode(), command.comment());
        return new TransitionResult(active ? "ACTIVE" : "INACTIVE", after.version(),
                List.of(active ? "DEACTIVATE" : "ACTIVATE"));
    }

    public List<SettlementContactView> settlementContacts(UUID customerId) {
        require(customerId);
        return jdbc.sql("""
                SELECT id,name,phone,other_contact,primary_contact,active,version,updated_at
                FROM customer_settlement_contact WHERE customer_id=:customer
                ORDER BY primary_contact DESC, active DESC, updated_at DESC, id DESC
                """).param("customer", customerId).query((rs, row) -> new SettlementContactView(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("phone"),
                        rs.getBoolean("other_contact"), rs.getBoolean("primary_contact"), rs.getBoolean("active"),
                        rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    @Transactional
    public SettlementContactView saveSettlementContact(UUID customerId, UUID contactId,
                                                       SettlementContactWrite body) {
        require(customerId);
        if (body.name() == null || body.name().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,
                "SETTLEMENT_CONTACT_NAME_REQUIRED", "结算人姓名不能为空");
        if (body.primaryContact()) jdbc.sql("""
                UPDATE customer_settlement_contact SET primary_contact=false, version=version+1, updated_at=now()
                WHERE customer_id=:customer AND primary_contact
                  AND (CAST(:id AS uuid) IS NULL OR id<>CAST(:id AS uuid))
                """).param("customer", customerId).param("id", contactId).update();
        UUID id = contactId == null ? UUID.randomUUID() : contactId;
        if (contactId == null) {
            jdbc.sql("""
                    INSERT INTO customer_settlement_contact(id,customer_id,name,phone,other_contact,primary_contact,active)
                    VALUES (:id,:customer,:name,:phone,:other,:primary,:active)
                    """).param("id", id).param("customer", customerId).param("name", body.name().trim())
                    .param("phone", nullable(body.phone())).param("other", body.otherContact())
                    .param("primary", body.primaryContact()).param("active", body.active()).update();
            SettlementContactView created = settlementContact(customerId, id);
            audit.record("CUSTOMER_SETTLEMENT_CONTACT_CREATED", "CUSTOMER", customerId, null, created, null, null);
            return created;
        }
        SettlementContactView before = settlementContact(customerId, id);
        if (body.expectedVersion() == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                "EXPECTED_VERSION_REQUIRED", "更新结算人必须携带当前版本");
        int changed = jdbc.sql("""
                UPDATE customer_settlement_contact SET name=:name,phone=:phone,other_contact=:other,
                  primary_contact=:primary,active=:active,version=version+1,updated_at=now()
                WHERE id=:id AND customer_id=:customer AND version=:version
                """).param("name", body.name().trim()).param("phone", nullable(body.phone()))
                .param("other", body.otherContact()).param("primary", body.primaryContact())
                .param("active", body.active()).param("id", id).param("customer", customerId)
                .param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "结算人信息已被更新",
                settlementContact(customerId, id).version());
        SettlementContactView after = settlementContact(customerId, id);
        audit.record("CUSTOMER_SETTLEMENT_CONTACT_UPDATED", "CUSTOMER", customerId, before, after, null, null);
        return after;
    }

    public List<PersonView> people(UUID customerId) {
        require(customerId);
        return jdbc.sql("""
                SELECT id,name,gender,person_type,phone,certificate_number,remark,avatar_file_id,
                  active,version,updated_at FROM customer_person WHERE customer_id=:customer
                ORDER BY active DESC, updated_at DESC, id DESC
                """).param("customer", customerId).query((rs, row) -> new PersonView(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("gender"),
                        rs.getString("person_type"), rs.getString("phone"), rs.getString("certificate_number"),
                        rs.getString("remark"), rs.getObject("avatar_file_id", UUID.class), rs.getBoolean("active"),
                        rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    @Transactional
    public PersonView savePerson(UUID customerId, UUID personId, PersonWrite body) {
        require(customerId);
        if (body.name() == null || body.name().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,
                "CUSTOMER_PERSON_NAME_REQUIRED", "客户人员姓名不能为空");
        validateGender(body.gender());
        validatePersonType(body.personType());
        UUID id = personId == null ? UUID.randomUUID() : personId;
        if (personId == null) {
            jdbc.sql("""
                    INSERT INTO customer_person(id,customer_id,name,gender,person_type,phone,certificate_number,
                      remark,avatar_file_id,active)
                    VALUES (:id,:customer,:name,:gender,:type,:phone,:certificate,:remark,:avatar,:active)
                    """).param("id", id).param("customer", customerId).param("name", body.name().trim())
                    .param("gender", upper(body.gender())).param("type", upper(body.personType()))
                    .param("phone", nullable(body.phone())).param("certificate", nullable(body.certificateNumber()))
                    .param("remark", nullable(body.remark())).param("avatar", body.avatarFileId())
                    .param("active", body.active()).update();
            PersonView created = person(customerId, id);
            audit.record("CUSTOMER_PERSON_CREATED", "CUSTOMER", customerId, null, created, null, null);
            return created;
        }
        PersonView before = person(customerId, id);
        if (body.expectedVersion() == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                "EXPECTED_VERSION_REQUIRED", "更新客户人员必须携带当前版本");
        int changed = jdbc.sql("""
                UPDATE customer_person SET name=:name,gender=:gender,person_type=:type,phone=:phone,
                  certificate_number=:certificate,remark=:remark,avatar_file_id=:avatar,active=:active,
                  version=version+1,updated_at=now()
                WHERE id=:id AND customer_id=:customer AND version=:version
                """).param("name", body.name().trim()).param("gender", upper(body.gender()))
                .param("type", upper(body.personType())).param("phone", nullable(body.phone()))
                .param("certificate", nullable(body.certificateNumber())).param("remark", nullable(body.remark()))
                .param("avatar", body.avatarFileId()).param("active", body.active()).param("id", id)
                .param("customer", customerId).param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "客户人员信息已被更新",
                person(customerId, id).version());
        PersonView after = person(customerId, id);
        audit.record("CUSTOMER_PERSON_UPDATED", "CUSTOMER", customerId, before, after, null, null);
        return after;
    }

    public List<AccountView> accounts(UUID customerId) {
        require(customerId);
        return jdbc.sql("""
                SELECT l.user_id,u.username,u.display_name,l.relationship,l.created_at
                FROM customer_account_link l JOIN app_user u ON u.id=l.user_id
                WHERE l.customer_id=:customer ORDER BY l.created_at DESC
                """).param("customer", customerId).query((rs, row) -> new AccountView(
                        rs.getObject("user_id", UUID.class), rs.getString("username"), rs.getString("display_name"),
                        rs.getString("relationship"), rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    @Transactional
    public AccountView linkAccount(UUID customerId, UUID userId, String relationship) {
        require(customerId);
        String normalized = upper(relationship);
        if (!List.of("ADMIN", "MEMBER", "REPORT_VIEWER").contains(normalized)) throw new ApiException(
                HttpStatus.BAD_REQUEST, "CUSTOMER_ACCOUNT_RELATIONSHIP_INVALID", "客户账号关系无效");
        int inserted = jdbc.sql("""
                INSERT INTO customer_account_link(customer_id,user_id,relationship,created_by)
                SELECT :customer,u.id,:relationship,:actor FROM app_user u
                WHERE u.id=:user AND u.organization_id=:organization
                ON CONFLICT(customer_id,user_id) DO UPDATE SET relationship=EXCLUDED.relationship
                """).param("customer", customerId).param("user", userId).param("relationship", normalized)
                .param("actor", actor.require().id()).param("organization", actor.require().organizationId()).update();
        if (inserted == 0) throw ApiException.notFound("USER_NOT_FOUND", "待关联账号不存在");
        AccountView linked = accounts(customerId).stream().filter(value -> value.userId().equals(userId)).findFirst().orElseThrow();
        audit.record("CUSTOMER_ACCOUNT_LINKED", "CUSTOMER", customerId, null, linked, null, null);
        return linked;
    }

    @Transactional
    public void unlinkAccount(UUID customerId, UUID userId) {
        AccountView before = accounts(customerId).stream().filter(value -> value.userId().equals(userId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("CUSTOMER_ACCOUNT_LINK_NOT_FOUND", "客户关联账号不存在"));
        jdbc.sql("DELETE FROM customer_account_link WHERE customer_id=:customer AND user_id=:user")
                .param("customer", customerId).param("user", userId).update();
        audit.record("CUSTOMER_ACCOUNT_UNLINKED", "CUSTOMER", customerId, before, null, null, null);
    }

    private Summary require(UUID id) {
        return jdbc.sql(baseSelect() + " WHERE c.id=:id AND c.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId())
                .query(this::mapSummary).optional()
                .orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "客户不存在"));
    }

    private SettlementContactView settlementContact(UUID customerId, UUID id) {
        return settlementContacts(customerId).stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("SETTLEMENT_CONTACT_NOT_FOUND", "结算人不存在"));
    }

    private PersonView person(UUID customerId, UUID id) {
        return people(customerId).stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("CUSTOMER_PERSON_NOT_FOUND", "客户人员不存在"));
    }

    private String baseSelect() {
        return """
                SELECT c.id,c.code,c.short_name,c.name,c.unit_type,c.unified_credit_code,c.commissioning_unit,
                  c.contact_name,c.contact_phone,c.active,c.version,c.updated_at,
                  sc.name settlement_name,sc.phone settlement_phone,
                  (SELECT count(*) FROM project p WHERE p.customer_id=c.id) commissioned_project_count,
                  (SELECT count(DISTINCT pp.project_id) FROM project_party pp
                    WHERE pp.customer_id=c.id AND pp.party_role<>'ENTRUSTING') participating_project_count,
                  (SELECT count(*) FROM customer_account_link al WHERE al.customer_id=c.id) account_count
                FROM customer c
                LEFT JOIN LATERAL (SELECT name,phone FROM customer_settlement_contact
                  WHERE customer_id=c.id AND active ORDER BY primary_contact DESC,updated_at DESC,id DESC LIMIT 1) sc ON true
                """;
    }

    private Summary mapSummary(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Summary(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("short_name"),
                rs.getString("name"), rs.getString("unit_type"), rs.getString("unified_credit_code"),
                rs.getBoolean("commissioning_unit"), rs.getString("settlement_name"),
                rs.getString("settlement_phone"), rs.getString("contact_name"), rs.getString("contact_phone"),
                rs.getLong("commissioned_project_count"), rs.getLong("participating_project_count"),
                rs.getLong("account_count"), rs.getBoolean("active"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private void validate(Write body) {
        if (body.code() == null || body.code().isBlank() || body.shortName() == null || body.shortName().isBlank()
                || body.name() == null || body.name().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,
                "CUSTOMER_REQUIRED_FIELDS", "单位编号、简称和全称不能为空");
        if (!List.of("OWNER","ENTRUSTING","SUPERVISION","CONSTRUCTION","SAMPLING","DESIGN","SURVEY",
                "GENERAL_CONTRACTOR","OTHER").contains(upper(body.unitType()))) throw new ApiException(
                HttpStatus.BAD_REQUEST, "CUSTOMER_UNIT_TYPE_INVALID", "单位类别无效");
    }

    private void validateGender(String gender) {
        if (!List.of("UNKNOWN", "MALE", "FEMALE").contains(upper(gender))) throw new ApiException(
                HttpStatus.BAD_REQUEST, "CUSTOMER_PERSON_GENDER_INVALID", "人员性别无效");
    }

    private void validatePersonType(String type) {
        if (!List.of("CONTACT","ENTRUSTER","SAMPLER","WITNESS","SETTLEMENT","LEGAL_REPRESENTATIVE","OTHER")
                .contains(upper(type))) throw new ApiException(HttpStatus.BAD_REQUEST,
                "CUSTOMER_PERSON_TYPE_INVALID", "客户人员类型无效");
    }

    private void ensureCodeAvailable(String code, UUID ignoredId) {
        long count = jdbc.sql("""
                SELECT count(*) FROM customer WHERE organization_id=:organization AND lower(code)=lower(:code)
                  AND (CAST(:ignored AS uuid) IS NULL OR id<>CAST(:ignored AS uuid))
                """).param("organization", actor.require().organizationId()).param("code", code.trim())
                .param("ignored", ignoredId).query(Long.class).single();
        if (count > 0) throw new ApiException(HttpStatus.CONFLICT, "CUSTOMER_CODE_EXISTS", "单位自编号已存在");
    }

    private String text(String value) { return value == null ? "" : value.trim(); }
    private String upper(String value) { return text(value).toUpperCase(); }
    private String like(String value) { return "%" + text(value).toLowerCase() + "%"; }
    private String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
