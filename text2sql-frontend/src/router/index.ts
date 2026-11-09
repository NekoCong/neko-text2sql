import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** Routes reachable without a session (the login page). */
    public?: boolean
    /** Routes only administrators may open. */
    admin?: boolean
    /** Routes gated by a specific permission key, checked via `auth.can(...)`. */
    permission?: string
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '登录', public: true },
    },
    {
      path: '/',
      name: 'chat',
      component: () => import('@/views/ChatView.vue'),
      meta: { title: '对话' },
    },
    {
      path: '/datasources',
      name: 'datasources',
      component: () => import('@/views/DataSourcesView.vue'),
      meta: { title: '数据源' },
    },
    {
      path: '/settings/models',
      name: 'model-settings',
      component: () => import('@/views/ModelSettingsView.vue'),
      meta: { title: '模型配置' },
    },
    {
      path: '/admin/datasource-access',
      name: 'datasource-access',
      component: () => import('@/views/DataSourceAccessView.vue'),
      meta: { title: '数据源权限', admin: true },
    },
    {
      path: '/admin/access-control',
      name: 'access-control',
      component: () => import('@/views/AccessControlView.vue'),
      meta: { title: '权限管理', permission: 'access.manage' },
    },
    {
      path: '/knowledge',
      name: 'knowledge',
      component: () => import('@/views/KnowledgeView.vue'),
      meta: { title: '知识库', admin: true },
    },
    // The About page was removed; keep old links and bookmarks working.
    { path: '/about', redirect: '/' },
  ],
})

// Session guard: rehydrate the auth store (so a page refresh keeps the session), send
// guests to /login and keep signed-in users out of it. A route carrying `meta.permission`
// refreshes the profile first, so a stale localStorage snapshot can never grant (or deny)
// access against the server's current permission set.
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.restore()
  if (to.meta.public !== true && !auth.isAuthenticated) {
    return { name: 'login' }
  }
  if (to.meta.public === true && auth.isAuthenticated) {
    return { name: 'chat' }
  }
  if (to.meta.permission) {
    await auth.refreshProfile()
    if (!auth.can(to.meta.permission)) {
      return { name: 'chat' }
    }
  }
  if (to.meta.admin === true && auth.user?.role !== 'admin') {
    return { name: 'chat' }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title
  document.title = title ? `${title} · Text2SQL` : 'Text2SQL'
})

export default router
