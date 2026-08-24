<script setup lang="ts">
import { ArrowRight, Bell, CircleCheckFilled, CircleCloseFilled, InfoFilled, Loading, Search, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { localDateString, workbenchGreeting } from '../presentation'
import { useAuthStore } from '../stores/auth'
import type { WorkbenchData, WorkflowStep } from '../types'

type NotificationItem = WorkbenchData['notifications'][number]

const loading = ref(true)
const router = useRouter()
const auth = useAuthStore()
const error = ref('')
const data = ref<WorkbenchData>()
const stageDrawer = ref(false)
const activityDrawer = ref(false)
const notificationDrawer = ref(false)
const notificationLoading = ref(false)
const allNotifications = ref<NotificationItem[]>([])
const selectedStep = ref<WorkflowStep>()
const statusFilter = ref('ALL')
const keyword = ref('')
const today = new Date()
const monthAgo = new Date(today)
monthAgo.setDate(today.getDate() - 30)
const dateRange = ref<[string, string]>([localDateString(monthAgo), localDateString(today)])
const currentTime = ref(new Date())
const businessDateFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric', weekday: 'long',
})
const businessTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})
let clockTimer: number | undefined

const stepIcon = (state: string) => state === 'COMPLETED' ? CircleCheckFilled : state === 'BLOCKED' ? CircleCloseFilled : state === 'ACTIVE' ? Loading : InfoFilled
const stepStateLabel = (state: string) => state === 'COMPLETED' ? '已完成' : state === 'ACTIVE' ? '进行中' : state === 'BLOCKED' ? '已阻塞' : '待开始'
const evidenceLabel = (status: string) => status === 'COMPLETE' ? '已齐全' : status === 'PARTIAL' ? '部分缺失' : '缺失'
const currentStage = computed(() => data.value?.steps.find((step) => step.state === 'ACTIVE' || step.state === 'BLOCKED'))
const unreadCount = computed(() => data.value?.notifications.filter((item) => !item.read).length ?? 0)
const greeting = computed(() => workbenchGreeting(currentTime.value))
const welcomeTitle = computed(() => auth.user?.displayName?.trim()
  ? `${greeting.value}，${auth.user.displayName.trim()}` : greeting.value)
const businessDate = computed(() => businessDateFormatter.format(currentTime.value))
const businessTime = computed(() => businessTimeFormatter.format(currentTime.value))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const query = new URLSearchParams({ status: statusFilter.value, from: dateRange.value[0], to: dateRange.value[1], q: keyword.value.trim() })
    data.value = await api<WorkbenchData>(`/api/workbench?${query}`)
  } catch (cause) {
    error.value = errorMessage(cause, '工作台加载失败')
  } finally {
    loading.value = false
  }
}

function inspectStage(step: WorkflowStep | undefined) {
  if (!step) return
  selectedStep.value = step
  stageDrawer.value = true
}

function openFlow(tab?: 'samples' | 'tasks' | 'tests' | 'reports') {
  const commission = data.value?.spotlightCommission
  if (!commission) return
  router.push({ path: `/flow/${commission.id}`, query: tab ? { tab } : undefined })
}

function openCommission(id: string, tab?: 'samples' | 'tasks' | 'tests' | 'reports') {
  router.push({ path: `/flow/${id}`, query: tab ? { tab } : undefined })
}

function chooseStep(step: WorkflowStep) {
  if (step.state === 'PENDING') {
    ElMessage.info('需先完成前置阶段')
    return
  }
  inspectStage(step)
}

async function openNotificationCenter() {
  notificationDrawer.value = true
  notificationLoading.value = true
  try {
    allNotifications.value = await api<NotificationItem[]>('/api/notifications')
  } catch (cause) {
    ElMessage.error(errorMessage(cause, '通知加载失败'))
  } finally {
    notificationLoading.value = false
  }
}

function notificationPath(item: NotificationItem) {
  if (item.category.includes('REPORT')) return item.category.includes('ISSUE') ? '/modules/report-issue' : '/modules/report-review'
  if (item.category.includes('TASK') || item.category.includes('TEST')) return '/modules/tasks'
  if (item.category.includes('EQUIPMENT')) return '/modules/equipment'
  return '/modules/commissions'
}

async function openNotification(item: NotificationItem) {
  if (!item.read) {
    try {
      await api(`/api/notifications/${item.id}/transitions`, {
        method: 'POST',
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ action: 'READ', expectedVersion: item.version, reasonCode: 'WORKBENCH_OPEN', comment: '从流程工作台打开通知' }),
      })
      item.read = true
      item.version += 1
      const summary = data.value?.notifications.find((value) => value.id === item.id)
      if (summary) { summary.read = true; summary.version = item.version }
    } catch (cause) {
      ElMessage.error(errorMessage(cause, '通知状态更新失败'))
      return
    }
  }
  notificationDrawer.value = false
  await router.push(notificationPath(item))
}

onMounted(() => {
  load()
  clockTimer = window.setInterval(() => { currentTime.value = new Date() }, 60_000)
})
onBeforeUnmount(() => {
  if (clockTimer !== undefined) window.clearInterval(clockTimer)
})
</script>

<template>
  <section class="workbench page" v-loading="loading">
    <header class="page-heading workbench-heading">
      <div><h1>流程工作台</h1></div>
      <div class="filters" aria-label="工作台筛选">
        <label>委托状态</label>
        <el-select v-model="statusFilter" style="width: 112px" @change="load">
          <el-option label="全部" value="ALL" /><el-option label="执行中" value="IN_PROGRESS" /><el-option label="已阻塞" value="BLOCKED" />
        </el-select>
        <label>委托日期</label>
        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" @change="load" />
        <el-input v-model="keyword" class="workbench-search" clearable :prefix-icon="Search" placeholder="编号 / 工程 / 客户" @keyup.enter="load" @clear="load" />
      </div>
    </header>

    <section class="workbench-welcome" aria-labelledby="workbench-welcome-title">
      <div class="workbench-welcome-copy">
        <span>{{ businessDate }}</span>
        <h2 id="workbench-welcome-title">{{ welcomeTitle }}</h2>
        <p>欢迎使用试验检测信息管理系统</p>
      </div>
      <time class="workbench-welcome-time" :datetime="currentTime.toISOString()">
        <strong>{{ businessTime }}</strong>
        <span>北京时间</span>
      </time>
    </section>

    <el-result v-if="error" icon="error" title="暂时无法加载工作台" :sub-title="error">
      <template #extra><el-button type="primary" @click="load">重新加载</el-button></template>
    </el-result>

    <template v-else-if="data">
      <section class="quick-action-surface" aria-label="快捷入口">
        <header><div><h2>快捷入口</h2></div></header>
        <div class="quick-action-grid">
          <button v-for="item in data.quickActions" :key="item.key" type="button" :class="`tone-${item.tone}`" @click="router.push(item.path)">
            <span>{{ item.label }}</span><small>{{ item.description }}</small><el-icon><ArrowRight /></el-icon>
          </button>
        </div>
      </section>

      <section class="workbench-section">
        <header class="section-heading"><div><h2>业务流转汇总</h2></div><span>总量 / 当前待处理</span></header>
        <div class="stage-count-grid">
          <button v-for="item in data.stageCounts" :key="item.key" type="button" @click="router.push(item.path)">
            <span>{{ item.label }}</span><strong>{{ item.total }}</strong><small>待处理 <b>{{ item.pending }}</b> 项</small><el-icon><ArrowRight /></el-icon>
          </button>
        </div>
      </section>

      <section class="alert-overview">
        <button v-for="item in data.alerts" :key="item.key" type="button" :class="{ warning: item.severity === 'WARNING' }" @click="router.push(item.path)">
          <span>{{ item.label }}</span><strong>{{ item.total }}</strong><small>{{ item.description }}</small><b>{{ item.warning }} 项预警</b>
        </button>
      </section>

      <section class="role-todo-section" aria-label="我的待办">
        <header class="section-heading"><div><h2>我的待办</h2></div><span>按当前账号职责显示</span></header>
        <div class="role-todo-grid">
          <article v-for="group in data.roleTodos" :key="group.key">
            <header><button type="button" @click="router.push(group.path)"><span>{{ group.label }}</span><strong>{{ group.total }}</strong><el-icon><ArrowRight /></el-icon></button></header>
            <button v-for="item in group.items.slice(0, 3)" :key="item.id" type="button" class="role-todo-item" @click="router.push(item.path)">
              <span><b :title="item.title">{{ item.title }}</b><el-tag size="small" effect="plain">{{ item.stateLabel }}</el-tag></span>
              <small><code :title="item.businessNumber">{{ item.businessNumber }}</code> · {{ item.dueAt }}</small>
              <em :title="item.secondary">{{ item.secondary }}</em>
            </button>
            <el-empty v-if="!group.items.length" description="暂无待办" :image-size="38" />
          </article>
        </div>
      </section>

      <section class="queue-grid">
        <article class="queue-panel">
          <header><div><h2>待流转委托</h2><span>{{ data.pendingCommissions.length }} 项正在队列中</span></div><el-button text type="primary" @click="router.push('/modules/commissions?flow=PENDING')">查看全部</el-button></header>
          <el-table :data="data.pendingCommissions" stripe height="300" @row-dblclick="(row: any) => openCommission(row.id)">
            <el-table-column prop="number" label="委托编号" width="150" show-overflow-tooltip />
            <el-table-column prop="detectionProjects" label="检测项目" min-width="150" show-overflow-tooltip />
            <el-table-column prop="projectName" label="工程名称" min-width="180" show-overflow-tooltip />
            <el-table-column prop="dueOn" label="计划完成" width="105" />
            <el-table-column label="操作" width="80" align="center"><template #default="scope"><el-button link type="primary" @click="openCommission(scope.row.id)">处理</el-button></template></el-table-column>
            <template #empty><el-empty description="暂无待流转委托" :image-size="52" /></template>
          </el-table>
        </article>

        <article class="queue-panel">
          <header><div><h2>待处理报告</h2><span>编制、审核、签发与发放</span></div><el-button text type="primary" @click="router.push('/modules/report-review')">查看全部</el-button></header>
          <el-table :data="data.pendingReports" stripe height="300" @row-dblclick="(row: any) => openCommission(row.commissionId, 'reports')">
            <el-table-column prop="number" label="报告编号" width="145" show-overflow-tooltip />
            <el-table-column prop="title" label="报告名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="projectName" label="工程名称" min-width="170" show-overflow-tooltip />
            <el-table-column label="动作" width="76"><template #default="scope"><el-tag effect="plain">{{ scope.row.actionLabel }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="80" align="center"><template #default="scope"><el-button link type="primary" @click="openCommission(scope.row.commissionId, 'reports')">处理</el-button></template></el-table-column>
            <template #empty><el-empty description="暂无待处理报告" :image-size="52" /></template>
          </el-table>
        </article>

        <article class="notice-panel">
          <header><div><h2>站内通知</h2><span>{{ unreadCount }} 条未读</span></div><el-button text type="primary" @click="openNotificationCenter">查看全部</el-button></header>
          <button v-for="item in data.notifications" :key="item.id" type="button" :class="{ unread: !item.read }" @click="openNotification(item)">
            <span><b>{{ item.title }}</b><time>{{ item.createdAt }}</time></span><small>{{ item.body }}</small>
          </button>
          <el-empty v-if="!data.notifications.length" description="暂无站内通知" :image-size="52" />
        </article>
      </section>

      <section class="recent-activity-bar">
        <header><div><h2>近期动态</h2></div><el-button text type="primary" @click="activityDrawer = true">查看全部动态</el-button></header>
        <div>
          <button v-for="event in data.recentEvents.slice(0, 5)" :key="event.id" type="button" @click="activityDrawer = true">
            <span>{{ event.title }}</span><small>{{ event.actor }} · {{ event.occurredAt }}</small>
          </button>
          <el-empty v-if="!data.recentEvents.length" description="当前数据范围内暂无审计动态" :image-size="44" />
        </div>
      </section>

      <template v-if="data.spotlightCommission">
        <section class="workflow-surface spotlight-surface">
          <div class="commission-overview">
            <div class="commission-number"><span>当前重点委托</span><strong>{{ data.spotlightCommission.number }}</strong></div>
            <dl><div><dt>工程名称</dt><dd>{{ data.spotlightCommission.projectName }}</dd></div><div><dt>客户名称</dt><dd>{{ data.spotlightCommission.customerName }}</dd></div><div><dt>委托日期</dt><dd>{{ data.spotlightCommission.commissionedOn }}</dd></div><div><dt>计划完成</dt><dd>{{ data.spotlightCommission.dueOn }}</dd></div><div><dt>委托人</dt><dd>{{ data.spotlightCommission.contact }}</dd></div></dl>
            <el-button plain @click="openFlow()">查看委托详情</el-button>
          </div>

          <ol class="workflow-rail" aria-label="检测流程">
            <li v-for="(step, index) in data.steps" :key="step.key" :class="step.state.toLowerCase()" @click="chooseStep(step)">
              <button type="button" :aria-current="step.state === 'ACTIVE' ? 'step' : undefined">
                <span class="step-index">{{ index + 1 }}</span><strong>{{ step.label }}</strong>
                <span class="step-state"><el-icon><component :is="stepIcon(step.state)" /></el-icon>{{ stepStateLabel(step.state) }}</span>
                <small>{{ step.date ?? `${step.count}/${step.total} 项` }}</small>
                <small v-if="step.total">{{ Math.round(step.count / step.total * 100) }}%</small>
              </button>
            </li>
          </ol>

          <div class="workflow-summary">
            <strong>流程总览</strong>
            <span><b>{{ data.summary.total }}</b>总阶段</span><span class="success"><b>{{ data.summary.completed }}</b>已完成</span><span class="primary"><b>{{ data.summary.active }}</b>进行中</span><span><b>{{ data.summary.pending }}</b>待开始</span><span class="danger"><b>{{ data.summary.blocked }}</b>阻塞项</span>
            <el-button type="primary" size="large" :disabled="!currentStage" @click="inspectStage(currentStage)">查看当前阶段</el-button>
          </div>
          <div v-if="data.transitionWarnings.length" class="transition-warning-strip" role="status">
            <el-icon><WarningFilled /></el-icon><strong>{{ data.transitionWarnings.length }} 项流转时限预警</strong>
            <span v-for="warning in data.transitionWarnings.slice(0, 3)" :key="warning.resourceId">{{ warning.stageLabel }} · {{ warning.businessNumber }} · {{ warning.status === 'OVERDUE' ? `逾期 ${warning.overdueDays} 天` : '24 小时内到期' }}</span>
            <el-button text type="warning" @click="router.push('/modules/transition-time-settings')">查看规则</el-button>
          </div>
        </section>

        <section class="workspace-grid">
          <article class="workspace-panel evidence-panel">
            <header><h2>资料完整性 <small>（{{ currentStage?.label }}阶段）</small></h2></header>
            <el-table :data="data.evidence" stripe>
              <el-table-column prop="name" label="资料名称" min-width="132" /><el-table-column label="状态" width="110" align="center"><template #default="scope"><span class="evidence-status" :class="scope.row.status.toLowerCase()"><el-icon><component :is="scope.row.status === 'COMPLETE' ? CircleCheckFilled : scope.row.status === 'PARTIAL' ? WarningFilled : CircleCloseFilled" /></el-icon>{{ evidenceLabel(scope.row.status) }}</span></template></el-table-column><el-table-column label="备注" width="110"><template #default="scope">已收集 {{ scope.row.collected }}/{{ scope.row.required }}</template></el-table-column>
            </el-table>
            <footer><el-button text type="primary" @click="openFlow('samples')">查看完整性详情</el-button></footer>
          </article>
          <article class="workspace-panel task-panel">
            <header><h2>当前阶段任务 <small>（{{ currentStage?.label }}）</small></h2></header>
            <el-table :data="data.tasks" stripe><el-table-column prop="project" label="检测项目" min-width="110" show-overflow-tooltip /><el-table-column prop="sampleNumber" label="样品编号" width="110" show-overflow-tooltip /><el-table-column prop="assignee" label="责任人" width="72" /><el-table-column prop="dueOn" label="计划完成" width="96" /><el-table-column prop="status" label="状态" width="78" align="center"><template #default="scope"><el-tag effect="plain" :type="scope.row.status === '已完成' ? 'success' : 'primary'">{{ scope.row.status }}</el-tag></template></el-table-column></el-table>
            <footer><span>共 {{ data.tasks.length }} 条</span><el-button text type="primary" @click="openFlow('tasks')">查看全部任务</el-button></footer>
          </article>
          <article class="workspace-panel timeline-panel">
            <header><h2>重点委托动态</h2></header>
            <el-timeline><el-timeline-item v-for="event in data.timeline" :key="`${event.occurredAt}-${event.title}`" :timestamp="event.occurredAt" :type="event.active ? 'primary' : 'info'" placement="top"><strong>{{ event.title }}</strong><p>责任人：{{ event.actor }}</p></el-timeline-item></el-timeline>
            <el-empty v-if="!data.timeline.length" description="暂无审计事件" :image-size="48" />
            <footer><el-button text type="primary" @click="activityDrawer = true">查看全部动态</el-button></footer>
          </article>
        </section>
      </template>
      <el-empty v-else description="当前筛选范围内暂无重点委托"><el-button type="primary" @click="router.push('/modules/commissions')">进入委托管理</el-button></el-empty>
    </template>

    <el-drawer v-model="stageDrawer" :title="selectedStep ? `${selectedStep.label}阶段` : '阶段详情'" size="440px">
      <div class="stage-detail" v-if="selectedStep"><el-alert :title="selectedStep.state === 'BLOCKED' ? '当前阶段存在阻塞项' : '阶段状态正常'" :type="selectedStep.state === 'BLOCKED' ? 'error' : 'success'" show-icon :closable="false" /><dl><div><dt>阶段状态</dt><dd>{{ stepStateLabel(selectedStep.state) }}</dd></div><div><dt>完成进度</dt><dd>{{ selectedStep.count }}/{{ selectedStep.total }}</dd></div></dl><el-button type="primary" class="drawer-action" @click="openFlow(selectedStep.key === 'sample' ? 'samples' : selectedStep.key === 'task' ? 'tasks' : selectedStep.key === 'test' ? 'tests' : ['editing','review','issue','archive'].includes(selectedStep.key) ? 'reports' : undefined)">进入{{ selectedStep.label }}</el-button></div>
    </el-drawer>

    <el-drawer v-model="notificationDrawer" title="站内通知" size="620px">
      <div v-loading="notificationLoading" class="notification-drawer-list"><button v-for="item in allNotifications" :key="item.id" type="button" :class="{ unread: !item.read }" @click="openNotification(item)"><span><b>{{ item.title }}</b><time>{{ item.createdAt }}</time></span><p>{{ item.body }}</p></button><el-empty v-if="!notificationLoading && !allNotifications.length" description="暂无通知" /></div>
    </el-drawer>

    <el-drawer v-model="activityDrawer" title="近期审计动态" size="680px">
      <el-timeline class="activity-drawer-timeline"><el-timeline-item v-for="event in data?.recentEvents ?? []" :key="event.id" :timestamp="event.occurredAt" placement="top"><strong>{{ event.title }}</strong><p>{{ event.actor }} · {{ event.resourceType }}</p><small v-if="event.comment">{{ event.comment }}</small></el-timeline-item></el-timeline><el-empty v-if="!data?.recentEvents.length" description="暂无审计动态" />
    </el-drawer>
  </section>
</template>
