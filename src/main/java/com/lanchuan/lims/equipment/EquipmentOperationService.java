package com.lanchuan.lims.equipment;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.storedfile.StoredFileService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class EquipmentOperationService {
    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final AuditService audit;
    private final StoredFileService files;
    private final EquipmentService equipment;

    public EquipmentOperationService(JdbcClient jdbc, CurrentActor actor, AuditService audit,
                                     StoredFileService files, EquipmentService equipment) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.audit = audit;
        this.files = files;
        this.equipment = equipment;
    }

    public record InspectionCreate(UUID equipmentId, UUID outboundPersonId, OffsetDateTime outboundAt,
                                   BigDecimal outboundAccessoryQuantity, String destination, UUID photoFileId,
                                   UUID attachmentFileId, String remark, long expectedVersion) {}
    public record InspectionTransition(String action, long expectedVersion, String reasonCode, String comment,
                                       UUID personId, OffsetDateTime occurredAt, BigDecimal accessoryQuantity,
                                       String inspectionType, String inspectionNumber, String inspectionOrganization,
                                       LocalDate inspectionDate, LocalDate validUntil, String result,
                                       UUID certificateFileId) {}
    public record InspectionView(UUID id, UUID equipmentId, String equipmentCode, String equipmentName, String model,
                                 UUID outboundPersonId, String outboundPersonName, OffsetDateTime outboundAt,
                                 BigDecimal outboundAccessoryQuantity, String destination, UUID photoFileId,
                                 UUID attachmentFileId, UUID receivedBy, String receivedByName, OffsetDateTime receivedAt,
                                 String inspectionType, String inspectionNumber, String inspectionOrganization,
                                 LocalDate inspectionDate, LocalDate validUntil, String result, UUID certificateFileId,
                                 UUID filledBy, String filledByName, OffsetDateTime filledAt, UUID confirmedBy,
                                 String confirmedByName, OffsetDateTime confirmedAt, UUID reviewedBy,
                                 String reviewedByName, OffsetDateTime reviewedAt, UUID stockInBy,
                                 String stockInByName, OffsetDateTime stockInAt, BigDecimal stockInAccessoryQuantity,
                                 String state, String remark, String voidReason, long version,
                                 OffsetDateTime createdAt, OffsetDateTime updatedAt, List<String> allowedActions) {}

    public record BorrowCreate(UUID equipmentId, UUID borrowerId, List<UUID> companionIds,
                               OffsetDateTime borrowedAt, String borrowedPeriod, OffsetDateTime plannedReturnAt,
                               String plannedReturnPeriod, String beforeCondition, BigDecimal accessoryQuantity,
                               String province, String city, String district, String address, String destination,
                               UUID photoFileId, String remark, long expectedVersion) {}
    public record BorrowTransition(String action, long expectedVersion, String reasonCode, String comment,
                                   UUID personId, OffsetDateTime occurredAt, String period, String condition,
                                   BigDecimal accessoryQuantity) {}
    public record BorrowView(UUID id, UUID equipmentId, String equipmentCode, String equipmentName, String model,
                             UUID borrowerId, String borrowerName, List<PersonOption> companions,
                             OffsetDateTime borrowedAt, String borrowedPeriod, OffsetDateTime plannedReturnAt,
                             String plannedReturnPeriod, String beforeCondition, BigDecimal outboundAccessoryQuantity,
                             String province, String city, String district, String address, String destination,
                             UUID photoFileId, UUID returnedBy, String returnedByName, OffsetDateTime returnedAt,
                             String returnPeriod, String afterCondition, BigDecimal returnedAccessoryQuantity,
                             UUID transferredTo, String transferredToName, OffsetDateTime transferredAt,
                             String transferReason, String state, String remark, String voidReason, long version,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt, List<String> allowedActions) {}

    public record UsageCreate(UUID equipmentId, UUID taskId, String sampleName, String commissionNumber,
                              UUID usedBy, OffsetDateTime startedAt, BigDecimal temperature, BigDecimal humidity,
                              String location, String beforeCondition, String remark, long expectedVersion) {}
    public record UsageTransition(String action, long expectedVersion, String reasonCode, String comment,
                                  OffsetDateTime endedAt, String afterCondition) {}
    public record UsageView(UUID id, UUID equipmentId, String equipmentCode, String equipmentName, String model,
                            String equipmentType, UUID taskId, String sampleName, String commissionNumber,
                            UUID usedBy, String usedByName,
                            OffsetDateTime startedAt, OffsetDateTime endedAt, BigDecimal temperature,
                            BigDecimal humidity, String location, String beforeCondition, String afterCondition,
                            String state, String remark, long version, OffsetDateTime createdAt,
                            OffsetDateTime updatedAt, List<String> allowedActions) {}
    private record TestUsageSource(String sampleName, String commissionNumber,
                                   OffsetDateTime startedAt, String location) {}

    public record MovementView(UUID id, UUID equipmentId, String equipmentCode, String equipmentName, String model,
                               String operationType, String movementType, BigDecimal accessoryQuantity,
                               OffsetDateTime occurredAt, UUID personId, String personName, String referenceType,
                               UUID referenceId, String remark) {}
    public record PersonOption(UUID id, String name) {}

    public CursorPage<InspectionView> inspections(String query, String states, String result,
                                                  LocalDate outboundFrom, LocalDate outboundTo,
                                                  String equipmentCode, String equipmentName, String model,
                                                  String inspectionOrganization,
                                                  String cursorValue, int limit) {
        PageCursor cursor = PageCursor.decode(cursorValue);
        String cursorClause = cursor == null ? "" : " AND (inspection.updated_at,inspection.id)<(:cursorTime,:cursorId)";
        String fromDate = outboundFrom == null ? "" : " AND inspection.outbound_at>=:outboundFrom";
        String toDate = outboundTo == null ? "" : " AND inspection.outbound_at<(:outboundTo+interval '1 day')";
        String filters = """
                WHERE equipment.organization_id=:organization
                  AND (:query='' OR equipment.code ILIKE :pattern ESCAPE '\\' OR equipment.name ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(equipment.model,'') ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(inspection.inspection_organization,'') ILIKE :pattern ESCAPE '\\')
                  AND (:states='' OR inspection.state=ANY(string_to_array(:states, ',')))
                  AND (:result='' OR inspection.result=:result)
                  AND (:equipmentCode='' OR equipment.code ILIKE :equipmentCodePattern ESCAPE '\\')
                  AND (:equipmentName='' OR equipment.name ILIKE :equipmentNamePattern ESCAPE '\\')
                  AND (:model='' OR COALESCE(equipment.model,'') ILIKE :modelPattern ESCAPE '\\')
                  AND (:inspectionOrganization='' OR COALESCE(inspection.inspection_organization,'')
                    ILIKE :inspectionOrganizationPattern ESCAPE '\\')
                """ + fromDate + toDate;
        String from = inspectionFrom();
        Map<String, Object> params = pageParams(query, states, result, cursor, limit);
        addPattern(params, "equipmentCode", equipmentCode);
        addPattern(params, "equipmentName", equipmentName);
        addPattern(params, "model", model);
        addPattern(params, "inspectionOrganization", inspectionOrganization);
        if (outboundFrom != null) params.put("outboundFrom", outboundFrom);
        if (outboundTo != null) params.put("outboundTo", outboundTo);
        List<InspectionView> fetched = jdbc.sql(inspectionSelect() + from + filters + cursorClause
                + " ORDER BY inspection.updated_at DESC,inspection.id DESC LIMIT :fetch")
                .params(params).query(this::mapInspection).list();
        return page(fetched, limit, params, "SELECT count(*) " + from + filters, InspectionView::updatedAt, InspectionView::id);
    }

    public InspectionView requireInspection(UUID id) {
        return jdbc.sql(inspectionSelect() + inspectionFrom() + " WHERE inspection.id=:id AND equipment.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId()).query(this::mapInspection).optional()
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_INSPECTION_NOT_FOUND", "送检记录不存在"));
    }

    @Transactional
    public InspectionView createInspection(InspectionCreate command) {
        EquipmentService.View current = usableAvailable(command.equipmentId(), command.expectedVersion());
        requirePerson(command.outboundPersonId());
        requireFile(command.photoFileId(), "EQUIPMENT_INSPECTION_PHOTO");
        requireFile(command.attachmentFileId(), "EQUIPMENT_INSPECTION_ATTACHMENT");
        OffsetDateTime outboundAt = command.outboundAt() == null ? OffsetDateTime.now() : command.outboundAt();
        BigDecimal quantity = nonNegative(command.outboundAccessoryQuantity(), "出库配件数量");
        UUID id = UUID.randomUUID();
        equipment.updateOperationalState(current.id(), current.version(), EquipmentState.AVAILABLE,
                EquipmentState.UNDER_INSPECTION, "OUT");
        jdbc.sql("""
                INSERT INTO equipment_inspection(id,equipment_id,outbound_person_id,outbound_at,
                  outbound_accessory_quantity,destination,photo_file_id,attachment_file_id,state,remark,created_by)
                VALUES (:id,:equipment,:person,:occurred,:quantity,:destination,:photo,:attachment,'OUTBOUND',:remark,:actor)
                """).param("id", id).param("equipment", current.id()).param("person", command.outboundPersonId())
                .param("occurred", outboundAt).param("quantity", quantity).param("destination", optional(command.destination(), 500))
                .param("photo", command.photoFileId()).param("attachment", command.attachmentFileId())
                .param("remark", optional(command.remark(), 2000)).param("actor", actor.require().id()).update();
        movement(current.id(), "OUT", "INSPECTION", quantity, outboundAt, command.outboundPersonId(),
                "EQUIPMENT_INSPECTION", id, "送检出库");
        InspectionView created = requireInspection(id);
        audit.record("EQUIPMENT_INSPECTION_CREATED", "EQUIPMENT_INSPECTION", id, null, created, null, null);
        return created;
    }

    @Transactional
    public TransitionResult transitionInspection(UUID id, InspectionTransition command) {
        InspectionView before = requireInspection(id);
        requireVersion(before.version(), command.expectedVersion(), "送检记录已更新");
        String action = required(command.action(), 40, "动作").toUpperCase(Locale.ROOT);
        String next;
        Map<String, Object> updates = new HashMap<>();
        updates.put("id", id); updates.put("version", before.version());
        switch (action) {
            case "RECEIVE" -> {
                requireState(before.state(), Set.of("OUTBOUND")); requirePerson(command.personId());
                next = "RECEIVED"; updates.put("receivedBy", command.personId());
                updates.put("receivedAt", requiredTime(command.occurredAt(), "接收时间"));
            }
            case "REPORT" -> {
                requireState(before.state(), Set.of("OUTBOUND", "RECEIVED")); requirePerson(command.personId());
                String type = requiredEnum(command.inspectionType(), EquipmentService.VERIFICATION_TYPES, "检校类型");
                String result = requiredEnum(command.result(), Set.of("PASS", "LIMITED", "FAIL"), "检校结论");
                if (command.inspectionDate() == null || command.validUntil() == null || command.validUntil().isBefore(command.inspectionDate())) {
                    throw invalid("EQUIPMENT_INSPECTION_DATE_INVALID", "检校日期和有效期无效");
                }
                requireFile(command.certificateFileId(), "EQUIPMENT_CERTIFICATE");
                next = "REPORTED"; updates.put("inspectionType", type);
                updates.put("inspectionNumber", required(command.inspectionNumber(), 120, "检校编号"));
                updates.put("inspectionOrg", required(command.inspectionOrganization(), 240, "检校单位"));
                updates.put("inspectionDate", command.inspectionDate()); updates.put("validUntil", command.validUntil());
                updates.put("result", result); updates.put("certificateFile", command.certificateFileId());
                updates.put("filledBy", command.personId()); updates.put("filledAt", OffsetDateTime.now());
            }
            case "CONFIRM" -> {
                requireState(before.state(), Set.of("REPORTED")); requirePerson(command.personId());
                next = "CONFIRMED"; updates.put("confirmedBy", command.personId()); updates.put("confirmedAt", OffsetDateTime.now());
            }
            case "WITHDRAW" -> {
                requireState(before.state(), Set.of("CONFIRMED"));
                required(command.comment(), 1000, "撤回原因");
                next = "REPORTED"; updates.put("clearConfirmation", true);
            }
            case "APPROVE" -> {
                requireState(before.state(), Set.of("CONFIRMED")); requirePerson(command.personId());
                next = "APPROVED"; updates.put("reviewedBy", command.personId()); updates.put("reviewedAt", OffsetDateTime.now());
            }
            case "RETURN" -> {
                requireState(before.state(), Set.of("APPROVED")); requirePerson(command.personId());
                next = "RETURNED"; OffsetDateTime occurred = requiredTime(command.occurredAt(), "入库时间");
                BigDecimal quantity = nonNegative(command.accessoryQuantity(), "入库配件数量");
                updates.put("stockInBy", command.personId()); updates.put("stockInAt", occurred); updates.put("stockInQuantity", quantity);
                EquipmentService.View current = equipment.require(before.equipmentId());
                equipment.updateOperationalState(current.id(), current.version(), EquipmentState.UNDER_INSPECTION,
                        EquipmentState.AVAILABLE, "IN");
                movement(current.id(), "IN", "INSPECTION", quantity, occurred, command.personId(),
                        "EQUIPMENT_INSPECTION", id, "检校返场入库");
            }
            case "VOID" -> {
                requireState(before.state(), Set.of("OUTBOUND", "RECEIVED")); requirePerson(command.personId());
                next = "VOID"; String reason = required(command.comment(), 1000, "撤回原因"); updates.put("voidReason", reason);
                OffsetDateTime occurred = command.occurredAt() == null ? OffsetDateTime.now() : command.occurredAt();
                EquipmentService.View current = equipment.require(before.equipmentId());
                equipment.updateOperationalState(current.id(), current.version(), EquipmentState.UNDER_INSPECTION,
                        EquipmentState.AVAILABLE, "IN");
                movement(current.id(), "IN", "INSPECTION", before.outboundAccessoryQuantity(), occurred, command.personId(),
                        "EQUIPMENT_INSPECTION", id, "送检撤回入库");
            }
            default -> throw invalid("EQUIPMENT_INSPECTION_TRANSITION_INVALID", "送检动作无效");
        }
        updates.put("state", next);
        int changed = inspectionUpdate(updates);
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "送检记录已更新", requireInspection(id).version());
        InspectionView after = requireInspection(id);
        if (action.equals("APPROVE")) {
            equipment.addCertificate(after.equipmentId(), new EquipmentService.CertificateWrite(
                    "CALIBRATION".equals(after.inspectionType()) ? "CALIBRATION" : "VERIFICATION",
                    after.inspectionType(), after.inspectionNumber(), after.inspectionDate(), after.validUntil(),
                    LocalDate.now(), after.result(), after.inspectionOrganization(), after.certificateFileId(),
                    "由送检流程审核生成", 0));
        }
        audit.record("EQUIPMENT_INSPECTION_" + action, "EQUIPMENT_INSPECTION", id, before, after,
                command.reasonCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    public CursorPage<BorrowView> borrows(String query, String beforeCondition, String afterCondition,
                                          LocalDate borrowedFrom, LocalDate borrowedTo, String equipmentCode,
                                          String equipmentName, String model, String borrowerName, String returnerName,
                                          String cursorValue, int limit) {
        PageCursor cursor = PageCursor.decode(cursorValue);
        String cursorClause = cursor == null ? "" : " AND (borrow.updated_at,borrow.id)<(:cursorTime,:cursorId)";
        String fromDate = borrowedFrom == null ? "" : " AND borrow.borrowed_at>=:borrowedFrom";
        String toDate = borrowedTo == null ? "" : " AND borrow.borrowed_at<(:borrowedTo+interval '1 day')";
        String filters = """
                WHERE equipment.organization_id=:organization
                  AND (:query='' OR equipment.code ILIKE :pattern ESCAPE '\\' OR equipment.name ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(equipment.model,'') ILIKE :pattern ESCAPE '\\'
                    OR borrower.name ILIKE :pattern ESCAPE '\\' OR COALESCE(returner.name,'') ILIKE :pattern ESCAPE '\\')
                  AND (:beforeCondition='' OR borrow.before_condition=:beforeCondition)
                  AND (:afterCondition='' OR borrow.after_condition=:afterCondition)
                  AND (:equipmentCode='' OR equipment.code ILIKE :equipmentCodePattern ESCAPE '\\')
                  AND (:equipmentName='' OR equipment.name ILIKE :equipmentNamePattern ESCAPE '\\')
                  AND (:model='' OR COALESCE(equipment.model,'') ILIKE :modelPattern ESCAPE '\\')
                  AND (:borrowerName='' OR borrower.name ILIKE :borrowerNamePattern ESCAPE '\\')
                  AND (:returnerName='' OR COALESCE(returner.name,'') ILIKE :returnerNamePattern ESCAPE '\\')
                """ + fromDate + toDate;
        String from = borrowFrom();
        Map<String, Object> params = new HashMap<>();
        String needle = optionalFilter(query);
        params.put("organization", actor.require().organizationId()); params.put("query", needle); params.put("pattern", pattern(needle));
        params.put("beforeCondition", filterEnum(beforeCondition, Set.of("NORMAL", "ABNORMAL"), "领用状态"));
        params.put("afterCondition", filterEnum(afterCondition, Set.of("NORMAL", "ABNORMAL"), "归还状态"));
        addPattern(params, "equipmentCode", equipmentCode);
        addPattern(params, "equipmentName", equipmentName);
        addPattern(params, "model", model);
        addPattern(params, "borrowerName", borrowerName);
        addPattern(params, "returnerName", returnerName);
        params.put("fetch", limit + 1);
        if (borrowedFrom != null) params.put("borrowedFrom", borrowedFrom);
        if (borrowedTo != null) params.put("borrowedTo", borrowedTo);
        if (cursor != null) { params.put("cursorTime", cursor.updatedAt()); params.put("cursorId", cursor.id()); }
        List<BorrowView> fetched = jdbc.sql(borrowSelect() + from + filters + cursorClause
                + " ORDER BY borrow.updated_at DESC,borrow.id DESC LIMIT :fetch").params(params).query(this::mapBorrow).list();
        return page(fetched, limit, params, "SELECT count(*) " + from + filters, BorrowView::updatedAt, BorrowView::id);
    }

    public BorrowView requireBorrow(UUID id) {
        return withCompanions(jdbc.sql(borrowSelect() + borrowFrom()
                + " WHERE borrow.id=:id AND equipment.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId()).query(this::mapBorrow).optional()
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_BORROW_NOT_FOUND", "领用记录不存在")));
    }

    public BorrowView requireActiveBorrow(UUID equipmentId) {
        UUID id = jdbc.sql("""
                SELECT borrow.id FROM equipment_borrow borrow JOIN equipment ON equipment.id=borrow.equipment_id
                WHERE borrow.equipment_id=:equipment AND equipment.organization_id=:organization
                  AND borrow.state IN ('BORROWED','TRANSFERRED')
                ORDER BY borrow.updated_at DESC LIMIT 1
                """).param("equipment", equipmentId).param("organization", actor.require().organizationId())
                .query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_ACTIVE_BORROW_NOT_FOUND", "该设备没有待归还领用记录"));
        return requireBorrow(id);
    }

    @Transactional
    public BorrowView createBorrow(BorrowCreate command) {
        EquipmentService.View current = usableAvailable(command.equipmentId(), command.expectedVersion());
        requirePerson(command.borrowerId());
        List<UUID> companions = command.companionIds() == null ? List.of()
                : command.companionIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (companions.size() > 20) throw invalid("EQUIPMENT_BORROW_COMPANIONS_EXCEEDED", "同行人员最多20人");
        for (UUID person : companions) requirePerson(person);
        if (command.borrowedAt() == null || command.plannedReturnAt() == null
                || command.plannedReturnAt().isBefore(command.borrowedAt())) {
            throw invalid("EQUIPMENT_BORROW_DATE_INVALID", "计划归还时间不能早于领用时间");
        }
        String borrowedPeriod = requiredEnum(command.borrowedPeriod(), Set.of("AM", "PM"), "领用时段");
        String returnPeriod = requiredEnum(command.plannedReturnPeriod(), Set.of("AM", "PM"), "计划归还时段");
        String condition = requiredEnum(command.beforeCondition(), Set.of("NORMAL", "ABNORMAL"), "设备状态");
        BigDecimal quantity = nonNegative(command.accessoryQuantity(), "领用配件数量");
        requireFile(command.photoFileId(), "EQUIPMENT_BORROW_PHOTO");
        UUID id = UUID.randomUUID();
        equipment.updateOperationalState(current.id(), current.version(), EquipmentState.AVAILABLE, EquipmentState.BORROWED, "OUT");
        jdbc.sql("""
                INSERT INTO equipment_borrow(id,equipment_id,borrower_id,borrowed_at,borrowed_period,
                  planned_return_at,planned_return_period,before_condition,outbound_accessory_quantity,
                  province,city,district,address,destination,photo_file_id,state,remark,created_by)
                VALUES (:id,:equipment,:borrower,:borrowedAt,:borrowedPeriod,:returnAt,:returnPeriod,:condition,
                  :quantity,:province,:city,:district,:address,:destination,:photo,'BORROWED',:remark,:actor)
                """).param("id", id).param("equipment", current.id()).param("borrower", command.borrowerId())
                .param("borrowedAt", command.borrowedAt()).param("borrowedPeriod", borrowedPeriod)
                .param("returnAt", command.plannedReturnAt()).param("returnPeriod", returnPeriod)
                .param("condition", condition).param("quantity", quantity).param("province", optional(command.province(), 80))
                .param("city", optional(command.city(), 80)).param("district", optional(command.district(), 80))
                .param("address", optional(command.address(), 500)).param("destination", optional(command.destination(), 500))
                .param("photo", command.photoFileId()).param("remark", optional(command.remark(), 2000))
                .param("actor", actor.require().id()).update();
        for (UUID companion : companions) jdbc.sql("INSERT INTO equipment_borrow_companion(borrow_id,person_id) VALUES (:borrow,:person)")
                .param("borrow", id).param("person", companion).update();
        movement(current.id(), "OUT", "BORROW_RETURN", quantity, command.borrowedAt(), command.borrowerId(),
                "EQUIPMENT_BORROW", id, "设备领用出库");
        BorrowView created = requireBorrow(id);
        audit.record("EQUIPMENT_BORROW_CREATED", "EQUIPMENT_BORROW", id, null, created, null, null);
        return created;
    }

    @Transactional
    public BorrowView updateBorrow(UUID id, BorrowCreate command) {
        BorrowView before = requireBorrow(id);
        requireVersion(before.version(), command.expectedVersion(), "领用记录已更新");
        requireState(before.state(), Set.of("BORROWED"));
        if (!before.equipmentId().equals(command.equipmentId())) throw invalid("EQUIPMENT_BORROW_EQUIPMENT_IMMUTABLE", "领用后不能更换设备");
        requirePerson(command.borrowerId());
        List<UUID> companions = command.companionIds() == null ? List.of()
                : command.companionIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (companions.size() > 20) throw invalid("EQUIPMENT_BORROW_COMPANIONS_EXCEEDED", "同行人员最多20人");
        for (UUID person : companions) requirePerson(person);
        if (command.borrowedAt() == null || command.plannedReturnAt() == null
                || command.plannedReturnAt().isBefore(command.borrowedAt())) throw invalid("EQUIPMENT_BORROW_DATE_INVALID", "计划归还时间不能早于领用时间");
        requireFile(command.photoFileId(), "EQUIPMENT_BORROW_PHOTO");
        int changed = jdbc.sql("""
                UPDATE equipment_borrow SET borrower_id=:borrower,borrowed_at=:borrowedAt,
                  borrowed_period=:borrowedPeriod,planned_return_at=:returnAt,
                  planned_return_period=:returnPeriod,before_condition=:condition,
                  outbound_accessory_quantity=:quantity,province=:province,city=:city,district=:district,
                  address=:address,destination=:destination,photo_file_id=:photo,remark=:remark,
                  version=version+1,updated_at=now() WHERE id=:id AND version=:version AND state='BORROWED'
                """).param("borrower", command.borrowerId()).param("borrowedAt", command.borrowedAt())
                .param("borrowedPeriod", requiredEnum(command.borrowedPeriod(), Set.of("AM", "PM"), "领用时段"))
                .param("returnAt", command.plannedReturnAt()).param("returnPeriod", requiredEnum(command.plannedReturnPeriod(), Set.of("AM", "PM"), "计划归还时段"))
                .param("condition", requiredEnum(command.beforeCondition(), Set.of("NORMAL", "ABNORMAL"), "设备状态"))
                .param("quantity", nonNegative(command.accessoryQuantity(), "领用配件数量"))
                .param("province", optional(command.province(), 80)).param("city", optional(command.city(), 80))
                .param("district", optional(command.district(), 80)).param("address", optional(command.address(), 500))
                .param("destination", optional(command.destination(), 500)).param("photo", command.photoFileId())
                .param("remark", optional(command.remark(), 2000)).param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "领用记录已更新", requireBorrow(id).version());
        jdbc.sql("DELETE FROM equipment_borrow_companion WHERE borrow_id=:id").param("id", id).update();
        for (UUID companion : companions) jdbc.sql("INSERT INTO equipment_borrow_companion(borrow_id,person_id) VALUES (:borrow,:person)")
                .param("borrow", id).param("person", companion).update();
        BorrowView after = requireBorrow(id);
        audit.record("EQUIPMENT_BORROW_UPDATED", "EQUIPMENT_BORROW", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TransitionResult transitionBorrow(UUID id, BorrowTransition command) {
        BorrowView before = requireBorrow(id);
        requireVersion(before.version(), command.expectedVersion(), "领用记录已更新");
        String action = required(command.action(), 40, "动作").toUpperCase(Locale.ROOT);
        requireState(before.state(), Set.of("BORROWED", "TRANSFERRED"));
        requirePerson(command.personId());
        String next;
        if (action.equals("RETURN")) {
            next = "RETURNED";
            OffsetDateTime occurred = requiredTime(command.occurredAt(), "归还时间");
            String period = requiredEnum(command.period(), Set.of("AM", "PM"), "归还时段");
            String condition = requiredEnum(command.condition(), Set.of("NORMAL", "ABNORMAL"), "归还后状态");
            BigDecimal quantity = nonNegative(command.accessoryQuantity(), "归还配件数量");
            int changed = jdbc.sql("""
                    UPDATE equipment_borrow SET returned_by=:person,returned_at=:occurred,return_period=:period,
                      after_condition=:condition,returned_accessory_quantity=:quantity,state='RETURNED',
                      version=version+1,updated_at=now() WHERE id=:id AND version=:version
                    """).param("person", command.personId()).param("occurred", occurred).param("period", period)
                    .param("condition", condition).param("quantity", quantity).param("id", id).param("version", before.version()).update();
            if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "领用记录已更新", requireBorrow(id).version());
            EquipmentService.View current = equipment.require(before.equipmentId());
            equipment.updateOperationalState(current.id(), current.version(), EquipmentState.BORROWED, EquipmentState.AVAILABLE, "IN");
            movement(current.id(), "IN", "BORROW_RETURN", quantity, occurred, command.personId(),
                    "EQUIPMENT_BORROW", id, "设备归还入库");
        } else if (action.equals("TRANSFER")) {
            next = "TRANSFERRED";
            String reason = required(command.comment(), 1000, "移交原因");
            OffsetDateTime occurred = requiredTime(command.occurredAt(), "移交时间");
            int changed = jdbc.sql("""
                    UPDATE equipment_borrow SET transferred_to=:person,transferred_at=:occurred,transfer_reason=:reason,
                      state='TRANSFERRED',version=version+1,updated_at=now() WHERE id=:id AND version=:version
                    """).param("person", command.personId()).param("occurred", occurred).param("reason", reason)
                    .param("id", id).param("version", before.version()).update();
            if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "领用记录已更新", requireBorrow(id).version());
        } else if (action.equals("VOID")) {
            next = "VOID";
            String reason = required(command.comment(), 1000, "作废原因");
            OffsetDateTime occurred = command.occurredAt() == null ? OffsetDateTime.now() : command.occurredAt();
            int changed = jdbc.sql("""
                    UPDATE equipment_borrow SET void_reason=:reason,state='VOID',version=version+1,updated_at=now()
                    WHERE id=:id AND version=:version
                    """).param("reason", reason).param("id", id).param("version", before.version()).update();
            if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "领用记录已更新", requireBorrow(id).version());
            EquipmentService.View current = equipment.require(before.equipmentId());
            equipment.updateOperationalState(current.id(), current.version(), EquipmentState.BORROWED, EquipmentState.AVAILABLE, "IN");
            movement(current.id(), "IN", "BORROW_RETURN", before.outboundAccessoryQuantity(), occurred, command.personId(),
                    "EQUIPMENT_BORROW", id, "领用记录作废入库");
        } else throw invalid("EQUIPMENT_BORROW_TRANSITION_INVALID", "领用动作无效");
        BorrowView after = requireBorrow(id);
        audit.record("EQUIPMENT_BORROW_" + action, "EQUIPMENT_BORROW", id, before, after,
                command.reasonCode(), command.comment());
        return new TransitionResult(next, after.version(), after.allowedActions());
    }

    public CursorPage<UsageView> usages(String query, String beforeCondition, String afterCondition,
                                        String equipmentType, LocalDate startedFrom, LocalDate startedTo,
                                        String equipmentCode, String equipmentName, String model,
                                        String commissionNumber,
                                        String cursorValue, int limit) {
        PageCursor cursor = PageCursor.decode(cursorValue);
        String cursorClause = cursor == null ? "" : " AND (usage.updated_at,usage.id)<(:cursorTime,:cursorId)";
        String fromDate = startedFrom == null ? "" : " AND usage.started_at>=:startedFrom";
        String toDate = startedTo == null ? "" : " AND usage.started_at<(:startedTo+interval '1 day')";
        String filters = """
                WHERE equipment.organization_id=:organization
                  AND (:query='' OR equipment.code ILIKE :pattern ESCAPE '\\' OR equipment.name ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(equipment.model,'') ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(usage.sample_name,'') ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(usage.commission_number,'') ILIKE :pattern ESCAPE '\\')
                  AND (:beforeCondition='' OR usage.before_condition=:beforeCondition)
                  AND (:afterCondition='' OR usage.after_condition=:afterCondition)
                  AND (:equipmentType='' OR equipment.equipment_type=:equipmentType)
                  AND (:equipmentCode='' OR equipment.code ILIKE :equipmentCodePattern ESCAPE '\\')
                  AND (:equipmentName='' OR equipment.name ILIKE :equipmentNamePattern ESCAPE '\\')
                  AND (:model='' OR COALESCE(equipment.model,'') ILIKE :modelPattern ESCAPE '\\')
                  AND (:commissionNumber='' OR COALESCE(usage.commission_number,'')
                    ILIKE :commissionNumberPattern ESCAPE '\\')
                """ + fromDate + toDate;
        String from = usageFrom();
        Map<String, Object> params = new HashMap<>();
        String needle = optionalFilter(query);
        params.put("organization", actor.require().organizationId()); params.put("query", needle); params.put("pattern", pattern(needle));
        params.put("beforeCondition", filterEnum(beforeCondition, Set.of("NORMAL", "ABNORMAL"), "使用前状态"));
        params.put("afterCondition", filterEnum(afterCondition, Set.of("NORMAL", "ABNORMAL"), "使用后状态"));
        params.put("equipmentType", filterEnum(equipmentType, EquipmentService.EQUIPMENT_TYPES, "设备类型")); params.put("fetch", limit + 1);
        addPattern(params, "equipmentCode", equipmentCode);
        addPattern(params, "equipmentName", equipmentName);
        addPattern(params, "model", model);
        addPattern(params, "commissionNumber", commissionNumber);
        if (startedFrom != null) params.put("startedFrom", startedFrom);
        if (startedTo != null) params.put("startedTo", startedTo);
        if (cursor != null) { params.put("cursorTime", cursor.updatedAt()); params.put("cursorId", cursor.id()); }
        List<UsageView> fetched = jdbc.sql(usageSelect() + from + filters + cursorClause
                + " ORDER BY usage.updated_at DESC,usage.id DESC LIMIT :fetch").params(params).query(this::mapUsage).list();
        return page(fetched, limit, params, "SELECT count(*) " + from + filters, UsageView::updatedAt, UsageView::id);
    }

    public UsageView requireUsage(UUID id) {
        return jdbc.sql(usageSelect() + usageFrom() + " WHERE usage.id=:id AND equipment.organization_id=:organization")
                .param("id", id).param("organization", actor.require().organizationId()).query(this::mapUsage).optional()
                .orElseThrow(() -> ApiException.notFound("EQUIPMENT_USAGE_NOT_FOUND", "使用记录不存在"));
    }

    @Transactional
    public void recordCompletedTestUsage(UUID taskId, UUID equipmentId, UUID usedBy) {
        UUID organizationId = actor.require().organizationId();
        TestUsageSource source = jdbc.sql("""
                SELECT sample.name sample_name, commission.number commission_number,
                  COALESCE(task.started_at, now()) started_at, equipment.location
                FROM test_task task
                  JOIN sample ON sample.id=task.sample_id
                  JOIN commission ON commission.id=task.commission_id
                  JOIN equipment ON equipment.id=:equipment
                WHERE task.id=:task AND commission.organization_id=:organization
                  AND equipment.organization_id=:organization
                """).param("task", taskId).param("equipment", equipmentId)
                .param("organization", organizationId)
                .query((rs, row) -> new TestUsageSource(rs.getString("sample_name"),
                        rs.getString("commission_number"), rs.getObject("started_at", OffsetDateTime.class),
                        rs.getString("location"))).optional()
                .orElseThrow(() -> invalid("EQUIPMENT_USAGE_SOURCE_INVALID", "试验任务或检测设备不存在"));
        OffsetDateTime endedAt = OffsetDateTime.now();
        OffsetDateTime startedAt = source.startedAt().isAfter(endedAt) ? endedAt : source.startedAt();
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO equipment_usage_record(id,equipment_id,task_id,sample_name,commission_number,used_by,
                  started_at,ended_at,location,before_condition,after_condition,state,remark,created_by)
                VALUES (:id,:equipment,:task,:sample,:commission,:person,:started,:ended,:location,
                  'NORMAL','NORMAL','COMPLETED','由试验记录提交自动生成',:actor)
                ON CONFLICT (task_id) WHERE task_id IS NOT NULL DO NOTHING
                """).param("id", id).param("equipment", equipmentId).param("task", taskId)
                .param("sample", source.sampleName()).param("commission", source.commissionNumber())
                .param("person", usedBy).param("started", startedAt).param("ended", endedAt)
                .param("location", source.location()).param("actor", actor.require().id()).update();
        if (inserted == 1) {
            UsageView created = requireUsage(id);
            audit.record("EQUIPMENT_USAGE_CREATED_FROM_TEST", "EQUIPMENT_USAGE", id,
                    null, created, null, "试验记录提交");
        }
    }

    @Transactional
    public UsageView createUsage(UsageCreate command) {
        EquipmentService.View current = usableAvailable(command.equipmentId(), command.expectedVersion());
        if (!equipment.certificateValid(current.id())) {
            throw ApiException.blocked("EQUIPMENT_CERTIFICATE_INVALID", "设备证书已过期或结论不合格", current.version(),
                    List.of("开始使用前必须存在当前有效的检定或校准证书"));
        }
        requirePerson(command.usedBy());
        if (command.taskId() != null) {
            boolean taskExists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM test_task WHERE id=:id)")
                    .param("id", command.taskId()).query(Boolean.class).single();
            if (!taskExists) throw invalid("EQUIPMENT_USAGE_TASK_INVALID", "关联任务不存在");
        }
        OffsetDateTime started = command.startedAt() == null ? OffsetDateTime.now() : command.startedAt();
        String condition = requiredEnum(command.beforeCondition(), Set.of("NORMAL", "ABNORMAL"), "使用前状态");
        if (command.humidity() != null && (command.humidity().signum() < 0 || command.humidity().compareTo(new BigDecimal("100")) > 0)) {
            throw invalid("EQUIPMENT_USAGE_HUMIDITY_INVALID", "湿度必须介于0至100");
        }
        UUID id = UUID.randomUUID();
        equipment.updateOperationalState(current.id(), current.version(), EquipmentState.AVAILABLE, EquipmentState.IN_USE, "IN");
        jdbc.sql("""
                INSERT INTO equipment_usage_record(id,equipment_id,task_id,sample_name,commission_number,used_by,
                  started_at,temperature,humidity,location,before_condition,state,remark,created_by)
                VALUES (:id,:equipment,:task,:sample,:commission,:person,:started,:temperature,:humidity,:location,
                  :condition,'IN_PROGRESS',:remark,:actor)
                """).param("id", id).param("equipment", current.id()).param("task", command.taskId())
                .param("sample", optional(command.sampleName(), 240)).param("commission", optional(command.commissionNumber(), 80))
                .param("person", command.usedBy()).param("started", started).param("temperature", command.temperature())
                .param("humidity", command.humidity()).param("location", optional(command.location(), 240))
                .param("condition", condition).param("remark", optional(command.remark(), 2000))
                .param("actor", actor.require().id()).update();
        UsageView created = requireUsage(id);
        audit.record("EQUIPMENT_USAGE_CREATED", "EQUIPMENT_USAGE", id, null, created, null, null);
        return created;
    }

    @Transactional
    public TransitionResult transitionUsage(UUID id, UsageTransition command) {
        UsageView before = requireUsage(id);
        requireVersion(before.version(), command.expectedVersion(), "使用记录已更新");
        requireState(before.state(), Set.of("IN_PROGRESS"));
        String action = required(command.action(), 40, "动作").toUpperCase(Locale.ROOT);
        String next;
        OffsetDateTime ended = command.endedAt() == null ? OffsetDateTime.now() : command.endedAt();
        if (ended.isBefore(before.startedAt())) throw invalid("EQUIPMENT_USAGE_DATE_INVALID", "结束时间不能早于开始时间");
        if (action.equals("COMPLETE")) next = "COMPLETED";
        else if (action.equals("VOID")) { next = "VOID"; required(command.comment(), 1000, "作废原因"); }
        else throw invalid("EQUIPMENT_USAGE_TRANSITION_INVALID", "使用记录动作无效");
        String condition = action.equals("COMPLETE")
                ? requiredEnum(command.afterCondition(), Set.of("NORMAL", "ABNORMAL"), "使用后状态") : null;
        int changed = jdbc.sql("""
                UPDATE equipment_usage_record SET ended_at=:ended,after_condition=:condition,state=:state,
                  remark=CASE WHEN :comment IS NULL THEN remark ELSE concat_ws(E'\n',remark,:comment) END,
                  version=version+1,updated_at=now() WHERE id=:id AND version=:version
                """).param("ended", ended).param("condition", condition).param("state", next)
                .param("comment", optional(command.comment(), 1000)).param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "使用记录已更新", requireUsage(id).version());
        EquipmentService.View current = equipment.require(before.equipmentId());
        equipment.updateOperationalState(current.id(), current.version(), EquipmentState.IN_USE, EquipmentState.AVAILABLE, "IN");
        UsageView after = requireUsage(id);
        audit.record("EQUIPMENT_USAGE_" + action, "EQUIPMENT_USAGE", id, before, after,
                command.reasonCode(), command.comment());
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    public CursorPage<MovementView> movements(String query, String operationType, String movementType,
                                              LocalDate occurredFrom, LocalDate occurredTo,
                                              String equipmentCode, String equipmentName, String model,
                                              String personName,
                                              String cursorValue, int limit) {
        PageCursor cursor = PageCursor.decode(cursorValue);
        String cursorClause = cursor == null ? "" : " AND (movement.occurred_at,movement.id)<(:cursorTime,:cursorId)";
        String fromDate = occurredFrom == null ? "" : " AND movement.occurred_at>=:occurredFrom";
        String toDate = occurredTo == null ? "" : " AND movement.occurred_at<(:occurredTo+interval '1 day')";
        String filters = """
                WHERE equipment.organization_id=:organization
                  AND (:query='' OR equipment.code ILIKE :pattern ESCAPE '\\' OR equipment.name ILIKE :pattern ESCAPE '\\'
                    OR COALESCE(equipment.model,'') ILIKE :pattern ESCAPE '\\' OR person.name ILIKE :pattern ESCAPE '\\')
                  AND (:operation='' OR movement.operation_type=:operation)
                  AND (:movementType='' OR movement.movement_type=:movementType)
                  AND (:equipmentCode='' OR equipment.code ILIKE :equipmentCodePattern ESCAPE '\\')
                  AND (:equipmentName='' OR equipment.name ILIKE :equipmentNamePattern ESCAPE '\\')
                  AND (:model='' OR COALESCE(equipment.model,'') ILIKE :modelPattern ESCAPE '\\')
                  AND (:personName='' OR person.name ILIKE :personNamePattern ESCAPE '\\')
                """ + fromDate + toDate;
        String from = """
                FROM equipment_movement movement JOIN equipment ON equipment.id=movement.equipment_id
                JOIN person ON person.id=movement.person_id
                """;
        String select = """
                SELECT movement.id,movement.equipment_id,equipment.code equipment_code,equipment.name equipment_name,
                  equipment.model,movement.operation_type,movement.movement_type,movement.accessory_quantity,
                  movement.occurred_at,movement.person_id,person.name person_name,movement.reference_type,
                  movement.reference_id,movement.remark
                """;
        Map<String, Object> params = new HashMap<>(); String needle = optionalFilter(query);
        params.put("organization", actor.require().organizationId()); params.put("query", needle); params.put("pattern", pattern(needle));
        params.put("operation", filterEnum(operationType, Set.of("IN", "OUT"), "操作类型"));
        params.put("movementType", filterEnum(movementType, Set.of("BORROW_RETURN", "INSPECTION", "INVENTORY", "MAINTENANCE", "OTHER"), "出入库类型"));
        addPattern(params, "equipmentCode", equipmentCode);
        addPattern(params, "equipmentName", equipmentName);
        addPattern(params, "model", model);
        addPattern(params, "personName", personName);
        params.put("fetch", limit + 1);
        if (occurredFrom != null) params.put("occurredFrom", occurredFrom);
        if (occurredTo != null) params.put("occurredTo", occurredTo);
        if (cursor != null) { params.put("cursorTime", cursor.updatedAt()); params.put("cursorId", cursor.id()); }
        List<MovementView> fetched = jdbc.sql(select + from + filters + cursorClause
                + " ORDER BY movement.occurred_at DESC,movement.id DESC LIMIT :fetch")
                .params(params).query(this::mapMovement).list();
        return page(fetched, limit, params, "SELECT count(*) " + from + filters, MovementView::occurredAt, MovementView::id);
    }

    private int inspectionUpdate(Map<String, Object> values) {
        for (String key : List.of("receivedBy", "receivedAt", "inspectionType", "inspectionNumber", "inspectionOrg",
                "inspectionDate", "validUntil", "result", "certificateFile", "filledBy", "filledAt",
                "confirmedBy", "confirmedAt", "reviewedBy", "reviewedAt", "stockInBy", "stockInAt",
                "stockInQuantity", "voidReason")) values.putIfAbsent(key, null);
        values.putIfAbsent("clearConfirmation", false);
        return jdbc.sql("""
                UPDATE equipment_inspection SET state=:state,
                  received_by=COALESCE(:receivedBy,received_by),received_at=COALESCE(:receivedAt,received_at),
                  inspection_type=COALESCE(:inspectionType,inspection_type),
                  inspection_number=COALESCE(:inspectionNumber,inspection_number),
                  inspection_organization=COALESCE(:inspectionOrg,inspection_organization),
                  inspection_date=COALESCE(:inspectionDate,inspection_date),valid_until=COALESCE(:validUntil,valid_until),
                  result=COALESCE(:result,result),certificate_file_id=COALESCE(:certificateFile,certificate_file_id),
                  filled_by=COALESCE(:filledBy,filled_by),filled_at=COALESCE(:filledAt,filled_at),
                  confirmed_by=CASE WHEN CAST(:clearConfirmation AS boolean) THEN NULL ELSE COALESCE(:confirmedBy,confirmed_by) END,
                  confirmed_at=CASE WHEN CAST(:clearConfirmation AS boolean) THEN NULL ELSE COALESCE(:confirmedAt,confirmed_at) END,
                  reviewed_by=COALESCE(:reviewedBy,reviewed_by),reviewed_at=COALESCE(:reviewedAt,reviewed_at),
                  stock_in_by=COALESCE(:stockInBy,stock_in_by),stock_in_at=COALESCE(:stockInAt,stock_in_at),
                  stock_in_accessory_quantity=COALESCE(:stockInQuantity,stock_in_accessory_quantity),
                  void_reason=COALESCE(:voidReason,void_reason),version=version+1,updated_at=now()
                WHERE id=:id AND version=:version
                """).params(values).update();
    }

    private String inspectionSelect() {
        return """
                SELECT inspection.id,inspection.equipment_id,equipment.code equipment_code,equipment.name equipment_name,
                  equipment.model,inspection.outbound_person_id,outbound.name outbound_person_name,
                  inspection.outbound_at,inspection.outbound_accessory_quantity,inspection.destination,
                  inspection.photo_file_id,inspection.attachment_file_id,inspection.received_by,receiver.name received_by_name,
                  inspection.received_at,inspection.inspection_type,inspection.inspection_number,
                  inspection.inspection_organization,inspection.inspection_date,inspection.valid_until,inspection.result,
                  inspection.certificate_file_id,inspection.filled_by,filler.name filled_by_name,inspection.filled_at,
                  inspection.confirmed_by,confirmer.name confirmed_by_name,inspection.confirmed_at,
                  inspection.reviewed_by,reviewer.name reviewed_by_name,inspection.reviewed_at,
                  inspection.stock_in_by,stock_person.name stock_in_by_name,inspection.stock_in_at,
                  inspection.stock_in_accessory_quantity,inspection.state,inspection.remark,inspection.void_reason,
                  inspection.version,inspection.created_at,inspection.updated_at
                """;
    }

    private String inspectionFrom() {
        return """
                FROM equipment_inspection inspection JOIN equipment ON equipment.id=inspection.equipment_id
                JOIN person outbound ON outbound.id=inspection.outbound_person_id
                LEFT JOIN person receiver ON receiver.id=inspection.received_by
                LEFT JOIN person filler ON filler.id=inspection.filled_by
                LEFT JOIN person confirmer ON confirmer.id=inspection.confirmed_by
                LEFT JOIN person reviewer ON reviewer.id=inspection.reviewed_by
                LEFT JOIN person stock_person ON stock_person.id=inspection.stock_in_by
                """;
    }

    private String borrowSelect() {
        return """
                SELECT borrow.id,borrow.equipment_id,equipment.code equipment_code,equipment.name equipment_name,
                  equipment.model,borrow.borrower_id,borrower.name borrower_name,borrow.borrowed_at,
                  borrow.borrowed_period,borrow.planned_return_at,borrow.planned_return_period,borrow.before_condition,
                  borrow.outbound_accessory_quantity,borrow.province,borrow.city,borrow.district,borrow.address,
                  borrow.destination,borrow.photo_file_id,borrow.returned_by,returner.name returned_by_name,
                  borrow.returned_at,borrow.return_period,borrow.after_condition,borrow.returned_accessory_quantity,
                  borrow.transferred_to,transferee.name transferred_to_name,borrow.transferred_at,borrow.transfer_reason,
                  borrow.state,borrow.remark,borrow.void_reason,borrow.version,borrow.created_at,borrow.updated_at
                """;
    }

    private String borrowFrom() {
        return """
                FROM equipment_borrow borrow JOIN equipment ON equipment.id=borrow.equipment_id
                JOIN person borrower ON borrower.id=borrow.borrower_id
                LEFT JOIN person returner ON returner.id=borrow.returned_by
                LEFT JOIN person transferee ON transferee.id=borrow.transferred_to
                """;
    }

    private String usageSelect() {
        return """
                SELECT usage.id,usage.equipment_id,equipment.code equipment_code,equipment.name equipment_name,
                  equipment.model,equipment.equipment_type,usage.task_id,usage.sample_name,usage.commission_number,usage.used_by,
                  person.name used_by_name,usage.started_at,usage.ended_at,usage.temperature,usage.humidity,
                  usage.location,usage.before_condition,usage.after_condition,usage.state,usage.remark,
                  usage.version,usage.created_at,usage.updated_at
                """;
    }

    private String usageFrom() {
        return """
                FROM equipment_usage_record usage JOIN equipment ON equipment.id=usage.equipment_id
                JOIN person ON person.id=usage.used_by
                """;
    }

    private InspectionView mapInspection(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new InspectionView(rs.getObject("id", UUID.class), rs.getObject("equipment_id", UUID.class),
                rs.getString("equipment_code"), rs.getString("equipment_name"), rs.getString("model"),
                rs.getObject("outbound_person_id", UUID.class), rs.getString("outbound_person_name"),
                rs.getObject("outbound_at", OffsetDateTime.class), rs.getBigDecimal("outbound_accessory_quantity"),
                rs.getString("destination"), rs.getObject("photo_file_id", UUID.class),
                rs.getObject("attachment_file_id", UUID.class), rs.getObject("received_by", UUID.class),
                rs.getString("received_by_name"), rs.getObject("received_at", OffsetDateTime.class),
                rs.getString("inspection_type"), rs.getString("inspection_number"), rs.getString("inspection_organization"),
                rs.getObject("inspection_date", LocalDate.class), rs.getObject("valid_until", LocalDate.class),
                rs.getString("result"), rs.getObject("certificate_file_id", UUID.class),
                rs.getObject("filled_by", UUID.class), rs.getString("filled_by_name"), rs.getObject("filled_at", OffsetDateTime.class),
                rs.getObject("confirmed_by", UUID.class), rs.getString("confirmed_by_name"), rs.getObject("confirmed_at", OffsetDateTime.class),
                rs.getObject("reviewed_by", UUID.class), rs.getString("reviewed_by_name"), rs.getObject("reviewed_at", OffsetDateTime.class),
                rs.getObject("stock_in_by", UUID.class), rs.getString("stock_in_by_name"), rs.getObject("stock_in_at", OffsetDateTime.class),
                rs.getBigDecimal("stock_in_accessory_quantity"), state, rs.getString("remark"), rs.getString("void_reason"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), inspectionActions(state));
    }

    private BorrowView mapBorrow(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new BorrowView(rs.getObject("id", UUID.class), rs.getObject("equipment_id", UUID.class),
                rs.getString("equipment_code"), rs.getString("equipment_name"), rs.getString("model"),
                rs.getObject("borrower_id", UUID.class), rs.getString("borrower_name"), List.of(),
                rs.getObject("borrowed_at", OffsetDateTime.class), rs.getString("borrowed_period"),
                rs.getObject("planned_return_at", OffsetDateTime.class), rs.getString("planned_return_period"),
                rs.getString("before_condition"), rs.getBigDecimal("outbound_accessory_quantity"),
                rs.getString("province"), rs.getString("city"), rs.getString("district"), rs.getString("address"),
                rs.getString("destination"), rs.getObject("photo_file_id", UUID.class),
                rs.getObject("returned_by", UUID.class), rs.getString("returned_by_name"),
                rs.getObject("returned_at", OffsetDateTime.class), rs.getString("return_period"),
                rs.getString("after_condition"), rs.getBigDecimal("returned_accessory_quantity"),
                rs.getObject("transferred_to", UUID.class), rs.getString("transferred_to_name"),
                rs.getObject("transferred_at", OffsetDateTime.class), rs.getString("transfer_reason"), state,
                rs.getString("remark"), rs.getString("void_reason"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                borrowActions(state));
    }

    private BorrowView withCompanions(BorrowView value) {
        List<PersonOption> companions = jdbc.sql("""
                SELECT person.id,person.name FROM equipment_borrow_companion companion
                JOIN person ON person.id=companion.person_id WHERE companion.borrow_id=:id ORDER BY person.name
                """).param("id", value.id()).query((rs, row) -> new PersonOption(rs.getObject("id", UUID.class), rs.getString("name"))).list();
        return new BorrowView(value.id(), value.equipmentId(), value.equipmentCode(), value.equipmentName(), value.model(),
                value.borrowerId(), value.borrowerName(), companions, value.borrowedAt(), value.borrowedPeriod(),
                value.plannedReturnAt(), value.plannedReturnPeriod(), value.beforeCondition(), value.outboundAccessoryQuantity(),
                value.province(), value.city(), value.district(), value.address(), value.destination(), value.photoFileId(),
                value.returnedBy(), value.returnedByName(), value.returnedAt(), value.returnPeriod(), value.afterCondition(),
                value.returnedAccessoryQuantity(), value.transferredTo(), value.transferredToName(), value.transferredAt(),
                value.transferReason(), value.state(), value.remark(), value.voidReason(), value.version(),
                value.createdAt(), value.updatedAt(), value.allowedActions());
    }

    private UsageView mapUsage(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new UsageView(rs.getObject("id", UUID.class), rs.getObject("equipment_id", UUID.class),
                rs.getString("equipment_code"), rs.getString("equipment_name"), rs.getString("model"),
                rs.getString("equipment_type"), rs.getObject("task_id", UUID.class), rs.getString("sample_name"), rs.getString("commission_number"),
                rs.getObject("used_by", UUID.class), rs.getString("used_by_name"),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("ended_at", OffsetDateTime.class),
                rs.getBigDecimal("temperature"), rs.getBigDecimal("humidity"), rs.getString("location"),
                rs.getString("before_condition"), rs.getString("after_condition"), state, rs.getString("remark"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), state.equals("IN_PROGRESS") ? List.of("COMPLETE", "VOID") : List.of());
    }

    private MovementView mapMovement(ResultSet rs, int row) throws SQLException {
        return new MovementView(rs.getObject("id", UUID.class), rs.getObject("equipment_id", UUID.class),
                rs.getString("equipment_code"), rs.getString("equipment_name"), rs.getString("model"),
                rs.getString("operation_type"), rs.getString("movement_type"), rs.getBigDecimal("accessory_quantity"),
                rs.getObject("occurred_at", OffsetDateTime.class), rs.getObject("person_id", UUID.class),
                rs.getString("person_name"), rs.getString("reference_type"), rs.getObject("reference_id", UUID.class),
                rs.getString("remark"));
    }

    private EquipmentService.View usableAvailable(UUID id, long expectedVersion) {
        EquipmentService.View current = equipment.require(id);
        requireVersion(current.version(), expectedVersion, "设备已更新");
        if (!current.approvalState().equals("APPROVED") || !current.enabled()
                || current.performanceStatus().equals("ABNORMAL") || current.state() != EquipmentState.AVAILABLE
                || !current.inventoryStatus().equals("IN")) {
            throw ApiException.blocked("EQUIPMENT_OPERATION_BLOCKED", "设备当前不可用", current.version(),
                    List.of("设备必须已审批、已启用、性能正常、在库且未被占用"));
        }
        return current;
    }

    private void movement(UUID equipmentId, String operation, String type, BigDecimal quantity,
                          OffsetDateTime occurredAt, UUID personId, String referenceType, UUID referenceId,
                          String remark) {
        jdbc.sql("""
                INSERT INTO equipment_movement(id,equipment_id,operation_type,movement_type,accessory_quantity,
                  occurred_at,person_id,reference_type,reference_id,remark,created_by)
                VALUES (:id,:equipment,:operation,:type,:quantity,:occurred,:person,:referenceType,:referenceId,:remark,:actor)
                """).param("id", UUID.randomUUID()).param("equipment", equipmentId).param("operation", operation)
                .param("type", type).param("quantity", quantity).param("occurred", occurredAt).param("person", personId)
                .param("referenceType", referenceType).param("referenceId", referenceId).param("remark", remark)
                .param("actor", actor.require().id()).update();
    }

    private void requirePerson(UUID id) {
        if (id == null) throw invalid("EQUIPMENT_PERSON_REQUIRED", "请选择人员");
        boolean exists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM person p JOIN department d ON d.id=p.department_id
                  WHERE p.id=:id AND d.organization_id=:organization AND p.active)
                """).param("id", id).param("organization", actor.require().organizationId()).query(Boolean.class).single();
        if (!exists) throw invalid("EQUIPMENT_PERSON_INVALID", "人员不存在、已停用或不在当前机构");
    }

    private void requireFile(UUID id, String purpose) {
        if (id == null) return;
        StoredFileService.View file = files.require(id);
        if (!file.purpose().equals(purpose)) throw invalid("EQUIPMENT_FILE_PURPOSE_INVALID", "附件上传入口不匹配");
    }

    private <T> CursorPage<T> page(List<T> fetched, int limit, Map<String, Object> params, String countSql,
                                   java.util.function.Function<T, OffsetDateTime> time,
                                   java.util.function.Function<T, UUID> id) {
        boolean hasMore = fetched.size() > limit;
        List<T> items = hasMore ? fetched.subList(0, limit) : fetched;
        Map<String, Object> countParams = new HashMap<>(params);
        countParams.remove("fetch"); countParams.remove("cursorTime"); countParams.remove("cursorId");
        long total = jdbc.sql(countSql).params(countParams).query(Long.class).single();
        String next = hasMore ? new PageCursor(time.apply(items.getLast()), id.apply(items.getLast())).encode() : null;
        return new CursorPage<>(List.copyOf(items), next, hasMore, total);
    }

    private Map<String, Object> pageParams(String query, String states, String result, PageCursor cursor, int limit) {
        Map<String, Object> params = new HashMap<>();
        String needle = optionalFilter(query);
        params.put("organization", actor.require().organizationId()); params.put("query", needle); params.put("pattern", pattern(needle));
        params.put("states", stateCsv(states));
        params.put("result", filterEnum(result, Set.of("PASS", "LIMITED", "FAIL"), "检校结论")); params.put("fetch", limit + 1);
        if (cursor != null) { params.put("cursorTime", cursor.updatedAt()); params.put("cursorId", cursor.id()); }
        return params;
    }

    private BigDecimal nonNegative(BigDecimal value, String label) {
        BigDecimal result = value == null ? BigDecimal.ZERO : value;
        if (result.signum() < 0) throw invalid("EQUIPMENT_QUANTITY_INVALID", label + "不能为负数");
        return result;
    }
    private OffsetDateTime requiredTime(OffsetDateTime value, String label) {
        if (value == null) throw invalid("EQUIPMENT_DATE_REQUIRED", label + "不能为空");
        return value;
    }
    private void requireVersion(long current, long expected, String message) {
        if (current != expected) throw ApiException.conflict("VERSION_CONFLICT", message, current);
    }
    private void requireState(String state, Set<String> allowed) {
        if (!allowed.contains(state)) throw new ApiException(HttpStatus.CONFLICT, "EQUIPMENT_OPERATION_TRANSITION_INVALID", "当前记录状态不允许该动作");
    }
    private String requiredEnum(String value, Set<String> allowed, String label) {
        String normalized = required(value, 80, label).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw invalid("EQUIPMENT_ENUM_INVALID", label + "无效");
        return normalized;
    }
    private String filterEnum(String value, Set<String> allowed, String label) {
        return value == null || value.isBlank() ? "" : requiredEnum(value, allowed, label);
    }
    private String required(String value, int max, String label) {
        if (value == null || value.isBlank()) throw invalid("EQUIPMENT_FIELD_REQUIRED", label + "不能为空");
        String result = value.trim();
        if (result.length() > max) throw invalid("EQUIPMENT_FIELD_TOO_LONG", label + "超过最大长度");
        return result;
    }
    private String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > max) throw invalid("EQUIPMENT_FIELD_TOO_LONG", "字段超过最大长度");
        return result;
    }
    private String optionalFilter(String value) { return value == null ? "" : value.trim(); }
    private void addPattern(Map<String, Object> params, String key, String value) {
        String text = optionalFilter(value);
        params.put(key, text); params.put(key + "Pattern", pattern(text));
    }
    private String stateCsv(String value) {
        if (value == null || value.isBlank()) return "";
        Set<String> allowed = Set.of("OUTBOUND", "RECEIVED", "REPORTED", "CONFIRMED", "APPROVED", "RETURNED", "VOID");
        List<String> states = java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(part -> !part.isEmpty()).map(part -> part.toUpperCase(Locale.ROOT)).distinct().toList();
        if (states.isEmpty() || !allowed.containsAll(states)) throw invalid("EQUIPMENT_ENUM_INVALID", "送检状态无效");
        return String.join(",", states);
    }
    private String pattern(String value) {
        String text = optionalFilter(value);
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
    private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }

    private static List<String> inspectionActions(String state) {
        return switch (state) {
            case "OUTBOUND" -> List.of("RECEIVE", "REPORT", "VOID");
            case "RECEIVED" -> List.of("REPORT", "VOID");
            case "REPORTED" -> List.of("CONFIRM");
            case "CONFIRMED" -> List.of("WITHDRAW", "APPROVE");
            case "APPROVED" -> List.of("RETURN");
            default -> List.of();
        };
    }
    private static List<String> borrowActions(String state) {
        return Set.of("BORROWED", "TRANSFERRED").contains(state) ? List.of("RETURN", "TRANSFER", "VOID") : List.of();
    }
}
