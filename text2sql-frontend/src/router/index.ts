import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { firstPermittedRoute } from '@/utils/navigation'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** Routes reachable without a session (the login page). */
    public?: boolean
    /** @deprecated Legacy alias for `permission: 'access.manage'`. */
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
      meta: { title: '对话', permission: 'chat.use' },
    },
    {
      path: '/datasources',
      name: 'datasources',
      component: () => import('@/views/DataSourcesView.vue'),
      meta: { title: '数据源', permission: 'datasource.read' },
    },
    {
      path: '/settings/models',
      name: 'model-settings',
      component: () => import('@/views/ModelSettingsView.vue'),
      meta: { title: '模型配置', permission: 'model.manage' },
    },
    {
      path: '/admin/datasource-access',
      name: 'datasource-access',
      component: () => import('@/views/DataSourceAccessView.vue'),
      meta: { title: '数据源权限', permission: 'access.manage' },
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
      meta: { title: '知识库', permission: 'access.manage' },
    },
    {
      path: '/no-permission',
      name: 'no-permission',
      component: () => import('@/views/NoPermissionView.vue'),
      meta: { title: '暂无可用功能' },
    },
    // The About page was removed; keep old links and bookmarks working.
    { path: '/about', redirect: '/' },
  ],
})

// Session guard: rehydrate the auth store, send guests to /login and keep signed-in users out of
// it. Every gated route refreshes the effective profile before its permission is checked. Denied
// destinations fall through to the first permitted page, or to a stable page when none is available.
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.restore()

  if (to.meta.public !== true && !auth.isAuthenticated) {
    return { name: 'login' }
  }

  const firstAvailableDestination = () => {
    const path = firstPermittedRoute((permission) => auth.can(permission))
    // The path check is a final loop guard in case a route and the navigation map ever drift apart.
    return path !== null && path !== to.path ? { path } : { name: 'no-permission' }
  }

  if (to.meta.public === true && auth.isAuthenticated) {
    return firstAvailableDestination()
  }

  // `admin` remains a backwards-compatible alias, but it is permission-based too: the display-only
  // role claim is never consulted by this guard.
  const requiredPermission =
    to.meta.permission ?? (to.meta.admin === true ? 'access.manage' : undefined)
  if (requiredPermission) {
    await auth.refreshProfile()
    if (!auth.isAuthenticated) {
      return { name: 'login' }
    }
    if (!auth.can(requiredPermission)) {
      return firstAvailableDestination()
    }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title
  document.title = title ? `${title} · Text2SQL` : 'Text2SQL'
})

export default router
