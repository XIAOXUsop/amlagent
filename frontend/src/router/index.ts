import { createRouter, createWebHistory } from 'vue-router'

/**
 * 前端路由：刷新后保留详情页 URL；各视图按需异步加载，维持代码分割。
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
    { path: '/reviews', component: () => import('../views/ReviewView.vue') },
    { path: '/eval', component: () => import('../views/EvalDashboard.vue') },
  ],
})

export default router
