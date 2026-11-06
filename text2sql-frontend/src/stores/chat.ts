import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ChatMessage, ConversationEvent, Datasource } from '@/types'
import { listDatasources, streamAgent, streamText2Sql } from '@/api/text2sql'

/**
 * Generates a client-side message id.
 *
 * `crypto.randomUUID()` only exists in secure contexts, so the demo (served over plain
 * HTTP) falls back to `getRandomValues`, and only then to `Math.random`.
 */
function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  const bytes = new Uint8Array(16)
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    crypto.getRandomValues(bytes)
  } else {
    for (let i = 0; i < bytes.length; i++) {
      bytes[i] = Math.floor(Math.random() * 256)
    }
  }
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
}

/** Appends an error line without discarding partial output already streamed. */
function appendError(content: string, message: string): string {
  return content ? `${content}\n\n⚠️ ${message}` : `⚠️ ${message}`
}

/**
 * Conversation state for the text-to-SQL chat: the message list currently on screen,
 * the datasource catalog and the streaming lifecycle. Persisted conversations live in
 * the conversation store, which the caller uses to supply the conversation id.
 */
export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const streaming = ref(false)
  const datasources = ref<Datasource[]>([])
  const datasourceId = ref('')
  /** Switches between the deterministic pipeline and the AgentScope agent. */
  const agentMode = ref(false)

  let cancelStream: (() => void) | null = null

  /** Loads the datasource catalog and selects the backend default (or the first entry). */
  async function loadDatasources(): Promise<void> {
    try {
      const list = await listDatasources()
      datasources.value = list
      const preferred = list.find((item) => item.default) ?? list[0]
      if (preferred) {
        datasourceId.value = preferred.id
      }
    } catch {
      // Keep the picker empty; a broken backend surfaces on the stream itself.
      datasources.value = []
      datasourceId.value = ''
    }
  }

  /**
   * Sends a question and streams the assistant answer into the message list.
   *
   * @param question raw user input
   * @param conversationId conversation the exchange belongs to, or `null` for a new one
   * @param onConversation notified with the conversation the backend persisted to
   */
  function send(
    question: string,
    conversationId: string | null = null,
    onConversation?: (event: ConversationEvent) => void,
  ): void {
    const text = question.trim()
    if (!text || streaming.value) {
      return
    }

    messages.value.push({ id: newId(), role: 'user', content: text })
    const assistantIndex =
      messages.value.push({
        id: newId(),
        role: 'assistant',
        content: '',
        streaming: true,
        steps: [],
      }) - 1
    streaming.value = true

    if (agentMode.value) {
      cancelStream = streamAgent(text, 'default', {
        onDelta: (delta) => {
          messages.value[assistantIndex].content += delta
        },
        onStep: (step) => {
          const assistant = messages.value[assistantIndex]
          assistant.steps = [...(assistant.steps ?? []), step]
        },
        onSql: (payload) => {
          const assistant = messages.value[assistantIndex]
          assistant.sql = payload.sql
          assistant.scopeApplied = payload.scopeApplied === true
          assistant.rowCount = payload.rowCount
        },
        onCitations: (citations) => {
          messages.value[assistantIndex].citations = citations
        },
        onDone: () => {
          messages.value[assistantIndex].streaming = false
          streaming.value = false
          cancelStream = null
        },
        onError: (message) => {
          const assistant = messages.value[assistantIndex]
          assistant.streaming = false
          assistant.error = true
          assistant.content = appendError(assistant.content, message)
          streaming.value = false
          cancelStream = null
        },
      })
      return
    }

    cancelStream = streamText2Sql(text, datasourceId.value, conversationId, {
      onConversation: (event) => {
        onConversation?.(event)
      },
      onDelta: (delta) => {
        messages.value[assistantIndex].content += delta
      },
      onStep: (step) => {
        const assistant = messages.value[assistantIndex]
        assistant.steps = [...(assistant.steps ?? []), step]
      },
      onSql: (payload) => {
        const assistant = messages.value[assistantIndex]
        assistant.sql = payload.sql
        assistant.dialect = payload.dialect
        if (payload.explanation) {
          assistant.explanation = payload.explanation
        }
      },
      onRows: (result) => {
        messages.value[assistantIndex].rows = result
      },
      onChart: (chart) => {
        messages.value[assistantIndex].chart = chart
      },
      onCitations: (citations) => {
        messages.value[assistantIndex].citations = citations
      },
      onDone: () => {
        messages.value[assistantIndex].streaming = false
        streaming.value = false
        cancelStream = null
      },
      onError: (message) => {
        const assistant = messages.value[assistantIndex]
        assistant.streaming = false
        assistant.error = true
        assistant.content = appendError(assistant.content, message)
        streaming.value = false
        cancelStream = null
      },
    })
  }

  /** Aborts the in-flight stream, if any. */
  function stop(): void {
    cancelStream?.()
    cancelStream = null
    streaming.value = false

    const last = messages.value.at(-1)
    if (last) {
      last.streaming = false
    }
  }

  /**
   * Replaces the visible messages, e.g. with a loaded conversation history. Loading a
   * persisted conversation always returns to the deterministic pipeline, since the agent
   * path is not persisted.
   *
   * @param next the messages to display
   */
  function replaceMessages(next: ChatMessage[]): void {
    messages.value = next
    agentMode.value = false
  }

  /** Clears the conversation and starts over. */
  function reset(): void {
    stop()
    messages.value = []
  }

  return {
    messages,
    streaming,
    datasources,
    datasourceId,
    agentMode,
    loadDatasources,
    send,
    stop,
    replaceMessages,
    reset,
  }
})
