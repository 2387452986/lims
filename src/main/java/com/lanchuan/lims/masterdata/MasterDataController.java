package com.lanchuan.lims.masterdata;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.IdempotencyService;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.configuration.FormulaEvaluator;
import com.lanchuan.lims.identity.CurrentActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/master-data")
@PreAuthorize("hasAuthority('MODULE_READ')")
public class MasterDataController {
    private static final List<String> VALUE_TYPES = List.of("NUMBER", "TEXT", "DATE", "CHOICE", "TABLE", "REPEATING_GROUP", "ATTACHMENT");
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    private final FormulaEvaluator formulas;

    public MasterDataController(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                IdempotencyService idempotency, ObjectMapper json, FormulaEvaluator formulas) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.idempotency = idempotency;
        this.json = json;
        this.formulas = formulas;
    }

    public record OrganizationView(UUID id, String code, String name, String kind, boolean active, long version) {}
    public record OrganizationUpdate(@NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 24) String kind,
                                     boolean active, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record CustomerView(UUID id, String code, String name, String unifiedCreditCode, String contactName,
                               String contactPhone, boolean active, long version) {}
    public record CustomerCreate(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 200) String name,
                                 @Size(max = 32) String unifiedCreditCode, @Size(max = 120) String contactName,
                                 @Size(max = 40) String contactPhone) {}
    public record CustomerUpdate(@NotBlank @Size(max = 200) String name, @Size(max = 32) String unifiedCreditCode,
                                 @Size(max = 120) String contactName, @Size(max = 40) String contactPhone,
                                 boolean active, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record ProjectView(UUID id, String number, String name, UUID customerId, String customerName,
                              String location, String responsiblePerson, String status, long version) {}
    public record ProjectCreate(@NotBlank @Size(max = 64) String number, @NotBlank @Size(max = 240) String name,
                                @NotNull UUID customerId, @Size(max = 240) String location,
                                @Size(max = 120) String responsiblePerson) {}
    public record ProjectUpdate(@NotBlank @Size(max = 240) String name, @NotNull UUID customerId,
                                @Size(max = 240) String location, @Size(max = 120) String responsiblePerson,
                                @NotBlank String status, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record PersonView(UUID id, String employeeNumber, String name, UUID departmentId, String departmentName,
                             String title, String phone, boolean active, long version, long qualificationCount) {}
    public record ParameterView(UUID id, String code, String name, int versionNumber, String valueType,
                                Map<String, Object> definition, String formula, String unit, String status,
                                long version, OffsetDateTime publishedAt, OffsetDateTime updatedAt) {}
    public record ParameterCreate(@NotBlank @Size(max = 80) String code, @NotBlank @Size(max = 200) String name,
                                  @NotBlank String valueType, @NotEmpty Map<String, Object> definition,
                                  @Size(max = 1000) String formula, @Size(max = 40) String unit) {}
    public record ParameterUpdate(@NotBlank @Size(max = 200) String name, @NotBlank String valueType,
                                  @NotEmpty Map<String, Object> definition, @Size(max = 1000) String formula,
                                  @Size(max = 40) String unit, @NotNull @PositiveOrZero Long expectedVersion) {}

    @GetMapping("/organizations")
    public List<OrganizationView> organizations() {
        return jdbc.sql("SELECT id, code, name, kind, active, version FROM organization WHERE id=:id")
                .param("id", actor.require().organizationId()).query((rs, row) -> new OrganizationView(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("kind"), rs.getBoolean("active"), rs.getLong("version"))).list();
    }

    @PutMapping("/organizations/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    @Transactional
    public OrganizationView updateOrganization(@PathVariable UUID id, @Valid @RequestBody OrganizationUpdate body) {
        OrganizationView before = organization(id);
        int changed = jdbc.sql("""
                UPDATE organization SET name=:name, kind=:kind, active=:active, version=version+1, updated_at=now()
                WHERE id=:id AND version=:version
                """).param("name", body.name().trim()).param("kind", body.kind().trim().toUpperCase())
                .param("active", body.active()).param("id", id).param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "单位信息已被更新", organization(id).version());
        OrganizationView after = organization(id);
        audit.record("ORGANIZATION_UPDATED", "ORGANIZATION", id, before, after, null, null);
        return after;
    }

    @GetMapping("/customers")
    public List<CustomerView> customers() {
        return jdbc.sql("SELECT id, code, name, unified_credit_code, contact_name, contact_phone, active, version FROM customer ORDER BY code")
                .query((rs, row) -> new CustomerView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("unified_credit_code"), rs.getString("contact_name"), rs.getString("contact_phone"),
                        rs.getBoolean("active"), rs.getLong("version"))).list();
    }

    @PostMapping("/customers")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public CustomerView createCustomer(@RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody CustomerCreate body) {
        return idempotency.execute(key, "customer-create", body, CustomerView.class, () -> {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO customer(id, code, name, unified_credit_code, contact_name, contact_phone)
                    VALUES (:id,:code,:name,:credit,:contact,:phone)
                    """).param("id", id).param("code", body.code().trim()).param("name", body.name().trim())
                    .param("credit", body.unifiedCreditCode()).param("contact", body.contactName())
                    .param("phone", body.contactPhone()).update();
            CustomerView created = customer(id);
            audit.record("CUSTOMER_CREATED", "CUSTOMER", id, null, created, null, null);
            return created;
        });
    }

    @PutMapping("/customers/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    @Transactional
    public CustomerView updateCustomer(@PathVariable UUID id, @Valid @RequestBody CustomerUpdate body) {
        CustomerView before = customer(id);
        int changed = jdbc.sql("""
                UPDATE customer SET name=:name, unified_credit_code=:credit, contact_name=:contact,
                  contact_phone=:phone, active=:active, version=version+1, updated_at=now()
                WHERE id=:id AND version=:version
                """).param("name", body.name().trim()).param("credit", body.unifiedCreditCode())
                .param("contact", body.contactName()).param("phone", body.contactPhone()).param("active", body.active())
                .param("id", id).param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "客户信息已被更新", customer(id).version());
        CustomerView after = customer(id);
        audit.record("CUSTOMER_UPDATED", "CUSTOMER", id, before, after, null, null);
        return after;
    }

    @GetMapping("/projects")
    public List<ProjectView> projects() {
        return jdbc.sql("""
                SELECT p.id, p.number, p.name, p.customer_id, c.name customer_name, p.location,
                  p.responsible_person, p.status, p.version FROM project p LEFT JOIN customer c ON c.id=p.customer_id
                WHERE p.organization_id=:organization ORDER BY p.number
                """).param("organization", actor.require().organizationId()).query((rs, row) -> new ProjectView(
                        rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("name"),
                        rs.getObject("customer_id", UUID.class), rs.getString("customer_name"), rs.getString("location"),
                        rs.getString("responsible_person"), rs.getString("status"), rs.getLong("version"))).list();
    }

    @PostMapping("/projects")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ProjectView createProject(@RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody ProjectCreate body) {
        return idempotency.execute(key, "project-create", body, ProjectView.class, () -> {
            customer(body.customerId());
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO project(id, organization_id, customer_id, number, name, location, responsible_person, status)
                    VALUES (:id,:organization,:customer,:number,:name,:location,:responsible,'ACTIVE')
                    """).param("id", id).param("organization", actor.require().organizationId())
                    .param("customer", body.customerId()).param("number", body.number().trim()).param("name", body.name().trim())
                    .param("location", body.location()).param("responsible", body.responsiblePerson()).update();
            ProjectView created = project(id);
            audit.record("PROJECT_CREATED", "PROJECT", id, null, created, null, null);
            return created;
        });
    }

    @PutMapping("/projects/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    @Transactional
    public ProjectView updateProject(@PathVariable UUID id, @Valid @RequestBody ProjectUpdate body) {
        ProjectView before = project(id);
        customer(body.customerId());
        if (!List.of("ACTIVE", "SUSPENDED", "CLOSED").contains(body.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROJECT_STATUS_INVALID", "工程状态无效");
        }
        int changed = jdbc.sql("""
                UPDATE project SET name=:name, customer_id=:customer, location=:location,
                  responsible_person=:responsible, status=:status, version=version+1, updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("name", body.name().trim()).param("customer", body.customerId()).param("location", body.location())
                .param("responsible", body.responsiblePerson()).param("status", body.status())
                .param("id", id).param("organization", actor.require().organizationId())
                .param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "工程信息已被更新", project(id).version());
        ProjectView after = project(id);
        audit.record("PROJECT_UPDATED", "PROJECT", id, before, after, null, null);
        return after;
    }

    @GetMapping("/people")
    public List<PersonView> people() {
        return jdbc.sql("""
                SELECT p.id, p.employee_number, p.name, p.department_id, d.name department_name, p.title,
                  CASE WHEN p.phone IS NULL THEN NULL WHEN length(p.phone)>7
                    THEN left(p.phone,3)||'****'||right(p.phone,4) ELSE '***'||right(p.phone,2) END phone,
                  p.active, p.version, (SELECT count(*) FROM person_qualification q WHERE q.person_id=p.id) qualification_count
                FROM person p JOIN department d ON d.id=p.department_id
                WHERE d.organization_id=:organization ORDER BY p.employee_number
                """).param("organization", actor.require().organizationId()).query((rs, row) -> new PersonView(
                        rs.getObject("id", UUID.class), rs.getString("employee_number"), rs.getString("name"),
                        rs.getObject("department_id", UUID.class), rs.getString("department_name"), rs.getString("title"),
                        rs.getString("phone"), rs.getBoolean("active"), rs.getLong("version"),
                        rs.getLong("qualification_count"))).list();
    }

    @GetMapping("/parameters")
    public List<ParameterView> parameters() {
        return jdbc.sql(parameterSelect() + " ORDER BY code, version_number DESC").query(this::mapParameter).list();
    }

    @PostMapping("/parameters")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ParameterView createParameter(@RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody ParameterCreate body) {
        return idempotency.execute(key, "parameter-create", body, ParameterView.class,
                () -> createParameterInternal(body));
    }

    @Transactional
    protected ParameterView createParameterInternal(ParameterCreate body) {
        validateParameter(body.valueType(), body.formula());
        int versionNumber = jdbc.sql("SELECT COALESCE(max(version_number),0)+1 FROM test_parameter WHERE code=:code")
                .param("code", body.code().trim()).query(Integer.class).single();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO test_parameter(id, code, name, version_number, value_type, definition_json,
                  formula, unit, status, created_by) VALUES (:id,:code,:name,:versionNumber,:valueType,
                  CAST(:definition AS jsonb),:formula,:unit,'DRAFT',:actor)
                """).param("id", id).param("code", body.code().trim()).param("name", body.name().trim())
                .param("versionNumber", versionNumber).param("valueType", body.valueType())
                .param("definition", encode(body.definition())).param("formula", blankToNull(body.formula()))
                .param("unit", blankToNull(body.unit())).param("actor", actor.require().id()).update();
        ParameterView created = parameter(id);
        audit.record("TEST_PARAMETER_CREATED", "TEST_PARAMETER", id, null, created, null, null);
        return created;
    }

    @PutMapping("/parameters/{id}")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    @Transactional
    public ParameterView updateParameter(@PathVariable UUID id, @Valid @RequestBody ParameterUpdate body) {
        ParameterView before = parameter(id);
        if (!before.status().equals("DRAFT")) throw new ApiException(HttpStatus.CONFLICT,
                "PUBLISHED_PARAMETER_IMMUTABLE", "已发布检测参数只能创建新版本");
        validateParameter(body.valueType(), body.formula());
        int changed = jdbc.sql("""
                UPDATE test_parameter SET name=:name, value_type=:valueType, definition_json=CAST(:definition AS jsonb),
                  formula=:formula, unit=:unit, lock_version=lock_version+1, updated_at=now()
                WHERE id=:id AND status='DRAFT' AND lock_version=:version
                """).param("name", body.name().trim()).param("valueType", body.valueType())
                .param("definition", encode(body.definition())).param("formula", blankToNull(body.formula()))
                .param("unit", blankToNull(body.unit())).param("id", id).param("version", body.expectedVersion()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "检测参数已被更新", parameter(id).version());
        ParameterView after = parameter(id);
        audit.record("TEST_PARAMETER_UPDATED", "TEST_PARAMETER", id, before, after, null, null);
        return after;
    }

    @PostMapping("/parameters/{id}/transitions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public TransitionResult transitionParameter(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody TransitionCommand command) {
        return idempotency.execute(key, "parameter-transition:" + id, command, TransitionResult.class,
                () -> transitionParameterInternal(id, command));
    }

    @Transactional
    protected TransitionResult transitionParameterInternal(UUID id, TransitionCommand command) {
        ParameterView before = parameter(id);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict("VERSION_CONFLICT", "检测参数已被更新", before.version());
        String action = command.action().toUpperCase();
        String next = before.status().equals("DRAFT") && action.equals("PUBLISH") ? "PUBLISHED"
                : before.status().equals("PUBLISHED") && action.equals("RETIRE") ? "RETIRED" : null;
        if (next == null) throw new ApiException(HttpStatus.CONFLICT, "INVALID_PARAMETER_TRANSITION", "检测参数当前状态不允许该动作");
        if (next.equals("PUBLISHED")) {
            jdbc.sql("UPDATE test_parameter SET status='RETIRED', lock_version=lock_version+1, updated_at=now() WHERE code=:code AND status='PUBLISHED' AND id<>:id")
                    .param("code", before.code()).param("id", id).update();
        }
        int changed = jdbc.sql("""
                UPDATE test_parameter SET status=:status, published_at=CASE WHEN :status='PUBLISHED' THEN now() ELSE published_at END,
                  lock_version=lock_version+1, updated_at=now() WHERE id=:id AND lock_version=:version
                """).param("status", next).param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "检测参数已被更新", parameter(id).version());
        ParameterView after = parameter(id);
        audit.record("TEST_PARAMETER_" + action, "TEST_PARAMETER", id, before, after, command.reasonCode(), command.comment());
        return new TransitionResult(after.status(), after.version(), after.status().equals("PUBLISHED") ? List.of("RETIRE") : List.of());
    }

    @PostMapping("/parameters/{id}/revisions")
    @PreAuthorize("hasAuthority('MODULE_WRITE')")
    public ParameterView reviseParameter(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key) {
        ParameterView source = parameter(id);
        if (!List.of("PUBLISHED", "RETIRED").contains(source.status())) throw new ApiException(HttpStatus.CONFLICT,
                "PARAMETER_NOT_REVISIONABLE", "仅已发布或停用的参数可以创建新版本");
        ParameterCreate create = new ParameterCreate(source.code(), source.name(), source.valueType(),
                source.definition(), source.formula(), source.unit());
        return idempotency.execute(key, "parameter-revision:" + id, Map.of("id", id), ParameterView.class,
                () -> createParameterInternal(create));
    }

    private OrganizationView organization(UUID id) {
        if (!actor.require().organizationId().equals(id)) throw ApiException.notFound("ORGANIZATION_NOT_FOUND", "单位不存在");
        return organizations().stream().findFirst().orElseThrow(() -> ApiException.notFound("ORGANIZATION_NOT_FOUND", "单位不存在"));
    }

    private CustomerView customer(UUID id) {
        return customers().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "客户不存在"));
    }

    private ProjectView project(UUID id) {
        return projects().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("PROJECT_NOT_FOUND", "工程不存在"));
    }

    private PersonView person(UUID id) {
        return people().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("PERSON_NOT_FOUND", "人员不存在"));
    }

    private ParameterView parameter(UUID id) {
        return jdbc.sql(parameterSelect() + " WHERE id=:id").param("id", id).query(this::mapParameter).optional()
                .orElseThrow(() -> ApiException.notFound("PARAMETER_NOT_FOUND", "检测参数不存在"));
    }

    private String parameterSelect() {
        return """
                SELECT id, code, name, version_number, value_type, definition_json::text, formula, unit, status,
                  lock_version, published_at, updated_at FROM test_parameter
                """;
    }

    private ParameterView mapParameter(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        try {
            Map<String, Object> definition = json.readValue(rs.getString("definition_json"), new TypeReference<>() {});
            return new ParameterView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                    rs.getInt("version_number"), rs.getString("value_type"), definition, rs.getString("formula"),
                    rs.getString("unit"), rs.getString("status"), rs.getLong("lock_version"),
                    rs.getObject("published_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
        } catch (JacksonException exception) { throw new java.sql.SQLException("Invalid test parameter definition", exception); }
    }

    private void validateParameter(String valueType, String formula) {
        if (!VALUE_TYPES.contains(valueType)) throw new ApiException(HttpStatus.BAD_REQUEST, "PARAMETER_VALUE_TYPE_INVALID", "检测参数值类型无效");
        if (formula != null && !formula.isBlank()) formulas.validateSafety(formula);
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("受控定义无法序列化", exception); }
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
