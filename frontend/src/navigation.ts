export interface NavigationItem {
  key: string
  label: string
  external?: boolean
}

export interface NavigationGroup {
  key: string
  label: string
  items: NavigationItem[]
}

export interface NavigationDomain {
  key: string
  label: string
  groups: NavigationGroup[]
}

export const navigation: NavigationDomain[] = [
  { key: 'foundation', label: '基础数据', groups: [
    { key: 'people-management', label: '人员管理', items: [
      { key: 'people', label: '人员信息' }, { key: 'qualifications', label: '人员资格' },
      { key: 'training', label: '培训信息' }, { key: 'project-groups', label: '项目组管理' },
      { key: 'titles', label: '人员职称' },
    ] },
    { key: 'organization-management', label: '单位管理', items: [
      { key: 'organizations', label: '单位信息' }, { key: 'departments', label: '部门配置' },
      { key: 'organization-qualifications', label: '单位资质' }, { key: 'metrology-qualifications', label: '计量资质' },
      { key: 'shelves', label: '货架管理' },
    ] },
    { key: 'detection-settings', label: '检测设定', items: [
      { key: 'parameters', label: '检测参数' }, { key: 'parameter-prices', label: '参数价格管理' },
      { key: 'standards', label: '标准库' }, { key: 'numbering-flow-rules', label: '流水规则管理' },
    ] },
    { key: 'electronic-seals', label: '电子签章', items: [
      { key: 'institution-seals', label: '机构签章' }, { key: 'person-signatures', label: '人员签章' },
      { key: 'signature-schemes', label: '签章方案' }, { key: 'identity-intent', label: '核身意愿' },
    ] },
  ] },
  { key: 'equipment', label: '设备物质', groups: [
    { key: 'equipment-management', label: '设备管理', items: [
      { key: 'equipment', label: '设备管理' }, { key: 'equipment-certificates', label: '设备检定' },
      { key: 'equipment-service', label: '设备送检' }, { key: 'equipment-borrow', label: '领用归还' },
      { key: 'equipment-usage', label: '使用记录' }, { key: 'equipment-calibration', label: '设备校准' },
      { key: 'equipment-movements', label: '出入库记录' },
    ] },
  ] },
  { key: 'business', label: '检测业务', groups: [
    { key: 'business-management', label: '业务管理', items: [
      { key: 'projects', label: '工程信息' }, { key: 'customers', label: '客户信息' },
    ] },
    { key: 'commission-management', label: '委托管理', items: [
      { key: 'commissions', label: '委托管理' }, { key: 'precommissions', label: '预委托受理' },
      { key: 'commission-change-requests', label: '委托修改申请' },
    ] },
    { key: 'task-circulation', label: '任务流转', items: [
      { key: 'tasks', label: '任务分配' }, { key: 'task-return-records', label: '任务退回记录' },
    ] },
    { key: 'test-management', label: '试验检测', items: [
      { key: 'tests', label: '试验检测' }, { key: 'test-return-records', label: '试验退回记录' },
      { key: 'data-change-requests', label: '数据修改申请' },
    ] },
    { key: 'report-management', label: '报告管理', items: [
      { key: 'report-editing', label: '报告流转' }, { key: 'report-review', label: '报告审核' },
      { key: 'report-issue', label: '报告签发' }, { key: 'report-seal', label: '报告签章' },
      { key: 'report-distribution', label: '报告发放' }, { key: 'report-print', label: '报告打印' },
      { key: 'report-archive', label: '报告存档' }, { key: 'void-reports', label: '作废报告' },
      { key: 'report-change-requests', label: '变更申请' }, { key: 'report-return-records', label: '退回记录' },
      { key: 'nonconformance-notices', label: '不合格通知' },
    ] },
  ] },
  { key: 'ledgers', label: '台账管理', groups: [
    { key: 'sample-ledgers', label: '样品台账', items: [
      { key: 'concrete-sample-ledger', label: '混凝土台账' }, { key: 'sample-ledger', label: '样品总台账' },
      { key: 'management-ledger', label: '管理台账' }, { key: 'retained-sample-ledger', label: '留样台账' },
      { key: 'sample-age-schedule', label: '样品龄期表' },
    ] },
    { key: 'test-ledgers', label: '检测台账', items: [
      { key: 'test-ledger', label: '试验台账' }, { key: 'report-ledger', label: '报告台账' },
      { key: 'overdue-specimen-ledger', label: '试块逾期台账' }, { key: 'due-specimen-ledger', label: '试块到期台账' },
      { key: 'stage-summary', label: '阶段综合汇总' }, { key: 'comprehensive-reports', label: '综合报告' },
      { key: 'rebound-assessment', label: '回弹评定单' }, { key: 'concrete-strength-assessment', label: '混凝土强度评定' },
      { key: 'report-search', label: '报告查询' },
    ] },
    { key: 'nonconformance-ledgers', label: '不合格台账', items: [
      { key: 'nonconforming-samples', label: '不合格样品' }, { key: 'nonconforming-concrete-ledger', label: '混凝土台账' },
      { key: 'nonconforming-reports', label: '不合格报告' }, { key: 'nonconforming-field-tests', label: '现场检测' },
      { key: 'nonconforming-report-handling', label: '不合格报告处理' },
    ] },
    { key: 'warning-information', label: '预警信息', items: [{ key: 'warning-records', label: '预警记录' }] },
  ] },
  { key: 'iot', label: '现场物联', groups: [
    { key: 'field-testing', label: '现场检测', items: [{ key: 'field-test-records', label: '检测记录' }] },
    { key: 'equipment-iot', label: '设备物联', items: [
      { key: 'iot-temperature-humidity', label: '温湿度仪', external: true },
      { key: 'iot-video', label: '视频监控', external: true },
      { key: 'iot-pressure-machine', label: '压力机数据', external: true },
      { key: 'iot-universal-machine', label: '万能机数据', external: true },
      { key: 'iot-rebound-tester', label: '回弹仪数据', external: true },
    ] },
    { key: 'static-load-data', label: '静载数据', items: [
      { key: 'static-load-monitoring', label: '数据监控', external: true },
      { key: 'static-load-management', label: '数据管理', external: true },
      { key: 'static-load-unknown', label: '未知数据', external: true },
      { key: 'static-load-devices', label: '在线设备', external: true },
    ] },
  ] },
  { key: 'finance', label: '合同财务', groups: [
    { key: 'contract-management', label: '合同管理', items: [
      { key: 'contracts', label: '合同管理' }, { key: 'my-projects', label: '我的项目' },
      { key: 'contract-price-templates', label: '合同价模板' },
    ] },
    { key: 'financial-management', label: '财务管理', items: [
      { key: 'fee-statements', label: '费用清单' }, { key: 'receipts', label: '收款管理' },
      { key: 'payments', label: '付款管理' },
    ] },
    { key: 'payment-orders', label: '充值缴费', items: [{ key: 'payment-order-records', label: '订单记录', external: true }] },
    { key: 'contract-analysis', label: '账表分析', items: [{ key: 'contract-analysis', label: '合同分析' }] },
  ] },
  { key: 'system', label: '系统设置', groups: [
    { key: 'system-foundation', label: '基础数据', items: [
      { key: 'dictionaries', label: '数据字典' }, { key: 'permissions', label: '权限配置' },
      { key: 'report-templates', label: '报告版本管理' }, { key: 'commission-templates', label: '委托单版本管理' },
      { key: 'task-templates', label: '任务单版本管理' }, { key: 'notifications', label: '消息通知配置' },
      { key: 'numbering-rules', label: '编号配置规则' }, { key: 'desktop-themes', label: '桌面风格管理' },
      { key: 'safety-briefings', label: '安全交底配置' }, { key: 'template-numbering', label: '模板编号配置' },
      { key: 'transition-time-settings', label: '流转时间设置' },
    ] },
    { key: 'system-logs', label: '日志信息', items: [{ key: 'audit', label: '操作日志' }] },
  ] },
]

export function moduleLabel(key: string): string {
  return navigation.flatMap((domain) => domain.groups).flatMap((group) => group.items)
    .find((item) => item.key === key)?.label ?? '业务模块'
}

export function domainForModule(key: string): NavigationDomain | undefined {
  return navigation.find((domain) => domain.groups.some((group) => group.items.some((item) => item.key === key)))
}
