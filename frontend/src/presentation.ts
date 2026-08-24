const statusLabels: Record<string, string> = {
  ACCEPTED: '已受理', ACTIVE: '启用', INACTIVE: '停用', AVAILABLE: '可用', APPROVED: '已批准', APPLIED: '已应用',
  ARCHIVED: '已归档', ASSIGNED: '已派发', BLOCKED: '已阻塞', BORROWED: '已领用',
  CANCELLED: '已取消', CLOSED: '已关闭', COMPLETED: '已完成', CONFIRMED: '已确认',
  DISPOSITION_IN_PROGRESS: '处置中', DISTRIBUTED: '已发放', DRAFT: '草稿', EXPIRED: '已过期',
  FAILED: '失败', HISTORICAL: '历史', IN_PROGRESS: '进行中', IN_REVIEW: '审核中', ISSUED: '已签发',
  LOCKED: '已锁定', OPEN: '待质量确认', PAID: '已付款', PENDING: '待处理', PENDING_VERIFICATION: '待独立验证', PLANNED: '待执行',
  PRE_ACCEPTED: '待受理', PROCESSING: '处理中', PUBLISHED: '已发布', QUALITY_CONFIRMED: '已质量确认',
  READY: '待执行', REJECTED: '已拒绝', REPORTING: '报告编制中', REQUESTED: '待审批', RETIRED: '已停用', RETRY: '等待重试',
  RETURNED: '已退回', REVOKED: '已撤销', RUNNING: '执行中', SAMPLING: '收样中', SETTLED: '已结清', SUBMITTED: '已提交',
  SUPERSEDED: '已被新修订替代',
  TERMINATED: '已终止', TESTING: '检测中', UNASSIGNED: '待派发', VALID: '有效', VERIFIED: '已验证', VOID: '已作废', WITHDRAWN: '已撤回',
}

const sourceLabels: Record<string, string> = {
  COMMISSION: '委托', EQUIPMENT: '设备', REPORT: '报告', SAMPLE: '样品', TEST_RECORD: '试验记录',
  TEST_TASK: '检测任务', lims: '本系统', showcase_catalog: '公开示例配置',
}

const severityLabels: Record<string, string> = {
  LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重',
}

const actionTypeLabels: Record<string, string> = {
  CORRECTION: '即时纠正', CORRECTIVE: '纠正措施', PREVENTIVE: '预防措施',
}

const blockerLabels: Record<string, string> = {
  EQUIPMENT_NOT_VALID: '设备有效性不满足', PERSON_NOT_QUALIFIED: '人员资格不满足',
}

const jobTypeLabels: Record<string, string> = {
  EXPORT_COMMISSION_XLSX: '委托数据导出', EXPORT_DOMAIN_LEDGER_XLSX: '台账数据导出',
  EXPORT_FINANCE_XLSX: '财务数据导出', EXPORT_PERSONNEL_XLSX: '人员数据导出',
  EXPORT_QUALITY_LEDGER_XLSX: '质量台账导出', RENDER_COMMISSION_DOCUMENT: '电子委托单生成',
}

const integrationStateLabels: Record<string, string> = {
  READY: '已就绪', DISABLED: '未启用', CONFIGURATION_REQUIRED: '配置不完整', ADAPTER_UNAVAILABLE: '适配器未就绪',
}

const problemCodeLabels: Record<string, string> = {
  ACCESS_DENIED: '当前账号无权执行此操作', AUTHENTICATION_REQUIRED: '登录状态已失效，请重新登录',
  BUSINESS_RULE_BLOCKED: '当前业务条件尚未满足', CONFLICT: '记录状态已变化，请刷新后重试',
  CSRF_INVALID: '页面安全校验已失效，请刷新后重试', FORBIDDEN: '当前账号无权执行此操作',
  IDEMPOTENCY_CONFLICT: '本次操作与已提交请求不一致，请刷新后重试',
  IDEMPOTENCY_KEY_REQUIRED: '操作标识缺失，请刷新页面后重试', INVALID_CREDENTIALS: '账号或密码错误',
  METHOD_NOT_ALLOWED: '当前页面不支持此操作', NOT_FOUND: '请求的记录不存在或已不可用',
  REQUEST_FAILED: '操作未完成，请稍后重试', SESSION_REVOKED: '会话已被撤销，请重新登录',
  STATE_TRANSITION_NOT_ALLOWED: '当前状态不能执行此操作', UNAUTHORIZED: '登录状态已失效，请重新登录',
  VALIDATION_FAILED: '请检查填写内容', VERSION_CONFLICT: '记录已被其他人更新，请刷新后重试',
}

const fieldLabels: Record<string, string> = {
  action: '操作', code: '编号', comment: '说明', content: '内容', departmentId: '部门', description: '描述',
  displayName: '姓名', email: '电子邮箱', expectedVersion: '记录版本', id: '记录', name: '名称',
  password: '密码', phone: '联系电话', reason: '原因', reasonCode: '原因类型', sortOrder: '排序',
  state: '状态', title: '标题', username: '账号', version: '版本',
}

const technicalLabels: Record<string, string> = {
  ...statusLabels, ...sourceLabels, ...severityLabels, ...actionTypeLabels, ...blockerLabels,
  ...jobTypeLabels, ...integrationStateLabels,
}

const shanghaiDateFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
})
const shanghaiHourFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Shanghai', hour: '2-digit', hourCycle: 'h23',
})

function requiredLabel(values: Record<string, string>, value: string | null | undefined, kind: string): string {
  if (!value) return '—'
  const label = values[value]
  if (!label) throw new Error(`${kind}缺少中文显示映射：${value}`)
  return label
}

export const statusLabel = (value?: string | null) => requiredLabel(statusLabels, value, '状态')
export const sourceLabel = (value?: string | null) => requiredLabel(sourceLabels, value, '来源')
export const severityLabel = (value?: string | null) => requiredLabel(severityLabels, value, '严重度')
export const actionTypeLabel = (value?: string | null) => requiredLabel(actionTypeLabels, value, '措施类型')
export const blockerLabel = (value?: string | null) => requiredLabel(blockerLabels, value, '阻断类型')
export const jobTypeLabel = (value?: string | null) => requiredLabel(jobTypeLabels, value, '作业类型')
export const integrationStateLabel = (value?: string | null) => requiredLabel(integrationStateLabels, value, '集成状态')

function hasChinese(value: string): boolean { return /[\u3400-\u9fff]/.test(value) }

function fieldLabel(value: string): string {
  const leaf = value.split('.').at(-1)?.replace(/\[\d+]/g, '') ?? value
  return fieldLabels[leaf] ?? '相关字段'
}

function validationLabel(value: unknown): string {
  const text = String(value ?? '').trim()
  if (!text) return '填写内容不符合要求'
  const localizedSize = text.match(/(?:个数|长度|大小)必须在\s*(\d+)\s*和\s*(\d+)\s*之间/)
  if (localizedSize) return `长度应为 ${localizedSize[1]}–${localizedSize[2]} 个字符`
  if (/不能为\s*(?:null|空)|不允许为空/i.test(text)) return '不能为空'
  const localizedMin = text.match(/必须大于或等于\s*([\d.]+)/)
  if (localizedMin) return `不能小于 ${localizedMin[1]}`
  const localizedMax = text.match(/必须小于或等于\s*([\d.]+)/)
  if (localizedMax) return `不能大于 ${localizedMax[1]}`
  if (/电子邮件|邮箱|邮件地址/.test(text) && /不合法|无效|格式/.test(text)) return '电子邮箱格式不正确'
  if (hasChinese(text)) return text
  if (/must not be (?:blank|null|empty)/i.test(text)) return '不能为空'
  const size = text.match(/size must be between (\d+) and (\d+)/i)
  if (size) return `长度应为 ${size[1]}–${size[2]} 个字符`
  const max = text.match(/size must be less than or equal to (\d+)/i)
  if (max) return `长度不能超过 ${max[1]} 个字符`
  const min = text.match(/must be greater than or equal to ([\d.]+)/i)
  if (min) return `不能小于 ${min[1]}`
  const upper = text.match(/must be less than or equal to ([\d.]+)/i)
  if (upper) return `不能大于 ${upper[1]}`
  if (/well-formed email|valid email/i.test(text)) return '电子邮箱格式不正确'
  if (/must match/i.test(text)) return '格式不符合要求'
  return '填写内容不符合要求'
}

function readableDetail(value: unknown): string | undefined {
  const text = String(value ?? '').trim()
  if (!text) return undefined
  if (technicalLabels[text]) return technicalLabels[text]
  return hasChinese(text) ? text : undefined
}

export function failureReasonLabel(value: unknown, fallback = '作业执行失败，请重试或联系管理员'): string {
  const text = String(value ?? '').trim()
  if (!text) return '—'
  if (technicalLabels[text]) return technicalLabels[text]
  const looksTechnical = /(?:exception|stack\s*trace|sqlstate|failed\s+to|could\s+not|not\s+a\s+function|undefined|null\s*pointer|\bjava\.|\borg\.|\bcom\.)/i.test(text)
  return hasChinese(text) && !looksTechnical ? text : fallback
}

export function problemMessage(status: number, code: string, detail: unknown, details?: unknown): string {
  const record = details && typeof details === 'object' ? details as Record<string, unknown> : {}
  const base = readableDetail(detail) ?? problemCodeLabels[code]
    ?? (status === 401 ? '登录状态已失效，请重新登录'
      : status === 403 ? '当前账号无权执行此操作'
        : status === 404 ? '请求的记录不存在或已不可用'
          : status === 409 ? '记录状态已变化，请刷新后重试'
            : status >= 500 ? '服务暂时不可用，请稍后重试' : '操作未完成，请检查当前数据后重试')

  const fieldErrors = record.fieldErrors && typeof record.fieldErrors === 'object'
    ? Object.entries(record.fieldErrors as Record<string, unknown>)
      .map(([field, message]) => `${fieldLabel(field)}：${validationLabel(message)}`)
    : []
  const blockers = Array.isArray(record.blockers)
    ? record.blockers.map(readableDetail).filter((item): item is string => Boolean(item))
    : []
  const errors = Array.isArray(record.errors)
    ? record.errors.map(readableDetail).filter((item): item is string => Boolean(item))
    : []
  const additions = [...fieldErrors, ...blockers, ...errors].filter((item, index, values) => item !== base && values.indexOf(item) === index)
  return additions.length ? `${base}：${additions.join('；')}` : base
}

export function localDateString(value: Date = new Date()): string {
  const parts = Object.fromEntries(shanghaiDateFormatter.formatToParts(value)
    .filter((part) => part.type !== 'literal').map((part) => [part.type, part.value]))
  return `${parts.year}-${parts.month}-${parts.day}`
}

export function workbenchGreeting(value: Date = new Date()): '早上好' | '中午好' | '晚上好' {
  const hour = Number(shanghaiHourFormatter.format(value))
  if (hour >= 5 && hour < 12) return '早上好'
  if (hour >= 12 && hour < 18) return '中午好'
  return '晚上好'
}

export const presentationStatusCodes = Object.freeze(Object.keys(statusLabels))
