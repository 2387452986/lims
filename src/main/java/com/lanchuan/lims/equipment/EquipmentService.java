package com.lanchuan.lims.equipment;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.storedfile.StoredFileService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class EquipmentService {
    static final Set<String> VERIFICATION_TYPES = Set.of(
            "VERIFICATION", "CALIBRATION", "FUNCTION_CHECK", "SELF_CALIBRATION", "COMPARISON", "TESTING", "OTHER");
    static final Set<String> EQUIPMENT_TYPES = Set.of(
            "FIELD", "GEOTECHNICAL", "CONCRETE", "METAL_STRUCTURE", "MECHANICAL_ELECTRICAL", "SURVEY", "OTHER");
    static final Set<String> PILE_TYPES = Set.of(
            "STATIC_LOAD_HOST", "PRESSURE_SENSOR", "DISPLACEMENT_SENSOR", "DYNAMIC_HOST", "ACCELERATION_SENSOR",
            "FORCE_SENSOR", "JACK", "SPEED_SENSOR", "ULTRASONIC_HOST", "CROSS_HOLE_TRANSDUCER",
            "PLANE_TRANSDUCER", "PERCENTAGE_METER", "SELF_BALANCING_LOAD_CELL", "WIRELESS_TRANSMISSION");

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final StoredFileService files;

    public EquipmentService(JdbcClient jdbc, CurrentActor actor, AuditService audit, StoredFileService files) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.files = files;
    }

    public record Write(String code, String assetNumber, String name, String model, UUID categoryId,
                        LocalDate purchasedOn, BigDecimal purchasePrice, String manufacturer, String serialNumber,
                        Integer verificationCycleMonths, String verificationType, String technicalSpecification,
                        String nominalRange, String measurementRange, String resolution, String accuracy,
                        UUID custodianId, boolean primaryEquipment, boolean enabled, String performanceStatus,
                        String location, String equipmentType, String pileEquipmentType, UUID attachmentFileId,
                        UUID photoFileId, String remark, List<UUID> detectionProjectIds, long expectedVersion) {}

    public record Summary(UUID id, String code, String assetNumber, String name, String model, UUID categoryId,
                          String categoryName, LocalDate purchasedOn, BigDecimal purchasePrice,
                          String manufacturer, String serialNumber, Integer verificationCycleMonths,
                          String verificationType, String technicalSpecification, String nominalRange,
                          String measurementRange, String resolution, String accuracy, String location,
                          UUID custodianId, String custodianName, String custodianEmployeeNumber,
                          boolean primaryEquipment, boolean enabled,
                          String performanceStatus, String equipmentType, String pileEquipmentType, String remark,
                          EquipmentState state, String inventoryStatus,
                          String approvalState, LocalDate certificateValidUntil, String verificationStatus,
                          LocalDate latestVerificationDate, String latestVerificationOrganization,
                          long version, OffsetDateTime updatedAt,
                          List<String> allowedActions) {}

    public record View(UUID id, String code, String assetNumber, String name, String model, UUID categoryId,
                       String categoryName, LocalDate purchasedOn, BigDecimal purchasePrice, String manufacturer,
                       String serialNumber, Integer verificationCycleMonths, String verificationType,
                       String technicalSpecification, String nominalRange, String measurementRange,
                       String resolution, String accuracy, UUID custodianId, String custodianName,
                       boolean primaryEquipment, boolean enabled, String performanceStatus, String location,
                       String equipmentType, String pileEquipmentType, UUID attachmentFileId, String attachmentFileName,
                       UUID photoFileId, String photoFileName, String remark, EquipmentState state,
                       String inventoryStatus, String approvalState, UUID approvedBy, OffsetDateTime approvedAt,
                       LocalDate certificateValidUntil, String verificationStatus, long version,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt, List<UUID> detectionProjectIds,
                       List<String> allowedActions) {}

    public record CategoryWrite(String code, String name, UUID parentId, int sortOrder, long expectedVersion) {}
    public record CategoryView(UUID id, UUID parentId, String code, String name, int sortOrder, boolean active,
                               long version, long equipmentCount, OffsetDateTime updatedAt) {}

    public record CertificateWrite(String certificateType, String inspectionType, String certificateNumber,
                                   LocalDate issuedOn, LocalDate validUntil, LocalDate confirmedOn,
                                   String result, String issuingOrganization, UUID fileId, String remark,
                                   long expectedVersion) {}
    public record CertificateView(UUID id, UUID equipmentId, String equipmentCode, String equipmentName,
                                  String model, String manufacturer, String certificateType, String inspectionType,
                                  String certificateNumber, LocalDate issuedOn, LocalDate validUntil,
                                  LocalDate confirmedOn, String result, String issuingOrganization, UUID fileId,
                                  String fileName, String remark, String state, String verificationStatus,
                                  UUID operatorId, String operatorName,
                                  long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    public record Transition(String action, long expectedVersion, String reasonCode, String comment,
                             UUID taskId, Map<String, Object> details) {}
    public record BatchTarget(UUID id, long expectedVersion) {}
    public record BatchItem(UUID id, String state, long version, List<String> allowedActions) {}
    public record BatchResult(List<BatchItem> items) {}

    public CursorPage<Summary> list(String query, UUID categoryId, LocalDate verificationFrom,
                                    LocalDate verificationTo, String equipmentType, String enabled,
                                    String performanceStatus, String approvalState, String verificationStatus,
                                    String scrapped, String inventoryStatus, String code, String assetNumber,
                                    String name, String manufacturer, String verificationOrganization,
                                    String technicalSpecification, String location, String serialNumber,
                                    String cursorValue, int limit) {
        PageCursor cursor = PageCursor.decode(cursorValue);
        String cursorClause = cursor == null ? "" : " AND (e.updated_at,e.id)<(:cursorTime,:cursorId)";
        String dateFrom = verificationFrom == null ? "" : " AND latest.valid_until>=:verificationFrom";
        String dateTo = verificationTo == null ? "" : " AND latest.valid_until<=:verificationTo";
        String filters = """
                WHERE e.organization_id=:organization
                  AND (:query='' OR e.code ILIKE :pattern ESCAPE '\\' OR e.name ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(e.asset_number,'') ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(e.model,'') ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(e.serial_number,'') ILIKE :pattern ESCAPE '\\')
                  AND (CAST(:categoryId AS uuid) IS NULL OR e.category_id=CAST(:categoryId AS uuid))
                  AND (:equipmentType='' OR e.equipment_type=:equipmentType)
                  AND (:enabled='' OR e.enabled=CAST(:enabled AS boolean))
                  AND (:performance='' OR e.performance_status=:performance)
                  AND (:approval='' OR e.approval_state=:approval)
                  AND (:verification='' OR CASE WHEN latest.id IS NULL THEN 'NONE'
                    WHEN latest.result='FAIL' THEN 'FAILED' WHEN latest.valid_until<current_date THEN 'EXPIRED'
                    ELSE 'VALID' END=:verification)
                  AND (:scrapped='' OR (e.state='SCRAPPED')=CAST(:scrapped AS boolean))
                  AND (:inventory='' OR e.inventory_status=:inventory)
                  AND (:code='' OR e.code ILIKE :codePattern ESCAPE '\\')
                  AND (:asset='' OR COALESCE(e.asset_number,'') ILIKE :assetPattern ESCAPE '\\')
                  AND (:name='' OR e.name ILIKE :namePattern ESCAPE '\\')
                  AND (:manufacturer='' OR COALESCE(e.manufacturer,'') ILIKE :manufacturerPattern ESCAPE '\\')
                  AND (:verificationOrg='' OR COALESCE(latest.issuing_organization,'') ILIKE :verificationOrgPattern ESCAPE '\\')
                  AND (:technical='' OR COALESCE(e.technical_specification,'') ILIKE :technicalPattern ESCAPE '\\')
                  AND (:location='' OR COALESCE(e.location,'') ILIKE :locationPattern ESCAPE '\\')
                  AND (:serial='' OR COALESCE(e.serial_number,'') ILIKE :serialPattern ESCAPE '\\')
                """ + dateFrom + dateTo;
        String from = """
                FROM equipment e LEFT JOIN equipment_category category ON category.id=e.category_id
                LEFT JOIN person custodian ON custodian.id=e.custodian_id
                LEFT JOIN LATERAL (SELECT certificate.* FROM equipment_certificate certificate
                  WHERE certificate.equipment_id=e.id AND certificate.state<>'VOID'
                  ORDER BY certificate.issued_on DESC,certificate.created_at DESC LIMIT 1) latest ON true
                """;
        String select = """
                SELECT e.id,e.code,e.asset_number,e.name,e.model,e.category_id,category.name category_name,
                  e.purchased_on,e.purchase_price,e.manufacturer,e.serial_number,e.verification_cycle_months,
                  e.verification_type,e.technical_specification,e.nominal_range,e.measurement_range,e.resolution,e.accuracy,
                  e.location,e.custodian_id,custodian.name custodian_name,
                  custodian.employee_number custodian_employee_number,e.primary_equipment,e.enabled,
                  e.performance_status,e.equipment_type,e.pile_equipment_type,e.remark,e.state,e.inventory_status,
                  e.approval_state,latest.valid_until certificate_valid_until,
                  CASE WHEN latest.id IS NULL THEN 'NONE' WHEN latest.result='FAIL' THEN 'FAILED'
                    WHEN latest.valid_until<current_date THEN 'EXPIRED' ELSE 'VALID' END verification_status,
                  latest.issued_on latest_verification_date,
                  latest.issuing_organization latest_verification_organization,e.version,e.updated_at
                """ + from + filters + cursorClause + " ORDER BY e.updated_at DESC,e.id DESC LIMIT :fetch";
        Map<String, Object> params = listParams(query, categoryId, equipmentType, enabled, performanceStatus,
                approvalState, verificationStatus, scrapped, inventoryStatus, code, assetNumber, name, manufacturer,
                verificationOrganization, technicalSpecification, location, serialNumber);
        params.put("organization", actor.require().organizationId());
        params.put("fetch", limit + 1);
        if (verificationFrom != null) params.put("verificationFrom", verificationFrom);
        if (verificationTo != null) params.put("verificationTo", verificationTo);
        if (cursor != null) { params.put("cursorTime", cursor.updatedAt()); params.put("cursorId", cursor.id()); }
        List<Summary> fetched = jdbc.sql(select).params(params).query(this::mapSummary).list();
        boolean hasMore = fetched.size() > limit;
        List<Summary> items = hasMore ? fetched.subList(0, limit) : fetched;
        params.remove("fetch"); params.remove("cursorTime"); params.remove("cursorId");
        long total = jdbc.sql("SELECT count(*) " + from + filters).params(params).query(Long.class).single();
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(List.copyOf(items), next, hasMore, total);
    }

    public View require(UUID id) {
        View view = jdbc.sql("""
                SELECT e.id,e.code,e.asset_number,e.name,e.model,e.category_id,category.name category_name,
                  e.purchased_on,e.purchase_price,e.manufacturer,e.serial_number,e.verification_cycle_months,
                  e.verification_type,e.technical_specification,e.nominal_range,e.measurement_range,e.resolution,
                  e.accuracy,e.custodian_id,custodian.name custodian_name,e.primary_equipment,e.enabled,
                  e.performance_status,e.location,e.equipment_type,e.pile_equipment_type,e.attachment_file_id,
                  attachment.original_name attachment_file_name,e.photo_file_id,photo.original_name photo_file_name,
                  e.remark,e.state,e.inventory_status,e.approval_state,e.approved_by,e.approved_at,
                  latest.valid_until certificate_valid_until,
                  CASE WHEN latest.id IS NULL THEN 'NONE' WHEN latest.result='FAIL' THEN 'FAILED'
                    WHEN latest.valid_until<current_date THEN 'EXPIRED' ELSE 'VALID' END verification_status,
                  e.version,e.created_at,e.updated_at
                FROM equipment e LEFT JOIN equipment_category category ON category.id=e.category_id
                LEFT JOIN person custodian ON custodian.id=e.custodian_id
                LEFT JOIN stored_file attachment ON attachment.id=e.attachment_file_id
                LEFT JOIN stored_file photo ON photo.id=e.photo_file_id
                LEFT JOIN LATERAL (SELECT certificate.* FROM equipment_certificate certificate
                  WHERE certificate.equipment_id=e.id AND certificate.state<>'VOID'
                  ORDER BY certificate.issued_on DESC,certificate.created_at DESC LIMIT 1) latest ON true
                WHERE e.id=:id AND e.organization_id=:organization
                """).param("id", id).param("organization", actor.require().organizationId())
                .query(this::mapView).optional()
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在"));
        List<UUID> projects = jdbc.sql("""
                SELECT project_node_id FROM equipment_detection_project WHERE equipment_id=:id ORDER BY project_node_id
                """).param("id", id).query(UUID.class).list();
        return withProjects(view, projects);
    }

    @Transactional
    public View create(Write body) {
        Validated value = validate(body);
        UUID id = UUID.randomUUID();
        Map<String, Object> params = writeParams(value);
        params.put("id", id);
        params.put("organization", actor.require().organizationId());
        try {
            jdbc.sql("""
                    INSERT INTO equipment(id,organization_id,code,asset_number,name,model,category_id,purchased_on,
                      purchase_price,manufacturer,serial_number,verification_cycle_months,verification_type,
                      technical_specification,nominal_range,measurement_range,resolution,accuracy,custodian_id,
                      primary_equipment,enabled,performance_status,location,equipment_type,pile_equipment_type,
                      attachment_file_id,photo_file_id,remark,state,approval_state,inventory_status)
                    VALUES (:id,:organization,:code,:asset,:name,:model,:category,:purchased,:price,:manufacturer,
                      :serial,:cycle,:verificationType,:technical,:nominalRange,:measurementRange,:resolution,
                      :accuracy,:custodian,:primary,:enabled,:performance,:location,:equipmentType,:pileType,
                      :attachment,:photo,:remark,'AVAILABLE','DRAFT','IN')
                    """).params(params).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "EQUIPMENT_CODE_EXISTS", "设备编号或固定资产编号已存在");
        }
        replaceProjects(id, value.projectIds());
        View created = require(id);
        audit.record("EQUIPMENT_CREATED", "EQUIPMENT", id, null, created, null, null);
        return created;
    }

    @Transactional
    public View update(UUID id, Write body) {
        View before = require(id);
        if (before.approvalState().equals("SUBMITTED") || before.state() == EquipmentState.SCRAPPED) {
            throw new ApiException(HttpStatus.CONFLICT, "EQUIPMENT_IMMUTABLE", "审批中或已报废设备不能修改");
        }
        if (before.state() != EquipmentState.AVAILABLE) {
            throw ApiException.blocked("EQUIPMENT_OPERATION_ACTIVE", "设备正在业务流转，不能修改主档", before.version(),
                    List.of("请先完成使用、领用归还或送检返场流程"));
        }
        if (before.version() != body.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "设备已被其他用户更新", before.version());
        }
        Validated value = validate(body);
        Map<String, Object> params = writeParams(value);
        params.put("id", id); params.put("version", before.version());
        int changed;
        try {
            changed = jdbc.sql("""
                    UPDATE equipment SET code=:code,asset_number=:asset,name=:name,model=:model,category_id=:category,
                      purchased_on=:purchased,purchase_price=:price,manufacturer=:manufacturer,serial_number=:serial,
                      verification_cycle_months=:cycle,verification_type=:verificationType,
                      technical_specification=:technical,nominal_range=:nominalRange,measurement_range=:measurementRange,
                      resolution=:resolution,accuracy=:accuracy,custodian_id=:custodian,primary_equipment=:primary,
                      enabled=:enabled,performance_status=:performance,location=:location,equipment_type=:equipmentType,
                      pile_equipment_type=:pileType,attachment_file_id=:attachment,photo_file_id=:photo,remark=:remark,
                      approval_state='DRAFT',approved_by=NULL,approved_at=NULL,
                      version=version+1,updated_at=now() WHERE id=:id AND version=:version
                    """).params(params).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "EQUIPMENT_CODE_EXISTS", "设备编号或固定资产编号已存在");
        }
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "设备已被其他用户更新", require(id).version());
        replaceProjects(id, value.projectIds());
        View after = require(id);
        audit.record("EQUIPMENT_UPDATED", "EQUIPMENT", id, before, after, null, null);
        return after;
    }

    @Transactional
    public void delete(UUID id, long expectedVersion, String reason) {
        View before = require(id);
        if (before.version() != expectedVersion) throw ApiException.conflict("VERSION_CONFLICT", "设备已被其他用户更新", before.version());
        if (!before.approvalState().equals("DRAFT")) {
            throw new ApiException(HttpStatus.CONFLICT, "EQUIPMENT_DELETE_FORBIDDEN", "仅未提交草稿可删除；其他设备请停用或报废");
        }
        long references = jdbc.sql("""
                SELECT (SELECT count(*) FROM equipment_certificate WHERE equipment_id=:id)
                  +(SELECT count(*) FROM equipment_event WHERE equipment_id=:id)
                  +(SELECT count(*) FROM test_record WHERE equipment_id=:id)
                """).param("id", id).query(Long.class).single();
        if (references > 0) throw new ApiException(HttpStatus.CONFLICT, "EQUIPMENT_DELETE_REFERENCED", "设备已产生业务记录，不能删除");
        audit.record("EQUIPMENT_DELETED", "EQUIPMENT", id, before, null, "DRAFT_DELETE", required(reason, 1000, "删除原因"));
        jdbc.sql("DELETE FROM equipment WHERE id=:id AND version=:version").param("id", id).param("version", expectedVersion).update();
    }

    @Transactional
    public TransitionResult transition(UUID id, Transition command) {
        View before = require(id);
        if (before.version() != command.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT", "设备已被其他用户更新", before.version());
        }
        String action = required(command.action(), 80, "动作").toUpperCase(Locale.ROOT);
        String approval = before.approvalState();
        EquipmentState state = before.state();
        boolean enabled = before.enabled();
        UUID approvedBy = before.approvedBy();
        OffsetDateTime approvedAt = before.approvedAt();
        switch (action) {
            case "SUBMIT" -> {
                if (!Set.of("DRAFT", "REJECTED").contains(approval)) invalidTransition();
                approval = "SUBMITTED";
            }
            case "WITHDRAW" -> { if (!approval.equals("SUBMITTED")) invalidTransition(); approval = "DRAFT"; }
            case "APPROVE" -> {
                if (!approval.equals("SUBMITTED")) invalidTransition();
                approval = "APPROVED"; approvedBy = actor.require().id(); approvedAt = OffsetDateTime.now();
            }
            case "REJECT" -> {
                if (!approval.equals("SUBMITTED")) invalidTransition();
                required(command.comment(), 1000, "驳回原因"); approval = "REJECTED";
            }
            case "ENABLE" -> { requireApproved(before); enabled = true; }
            case "DISABLE" -> {
                requireApproved(before); required(command.comment(), 1000, "停用原因");
                if (state != EquipmentState.AVAILABLE && state != EquipmentState.OUT_OF_SERVICE) invalidTransition();
                enabled = false;
            }
            case "SCRAP" -> {
                requireApproved(before); required(command.comment(), 1000, "报废原因");
                if (state != EquipmentState.AVAILABLE && state != EquipmentState.OUT_OF_SERVICE) invalidTransition();
                state = EquipmentState.SCRAPPED; enabled = false;
            }
            default -> {
                throw ApiException.blocked("EQUIPMENT_TYPED_WORKFLOW_REQUIRED", "该动作必须通过对应设备业务流程办理",
                        before.version(), List.of("使用记录、领用归还、设备送检和出入库记录由各自专属流程写入"));
            }
        }
        int changed = jdbc.sql("""
                UPDATE equipment SET approval_state=:approval,state=:state,enabled=:enabled,approved_by=:approvedBy,
                  approved_at=:approvedAt,version=version+1,updated_at=now() WHERE id=:id AND version=:version
                """).param("approval", approval).param("state", state.name()).param("enabled", enabled)
                .param("approvedBy", approvedBy).param("approvedAt", approvedAt)
                .param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "设备已被其他用户更新", require(id).version());
        View after = require(id);
        audit.record("EQUIPMENT_" + action, "EQUIPMENT", id, before, after, command.reasonCode(), command.comment());
        return new TransitionResult(after.approvalState() + "/" + after.state().name(), after.version(), after.allowedActions());
    }

    @Transactional
    public BatchResult transitionBatch(List<BatchTarget> targets, String action, String reasonCode, String comment) {
        List<BatchTarget> values = targets == null ? List.of()
                : targets.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (values.isEmpty() || values.size() > 500) throw invalid("EQUIPMENT_SELECTION_INVALID", "请选择1至500台设备");
        if (values.stream().map(BatchTarget::id).distinct().count() != values.size()) {
            throw invalid("EQUIPMENT_SELECTION_DUPLICATED", "批量设备列表包含重复记录");
        }
        String normalizedAction = requiredEnum(action, Set.of("SUBMIT", "WITHDRAW"), "批量动作");
        List<BatchItem> items = new ArrayList<>();
        for (BatchTarget target : values) {
            if (target.id() == null) throw invalid("EQUIPMENT_SELECTION_INVALID", "设备标识不能为空");
            TransitionResult result = transition(target.id(), new Transition(normalizedAction, target.expectedVersion(),
                    reasonCode, comment, null, Map.of()));
            items.add(new BatchItem(target.id(), result.state(), result.version(), result.allowedActions()));
        }
        return new BatchResult(List.copyOf(items));
    }

    public List<CategoryView> categories() {
        return jdbc.sql("""
                SELECT category.id,category.parent_id,category.code,category.name,category.sort_order,category.active,
                  category.version,(SELECT count(*) FROM equipment e WHERE e.category_id=category.id) equipment_count,
                  category.updated_at FROM equipment_category category
                WHERE category.organization_id=:organization ORDER BY category.sort_order,category.name
                """).param("organization", actor.require().organizationId()).query(this::mapCategory).list();
    }

    @Transactional
    public CategoryView createCategory(CategoryWrite command) {
        validateCategoryParent(null, command.parentId());
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO equipment_category(id,organization_id,parent_id,code,name,sort_order)
                    VALUES (:id,:organization,:parent,:code,:name,:sortOrder)
                    """).param("id", id).param("organization", actor.require().organizationId())
                    .param("parent", command.parentId()).param("code", required(command.code(), 64, "分类编码"))
                    .param("name", required(command.name(), 160, "分类名称")).param("sortOrder", command.sortOrder()).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "EQUIPMENT_CATEGORY_CODE_EXISTS", "分类编码已存在");
        }
        CategoryView created = requireCategory(id);
        audit.record("EQUIPMENT_CATEGORY_CREATED", "EQUIPMENT_CATEGORY", id, null, created, null, null);
        return created;
    }

    @Transactional
    public CategoryView updateCategory(UUID id, CategoryWrite command) {
        CategoryView before = requireCategory(id);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict("VERSION_CONFLICT", "设备分类已更新", before.version());
        validateCategoryParent(id, command.parentId());
        int changed;
        try {
            changed = jdbc.sql("""
                    UPDATE equipment_category SET parent_id=:parent,code=:code,name=:name,sort_order=:sortOrder,
                      version=version+1,updated_at=now() WHERE id=:id AND version=:version
                    """).param("parent", command.parentId()).param("code", required(command.code(), 64, "分类编码"))
                    .param("name", required(command.name(), 160, "分类名称")).param("sortOrder", command.sortOrder())
                    .param("id", id).param("version", before.version()).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "EQUIPMENT_CATEGORY_CODE_EXISTS", "分类编码已存在");
        }
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "设备分类已更新", requireCategory(id).version());
        CategoryView after = requireCategory(id);
        audit.record("EQUIPMENT_CATEGORY_UPDATED", "EQUIPMENT_CATEGORY", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TransitionResult transitionCategory(UUID id, Transition command) {
        CategoryView before = requireCategory(id);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict("VERSION_CONFLICT", "设备分类已更新", before.version());
        String action = required(command.action(), 40, "动作").toUpperCase(Locale.ROOT);
        boolean active = switch (action) {
            case "ENABLE" -> true;
            case "DISABLE" -> false;
            default -> throw invalid("EQUIPMENT_CATEGORY_TRANSITION_INVALID", "分类动作无效");
        };
        int changed = jdbc.sql("""
                UPDATE equipment_category SET active=:active,version=version+1,updated_at=now()
                WHERE id=:id AND version=:version
                """).param("active", active).param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "设备分类已更新", requireCategory(id).version());
        CategoryView after = requireCategory(id);
        audit.record("EQUIPMENT_CATEGORY_" + action, "EQUIPMENT_CATEGORY", id, before, after, command.reasonCode(), command.comment());
        return new TransitionResult(active ? "ACTIVE" : "INACTIVE", after.version(), List.of(active ? "DISABLE" : "ENABLE"));
    }

    @Transactional
    public void assignCategory(List<UUID> equipmentIds, UUID categoryId) {
        List<UUID> ids = equipmentIds == null ? List.of() : equipmentIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() > 500) throw invalid("EQUIPMENT_SELECTION_INVALID", "请选择1至500台设备");
        CategoryView category = requireCategory(categoryId);
        if (!category.active()) throw invalid("EQUIPMENT_CATEGORY_INACTIVE", "不能设定已停用分类");
        int changed = jdbc.sql("""
                UPDATE equipment SET category_id=:category,version=version+1,updated_at=now()
                WHERE organization_id=:organization AND id IN (:ids) AND approval_state IN ('DRAFT','REJECTED')
                """).param("category", categoryId).param("organization", actor.require().organizationId())
                .param("ids", ids).update();
        if (changed != ids.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "EQUIPMENT_CATEGORY_ASSIGN_BLOCKED", "仅草稿或驳回设备可批量设定分类");
        }
        audit.record("EQUIPMENT_CATEGORY_ASSIGNED", "EQUIPMENT_CATEGORY", categoryId, null, ids, null, "批量设定分类");
    }

    public CursorPage<CertificateView> certificates(String query, String certificateType, String inspectionType,
                                                     String verificationStatus, String result, String state,
                                                     String uploaded, LocalDate issuedFrom, LocalDate issuedTo,
                                                     String equipmentCode, String certificateNumber,
                                                     String equipmentName, String model, String manufacturer,
                                                     String issuingOrganization, String operatorName,
                                                     String cursorValue, int limit) {
        PageCursor cursor = PageCursor.decode(cursorValue);
        String cursorClause = cursor == null ? "" : " AND (certificate.updated_at,certificate.id)<(:cursorTime,:cursorId)";
        String fromClause = issuedFrom == null ? "" : " AND certificate.issued_on>=:issuedFrom";
        String toClause = issuedTo == null ? "" : " AND certificate.issued_on<=:issuedTo";
        String filters = """
                WHERE equipment.organization_id=:organization
                  AND (:query='' OR equipment.code ILIKE :pattern ESCAPE '\\' OR equipment.name ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(equipment.model,'') ILIKE :pattern ESCAPE '\\'
                    OR certificate.certificate_number ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(certificate.issuing_organization,'') ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(operator.display_name,'') ILIKE :pattern ESCAPE '\\')
                  AND (:type='' OR certificate.certificate_type=:type)
                  AND (:inspectionType='' OR certificate.inspection_type=:inspectionType)
                  AND (:verificationStatus='' OR CASE WHEN certificate.state='VOID' THEN 'VOID'
                    WHEN certificate.result='FAIL' THEN 'FAILED' WHEN certificate.valid_until<current_date THEN 'EXPIRED'
                    ELSE 'VALID' END=:verificationStatus)
                  AND (:result='' OR certificate.result=:result)
                  AND (:state='' OR certificate.state=:state)
                  AND (:uploaded='' OR (certificate.file_id IS NOT NULL)=CAST(:uploaded AS boolean))
                  AND (:equipmentCode='' OR equipment.code ILIKE :equipmentCodePattern ESCAPE '\\')
                  AND (:certificateNumber='' OR certificate.certificate_number ILIKE :certificateNumberPattern ESCAPE '\\')
                  AND (:equipmentName='' OR equipment.name ILIKE :equipmentNamePattern ESCAPE '\\')
                  AND (:model='' OR COALESCE(equipment.model,'') ILIKE :modelPattern ESCAPE '\\')
                  AND (:manufacturer='' OR COALESCE(equipment.manufacturer,'') ILIKE :manufacturerPattern ESCAPE '\\')
                  AND (:issuingOrganization='' OR COALESCE(certificate.issuing_organization,'')
                    ILIKE :issuingOrganizationPattern ESCAPE '\\')
                  AND (:operatorName='' OR COALESCE(operator.display_name,'') ILIKE :operatorNamePattern ESCAPE '\\')
                """ + fromClause + toClause;
        String from = """
                FROM equipment_certificate certificate JOIN equipment ON equipment.id=certificate.equipment_id
                LEFT JOIN app_user operator ON operator.id=certificate.operator_id
                LEFT JOIN stored_file file ON file.id=certificate.file_id
                """;
        String select = certificateSelect() + from + filters + cursorClause
                + " ORDER BY certificate.updated_at DESC,certificate.id DESC LIMIT :fetch";
        String needle = optionalFilter(query);
        Map<String, Object> params = new HashMap<>();
        params.put("organization", actor.require().organizationId()); params.put("query", needle); params.put("pattern", pattern(needle));
        params.put("type", filterEnum(certificateType, Set.of("VERIFICATION", "CALIBRATION"), "证书类型"));
        params.put("inspectionType", filterEnum(inspectionType, VERIFICATION_TYPES, "检校类型"));
        params.put("verificationStatus", filterEnum(verificationStatus, Set.of("VALID", "EXPIRED", "FAILED", "VOID"), "检定状态"));
        params.put("result", filterEnum(result, Set.of("PASS", "LIMITED", "FAIL"), "检定结论"));
        params.put("state", filterEnum(state, Set.of("DRAFT", "CONFIRMED", "VOID"), "证书状态"));
        params.put("uploaded", booleanFilter(uploaded)); params.put("fetch", limit + 1);
        addPattern(params, "equipmentCode", equipmentCode); addPattern(params, "certificateNumber", certificateNumber);
        addPattern(params, "equipmentName", equipmentName); addPattern(params, "model", model);
        addPattern(params, "manufacturer", manufacturer); addPattern(params, "issuingOrganization", issuingOrganization);
        addPattern(params, "operatorName", operatorName);
        if (issuedFrom != null) params.put("issuedFrom", issuedFrom);
        if (issuedTo != null) params.put("issuedTo", issuedTo);
        if (cursor != null) { params.put("cursorTime", cursor.updatedAt()); params.put("cursorId", cursor.id()); }
        List<CertificateView> fetched = jdbc.sql(select).params(params).query(this::mapCertificate).list();
        boolean hasMore = fetched.size() > limit;
        List<CertificateView> items = hasMore ? fetched.subList(0, limit) : fetched;
        params.remove("fetch"); params.remove("cursorTime"); params.remove("cursorId");
        long total = jdbc.sql("SELECT count(*) " + from + filters).params(params).query(Long.class).single();
        String next = hasMore ? new PageCursor(items.getLast().updatedAt(), items.getLast().id()).encode() : null;
        return new CursorPage<>(List.copyOf(items), next, hasMore, total);
    }

    public CertificateView requireCertificate(UUID id) {
        return jdbc.sql(certificateSelect() + """
                FROM equipment_certificate certificate JOIN equipment ON equipment.id=certificate.equipment_id
                LEFT JOIN app_user operator ON operator.id=certificate.operator_id
                LEFT JOIN stored_file file ON file.id=certificate.file_id
                WHERE certificate.id=:id AND equipment.organization_id=:organization
                """).param("id", id).param("organization", actor.require().organizationId())
                .query(this::mapCertificate).optional()
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_CERTIFICATE_NOT_FOUND", "设备证书不存在"));
    }

    @Transactional
    public CertificateView addCertificate(UUID equipmentId, CertificateWrite body) {
        View equipment = require(equipmentId);
        ValidatedCertificate value = validateCertificate(body);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO equipment_certificate(id,equipment_id,certificate_type,inspection_type,
                      certificate_number,issued_on,valid_until,confirmed_on,result,issuing_organization,
                      file_id,remark,state,operator_id)
                    VALUES (:id,:equipment,:type,:inspectionType,:number,:issuedOn,:validUntil,:confirmedOn,
                      :result,:organization,:file,:remark,'CONFIRMED',:actor)
                    """).param("id", id).param("equipment", equipmentId).param("type", value.certificateType())
                    .param("inspectionType", value.inspectionType()).param("number", value.certificateNumber())
                    .param("issuedOn", value.issuedOn()).param("validUntil", value.validUntil())
                    .param("confirmedOn", value.confirmedOn()).param("result", value.result())
                    .param("organization", value.issuingOrganization()).param("file", value.fileId())
                    .param("remark", value.remark()).param("actor", actor.require().id()).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "EQUIPMENT_CERTIFICATE_EXISTS", "该设备已存在相同类型和编号的证书");
        }
        CertificateView created = requireCertificate(id);
        audit.record("EQUIPMENT_CERTIFICATE_CREATED", "EQUIPMENT_CERTIFICATE", id, null, created, null, equipment.code());
        return created;
    }

    @Transactional
    public CertificateView updateCertificate(UUID id, CertificateWrite body) {
        CertificateView before = requireCertificate(id);
        if (before.version() != body.expectedVersion()) throw ApiException.conflict("VERSION_CONFLICT", "设备证书已更新", before.version());
        if (before.state().equals("VOID")) throw new ApiException(HttpStatus.CONFLICT, "EQUIPMENT_CERTIFICATE_VOID", "已作废证书不能修改");
        ValidatedCertificate value = validateCertificate(body);
        int changed = jdbc.sql("""
                UPDATE equipment_certificate SET certificate_type=:type,inspection_type=:inspectionType,
                  certificate_number=:number,issued_on=:issuedOn,valid_until=:validUntil,confirmed_on=:confirmedOn,
                  result=:result,issuing_organization=:organization,file_id=:file,remark=:remark,
                  operator_id=:actor,version=version+1,updated_at=now() WHERE id=:id AND version=:version
                """).param("type", value.certificateType()).param("inspectionType", value.inspectionType())
                .param("number", value.certificateNumber()).param("issuedOn", value.issuedOn())
                .param("validUntil", value.validUntil()).param("confirmedOn", value.confirmedOn())
                .param("result", value.result()).param("organization", value.issuingOrganization())
                .param("file", value.fileId()).param("remark", value.remark()).param("actor", actor.require().id())
                .param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "设备证书已更新", requireCertificate(id).version());
        CertificateView after = requireCertificate(id);
        audit.record("EQUIPMENT_CERTIFICATE_UPDATED", "EQUIPMENT_CERTIFICATE", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TransitionResult transitionCertificate(UUID id, Transition command) {
        CertificateView before = requireCertificate(id);
        if (before.version() != command.expectedVersion()) throw ApiException.conflict("VERSION_CONFLICT", "设备证书已更新", before.version());
        if (!required(command.action(), 40, "动作").equalsIgnoreCase("VOID") || before.state().equals("VOID")) {
            throw new ApiException(HttpStatus.CONFLICT, "EQUIPMENT_CERTIFICATE_TRANSITION_INVALID", "当前证书不能执行该动作");
        }
        required(command.comment(), 1000, "作废原因");
        int changed = jdbc.sql("""
                UPDATE equipment_certificate SET state='VOID',version=version+1,operator_id=:actor,updated_at=now()
                WHERE id=:id AND version=:version
                """).param("actor", actor.require().id()).param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "设备证书已更新", requireCertificate(id).version());
        CertificateView after = requireCertificate(id);
        audit.record("EQUIPMENT_CERTIFICATE_VOIDED", "EQUIPMENT_CERTIFICATE", id, before, after, command.reasonCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), List.of());
    }

    boolean certificateValid(UUID id) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM equipment_certificate WHERE equipment_id=:id AND state='CONFIRMED'
                  AND issued_on<=current_date AND valid_until>=current_date AND result IN ('PASS','LIMITED'))
                """).param("id", id).query(Boolean.class).single();
    }

    void updateOperationalState(UUID equipmentId, long expectedVersion, EquipmentState expectedState,
                                EquipmentState nextState, String inventoryStatus) {
        int changed = jdbc.sql("""
                UPDATE equipment SET state=:next,inventory_status=:inventory,version=version+1,updated_at=now()
                WHERE id=:id AND version=:version AND state=:expected
                """).param("next", nextState.name()).param("inventory", inventoryStatus).param("id", equipmentId)
                .param("version", expectedVersion).param("expected", expectedState.name()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "设备状态已更新", require(equipmentId).version());
    }

    private Validated validate(Write body) {
        String code = required(body.code(), 64, "设备编号");
        String name = required(body.name(), 200, "设备名称");
        String model = required(body.model(), 120, "规格型号");
        String performance = requiredEnum(body.performanceStatus(), Set.of("NORMAL", "ABNORMAL"), "性能状态");
        String verificationType = optionalEnum(body.verificationType(), VERIFICATION_TYPES, "检校类型");
        String equipmentType = optionalEnum(body.equipmentType(), EQUIPMENT_TYPES, "设备类型");
        String pileType = optionalEnum(body.pileEquipmentType(), PILE_TYPES, "桩基设备类型");
        if (body.verificationCycleMonths() != null && body.verificationCycleMonths() <= 0) throw invalid("EQUIPMENT_CYCLE_INVALID", "检定周期必须大于0");
        if (body.purchasePrice() != null && body.purchasePrice().signum() < 0) throw invalid("EQUIPMENT_PRICE_INVALID", "购置单价不能为负数");
        if (body.categoryId() != null) {
            CategoryView category = requireCategory(body.categoryId());
            if (!category.active()) throw invalid("EQUIPMENT_CATEGORY_INACTIVE", "不能选择已停用分类");
        }
        requirePerson(body.custodianId());
        requireFile(body.attachmentFileId(), Set.of("EQUIPMENT_ATTACHMENT"), "设备附件");
        requireFile(body.photoFileId(), Set.of("EQUIPMENT_PHOTO"), "设备照片");
        List<UUID> projects = body.detectionProjectIds() == null ? List.of()
                : body.detectionProjectIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (projects.size() > 2000) throw invalid("EQUIPMENT_PROJECT_SCOPE_EXCEEDED", "限定检测项目最多2000项");
        if (!projects.isEmpty()) {
            long count = jdbc.sql("SELECT count(*) FROM detection_catalog_node WHERE id IN (:ids) AND node_type='PROJECT'")
                    .param("ids", projects).query(Long.class).single();
            if (count != projects.size()) throw invalid("EQUIPMENT_PROJECT_SCOPE_INVALID", "限定检测项目包含无效节点");
        }
        return new Validated(code, optional(body.assetNumber(), 80), name, model, body.categoryId(), body.purchasedOn(),
                body.purchasePrice(), optional(body.manufacturer(), 240), optional(body.serialNumber(), 120),
                body.verificationCycleMonths(), verificationType, optional(body.technicalSpecification(), 1000),
                optional(body.nominalRange(), 240), optional(body.measurementRange(), 240), optional(body.resolution(), 120),
                optional(body.accuracy(), 120), body.custodianId(), body.primaryEquipment(), body.enabled(), performance,
                optional(body.location(), 160), equipmentType, pileType, body.attachmentFileId(), body.photoFileId(),
                optional(body.remark(), 2000), projects);
    }

    private ValidatedCertificate validateCertificate(CertificateWrite body) {
        String type = requiredEnum(body.certificateType(), Set.of("VERIFICATION", "CALIBRATION"), "证书类型");
        String inspectionType = optionalEnum(body.inspectionType(), VERIFICATION_TYPES, "检校类型");
        String result = requiredEnum(body.result(), Set.of("PASS", "LIMITED", "FAIL"), "检校结论");
        if (body.issuedOn() == null || body.validUntil() == null) throw invalid("EQUIPMENT_CERTIFICATE_DATE_REQUIRED", "请填写检校日期和有效期");
        if (body.validUntil().isBefore(body.issuedOn())) throw invalid("EQUIPMENT_CERTIFICATE_DATE_INVALID", "有效期不能早于检校日期");
        requireFile(body.fileId(), Set.of("EQUIPMENT_CERTIFICATE"), "证书附件");
        return new ValidatedCertificate(type, inspectionType, required(body.certificateNumber(), 120, "检校编号"),
                body.issuedOn(), body.validUntil(), body.confirmedOn(), result,
                required(body.issuingOrganization(), 240, "检校单位"), body.fileId(), optional(body.remark(), 2000));
    }

    private Map<String, Object> writeParams(Validated value) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", value.code()); params.put("asset", value.assetNumber()); params.put("name", value.name());
        params.put("model", value.model()); params.put("category", value.categoryId()); params.put("purchased", value.purchasedOn());
        params.put("price", value.purchasePrice()); params.put("manufacturer", value.manufacturer()); params.put("serial", value.serialNumber());
        params.put("cycle", value.verificationCycleMonths()); params.put("verificationType", value.verificationType());
        params.put("technical", value.technicalSpecification()); params.put("nominalRange", value.nominalRange());
        params.put("measurementRange", value.measurementRange()); params.put("resolution", value.resolution()); params.put("accuracy", value.accuracy());
        params.put("custodian", value.custodianId()); params.put("primary", value.primaryEquipment()); params.put("enabled", value.enabled());
        params.put("performance", value.performanceStatus()); params.put("location", value.location()); params.put("equipmentType", value.equipmentType());
        params.put("pileType", value.pileEquipmentType()); params.put("attachment", value.attachmentFileId()); params.put("photo", value.photoFileId());
        params.put("remark", value.remark());
        return params;
    }

    private Map<String, Object> listParams(String query, UUID categoryId, String equipmentType, String enabled,
                                           String performance, String approval, String verification, String scrapped,
                                           String inventory, String code, String asset, String name, String manufacturer,
                                           String verificationOrg, String technical, String location, String serial) {
        Map<String, Object> params = new HashMap<>();
        String needle = optionalFilter(query);
        params.put("query", needle); params.put("pattern", pattern(needle)); params.put("categoryId", categoryId);
        params.put("equipmentType", filterEnum(equipmentType, EQUIPMENT_TYPES, "设备类型"));
        params.put("enabled", booleanFilter(enabled));
        params.put("performance", filterEnum(performance, Set.of("NORMAL", "ABNORMAL"), "性能状态"));
        params.put("approval", filterEnum(approval, Set.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED"), "审批状态"));
        params.put("verification", filterEnum(verification, Set.of("NONE", "FAILED", "EXPIRED", "VALID"), "检定状态"));
        params.put("scrapped", booleanFilter(scrapped));
        params.put("inventory", filterEnum(inventory, Set.of("IN", "OUT"), "出入库状态"));
        params.put("code", optionalFilter(code)); params.put("codePattern", pattern(code));
        params.put("asset", optionalFilter(asset)); params.put("assetPattern", pattern(asset));
        params.put("name", optionalFilter(name)); params.put("namePattern", pattern(name));
        params.put("manufacturer", optionalFilter(manufacturer)); params.put("manufacturerPattern", pattern(manufacturer));
        params.put("verificationOrg", optionalFilter(verificationOrg)); params.put("verificationOrgPattern", pattern(verificationOrg));
        params.put("technical", optionalFilter(technical)); params.put("technicalPattern", pattern(technical));
        params.put("location", optionalFilter(location)); params.put("locationPattern", pattern(location));
        params.put("serial", optionalFilter(serial)); params.put("serialPattern", pattern(serial));
        return params;
    }

    private void replaceProjects(UUID equipmentId, List<UUID> projects) {
        jdbc.sql("DELETE FROM equipment_detection_project WHERE equipment_id=:id").param("id", equipmentId).update();
        for (UUID project : projects) jdbc.sql("INSERT INTO equipment_detection_project(equipment_id,project_node_id) VALUES (:equipment,:project)")
                .param("equipment", equipmentId).param("project", project).update();
    }

    private void validateCategoryParent(UUID id, UUID parentId) {
        if (parentId == null) return;
        requireCategory(parentId);
        if (parentId.equals(id)) throw invalid("EQUIPMENT_CATEGORY_CYCLE", "分类不能作为自己的上级");
        if (id != null) {
            boolean cycle = jdbc.sql("""
                    WITH RECURSIVE descendants AS (
                      SELECT id FROM equipment_category WHERE parent_id=:id
                      UNION ALL SELECT child.id FROM equipment_category child JOIN descendants d ON child.parent_id=d.id
                    ) SELECT EXISTS(SELECT 1 FROM descendants WHERE id=:parent)
                    """).param("id", id).param("parent", parentId).query(Boolean.class).single();
            if (cycle) throw invalid("EQUIPMENT_CATEGORY_CYCLE", "不能将分类移动到其子级");
        }
    }

    private CategoryView requireCategory(UUID id) {
        return jdbc.sql("""
                SELECT category.id,category.parent_id,category.code,category.name,category.sort_order,category.active,
                  category.version,(SELECT count(*) FROM equipment e WHERE e.category_id=category.id) equipment_count,
                  category.updated_at FROM equipment_category category
                WHERE category.id=:id AND category.organization_id=:organization
                """).param("id", id).param("organization", actor.require().organizationId())
                .query(this::mapCategory).optional()
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_CATEGORY_NOT_FOUND", "设备分类不存在"));
    }

    private void requirePerson(UUID id) {
        if (id == null) return;
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM person p JOIN department d ON d.id=p.department_id
                  WHERE p.id=:id AND d.organization_id=:organization AND p.active)
                """).param("id", id).param("organization", actor.require().organizationId()).query(Boolean.class).single();
        if (!exists) throw invalid("EQUIPMENT_PERSON_INVALID", "人员不存在、已停用或不在当前机构");
    }

    private void requireFile(UUID id, Set<String> purposes, String label) {
        if (id == null) return;
        StoredFileService.View file = files.require(id);
        if (!purposes.contains(file.purpose())) throw invalid("EQUIPMENT_FILE_PURPOSE_INVALID", label + "上传入口不匹配");
    }

    private void requireApproved(View value) {
        if (!value.approvalState().equals("APPROVED")) {
            throw ApiException.blocked("EQUIPMENT_NOT_APPROVED", "设备未审批", value.version(), List.of("设备必须提交并审批后才能进入业务流转"));
        }
    }

    private void invalidTransition() { throw new ApiException(HttpStatus.CONFLICT, "INVALID_EQUIPMENT_TRANSITION", "当前设备状态不允许该动作"); }

    private String certificateSelect() {
        return """
                SELECT certificate.id,certificate.equipment_id,equipment.code equipment_code,
                  equipment.name equipment_name,equipment.model,equipment.manufacturer,
                  certificate.certificate_type,certificate.inspection_type,certificate.certificate_number,
                  certificate.issued_on,certificate.valid_until,certificate.confirmed_on,certificate.result,
                  certificate.issuing_organization,certificate.file_id,file.original_name file_name,
                  certificate.remark,certificate.state,CASE WHEN certificate.state='VOID' THEN 'VOID'
                    WHEN certificate.result='FAIL' THEN 'FAILED' WHEN certificate.valid_until<current_date THEN 'EXPIRED'
                    ELSE 'VALID' END verification_status,
                  certificate.operator_id,operator.display_name operator_name,
                  certificate.version,certificate.created_at,certificate.updated_at
                """;
    }

    private Summary mapSummary(ResultSet rs, int row) throws SQLException {
        String approval = rs.getString("approval_state");
        EquipmentState state = EquipmentState.valueOf(rs.getString("state"));
        boolean enabled = rs.getBoolean("enabled");
        return new Summary(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("asset_number"),
                rs.getString("name"), rs.getString("model"), rs.getObject("category_id", UUID.class),
                rs.getString("category_name"), rs.getObject("purchased_on", LocalDate.class), rs.getBigDecimal("purchase_price"),
                rs.getString("manufacturer"), rs.getString("serial_number"),
                rs.getObject("verification_cycle_months", Integer.class), rs.getString("verification_type"),
                rs.getString("technical_specification"), rs.getString("nominal_range"), rs.getString("measurement_range"),
                rs.getString("resolution"), rs.getString("accuracy"), rs.getString("location"),
                rs.getObject("custodian_id", UUID.class), rs.getString("custodian_name"),
                rs.getString("custodian_employee_number"),
                rs.getBoolean("primary_equipment"), enabled, rs.getString("performance_status"),
                rs.getString("equipment_type"), rs.getString("pile_equipment_type"), rs.getString("remark"),
                state, rs.getString("inventory_status"), approval,
                rs.getObject("certificate_valid_until", LocalDate.class), rs.getString("verification_status"),
                rs.getObject("latest_verification_date", LocalDate.class), rs.getString("latest_verification_organization"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class), actions(approval, state, enabled));
    }

    private View mapView(ResultSet rs, int row) throws SQLException {
        String approval = rs.getString("approval_state");
        EquipmentState state = EquipmentState.valueOf(rs.getString("state"));
        boolean enabled = rs.getBoolean("enabled");
        return new View(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("asset_number"),
                rs.getString("name"), rs.getString("model"), rs.getObject("category_id", UUID.class),
                rs.getString("category_name"), rs.getObject("purchased_on", LocalDate.class), rs.getBigDecimal("purchase_price"),
                rs.getString("manufacturer"), rs.getString("serial_number"), rs.getObject("verification_cycle_months", Integer.class),
                rs.getString("verification_type"), rs.getString("technical_specification"), rs.getString("nominal_range"),
                rs.getString("measurement_range"), rs.getString("resolution"), rs.getString("accuracy"),
                rs.getObject("custodian_id", UUID.class), rs.getString("custodian_name"), rs.getBoolean("primary_equipment"),
                enabled, rs.getString("performance_status"), rs.getString("location"), rs.getString("equipment_type"),
                rs.getString("pile_equipment_type"), rs.getObject("attachment_file_id", UUID.class),
                rs.getString("attachment_file_name"), rs.getObject("photo_file_id", UUID.class), rs.getString("photo_file_name"),
                rs.getString("remark"), state, rs.getString("inventory_status"), approval,
                rs.getObject("approved_by", UUID.class), rs.getObject("approved_at", OffsetDateTime.class),
                rs.getObject("certificate_valid_until", LocalDate.class), rs.getString("verification_status"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), List.of(), actions(approval, state, enabled));
    }

    private View withProjects(View value, List<UUID> projects) {
        return new View(value.id(), value.code(), value.assetNumber(), value.name(), value.model(), value.categoryId(),
                value.categoryName(), value.purchasedOn(), value.purchasePrice(), value.manufacturer(), value.serialNumber(),
                value.verificationCycleMonths(), value.verificationType(), value.technicalSpecification(), value.nominalRange(),
                value.measurementRange(), value.resolution(), value.accuracy(), value.custodianId(), value.custodianName(),
                value.primaryEquipment(), value.enabled(), value.performanceStatus(), value.location(), value.equipmentType(),
                value.pileEquipmentType(), value.attachmentFileId(), value.attachmentFileName(), value.photoFileId(),
                value.photoFileName(), value.remark(), value.state(), value.inventoryStatus(), value.approvalState(),
                value.approvedBy(), value.approvedAt(), value.certificateValidUntil(), value.verificationStatus(),
                value.version(), value.createdAt(), value.updatedAt(), projects, value.allowedActions());
    }

    private CategoryView mapCategory(ResultSet rs, int row) throws SQLException {
        return new CategoryView(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getInt("sort_order"), rs.getBoolean("active"), rs.getLong("version"),
                rs.getLong("equipment_count"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private CertificateView mapCertificate(ResultSet rs, int row) throws SQLException {
        return new CertificateView(rs.getObject("id", UUID.class), rs.getObject("equipment_id", UUID.class),
                rs.getString("equipment_code"), rs.getString("equipment_name"), rs.getString("model"),
                rs.getString("manufacturer"), rs.getString("certificate_type"), rs.getString("inspection_type"),
                rs.getString("certificate_number"), rs.getObject("issued_on", LocalDate.class),
                rs.getObject("valid_until", LocalDate.class), rs.getObject("confirmed_on", LocalDate.class),
                rs.getString("result"), rs.getString("issuing_organization"), rs.getObject("file_id", UUID.class),
                rs.getString("file_name"), rs.getString("remark"), rs.getString("state"), rs.getString("verification_status"),
                rs.getObject("operator_id", UUID.class), rs.getString("operator_name"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static List<String> actions(String approval, EquipmentState state, boolean enabled) {
        if (approval.equals("DRAFT") || approval.equals("REJECTED")) return List.of("UPDATE", "SUBMIT", "DELETE");
        if (approval.equals("SUBMITTED")) return List.of("WITHDRAW", "APPROVE", "REJECT");
        if (state == EquipmentState.SCRAPPED) return List.of();
        ArrayList<String> result = new ArrayList<>();
        if (state == EquipmentState.AVAILABLE) result.add("UPDATE");
        result.add(enabled ? "DISABLE" : "ENABLE");
        if (state == EquipmentState.AVAILABLE || state == EquipmentState.OUT_OF_SERVICE) result.add("SCRAP");
        return List.copyOf(result);
    }

    private void addPattern(Map<String, Object> params, String key, String value) {
        String text = optionalFilter(value); params.put(key, text); params.put(key + "Pattern", pattern(text));
    }

    private String pattern(String value) {
        String text = optionalFilter(value);
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
    private String optionalFilter(String value) { return value == null ? "" : value.trim(); }
    private String booleanFilter(String value) {
        String normalized = optionalFilter(value).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.equals("true") || normalized.equals("false")) return normalized;
        throw invalid("EQUIPMENT_FILTER_INVALID", "布尔筛选条件无效");
    }
    private String filterEnum(String value, Set<String> allowed, String label) {
        return value == null || value.isBlank() ? "" : requiredEnum(value, allowed, label);
    }
    private String requiredEnum(String value, Set<String> allowed, String label) {
        String normalized = required(value, 80, label).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw invalid("EQUIPMENT_ENUM_INVALID", label + "无效");
        return normalized;
    }
    private String optionalEnum(String value, Set<String> allowed, String label) {
        return value == null || value.isBlank() ? null : requiredEnum(value, allowed, label);
    }
    private String required(String value, int max, String label) {
        if (value == null || value.isBlank()) throw invalid("EQUIPMENT_FIELD_REQUIRED", label + "不能为空");
        String text = value.trim();
        if (text.length() > max) throw invalid("EQUIPMENT_FIELD_TOO_LONG", label + "超过最大长度");
        return text;
    }
    private String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        if (text.length() > max) throw invalid("EQUIPMENT_FIELD_TOO_LONG", "字段超过最大长度");
        return text;
    }
    private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }

    private record Validated(String code, String assetNumber, String name, String model, UUID categoryId,
                             LocalDate purchasedOn, BigDecimal purchasePrice, String manufacturer, String serialNumber,
                             Integer verificationCycleMonths, String verificationType, String technicalSpecification,
                             String nominalRange, String measurementRange, String resolution, String accuracy,
                             UUID custodianId, boolean primaryEquipment, boolean enabled, String performanceStatus,
                             String location, String equipmentType, String pileEquipmentType, UUID attachmentFileId,
                             UUID photoFileId, String remark, List<UUID> projectIds) {}
    private record ValidatedCertificate(String certificateType, String inspectionType, String certificateNumber,
                                        LocalDate issuedOn, LocalDate validUntil, LocalDate confirmedOn, String result,
                                        String issuingOrganization, UUID fileId, String remark) {}
}
