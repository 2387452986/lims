<script setup lang="ts">
import { Refresh, Search, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { api, errorMessage } from '../api'

interface Option { code: string; label: string; connected: boolean }
interface Facets { businesses: Option[]; operations: Option[] }
interface AuditEvent {
  id: string; occurredAt: string; operationType: string; action: string; operationData: string
  actorName: string; actorAccount: string; remoteAddress: string; businessCode: string
  businessLabel: string; description: string; requestId: string; resourceType: string
  resourceId?: string; reasonCode?: string; comment?: string
}
interface Page { items: AuditEvent[]; nextCursor?: string; hasMore: boolean; total: number }

const loading = ref(false)
const events = ref<AuditEvent[]>([])
const businesses = ref<Option[]>([])
const operations = ref<Option[]>([])
const total = ref(0)
const nextCursor = ref('')
const cursor = ref('')
const cursorHistory = ref<string[]>([])
const pageSize = ref(10)
const detail = ref<AuditEvent>()
const detailDrawer = ref(false)
const filters = reactive({ account: '', dates: [] as Date[], operationData: '', business: '', operationType: '' })
const pageNumber = computed(() => cursorHistory.value.length + 1)
const operationLabels: Record<string, string> = { LOGIN: '登录', BROWSE: '浏览', CREATE: '新增', UPDATE: '修改', DELETE: '删除', LOGOUT: '退出登录' }
const operationTag = (value: string) => value === 'CREATE' ? 'success' : value === 'DELETE' ? 'danger' : value === 'UPDATE' ? 'warning' : value === 'LOGIN' ? 'primary' : 'info'
const formatTime = (value: string) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium', timeZone: 'Asia/Shanghai' }).format(new Date(value))
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
function errorText(error: unknown) { return errorMessage(error) }
function isIdentifier(value: string) { return uuidPattern.test(value) }
function operationDataSummary(item: AuditEvent) { return `${item.businessLabel} · ${item.operationData.slice(0, 8)}…` }
async function copyOperationData(value: string) {
  await navigator.clipboard.writeText(value)
  ElMessage.success('资源标识已复制')
}

function queryParams() {
  const value = new URLSearchParams({ limit: String(pageSize.value) })
  if (filters.account) value.set('account', filters.account)
  if (filters.operationData) value.set('operationData', filters.operationData)
  if (filters.business) value.set('business', filters.business)
  if (filters.operationType) value.set('operationType', filters.operationType)
  if (filters.dates.length === 2) {
    value.set('from', filters.dates[0].toISOString())
    value.set('to', filters.dates[1].toISOString())
  }
  if (cursor.value) value.set('cursor', cursor.value)
  return value
}
async function load(resetCursor = false) {
  if (resetCursor) { cursor.value = ''; cursorHistory.value = [] }
  loading.value = true
  try {
    const result = await api<Page>(`/api/audit-events?${queryParams()}`)
    events.value = result.items; total.value = result.total; nextCursor.value = result.nextCursor ?? ''
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { loading.value = false }
}
async function loadFacets() {
  try { const result = await api<Facets>('/api/audit-events/facets'); businesses.value = result.businesses; operations.value = result.operations }
  catch (error) { ElMessage.error(errorText(error)) }
}
function reset() { Object.assign(filters, { account: '', dates: [], operationData: '', business: '', operationType: '' }); load(true) }
function nextPage() { if (!nextCursor.value) return; cursorHistory.value.push(cursor.value); cursor.value = nextCursor.value; load() }
function previousPage() { if (!cursorHistory.value.length) return; cursor.value = cursorHistory.value.pop() ?? ''; load() }
function changePageSize() { load(true) }
function showDetail(item: AuditEvent) { detail.value = item; detailDrawer.value = true }

onMounted(async () => { await Promise.all([loadFacets(), load(true)]) })
</script>

<template>
  <section class="page audit-log-page">
    <header class="page-heading"><div><h1>操作日志</h1><p>按账号、日期、业务和操作类型查询系统记录。</p></div></header>

    <section class="audit-filter" aria-label="操作日志查询条件">
      <el-form-item label="账号"><el-input v-model="filters.account" clearable placeholder="账号/真实姓名" @keyup.enter="load(true)" /></el-form-item>
      <el-form-item label="日期"><el-date-picker v-model="filters.dates" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" range-separator="—" /></el-form-item>
      <el-form-item label="操作数据"><el-input v-model="filters.operationData" clearable placeholder="编号、名称或资源标识" @keyup.enter="load(true)" /></el-form-item>
      <el-form-item label="操作业务"><el-select v-model="filters.business" clearable filterable placeholder="所有"><el-option v-for="item in businesses" :key="item.code" :label="item.label" :value="item.code"><span>{{ item.label }}</span><small v-if="!item.connected" class="unconnected-option">当前无本地事件映射</small></el-option></el-select></el-form-item>
      <el-form-item label="操作类型"><el-select v-model="filters.operationType" clearable placeholder="所有"><el-option v-for="item in operations" :key="item.code" :label="item.label" :value="item.code" /></el-select></el-form-item>
      <div class="audit-filter-actions"><el-button type="primary" :icon="Search" @click="load(true)">查询</el-button><el-button :icon="Refresh" @click="reset">重置</el-button></div>
    </section>

    <el-table v-loading="loading" :data="events" border stripe row-key="id" class="module-table audit-log-table" @row-dblclick="showDetail">
      <el-table-column label="操作" width="86" align="center"><template #default="scope"><div class="table-tag-cell"><el-tag :type="operationTag(scope.row.operationType)" effect="plain">{{ operationLabels[scope.row.operationType] ?? scope.row.operationType }}</el-tag></div></template></el-table-column>
      <el-table-column label="操作数据" min-width="200"><template #default="scope"><button v-if="isIdentifier(scope.row.operationData)" type="button" class="copyable-code audit-reference" :title="`${scope.row.operationData}（点击复制）`" @click="copyOperationData(scope.row.operationData)">{{ operationDataSummary(scope.row) }}</button><span v-else class="cell-two-line" :title="scope.row.operationData">{{ scope.row.operationData || '—' }}</span></template></el-table-column>
      <el-table-column label="操作账号" width="150"><template #default="scope"><div class="actor-cell"><strong>{{ scope.row.actorName }}</strong><span>{{ scope.row.actorAccount || '系统任务' }}</span></div></template></el-table-column>
      <el-table-column prop="remoteAddress" label="IP 地址" width="135" />
      <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
      <el-table-column label="操作时间" width="168"><template #default="scope">{{ formatTime(scope.row.occurredAt) }}</template></el-table-column>
      <el-table-column prop="businessLabel" label="业务" width="150" show-overflow-tooltip />
      <el-table-column fixed="right" label="详情" width="70" align="center"><template #default="scope"><el-button link type="primary" :icon="View" :aria-label="`查看 ${scope.row.operationData} 审计详情`" @click="showDetail(scope.row)" /></template></el-table-column>
      <template #empty><el-empty description="没有符合条件的操作日志" /></template>
    </el-table>
    <footer class="table-footer audit-footer"><span>共 {{ total }} 条 · 第 {{ pageNumber }} 页 · 审计事件不可修改、不可删除</span><div><el-select v-model="pageSize" aria-label="每页条数" @change="changePageSize"><el-option v-for="size in [10,20,50,100]" :key="size" :label="`${size} 条/页`" :value="size" /></el-select><el-button :disabled="!cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="!nextCursor" @click="nextPage">下一页</el-button></div></footer>

    <el-drawer v-model="detailDrawer" title="操作日志详情" size="min(620px, 92vw)">
      <el-descriptions v-if="detail" :column="1" border><el-descriptions-item label="操作">{{ operationLabels[detail.operationType] }} · {{ detail.action }}</el-descriptions-item><el-descriptions-item label="操作数据">{{ detail.operationData || '—' }}</el-descriptions-item><el-descriptions-item label="操作业务">{{ detail.businessLabel }} · {{ detail.resourceType }}</el-descriptions-item><el-descriptions-item label="资源标识">{{ detail.resourceId || '—' }}</el-descriptions-item><el-descriptions-item label="操作账号">{{ detail.actorName }} · {{ detail.actorAccount || '系统任务' }}</el-descriptions-item><el-descriptions-item label="IP 地址">{{ detail.remoteAddress || '—' }}</el-descriptions-item><el-descriptions-item label="操作时间">{{ formatTime(detail.occurredAt) }}</el-descriptions-item><el-descriptions-item label="请求 ID">{{ detail.requestId }}</el-descriptions-item><el-descriptions-item label="原因码">{{ detail.reasonCode || '—' }}</el-descriptions-item><el-descriptions-item label="说明">{{ detail.comment || detail.description || '—' }}</el-descriptions-item></el-descriptions>
    </el-drawer>
  </section>
</template>

<style scoped>
.audit-log-page { overflow-x:hidden; }.page-heading p:last-child { max-width:820px;margin:6px 0 0;color:var(--muted);font-size: 13px; }
.audit-filter { display:grid;grid-template-columns:minmax(145px,.75fr) minmax(330px,1.55fr) minmax(170px,1fr) minmax(150px,.8fr) minmax(130px,.65fr) auto;align-items:end;gap:9px;padding:13px 14px;background:#fff;border:1px solid var(--line);border-radius:10px 10px 0 0; }.audit-filter :deep(.el-form-item) { margin-bottom:0; }.audit-filter :deep(.el-form-item__label) { padding-bottom:4px;color:#60708a;font-size:13px;line-height:1.2; }.audit-filter :deep(.el-select),.audit-filter :deep(.el-date-editor) { width:100%; }.audit-filter-actions { display:flex;gap:8px;padding-bottom:1px; }.unconnected-option { float:right;margin-left:18px;color:#98a4b5; }
.audit-log-table { flex:1 1 0;height:0;min-height:0; }.audit-log-table :deep(.el-table__cell) { padding-block:8px; }.audit-reference { color:#315b8e; }.actor-cell strong,.actor-cell span { display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap; }.actor-cell strong { color:#2c405d;font-size: 13px; }.actor-cell span { margin-top:2px;color:var(--muted);font-size:13px; }.audit-footer { min-height:56px; }.audit-footer>div { display:flex;align-items:center;gap:8px; }.audit-footer .el-select { width:110px; }
@media (max-width:1280px) { .audit-filter { grid-template-columns:repeat(4,minmax(0,1fr)); }.audit-filter :deep(.el-form-item:nth-child(2)) { grid-column:span 2; }.audit-filter-actions { justify-content:flex-end; } }
</style>
