<script setup lang="ts">
import { CirclePlus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, errorMessage } from '../api'
import { actionTypeLabel, blockerLabel, severityLabel, sourceLabel, statusLabel } from '../presentation'
import { useAuthStore } from '../stores/auth'

interface Option { id: string; code: string; label: string }
interface ReferenceData { people: Option[] }
interface Nonconformance {
  id: string; number: string; sourceType: string; sourceId: string; commissionId: string
  severity: string; description: string; state: string; ownerId?: string; version: number; updatedAt: string
}
interface CorrectiveAction {
  id: string; nonconformanceId: string; actionType: string; description: string; ownerId: string
  dueOn: string; state: string; evidence: string[]; verifiedBy?: string; verifiedAt?: string
}
interface QualityException {
  id: string; resourceType: string; resourceId: string; blockerCode: string; reason: string
  expiresAt: string; requestedBy: string; approvedBy?: string; state: string; decisionComment?: string
  version: number; createdAt: string
}
interface DataChange {
  id: string; testRecordId: string; taskNumber: string; commissionNumber: string; parameterName: string
  equipmentCode?: string; equipmentName?: string; reasonCode: string; reason: string; proposed: Record<string, unknown>
  state: string; requestedBy: string; decidedBy?: string; decisionComment?: string; version: number
  createdAt: string; appliedAt?: string
}
interface CursorPage<T> { items: T[]; nextCursor?: string; hasMore: boolean; total: number }

const route = useRoute()
const auth = useAuthStore()
const moduleKey = computed(() => String(route.params.moduleKey))
const activeTab = ref('capa')
const loading = ref(false)
const nonconformances = ref<Nonconformance[]>([])
const exceptions = ref<QualityException[]>([])
const dataChanges = ref<DataChange[]>([])
const exceptionTotal = ref(0)
const exceptionNextCursor = ref('')
const exceptionCursors = ref<string[]>([])
const exceptionPageSize = ref(50)
const changeTotal = ref(0)
const changeNextCursor = ref('')
const changeCursors = ref<string[]>([])
const changePageSize = ref(50)
const people = ref<Option[]>([])
const selected = ref<Nonconformance>()
const actions = ref<CorrectiveAction[]>([])
const drawer = ref(false)
const actionDialog = ref(false)
const exceptionDialog = ref(false)
const actionForm = reactive({ actionType: 'CORRECTIVE', description: '', ownerId: '', dueOn: '' })
const exceptionForm = reactive({ resourceType: 'TEST_TASK', resourceId: '', blockerCode: 'PERSON_NOT_QUALIFIED', reason: '', hours: 8 })

const canApprove = computed(() => auth.user?.permissions.includes('QUALITY_EXCEPTION_APPROVE'))
const canApproveChanges = computed(() => auth.user?.permissions.includes('DATA_CHANGE_APPROVE'))
const canTransition = computed(() => auth.user?.permissions.includes('BUSINESS_TRANSITION'))
const canVerifyCapa = computed(() => auth.user?.permissions.includes('CAPA_VERIFY'))
const sourceFilter = computed(() => {
  if (['nonconforming-samples', 'nonconforming-concrete-ledger'].includes(moduleKey.value)) return new Set(['SAMPLE', 'TEST_RECORD'])
  if (['nonconforming-reports', 'nonconforming-report-handling'].includes(moduleKey.value)) return new Set(['REPORT'])
  if (moduleKey.value === 'nonconforming-field-tests') return new Set(['TEST_RECORD', 'PROCESS'])
  return undefined
})
const visibleNonconformances = computed(() => sourceFilter.value
  ? nonconformances.value.filter(item => sourceFilter.value?.has(item.sourceType))
  : nonconformances.value)

const ncActions: Record<string, string[]> = {
  OPEN: ['CONFIRM', 'REJECT'], QUALITY_CONFIRMED: ['START_DISPOSITION'],
  DISPOSITION_IN_PROGRESS: ['SUBMIT_VERIFICATION'], PENDING_VERIFICATION: ['CLOSE', 'RETURN'],
}
const actionTransitions: Record<string, string[]> = {
  PLANNED: ['START'], IN_PROGRESS: ['COMPLETE'], COMPLETED: ['VERIFY', 'REJECT'], REJECTED: ['RESTART'],
}
const labels: Record<string, string> = {
  CONFIRM: '质量确认', REJECT: '拒绝/退回', START_DISPOSITION: '开始处置',
  SUBMIT_VERIFICATION: '提交独立验证', CLOSE: '验证关闭', RETURN: '退回处置',
  START: '开始执行', COMPLETE: '完成并提交证据', VERIFY: '独立验证通过', RESTART: '重新执行',
  APPROVE: '批准', REVOKE: '撤销', APPLY: '应用修改',
}

function initialTab() {
  activeTab.value = moduleKey.value === 'quality-exceptions' ? 'exceptions' : moduleKey.value === 'data-change-requests' ? 'changes' : 'capa'
}

async function load() {
  loading.value = true
  try {
    const [nc, qualityExceptions, changes, reference] = await Promise.all([
      api<Nonconformance[]>('/api/nonconformances'),
      api<CursorPage<QualityException>>(`/api/exception-approvals?limit=${exceptionPageSize.value}&cursor=${encodeURIComponent(exceptionCursors.value.at(-1) ?? '')}`),
      api<CursorPage<DataChange>>(`/api/data-change-requests?limit=${changePageSize.value}&cursor=${encodeURIComponent(changeCursors.value.at(-1) ?? '')}`),
      api<ReferenceData>('/api/reference-data'),
    ])
    nonconformances.value = nc
    exceptions.value = qualityExceptions.items
    exceptionTotal.value = qualityExceptions.total
    exceptionNextCursor.value = qualityExceptions.nextCursor ?? ''
    dataChanges.value = changes.items
    changeTotal.value = changes.total
    changeNextCursor.value = changes.nextCursor ?? ''
    people.value = reference.people
    if (selected.value) {
      selected.value = nc.find(item => item.id === selected.value?.id)
      if (selected.value) actions.value = await api<CorrectiveAction[]>(`/api/nonconformances/${selected.value.id}/actions`)
    }
  } catch (error) { showError(error) } finally { loading.value = false }
}

async function loadExceptions(reset = false) {
  if (reset) exceptionCursors.value = []
  try {
    const page = await api<CursorPage<QualityException>>(`/api/exception-approvals?limit=${exceptionPageSize.value}&cursor=${encodeURIComponent(exceptionCursors.value.at(-1) ?? '')}`)
    exceptions.value = page.items; exceptionTotal.value = page.total; exceptionNextCursor.value = page.nextCursor ?? ''
  } catch (error) { showError(error) }
}

async function loadChanges(reset = false) {
  if (reset) changeCursors.value = []
  try {
    const page = await api<CursorPage<DataChange>>(`/api/data-change-requests?limit=${changePageSize.value}&cursor=${encodeURIComponent(changeCursors.value.at(-1) ?? '')}`)
    dataChanges.value = page.items; changeTotal.value = page.total; changeNextCursor.value = page.nextCursor ?? ''
  } catch (error) { showError(error) }
}

function nextExceptionPage() { if (exceptionNextCursor.value) { exceptionCursors.value.push(exceptionNextCursor.value); void loadExceptions() } }
function previousExceptionPage() { if (exceptionCursors.value.length) { exceptionCursors.value.pop(); void loadExceptions() } }
function nextChangePage() { if (changeNextCursor.value) { changeCursors.value.push(changeNextCursor.value); void loadChanges() } }
function previousChangePage() { if (changeCursors.value.length) { changeCursors.value.pop(); void loadChanges() } }

function showError(error: unknown) {
  ElMessage.error(errorMessage(error))
}

function stateType(state: string) {
  if (['CLOSED', 'VERIFIED', 'APPROVED', 'APPLIED'].includes(state)) return 'success'
  if (['REJECTED', 'REVOKED', 'EXPIRED'].includes(state)) return 'danger'
  if (['OPEN', 'REQUESTED', 'PENDING_VERIFICATION', 'COMPLETED'].includes(state)) return 'warning'
  return 'info'
}

function personName(id?: string) { return people.value.find(item => item.id === id)?.label ?? (id ? id.slice(0, 8) : '—') }
function proposedSummary(item: DataChange) {
  const values = item.proposed.values && typeof item.proposed.values === 'object'
    ? item.proposed.values as Record<string, unknown> : {}
  const summary: string[] = []
  if (values.load !== undefined) summary.push(`破坏荷载：${values.load} N`)
  if (values.area !== undefined) summary.push(`受压面积：${values.area} mm²`)
  if (values.calculatedResult !== undefined) summary.push(`计算结果：${values.calculatedResult}`)
  if (item.equipmentCode || item.equipmentName) {
    summary.push(`检测设备：${[item.equipmentCode, item.equipmentName].filter(Boolean).join(' · ')}`)
  }
  return summary.length ? summary : ['受控字段修改']
}
async function copyIdentifier(value: string) {
  await navigator.clipboard.writeText(value)
  ElMessage.success('编号已复制')
}
function availableActionTransitions(item: CorrectiveAction) {
  const values = actionTransitions[item.state] ?? []
  if (['VERIFY', 'REJECT'].some(action => values.includes(action))) {
    return selected.value?.state === 'PENDING_VERIFICATION' && canVerifyCapa.value ? values : []
  }
  return selected.value?.state === 'DISPOSITION_IN_PROGRESS' && canTransition.value ? values : []
}

async function transitionNonconformance(item: Nonconformance, action: string) {
  try {
    let comment = ''
    if (['REJECT', 'RETURN', 'CLOSE'].includes(action)) {
      comment = (await ElMessageBox.prompt('请填写质量决定依据', labels[action], {
        inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '质量决定依据不能为空',
      })).value
    }
    await api(`/api/nonconformances/${item.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: item.version, reasonCode: 'QUALITY_CONTROL', comment }),
    })
    ElMessage.success(`已完成：${labels[action]}`)
    await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

async function openActions(item: Nonconformance) {
  selected.value = item
  actions.value = await api<CorrectiveAction[]>(`/api/nonconformances/${item.id}/actions`)
  drawer.value = true
}

function openActionCreate() {
  Object.assign(actionForm, { actionType: 'CORRECTIVE', description: '', ownerId: people.value[0]?.id ?? '', dueOn: '' })
  actionDialog.value = true
}

async function createAction() {
  if (!selected.value) return
  try {
    await api(`/api/nonconformances/${selected.value.id}/actions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(actionForm),
    })
    actionDialog.value = false
    actions.value = await api<CorrectiveAction[]>(`/api/nonconformances/${selected.value.id}/actions`)
    ElMessage.success('处置措施已建立')
  } catch (error) { showError(error) }
}

async function transitionCorrectiveAction(item: CorrectiveAction, action: string) {
  try {
    let comment = ''
    let evidence: string[] | undefined
    if (action === 'COMPLETE') {
      const response = await ElMessageBox.prompt('逐行填写证据名称、文件编号或核验记录', labels[action], {
        inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '至少填写一项证据',
      })
      evidence = response.value.split('\n').map(value => value.trim()).filter(Boolean)
      comment = '责任人提交处置证据'
    } else if (action === 'REJECT') {
      comment = (await ElMessageBox.prompt('请填写验证不通过原因', labels[action], { inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空' })).value
    } else if (action === 'VERIFY') {
      comment = (await ElMessageBox.prompt('请填写独立验证结论', labels[action], { inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '验证结论不能为空' })).value
    }
    await api(`/api/nonconformances/actions/${item.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ action, comment, evidence }),
    })
    actions.value = await api<CorrectiveAction[]>(`/api/nonconformances/${item.nonconformanceId}/actions`)
    ElMessage.success(`处置措施已${labels[action]}`)
  } catch (error) { if (error !== 'cancel') showError(error) }
}

function openExceptionCreate() {
  Object.assign(exceptionForm, { resourceType: 'TEST_TASK', resourceId: '', blockerCode: 'PERSON_NOT_QUALIFIED', reason: '', hours: 8 })
  exceptionDialog.value = true
}

async function createException() {
  try {
    const expiresAt = new Date(Date.now() + Math.max(1, Math.min(24, Number(exceptionForm.hours))) * 3600000).toISOString()
    await api('/api/exception-approvals', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ...exceptionForm, expiresAt }),
    })
    exceptionDialog.value = false
    ElMessage.success('质量例外已提交，批准前不会绕过阻断')
    await loadExceptions(true)
  } catch (error) { showError(error) }
}

async function decideException(item: QualityException, action: string) {
  try {
    let comment = ''
    if (action !== 'APPROVE') {
      comment = (await ElMessageBox.prompt('请填写决定说明', labels[action], { inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '决定说明不能为空' })).value
    }
    await api(`/api/exception-approvals/${item.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: item.version, comment }),
    })
    ElMessage.success(`质量例外已${labels[action]}`)
    await loadExceptions()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

async function decideChange(item: DataChange, action: string) {
  try {
    let comment = ''
    if (action === 'REJECT') {
      comment = (await ElMessageBox.prompt('请填写拒绝原因', labels[action], { inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '拒绝原因不能为空' })).value
    }
    await api(`/api/data-change-requests/${item.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: item.version, comment }),
    })
    ElMessage.success(`数据修改申请已${labels[action]}`)
    await loadChanges()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

async function applyChange(item: DataChange) {
  try {
    await ElMessageBox.confirm('应用后原记录会标记为“已被新修订替代”，新修订以草稿建立，任务返回待处理。是否继续？', '应用修改', { type: 'warning' })
    await api(`/api/data-change-requests/${item.id}/apply`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ expectedVersion: item.version }),
    })
    ElMessage.success('修改已形成新修订，原记录完整保留')
    await loadChanges()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

watch(moduleKey, () => { initialTab(); load() })
onMounted(() => { initialTab(); load() })
</script>

<template>
  <section class="page quality-page" v-loading="loading">
    <header class="page-heading">
      <div><h1>质量闭环中心</h1></div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </header>
    <el-alert class="quality-gate" title="任何质量例外、数据修改或不符合关闭都不会覆盖原记录；申请、独立审批、证据和版本链会同时写入审计。" type="info" show-icon :closable="false" />
    <el-tabs v-model="activeTab" class="quality-tabs">
      <el-tab-pane label="不符合与 CAPA" name="capa">
        <div class="tab-toolbar"><div><h2>不符合项</h2><p>质量确认、责任人处置、独立验证后才能关闭。</p></div><span class="record-count">{{ visibleNonconformances.length }} 项</span></div>
        <el-table :data="visibleNonconformances" stripe border>
          <el-table-column prop="number" label="不符合编号" width="180" />
          <el-table-column label="来源" width="110"><template #default="scope">{{ sourceLabel(scope.row.sourceType) }}</template></el-table-column>
          <el-table-column label="严重度" width="100" align="center"><template #default="scope"><div class="table-tag-cell"><el-tag :type="scope.row.severity === 'CRITICAL' || scope.row.severity === 'HIGH' ? 'danger' : 'warning'" effect="plain">{{ severityLabel(scope.row.severity) }}</el-tag></div></template></el-table-column>
          <el-table-column prop="description" label="问题描述" min-width="280"><template #default="scope"><span class="cell-two-line" :title="scope.row.description">{{ scope.row.description }}</span></template></el-table-column>
          <el-table-column label="状态" width="150" align="center"><template #default="scope"><div class="table-tag-cell"><el-tag :type="stateType(scope.row.state)" effect="plain">{{ statusLabel(scope.row.state) }}</el-tag></div></template></el-table-column>
          <el-table-column label="操作" width="185" fixed="right" align="center"><template #default="scope"><div class="table-actions table-actions--center">
            <el-button link type="primary" @click="openActions(scope.row)">处置措施</el-button>
            <el-dropdown v-if="canApprove && (ncActions[scope.row.state]?.length ?? 0) > 0" @command="transitionNonconformance(scope.row, String($event))"><el-button link type="primary">质量动作</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="action in ncActions[scope.row.state]" :key="action" :command="action">{{ labels[action] }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
          </div></template></el-table-column>
          <template #empty><el-empty description="暂无不符合项" /></template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="质量例外" name="exceptions">
        <div class="tab-toolbar"><div><h2>限时质量例外</h2><p>仅覆盖人员资格或设备有效性阻断，最长 24 小时，申请人与审批人必须独立。</p></div><el-button v-if="canTransition" type="primary" :icon="CirclePlus" @click="openExceptionCreate">申请例外</el-button></div>
        <el-table :data="exceptions" stripe border>
          <el-table-column label="资源类型" width="120"><template #default="scope">{{ sourceLabel(scope.row.resourceType) }}</template></el-table-column><el-table-column prop="resourceId" label="资源编号" min-width="210"><template #default="scope"><button type="button" class="copyable-code" :title="`${scope.row.resourceId}（点击复制）`" @click="copyIdentifier(scope.row.resourceId)">{{ scope.row.resourceId }}</button></template></el-table-column>
          <el-table-column label="阻断类型" width="170"><template #default="scope">{{ blockerLabel(scope.row.blockerCode) }}</template></el-table-column><el-table-column prop="reason" label="申请原因" min-width="240"><template #default="scope"><span class="cell-two-line" :title="scope.row.reason">{{ scope.row.reason }}</span></template></el-table-column>
          <el-table-column prop="expiresAt" label="到期时间" width="180" /><el-table-column label="状态" width="110" align="center"><template #default="scope"><div class="table-tag-cell"><el-tag :type="stateType(scope.row.state)" effect="plain">{{ statusLabel(scope.row.state) }}</el-tag></div></template></el-table-column>
          <el-table-column label="操作" width="180" fixed="right" align="center"><template #default="scope"><div class="table-actions table-actions--center"><template v-if="canApprove"><el-button v-if="scope.row.state === 'REQUESTED'" link type="primary" @click="decideException(scope.row, 'APPROVE')">批准</el-button><el-button v-if="scope.row.state === 'REQUESTED'" link type="danger" @click="decideException(scope.row, 'REJECT')">拒绝</el-button><el-button v-if="scope.row.state === 'APPROVED'" link type="danger" @click="decideException(scope.row, 'REVOKE')">撤销</el-button></template></div></template></el-table-column>
          <template #empty><el-empty description="暂无质量例外申请" /></template>
        </el-table>
        <footer class="table-footer"><span>共 {{ exceptionTotal }} 条 · 第 {{ exceptionCursors.length + 1 }} 页</span><div><el-select v-model="exceptionPageSize" class="page-size-select" @change="loadExceptions(true)"><el-option v-for="size in [20,50,100]" :key="size" :label="`${size} 条/页`" :value="size" /></el-select><el-button :disabled="!exceptionCursors.length" @click="previousExceptionPage">上一页</el-button><el-button type="primary" plain :disabled="!exceptionNextCursor" @click="nextExceptionPage">下一页</el-button></div></footer>
      </el-tab-pane>

      <el-tab-pane label="数据修改申请" name="changes">
        <div class="tab-toolbar"><div><h2>检测数据受控修改</h2><p>在委托证据链发起；独立批准后以新修订应用，签发报告关联数据必须先修订报告。</p></div><span class="record-count">{{ dataChanges.length }} 项</span></div>
        <el-table :data="dataChanges" stripe border>
          <el-table-column label="任务 / 委托" min-width="205"><template #default="scope"><strong>{{ scope.row.taskNumber }}</strong><div class="cell-secondary">{{ scope.row.commissionNumber }} · {{ scope.row.parameterName }}</div></template></el-table-column>
          <el-table-column prop="reason" label="修改原因" min-width="225"><template #default="scope"><span class="cell-two-line" :title="scope.row.reason">{{ scope.row.reason }}</span></template></el-table-column>
          <el-table-column label="拟修改内容" min-width="260"><template #default="scope"><span class="cell-two-line" :title="proposedSummary(scope.row).join('；')">{{ proposedSummary(scope.row).join('；') }}</span></template></el-table-column>
          <el-table-column label="状态" width="105" align="center"><template #default="scope"><div class="table-tag-cell"><el-tag :type="stateType(scope.row.state)" effect="plain">{{ statusLabel(scope.row.state) }}</el-tag></div></template></el-table-column>
          <el-table-column label="操作" width="140" fixed="right" align="center"><template #default="scope"><div class="table-actions table-actions--center"><template v-if="scope.row.state === 'REQUESTED' && canApproveChanges"><el-button link type="primary" @click="decideChange(scope.row, 'APPROVE')">批准</el-button><el-button link type="danger" @click="decideChange(scope.row, 'REJECT')">拒绝</el-button></template><el-button v-if="scope.row.state === 'APPROVED' && canTransition" link type="primary" @click="applyChange(scope.row)">应用新修订</el-button></div></template></el-table-column>
          <template #empty><el-empty description="暂无数据修改申请" /></template>
        </el-table>
        <footer class="table-footer"><span>共 {{ changeTotal }} 条 · 第 {{ changeCursors.length + 1 }} 页 · 修改只建立新修订</span><div><el-select v-model="changePageSize" class="page-size-select" @change="loadChanges(true)"><el-option v-for="size in [20,50,100]" :key="size" :label="`${size} 条/页`" :value="size" /></el-select><el-button :disabled="!changeCursors.length" @click="previousChangePage">上一页</el-button><el-button type="primary" plain :disabled="!changeNextCursor" @click="nextChangePage">下一页</el-button></div></footer>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="drawer" size="660px" :title="`${selected?.number ?? ''} · CAPA 处置措施`">
      <div class="drawer-toolbar"><p>{{ selected?.description }}</p><el-button v-if="canTransition && selected && ['QUALITY_CONFIRMED','DISPOSITION_IN_PROGRESS'].includes(selected.state)" type="primary" :icon="CirclePlus" @click="openActionCreate">新增措施</el-button></div>
      <el-table :data="actions" stripe border>
        <el-table-column label="类型" width="120"><template #default="scope">{{ actionTypeLabel(scope.row.actionType) }}</template></el-table-column><el-table-column prop="description" label="措施" min-width="190" />
        <el-table-column label="责任人" width="110"><template #default="scope">{{ personName(scope.row.ownerId) }}</template></el-table-column>
        <el-table-column prop="dueOn" label="期限" width="115" /><el-table-column label="状态" width="120" align="center"><template #default="scope"><div class="table-tag-cell"><el-tag :type="stateType(scope.row.state)" effect="plain">{{ statusLabel(scope.row.state) }}</el-tag></div></template></el-table-column>
        <el-table-column label="操作" width="140" align="center"><template #default="scope"><div class="table-actions table-actions--center"><el-dropdown v-if="availableActionTransitions(scope.row).length > 0" @command="transitionCorrectiveAction(scope.row, String($event))"><el-button link type="primary">执行动作</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="action in availableActionTransitions(scope.row)" :key="action" :command="action">{{ labels[action] }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></template></el-table-column>
      </el-table>
      <el-alert v-if="actions.some(item => item.evidence.length)" class="evidence-alert" title="处置证据已保存，验证通过后仍可查阅。" type="success" :closable="false" />
    </el-drawer>

    <el-dialog v-model="actionDialog" title="新增 CAPA 处置措施" width="560px"><el-form label-position="top"><el-form-item label="措施类型" required><el-select v-model="actionForm.actionType"><el-option label="即时纠正" value="CORRECTION" /><el-option label="纠正措施" value="CORRECTIVE" /><el-option label="预防措施" value="PREVENTIVE" /></el-select></el-form-item><el-form-item label="措施描述" required><el-input v-model="actionForm.description" type="textarea" :rows="4" /></el-form-item><div class="form-grid"><el-form-item label="责任人" required><el-select v-model="actionForm.ownerId"><el-option v-for="item in people" :key="item.id" :label="`${item.code} · ${item.label}`" :value="item.id" /></el-select></el-form-item><el-form-item label="完成期限" required><el-date-picker v-model="actionForm.dueOn" type="date" value-format="YYYY-MM-DD" /></el-form-item></div></el-form><template #footer><el-button @click="actionDialog=false">取消</el-button><el-button type="primary" @click="createAction">建立措施</el-button></template></el-dialog>
    <el-dialog v-model="exceptionDialog" title="申请限时质量例外" width="580px"><el-alert title="例外不会取消校验，只允许在独立批准后的有效期内继续指定资源的单个阻断动作。" type="warning" :closable="false" /><el-form label-position="top"><div class="form-grid"><el-form-item label="资源类型" required><el-select v-model="exceptionForm.resourceType" @change="exceptionForm.blockerCode = exceptionForm.resourceType === 'TEST_TASK' ? 'PERSON_NOT_QUALIFIED' : 'EQUIPMENT_NOT_VALID'"><el-option label="检测任务" value="TEST_TASK" /><el-option label="试验记录" value="TEST_RECORD" /></el-select></el-form-item><el-form-item label="阻断类型" required><el-select v-model="exceptionForm.blockerCode"><el-option v-if="exceptionForm.resourceType === 'TEST_TASK'" label="人员资格无效" value="PERSON_NOT_QUALIFIED" /><el-option v-else label="设备有效性无效" value="EQUIPMENT_NOT_VALID" /></el-select></el-form-item></div><el-form-item label="资源 ID" required><el-input v-model="exceptionForm.resourceId" placeholder="从委托证据链复制任务或试验记录 ID" /></el-form-item><el-form-item label="申请原因" required><el-input v-model="exceptionForm.reason" type="textarea" :rows="4" /></el-form-item><el-form-item label="有效时长（小时，1–24）" required><el-input-number v-model="exceptionForm.hours" :min="1" :max="24" /></el-form-item></el-form><template #footer><el-button @click="exceptionDialog=false">取消</el-button><el-button type="primary" @click="createException">提交独立审批</el-button></template></el-dialog>
  </section>
</template>
