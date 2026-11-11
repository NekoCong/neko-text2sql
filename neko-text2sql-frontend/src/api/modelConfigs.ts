import { isAxiosError } from 'axios'
import { http } from './http'
import type { ConnectionTestResult, ModelConfig, ModelConfigPayload } from '@/types'

const BASE = '/model-configs'

/**
 * Lists the current user's model configurations. The API key is never included.
 *
 * @returns the stored model configs
 */
export async function listModelConfigs(): Promise<ModelConfig[]> {
  const { data } = await http.get<ModelConfig[]>(BASE)
  return data
}

/**
 * Creates a model configuration.
 *
 * @param payload provider / model / credentials of the new config
 * @returns the created config
 */
export async function createModelConfig(payload: ModelConfigPayload): Promise<ModelConfig> {
  const { data } = await http.post<ModelConfig>(BASE, payload)
  return data
}

/**
 * Updates a model configuration. A blank `apiKey` keeps the stored key.
 *
 * @param id identifier of the config to update
 * @param payload new values
 * @returns the updated config
 */
export async function updateModelConfig(
  id: string,
  payload: ModelConfigPayload,
): Promise<ModelConfig> {
  const { data } = await http.put<ModelConfig>(`${BASE}/${id}`, payload)
  return data
}

/**
 * Deletes a model configuration.
 *
 * @param id identifier of the config to delete
 */
export async function deleteModelConfig(id: string): Promise<void> {
  await http.delete(`${BASE}/${id}`)
}

/**
 * Probes connectivity of a stored model configuration.
 *
 * @param id identifier of the config to test
 * @returns the test outcome including the elapsed milliseconds
 */
export async function testModelConfig(id: string): Promise<ConnectionTestResult> {
  const { data } = await http.post<ConnectionTestResult>(`${BASE}/${id}/test`)
  return data
}

/**
 * Extracts a user-facing message from an API failure. The backend reports errors as
 * plain-text bodies (`400` / `404`), so surface that text whenever present.
 *
 * @param error error thrown by the API call
 * @param fallback message used when the backend sent no readable body
 * @returns the message to display
 */
export function modelConfigErrorMessage(error: unknown, fallback: string): string {
  if (isAxiosError(error)) {
    const body: unknown = error.response?.data
    if (typeof body === 'string' && body.trim().length > 0) {
      return body
    }
    const status = error.response?.status
    if (status === 404) {
      return '模型配置不存在，可能已被删除'
    }
    if (status === 400) {
      return '请求参数无效，请检查表单内容'
    }
  }
  return fallback
}
