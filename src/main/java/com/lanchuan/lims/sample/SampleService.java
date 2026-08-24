package com.lanchuan.lims.sample;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.TransitionCommand;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.configuration.NumberingService;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.DataScopeService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class SampleService {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final NumberingService numbering;
    private final AuditService audit;
    private final DataScopeService access;

    public SampleService(JdbcClient jdbc, CurrentActor actor, NumberingService numbering, AuditService audit,
                         DataScopeService access) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.numbering = numbering;
        this.audit = audit;
        this.access = access;
    }

    public record Create(UUID commissionId, UUID sampleGroupId, String name, String specification,
                         String quantity, String unit, LocalDate samplingDate, LocalDate moldingDate,
                         LocalDate plannedTestDate, Integer ageDays, String curingMethod,
                         String strengthGrade, String structurePart, String chipCode,
                         String representedQuantity, String representedUnit) {}
    public record Split(String name, String quantity, long expectedVersion, String comment) {}
    public record Consume(String quantity, long expectedVersion, String comment) {}
    public record View(UUID id, UUID commissionId, UUID parentId, UUID sampleGroupId, String number, String name, String specification,
                       String receivedQuantity, String availableQuantity, String unit, LocalDate samplingDate,
                       LocalDate moldingDate, LocalDate plannedTestDate, Integer ageDays, String curingMethod,
                       String strengthGrade, String structurePart, String chipCode,
                       String representedQuantity, String representedUnit, SampleState state,
                       long version, OffsetDateTime updatedAt) {}

    @Transactional
    public View create(Create body) {
        access.requireCommission(body.commissionId());
        BigDecimal quantity = positive(body.quantity());
        BigDecimal representedQuantity = optionalPositive(body.representedQuantity());
        validateDates(body.moldingDate(), body.plannedTestDate(), body.ageDays());
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO sample(id, commission_id, sample_group_id, number, name, specification, received_quantity,
                  available_quantity, unit, sampling_date, molding_date, planned_test_date, age_days,
                  curing_method, strength_grade, structure_part, chip_code, represented_quantity,
                  represented_unit, state)
                SELECT :id, c.id, g.id, :number, :name, :specification, :quantity, 0, :unit,
                  :samplingDate, :moldingDate, :plannedTestDate, :ageDays, :curingMethod,
                  :strengthGrade, COALESCE(:structurePart,NULLIF(c.project_part,'')), :chipCode,
                  :representedQuantity, :representedUnit, 'EXPECTED'
                FROM commission c JOIN commission_sample_group g ON g.commission_id=c.id
                WHERE c.id=:commission AND g.id=:sampleGroup AND c.organization_id=:organization
                  AND c.state IN ('ACCEPTED','SAMPLING')
                """).param("id", id).param("commission", body.commissionId()).param("sampleGroup", body.sampleGroupId())
                .param("organization", actor.require().organizationId()).param("number", numbering.next("SAMPLE", "YP"))
                .param("name", body.name()).param("specification", body.specification())
                .param("quantity", quantity).param("unit", body.unit())
                .param("samplingDate", body.samplingDate()).param("moldingDate", body.moldingDate())
                .param("plannedTestDate", body.plannedTestDate()).param("ageDays", body.ageDays())
                .param("curingMethod", nullable(body.curingMethod())).param("strengthGrade", nullable(body.strengthGrade()))
                .param("structurePart", nullable(body.structurePart())).param("chipCode", nullable(body.chipCode()))
                .param("representedQuantity", representedQuantity)
                .param("representedUnit", representedQuantity == null ? null : nullable(body.representedUnit())).update();
        if (inserted == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "SAMPLE_COMMISSION_STATE_INVALID",
                    "仅已受理或取样中的委托可以按冻结样品组登记样品");
        }
        View created = require(id);
        audit.record("SAMPLE_CREATED", "SAMPLE", id, null, created, null, null);
        return created;
    }

    public List<View> list(UUID commissionId) {
        access.requireCommission(commissionId);
        return jdbc.sql(baseSelect() + " WHERE s.commission_id=:commission AND c.organization_id=:organization ORDER BY s.created_at")
                .param("commission", commissionId).param("organization", actor.require().organizationId())
                .query(this::map).list();
    }

    public View require(UUID id) {
        View view = jdbc.sql(baseSelect() + " WHERE s.id=:id AND c.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId()).query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("SAMPLE_NOT_FOUND", "样品不存在"));
        access.requireCommission(view.commissionId());
        return view;
    }

    @Transactional
    public TransitionResult transition(UUID id, TransitionCommand command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "样品已被其他用户更新", before.version());
        }
        String action = command.action().trim().toUpperCase();
        if (action.equals("RECEIVE") && !commissionState(id).equals("SAMPLING")) {
            throw new ApiException(HttpStatus.CONFLICT, "SAMPLE_RECEIPT_NOT_STARTED", "委托进入取样阶段后才能接收样品");
        }
        SampleState next = before.state().transition(action);
        BigDecimal available = new BigDecimal(before.availableQuantity());
        BigDecimal received = new BigDecimal(before.receivedQuantity());
        BigDecimal nextAvailable = action.equals("RECEIVE") ? received
                : List.of("RETURN", "DISPOSE").contains(action) ? BigDecimal.ZERO : available;
        int updated = jdbc.sql("""
                UPDATE sample SET state=:state, available_quantity=:available,
                  received_at=CASE WHEN :state='RECEIVED' THEN now() ELSE received_at END,
                  version=version+1, updated_at=now() WHERE id=:id AND version=:version
                """).param("state", next.name()).param("available", nextAvailable)
                .param("id", id).param("version", before.version()).update();
        if (updated == 0) throw ApiException.conflict("VERSION_CONFLICT", "样品已被其他用户更新", require(id).version());
        if (action.equals("RECEIVE") || action.equals("RETURN") || action.equals("DISPOSE")) {
            BigDecimal quantity = action.equals("RECEIVE") ? received : available;
            if (quantity.signum() > 0) movement(id, action, quantity, command.comment());
        }
        View after = require(id);
        audit.record("SAMPLE_" + action, "SAMPLE", id, before, after, command.reasonCode(), command.comment());
        return new TransitionResult(after.state().name(), after.version(), after.state().allowedActions());
    }

    @Transactional
    public View split(UUID parentId, Split command) {
        View parent = require(parentId);
        if (parent.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "样品已被其他用户更新", parent.version());
        }
        BigDecimal quantity = positive(command.quantity());
        BigDecimal available = new BigDecimal(parent.availableQuantity());
        if (available.compareTo(quantity) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SAMPLE_QUANTITY_INSUFFICIENT", "可用样品数量不足");
        }
        jdbc.sql("""
                UPDATE sample SET available_quantity=available_quantity-:quantity, version=version+1, updated_at=now()
                WHERE id=:id AND version=:version
                """).param("quantity", quantity).param("id", parentId).param("version", parent.version()).update();
        UUID childId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO sample(id, commission_id, parent_id, sample_group_id, number, name, specification, received_quantity,
                  available_quantity, unit, received_at, sampling_date, molding_date, planned_test_date,
                  age_days, curing_method, strength_grade, structure_part, chip_code, represented_quantity,
                  represented_unit, state)
                VALUES (:id, :commission, :parent, :sampleGroup, :number, :name, :specification, :quantity,
                        :quantity, :unit, now(), :samplingDate, :moldingDate, :plannedTestDate,
                        :ageDays, :curingMethod, :strengthGrade, :structurePart, :chipCode,
                        :representedQuantity, :representedUnit, 'RECEIVED')
                """).param("id", childId).param("commission", parent.commissionId()).param("parent", parentId)
                .param("sampleGroup", parent.sampleGroupId())
                .param("number", numbering.next("SAMPLE", "YP")).param("name", command.name())
                .param("specification", parent.specification()).param("quantity", quantity).param("unit", parent.unit())
                .param("samplingDate", parent.samplingDate()).param("moldingDate", parent.moldingDate())
                .param("plannedTestDate", parent.plannedTestDate()).param("ageDays", parent.ageDays())
                .param("curingMethod", parent.curingMethod()).param("strengthGrade", parent.strengthGrade())
                .param("structurePart", parent.structurePart()).param("chipCode", null)
                .param("representedQuantity", parent.representedQuantity() == null ? null : new BigDecimal(parent.representedQuantity()))
                .param("representedUnit", parent.representedUnit()).update();
        movement(parentId, "SPLIT", quantity, command.comment());
        View child = require(childId);
        audit.record("SAMPLE_SPLIT", "SAMPLE", parentId, parent, child, null, command.comment());
        return child;
    }

    @Transactional
    public View consume(UUID id, Consume command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "样品已被其他用户更新", before.version());
        }
        if (before.state() != SampleState.IN_TEST) {
            throw new ApiException(HttpStatus.CONFLICT, "SAMPLE_NOT_IN_TEST", "仅检测中的样品可以消耗");
        }
        BigDecimal quantity = positive(command.quantity());
        int updated = jdbc.sql("""
                UPDATE sample SET available_quantity=available_quantity-:quantity, version=version+1, updated_at=now()
                WHERE id=:id AND version=:version AND available_quantity>=:quantity
                """).param("quantity", quantity).param("id", id).param("version", before.version()).update();
        if (updated == 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SAMPLE_QUANTITY_INSUFFICIENT", "可用样品数量不足或版本已变化");
        movement(id, "CONSUME", quantity, command.comment());
        View after = require(id);
        audit.record("SAMPLE_CONSUMED", "SAMPLE", id, before, after, null, command.comment());
        return after;
    }

    private void movement(UUID sampleId, String type, BigDecimal quantity, String reason) {
        jdbc.sql("""
                INSERT INTO sample_movement(id, sample_id, movement_type, quantity, reason, actor_id)
                VALUES (:id, :sample, :type, :quantity, :reason, :actor)
                """).param("id", UUID.randomUUID()).param("sample", sampleId).param("type", type)
                .param("quantity", quantity).param("reason", reason).param("actor", actor.require().id()).update();
    }

    private String commissionState(UUID sampleId) {
        return jdbc.sql("SELECT c.state FROM commission c JOIN sample s ON s.commission_id=c.id WHERE s.id=:id")
                .param("id", sampleId).query(String.class).single();
    }

    private BigDecimal positive(String value) {
        try {
            BigDecimal number = new BigDecimal(value);
            if (number.signum() <= 0 || number.scale() > 6) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DECIMAL", "数量必须是最多6位小数的正数");
        }
    }

    private BigDecimal optionalPositive(String value) {
        if (value == null || value.isBlank()) return null;
        return positive(value);
    }

    private void validateDates(LocalDate moldingDate, LocalDate plannedTestDate, Integer ageDays) {
        if (ageDays != null && (ageDays < 1 || ageDays > 3650)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAMPLE_AGE_INVALID", "龄期必须在 1 至 3650 天之间");
        }
        if (moldingDate != null && plannedTestDate != null && plannedTestDate.isBefore(moldingDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAMPLE_TEST_DATE_INVALID", "计划检测日期不得早于成型日期");
        }
        if (moldingDate != null && plannedTestDate != null && ageDays != null
                && !plannedTestDate.equals(moldingDate.plusDays(ageDays))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SAMPLE_AGE_DATE_MISMATCH",
                    "计划检测日期必须等于成型日期加龄期");
        }
    }

    private String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private String baseSelect() {
        return """
                SELECT s.id, s.commission_id, s.parent_id, s.sample_group_id, s.number, s.name, s.specification,
                  s.received_quantity, s.available_quantity, s.unit, s.sampling_date, s.molding_date,
                  s.planned_test_date, s.age_days, s.curing_method, s.strength_grade, s.structure_part,
                  s.chip_code, s.represented_quantity, s.represented_unit, s.state, s.version, s.updated_at
                FROM sample s JOIN commission c ON c.id=s.commission_id
                """;
    }

    private View map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new View(rs.getObject("id", UUID.class), rs.getObject("commission_id", UUID.class),
                rs.getObject("parent_id", UUID.class), rs.getObject("sample_group_id", UUID.class),
                rs.getString("number"), rs.getString("name"),
                rs.getString("specification"), rs.getBigDecimal("received_quantity").toPlainString(),
                rs.getBigDecimal("available_quantity").toPlainString(), rs.getString("unit"),
                rs.getObject("sampling_date", LocalDate.class), rs.getObject("molding_date", LocalDate.class),
                rs.getObject("planned_test_date", LocalDate.class), rs.getObject("age_days", Integer.class),
                rs.getString("curing_method"), rs.getString("strength_grade"), rs.getString("structure_part"),
                rs.getString("chip_code"), rs.getBigDecimal("represented_quantity") == null ? null
                : rs.getBigDecimal("represented_quantity").toPlainString(), rs.getString("represented_unit"),
                SampleState.valueOf(rs.getString("state")), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
