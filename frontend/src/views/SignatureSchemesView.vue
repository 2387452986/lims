<script setup lang="ts">
import { Delete, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, api, errorMessage } from '../api'

interface SigningAsset {
  id: string; assetType: 'SIGNATURE' | 'SEAL'; ownerName?: string; name: string
  verificationState: string; active: boolean; validFrom: string; validUntil: string
}
interface Position {
  id?: string; positionType: 'SIGNATURE' | 'SEAL'; signingAssetId: string; assetName?: string
  label: string; pageRule: 'FIRST' | 'LAST' | 'EVERY'; xMm: number; yMm: number
  widthMm: number; heightMm: number; sortOrder: number
}
interface Scheme {
  id: string; seriesId: string; predecessorId?: string; revisionNumber: number; name: string; remark?: string
  state: 'DRAFT' | 'ACTIVE' | 'INACTIVE'; version: number; createdBy: string; createdAt: string; updatedAt: string
  positions: Position[]; allowedActions: string[]
}

const loading = ref(false)
const schemes = ref<Scheme[]>([])
const assets = ref<SigningAsset[]>([])
const drawer = ref(false)
const saving = ref(false)
const editing = ref<Scheme>()
const form = reactive<{ name: string; remark: string; positions: Position[]; expectedVersion: number }>({ name: '', remark: '', positions: [], expectedVersion: 0 })
const signatures = computed(() => assets.value.filter(item => item.assetType === 'SIGNATURE'))
const seals = computed(() => assets.value.filter(item => item.assetType === 'SEAL'))
const actionLabels: Record<string, string> = { ACTIVATE: '启用', DEACTIVATE: '停用', REVISE: '创建修订' }
const stateLabels: Record<string, string> = { DRAFT: '草稿', ACTIVE: '启用', INACTIVE: '停用' }
const pageLabels: Record<string, string> = { FIRST: '首页', LAST: '末页', EVERY: '每页' }

async function load() {
  loading.value = true
  try {
    const [schemeRows, assetRows] = await Promise.all([
      api<Scheme[]>('/api/signature-schemes'),
      api<SigningAsset[]>('/api/signing-assets?verificationState=VERIFIED&active=true'),
    ])
    schemes.value = schemeRows
    assets.value = assetRows
  } catch (error) { showError(error) } finally { loading.value = false }
}

function resetForm() {
  editing.value = undefined
  Object.assign(form, { name: '', remark: '', positions: [], expectedVersion: 0 })
}

function startCreate() {
  resetForm()
  drawer.value = true
}

function startEdit(row: Scheme) {
  editing.value = row
  Object.assign(form, {
    name: row.name, remark: row.remark ?? '', expectedVersion: row.version,
    positions: row.positions.map(item => ({ ...item, xMm: Number(item.xMm), yMm: Number(item.yMm), widthMm: Number(item.widthMm), heightMm: Number(item.heightMm) })),
  })
  drawer.value = true
}

function addPosition(type: 'SIGNATURE' | 'SEAL') {
  const options = type === 'SIGNATURE' ? signatures.value : seals.value
  if (!options.length) return ElMessage.warning(`请先认证并启用${type === 'SIGNATURE' ? '人员签名' : '机构章'}素材`)
  const count = form.positions.filter(item => item.positionType === type).length
  form.positions.push({
    positionType: type, signingAssetId: options[0].id,
    label: type === 'SIGNATURE' ? '签发人受控签名' : '机构受控印章', pageRule: 'LAST',
    xMm: type === 'SIGNATURE' ? 100 : 140, yMm: type === 'SIGNATURE' ? 215 : 205,
    widthMm: type === 'SIGNATURE' ? 48 : 30, heightMm: type === 'SIGNATURE' ? 24 : 30,
    sortOrder: count,
  })
}

function removePosition(index: number) {
  form.positions.splice(index, 1)
  normalizeOrder()
}

function normalizeOrder() {
  const counters = { SIGNATURE: 0, SEAL: 0 }
  form.positions.forEach(item => { item.sortOrder = counters[item.positionType]++ })
}

function assetOptions(type: string) { return type === 'SIGNATURE' ? signatures.value : seals.value }
function assetName(id: string) { return assets.value.find(item => item.id === id)?.name ?? '素材不可用' }

function validateForm() {
  if (!form.name.trim()) return '请填写方案名称'
  for (const position of form.positions) {
    if (!position.signingAssetId || !position.label.trim()) return '请完整填写每个签章位置'
    if (position.xMm < 0 || position.yMm < 0 || position.widthMm < 10 || position.heightMm < 10 ||
        position.xMm + position.widthMm > 180 || position.yMm + position.heightMm > 250) return '签章位置超出 A4 受控内容区域'
  }
  return ''
}

function payload() {
  normalizeOrder()
  return { name: form.name, remark: form.remark || null, positions: form.positions.map(item => ({
    positionType: item.positionType, signingAssetId: item.signingAssetId, label: item.label,
    pageRule: item.pageRule, xMm: item.xMm, yMm: item.yMm, widthMm: item.widthMm,
    heightMm: item.heightMm, sortOrder: item.sortOrder,
  })) }
}

async function save() {
  const warning = validateForm()
  if (warning) return ElMessage.warning(warning)
  saving.value = true
  try {
    if (editing.value) {
      await api(`/api/signature-schemes/${editing.value.id}`, { method: 'PUT', body: JSON.stringify({ ...payload(), expectedVersion: form.expectedVersion }) })
    } else {
      await api('/api/signature-schemes', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(payload()) })
    }
    ElMessage.success(editing.value ? '签章方案草稿已更新' : '签章方案草稿已创建')
    drawer.value = false
    resetForm()
    await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function transition(row: Scheme, action: string) {
  try {
    if (action === 'REVISE') {
      const revised = await api<Scheme>(`/api/signature-schemes/${row.id}/revisions`, {
        method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      })
      ElMessage.success(`已创建修订 V${revised.revisionNumber}`)
      await load()
      startEdit(revised)
      return
    }
    let comment = ''
    if (action === 'DEACTIVATE') {
      comment = (await ElMessageBox.prompt('请填写停用原因，内容将进入审计记录', '停用签章方案', {
        inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空',
      })).value
    } else {
      await ElMessageBox.confirm('启用后方案内容不可原地修改，后续变更必须创建修订。确认启用？', '启用签章方案', { type: 'warning' })
    }
    const key = crypto.randomUUID()
    await withPassword(() => api(`/api/signature-schemes/${row.id}/transitions`, {
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

function stateType(state: string) { return state === 'ACTIVE' ? 'success' : state === 'DRAFT' ? 'warning' : 'info' }
function positionStyle(position: Position) {
  return {
    left: `${position.xMm / 180 * 100}%`, top: `${position.yMm / 250 * 100}%`,
    width: `${position.widthMm / 180 * 100}%`, height: `${position.heightMm / 250 * 100}%`,
  }
}

onMounted(load)
</script>

<template>
  <section class="page signing-page">
    <header class="page-heading"><div><h1>签章方案</h1><p>绑定已认证素材，配置 A4 坐标和首页、末页或每页规则</p></div><el-button type="primary" :icon="Plus" @click="startCreate">新增方案</el-button></header>
    <el-alert v-if="!signatures.length || !seals.length" title="启用方案前，至少需要一个已认证并启用的人员签名和机构章。" type="warning" show-icon :closable="false" />
    <section class="module-toolbar"><el-button :icon="Refresh" @click="load">刷新</el-button><span class="toolbar-spacer"></span><span class="muted">启用方案：{{ schemes.filter(item => item.state === 'ACTIVE').length }} · 可用签名：{{ signatures.length }} · 可用机构章：{{ seals.length }}</span></section>
    <el-table v-loading="loading" :data="schemes" border stripe class="module-table">
      <el-table-column prop="name" label="方案名称" min-width="220" />
      <el-table-column label="版本" width="90"><template #default="scope">V{{ scope.row.revisionNumber }}</template></el-table-column>
      <el-table-column label="人员章/机构章" width="150"><template #default="scope">{{ scope.row.positions.filter((item: Position) => item.positionType === 'SIGNATURE').length }} / {{ scope.row.positions.filter((item: Position) => item.positionType === 'SEAL').length }}</template></el-table-column>
      <el-table-column label="位置规则" min-width="210"><template #default="scope"><span v-for="item in scope.row.positions" :key="item.id" class="scheme-rule">{{ item.label }}·{{ pageLabels[item.pageRule] }}</span></template></el-table-column>
      <el-table-column prop="createdBy" label="创建人" width="140" />
      <el-table-column label="状态" width="100" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)">{{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column>
      <el-table-column label="操作" fixed="right" width="220" align="center"><template #default="scope">
        <el-button v-if="scope.row.state === 'DRAFT'" link type="primary" @click="startEdit(scope.row)">配置位置</el-button>
        <el-button v-for="action in scope.row.allowedActions" :key="action" link :type="action === 'DEACTIVATE' ? 'danger' : 'primary'" @click="transition(scope.row, action)">{{ actionLabels[action] }}</el-button>
      </template></el-table-column>
      <template #empty><el-empty description="暂无签章方案" /></template>
    </el-table>

    <el-drawer v-model="drawer" :title="`${editing ? '配置' : '新增'}签章方案`" size="min(900px, 96vw)" destroy-on-close @closed="resetForm">
      <div class="scheme-editor">
        <section class="scheme-fields"><el-form label-position="top"><el-form-item label="方案名称" required><el-input v-model="form.name" maxlength="160" /></el-form-item><el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item></el-form>
          <div class="scheme-position-heading"><strong>签章位置</strong><div><el-button size="small" @click="addPosition('SIGNATURE')">添加人员签名</el-button><el-button size="small" @click="addPosition('SEAL')">添加机构章</el-button></div></div>
          <el-empty v-if="!form.positions.length" description="尚未配置签章位置" :image-size="72" />
          <article v-for="(position, index) in form.positions" :key="`${position.positionType}-${index}`" class="position-card">
            <header><strong>{{ position.positionType === 'SIGNATURE' ? '人员签名' : '机构章' }}</strong><el-button link type="danger" :icon="Delete" @click="removePosition(index)">移除</el-button></header>
            <div class="form-grid"><el-select v-model="position.signingAssetId" placeholder="选择已认证素材"><el-option v-for="asset in assetOptions(position.positionType)" :key="asset.id" :label="asset.ownerName ? `${asset.name} · ${asset.ownerName}` : asset.name" :value="asset.id" /></el-select><el-input v-model="position.label" maxlength="120" placeholder="位置名称" /><el-select v-model="position.pageRule"><el-option label="首页" value="FIRST" /><el-option label="末页" value="LAST" /><el-option label="每页" value="EVERY" /></el-select></div>
            <div class="position-numbers"><label>X(mm)<el-input-number v-model="position.xMm" :min="0" :max="170" :step="1" /></label><label>Y(mm)<el-input-number v-model="position.yMm" :min="0" :max="240" :step="1" /></label><label>宽(mm)<el-input-number v-model="position.widthMm" :min="10" :max="80" :step="1" /></label><label>高(mm)<el-input-number v-model="position.heightMm" :min="10" :max="80" :step="1" /></label></div>
          </article>
        </section>
        <aside class="scheme-preview"><div class="scheme-preview-head"><strong>A4 落章预览</strong><span>内容区域 180×250mm</span></div><div class="a4-canvas" aria-label="A4 签章位置预览"><figure v-for="(position, index) in form.positions" :key="index" :style="positionStyle(position)" :title="`${position.label} · ${pageLabels[position.pageRule]}`"><img :src="`/api/signing-assets/${position.signingAssetId}/content`" :alt="assetName(position.signingAssetId)" /><figcaption>{{ pageLabels[position.pageRule] }}</figcaption></figure></div></aside>
      </div>
      <template #footer><el-button @click="drawer = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
    </el-drawer>
  </section>
</template>
