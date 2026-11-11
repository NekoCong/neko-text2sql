import { isAxiosError } from 'axios'
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
  DatasourceSchema,
  RoleDatasources,
} from '@/types'

/**
 * Typed client for the access-control admin API (`/api/admin/access-control`). Every route is
 * gated server-side by the appropriate permission; a caller without it gets a 401/403 from the
 * shared `http` instance.
 */
const BASE = '/admin/access-control'

/**
 * A normalized API failure: the HTTP status plus a readable message. The access-control endpoints
 * report failures as `{"status": ..., "message": ...}` bodies, so surface that message whenever the
 * body carries one.
 */
export interface AccessControlError {
  status: number | null
  message: string
}

/**
 * Extracts a user-facing message and status from an access-control API failure. Backend validation
 * and conflict failures are `{status, message}` bodies; plain-text bodies and common statuses are
 * mapped to readable fallbacks.
 *
 * @param error error thrown by the API call
 * @param fallback message used when the backend sent no readable body
 * @returns the normalized failure
 */
export function normalizeAccessControlError(
  error: unknown,
  fallback: string,
): AccessControlError {
  if (isAxiosError(error)) {
    const body: unknown = error.response?.data
    if (body != null && typeof body === 'object' && 'message' in body) {
      const message = (body as { message?: unknown }).message
      if (typeof message === 'string' && message.trim().length > 0) {
        return { status: error.response?.status ?? null, message }
      }
    }
    if (typeof body === 'string' && body.trim().length > 0) {
      return { status: error.response?.status ?? null, message: body }
    }
    const status = error.response?.status ?? null
    if (status === 403) {
      return { status, message: '没有权限执行该操作' }
    }
    if (status === 404) {
      return { status, message: '目标不存在，可能已被删除' }
    }
    if (status === 400) {
      return { status, message: '请求参数无效，请检查表单内容' }
    }
    return { status, message: fallback }
  }
  return { status: null, message: fallback }
}

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
  const { data } = await http.put<AccessRole>(`${BASE}/roles/${encodeURIComponent(id)}`, request)
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
  const { data } = await http.get<AccessRolePermissions>(
    `${BASE}/roles/${encodeURIComponent(roleId)}/permissions`,
  )
  return data
}

/** Replaces a role's permission set with the complete requested set. */
export async function replaceRolePermissions(
  roleId: string,
  permissions: string[],
): Promise<AccessRolePermissions> {
  const { data } = await http.put<AccessRolePermissions>(
    `${BASE}/roles/${encodeURIComponent(roleId)}/permissions`,
    { permissions },
  )
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
  await http.delete(`${BASE}/data-scopes/${encodeURIComponent(id)}`)
}

/** Reads one datasource's live table/column metadata for the data-scope editor. */
export async function getDatasourceSchema(datasourceId: string): Promise<DatasourceSchema> {
  const { data } = await http.get<DatasourceSchema>(
    `${BASE}/datasources/${encodeURIComponent(datasourceId)}/schema`,
  )
  return data
}

// --- Datasource matrix --------------------------------------------------------------------------

/** Reads the full role → datasource matrix. */
export async function getDatasourceMatrix(): Promise<DatasourceMatrix> {
  const { data } = await http.get<DatasourceMatrix>(`${BASE}/datasource-access`)
  return data
}

/** Reads a role's own datasource grant set. */
export async function getRoleDatasources(roleId: string): Promise<RoleDatasources> {
  const { data } = await http.get<RoleDatasources>(
    `${BASE}/roles/${encodeURIComponent(roleId)}/datasources`,
  )
  return data
}

/** Replaces a role's datasource grant set with the complete requested set. */
export async function replaceRoleDatasources(
  roleId: string,
  datasourceIds: string[],
): Promise<RoleDatasources> {
  const { data } = await http.put<RoleDatasources>(
    `${BASE}/roles/${encodeURIComponent(roleId)}/datasources`,
    { datasourceIds },
  )
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
  const { data } = await http.get<AccessUser>(`${BASE}/users/${encodeURIComponent(id)}`)
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
  const { data } = await http.put<AccessUser>(`${BASE}/users/${encodeURIComponent(id)}`, request)
  return data
}

/** Enables or disables a user. */
export async function updateUserStatus(
  id: string,
  request: AccessUserStatusRequest,
): Promise<AccessUser> {
  const { data } = await http.put<AccessUser>(
    `${BASE}/users/${encodeURIComponent(id)}/status`,
    request,
  )
  return data
}

/** Resets a user's password. */
export async function resetUserPassword(
  id: string,
  request: AccessUserPasswordRequest,
): Promise<void> {
  await http.post(`${BASE}/users/${encodeURIComponent(id)}/reset-password`, request)
}

/** Replaces a user's role assignments. */
export async function replaceUserRoles(
  id: string,
  request: AccessUserRolesRequest,
): Promise<AccessUser> {
  const { data } = await http.put<AccessUser>(
    `${BASE}/users/${encodeURIComponent(id)}/roles`,
    request,
  )
  return data
}

// --- Audit --------------------------------------------------------------------------------------

/** Lists the access-control audit trail, newest first, paginated and filtered. */
export async function listAudit(filters: AuditFilters = {}): Promise<AccessAuditPage> {
  const { data } = await http.get<AccessAuditPage>(`${BASE}/audit`, { params: filters })
  return data
}

