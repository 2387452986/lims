<script setup lang="ts">
import {
  ArrowDown, Delete, Download, Edit, FolderAdd, MoreFilled, Plus, Printer, Refresh, Search, Upload,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, errorMessage } from '../api'
import { flattenCatalog, type CatalogNode } from '../detection'
import { moduleLabel } from '../navigation'
import { localDateString } from '../presentation'
import { useAuthStore } from '../stores/auth'

type Row = Record<string, any>
type CursorPage = { items: Row[]; nextCursor?: string; hasMore: boolean; total: number }
type StoredFile = { id: string; originalName: string }
type Category = { id: string; parentId?: string; code: string; name: string; sortOrder: number; active: boolean; version: number; equipmentCount: number; children?: Category[] }

const route = useRoute()
const auth = useAuthStore()
const moduleKey = computed(() => String(route.params.moduleKey))
const title = computed(() => moduleLabel(moduleKey.value))
const canWrite = computed(() => auth.user?.permissions.includes('MODULE_WRITE'))
const canTransition = computed(() => auth.user?.permissions.includes('BUSINESS_TRANSITION'))
const canExport = computed(() => auth.user?.permissions.includes('EXPORT_DATA'))
const loading = ref(false)
const saving = ref(false)
const uploading = ref('')
const rows = ref<Row[]>([])
const total = ref(0)
const cursor = ref('')
const nextCursor = ref('')
const cursorHistory = ref<string[]>([])
const selectedRows = ref<Row[]>([])
const people = ref<Row[]>([])
const equipmentOptions = ref<Row[]>([])
const categories = ref<Category[]>([])
const detectionTree = ref<CatalogNode[]>([])
const projectTreeRef = ref<any>()
const importInput = ref<HTMLInputElement>()
const certificateImportInput = ref<HTMLInputElement>()
const equipmentDrawer = ref(false)
const certificateDialog = ref(false)
const categoryDialog = ref(false)
const inspectionDialog = ref(false)
const inspectionActionDialog = ref(false)
const borrowDialog = ref(false)
const borrowActionDialog = ref(false)
const editing = ref<Row>()
const selectedCategoryId = ref('')
const inspectionTab = ref('outbound')
const activeEquipmentTab = ref('basic')
const advancedFiltersOpen = ref(false)

const filters = reactive<Row>({
  query: '', dateRange: [], equipmentType: '', enabled: '', performanceStatus: '', approvalState: '',
  verificationStatus: '', scrapped: '', inventoryStatus: '', code: '', assetNumber: '', name: '',
  model: '', manufacturer: '', verificationOrganization: '', technicalSpecification: '', location: '', serialNumber: '',
  certificateNumber: '', inspectionType: '', operatorName: '', result: '', state: '', uploaded: '',
  borrowerName: '', returnerName: '', beforeCondition: '', afterCondition: '', commissionNumber: '',
  operationType: '', movementType: '', personName: '',
})

const equipmentForm = reactive<Row>({})
const certificateForm = reactive<Row>({})
const categoryForm = reactive<Row>({})
const inspectionForm = reactive<Row>({})
const inspectionActionForm = reactive<Row>({})
const borrowForm = reactive<Row>({})
const borrowActionForm = reactive<Row>({})

const descriptions: Record<string, string> = {
  equipment: '设备分类、主档、审批、限定检测项目与状态全生命周期',
  'equipment-certificates': '检定、校准、功能核查等证书及有效期受控管理',
  'equipment-service': '送检出库、接收、填报、确认、审核与返场入库',
  'equipment-borrow': '领用、移交、归还、作废及配件数量守恒',
  'equipment-usage': '查询设备使用环境及使用前后状态',
  'equipment-calibration': '查询已确认的设备校准结果与操作记录',
  'equipment-movements': '送检、领用和库存操作生成的不可篡改出入库台账',
}

const labels: Record<string, string> = {
  DRAFT: '未提交', SUBMITTED: '已提交', APPROVED: '已审批', REJECTED: '已驳回',
  AVAILABLE: '在库可用', IN_USE: '使用中', BORROWED: '已领用', UNDER_INSPECTION: '送检中',
  UNDER_CALIBRATION: '送校中', MAINTENANCE: '维修中', OUT_OF_SERVICE: '已出库', SCRAPPED: '已报废',
  NORMAL: '正常', ABNORMAL: '异常', IN: '入库', OUT: '出库', VALID: '有效', EXPIRED: '过期',
  FAILED: '不合格', NONE: '未录入', PASS: '合格', LIMITED: '限用', FAIL: '不合格',
  VERIFICATION: '检定', CALIBRATION: '校准', FUNCTION_CHECK: '功能核查', SELF_CALIBRATION: '自校',
  COMPARISON: '比对', TESTING: '检测', OTHER: '其他', FIELD: '现场检测类', GEOTECHNICAL: '岩土类',
  CONCRETE: '混凝土工程类', METAL_STRUCTURE: '金属结构类', MECHANICAL_ELECTRICAL: '机械电气类', SURVEY: '测量类',
  OUTBOUND: '送检出库', RECEIVED: '已接收', REPORTED: '已填报', CONFIRMED: '已确认', RETURNED: '已归还',
  VOID: '已作废', TRANSFERRED: '已移交', IN_PROGRESS: '使用中', COMPLETED: '已完成',
  BORROW_RETURN: '领用归还', INSPECTION: '送检', INVENTORY: '库存',
}

const actionLabels: Record<string, string> = {
  SUBMIT: '提交', WITHDRAW: '撤回', APPROVE: '审批通过', REJECT: '驳回', ENABLE: '启用',
  DISABLE: '停用', SCRAP: '报废', RECEIVE: '接收', REPORT: '检定填报', CONFIRM: '检定确认',
  RETURN: '归还 / 入库', VOID: '作废', TRANSFER: '移交', COMPLETE: '完成使用',
}
const verificationTypes = ['VERIFICATION', 'CALIBRATION', 'FUNCTION_CHECK', 'SELF_CALIBRATION', 'COMPARISON', 'TESTING', 'OTHER']
const equipmentTypes = ['FIELD', 'GEOTECHNICAL', 'CONCRETE', 'METAL_STRUCTURE', 'MECHANICAL_ELECTRICAL', 'SURVEY', 'OTHER']
const pileTypes = [
  ['STATIC_LOAD_HOST', '静载主机'], ['PRESSURE_SENSOR', '压力传感器'], ['DISPLACEMENT_SENSOR', '位移传感器'],
  ['DYNAMIC_HOST', '动测主机'], ['ACCELERATION_SENSOR', '加速度传感器'], ['FORCE_SENSOR', '力传感器'],
  ['JACK', '千斤顶'], ['SPEED_SENSOR', '速度传感器'], ['ULTRASONIC_HOST', '超声波主机'],
  ['CROSS_HOLE_TRANSDUCER', '跨孔换能器'], ['PLANE_TRANSDUCER', '平面换能器'], ['PERCENTAGE_METER', '百分比'],
  ['SELF_BALANCING_LOAD_CELL', '自平衡荷载箱'], ['WIRELESS_TRANSMISSION', '无线传输仪器'],
]

const categoryTree = computed(() => buildCategoryTree(categories.value))
const flatProjects = computed(() => flattenCatalog(detectionTree.value).filter(item => item.type === 'PROJECT'))
const selected = computed(() => selectedRows.value[0])
const inspectionRows = computed(() => rows.value)
const availableEquipmentOptions = computed(() => equipmentOptions.value.filter(value => value.approvalState === 'APPROVED'
  && value.enabled && value.performanceStatus === 'NORMAL' && value.state === 'AVAILABLE' && value.inventoryStatus === 'IN'))

function showError(error: unknown) {
  ElMessage.error(errorMessage(error))
}
function formatTime(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function formatDatePeriod(value?: string, period?: string) {
  if (!value) return '—'
  return `${new Date(value).toLocaleDateString('zh-CN')} ${period === 'AM' ? '上午' : period === 'PM' ? '下午' : ''}`.trim()
}
function typeOfStatus(value: string) {
  if (['APPROVED', 'AVAILABLE', 'NORMAL', 'VALID', 'PASS', 'RETURNED', 'COMPLETED', 'IN'].includes(value)) return 'success'
  if (['REJECTED', 'ABNORMAL', 'FAILED', 'FAIL', 'SCRAPPED', 'VOID', 'OUT'].includes(value)) return 'danger'
  if (['SUBMITTED', 'IN_USE', 'BORROWED', 'UNDER_INSPECTION', 'OUTBOUND', 'RECEIVED', 'REPORTED', 'CONFIRMED', 'TRANSFERRED'].includes(value)) return 'warning'
  return 'info'
}
function buildCategoryTree(source: Category[], parentId: string | null = null): Category[] {
  return source.filter(item => item.parentId === parentId).map(item => ({ ...item, children: buildCategoryTree(source, item.id) }))
}
function nowLocal(offsetDays = 0) {
  const date = new Date(Date.now() + offsetDays * 86400000 - new Date().getTimezoneOffset() * 60000)
  return date.toISOString().slice(0, 16)
}

async function loadReferences() {
  try {
    const [personRows, categoryRows, catalog] = await Promise.all([
      api<Row[]>('/api/master-data/people'), api<Category[]>('/api/equipment/categories'),
      api<CatalogNode[]>('/api/detection-catalog/tree?includeInactive=false&includeHistory=false'),
    ])
    people.value = personRows.filter(item => item.active)
    categories.value = categoryRows
    detectionTree.value = catalog
    await loadEquipmentOptions()
  } catch (error) { showError(error) }
}

async function loadEquipmentOptions(query = '') {
  const result = await api<CursorPage>(`/api/equipment?limit=100&scrapped=false&query=${encodeURIComponent(query)}`)
  equipmentOptions.value = result.items
}

function endpoint() {
  if (moduleKey.value === 'equipment') return '/api/equipment'
  if (['equipment-certificates', 'equipment-calibration'].includes(moduleKey.value)) return '/api/equipment/certificates'
  if (moduleKey.value === 'equipment-service') return '/api/equipment/inspections'
  if (moduleKey.value === 'equipment-borrow') return '/api/equipment/borrows'
  if (moduleKey.value === 'equipment-usage') return '/api/equipment/usages'
  return '/api/equipment/movements'
}

function queryParams() {
  const params = new URLSearchParams({ query: String(filters.query ?? ''), limit: '50' })
  if (cursor.value) params.set('cursor', cursor.value)
  if (moduleKey.value === 'equipment') {
    for (const key of ['equipmentType', 'enabled', 'performanceStatus', 'approvalState', 'verificationStatus',
      'scrapped', 'inventoryStatus', 'code', 'assetNumber', 'name', 'manufacturer', 'verificationOrganization',
      'technicalSpecification', 'location', 'serialNumber']) if (filters[key] !== '') params.set(key, String(filters[key]))
    if (selectedCategoryId.value) params.set('categoryId', selectedCategoryId.value)
    if (filters.dateRange?.[0]) params.set('verificationFrom', filters.dateRange[0])
    if (filters.dateRange?.[1]) params.set('verificationTo', filters.dateRange[1])
  } else if (['equipment-certificates', 'equipment-calibration'].includes(moduleKey.value)) {
    if (moduleKey.value === 'equipment-calibration') params.set('certificateType', 'CALIBRATION')
    if (filters.inspectionType) params.set('inspectionType', String(filters.inspectionType))
    if (filters.verificationStatus) params.set('verificationStatus', String(filters.verificationStatus))
    if (filters.result) params.set('result', String(filters.result))
    if (filters.state) params.set('state', String(filters.state))
    if (filters.uploaded !== '') params.set('uploaded', String(filters.uploaded))
    if (filters.dateRange?.[0]) params.set('issuedFrom', filters.dateRange[0])
    if (filters.dateRange?.[1]) params.set('issuedTo', filters.dateRange[1])
    for (const [source, target] of [['code', 'equipmentCode'], ['certificateNumber', 'certificateNumber'],
      ['name', 'equipmentName'], ['model', 'model'], ['manufacturer', 'manufacturer'],
      ['verificationOrganization', 'issuingOrganization'], ['operatorName', 'operatorName']]) {
      if (filters[source] !== '') params.set(target, String(filters[source]))
    }
  } else if (moduleKey.value === 'equipment-service') {
    const states = inspectionTab.value === 'outbound' ? 'OUTBOUND'
      : inspectionTab.value === 'inspection' ? 'OUTBOUND,RECEIVED'
        : inspectionTab.value === 'confirm' ? 'REPORTED,CONFIRMED,APPROVED' : 'RETURNED,VOID'
    params.set('states', states)
    if (filters.result) params.set('result', String(filters.result))
    if (filters.dateRange?.[0]) params.set('outboundFrom', filters.dateRange[0])
    if (filters.dateRange?.[1]) params.set('outboundTo', filters.dateRange[1])
    for (const [source, target] of [['code', 'equipmentCode'], ['name', 'equipmentName'], ['model', 'model'],
      ['verificationOrganization', 'inspectionOrganization']]) if (filters[source] !== '') params.set(target, String(filters[source]))
  } else if (moduleKey.value === 'equipment-borrow') {
    if (filters.beforeCondition) params.set('beforeCondition', String(filters.beforeCondition))
    if (filters.afterCondition) params.set('afterCondition', String(filters.afterCondition))
    if (filters.dateRange?.[0]) params.set('borrowedFrom', filters.dateRange[0])
    if (filters.dateRange?.[1]) params.set('borrowedTo', filters.dateRange[1])
    for (const [source, target] of [['code', 'equipmentCode'], ['name', 'equipmentName'], ['model', 'model'],
      ['borrowerName', 'borrowerName'], ['returnerName', 'returnerName']]) if (filters[source] !== '') params.set(target, String(filters[source]))
  } else if (moduleKey.value === 'equipment-usage') {
    if (filters.beforeCondition) params.set('beforeCondition', String(filters.beforeCondition))
    if (filters.afterCondition) params.set('afterCondition', String(filters.afterCondition))
    if (filters.equipmentType) params.set('equipmentType', String(filters.equipmentType))
    if (filters.dateRange?.[0]) params.set('startedFrom', filters.dateRange[0])
    if (filters.dateRange?.[1]) params.set('startedTo', filters.dateRange[1])
    for (const [source, target] of [['code', 'equipmentCode'], ['name', 'equipmentName'], ['model', 'model'],
      ['commissionNumber', 'commissionNumber']]) if (filters[source] !== '') params.set(target, String(filters[source]))
  } else {
    if (filters.operationType) params.set('operationType', String(filters.operationType))
    if (filters.movementType) params.set('movementType', String(filters.movementType))
    if (filters.dateRange?.[0]) params.set('occurredFrom', filters.dateRange[0])
    for (const [source, target] of [['code', 'equipmentCode'], ['name', 'equipmentName'], ['model', 'model'],
      ['personName', 'personName']]) if (filters[source] !== '') params.set(target, String(filters[source]))
  }
  return params
}

async function load() {
  loading.value = true
  try {
    const result = await api<CursorPage>(`${endpoint()}?${queryParams()}`)
    rows.value = result.items
    total.value = result.total
    nextCursor.value = result.nextCursor ?? ''
    selectedRows.value = []
  } catch (error) { showError(error) }
  finally { loading.value = false }
}
function search() { cursor.value = ''; cursorHistory.value = []; load() }
function reset() {
  Object.assign(filters, { query: '', dateRange: [], equipmentType: '', enabled: '', performanceStatus: '',
    approvalState: '', verificationStatus: '', scrapped: '', inventoryStatus: '', code: '', assetNumber: '',
    name: '', model: '', manufacturer: '', verificationOrganization: '', technicalSpecification: '', location: '',
    serialNumber: '', certificateNumber: '', inspectionType: '', operatorName: '', result: '', state: '', uploaded: '',
    borrowerName: '', returnerName: '', beforeCondition: '', afterCondition: '', commissionNumber: '',
    operationType: '', movementType: '', personName: '' })
  search()
}
function nextPage() { if (nextCursor.value) { cursorHistory.value.push(cursor.value); cursor.value = nextCursor.value; load() } }
function previousPage() { if (cursorHistory.value.length) { cursor.value = cursorHistory.value.pop() ?? ''; load() } }
function chooseCategory(value?: Category) { selectedCategoryId.value = value?.id ?? ''; search() }

async function download(path: string, fallback: string) {
  try {
    const response = await fetch(path, { credentials: 'same-origin' })
    if (!response.ok) {
      const body = await response.json().catch(() => ({})); throw new Error(body.detail ?? '下载失败')
    }
    const blob = await response.blob(); const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = fallback; anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) { showError(error) }
}

async function uploadFile(event: Event, field: string, nameField: string, purpose: string) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]
  if (!file) return
  uploading.value = field
  try {
    const data = new FormData(); data.append('file', file); data.append('purpose', purpose)
    const stored = await api<StoredFile>('/api/files', { method: 'POST', body: data })
    ;(equipmentForm as Row)[field] = stored.id; (equipmentForm as Row)[nameField] = stored.originalName
    if (certificateDialog.value) { certificateForm[field] = stored.id; certificateForm[nameField] = stored.originalName }
    if (inspectionDialog.value || inspectionActionDialog.value) {
      const target = inspectionActionDialog.value ? inspectionActionForm : inspectionForm
      target[field] = stored.id; target[nameField] = stored.originalName
    }
    if (borrowDialog.value) { borrowForm[field] = stored.id; borrowForm[nameField] = stored.originalName }
    ElMessage.success('附件已安全上传')
  } catch (error) { showError(error) }
  finally { uploading.value = ''; input.value = '' }
}

async function importWorkbook(event: Event, certificate = false) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]
  if (!file) return
  saving.value = true
  try {
    const data = new FormData(); data.append('file', file)
    const path = certificate ? '/api/equipment/certificates/imports' : '/api/equipment/imports'
    const result = await api<Row>(path, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: data })
    ElMessage.success(`已事务导入 ${result.importedCount} 条记录`)
    await Promise.all([load(), loadReferences()])
  } catch (error) { showError(error) }
  finally { saving.value = false; input.value = '' }
}

function blankEquipment() {
  Object.assign(equipmentForm, {
    code: '', assetNumber: '', name: '', model: '', categoryId: selectedCategoryId.value || '', purchasedOn: '',
    purchasePrice: '', manufacturer: '', serialNumber: '', verificationCycleMonths: 12,
    verificationType: 'VERIFICATION', technicalSpecification: '', nominalRange: '', measurementRange: '',
    resolution: '', accuracy: '', custodianId: '', primaryEquipment: false, enabled: true,
    performanceStatus: 'NORMAL', location: '', equipmentType: '', pileEquipmentType: '', attachmentFileId: '',
    attachmentFileName: '', photoFileId: '', photoFileName: '', remark: '', detectionProjectIds: [], expectedVersion: 0,
  })
}
async function openEquipment(row?: Row) {
  editing.value = row
  activeEquipmentTab.value = 'basic'
  blankEquipment()
  if (row) {
    try { Object.assign(equipmentForm, await api<Row>(`/api/equipment/${row.id}`)) }
    catch (error) { showError(error); return }
  }
  equipmentDrawer.value = true
  await nextTick()
  projectTreeRef.value?.setCheckedKeys(equipmentForm.detectionProjectIds ?? [])
}
async function saveEquipment() {
  if (!equipmentForm.code?.trim() || !equipmentForm.name?.trim() || !equipmentForm.model?.trim()) return ElMessage.warning('请填写设备编号、名称和规格型号')
  equipmentForm.detectionProjectIds = projectTreeRef.value?.getCheckedKeys(true) ?? []
  const body = { ...equipmentForm, categoryId: equipmentForm.categoryId || null, custodianId: equipmentForm.custodianId || null,
    purchasedOn: equipmentForm.purchasedOn || null, purchasePrice: equipmentForm.purchasePrice === '' ? null : String(equipmentForm.purchasePrice),
    verificationCycleMonths: equipmentForm.verificationCycleMonths || null, verificationType: equipmentForm.verificationType || null,
    equipmentType: equipmentForm.equipmentType || null, pileEquipmentType: equipmentForm.pileEquipmentType || null,
    attachmentFileId: equipmentForm.attachmentFileId || null, photoFileId: equipmentForm.photoFileId || null }
  saving.value = true
  try {
    if (editing.value) await api(`/api/equipment/${editing.value.id}`, { method: 'PUT', body: JSON.stringify(body) })
    else await api('/api/equipment', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) })
    equipmentDrawer.value = false
    ElMessage.success(editing.value ? '设备修改已保存，须重新提交审批' : '设备草稿已建档')
    await Promise.all([load(), loadEquipmentOptions()])
  } catch (error) { showError(error) }
  finally { saving.value = false }
}
async function equipmentAction(row: Row, action: string) {
  let comment = ''
  if (['REJECT', 'DISABLE', 'SCRAP'].includes(action)) {
    const result = await ElMessageBox.prompt(`请填写${actionLabels[action]}原因`, actionLabels[action], { inputPattern: /\S+/, inputErrorMessage: '原因不能为空', type: 'warning' })
    comment = result.value
  } else if (action === 'APPROVE') await ElMessageBox.confirm('确认审批通过该设备主档？', '审批确认')
  try {
    await api(`/api/equipment/${row.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: 'EQUIPMENT_CONTROL', comment, details: {} }) })
    ElMessage.success(`${actionLabels[action]}已完成`); await Promise.all([load(), loadEquipmentOptions()])
  } catch (error) { showError(error) }
}
async function batchEquipmentAction(action: string) {
  const eligible = selectedRows.value.filter(row => action === 'SUBMIT'
    ? ['DRAFT', 'REJECTED'].includes(row.approvalState) : row.approvalState === 'SUBMITTED')
  if (!eligible.length || eligible.length !== selectedRows.value.length) return ElMessage.warning('所选设备状态不一致，无法批量办理')
  try {
    await ElMessageBox.confirm(`确认对 ${eligible.length} 台设备执行“${actionLabels[action]}”？`, '批量操作确认')
    await api('/api/equipment/transitions', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, reasonCode: 'EQUIPMENT_BATCH_CONTROL', comment: '',
        items: eligible.map(row => ({ id: row.id, expectedVersion: row.version })) }) })
    ElMessage.success(`${eligible.length} 台设备已${actionLabels[action]}`); await Promise.all([load(), loadEquipmentOptions()])
  } catch (error) { if (error !== 'cancel') showError(error) }
}
function handleEquipmentMore(command: string) {
  if (command === 'assign') return assignCategory()
  if (command === 'template') return download('/api/equipment/import-template', '设备管理-导入模板.xlsx')
  if (command === 'import') return importInput.value?.click()
  if (['SUBMIT', 'WITHDRAW'].includes(command) && selectedRows.value.length > 1) return batchEquipmentAction(command)
  if (selected.value) return equipmentAction(selected.value, command)
}
async function deleteEquipment() {
  if (!selected.value) return ElMessage.warning('请选择一台设备')
  try {
    const result = await ElMessageBox.prompt('仅未提交草稿可删除，请填写删除原因', '删除设备草稿', { type: 'warning', inputPattern: /\S+/, inputErrorMessage: '原因不能为空' })
    await api(`/api/equipment/${selected.value.id}?expectedVersion=${selected.value.version}&reason=${encodeURIComponent(result.value)}`, { method: 'DELETE' })
    ElMessage.success('设备草稿已删除'); await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}
function printLabel(row?: Row) {
  const value = row ?? selected.value
  if (!value) return ElMessage.warning('请选择一台设备')
  window.open(`/api/equipment/${value.id}/label`, '_blank', 'noopener,noreferrer,width=760,height=560')
}

function openCategory(row?: Category) {
  Object.assign(categoryForm, row ? { ...row } : { code: '', name: '', parentId: selectedCategoryId.value || '', sortOrder: 0, expectedVersion: 0 })
  editing.value = row as Row | undefined
  categoryDialog.value = true
}
async function saveCategory() {
  if (!categoryForm.code?.trim() || !categoryForm.name?.trim()) return ElMessage.warning('请填写分类编码和名称')
  saving.value = true
  try {
    const body = { ...categoryForm, parentId: categoryForm.parentId || null }
    if (editing.value) await api(`/api/equipment/categories/${editing.value.id}`, { method: 'PUT', body: JSON.stringify(body) })
    else await api('/api/equipment/categories', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) })
    categoryDialog.value = false; ElMessage.success('设备分类已保存'); await loadReferences()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function assignCategory() {
  if (!selectedCategoryId.value || !selectedRows.value.length) return ElMessage.warning('请先选择分类和设备草稿')
  try {
    await api('/api/equipment/category-assignments', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ categoryId: selectedCategoryId.value, equipmentIds: selectedRows.value.map(item => item.id) }) })
    ElMessage.success('已批量设定分类'); await Promise.all([load(), loadReferences()])
  } catch (error) { showError(error) }
}
async function categoryAction(row: Category, action: string) {
  try {
    await api(`/api/equipment/categories/${row.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: 'EQUIPMENT_CATEGORY_CONTROL', comment: '', details: {} }) })
    ElMessage.success(`分类已${action === 'ENABLE' ? '启用' : '停用'}`); await loadReferences()
  } catch (error) { showError(error) }
}

function blankCertificate(equipmentId = '') {
  Object.assign(certificateForm, { equipmentId, certificateType: moduleKey.value === 'equipment-calibration' ? 'CALIBRATION' : 'VERIFICATION',
    inspectionType: moduleKey.value === 'equipment-calibration' ? 'CALIBRATION' : 'VERIFICATION', certificateNumber: '',
    issuedOn: localDateString(), validUntil: localDateString(new Date(Date.now() + 365 * 86400000)),
    confirmedOn: '', result: 'PASS', issuingOrganization: '', fileId: '', fileName: '', remark: '', expectedVersion: 0 })
}
function openCertificate(row?: Row, equipmentRow?: Row) {
  editing.value = row
  blankCertificate(equipmentRow?.id ?? '')
  if (row) Object.assign(certificateForm, row)
  certificateDialog.value = true
}
async function saveCertificate() {
  if (!certificateForm.equipmentId || !certificateForm.certificateNumber?.trim() || !certificateForm.issuingOrganization?.trim()) return ElMessage.warning('请填写设备、检校编号和检校单位')
  const body = { ...certificateForm, confirmedOn: certificateForm.confirmedOn || null, fileId: certificateForm.fileId || null }
  saving.value = true
  try {
    if (editing.value) await api(`/api/equipment/certificates/${editing.value.id}`, { method: 'PUT', body: JSON.stringify(body) })
    else await api(`/api/equipment/${certificateForm.equipmentId}/certificates`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) })
    certificateDialog.value = false; ElMessage.success('检校证书已受控保存'); await Promise.all([load(), loadEquipmentOptions()])
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function voidCertificate() {
  if (!selected.value) return ElMessage.warning('请选择一条证书')
  try {
    const result = await ElMessageBox.prompt('证书不物理删除，请填写作废原因', '作废证书', { type: 'warning', inputPattern: /\S+/, inputErrorMessage: '原因不能为空' })
    await api(`/api/equipment/certificates/${selected.value.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action: 'VOID', expectedVersion: selected.value.version, reasonCode: 'CERTIFICATE_VOID', comment: result.value, details: {} }) })
    ElMessage.success('证书已作废'); await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

function blankInspection() {
  Object.assign(inspectionForm, { equipmentId: '', outboundPersonId: '', outboundAt: nowLocal(),
    outboundAccessoryQuantity: 0, destination: '', photoFileId: '', photoFileName: '', attachmentFileId: '',
    attachmentFileName: '', remark: '', expectedVersion: 0 })
}
function openInspection(equipmentRow?: Row) {
  blankInspection()
  if (equipmentRow) Object.assign(inspectionForm, { equipmentId: equipmentRow.id, expectedVersion: equipmentRow.version })
  inspectionDialog.value = true
}
function selectedEquipmentChanged(target: Row) {
  const value = equipmentOptions.value.find(item => item.id === target.equipmentId)
  target.expectedVersion = value?.version ?? 0
}
async function saveInspection() {
  if (!inspectionForm.equipmentId || !inspectionForm.outboundPersonId || !inspectionForm.outboundAt) return ElMessage.warning('请选择设备、出库人员和时间')
  saving.value = true
  try {
    await api('/api/equipment/inspections', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ...inspectionForm, outboundAt: new Date(inspectionForm.outboundAt).toISOString(),
        photoFileId: inspectionForm.photoFileId || null, attachmentFileId: inspectionForm.attachmentFileId || null }) })
    inspectionDialog.value = false; ElMessage.success('送检出库已登记'); await Promise.all([load(), loadEquipmentOptions()])
  } catch (error) { showError(error) } finally { saving.value = false }
}
function openInspectionAction(row: Row, action: string) {
  editing.value = row
  Object.assign(inspectionActionForm, { action, expectedVersion: row.version, reasonCode: 'INSPECTION_FLOW', comment: '',
    personId: '', occurredAt: nowLocal(), accessoryQuantity: row.outboundAccessoryQuantity ?? 0,
    inspectionType: row.inspectionType ?? 'VERIFICATION', inspectionNumber: row.inspectionNumber ?? '',
    inspectionOrganization: row.inspectionOrganization ?? '', inspectionDate: row.inspectionDate ?? localDateString(),
    validUntil: row.validUntil ?? localDateString(new Date(Date.now() + 365 * 86400000)),
    result: row.result ?? 'PASS', certificateFileId: row.certificateFileId ?? '', certificateFileName: '' })
  inspectionActionDialog.value = true
}
function inspectionRowActions(row: Row) {
  if (!canTransition.value) return []
  if (inspectionTab.value === 'outbound' && row.state === 'OUTBOUND') return ['RECEIVE']
  if (inspectionTab.value === 'inspection' && ['OUTBOUND', 'RECEIVED'].includes(row.state)) return ['REPORT', 'VOID']
  if (inspectionTab.value === 'confirm' && row.state === 'REPORTED') return ['CONFIRM']
  if (inspectionTab.value === 'confirm' && row.state === 'CONFIRMED') return ['APPROVE', 'WITHDRAW']
  if (inspectionTab.value === 'confirm' && row.state === 'APPROVED') return ['RETURN']
  return []
}
async function saveInspectionAction() {
  if (!editing.value) return
  if (inspectionActionForm.action !== 'WITHDRAW' && !inspectionActionForm.personId) return ElMessage.warning('请选择操作人员')
  if (['VOID', 'WITHDRAW'].includes(inspectionActionForm.action) && !inspectionActionForm.comment?.trim()) return ElMessage.warning('请填写撤回原因')
  const body: Row = { ...inspectionActionForm,
    occurredAt: inspectionActionForm.occurredAt ? new Date(inspectionActionForm.occurredAt).toISOString() : null,
    certificateFileId: inspectionActionForm.certificateFileId || null }
  saving.value = true
  try {
    await api(`/api/equipment/inspections/${editing.value.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) })
    inspectionActionDialog.value = false; ElMessage.success(`${actionLabels[body.action]}已完成`)
    await Promise.all([load(), loadEquipmentOptions()])
  } catch (error) { showError(error) } finally { saving.value = false }
}

function blankBorrow() {
  Object.assign(borrowForm, { equipmentId: '', borrowerId: '', companionIds: [],
    borrowedAt: nowLocal(), borrowedPeriod: 'AM', plannedReturnAt: nowLocal(7), plannedReturnPeriod: 'PM',
    beforeCondition: 'NORMAL', accessoryQuantity: 0, province: '', city: '', district: '', address: '',
    destination: '', photoFileId: '', photoFileName: '', remark: '', expectedVersion: 0 })
}
async function openBorrow(row?: Row, equipmentRow?: Row) {
  editing.value = row
  blankBorrow()
  if (equipmentRow) Object.assign(borrowForm, { equipmentId: equipmentRow.id, expectedVersion: equipmentRow.version })
  if (row) {
    try {
      const detail = await api<Row>(`/api/equipment/borrows/${row.id}`)
      Object.assign(borrowForm, detail, { companionIds: detail.companions?.map((item: Row) => item.id) ?? [],
        borrowedAt: detail.borrowedAt?.slice(0, 16), plannedReturnAt: detail.plannedReturnAt?.slice(0, 16),
        accessoryQuantity: detail.outboundAccessoryQuantity, expectedVersion: detail.version })
    } catch (error) { showError(error); return }
  }
  borrowDialog.value = true
}
async function returnSelectedEquipment() {
  if (!selected.value) return ElMessage.warning('请选择一台已领用设备')
  try {
    const active = await api<Row>(`/api/equipment/borrows/active?equipmentId=${selected.value.id}`)
    openBorrowAction(active, 'RETURN')
  } catch (error) { showError(error) }
}
async function saveBorrow() {
  if (!borrowForm.equipmentId || !borrowForm.borrowerId || !borrowForm.borrowedAt || !borrowForm.plannedReturnAt) return ElMessage.warning('请填写设备、领用人及领用和计划归还时间')
  const body = { ...borrowForm, borrowedAt: new Date(borrowForm.borrowedAt).toISOString(),
    plannedReturnAt: new Date(borrowForm.plannedReturnAt).toISOString(), photoFileId: borrowForm.photoFileId || null }
  saving.value = true
  try {
    if (editing.value) await api(`/api/equipment/borrows/${editing.value.id}`, { method: 'PUT', body: JSON.stringify(body) })
    else await api('/api/equipment/borrows', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) })
    borrowDialog.value = false; ElMessage.success(editing.value ? '领用记录已修改' : '设备领用已登记')
    await Promise.all([load(), loadEquipmentOptions()])
  } catch (error) { showError(error) } finally { saving.value = false }
}
function openBorrowAction(row: Row, action: string) {
  editing.value = row
  Object.assign(borrowActionForm, { action, expectedVersion: row.version, reasonCode: 'BORROW_FLOW', comment: '',
    personId: '', occurredAt: nowLocal(), period: 'PM', condition: 'NORMAL',
    accessoryQuantity: row.outboundAccessoryQuantity ?? 0 })
  borrowActionDialog.value = true
}
async function saveBorrowAction() {
  if (!editing.value || !borrowActionForm.personId) return ElMessage.warning('请选择操作人员')
  if (['VOID', 'TRANSFER'].includes(borrowActionForm.action) && !borrowActionForm.comment?.trim()) return ElMessage.warning('请填写原因')
  saving.value = true
  try {
    await api(`/api/equipment/borrows/${editing.value.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ...borrowActionForm, occurredAt: new Date(borrowActionForm.occurredAt).toISOString() }) })
    borrowActionDialog.value = false; ElMessage.success(`${actionLabels[borrowActionForm.action]}已完成`)
    await Promise.all([load(), loadEquipmentOptions()])
  } catch (error) { showError(error) } finally { saving.value = false }
}

function exportCurrent() {
  const query = encodeURIComponent(String(filters.query ?? ''))
  const map: Record<string, [string, string]> = {
    equipment: [`/api/equipment/export?query=${query}`, '设备管理-受控导出.xlsx'],
    'equipment-certificates': [`/api/equipment/certificates/export?query=${query}`, '设备检定-受控导出.xlsx'],
    'equipment-calibration': [`/api/equipment/certificates/export?certificateType=CALIBRATION&query=${query}`, '设备校准-受控导出.xlsx'],
    'equipment-service': [`/api/equipment/inspections/export?query=${query}`, '设备送检-受控导出.xlsx'],
    'equipment-borrow': [`/api/equipment/borrows/export?query=${query}`, '领用归还-受控导出.xlsx'],
    'equipment-usage': [`/api/equipment/usages/export?query=${query}`, '使用记录-受控导出.xlsx'],
    'equipment-movements': [`/api/equipment/movements/export?query=${query}`, '出入库记录-受控导出.xlsx'],
  }
  const target = map[moduleKey.value]
  if (target) download(target[0], target[1])
}

watch(moduleKey, () => { advancedFiltersOpen.value = false; inspectionTab.value = 'outbound'; reset() })
watch(inspectionTab, () => { if (moduleKey.value === 'equipment-service') search() })
onMounted(async () => { await loadReferences(); await load() })
</script>

<template>
  <section class="page equipment-page">
    <header class="page-heading equipment-heading">
      <div><h1>{{ title }}</h1><p>{{ descriptions[moduleKey] }}</p></div>
      <div class="heading-metrics" aria-label="当前列表统计"><span><b>{{ total }}</b><small>条记录</small></span><span><b>{{ selectedRows.length }}</b><small>条已选</small></span></div>
    </header>

    <el-tabs v-if="moduleKey === 'equipment-service'" v-model="inspectionTab" class="equipment-flow-tabs">
      <el-tab-pane label="送检出库" name="outbound" /><el-tab-pane label="设备检定" name="inspection" />
      <el-tab-pane label="检定确认" name="confirm" /><el-tab-pane label="检定台账" name="ledger" />
    </el-tabs>

    <section class="equipment-filter">
      <el-input v-model="filters.query" clearable :prefix-icon="Search" :placeholder="moduleKey === 'equipment' ? '综合搜索设备编号、名称、型号或出厂编号' : '设备编号、名称、型号、人员或单位'" @keyup.enter="search" />
      <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="日期起" end-placeholder="日期止" />
      <template v-if="moduleKey === 'equipment'">
        <el-select v-model="filters.equipmentType" clearable placeholder="设备类型"><el-option v-for="item in equipmentTypes" :key="item" :label="labels[item]" :value="item" /></el-select>
        <el-select v-model="filters.enabled" clearable placeholder="启停状态"><el-option label="启用" value="true" /><el-option label="停用" value="false" /></el-select>
        <el-select v-model="filters.performanceStatus" clearable placeholder="性能状态"><el-option label="正常" value="NORMAL" /><el-option label="异常" value="ABNORMAL" /></el-select>
        <el-select v-model="filters.approvalState" clearable placeholder="审批状态"><el-option v-for="item in ['DRAFT','SUBMITTED','APPROVED','REJECTED']" :key="item" :label="labels[item]" :value="item" /></el-select>
      </template>
      <template v-else-if="['equipment-certificates','equipment-calibration'].includes(moduleKey)">
        <el-select v-model="filters.result" clearable placeholder="检校结论"><el-option v-for="item in ['PASS','LIMITED','FAIL']" :key="item" :label="labels[item]" :value="item" /></el-select>
        <el-select v-model="filters.inspectionType" clearable placeholder="检校类型"><el-option v-for="item in verificationTypes" :key="item" :label="labels[item]" :value="item" /></el-select>
        <el-select v-if="moduleKey === 'equipment-certificates'" v-model="filters.verificationStatus" clearable placeholder="检定状态"><el-option v-for="item in ['VALID','EXPIRED','FAILED','VOID']" :key="item" :label="labels[item] ?? '已作废'" :value="item" /></el-select>
      </template>
      <template v-else-if="moduleKey === 'equipment-service'"><el-select v-model="filters.result" clearable placeholder="检定情况"><el-option v-for="item in ['PASS','LIMITED','FAIL']" :key="item" :label="labels[item]" :value="item" /></el-select></template>
      <template v-else-if="moduleKey === 'equipment-borrow'">
        <el-select v-model="filters.beforeCondition" clearable placeholder="领用状态"><el-option label="正常" value="NORMAL" /><el-option label="异常" value="ABNORMAL" /></el-select>
        <el-select v-model="filters.afterCondition" clearable placeholder="归还状态"><el-option label="正常" value="NORMAL" /><el-option label="异常" value="ABNORMAL" /></el-select>
      </template>
      <template v-else-if="moduleKey === 'equipment-usage'">
        <el-select v-model="filters.beforeCondition" clearable placeholder="使用前状态"><el-option label="正常" value="NORMAL" /><el-option label="异常" value="ABNORMAL" /></el-select>
        <el-select v-model="filters.afterCondition" clearable placeholder="使用后状态"><el-option label="正常" value="NORMAL" /><el-option label="异常" value="ABNORMAL" /></el-select>
        <el-select v-model="filters.equipmentType" clearable placeholder="设备类型"><el-option v-for="item in equipmentTypes" :key="item" :label="labels[item]" :value="item" /></el-select>
      </template>
      <template v-else>
        <el-select v-model="filters.operationType" clearable placeholder="操作类型"><el-option label="入库" value="IN" /><el-option label="出库" value="OUT" /></el-select>
        <el-select v-model="filters.movementType" clearable placeholder="出入库类型"><el-option v-for="item in ['BORROW_RETURN','INSPECTION','INVENTORY','MAINTENANCE','OTHER']" :key="item" :label="labels[item]" :value="item" /></el-select>
      </template>
      <div class="foundation-filter-actions"><el-button type="primary" @click="search">查询</el-button><el-button :icon="Refresh" @click="reset">重置</el-button><el-button text @click="advancedFiltersOpen = !advancedFiltersOpen">{{ advancedFiltersOpen ? '收起筛选' : '高级筛选' }}</el-button></div>

      <div v-if="advancedFiltersOpen" class="equipment-filter-advanced">
        <template v-if="moduleKey === 'equipment'">
          <el-select v-model="filters.verificationStatus" clearable placeholder="检定状态"><el-option v-for="item in ['VALID','EXPIRED','FAILED','NONE']" :key="item" :label="labels[item]" :value="item" /></el-select>
          <el-select v-model="filters.scrapped" clearable placeholder="是否报废"><el-option label="未报废" value="false" /><el-option label="已报废" value="true" /></el-select>
          <el-select v-model="filters.inventoryStatus" clearable placeholder="出入库状态"><el-option label="在库" value="IN" /><el-option label="出库" value="OUT" /></el-select>
          <el-input v-model="filters.code" clearable placeholder="设备编号" /><el-input v-model="filters.assetNumber" clearable placeholder="固定资产编号" />
          <el-input v-model="filters.name" clearable placeholder="设备名称" /><el-input v-model="filters.manufacturer" clearable placeholder="生产厂家" />
          <el-input v-model="filters.verificationOrganization" clearable placeholder="检效单位" /><el-input v-model="filters.technicalSpecification" clearable placeholder="技术指标" />
          <el-input v-model="filters.location" clearable placeholder="存放位置" /><el-input v-model="filters.serialNumber" clearable placeholder="出厂编号" />
        </template>
        <template v-else-if="['equipment-certificates','equipment-calibration'].includes(moduleKey)">
          <el-select v-if="moduleKey === 'equipment-certificates'" v-model="filters.state" clearable placeholder="证书状态"><el-option label="已确认" value="CONFIRMED" /><el-option label="已作废" value="VOID" /></el-select>
          <el-select v-if="moduleKey === 'equipment-certificates'" v-model="filters.uploaded" clearable placeholder="是否上传文件"><el-option label="已上传" value="true" /><el-option label="未上传" value="false" /></el-select>
          <el-input v-model="filters.code" clearable placeholder="设备编号" /><el-input v-model="filters.certificateNumber" clearable placeholder="检定编号" />
          <el-input v-model="filters.name" clearable placeholder="设备名称" /><el-input v-model="filters.model" clearable placeholder="规格型号" />
          <el-input v-model="filters.manufacturer" clearable placeholder="生产厂家" /><el-input v-model="filters.verificationOrganization" clearable placeholder="检校单位" />
          <el-input v-model="filters.operatorName" clearable placeholder="操作人" />
        </template>
        <template v-else-if="moduleKey === 'equipment-service'">
          <el-input v-model="filters.code" clearable placeholder="设备编号" /><el-input v-model="filters.name" clearable placeholder="设备名称" />
          <el-input v-model="filters.model" clearable placeholder="规格型号" /><el-input v-model="filters.verificationOrganization" clearable placeholder="检校单位" />
        </template>
        <template v-else-if="moduleKey === 'equipment-borrow'">
          <el-input v-model="filters.code" clearable placeholder="设备编号" /><el-input v-model="filters.name" clearable placeholder="设备名称" />
          <el-input v-model="filters.model" clearable placeholder="规格型号" /><el-input v-model="filters.borrowerName" clearable placeholder="领用人" />
          <el-input v-model="filters.returnerName" clearable placeholder="归还人" />
        </template>
        <template v-else-if="moduleKey === 'equipment-usage'">
          <el-input v-model="filters.code" clearable placeholder="设备编号" /><el-input v-model="filters.name" clearable placeholder="设备名称" />
          <el-input v-model="filters.model" clearable placeholder="规格型号" /><el-input v-model="filters.commissionNumber" clearable placeholder="委托单号" />
        </template>
        <template v-else>
          <el-input v-model="filters.code" clearable placeholder="设备编号" /><el-input v-model="filters.name" clearable placeholder="设备名称" />
          <el-input v-model="filters.model" clearable placeholder="规格型号" /><el-input v-model="filters.personName" clearable placeholder="操作人" />
        </template>
      </div>
    </section>

    <section :class="['equipment-workspace', { 'with-tree': moduleKey === 'equipment' }]">
      <aside v-if="moduleKey === 'equipment'" class="equipment-category-panel">
        <header><div><strong>设备分类</strong><span>{{ categories.length }} 个节点</span></div><el-button v-if="canWrite" :icon="FolderAdd" circle text aria-label="新增分类" @click="openCategory()" /></header>
        <button :class="{ active: !selectedCategoryId }" @click="chooseCategory()"><span>全部类别</span><b>{{ total }}</b></button>
        <el-tree :data="categoryTree" node-key="id" default-expand-all :expand-on-click-node="false" highlight-current @node-click="chooseCategory">
          <template #default="{ data }"><span class="category-node"><span :title="data.name">{{ data.name }}</span><b>{{ data.equipmentCount }}</b><el-tag v-if="!data.active" size="small" type="info">停用</el-tag><el-dropdown v-if="canWrite" trigger="click" @command="String($event) === 'EDIT' ? openCategory(data) : categoryAction(data,String($event))" @click.stop><el-button text size="small" :icon="MoreFilled" aria-label="分类操作" /><template #dropdown><el-dropdown-menu><el-dropdown-item command="EDIT">编辑</el-dropdown-item><el-dropdown-item :command="data.active ? 'DISABLE' : 'ENABLE'">{{ data.active ? '停用' : '启用' }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></span></template>
        </el-tree>
        <footer v-if="selectedCategoryId && canWrite"><el-button link type="primary" @click="openCategory(categories.find(item => item.id === selectedCategoryId))">编辑当前分类</el-button></footer>
      </aside>

      <main class="equipment-data-panel">
        <section class="equipment-actionbar">
          <template v-if="moduleKey === 'equipment'">
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openEquipment()">添加</el-button>
            <el-button v-if="canWrite" :icon="Edit" :disabled="selectedRows.length !== 1" @click="openEquipment(selected)">修改</el-button>
            <el-button v-if="canWrite" :icon="Delete" :disabled="selectedRows.length !== 1" @click="deleteEquipment">删除</el-button>
            <el-button v-if="canWrite" :disabled="selectedRows.length !== 1" @click="openCertificate(undefined, selected)">检定</el-button>
            <el-button v-if="canTransition" :disabled="selectedRows.length !== 1 || selected?.state !== 'AVAILABLE'" @click="openBorrow(undefined, selected)">领用</el-button>
            <el-button v-if="canTransition" :disabled="selectedRows.length !== 1 || selected?.state !== 'BORROWED'" @click="returnSelectedEquipment">归还</el-button>
            <el-button :icon="Printer" :disabled="selectedRows.length !== 1" @click="printLabel()">设备标签打印</el-button>
            <el-dropdown v-if="canWrite || canTransition" @command="handleEquipmentMore(String($event))">
              <el-button>更多操作<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
              <template #dropdown><el-dropdown-menu><el-dropdown-item v-if="canWrite" command="assign">设定分类</el-dropdown-item><el-dropdown-item v-if="canWrite" command="template">下载导入模板</el-dropdown-item><el-dropdown-item v-if="canWrite" command="import">批量导入</el-dropdown-item><el-dropdown-item v-if="canTransition" :divided="canWrite" command="SUBMIT" :disabled="!selectedRows.length || selectedRows.some(row => !['DRAFT','REJECTED'].includes(row.approvalState))">批量提交</el-dropdown-item><el-dropdown-item v-if="canTransition" command="WITHDRAW" :disabled="!selectedRows.length || selectedRows.some(row => row.approvalState !== 'SUBMITTED')">批量撤回</el-dropdown-item><el-dropdown-item v-if="canTransition" command="APPROVE" :disabled="selectedRows.length !== 1 || selected?.approvalState !== 'SUBMITTED'">审批通过</el-dropdown-item><el-dropdown-item v-if="canTransition" command="REJECT" :disabled="selectedRows.length !== 1 || selected?.approvalState !== 'SUBMITTED'">驳回</el-dropdown-item></el-dropdown-menu></template>
            </el-dropdown>
            <input ref="importInput" hidden type="file" accept=".xlsx" @change="importWorkbook($event,false)" />
          </template>
          <template v-else-if="moduleKey === 'equipment-certificates'">
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCertificate()">添加</el-button><el-button v-if="canWrite" :icon="Edit" :disabled="selectedRows.length !== 1 || selected?.state === 'VOID'" @click="openCertificate(selected)">修改</el-button>
            <el-button v-if="canWrite" :icon="Upload" @click="certificateImportInput?.click()">批量导入</el-button><el-button v-if="canWrite" :icon="Delete" :disabled="selectedRows.length !== 1 || selected?.state === 'VOID'" @click="voidCertificate">删除 / 作废</el-button>
            <el-button @click="download('/api/equipment/certificates/import-template','设备检定-导入模板.xlsx')">导入模板</el-button>
            <input ref="certificateImportInput" hidden type="file" accept=".xlsx" @change="importWorkbook($event,true)" />
          </template>
          <template v-else-if="moduleKey === 'equipment-service'">
            <el-button v-if="inspectionTab === 'outbound' && canTransition" type="primary" :icon="Plus" @click="openInspection">添加送检</el-button>
          </template>
          <template v-else-if="moduleKey === 'equipment-borrow'">
            <el-button v-if="canTransition" type="primary" :icon="Plus" @click="openBorrow()">领用</el-button><el-button v-if="canTransition" :disabled="selectedRows.length !== 1 || !['BORROWED','TRANSFERRED'].includes(selected?.state)" @click="openBorrowAction(selected,'RETURN')">归还</el-button>
            <el-button v-if="canTransition" :icon="Edit" :disabled="selectedRows.length !== 1 || selected?.state !== 'BORROWED'" @click="openBorrow(selected)">修改</el-button><el-button v-if="canTransition" :disabled="selectedRows.length !== 1 || !['BORROWED','TRANSFERRED'].includes(selected?.state)" @click="openBorrowAction(selected,'VOID')">作废记录</el-button><el-button v-if="canTransition" :disabled="selectedRows.length !== 1 || !['BORROWED','TRANSFERRED'].includes(selected?.state)" @click="openBorrowAction(selected,'TRANSFER')">移交</el-button>
          </template>
          <span class="toolbar-spacer" />
          <el-button v-if="canExport" class="equipment-export-button" :icon="Download" @click="exportCurrent">导出当前结果</el-button>
        </section>

        <el-table v-if="moduleKey === 'equipment'" v-loading="loading" :data="rows" border stripe class="equipment-table" @selection-change="selectedRows = $event">
          <el-table-column type="selection" width="44" fixed />
          <el-table-column label="设备标识" width="255" fixed><template #default="{ row }"><div class="table-cell-stack"><strong>{{ row.name }}</strong><span>{{ row.code }} · {{ row.model }}</span><span>固定资产：{{ row.assetNumber || '未录入' }}</span></div></template></el-table-column>
          <el-table-column label="购置与位置" width="205"><template #default="{ row }"><div class="table-cell-stack"><strong>{{ row.purchasePrice == null ? '单价未录入' : `${row.purchasePrice} 元` }}</strong><span>{{ labels[row.equipmentType] || '设备类型未设定' }}</span><span>{{ row.location || '存放位置未设定' }}</span></div></template></el-table-column>
          <el-table-column label="生产与技术指标" min-width="260"><template #default="{ row }"><div class="table-cell-stack"><strong>{{ row.manufacturer || '生产厂家未录入' }}</strong><span class="cell-clamp">{{ row.technicalSpecification || '技术指标未录入' }}</span><span>出厂编号：{{ row.serialNumber || '未录入' }}</span></div></template></el-table-column>
          <el-table-column label="启停 / 性能 / 报废" width="185" align="center"><template #default="{ row }"><div class="status-stack"><el-tag :type="row.enabled ? 'success' : 'info'" effect="plain">{{ row.enabled ? '启用' : '停用' }}</el-tag><el-tag :type="typeOfStatus(row.performanceStatus)" effect="plain">{{ labels[row.performanceStatus] }}</el-tag><el-tag :type="row.state === 'SCRAPPED' ? 'danger' : 'info'" effect="plain">{{ row.state === 'SCRAPPED' ? '已报废' : '未报废' }}</el-tag></div></template></el-table-column>
          <el-table-column label="检定控制" width="265"><template #default="{ row }"><div class="table-cell-stack"><strong><el-tag :type="typeOfStatus(row.verificationStatus)" effect="plain">{{ labels[row.verificationStatus] }}</el-tag></strong><span>周期：{{ row.verificationCycleMonths ? `${row.verificationCycleMonths} 个月` : '未设定' }} · {{ row.latestVerificationOrganization || '检效单位未录入' }}</span><span>{{ row.latestVerificationDate || '检定日期未录入' }} → {{ row.certificateValidUntil || '有效期未录入' }}</span></div></template></el-table-column>
          <el-table-column label="库存与审批" width="155"><template #default="{ row }"><div class="table-cell-stack"><strong>{{ row.inventoryStatus === 'IN' ? '在库' : '出库' }}</strong><span>{{ labels[row.state] }}</span><el-tag :type="typeOfStatus(row.approvalState)" effect="plain">{{ labels[row.approvalState] }}</el-tag></div></template></el-table-column>
          <el-table-column label="操作" fixed="right" width="155" align="center"><template #default="{ row }"><div class="table-actions table-actions--center"><el-button link type="primary" @click="openEquipment(row)">查看</el-button><el-dropdown v-if="row.allowedActions?.length" @command="String($event) === 'UPDATE' ? openEquipment(row) : equipmentAction(row,String($event))"><el-button link type="primary">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="action in row.allowedActions.filter((item: string) => !['DELETE','START_USE','BORROW','SEND_INSPECTION','SEND_CALIBRATION','STOCK_OUT'].includes(item))" :key="action" :command="action">{{ action === 'UPDATE' ? '修改' : actionLabels[action] ?? action }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></template></el-table-column>
          <template #empty><el-empty description="当前筛选条件下没有设备" /></template>
        </el-table>

        <el-table v-else-if="moduleKey === 'equipment-certificates'" v-loading="loading" :data="rows" border stripe class="equipment-table" @selection-change="selectedRows = $event">
          <el-table-column type="selection" width="44" fixed /><el-table-column prop="equipmentCode" label="设备编号" width="145" fixed />
          <el-table-column prop="equipmentName" label="设备名称" min-width="175" /><el-table-column prop="model" label="规格型号" width="130" /><el-table-column prop="manufacturer" label="生产厂家" min-width="150" show-overflow-tooltip />
          <el-table-column prop="certificateNumber" label="检定编号" width="150" /><el-table-column label="检校类型" width="115"><template #default="{ row }">{{ labels[row.inspectionType] || labels[row.certificateType] }}</template></el-table-column><el-table-column prop="issuingOrganization" label="检校单位" min-width="165" show-overflow-tooltip />
          <el-table-column prop="issuedOn" label="检定日期" width="110" /><el-table-column prop="validUntil" label="有效期至" width="110" /><el-table-column label="检定状态" width="105" align="center"><template #default="{ row }"><div class="table-tag-cell"><el-tag :type="typeOfStatus(row.verificationStatus)" effect="plain">{{ labels[row.verificationStatus] }}</el-tag></div></template></el-table-column>
          <el-table-column label="检校结论" width="100" align="center"><template #default="{ row }"><div class="table-tag-cell"><el-tag :type="typeOfStatus(row.result)" effect="plain">{{ labels[row.result] }}</el-tag></div></template></el-table-column><el-table-column label="文件" width="90"><template #default="{ row }">{{ row.fileId ? '已上传' : '未上传' }}</template></el-table-column><el-table-column prop="operatorName" label="操作人" width="110" /><el-table-column label="操作时间" width="165"><template #default="{ row }">{{ formatTime(row.updatedAt) }}</template></el-table-column>
          <template #empty><el-empty description="暂无检校记录" /></template>
        </el-table>

        <el-table v-else-if="moduleKey === 'equipment-calibration'" v-loading="loading" :data="rows" border stripe class="equipment-table">
          <el-table-column prop="equipmentCode" label="设备编号" width="145" fixed /><el-table-column prop="equipmentName" label="设备名称" min-width="190" /><el-table-column prop="model" label="规格型号" width="150" />
          <el-table-column label="校准类型" width="140"><template #default="{ row }">{{ labels[row.inspectionType] || labels[row.certificateType] }}</template></el-table-column><el-table-column label="校准结果" width="120" align="center"><template #default="{ row }"><div class="table-tag-cell"><el-tag :type="typeOfStatus(row.result)" effect="plain">{{ labels[row.result] }}</el-tag></div></template></el-table-column><el-table-column prop="issuedOn" label="校准时间" width="130" /><el-table-column prop="operatorName" label="操作人" width="130" /><el-table-column label="操作时间" min-width="180"><template #default="{ row }">{{ formatTime(row.updatedAt) }}</template></el-table-column>
          <template #empty><el-empty description="暂无校准记录" /></template>
        </el-table>

        <el-table v-else-if="moduleKey === 'equipment-service'" v-loading="loading" :data="inspectionRows" border stripe class="equipment-table" @selection-change="selectedRows = $event">
          <el-table-column type="selection" width="44" fixed /><el-table-column prop="equipmentCode" label="设备编号" width="145" fixed /><el-table-column prop="equipmentName" label="设备名称" min-width="170" /><el-table-column prop="model" label="规格型号" width="125" />
          <el-table-column label="出库" width="175"><template #default="{ row }"><div class="table-cell-stack"><strong>{{ row.outboundPersonName }}</strong><span>{{ formatTime(row.outboundAt) }}</span><span>配件 {{ row.outboundAccessoryQuantity }}</span></div></template></el-table-column>
          <el-table-column label="接收 / 送检" width="175"><template #default="{ row }"><div class="table-cell-stack"><strong>{{ row.receivedByName || '待接收' }}</strong><span>{{ formatTime(row.receivedAt) }}</span><span>{{ row.destination || '去向未录入' }}</span></div></template></el-table-column>
          <el-table-column prop="inspectionOrganization" label="检校单位" min-width="170" show-overflow-tooltip /><el-table-column prop="inspectionNumber" label="检校编号" width="145" />
          <el-table-column label="检定填报" width="185"><template #default="{ row }"><div class="table-cell-stack"><strong>{{ row.inspectionDate || '待填报' }}</strong><span>{{ row.filledByName || '填报人未产生' }}</span><span>{{ labels[row.inspectionType] || '类型未录入' }}</span></div></template></el-table-column>
          <el-table-column label="确认 / 审核" width="195"><template #default="{ row }"><div class="table-cell-stack"><strong>{{ row.confirmedByName || '待确认' }} / {{ row.reviewedByName || '待审核' }}</strong><span>{{ formatTime(row.confirmedAt) }}</span><span>{{ formatTime(row.reviewedAt) }}</span></div></template></el-table-column>
          <el-table-column label="有效期 / 入库" width="185"><template #default="{ row }"><div class="table-cell-stack"><strong>{{ row.validUntil || '有效期未录入' }}</strong><span>{{ row.stockInByName || '待入库' }}</span><span>{{ formatTime(row.stockInAt) }}</span></div></template></el-table-column>
          <el-table-column label="检定情况" width="110" align="center"><template #default="{ row }"><div class="table-tag-cell"><el-tag :type="typeOfStatus(row.result)" effect="plain">{{ labels[row.result] ?? '待填报' }}</el-tag></div></template></el-table-column><el-table-column label="出入库情况" width="120"><template #default="{ row }">{{ ['RETURNED','VOID'].includes(row.state) ? '已入库' : '已出库' }}</template></el-table-column><el-table-column label="流程状态" width="110" align="center"><template #default="{ row }"><div class="table-tag-cell"><el-tag :type="typeOfStatus(row.state)" effect="plain">{{ labels[row.state] }}</el-tag></div></template></el-table-column>
          <el-table-column label="操作" fixed="right" width="145" align="center"><template #default="{ row }"><div class="table-actions table-actions--center">
            <el-button v-if="inspectionRowActions(row)[0]" link type="primary" @click="openInspectionAction(row,inspectionRowActions(row)[0])">{{ actionLabels[inspectionRowActions(row)[0]] }}</el-button>
            <el-dropdown v-if="inspectionRowActions(row).length > 1" @command="openInspectionAction(row,String($event))"><el-button link type="primary">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="action in inspectionRowActions(row).slice(1)" :key="action" :command="action">{{ actionLabels[action] }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
          </div></template></el-table-column>
          <template #empty><el-empty :description="`当前“${inspectionTab === 'ledger' ? '检定台账' : '流程阶段'}”无数据`" /></template>
        </el-table>

        <el-table v-else-if="moduleKey === 'equipment-borrow'" v-loading="loading" :data="rows" border stripe class="equipment-table" @selection-change="selectedRows = $event">
          <el-table-column type="selection" width="44" fixed /><el-table-column prop="equipmentCode" label="设备编号" width="145" fixed /><el-table-column prop="equipmentName" label="设备名称" min-width="170" /><el-table-column prop="model" label="规格型号" width="125" /><el-table-column prop="borrowerName" label="领用人员" width="105" /><el-table-column label="领用时间" width="145"><template #default="{ row }">{{ formatDatePeriod(row.borrowedAt,row.borrowedPeriod) }}</template></el-table-column><el-table-column prop="outboundAccessoryQuantity" label="领用配件量" width="110" /><el-table-column prop="destination" label="设备去向" min-width="150" show-overflow-tooltip /><el-table-column label="领用状态" width="105" align="center"><template #default="{ row }"><div class="table-tag-cell"><el-tag :type="typeOfStatus(row.beforeCondition)" effect="plain">{{ labels[row.beforeCondition] }}</el-tag></div></template></el-table-column><el-table-column prop="returnedByName" label="归还人员" width="105" /><el-table-column label="归还时间" width="145"><template #default="{ row }">{{ formatDatePeriod(row.returnedAt,row.returnPeriod) }}</template></el-table-column><el-table-column prop="returnedAccessoryQuantity" label="归还配件量" width="110" /><el-table-column label="归还状态" width="105" align="center"><template #default="{ row }"><div class="table-tag-cell"><el-tag v-if="row.afterCondition" :type="typeOfStatus(row.afterCondition)" effect="plain">{{ labels[row.afterCondition] }}</el-tag><span v-else>待归还</span></div></template></el-table-column><el-table-column label="流程状态" width="110" align="center"><template #default="{ row }"><div class="table-tag-cell"><el-tag :type="typeOfStatus(row.state)" effect="plain">{{ labels[row.state] }}</el-tag></div></template></el-table-column>
          <template #empty><el-empty description="暂无领用归还记录" /></template>
        </el-table>

        <el-table v-else-if="moduleKey === 'equipment-usage'" v-loading="loading" :data="rows" border stripe class="equipment-table">
          <el-table-column prop="equipmentCode" label="设备编号" width="145" fixed /><el-table-column prop="sampleName" label="使用样品" min-width="150" /><el-table-column prop="equipmentName" label="设备名称" min-width="170" /><el-table-column prop="model" label="规格型号" width="125" /><el-table-column label="设备类型" width="120"><template #default="{ row }">{{ labels[row.equipmentType] ?? '—' }}</template></el-table-column><el-table-column prop="location" label="设备放置场所" min-width="145" /><el-table-column prop="usedByName" label="使用人" width="105" /><el-table-column label="开始时间" width="165"><template #default="{ row }">{{ formatTime(row.startedAt) }}</template></el-table-column><el-table-column label="结束时间" width="165"><template #default="{ row }">{{ formatTime(row.endedAt) }}</template></el-table-column><el-table-column prop="temperature" label="温度(℃)" width="90" /><el-table-column prop="humidity" label="湿度(%)" width="90" /><el-table-column label="使用前状态" width="110"><template #default="{ row }">{{ labels[row.beforeCondition] }}</template></el-table-column><el-table-column label="使用后状态" width="110"><template #default="{ row }">{{ labels[row.afterCondition] ?? '使用中' }}</template></el-table-column>
          <template #empty><el-empty description="暂无使用记录；记录由试验业务自动生成" /></template>
        </el-table>

        <el-table v-else v-loading="loading" :data="rows" border stripe class="equipment-table">
          <el-table-column prop="equipmentCode" label="设备编号" width="145" fixed /><el-table-column prop="equipmentName" label="设备名称" min-width="175" /><el-table-column prop="model" label="规格型号" width="130" /><el-table-column label="操作类型" width="100" align="center"><template #default="{ row }"><div class="table-tag-cell"><el-tag :type="row.operationType === 'IN' ? 'success' : 'warning'" effect="plain">{{ labels[row.operationType] }}</el-tag></div></template></el-table-column><el-table-column label="出入库类型" width="125"><template #default="{ row }">{{ labels[row.movementType] }}</template></el-table-column><el-table-column prop="accessoryQuantity" label="配件数量" width="105" /><el-table-column label="出入库时间" width="170"><template #default="{ row }">{{ formatTime(row.occurredAt) }}</template></el-table-column><el-table-column prop="personName" label="出入库人员" width="115" /><el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
          <template #empty><el-empty description="暂无出入库记录" /></template>
        </el-table>

        <footer class="equipment-table-footer"><span>共 {{ total }} 条</span><div><el-button :disabled="!cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="!nextCursor" @click="nextPage">下一页</el-button></div></footer>
      </main>
    </section>

    <el-drawer v-model="equipmentDrawer" :title="editing ? '修改设备主档' : '设备建档'" size="780px" class="equipment-drawer">
      <el-alert v-if="editing && editing.approvalState === 'APPROVED'" title="已审批设备修改后将回到草稿，重新提交审批前不能进入新业务。" type="warning" :closable="false" />
      <el-tabs v-model="activeEquipmentTab" class="equipment-form-tabs">
        <el-tab-pane label="基本信息" name="basic"><el-form label-position="top">
          <div class="form-grid"><el-form-item label="设备编号" required><el-input v-model="equipmentForm.code" maxlength="64" /></el-form-item><el-form-item label="固定资产编号"><el-input v-model="equipmentForm.assetNumber" maxlength="80" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="设备名称" required><el-input v-model="equipmentForm.name" maxlength="200" /></el-form-item><el-form-item label="规格型号" required><el-input v-model="equipmentForm.model" maxlength="120" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="所属分类"><el-tree-select v-model="equipmentForm.categoryId" :data="categoryTree" node-key="id" :props="{ label: 'name', children: 'children', disabled: (data: Category) => !data.active }" clearable check-strictly /></el-form-item><el-form-item label="生产厂家"><el-input v-model="equipmentForm.manufacturer" maxlength="240" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="购置日期"><el-date-picker v-model="equipmentForm.purchasedOn" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="购置单价（元）"><el-input-number v-model="equipmentForm.purchasePrice" :min="0" :precision="2" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="出厂编号"><el-input v-model="equipmentForm.serialNumber" maxlength="120" /></el-form-item><el-form-item label="保管人"><el-select v-model="equipmentForm.custodianId" clearable filterable><el-option v-for="item in people" :key="item.id" :label="`${item.name} · ${item.employeeNumber}`" :value="item.id" /></el-select></el-form-item></div>
          <div class="form-grid"><el-form-item label="存放位置"><el-input v-model="equipmentForm.location" maxlength="160" /></el-form-item><el-form-item label="设备类型"><el-select v-model="equipmentForm.equipmentType" clearable><el-option v-for="item in equipmentTypes" :key="item" :label="labels[item]" :value="item" /></el-select></el-form-item></div>
          <el-form-item label="桩基设备类型"><el-select v-model="equipmentForm.pileEquipmentType" clearable filterable><el-option v-for="item in pileTypes" :key="item[0]" :label="item[1]" :value="item[0]" /></el-select></el-form-item>
          <div class="equipment-switches"><el-checkbox v-model="equipmentForm.primaryEquipment">主要设备</el-checkbox><el-checkbox v-model="equipmentForm.enabled">启用</el-checkbox><el-radio-group v-model="equipmentForm.performanceStatus"><el-radio value="NORMAL">性能正常</el-radio><el-radio value="ABNORMAL">性能异常</el-radio></el-radio-group></div>
        </el-form></el-tab-pane>
        <el-tab-pane label="检校与技术指标" name="technical"><el-form label-position="top">
          <div class="form-grid"><el-form-item label="检定周期（月）"><el-input-number v-model="equipmentForm.verificationCycleMonths" :min="1" /></el-form-item><el-form-item label="检校类型"><el-select v-model="equipmentForm.verificationType" clearable><el-option v-for="item in verificationTypes" :key="item" :label="labels[item]" :value="item" /></el-select></el-form-item></div>
          <el-form-item label="技术指标"><el-input v-model="equipmentForm.technicalSpecification" type="textarea" :rows="4" maxlength="1000" show-word-limit /></el-form-item>
          <div class="form-grid"><el-form-item label="量程"><el-input v-model="equipmentForm.nominalRange" maxlength="240" /></el-form-item><el-form-item label="测量范围"><el-input v-model="equipmentForm.measurementRange" maxlength="240" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="分度值"><el-input v-model="equipmentForm.resolution" maxlength="120" /></el-form-item><el-form-item label="准确度"><el-input v-model="equipmentForm.accuracy" maxlength="120" /></el-form-item></div>
        </el-form></el-tab-pane>
        <el-tab-pane label="限定检测项目" name="scope"><div class="project-scope-heading"><div><strong>设备可用检测项目</strong><p>空选择表示不限项目；选择后试验分派会硬性校验适用范围。</p></div><el-tag>{{ flatProjects.length }} 个已发布项目</el-tag></div><el-tree ref="projectTreeRef" :data="detectionTree" node-key="id" show-checkbox default-expand-all :props="{ label: 'name', children: 'children', disabled: (data: CatalogNode) => data.type !== 'PROJECT' }" class="equipment-project-tree" /></el-tab-pane>
        <el-tab-pane label="附件与备注" name="files"><el-form label-position="top">
          <div class="form-grid"><el-form-item label="设备附件（PDF/图片）"><label class="file-picker"><input type="file" accept="application/pdf,image/png,image/jpeg" @change="uploadFile($event,'attachmentFileId','attachmentFileName','EQUIPMENT_ATTACHMENT')" /><el-icon><Upload /></el-icon>{{ equipmentForm.attachmentFileName || '选择文件' }}</label></el-form-item><el-form-item label="设备照片"><label class="file-picker"><input type="file" accept="image/png,image/jpeg" @change="uploadFile($event,'photoFileId','photoFileName','EQUIPMENT_PHOTO')" /><el-icon><Upload /></el-icon>{{ equipmentForm.photoFileName || '选择图片' }}</label></el-form-item></div>
          <el-form-item label="备注"><el-input v-model="equipmentForm.remark" type="textarea" :rows="6" maxlength="2000" show-word-limit /></el-form-item>
        </el-form></el-tab-pane>
      </el-tabs>
      <template #footer><el-button @click="equipmentDrawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveEquipment">保存设备主档</el-button></template>
    </el-drawer>

    <el-dialog v-model="categoryDialog" :title="editing ? '编辑设备分类' : '新增设备分类'" width="520px"><el-form label-position="top"><div class="form-grid"><el-form-item label="分类编码" required><el-input v-model="categoryForm.code" maxlength="64" /></el-form-item><el-form-item label="分类名称" required><el-input v-model="categoryForm.name" maxlength="160" /></el-form-item></div><el-form-item label="上级分类"><el-tree-select v-model="categoryForm.parentId" :data="categoryTree" node-key="id" :props="{ label: 'name', children: 'children' }" clearable check-strictly /></el-form-item><el-form-item label="排序"><el-input-number v-model="categoryForm.sortOrder" :min="0" /></el-form-item></el-form><template #footer><el-button @click="categoryDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveCategory">保存</el-button></template></el-dialog>

    <el-dialog v-model="certificateDialog" :title="editing ? '修改检校证书' : '录入检校证书'" width="660px"><el-form label-position="top">
      <el-form-item label="设备" required><el-select v-model="certificateForm.equipmentId" :disabled="!!editing" filterable><el-option v-for="item in equipmentOptions" :key="item.id" :label="`${item.code} · ${item.name} · ${item.model}`" :value="item.id" /></el-select></el-form-item>
      <div class="form-grid"><el-form-item label="证书类型" required><el-select v-model="certificateForm.certificateType"><el-option label="检定" value="VERIFICATION" /><el-option label="校准" value="CALIBRATION" /></el-select></el-form-item><el-form-item label="检校类型"><el-select v-model="certificateForm.inspectionType" clearable><el-option v-for="item in verificationTypes" :key="item" :label="labels[item]" :value="item" /></el-select></el-form-item></div>
      <div class="form-grid"><el-form-item label="检校编号" required><el-input v-model="certificateForm.certificateNumber" maxlength="120" /></el-form-item><el-form-item label="检校单位" required><el-input v-model="certificateForm.issuingOrganization" maxlength="240" /></el-form-item></div>
      <div class="form-grid"><el-form-item label="检定日期" required><el-date-picker v-model="certificateForm.issuedOn" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="有效期至" required><el-date-picker v-model="certificateForm.validUntil" value-format="YYYY-MM-DD" /></el-form-item></div>
      <div class="form-grid"><el-form-item label="确认日期"><el-date-picker v-model="certificateForm.confirmedOn" value-format="YYYY-MM-DD" clearable /></el-form-item><el-form-item label="检校结论" required><el-select v-model="certificateForm.result"><el-option v-for="item in ['PASS','LIMITED','FAIL']" :key="item" :label="labels[item]" :value="item" /></el-select></el-form-item></div>
      <el-form-item label="证书文件（图片/PDF）"><label class="file-picker"><input type="file" accept="application/pdf,image/png,image/jpeg" @change="uploadFile($event,'fileId','fileName','EQUIPMENT_CERTIFICATE')" /><el-icon><Upload /></el-icon>{{ certificateForm.fileName || '选择证书文件' }}</label></el-form-item>
      <el-form-item label="备注"><el-input v-model="certificateForm.remark" type="textarea" :rows="3" maxlength="2000" /></el-form-item>
    </el-form><template #footer><el-button @click="certificateDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveCertificate">保存证书</el-button></template></el-dialog>

    <el-dialog v-model="inspectionDialog" title="送检出库" width="680px"><el-form label-position="top">
      <el-form-item label="设备" required><el-select v-model="inspectionForm.equipmentId" filterable remote reserve-keyword :remote-method="loadEquipmentOptions" @change="selectedEquipmentChanged(inspectionForm)"><el-option v-for="item in availableEquipmentOptions" :key="item.id" :label="`${item.code} · ${item.name} · ${item.model} · ${item.location ?? '未设定位置'}`" :value="item.id" /></el-select></el-form-item>
      <div class="form-grid"><el-form-item label="出库人" required><el-select v-model="inspectionForm.outboundPersonId" filterable><el-option v-for="item in people" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item><el-form-item label="出库时间" required><el-date-picker v-model="inspectionForm.outboundAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" /></el-form-item></div>
      <div class="form-grid"><el-form-item label="出库配件数量"><el-input-number v-model="inspectionForm.outboundAccessoryQuantity" :min="0" :precision="3" /></el-form-item><el-form-item label="设备去向"><el-input v-model="inspectionForm.destination" maxlength="500" /></el-form-item></div>
      <div class="form-grid"><el-form-item label="拍照记录"><label class="file-picker"><input type="file" accept="image/png,image/jpeg" @change="uploadFile($event,'photoFileId','photoFileName','EQUIPMENT_INSPECTION_PHOTO')" /><el-icon><Upload /></el-icon>{{ inspectionForm.photoFileName || '选择图片' }}</label></el-form-item><el-form-item label="附件上传"><label class="file-picker"><input type="file" accept="application/pdf,image/png,image/jpeg" @change="uploadFile($event,'attachmentFileId','attachmentFileName','EQUIPMENT_INSPECTION_ATTACHMENT')" /><el-icon><Upload /></el-icon>{{ inspectionForm.attachmentFileName || '选择文件' }}</label></el-form-item></div>
      <el-form-item label="备注"><el-input v-model="inspectionForm.remark" type="textarea" :rows="3" /></el-form-item>
    </el-form><template #footer><el-button @click="inspectionDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveInspection">确认出库</el-button></template></el-dialog>

    <el-dialog v-model="inspectionActionDialog" :title="actionLabels[inspectionActionForm.action]" width="660px"><el-form label-position="top">
      <el-alert :title="editing ? `${editing.equipmentCode} · ${editing.equipmentName} · 当前状态：${labels[editing.state]}` : ''" type="info" :closable="false" />
      <div v-if="inspectionActionForm.action !== 'WITHDRAW'" class="form-grid action-form-row"><el-form-item label="操作人员" required><el-select v-model="inspectionActionForm.personId" filterable><el-option v-for="item in people" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item><el-form-item v-if="['RECEIVE','RETURN','VOID'].includes(inspectionActionForm.action)" label="操作时间" required><el-date-picker v-model="inspectionActionForm.occurredAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" /></el-form-item></div>
      <template v-if="inspectionActionForm.action === 'REPORT'">
        <div class="form-grid"><el-form-item label="检校类型" required><el-select v-model="inspectionActionForm.inspectionType"><el-option v-for="item in verificationTypes" :key="item" :label="labels[item]" :value="item" /></el-select></el-form-item><el-form-item label="检校编号" required><el-input v-model="inspectionActionForm.inspectionNumber" /></el-form-item></div>
        <el-form-item label="检校单位" required><el-input v-model="inspectionActionForm.inspectionOrganization" /></el-form-item><div class="form-grid"><el-form-item label="检定日期" required><el-date-picker v-model="inspectionActionForm.inspectionDate" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="有效期至" required><el-date-picker v-model="inspectionActionForm.validUntil" value-format="YYYY-MM-DD" /></el-form-item></div><div class="form-grid"><el-form-item label="检校结论" required><el-select v-model="inspectionActionForm.result"><el-option v-for="item in ['PASS','LIMITED','FAIL']" :key="item" :label="labels[item]" :value="item" /></el-select></el-form-item><el-form-item label="证书文件"><label class="file-picker"><input type="file" accept="application/pdf,image/png,image/jpeg" @change="uploadFile($event,'certificateFileId','certificateFileName','EQUIPMENT_CERTIFICATE')" /><el-icon><Upload /></el-icon>{{ inspectionActionForm.certificateFileName || '选择文件' }}</label></el-form-item></div>
      </template>
      <el-form-item v-if="inspectionActionForm.action === 'RETURN'" label="入库配件数量"><el-input-number v-model="inspectionActionForm.accessoryQuantity" :min="0" :precision="3" /></el-form-item>
      <el-form-item v-if="['VOID','WITHDRAW'].includes(inspectionActionForm.action)" label="撤回原因" required><el-input v-model="inspectionActionForm.comment" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
    </el-form><template #footer><el-button @click="inspectionActionDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveInspectionAction">确认{{ actionLabels[inspectionActionForm.action] }}</el-button></template></el-dialog>

    <el-dialog v-model="borrowDialog" :title="editing ? '修改领用记录' : '设备领用'" width="720px"><el-form label-position="top">
      <el-form-item label="设备" required><el-select v-model="borrowForm.equipmentId" :disabled="!!editing" filterable remote reserve-keyword :remote-method="loadEquipmentOptions" @change="selectedEquipmentChanged(borrowForm)"><el-option v-for="item in equipmentOptions.filter(value => availableEquipmentOptions.some(option => option.id === value.id) || value.id === borrowForm.equipmentId)" :key="item.id" :label="`${item.code} · ${item.name} · ${item.model}`" :value="item.id" /></el-select></el-form-item>
      <div class="form-grid"><el-form-item label="领用人员" required><el-select v-model="borrowForm.borrowerId" filterable><el-option v-for="item in people" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item><el-form-item label="同行人员"><el-select v-model="borrowForm.companionIds" multiple filterable collapse-tags><el-option v-for="item in people" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item></div>
      <div class="form-grid"><el-form-item label="领用时间" required><div class="date-period"><el-date-picker v-model="borrowForm.borrowedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" /><el-select v-model="borrowForm.borrowedPeriod"><el-option label="上午" value="AM" /><el-option label="下午" value="PM" /></el-select></div></el-form-item><el-form-item label="计划归还时间" required><div class="date-period"><el-date-picker v-model="borrowForm.plannedReturnAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" /><el-select v-model="borrowForm.plannedReturnPeriod"><el-option label="上午" value="AM" /><el-option label="下午" value="PM" /></el-select></div></el-form-item></div>
      <div class="form-grid"><el-form-item label="设备状态" required><el-radio-group v-model="borrowForm.beforeCondition"><el-radio value="NORMAL">正常</el-radio><el-radio value="ABNORMAL">异常</el-radio></el-radio-group></el-form-item><el-form-item label="领用配件数量"><el-input-number v-model="borrowForm.accessoryQuantity" :min="0" :precision="3" /></el-form-item></div>
      <div class="address-grid"><el-input v-model="borrowForm.province" placeholder="省" maxlength="80" /><el-input v-model="borrowForm.city" placeholder="市" maxlength="80" /><el-input v-model="borrowForm.district" placeholder="区/县" maxlength="80" /></div><el-form-item label="详细地址"><el-input v-model="borrowForm.address" maxlength="500" /></el-form-item><el-form-item label="设备去向"><el-input v-model="borrowForm.destination" maxlength="500" /></el-form-item>
      <el-form-item label="领用拍照"><label class="file-picker"><input type="file" accept="image/png,image/jpeg" @change="uploadFile($event,'photoFileId','photoFileName','EQUIPMENT_BORROW_PHOTO')" /><el-icon><Upload /></el-icon>{{ borrowForm.photoFileName || '选择图片' }}</label></el-form-item><el-form-item label="备注"><el-input v-model="borrowForm.remark" type="textarea" :rows="3" maxlength="2000" /></el-form-item>
    </el-form><template #footer><el-button @click="borrowDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveBorrow">{{ editing ? '保存修改' : '确认领用' }}</el-button></template></el-dialog>

    <el-dialog v-model="borrowActionDialog" :title="actionLabels[borrowActionForm.action]" width="580px"><el-form label-position="top"><el-alert :title="editing ? `${editing.equipmentCode} · ${editing.equipmentName} · ${labels[editing.state]}` : ''" type="info" :closable="false" /><div class="form-grid action-form-row"><el-form-item :label="borrowActionForm.action === 'TRANSFER' ? '移交给' : borrowActionForm.action === 'RETURN' ? '归还人员' : '作废操作人'" required><el-select v-model="borrowActionForm.personId" filterable><el-option v-for="item in people" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item><el-form-item label="操作时间" required><el-date-picker v-model="borrowActionForm.occurredAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" /></el-form-item></div><template v-if="borrowActionForm.action === 'RETURN'"><div class="form-grid"><el-form-item label="归还时段"><el-select v-model="borrowActionForm.period"><el-option label="上午" value="AM" /><el-option label="下午" value="PM" /></el-select></el-form-item><el-form-item label="归还后状态"><el-select v-model="borrowActionForm.condition"><el-option label="正常" value="NORMAL" /><el-option label="异常" value="ABNORMAL" /></el-select></el-form-item></div><el-form-item label="归还配件数量"><el-input-number v-model="borrowActionForm.accessoryQuantity" :min="0" :precision="3" /></el-form-item></template><el-form-item v-if="['TRANSFER','VOID'].includes(borrowActionForm.action)" :label="borrowActionForm.action === 'TRANSFER' ? '移交原因' : '作废原因'" required><el-input v-model="borrowActionForm.comment" type="textarea" :rows="3" maxlength="1000" /></el-form-item></el-form><template #footer><el-button @click="borrowActionDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveBorrowAction">确认{{ actionLabels[borrowActionForm.action] }}</el-button></template></el-dialog>
  </section>
</template>

<style scoped>
.equipment-heading { align-items: center; }
.equipment-heading h1 { margin-bottom: 4px; }
.equipment-heading p:last-child { margin: 4px 0 0; color: var(--muted); font-size: 13px; }
.heading-metrics { display: flex; gap: 10px; }
.heading-metrics span { min-width: 112px; min-height: 64px; display: grid; place-items: center; align-content: center; gap: 2px; padding: 8px 14px; text-align: center; border: 1px solid var(--line); border-radius: 8px; background: #fff; }
.heading-metrics b { color: #203754; font-size: 20px; font-variant-numeric: tabular-nums; line-height: 1.15; }
.heading-metrics small { color: var(--muted); font-size: 13px; line-height: 1.2; }
.equipment-flow-tabs { margin: -2px 0 10px; padding: 0 16px; border: 1px solid var(--line); border-radius: 9px; background: #fff; }
.equipment-flow-tabs :deep(.el-tabs__header) { margin: 0; }
.equipment-flow-tabs :deep(.el-tabs__content) { display: none; }
.equipment-filter { display: grid; grid-template-columns: minmax(250px, 1.35fr) minmax(215px, .95fr) repeat(3, minmax(128px, .62fr)) auto; align-items: center; gap: 8px; padding: 12px; border: 1px solid var(--line); border-radius: 10px 10px 0 0; background: #fff; }
.equipment-filter :deep(.el-select), .equipment-filter :deep(.el-date-editor) { width: 100%; }
.equipment-filter-advanced { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(5, minmax(150px, 1fr)); gap: 8px; padding-top: 10px; border-top: 1px dashed #dce4ef; }
.foundation-filter-actions { display: flex; align-items: center; gap: 2px; white-space: nowrap; }
.equipment-filter > .foundation-filter-actions { grid-column: -2 / -1; justify-self: end; }
.equipment-page { display: flex; min-height: 0; flex-direction: column; overflow: hidden; }
.equipment-heading, .equipment-flow-tabs, .equipment-filter { flex: 0 0 auto; }
.equipment-workspace { min-width: 0; min-height: 0; display: flex; flex: 1 1 0; flex-direction: column; }
.equipment-workspace.with-tree { display: grid; grid-template-columns: 238px minmax(0, 1fr); min-height: 0; }
.equipment-category-panel { display: flex; flex-direction: column; min-width: 0; border: 1px solid var(--line); border-top: 0; border-radius: 0 0 0 10px; background: #fff; }
.equipment-category-panel header { min-height: 58px; display: flex; align-items: center; justify-content: space-between; padding: 10px 12px; border-bottom: 1px solid #edf1f6; }
.equipment-category-panel header strong, .equipment-category-panel header span { display: block; }
.equipment-category-panel header strong { color: #253c5b; font-size: 14px; }
.equipment-category-panel header span { margin-top: 2px; color: var(--muted); font-size:13px; }
.equipment-category-panel > button { width: calc(100% - 16px); min-height: 38px; display: flex; align-items: center; justify-content: space-between; margin: 8px; padding: 0 10px; color: #415875; border: 0; border-radius: 7px; background: #f7f9fc; cursor: pointer; }
.equipment-category-panel > button.active { color: var(--brand-600); background: var(--brand-100); font-weight: 600; }
.equipment-category-panel :deep(.el-tree) { flex: 1; padding: 0 8px 10px; overflow: auto; --el-tree-node-hover-bg-color: #f3f7fd; }
.equipment-category-panel :deep(.el-tree-node__content) { min-height: 36px; border-radius: 6px; }
.category-node { min-width: 0; flex: 1; display: flex; align-items: center; gap: 6px; }
.category-node > span { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.category-node b { color: #8694a8; font-size:13px; font-weight: 500; }
.equipment-category-panel footer { padding: 10px 12px; border-top: 1px solid #edf1f6; }
.equipment-data-panel { min-width: 0; min-height: 0; display: flex; flex: 1 1 0; flex-direction: column; }
.with-tree .equipment-data-panel { border-right: 1px solid var(--line); border-radius: 0 0 10px 0; overflow: hidden; }
.equipment-actionbar { min-height: 58px; display: flex; align-items: center; gap: 8px; padding: 10px 12px; border: 1px solid var(--line); border-top: 0; background: #fff; }
.with-tree .equipment-actionbar { border-left: 0; border-right: 0; }
.toolbar-spacer { flex: 1; }
.equipment-table { width: 100%; min-height: 0; height: 0; flex: 1 1 0; --el-table-header-bg-color: #f7f9fc; --el-table-border-color: #e7edf4; font-size: 14px; }
.table-cell-stack { min-width: 0; display: flex; flex-direction: column; gap: 3px; line-height: 1.35; }
.table-cell-stack strong { color: #243b59; font-weight: 650; }
.table-cell-stack span { color: #66768c; }
.cell-clamp { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status-stack { min-height: 48px; display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 5px; }
.with-tree .equipment-table { border-left: 0; border-right: 0; }
.equipment-table-footer { min-height: 52px; display: flex; align-items: center; justify-content: space-between; padding: 8px 13px; color: var(--muted); font-size: 13px; border: 1px solid var(--line); border-top: 0; border-radius: 0 0 10px 10px; background: #fff; }
.with-tree .equipment-table-footer { border-left: 0; border-right: 0; border-bottom: 0; }
.equipment-drawer :deep(.el-drawer__header) { margin-bottom: 0; padding-bottom: 14px; border-bottom: 1px solid var(--line); }
.equipment-drawer :deep(.el-drawer__body) { padding-top: 12px; }
.equipment-drawer :deep(.el-drawer__footer) { padding-top: 12px; border-top: 1px solid var(--line); }
.equipment-drawer :deep(.el-select), .equipment-drawer :deep(.el-date-editor), .equipment-drawer :deep(.el-input-number) { width: 100%; }
.equipment-form-tabs { margin-top: 8px; }
.equipment-switches { display: flex; align-items: center; gap: 24px; padding: 12px 14px; border: 1px solid #e4eaf2; border-radius: 8px; background: #f8fafd; }
.project-scope-heading { display: flex; justify-content: space-between; gap: 16px; margin-bottom: 12px; padding: 12px 14px; border: 1px solid #dfe8f6; border-radius: 8px; background: #f6f9ff; }
.project-scope-heading strong { color: #233d60; }
.project-scope-heading p { margin: 4px 0 0; color: var(--muted); font-size: 13px; }
.equipment-project-tree { max-height: min(52dvh, 520px); overflow: auto; padding: 8px; border: 1px solid var(--line); border-radius: 8px; }
.action-form-row { margin-top: 16px; }
.date-period { display: grid; grid-template-columns: 1fr 84px; gap: 6px; }
.address-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-bottom: 14px; }
:deep(.el-dialog .el-select), :deep(.el-dialog .el-date-editor), :deep(.el-dialog .el-input-number) { width: 100%; }
@media (max-width: 1360px) {
  .equipment-filter { grid-template-columns: minmax(220px, 1.2fr) minmax(200px, .9fr) repeat(2, minmax(125px, .62fr)) auto; }
  .equipment-filter-advanced { grid-template-columns: repeat(4, minmax(140px, 1fr)); }
  .equipment-workspace.with-tree { grid-template-columns: 216px minmax(0, 1fr); }
  .equipment-actionbar { overflow-x: auto; }
  .equipment-actionbar > * { flex: 0 0 auto; }
}
@media (max-width: 1200px) {
  .equipment-filter { grid-template-columns: minmax(230px, 1.2fr) minmax(205px, 1fr) repeat(2, minmax(120px, .65fr)); }
  .equipment-filter-advanced { grid-template-columns: repeat(3, minmax(150px, 1fr)); }
  .equipment-filter > .foundation-filter-actions { justify-self: end; grid-column: 1 / -1; }
  .equipment-workspace.with-tree { grid-template-columns: 200px minmax(0, 1fr); }
  .equipment-actionbar { flex-wrap: wrap; overflow-x: visible; }
  .equipment-actionbar :deep(.el-button + .el-button) { margin-left: 0; }
  .equipment-actionbar .toolbar-spacer { display: none; }
  .equipment-actionbar .equipment-export-button { margin-left: auto; }
}
</style>
