<script setup lang="ts">
import { DocumentAdd, Edit, Link, Plus, Refresh, Search, Upload, View as ViewIcon } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref, toRaw } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { useAuthStore } from '../stores/auth'
import { statusLabel } from '../presentation'

interface Commission {
  id: string; number: string; precommissionNumber?: string; projectName: string; customerName: string
  contactName: string; contactPhone?: string; commissionedOn: string; dueOn: string; state: string; version: number
}
interface CursorPage<T> { items: T[]; nextCursor?: string; hasMore: boolean; total: number }
interface ChangeRequest {
  id: string; commissionId: string; commissionNumber: string; commissionedOn: string; detectionProjects: string
  requestType: string; processingMethod: string; reason: string; requestedContactName?: string
  requestedContactPhone?: string; requestedDueOn?: string; requestedSummary?: string; attachmentFileId?: string
  attachmentName?: string; state: string; requestedById: string; requestedBy: string; requestedAt: string
  reviewedBy?: string; reviewedAt?: string; reviewComment?: string; commissionVersionSnapshot: number
  appliedCommissionVersion?: number; resultCommissionId?: string; version: number; updatedAt: string
  allowedActions: string[]; blockers: string[]
}
interface CursorPage<T> { items: T[]; nextCursor?: string; hasMore: boolean; total: number }
interface StoredFile { id: string; originalName: string }

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const acting = ref('')
const uploading = ref(false)
const rows = ref<ChangeRequest[]>([])
const commissions = ref<Commission[]>([])
const drawer = ref(false)
const detailDrawer = ref(false)
const editing = ref<ChangeRequest>()
const detail = ref<ChangeRequest>()
const nextCursor = ref('')
const cursor = ref('')
const cursorHistory = ref<string[]>([])
const total = ref(0)
const filters = reactive({ dateRange: [] as string[], detectionProject: '', commissionNumber: '', state: '', applicant: '', reviewer: '', processingMethod: '' })
const form = reactive({ commissionId: '', requestType: 'CONTACT', processingMethod: 'UPDATE_CURRENT', reason: '', requestedContactName: '', requestedContactPhone: '', requestedDueOn: '', requestedSummary: '', attachmentFileId: '', attachmentName: '' })

const canWrite = computed(() => auth.user?.permissions.includes('MODULE_WRITE'))
const canTransition = computed(() => auth.user?.permissions.includes('BUSINESS_TRANSITION'))
const selectedCommission = computed(() => commissions.value.find(item => item.id === form.commissionId))
const selectableCommissions = computed(() => commissions.value.filter(item => !['DRAFT', 'REJECTED', 'CANCELLED'].includes(item.state)))

const stateLabels: Record<string, string> = { DRAFT: '草稿', SUBMITTED: '待审核', APPROVED: '已通过', REJECTED: '已驳回', WITHDRAWN: '已撤回' }
const typeOptions = [
  ['CONTACT', '联系人信息'], ['DUE_DATE', '完成时限'], ['REPORT_REQUIREMENT', '报告要求'],
  ['SAMPLE', '样品信息'], ['DETECTION_SELECTION', '检测项目/参数'], ['OTHER', '其他'],
]
const methodOptions = [
  ['UPDATE_CURRENT', '更新当前委托'], ['CREATE_REVISION', '创建修订版'],
  ['VOID_AND_RECREATE', '作废并重建'], ['MANUAL_RECORD', '仅登记处理记录'],
]
const typeLabel = (value: string) => typeOptions.find(item => item[0] === value)?.[1] ?? value
const methodLabel = (value: string) => methodOptions.find(item => item[0] === value)?.[1] ?? value
const stateType = (state: string) => state === 'APPROVED' ? 'success' : state === 'REJECTED' ? 'danger' : state === 'WITHDRAWN' ? 'info' : 'warning'
const showError = (error: unknown) => ElMessage.error(errorMessage(error))
const formatTime = (value?: string) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'

function queryString() {
  const params = new URLSearchParams({ limit: '50' })
  if (filters.dateRange[0]) params.set('from', filters.dateRange[0])
  if (filters.dateRange[1]) params.set('to', filters.dateRange[1])
  for (const key of ['detectionProject', 'commissionNumber', 'state', 'applicant', 'reviewer', 'processingMethod'] as const) if (filters[key]) params.set(key, filters[key])
  if (cursor.value) params.set('cursor', cursor.value)
  return params.toString()
}

async function load() {
  loading.value = true
  try {
    const page = await api<CursorPage<ChangeRequest>>(`/api/commission-change-requests?${queryString()}`)
    rows.value = page.items; nextCursor.value = page.nextCursor ?? ''; total.value = page.total
  } catch (error) { showError(error) }
  finally { loading.value = false }
}
async function loadCommissions() {
  commissions.value = (await api<CursorPage<Commission>>('/api/commissions?limit=100')).items
}
function search() { cursor.value = ''; cursorHistory.value = []; load() }
function reset() { Object.assign(filters, { dateRange: [], detectionProject: '', commissionNumber: '', state: '', applicant: '', reviewer: '', processingMethod: '' }); search() }
function nextPage() { if (nextCursor.value) { cursorHistory.value.push(cursor.value); cursor.value = nextCursor.value; load() } }
function previousPage() { if (cursorHistory.value.length) { cursor.value = cursorHistory.value.pop() ?? ''; load() } }

function blankForm(commissionId = '') {
  Object.assign(form, { commissionId, requestType: 'CONTACT', processingMethod: 'UPDATE_CURRENT', reason: '', requestedContactName: '', requestedContactPhone: '', requestedDueOn: '', requestedSummary: '', attachmentFileId: '', attachmentName: '' })
  commissionChanged()
}
async function openCreate(commissionId = '') {
  editing.value = undefined
  if (!commissions.value.length) await loadCommissions()
  blankForm(commissionId)
  drawer.value = true
}
function commissionChanged() {
  const current = commissions.value.find(item => item.id === form.commissionId)
  if (!current) return
  form.requestedContactName = current.contactName
  form.requestedContactPhone = current.contactPhone ?? ''
  form.requestedDueOn = current.dueOn
}
function openEdit(row: ChangeRequest) {
  if (row.state !== 'DRAFT' || row.requestedById !== auth.user?.id) return ElMessage.warning('仅申请人可编辑自己的草稿')
  editing.value = row
  Object.assign(form, {
    commissionId: row.commissionId, requestType: row.requestType, processingMethod: row.processingMethod,
    reason: row.reason, requestedContactName: row.requestedContactName ?? '', requestedContactPhone: row.requestedContactPhone ?? '',
    requestedDueOn: row.requestedDueOn ?? '', requestedSummary: row.requestedSummary ?? '',
    attachmentFileId: row.attachmentFileId ?? '', attachmentName: row.attachmentName ?? '',
  })
  drawer.value = true
}
async function openDetail(row: ChangeRequest) {
  try { detail.value = await api<ChangeRequest>(`/api/commission-change-requests/${row.id}`); detailDrawer.value = true }
  catch (error) { showError(error) }
}

function validateForm() {
  if (!form.commissionId || !form.reason.trim()) return '请选择委托并填写修改原因'
  if (form.requestType === 'CONTACT' && !form.requestedContactName.trim()) return '请填写新联系人'
  if (form.requestType === 'DUE_DATE' && !form.requestedDueOn) return '请填写新完成时限'
  if (['SAMPLE', 'DETECTION_SELECTION'].includes(form.requestType) && ['UPDATE_CURRENT', 'CREATE_REVISION'].includes(form.processingMethod)) return '样品或检测选项不能用文本直接覆盖冻结快照，请选择“作废并重建”'
  return ''
}
async function save() {
  const invalid = validateForm(); if (invalid) return ElMessage.warning(invalid)
  saving.value = true
  try {
    const body = { ...structuredClone(toRaw(form)), attachmentFileId: form.attachmentFileId || null, expectedVersion: editing.value?.version ?? null }
    delete (body as Record<string, unknown>).attachmentName
    const path = editing.value ? `/api/commission-change-requests/${editing.value.id}` : '/api/commission-change-requests'
    await api(path, { method: editing.value ? 'PUT' : 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) })
    drawer.value = false
    ElMessage.success(editing.value ? '修改申请已更新' : '修改申请已建立')
    await load()
  } catch (error) { showError(error) }
  finally { saving.value = false }
}

async function uploadAttachment(event: Event) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = ''
  if (!file) return
  uploading.value = true
  try {
    const body = new FormData(); body.append('file', file); body.append('purpose', 'COMMISSION_CHANGE_EVIDENCE')
    const stored = await api<StoredFile>('/api/files', { method: 'POST', body })
    form.attachmentFileId = stored.id; form.attachmentName = stored.originalName
    ElMessage.success('附件已通过文件头与安全解析校验')
  } catch (error) { showError(error) }
  finally { uploading.value = false }
}

function canRun(row: ChangeRequest, action: string) {
  if (!canTransition.value || !row.allowedActions.includes(action)) return false
  if (['SUBMIT', 'WITHDRAW'].includes(action)) return row.requestedById === auth.user?.id
  if (['APPROVE', 'REJECT'].includes(action)) return row.requestedById !== auth.user?.id
  return true
}
async function transition(row: ChangeRequest, action: 'SUBMIT' | 'WITHDRAW' | 'APPROVE' | 'REJECT') {
  const labels: Record<string, string> = { SUBMIT: '提交', WITHDRAW: '撤回', APPROVE: '通过', REJECT: '驳回' }
  let comment = action === 'SUBMIT' ? '修改资料完整，提交审核' : ''
  try {
    if (action === 'APPROVE') {
      if (row.blockers.length) return ElMessage.error(row.blockers.join('；'))
      await ElMessageBox.confirm(`审批后将按“${methodLabel(row.processingMethod)}”执行，不会原地覆盖已发布快照。`, '审批确认', { type: 'warning' })
      comment = '已核对申请内容与委托版本，同意执行'
    } else if (action !== 'SUBMIT') comment = (await ElMessageBox.prompt(`请填写${labels[action]}原因，将写入审计日志`, `${labels[action]}修改申请`, { inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空' })).value
    acting.value = row.id
    await api(`/api/commission-change-requests/${row.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: `COMMISSION_CHANGE_${action}`, comment }),
    })
    ElMessage.success(`申请已${labels[action]}`)
    detailDrawer.value = false
    await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
  finally { acting.value = '' }
}

onMounted(async () => {
  try {
    await Promise.all([load(), loadCommissions()])
    const commissionId = typeof route.query.commissionId === 'string' ? route.query.commissionId : ''
    if (commissionId) await openCreate(commissionId)
  } catch (error) { showError(error) }
})
</script>

<template>
  <section class="page change-request-page">
    <header class="page-heading">
      <div><h1>委托修改申请</h1><p>提交受理后委托的修改事项，办理审核并查询执行结果。</p></div>
      <div class="heading-actions"><el-button :icon="Refresh" @click="load">刷新</el-button><el-button type="primary" :icon="Plus" :disabled="!canWrite" @click="openCreate()">发起修改</el-button></div>
    </header>

    <section class="change-filter">
      <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="申请起始" end-placeholder="申请截止" />
      <el-input v-model="filters.detectionProject" clearable placeholder="检测项目" />
      <el-input v-model="filters.commissionNumber" clearable placeholder="委托编号" />
      <el-select v-model="filters.state" clearable placeholder="全部状态"><el-option v-for="(label, value) in stateLabels" :key="value" :label="label" :value="value" /></el-select>
      <el-input v-model="filters.applicant" clearable placeholder="申请人" />
      <el-input v-model="filters.reviewer" clearable placeholder="审核人" />
      <el-select v-model="filters.processingMethod" clearable placeholder="处理方式"><el-option v-for="item in methodOptions" :key="item[0]" :label="item[1]" :value="item[0]" /></el-select>
      <div class="filter-actions"><el-button type="primary" :icon="Search" @click="search">查询</el-button><el-button @click="reset">重置</el-button></div>
    </section>

    <el-table v-loading="loading" :data="rows" stripe border row-key="id" class="module-table viewport-table" scrollbar-always-on @row-dblclick="openDetail">
      <el-table-column prop="commissionNumber" label="委托编号" width="164" fixed="left"><template #default="scope"><strong>{{ scope.row.commissionNumber }}</strong></template></el-table-column>
      <el-table-column prop="commissionedOn" label="委托日期" width="112" />
      <el-table-column prop="detectionProjects" label="检测项目" min-width="220" show-overflow-tooltip />
      <el-table-column label="修改类型" width="132"><template #default="scope">{{ typeLabel(scope.row.requestType) }}</template></el-table-column>
      <el-table-column label="处理方式" width="142"><template #default="scope">{{ methodLabel(scope.row.processingMethod) }}</template></el-table-column>
      <el-table-column prop="reason" label="修改原因" min-width="230" show-overflow-tooltip />
      <el-table-column prop="requestedBy" label="申请人" width="100" />
      <el-table-column label="申请时间" width="154"><template #default="scope">{{ formatTime(scope.row.requestedAt) }}</template></el-table-column>
      <el-table-column prop="reviewedBy" label="审核人" width="100"><template #default="scope">{{ scope.row.reviewedBy || '—' }}</template></el-table-column>
      <el-table-column label="审核时间" width="154"><template #default="scope">{{ formatTime(scope.row.reviewedAt) }}</template></el-table-column>
      <el-table-column label="申请附件" width="132"><template #default="scope"><el-link v-if="scope.row.attachmentFileId" :href="`/api/files/${scope.row.attachmentFileId}`" type="primary">{{ scope.row.attachmentName || '下载附件' }}</el-link><span v-else>—</span></template></el-table-column>
      <el-table-column label="状态" width="100" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)" effect="plain">{{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column>
      <el-table-column label="操作" fixed="right" width="254" align="center"><template #default="scope">
        <el-button link :icon="ViewIcon" @click="openDetail(scope.row)">详情</el-button>
        <el-button v-if="scope.row.state === 'DRAFT'" link :icon="Edit" :disabled="!canWrite || scope.row.requestedById !== auth.user?.id" @click="openEdit(scope.row)">修改</el-button>
        <el-button v-if="scope.row.allowedActions.includes('SUBMIT')" link type="primary" :disabled="!canRun(scope.row,'SUBMIT')" @click="transition(scope.row,'SUBMIT')">提交</el-button>
        <el-button v-if="scope.row.allowedActions.includes('APPROVE')" link type="success" :disabled="!canRun(scope.row,'APPROVE') || scope.row.blockers.length" @click="transition(scope.row,'APPROVE')">通过</el-button>
        <el-button v-if="scope.row.allowedActions.includes('REJECT')" link type="danger" :disabled="!canRun(scope.row,'REJECT')" @click="transition(scope.row,'REJECT')">驳回</el-button>
        <el-dropdown v-if="scope.row.allowedActions.includes('WITHDRAW')" @command="transition(scope.row,'WITHDRAW')"><el-button link :disabled="!canRun(scope.row,'WITHDRAW')">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="WITHDRAW">撤回申请</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
      </template></el-table-column>
      <template #empty><el-empty description="没有符合条件的委托修改申请" /></template>
    </el-table>
    <footer class="table-footer"><span>共 {{ total }} 条 · 第 {{ cursorHistory.length + 1 }} 页 · 每页最多 50 条</span><div><el-button :disabled="!cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="!nextCursor" @click="nextPage">下一页</el-button></div></footer>

    <el-drawer v-model="drawer" :title="editing ? `编辑修改申请 · ${editing.commissionNumber}` : '发起委托修改申请'" size="680px" class="change-drawer" :close-on-click-modal="false">
      <el-alert title="申请人不能审批自己的申请；委托版本在审批前如有变化，系统将阻断执行。" type="info" :closable="false" show-icon />
      <el-form label-position="top" class="change-form">
        <el-form-item label="所属委托" required><el-select v-model="form.commissionId" filterable :disabled="Boolean(editing)" placeholder="选择已提交或已受理委托" @change="commissionChanged"><el-option v-for="item in selectableCommissions" :key="item.id" :label="`${item.number} · ${item.projectName} · ${item.customerName}`" :value="item.id"><span>{{ item.number }} · {{ item.projectName }}</span><small>{{ item.customerName }}</small></el-option></el-select></el-form-item>
        <section v-if="selectedCommission" class="commission-snapshot"><div><span>当前状态</span><strong>{{ statusLabel(selectedCommission.state) }}</strong></div><div><span>联系人</span><strong>{{ selectedCommission.contactName }} {{ selectedCommission.contactPhone }}</strong></div><div><span>完成时限</span><strong>{{ selectedCommission.dueOn }}</strong></div><div><span>委托版本</span><strong>V{{ selectedCommission.version }}</strong></div></section>
        <div class="form-grid"><el-form-item label="修改类型" required><el-select v-model="form.requestType"><el-option v-for="item in typeOptions" :key="item[0]" :label="item[1]" :value="item[0]" /></el-select></el-form-item><el-form-item label="处理方式" required><el-select v-model="form.processingMethod"><el-option v-for="item in methodOptions" :key="item[0]" :label="item[1]" :value="item[0]" /></el-select></el-form-item></div>
        <el-alert v-if="['SAMPLE','DETECTION_SELECTION'].includes(form.requestType) && ['UPDATE_CURRENT','CREATE_REVISION'].includes(form.processingMethod)" title="该组合无法安全执行：冻结的样品和检测参数必须使用结构化重建流程。" type="error" :closable="false" show-icon />
        <div v-if="form.requestType === 'CONTACT'" class="form-grid"><el-form-item label="新联系人" required><el-input v-model="form.requestedContactName" maxlength="120" /></el-form-item><el-form-item label="新联电话"><el-input v-model="form.requestedContactPhone" maxlength="40" /></el-form-item></div>
        <el-form-item v-if="form.requestType === 'DUE_DATE'" label="新完成时限" required><el-date-picker v-model="form.requestedDueOn" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="修改原因" required><el-input v-model="form.reason" type="textarea" :rows="4" maxlength="2000" show-word-limit /></el-form-item>
        <el-form-item label="修改内容说明"><el-input v-model="form.requestedSummary" type="textarea" :rows="5" maxlength="4000" show-word-limit placeholder="说明修改前后值、影响范围和期望结果" /></el-form-item>
        <el-form-item label="依据附件"><label class="file-picker" :class="{ uploading }"><input type="file" accept="application/pdf,image/png,image/jpeg,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.openxmlformats-officedocument.wordprocessingml.document" @change="uploadAttachment" /><el-icon><Upload /></el-icon>{{ uploading ? '正在安全校验…' : form.attachmentName || '选择 PDF、图片、XLSX 或 DOCX' }}</label><el-link v-if="form.attachmentFileId" :href="`/api/files/${form.attachmentFileId}`" :icon="Link">下载已上传附件</el-link></el-form-item>
      </el-form>
      <template #footer><el-button @click="drawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
    </el-drawer>

    <el-drawer v-model="detailDrawer" title="委托修改证据链" size="720px" class="change-detail-drawer">
      <template v-if="detail">
        <section class="detail-hero"><div><span>{{ detail.commissionNumber }}</span><h2>{{ typeLabel(detail.requestType) }}</h2><p>{{ methodLabel(detail.processingMethod) }} · 申请时锁定委托 V{{ detail.commissionVersionSnapshot }}</p></div><el-tag :type="stateType(detail.state)" effect="dark">{{ stateLabels[detail.state] }}</el-tag></section>
        <el-alert v-for="item in detail.blockers" :key="item" :title="item" type="error" :closable="false" show-icon class="detail-alert" />
        <section class="detail-section"><h3>申请内容</h3><dl><div><dt>检测项目</dt><dd>{{ detail.detectionProjects || '—' }}</dd></div><div><dt>修改原因</dt><dd>{{ detail.reason }}</dd></div><div v-if="detail.requestedContactName"><dt>新联系人</dt><dd>{{ detail.requestedContactName }} {{ detail.requestedContactPhone }}</dd></div><div v-if="detail.requestedDueOn"><dt>新完成时限</dt><dd>{{ detail.requestedDueOn }}</dd></div><div><dt>详细说明</dt><dd>{{ detail.requestedSummary || '—' }}</dd></div><div><dt>依据附件</dt><dd><el-link v-if="detail.attachmentFileId" :href="`/api/files/${detail.attachmentFileId}`" type="primary">{{ detail.attachmentName }}</el-link><span v-else>—</span></dd></div></dl></section>
        <section class="detail-section"><h3>流转与执行</h3><el-timeline><el-timeline-item type="primary" :timestamp="formatTime(detail.requestedAt)"><strong>{{ detail.requestedBy }} 发起申请</strong><p>委托版本 V{{ detail.commissionVersionSnapshot }}</p></el-timeline-item><el-timeline-item v-if="detail.reviewedAt" :type="detail.state === 'APPROVED' ? 'success' : 'danger'" :timestamp="formatTime(detail.reviewedAt)"><strong>{{ detail.reviewedBy }} {{ detail.state === 'APPROVED' ? '审批通过' : '驳回申请' }}</strong><p>{{ detail.reviewComment || '—' }}</p></el-timeline-item><el-timeline-item v-if="detail.appliedCommissionVersion !== undefined" type="success"><strong>变更已执行</strong><p>结果委托版本 V{{ detail.appliedCommissionVersion }}</p></el-timeline-item></el-timeline></section>
        <el-alert title="操作、原因、版本及执行结果均已保留。" type="info" :closable="false" show-icon />
      </template>
      <template #footer><el-button @click="detailDrawer=false">关闭</el-button><el-button v-if="detail?.resultCommissionId" :icon="DocumentAdd" @click="router.push(`/flow/${detail.resultCommissionId}`)">打开结果委托</el-button><el-button v-if="detail && canRun(detail,'APPROVE')" type="success" :disabled="detail.blockers.length > 0" :loading="acting === detail.id" @click="transition(detail,'APPROVE')">审批通过</el-button><el-button v-if="detail && canRun(detail,'REJECT')" type="danger" @click="transition(detail,'REJECT')">驳回</el-button></template>
    </el-drawer>
  </section>
</template>

<style scoped>
.change-filter { display: grid; grid-template-columns: 260px repeat(3,minmax(145px,1fr)) repeat(3,minmax(125px,.8fr)) auto; gap: 8px; padding: 12px; border: 1px solid var(--line); border-radius: 10px 10px 0 0; background: #fff; }
.change-filter :deep(.el-date-editor), .change-filter :deep(.el-select) { width: 100%; }.filter-actions { display: flex; gap: 8px; justify-content: flex-end; }.change-request-page .module-table { flex: 1 1 0; min-height: 0; height: 0; }
.change-drawer :deep(.el-drawer__body), .change-detail-drawer :deep(.el-drawer__body) { background: #f7f9fc; }.change-drawer .el-alert { margin-bottom: 16px; }.change-form { margin-top: 16px; padding: 18px; border: 1px solid var(--line); border-radius: 10px; background: #fff; }.change-form :deep(.el-select), .change-form :deep(.el-date-editor) { width: 100%; }
.change-form :deep(.el-select-dropdown__item) span, .change-form :deep(.el-select-dropdown__item) small { display: block; }.change-form :deep(.el-select-dropdown__item) small { color: var(--muted); }
.commission-snapshot { display: grid; grid-template-columns: repeat(4,1fr); margin-bottom: 16px; border: 1px solid #dce6f3; border-radius: 8px; background: #f5f9ff; }.commission-snapshot > div { padding: 10px 12px; border-right: 1px solid #dce6f3; }.commission-snapshot > div:last-child { border-right: 0; }.commission-snapshot span,.commission-snapshot strong { display: block; }.commission-snapshot span { color: var(--muted); font-size:13px; }.commission-snapshot strong { margin-top: 5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #2d4260; font-size: 13px; }
.file-picker { margin-bottom: 8px; }.detail-hero { display: flex; justify-content: space-between; align-items: flex-start; padding: 20px; color: #fff; border-radius: 10px; background: linear-gradient(135deg,#155ebc,#397fe0); }.detail-hero span { font-size: 13px; opacity: .85; }.detail-hero h2 { margin: 6px 0; font-size: 24px; }.detail-hero p { margin: 0; opacity: .85; }.detail-alert { margin-top: 12px; }
.detail-section { margin-top: 14px; padding: 17px; border: 1px solid var(--line); border-radius: 10px; background: #fff; }.detail-section h3 { margin: 0 0 12px; padding-left: 10px; border-left: 3px solid var(--brand-600); font-size: 15px; }.detail-section dl { margin: 0; }.detail-section dl > div { display: grid; grid-template-columns: 118px 1fr; gap: 18px; padding: 10px 0; border-bottom: 1px solid var(--line); }.detail-section dl > div:last-child { border-bottom: 0; }.detail-section dt { color: var(--muted); }.detail-section dd { margin: 0; line-height: 1.65; word-break: break-word; }.detail-section p { margin: 4px 0 0; color: var(--muted); }
@media (max-width: 1500px) { .change-filter { grid-template-columns: repeat(4,minmax(145px,1fr)); }.change-filter > :first-child { grid-column: span 2; } }
</style>
