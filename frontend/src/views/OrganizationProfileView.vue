<script setup lang="ts">
import {
  Clock, Document, EditPen, Location, OfficeBuilding, Plus, Refresh, Upload, User, WarningFilled,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { useAuthStore } from '../stores/auth'
import { integrationStateLabel } from '../presentation'

type PersonCard = { id: string; name: string; title?: string; position?: string; phone?: string; avatarFileId?: string }
type Profile = {
  id: string; code: string; fullName: string; shortName?: string; kind: string; organizationNature?: string
  unifiedCreditCode?: string; registeredOn?: string; registrationAuthority?: string; postalCode?: string
  province?: string; city?: string; district?: string; detailedAddress?: string; registeredCapital?: number
  telephone?: string; fax?: string; email?: string; legalRepresentativeId?: string; frequentContactName?: string
  frequentContactPhone?: string; primaryResponsiblePersonId?: string; qualityResponsiblePersonId?: string
  technicalResponsiblePersonId?: string; declaredEquipmentCount?: number; declaredEquipmentOriginalValue?: number
  testingArea?: number; workplaceArea?: number; remark?: string; businessLicenseFileId?: string
  businessLicenseFileName?: string; legalRepresentative?: PersonCard; primaryResponsiblePerson?: PersonCard
  qualityResponsiblePerson?: PersonCard; technicalResponsiblePerson?: PersonCard; active: boolean; version: number
  updatedAt: string
}
type PersonOption = { id: string; employeeNumber: string; name: string; departmentName: string; title?: string; phone?: string; active: boolean }
type DivisionOption = { name: string }
type OfficeAddress = {
  id: string; officeName: string; province: string; city: string; district: string; detailedAddress: string
  fullAddress: string; postalCode: string; changedOn: string; correspondenceAddress: boolean; active: boolean
  revisionNumber: number; version: number; updatedAt: string; allowedActions: string[]
}
type AddressRevision = OfficeAddress & { createdAt: string }
type Qualification = {
  id: string; certificateNumber: string; qualificationDetails: string[]; issuedOn: string; validUntil: string
  issuingAuthority: string; testingScope: string; state: string
}
type CursorPage<T> = { items: T[]; total: number }
type StoredFile = { id: string; originalName: string }
type IntegrationStatus = { state: string; blockers: string[]; safetyPolicy: string }

const router = useRouter()
const auth = useAuthStore()
const canAdmin = computed(() => auth.user?.permissions.includes('SYSTEM_ADMIN'))
const profile = ref<Profile>()
const people = ref<PersonOption[]>([])
const addresses = ref<OfficeAddress[]>([])
const qualifications = ref<Qualification[]>([])
const provinces = ref<DivisionOption[]>([])
const cities = ref<DivisionOption[]>([])
const districts = ref<DivisionOption[]>([])
const addressCities = ref<DivisionOption[]>([])
const addressDistricts = ref<DivisionOption[]>([])
const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)
const profileDrawer = ref(false)
const addressDrawer = ref(false)
const addressHistoryDialog = ref(false)
const esignDialog = ref(false)
const includeRetired = ref(false)
const profileTab = ref('basic')
const history = ref<AddressRevision[]>([])
const editingAddress = ref<OfficeAddress>()
const integration = ref<IntegrationStatus>()
const profileFormRef = ref<FormInstance>()
const addressFormRef = ref<FormInstance>()

const profileForm = reactive<Record<string, any>>({})
const addressForm = reactive<Record<string, any>>({})
const agreements = reactive({ certificationAgreement: false, digitalCertificateAgreement: false, privacyPolicy: false })

const requiredProfileFields = [
  'shortName', 'fullName', 'organizationNature', 'unifiedCreditCode', 'registeredOn', 'registrationAuthority',
  'postalCode', 'province', 'city', 'district', 'detailedAddress', 'registeredCapital', 'telephone', 'fax',
  'legalRepresentativeId', 'frequentContactName', 'frequentContactPhone', 'primaryResponsiblePersonId',
  'qualityResponsiblePersonId', 'technicalResponsiblePersonId', 'declaredEquipmentCount',
  'declaredEquipmentOriginalValue', 'testingArea', 'workplaceArea', 'remark',
]
const profileComplete = computed(() => Boolean(profile.value)
  && requiredProfileFields.every(field => profile.value?.[field as keyof Profile] !== null
    && profile.value?.[field as keyof Profile] !== undefined && profile.value?.[field as keyof Profile] !== ''))
const activeAddresses = computed(() => addresses.value.filter(item => includeRetired.value || item.active))
const roleCards = computed(() => [
  ['法定代表人', profile.value?.legalRepresentative],
  ['主要负责人', profile.value?.primaryResponsiblePerson],
  ['质量负责人', profile.value?.qualityResponsiblePerson],
  ['技术负责人', profile.value?.technicalResponsiblePerson],
] as [string, PersonCard | undefined][])
const esignBlockers = computed(() => [
  ...(!profileComplete.value ? ['单位资料尚未完整，缺少必填主体资料或负责人配置'] : []),
  ...(integration.value?.blockers ?? []),
])
const esignReady = computed(() => profileComplete.value && integration.value?.state === 'READY')

const required = { required: true, message: '此项为必填项', trigger: 'blur' }
const profileRules: FormRules = Object.fromEntries(requiredProfileFields.map(field => [field, required]))
profileRules.unifiedCreditCode = [required, { pattern: /^[0-9A-Z]{18}$/, message: '请输入 18 位大写统一社会信用代码', trigger: 'blur' }]
profileRules.postalCode = [required, { pattern: /^\d{6}$/, message: '请输入 6 位邮政编码', trigger: 'blur' }]
profileRules.email = [{ type: 'email', message: '电子邮箱格式不正确', trigger: 'blur' }]
const addressRules: FormRules = {
  officeName: required, changedOn: required, province: required, city: required, district: required,
  detailedAddress: required,
  postalCode: [required, { pattern: /^\d{6}$/, message: '请输入 6 位邮政编码', trigger: 'blur' }],
}
const qualificationLabels: Record<string, string> = {
  GEOTECHNICAL_A: '岩土工程（甲级）', CONCRETE_A: '混凝土工程（甲级）',
  METAL_STRUCTURE_A: '金属结构（甲级）', MECHANICAL_ELECTRICAL_A: '机械电气（甲级）',
  MEASUREMENT_A: '量测（甲级）', GEOTECHNICAL_B: '岩土工程（乙级）',
  CONCRETE_B: '混凝土工程（乙级）', METAL_STRUCTURE_B: '金属结构（乙级）',
  MECHANICAL_ELECTRICAL_B: '机械电气（乙级）', MEASUREMENT_B: '量测（乙级）',
}

function showError(error: unknown) {
  ElMessage.error(errorMessage(error))
}
function value(input: unknown, suffix = '') { return input === null || input === undefined || input === '' ? '—' : `${input}${suffix}` }
function natureLabel(input?: string) { return input === 'STATE_OWNED' ? '国有单位' : input === 'PRIVATE' ? '私有单位' : '待完善' }
function stateLabel(input: string) { return ({ DRAFT: '未提交', SUBMITTED: '已提交', RETIRED: '已停用' } as Record<string, string>)[input] ?? input }
function stateType(input: string) { return input === 'SUBMITTED' ? 'success' : input === 'RETIRED' ? 'info' : 'warning' }
function dateTime(input?: string) {
  return input ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit',
    day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(input)) : '—'
}
function todayShanghai() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
}
function personLabel(id?: string) {
  const person = people.value.find(item => item.id === id)
  return person ? `${person.name} · ${person.employeeNumber} · ${person.departmentName}` : id ? '当前数据范围外人员' : ''
}
function openQualificationManagement() {
  void router.push({ name: 'module', params: { moduleKey: 'organization-qualifications' } })
}

async function load() {
  loading.value = true
  try {
    const [profileValue, addressValues, personValues, qualificationPage, provinceValues] = await Promise.all([
      api<Profile>('/api/organization-profile'),
      api<OfficeAddress[]>('/api/organization-profile/office-addresses?includeRetired=true'),
      api<PersonOption[]>('/api/master-data/people'),
      api<CursorPage<Qualification>>('/api/foundation/organization-qualifications?limit=100'),
      api<DivisionOption[]>('/api/administrative-divisions'),
    ])
    profile.value = profileValue
    addresses.value = addressValues
    people.value = personValues.filter(item => item.active)
    qualifications.value = qualificationPage.items
    provinces.value = provinceValues
  } catch (error) { showError(error) }
  finally { loading.value = false }
}

async function loadCities(province: string, target: 'profile' | 'address') {
  const result = province ? await api<DivisionOption[]>(`/api/administrative-divisions?province=${encodeURIComponent(province)}`) : []
  if (target === 'profile') cities.value = result
  else addressCities.value = result
}
async function loadDistricts(province: string, city: string, target: 'profile' | 'address') {
  const result = province && city ? await api<DivisionOption[]>(`/api/administrative-divisions?province=${encodeURIComponent(province)}&city=${encodeURIComponent(city)}`) : []
  if (target === 'profile') districts.value = result
  else addressDistricts.value = result
}
async function changeProfileProvince(value: string) {
  profileForm.city = ''; profileForm.district = ''; districts.value = []
  try { await loadCities(value, 'profile') } catch (error) { showError(error) }
}
async function changeProfileCity(value: string) {
  profileForm.district = ''
  try { await loadDistricts(profileForm.province, value, 'profile') } catch (error) { showError(error) }
}
async function changeAddressProvince(value: string) {
  addressForm.city = ''; addressForm.district = ''; addressDistricts.value = []
  try { await loadCities(value, 'address') } catch (error) { showError(error) }
}
async function changeAddressCity(value: string) {
  addressForm.district = ''
  try { await loadDistricts(addressForm.province, value, 'address') } catch (error) { showError(error) }
}

async function openProfile() {
  if (!profile.value) return
  Object.keys(profileForm).forEach(key => delete profileForm[key])
  Object.assign(profileForm, profile.value, { expectedVersion: profile.value.version })
  profileTab.value = 'basic'
  profileDrawer.value = true
  try {
    await loadCities(profileForm.province, 'profile')
    await loadDistricts(profileForm.province, profileForm.city, 'profile')
  } catch (error) { showError(error) }
}
async function saveProfile() {
  if (!await profileFormRef.value?.validate().catch(() => false)) return ElMessage.warning('请检查必填项和格式')
  saving.value = true
  try {
    profile.value = await api<Profile>('/api/organization-profile', {
      method: 'PUT', body: JSON.stringify({ ...profileForm, email: profileForm.email || null }),
    })
    profileDrawer.value = false
    ElMessage.success('单位资料已保存，负责人和容量声明同步更新')
  } catch (error) { showError(error) }
  finally { saving.value = false }
}

async function openAddress(row?: OfficeAddress) {
  editingAddress.value = row
  Object.keys(addressForm).forEach(key => delete addressForm[key])
  Object.assign(addressForm, row ? { ...row, expectedVersion: row.version } : {
    officeName: '', changedOn: todayShanghai(), province: '', city: '', district: '',
    detailedAddress: '', postalCode: '', correspondenceAddress: !addresses.value.some(item => item.active && item.correspondenceAddress),
  })
  addressDrawer.value = true
  if (row) {
    try { await loadCities(row.province, 'address'); await loadDistricts(row.province, row.city, 'address') }
    catch (error) { showError(error) }
  } else { addressCities.value = []; addressDistricts.value = [] }
}
async function saveAddress() {
  if (!await addressFormRef.value?.validate().catch(() => false)) return ElMessage.warning('请检查办公场所必填项')
  saving.value = true
  try {
    if (editingAddress.value) {
      await api(`/api/organization-profile/office-addresses/${editingAddress.value.id}`, {
        method: 'PUT', body: JSON.stringify(addressForm),
      })
    } else {
      await api('/api/organization-profile/office-addresses', {
        method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(addressForm),
      })
    }
    addressDrawer.value = false
    ElMessage.success(editingAddress.value ? '已生成新的地址修订版本' : '办公场所已添加')
    await load()
  } catch (error) { showError(error) }
  finally { saving.value = false }
}
async function transitionAddress(row: OfficeAddress, action: 'RETIRE' | 'RESTORE') {
  try {
    const comment = action === 'RETIRE'
      ? (await ElMessageBox.prompt('停用后仍保留全部地址历史，请填写原因。', '停用办公场所', {
          inputType: 'textarea', inputValidator: value => Boolean(value.trim()) || '停用原因不能为空', type: 'warning',
        })).value
      : (await ElMessageBox.confirm('确认恢复该办公场所吗？', '恢复办公场所', { type: 'warning' }), '恢复停用记录')
    await api(`/api/organization-profile/office-addresses/${row.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: row.version, reasonCode: action === 'RETIRE' ? 'BUSINESS_CHANGE' : 'CORRECTION', comment }),
    })
    ElMessage.success(action === 'RETIRE' ? '办公场所已停用' : '办公场所已恢复')
    await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}
async function openHistory(row: OfficeAddress) {
  try {
    history.value = await api<AddressRevision[]>(`/api/organization-profile/office-addresses/${row.id}/history`)
    addressHistoryDialog.value = true
  } catch (error) { showError(error) }
}

async function uploadBusinessLicense(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !profile.value) return
  if (!['image/png', 'image/jpeg', 'image/bmp'].includes(file.type) || file.size > 10 * 1024 * 1024) {
    return ElMessage.warning('营业执照只接受不超过 10MB 的 JPG、PNG 或 BMP 图片')
  }
  uploading.value = true
  try {
    const body = new FormData(); body.append('file', file); body.append('purpose', 'ORGANIZATION_BUSINESS_LICENSE')
    const stored = await api<StoredFile>('/api/files', { method: 'POST', body })
    profile.value = await api<Profile>('/api/organization-profile/business-license', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ fileId: stored.id, expectedVersion: profile.value.version }),
    })
    ElMessage.success('营业执照已通过文件校验并绑定')
  } catch (error) { showError(error) }
  finally { uploading.value = false }
}

async function openEsign() {
  Object.assign(agreements, { certificationAgreement: false, digitalCertificateAgreement: false, privacyPolicy: false })
  esignDialog.value = true
  try { integration.value = await api<IntegrationStatus>('/api/external-integrations/identity-intent') }
  catch (error) { showError(error) }
}
async function submitEsign() {
  if (!profile.value) return
  if (!agreements.certificationAgreement || !agreements.digitalCertificateAgreement || !agreements.privacyPolicy) {
    return ElMessage.warning('请逐项阅读并确认全部协议')
  }
  saving.value = true
  try {
    await api('/api/organization-profile/electronic-signature-enrollment', {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ...agreements, expectedVersion: profile.value.version }),
    })
  } catch (error) { showError(error) }
  finally { saving.value = false }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="page organization-profile-page">
    <header class="page-heading">
      <div><h1>单位信息</h1></div>
      <div class="heading-actions"><el-button :icon="Refresh" @click="load">刷新</el-button><el-button v-if="canAdmin" type="primary" :icon="EditPen" @click="openProfile">修改资料</el-button></div>
    </header>

    <el-alert v-if="profile && !profileComplete" title="单位资料尚未完整" description="请补齐参考系统要求的主体、负责人、联系信息和能力声明后再进入签章开户及正式业务。" type="warning" :closable="false" show-icon />

    <section v-if="profile" class="profile-hero">
      <div class="profile-mark"><el-icon><OfficeBuilding /></el-icon></div>
      <div class="profile-identity"><span>{{ profile.code }}</span><h2>{{ profile.fullName }}</h2><div><el-tag size="small">{{ natureLabel(profile.organizationNature) }}</el-tag><el-tag size="small" :type="profile.active ? 'success' : 'info'">{{ profile.active ? '启用' : '停用' }}</el-tag><el-tag v-if="profileComplete" size="small" type="success">资料完整</el-tag><el-tag v-else size="small" type="warning">待完善</el-tag></div></div>
      <dl class="hero-facts"><div><dt>单位简称</dt><dd>{{ value(profile.shortName) }}</dd></div><div><dt>注册时间</dt><dd>{{ value(profile.registeredOn) }}</dd></div><div><dt>统一社会信用代码</dt><dd>{{ value(profile.unifiedCreditCode) }}</dd></div><div><dt>资料版本</dt><dd>V{{ profile.version }}</dd></div></dl>
      <el-button v-if="canAdmin" plain @click="openEsign">电子签章开户</el-button>
    </section>

    <section v-if="profile" class="summary-grid">
      <article><span>仪器设备总数</span><strong>{{ value(profile.declaredEquipmentCount, ' 台') }}</strong><small>机构声明</small></article>
      <article><span>设备固定资产原值</span><strong>{{ value(profile.declaredEquipmentOriginalValue, ' 万元') }}</strong><small>机构声明</small></article>
      <article><span>试验检测面积</span><strong>{{ value(profile.testingArea, ' ㎡') }}</strong><small>单位资料</small></article>
      <article><span>工作场所总面积</span><strong>{{ value(profile.workplaceArea, ' ㎡') }}</strong><small>单位资料</small></article>
    </section>

    <section v-if="profile" class="role-section surface-card">
      <header class="section-heading"><div><h2>机构负责人</h2><p>负责人从启用人员档案中选择，联系方式按当前账号权限脱敏。</p></div></header>
      <div class="role-grid">
        <article v-for="item in roleCards" :key="item[0]" class="role-card">
          <el-avatar :size="44" :src="item[1]?.avatarFileId ? `/api/files/${item[1].avatarFileId}` : undefined"><el-icon><User /></el-icon></el-avatar>
          <div><span>{{ item[0] }}</span><strong>{{ item[1]?.name ?? '待配置' }}</strong><small>{{ item[1] ? [item[1].title, item[1].position].filter(Boolean).join(' · ') || '未配置职称/职务' : '请在单位资料中选择人员' }}</small></div>
          <em>{{ item[1]?.phone ?? '—' }}</em>
        </article>
      </div>
    </section>

    <div v-if="profile" class="content-grid">
      <section class="surface-card license-card">
        <header class="section-heading"><div><h2>营业执照</h2><p>上传并维护当前单位的营业执照图片。</p></div></header>
        <div class="license-body">
          <div class="license-icon"><el-icon><Document /></el-icon></div>
          <div><strong>{{ profile.businessLicenseFileName || '尚未上传营业执照' }}</strong><span>{{ profile.businessLicenseFileId ? '已绑定当前机构档案' : '支持 JPG、PNG、BMP，不超过 10MB' }}</span></div>
          <el-link v-if="profile.businessLicenseFileId" :href="`/api/files/${profile.businessLicenseFileId}`" type="primary">下载</el-link>
          <label v-if="canAdmin" class="file-picker compact"><input type="file" accept="image/png,image/jpeg,image/bmp" @change="uploadBusinessLicense" /><el-icon><Upload /></el-icon>{{ uploading ? '校验并上传中…' : profile.businessLicenseFileId ? '替换' : '上传' }}</label>
        </div>
      </section>

      <section class="surface-card qualification-card">
        <header class="section-heading"><div><h2>资质证书</h2><p>在资质管理中维护检测范围、有效期及证书正副本。</p></div><el-button link type="primary" @click="openQualificationManagement">进入资质管理</el-button></header>
        <el-empty v-if="!qualifications.length" description="尚无单位资质记录" :image-size="58" />
        <div v-else class="qualification-list"><article v-for="item in qualifications.slice(0, 3)" :key="item.id"><div><strong>{{ item.certificateNumber }}</strong><span>{{ item.issuingAuthority }} · 有效期至 {{ item.validUntil }}</span></div><el-tag :type="stateType(item.state)" size="small">{{ stateLabel(item.state) }}</el-tag><p><el-tag v-for="detail in item.qualificationDetails.slice(0, 3)" :key="detail" size="small" effect="plain">{{ qualificationLabels[detail] ?? detail }}</el-tag></p></article></div>
      </section>
    </div>

    <section v-if="profile" class="surface-card address-section">
      <header class="section-heading"><div><h2>办公场所地址维护</h2><p>地址修改后保留历史记录；同一机构只能有一个当前收件地址。</p></div><div class="heading-actions"><el-switch v-model="includeRetired" active-text="显示停用" /><el-button v-if="canAdmin" type="primary" :icon="Plus" @click="openAddress()">添加办公场所</el-button></div></header>
      <el-table :data="activeAddresses" border stripe empty-text="暂无办公场所地址">
        <el-table-column prop="officeName" label="办公场所" min-width="150" />
        <el-table-column prop="fullAddress" label="当前地址" min-width="330" show-overflow-tooltip />
        <el-table-column prop="postalCode" label="邮政编码" width="110" />
        <el-table-column prop="changedOn" label="变更日期" width="115" />
        <el-table-column label="收件地址" width="100"><template #default="scope"><el-tag v-if="scope.row.correspondenceAddress" type="success" size="small">是</el-tag><span v-else>否</span></template></el-table-column>
        <el-table-column label="状态/版本" width="125" align="center"><template #default="scope"><el-tag :type="scope.row.active ? 'success' : 'info'" size="small">{{ scope.row.active ? '启用' : '停用' }}</el-tag><span class="revision-label">R{{ scope.row.revisionNumber }}</span></template></el-table-column>
        <el-table-column label="操作" fixed="right" width="210" align="center"><template #default="scope"><el-button link type="primary" @click="openHistory(scope.row)">历史地址</el-button><template v-if="canAdmin"><el-button v-if="scope.row.active" link type="primary" @click="openAddress(scope.row)">修改</el-button><el-button link :type="scope.row.active ? 'danger' : 'primary'" @click="transitionAddress(scope.row, scope.row.active ? 'RETIRE' : 'RESTORE')">{{ scope.row.active ? '停用' : '恢复' }}</el-button></template></template></el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="profileDrawer" title="修改单位资料" size="min(860px, calc(100vw - 260px))" destroy-on-close class="organization-drawer">
      <el-form ref="profileFormRef" :model="profileForm" :rules="profileRules" label-position="top">
        <el-tabs v-model="profileTab">
          <el-tab-pane label="基本信息" name="basic">
            <div class="form-grid"><el-form-item label="单位简称" prop="shortName"><el-input v-model="profileForm.shortName" maxlength="120" /></el-form-item><el-form-item label="单位全称" prop="fullName"><el-input v-model="profileForm.fullName" maxlength="200" /></el-form-item></div>
            <div class="form-grid"><el-form-item label="单位性质" prop="organizationNature"><el-select v-model="profileForm.organizationNature"><el-option label="国有单位" value="STATE_OWNED" /><el-option label="私有单位" value="PRIVATE" /></el-select></el-form-item><el-form-item label="统一社会信用代码" prop="unifiedCreditCode"><el-input v-model="profileForm.unifiedCreditCode" maxlength="18" /></el-form-item></div>
            <div class="form-grid"><el-form-item label="注册时间" prop="registeredOn"><el-date-picker v-model="profileForm.registeredOn" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="发证机关" prop="registrationAuthority"><el-input v-model="profileForm.registrationAuthority" maxlength="200" /></el-form-item></div>
            <div class="form-grid"><el-form-item label="注册资本（万元）" prop="registeredCapital"><el-input-number v-model="profileForm.registeredCapital" :min="0" :precision="2" /></el-form-item><el-form-item label="邮政编码" prop="postalCode"><el-input v-model="profileForm.postalCode" maxlength="6" /></el-form-item></div>
            <div class="form-grid three"><el-form-item label="省" prop="province"><el-select v-model="profileForm.province" filterable @change="changeProfileProvince"><el-option v-for="item in provinces" :key="item.name" :label="item.name" :value="item.name" /></el-select></el-form-item><el-form-item label="市" prop="city"><el-select v-model="profileForm.city" filterable @change="changeProfileCity"><el-option v-for="item in cities" :key="item.name" :label="item.name" :value="item.name" /></el-select></el-form-item><el-form-item label="区县" prop="district"><el-select v-model="profileForm.district" filterable><el-option v-for="item in districts" :key="item.name" :label="item.name" :value="item.name" /></el-select></el-form-item></div>
            <el-form-item label="详细地址" prop="detailedAddress"><el-input v-model="profileForm.detailedAddress" maxlength="500" /></el-form-item>
            <div class="form-grid three"><el-form-item label="单位电话" prop="telephone"><el-input v-model="profileForm.telephone" maxlength="40" /></el-form-item><el-form-item label="单位传真" prop="fax"><el-input v-model="profileForm.fax" maxlength="40" /></el-form-item><el-form-item label="电子邮箱" prop="email"><el-input v-model="profileForm.email" maxlength="200" /></el-form-item></div>
            <div class="form-grid"><el-form-item label="经常联系人" prop="frequentContactName"><el-input v-model="profileForm.frequentContactName" maxlength="120" /></el-form-item><el-form-item label="经常联系人电话" prop="frequentContactPhone"><el-input v-model="profileForm.frequentContactPhone" maxlength="40" /></el-form-item></div>
            <el-form-item label="备注说明" prop="remark"><el-input v-model="profileForm.remark" type="textarea" :rows="3" maxlength="2000" show-word-limit /></el-form-item>
          </el-tab-pane>
          <el-tab-pane label="负责人" name="people">
            <el-alert title="只允许选择当前机构启用人员；保存时服务端再次校验人员归属和状态。" type="info" :closable="false" show-icon />
            <div class="form-grid people-form-grid">
              <el-form-item label="法定代表人" prop="legalRepresentativeId"><el-select v-model="profileForm.legalRepresentativeId" filterable><el-option v-for="item in people" :key="item.id" :label="personLabel(item.id)" :value="item.id" /></el-select></el-form-item>
              <el-form-item label="主要负责人" prop="primaryResponsiblePersonId"><el-select v-model="profileForm.primaryResponsiblePersonId" filterable><el-option v-for="item in people" :key="item.id" :label="personLabel(item.id)" :value="item.id" /></el-select></el-form-item>
              <el-form-item label="质量负责人" prop="qualityResponsiblePersonId"><el-select v-model="profileForm.qualityResponsiblePersonId" filterable><el-option v-for="item in people" :key="item.id" :label="personLabel(item.id)" :value="item.id" /></el-select></el-form-item>
              <el-form-item label="技术负责人" prop="technicalResponsiblePersonId"><el-select v-model="profileForm.technicalResponsiblePersonId" filterable><el-option v-for="item in people" :key="item.id" :label="personLabel(item.id)" :value="item.id" /></el-select></el-form-item>
            </div>
          </el-tab-pane>
          <el-tab-pane label="能力声明" name="capacity">
            <div class="form-grid"><el-form-item label="仪器设备总数（台）" prop="declaredEquipmentCount"><el-input-number v-model="profileForm.declaredEquipmentCount" :min="0" :precision="0" /></el-form-item><el-form-item label="仪器设备固定资产原值（万元）" prop="declaredEquipmentOriginalValue"><el-input-number v-model="profileForm.declaredEquipmentOriginalValue" :min="0" :precision="2" /></el-form-item></div>
            <div class="form-grid"><el-form-item label="试验检测面积（㎡）" prop="testingArea"><el-input-number v-model="profileForm.testingArea" :min="0" :precision="2" /></el-form-item><el-form-item label="工作场所总面积（㎡）" prop="workplaceArea"><el-input-number v-model="profileForm.workplaceArea" :min="0" :precision="2" /></el-form-item></div>
            <el-alert title="本页数值为机构资料声明；设备管理仍以设备台账中的受控记录为事实来源。" type="warning" :closable="false" show-icon />
          </el-tab-pane>
        </el-tabs>
      </el-form>
      <template #footer><el-button @click="profileDrawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveProfile">保存资料</el-button></template>
    </el-drawer>

    <el-drawer v-model="addressDrawer" :title="editingAddress ? '修改办公场所' : '添加办公场所'" size="640px" destroy-on-close class="organization-drawer">
      <el-form ref="addressFormRef" :model="addressForm" :rules="addressRules" label-position="top">
        <div class="form-grid"><el-form-item label="办公场所" prop="officeName"><el-input v-model="addressForm.officeName" maxlength="160" /></el-form-item><el-form-item label="变更日期" prop="changedOn"><el-date-picker v-model="addressForm.changedOn" value-format="YYYY-MM-DD" :disabled-date="(date: Date) => date.getTime() > Date.now()" /></el-form-item></div>
        <div class="form-grid three"><el-form-item label="省" prop="province"><el-select v-model="addressForm.province" filterable @change="changeAddressProvince"><el-option v-for="item in provinces" :key="item.name" :label="item.name" :value="item.name" /></el-select></el-form-item><el-form-item label="市" prop="city"><el-select v-model="addressForm.city" filterable @change="changeAddressCity"><el-option v-for="item in addressCities" :key="item.name" :label="item.name" :value="item.name" /></el-select></el-form-item><el-form-item label="区县" prop="district"><el-select v-model="addressForm.district" filterable><el-option v-for="item in addressDistricts" :key="item.name" :label="item.name" :value="item.name" /></el-select></el-form-item></div>
        <el-form-item label="详细地址" prop="detailedAddress"><el-input v-model="addressForm.detailedAddress" maxlength="500" /></el-form-item>
        <div class="form-grid"><el-form-item label="邮政编码" prop="postalCode"><el-input v-model="addressForm.postalCode" maxlength="6" /></el-form-item><el-form-item label="当前通讯地址"><el-radio-group v-model="addressForm.correspondenceAddress"><el-radio :value="true">是</el-radio><el-radio :value="false">否</el-radio></el-radio-group></el-form-item></div>
        <el-alert v-if="editingAddress" title="保存不会覆盖历史地址，而是生成下一修订版本。" type="info" :closable="false" show-icon />
      </el-form>
      <template #footer><el-button @click="addressDrawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveAddress">{{ editingAddress ? '保存修订' : '添加' }}</el-button></template>
    </el-drawer>

    <el-dialog v-model="addressHistoryDialog" title="办公场所历史地址" width="min(1040px, calc(100vw - 80px))" append-to-body>
      <el-table :data="history" border stripe><el-table-column prop="revisionNumber" label="版本" width="80"><template #default="scope">R{{ scope.row.revisionNumber }}</template></el-table-column><el-table-column prop="officeName" label="办公场所" min-width="150" /><el-table-column prop="fullAddress" label="历史地址" min-width="340" /><el-table-column prop="postalCode" label="邮政编码" width="105" /><el-table-column prop="changedOn" label="变更日期" width="110" /><el-table-column label="收件地址" width="90"><template #default="scope">{{ scope.row.correspondenceAddress ? '是' : '否' }}</template></el-table-column><el-table-column label="记录时间" width="170"><template #default="scope">{{ dateTime(scope.row.createdAt) }}</template></el-table-column></el-table>
      <template #footer><el-button @click="addressHistoryDialog=false">关闭</el-button></template>
    </el-dialog>

    <el-dialog v-model="esignDialog" title="电子签章开户" width="660px" append-to-body>
      <div class="esign-intro"><el-icon><WarningFilled /></el-icon><div><strong>正式服务就绪检查</strong><p>{{ integration?.safetyPolicy || '正在读取正式适配器配置…' }}</p></div></div>
      <el-descriptions v-if="profile" :column="1" border><el-descriptions-item label="单位名称">{{ profile.fullName }}</el-descriptions-item><el-descriptions-item label="统一社会信用代码">{{ value(profile.unifiedCreditCode) }}</el-descriptions-item><el-descriptions-item label="接入状态"><el-tag type="warning">{{ integration ? integrationStateLabel(integration.state) : '检查中' }}</el-tag></el-descriptions-item></el-descriptions>
      <ul v-if="esignBlockers.length" class="blocker-list"><li v-for="item in esignBlockers" :key="item">{{ item }}</li></ul>
      <el-checkbox v-model="agreements.certificationAgreement">我已阅读并同意《认证服务协议》</el-checkbox>
      <el-checkbox v-model="agreements.digitalCertificateAgreement">我已阅读并同意《数字证书服务协议》</el-checkbox>
      <el-checkbox v-model="agreements.privacyPolicy">我已阅读并同意《隐私政策》</el-checkbox>
      <template #footer><el-button @click="esignDialog=false">取消</el-button><el-tooltip :disabled="esignReady" content="请先消除上方全部阻塞项" placement="top"><span><el-button type="primary" :loading="saving" :disabled="!esignReady" @click="submitEsign">提交开户检查</el-button></span></el-tooltip></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.organization-profile-page { display: flex; flex-direction: column; gap: 14px; }
.organization-profile-page > * { flex: 0 0 auto; }
.organization-profile-page > .page-heading { margin-bottom: 0; }
.heading-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.profile-hero { min-height: 138px; display: grid; grid-template-columns: 66px minmax(270px, 1.2fr) minmax(460px, 1.8fr) auto; align-items: center; gap: 18px; padding: 22px; color: #fff; background: linear-gradient(118deg, #07377f 0%, #135fca 57%, #2877df 100%); border-radius: 14px; box-shadow: 0 10px 28px rgba(7,55,127,.18); }
.profile-mark { width: 60px; height: 60px; display: grid; place-items: center; border: 1px solid rgba(255,255,255,.36); border-radius: 16px; background: rgba(255,255,255,.14); font-size: 28px; }
.profile-identity { min-width: 0; }.profile-identity > span { color: rgba(255,255,255,.72); font-size:13px; letter-spacing: .08em; }.profile-identity h2 { margin: 5px 0 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 20px; }.profile-identity .el-tag + .el-tag { margin-left: 6px; }
.hero-facts { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 13px 20px; margin: 0; }.hero-facts div { min-width: 0; }.hero-facts dt { color: rgba(255,255,255,.7); font-size:13px; }.hero-facts dd { margin: 4px 0 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; font-weight: 500; }
.profile-hero > .el-button { color: #fff; border-color: rgba(255,255,255,.65); background: rgba(255,255,255,.1); }
.summary-grid { display: grid; grid-template-columns: repeat(4,1fr); gap: 12px; }.summary-grid article { min-width: 0; padding: 17px 18px; background: #fff; border: 1px solid var(--line); border-radius: 11px; box-shadow: 0 3px 12px rgba(30,61,102,.04); }.summary-grid span,.summary-grid strong,.summary-grid small { display: block; }.summary-grid span { color: var(--muted); font-size: 13px; }.summary-grid strong { margin: 8px 0 4px; overflow: hidden; color: #233d61; font-size: 20px; text-overflow: ellipsis; white-space: nowrap; }.summary-grid small { color: #8b99ab; font-size:13px; }
.surface-card { background: #fff; border: 1px solid var(--line); border-radius: 12px; box-shadow: 0 3px 12px rgba(30,61,102,.04); overflow: hidden; }.section-heading { min-height: 70px; display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 14px 18px; border-bottom: 1px solid #edf1f6; }.section-heading h2 { margin: 0; padding-left: 10px; border-left: 3px solid var(--brand-600); font-size: 16px; }.section-heading p { margin: 5px 0 0; color: var(--muted); font-size:13px; }
.role-grid { display: grid; grid-template-columns: repeat(4,1fr); gap: 10px; padding: 15px 18px 18px; }.role-card { min-width: 0; display: grid; grid-template-columns: 44px minmax(0,1fr); gap: 5px 11px; align-items: center; padding: 13px; background: #f8fafd; border: 1px solid #e6ecf4; border-radius: 10px; }.role-card .el-avatar { grid-row: 1 / 3; display: grid; place-items: center; color: #4773a7; background: #e8f1fd; }.role-card .el-avatar :deep(.el-icon) { margin: 0; }.role-card div { min-width: 0; }.role-card span,.role-card strong,.role-card small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.role-card span { color: var(--muted); font-size:13px; }.role-card strong { margin-top: 3px; color: #253c5b; font-size: 14px; }.role-card small { margin-top: 3px; color: #7b899d; font-size:13px; }.role-card em { grid-column: 2; color: #536985; font-size:13px; font-style: normal; }
.content-grid { display: grid; grid-template-columns: minmax(360px,.85fr) minmax(520px,1.15fr); gap: 12px; }.license-body { min-height: 124px; display: grid; grid-template-columns: 48px minmax(0,1fr) auto auto; align-items: center; gap: 12px; padding: 16px 18px; }.license-icon { width: 48px; height: 48px; display: grid; place-items: center; color: var(--brand-600); background: var(--brand-100); border-radius: 10px; font-size: 22px; }.license-body strong,.license-body span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.license-body strong { color: #2b405e; font-size: 13px; }.license-body span { margin-top: 5px; color: var(--muted); font-size:13px; }.file-picker.compact { width: auto; min-width: 82px; justify-content: center; }
.qualification-list { padding: 8px 18px 14px; }.qualification-list article { display: grid; grid-template-columns: minmax(0,1fr) auto; gap: 4px 12px; padding: 10px 0; border-bottom: 1px solid #edf1f6; }.qualification-list article:last-child { border-bottom: 0; }.qualification-list strong,.qualification-list span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.qualification-list strong { color: #294262; font-size: 13px; }.qualification-list span { margin-top: 3px; color: var(--muted); font-size:13px; }.qualification-list p { grid-column: 1 / -1; margin: 3px 0 0; }.qualification-list p .el-tag { margin: 2px 4px 0 0; }
.address-section .el-table { --el-table-header-bg-color: #f7f9fc; font-size: 13px; }.revision-label { margin-left: 7px; color: var(--muted); font-size:13px; }
.organization-drawer :deep(.el-drawer__header) { margin-bottom: 0; padding-bottom: 14px; border-bottom: 1px solid var(--line); }.organization-drawer :deep(.el-drawer__footer) { padding-top: 12px; border-top: 1px solid var(--line); }.organization-drawer :deep(.el-tabs__header) { position: sticky; top: 0; z-index: 2; background: #fff; }.organization-drawer :deep(.el-select),.organization-drawer :deep(.el-date-editor),.organization-drawer :deep(.el-input-number) { width: 100%; }.form-grid.three { grid-template-columns: repeat(3,1fr); }.people-form-grid { margin-top: 18px; }
.esign-intro { display: flex; gap: 12px; margin-bottom: 14px; padding: 13px 14px; color: #8b5b0a; background: #fff8e9; border: 1px solid #f0d7aa; border-radius: 9px; }.esign-intro > .el-icon { flex: 0 0 auto; margin-top: 2px; font-size: 20px; }.esign-intro strong,.esign-intro p { display: block; margin: 0; }.esign-intro p { margin-top: 4px; font-size:13px; line-height: 1.6; }.blocker-list { margin: 14px 0; padding: 12px 14px 12px 34px; color: #805414; background: #fffaf0; border-radius: 8px; font-size: 13px; line-height: 1.8; }.esign-intro ~ .el-checkbox { display: block; margin: 9px 0; }
@media (max-width: 1450px) { .profile-hero { grid-template-columns: 60px minmax(250px,1fr) minmax(380px,1.4fr) auto; }.role-grid { grid-template-columns: repeat(2,1fr); }.content-grid { grid-template-columns: 1fr; } }
@media (max-width: 1220px) { .profile-hero { grid-template-columns: 60px 1fr auto; }.hero-facts { grid-column: 1 / -1; grid-template-columns: repeat(4,1fr); }.summary-grid { grid-template-columns: repeat(2,1fr); }.form-grid.three { grid-template-columns: 1fr 1fr; } }
</style>
