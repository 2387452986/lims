<script setup lang="ts">
import { Check, CopyDocument, Edit, Plus, Refresh, Search, Setting } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError, api, errorMessage } from '../api'

interface Theme {
  id: string; code: string; name: string; remark?: string; brand900: string; brand600: string
  brand100: string; surfaceBackground: string; density: 'COMPACT' | 'COMFORTABLE'; radiusPx: number
  revision: number; state: string; version: number; activatedAt?: string; updatedAt: string; allowedActions: string[]
}
interface ProjectOption { id: string; code: string; label: string; revision: number }
interface Briefing {
  id: string; detectionProjectRevisionId: string; projectCode: string; projectName: string; code: string
  projectRevision: number; title: string; briefingType: string; content: string; readSeconds: number; revision: number; state: string
  version: number; activatedAt?: string; updatedAt: string; allowedActions: string[]
}
interface TransitionSetting { id: string; stageCode: string; stageLabel: string; enabled: boolean; allowedDays: number; version: number; updatedAt: string; dirty?: boolean }
interface Warning { stageCode: string; stageLabel: string; resourceType: string; resourceId: string; businessNumber: string; summary: string; deadline: string; status: 'OVERDUE' | 'DUE_SOON'; overdueDays: number }
interface CursorPage<T> { items: T[]; nextCursor?: string; hasMore: boolean; total: number }

const route = useRoute()
const moduleKey = computed(() => String(route.params.moduleKey))
const loading = ref(false)
const saving = ref(false)
const themes = ref<Theme[]>([])
const briefings = ref<Briefing[]>([])
const transitionSettings = ref<TransitionSetting[]>([])
const warnings = ref<Warning[]>([])
const projectOptions = ref<ProjectOption[]>([])
const themeDrawer = ref(false)
const briefingDrawer = ref(false)
const briefingQuery = ref('')
const briefingState = ref('')
const briefingTotal = ref(0)
const briefingNextCursor = ref('')
const briefingCursors = ref<string[]>([])
const briefingPageSize = ref(50)
const selectedTheme = ref<Theme>()
const selectedBriefing = ref<Briefing>()

const themeForm = reactive({
  code: '', name: '', remark: '', brand900: '#07377F', brand600: '#1769E0', brand100: '#EAF2FF',
  surfaceBackground: '#F4F7FB', density: 'COMPACT' as 'COMPACT' | 'COMFORTABLE', radiusPx: 10, expectedVersion: undefined as number | undefined,
})
const briefingForm = reactive({
  detectionProjectRevisionId: '', code: '', title: '', briefingType: 'PROJECT_SAFETY', content: '',
  readSeconds: 30, expectedVersion: undefined as number | undefined,
})

const pageMeta = computed(() => moduleKey.value === 'desktop-themes'
  ? { eyebrow: '受控设计令牌', title: '桌面风格管理', description: '应用全机构桌面主题；已应用版本不可原地覆盖。' }
  : moduleKey.value === 'safety-briefings'
    ? { eyebrow: '任务安全', title: '安全交底配置', description: '按检测项目配置交底内容与最短阅读时间。' }
    : { eyebrow: '流程时效控制', title: '流转时间设置', description: '统一维护阶段时限，并显示真实在途业务预警。' })

const typeLabels: Record<string, string> = {
  PROJECT_SAFETY: '项目安全', EQUIPMENT_OPERATION: '设备操作', SITE_SAFETY: '现场安全', OTHER: '其他交底',
}

function stateType(state: string) { return state === 'ACTIVE' ? 'success' : state === 'DRAFT' ? 'warning' : 'info' }
function stateLabel(state: string) { return state === 'ACTIVE' ? '已应用' : state === 'DRAFT' ? '草稿' : '已停用' }
function formatTime(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function showError(error: unknown) { ElMessage.error(errorMessage(error)) }

async function load() {
  loading.value = true
  try {
    if (moduleKey.value === 'desktop-themes') themes.value = await api<Theme[]>('/api/system-experience/desktop-themes')
    else if (moduleKey.value === 'safety-briefings') await Promise.all([loadBriefings(), searchProjects('')])
    else {
      const [settings, activeWarnings] = await Promise.all([
        api<TransitionSetting[]>('/api/system-experience/transition-time-settings'),
        api<Warning[]>('/api/system-experience/transition-warnings'),
      ])
      transitionSettings.value = settings
      warnings.value = activeWarnings
    }
  } catch (error) { showError(error) } finally { loading.value = false }
}

async function loadBriefings(reset = false) {
  if (reset) briefingCursors.value = []
  const params = new URLSearchParams({
    q: briefingQuery.value.trim(), state: briefingState.value,
    limit: String(briefingPageSize.value), cursor: briefingCursors.value.at(-1) ?? '',
  })
  try {
    const page = await api<CursorPage<Briefing>>(`/api/system-experience/safety-briefings?${params}`)
    briefings.value = page.items; briefingTotal.value = page.total; briefingNextCursor.value = page.nextCursor ?? ''
  } catch (error) { showError(error) }
}

function nextBriefingPage() { if (briefingNextCursor.value) { briefingCursors.value.push(briefingNextCursor.value); void loadBriefings() } }
function previousBriefingPage() { if (briefingCursors.value.length) { briefingCursors.value.pop(); void loadBriefings() } }

async function searchProjects(query: string) {
  projectOptions.value = await api<ProjectOption[]>(`/api/system-experience/detection-projects?q=${encodeURIComponent(query.trim())}`)
}

function openTheme(theme?: Theme) {
  selectedTheme.value = theme
  Object.assign(themeForm, theme ? {
    code: theme.code, name: theme.name, remark: theme.remark ?? '', brand900: theme.brand900,
    brand600: theme.brand600, brand100: theme.brand100, surfaceBackground: theme.surfaceBackground,
    density: theme.density, radiusPx: theme.radiusPx, expectedVersion: theme.version,
  } : {
    code: '', name: '', remark: '', brand900: '#07377F', brand600: '#1769E0', brand100: '#EAF2FF',
    surfaceBackground: '#F4F7FB', density: 'COMPACT', radiusPx: 10, expectedVersion: undefined,
  })
  themeDrawer.value = true
}

async function saveTheme() {
  if (!themeForm.code || !themeForm.name) return ElMessage.warning('请填写风格代码和名称')
  saving.value = true
  try {
    if (selectedTheme.value) await api(`/api/system-experience/desktop-themes/${selectedTheme.value.id}`, { method: 'PUT', body: JSON.stringify(themeForm) })
    else await api('/api/system-experience/desktop-themes', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(themeForm) })
    themeDrawer.value = false; ElMessage.success('桌面风格草稿已保存'); await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function transitionTheme(theme: Theme, action: string) {
  const label = action === 'ACTIVATE' ? '应用' : '作废'
  try {
    await ElMessageBox.confirm(action === 'ACTIVATE' ? '应用后将替换当前机构正在使用的桌面风格，是否继续？' : '草稿作废后不可恢复，是否继续？', `${label}桌面风格`, { type: action === 'ACTIVATE' ? 'warning' : 'error' })
    await api(`/api/system-experience/desktop-themes/${theme.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: theme.version, reasonCode: 'THEME_CONTROL', comment: `${label}桌面风格` }),
    })
    if (action === 'ACTIVATE') window.dispatchEvent(new Event('lims-theme-changed'))
    ElMessage.success(`桌面风格已${label}`); await load()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

async function reviseTheme(theme: Theme) {
  try {
    const revised = await api<Theme>(`/api/system-experience/desktop-themes/${theme.id}/revisions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ expectedVersion: theme.version }),
    })
    await load(); openTheme(revised); ElMessage.success('已创建可编辑修订')
  } catch (error) { showError(error) }
}

function openBriefing(briefing?: Briefing) {
  selectedBriefing.value = briefing
  Object.assign(briefingForm, briefing ? {
    detectionProjectRevisionId: briefing.detectionProjectRevisionId, code: briefing.code, title: briefing.title,
    briefingType: briefing.briefingType, content: briefing.content, readSeconds: briefing.readSeconds,
    expectedVersion: briefing.version,
  } : { detectionProjectRevisionId: '', code: '', title: '', briefingType: 'PROJECT_SAFETY', content: '', readSeconds: 30, expectedVersion: undefined })
  if (briefing && !projectOptions.value.some((item) => item.id === briefing.detectionProjectRevisionId)) {
    projectOptions.value.unshift({ id: briefing.detectionProjectRevisionId, code: briefing.projectCode, label: briefing.projectName, revision: briefing.projectRevision })
  }
  briefingDrawer.value = true
}

async function saveBriefing() {
  if (!briefingForm.detectionProjectRevisionId || !briefingForm.code || !briefingForm.title || !briefingForm.content) return ElMessage.warning('请完整填写检测项目、代码、标题和交底内容')
  saving.value = true
  try {
    if (selectedBriefing.value) await api(`/api/system-experience/safety-briefings/${selectedBriefing.value.id}`, { method: 'PUT', body: JSON.stringify(briefingForm) })
    else await api('/api/system-experience/safety-briefings', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(briefingForm) })
    briefingDrawer.value = false; ElMessage.success('安全交底草稿已保存'); await loadBriefings(!selectedBriefing.value)
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function transitionBriefing(briefing: Briefing, action: string) {
  const label = action === 'ACTIVATE' ? '启用' : action === 'DISABLE' ? '停用' : '作废'
  try {
    await ElMessageBox.confirm(`确认${label}“${briefing.title}”？`, `${label}安全交底`, { type: action === 'DISCARD' ? 'error' : 'warning' })
    await api(`/api/system-experience/safety-briefings/${briefing.id}/transitions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action, expectedVersion: briefing.version, reasonCode: 'SAFETY_CONTROL', comment: `${label}安全交底` }),
    })
    ElMessage.success(`安全交底已${label}`); await loadBriefings()
  } catch (error) { if (error !== 'cancel') showError(error) }
}

async function reviseBriefing(briefing: Briefing) {
  try {
    const revised = await api<Briefing>(`/api/system-experience/safety-briefings/${briefing.id}/revisions`, {
      method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ expectedVersion: briefing.version }),
    })
    await loadBriefings(); openBriefing(revised); ElMessage.success('已创建可编辑修订')
  } catch (error) { showError(error) }
}

async function saveTransition(setting: TransitionSetting) {
  saving.value = true
  try {
    const updated = await api<TransitionSetting>(`/api/system-experience/transition-time-settings/${setting.id}`, {
      method: 'PUT', body: JSON.stringify({ enabled: setting.enabled, allowedDays: setting.allowedDays, expectedVersion: setting.version }),
    })
    Object.assign(setting, updated, { dirty: false }); ElMessage.success(`${setting.stageLabel}时限已更新`)
    warnings.value = await api<Warning[]>('/api/system-experience/transition-warnings')
  } catch (error) {
    if (error instanceof ApiError && error.code === 'VERSION_CONFLICT') await load()
    showError(error)
  } finally { saving.value = false }
}

watch(moduleKey, load)
onMounted(load)
</script>

<template>
  <section class="page system-experience-page" :class="{ 'briefing-page': moduleKey === 'safety-briefings' }" v-loading="loading">
    <header class="page-heading">
      <div><h1>{{ pageMeta.title }}</h1><p class="page-description">{{ pageMeta.description }}</p></div>
      <div class="heading-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button v-if="moduleKey === 'desktop-themes'" type="primary" :icon="Plus" @click="openTheme()">新增风格</el-button>
        <el-button v-if="moduleKey === 'safety-briefings'" type="primary" :icon="Plus" @click="openBriefing()">新增交底</el-button>
      </div>
    </header>

    <template v-if="moduleKey === 'desktop-themes'">
      <section class="experience-summary">
        <div><span>已登记版本</span><strong>{{ themes.length }}</strong></div><div><span>当前应用</span><strong>{{ themes.find(item => item.state === 'ACTIVE')?.name ?? '未配置' }}</strong></div><div><span>配置边界</span><strong>受控令牌</strong></div>
        <p>仅允许颜色、密度和圆角字段；不接受任意 CSS、脚本或外部资源。</p>
      </section>
      <div class="theme-grid">
        <article v-for="theme in themes" :key="theme.id" class="theme-card" :class="{ active: theme.state === 'ACTIVE' }">
          <div class="theme-token-preview" aria-label="桌面风格颜色令牌">
            <div><el-color-picker :model-value="theme.brand900" disabled /><span><small>顶部深色</small><code>{{ theme.brand900 }}</code></span></div>
            <div><el-color-picker :model-value="theme.brand600" disabled /><span><small>主品牌色</small><code>{{ theme.brand600 }}</code></span></div>
            <div><el-color-picker :model-value="theme.brand100" disabled /><span><small>品牌浅色</small><code>{{ theme.brand100 }}</code></span></div>
            <div><el-color-picker :model-value="theme.surfaceBackground" disabled /><span><small>页面底色</small><code>{{ theme.surfaceBackground }}</code></span></div>
          </div>
          <header><div><strong>{{ theme.name }}</strong><small>{{ theme.code }} · R{{ theme.revision }}</small></div><el-tag :type="stateType(theme.state)" effect="plain">{{ stateLabel(theme.state) }}</el-tag></header>
          <p>{{ theme.remark || '未填写说明' }}</p>
          <dl><div><dt>界面密度</dt><dd>{{ theme.density === 'COMPACT' ? '紧凑' : '舒适' }}</dd></div><div><dt>圆角</dt><dd>{{ theme.radiusPx }} px</dd></div><div><dt>更新时间</dt><dd>{{ formatTime(theme.updatedAt) }}</dd></div></dl>
          <footer>
            <el-button v-if="theme.state === 'DRAFT'" :icon="Edit" @click="openTheme(theme)">编辑</el-button>
            <el-button v-if="theme.state !== 'DRAFT'" :icon="CopyDocument" @click="reviseTheme(theme)">创建修订</el-button>
            <el-button v-if="theme.allowedActions.includes('DISCARD')" type="danger" plain @click="transitionTheme(theme, 'DISCARD')">作废草稿</el-button>
            <el-button v-if="theme.allowedActions.includes('ACTIVATE')" type="primary" :icon="Check" @click="transitionTheme(theme, 'ACTIVATE')">应用</el-button>
          </footer>
        </article>
      </div>
      <el-empty v-if="themes.length === 0" description="暂无桌面风格" />
    </template>

    <template v-else-if="moduleKey === 'safety-briefings'">
      <section class="module-toolbar">
        <el-input v-model="briefingQuery" :prefix-icon="Search" clearable placeholder="检测项目、交底代码或标题" @keyup.enter="loadBriefings(true)" @clear="loadBriefings(true)" />
        <el-select v-model="briefingState" clearable placeholder="全部状态" @change="loadBriefings(true)"><el-option label="草稿" value="DRAFT" /><el-option label="已启用" value="ACTIVE" /><el-option label="已停用" value="RETIRED" /></el-select>
        <el-button type="primary" @click="loadBriefings(true)">查询</el-button><el-button @click="briefingQuery='';briefingState='';loadBriefings(true)">重置</el-button>
        <span class="toolbar-spacer"></span><span class="record-count">共 {{ briefingTotal }} 条</span>
      </section>
      <el-alert title="启用后的交底将成为匹配检测项目任务的接收前置条件；计时与确认由服务端校验。" type="warning" show-icon :closable="false" />
      <el-table :data="briefings" stripe border class="module-table briefing-table">
        <el-table-column label="检测项目" min-width="250"><template #default="scope"><strong>{{ scope.row.projectName }}</strong><div class="cell-secondary">{{ scope.row.projectCode }}</div></template></el-table-column>
        <el-table-column label="交底配置" min-width="240"><template #default="scope"><strong>{{ scope.row.title }}</strong><div class="cell-secondary">{{ scope.row.code }} · R{{ scope.row.revision }}</div></template></el-table-column>
        <el-table-column label="类型" width="130"><template #default="scope">{{ typeLabels[scope.row.briefingType] }}</template></el-table-column>
        <el-table-column prop="readSeconds" label="最短阅读" width="110"><template #default="scope">{{ scope.row.readSeconds }} 秒</template></el-table-column>
        <el-table-column label="状态" width="100" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)" effect="plain">{{ stateLabel(scope.row.state) }}</el-tag></template></el-table-column>
        <el-table-column label="更新时间" width="180"><template #default="scope">{{ formatTime(scope.row.updatedAt) }}</template></el-table-column>
        <el-table-column label="操作" fixed="right" width="250" align="center"><template #default="scope">
          <el-button v-if="scope.row.state === 'DRAFT'" link type="primary" @click="openBriefing(scope.row)">编辑</el-button>
          <el-button v-if="scope.row.state !== 'DRAFT'" link type="primary" @click="reviseBriefing(scope.row)">创建修订</el-button>
          <el-button v-if="scope.row.allowedActions.includes('ACTIVATE')" link type="success" @click="transitionBriefing(scope.row,'ACTIVATE')">启用</el-button>
          <el-button v-if="scope.row.allowedActions.includes('DISABLE')" link type="warning" @click="transitionBriefing(scope.row,'DISABLE')">停用</el-button>
          <el-button v-if="scope.row.allowedActions.includes('DISCARD')" link type="danger" @click="transitionBriefing(scope.row,'DISCARD')">作废</el-button>
        </template></el-table-column>
        <template #empty><el-empty description="暂无符合条件的安全交底" /></template>
      </el-table>
      <footer class="table-footer"><span>共 {{ briefingTotal }} 条 · 第 {{ briefingCursors.length + 1 }} 页 · 配置发布后只允许创建修订</span><div><el-select v-model="briefingPageSize" class="page-size-select" @change="loadBriefings(true)"><el-option v-for="size in [20,50,100]" :key="size" :label="`${size} 条/页`" :value="size" /></el-select><el-button :disabled="!briefingCursors.length" @click="previousBriefingPage">上一页</el-button><el-button type="primary" plain :disabled="!briefingNextCursor" @click="nextBriefingPage">下一页</el-button></div></footer>
    </template>

    <template v-else>
      <section class="experience-summary transition-summary">
        <div><span>流转阶段</span><strong>{{ transitionSettings.length }}</strong></div><div><span>已启用</span><strong>{{ transitionSettings.filter(item => item.enabled).length }}</strong></div><div><span>当前预警</span><strong :class="{ danger: warnings.some(item => item.status === 'OVERDUE') }">{{ warnings.length }}</strong></div>
        <p>到期前 24 小时进入预警；逾期天数由业务记录时间与当前受控规则实时计算。</p>
      </section>
      <div class="transition-layout">
        <section class="settings-panel">
          <header><div><el-icon><Setting /></el-icon><span><strong>阶段时限</strong><small>每项独立乐观锁保存</small></span></div></header>
          <div v-for="setting in transitionSettings" :key="setting.id" class="transition-setting-row">
            <div><strong>{{ setting.stageLabel }}</strong><small>{{ setting.stageCode }}</small></div>
            <el-switch v-model="setting.enabled" inline-prompt active-text="启" inactive-text="停" @change="setting.dirty=true" />
            <el-input-number v-model="setting.allowedDays" :min="1" :max="3650" controls-position="right" @change="setting.dirty=true" /><span>天</span>
            <el-button type="primary" plain :disabled="!setting.dirty" :loading="saving" @click="saveTransition(setting)">保存</el-button>
          </div>
        </section>
        <section class="warning-panel">
          <header><div><strong>实时流转预警</strong><small>仅显示逾期或 24 小时内到期的真实业务记录</small></div><el-button :icon="Refresh" circle aria-label="刷新预警" @click="load" /></header>
          <div v-for="warning in warnings" :key="`${warning.resourceType}-${warning.resourceId}`" class="warning-row" :class="warning.status.toLowerCase()">
            <el-tag :type="warning.status === 'OVERDUE' ? 'danger' : 'warning'" effect="dark">{{ warning.status === 'OVERDUE' ? `逾期 ${warning.overdueDays} 天` : '即将到期' }}</el-tag>
            <div><strong>{{ warning.stageLabel }} · {{ warning.businessNumber }}</strong><span>{{ warning.summary }}</span><small>截止：{{ formatTime(warning.deadline) }}</small></div>
          </div>
          <el-empty v-if="warnings.length === 0" description="当前没有流转时限预警" :image-size="76" />
        </section>
      </div>
    </template>

    <el-drawer v-model="themeDrawer" :title="selectedTheme ? '编辑桌面风格草稿' : '新增桌面风格'" size="560px">
      <el-form label-position="top">
        <div class="form-grid"><el-form-item label="风格代码" required><el-input v-model="themeForm.code" maxlength="80" /></el-form-item><el-form-item label="风格名称" required><el-input v-model="themeForm.name" maxlength="160" /></el-form-item></div>
        <el-form-item label="说明"><el-input v-model="themeForm.remark" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
        <div class="theme-color-grid">
          <el-form-item label="顶部深色"><div class="color-field"><el-color-picker v-model="themeForm.brand900" /><el-input v-model="themeForm.brand900" /></div></el-form-item>
          <el-form-item label="主品牌色"><div class="color-field"><el-color-picker v-model="themeForm.brand600" /><el-input v-model="themeForm.brand600" /></div></el-form-item>
          <el-form-item label="品牌浅色"><div class="color-field"><el-color-picker v-model="themeForm.brand100" /><el-input v-model="themeForm.brand100" /></div></el-form-item>
          <el-form-item label="页面底色"><div class="color-field"><el-color-picker v-model="themeForm.surfaceBackground" /><el-input v-model="themeForm.surfaceBackground" /></div></el-form-item>
        </div>
        <div class="form-grid"><el-form-item label="界面密度"><el-radio-group v-model="themeForm.density"><el-radio-button value="COMPACT">紧凑</el-radio-button><el-radio-button value="COMFORTABLE">舒适</el-radio-button></el-radio-group></el-form-item><el-form-item label="圆角（4–16 px）"><el-slider v-model="themeForm.radiusPx" :min="4" :max="16" show-input /></el-form-item></div>
      </el-form>
      <template #footer><el-button @click="themeDrawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveTheme">保存草稿</el-button></template>
    </el-drawer>

    <el-drawer v-model="briefingDrawer" :title="selectedBriefing ? '编辑安全交底草稿' : '新增安全交底'" size="660px">
      <el-form label-position="top">
        <el-form-item label="检测项目版本" required><el-select v-model="briefingForm.detectionProjectRevisionId" filterable remote :remote-method="searchProjects" placeholder="输入项目代码或名称"><el-option v-for="item in projectOptions" :key="item.id" :label="`${item.code} · ${item.label} · R${item.revision}`" :value="item.id" /></el-select></el-form-item>
        <div class="form-grid"><el-form-item label="交底代码" required><el-input v-model="briefingForm.code" maxlength="80" /></el-form-item><el-form-item label="交底类型" required><el-select v-model="briefingForm.briefingType"><el-option v-for="(label,key) in typeLabels" :key="key" :label="label" :value="key" /></el-select></el-form-item></div>
        <el-form-item label="交底标题" required><el-input v-model="briefingForm.title" maxlength="240" /></el-form-item>
        <el-form-item label="交底内容" required><el-input v-model="briefingForm.content" type="textarea" :rows="12" maxlength="10000" show-word-limit /></el-form-item>
        <el-form-item label="最短阅读时间（秒）" required><el-input-number v-model="briefingForm.readSeconds" :min="5" :max="1800" controls-position="right" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="briefingDrawer=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveBriefing">保存草稿</el-button></template>
    </el-drawer>
  </section>
</template>

<style scoped>
.page-description{margin:7px 0 0;color:var(--muted);font-size:13px}.heading-actions{display:flex;gap:10px}.experience-summary{display:grid;grid-template-columns:160px minmax(220px,1fr) 160px minmax(320px,1.4fr);align-items:center;gap:0;margin-bottom:14px;border:1px solid var(--line);border-radius:var(--control-radius);background:#fff;overflow:hidden}.experience-summary>div{min-height:80px;padding:15px 18px;border-right:1px solid var(--line)}.experience-summary span,.experience-summary strong{display:block}.experience-summary span{color:var(--muted);font-size: 13px}.experience-summary strong{margin-top:7px;color:#213653;font-size:18px}.experience-summary p{margin:0;padding:16px 20px;color:var(--muted);font-size:13px;line-height:1.65}.experience-summary .danger{color:var(--danger)}
.theme-grid{display:grid;grid-template-columns:repeat(3,minmax(280px,1fr));gap:14px}.theme-card{padding:15px;border:1px solid var(--line);border-radius:var(--control-radius);background:#fff;box-shadow:0 3px 12px rgba(30,61,102,.04)}.theme-card.active{border-color:#8db7ef;box-shadow:0 0 0 2px color-mix(in srgb,var(--brand-600) 12%,transparent)}.theme-token-preview{display:grid;grid-template-columns:1fr 1fr;gap:10px;padding:12px;border:1px solid var(--line);border-radius:calc(var(--control-radius) * .8);background:#f8fafc}.theme-token-preview>div{display:flex;align-items:center;gap:9px;min-width:0}.theme-token-preview span,.theme-token-preview small,.theme-token-preview code{display:block}.theme-token-preview small{color:var(--muted);font-size:13px}.theme-token-preview code{margin-top:2px;color:#30445f;font-size:13px}.theme-card>header{display:flex;justify-content:space-between;align-items:flex-start;margin-top:15px}.theme-card header strong,.theme-card header small{display:block}.theme-card header small{margin-top:4px;color:var(--muted)}.theme-card>p{min-height:40px;color:var(--muted);font-size: 13px;line-height:1.6}.theme-card dl{display:grid;grid-template-columns:repeat(3,1fr);margin:12px -15px 0;border-block:1px solid var(--line)}.theme-card dl>div{padding:10px 15px;border-right:1px solid var(--line)}.theme-card dl>div:last-child{border-right:0}.theme-card dt{color:var(--muted);font-size:13px}.theme-card dd{margin:5px 0 0;color:#2b405d;font-size: 13px}.theme-card>footer{display:flex;justify-content:flex-end;gap:8px;padding-top:14px}
.briefing-table{margin-top:12px}.cell-secondary{margin-top:4px;color:var(--muted);font-size:13px}.transition-layout{display:grid;grid-template-columns:minmax(560px,1.08fr) minmax(430px,.92fr);gap:14px}.settings-panel,.warning-panel{border:1px solid var(--line);border-radius:var(--control-radius);background:#fff;overflow:hidden}.settings-panel>header,.warning-panel>header{min-height:64px;display:flex;align-items:center;justify-content:space-between;padding:13px 16px;border-bottom:1px solid var(--line)}.settings-panel>header>div{display:flex;align-items:center;gap:10px}.settings-panel header .el-icon{width:34px;height:34px;border-radius:9px;color:var(--brand-600);background:var(--brand-100)}.settings-panel header span,.settings-panel header strong,.settings-panel header small,.warning-panel header strong,.warning-panel header small{display:block}.settings-panel header small,.warning-panel header small{margin-top:3px;color:var(--muted);font-size:13px}.transition-setting-row{display:grid;grid-template-columns:minmax(160px,1fr) 50px 150px 28px 72px;align-items:center;gap:10px;min-height:64px;padding:10px 16px;border-bottom:1px solid #edf1f6}.transition-setting-row:last-child{border-bottom:0}.transition-setting-row>div strong,.transition-setting-row>div small{display:block}.transition-setting-row>div small{margin-top:3px;color:var(--muted);font-size:13px}.transition-setting-row>span{color:var(--muted)}.warning-row{display:flex;align-items:flex-start;gap:12px;padding:14px 16px;border-bottom:1px solid #edf1f6}.warning-row:last-child{border-bottom:0}.warning-row>div{min-width:0}.warning-row strong,.warning-row span,.warning-row small{display:block}.warning-row span{margin-top:5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#435875;font-size: 13px}.warning-row small{margin-top:5px;color:var(--muted)}.warning-row.overdue{background:#fff8f8}.warning-row.due_soon{background:#fffcf4}.theme-color-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 16px}.color-field{width:100%;display:grid;grid-template-columns:40px 1fr;gap:8px}.color-field .el-color-picker{width:40px}
@media(max-width:1400px){.theme-grid{grid-template-columns:repeat(2,minmax(280px,1fr))}.transition-layout{grid-template-columns:1fr}.experience-summary{grid-template-columns:150px minmax(200px,1fr) 140px minmax(260px,1fr)}}
</style>
