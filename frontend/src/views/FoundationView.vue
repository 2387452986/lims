<script setup lang="ts">
import { Delete, Download, Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onMounted, reactive, ref, toRaw, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, apiBlob, errorMessage } from '../api'
import { moduleLabel } from '../navigation'
import { localDateString } from '../presentation'
import { useAuthStore } from '../stores/auth'

type Row = Record<string, any>
type CursorPage = { items: Row[]; nextCursor?: string; hasMore: boolean; total: number }
type StoredFile = { id: string; originalName: string; purpose: string }
type TrainingOptions = { people: Row[]; departments: Row[]; trainingMethods: string[]; assessmentResults: string[] }

const route = useRoute()
const auth = useAuthStore()
const moduleKey = computed(() => String(route.params.moduleKey))
const title = computed(() => moduleLabel(moduleKey.value))
const canWrite = computed(() => auth.user?.permissions.includes('MODULE_WRITE'))
const canImport = computed(() => auth.user?.permissions.includes('PERSON_IMPORT'))
const canExport = computed(() => auth.user?.permissions.includes('EXPORT_DATA'))
const rows = ref<Row[]>([])
const people = ref<Row[]>([])
const departments = ref<Row[]>([])
const loading = ref(false)
const saving = ref(false)
const uploading = ref('')
const drawer = ref(false)
const readOnly = ref(false)
const editing = ref<Row>()
const selectedRows = ref<Row[]>([])
const titleImportDialog = ref(false)
const titleImportFile = ref<File>()
const trainingMethods = ref<string[]>([])
const assessmentResults = ref<string[]>([])
const memberPicker = ref(false)
const memberLoading = ref(false)
const memberRows = ref<Row[]>([])
const memberSelections = ref<Row[]>([])
const memberTable = ref<any>()
const memberRestoringSelection = ref(false)
const memberCursor = ref('')
const memberNextCursor = ref('')
const memberCursorHistory = ref<string[]>([])
const memberTotal = ref(0)
const memberFilters = reactive({ applicationState: '', personType: '', name: '', position: '' })
const nextCursor = ref('')
const cursor = ref('')
const cursorHistory = ref<string[]>([])
const total = ref(0)
const legacyCount = ref(0)
const form = reactive<Row>({})
const filters = reactive<Row>({
  q: '', state: '', dateRange: [], departmentId: '', organizer: '', trainingMethod: '',
  category: '', level: '', hasFile: '', detail: '', certificateNumber: '',
  personName: '', specialtyName: '', issuingAuthority: '',
})
const selectionCount = computed(() => selectedRows.value.length)
const selectedTrainingAction = computed(() => {
  if (!selectionCount.value) return ''
  if (selectedRows.value.every(row => row.state === 'ACTIVE')) return 'VOID'
  if (selectedRows.value.every(row => row.state === 'VOID')) return 'RESTORE'
  return ''
})
const canModifySelectedTitle = computed(() => moduleKey.value === 'titles'
  && selectionCount.value === 1 && canEdit(selectedRows.value[0]))
const canModifySelectedQualification = computed(() => moduleKey.value === 'organization-qualifications'
  && selectionCount.value === 1 && canEdit(selectedRows.value[0]))
const canModifySelectedMetrology = computed(() => moduleKey.value === 'metrology-qualifications'
  && selectionCount.value === 1 && canEdit(selectedRows.value[0]))
const drawerTitle = computed(() => moduleKey.value === 'shelves' && !editing.value && form.parentId
  ? `新增下级货架 · ${form.parentShelfNumber}`
  : `${readOnly.value ? '查看' : editing.value ? '修改' : '新增'}${title.value}`)

const paths: Record<string, string> = {
  training: 'training',
  'project-groups': 'project-teams',
  titles: 'titles',
  'organization-qualifications': 'organization-qualifications',
  'metrology-qualifications': 'metrology-qualifications',
  shelves: 'shelves',
}
const descriptions: Record<string, string> = {
  training: '人员教育培训、学时、考核结果与资格附件',
  'project-groups': '项目组成员、职责与唯一负责人配置',
  titles: '人员职称证书、专业类型、附件与提交状态',
  'organization-qualifications': '单位检测资质、检测范围、有效期与受控附件',
  'metrology-qualifications': '计量认证证书、有效期和附件',
  shelves: '两级样品货架、二维码、排序和受控启停',
}
const searchPlaceholders: Record<string, string> = {
  training: '姓名或培训内容',
  'project-groups': '项目组名称',
  titles: '职称证书编号',
  'organization-qualifications': '资质编号或发证单位',
  'metrology-qualifications': '计量证书编号或发证单位',
  shelves: '货架编号',
}
const levelOptions = [
  ['ASSISTANT_ENGINEER', '助理工程师'], ['ENGINEER', '工程师'],
  ['SENIOR_ENGINEER', '副高级工程师'], ['PROFESSORATE_SENIOR_ENGINEER', '正高级工程师'],
]
const categoryOptions = [
  ['WATER_CONSERVANCY', '水利'], ['TESTING', '检测'], ['TRANSPORTATION', '交通'],
  ['MUNICIPAL', '市政'], ['PORT_WATERWAY', '港航'], ['CIVIL', '土木'],
  ['GEOTECHNICAL', '岩土'], ['STRUCTURAL', '结构'], ['OTHER', '其他'],
]
const qualificationOptions = [
  ['GEOTECHNICAL_A', '岩土工程(甲级)'], ['CONCRETE_A', '混凝土工程(甲级)'],
  ['METAL_STRUCTURE_A', '金属结构(甲级)'], ['MECHANICAL_ELECTRICAL_A', '机械电气(甲级)'],
  ['MEASUREMENT_A', '量测(甲级)'], ['GEOTECHNICAL_B', '岩土工程(乙级)'],
  ['CONCRETE_B', '混凝土工程(乙级)'], ['METAL_STRUCTURE_B', '金属结构(乙级)'],
  ['MECHANICAL_ELECTRICAL_B', '机械电气(乙级)'], ['MEASUREMENT_B', '量测(乙级)'],
]
const stateLabels: Record<string, string> = {
  ACTIVE: '启用', INACTIVE: '停用', VOID: '已作废', DRAFT: '未提交', SUBMITTED: '已提交', RETIRED: '已停用',
}
const actionLabels: Record<string, string> = {
  SUBMIT: '提交', RECALL: '撤回提交', RETIRE: '停用', VOID: '作废', RESTORE: '恢复',
  ACTIVATE: '启用', DEACTIVATE: '停用',
}

function showError(error: unknown) {
  ElMessage.error(errorMessage(error))
}

async function loadReferences() {
  const options = await api<TrainingOptions>('/api/foundation/training/options')
  people.value = options.people
  departments.value = options.departments
  trainingMethods.value = options.trainingMethods
  assessmentResults.value = options.assessmentResults
}

function queryParams() {
  const params = new URLSearchParams({ q: String(filters.q ?? ''), limit: '50' })
  if (cursor.value) params.set('cursor', cursor.value)
  if (filters.state) params.set('state', String(filters.state))
  if (moduleKey.value === 'training') {
    if (filters.dateRange?.[0]) params.set('startDate', filters.dateRange[0])
    if (filters.dateRange?.[1]) params.set('endDate', filters.dateRange[1])
    if (filters.departmentId) params.set('departmentId', filters.departmentId)
    if (filters.organizer) params.set('organizer', filters.organizer)
    if (filters.trainingMethod) params.set('trainingMethod', filters.trainingMethod)
  }
  if (moduleKey.value === 'titles') {
    if (filters.dateRange?.[0]) params.set('issuedFrom', filters.dateRange[0])
    if (filters.dateRange?.[1]) params.set('issuedTo', filters.dateRange[1])
    if (filters.category) params.set('category', filters.category)
    if (filters.level) params.set('level', filters.level)
    if (filters.hasFile !== '') params.set('hasFile', String(filters.hasFile))
    if (filters.personName) params.set('personName', filters.personName)
    if (filters.specialtyName) params.set('specialtyName', filters.specialtyName)
    if (filters.issuingAuthority) params.set('issuingAuthority', filters.issuingAuthority)
  }
  if (moduleKey.value === 'organization-qualifications') {
    params.delete('q')
    if (filters.certificateNumber) params.set('certificateNumber', filters.certificateNumber)
    if (filters.dateRange?.[0]) params.set('issuedFrom', filters.dateRange[0])
    if (filters.dateRange?.[1]) params.set('issuedTo', filters.dateRange[1])
    if (filters.detail) params.set('detail', filters.detail)
  }
  if (moduleKey.value === 'metrology-qualifications') {
    params.delete('q')
    if (filters.certificateNumber) params.set('certificateNumber', filters.certificateNumber)
    if (filters.issuingAuthority) params.set('issuingAuthority', filters.issuingAuthority)
    if (filters.dateRange?.[0]) params.set('issuedFrom', filters.dateRange[0])
    if (filters.dateRange?.[1]) params.set('issuedTo', filters.dateRange[1])
  }
  if (moduleKey.value === 'shelves' && filters.state) params.set('active', filters.state === 'ACTIVE' ? 'true' : 'false')
  return params
}

async function load() {
  loading.value = true
  try {
    const [result, legacy] = await Promise.all([
      api<CursorPage>(`/api/foundation/${paths[moduleKey.value]}?${queryParams()}`),
      api<{ count: number }>(`/api/foundation/legacy-records/${moduleKey.value}/count`),
    ])
    rows.value = result.items
    selectedRows.value = []
    nextCursor.value = result.nextCursor ?? ''
    total.value = result.total
    legacyCount.value = legacy.count
  } catch (error) { showError(error) } finally { loading.value = false }
}

function search() { cursor.value = ''; cursorHistory.value = []; load() }
function reset() {
  Object.assign(filters, {
    q: '', state: '', dateRange: [], departmentId: '', organizer: '', trainingMethod: '',
    category: '', level: '', hasFile: '', detail: '', certificateNumber: '',
    personName: '', specialtyName: '', issuingAuthority: '',
  })
  search()
}
function nextPage() {
  if (!nextCursor.value) return
  cursorHistory.value.push(cursor.value)
  cursor.value = nextCursor.value
  load()
}
function previousPage() {
  if (!cursorHistory.value.length) return
  cursor.value = cursorHistory.value.pop() ?? ''
  load()
}

function blankForm() {
  const today = localDateString()
  const nextYear = new Date(); nextYear.setFullYear(nextYear.getFullYear() + 1)
  const defaults: Record<string, Row> = {
    training: { personId: people.value[0]?.id ?? '', startDate: today, endDate: today, hours: 0, organizer: '', trainingMethod: trainingMethods.value[0] ?? '', assessmentResult: '', location: '', content: '', certificateFileId: null, certificateFileName: '', remark: '' },
    'project-groups': { name: '', remark: '', members: [] },
    titles: { personId: people.value[0]?.id ?? '', issuedOn: today, certificateNumber: '', specialtyName: '', issuingAuthority: '', titleLevel: 'ENGINEER', specialtyCategory: 'WATER_CONSERVANCY', attachmentFileId: null, attachmentFileName: '', remark: '' },
    'organization-qualifications': { certificateNumber: '', issuedOn: today, validUntil: localDateString(nextYear), issuingAuthority: '', testingScope: '', qualificationDetails: [], originalFileId: null, originalFileName: '', copyFileId: null, copyFileName: '', remark: '' },
    'metrology-qualifications': { certificateNumber: '', issuedOn: today, validUntil: localDateString(nextYear), issuingAuthority: '', certificateFileId: null, certificateFileName: '', otherFileId: null, otherFileName: '', remark: '' },
    shelves: { shelfNumber: '', sortOrder: 0, parentId: null, parentShelfNumber: '' },
  }
  Object.keys(form).forEach(key => delete form[key])
  Object.assign(form, defaults[moduleKey.value])
}
function openCreate() { readOnly.value = false; editing.value = undefined; blankForm(); drawer.value = true }
function openChild(parent: Row) {
  if (!parent.active) return ElMessage.warning('请先启用上级货架')
  readOnly.value = false
  editing.value = undefined
  blankForm()
  form.parentId = parent.id
  form.parentShelfNumber = parent.shelfNumber
  drawer.value = true
}
async function openEdit(row: Row) {
  readOnly.value = false
  const detail = ['training', 'titles', 'organization-qualifications', 'metrology-qualifications'].includes(moduleKey.value)
    ? await api<Row>(`/api/foundation/${paths[moduleKey.value]}/${row.id}`) : row
  editing.value = detail
  blankForm()
  Object.assign(form, structuredClone(toRaw(detail)))
  drawer.value = true
}
async function openDetail(row: Row) {
  try {
    if (!['training', 'titles', 'organization-qualifications', 'metrology-qualifications'].includes(moduleKey.value)) return
    const detail = await api<Row>(`/api/foundation/${paths[moduleKey.value]}/${row.id}`)
    readOnly.value = true
    editing.value = detail
    blankForm()
    Object.assign(form, structuredClone(toRaw(detail)))
    drawer.value = true
  } catch (error) { showError(error) }
}

function validateForm() {
  if (moduleKey.value === 'training') {
    if (!form.personId || !form.startDate || !form.endDate || !form.organizer || !form.trainingMethod || !form.location || !form.content) return '请完整填写培训必填信息'
  } else if (moduleKey.value === 'project-groups') {
    if (!form.name || !form.members?.length) return '请填写项目组名称并添加成员'
    if (form.members.filter((item: Row) => item.leader).length !== 1) return '项目组必须且只能指定一名负责人'
    if (form.members.some((item: Row) => !item.personId || !item.duty)) return '请完整填写成员和职务'
    if (new Set(form.members.map((item: Row) => item.personId)).size !== form.members.length) return '项目组成员不能重复'
  } else if (moduleKey.value === 'titles') {
    if (!form.personId || !form.issuedOn || !form.certificateNumber || !form.specialtyName || !form.issuingAuthority) return '请完整填写职称证书信息'
  } else if (moduleKey.value === 'organization-qualifications') {
    if (!form.certificateNumber || !form.issuedOn || !form.validUntil || !form.issuingAuthority || !form.testingScope || !form.qualificationDetails?.length) return '请完整填写单位资质必填信息'
    if (form.validUntil < form.issuedOn) return '有效期至不能早于签发日期'
  } else if (moduleKey.value === 'metrology-qualifications') {
    if (!form.certificateNumber || !form.issuedOn || !form.validUntil || !form.issuingAuthority || !form.certificateFileId) return '请填写证书信息并上传证书附件'
    if (form.validUntil < form.issuedOn) return '有效期至不能早于证书签发日期'
  } else if (!form.shelfNumber) return '请填写货架编号'
  return ''
}

async function save() {
  if (readOnly.value) return
  const message = validateForm()
  if (message) return ElMessage.warning(message)
  saving.value = true
  try {
    const body = structuredClone(toRaw(form))
    delete body.updatedAt
    for (const key of Object.keys(body)) if (key.endsWith('FileName') || ['id', 'state', 'version', 'memberCount', 'leaderName', 'parentShelfNumber', 'childCount', 'activeChildCount', 'children'].includes(key)) delete body[key]
    if (editing.value) {
      body.expectedVersion = editing.value.version
      await api(`/api/foundation/${paths[moduleKey.value]}/${editing.value.id}`, {
        method: 'PUT', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body),
      })
    } else {
      await api(`/api/foundation/${paths[moduleKey.value]}`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) })
    }
    drawer.value = false
    ElMessage.success(editing.value ? '信息已更新' : '记录已创建')
    search()
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function transition(row: Row, action: string) {
  let comment = `${actionLabels[action]}${title.value}`
  if (['VOID', 'RETIRE', 'DEACTIVATE'].includes(action)) {
    try {
      comment = (await ElMessageBox.prompt(`请填写${actionLabels[action]}原因`, `${actionLabels[action]}${title.value}`, {
        inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空',
      })).value
    } catch (error) { if (error === 'cancel') return; throw error }
  }
  try {
    await api(`/api/foundation/${paths[moduleKey.value]}/${row.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: 'REFERENCE_PARITY_CONTROL', comment }),
    })
    ElMessage.success(`${actionLabels[action]}成功`)
    await load()
  } catch (error) { showError(error) }
}

function onTrainingSelection(selection: Row[]) { selectedRows.value = selection }

function shelfActionBlocker(row: Row, action: string) {
  if (action === 'DEACTIVATE' && !row.parentId && row.activeChildCount > 0) return `请先停用 ${row.activeChildCount} 个启用中的下级货架`
  return ''
}

async function downloadShelfQrs() {
  if (!selectedRows.value.length) return ElMessage.warning('请至少选择一个货架')
  if (selectedRows.value.length > 100) return ElMessage.warning('单次最多下载 100 个货架二维码')
  saving.value = true
  try {
    const blob = await apiBlob('/api/foundation/shelves/qr-downloads', {
      method: 'POST', body: JSON.stringify({ ids: selectedRows.value.map(row => row.id) }),
    })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = '货架二维码.zip'
    anchor.click()
    URL.revokeObjectURL(url)
    ElMessage.success(`已生成 ${selectedRows.value.length} 个货架二维码`)
  } catch (error) { showError(error) } finally { saving.value = false }
}

function modifySelectedTraining() {
  if (selectionCount.value !== 1 || !canEdit(selectedRows.value[0])) return
  openEdit(selectedRows.value[0])
}

async function transitionSelectedTraining() {
  const action = selectedTrainingAction.value
  if (!action) return ElMessage.warning('请选择状态一致的培训记录')
  let comment = `${actionLabels[action]}培训信息`
  if (action === 'VOID') {
    try {
      comment = (await ElMessageBox.prompt('请填写作废原因', '批量作废培训信息', {
        inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空',
      })).value
    } catch (error) { if (error === 'cancel') return; throw error }
  }
  try {
    await api('/api/foundation/training/batch-transitions', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        action, reasonCode: 'REFERENCE_PARITY_CONTROL', comment,
        items: selectedRows.value.map(row => ({ id: row.id, expectedVersion: row.version })),
      }),
    })
    ElMessage.success(`已${actionLabels[action]} ${selectionCount.value} 条培训记录`)
    await load()
  } catch (error) { showError(error) }
}

function titleBatchAllowed(action: string) {
  return moduleKey.value === 'titles' && selectionCount.value > 0
    && selectedRows.value.every(row => actions(row).includes(action))
}

function modifySelectedTitle() {
  if (!canModifySelectedTitle.value) return
  openEdit(selectedRows.value[0])
}

async function transitionSelectedTitles(action: string) {
  if (!titleBatchAllowed(action)) return ElMessage.warning(`所选记录的状态不允许批量${actionLabels[action]}`)
  let comment = `批量${actionLabels[action]}人员职称`
  if (action === 'RETIRE') {
    try {
      comment = (await ElMessageBox.prompt('请填写停用原因', '批量停用人员职称', {
        inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空',
      })).value
    } catch (error) { if (error === 'cancel') return; throw error }
  }
  try {
    await api('/api/foundation/titles/batch-transitions', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        action, reasonCode: 'REFERENCE_PARITY_CONTROL', comment,
        items: selectedRows.value.map(row => ({ id: row.id, expectedVersion: row.version })),
      }),
    })
    ElMessage.success(`已${actionLabels[action]} ${selectionCount.value} 条人员职称`)
    await load()
  } catch (error) { showError(error) }
}

function qualificationBatchAllowed(action: string) {
  return moduleKey.value === 'organization-qualifications' && selectionCount.value > 0
    && selectedRows.value.every(row => actions(row).includes(action))
}

function modifySelectedQualification() {
  if (!canModifySelectedQualification.value) return
  openEdit(selectedRows.value[0])
}

async function transitionSelectedQualifications(action: string) {
  if (!qualificationBatchAllowed(action)) {
    return ElMessage.warning(`所选单位资质的当前状态不允许批量${actionLabels[action]}`)
  }
  let comment = `批量${actionLabels[action]}单位资质`
  if (action === 'RETIRE') {
    try {
      comment = (await ElMessageBox.prompt('请填写停用原因；停用后记录仅可查询，不可恢复或修改。', '批量停用单位资质', {
        inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空',
      })).value
    } catch (error) { if (error === 'cancel') return; throw error }
  }
  try {
    await api('/api/foundation/organization-qualifications/batch-transitions', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        action, reasonCode: 'REFERENCE_PARITY_CONTROL', comment,
        items: selectedRows.value.map(row => ({ id: row.id, expectedVersion: row.version })),
      }),
    })
    ElMessage.success(`已${actionLabels[action]} ${selectionCount.value} 条单位资质`)
    await load()
  } catch (error) { showError(error) }
}

function metrologyBatchAllowed(action: string) {
  return moduleKey.value === 'metrology-qualifications' && selectionCount.value > 0
    && selectedRows.value.every(row => actions(row).includes(action))
}

function modifySelectedMetrology() {
  if (!canModifySelectedMetrology.value) return
  openEdit(selectedRows.value[0])
}

async function transitionSelectedMetrology(action: string) {
  if (!metrologyBatchAllowed(action)) {
    return ElMessage.warning(`所选计量资质的当前状态不允许批量${actionLabels[action]}`)
  }
  let comment = `批量${actionLabels[action]}计量资质`
  if (action === 'RETIRE') {
    try {
      comment = (await ElMessageBox.prompt('请填写停用原因；停用后记录仅可查询，不可恢复或修改。', '批量停用计量资质', {
        inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空',
      })).value
    } catch (error) { if (error === 'cancel') return; throw error }
  }
  try {
    await api('/api/foundation/metrology-qualifications/batch-transitions', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        action, reasonCode: 'REFERENCE_PARITY_CONTROL', comment,
        items: selectedRows.value.map(row => ({ id: row.id, expectedVersion: row.version })),
      }),
    })
    ElMessage.success(`已${actionLabels[action]} ${selectionCount.value} 条计量资质`)
    await load()
  } catch (error) { showError(error) }
}

async function downloadStoredFile(id: string, name: string) {
  const response = await fetch(`/api/files/${id}`, { credentials: 'same-origin' })
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new Error(body.detail ?? '下载失败')
  }
  const url = URL.createObjectURL(await response.blob())
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name
  anchor.click()
  URL.revokeObjectURL(url)
}

async function downloadTitleTemplate() {
  saving.value = true
  try {
    const stored = await api<StoredFile>('/api/foundation/titles/import-template', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
    })
    await downloadStoredFile(stored.id, stored.originalName)
  } catch (error) { showError(error) } finally { saving.value = false }
}

function chooseTitleImport(event: Event) {
  const input = event.target as HTMLInputElement
  titleImportFile.value = input.files?.[0]
  input.value = ''
}

async function submitTitleImport() {
  if (!titleImportFile.value) return ElMessage.warning('请选择按受控模板填写的 .xlsx 文件')
  saving.value = true
  try {
    const body = new FormData()
    body.append('file', titleImportFile.value)
    const result = await api<{ importedCount: number }>('/api/foundation/titles/imports', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body,
    })
    titleImportDialog.value = false
    titleImportFile.value = undefined
    ElMessage.success(`已整批导入 ${result.importedCount} 条人员职称`)
    await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}

function actions(row: Row) {
  if (moduleKey.value === 'training') return row.state === 'ACTIVE' ? ['VOID'] : ['RESTORE']
  if (moduleKey.value === 'project-groups') return row.state === 'ACTIVE' ? ['DEACTIVATE'] : ['ACTIVATE']
  if (moduleKey.value === 'shelves') return row.active ? ['DEACTIVATE'] : ['ACTIVATE']
  if (row.state === 'DRAFT') return ['SUBMIT', 'RETIRE']
  if (row.state === 'SUBMITTED') return ['RECALL', 'RETIRE']
  return []
}

function canEdit(row: Row) {
  if (moduleKey.value === 'training') return row.state === 'ACTIVE'
  if (['titles', 'organization-qualifications', 'metrology-qualifications'].includes(moduleKey.value)) return row.state === 'DRAFT'
  return true
}

function handleTrainingCommand(row: Row, command: string) {
  if (command === 'EDIT') openEdit(row)
  else void transition(row, command)
}

function memberParams() {
  const params = new URLSearchParams({ limit: '20' })
  if (memberFilters.applicationState) params.set('applicationState', memberFilters.applicationState)
  if (memberFilters.personType) params.set('personType', memberFilters.personType)
  if (memberFilters.name) params.set('name', memberFilters.name)
  if (memberFilters.position) params.set('position', memberFilters.position)
  if (memberCursor.value) params.set('cursor', memberCursor.value)
  return params
}
async function loadMemberCandidates() {
  memberLoading.value = true
  try {
    const page = await api<CursorPage>(`/api/personnel?${memberParams()}`)
    memberRestoringSelection.value = true
    memberRows.value = page.items
    memberTotal.value = page.total
    memberNextCursor.value = page.nextCursor ?? ''
    await nextTick()
    const selectedIds = new Set(memberSelections.value.map(item => item.id))
    memberRows.value.forEach(row => { if (selectedIds.has(row.id)) memberTable.value?.toggleRowSelection(row, true) })
    memberRestoringSelection.value = false
  } catch (error) { showError(error) } finally { memberRestoringSelection.value = false; memberLoading.value = false }
}
async function openMemberPicker() {
  memberSelections.value = []
  Object.assign(memberFilters, { applicationState: '', personType: '', name: '', position: '' })
  memberCursor.value = ''
  memberNextCursor.value = ''
  memberCursorHistory.value = []
  memberPicker.value = true
  await loadMemberCandidates()
}
function searchMemberCandidates() {
  memberCursor.value = ''
  memberCursorHistory.value = []
  loadMemberCandidates()
}
function resetMemberCandidates() {
  Object.assign(memberFilters, { applicationState: '', personType: '', name: '', position: '' })
  searchMemberCandidates()
}
function nextMemberPage() {
  if (!memberNextCursor.value) return
  memberCursorHistory.value.push(memberCursor.value)
  memberCursor.value = memberNextCursor.value
  loadMemberCandidates()
}
function previousMemberPage() {
  if (!memberCursorHistory.value.length) return
  memberCursor.value = memberCursorHistory.value.pop() ?? ''
  loadMemberCandidates()
}
function onMemberSelectionChange(selection: Row[]) {
  if (memberRestoringSelection.value) return
  const currentIds = new Set(memberRows.value.map(item => item.id))
  memberSelections.value = [
    ...memberSelections.value.filter(item => !currentIds.has(item.id)),
    ...selection,
  ]
}
function memberSelectable(row: Row) {
  return row.active && row.applicationState !== 'ARCHIVED'
    && !(form.members ?? []).some((member: Row) => member.personId === row.id)
}
function confirmMemberSelection() {
  if (!memberSelections.value.length) return ElMessage.warning('请至少选择一名人员')
  const hadLeader = (form.members ?? []).some((item: Row) => item.leader)
  memberSelections.value.forEach((person, index) => form.members.push({
    personId: person.id,
    personName: person.name,
    duty: person.position || person.title || '',
    leader: !hadLeader && index === 0,
    sortOrder: form.members.length,
  }))
  memberPicker.value = false
}
function setLeader(index: string | number) { form.members.forEach((item: Row, memberIndex: number) => { item.leader = memberIndex === Number(index) }) }
function memberDepartmentName(member: Row) { return people.value.find(item => item.id === member.personId)?.departmentName ?? '当前数据范围外' }
function genderLabel(value: string) { return value === 'MALE' ? '男' : value === 'FEMALE' ? '女' : '未知' }
function personTypeLabel(value: string) { return ({ EMPLOYEE: '在职员工', CONTRACTOR: '合同人员', EXTERNAL_EXPERT: '外部专家', INTERN: '实习人员' } as Record<string, string>)[value] ?? value }
function applicationLabel(value: string) { return ({ DRAFT: '未提交', SUBMITTED: '已提交', VERIFIED: '已核验', RETURNED: '已退回', ARCHIVED: '已归档' } as Record<string, string>)[value] ?? value }

async function uploadFile(event: Event, idField: string, nameField: string, purpose: string) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (purpose === 'PERSON_TITLE'
    && (!['image/png', 'image/jpeg', 'image/bmp'].includes(file.type) || file.size > 3 * 1024 * 1024)) {
    return ElMessage.warning('职称证书只接受不超过 3MB 的 JPG、PNG 或 BMP 图片')
  }
  if (['METROLOGY_CERTIFICATE', 'METROLOGY_OTHER'].includes(purpose)
    && (file.type !== 'application/pdf' || file.size > 30 * 1024 * 1024)) {
    return ElMessage.warning('计量资质附件只接受不超过 30MB 的 PDF 文件')
  }
  uploading.value = idField
  try {
    const body = new FormData()
    body.append('file', file)
    body.append('purpose', purpose)
    const stored = await api<StoredFile>('/api/files', { method: 'POST', body })
    form[idField] = stored.id
    form[nameField] = stored.originalName
    ElMessage.success('附件已安全上传')
  } catch (error) { showError(error) } finally { uploading.value = '' }
}

function fileUrl(id?: string) { return id ? `/api/files/${id}` : '' }
function stateType(value: string) { return ['ACTIVE', 'SUBMITTED'].includes(value) ? 'success' : ['VOID', 'RETIRED', 'INACTIVE'].includes(value) ? 'info' : 'warning' }
function optionLabel(options: string[][], value: string) { return options.find(item => item[0] === value)?.[1] ?? value }
function date(value?: string) { return value || '—' }
function formatDateTime(value?: string) {
  return value ? new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(new Date(value)) : '—'
}

watch(moduleKey, async () => {
  drawer.value = false
  titleImportDialog.value = false
  titleImportFile.value = undefined
  selectedRows.value = []
  await loadReferences()
  reset()
})
onMounted(async () => { await loadReferences(); await load() })
</script>

<template>
  <section class="page foundation-page">
    <header class="page-heading">
      <div><h1>{{ title }}</h1><p>{{ descriptions[moduleKey] }}</p></div>
      <div v-if="canWrite || (moduleKey === 'shelves' && canExport)" class="training-heading-actions">
        <template v-if="moduleKey === 'training'">
          <el-button :disabled="selectionCount !== 1 || !canEdit(selectedRows[0])" @click="modifySelectedTraining">修改</el-button>
          <el-button :disabled="!selectedTrainingAction" :type="selectedTrainingAction === 'VOID' ? 'danger' : 'default'" @click="transitionSelectedTraining">
            {{ selectedTrainingAction ? actionLabels[selectedTrainingAction] : '作废/恢复' }}
          </el-button>
        </template>
        <template v-else-if="moduleKey === 'titles'">
          <el-button v-if="canImport" :icon="Upload" @click="titleImportFile=undefined; titleImportDialog=true">批量导入</el-button>
          <el-button :disabled="!canModifySelectedTitle" @click="modifySelectedTitle">修改</el-button>
          <el-button :disabled="!titleBatchAllowed('SUBMIT')" @click="transitionSelectedTitles('SUBMIT')">提交</el-button>
          <el-button :disabled="!titleBatchAllowed('RECALL')" @click="transitionSelectedTitles('RECALL')">撤回提交</el-button>
          <el-button type="danger" plain :disabled="!titleBatchAllowed('RETIRE')" @click="transitionSelectedTitles('RETIRE')">停用</el-button>
        </template>
        <template v-else-if="moduleKey === 'organization-qualifications'">
          <el-button :disabled="!canModifySelectedQualification" @click="modifySelectedQualification">修改</el-button>
          <el-button :disabled="!qualificationBatchAllowed('SUBMIT')" @click="transitionSelectedQualifications('SUBMIT')">提交</el-button>
          <el-button :disabled="!qualificationBatchAllowed('RECALL')" @click="transitionSelectedQualifications('RECALL')">撤回提交</el-button>
          <el-button type="danger" plain :disabled="!qualificationBatchAllowed('RETIRE')" @click="transitionSelectedQualifications('RETIRE')">停用（代替删除）</el-button>
        </template>
        <template v-else-if="moduleKey === 'metrology-qualifications'">
          <el-button :disabled="!canModifySelectedMetrology" @click="modifySelectedMetrology">修改</el-button>
          <el-button :disabled="!metrologyBatchAllowed('SUBMIT')" @click="transitionSelectedMetrology('SUBMIT')">提交</el-button>
          <el-button :disabled="!metrologyBatchAllowed('RECALL')" @click="transitionSelectedMetrology('RECALL')">撤回提交</el-button>
          <el-button type="danger" plain :disabled="!metrologyBatchAllowed('RETIRE')" @click="transitionSelectedMetrology('RETIRE')">停用（代替删除）</el-button>
        </template>
        <template v-else-if="moduleKey === 'shelves'">
          <el-button v-if="canExport" :icon="Download" :loading="saving" :disabled="!selectionCount" @click="downloadShelfQrs">下载二维码</el-button>
        </template>
        <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">新增</el-button>
      </div>
    </header>

    <section class="foundation-filter" :class="{ 'foundation-filter--titles': ['titles','organization-qualifications','metrology-qualifications'].includes(moduleKey) }">
      <el-input v-if="['organization-qualifications','metrology-qualifications'].includes(moduleKey)" v-model="filters.certificateNumber" clearable :prefix-icon="Search" :placeholder="moduleKey === 'organization-qualifications' ? '资质编号' : '计量认证编号'" @keyup.enter="search" />
      <el-input v-else v-model="filters.q" clearable :prefix-icon="Search" :placeholder="searchPlaceholders[moduleKey]" @keyup.enter="search" />
      <el-date-picker v-if="['training','titles','organization-qualifications','metrology-qualifications'].includes(moduleKey)" v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" :start-placeholder="moduleKey === 'training' ? '开始日期' : '签发开始日期'" :end-placeholder="moduleKey === 'training' ? '结束日期' : '签发结束日期'" />
      <el-select v-if="moduleKey === 'training'" v-model="filters.departmentId" clearable placeholder="所在部门"><el-option v-for="item in departments" :key="item.id" :label="item.name" :value="item.id" /></el-select>
      <el-input v-if="moduleKey === 'training'" v-model="filters.organizer" clearable placeholder="主办方" />
      <el-select v-if="moduleKey === 'training'" v-model="filters.trainingMethod" clearable placeholder="培训方式"><el-option v-for="item in trainingMethods" :key="item" :label="item" :value="item" /></el-select>
      <el-select v-if="moduleKey === 'titles'" v-model="filters.category" clearable placeholder="专业类型"><el-option v-for="item in categoryOptions" :key="item[0]" :label="item[1]" :value="item[0]" /></el-select>
      <el-select v-if="moduleKey === 'titles'" v-model="filters.level" clearable placeholder="职称"><el-option v-for="item in levelOptions" :key="item[0]" :label="item[1]" :value="item[0]" /></el-select>
      <el-select v-if="moduleKey === 'titles'" v-model="filters.hasFile" clearable placeholder="是否上传文件"><el-option label="已上传" :value="true" /><el-option label="未上传" :value="false" /></el-select>
      <el-input v-if="moduleKey === 'titles'" v-model="filters.personName" clearable placeholder="姓名" @keyup.enter="search" />
      <el-input v-if="moduleKey === 'titles'" v-model="filters.specialtyName" clearable placeholder="专业名称" @keyup.enter="search" />
      <el-input v-if="moduleKey === 'titles'" v-model="filters.issuingAuthority" clearable placeholder="发证单位" @keyup.enter="search" />
      <el-input v-if="moduleKey === 'metrology-qualifications'" v-model="filters.issuingAuthority" clearable placeholder="发证单位" @keyup.enter="search" />
      <el-select v-if="['project-groups','titles','organization-qualifications','metrology-qualifications','shelves'].includes(moduleKey)" v-model="filters.state" clearable placeholder="状态">
        <el-option v-if="moduleKey === 'shelves'" label="启用" value="ACTIVE" /><el-option v-if="moduleKey === 'shelves'" label="停用" value="INACTIVE" />
        <template v-else-if="moduleKey === 'project-groups'"><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="INACTIVE" /></template>
        <template v-else><el-option label="未提交" value="DRAFT" /><el-option label="已提交" value="SUBMITTED" /><el-option label="已停用" value="RETIRED" /></template>
      </el-select>
      <el-select v-if="moduleKey === 'organization-qualifications'" v-model="filters.detail" clearable placeholder="资质详情"><el-option v-for="item in qualificationOptions" :key="item[0]" :label="item[1]" :value="item[0]" /></el-select>
      <div class="foundation-filter-actions"><el-button type="primary" @click="search">查询</el-button><el-button :icon="Refresh" @click="reset">重置</el-button></div>
    </section>
    <el-alert v-if="legacyCount" class="legacy-alert" type="warning" show-icon :closable="false" :title="`检测到 ${legacyCount} 条旧通用记录，原数据已保留并进入人工字段映射队列；不会以猜测值自动迁移。`" />

    <div v-if="moduleKey === 'training' && selectionCount" class="training-selection-summary" role="status">
      已选择 {{ selectionCount }} 条；批量操作会校验每条记录的当前状态与版本，任一失败则整批回滚。
    </div>
    <div v-if="moduleKey === 'titles' && selectionCount" class="training-selection-summary" role="status">
      已选择 {{ selectionCount }} 条人员职称；提交、撤回与停用会逐条校验状态和版本，任一失败则整批回滚。
    </div>
    <div v-if="moduleKey === 'organization-qualifications' && selectionCount" class="training-selection-summary" role="status">
      已选择 {{ selectionCount }} 条单位资质；批量操作会逐条校验状态和版本，任一失败则整批回滚。
    </div>
    <div v-if="moduleKey === 'metrology-qualifications' && selectionCount" class="training-selection-summary" role="status">
      已选择 {{ selectionCount }} 条计量资质；批量操作会逐条校验状态和版本，任一失败则整批回滚。
    </div>
    <div v-if="moduleKey === 'shelves' && selectionCount" class="training-selection-summary" role="status">
      已选择 {{ selectionCount }} 个货架；二维码按当前系统货架 UUID 生成，单次最多下载 100 个。
    </div>
    <el-table v-if="moduleKey === 'training'" v-loading="loading" :data="rows" border stripe class="module-table" row-key="id" @selection-change="onTrainingSelection" @row-dblclick="openDetail">
      <el-table-column v-if="canWrite" type="selection" width="44" />
      <el-table-column label="姓名 / 部门" width="135"><template #default="scope"><div class="training-primary">{{ scope.row.personName }}</div><div class="training-secondary">{{ scope.row.departmentName || '未配置部门' }}</div></template></el-table-column>
      <el-table-column label="培训日期" width="150"><template #default="scope"><div class="training-primary">{{ scope.row.startDate }}</div><div class="training-secondary">至 {{ scope.row.endDate }}</div></template></el-table-column>
      <el-table-column prop="hours" label="学时" width="64" /><el-table-column label="主办方" min-width="150"><template #default="scope"><span class="cell-two-line" :title="scope.row.organizer">{{ scope.row.organizer }}</span></template></el-table-column>
      <el-table-column label="方式 / 考核" width="120"><template #default="scope"><div class="training-primary">{{ scope.row.trainingMethod }}</div><div class="training-secondary">{{ scope.row.assessmentResult || '未填写考核' }}</div></template></el-table-column><el-table-column label="培训内容" min-width="260"><template #default="scope"><span class="cell-two-line" :title="scope.row.content">{{ scope.row.content }}</span></template></el-table-column>
      <el-table-column label="状态 / 证书" width="84" align="center"><template #default="scope"><el-tag size="small" :type="stateType(scope.row.state)">{{ stateLabels[scope.row.state] }}</el-tag><div class="training-secondary"><el-link v-if="scope.row.certificateFileId" :href="fileUrl(scope.row.certificateFileId)" type="primary">下载证书</el-link><span v-else>无证书</span></div></template></el-table-column>
      <el-table-column label="操作" fixed="right" width="145" align="center"><template #default="scope"><el-button link type="primary" @click="openDetail(scope.row)">详情</el-button><el-dropdown v-if="canWrite && (canEdit(scope.row) || actions(scope.row).length)" trigger="click" @command="handleTrainingCommand(scope.row, String($event))"><el-button link type="primary">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-if="canEdit(scope.row)" command="EDIT">修改</el-dropdown-item><el-dropdown-item v-for="action in actions(scope.row)" :key="action" :command="action" :divided="action === 'VOID'">{{ actionLabels[action] }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></el-table-column>
    </el-table>

    <el-table v-else-if="moduleKey === 'project-groups'" v-loading="loading" :data="rows" border stripe class="module-table">
      <el-table-column prop="name" label="项目组名称" min-width="240" /><el-table-column prop="leaderName" label="负责人" width="130" /><el-table-column prop="memberCount" label="组成员数" width="110" /><el-table-column prop="remark" label="备注" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" width="90" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)">{{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column>
      <el-table-column v-if="canWrite" label="操作" fixed="right" width="150" align="center"><template #default="scope"><el-button link type="primary" @click="openEdit(scope.row)">修改</el-button><el-button v-for="action in actions(scope.row)" :key="action" link :type="action === 'DEACTIVATE' ? 'danger' : 'primary'" @click="transition(scope.row, action)">{{ actionLabels[action] }}</el-button></template></el-table-column>
    </el-table>

    <el-table v-else-if="moduleKey === 'titles'" v-loading="loading" :data="rows" border stripe class="module-table" row-key="id" @selection-change="onTrainingSelection" @row-dblclick="openDetail">
      <el-table-column v-if="canWrite" type="selection" width="44" />
      <el-table-column prop="certificateNumber" label="证书编号" min-width="170" />
      <el-table-column prop="personName" label="姓名" width="110" />
      <el-table-column prop="specialtyName" label="专业名称" min-width="150" />
      <el-table-column label="专业类型" width="110"><template #default="scope">{{ optionLabel(categoryOptions, scope.row.specialtyCategory) }}</template></el-table-column>
      <el-table-column label="职称" width="130"><template #default="scope">{{ optionLabel(levelOptions, scope.row.titleLevel) }}</template></el-table-column>
      <el-table-column prop="issuingAuthority" label="发证单位" min-width="160" />
      <el-table-column prop="issuedOn" label="签发日期" width="115" />
      <el-table-column label="文件" width="80"><template #default="scope"><el-link v-if="scope.row.attachmentFileId" :href="fileUrl(scope.row.attachmentFileId)" type="primary">下载</el-link><span v-else>—</span></template></el-table-column>
      <el-table-column label="状态" width="90" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)">{{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column>
      <el-table-column label="操作" fixed="right" width="205" align="center"><template #default="scope"><el-button link type="primary" @click="openDetail(scope.row)">详情</el-button><template v-if="canWrite"><el-button v-if="canEdit(scope.row)" link type="primary" @click="openEdit(scope.row)">修改</el-button><el-button v-for="action in actions(scope.row)" :key="action" link :type="action === 'RETIRE' ? 'danger' : 'primary'" @click="transition(scope.row, action)">{{ actionLabels[action] }}</el-button></template></template></el-table-column>
    </el-table>

    <el-table v-else-if="moduleKey === 'organization-qualifications'" v-loading="loading" :data="rows" border stripe class="module-table" row-key="id" @selection-change="onTrainingSelection" @row-dblclick="openDetail">
      <el-table-column v-if="canWrite" type="selection" width="44" />
      <el-table-column prop="certificateNumber" label="资质编号" min-width="210" />
      <el-table-column label="资质详情" min-width="280"><template #default="scope"><el-tag v-for="detail in scope.row.qualificationDetails" :key="detail" size="small" class="detail-tag">{{ optionLabel(qualificationOptions, detail) }}</el-tag></template></el-table-column>
      <el-table-column prop="issuedOn" label="签发日期" width="115" />
      <el-table-column prop="validUntil" label="有效期至" width="115" />
      <el-table-column prop="issuingAuthority" label="发证单位" min-width="170" show-overflow-tooltip />
      <el-table-column label="附件" width="100"><template #default="scope"><el-link v-if="scope.row.originalFileId" :href="fileUrl(scope.row.originalFileId)" type="primary">正本</el-link><el-link v-if="scope.row.copyFileId" :href="fileUrl(scope.row.copyFileId)" type="primary">副本</el-link><span v-if="!scope.row.originalFileId && !scope.row.copyFileId">—</span></template></el-table-column>
      <el-table-column label="提交状态" fixed="right" width="100" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)">{{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column>
      <el-table-column label="操作" fixed="right" width="215" align="center"><template #default="scope"><el-button link type="primary" @click="openDetail(scope.row)">详情</el-button><template v-if="canWrite"><el-button v-if="canEdit(scope.row)" link type="primary" @click="openEdit(scope.row)">修改</el-button><el-button v-for="action in actions(scope.row)" :key="action" link :type="action === 'RETIRE' ? 'danger' : 'primary'" @click="transition(scope.row, action)">{{ actionLabels[action] }}</el-button></template></template></el-table-column>
    </el-table>

    <el-table v-else-if="moduleKey === 'metrology-qualifications'" v-loading="loading" :data="rows" border stripe class="module-table" row-key="id" @selection-change="onTrainingSelection" @row-dblclick="openDetail">
      <el-table-column v-if="canWrite" type="selection" width="44" />
      <el-table-column prop="certificateNumber" label="计量认证编号" min-width="210" />
      <el-table-column prop="issuingAuthority" label="发证单位" min-width="190" show-overflow-tooltip />
      <el-table-column prop="issuedOn" label="签发日期" width="115" />
      <el-table-column prop="validUntil" label="有效期至" width="115" />
      <el-table-column label="附件" width="120"><template #default="scope"><el-link :href="fileUrl(scope.row.certificateFileId)" type="primary">证书</el-link><el-link v-if="scope.row.otherFileId" :href="fileUrl(scope.row.otherFileId)" type="primary">其他</el-link></template></el-table-column>
      <el-table-column label="提交状态" fixed="right" width="100" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)">{{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column>
      <el-table-column label="操作" fixed="right" width="215" align="center"><template #default="scope"><el-button link type="primary" @click="openDetail(scope.row)">详情</el-button><template v-if="canWrite"><el-button v-if="canEdit(scope.row)" link type="primary" @click="openEdit(scope.row)">修改</el-button><el-button v-for="action in actions(scope.row)" :key="action" link :type="action === 'RETIRE' ? 'danger' : 'primary'" @click="transition(scope.row, action)">{{ actionLabels[action] }}</el-button></template></template></el-table-column>
    </el-table>

    <el-table v-else v-loading="loading" :data="rows" border stripe class="module-table shelf-table" row-key="id" default-expand-all :tree-props="{ children: 'children' }" @selection-change="onTrainingSelection">
      <el-table-column v-if="canExport" type="selection" width="44" />
      <el-table-column label="货架编号" min-width="250"><template #default="scope"><div class="shelf-identity"><strong :title="scope.row.shelfNumber">{{ scope.row.shelfNumber }}</strong><span :title="scope.row.parentId ? `下级货架 · 上级 ${scope.row.parentShelfNumber}` : `根货架 · ${scope.row.childCount} 个下级`">{{ scope.row.parentId ? `下级货架 · 上级 ${scope.row.parentShelfNumber}` : `根货架 · ${scope.row.childCount} 个下级` }}</span></div></template></el-table-column>
      <el-table-column label="二维码" width="104" align="center"><template #default="scope"><el-image class="shelf-qr" :src="`/api/foundation/shelves/${scope.row.id}/qr`" :preview-src-list="[`/api/foundation/shelves/${scope.row.id}/qr`]" preview-teleported fit="contain" loading="lazy" :alt="`${scope.row.shelfNumber} 货架二维码`" /></template></el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="90" />
      <el-table-column label="状态" width="90" align="center"><template #default="scope"><el-tag :type="scope.row.active ? 'success' : 'info'">{{ scope.row.active ? '启用' : '停用' }}</el-tag></template></el-table-column>
      <el-table-column label="更新时间" min-width="170"><template #default="scope">{{ formatDateTime(scope.row.updatedAt) }}</template></el-table-column>
      <el-table-column v-if="canWrite" label="操作" fixed="right" width="250" align="center"><template #default="scope">
        <el-button v-if="!scope.row.parentId" link type="primary" :disabled="!scope.row.active" @click="openChild(scope.row)">添加下级</el-button>
        <el-button link type="primary" @click="openEdit(scope.row)">修改</el-button>
        <el-tooltip v-for="action in actions(scope.row)" :key="action" :disabled="!shelfActionBlocker(scope.row, action)" :content="shelfActionBlocker(scope.row, action)" placement="top"><span><el-button link :disabled="Boolean(shelfActionBlocker(scope.row, action))" :type="action === 'DEACTIVATE' ? 'danger' : 'primary'" @click="transition(scope.row, action)">{{ actionLabels[action] }}</el-button></span></el-tooltip>
      </template></el-table-column>
    </el-table>

    <footer class="table-footer"><span>共 {{ total }} 条 · 当前第 {{ cursorHistory.length + 1 }} 页 · 每页最多 50 条</span><div><el-button :disabled="!cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="!nextCursor" @click="nextPage">下一页</el-button></div></footer>

    <el-drawer v-model="drawer" :title="drawerTitle" size="720px" destroy-on-close class="foundation-drawer">
      <el-alert v-if="readOnly" title="当前为只读详情；双击表格行也可进入此视图。" type="info" :closable="false" show-icon />
      <el-form label-position="top" :disabled="readOnly">
        <template v-if="moduleKey === 'training'">
          <div class="form-grid"><el-form-item label="姓名" required><el-select v-model="form.personId" filterable><el-option v-for="item in people" :key="item.id" :label="`${item.name} · ${item.departmentName}`" :value="item.id" /></el-select></el-form-item><el-form-item label="学时数（学时）" required><el-input-number v-model="form.hours" :min="0" :precision="2" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="开始日期" required><el-date-picker v-model="form.startDate" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="结束日期" required><el-date-picker v-model="form.endDate" value-format="YYYY-MM-DD" /></el-form-item></div>
          <div class="form-grid"><el-form-item label="主办方" required><el-input v-model="form.organizer" maxlength="200" /></el-form-item><el-form-item label="培训方式" required><el-select v-model="form.trainingMethod"><el-option v-for="item in trainingMethods" :key="item" :label="item" :value="item" /></el-select></el-form-item></div>
          <el-form-item label="培训考核结果"><el-select v-model="form.assessmentResult" clearable><el-option v-for="item in assessmentResults" :key="item" :label="item" :value="item" /></el-select></el-form-item>
          <el-form-item label="培训地址" required><el-input v-model="form.location" maxlength="240" /></el-form-item><el-form-item label="进修专业及主要内容" required><el-input v-model="form.content" type="textarea" :rows="4" maxlength="2000" show-word-limit /></el-form-item>
          <el-form-item label="培训取得资格证书（图片或 PDF）"><label v-if="!readOnly" class="file-picker"><input type="file" accept="image/png,image/jpeg,image/bmp,application/pdf" @change="uploadFile($event,'certificateFileId','certificateFileName','TRAINING_CERTIFICATE')" /><el-icon><Upload /></el-icon>{{ uploading === 'certificateFileId' ? '上传中…' : form.certificateFileName || '选择文件' }}</label><el-link v-else-if="form.certificateFileId" :href="fileUrl(form.certificateFileId)" type="primary">下载 {{ form.certificateFileName }}</el-link><span v-else>未上传</span></el-form-item><el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
        </template>

        <template v-else-if="moduleKey === 'project-groups'">
          <el-form-item label="项目组名称" required><el-input v-model="form.name" maxlength="200" /></el-form-item><el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
          <div class="member-heading"><div><strong>人员信息</strong><span>从受控人员库选择成员，配置职责并指定唯一负责人</span></div><el-button :icon="Plus" @click="openMemberPicker">添加人员</el-button></div>
          <el-empty v-if="!form.members?.length" description="尚未添加项目组成员" :image-size="72" />
          <div v-else class="member-list"><div v-for="(member,index) in form.members" :key="member.personId" class="member-row"><div class="member-person"><strong>{{ member.personName }}</strong><span>{{ memberDepartmentName(member) }}</span></div><el-input v-model="member.duty" placeholder="职务/职责" maxlength="120" /><el-radio :model-value="member.leader" :value="true" @change="setLeader(index)">负责人</el-radio><el-button :icon="Delete" circle plain type="danger" aria-label="移除成员" @click="form.members.splice(index,1)" /></div></div>
        </template>

        <template v-else-if="moduleKey === 'titles'">
          <div class="form-grid">
            <el-form-item label="姓名" required><el-select v-model="form.personId" filterable><el-option v-for="item in people" :key="item.id" :label="`${item.name} · ${item.departmentName}`" :value="item.id" /></el-select></el-form-item>
            <el-form-item label="发证时间" required><el-date-picker v-model="form.issuedOn" value-format="YYYY-MM-DD" /></el-form-item>
          </div>
          <el-form-item label="职称证书编号" required><el-input v-model="form.certificateNumber" maxlength="120" /></el-form-item>
          <div class="form-grid">
            <el-form-item label="专业名称" required><el-input v-model="form.specialtyName" maxlength="200" /></el-form-item>
            <el-form-item label="发证单位" required><el-input v-model="form.issuingAuthority" maxlength="200" /></el-form-item>
          </div>
          <el-form-item label="职称等级" required><el-radio-group v-model="form.titleLevel"><el-radio v-for="item in levelOptions" :key="item[0]" :value="item[0]">{{ item[1] }}</el-radio></el-radio-group></el-form-item>
          <el-form-item label="专业类型" required><el-radio-group v-model="form.specialtyCategory"><el-radio v-for="item in categoryOptions" :key="item[0]" :value="item[0]">{{ item[1] }}</el-radio></el-radio-group></el-form-item>
          <el-form-item label="证书文件（JPG、PNG 或 BMP，不超过 3MB）">
            <label v-if="!readOnly" class="file-picker"><input type="file" accept="image/png,image/jpeg,image/bmp" @change="uploadFile($event,'attachmentFileId','attachmentFileName','PERSON_TITLE')" /><el-icon><Upload /></el-icon>{{ uploading === 'attachmentFileId' ? '上传中…' : form.attachmentFileName || '选择文件' }}</label>
            <el-link v-else-if="form.attachmentFileId" :href="fileUrl(form.attachmentFileId)" type="primary">下载 {{ form.attachmentFileName }}</el-link><span v-else>未上传</span>
          </el-form-item>
          <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
        </template>

        <template v-else-if="moduleKey === 'organization-qualifications'">
          <el-form-item label="资质编号" required><el-input v-model="form.certificateNumber" maxlength="160" /></el-form-item>
          <div class="form-grid">
            <el-form-item label="签发日期" required><el-date-picker v-model="form.issuedOn" value-format="YYYY-MM-DD" /></el-form-item>
            <el-form-item label="有效期至" required><el-date-picker v-model="form.validUntil" value-format="YYYY-MM-DD" /></el-form-item>
          </div>
          <el-form-item label="发证单位" required><el-input v-model="form.issuingAuthority" maxlength="240" /></el-form-item>
          <el-form-item label="检测范围" required><el-input v-model="form.testingScope" type="textarea" :rows="4" maxlength="1000" show-word-limit /></el-form-item>
          <el-form-item label="资质详情" required><el-checkbox-group v-model="form.qualificationDetails" class="qualification-grid"><el-checkbox v-for="item in qualificationOptions" :key="item[0]" :value="item[0]">{{ item[1] }}</el-checkbox></el-checkbox-group></el-form-item>
          <div class="form-grid">
            <el-form-item label="资质正本文件">
              <label v-if="!readOnly" class="file-picker"><input type="file" accept="image/png,image/jpeg,image/bmp,application/pdf" @change="uploadFile($event,'originalFileId','originalFileName','ORGANIZATION_QUALIFICATION_ORIGINAL')" /><el-icon><Upload /></el-icon>{{ uploading === 'originalFileId' ? '上传中…' : form.originalFileName || '选择文件' }}</label>
              <div v-if="form.originalFileId" class="controlled-file-row"><el-link :href="fileUrl(form.originalFileId)" type="primary">下载 {{ form.originalFileName || '资质正本' }}</el-link><el-button v-if="!readOnly" link type="danger" @click="form.originalFileId=null; form.originalFileName=''">移除</el-button></div>
              <span v-else-if="readOnly">未上传</span>
            </el-form-item>
            <el-form-item label="资质副本文件">
              <label v-if="!readOnly" class="file-picker"><input type="file" accept="image/png,image/jpeg,image/bmp,application/pdf" @change="uploadFile($event,'copyFileId','copyFileName','ORGANIZATION_QUALIFICATION_COPY')" /><el-icon><Upload /></el-icon>{{ uploading === 'copyFileId' ? '上传中…' : form.copyFileName || '选择文件' }}</label>
              <div v-if="form.copyFileId" class="controlled-file-row"><el-link :href="fileUrl(form.copyFileId)" type="primary">下载 {{ form.copyFileName || '资质副本' }}</el-link><el-button v-if="!readOnly" link type="danger" @click="form.copyFileId=null; form.copyFileName=''">移除</el-button></div>
              <span v-else-if="readOnly">未上传</span>
            </el-form-item>
          </div>
          <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
        </template>

        <template v-else-if="moduleKey === 'metrology-qualifications'">
          <el-form-item label="计量证书编号" required><el-input v-model="form.certificateNumber" maxlength="160" /></el-form-item>
          <div class="form-grid"><el-form-item label="证书签发日期" required><el-date-picker v-model="form.issuedOn" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="有效期至" required><el-date-picker v-model="form.validUntil" value-format="YYYY-MM-DD" /></el-form-item></div>
          <el-form-item label="发证单位" required><el-input v-model="form.issuingAuthority" maxlength="240" /></el-form-item>
          <el-form-item label="证书附件（正本和附表 PDF，最大 30MB）" required>
            <label v-if="!readOnly" class="file-picker"><input type="file" accept="application/pdf" @change="uploadFile($event,'certificateFileId','certificateFileName','METROLOGY_CERTIFICATE')" /><el-icon><Upload /></el-icon>{{ uploading === 'certificateFileId' ? '上传中…' : form.certificateFileName || '选择 PDF' }}</label>
            <div v-if="form.certificateFileId" class="controlled-file-row"><el-link :href="fileUrl(form.certificateFileId)" type="primary">下载 {{ form.certificateFileName || '计量证书' }}</el-link><el-button v-if="!readOnly" link type="danger" @click="form.certificateFileId=null; form.certificateFileName=''">移除</el-button></div>
            <span v-else-if="readOnly">未上传</span>
          </el-form-item>
          <el-form-item label="其他附件（标准变更确认或声明 PDF，最大 30MB）">
            <label v-if="!readOnly" class="file-picker"><input type="file" accept="application/pdf" @change="uploadFile($event,'otherFileId','otherFileName','METROLOGY_OTHER')" /><el-icon><Upload /></el-icon>{{ uploading === 'otherFileId' ? '上传中…' : form.otherFileName || '选择 PDF' }}</label>
            <div v-if="form.otherFileId" class="controlled-file-row"><el-link :href="fileUrl(form.otherFileId)" type="primary">下载 {{ form.otherFileName || '其他附件' }}</el-link><el-button v-if="!readOnly" link type="danger" @click="form.otherFileId=null; form.otherFileName=''">移除</el-button></div>
            <span v-else-if="readOnly">未上传</span>
          </el-form-item>
          <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
        </template>

        <template v-else>
          <el-alert v-if="form.parentId" class="shelf-parent-alert" :title="`上级货架：${form.parentShelfNumber}`" description="系统只允许根货架和一级下级货架；保存后不能变更上级。" type="info" :closable="false" show-icon />
          <el-form-item label="货架编号" required><el-input v-model="form.shelfNumber" maxlength="80" /></el-form-item>
          <el-form-item label="排序（数字越小越靠前）"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
          <el-alert title="二维码编码内容为货架 UUID，可预览并批量下载；扫码硬件与打印协议仍由外部适配器接入。" type="info" :closable="false" show-icon />
        </template>
      </el-form>
      <template #footer><el-button @click="drawer=false">{{ readOnly ? '关闭' : '取消' }}</el-button><el-button v-if="!readOnly" type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-drawer>

    <el-dialog v-model="titleImportDialog" title="批量导入人员职称" width="620px" destroy-on-close append-to-body>
      <el-alert title="只接受本系统受控模板。服务端会拒绝公式，并在写入前完整校验全部行；任一行失败则整批零写入并返回行号。" type="warning" :closable="false" show-icon />
      <div class="person-import-actions">
        <el-button :loading="saving" @click="downloadTitleTemplate">下载受控模板</el-button>
        <label class="file-picker"><input type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="chooseTitleImport" /><el-icon><Upload /></el-icon>{{ titleImportFile?.name || '选择已填写模板' }}</label>
      </div>
      <template #footer><el-button @click="titleImportDialog=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!titleImportFile" @click="submitTitleImport">完整校验并导入</el-button></template>
    </el-dialog>

    <el-dialog v-model="memberPicker" title="人员选择" width="min(1080px, calc(100vw - 48px))" top="6vh" destroy-on-close append-to-body>
      <el-alert title="仅显示当前机构且位于当前账号数据范围内的人员；联系电话和证件号码按最小必要原则脱敏。" type="info" :closable="false" show-icon />
      <section class="member-picker-filter">
        <el-select v-model="memberFilters.applicationState" clearable placeholder="申报状态">
          <el-option label="未提交" value="DRAFT" /><el-option label="已提交" value="SUBMITTED" /><el-option label="已核验" value="VERIFIED" /><el-option label="已退回" value="RETURNED" />
        </el-select>
        <el-select v-model="memberFilters.personType" clearable placeholder="人员类型">
          <el-option label="在职员工" value="EMPLOYEE" /><el-option label="合同人员" value="CONTRACTOR" /><el-option label="外部专家" value="EXTERNAL_EXPERT" /><el-option label="实习人员" value="INTERN" />
        </el-select>
        <el-input v-model="memberFilters.name" clearable placeholder="姓名或工号" @keyup.enter="searchMemberCandidates" />
        <el-input v-model="memberFilters.position" clearable placeholder="职位或职称" @keyup.enter="searchMemberCandidates" />
        <el-button type="primary" :icon="Search" @click="searchMemberCandidates">查询</el-button>
        <el-button :icon="Refresh" @click="resetMemberCandidates">重置</el-button>
      </section>
      <div class="member-picker-summary" role="status">项目组已有 {{ form.members?.length ?? 0 }} 人，本次新选 {{ memberSelections.length }} 人；已停用、已归档或已在项目组中的人员不可重复选择。</div>
      <el-table ref="memberTable" v-loading="memberLoading" :data="memberRows" border stripe row-key="id" max-height="min(42dvh, 420px)" class="member-picker-table" @selection-change="onMemberSelectionChange">
        <el-table-column type="selection" width="44" :selectable="memberSelectable" />
        <el-table-column label="人员" width="175"><template #default="scope"><div class="member-candidate-person"><el-avatar v-if="scope.row.avatarFileId" :size="30" :src="fileUrl(scope.row.avatarFileId)" /><div><strong>{{ scope.row.name }}</strong><span>{{ scope.row.employeeNumber }}</span></div></div></template></el-table-column>
        <el-table-column label="性别 / 类型" width="105"><template #default="scope"><div class="training-primary">{{ genderLabel(scope.row.gender) }}</div><div class="training-secondary">{{ personTypeLabel(scope.row.personType) }}</div></template></el-table-column>
        <el-table-column label="联系信息" width="150"><template #default="scope"><div class="training-primary">{{ scope.row.maskedPhone || '未登记电话' }}</div><div class="training-secondary">{{ scope.row.maskedNationalId || '未登记证件' }}</div></template></el-table-column>
        <el-table-column label="职位 / 职称" min-width="145"><template #default="scope"><div class="training-primary">{{ scope.row.position || '未配置职位' }}</div><div class="training-secondary">{{ scope.row.title || '未配置职称' }}</div></template></el-table-column>
        <el-table-column prop="departmentName" label="所在部门" min-width="170" show-overflow-tooltip />
        <el-table-column prop="organizationName" label="所在单位" min-width="170" show-overflow-tooltip />
        <el-table-column label="申报状态" width="90"><template #default="scope"><el-tag size="small" :type="scope.row.applicationState === 'VERIFIED' ? 'success' : 'warning'">{{ applicationLabel(scope.row.applicationState) }}</el-tag></template></el-table-column>
      </el-table>
      <footer class="member-picker-footer"><span>共 {{ memberTotal }} 人 · 当前第 {{ memberCursorHistory.length + 1 }} 页 · 每页最多 20 人</span><div><el-button :disabled="!memberCursorHistory.length" @click="previousMemberPage">上一页</el-button><el-button :disabled="!memberNextCursor" @click="nextMemberPage">下一页</el-button></div></footer>
      <template #footer><el-button @click="memberPicker=false">取消</el-button><el-button type="primary" :disabled="!memberSelections.length" @click="confirmMemberSelection">确定添加 {{ memberSelections.length }} 人</el-button></template>
    </el-dialog>
  </section>
</template>
