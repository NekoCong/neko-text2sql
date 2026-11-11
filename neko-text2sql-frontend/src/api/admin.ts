import { http } from './http'

/** One datasource row of the role -> datasource access matrix. */
export interface DatasourceAccessRow {
  id: string
  name: string
  defaultDatasource: boolean
  grantedRoles: string[]
}

/** The role -> datasource access matrix maintained by administrators. */
export interface DatasourceAccessMatrix {
  roles: string[]
  datasources: DatasourceAccessRow[]
}

/** Reads the access matrix (admin only). */
export async function getAccessMatrix(): Promise<DatasourceAccessMatrix> {
  const { data } = await http.get<DatasourceAccessMatrix>('/admin/datasource-access')
  return data
}

/** Grants a role access to a datasource (admin only). */
export async function grantAccess(
  role: string,
  datasourceId: string,
): Promise<DatasourceAccessMatrix> {
  const { data } = await http.put<DatasourceAccessMatrix>(
    `/admin/datasource-access/${role}/${datasourceId}`,
  )
  return data
}

/** Revokes a role's access to a datasource (admin only). */
export async function revokeAccess(
  role: string,
  datasourceId: string,
): Promise<DatasourceAccessMatrix> {
  const { data } = await http.delete<DatasourceAccessMatrix>(
    `/admin/datasource-access/${role}/${datasourceId}`,
  )
  return data
}
