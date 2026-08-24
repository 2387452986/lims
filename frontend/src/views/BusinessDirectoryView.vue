<script setup lang="ts">
import {
  Connection, Edit, Location, MoreFilled, Plus, Refresh, Search, Tickets, Upload, UserFilled,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, errorMessage } from '../api'
import { flattenCatalog, type CatalogNode, type DetectionParameter, type ProjectRevision } from '../detection'
import { moduleLabel } from '../navigation'
import { localDateString } from '../presentation'
import { useAuthStore } from '../stores/auth'

type Row = Record<string, any>
type CursorPage = { items: Row[]; nextCursor?: string; hasMore: boolean; total: number }
type StoredFile = { id: string; originalName: string }
type PartNode = Row & { children: PartNode[] }
type ProjectOptions = { projectTypes: string[]; projectGrades: string[]; defaultDetectionCategories: string[] }
type DivisionOption = { name: string; sortOrder: number; hasChildren: boolean }

const route = useRoute()
const auth = useAuthStore()
const moduleKey = computed(() => String(route.params.moduleKey))
const title = computed(() => moduleLabel(moduleKey.value))
const isProjects = computed(() => moduleKey.value === 'projects')
const canWrite = computed(() => auth.user?.permissions.includes('MODULE_WRITE'))
const canTransition = computed(() => auth.user?.permissions.includes('BUSINESS_TRANSITION'))
const isAdmin = computed(() => auth.user?.permissions.includes('SYSTEM_ADMIN'))
const loading = ref(false)
const saving = ref(false)
const rows = ref<Row[]>([])
const total = ref(0)
const cursor = ref('')
const nextCursor = ref('')
const cursorHistory = ref<string[]>([])
const selectedRows = ref<Row[]>([])
const advancedOpen = ref(true)
const customers = ref<Row[]>([])
const customerLoading = ref(false)
const projectTeams = ref<Row[]>([])
const projectOptions = ref<ProjectOptions>({ projectTypes: [], projectGrades: [], defaultDetectionCategories: [] })
const provinces = ref<DivisionOption[]>([])
const cities = ref<DivisionOption[]>([])
const districts = ref<DivisionOption[]>([])
const divisionLoading = ref(false)
const catalog = ref<CatalogNode[]>([])
const projectDrawer = ref(false)
const customerDrawer = ref(false)
const projectTab = ref('basic')
const editing = ref<Row>()
const projectForm = reactive<Row>({})
const customerForm = reactive<Row>({})
const settlementContacts = ref<Row[]>([])
const customerPeople = ref<Row[]>([])
const planDialog = ref(false)
const plans = ref<Row[]>([])
const planQuery = ref('')
const planEditor = ref(false)
const planForm = reactive<Row>({})
const planParameters = ref<DetectionParameter[]>([])
const progressDialog = ref(false)
const progressMarks = ref<Row[]>([])
const progressForm = reactive<Row>({})
const partsDialog = ref(false)
const parts = ref<Row[]>([])
const partEditor = ref(false)
const partForm = reactive<Row>({})
const locationDialog = ref(false)
const accountDialog = ref(false)
const accountUsers = ref<Row[]>([])
const customerAccounts = ref<Row[]>([])
const accountForm = reactive<Row>({ userId: '', relationship: 'REPORT_VIEWER' })
const filters = reactive<Row>({
  query: '', regulatoryOrganization: '', statuses: [], regulatoryLinked: '', projectTeamId: '',
  number: '', pileSerialNumber: '', name: '', entrustingName: '', ownerName: '', supervisionName: '', constructionName: '',
  unitType: '', code: '', customerName: '', settlementName: '', settlementPhone: '', contactName: '', contactPhone: '', active: '',
})

const selected = computed(() => selectedRows.value[0])
const flatCatalog = computed(() => flattenCatalog(catalog.value).filter(item => item.type === 'PROJECT' && item.projectRevisionId))
const partTree = computed<PartNode[]>(() => buildPartTree(parts.value))
const partParentTree = computed<PartNode[]>(() => prunePartTree(partTree.value, partForm.id))
const descriptions = computed(() => isProjects.value
  ? '工程主体、参建单位、监督关联、检测计划、进度与单体部位一体管理'
  : '委托客户、结算人员、客户人员、项目关系与账号授权一体管理')
const labels: Record<string, string> = {
  DRAFT: '已保存未提交', ACTIVE: '进行中', SUSPENDED: '已暂停', COMPLETED: '已完成', CLOSED: '已关闭',
  OWNER: '建设单位', ENTRUSTING: '委托单位', SUPERVISION: '监理单位', CONSTRUCTION: '施工单位',
  SAMPLING: '抽样单位', DESIGN: '设计单位', SURVEY: '勘察单位', GENERAL_CONTRACTOR: '总承包单位', OTHER: '其他',
  FOLLOW_PROJECT: '跟随检测项目', ALWAYS: '始终使用', NEVER: '不使用',
  UNKNOWN: '未知', MALE: '男', FEMALE: '女', CONTACT: '联系人', ENTRUSTER: '委托人',
  SAMPLER: '抽样人', WITNESS: '见证人', SETTLEMENT: '结算人', LEGAL_REPRESENTATIVE: '法定代表人',
  NORMAL: '正常', DELAYED: '滞后', ADMIN: '客户管理员', MEMBER: '普通成员', REPORT_VIEWER: '报告查看人',
}
const actionLabels: Record<string, string> = {
  SUBMIT: '提交', SUSPEND: '暂停', ACTIVATE: '恢复', COMPLETE: '标记完成', REOPEN: '重新开启', CLOSE: '关闭',
  DEACTIVATE: '停用',
}

function showError(error: unknown) {
  ElMessage.error(errorMessage(error))
}
function typeOfStatus(value: string) {
  if (['ACTIVE', 'COMPLETED', 'NORMAL'].includes(value)) return 'success'
  if (['CLOSED', 'INACTIVE'].includes(value)) return 'info'
  if (['SUSPENDED', 'DELAYED'].includes(value)) return 'warning'
  return 'primary'
}
function formatDate(value?: string) { return value ? new Date(value).toLocaleDateString('zh-CN') : '—' }
function blankProject() {
  const today = localDateString()
  Object.keys(projectForm).forEach(key => delete projectForm[key])
  Object.assign(projectForm, {
    number: '', name: '', customerId: customers.value[0]?.id ?? '', location: '', pileSerialNumber: '',
    entrustingManual: false, entrustingManualName: '', samplingManual: true, samplingCustomerId: '',
    ownerManual: true, ownerCustomerId: '', supervisionManual: true, supervisionCustomerId: '',
    constructionManual: true, constructionCustomerId: '',
    projectType: '', projectGrade: '', regulatoryOrganization: '', regulatoryProjectCode: '',
    regulatoryProjectName: '', regulatoryLinked: false, entrusterName: '', entrusterPhone: '',
    settlementContactName: '', settlementContactPhone: '', samplingUnitName: '', samplerName: '',
    samplerPhone: '', samplerCertificateNumber: '', province: '', city: '', district: '', detailedAddress: '',
    sectionNumber: '', regionCode: '', defaultDetectionCategory: '', remark: '', ownerName: '',
    ownerReportAccess: false, supervisionName: '', witnessName: '', witnessPhone: '',
    supervisionReportAccess: false, constructionName: '', constructionReportAccess: false, surveyName: '',
    designName: '', generalContractorName: '', customerReportAccess: false, cmaSealPolicy: 'FOLLOW_PROJECT',
    qualificationSealPolicy: 'FOLLOW_PROJECT', electronicSealPolicy: 'FOLLOW_PROJECT', customerPrintAllowed: false,
    customerPrintLimit: null, settlementMethod: '', projectTeamId: '', reportPrintRestricted: false,
    externalContractNumber: '', handledOn: today, latitude: '', longitude: '', version: 0,
  })
  cities.value = []
  districts.value = []
}
function blankCustomer() {
  Object.keys(customerForm).forEach(key => delete customerForm[key])
  Object.assign(customerForm, { code: '', shortName: '', name: '', unitType: 'OTHER', unifiedCreditCode: '',
    commissioningUnit: false, contactName: '', contactPhone: '', landline: '', address: '', fax: '', email: '',
    remark: '', active: true, version: 0 })
  settlementContacts.value = []
  customerPeople.value = []
}
function projectParams() {
  const params = new URLSearchParams({ query: String(filters.query), limit: '50' })
  if (cursor.value) params.set('cursor', cursor.value)
  if (filters.regulatoryOrganization) params.set('regulatoryOrganization', filters.regulatoryOrganization)
  if (filters.statuses?.length) params.set('statuses', filters.statuses.join(','))
  if (filters.regulatoryLinked !== '') params.set('regulatoryLinked', String(filters.regulatoryLinked))
  if (filters.projectTeamId) params.set('projectTeamId', filters.projectTeamId)
  for (const key of ['number', 'pileSerialNumber', 'name', 'entrustingName', 'ownerName', 'supervisionName', 'constructionName']) {
    if (filters[key]) params.set(key, filters[key])
  }
  return params
}
function customerParams() {
  const params = new URLSearchParams({ query: String(filters.query), limit: '50' })
  if (cursor.value) params.set('cursor', cursor.value)
  for (const key of ['unitType', 'code', 'settlementName', 'settlementPhone', 'contactName', 'contactPhone']) {
    if (filters[key]) params.set(key, filters[key])
  }
  if (filters.customerName) params.set('name', filters.customerName)
  if (filters.active !== '') params.set('active', String(filters.active))
  return params
}
async function loadReferences() {
  try {
    const [customerPage, teamPage, tree, options, provinceOptions] = await Promise.all([
      api<CursorPage>('/api/customers?active=true&limit=100'),
      api<CursorPage>('/api/foundation/project-teams?state=ACTIVE&limit=100'),
      api<CatalogNode[]>('/api/detection-catalog/tree?includeInactive=false&includeHistory=false'),
      api<ProjectOptions>('/api/projects/options'),
      api<DivisionOption[]>('/api/administrative-divisions'),
    ])
    customers.value = customerPage.items
    projectTeams.value = teamPage.items
    catalog.value = tree
    projectOptions.value = options
    provinces.value = provinceOptions
  } catch (error) { showError(error) }
}
async function loadCities(province: string) {
  if (!province) { cities.value = []; districts.value = []; return }
  divisionLoading.value = true
  try { cities.value = await api<DivisionOption[]>(`/api/administrative-divisions?province=${encodeURIComponent(province)}`) }
  catch (error) { showError(error) }
  finally { divisionLoading.value = false }
}
async function loadDistricts(province: string, city: string) {
  if (!province || !city) { districts.value = []; return }
  divisionLoading.value = true
  try { districts.value = await api<DivisionOption[]>(`/api/administrative-divisions?province=${encodeURIComponent(province)}&city=${encodeURIComponent(city)}`) }
  catch (error) { showError(error) }
  finally { divisionLoading.value = false }
}
async function changeProvince(value: string) {
  projectForm.city = ''
  projectForm.district = ''
  districts.value = []
  await loadCities(value)
}
async function changeCity(value: string) {
  projectForm.district = ''
  await loadDistricts(projectForm.province, value)
}
async function searchCustomerOptions(query = '') {
  customerLoading.value = true
  try {
    const page = await api<CursorPage>(`/api/customers?active=true&limit=50&query=${encodeURIComponent(query.trim())}`)
    const selectedIds = new Set([
      projectForm.customerId, projectForm.samplingCustomerId, projectForm.ownerCustomerId,
      projectForm.supervisionCustomerId, projectForm.constructionCustomerId,
    ].filter(Boolean))
    const retained = customers.value.filter(item => selectedIds.has(item.id))
    customers.value = [...retained, ...page.items.filter(item => !retained.some(existing => existing.id === item.id))]
  } catch (error) { showError(error) }
  finally { customerLoading.value = false }
}
function handleCustomerVisibleChange(visible: boolean) {
  if (visible) void searchCustomerOptions('')
}
async function ensureCustomerOptions(ids: string[]) {
  const missing = [...new Set(ids.filter(Boolean))].filter(id => !customers.value.some(item => item.id === id))
  if (!missing.length) return
  const details = await Promise.all(missing.map(id => api<Row>(`/api/customers/${id}`)))
  customers.value.push(...details.map(detail => detail.summary))
}
async function load() {
  loading.value = true
  try {
    const result = await api<CursorPage>(isProjects.value ? `/api/projects?${projectParams()}` : `/api/customers?${customerParams()}`)
    rows.value = result.items; total.value = result.total; nextCursor.value = result.nextCursor ?? ''; selectedRows.value = []
  } catch (error) { showError(error) }
  finally { loading.value = false }
}
function search() { cursor.value = ''; cursorHistory.value = []; load() }
function reset() {
  Object.assign(filters, { query: '', regulatoryOrganization: '', statuses: [], regulatoryLinked: '', projectTeamId: '',
    number: '', pileSerialNumber: '', name: '', entrustingName: '', ownerName: '', supervisionName: '', constructionName: '',
    unitType: '', code: '', customerName: '', settlementName: '', settlementPhone: '', contactName: '', contactPhone: '', active: '' })
  search()
}
function nextPage() { if (nextCursor.value) { cursorHistory.value.push(cursor.value); cursor.value = nextCursor.value; load() } }
function previousPage() { if (cursorHistory.value.length) { cursor.value = cursorHistory.value.pop() ?? ''; load() } }

async function openCreate() {
  editing.value = undefined
  if (isProjects.value) { blankProject(); projectTab.value = 'basic'; projectDrawer.value = true }
  else { blankCustomer(); customerDrawer.value = true }
}
async function openEdit(row = selected.value) {
  if (!row) return ElMessage.warning('请先选择一条记录')
  editing.value = row
  try {
    if (isProjects.value) {
      const detail = await api<Row>(`/api/projects/${row.id}`); blankProject(); Object.assign(projectForm, detail.information)
      await loadCities(projectForm.province)
      await loadDistricts(projectForm.province, projectForm.city)
      await ensureCustomerOptions([detail.information.customerId, ...detail.parties.map((item: Row) => item.customerId)])
      const entrusting = detail.parties.find((item: Row) => item.partyRole === 'ENTRUSTING')
      const sampling = detail.parties.find((item: Row) => item.partyRole === 'SAMPLING')
      projectForm.entrustingManual = Boolean(entrusting?.manualEntry)
      projectForm.entrustingManualName = projectForm.entrustingManual ? (entrusting?.name ?? '') : ''
      projectForm.samplingManual = sampling ? Boolean(sampling.manualEntry) : true
      projectForm.samplingCustomerId = sampling?.customerId ?? ''
      for (const [role, manualKey, customerKey] of [
        ['OWNER', 'ownerManual', 'ownerCustomerId'],
        ['SUPERVISION', 'supervisionManual', 'supervisionCustomerId'],
        ['CONSTRUCTION', 'constructionManual', 'constructionCustomerId'],
      ]) {
        const party = detail.parties.find((item: Row) => item.partyRole === role)
        projectForm[manualKey] = party ? Boolean(party.manualEntry) : true
        projectForm[customerKey] = party?.customerId ?? ''
      }
      projectForm.projectTeamId ||= ''; projectTab.value = 'basic'; projectDrawer.value = true
    } else {
      const detail = await api<Row>(`/api/customers/${row.id}`); blankCustomer(); Object.assign(customerForm, detail.summary, detail)
      settlementContacts.value = detail.settlementContacts.map((item: Row) => ({ ...item }))
      customerPeople.value = detail.people.map((item: Row) => ({ ...item })); customerDrawer.value = true
    }
  } catch (error) { showError(error) }
}
function customerById(id: string) { return customers.value.find(item => item.id === id) }
function partyName(manual: boolean, name: string, customerId: string) {
  return manual ? name : (customerById(customerId)?.name ?? '')
}
function projectParties() {
  const customer = customerById(projectForm.customerId)
  const samplingCustomer = customerById(projectForm.samplingCustomerId)
  const parties: Row[] = [
    { partyRole: 'ENTRUSTING', customerId: projectForm.entrustingManual ? null : projectForm.customerId,
      name: projectForm.entrustingManual ? projectForm.entrustingManualName : (customer?.name ?? ''),
      contactName: projectForm.entrusterName, contactPhone: projectForm.entrusterPhone,
      manualEntry: Boolean(projectForm.entrustingManual), reportAccess: projectForm.customerReportAccess },
    { partyRole: 'OWNER', customerId: projectForm.ownerManual ? null : projectForm.ownerCustomerId,
      name: partyName(projectForm.ownerManual, projectForm.ownerName, projectForm.ownerCustomerId),
      manualEntry: Boolean(projectForm.ownerManual), reportAccess: projectForm.ownerReportAccess },
    { partyRole: 'SUPERVISION', customerId: projectForm.supervisionManual ? null : projectForm.supervisionCustomerId,
      name: partyName(projectForm.supervisionManual, projectForm.supervisionName, projectForm.supervisionCustomerId),
      contactName: projectForm.witnessName, contactPhone: projectForm.witnessPhone,
      manualEntry: Boolean(projectForm.supervisionManual), reportAccess: projectForm.supervisionReportAccess },
    { partyRole: 'CONSTRUCTION', customerId: projectForm.constructionManual ? null : projectForm.constructionCustomerId,
      name: partyName(projectForm.constructionManual, projectForm.constructionName, projectForm.constructionCustomerId),
      manualEntry: Boolean(projectForm.constructionManual), reportAccess: projectForm.constructionReportAccess },
  ]
  const samplingName = projectForm.samplingManual ? projectForm.samplingUnitName : samplingCustomer?.name
  if (samplingName) parties.push({ partyRole: 'SAMPLING', customerId: projectForm.samplingManual ? null : projectForm.samplingCustomerId,
    name: samplingName, contactName: projectForm.samplerName, contactPhone: projectForm.samplerPhone,
    certificateNumber: projectForm.samplerCertificateNumber, manualEntry: Boolean(projectForm.samplingManual), reportAccess: false })
  for (const [partyRole, key] of [['SURVEY', 'surveyName'], ['DESIGN', 'designName'], ['GENERAL_CONTRACTOR', 'generalContractorName']]) {
    if (projectForm[key]) parties.push({ partyRole, name: projectForm[key], manualEntry: true, reportAccess: false })
  }
  return parties
}
function projectBody() {
  const body: Row = { ...projectForm, parties: projectParties(), projectTeamId: projectForm.projectTeamId || null,
    samplingUnitName: projectForm.samplingManual ? projectForm.samplingUnitName : (customerById(projectForm.samplingCustomerId)?.name ?? ''),
    ownerName: partyName(projectForm.ownerManual, projectForm.ownerName, projectForm.ownerCustomerId),
    supervisionName: partyName(projectForm.supervisionManual, projectForm.supervisionName, projectForm.supervisionCustomerId),
    constructionName: partyName(projectForm.constructionManual, projectForm.constructionName, projectForm.constructionCustomerId),
    customerPrintLimit: projectForm.customerPrintAllowed ? projectForm.customerPrintLimit : null }
  delete body.id; delete body.updatedAt
  for (const key of ['entrustingManual', 'entrustingManualName', 'samplingManual', 'samplingCustomerId',
    'ownerManual', 'ownerCustomerId', 'supervisionManual', 'supervisionCustomerId',
    'constructionManual', 'constructionCustomerId']) delete body[key]
  if (!editing.value) delete body.expectedVersion
  else body.expectedVersion = projectForm.version
  return body
}
async function saveProject(close = true) {
  const body = projectBody()
  if (!body.number || !body.name || !body.customerId || !body.ownerName
    || !body.supervisionName || !body.constructionName) return ElMessage.warning('请填写工程编号、名称、委托单位及三方参建单位')
  if (projectForm.entrustingManual && !projectForm.entrustingManualName) return ElMessage.warning('请填写手工委托单位名称')
  saving.value = true
  try {
    const path = editing.value ? `/api/projects/${editing.value.id}` : '/api/projects'
    const detail = await api<Row>(path, { method: editing.value ? 'PUT' : 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) })
    Object.assign(projectForm, detail.information); editing.value = detail.information
    if (close) projectDrawer.value = false
    ElMessage.success('工程信息已保存'); await load(); await loadReferences()
  } catch (error) { showError(error) }
  finally { saving.value = false }
}
async function generateProjectNumber() {
  if (editing.value) return
  try {
    const generated = await api<{ number: string }>('/api/projects/number-generations', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
    })
    projectForm.number = generated.number
    ElMessage.success('工程自编号已按受控规则生成')
  } catch (error) { showError(error) }
}
function customerBody() {
  const body = { code: customerForm.code, shortName: customerForm.shortName, name: customerForm.name,
    unitType: customerForm.unitType, unifiedCreditCode: customerForm.unifiedCreditCode,
    commissioningUnit: customerForm.commissioningUnit, contactName: customerForm.contactName,
    contactPhone: customerForm.contactPhone, landline: customerForm.landline, address: customerForm.address,
    fax: customerForm.fax, email: customerForm.email, remark: customerForm.remark, active: customerForm.active }
  if (editing.value) Object.assign(body, { expectedVersion: customerForm.version })
  return body
}
async function saveCustomer() {
  if (!customerForm.code || !customerForm.shortName || !customerForm.name) return ElMessage.warning('单位编号、简称和全称不能为空')
  saving.value = true
  try {
    const path = editing.value ? `/api/customers/${editing.value.id}` : '/api/customers'
    const detail = await api<Row>(path, { method: editing.value ? 'PUT' : 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(customerBody()) })
    const id = detail.summary.id
    for (const item of settlementContacts.value) {
      if (item.id && !item._dirty) continue
      const target = item.id ? `/api/customers/${id}/settlement-contacts/${item.id}` : `/api/customers/${id}/settlement-contacts`
      await api(target, { method: item.id ? 'PUT' : 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ name: item.name, phone: item.phone, otherContact: item.otherContact, primaryContact: item.primaryContact, active: item.active, ...(item.id ? { expectedVersion: item.version } : {}) }) })
    }
    for (const item of customerPeople.value) {
      if (item.id && !item._dirty) continue
      const target = item.id ? `/api/customers/${id}/people/${item.id}` : `/api/customers/${id}/people`
      await api(target, { method: item.id ? 'PUT' : 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ name: item.name, gender: item.gender, personType: item.personType, phone: item.phone,
          certificateNumber: item.certificateNumber, remark: item.remark, avatarFileId: item.avatarFileId || null,
          active: item.active, ...(item.id ? { expectedVersion: item.version } : {}) }) })
    }
    customerDrawer.value = false; ElMessage.success('客户信息已保存'); await load(); await loadReferences()
  } catch (error) { showError(error) }
  finally { saving.value = false }
}
function addSettlementContact() { settlementContacts.value.push({ name: '', phone: '', otherContact: true, primaryContact: !settlementContacts.value.length, active: true, _dirty: true }) }
function addCustomerPerson() { customerPeople.value.push({ name: '', gender: 'UNKNOWN', personType: 'CONTACT', phone: '', certificateNumber: '', remark: '', active: true, _dirty: true }) }
async function uploadAvatar(event: Event, item: Row) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]
  if (!file) return
  try {
    const data = new FormData(); data.append('file', file); data.append('purpose', 'CUSTOMER_PERSON_AVATAR')
    const stored = await api<StoredFile>('/api/files', { method: 'POST', body: data })
    item.avatarFileId = stored.id; item.avatarFileName = stored.originalName; item._dirty = true; ElMessage.success('头像已安全上传')
  } catch (error) { showError(error) }
  finally { input.value = '' }
}
async function transition(row: Row, action: string) {
  let comment = `${actionLabels[action] ?? action}${isProjects.value ? '工程' : '客户'}`
  if (['SUSPEND', 'CLOSE', 'DEACTIVATE'].includes(action)) {
    try { comment = (await ElMessageBox.prompt('请说明本次状态变更原因', actionLabels[action], { inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '原因不能为空' })).value }
    catch (error) { if (error === 'cancel') return; throw error }
  }
  try {
    await api(`/api/${isProjects.value ? 'projects' : 'customers'}/${row.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: `${isProjects.value ? 'PROJECT' : 'CUSTOMER'}_CONTROL`, comment }) })
    ElMessage.success('状态已更新'); await load(); await loadReferences()
  } catch (error) { showError(error) }
}
async function openPlans() {
  if (!selected.value) return ElMessage.warning('请先选择工程')
  planDialog.value = true; await loadPlans()
}
async function loadPlans() { plans.value = await api<Row[]>(`/api/projects/${selected.value.id}/plans?query=${encodeURIComponent(planQuery.value)}`) }
function blankPlan() { Object.keys(planForm).forEach(key => delete planForm[key]); Object.assign(planForm, { detectionProjectRevisionId: '', parameterId: '', categoryName: '', projectName: '', parameterName: '', plannedQuantity: '1', unit: '组', bindingParameter: '', detectionMethod: '', plannedCompletionOn: '', unitPrice: '0', active: true, sortOrder: plans.value.length + 1 }) }
function openPlanEditor(row?: Row) { blankPlan(); if (row) Object.assign(planForm, row); planParameters.value = []; planEditor.value = true; if (row?.detectionProjectRevisionId) loadPlanRevision(row.detectionProjectRevisionId) }
async function loadPlanRevision(id: string) {
  if (!id) { planParameters.value = []; return }
  const revision = await api<ProjectRevision>(`/api/detection-project-revisions/${id}`)
  planParameters.value = revision.parameters.filter(item => item.status === 'PUBLISHED' && item.enabled)
  planForm.projectName = revision.projectName; planForm.categoryName = revision.path
}
function choosePlanParameter(id: string) {
  const parameter = planParameters.value.find(item => item.id === id)
  if (!parameter) return
  planForm.parameterName = parameter.name; planForm.bindingParameter = `${parameter.code} · ${parameter.name}`
  planForm.unit = parameter.unit || planForm.unit; planForm.unitPrice = parameter.price || '0'
}
async function savePlan() {
  if (!planForm.projectName || !planForm.parameterName || !planForm.plannedQuantity || !planForm.unit) return ElMessage.warning('请填写检测项目、参数、计划量和单位')
  saving.value = true
  try {
    const path = planForm.id ? `/api/projects/${selected.value.id}/plans/${planForm.id}` : `/api/projects/${selected.value.id}/plans`
    const body = { detectionProjectRevisionId: planForm.detectionProjectRevisionId || null, parameterId: planForm.parameterId || null,
      categoryName: planForm.categoryName, projectName: planForm.projectName, parameterName: planForm.parameterName,
      plannedQuantity: String(planForm.plannedQuantity), unit: planForm.unit, bindingParameter: planForm.bindingParameter,
      detectionMethod: planForm.detectionMethod, plannedCompletionOn: planForm.plannedCompletionOn || null,
      unitPrice: String(planForm.unitPrice || '0'), active: planForm.active, sortOrder: Number(planForm.sortOrder || 0),
      ...(planForm.id ? { expectedVersion: planForm.version } : {}) }
    await api(path, { method: planForm.id ? 'PUT' : 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) })
    planEditor.value = false; ElMessage.success('检测计划已保存'); await loadPlans()
  } catch (error) { showError(error) }
  finally { saving.value = false }
}
async function openProgress() {
  if (!selected.value) return ElMessage.warning('请先选择工程')
  progressMarks.value = await api<Row[]>(`/api/projects/${selected.value.id}/progress-marks`)
  Object.assign(progressForm, { progressPercent: selected.value.progressPercent ?? '0', progressLabel: '', occurredOn: localDateString(), comment: '' })
  progressDialog.value = true
}
async function saveProgress() {
  if (!progressForm.progressLabel) return ElMessage.warning('请填写进度说明')
  saving.value = true
  try {
    await api(`/api/projects/${selected.value.id}/progress-marks`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ ...progressForm, progressPercent: String(progressForm.progressPercent), expectedVersion: selected.value.version }) })
    progressDialog.value = false; ElMessage.success('工程进度已标记'); await load()
  } catch (error) { showError(error) }
  finally { saving.value = false }
}
function buildPartTree(source: Row[], parentId: string | null = null): PartNode[] { return source.filter(item => (item.parentId ?? null) === parentId).map(item => ({ ...item, children: buildPartTree(source, item.id) })) }
function prunePartTree(source: PartNode[], excludedId?: string): PartNode[] { return source.filter(item => item.id !== excludedId).map(item => ({ ...item, children: prunePartTree(item.children, excludedId) })) }
async function openParts() { if (!selected.value) return ElMessage.warning('请先选择工程'); parts.value = await api<Row[]>(`/api/projects/${selected.value.id}/parts`); partsDialog.value = true }
function openPartEditor(row?: Row) { Object.keys(partForm).forEach(key => delete partForm[key]); Object.assign(partForm, row ?? { parentId: '', code: '', name: '', sortOrder: parts.value.length + 1, active: true }); partEditor.value = true }
async function savePart() {
  if (!partForm.code || !partForm.name) return ElMessage.warning('请填写部位代码和名称')
  saving.value = true
  try {
    const path = partForm.id ? `/api/projects/${selected.value.id}/parts/${partForm.id}` : `/api/projects/${selected.value.id}/parts`
    await api(path, { method: partForm.id ? 'PUT' : 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ parentId: partForm.parentId || null, code: partForm.code, name: partForm.name, sortOrder: Number(partForm.sortOrder || 0), active: partForm.active, ...(partForm.id ? { expectedVersion: partForm.version } : {}) }) })
    partEditor.value = false; parts.value = await api<Row[]>(`/api/projects/${selected.value.id}/parts`); ElMessage.success('单体部位已保存')
  } catch (error) { showError(error) }
  finally { saving.value = false }
}
async function openLocation() { if (!selected.value) return ElMessage.warning('请先选择工程'); await openEdit(selected.value); projectDrawer.value = false; locationDialog.value = true }
async function saveLocation() { await saveProject(false); locationDialog.value = false }
async function openAccounts() {
  if (!selected.value) return ElMessage.warning('请先选择客户')
  if (!isAdmin.value) return ElMessage.warning('只有系统管理员可以配置客户关联账号')
  const [detail, users] = await Promise.all([api<Row>(`/api/customers/${selected.value.id}`), api<Row[]>('/api/access-control/users')])
  customerAccounts.value = detail.accounts; accountUsers.value = users.filter(item => item.enabled); Object.assign(accountForm, { userId: '', relationship: 'REPORT_VIEWER' }); accountDialog.value = true
}
async function linkAccount() {
  if (!accountForm.userId) return ElMessage.warning('请选择账号')
  await api(`/api/customers/${selected.value.id}/accounts`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(accountForm) })
  ElMessage.success('账号已关联'); await openAccounts(); await load()
}
async function unlinkAccount(row: Row) {
  await ElMessageBox.confirm(`确认解除 ${row.displayName} 的客户关联？`, '解除关联', { type: 'warning' })
  await api(`/api/customers/${selected.value.id}/accounts/${row.userId}`, { method: 'DELETE' })
  ElMessage.success('关联已解除'); await openAccounts(); await load()
}

watch(moduleKey, () => { reset(); load(); loadReferences() })
onMounted(async () => { await loadReferences(); await load() })
</script>

<template>
  <section class="page business-directory-page">
    <header class="page-heading">
      <div><h1>{{ title }}</h1><p>{{ descriptions }}</p></div>
      <div class="heading-actions"><el-button :icon="Refresh" @click="reset">刷新</el-button><el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">{{ isProjects ? '添加工程' : '添加客户' }}</el-button></div>
    </header>

    <section class="directory-filters">
      <div class="filter-primary"><el-input v-model="filters.query" clearable :prefix-icon="Search" :placeholder="isProjects ? '搜索工程编号、名称或委托单位' : '搜索单位编号、简称、全称或联系人'" @keyup.enter="search" /><el-button type="primary" :icon="Search" @click="search">查询</el-button><el-button :icon="Refresh" @click="reset">重置</el-button><el-button link @click="advancedOpen=!advancedOpen">{{ advancedOpen ? '收起筛选' : '展开筛选' }}</el-button><span class="toolbar-spacer" /><span class="record-count">共 {{ total }} 条</span></div>
      <div v-if="advancedOpen && isProjects" class="filter-grid project-filter-grid">
        <el-input v-model="filters.regulatoryOrganization" clearable placeholder="监管单位" />
        <el-select v-model="filters.statuses" multiple collapse-tags clearable placeholder="工程状态"><el-option v-for="item in ['DRAFT','ACTIVE','SUSPENDED','COMPLETED','CLOSED']" :key="item" :label="labels[item]" :value="item" /></el-select>
        <el-select v-model="filters.regulatoryLinked" clearable placeholder="关联监督工程"><el-option label="已关联" :value="true" /><el-option label="未关联" :value="false" /></el-select>
        <el-select v-model="filters.projectTeamId" clearable filterable placeholder="所属项目组"><el-option v-for="item in projectTeams" :key="item.id" :label="item.name" :value="item.id" /></el-select>
        <el-input v-model="filters.number" clearable placeholder="工程编号" /><el-input v-model="filters.pileSerialNumber" clearable placeholder="桩基流水号" />
        <el-input v-model="filters.name" clearable placeholder="工程名称" /><el-input v-model="filters.entrustingName" clearable placeholder="委托单位" />
        <el-input v-model="filters.ownerName" clearable placeholder="建设单位" /><el-input v-model="filters.supervisionName" clearable placeholder="监理单位" /><el-input v-model="filters.constructionName" clearable placeholder="施工单位" />
      </div>
      <div v-if="advancedOpen && !isProjects" class="filter-grid customer-filter-grid">
        <el-select v-model="filters.unitType" clearable placeholder="单位类型"><el-option v-for="item in ['OWNER','ENTRUSTING','SUPERVISION','CONSTRUCTION','SAMPLING','DESIGN','SURVEY','GENERAL_CONTRACTOR','OTHER']" :key="item" :label="labels[item]" :value="item" /></el-select>
        <el-input v-model="filters.code" clearable placeholder="单位自编号" /><el-input v-model="filters.customerName" clearable placeholder="单位名称" />
        <el-input v-model="filters.settlementName" clearable placeholder="结算人" /><el-input v-model="filters.settlementPhone" clearable placeholder="结算人手机" />
        <el-input v-model="filters.contactName" clearable placeholder="联系人" /><el-input v-model="filters.contactPhone" clearable placeholder="联系人手机" />
        <el-select v-model="filters.active" clearable placeholder="启停状态"><el-option label="启用" :value="true" /><el-option label="停用" :value="false" /></el-select>
      </div>
    </section>

    <section class="directory-toolbar">
      <el-button v-if="canWrite" :icon="Plus" @click="openCreate">添加</el-button>
      <el-button v-if="canWrite" :icon="Edit" :disabled="!selected" @click="openEdit()">修改</el-button>
      <template v-if="isProjects">
        <el-button :icon="Tickets" :disabled="!selected" @click="openPlans">检测计划</el-button>
        <el-button :icon="Connection" :disabled="!selected" @click="openProgress">进度标记</el-button>
        <el-button :icon="MoreFilled" :disabled="!selected" @click="openParts">单体部位管理</el-button>
        <el-button :icon="Location" :disabled="!selected" @click="openLocation">定位</el-button>
      </template>
      <template v-else><el-button :icon="UserFilled" :disabled="!selected" @click="openEdit()">添加单位人员</el-button><el-button v-if="isAdmin" :icon="Connection" :disabled="!selected" @click="openAccounts">关联账号</el-button></template>
      <span class="toolbar-spacer" />
      <el-dropdown v-if="selected && canTransition" @command="transition(selected, String($event))"><el-button>状态操作<el-icon class="el-icon--right"><MoreFilled /></el-icon></el-button><template #dropdown><el-dropdown-menu><template v-if="isProjects"><el-dropdown-item v-for="action in (selected.status === 'DRAFT' ? ['SUBMIT','CLOSE'] : selected.status === 'ACTIVE' ? ['SUSPEND','COMPLETE','CLOSE'] : selected.status === 'SUSPENDED' ? ['ACTIVATE','CLOSE'] : selected.status === 'COMPLETED' ? ['REOPEN','CLOSE'] : [])" :key="action" :command="action">{{ actionLabels[action] }}</el-dropdown-item></template><template v-else><el-dropdown-item :command="selected.active ? 'DEACTIVATE' : 'ACTIVATE'">{{ selected.active ? '停用' : '启用' }}</el-dropdown-item></template></el-dropdown-menu></template></el-dropdown>
    </section>

    <el-table v-if="isProjects" v-loading="loading" :data="rows" border stripe highlight-current-row scrollbar-always-on class="directory-table viewport-table" @selection-change="selectedRows=$event.slice(-1)">
      <el-table-column type="selection" width="46" /><el-table-column prop="number" label="工程自编号" width="150" sortable /><el-table-column prop="pileSerialNumber" label="桩基流水号" width="130" />
      <el-table-column prop="name" label="工程名称" min-width="230" show-overflow-tooltip /><el-table-column prop="customerName" label="委托单位" min-width="190" show-overflow-tooltip />
      <el-table-column prop="ownerName" label="建设单位" min-width="180" show-overflow-tooltip /><el-table-column prop="supervisionName" label="监理单位" min-width="170" show-overflow-tooltip /><el-table-column prop="constructionName" label="施工单位" min-width="170" show-overflow-tooltip />
      <el-table-column prop="handledOn" label="经办日期" width="112"><template #default="scope">{{ formatDate(scope.row.handledOn) }}</template></el-table-column><el-table-column label="工程进度" width="112"><template #default="scope"><el-progress :percentage="Number(scope.row.progressPercent)" :stroke-width="6" /></template></el-table-column>
      <el-table-column label="工程状态" width="120"><template #default="scope"><el-tag :type="typeOfStatus(scope.row.status)">{{ labels[scope.row.status] }}</el-tag></template></el-table-column><el-table-column prop="projectTeamName" label="项目组" min-width="150" /><el-table-column label="关联监督" width="100"><template #default="scope">{{ scope.row.regulatoryLinked ? '是' : '否' }}</template></el-table-column><el-table-column prop="externalContractNumber" label="关联合同" width="150"><template #default="scope">{{ scope.row.externalContractNumber || '未绑定合同' }}</template></el-table-column>
    </el-table>
    <el-table v-else v-loading="loading" :data="rows" border stripe highlight-current-row scrollbar-always-on class="directory-table viewport-table" @selection-change="selectedRows=$event.slice(-1)">
      <el-table-column type="selection" width="46" /><el-table-column prop="code" label="单位自编号" width="140" /><el-table-column prop="unitType" label="单位类型" width="120"><template #default="scope">{{ labels[scope.row.unitType] }}</template></el-table-column><el-table-column prop="name" label="单位全称" min-width="250" show-overflow-tooltip />
      <el-table-column prop="settlementName" label="结算人" width="120" /><el-table-column prop="settlementPhone" label="结算人手机" width="145" /><el-table-column prop="contactName" label="联系人" width="120" /><el-table-column prop="contactPhone" label="联系人手机" width="145" />
      <el-table-column prop="commissionedProjectCount" label="委托工程数" width="105" /><el-table-column prop="participatingProjectCount" label="参建工程数" width="105" /><el-table-column prop="accountCount" label="关联账号" width="95" /><el-table-column label="状态" width="90" align="center"><template #default="scope"><el-tag :type="scope.row.active ? 'success' : 'info'">{{ scope.row.active ? '启用' : '停用' }}</el-tag></template></el-table-column>
    </el-table>
    <footer class="cursor-footer"><span>当前 {{ rows.length }} 条 / 共 {{ total }} 条</span><div><el-button :disabled="!cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="!nextCursor" @click="nextPage">下一页</el-button></div></footer>

    <el-drawer v-model="projectDrawer" :title="`${editing ? '编辑' : '添加'}工程`" size="min(980px, 94vw)" class="project-drawer">
      <el-tabs v-model="projectTab">
        <el-tab-pane label="基本信息" name="basic"><el-form label-position="top">
          <h3 class="form-section-title">工程基本信息</h3><div class="form-grid three"><el-form-item label="工程自编号（检测单位内部工程编号）" required><div class="number-field"><el-input v-model="projectForm.number" maxlength="64" /><el-button v-if="!editing" :loading="saving" @click="generateProjectNumber">生成编号</el-button></div></el-form-item><el-form-item label="工程名称（可填写至标段）" required><el-input v-model="projectForm.name" maxlength="240" /></el-form-item><el-form-item label="桩基流水号"><el-input v-model="projectForm.pileSerialNumber" maxlength="80" /></el-form-item></div>
          <div class="form-grid three"><el-form-item label="工程类型"><el-select v-model="projectForm.projectType" clearable filterable placeholder="请选择已发布工程类型"><el-option v-for="item in projectOptions.projectTypes" :key="item" :label="item" :value="item" /></el-select></el-form-item><el-form-item label="工程等级"><el-select v-model="projectForm.projectGrade" clearable placeholder="请选择已发布工程等级"><el-option v-for="item in projectOptions.projectGrades" :key="item" :label="item" :value="item" /></el-select></el-form-item><el-form-item label="经办日期"><el-date-picker v-model="projectForm.handledOn" value-format="YYYY-MM-DD" /></el-form-item></div>
          <h3 class="form-section-title">监管关联</h3><div class="form-grid three"><el-form-item label="监管单位"><el-input v-model="projectForm.regulatoryOrganization" /></el-form-item><el-form-item label="监管项目编码"><el-input v-model="projectForm.regulatoryProjectCode" /></el-form-item><el-form-item label="监管备案项目名称"><el-input v-model="projectForm.regulatoryProjectName" /></el-form-item></div><el-switch v-model="projectForm.regulatoryLinked" active-text="已关联监督工程" inactive-text="未关联" />
          <h3 class="form-section-title">委托与抽样</h3><div class="manual-choice"><el-switch v-model="projectForm.entrustingManual" active-text="手工输入委托单位" inactive-text="从客户库选择委托单位" /><span>手工单位仍需绑定归属客户，用于结算、数据权限和后续委托闭环。</span></div><div class="form-grid three"><el-form-item v-if="projectForm.entrustingManual" label="手工委托单位名称" required><el-input v-model="projectForm.entrustingManualName" maxlength="240" /></el-form-item><el-form-item :label="projectForm.entrustingManual ? '归属客户（结算与权限）' : '委托单位'" required><el-select v-model="projectForm.customerId" filterable remote reserve-keyword :remote-method="searchCustomerOptions" :loading="customerLoading" @visible-change="handleCustomerVisibleChange"><el-option v-for="item in customers" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item><el-form-item label="委托人姓名"><el-input v-model="projectForm.entrusterName" /></el-form-item><el-form-item label="委托人手机"><el-input v-model="projectForm.entrusterPhone" /></el-form-item></div><div class="form-grid three"><el-form-item label="结算人"><el-input v-model="projectForm.settlementContactName" /></el-form-item><el-form-item label="结算人联系电话"><el-input v-model="projectForm.settlementContactPhone" /></el-form-item><el-form-item label="抽样单位录入方式"><el-switch v-model="projectForm.samplingManual" active-text="手工输入" inactive-text="客户库选择" /></el-form-item></div><div class="form-grid three"><el-form-item label="抽样单位"><el-input v-if="projectForm.samplingManual" v-model="projectForm.samplingUnitName" /><el-select v-else v-model="projectForm.samplingCustomerId" filterable remote reserve-keyword clearable :remote-method="searchCustomerOptions" :loading="customerLoading" @visible-change="handleCustomerVisibleChange"><el-option v-for="item in customers" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item><el-form-item label="抽样人姓名"><el-input v-model="projectForm.samplerName" /></el-form-item><el-form-item label="抽样人手机"><el-input v-model="projectForm.samplerPhone" /></el-form-item></div><div class="form-grid three"><el-form-item label="抽样人证号"><el-input v-model="projectForm.samplerCertificateNumber" /></el-form-item></div>
          <h3 class="form-section-title">位置与关联</h3><div class="form-grid three"><el-form-item label="省"><el-select v-model="projectForm.province" clearable filterable :loading="divisionLoading" placeholder="请选择省" @change="changeProvince"><el-option v-for="item in provinces" :key="item.name" :label="item.name" :value="item.name" /></el-select></el-form-item><el-form-item label="市"><el-select v-model="projectForm.city" clearable filterable :loading="divisionLoading" :disabled="!projectForm.province" placeholder="请选择市" @change="changeCity"><el-option v-for="item in cities" :key="item.name" :label="item.name" :value="item.name" /></el-select></el-form-item><el-form-item label="区县"><el-select v-model="projectForm.district" clearable filterable :loading="divisionLoading" :disabled="!projectForm.city || !districts.length" placeholder="请选择区"><el-option v-for="item in districts" :key="item.name" :label="item.name" :value="item.name" /></el-select></el-form-item></div><el-form-item label="详细工程地址"><el-input v-model="projectForm.detailedAddress" maxlength="500" /></el-form-item><div class="form-grid three"><el-form-item label="标段号"><el-input v-model="projectForm.sectionNumber" /></el-form-item><el-form-item label="地区代号"><el-input v-model="projectForm.regionCode" /></el-form-item><el-form-item label="外部系统合同编号"><el-input v-model="projectForm.externalContractNumber" /></el-form-item></div><div class="form-grid"><el-form-item label="默认检测类别"><el-select v-model="projectForm.defaultDetectionCategory" clearable filterable placeholder="请选择已发布检测类别"><el-option v-for="item in projectOptions.defaultDetectionCategories" :key="item" :label="item" :value="item" /></el-select></el-form-item><el-form-item label="归属项目组"><el-select v-model="projectForm.projectTeamId" clearable filterable><el-option v-for="item in projectTeams" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item></div><el-form-item label="备注"><el-input v-model="projectForm.remark" type="textarea" :rows="3" maxlength="2000" /></el-form-item>
        </el-form></el-tab-pane>
        <el-tab-pane label="参建单位" name="parties"><el-form label-position="top"><h3 class="form-section-title">参建单位</h3><div class="party-block"><div class="manual-choice"><el-switch v-model="projectForm.ownerManual" active-text="手工输入建设单位" inactive-text="从客户库选择建设单位" /></div><div class="form-grid"><el-form-item label="建设单位" required><el-input v-if="projectForm.ownerManual" v-model="projectForm.ownerName" /><el-select v-else v-model="projectForm.ownerCustomerId" filterable remote reserve-keyword :remote-method="searchCustomerOptions" :loading="customerLoading" @visible-change="handleCustomerVisibleChange"><el-option v-for="item in customers" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item><el-form-item label="报告权限"><el-switch v-model="projectForm.ownerReportAccess" active-text="允许查看报告" /></el-form-item></div></div><div class="party-block"><div class="manual-choice"><el-switch v-model="projectForm.supervisionManual" active-text="手工输入监理单位" inactive-text="从客户库选择监理单位" /></div><div class="form-grid three"><el-form-item label="监理单位" required><el-input v-if="projectForm.supervisionManual" v-model="projectForm.supervisionName" /><el-select v-else v-model="projectForm.supervisionCustomerId" filterable remote reserve-keyword :remote-method="searchCustomerOptions" :loading="customerLoading" @visible-change="handleCustomerVisibleChange"><el-option v-for="item in customers" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item><el-form-item label="见证人姓名"><el-input v-model="projectForm.witnessName" /></el-form-item><el-form-item label="见证人手机"><el-input v-model="projectForm.witnessPhone" /></el-form-item></div><el-switch v-model="projectForm.supervisionReportAccess" active-text="允许监理单位查看报告" /></div><div class="party-block"><div class="manual-choice"><el-switch v-model="projectForm.constructionManual" active-text="手工输入施工单位" inactive-text="从客户库选择施工单位" /></div><div class="form-grid"><el-form-item label="施工单位" required><el-input v-if="projectForm.constructionManual" v-model="projectForm.constructionName" /><el-select v-else v-model="projectForm.constructionCustomerId" filterable remote reserve-keyword :remote-method="searchCustomerOptions" :loading="customerLoading" @visible-change="handleCustomerVisibleChange"><el-option v-for="item in customers" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item><el-form-item label="报告权限"><el-switch v-model="projectForm.constructionReportAccess" active-text="允许施工单位查看报告" /></el-form-item></div></div><div class="form-grid three"><el-form-item label="勘察单位"><el-input v-model="projectForm.surveyName" /></el-form-item><el-form-item label="设计单位"><el-input v-model="projectForm.designName" /></el-form-item><el-form-item label="总承包单位"><el-input v-model="projectForm.generalContractorName" /></el-form-item></div></el-form></el-tab-pane>
        <el-tab-pane label="扩展控制" name="extension"><el-form label-position="top"><div class="form-grid three"><el-form-item label="打印 CMA 章"><el-select v-model="projectForm.cmaSealPolicy"><el-option v-for="item in ['FOLLOW_PROJECT','ALWAYS','NEVER']" :key="item" :label="labels[item]" :value="item" /></el-select></el-form-item><el-form-item label="打印资质章"><el-select v-model="projectForm.qualificationSealPolicy"><el-option v-for="item in ['FOLLOW_PROJECT','ALWAYS','NEVER']" :key="item" :label="labels[item]" :value="item" /></el-select></el-form-item><el-form-item label="使用电子签章"><el-select v-model="projectForm.electronicSealPolicy"><el-option v-for="item in ['FOLLOW_PROJECT','ALWAYS','NEVER']" :key="item" :label="labels[item]" :value="item" /></el-select></el-form-item></div><div class="form-grid"><el-form-item label="委托单位报告权限"><el-switch v-model="projectForm.customerReportAccess" active-text="允许查看报告" /></el-form-item><el-form-item label="委托单位打印报告"><el-switch v-model="projectForm.customerPrintAllowed" active-text="允许打印" /></el-form-item></div><div class="form-grid three"><el-form-item label="允许打印次数"><el-input-number v-model="projectForm.customerPrintLimit" :min="0" :disabled="!projectForm.customerPrintAllowed" /></el-form-item><el-form-item label="结算方式"><el-input v-model="projectForm.settlementMethod" /></el-form-item><el-form-item label="限制打印报告"><el-switch v-model="projectForm.reportPrintRestricted" /></el-form-item></div></el-form></el-tab-pane>
      </el-tabs><template #footer><el-button @click="projectDrawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveProject()">保存工程信息</el-button></template>
    </el-drawer>

    <el-drawer v-model="customerDrawer" :title="`${editing ? '编辑' : '添加'}客户`" size="min(940px, 94vw)"><el-form label-position="top"><h3 class="form-section-title">客户基础信息</h3><div class="form-grid three"><el-form-item label="单位简称" required><el-input v-model="customerForm.shortName" /></el-form-item><el-form-item label="单位全称" required><el-input v-model="customerForm.name" /></el-form-item><el-form-item label="单位自编号" required><el-input v-model="customerForm.code" /></el-form-item></div><div class="form-grid three"><el-form-item label="单位类别" required><el-select v-model="customerForm.unitType"><el-option v-for="item in ['OWNER','ENTRUSTING','SUPERVISION','CONSTRUCTION','SAMPLING','DESIGN','SURVEY','GENERAL_CONTRACTOR','OTHER']" :key="item" :label="labels[item]" :value="item" /></el-select></el-form-item><el-form-item label="统一社会信用代码"><el-input v-model="customerForm.unifiedCreditCode" /></el-form-item><el-form-item label="是否为委托单位"><el-switch v-model="customerForm.commissioningUnit" /></el-form-item></div><div class="form-grid three"><el-form-item label="联系人"><el-input v-model="customerForm.contactName" /></el-form-item><el-form-item label="联系方式"><el-input v-model="customerForm.contactPhone" /></el-form-item><el-form-item label="单位固定电话"><el-input v-model="customerForm.landline" /></el-form-item></div><div class="form-grid three"><el-form-item label="联系地址"><el-input v-model="customerForm.address" /></el-form-item><el-form-item label="传真"><el-input v-model="customerForm.fax" /></el-form-item><el-form-item label="电子邮箱"><el-input v-model="customerForm.email" /></el-form-item></div><el-form-item label="备注"><el-input v-model="customerForm.remark" type="textarea" :rows="2" /></el-form-item>
      <div class="subresource-title"><h3>结算人员信息</h3><el-button :icon="Plus" @click="addSettlementContact">添加</el-button></div><div v-for="(item,index) in settlementContacts" :key="item.id ?? index" class="inline-editor"><el-checkbox v-model="item.otherContact" @change="item._dirty=true">其他结算人</el-checkbox><el-input v-model="item.name" placeholder="结算人姓名" @input="item._dirty=true" /><el-input v-model="item.phone" placeholder="手机号码" @input="item._dirty=true" /><el-checkbox v-model="item.primaryContact" @change="item._dirty=true">主要结算人</el-checkbox><el-switch v-model="item.active" @change="item._dirty=true" /></div>
      <div class="subresource-title"><h3>客户人员信息</h3><el-button :icon="Plus" @click="addCustomerPerson">添加</el-button></div><el-table :data="customerPeople" border size="small"><el-table-column label="姓名" min-width="130"><template #default="scope"><el-input v-model="scope.row.name" @input="scope.row._dirty=true" /></template></el-table-column><el-table-column label="性别" width="105"><template #default="scope"><el-select v-model="scope.row.gender" @change="scope.row._dirty=true"><el-option v-for="item in ['UNKNOWN','MALE','FEMALE']" :key="item" :label="labels[item]" :value="item" /></el-select></template></el-table-column><el-table-column label="人员类型" width="150"><template #default="scope"><el-select v-model="scope.row.personType" @change="scope.row._dirty=true"><el-option v-for="item in ['CONTACT','ENTRUSTER','SAMPLER','WITNESS','SETTLEMENT','LEGAL_REPRESENTATIVE','OTHER']" :key="item" :label="labels[item]" :value="item" /></el-select></template></el-table-column><el-table-column label="手机号码" width="150"><template #default="scope"><el-input v-model="scope.row.phone" @input="scope.row._dirty=true" /></template></el-table-column><el-table-column label="证书编号" min-width="150"><template #default="scope"><el-input v-model="scope.row.certificateNumber" @input="scope.row._dirty=true" /></template></el-table-column><el-table-column label="头像" width="130"><template #default="scope"><label class="file-picker compact"><input type="file" accept="image/png,image/jpeg" @change="uploadAvatar($event,scope.row)" /><el-icon><Upload /></el-icon>{{ scope.row.avatarFileName || (scope.row.avatarFileId ? '已上传' : '上传') }}</label></template></el-table-column><el-table-column label="启用" width="70" align="center"><template #default="scope"><el-switch v-model="scope.row.active" @change="scope.row._dirty=true" /></template></el-table-column></el-table>
    </el-form><template #footer><el-button @click="customerDrawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveCustomer">保存</el-button></template></el-drawer>

    <el-dialog v-model="planDialog" :title="`${selected?.number ?? ''} · 工程量与检测计划`" width="min(1180px, 96vw)" top="4vh"><div class="dialog-toolbar"><el-input v-model="planQuery" clearable :prefix-icon="Search" placeholder="检测类别、项目、参数或方法" @keyup.enter="loadPlans" /><el-button @click="loadPlans">查询</el-button><span class="toolbar-spacer" /><el-button v-if="canWrite" type="primary" :icon="Plus" @click="openPlanEditor()">新增计划</el-button></div><el-table :data="plans" border height="520" scrollbar-always-on><el-table-column type="index" label="序号" width="60" /><el-table-column prop="categoryName" label="检测类别" width="150" show-overflow-tooltip /><el-table-column prop="projectName" label="检测项目" min-width="170" /><el-table-column prop="parameterName" label="检测参数" min-width="170" /><el-table-column prop="plannedQuantity" label="计划量" width="90" /><el-table-column prop="unit" label="单位" width="70" /><el-table-column prop="bindingParameter" label="绑定检测参数" min-width="170" /><el-table-column prop="detectionMethod" label="检测方法" min-width="160" /><el-table-column prop="unitPrice" label="单价(元)" width="90" /><el-table-column prop="totalPrice" label="总价(元)" width="100" /><el-table-column prop="completedQuantity" label="完成量" width="90" /><el-table-column prop="completionPercent" label="完成比例" width="105"><template #default="scope">{{ scope.row.completionPercent }}%</template></el-table-column><el-table-column prop="completedContractPrice" label="完成合同价" width="120" /><el-table-column label="滞后情况" width="100"><template #default="scope"><el-tag :type="typeOfStatus(scope.row.lagStatus)">{{ labels[scope.row.lagStatus] }}</el-tag></template></el-table-column><el-table-column v-if="canWrite" label="操作" fixed="right" width="80" align="center"><template #default="scope"><el-button link type="primary" @click="openPlanEditor(scope.row)">编辑</el-button></template></el-table-column></el-table></el-dialog>
    <el-dialog v-model="planEditor" :title="planForm.id ? '编辑检测计划' : '新增检测计划'" width="760px"><el-form label-position="top"><div class="form-grid"><el-form-item label="绑定检测项目"><el-select v-model="planForm.detectionProjectRevisionId" clearable filterable @change="loadPlanRevision"><el-option v-for="item in flatCatalog" :key="item.projectRevisionId" :label="item.path" :value="item.projectRevisionId" /></el-select></el-form-item><el-form-item label="绑定检测参数"><el-select v-model="planForm.parameterId" clearable filterable @change="choosePlanParameter"><el-option v-for="item in planParameters" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item></div><div class="form-grid"><el-form-item label="检测项目" required><el-input v-model="planForm.projectName" /></el-form-item><el-form-item label="检测参数" required><el-input v-model="planForm.parameterName" /></el-form-item></div><div class="form-grid three"><el-form-item label="计划量" required><el-input v-model="planForm.plannedQuantity" /></el-form-item><el-form-item label="单位" required><el-input v-model="planForm.unit" /></el-form-item><el-form-item label="单价(元)"><el-input v-model="planForm.unitPrice" /></el-form-item></div><div class="form-grid"><el-form-item label="检测方法"><el-input v-model="planForm.detectionMethod" /></el-form-item><el-form-item label="计划完成日期"><el-date-picker v-model="planForm.plannedCompletionOn" value-format="YYYY-MM-DD" /></el-form-item></div></el-form><template #footer><el-button @click="planEditor=false">取消</el-button><el-button type="primary" :loading="saving" @click="savePlan">保存</el-button></template></el-dialog>

    <el-dialog v-model="progressDialog" title="工程进度标记" width="720px"><el-form label-position="top"><div class="form-grid three"><el-form-item label="完成比例"><el-input-number v-model="progressForm.progressPercent" :min="0" :max="100" :precision="2" /></el-form-item><el-form-item label="进度说明" required><el-input v-model="progressForm.progressLabel" /></el-form-item><el-form-item label="标记日期"><el-date-picker v-model="progressForm.occurredOn" value-format="YYYY-MM-DD" /></el-form-item></div><el-form-item label="备注"><el-input v-model="progressForm.comment" type="textarea" :rows="2" /></el-form-item></el-form><el-timeline class="progress-timeline"><el-timeline-item v-for="item in progressMarks" :key="item.id" :timestamp="`${item.occurredOn} · ${item.createdBy}`" placement="top"><strong>{{ item.progressPercent }}% · {{ item.progressLabel }}</strong><p>{{ item.comment || '无备注' }}</p></el-timeline-item></el-timeline><template #footer><el-button @click="progressDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveProgress">保存进度</el-button></template></el-dialog>

    <el-dialog v-model="partsDialog" title="单体部位管理" width="820px"><div class="parts-layout"><el-tree :data="partTree" node-key="id" default-expand-all :props="{ label:'name', children:'children' }"><template #default="{ data }"><span class="part-node"><span><code>{{ data.code }}</code>{{ data.name }}<el-tag v-if="!data.active" size="small" type="info">停用</el-tag></span><el-button v-if="canWrite" link type="primary" @click.stop="openPartEditor(data)">编辑</el-button></span></template></el-tree><aside><el-button v-if="canWrite" type="primary" :icon="Plus" @click="openPartEditor()">新增部位</el-button><p>支持多级单体、分部与部位树；移动时自动阻止循环层级。</p></aside></div></el-dialog>
    <el-dialog v-model="partEditor" :title="partForm.id ? '编辑单体部位' : '新增单体部位'" width="560px"><el-form label-position="top"><div class="form-grid"><el-form-item label="部位代码" required><el-input v-model="partForm.code" /></el-form-item><el-form-item label="部位名称" required><el-input v-model="partForm.name" /></el-form-item></div><el-form-item label="上级部位"><el-tree-select v-model="partForm.parentId" :data="partParentTree" node-key="id" :props="{ label:'name',children:'children' }" clearable check-strictly /></el-form-item><div class="form-grid"><el-form-item label="排序"><el-input-number v-model="partForm.sortOrder" :min="0" /></el-form-item><el-form-item label="状态"><el-switch v-model="partForm.active" active-text="启用" inactive-text="停用" /></el-form-item></div></el-form><template #footer><el-button @click="partEditor=false">取消</el-button><el-button type="primary" :loading="saving" @click="savePart">保存</el-button></template></el-dialog>

    <el-dialog v-model="locationDialog" title="工程定位" width="680px"><el-alert title="坐标与工程地址受版本控制；坐标仅作内部定位，不向外部地图平台自动传输。" type="info" :closable="false" /><el-form label-position="top"><el-form-item label="详细工程地址"><el-input v-model="projectForm.detailedAddress" /></el-form-item><div class="form-grid"><el-form-item label="纬度（-90 至 90）"><el-input v-model="projectForm.latitude" /></el-form-item><el-form-item label="经度（-180 至 180）"><el-input v-model="projectForm.longitude" /></el-form-item></div></el-form><template #footer><el-button @click="locationDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveLocation">保存定位</el-button></template></el-dialog>

    <el-dialog v-model="accountDialog" title="客户关联账号" width="760px"><div class="account-link-form"><el-select v-model="accountForm.userId" filterable placeholder="选择账号"><el-option v-for="item in accountUsers" :key="item.id" :label="`${item.username} · ${item.displayName}`" :value="item.id" /></el-select><el-select v-model="accountForm.relationship"><el-option v-for="item in ['ADMIN','MEMBER','REPORT_VIEWER']" :key="item" :label="labels[item]" :value="item" /></el-select><el-button type="primary" @click="linkAccount">关联</el-button></div><el-table :data="customerAccounts" border><el-table-column prop="username" label="账号" /><el-table-column prop="displayName" label="姓名" /><el-table-column label="关系"><template #default="scope">{{ labels[scope.row.relationship] }}</template></el-table-column><el-table-column label="操作" width="90" align="center"><template #default="scope"><el-button link type="danger" @click="unlinkAccount(scope.row)">解除</el-button></template></el-table-column></el-table></el-dialog>
  </section>
</template>

<style scoped>
.business-directory-page { min-width: 0; }
.heading-actions,.filter-primary,.directory-toolbar,.dialog-toolbar,.account-link-form { display:flex;align-items:center;gap:10px; }
.directory-filters { background:#fff;border:1px solid var(--line);border-radius:10px 10px 0 0; }
.filter-primary { padding:13px 14px; }.filter-primary .el-input { max-width:430px; }
.filter-grid { display:grid;gap:10px;padding:0 14px 14px;border-top:1px solid #eef2f7;padding-top:12px; }
.project-filter-grid { grid-template-columns:repeat(4,minmax(150px,1fr)); }.customer-filter-grid { grid-template-columns:repeat(4,minmax(160px,1fr)); }
.directory-toolbar { flex-wrap:wrap;padding:10px 12px;background:#f8fafc;border:1px solid var(--line);border-top:0; }
.directory-table { width:100%;font-size: 13px;--el-table-header-bg-color:#f5f8fc; }
.directory-table.viewport-table { flex:1 1 0;min-height:0;height:0; }
.cursor-footer { display:flex;justify-content:space-between;align-items:center;padding:11px 14px;background:#fff;border:1px solid var(--line);border-top:0;border-radius:0 0 10px 10px;color:var(--muted);font-size:13px; }
.form-grid.three { grid-template-columns:repeat(3,minmax(0,1fr)); }.form-grid .el-select,.form-grid .el-date-editor { width:100%; }
.form-section-title { margin:16px 0 12px;padding-left:9px;border-left:3px solid var(--brand-600);font-size:15px; }
.party-block { padding:12px;margin-bottom:10px;border:1px solid #e8edf4;border-radius:9px;background:#fbfcfe; }
.subresource-title { display:flex;align-items:center;justify-content:space-between;margin:18px 0 10px; }.subresource-title h3 { margin:0;font-size:15px; }
.inline-editor { display:grid;grid-template-columns:130px 1fr 160px 120px 66px;gap:8px;align-items:center;padding:8px;border:1px solid #e9edf3;border-radius:8px;margin-bottom:8px; }
.file-picker.compact { min-height:30px;padding:4px 8px;font-size: 13px; }
.dialog-toolbar { margin-bottom:10px; }.dialog-toolbar .el-input { width:340px; }
.progress-timeline { max-height:300px;overflow:auto;margin-top:16px; }.progress-timeline p { margin:4px 0;color:var(--muted); }
.parts-layout { display:grid;grid-template-columns:minmax(0,1fr) 220px;gap:18px;min-height:360px; }.parts-layout .el-tree { padding:10px;border:1px solid var(--line);border-radius:9px; }.parts-layout aside { padding:14px;background:#f7f9fc;border-radius:9px;color:var(--muted);font-size:13px; }.part-node { display:flex;justify-content:space-between;align-items:center;width:100%;gap:12px; }.part-node code { margin-right:8px;color:#4d6483; }.part-node .el-tag { margin-left:8px; }
.account-link-form { margin-bottom:12px; }.account-link-form .el-select { flex:1; }
.number-field,.manual-choice { display:flex;align-items:center;gap:10px;width:100%; }.number-field .el-input { flex:1; }.manual-choice { margin:2px 0 12px;color:var(--muted);font-size: 13px; }
@media (max-width:1440px) { .project-filter-grid,.customer-filter-grid { grid-template-columns:repeat(3,minmax(150px,1fr)); }.directory-toolbar { align-items:flex-start; } }
@media (max-width:1180px) { .project-filter-grid,.customer-filter-grid { grid-template-columns:repeat(2,minmax(150px,1fr)); }.form-grid.three { grid-template-columns:1fr 1fr; }.inline-editor { grid-template-columns:120px 1fr 150px; }.inline-editor > :nth-child(4),.inline-editor > :nth-child(5) { justify-self:start; }.parts-layout { grid-template-columns:1fr; } }
</style>
