<script setup lang="ts">
import { DocumentChecked, Link, Refresh, Search, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError, api, errorMessage } from '../api'
import { flattenCatalog, type CatalogNode } from '../detection'
import { useAuthStore } from '../stores/auth'

type LinkStatus = 'LINKED' | 'AMBIGUOUS' | 'SOURCE_ONLY'
type SaveState = 'idle' | 'saving' | 'saved' | 'error'
type DocumentType = 'REPORT' | 'COMMISSION' | 'TASK'

interface ControlRow {
  id: string
  documentType: DocumentType
  sourceFieldCode: string
  projectRevisionId?: string
  projectNodeId?: string
  projectName: string
  parentName: string
  controlledNumber?: string
  templateVersionNumber?: string
  linkStatus: LinkStatus
  catalogStatus?: string
  sourceOccurrence: number
  sourceSystem: string
  sourceCapturedAt: string
  sourceManifestSha256: string
  version: number
  updatedAt: string
}

interface ControlPage {
  items: ControlRow[]
  total: number
  page: number
  limit: number
  linkedCount: number
  ambiguousCount: number
  sourceOnlyCount: number
  configuredCount: number
}

const auth = useAuthStore()
const route = useRoute()
const pageDefinition = computed(() => {
  const definitions: Record<string, { documentType: DocumentType; title: string; noun: string; eyebrow: string; note: string }> = {
    'report-templates': { documentType: 'REPORT', title: '报告版本管理', noun: '报告', eyebrow: '系统设置 · 单据版本', note: '修改只影响此后创建或修订的报告。' },
    'commission-templates': { documentType: 'COMMISSION', title: '委托单版本管理', noun: '委托单', eyebrow: '系统设置 · 单据版本', note: '修改只影响此后受理的委托单。' },
    'task-templates': { documentType: 'TASK', title: '任务单版本管理', noun: '任务单', eyebrow: '系统设置 · 单据版本', note: '修改只影响此后创建的检测任务。' },
  }
  return definitions[String(route.params.moduleKey)] ?? definitions['report-templates']
})
const catalog = ref<CatalogNode[]>([])
const rows = ref<ControlRow[]>([])
const loading = ref(false)
const treeLoading = ref(false)
const query = ref('')
const appliedQuery = ref('')
const treeQuery = ref('')
const selectedNode = ref<CatalogNode>()
const linkStatus = ref<'ALL' | LinkStatus>('ALL')
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const linkedCount = ref(0)
const ambiguousCount = ref(0)
const sourceOnlyCount = ref(0)
const configuredCount = ref(0)
const saveStates = reactive<Record<string, SaveState>>({})
const originals = new Map<string, { controlledNumber: string; templateVersionNumber: string }>()
const saveTimers = new Map<string, number>()
const queuedSaves = new Set<string>()
const treeProps = { value: 'id', label: 'name', children: 'children' }
const treeScrollEl = ref<HTMLElement>()
const treeHeight = ref(320)

const canWrite = computed(() => auth.user?.permissions.includes('SYSTEM_ADMIN'))
const flatCatalog = computed(() => flattenCatalog(catalog.value))
const projectCount = computed(() => flatCatalog.value.filter((node) => node.type === 'PROJECT').length)
const expandedKeys = computed(() => catalog.value.map((node) => node.id))
const filteredCatalog = computed(() => {
  const needle = treeQuery.value.trim().toLowerCase()
  if (!needle) return catalog.value
  const filter = (nodes: CatalogNode[]): CatalogNode[] => nodes.flatMap((node) => {
    const children = filter(node.children ?? [])
    const matches = [node.name, node.symbol, node.path].some((value) => value?.toLowerCase().includes(needle))
    return matches || children.length ? [{ ...node, children }] : []
  })
  return filter(catalog.value)
})
const activeScope = computed(() => selectedNode.value?.path ?? '全部检测项目与来源保留项')

function errorText(error: unknown) {
  return errorMessage(error)
}

function normalized(value?: string) { return value?.trim() ?? '' }

async function loadCatalog() {
  treeLoading.value = true
  try {
    catalog.value = await api<CatalogNode[]>('/api/detection-catalog/tree?includeInactive=true&includeHistory=true')
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { treeLoading.value = false }
}

async function loadPage() {
  loading.value = true
  try {
    const params = new URLSearchParams({
      q: appliedQuery.value,
      linkStatus: linkStatus.value,
      page: String(page.value),
      limit: String(pageSize.value),
    })
    if (selectedNode.value) params.set('catalogNodeId', selectedNode.value.id)
    params.set('documentType', pageDefinition.value.documentType)
    const response = await api<ControlPage>(`/api/document-template-controls?${params}`)
    rows.value = response.items
    total.value = response.total
    linkedCount.value = response.linkedCount
    ambiguousCount.value = response.ambiguousCount
    sourceOnlyCount.value = response.sourceOnlyCount
    configuredCount.value = response.configuredCount
    for (const row of rows.value) {
      originals.set(row.id, {
        controlledNumber: normalized(row.controlledNumber),
        templateVersionNumber: normalized(row.templateVersionNumber),
      })
      saveStates[row.id] = 'idle'
    }
  } catch (error) { ElMessage.error(errorText(error)) }
  finally { loading.value = false }
}

async function load() {
  await Promise.all([loadCatalog(), loadPage()])
}

function applySearch() {
  appliedQuery.value = query.value.trim()
  page.value = 1
  loadPage()
}

function resetFilters() {
  query.value = ''
  appliedQuery.value = ''
  treeQuery.value = ''
  linkStatus.value = 'ALL'
  selectedNode.value = undefined
  page.value = 1
  loadPage()
}

function selectNode(node: CatalogNode) {
  selectedNode.value = node
  page.value = 1
  loadPage()
}

function showAll() {
  treeQuery.value = ''
  selectedNode.value = undefined
  page.value = 1
  loadPage()
}

function changeSize(size: number) {
  pageSize.value = size
  page.value = 1
  loadPage()
}

function finishEditing(event: Event) {
  ;(event.target as HTMLInputElement).blur()
}

function clearSaveTimer(rowId: string) {
  const timer = saveTimers.get(rowId)
  if (timer !== undefined) window.clearTimeout(timer)
  saveTimers.delete(rowId)
}

function scheduleSave(row: ControlRow) {
  clearSaveTimer(row.id)
  saveTimers.set(row.id, window.setTimeout(() => {
    saveTimers.delete(row.id)
    saveRow(row)
  }, 300))
}

async function saveRow(row: ControlRow) {
  clearSaveTimer(row.id)
  if (!canWrite.value) return
  if (saveStates[row.id] === 'saving') {
    queuedSaves.add(row.id)
    return
  }
  const controlledNumber = normalized(row.controlledNumber)
  const templateVersionNumber = normalized(row.templateVersionNumber)
  const original = originals.get(row.id)
  if (original?.controlledNumber === controlledNumber
      && original.templateVersionNumber === templateVersionNumber) return
  saveStates[row.id] = 'saving'
  try {
    const saved = await api<ControlRow>(`/api/document-template-controls/${row.id}`, {
      method: 'PUT',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        controlledNumber: controlledNumber || null,
        templateVersionNumber: templateVersionNumber || null,
        expectedVersion: row.version,
      }),
    })
    const latestControlledNumber = normalized(row.controlledNumber)
    const latestTemplateVersionNumber = normalized(row.templateVersionNumber)
    const changedWhileSaving = latestControlledNumber !== controlledNumber
      || latestTemplateVersionNumber !== templateVersionNumber
    Object.assign(row, saved)
    originals.set(row.id, { controlledNumber, templateVersionNumber })
    if (changedWhileSaving) {
      row.controlledNumber = latestControlledNumber
      row.templateVersionNumber = latestTemplateVersionNumber
    }
    saveStates[row.id] = 'saved'
    window.setTimeout(() => { if (saveStates[row.id] === 'saved') saveStates[row.id] = 'idle' }, 1800)
    if (changedWhileSaving) scheduleSave(row)
  } catch (error) {
    saveStates[row.id] = 'error'
    ElMessage.error(errorText(error))
    if (error instanceof ApiError && error.status === 409) await loadPage()
  } finally {
    if (queuedSaves.delete(row.id)) scheduleSave(row)
  }
}

function linkLabel(status: LinkStatus) {
  return status === 'LINKED' ? '已绑定受控项目' : status === 'AMBIGUOUS' ? '同名路径待核对' : '来源保留项'
}

function linkType(status: LinkStatus) {
  return status === 'LINKED' ? 'success' : status === 'AMBIGUOUS' ? 'warning' : 'info'
}

function syncTreeHeight() {
  treeHeight.value = Math.max(180, Math.floor(treeScrollEl.value?.clientHeight ?? 320))
}

let treeResizeObserver: ResizeObserver | undefined
onMounted(async () => {
  await nextTick()
  treeResizeObserver = new ResizeObserver(syncTreeHeight)
  if (treeScrollEl.value) treeResizeObserver.observe(treeScrollEl.value)
  syncTreeHeight()
  await load()
})
onBeforeUnmount(() => {
  treeResizeObserver?.disconnect()
  for (const timer of saveTimers.values()) window.clearTimeout(timer)
  saveTimers.clear()
})
</script>

<template>
  <section class="page report-control-page">
    <header class="page-heading report-control-heading">
      <div>
        <h1>{{ pageDefinition.title }}</h1>
        <p class="heading-note">按检测项目维护{{ pageDefinition.noun }}受控号与模板版本。{{ pageDefinition.note }}</p>
      </div>
      <div class="report-control-metrics" :aria-label="`${pageDefinition.noun}版本配置统计`">
        <span><small>当前范围</small><strong>{{ total }}</strong></span>
        <span><small>已完整配置</small><strong>{{ configuredCount }}</strong></span>
        <span><small>可靠关联</small><strong>{{ linkedCount }}</strong></span>
      </div>
    </header>

    <section class="report-control-workbench">
      <aside class="report-control-tree" v-loading="treeLoading">
        <header>
          <div><strong>检测目录</strong><span>{{ projectCount }} 个项目节点</span></div>
          <el-button link type="primary" @click="showAll">查看全部</el-button>
        </header>
        <el-input v-model="treeQuery" clearable :prefix-icon="Search" placeholder="搜索目录、项目或符号" />
        <button class="all-scope-button" :class="{ active: !selectedNode }" type="button" @click="showAll">
          <span class="scope-icon"><el-icon><DocumentChecked /></el-icon></span>
          <span><strong>全部{{ pageDefinition.noun }}版本</strong><small>含来源保留项</small></span>
          <b>{{ total }}</b>
        </button>
        <div ref="treeScrollEl" class="report-tree-scroll">
          <el-tree-v2 :data="filteredCatalog" :props="treeProps" :height="treeHeight" :item-size="38"
            :default-expanded-keys="expandedKeys" highlight-current @node-click="selectNode">
            <template #default="{ data }">
              <div class="report-tree-node" :class="{ selected: selectedNode?.id === data.id }">
                <span class="node-kind">{{ data.type === 'DOMAIN' ? '域' : data.type === 'CATEGORY' ? '类' : '项' }}</span>
                <span class="node-label" :title="data.path">{{ data.name }}</span>
                <span v-if="data.status !== 'ACTIVE'" class="node-state">{{ data.status === 'HISTORICAL' ? '历史' : '停用' }}</span>
              </div>
            </template>
          </el-tree-v2>
        </div>
      </aside>

      <section class="report-control-main" v-loading="loading">
        <header class="control-scope-heading">
          <div><span>当前目录</span><strong>{{ activeScope }}</strong></div>
          <el-button :icon="Refresh" :aria-label="`刷新${pageDefinition.noun}版本配置`" @click="loadPage">刷新</el-button>
        </header>

        <section class="control-toolbar">
          <el-input v-model="query" clearable :prefix-icon="Search"
            placeholder="检测项目、父级项目、受控号或版本号" @keyup.enter="applySearch" />
          <el-select v-model="linkStatus" aria-label="关联状态" @change="page=1; loadPage()">
            <el-option label="全部关联状态" value="ALL" />
            <el-option label="已绑定受控项目" value="LINKED" />
            <el-option label="同名路径待核对" value="AMBIGUOUS" />
            <el-option label="来源保留项" value="SOURCE_ONLY" />
          </el-select>
          <el-button type="primary" :icon="Search" @click="applySearch">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </section>

        <el-alert v-if="ambiguousCount || sourceOnlyCount" class="source-boundary" type="warning" show-icon :closable="false">
          <template #title>当前范围有 {{ ambiguousCount }} 个同名项目、{{ sourceOnlyCount }} 个历史来源项目待核对，核对前不会自动关联。</template>
        </el-alert>

        <el-table :data="rows" row-key="id" border stripe class="report-control-table">
          <el-table-column label="检测项目 / 父级项目" min-width="250">
            <template #default="scope">
              <div class="control-project-cell">
                <strong :title="scope.row.projectName">{{ scope.row.projectName }}</strong>
                <div class="control-project-meta">
                  <span>{{ scope.row.parentName }}</span>
                  <el-tag :type="linkType(scope.row.linkStatus)" effect="plain" size="small">
                    <el-icon><Link v-if="scope.row.linkStatus === 'LINKED'" /><WarningFilled v-else /></el-icon>
                    {{ linkLabel(scope.row.linkStatus) }}
                  </el-tag>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="受控号" min-width="210">
            <template #default="scope">
              <el-input v-model="scope.row.controlledNumber" clearable maxlength="160" show-word-limit
                placeholder="请输入受控号" :aria-label="`${scope.row.projectName}受控号`"
                :disabled="!canWrite || saveStates[scope.row.id] === 'saving'"
                @input="scheduleSave(scope.row)" @change="saveRow(scope.row)"
                @blur="saveRow(scope.row)" @keyup.enter="finishEditing" />
            </template>
          </el-table-column>
          <el-table-column label="版本号 / 保存状态" min-width="190">
            <template #default="scope">
              <div class="version-editor-cell">
                <el-input v-model="scope.row.templateVersionNumber" clearable maxlength="120"
                  placeholder="请输入版本号" :aria-label="`${scope.row.projectName}版本号`"
                  :disabled="!canWrite || saveStates[scope.row.id] === 'saving'"
                  @input="scheduleSave(scope.row)" @change="saveRow(scope.row)"
                  @blur="saveRow(scope.row)" @keyup.enter="finishEditing" />
                <span class="save-state" :class="saveStates[scope.row.id]" aria-live="polite">
                  {{ saveStates[scope.row.id] === 'saving' ? '保存中' : saveStates[scope.row.id] === 'saved' ? '已保存' : saveStates[scope.row.id] === 'error' ? '保存失败' : '自动保存' }}
                </span>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <footer class="report-control-footer">
          <span>共 {{ total }} 项</span>
          <el-pagination :current-page="page" :page-size="pageSize" :total="total"
            :page-sizes="[10,20,50,100]" layout="total, sizes, prev, pager, next, jumper"
            @update:current-page="(value: number) => { page = value; loadPage() }" @size-change="changeSize" />
        </footer>
      </section>
    </section>
  </section>
</template>

<style scoped>
.report-control-page{display:flex;min-width:0;min-height:0;flex-direction:column;overflow:hidden}.report-control-heading{flex:0 0 auto;align-items:flex-end}.heading-note{margin:8px 0 0;color:var(--muted);font-size:13px}.report-control-metrics{display:flex;gap:12px}.report-control-metrics span{min-width:112px;padding:12px 16px;border:1px solid var(--line);border-radius:12px;background:#fff;display:flex;flex-direction:column;gap:4px}.report-control-metrics small{color:var(--muted);font-size: 13px}.report-control-metrics strong{font-size:22px;color:var(--brand-600)}
.report-control-workbench{display:grid;flex:1 1 0;grid-template-columns:300px minmax(0,1fr);gap:14px;min-width:0;min-height:0;overflow:hidden}.report-control-tree,.report-control-main{display:flex;min-width:0;min-height:0;flex-direction:column;border:1px solid var(--line);border-radius:14px;background:#fff;box-shadow:0 8px 24px rgba(38, 61, 96, .08)}.report-control-tree{padding:14px;overflow:hidden}.report-control-tree>header{flex:0 0 auto;display:flex;align-items:center;justify-content:space-between;margin-bottom:12px}.report-control-tree>header div{display:flex;flex-direction:column;gap:3px}.report-control-tree>header span{font-size: 13px;color:var(--muted)}
.all-scope-button{flex:0 0 auto;width:100%;margin:12px 0 8px;padding:10px;border:1px solid var(--line);border-radius:10px;background:#f6f9fd;display:grid;grid-template-columns:32px 1fr auto;align-items:center;gap:8px;text-align:left;color:#15233d;cursor:pointer}.all-scope-button:hover,.all-scope-button.active{border-color:var(--brand-600);background:var(--brand-100)}.all-scope-button span:nth-child(2){display:flex;min-width:0;flex-direction:column}.all-scope-button small{margin-top:2px;color:var(--muted)}.all-scope-button b{color:var(--brand-600)}.scope-icon{width:30px;height:30px;border-radius:8px;background:#fff;display:grid;place-items:center;color:var(--brand-600)}.report-tree-scroll{flex:1 1 0;min-height:0;margin:0 -8px -4px;overflow:hidden}.report-tree-node{display:flex;align-items:center;gap:7px;width:100%;min-width:0}.node-kind{flex:0 0 auto;padding:2px 5px;border-radius:5px;background:#f6f9fd;color:var(--muted);font-size:13px}.node-label{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.node-state{margin-left:auto;font-size:13px;color:var(--warning)}
.report-control-main{padding:14px;overflow:hidden}.control-scope-heading,.control-toolbar,.source-boundary,.report-control-footer{flex:0 0 auto}.control-scope-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;padding-bottom:12px;border-bottom:1px solid var(--line)}.control-scope-heading div{display:flex;min-width:0;flex-direction:column;gap:4px}.control-scope-heading span{font-size:13px;letter-spacing:.08em;color:var(--muted)}.control-scope-heading strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.control-toolbar{display:grid;grid-template-columns:minmax(260px,1fr) 190px auto auto;gap:8px;margin:12px 0}.source-boundary{margin-bottom:10px}.report-control-table{flex:1 1 0;width:100%;height:0!important;min-height:0!important}.control-project-cell{display:flex;min-width:0;flex-direction:column;gap:6px}.control-project-cell>strong,.control-project-meta>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.control-project-meta{display:flex;min-width:0;align-items:center;gap:6px}.control-project-meta>span{flex:1;font-size: 13px;color:var(--muted)}.control-project-meta .el-tag{flex:0 0 auto;max-width:132px}.version-editor-cell{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:8px}.save-state{min-width:48px;font-size:13px;color:var(--muted);white-space:nowrap}.save-state.saving{color:var(--brand-600)}.save-state.saved{color:var(--success)}.save-state.error{color:var(--danger)}.report-control-footer{display:flex;align-items:center;justify-content:space-between;gap:16px;padding-top:12px;color:var(--muted);font-size: 13px}.report-control-footer>span{min-width:0}
.source-boundary :deep(.el-alert__title){color:#704509;font-weight:500}.source-boundary :deep(.el-alert__icon){color:#8f5706}
@media(max-width:1360px){.report-control-workbench{grid-template-columns:230px minmax(0,1fr)}.report-control-metrics span{min-width:96px;padding:10px 12px}.control-toolbar{grid-template-columns:minmax(210px,1fr) 168px auto auto}.report-control-footer{align-items:flex-end;flex-direction:column}}
</style>
