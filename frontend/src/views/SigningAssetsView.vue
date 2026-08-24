<script setup lang="ts">
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError, api, errorMessage } from '../api'
import { localDateString } from '../presentation'

interface Option { id: string; code: string; label: string }
interface ReferenceData { people: Option[] }
interface SigningAsset {
  id: string; assetType: 'SIGNATURE' | 'SEAL'; ownerPersonId?: string; ownerName?: string; name: string
  engineeringType?: string; sealType?: string; validFrom: string; validUntil: string; mediaType: string
  sha256: string; pixelWidth: number; pixelHeight: number; verificationState: string; active: boolean
  version: number; verifiedAt?: string; createdAt: string; updatedAt: string; allowedActions: string[]
}

const route = useRoute()
const moduleKey = computed(() => String(route.params.moduleKey))
const assetType = computed<'SIGNATURE' | 'SEAL'>(() => moduleKey.value === 'person-signatures' ? 'SIGNATURE' : 'SEAL')
const title = computed(() => assetType.value === 'SIGNATURE' ? '人员签章' : '机构签章')
const subtitle = computed(() => assetType.value === 'SIGNATURE'
  ? '人员签名图片、有效期、内部认证与启用状态'
  : '机构印章类型、适用工程、有效期和内部认证')
const loading = ref(false)
const rows = ref<SigningAsset[]>([])
const people = ref<Option[]>([])
const filters = reactive<{ q: string; createdRange: string[]; verificationState: string; active: string }>({ q: '', createdRange: [], verificationState: '', active: '' })
const currentPage = ref(1)
const pageSize = 10
const pagedRows = computed(() => rows.value.slice((currentPage.value - 1) * pageSize, currentPage.value * pageSize))
const drawer = ref(false)
const saving = ref(false)
const editing = ref<SigningAsset>()
const selectedFile = ref<File>()
const previewUrl = ref('')
const form = reactive({ name: '', ownerPersonId: '', engineeringType: '', sealType: '', validFrom: '', validUntil: '', expectedVersion: 0 })

const actionLabels: Record<string, string> = {
  VERIFY: '认证通过', REJECT: '认证驳回', ACTIVATE: '启用', DEACTIVATE: '停用',
}
const stateLabels: Record<string, string> = { DRAFT: '待认证', VERIFIED: '已认证', REJECTED: '认证驳回' }

function defaultDates() {
  const start = new Date()
  const end = new Date(start)
  end.setFullYear(end.getFullYear() + 1)
  return { validFrom: localDateString(start), validUntil: localDateString(end) }
}

function revokePreview() {
  if (previewUrl.value.startsWith('blob:')) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
}

function resetForm() {
  revokePreview()
  editing.value = undefined
  selectedFile.value = undefined
  Object.assign(form, { name: '', ownerPersonId: '', engineeringType: '', sealType: '', ...defaultDates(), expectedVersion: 0 })
}

async function load() {
  loading.value = true
  try {
    const params = new URLSearchParams({ assetType: assetType.value })
    if (filters.q.trim()) params.set('q', filters.q.trim())
    if (filters.createdRange.length === 2) {
      params.set('createdFrom', filters.createdRange[0])
      params.set('createdTo', filters.createdRange[1])
    }
    if (filters.verificationState) params.set('verificationState', filters.verificationState)
    if (filters.active) params.set('active', filters.active)
    const [assets, reference] = await Promise.all([
      api<SigningAsset[]>(`/api/signing-assets?${params}`), api<ReferenceData>('/api/reference-data'),
    ])
    rows.value = assets
    currentPage.value = 1
    people.value = reference.people
  } catch (error) { showError(error) } finally { loading.value = false }
}

function startCreate() {
  resetForm()
  drawer.value = true
}

function startEdit(row: SigningAsset) {
  resetForm()
  editing.value = row
  Object.assign(form, {
    name: row.name, ownerPersonId: row.ownerPersonId ?? '', engineeringType: row.engineeringType ?? '',
    sealType: row.sealType ?? '', validFrom: row.validFrom, validUntil: row.validUntil, expectedVersion: row.version,
  })
  previewUrl.value = `/api/signing-assets/${row.id}/content`
  drawer.value = true
}

function chooseFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > 2 * 1024 * 1024) {
    ElMessage.warning('请选择不超过 2MB 的 PNG 或 JPEG 图片')
    ;(event.target as HTMLInputElement).value = ''
    return
  }
  revokePreview()
  selectedFile.value = file
  previewUrl.value = URL.createObjectURL(file)
}

function validateForm() {
  if (!form.name.trim() || !form.validFrom || !form.validUntil) return '请填写名称和有效期'
  if (form.validUntil < form.validFrom) return '有效期结束日期不能早于开始日期'
  if (assetType.value === 'SIGNATURE' && !form.ownerPersonId) return '请选择签名所属人员'
  if (assetType.value === 'SEAL' && (!form.engineeringType.trim() || !form.sealType.trim())) return '请填写工程类型和印章类型'
  if (!editing.value && !selectedFile.value) return '请选择签章图片'
  return ''
}

async function save() {
  const warning = validateForm()
  if (warning) return ElMessage.warning(warning)
  saving.value = true
  const key = crypto.randomUUID()
  try {
    await withPassword(async () => {
      if (editing.value) {
        await api(`/api/signing-assets/${editing.value.id}`, { method: 'PUT', body: JSON.stringify({
          name: form.name, ownerPersonId: form.ownerPersonId || null, engineeringType: form.engineeringType || null,
          sealType: form.sealType || null, validFrom: form.validFrom, validUntil: form.validUntil,
          expectedVersion: form.expectedVersion,
        }) })
      } else {
        const body = new FormData()
        body.append('assetType', assetType.value)
        body.append('name', form.name)
        if (form.ownerPersonId) body.append('ownerPersonId', form.ownerPersonId)
        if (form.engineeringType) body.append('engineeringType', form.engineeringType)
        if (form.sealType) body.append('sealType', form.sealType)
        body.append('validFrom', form.validFrom)
        body.append('validUntil', form.validUntil)
        body.append('file', selectedFile.value as File)
        await api('/api/signing-assets', { method: 'POST', headers: { 'Idempotency-Key': key }, body })
      }
    })
    ElMessage.success(editing.value ? '签章素材已更新' : '签章素材草稿已创建，请继续认证与启用')
    drawer.value = false
    resetForm()
    await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function transition(row: SigningAsset, action: string) {
  let comment = ''
  try {
    if (['REJECT', 'DEACTIVATE'].includes(action)) {
      comment = (await ElMessageBox.prompt('请填写原因，内容将进入审计记录', actionLabels[action], {
        inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空',
      })).value
    } else {
      await ElMessageBox.confirm(`确认执行“${actionLabels[action]}”？`, title.value, { type: action === 'ACTIVATE' ? 'warning' : 'info' })
    }
    const key = crypto.randomUUID()
    await withPassword(() => api(`/api/signing-assets/${row.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': key },
      body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: 'CONTROLLED_SIGNING', comment }),
    }))
    ElMessage.success(`已完成：${actionLabels[action]}`)
    await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

async function withPassword<T>(operation: () => Promise<T>): Promise<T> {
  try { return await operation() } catch (error) {
    if (!(error instanceof ApiError) || error.code !== 'PASSWORD_REVERIFICATION_REQUIRED') throw error
    const password = (await ElMessageBox.prompt('请输入当前账号密码。密码只用于本次复核，不会保存。', '高风险操作密码复核', {
      inputType: 'password', inputValidator: value => Boolean(value) || '请输入当前密码',
    })).value
    await api('/api/auth/verify-password', { method: 'POST', body: JSON.stringify({ password }) })
    return operation()
  }
}

function showError(error: unknown) {
  ElMessage.error(errorMessage(error))
}

function stateType(row: SigningAsset) {
  if (row.verificationState === 'REJECTED') return 'danger'
  if (row.active) return 'success'
  if (row.verificationState === 'DRAFT') return 'warning'
  return 'info'
}

function resetFilters() {
  Object.assign(filters, { q: '', createdRange: [], verificationState: '', active: '' })
  load()
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai' }).format(new Date(value))
}

function visibleAssetName(row: SigningAsset) {
  return row.assetType === 'SIGNATURE' && row.ownerName ? `${row.ownerName}人员签名` : row.name
}

watch(moduleKey, () => { resetForm(); load() })
onMounted(load)
onBeforeUnmount(revokePreview)
</script>

<template>
  <section class="page signing-page">
    <header class="page-heading">
      <div><h1>{{ title }}</h1><p>{{ subtitle }}</p></div>
      <el-button type="primary" :icon="Plus" @click="startCreate">新增{{ assetType === 'SIGNATURE' ? '签名' : '印章' }}</el-button>
    </header>

    <el-alert title="签章图片加密保存；新素材须完成密码复核、内部认证和启用后，才能进入报告签发链。" type="info" show-icon :closable="false" />
    <section class="module-toolbar signing-toolbar">
      <el-input v-model="filters.q" clearable :placeholder="assetType === 'SIGNATURE' ? '人员或签名名称' : '印章名称'" :prefix-icon="Search" @keyup.enter="load" />
      <el-date-picker v-model="filters.createdRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="创建开始" end-placeholder="创建结束" range-separator="至" />
      <el-select v-model="filters.verificationState" clearable placeholder="认证状态">
        <el-option label="待认证" value="DRAFT" /><el-option label="已认证" value="VERIFIED" /><el-option label="认证驳回" value="REJECTED" />
      </el-select>
      <el-select v-model="filters.active" clearable placeholder="启用状态"><el-option label="已启用" value="true" /><el-option label="未启用" value="false" /></el-select>
      <el-button type="primary" @click="load">查询</el-button><el-button :icon="Refresh" @click="resetFilters">重置</el-button>
    </section>

    <el-table v-loading="loading" :data="pagedRows" border stripe class="module-table signing-assets-table">
      <el-table-column label="素材" width="110" align="center"><template #default="scope"><div class="signing-thumb-cell"><img class="signing-thumb" :src="`/api/signing-assets/${scope.row.id}/content`" :alt="visibleAssetName(scope.row)" /><small>{{ scope.row.pixelWidth }}×{{ scope.row.pixelHeight }}</small></div></template></el-table-column>
      <el-table-column label="名称" min-width="145"><template #default="scope"><span class="cell-two-line" :title="visibleAssetName(scope.row)">{{ visibleAssetName(scope.row) }}</span></template></el-table-column>
      <el-table-column v-if="assetType === 'SIGNATURE'" prop="ownerName" label="所属人员" width="95" />
      <el-table-column v-if="assetType === 'SEAL'" prop="engineeringType" label="工程类型" width="130" show-overflow-tooltip />
      <el-table-column v-if="assetType === 'SEAL'" prop="sealType" label="印章类型" width="115" show-overflow-tooltip />
      <el-table-column label="有效期" width="180"><template #default="scope">{{ scope.row.validFrom }} 至 {{ scope.row.validUntil }}</template></el-table-column>
      <el-table-column label="创建日期" width="100"><template #default="scope">{{ formatDate(scope.row.createdAt) }}</template></el-table-column>
      <el-table-column label="认证/启用" width="110" align="center"><template #default="scope"><div class="table-status-stack"><el-tag :type="stateType(scope.row)">{{ stateLabels[scope.row.verificationState] }}</el-tag><el-tag :type="scope.row.active ? 'success' : 'info'" effect="plain">{{ scope.row.active ? '已启用' : '未启用' }}</el-tag></div></template></el-table-column>
      <el-table-column label="操作" fixed="right" width="165" align="center"><template #default="scope"><div class="table-actions table-actions--center">
        <el-button v-if="scope.row.verificationState === 'DRAFT'" link type="primary" @click="startEdit(scope.row)">修改</el-button>
        <el-button v-for="action in scope.row.allowedActions" :key="action" link :type="['REJECT','DEACTIVATE'].includes(action) ? 'danger' : 'primary'" @click="transition(scope.row, action)">{{ actionLabels[action] }}</el-button>
      </div></template></el-table-column>
      <template #empty><el-empty :description="`暂无${title}记录`" /></template>
    </el-table>
    <footer class="table-footer"><span>共 {{ rows.length }} 条</span><el-pagination v-model:current-page="currentPage" :page-size="pageSize" :total="rows.length" layout="prev, pager, next" background /></footer>

    <el-drawer v-model="drawer" :title="`${editing ? '修改' : '新增'}${title}`" size="min(620px, 92vw)" destroy-on-close @closed="resetForm">
      <el-form label-position="top">
        <div class="form-grid"><el-form-item label="素材名称" required><el-input v-model="form.name" maxlength="160" /></el-form-item>
          <el-form-item v-if="assetType === 'SIGNATURE'" label="所属人员" required><el-select v-model="form.ownerPersonId" filterable><el-option v-for="person in people" :key="person.id" :label="`${person.label}（${person.code}）`" :value="person.id" /></el-select></el-form-item>
          <el-form-item v-else label="工程类型" required><el-input v-model="form.engineeringType" maxlength="80" placeholder="如：水利工程" /></el-form-item>
          <el-form-item v-if="assetType === 'SEAL'" label="印章类型" required><el-select v-model="form.sealType" allow-create filterable><el-option label="检测专用章" value="检测专用章" /><el-option label="机构公章" value="机构公章" /><el-option label="资质认定标志" value="资质认定标志" /></el-select></el-form-item>
        </div>
        <div class="form-grid"><el-form-item label="有效期开始" required><el-date-picker v-model="form.validFrom" type="date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="有效期结束" required><el-date-picker v-model="form.validUntil" type="date" value-format="YYYY-MM-DD" /></el-form-item></div>
        <el-form-item :label="assetType === 'SIGNATURE' ? '签名图片（建议 250×130）' : '印章图片'" :required="!editing">
          <div class="signing-upload">
            <img v-if="previewUrl" :src="previewUrl" alt="待保存签章素材预览" />
            <input v-if="!editing" type="file" accept="image/png,image/jpeg" aria-label="选择签章图片" @change="chooseFile" />
            <p v-if="editing">认证素材的图片内容不可原地替换；如需换图，请新建素材版本。</p>
            <p v-else>PNG/JPEG，最大 2MB，服务端会解析真实图片格式和尺寸。</p>
          </div>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="drawer = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
    </el-drawer>
  </section>
</template>
