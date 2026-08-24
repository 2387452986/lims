<script setup lang="ts">
import { Plus, Refresh, Search, Setting } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { api, errorMessage } from '../api'
import { flattenCatalog, type CatalogNode } from '../detection'
import { useAuthStore } from '../stores/auth'

interface Pattern { resourceType: string; generationMode: string; sequenceScope: string; seed?: string; pattern: string; preview?: string }
interface Rule {
  id: string; seriesId: string; predecessorId?: string; revisionNumber: number; code?: string; name: string
  autoMatchProjectCode: boolean; state: string; version: number; sourceSystem?: string; sourceRecordId?: string
  sourcePath?: string; sourceCapturedAt?: string; publishedAt?: string; updatedAt: string
  patterns: Pattern[]; projectIds: string[]; projectCount: number; allowedActions: string[]
}
interface Template {
  id: string; seriesId: string; predecessorId?: string; revisionNumber: number; code: string; name: string
  prefix: string; prefixPosition?: string; datePattern: string; suffix?: string; suffixPosition?: string
  sequenceWidth: number; state: string; version: number; sourceSystem?: string; sourceRecordId?: string
  sourcePath?: string; sourceCapturedAt?: string; publishedAt?: string; updatedAt: string
  preview: string; allowedActions: string[]
}
interface RevisionPolicy { mode: string; pattern: string; firstRevision: string; description: string }
interface ProjectTreeNode extends CatalogNode { disabled?: boolean; children: ProjectTreeNode[] }

const auth = useAuthStore()
const activeTab = ref('rules')
const query = ref('')
const loading = ref(false)
const saving = ref(false)
const rules = ref<Rule[]>([])
const templates = ref<Template[]>([])
const ruleDrawer = ref(false)
const templateDialog = ref(false)
const policyDialog = ref(false)
const editingRule = ref<Rule>()
const editingTemplate = ref<Template>()
const catalog = ref<ProjectTreeNode[]>([])
const treeRef = ref<any>()
const templatePreview = ref('')
const policy = ref<RevisionPolicy>()
const canWrite = computed(() => auth.user?.permissions.includes('MODULE_WRITE'))
const treeProps = { label: 'name', children: 'children', disabled: 'disabled' }
const typeLabels: Record<string, string> = { COMMISSION: '委托单', SAMPLE: '样品', TASK: '任务单', REPORT: '报告' }
const modeLabels: Record<string, string> = { SEPARATE: '独立流水', SAME_AS_COMMISSION: '与委托单相同', REPLACE_PREFIX: '替换前缀', REMOVE_PREFIX: '去除前缀' }
const scopeLabels: Record<string, string> = { SHARED: '所选项目共用流水', PER_PROJECT: '各项目独立流水' }
const stateLabels: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', RETIRED: '已停用' }
const ruleForm = reactive({ code: '', name: '', autoMatchProjectCode: false, projectIds: [] as string[], expectedVersion: 0, patterns: [] as Pattern[] })
const templateForm = reactive({ code: '', name: '', prefix: '', prefixPosition: '', datePattern: 'y', suffix: '', suffixPosition: '', sequenceWidth: 4, expectedVersion: 0 })

function errorText(error: unknown) { return errorMessage(error) }
function stateType(state: string) { return state === 'PUBLISHED' ? 'success' : state === 'DRAFT' ? 'warning' : 'info' }
function pattern(rule: Rule, type: string) { return rule.patterns.find((item) => item.resourceType === type) }
function mapProjectTree(nodes: CatalogNode[]): ProjectTreeNode[] { return nodes.map((node) => ({ ...node, disabled: node.type !== 'PROJECT', children: mapProjectTree(node.children ?? []) })) }

async function load() {
  loading.value = true
  try {
    [rules.value, templates.value] = await Promise.all([
      api<Rule[]>(`/api/numbering-flows/rules?query=${encodeURIComponent(query.value)}`),
      api<Template[]>(`/api/numbering-flows/templates?query=${encodeURIComponent(query.value)}`),
    ])
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { loading.value = false }
}
function resetQuery() { query.value = ''; load() }

function emptyPatterns(): Pattern[] { return ['COMMISSION', 'SAMPLE', 'TASK', 'REPORT'].map((resourceType, index) => ({
  resourceType, generationMode: index === 0 ? 'SEPARATE' : 'SAME_AS_COMMISSION', sequenceScope: 'SHARED',
  seed: index === 0 ? 'WT0001' : '', pattern: 'WT-{symbol}-y-0001',
})) }

async function ensureCatalog() {
  if (catalog.value.length) return
  const source = await api<CatalogNode[]>('/api/detection-catalog/tree?includeHistory=false')
  catalog.value = mapProjectTree(source)
}
async function openRule(rule?: Rule) {
  try {
    await ensureCatalog()
    editingRule.value = rule
    Object.assign(ruleForm, rule ? { code: rule.code ?? '', name: rule.name, autoMatchProjectCode: rule.autoMatchProjectCode,
      projectIds: [...rule.projectIds], expectedVersion: rule.version, patterns: rule.patterns.map((item) => ({ ...item })) }
      : { code: '', name: '', autoMatchProjectCode: false, projectIds: [], expectedVersion: 0, patterns: emptyPatterns() })
    ruleDrawer.value = true
    await nextTick(); treeRef.value?.setCheckedKeys(ruleForm.projectIds, false)
  } catch (error) { ElMessage.error(errorText(error)) }
}
async function saveRule() {
  const checked = (treeRef.value?.getCheckedKeys(true) ?? []) as string[]
  ruleForm.projectIds = checked
  if (!ruleForm.code.trim() || !ruleForm.name.trim()) return ElMessage.warning('请填写规则代码和名称')
  saving.value = true
  try {
    const path = editingRule.value ? `/api/numbering-flows/rules/${editingRule.value.id}` : '/api/numbering-flows/rules'
    await api(path, { method: editingRule.value ? 'PUT' : 'POST', headers: editingRule.value ? undefined : { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(ruleForm) })
    ruleDrawer.value = false; ElMessage.success('流水规则草稿已保存'); await load()
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { saving.value = false }
}
async function reviseRule(rule: Rule) {
  try {
    const created = await api<Rule>(`/api/numbering-flows/rules/${rule.id}/revisions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } })
    ElMessage.success(`已创建 R${created.revisionNumber} 修订草稿`); await load(); await openRule(created)
  } catch (error) { ElMessage.error(errorText(error)) }
}
async function transitionRule(rule: Rule, action: string) { await transition('rules', rule, action) }

function openTemplate(item?: Template) {
  editingTemplate.value = item
  Object.assign(templateForm, item ? { code: item.code, name: item.name, prefix: item.prefix, prefixPosition: item.prefixPosition ?? '',
    datePattern: item.datePattern, suffix: item.suffix ?? '', suffixPosition: item.suffixPosition ?? '', sequenceWidth: item.sequenceWidth, expectedVersion: item.version }
    : { code: '', name: '', prefix: '', prefixPosition: '', datePattern: 'y', suffix: '', suffixPosition: '', sequenceWidth: 4, expectedVersion: 0 })
  templatePreview.value = item?.preview ?? ''; templateDialog.value = true
}
async function previewTemplate() {
  try {
    const result = await api<{ preview: string }>('/api/numbering-flows/templates/preview', { method: 'POST', body: JSON.stringify({ ...templateForm, prefixPosition: templateForm.prefixPosition || null, suffixPosition: templateForm.suffixPosition || null }) })
    templatePreview.value = result.preview
  } catch (error) { ElMessage.error(errorText(error)) }
}
async function saveTemplate() {
  saving.value = true
  try {
    const path = editingTemplate.value ? `/api/numbering-flows/templates/${editingTemplate.value.id}` : '/api/numbering-flows/templates'
    const payload = { ...templateForm, prefixPosition: templateForm.prefixPosition || null, suffixPosition: templateForm.suffixPosition || null }
    await api(path, { method: editingTemplate.value ? 'PUT' : 'POST', headers: editingTemplate.value ? undefined : { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(payload) })
    templateDialog.value = false; ElMessage.success('流水模板草稿已保存'); await load()
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { saving.value = false }
}
async function reviseTemplate(item: Template) {
  try {
    const created = await api<Template>(`/api/numbering-flows/templates/${item.id}/revisions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } })
    ElMessage.success(`已创建 R${created.revisionNumber} 模板草稿`); await load(); openTemplate(created)
  } catch (error) { ElMessage.error(errorText(error)) }
}
async function transitionTemplate(item: Template, action: string) { await transition('templates', item, action) }

async function transition(resource: 'rules' | 'templates', item: Rule | Template, action: string) {
  let comment = `${action}流水配置`
  if (action !== 'PUBLISH') {
    try { comment = await ElMessageBox.prompt('请填写原因，操作将写入审计日志。', action === 'RETIRE' ? '停用配置' : '废弃草稿', { inputPattern: /\S+/, inputErrorMessage: '原因不能为空' }).then((result) => result.value) } catch { return }
  } else {
    try { await ElMessageBox.confirm('发布后该版本不可原地修改，并替代同系列旧发布版。', '发布流水配置') } catch { return }
  }
  try {
    await api(`/api/numbering-flows/${resource}/${item.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ action, expectedVersion: item.version, reasonCode: 'CONTROLLED_NUMBERING', comment }) })
    ElMessage.success('状态已更新'); await load()
  } catch (error) { ElMessage.error(errorText(error)) }
}
async function showPolicy() {
  try { policy.value = await api<RevisionPolicy>('/api/numbering-flows/report-revision-policy'); policyDialog.value = true }
  catch (error) { ElMessage.error(errorText(error)) }
}
async function handleRuleCommand(item: Rule, command: string) {
  if (command === 'EDIT') return openRule(item)
  if (command === 'REVISE') return reviseRule(item)
  if (['PUBLISH', 'RETIRE', 'DISCARD'].includes(command)) return transitionRule(item, command)
}
async function handleTemplateCommand(item: Template, command: string) {
  if (command === 'EDIT') return openTemplate(item)
  if (command === 'REVISE') return reviseTemplate(item)
  if (['PUBLISH', 'RETIRE', 'DISCARD'].includes(command)) return transitionTemplate(item, command)
}

onMounted(load)
</script>

<template>
  <section class="page numbering-page">
    <header class="page-heading"><div><h1>流水规则管理</h1></div><div class="toolbar-buttons"><el-input v-model="query" :prefix-icon="Search" clearable placeholder="规则代码或名称" @keyup.enter="load" /><el-button type="primary" @click="load">查询</el-button><el-button :icon="Refresh" @click="resetQuery">重置</el-button></div></header>
    <el-tabs v-model="activeTab" class="numbering-tabs" v-loading="loading">
      <el-tab-pane label="规则管理" name="rules">
        <div class="tab-toolbar"><div><h2>业务单据流水</h2><p>同时管理委托、样品、任务和报告编号，发布后按项目冻结。</p></div><div class="toolbar-buttons"><el-button :icon="Setting" @click="showPolicy">报告更正规则</el-button><el-button v-if="canWrite" type="primary" :icon="Plus" @click="openRule()">新增规则</el-button></div></div>
        <el-table :data="rules" border stripe class="module-table numbering-rule-table">
          <el-table-column label="规则" width="230"><template #default="scope"><div class="numbering-identity"><strong v-if="scope.row.code">{{ scope.row.code }}</strong><el-tag v-else type="danger">待补录代码</el-tag><span :title="scope.row.name">{{ scope.row.name }}</span></div></template></el-table-column>
          <el-table-column label="编号预览" min-width="430"><template #default="scope"><div class="number-preview-grid"><article v-for="type in ['COMMISSION','SAMPLE','TASK','REPORT']" :key="type"><label>{{ typeLabels[type] }}</label><strong :title="pattern(scope.row,type)?.preview">{{ pattern(scope.row,type)?.preview || '未配置' }}</strong><span>{{ modeLabels[pattern(scope.row,type)?.generationMode ?? ''] || '未配置' }}</span></article></div></template></el-table-column>
          <el-table-column label="范围 / 版本" width="140"><template #default="scope"><div class="status-stack"><span>{{ scope.row.projectCount ? `${scope.row.projectCount} 个项目` : '全局规则' }}</span><el-tag :type="stateType(scope.row.state)" effect="plain">R{{ scope.row.revisionNumber }} · {{ stateLabels[scope.row.state] }}</el-tag></div></template></el-table-column>
          <el-table-column label="操作" fixed="right" width="104" align="center"><template #default="scope"><el-dropdown trigger="click" @command="(command: string) => handleRuleCommand(scope.row, command)"><el-button link type="primary" :aria-label="`操作 ${scope.row.name}`">操作</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-if="scope.row.state === 'DRAFT' && canWrite" command="EDIT">修改草稿</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('REVISE') && canWrite" command="REVISE">创建修订</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('PUBLISH') && canWrite" command="PUBLISH">发布版本</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('RETIRE') && canWrite" command="RETIRE" divided>停用版本</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('DISCARD') && canWrite" command="DISCARD" divided>废弃草稿</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="流水模板" name="templates">
        <div class="tab-toolbar"><div><h2>流水模板</h2><p>模板定义前缀、日期格式和流水宽度，规则只引用发布版。</p></div><el-button v-if="canWrite" type="primary" :icon="Plus" @click="openTemplate()">新增模板</el-button></div>
        <el-table :data="templates" border stripe class="module-table numbering-template-table"><el-table-column label="模板" width="220"><template #default="scope"><div class="numbering-identity"><strong>{{ scope.row.code }}</strong><span :title="scope.row.name">{{ scope.row.name }}</span></div></template></el-table-column><el-table-column label="格式" width="230"><template #default="scope"><div class="cell-stack"><strong :title="scope.row.prefix">{{ scope.row.prefix || '无前缀' }}</strong><span>{{ scope.row.datePattern }} 日期 · {{ scope.row.sequenceWidth }} 位流水</span></div></template></el-table-column><el-table-column label="受控预览" min-width="260"><template #default="scope"><div class="number-preview"><strong :title="scope.row.preview">{{ scope.row.preview }}</strong><span>发布后规则只引用该版本</span></div></template></el-table-column><el-table-column label="版本" width="112"><template #default="scope"><el-tag :type="stateType(scope.row.state)" effect="plain">R{{ scope.row.revisionNumber }} · {{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column><el-table-column label="操作" fixed="right" width="104" align="center"><template #default="scope"><el-dropdown trigger="click" @command="(command: string) => handleTemplateCommand(scope.row, command)"><el-button link type="primary" :aria-label="`操作 ${scope.row.name}`">操作</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-if="scope.row.state === 'DRAFT' && canWrite" command="EDIT">修改草稿</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('REVISE') && canWrite" command="REVISE">创建修订</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('PUBLISH') && canWrite" command="PUBLISH">发布版本</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('RETIRE') && canWrite" command="RETIRE" divided>停用版本</el-dropdown-item><el-dropdown-item v-if="scope.row.allowedActions.includes('DISCARD') && canWrite" command="DISCARD" divided>废弃草稿</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></el-table-column></el-table>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="ruleDrawer" :title="editingRule ? `修改流水规则 R${editingRule.revisionNumber}` : '新增流水规则'" size="980px" class="number-rule-drawer">
      <el-form label-position="top"><div class="form-grid"><el-form-item label="规则代码" required><el-input v-model="ruleForm.code" maxlength="80" placeholder="历史空代码必须在修订中补录" /></el-form-item><el-form-item label="规则名称" required><el-input v-model="ruleForm.name" maxlength="240" /></el-form-item></div><el-form-item><el-switch v-model="ruleForm.autoMatchProjectCode" active-text="自动匹配项目代码" /></el-form-item>
        <div class="pattern-editor" v-for="item in ruleForm.patterns" :key="item.resourceType"><header><strong>{{ typeLabels[item.resourceType] }}</strong><span>{{ item.preview }}</span></header><div class="pattern-grid"><el-form-item label="生成方式"><el-select v-model="item.generationMode" :disabled="item.resourceType === 'COMMISSION'"><el-option v-for="mode in ['SEPARATE','SAME_AS_COMMISSION','REPLACE_PREFIX','REMOVE_PREFIX']" :key="mode" :label="modeLabels[mode]" :value="mode" /></el-select></el-form-item><el-form-item label="流水范围"><el-select v-model="item.sequenceScope"><el-option v-for="scope in ['SHARED','PER_PROJECT']" :key="scope" :label="scopeLabels[scope]" :value="scope" /></el-select></el-form-item><el-form-item label="流水种子" :required="item.generationMode === 'SEPARATE'"><el-input v-model="item.seed" :disabled="item.generationMode !== 'SEPARATE'" /></el-form-item><el-form-item label="编号模式" required><el-input v-model="item.pattern" placeholder="WT-{symbol}-ymd-0001" /></el-form-item></div></div>
        <el-divider content-position="left">适用检测项目</el-divider><el-alert title="只有检测项目节点可选；不选表示全局受控规则。" type="info" :closable="false" /><div class="rule-project-tree"><el-tree ref="treeRef" :data="catalog" node-key="id" :props="treeProps" show-checkbox check-strictly default-expand-all /></div>
      </el-form><template #footer><el-button @click="ruleDrawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveRule">保存草稿</el-button></template>
    </el-drawer>

    <el-dialog v-model="templateDialog" :title="editingTemplate ? '修改流水模板' : '新增流水模板'" width="720px"><el-form label-position="top"><div class="form-grid"><el-form-item label="模板代码" required><el-input v-model="templateForm.code" /></el-form-item><el-form-item label="模板名称" required><el-input v-model="templateForm.name" /></el-form-item></div><div class="form-grid"><el-form-item label="前缀"><el-input v-model="templateForm.prefix" /></el-form-item><el-form-item label="前缀位置"><el-select v-model="templateForm.prefixPosition" clearable><el-option label="日期前" value="BEFORE_DATE" /><el-option label="日期后" value="AFTER_DATE" /></el-select></el-form-item></div><div class="form-grid"><el-form-item label="日期格式" required><el-select v-model="templateForm.datePattern"><el-option v-for="value in ['NONE','y','ym','ymd','Y','Ym','Ymd']" :key="value" :label="value" :value="value" /></el-select></el-form-item><el-form-item label="流水位数" required><el-input-number v-model="templateForm.sequenceWidth" :min="1" :max="12" /></el-form-item></div><div class="form-grid"><el-form-item label="后缀"><el-input v-model="templateForm.suffix" /></el-form-item><el-form-item label="后缀位置"><el-select v-model="templateForm.suffixPosition" clearable><el-option label="流水前" value="BEFORE_SEQUENCE" /><el-option label="流水后" value="AFTER_SEQUENCE" /></el-select></el-form-item></div><div class="template-preview"><span>实时受控预览</span><strong>{{ templatePreview || '点击生成预览' }}</strong><el-button @click="previewTemplate">生成预览</el-button></div></el-form><template #footer><el-button @click="templateDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveTemplate">保存草稿</el-button></template></el-dialog>
    <el-dialog v-model="policyDialog" title="报告更正规则" width="560px"><el-result icon="success" title="生产受控策略已启用" :sub-title="policy?.description"><template #extra><el-descriptions :column="1" border><el-descriptions-item label="策略模式">{{ policy?.mode }}</el-descriptions-item><el-descriptions-item label="修订格式">{{ policy?.pattern }}</el-descriptions-item><el-descriptions-item label="首次修订">{{ policy?.firstRevision }}</el-descriptions-item></el-descriptions></template></el-result></el-dialog>
  </section>
</template>
