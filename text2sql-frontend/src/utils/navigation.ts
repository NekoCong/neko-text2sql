/**
 * Permission-gated application destinations, in landing-page priority order.
 *
 * This module deliberately has no Vue, Router or Pinia imports so the navigation mapping and
 * fallback selection can be tested directly with the Node test runner.
 */
export type NavigationPlacement = 'menu' | 'dropdown'

export interface AppNavigationEntry {
  name: string
  path: string
  label: string
  permission: string
  placement: NavigationPlacement
}

export type PermissionChecker = (permission: string) => boolean

/**
 * Every user-facing destination exposed by the shell. The legacy datasource-access route is
 * intentionally absent: it remains routable for old bookmarks but is no longer a navigation item.
 */
export const APP_NAVIGATION_ENTRIES: readonly AppNavigationEntry[] = [
  { name: 'chat', path: '/', label: '对话', permission: 'chat.use', placement: 'menu' },
  {
    name: 'datasources',
    path: '/datasources',
    label: '数据源',
    permission: 'datasource.read',
    placement: 'menu',
  },
  {
    name: 'model-settings',
    path: '/settings/models',
    label: '模型配置',
    permission: 'model.manage',
    placement: 'dropdown',
  },
  {
    name: 'knowledge',
    path: '/knowledge',
    label: '知识库',
    permission: 'access.manage',
    placement: 'menu',
  },
  {
    name: 'access-control',
    path: '/admin/access-control',
    label: '权限管理',
    permission: 'access.manage',
    placement: 'menu',
  },
]

/** Returns the entries whose permission is granted by the supplied effective-permission check. */
export function visibleNavigationEntries(can: PermissionChecker): AppNavigationEntry[] {
  return APP_NAVIGATION_ENTRIES.filter((entry) => can(entry.permission))
}

/** Returns the highest-priority permitted path, or `null` when no app feature is available. */
export function firstPermittedRoute(can: PermissionChecker): string | null {
  return APP_NAVIGATION_ENTRIES.find((entry) => can(entry.permission))?.path ?? null
}
