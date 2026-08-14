import { createRouter, createWebHistory } from 'vue-router'
import { currentUser } from '../auth'

/**
 * 前端路由：刷新后保留详情页 URL；各视图按需异步加载，维持代码分割。
 * meta.roles 仅用于前端体验（菜单/守卫）；后端 @PreAuthorize 仍是最终安全边界。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/cases' },
    { path: '/cases', component: () => import('../views/CaseDashboard.vue') },
    {
      path: '/cases/:id',
      component: () => import('../views/CaseDetailView.vue'),
      props: (route) => ({ caseId: Number(route.params.id) }),
    },
    {
      path: '/reviews',
      component: () => import('../views/ReviewView.vue'),
      meta: { roles: ['REVIEWER', 'ADMIN'] },
    },
    {
      path: '/eval',
      component: () => import('../views/EvalDashboard.vue'),
      meta: { roles: ['ADMIN'] },
    },
  ],
})

// 角色守卫：仅做前端体验控制，越权仍由后端拦截
router.beforeEach((to) => {
  const roles = to.meta.roles as string[] | undefined
  if (roles && roles.length > 0) {
    const role = currentUser.value?.role
    if (!role || !roles.includes(role)) {
      return '/cases'
    }
  }
})

export default router
