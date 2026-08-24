<script setup lang="ts">
import { Download, Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, errorMessage } from '../api'
import { localDateString } from '../presentation'
import { useAuthStore } from '../stores/auth'

type Row = Record<string, any>
type Page<T> = { items: T[]; nextCursor?: string; hasMore: boolean; total: number }
type Options = { people: { id: string; employeeNumber: string; name: string; departmentName: string }[]; specialties: string[]; filePurpose: string }
type StoredFile = { id: string; originalName: string; mediaType: string; purpose: string }

const route = useRoute()
const auth = useAuthStore()
const canAdmin = computed(() => auth.user?.permissions.includes('PERSON_ADMIN'))
const canImport = computed(() => auth.user?.permissions.includes('PERSON_IMPORT'))
const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)
const rows = ref<Row[]>([])
const options = ref<Options>({ people: [], specialties: [], filePurpose: '' })
const selectedRows = ref<Row[]>([])
const qualificationTable = ref<any>()
const total = ref(0)
const cursor = ref('')
const nextCursor = ref('')
const cursorHistory = ref<string[]>([])
const pageSize = ref(20)
const drawer = ref(false)
const readOnly = ref(false)
const editing = ref<Row>()
const importDialog = ref(false)
const importFile = ref<File>()
const form = reactive<Row>({})
const filters = reactive({ status: '', specialty: '', hasFile: '', issuer: '', personName: '' })

const statuses = [
  ['DRAFT', '草稿'], ['SUBMITTED', '已提交'], ['RETURNED', '已退回'], ['VALID', '有效'],
  ['SUSPENDED', '已暂停'], ['EXPIRED', '已到期'], ['REVOKED', '已撤销'], ['ARCHIVED', '已归档'],
]
const labels: Record<string, string> = {
  ...Object.fromEntries(statuses), SUBMIT: '提交', WITHDRAW: '撤回提交', APPROVE: '审核通过',
  RETURN: '退回', SUSPEND: '暂停资格', REVOKE: '撤销资格', EXPIRE: '标记到期',
  ACTIVATE: '恢复有效', ARCHIVE: '归档', YES: '已上传', NO: '未上传',
}
const reasonActions = new Set(['WITHDRAW', 'RETURN', 'SUSPEND', 'REVOKE', 'EXPIRE', 'ARCHIVE'])
const selectionCount = computed(() => selectedRows.value.length)

function label(value?: string) { return value ? labels[value] ?? value : '—' }
function tagType(value?: string) {
  if (value === 'VALID') return 'success'
  if (['REVOKED', 'ARCHIVED'].includes(value ?? '')) return 'info'
  if (['RETURNED', 'EXPIRED'].includes(value ?? '')) return 'danger'
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
    const page = await api<Page<Row>>(`/api/person-qualifications?${params()}`)
    rows.value = page.items; total.value = page.total; nextCursor.value = page.nextCursor ?? ''
    await nextTick(); qualificationTable.value?.clearSelection(); selectedRows.value = []
  } catch (error) { showError(error) } finally { loading.value = false }
}
function search() { cursor.value = ''; cursorHistory.value = []; load() }
function reset() { Object.assign(filters, { status: '', specialty: '', hasFile: '', issuer: '', personName: '' }); search() }
function nextPage() { if (nextCursor.value) { cursorHistory.value.push(cursor.value); cursor.value = nextCursor.value; load() } }
function previousPage() { if (cursorHistory.value.length) { cursor.value = cursorHistory.value.pop() ?? ''; load() } }
function onSelectionChange(value: Row[]) { selectedRows.value = value }
function canBatch(action: string) { return selectedRows.value.length > 0 && selectedRows.value.every(row => row.allowedActions?.includes(action)) }

function blankForm() {
  Object.keys(form).forEach(key => delete form[key])
  const today = new Date(); const future = new Date(); future.setFullYear(future.getFullYear() + 1)
  Object.assign(form, { personId: options.value.people[0]?.id ?? '', qualificationCode: '', certificateNumber: '',
    validFrom: localDateString(today), validUntil: localDateString(future), issuingAuthority: '',
    practiceNumber: '', practiceOrganization: '', specialties: [], certificateFileId: null,
    certificateFileName: '', remark: '' })
}
function openCreate() { editing.value = undefined; readOnly.value = false; blankForm(); drawer.value = true }
async function openDetail(row: Row, edit = false) {
  try {
    const detail = await api<Row>(`/api/person-qualifications/${row.id}`)
    editing.value = detail; blankForm(); Object.assign(form, detail)
    readOnly.value = !edit || !['DRAFT', 'RETURNED'].includes(detail.status); drawer.value = true
  } catch (error) { showError(error) }
}
function openReadOnly(row: Row) { return openDetail(row, false) }
function editSelected() {
  if (selectedRows.value.length !== 1) return ElMessage.warning('请选择且仅选择一条资格')
  openDetail(selectedRows.value[0], true)
}
async function save() {
  if (!form.personId || !form.qualificationCode || !form.certificateNumber || !form.validFrom || !form.validUntil
      || !form.issuingAuthority || !form.practiceNumber || !form.practiceOrganization || !form.specialties?.length) {
    return ElMessage.warning('请完整填写必填信息并选择专业详情')
  }
  saving.value = true
  try {
    const body = { personId: form.personId, qualificationCode: form.qualificationCode,
      certificateNumber: form.certificateNumber, validFrom: form.validFrom, validUntil: form.validUntil,
      issuingAuthority: form.issuingAuthority, practiceNumber: form.practiceNumber,
      practiceOrganization: form.practiceOrganization, specialties: form.specialties,
      certificateFileId: form.certificateFileId || null, remark: form.remark,
      ...(editing.value ? { expectedVersion: editing.value.version } : {}) }
    await api(editing.value ? `/api/person-qualifications/${editing.value.id}` : '/api/person-qualifications', {
      method: editing.value ? 'PUT' : 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body),
    })
    drawer.value = false; ElMessage.success(editing.value ? '人员资格草稿已更新' : '人员资格草稿已建立'); await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function transition(row: Row, action: string) {
  let comment = ''
  try {
    if (reasonActions.has(action)) {
      comment = (await ElMessageBox.prompt(`请填写“${label(action)}”原因`, label(action), {
        inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空',
      })).value
    } else {
      await ElMessageBox.confirm(`确认执行“${label(action)}”吗？`, '资格状态确认', { type: 'warning' })
    }
    await api(`/api/person-qualifications/${row.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: 'QUALIFICATION_CONTROL', comment }) })
    ElMessage.success(`已${label(action)}`); await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}
async function batchTransition(action: string) {
  if (!canBatch(action)) return ElMessage.warning('所选资格的当前状态不支持该操作')
  let comment = ''
  try {
    if (reasonActions.has(action)) {
      comment = (await ElMessageBox.prompt(`请填写批量${label(action)}原因`, `批量${label(action)}`, {
        inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空',
      })).value
    } else {
      await ElMessageBox.confirm(`确认对已选 ${selectionCount.value} 条资格执行“${label(action)}”吗？`, `批量${label(action)}`, { type: 'warning' })
    }
    await api('/api/person-qualifications/batch-transitions', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, items: selectedRows.value.map(row => ({ id: row.id, expectedVersion: row.version })),
        reasonCode: 'QUALIFICATION_BATCH_CONTROL', comment }) })
    ElMessage.success(`已完成 ${selectionCount.value} 条资格的${label(action)}`); await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}
async function uploadCertificate(event: Event) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = ''
  if (!file) return
  uploading.value = true
  try {
    const body = new FormData(); body.append('file', file); body.append('purpose', options.value.filePurpose)
    const stored = await api<StoredFile>('/api/files', { method: 'POST', body })
    form.certificateFileId = stored.id; form.certificateFileName = stored.originalName
    ElMessage.success('资格照片已安全上传')
  } catch (error) { showError(error) } finally { uploading.value = false }
}
async function downloadFile(id: string, name: string) {
  try {
    const response = await fetch(`/api/files/${id}`, { credentials: 'same-origin' })
    if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.detail ?? '下载失败') }
    const blob = await response.blob(); const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = name; anchor.click(); URL.revokeObjectURL(url)
  } catch (error) { showError(error) }
}
async function downloadTemplate() {
  saving.value = true
  try {
    const stored = await api<StoredFile>('/api/person-qualifications/import-template', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } })
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
    const result = await api<{ importedCount: number }>('/api/person-qualifications/imports', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body,
    })
    importDialog.value = false; importFile.value = undefined
    ElMessage.success(`已整批导入 ${result.importedCount} 条人员资格`); await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}

onMounted(async () => {
  try {
    options.value = await api<Options>('/api/person-qualifications/options')
    const personId = String(route.query.personId ?? '')
    const person = options.value.people.find(item => item.id === personId)
    if (person) filters.personName = person.employeeNumber
    await load()
  } catch (error) { showError(error) }
})
</script>

<template>
  <section class="page qualification-page">
    <header class="page-heading">
      <div><h1>人员资格</h1><p>管理资格证书、执业信息、专业范围和证书附件。</p></div>
      <div class="heading-actions"><el-button v-if="canImport" :icon="Upload" @click="importDialog=true">批量导入</el-button><el-button v-if="canAdmin" type="primary" :icon="Plus" @click="openCreate">新增资格</el-button></div>
    </header>

    <section class="qualification-filter">
      <el-select v-model="filters.status" clearable placeholder="资质状态"><el-option v-for="item in statuses" :key="item[0]" :value="item[0]" :label="item[1]" /></el-select>
      <el-select v-model="filters.specialty" clearable filterable placeholder="专业详情"><el-option v-for="item in options.specialties" :key="item" :value="item" :label="item" /></el-select>
      <el-select v-model="filters.hasFile" clearable placeholder="是否上传文件"><el-option label="已上传" value="YES" /><el-option label="未上传" value="NO" /></el-select>
      <el-input v-model="filters.issuer" clearable placeholder="发证单位" @keyup.enter="search" />
      <el-input v-model="filters.personName" clearable :prefix-icon="Search" placeholder="姓名或工号" @keyup.enter="search" />
      <el-button type="primary" :icon="Search" @click="search">查询</el-button><el-button :icon="Refresh" @click="reset">重置</el-button>
    </section>

    <section v-if="canAdmin" class="personnel-selection-bar qualification-selection-bar">
      <span>已选 <strong>{{ selectionCount }}</strong> 条</span>
      <el-button :disabled="selectionCount!==1 || !['DRAFT','RETURNED'].includes(selectedRows[0]?.status)" @click="editSelected">修改</el-button>
      <el-button :disabled="!canBatch('SUBMIT')" @click="batchTransition('SUBMIT')">提交</el-button>
      <el-button :disabled="!canBatch('WITHDRAW')" @click="batchTransition('WITHDRAW')">撤回提交</el-button>
      <el-button :disabled="!canBatch('ARCHIVE')" @click="batchTransition('ARCHIVE')">归档（代替删除）</el-button>
      <el-dropdown trigger="click" :disabled="!selectionCount" @command="batchTransition"><el-button :disabled="!selectionCount">审核与状态</el-button><template #dropdown><el-dropdown-menu>
        <el-dropdown-item command="APPROVE" :disabled="!canBatch('APPROVE')">审核通过</el-dropdown-item>
        <el-dropdown-item command="RETURN" :disabled="!canBatch('RETURN')">退回</el-dropdown-item>
        <el-dropdown-item command="SUSPEND" :disabled="!canBatch('SUSPEND')">暂停资格</el-dropdown-item>
        <el-dropdown-item command="ACTIVATE" :disabled="!canBatch('ACTIVATE')">恢复有效</el-dropdown-item>
        <el-dropdown-item command="EXPIRE" :disabled="!canBatch('EXPIRE')">标记到期</el-dropdown-item>
        <el-dropdown-item command="REVOKE" :disabled="!canBatch('REVOKE')">撤销资格</el-dropdown-item>
      </el-dropdown-menu></template></el-dropdown>
    </section>

    <el-table ref="qualificationTable" v-loading="loading" :data="rows" border stripe class="module-table qualification-table" row-key="id" @selection-change="onSelectionChange" @row-dblclick="openReadOnly">
      <el-table-column v-if="canAdmin" type="selection" fixed width="44" />
      <el-table-column fixed label="证书编号" width="190"><template #default="scope"><button class="qualification-certificate" @click="openDetail(scope.row, false)"><strong>{{ scope.row.certificateNumber }}</strong><span>{{ scope.row.qualificationCode }}</span></button></template></el-table-column>
      <el-table-column label="姓名" width="155"><template #default="scope"><strong>{{ scope.row.personName }}</strong><small>{{ scope.row.employeeNumber }}</small></template></el-table-column>
      <el-table-column label="有效期" width="210"><template #default="scope">{{ scope.row.validFrom }} 至 {{ scope.row.validUntil }}</template></el-table-column>
      <el-table-column prop="issuingAuthority" label="发证单位" min-width="180" show-overflow-tooltip />
      <el-table-column label="专业详情" min-width="260"><template #default="scope"><div class="qualification-tags"><el-tag v-for="item in scope.row.specialties" :key="item" size="small" effect="plain">{{ item }}</el-tag><span v-if="!scope.row.specialties?.length">未采集</span></div></template></el-table-column>
      <el-table-column label="资质文件" width="120"><template #default="scope"><el-button v-if="scope.row.certificateFileId" link type="primary" :icon="Download" @click="downloadFile(scope.row.certificateFileId, scope.row.certificateFileName)">查看</el-button><span v-else>未上传</span></template></el-table-column>
      <el-table-column label="状态" width="105" align="center"><template #default="scope"><el-tag :type="tagType(scope.row.status)">{{ label(scope.row.status) }}</el-tag></template></el-table-column>
      <el-table-column fixed="right" label="操作" width="155" align="center"><template #default="scope"><el-button link type="primary" @click="openDetail(scope.row, false)">详情</el-button><el-dropdown v-if="canAdmin && scope.row.allowedActions?.length" @command="transition(scope.row, String($event))"><el-button link type="primary">更多操作</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="action in scope.row.allowedActions" :key="action" :command="action">{{ label(action) }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></el-table-column>
      <template #empty><el-empty description="没有符合条件的人员资格" /></template>
    </el-table>
    <footer class="table-footer"><span>共 {{ total }} 条 · 双击进入只读详情</span><div class="cursor-pagination"><el-select v-model="pageSize" @change="search"><el-option :value="20" label="20 条/页" /><el-option :value="50" label="50 条/页" /><el-option :value="100" label="100 条/页" /></el-select><el-button :disabled="!cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="!nextCursor" @click="nextPage">下一页</el-button></div></footer>

    <el-drawer v-model="drawer" :title="editing ? `${editing.personName} · ${editing.certificateNumber}` : '新增人员资格'" size="760px" :close-on-click-modal="false">
      <el-alert v-if="readOnly" title="当前为只读详情。已提交内容不可原地覆盖；仅草稿或已退回记录可修改。" type="info" :closable="false" show-icon />
      <el-form label-position="top" :disabled="readOnly" class="qualification-form">
        <div class="form-grid"><el-form-item label="姓名" required><el-select v-model="form.personId" filterable><el-option v-for="person in options.people" :key="person.id" :value="person.id" :label="`${person.name} · ${person.employeeNumber} · ${person.departmentName}`" /></el-select></el-form-item><el-form-item label="资格代码（任务门禁）" required><el-input v-model="form.qualificationCode" maxlength="80" /></el-form-item></div>
        <el-form-item label="资质证书编号" required><el-input v-model="form.certificateNumber" maxlength="100" /></el-form-item>
        <div class="form-grid"><el-form-item label="生效日期" required><el-date-picker v-model="form.validFrom" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="有效期至" required><el-date-picker v-model="form.validUntil" value-format="YYYY-MM-DD" /></el-form-item></div>
        <el-form-item label="发证单位" required><el-input v-model="form.issuingAuthority" maxlength="200" /></el-form-item>
        <div class="form-grid"><el-form-item label="执业编号" required><el-input v-model="form.practiceNumber" maxlength="120" /></el-form-item><el-form-item label="执业单位" required><el-input v-model="form.practiceOrganization" maxlength="200" /></el-form-item></div>
        <el-form-item label="专业详情" required><el-checkbox-group v-model="form.specialties" class="qualification-specialty-grid"><el-checkbox v-for="item in options.specialties" :key="item" :value="item">{{ item }}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="2000" show-word-limit /></el-form-item>
        <el-form-item label="资质照片"><div class="qualification-file"><label v-if="!readOnly"><input type="file" accept="image/png,image/jpeg,image/bmp" @change="uploadCertificate" /><el-icon><Upload /></el-icon>{{ uploading ? '上传中…' : form.certificateFileName || '选择 JPG、PNG 或 BMP（不超过3MB）' }}</label><el-button v-if="form.certificateFileId" link type="primary" :icon="Download" @click="downloadFile(form.certificateFileId, form.certificateFileName)">下载当前照片</el-button></div></el-form-item>
      </el-form>
      <template #footer><el-button @click="drawer=false">关闭</el-button><el-button v-if="!readOnly && canAdmin" type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
    </el-drawer>

    <el-dialog v-model="importDialog" title="人员资格批量导入" width="600px">
      <el-alert title="只接受本系统受控模板。服务端先校验全部行，任一错误则整表零写入，照片在草稿详情中单独受控上传。" type="info" :closable="false" show-icon />
      <div class="import-actions"><el-button :loading="saving" @click="downloadTemplate">下载受控模板</el-button><label class="file-picker"><input type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="chooseImport" /><el-icon><Upload /></el-icon>{{ importFile?.name || '选择已填写模板' }}</label></div>
      <template #footer><el-button @click="importDialog=false">取消</el-button><el-button type="primary" :disabled="!importFile" :loading="saving" @click="submitImport">完整校验并导入</el-button></template>
    </el-dialog>
  </section>
</template>
