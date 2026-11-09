/**
 * Pure predicates over a caller's effective permission keys, shared by the auth store and its unit
 * tests. Kept free of any store/Vue import so the node test runner can exercise it directly.
 */

/** Permission key that gates the access-control admin surface. */
export const ACCESS_MANAGE = 'access.manage'

/**
 * Reports whether a permission set contains the given key.
 *
 * @param permissions the effective permission keys, or `undefined` when the profile is absent
 * @param permission the permission key to check
 * @returns `true` only when the key is present (fails closed on a missing set)
 */
export function hasPermission(permissions: string[] | undefined, permission: string): boolean {
  return permissions != null && permissions.includes(permission)
}

/**
 * Reports whether a permission set grants `access.manage` (administrator access).
 *
 * @param permissions the effective permission keys, or `undefined` when the profile is absent
 * @returns `true` only when `access.manage` is present (fails closed on a missing set)
 */
export function hasAdminPermission(permissions: string[] | undefined): boolean {
  return hasPermission(permissions, ACCESS_MANAGE)
}
