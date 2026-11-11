import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  createConversation,
  deleteConversation,
  listConversations,
  listMessages,
  renameConversation,
} from '@/api/conversations'
import type { Conversation, HistoryMessage } from '@/types'

/** A date bucket of the sidebar history (今天 / 昨天 / 更早). */
export interface ConversationGroup {
  label: string
  items: Conversation[]
}

const DAY_MS = 86_400_000

/** Local midnight of the current day, in epoch millis. */
function startOfToday(): number {
  const now = new Date()
  return new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
}

/**
 * Conversation history state backing the left-hand sidebar: the conversation list,
 * the currently selected conversation and the persisted messages of each one.
 */
export const useConversationStore = defineStore('conversation', () => {
  const conversations = ref<Conversation[]>([])
  const activeId = ref<string | null>(null)
  const loading = ref(false)

  /** Newest first — the order the sidebar renders in. */
  const sorted = computed(() =>
    [...conversations.value].sort((left, right) => right.updatedAt - left.updatedAt),
  )

  /** The history bucketed by last update: 今天 / 昨天 / 更早. Empty buckets are dropped. */
  const groups = computed<ConversationGroup[]>(() => {
    const todayStart = startOfToday()
    const yesterdayStart = todayStart - DAY_MS
    const buckets: ConversationGroup[] = [
      { label: '今天', items: [] },
      { label: '昨天', items: [] },
      { label: '更早', items: [] },
    ]
    for (const conversation of sorted.value) {
      if (conversation.updatedAt >= todayStart) {
        buckets[0].items.push(conversation)
      } else if (conversation.updatedAt >= yesterdayStart) {
        buckets[1].items.push(conversation)
      } else {
        buckets[2].items.push(conversation)
      }
    }
    return buckets.filter((bucket) => bucket.items.length > 0)
  })

  /** Loads (or reloads) the conversation list from the backend. */
  async function load(): Promise<void> {
    loading.value = true
    try {
      conversations.value = await listConversations()
    } finally {
      loading.value = false
    }
  }

  /**
   * Creates a conversation on the backend, puts it on top of the list and selects it.
   *
   * @returns the id of the created conversation
   */
  async function create(): Promise<string> {
    const conversation = await createConversation()
    conversations.value = [conversation, ...conversations.value]
    activeId.value = conversation.id
    return conversation.id
  }

  /**
   * Renames a conversation on the backend, then mirrors the change locally.
   *
   * @param id identifier of the conversation
   * @param title the new title
   */
  async function rename(id: string, title: string): Promise<void> {
    const updated = await renameConversation(id, title)
    conversations.value = conversations.value.map((conversation) =>
      conversation.id === id ? updated : conversation,
    )
  }

  /**
   * Deletes a conversation on the backend, then mirrors the change locally. When the
   * deleted conversation is the active one the selection is cleared.
   *
   * @param id identifier of the conversation
   */
  async function remove(id: string): Promise<void> {
    await deleteConversation(id)
    conversations.value = conversations.value.filter((conversation) => conversation.id !== id)
    if (activeId.value === id) {
      activeId.value = null
    }
  }

  /**
   * Loads the stored messages of a conversation.
   *
   * @param id identifier of the conversation
   * @returns the persisted history, oldest first
   */
  async function messagesFor(id: string): Promise<HistoryMessage[]> {
    return listMessages(id)
  }

  /**
   * Records a conversation that the chat stream just created or touched: prepends it
   * when unknown, refreshes its title/timestamp otherwise, and selects it when no
   * conversation is active yet.
   *
   * @param id conversation id reported by the `conversation` SSE event
   * @param title conversation title reported by the `conversation` SSE event
   */
  function attach(id: string, title: string): void {
    const existing = conversations.value.find((conversation) => conversation.id === id)
    if (existing) {
      if (title) {
        existing.title = title
      }
      existing.updatedAt = Date.now()
    } else {
      const now = Date.now()
      conversations.value.unshift({
        id,
        title,
        datasourceId: null,
        modelConfigId: null,
        createdAt: now,
        updatedAt: now,
      })
    }
    if (activeId.value === null) {
      activeId.value = id
    }
  }

  /**
   * Changes the selected conversation.
   *
   * @param id conversation id, or `null` for a fresh, unsaved conversation
   */
  function select(id: string | null): void {
    activeId.value = id
  }

  /** Drops all local history state (used on logout). */
  function clear(): void {
    conversations.value = []
    activeId.value = null
  }

  return {
    conversations,
    activeId,
    loading,
    sorted,
    groups,
    load,
    create,
    rename,
    remove,
    messagesFor,
    attach,
    select,
    clear,
  }
})
