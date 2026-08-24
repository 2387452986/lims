<script setup lang="ts">
import { DocumentChecked, Edit, Plus, Printer, Refresh, Search, Upload, View as ViewIcon, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, errorMessage } from '../api'
import { useAuthStore } from '../stores/auth'

interface CursorPage<T> { items: T[]; nextCursor?: string; hasMore: boolean; total: number }
interface Attachment { id: string; name: string; originalName?: string; mediaType?: string; sizeBytes?: number }
interface SummaryRow {
  id: string; documentType: 'STAGE' | 'COMPREHENSIVE'; number: string; projectId: string; customerId: string
  projectNumber: string; projectName: string; customerName: string; sectionName: string; periodStart?: string
  periodEnd?: string; reportOn: string; contentCodes: string[]; conclusion?: string; attachments: Attachment[]
  state: string; version: number; creatorName: string; publisherName?: string; publishedAt?: string
  updatedAt: string; allowedActions: string[]
}
interface SummaryOptions {
  projects: Array<{ id: string; number: string; name: string; customerId: string; customerName: string; sectionName: string }>
  customers: Array<{ id: string; code: string; name: string }>
}
interface StrengthResult { testRecordId: string; reportNumber?: string; constructionOn?: string; strengthValue: string; sortOrder: number }
interface StrengthRow {
  id: string; projectId: string; projectNumber: string; projectName: string; structurePartId: string; structurePart: string
  detectionProjectRevisionId: string; detectionProject: string; parameterId: string; parameterName: string
  standardId: string; standardCode: string; standardName: string; designRequirement: string; periodStart: string; periodEnd: string
  concreteQuantity: string; standardDeviationAverageLevel?: string; specimenGroupCount?: number; averageStrength?: string
  standardDeviation?: string; minimumStrength?: string; acceptanceCoefficient?: string; acceptanceConstant?: string
  statisticsText?: string; standardText?: string; calculationText?: string; conclusion?: string; remark?: string
  ruleReadiness: string; state: string; version: number; testerName?: string; reviewerName?: string; approverName?: string
  calculatedAt?: string; reviewedAt?: string; approvedAt?: string; updatedAt: string; results: StrengthResult[]
  blockers: string[]; allowedActions: string[]
}
interface StrengthOptions {
  projects: Array<{ id: string; number: string; name: string }>
  structureParts: Array<{ id: string; code: string; name: string }>
  parameters: Array<{ id: string; name: string; projectRevisionId: string; detectionProject: string; standards: Array<{ id: string; code: string; name: string }> }>
  designRequirements: string[]
}
interface StoredFile { id: string; originalName: string; mediaType: string; sizeBytes: number }

const route = useRoute()
const auth = useAuthStore()
const moduleKey = computed(() => String(route.params.moduleKey))
const mode = computed<'STAGE' | 'COMPREHENSIVE' | 'STRENGTH'>(() => moduleKey.value === 'stage-summary' ? 'STAGE' : moduleKey.value === 'comprehensive-reports' ? 'COMPREHENSIVE' : 'STRENGTH')
const title = computed(() => mode.value === 'STAGE' ? '阶段综合汇总' : mode.value === 'COMPREHENSIVE' ? '综合报告' : '混凝土强度评定')
const description = computed(() => mode.value === 'STRENGTH' ? '从已确认试验记录生成可追溯统计快照，审核与批准职责分离' : mode.value === 'STAGE' ? '按检测时段汇总工程检测成果，发布后形成不可变受控版本' : '形成工程级质量检测综合报告并保留发布证据链')
const canWrite = computed(() => Boolean(auth.user?.permissions.includes('MODULE_WRITE') || auth.user?.permissions.includes('SYSTEM_ADMIN')))
const canReview = computed(() => Boolean(auth.user?.permissions.includes('REPORT_REVIEW') || auth.user?.permissions.includes('SYSTEM_ADMIN')))
const canIssue = computed(() => Boolean(auth.user?.permissions.includes('REPORT_ISSUE') || auth.user?.permissions.includes('SYSTEM_ADMIN')))
const loading = ref(false)
const saving = ref(false)
const optionsLoading = ref(false)
const rows = ref<Array<SummaryRow | StrengthRow>>([])
const total = ref(0)
const nextCursor = ref('')
const cursorHistory = ref<string[]>([])
const pageSize = ref(20)
const drawer = ref(false)
const editMode = ref(false)
const editingId = ref('')
const activeTab = ref('evaluation')
const uploading = ref(false)
const summaryOptions = ref<SummaryOptions>({ projects: [], customers: [] })
const strengthOptions = ref<StrengthOptions>({ projects: [], structureParts: [], parameters: [], designRequirements: [] })
const selectedSummary = ref<SummaryRow>()
const selectedStrength = ref<StrengthRow>()
const filters = reactive({ dateRange: [] as string[], number: '', projectNumber: '', projectName: '', customerName: '', contentCode: '', state: '', revisionId: '', designRequirement: '', structurePart: '' })
const summaryForm = reactive({ number: '', projectId: '', customerId: '', sectionName: '', periodStart: '', periodEnd: '', reportOn: '', contentCodes: [] as string[], conclusion: '', attachments: [] as Attachment[], expectedVersion: null as number | null })
const strengthForm = reactive({ projectId: '', structurePartId: '', detectionProjectRevisionId: '', parameterId: '', standardId: '', designRequirement: '', periodStart: '', periodEnd: '', concreteQuantity: '', standardDeviationAverageLevel: '', remark: '', expectedVersion: null as number | null })

const contentOptions = [
  ['FOUNDATION_ENGINEERING', '基础工程'], ['EARTHWORK', '土方工程'], ['MASONRY_ENGINEERING', '砌石工程'],
  ['CONCRETE_ENGINEERING', '混凝土工程'], ['METAL_STRUCTURE', '金属结构'], ['MECHANICAL_ELECTRICAL', '机械电气'],
  ['AUTOMATION', '自动化'], ['BRIDGE_ENGINEERING', '桥梁工程'], ['ROAD_ENGINEERING', '道路工程'],
  ['BUILDING_ENGINEERING', '房屋建筑工程'], ['TEMPORARY_ENGINEERING', '临时工程'],
] as const
const contentLabels = Object.fromEntries(contentOptions)
const stateLabels: Record<string, string> = { DRAFT: '草稿', CALCULATED: '已统计', IN_REVIEW: '待审核', REVIEWED: '已审核', PUBLISHED: '已发布', APPROVED: '已批准', RETIRED: '已停用' }
const actionLabels: Record<string, string> = { UPDATE: '修改', PUBLISH: '发布', DISCARD: '作废草稿', RETIRE: '停用', CALCULATE: '生成统计', REOPEN: '重新计算', SUBMIT_REVIEW: '提交审核', REVIEW: '审核通过', RETURN: '退回', APPROVE: '批准' }
const selectedParameter = computed(() => strengthOptions.value.parameters.find(item => item.id === strengthForm.parameterId))
const detectionProjects = computed(() => [...new Map(strengthOptions.value.parameters.map(item => [item.projectRevisionId, { id: item.projectRevisionId, name: item.detectionProject }])).values()])
const strengthPeriod = computed<string[]>({
  get: () => strengthForm.periodStart && strengthForm.periodEnd ? [strengthForm.periodStart, strengthForm.periodEnd] : [],
  set: (value: string[]) => { strengthForm.periodStart = value[0] ?? ''; strengthForm.periodEnd = value[1] ?? '' },
})
const summaryReadOnly = computed(() => !editMode.value
  || Boolean(selectedSummary.value && selectedSummary.value.state !== 'DRAFT'))
const strengthReadOnly = computed(() => !editMode.value
  || Boolean(selectedStrength.value && selectedStrength.value.state !== 'DRAFT'))
let optionsRequestVersion = 0

function showError(error: unknown) {
  ElMessage.error(errorMessage(error))
}
function stateType(state: string) { return ['PUBLISHED', 'APPROVED'].includes(state) ? 'success' : state === 'RETIRED' ? 'info' : ['IN_REVIEW', 'REVIEWED'].includes(state) ? 'warning' : 'primary' }
function formatTime(value?: string) { return value ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) : '—' }
function contentText(codes: string[]) { return codes.map(code => contentLabels[code] ?? code).join('、') }
function resetFilters() { Object.assign(filters, { dateRange: [], number: '', projectNumber: '', projectName: '', customerName: '', contentCode: '', state: '', revisionId: '', designRequirement: '', structurePart: '' }); load(true) }
function params(cursor = '') {
  const query = new URLSearchParams({ cursor, limit: String(pageSize.value) })
  const add = (key: string, value: string) => { if (value) query.set(key, value) }
  if (mode.value === 'STRENGTH') {
    add('periodFrom', filters.dateRange[0]); add('periodTo', filters.dateRange[1]); add('detectionProjectRevisionId', filters.revisionId)
    add('designRequirement', filters.designRequirement); add('projectNumber', filters.projectNumber); add('projectName', filters.projectName)
    add('structurePart', filters.structurePart); add('state', filters.state)
  } else {
    query.set('documentType', mode.value); add('number', filters.number); add('projectNumber', filters.projectNumber)
    add('projectName', filters.projectName); add('customerName', filters.customerName); add('contentCode', filters.contentCode)
    add('state', filters.state); add('reportFrom', filters.dateRange[0]); add('reportTo', filters.dateRange[1])
  }
  return query.toString()
}
async function load(reset = false) {
  if (reset) cursorHistory.value = []
  loading.value = true
  try {
    const endpoint = mode.value === 'STRENGTH' ? '/api/concrete-strength-assessments' : '/api/quality-summary-documents'
    const page = await api<CursorPage<SummaryRow | StrengthRow>>(`${endpoint}?${params(cursorHistory.value.at(-1) ?? '')}`)
    rows.value = page.items; total.value = page.total; nextCursor.value = page.nextCursor ?? ''
  } catch (error) { showError(error) } finally { loading.value = false }
}
function nextPage() { if (nextCursor.value) { cursorHistory.value.push(nextCursor.value); load() } }
function previousPage() { if (cursorHistory.value.length) { cursorHistory.value.pop(); load() } }
async function loadSummaryOptions(search = '') {
  const requestVersion = ++optionsRequestVersion
  const retainedProject = summaryOptions.value.projects.find(item => item.id === summaryForm.projectId)
  const retainedCustomer = summaryOptions.value.customers.find(item => item.id === summaryForm.customerId)
  optionsLoading.value = true
  try {
    const query = new URLSearchParams(); if (search) query.set('q', search)
    const next = await api<SummaryOptions>(`/api/quality-summary-documents/options?${query}`)
    if (requestVersion !== optionsRequestVersion) return
    if (retainedProject && !next.projects.some(item => item.id === retainedProject.id)) next.projects.unshift(retainedProject)
    if (retainedCustomer && !next.customers.some(item => item.id === retainedCustomer.id)) next.customers.unshift(retainedCustomer)
    summaryOptions.value = next
  } catch (error) {
    if (requestVersion === optionsRequestVersion) showError(error)
  } finally { if (requestVersion === optionsRequestVersion) optionsLoading.value = false }
}
async function loadStrengthOptions(projectId = '', search = '') {
  const requestVersion = ++optionsRequestVersion
  const retainedProject = strengthOptions.value.projects.find(item => item.id === strengthForm.projectId)
  const retainedParameter = strengthOptions.value.parameters.find(item => item.id === strengthForm.parameterId)
  optionsLoading.value = true
  try {
    const query = new URLSearchParams(); if (projectId) query.set('projectId', projectId); if (search) query.set('q', search)
    const next = await api<StrengthOptions>(`/api/concrete-strength-assessments/options?${query}`)
    if (requestVersion !== optionsRequestVersion) return
    if (retainedProject && !next.projects.some(item => item.id === retainedProject.id)) next.projects.unshift(retainedProject)
    if (retainedParameter && !next.parameters.some(item => item.id === retainedParameter.id)) next.parameters.unshift(retainedParameter)
    strengthOptions.value = next
  } catch (error) {
    if (requestVersion === optionsRequestVersion) showError(error)
  } finally { if (requestVersion === optionsRequestVersion) optionsLoading.value = false }
}
function clearSummaryForm() { Object.assign(summaryForm, { number: '', projectId: '', customerId: '', sectionName: '', periodStart: '', periodEnd: '', reportOn: '', contentCodes: [], conclusion: '', attachments: [], expectedVersion: null }) }
function clearStrengthForm() { Object.assign(strengthForm, { projectId: '', structurePartId: '', detectionProjectRevisionId: '', parameterId: '', standardId: '', designRequirement: '', periodStart: '', periodEnd: '', concreteQuantity: '', standardDeviationAverageLevel: '', remark: '', expectedVersion: null }) }
async function openCreate() {
  editingId.value = ''; editMode.value = true; selectedSummary.value = undefined; selectedStrength.value = undefined; activeTab.value = 'evaluation'
  if (mode.value === 'STRENGTH') { clearStrengthForm(); await loadStrengthOptions() } else { clearSummaryForm(); await loadSummaryOptions() }
  drawer.value = true
}
async function openDetail(row: SummaryRow | StrengthRow, edit = false) {
  try {
    editingId.value = row.id; editMode.value = edit && canWrite.value; activeTab.value = 'evaluation'
    if (mode.value === 'STRENGTH') {
      const detail = await api<StrengthRow>(`/api/concrete-strength-assessments/${row.id}`); selectedStrength.value = detail
      await loadStrengthOptions(detail.projectId, detail.parameterName)
      if (!strengthOptions.value.projects.some(item => item.id === detail.projectId)) strengthOptions.value.projects.unshift({ id: detail.projectId, number: detail.projectNumber, name: detail.projectName })
      if (!strengthOptions.value.structureParts.some(item => item.id === detail.structurePartId)) strengthOptions.value.structureParts.unshift({ id: detail.structurePartId, code: '历史快照', name: detail.structurePart })
      if (!strengthOptions.value.parameters.some(item => item.id === detail.parameterId)) strengthOptions.value.parameters.unshift({ id: detail.parameterId, name: detail.parameterName, projectRevisionId: detail.detectionProjectRevisionId, detectionProject: detail.detectionProject, standards: [{ id: detail.standardId, code: detail.standardCode, name: detail.standardName }] })
      Object.assign(strengthForm, { projectId: detail.projectId, structurePartId: detail.structurePartId, detectionProjectRevisionId: detail.detectionProjectRevisionId, parameterId: detail.parameterId, standardId: detail.standardId, designRequirement: detail.designRequirement, periodStart: detail.periodStart, periodEnd: detail.periodEnd, concreteQuantity: detail.concreteQuantity, standardDeviationAverageLevel: detail.standardDeviationAverageLevel ?? '', remark: detail.remark ?? '', expectedVersion: detail.version })
    } else {
      const detail = await api<SummaryRow>(`/api/quality-summary-documents/${row.id}`); selectedSummary.value = detail
      await loadSummaryOptions(detail.projectName)
      if (!summaryOptions.value.projects.some(item => item.id === detail.projectId)) summaryOptions.value.projects.unshift({ id: detail.projectId, number: detail.projectNumber, name: detail.projectName, customerId: detail.customerId, customerName: detail.customerName, sectionName: detail.sectionName })
      if (!summaryOptions.value.customers.some(item => item.id === detail.customerId)) summaryOptions.value.customers.unshift({ id: detail.customerId, code: '', name: detail.customerName })
      Object.assign(summaryForm, { number: detail.number, projectId: detail.projectId, customerId: detail.customerId, sectionName: detail.sectionName, periodStart: detail.periodStart ?? '', periodEnd: detail.periodEnd ?? '', reportOn: detail.reportOn, contentCodes: [...detail.contentCodes], conclusion: detail.conclusion ?? '', attachments: detail.attachments.map(item => ({ ...item })), expectedVersion: detail.version })
    }
    drawer.value = true
    if (edit && !canWrite.value) ElMessage.warning('当前账号仅可查看')
  } catch (error) {
    showError(error)
  }
}
function openReadOnly(row: SummaryRow | StrengthRow) { return openDetail(row) }
function onSummaryProject(value: string) {
  const project = summaryOptions.value.projects.find(item => item.id === value)
  if (project) { summaryForm.customerId = project.customerId; summaryForm.sectionName = project.sectionName }
}
async function onStrengthProject(value: string) {
  strengthForm.structurePartId = ''; await loadStrengthOptions(value)
}
async function searchStrengthProjects(search: string) { await loadStrengthOptions('', search) }
async function searchStrengthParameters(search: string) { await loadStrengthOptions(strengthForm.projectId, search) }
function onParameter(value: string) {
  const parameter = strengthOptions.value.parameters.find(item => item.id === value)
  strengthForm.detectionProjectRevisionId = parameter?.projectRevisionId ?? ''
  strengthForm.standardId = parameter?.standards[0]?.id ?? ''
}
async function uploadAttachment(event: Event) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = ''; if (!file) return
  uploading.value = true
  try {
    const body = new FormData(); body.append('file', file); body.append('purpose', 'QUALITY_SUMMARY_ATTACHMENT')
    const stored = await api<StoredFile>('/api/files', { method: 'POST', body })
    if (!summaryForm.attachments.some(item => item.id === stored.id)) summaryForm.attachments.push({ id: stored.id, name: stored.originalName, originalName: stored.originalName, mediaType: stored.mediaType, sizeBytes: stored.sizeBytes })
    ElMessage.success('附件已完成安全校验')
  } catch (error) { showError(error) } finally { uploading.value = false }
}
async function saveSummary() {
  saving.value = true
  try {
    const body = { documentType: mode.value, number: summaryForm.number, projectId: summaryForm.projectId, customerId: summaryForm.customerId, sectionName: summaryForm.sectionName, periodStart: mode.value === 'STAGE' ? summaryForm.periodStart : null, periodEnd: mode.value === 'STAGE' ? summaryForm.periodEnd : null, reportOn: mode.value === 'STAGE' ? summaryForm.periodEnd : summaryForm.reportOn, contentCodes: summaryForm.contentCodes, conclusion: summaryForm.conclusion, attachmentFileIds: summaryForm.attachments.map(item => item.id), expectedVersion: summaryForm.expectedVersion }
    const path = editingId.value ? `/api/quality-summary-documents/${editingId.value}` : '/api/quality-summary-documents'
    const saved = await api<SummaryRow>(path, { method: editingId.value ? 'PUT' : 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) })
    editingId.value = saved.id; selectedSummary.value = saved; summaryForm.expectedVersion = saved.version; ElMessage.success('草稿已保存并写入审计'); await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function saveStrength() {
  saving.value = true
  try {
    const path = editingId.value ? `/api/concrete-strength-assessments/${editingId.value}` : '/api/concrete-strength-assessments'
    const saved = await api<StrengthRow>(path, { method: editingId.value ? 'PUT' : 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(strengthForm) })
    editingId.value = saved.id; selectedStrength.value = saved; strengthForm.expectedVersion = saved.version; ElMessage.success('评定草稿已保存'); await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function transitionSummary(action: string) {
  if (!selectedSummary.value) return
  let comment = `${actionLabels[action]}汇总报告`
  if (['RETIRE', 'DISCARD'].includes(action)) {
    try { comment = (await ElMessageBox.prompt('请填写停用或作废原因', actionLabels[action], { inputValidator: value => Boolean(value.trim()) || '原因不能为空' })).value } catch { return }
  } else { try { await ElMessageBox.confirm(`确认${actionLabels[action]}“${selectedSummary.value.number}”？`, '受控状态变更', { type: 'warning' }) } catch { return } }
  try {
    await api(`/api/quality-summary-documents/${selectedSummary.value.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ action, expectedVersion: selectedSummary.value.version, reasonCode: 'QUALITY_SUMMARY_CONTROL', comment }) })
    ElMessage.success(`${actionLabels[action]}成功`); await openDetail(selectedSummary.value); await load()
  } catch (error) { showError(error) }
}
async function transitionStrength(action: string) {
  if (!selectedStrength.value) return
  let comment = `${actionLabels[action]}混凝土强度评定`
  if (['RETURN', 'RETIRE', 'DISCARD'].includes(action)) {
    try { comment = (await ElMessageBox.prompt(`请填写${actionLabels[action]}原因`, actionLabels[action], { inputValidator: value => Boolean(value.trim()) || '原因不能为空' })).value } catch { return }
  } else { try { await ElMessageBox.confirm(`确认执行“${actionLabels[action]}”？`, '受控状态变更', { type: action === 'CALCULATE' ? 'info' : 'warning' }) } catch { return } }
  try {
    await api(`/api/concrete-strength-assessments/${selectedStrength.value.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ action, expectedVersion: selectedStrength.value.version, reasonCode: 'CONCRETE_STRENGTH_CONTROL', comment }) })
    ElMessage.success(`${actionLabels[action]}成功`); await openDetail(selectedStrength.value); await load()
  } catch (error) { showError(error) }
}
function actionVisible(action: string) {
  if (['PUBLISH', 'REVIEW', 'RETURN', 'RETIRE'].includes(action)) return canReview.value
  if (action === 'APPROVE') return canIssue.value
  return canWrite.value
}
function printCurrent() { window.print() }
async function initialize() {
  resetFilters();
  try { if (mode.value === 'STRENGTH') await loadStrengthOptions(); else await loadSummaryOptions() } catch (error) { showError(error) }
}
watch(moduleKey, initialize)
onMounted(initialize)
</script>

<template>
  <section class="page quality-document-page" v-loading="loading">
    <header class="page-heading">
      <div><h1>{{ title }}</h1><p>{{ description }}</p></div>
      <div class="heading-actions"><el-button :icon="Refresh" @click="load()">刷新</el-button><el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">新增</el-button></div>
    </header>

    <section class="quality-document-filter">
      <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" :start-placeholder="mode === 'STRENGTH' ? '验收开始' : '报告开始'" :end-placeholder="mode === 'STRENGTH' ? '验收结束' : '报告结束'" />
      <template v-if="mode !== 'STRENGTH'">
        <el-input v-model="filters.number" clearable placeholder="编号" @keyup.enter="load(true)" />
        <el-input v-model="filters.projectNumber" clearable placeholder="工程编号" @keyup.enter="load(true)" />
        <el-input v-model="filters.projectName" clearable placeholder="工程名称" @keyup.enter="load(true)" />
        <el-input v-model="filters.customerName" clearable placeholder="委托方" @keyup.enter="load(true)" />
        <el-select v-model="filters.contentCode" clearable placeholder="检测内容"><el-option v-for="item in contentOptions" :key="item[0]" :label="item[1]" :value="item[0]" /></el-select>
        <el-select v-model="filters.state" clearable placeholder="状态"><el-option v-for="item in ['DRAFT','PUBLISHED','RETIRED']" :key="item" :label="stateLabels[item]" :value="item" /></el-select>
      </template>
      <template v-else>
        <el-select v-model="filters.revisionId" clearable filterable placeholder="检测项目"><el-option v-for="item in detectionProjects" :key="item.id" :label="item.name" :value="item.id" /></el-select>
        <el-input v-model="filters.designRequirement" clearable placeholder="设计要求" />
        <el-input v-model="filters.projectNumber" clearable placeholder="工程编号" />
        <el-input v-model="filters.projectName" clearable placeholder="工程名称" />
        <el-input v-model="filters.structurePart" clearable placeholder="分项工程或构件名称" />
        <el-select v-model="filters.state" clearable placeholder="评定状态"><el-option v-for="item in ['DRAFT','CALCULATED','IN_REVIEW','REVIEWED','APPROVED','RETIRED']" :key="item" :label="stateLabels[item]" :value="item" /></el-select>
      </template>
      <div class="filter-actions"><el-button type="primary" :icon="Search" @click="load(true)">查询</el-button><el-button :icon="Refresh" @click="resetFilters">重置</el-button></div>
    </section>

    <el-table v-if="mode !== 'STRENGTH'" :data="rows" stripe border class="module-table quality-document-table" @row-dblclick="openReadOnly">
      <el-table-column prop="number" label="编号" width="185" fixed />
      <el-table-column :label="mode === 'STAGE' ? '检测日期' : '报告时间'" width="120"><template #default="scope">{{ mode === 'STAGE' ? `${scope.row.periodStart} 至 ${scope.row.periodEnd}` : scope.row.reportOn }}</template></el-table-column>
      <el-table-column prop="customerName" label="委托方" min-width="190" show-overflow-tooltip />
      <el-table-column prop="projectNumber" label="工程编号" width="150" />
      <el-table-column prop="projectName" label="工程名称" min-width="220" show-overflow-tooltip />
      <el-table-column prop="sectionName" label="标段" min-width="140" show-overflow-tooltip />
      <el-table-column label="检测内容" min-width="250" show-overflow-tooltip><template #default="scope">{{ contentText(scope.row.contentCodes) }}</template></el-table-column>
      <el-table-column prop="conclusion" label="检测结论" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" width="95" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)" effect="plain">{{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="130" fixed="right" align="center"><template #default="scope"><el-button link type="primary" :icon="ViewIcon" @click="openDetail(scope.row)">查看</el-button><el-button v-if="scope.row.state === 'DRAFT' && canWrite" link type="primary" :icon="Edit" @click="openDetail(scope.row, true)">修改</el-button></template></el-table-column>
      <template #empty><el-empty :description="`暂无${title}记录`" /></template>
    </el-table>

    <el-table v-else :data="rows" stripe border class="module-table quality-document-table" @row-dblclick="openReadOnly">
      <el-table-column prop="projectNumber" label="工程编号" width="155" fixed />
      <el-table-column prop="projectName" label="工程名称" min-width="220" show-overflow-tooltip />
      <el-table-column prop="structurePart" label="分项工程或构件名称" min-width="190" show-overflow-tooltip />
      <el-table-column prop="detectionProject" label="检测项目" min-width="175" show-overflow-tooltip />
      <el-table-column prop="designRequirement" label="设计要求" width="120" />
      <el-table-column label="验收时段" width="205"><template #default="scope">{{ scope.row.periodStart }} 至 {{ scope.row.periodEnd }}</template></el-table-column>
      <el-table-column prop="concreteQuantity" label="混凝土数量(m³)" width="135" align="right" />
      <el-table-column prop="specimenGroupCount" label="验收批试件(组)" width="125" align="right"><template #default="scope">{{ scope.row.specimenGroupCount ?? '—' }}</template></el-table-column>
      <el-table-column prop="averageStrength" label="平均强度(MPa)" width="130" align="right" />
      <el-table-column prop="standardDeviation" label="标准差(MPa)" width="115" align="right" />
      <el-table-column prop="minimumStrength" label="强度最小值(MPa)" width="140" align="right" />
      <el-table-column prop="acceptanceCoefficient" label="验收系数" width="100" align="right"><template #default="scope">{{ scope.row.acceptanceCoefficient ?? '待规则' }}</template></el-table-column>
      <el-table-column prop="acceptanceConstant" label="验收常数(MPa)" width="125" align="right"><template #default="scope">{{ scope.row.acceptanceConstant ?? '待规则' }}</template></el-table-column>
      <el-table-column label="评定状态" width="105" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)" effect="plain">{{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="135" fixed="right" align="center"><template #default="scope"><el-button link type="primary" :icon="ViewIcon" @click="openDetail(scope.row)">评定表</el-button><el-button v-if="scope.row.state === 'DRAFT' && canWrite" link type="primary" :icon="Edit" @click="openDetail(scope.row, true)">修改</el-button></template></el-table-column>
      <template #empty><el-empty description="暂无混凝土强度评定记录" /></template>
    </el-table>

    <footer class="table-footer"><span>共 {{ total }} 条 · 第 {{ cursorHistory.length + 1 }} 页</span><div class="cursor-pagination"><el-select v-model="pageSize" @change="load(true)"><el-option :value="20" label="20 条/页" /><el-option :value="50" label="50 条/页" /><el-option :value="100" label="100 条/页" /></el-select><el-button :disabled="!cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="!nextCursor" @click="nextPage">下一页</el-button></div></footer>

    <el-drawer v-model="drawer" :title="editingId ? title : `新增${title}`" size="min(920px, 82vw)" class="quality-document-drawer">
      <template #header>
        <div class="drawer-title"><div><strong>{{ editingId ? title : `新增${title}` }}</strong><span v-if="editingId">版本 {{ selectedSummary?.version ?? selectedStrength?.version }} · {{ stateLabels[selectedSummary?.state ?? selectedStrength?.state ?? 'DRAFT'] }}</span><span v-else>建立受控草稿，提交后保留完整审计链</span></div><el-button v-if="editingId" :icon="Printer" @click="printCurrent">打印当前视图</el-button></div>
      </template>

      <template v-if="mode !== 'STRENGTH'">
        <el-alert v-if="selectedSummary?.state !== 'DRAFT' && selectedSummary" title="已发布内容不可原地修改；调整需创建新的受控记录。" type="success" :closable="false" show-icon />
        <el-form label-position="top" class="document-form">
          <div class="document-form-grid">
            <el-form-item :label="mode === 'STAGE' ? '阶段检测成果编号' : '质量检测报告编号'" required><el-input v-model="summaryForm.number" maxlength="80" :disabled="summaryReadOnly" /></el-form-item>
            <el-form-item label="工程名称" required><el-select v-model="summaryForm.projectId" filterable remote reserve-keyword :remote-method="loadSummaryOptions" :loading="optionsLoading" :disabled="summaryReadOnly" @change="onSummaryProject"><el-option v-for="item in summaryOptions.projects" :key="item.id" :label="`${item.number} · ${item.name}`" :value="item.id" /></el-select></el-form-item>
            <el-form-item label="委托单位" required><el-select v-model="summaryForm.customerId" filterable remote reserve-keyword :remote-method="loadSummaryOptions" :loading="optionsLoading" :disabled="summaryReadOnly"><el-option v-for="item in summaryOptions.customers" :key="item.id" :label="item.code ? `${item.code} · ${item.name}` : item.name" :value="item.id" /></el-select></el-form-item>
            <el-form-item label="标段" required><el-input v-model="summaryForm.sectionName" maxlength="240" :disabled="summaryReadOnly" /></el-form-item>
            <template v-if="mode === 'STAGE'"><el-form-item label="开始时间" required><el-date-picker v-model="summaryForm.periodStart" value-format="YYYY-MM-DD" :disabled="summaryReadOnly" /></el-form-item><el-form-item label="结束时间" required><el-date-picker v-model="summaryForm.periodEnd" value-format="YYYY-MM-DD" :disabled="summaryReadOnly" /></el-form-item></template>
            <el-form-item v-else label="报告时间" required><el-date-picker v-model="summaryForm.reportOn" value-format="YYYY-MM-DD" :disabled="summaryReadOnly" /></el-form-item>
          </div>
          <el-form-item label="检测内容" required><el-checkbox-group v-model="summaryForm.contentCodes" class="content-checks" :disabled="summaryReadOnly"><el-checkbox v-for="item in contentOptions" :key="item[0]" :label="item[0]">{{ item[1] }}</el-checkbox></el-checkbox-group></el-form-item>
          <el-form-item label="检测结论"><el-input v-model="summaryForm.conclusion" type="textarea" :rows="5" maxlength="5000" show-word-limit :disabled="summaryReadOnly" /></el-form-item>
          <el-form-item label="附件上传（PDF、图片、DOCX、XLSX，最多 20 个）"><label v-if="!summaryReadOnly" class="file-picker"><input type="file" accept="application/pdf,image/png,image/jpeg,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.openxmlformats-officedocument.wordprocessingml.document" @change="uploadAttachment" /><el-icon><Upload /></el-icon>{{ uploading ? '正在安全校验…' : '选择受控附件' }}</label><div class="attachment-list"><div v-for="file in summaryForm.attachments" :key="file.id"><el-link :href="`/api/files/${file.id}`" type="primary">{{ file.name || file.originalName }}</el-link><el-button v-if="!summaryReadOnly" link type="danger" @click="summaryForm.attachments = summaryForm.attachments.filter(item => item.id !== file.id)">移除</el-button></div><span v-if="!summaryForm.attachments.length">暂无附件</span></div></el-form-item>
        </el-form>
        <section v-if="selectedSummary" class="signature-strip"><div><span>编制</span><strong>{{ selectedSummary.creatorName }}</strong></div><div><span>发布</span><strong>{{ selectedSummary.publisherName || '待发布' }}</strong></div><div><span>发布时间</span><strong>{{ formatTime(selectedSummary.publishedAt) }}</strong></div></section>
      </template>

      <template v-else>
        <el-alert v-if="selectedStrength?.blockers.length" class="readiness-alert" :title="'正式判定门禁未就绪'" :description="selectedStrength.blockers.join('；')" type="warning" :closable="false" show-icon />
        <el-tabs v-model="activeTab" class="strength-tabs">
          <el-tab-pane label="强度评定表" name="evaluation">
            <el-form label-position="top" class="document-form">
              <div class="document-form-grid">
                <el-form-item label="工程名称" required><el-select v-model="strengthForm.projectId" filterable remote reserve-keyword :remote-method="searchStrengthProjects" :loading="optionsLoading" :disabled="strengthReadOnly" @change="onStrengthProject"><el-option v-for="item in strengthOptions.projects" :key="item.id" :label="`${item.number} · ${item.name}`" :value="item.id" /></el-select></el-form-item>
                <el-form-item label="分项工程或构件名称" required><el-select v-model="strengthForm.structurePartId" filterable :disabled="strengthReadOnly"><el-option v-for="item in strengthOptions.structureParts" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item>
                <el-form-item label="检测项目" required><el-select v-model="strengthForm.parameterId" filterable remote reserve-keyword :remote-method="searchStrengthParameters" :loading="optionsLoading" :disabled="strengthReadOnly" @change="onParameter"><el-option v-for="item in strengthOptions.parameters" :key="item.id" :label="`${item.detectionProject} · ${item.name}`" :value="item.id" /></el-select></el-form-item>
                <el-form-item label="标准选择" required><el-select v-model="strengthForm.standardId" filterable :disabled="strengthReadOnly"><el-option v-for="item in selectedParameter?.standards ?? []" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item>
                <el-form-item label="设计要求" required><el-input v-model="strengthForm.designRequirement" list="design-requirements" maxlength="80" :disabled="strengthReadOnly" /><datalist id="design-requirements"><option v-for="item in strengthOptions.designRequirements" :key="item" :value="item" /></datalist></el-form-item>
                <el-form-item label="验收时段" required><el-date-picker v-model="strengthPeriod" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :disabled="strengthReadOnly" /></el-form-item>
                <el-form-item label="混凝土数量(m³)" required><el-input v-model="strengthForm.concreteQuantity" inputmode="decimal" :disabled="strengthReadOnly" /></el-form-item>
                <el-form-item label="标准差平均水平"><el-input v-model="strengthForm.standardDeviationAverageLevel" inputmode="decimal" :disabled="strengthReadOnly" /></el-form-item>
              </div>
              <div v-if="selectedStrength" class="metric-grid"><div><span>验收批试件</span><strong>{{ selectedStrength.specimenGroupCount ?? '—' }}<small>组</small></strong></div><div><span>平均强度</span><strong>{{ selectedStrength.averageStrength ?? '—' }}<small>MPa</small></strong></div><div><span>标准差</span><strong>{{ selectedStrength.standardDeviation ?? '—' }}<small>MPa</small></strong></div><div><span>强度最小值</span><strong>{{ selectedStrength.minimumStrength ?? '—' }}<small>MPa</small></strong></div></div>
              <el-form-item label="评定结论"><el-input :model-value="selectedStrength?.conclusion || '正式规则未就绪，系统不生成推测性合格结论'" type="textarea" :rows="3" disabled /></el-form-item>
              <el-form-item label="备注"><el-input v-model="strengthForm.remark" type="textarea" :rows="3" maxlength="2000" :disabled="strengthReadOnly" /></el-form-item>
            </el-form>
          </el-tab-pane>
          <el-tab-pane label="强度汇总表" name="summary">
            <section class="controlled-copy"><header><div><strong>{{ selectedStrength?.projectName || '尚未保存评定草稿' }}</strong><span>{{ selectedStrength?.structurePart || '保存后从已确认试验记录生成统计快照' }}</span></div><el-tag :type="selectedStrength?.ruleReadiness === 'READY' ? 'success' : 'warning'" effect="plain">{{ selectedStrength?.ruleReadiness === 'READY' ? '规则已就绪' : '正式规则待安装' }}</el-tag></header><dl><div><dt>统计值</dt><dd>{{ selectedStrength?.statisticsText || '尚未生成' }}</dd></div><div><dt>评定标准</dt><dd>{{ selectedStrength?.standardText || '尚未冻结' }}</dd></div><div><dt>强度评定计算</dt><dd>{{ selectedStrength?.calculationText || '尚未生成' }}</dd></div><div><dt>评定结论</dt><dd>{{ selectedStrength?.conclusion || '受规则门禁阻断' }}</dd></div></dl></section>
          </el-tab-pane>
          <el-tab-pane label="强度统计表" name="statistics">
            <section class="statistics-sheet"><header><span>{{ selectedStrength?.projectNumber }}</span><h2>混凝土强度统计表</h2><span>{{ selectedStrength?.designRequirement }}</span></header><el-table :data="selectedStrength?.results ?? []" border><el-table-column type="index" label="序号" width="70" /><el-table-column prop="reportNumber" label="报告编号" min-width="180"><template #default="scope">{{ scope.row.reportNumber || '未形成报告' }}</template></el-table-column><el-table-column prop="constructionOn" label="施工/委托日期" width="140" /><el-table-column prop="strengthValue" label="强度值(MPa)" width="140" align="right" /><template #empty><el-empty description="尚未生成试验结果快照" /></template></el-table><div class="statistics-footer"><div><span>平均值</span><strong>{{ selectedStrength?.averageStrength ?? '—' }} MPa</strong></div><div><span>最小值</span><strong>{{ selectedStrength?.minimumStrength ?? '—' }} MPa</strong></div><div><span>标准差</span><strong>{{ selectedStrength?.standardDeviation ?? '—' }} MPa</strong></div><div><span>平均标准差</span><strong>{{ selectedStrength?.standardDeviationAverageLevel ?? '—' }} MPa</strong></div></div><section class="signature-strip"><div><span>试验</span><strong>{{ selectedStrength?.testerName || '待统计' }}</strong></div><div><span>审核</span><strong>{{ selectedStrength?.reviewerName || '待审核' }}</strong></div><div><span>批准</span><strong>{{ selectedStrength?.approverName || '待批准' }}</strong></div></section></section>
          </el-tab-pane>
        </el-tabs>
      </template>

      <template #footer>
        <div class="drawer-footer"><span v-if="selectedStrength?.ruleReadiness !== 'READY' && mode === 'STRENGTH'"><el-icon><WarningFilled /></el-icon>正式规则未安装前，仅允许描述性统计，不允许审核发布结论</span><span v-else></span><div><el-button @click="drawer = false">关闭</el-button><el-button v-if="mode !== 'STRENGTH' && editMode && (!selectedSummary || selectedSummary.state === 'DRAFT') && canWrite" type="primary" :loading="saving" @click="saveSummary">保存草稿</el-button><el-button v-if="mode === 'STRENGTH' && editMode && (!selectedStrength || selectedStrength.state === 'DRAFT') && canWrite" type="primary" :loading="saving" @click="saveStrength">保存草稿</el-button><template v-if="selectedSummary"><el-button v-for="action in selectedSummary.allowedActions.filter(item => item !== 'UPDATE' && actionVisible(item))" :key="action" :type="action === 'PUBLISH' ? 'success' : action === 'RETIRE' || action === 'DISCARD' ? 'danger' : 'primary'" plain @click="transitionSummary(action)">{{ actionLabels[action] }}</el-button></template><template v-if="selectedStrength"><el-tooltip v-for="action in selectedStrength.allowedActions.filter(item => item !== 'UPDATE' && actionVisible(item))" :key="action" :disabled="!(action === 'SUBMIT_REVIEW' && selectedStrength.ruleReadiness !== 'READY')" content="正式规则未安装，不能提交审核" placement="top"><span><el-button :type="action === 'APPROVE' ? 'success' : ['RETIRE','DISCARD'].includes(action) ? 'danger' : 'primary'" plain :disabled="action === 'SUBMIT_REVIEW' && selectedStrength.ruleReadiness !== 'READY'" @click="transitionStrength(action)"><el-icon v-if="action === 'CALCULATE'"><DocumentChecked /></el-icon>{{ actionLabels[action] }}</el-button></span></el-tooltip></template></div></div>
      </template>
    </el-drawer>
  </section>
</template>

<style scoped>
.quality-document-filter{display:grid;grid-template-columns:repeat(4,minmax(155px,1fr)) auto;gap:10px;padding:13px 14px;background:#fff;border:1px solid var(--line);border-radius:10px 10px 0 0}.quality-document-filter :deep(.el-date-editor),.quality-document-filter .el-select{width:100%}.filter-actions{display:flex;gap:8px}.quality-document-table{min-height:0}.cursor-pagination{display:flex;align-items:center;gap:8px}.drawer-title{width:100%;display:flex;align-items:center;justify-content:space-between;gap:18px}.drawer-title strong,.drawer-title span{display:block}.drawer-title strong{color:#203653;font-size:17px}.drawer-title span{margin-top:4px;color:var(--muted);font-size: 13px}.document-form{padding:16px 2px}.document-form-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 14px}.document-form .el-select,.document-form :deep(.el-date-editor){width:100%}.content-checks{display:grid;grid-template-columns:repeat(4,minmax(120px,1fr));gap:4px 12px;width:100%;padding:12px 14px;border:1px solid var(--line);border-radius:8px;background:#fafcff}.attachment-list{width:100%;display:grid;gap:5px;margin-top:8px}.attachment-list>div{display:flex;align-items:center;justify-content:space-between;padding:6px 9px;border-radius:6px;background:#f4f7fb}.attachment-list>span{color:var(--muted);font-size: 13px}.signature-strip{display:grid;grid-template-columns:repeat(3,1fr);border:1px solid var(--line);border-radius:8px;background:#fff}.signature-strip>div{padding:12px 16px;border-right:1px solid var(--line)}.signature-strip>div:last-child{border-right:0}.signature-strip span,.signature-strip strong{display:block}.signature-strip span{color:var(--muted);font-size:13px}.signature-strip strong{margin-top:5px;color:#263b58}.readiness-alert{margin-bottom:12px}.strength-tabs{padding:0 2px}.metric-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:18px}.metric-grid>div{padding:13px 15px;border:1px solid #dbe7f6;border-radius:8px;background:#f7faff}.metric-grid span,.metric-grid strong{display:block}.metric-grid span{color:var(--muted);font-size:13px}.metric-grid strong{margin-top:6px;color:var(--brand-600);font-size:20px}.metric-grid small{margin-left:4px;color:var(--muted);font-size:13px;font-weight:400}.controlled-copy{border:1px solid var(--line);border-radius:9px;background:#fff}.controlled-copy>header{display:flex;align-items:center;justify-content:space-between;padding:16px 18px;border-bottom:1px solid var(--line)}.controlled-copy header strong,.controlled-copy header span{display:block}.controlled-copy header span{margin-top:4px;color:var(--muted);font-size: 13px}.controlled-copy dl{margin:0;padding:5px 18px 18px}.controlled-copy dl>div{display:grid;grid-template-columns:130px 1fr;gap:16px;padding:14px 0;border-bottom:1px solid #edf1f6}.controlled-copy dt{color:var(--muted)}.controlled-copy dd{margin:0;color:#2c405c;line-height:1.7}.statistics-sheet{padding:10px 4px}.statistics-sheet>header{display:grid;grid-template-columns:1fr auto 1fr;align-items:end;margin-bottom:14px}.statistics-sheet>header h2{margin:0;font-size:20px;text-align:center}.statistics-sheet>header span{color:var(--muted);font-size: 13px}.statistics-sheet>header span:last-child{text-align:right}.statistics-footer{display:grid;grid-template-columns:repeat(4,1fr);border:1px solid var(--line);border-top:0}.statistics-footer>div{padding:10px 12px;border-right:1px solid var(--line)}.statistics-footer>div:last-child{border-right:0}.statistics-footer span,.statistics-footer strong{display:block}.statistics-footer span{color:var(--muted);font-size:13px}.statistics-footer strong{margin-top:4px}.statistics-sheet .signature-strip{margin-top:14px}.drawer-footer{width:100%;display:flex;align-items:center;justify-content:space-between;gap:16px}.drawer-footer>span:first-child{max-width:470px;color:#986314;font-size: 13px}.drawer-footer>div{display:flex;align-items:center;gap:8px;flex-wrap:wrap;justify-content:flex-end}
@media(max-width:1440px){.quality-document-filter{grid-template-columns:repeat(3,minmax(150px,1fr)) auto}.content-checks{grid-template-columns:repeat(3,minmax(120px,1fr))}}@media(max-width:1180px){.quality-document-filter{grid-template-columns:repeat(2,minmax(150px,1fr))}.filter-actions{justify-content:flex-end}.document-form-grid{grid-template-columns:1fr}.content-checks{grid-template-columns:repeat(2,minmax(120px,1fr))}.drawer-footer{align-items:flex-end;flex-direction:column}}
@media print{.quality-document-page>.page-heading,.quality-document-page>.quality-document-filter,.quality-document-page>.quality-document-table,.quality-document-page>.table-footer,:deep(.el-drawer__header),:deep(.el-drawer__footer){display:none!important}:deep(.el-overlay){position:static!important;background:#fff!important}:deep(.el-drawer){position:static!important;width:100%!important;box-shadow:none!important}:deep(.el-drawer__body){padding:0!important}.strength-tabs :deep(.el-tabs__header){display:none}.strength-tabs :deep(.el-tab-pane){display:none}.strength-tabs :deep(.el-tab-pane[aria-hidden='false']){display:block}}
</style>
