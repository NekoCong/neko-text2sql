import { http } from './http'
import type {
  AccessAuditPage,
  AccessPermission,
  AccessRole,
  AccessRolePermissions,
  AccessRoleRequest,
  AccessUser,
  AccessUserCreateRequest,
  AccessUserDisplayNameRequest,
  AccessUserPage,
  AccessUserPasswordRequest,
  AccessUserRolesRequest,
  AccessUserStatusRequest,
  AuditFilters,
  DataScope,
  DataScopeRequest,
  DatasourceMatrix,
  RoleDatasources,
} from '@/types'

/**
 * Typed client for the access-control admin API (`/api/admin/access-control`). Every route is
 * gated server-side by the `access.manage` permission; a caller without it gets a 401/403 from the
 * shared `http` instance.
 */
const BASE = '/admin/access-control'

// --- Roles --------------------------------------------------------------------------------------

/** Lists every role, ordered by code. */
export async function listRoles(): Promise<AccessRole[]> {
  const { data } = await http.get<AccessRole[]>(`${BASE}/roles`)
  return data
}

/** Creates a role. */
export async function createRole(request: AccessRoleRequest): Promise<AccessRole> {
  const { data } = await http.post<AccessRole>(`${BASE}/roles`, request)
  return data
}

/** Updates a role (`code` is immutable; omitted fields keep their current value). */
export async function updateRole(id: string, request: AccessRoleRequest): Promise<AccessRole> {
  const { data } = await http.put<AccessRole>(`${BASE}/roles/${id}`, request)
  return data
}

// --- Permissions --------------------------------------------------------------------------------

/** Lists the permission catalog, ordered by module then key. */
export async function listPermissions(): Promise<AccessPermission[]> {
  const { data } = await http.get<AccessPermission[]>(`${BASE}/permissions`)
  return data
}

/** Reads a role's own permission set. */
export async function getRolePermissions(roleId: string): Promise<AccessRolePermissions> {
  const { data } = await http.get<AccessRolePermissions>(`${BASE}/roles/${roleId}/permissions`)
  return data
}

/** Replaces a role's permission set with the complete requested set. */
export async function replaceRolePermissions(
  roleId: string,
  permissions: string[],
): Promise<AccessRolePermissions> {
  const { data } = await http.put<AccessRolePermissions>(`${BASE}/roles/${roleId}/permissions`, {
    permissions,
  })
  return data
}

// --- Data scopes --------------------------------------------------------------------------------

/** Parameters for listing a role's data-scope rules. */
export interface ListDataScopesParams {
  roleId: string
  datasourceId?: string
}

/** Lists a role's data-scope rules, ordered by datasource then table. */
export async function listDataScopes(params: ListDataScopesParams): Promise<DataScope[]> {
  const { data } = await http.get<DataScope[]>(`${BASE}/data-scopes`, { params })
  return data
}

/** Inserts or updates the rule for a role, datasource and table. */
export async function upsertDataScope(request: DataScopeRequest): Promise<DataScope> {
  const { data } = await http.put<DataScope>(`${BASE}/data-scopes`, request)
  return data
}

/** Deletes one data-scope rule. */
export async function deleteDataScope(id: string): Promise<void> {
  await http.delete(`${BASE}/data-scopes/${id}`)
}

// --- Datasource matrix --------------------------------------------------------------------------

/** Reads the full role → datasource matrix. */
export async function getDatasourceMatrix(): Promise<DatasourceMatrix> {
  const { data } = await http.get<DatasourceMatrix>(`${BASE}/datasource-access`)
  return data
}

/** Reads a role's own datasource grant set. */
export async function getRoleDatasources(roleId: string): Promise<RoleDatasources> {
  const { data } = await http.get<RoleDatasources>(`${BASE}/roles/${roleId}/datasources`)
  return data
}

/** Replaces a role's datasource grant set with the complete requested set. */
export async function replaceRoleDatasources(
  roleId: string,
  datasourceIds: string[],
): Promise<RoleDatasources> {
  const { data } = await http.put<RoleDatasources>(`${BASE}/roles/${roleId}/datasources`, {
    datasourceIds,
  })
  return data
}

// --- Users --------------------------------------------------------------------------------------

/** Parameters for listing users. */
export interface ListUsersParams {
  query?: string
  enabled?: boolean
  roleId?: string
  page?: number
  size?: number
}

/** Lists users, ordered by username, paginated. */
export async function listUsers(params: ListUsersParams = {}): Promise<AccessUserPage> {
  const { data } = await http.get<AccessUserPage>(`${BASE}/users`, { params })
  return data
}

/** Reads one user. */
export async function getUser(id: string): Promise<AccessUser> {
  const { data } = await http.get<AccessUser>(`${BASE}/users/${id}`)
  return data
}

/** Creates a user. */
export async function createUser(request: AccessUserCreateRequest): Promise<AccessUser> {
  const { data } = await http.post<AccessUser>(`${BASE}/users`, request)
  return data
}

/** Edits a user's display name. */
export async function updateUserDisplayName(
  id: string,
  request: AccessUserDisplayNameRequest,
): Promise<AccessUser> {
  const { data } = await http.put<AccessUser>(`${BASE}/users/${id}`, request)
  return data
}

/** Enables or disables a user. */
export async function updateUserStatus(
  id: string,
  request: AccessUserStatusRequest,
): Promise<AccessUser> {
  const { data } = await http.put<AccessUser>(`${BASE}/users/${id}/status`, request)
  return data
}

/** Resets a user's password. */
export async function resetUserPassword(
  id: string,
  request: AccessUserPasswordRequest,
): Promise<void> {
  await http.post(`${BASE}/users/${id}/reset-password`, request)
}

/** Replaces a user's role assignments. */
export async function replaceUserRoles(
  id: string,
  request: AccessUserRolesRequest,
): Promise<AccessUser> {
  const { data } = await http.put<AccessUser>(`${BASE}/users/${id}/roles`, request)
  return data
}

// --- Audit --------------------------------------------------------------------------------------

/** Lists the access-control audit trail, newest first, paginated and filtered. */
export async function listAudit(filters: AuditFilters = {}): Promise<AccessAuditPage> {
  const { data } = await http.get<AccessAuditPage>(`${BASE}/audit`, { params: filters })
  return data
}
