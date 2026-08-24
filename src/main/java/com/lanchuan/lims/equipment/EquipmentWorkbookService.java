package com.lanchuan.lims.equipment;

import com.lanchuan.lims.audit.AuditService;
import com.lanchuan.lims.common.ApiException;
import com.lanchuan.lims.common.CursorPage;
import com.lanchuan.lims.identity.CurrentActor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class EquipmentWorkbookService {
    static final String EQUIPMENT_TEMPLATE_VERSION = "LIMS_EQUIPMENT_IMPORT_V1";
    static final String CERTIFICATE_TEMPLATE_VERSION = "LIMS_EQUIPMENT_CERTIFICATE_IMPORT_V1";
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final List<String> EQUIPMENT_HEADERS = List.of(
            "设备编号", "固定资产编号", "设备名称", "规格型号", "所属分类编码", "购置日期", "购置单价(元)",
            "生产厂家", "出厂编号", "检定周期(月)", "检校类型", "技术指标", "量程", "测量范围", "分度值", "准确度",
            "保管人工号", "主要设备", "启停状态", "性能状态", "存放位置", "设备类型", "桩基设备类型", "备注");
    private static final List<String> CERTIFICATE_HEADERS = List.of(
            "设备编号", "检校编号", "证书类型", "检校类型", "检定日期", "有效期至", "确认日期", "检校单位", "检校结论", "备注");
    private static final Map<String, String> VERIFICATION = aliases(
            "检定", "VERIFICATION", "校准", "CALIBRATION", "功能核查", "FUNCTION_CHECK", "自校", "SELF_CALIBRATION",
            "比对", "COMPARISON", "检测", "TESTING", "其他", "OTHER");
    private static final Map<String, String> EQUIPMENT_TYPE = aliases(
            "现场检测类", "FIELD", "岩土类", "GEOTECHNICAL", "混凝土工程类", "CONCRETE", "金属结构类", "METAL_STRUCTURE",
            "机械电气类", "MECHANICAL_ELECTRICAL", "测量类", "SURVEY", "其他", "OTHER");
    private static final Map<String, String> PILE_TYPE = aliases(
            "静载主机", "STATIC_LOAD_HOST", "压力传感器", "PRESSURE_SENSOR", "位移传感器", "DISPLACEMENT_SENSOR",
            "动测主机", "DYNAMIC_HOST", "加速度传感器", "ACCELERATION_SENSOR", "力传感器", "FORCE_SENSOR",
            "千斤顶", "JACK", "速度传感器", "SPEED_SENSOR", "超声波主机", "ULTRASONIC_HOST",
            "跨孔换能器", "CROSS_HOLE_TRANSDUCER", "平面换能器", "PLANE_TRANSDUCER", "百分比", "PERCENTAGE_METER",
            "自平衡荷载箱", "SELF_BALANCING_LOAD_CELL", "无线传输仪器", "WIRELESS_TRANSMISSION");
    private static final Map<String, String> RESULT = aliases("合格", "PASS", "限用", "LIMITED", "不合格", "FAIL");

    private final JdbcClient jdbc;
    private final EquipmentService equipment;
    private final EquipmentOperationService operations;
    private final CurrentActor actor;
    private final AuditService audit;

    public EquipmentWorkbookService(JdbcClient jdbc, EquipmentService equipment, EquipmentOperationService operations,
                                    CurrentActor actor, AuditService audit) {
        this.jdbc = jdbc;
        this.equipment = equipment;
        this.operations = operations;
        this.actor = actor;
        this.audit = audit;
    }

    public record Fingerprint(String filename, String sha256) {}
    public record ImportResult(int importedCount, String templateVersion, String sha256) {}
    public record ExportFile(byte[] content, String filename, int rowCount) {}
    private record EquipmentPending(String code, String asset, String name, String model, UUID categoryId,
                                    LocalDate purchased, BigDecimal price, String manufacturer, String serial,
                                    Integer cycle, String verificationType, String technical, String nominalRange,
                                    String measurementRange, String resolution, String accuracy, UUID custodianId,
                                    boolean primary, boolean enabled, String performance, String location,
                                    String equipmentType, String pileType, String remark) {}
    private record CertificatePending(UUID equipmentId, String certificateType, String inspectionType, String number,
                                      LocalDate issuedOn, LocalDate validUntil, LocalDate confirmedOn,
                                      String organization, String result, String remark) {}

    public byte[] equipmentTemplate() { return workbook(EQUIPMENT_TEMPLATE_VERSION, EQUIPMENT_HEADERS, List.of(), false, "设备导入").content(); }
    public byte[] certificateTemplate() { return workbook(CERTIFICATE_TEMPLATE_VERSION, CERTIFICATE_HEADERS, List.of(), false, "设备检定导入").content(); }

    public ExportFile exportEquipment(String query) {
        List<EquipmentService.Summary> rows = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<EquipmentService.Summary> page = equipment.list(query, null, null, null, "", "", "", "", "", "", "",
                    "", "", "", "", "", "", "", "", cursor, 100);
            rows.addAll(page.items());
            if (rows.size() > MAX_ROWS) throw tooLarge();
            cursor = page.hasMore() ? page.nextCursor() : null;
        } while (cursor != null);
        List<List<String>> data = rows.stream().map(value -> List.of(
                value.code(), text(value.assetNumber()), value.name(), text(value.model()), text(value.categoryName()),
                value.purchasedOn() == null ? "" : value.purchasedOn().toString(),
                value.purchasePrice() == null ? "" : value.purchasePrice().toPlainString(),
                text(value.manufacturer()), text(value.serialNumber()),
                value.verificationCycleMonths() == null ? "" : value.verificationCycleMonths().toString(),
                text(value.verificationType()), text(value.technicalSpecification()), text(value.nominalRange()),
                text(value.measurementRange()), text(value.resolution()), text(value.accuracy()),
                text(value.custodianEmployeeNumber()),
                value.primaryEquipment() ? "是" : "否", value.enabled() ? "启用" : "停用",
                value.performanceStatus().equals("NORMAL") ? "正常" : "异常", text(value.location()),
                text(value.equipmentType()), text(value.pileEquipmentType()), text(value.remark()))).toList();
        ExportFile result = workbook(EQUIPMENT_TEMPLATE_VERSION, EQUIPMENT_HEADERS, data, true, "设备受控导出");
        audit.record("EQUIPMENT_EXPORTED", "EQUIPMENT", null, null, Map.of("rowCount", rows.size()), null, query);
        return result;
    }

    public ExportFile exportCertificates(String query, String type) {
        List<EquipmentService.CertificateView> rows = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<EquipmentService.CertificateView> page = equipment.certificates(query, type, "", "", "", "", "",
                    null, null, "", "", "", "", "", "", "", cursor, 100);
            rows.addAll(page.items()); if (rows.size() > MAX_ROWS) throw tooLarge();
            cursor = page.hasMore() ? page.nextCursor() : null;
        } while (cursor != null);
        List<List<String>> data = rows.stream().map(value -> List.of(value.equipmentCode(), value.certificateNumber(),
                value.certificateType(), text(value.inspectionType()), value.issuedOn().toString(), value.validUntil().toString(),
                value.confirmedOn() == null ? "" : value.confirmedOn().toString(), value.issuingOrganization(),
                value.result(), text(value.remark()))).toList();
        ExportFile result = workbook(CERTIFICATE_TEMPLATE_VERSION, CERTIFICATE_HEADERS, data, true, "设备检定受控导出");
        audit.record("EQUIPMENT_CERTIFICATES_EXPORTED", "EQUIPMENT_CERTIFICATE", null, null,
                Map.of("rowCount", rows.size()), null, query);
        return result;
    }

    public ExportFile exportInspections(String query) {
        List<EquipmentOperationService.InspectionView> rows = collect(cursor -> operations.inspections(
                query, "", "", null, null, "", "", "", "", cursor, 100));
        List<String> headers = List.of("设备编号", "设备名称", "规格型号", "出库人员", "出库时间", "出库配件数量", "设备去向",
                "检校类型", "检校编号", "检校单位", "检定日期", "有效期至", "检校结论", "确认人", "审核人", "入库人员", "状态");
        List<List<String>> data = rows.stream().map(v -> List.of(v.equipmentCode(), v.equipmentName(), text(v.model()),
                v.outboundPersonName(), v.outboundAt().toString(), v.outboundAccessoryQuantity().toPlainString(), text(v.destination()),
                text(v.inspectionType()), text(v.inspectionNumber()), text(v.inspectionOrganization()), v.inspectionDate() == null ? "" : v.inspectionDate().toString(),
                v.validUntil() == null ? "" : v.validUntil().toString(), text(v.result()), text(v.confirmedByName()), text(v.reviewedByName()),
                text(v.stockInByName()), v.state())).toList();
        return auditedExport("EQUIPMENT_INSPECTIONS_EXPORTED", "EQUIPMENT_INSPECTION", "设备送检受控导出", headers, data, query);
    }

    public ExportFile exportBorrows(String query) {
        List<EquipmentOperationService.BorrowView> rows = collect(cursor -> operations.borrows(
                query, "", "", null, null, "", "", "", "", "", cursor, 100));
        List<String> headers = List.of("设备编号", "设备名称", "规格型号", "领用人员", "领用时间", "领用配件量", "设备去向",
                "计划归还时间", "归还人员", "归还时间", "归还配件量", "移交人员", "状态");
        List<List<String>> data = rows.stream().map(v -> List.of(v.equipmentCode(), v.equipmentName(), text(v.model()),
                v.borrowerName(), v.borrowedAt().toString(), v.outboundAccessoryQuantity().toPlainString(), text(v.destination()),
                v.plannedReturnAt().toString(), text(v.returnedByName()), v.returnedAt() == null ? "" : v.returnedAt().toString(),
                v.returnedAccessoryQuantity() == null ? "" : v.returnedAccessoryQuantity().toPlainString(), text(v.transferredToName()), v.state())).toList();
        return auditedExport("EQUIPMENT_BORROWS_EXPORTED", "EQUIPMENT_BORROW", "设备领用归还受控导出", headers, data, query);
    }

    public ExportFile exportUsages(String query) {
        List<EquipmentOperationService.UsageView> rows = collect(cursor -> operations.usages(
                query, "", "", "", null, null, "", "", "", "", cursor, 100));
        List<String> headers = List.of("设备编号", "设备名称", "规格型号", "使用样品", "委托单号", "使用人", "开始时间", "结束时间",
                "温度(℃)", "湿度(%)", "设备放置场所", "使用前状态", "使用后状态", "状态");
        List<List<String>> data = rows.stream().map(v -> List.of(v.equipmentCode(), v.equipmentName(), text(v.model()), text(v.sampleName()),
                text(v.commissionNumber()), v.usedByName(), v.startedAt().toString(), v.endedAt() == null ? "" : v.endedAt().toString(),
                v.temperature() == null ? "" : v.temperature().toPlainString(), v.humidity() == null ? "" : v.humidity().toPlainString(),
                text(v.location()), v.beforeCondition(), text(v.afterCondition()), v.state())).toList();
        return auditedExport("EQUIPMENT_USAGES_EXPORTED", "EQUIPMENT_USAGE", "设备使用记录受控导出", headers, data, query);
    }

    public ExportFile exportMovements(String query) {
        List<EquipmentOperationService.MovementView> rows = collect(cursor -> operations.movements(
                query, "", "", null, null, "", "", "", "", cursor, 100));
        List<String> headers = List.of("设备编号", "设备名称", "规格型号", "操作类型", "出入库类型", "配件数量", "出入库时间", "出入库人员", "备注");
        List<List<String>> data = rows.stream().map(v -> List.of(v.equipmentCode(), v.equipmentName(), text(v.model()),
                v.operationType(), v.movementType(), v.accessoryQuantity().toPlainString(), v.occurredAt().toString(), v.personName(), text(v.remark()))).toList();
        return auditedExport("EQUIPMENT_MOVEMENTS_EXPORTED", "EQUIPMENT_MOVEMENT", "设备出入库受控导出", headers, data, query);
    }

    @Transactional
    public ImportResult importEquipment(byte[] content, String filename) {
        validateEnvelope(content, filename);
        List<String> errors = new ArrayList<>();
        List<EquipmentPending> pending = parseEquipment(content, errors);
        reject(errors, pending.isEmpty(), "EQUIPMENT_IMPORT_VALIDATION_FAILED");
        for (EquipmentPending row : pending) equipment.create(new EquipmentService.Write(row.code(), row.asset(), row.name(), row.model(),
                row.categoryId(), row.purchased(), row.price(), row.manufacturer(), row.serial(), row.cycle(), row.verificationType(),
                row.technical(), row.nominalRange(), row.measurementRange(), row.resolution(), row.accuracy(), row.custodianId(),
                row.primary(), row.enabled(), row.performance(), row.location(), row.equipmentType(), row.pileType(), null, null,
                row.remark(), List.of(), 0));
        ImportResult result = new ImportResult(pending.size(), EQUIPMENT_TEMPLATE_VERSION, sha256(content));
        audit.record("EQUIPMENT_IMPORTED", "EQUIPMENT_IMPORT", result.sha256(), null, result, null, filename);
        return result;
    }

    @Transactional
    public ImportResult importCertificates(byte[] content, String filename) {
        validateEnvelope(content, filename);
        List<String> errors = new ArrayList<>();
        List<CertificatePending> pending = parseCertificates(content, errors);
        reject(errors, pending.isEmpty(), "EQUIPMENT_CERTIFICATE_IMPORT_VALIDATION_FAILED");
        for (CertificatePending row : pending) equipment.addCertificate(row.equipmentId(), new EquipmentService.CertificateWrite(
                row.certificateType(), row.inspectionType(), row.number(), row.issuedOn(), row.validUntil(),
                row.confirmedOn(), row.result(), row.organization(), null, row.remark(), 0));
        ImportResult result = new ImportResult(pending.size(), CERTIFICATE_TEMPLATE_VERSION, sha256(content));
        audit.record("EQUIPMENT_CERTIFICATES_IMPORTED", "EQUIPMENT_CERTIFICATE_IMPORT", result.sha256(), null, result, null, filename);
        return result;
    }

    public Fingerprint fingerprint(byte[] content, String filename) { return new Fingerprint(filename == null ? "" : filename, sha256(content)); }

    private List<EquipmentPending> parseEquipment(byte[] content, List<String> errors) {
        Map<String, UUID> categories = jdbc.sql("SELECT lower(code),id FROM equipment_category WHERE organization_id=:organization AND active")
                .param("organization", actor.require().organizationId()).query((rs, row) -> Map.entry(rs.getString(1), rs.getObject(2, UUID.class)))
                .list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, UUID> people = jdbc.sql("""
                SELECT lower(p.employee_number),p.id FROM person p JOIN department d ON d.id=p.department_id
                WHERE d.organization_id=:organization AND p.active
                """).param("organization", actor.require().organizationId())
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getObject(2, UUID.class))).list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Set<String> existingCodes = new HashSet<>(jdbc.sql("SELECT lower(code) FROM equipment").query(String.class).list());
        Set<String> existingAssets = new HashSet<>(jdbc.sql("SELECT lower(asset_number) FROM equipment WHERE asset_number IS NOT NULL").query(String.class).list());
        Set<String> fileCodes = new HashSet<>(); Set<String> fileAssets = new HashSet<>();
        List<EquipmentPending> result = new ArrayList<>();
        try (XSSFWorkbook workbook = open(content)) {
            Sheet sheet = sheet(workbook, "设备导入", EQUIPMENT_TEMPLATE_VERSION, errors);
            if (sheet == null) return result;
            Map<String, Integer> columns = columns(sheet.getRow(1), EQUIPMENT_HEADERS, errors);
            for (int index = 2, count = 0; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index); if (row == null || blank(row, columns.values())) continue;
                if (++count > MAX_ROWS) { errors.add("数据行超过上限 " + MAX_ROWS); break; }
                int number = index + 1;
                try {
                    String code = required(row, columns, "设备编号", 64);
                    String normalizedCode = code.toLowerCase(Locale.ROOT);
                    if (!fileCodes.add(normalizedCode)) throw new IllegalArgumentException("文件内设备编号重复：" + code);
                    if (existingCodes.contains(normalizedCode)) throw new IllegalArgumentException("设备编号已存在（已存在打回）：" + code);
                    String asset = optional(row, columns, "固定资产编号", 80);
                    if (asset != null && (!fileAssets.add(asset.toLowerCase(Locale.ROOT)) || existingAssets.contains(asset.toLowerCase(Locale.ROOT)))) {
                        throw new IllegalArgumentException("固定资产编号重复或已存在：" + asset);
                    }
                    UUID category = lookup(optional(row, columns, "所属分类编码", 64), categories, "设备分类");
                    UUID custodian = lookup(optional(row, columns, "保管人工号", 40), people, "保管人");
                    result.add(new EquipmentPending(code, asset, required(row, columns, "设备名称", 200),
                            required(row, columns, "规格型号", 120), category, date(row, columns, "购置日期", false),
                            decimal(row, columns, "购置单价(元)", false), optional(row, columns, "生产厂家", 240),
                            optional(row, columns, "出厂编号", 120), integer(row, columns, "检定周期(月)"),
                            alias(row, columns, "检校类型", VERIFICATION, false), optional(row, columns, "技术指标", 1000),
                            optional(row, columns, "量程", 240), optional(row, columns, "测量范围", 240),
                            optional(row, columns, "分度值", 120), optional(row, columns, "准确度", 120), custodian,
                            bool(row, columns, "主要设备", false), bool(row, columns, "启停状态", true),
                            performance(row, columns), optional(row, columns, "存放位置", 160),
                            alias(row, columns, "设备类型", EQUIPMENT_TYPE, false),
                            alias(row, columns, "桩基设备类型", PILE_TYPE, false), optional(row, columns, "备注", 2000)));
                } catch (IllegalArgumentException exception) { errors.add("第 " + number + " 行：" + exception.getMessage()); }
            }
        } catch (IOException | RuntimeException exception) { errors.add("工作簿无法安全解析：" + safe(exception)); }
        return result;
    }

    private List<CertificatePending> parseCertificates(byte[] content, List<String> errors) {
        Map<String, UUID> devices = jdbc.sql("SELECT lower(code),id FROM equipment WHERE organization_id=:organization AND state<>'SCRAPPED'")
                .param("organization", actor.require().organizationId()).query((rs, row) -> Map.entry(rs.getString(1), rs.getObject(2, UUID.class)))
                .list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Set<String> keys = new HashSet<>();
        List<CertificatePending> result = new ArrayList<>();
        try (XSSFWorkbook workbook = open(content)) {
            Sheet sheet = sheet(workbook, "设备检定导入", CERTIFICATE_TEMPLATE_VERSION, errors);
            if (sheet == null) return result;
            Map<String, Integer> columns = columns(sheet.getRow(1), CERTIFICATE_HEADERS, errors);
            for (int index = 2, count = 0; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index); if (row == null || blank(row, columns.values())) continue;
                if (++count > MAX_ROWS) { errors.add("数据行超过上限 " + MAX_ROWS); break; }
                int number = index + 1;
                try {
                    String code = required(row, columns, "设备编号", 64);
                    UUID equipmentId = devices.get(code.toLowerCase(Locale.ROOT));
                    if (equipmentId == null) throw new IllegalArgumentException("设备编号不存在：" + code);
                    String certificateType = certificateType(row, columns);
                    String certificateNumber = required(row, columns, "检校编号", 120);
                    String key = equipmentId + "|" + certificateType + "|" + certificateNumber.toLowerCase(Locale.ROOT);
                    if (!keys.add(key)) throw new IllegalArgumentException("文件内证书重复：" + certificateNumber);
                    boolean exists = jdbc.sql("""
                            SELECT EXISTS(SELECT 1 FROM equipment_certificate WHERE equipment_id=:equipment
                              AND certificate_type=:type AND lower(certificate_number)=lower(:number))
                            """).param("equipment", equipmentId).param("type", certificateType).param("number", certificateNumber)
                            .query(Boolean.class).single();
                    if (exists) throw new IllegalArgumentException("证书已存在（已存在打回）：" + certificateNumber);
                    LocalDate issued = date(row, columns, "检定日期", true);
                    LocalDate valid = date(row, columns, "有效期至", true);
                    if (valid.isBefore(issued)) throw new IllegalArgumentException("有效期不能早于检定日期");
                    result.add(new CertificatePending(equipmentId, certificateType,
                            alias(row, columns, "检校类型", VERIFICATION, false), certificateNumber, issued, valid,
                            date(row, columns, "确认日期", false), required(row, columns, "检校单位", 240),
                            alias(row, columns, "检校结论", RESULT, true), optional(row, columns, "备注", 2000)));
                } catch (IllegalArgumentException exception) { errors.add("第 " + number + " 行：" + exception.getMessage()); }
            }
        } catch (IOException | RuntimeException exception) { errors.add("工作簿无法安全解析：" + safe(exception)); }
        return result;
    }

    private ExportFile workbook(String version, List<String> headers, List<List<String>> data, boolean export, String sheetName) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName);
            Row marker = sheet.createRow(0);
            marker.createCell(0).setCellValue(version);
            marker.createCell(1).setCellValue(export
                    ? "内部受控导出 · " + actor.require().displayName() + " · "
                    + OffsetDateTime.now().atZoneSameInstant(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    : "受控模板：整文件事务导入，任一行错误则全部拒绝；已存在记录打回");
            CellStyle markerStyle = workbook.createCellStyle(); Font markerFont = workbook.createFont();
            markerFont.setBold(true); markerFont.setColor((short) 10); markerStyle.setFont(markerFont);
            marker.getCell(0).setCellStyle(markerStyle); marker.getCell(1).setCellStyle(markerStyle);
            Row header = sheet.createRow(1); CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont(); headerFont.setBold(true); headerStyle.setFont(headerFont);
            for (int column = 0; column < headers.size(); column++) {
                Cell cell = header.createCell(column); cell.setCellValue(headers.get(column)); cell.setCellStyle(headerStyle);
            }
            for (int index = 0; index < data.size(); index++) {
                Row row = sheet.createRow(index + 2); List<String> values = data.get(index);
                for (int column = 0; column < values.size(); column++) row.createCell(column).setCellValue(values.get(column));
            }
            for (int column = 0; column < headers.size(); column++) sheet.setColumnWidth(column, Math.min(40, Math.max(14, headers.get(column).length() * 2 + 4)) * 256);
            sheet.createFreezePane(0, 2);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(1, Math.max(1, data.size() + 1), 0, headers.size() - 1));
            workbook.write(output);
            return new ExportFile(output.toByteArray(), sheetName + (export ? "-受控导出.xlsx" : "-受控导入模板-V1.xlsx"), data.size());
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EQUIPMENT_WORKBOOK_FAILED", "设备工作簿生成失败");
        }
    }

    private <T> List<T> collect(java.util.function.Function<String, CursorPage<T>> loader) {
        List<T> rows = new ArrayList<>(); String cursor = null;
        do {
            CursorPage<T> page = loader.apply(cursor); rows.addAll(page.items());
            if (rows.size() > MAX_ROWS) throw tooLarge();
            cursor = page.hasMore() ? page.nextCursor() : null;
        } while (cursor != null);
        return rows;
    }

    private ExportFile auditedExport(String action, String resource, String sheetName, List<String> headers,
                                     List<List<String>> data, String query) {
        ExportFile result = workbook("LIMS_CONTROLLED_EXPORT_V1", headers, data, true, sheetName);
        audit.record(action, resource, null, null, Map.of("rowCount", data.size()), null, query);
        return result;
    }

    private XSSFWorkbook open(byte[] content) throws IOException {
        ZipSecureFile.setMinInflateRatio(0.01d); ZipSecureFile.setMaxEntrySize(MAX_BYTES); ZipSecureFile.setMaxTextSize(MAX_BYTES);
        return new XSSFWorkbook(new ByteArrayInputStream(content));
    }

    private Sheet sheet(XSSFWorkbook workbook, String name, String version, List<String> errors) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) { errors.add("缺少名为“" + name + "”的数据工作表"); return null; }
        if (!version.equals(cell(sheet.getRow(0), 0))) errors.add("模板版本不匹配，请重新下载受控导入模板");
        return sheet;
    }

    private Map<String, Integer> columns(Row row, List<String> required, List<String> errors) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (row != null) for (int column = 0; column < row.getLastCellNum(); column++) {
            String value = cell(row.getCell(column)); if (value.isBlank()) continue;
            if (result.putIfAbsent(value, column) != null) errors.add("字段名重复：" + value);
        }
        for (String header : required) if (!result.containsKey(header)) errors.add("缺少必需列：" + header);
        return result;
    }

    private String required(Row row, Map<String, Integer> columns, String header, int max) {
        String value = optional(row, columns, header, max);
        if (value == null) throw new IllegalArgumentException(header + "不能为空");
        return value;
    }
    private String optional(Row row, Map<String, Integer> columns, String header, int max) {
        Integer column = columns.get(header); String value = column == null ? "" : cell(row.getCell(column));
        if (value.isBlank()) return null;
        if (value.length() > max) throw new IllegalArgumentException(header + "超过最大长度 " + max);
        return value;
    }
    private String alias(Row row, Map<String, Integer> columns, String header, Map<String, String> aliases, boolean required) {
        String raw = optional(row, columns, header, 80);
        if (raw == null) { if (required) throw new IllegalArgumentException(header + "不能为空"); return null; }
        String value = aliases.get(raw.toUpperCase(Locale.ROOT));
        if (value == null && aliases.containsValue(raw.toUpperCase(Locale.ROOT))) value = raw.toUpperCase(Locale.ROOT);
        if (value == null) throw new IllegalArgumentException(header + "无效：" + raw);
        return value;
    }
    private String certificateType(Row row, Map<String, Integer> columns) {
        String raw = required(row, columns, "证书类型", 40);
        if (Set.of("检定", "VERIFICATION").contains(raw.toUpperCase(Locale.ROOT))) return "VERIFICATION";
        if (Set.of("校准", "CALIBRATION").contains(raw.toUpperCase(Locale.ROOT))) return "CALIBRATION";
        throw new IllegalArgumentException("证书类型无效：" + raw);
    }
    private String performance(Row row, Map<String, Integer> columns) {
        String raw = required(row, columns, "性能状态", 16);
        if (Set.of("正常", "NORMAL").contains(raw.toUpperCase(Locale.ROOT))) return "NORMAL";
        if (Set.of("异常", "ABNORMAL").contains(raw.toUpperCase(Locale.ROOT))) return "ABNORMAL";
        throw new IllegalArgumentException("性能状态无效：" + raw);
    }
    private boolean bool(Row row, Map<String, Integer> columns, String header, boolean defaultValue) {
        String raw = optional(row, columns, header, 16); if (raw == null) return defaultValue;
        if (Set.of("是", "启用", "TRUE", "1").contains(raw.toUpperCase(Locale.ROOT))) return true;
        if (Set.of("否", "停用", "FALSE", "0").contains(raw.toUpperCase(Locale.ROOT))) return false;
        throw new IllegalArgumentException(header + "无效：" + raw);
    }
    private UUID lookup(String code, Map<String, UUID> values, String label) {
        if (code == null) return null; UUID id = values.get(code.toLowerCase(Locale.ROOT));
        if (id == null) throw new IllegalArgumentException(label + "编码不存在：" + code);
        return id;
    }
    private Integer integer(Row row, Map<String, Integer> columns, String header) {
        BigDecimal value = decimal(row, columns, header, false);
        if (value == null) return null;
        try { int result = value.intValueExact(); if (result <= 0) throw new ArithmeticException(); return result; }
        catch (ArithmeticException exception) { throw new IllegalArgumentException(header + "必须为大于0的整数"); }
    }
    private BigDecimal decimal(Row row, Map<String, Integer> columns, String header, boolean required) {
        String raw = optional(row, columns, header, 60);
        if (raw == null) { if (required) throw new IllegalArgumentException(header + "不能为空"); return null; }
        try { BigDecimal value = new BigDecimal(raw); if (value.signum() < 0) throw new NumberFormatException(); return value; }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(header + "必须为非负数字"); }
    }
    private LocalDate date(Row row, Map<String, Integer> columns, String header, boolean required) {
        Integer column = columns.get(header); Cell value = column == null ? null : row.getCell(column);
        if (value == null || value.getCellType() == CellType.BLANK) {
            if (required) throw new IllegalArgumentException(header + "不能为空"); return null;
        }
        if (value.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(value)) return value.getLocalDateTimeCellValue().toLocalDate();
        String raw = cell(value);
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("yyyy/M/d"), DateTimeFormatter.ofPattern("yyyy.MM.dd"))) {
            try { return LocalDate.parse(raw, formatter); } catch (DateTimeParseException ignored) { }
        }
        throw new IllegalArgumentException(header + "格式无效，应为 YYYY-MM-DD");
    }
    private boolean blank(Row row, Iterable<Integer> columns) { for (Integer column : columns) if (!cell(row.getCell(column)).isBlank()) return false; return true; }
    private String cell(Row row, int column) { return row == null ? "" : cell(row.getCell(column)); }
    private String cell(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return "";
        if (cell.getCellType() == CellType.FORMULA) throw new IllegalArgumentException("导入文件不允许公式单元格");
        if (cell.getCellType() == CellType.NUMERIC) return NumberToTextConverter.toText(cell.getNumericCellValue());
        if (cell.getCellType() == CellType.BOOLEAN) return Boolean.toString(cell.getBooleanCellValue());
        if (cell.getCellType() == CellType.ERROR) throw new IllegalArgumentException("导入文件包含错误单元格");
        return cell.getStringCellValue().trim();
    }
    private void validateEnvelope(byte[] content, String filename) {
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw invalid("EQUIPMENT_IMPORT_FORMAT_INVALID", "仅允许受控 .xlsx 导入文件");
        if (content == null || content.length == 0 || content.length > MAX_BYTES) throw invalid("EQUIPMENT_IMPORT_SIZE_INVALID", "导入文件须大于0且不超过20MB");
        if (content.length < 4 || content[0] != 'P' || content[1] != 'K') throw invalid("EQUIPMENT_IMPORT_HEADER_INVALID", "导入文件不是有效 .xlsx");
    }
    private void reject(List<String> errors, boolean empty, String code) {
        if (!errors.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, code, "受控导入校验失败，未写入任何记录", null, errors.stream().limit(200).toList(), Map.of());
        if (empty) throw invalid("EQUIPMENT_IMPORT_EMPTY", "导入文件没有有效数据行");
    }
    private String sha256(byte[] content) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private String safe(Exception exception) { return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(); }
    private String text(String value) { return value == null ? "" : value; }
    private ApiException tooLarge() { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EQUIPMENT_EXPORT_TOO_LARGE", "当前筛选结果超过10000条，请缩小范围后导出"); }
    private ApiException invalid(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static Map<String, String> aliases(String... entries) {
        Map<String, String> result = new HashMap<>();
        for (int index = 0; index < entries.length; index += 2) result.put(entries[index].toUpperCase(Locale.ROOT), entries[index + 1]);
        return Map.copyOf(result);
    }
}
