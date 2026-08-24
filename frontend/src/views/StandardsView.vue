<script setup lang="ts">
import { Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, api, errorMessage } from '../api'
import { firstProject, flattenCatalog, type CatalogNode, type ProjectRevision } from '../detection'
import { useAuthStore } from '../stores/auth'

interface StandardSummary {
  id: string; seriesId: string; revisionNumber: number; code: string; name: string; ownershipType: string
  basisType: string; standardType: string; issuingInstitution?: string; effectiveDate: string; repealDate?: string
  standardStatus: string; state: string; version: number; sourceAttachmentCount: number
  sourceValidationWarning?: string; hasDocument: boolean; parameterCount: number; updatedAt: string; allowedActions: string[]
}
interface StandardView extends StandardSummary {
  predecessorId?: string; citedBasis?: string; documentFileId?: string; documentFileName?: string
  sourceSystem?: string; sourceKey?: string; sourceRecordId?: string; sourcePath?: string
  sourceCapturedAt?: string; contentSha256?: string; publishedAt?: string; createdAt: string
  parameterIds: string[]
}
interface CursorPage<T> { items: T[]; nextCursor?: string; hasMore: boolean; total: number }

const auth = useAuthStore()
const rows = ref<StandardSummary[]>([])
const total = ref(0)
const nextCursor = ref('')
const cursors = ref<string[]>([])
const currentCursor = ref('')
const loading = ref(false)
const saving = ref(false)
const drawer = ref(false)
const scopeDialog = ref(false)
const historyDrawer = ref(false)
const history = ref<StandardView[]>([])
const editing = ref<StandardView>()
const uploading = ref(false)
const importing = ref(false)
const importInput = ref<HTMLInputElement>()
const catalog = ref<CatalogNode[]>([])
const selectedProject = ref<CatalogNode>()
const projectRevision = ref<ProjectRevision>()
const expandedKeys = ref<string[]>([])
const treeProps = { value: 'id', label: 'name', children: 'children' }
const canWrite = computed(() => auth.user?.permissions.includes('MODULE_WRITE'))
const canExport = computed(() => auth.user?.permissions.includes('EXPORT_DATA'))
const filters = reactive({ query: '', basisType: '', standardType: '', standardStatus: '', state: '', effectiveRange: [] as string[] })
const form = reactive({
  code: '', name: '', ownershipType: 'SYSTEM', basisType: 'DETECTION', standardType: 'NATIONAL',
  issuingInstitution: '', citedBasis: '', effectiveDate: '', repealDate: '', standardStatus: 'CURRENT',
  documentFileId: '', documentFileName: '', parameterIds: [] as string[], expectedVersion: 0,
})
const selectedInProject = computed({
  get: () => projectRevision.value?.parameters.filter((item) => form.parameterIds.includes(item.id)).map((item) => item.id) ?? [],
  set: (ids: string[]) => {
    const currentIds = new Set(projectRevision.value?.parameters.map((item) => item.id) ?? [])
    form.parameterIds = [...form.parameterIds.filter((id) => !currentIds.has(id)), ...ids]
  },
})

const labels: Record<string, string> = {
  SYSTEM: '系统', ORGANIZATION: '机构', DETECTION: '检测依据', JUDGEMENT: '判定依据', BOTH: '检测/判定依据',
  NATIONAL: '国标', INDUSTRY: '行标', LOCAL: '地标', ASSOCIATION: '团标', ENTERPRISE: '企标', OTHER: '其他',
  CURRENT: '现行', UPCOMING: '即将实施', REPEALED: '作废', DRAFT: '草稿', PUBLISHED: '已发布', RETIRED: '已停用',
}

function errorText(error: unknown) { return errorMessage(error) }
function stateType(state: string) { return state === 'PUBLISHED' || state === 'CURRENT' ? 'success' : state === 'DRAFT' || state === 'UPCOMING' ? 'warning' : 'info' }
function queryString(cursor = '') {
  const params = new URLSearchParams({ query: filters.query, basisType: filters.basisType, standardType: filters.standardType,
    standardStatus: filters.standardStatus, state: filters.state, limit: '50' })
  if (filters.effectiveRange?.[0]) params.set('effectiveFrom', filters.effectiveRange[0])
  if (filters.effectiveRange?.[1]) params.set('effectiveTo', filters.effectiveRange[1])
  if (cursor) params.set('cursor', cursor)
  return params.toString()
}

function exportQueryString() {
  const params = new URLSearchParams({ query: filters.query, basisType: filters.basisType, standardType: filters.standardType,
    standardStatus: filters.standardStatus, state: filters.state })
  if (filters.effectiveRange?.[0]) params.set('effectiveFrom', filters.effectiveRange[0])
  if (filters.effectiveRange?.[1]) params.set('effectiveTo', filters.effectiveRange[1])
  return params.toString()
}

async function download(path: string, fallbackName: string) {
  const response = await fetch(path, { credentials: 'same-origin' })
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new ApiError(response.status, body.code ?? 'DOWNLOAD_FAILED', body.detail ?? '下载失败', body)
  }
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url; anchor.download = fallbackName; document.body.appendChild(anchor); anchor.click(); anchor.remove()
  URL.revokeObjectURL(url)
}

async function downloadTemplate() {
  try { await download('/api/standards/import-template', '标准库-受控导入模板-V1.xlsx') }
  catch (error) { ElMessage.error(errorText(error)) }
}

async function exportStandards() {
  try { await download(`/api/standards/export?${exportQueryString()}`, '标准库-受控导出.xlsx'); ElMessage.success('标准库导出已生成并记录审计') }
  catch (error) { ElMessage.error(errorText(error)) }
}

async function importStandards(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  importing.value = true
  try {
    const data = new FormData(); data.append('file', file)
    const result = await api<{ importedCount: number; createdCount: number; revisedCount: number; updatedDraftCount: number }>('/api/standards/imports', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: data,
    })
    ElMessage.success(`整文件导入成功：${result.importedCount} 条（新增 ${result.createdCount}、修订 ${result.revisedCount}、更新草稿 ${result.updatedDraftCount}）`)
    await load('')
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { importing.value = false; input.value = '' }
}

async function load(cursor = currentCursor.value) {
  loading.value = true
  try {
    const page = await api<CursorPage<StandardSummary>>(`/api/standards?${queryString(cursor)}`)
    rows.value = page.items; total.value = page.total; nextCursor.value = page.nextCursor ?? ''
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { loading.value = false }
}
function search() { cursors.value = []; currentCursor.value = ''; load('') }
function reset() { Object.assign(filters, { query: '', basisType: '', standardType: '', standardStatus: '', state: '', effectiveRange: [] }); search() }
function nextPage() { if (!nextCursor.value) return; cursors.value.push(currentCursor.value); currentCursor.value = nextCursor.value; load() }
function previousPage() { currentCursor.value = cursors.value.pop() ?? ''; load() }

function resetForm() {
  Object.assign(form, { code: '', name: '', ownershipType: 'SYSTEM', basisType: 'DETECTION', standardType: 'NATIONAL',
    issuingInstitution: '', citedBasis: '', effectiveDate: '', repealDate: '', standardStatus: 'CURRENT',
    documentFileId: '', documentFileName: '', parameterIds: [], expectedVersion: 0 })
}
function openCreate() { editing.value = undefined; resetForm(); drawer.value = true }
async function openEdit(row: StandardSummary) {
  try {
    const value = await api<StandardView>(`/api/standards/${row.id}`)
    editing.value = value
    Object.assign(form, { code: value.code, name: value.name, ownershipType: value.ownershipType, basisType: value.basisType,
      standardType: value.standardType, issuingInstitution: value.issuingInstitution ?? '', citedBasis: value.citedBasis ?? '',
      effectiveDate: value.effectiveDate, repealDate: value.repealDate ?? '', standardStatus: value.standardStatus,
      documentFileId: value.documentFileId ?? '', documentFileName: value.documentFileName ?? '',
      parameterIds: [...value.parameterIds], expectedVersion: value.version })
    drawer.value = true
  } catch (error) { ElMessage.error(errorText(error)) }
}
async function save() {
  if (!form.code.trim() || !form.name.trim() || !form.effectiveDate) return ElMessage.warning('请完整填写必填字段')
  saving.value = true
  try {
    const path = editing.value ? `/api/standards/${editing.value.id}` : '/api/standards'
    await api(path, { method: editing.value ? 'PUT' : 'POST', headers: editing.value ? undefined : { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ...form, documentFileId: form.documentFileId || null, repealDate: form.repealDate || null }) })
    drawer.value = false; ElMessage.success(editing.value ? '标准草稿已保存' : '标准草稿已创建'); await load()
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { saving.value = false }
}
async function revise(row: StandardSummary) {
  try {
    const created = await api<StandardView>(`/api/standards/${row.id}/revisions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } })
    ElMessage.success(`已创建 R${created.revisionNumber} 修订草稿`); await load(); await openEdit(created)
  } catch (error) { ElMessage.error(errorText(error)) }
}
async function transition(row: StandardSummary, action: string) {
  let comment = `${action}标准版本`
  if (action !== 'PUBLISH') {
    try { comment = await ElMessageBox.prompt('请填写原因，操作将写入审计日志。', action === 'RETIRE' ? '停用标准' : '废弃草稿', { inputPattern: /\S+/, inputErrorMessage: '原因不能为空' }).then((result) => result.value) } catch { return }
  } else {
    try { await ElMessageBox.confirm('发布后该版本不可原地修改，并会替代当前发布版。', '发布标准') } catch { return }
  }
  try {
    await api(`/api/standards/${row.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: 'CONTROLLED_STANDARD', comment }) })
    ElMessage.success('状态已更新'); await load()
  } catch (error) { ElMessage.error(errorText(error)) }
}
async function showHistory(row: StandardSummary) {
  try { history.value = await api<StandardView[]>(`/api/standards/${row.id}/revisions`); historyDrawer.value = true }
  catch (error) { ElMessage.error(errorText(error)) }
}
async function handleStandardCommand(row: StandardSummary, command: string) {
  if (command === 'EDIT') return openEdit(row)
  if (command === 'REVISE') return revise(row)
  if (command === 'HISTORY') return showHistory(row)
  if (['PUBLISH', 'RETIRE', 'DISCARD'].includes(command)) return transition(row, command)
}
async function uploadDocument(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]; if (!file) return
  uploading.value = true
  try {
    const data = new FormData(); data.append('file', file); data.append('purpose', 'DETECTION_STANDARD_DOCUMENT')
    const stored = await api<{ id: string; originalName: string }>('/api/files', { method: 'POST', body: data })
    form.documentFileId = stored.id; form.documentFileName = stored.originalName; ElMessage.success('标准 PDF 已安全入库')
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { uploading.value = false; (event.target as HTMLInputElement).value = '' }
}

async function openScope() {
  scopeDialog.value = true
  if (catalog.value.length) return
  try {
    catalog.value = await api<CatalogNode[]>('/api/detection-catalog/tree?includeHistory=false')
    expandedKeys.value = flattenCatalog(catalog.value).filter((item) => item.type !== 'PROJECT').map((item) => item.id)
    const project = firstProject(catalog.value); if (project) await selectProject(project)
  } catch (error) { ElMessage.error(errorText(error)) }
}
async function selectProject(node: CatalogNode) {
  selectedProject.value = node
  if (node.type === 'PROJECT' && node.projectRevisionId) projectRevision.value = await api<ProjectRevision>(`/api/detection-project-revisions/${node.projectRevisionId}`)
}

onMounted(() => load(''))
</script>

<template>
  <section class="page standards-page">
    <header class="page-heading"><div><h1>标准库</h1></div><div class="standards-actions"><el-button @click="downloadTemplate">导入模板</el-button><input ref="importInput" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" hidden @change="importStandards" /><el-button v-if="canWrite" :loading="importing" :icon="Upload" @click="importInput?.click()">导入标准</el-button><el-button v-if="canExport" @click="exportStandards">导出当前结果</el-button><el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">新增标准</el-button></div></header>
    <section class="standards-filter">
      <el-input v-model="filters.query" :prefix-icon="Search" clearable placeholder="标准编号、名称或发布机构" @keyup.enter="search" />
      <el-date-picker v-model="filters.effectiveRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="实施日起" end-placeholder="实施日止" />
      <el-select v-model="filters.basisType" clearable placeholder="依据类型"><el-option v-for="value in ['DETECTION','JUDGEMENT','BOTH']" :key="value" :label="labels[value]" :value="value" /></el-select>
      <el-select v-model="filters.standardType" clearable placeholder="标准类型"><el-option v-for="value in ['NATIONAL','INDUSTRY','LOCAL','ASSOCIATION','ENTERPRISE','OTHER']" :key="value" :label="labels[value]" :value="value" /></el-select>
      <el-select v-model="filters.standardStatus" clearable placeholder="标准状态"><el-option v-for="value in ['CURRENT','UPCOMING','REPEALED']" :key="value" :label="labels[value]" :value="value" /></el-select>
      <el-select v-model="filters.state" clearable placeholder="版本状态"><el-option v-for="value in ['DRAFT','PUBLISHED','RETIRED']" :key="value" :label="labels[value]" :value="value" /></el-select>
      <div class="foundation-filter-actions"><el-button type="primary" @click="search">查询</el-button><el-button :icon="Refresh" @click="reset">重置</el-button></div>
    </section>
    <el-table v-loading="loading" :data="rows" border stripe class="module-table standards-table">
      <el-table-column label="标准" min-width="220"><template #default="scope"><div class="standard-identity"><strong>{{ scope.row.code }}</strong><span :title="scope.row.name">{{ scope.row.name }}</span><small v-if="scope.row.issuingInstitution" :title="scope.row.issuingInstitution">{{ scope.row.issuingInstitution }}</small></div></template></el-table-column>
      <el-table-column label="分类" width="126"><template #default="scope"><div class="cell-stack"><strong>{{ labels[scope.row.basisType] }}</strong><span>{{ labels[scope.row.standardType] }} · {{ labels[scope.row.ownershipType] }}</span></div></template></el-table-column>
      <el-table-column label="有效期" width="138"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.effectiveDate }}</strong><span>{{ scope.row.repealDate ? `至 ${scope.row.repealDate}` : '长期有效' }}</span></div></template></el-table-column>
      <el-table-column label="状态 / 版本" width="142" align="center"><template #default="scope"><div class="status-stack"><el-tag :type="stateType(scope.row.standardStatus)">{{ labels[scope.row.standardStatus] }}</el-tag><el-tag :type="stateType(scope.row.state)" effect="plain">R{{ scope.row.revisionNumber }} · {{ labels[scope.row.state] }}</el-tag></div></template></el-table-column>
      <el-table-column label="关联" width="98"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.parameterCount }} 项参数</strong><span v-if="scope.row.hasDocument">PDF 已入库</span><el-tooltip v-else-if="scope.row.sourceAttachmentCount" content="参考站显示附件，但文件内容未对当前角色暴露"><span class="source-only">源站附件 {{ scope.row.sourceAttachmentCount }}</span></el-tooltip><span v-else>无附件</span></div></template></el-table-column>
      <el-table-column label="操作" fixed="right" width="104" align="center"><template #default="scope"><el-dropdown trigger="click" @command="(command: string) => handleStandardCommand(scope.row, command)"><el-button link type="primary" :aria-label="`操作 ${scope.row.code}`">操作</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-if="scope.row.state === 'DRAFT' && canWrite" command="EDIT">修改草稿</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('REVISE') && canWrite" command="REVISE">创建修订</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('PUBLISH') && canWrite" command="PUBLISH">发布版本</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('RETIRE') && canWrite" command="RETIRE" divided>停用版本</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('DISCARD') && canWrite" command="DISCARD" divided>废弃草稿</el-dropdown-item><el-dropdown-item command="HISTORY" divided>查看版本链</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></el-table-column>
    </el-table>
    <footer class="table-footer"><span>共 {{ total }} 条 · 第 {{ cursors.length + 1 }} 页 · 每页 50 条</span><div><el-button :disabled="!cursors.length" @click="previousPage">上一页</el-button><el-button :disabled="!nextCursor" @click="nextPage">下一页</el-button></div></footer>

    <el-drawer v-model="drawer" :title="editing ? `修改标准草稿 R${editing.revisionNumber}` : '新增标准'" size="760px" class="foundation-drawer">
      <el-alert v-if="editing?.sourceValidationWarning" type="warning" show-icon :closable="false" :title="editing.sourceValidationWarning" description="创建修订后必须校正日期才能保存与发布。" />
      <el-form label-position="top">
        <div class="form-grid"><el-form-item label="标准编号" required><el-input v-model="form.code" maxlength="240" /></el-form-item><el-form-item label="标准名称" required><el-input v-model="form.name" maxlength="500" /></el-form-item></div>
        <div class="form-grid"><el-form-item label="标准归属" required><el-radio-group v-model="form.ownershipType"><el-radio value="SYSTEM">系统</el-radio><el-radio value="ORGANIZATION">机构</el-radio></el-radio-group></el-form-item><el-form-item label="标准状态" required><el-select v-model="form.standardStatus"><el-option v-for="value in ['CURRENT','UPCOMING','REPEALED']" :key="value" :label="labels[value]" :value="value" /></el-select></el-form-item></div>
        <div class="form-grid"><el-form-item label="依据类型" required><el-select v-model="form.basisType"><el-option v-for="value in ['DETECTION','JUDGEMENT','BOTH']" :key="value" :label="labels[value]" :value="value" /></el-select></el-form-item><el-form-item label="标准类型" required><el-select v-model="form.standardType"><el-option v-for="value in ['NATIONAL','INDUSTRY','LOCAL','ASSOCIATION','ENTERPRISE','OTHER']" :key="value" :label="labels[value]" :value="value" /></el-select></el-form-item></div>
        <div class="form-grid"><el-form-item label="实施日期" required><el-date-picker v-model="form.effectiveDate" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="废止日期"><el-date-picker v-model="form.repealDate" value-format="YYYY-MM-DD" clearable /></el-form-item></div>
        <el-form-item label="发布机构"><el-input v-model="form.issuingInstitution" maxlength="240" /></el-form-item><el-form-item label="引用依据"><el-input v-model="form.citedBasis" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
        <el-form-item label="标准 PDF 文件"><label class="file-picker"><input type="file" accept="application/pdf" @change="uploadDocument" /><el-icon><Upload /></el-icon>{{ uploading ? '上传中…' : form.documentFileName || '选择受控 PDF' }}</label></el-form-item>
        <el-form-item label="适用检测参数"><div class="scope-summary"><span>已选 {{ form.parameterIds.length }} 条参数</span><el-button @click="openScope">选择适用范围</el-button></div></el-form-item>
      </el-form>
      <template #footer><el-button @click="drawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
    </el-drawer>

    <el-dialog v-model="scopeDialog" title="选择适用检测参数" width="980px" class="standard-scope-dialog">
      <div class="standard-scope-grid"><div class="catalog-tree-scroll"><el-tree-v2 :data="catalog" :props="treeProps" :height="520" :default-expanded-keys="expandedKeys" highlight-current @node-click="selectProject"><template #default="{ data }"><div class="catalog-tree-node"><span class="node-kind">{{ data.type === 'DOMAIN' ? '域' : data.type === 'CATEGORY' ? '类' : '项' }}</span><span class="node-name">{{ data.name }}</span></div></template></el-tree-v2></div><div><header class="scope-project-heading"><strong>{{ selectedProject?.name ?? '请选择检测项目' }}</strong><span>{{ projectRevision?.parameters.length ?? 0 }} 条参数</span></header><el-checkbox-group v-model="selectedInProject" class="parameter-scope-list"><el-checkbox v-for="item in projectRevision?.parameters" :key="item.id" :value="item.id"><strong>{{ item.name }}</strong><span>{{ item.code }} {{ item.unit ? `· ${item.unit}` : '' }}</span></el-checkbox></el-checkbox-group></div></div>
      <template #footer><span class="scope-dialog-count">累计已选 {{ form.parameterIds.length }} 条</span><el-button type="primary" @click="scopeDialog=false">完成选择</el-button></template>
    </el-dialog>

    <el-drawer v-model="historyDrawer" title="标准版本链" size="680px"><el-timeline><el-timeline-item v-for="item in history" :key="item.id" :timestamp="item.updatedAt" :type="item.state === 'PUBLISHED' ? 'success' : item.state === 'DRAFT' ? 'warning' : 'info'"><strong>R{{ item.revisionNumber }} · {{ labels[item.state] }}</strong><p>{{ item.code }} · {{ item.name }}</p><p v-if="item.sourceSystem">来源：{{ item.sourceSystem }} {{ item.sourceRecordId ? `#${item.sourceRecordId}` : '· 源站未暴露记录 ID' }}</p></el-timeline-item></el-timeline></el-drawer>
  </section>
</template>
