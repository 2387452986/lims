<script setup lang="ts">
import { Download, Link, Refresh, Search, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { useAuthStore } from '../stores/auth'
import { failureReasonLabel, statusLabel } from '../presentation'

interface CursorPage<T> { items: T[]; nextCursor?: string; hasMore: boolean; total: number }
interface SpecimenRow { id: string; commissionId: string; sampleNumber: string; commissionNumber: string; reportNumber?: string; detectionProject?: string; taskState?: string; conclusion?: string; customerName: string; projectNumber: string; projectName: string; constructionUnit?: string; structurePart?: string; commissionedOn: string; samplingDate?: string; moldingDate?: string; ageDays?: number; plannedTestDate: string; actualTestDate?: string; curingMethod?: string; strengthGrade?: string; chipCode?: string }
interface ReboundRow { id: string; commissionId: string; sampleNumber: string; commissionNumber: string; componentCount?: number; measurementAreaCount?: number; commissionedOn: string; detectionProject: string; specification?: string; strengthGrade?: string; ageDays?: number; structurePart?: string; customerName: string; projectNumber: string; projectName: string }
interface FieldRow { id: string; commissionId: string; taskNumber: string; projectName: string; detectionProject: string; startedAt?: string; completedAt?: string; testerName?: string; progress: string }
interface WarningRow { id: string; warningType: string; sourceType: string; sourceId: string; projectName?: string; commissionNumber?: string; commissionId?: string; content: string; occurredAt: string; state: string; handlerName?: string; handledAt?: string; handlingDescription?: string; attachmentCount: number; version: number; allowedActions: string[] }
interface Job { id: string; jobType: string; state: string; attemptCount: number; lastError?: string; resultFileId?: string; resourceType: string; version: number; createdAt: string; completedAt?: string; allowedActions: string[] }
interface StoredFile { id: string; originalName: string }

const route = useRoute(); const router = useRouter(); const auth = useAuthStore()
const moduleKey = computed(() => String(route.params.moduleKey))
const mode = computed(() => moduleKey.value === 'overdue-specimen-ledger' ? 'OVERDUE'
  : moduleKey.value === 'due-specimen-ledger' ? 'DUE'
    : moduleKey.value === 'rebound-assessment' ? 'REBOUND'
      : moduleKey.value === 'field-test-records' ? 'FIELD' : 'WARNING')
const title = computed(() => ({ OVERDUE: '试块逾期台账', DUE: '试块到期台账', REBOUND: '回弹评定单', FIELD: '现场检测记录', WARNING: '预警记录' }[mode.value]))
const description = computed(() => ({ OVERDUE: '跟踪已超过计划检测日且尚未完成检测的试块', DUE: '按委托、样品、龄期与检测状态核对所有到期计划', REBOUND: '汇总已确认回弹试验的构件和测区评定结果', FIELD: '记录现场检测的开始、结束、人员与进度', WARNING: '办理设备效期、检测不符合和样品逾期预警' }[mode.value]))
const canExport = computed(() => auth.user?.permissions.includes('EXPORT_DATA') && ['OVERDUE','REBOUND','FIELD'].includes(mode.value))
const canResolve = computed(() => auth.user?.permissions.includes('WARNING_RESOLVE'))
const loading = ref(false); const rows = ref<Array<SpecimenRow | ReboundRow | FieldRow | WarningRow>>([])
const total = ref(0); const nextCursor = ref(''); const cursorHistory = ref<string[]>([]); const pageSize = ref(20)
const jobsDrawer = ref(false); const jobs = ref<Job[]>([]); let jobTimer: number | undefined
const resolveDialog = ref(false); const selectedWarning = ref<WarningRow>(); const uploading = ref(false)
const resolveForm = reactive({ description: '', attachments: [] as StoredFile[] })
const filters = reactive({ dateRange: [] as string[], region: '', detectionProject: '', commissionNumber: '', sampleNumber: '', reportNumber: '', projectNumber: '', projectName: '', customerName: '', conclusion: '', taskState: '', curingMethod: '', strengthGrade: '', progress: '', taskNumber: '', testerName: '', warningType: '', warningState: '' })
const stateLabels: Record<string,string> = { UNASSIGNED:'待分配',ASSIGNED:'待接收',ACCEPTED:'已接收',IN_PROGRESS:'检测中',SUBMITTED:'待复核',RETURNED:'已退回',COMPLETED:'已完成',CANCELLED:'已取消',CONFORMING:'合格',NONCONFORMING:'不合格',PENDING:'待判定',OPEN:'待处置',RESOLVED:'已处置',READY:'排队中',RUNNING:'执行中',RETRY:'等待重试',FAILED:'失败' }
const warningLabels: Record<string,string> = { EQUIPMENT_VERIFICATION_EXPIRED:'设备检定到期',EQUIPMENT_CALIBRATION_EXPIRED:'设备校准到期',DETECTION_NONCONFORMING:'检测不合格',SAMPLE_TEST_OVERDUE:'样品检测逾期' }

function params(cursor = '') {
  const value = new URLSearchParams({ cursor, limit: String(pageSize.value) })
  const add = (key: string, data: string) => { if (data) value.set(key, data) }
  if (mode.value === 'WARNING') {
    if (filters.dateRange[0]) value.set('from', `${filters.dateRange[0]}T00:00:00+08:00`)
    if (filters.dateRange[1]) value.set('to', `${filters.dateRange[1]}T23:59:59+08:00`)
    add('warningType',filters.warningType); add('state',filters.warningState); add('q',filters.projectName)
  } else if (mode.value === 'FIELD') {
    add('from',filters.dateRange[0]); add('to',filters.dateRange[1]); add('progress',filters.progress)
    add('detectionProject',filters.detectionProject); add('taskNumber',filters.taskNumber)
    add('projectName',filters.projectName); add('testerName',filters.testerName)
  } else if (mode.value === 'REBOUND') {
    add('commissionedFrom',filters.dateRange[0]); add('commissionedTo',filters.dateRange[1])
    add('commissionNumber',filters.commissionNumber); add('sampleNumber',filters.sampleNumber)
    add('projectNumber',filters.projectNumber); add('projectName',filters.projectName); add('customerName',filters.customerName)
  } else {
    value.set('view',mode.value); add('commissionedFrom',filters.dateRange[0]); add('commissionedTo',filters.dateRange[1])
    for (const key of ['region','detectionProject','commissionNumber','sampleNumber','reportNumber','projectNumber','projectName','customerName','conclusion','taskState','curingMethod','strengthGrade'] as const) add(key,filters[key])
  }
  return value.toString()
}
function endpoint() { return mode.value === 'WARNING' ? '/api/warnings' : mode.value === 'FIELD' ? '/api/quality-ledgers/field-tests' : mode.value === 'REBOUND' ? '/api/quality-ledgers/rebound-assessments' : '/api/quality-ledgers/specimens' }
async function load(reset = false) {
  if (reset) cursorHistory.value=[]
  loading.value=true
  try { const page=await api<CursorPage<never>>(`${endpoint()}?${params(cursorHistory.value.at(-1) ?? '')}`); rows.value=page.items; total.value=page.total; nextCursor.value=page.nextCursor ?? '' }
  catch (error) { showError(error) } finally { loading.value=false }
}
function nextPage() { if (!nextCursor.value) return; cursorHistory.value.push(nextCursor.value); load() }
function previousPage() { if (!cursorHistory.value.length) return; cursorHistory.value.pop(); load() }
function reset() { Object.assign(filters,{ dateRange:[],region:'',detectionProject:'',commissionNumber:'',sampleNumber:'',reportNumber:'',projectNumber:'',projectName:'',customerName:'',conclusion:'',taskState:'',curingMethod:'',strengthGrade:'',progress:'',taskNumber:'',testerName:'',warningType:'',warningState:'' }); load(true) }
function showError(error: unknown) { ElMessage.error(errorMessage(error)) }
function formatTime(value?: string) { return value ? new Intl.DateTimeFormat('zh-CN',{ timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit' }).format(new Date(value)) : '—' }
function tagType(state?: string) { return ['COMPLETED','CONFORMING','RESOLVED'].includes(state ?? '') ? 'success' : ['NONCONFORMING','FAILED'].includes(state ?? '') ? 'danger' : ['OPEN','IN_PROGRESS','RETRY'].includes(state ?? '') ? 'warning' : 'info' }
function openFlow(row: SpecimenRow | ReboundRow | FieldRow | WarningRow) { if (row.commissionId) router.push(`/flow/${row.commissionId}`) }

function exportPayload() { return { ledgerType: mode.value === 'OVERDUE' ? 'OVERDUE_SPECIMEN' : mode.value, from:filters.dateRange[0] || null,to:filters.dateRange[1] || null,region:filters.region,detectionProject:filters.detectionProject,commissionNumber:filters.commissionNumber,sampleNumber:filters.sampleNumber,reportNumber:filters.reportNumber,projectNumber:filters.projectNumber,projectName:filters.projectName,customerName:filters.customerName,conclusion:filters.conclusion,taskState:filters.taskState,curingMethod:filters.curingMethod,strengthGrade:filters.strengthGrade,progress:filters.progress,taskNumber:filters.taskNumber,testerName:filters.testerName } }
async function queueExport() { try { await api('/api/quality-ledger-exports',{ method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify(exportPayload()) }); ElMessage.success('受控导出已排队'); await openJobs() } catch(error){ showError(error) } }
const jobResourceType = computed(() => `QUALITY_LEDGER_${mode.value === 'OVERDUE' ? 'OVERDUE_SPECIMEN' : mode.value}`)
async function loadJobs() { if (!canExport.value) return; jobs.value=await api<Job[]>(`/api/jobs?resourceType=${jobResourceType.value}&limit=30`) }
async function openJobs() { jobsDrawer.value=true; await loadJobs(); stopJobTimer(); jobTimer=window.setInterval(loadJobs,2000) }
function stopJobTimer() { if (jobTimer) window.clearInterval(jobTimer); jobTimer=undefined }
async function retryJob(job: Job) { try { const comment=(await ElMessageBox.prompt('请填写重试原因','重试导出',{ inputValidator:value=>Boolean(value.trim())||'重试原因不能为空' })).value; await api(`/api/jobs/${job.id}/transitions`,{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({action:'RETRY',expectedVersion:job.version,comment})}); await loadJobs() } catch(error){ if(error!=='cancel') showError(error) } }

function openResolve(row: WarningRow) { selectedWarning.value=row; Object.assign(resolveForm,{description:'',attachments:[]}); resolveDialog.value=true }
async function uploadEvidence(event: Event) { const input=event.target as HTMLInputElement; const file=input.files?.[0]; input.value=''; if(!file)return; uploading.value=true; try { const body=new FormData();body.append('file',file);body.append('purpose','WARNING_EVIDENCE'); resolveForm.attachments.push(await api<StoredFile>('/api/files',{method:'POST',body}));ElMessage.success('处置附件已安全上传') } catch(error){showError(error)} finally{uploading.value=false} }
async function resolveWarning() { if(!selectedWarning.value)return; try { await api(`/api/warnings/${selectedWarning.value.id}/transitions`,{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({action:'RESOLVE',expectedVersion:selectedWarning.value.version,description:resolveForm.description,attachmentFileIds:resolveForm.attachments.map(item=>item.id)})});resolveDialog.value=false;ElMessage.success('预警已处置并写入审计');await load() } catch(error){showError(error)} }

watch(moduleKey,()=>{reset();stopJobTimer()}); onMounted(()=>load(true)); onBeforeUnmount(stopJobTimer)
</script>

<template>
  <section class="page quality-ledger-page" v-loading="loading">
    <header class="page-heading"><div><h1>{{ title }}</h1><p>{{ description }}</p></div><div class="heading-actions"><el-button :icon="Refresh" @click="load()">刷新</el-button><el-button v-if="canExport" @click="openJobs">导出作业</el-button><el-button v-if="canExport" type="primary" :icon="Download" @click="queueExport">水印导出</el-button></div></header>
    <section class="ledger-filter-shell">
      <div class="ledger-filter-grid">
        <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" />
        <template v-if="['OVERDUE','DUE'].includes(mode)">
          <el-input v-model="filters.detectionProject" clearable placeholder="检测项目" /><el-input v-model="filters.commissionNumber" clearable placeholder="委托编号" /><el-input v-model="filters.sampleNumber" clearable placeholder="样品编号" />
          <el-input v-if="mode==='DUE'" v-model="filters.reportNumber" clearable placeholder="报告编号" /><el-input v-model="filters.projectNumber" clearable placeholder="工程编号" /><el-input v-model="filters.projectName" clearable placeholder="工程名称" />
          <el-input v-model="filters.customerName" clearable placeholder="委托单位" /><el-input v-if="mode==='OVERDUE'" v-model="filters.region" clearable placeholder="所属区域" />
          <el-select v-if="mode==='DUE'" v-model="filters.conclusion" clearable placeholder="结论"><el-option label="合格" value="CONFORMING" /><el-option label="不合格" value="NONCONFORMING" /><el-option label="待判定" value="PENDING" /></el-select>
          <el-select v-if="mode==='DUE'" v-model="filters.taskState" clearable placeholder="检测状态"><el-option v-for="item in ['UNASSIGNED','ASSIGNED','ACCEPTED','IN_PROGRESS','SUBMITTED','RETURNED','COMPLETED','CANCELLED']" :key="item" :label="stateLabels[item]" :value="item" /></el-select>
          <el-input v-if="mode==='DUE'" v-model="filters.curingMethod" clearable placeholder="养护方式" /><el-input v-if="mode==='DUE'" v-model="filters.strengthGrade" clearable placeholder="强度" />
        </template>
        <template v-else-if="mode==='REBOUND'"><el-input v-model="filters.commissionNumber" clearable placeholder="委托单编号" /><el-input v-model="filters.sampleNumber" clearable placeholder="样品编号" /><el-input v-model="filters.projectNumber" clearable placeholder="工程编号" /><el-input v-model="filters.projectName" clearable placeholder="工程名称" /><el-input v-model="filters.customerName" clearable placeholder="委托单位" /></template>
        <template v-else-if="mode==='FIELD'"><el-select v-model="filters.progress" clearable placeholder="检测进度"><el-option v-for="item in ['UNASSIGNED','ASSIGNED','ACCEPTED','IN_PROGRESS','SUBMITTED','RETURNED','COMPLETED','CANCELLED']" :key="item" :label="stateLabels[item]" :value="item" /></el-select><el-input v-model="filters.detectionProject" clearable placeholder="检测项目" /><el-input v-model="filters.taskNumber" clearable placeholder="任务单号" /><el-input v-model="filters.projectName" clearable placeholder="工程名称" /><el-input v-model="filters.testerName" clearable placeholder="检测人员" /></template>
        <template v-else><el-select v-model="filters.warningType" clearable placeholder="预警类型"><el-option v-for="(label,key) in warningLabels" :key="key" :label="label" :value="key" /></el-select><el-select v-model="filters.warningState" clearable placeholder="处理状态"><el-option label="待处置" value="OPEN" /><el-option label="已处置" value="RESOLVED" /></el-select><el-input v-model="filters.projectName" clearable placeholder="预警内容、工程或委托编号" /></template>
      </div>
      <div class="filter-actions"><el-button type="primary" :icon="Search" @click="load(true)">查询</el-button><el-button :icon="Refresh" @click="reset">重置</el-button></div>
    </section>

    <el-table v-if="['OVERDUE','DUE'].includes(mode)" :data="rows" stripe border class="ledger-table" @row-dblclick="openFlow">
      <el-table-column prop="sampleNumber" label="样品编号" width="170" fixed /><el-table-column prop="detectionProject" label="检测项目" min-width="190" show-overflow-tooltip /><el-table-column prop="taskState" label="任务状态" width="110" align="center"><template #default="scope"><el-tag :type="tagType(scope.row.taskState)" effect="plain">{{ stateLabels[scope.row.taskState] ?? scope.row.taskState ?? '未建任务' }}</el-tag></template></el-table-column><el-table-column prop="customerName" label="委托单位" min-width="200" show-overflow-tooltip /><el-table-column prop="projectNumber" label="工程编号" width="150" /><el-table-column prop="projectName" label="工程名称" min-width="220" show-overflow-tooltip /><el-table-column prop="constructionUnit" label="施工单位" min-width="190" show-overflow-tooltip /><el-table-column prop="structurePart" label="工程部位" min-width="160" show-overflow-tooltip /><el-table-column prop="commissionedOn" label="委托日期" width="115" /><el-table-column prop="samplingDate" label="取样日期" width="115" /><el-table-column prop="moldingDate" label="成型日期" width="115" /><el-table-column prop="ageDays" label="龄期" width="80"><template #default="scope">{{ scope.row.ageDays ? `${scope.row.ageDays} 天` : '—' }}</template></el-table-column><el-table-column prop="plannedTestDate" label="应检日期" width="115" /><el-table-column v-if="mode==='DUE'" prop="actualTestDate" label="检测日期" width="115" /><el-table-column v-if="mode==='DUE'" prop="curingMethod" label="养护方式" width="120" /><el-table-column v-if="mode==='DUE'" prop="strengthGrade" label="强度" width="100" /><el-table-column prop="chipCode" label="芯片码" width="150" /><el-table-column label="操作" width="100" fixed="right" align="center"><template #default="scope"><el-button link type="primary" @click="openFlow(scope.row)">证据链</el-button></template></el-table-column>
      <template #empty><el-empty description="暂无符合条件的试块记录" /></template>
    </el-table>
    <el-table v-else-if="mode==='REBOUND'" :data="rows" stripe border class="ledger-table" @row-dblclick="openFlow"><el-table-column prop="sampleNumber" label="样品编号" width="170" fixed /><el-table-column prop="commissionNumber" label="委托单编号" width="170" /><el-table-column prop="componentCount" label="构件总数" width="100" /><el-table-column prop="measurementAreaCount" label="测区总数" width="100" /><el-table-column prop="commissionedOn" label="委托日期" width="115" /><el-table-column prop="detectionProject" label="检测项目" min-width="180" /><el-table-column prop="specification" label="规格" width="120" /><el-table-column prop="strengthGrade" label="等级" width="110" /><el-table-column prop="ageDays" label="龄期" width="80" /><el-table-column prop="structurePart" label="结构部位" min-width="160" /><el-table-column prop="customerName" label="委托单位" min-width="200" /><el-table-column prop="projectNumber" label="工程编号" width="150" /><el-table-column prop="projectName" label="工程名称" min-width="220" /><el-table-column label="操作" width="100" fixed="right" align="center"><template #default="scope"><el-button link type="primary" @click="openFlow(scope.row)">证据链</el-button></template></el-table-column><template #empty><el-empty description="暂无已确认的回弹试验记录" /></template></el-table>
    <el-table v-else-if="mode==='FIELD'" :data="rows" stripe border class="ledger-table" @row-dblclick="openFlow"><el-table-column prop="taskNumber" label="任务单号" width="180" fixed /><el-table-column prop="projectName" label="工程名称" min-width="240" /><el-table-column prop="detectionProject" label="检测项目" min-width="200" /><el-table-column label="开始时间" width="180"><template #default="scope">{{ formatTime(scope.row.startedAt) }}</template></el-table-column><el-table-column label="结束时间" width="180"><template #default="scope">{{ formatTime(scope.row.completedAt) }}</template></el-table-column><el-table-column prop="testerName" label="检测人员" width="130" /><el-table-column prop="progress" label="检测进度" width="120"><template #default="scope"><el-tag :type="tagType(scope.row.progress)" effect="plain">{{ stateLabels[scope.row.progress] ?? scope.row.progress }}</el-tag></template></el-table-column><el-table-column label="操作" width="100" fixed="right" align="center"><template #default="scope"><el-button link type="primary" @click="openFlow(scope.row)">证据链</el-button></template></el-table-column><template #empty><el-empty description="暂无现场检测任务记录" /></template></el-table>
    <el-table v-else :data="rows" stripe border class="ledger-table"><el-table-column label="预警类型" width="155"><template #default="scope"><el-tag :type="scope.row.state==='OPEN'?'warning':'success'" effect="plain">{{ warningLabels[scope.row.warningType] ?? scope.row.warningType }}</el-tag></template></el-table-column><el-table-column label="预警时间" width="180"><template #default="scope">{{ formatTime(scope.row.occurredAt) }}</template></el-table-column><el-table-column prop="content" label="预警内容" min-width="330" show-overflow-tooltip /><el-table-column prop="projectName" label="所属工程" min-width="180" /><el-table-column label="处理状态" width="110" align="center"><template #default="scope"><el-tag :type="tagType(scope.row.state)" effect="plain">{{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column><el-table-column prop="handlingDescription" label="处理描述" min-width="220" show-overflow-tooltip /><el-table-column prop="attachmentCount" label="拍照记录" width="95"><template #default="scope">{{ scope.row.attachmentCount ? `${scope.row.attachmentCount} 个` : '—' }}</template></el-table-column><el-table-column label="处理时间" width="180"><template #default="scope">{{ formatTime(scope.row.handledAt) }}</template></el-table-column><el-table-column prop="handlerName" label="处理人员" width="120" /><el-table-column label="操作" width="110" fixed="right" align="center"><template #default="scope"><el-button v-if="scope.row.state==='OPEN' && canResolve" link type="primary" @click="openResolve(scope.row)">处理</el-button><el-button v-else-if="scope.row.commissionId" link type="primary" @click="openFlow(scope.row)">证据链</el-button><span v-else>—</span></template></el-table-column><template #empty><el-empty description="暂无符合条件的预警记录" /></template></el-table>
    <footer class="table-footer"><span>共 {{ total }} 条 · 第 {{ cursorHistory.length+1 }} 页</span><div><el-select v-model="pageSize" style="width:110px" @change="load(true)"><el-option :value="20" label="20 条/页" /><el-option :value="50" label="50 条/页" /><el-option :value="100" label="100 条/页" /></el-select><el-button :disabled="!cursorHistory.length" @click="previousPage">上一页</el-button><el-button :disabled="!nextCursor" @click="nextPage">下一页</el-button></div></footer>

    <el-drawer v-model="jobsDrawer" title="质量台账导出作业" size="700px" @closed="stopJobTimer"><el-alert title="导出将在后台执行，完成后可在此下载；失败作业可重试。" type="info" :closable="false" show-icon /><el-table :data="jobs" stripe border><el-table-column label="创建时间" width="180"><template #default="scope">{{ formatTime(scope.row.createdAt) }}</template></el-table-column><el-table-column prop="state" label="状态" width="110" align="center"><template #default="scope"><el-tag :type="tagType(scope.row.state)" effect="plain">{{ stateLabels[scope.row.state] ?? statusLabel(scope.row.state) }}</el-tag></template></el-table-column><el-table-column prop="attemptCount" label="尝试" width="70" /><el-table-column label="失败原因" min-width="210" show-overflow-tooltip><template #default="scope">{{ failureReasonLabel(scope.row.lastError, '质量台账导出失败') }}</template></el-table-column><el-table-column label="操作" width="130" align="center"><template #default="scope"><el-link v-if="scope.row.resultFileId" :href="`/api/files/${scope.row.resultFileId}`" :icon="Link">下载</el-link><el-button v-else-if="scope.row.state==='FAILED'" link type="primary" @click="retryJob(scope.row)">重试</el-button></template></el-table-column><template #empty><el-empty description="暂无导出作业" /></template></el-table></el-drawer>
    <el-dialog v-model="resolveDialog" title="处置预警记录" width="620px"><el-alert v-if="selectedWarning" :title="warningLabels[selectedWarning.warningType]" :description="selectedWarning.content" type="warning" :closable="false" show-icon /><el-form label-position="top"><el-form-item label="处理描述" required><el-input v-model="resolveForm.description" type="textarea" :rows="5" maxlength="2000" show-word-limit /></el-form-item><el-form-item label="拍照或依据附件（PNG、JPG、PDF，最多 20 个）"><label class="file-picker"><input type="file" accept="image/png,image/jpeg,application/pdf" @change="uploadEvidence" /><el-icon><Upload /></el-icon>{{ uploading ? '正在安全校验…' : '选择处置证据' }}</label><div class="evidence-files"><span v-for="file in resolveForm.attachments" :key="file.id">{{ file.originalName }}<el-button link type="danger" @click="resolveForm.attachments=resolveForm.attachments.filter(item=>item.id!==file.id)">移除</el-button></span></div></el-form-item></el-form><template #footer><el-button @click="resolveDialog=false">取消</el-button><el-button type="primary" :disabled="!resolveForm.description.trim()" @click="resolveWarning">确认处置</el-button></template></el-dialog>
  </section>
</template>

<style scoped>
.ledger-filter-shell{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:14px;padding:14px;background:#fff;border:1px solid var(--line);border-radius:10px 10px 0 0}.ledger-filter-grid{display:grid;grid-template-columns:repeat(4,minmax(150px,1fr));gap:10px}.ledger-filter-grid :deep(.el-date-editor){width:100%}.filter-actions{display:flex;align-items:flex-start;gap:8px}.ledger-table{flex:1 1 0;width:100%;height:0;min-height:0}.table-footer>div{display:flex;align-items:center;gap:8px}.evidence-files{display:grid;gap:4px;width:100%;margin-top:8px}.evidence-files span{display:flex;align-items:center;justify-content:space-between;padding:5px 8px;border-radius:6px;background:#f4f7fb;color:#52647d;font-size: 13px}
@media(max-width:1400px){.ledger-filter-grid{grid-template-columns:repeat(3,minmax(150px,1fr))}}@media(max-width:1180px){.ledger-filter-shell{grid-template-columns:1fr}.ledger-filter-grid{grid-template-columns:repeat(2,minmax(150px,1fr))}.filter-actions{justify-content:flex-end}}
</style>
