<script setup lang="ts">
import { Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import DomainExportControl from '../components/DomainExportControl.vue'
import { useAuthStore } from '../stores/auth'

interface CursorPage<T> { items: T[]; nextCursor?: string; hasMore: boolean; total: number }
interface Row { id:string; commissionId:string; commissionNumber:string; projectNumber:string; projectName:string; customerName:string; detectionProjects:string; sampleNumber:string; sampleName:string; specification?:string; receivedQuantity:string; availableQuantity:string; unit:string; samplingDate?:string; moldingDate?:string; plannedTestDate?:string; actualTestDate?:string; ageDays?:number; curingMethod?:string; strengthGrade?:string; structurePart?:string; chipCode?:string; state:string; version:number; receivedAt?:string; updatedAt:string }

const route=useRoute(); const router=useRouter(); const auth=useAuthStore(); const moduleKey=computed(()=>String(route.params.moduleKey))
const definitions:Record<string,{view:string;title:string;description:string}>={
  samples:{view:'SAMPLES',title:'样品管理',description:'样品接收、拆分、交接、留样、返还与处置'},
  'sample-ledger':{view:'SAMPLE_LEDGER',title:'样品总台账',description:'按样品、委托、工程和检测项目追溯全生命周期'},
  'concrete-sample-ledger':{view:'CONCRETE',title:'混凝土台账',description:'按强度等级、成型日期、龄期和部位核对混凝土样品'},
  'management-ledger':{view:'MANAGEMENT',title:'管理台账',description:'集中核对样品数量守恒、流转状态和检测计划'},
  'retained-sample-ledger':{view:'RETAINED',title:'留样台账',description:'跟踪留样状态、样品余量和后续处置'},
  'sample-age-schedule':{view:'AGE_SCHEDULE',title:'样品龄期表',description:'按成型日期、龄期和计划检测日安排试验'},
}
const definition=computed(()=>definitions[moduleKey.value]??definitions['sample-ledger'])
const rows=ref<Row[]>([]); const loading=ref(false); const total=ref(0); const nextCursor=ref(''); const cursorHistory=ref<string[]>([]); const pageSize=ref(50)
const filters=reactive({q:'',state:String(route.query.state??''),dateRange:[] as string[]})
const stateLabels:Record<string,string>={EXPECTED:'待接收',RECEIVED:'已接收',HANDED_OVER:'已交接',IN_TEST:'检测中',RETAINED:'留样',RETURNED:'已返还',DISPOSED:'已处置'}
const canExport=computed(()=>auth.user?.permissions.includes('EXPORT_DATA'))
const exportPayload=computed(()=>({view:definition.value.view,q:filters.q,state:filters.state,from:filters.dateRange[0]||null,to:filters.dateRange[1]||null}))

function params(cursor='') { const value=new URLSearchParams({view:definition.value.view,q:filters.q,state:filters.state,cursor,limit:String(pageSize.value)}); if(filters.dateRange[0])value.set('from',filters.dateRange[0]);if(filters.dateRange[1])value.set('to',filters.dateRange[1]);return value.toString() }
async function load(reset=false){if(reset)cursorHistory.value=[];loading.value=true;try{const page=await api<CursorPage<Row>>(`/api/samples/ledger?${params(cursorHistory.value.at(-1)??'')}`);rows.value=page.items;total.value=page.total;nextCursor.value=page.nextCursor??''}catch(error){ElMessage.error(errorMessage(error,'样品台账加载失败'))}finally{loading.value=false}}
function reset(){Object.assign(filters,{q:'',state:'',dateRange:[]});load(true)}
function next(){if(nextCursor.value){cursorHistory.value.push(nextCursor.value);load()}}
function previous(){if(cursorHistory.value.length){cursorHistory.value.pop();load()}}
function openFlow(row:Row){router.push({path:`/flow/${row.commissionId}`,query:{tab:'samples'}})}
function formatTime(value?:string){return value?new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}).format(new Date(value)):'—'}
function stateType(value:string){return ['RECEIVED','HANDED_OVER','IN_TEST','RETAINED'].includes(value)?'success':value==='DISPOSED'?'info':value==='RETURNED'?'warning':'primary'}
watch(moduleKey,()=>{filters.state=String(route.query.state??'');reset()});watch(()=>route.query.state,(value)=>{filters.state=String(value??'');load(true)})
onMounted(()=>load(true))
</script>

<template>
  <section class="page module-page domain-queue-page">
    <header class="page-heading"><div><h1>{{ definition.title }}</h1><p>{{ definition.description }}</p></div><el-button type="primary" @click="router.push('/modules/commissions')">进入委托受理</el-button></header>
    <section class="domain-filter-bar">
      <el-input v-model="filters.q" clearable :prefix-icon="Search" placeholder="样品、委托、工程、客户或检测项目" @keyup.enter="load(true)" @clear="load(true)" />
      <el-select v-model="filters.state" clearable placeholder="全部样品状态"><el-option v-for="(label,key) in stateLabels" :key="key" :label="label" :value="key" /></el-select>
      <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="委托开始" end-placeholder="委托结束" />
      <div class="domain-filter-actions"><el-button type="primary" @click="load(true)">查询</el-button><el-button :icon="Refresh" @click="reset">重置</el-button><DomainExportControl v-if="canExport" endpoint="/api/samples/exports" resource-type="SAMPLE_LEDGER" :payload="exportPayload"/></div>
    </section>
    <el-table v-loading="loading" :data="rows" border stripe class="module-table domain-queue-table" row-key="id" @row-dblclick="openFlow">
      <el-table-column label="样品 / 委托" width="210" fixed="left"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.sampleNumber }}</strong><span>{{ scope.row.commissionNumber }}</span></div></template></el-table-column>
      <el-table-column label="样品信息" min-width="220"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.sampleName }}</strong><span>{{ scope.row.specification || '未填写规格' }} · {{ scope.row.availableQuantity }}/{{ scope.row.receivedQuantity }} {{ scope.row.unit }}</span></div></template></el-table-column>
      <el-table-column label="工程 / 客户" min-width="250"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.projectName }}</strong><span>{{ scope.row.projectNumber }} · {{ scope.row.customerName }}</span></div></template></el-table-column>
      <el-table-column label="检测项目 / 部位" min-width="230"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.detectionProjects || '待配置检测项目' }}</strong><span>{{ scope.row.structurePart || '未填写工程部位' }}</span></div></template></el-table-column>
      <el-table-column v-if="definition.view==='CONCRETE'||definition.view==='AGE_SCHEDULE'" label="成型 / 龄期" width="185"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.moldingDate || '—' }} · {{ scope.row.ageDays ? `${scope.row.ageDays} d` : '—' }}</strong><span>{{ scope.row.strengthGrade || '未填写强度等级' }} · {{ scope.row.curingMethod || '未填写养护方式' }}</span></div></template></el-table-column>
      <el-table-column label="计划 / 实际检测" width="190"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.plannedTestDate || '未安排' }}</strong><span>{{ scope.row.actualTestDate ? `实际 ${scope.row.actualTestDate}` : `收样 ${formatTime(scope.row.receivedAt)}` }}</span></div></template></el-table-column>
      <el-table-column label="状态" width="100" fixed="right" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)" effect="plain">{{ stateLabels[scope.row.state] }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="112" fixed="right" align="center"><template #default="scope"><el-button link type="primary" @click="openFlow(scope.row)">进入样品闭环</el-button></template></el-table-column>
      <template #empty><el-empty description="暂无符合条件的样品记录" /></template>
    </el-table>
    <footer class="table-footer domain-pagination"><span>共 {{ total }} 条 · 第 {{ cursorHistory.length+1 }} 页 · 双击可进入证据链</span><div><el-select v-model="pageSize" class="page-size-select" @change="load(true)"><el-option v-for="size in [20,50,100]" :key="size" :label="`${size} 条/页`" :value="size" /></el-select><el-button :disabled="!cursorHistory.length" @click="previous">上一页</el-button><el-button type="primary" plain :disabled="!nextCursor" @click="next">下一页</el-button></div></footer>
  </section>
</template>
