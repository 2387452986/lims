<script setup lang="ts">
import { Download, Plus, Refresh, Search, Upload, UserFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { failureReasonLabel } from '../presentation'
import { useAuthStore } from '../stores/auth'

type Row = Record<string, any>
type StoredFile = { id: string; originalName: string; mediaType: string; purpose: string }
type Page<T> = { items: T[]; nextCursor?: string; hasMore: boolean; total: number }
type Options = {
  departments: { id: string; code: string; name: string }[]
  roles: { id: string; code: string; name: string; dataScope: string }[]
  capabilities: { revisionId: string; projectNodeId: string; path: string; symbol?: string }[]
  filePurposes: Record<string, string>
}
type TreeNode = { id: string; label: string; leaf?: boolean; path?: string; children?: TreeNode[] }

const auth = useAuthStore()
const router = useRouter()
const canAdmin = computed(() => auth.user?.permissions.includes('PERSON_ADMIN'))
const canSensitive = computed(() => auth.user?.permissions.includes('PERSON_SENSITIVE_READ'))
const canImport = computed(() => auth.user?.permissions.includes('PERSON_IMPORT'))
const canExport = computed(() => auth.user?.permissions.includes('PERSON_EXPORT'))
const loading = ref(false)
const saving = ref(false)
const uploading = ref('')
const rows = ref<Row[]>([])
const personnelTable = ref<any>()
const total = ref(0)
const cursor = ref('')
const nextCursor = ref('')
const cursorHistory = ref<string[]>([])
const pageSize = ref(20)
const selected = ref<Row>()
const selectedRows = ref<Row[]>([])
const options = ref<Options>({ departments: [], roles: [], capabilities: [], filePurposes: {} })
const drawer = ref(false)
const detailLoading = ref(false)
const activeTab = ref('account')
const capabilityTree = ref<any>()
const capabilityQuery = ref('')
const selectedCapabilityIds = ref<string[]>([])
const permissionDialog = ref(false)
const permissionOverview = ref<Row>()
const accountDialog = ref(false)
const importDialog = ref(false)
const importFile = ref<File>()
const jobsDrawer = ref(false)
const jobs = ref<Row[]>([])
let jobPoll: number | undefined
const accountForm = reactive<Row>({ username: '', password: '', roleIds: [], enabled: true })
const filters = reactive({ accountState: '', applicationState: '', personType: '', name: '', position: '' })
const form = reactive<Row>({})

const accountStates = [['ENABLED', '已启用'], ['DISABLED', '已停用'], ['LOCKED', '已锁定'], ['NO_ACCOUNT', '未开户']]
const applicationStates = [['DRAFT', '草稿'], ['SUBMITTED', '已提交'], ['VERIFIED', '已核验'], ['RETURNED', '已退回'], ['ARCHIVED', '已归档']]
const personTypes = [['EMPLOYEE', '正式员工'], ['CONTRACTOR', '合同人员'], ['EXTERNAL_EXPERT', '外聘专家'], ['INTERN', '实习人员']]
const genderOptions = [['UNKNOWN', '未填写'], ['MALE', '男'], ['FEMALE', '女']]
const transitionLabels: Record<string, string> = {
  ENABLE: '启用', DISABLE: '停用', SUBMIT: '提交申报', WITHDRAW: '撤回申报', VERIFY: '核验通过',
  RETURN: '退回', ARCHIVE: '归档', START_IDENTITY_VERIFICATION: '身份核验',
  OPEN_ELECTRONIC_SIGNATURE: '电子签名开户',
}
const stateLabels: Record<string, string> = {
  ...Object.fromEntries([...accountStates, ...applicationStates, ...personTypes, ...genderOptions]),
  UNVERIFIED: '未核验', VERIFIED: '已核验', FAILED: '核验失败',
  NOT_OPENED: '未开户', OPENED: '已开户', VALID: '有效', SUSPENDED: '已暂停',
  REVOKED: '已撤销', EXPIRED: '已到期', READY: '排队中', RUNNING: '执行中',
  RETRY: '待重试', COMPLETED: '已完成',
}

const treeData = computed<TreeNode[]>(() => {
  const roots: TreeNode[] = []
  const nodes = new Map<string, TreeNode>()
  const query = capabilityQuery.value.trim().toLowerCase()
  for (const item of options.value.capabilities) {
    if (query && !`${item.path} ${item.symbol ?? ''}`.toLowerCase().includes(query)) continue
    let path = ''
    let target = roots
    const parts = item.path.split(' / ')
    parts.forEach((part, index) => {
      path = path ? `${path} / ${part}` : part
      const leaf = index === parts.length - 1
      const id = leaf ? item.revisionId : `group:${path}`
      let node = nodes.get(id)
      if (!node) {
        node = { id, label: leaf && item.symbol ? `${part} · ${item.symbol}` : part, leaf, path, children: leaf ? undefined : [] }
        nodes.set(id, node); target.push(node)
      }
      target = node.children ?? []
    })
  }
  return roots
})
const capabilityCount = computed(() => selectedCapabilityIds.value.length)
const visibleCapabilityIds = computed(() => {
  const ids = new Set<string>()
  const visit = (nodes: TreeNode[]) => nodes.forEach(node => { if (node.leaf) ids.add(node.id); if (node.children) visit(node.children) })
  visit(treeData.value)
  return ids
})
const selectionCount = computed(() => selectedRows.value.length)
const permissionGroups = computed(() => {
  const groups = new Map<string, Row[]>()
  for (const permission of permissionOverview.value?.effectivePermissions ?? []) {
    const items = groups.get(permission.category) ?? []
    items.push(permission); groups.set(permission.category, items)
  }
  return [...groups.entries()].map(([category, permissions]) => ({ category, permissions }))
})

function valueLabel(value?: string) { return value ? stateLabels[value] ?? value : '—' }
function roleNames(codes?: string[]) {
  if (!codes?.length) return '—'
  return codes.map((code) => options.value.roles.find((role) => role.code === code)?.name ?? '未识别角色').join(' / ')
}
function tagType(value?: string) {
  if (['ENABLED', 'VERIFIED', 'ACTIVE', 'OPENED'].includes(value ?? '')) return 'success'
  if (['DISABLED', 'ARCHIVED', 'NO_ACCOUNT'].includes(value ?? '')) return 'info'
  if (['LOCKED', 'RETURNED', 'FAILED'].includes(value ?? '')) return 'danger'
  return 'warning'
}
function showError(error: unknown) {
  ElMessage.error(errorMessage(error))
}
function params() {
  const search = new URLSearchParams({ limit: String(pageSize.value) })
  Object.entries(filters).forEach(([key, value]) => { if (value) search.set(key, value) })
  if (cursor.value) search.set('cursor', cursor.value)
  return search
}
async function load() {
  loading.value = true
  try {
    const page = await api<Page<Row>>(`/api/personnel?${params()}`)
    rows.value = page.items; total.value = page.total; nextCursor.value = page.nextCursor ?? ''
    await nextTick(); personnelTable.value?.clearSelection(); selectedRows.value = []
  } catch (error) { showError(error) } finally { loading.value = false }
}
function search() { cursor.value = ''; cursorHistory.value = []; load() }
function reset() { Object.assign(filters, { accountState: '', applicationState: '', personType: '', name: '', position: '' }); search() }
function nextPage() { if (nextCursor.value) { cursorHistory.value.push(cursor.value); cursor.value = nextCursor.value; load() } }
function previousPage() { if (cursorHistory.value.length) { cursor.value = cursorHistory.value.pop() ?? ''; load() } }

function blankForm() {
  Object.keys(form).forEach(key => delete form[key])
  Object.assign(form, {
    employeeNumber: '', name: '', departmentId: options.value.departments[0]?.id ?? '', personType: 'EMPLOYEE',
    gender: 'UNKNOWN', phone: '', nationalId: '', replaceNationalId: true, birthDate: '', title: '', position: '',
    officeLocation: '', specialty: '', education: '', testingStartedOn: '', laborRelationship: '',
    testingQualification: '', titleIssuer: '', remark: '', avatarFileId: null, idFrontFileId: null,
    idBackFileId: null, socialSecurityFileId: null, capabilityRevisionIds: [], roleIds: [],
    username: '', password: '', accountEnabled: true, attachments: [],
  })
}
async function openCreate() {
  selected.value = undefined; blankForm(); selectedCapabilityIds.value = []; activeTab.value = 'account'; drawer.value = true
  await nextTick(); capabilityTree.value?.setCheckedKeys([])
}
async function openDetail(row: Row) {
  detailLoading.value = true; drawer.value = true; activeTab.value = 'account'
  try {
    const detail = await api<Row>(`/api/personnel/${row.id}`)
    selected.value = detail; blankForm(); Object.assign(form, detail, { replaceNationalId: false, password: '' })
    for (const attachment of detail.attachments ?? []) {
      if (attachment.purpose === options.value.filePurposes.avatar) form.avatarFileName = attachment.originalName
      if (attachment.purpose === options.value.filePurposes.idFront) form.idFrontFileName = attachment.originalName
      if (attachment.purpose === options.value.filePurposes.idBack) form.idBackFileName = attachment.originalName
      if (attachment.purpose === options.value.filePurposes.socialSecurity) form.socialSecurityFileName = attachment.originalName
    }
    selectedCapabilityIds.value = [...(detail.capabilityRevisionIds ?? [])]
    await nextTick(); capabilityTree.value?.setCheckedKeys(selectedCapabilityIds.value)
  } catch (error) { drawer.value = false; showError(error) } finally { detailLoading.value = false }
}
function checkedCapabilities() {
  return selectedCapabilityIds.value
}
function syncCapabilitySelection(_data?: TreeNode, state?: { checkedKeys?: string[] }) {
  const checkedVisible = (state?.checkedKeys ?? capabilityTree.value?.getCheckedKeys(true) ?? [])
    .filter((id: string) => !id.startsWith('group:'))
  const preserved = selectedCapabilityIds.value.filter(id => !visibleCapabilityIds.value.has(id))
  selectedCapabilityIds.value = [...new Set([...preserved, ...checkedVisible])]
}
function onSelectionChange(value: Row[]) { selectedRows.value = value }
function transitionAllowed(row: Row, action: string) {
  if (action === 'ENABLE') return !row.active && row.applicationState !== 'ARCHIVED'
  if (action === 'DISABLE') return row.active
  if (action === 'SUBMIT') return ['DRAFT', 'RETURNED'].includes(row.applicationState)
  if (action === 'WITHDRAW' || action === 'VERIFY') return row.applicationState === 'SUBMITTED'
  if (action === 'RETURN') return ['SUBMITTED', 'VERIFIED'].includes(row.applicationState)
  if (action === 'ARCHIVE') return !row.active && row.applicationState !== 'ARCHIVED'
  return true
}
function canBatch(action: string) {
  return selectedRows.value.length > 0 && selectedRows.value.every(row => transitionAllowed(row, action))
}
async function openSelected(tab: string) {
  if (selectedRows.value.length !== 1) return ElMessage.warning('请选择且仅选择一名人员')
  await openDetail(selectedRows.value[0]); activeTab.value = tab
}
async function openPermissionOverview(row?: Row) {
  const target = row ?? (selectedRows.value.length === 1 ? selectedRows.value[0] : undefined)
  if (!target) return ElMessage.warning('请选择且仅选择一名人员')
  try {
    selected.value = target
    permissionOverview.value = await api<Row>(`/api/personnel/${target.id}/permission-overview`)
    permissionDialog.value = true
  } catch (error) { showError(error) }
}
function handleSelectionMore(action: string) {
  if (action === 'CAPABILITY') return openSelected('capabilities')
  if (action === 'PERMISSION') return openPermissionOverview()
  return batchTransition(action)
}
function handleRowMore(row: Row, action: string) {
  if (action === 'OPEN_ACCOUNT') return openAccount(row)
  if (action === 'PERMISSION_OVERVIEW') return openPermissionOverview(row)
  if (action === 'QUALIFICATION') return openQualifications(row)
  return transition(row, action)
}
async function batchTransition(action: string) {
  if (!canBatch(action)) return ElMessage.warning('所选人员当前状态不支持该操作')
  let comment = ''
  try {
    if (['RETURN', 'DISABLE', 'ARCHIVE'].includes(action)) {
      comment = (await ElMessageBox.prompt(`请填写批量${transitionLabels[action]}原因`, `批量${transitionLabels[action]}`, {
        inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空',
      })).value
    } else {
      await ElMessageBox.confirm(`确认对已选 ${selectionCount.value} 人执行“${transitionLabels[action]}”吗？`,
        `批量${transitionLabels[action]}`, { type: 'warning' })
    }
  } catch (error) { if (error === 'cancel') return; throw error }
  try {
    await api('/api/personnel/batch-transitions', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, items: selectedRows.value.map(row => ({ id: row.id, expectedVersion: row.version })),
        reasonCode: 'PERSONNEL_BATCH_CONTROL', comment }) })
    ElMessage.success(`已完成 ${selectionCount.value} 人的${transitionLabels[action]}`); await load()
  } catch (error) { showError(error) }
}
async function save() {
  if (!form.name || !form.departmentId || !form.personType) return ElMessage.warning('请完成必填档案字段')
  if (!selected.value && (!form.employeeNumber || !form.username || !form.password || !form.roleIds.length)) {
    return ElMessage.warning('新增人员必须填写工号、登录账号、初始密码并分配角色')
  }
  saving.value = true
  try {
    const profile: Row = {
      name: form.name, departmentId: form.departmentId, personType: form.personType, gender: form.gender,
      phone: form.phone, nationalId: form.replaceNationalId ? form.nationalId : null,
      replaceNationalId: Boolean(form.replaceNationalId), birthDate: form.birthDate || null, title: form.title,
      position: form.position, officeLocation: form.officeLocation, specialty: form.specialty, education: form.education,
      testingStartedOn: form.testingStartedOn || null, laborRelationship: form.laborRelationship,
      testingQualification: form.testingQualification, titleIssuer: form.titleIssuer, remark: form.remark,
      avatarFileId: form.avatarFileId || null, idFrontFileId: form.idFrontFileId || null,
      idBackFileId: form.idBackFileId || null, socialSecurityFileId: form.socialSecurityFileId || null,
      capabilityRevisionIds: checkedCapabilities(),
    }
    if (selected.value) {
      Object.assign(profile, { roleIds: form.roleIds, expectedAccountVersion: form.accountVersion, expectedVersion: form.version })
      await api(`/api/personnel/${form.id}`, { method: 'PUT', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(profile) })
    } else {
      Object.assign(profile, { employeeNumber: form.employeeNumber,
        account: { username: form.username, password: form.password, roleIds: form.roleIds, enabled: form.accountEnabled } })
      await api('/api/personnel', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(profile) })
    }
    drawer.value = false; ElMessage.success(selected.value ? '人员档案与账号已同步更新' : '人员档案与账号已创建'); await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function transition(row: Row, action: string) {
  let comment = ''
  if (['RETURN', 'DISABLE', 'ARCHIVE'].includes(action)) {
    try {
      comment = (await ElMessageBox.prompt(`请填写${transitionLabels[action]}原因`, transitionLabels[action], {
        inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空',
      })).value
    } catch (error) { if (error === 'cancel') return; throw error }
  } else {
    try { await ElMessageBox.confirm(`确认执行“${transitionLabels[action]}”吗？`, '状态确认', { type: 'warning' }) }
    catch (error) { if (error === 'cancel') return; throw error }
  }
  try {
    await api(`/api/personnel/${row.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: 'PERSONNEL_CONTROL', comment }) })
    ElMessage.success('人员状态已更新'); await load()
  } catch (error) { showError(error) }
}
async function openAccount(row: Row) {
  selected.value = await api<Row>(`/api/personnel/${row.id}`)
  Object.assign(accountForm, { username: '', password: '', roleIds: [], enabled: true }); accountDialog.value = true
}
async function saveAccount() {
  if (!selected.value || !accountForm.username || !accountForm.password || !accountForm.roleIds.length) return ElMessage.warning('请完整填写开户信息')
  saving.value = true
  try {
    await api(`/api/personnel/${selected.value.id}/account`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ...accountForm, expectedVersion: selected.value.version }) })
    accountDialog.value = false; ElMessage.success('账号已开通，首次登录必须修改密码'); await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function uploadFile(event: Event, idField: string, nameField: string, purpose: string) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = ''
  if (!file) return
  uploading.value = idField
  try {
    const body = new FormData(); body.append('file', file); body.append('purpose', purpose)
    const stored = await api<StoredFile>('/api/files', { method: 'POST', body })
    form[idField] = stored.id; form[nameField] = stored.originalName; ElMessage.success('附件已安全上传')
  } catch (error) { showError(error) } finally { uploading.value = '' }
}
function openQualifications(row: Row) {
  return router.push({ name: 'module', params: { moduleKey: 'qualifications' }, query: { personId: row.id } })
}
async function downloadFile(id: string, fallback: string) {
  try {
    const response = await fetch(`/api/files/${id}`, { credentials: 'same-origin' })
    if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.detail ?? '下载失败') }
    const blob = await response.blob(); const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = fallback; anchor.click(); URL.revokeObjectURL(url)
  } catch (error) { showError(error) }
}
async function downloadTemplate() {
  saving.value = true
  try {
    const stored = await api<StoredFile>('/api/personnel/import-template', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } })
    await downloadFile(stored.id, stored.originalName)
  } catch (error) { showError(error) } finally { saving.value = false }
}
function chooseImport(event: Event) {
  const input = event.target as HTMLInputElement; importFile.value = input.files?.[0]; input.value = ''
}
async function submitImport() {
  if (!importFile.value) return ElMessage.warning('请选择按受控模板填写的 .xlsx 文件')
  saving.value = true
  try {
    const body = new FormData(); body.append('file', importFile.value)
    const result = await api<{ importedCount: number }>('/api/personnel/imports', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body })
    importDialog.value = false; importFile.value = undefined; ElMessage.success(`已整批导入 ${result.importedCount} 人`); await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function loadJobs() {
  if (!canExport.value) return
  try { jobs.value = await api<Row[]>('/api/jobs?resourceType=PERSONNEL_EXPORT&limit=30') }
  catch (error) { showError(error) }
}
function beginJobPolling() {
  if (jobPoll) window.clearInterval(jobPoll)
  jobPoll = window.setInterval(async () => {
    if (!jobsDrawer.value) return
    await loadJobs()
    if (!jobs.value.some(job => ['READY', 'RUNNING', 'RETRY'].includes(job.state))) {
      if (jobPoll) window.clearInterval(jobPoll); jobPoll = undefined
    }
  }, 1500)
}
async function startControlledExport() {
  try {
    await ElMessageBox.confirm('按当前查询条件生成默认脱敏、带可见水印的人员 XLSX，并写入下载审计。确认继续？', '受控人员导出', { type: 'warning' })
    await api('/api/personnel/exports', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ ...filters }) })
    jobsDrawer.value = true; await loadJobs(); beginJobPolling(); ElMessage.success('人员导出作业已排队')
  } catch (error) { if (error !== 'cancel') showError(error) }
}
async function retryJob(job: Row) {
  try {
    const comment = (await ElMessageBox.prompt('请填写重试原因', '重试人员导出', { inputValidator: value => Boolean(value.trim()) || '重试原因不能为空' })).value
    await api(`/api/jobs/${job.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ action: 'RETRY', expectedVersion: job.version, reasonCode: 'PERSONNEL_EXPORT_RETRY', comment }) })
    await loadJobs(); beginJobPolling()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

onMounted(async () => {
  try { options.value = await api<Options>('/api/personnel/options'); await load() } catch (error) { showError(error) }
})
watch(capabilityQuery, async () => { await nextTick(); capabilityTree.value?.setCheckedKeys(selectedCapabilityIds.value) })
onUnmounted(() => { if (jobPoll) window.clearInterval(jobPoll) })
</script>

<template>
  <section class="page personnel-page">
    <header class="page-heading">
      <div><h1>人员信息</h1><p>维护人员档案、账号角色、资格和检测能力。</p></div>
      <div class="heading-actions">
        <el-tooltip content="受控 XLSX 先完整校验，任一行错误则整批零写入" placement="bottom"><el-button v-if="canImport" :icon="Upload" @click="importDialog=true">人员导入</el-button></el-tooltip>
        <el-tooltip content="导出默认脱敏并写入下载审计" placement="bottom"><el-button v-if="canExport" :icon="Download" @click="startControlledExport">人员导出</el-button></el-tooltip>
        <el-button v-if="canAdmin && canSensitive" type="primary" :icon="Plus" @click="openCreate">新增人员</el-button>
      </div>
    </header>

    <section class="personnel-filter">
      <el-select v-model="filters.accountState" clearable placeholder="账号状态"><el-option v-for="item in accountStates" :key="item[0]" :value="item[0]" :label="item[1]" /></el-select>
      <el-select v-model="filters.applicationState" clearable placeholder="申报状态"><el-option v-for="item in applicationStates" :key="item[0]" :value="item[0]" :label="item[1]" /></el-select>
      <el-select v-model="filters.personType" clearable placeholder="人员类型"><el-option v-for="item in personTypes" :key="item[0]" :value="item[0]" :label="item[1]" /></el-select>
      <el-input v-model="filters.name" clearable :prefix-icon="Search" placeholder="姓名、工号或登录账号" @keyup.enter="search" />
      <el-input v-model="filters.position" clearable placeholder="职务或职称" @keyup.enter="search" />
      <el-button type="primary" :icon="Search" @click="search">查询</el-button><el-button :icon="Refresh" @click="reset">重置</el-button>
    </section>

    <section v-if="canAdmin" class="personnel-selection-bar">
      <span>已选 <strong>{{ selectionCount }}</strong> 人</span>
      <el-button :disabled="selectionCount!==1" @click="openSelected('profile')">修改</el-button>
      <el-button :disabled="!canBatch('ENABLE')" @click="batchTransition('ENABLE')">启用</el-button>
      <el-button :disabled="!canBatch('DISABLE')" @click="batchTransition('DISABLE')">停用</el-button>
      <el-button :disabled="selectionCount!==1" @click="openSelected('account')">权限设置</el-button>
      <el-dropdown trigger="click" :disabled="!selectionCount" @command="handleSelectionMore">
        <el-button :disabled="!selectionCount">更多操作</el-button>
        <template #dropdown><el-dropdown-menu>
          <el-dropdown-item command="CAPABILITY" :disabled="selectionCount!==1">参数能力</el-dropdown-item>
          <el-dropdown-item command="PERMISSION" :disabled="selectionCount!==1">权限总览</el-dropdown-item>
          <el-dropdown-item command="SUBMIT" :disabled="!canBatch('SUBMIT')">提交申报</el-dropdown-item>
          <el-dropdown-item command="WITHDRAW" :disabled="!canBatch('WITHDRAW')">撤回申报</el-dropdown-item>
          <el-dropdown-item command="VERIFY" :disabled="!canBatch('VERIFY')">核验通过</el-dropdown-item>
          <el-dropdown-item command="RETURN" :disabled="!canBatch('RETURN')">退回</el-dropdown-item>
          <el-dropdown-item command="START_IDENTITY_VERIFICATION" :disabled="selectionCount!==1">身份核验</el-dropdown-item>
          <el-dropdown-item command="OPEN_ELECTRONIC_SIGNATURE" :disabled="selectionCount!==1">电子签名开户</el-dropdown-item>
          <el-dropdown-item command="ARCHIVE" :disabled="!canBatch('ARCHIVE')" divided>归档（代替删除）</el-dropdown-item>
        </el-dropdown-menu></template>
      </el-dropdown>
    </section>

    <el-table ref="personnelTable" v-loading="loading" :data="rows" border stripe class="module-table personnel-table" row-key="id" @selection-change="onSelectionChange">
      <el-table-column v-if="canAdmin" type="selection" fixed width="44" />
      <el-table-column fixed prop="name" label="人员" width="185"><template #default="scope"><div class="person-summary"><el-avatar :size="34" :src="scope.row.avatarFileId ? `/api/files/${scope.row.avatarFileId}` : undefined" :icon="UserFilled" /><button class="person-name" @click="openDetail(scope.row)"><strong>{{ scope.row.name }}</strong><span :title="scope.row.employeeNumber">{{ scope.row.employeeNumber }}</span></button></div></template></el-table-column>
      <el-table-column prop="username" label="登录账号" width="150" show-overflow-tooltip><template #default="scope"><span class="person-nowrap">{{ scope.row.username || '未开户' }}</span></template></el-table-column>
      <el-table-column prop="gender" label="性别" width="70"><template #default="scope">{{ valueLabel(scope.row.gender) }}</template></el-table-column>
      <el-table-column prop="maskedPhone" label="联系电话" width="125" />
      <el-table-column prop="maskedNationalId" label="身份证件" width="170" />
      <el-table-column prop="age" label="年龄" width="65" />
      <el-table-column prop="birthDate" label="出生日期" width="115" />
      <el-table-column prop="title" label="职称" width="120" />
      <el-table-column prop="position" label="职务" width="120" />
      <el-table-column prop="departmentName" label="所属部门" min-width="150" />
      <el-table-column prop="personType" label="人员类型" width="110"><template #default="scope">{{ valueLabel(scope.row.personType) }}</template></el-table-column>
      <el-table-column label="账号状态" width="100" align="center"><template #default="scope"><div class="table-tag-cell"><el-tag :type="tagType(scope.row.accountState)" size="small">{{ valueLabel(scope.row.accountState) }}</el-tag></div></template></el-table-column>
      <el-table-column label="申报状态" width="100" align="center"><template #default="scope"><div class="table-tag-cell"><el-tag :type="tagType(scope.row.applicationState)" size="small">{{ valueLabel(scope.row.applicationState) }}</el-tag></div></template></el-table-column>
      <el-table-column label="电子签名" width="105" align="center"><template #default="scope"><div class="table-tag-cell"><el-tag :type="tagType(scope.row.electronicSignatureState)" size="small">{{ valueLabel(scope.row.electronicSignatureState) }}</el-tag></div></template></el-table-column>
      <el-table-column prop="roleCodes" label="角色权限" min-width="160" show-overflow-tooltip><template #default="scope">{{ roleNames(scope.row.roleCodes) }}</template></el-table-column>
      <el-table-column prop="capabilityCount" label="检测能力" width="90" />
      <el-table-column fixed="right" label="操作" width="170" align="center"><template #default="scope"><div class="table-actions table-actions--center">
        <el-button link type="primary" @click="openDetail(scope.row)">详情</el-button>
        <el-dropdown v-if="canAdmin" @command="handleRowMore(scope.row, $event)">
          <el-button link type="primary">更多操作</el-button>
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item v-if="!scope.row.username" command="OPEN_ACCOUNT">账号开户</el-dropdown-item>
            <el-dropdown-item command="QUALIFICATION">人员资格</el-dropdown-item>
            <el-dropdown-item v-if="scope.row.username" command="PERMISSION_OVERVIEW">权限总览</el-dropdown-item>
            <el-dropdown-item :command="scope.row.active ? 'DISABLE' : 'ENABLE'">{{ scope.row.active ? '停用' : '启用' }}</el-dropdown-item>
            <el-dropdown-item v-if="['DRAFT','RETURNED'].includes(scope.row.applicationState)" command="SUBMIT">提交申报</el-dropdown-item>
            <el-dropdown-item v-if="scope.row.applicationState==='SUBMITTED'" command="WITHDRAW">撤回申报</el-dropdown-item>
            <el-dropdown-item v-if="scope.row.applicationState==='SUBMITTED'" command="VERIFY">核验通过</el-dropdown-item>
            <el-dropdown-item v-if="['SUBMITTED','VERIFIED'].includes(scope.row.applicationState)" command="RETURN">退回</el-dropdown-item>
            <el-dropdown-item command="START_IDENTITY_VERIFICATION">身份核验</el-dropdown-item>
            <el-dropdown-item command="OPEN_ELECTRONIC_SIGNATURE">电子签名开户</el-dropdown-item>
            <el-dropdown-item v-if="!scope.row.active && scope.row.applicationState!=='ARCHIVED'" command="ARCHIVE" divided>归档</el-dropdown-item>
          </el-dropdown-menu></template>
        </el-dropdown>
      </div></template></el-table-column>
      <template #empty><el-empty description="没有符合条件的人员档案" /></template>
    </el-table>
    <footer class="table-footer"><span>共 {{ total }} 人 · 身份证件与电话默认脱敏</span><div class="cursor-pagination"><el-select v-model="pageSize" @change="search"><el-option :value="20" label="20 条/页" /><el-option :value="50" label="50 条/页" /><el-option :value="100" label="100 条/页" /></el-select><el-button :disabled="!cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="!nextCursor" @click="nextPage">下一页</el-button></div></footer>

    <el-drawer v-model="drawer" :title="selected ? `${selected.name} · 人员档案` : '新增人员及账号'" size="860px" class="personnel-drawer" :close-on-click-modal="false">
      <div v-loading="detailLoading" class="personnel-drawer-body">
        <el-alert v-if="selected && !canSensitive" title="当前角色仅可查看脱敏资料；敏感字段和身份证明附件不可读取或修改。" type="warning" :closable="false" show-icon />
        <el-tabs v-model="activeTab">
          <el-tab-pane label="账号与权限" name="account"><el-form label-position="top">
            <div class="form-grid"><el-form-item label="登录账号" required><el-input v-model="form.username" :disabled="Boolean(selected)" maxlength="80" /></el-form-item><el-form-item v-if="!selected" label="初始密码" required><el-input v-model="form.password" type="password" show-password maxlength="128" autocomplete="new-password" /></el-form-item></div>
            <el-form-item label="角色权限" required><el-checkbox-group v-model="form.roleIds" class="personnel-role-grid"><el-checkbox v-for="role in options.roles" :key="role.id" :value="role.id"><strong>{{ role.name }}</strong><span>{{ role.code }} · {{ role.dataScope }}</span></el-checkbox></el-checkbox-group></el-form-item>
            <el-alert title="新增账号首次登录强制改密；角色或部门变化会撤销既有会话。" type="info" :closable="false" show-icon />
          </el-form></el-tab-pane>
          <el-tab-pane label="基本资料" name="profile"><el-form label-position="top">
            <div class="form-grid"><el-form-item label="人员工号" required><el-input v-model="form.employeeNumber" :disabled="Boolean(selected)" maxlength="40" /></el-form-item><el-form-item label="姓名" required><el-input v-model="form.name" maxlength="120" /></el-form-item></div>
            <div class="form-grid"><el-form-item label="身份证件号码"><el-input v-model="form.nationalId" :disabled="!canSensitive" maxlength="32" @input="form.replaceNationalId=true" /><small>中国居民身份证会自动校验并推导生日、性别和年龄。</small></el-form-item><el-form-item label="联系电话"><el-input v-model="form.phone" :disabled="!canSensitive" maxlength="40" /></el-form-item></div>
            <div class="form-grid"><el-form-item label="性别"><el-select v-model="form.gender"><el-option v-for="item in genderOptions" :key="item[0]" :value="item[0]" :label="item[1]" /></el-select></el-form-item><el-form-item label="出生日期"><el-date-picker v-model="form.birthDate" value-format="YYYY-MM-DD" :disabled="!canSensitive" /></el-form-item></div>
            <div class="form-grid"><el-form-item label="人员类型" required><el-select v-model="form.personType"><el-option v-for="item in personTypes" :key="item[0]" :value="item[0]" :label="item[1]" /></el-select></el-form-item><el-form-item label="所属部门" required><el-select v-model="form.departmentId" filterable><el-option v-for="item in options.departments" :key="item.id" :value="item.id" :label="`${item.code} · ${item.name}`" /></el-select></el-form-item></div>
            <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="2000" show-word-limit /></el-form-item>
          </el-form></el-tab-pane>
          <el-tab-pane label="任职与专业" name="employment"><el-form label-position="top">
            <div class="form-grid"><el-form-item label="职务"><el-input v-model="form.position" maxlength="120" /></el-form-item><el-form-item label="职称"><el-input v-model="form.title" maxlength="120" /></el-form-item></div>
            <div class="form-grid"><el-form-item label="职称发证单位"><el-input v-model="form.titleIssuer" maxlength="240" /></el-form-item><el-form-item label="办公地点"><el-input v-model="form.officeLocation" maxlength="240" /></el-form-item></div>
            <div class="form-grid"><el-form-item label="从事专业"><el-input v-model="form.specialty" maxlength="240" /></el-form-item><el-form-item label="学历"><el-input v-model="form.education" maxlength="80" /></el-form-item></div>
            <div class="form-grid"><el-form-item label="开始检测工作日期"><el-date-picker v-model="form.testingStartedOn" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="劳动关系"><el-input v-model="form.laborRelationship" maxlength="120" /></el-form-item></div>
            <el-form-item label="检测资格说明"><el-input v-model="form.testingQualification" type="textarea" :rows="3" maxlength="500" /></el-form-item>
          </el-form></el-tab-pane>
          <el-tab-pane label="身份与附件" name="evidence"><div class="personnel-evidence-grid">
            <label class="evidence-upload"><span>人员头像</span><strong>{{ form.avatarFileName || '选择 PNG/JPEG' }}</strong><input type="file" accept="image/png,image/jpeg" @change="uploadFile($event,'avatarFileId','avatarFileName',options.filePurposes.avatar)" /><el-icon><Upload /></el-icon></label>
            <label class="evidence-upload" :class="{ disabled: !canSensitive }"><span>身份证正面</span><strong>{{ form.idFrontFileName || '选择 PNG/JPEG/PDF' }}</strong><input type="file" :disabled="!canSensitive" accept="image/png,image/jpeg,application/pdf" @change="uploadFile($event,'idFrontFileId','idFrontFileName',options.filePurposes.idFront)" /><el-icon><Upload /></el-icon></label>
            <label class="evidence-upload" :class="{ disabled: !canSensitive }"><span>身份证背面</span><strong>{{ form.idBackFileName || '选择 PNG/JPEG/PDF' }}</strong><input type="file" :disabled="!canSensitive" accept="image/png,image/jpeg,application/pdf" @change="uploadFile($event,'idBackFileId','idBackFileName',options.filePurposes.idBack)" /><el-icon><Upload /></el-icon></label>
            <label class="evidence-upload" :class="{ disabled: !canSensitive }"><span>社会保险证明</span><strong>{{ form.socialSecurityFileName || '选择 PNG/JPEG/PDF' }}</strong><input type="file" :disabled="!canSensitive" accept="image/png,image/jpeg,application/pdf" @change="uploadFile($event,'socialSecurityFileId','socialSecurityFileName',options.filePurposes.socialSecurity)" /><el-icon><Upload /></el-icon></label>
          </div><el-alert title="附件先经过扩展名、MIME、文件头和安全结构校验，再按 SHA-256 移入不可变文件区。" type="info" :closable="false" show-icon /></el-tab-pane>
          <el-tab-pane label="限定检测项目" name="capabilities"><div class="capability-toolbar"><el-input v-model="capabilityQuery" clearable :prefix-icon="Search" placeholder="搜索项目路径或符号" /><span>已选 {{ capabilityCount }} 项</span></div><el-tree-v2 ref="capabilityTree" :data="treeData" :props="{ value: 'id', label: 'label', children: 'children' }" show-checkbox :height="460" class="capability-tree" @check="syncCapabilitySelection"><template #default="{ data }"><div class="capability-node" :class="{ leaf: data.leaf }"><span>{{ data.label }}</span><small v-if="data.leaf">已发布版本</small></div></template></el-tree-v2></el-tab-pane>
          <el-tab-pane label="版本与状态" name="audit"><div v-if="selected" class="personnel-state-grid"><div><span>档案版本</span><strong>v{{ form.version }}</strong></div><div><span>申报状态</span><el-tag :type="tagType(form.applicationState)">{{ valueLabel(form.applicationState) }}</el-tag></div><div><span>身份核验</span><el-tag :type="tagType(form.identityState)">{{ valueLabel(form.identityState) }}</el-tag></div><div><span>电子签名</span><el-tag :type="tagType(form.electronicSignatureState)">{{ valueLabel(form.electronicSignatureState) }}</el-tag></div></div><el-alert title="状态动作、权限变更、附件读取及失败的外部服务调用均写入结构化审计；归档代替物理删除。" type="info" :closable="false" show-icon /></el-tab-pane>
        </el-tabs>
      </div>
      <template #footer><div class="drawer-footer"><span v-if="selected">工号 {{ selected.employeeNumber }} · {{ selected.departmentName }}</span><span v-else>保存后同时创建人员档案和登录账号</span><div><el-button @click="drawer=false">取消</el-button><el-button v-if="canAdmin && canSensitive" type="primary" :loading="saving" @click="save">保存</el-button></div></div></template>
    </el-drawer>

    <el-dialog v-model="accountDialog" title="人员账号开户" width="560px"><el-form label-position="top"><el-form-item label="登录账号" required><el-input v-model="accountForm.username" maxlength="80" /></el-form-item><el-form-item label="初始密码" required><el-input v-model="accountForm.password" type="password" show-password autocomplete="new-password" /></el-form-item><el-form-item label="角色权限" required><el-checkbox-group v-model="accountForm.roleIds" class="personnel-role-grid"><el-checkbox v-for="role in options.roles" :key="role.id" :value="role.id">{{ role.name }}</el-checkbox></el-checkbox-group></el-form-item></el-form><template #footer><el-button @click="accountDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveAccount">确认开户</el-button></template></el-dialog>

    <el-dialog v-model="importDialog" title="受控人员导入" width="620px"><el-alert title="只接受本系统模板。服务端会拒绝公式，先校验全部行，再在单一事务中整批写入；任何错误都会零写入并返回行号。" type="warning" :closable="false" show-icon /><div class="person-import-actions"><el-button :loading="saving" @click="downloadTemplate">下载受控模板</el-button><label class="file-picker"><input type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="chooseImport" /><el-icon><Upload /></el-icon>{{ importFile?.name || '选择已填写模板' }}</label></div><template #footer><el-button @click="importDialog=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!importFile" @click="submitImport">完整校验并导入</el-button></template></el-dialog>

    <el-drawer v-model="jobsDrawer" title="人员导出作业" size="680px"><el-table :data="jobs" border><el-table-column prop="createdAt" label="创建时间" width="180" /><el-table-column label="状态" width="100" align="center"><template #default="scope"><el-tag :type="tagType(scope.row.state)">{{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column><el-table-column prop="attemptCount" label="尝试" width="70" /><el-table-column label="失败原因" min-width="210" show-overflow-tooltip><template #default="scope">{{ failureReasonLabel(scope.row.lastError, '人员导出作业失败') }}</template></el-table-column><el-table-column label="操作" width="120" align="center"><template #default="scope"><el-button v-if="scope.row.resultFileId" link type="primary" @click="downloadFile(scope.row.resultFileId,'人员信息-脱敏.xlsx')">下载</el-button><el-button v-else-if="scope.row.state==='FAILED'" link type="primary" @click="retryJob(scope.row)">重试</el-button></template></el-table-column><template #empty><el-empty description="暂无人员导出作业" /></template></el-table></el-drawer>

    <el-dialog v-model="permissionDialog" :title="`${selected?.name ?? ''} · 权限总览`" width="920px">
      <el-alert title="下列权限按当前角色实时计算；角色或数据范围变更后，旧会话会被撤销。" type="info" :closable="false" show-icon />
      <div class="permission-role-strip">
        <article v-for="role in permissionOverview?.roles ?? []" :key="role.id"><strong>{{ role.name }}</strong><span>{{ role.code }} · {{ role.dataScope }} · {{ role.permissions.length }} 项</span></article>
      </div>
      <el-empty v-if="!permissionGroups.length" description="当前账号没有实效权限" />
      <div v-else class="permission-overview-grid">
        <section v-for="group in permissionGroups" :key="group.category"><header><strong>{{ group.category }}</strong><span>{{ group.permissions.length }} 项</span></header><div><el-tag v-for="permission in group.permissions" :key="permission.code" type="info" effect="plain"><span :title="permission.code">{{ permission.name }}</span></el-tag></div></section>
      </div>
      <template #footer><el-button type="primary" @click="permissionDialog=false">完成查看</el-button></template>
    </el-dialog>
  </section>
</template>
