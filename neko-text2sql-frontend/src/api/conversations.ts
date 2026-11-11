import { isAxiosError } from 'axios'
import { http } from './http'
import type { Conversation, ConversationCreatePayload, HistoryMessage } from '@/types'

const BASE = '/conversations'

/**
 * Lists the current user's conversations (newest first is left to the caller).
 *
 * @returns the stored conversations
 */
export async function listConversations(): Promise<Conversation[]> {
  const { data } = await http.get<Conversation[]>(BASE)
  return data
}

/**
 * Creates a conversation. The backend fills in a default title when none is given.
 *
 * @param payload optional title / datasource / model config
 * @returns the created conversation
 */
export async function createConversation(
  payload: ConversationCreatePayload = {},
): Promise<Conversation> {
  const { data } = await http.post<Conversation>(BASE, payload)
  return data
}

/**
 * Renames a conversation.
 *
 * @param id identifier of the conversation to rename
 * @param title the new title
 * @returns the updated conversation
 */
export async function renameConversation(id: string, title: string): Promise<Conversation> {
  const { data } = await http.put<Conversation>(`${BASE}/${encodeURIComponent(id)}`, { title })
  return data
}

/**
 * Deletes a conversation together with its messages.
 *
 * @param id identifier of the conversation to delete
 */
export async function deleteConversation(id: string): Promise<void> {
  await http.delete(`${BASE}/${encodeURIComponent(id)}`)
}

/**
 * Loads the stored messages of a conversation, oldest first.
 *
 * @param id identifier of the conversation
 * @returns the messages including SQL / rows / chart payloads
 */
export async function listMessages(id: string): Promise<HistoryMessage[]> {
  const { data } = await http.get<HistoryMessage[]>(`${BASE}/${encodeURIComponent(id)}/messages`)
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
export function conversationErrorMessage(error: unknown, fallback: string): string {
  if (isAxiosError(error)) {
    const body: unknown = error.response?.data
    if (typeof body === 'string' && body.trim().length > 0) {
      return body
    }
    const status = error.response?.status
    if (status === 404) {
      return '会话不存在，可能已被删除'
    }
    if (status === 400) {
      return '请求参数无效'
    }
  }
  return fallback
}
