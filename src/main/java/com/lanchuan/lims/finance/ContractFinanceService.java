package com.lanchuan.lims.finance;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.common.PageCursor;
import com.lanchuan.lims.common.TransitionResult;
import com.lanchuan.lims.identity.AuthenticatedUser;
import com.lanchuan.lims.identity.CurrentActor;
import com.lanchuan.lims.identity.DataScopeService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ContractFinanceService {
    private static final Set<String> CONTRACT_TYPES = Set.of(
            "TESTING_SERVICE", "TECHNICAL_SERVICE", "FRAMEWORK", "SUBCONTRACT", "OTHER");
    private static final Set<String> PRICING_METHODS = Set.of("UNIT_PRICE", "FIXED_PRICE", "NEGOTIATED");
    private static final Set<String> PAYMENT_METHODS = Set.of(
            "BANK_TRANSFER", "CASH", "CHECK", "INTERNAL_OFFSET", "OTHER");
    private static final Set<String> ENTRY_TYPES = Set.of(
            "TARGET_DELIVERY", "PRICE_ADJUSTMENT", "SETTLEMENT", "OTHER_FEE", "INVOICE");
    private static final Set<String> EXPORT_TYPES = Set.of("CONTRACT", "FEE_STATEMENT", "RECEIPT", "PAYMENT");
    private static final UUID NO_ID = new UUID(0, 0);

    private final JdbcClient jdbc;
    private final CurrentActor actor;
    private final DataScopeService scope;
    private final AuditService audit;
    private final ObjectMapper json;

    public ContractFinanceService(JdbcClient jdbc, CurrentActor actor, DataScopeService scope, AuditService audit,
                                  ObjectMapper json) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.scope = scope;
        this.audit = audit;
        this.json = json;
    }

    public record ContractWrite(String number, String name, String contractType, UUID customerId, UUID projectId,
                                String counterpartyName, LocalDate signedOn, LocalDate effectiveFrom,
                                LocalDate effectiveUntil, LocalDate deliveryDeadline, String contractNature,
                                UUID priceTemplateId, String currency, String signedAmount, String contractPrice,
                                String settlementMethod, UUID ownerId, Long expectedVersion) {}
    public record ContractView(UUID id, String number, String name, String contractType, UUID customerId,
                               String customerName, UUID projectId, String projectNumber, String projectName,
                               String counterpartyName, LocalDate signedOn, LocalDate effectiveFrom,
                               LocalDate effectiveUntil, LocalDate deliveryDeadline, String contractNature,
                               UUID priceTemplateId, String priceTemplateName, String currency, String signedAmount,
                               String contractPrice, String invoicedAmount, String settledAmount, String receivedAmount,
                               String paidAmount, String performancePercent, String settlementMethod, UUID ownerId,
                               String ownerName, String state, String terminatedReason, long version,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt, List<String> allowedActions) {}
    public record EntryWrite(String entryType, String businessNumber, LocalDate occurredOn, String amount,
                             String description, long expectedVersion) {}
    public record EntryView(UUID id, UUID contractId, String entryType, String businessNumber, LocalDate occurredOn,
                            String amount, String description, String state, String voidReason, long version,
                            String createdBy, OffsetDateTime createdAt) {}

    public record TemplateItemWrite(UUID detectionProjectRevisionId, UUID parameterId, String itemName,
                                    String unit, String unitPrice, String minimumQuantity, int sortOrder) {}
    public record TemplateWrite(String code, String name, String description, String pricingMethod, String currency,
                                List<TemplateItemWrite> items, Long expectedVersion) {}
    public record TemplateItemView(UUID id, UUID detectionProjectRevisionId, UUID parameterId, String itemName,
                                   String unit, String unitPrice, String minimumQuantity, int sortOrder, long version) {}
    public record TemplateView(UUID id, String code, String name, String description, String pricingMethod,
                               String currency, int revision, UUID supersedesId, String state, long version,
                               OffsetDateTime publishedAt, OffsetDateTime updatedAt, List<TemplateItemView> items,
                               List<String> allowedActions) {}

    public record FeeView(UUID id, UUID contractId, String contractNumber, UUID projectId, String projectName,
                          UUID commissionId, UUID sampleId, UUID reportId, UUID parameterId, String itemName,
                          String sampleName, String parameterName, String quantity, String unit, String unitPrice,
                          String amount, String paidAmount, String outstandingAmount, UUID settlementOwnerId,
                          String settlementOwnerName, String state, String lockReason, long version,
                          OffsetDateTime updatedAt, List<String> allowedActions) {}
    public record FeeTransition(String action, long expectedVersion, String reasonCode, String comment,
                                UUID settlementOwnerId) {}

    public record ReceiptWrite(String number, UUID contractId, UUID feeStatementId, String receivableAmount,
                               String actualAmount, LocalDate receivedOn, String paymentMethod, UUID collectorId,
                               String payerName, String invoiceNumber, Long expectedVersion) {}
    public record ReceiptView(UUID id, String number, UUID contractId, String contractNumber, UUID feeStatementId,
                              UUID originalReceiptId, String receivableAmount, String actualAmount,
                              LocalDate receivedOn, String paymentMethod, UUID collectorId, String collectorName,
                              String payerName, String invoiceNumber, String state, long version,
                              OffsetDateTime confirmedAt, OffsetDateTime updatedAt, List<String> allowedActions) {}
    public record RefundWrite(String amount, LocalDate receivedOn, String reason, long expectedVersion) {}

    public record PaymentWrite(String number, UUID contractId, String payableAmount, LocalDate plannedOn,
                               String payeeName, String invoiceNumber, String purpose, Long expectedVersion) {}
    public record PaymentTransition(String action, long expectedVersion, String reasonCode, String comment,
                                    String actualAmount, LocalDate paidOn, String paymentMethod) {}
    public record PaymentView(UUID id, String number, UUID contractId, String contractNumber,
                              String payableAmount, String actualAmount, LocalDate plannedOn, LocalDate paidOn,
                              String paymentMethod, String payeeName, String invoiceNumber, String purpose,
                              String state, String decisionComment, long version, OffsetDateTime updatedAt,
                              List<String> allowedActions) {}

    public record Analysis(String signedAmount, String contractPrice, String invoicedAmount, String receivableAmount,
                           String receivedAmount, String payableAmount, String paidAmount, String collectionRate,
                           String invoiceRate, long activeContracts, long overdueContracts, long unsettledFees) {}
    public record MyProject(UUID projectId, String projectNumber, String projectName, UUID contractId,
                            String contractNumber, String contractName, String contractState,
                            String performancePercent, long reportCount, long issuedReportCount,
                            OffsetDateTime lastReportAt) {}
    public record ProjectReport(UUID reportId, String reportNumber, String title, int revision,
                                String revisionLabel, String state, String reviewActor, String issueActor,
                                String sealActor, long distributionCount, boolean pdfReady, long version,
                                OffsetDateTime updatedAt) {}
    public record FinanceExportWrite(String resourceType, String query, String state, UUID contractId,
                                     UUID projectId) {}
    public record JobQueued(UUID jobId, String jobType, String state) {}
    public record Option(UUID id, String code, String label) {}
    public record Options(List<Option> users, List<Option> publishedTemplates) {}

    public Options options() {
        UUID organization = actor.require().organizationId();
        List<Option> users = jdbc.sql("""
                SELECT u.id,u.username,COALESCE(p.name,u.username) label FROM app_user u
                LEFT JOIN person p ON p.id=u.person_id
                WHERE u.organization_id=:organization AND u.enabled ORDER BY label,u.username
                """).param("organization", organization).query((rs, row) -> new Option(
                        rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("label"))).list();
        List<Option> templates = jdbc.sql("""
                SELECT id,concat(code,'-R',revision) code,name label FROM contract_price_template
                WHERE organization_id=:organization AND state='PUBLISHED' ORDER BY name,revision DESC
                """).param("organization", organization).query((rs, row) -> new Option(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("label"))).list();
        return new Options(users, templates);
    }

    public CursorPage<ContractView> contracts(String query, String state, String contractType, UUID customerId,
                                               UUID projectId, LocalDate signedFrom, LocalDate signedTo,
                                               String cursorValue, int requestedLimit) {
        int limit = limit(requestedLimit);
        PageCursor cursor = PageCursor.decode(cursorValue);
        Map<String, Object> params = commonParams(query, cursor, limit);
        params.put("state", upper(state)); params.put("type", upper(contractType));
        params.put("customer", customerId); params.put("project", projectId);
        params.put("signedFrom", signedFrom); params.put("signedTo", signedTo);
        String filters = contractFilters();
        List<ContractView> fetched = jdbc.sql(contractSelect() + filters + " ORDER BY b.updated_at DESC,b.id DESC LIMIT :fetch")
                .params(params).query(this::mapContract).list();
        return page(fetched, limit, this::contractCursor, () -> jdbc.sql("SELECT count(*) " + contractFrom()
                        + withoutCursor(filters, "b"))
                .params(withoutPaging(params)).query(Long.class).single());
    }

    public ContractView contract(UUID id) {
        Map<String, Object> params = actorParams(); params.put("id", id);
        return jdbc.sql(contractSelect() + contractScope() + " AND b.id=:id")
                .params(params).query(this::mapContract).optional()
                .orElseThrow(() -> ApiException.notFound("CONTRACT_NOT_FOUND", "合同不存在或不在当前数据范围内"));
    }

    @Transactional
    public ContractView createContract(ContractWrite body) {
        ContractValues value = validateContract(body, true);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO business_contract(id,organization_id,number,name,contract_type,customer_id,project_id,
                      counterparty_name,signed_on,effective_from,effective_until,delivery_deadline,contract_nature,
                      price_template_id,currency,signed_amount,contract_price,settlement_method,owner_id,created_by)
                    VALUES (:id,:organization,:number,:name,:type,:customer,:project,:counterparty,:signedOn,
                      :effectiveFrom,:effectiveUntil,:deadline,:nature,:template,:currency,:signedAmount,
                      :contractPrice,:settlement,:owner,:actor)
                    """).params(contractParams(id, value)).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "CONTRACT_NUMBER_EXISTS", "合同编号已存在");
        }
        ContractView created = contract(id);
        audit.record("CONTRACT_CREATED", "CONTRACT", id, null, created, null, null);
        return created;
    }

    @Transactional
    public ContractView updateContract(UUID id, ContractWrite body) {
        ContractView before = contract(id);
        if (!before.state().equals("DRAFT")) throw ApiException.blocked("CONTRACT_IMMUTABLE",
                "只有草稿合同可以修改", before.version(), List.of("可通过合同业务明细记录后续金额和交付变化"));
        requireVersion(body.expectedVersion(), before.version(), "合同");
        ContractValues value = validateContract(body, false);
        Map<String, Object> params = contractParams(id, value); params.put("version", before.version());
        int changed;
        try {
            changed = jdbc.sql("""
                    UPDATE business_contract SET number=:number,name=:name,contract_type=:type,customer_id=:customer,
                      project_id=:project,counterparty_name=:counterparty,signed_on=:signedOn,
                      effective_from=:effectiveFrom,effective_until=:effectiveUntil,delivery_deadline=:deadline,
                      contract_nature=:nature,price_template_id=:template,currency=:currency,
                      signed_amount=:signedAmount,contract_price=:contractPrice,settlement_method=:settlement,
                      owner_id=:owner,version=version+1,updated_at=now()
                    WHERE id=:id AND organization_id=:organization AND version=:version
                    """).params(params).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "CONTRACT_NUMBER_EXISTS", "合同编号已存在");
        }
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "合同已被其他用户更新", contract(id).version());
        ContractView after = contract(id);
        audit.record("CONTRACT_UPDATED", "CONTRACT", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TransitionResult transitionContract(UUID id, String actionValue, long expectedVersion,
                                               String reasonCode, String comment) {
        ContractView before = contract(id);
        requireVersion(expectedVersion, before.version(), "合同");
        String action = upper(actionValue);
        String next = switch (before.state() + ":" + action) {
            case "DRAFT:SUBMIT" -> "SUBMITTED";
            case "SUBMITTED:ACTIVATE" -> "ACTIVE";
            case "SUBMITTED:REJECT" -> "DRAFT";
            case "ACTIVE:COMPLETE" -> "COMPLETED";
            case "ACTIVE:TERMINATE" -> "TERMINATED";
            default -> throw ApiException.blocked("CONTRACT_ACTION_NOT_ALLOWED", "当前合同状态不允许该操作",
                    before.version(), before.allowedActions());
        };
        if (action.equals("SUBMIT")) {
            List<String> blockers = new ArrayList<>();
            if (before.signedOn() == null) blockers.add("签订日期不能为空");
            if (decimal(before.contractPrice()).signum() <= 0) blockers.add("合同价必须大于零");
            if (!blockers.isEmpty()) throw ApiException.blocked("CONTRACT_SUBMIT_BLOCKED", "合同尚不完整", before.version(), blockers);
        }
        if (action.equals("TERMINATE") && blank(comment)) throw new ApiException(HttpStatus.BAD_REQUEST,
                "TERMINATION_REASON_REQUIRED", "终止合同必须填写原因");
        int changed = jdbc.sql("""
                UPDATE business_contract SET state=:state,
                  completed_at=CASE WHEN :state='COMPLETED' THEN now() ELSE completed_at END,
                  terminated_at=CASE WHEN :state='TERMINATED' THEN now() ELSE terminated_at END,
                  terminated_reason=CASE WHEN :state='TERMINATED' THEN :comment ELSE terminated_reason END,
                  version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("state", next).param("comment", nullable(comment)).param("id", id)
                .param("organization", actor.require().organizationId()).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "合同已被其他用户更新", contract(id).version());
        ContractView after = contract(id);
        audit.record("CONTRACT_" + action, "CONTRACT", id, before, after, reasonCode, comment);
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    public List<EntryView> entries(UUID contractId) {
        contract(contractId);
        return jdbc.sql("""
                SELECT e.id,e.contract_id,e.entry_type,e.business_number,e.occurred_on,e.amount,e.description,e.state,
                  e.void_reason,e.version,u.username created_by,e.created_at
                FROM contract_financial_entry e JOIN app_user u ON u.id=e.created_by
                WHERE e.contract_id=:contract ORDER BY e.occurred_on DESC,e.created_at DESC,e.id DESC
                """).param("contract", contractId).query(this::mapEntry).list();
    }

    @Transactional
    public EntryView addEntry(UUID contractId, EntryWrite body) {
        ContractView contract = contract(contractId);
        if (!Set.of("SUBMITTED", "ACTIVE").contains(contract.state())) throw ApiException.blocked(
                "CONTRACT_ENTRY_BLOCKED", "合同未进入执行阶段", contract.version(), List.of("请先提交并生效合同"));
        requireVersion(body.expectedVersion(), contract.version(), "合同");
        String type = allowed(body.entryType(), ENTRY_TYPES, "业务类型");
        BigDecimal amount = money(body.amount(), "金额", false);
        if (!type.equals("PRICE_ADJUSTMENT") && amount.signum() < 0) throw field("金额", "除合同价调整外不得为负数");
        if (type.equals("TARGET_DELIVERY") && amount.signum() != 0) throw field("金额", "标的交付记录金额必须为 0");
        if (blank(body.description()) || body.occurredOn() == null) throw field("业务说明", "日期和说明不能为空");
        if (type.equals("PRICE_ADJUSTMENT") && decimal(contract.contractPrice()).add(amount).signum() < 0) {
            throw ApiException.blocked("CONTRACT_PRICE_NEGATIVE", "调整后合同价不能为负数", contract.version(), List.of());
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO contract_financial_entry(id,contract_id,entry_type,business_number,occurred_on,
                      amount,description,created_by)
                    VALUES (:id,:contract,:type,:number,:occurredOn,:amount,:description,:actor)
                    """).param("id", id).param("contract", contractId).param("type", type)
                    .param("number", nullable(body.businessNumber())).param("occurredOn", body.occurredOn())
                    .param("amount", amount).param("description", body.description().trim())
                    .param("actor", actor.require().id()).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "CONTRACT_ENTRY_NUMBER_EXISTS", "该类型业务编号已存在");
        }
        int contractChanged = jdbc.sql("""
                UPDATE business_contract SET contract_price=CASE WHEN :type='PRICE_ADJUSTMENT'
                  THEN contract_price+:amount ELSE contract_price END,
                  delivery_deadline=CASE WHEN :type='TARGET_DELIVERY' THEN :occurredOn ELSE delivery_deadline END,
                  version=version+1,updated_at=now() WHERE id=:id AND version=:version
                """).param("type", type).param("amount", amount).param("occurredOn", body.occurredOn())
                .param("id", contractId).param("version", contract.version()).update();
        if (contractChanged == 0) throw ApiException.conflict("VERSION_CONFLICT", "合同已被其他用户更新",
                contract(contractId).version());
        EntryView created = entry(id);
        audit.record("CONTRACT_ENTRY_CREATED", "CONTRACT", contractId, contract, created, type, body.description());
        return created;
    }

    @Transactional
    public EntryView voidEntry(UUID contractId, UUID entryId, long expectedVersion, long entryExpectedVersion, String reason) {
        ContractView contract = contract(contractId);
        requireVersion(expectedVersion, contract.version(), "合同");
        EntryView before = entry(entryId);
        if (!before.contractId().equals(contractId)) throw ApiException.notFound("CONTRACT_ENTRY_NOT_FOUND", "合同业务记录不存在");
        requireVersion(entryExpectedVersion, before.version(), "合同业务记录");
        if (before.state().equals("VOID")) return before;
        if (blank(reason)) throw new ApiException(HttpStatus.BAD_REQUEST, "VOID_REASON_REQUIRED", "作废原因不能为空");
        BigDecimal reverse = before.entryType().equals("PRICE_ADJUSTMENT") ? decimal(before.amount()).negate() : BigDecimal.ZERO;
        if (decimal(contract.contractPrice()).add(reverse).signum() < 0) throw ApiException.blocked(
                "CONTRACT_PRICE_NEGATIVE", "作废后合同价不能为负数", contract.version(), List.of());
        jdbc.sql("""
                UPDATE contract_financial_entry SET state='VOID',void_reason=:reason,version=version+1,updated_at=now()
                WHERE id=:id AND version=:entryVersion
                """).param("reason", reason.trim()).param("id", entryId).param("entryVersion", before.version()).update();
        int contractChanged = jdbc.sql("""
                UPDATE business_contract SET contract_price=contract_price+:reverse,version=version+1,updated_at=now()
                WHERE id=:id AND version=:version
                """).param("reverse", reverse).param("id", contractId).param("version", contract.version()).update();
        if (contractChanged == 0) throw ApiException.conflict("VERSION_CONFLICT", "合同已被其他用户更新",
                contract(contractId).version());
        EntryView after = entry(entryId);
        audit.record("CONTRACT_ENTRY_VOID", "CONTRACT_ENTRY", entryId, before, after, "VOID", reason);
        return after;
    }

    public CursorPage<TemplateView> templates(String query, String state, String cursorValue, int requestedLimit) {
        int limit = limit(requestedLimit); PageCursor cursor = PageCursor.decode(cursorValue);
        Map<String, Object> params = commonParams(query, cursor, limit); params.put("state", upper(state));
        String filters = " WHERE t.organization_id=:organization AND (:state='' OR t.state=:state)"
                + " AND (:query='' OR lower(t.code) LIKE :pattern OR lower(t.name) LIKE :pattern)"
                + " AND (t.updated_at,t.id)<(:cursorAt,:cursorId)";
        List<TemplateView> fetched = jdbc.sql("""
                SELECT t.id,t.code,t.name,t.description,t.pricing_method,t.currency,t.revision,t.supersedes_id,
                  t.state,t.version,t.published_at,t.updated_at FROM contract_price_template t
                """ + filters + " ORDER BY t.updated_at DESC,t.id DESC LIMIT :fetch")
                .params(params).query(this::mapTemplateSummary).list();
        return page(fetched, limit, value -> new PageCursor(value.updatedAt(), value.id()),
                () -> jdbc.sql("SELECT count(*) FROM contract_price_template t" + withoutCursor(filters, "t"))
                        .params(withoutPaging(params)).query(Long.class).single());
    }

    public TemplateView template(UUID id) {
        TemplateView summary = jdbc.sql("""
                SELECT t.id,t.code,t.name,t.description,t.pricing_method,t.currency,t.revision,t.supersedes_id,
                  t.state,t.version,t.published_at,t.updated_at FROM contract_price_template t
                WHERE t.id=:id AND t.organization_id=:organization
                """).param("id", id).param("organization", actor.require().organizationId())
                .query(this::mapTemplateSummary).optional()
                .orElseThrow(() -> ApiException.notFound("PRICE_TEMPLATE_NOT_FOUND", "合同价模板不存在"));
        return withItems(summary, templateItems(id));
    }

    @Transactional
    public TemplateView createTemplate(TemplateWrite body) {
        TemplateValues value = validateTemplate(body);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO contract_price_template(id,organization_id,code,name,description,pricing_method,
                      currency,created_by) VALUES (:id,:organization,:code,:name,:description,:method,:currency,:actor)
                    """).param("id", id).param("organization", actor.require().organizationId())
                    .param("code", value.code()).param("name", value.name()).param("description", value.description())
                    .param("method", value.method()).param("currency", value.currency())
                    .param("actor", actor.require().id()).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "PRICE_TEMPLATE_CODE_EXISTS", "合同价模板编号已存在");
        }
        replaceTemplateItems(id, value.items());
        TemplateView created = template(id);
        audit.record("PRICE_TEMPLATE_CREATED", "PRICE_TEMPLATE", id, null, created, null, null);
        return created;
    }

    @Transactional
    public TemplateView updateTemplate(UUID id, TemplateWrite body) {
        TemplateView before = template(id);
        if (!before.state().equals("DRAFT")) throw ApiException.blocked("PRICE_TEMPLATE_IMMUTABLE",
                "已发布模板不可原地修改", before.version(), List.of("请创建新修订"));
        requireVersion(body.expectedVersion(), before.version(), "合同价模板");
        TemplateValues value = validateTemplate(body);
        int changed;
        try {
            changed = jdbc.sql("""
                    UPDATE contract_price_template SET code=:code,name=:name,description=:description,
                      pricing_method=:method,currency=:currency,version=version+1,updated_at=now()
                    WHERE id=:id AND organization_id=:organization AND version=:version
                    """).param("code", value.code()).param("name", value.name()).param("description", value.description())
                    .param("method", value.method()).param("currency", value.currency()).param("id", id)
                    .param("organization", actor.require().organizationId()).param("version", before.version()).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "PRICE_TEMPLATE_CODE_EXISTS", "合同价模板编号已存在");
        }
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "合同价模板已被更新", template(id).version());
        replaceTemplateItems(id, value.items());
        TemplateView after = template(id);
        audit.record("PRICE_TEMPLATE_UPDATED", "PRICE_TEMPLATE", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TemplateView reviseTemplate(UUID id, long expectedVersion) {
        TemplateView before = template(id);
        requireVersion(expectedVersion, before.version(), "合同价模板");
        if (!before.state().equals("PUBLISHED")) throw ApiException.blocked("PRICE_TEMPLATE_REVISION_BLOCKED",
                "只有已发布模板可以创建新修订", before.version(), List.of());
        UUID revisionId = UUID.randomUUID();
        int inserted;
        try {
            inserted = jdbc.sql("""
                    INSERT INTO contract_price_template(id,organization_id,code,name,description,pricing_method,currency,
                      revision,supersedes_id,created_by)
                    SELECT :newId,organization_id,code,name,description,pricing_method,currency,revision+1,id,:actor
                    FROM contract_price_template WHERE id=:id AND version=:version
                    """).param("newId", revisionId).param("actor", actor.require().id()).param("id", id)
                    .param("version", before.version()).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "PRICE_TEMPLATE_REVISION_EXISTS", "该模板的新修订已存在");
        }
        if (inserted == 0) throw ApiException.conflict("VERSION_CONFLICT", "合同价模板已被更新", template(id).version());
        jdbc.sql("""
                INSERT INTO contract_price_template_item(id,template_id,detection_project_revision_id,parameter_id,
                  item_name,unit,unit_price,minimum_quantity,sort_order)
                SELECT gen_random_uuid(),:newId,detection_project_revision_id,parameter_id,item_name,unit,unit_price,
                  minimum_quantity,sort_order FROM contract_price_template_item WHERE template_id=:id
                """).param("newId", revisionId).param("id", id).update();
        TemplateView created = template(revisionId);
        audit.record("PRICE_TEMPLATE_REVISED", "PRICE_TEMPLATE", revisionId, before, created, null, null);
        return created;
    }

    @Transactional
    public TransitionResult transitionTemplate(UUID id, String actionValue, long expectedVersion, String comment) {
        TemplateView before = template(id); requireVersion(expectedVersion, before.version(), "合同价模板");
        String action = upper(actionValue);
        String next = switch (before.state() + ":" + action) {
            case "DRAFT:PUBLISH" -> "PUBLISHED";
            case "PUBLISHED:RETIRE" -> "RETIRED";
            default -> throw ApiException.blocked("PRICE_TEMPLATE_ACTION_NOT_ALLOWED", "当前模板状态不允许该操作",
                    before.version(), before.allowedActions());
        };
        if (action.equals("PUBLISH") && before.items().isEmpty()) throw ApiException.blocked(
                "PRICE_TEMPLATE_EMPTY", "空模板不能发布", before.version(), List.of("至少配置一条计价项目"));
        int changed = jdbc.sql("""
                UPDATE contract_price_template SET state=:state,
                  published_by=CASE WHEN :state='PUBLISHED' THEN :actor ELSE published_by END,
                  published_at=CASE WHEN :state='PUBLISHED' THEN now() ELSE published_at END,
                  version=version+1,updated_at=now() WHERE id=:id AND version=:version
                """).param("state", next).param("actor", actor.require().id()).param("id", id)
                .param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "合同价模板已被更新", template(id).version());
        TemplateView after = template(id);
        audit.record("PRICE_TEMPLATE_" + action, "PRICE_TEMPLATE", id, before, after, action, comment);
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    @Transactional
    public List<FeeView> recalculateFees(UUID contractId, long expectedVersion) {
        ContractView contract = contract(contractId); requireVersion(expectedVersion, contract.version(), "合同");
        if (!Set.of("SUBMITTED", "ACTIVE").contains(contract.state())) throw ApiException.blocked(
                "FEE_RECALCULATE_BLOCKED", "合同未进入执行阶段", contract.version(), List.of());
        List<PlanFee> plans = jdbc.sql("""
                SELECT pl.id,pl.project_id,pl.detection_project_revision_id,pl.parameter_id,pl.project_name,
                  pl.parameter_name,pl.planned_quantity,pl.unit,pl.unit_price
                FROM project_detection_plan pl WHERE pl.project_id=:project AND pl.active
                ORDER BY pl.sort_order,pl.id
                """).param("project", contract.projectId()).query((rs, row) -> new PlanFee(
                        rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                        rs.getObject("detection_project_revision_id", UUID.class), rs.getObject("parameter_id", UUID.class),
                        rs.getString("project_name"), rs.getString("parameter_name"), rs.getBigDecimal("planned_quantity"),
                        rs.getString("unit"), rs.getBigDecimal("unit_price"))).list();
        if (plans.isEmpty()) throw ApiException.blocked("FEE_SOURCE_EMPTY", "工程没有可计价检测计划", contract.version(),
                List.of("请先在工程检测计划中配置数量和单价"));
        for (PlanFee plan : plans) {
            BigDecimal amount = plan.quantity().multiply(plan.unitPrice()).setScale(2, RoundingMode.HALF_UP);
            jdbc.sql("""
                    INSERT INTO fee_statement(id,organization_id,contract_id,project_id,
                      detection_project_revision_id,parameter_id,source_plan_id,item_name,parameter_name,quantity,
                      unit,unit_price,amount,settlement_owner_id,created_by)
                    VALUES (gen_random_uuid(),:organization,:contract,:project,:revision,:parameter,:plan,:item,
                      :parameterName,:quantity,:unit,:unitPrice,:amount,:owner,:actor)
                    ON CONFLICT (contract_id,source_plan_id) DO UPDATE SET
                      item_name=EXCLUDED.item_name,parameter_name=EXCLUDED.parameter_name,quantity=EXCLUDED.quantity,
                      unit=EXCLUDED.unit,unit_price=EXCLUDED.unit_price,amount=EXCLUDED.amount,
                      version=fee_statement.version+1,updated_at=now()
                    WHERE fee_statement.state='DRAFT' AND fee_statement.paid_amount=0
                    """).param("organization", actor.require().organizationId()).param("contract", contractId)
                    .param("project", plan.projectId()).param("revision", plan.revisionId())
                    .param("parameter", plan.parameterId()).param("plan", plan.id()).param("item", plan.projectName())
                    .param("parameterName", plan.parameterName()).param("quantity", plan.quantity())
                    .param("unit", plan.unit()).param("unitPrice", plan.unitPrice()).param("amount", amount)
                    .param("owner", contract.ownerId()).param("actor", actor.require().id()).update();
        }
        int changed = jdbc.sql("UPDATE business_contract SET version=version+1,updated_at=now() WHERE id=:id AND version=:version")
                .param("id", contractId).param("version", contract.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "合同已被其他用户更新", contract(contractId).version());
        List<FeeView> after = feesForContract(contractId);
        audit.record("FEE_STATEMENTS_RECALCULATED", "CONTRACT", contractId, contract, after, null, null);
        return after;
    }

    public CursorPage<FeeView> fees(String query, String state, UUID contractId, UUID projectId,
                                    String cursorValue, int requestedLimit) {
        int limit = limit(requestedLimit); PageCursor cursor = PageCursor.decode(cursorValue);
        Map<String, Object> params = commonParams(query, cursor, limit); params.put("state", upper(state));
        params.put("contract", contractId); params.put("project", projectId);
        String filters = feeScope() + " AND (:state='' OR f.state=:state)"
                + " AND (CAST(:contract AS uuid) IS NULL OR f.contract_id=CAST(:contract AS uuid))"
                + " AND (CAST(:project AS uuid) IS NULL OR f.project_id=CAST(:project AS uuid))"
                + " AND (:query='' OR lower(f.item_name) LIKE :pattern OR lower(COALESCE(f.parameter_name,'')) LIKE :pattern"
                + " OR lower(b.number) LIKE :pattern OR lower(p.name) LIKE :pattern)"
                + " AND (f.updated_at,f.id)<(:cursorAt,:cursorId)";
        List<FeeView> fetched = jdbc.sql(feeSelect() + filters + " ORDER BY f.updated_at DESC,f.id DESC LIMIT :fetch")
                .params(params).query(this::mapFee).list();
        return page(fetched, limit, value -> new PageCursor(value.updatedAt(), value.id()),
                () -> jdbc.sql("SELECT count(*) " + feeFrom() + withoutCursor(filters, "f"))
                        .params(withoutPaging(params)).query(Long.class).single());
    }

    @Transactional
    public TransitionResult transitionFee(UUID id, FeeTransition body) {
        FeeView before = fee(id); requireVersion(body.expectedVersion(), before.version(), "费用清单");
        String action = upper(body.action());
        String next;
        if (action.equals("CHANGE_SETTLEMENT")) {
            if (body.settlementOwnerId() == null) throw field("结算人", "结算人不能为空");
            requireUser(body.settlementOwnerId());
            int changed = jdbc.sql("""
                    UPDATE fee_statement SET settlement_owner_id=:owner,version=version+1,updated_at=now()
                    WHERE id=:id AND version=:version
                    """).param("owner", body.settlementOwnerId()).param("id", id).param("version", before.version()).update();
            if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "费用清单已被更新", fee(id).version());
            FeeView after = fee(id);
            audit.record("FEE_SETTLEMENT_OWNER_CHANGED", "FEE_STATEMENT", id, before, after,
                    body.reasonCode(), body.comment());
            return new TransitionResult(after.state(), after.version(), after.allowedActions());
        }
        next = switch (before.state() + ":" + action) {
            case "DRAFT:LOCK" -> "LOCKED";
            case "LOCKED:UNLOCK", "LOCKED:FORCE_UNLOCK" -> "DRAFT";
            default -> throw ApiException.blocked("FEE_ACTION_NOT_ALLOWED", "当前费用状态不允许该操作",
                    before.version(), before.allowedActions());
        };
        if (action.equals("UNLOCK") && decimal(before.paidAmount()).signum() != 0) throw ApiException.blocked(
                "FEE_HAS_RECEIPTS", "已有收款的费用不能普通解锁", before.version(), List.of("使用强制解锁并填写原因"));
        if (action.equals("FORCE_UNLOCK") && blank(body.comment())) throw new ApiException(HttpStatus.BAD_REQUEST,
                "FORCE_UNLOCK_REASON_REQUIRED", "强制解锁必须填写原因");
        int changed = jdbc.sql("""
                UPDATE fee_statement SET state=:state,
                  lock_reason=CASE WHEN :state='LOCKED' THEN :comment ELSE NULL END,
                  locked_by=CASE WHEN :state='LOCKED' THEN :actor ELSE NULL END,
                  locked_at=CASE WHEN :state='LOCKED' THEN now() ELSE NULL END,
                  version=version+1,updated_at=now() WHERE id=:id AND version=:version
                """).param("state", next).param("comment", nullable(body.comment())).param("actor", actor.require().id())
                .param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "费用清单已被更新", fee(id).version());
        FeeView after = fee(id);
        audit.record("FEE_" + action, "FEE_STATEMENT", id, before, after, body.reasonCode(), body.comment());
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    public CursorPage<ReceiptView> receipts(String query, String state, UUID contractId,
                                            String cursorValue, int requestedLimit) {
        int limit = limit(requestedLimit); PageCursor cursor = PageCursor.decode(cursorValue);
        Map<String, Object> params = commonParams(query, cursor, limit); params.put("state", upper(state));
        params.put("contract", contractId);
        String filters = receiptScope() + " AND (:state='' OR r.state=:state)"
                + " AND (CAST(:contract AS uuid) IS NULL OR r.contract_id=CAST(:contract AS uuid))"
                + " AND (:query='' OR lower(r.number) LIKE :pattern OR lower(r.payer_name) LIKE :pattern"
                + " OR lower(b.number) LIKE :pattern OR lower(COALESCE(r.invoice_number,'')) LIKE :pattern)"
                + " AND (r.updated_at,r.id)<(:cursorAt,:cursorId)";
        List<ReceiptView> fetched = jdbc.sql(receiptSelect() + filters + " ORDER BY r.updated_at DESC,r.id DESC LIMIT :fetch")
                .params(params).query(this::mapReceipt).list();
        return page(fetched, limit, value -> new PageCursor(value.updatedAt(), value.id()),
                () -> jdbc.sql("SELECT count(*) " + receiptFrom() + withoutCursor(filters, "r"))
                        .params(withoutPaging(params)).query(Long.class).single());
    }

    @Transactional
    public ReceiptView createReceipt(ReceiptWrite body) {
        ContractView contract = contract(body.contractId());
        if (!contract.state().equals("ACTIVE")) throw ApiException.blocked("RECEIPT_CONTRACT_BLOCKED",
                "只有生效合同可以登记收款", contract.version(), List.of());
        ReceiptValues value = validateReceipt(body);
        if (value.feeId() != null) {
            FeeView fee = fee(value.feeId());
            if (!fee.contractId().equals(contract.id())) throw field("费用清单", "费用不属于所选合同");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO finance_receipt(id,organization_id,number,contract_id,fee_statement_id,
                      receivable_amount,actual_amount,received_on,payment_method,collector_id,payer_name,
                      invoice_number,created_by)
                    VALUES (:id,:organization,:number,:contract,:fee,:receivable,:actual,:receivedOn,:method,
                      :collector,:payer,:invoice,:actor)
                    """).param("id", id).param("organization", actor.require().organizationId())
                    .param("number", value.number()).param("contract", contract.id()).param("fee", value.feeId())
                    .param("receivable", value.receivable()).param("actual", value.actual())
                    .param("receivedOn", value.receivedOn()).param("method", value.method())
                    .param("collector", value.collectorId()).param("payer", value.payer())
                    .param("invoice", value.invoice()).param("actor", actor.require().id()).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "RECEIPT_NUMBER_EXISTS", "收款单号已存在");
        }
        ReceiptView created = receipt(id);
        audit.record("RECEIPT_CREATED", "RECEIPT", id, null, created, null, null);
        return created;
    }

    @Transactional
    public ReceiptView updateReceipt(UUID id, ReceiptWrite body) {
        ReceiptView before = receipt(id);
        if (!before.state().equals("DRAFT")) throw ApiException.blocked("RECEIPT_IMMUTABLE",
                "已确认收款不可修改", before.version(), List.of("错误收款应作废或退款"));
        requireVersion(body.expectedVersion(), before.version(), "收款单");
        ContractView contract = contract(body.contractId());
        if (!contract.state().equals("ACTIVE")) throw ApiException.blocked("RECEIPT_CONTRACT_BLOCKED",
                "只有生效合同可以登记收款", contract.version(), List.of());
        ReceiptValues value = validateReceipt(body);
        if (value.feeId() != null && !fee(value.feeId()).contractId().equals(contract.id())) throw field("费用清单", "费用不属于所选合同");
        int changed = jdbc.sql("""
                UPDATE finance_receipt SET number=:number,contract_id=:contract,fee_statement_id=:fee,
                  receivable_amount=:receivable,actual_amount=:actual,received_on=:receivedOn,payment_method=:method,
                  collector_id=:collector,payer_name=:payer,invoice_number=:invoice,version=version+1,updated_at=now()
                WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("number", value.number()).param("contract", contract.id()).param("fee", value.feeId())
                .param("receivable", value.receivable()).param("actual", value.actual())
                .param("receivedOn", value.receivedOn()).param("method", value.method())
                .param("collector", value.collectorId()).param("payer", value.payer())
                .param("invoice", value.invoice()).param("id", id).param("organization", actor.require().organizationId())
                .param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "收款单已被更新", receipt(id).version());
        ReceiptView after = receipt(id); audit.record("RECEIPT_UPDATED", "RECEIPT", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TransitionResult transitionReceipt(UUID id, String actionValue, long expectedVersion, String comment) {
        ReceiptView before = receipt(id); requireVersion(expectedVersion, before.version(), "收款单");
        String action = upper(actionValue);
        requirePermission(action.equals("CONFIRM") ? "FINANCE_APPROVE" : "FINANCE_MANAGE");
        String next = switch (before.state() + ":" + action) {
            case "DRAFT:CONFIRM" -> "CONFIRMED";
            case "DRAFT:VOID" -> "VOID";
            default -> throw ApiException.blocked("RECEIPT_ACTION_NOT_ALLOWED", "当前收款状态不允许该操作",
                    before.version(), before.allowedActions());
        };
        if (action.equals("VOID") && blank(comment)) throw new ApiException(HttpStatus.BAD_REQUEST,
                "VOID_REASON_REQUIRED", "作废原因不能为空");
        if (action.equals("CONFIRM") && before.feeStatementId() != null) applyReceiptToFee(before.feeStatementId(), decimal(before.actualAmount()));
        int changed = jdbc.sql("""
                UPDATE finance_receipt SET state=:state,
                  confirmed_by=CASE WHEN :state='CONFIRMED' THEN :actor ELSE NULL END,
                  confirmed_at=CASE WHEN :state='CONFIRMED' THEN now() ELSE NULL END,
                  void_reason=CASE WHEN :state='VOID' THEN :comment ELSE NULL END,
                  version=version+1,updated_at=now() WHERE id=:id AND version=:version
                """).param("state", next).param("actor", actor.require().id()).param("comment", nullable(comment))
                .param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "收款单已被更新", receipt(id).version());
        ReceiptView after = receipt(id); audit.record("RECEIPT_" + action, "RECEIPT", id, before, after, action, comment);
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    @Transactional
    public ReceiptView refundReceipt(UUID id, RefundWrite body) {
        ReceiptView original = receipt(id); requireVersion(body.expectedVersion(), original.version(), "收款单");
        if (!original.state().equals("CONFIRMED") || decimal(original.actualAmount()).signum() <= 0) throw ApiException.blocked(
                "RECEIPT_REFUND_BLOCKED", "只有已确认的正向收款可以退款", original.version(), List.of());
        BigDecimal amount = money(body.amount(), "退款金额", true);
        BigDecimal already = jdbc.sql("""
                SELECT COALESCE(-sum(actual_amount),0) FROM finance_receipt
                WHERE original_receipt_id=:id AND state='CONFIRMED'
                """).param("id", id).query(BigDecimal.class).single();
        if (already.add(amount).compareTo(decimal(original.actualAmount())) > 0) throw ApiException.blocked(
                "REFUND_EXCEEDS_RECEIPT", "累计退款不能超过原收款", original.version(), List.of());
        if (blank(body.reason()) || body.receivedOn() == null) throw field("退款原因", "退款日期和原因不能为空");
        int reserved = jdbc.sql("""
                UPDATE finance_receipt SET version=version+1,updated_at=now()
                WHERE id=:id AND version=:version AND state='CONFIRMED'
                """).param("id", id).param("version", original.version()).update();
        if (reserved == 0) throw ApiException.conflict("VERSION_CONFLICT", "收款单已被更新", receipt(id).version());
        int sequence = jdbc.sql("SELECT count(*)+1 FROM finance_receipt WHERE original_receipt_id=:id")
                .param("id", id).query(Integer.class).single();
        UUID refundId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO finance_receipt(id,organization_id,number,contract_id,fee_statement_id,original_receipt_id,
                  receivable_amount,actual_amount,received_on,payment_method,collector_id,payer_name,invoice_number,
                  state,confirmed_by,confirmed_at,created_by)
                SELECT :refundId,organization_id,concat(number,'-TK',:sequence),contract_id,fee_statement_id,id,
                  0,:amount, :receivedOn,payment_method,:actor,payer_name,invoice_number,'CONFIRMED',:actor,now(),:actor
                FROM finance_receipt WHERE id=:id
                """).param("refundId", refundId).param("sequence", sequence).param("amount", amount.negate())
                .param("receivedOn", body.receivedOn()).param("actor", actor.require().id()).param("id", id).update();
        if (original.feeStatementId() != null) applyReceiptToFee(original.feeStatementId(), amount.negate());
        ReceiptView refund = receipt(refundId);
        audit.record("RECEIPT_REFUNDED", "RECEIPT", id, original, refund, "REFUND", body.reason());
        return refund;
    }

    public CursorPage<PaymentView> payments(String query, String state, UUID contractId,
                                            String cursorValue, int requestedLimit) {
        int limit = limit(requestedLimit); PageCursor cursor = PageCursor.decode(cursorValue);
        Map<String, Object> params = commonParams(query, cursor, limit); params.put("state", upper(state));
        params.put("contract", contractId);
        String filters = paymentScope() + " AND (:state='' OR py.state=:state)"
                + " AND (CAST(:contract AS uuid) IS NULL OR py.contract_id=CAST(:contract AS uuid))"
                + " AND (:query='' OR lower(py.number) LIKE :pattern OR lower(py.payee_name) LIKE :pattern"
                + " OR lower(b.number) LIKE :pattern OR lower(COALESCE(py.invoice_number,'')) LIKE :pattern)"
                + " AND (py.updated_at,py.id)<(:cursorAt,:cursorId)";
        List<PaymentView> fetched = jdbc.sql(paymentSelect() + filters + " ORDER BY py.updated_at DESC,py.id DESC LIMIT :fetch")
                .params(params).query(this::mapPayment).list();
        return page(fetched, limit, value -> new PageCursor(value.updatedAt(), value.id()),
                () -> jdbc.sql("SELECT count(*) " + paymentFrom() + withoutCursor(filters, "py"))
                        .params(withoutPaging(params)).query(Long.class).single());
    }

    @Transactional
    public PaymentView createPayment(PaymentWrite body) {
        ContractView contract = contract(body.contractId());
        if (!contract.state().equals("ACTIVE")) throw ApiException.blocked("PAYMENT_CONTRACT_BLOCKED",
                "只有生效合同可以登记付款", contract.version(), List.of());
        PaymentValues value = validatePayment(body); UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO finance_payment(id,organization_id,number,contract_id,payable_amount,planned_on,
                      payee_name,invoice_number,purpose,created_by)
                    VALUES (:id,:organization,:number,:contract,:payable,:plannedOn,:payee,:invoice,:purpose,:actor)
                    """).param("id", id).param("organization", actor.require().organizationId())
                    .param("number", value.number()).param("contract", contract.id()).param("payable", value.payable())
                    .param("plannedOn", value.plannedOn()).param("payee", value.payee()).param("invoice", value.invoice())
                    .param("purpose", value.purpose()).param("actor", actor.require().id()).update();
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NUMBER_EXISTS", "付款单号已存在");
        }
        PaymentView created = payment(id); audit.record("PAYMENT_CREATED", "PAYMENT", id, null, created, null, null);
        return created;
    }

    @Transactional
    public PaymentView updatePayment(UUID id, PaymentWrite body) {
        PaymentView before = payment(id);
        if (!before.state().equals("DRAFT")) throw ApiException.blocked("PAYMENT_IMMUTABLE",
                "只有草稿付款单可以修改", before.version(), List.of());
        requireVersion(body.expectedVersion(), before.version(), "付款单");
        ContractView contract = contract(body.contractId()); PaymentValues value = validatePayment(body);
        int changed = jdbc.sql("""
                UPDATE finance_payment SET number=:number,contract_id=:contract,payable_amount=:payable,
                  planned_on=:plannedOn,payee_name=:payee,invoice_number=:invoice,purpose=:purpose,
                  version=version+1,updated_at=now() WHERE id=:id AND organization_id=:organization AND version=:version
                """).param("number", value.number()).param("contract", contract.id()).param("payable", value.payable())
                .param("plannedOn", value.plannedOn()).param("payee", value.payee()).param("invoice", value.invoice())
                .param("purpose", value.purpose()).param("id", id).param("organization", actor.require().organizationId())
                .param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "付款单已被更新", payment(id).version());
        PaymentView after = payment(id); audit.record("PAYMENT_UPDATED", "PAYMENT", id, before, after, null, null);
        return after;
    }

    @Transactional
    public TransitionResult transitionPayment(UUID id, PaymentTransition body) {
        PaymentView before = payment(id); requireVersion(body.expectedVersion(), before.version(), "付款单");
        String action = upper(body.action());
        requirePermission(Set.of("APPROVE", "REJECT", "MARK_PAID").contains(action)
                ? "FINANCE_APPROVE" : "FINANCE_MANAGE");
        String next = switch (before.state() + ":" + action) {
            case "DRAFT:SUBMIT" -> "SUBMITTED";
            case "SUBMITTED:APPROVE" -> "APPROVED";
            case "SUBMITTED:REJECT" -> "REJECTED";
            case "APPROVED:MARK_PAID" -> "PAID";
            case "DRAFT:VOID", "REJECTED:VOID" -> "VOID";
            default -> throw ApiException.blocked("PAYMENT_ACTION_NOT_ALLOWED", "当前付款状态不允许该操作",
                    before.version(), before.allowedActions());
        };
        if (action.equals("APPROVE") && createdBy(id).equals(actor.require().id())) throw ApiException.blocked(
                "PAYMENT_DUTY_CONFLICT", "付款申请人不能审批本人付款单", before.version(), List.of("请由独立复核人审批"));
        if (Set.of("REJECT", "VOID").contains(action) && blank(body.comment())) throw new ApiException(HttpStatus.BAD_REQUEST,
                "DECISION_COMMENT_REQUIRED", "拒绝或作废必须填写原因");
        BigDecimal actual = action.equals("MARK_PAID") ? money(body.actualAmount(), "实付金额", true) : null;
        String method = action.equals("MARK_PAID") ? allowed(body.paymentMethod(), PAYMENT_METHODS, "付款方式") : null;
        if (action.equals("MARK_PAID") && body.paidOn() == null) throw field("付款日期", "付款日期不能为空");
        int changed = jdbc.sql("""
                UPDATE finance_payment SET state=:state,
                  approved_by=CASE WHEN :action='APPROVE' THEN :actor ELSE approved_by END,
                  approved_at=CASE WHEN :action='APPROVE' THEN now() ELSE approved_at END,
                  decision_comment=CASE WHEN :action IN ('APPROVE','REJECT','VOID') THEN :comment ELSE decision_comment END,
                  actual_amount=CASE WHEN :action='MARK_PAID' THEN :actual ELSE actual_amount END,
                  paid_on=CASE WHEN :action='MARK_PAID' THEN :paidOn ELSE paid_on END,
                  payment_method=CASE WHEN :action='MARK_PAID' THEN :method ELSE payment_method END,
                  version=version+1,updated_at=now() WHERE id=:id AND version=:version
                """).param("state", next).param("action", action).param("actor", actor.require().id())
                .param("comment", nullable(body.comment())).param("actual", actual).param("paidOn", body.paidOn())
                .param("method", method).param("id", id).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "付款单已被更新", payment(id).version());
        PaymentView after = payment(id); audit.record("PAYMENT_" + action, "PAYMENT", id, before, after,
                body.reasonCode(), body.comment());
        return new TransitionResult(after.state(), after.version(), after.allowedActions());
    }

    public Analysis analysis(LocalDate from, LocalDate to) {
        AuthenticatedUser user = actor.require();
        LocalDate start = from == null ? LocalDate.now().withDayOfYear(1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        if (end.isBefore(start)) throw field("统计时段", "结束日期不得早于开始日期");
        return jdbc.sql("""
                WITH visible AS (
                  SELECT b.* FROM business_contract b JOIN project p ON p.id=b.project_id
                  WHERE b.organization_id=:organization AND b.signed_on BETWEEN :from AND :to AND (
                    :scope IN ('ALL','ORGANIZATION')
                    OR (:scope='PROJECT' AND EXISTS(SELECT 1 FROM project_member pm WHERE pm.project_id=p.id AND pm.user_id=:actor))
                    OR (:scope='DEPARTMENT' AND EXISTS(SELECT 1 FROM app_user u WHERE u.id=p.created_by AND u.department_id=:department))
                    OR (:scope='SELF' AND (b.owner_id=:actor OR p.created_by=:actor OR EXISTS(
                      SELECT 1 FROM project_member pm WHERE pm.project_id=p.id AND pm.user_id=:actor)))
                  )
                ), entries AS (
                  SELECT COALESCE(sum(CASE WHEN e.entry_type='INVOICE' THEN e.amount ELSE 0 END),0) invoiced,
                    COALESCE(sum(CASE WHEN e.entry_type='SETTLEMENT' THEN e.amount ELSE 0 END),0) settled
                  FROM contract_financial_entry e JOIN visible b ON b.id=e.contract_id WHERE e.state='VALID'
                ), receipts AS (
                  SELECT COALESCE(sum(r.actual_amount),0) received FROM finance_receipt r JOIN visible b ON b.id=r.contract_id
                  WHERE r.state='CONFIRMED'
                ), payments AS (
                  SELECT COALESCE(sum(py.payable_amount),0) payable,
                    COALESCE(sum(CASE WHEN py.state='PAID' THEN py.actual_amount ELSE 0 END),0) paid
                  FROM finance_payment py JOIN visible b ON b.id=py.contract_id WHERE py.state<>'VOID'
                ), fees AS (
                  SELECT COALESCE(sum(f.amount),0) receivable,
                    count(*) FILTER (WHERE f.state<>'SETTLED') unsettled
                  FROM fee_statement f JOIN visible b ON b.id=f.contract_id
                )
                SELECT COALESCE(sum(b.signed_amount),0) signed_amount,COALESCE(sum(b.contract_price),0) contract_price,
                  entries.invoiced,fees.receivable,receipts.received,payments.payable,payments.paid,
                  count(*) FILTER (WHERE b.state='ACTIVE') active_contracts,
                  count(*) FILTER (WHERE b.state='ACTIVE' AND b.delivery_deadline<CURRENT_DATE) overdue_contracts,
                  fees.unsettled FROM visible b CROSS JOIN entries CROSS JOIN receipts CROSS JOIN payments CROSS JOIN fees
                GROUP BY entries.invoiced,fees.receivable,receipts.received,payments.payable,payments.paid,fees.unsettled
                """).params(actorParams()).param("from", start).param("to", end).query((rs, row) -> {
                    BigDecimal price = rs.getBigDecimal("contract_price"); BigDecimal invoiced = rs.getBigDecimal("invoiced");
                    BigDecimal received = rs.getBigDecimal("received");
                    return new Analysis(decimal(rs.getBigDecimal("signed_amount")), decimal(price), decimal(invoiced),
                            decimal(rs.getBigDecimal("receivable")), decimal(received), decimal(rs.getBigDecimal("payable")),
                            decimal(rs.getBigDecimal("paid")), percent(received, price), percent(invoiced, price),
                            rs.getLong("active_contracts"), rs.getLong("overdue_contracts"), rs.getLong("unsettled"));
                }).optional().orElse(new Analysis("0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00",
                        "0.00", "0.00", 0, 0, 0));
    }

    public List<MyProject> myProjects(String query) {
        UUID user = actor.require().id();
        String normalized = text(query);
        return jdbc.sql("""
                SELECT p.id project_id,p.number project_number,p.name project_name,b.id contract_id,
                  b.number contract_number,b.name contract_name,b.state contract_state,p.progress_percent,
                  count(r.id) report_count,count(r.id) FILTER (WHERE r.state IN ('ISSUED','DISTRIBUTED','ARCHIVED')) issued_count,
                  max(r.updated_at) last_report_at
                FROM business_contract b JOIN project p ON p.id=b.project_id
                LEFT JOIN commission c ON c.project_id=p.id LEFT JOIN report r ON r.commission_id=c.id
                WHERE b.organization_id=:organization AND (b.owner_id=:actor OR p.created_by=:actor OR EXISTS(
                  SELECT 1 FROM project_member pm WHERE pm.project_id=p.id AND pm.user_id=:actor))
                  AND (:query='' OR lower(p.number) LIKE :pattern OR lower(p.name) LIKE :pattern
                    OR lower(b.number) LIKE :pattern OR lower(b.name) LIKE :pattern)
                GROUP BY p.id,p.number,p.name,b.id,b.number,b.name,b.state,p.progress_percent,b.updated_at
                ORDER BY b.updated_at DESC,b.id DESC LIMIT 100
                """).param("organization", actor.require().organizationId()).param("actor", user)
                .param("query", normalized).param("pattern", "%" + normalized.toLowerCase(Locale.ROOT) + "%")
                .query((rs, row) -> new MyProject(rs.getObject("project_id", UUID.class), rs.getString("project_number"),
                        rs.getString("project_name"), rs.getObject("contract_id", UUID.class), rs.getString("contract_number"),
                        rs.getString("contract_name"), rs.getString("contract_state"), decimal(rs.getBigDecimal("progress_percent")),
                        rs.getLong("report_count"), rs.getLong("issued_count"),
                        rs.getObject("last_report_at", OffsetDateTime.class))).list();
    }

    public List<ProjectReport> projectReports(UUID projectId) {
        scope.requireProject(projectId);
        return jdbc.sql("""
                SELECT r.id report_id,r.number report_number,r.title,r.current_revision,v.revision_label,r.state,
                  COALESCE((SELECT reviewer.username FROM report_approval approval JOIN app_user reviewer
                    ON reviewer.id=approval.actor_id WHERE approval.report_version_id=v.id
                    AND approval.step='REVIEW' AND approval.decision='APPROVED'
                    ORDER BY approval.decided_at DESC,approval.id DESC LIMIT 1),'') review_actor,
                  COALESCE((SELECT issuer.username FROM report_approval approval JOIN app_user issuer
                    ON issuer.id=approval.actor_id WHERE approval.report_version_id=v.id
                    AND approval.step='ISSUE' AND approval.decision='APPROVED'
                    ORDER BY approval.decided_at DESC,approval.id DESC LIMIT 1),'') issue_actor,
                  COALESCE((SELECT sealer.username FROM report_approval approval JOIN app_user sealer
                    ON sealer.id=approval.actor_id WHERE approval.report_version_id=v.id
                    AND approval.step='SEAL' AND approval.decision='APPROVED'
                    ORDER BY approval.decided_at DESC,approval.id DESC LIMIT 1),'') seal_actor,
                  (SELECT count(*) FROM report_distribution distribution
                    WHERE distribution.report_version_id=v.id) distribution_count,
                  v.pdf_file_id IS NOT NULL pdf_ready,r.version,r.updated_at
                FROM report r JOIN report_version v ON v.report_id=r.id AND v.revision=r.current_revision
                JOIN commission commission ON commission.id=r.commission_id
                WHERE commission.organization_id=:organization AND commission.project_id=:project
                ORDER BY r.updated_at DESC,r.id DESC LIMIT 200
                """).param("organization", actor.require().organizationId()).param("project", projectId)
                .query((rs, row) -> new ProjectReport(rs.getObject("report_id", UUID.class),
                        rs.getString("report_number"), rs.getString("title"), rs.getInt("current_revision"),
                        rs.getString("revision_label"), rs.getString("state"), rs.getString("review_actor"),
                        rs.getString("issue_actor"), rs.getString("seal_actor"), rs.getLong("distribution_count"),
                        rs.getBoolean("pdf_ready"), rs.getLong("version"),
                        rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    @Transactional
    public JobQueued enqueueExport(FinanceExportWrite body, String idempotencyKey) {
        if (body == null) throw field("导出类型", "导出请求不能为空");
        String resourceType = allowed(body.resourceType(), EXPORT_TYPES, "导出类型");
        String query = text(body.query());
        String state = upper(body.state());
        if (query.length() > 200) throw field("查询条件", "查询条件最多 200 个字符");
        if (state.length() > 24) throw field("状态", "状态条件不合法");
        if (body.contractId() != null) contract(body.contractId());
        if (body.projectId() != null) scope.requireProject(body.projectId());
        AuthenticatedUser user = actor.require();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actorId", user.id());
        payload.put("organizationId", user.organizationId());
        payload.put("dataScope", user.dataScope());
        payload.put("departmentId", user.departmentId());
        payload.put("resourceType", resourceType);
        payload.put("query", query);
        payload.put("state", state);
        payload.put("contractId", body.contractId());
        payload.put("projectId", body.projectId());
        UUID jobId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO background_job(id,job_type,payload,idempotency_key,state,created_by,resource_type,resource_id)
                VALUES (:id,'EXPORT_FINANCE_XLSX',CAST(:payload AS jsonb),:key,'READY',:actor,:resource,:contract)
                """).param("id", jobId).param("payload", encode(payload)).param("key", idempotencyKey)
                .param("actor", user.id()).param("resource", "FINANCE_" + resourceType)
                .param("contract", body.contractId()).update();
        JobQueued queued = new JobQueued(jobId, "EXPORT_FINANCE_XLSX", "READY");
        audit.record("FINANCE_EXPORT_QUEUED", "BACKGROUND_JOB", jobId, null,
                Map.of("job", queued, "resourceType", resourceType), null, "受控财务导出已排队");
        return queued;
    }

    private FeeView fee(UUID id) {
        Map<String, Object> params = actorParams(); params.put("id", id);
        return jdbc.sql(feeSelect() + feeScope() + " AND f.id=:id").params(params).query(this::mapFee).optional()
                .orElseThrow(() -> ApiException.notFound("FEE_NOT_FOUND", "费用清单不存在或不在数据范围内"));
    }

    private List<FeeView> feesForContract(UUID contractId) {
        Map<String, Object> params = actorParams(); params.put("contract", contractId);
        return jdbc.sql(feeSelect() + feeScope() + " AND f.contract_id=:contract ORDER BY f.updated_at DESC,f.id DESC")
                .params(params).query(this::mapFee).list();
    }

    private ReceiptView receipt(UUID id) {
        Map<String, Object> params = actorParams(); params.put("id", id);
        return jdbc.sql(receiptSelect() + receiptScope() + " AND r.id=:id").params(params).query(this::mapReceipt).optional()
                .orElseThrow(() -> ApiException.notFound("RECEIPT_NOT_FOUND", "收款单不存在或不在数据范围内"));
    }

    private PaymentView payment(UUID id) {
        Map<String, Object> params = actorParams(); params.put("id", id);
        return jdbc.sql(paymentSelect() + paymentScope() + " AND py.id=:id").params(params).query(this::mapPayment).optional()
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "付款单不存在或不在数据范围内"));
    }

    private EntryView entry(UUID id) {
        return jdbc.sql("""
                SELECT e.id,e.contract_id,e.entry_type,e.business_number,e.occurred_on,e.amount,e.description,e.state,
                  e.void_reason,e.version,u.username created_by,e.created_at
                FROM contract_financial_entry e JOIN app_user u ON u.id=e.created_by WHERE e.id=:id
                """).param("id", id).query(this::mapEntry).optional()
                .orElseThrow(() -> ApiException.notFound("CONTRACT_ENTRY_NOT_FOUND", "合同业务记录不存在"));
    }

    private void applyReceiptToFee(UUID feeId, BigDecimal delta) {
        FeeView before = fee(feeId);
        if (delta.signum() > 0 && !before.state().equals("LOCKED")) throw ApiException.blocked(
                "FEE_NOT_LOCKED", "确认收款前必须锁定费用清单", before.version(), List.of("请先执行费用锁定"));
        BigDecimal paid = decimal(before.paidAmount()).add(delta);
        BigDecimal amount = decimal(before.amount());
        if (paid.signum() < 0 || paid.compareTo(amount) > 0) throw ApiException.blocked(
                "RECEIPT_AMOUNT_INVALID", "收退款金额超出费用可结算余额", before.version(), List.of());
        String next = paid.compareTo(amount) == 0 ? "SETTLED" : "LOCKED";
        int changed = jdbc.sql("""
                UPDATE fee_statement SET paid_amount=:paid,state=:state,locked_by=COALESCE(locked_by,:actor),
                  locked_at=COALESCE(locked_at,now()),version=version+1,updated_at=now()
                WHERE id=:id AND version=:version
                """).param("paid", paid).param("state", next).param("actor", actor.require().id())
                .param("id", feeId).param("version", before.version()).update();
        if (changed == 0) throw ApiException.conflict("VERSION_CONFLICT", "费用清单已被更新", fee(feeId).version());
    }

    private ContractValues validateContract(ContractWrite body, boolean creating) {
        if (blank(body.number()) || blank(body.name()) || body.customerId() == null || body.projectId() == null
                || blank(body.counterpartyName()) || body.ownerId() == null) throw field("合同必填项", "编号、名称、客户、工程、交易单位和负责人不能为空");
        scope.requireProject(body.projectId()); requireCustomer(body.customerId()); requireUser(body.ownerId());
        UUID projectCustomer = jdbc.sql("SELECT customer_id FROM project WHERE id=:id")
                .param("id", body.projectId()).query(UUID.class).single();
        if (!projectCustomer.equals(body.customerId())) throw field("客户", "合同客户必须与工程客户一致");
        String type = allowed(body.contractType(), CONTRACT_TYPES, "合同类别");
        String currency = currency(body.currency()); BigDecimal signed = money(body.signedAmount(), "签订金额", false);
        BigDecimal price = money(body.contractPrice(), "合同价", false);
        if (signed.signum() < 0 || price.signum() < 0) throw field("合同金额", "合同金额不得为负数");
        if (body.effectiveFrom() != null && body.effectiveUntil() != null && body.effectiveUntil().isBefore(body.effectiveFrom()))
            throw field("有效期", "结束日期不得早于开始日期");
        if (body.priceTemplateId() != null) {
            TemplateView template = template(body.priceTemplateId());
            if (!template.state().equals("PUBLISHED")) throw field("计价模板", "只能选择已发布模板");
        }
        return new ContractValues(body.number().trim(), body.name().trim(), type, body.customerId(), body.projectId(),
                body.counterpartyName().trim(), body.signedOn(), body.effectiveFrom(), body.effectiveUntil(),
                body.deliveryDeadline(), nullable(body.contractNature()), body.priceTemplateId(), currency, signed, price,
                nullable(body.settlementMethod()), body.ownerId());
    }

    private TemplateValues validateTemplate(TemplateWrite body) {
        if (blank(body.code()) || blank(body.name())) throw field("模板必填项", "编号和名称不能为空");
        String method = allowed(body.pricingMethod(), PRICING_METHODS, "计价方式");
        List<TemplateItemWrite> items = body.items() == null ? List.of() : List.copyOf(body.items());
        if (items.size() > 500) throw field("计价项目", "单个模板最多 500 条计价项目");
        for (TemplateItemWrite item : items) {
            if (blank(item.itemName()) || blank(item.unit())) throw field("计价项目", "项目名称和单位不能为空");
            money(item.unitPrice(), "单价", false); quantity(item.minimumQuantity(), false);
            if (item.parameterId() != null && item.detectionProjectRevisionId() == null) throw field("检测参数", "参数必须绑定检测项目版本");
            if (item.parameterId() != null) validateParameterBinding(item.detectionProjectRevisionId(), item.parameterId());
        }
        return new TemplateValues(body.code().trim(), body.name().trim(), nullable(body.description()), method,
                currency(body.currency()), items);
    }

    private ReceiptValues validateReceipt(ReceiptWrite body) {
        if (blank(body.number()) || body.contractId() == null || body.receivedOn() == null || body.collectorId() == null
                || blank(body.payerName())) throw field("收款必填项", "单号、合同、日期、收款人和付款方不能为空");
        requireUser(body.collectorId()); BigDecimal receivable = money(body.receivableAmount(), "应收金额", false);
        BigDecimal actual = money(body.actualAmount(), "实收金额", true);
        if (receivable.signum() < 0) throw field("应收金额", "应收金额不得为负数");
        return new ReceiptValues(body.number().trim(), body.feeStatementId(), receivable, actual, body.receivedOn(),
                allowed(body.paymentMethod(), PAYMENT_METHODS, "收款方式"), body.collectorId(), body.payerName().trim(),
                nullable(body.invoiceNumber()));
    }

    private PaymentValues validatePayment(PaymentWrite body) {
        if (blank(body.number()) || body.contractId() == null || body.plannedOn() == null || blank(body.payeeName())
                || blank(body.purpose())) throw field("付款必填项", "单号、合同、计划日期、收款方和用途不能为空");
        return new PaymentValues(body.number().trim(), money(body.payableAmount(), "应付金额", true), body.plannedOn(),
                body.payeeName().trim(), nullable(body.invoiceNumber()), body.purpose().trim());
    }

    private void replaceTemplateItems(UUID templateId, List<TemplateItemWrite> items) {
        jdbc.sql("DELETE FROM contract_price_template_item WHERE template_id=:id").param("id", templateId).update();
        for (TemplateItemWrite item : items) jdbc.sql("""
                INSERT INTO contract_price_template_item(id,template_id,detection_project_revision_id,parameter_id,
                  item_name,unit,unit_price,minimum_quantity,sort_order)
                VALUES (gen_random_uuid(),:template,:revision,:parameter,:name,:unit,:price,:minimum,:sort)
                """).param("template", templateId).param("revision", item.detectionProjectRevisionId())
                .param("parameter", item.parameterId()).param("name", item.itemName().trim())
                .param("unit", item.unit().trim()).param("price", money(item.unitPrice(), "单价", false))
                .param("minimum", quantity(item.minimumQuantity(), false)).param("sort", item.sortOrder()).update();
    }

    private List<TemplateItemView> templateItems(UUID templateId) {
        return jdbc.sql("""
                SELECT id,detection_project_revision_id,parameter_id,item_name,unit,unit_price,minimum_quantity,
                  sort_order,version FROM contract_price_template_item WHERE template_id=:id ORDER BY sort_order,id
                """).param("id", templateId).query((rs, row) -> new TemplateItemView(rs.getObject("id", UUID.class),
                        rs.getObject("detection_project_revision_id", UUID.class), rs.getObject("parameter_id", UUID.class),
                        rs.getString("item_name"), rs.getString("unit"), decimal(rs.getBigDecimal("unit_price")),
                        decimal(rs.getBigDecimal("minimum_quantity")), rs.getInt("sort_order"), rs.getLong("version"))).list();
    }

    private String contractSelect() {
        return """
                SELECT b.id,b.number,b.name,b.contract_type,b.customer_id,c.name customer_name,b.project_id,
                  p.number project_number,p.name project_name,b.counterparty_name,b.signed_on,b.effective_from,
                  b.effective_until,b.delivery_deadline,b.contract_nature,b.price_template_id,t.name price_template_name,
                  b.currency,b.signed_amount,b.contract_price,
                  COALESCE(fin.invoiced_amount,0) invoiced_amount,COALESCE(fin.settled_amount,0) settled_amount,
                  COALESCE(rc.received_amount,0) received_amount,COALESCE(py.paid_amount,0) paid_amount,
                  p.progress_percent,b.settlement_method,b.owner_id,owner.username owner_name,b.state,
                  b.terminated_reason,b.version,b.created_at,b.updated_at
                """ + contractFrom();
    }

    private String contractFrom() {
        return """
                FROM business_contract b JOIN customer c ON c.id=b.customer_id JOIN project p ON p.id=b.project_id
                JOIN app_user owner ON owner.id=b.owner_id
                LEFT JOIN contract_price_template t ON t.id=b.price_template_id
                LEFT JOIN LATERAL (SELECT
                  COALESCE(sum(amount) FILTER (WHERE entry_type='INVOICE'),0) invoiced_amount,
                  COALESCE(sum(amount) FILTER (WHERE entry_type='SETTLEMENT'),0) settled_amount
                  FROM contract_financial_entry e WHERE e.contract_id=b.id AND e.state='VALID') fin ON true
                LEFT JOIN LATERAL (SELECT COALESCE(sum(actual_amount),0) received_amount
                  FROM finance_receipt r WHERE r.contract_id=b.id AND r.state='CONFIRMED') rc ON true
                LEFT JOIN LATERAL (SELECT COALESCE(sum(actual_amount),0) paid_amount
                  FROM finance_payment py WHERE py.contract_id=b.id AND py.state='PAID') py ON true
                """;
    }

    private String contractFilters() {
        return contractScope() + " AND (:state='' OR b.state=:state) AND (:type='' OR b.contract_type=:type)"
                + " AND (CAST(:customer AS uuid) IS NULL OR b.customer_id=CAST(:customer AS uuid))"
                + " AND (CAST(:project AS uuid) IS NULL OR b.project_id=CAST(:project AS uuid))"
                + " AND (CAST(:signedFrom AS date) IS NULL OR b.signed_on>=CAST(:signedFrom AS date))"
                + " AND (CAST(:signedTo AS date) IS NULL OR b.signed_on<=CAST(:signedTo AS date))"
                + " AND (:query='' OR lower(b.number) LIKE :pattern OR lower(b.name) LIKE :pattern"
                + " OR lower(c.name) LIKE :pattern OR lower(p.name) LIKE :pattern)"
                + " AND (b.updated_at,b.id)<(:cursorAt,:cursorId)";
    }

    private String contractScope() {
        return " WHERE b.organization_id=:organization AND ("
                + " :scope IN ('ALL','ORGANIZATION')"
                + " OR (:scope='PROJECT' AND EXISTS(SELECT 1 FROM project_member pm WHERE pm.project_id=p.id AND pm.user_id=:actor))"
                + " OR (:scope='DEPARTMENT' AND EXISTS(SELECT 1 FROM app_user creator WHERE creator.id=p.created_by AND creator.department_id=:department))"
                + " OR (:scope='SELF' AND (b.owner_id=:actor OR p.created_by=:actor OR EXISTS(SELECT 1 FROM project_member pm WHERE pm.project_id=p.id AND pm.user_id=:actor)))"
                + ")";
    }

    private String feeSelect() {
        return """
                SELECT f.id,f.contract_id,b.number contract_number,f.project_id,p.name project_name,f.commission_id,
                  f.sample_id,f.report_id,f.parameter_id,f.item_name,f.sample_name,f.parameter_name,f.quantity,f.unit,
                  f.unit_price,f.amount,f.paid_amount,(f.amount-f.paid_amount) outstanding_amount,
                  f.settlement_owner_id,settler.username settlement_owner_name,f.state,f.lock_reason,f.version,f.updated_at
                """ + feeFrom();
    }
    private String feeFrom() { return " FROM fee_statement f JOIN business_contract b ON b.id=f.contract_id"
            + " JOIN project p ON p.id=f.project_id LEFT JOIN app_user settler ON settler.id=f.settlement_owner_id"; }
    private String feeScope() { return " WHERE f.organization_id=:organization AND ("
            + " :scope IN ('ALL','ORGANIZATION')"
            + " OR (:scope='PROJECT' AND EXISTS(SELECT 1 FROM project_member pm WHERE pm.project_id=p.id AND pm.user_id=:actor))"
            + " OR (:scope='DEPARTMENT' AND EXISTS(SELECT 1 FROM app_user creator WHERE creator.id=p.created_by AND creator.department_id=:department))"
            + " OR (:scope='SELF' AND (b.owner_id=:actor OR p.created_by=:actor OR EXISTS(SELECT 1 FROM project_member pm WHERE pm.project_id=p.id AND pm.user_id=:actor)))"
            + ")"; }

    private String receiptSelect() {
        return """
                SELECT r.id,r.number,r.contract_id,b.number contract_number,r.fee_statement_id,r.original_receipt_id,
                  r.receivable_amount,r.actual_amount,r.received_on,r.payment_method,r.collector_id,
                  collector.username collector_name,r.payer_name,r.invoice_number,r.state,r.version,
                  r.confirmed_at,r.updated_at
                """ + receiptFrom();
    }
    private String receiptFrom() { return " FROM finance_receipt r JOIN business_contract b ON b.id=r.contract_id"
            + " JOIN project p ON p.id=b.project_id JOIN app_user collector ON collector.id=r.collector_id"; }
    private String receiptScope() { return contractLikeScope("r.organization_id"); }

    private String paymentSelect() {
        return """
                SELECT py.id,py.number,py.contract_id,b.number contract_number,py.payable_amount,py.actual_amount,
                  py.planned_on,py.paid_on,py.payment_method,py.payee_name,py.invoice_number,py.purpose,py.state,
                  py.decision_comment,py.version,py.updated_at
                """ + paymentFrom();
    }
    private String paymentFrom() { return " FROM finance_payment py JOIN business_contract b ON b.id=py.contract_id"
            + " JOIN project p ON p.id=b.project_id"; }
    private String paymentScope() { return contractLikeScope("py.organization_id"); }
    private String contractLikeScope(String organizationColumn) { return " WHERE " + organizationColumn + "=:organization AND ("
            + " :scope IN ('ALL','ORGANIZATION')"
            + " OR (:scope='PROJECT' AND EXISTS(SELECT 1 FROM project_member pm WHERE pm.project_id=p.id AND pm.user_id=:actor))"
            + " OR (:scope='DEPARTMENT' AND EXISTS(SELECT 1 FROM app_user creator WHERE creator.id=p.created_by AND creator.department_id=:department))"
            + " OR (:scope='SELF' AND (b.owner_id=:actor OR p.created_by=:actor OR EXISTS(SELECT 1 FROM project_member pm WHERE pm.project_id=p.id AND pm.user_id=:actor)))"
            + ")"; }

    private ContractView mapContract(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new ContractView(rs.getObject("id", UUID.class), rs.getString("number"), rs.getString("name"),
                rs.getString("contract_type"), rs.getObject("customer_id", UUID.class), rs.getString("customer_name"),
                rs.getObject("project_id", UUID.class), rs.getString("project_number"), rs.getString("project_name"),
                rs.getString("counterparty_name"), rs.getObject("signed_on", LocalDate.class),
                rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_until", LocalDate.class),
                rs.getObject("delivery_deadline", LocalDate.class), rs.getString("contract_nature"),
                rs.getObject("price_template_id", UUID.class), rs.getString("price_template_name"), rs.getString("currency"),
                decimal(rs.getBigDecimal("signed_amount")), decimal(rs.getBigDecimal("contract_price")),
                decimal(rs.getBigDecimal("invoiced_amount")), decimal(rs.getBigDecimal("settled_amount")),
                decimal(rs.getBigDecimal("received_amount")), decimal(rs.getBigDecimal("paid_amount")),
                decimal(rs.getBigDecimal("progress_percent")), rs.getString("settlement_method"),
                rs.getObject("owner_id", UUID.class), rs.getString("owner_name"), state, rs.getString("terminated_reason"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), contractActions(state));
    }

    private EntryView mapEntry(ResultSet rs, int row) throws SQLException { return new EntryView(
            rs.getObject("id", UUID.class), rs.getObject("contract_id", UUID.class), rs.getString("entry_type"),
            rs.getString("business_number"), rs.getObject("occurred_on", LocalDate.class),
            decimal(rs.getBigDecimal("amount")), rs.getString("description"), rs.getString("state"),
            rs.getString("void_reason"), rs.getLong("version"), rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class)); }

    private TemplateView mapTemplateSummary(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new TemplateView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getString("pricing_method"), rs.getString("currency"),
                rs.getInt("revision"), rs.getObject("supersedes_id", UUID.class), state, rs.getLong("version"),
                rs.getObject("published_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                List.of(), templateActions(state));
    }

    private TemplateView withItems(TemplateView value, List<TemplateItemView> items) { return new TemplateView(value.id(),
            value.code(), value.name(), value.description(), value.pricingMethod(), value.currency(), value.revision(),
            value.supersedesId(), value.state(), value.version(), value.publishedAt(), value.updatedAt(), items,
            value.allowedActions()); }

    private FeeView mapFee(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new FeeView(rs.getObject("id", UUID.class), rs.getObject("contract_id", UUID.class),
                rs.getString("contract_number"), rs.getObject("project_id", UUID.class), rs.getString("project_name"),
                rs.getObject("commission_id", UUID.class), rs.getObject("sample_id", UUID.class),
                rs.getObject("report_id", UUID.class), rs.getObject("parameter_id", UUID.class), rs.getString("item_name"),
                rs.getString("sample_name"), rs.getString("parameter_name"), decimal(rs.getBigDecimal("quantity")),
                rs.getString("unit"), decimal(rs.getBigDecimal("unit_price")), decimal(rs.getBigDecimal("amount")),
                decimal(rs.getBigDecimal("paid_amount")), decimal(rs.getBigDecimal("outstanding_amount")),
                rs.getObject("settlement_owner_id", UUID.class), rs.getString("settlement_owner_name"), state,
                rs.getString("lock_reason"), rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class),
                feeActions(state));
    }

    private ReceiptView mapReceipt(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new ReceiptView(rs.getObject("id", UUID.class), rs.getString("number"),
                rs.getObject("contract_id", UUID.class), rs.getString("contract_number"),
                rs.getObject("fee_statement_id", UUID.class), rs.getObject("original_receipt_id", UUID.class),
                decimal(rs.getBigDecimal("receivable_amount")), decimal(rs.getBigDecimal("actual_amount")),
                rs.getObject("received_on", LocalDate.class), rs.getString("payment_method"),
                rs.getObject("collector_id", UUID.class), rs.getString("collector_name"), rs.getString("payer_name"),
                rs.getString("invoice_number"), state, rs.getLong("version"),
                rs.getObject("confirmed_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                receiptActions(state));
    }

    private PaymentView mapPayment(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new PaymentView(rs.getObject("id", UUID.class), rs.getString("number"),
                rs.getObject("contract_id", UUID.class), rs.getString("contract_number"),
                decimal(rs.getBigDecimal("payable_amount")), decimal(rs.getBigDecimal("actual_amount")),
                rs.getObject("planned_on", LocalDate.class), rs.getObject("paid_on", LocalDate.class),
                rs.getString("payment_method"), rs.getString("payee_name"), rs.getString("invoice_number"),
                rs.getString("purpose"), state, rs.getString("decision_comment"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class), paymentActions(state));
    }

    private List<String> contractActions(String state) { return switch (state) {
        case "DRAFT" -> List.of("SUBMIT"); case "SUBMITTED" -> List.of("ACTIVATE", "REJECT");
        case "ACTIVE" -> List.of("COMPLETE", "TERMINATE"); default -> List.of(); }; }
    private List<String> templateActions(String state) { return switch (state) {
        case "DRAFT" -> List.of("PUBLISH"); case "PUBLISHED" -> List.of("REVISE", "RETIRE"); default -> List.of(); }; }
    private List<String> feeActions(String state) { return switch (state) {
        case "DRAFT" -> List.of("LOCK", "CHANGE_SETTLEMENT");
        case "LOCKED" -> List.of("UNLOCK", "FORCE_UNLOCK", "CHANGE_SETTLEMENT");
        default -> List.of("CHANGE_SETTLEMENT"); }; }
    private List<String> receiptActions(String state) { return switch (state) {
        case "DRAFT" -> List.of("CONFIRM", "VOID"); case "CONFIRMED" -> List.of("REFUND"); default -> List.of(); }; }
    private List<String> paymentActions(String state) { return switch (state) {
        case "DRAFT" -> List.of("SUBMIT", "VOID"); case "SUBMITTED" -> List.of("APPROVE", "REJECT");
        case "APPROVED" -> List.of("MARK_PAID"); case "REJECTED" -> List.of("VOID"); default -> List.of(); }; }

    private Map<String, Object> actorParams() {
        AuthenticatedUser user = actor.require(); Map<String, Object> params = new HashMap<>();
        params.put("organization", user.organizationId()); params.put("scope", user.dataScope()); params.put("actor", user.id());
        params.put("department", user.departmentId() == null ? NO_ID : user.departmentId()); return params;
    }
    private Map<String, Object> commonParams(String query, PageCursor cursor, int limit) {
        Map<String, Object> params = actorParams(); String normalized = text(query);
        params.put("query", normalized); params.put("pattern", "%" + normalized.toLowerCase(Locale.ROOT) + "%");
        params.put("cursorAt", cursor == null ? OffsetDateTime.parse("9999-12-31T23:59:59Z") : cursor.updatedAt());
        params.put("cursorId", cursor == null ? new UUID(-1L, -1L) : cursor.id()); params.put("fetch", limit + 1); return params;
    }
    private Map<String, Object> withoutPaging(Map<String, Object> source) { Map<String, Object> copy = new HashMap<>(source);
        copy.remove("fetch"); copy.remove("cursorAt"); copy.remove("cursorId"); return copy; }
    private String withoutCursor(String filters, String alias) {
        return filters.replace(" AND (" + alias + ".updated_at," + alias + ".id)<(:cursorAt,:cursorId)", "");
    }
    private <T> CursorPage<T> page(List<T> fetched, int limit, CursorExtractor<T> extractor, CountSupplier count) {
        boolean hasMore = fetched.size() > limit; List<T> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String next = hasMore ? extractor.cursor(items.getLast()).encode() : null;
        return new CursorPage<>(items, next, hasMore, count.value());
    }
    @FunctionalInterface private interface CursorExtractor<T> { PageCursor cursor(T value); }
    @FunctionalInterface private interface CountSupplier { long value(); }
    private PageCursor contractCursor(ContractView value) { return new PageCursor(value.updatedAt(), value.id()); }

    private Map<String, Object> contractParams(UUID id, ContractValues value) {
        Map<String, Object> params = actorParams(); params.put("id", id); params.put("number", value.number());
        params.put("name", value.name()); params.put("type", value.type()); params.put("customer", value.customerId());
        params.put("project", value.projectId()); params.put("counterparty", value.counterparty());
        params.put("signedOn", value.signedOn()); params.put("effectiveFrom", value.effectiveFrom());
        params.put("effectiveUntil", value.effectiveUntil()); params.put("deadline", value.deadline());
        params.put("nature", value.nature()); params.put("template", value.templateId()); params.put("currency", value.currency());
        params.put("signedAmount", value.signedAmount()); params.put("contractPrice", value.contractPrice());
        params.put("settlement", value.settlement()); params.put("owner", value.ownerId()); return params;
    }

    private void requireCustomer(UUID id) { boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM customer WHERE id=:id AND organization_id=:organization AND active)")
            .param("id", id).param("organization", actor.require().organizationId()).query(Boolean.class).single();
        if (!exists) throw ApiException.notFound("CUSTOMER_NOT_FOUND", "客户不存在或已停用"); }
    private void requireUser(UUID id) { boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_user WHERE id=:id AND organization_id=:organization AND enabled)")
            .param("id", id).param("organization", actor.require().organizationId()).query(Boolean.class).single();
        if (!exists) throw ApiException.notFound("USER_NOT_FOUND", "账号不存在或已停用"); }
    private void validateParameterBinding(UUID revisionId, UUID parameterId) {
        boolean valid = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM test_parameter
                  WHERE id=:parameter AND project_revision_id=:revision AND status='PUBLISHED' AND enabled)
                """).param("parameter", parameterId).param("revision", revisionId).query(Boolean.class).single();
        if (!valid) throw field("检测参数", "参数不属于所选已发布检测项目版本");
    }
    private void requirePermission(String permission) { if (!actor.require().permissions().contains(permission))
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "当前账号无权执行该财务动作"); }
    private UUID createdBy(UUID paymentId) { return jdbc.sql("SELECT created_by FROM finance_payment WHERE id=:id")
            .param("id", paymentId).query(UUID.class).single(); }

    private int limit(int value) { return Math.max(1, Math.min(value, 100)); }
    private String encode(Object value) { try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("财务作业参数无法序列化", exception); } }
    private String text(String value) { return value == null ? "" : value.trim(); }
    private String nullable(String value) { String normalized = text(value); return normalized.isEmpty() ? null : normalized; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String upper(String value) { return text(value).toUpperCase(Locale.ROOT); }
    private String allowed(String value, Set<String> values, String field) { String normalized = upper(value);
        if (!values.contains(normalized)) throw field(field, "取值不受支持"); return normalized; }
    private String currency(String value) { String normalized = blank(value) ? "CNY" : upper(value);
        if (!normalized.matches("[A-Z]{3}")) throw field("币种", "币种必须为 3 位大写代码"); return normalized; }
    private BigDecimal money(String value, String field, boolean positive) { BigDecimal amount = parse(value, field, 2);
        if (positive && amount.signum() <= 0) throw field(field, "必须大于 0"); return amount; }
    private BigDecimal quantity(String value, boolean positive) { BigDecimal amount = parse(value, "数量", 6);
        if (positive && amount.signum() <= 0) throw field("数量", "必须大于 0");
        if (!positive && amount.signum() < 0) throw field("数量", "不得小于 0"); return amount; }
    private BigDecimal parse(String value, String field, int scale) { try {
        if (blank(value)) return BigDecimal.ZERO.setScale(scale); BigDecimal result = new BigDecimal(value.trim());
        if (result.precision() > 20 || result.scale() > scale) throw new NumberFormatException(); return result.setScale(scale);
    } catch (NumberFormatException exception) { throw field(field, "格式不正确，最多 " + scale + " 位小数"); } }
    private BigDecimal decimal(String value) { return value == null ? BigDecimal.ZERO : new BigDecimal(value); }
    private String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().scale() < 0
            ? value.setScale(0).toPlainString() : value.toPlainString(); }
    private String percent(BigDecimal part, BigDecimal total) { return total.signum() == 0 ? "0.00"
            : part.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP).toPlainString(); }
    private ApiException field(String name, String message) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
            "FIELD_VALIDATION_FAILED", "字段校验失败", null, List.of(), Map.of(name, message)); }
    private void requireVersion(Long expected, long current, String resource) { if (expected == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXPECTED_VERSION_REQUIRED", resource + "操作必须携带当前版本");
        requireVersion(expected.longValue(), current, resource); }
    private void requireVersion(long expected, long current, String resource) { if (expected != current)
            throw ApiException.conflict("VERSION_CONFLICT", resource + "已被其他用户更新", current); }

    private record ContractValues(String number, String name, String type, UUID customerId, UUID projectId,
                                  String counterparty, LocalDate signedOn, LocalDate effectiveFrom,
                                  LocalDate effectiveUntil, LocalDate deadline, String nature, UUID templateId,
                                  String currency, BigDecimal signedAmount, BigDecimal contractPrice,
                                  String settlement, UUID ownerId) {}
    private record TemplateValues(String code, String name, String description, String method, String currency,
                                  List<TemplateItemWrite> items) {}
    private record ReceiptValues(String number, UUID feeId, BigDecimal receivable, BigDecimal actual,
                                 LocalDate receivedOn, String method, UUID collectorId, String payer, String invoice) {}
    private record PaymentValues(String number, BigDecimal payable, LocalDate plannedOn, String payee,
                                 String invoice, String purpose) {}
    private record PlanFee(UUID id, UUID projectId, UUID revisionId, UUID parameterId, String projectName,
                           String parameterName, BigDecimal quantity, String unit, BigDecimal unitPrice) {}
}
