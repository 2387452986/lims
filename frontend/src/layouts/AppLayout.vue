<script setup lang="ts">
import {
  Bell, Collection, Connection, Document, Expand, Fold, Grid, House, List, Lock,
  Money, OfficeBuilding, Operation, Setting, Tools, User, UserFilled,
} from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { domainForModule, navigation, type NavigationDomain } from '../navigation'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)
const compactDomains = ref(window.innerWidth < 1400)
const unread = ref(0)
interface NotificationItem {
  id: string; title: string; body: string; read: boolean; version: number
  resourceType?: string; resourceId?: string
}
const notifications = ref<NotificationItem[]>([])
const profileVisible = ref(false)
interface ActiveTheme { brand900: string; brand600: string; brand100: string; surfaceBackground: string; density: 'COMPACT' | 'COMFORTABLE'; radiusPx: number }

const icons = {
  workbench: House,
  foundation: OfficeBuilding, equipment: Tools, business: Operation, ledgers: Collection,
  iot: Connection, finance: Money, system: Setting,
}
const workbenchDomain: NavigationDomain = { key: 'workbench', label: '流程工作台', groups: [
  { key: 'workbench-flow', label: '待办业务', items: [
    { key: 'commissions', label: '委托受理' }, { key: 'tasks', label: '任务派发' },
    { key: 'tests', label: '试验检测' },
  ] },
  { key: 'workbench-reports', label: '报告待办', items: [
    { key: 'report-editing', label: '报告编制' }, { key: 'report-review', label: '报告审核' },
    { key: 'report-issue', label: '报告签发' },
  ] },
  { key: 'workbench-alerts', label: '风险预警', items: [
    { key: 'qualifications', label: '人员资格' }, { key: 'equipment', label: '设备状态' },
    { key: 'nonconforming-samples', label: '检测异常' },
  ] },
] }
const activeMenu = computed(() => {
  if (route.name === 'workbench') return 'workbench'
  if (route.name === 'commissions' || route.name === 'commission-flow') return 'commissions'
  return String(route.params.moduleKey ?? '')
})
const domainIcon = (key: string) => icons[key as keyof typeof icons] ?? Grid

const visibleDomains = computed(() => navigation.map((domain) => ({
  ...domain,
  groups: domain.groups.map((group) => ({
    ...group,
    items: group.items.filter((item) => {
      if (domain.key !== 'system') return true
      if (item.key === 'audit') return auth.user?.permissions.includes('AUDIT_READ')
      return auth.user?.permissions.includes('SYSTEM_ADMIN')
    }),
  })).filter((group) => group.items.length > 0),
})).filter((domain) => domain.groups.length > 0))

const activeDomainKey = computed(() => {
  if (route.name === 'workbench') return 'workbench'
  if (route.name === 'commission-flow' || route.name === 'commissions') return 'business'
  return domainForModule(activeMenu.value)?.key ?? 'business'
})
const currentDomain = computed(() => activeDomainKey.value === 'workbench' ? workbenchDomain
  : visibleDomains.value.find((domain) => domain.key === activeDomainKey.value) ?? visibleDomains.value[0])
const activeGroupKeys = computed(() => {
  const activeGroup = currentDomain.value?.groups.find((group) => group.items.some((item) => item.key === activeMenu.value))
  return activeGroup ? [activeGroup.key] : [currentDomain.value?.groups[0]?.key].filter(Boolean) as string[]
})
const primaryDomains = computed(() => compactDomains.value ? visibleDomains.value.slice(0, 4) : visibleDomains.value)
const overflowDomains = computed(() => compactDomains.value ? visibleDomains.value.slice(4) : [])

function openDomain(domain: NavigationDomain | string) {
  const target = typeof domain === 'string' ? visibleDomains.value.find((item) => item.key === domain) : domain
  const first = target?.groups[0]?.items[0]
  if (first) router.push(first.key === 'commissions' ? '/modules/commissions' : `/modules/${first.key}`)
}

function updateViewport() { compactDomains.value = window.innerWidth < 1400 }

async function logout() {
  await auth.logout()
  await router.replace('/login')
}

async function loadNotifications() {
  const [count, items] = await Promise.all([
    api<{ count: number }>('/api/notifications/unread-count'),
    api<NotificationItem[]>('/api/notifications'),
  ])
  unread.value = count.count
  notifications.value = items.slice(0, 8)
}

async function loadTheme() {
  const theme = await api<ActiveTheme>('/api/system-experience/desktop-themes/active')
  const root = document.documentElement
  root.style.setProperty('--brand-900', theme.brand900)
  root.style.setProperty('--brand-600', theme.brand600)
  root.style.setProperty('--brand-100', theme.brand100)
  root.style.setProperty('--surface-bg', theme.surfaceBackground)
  root.style.setProperty('--control-radius', `${theme.radiusPx}px`)
  root.style.setProperty('--el-color-primary', theme.brand600)
  root.style.setProperty('--el-border-radius-base', `${Math.min(theme.radiusPx, 12)}px`)
  root.dataset.density = theme.density.toLowerCase()
}

async function openNotification(item: NotificationItem) {
  if (!item.read) {
    await api(`/api/notifications/${item.id}/transitions`, {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ action: 'READ', expectedVersion: item.version }),
    })
    await loadNotifications()
  }
  const target = item.resourceType === 'COMMISSION' && item.resourceId ? `/flow/${item.resourceId}`
    : item.resourceType === 'TEST_TASK' ? '/modules/tasks'
      : item.resourceType === 'REPORT' ? '/modules/report-review'
        : item.resourceType === 'EQUIPMENT_CERTIFICATE' ? '/modules/equipment-certificates'
          : item.resourceType === 'SAMPLE' ? '/modules/samples' : ''
  if (target) await router.push(target)
}

onMounted(() => {
  window.addEventListener('resize', updateViewport)
  window.addEventListener('lims-theme-changed', loadTheme)
  loadTheme().catch(() => undefined)
  loadNotifications().catch(() => undefined)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', updateViewport)
  window.removeEventListener('lims-theme-changed', loadTheme)
})
</script>

<template>
  <div class="app-shell" :class="{ 'is-collapsed': collapsed }">
    <header class="topbar">
      <div class="brand">
        <img src="/brand/institution-logo.svg" alt="机构标志" />
        <strong>澜川智检科技有限公司</strong>
        <span class="brand-divider" aria-hidden="true"></span>
        <span>试验检测信息管理系统</span>
      </div>
      <div class="topbar-actions">
        <el-popover placement="bottom-end" :width="360" trigger="click" @show="loadNotifications">
          <template #reference>
            <el-button class="notification-button" text aria-label="通知">
              <el-badge :value="unread" :hidden="unread === 0"><el-icon><Bell /></el-icon></el-badge>
            </el-button>
          </template>
          <section class="notification-popover">
            <header><strong>站内通知</strong><span>{{ unread }} 条未读</span></header>
            <button v-for="item in notifications" :key="item.id" type="button" :class="{ unread: !item.read }" @click="openNotification(item)">
              <strong>{{ item.title }}</strong><span>{{ item.body }}</span>
            </button>
            <el-empty v-if="notifications.length === 0" description="暂无通知" :image-size="54" />
          </section>
        </el-popover>
        <el-dropdown trigger="click">
          <button class="user-menu" type="button">
            <el-avatar :size="32"><el-icon><UserFilled /></el-icon></el-avatar>
            <span>{{ auth.user?.displayName }}</span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="profileVisible = true"><el-icon><User /></el-icon>个人信息</el-dropdown-item>
              <el-dropdown-item @click="router.push({ path:'/change-password', query:{ returnTo:route.fullPath } })"><el-icon><Lock /></el-icon>修改密码</el-dropdown-item>
              <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <nav class="domain-nav" aria-label="业务域导航">
      <button type="button" :class="{ active: activeDomainKey === 'workbench' }" @click="router.push('/')">
        <el-icon><House /></el-icon><span>流程工作台</span>
      </button>
      <button v-for="domain in primaryDomains" :key="domain.key" type="button"
              :class="{ active: activeDomainKey === domain.key }" @click="openDomain(domain)">
        <el-icon><component :is="domainIcon(domain.key)" /></el-icon><span>{{ domain.label }}</span>
      </button>
      <el-dropdown v-if="overflowDomains.length" trigger="click" @command="openDomain(String($event))">
        <button type="button" class="domain-more" :class="{ active: overflowDomains.some((item) => item.key === activeDomainKey) }">
          <el-icon><Grid /></el-icon><span>更多业务</span>
        </button>
        <template #dropdown><el-dropdown-menu>
          <el-dropdown-item v-for="domain in overflowDomains" :key="domain.key" :command="domain.key">
            <el-icon><component :is="domainIcon(domain.key)" /></el-icon>{{ domain.label }}
          </el-dropdown-item>
        </el-dropdown-menu></template>
      </el-dropdown>
    </nav>

    <aside class="sidebar">
      <div class="sidebar-domain">
        <el-icon><component :is="domainIcon(currentDomain?.key ?? '')" /></el-icon>
        <div><span>当前业务域</span><strong>{{ currentDomain?.label }}</strong></div>
      </div>
      <el-scrollbar class="sidebar-scroll">
        <el-menu :key="activeDomainKey" :default-active="activeMenu" :default-openeds="activeGroupKeys"
                 :collapse="collapsed" :collapse-transition="false" router>
          <el-sub-menu v-for="group in currentDomain?.groups" :key="group.key" :index="group.key">
            <template #title>
              <el-icon><List /></el-icon><span>{{ group.label }}</span>
            </template>
            <el-menu-item v-for="item in group.items" :key="item.key" :index="item.key"
                          :route="item.key === 'commissions' ? '/modules/commissions' : `/modules/${item.key}`">
              <span>{{ item.label }}</span><i v-if="item.external" class="external-dot" title="需要外部协议与环境"></i>
            </el-menu-item>
          </el-sub-menu>
        </el-menu>
      </el-scrollbar>
      <button class="collapse-button" type="button" @click="collapsed = !collapsed">
        <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon><span>{{ collapsed ? '展开菜单' : '收起菜单' }}</span>
      </button>
    </aside>

    <main class="main-content"><RouterView /></main>
    <el-dialog v-model="profileVisible" title="个人信息" width="440px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="姓名">{{ auth.user?.displayName }}</el-descriptions-item>
        <el-descriptions-item label="账号">{{ auth.user?.username }}</el-descriptions-item>
        <el-descriptions-item label="部门">{{ auth.user?.departmentName }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ auth.user?.roles.join('、') }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>
