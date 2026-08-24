import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from './layouts/AppLayout.vue'
import { useAuthStore } from './stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
    { path: '/change-password', name: 'change-password', component: () => import('./views/ChangePasswordView.vue') },
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', name: 'workbench', component: () => import('./views/WorkbenchView.vue') },
        { path: 'flow/:commissionId', name: 'commission-flow', component: () => import('./views/CommissionFlowView.vue') },
        { path: 'modules/commissions', name: 'commissions', component: () => import('./views/CommissionManagementView.vue') },
        { path: 'modules/:moduleKey', name: 'module', component: () => import('./views/ModuleRouterView.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.restore()
  if (!to.meta.public && !auth.user) return { name: 'login', query: { redirect: to.fullPath } }
  if (auth.user?.forcePasswordChange && to.name !== 'change-password') return { name: 'change-password' }
  if (to.name === 'login' && auth.user) return { name: 'workbench' }
})

export default router
