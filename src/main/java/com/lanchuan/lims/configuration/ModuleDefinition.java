package com.lanchuan.lims.configuration;

import com.lanchuan.lims.common.ApiException;

import java.util.Arrays;

public enum ModuleDefinition {
    PROJECTS("projects", "工程信息", "工程与项目责任信息"),
    CUSTOMERS("customers", "客户信息", "委托客户和联系人"),
    COMMISSIONS("commissions", "委托管理", "受控委托及受理状态"),
    PRECOMMISSIONS("precommissions", "预委托受理", "预委托登记与受理"),
    SAMPLES("samples", "样品管理", "样品接收、交接与处置"),
    TASKS("tasks", "任务分配", "检测任务分配、接收与改派"),
    TESTS("tests", "试验检测", "结构化试验记录与复测"),
    REPORT_EDITING("report-editing", "报告编制", "受控报告内容编制"),
    REPORT_REVIEW("report-review", "报告审核", "报告审核与退回"),
    REPORT_ISSUE("report-issue", "报告签发", "报告签发与内部签名"),
    REPORT_SEAL("report-seal", "签章发放", "机构章、发放与签收"),
    REPORT_ARCHIVE("report-archive", "报告归档", "报告及证据链归档"),
    REPORT_REVISION("report-revision", "修订与作废", "签发报告修订和作废"),
    PEOPLE("people", "人员信息", "人员、职称与在岗状态"),
    ORGANIZATIONS("organizations", "单位信息", "机构及分支单位"),
    DEPARTMENTS("departments", "部门配置", "部门层级与数据范围"),
    PARAMETERS("parameters", "检测参数", "版本化检测字段和公式"),
    EQUIPMENT("equipment", "设备管理", "设备主数据与状态"),
    EQUIPMENT_CERTIFICATES("equipment-certificates", "设备检定", "设备检定证书和有效期"),
    EQUIPMENT_SERVICE("equipment-service", "设备送检", "设备送检与返场"),
    EQUIPMENT_BORROW("equipment-borrow", "领用归还", "设备领用与归还"),
    EQUIPMENT_USAGE("equipment-usage", "使用记录", "设备使用及关联任务"),
    EQUIPMENT_CALIBRATION("equipment-calibration", "设备校准", "设备校准证书和有效期"),
    EQUIPMENT_MOVEMENTS("equipment-movements", "出入库记录", "设备出入库追踪"),
    SAMPLE_LEDGER("sample-ledger", "样品总台账", "只读样品全生命周期台账"),
    TEST_LEDGER("test-ledger", "试验台账", "只读试验记录台账"),
    REPORT_LEDGER("report-ledger", "报告台账", "只读报告版本台账"),
    NONCONFORMING_SAMPLES("nonconforming-samples", "不合格样品", "不合格样品及处置"),
    NONCONFORMING_REPORTS("nonconforming-reports", "不合格报告", "不合格报告及CAPA"),
    DICTIONARIES("dictionaries", "数据字典", "版本化业务字典"),
    PERMISSIONS("permissions", "权限配置", "角色、动作和数据范围"),
    REPORT_TEMPLATES("report-templates", "报告版本管理", "报告模板版本"),
    COMMISSION_TEMPLATES("commission-templates", "委托单版本管理", "委托单模板版本"),
    TASK_TEMPLATES("task-templates", "任务单版本管理", "任务单模板版本"),
    NOTIFICATIONS("notifications", "消息通知配置", "站内通知触发规则"),
    NUMBERING_RULES("numbering-rules", "编号配置规则", "业务编号并发规则"),
    TEMPLATE_NUMBERING("template-numbering", "模板编号配置", "受控模板编号"),
    AUDIT("audit", "操作日志", "不可变结构化审计事件"),
    QUALIFICATIONS("qualifications", "人员资格", "人员资格、证书和有效期"),
    TRAINING("training", "培训信息", "人员培训计划与完成记录"),
    PROJECT_GROUPS("project-groups", "项目组管理", "项目成员和职责分工"),
    TITLES("titles", "人员职称", "职称与专业技术等级"),
    ORGANIZATION_QUALIFICATIONS("organization-qualifications", "单位资质", "单位资质和有效期"),
    METROLOGY_QUALIFICATIONS("metrology-qualifications", "计量资质", "计量认证范围和有效期"),
    SHELVES("shelves", "货架管理", "样品货架、库位和容量"),
    PARAMETER_PRICES("parameter-prices", "参数价格管理", "检测项目与参数计价配置"),
    STANDARDS("standards", "标准库", "检测依据和标准版本"),
    NUMBERING_FLOW_RULES("numbering-flow-rules", "流水规则管理", "业务流水号规则"),
    INSTITUTION_SEALS("institution-seals", "机构签章", "机构印章素材和使用权限"),
    PERSON_SIGNATURES("person-signatures", "人员签章", "人员签名素材和使用权限"),
    SIGNATURE_SCHEMES("signature-schemes", "签章方案", "报告签名与印章位置方案"),
    IDENTITY_INTENT("identity-intent", "核身意愿", "高风险签署意愿和近期复核"),
    COMMISSION_CHANGE_REQUESTS("commission-change-requests", "委托修改申请", "委托受理后的受控变更"),
    TASK_RETURN_RECORDS("task-return-records", "任务退回记录", "任务拒绝与退回证据"),
    TEST_RETURN_RECORDS("test-return-records", "试验退回记录", "试验记录退回与复测证据"),
    DATA_CHANGE_REQUESTS("data-change-requests", "数据修改申请", "检测数据受控修改与新修订"),
    REPORT_DISTRIBUTION("report-distribution", "报告发放", "报告受控发放和签收"),
    REPORT_PRINT("report-print", "报告打印", "报告受控打印和下载审计"),
    VOID_REPORTS("void-reports", "作废报告", "报告作废与版本链"),
    REPORT_CHANGE_REQUESTS("report-change-requests", "变更申请", "报告修订申请"),
    REPORT_RETURN_RECORDS("report-return-records", "退回记录", "报告审核退回记录"),
    NONCONFORMANCE_NOTICES("nonconformance-notices", "不合格通知", "不合格通知与处置跟踪"),
    CONCRETE_SAMPLE_LEDGER("concrete-sample-ledger", "混凝土台账", "混凝土样品台账"),
    MANAGEMENT_LEDGER("management-ledger", "管理台账", "样品管理状态汇总"),
    RETAINED_SAMPLE_LEDGER("retained-sample-ledger", "留样台账", "留样期限和处置台账"),
    SAMPLE_AGE_SCHEDULE("sample-age-schedule", "样品龄期表", "试件龄期和到期计划"),
    OVERDUE_SPECIMEN_LEDGER("overdue-specimen-ledger", "试块逾期台账", "逾期试块和责任跟踪"),
    DUE_SPECIMEN_LEDGER("due-specimen-ledger", "试块到期台账", "即将到期试块计划"),
    STAGE_SUMMARY("stage-summary", "阶段综合汇总", "检测阶段综合统计"),
    COMPREHENSIVE_REPORTS("comprehensive-reports", "综合报告", "检测业务综合报告"),
    REBOUND_ASSESSMENT("rebound-assessment", "回弹评定单", "回弹检测评定记录"),
    CONCRETE_STRENGTH_ASSESSMENT("concrete-strength-assessment", "混凝土强度评定", "混凝土强度统计评定"),
    REPORT_SEARCH("report-search", "报告查询", "报告版本和归档检索"),
    NONCONFORMING_CONCRETE_LEDGER("nonconforming-concrete-ledger", "混凝土台账", "不合格混凝土记录"),
    NONCONFORMING_FIELD_TESTS("nonconforming-field-tests", "现场检测", "现场检测不符合记录"),
    NONCONFORMING_REPORT_HANDLING("nonconforming-report-handling", "不合格报告处理", "不合格报告 CAPA 处置"),
    WARNING_RECORDS("warning-records", "预警记录", "资格、设备、时限和质量预警"),
    FIELD_TEST_RECORDS("field-test-records", "检测记录", "现场检测软件记录"),
    IOT_TEMPERATURE_HUMIDITY("iot-temperature-humidity", "温湿度仪", "温湿度设备接入"),
    IOT_VIDEO("iot-video", "视频监控", "视频平台接入"),
    IOT_PRESSURE_MACHINE("iot-pressure-machine", "压力机数据", "压力机数据接入"),
    IOT_UNIVERSAL_MACHINE("iot-universal-machine", "万能机数据", "万能试验机数据接入"),
    IOT_REBOUND_TESTER("iot-rebound-tester", "回弹仪数据", "回弹仪数据接入"),
    STATIC_LOAD_MONITORING("static-load-monitoring", "数据监控", "静载数据实时监控"),
    STATIC_LOAD_MANAGEMENT("static-load-management", "数据管理", "静载数据管理"),
    STATIC_LOAD_UNKNOWN("static-load-unknown", "未知数据", "未识别静载数据隔离"),
    STATIC_LOAD_DEVICES("static-load-devices", "在线设备", "静载在线设备"),
    CONTRACTS("contracts", "合同管理", "合同版本、金额和履约状态"),
    MY_PROJECTS("my-projects", "我的项目", "本人参与项目和合同"),
    CONTRACT_PRICE_TEMPLATES("contract-price-templates", "合同价模板", "合同计价模板版本"),
    FEE_STATEMENTS("fee-statements", "费用清单", "合同费用和检测项目清单"),
    RECEIPTS("receipts", "收款管理", "收款登记和核销"),
    PAYMENTS("payments", "付款管理", "付款申请和审批"),
    PAYMENT_ORDER_RECORDS("payment-order-records", "订单记录", "外部支付订单接入"),
    CONTRACT_ANALYSIS("contract-analysis", "合同分析", "合同金额、履约和回款分析"),
    DESKTOP_THEMES("desktop-themes", "桌面风格管理", "桌面布局和视觉配置"),
    SAFETY_BRIEFINGS("safety-briefings", "安全交底配置", "安全交底模板和确认规则"),
    TRANSITION_TIME_SETTINGS("transition-time-settings", "流转时间设置", "业务流转时限和预警"),
    QUALITY_EXCEPTIONS("quality-exceptions", "质量例外", "限时、独立审批的质量例外");

    private final String key;
    private final String title;
    private final String description;

    ModuleDefinition(String key, String title, String description) {
        this.key = key;
        this.title = title;
        this.description = description;
    }

    public String key() { return key; }
    public String title() { return title; }
    public String description() { return description; }

    public static ModuleDefinition require(String key) {
        return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst()
                .orElseThrow(() -> ApiException.notFound("MODULE_NOT_FOUND", "业务模块不存在"));
    }
}
