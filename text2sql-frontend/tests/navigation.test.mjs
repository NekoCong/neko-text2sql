import test from 'node:test'
import assert from 'node:assert/strict'
import {
  APP_NAVIGATION_ENTRIES,
  firstPermittedRoute,
  visibleNavigationEntries,
} from '../src/utils/navigation.ts'

/** Builds the same kind of synchronous permission predicate exposed by `auth.can`. */
function permissions(...granted) {
  return (permission) => granted.includes(permission)
}

test('navigation entries use the expected permission keys and landing order', () => {
  assert.deepEqual(
    APP_NAVIGATION_ENTRIES.map(({ path, permission, placement }) => ({ path, permission, placement })),
    [
      { path: '/', permission: 'chat.use', placement: 'menu' },
      { path: '/datasources', permission: 'datasource.read', placement: 'menu' },
      { path: '/settings/models', permission: 'model.manage', placement: 'dropdown' },
      { path: '/knowledge', permission: 'access.manage', placement: 'menu' },
      { path: '/admin/access-control', permission: 'access.manage', placement: 'menu' },
    ],
  )
  assert.equal(
    APP_NAVIGATION_ENTRIES.some((entry) => entry.path === '/admin/datasource-access'),
    false,
    'the legacy datasource-access page must stay out of navigation',
  )
})

test('visibleNavigationEntries exposes chat and datasource reading to student/counselor permissions', () => {
  const entries = visibleNavigationEntries(permissions('chat.use', 'datasource.read'))

  assert.deepEqual(
    entries.map((entry) => entry.path),
    ['/', '/datasources'],
  )
})

test('visibleNavigationEntries uses access.manage instead of an administrator role claim', () => {
  const entries = visibleNavigationEntries(permissions('access.manage'))

  assert.deepEqual(
    entries.map((entry) => entry.path),
    ['/knowledge', '/admin/access-control'],
  )
})

test('visibleNavigationEntries includes model settings only with model.manage', () => {
  const entries = visibleNavigationEntries(permissions('datasource.read', 'model.manage'))

  assert.deepEqual(
    entries.map((entry) => entry.path),
    ['/datasources', '/settings/models'],
  )
})

test('firstPermittedRoute skips chat when chat.use is absent', () => {
  assert.equal(firstPermittedRoute(permissions('datasource.read', 'model.manage')), '/datasources')
  assert.equal(firstPermittedRoute(permissions('model.manage')), '/settings/models')
  assert.equal(firstPermittedRoute(permissions('access.manage')), '/knowledge')
})

test('firstPermittedRoute fails closed when no destination is permitted', () => {
  assert.equal(firstPermittedRoute(permissions()), null)
  assert.equal(firstPermittedRoute(permissions('user.read')), null)
  assert.deepEqual(visibleNavigationEntries(permissions()), [])
})
