<script setup lang="ts">
import {
  ArrowDown, CirclePlus, CopyDocument, Edit, Expand, Fold, Link, List, Plus, Refresh,
  Search, Setting, Sort, Tickets,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import {
  firstProject, flattenCatalog, type CatalogNode, type DetectionGroup, type DetectionMarker,
  type DetectionParameter, type DetectionSampleField, type DetectionStandard, type ProjectRevision,
} from '../detection'
import { useAuthStore } from '../stores/auth'
import { sourceLabel, statusLabel } from '../presentation'

interface FieldDefinition {
  key: string
  label: string
  type: string
  required: boolean
  unit: string
  optionsText: string
}
interface ConfigurationVersion { id: string; code: string; name: string; versionNumber: number; state: string }

const router = useRouter()
const auth = useAuthStore()
const loadingTree = ref(false)
const loadingRevision = ref(false)
const saving = ref(false)
const tree = ref<CatalogNode[]>([])
const selectedNode = ref<CatalogNode>()
const revision = ref<ProjectRevision>()
const selectedParameterId = ref('')
const treeQuery = ref('')
const parameterQuery = ref('')
const includeInactive = ref(false)
const includeHistory = ref(true)
const treeRenderKey = ref(0)
const expandedKeys = ref<string[]>([])
const treeScrollEl = ref<HTMLElement>()
const treeHeight = ref(320)
const selectedBatch = ref<DetectionParameter[]>([])
const activeEditorTab = ref('basic')
const creatingParameter = ref(false)
const nodeDialog = ref(false)
const nodeCreating = ref(false)
const nodeMoving = ref(false)
const batchDialog = ref(false)
const groupDialog = ref(false)
const markerDialog = ref(false)
const sampleFieldDialog = ref(false)
const configurationDialog = ref(false)
const editingGroup = ref<DetectionGroup>()
const editingMarker = ref<DetectionMarker>()
const editingSampleField = ref<DetectionSampleField>()
const configurationOptions = ref<ConfigurationVersion[]>([])
const standards = ref<DetectionStandard[]>([])
const fieldDefinitions = ref<FieldDefinition[]>([])
const definitionBase = ref<Record<string, unknown>>({})

const treeProps = { value: 'id', label: 'name', children: 'children' }
const flatNodes = computed(() => flattenCatalog(tree.value))
const projectCount = computed(() => flatNodes.value.filter((item) => item.type === 'PROJECT').length)
const parameterTotal = computed(() => flatNodes.value.filter((item) => item.type === 'PROJECT')
  .reduce((total, item) => total + item.parameterCount, 0))
const canWrite = computed(() => auth.user?.permissions.includes('MODULE_WRITE'))
const canEdit = computed(() => canWrite.value && revision.value?.status === 'DRAFT')
const selectedParameter = computed(() => revision.value?.parameters.find((item) => item.id === selectedParameterId.value))
const visibleParameters = computed(() => {
  const needle = parameterQuery.value.trim().toLowerCase()
  return (revision.value?.parameters ?? []).filter((item) => !needle
    || [item.code, item.name, item.unit, item.standards.map((standard) => standard.code).join(' ')]
      .some((value) => value?.toLowerCase().includes(needle)))
})
const parentOptions = computed(() => flatNodes.value.filter((item) => {
  if (!selectedNode.value) return false
  return selectedNode.value.type === 'CATEGORY' ? item.type === 'DOMAIN'
    : selectedNode.value.type === 'PROJECT' ? item.type === 'CATEGORY' : false
}))
const childType = computed(() => selectedNode.value?.type === 'DOMAIN' ? 'CATEGORY'
  : selectedNode.value?.type === 'CATEGORY' ? 'PROJECT' : '')

const valueTypeOptions = [
  { value: 'UNSPECIFIED', label: '未指定' }, { value: 'NUMBER', label: '数值' },
  { value: 'TEXT', label: '文本' }, { value: 'DATE', label: '日期' },
  { value: 'CHOICE', label: '选择项' }, { value: 'TABLE', label: '表格' },
  { value: 'REPEATING_GROUP', label: '重复组' }, { value: 'ATTACHMENT', label: '附件' },
]
const fieldTypeLabels: Record<string, string> = {
  TEXT: '文本', DECIMAL: '数值', DATE: '日期', SINGLE_SELECT: '单选', MULTI_SELECT: '多选',
  STANDARD_SELECT: '检测依据单选', STANDARD_MULTI: '检测依据多选', NUMBER: '数值', CHOICE: '选择项',
  TABLE: '表格', REPEATING_GROUP: '重复组', ATTACHMENT: '附件',
}
const configurationRoleLabels: Record<string, string> = {
  REPORT_TEMPLATE: '报告模板', COMMISSION_TEMPLATE: '委托单模板', TASK_TEMPLATE: '任务单模板',
  NUMBERING_RULE: '编号规则', TEMPLATE_NUMBERING: '模板编号',
}

function catalogPathKeys(node: CatalogNode): string[] {
  const nodes = new Map(flatNodes.value.map((item) => [item.id, item]))
  const keys: string[] = []
  const visited = new Set<string>()
  let current: CatalogNode | undefined = node
  while (current?.parentId && !visited.has(current.parentId)) {
    visited.add(current.parentId)
    keys.unshift(current.parentId)
    current = nodes.get(current.parentId)
  }
  return keys
}

function syncTreeHeight() {
  treeHeight.value = Math.max(180, Math.floor(treeScrollEl.value?.clientHeight ?? 320))
}

const revisionForm = reactive({ conclusion: '', defaultNote: '' })
const parameterForm = reactive({
  code: '', name: '', valueType: 'UNSPECIFIED', formula: '', unit: '', sortOrder: 0,
  conclusion: '', defaultNote: '', pricingMethod: '', price: '', sampleName: '',
  promisedDays: undefined as number | undefined, sampleDescription: '', sampleQuantity: '',
  postTestTreatment: '', retentionDays: undefined as number | undefined, retentionQuantity: '',
  retentionNature: '', defaultCommissioned: true, required: false, enabled: true,
  remark: '', standardIds: [] as string[], expectedVersion: 0,
})
const nodeForm = reactive({ parentId: '', type: 'DOMAIN', name: '', symbol: '', sortOrder: 0, expectedVersion: 0 })
const batchForm = reactive({
  applyDefault: false, defaultCommissioned: true, applyRequired: false, required: false,
  applyEnabled: false, enabled: true, applyPricing: false, pricingMethod: '按单个样品计价',
  applyPrice: false, price: '',
})
const groupForm = reactive({ code: '', name: '', sortOrder: 0, status: 'ACTIVE', parameterIds: [] as string[], expectedVersion: 0 })
const markerForm = reactive({ code: '', name: '', status: 'ACTIVE', contentText: '{\n  "label": ""\n}', expectedVersion: 0 })
const sampleFieldForm = reactive({
  code: '', label: '', fieldType: 'TEXT', required: false, unit: '', placeholder: '',
  optionsText: '', coreBinding: 'NONE', sortOrder: 0, standardIds: [] as string[], expectedVersion: 0,
})
const configurationForm = reactive({ role: 'REPORT_TEMPLATE', configurationId: '' })

async function loadTree(keepSelection = true) {
  loadingTree.value = true
  try {
    tree.value = await api<CatalogNode[]>(`/api/detection-catalog/tree?query=${encodeURIComponent(treeQuery.value)}&includeInactive=${includeInactive.value}&includeHistory=${includeHistory.value}`)
    const current = keepSelection && selectedNode.value
      ? flatNodes.value.find((item) => item.id === selectedNode.value?.id) : firstProject(tree.value)
    if (current) {
      expandedKeys.value = catalogPathKeys(current)
      treeRenderKey.value += 1
      await selectNode(current)
    }
    else { selectedNode.value = undefined; revision.value = undefined }
    await nextTick()
    syncTreeHeight()
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { loadingTree.value = false }
}

async function selectNode(node: CatalogNode) {
  selectedNode.value = node
  if (node.type !== 'PROJECT' || !node.projectRevisionId) {
    revision.value = undefined
    selectedParameterId.value = ''
    return
  }
  await loadRevision(node.projectRevisionId)
}

async function loadRevision(id: string, preferredParameterId?: string) {
  loadingRevision.value = true
  try {
    revision.value = await api<ProjectRevision>(`/api/detection-project-revisions/${id}`)
    Object.assign(revisionForm, { conclusion: revision.value.conclusion ?? '', defaultNote: revision.value.defaultNote ?? '' })
    const next = revision.value.parameters.find((item) => item.id === (preferredParameterId ?? selectedParameterId.value))
      ?? revision.value.parameters[0]
    if (next) selectParameter(next)
    else { selectedParameterId.value = ''; creatingParameter.value = false }
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { loadingRevision.value = false }
}

function selectParameter(parameter: DetectionParameter) {
  creatingParameter.value = false
  selectedParameterId.value = parameter.id
  activeEditorTab.value = 'basic'
  const definition = parameter.definition ?? {}
  const rawFields = Array.isArray(definition.fields) ? definition.fields as Array<Record<string, unknown>> : []
  definitionBase.value = Object.fromEntries(Object.entries(definition).filter(([key]) => key !== 'fields'))
  fieldDefinitions.value = rawFields.map((field) => ({
    key: String(field.key ?? ''), label: String(field.label ?? ''), type: String(field.type ?? 'NUMBER'),
    required: Boolean(field.required), unit: String(field.unit ?? ''),
    optionsText: Array.isArray(field.options) ? field.options.join('、') : '',
  }))
  Object.assign(parameterForm, {
    code: parameter.code, name: parameter.name, valueType: parameter.valueType, formula: parameter.formula ?? '',
    unit: parameter.unit ?? '', sortOrder: parameter.sortOrder, conclusion: parameter.conclusion ?? '',
    defaultNote: parameter.defaultNote ?? '', pricingMethod: parameter.pricingMethod ?? '', price: parameter.price ?? '',
    sampleName: parameter.sampleName ?? '', promisedDays: parameter.promisedDays,
    sampleDescription: parameter.sampleDescription ?? '', sampleQuantity: parameter.sampleQuantity ?? '',
    postTestTreatment: parameter.postTestTreatment ?? '', retentionDays: parameter.retentionDays,
    retentionQuantity: parameter.retentionQuantity ?? '', retentionNature: parameter.retentionNature ?? '',
    defaultCommissioned: parameter.defaultCommissioned, required: parameter.required, enabled: parameter.enabled,
    remark: parameter.remark ?? '', standardIds: parameter.standards.map((item) => item.id),
    expectedVersion: parameter.version,
  })
}

function startCreateParameter() {
  if (!canEdit.value) return
  creatingParameter.value = true
  selectedParameterId.value = ''
  activeEditorTab.value = 'basic'
  definitionBase.value = {}
  fieldDefinitions.value = [{ key: 'value', label: '检测值', type: 'NUMBER', required: true, unit: '', optionsText: '' }]
  Object.assign(parameterForm, {
    code: '', name: '', valueType: 'NUMBER', formula: '', unit: '',
    sortOrder: (revision.value?.parameters.length ?? 0) + 1, conclusion: '', defaultNote: '',
    pricingMethod: '按单个样品计价', price: '', sampleName: '', promisedDays: undefined,
    sampleDescription: '', sampleQuantity: '1', postTestTreatment: '', retentionDays: undefined,
    retentionQuantity: '', retentionNature: '', defaultCommissioned: true, required: false,
    enabled: true, remark: '', standardIds: [], expectedVersion: 0,
  })
}

function addField() {
  fieldDefinitions.value.push({ key: '', label: '', type: 'NUMBER', required: false, unit: '', optionsText: '' })
}

function parameterPayload() {
  const fields = fieldDefinitions.value.filter((field) => field.key.trim()).map((field) => ({
    key: field.key.trim(), label: field.label.trim(), type: field.type, required: field.required,
    ...(field.unit.trim() ? { unit: field.unit.trim() } : {}),
    ...(field.optionsText.trim() ? { options: field.optionsText.split(/[、,，]/).map((item) => item.trim()).filter(Boolean) } : {}),
  }))
  const definition = fields.length ? { ...definitionBase.value, fields } : definitionBase.value
  return { ...parameterForm, definition, standardIds: parameterForm.standardIds }
}

async function saveParameter() {
  if (!revision.value || !parameterForm.code.trim() || !parameterForm.name.trim()) return ElMessage.warning('请填写参数代码和名称')
  saving.value = true
  try {
    const path = creatingParameter.value
      ? `/api/detection-project-revisions/${revision.value.id}/parameters`
      : `/api/detection-project-revisions/${revision.value.id}/parameters/${selectedParameterId.value}`
    const saved = await api<DetectionParameter>(path, {
      method: creatingParameter.value ? 'POST' : 'PUT',
      headers: creatingParameter.value ? { 'Idempotency-Key': crypto.randomUUID() } : undefined,
      body: JSON.stringify(parameterPayload()),
    })
    ElMessage.success(creatingParameter.value ? '检测参数已创建' : '检测参数已保存')
    await loadRevision(revision.value.id, saved.id)
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { saving.value = false }
}

async function saveRevision() {
  if (!revision.value) return
  saving.value = true
  try {
    revision.value = await api<ProjectRevision>(`/api/detection-project-revisions/${revision.value.id}`, {
      method: 'PUT', body: JSON.stringify({ ...revisionForm, expectedVersion: revision.value.version }),
    })
    ElMessage.success('项目默认信息已保存')
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { saving.value = false }
}

async function reviseProject() {
  if (!revision.value) return
  try {
    const created = await api<ProjectRevision>(`/api/detection-project-revisions/${revision.value.id}/revisions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
    })
    ElMessage.success(`已创建 R${created.revisionNumber} 修订草稿`)
    await loadTree(false)
    await loadRevision(created.id)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function transitionRevision(action: 'PUBLISH' | 'RETIRE') {
  if (!revision.value) return
  if (action === 'PUBLISH') await ElMessageBox.confirm('发布后该版本不可原地修改，并将替代当前发布版。', '发布检测项目', { type: 'warning' })
  try {
    await api(`/api/detection-project-revisions/${revision.value.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: revision.value.version, reasonCode: 'CONFIGURATION_CONTROL', comment: `${action}检测项目版本` }),
    })
    ElMessage.success(action === 'PUBLISH' ? '检测项目版本已发布' : '检测项目版本已停用')
    await loadTree(false)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

function openNodeEdit() {
  if (!selectedNode.value) return
  nodeCreating.value = false; nodeMoving.value = false
  Object.assign(nodeForm, { parentId: selectedNode.value.parentId ?? '', type: selectedNode.value.type,
    name: selectedNode.value.name, symbol: selectedNode.value.symbol ?? '', sortOrder: selectedNode.value.sortOrder,
    expectedVersion: selectedNode.value.version })
  nodeDialog.value = true
}

function openNodeCreate(type?: string) {
  const nextType = type ?? (selectedNode.value ? childType.value : 'DOMAIN')
  if (!nextType) return ElMessage.info('检测项目下不能继续新增子节点')
  nodeCreating.value = true; nodeMoving.value = false
  Object.assign(nodeForm, { parentId: nextType === 'DOMAIN' ? '' : selectedNode.value?.id ?? '', type: nextType,
    name: '', symbol: '', sortOrder: (selectedNode.value?.children.length ?? flatNodes.value.filter((item) => item.type === 'DOMAIN').length) + 1,
    expectedVersion: 0 })
  nodeDialog.value = true
}

function openNodeMove() {
  if (!selectedNode.value || selectedNode.value.type === 'DOMAIN') return
  nodeCreating.value = false; nodeMoving.value = true
  Object.assign(nodeForm, { parentId: selectedNode.value.parentId ?? '', type: selectedNode.value.type,
    name: selectedNode.value.name, symbol: selectedNode.value.symbol ?? '', sortOrder: selectedNode.value.sortOrder,
    expectedVersion: selectedNode.value.version })
  nodeDialog.value = true
}

async function saveNode() {
  if (!nodeForm.name.trim() && !nodeMoving.value) return ElMessage.warning('请填写节点名称')
  try {
    if (nodeCreating.value) {
      await api('/api/detection-catalog/nodes', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ ...nodeForm, parentId: nodeForm.parentId || null }) })
    } else if (nodeMoving.value && selectedNode.value) {
      await api(`/api/detection-catalog/nodes/${selectedNode.value.id}/move`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ parentId: nodeForm.parentId, sortOrder: nodeForm.sortOrder, expectedVersion: nodeForm.expectedVersion }) })
    } else if (selectedNode.value) {
      await api(`/api/detection-catalog/nodes/${selectedNode.value.id}`, { method: 'PUT', body: JSON.stringify(nodeForm) })
    }
    nodeDialog.value = false
    ElMessage.success(nodeCreating.value ? '目录节点已创建' : nodeMoving.value ? '目录节点已移动' : '目录节点已更新')
    await loadTree(false)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function transitionNode() {
  if (!selectedNode.value) return
  const action = selectedNode.value.status === 'ACTIVE' ? 'DISABLE' : 'ENABLE'
  try {
    await api(`/api/detection-catalog/nodes/${selectedNode.value.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: selectedNode.value.version, reasonCode: 'CATALOG_CONTROL', comment: `${action}目录节点` }),
    })
    ElMessage.success(action === 'ENABLE' ? '节点已启用' : '节点已停用')
    await loadTree(false)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

function expandAll() { expandedKeys.value = flatNodes.value.filter((item) => item.type !== 'PROJECT').map((item) => item.id); treeRenderKey.value += 1 }
function collapseAll() { expandedKeys.value = []; treeRenderKey.value += 1 }
function onParameterSelection(rows: DetectionParameter[]) { selectedBatch.value = rows }

async function applyBatch() {
  if (!revision.value || selectedBatch.value.length === 0) return ElMessage.warning('请先勾选参数')
  try {
    await api(`/api/detection-project-revisions/${revision.value.id}/parameters/batch`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({
        targets: selectedBatch.value.map((item) => ({ id: item.id, expectedVersion: item.version })),
        defaultCommissioned: batchForm.applyDefault ? batchForm.defaultCommissioned : null,
        required: batchForm.applyRequired ? batchForm.required : null,
        enabled: batchForm.applyEnabled ? batchForm.enabled : null,
        pricingMethod: batchForm.applyPricing ? batchForm.pricingMethod : null,
        price: batchForm.applyPrice ? batchForm.price : null,
      }),
    })
    batchDialog.value = false; ElMessage.success(`已更新 ${selectedBatch.value.length} 条参数`)
    await loadRevision(revision.value.id)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

function openGroup(group?: DetectionGroup) {
  editingGroup.value = group
  Object.assign(groupForm, group ? { ...group, expectedVersion: group.version }
    : { code: '', name: '', sortOrder: (revision.value?.groups.length ?? 0) + 1, status: 'ACTIVE', parameterIds: [], expectedVersion: revision.value?.version ?? 0 })
  groupDialog.value = true
}

async function saveGroup() {
  if (!revision.value || !groupForm.code.trim() || !groupForm.name.trim() || !groupForm.parameterIds.length) return ElMessage.warning('请填写组合并选择参数')
  try {
    const path = editingGroup.value
      ? `/api/detection-project-revisions/${revision.value.id}/groups/${editingGroup.value.id}`
      : `/api/detection-project-revisions/${revision.value.id}/groups`
    revision.value = await api<ProjectRevision>(path, { method: editingGroup.value ? 'PUT' : 'POST',
      headers: editingGroup.value ? undefined : { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(groupForm) })
    groupDialog.value = false; ElMessage.success('参数组合已保存')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

function openMarker(marker?: DetectionMarker) {
  editingMarker.value = marker
  Object.assign(markerForm, marker ? { code: marker.code, name: marker.name, status: marker.status,
    contentText: JSON.stringify(marker.content, null, 2), expectedVersion: marker.version }
    : { code: '', name: '', status: 'ACTIVE', contentText: '{\n  "label": ""\n}', expectedVersion: revision.value?.version ?? 0 })
  markerDialog.value = true
}

async function saveMarker() {
  if (!revision.value) return
  let content: Record<string, unknown>
  try { content = JSON.parse(markerForm.contentText) }
  catch { return ElMessage.warning('样本标记内容不是有效 JSON') }
  try {
    const path = editingMarker.value
      ? `/api/detection-project-revisions/${revision.value.id}/markers/${editingMarker.value.id}`
      : `/api/detection-project-revisions/${revision.value.id}/markers`
    revision.value = await api<ProjectRevision>(path, { method: editingMarker.value ? 'PUT' : 'POST',
      headers: editingMarker.value ? undefined : { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ code: markerForm.code, name: markerForm.name, status: markerForm.status, content,
        expectedVersion: markerForm.expectedVersion }) })
    markerDialog.value = false; ElMessage.success('样本标记已保存')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

function openSampleField(field?: DetectionSampleField) {
  editingSampleField.value = field
  Object.assign(sampleFieldForm, field ? {
    code: field.code, label: field.label, fieldType: field.fieldType, required: field.required,
    unit: field.unit ?? '', placeholder: field.placeholder ?? '', optionsText: field.options.join('、'),
    coreBinding: field.coreBinding, sortOrder: field.sortOrder,
    standardIds: field.standards.map((item) => item.id), expectedVersion: field.version,
  } : {
    code: '', label: '', fieldType: 'TEXT', required: false, unit: '', placeholder: '',
    optionsText: '', coreBinding: 'NONE', sortOrder: (revision.value?.sampleFields.length ?? 0) + 1,
    standardIds: [], expectedVersion: revision.value?.version ?? 0,
  })
  sampleFieldDialog.value = true
}

async function saveSampleField() {
  if (!revision.value || !sampleFieldForm.code.trim() || !sampleFieldForm.label.trim()) return ElMessage.warning('请填写样品字段代码和名称')
  const payload = {
    ...sampleFieldForm,
    options: sampleFieldForm.optionsText.split(/[、,，\n]/).map((item) => item.trim()).filter(Boolean),
  }
  try {
    const path = editingSampleField.value
      ? `/api/detection-project-revisions/${revision.value.id}/sample-fields/${editingSampleField.value.id}`
      : `/api/detection-project-revisions/${revision.value.id}/sample-fields`
    revision.value = await api<ProjectRevision>(path, {
      method: editingSampleField.value ? 'PUT' : 'POST',
      headers: editingSampleField.value ? undefined : { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(payload),
    })
    sampleFieldDialog.value = false
    ElMessage.success('委托样品字段已保存')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function loadConfigurationOptions() {
  configurationForm.configurationId = ''
  try {
    configurationOptions.value = (await api<ConfigurationVersion[]>(`/api/configurations?type=${configurationForm.role}`))
      .filter((item) => item.state === 'PUBLISHED')
  } catch (error) { configurationOptions.value = []; ElMessage.error(errorMessage(error)) }
}

async function openConfigurationLink() {
  configurationDialog.value = true
  await loadConfigurationOptions()
}

async function saveConfigurationLink() {
  if (!revision.value || !configurationForm.configurationId) return ElMessage.warning('请选择已发布配置版本')
  try {
    revision.value = await api<ProjectRevision>(`/api/detection-project-revisions/${revision.value.id}/configuration-link`, {
      method: 'PUT', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({
        ...configurationForm, expectedRevisionVersion: revision.value.version,
      }),
    })
    configurationDialog.value = false; ElMessage.success('受控配置已绑定')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

let treeResizeObserver: ResizeObserver | undefined
watch([includeInactive, includeHistory], () => loadTree(false))
onMounted(async () => {
  standards.value = await api<DetectionStandard[]>('/api/detection-standards').catch(() => [])
  await loadTree(false)
  treeResizeObserver = new ResizeObserver(syncTreeHeight)
  if (treeScrollEl.value) treeResizeObserver.observe(treeScrollEl.value)
})
onUnmounted(() => treeResizeObserver?.disconnect())
</script>

<template>
  <section class="page detection-catalog-page">
    <header class="page-heading detection-heading">
      <div><h1>检测参数</h1><p>维护检测目录、参数、样品要求、检测依据和模板绑定。</p></div>
      <div class="heading-actions">
        <el-button :icon="Refresh" @click="loadTree(false)">刷新</el-button>
        <el-button v-if="canWrite" :icon="Plus" @click="openNodeCreate('DOMAIN')">新增领域</el-button>
        <el-button v-if="revision?.status === 'PUBLISHED' && canWrite" type="primary" :icon="CopyDocument" @click="reviseProject">创建修订</el-button>
        <el-button v-if="revision?.status === 'DRAFT' && canWrite" type="primary" @click="transitionRevision('PUBLISH')">发布版本</el-button>
      </div>
    </header>

    <section class="catalog-metrics" aria-label="检测配置概览">
      <div><span>当前树节点</span><strong>{{ flatNodes.length }}</strong></div>
      <div><span>检测项目</span><strong>{{ projectCount }}</strong></div>
      <div><span>检测参数</span><strong>{{ parameterTotal }}</strong></div>
      <div><span>标准库</span><strong>{{ standards.length }}</strong></div>
    </section>

    <section class="catalog-workbench">
      <aside class="catalog-tree-pane" v-loading="loadingTree">
        <header><div><strong>检测目录</strong><span>领域 / 分类 / 项目</span></div></header>
        <div class="tree-tools">
          <el-input v-model="treeQuery" clearable :prefix-icon="Search" placeholder="搜索项目或分类" @keyup.enter="loadTree(false)" @clear="loadTree(false)" />
          <el-button type="primary" :icon="Search" circle aria-label="搜索" @click="loadTree(false)" />
        </div>
        <div class="tree-options"><el-switch v-model="includeHistory" size="small" active-text="历史节点" /><el-switch v-model="includeInactive" size="small" active-text="已停用" /></div>
        <div class="tree-actions"><el-button text :icon="Expand" @click="expandAll">全部展开</el-button><el-button text :icon="Fold" @click="collapseAll">全部收起</el-button></div>
        <div ref="treeScrollEl" class="catalog-tree-scroll">
          <el-tree-v2 :key="treeRenderKey" :data="tree" :props="treeProps" :height="treeHeight" :item-size="38"
                      :default-expanded-keys="expandedKeys" :current-node-key="selectedNode?.id" highlight-current
                      @node-click="selectNode">
            <template #default="{ data }">
              <div class="catalog-tree-node" :class="`type-${data.type.toLowerCase()}`" :title="data.path">
                <span class="node-kind">{{ data.type === 'DOMAIN' ? '域' : data.type === 'CATEGORY' ? '类' : '项' }}</span>
                <span class="node-name">{{ data.name }}</span>
                <el-tag v-if="data.legacy" size="small" type="info" effect="plain">历史</el-tag>
                <span v-if="data.type === 'PROJECT'" class="node-count">{{ data.parameterCount }}</span>
                <i v-if="data.status === 'INACTIVE'" class="node-state">停</i>
              </div>
            </template>
          </el-tree-v2>
        </div>
        <footer v-if="selectedNode">
          <el-dropdown trigger="click">
            <el-button :icon="Setting">节点操作<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
            <template #dropdown><el-dropdown-menu>
              <el-dropdown-item :icon="Edit" @click="openNodeEdit">修改节点</el-dropdown-item>
              <el-dropdown-item v-if="childType" :icon="CirclePlus" @click="openNodeCreate()">新增下级</el-dropdown-item>
              <el-dropdown-item v-if="selectedNode.type !== 'DOMAIN'" :icon="Sort" @click="openNodeMove">移动 / 排序</el-dropdown-item>
              <el-dropdown-item v-if="selectedNode.status !== 'HISTORICAL'" divided @click="transitionNode">{{ selectedNode.status === 'ACTIVE' ? '停用节点' : '启用节点' }}</el-dropdown-item>
            </el-dropdown-menu></template>
          </el-dropdown>
          <el-button text @click="router.push('/modules/numbering-flow-rules')">编号规则</el-button>
        </footer>
      </aside>

      <section class="parameter-list-pane" v-loading="loadingRevision">
        <header>
          <div><strong>{{ revision?.projectName ?? '请选择检测项目' }}</strong><span>{{ revision?.path ?? '从左侧目录选择项目' }}</span></div>
          <div v-if="revision" class="revision-badges"><el-tag size="small" :type="revision.status === 'PUBLISHED' ? 'success' : revision.status === 'DRAFT' ? 'warning' : 'info'">R{{ revision.revisionNumber }} · {{ statusLabel(revision.status) }}</el-tag></div>
        </header>
        <template v-if="revision">
          <div class="parameter-list-tools">
            <el-input v-model="parameterQuery" clearable :prefix-icon="Search" placeholder="参数名称、代码、单位或标准" />
            <el-button v-if="canEdit" :icon="Plus" type="primary" @click="startCreateParameter">新增参数</el-button>
          </div>
          <el-table :data="visibleParameters" row-key="id" height="100%" class="parameter-master-table" highlight-current-row
                    @selection-change="onParameterSelection" @current-change="selectParameter">
            <el-table-column v-if="canEdit" type="selection" width="40" />
            <el-table-column prop="name" label="检测参数" min-width="170" show-overflow-tooltip>
              <template #default="scope"><div class="parameter-name-cell"><strong>{{ scope.row.name }}</strong><span>{{ scope.row.code }}</span></div></template>
            </el-table-column>
            <el-table-column label="属性" width="110"><template #default="scope"><div class="parameter-flags"><el-tag v-if="scope.row.required" size="small" type="danger">必选</el-tag><el-tag v-if="scope.row.defaultCommissioned" size="small" type="primary" effect="plain">默认</el-tag><el-tag v-if="!scope.row.enabled" size="small" type="info">停用</el-tag></div></template></el-table-column>
            <el-table-column prop="unit" label="单位" width="70" />
            <el-table-column prop="versionNumber" label="版本" width="60" />
          </el-table>
          <footer><span>显示 {{ visibleParameters.length }} / {{ revision.parameters.length }} 条</span><el-button v-if="canEdit" :disabled="selectedBatch.length === 0" @click="batchDialog = true">批量设置（{{ selectedBatch.length }}）</el-button></footer>
        </template>
        <el-empty v-else description="选择左侧检测项目后查看完整参数" />
      </section>

      <section class="parameter-editor-pane" v-loading="saving">
        <header>
          <div><strong>{{ creatingParameter ? '新增检测参数' : selectedParameter?.name ?? '参数详情' }}</strong><span v-if="selectedParameter">{{ selectedParameter.code }} · V{{ selectedParameter.versionNumber }}</span></div>
          <el-tag v-if="revision" :type="canEdit ? 'warning' : 'success'" effect="plain">{{ canEdit ? '草稿可编辑' : '发布版只读' }}</el-tag>
        </header>
        <template v-if="selectedParameter || creatingParameter">
          <el-tabs v-model="activeEditorTab" class="parameter-editor-tabs">
            <el-tab-pane label="基本信息" name="basic">
              <el-form label-position="top" :disabled="!canEdit">
                <div class="form-grid"><el-form-item label="参数代码" required><el-input v-model="parameterForm.code" :disabled="!creatingParameter" /></el-form-item><el-form-item label="参数名称" required><el-input v-model="parameterForm.name" /></el-form-item></div>
                <div class="form-grid"><el-form-item label="单位"><el-input v-model="parameterForm.unit" /></el-form-item><el-form-item label="排序号"><el-input-number v-model="parameterForm.sortOrder" :min="0" /></el-form-item></div>
                <el-form-item label="检验结论"><el-input v-model="parameterForm.conclusion" type="textarea" :rows="3" /></el-form-item>
                <el-form-item label="默认备注"><el-input v-model="parameterForm.defaultNote" type="textarea" :rows="3" /></el-form-item>
                <div class="switch-row"><el-switch v-model="parameterForm.defaultCommissioned" active-text="默认委托" /><el-switch v-model="parameterForm.required" active-text="必选参数" /><el-switch v-model="parameterForm.enabled" active-text="启用" /></div>
                <el-form-item label="备注"><el-input v-model="parameterForm.remark" type="textarea" :rows="3" /></el-form-item>
              </el-form>
            </el-tab-pane>
            <el-tab-pane label="检测依据" name="standards">
              <el-form label-position="top" :disabled="!canEdit"><el-form-item label="可用检测依据"><el-select v-model="parameterForm.standardIds" multiple filterable collapse-tags :max-collapse-tags="4" placeholder="选择标准"><el-option v-for="item in standards" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item></el-form>
              <div class="standard-list"><div v-for="item in selectedParameter?.standards" :key="item.id"><strong>{{ item.code }}</strong><span>{{ item.name }}</span></div><el-empty v-if="!selectedParameter?.standards.length" description="暂无检测依据" :image-size="48" /></div>
            </el-tab-pane>
            <el-tab-pane label="样品时限" name="sample">
              <el-form label-position="top" :disabled="!canEdit"><div class="form-grid"><el-form-item label="样品名称"><el-input v-model="parameterForm.sampleName" /></el-form-item><el-form-item label="承诺天数"><el-input-number v-model="parameterForm.promisedDays" :min="0" /></el-form-item></div><el-form-item label="样品描述"><el-input v-model="parameterForm.sampleDescription" type="textarea" :rows="3" /></el-form-item><div class="form-grid"><el-form-item label="样品数量"><el-input v-model="parameterForm.sampleQuantity" /></el-form-item><el-form-item label="计量单位"><el-input v-model="parameterForm.unit" /></el-form-item></div><el-form-item label="检后处理"><el-input v-model="parameterForm.postTestTreatment" /></el-form-item><div class="form-grid"><el-form-item label="留样天数"><el-input-number v-model="parameterForm.retentionDays" :min="0" /></el-form-item><el-form-item label="留样数量"><el-input v-model="parameterForm.retentionQuantity" /></el-form-item></div><el-form-item label="留样性质"><el-input v-model="parameterForm.retentionNature" /></el-form-item></el-form>
              <el-divider content-position="left">委托样品字段</el-divider>
              <div class="field-definition-header"><div><strong>委托样品字段</strong><span>委托选择本项目时生成，受理后随项目版本保存</span></div><el-button v-if="canEdit" text :icon="Plus" @click="openSampleField()">新增字段</el-button></div>
              <div class="compact-list sample-schema-list">
                <button v-for="item in revision?.sampleFields" :key="item.id" type="button" @click="canEdit && openSampleField(item)">
                  <strong>{{ item.label }}<em v-if="item.required">必填</em></strong>
                  <span>{{ item.code }} · {{ fieldTypeLabels[item.fieldType] }}<template v-if="item.unit"> · {{ item.unit }}</template></span>
                </button>
                <el-empty v-if="!revision?.sampleFields.length" description="当前项目未配置委托样品字段" :image-size="48" />
              </div>
              <el-divider content-position="left">龄期报告选项</el-divider>
              <div class="configuration-cards"><div v-for="item in revision?.ageReportOptions" :key="item.value"><span>{{ item.defaultSelected ? '默认选择' : '可选' }}</span><strong>{{ item.label }}</strong></div><el-empty v-if="!revision?.ageReportOptions.length" description="当前项目无龄期报告选项" :image-size="48" /></div>
            </el-tab-pane>
            <el-tab-pane label="价格模板" name="pricing">
              <el-form label-position="top" :disabled="!canEdit"><div class="form-grid"><el-form-item label="计价方式"><el-select v-model="parameterForm.pricingMethod" allow-create filterable><el-option label="按单个样品计价" value="按单个样品计价" /><el-option label="按检测参数计价" value="按检测参数计价" /><el-option label="按委托计价" value="按委托计价" /></el-select></el-form-item><el-form-item label="价格（元）"><el-input v-model="parameterForm.price" /></el-form-item></div></el-form>
              <el-divider content-position="left">项目受控配置</el-divider>
              <div class="configuration-cards"><div v-for="item in revision?.configurations" :key="`${item.role}-${item.id}`"><span>{{ configurationRoleLabels[item.role] ?? item.role }}</span><strong>{{ item.name }} · V{{ item.versionNumber }}</strong></div><el-empty v-if="!revision?.configurations.length" description="当前项目未绑定模板" :image-size="48" /></div>
              <div class="section-action"><el-button v-if="canEdit && auth.user?.permissions.includes('SYSTEM_ADMIN')" :icon="Link" @click="openConfigurationLink">绑定已发布配置</el-button></div>
              <el-divider content-position="left">参数组合</el-divider>
              <div class="compact-list"><button v-for="item in revision?.groups" :key="item.id" type="button" @click="canEdit && openGroup(item)"><strong>{{ item.name }}</strong><span>{{ item.parameterIds.length }} 项 · {{ statusLabel(item.status) }}</span></button><el-button v-if="canEdit" text :icon="Plus" @click="openGroup()">新增参数组合</el-button></div>
              <el-divider content-position="left">样本标记</el-divider>
              <div class="compact-list"><button v-for="item in revision?.markers" :key="item.id" type="button" @click="canEdit && openMarker(item)"><strong>{{ item.name }}</strong><span>{{ item.code }} · {{ statusLabel(item.status) }}</span></button><el-button v-if="canEdit" text :icon="Plus" @click="openMarker()">新增样本标记</el-button></div>
            </el-tab-pane>
            <el-tab-pane label="字段公式" name="fields">
              <el-form label-position="top" :disabled="!canEdit"><el-form-item label="参数值类型"><el-select v-model="parameterForm.valueType"><el-option v-for="item in valueTypeOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item><el-form-item label="计算公式"><el-input v-model="parameterForm.formula" type="textarea" :rows="3" placeholder="例如：荷载 / 面积" /></el-form-item></el-form>
              <div class="field-definition-header"><strong>结构化字段定义</strong><el-button v-if="canEdit" text :icon="Plus" @click="addField">添加字段</el-button></div>
              <div class="field-definition-list"><div v-for="(field, index) in fieldDefinitions" :key="index" class="field-definition-row"><el-input v-model="field.key" placeholder="字段键" :disabled="!canEdit" /><el-input v-model="field.label" placeholder="显示名称" :disabled="!canEdit" /><el-select v-model="field.type" :disabled="!canEdit"><el-option v-for="item in valueTypeOptions.filter((option) => option.value !== 'UNSPECIFIED')" :key="item.value" :label="item.label" :value="item.value" /></el-select><el-input v-model="field.unit" placeholder="单位" :disabled="!canEdit" /><el-input v-if="field.type === 'CHOICE'" v-model="field.optionsText" placeholder="选项，以、分隔" :disabled="!canEdit" /><el-checkbox v-model="field.required" :disabled="!canEdit">必填</el-checkbox><el-button v-if="canEdit" link type="danger" @click="fieldDefinitions.splice(index, 1)">移除</el-button></div><el-empty v-if="!fieldDefinitions.length" description="当前参数未配置结构化字段" :image-size="48" /></div>
            </el-tab-pane>
            <el-tab-pane label="版本审计" name="version">
              <el-descriptions :column="1" border><el-descriptions-item label="项目路径">{{ revision?.path }}</el-descriptions-item><el-descriptions-item label="项目版本">R{{ revision?.revisionNumber }} / {{ statusLabel(revision?.status) }}</el-descriptions-item><el-descriptions-item label="参数版本">V{{ selectedParameter?.versionNumber ?? '新建' }}</el-descriptions-item><el-descriptions-item label="来源系统">{{ sourceLabel(selectedParameter?.sourceSystem ?? 'lims') }}</el-descriptions-item><el-descriptions-item label="来源标识">{{ selectedParameter?.sourceKey ?? '保存后生成' }}</el-descriptions-item><el-descriptions-item label="采集时间">{{ revision?.sourceCapturedAt ?? '本系统创建' }}</el-descriptions-item><el-descriptions-item label="清单校验"><code>{{ revision?.sourceManifestSha256 ?? '本系统创建，无迁移清单' }}</code></el-descriptions-item></el-descriptions>
              <div class="section-action"><el-button :icon="Tickets" @click="router.push('/modules/audit')">查看操作日志</el-button><el-button v-if="revision?.status === 'PUBLISHED' && canWrite" type="danger" plain @click="transitionRevision('RETIRE')">停用当前版本</el-button></div>
            </el-tab-pane>
          </el-tabs>
          <footer><span>{{ canEdit ? '保存只更新草稿；发布后形成不可覆盖版本。' : '发布版不可原地编辑，请创建修订。' }}</span><el-button v-if="canEdit" type="primary" :loading="saving" @click="saveParameter">{{ creatingParameter ? '创建参数' : '保存参数' }}</el-button></footer>
        </template>
        <el-empty v-else description="从中间列表选择参数" />
      </section>
    </section>

    <el-dialog v-model="nodeDialog" :title="nodeCreating ? '新增目录节点' : nodeMoving ? '移动 / 排序节点' : '修改目录节点'" width="520px"><el-form label-position="top"><el-form-item v-if="nodeCreating" label="节点类型"><el-select v-model="nodeForm.type" disabled><el-option label="领域" value="DOMAIN" /><el-option label="分类" value="CATEGORY" /><el-option label="检测项目" value="PROJECT" /></el-select></el-form-item><el-form-item v-if="nodeMoving" label="新上级节点" required><el-select v-model="nodeForm.parentId" filterable><el-option v-for="item in parentOptions" :key="item.id" :label="item.path" :value="item.id" /></el-select></el-form-item><template v-if="!nodeMoving"><el-form-item label="节点名称" required><el-input v-model="nodeForm.name" /></el-form-item><el-form-item label="符号 / 简码"><el-input v-model="nodeForm.symbol" /></el-form-item></template><el-form-item label="排序号"><el-input-number v-model="nodeForm.sortOrder" :min="0" /></el-form-item></el-form><template #footer><el-button @click="nodeDialog = false">取消</el-button><el-button type="primary" @click="saveNode">保存</el-button></template></el-dialog>

    <el-dialog v-model="batchDialog" title="批量设置检测参数" width="620px"><el-alert title="只应用已勾选的字段；每条参数都执行乐观锁校验，任一冲突则整批回滚。" type="info" :closable="false" /><div class="batch-grid"><el-checkbox v-model="batchForm.applyDefault">修改默认委托</el-checkbox><el-switch v-model="batchForm.defaultCommissioned" :disabled="!batchForm.applyDefault" /><el-checkbox v-model="batchForm.applyRequired">修改必选状态</el-checkbox><el-switch v-model="batchForm.required" :disabled="!batchForm.applyRequired" /><el-checkbox v-model="batchForm.applyEnabled">修改启停状态</el-checkbox><el-switch v-model="batchForm.enabled" :disabled="!batchForm.applyEnabled" /><el-checkbox v-model="batchForm.applyPricing">修改计价方式</el-checkbox><el-select v-model="batchForm.pricingMethod" :disabled="!batchForm.applyPricing"><el-option label="按单个样品计价" value="按单个样品计价" /><el-option label="按检测参数计价" value="按检测参数计价" /><el-option label="按委托计价" value="按委托计价" /></el-select><el-checkbox v-model="batchForm.applyPrice">修改价格</el-checkbox><el-input v-model="batchForm.price" :disabled="!batchForm.applyPrice" placeholder="留空表示清空价格" /></div><template #footer><el-button @click="batchDialog = false">取消</el-button><el-button type="primary" @click="applyBatch">应用到 {{ selectedBatch.length }} 条</el-button></template></el-dialog>

    <el-dialog v-model="groupDialog" :title="editingGroup ? '编辑参数组合' : '新增参数组合'" width="620px"><el-form label-position="top"><div class="form-grid"><el-form-item label="组合代码" required><el-input v-model="groupForm.code" /></el-form-item><el-form-item label="组合名称" required><el-input v-model="groupForm.name" /></el-form-item></div><div class="form-grid"><el-form-item label="排序"><el-input-number v-model="groupForm.sortOrder" :min="0" /></el-form-item><el-form-item label="状态"><el-select v-model="groupForm.status"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="INACTIVE" /></el-select></el-form-item></div><el-form-item label="包含参数" required><el-select v-model="groupForm.parameterIds" multiple filterable><el-option v-for="item in revision?.parameters" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item></el-form><template #footer><el-button @click="groupDialog = false">取消</el-button><el-button type="primary" @click="saveGroup">保存</el-button></template></el-dialog>

    <el-dialog v-model="markerDialog" :title="editingMarker ? '编辑样本标记' : '新增样本标记'" width="620px"><el-form label-position="top"><div class="form-grid"><el-form-item label="标记代码" required><el-input v-model="markerForm.code" /></el-form-item><el-form-item label="标记名称" required><el-input v-model="markerForm.name" /></el-form-item></div><el-form-item label="状态"><el-select v-model="markerForm.status"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="INACTIVE" /></el-select></el-form-item><el-form-item label="受控内容 JSON"><el-input v-model="markerForm.contentText" type="textarea" :rows="9" /></el-form-item></el-form><template #footer><el-button @click="markerDialog = false">取消</el-button><el-button type="primary" @click="saveMarker">保存</el-button></template></el-dialog>

    <el-dialog v-model="sampleFieldDialog" :title="editingSampleField ? '编辑委托样品字段' : '新增委托样品字段'" width="680px">
      <el-alert title="字段属于检测项目版本；发布后不可原地修改。委托受理时冻结字段和值。" type="info" :closable="false" />
      <el-form label-position="top" class="sample-field-dialog-form">
        <div class="form-grid"><el-form-item label="字段代码" required><el-input v-model="sampleFieldForm.code" :disabled="Boolean(editingSampleField)" placeholder="小写字母、数字、下划线" /></el-form-item><el-form-item label="显示名称" required><el-input v-model="sampleFieldForm.label" /></el-form-item></div>
        <div class="form-grid"><el-form-item label="字段类型" required><el-select v-model="sampleFieldForm.fieldType"><el-option v-for="item in ['TEXT','DECIMAL','DATE','SINGLE_SELECT','MULTI_SELECT','STANDARD_SELECT','STANDARD_MULTI']" :key="item" :label="fieldTypeLabels[item]" :value="item" /></el-select></el-form-item><el-form-item label="核心字段绑定"><el-select v-model="sampleFieldForm.coreBinding"><el-option label="无" value="NONE" /><el-option label="样品名称" value="NAME" /><el-option label="样品描述" value="DESCRIPTION" /><el-option label="样品数量" value="QUANTITY" /><el-option label="计量单位" value="UNIT" /></el-select></el-form-item></div>
        <div class="form-grid"><el-form-item label="单位"><el-input v-model="sampleFieldForm.unit" /></el-form-item><el-form-item label="排序号"><el-input-number v-model="sampleFieldForm.sortOrder" :min="0" /></el-form-item></div>
        <el-form-item label="输入提示"><el-input v-model="sampleFieldForm.placeholder" /></el-form-item>
        <el-form-item v-if="['SINGLE_SELECT','MULTI_SELECT'].includes(sampleFieldForm.fieldType)" label="受控选项" required><el-input v-model="sampleFieldForm.optionsText" type="textarea" :rows="4" placeholder="使用顿号、逗号或换行分隔" /></el-form-item>
        <el-form-item v-if="['STANDARD_SELECT','STANDARD_MULTI'].includes(sampleFieldForm.fieldType)" label="可选检测依据" required><el-select v-model="sampleFieldForm.standardIds" multiple filterable collapse-tags><el-option v-for="item in standards" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item>
        <el-checkbox v-model="sampleFieldForm.required">委托样品必填</el-checkbox>
      </el-form>
      <template #footer><el-button @click="sampleFieldDialog = false">取消</el-button><el-button type="primary" @click="saveSampleField">保存字段</el-button></template>
    </el-dialog>

    <el-dialog v-model="configurationDialog" title="绑定受控配置版本" width="560px"><el-form label-position="top"><el-form-item label="配置角色"><el-select v-model="configurationForm.role" @change="loadConfigurationOptions"><el-option label="报告模板" value="REPORT_TEMPLATE" /><el-option label="委托单模板" value="COMMISSION_TEMPLATE" /><el-option label="任务单模板" value="TASK_TEMPLATE" /><el-option label="编号规则" value="NUMBERING_RULE" /><el-option label="模板编号" value="TEMPLATE_NUMBERING" /></el-select></el-form-item><el-form-item label="已发布版本" required><el-select v-model="configurationForm.configurationId" filterable><el-option v-for="item in configurationOptions" :key="item.id" :label="`${item.code} · ${item.name} · V${item.versionNumber}`" :value="item.id" /></el-select></el-form-item><el-alert v-if="configurationOptions.length === 0" title="当前类型没有已发布配置，请先到系统设置创建并发布版本。" type="warning" :closable="false" /></el-form><template #footer><el-button @click="configurationDialog = false">取消</el-button><el-button type="primary" :disabled="!configurationForm.configurationId" @click="saveConfigurationLink">绑定</el-button></template></el-dialog>
  </section>
</template>
