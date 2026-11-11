import test from 'node:test'
import assert from 'node:assert/strict'
import { ACCESS_MANAGE, hasAdminPermission, hasPermission } from '../src/utils/permissions.ts'

/**
 * Pure unit tests for the permission predicates backing the auth store's `can()` and `isAdmin`
 * getters. These are the exact functions the store delegates to, so testing them here pins the
 * fail-closed behaviour without needing a Pinia/Vue/DOM environment.
 */
test('hasPermission reports true only for a present key', () => {
  const permissions = ['chat.use', 'datasource.read', 'access.manage']

  assert.equal(hasPermission(permissions, 'chat.use'), true)
  assert.equal(hasPermission(permissions, 'access.manage'), true)
  assert.equal(hasPermission(permissions, 'user.manage'), false)
})

test('hasPermission fails closed on an absent permission set', () => {
  assert.equal(hasPermission(undefined, 'access.manage'), false)
  assert.equal(hasPermission([], 'access.manage'), false)
})

test('hasAdminPermission is true only with access.manage, independent of the legacy role', () => {
  assert.equal(hasAdminPermission(['chat.use', 'access.manage']), true)
  // A user may carry the display-only `role === 'admin'` string, but authorization must come from
  // the permission set. The helper only sees the permission list, so a list without access.manage
  // is never an administrator.
  assert.equal(hasAdminPermission(['chat.use', 'datasource.read']), false)
  assert.equal(hasAdminPermission(undefined), false)
  assert.equal(hasAdminPermission([]), false)
})

test('ACCESS_MANAGE is the access.manage key the store gates on', () => {
  assert.equal(ACCESS_MANAGE, 'access.manage')
})
