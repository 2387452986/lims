<script setup lang="ts">
import { Delete, Edit, Filter, Plus, Printer, Refresh, Search, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import {
  flattenCatalog, type CatalogNode, type DetectionParameter, type DetectionSampleField,
  type ProjectRevision,
} from '../detection'
import { useAuthStore } from '../stores/auth'
import { failureReasonLabel, jobTypeLabel, localDateString, statusLabel } from '../presentation'

interface ReferenceOption { id: string; code: string; label: string }
interface References { projects: ReferenceOption[]; customers: ReferenceOption[] }
interface LabeledOption { code: string; label: string }
interface CommissionOptions {
  projectProgress: LabeledOption[]; testingTypes: LabeledOption[]; testingStandards: LabeledOption[]
  testingPlaces: LabeledOption[]; sampleHandling: LabeledOption[]; reportDelivery: LabeledOption[]
}
interface CursorPage<T> { items: T[]; nextCursor?: string; hasMore: boolean; total: number }
interface StoredFile { id: string; name?: string; originalName?: string }
interface PersonDefault { name?: string; phone?: string; certificateNumber?: string }
interface ProjectDefaults {
  projectId: string; projectNumber: string; projectName: string; customerId: string; customerName: string
  constructionUnit?: string; witnessUnit?: string; samplingUnit?: string; entruster: PersonDefault
  witness: PersonDefault; sampler: PersonDefault; settlementContact?: string
  electronicSignature: boolean; cmaSeal: boolean; qualificationSeal: boolean; structureParts: LabeledOption[]
}
interface DepartmentOption { id: string; code: string; name: string; active: boolean }
interface RelationView { id: string; sourceCommissionId: string; sourceNumber: string; targetCommissionId: string; targetNumber: string; relationType: string; comment?: string; createdBy: string; createdAt: string }
interface TransferView { id: string; toDepartmentId: string; toDepartment: string; state: string; comment?: string; createdBy: string; version: number; updatedAt: string; allowedActions: string[] }
interface FlowEvent { id: string; occurredAt: string; actorName: string; action: string; resourceType: string; reasonCode?: string; comment?: string }
interface BackgroundJob { id: string; jobType: string; state: string; attemptCount: number; lastError?: string; resultFileId?: string; version: number; createdAt: string; completedAt?: string; allowedActions: string[] }
interface JobQueued { jobId: string; jobType: string; state: string }
interface ElectronicReadiness { ready: boolean; blockers: string[] }
interface ElectronicDocument { id: string; revision: number; templateVersionId: string; fileId: string; jobId: string; createdBy: string; createdAt: string }
interface PrintRestriction { commissionId: string; restricted: boolean; printLimit?: number; reason?: string; version: number }
interface SampleGroupPart { sampleGroupId: string; projectPartId: string; partName: string }
interface PartDraft { sampleGroupId: string; sampleGroupName: string; projectPartIds: string[] }
interface IntakeDetails {
  projectProgress: string; testingType: string; testingStandard: string; testingPlace: string
  sampleHandling: string; reportDelivery: string; reportDeliveryAddress: string; reportCopies: number
  projectPart: string; monomer: string; settlementContact: string; witnessUnit: string; witnessName: string
  witnessPhone: string; witnessCertificate: string; samplingUnit: string; samplingPerson: string
  samplingPhone: string; samplingCertificate: string; constructionUnit: string; needSampling: boolean
  electronicSignature: boolean; cmaSeal: boolean; qualificationSeal: boolean; externalCommission: boolean
  externalOrganization: string; externalNumber: string; sampleReceivingAddress: string
  expectedReportOn: string; remark: string; sourceRemark: string
}
interface CommissionSummary {
  rowId: string; id: string; sampleId?: string; sampleGroupId?: string; sampleNumber?: string
  sampleName?: string; sampleQuantity?: string; sampleUnit?: string
  number: string; precommissionNumber?: string; managementSequence: number; internalSequence?: number; externalNumber?: string
  projectId: string; projectNumber: string; projectName: string; customerId: string; customerName: string
  contactName: string; commissionedOn: string; dueOn: string; state: string; testingPlace: string
  externalCommission: boolean; detectionProjects: string; parameterCount: number; sampleGroupCount: number
  flowStatus: string; taskStatus: string; reportStatus: string; printStatus: string
  receiverName?: string; departmentName?: string; version: number; createdAt: string; updatedAt: string
}
interface CommissionDetail {
  id: string; number: string; precommissionNumber?: string; projectId: string; projectName: string
  customerId: string; customerName: string; contactName: string; contactPhone?: string
  commissionedOn: string; dueOn: string; state: string; version: number; selectionCount: number
  selectionFrozenAt?: string; updatedAt: string; details: IntakeDetails & {
    ageReportValues: string[]; attachments: StoredFile[]
  }; allowedActions: string[]
}
interface FrozenParameter {
  id: string; parameterId: string; code: string; name: string; unit?: string; required: boolean
  defaultCommissioned: boolean; sortOrder: number; sampleGroupId: string
}
interface FrozenSampleGroup {
  id: string; clientKey: string; name: string; description?: string; quantity: string; unit: string
  sortOrder: number; parameterIds: string[]; fieldValues: Record<string, string[]>
}
interface FrozenSelection {
  projectRevisionId: string; projectName: string; projectSymbol?: string; revisionNumber: number
  parameters: FrozenParameter[]; sampleGroups: FrozenSampleGroup[]
}
interface SampleGroupDraft {
  clientKey: string; name: string; description: string; quantity: string; unit: string
  parameterIds: string[]; fieldValues: Record<string, string[]>
}
interface SelectionDraft {
  projectRevisionId: string; projectName: string; path: string; revisionNumber: number
  parameters: DetectionParameter[]; parameterIds: string[]; sampleFields: DetectionSampleField[]
  ageReportOptions: ProjectRevision['ageReportOptions']; sampleGroups: SampleGroupDraft[]
}

const router = useRouter()
const auth = useAuthStore()
const treeProps = { value: 'id', label: 'name', children: 'children' }
const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)
const drawer = ref(false)
const filtersExpanded = ref(false)
const editing = ref<CommissionDetail>()
const rows = ref<CommissionSummary[]>([])
const checkedRows = ref<CommissionSummary[]>([])
const total = ref(0)
const pageSize = ref(20)
const pageNumber = ref(1)
const viewMode = ref<'COMMISSION' | 'SAMPLE'>('COMMISSION')
const departmentScope = ref<'DEPARTMENT' | 'ALL'>('DEPARTMENT')
const nextCursor = ref('')
const currentCursor = ref('')
const cursorStack = ref<string[]>([])
const references = ref<References>({ projects: [], customers: [] })
const commissionOptions = ref<CommissionOptions>({ projectProgress: [], testingTypes: [], testingStandards: [], testingPlaces: [], sampleHandling: [], reportDelivery: [] })
const projectParts = ref<LabeledOption[]>([])
const catalog = ref<CatalogNode[]>([])
const catalogQuery = ref('')
const catalogLoading = ref(false)
const selectionDrafts = ref<SelectionDraft[]>([])
const activeRevisionId = ref('')
const parameterQuery = ref('')
const attachments = ref<StoredFile[]>([])
const ageReportValues = ref<string[]>([])
const departments = ref<DepartmentOption[]>([])
const actionRow = ref<CommissionSummary>()
const relationDialog = ref(false)
const transferDialog = ref(false)
const internalSequenceDialog = ref(false)
const flowDialog = ref(false)
const samplePartsDialog = ref(false)
const printRestrictionDialog = ref(false)
const electronicDialog = ref(false)
const batchChangeDialog = ref(false)
const jobDialog = ref(false)
const internalSequenceDrafts = ref<Array<{ id: string; number: string; projectName: string; version: number; internalSequence?: number }>>([])
const relatedCandidates = ref<CommissionSummary[]>([])
const relations = ref<RelationView[]>([])
const transfers = ref<TransferView[]>([])
const flowEvents = ref<FlowEvent[]>([])
const flowNextCursor = ref('')
const flowHasMore = ref(false)
const flowTotal = ref(0)
const flowLoading = ref(false)
const samplePartDrafts = ref<PartDraft[]>([])
const partOptions = ref<LabeledOption[]>([])
const electronicReadiness = ref<ElectronicReadiness>({ ready: false, blockers: [] })
const electronicDocuments = ref<ElectronicDocument[]>([])
const currentJob = ref<BackgroundJob>()
const relationForm = reactive({ targetCommissionId: '', relationType: 'RELATED', comment: '' })
const transferForm = reactive({ toDepartmentId: '', comment: '' })
const printRestrictionForm = reactive({ restricted: false, printLimit: undefined as number | undefined, reason: '' })
const batchChangeForm = reactive({ requestType: 'OTHER', processingMethod: 'MANUAL_RECORD', reason: '', requestedSummary: '' })
const batchChangeTargets = ref<CommissionSummary[]>([])

const filters = reactive({
  q: '', commissionedFrom: '', commissionedTo: '', states: '', flowStates: '', taskStates: '', testingPlace: '',
  externalCommission: '' as '' | 'true' | 'false', detectionProject: '', commissionNumber: '',
  sampleNumber: '', precommissionNumber: '', customer: '', projectNumber: '', projectName: '',
  managementSequenceMin: undefined as number | undefined, managementSequenceMax: undefined as number | undefined,
  waitingFlow: false,
})
const form = reactive({ projectId: '', customerId: '', contactName: '', contactPhone: '', commissionedOn: '', dueOn: '' })
const details = reactive<IntakeDetails>({
  projectProgress: 'FOUNDATION_FACILITIES', testingType: 'PARALLEL_SUPERVISION', testingStandard: 'NATIONAL',
  testingPlace: 'LAB', sampleHandling: 'CLIENT_DISPOSE', reportDelivery: 'PICKUP', reportDeliveryAddress: '',
  reportCopies: 0, projectPart: '', monomer: '', settlementContact: '', witnessUnit: '', witnessName: '',
  witnessPhone: '', witnessCertificate: '', samplingUnit: '', samplingPerson: '', samplingPhone: '',
  samplingCertificate: '', constructionUnit: '', needSampling: false, electronicSignature: false, cmaSeal: false,
  qualificationSeal: false, externalCommission: false, externalOrganization: '', externalNumber: '',
  sampleReceivingAddress: '', expectedReportOn: '', remark: '', sourceRemark: '',
})

const activeSelection = computed(() => selectionDrafts.value.find((item) => item.projectRevisionId === activeRevisionId.value))
const visibleParameters = computed(() => {
  const needle = parameterQuery.value.trim().toLowerCase()
  return (activeSelection.value?.parameters ?? []).filter((item) => !needle
    || [item.name, item.code, item.unit, item.standards.map((entry) => entry.code).join(' ')]
      .some((value) => value?.toLowerCase().includes(needle)))
})
const flatCatalog = computed(() => flattenCatalog(catalog.value))
const availableAgeOptions = computed(() => {
  const values = new Map<string, string>()
  selectionDrafts.value.flatMap((item) => item.ageReportOptions).forEach((item) => values.set(item.value, item.label))
  return [...values].map(([code, label]) => ({ code, label }))
})
const canPrint = computed(() => auth.user?.permissions.includes('EXPORT_DATA'))

const stateLabel = (state: string) => ({ DRAFT: '草稿', PRE_ACCEPTED: '待受理', ACCEPTED: '已受理', SAMPLING: '样品阶段', ASSIGNED: '待检测', TESTING: '检测中', REPORTING: '报告阶段', ISSUED: '已签发', ARCHIVED: '已归档', REJECTED: '已拒绝', CANCELLED: '已作废' }[state] ?? state)
const taskLabel = (state: string) => ({ NOT_ASSIGNED: '未派发', UNASSIGNED: '待派发', ASSIGNED: '已派发', IN_PROGRESS: '检测中', RETURNED: '已退回', COMPLETED: '已完成' }[state] ?? state)
const reportLabel = (state: string) => ({ NOT_CREATED: '未编制', DRAFT: '草稿', IN_REVIEW: '审核中', RETURNED: '已退回', APPROVED: '已审核', ISSUED: '已签发', DISTRIBUTED: '已发放', ARCHIVED: '已归档', VOID: '已作废' }[state] ?? state)
const flowLabel = (state: string) => ({ PENDING: '待流转', TRANSFERRED: '已流转' }[state] ?? state)
const printLabel = (state: string) => ({ NOT_PRINTED: '未打印', COMMISSION_PRINTED: '委托单已打印', SAMPLE_LABEL_PRINTED: '标签已打印', ALL_PRINTED: '全部已打印' }[state] ?? state)
const stateType = (state: string) => state === 'ARCHIVED' ? 'success' : ['REJECTED', 'CANCELLED'].includes(state) ? 'info' : ['TESTING', 'REPORTING', 'ISSUED'].includes(state) ? 'primary' : 'warning'
const selectable = (parameter: DetectionParameter) => parameter.status === 'PUBLISHED' && parameter.enabled
const isSelected = (parameterId: string) => activeSelection.value?.parameterIds.includes(parameterId) ?? false
const groupsForParameter = (parameterId: string) => activeSelection.value?.sampleGroups.filter((group) => group.parameterIds.includes(parameterId)).map((group) => group.clientKey) ?? []

function listQuery() {
  const params = new URLSearchParams({ limit: String(pageSize.value), viewMode: viewMode.value })
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== '' && value !== undefined && value !== false) params.set(key, String(value))
  })
  if (departmentScope.value === 'DEPARTMENT' && auth.user?.departmentId) params.set('departmentId', auth.user.departmentId)
  if (currentCursor.value) params.set('cursor', currentCursor.value)
  return params.toString()
}

async function load(reset = false) {
  if (reset) { currentCursor.value = ''; cursorStack.value = []; pageNumber.value = 1 }
  loading.value = true
  try {
    const page = await api<CursorPage<CommissionSummary>>(`/api/commissions?${listQuery()}`)
    rows.value = page.items; total.value = page.total; nextCursor.value = page.nextCursor ?? ''
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { loading.value = false }
}

async function nextPage() {
  if (!nextCursor.value) return
  cursorStack.value.push(currentCursor.value); currentCursor.value = nextCursor.value; pageNumber.value += 1
  await load()
}
async function previousPage() {
  if (!cursorStack.value.length) return
  currentCursor.value = cursorStack.value.pop() ?? ''; pageNumber.value = Math.max(1, pageNumber.value - 1)
  await load()
}

function resetFilters() {
  Object.assign(filters, { q: '', commissionedFrom: '', commissionedTo: '', states: '', flowStates: '', taskStates: '', testingPlace: '', externalCommission: '', detectionProject: '', commissionNumber: '', sampleNumber: '', precommissionNumber: '', customer: '', projectNumber: '', projectName: '', managementSequenceMin: undefined, managementSequenceMax: undefined, waitingFlow: false })
  departmentScope.value = 'DEPARTMENT'
  load(true)
}

async function loadCatalog() {
  catalogLoading.value = true
  try { catalog.value = await api<CatalogNode[]>(`/api/detection-catalog/tree?query=${encodeURIComponent(catalogQuery.value)}&includeInactive=false&includeHistory=false`) }
  catch (error) { ElMessage.error(errorMessage(error)) }
  finally { catalogLoading.value = false }
}

function resetForm() {
  const now = new Date(); const due = new Date(now); due.setDate(due.getDate() + 15)
  Object.assign(form, { projectId: '', customerId: '', contactName: '', contactPhone: '', commissionedOn: localDateString(now), dueOn: localDateString(due) })
  Object.assign(details, { projectProgress: 'FOUNDATION_FACILITIES', testingType: 'PARALLEL_SUPERVISION', testingStandard: 'NATIONAL', testingPlace: 'LAB', sampleHandling: 'CLIENT_DISPOSE', reportDelivery: 'PICKUP', reportDeliveryAddress: '', reportCopies: 0, projectPart: '', monomer: '', settlementContact: '', witnessUnit: '', witnessName: '', witnessPhone: '', witnessCertificate: '', samplingUnit: '', samplingPerson: '', samplingPhone: '', samplingCertificate: '', constructionUnit: '', needSampling: false, electronicSignature: false, cmaSeal: false, qualificationSeal: false, externalCommission: false, externalOrganization: '', externalNumber: '', sampleReceivingAddress: '', expectedReportOn: '', remark: '', sourceRemark: '' })
  projectParts.value = []; attachments.value = []; ageReportValues.value = []; selectionDrafts.value = []
  activeRevisionId.value = ''; parameterQuery.value = ''
}

async function openCreate() {
  editing.value = undefined; resetForm(); drawer.value = true
  if (!catalog.value.length) await loadCatalog()
}

async function projectChanged() {
  if (!form.projectId || editing.value) return
  try {
    const value = await api<ProjectDefaults>(`/api/commissions/project-defaults/${form.projectId}`)
    form.customerId = value.customerId; form.contactName = value.entruster.name ?? ''; form.contactPhone = value.entruster.phone ?? ''
    Object.assign(details, { constructionUnit: value.constructionUnit ?? '', witnessUnit: value.witnessUnit ?? '', witnessName: value.witness.name ?? '', witnessPhone: value.witness.phone ?? '', witnessCertificate: value.witness.certificateNumber ?? '', samplingUnit: value.samplingUnit ?? '', samplingPerson: value.sampler.name ?? '', samplingPhone: value.sampler.phone ?? '', samplingCertificate: value.sampler.certificateNumber ?? '', settlementContact: value.settlementContact ?? '', electronicSignature: value.electronicSignature, cmaSeal: value.cmaSeal, qualificationSeal: value.qualificationSeal })
    projectParts.value = value.structureParts
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

function emptyFieldValues(fields: DetectionSampleField[]) {
  return Object.fromEntries(fields.map((field) => [field.code, [] as string[]]))
}

async function chooseProject(node: CatalogNode) {
  if (node.type !== 'PROJECT' || !node.projectRevisionId) return
  const existing = selectionDrafts.value.find((item) => item.projectRevisionId === node.projectRevisionId)
  if (existing) { activeRevisionId.value = existing.projectRevisionId; return }
  try {
    const revision = await api<ProjectRevision>(`/api/detection-project-revisions/${node.projectRevisionId}`)
    const parameters = revision.parameters.filter(selectable)
    const selected = parameters.filter((item) => item.required || item.defaultCommissioned).map((item) => item.id)
    if (!selected.length && parameters[0]) selected.push(parameters[0].id)
    const first = parameters.find((item) => selected.includes(item.id)); const clientKey = `sg-${crypto.randomUUID()}`
    const fields = emptyFieldValues(revision.sampleFields)
    const projectPart = revision.sampleFields.find((field) => field.code === 'project_part')
    if (projectPart && details.projectPart) fields[projectPart.code] = [details.projectPart]
    selectionDrafts.value.push({ projectRevisionId: revision.id, projectName: revision.projectName, path: revision.path, revisionNumber: revision.revisionNumber, parameters, parameterIds: selected, sampleFields: revision.sampleFields, ageReportOptions: revision.ageReportOptions, sampleGroups: [{ clientKey, name: first?.sampleName || revision.projectName, description: first?.sampleDescription ?? '', quantity: validQuantity(first?.sampleQuantity), unit: first?.unit || '组', parameterIds: [...selected], fieldValues: fields }] })
    revision.ageReportOptions.filter((item) => item.defaultSelected).forEach((item) => { if (!ageReportValues.value.includes(item.value)) ageReportValues.value.push(item.value) })
    activeRevisionId.value = revision.id
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

function validQuantity(value?: string) { return value && /^\d+(\.\d{1,6})?$/.test(value) && Number(value) > 0 ? value : '1' }
function toggleParameter(parameter: DetectionParameter, checked: boolean) {
  const draft = activeSelection.value; if (!draft || parameter.required && !checked) return
  if (checked && !draft.parameterIds.includes(parameter.id)) { draft.parameterIds.push(parameter.id); draft.sampleGroups[0]?.parameterIds.push(parameter.id) }
  if (!checked) { draft.parameterIds = draft.parameterIds.filter((id) => id !== parameter.id); draft.sampleGroups.forEach((group) => { group.parameterIds = group.parameterIds.filter((id) => id !== parameter.id) }) }
}
function selectAllParameters() {
  const draft = activeSelection.value; if (!draft) return
  draft.parameterIds = draft.parameters.filter(selectable).map((item) => item.id)
  const assigned = new Set(draft.sampleGroups.flatMap((group) => group.parameterIds)); draft.sampleGroups[0]?.parameterIds.push(...draft.parameterIds.filter((id) => !assigned.has(id)))
}
function clearOptionalParameters() {
  const draft = activeSelection.value; if (!draft) return
  draft.parameterIds = draft.parameters.filter((item) => item.required).map((item) => item.id)
  draft.sampleGroups.forEach((group) => { group.parameterIds = group.parameterIds.filter((id) => draft.parameterIds.includes(id)) })
  if (draft.parameterIds.length && !draft.sampleGroups.some((group) => group.parameterIds.length)) draft.sampleGroups[0]?.parameterIds.push(...draft.parameterIds)
}
function setParameterGroups(parameterId: string, clientKeys: string[]) {
  const draft = activeSelection.value; if (!draft) return
  draft.sampleGroups.forEach((group) => { group.parameterIds = group.parameterIds.filter((id) => id !== parameterId) })
  clientKeys.forEach((clientKey) => draft.sampleGroups.find((group) => group.clientKey === clientKey)?.parameterIds.push(parameterId))
}
function addSampleGroup() {
  const draft = activeSelection.value; if (!draft) return
  draft.sampleGroups.push({ clientKey: `sg-${crypto.randomUUID()}`, name: `${draft.projectName}样品组${draft.sampleGroups.length + 1}`, description: '', quantity: '1', unit: '组', parameterIds: [], fieldValues: emptyFieldValues(draft.sampleFields) })
}
function removeSampleGroup(group: SampleGroupDraft) {
  const draft = activeSelection.value; if (!draft || draft.sampleGroups.length === 1) return ElMessage.warning('每个检测项目至少保留一个样品组')
  const fallback = draft.sampleGroups.find((item) => item.clientKey !== group.clientKey); fallback?.parameterIds.push(...group.parameterIds)
  draft.sampleGroups = draft.sampleGroups.filter((item) => item.clientKey !== group.clientKey)
}
function removeProject(projectRevisionId: string) {
  selectionDrafts.value = selectionDrafts.value.filter((item) => item.projectRevisionId !== projectRevisionId)
  if (activeRevisionId.value === projectRevisionId) activeRevisionId.value = selectionDrafts.value[0]?.projectRevisionId ?? ''
}

async function openEdit(row: CommissionSummary) {
  if (row.state !== 'DRAFT') return ElMessage.warning('委托提交后检测选项已冻结，不能原地修改')
  saving.value = true
  try {
    const [current, frozen] = await Promise.all([api<CommissionDetail>(`/api/commissions/${row.id}`), api<FrozenSelection[]>(`/api/commissions/${row.id}/detection-selections`), loadCatalog()])
    editing.value = current
    Object.assign(form, { projectId: current.projectId, customerId: current.customerId, contactName: current.contactName, contactPhone: current.contactPhone ?? '', commissionedOn: current.commissionedOn, dueOn: current.dueOn })
    Object.assign(details, Object.fromEntries(Object.entries(current.details)
      .filter(([key]) => !['ageReportValues', 'attachments'].includes(key))
      .map(([key, value]) => [key, value ?? ''])))
    attachments.value = current.details.attachments; ageReportValues.value = current.details.ageReportValues
    const defaults = await api<ProjectDefaults>(`/api/commissions/project-defaults/${current.projectId}`); projectParts.value = defaults.structureParts
    selectionDrafts.value = await Promise.all(frozen.map(async (item) => {
      const revision = await api<ProjectRevision>(`/api/detection-project-revisions/${item.projectRevisionId}`)
      return { projectRevisionId: item.projectRevisionId, projectName: item.projectName, path: revision.path, revisionNumber: item.revisionNumber, parameters: revision.parameters.filter(selectable), parameterIds: [...new Set(item.parameters.map((parameter) => parameter.parameterId))], sampleFields: revision.sampleFields, ageReportOptions: revision.ageReportOptions, sampleGroups: item.sampleGroups.map((group) => ({ clientKey: group.clientKey, name: group.name, description: group.description ?? '', quantity: group.quantity, unit: group.unit, parameterIds: [...group.parameterIds], fieldValues: { ...emptyFieldValues(revision.sampleFields), ...group.fieldValues } })) }
    }))
    activeRevisionId.value = selectionDrafts.value[0]?.projectRevisionId ?? ''; drawer.value = true
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { saving.value = false }
}

function detectionPayload() {
  return selectionDrafts.value.map((draft) => ({ projectRevisionId: draft.projectRevisionId, parameterIds: draft.parameterIds, sampleGroups: draft.sampleGroups.filter((group) => group.parameterIds.length).map((group) => ({ clientKey: group.clientKey, name: group.name, description: group.description, quantity: group.quantity, unit: group.unit, parameterIds: group.parameterIds, fieldValues: group.fieldValues })) }))
}
function validateForm() {
  if (!form.projectId || !form.customerId || !form.contactName.trim() || !form.commissionedOn || !form.dueOn) return '请填写完整委托基本信息'
  if (form.dueOn < form.commissionedOn) return '计划完成日期不得早于委托日期'
  if (['MAIL', 'DELIVERED', 'EMAIL'].includes(details.reportDelivery) && !details.reportDeliveryAddress.trim()) return '请填写报告邮寄地址或电子邮箱'
  if (details.externalCommission && !details.externalOrganization.trim()) return '外委检测必须填写外委机构'
  if (!selectionDrafts.value.length) return '请至少选择一个检测项目'
  for (const draft of selectionDrafts.value) {
    if (!draft.parameterIds.length) return `“${draft.projectName}”至少选择一项检测参数`
    const required = draft.parameters.filter((item) => item.required).map((item) => item.id)
    if (required.some((id) => !draft.parameterIds.includes(id))) return `“${draft.projectName}”存在未选择的必选参数`
    const assigned = draft.sampleGroups.flatMap((group) => group.parameterIds)
    if (draft.parameterIds.some((id) => !assigned.includes(id))) return `“${draft.projectName}”每项参数至少分配到一个样品组`
    for (const group of draft.sampleGroups.filter((item) => item.parameterIds.length)) {
      if (!group.name.trim() || !group.unit.trim() || !/^\d+(\.\d{1,6})?$/.test(group.quantity) || Number(group.quantity) <= 0) return `请完善“${draft.projectName}”的样品组名称、数量和单位`
      const missing = draft.sampleFields.find((field) => field.required && field.coreBinding === 'NONE' && !(group.fieldValues[field.code] ?? []).some((value) => value.trim()))
      if (missing) return `“${group.name}”缺少必填字段：${missing.label}`
    }
  }
  return ''
}
async function save() {
  const invalid = validateForm(); if (invalid) return ElMessage.warning(invalid)
  saving.value = true
  try {
    const payload = { contactName: form.contactName, contactPhone: form.contactPhone, commissionedOn: form.commissionedOn, dueOn: form.dueOn, details: { ...details, expectedReportOn: details.expectedReportOn || null, ageReportValues: ageReportValues.value, attachmentFileIds: attachments.value.map((item) => item.id) }, detectionSelections: detectionPayload() }
    const created = editing.value ? await api<CommissionDetail>(`/api/commissions/${editing.value.id}`, { method: 'PUT', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ ...payload, expectedVersion: editing.value.version }) }) : await api<CommissionDetail>('/api/commissions', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ projectId: form.projectId, customerId: form.customerId, ...payload }) })
    ElMessage.success(editing.value ? '委托、样品字段与检测选项已更新' : '委托已创建，受理时将冻结完整版本快照')
    drawer.value = false; await load(true); await router.push(`/flow/${created.id}`)
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { saving.value = false }
}

async function uploadAttachment(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]; if (!file) return
  uploading.value = true
  try { const body = new FormData(); body.append('file', file); body.append('purpose', 'COMMISSION_INTAKE'); attachments.value.push(await api<StoredFile>('/api/files', { method: 'POST', body })); ElMessage.success('附件已安全上传') }
  catch (error) { ElMessage.error(errorMessage(error)) }
  finally { uploading.value = false; (event.target as HTMLInputElement).value = '' }
}

async function transition(row: CommissionSummary, action: 'SUBMIT' | 'WITHDRAW' | 'CANCEL') {
  try {
    const actionLabel = action === 'SUBMIT' ? '提交' : action === 'WITHDRAW' ? '撤销提交' : '作废'
    const result = await ElMessageBox.prompt(`请输入${actionLabel}原因，操作将写入审计日志`, `${actionLabel}委托`, { inputType: 'textarea', inputValue: action === 'SUBMIT' ? '资料完整，提交受理' : '', inputValidator: (value) => Boolean(value.trim()) || '原因不能为空' })
    await api(`/api/commissions/${row.id}/transitions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: `COMMISSION_${action}`, comment: result.value }) })
    ElMessage.success(`委托已${actionLabel}`); await load(true)
  } catch (error) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}
async function batchSubmit() {
  if (viewMode.value !== 'COMMISSION') return ElMessage.warning('请切换到按委托单查看后执行批量提交')
  const targets = checkedRows.value.filter((row) => row.state === 'DRAFT'); if (!targets.length) return ElMessage.warning('请勾选草稿委托')
  await ElMessageBox.confirm(`将提交 ${targets.length} 条草稿委托，任一不满足前置条件的记录会明确返回失败原因。`, '批量提交', { type: 'warning' })
  try {
    await api('/api/commissions/batch-transitions', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ targets: targets.map((row) => ({ id: row.id, expectedVersion: row.version })), action: 'SUBMIT', reasonCode: 'BATCH_SUBMIT', comment: '委托列表批量提交' }) })
    ElMessage.success(`已原子提交 ${targets.length} 条委托`); await load(true)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
function openBatchInternalSequence() {
  if (viewMode.value !== 'COMMISSION') return ElMessage.warning('请切换到按委托单查看后修改内部流水')
  if (!checkedRows.value.length) return ElMessage.warning('请至少勾选一条委托')
  internalSequenceDrafts.value = checkedRows.value.map((row) => ({
    id: row.id, number: row.number, projectName: row.projectName,
    version: row.version, internalSequence: row.internalSequence,
  }))
  internalSequenceDialog.value = true
}
async function saveBatchInternalSequence() {
  try {
    await api('/api/commissions/batch-internal-sequences', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        targets: internalSequenceDrafts.value.map((item) => ({
          id: item.id, expectedVersion: item.version, internalSequence: item.internalSequence ?? null,
        })),
        comment: '委托列表批量修改内部流水',
      }),
    })
    internalSequenceDialog.value = false
    ElMessage.success(`已原子更新 ${internalSequenceDrafts.value.length} 条内部流水`)
    await load(true)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
async function changeViewMode() {
  checkedRows.value = []
  await load(true)
}
function openRow(row: CommissionSummary) { router.push(`/flow/${row.id}`) }
function requestChange(row: CommissionSummary) { router.push({ path: '/modules/commission-change-requests', query: { commissionId: row.id } }) }
async function copyCommission(row: CommissionSummary) {
  await ElMessageBox.confirm(`将“${row.number}”的工程、检测选项、样品字段和附件复制为新草稿；原编号不会复用。`, '复制委托')
  try { const copied = await api<CommissionDetail>(`/api/commissions/${row.id}/copies`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ expectedVersion: row.version }) }); ElMessage.success(`已复制为 ${copied.number}`); await load(true) }
  catch (error) { ElMessage.error(errorMessage(error)) }
}
async function printCommission(row: CommissionSummary, documentType: 'COMMISSION' | 'SAMPLE_LABEL') {
  try { await api(`/api/commissions/${row.id}/prints`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ documentType, expectedVersion: row.version }) }); await load(true); window.print() }
  catch (error) { ElMessage.error(errorMessage(error)) }
}
async function searchRelation(query = '') {
  const page = await api<CursorPage<CommissionSummary>>(`/api/commissions?q=${encodeURIComponent(query)}&limit=50`)
  relatedCandidates.value = page.items.filter((item) => item.id !== actionRow.value?.id)
}
async function openRelation(row: CommissionSummary) {
  actionRow.value = row; Object.assign(relationForm, { targetCommissionId: '', relationType: 'RELATED', comment: '' })
  relations.value = await api<RelationView[]>(`/api/commissions/${row.id}/relations`); await searchRelation(); relationDialog.value = true
}
async function saveRelation() {
  if (!actionRow.value || !relationForm.targetCommissionId) return ElMessage.warning('请选择关联委托')
  try { await api(`/api/commissions/${actionRow.value.id}/relations`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ ...relationForm, expectedVersion: actionRow.value.version }) }); relationDialog.value = false; ElMessage.success('委托关联已建立'); await load(true) }
  catch (error) { ElMessage.error(errorMessage(error)) }
}
async function openTransfer(row: CommissionSummary) {
  actionRow.value = row; Object.assign(transferForm, { toDepartmentId: '', comment: '' })
  transfers.value = await api<TransferView[]>(`/api/commissions/${row.id}/transfers`); transferDialog.value = true
}
async function saveTransfer() {
  if (!actionRow.value || !transferForm.toDepartmentId) return ElMessage.warning('请选择目标部门')
  try { await api(`/api/commissions/${actionRow.value.id}/transfers`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ ...transferForm, expectedVersion: actionRow.value.version }) }); transferDialog.value = false; ElMessage.success('转交申请已发起'); await load(true) }
  catch (error) { ElMessage.error(errorMessage(error)) }
}
async function transitionTransfer(item: TransferView, action: 'ACCEPT' | 'REJECT' | 'CANCEL') {
  const label = action === 'ACCEPT' ? '接收' : action === 'REJECT' ? '拒绝' : '撤销'
  try {
    const result = await ElMessageBox.prompt(`请输入${label}原因，操作将写入审计日志`, `${label}转交`, {
      inputType: 'textarea', inputValidator: (value) => Boolean(value.trim()) || '原因不能为空',
    })
    await api(`/api/commissions/transfers/${item.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: item.version, reasonCode: `COMMISSION_TRANSFER_${action}`, comment: result.value }),
    })
    if (actionRow.value) transfers.value = await api<TransferView[]>(`/api/commissions/${actionRow.value.id}/transfers`)
    ElMessage.success(`已${label}转交`); await load(true)
  } catch (error) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}
function selectedOrRow(row?: CommissionSummary) {
  const values = checkedRows.value.length ? checkedRows.value : row ? [row] : []
  return [...new Map(values.map((item) => [item.id, item])).values()]
}
async function openFlowEvents(row: CommissionSummary) {
  actionRow.value = row
  flowEvents.value = []; flowNextCursor.value = ''; flowHasMore.value = false; flowTotal.value = 0
  try { await loadFlowEvents(); flowDialog.value = true }
  catch (error) { ElMessage.error(errorMessage(error)) }
}
async function loadFlowEvents(append = false) {
  if (!actionRow.value || flowLoading.value) return
  flowLoading.value = true
  try {
    const params = new URLSearchParams({ limit: '50' })
    if (append && flowNextCursor.value) params.set('cursor', flowNextCursor.value)
    const page = await api<CursorPage<FlowEvent>>(`/api/commissions/${actionRow.value.id}/flow-events?${params}`)
    flowEvents.value = append ? [...flowEvents.value, ...page.items] : page.items
    flowNextCursor.value = page.nextCursor ?? ''
    flowHasMore.value = page.hasMore
    flowTotal.value = page.total
  } finally { flowLoading.value = false }
}
async function batchCopy(row?: CommissionSummary) {
  const targets = selectedOrRow(row)
  if (!targets.length) return ElMessage.warning('请至少勾选一条委托')
  if (targets.length > 50) return ElMessage.warning('单次最多复制 50 条委托')
  try {
    await ElMessageBox.confirm(`将原子复制 ${targets.length} 条委托为新草稿，原编号和外部编号不会复用。`, '批量复制', { type: 'warning' })
    await api('/api/commissions/batch-copies', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ targets: targets.map((item) => ({ id: item.id, expectedVersion: item.version })), comment: '委托管理批量复制' }) })
    ElMessage.success(`已复制 ${targets.length} 条委托`); await load(true)
  } catch (error) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}
async function pollJob(jobId: string) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const job = await api<BackgroundJob>(`/api/jobs/${jobId}`); currentJob.value = job
    if (['COMPLETED', 'FAILED'].includes(job.state)) return job
    await new Promise((resolve) => window.setTimeout(resolve, 1200))
  }
  return currentJob.value
}
async function exportCommissions(row?: CommissionSummary) {
  const targets = selectedOrRow(row)
  if (!targets.length) return ElMessage.warning('请至少勾选一条委托')
  try {
    const queued = await api<JobQueued>('/api/commissions/exports', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ targets: targets.map((item) => ({ id: item.id, expectedVersion: item.version })), comment: '委托管理受控导出' }) })
    currentJob.value = { id: queued.jobId, jobType: queued.jobType, state: queued.state, attemptCount: 0, version: 0, createdAt: new Date().toISOString(), allowedActions: [] }
    jobDialog.value = true
    const completed = await pollJob(queued.jobId)
    if (completed?.state === 'COMPLETED') ElMessage.success('委托清单已生成，可下载')
    else if (completed?.state === 'FAILED') ElMessage.error(failureReasonLabel(completed.lastError, '导出作业失败'))
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
function downloadJob() {
  if (!currentJob.value?.resultFileId) return
  const anchor = document.createElement('a'); anchor.href = `/api/files/${currentJob.value.resultFileId}`
  anchor.download = '委托清单.xlsx'; document.body.appendChild(anchor); anchor.click(); anchor.remove()
}
async function bindExternalNumber(row: CommissionSummary) {
  try {
    const result = await ElMessageBox.prompt('请输入外部系统委托编号。编号在本机构内必须唯一。', `绑定外部编号 · ${row.number}`, { inputValue: row.externalNumber ?? '', inputValidator: (value) => Boolean(value.trim()) || '外部编号不能为空' })
    await api(`/api/commissions/${row.id}/external-number-bindings`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ externalNumber: result.value, expectedVersion: row.version, comment: '委托管理绑定外部编号' }) })
    ElMessage.success('外部编号已绑定'); await load(true)
  } catch (error) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}
async function openSampleParts(row: CommissionSummary) {
  actionRow.value = row
  try {
    const [selections, defaults, bindings] = await Promise.all([
      api<FrozenSelection[]>(`/api/commissions/${row.id}/detection-selections`),
      api<ProjectDefaults>(`/api/commissions/project-defaults/${row.projectId}`),
      api<SampleGroupPart[]>(`/api/commissions/${row.id}/sample-group-parts`),
    ])
    partOptions.value = defaults.structureParts
    samplePartDrafts.value = selections.flatMap((selection) => selection.sampleGroups).map((group) => ({
      sampleGroupId: group.id, sampleGroupName: group.name,
      projectPartIds: bindings.filter((item) => item.sampleGroupId === group.id).map((item) => item.projectPartId),
    }))
    samplePartsDialog.value = true
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
async function saveSampleParts() {
  if (!actionRow.value) return
  try {
    await api(`/api/commissions/${actionRow.value.id}/sample-group-parts`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ bindings: samplePartDrafts.value.map((item) => ({ sampleGroupId: item.sampleGroupId, projectPartIds: item.projectPartIds })), expectedVersion: actionRow.value.version, comment: '绑定样品组分部/分项' }) })
    samplePartsDialog.value = false; ElMessage.success('分部/分项绑定已更新'); await load(true)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
async function terminateTests(row: CommissionSummary) {
  try {
    await ElMessageBox.confirm('该操作会取消所有未完成任务并终止委托；存在有效报告时服务端将拒绝。此操作不可撤销。', `终止试验 · ${row.number}`, { type: 'error' })
    const result = await ElMessageBox.prompt('请输入终止原因，至少说明触发依据和后续处置。', '终止原因', { inputType: 'textarea', inputValidator: (value) => Boolean(value.trim()) || '终止原因不能为空' })
    await api(`/api/commissions/${row.id}/test-terminations`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ reasonCode: 'MANUAL_TERMINATION', comment: result.value, expectedVersion: row.version }) })
    ElMessage.success('检测任务已受控终止'); await load(true)
  } catch (error) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}
async function openPrintRestriction(row: CommissionSummary) {
  actionRow.value = row
  try {
    const value = await api<PrintRestriction>(`/api/commissions/${row.id}/report-print-restriction`)
    Object.assign(printRestrictionForm, { restricted: value.restricted, printLimit: value.printLimit, reason: value.reason ?? '' })
    printRestrictionDialog.value = true
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
async function savePrintRestriction() {
  if (!actionRow.value || !printRestrictionForm.reason.trim()) return ElMessage.warning('请填写策略调整原因')
  try {
    await api(`/api/commissions/${actionRow.value.id}/report-print-restrictions`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ restricted: printRestrictionForm.restricted, printLimit: printRestrictionForm.restricted ? printRestrictionForm.printLimit ?? null : null, reason: printRestrictionForm.reason, expectedVersion: actionRow.value.version }) })
    printRestrictionDialog.value = false; ElMessage.success('报告打印策略已生效'); await load(true)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
async function openElectronicDocuments(row: CommissionSummary) {
  actionRow.value = row
  try {
    [electronicReadiness.value, electronicDocuments.value] = await Promise.all([
      api<ElectronicReadiness>(`/api/commissions/${row.id}/electronic-document-readiness`),
      api<ElectronicDocument[]>(`/api/commissions/${row.id}/electronic-documents`),
    ])
    electronicDialog.value = true
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
async function refreshElectronicDocument() {
  if (!actionRow.value) return
  if (!electronicReadiness.value.ready) return ElMessage.warning(electronicReadiness.value.blockers.join('；'))
  try {
    const queued = await api<JobQueued>(`/api/commissions/${actionRow.value.id}/electronic-document-refreshes`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ expectedVersion: actionRow.value.version }) })
    currentJob.value = { id: queued.jobId, jobType: queued.jobType, state: queued.state, attemptCount: 0, version: 0, createdAt: new Date().toISOString(), allowedActions: [] }
    jobDialog.value = true
    const completed = await pollJob(queued.jobId)
    if (completed?.state === 'COMPLETED') { ElMessage.success('电子委托单已生成'); await openElectronicDocuments(actionRow.value) }
    else if (completed?.state === 'FAILED') ElMessage.error(failureReasonLabel(completed.lastError, '电子委托单生成失败'))
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
function openBatchChange(row?: CommissionSummary) {
  const targets = selectedOrRow(row)
  const blocked = targets.filter((item) => ['DRAFT', 'REJECTED', 'CANCELLED'].includes(item.state))
  if (!targets.length) return ElMessage.warning('请至少勾选一条委托')
  if (blocked.length) return ElMessage.warning(`以下委托状态不能发起修改申请：${blocked.map((item) => item.number).join('、')}`)
  batchChangeTargets.value = targets
  Object.assign(batchChangeForm, { requestType: 'OTHER', processingMethod: 'MANUAL_RECORD', reason: '', requestedSummary: '' })
  batchChangeDialog.value = true
}
async function saveBatchChange() {
  if (!batchChangeForm.reason.trim()) return ElMessage.warning('请填写修改原因')
  try {
    await api('/api/commission-change-requests/batches', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ targets: batchChangeTargets.value.map((item) => ({ commissionId: item.id, expectedVersion: item.version })), ...batchChangeForm }) })
    batchChangeDialog.value = false; ElMessage.success(`已原子创建 ${batchChangeTargets.value.length} 条修改申请`)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
function openInternalForRow(row: CommissionSummary) {
  checkedRows.value = [row]; openBatchInternalSequence()
}
function rowAction(row: CommissionSummary, action: string) {
  if (action === 'COPY') copyCommission(row)
  else if (action === 'PRINT' && canPrint.value) printCommission(row, 'COMMISSION')
  else if (action === 'LABEL' && canPrint.value) printCommission(row, 'SAMPLE_LABEL')
  else if (action === 'RELATE') openRelation(row)
  else if (action === 'TRANSFER') openTransfer(row)
  else if (action === 'FLOW') openFlowEvents(row)
  else if (action === 'ELECTRONIC') openElectronicDocuments(row)
  else if (action === 'TERMINATE') terminateTests(row)
  else if (action === 'BATCH_COPY') batchCopy(row)
  else if (action === 'EXTERNAL_NUMBER') bindExternalNumber(row)
  else if (action === 'PARTS') openSampleParts(row)
  else if (action === 'EXPORT') exportCommissions(row)
  else if (action === 'PRE_RETURN') transition(row, 'WITHDRAW')
  else if (action === 'BATCH_CHANGE') openBatchChange(row)
  else if (action === 'PRINT_RESTRICTION') openPrintRestriction(row)
  else if (action === 'INTERNAL_SEQUENCE') openInternalForRow(row)
}
async function printList() {
  await api('/api/commissions/print-audits', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ query: filters.q, state: filters.states, customerId: null, from: filters.commissionedFrom || null, to: filters.commissionedTo || null, rowCount: rows.value.length }) })
  window.print()
}

onMounted(async () => {
  const [referenceData, options, departmentData] = await Promise.all([api<References>('/api/reference-data'), api<CommissionOptions>('/api/commissions/options'), api<DepartmentOption[]>('/api/master-data/departments')])
  references.value = referenceData; commissionOptions.value = options; departments.value = departmentData.filter((item) => item.active); await load(true)
})
</script>

<template>
  <section class="page commission-list-page">
    <header class="page-heading">
      <div><h1>委托管理</h1><p>维护委托信息、检测项目、参数和样品组，并办理提交与受理。</p></div>
      <div class="heading-actions"><el-button :icon="Refresh" @click="load">刷新</el-button><el-button type="primary" :icon="Plus" @click="openCreate">新建委托</el-button></div>
    </header>

    <section class="commission-filter-shell">
      <div class="commission-filter-bar">
        <el-segmented v-model="viewMode" :options="[{ label: '按委托单查看', value: 'COMMISSION' }, { label: '按样品查看', value: 'SAMPLE' }]" @change="changeViewMode" />
        <el-input v-model="filters.q" clearable :prefix-icon="Search" placeholder="委托号、工程、客户、联系人或检测项目" @keyup.enter="load(true)" />
        <el-select v-model="filters.states" clearable placeholder="委托状态"><el-option v-for="item in ['DRAFT','PRE_ACCEPTED','ACCEPTED','SAMPLING','ASSIGNED','TESTING','REPORTING','ISSUED','ARCHIVED','REJECTED','CANCELLED']" :key="item" :label="stateLabel(item)" :value="item" /></el-select>
        <div class="filter-date-pair"><el-date-picker v-model="filters.commissionedFrom" value-format="YYYY-MM-DD" placeholder="委托起始" /><span>至</span><el-date-picker v-model="filters.commissionedTo" value-format="YYYY-MM-DD" placeholder="委托截止" /></div>
        <el-button type="primary" :icon="Search" @click="load(true)">查询</el-button>
        <el-button :icon="Filter" @click="filtersExpanded = !filtersExpanded">{{ filtersExpanded ? '收起条件' : '更多条件' }}</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
      <div v-show="filtersExpanded" class="commission-advanced-filters">
        <el-input v-model="filters.detectionProject" clearable placeholder="检测项目" />
        <el-input v-model="filters.commissionNumber" clearable placeholder="委托编号" />
        <el-input v-model="filters.precommissionNumber" clearable placeholder="预委托编号" />
        <el-input v-model="filters.sampleNumber" clearable placeholder="样品编号" />
        <el-input v-model="filters.customer" clearable placeholder="委托单位" />
        <el-input v-model="filters.projectNumber" clearable placeholder="工程编号" />
        <el-input v-model="filters.projectName" clearable placeholder="工程名称" />
        <el-select v-model="filters.flowStates" clearable placeholder="流转状态"><el-option label="待流转" value="PENDING" /><el-option label="已流转" value="TRANSFERRED" /></el-select>
        <el-select v-model="filters.taskStates" clearable placeholder="任务状态"><el-option v-for="item in ['NOT_ASSIGNED','UNASSIGNED','ASSIGNED','IN_PROGRESS','RETURNED','COMPLETED']" :key="item" :label="taskLabel(item)" :value="item" /></el-select>
        <el-select v-model="filters.testingPlace" clearable placeholder="检测地点"><el-option v-for="item in commissionOptions.testingPlaces" :key="item.code" :label="item.label" :value="item.code" /></el-select>
        <el-select v-model="filters.externalCommission" clearable placeholder="是否外委"><el-option label="是" value="true" /><el-option label="否" value="false" /></el-select>
        <div class="sequence-range"><el-input-number v-model="filters.managementSequenceMin" :min="1" controls-position="right" placeholder="管理序号起" /><span>—</span><el-input-number v-model="filters.managementSequenceMax" :min="1" controls-position="right" placeholder="管理序号止" /></div>
        <el-radio-group v-model="departmentScope" @change="load(true)"><el-radio-button value="DEPARTMENT">本部门委托</el-radio-button><el-radio-button value="ALL">全部</el-radio-button></el-radio-group>
        <el-checkbox v-model="filters.waitingFlow">只显示待流转</el-checkbox>
      </div>
      <div class="commission-batch-bar"><span>已选择 {{ checkedRows.length }} 条</span><el-button :disabled="viewMode !== 'COMMISSION' || !checkedRows.some(item => item.state === 'DRAFT')" @click="batchSubmit">批量提交</el-button><el-button :disabled="viewMode !== 'COMMISSION' || !checkedRows.length" @click="batchCopy()">批量复制</el-button><el-button :disabled="viewMode !== 'COMMISSION' || !checkedRows.length" @click="openBatchChange()">批量修改申请</el-button><el-button :disabled="viewMode !== 'COMMISSION' || !checkedRows.length" @click="openBatchInternalSequence">批量修改内部流水</el-button><el-button v-if="canPrint" :disabled="!checkedRows.length" @click="exportCommissions()">数据导出</el-button><el-button v-if="canPrint" :icon="Printer" @click="printList">打印当前清单</el-button></div>
    </section>

    <el-table v-loading="loading" :data="rows" stripe border class="module-table commission-master-table" row-key="rowId" @selection-change="checkedRows = $event" @row-dblclick="openRow">
      <el-table-column v-if="viewMode === 'COMMISSION'" type="selection" width="44" />
      <el-table-column prop="number" label="委托编号" width="166" fixed="left" />
      <el-table-column v-if="viewMode === 'SAMPLE'" prop="sampleNumber" label="样品编号" width="168"><template #default="scope">{{ scope.row.sampleNumber || '待生成' }}</template></el-table-column>
      <el-table-column v-if="viewMode === 'SAMPLE'" prop="sampleName" label="样品名称" min-width="180" show-overflow-tooltip />
      <el-table-column v-if="viewMode === 'SAMPLE'" label="样品数量" width="116"><template #default="scope">{{ scope.row.sampleQuantity }} {{ scope.row.sampleUnit }}</template></el-table-column>
      <el-table-column prop="precommissionNumber" label="预委托编号" width="150"><template #default="scope">{{ scope.row.precommissionNumber || '—' }}</template></el-table-column>
      <el-table-column prop="customerName" label="委托单位" min-width="190" show-overflow-tooltip />
      <el-table-column prop="projectNumber" label="工程编号" width="136" show-overflow-tooltip />
      <el-table-column prop="projectName" label="工程名称" min-width="220" show-overflow-tooltip />
      <el-table-column prop="detectionProjects" label="检测项目" min-width="220" show-overflow-tooltip />
      <el-table-column prop="commissionedOn" label="委托日期" width="116" />
      <el-table-column label="委托状态" width="108" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)" effect="plain">{{ stateLabel(scope.row.state) }}</el-tag></template></el-table-column>
      <el-table-column label="流转状态" width="100" align="center"><template #default="scope"><el-tag :type="scope.row.flowStatus === 'PENDING' ? 'warning' : 'success'" effect="plain">{{ flowLabel(scope.row.flowStatus) }}</el-tag></template></el-table-column>
      <el-table-column label="任务状态" width="102" align="center"><template #default="scope">{{ taskLabel(scope.row.taskStatus) }}</template></el-table-column>
      <el-table-column label="报告状态" width="102" align="center"><template #default="scope">{{ reportLabel(scope.row.reportStatus) }}</template></el-table-column>
      <el-table-column label="委托打印" width="120"><template #default="scope">{{ printLabel(scope.row.printStatus) }}</template></el-table-column>
      <el-table-column label="样品组" width="82"><template #default="scope">{{ scope.row.sampleGroupCount }}</template></el-table-column>
      <el-table-column prop="receiverName" label="受理人" width="104"><template #default="scope">{{ scope.row.receiverName || '—' }}</template></el-table-column>
      <el-table-column prop="managementSequence" label="委托流水" width="104" />
      <el-table-column prop="internalSequence" label="内部流水" width="104"><template #default="scope">{{ scope.row.internalSequence || '—' }}</template></el-table-column>
      <el-table-column prop="departmentName" label="接收部门" width="140"><template #default="scope">{{ scope.row.departmentName || '—' }}</template></el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="176"><template #default="scope">{{ new Date(scope.row.createdAt).toLocaleString('zh-CN', { hour12: false }) }}</template></el-table-column>
      <el-table-column label="操作" fixed="right" width="310" align="center"><template #default="scope">
        <el-button link type="primary" @click="router.push(`/flow/${scope.row.id}`)">进入流程</el-button>
        <el-button v-if="scope.row.state === 'DRAFT'" link :icon="Edit" @click="openEdit(scope.row)">修改</el-button>
        <el-button v-if="scope.row.state === 'DRAFT'" link type="primary" @click="transition(scope.row, 'SUBMIT')">提交</el-button>
        <el-button v-if="scope.row.state === 'PRE_ACCEPTED'" link @click="transition(scope.row, 'WITHDRAW')">撤销</el-button>
        <el-button v-if="!['DRAFT','REJECTED','CANCELLED'].includes(scope.row.state)" link @click="requestChange(scope.row)">申请修改</el-button>
        <el-button v-if="['DRAFT','ACCEPTED','SAMPLING','ASSIGNED'].includes(scope.row.state)" link type="danger" @click="transition(scope.row, 'CANCEL')">作废</el-button>
        <el-dropdown trigger="click" @command="rowAction(scope.row, String($event))"><el-button link>更多</el-button><template #dropdown><el-dropdown-menu>
          <el-dropdown-item command="FLOW">流转记录</el-dropdown-item>
          <el-dropdown-item command="ELECTRONIC">刷新电子委托单</el-dropdown-item>
          <el-dropdown-item command="RELATE">委托单关联</el-dropdown-item>
          <el-dropdown-item command="TERMINATE" :disabled="!['ASSIGNED','TESTING'].includes(scope.row.state)">终止试验</el-dropdown-item>
          <el-dropdown-item command="BATCH_COPY">批量复制</el-dropdown-item>
          <el-dropdown-item command="EXTERNAL_NUMBER">绑定外部编号</el-dropdown-item>
          <el-dropdown-item command="PARTS">绑定分部/分项</el-dropdown-item>
          <el-dropdown-item v-if="canPrint" command="EXPORT">数据导出</el-dropdown-item>
          <el-dropdown-item v-if="scope.row.state === 'PRE_ACCEPTED'" command="PRE_RETURN">预委托退回</el-dropdown-item>
          <el-dropdown-item v-if="!['DRAFT','REJECTED','CANCELLED'].includes(scope.row.state)" command="BATCH_CHANGE">批量修改申请</el-dropdown-item>
          <el-dropdown-item command="PRINT_RESTRICTION">报告限制打印</el-dropdown-item>
          <el-dropdown-item command="INTERNAL_SEQUENCE">修改内部流水</el-dropdown-item>
          <el-dropdown-item command="TRANSFER" divided>转交部门</el-dropdown-item>
          <el-dropdown-item v-if="canPrint" command="PRINT">打印委托单</el-dropdown-item>
          <el-dropdown-item v-if="canPrint" command="LABEL">打印样品标签</el-dropdown-item>
        </el-dropdown-menu></template></el-dropdown>
      </template></el-table-column>
      <template #empty><el-empty description="没有符合条件的委托" /></template>
    </el-table>
    <footer class="table-footer"><span>共 {{ total }} 条，当前第 {{ pageNumber }} 页</span><div class="cursor-pagination"><el-select v-model="pageSize" @change="load(true)"><el-option :value="20" label="20 条/页" /><el-option :value="50" label="50 条/页" /><el-option :value="100" label="100 条/页" /></el-select><el-button :disabled="pageNumber === 1" @click="previousPage">上一页</el-button><el-button :disabled="!nextCursor" @click="nextPage">下一页</el-button></div></footer>

    <el-drawer v-model="drawer" :title="editing ? `修改委托 · ${editing.number}` : '新建委托'" size="96%" class="commission-acceptance-drawer" :close-on-click-modal="false">
      <div v-loading="saving" class="commission-acceptance-body">
        <section class="commission-basic-card">
          <header><div><strong>委托基本信息</strong><span v-if="editing">工程与客户建立后不可替换；其他内容仅在草稿阶段可修改。</span></div><el-tag v-if="editing" type="warning" effect="plain">草稿 V{{ editing.version }}</el-tag></header>
          <el-form label-position="top"><div class="commission-form-grid">
            <el-form-item label="工程" required><el-select v-model="form.projectId" filterable :disabled="Boolean(editing)" @change="projectChanged"><el-option v-for="item in references.projects" :key="item.id" :label="`${item.code} · ${item.label}`" :value="item.id" /></el-select></el-form-item>
            <el-form-item label="委托单位" required><el-select v-model="form.customerId" filterable :disabled="Boolean(form.projectId)"><el-option v-for="item in references.customers" :key="item.id" :label="`${item.code} · ${item.label}`" :value="item.id" /></el-select></el-form-item>
            <el-form-item label="联系人" required><el-input v-model="form.contactName" /></el-form-item><el-form-item label="联系电话"><el-input v-model="form.contactPhone" /></el-form-item>
            <el-form-item label="委托日期" required><el-date-picker v-model="form.commissionedOn" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="计划完成" required><el-date-picker v-model="form.dueOn" value-format="YYYY-MM-DD" /></el-form-item>
          </div></el-form>
        </section>

        <section class="commission-basic-card commission-intake-card">
          <header><div><strong>受理与报告要求</strong><span>字段对标试用站，工程资料会自动带入，仍可在草稿阶段核对修正。</span></div><el-tag effect="plain">结构化受控</el-tag></header>
          <el-tabs>
            <el-tab-pane label="检测与工程">
              <el-form label-position="top"><div class="commission-form-grid">
                <el-form-item label="工程进度" required><el-select v-model="details.projectProgress"><el-option v-for="item in commissionOptions.projectProgress" :key="item.code" :label="item.label" :value="item.code" /></el-select></el-form-item>
                <el-form-item label="检测类型" required><el-select v-model="details.testingType"><el-option v-for="item in commissionOptions.testingTypes" :key="item.code" :label="item.label" :value="item.code" /></el-select></el-form-item>
                <el-form-item label="检测标准类别" required><el-select v-model="details.testingStandard"><el-option v-for="item in commissionOptions.testingStandards" :key="item.code" :label="item.label" :value="item.code" /></el-select></el-form-item>
                <el-form-item label="检测地点" required><el-select v-model="details.testingPlace"><el-option v-for="item in commissionOptions.testingPlaces" :key="item.code" :label="item.label" :value="item.code" /></el-select></el-form-item>
                <el-form-item label="结构部位"><el-select v-model="details.projectPart" filterable allow-create clearable><el-option v-for="item in projectParts" :key="item.code" :label="item.label" :value="item.label" /></el-select></el-form-item>
                <el-form-item label="单体工程"><el-input v-model="details.monomer" /></el-form-item>
                <el-form-item label="施工单位"><el-input v-model="details.constructionUnit" /></el-form-item>
                <el-form-item label="结算联系人"><el-input v-model="details.settlementContact" /></el-form-item>
              </div></el-form>
            </el-tab-pane>
            <el-tab-pane label="见证与抽样">
              <el-form label-position="top"><div class="commission-form-grid">
                <el-form-item label="见证单位"><el-input v-model="details.witnessUnit" /></el-form-item><el-form-item label="见证人"><el-input v-model="details.witnessName" /></el-form-item>
                <el-form-item label="见证人电话"><el-input v-model="details.witnessPhone" /></el-form-item><el-form-item label="见证证书号"><el-input v-model="details.witnessCertificate" /></el-form-item>
                <el-form-item label="抽样单位"><el-input v-model="details.samplingUnit" /></el-form-item><el-form-item label="抽样人"><el-input v-model="details.samplingPerson" /></el-form-item>
                <el-form-item label="抽样人电话"><el-input v-model="details.samplingPhone" /></el-form-item><el-form-item label="抽样证书号"><el-input v-model="details.samplingCertificate" /></el-form-item>
              </div><div class="commission-switch-grid"><el-switch v-model="details.needSampling" active-text="需要抽样" /></div></el-form>
            </el-tab-pane>
            <el-tab-pane label="报告与样品">
              <el-form label-position="top"><div class="commission-form-grid">
                <el-form-item label="样品处理" required><el-select v-model="details.sampleHandling"><el-option v-for="item in commissionOptions.sampleHandling" :key="item.code" :label="item.label" :value="item.code" /></el-select></el-form-item>
                <el-form-item label="报告领取" required><el-select v-model="details.reportDelivery"><el-option v-for="item in commissionOptions.reportDelivery" :key="item.code" :label="item.label" :value="item.code" /></el-select></el-form-item>
                <el-form-item label="报告份数"><el-input-number v-model="details.reportCopies" :min="0" :max="999" /></el-form-item>
                <el-form-item label="计划报告日期"><el-date-picker v-model="details.expectedReportOn" value-format="YYYY-MM-DD" clearable /></el-form-item>
                <el-form-item label="邮寄地址 / 电子邮箱" :required="['MAIL','DELIVERED','EMAIL'].includes(details.reportDelivery)" class="span-two"><el-input v-model="details.reportDeliveryAddress" /></el-form-item>
                <el-form-item label="样品接收地址" class="span-two"><el-input v-model="details.sampleReceivingAddress" /></el-form-item>
                <el-form-item label="龄期报告" class="span-two"><el-checkbox-group v-model="ageReportValues"><el-checkbox v-for="item in availableAgeOptions" :key="item.code" :value="item.code">{{ item.label }}</el-checkbox></el-checkbox-group><span v-if="!availableAgeOptions.length" class="field-hint">所选检测项目无龄期选项</span></el-form-item>
              </div><div class="commission-switch-grid"><el-switch v-model="details.electronicSignature" active-text="电子签名" /><el-switch v-model="details.cmaSeal" active-text="CMA章" /><el-switch v-model="details.qualificationSeal" active-text="资质章" /></div></el-form>
            </el-tab-pane>
            <el-tab-pane label="外委、备注与附件">
              <el-form label-position="top"><div class="commission-form-grid">
                <el-form-item label="是否外委"><el-switch v-model="details.externalCommission" /></el-form-item>
                <el-form-item label="外部委托编号"><el-input v-model="details.externalNumber" /></el-form-item>
                <el-form-item v-if="details.externalCommission" label="外委机构" required class="span-two"><el-input v-model="details.externalOrganization" /></el-form-item>
                <el-form-item label="委托备注" class="span-two"><el-input v-model="details.remark" type="textarea" :rows="3" /></el-form-item>
                <el-form-item label="来源备注" class="span-two"><el-input v-model="details.sourceRemark" type="textarea" :rows="3" /></el-form-item>
                <el-form-item label="委托附件" class="span-two"><label class="file-picker"><input type="file" accept="application/pdf,image/png,image/jpeg,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.openxmlformats-officedocument.wordprocessingml.document" @change="uploadAttachment" /><el-icon><Upload /></el-icon>{{ uploading ? '正在安全校验…' : '上传 PDF、图片、XLSX 或 DOCX' }}</label><div class="commission-attachment-list"><span v-for="file in attachments" :key="file.id"><el-link :href="`/api/files/${file.id}`">{{ file.name || file.originalName }}</el-link><el-button link type="danger" @click="attachments = attachments.filter(item => item.id !== file.id)">移除</el-button></span></div></el-form-item>
              </div></el-form>
            </el-tab-pane>
          </el-tabs>
        </section>

        <section class="commission-selection-workbench">
          <aside class="commission-catalog-pane" v-loading="catalogLoading">
            <header><div><strong>1. 选择检测项目</strong><span>仅显示启用的当前发布版</span></div><el-tag size="small">{{ flatCatalog.filter(item => item.type === 'PROJECT').length }} 项</el-tag></header>
            <div class="commission-tree-search"><el-input v-model="catalogQuery" clearable :prefix-icon="Search" placeholder="搜索目录或检测项目" @keyup.enter="loadCatalog" @clear="loadCatalog" /><el-button :icon="Search" circle @click="loadCatalog" /></div>
            <el-tree-v2 :data="catalog" :props="treeProps" :height="590" :item-size="36" @node-click="chooseProject">
              <template #default="{ data }"><div class="commission-tree-node" :class="{ project: data.type === 'PROJECT' }"><span>{{ data.type === 'DOMAIN' ? '域' : data.type === 'CATEGORY' ? '类' : '项' }}</span><strong>{{ data.name }}</strong><i v-if="data.type === 'PROJECT'">{{ data.parameterCount }}</i></div></template>
            </el-tree-v2>
          </aside>

          <section class="commission-parameter-pane">
            <header><div><strong>2. 选择检测参数</strong><span>{{ activeSelection?.path ?? '从左侧选择检测项目' }}</span></div><div v-if="activeSelection" class="parameter-actions"><el-button text @click="selectAllParameters">全选</el-button><el-button text @click="clearOptionalParameters">取消可选项</el-button></div></header>
            <template v-if="activeSelection">
              <div class="selected-project-tabs"><button v-for="item in selectionDrafts" :key="item.projectRevisionId" type="button" :class="{ active: item.projectRevisionId === activeRevisionId }" @click="activeRevisionId = item.projectRevisionId"><span>{{ item.projectName }}</span><small>R{{ item.revisionNumber }} · {{ item.parameterIds.length }} 项</small><el-icon @click.stop="removeProject(item.projectRevisionId)"><Delete /></el-icon></button></div>
              <div class="commission-parameter-search"><el-input v-model="parameterQuery" clearable :prefix-icon="Search" placeholder="搜索参数、代码、单位或检测依据" /><span>已选 {{ activeSelection.parameterIds.length }} / {{ activeSelection.parameters.length }}</span></div>
              <div class="commission-parameter-list">
                <div v-for="item in visibleParameters" :key="item.id" class="commission-parameter-row" :class="{ selected: isSelected(item.id) }">
                  <el-checkbox :model-value="isSelected(item.id)" :disabled="item.required" @change="toggleParameter(item, Boolean($event))" />
                  <div><strong>{{ item.name }}</strong><span>{{ item.code }}<template v-if="item.unit"> · {{ item.unit }}</template><template v-if="item.standards.length"> · {{ item.standards.map(entry => entry.code).join('、') }}</template></span></div>
                  <div class="row-tags"><el-tag v-if="item.required" size="small" type="danger">必选</el-tag><el-tag v-if="item.defaultCommissioned" size="small" effect="plain">默认</el-tag></div>
                  <el-select v-if="isSelected(item.id)" :model-value="groupsForParameter(item.id)" multiple collapse-tags size="small" placeholder="分配样品组" @change="setParameterGroups(item.id, $event as string[])"><el-option v-for="group in activeSelection.sampleGroups" :key="group.clientKey" :label="group.name" :value="group.clientKey" /></el-select>
                </div>
              </div>
            </template>
            <el-empty v-else description="选择检测项目后加载参数、依据和样品默认值" />
          </section>

          <aside class="commission-sample-pane">
            <header><div><strong>3. 配置样品组</strong><span>同一参数可用于多个样品组</span></div><el-button v-if="activeSelection" :icon="Plus" text @click="addSampleGroup">新增组</el-button></header>
            <template v-if="activeSelection">
              <div class="sample-group-list"><section v-for="(group, index) in activeSelection.sampleGroups" :key="group.clientKey" class="sample-group-card">
                <header><strong>样品组 {{ index + 1 }}</strong><el-button :disabled="activeSelection.sampleGroups.length === 1" :icon="Delete" text type="danger" @click="removeSampleGroup(group)" /></header>
                <el-form label-position="top"><el-form-item label="样品名称" required><el-input v-model="group.name" /></el-form-item><el-form-item label="样品描述 / 规格"><el-input v-model="group.description" type="textarea" :rows="2" /></el-form-item><div class="form-grid"><el-form-item label="预计数量" required><el-input v-model="group.quantity" /></el-form-item><el-form-item label="单位" required><el-input v-model="group.unit" /></el-form-item></div></el-form>
                <div v-if="activeSelection.sampleFields.some(field => field.coreBinding === 'NONE')" class="sample-dynamic-fields"><strong>项目样品字段</strong><el-form label-position="top">
                  <el-form-item v-for="field in activeSelection.sampleFields.filter(item => item.coreBinding === 'NONE')" :key="field.id" :label="`${field.label}${field.unit ? `（${field.unit}）` : ''}`" :required="field.required">
                    <el-input v-if="field.fieldType === 'TEXT'" v-model="group.fieldValues[field.code][0]" :placeholder="field.placeholder" />
                    <el-input v-else-if="field.fieldType === 'DECIMAL'" v-model="group.fieldValues[field.code][0]" inputmode="decimal" :placeholder="field.placeholder" />
                    <el-date-picker v-else-if="field.fieldType === 'DATE'" v-model="group.fieldValues[field.code][0]" value-format="YYYY-MM-DD" :placeholder="field.placeholder || '选择日期'" />
                    <el-select v-else-if="field.fieldType === 'SINGLE_SELECT'" v-model="group.fieldValues[field.code][0]" filterable clearable :placeholder="field.placeholder || '请选择'"><el-option v-for="option in field.options" :key="option" :label="option" :value="option" /></el-select>
                    <el-select v-else-if="field.fieldType === 'MULTI_SELECT'" v-model="group.fieldValues[field.code]" multiple filterable collapse-tags :placeholder="field.placeholder || '请选择'"><el-option v-for="option in field.options" :key="option" :label="option" :value="option" /></el-select>
                    <el-select v-else-if="field.fieldType === 'STANDARD_SELECT'" v-model="group.fieldValues[field.code][0]" filterable clearable :placeholder="field.placeholder || '选择依据'"><el-option v-for="standard in field.standards" :key="standard.id" :label="`${standard.code} · ${standard.name}`" :value="standard.id" /></el-select>
                    <el-select v-else v-model="group.fieldValues[field.code]" multiple filterable collapse-tags :placeholder="field.placeholder || '选择依据'"><el-option v-for="standard in field.standards" :key="standard.id" :label="`${standard.code} · ${standard.name}`" :value="standard.id" /></el-select>
                  </el-form-item>
                </el-form></div>
                <footer><span>已分配 {{ group.parameterIds.length }} 项</span><el-tooltip :content="group.parameterIds.map(id => activeSelection?.parameters.find(item => item.id === id)?.name).filter(Boolean).join('、') || '暂无参数'" placement="top"><el-tag size="small" effect="plain">查看参数</el-tag></el-tooltip></footer>
              </section></div>
              <el-alert v-if="activeSelection.parameterIds.some(id => groupsForParameter(id).length === 0)" title="仍有已选参数未分配样品组，保存前必须完成分配。" type="warning" :closable="false" show-icon />
            </template>
            <el-empty v-else description="样品默认值随检测项目加载" />
          </aside>
        </section>
      </div>
      <template #footer><div class="acceptance-footer"><span>已选 {{ selectionDrafts.length }} 个项目、{{ selectionDrafts.reduce((total, item) => total + item.parameterIds.length, 0) }} 项参数、{{ selectionDrafts.reduce((total, item) => total + item.sampleGroups.length, 0) }} 个样品组</span><div><el-button @click="drawer = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">{{ editing ? '保存草稿修改' : '保存并进入流程' }}</el-button></div></div></template>
    </el-drawer>

    <el-dialog v-model="internalSequenceDialog" title="批量修改内部流水" width="760px" :close-on-click-modal="false">
      <el-alert title="仅修改所选委托的内部流水；留空表示未配置。整批使用乐观锁原子提交，任一记录冲突则全部回滚。" type="info" :closable="false" />
      <el-table :data="internalSequenceDrafts" size="small" border class="internal-sequence-table">
        <el-table-column prop="number" label="委托编号" width="180" />
        <el-table-column prop="projectName" label="工程名称" min-width="260" show-overflow-tooltip />
        <el-table-column label="内部流水" width="180"><template #default="scope"><el-input-number v-model="scope.row.internalSequence" :min="1" :max="999999999" controls-position="right" placeholder="未配置" /></template></el-table-column>
      </el-table>
      <template #footer><el-button @click="internalSequenceDialog = false">取消</el-button><el-button type="primary" @click="saveBatchInternalSequence">确定修改</el-button></template>
    </el-dialog>

    <el-dialog v-model="relationDialog" :title="`关联委托 · ${actionRow?.number ?? ''}`" width="680px">
      <el-form label-position="top"><el-form-item label="目标委托" required><el-select v-model="relationForm.targetCommissionId" filterable remote :remote-method="searchRelation" placeholder="输入委托号、工程或客户搜索"><el-option v-for="item in relatedCandidates" :key="item.id" :label="`${item.number} · ${item.projectName}`" :value="item.id" /></el-select></el-form-item><div class="form-grid"><el-form-item label="关联类型"><el-select v-model="relationForm.relationType"><el-option label="一般关联" value="RELATED" /><el-option label="拆分来源" value="SPLIT_FROM" /></el-select></el-form-item><el-form-item label="说明"><el-input v-model="relationForm.comment" /></el-form-item></div></el-form>
      <el-divider content-position="left">现有关联</el-divider><el-table :data="relations" size="small"><el-table-column prop="sourceNumber" label="源委托" /><el-table-column prop="targetNumber" label="目标委托" /><el-table-column prop="relationType" label="关系" width="110" /><el-table-column prop="createdBy" label="操作人" width="100" /></el-table>
      <template #footer><el-button @click="relationDialog = false">取消</el-button><el-button type="primary" @click="saveRelation">建立关联</el-button></template>
    </el-dialog>

    <el-dialog v-model="transferDialog" :title="`转交委托 · ${actionRow?.number ?? ''}`" width="640px">
      <el-alert title="转交先形成待接收申请，目标部门接受后才改变委托归属；全过程写入审计。" type="info" :closable="false" />
      <el-form label-position="top"><el-form-item label="目标部门" required><el-select v-model="transferForm.toDepartmentId" filterable><el-option v-for="item in departments" :key="item.id" :label="`${item.code} · ${item.name}`" :value="item.id" /></el-select></el-form-item><el-form-item label="转交说明"><el-input v-model="transferForm.comment" type="textarea" :rows="3" /></el-form-item></el-form>
      <el-divider content-position="left">转交记录</el-divider><el-table :data="transfers" size="small"><el-table-column prop="toDepartment" label="目标部门" /><el-table-column prop="state" label="状态" width="100" align="center" /><el-table-column prop="createdBy" label="发起人" width="100" /><el-table-column prop="updatedAt" label="更新时间" width="178" /><el-table-column label="操作" width="150" align="center"><template #default="scope"><el-button v-if="scope.row.allowedActions.includes('ACCEPT')" link type="primary" @click="transitionTransfer(scope.row, 'ACCEPT')">接收</el-button><el-button v-if="scope.row.allowedActions.includes('REJECT')" link type="danger" @click="transitionTransfer(scope.row, 'REJECT')">拒绝</el-button><el-button v-if="scope.row.allowedActions.includes('CANCEL')" link @click="transitionTransfer(scope.row, 'CANCEL')">撤销</el-button></template></el-table-column></el-table>
      <template #footer><el-button @click="transferDialog = false">取消</el-button><el-button type="primary" @click="saveTransfer">发起转交</el-button></template>
    </el-dialog>

    <el-dialog v-model="flowDialog" :title="`流转记录 · ${actionRow?.number ?? ''}（共 ${flowTotal} 条）`" width="820px">
      <el-timeline v-if="flowEvents.length" class="commission-flow-timeline">
        <el-timeline-item v-for="item in flowEvents" :key="item.id" :timestamp="new Date(item.occurredAt).toLocaleString('zh-CN', { hour12: false })" placement="top">
          <div class="flow-event-card"><strong>{{ item.action }}</strong><span>{{ item.actorName || '系统作业' }} · {{ item.resourceType }}</span><p v-if="item.comment">{{ item.comment }}</p><code v-if="item.reasonCode">{{ item.reasonCode }}</code></div>
        </el-timeline-item>
      </el-timeline><el-empty v-else description="暂无流转事件" />
      <template #footer><el-button v-if="flowHasMore" :loading="flowLoading" @click="loadFlowEvents(true)">加载更早记录</el-button><el-button @click="flowDialog = false">关闭</el-button></template>
    </el-dialog>

    <el-dialog v-model="samplePartsDialog" :title="`绑定分部/分项 · ${actionRow?.number ?? ''}`" width="760px" :close-on-click-modal="false">
      <el-alert title="分部/分项来自该委托所属工程的受控结构树；每个样品组可绑定多个节点。" type="info" :closable="false" />
      <el-form label-position="top" class="sample-part-form"><el-form-item v-for="item in samplePartDrafts" :key="item.sampleGroupId" :label="item.sampleGroupName"><el-select v-model="item.projectPartIds" multiple filterable collapse-tags placeholder="选择分部/分项"><el-option v-for="part in partOptions" :key="part.code" :label="part.label" :value="part.code" /></el-select></el-form-item></el-form>
      <el-empty v-if="!samplePartDrafts.length" description="当前委托没有样品组" />
      <template #footer><el-button @click="samplePartsDialog = false">取消</el-button><el-button type="primary" :disabled="!samplePartDrafts.length" @click="saveSampleParts">保存绑定</el-button></template>
    </el-dialog>

    <el-dialog v-model="printRestrictionDialog" :title="`报告限制打印 · ${actionRow?.number ?? ''}`" width="580px" :close-on-click-modal="false">
      <el-alert title="限制策略由服务端在报告 PDF 下载前执行；每次下载使用两分钟有效的一次性授权，无法通过直接文件地址绕过。" type="warning" :closable="false" />
      <el-form label-position="top"><el-form-item label="启用打印限制"><el-switch v-model="printRestrictionForm.restricted" active-text="限制报告打印" /></el-form-item><el-form-item v-if="printRestrictionForm.restricted" label="允许打印/下载次数"><el-input-number v-model="printRestrictionForm.printLimit" :min="0" :max="9999" /><span class="field-hint">留空或 0 表示禁止打印</span></el-form-item><el-form-item label="调整原因" required><el-input v-model="printRestrictionForm.reason" type="textarea" :rows="3" /></el-form-item></el-form>
      <template #footer><el-button @click="printRestrictionDialog = false">取消</el-button><el-button type="primary" @click="savePrintRestriction">保存策略</el-button></template>
    </el-dialog>

    <el-dialog v-model="electronicDialog" :title="`电子委托单 · ${actionRow?.number ?? ''}`" width="760px">
      <el-alert v-if="!electronicReadiness.ready" title="正式模板尚未就绪，系统不会生成占位文件" type="warning" :closable="false" show-icon><template #default><ul><li v-for="item in electronicReadiness.blockers" :key="item">{{ item }}</li></ul></template></el-alert>
      <el-alert v-else title="受控模板已就绪；刷新会排队生成新的不可变版本。" type="success" :closable="false" show-icon />
      <el-table :data="electronicDocuments" size="small" border class="electronic-document-table"><el-table-column prop="revision" label="版本" width="90"><template #default="scope">R{{ scope.row.revision }}</template></el-table-column><el-table-column prop="createdBy" label="生成操作人" width="130" /><el-table-column prop="createdAt" label="生成时间" width="190"><template #default="scope">{{ new Date(scope.row.createdAt).toLocaleString('zh-CN', { hour12: false }) }}</template></el-table-column><el-table-column label="受控文件"><template #default="scope"><el-link :href="`/api/files/${scope.row.fileId}`" type="primary">下载电子委托单</el-link></template></el-table-column></el-table>
      <template #footer><el-button @click="electronicDialog = false">关闭</el-button><el-tooltip :disabled="electronicReadiness.ready" :content="electronicReadiness.blockers.join('；')"><span><el-button type="primary" :disabled="!electronicReadiness.ready" @click="refreshElectronicDocument">刷新电子委托单</el-button></span></el-tooltip></template>
    </el-dialog>

    <el-dialog v-model="batchChangeDialog" :title="`批量修改申请 · ${batchChangeTargets.length} 条委托`" width="680px" :close-on-click-modal="false">
      <el-alert title="整批原子创建：任一委托版本冲突、已有未结申请或状态不允许时，全部回滚。" type="info" :closable="false" />
      <el-form label-position="top"><div class="form-grid"><el-form-item label="修改类型"><el-select v-model="batchChangeForm.requestType"><el-option label="报告要求" value="REPORT_REQUIREMENT" /><el-option label="样品" value="SAMPLE" /><el-option label="检测选项" value="DETECTION_SELECTION" /><el-option label="其他" value="OTHER" /></el-select></el-form-item><el-form-item label="处理方式"><el-select v-model="batchChangeForm.processingMethod"><el-option label="建立修订版本" value="CREATE_REVISION" /><el-option label="作废并重建" value="VOID_AND_RECREATE" /><el-option label="人工记录" value="MANUAL_RECORD" /></el-select></el-form-item></div><el-form-item label="修改原因" required><el-input v-model="batchChangeForm.reason" type="textarea" :rows="3" /></el-form-item><el-form-item label="申请内容"><el-input v-model="batchChangeForm.requestedSummary" type="textarea" :rows="4" /></el-form-item></el-form>
      <div class="batch-target-list"><el-tag v-for="item in batchChangeTargets" :key="item.id" effect="plain">{{ item.number }}</el-tag></div>
      <template #footer><el-button @click="batchChangeDialog = false">取消</el-button><el-button type="primary" @click="saveBatchChange">原子创建申请</el-button></template>
    </el-dialog>

    <el-dialog v-model="jobDialog" title="后台作业" width="620px" :close-on-click-modal="false">
      <el-descriptions v-if="currentJob" :column="2" border><el-descriptions-item label="作业类型">{{ jobTypeLabel(currentJob.jobType) }}</el-descriptions-item><el-descriptions-item label="状态"><el-tag :type="currentJob.state === 'COMPLETED' ? 'success' : currentJob.state === 'FAILED' ? 'danger' : 'warning'">{{ statusLabel(currentJob.state) }}</el-tag></el-descriptions-item><el-descriptions-item label="尝试次数">{{ currentJob.attemptCount }}</el-descriptions-item><el-descriptions-item label="创建时间">{{ new Date(currentJob.createdAt).toLocaleString('zh-CN', { hour12: false }) }}</el-descriptions-item><el-descriptions-item v-if="currentJob.lastError" label="失败原因" :span="2">{{ failureReasonLabel(currentJob.lastError, '后台作业失败') }}</el-descriptions-item></el-descriptions>
      <template #footer><el-button @click="jobDialog = false">关闭</el-button><el-button v-if="currentJob?.resultFileId" type="primary" @click="downloadJob">下载结果</el-button></template>
    </el-dialog>
  </section>
</template>
