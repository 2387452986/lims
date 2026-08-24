<script setup lang="ts">
import { Download, Link, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onBeforeUnmount, ref } from 'vue'
import { api, errorMessage } from '../api'
import { failureReasonLabel } from '../presentation'

interface Job{id:string;jobType:string;state:string;attemptCount:number;lastError?:string;resultFileId?:string;resourceType:string;version:number;createdAt:string;completedAt?:string;allowedActions:string[]}
const props=defineProps<{endpoint:string;resourceType:string;payload:Record<string,unknown>}>()
const drawer=ref(false);const loading=ref(false);const jobs=ref<Job[]>([]);let timer:number|undefined
const labels:Record<string,string>={READY:'排队中',RUNNING:'执行中',RETRY:'等待重试',COMPLETED:'已完成',FAILED:'失败'}
async function enqueue(){loading.value=true;try{await api(props.endpoint,{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify(props.payload)});ElMessage.success('受控导出已进入后台队列');await openJobs()}catch(error){ElMessage.error(errorMessage(error,'导出排队失败'))}finally{loading.value=false}}
async function loadJobs(){try{jobs.value=await api<Job[]>(`/api/jobs?resourceType=${encodeURIComponent(props.resourceType)}&limit=50`);if(jobs.value.some(item=>['READY','RUNNING','RETRY'].includes(item.state)))startTimer();else stopTimer()}catch(error){ElMessage.error(errorMessage(error,'导出作业加载失败'));stopTimer()}}
async function openJobs(){drawer.value=true;await loadJobs()}
async function retry(job:Job){try{const result=await ElMessageBox.prompt('请填写重试原因','重试导出作业',{inputValidator:value=>Boolean(value?.trim())||'必须填写重试原因'});await api(`/api/jobs/${job.id}/transitions`,{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({action:'RETRY',expectedVersion:job.version,comment:result.value})});ElMessage.success('已重新排队');loadJobs()}catch(error){if(error!=='cancel'&&error!=='close')ElMessage.error(errorMessage(error,'重试失败'))}}
function startTimer(){if(timer===undefined)timer=window.setInterval(loadJobs,1500)}function stopTimer(){if(timer!==undefined){window.clearInterval(timer);timer=undefined}}
function format(value?:string){return value?new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}).format(new Date(value)):'—'}function tag(value:string){return value==='COMPLETED'?'success':value==='FAILED'?'danger':['RUNNING','RETRY'].includes(value)?'warning':'info'}
onBeforeUnmount(stopTimer)
</script>
<template>
  <el-button :icon="Download" :loading="loading" @click="enqueue">导出</el-button><el-button @click="openJobs">作业</el-button>
  <el-drawer v-model="drawer" title="领域台账导出作业" size="min(720px,92vw)" @closed="stopTimer">
    <el-alert title="大列表通过 PostgreSQL 作业表异步导出；文件带操作人和时间水印，最多 50,000 行。" type="info" show-icon :closable="false"/>
    <div class="job-drawer-toolbar"><span>仅显示本人创建的作业；系统管理员可查看机构作业</span><el-button :icon="Refresh" @click="loadJobs">刷新</el-button></div>
    <el-table :data="jobs" border stripe><el-table-column label="创建时间" width="170"><template #default="scope">{{ format(scope.row.createdAt) }}</template></el-table-column><el-table-column label="状态" width="100" align="center"><template #default="scope"><el-tag :type="tag(scope.row.state)" effect="plain">{{ labels[scope.row.state] }}</el-tag></template></el-table-column><el-table-column prop="attemptCount" label="尝试" width="70"/><el-table-column label="失败原因" min-width="210" show-overflow-tooltip><template #default="scope">{{ failureReasonLabel(scope.row.lastError, '导出作业失败') }}</template></el-table-column><el-table-column label="操作" width="110" align="center"><template #default="scope"><el-link v-if="scope.row.resultFileId" :href="`/api/files/${scope.row.resultFileId}`" :icon="Link">下载</el-link><el-button v-else-if="scope.row.state==='FAILED'" link type="primary" @click="retry(scope.row)">重试</el-button></template></el-table-column><template #empty><el-empty description="暂无导出作业"/></template></el-table>
  </el-drawer>
</template>
