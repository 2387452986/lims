<script setup lang="ts">
import { Coin, CopyDocument, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { api, errorMessage } from '../api'
import { firstProject, flattenCatalog, type CatalogNode, type DetectionParameter, type ProjectRevision } from '../detection'
import { useAuthStore } from '../stores/auth'

interface PriceDraft { pricingMethod: string; price: string }
interface QuoteLine { parameterId: string; code: string; name: string; pricingMethod: string; unitPrice: string; quantity: string; lineTotal: string }
interface Quote { lines: QuoteLine[]; total: string }

const auth = useAuthStore()
const tree = ref<CatalogNode[]>([])
const selectedNode = ref<CatalogNode>()
const revision = ref<ProjectRevision>()
const treeQuery = ref('')
const parameterQuery = ref('')
const onlyConfigured = ref(false)
const loading = ref(false)
const saving = ref(false)
const expandedKeys = ref<string[]>([])
const treeScrollEl = ref<HTMLElement>()
const treeHeight = ref(320)
const selected = ref<DetectionParameter[]>([])
const drafts = reactive<Record<string, PriceDraft>>({})
const quoteDrawer = ref(false)
const quote = ref<Quote>()
const quoteQuantity = ref('1')
const treeProps = { value: 'id', label: 'name', children: 'children' }
const canWrite = computed(() => auth.user?.permissions.includes('MODULE_WRITE'))
const canEdit = computed(() => canWrite.value && revision.value?.status === 'DRAFT')
const flat = computed(() => flattenCatalog(tree.value))
const rows = computed(() => {
  const needle = parameterQuery.value.trim().toLowerCase()
  return (revision.value?.parameters ?? []).filter((item) => {
    const draft = drafts[item.id]
    return (!needle || [item.code, item.name, item.unit, draft?.pricingMethod]
      .some((value) => value?.toLowerCase().includes(needle)))
      && (!onlyConfigured.value || Boolean(draft?.price))
  })
})
const dirty = computed(() => (revision.value?.parameters ?? []).filter((item) => {
  const draft = drafts[item.id]
  return draft && (draft.pricingMethod !== (item.pricingMethod ?? '') || draft.price !== (item.price ?? ''))
}))

function errorText(error: unknown) { return errorMessage(error) }

async function loadTree() {
  loading.value = true
  try {
    tree.value = await api<CatalogNode[]>(`/api/detection-catalog/tree?query=${encodeURIComponent(treeQuery.value)}&includeHistory=false`)
    expandedKeys.value = flat.value.filter((item) => item.type !== 'PROJECT').map((item) => item.id)
    const next = selectedNode.value ? flat.value.find((item) => item.id === selectedNode.value?.id) : firstProject(tree.value)
    if (next) await selectNode(next)
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { loading.value = false }
}

function syncTreeHeight() {
  treeHeight.value = Math.max(180, Math.floor(treeScrollEl.value?.clientHeight ?? 320))
}

async function selectNode(node: CatalogNode) {
  selectedNode.value = node
  if (node.type !== 'PROJECT' || !node.projectRevisionId) { revision.value = undefined; return }
  await loadRevision(node.projectRevisionId)
}

async function loadRevision(id: string) {
  revision.value = await api<ProjectRevision>(`/api/detection-project-revisions/${id}`)
  for (const key of Object.keys(drafts)) delete drafts[key]
  revision.value.parameters.forEach((item) => { drafts[item.id] = { pricingMethod: item.pricingMethod ?? '按单个样品计价', price: item.price ?? '' } })
  selected.value = []
}

async function createRevision() {
  if (!revision.value) return
  await ElMessageBox.confirm('价格修改将在新的检测项目修订中进行，已受理委托仍使用原快照。', '创建价格修订')
  try {
    const created = await api<ProjectRevision>(`/api/detection-project-revisions/${revision.value.id}/revisions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
    })
    await loadRevision(created.id)
    ElMessage.success(`已创建 R${created.revisionNumber} 价格修订草稿`)
  } catch (error) { ElMessage.error(errorText(error)) }
}

async function savePrices() {
  if (!revision.value || !dirty.value.length) return ElMessage.info('没有待保存的价格变更')
  saving.value = true
  try {
    const payload = dirty.value.map((item) => ({ id: item.id, ...drafts[item.id], expectedVersion: item.version }))
    await api(`/api/detection-project-revisions/${revision.value.id}/parameter-prices`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(payload),
    })
    await loadRevision(revision.value.id)
    ElMessage.success(`已原子保存 ${payload.length} 条参数价格`)
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { saving.value = false }
}

async function calculate() {
  if (!revision.value) return
  const targets = selected.value.length ? selected.value : revision.value.parameters.filter((item) => drafts[item.id]?.price)
  if (!targets.length) return ElMessage.warning('请勾选参数，或先配置固定价格')
  try {
    quote.value = await api<Quote>(`/api/detection-project-revisions/${revision.value.id}/parameter-prices/quote`, {
      method: 'POST', body: JSON.stringify(targets.map((item) => ({ parameterId: item.id, quantity: quoteQuantity.value }))),
    })
    quoteDrawer.value = true
  } catch (error) { ElMessage.error(errorText(error)) }
}

let treeResizeObserver: ResizeObserver | undefined
onMounted(async () => {
  await nextTick()
  treeResizeObserver = new ResizeObserver(syncTreeHeight)
  if (treeScrollEl.value) treeResizeObserver.observe(treeScrollEl.value)
  syncTreeHeight()
  await loadTree()
})
onUnmounted(() => treeResizeObserver?.disconnect())
</script>

<template>
  <section class="page pricing-page">
    <header class="page-heading detection-heading">
      <div><h1>参数价格管理</h1></div>
      <div class="catalog-metrics"><span>当前项目 <strong>{{ revision?.projectName ?? '未选择' }}</strong></span><span>已配价 <strong>{{ revision?.parameters.filter(item => drafts[item.id]?.price).length ?? 0 }}</strong></span></div>
    </header>

    <section class="pricing-workbench" v-loading="loading">
      <aside class="catalog-tree-pane pricing-tree">
        <header><div><strong>检测目录</strong><span>与委托使用同一检测目录</span></div></header>
        <div class="tree-tools"><el-input v-model="treeQuery" clearable :prefix-icon="Search" placeholder="搜索项目" @keyup.enter="loadTree" /><el-button :icon="Refresh" aria-label="刷新" @click="loadTree" /></div>
        <div ref="treeScrollEl" class="catalog-tree-scroll"><el-tree-v2 :data="tree" :props="treeProps" :height="treeHeight" :default-expanded-keys="expandedKeys" highlight-current @node-click="selectNode">
          <template #default="{ data }"><div class="catalog-tree-node"><span class="node-kind">{{ data.type === 'DOMAIN' ? '域' : data.type === 'CATEGORY' ? '类' : '项' }}</span><span class="node-name">{{ data.name }}</span><span v-if="data.type === 'PROJECT'" class="node-count">{{ data.parameterCount }}</span></div></template>
        </el-tree-v2></div>
      </aside>

      <section class="pricing-main">
        <header class="pricing-toolbar">
          <div class="pricing-title"><strong>{{ revision?.projectName ?? '请选择检测项目' }}</strong><span v-if="revision">R{{ revision.revisionNumber }} · {{ revision.status === 'DRAFT' ? '草稿可编辑' : '发布版只读' }}</span></div>
          <div class="toolbar-buttons"><el-input v-model="parameterQuery" clearable :prefix-icon="Search" placeholder="参数名称、编码或单位" /><el-switch v-model="onlyConfigured" active-text="仅看已配价" /><el-button :icon="Coin" :disabled="!revision" @click="calculate">价格试算</el-button><el-button v-if="revision?.status === 'PUBLISHED' && canWrite" :icon="CopyDocument" @click="createRevision">创建价格修订</el-button><el-button v-if="canEdit" type="primary" :loading="saving" :disabled="!dirty.length" @click="savePrices">保存 {{ dirty.length ? `(${dirty.length})` : '' }}</el-button></div>
        </header>
        <el-alert v-if="revision?.status === 'PUBLISHED'" type="info" show-icon :closable="false" title="发布版价格已被委托快照引用；需要改价时请创建修订，禁止原地覆盖。" />
        <el-table :data="rows" row-key="id" border stripe class="pricing-table" @selection-change="selected = $event">
          <el-table-column type="selection" width="42" />
          <el-table-column label="检测参数" min-width="250"><template #default="scope"><div class="parameter-identity"><strong :title="scope.row.name">{{ scope.row.name }}</strong><span>{{ scope.row.code }}<template v-if="scope.row.unit"> · {{ scope.row.unit }}</template></span></div></template></el-table-column>
          <el-table-column label="计价方式" min-width="180"><template #default="scope"><el-select v-model="drafts[scope.row.id].pricingMethod" :disabled="!canEdit" allow-create filterable><el-option label="按单个样品计价" value="按单个样品计价" /><el-option label="按检测参数计价" value="按检测参数计价" /><el-option label="按委托计价" value="按委托计价" /></el-select></template></el-table-column>
          <el-table-column label="固定价（元）" width="176"><template #default="scope"><div class="price-entry-cell"><el-input v-model="drafts[scope.row.id].price" :disabled="!canEdit" inputmode="decimal" placeholder="未配置" /><el-tag v-if="drafts[scope.row.id].price" type="success" effect="plain">已配置</el-tag><el-tag v-else type="info" effect="plain">未配置</el-tag></div></template></el-table-column>
        </el-table>
        <footer class="table-footer"><span>显示 {{ rows.length }} / {{ revision?.parameters.length ?? 0 }} 条</span><span v-if="dirty.length" class="dirty-note">{{ dirty.length }} 条未保存</span></footer>
      </section>
    </section>

    <el-drawer v-model="quoteDrawer" title="价格试算" size="720px">
      <div class="quote-control"><span>每项数量</span><el-input v-model="quoteQuantity" inputmode="decimal" /><el-button type="primary" @click="calculate">重新试算</el-button></div>
      <el-table :data="quote?.lines" border><el-table-column prop="code" label="编码" width="140" /><el-table-column prop="name" label="参数" min-width="180" /><el-table-column prop="unitPrice" label="单价" width="90" /><el-table-column prop="quantity" label="数量" width="80" /><el-table-column prop="lineTotal" label="小计" width="100" /></el-table>
      <div class="quote-total"><span>试算合计</span><strong>¥ {{ quote?.total ?? '0' }}</strong></div>
    </el-drawer>
  </section>
</template>
