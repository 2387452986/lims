<script setup lang="ts">
import { Check, Printer, Refresh, Search, View as ViewIcon } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { api, errorMessage } from '../api'
import { useAuthStore } from '../stores/auth'

interface Precommission {
  id: string; precommissionNumber?: string; commissionNumber: string; customerId: string; customerName: string
  projectId: string; projectNumber: string; projectName: string; detectionProjects: string
  commissionedOn: string; sampleQuantity?: string; moldingDate?: string; expectedTestDate?: string
  samplingStatus: string; commissionState: string; printStatus: string; acceptedBy?: string; entruster: string
  submittedAt?: string; version: number; updatedAt: string; allowedActions: string[]
}
interface CursorPage<T> { items: T[]; nextCursor?: string; hasMore: boolean; total: number }
interface ScanReadiness { enabled: boolean; code: string; reason: string }

const auth = useAuthStore()
const activeTab = ref('manual')
const loading = ref(false)
const acting = ref('')
const rows = ref<Precommission[]>([])
const selected = ref<Precommission[]>([])
const detail = ref<Precommission>()
const detailDrawer = ref(false)
const nextCursor = ref('')
const cursor = ref('')
const cursorHistory = ref<string[]>([])
const total = ref(0)
const scan = ref<ScanReadiness>()
const filters = reactive({
  dateRange: [] as string[], state: '', detectionProject: '', precommissionNumber: '', commissionNumber: '',
  customer: '', entruster: '', projectNumber: '', projectName: '',
})

const canTransition = computed(() => auth.user?.permissions.includes('BUSINESS_TRANSITION'))
const canWrite = computed(() => auth.user?.permissions.includes('MODULE_WRITE'))
const batchTargets = computed(() => selected.value.filter(item => item.commissionState === 'PRE_ACCEPTED'))

const stateLabels: Record<string, string> = {
  PRE_ACCEPTED: '待受理', ACCEPTED: '已受理', REJECTED: '已退回',
}
const samplingLabels: Record<string, string> = {
  NOT_SAMPLED: '未取样', SAMPLED: '已取样', RECEIVED: '已收样', REJECTED: '已拒收',
}
const stateType = (state: string) => state === 'ACCEPTED' ? 'success' : state === 'REJECTED' ? 'danger' : 'warning'
const showError = (error: unknown) => ElMessage.error(errorMessage(error))
const formatTime = (value?: string) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'

function queryString() {
  const params = new URLSearchParams({ limit: '50' })
  if (filters.dateRange[0]) params.set('from', filters.dateRange[0])
  if (filters.dateRange[1]) params.set('to', filters.dateRange[1])
  for (const key of ['state', 'detectionProject', 'precommissionNumber', 'commissionNumber', 'customer', 'entruster', 'projectNumber', 'projectName'] as const) {
    if (filters[key]) params.set(key, filters[key])
  }
  if (cursor.value) params.set('cursor', cursor.value)
  return params.toString()
}

async function load() {
  loading.value = true
  try {
    const page = await api<CursorPage<Precommission>>(`/api/precommissions?${queryString()}`)
    rows.value = page.items
    nextCursor.value = page.nextCursor ?? ''
    total.value = page.total
    selected.value = []
  } catch (error) { showError(error) }
  finally { loading.value = false }
}

function search() { cursor.value = ''; cursorHistory.value = []; load() }
function reset() {
  Object.assign(filters, { dateRange: [], state: '', detectionProject: '', precommissionNumber: '', commissionNumber: '', customer: '', entruster: '', projectNumber: '', projectName: '' })
  search()
}
function nextPage() { if (nextCursor.value) { cursorHistory.value.push(cursor.value); cursor.value = nextCursor.value; load() } }
function previousPage() { if (cursorHistory.value.length) { cursor.value = cursorHistory.value.pop() ?? ''; load() } }

async function loadScanReadiness() {
  try { scan.value = await api<ScanReadiness>('/api/precommissions/scan-readiness') }
  catch (error) { showError(error) }
}

async function openDetail(row: Precommission) {
  try { detail.value = await api<Precommission>(`/api/precommissions/${row.id}`); detailDrawer.value = true }
  catch (error) { showError(error) }
}

async function transition(row: Precommission, action: 'ACCEPT' | 'REJECT' | 'WITHDRAW') {
  const label = action === 'ACCEPT' ? '受理' : action === 'REJECT' ? '退回' : '撤回'
  let comment = '资料完整，同意受理'
  try {
    if (action !== 'ACCEPT') comment = (await ElMessageBox.prompt(`请填写${label}原因，将写入审计日志`, `${label}预委托`, {
      inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空',
    })).value
    else await ElMessageBox.confirm(`确认受理 ${row.precommissionNumber || row.commissionNumber}？受理后将冻结检测参数并生成预期样品。`, '受理确认', { type: 'warning' })
    acting.value = row.id
    await api(`/api/precommissions/${row.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: `PRECOMMISSION_${action}`, comment }),
    })
    ElMessage.success(`${label}成功`)
    await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
  finally { acting.value = '' }
}

async function batchAccept() {
  if (!batchTargets.value.length) return ElMessage.warning('请勾选待受理记录')
  try {
    await ElMessageBox.confirm(`将一次性受理 ${batchTargets.value.length} 条预委托；任一条校验失败时整批回滚。`, '批量受理', { type: 'warning' })
    acting.value = 'batch'
    await api('/api/precommissions/batch-accept', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ items: batchTargets.value.map(item => ({ id: item.id, expectedVersion: item.version })), reasonCode: 'PRECOMMISSION_BATCH_ACCEPT', comment: '预委托列表批量受理' }),
    })
    ElMessage.success(`已受理 ${batchTargets.value.length} 条预委托`)
    await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
  finally { acting.value = '' }
}

async function printReceipt(row: Precommission) {
  try {
    acting.value = `print-${row.id}`
    await api(`/api/precommissions/${row.id}/prints`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ expectedVersion: row.version }),
    })
    detail.value = await api<Precommission>(`/api/precommissions/${row.id}`)
    detailDrawer.value = true
    await load()
    requestAnimationFrame(() => window.print())
  } catch (error) { showError(error) }
  finally { acting.value = '' }
}

onMounted(async () => { await Promise.all([load(), loadScanReadiness()]) })
</script>

<template>
  <section class="page precommission-page">
    <header class="page-heading">
      <div><h1>预委托受理</h1><p>核对委托项目、检测参数和样品信息后办理受理。</p></div>
      <div class="heading-actions"><el-button :icon="Refresh" @click="load">刷新</el-button><el-button type="primary" :icon="Check" :disabled="!canTransition || !batchTargets.length" :loading="acting === 'batch'" @click="batchAccept">批量受理 {{ batchTargets.length || '' }}</el-button></div>
    </header>

    <el-tabs v-model="activeTab" class="intake-tabs">
      <el-tab-pane label="预委托管理" name="manual">
        <section class="intake-filter">
          <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="委托起始" end-placeholder="委托截止" />
          <el-select v-model="filters.state" clearable placeholder="全部状态"><el-option label="待受理" value="PRE_ACCEPTED" /><el-option label="已受理" value="ACCEPTED" /><el-option label="已退回" value="REJECTED" /></el-select>
          <el-input v-model="filters.detectionProject" clearable placeholder="检测项目" />
          <el-input v-model="filters.precommissionNumber" clearable placeholder="预委托编号" />
          <el-input v-model="filters.commissionNumber" clearable placeholder="委托编号" />
          <el-input v-model="filters.customer" clearable placeholder="委托单位" />
          <el-input v-model="filters.entruster" clearable placeholder="委托人" />
          <el-input v-model="filters.projectNumber" clearable placeholder="工程编号" />
          <el-input v-model="filters.projectName" clearable placeholder="工程名称" @keyup.enter="search" />
          <div class="filter-actions"><el-button type="primary" :icon="Search" @click="search">查询</el-button><el-button @click="reset">重置</el-button></div>
        </section>

        <el-table v-loading="loading" :data="rows" stripe border row-key="id" class="module-table viewport-table" scrollbar-always-on @selection-change="selected = $event" @row-dblclick="openDetail">
          <el-table-column type="selection" width="44" :selectable="(row: Precommission) => row.commissionState === 'PRE_ACCEPTED'" />
          <el-table-column prop="precommissionNumber" label="预委托编号" width="164" fixed="left"><template #default="scope"><strong>{{ scope.row.precommissionNumber || '待生成' }}</strong></template></el-table-column>
          <el-table-column prop="commissionNumber" label="委托编号" width="156" />
          <el-table-column prop="detectionProjects" label="检测项目" min-width="230" show-overflow-tooltip />
          <el-table-column prop="customerName" label="委托单位" min-width="190" show-overflow-tooltip />
          <el-table-column prop="entruster" label="委托人" width="100" />
          <el-table-column prop="projectNumber" label="工程编号" width="140" show-overflow-tooltip />
          <el-table-column prop="projectName" label="工程名称" min-width="210" show-overflow-tooltip />
          <el-table-column prop="sampleQuantity" label="样品数量" width="96" align="right" />
          <el-table-column prop="moldingDate" label="成型日期" width="112"><template #default="scope">{{ scope.row.moldingDate || '—' }}</template></el-table-column>
          <el-table-column prop="expectedTestDate" label="应检日期" width="112"><template #default="scope">{{ scope.row.expectedTestDate || '—' }}</template></el-table-column>
          <el-table-column label="取样状态" width="104"><template #default="scope">{{ samplingLabels[scope.row.samplingStatus] || scope.row.samplingStatus }}</template></el-table-column>
          <el-table-column prop="commissionedOn" label="委托日期" width="112" />
          <el-table-column label="状态" width="96" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.commissionState)" effect="plain">{{ stateLabels[scope.row.commissionState] || scope.row.commissionState }}</el-tag></template></el-table-column>
          <el-table-column label="打印" width="76"><template #default="scope"><span :class="scope.row.printStatus === 'PRINTED' ? 'printed' : 'muted'">{{ scope.row.printStatus === 'PRINTED' ? '已打印' : '未打印' }}</span></template></el-table-column>
          <el-table-column prop="acceptedBy" label="受理人" width="108"><template #default="scope">{{ scope.row.acceptedBy || '—' }}</template></el-table-column>
          <el-table-column label="提交时间" width="154"><template #default="scope">{{ formatTime(scope.row.submittedAt) }}</template></el-table-column>
          <el-table-column label="操作" fixed="right" width="238" align="center"><template #default="scope">
            <el-button link :icon="ViewIcon" @click="openDetail(scope.row)">详情</el-button>
            <el-button v-if="scope.row.allowedActions.includes('ACCEPT')" link type="primary" :disabled="!canTransition" :loading="acting === scope.row.id" @click="transition(scope.row, 'ACCEPT')">受理</el-button>
            <el-button v-if="scope.row.allowedActions.includes('REJECT')" link type="danger" :disabled="!canTransition" @click="transition(scope.row, 'REJECT')">退回</el-button>
            <el-dropdown v-if="scope.row.allowedActions.includes('WITHDRAW') || scope.row.commissionState !== 'REJECTED'" @command="String($event) === 'WITHDRAW' ? transition(scope.row, 'WITHDRAW') : printReceipt(scope.row)"><el-button link>更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-if="scope.row.allowedActions.includes('WITHDRAW')" command="WITHDRAW" :disabled="!canTransition">撤回提交</el-dropdown-item><el-dropdown-item command="PRINT" :disabled="!canWrite" :icon="Printer">打印受理单</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
          </template></el-table-column>
          <template #empty><el-empty description="没有符合条件的预委托" /></template>
        </el-table>
        <footer class="table-footer"><span>共 {{ total }} 条 · 第 {{ cursorHistory.length + 1 }} 页 · 每页最多 50 条</span><div><el-button :disabled="!cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="!nextCursor" @click="nextPage">下一页</el-button></div></footer>
      </el-tab-pane>

      <el-tab-pane label="扫码受理" name="scan">
        <section class="scan-readiness">
          <div class="scan-illustration"><span>扫</span></div>
          <div><h2>扫码受理当前不可用</h2><p>{{ scan?.reason || '正在读取接入状态…' }}</p><el-alert title="请完成扫码设备与协议配置后再启用" type="info" :closable="false" show-icon /><el-button type="primary" disabled>启动扫码器</el-button></div>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="detailDrawer" title="预委托受理单" size="620px" class="precommission-detail">
      <section v-if="detail" class="receipt-sheet">
        <header><div><span>预委托编号</span><strong>{{ detail.precommissionNumber || '待生成' }}</strong></div><el-tag :type="stateType(detail.commissionState)" effect="plain">{{ stateLabels[detail.commissionState] }}</el-tag></header>
        <dl><div><dt>委托编号</dt><dd>{{ detail.commissionNumber }}</dd></div><div><dt>委托单位</dt><dd>{{ detail.customerName }}</dd></div><div><dt>工程</dt><dd>{{ detail.projectNumber }} · {{ detail.projectName }}</dd></div><div><dt>检测项目</dt><dd>{{ detail.detectionProjects || '—' }}</dd></div><div><dt>委托人</dt><dd>{{ detail.entruster }}</dd></div><div><dt>委托日期</dt><dd>{{ detail.commissionedOn }}</dd></div><div><dt>样品数量</dt><dd>{{ detail.sampleQuantity || '—' }}</dd></div><div><dt>成型日期</dt><dd>{{ detail.moldingDate || '—' }}</dd></div><div><dt>预计试验日期</dt><dd>{{ detail.expectedTestDate || '—' }}</dd></div><div><dt>取样状态</dt><dd>{{ samplingLabels[detail.samplingStatus] || detail.samplingStatus }}</dd></div><div><dt>受理人</dt><dd>{{ detail.acceptedBy || '—' }}</dd></div><div><dt>提交时间</dt><dd>{{ formatTime(detail.submittedAt) }}</dd></div></dl>
        <footer>打印状态：{{ detail.printStatus === 'PRINTED' ? '已登记打印' : '未打印' }} · 数据版本 V{{ detail.version }}</footer>
      </section>
      <template #footer><el-button @click="detailDrawer = false">关闭</el-button><el-button v-if="detail && detail.commissionState !== 'REJECTED'" type="primary" :icon="Printer" :disabled="!canWrite" @click="printReceipt(detail)">打印受理单</el-button></template>
    </el-drawer>
  </section>
</template>

<style scoped>
.intake-tabs { padding: 0 14px 14px; background: #fff; border: 1px solid var(--line); border-radius: 10px; }
.intake-tabs :deep(.el-tabs__header) { margin-bottom: 12px; }
.intake-filter { display: grid; grid-template-columns: 260px repeat(4,minmax(140px,1fr)); gap: 8px; margin-bottom: 10px; padding: 12px; border: 1px solid var(--line); border-radius: 8px; background: #f8fafd; }
.intake-filter :deep(.el-date-editor), .intake-filter :deep(.el-select) { width: 100%; }
.filter-actions { display: flex; justify-content: flex-end; gap: 8px; }
.precommission-page .module-table { flex: 1 1 0; min-height: 0; height: 0; }
.printed { color: var(--success); }.muted { color: var(--muted); }
.scan-readiness { min-height: 560px; display: grid; grid-template-columns: 240px minmax(360px,620px); align-items: center; justify-content: center; gap: 55px; }
.scan-readiness h2 { margin: 7px 0 12px; font-size: 25px; color: #243653; }.scan-readiness p:not(.eyebrow) { margin: 0 0 18px; color: var(--muted); line-height: 1.8; }
.scan-readiness .el-alert { margin-bottom: 18px; }.scan-illustration { width: 220px; height: 220px; display: grid; place-items: center; border: 1px solid #cfe0f6; border-radius: 50%; background: radial-gradient(circle at 35% 28%,#fff,#e8f2ff); box-shadow: 0 18px 45px rgba(25,102,205,.12); }
.scan-illustration span { width: 104px; height: 104px; display: grid; place-items: center; color: var(--brand-600); border: 10px solid #91baf0; border-radius: 24px; font-size: 44px; font-weight: 700; }
.receipt-sheet { color: #263852; }.receipt-sheet > header { display: flex; justify-content: space-between; align-items: center; padding: 18px; border-radius: 10px; background: #f2f7ff; }
.receipt-sheet > header span, .receipt-sheet > header strong { display: block; }.receipt-sheet > header span { color: var(--muted); font-size: 13px; }.receipt-sheet > header strong { margin-top: 5px; font-size: 21px; }
.receipt-sheet dl { margin: 18px 0; }.receipt-sheet dl > div { display: grid; grid-template-columns: 128px 1fr; gap: 18px; padding: 11px 4px; border-bottom: 1px solid var(--line); }.receipt-sheet dt { color: var(--muted); }.receipt-sheet dd { margin: 0; text-align: right; word-break: break-word; }.receipt-sheet footer { color: var(--muted); font-size: 13px; }
@media (max-width: 1400px) { .intake-filter { grid-template-columns: repeat(4,minmax(145px,1fr)); }.intake-filter > :first-child { grid-column: span 2; } }
@media print { .page-heading, .intake-tabs, :deep(.el-drawer__header), :deep(.el-drawer__footer), :deep(.el-overlay) > *:not(.el-drawer) { display: none !important; }.receipt-sheet { padding: 24px; } }
</style>
