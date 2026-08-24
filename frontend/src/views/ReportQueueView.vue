<script setup lang="ts">
import { Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import DomainExportControl from '../components/DomainExportControl.vue'
import { useAuthStore } from '../stores/auth'
import { statusLabel } from '../presentation'
interface CursorPage<T>{items:T[];nextCursor?:string;hasMore:boolean;total:number}
interface Row{id:string;versionId:string;commissionId:string;reportNumber:string;title:string;commissionNumber:string;projectNumber:string;projectName:string;customerName:string;revision:number;revisionLabel:string;state:string;pdfStatus:string;signed:boolean;sealed:boolean;distributedCount:number;acknowledgedCount:number;archiveNumber?:string;version:number;updatedAt:string}
const route=useRoute();const router=useRouter();const auth=useAuthStore();const key=computed(()=>String(route.params.moduleKey))
const definitions:Record<string,{view:string;title:string;description:string;action:string}>={
  'report-editing':{view:'REPORT_EDITING',title:'报告流转',description:'编制受控内容、提交审核并处理退回',action:'编制报告'},'report-review':{view:'REPORT_REVIEW',title:'报告审核',description:'核对原始记录、版本快照与报告内容',action:'执行审核'},'report-issue':{view:'REPORT_ISSUE',title:'报告签发',description:'签发已批准的受控报告版本',action:'执行签发'},'report-seal':{view:'REPORT_SEAL',title:'报告签章',description:'使用已认证签名、机构章和启用方案完成盖章',action:'执行签章'},'report-distribution':{view:'REPORT_DISTRIBUTION',title:'报告发放',description:'登记收件人、受控副本和签收证据',action:'登记发放'},'report-print':{view:'REPORT_PRINT',title:'报告打印',description:'按委托打印策略申请一次性受控下载',action:'受控打印'},'report-archive':{view:'REPORT_ARCHIVE',title:'报告存档',description:'核对发放签收后生成不可变归档包',action:'归档核对'},'report-revision':{view:'REPORT_REVISION',title:'报告修订',description:'保持报告主编号，创建 R1、R2 修订链',action:'创建修订'},'report-ledger':{view:'REPORT_LEDGER',title:'报告台账',description:'按报告版本追溯签发、签章、发放和归档事实',action:'查看版本'},'void-reports':{view:'VOID_REPORTS',title:'作废报告',description:'查看已作废版本并按受控流程创建修订',action:'查看作废证据'},'report-change-requests':{view:'REPORT_CHANGE_REQUESTS',title:'变更申请',description:'从已签发报告进入有理由的版本修订流程',action:'处理变更'},'report-return-records':{view:'REPORT_RETURN_RECORDS',title:'退回记录',description:'核对审核退回原因并重新编制',action:'处理退回'},'report-search':{view:'REPORT_SEARCH',title:'报告查询',description:'按报告、委托、工程和客户查询受控版本',action:'查看报告'},
}
const definition=computed(()=>definitions[key.value]??definitions['report-search'])
const rows=ref<Row[]>([]);const loading=ref(false);const total=ref(0);const nextCursor=ref('');const cursors=ref<string[]>([]);const pageSize=ref(50)
const initialStates=()=>String(route.query.state??'').split(',').filter(Boolean);const filters=reactive({q:'',states:initialStates(),dateRange:[] as string[]})
const labels:Record<string,string>={DRAFT:'草稿',IN_REVIEW:'待审核',RETURNED:'已退回',APPROVED:'已批准',ISSUED:'已签发',DISTRIBUTED:'已发放',ARCHIVED:'已归档',VOID:'已作废',SUPERSEDED:'历史修订'};const pdfLabels:Record<string,string>={PENDING:'生成中',REVIEW:'审核版',FINAL:'最终版'}
const canExport=computed(()=>auth.user?.permissions.includes('EXPORT_DATA'));const exportPayload=computed(()=>({view:definition.value.view,q:filters.q,states:filters.states.join(','),from:filters.dateRange[0]||null,to:filters.dateRange[1]||null}))
function params(cursor=''){const value=new URLSearchParams({view:definition.value.view,q:filters.q,states:filters.states.join(','),cursor,limit:String(pageSize.value)});if(filters.dateRange[0])value.set('from',filters.dateRange[0]);if(filters.dateRange[1])value.set('to',filters.dateRange[1]);return value.toString()}
async function load(reset=false){if(reset)cursors.value=[];loading.value=true;try{const page=await api<CursorPage<Row>>(`/api/reports/queue?${params(cursors.value.at(-1)??'')}`);rows.value=page.items;total.value=page.total;nextCursor.value=page.nextCursor??''}catch(error){ElMessage.error(errorMessage(error,'报告队列加载失败'))}finally{loading.value=false}}
function reset(){Object.assign(filters,{q:'',states:[],dateRange:[]});load(true)}function next(){if(nextCursor.value){cursors.value.push(nextCursor.value);load()}}function previous(){if(cursors.value.length){cursors.value.pop();load()}}function openFlow(row:Row){router.push({path:`/flow/${row.commissionId}`,query:{tab:'reports'}})}
function format(value?:string){return value?new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}).format(new Date(value)):'—'}function stateType(value:string){return ['ISSUED','DISTRIBUTED','ARCHIVED'].includes(value)?'success':['VOID','RETURNED'].includes(value)?'danger':value==='SUPERSEDED'?'info':'warning'}
watch(key,()=>{filters.states=initialStates();load(true)});watch(()=>route.query.state,()=>{filters.states=initialStates();load(true)});onMounted(()=>load(true))
</script>
<template>
  <section class="page module-page domain-queue-page">
    <header class="page-heading"><div><h1>{{ definition.title }}</h1><p>{{ definition.description }}</p></div><el-button type="primary" @click="router.push('/modules/commissions')">进入委托流程</el-button></header>
    <section class="domain-filter-bar"><el-input v-model="filters.q" clearable :prefix-icon="Search" placeholder="报告、委托、工程或客户" @keyup.enter="load(true)" @clear="load(true)"/><el-select v-model="filters.states" multiple collapse-tags clearable placeholder="全部报告状态"><el-option v-for="(label,value) in labels" v-show="value!=='SUPERSEDED'" :key="value" :label="label" :value="value"/></el-select><el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="报告开始" end-placeholder="报告结束"/><div class="domain-filter-actions"><el-button type="primary" @click="load(true)">查询</el-button><el-button :icon="Refresh" @click="reset">重置</el-button><DomainExportControl v-if="canExport" endpoint="/api/reports/exports" resource-type="REPORT_QUEUE" :payload="exportPayload"/></div></section>
    <el-table v-loading="loading" :data="rows" border stripe class="module-table domain-queue-table" row-key="versionId" @row-dblclick="openFlow">
      <el-table-column label="报告 / 版本" width="210" fixed="left"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.reportNumber }}</strong><span>{{ scope.row.revisionLabel }} · {{ scope.row.title }}</span></div></template></el-table-column>
      <el-table-column label="工程 / 委托" min-width="250"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.projectName }}</strong><span>{{ scope.row.projectNumber }} · {{ scope.row.commissionNumber }}</span></div></template></el-table-column>
      <el-table-column label="客户" min-width="160" prop="customerName" show-overflow-tooltip/>
      <el-table-column label="PDF / 签章" width="175"><template #default="scope"><div class="cell-stack"><strong>{{ pdfLabels[scope.row.pdfStatus] }}</strong><span>{{ scope.row.signed?'已签名':'未签名' }} · {{ scope.row.sealed?'已盖章':'未盖章' }}</span></div></template></el-table-column>
      <el-table-column label="发放 / 签收" width="145"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.distributedCount }} 份</strong><span>{{ scope.row.acknowledgedCount }} 份已签收</span></div></template></el-table-column>
      <el-table-column label="归档 / 更新时间" width="205"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.archiveNumber || '未归档' }}</strong><span>{{ format(scope.row.updatedAt) }}</span></div></template></el-table-column>
      <el-table-column label="状态" width="100" fixed="right" align="center"><template #default="scope"><el-tag :type="stateType(scope.row.state)" effect="plain">{{ labels[scope.row.state] ?? statusLabel(scope.row.state) }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="106" fixed="right" align="center"><template #default="scope"><el-button link type="primary" @click="openFlow(scope.row)">{{ definition.action }}</el-button></template></el-table-column>
      <template #empty><el-empty description="暂无符合条件的报告记录"/></template>
    </el-table>
    <footer class="table-footer domain-pagination"><span>共 {{ total }} 条 · 第 {{ cursors.length+1 }} 页 · 签发版本禁止原地修改或删除</span><div><el-select v-model="pageSize" class="page-size-select" @change="load(true)"><el-option v-for="size in [20,50,100]" :key="size" :label="`${size} 条/页`" :value="size"/></el-select><el-button :disabled="!cursors.length" @click="previous">上一页</el-button><el-button type="primary" plain :disabled="!nextCursor" @click="next">下一页</el-button></div></footer>
  </section>
</template>
