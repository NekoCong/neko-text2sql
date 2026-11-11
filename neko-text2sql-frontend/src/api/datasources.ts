import { isAxiosError } from 'axios'
import { http } from './http'
import type { ConnectionTestResult, DatasourceDetail, DatasourcePayload } from '@/types'

const BASE = '/text2sql/datasources'

/**
 * Lists the registered datasources, including their connection details.
 *
 * @returns the registered datasources
 */
export async function listDatasources(): Promise<DatasourceDetail[]> {
  const { data } = await http.get<DatasourceDetail[]>(BASE)
  return data
}

/**
 * Registers a new datasource at runtime.
 *
 * @param payload connection settings of the new datasource
 * @returns the created datasource
 */
export async function createDatasource(payload: DatasourcePayload): Promise<DatasourceDetail> {
  const { data } = await http.post<DatasourceDetail>(BASE, payload)
  return data
}

/**
 * Updates an existing datasource.
 *
 * @param id identifier of the datasource to update
 * @param payload new connection settings
 * @returns the updated datasource
 */
export async function updateDatasource(
  id: string,
  payload: DatasourcePayload,
): Promise<DatasourceDetail> {
  const { data } = await http.put<DatasourceDetail>(`${BASE}/${encodeURIComponent(id)}`, payload)
  return data
}

/**
 * Deletes a datasource.
 *
 * @param id identifier of the datasource to delete
 */
export async function deleteDatasource(id: string): Promise<void> {
  await http.delete(`${BASE}/${encodeURIComponent(id)}`)
}

/**
 * Tests the connection of an already registered datasource.
 *
 * @param id identifier of the datasource to test
 * @returns the test outcome including the elapsed milliseconds
 */
export async function testDatasource(id: string): Promise<ConnectionTestResult> {
  const { data } = await http.post<ConnectionTestResult>(`${BASE}/${encodeURIComponent(id)}/test`)
  return data
}

/**
 * Tests a candidate datasource without registering it.
 *
 * @param payload connection settings to probe
 * @returns the test outcome including the elapsed milliseconds
 */
export async function testCandidateDatasource(
  payload: DatasourcePayload,
): Promise<ConnectionTestResult> {
  const { data } = await http.post<ConnectionTestResult>(`${BASE}/test`, payload)
  return data
}

/**
 * Extracts a user-facing message from an API failure. The backend reports errors as
 * plain-text bodies (`400` / `404` / `409`), so surface that text whenever present.
 *
 * @param error error thrown by the API call
 * @param fallback message used when the backend sent no readable body
 * @returns the message to display
 */
export function datasourceErrorMessage(error: unknown, fallback: string): string {
  if (isAxiosError(error)) {
    const body: unknown = error.response?.data
    if (typeof body === 'string' && body.trim().length > 0) {
      return body
    }
    const status = error.response?.status
    if (status === 400) {
      return '请求参数无效，请检查表单内容'
    }
    if (status === 404) {
      return '数据源不存在，可能已被删除'
    }
    if (status === 409) {
      return '操作冲突：无法删除该数据源（可能是最后一个）'
    }
  }
  return fallback
}
