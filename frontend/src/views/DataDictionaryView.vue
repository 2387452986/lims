<script setup lang="ts">
import {
  Collection, Delete, DocumentCopy, Edit, Plus, Refresh, Search,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { api, errorMessage } from '../api'

type DictionaryNode = {
  id: string
  parentId?: string
  code: string
  sortOrder: number
  revisionId: string
  revisionNumber: number
  name: string
  allowsChildren: boolean
  state: 'DRAFT' | 'PUBLISHED' | 'RETIRED'
  version: number
  valueCount: number
  childCount: number
  sourceSystem?: string
  sourceRecordId?: string
  sourceCapturedAt?: string
  allowedActions: string[]
  children: DictionaryNode[]
}
type DictionaryValue = { id: string; sortOrder: number; value: string; remark: string }
type DictionaryDetail = Omit<DictionaryNode, 'children' | 'valueCount' | 'childCount'> & {
  sourcePath?: string
  contentSha256?: string
  values: DictionaryValue[]
}
type TreeResponse = { items: DictionaryNode[]; nodeCount: number; valueCount: number }

const loading = ref(false)
const saving = ref(false)
const tree = ref<DictionaryNode[]>([])
const nodeCount = ref(0)
const valueCount = ref(0)
const selectedSeriesId = ref('')
const detail = ref<DictionaryDetail>()
const query = ref('')
const includeRetired = ref(false)
const metadataDrawer = ref(false)
const creatingParent = ref<DictionaryNode>()
const editingDraft = ref(false)
const valueDialog = ref(false)
const editingValueId = ref('')
const selectedValues = ref<DictionaryValue[]>([])
const form = reactive({ name: '', allowsChildren: false })
const valueForm = reactive({ sortOrder: 1, value: '', remark: '' })

const flatNodes = computed(() => {
  const result: DictionaryNode[] = []
  const visit = (nodes: DictionaryNode[]) => nodes.forEach((node) => { result.push(node); visit(node.children) })
  visit(tree.value)
  return result
})
const selectedNode = computed(() => flatNodes.value.find((node) => node.id === selectedSeriesId.value))
const expandedKeys = computed(() => selectedNode.value ? [selectedNode.value.parentId ?? selectedNode.value.id] : [])
const canEditValues = computed(() => detail.value?.state === 'DRAFT' && !detail.value.allowsChildren)

const stateLabels: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', RETIRED: '已停用' }
function stateType(state?: string) { return state === 'PUBLISHED' ? 'success' : state === 'RETIRED' ? 'info' : 'warning' }
function showError(error: unknown) {
  ElMessage.error(errorMessage(error))
}
async function load(keepSelection = true) {
  loading.value = true
  try {
    const params = new URLSearchParams({ q: query.value.trim(), includeRetired: String(includeRetired.value) })
    const response = await api<TreeResponse>(`/api/data-dictionaries/tree?${params}`)
    tree.value = response.items; nodeCount.value = response.nodeCount; valueCount.value = response.valueCount
    const current = keepSelection ? flatNodes.value.find((node) => node.id === selectedSeriesId.value) : undefined
    const next = current ?? flatNodes.value[0]
    selectedSeriesId.value = next?.id ?? ''
    if (next) await loadDetail(next.revisionId)
    else detail.value = undefined
  } catch (error) { showError(error) }
  finally { loading.value = false }
}
async function loadDetail(revisionId: string) {
  try { detail.value = await api<DictionaryDetail>(`/api/data-dictionary-revisions/${revisionId}`); selectedValues.value = [] }
  catch (error) { showError(error) }
}
async function selectNode(node: DictionaryNode) {
  selectedSeriesId.value = node.id
  await loadDetail(node.revisionId)
}
function search() { void load(false) }
function reset() { query.value = ''; includeRetired.value = false; void load(false) }

function openCreate(parent?: DictionaryNode) {
  creatingParent.value = parent
  editingDraft.value = false
  Object.assign(form, { name: '', allowsChildren: false })
  metadataDrawer.value = true
}
function openMetadata() {
  if (!detail.value || detail.value.state !== 'DRAFT') return
  creatingParent.value = undefined
  editingDraft.value = true
  Object.assign(form, { name: detail.value.name, allowsChildren: detail.value.allowsChildren })
  metadataDrawer.value = true
}
async function saveMetadata() {
  if (!form.name.trim()) return ElMessage.warning('请填写字典名称')
  saving.value = true
  try {
    if (editingDraft.value && detail.value) {
      const updated = await updateDraft(form.name, form.allowsChildren, detail.value.values)
      selectedSeriesId.value = updated.id
      detail.value = updated
      ElMessage.success('字典草稿已更新')
    } else {
      const created = await api<DictionaryDetail>('/api/data-dictionaries', {
        method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ parentId: creatingParent.value?.id ?? null, name: form.name.trim(),
          allowsChildren: creatingParent.value ? false : form.allowsChildren }),
      })
      selectedSeriesId.value = created.id; detail.value = created
      ElMessage.success(creatingParent.value ? '子级字典草稿已创建' : '一级字典草稿已创建')
    }
    metadataDrawer.value = false
    await load()
  } catch (error) { showError(error) }
  finally { saving.value = false }
}

async function updateDraft(name: string, allowsChildren: boolean, values: Array<Pick<DictionaryValue, 'sortOrder' | 'value' | 'remark'>>) {
  if (!detail.value) throw new Error('未选择字典')
  return api<DictionaryDetail>(`/api/data-dictionary-revisions/${detail.value.revisionId}`, {
    method: 'PUT', headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ name: name.trim(), allowsChildren,
      values: values.map((item) => ({ sortOrder: item.sortOrder, value: item.value, remark: item.remark ?? '' })),
      expectedVersion: detail.value.version }),
  })
}

async function revise() {
  if (!detail.value) return
  saving.value = true
  try {
    const draft = await api<DictionaryDetail>(`/api/data-dictionaries/${detail.value.id}/revisions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
    })
    selectedSeriesId.value = draft.id; detail.value = draft
    await load()
    ElMessage.success('新修订草稿已创建，可安全修改名称和值项')
  } catch (error) { showError(error) }
  finally { saving.value = false }
}
async function transition(action: 'PUBLISH' | 'RETIRE') {
  if (!detail.value) return
  try {
    let comment = '发布数据字典版本'
    if (action === 'RETIRE') {
      const result = await ElMessageBox.prompt('停用后新业务不再采用该字典。请输入停用原因。',
        `停用字典 · ${detail.value.name}`, { inputPlaceholder: '说明替代配置或停用依据',
          inputValidator: (value) => Boolean(value.trim()) || '必须填写停用原因', type: 'warning' })
      comment = result.value.trim()
    } else {
      await ElMessageBox.confirm('发布后内容不可原地修改，后续调整必须创建新修订。确认发布？',
        `发布 R${detail.value.revisionNumber}`, { type: 'warning' })
    }
    saving.value = true
    await api(`/api/data-dictionary-revisions/${detail.value.revisionId}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: detail.value.version,
        reasonCode: action === 'RETIRE' ? 'DICTIONARY_RETIRE' : 'DICTIONARY_PUBLISH', comment }),
    })
    ElMessage.success(action === 'PUBLISH' ? '字典版本已发布' : '字典已受控停用')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showError(error)
  } finally { saving.value = false }
}

function openValue(row?: DictionaryValue) {
  if (!canEditValues.value) return ElMessage.warning('请先创建草稿修订后维护值项')
  editingValueId.value = row?.id ?? ''
  Object.assign(valueForm, { sortOrder: row?.sortOrder ?? (detail.value?.values.length ?? 0) + 1,
    value: row?.value ?? '', remark: row?.remark ?? '' })
  valueDialog.value = true
}
async function saveValue() {
  if (!detail.value || !valueForm.value.trim()) return ElMessage.warning('请填写数据字典值')
  const values = detail.value.values.map((item) => ({ ...item }))
  const index = values.findIndex((item) => item.id === editingValueId.value)
  const next = { id: editingValueId.value, sortOrder: valueForm.sortOrder, value: valueForm.value.trim(),
    remark: valueForm.remark.trim() }
  if (index >= 0) values.splice(index, 1, next)
  else values.push(next)
  saving.value = true
  try {
    detail.value = await updateDraft(detail.value.name, detail.value.allowsChildren, values)
    valueDialog.value = false
    await load()
    ElMessage.success(index >= 0 ? '字典值已修改' : '字典值已添加')
  } catch (error) { showError(error) }
  finally { saving.value = false }
}
async function deleteValues(rows: DictionaryValue[]) {
  if (!detail.value || !rows.length) return
  try {
    await ElMessageBox.confirm(`确认从当前草稿移除 ${rows.length} 个值项？发布版本不会被修改。`, '删除草稿值项', { type: 'warning' })
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showError(error)
    return
  }
  const ids = new Set(rows.map((item) => item.id))
  saving.value = true
  try {
    detail.value = await updateDraft(detail.value.name, detail.value.allowsChildren,
      detail.value.values.filter((item) => !ids.has(item.id)))
    selectedValues.value = []
    await load()
    ElMessage.success('所选值项已从草稿移除')
  } catch (error) { showError(error) }
  finally { saving.value = false }
}

onMounted(() => load(false))
</script>

<template>
  <section class="page dictionary-page">
    <header class="page-heading">
      <div><h1>数据字典</h1><p class="page-description">维护委托、样品、试验和报告共用选项。</p></div>
      <el-button type="primary" :icon="Plus" @click="openCreate()">新增一级字典</el-button>
    </header>

    <section v-loading="loading" class="dictionary-workspace">
      <aside class="dictionary-tree-panel">
        <header><div><strong>字典目录</strong><span>{{ nodeCount }} 个节点 · {{ valueCount }} 个值项</span></div><el-button text :icon="Refresh" aria-label="刷新字典" @click="load()" /></header>
        <div class="dictionary-search"><el-input v-model="query" clearable :prefix-icon="Search" placeholder="编号、名称或值项" @keyup.enter="search" /><el-button @click="search">查询</el-button></div>
        <div class="dictionary-tree-options"><el-switch v-model="includeRetired" inline-prompt active-text="含停" inactive-text="现行" @change="load(false)" /><el-button text @click="reset">重置条件</el-button></div>
        <div class="dictionary-tree-scroll">
          <el-tree :data="tree" node-key="id" :default-expanded-keys="expandedKeys" highlight-current :current-node-key="selectedSeriesId"
            :expand-on-click-node="false" :props="{ label:'name',children:'children' }" @node-click="selectNode">
            <template #default="{ data }">
              <div class="dictionary-tree-node" :class="{ retired: data.state === 'RETIRED' }">
                <span class="dictionary-node-icon"><el-icon><Collection /></el-icon></span>
                <span class="dictionary-node-copy"><strong>{{ data.name }}</strong><small>{{ data.code }} · {{ data.allowsChildren ? `${data.childCount} 个子级` : `${data.valueCount} 个值` }}</small></span>
                <el-tag v-if="data.state !== 'PUBLISHED'" size="small" :type="stateType(data.state)" effect="plain">{{ stateLabels[data.state] }}</el-tag>
              </div>
            </template>
          </el-tree>
          <el-empty v-if="!tree.length" :image-size="64" description="没有匹配的数据字典" />
        </div>
      </aside>

      <main v-if="detail && selectedNode" class="dictionary-detail-panel">
        <header class="dictionary-detail-heading">
          <div><p>{{ detail.parentId ? '二级字典' : detail.allowsChildren ? '目录型字典' : '值项型字典' }}</p><h2>{{ detail.name }} <code>{{ detail.code }}</code></h2><span>版本 R{{ detail.revisionNumber }}</span></div>
          <div class="dictionary-actions">
            <el-button v-if="selectedNode.allowsChildren && selectedNode.state !== 'RETIRED'" :icon="Plus" @click="openCreate(selectedNode)">添加子级</el-button>
            <el-button v-if="detail.state === 'DRAFT'" :icon="Edit" @click="openMetadata">编辑字典</el-button>
            <el-button v-if="detail.state !== 'DRAFT'" :icon="DocumentCopy" @click="revise">创建修订</el-button>
            <el-button v-if="detail.state === 'DRAFT'" type="primary" @click="transition('PUBLISH')">发布版本</el-button>
            <el-button v-if="detail.state === 'PUBLISHED'" type="danger" plain @click="transition('RETIRE')">停用</el-button>
          </div>
        </header>

        <section class="dictionary-metrics">
          <article><span>版本状态</span><strong><el-tag :type="stateType(detail.state)" effect="plain">{{ stateLabels[detail.state] }}</el-tag></strong><small>锁版本 {{ detail.version }}</small></article>
          <article><span>字典值项</span><strong>{{ detail.values.length }}</strong><small>{{ detail.allowsChildren ? '目录节点' : '按排序输出' }}</small></article>
          <article><span>结构类型</span><strong>{{ detail.allowsChildren ? '分组目录' : '值项字典' }}</strong><small>{{ detail.parentId ? '第二级' : '第一级' }}</small></article>
        </section>

        <section class="dictionary-values-section">
          <header><div><h3>数据字典值</h3></div><div class="dictionary-value-actions"><el-button v-if="canEditValues" :icon="Plus" type="primary" @click="openValue()">添加值项</el-button><el-button v-if="canEditValues" :icon="Delete" :disabled="!selectedValues.length" @click="deleteValues(selectedValues)">删除所选</el-button></div></header>
          <el-alert v-if="detail.state !== 'DRAFT'" title="当前为不可变版本；如需修改名称、结构或值项，请先创建修订。" type="info" :closable="false" show-icon />
          <el-alert v-else-if="detail.allowsChildren" title="目录型字典通过左侧树维护子级，不能同时维护值项。" type="warning" :closable="false" show-icon />
          <el-table :data="detail.values" row-key="id" border stripe class="dictionary-value-table" @selection-change="selectedValues = $event">
            <el-table-column v-if="canEditValues" type="selection" width="46" /><el-table-column prop="sortOrder" label="排序" width="88" align="center" />
            <el-table-column prop="value" label="数据字典值" min-width="280" show-overflow-tooltip /><el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip><template #default="scope">{{ scope.row.remark || '—' }}</template></el-table-column>
            <el-table-column v-if="canEditValues" label="操作" fixed="right" width="135" align="center"><template #default="scope"><el-button link type="primary" @click="openValue(scope.row)">修改</el-button><el-button link type="danger" @click="deleteValues([scope.row])">删除</el-button></template></el-table-column>
          </el-table>
          <el-empty v-if="!detail.values.length" :image-size="64" :description="detail.allowsChildren ? '该节点用于组织子级字典' : '当前版本尚无字典值'" />
        </section>

      </main>
      <main v-else class="dictionary-detail-panel dictionary-empty"><el-empty description="选择左侧字典查看值项和版本" /></main>
    </section>

    <el-drawer v-model="metadataDrawer" :title="editingDraft ? `编辑字典草稿 · ${detail?.code}` : creatingParent ? `添加子级 · ${creatingParent.name}` : '新增一级字典'" size="520px" destroy-on-close :close-on-click-modal="false">
      <el-alert title="编号由系统生成；发布后内容不可原地覆盖，后续调整使用修订版本。" type="info" :closable="false" show-icon />
      <el-form label-position="top" class="dictionary-form"><el-form-item label="字典名称" required><el-input v-model="form.name" maxlength="240" show-word-limit /></el-form-item><el-form-item v-if="!creatingParent" label="是否允许添加子级"><el-switch v-model="form.allowsChildren" :disabled="editingDraft && Boolean(selectedNode?.childCount)" active-text="目录型字典" inactive-text="值项型字典" /></el-form-item><el-form-item v-else label="上级字典"><el-input :model-value="`${creatingParent.code} · ${creatingParent.name}`" disabled /></el-form-item></el-form>
      <template #footer><el-button @click="metadataDrawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveMetadata">保存草稿</el-button></template>
    </el-drawer>

    <el-dialog v-model="valueDialog" :title="editingValueId ? '修改数据字典值' : '添加数据字典值'" width="560px" :close-on-click-modal="false">
      <el-form label-position="top"><el-form-item label="排序" required><el-input-number v-model="valueForm.sortOrder" :min="0" :max="999999" controls-position="right" /></el-form-item><el-form-item label="数据字典值" required><el-input v-model="valueForm.value" maxlength="1000" show-word-limit /></el-form-item><el-form-item label="备注"><el-input v-model="valueForm.remark" type="textarea" :rows="3" maxlength="2000" show-word-limit /></el-form-item></el-form>
      <template #footer><el-button @click="valueDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveValue">保存并校验整表</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.page-description{margin:6px 0 0;color:var(--muted);font-size:13px}.dictionary-page{display:flex;min-height:0;flex-direction:column;overflow:hidden}.dictionary-workspace{display:grid;grid-template-columns:318px minmax(0,1fr);flex:1;min-height:0;overflow:hidden;border:1px solid var(--line);border-radius:11px;background:#fff;box-shadow:0 5px 18px rgba(31,62,105,.05)}.dictionary-tree-panel{display:flex;min-width:0;min-height:0;flex-direction:column;border-right:1px solid var(--line);background:#f8fafc}.dictionary-tree-panel>header{display:flex;align-items:center;justify-content:space-between;padding:13px 15px;border-bottom:1px solid var(--line)}.dictionary-tree-panel>header div{display:flex;flex-direction:column;gap:2px}.dictionary-tree-panel>header strong{font-size:14px}.dictionary-tree-panel>header span{color:var(--muted);font-size:13px}.dictionary-search{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:7px;padding:12px 12px 7px}.dictionary-tree-options{display:flex;align-items:center;justify-content:space-between;padding:0 12px 8px}.dictionary-tree-scroll{flex:1;min-height:0;overflow:auto;padding:4px 7px 14px}.dictionary-tree-scroll :deep(.el-tree){background:transparent;--el-tree-node-hover-bg-color:#edf4ff}.dictionary-tree-scroll :deep(.el-tree-node__content){height:50px;margin:2px 0;border-radius:7px}.dictionary-tree-scroll :deep(.el-tree-node.is-current>.el-tree-node__content){background:#e5efff;color:var(--brand-700);box-shadow:inset 3px 0 var(--brand-600)}.dictionary-tree-node{display:flex;align-items:center;gap:8px;min-width:0;width:100%;padding-right:7px}.dictionary-tree-node.retired{opacity:.62}.dictionary-node-icon{display:grid;flex:0 0 auto;place-items:center;width:28px;height:28px;border:1px solid #d9e4f5;border-radius:7px;background:#fff;color:var(--brand-600)}.dictionary-node-copy{display:flex;min-width:0;flex:1;flex-direction:column}.dictionary-node-copy strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:14px}.dictionary-node-copy small{color:var(--muted);font-size:13px}.dictionary-detail-panel{min-width:0;min-height:0;padding:20px;overflow:auto}.dictionary-detail-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;padding-bottom:18px;border-bottom:1px solid var(--line)}.dictionary-detail-heading p{margin:0 0 3px;color:var(--brand-600);font-size:13px;font-weight:700;letter-spacing:.06em}.dictionary-detail-heading h2{margin:0;font-size:20px}.dictionary-detail-heading h2 code{margin-left:6px;color:#58708f;font-size: 13px;font-weight:600}.dictionary-detail-heading span{display:block;margin-top:5px;color:var(--muted);font-size:13px}.dictionary-actions{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:8px}.dictionary-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin:16px 0}.dictionary-metrics article{display:flex;min-height:86px;flex-direction:column;justify-content:center;padding:13px 14px;border:1px solid var(--line);border-radius:9px;background:#fbfcfe}.dictionary-metrics span,.dictionary-metrics small{color:var(--muted);font-size:13px}.dictionary-metrics strong{margin:5px 0;font-size:17px}.dictionary-values-section{overflow:hidden;border:1px solid var(--line);border-radius:10px}.dictionary-values-section>header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;padding:14px 16px;border-bottom:1px solid var(--line);background:#f8fafc}.dictionary-values-section h3{margin:0;font-size:14px}.dictionary-values-section p{margin:4px 0 0;color:var(--muted);font-size:13px}.dictionary-value-actions{display:flex;gap:8px}.dictionary-value-table{min-height:260px}.dictionary-values-section>.el-alert{border-radius:0}.dictionary-values-section>.el-empty{padding:10px}.dictionary-form{margin-top:18px}.dictionary-form .el-switch{--el-switch-on-color:var(--brand-600)}.dictionary-empty{display:grid;place-items:center}.dictionary-page :deep(.el-input-number){width:100%}
@media(max-width:1320px){.dictionary-workspace{grid-template-columns:270px minmax(0,1fr)}.dictionary-detail-panel{padding:16px}.dictionary-detail-heading{flex-direction:column}.dictionary-actions{justify-content:flex-start}.dictionary-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.dictionary-values-section>header{flex-direction:column}.dictionary-value-actions{justify-content:flex-start}}
</style>
