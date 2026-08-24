<script setup lang="ts">
import { Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import DomainExportControl from '../components/DomainExportControl.vue'
import { useAuthStore } from '../stores/auth'

interface CursorPage<T>{items:T[];nextCursor?:string;hasMore:boolean;total:number}
interface Row{id:string;commissionId:string;commissionNumber:string;projectNumber:string;projectName:string;customerName:string;taskNumber:string;sampleNumber:string;sampleName:string;parameterCode:string;parameterName:string;assigneeName:string;dueAt?:string;state:string;version:number;acceptedAt?:string;startedAt?:string;completedAt?:string;updatedAt:string}
const route=useRoute();const router=useRouter();const auth=useAuthStore();const moduleKey=computed(()=>String(route.params.moduleKey));const returned=computed(()=>moduleKey.value==='task-return-records')
const rows=ref<Row[]>([]);const loading=ref(false);const total=ref(0);const nextCursor=ref('');const cursors=ref<string[]>([]);const pageSize=ref(50)
const filters=reactive({q:'',state:String(route.query.state??''),dateRange:[] as string[]})
const labels:Record<string,string>={UNASSIGNED:'待分配',ASSIGNED:'待接收',ACCEPTED:'已接收',IN_PROGRESS:'检测中',SUBMITTED:'待复核',RETURNED:'已退回',COMPLETED:'已完成',CANCELLED:'已取消'}
const title=computed(()=>returned.value?'任务退回记录':'任务分配');const description=computed(()=>returned.value?'核对退回原因并进入原委托完成改派或修正':'分配、接收、拒绝、退回和改派均在委托冻结参数范围内执行')
const canExport=computed(()=>auth.user?.permissions.includes('EXPORT_DATA'));const exportPayload=computed(()=>({view:returned.value?'RETURNED':'TASKS',q:filters.q,state:filters.state,dueFrom:filters.dateRange[0]||null,dueTo:filters.dateRange[1]||null}))
function params(cursor=''){const value=new URLSearchParams({view:returned.value?'RETURNED':'TASKS',q:filters.q,state:filters.state,cursor,limit:String(pageSize.value)});if(filters.dateRange[0])value.set('dueFrom',filters.dateRange[0]);if(filters.dateRange[1])value.set('dueTo',filters.dateRange[1]);return value.toString()}
async function load(reset=false){if(reset)cursors.value=[];loading.value=true;try{const page=await api<CursorPage<Row>>(`/api/tasks/queue?${params(cursors.value.at(-1)??'')}`);rows.value=page.items;total.value=page.total;nextCursor.value=page.nextCursor??''}catch(error){ElMessage.error(errorMessage(error,'任务队列加载失败'))}finally{loading.value=false}}
function reset(){Object.assign(filters,{q:'',state:'',dateRange:[]});load(true)}function next(){if(nextCursor.value){cursors.value.push(nextCursor.value);load()}}function previous(){if(cursors.value.length){cursors.value.pop();load()}}
function openFlow(row:Row){router.push({path:`/flow/${row.commissionId}`,query:{tab:'tasks'}})}
function format(value?:string){return value?new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}).format(new Date(value)):'—'}
function tag(value:string){return value==='COMPLETED'?'success':value==='RETURNED'?'danger':['IN_PROGRESS','SUBMITTED'].includes(value)?'warning':['UNASSIGNED','CANCELLED'].includes(value)?'info':'primary'}
watch(moduleKey,()=>reset());watch(()=>route.query.state,(value)=>{filters.state=String(value??'');load(true)});onMounted(()=>load(true))
</script>
<template>
  <section class="page module-page domain-queue-page">
    <header class="page-heading"><div><h1>{{ title }}</h1><p>{{ description }}</p></div><el-button type="primary" @click="router.push('/modules/commissions')">查看委托</el-button></header>
    <section class="domain-filter-bar"><el-input v-model="filters.q" clearable :prefix-icon="Search" placeholder="任务、委托、样品、参数、工程、客户或人员" @keyup.enter="load(true)" @clear="load(true)"/><el-select v-model="filters.state" clearable placeholder="全部任务状态"><el-option v-for="(label,key) in labels" :key="key" :label="label" :value="key"/></el-select><el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="计划开始" end-placeholder="计划结束"/><div class="domain-filter-actions"><el-button type="primary" @click="load(true)">查询</el-button><el-button :icon="Refresh" @click="reset">重置</el-button><DomainExportControl v-if="canExport" endpoint="/api/tasks/exports" resource-type="TASK_QUEUE" :payload="exportPayload"/></div></section>
    <el-table v-loading="loading" :data="rows" border stripe class="module-table domain-queue-table" row-key="id" @row-dblclick="openFlow">
      <el-table-column label="任务 / 委托" width="205" fixed="left"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.taskNumber }}</strong><span>{{ scope.row.commissionNumber }}</span></div></template></el-table-column>
      <el-table-column label="样品 / 参数" min-width="240"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.sampleNumber }} · {{ scope.row.sampleName }}</strong><span>{{ scope.row.parameterCode }} · {{ scope.row.parameterName }}</span></div></template></el-table-column>
      <el-table-column label="工程 / 客户" min-width="250"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.projectName }}</strong><span>{{ scope.row.projectNumber }} · {{ scope.row.customerName }}</span></div></template></el-table-column>
      <el-table-column label="检测人员" width="120" prop="assigneeName"/>
      <el-table-column label="计划完成" width="160"><template #default="scope">{{ format(scope.row.dueAt) }}</template></el-table-column>
      <el-table-column label="执行时间" width="210"><template #default="scope"><div class="cell-stack"><strong>开始 {{ format(scope.row.startedAt) }}</strong><span>完成 {{ format(scope.row.completedAt) }}</span></div></template></el-table-column>
      <el-table-column label="状态" width="100" fixed="right" align="center"><template #default="scope"><el-tag :type="tag(scope.row.state)" effect="plain">{{ labels[scope.row.state] }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="94" fixed="right" align="center"><template #default="scope"><el-tooltip :content="returned?'处理退回任务':'进入任务处理'" placement="top"><el-button class="queue-row-action" link type="primary" @click="openFlow(scope.row)">{{ returned?'处理退回':'处理' }}</el-button></el-tooltip></template></el-table-column>
      <template #empty><el-empty :description="returned?'暂无任务退回记录':'暂无符合条件的任务'"/></template>
    </el-table>
    <footer class="table-footer domain-pagination"><span>共 {{ total }} 条 · 第 {{ cursors.length+1 }} 页 · 所有动作由后端状态机校验</span><div><el-select v-model="pageSize" class="page-size-select" @change="load(true)"><el-option v-for="size in [20,50,100]" :key="size" :label="`${size} 条/页`" :value="size"/></el-select><el-button :disabled="!cursors.length" @click="previous">上一页</el-button><el-button type="primary" plain :disabled="!nextCursor" @click="next">下一页</el-button></div></footer>
  </section>
</template>
