<script setup lang="ts">
import { Coin, Document, Download, Edit, MoreFilled, Plus, Printer, Refresh, Search, Tickets } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, errorMessage } from '../api'
import { moduleLabel } from '../navigation'
import { useAuthStore } from '../stores/auth'
import { failureReasonLabel, jobTypeLabel, localDateString, statusLabel } from '../presentation'

type Row = Record<string, any>
type Page = { items: Row[]; nextCursor?: string; hasMore: boolean; total: number }
type Option = { id: string; code: string; label: string }
type References = { projects: Option[]; customers: Option[] }
type FinanceOptions = { users: Option[]; publishedTemplates: Option[] }
type BackgroundJob = { id: string; jobType: string; state: string; attemptCount: number; lastError?: string; resultFileId?: string; version: number; createdAt: string; completedAt?: string; allowedActions: string[] }
type JobQueued = { jobId: string; jobType: string; state: string }
type PrintAuthorization = { downloadPath: string; downloadToken: string; remainingCount?: number }

const route = useRoute()
const auth = useAuthStore()
const moduleKey = computed(() => String(route.params.moduleKey))
const title = computed(() => moduleLabel(moduleKey.value))
const loading = ref(false)
const saving = ref(false)
const rows = ref<Row[]>([])
const total = ref(0)
const cursor = ref('')
const nextCursor = ref('')
const cursorHistory = ref<string[]>([])
const selected = ref<Row>()
const references = reactive<References>({ projects: [], customers: [] })
const financeOptions = reactive<FinanceOptions>({ users: [], publishedTemplates: [] })
const contracts = ref<Row[]>([])
const filters = reactive({ query: '', state: '', contractType: '', contractId: '', from: '', to: '' })
const analysis = ref<Row>({})

const contractDrawer = ref(false)
const contractTab = ref('basic')
const contractForm = reactive<Row>({})
const templateDrawer = ref(false)
const templateForm = reactive<Row>({})
const receiptDrawer = ref(false)
const receiptForm = reactive<Row>({})
const paymentDrawer = ref(false)
const paymentForm = reactive<Row>({})
const entryDrawer = ref(false)
const entryForm = reactive<Row>({})
const entries = ref<Row[]>([])
const activeContract = ref<Row>()
const jobDialog = ref(false)
const currentJob = ref<BackgroundJob>()
const reportDrawer = ref(false)
const projectReports = ref<Row[]>([])
const activeProject = ref<Row>()

const canContract = computed(() => auth.user?.permissions.includes('CONTRACT_MANAGE'))
const canFinance = computed(() => auth.user?.permissions.includes('FINANCE_MANAGE'))
const canApprove = computed(() => auth.user?.permissions.includes('FINANCE_APPROVE'))
const canExport = computed(() => auth.user?.permissions.includes('EXPORT_DATA'))
const isPaged = computed(() => !['my-projects', 'contract-analysis'].includes(moduleKey.value))
const canCreate = computed(() => (moduleKey.value === 'contracts' || moduleKey.value === 'contract-price-templates')
  ? canContract.value : ['receipts', 'payments'].includes(moduleKey.value) && canFinance.value)
const pageDescription = computed(() => ({
  contracts: '合同版本、交易单位、金额、履约、结算与交付证据统一管理',
  'my-projects': '查询本人负责或参与工程的合同与报告履约情况',
  'contract-price-templates': '版本化计价项目、单价与发布状态',
  'fee-statements': '从工程检测计划生成费用，锁定后进入收退款闭环',
  receipts: '收款登记、独立确认、费用核销与受控退款',
  payments: '付款申请、职责分离审批与实付登记',
  'contract-analysis': '合同额、产值、开票、应收、实收与付款汇总',
}[moduleKey.value] ?? '合同财务'))

const labels: Record<string, string> = {
  DRAFT: '草稿', IN_REVIEW: '审核中', RETURNED: '已退回', ISSUED: '已签发', DISTRIBUTED: '已发放', ARCHIVED: '已归档',
  SUBMITTED: '待审批', ACTIVE: '履约中', COMPLETED: '已完成', TERMINATED: '已终止',
  PUBLISHED: '已发布', RETIRED: '已停用', LOCKED: '已锁定', SETTLED: '已结清', CONFIRMED: '已确认',
  VOID: '已作废', APPROVED: '已批准', REJECTED: '已拒绝', PAID: '已付款',
  TESTING_SERVICE: '检测服务', TECHNICAL_SERVICE: '技术服务', FRAMEWORK: '框架合同',
  SUBCONTRACT: '分包合同', OTHER: '其他', UNIT_PRICE: '单价计价', FIXED_PRICE: '固定总价',
  NEGOTIATED: '协商计价', BANK_TRANSFER: '银行转账', CASH: '现金', CHECK: '支票',
  INTERNAL_OFFSET: '内部冲抵', TARGET_DELIVERY: '标的交付', PRICE_ADJUSTMENT: '合同价调整',
  SETTLEMENT: '结算', OTHER_FEE: '其他费用', INVOICE: '发票',
}
const actionLabels: Record<string, string> = {
  SUBMIT: '提交审批', ACTIVATE: '确认生效', REJECT: '退回草稿', COMPLETE: '履约完成', TERMINATE: '终止合同',
  PUBLISH: '发布', RETIRE: '停用', REVISE: '创建新修订', LOCK: '锁定', UNLOCK: '解锁',
  FORCE_UNLOCK: '强制解锁', CHANGE_SETTLEMENT: '修改结算人', CONFIRM: '确认收款', VOID: '作废',
  REFUND: '退款', APPROVE: '批准', MARK_PAID: '登记实付',
}

function showError(error: unknown) {
  ElMessage.error(errorMessage(error))
}
function stateType(state: string) {
  if (['ACTIVE', 'PUBLISHED', 'CONFIRMED', 'APPROVED', 'PAID', 'COMPLETED', 'SETTLED'].includes(state)) return 'success'
  if (['TERMINATED', 'REJECTED', 'VOID'].includes(state)) return 'danger'
  if (['SUBMITTED', 'LOCKED'].includes(state)) return 'warning'
  return 'info'
}
function money(value?: string) { return value == null ? '—' : `¥ ${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` }
function date(value?: string) { return value ? new Date(value).toLocaleDateString('zh-CN') : '—' }
function datetime(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function resetObject(target: Row, value: Row) { Object.keys(target).forEach(key => delete target[key]); Object.assign(target, value) }
function currentUserId() { return financeOptions.users.find(item => item.code === auth.user?.username)?.id ?? financeOptions.users[0]?.id ?? '' }

async function loadReferences() {
  try {
    const [base, options, contractPage] = await Promise.all([
      api<References>('/api/reference-data'), api<FinanceOptions>('/api/finance/options'),
      api<Page>('/api/finance/contracts?limit=100'),
    ])
    Object.assign(references, base); Object.assign(financeOptions, options); contracts.value = contractPage.items
  } catch (error) { showError(error) }
}
function listUrl() {
  const params = new URLSearchParams({ limit: '50' })
  if (filters.query) params.set('q', filters.query)
  if (filters.state) params.set('state', filters.state)
  if (filters.contractId) params.set('contractId', filters.contractId)
  if (cursor.value) params.set('cursor', cursor.value)
  if (moduleKey.value === 'contracts' && filters.contractType) params.set('contractType', filters.contractType)
  return ({ contracts: '/api/finance/contracts', 'contract-price-templates': '/api/finance/price-templates',
    'fee-statements': '/api/finance/fees', receipts: '/api/finance/receipts', payments: '/api/finance/payments' } as Row)[moduleKey.value] + `?${params}`
}
async function load() {
  loading.value = true
  try {
    selected.value = undefined
    if (moduleKey.value === 'my-projects') {
      const params = new URLSearchParams(); if (filters.query) params.set('q', filters.query)
      rows.value = await api<Row[]>(`/api/finance/my-projects?${params}`); total.value = rows.value.length; nextCursor.value = ''
    } else if (moduleKey.value === 'contract-analysis') {
      const params = new URLSearchParams(); if (filters.from) params.set('from', filters.from); if (filters.to) params.set('to', filters.to)
      analysis.value = await api<Row>(`/api/finance/analysis?${params}`); rows.value = []; total.value = 0; nextCursor.value = ''
    } else {
      const page = await api<Page>(listUrl()); rows.value = page.items; total.value = page.total; nextCursor.value = page.nextCursor ?? ''
    }
  } catch (error) { showError(error) } finally { loading.value = false }
}
function search() { cursor.value = ''; cursorHistory.value = []; void load() }
function resetFilters() { Object.assign(filters, { query: '', state: '', contractType: '', contractId: '', from: '', to: '' }); search() }
function nextPage() { if (nextCursor.value) { cursorHistory.value.push(cursor.value); cursor.value = nextCursor.value; void load() } }
function previousPage() { if (cursorHistory.value.length) { cursor.value = cursorHistory.value.pop() ?? ''; void load() } }

function blankContract() {
  resetObject(contractForm, { number: '', name: '', contractType: 'TESTING_SERVICE', customerId: '', projectId: '',
    counterpartyName: '', signedOn: '', effectiveFrom: '', effectiveUntil: '', deliveryDeadline: '', contractNature: '',
    priceTemplateId: '', currency: 'CNY', signedAmount: '0.00', contractPrice: '0.00', settlementMethod: '',
    ownerId: currentUserId(), expectedVersion: null })
}
async function openContract(row?: Row) {
  contractTab.value = 'basic'; blankContract()
  if (row) {
    const detail = await api<Row>(`/api/finance/contracts/${row.id}`)
    Object.assign(contractForm, detail, { expectedVersion: detail.version })
  }
  contractDrawer.value = true
}
async function saveContract() {
  saving.value = true
  try {
    const body = { ...contractForm, priceTemplateId: contractForm.priceTemplateId || null,
      signedOn: contractForm.signedOn || null, effectiveFrom: contractForm.effectiveFrom || null,
      effectiveUntil: contractForm.effectiveUntil || null, deliveryDeadline: contractForm.deliveryDeadline || null }
    if (contractForm.id) await api(`/api/finance/contracts/${contractForm.id}`, { method: 'PUT', body: JSON.stringify(body) })
    else await api('/api/finance/contracts', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) })
    ElMessage.success(contractForm.id ? '合同已更新' : '合同已建立'); contractDrawer.value = false; await loadReferences(); await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function contractAction(row: Row, action: string) {
  try {
    let comment = ''
    if (['REJECT', 'TERMINATE'].includes(action)) comment = (await ElMessageBox.prompt('请填写决定依据', actionLabels[action], {
      inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '决定依据不能为空',
    })).value
    await api(`/api/finance/contracts/${row.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: 'CONTRACT_CONTROL', comment }) })
    ElMessage.success(`合同已${actionLabels[action]}`); await loadReferences(); await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

function blankTemplate() { resetObject(templateForm, { code: '', name: '', description: '', pricingMethod: 'UNIT_PRICE', currency: 'CNY', items: [], expectedVersion: null }) }
async function openTemplate(row?: Row) {
  blankTemplate()
  if (row) {
    const detail = await api<Row>(`/api/finance/price-templates/${row.id}`)
    Object.assign(templateForm, detail, { expectedVersion: detail.version, items: detail.items.map((item: Row) => ({ ...item })) })
  }
  templateDrawer.value = true
}
function addTemplateItem() { templateForm.items.push({ itemName: '', unit: '项', unitPrice: '0.00', minimumQuantity: '0', sortOrder: templateForm.items.length }) }
function removeTemplateItem(index: number) { templateForm.items.splice(index, 1) }
async function saveTemplate() {
  saving.value = true
  try {
    const body = { ...templateForm, items: templateForm.items.map((item: Row, index: number) => ({
      detectionProjectRevisionId: item.detectionProjectRevisionId || null, parameterId: item.parameterId || null,
      itemName: item.itemName, unit: item.unit, unitPrice: String(item.unitPrice), minimumQuantity: String(item.minimumQuantity), sortOrder: index,
    })) }
    if (templateForm.id) await api(`/api/finance/price-templates/${templateForm.id}`, { method: 'PUT', body: JSON.stringify(body) })
    else await api('/api/finance/price-templates', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) })
    ElMessage.success('计价模板已保存'); templateDrawer.value = false; await loadReferences(); await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function templateAction(row: Row, action: string) {
  try {
    if (action === 'REVISE') await api(`/api/finance/price-templates/${row.id}/revisions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ expectedVersion: row.version }) })
    else await api(`/api/finance/price-templates/${row.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: 'TEMPLATE_CONTROL', comment: '' }) })
    ElMessage.success(action === 'REVISE' ? '新修订草稿已建立' : `模板已${actionLabels[action]}`); await loadReferences(); await load()
  } catch (error) { showError(error) }
}

function blankReceipt() { resetObject(receiptForm, { number: `SK-${new Date().toISOString().replace(/\D/g, '').slice(0, 14)}`, contractId: '', feeStatementId: '',
  receivableAmount: '0.00', actualAmount: '0.00', receivedOn: localDateString(), paymentMethod: 'BANK_TRANSFER',
  collectorId: currentUserId(), payerName: '', invoiceNumber: '', expectedVersion: null }) }
async function openReceipt(row?: Row, fee?: Row) {
  blankReceipt()
  if (row) Object.assign(receiptForm, row, { expectedVersion: row.version })
  if (fee) Object.assign(receiptForm, { contractId: fee.contractId, feeStatementId: fee.id, receivableAmount: fee.outstandingAmount,
    actualAmount: fee.outstandingAmount, payerName: contracts.value.find(item => item.id === fee.contractId)?.counterpartyName ?? '' })
  receiptDrawer.value = true
}
async function saveReceipt() {
  saving.value = true
  try {
    const body = { ...receiptForm, feeStatementId: receiptForm.feeStatementId || null }
    if (receiptForm.id) await api(`/api/finance/receipts/${receiptForm.id}`, { method: 'PUT', body: JSON.stringify(body) })
    else await api('/api/finance/receipts', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) })
    ElMessage.success('收款单已保存'); receiptDrawer.value = false; await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function receiptAction(row: Row, action: string) {
  try {
    if (action === 'REFUND') {
      const amount = (await ElMessageBox.prompt('请输入本次退款金额', '受控退款', { inputValue: row.actualAmount,
        inputValidator: value => Number(value) > 0 || '退款金额必须大于 0' })).value
      const reason = (await ElMessageBox.prompt('请填写退款依据', '退款依据', { inputType: 'textarea',
        inputValidator: value => Boolean(value.trim()) || '退款依据不能为空' })).value
      await api(`/api/finance/receipts/${row.id}/refunds`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ amount, receivedOn: localDateString(), reason, expectedVersion: row.version }) })
    } else {
      let comment = ''
      if (action === 'VOID') comment = (await ElMessageBox.prompt('请填写作废原因', '作废收款', { inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空' })).value
      await api(`/api/finance/receipts/${row.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: 'RECEIPT_CONTROL', comment }) })
    }
    ElMessage.success(`已完成：${actionLabels[action]}`); await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

function blankPayment() { resetObject(paymentForm, { number: `FK-${new Date().toISOString().replace(/\D/g, '').slice(0, 14)}`, contractId: '',
  payableAmount: '0.00', plannedOn: localDateString(), payeeName: '', invoiceNumber: '', purpose: '', expectedVersion: null }) }
function openPayment(row?: Row) { blankPayment(); if (row) Object.assign(paymentForm, row, { expectedVersion: row.version }); paymentDrawer.value = true }
async function savePayment() {
  saving.value = true
  try {
    if (paymentForm.id) await api(`/api/finance/payments/${paymentForm.id}`, { method: 'PUT', body: JSON.stringify(paymentForm) })
    else await api('/api/finance/payments', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(paymentForm) })
    ElMessage.success('付款单已保存'); paymentDrawer.value = false; await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function paymentAction(row: Row, action: string) {
  try {
    let comment = ''; let actualAmount: string | null = null; let paidOn: string | null = null; let paymentMethod: string | null = null
    if (['REJECT', 'VOID'].includes(action)) comment = (await ElMessageBox.prompt('请填写决定依据', actionLabels[action], { inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '决定依据不能为空' })).value
    if (action === 'APPROVE') comment = (await ElMessageBox.prompt('请填写审批意见', '批准付款', { inputType: 'textarea', inputValue: '同意付款' })).value
    if (action === 'MARK_PAID') {
      actualAmount = (await ElMessageBox.prompt('请输入实付金额', '登记实付', { inputValue: row.payableAmount, inputValidator: value => Number(value) > 0 || '金额必须大于 0' })).value
      paidOn = localDateString(); paymentMethod = 'BANK_TRANSFER'
    }
    await api(`/api/finance/payments/${row.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: 'PAYMENT_CONTROL', comment, actualAmount, paidOn, paymentMethod }) })
    ElMessage.success(`已完成：${actionLabels[action]}`); await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

async function feeAction(row: Row, action: string) {
  try {
    let comment = ''; let settlementOwnerId: string | null = null
    if (['FORCE_UNLOCK'].includes(action)) comment = (await ElMessageBox.prompt('请填写强制解锁依据', actionLabels[action], { inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '依据不能为空' })).value
    if (action === 'LOCK') comment = (await ElMessageBox.prompt('填写锁定说明（可选）', '锁定费用', { inputType: 'textarea' })).value
    if (action === 'CHANGE_SETTLEMENT') {
      const username = (await ElMessageBox.prompt('请输入结算人账号', '修改结算人', { inputValue: financeOptions.users.find(item => item.id === row.settlementOwnerId)?.code ?? '',
        inputValidator: value => financeOptions.users.some(item => item.code === value) || '请输入有效账号' })).value
      settlementOwnerId = financeOptions.users.find(item => item.code === username)?.id ?? null
    }
    await api(`/api/finance/fees/${row.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: 'FEE_CONTROL', comment, settlementOwnerId }) })
    ElMessage.success(`已完成：${actionLabels[action]}`); await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}
async function recalculateFees() {
  try {
    const contractNumber = (await ElMessageBox.prompt('请输入需要重算费用的合同编号', '从工程检测计划重算', {
      inputValidator: value => contracts.value.some(item => item.number === value) || '请输入有效合同编号',
    })).value
    const contract = contracts.value.find(item => item.number === contractNumber)
    if (!contract) throw new Error('合同不在当前可访问范围内')
    await api(`/api/finance/contracts/${contract.id}/fees/recalculate`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ expectedVersion: contract.version }) })
    ElMessage.success('费用已按最新工程检测计划重算'); await loadReferences(); await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

function exportType() {
  return ({ contracts: 'CONTRACT', 'fee-statements': 'FEE_STATEMENT', receipts: 'RECEIPT', payments: 'PAYMENT' } as Row)[moduleKey.value]
}
async function pollJob(jobId: string) {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const job = await api<BackgroundJob>(`/api/jobs/${jobId}`); currentJob.value = job
    if (['COMPLETED', 'FAILED'].includes(job.state)) return job
    await new Promise(resolve => window.setTimeout(resolve, 1200))
  }
  return currentJob.value
}
async function queueExport() {
  const resourceType = exportType()
  if (!resourceType) return
  try {
    const queued = await api<JobQueued>('/api/finance/exports', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ resourceType, query: filters.query, state: filters.state, contractId: filters.contractId || null, projectId: null }) })
    currentJob.value = { id: queued.jobId, jobType: queued.jobType, state: queued.state, attemptCount: 0, version: 0,
      createdAt: new Date().toISOString(), allowedActions: [] }
    jobDialog.value = true
    const completed = await pollJob(queued.jobId)
    if (completed?.state === 'COMPLETED') ElMessage.success('受控清单已生成，可下载')
    else if (completed?.state === 'FAILED') ElMessage.error(failureReasonLabel(completed.lastError, '导出作业失败'))
  } catch (error) { showError(error) }
}
async function retryJob() {
  if (!currentJob.value) return
  try {
    const job = await api<BackgroundJob>(`/api/jobs/${currentJob.value.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action: 'RETRY', expectedVersion: currentJob.value.version, reasonCode: 'FINANCE_EXPORT_RETRY', comment: '用户确认重试财务导出' }) })
    currentJob.value = job
    const completed = await pollJob(job.id)
    if (completed?.state === 'COMPLETED') ElMessage.success('重试成功，可下载')
    else if (completed?.state === 'FAILED') ElMessage.error(failureReasonLabel(completed.lastError, '导出重试失败'))
  } catch (error) { showError(error) }
}
function downloadJob() {
  if (!currentJob.value?.resultFileId) return
  const anchor = document.createElement('a'); anchor.href = `/api/files/${currentJob.value.resultFileId}`
  anchor.download = `${title.value}.xlsx`; document.body.appendChild(anchor); anchor.click(); anchor.remove()
}

async function openProjectReports(row: Row) {
  try {
    activeProject.value = row
    projectReports.value = await api<Row[]>(`/api/finance/my-projects/${row.projectId}/reports`)
    reportDrawer.value = true
  } catch (error) { showError(error) }
}
async function openReportFile(row: Row, forPrint: boolean) {
  const preview = forPrint ? window.open('', '_blank') : null
  try {
    const authorization = await api<PrintAuthorization>(`/api/reports/${row.reportId}/print-authorizations`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ expectedVersion: row.version }),
    })
    const response = await fetch(authorization.downloadPath, { credentials: 'same-origin', headers: { 'X-Download-Token': authorization.downloadToken } })
    if (!response.ok) throw new Error('报告文件下载失败')
    const url = URL.createObjectURL(await response.blob())
    if (forPrint && preview) { preview.location.href = url; window.setTimeout(() => URL.revokeObjectURL(url), 120_000) }
    else {
      preview?.close()
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${row.reportNumber}-${row.revisionLabel}.pdf`
      document.body.appendChild(anchor); anchor.click(); anchor.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 5_000)
    }
    if (authorization.remainingCount != null) ElMessage.info(`本次授权后剩余打印次数：${authorization.remainingCount}`)
  } catch (error) { preview?.close(); showError(error) }
}

async function openEntries(row: Row) {
  activeContract.value = await api<Row>(`/api/finance/contracts/${row.id}`)
  entries.value = await api<Row[]>(`/api/finance/contracts/${row.id}/entries`)
  resetObject(entryForm, { entryType: 'TARGET_DELIVERY', businessNumber: '', occurredOn: localDateString(), amount: '0.00', description: '' })
  entryDrawer.value = true
}
async function addEntry() {
  if (!activeContract.value) return
  saving.value = true
  try {
    await api(`/api/finance/contracts/${activeContract.value.id}/entries`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ...entryForm, expectedVersion: activeContract.value.version }) })
    ElMessage.success('合同业务明细已记录'); await openEntries(activeContract.value); await loadReferences(); await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function voidEntry(row: Row) {
  if (!activeContract.value) return
  try {
    const reason = (await ElMessageBox.prompt('请填写作废原因', '作废业务明细', { inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空' })).value
    await api(`/api/finance/contracts/${activeContract.value.id}/entries/${row.id}/void`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ expectedVersion: activeContract.value.version, entryExpectedVersion: row.version, reason }) })
    ElMessage.success('业务明细已作废'); await openEntries(activeContract.value); await loadReferences(); await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

function openCreate() {
  if (moduleKey.value === 'contracts') void openContract()
  else if (moduleKey.value === 'contract-price-templates') void openTemplate()
  else if (moduleKey.value === 'receipts') void openReceipt()
  else if (moduleKey.value === 'payments') openPayment()
}
function rowAction(row: Row, action: string) {
  if (moduleKey.value === 'contracts') void contractAction(row, action)
  else if (moduleKey.value === 'contract-price-templates') void templateAction(row, action)
  else if (moduleKey.value === 'fee-statements') void feeAction(row, action)
  else if (moduleKey.value === 'receipts') void receiptAction(row, action)
  else if (moduleKey.value === 'payments') void paymentAction(row, action)
}
function actionVisible(action: string) {
  if (['APPROVE', 'REJECT', 'MARK_PAID', 'CONFIRM', 'REFUND'].includes(action)) return canApprove.value
  if (['LOCK', 'UNLOCK', 'FORCE_UNLOCK', 'CHANGE_SETTLEMENT'].includes(action)) return canFinance.value
  return canContract.value || canFinance.value
}

watch(moduleKey, () => { resetFilters(); void loadReferences() })
onMounted(async () => { await loadReferences(); await load() })
</script>

<template>
  <section class="page contract-finance-page" v-loading="loading">
    <header class="page-heading">
      <div><h1>{{ title }}</h1><p>{{ pageDescription }}</p></div>
      <div class="heading-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button v-if="canExport && exportType()" :icon="Download" @click="queueExport">受控导出</el-button>
        <el-button v-if="moduleKey === 'fee-statements' && canFinance" :icon="Coin" @click="recalculateFees">重算费用</el-button>
        <el-button v-if="canCreate" type="primary" :icon="Plus" @click="openCreate">新增{{ title.replace('管理', '') }}</el-button>
      </div>
    </header>

    <el-alert title="已发布模板、已确认收款和履约合同不会被原地覆盖；修改、退回、退款、解锁和终止均保留版本与审计。" type="info" show-icon :closable="false" />

    <div v-if="moduleKey === 'contract-analysis'" class="analysis-shell">
      <div class="finance-filter-row">
        <el-date-picker v-model="filters.from" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" />
        <span>至</span><el-date-picker v-model="filters.to" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" />
        <el-button type="primary" :icon="Search" @click="load">查询</el-button><el-button @click="resetFilters">重置</el-button>
      </div>
      <div class="analysis-grid">
        <article><span>合同价</span><strong>{{ money(analysis.contractPrice) }}</strong><small>签订金额 {{ money(analysis.signedAmount) }}</small></article>
        <article><span>已开票</span><strong>{{ money(analysis.invoicedAmount) }}</strong><small>开票率 {{ analysis.invoiceRate ?? '0.00' }}%</small></article>
        <article><span>应收 / 实收</span><strong>{{ money(analysis.receivableAmount) }}</strong><small>实收 {{ money(analysis.receivedAmount) }} · {{ analysis.collectionRate ?? '0.00' }}%</small></article>
        <article><span>应付 / 实付</span><strong>{{ money(analysis.payableAmount) }}</strong><small>实付 {{ money(analysis.paidAmount) }}</small></article>
      </div>
      <div class="analysis-status">
        <div><b>{{ analysis.activeContracts ?? 0 }}</b><span>履约中合同</span></div>
        <div><b>{{ analysis.overdueContracts ?? 0 }}</b><span>逾期合同</span></div>
        <div><b>{{ analysis.unsettledFees ?? 0 }}</b><span>未结清费用</span></div>
      </div>
    </div>

    <template v-else>
      <div class="finance-filter-row">
        <el-input v-model="filters.query" clearable :prefix-icon="Search" placeholder="编号、名称、单位、工程或发票" @keyup.enter="search" />
        <el-select v-if="moduleKey !== 'my-projects'" v-model="filters.state" clearable placeholder="全部状态">
          <el-option v-for="value in ['DRAFT','SUBMITTED','ACTIVE','PUBLISHED','LOCKED','SETTLED','CONFIRMED','APPROVED','PAID','COMPLETED','TERMINATED','RETIRED','REJECTED','VOID']" :key="value" :label="labels[value]" :value="value" />
        </el-select>
        <el-select v-if="moduleKey === 'contracts'" v-model="filters.contractType" clearable placeholder="合同类别">
          <el-option v-for="value in ['TESTING_SERVICE','TECHNICAL_SERVICE','FRAMEWORK','SUBCONTRACT','OTHER']" :key="value" :label="labels[value]" :value="value" />
        </el-select>
        <el-select v-if="['fee-statements','receipts','payments'].includes(moduleKey)" v-model="filters.contractId" clearable filterable placeholder="全部合同">
          <el-option v-for="item in contracts" :key="item.id" :label="`${item.number} · ${item.name}`" :value="item.id" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="search">查询</el-button><el-button @click="resetFilters">重置</el-button>
        <span class="record-count">共 {{ total }} 条</span>
      </div>

      <el-table v-if="moduleKey === 'contracts'" :data="rows" border stripe highlight-current-row @current-change="selected = $event">
        <el-table-column prop="number" label="合同编号" width="150" fixed="left" />
        <el-table-column prop="name" label="合同名称" min-width="220" show-overflow-tooltip />
        <el-table-column label="类别" width="120"><template #default="s">{{ labels[s.row.contractType] }}</template></el-table-column>
        <el-table-column prop="counterpartyName" label="交易单位" min-width="180" show-overflow-tooltip />
        <el-table-column prop="projectName" label="工程" min-width="190" show-overflow-tooltip />
        <el-table-column prop="signedOn" label="签订日期" width="110" />
        <el-table-column label="合同价" width="140" align="right"><template #default="s">{{ money(s.row.contractPrice) }}</template></el-table-column>
        <el-table-column label="履约率" width="110"><template #default="s"><el-progress :percentage="Number(s.row.performancePercent)" :stroke-width="7" /></template></el-table-column>
        <el-table-column label="状态" width="105" align="center"><template #default="s"><el-tag :type="stateType(s.row.state)" effect="plain">{{ labels[s.row.state] }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="250" fixed="right" align="center"><template #default="s">
          <el-button link type="primary" :icon="Document" @click="openEntries(s.row)">业务明细</el-button>
          <el-button v-if="s.row.state === 'DRAFT' && canContract" link type="primary" :icon="Edit" @click="openContract(s.row)">修改</el-button>
          <el-dropdown v-if="s.row.allowedActions?.length" @command="rowAction(s.row, String($event))"><el-button link type="primary" :icon="MoreFilled">状态动作</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="action in s.row.allowedActions" :key="action" :command="action" :disabled="!actionVisible(action)">{{ actionLabels[action] }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
        </template></el-table-column>
      </el-table>

      <el-table v-else-if="moduleKey === 'my-projects'" :data="rows" border stripe>
        <el-table-column prop="projectNumber" label="工程编号" width="150" />
        <el-table-column prop="projectName" label="工程名称" min-width="240" />
        <el-table-column prop="contractNumber" label="合同编号" width="150" />
        <el-table-column prop="contractName" label="合同名称" min-width="210" />
        <el-table-column label="履约率" width="150"><template #default="s"><el-progress :percentage="Number(s.row.performancePercent)" :stroke-width="7" /></template></el-table-column>
        <el-table-column label="报告" width="150"><template #default="s">{{ s.row.issuedReportCount }} / {{ s.row.reportCount }} 已签发</template></el-table-column>
        <el-table-column label="最后报告变更" width="180"><template #default="s">{{ datetime(s.row.lastReportAt) }}</template></el-table-column>
        <el-table-column label="合同状态" width="110"><template #default="s"><el-tag :type="stateType(s.row.contractState)" effect="plain">{{ labels[s.row.contractState] }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="110" fixed="right" align="center"><template #default="s"><el-button link type="primary" :icon="Document" @click="openProjectReports(s.row)">报告明细</el-button></template></el-table-column>
      </el-table>

      <el-table v-else-if="moduleKey === 'contract-price-templates'" :data="rows" border stripe>
        <el-table-column prop="code" label="模板编号" width="150" />
        <el-table-column prop="name" label="模板名称" min-width="240" />
        <el-table-column label="计价方式" width="130"><template #default="s">{{ labels[s.row.pricingMethod] }}</template></el-table-column>
        <el-table-column label="版本" width="90"><template #default="s">R{{ s.row.revision }}</template></el-table-column>
        <el-table-column prop="currency" label="币种" width="80" />
        <el-table-column label="状态" width="105" align="center"><template #default="s"><el-tag :type="stateType(s.row.state)" effect="plain">{{ labels[s.row.state] }}</el-tag></template></el-table-column>
        <el-table-column label="更新时间" width="180"><template #default="s">{{ datetime(s.row.updatedAt) }}</template></el-table-column>
        <el-table-column label="操作" width="230" fixed="right" align="center"><template #default="s">
          <el-button link type="primary" @click="openTemplate(s.row)">{{ s.row.state === 'DRAFT' ? '编辑' : '查看' }}</el-button>
          <el-button v-for="action in s.row.allowedActions" :key="action" v-show="actionVisible(action)" link type="primary" @click="rowAction(s.row, action)">{{ actionLabels[action] }}</el-button>
        </template></el-table-column>
      </el-table>

      <el-table v-else-if="moduleKey === 'fee-statements'" :data="rows" border stripe>
        <el-table-column prop="contractNumber" label="合同编号" width="150" />
        <el-table-column prop="projectName" label="工程" min-width="190" show-overflow-tooltip />
        <el-table-column prop="itemName" label="检测项目" min-width="190" />
        <el-table-column prop="parameterName" label="检测参数" min-width="180" />
        <el-table-column label="数量 / 单位" width="120"><template #default="s">{{ s.row.quantity }} {{ s.row.unit }}</template></el-table-column>
        <el-table-column label="单价" width="120" align="right"><template #default="s">{{ money(s.row.unitPrice) }}</template></el-table-column>
        <el-table-column label="金额" width="125" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column>
        <el-table-column label="未收" width="125" align="right"><template #default="s">{{ money(s.row.outstandingAmount) }}</template></el-table-column>
        <el-table-column prop="settlementOwnerName" label="结算人" width="120" />
        <el-table-column label="状态" width="100" align="center"><template #default="s"><el-tag :type="stateType(s.row.state)" effect="plain">{{ labels[s.row.state] }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="280" fixed="right" align="center"><template #default="s">
          <el-button v-if="s.row.state === 'LOCKED' && Number(s.row.outstandingAmount) > 0 && canFinance" link type="primary" @click="openReceipt(undefined, s.row)">登记收款</el-button>
          <el-button v-for="action in s.row.allowedActions" :key="action" v-show="actionVisible(action)" link type="primary" @click="rowAction(s.row, action)">{{ actionLabels[action] }}</el-button>
        </template></el-table-column>
      </el-table>

      <el-table v-else-if="moduleKey === 'receipts'" :data="rows" border stripe>
        <el-table-column prop="number" label="收款单号" width="170" />
        <el-table-column prop="contractNumber" label="合同编号" width="150" />
        <el-table-column label="应收" width="125" align="right"><template #default="s">{{ money(s.row.receivableAmount) }}</template></el-table-column>
        <el-table-column label="实收 / 退款" width="135" align="right"><template #default="s"><span :class="{ 'refund-amount': Number(s.row.actualAmount) < 0 }">{{ money(s.row.actualAmount) }}</span></template></el-table-column>
        <el-table-column prop="receivedOn" label="收款日期" width="110" />
        <el-table-column label="方式" width="110"><template #default="s">{{ labels[s.row.paymentMethod] ?? s.row.paymentMethod }}</template></el-table-column>
        <el-table-column prop="payerName" label="付款方" min-width="180" />
        <el-table-column prop="collectorName" label="收款人" width="120" />
        <el-table-column prop="invoiceNumber" label="发票号" width="150" />
        <el-table-column label="状态" width="100" align="center"><template #default="s"><el-tag :type="stateType(s.row.state)" effect="plain">{{ labels[s.row.state] }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="190" fixed="right" align="center"><template #default="s">
          <el-button v-if="s.row.state === 'DRAFT' && canFinance" link type="primary" @click="openReceipt(s.row)">修改</el-button>
          <el-button v-for="action in s.row.allowedActions" :key="action" v-show="actionVisible(action)" link type="primary" @click="rowAction(s.row, action)">{{ actionLabels[action] }}</el-button>
        </template></el-table-column>
      </el-table>

      <el-table v-else-if="moduleKey === 'payments'" :data="rows" border stripe>
        <el-table-column prop="number" label="付款单号" width="170" />
        <el-table-column prop="contractNumber" label="合同编号" width="150" />
        <el-table-column label="应付" width="125" align="right"><template #default="s">{{ money(s.row.payableAmount) }}</template></el-table-column>
        <el-table-column label="实付" width="125" align="right"><template #default="s">{{ money(s.row.actualAmount) }}</template></el-table-column>
        <el-table-column prop="plannedOn" label="计划日期" width="110" />
        <el-table-column prop="paidOn" label="付款日期" width="110" />
        <el-table-column prop="payeeName" label="收款方" min-width="180" />
        <el-table-column prop="invoiceNumber" label="发票号" width="150" />
        <el-table-column prop="purpose" label="用途" min-width="190" show-overflow-tooltip />
        <el-table-column label="状态" width="100" align="center"><template #default="s"><el-tag :type="stateType(s.row.state)" effect="plain">{{ labels[s.row.state] }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="210" fixed="right" align="center"><template #default="s">
          <el-button v-if="s.row.state === 'DRAFT' && canFinance" link type="primary" @click="openPayment(s.row)">修改</el-button>
          <el-button v-for="action in s.row.allowedActions" :key="action" v-show="actionVisible(action)" link type="primary" @click="rowAction(s.row, action)">{{ actionLabels[action] }}</el-button>
        </template></el-table-column>
      </el-table>

      <div v-if="isPaged" class="cursor-pagination"><el-button :disabled="!cursorHistory.length" @click="previousPage">上一页</el-button><span>每页最多 50 条</span><el-button :disabled="!nextCursor" @click="nextPage">下一页</el-button></div>
    </template>

    <el-drawer v-model="contractDrawer" :title="contractForm.id ? '修改合同草稿' : '新建合同'" size="760px" destroy-on-close>
      <el-tabs v-model="contractTab"><el-tab-pane label="合同主体" name="basic"><el-form label-position="top" class="finance-form-grid">
        <el-form-item label="合同编号" required><el-input v-model="contractForm.number" maxlength="100" /></el-form-item>
        <el-form-item label="合同名称" required><el-input v-model="contractForm.name" maxlength="300" /></el-form-item>
        <el-form-item label="合同类别" required><el-select v-model="contractForm.contractType"><el-option v-for="v in ['TESTING_SERVICE','TECHNICAL_SERVICE','FRAMEWORK','SUBCONTRACT','OTHER']" :key="v" :label="labels[v]" :value="v" /></el-select></el-form-item>
        <el-form-item label="合同性质"><el-input v-model="contractForm.contractNature" /></el-form-item>
        <el-form-item label="工程" required><el-select v-model="contractForm.projectId" filterable><el-option v-for="v in references.projects" :key="v.id" :label="`${v.code} · ${v.label}`" :value="v.id" /></el-select></el-form-item>
        <el-form-item label="客户" required><el-select v-model="contractForm.customerId" filterable><el-option v-for="v in references.customers" :key="v.id" :label="`${v.code} · ${v.label}`" :value="v.id" /></el-select></el-form-item>
        <el-form-item label="交易单位" required><el-input v-model="contractForm.counterpartyName" maxlength="240" /></el-form-item>
        <el-form-item label="合同负责人" required><el-select v-model="contractForm.ownerId" filterable><el-option v-for="v in financeOptions.users" :key="v.id" :label="`${v.label} · ${v.code}`" :value="v.id" /></el-select></el-form-item>
      </el-form></el-tab-pane><el-tab-pane label="时间与金额" name="terms"><el-form label-position="top" class="finance-form-grid">
        <el-form-item label="签订日期"><el-date-picker v-model="contractForm.signedOn" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="标的交付期限"><el-date-picker v-model="contractForm.deliveryDeadline" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="有效期起"><el-date-picker v-model="contractForm.effectiveFrom" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="有效期止"><el-date-picker v-model="contractForm.effectiveUntil" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="签订金额"><el-input v-model="contractForm.signedAmount" inputmode="decimal"><template #prepend>¥</template></el-input></el-form-item>
        <el-form-item label="合同价"><el-input v-model="contractForm.contractPrice" inputmode="decimal"><template #prepend>¥</template></el-input></el-form-item>
        <el-form-item label="合同价模板"><el-select v-model="contractForm.priceTemplateId" clearable><el-option v-for="v in financeOptions.publishedTemplates" :key="v.id" :label="`${v.code} · ${v.label}`" :value="v.id" /></el-select></el-form-item>
        <el-form-item label="结算方式"><el-input v-model="contractForm.settlementMethod" /></el-form-item>
      </el-form></el-tab-pane></el-tabs>
      <template #footer><el-button @click="contractDrawer = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveContract">保存草稿</el-button></template>
    </el-drawer>

    <el-drawer v-model="templateDrawer" :title="templateForm.id ? `合同价模板 R${templateForm.revision}` : '新建合同价模板'" size="860px">
      <el-form label-position="top" class="finance-form-grid"><el-form-item label="模板编号" required><el-input v-model="templateForm.code" /></el-form-item><el-form-item label="模板名称" required><el-input v-model="templateForm.name" /></el-form-item><el-form-item label="计价方式"><el-select v-model="templateForm.pricingMethod"><el-option v-for="v in ['UNIT_PRICE','FIXED_PRICE','NEGOTIATED']" :key="v" :label="labels[v]" :value="v" /></el-select></el-form-item><el-form-item label="币种"><el-input v-model="templateForm.currency" maxlength="3" /></el-form-item><el-form-item class="span-2" label="说明"><el-input v-model="templateForm.description" type="textarea" :rows="2" /></el-form-item></el-form>
      <div class="drawer-section-heading"><div><h3>计价项目</h3><p>发布后不可原地修改，需创建新修订。</p></div><el-button v-if="!templateForm.id || templateForm.state === 'DRAFT'" :icon="Plus" @click="addTemplateItem">添加项目</el-button></div>
      <el-table :data="templateForm.items" border><el-table-column label="项目名称" min-width="220"><template #default="s"><el-input v-model="s.row.itemName" :disabled="templateForm.state && templateForm.state !== 'DRAFT'" /></template></el-table-column><el-table-column label="单位" width="110"><template #default="s"><el-input v-model="s.row.unit" :disabled="templateForm.state && templateForm.state !== 'DRAFT'" /></template></el-table-column><el-table-column label="单价" width="150"><template #default="s"><el-input v-model="s.row.unitPrice" :disabled="templateForm.state && templateForm.state !== 'DRAFT'" /></template></el-table-column><el-table-column label="起计数量" width="130"><template #default="s"><el-input v-model="s.row.minimumQuantity" :disabled="templateForm.state && templateForm.state !== 'DRAFT'" /></template></el-table-column><el-table-column v-if="!templateForm.state || templateForm.state === 'DRAFT'" label="操作" width="80" align="center"><template #default="s"><el-button link type="danger" @click="removeTemplateItem(s.$index)">移除</el-button></template></el-table-column></el-table>
      <template #footer><el-button @click="templateDrawer = false">关闭</el-button><el-button v-if="!templateForm.state || templateForm.state === 'DRAFT'" type="primary" :loading="saving" @click="saveTemplate">保存草稿</el-button></template>
    </el-drawer>

    <el-drawer v-model="receiptDrawer" :title="receiptForm.id ? '修改收款草稿' : '登记收款'" size="620px"><el-form label-position="top" class="finance-form-grid"><el-form-item label="收款单号" required><el-input v-model="receiptForm.number" /></el-form-item><el-form-item label="合同" required><el-select v-model="receiptForm.contractId" filterable><el-option v-for="v in contracts.filter(c => c.state === 'ACTIVE')" :key="v.id" :label="`${v.number} · ${v.name}`" :value="v.id" /></el-select></el-form-item><el-form-item label="应收金额"><el-input v-model="receiptForm.receivableAmount" /></el-form-item><el-form-item label="实收金额" required><el-input v-model="receiptForm.actualAmount" /></el-form-item><el-form-item label="收款日期" required><el-date-picker v-model="receiptForm.receivedOn" type="date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="收款方式"><el-select v-model="receiptForm.paymentMethod"><el-option v-for="v in ['BANK_TRANSFER','CASH','CHECK','INTERNAL_OFFSET','OTHER']" :key="v" :label="labels[v]" :value="v" /></el-select></el-form-item><el-form-item label="收款人"><el-select v-model="receiptForm.collectorId"><el-option v-for="v in financeOptions.users" :key="v.id" :label="v.label" :value="v.id" /></el-select></el-form-item><el-form-item label="付款方" required><el-input v-model="receiptForm.payerName" /></el-form-item><el-form-item class="span-2" label="发票号"><el-input v-model="receiptForm.invoiceNumber" /></el-form-item></el-form><template #footer><el-button @click="receiptDrawer = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveReceipt">保存草稿</el-button></template></el-drawer>

    <el-drawer v-model="paymentDrawer" :title="paymentForm.id ? '修改付款草稿' : '新增付款申请'" size="620px"><el-form label-position="top" class="finance-form-grid"><el-form-item label="付款单号" required><el-input v-model="paymentForm.number" /></el-form-item><el-form-item label="合同" required><el-select v-model="paymentForm.contractId" filterable><el-option v-for="v in contracts.filter(c => c.state === 'ACTIVE')" :key="v.id" :label="`${v.number} · ${v.name}`" :value="v.id" /></el-select></el-form-item><el-form-item label="应付金额" required><el-input v-model="paymentForm.payableAmount" /></el-form-item><el-form-item label="计划付款日" required><el-date-picker v-model="paymentForm.plannedOn" type="date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="收款方" required><el-input v-model="paymentForm.payeeName" /></el-form-item><el-form-item label="发票号"><el-input v-model="paymentForm.invoiceNumber" /></el-form-item><el-form-item class="span-2" label="付款用途" required><el-input v-model="paymentForm.purpose" type="textarea" :rows="3" /></el-form-item></el-form><template #footer><el-button @click="paymentDrawer = false">取消</el-button><el-button type="primary" :loading="saving" @click="savePayment">保存草稿</el-button></template></el-drawer>

    <el-drawer v-model="entryDrawer" :title="`${activeContract?.number ?? ''} · 合同业务明细`" size="760px"><el-form label-position="top" class="finance-entry-form"><el-form-item label="业务类型"><el-select v-model="entryForm.entryType"><el-option v-for="v in ['TARGET_DELIVERY','PRICE_ADJUSTMENT','SETTLEMENT','OTHER_FEE','INVOICE']" :key="v" :label="labels[v]" :value="v" /></el-select></el-form-item><el-form-item label="业务日期"><el-date-picker v-model="entryForm.occurredOn" type="date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="业务编号"><el-input v-model="entryForm.businessNumber" placeholder="发票号、结算单号等" /></el-form-item><el-form-item label="金额"><el-input v-model="entryForm.amount" :disabled="entryForm.entryType === 'TARGET_DELIVERY'" /></el-form-item><el-form-item class="entry-description" label="说明"><el-input v-model="entryForm.description" /></el-form-item><el-form-item><el-button type="primary" :loading="saving" @click="addEntry">记录明细</el-button></el-form-item></el-form><el-table :data="entries" border stripe><el-table-column label="类型" width="120"><template #default="s">{{ labels[s.row.entryType] }}</template></el-table-column><el-table-column prop="businessNumber" label="业务编号" width="150" /><el-table-column prop="occurredOn" label="日期" width="110" /><el-table-column label="金额" width="125" align="right"><template #default="s">{{ money(s.row.amount) }}</template></el-table-column><el-table-column prop="description" label="说明" min-width="200" /><el-table-column label="状态" width="90" align="center"><template #default="s"><el-tag :type="stateType(s.row.state)" effect="plain">{{ labels[s.row.state] ?? statusLabel(s.row.state) }}</el-tag></template></el-table-column><el-table-column label="操作" width="80" align="center"><template #default="s"><el-button v-if="s.row.state === 'VALID' && canContract" link type="danger" @click="voidEntry(s.row)">作废</el-button></template></el-table-column></el-table></el-drawer>

    <el-drawer v-model="reportDrawer" :title="`${activeProject?.projectNumber ?? ''} · 项目报告状态`" size="980px">
      <el-alert title="报告下载和打印使用两分钟、单次有效的受控授权，并执行委托打印次数限制。" type="info" show-icon :closable="false" />
      <el-table :data="projectReports" border stripe class="finance-report-table">
        <el-table-column prop="reportNumber" label="报告编号" width="160" /><el-table-column prop="title" label="报告名称" min-width="200" show-overflow-tooltip />
        <el-table-column label="修订" width="80"><template #default="s">{{ s.row.revisionLabel }}</template></el-table-column>
        <el-table-column label="状态" width="100" align="center"><template #default="s"><el-tag :type="stateType(s.row.state)" effect="plain">{{ labels[s.row.state] ?? statusLabel(s.row.state) }}</el-tag></template></el-table-column>
        <el-table-column prop="reviewActor" label="审核人" width="110" /><el-table-column prop="issueActor" label="签发人" width="110" /><el-table-column prop="sealActor" label="签章人" width="110" />
        <el-table-column label="发放" width="75"><template #default="s">{{ s.row.distributionCount }}</template></el-table-column>
        <el-table-column label="更新时间" width="170"><template #default="s">{{ datetime(s.row.updatedAt) }}</template></el-table-column>
        <el-table-column label="操作" width="150" fixed="right" align="center"><template #default="s"><el-button link type="primary" :icon="Download" :disabled="!s.row.pdfReady" @click="openReportFile(s.row, false)">下载</el-button><el-button link type="primary" :icon="Printer" :disabled="!s.row.pdfReady" @click="openReportFile(s.row, true)">打印</el-button></template></el-table-column>
        <template #empty><el-empty description="当前工程暂无报告" /></template>
      </el-table>
    </el-drawer>

    <el-dialog v-model="jobDialog" title="财务后台作业" width="620px" :close-on-click-modal="false">
      <el-descriptions v-if="currentJob" :column="2" border><el-descriptions-item label="作业类型">{{ jobTypeLabel(currentJob.jobType) }}</el-descriptions-item><el-descriptions-item label="状态"><el-tag :type="currentJob.state === 'COMPLETED' ? 'success' : currentJob.state === 'FAILED' ? 'danger' : 'warning'">{{ statusLabel(currentJob.state) }}</el-tag></el-descriptions-item><el-descriptions-item label="尝试次数">{{ currentJob.attemptCount }}</el-descriptions-item><el-descriptions-item label="创建时间">{{ datetime(currentJob.createdAt) }}</el-descriptions-item><el-descriptions-item v-if="currentJob.lastError" label="失败原因" :span="2">{{ failureReasonLabel(currentJob.lastError, '财务后台作业失败') }}</el-descriptions-item></el-descriptions>
      <template #footer><el-button @click="jobDialog = false">关闭</el-button><el-button v-if="currentJob?.state === 'FAILED'" @click="retryJob">重试</el-button><el-button v-if="currentJob?.resultFileId" type="primary" :icon="Download" @click="downloadJob">下载结果</el-button></template>
    </el-dialog>
  </section>
</template>
