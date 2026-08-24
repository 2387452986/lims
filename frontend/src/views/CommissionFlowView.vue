<script setup lang="ts">
import { ArrowLeft, Download, Plus, Refresh, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, api, errorMessage } from '../api'
import { useAuthStore } from '../stores/auth'

interface Option { id: string; code: string; label: string }
interface References { parameters: Option[]; people: Option[]; equipment: Option[] }
interface Commission { id: string; number: string; projectName: string; customerName: string; contactName: string; commissionedOn: string; dueOn: string; state: string; version: number }
interface FrozenParameter { parameterId: string; code: string; name: string; unit?: string; required: boolean; defaultCommissioned: boolean; sampleGroupId: string }
interface FrozenSampleGroup { id: string; clientKey: string; name: string; description?: string; quantity: string; unit: string; parameterIds: string[] }
interface FrozenSelection { projectRevisionId: string; projectName: string; revisionNumber: number; parameters: FrozenParameter[]; sampleGroups: FrozenSampleGroup[] }
interface Sample { id: string; sampleGroupId: string; number: string; name: string; specification: string; receivedQuantity: string; availableQuantity: string; unit: string; samplingDate?: string; moldingDate?: string; plannedTestDate?: string; ageDays?: number; curingMethod?: string; strengthGrade?: string; structurePart?: string; chipCode?: string; representedQuantity?: string; representedUnit?: string; state: string; version: number }
interface Task { id: string; number: string; sampleId: string; sampleNumber: string; parameterName: string; assignedTo?: string; assigneeName?: string; dueAt?: string; state: string; version: number }
interface TaskBriefing { id: string; code: string; title: string; briefingType: string; content: string; readSeconds: number; ruleVersion: number; startedAt?: string; eligibleAt?: string; acknowledgedAt?: string; readVersion: number; remainingSeconds: number }
interface TestRecord { id: string; taskId: string; taskNumber: string; parameterName: string; values: Record<string, unknown>; result: string; state: string; equipmentId?: string; version: number; updatedAt: string }
interface Report { id: string; number: string; title: string; currentRevision: number; revisionLabel: string; state: string; content: Record<string, unknown>; pdfFileId?: string; pdfFinalized: boolean; signed: boolean; sealed: boolean; version: number }
interface PrintAuthorization { downloadPath: string; downloadToken: string; expiresAt: string; remainingCount?: number }
interface Distribution { id: string; recipient: string; method: string; copyNumber?: string; distributedAt: string; acknowledgedBy?: string; acknowledgedAt?: string; version: number }

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const commissionId = computed(() => String(route.params.commissionId))
const loading = ref(false)
const importing = ref(false)
const importInput = ref<HTMLInputElement>()
const flowTabs = new Set(['samples', 'tasks', 'tests', 'reports'])
const requestedTab = () => flowTabs.has(String(route.query.tab)) ? String(route.query.tab) : 'samples'
const activeTab = ref(requestedTab())
const commission = ref<Commission>()
const samples = ref<Sample[]>([])
const tasks = ref<Task[]>([])
const records = ref<TestRecord[]>([])
const reports = ref<Report[]>([])
const detectionSelections = ref<FrozenSelection[]>([])
const distributions = ref<Record<string, Distribution[]>>({})
const references = ref<References>({ parameters: [], people: [], equipment: [] })

const sampleDialog = ref(false)
const taskDialog = ref(false)
const assignmentDialog = ref(false)
const testDialog = ref(false)
const confirmDialog = ref(false)
const reportDialog = ref(false)
const distributionDialog = ref(false)
const passwordDialog = ref(false)
const exceptionDialog = ref(false)
const dataChangeDialog = ref(false)
const safetyDialog = ref(false)
const safetyTask = ref<Task>()
const safetyBriefings = ref<TaskBriefing[]>([])
const safetyClock = ref(Date.now())
let safetyTimer: number | undefined
const sampleForm = reactive({ sampleGroupId: '', name: '', specification: '', quantity: '', unit: '组',
  samplingDate: '', moldingDate: '', plannedTestDate: '', ageDays: undefined as number | undefined,
  curingMethod: '', strengthGrade: '', structurePart: '', chipCode: '', representedQuantity: '', representedUnit: '' })
const taskForm = reactive({ sampleId: '', parameterId: '', dueAt: '' })
const assignmentForm = reactive({ task: undefined as Task | undefined, action: 'ASSIGN', assignedTo: '' })
const testForm = reactive({ taskId: '', equipmentId: '', load: '', area: '' })
const confirmForm = reactive({ record: undefined as TestRecord | undefined, result: 'CONFORMING', comment: '' })
const reportForm = reactive({ title: '', projectOverview: '', conclusion: '' })
const distributionForm = reactive({ report: undefined as Report | undefined, recipient: '', method: 'CONTROLLED_COPY', copyNumber: '' })
const passwordForm = reactive({ report: undefined as Report | undefined, action: '', password: '' })
const exceptionForm = reactive({ resourceType: 'TEST_TASK', resourceId: '', blockerCode: 'PERSON_NOT_QUALIFIED', reason: '', hours: 8 })
const dataChangeForm = reactive({ record: undefined as TestRecord | undefined, reasonCode: 'CORRECTION', reason: '', equipmentId: '', load: '', area: '' })

const commissionActions: Record<string, string[]> = {
  DRAFT: ['SUBMIT'], PRE_ACCEPTED: ['ACCEPT', 'REJECT', 'WITHDRAW'], ACCEPTED: ['START_SAMPLING', 'CANCEL'],
  SAMPLING: ['COMPLETE_SAMPLING'], ASSIGNED: ['START_TESTING'], TESTING: ['COMPLETE_TESTING'],
  REPORTING: ['MARK_ISSUED'], ISSUED: ['ARCHIVE'],
}
const sampleActions: Record<string, string[]> = {
  EXPECTED: ['RECEIVE'], RECEIVED: ['HANDOVER', 'RETURN'], HANDED_OVER: ['START_TEST', 'RETURN'],
  IN_TEST: ['RETAIN', 'RETURN', 'DISPOSE'], RETAINED: ['RETURN', 'DISPOSE'],
}
const taskActions: Record<string, string[]> = {
  UNASSIGNED: ['ASSIGN'], ASSIGNED: ['ACCEPT', 'REJECT', 'REASSIGN'], ACCEPTED: ['START', 'REASSIGN'],
  IN_PROGRESS: ['REASSIGN'], SUBMITTED: ['RETURN', 'COMPLETE'], RETURNED: ['REASSIGN'],
}
const testActions: Record<string, string[]> = { DRAFT: ['SUBMIT'], SUBMITTED: ['RETURN', 'CONFIRM'], RETURNED: ['RESUBMIT'] }
const reportActions: Record<string, string[]> = {
  DRAFT: ['SUBMIT_REVIEW'], IN_REVIEW: ['APPROVE', 'RETURN'], RETURNED: ['RESUBMIT'],
  APPROVED: ['ISSUE'], ISSUED: ['SEAL', 'DISTRIBUTE', 'VOID'], DISTRIBUTED: ['ARCHIVE', 'VOID'], ARCHIVED: ['VOID'],
}
const labels: Record<string, string> = {
  SUBMIT: '提交', ACCEPT: '受理', REJECT: '拒绝', START_SAMPLING: '开始样品接收', COMPLETE_SAMPLING: '完成样品阶段',
  START_TESTING: '开始检测', COMPLETE_TESTING: '完成检测', MARK_ISSUED: '同步签发状态', ARCHIVE: '归档',
  RECEIVE: '接收', HANDOVER: '交接', START_TEST: '进入检测', RETAIN: '留样', RETURN: '退回', DISPOSE: '处置',
  ASSIGN: '分配', REASSIGN: '改派', START: '开始', COMPLETE: '完成', RESUBMIT: '重新提交',
  CONFIRM: '确认数据', SUBMIT_REVIEW: '提交审核', APPROVE: '审核通过', ISSUE: '签发', SEAL: '签章',
  DISTRIBUTE: '发放', VOID: '作废',
  WITHDRAW: '撤销提交', CANCEL: '作废',
}
const taskActionLabel = (action: string) => action === 'ACCEPT' ? '接收' : labels[action]
const availableTestActions = (record: TestRecord) => (testActions[record.state] ?? []).filter((action) => {
  if (['CONFIRM', 'RETURN'].includes(action)) return auth.user?.permissions.includes('TEST_CONFIRM')
  return auth.user?.permissions.includes('BUSINESS_TRANSITION')
})
const reportActionBlocker = (report: Report, action: string) => {
  if (action === 'DISTRIBUTE' && !report.pdfFinalized) return '受控最终版生成完成后才能发放'
  if (action === 'SEAL' && report.sealed) return '当前报告版本已完成签章'
  return ''
}

const eligibleTasks = computed(() => tasks.value.filter((task) => ['IN_PROGRESS', 'RETURNED'].includes(task.state) && !records.value.some((record) => record.taskId === task.id && record.state !== 'SUPERSEDED')))
const sampleGroups = computed(() => detectionSelections.value.flatMap((selection) => selection.sampleGroups.map((group) => ({ ...group, projectName: selection.projectName }))))
const taskParameterOptions = computed<Option[]>(() => {
  const sample = samples.value.find((item) => item.id === taskForm.sampleId)
  if (!sample) return []
  const seen = new Set<string>()
  return detectionSelections.value.flatMap((selection) => selection.parameters)
    .filter((parameter) => parameter.sampleGroupId === sample.sampleGroupId && !seen.has(parameter.parameterId) && seen.add(parameter.parameterId))
    .map((parameter) => ({ id: parameter.parameterId, code: parameter.code, label: parameter.name }))
})
const sampleGroupName = (id: string) => sampleGroups.value.find((group) => group.id === id)?.name ?? '未识别样品组'
const canCreateTask = computed(() => ['ASSIGNED', 'TESTING'].includes(commission.value?.state ?? '')
  && samples.value.some((sample) => ['RECEIVED', 'HANDED_OVER', 'IN_TEST'].includes(sample.state)))
const testValue = (record: TestRecord, key: string) => String(record.values[key] ?? '—')
const stateLabel = (state?: string) => ({
  DRAFT: '草稿', PRE_ACCEPTED: '待受理', ACCEPTED: '已受理', SAMPLING: '样品阶段', ASSIGNED: '已分配',
  TESTING: '检测中', REPORTING: '报告阶段', ISSUED: '已签发', ARCHIVED: '已归档', REJECTED: '已拒绝',
  CANCELLED: '已取消', EXPECTED: '待接收', RECEIVED: '已接收', HANDED_OVER: '已交接', IN_TEST: '检测中',
  RETAINED: '留样', RETURNED: '已退回', DISPOSED: '已处置', UNASSIGNED: '待分配', IN_PROGRESS: '进行中',
  SUBMITTED: '待复核', COMPLETED: '已完成', CONFIRMED: '已确认', SUPERSEDED: '已替代', IN_REVIEW: '审核中',
  APPROVED: '待签发', DISTRIBUTED: '已发放', VOID: '已作废', CONFORMING: '合格', NONCONFORMING: '不合格',
  INVALID: '无效', PENDING: '待判定',
}[state ?? ''] ?? state ?? '—')
const formatDateTime = (value?: string) => value ? new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
}).format(new Date(value)) : '—'

async function load() {
  loading.value = true
  try {
    const [commissionData, sampleData, taskData, testData, reportData, referenceData, selectionData] = await Promise.all([
      api<Commission>(`/api/commissions/${commissionId.value}`),
      api<Sample[]>(`/api/samples?commissionId=${commissionId.value}`),
      api<Task[]>(`/api/tasks?commissionId=${commissionId.value}`),
      api<TestRecord[]>(`/api/tests?commissionId=${commissionId.value}`),
      api<Report[]>(`/api/reports?commissionId=${commissionId.value}`),
      api<References>(`/api/reference-data?commissionId=${commissionId.value}`),
      api<FrozenSelection[]>(`/api/commissions/${commissionId.value}/detection-selections`),
    ])
    commission.value = commissionData
    samples.value = sampleData
    tasks.value = taskData
    records.value = testData
    reports.value = reportData
    detectionSelections.value = selectionData
    distributions.value = Object.fromEntries(await Promise.all(reportData.map(async (report) => [
      report.id,
      await api<Distribution[]>(`/api/reports/${report.id}/distributions`),
    ])))
    references.value = referenceData
  } catch (error) { showError(error) } finally { loading.value = false }
}

function showError(error: unknown) {
  ElMessage.error(errorMessage(error))
}

async function optionalComment(action: string) {
  if (!['REJECT', 'RETURN', 'VOID', 'DISPOSE'].includes(action)) return ''
  const result = await ElMessageBox.prompt(`请填写“${labels[action]}”原因`, '操作说明', { inputType: 'textarea', inputValidator: (value) => Boolean(value.trim()) || '原因不能为空' })
  return result.value
}

async function transitionCommission(action: string) {
  if (!commission.value) return
  try {
    const comment = await optionalComment(action)
    await api(`/api/commissions/${commission.value.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ action, expectedVersion: commission.value.version, comment }) })
    ElMessage.success(`已${labels[action]}`)
    await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

async function createSample() {
  if (!sampleForm.sampleGroupId) return ElMessage.warning('请选择委托冻结的样品组')
  try {
    const optional = (value: string) => value.trim() || null
    await api('/api/samples', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({
      commissionId: commissionId.value, ...sampleForm,
      samplingDate: sampleForm.samplingDate || null, moldingDate: sampleForm.moldingDate || null,
      plannedTestDate: sampleForm.plannedTestDate || null, curingMethod: optional(sampleForm.curingMethod),
      strengthGrade: optional(sampleForm.strengthGrade), structurePart: optional(sampleForm.structurePart),
      chipCode: optional(sampleForm.chipCode), representedQuantity: optional(sampleForm.representedQuantity),
      representedUnit: optional(sampleForm.representedUnit),
    }) })
    sampleDialog.value = false; ElMessage.success('样品已登记'); await load()
  } catch (error) { showError(error) }
}

function openSampleCreate(group?: FrozenSampleGroup) {
  const target = group ?? sampleGroups.value[0]
  Object.assign(sampleForm, {
    sampleGroupId: target?.id ?? '', name: target?.name ?? '', specification: target?.description ?? '',
    quantity: target?.quantity ?? '1', unit: target?.unit ?? '组', samplingDate: '', moldingDate: '',
    plannedTestDate: '', ageDays: undefined, curingMethod: '', strengthGrade: '', structurePart: '',
    chipCode: '', representedQuantity: '', representedUnit: '',
  })
  sampleDialog.value = true
}

async function transitionSample(sample: Sample, action: string) {
  try {
    const comment = await optionalComment(action)
    await api(`/api/samples/${sample.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ action, expectedVersion: sample.version, comment }) })
    ElMessage.success(`样品已${labels[action]}`); await load()
  } catch (error) { showError(error) }
}

async function splitSample(sample: Sample) {
  try {
    const name = await ElMessageBox.prompt('请输入拆分后样品名称', '拆分样品', { inputValue: `${sample.name}-分样` })
    const quantity = await ElMessageBox.prompt(`当前可用 ${sample.availableQuantity}${sample.unit}，请输入拆分数量`, '拆分数量', { inputPattern: /^\d+(\.\d{1,6})?$/, inputErrorMessage: '请输入有效数量' })
    await api(`/api/samples/${sample.id}/split`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ name: name.value, quantity: quantity.value, expectedVersion: sample.version, comment: '流程页面拆分' }) })
    ElMessage.success('样品已拆分'); await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

function openTaskCreate() {
  const sample = samples.value.find((item) => ['HANDED_OVER', 'IN_TEST', 'RECEIVED'].includes(item.state)) ?? samples.value[0]
  Object.assign(taskForm, { sampleId: sample?.id ?? '', parameterId: '', dueAt: '' })
  taskForm.parameterId = taskParameterOptions.value[0]?.id ?? ''
  taskDialog.value = true
}

async function createTask() {
  if (!taskForm.sampleId || !taskForm.parameterId) return ElMessage.warning('请选择样品及其冻结检测参数')
  try {
    await api('/api/tasks', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ commissionId: commissionId.value, ...taskForm, dueAt: taskForm.dueAt || null }) })
    taskDialog.value = false; ElMessage.success('任务已创建'); await load()
  } catch (error) { showError(error) }
}

function openAssignment(task: Task, action: string) {
  Object.assign(assignmentForm, { task, action, assignedTo: task.assignedTo ?? '' })
  assignmentDialog.value = true
}

async function transitionTask(task: Task, action: string, assignedTo?: string) {
  try {
    const comment = await optionalComment(action)
    await api(`/api/tasks/${task.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ action, expectedVersion: task.version, assignedTo: assignedTo || undefined, comment }) })
    assignmentDialog.value = false; ElMessage.success(`任务已${taskActionLabel(action)}`); await load()
  } catch (error) {
    if (error instanceof ApiError && error.code === 'PERSON_NOT_QUALIFIED') {
      assignmentDialog.value = false
      openQualityException('TEST_TASK', task.id, 'PERSON_NOT_QUALIFIED')
    }
    showError(error)
  }
}

function briefingRemaining(item: TaskBriefing) {
  if (!item.eligibleAt || item.acknowledgedAt) return 0
  return Math.max(0, Math.ceil((new Date(item.eligibleAt).getTime() - safetyClock.value) / 1000))
}

function stopSafetyTimer() {
  if (safetyTimer) window.clearInterval(safetyTimer)
  safetyTimer = undefined
}

async function openSafetyBriefings(task: Task) {
  try {
    const items = await api<TaskBriefing[]>(`/api/tasks/${task.id}/safety-briefings`)
    if (items.length === 0) return transitionTask(task, 'ACCEPT')
    safetyTask.value = task
    safetyBriefings.value = items
    safetyDialog.value = true
    safetyClock.value = Date.now()
    stopSafetyTimer()
    safetyTimer = window.setInterval(() => { safetyClock.value = Date.now() }, 500)
  } catch (error) { showError(error) }
}

async function startBriefing(item: TaskBriefing) {
  if (!safetyTask.value) return
  try {
    await api(`/api/tasks/${safetyTask.value.id}/safety-briefings/${item.id}/read-sessions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ruleVersion: item.ruleVersion }),
    })
    safetyBriefings.value = await api<TaskBriefing[]>(`/api/tasks/${safetyTask.value.id}/safety-briefings`)
  } catch (error) { showError(error) }
}

async function acknowledgeBriefing(item: TaskBriefing) {
  if (!safetyTask.value || briefingRemaining(item) > 0) return
  try {
    await api(`/api/tasks/${safetyTask.value.id}/safety-briefings/${item.id}/acknowledgements`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ruleVersion: item.ruleVersion, expectedVersion: item.readVersion }),
    })
    safetyBriefings.value = await api<TaskBriefing[]>(`/api/tasks/${safetyTask.value.id}/safety-briefings`)
    ElMessage.success(`已确认：${item.title}`)
  } catch (error) { showError(error) }
}

async function acceptAfterBriefings() {
  if (!safetyTask.value || safetyBriefings.value.some((item) => !item.acknowledgedAt)) return
  const task = safetyTask.value
  safetyDialog.value = false
  await transitionTask(task, 'ACCEPT')
}

async function runTaskAction(task: Task, action: string) {
  if (['ASSIGN', 'REASSIGN'].includes(action)) return openAssignment(task, action)
  if (action === 'ACCEPT') return openSafetyBriefings(task)
  await transitionTask(task, action)
}

function openTestCreate() {
  Object.assign(testForm, { taskId: eligibleTasks.value[0]?.id ?? '', equipmentId: references.value.equipment[0]?.id ?? '', load: '', area: '' })
  testDialog.value = true
}

async function createTest() {
  try {
    await api('/api/tests', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ taskId: testForm.taskId, equipmentId: testForm.equipmentId, values: { load: testForm.load, area: testForm.area } }) })
    testDialog.value = false; ElMessage.success('试验记录已保存'); await load()
  } catch (error) { showError(error) }
}

async function importTests(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  importing.value = true
  try {
    const body = new FormData()
    body.append('file', file)
    const result = await api<{ importedCount: number }>('/api/test-imports', {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body,
    })
    ElMessage.success(`已原子导入 ${result.importedCount} 条试验记录`)
    await load()
  } catch (error) { showError(error) } finally { importing.value = false }
}

function downloadImportTemplate() { window.location.assign('/api/test-imports/template') }

function openConfirm(record: TestRecord) { Object.assign(confirmForm, { record, result: 'CONFORMING', comment: '' }); confirmDialog.value = true }

async function transitionTest(record: TestRecord, action: string, result?: string, comment?: string) {
  try {
    if (action === 'CONFIRM' && !result) return openConfirm(record)
    const reason = comment ?? await optionalComment(action)
    await api(`/api/tests/${record.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ action, expectedVersion: record.version, result, comment: reason }) })
    confirmDialog.value = false; ElMessage.success(`试验记录已${labels[action]}`); await load()
  } catch (error) {
    if (error instanceof ApiError && error.code === 'EQUIPMENT_NOT_VALID') {
      openQualityException('TEST_RECORD', record.id, 'EQUIPMENT_NOT_VALID')
    }
    showError(error)
  }
}

function openQualityException(resourceType: string, resourceId: string, blockerCode: string) {
  Object.assign(exceptionForm, { resourceType, resourceId, blockerCode, reason: '', hours: 8 })
  exceptionDialog.value = true
}

async function createQualityException() {
  try {
    const expiresAt = new Date(Date.now() + Math.max(1, Math.min(24, Number(exceptionForm.hours))) * 3600000).toISOString()
    await api('/api/exception-approvals', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ resourceType: exceptionForm.resourceType, resourceId: exceptionForm.resourceId,
        blockerCode: exceptionForm.blockerCode, reason: exceptionForm.reason, expiresAt }),
    })
    exceptionDialog.value = false
    ElMessage.success('质量例外已提交；需由质量负责人独立批准后重试当前动作')
  } catch (error) { showError(error) }
}

function openDataChange(record: TestRecord) {
  Object.assign(dataChangeForm, {
    record, reasonCode: 'CORRECTION', reason: '', equipmentId: record.equipmentId ?? '',
    load: String(record.values.load ?? ''), area: String(record.values.area ?? ''),
  })
  dataChangeDialog.value = true
}

async function createDataChange() {
  if (!dataChangeForm.record) return
  try {
    await api('/api/data-change-requests', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ testRecordId: dataChangeForm.record.id, reasonCode: dataChangeForm.reasonCode,
        reason: dataChangeForm.reason, equipmentId: dataChangeForm.equipmentId || null,
        values: { load: dataChangeForm.load, area: dataChangeForm.area } }),
    })
    dataChangeDialog.value = false
    ElMessage.success('数据修改申请已提交；批准并应用前原记录保持不变')
  } catch (error) { showError(error) }
}

async function createReport() {
  try {
    await api('/api/reports', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ commissionId: commissionId.value, title: reportForm.title, content: { 工程概况: reportForm.projectOverview, 检测结论: reportForm.conclusion } }) })
    reportDialog.value = false; ElMessage.success('报告草稿已创建'); await load()
  } catch (error) { showError(error) }
}

async function transitionReport(report: Report, action: string) {
  if (action === 'DISTRIBUTE') { Object.assign(distributionForm, { report, recipient: '', method: 'CONTROLLED_COPY', copyNumber: '' }); distributionDialog.value = true; return }
  if (['ISSUE', 'SEAL'].includes(action)) {
    Object.assign(passwordForm, { report, action, password: '' })
    passwordDialog.value = true
    return
  }
  await performReportTransition(report, action)
}

async function performReportTransition(report: Report, action: string) {
  try {
    const comment = await optionalComment(action)
    await api(`/api/reports/${report.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ action, expectedVersion: report.version, comment }) })
    ElMessage.success(`报告已${labels[action]}`); await load()
  } catch (error) { showError(error) }
}

async function verifyPasswordAndTransition() {
  if (!passwordForm.report || !passwordForm.password) return ElMessage.warning('请输入当前密码')
  try {
    await api('/api/auth/verify-password', { method: 'POST', body: JSON.stringify({ password: passwordForm.password }) })
    passwordDialog.value = false
    await performReportTransition(passwordForm.report, passwordForm.action)
  } catch (error) { showError(error) }
}

async function distribute() {
  if (!distributionForm.report) return
  try {
    await api(`/api/reports/${distributionForm.report.id}/distributions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ recipient: distributionForm.recipient, method: distributionForm.method, copyNumber: distributionForm.copyNumber, expectedVersion: distributionForm.report.version }) })
    distributionDialog.value = false; ElMessage.success('报告已发放'); await load()
  } catch (error) { showError(error) }
}

async function acknowledge(report: Report, distribution: Distribution) {
  try {
    const result = await ElMessageBox.prompt(`请填写“${distribution.recipient}”的实际签收人`, '确认报告签收', {
      inputValue: distribution.recipient,
      inputValidator: (value) => Boolean(value.trim()) || '签收人不能为空',
    })
    await api(`/api/reports/${report.id}/distributions/${distribution.id}/acknowledgements`, {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ acknowledgedBy: result.value, expectedVersion: distribution.version }),
    })
    ElMessage.success('签收凭证已登记')
    await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

async function revise(report: Report) {
  try {
    const reason = await ElMessageBox.prompt('请输入修订原因', '创建新修订', { inputType: 'textarea', inputValidator: (value) => Boolean(value.trim()) || '原因不能为空' })
    await api(`/api/reports/${report.id}/revisions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ expectedVersion: report.version, reasonCode: 'BUSINESS_REVISION', comment: reason.value }) })
    ElMessage.success('已创建下一修订版本'); await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

async function downloadReport(report: Report) {
  try {
    const authorization = await api<PrintAuthorization>(`/api/reports/${report.id}/print-authorizations`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ expectedVersion: report.version }),
    })
    const response = await fetch(authorization.downloadPath, {
      credentials: 'same-origin', headers: { 'X-Download-Token': authorization.downloadToken },
    })
    if (!response.ok) {
      const problem = await response.json().catch(() => ({})) as { detail?: string; title?: string }
      throw new Error(problem.detail || problem.title || '受控报告下载失败')
    }
    const url = URL.createObjectURL(await response.blob())
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${report.number}-${report.revisionLabel}.pdf`
    document.body.appendChild(anchor); anchor.click(); anchor.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 0)
    const remaining = authorization.remainingCount == null ? '' : `，本版本剩余 ${authorization.remainingCount} 次`
    ElMessage.success(`受控下载已授权${remaining}`)
  } catch (error) { showError(error) }
}

watch(() => taskForm.sampleId, () => {
  if (!taskParameterOptions.value.some((item) => item.id === taskForm.parameterId)) {
    taskForm.parameterId = taskParameterOptions.value[0]?.id ?? ''
  }
})
watch(() => route.query.tab, () => { activeTab.value = requestedTab() })

onMounted(load)
onBeforeUnmount(stopSafetyTimer)
</script>

<template>
  <section class="page flow-page" v-loading="loading">
    <header class="flow-heading">
      <div class="flow-title"><el-button text :icon="ArrowLeft" @click="router.push('/modules/commissions')">返回委托</el-button><h1>{{ commission?.number }} · {{ commission?.projectName }}</h1><p>{{ commission?.customerName }}　委托人：{{ commission?.contactName }}　计划完成：{{ commission?.dueOn }}</p></div>
      <div class="flow-actions"><el-button :icon="Refresh" @click="load">刷新</el-button><el-button v-for="action in commissionActions[commission?.state ?? ''] ?? []" :key="action" type="primary" @click="transitionCommission(action)">{{ labels[action] }}</el-button></div>
    </header>

    <section class="flow-status"><div><span>当前状态</span><strong>{{ stateLabel(commission?.state) }}</strong></div><div><span>委托日期</span><strong>{{ commission?.commissionedOn }}</strong></div><div><span>样品</span><strong>{{ samples.length }}</strong></div><div><span>检测任务</span><strong>{{ tasks.length }}</strong></div><div><span>已确认记录</span><strong>{{ records.filter(item => item.state === 'CONFIRMED').length }}</strong></div><div><span>报告</span><strong>{{ reports.length }}</strong></div></section>

    <el-tabs v-model="activeTab" class="flow-tabs">
      <el-tab-pane label="样品接收与交接" name="samples">
        <div class="tab-toolbar"><div><h2>样品</h2><p>样品必须来自委托冻结的样品组；接收、拆分、交接、留样、返还和处置均保留数量轨迹。</p></div><el-button type="primary" :icon="Plus" :disabled="sampleGroups.length === 0" @click="openSampleCreate()">登记样品</el-button></div>
        <el-table :data="samples" stripe border><el-table-column prop="number" label="样品编号" width="160" /><el-table-column prop="name" label="名称" min-width="170" /><el-table-column label="冻结样品组" min-width="170"><template #default="scope">{{ sampleGroupName(scope.row.sampleGroupId) }}</template></el-table-column><el-table-column prop="specification" label="规格" min-width="140" /><el-table-column label="数量" width="150"><template #default="scope">{{ scope.row.availableQuantity }}/{{ scope.row.receivedQuantity }} {{ scope.row.unit }}</template></el-table-column><el-table-column label="状态" width="120" align="center"><template #default="scope">{{ stateLabel(scope.row.state) }}</template></el-table-column><el-table-column label="操作" width="230" align="center"><template #default="scope"><div class="table-actions table-actions--center"><el-dropdown @command="transitionSample(scope.row, String($event))"><el-button link type="primary">状态操作</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="action in sampleActions[scope.row.state] ?? []" :key="action" :command="action">{{ labels[action] }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown><el-button link type="primary" :disabled="Number(scope.row.availableQuantity) <= 0" @click="splitSample(scope.row)">拆分</el-button></div></template></el-table-column></el-table>
      </el-tab-pane>

      <el-tab-pane label="任务分配" name="tasks">
        <div class="tab-toolbar"><div><h2>检测任务</h2><p>分配前校验人员资格，支持接收、拒绝、退回和改派。</p></div><div class="toolbar-buttons"><el-button @click="router.push('/modules/quality-exceptions')">质量例外</el-button><el-button type="primary" :icon="Plus" :disabled="!canCreateTask" @click="openTaskCreate">创建任务</el-button></div></div>
        <el-alert v-if="!canCreateTask" title="完成样品接收阶段后才能创建任务；任务参数将严格取自当前样品组的冻结快照。" type="info" :closable="false" show-icon />
        <el-table :data="tasks" stripe border><el-table-column prop="number" label="任务编号" width="160" /><el-table-column prop="sampleNumber" label="样品编号" width="160" /><el-table-column prop="parameterName" label="检测项目" min-width="190" /><el-table-column prop="assigneeName" label="责任人" width="110" /><el-table-column label="计划完成" width="180"><template #default="scope">{{ formatDateTime(scope.row.dueAt) }}</template></el-table-column><el-table-column label="状态" width="120" align="center"><template #default="scope">{{ stateLabel(scope.row.state) }}</template></el-table-column><el-table-column label="操作" width="140" align="center"><template #default="scope"><div class="table-actions table-actions--center"><el-dropdown @command="runTaskAction(scope.row, String($event))"><el-button link type="primary">执行动作</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="action in taskActions[scope.row.state] ?? []" :key="action" :command="action">{{ taskActionLabel(action) }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></template></el-table-column></el-table>
      </el-tab-pane>

      <el-tab-pane label="试验检测" name="tests">
        <div class="tab-toolbar"><div><h2>结构化试验记录</h2><p>十进制计算、设备有效性校验、退回、复测和不合格自动立项。</p></div><div class="toolbar-buttons"><el-button @click="router.push('/modules/data-change-requests')">修改申请</el-button><el-button :icon="Download" @click="downloadImportTemplate">下载受控模板</el-button><el-button :icon="Upload" :loading="importing" @click="importInput?.click()">原子导入</el-button><el-button type="primary" :icon="Plus" :disabled="eligibleTasks.length === 0" @click="openTestCreate">录入数据</el-button><input ref="importInput" class="visually-hidden" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="importTests" /></div></div>
        <el-table :data="records" stripe border><el-table-column label="任务 / 参数" min-width="210"><template #default="scope"><strong>{{ scope.row.taskNumber }}</strong><div class="cell-secondary">{{ scope.row.parameterName }}</div></template></el-table-column><el-table-column label="检测值" min-width="250"><template #default="scope"><span>荷载 {{ testValue(scope.row, 'load') }} N</span><div class="cell-secondary">面积 {{ testValue(scope.row, 'area') }} mm²<span v-if="scope.row.values.calculatedResult"> · 结果 {{ scope.row.values.calculatedResult }}</span></div></template></el-table-column><el-table-column label="结论" width="130" align="center"><template #default="scope">{{ stateLabel(scope.row.result) }}</template></el-table-column><el-table-column label="状态" width="120" align="center"><template #default="scope">{{ stateLabel(scope.row.state) }}</template></el-table-column><el-table-column label="更新时间" width="190"><template #default="scope">{{ formatDateTime(scope.row.updatedAt) }}</template></el-table-column><el-table-column label="操作" width="190" align="center"><template #default="scope"><div class="table-actions table-actions--center"><el-dropdown v-if="availableTestActions(scope.row).length" @command="transitionTest(scope.row, String($event))"><el-button link type="primary">执行动作</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="action in availableTestActions(scope.row)" :key="action" :command="action">{{ labels[action] }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown><el-button v-if="['SUBMITTED','CONFIRMED'].includes(scope.row.state)" link type="primary" @click="openDataChange(scope.row)">申请修改</el-button></div></template></el-table-column></el-table>
      </el-tab-pane>

      <el-tab-pane label="报告审核与签发" name="reports">
        <div class="tab-toolbar"><div><h2>报告版本链</h2><p>审核、签发、签章、发放、归档、作废和 R1/R2 修订。</p></div><el-button type="primary" :icon="Plus" @click="reportDialog = true">编制报告</el-button></div>
        <el-table :data="reports" stripe border><el-table-column prop="number" label="报告编号" width="170" /><el-table-column prop="title" label="标题" min-width="220" /><el-table-column prop="revisionLabel" label="修订" width="90" /><el-table-column label="状态" width="120" align="center"><template #default="scope">{{ stateLabel(scope.row.state) }}</template></el-table-column><el-table-column label="PDF" width="120"><template #default="scope"><span v-if="scope.row.sealed && !scope.row.pdfFinalized">最终版生成中</span><el-button v-else-if="scope.row.pdfFileId" link type="primary" @click="downloadReport(scope.row)">{{ scope.row.pdfFinalized ? '受控最终版' : '受控审核版' }}</el-button><span v-else>生成中</span></template></el-table-column><el-table-column label="签名/章" width="120"><template #default="scope">{{ scope.row.signed ? '已签' : '未签' }} / {{ scope.row.sealed ? '已盖章' : '未盖章' }}</template></el-table-column><el-table-column label="发放与签收" min-width="190"><template #default="scope"><div v-if="distributions[scope.row.id]?.length" class="distribution-list"><div v-for="item in distributions[scope.row.id]" :key="item.id"><span>{{ item.recipient }} · {{ item.acknowledgedAt ? `已签收（${item.acknowledgedBy}）` : '待签收' }}</span><el-button v-if="!item.acknowledgedAt && item.method !== 'INTERNAL_DOWNLOAD'" link type="primary" @click="acknowledge(scope.row, item)">登记签收</el-button></div></div><span v-else>尚未发放</span></template></el-table-column><el-table-column label="操作" width="190" align="center"><template #default="scope"><div class="table-actions table-actions--center"><el-dropdown @command="transitionReport(scope.row, String($event))"><el-button link type="primary">执行动作</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="action in reportActions[scope.row.state] ?? []" :key="action" :command="action" :disabled="Boolean(reportActionBlocker(scope.row, action))" :title="reportActionBlocker(scope.row, action)">{{ labels[action] }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown><el-button v-if="['ISSUED','DISTRIBUTED','ARCHIVED','VOID'].includes(scope.row.state)" link type="primary" @click="revise(scope.row)">创建修订</el-button></div></template></el-table-column></el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="sampleDialog" title="登记冻结样品组样品" width="760px"><el-form label-position="top"><el-form-item label="委托样品组" required><el-select v-model="sampleForm.sampleGroupId" @change="openSampleCreate(sampleGroups.find(item => item.id === sampleForm.sampleGroupId))"><el-option v-for="item in sampleGroups" :key="item.id" :label="`${item.projectName} · ${item.name}`" :value="item.id" /></el-select></el-form-item><div class="form-grid"><el-form-item label="样品名称" required><el-input v-model="sampleForm.name" /></el-form-item><el-form-item label="规格 / 型号"><el-input v-model="sampleForm.specification" /></el-form-item></div><div class="form-grid"><el-form-item label="预计数量" required><el-input v-model="sampleForm.quantity" /></el-form-item><el-form-item label="计量单位" required><el-input v-model="sampleForm.unit" /></el-form-item></div><el-divider content-position="left">混凝土试块与龄期信息（适用时填写）</el-divider><div class="form-grid"><el-form-item label="取样日期"><el-date-picker v-model="sampleForm.samplingDate" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="成型日期"><el-date-picker v-model="sampleForm.moldingDate" value-format="YYYY-MM-DD" /></el-form-item></div><div class="form-grid"><el-form-item label="龄期（天）"><el-input-number v-model="sampleForm.ageDays" :min="1" :max="3650" /></el-form-item><el-form-item label="计划检测日期"><el-date-picker v-model="sampleForm.plannedTestDate" value-format="YYYY-MM-DD" /></el-form-item></div><div class="form-grid"><el-form-item label="养护方式"><el-input v-model="sampleForm.curingMethod" maxlength="80" /></el-form-item><el-form-item label="强度等级"><el-input v-model="sampleForm.strengthGrade" maxlength="80" /></el-form-item></div><div class="form-grid"><el-form-item label="工程部位"><el-input v-model="sampleForm.structurePart" maxlength="240" /></el-form-item><el-form-item label="芯片码"><el-input v-model="sampleForm.chipCode" maxlength="160" /></el-form-item></div><div class="form-grid"><el-form-item label="代表数量"><el-input v-model="sampleForm.representedQuantity" /></el-form-item><el-form-item label="代表数量单位"><el-input v-model="sampleForm.representedUnit" maxlength="24" /></el-form-item></div></el-form><template #footer><el-button @click="sampleDialog=false">取消</el-button><el-button type="primary" @click="createSample">保存样品</el-button></template></el-dialog>
    <el-dialog v-model="taskDialog" title="创建冻结参数检测任务" width="560px"><el-alert title="参数选项仅来自当前委托且已分配到所选样品组的冻结快照，服务端会再次校验。" type="info" :closable="false" /><el-form label-position="top"><el-form-item label="样品" required><el-select v-model="taskForm.sampleId"><el-option v-for="item in samples" :key="item.id" :label="`${item.number} · ${item.name} · ${sampleGroupName(item.sampleGroupId)}`" :value="item.id" /></el-select></el-form-item><el-form-item label="检测参数" required><el-select v-model="taskForm.parameterId" :disabled="!taskForm.sampleId"><el-option v-for="item in taskParameterOptions" :key="item.id" :label="`${item.code} · ${item.label}`" :value="item.id" /></el-select><div v-if="taskForm.sampleId && taskParameterOptions.length === 0" class="el-form-item__error">该样品组没有可创建任务的冻结参数</div></el-form-item><el-form-item label="计划完成"><el-date-picker v-model="taskForm.dueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" /></el-form-item></el-form><template #footer><el-button @click="taskDialog=false">取消</el-button><el-button type="primary" :disabled="!taskForm.parameterId" @click="createTask">保存</el-button></template></el-dialog>
    <el-dialog v-model="assignmentDialog" :title="labels[assignmentForm.action]" width="460px"><el-form label-position="top"><el-form-item label="检测人员" required><el-select v-model="assignmentForm.assignedTo" filterable><el-option v-for="item in references.people" :key="item.id" :label="`${item.code} · ${item.label}`" :value="item.id" /></el-select></el-form-item></el-form><template #footer><el-button @click="assignmentDialog=false">取消</el-button><el-button type="primary" @click="assignmentForm.task && transitionTask(assignmentForm.task, assignmentForm.action, assignmentForm.assignedTo)">确认</el-button></template></el-dialog>
    <el-dialog v-model="safetyDialog" title="任务接收前安全交底" width="720px" @closed="stopSafetyTimer">
      <el-alert title="请逐项开始阅读。达到配置的最短阅读时间并确认后，服务端才允许接收任务。" type="warning" :closable="false" show-icon />
      <div class="task-briefing-list">
        <article v-for="item in safetyBriefings" :key="item.id" :class="{ acknowledged: item.acknowledgedAt }">
          <header><div><strong>{{ item.title }}</strong><small>{{ item.code }} · 最短阅读 {{ item.readSeconds }} 秒</small></div><el-tag :type="item.acknowledgedAt ? 'success' : item.startedAt ? 'warning' : 'info'" effect="plain">{{ item.acknowledgedAt ? '已确认' : item.startedAt ? '阅读中' : '未开始' }}</el-tag></header>
          <p>{{ item.content }}</p>
          <footer>
            <el-button v-if="!item.startedAt" type="primary" plain @click="startBriefing(item)">开始阅读</el-button>
            <span v-else-if="!item.acknowledgedAt && briefingRemaining(item) > 0">还需阅读 <strong>{{ briefingRemaining(item) }}</strong> 秒</span>
            <el-button v-if="item.startedAt && !item.acknowledgedAt" type="primary" :disabled="briefingRemaining(item) > 0" @click="acknowledgeBriefing(item)">确认已阅读并理解</el-button>
            <span v-if="item.acknowledgedAt" class="briefing-acknowledged">已由当前账号确认</span>
          </footer>
        </article>
      </div>
      <template #footer><el-button @click="safetyDialog=false">暂不接收</el-button><el-button type="primary" :disabled="safetyBriefings.some(item => !item.acknowledgedAt)" @click="acceptAfterBriefings">确认并接收任务</el-button></template>
    </el-dialog>
    <el-dialog v-model="testDialog" title="录入试验数据" width="560px"><el-form label-position="top"><el-form-item label="任务" required><el-select v-model="testForm.taskId"><el-option v-for="item in eligibleTasks" :key="item.id" :label="`${item.number} · ${item.parameterName}`" :value="item.id" /></el-select></el-form-item><el-form-item label="检测设备" required><el-select v-model="testForm.equipmentId"><el-option v-for="item in references.equipment" :key="item.id" :label="`${item.code} · ${item.label}`" :value="item.id" /></el-select></el-form-item><div class="form-grid"><el-form-item label="破坏荷载（N）" required><el-input v-model="testForm.load" /></el-form-item><el-form-item label="受压面积（mm²）" required><el-input v-model="testForm.area" /></el-form-item></div></el-form><template #footer><el-button @click="testDialog=false">取消</el-button><el-button type="primary" @click="createTest">保存草稿</el-button></template></el-dialog>
    <el-dialog v-model="confirmDialog" title="确认试验结果" width="500px"><el-form label-position="top"><el-form-item label="受控结论" required><el-select v-model="confirmForm.result"><el-option label="合格" value="CONFORMING" /><el-option label="不合格" value="NONCONFORMING" /><el-option label="数据无效" value="INVALID" /></el-select></el-form-item><el-form-item label="说明"><el-input v-model="confirmForm.comment" type="textarea" /></el-form-item></el-form><template #footer><el-button @click="confirmDialog=false">取消</el-button><el-button type="primary" @click="confirmForm.record && transitionTest(confirmForm.record, 'CONFIRM', confirmForm.result, confirmForm.comment)">确认</el-button></template></el-dialog>
    <el-dialog v-model="reportDialog" title="编制报告草稿" width="600px"><el-form label-position="top"><el-form-item label="报告标题" required><el-input v-model="reportForm.title" /></el-form-item><el-form-item label="工程概况" required><el-input v-model="reportForm.projectOverview" type="textarea" :rows="4" /></el-form-item><el-form-item label="检测结论" required><el-input v-model="reportForm.conclusion" type="textarea" :rows="4" /></el-form-item></el-form><template #footer><el-button @click="reportDialog=false">取消</el-button><el-button type="primary" @click="createReport">保存草稿</el-button></template></el-dialog>
    <el-dialog v-model="distributionDialog" title="受控发放" width="520px"><el-form label-position="top"><el-form-item label="收件人" required><el-input v-model="distributionForm.recipient" /></el-form-item><div class="form-grid"><el-form-item label="发放方式"><el-select v-model="distributionForm.method"><el-option label="现场领取" value="IN_PERSON" /><el-option label="内部下载" value="INTERNAL_DOWNLOAD" /><el-option label="受控副本" value="CONTROLLED_COPY" /></el-select></el-form-item><el-form-item label="副本编号"><el-input v-model="distributionForm.copyNumber" /></el-form-item></div></el-form><template #footer><el-button @click="distributionDialog=false">取消</el-button><el-button type="primary" @click="distribute">确认发放</el-button></template></el-dialog>
    <el-dialog v-model="passwordDialog" :title="`${labels[passwordForm.action]}前身份复核`" width="440px"><el-alert title="该操作会使用受控签名或印章，请输入当前账号密码。复核结果仅在 5 分钟内有效。" type="warning" :closable="false" /><el-form label-position="top"><el-form-item label="当前密码" required><el-input v-model="passwordForm.password" type="password" show-password autocomplete="current-password" @keyup.enter="verifyPasswordAndTransition" /></el-form-item></el-form><template #footer><el-button @click="passwordDialog=false">取消</el-button><el-button type="primary" @click="verifyPasswordAndTransition">复核并继续</el-button></template></el-dialog>
    <el-dialog v-model="exceptionDialog" title="申请限时质量例外" width="560px"><el-alert title="该申请不会立即放行；必须由非申请人的质量负责人批准，且最多有效 24 小时。" type="warning" :closable="false" /><el-form label-position="top"><div class="form-grid"><el-form-item label="资源类型"><el-input :model-value="exceptionForm.resourceType" disabled /></el-form-item><el-form-item label="阻断类型"><el-input :model-value="exceptionForm.blockerCode" disabled /></el-form-item></div><el-form-item label="资源 ID"><el-input :model-value="exceptionForm.resourceId" disabled /></el-form-item><el-form-item label="申请原因" required><el-input v-model="exceptionForm.reason" type="textarea" :rows="4" /></el-form-item><el-form-item label="有效时长（小时）"><el-input-number v-model="exceptionForm.hours" :min="1" :max="24" /></el-form-item></el-form><template #footer><el-button @click="exceptionDialog=false">取消</el-button><el-button type="primary" @click="createQualityException">提交独立审批</el-button></template></el-dialog>
    <el-dialog v-model="dataChangeDialog" title="检测数据修改申请" width="600px"><el-alert title="原始记录不会被覆盖；批准并应用后将生成新修订，原记录标记为已被新修订替代。" type="info" :closable="false" /><el-form label-position="top"><el-form-item label="原因代码" required><el-select v-model="dataChangeForm.reasonCode"><el-option label="录入纠错" value="CORRECTION" /><el-option label="设备信息纠正" value="EQUIPMENT_CORRECTION" /><el-option label="复核发现偏差" value="REVIEW_FINDING" /></el-select></el-form-item><el-form-item label="修改原因" required><el-input v-model="dataChangeForm.reason" type="textarea" :rows="3" /></el-form-item><el-form-item label="检测设备" required><el-select v-model="dataChangeForm.equipmentId"><el-option v-for="item in references.equipment" :key="item.id" :label="`${item.code} · ${item.label}`" :value="item.id" /></el-select></el-form-item><div class="form-grid"><el-form-item label="破坏荷载（N）" required><el-input v-model="dataChangeForm.load" /></el-form-item><el-form-item label="受压面积（mm²）" required><el-input v-model="dataChangeForm.area" /></el-form-item></div></el-form><template #footer><el-button @click="dataChangeDialog=false">取消</el-button><el-button type="primary" @click="createDataChange">提交修改申请</el-button></template></el-dialog>
  </section>
</template>
