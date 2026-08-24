<script setup lang="ts">
import {
  CircleCheck, Clock, EditPen, Key, Plus, Refresh, Search, SwitchButton, UserFilled,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { useAuthStore } from '../stores/auth'

type CatalogNode = {
  key: string
  parentKey?: string
  label: string
  nodeType: 'DOMAIN' | 'GROUP' | 'PAGE' | 'ACTION'
  origin: 'SOURCE' | 'LOCAL'
  sourcePath?: string
  sortOrder: number
  leaf: boolean
  enforcementState: 'CONTAINER' | 'ENFORCED' | 'BLOCKED'
  permissionCode?: string
  permissionName?: string
  permissionCategory?: string
  blockingReason?: string
  children: CatalogNode[]
}
type ClientCatalog = {
  code: string
  sourceKey: number
  label: string
  availability: 'ACTIVE' | 'READINESS_BLOCKED'
  blockingReason?: string
  tree: CatalogNode[]
}
type Catalog = {
  sourceNodeCount: number
  enforcedLeafCount: number
  blockedLeafCount: number
  sourceManifestSha256: string
  clients: ClientCatalog[]
}
type Role = {
  id: string
  code: string
  name: string
  dataScope: string
  description?: string
  systemRole: boolean
  status: 'ACTIVE' | 'RETIRED'
  version: number
  revisionNumber: number
  clientCodes: string[]
  catalogGrantKeys: string[]
  permissions: string[]
  assignedUserCount: number
  updatedAt: string
  allowedActions: string[]
}
type RoleRevision = {
  id: string
  revisionNumber: number
  name: string
  dataScope: string
  description?: string
  status: 'ACTIVE' | 'RETIRED'
  version: number
  reasonCode?: string
  comment?: string
  clientCodes: string[]
  catalogGrantKeys: string[]
  permissions: string[]
  createdAt: string
}
type User = {
  id: string
  username: string
  displayName: string
  enabled: boolean
  version: number
  roles: string[]
}
type TreeNode = CatalogNode & { disabled: boolean; children: TreeNode[] }

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const catalog = ref<Catalog>()
const roles = ref<Role[]>([])
const users = ref<User[]>([])
const workspaceTab = ref<'roles' | 'accounts'>('roles')
const roleQuery = ref('')
const roleStatus = ref<'ALL' | 'ACTIVE' | 'RETIRED'>('ALL')
const accountQuery = ref('')
const selectedRoleId = ref('')
const selectedClientCode = ref('PC')
const catalogQuery = ref('')
const roleGrantQuery = ref('')
const selectedNodeKey = ref('')
const roleDrawer = ref(false)
const accountDialog = ref(false)
const historyDrawer = ref(false)
const editingRoleId = ref('')
const selectedUser = ref<User>()
const selectedUserRoleIds = ref<string[]>([])
const history = ref<RoleRevision[]>([])
const historyTitle = ref('')
const roleTree = ref<{ setCheckedKeys: (keys: string[]) => void }>()
const roleForm = reactive({
  name: '', dataScope: 'DEPARTMENT', description: '', clientCodes: ['PC'] as string[],
  catalogGrantKeys: [] as string[], expectedVersion: 0,
})

const dataScopes = [
  { value: 'ALL', label: '全机构', description: '可访问机构全部授权业务数据' },
  { value: 'ORGANIZATION', label: '本机构', description: '仅当前机构及下属数据' },
  { value: 'DEPARTMENT', label: '本部门', description: '仅当前部门数据' },
  { value: 'PROJECT', label: '参与项目', description: '仅被分配的工程项目' },
  { value: 'SELF', label: '仅本人', description: '仅本人创建或负责的数据' },
]
const scopeLabel = (value: string) => dataScopes.find((item) => item.value === value)?.label ?? value
const scopeDescription = (value: string) => dataScopes.find((item) => item.value === value)?.description ?? '受控数据范围'
const selectedRole = computed(() => roles.value.find((role) => role.id === selectedRoleId.value))
const currentClient = computed(() => catalog.value?.clients.find((item) => item.code === selectedClientCode.value))
const filteredRoles = computed(() => {
  const needle = roleQuery.value.trim().toLowerCase()
  return roles.value.filter((role) => (roleStatus.value === 'ALL' || role.status === roleStatus.value)
    && (!needle || [role.name, role.code, role.description].some((value) => value?.toLowerCase().includes(needle))))
})
const filteredUsers = computed(() => {
  const needle = accountQuery.value.trim().toLowerCase()
  return users.value.filter((user) => !needle || [user.username, user.displayName, ...user.roles]
    .some((value) => value.toLowerCase().includes(needle)))
})
const allCatalogNodes = computed(() => flatten(catalog.value?.clients.flatMap((item) => item.tree) ?? []))
const selectedNode = computed(() => allCatalogNodes.value.find((node) => node.key === selectedNodeKey.value))
const enforcedKeys = computed(() => new Set(allCatalogNodes.value
  .filter((node) => node.leaf && node.enforcementState === 'ENFORCED').map((node) => node.key)))
const grantedKeys = computed(() => new Set(selectedRole.value?.catalogGrantKeys ?? []))
const visibleTree = computed(() => makeGrantTree(currentClient.value?.tree ?? [], catalogQuery.value))
const expandedKeys = computed(() => visibleTree.value.map((node) => node.key))
const editableTree = computed<TreeNode[]>(() => makeGrantTree(currentClient.value?.tree ?? [], roleGrantQuery.value))
const editableExpandedKeys = computed(() => flatten(editableTree.value)
  .filter((node) => !node.leaf).map((node) => node.key))
const activeRoles = computed(() => roles.value.filter((role) => role.status === 'ACTIVE'))
const clientGrantCount = computed(() => {
  const clientKeys = new Set(flatten(currentClient.value?.tree ?? []).map((node) => node.key))
  return [...grantedKeys.value].filter((key) => clientKeys.has(key)).length
})
const activeRoleCount = computed(() => roles.value.filter((role) => role.status === 'ACTIVE').length)

function flatten(nodes: CatalogNode[]): CatalogNode[] {
  const result: CatalogNode[] = []
  const visit = (values: CatalogNode[]) => values.forEach((node) => { result.push(node); visit(node.children) })
  visit(nodes)
  return result
}
function makeGrantTree(nodes: CatalogNode[], query: string): TreeNode[] {
  const needle = query.trim().toLowerCase()
  const result: TreeNode[] = []
  nodes.forEach((node) => {
    const children = makeGrantTree(node.children, query)
    if (!node.leaf) {
      if (children.length) result.push({ ...node, disabled: true, children })
      return
    }
    const eligible = node.enforcementState === 'ENFORCED'
    const matched = !needle || [node.label, node.permissionCode, node.permissionName, node.permissionCategory]
      .some((value) => value?.toLowerCase().includes(needle))
    if (eligible && matched) result.push({ ...node, disabled: false, children: [] })
  })
  return result
}
function isMatched(label: string) {
  return Boolean(catalogQuery.value.trim() && label.toLowerCase().includes(catalogQuery.value.trim().toLowerCase()))
}
function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}
function stateType(state: string) { return state === 'ACTIVE' ? 'success' : 'info' }
function showError(error: unknown) {
  ElMessage.error(errorMessage(error))
}
function roleNames(codes: string[]) {
  return codes.map((code) => roles.value.find((role) => role.code === code)?.name ?? code)
}
function selectFirstRole() {
  if (!roles.value.some((role) => role.id === selectedRoleId.value)) selectedRoleId.value = roles.value[0]?.id ?? ''
}
function selectClient(code: string) {
  selectedClientCode.value = code
  catalogQuery.value = ''
  selectedNodeKey.value = currentClient.value?.tree[0]?.key ?? ''
}

async function load(keepSelection = true) {
  loading.value = true
  try {
    const [nextCatalog, nextRoles, nextUsers] = await Promise.all([
      api<Catalog>('/api/access-control/catalog'), api<Role[]>('/api/access-control/roles'),
      api<User[]>('/api/access-control/users'),
    ])
    catalog.value = nextCatalog; roles.value = nextRoles; users.value = nextUsers
    if (!keepSelection) selectedRoleId.value = ''
    selectFirstRole()
    if (!nextCatalog.clients.some((item) => item.code === selectedClientCode.value)) {
      selectedClientCode.value = nextCatalog.clients[0]?.code ?? ''
    }
    if (!allCatalogNodes.value.some((node) => node.key === selectedNodeKey.value)) {
      selectedNodeKey.value = currentClient.value?.tree[0]?.key ?? ''
    }
  } catch (error) { showError(error) }
  finally { loading.value = false }
}

function resetRoleForm(role?: Role) {
  editingRoleId.value = role?.id ?? ''
  roleGrantQuery.value = ''
  Object.assign(roleForm, {
    name: role?.name ?? '', dataScope: role?.dataScope ?? 'DEPARTMENT',
    description: role?.description ?? '', clientCodes: role?.clientCodes.length ? [...role.clientCodes] : ['PC'],
    catalogGrantKeys: role ? [...role.catalogGrantKeys] : [], expectedVersion: role?.version ?? 0,
  })
}
function openCreate() { resetRoleForm(); selectedClientCode.value = 'PC'; roleDrawer.value = true }
function openEdit(role = selectedRole.value) {
  if (!role || !role.allowedActions.includes('UPDATE')) return
  resetRoleForm(role); selectedClientCode.value = role.clientCodes[0] ?? 'PC'; roleDrawer.value = true
}
function syncCheckedKeys(_node: TreeNode, info: { checkedKeys: Array<string | number> }) {
  const visibleKeys = new Set(flatten(editableTree.value).filter((node) => node.leaf).map((node) => node.key))
  const preservedKeys = roleForm.catalogGrantKeys.filter((key) => enforcedKeys.value.has(key) && !visibleKeys.has(key))
  const checkedVisibleKeys = info.checkedKeys.map(String)
    .filter((key) => enforcedKeys.value.has(key) && visibleKeys.has(key))
  roleForm.catalogGrantKeys = [...new Set([...preservedKeys, ...checkedVisibleKeys])]
}
function setClientEnabled(client: ClientCatalog, enabled: boolean) {
  if (client.availability !== 'ACTIVE') return
  if (enabled && !roleForm.clientCodes.includes(client.code)) roleForm.clientCodes.push(client.code)
  if (!enabled) {
    if (roleForm.clientCodes.length === 1) return ElMessage.warning('角色至少启用一个可用客户端')
    roleForm.clientCodes = roleForm.clientCodes.filter((code) => code !== client.code)
    const clientKeys = new Set(flatten(client.tree).map((node) => node.key))
    roleForm.catalogGrantKeys = roleForm.catalogGrantKeys.filter((key) => !clientKeys.has(key))
  }
}
async function saveRole() {
  if (!roleForm.name.trim()) return ElMessage.warning('请填写角色名称')
  if (!roleForm.clientCodes.length) return ElMessage.warning('至少启用一个客户端')
  if (!roleForm.catalogGrantKeys.length) return ElMessage.warning('至少选择一项已接通的动作权限')
  const edited = roles.value.find((role) => role.id === editingRoleId.value)
  const revokesCurrentSession = Boolean(edited && auth.user?.roles.includes(edited.code))
  saving.value = true
  try {
    const body = JSON.stringify({ name: roleForm.name.trim(), dataScope: roleForm.dataScope,
      description: roleForm.description.trim(), clientCodes: roleForm.clientCodes,
      catalogGrantKeys: roleForm.catalogGrantKeys,
      ...(editingRoleId.value ? { expectedVersion: roleForm.expectedVersion } : {}),
    })
    const saved = editingRoleId.value
      ? await api<Role>(`/api/access-control/roles/${editingRoleId.value}`, { method: 'PUT',
        headers: { 'Idempotency-Key': crypto.randomUUID() }, body })
      : await api<Role>('/api/access-control/roles', { method: 'POST',
        headers: { 'Idempotency-Key': crypto.randomUUID() }, body })
    roleDrawer.value = false
    if (revokesCurrentSession) {
      ElMessage.success('角色策略已修订；当前会话已按安全规则撤销，请重新登录')
      auth.$patch({ user: null, checked: true })
      await router.replace({ name: 'login' })
      return
    }
    selectedRoleId.value = saved.id
    ElMessage.success(editingRoleId.value ? '角色策略新修订已生效' : '自定义角色已创建')
    await load()
  } catch (error) { showError(error) }
  finally { saving.value = false }
}

async function transitionRole(role: Role, action: 'RETIRE' | 'REACTIVATE') {
  try {
    let comment = '重新启用角色'
    if (action === 'RETIRE') {
      comment = (await ElMessageBox.prompt('停用后该角色不可再分配；历史策略与审计记录永久保留。请输入停用原因。',
        `停用角色 · ${role.name}`, { type: 'warning', inputType: 'textarea',
          inputValidator: (value) => Boolean(value.trim()) || '必须填写停用原因' })).value.trim()
    } else {
      await ElMessageBox.confirm('重新启用后该角色可再次分配，原策略保持不变。确认继续？',
        `重新启用 · ${role.name}`, { type: 'warning' })
    }
    saving.value = true
    await api(`/api/access-control/roles/${role.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: role.version,
        reasonCode: action === 'RETIRE' ? 'ROLE_RETIRE' : 'ROLE_REACTIVATE', comment }),
    })
    ElMessage.success(action === 'RETIRE' ? '角色已受控停用' : '角色已重新启用')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showError(error)
  } finally { saving.value = false }
}

async function openHistory(role = selectedRole.value) {
  if (!role) return
  try {
    history.value = await api<RoleRevision[]>(`/api/access-control/roles/${role.id}/history`)
    historyTitle.value = role.name; historyDrawer.value = true
  } catch (error) { showError(error) }
}

function editUser(user: User) {
  selectedUser.value = user
  selectedUserRoleIds.value = activeRoles.value.filter((role) => user.roles.includes(role.code)).map((role) => role.id)
  accountDialog.value = true
}
async function saveUser() {
  if (!selectedUser.value) return
  if (!selectedUserRoleIds.value.length) return ElMessage.warning('账号至少保留一个启用角色')
  const revokesCurrentSession = selectedUser.value.id === auth.user?.id
  saving.value = true
  try {
    await api(`/api/access-control/users/${selectedUser.value.id}/roles`, { method: 'PUT',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ roles: selectedUserRoleIds.value, expectedVersion: selectedUser.value.version }) })
    accountDialog.value = false
    if (revokesCurrentSession) {
      ElMessage.success('账号角色已更新；当前会话已撤销，请重新登录')
      auth.$patch({ user: null, checked: true })
      await router.replace({ name: 'login' })
      return
    }
    ElMessage.success('账号角色已更新，旧会话已撤销')
    await load()
  } catch (error) { showError(error) }
  finally { saving.value = false }
}
async function revokeSessions(user: User) {
  try {
    await ElMessageBox.confirm(`确认撤销 ${user.displayName}（${user.username}）的全部现有会话？`,
      '撤销账号会话', { type: 'warning' })
    await api(`/api/access-control/users/${user.id}/sessions/revoke`, { method: 'PUT',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ expectedVersion: user.version }) })
    if (user.id === auth.user?.id) {
      ElMessage.success('当前会话已撤销，请重新登录')
      auth.$patch({ user: null, checked: true })
      await router.replace({ name: 'login' })
      return
    }
    ElMessage.success('账号全部会话已撤销')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showError(error)
  }
}

onMounted(() => load(false))
</script>

<template>
  <section class="page access-page">
    <header class="page-heading access-heading">
      <div><h1>权限配置</h1><p class="page-description">维护角色、动作权限、数据范围和账号会话。</p></div>
      <div class="heading-actions"><el-button :icon="Refresh" :loading="loading" @click="load()">刷新</el-button><el-button type="primary" :icon="Plus" @click="openCreate">新增角色</el-button></div>
    </header>

    <section class="access-metrics" aria-label="权限统计">
      <article><span>启用角色</span><strong>{{ activeRoleCount }}</strong></article>
      <article><span>账号</span><strong>{{ users.length }}</strong></article>
      <article class="success"><span>可分配动作</span><strong>{{ catalog?.enforcedLeafCount ?? 0 }}</strong></article>
    </section>

    <el-tabs v-model="workspaceTab" class="access-tabs">
      <el-tab-pane name="roles"><template #label><span class="tab-label"><el-icon><Key /></el-icon>角色与动作权限</span></template>
        <section v-loading="loading" class="role-workspace">
          <aside class="role-list-panel">
            <header><div><strong>角色目录</strong><span>{{ filteredRoles.length }} / {{ roles.length }} 个角色</span></div><el-button text :icon="Plus" aria-label="新增角色" @click="openCreate" /></header>
            <div class="role-filters"><el-input v-model="roleQuery" clearable :prefix-icon="Search" placeholder="角色名称或代码" /><el-segmented v-model="roleStatus" :options="[{label:'全部',value:'ALL'},{label:'启用',value:'ACTIVE'},{label:'停用',value:'RETIRED'}]" /></div>
            <div class="role-list" role="listbox" aria-label="角色列表">
              <button v-for="role in filteredRoles" :key="role.id" type="button" :class="{ active:selectedRoleId===role.id, retired:role.status==='RETIRED' }" @click="selectedRoleId=role.id">
                <span class="role-avatar"><el-icon><UserFilled /></el-icon></span><span class="role-copy"><strong>{{ role.name }}</strong><small>{{ role.code }}</small><em>{{ scopeLabel(role.dataScope) }} · {{ role.permissions.length }} 项动作</em></span><el-tag :type="stateType(role.status)" size="small" effect="plain">{{ role.status==='ACTIVE'?'启用':'停用' }}</el-tag>
              </button>
              <el-empty v-if="!filteredRoles.length" :image-size="56" description="没有匹配角色" />
            </div>
          </aside>

          <main v-if="selectedRole" class="role-detail-panel">
            <header class="role-detail-heading">
              <div><p>{{ selectedRole.systemRole ? '系统预置角色' : '机构自定义角色' }}</p><h2>{{ selectedRole.name }} <el-tag :type="stateType(selectedRole.status)" size="small" effect="plain">{{ selectedRole.status==='ACTIVE'?'启用':'停用' }}</el-tag></h2><span>{{ selectedRole.code }} · R{{ selectedRole.revisionNumber }} · 更新于 {{ formatTime(selectedRole.updatedAt) }}</span></div>
              <div class="role-actions"><el-button :icon="Clock" @click="openHistory()">版本链</el-button><el-button v-if="selectedRole.allowedActions.includes('UPDATE')" type="primary" :icon="EditPen" @click="openEdit()">修订策略</el-button><el-tooltip v-if="selectedRole.allowedActions.includes('RETIRE')" :disabled="selectedRole.assignedUserCount===0" :content="`仍有 ${selectedRole.assignedUserCount} 个账号使用该角色，请先解除分配`"><span><el-button type="danger" plain :disabled="selectedRole.assignedUserCount>0" @click="transitionRole(selectedRole,'RETIRE')">停用</el-button></span></el-tooltip><el-button v-if="selectedRole.allowedActions.includes('REACTIVATE')" type="primary" plain @click="transitionRole(selectedRole,'REACTIVATE')">重新启用</el-button></div>
            </header>
            <section class="role-summary">
              <article><span>数据范围</span><strong>{{ scopeLabel(selectedRole.dataScope) }}</strong><small>{{ scopeDescription(selectedRole.dataScope) }}</small></article>
              <article><span>动作权限</span><strong>{{ selectedRole.permissions.length }}</strong><small>当前已授权</small></article>
              <article><span>适用客户端</span><strong>{{ selectedRole.clientCodes.length }}</strong><small>{{ selectedRole.clientCodes.join('、') }}</small></article>
              <article><span>已分配账号</span><strong>{{ selectedRole.assignedUserCount }}</strong><small>策略修订将撤销会话</small></article>
            </section>
            <p v-if="selectedRole.description" class="role-description">{{ selectedRole.description }}</p>

            <section class="catalog-workbench">
              <header><div><h3>动作权限</h3><p>仅显示可实际分配并由服务端校验的动作。</p></div><div class="catalog-legend"><span><i class="legend-enforced"></i>当前角色已授权</span></div></header>
              <el-tabs :model-value="selectedClientCode" class="client-tabs" @tab-change="selectClient(String($event))">
                <el-tab-pane v-for="client in catalog?.clients" :key="client.code" :name="client.code">
                  <template #label><span class="client-tab-label">{{ client.label }}<el-tag v-if="client.availability!=='ACTIVE'" type="warning" size="small" effect="plain">待交付</el-tag></span></template>
                </el-tab-pane>
              </el-tabs>
              <el-alert v-if="currentClient?.availability!=='ACTIVE'" :title="currentClient?.blockingReason" type="warning" :closable="false" show-icon />
              <div class="catalog-toolbar"><el-input v-model="catalogQuery" clearable :prefix-icon="Search" placeholder="搜索页面或动作" /><span>当前角色 {{ clientGrantCount }} 项授权</span></div>
              <div class="catalog-grid">
                <div class="permission-tree-wrap">
                  <el-tree-v2 :data="visibleTree" :props="{value:'key',label:'label',children:'children'}" :height="468" :item-size="38" highlight-current :current-node-key="selectedNodeKey" :default-expanded-keys="expandedKeys" @node-click="(node: CatalogNode)=>selectedNodeKey=node.key">
                    <template #default="{ data }"><div class="permission-node" :class="{ selected:selectedNodeKey===data.key, matched:isMatched(data.label) }"><span class="node-label">{{ data.label }}</span><span class="node-meta"><el-icon v-if="grantedKeys.has(data.key)" class="granted-icon" title="当前角色已授权"><CircleCheck /></el-icon><el-tag v-if="data.leaf" type="success" size="small" effect="plain">可分配</el-tag></span></div></template>
                  </el-tree-v2>
                  <el-empty v-if="!visibleTree.length" :image-size="56" description="没有匹配权限节点" />
                </div>
                <aside class="permission-inspector">
                  <template v-if="selectedNode"><span class="inspector-kicker">{{ selectedNode.leaf ? '动作' : '权限分组' }}</span><h4>{{ selectedNode.label }}</h4><dl><div><dt>当前角色</dt><dd>{{ grantedKeys.has(selectedNode.key)?'已授权':'未授权' }}</dd></div><div><dt>校验位置</dt><dd>{{ selectedNode.leaf ? '服务端强制校验' : '分组目录' }}</dd></div></dl><el-alert v-if="selectedNode.leaf" title="保存角色后，该账号的旧会话会立即撤销。" type="info" :closable="false" show-icon /></template>
                  <el-empty v-else :image-size="52" description="选择权限节点查看详情" />
                </aside>
              </div>
            </section>
          </main>
          <main v-else class="role-detail-panel role-empty"><el-empty description="选择角色查看权限策略" /></main>
        </section>
      </el-tab-pane>

      <el-tab-pane name="accounts"><template #label><span class="tab-label"><el-icon><UserFilled /></el-icon>账号角色与会话</span></template>
        <section class="account-workspace" v-loading="loading">
          <header><div><h2>账号角色与会话</h2><p>角色变更与主动撤销都会立即增加会话版本，使旧会话在下一请求失效。</p></div><el-input v-model="accountQuery" clearable :prefix-icon="Search" placeholder="账号、姓名或角色" /></header>
          <el-table :data="filteredUsers" border stripe row-key="id" class="account-table">
            <el-table-column label="账号" min-width="210"><template #default="scope"><div class="account-identity"><el-avatar :icon="UserFilled" :size="34" /><span class="account-copy"><strong>{{ scope.row.displayName }}</strong><small>{{ scope.row.username }}</small></span><el-tag v-if="scope.row.id===auth.user?.id" size="small" effect="plain">当前账号</el-tag></div></template></el-table-column>
            <el-table-column label="角色" min-width="360"><template #default="scope"><div class="role-tags"><el-tag v-for="name in roleNames(scope.row.roles)" :key="name" size="small" effect="plain">{{ name }}</el-tag></div></template></el-table-column>
            <el-table-column label="状态" width="100" align="center"><template #default="scope"><div class="table-tag-cell"><el-tag :type="scope.row.enabled?'success':'info'" effect="plain">{{ scope.row.enabled?'启用':'停用' }}</el-tag></div></template></el-table-column>
            <el-table-column label="锁版本" prop="version" width="88" align="center" />
            <el-table-column label="操作" fixed="right" width="210" align="center"><template #default="scope"><div class="table-actions table-actions--center"><el-button link type="primary" @click="editUser(scope.row)">分配角色</el-button><el-button link type="danger" :icon="SwitchButton" @click="revokeSessions(scope.row)">撤销会话</el-button></div></template></el-table-column>
          </el-table>
          <el-empty v-if="!filteredUsers.length" :image-size="64" description="没有匹配账号" />
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="roleDrawer" :title="editingRoleId ? '修订角色策略' : '新增自定义角色'" size="760px" destroy-on-close :close-on-click-modal="false">
      <el-alert v-if="editingRoleId && auth.user?.roles.includes(roles.find(role=>role.id===editingRoleId)?.code ?? '')" title="当前账号正在使用该角色；保存成功后当前会话会立即撤销，需要重新登录。" type="warning" :closable="false" show-icon />
      <el-form label-position="top" class="role-form">
        <div class="form-grid"><el-form-item label="角色名称" required><el-input v-model="roleForm.name" maxlength="10" show-word-limit placeholder="1–10 个字符" /></el-form-item><el-form-item label="数据范围" required><el-select v-model="roleForm.dataScope"><el-option v-for="scope in dataScopes" :key="scope.value" :value="scope.value" :label="`${scope.label} · ${scope.description}`" /></el-select></el-form-item></div>
        <el-form-item label="角色说明"><el-input v-model="roleForm.description" type="textarea" :rows="2" maxlength="300" show-word-limit placeholder="说明职责边界和适用岗位" /></el-form-item>
        <el-form-item label="适用客户端" required><div class="client-selector"><label v-for="client in catalog?.clients" :key="client.code" :class="{ blocked:client.availability!=='ACTIVE', selected:roleForm.clientCodes.includes(client.code) }"><el-checkbox :model-value="roleForm.clientCodes.includes(client.code)" :disabled="client.availability!=='ACTIVE'" @change="setClientEnabled(client,Boolean($event))">{{ client.label }}</el-checkbox><span>{{ client.availability==='ACTIVE'?'已具备运行客户端':client.blockingReason }}</span></label></div></el-form-item>
        <el-form-item label="动作权限" required><section class="grant-editor"><header><div><strong>选择已接通动作</strong><span>已选 {{ roleForm.catalogGrantKeys.length }} 项；来源目录仍在主工作台完整保留</span></div><el-tag type="success" effect="plain">后端强制校验</el-tag></header><div class="grant-toolbar"><el-input v-model="roleGrantQuery" clearable :prefix-icon="Search" placeholder="搜索动作名称或权限码" /><span>仅展示可实际授权动作</span></div><el-tree-v2 ref="roleTree" :key="`${editingRoleId || 'create'}:${roleGrantQuery}`" :data="editableTree" :props="{value:'key',label:'label',children:'children',disabled:'disabled'}" :height="420" :item-size="36" show-checkbox check-strictly :default-expanded-keys="editableExpandedKeys" :default-checked-keys="roleForm.catalogGrantKeys" @check="syncCheckedKeys"><template #default="{ data }"><div class="grant-node"><span>{{ data.label }}</span><small v-if="data.leaf">{{ data.permissionCode }}</small></div></template></el-tree-v2><el-empty v-if="!editableTree.length" :image-size="52" description="没有匹配的已接通动作" /></section></el-form-item>
      </el-form>
      <template #footer><el-button @click="roleDrawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveRole">{{ editingRoleId?'保存为新修订':'创建角色' }}</el-button></template>
    </el-drawer>

    <el-dialog v-model="accountDialog" :title="`分配账号角色 · ${selectedUser?.displayName ?? ''}`" width="620px" :close-on-click-modal="false">
      <el-alert title="保存后该账号的全部旧会话立即失效；当前账号不能移除自己的系统管理权限。" type="warning" :closable="false" show-icon />
      <el-checkbox-group v-model="selectedUserRoleIds" class="account-role-grid"><label v-for="role in activeRoles" :key="role.id"><el-checkbox :value="role.id"><strong>{{ role.name }}</strong></el-checkbox><span>{{ scopeLabel(role.dataScope) }} · {{ role.permissions.length }} 项动作 · {{ role.assignedUserCount }} 个账号</span></label></el-checkbox-group>
      <template #footer><el-button @click="accountDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveUser">保存并撤销旧会话</el-button></template>
    </el-dialog>

    <el-drawer v-model="historyDrawer" :title="`策略版本链 · ${historyTitle}`" size="660px">
      <el-alert title="策略版本、客户端、目录授权和运行权限快照均不可修改或删除。" type="info" :closable="false" show-icon />
      <el-timeline class="role-history"><el-timeline-item v-for="revision in history" :key="revision.id" :timestamp="formatTime(revision.createdAt)" :type="revision.status==='ACTIVE'?'primary':'info'" placement="top"><article><header><strong>R{{ revision.revisionNumber }} · {{ revision.name }}</strong><el-tag :type="stateType(revision.status)" size="small" effect="plain">{{ revision.status==='ACTIVE'?'启用':'停用' }}</el-tag></header><p>{{ scopeLabel(revision.dataScope) }} · {{ revision.permissions.length }} 项动作 · 锁版本 {{ revision.version }}</p><dl><div><dt>客户端</dt><dd>{{ revision.clientCodes.join('、') }}</dd></div><div v-if="revision.reasonCode"><dt>原因码</dt><dd>{{ revision.reasonCode }}</dd></div><div v-if="revision.comment"><dt>审计说明</dt><dd>{{ revision.comment }}</dd></div></dl></article></el-timeline-item></el-timeline>
    </el-drawer>
  </section>
</template>

<style scoped>
.page-description{margin:6px 0 0;color:var(--muted);font-size:13px}.access-heading{align-items:center}.heading-actions{display:flex;gap:8px}.access-metrics{display:grid;grid-template-columns:repeat(3,minmax(160px,1fr));margin-bottom:12px;overflow:hidden;border:1px solid var(--line);border-radius:11px;background:#fff;box-shadow:0 4px 16px rgba(30,61,102,.04)}.access-metrics article{min-width:0;padding:14px 17px;border-right:1px solid var(--line)}.access-metrics article:last-child{border-right:0}.access-metrics span,.access-metrics strong,.access-metrics small{display:block}.access-metrics span{color:var(--muted);font-size:13px}.access-metrics strong{margin-top:5px;color:#233a58;font-size:22px}.access-metrics small{margin-top:3px;color:#8491a4;font-size:13px}.access-metrics .success strong{color:var(--success)}.access-metrics .warning strong{color:var(--warning)}.access-metrics .manifest strong{overflow:hidden;text-overflow:ellipsis;font-size:15px;white-space:nowrap}.access-tabs{padding:0 14px 14px;border:1px solid var(--line);border-radius:11px;background:#fff}.tab-label{display:inline-flex;align-items:center;gap:6px}.role-workspace{display:grid;grid-template-columns:270px minmax(0,1fr);min-height:730px;overflow:hidden;border:1px solid var(--line);border-radius:10px}.role-list-panel{display:flex;min-width:0;flex-direction:column;border-right:1px solid var(--line);background:#f8fafc}.role-list-panel>header{display:flex;align-items:center;justify-content:space-between;padding:13px 14px;border-bottom:1px solid var(--line)}.role-list-panel>header div,.role-list-panel>header strong,.role-list-panel>header span{display:block}.role-list-panel>header span{margin-top:2px;color:var(--muted);font-size:13px}.role-filters{display:grid;gap:8px;padding:10px}.role-filters :deep(.el-segmented){--el-segmented-item-selected-bg-color:#fff;font-size:13px}.role-list{min-height:0;max-height:650px;overflow:auto;padding:0 8px 12px}.role-list>button{width:100%;display:grid;grid-template-columns:34px minmax(0,1fr) auto;align-items:center;gap:9px;margin-bottom:6px;padding:11px;border:1px solid transparent;border-radius:9px;color:#2b405c;background:transparent;text-align:left;cursor:pointer}.role-list>button:hover{background:#f0f5fb}.role-list>button.active{border-color:#b9d3f8;background:#eaf3ff;box-shadow:0 2px 9px rgba(23,105,224,.08)}.role-list>button.retired{opacity:.68}.role-avatar{width:34px;height:34px;display:grid;place-items:center;border-radius:9px;color:#326eb4;background:#e7f0fb}.role-copy,.role-copy strong,.role-copy small,.role-copy em{min-width:0;display:block}.role-copy strong,.role-copy small,.role-copy em{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.role-copy strong{font-size:13px}.role-copy small{margin-top:2px;color:#7a899e;font-size:13px}.role-copy em{margin-top:5px;color:#58708e;font-size:13px;font-style:normal}.role-detail-panel{min-width:0;background:#fff}.role-detail-heading{min-height:87px;display:flex;align-items:center;justify-content:space-between;gap:16px;padding:14px 17px;border-bottom:1px solid var(--line)}.role-detail-heading p,.role-detail-heading h2,.role-detail-heading span{margin:0}.role-detail-heading p{color:var(--brand-600);font-size:13px;font-weight:600}.role-detail-heading h2{display:flex;align-items:center;gap:8px;margin-top:3px;font-size:18px}.role-detail-heading>div>span{display:block;margin-top:5px;color:var(--muted);font-size:13px}.role-actions{display:flex;align-items:center;justify-content:flex-end;gap:7px;flex-wrap:wrap}.role-summary{display:grid;grid-template-columns:repeat(4,1fr);margin:12px 14px 0;border:1px solid var(--line);border-radius:9px;background:#f8fafc}.role-summary article{min-width:0;padding:12px 14px;border-right:1px solid var(--line)}.role-summary article:last-child{border-right:0}.role-summary span,.role-summary strong,.role-summary small{display:block}.role-summary span{color:var(--muted);font-size:13px}.role-summary strong{margin-top:5px;overflow:hidden;text-overflow:ellipsis;color:#263e5c;font-size:16px;white-space:nowrap}.role-summary small{margin-top:3px;overflow:hidden;text-overflow:ellipsis;color:#8592a4;font-size:13px;white-space:nowrap}.role-description{margin:9px 14px 0;padding:9px 12px;border-radius:7px;color:#47617f;background:#f1f6fd;font-size:13px}.catalog-workbench{display:block;margin:12px 14px 14px;border:1px solid var(--line);border-radius:9px;overflow:hidden}.catalog-workbench>header{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:12px 14px;border-bottom:1px solid var(--line)}.catalog-workbench h3{margin:0;font-size:14px}.catalog-workbench header p{margin:4px 0 0;color:var(--muted);font-size:13px}.catalog-legend{display:flex;align-items:center;gap:10px;color:var(--muted);font-size:13px}.catalog-legend span{display:flex;align-items:center;gap:4px}.catalog-legend i{width:7px;height:7px;border-radius:50%}.legend-source{background:#8aa4c3}.legend-enforced{background:var(--success)}.legend-blocked{background:var(--warning)}.client-tabs{padding:0 12px}.client-tabs :deep(.el-tabs__header){margin:0}.client-tab-label{display:inline-flex;align-items:center;gap:5px}.catalog-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:9px 12px;border-top:1px solid #edf1f6;border-bottom:1px solid #edf1f6;background:#f8fafc}.catalog-toolbar .el-input{max-width:340px}.catalog-toolbar span{color:var(--muted);font-size:13px}.catalog-grid{display:grid;grid-template-columns:minmax(360px,1fr) 250px;min-height:470px}.permission-tree-wrap{min-width:0;padding:6px;border-right:1px solid var(--line)}.permission-tree-wrap :deep(.el-tree-node__content){height:38px!important;border-radius:6px}.permission-tree-wrap :deep(.el-tree-node__content:hover){background:#f0f5fc}.permission-node{min-width:0;width:100%;display:flex;align-items:center;justify-content:space-between;gap:8px;padding-right:8px}.node-label{overflow:hidden;text-overflow:ellipsis;color:#3c516c;font-size:13px;white-space:nowrap}.permission-node.matched .node-label{padding:1px 3px;border-radius:3px;color:#124f9f;background:#fff0b8;font-weight:600}.node-meta{display:flex;align-items:center;gap:4px}.granted-icon{color:var(--success)}.permission-inspector{min-width:0;padding:17px;background:#fafbfd}.inspector-kicker{color:var(--brand-600);font-size:13px;font-weight:600;letter-spacing:.05em}.permission-inspector h4{margin:7px 0 4px;color:#263d5a;font-size:16px}.permission-inspector>p{margin:0 0 15px;word-break:break-all;color:var(--muted);font-size:13px;line-height:1.55}.permission-inspector dl{margin:0 0 14px}.permission-inspector dl>div{display:grid;grid-template-columns:70px minmax(0,1fr);gap:7px;padding:9px 0;border-bottom:1px solid var(--line)}.permission-inspector dt{color:var(--muted);font-size:13px}.permission-inspector dd{min-width:0;margin:0;color:#304965;font-size:13px}.permission-inspector code{word-break:break-all}.role-empty{display:grid;place-items:center}.account-workspace{min-height:620px;border:1px solid var(--line);border-radius:10px;overflow:hidden}.account-workspace>header{min-height:76px;display:flex;align-items:center;justify-content:space-between;gap:20px;padding:13px 16px;border-bottom:1px solid var(--line);background:#f8fafc}.account-workspace h2{margin:0;font-size:16px}.account-workspace header p{margin:4px 0 0;color:var(--muted);font-size:13px}.account-workspace header .el-input{width:300px}.account-table{border-width:0!important}.account-identity{display:flex;align-items:center;gap:9px}.account-copy,.account-identity strong,.account-identity small{display:block}.account-identity strong{font-size: 13px}.account-identity small{margin-top:2px;color:var(--muted);font-size:13px}.role-tags{display:flex;gap:5px;flex-wrap:wrap}.role-form{margin-top:14px}.role-form .el-select{width:100%}.client-selector{width:100%;display:grid;grid-template-columns:1fr 1fr;gap:8px}.client-selector label{min-width:0;padding:10px 12px;border:1px solid var(--line);border-radius:8px;background:#fff}.client-selector label.selected{border-color:#a7caf7;background:#f2f7ff}.client-selector label.blocked{background:#f7f7f8}.client-selector label>span{display:block;margin:3px 0 0 24px;color:var(--muted);font-size:13px;line-height:1.45}.grant-editor{width:100%;overflow:hidden;border:1px solid var(--line);border-radius:9px}.grant-editor>header{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;border-bottom:1px solid var(--line);background:#f8fafc}.grant-editor header strong,.grant-editor header span{display:block}.grant-editor header strong{font-size: 13px}.grant-editor header span{margin-top:2px;color:var(--muted);font-size:13px}.grant-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 10px;border-bottom:1px solid var(--line);background:#fff}.grant-toolbar .el-input{max-width:360px}.grant-toolbar>span{color:var(--muted);font-size:13px;white-space:nowrap}.grant-editor :deep(.el-tree){padding:6px}.grant-node{width:100%;display:flex;align-items:center;justify-content:space-between;gap:8px;padding-right:10px}.grant-node span{overflow:hidden;text-overflow:ellipsis;font-size:13px;white-space:nowrap}.grant-node small{color:var(--muted);font-size:13px}.account-role-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:14px}.account-role-grid>label{padding:11px;border:1px solid var(--line);border-radius:8px}.account-role-grid label>span{display:block;margin:4px 0 0 24px;color:var(--muted);font-size:13px}.role-history{margin-top:22px;padding-right:8px}.role-history article{padding:12px;border:1px solid var(--line);border-radius:9px;background:#fff}.role-history article header{display:flex;align-items:center;justify-content:space-between}.role-history article p{margin:6px 0;color:var(--muted);font-size:13px}.role-history article dl{margin:8px 0 0;padding:7px 10px;border-radius:7px;background:#f7f9fc}.role-history article dl>div{display:grid;grid-template-columns:72px 1fr;gap:8px;padding:3px 0}.role-history dt{color:var(--muted);font-size:13px}.role-history dd{margin:0;color:#3b506d;font-size:13px}
.account-identity > .el-avatar{display:inline-flex;align-items:center;justify-content:center}
@media(max-width:1350px){.access-metrics{grid-template-columns:repeat(3,1fr)}.catalog-grid{grid-template-columns:minmax(330px,1fr) 220px}.role-summary{grid-template-columns:1fr 1fr}.role-summary article:nth-child(2){border-right:0}.role-summary article:nth-child(-n+2){border-bottom:1px solid var(--line)}}
</style>
