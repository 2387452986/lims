<script setup lang="ts">
import { Bell, Clock, Edit, Plus, Refresh, Search, User, UserFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { api, errorMessage } from '../api'
import { useAuthStore } from '../stores/auth'

interface EventType {
  code: string; sourceKey: number; label: string; selectableRecipientKind: 'PRIMARY' | 'ADDITIONAL'
  selectableRecipientLabel: string; implicitRecipientDescription: string; triggerDescription: string
  configured: boolean; active: boolean
}
interface Recipient {
  id: string; employeeNumber: string; name: string; departmentName: string; organizationName: string
  position?: string; title?: string; accountState: string
}
interface Rule {
  id: string; revisionId: string; revisionNumber: number; eventType: string; sourceKey: number
  messageType: string; selectableRecipientKind: 'PRIMARY' | 'ADDITIONAL'; selectableRecipientLabel: string
  implicitRecipientDescription: string; triggerDescription: string; conditionDays: number
  state: 'ACTIVE' | 'RETIRED'; version: number; primaryRecipients: Recipient[]
  additionalRecipients: Recipient[]; activatedAt: string; retiredAt?: string; retiredReason?: string
  updatedAt: string; allowedActions: string[]
}
interface RulePage {
  items: Rule[]; total: number; page: number; pageSize: number
  activeCount: number; retiredCount: number; unconfiguredCount: number
}
interface PersonRow extends Recipient {
  avatarFileId?: string; gender: string; maskedPhone?: string; maskedNationalId?: string
  applicationState: string; personType: string; accountState: string
}
interface PersonnelPage { items: PersonRow[]; nextCursor?: string; hasMore: boolean; total: number }

const auth = useAuthStore()
const canManage = computed(() => auth.user?.permissions.includes('NOTIFICATION_RULE_MANAGE'))
const loading = ref(false)
const saving = ref(false)
const types = ref<EventType[]>([])
const rules = ref<Rule[]>([])
const total = ref(0)
const activeCount = ref(0)
const retiredCount = ref(0)
const unconfiguredCount = ref(10)
const page = ref(1)
const pageSize = ref(20)
const filters = reactive({ eventType: '', state: 'ALL' })
const drawer = ref(false)
const editing = ref<Rule>()
const form = reactive({ eventType: '', conditionDays: 0 })
const selectedPeople = ref<PersonRow[]>([])
const historyDrawer = ref(false)
const history = ref<Rule[]>([])
const historyTitle = ref('')

const peopleDialog = ref(false)
const peopleLoading = ref(false)
const people = ref<PersonRow[]>([])
const peopleTotal = ref(0)
const peopleCursor = ref('')
const peopleNextCursor = ref('')
const peopleCursorHistory = ref<string[]>([])
const peopleTable = ref<any>()
const peopleFilters = reactive({ applicationState: '', personType: '', name: '', position: '' })

const selectedType = computed(() => types.value.find(item => item.code === form.eventType))
const availableTypes = computed(() => types.value.filter(item => !item.configured || item.code === editing.value?.eventType))
const selectedIds = computed(() => new Set(selectedPeople.value.map(item => item.id)))
const stateLabels: Record<string, string> = { ACTIVE: '启用', RETIRED: '已停用' }
const genderLabels: Record<string, string> = { UNKNOWN: '未设置', MALE: '男', FEMALE: '女' }
const applicationLabels: Record<string, string> = { DRAFT: '草稿', SUBMITTED: '已提交', VERIFIED: '已核验', RETURNED: '已退回', ARCHIVED: '已归档' }
const personTypeLabels: Record<string, string> = { EMPLOYEE: '正式员工', CONTRACTOR: '合同人员', EXTERNAL_EXPERT: '外部专家', INTERN: '实习人员' }

function formatTime(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai' }).format(new Date(value))
}
function showError(error: unknown) {
  ElMessage.error(errorMessage(error))
}
function selectedRecipients(rule: Rule) {
  return rule.selectableRecipientKind === 'PRIMARY' ? rule.primaryRecipients : rule.additionalRecipients
}
function names(items: Recipient[]) { return items.length ? items.map(item => item.name).join('、') : '未配置' }
function stateType(state: string) { return state === 'ACTIVE' ? 'success' : 'info' }

async function load(resetPage = false) {
  if (resetPage) page.value = 1
  loading.value = true
  try {
    const query = new URLSearchParams({ state: filters.state, page: String(page.value), pageSize: String(pageSize.value) })
    if (filters.eventType) query.set('eventType', filters.eventType)
    const [eventTypes, result] = await Promise.all([
      api<EventType[]>('/api/notification-rules/event-types'),
      api<RulePage>(`/api/notification-rules?${query}`),
    ])
    types.value = eventTypes; rules.value = result.items; total.value = result.total
    activeCount.value = result.activeCount; retiredCount.value = result.retiredCount
    unconfiguredCount.value = result.unconfiguredCount
  } catch (error) { showError(error) } finally { loading.value = false }
}
function reset() { Object.assign(filters, { eventType: '', state: 'ALL' }); load(true) }

function openCreate() {
  editing.value = undefined
  const first = availableTypes.value[0]
  Object.assign(form, { eventType: first?.code ?? '', conditionDays: 0 })
  selectedPeople.value = []
  drawer.value = true
}
function openEdit(rule: Rule) {
  editing.value = rule
  Object.assign(form, { eventType: rule.eventType, conditionDays: rule.conditionDays })
  selectedPeople.value = selectedRecipients(rule).map(item => ({ ...item, gender: 'UNKNOWN', applicationState: '', personType: '' }))
  drawer.value = true
}
async function save() {
  if (!selectedType.value) return ElMessage.warning('请选择消息类型')
  if (form.conditionDays < 0 || form.conditionDays > 3650) return ElMessage.warning('条件限制须为 0–3650 天')
  if (selectedType.value.selectableRecipientKind === 'PRIMARY' && !selectedPeople.value.length) {
    return ElMessage.warning('该消息类型必须至少选择一名通知人员')
  }
  saving.value = true
  try {
    const body = { conditionDays: form.conditionDays, recipientPersonIds: selectedPeople.value.map(item => item.id) }
    if (editing.value) {
      await api(`/api/notification-rules/${editing.value.id}`, {
        method: 'PUT', headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ ...body, expectedVersion: editing.value.version }),
      })
      ElMessage.success(`R${editing.value.revisionNumber + 1} 已启用，旧版本保持不可变`)
    } else {
      await api('/api/notification-rules', {
        method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ eventType: form.eventType, ...body }),
      })
      ElMessage.success('消息通知规则已启用')
    }
    drawer.value = false
    await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function transition(rule: Rule, action: 'RETIRE' | 'REACTIVATE') {
  try {
    let comment = '重新启用消息通知规则'
    if (action === 'RETIRE') {
      comment = (await ElMessageBox.prompt('停用后不再生成新通知；已生成通知和版本历史保留。请输入停用原因。',
        `停用 · ${rule.messageType}`, { type: 'warning', inputPlaceholder: '说明停用或替代原因',
          inputValidator: value => Boolean(value.trim()) || '必须填写停用原因' })).value.trim()
    } else {
      await ElMessageBox.confirm(`将按 R${rule.revisionNumber} 的条件和人员创建 R${rule.revisionNumber + 1} 并重新启用，确认继续？`,
        `重新启用 · ${rule.messageType}`, { type: 'warning' })
    }
    await api(`/api/notification-rules/${rule.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: rule.version,
        reasonCode: action === 'RETIRE' ? 'NOTIFICATION_RULE_RETIRE' : 'NOTIFICATION_RULE_REACTIVATE', comment }),
    })
    ElMessage.success(action === 'RETIRE' ? '规则已受控停用' : '规则已创建新版本并启用')
    await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') showError(error) }
}
async function openHistory(rule: Rule) {
  try {
    history.value = await api<Rule[]>(`/api/notification-rules/${rule.id}/history`)
    historyTitle.value = rule.messageType; historyDrawer.value = true
  } catch (error) { showError(error) }
}

function peopleQuery() {
  const query = new URLSearchParams({ limit: '20' })
  Object.entries(peopleFilters).forEach(([key, value]) => { if (value) query.set(key, value) })
  if (peopleCursor.value) query.set('cursor', peopleCursor.value)
  return query
}
async function loadPeople(resetCursor = false) {
  if (resetCursor) { peopleCursor.value = ''; peopleCursorHistory.value = [] }
  peopleLoading.value = true
  try {
    const result = await api<PersonnelPage>(`/api/personnel?${peopleQuery()}`)
    people.value = result.items; peopleTotal.value = result.total; peopleNextCursor.value = result.nextCursor ?? ''
    await nextTick()
    peopleTable.value?.clearSelection()
    people.value.filter(item => selectedIds.value.has(item.id)).forEach(item => peopleTable.value?.toggleRowSelection(item, true))
  } catch (error) { showError(error) } finally { peopleLoading.value = false }
}
function openPeople() { peopleDialog.value = true; loadPeople(true) }
function resetPeople() { Object.assign(peopleFilters, { applicationState: '', personType: '', name: '', position: '' }); loadPeople(true) }
function updatePeopleSelection(current: PersonRow[]) {
  const currentPageIds = new Set(people.value.map(item => item.id))
  const next = new Map(selectedPeople.value.filter(item => !currentPageIds.has(item.id)).map(item => [item.id, item]))
  current.forEach(item => next.set(item.id, item))
  selectedPeople.value = [...next.values()]
}
function removePerson(id: string) {
  selectedPeople.value = selectedPeople.value.filter(item => item.id !== id)
  const row = people.value.find(item => item.id === id)
  if (row) peopleTable.value?.toggleRowSelection(row, false)
}
function nextPeoplePage() {
  if (!peopleNextCursor.value) return
  peopleCursorHistory.value.push(peopleCursor.value); peopleCursor.value = peopleNextCursor.value; loadPeople()
}
function previousPeoplePage() {
  if (!peopleCursorHistory.value.length) return
  peopleCursor.value = peopleCursorHistory.value.pop() ?? ''; loadPeople()
}
function selectable(row: PersonRow) { return row.accountState === 'ENABLED' && row.applicationState !== 'ARCHIVED' }

onMounted(() => load())
</script>

<template>
  <section class="page notification-rule-page">
    <header class="page-heading">
      <div><h1>消息通知配置</h1><p>配置通知场景、接收人、提醒条件和消息内容。</p></div>
      <el-button v-if="canManage" type="primary" :icon="Plus" :disabled="unconfiguredCount===0" @click="openCreate">添加规则</el-button>
    </header>

    <section class="notification-metrics">
      <article><span class="metric-icon primary"><el-icon><Bell /></el-icon></span><div><small>消息类型</small><strong>10</strong><span>可配置</span></div></article>
      <article><span class="metric-icon success"><el-icon><Clock /></el-icon></span><div><small>启用规则</small><strong>{{ activeCount }}</strong><span>自动检查</span></div></article>
      <article><span class="metric-icon warning"><el-icon><Edit /></el-icon></span><div><small>未配置类型</small><strong>{{ unconfiguredCount }}</strong><span>等待配置</span></div></article>
      <article><span class="metric-icon neutral"><el-icon><User /></el-icon></span><div><small>受控停用</small><strong>{{ retiredCount }}</strong><span>历史可追溯</span></div></article>
    </section>

    <section class="notification-filter">
      <el-select v-model="filters.eventType" clearable placeholder="消息类型">
        <el-option v-for="item in types" :key="item.code" :label="item.label" :value="item.code" />
      </el-select>
      <el-select v-model="filters.state" placeholder="规则状态"><el-option label="全部状态" value="ALL" /><el-option label="启用" value="ACTIVE" /><el-option label="已停用" value="RETIRED" /></el-select>
      <el-button type="primary" :icon="Search" @click="load(true)">查询</el-button>
      <el-button :icon="Refresh" @click="reset">重置</el-button>
      <span class="toolbar-spacer" />
      <span>当前结果 {{ total }} 条</span>
    </section>

    <el-table v-loading="loading" :data="rules" border stripe class="module-table notification-rule-table" row-key="id">
      <el-table-column prop="messageType" label="消息类型" min-width="150"><template #default="scope"><div class="message-type-cell"><strong>{{ scope.row.messageType }}</strong><span>源类型 {{ scope.row.sourceKey }} · R{{ scope.row.revisionNumber }}</span></div></template></el-table-column>
      <el-table-column label="条件限制" width="90" align="center"><template #default="scope"><strong class="condition-days">{{ scope.row.conditionDays }} 天</strong></template></el-table-column>
      <el-table-column label="通知人员" min-width="160"><template #default="scope"><div class="recipient-cell"><template v-if="scope.row.selectableRecipientKind==='PRIMARY'"><el-tag v-for="person in scope.row.primaryRecipients" :key="person.id" size="small" effect="plain">{{ person.name }}</el-tag></template><span v-else class="implicit-recipient">{{ scope.row.implicitRecipientDescription }}</span></div></template></el-table-column>
      <el-table-column label="其他通知人员" min-width="150"><template #default="scope"><div class="recipient-cell"><template v-if="scope.row.selectableRecipientKind==='ADDITIONAL'"><el-tag v-for="person in scope.row.additionalRecipients" :key="person.id" size="small" effect="plain">{{ person.name }}</el-tag><span v-if="!scope.row.additionalRecipients.length">未配置</span></template><span v-else>—</span></div></template></el-table-column>
      <el-table-column label="状态" width="80" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)" effect="plain">{{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column>
      <el-table-column label="更新时间" width="138"><template #default="scope">{{ formatTime(scope.row.updatedAt) }}</template></el-table-column>
      <el-table-column fixed="right" label="操作" width="150" align="center"><template #default="scope"><el-button v-if="canManage && scope.row.state==='ACTIVE'" link type="primary" @click="openEdit(scope.row)">修改</el-button><el-button link type="primary" @click="openHistory(scope.row)">版本</el-button><el-button v-if="canManage && scope.row.state==='ACTIVE'" link type="danger" @click="transition(scope.row,'RETIRE')">停用</el-button><el-button v-if="canManage && scope.row.state==='RETIRED'" link type="primary" @click="transition(scope.row,'REACTIVATE')">重新启用</el-button></template></el-table-column>
      <template #empty><el-empty description="暂无消息通知规则" /></template>
    </el-table>
    <footer class="table-footer"><span>停用规则不会删除历史版本和已生成的通知。</span><el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[20,50,100]" layout="total, sizes, prev, pager, next, jumper" @current-change="load()" @size-change="load(true)" /></footer>

    <el-drawer v-model="drawer" :title="editing ? `修改消息规则 · ${editing.messageType}` : '添加消息通知规则'" size="min(720px, 92vw)" destroy-on-close :close-on-click-modal="false" class="notification-rule-drawer">
      <el-form label-position="top">
        <el-alert title="修改草稿后需重新发布；已发布版本和历史通知不会被覆盖。" type="info" :closable="false" show-icon />
        <div class="form-grid">
          <el-form-item label="消息类型" required><el-select v-model="form.eventType" :disabled="Boolean(editing)" placeholder="请选择消息类型"><el-option v-for="item in availableTypes" :key="item.code" :label="item.label" :value="item.code"><span>{{ item.label }}</span><small class="type-source-key">源类型 {{ item.sourceKey }}</small></el-option></el-select></el-form-item>
          <el-form-item label="条件限制（天）" required><el-input-number v-model="form.conditionDays" :min="0" :max="3650" :step="1" controls-position="right" /></el-form-item>
        </div>
        <section v-if="selectedType" class="rule-explanation"><strong>{{ selectedType.triggerDescription }}</strong><span>{{ selectedType.label }}信息按计划发送给相关人员；同一业务状态不会重复提醒。</span></section>
        <el-form-item v-if="selectedType" :label="selectedType.selectableRecipientLabel" :required="selectedType.selectableRecipientKind==='PRIMARY'">
          <div class="selected-people-box"><header><span>已选 {{ selectedPeople.length }} 人</span><el-button :icon="User" @click="openPeople">选择人员</el-button></header><div v-if="selectedPeople.length" class="selected-person-tags"><el-tag v-for="person in selectedPeople" :key="person.id" closable effect="plain" @close="removePerson(person.id)">{{ person.name }} · {{ person.departmentName }}</el-tag></div><el-empty v-else :image-size="50" :description="selectedType.selectableRecipientKind==='PRIMARY' ? '至少选择一名可用账号人员' : '可选；流程当前处理人仍会收到通知'" /></div>
        </el-form-item>
        <el-alert v-if="selectedType?.selectableRecipientKind==='ADDITIONAL'" :title="`主通知人员：${selectedType.implicitRecipientDescription}`" type="success" :closable="false" show-icon />
      </el-form>
      <template #footer><div class="drawer-footer"><span>{{ editing ? `保存将创建 R${editing.revisionNumber + 1}，R${editing.revisionNumber} 不被覆盖` : '确定后规则进入定时检查' }}</span><div><el-button @click="drawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">确定</el-button></div></div></template>
    </el-drawer>

    <el-dialog v-model="peopleDialog" :title="selectedType?.selectableRecipientLabel ?? '选择人员'" width="min(1120px, 96vw)" destroy-on-close class="notification-person-dialog">
      <section class="person-picker-filter"><el-select v-model="peopleFilters.applicationState" clearable placeholder="申报状态"><el-option v-for="(label,key) in applicationLabels" :key="key" :label="label" :value="key" /></el-select><el-select v-model="peopleFilters.personType" clearable placeholder="人员类型"><el-option v-for="(label,key) in personTypeLabels" :key="key" :label="label" :value="key" /></el-select><el-input v-model="peopleFilters.name" clearable placeholder="姓名或工号" @keyup.enter="loadPeople(true)" /><el-input v-model="peopleFilters.position" clearable placeholder="职位或职称" @keyup.enter="loadPeople(true)" /><el-button type="primary" :icon="Search" @click="loadPeople(true)">查询</el-button><el-button :icon="Refresh" @click="resetPeople">重置</el-button></section>
      <div class="person-picker-selected"><strong>已选 {{ selectedPeople.length }} 人</strong><div><el-tag v-for="person in selectedPeople" :key="person.id" closable @close="removePerson(person.id)">{{ person.name }}</el-tag></div></div>
      <el-table ref="peopleTable" v-loading="peopleLoading" :data="people" border stripe row-key="id" class="person-picker-table" @selection-change="updatePeopleSelection"><el-table-column type="selection" width="44" :selectable="selectable" reserve-selection /><el-table-column label="头像" width="68" align="center"><template #default="scope"><el-avatar :size="34" :src="scope.row.avatarFileId ? `/api/files/${scope.row.avatarFileId}` : undefined" :icon="UserFilled" /></template></el-table-column><el-table-column prop="name" label="姓名" width="110"><template #default="scope"><strong>{{ scope.row.name }}</strong><small class="person-number">{{ scope.row.employeeNumber }}</small></template></el-table-column><el-table-column label="性别" width="68"><template #default="scope">{{ genderLabels[scope.row.gender] ?? scope.row.gender }}</template></el-table-column><el-table-column prop="maskedPhone" label="手机号码" width="130" /><el-table-column prop="maskedNationalId" label="身份证号码" width="175" /><el-table-column prop="title" label="职称" width="125" /><el-table-column prop="departmentName" label="所在部门" min-width="150" /><el-table-column prop="organizationName" label="所在单位" min-width="220" /><el-table-column label="账号" width="95"><template #default="scope"><el-tag :type="scope.row.accountState==='ENABLED' ? 'success' : 'info'" size="small">{{ scope.row.accountState==='ENABLED' ? '可用' : '不可选' }}</el-tag></template></el-table-column><template #empty><el-empty description="没有符合条件的人员" /></template></el-table>
      <footer class="person-picker-footer"><span>共 {{ peopleTotal }} 人 · 手机和身份证仅显示脱敏值</span><div><el-button :disabled="!peopleCursorHistory.length" @click="previousPeoplePage">上一页</el-button><el-button :disabled="!peopleNextCursor" @click="nextPeoplePage">下一页</el-button></div></footer>
      <template #footer><el-button @click="peopleDialog=false">取消</el-button><el-button type="primary" @click="peopleDialog=false">确认选择（{{ selectedPeople.length }}）</el-button></template>
    </el-dialog>

    <el-drawer v-model="historyDrawer" :title="`版本链 · ${historyTitle}`" size="620px"><el-timeline><el-timeline-item v-for="item in history" :key="item.revisionId" :timestamp="formatTime(item.updatedAt)" :type="item.state==='ACTIVE' ? 'success' : 'info'"><article class="rule-history-item"><header><strong>R{{ item.revisionNumber }} · {{ stateLabels[item.state] }}</strong><el-tag :type="stateType(item.state)" size="small" effect="plain">{{ item.conditionDays }} 天</el-tag></header><p>{{ item.triggerDescription }}</p><dl><div><dt>{{ item.selectableRecipientLabel }}</dt><dd>{{ names(selectedRecipients(item)) }}</dd></div><div v-if="item.retiredReason"><dt>停用原因</dt><dd>{{ item.retiredReason }}</dd></div></dl></article></el-timeline-item></el-timeline></el-drawer>
  </section>
</template>

<style scoped>
.notification-rule-page { overflow-x: hidden; }
.page-heading p:last-child { max-width: 760px; }
.notification-metrics { display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-bottom:12px; }
.notification-metrics article { min-width:0;display:flex;align-items:center;gap:13px;padding:14px 16px;background:#fff;border:1px solid var(--line);border-radius:10px; }
.metric-icon { width:40px;height:40px;display:grid;place-items:center;flex:0 0 40px;border-radius:10px;font-size:19px; }
.metric-icon.primary { color:var(--brand-600);background:#eaf2ff; }.metric-icon.success { color:#11865a;background:#eaf8f2; }.metric-icon.warning { color:#b46f08;background:#fff5df; }.metric-icon.neutral { color:#5b6d86;background:#eef2f7; }
.notification-metrics small,.notification-metrics strong,.notification-metrics div>span { display:block; }.notification-metrics small { color:var(--muted);font-size:13px; }.notification-metrics strong { margin-top:2px;color:#243653;font-size:22px;line-height:1.2; }.notification-metrics div>span { margin-top:2px;color:#8793a5;font-size:13px; }
.notification-filter { display:flex;align-items:center;gap:8px;padding:12px 14px;background:#fff;border:1px solid var(--line);border-radius:10px 10px 0 0; }.notification-filter .el-select { width:220px; }.notification-filter>span:last-child { color:var(--muted);font-size: 13px; }
.notification-rule-table { flex:1 1 0;height:0;min-height:0; }.notification-rule-table :deep(.el-table__cell) { padding-block:8px; }
.message-type-cell strong,.message-type-cell span { display:block; }.message-type-cell strong { color:#263a56;font-size:13px; }.message-type-cell span { margin-top:3px;color:var(--muted);font-size:13px; }.condition-days { color:var(--brand-600);font-size:14px; }
.recipient-cell { display:flex;align-items:center;gap:5px;flex-wrap:wrap;color:var(--muted);font-size: 13px; }.implicit-recipient { color:#365270; }.table-footer { min-height:58px;gap:12px; }.table-footer>span { max-width:420px;font-size:13px; }
.notification-rule-drawer :deep(.el-drawer__header) { margin-bottom:0;padding-bottom:14px;border-bottom:1px solid var(--line); }.notification-rule-drawer :deep(.el-drawer__footer) { border-top:1px solid var(--line); }.notification-rule-drawer :deep(.el-select),.notification-rule-drawer :deep(.el-input-number) { width:100%; }.notification-rule-drawer :deep(.el-alert) { margin-bottom:16px; }
.type-source-key { float:right;margin-left:18px;color:var(--muted); }.rule-explanation { display:flex;flex-direction:column;gap:4px;margin-bottom:16px;padding:12px 14px;border:1px solid #d8e5f4;border-radius:8px;background:#f5f9ff; }.rule-explanation strong { color:#31557e;font-size: 13px; }.rule-explanation span { color:var(--muted);font-size:13px; }
.selected-people-box { width:100%;border:1px solid var(--line);border-radius:8px;overflow:hidden; }.selected-people-box header { display:flex;align-items:center;justify-content:space-between;padding:8px 10px;background:#f8fafd;border-bottom:1px solid var(--line);color:var(--muted);font-size: 13px; }.selected-person-tags { display:flex;gap:6px;flex-wrap:wrap;padding:10px; }.selected-people-box :deep(.el-empty) { padding:10px 0; }.selected-people-box :deep(.el-empty__description) { margin-top:2px; }
.person-picker-filter { display:grid;grid-template-columns:135px 135px minmax(150px,1fr) minmax(150px,1fr) auto auto;gap:8px;margin-bottom:10px; }.person-picker-selected { min-height:44px;display:flex;align-items:center;gap:12px;padding:7px 10px;border:1px solid #d8e5f4;border-bottom:0;background:#f4f8fe; }.person-picker-selected>strong { flex:0 0 auto;color:#315c91;font-size: 13px; }.person-picker-selected>div { display:flex;gap:5px;flex-wrap:wrap; }.person-picker-table { min-height:310px; }.person-number { display:block;margin-top:2px;color:var(--muted);font-size:13px; }.person-picker-footer { min-height:44px;display:flex;align-items:center;justify-content:space-between;color:var(--muted);font-size:13px; }.person-picker-footer>div { display:flex;gap:8px; }
.rule-history-item header { display:flex;align-items:center;justify-content:space-between;gap:12px; }.rule-history-item p { color:var(--muted);font-size: 13px;line-height:1.6; }.rule-history-item dl { margin:0;padding:10px 12px;background:#f8fafd;border-radius:8px; }.rule-history-item dl>div { display:grid;grid-template-columns:90px 1fr;gap:10px;padding:4px 0; }.rule-history-item dt { color:var(--muted);font-size:13px; }.rule-history-item dd { margin:0;color:#344a67;font-size:13px; }
@media (max-width: 1280px) { .notification-metrics { grid-template-columns:repeat(2,minmax(0,1fr)); }.notification-filter .el-select { width:180px; }.person-picker-filter { grid-template-columns:130px 130px 1fr 1fr; }.person-picker-filter .el-button { justify-self:start; } }
</style>
