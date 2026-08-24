<script setup lang="ts">
import {
  Connection, Edit, OfficeBuilding, Plus, Refresh, Search, UserFilled, WarningFilled,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { api, errorMessage } from '../api'
import { flattenCatalog, type CatalogNode } from '../detection'
import { useAuthStore } from '../stores/auth'

type PersonOption = {
  id: string
  employeeNumber: string
  name: string
  departmentId: string
  departmentName: string
  position?: string
  personType?: string
}

type Department = {
  id: string
  code: string
  name: string
  parentId?: string
  parentName?: string
  path: string
  sortOrder: number
  responsiblePersonId?: string
  responsiblePersonName?: string
  supervisingPersonId?: string
  supervisingPersonName?: string
  active: boolean
  version: number
  retiredAt?: string
  retiredReason?: string
  detectionProjectNodeIds: string[]
  detectionProjectCount: number
  memberCount: number
  childCount: number
  allowedActions: string[]
  retirementBlockers: string[]
}

type DepartmentNode = Department & { children: DepartmentNode[] }
type DepartmentOptions = { people: PersonOption[] }
type CheckableTree = {
  setCheckedKeys: (keys: string[]) => void
  getCheckedKeys: (leafOnly?: boolean) => unknown[]
  filter: (query: string) => void
}

const auth = useAuthStore()
const rows = ref<Department[]>([])
const people = ref<PersonOption[]>([])
const catalog = ref<CatalogNode[]>([])
const selectedId = ref('')
const query = ref('')
const projectQuery = ref('')
const includeRetired = ref(false)
const loading = ref(false)
const saving = ref(false)
const drawer = ref(false)
const editing = ref<Department>()
const activeFormTab = ref('basic')
const projectTreeRef = ref<CheckableTree>()

const canWrite = computed(() => auth.user?.permissions.includes('MODULE_WRITE'))
const selected = computed(() => rows.value.find((item) => item.id === selectedId.value))
const flatProjects = computed(() => flattenCatalog(catalog.value).filter((item) => item.type === 'PROJECT'))
const projectById = computed(() => new Map(flatProjects.value.map((item) => [item.id, item])))
const expandedProjectKeys = computed(() => flattenCatalog(catalog.value)
  .filter((item) => item.type !== 'PROJECT').map((item) => item.id))
const boundProjects = computed(() => (selected.value?.detectionProjectNodeIds ?? [])
  .map((id) => projectById.value.get(id)).filter((item): item is CatalogNode => Boolean(item)))
const activeCount = computed(() => rows.value.filter((item) => item.active).length)
const retiredCount = computed(() => rows.value.length - activeCount.value)

const form = reactive({
  code: '', name: '', parentId: '', sortOrder: 50,
  responsiblePersonId: '', supervisingPersonId: '', detectionProjectNodeIds: [] as string[],
})

const parentTree = computed(() => {
  const blocked = new Set<string>()
  if (editing.value) {
    blocked.add(editing.value.id)
    let changed = true
    while (changed) {
      changed = false
      for (const item of rows.value) {
        if (item.parentId && blocked.has(item.parentId) && !blocked.has(item.id)) {
          blocked.add(item.id)
          changed = true
        }
      }
    }
  }
  return buildTree(rows.value.filter((item) => item.active && !blocked.has(item.id)))
})
const departmentTree = computed(() => {
  const source = rows.value.filter((item) => includeRetired.value || item.active)
  const tree = buildTree(source)
  const needle = query.value.trim().toLowerCase()
  if (!needle) return tree
  const filter = (nodes: DepartmentNode[]): DepartmentNode[] => nodes.flatMap((node) => {
    const children = filter(node.children)
    const own = [node.code, node.name, node.path, node.responsiblePersonName, node.supervisingPersonName]
      .some((value) => value?.toLowerCase().includes(needle))
    return own || children.length ? [{ ...node, children }] : []
  })
  return filter(tree)
})

function buildTree(items: Department[]) {
  const ids = new Set(items.map((item) => item.id))
  const nodes = new Map(items.map((item) => [item.id, { ...item, children: [] as DepartmentNode[] }]))
  const roots: DepartmentNode[] = []
  for (const node of nodes.values()) {
    const parent = node.parentId && ids.has(node.parentId) ? nodes.get(node.parentId) : undefined
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  const sort = (values: DepartmentNode[]) => {
    values.sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name, 'zh-CN'))
    values.forEach((item) => sort(item.children))
  }
  sort(roots)
  return roots
}

function showError(error: unknown) {
  ElMessage.error(errorMessage(error))
}

async function load(keepSelection = true) {
  loading.value = true
  try {
    const [departments, options, tree] = await Promise.all([
      api<Department[]>('/api/master-data/departments'),
      api<DepartmentOptions>('/api/master-data/departments/options'),
      api<CatalogNode[]>('/api/detection-catalog/tree?includeInactive=false&includeHistory=false'),
    ])
    rows.value = departments
    people.value = options.people
    catalog.value = tree
    const current = keepSelection ? departments.find((item) => item.id === selectedId.value) : undefined
    selectedId.value = (current ?? departments.find((item) => item.active) ?? departments[0])?.id ?? ''
  } catch (error) { showError(error) }
  finally { loading.value = false }
}

function resetForm(parentId = '') {
  editing.value = undefined
  Object.assign(form, {
    code: '', name: '', parentId, sortOrder: 50,
    responsiblePersonId: '', supervisingPersonId: '', detectionProjectNodeIds: [],
  })
  activeFormTab.value = 'basic'
  projectQuery.value = ''
}

async function openCreate(parent?: Department) {
  resetForm(parent?.id ?? '')
  drawer.value = true
  await nextTick()
  projectTreeRef.value?.setCheckedKeys([])
}

async function openEdit(row: Department) {
  editing.value = row
  Object.assign(form, {
    code: row.code, name: row.name, parentId: row.parentId ?? '', sortOrder: row.sortOrder,
    responsiblePersonId: row.responsiblePersonId ?? '', supervisingPersonId: row.supervisingPersonId ?? '',
    detectionProjectNodeIds: [...row.detectionProjectNodeIds],
  })
  activeFormTab.value = 'basic'
  projectQuery.value = ''
  drawer.value = true
  await nextTick()
  projectTreeRef.value?.setCheckedKeys(row.detectionProjectNodeIds)
}

function selectedProjectIds() {
  return [...new Set(form.detectionProjectNodeIds)].filter((id) => projectById.value.has(id))
}

function syncProjectSelection(_data?: CatalogNode, state?: { checkedKeys?: string[] }) {
  const checkedVisible = (state?.checkedKeys ?? projectTreeRef.value?.getCheckedKeys(true) ?? [])
    .map(String).filter((id) => projectById.value.has(id))
  form.detectionProjectNodeIds = [...new Set(checkedVisible)]
}

async function save() {
  if (!form.name.trim()) return ElMessage.warning('请填写部门名称')
  const projectIds = selectedProjectIds()
  saving.value = true
  try {
    const body = {
      name: form.name.trim(), parentId: form.parentId || null, sortOrder: form.sortOrder,
      responsiblePersonId: form.responsiblePersonId || null,
      supervisingPersonId: form.supervisingPersonId || null,
      detectionProjectNodeIds: projectIds,
      ...(editing.value ? { expectedVersion: editing.value.version } : { code: form.code.trim() || null }),
    }
    const saved = await api<Department>(editing.value
      ? `/api/master-data/departments/${editing.value.id}` : '/api/master-data/departments', {
      method: editing.value ? 'PUT' : 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(body),
    })
    drawer.value = false
    selectedId.value = saved.id
    ElMessage.success(editing.value ? '部门配置已更新' : '部门已创建')
    await load()
  } catch (error) { showError(error) }
  finally { saving.value = false }
}

async function transition(row: Department, action: 'RETIRE' | 'RESTORE') {
  try {
    let comment = ''
    if (action === 'RETIRE') {
      if (row.retirementBlockers.length) {
        await ElMessageBox.alert(
          row.retirementBlockers.map((item, index) => `${index + 1}. ${item}`).join('\n'),
          `暂不能停用 · ${row.name}`,
          { type: 'warning', confirmButtonText: '我知道了', customClass: 'business-blocker-dialog' },
        )
        return
      }
      const result = await ElMessageBox.prompt('停用后不可用于新增人员、任务分配或委托接收。请输入停用原因。',
        `停用部门 · ${row.name}`, { inputPlaceholder: '至少说明停用依据或组织调整原因',
          inputValidator: (value) => Boolean(value.trim()) || '必须填写停用原因', confirmButtonText: '确认停用' })
      comment = result.value.trim()
    } else {
      await ElMessageBox.confirm(`确认恢复“${row.name}”？恢复后将重新进入可选范围。`, '恢复部门', { type: 'warning' })
    }
    await api(`/api/master-data/departments/${row.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: row.version,
        reasonCode: action === 'RETIRE' ? 'ORGANIZATION_ADJUSTMENT' : 'ORGANIZATION_RESTORE', comment: comment || null }),
    })
    ElMessage.success(action === 'RETIRE' ? '部门已受控停用' : '部门已恢复')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showError(error)
  }
}

function selectDepartment(row: Department) {
  selectedId.value = row.id
}

function projectFilter(value: string, data: CatalogNode) {
  if (!value.trim()) return true
  const needle = value.trim().toLowerCase()
  return [data.name, data.symbol, data.path].some((item) => item?.toLowerCase().includes(needle))
}

watch(projectQuery, (value) => projectTreeRef.value?.filter(value))
onMounted(() => load(false))
</script>

<template>
  <section class="page department-page">
    <header class="page-heading">
      <div><h1>部门配置</h1><p class="page-description">维护部门层级、负责人和检测项目分工。</p></div>
      <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate()">新增一级部门</el-button>
    </header>

    <section v-loading="loading" class="department-workspace">
      <aside class="department-tree-panel">
        <header><div><strong>组织架构</strong><span>{{ activeCount }} 个启用</span></div><el-button text :icon="Refresh" aria-label="刷新部门" @click="load()" /></header>
        <div class="tree-search"><el-input v-model="query" clearable :prefix-icon="Search" placeholder="搜索部门、负责人或路径" /><el-switch v-model="includeRetired" inline-prompt active-text="含停" inactive-text="启用" /></div>
        <div class="department-tree-scroll">
          <el-tree :data="departmentTree" node-key="id" default-expand-all highlight-current :current-node-key="selectedId"
            :expand-on-click-node="false" :props="{ label: 'name', children: 'children' }" @node-click="selectDepartment">
            <template #default="{ data }">
              <div class="department-tree-node" :class="{ retired: !data.active }">
                <span class="tree-node-icon"><el-icon><OfficeBuilding /></el-icon></span>
                <span class="tree-node-copy"><strong>{{ data.name }}</strong><small>{{ data.memberCount }} 人 · {{ data.detectionProjectCount }} 项</small></span>
                <el-tag v-if="!data.active" size="small" type="info" effect="plain">停用</el-tag>
              </div>
            </template>
          </el-tree>
          <el-empty v-if="!departmentTree.length" :image-size="64" description="没有匹配的部门" />
        </div>
        <footer><span>共 {{ rows.length }} 个部门</span><span v-if="retiredCount">{{ retiredCount }} 个已停用</span></footer>
      </aside>

      <main v-if="selected" class="department-detail-panel">
        <header class="department-detail-heading">
          <div class="department-title"><span class="department-mark"><el-icon><OfficeBuilding /></el-icon></span><div><p>{{ selected.path }}</p><h2>{{ selected.name }} <code>{{ selected.code }}</code></h2></div></div>
          <div v-if="canWrite" class="department-actions">
            <el-button :icon="Plus" :disabled="!selected.active" @click="openCreate(selected)">添加下级</el-button>
            <el-button v-if="selected.active" :icon="Edit" @click="openEdit(selected)">编辑</el-button>
            <el-button v-if="selected.active" type="danger" plain @click="transition(selected,'RETIRE')">停用</el-button>
            <el-button v-else type="primary" plain @click="transition(selected,'RESTORE')">恢复</el-button>
          </div>
        </header>

        <section class="department-metrics">
          <article><span>层级位置</span><strong>{{ selected.parentName ?? '一级部门' }}</strong><small>排序值 {{ selected.sortOrder }}</small></article>
          <article><span>在岗人员</span><strong>{{ selected.memberCount }}</strong><small>由人员主档实时汇总</small></article>
          <article><span>下级部门</span><strong>{{ selected.childCount }}</strong><small>仅统计启用部门</small></article>
          <article><span>可检测项目</span><strong>{{ selected.detectionProjectCount }}</strong><small>任务分配硬性范围</small></article>
        </section>

        <section class="department-section responsibility-section">
          <header><div><h3>部门责任人</h3><p>只可选择当前机构启用人员；不展示联系方式和证件信息。</p></div><el-icon><UserFilled /></el-icon></header>
          <div class="responsibility-grid">
            <article><span>部门负责人</span><strong>{{ selected.responsiblePersonName ?? '未配置' }}</strong><small>{{ selected.responsiblePersonId ? '负责部门业务组织与资源协调' : '建议在投入业务前完成配置' }}</small></article>
            <article><span>上级管理人</span><strong>{{ selected.supervisingPersonName ?? '未配置' }}</strong><small>{{ selected.supervisingPersonId ? '承担跨部门管理或上级监督' : '可按组织制度选配' }}</small></article>
          </div>
        </section>

        <section class="department-section project-scope-section">
          <header><div><h3>关联检测项目</h3><p>未勾选的检测项目，任务分配时不能选择本部门人员。</p></div><el-icon><Connection /></el-icon></header>
          <div v-if="boundProjects.length" class="project-scope-list">
            <el-tooltip v-for="item in boundProjects" :key="item.id" :content="item.path" placement="top">
              <el-tag effect="plain">{{ item.name }}</el-tag>
            </el-tooltip>
          </div>
          <el-empty v-else :image-size="58" description="尚未关联检测项目；本部门人员不能接收检测任务" />
        </section>

        <el-alert v-if="!selected.active" type="info" :closable="false" show-icon :title="`该部门已停用：${selected.retiredReason ?? '未记录原因'}`" />
      </main>

      <main v-else class="department-detail-panel empty-detail"><el-empty description="选择左侧部门查看配置" /></main>
    </section>

    <el-drawer v-model="drawer" :title="editing ? `编辑部门 · ${editing.name}` : form.parentId ? '添加下级部门' : '新增一级部门'"
      size="min(820px, calc(100vw - 260px))" destroy-on-close :close-on-click-modal="false" class="department-drawer">
      <el-tabs v-model="activeFormTab">
        <el-tab-pane label="部门信息" name="basic">
          <el-alert title="部门代码用于接口、导入和审计定位；留空时由系统生成，创建后不可修改。" type="info" :closable="false" show-icon />
          <el-form label-position="top" class="department-form">
            <div class="form-grid"><el-form-item label="部门名称" required><el-input v-model="form.name" maxlength="160" show-word-limit /></el-form-item><el-form-item label="部门代码"><el-input v-model="form.code" :disabled="Boolean(editing)" maxlength="40" placeholder="可留空自动生成" /></el-form-item></div>
            <div class="form-grid"><el-form-item label="上级部门"><el-tree-select v-model="form.parentId" :data="parentTree" node-key="id" :props="{ label:'name',children:'children' }" clearable check-strictly placeholder="不选择则为一级部门" /></el-form-item><el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" :max="999999" controls-position="right" /></el-form-item></div>
            <el-divider content-position="left">人员责任</el-divider>
            <div class="form-grid"><el-form-item label="部门负责人"><el-select v-model="form.responsiblePersonId" clearable filterable placeholder="按姓名、工号或部门搜索"><el-option v-for="item in people" :key="item.id" :label="`${item.name} · ${item.employeeNumber} · ${item.departmentName}`" :value="item.id"><span>{{ item.name }} · {{ item.employeeNumber }}</span><small class="person-option-meta">{{ item.departmentName }}{{ item.position ? ` · ${item.position}` : '' }}</small></el-option></el-select></el-form-item><el-form-item label="上级管理人"><el-select v-model="form.supervisingPersonId" clearable filterable placeholder="按姓名、工号或部门搜索"><el-option v-for="item in people" :key="item.id" :label="`${item.name} · ${item.employeeNumber} · ${item.departmentName}`" :value="item.id"><span>{{ item.name }} · {{ item.employeeNumber }}</span><small class="person-option-meta">{{ item.departmentName }}{{ item.position ? ` · ${item.position}` : '' }}</small></el-option></el-select></el-form-item></div>
          </el-form>
        </el-tab-pane>
        <el-tab-pane name="projects"><template #label><span>检测项目范围 <el-tag size="small" effect="plain">{{ selectedProjectIds().length }}</el-tag></span></template>
          <el-alert title="可勾选领域、分类或单个项目；保存时仅记录启用且已发布的项目节点。未选择表示本部门不能承接检测任务。" type="warning" :closable="false" show-icon />
          <div class="project-tree-toolbar"><el-input v-model="projectQuery" clearable :prefix-icon="Search" placeholder="搜索检测项目、符号或完整路径" /><span>共 {{ flatProjects.length }} 个可选项目</span></div>
          <div class="department-project-tree"><el-tree-v2 ref="projectTreeRef" :data="catalog" :props="{ value:'id',label:'name',children:'children' }" show-checkbox :height="500" :item-size="35" :default-expanded-keys="expandedProjectKeys" :filter-method="projectFilter" @check="syncProjectSelection"><template #default="{ data }"><div class="project-tree-node"><span>{{ data.name }}</span><small>{{ data.type === 'PROJECT' ? (data.symbol || '检测项目') : data.type === 'DOMAIN' ? '业务域' : '分类' }}</small></div></template></el-tree-v2></div>
        </el-tab-pane>
      </el-tabs>
      <template #footer><div class="drawer-footer-note"><el-icon><WarningFilled /></el-icon><span>保存会递增版本并写入完整审计；已创建任务不被反向修改。</span></div><el-button @click="drawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存配置</el-button></template>
    </el-drawer>
  </section>
</template>

<style scoped>
.page-description { margin: 6px 0 0; color: var(--muted); font-size: 13px; }
.department-workspace { display: grid; grid-template-columns: 292px minmax(0,1fr); min-height: 690px; overflow: hidden; border: 1px solid var(--line); border-radius: 11px; background: #fff; box-shadow: 0 5px 18px rgba(31,62,105,.05); }
.department-tree-panel { display: flex; min-width: 0; flex-direction: column; border-right: 1px solid var(--line); background: #f8fafc; }
.department-tree-panel > header,.department-tree-panel > footer { display: flex; align-items: center; justify-content: space-between; padding: 13px 15px; border-bottom: 1px solid var(--line); }
.department-tree-panel > header div { display:flex; flex-direction:column; gap:2px; }.department-tree-panel > header strong { font-size:14px; }.department-tree-panel > header span,.department-tree-panel > footer { color:var(--muted);font-size:13px; }
.department-tree-panel > footer { margin-top:auto;border-top:1px solid var(--line);border-bottom:0;background:#fff; }
.tree-search { display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px;padding:11px 12px;background:#fff; }
.department-tree-scroll { min-height:0;max-height:600px;overflow:auto;padding:6px 7px 12px; }
.department-tree-scroll :deep(.el-tree) { background:transparent;--el-tree-node-hover-bg-color:#edf4ff; }.department-tree-scroll :deep(.el-tree-node__content) { height:48px;margin:2px 0;border-radius:7px; }.department-tree-scroll :deep(.el-tree-node.is-current > .el-tree-node__content) { background:#e5efff;color:var(--brand-700);box-shadow:inset 3px 0 var(--brand-600); }
.department-tree-node { display:flex;align-items:center;gap:8px;min-width:0;width:100%;padding-right:7px; }.department-tree-node.retired { opacity:.62; }.tree-node-icon { display:grid;place-items:center;width:28px;height:28px;border:1px solid #d9e4f5;border-radius:7px;background:#fff;color:var(--brand-600); }.tree-node-copy { display:flex;min-width:0;flex:1;flex-direction:column; }.tree-node-copy strong { overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size: 13px; }.tree-node-copy small { color:var(--muted);font-size:13px; }
.department-detail-panel { min-width:0;padding:20px;background:linear-gradient(180deg,#fff 0,#fbfcfe 100%); }.department-detail-heading { display:flex;align-items:center;justify-content:space-between;gap:20px;padding-bottom:18px;border-bottom:1px solid var(--line); }.department-title { display:flex;align-items:center;min-width:0;gap:12px; }.department-mark { display:grid;place-items:center;width:44px;height:44px;border-radius:10px;background:#e7f0ff;color:var(--brand-600);font-size:20px; }.department-title p { margin:0 0 4px;color:var(--muted);font-size:13px; }.department-title h2 { margin:0;font-size:21px; }.department-title code { margin-left:7px;white-space:nowrap;color:#60728d;font-size:13px;font-weight:500; }.department-actions { display:flex;flex-wrap:wrap;justify-content:flex-end;gap:8px; }
.department-metrics { display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin:16px 0; }.department-metrics article { display:flex;min-width:0;flex-direction:column;padding:14px 15px;border:1px solid #e5ebf3;border-radius:9px;background:#fff; }.department-metrics span { color:var(--muted);font-size:13px; }.department-metrics strong { margin:7px 0 3px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#18345a;font-size:18px; }.department-metrics small { color:#8795aa;font-size:13px; }
.department-section { margin-top:14px;padding:16px 17px;border:1px solid var(--line);border-radius:9px;background:#fff; }.department-section > header { display:flex;align-items:center;justify-content:space-between;margin-bottom:14px; }.department-section h3 { margin:0 0 4px;font-size:14px; }.department-section p { margin:0;color:var(--muted);font-size:13px; }.department-section > header > .el-icon { color:#8ca5c8;font-size:22px; }.responsibility-grid { display:grid;grid-template-columns:1fr 1fr;gap:12px; }.responsibility-grid article { display:flex;flex-direction:column;padding:13px 14px;border-radius:8px;background:#f6f9fd; }.responsibility-grid span { color:var(--muted);font-size:13px; }.responsibility-grid strong { margin:5px 0;font-size:15px; }.responsibility-grid small { color:#8492a6;font-size:13px; }.project-scope-list { display:flex;max-height:205px;flex-wrap:wrap;gap:7px;overflow:auto;padding-right:4px; }.project-scope-list .el-tag { max-width:230px;overflow:hidden;text-overflow:ellipsis; }.blocker-list { margin:8px 0 0;padding-left:20px;font-size: 13px;line-height:1.8; }.department-detail-panel > .el-alert { margin-top:14px; }.empty-detail { display:grid;place-items:center; }
.department-drawer :deep(.el-drawer__header) { margin-bottom:0;padding-bottom:14px;border-bottom:1px solid var(--line); }.department-drawer :deep(.el-drawer__body) { padding-top:8px; }.department-drawer :deep(.el-drawer__footer) { display:flex;align-items:center;padding-top:12px;border-top:1px solid var(--line); }.department-drawer :deep(.el-select),.department-drawer :deep(.el-tree-select),.department-drawer :deep(.el-input-number) { width:100%; }.department-drawer :deep(.el-alert) { margin:8px 0 16px; }.department-form { margin-top:16px; }.person-option-meta { float:right;margin-left:18px;color:var(--muted); }.project-tree-toolbar { display:flex;align-items:center;gap:14px;margin:12px 0; }.project-tree-toolbar .el-input { flex:1; }.project-tree-toolbar span { color:var(--muted);font-size:13px;white-space:nowrap; }.department-project-tree { height:520px;overflow:hidden;border:1px solid var(--line);border-radius:8px;padding:8px;background:#fbfcfe; }.department-project-tree :deep(.el-tree-node__content) { min-height:35px;border-radius:6px; }.project-tree-node { display:flex;min-width:0;flex:1;align-items:center;justify-content:space-between;gap:12px;padding-right:8px; }.project-tree-node span { overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size: 13px; }.project-tree-node small { color:var(--muted);font-size:13px;white-space:nowrap; }.drawer-footer-note { display:flex;flex:1;align-items:center;gap:6px;color:var(--muted);font-size:13px;text-align:left; }
@media (max-width: 1320px) { .department-workspace { grid-template-columns:258px minmax(0,1fr); }.department-metrics { grid-template-columns:repeat(2,minmax(0,1fr)); }.department-detail-panel { padding:16px; }.department-detail-heading { align-items:flex-start;flex-direction:column;gap:14px; }.department-actions { justify-content:flex-start; } }
@media (max-width: 1080px) { .department-workspace { grid-template-columns:230px minmax(0,1fr); }.department-detail-heading { align-items:flex-start;flex-direction:column; }.department-actions { justify-content:flex-start; }.responsibility-grid { grid-template-columns:1fr; } }
</style>
