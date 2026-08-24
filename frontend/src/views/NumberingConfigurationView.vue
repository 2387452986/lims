<script setup lang="ts">
import { Clock, EditPen, Plus, Refresh, Search, Tickets } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, errorMessage } from '../api'
import { useAuthStore } from '../stores/auth'

interface Rule {
  id: string; seriesId: string; predecessorId?: string; revisionNumber: number; ruleCode: string
  resourceType: string; name: string; prefix: string; prefixPosition?: string; datePattern: string
  suffix: string; suffixPosition?: string; sequenceWidth: number; initialSequence: number
  nextSequence: number; nextPreview: string; state: string; version: number
  publishedAt?: string; updatedAt: string; allowedActions: string[]
}
interface TemplateRule {
  id: string; seriesId: string; predecessorId?: string; revisionNumber: number; code: string
  templateType: string; state: string; version: number; publishedAt?: string; updatedAt: string
  allowedActions: string[]
}

const route = useRoute()
const auth = useAuthStore()
const moduleKey = computed(() => String(route.params.moduleKey))
const templateMode = computed(() => moduleKey.value === 'template-numbering')
const canManage = computed(() => auth.user?.permissions.includes('SYSTEM_ADMIN'))
const loading = ref(false)
const saving = ref(false)
const rules = ref<Rule[]>([])
const templates = ref<TemplateRule[]>([])
const summary = reactive({ published: 0, draft: 0, history: 0, series: 0 })
const query = reactive({ q: '', type: '', state: '' })
const ruleDrawer = ref(false)
const templateDialog = ref(false)
const historyDrawer = ref(false)
const historyTitle = ref('')
const ruleHistory = ref<Rule[]>([])
const templateHistory = ref<TemplateRule[]>([])
const editingRule = ref<Rule>()
const editingTemplate = ref<TemplateRule>()
const isRuleRevision = computed(() => Boolean(editingRule.value?.predecessorId))
const preview = ref('')
const resetDialog = ref(false)
const resetTarget = ref<Rule>()
const resetForm = reactive({ nextSequence: 1, comment: '' })
const ruleForm = reactive({ ruleCode: '', resourceType: '', name: '', prefix: '', prefixPosition: '',
  datePattern: 'Y', suffix: '', suffixPosition: '', sequenceWidth: 5, initialSequence: 1, expectedVersion: 0 })
const templateForm = reactive({ code: '', templateType: '', expectedVersion: 0 })

const resourceTypes = [
  { code: 'PROJECT', label: '工程' }, { code: 'CONTRACT', label: '合同' },
  { code: 'QUALITY_ASSESSMENT', label: '质量评定' }, { code: 'PRECOMMISSION', label: '预委托' },
  { code: 'COMMISSION', label: '委托内部流水' },
]
const templateTypes = [
  { code: 'CONCRETE_ASSESSMENT', label: '混凝土评定' },
  { code: 'REPORT_DISTRIBUTION', label: '报告发放单' },
  { code: 'EQUIPMENT_BORROW_RETURN', label: '设备领用归还' },
  { code: 'REPORT_ARCHIVE_FLOW', label: '报告归档任务流转单' },
]
const stateLabels: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', RETIRED: '已停用', DISCARDED: '已废弃' }
const resourceLabel = (value: string) => resourceTypes.find(item => item.code === value)?.label ?? value
const templateLabel = (value: string) => templateTypes.find(item => item.code === value)?.label ?? value
const stateType = (state: string) => state === 'PUBLISHED' ? 'success' : state === 'DRAFT' ? 'warning' : 'info'
const formatTime = (value?: string) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short', timeZone: 'Asia/Shanghai' }).format(new Date(value)) : '—'
const publishedCount = computed(() => summary.published)
const draftCount = computed(() => summary.draft)
const retiredCount = computed(() => summary.history)

function errorText(error: unknown) { return errorMessage(error) }
function params() {
  const value = new URLSearchParams()
  if (query.q) value.set('q', query.q)
  if (query.type) value.set(templateMode.value ? 'templateType' : 'resourceType', query.type)
  if (query.state) value.set('state', query.state)
  return value
}
async function load() {
  loading.value = true
  try {
    const resource = templateMode.value ? 'templates' : 'rules'
    const [items, counts] = await Promise.all([
      api<TemplateRule[] | Rule[]>(`/api/numbering-configuration/${resource}?${params()}`),
      api<typeof summary>(`/api/numbering-configuration/${resource}/summary`),
    ])
    if (templateMode.value) templates.value = items as TemplateRule[]
    else rules.value = items as Rule[]
    Object.assign(summary, counts)
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { loading.value = false }
}
function resetQuery() { Object.assign(query, { q: '', type: '', state: '' }); load() }

function openRule(item?: Rule) {
  editingRule.value = item
  Object.assign(ruleForm, item ? { ruleCode: item.ruleCode, resourceType: item.resourceType, name: item.name,
    prefix: item.prefix, prefixPosition: item.prefixPosition ?? '', datePattern: item.datePattern,
    suffix: item.suffix, suffixPosition: item.suffixPosition ?? '', sequenceWidth: item.sequenceWidth,
    initialSequence: item.initialSequence, expectedVersion: item.version }
    : { ruleCode: '', resourceType: '', name: '', prefix: '', prefixPosition: '', datePattern: 'Y',
      suffix: '', suffixPosition: '', sequenceWidth: 5, initialSequence: 1, expectedVersion: 0 })
  preview.value = item?.nextPreview ?? ''
  ruleDrawer.value = true
}
async function previewRule() {
  try {
    const sequence = isRuleRevision.value ? editingRule.value!.nextSequence : ruleForm.initialSequence
    const result = await api<{ preview: string }>(`/api/numbering-configuration/rules/preview?sequence=${sequence}`, {
      method: 'POST', body: JSON.stringify(ruleForm),
    })
    preview.value = result.preview
  } catch (error) { ElMessage.error(errorText(error)) }
}
async function saveRule() {
  if (!ruleForm.ruleCode || !ruleForm.resourceType || !ruleForm.name) return ElMessage.warning('请填写规则编号、类型和名称')
  saving.value = true
  try {
    const path = editingRule.value ? `/api/numbering-configuration/rules/${editingRule.value.id}` : '/api/numbering-configuration/rules'
    await api(path, { method: editingRule.value ? 'PUT' : 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(ruleForm) })
    ruleDrawer.value = false; ElMessage.success('编号规则草稿已保存'); await load()
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { saving.value = false }
}
async function reviseRule(item: Rule) {
  try {
    const created = await api<Rule>(`/api/numbering-configuration/rules/${item.id}/revisions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ expectedVersion: item.version }),
    })
    ElMessage.success(`已创建 R${created.revisionNumber} 修订草稿`); await load(); openRule(created)
  } catch (error) { ElMessage.error(errorText(error)) }
}
async function transitionRule(item: Rule, action: string) {
  let comment = '发布编号规则'
  try {
    if (action === 'PUBLISH') await ElMessageBox.confirm('发布后该规则将立即参与对应业务编号生成，确认继续？', '发布编号规则', { type: 'warning' })
    else comment = await ElMessageBox.prompt('请填写停用或废弃原因。', action === 'RETIRE' ? '停用编号规则' : '废弃草稿', { inputPattern: /\S+/, inputErrorMessage: '原因不能为空' }).then(result => result.value)
    await api(`/api/numbering-configuration/rules/${item.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: item.version, reasonCode: 'CONTROLLED_NUMBERING', comment }),
    })
    ElMessage.success('编号规则状态已更新'); await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(errorText(error)) }
}
function openReset(item: Rule) {
  resetTarget.value = item; resetForm.nextSequence = item.nextSequence; resetForm.comment = ''; resetDialog.value = true
}
async function resetSequence() {
  if (!resetTarget.value || !resetForm.comment.trim()) return ElMessage.warning('请填写重置原因')
  saving.value = true
  try {
    await api(`/api/numbering-configuration/rules/${resetTarget.value.id}/sequence-reset`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({
        expectedVersion: resetTarget.value.version, nextSequence: resetForm.nextSequence,
        reasonCode: 'MANUAL_SEQUENCE_RESET', comment: resetForm.comment,
      }),
    })
    resetDialog.value = false; ElMessage.success('下一份流水号已受控重置'); await load()
  } catch (error) { ElMessage.error(errorText(error)) } finally { saving.value = false }
}
async function showRuleHistory(item: Rule) {
  try { ruleHistory.value = await api<Rule[]>(`/api/numbering-configuration/rules/${item.id}/revisions`); templateHistory.value = []; historyTitle.value = item.name; historyDrawer.value = true }
  catch (error) { ElMessage.error(errorText(error)) }
}

function openTemplate(item?: TemplateRule) {
  editingTemplate.value = item
  Object.assign(templateForm, item ? { code: item.code, templateType: item.templateType, expectedVersion: item.version }
    : { code: '', templateType: '', expectedVersion: 0 })
  templateDialog.value = true
}
async function saveTemplate() {
  if (!templateForm.code || !templateForm.templateType) return ElMessage.warning('请填写编号并选择类型')
  saving.value = true
  try {
    const path = editingTemplate.value ? `/api/numbering-configuration/templates/${editingTemplate.value.id}` : '/api/numbering-configuration/templates'
    await api(path, { method: editingTemplate.value ? 'PUT' : 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(templateForm) })
    templateDialog.value = false; ElMessage.success('模板编号草稿已保存'); await load()
  } catch (error) { ElMessage.error(errorText(error)) } finally { saving.value = false }
}
async function reviseTemplate(item: TemplateRule) {
  try {
    const created = await api<TemplateRule>(`/api/numbering-configuration/templates/${item.id}/revisions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ expectedVersion: item.version }),
    })
    ElMessage.success(`已创建 R${created.revisionNumber} 修订草稿`); await load(); openTemplate(created)
  } catch (error) { ElMessage.error(errorText(error)) }
}
async function transitionTemplate(item: TemplateRule, action: string) {
  let comment = '发布模板编号'
  try {
    if (action === 'PUBLISH') await ElMessageBox.confirm('发布后该类型将采用本版本编号，确认继续？', '发布模板编号', { type: 'warning' })
    else comment = await ElMessageBox.prompt('请填写停用或废弃原因。', action === 'RETIRE' ? '停用模板编号' : '废弃草稿', { inputPattern: /\S+/, inputErrorMessage: '原因不能为空' }).then(result => result.value)
    await api(`/api/numbering-configuration/templates/${item.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: item.version, reasonCode: 'CONTROLLED_TEMPLATE_NUMBERING', comment }),
    })
    ElMessage.success('模板编号状态已更新'); await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(errorText(error)) }
}
async function showTemplateHistory(item: TemplateRule) {
  try { templateHistory.value = await api<TemplateRule[]>(`/api/numbering-configuration/templates/${item.id}/revisions`); ruleHistory.value = []; historyTitle.value = templateLabel(item.templateType); historyDrawer.value = true }
  catch (error) { ElMessage.error(errorText(error)) }
}

watch(moduleKey, () => { Object.assign(query, { q: '', type: '', state: '' }); load() })
onMounted(load)
</script>

<template>
  <section class="page numbering-configuration-page">
    <header class="page-heading">
      <div><h1>{{ templateMode ? '模板编号配置' : '编号配置规则' }}</h1><p>{{ templateMode ? '配置报告、委托、任务和原始记录模板编号。' : '配置业务编号的组成、格式和流水号。' }}</p></div>
      <el-button v-if="canManage" type="primary" :icon="Plus" @click="templateMode ? openTemplate() : openRule()">添加{{ templateMode ? '模板编号' : '编号规则' }}</el-button>
    </header>

    <section class="numbering-metrics">
      <article><span class="metric-icon primary"><el-icon><Tickets /></el-icon></span><div><small>参考类型</small><strong>{{ templateMode ? 4 : 5 }}</strong><span>逐项受控</span></div></article>
      <article><span class="metric-icon success"><el-icon><Clock /></el-icon></span><div><small>已发布</small><strong>{{ publishedCount }}</strong><span>当前生效</span></div></article>
      <article><span class="metric-icon warning"><el-icon><EditPen /></el-icon></span><div><small>修订草稿</small><strong>{{ draftCount }}</strong><span>尚未影响业务</span></div></article>
      <article><span class="metric-icon neutral"><el-icon><Refresh /></el-icon></span><div><small>历史版本</small><strong>{{ retiredCount }}</strong><span>长期可追溯</span></div></article>
    </section>

    <section class="numbering-filter">
      <el-input v-model="query.q" clearable :prefix-icon="Search" :placeholder="templateMode ? '编号' : '规则编号或名称'" @keyup.enter="load" />
      <el-select v-model="query.type" clearable :placeholder="templateMode ? '模板类型' : '规则类型'">
        <el-option v-for="item in templateMode ? templateTypes : resourceTypes" :key="item.code" :label="item.label" :value="item.code" />
      </el-select>
      <el-select v-model="query.state" clearable placeholder="状态"><el-option v-for="(label,key) in stateLabels" :key="key" :label="label" :value="key" /></el-select>
      <el-button type="primary" :icon="Search" @click="load">查询</el-button><el-button :icon="Refresh" @click="resetQuery">重置</el-button>
      <span class="toolbar-spacer" /><span>当前结果 {{ templateMode ? templates.length : rules.length }} 条</span>
    </section>

    <el-table v-if="!templateMode" v-loading="loading" :data="rules" border stripe row-key="id" class="module-table numbering-config-table">
      <el-table-column prop="ruleCode" label="流水规则编号" width="126"><template #default="scope"><strong class="rule-code">{{ scope.row.ruleCode }}</strong></template></el-table-column>
      <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="resourceType" label="规则类型" width="126"><template #default="scope">{{ resourceLabel(scope.row.resourceType) }}</template></el-table-column>
      <el-table-column prop="sequenceWidth" label="流水号位数" width="96" align="center" />
      <el-table-column prop="nextSequence" label="下一份流水号" width="110" align="right" />
      <el-table-column prop="nextPreview" label="下一份流水预览" min-width="190"><template #default="scope"><code class="number-preview">{{ scope.row.nextPreview }}</code></template></el-table-column>
      <el-table-column prop="datePattern" label="日期规则" width="90" />
      <el-table-column prop="prefix" label="前缀" width="96"><template #default="scope">{{ scope.row.prefix || '—' }}</template></el-table-column>
      <el-table-column label="版本 / 状态" width="118" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)" effect="plain">R{{ scope.row.revisionNumber }} · {{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column>
      <el-table-column fixed="right" label="操作" width="190" align="center"><template #default="scope"><el-button v-if="scope.row.allowedActions.includes('EDIT') && canManage" link type="primary" @click="openRule(scope.row)">修改</el-button><el-button link type="primary" @click="showRuleHistory(scope.row)">版本</el-button><el-dropdown v-if="canManage" trigger="click" @command="(command:string) => command==='REVISE' ? reviseRule(scope.row) : command==='RESET' ? openReset(scope.row) : transitionRule(scope.row,command)"><el-button link type="primary">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-if="scope.row.allowedActions.includes('PUBLISH')" command="PUBLISH">发布</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('REVISE')" command="REVISE">创建修订</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('RESET')" command="RESET">重置流水号</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('RETIRE')" command="RETIRE" divided>停用</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('DISCARD')" command="DISCARD" divided>废弃草稿</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></el-table-column>
      <template #empty><el-empty description="暂无编号配置" /></template>
    </el-table>

    <el-table v-else v-loading="loading" :data="templates" border stripe row-key="id" class="module-table numbering-config-table">
      <el-table-column prop="code" label="编号" min-width="240"><template #default="scope"><strong class="rule-code">{{ scope.row.code }}</strong></template></el-table-column>
      <el-table-column prop="templateType" label="类型" min-width="220"><template #default="scope">{{ templateLabel(scope.row.templateType) }}</template></el-table-column>
      <el-table-column label="版本 / 状态" width="150" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)" effect="plain">R{{ scope.row.revisionNumber }} · {{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column>
      <el-table-column label="更新时间" width="160"><template #default="scope">{{ formatTime(scope.row.updatedAt) }}</template></el-table-column>
      <el-table-column fixed="right" label="操作" width="190" align="center"><template #default="scope"><el-button v-if="scope.row.allowedActions.includes('EDIT') && canManage" link type="primary" @click="openTemplate(scope.row)">修改</el-button><el-button link type="primary" @click="showTemplateHistory(scope.row)">版本</el-button><el-dropdown v-if="canManage" trigger="click" @command="(command:string) => command==='REVISE' ? reviseTemplate(scope.row) : transitionTemplate(scope.row,command)"><el-button link type="primary">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-if="scope.row.allowedActions.includes('PUBLISH')" command="PUBLISH">发布</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('REVISE')" command="REVISE">创建修订</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('RETIRE')" command="RETIRE" divided>停用</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('DISCARD')" command="DISCARD" divided>废弃草稿</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></el-table-column>
      <template #empty><el-empty description="暂无模板编号配置" /></template>
    </el-table>
    <footer class="table-footer"><span>{{ templateMode ? '已发布模板编号通过创建修订进行变更。' : '重置流水号需要填写原因，已生成编号不会改变。' }}</span><span>{{ templateMode ? '四类模板编号' : '五类业务编号' }}</span></footer>

    <el-drawer v-model="ruleDrawer" :title="editingRule ? `修改编号规则 R${editingRule.revisionNumber}` : '添加编号规则'" size="min(780px, 94vw)" destroy-on-close :close-on-click-modal="false" class="numbering-rule-drawer">
      <el-form label-position="top"><el-alert title="发布后规则将用于对应业务编号生成；修改已发布规则需创建修订。" type="info" :closable="false" show-icon />
        <div class="form-grid"><el-form-item label="流水号规则编号" required><el-input v-model="ruleForm.ruleCode" maxlength="6" show-word-limit placeholder="6 位数字或字母" /></el-form-item><el-form-item label="规则类型" required><el-select v-model="ruleForm.resourceType" :disabled="Boolean(editingRule)"><el-option v-for="item in resourceTypes" :key="item.code" :label="item.label" :value="item.code" /></el-select></el-form-item></div>
        <el-form-item label="名称" required><el-input v-model="ruleForm.name" maxlength="200" /></el-form-item>
        <div class="form-grid"><el-form-item label="前缀"><el-input v-model="ruleForm.prefix" maxlength="80" placeholder="例如 WT、HNT" /></el-form-item><el-form-item label="前缀位于"><el-select v-model="ruleForm.prefixPosition" clearable :disabled="!ruleForm.prefix"><el-option label="编号前" value="NUMBER_START" /><el-option label="流水号前" value="BEFORE_SEQUENCE" /></el-select></el-form-item></div>
        <div class="form-grid"><el-form-item label="日期规则" required><el-select v-model="ruleForm.datePattern"><el-option label="无日期" value="NONE" /><el-option label="四位年 Y" value="Y" /><el-option label="两位年 y" value="y" /><el-option label="四位年月 Ym" value="Ym" /><el-option label="两位年月 ym" value="ym" /><el-option label="四位年月日 Ymd" value="Ymd" /><el-option label="两位年月日 ymd" value="ymd" /></el-select></el-form-item><el-form-item label="后缀"><el-input v-model="ruleForm.suffix" maxlength="80" /></el-form-item></div>
        <div class="form-grid"><el-form-item label="后缀位于"><el-select v-model="ruleForm.suffixPosition" clearable :disabled="!ruleForm.suffix"><el-option label="编号后" value="NUMBER_END" /><el-option label="日期规则后" value="AFTER_DATE" /></el-select></el-form-item><el-form-item label="流水号位数" required><el-input-number v-model="ruleForm.sequenceWidth" :min="1" :max="12" controls-position="right" /></el-form-item></div>
        <el-form-item label="下一份流水号" required>
          <el-input-number v-if="isRuleRevision" :model-value="editingRule?.nextSequence" disabled controls-position="right" />
          <el-input-number v-else v-model="ruleForm.initialSequence" :min="1" :max="999999999999" controls-position="right" />
          <small class="field-hint">{{ isRuleRevision ? '修订继承已运行序列；请在已发布版本上使用“重置流水号”调整。' : '新规则首次发布时采用。' }}</small>
        </el-form-item>
        <section class="preview-surface"><span>下一份编号预览</span><strong>{{ preview || '填写后生成预览' }}</strong><el-button @click="previewRule">生成预览</el-button></section>
      </el-form><template #footer><el-button @click="ruleDrawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveRule">保存草稿</el-button></template>
    </el-drawer>

    <el-dialog v-model="templateDialog" :title="editingTemplate ? `修改模板编号 R${editingTemplate.revisionNumber}` : '添加模板编号'" width="min(620px, 92vw)" destroy-on-close><el-form label-position="top"><el-alert title="已发布模板编号不能直接修改，请创建修订后再发布。" type="info" :closable="false" show-icon /><el-form-item label="编号" required><el-input v-model="templateForm.code" maxlength="80" placeholder="请输入编号" /></el-form-item><el-form-item label="类型" required><el-select v-model="templateForm.templateType" :disabled="Boolean(editingTemplate)"><el-option v-for="item in templateTypes" :key="item.code" :label="item.label" :value="item.code" /></el-select></el-form-item></el-form><template #footer><el-button @click="templateDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveTemplate">保存草稿</el-button></template></el-dialog>

    <el-dialog v-model="resetDialog" title="重置下一份流水号" width="min(560px, 92vw)" :close-on-click-modal="false"><el-alert title="该动作会影响后续业务编号；已生成编号不会改变。" type="warning" :closable="false" show-icon /><el-form label-position="top"><el-form-item label="下一份流水号" required><el-input-number v-model="resetForm.nextSequence" :min="1" :max="999999999999" controls-position="right" /></el-form-item><el-form-item label="重置原因" required><el-input v-model="resetForm.comment" type="textarea" :rows="4" maxlength="1000" show-word-limit /></el-form-item></el-form><template #footer><el-button @click="resetDialog=false">取消</el-button><el-button type="danger" :loading="saving" @click="resetSequence">确认重置</el-button></template></el-dialog>

    <el-drawer v-model="historyDrawer" :title="`版本链 · ${historyTitle}`" size="620px"><el-timeline><el-timeline-item v-for="item in ruleHistory" :key="item.id" :timestamp="formatTime(item.updatedAt)" :type="item.state==='PUBLISHED' ? 'success' : 'info'"><article class="version-item"><header><strong>R{{ item.revisionNumber }} · {{ stateLabels[item.state] }}</strong><el-tag size="small" effect="plain">{{ item.ruleCode }}</el-tag></header><p>{{ item.nextPreview }}</p><span>{{ resourceLabel(item.resourceType) }} · {{ item.sequenceWidth }} 位流水 · 下一份 {{ item.nextSequence }}</span></article></el-timeline-item><el-timeline-item v-for="item in templateHistory" :key="item.id" :timestamp="formatTime(item.updatedAt)" :type="item.state==='PUBLISHED' ? 'success' : 'info'"><article class="version-item"><header><strong>R{{ item.revisionNumber }} · {{ stateLabels[item.state] }}</strong><el-tag size="small" effect="plain">{{ item.code }}</el-tag></header><span>{{ templateLabel(item.templateType) }}</span></article></el-timeline-item></el-timeline></el-drawer>
  </section>
</template>

<style scoped>
.numbering-configuration-page { overflow-x:hidden; }.page-heading p:last-child { max-width:760px;margin:6px 0 0;color:var(--muted);font-size: 13px; }
.numbering-metrics { display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-bottom:12px; }.numbering-metrics article { min-width:0;display:flex;align-items:center;gap:13px;padding:14px 16px;background:#fff;border:1px solid var(--line);border-radius:10px; }.metric-icon { width:40px;height:40px;display:grid;place-items:center;flex:0 0 40px;border-radius:10px;font-size:19px; }.metric-icon.primary { color:var(--brand-600);background:#eaf2ff; }.metric-icon.success { color:#11865a;background:#eaf8f2; }.metric-icon.warning { color:#b46f08;background:#fff5df; }.metric-icon.neutral { color:#5b6d86;background:#eef2f7; }.numbering-metrics small,.numbering-metrics strong,.numbering-metrics div>span { display:block; }.numbering-metrics small { color:var(--muted);font-size:13px; }.numbering-metrics strong { margin-top:2px;color:#243653;font-size:22px;line-height:1.2; }.numbering-metrics div>span { margin-top:2px;color:#8793a5;font-size:13px; }
.numbering-filter { display:flex;align-items:center;gap:8px;padding:12px 14px;background:#fff;border:1px solid var(--line);border-radius:10px 10px 0 0; }.numbering-filter .el-input { width:260px; }.numbering-filter .el-select { width:170px; }.numbering-filter>span:last-child { color:var(--muted);font-size: 13px; }.numbering-config-table { flex:1 1 0;height:0;min-height:0; }.numbering-config-table :deep(.el-table__cell) { padding-block:8px; }.rule-code { color:#24466f;letter-spacing:.04em; }.number-preview { display:block;overflow:hidden;color:var(--brand-600);font-weight:600;text-overflow:ellipsis;white-space:nowrap; }.table-footer { min-height:54px;gap:16px; }.table-footer span:first-child { max-width:700px; }
.numbering-rule-drawer :deep(.el-drawer__header) { margin-bottom:0;padding-bottom:14px;border-bottom:1px solid var(--line); }.numbering-rule-drawer :deep(.el-drawer__footer) { border-top:1px solid var(--line); }.numbering-rule-drawer :deep(.el-alert) { margin-bottom:16px; }.numbering-rule-drawer :deep(.el-select),.numbering-rule-drawer :deep(.el-input-number) { width:100%; }.field-hint { display:block;margin-top:4px;color:var(--muted);font-size:13px; }.preview-surface { display:grid;grid-template-columns:145px 1fr auto;align-items:center;gap:12px;padding:14px 16px;border:1px solid #d5e3f4;border-radius:9px;background:#f5f9ff; }.preview-surface span { color:var(--muted);font-size: 13px; }.preview-surface strong { overflow:hidden;color:var(--brand-600);font-size:16px;text-overflow:ellipsis;white-space:nowrap; }.version-item { padding:12px;border:1px solid var(--line);border-radius:9px;background:#fff; }.version-item header { display:flex;align-items:center;justify-content:space-between;gap:10px; }.version-item p { margin:8px 0;color:var(--brand-600);font-family:ui-monospace,monospace; }.version-item>span { color:var(--muted);font-size:13px; }
@media (max-width:1280px) { .numbering-metrics { grid-template-columns:repeat(2,minmax(0,1fr)); }.numbering-filter .el-input { width:210px; }.numbering-filter .el-select { width:145px; } }
</style>
