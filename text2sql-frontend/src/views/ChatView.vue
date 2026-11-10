<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { DocumentCopy, Plus, Promotion, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { conversationErrorMessage } from '@/api/conversations'
import type { Citation } from '@/api/knowledge'
import { useChatStore } from '@/stores/chat'
import { useConversationStore } from '@/stores/conversation'
import { useAuthStore } from '@/stores/auth'
import CitationBadge from '@/components/CitationBadge.vue'
import CitationDrawer from '@/components/CitationDrawer.vue'
import ResultChart from '@/components/ResultChart.vue'
import type {
  ChartSpec,
  ChatMessage,
  ConversationEvent,
  HistoryMessage,
  QueryResult,
  ChatStep,
} from '@/types'
import { copyText } from '@/utils/clipboard'
import { parseSegments } from '@/utils/markdown'

const auth = useAuthStore()
const chat = useChatStore()
const conversation = useConversationStore()
const canUseChat = computed(() => auth.can('chat.use'))
const draft = ref('')
const scrollRef = ref<HTMLElement | null>(null)
/** True while a stored conversation history is being fetched. */
const loadingHistory = ref(false)
/** Conversation the live stream attached; keeps its `activeId` watcher idle. */
const streamedConversationId = ref<string | null>(null)

/** Citation preview state shared by every answer in the transcript. */
const citationDrawerVisible = ref(false)
const activeCitation = ref<Citation | null>(null)
const activeCitationList = ref<Citation[]>([])

/**
 * Opens the original-document preview for one badge of an answer.
 *
 * @param message the answer owning the citations
 * @param index zero-based citation index
 */
function openMessageCitation(message: ChatMessage, index: number): void {
  const citations = message.citations ?? []
  const citation = citations[index]
  if (!citation) {
    return
  }
  activeCitationList.value = citations
  activeCitation.value = citation
  citationDrawerVisible.value = true
}

/** Switches to another citation of the same answer (drawer footer navigation). */
function onCitationNavigate(index: number): void {
  const citation = activeCitationList.value[index]
  if (citation) {
    activeCitation.value = citation
  }
}

const examples = [
  '1班和2班的数学平均分分别是多少',
  '我的各科成绩',
  '统计每个班的考勤异常次数',
]

const canSend = computed(
  () => canUseChat.value && draft.value.trim().length > 0 && !chat.streaming,
)

const selectedDatasource = computed(
  () => chat.datasources.find((item) => item.id === chat.datasourceId) ?? null,
)

onMounted(() => {
  if (!canUseChat.value) {
    return
  }
  void chat.loadDatasources()
  if (conversation.activeId && !chat.streaming) {
    void loadConversation(conversation.activeId)
  }
})

// Switching conversations swaps the visible messages: load the stored history, or
// clear the pane for a fresh conversation.
watch(
  () => conversation.activeId,
  (id) => {
    if (!canUseChat.value) {
      return
    }
    if (id === null) {
      chat.stop()
      chat.reset()
      return
    }
    if (chat.streaming && id === streamedConversationId.value) {
      // The running stream persisted this conversation; the live view is already correct.
      return
    }
    void loadConversation(id)
  },
)

/**
 * Normalises persisted citations: the backend may store them as an array or as a
 * JSON-encoded string, and older rows have none at all.
 *
 * @param value raw `citations` field of a history message
 * @returns the citation list, or an empty array
 */
function parseCitations(value: unknown): Citation[] {
  if (Array.isArray(value)) {
    return value as Citation[]
  }
  if (typeof value === 'string' && value.trim().length > 0) {
    try {
      const parsed: unknown = JSON.parse(value)
      return Array.isArray(parsed) ? (parsed as Citation[]) : []
    } catch {
      return []
    }
  }
  return []
}

/**
 * Maps a stored history message onto the live chat message shape.
 *
 * @param message persisted message
 * @returns the renderable chat message
 */
function toChatMessage(message: HistoryMessage): ChatMessage {
  const mapped: ChatMessage = {
    id: message.id,
    role: message.role,
    content: message.content,
  }
  if (message.sql) {
    mapped.sql = message.sql
  }
  if (message.rows && Array.isArray(message.rows.columns) && Array.isArray(message.rows.rows)) {
    mapped.rows = message.rows
  }
  if (message.chart && typeof message.chart.type === 'string') {
    mapped.chart = message.chart
  }
  const citations = parseCitations(message.citations)
  if (citations.length > 0) {
    mapped.citations = citations
  }
  return mapped
}

/**
 * Replaces the visible messages with the stored history of a conversation.
 *
 * @param id conversation to load
 */
async function loadConversation(id: string): Promise<void> {
  if (chat.streaming) {
    chat.stop()
  }
  loadingHistory.value = true
  try {
    const history = await conversation.messagesFor(id)
    if (conversation.activeId !== id) {
      return
    }
    chat.replaceMessages(history.map(toChatMessage))
  } catch (error) {
    ElMessage.error(conversationErrorMessage(error, '加载会话消息失败'))
  } finally {
    loadingHistory.value = false
  }
}

function onSend(): void {
  if (!canSend.value) {
    return
  }
  chat.send(draft.value, conversation.activeId, onConversationAttached)
  draft.value = ''
}

/**
 * Reflects the conversation the stream persisted to: selects it, surfaces it in the
 * sidebar immediately, then reconciles with the backend list.
 *
 * @param event payload of the `conversation` SSE event
 */
function onConversationAttached(event: ConversationEvent): void {
  streamedConversationId.value = event.id
  conversation.attach(event.id, event.title)
  void conversation.load().catch(() => {
    // The locally attached entry stays; the next refresh reconciles it.
  })
}

/** Starts a fresh conversation; it is persisted right away so the sidebar lists it. */
async function onNewConversation(): Promise<void> {
  if (chat.streaming) {
    chat.stop()
  }
  try {
    await conversation.create()
  } catch (error) {
    ElMessage.error(conversationErrorMessage(error, '新建对话失败'))
  }
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter' && !event.shiftKey) {
    // Enter that confirms an IME composition must not send the message.
    if (event.isComposing || event.keyCode === 229) {
      return
    }
    event.preventDefault()
    onSend()
  }
}

function useExample(text: string): void {
  draft.value = text
}

function segments(content: string) {
  return parseSegments(content)
}

/** Steps that carry a real detail line; empty-detail steps are not rendered. */
function visibleSteps(message: ChatMessage): ChatStep[] {
  return (message.steps ?? []).filter((step) => stepText(step).length > 0)
}

/** Readable label for a pipeline step or an agent tool step. */
function stepText(step: ChatStep): string {
  if (step.phase !== 'tool') {
    return step.detail
  }
  const name = step.tool || '工具'
  if (step.status === 'start') {
    return `调用 ${name}…`
  }
  if (step.state === 'success') {
    return `${name} 完成`
  }
  const suffix = step.reason ? `：${step.reason}` : ''
  return `${name} ${step.state ?? '结束'}${suffix}`
}

/** Stringifies a result cell for display. */
function cellText(row: Record<string, unknown>, key: string): string {
  const value = row[key]
  return value === null || value === undefined ? '' : String(value)
}

/** True when the chart spec references columns that actually exist in the result set. */
function hasChartFields(rows: QueryResult, chart: ChartSpec): boolean {
  const names = new Set(rows.columns.map((column) => column.name))
  return Boolean(
    chart.xField &&
      chart.yField &&
      names.has(chart.xField) &&
      names.has(chart.yField),
  )
}

async function copySql(message: ChatMessage): Promise<void> {
  const sql = message.sql
  if (!sql) {
    return
  }
  if (await copyText(sql)) {
    ElMessage.success('SQL 已复制')
  } else {
    ElMessage.error('复制失败，请手动选中 SQL 复制')
  }
}

watch(
  () =>
    chat.messages
      .map(
        (message) =>
          `${message.content.length}:${message.steps?.length ?? 0}:${message.sql?.length ?? 0}:${message.explanation?.length ?? 0}:${message.rows ? 'r' : ''}:${message.citations?.length ?? 0}`,
      )
      .join(','),
  () => {
    void nextTick(() => {
      const el = scrollRef.value
      if (el) {
        el.scrollTop = el.scrollHeight
      }
    })
  },
)
</script>

<template>
  <div class="chat">
    <section class="chat__panel" aria-label="数据问答工作区">
      <header class="chat__header">
        <div class="chat__heading">
          <h1 class="chat__title">数据问答</h1>
          <p class="chat__subtitle">用自然语言描述问题，自动生成 SQL、执行并返回结果。</p>
        </div>

        <div v-if="canUseChat" class="chat__controls">
          <el-select
            v-model="chat.datasourceId"
            class="chat__datasource"
            placeholder="选择数据源"
            :disabled="chat.datasources.length === 0"
          >
            <el-option
              v-for="datasource in chat.datasources"
              :key="datasource.id"
              :label="datasource.name"
              :value="datasource.id"
            >
              <span class="chat__datasource-option">
                <span class="chat__datasource-name">{{ datasource.name }}</span>
                <el-tag size="small" effect="plain" type="info">{{ datasource.dialect }}</el-tag>
              </span>
            </el-option>
            <template #label>
              <span v-if="selectedDatasource" class="chat__datasource-option">
                <span class="chat__datasource-name">{{ selectedDatasource.name }}</span>
                <el-tag size="small" effect="plain" type="info">
                  {{ selectedDatasource.dialect }}
                </el-tag>
              </span>
            </template>
          </el-select>

          <div class="chat__mode">
            <span class="chat__mode-label">模式</span>
            <el-switch
              v-model="chat.agentMode"
              inline-prompt
              active-text="Agent"
              inactive-text="流水线"
              :disabled="chat.streaming"
            />
          </div>

          <el-button class="chat__new" :icon="Plus" text @click="onNewConversation">
            新建对话
          </el-button>
        </div>
      </header>

      <div
        ref="scrollRef"
        v-loading="loadingHistory"
        class="chat__messages"
        aria-label="对话记录"
        :aria-busy="chat.streaming"
      >
        <div v-if="chat.messages.length === 0" class="empty">
          <span class="empty__mark" aria-hidden="true">T2S</span>
          <h2 class="empty__title">今天想了解校园里的哪些数据？</h2>
          <p class="empty__text">
            {{ canUseChat ? '选择一个示例，或直接输入你的问题。' : '当前账号仅可查看已有对话内容。' }}
          </p>
          <div v-if="canUseChat" class="empty__chips">
            <button
              v-for="example in examples"
              :key="example"
              type="button"
              class="chip"
              @click="useExample(example)"
            >
              {{ example }}
            </button>
          </div>
          <p class="empty__scope">查询结果仅限当前账号的数据权限范围。</p>
        </div>

        <article
          v-for="message in chat.messages"
          :key="message.id"
          class="msg"
          :class="[
            `msg--${message.role}`,
            { 'msg--streaming': message.streaming, 'msg--error': message.error },
          ]"
        >
          <div v-if="message.role === 'assistant'" class="msg__identity">
            <span class="msg__avatar" aria-hidden="true">AI</span>
            <span class="msg__agent">Text2SqlAgent</span>
            <span v-if="message.streaming" class="msg__state msg__state--live">生成中…</span>
            <span v-else-if="message.error" class="msg__state msg__state--error">生成失败</span>
          </div>

          <div class="msg__body">
            <ol
              v-if="visibleSteps(message).length > 0"
              class="steps"
              :class="{ 'steps--live': message.streaming }"
            >
              <li v-for="(step, index) in visibleSteps(message)" :key="index" class="steps__item">
                <span class="steps__dot" aria-hidden="true" />
                <span class="steps__detail">{{ stepText(step) }}</span>
              </li>
            </ol>

            <p v-if="message.explanation" class="msg__explanation">{{ message.explanation }}</p>

            <template v-for="(segment, index) in segments(message.content)" :key="index">
              <pre v-if="segment.type === 'code'" class="code"><code>{{ segment.value }}</code></pre>
              <div v-else-if="segment.type === 'table'" class="md-table">
                <table>
                  <thead>
                    <tr>
                      <th v-for="(header, cellIndex) in segment.headers" :key="cellIndex">{{ header }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, rowIndex) in segment.rows" :key="rowIndex">
                      <td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p v-else class="msg__text">{{ segment.value }}</p>
            </template>

            <div v-if="message.citations?.length" class="msg__citations">
              <span class="msg__citations-label">引用</span>
              <CitationBadge
                :citations="message.citations"
                @open="(index) => openMessageCitation(message, index)"
              />
            </div>

            <div v-if="message.sql" class="sql">
              <div class="sql__bar">
                <span class="sql__label">SQL</span>
                <span v-if="message.dialect" class="sql__tag">{{ message.dialect }}</span>
                <span v-if="message.scopeApplied" class="sql__tag sql__tag--scope">
                  已按当前数据范围过滤
                </span>
                <span v-if="message.rowCount !== undefined" class="sql__tag">
                  {{ message.rowCount }} 行
                </span>
                <el-button
                  class="sql__copy"
                  :icon="DocumentCopy"
                  text
                  size="small"
                  @click="copySql(message)"
                >
                  复制
                </el-button>
              </div>
              <pre class="sql__code"><code>{{ message.sql }}</code></pre>
            </div>

            <div v-if="message.rows" class="result">
              <ResultChart
                v-if="
                  message.chart &&
                  message.chart.type !== 'NONE' &&
                  hasChartFields(message.rows, message.chart)
                "
                class="result__chart"
                :rows="message.rows"
                :chart="message.chart"
              />
              <div class="result__table">
                <el-table :data="message.rows.rows" size="small" border stripe max-height="320">
                  <el-table-column
                    v-for="column in message.rows.columns"
                    :key="column.name"
                    :prop="column.name"
                    :label="column.name"
                    min-width="120"
                    show-overflow-tooltip
                  >
                    <template #default="{ row }">{{ cellText(row, column.name) }}</template>
                  </el-table-column>
                </el-table>
              </div>
              <div class="result__footer">
                <span>{{ message.rows.rowCount }} 行 · {{ message.rows.elapsedMs }}ms</span>
                <el-tag v-if="message.rows.truncated" size="small" type="warning" effect="plain">
                  已截断
                </el-tag>
              </div>
            </div>

            <span v-if="message.streaming" class="msg__cursor" aria-hidden="true" />
          </div>
        </article>
      </div>

      <footer class="chat__composer">
        <div v-if="canUseChat && chat.messages.length > 0" class="chat__examples">
          <button
            v-for="example in examples"
            :key="example"
            type="button"
            class="chip"
            @click="useExample(example)"
          >
            {{ example }}
          </button>
        </div>

        <div v-if="canUseChat" class="composer" :class="{ 'composer--busy': chat.streaming }">
          <el-input
            v-model="draft"
            class="composer__input"
            type="textarea"
            :rows="3"
            resize="none"
            placeholder="例如：查询每个月的订单数量"
            @keydown="onKeydown"
          />
          <div class="composer__bar">
            <span class="composer__hint">
              {{ chat.streaming ? '正在生成答案…' : 'Enter 发送 · Shift+Enter 换行' }}
            </span>
            <div class="composer__actions">
              <el-button v-if="chat.streaming" :icon="VideoPlay" @click="chat.stop()">停止</el-button>
              <el-button type="primary" :icon="Promotion" :disabled="!canSend" @click="onSend">
                发送
              </el-button>
            </div>
          </div>
        </div>
        <el-alert
          v-else
          class="chat__permission"
          type="info"
          :closable="false"
          show-icon
          title="当前账号没有对话权限"
          description="你可以查看已有内容，但不能发送问题或新建查询。如需使用对话，请联系管理员。"
        />
      </footer>
    </section>

    <CitationDrawer
      v-model="citationDrawerVisible"
      :citation="activeCitation"
      :citations="activeCitationList"
      @navigate="onCitationNavigate"
    />
  </div>
</template>

<style scoped>
/* ---------- Workspace surface -------------------------------------------- */

.chat {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.chat__panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  background: var(--ac-surface);
  border: 1px solid var(--ac-border-soft);
  border-radius: var(--ac-radius);
  box-shadow: var(--ac-shadow-sm);
}

/* ---------- Header ------------------------------------------------------- */

.chat__header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--ac-space-12) var(--ac-space-24);
  padding: var(--ac-space-16) var(--ac-space-24);
  border-bottom: 1px solid var(--ac-border-soft);
}

.chat__heading {
  min-width: 0;
}

.chat__title {
  margin: 0;
  color: var(--ac-ink);
  font-family: var(--ac-font-serif);
  font-size: var(--ac-text-title);
  font-weight: 600;
  line-height: 1.3;
}

.chat__subtitle {
  margin: var(--ac-space-4) 0 0;
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
  line-height: var(--ac-line-height);
}

.chat__controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ac-space-8) var(--ac-space-12);
  min-width: 0;
}

.chat__datasource {
  width: 240px;
  max-width: 100%;
}

.chat__datasource-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ac-space-12);
}

.chat__datasource-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat__mode {
  display: flex;
  align-items: center;
  gap: var(--ac-space-8);
}

.chat__mode-label {
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
}

.chat__new {
  /* Tertiary action: regular-control colour drops to muted without EP overrides. */
  --el-text-color-regular: var(--ac-muted);
}

/* ---------- Transcript --------------------------------------------------- */

.chat__messages {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: var(--ac-space-16);
  min-height: 0;
  padding: var(--ac-space-24);
  overflow-x: hidden;
  overflow-y: auto;
}

.empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--ac-space-4);
  padding: var(--ac-space-32) var(--ac-space-16);
  text-align: center;
}

.empty__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  color: var(--ac-surface);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.02em;
  background: linear-gradient(135deg, var(--ac-primary), var(--ac-primary-pressed));
  border-radius: var(--ac-radius);
  box-shadow: var(--ac-shadow-sm);
}

.empty__title {
  margin: var(--ac-space-8) 0 0;
  color: var(--ac-ink);
  font-size: var(--ac-text-lead);
  font-weight: 600;
}

.empty__text {
  margin: 0;
  color: var(--ac-text);
  font-size: var(--ac-text-body);
}

.empty__chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--ac-space-8);
  margin-top: var(--ac-space-12);
}

.empty__scope {
  margin: var(--ac-space-12) 0 0;
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
}

/* Outlined neutral chip; the hover tint marks it as actionable. */
.chip {
  padding: 6px 14px;
  color: var(--ac-text);
  font-family: inherit;
  font-size: var(--ac-text-meta);
  line-height: 1.4;
  cursor: pointer;
  background: var(--ac-surface);
  border: 1px solid var(--ac-border);
  border-radius: 999px;
  transition:
    color 0.15s ease,
    background-color 0.15s ease,
    border-color 0.15s ease;
}

.chip:hover {
  color: var(--ac-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

/* ---------- Messages ----------------------------------------------------- */

.msg {
  display: flex;
  flex-direction: column;
  gap: var(--ac-space-8);
  min-width: 0;
}

.msg--user {
  align-items: flex-end;
}

.msg__identity {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ac-space-8);
}

.msg__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  color: var(--ac-primary);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.02em;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-8);
  border-radius: var(--ac-radius-sm);
}

.msg__agent {
  color: var(--ac-text);
  font-size: var(--ac-text-meta);
  font-weight: 600;
}

.msg__state {
  padding: 0 var(--ac-space-8);
  font-size: var(--ac-text-meta);
  line-height: 20px;
  border-radius: 999px;
}

.msg__state--live {
  /* Ink text on a teal tint keeps small badges at AA contrast. */
  --msg-state-dot: var(--ac-accent);

  color: var(--ac-ink);
  background: var(--el-color-success-light-9);
}

.msg__state--error {
  --msg-state-dot: var(--el-color-danger);

  color: var(--el-color-danger-dark-2);
  background: var(--el-color-danger-light-9);
}

.msg__state::before {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 6px;
  vertical-align: 1px;
  content: '';
  background: var(--msg-state-dot);
  border-radius: 50%;
}

.msg__body {
  min-width: 0;
  word-break: break-word;
}

.msg--user .msg__body {
  max-width: 78%;
  padding: 10px var(--ac-space-16);
  color: var(--ac-ink);
  font-size: var(--ac-text-body);
  line-height: var(--ac-line-height);
  white-space: pre-wrap;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-8);
  border-radius: var(--ac-radius-lg);
}

.msg--assistant .msg__body {
  padding: var(--ac-space-16) var(--ac-space-24);
  background: var(--ac-surface);
  border: 1px solid var(--ac-border-soft);
  border-radius: var(--ac-radius);
  box-shadow: var(--ac-shadow-sm);
}

/* Streaming: the card stays primary-tinted until the answer completes. */
.msg--streaming .msg__body {
  border-color: var(--el-color-primary-light-5);
  box-shadow: 0 0 0 3px var(--el-color-primary-light-9);
}

/* Error: danger-tinted surface, but prose keeps ink contrast. */
.msg--error .msg__body {
  background: var(--el-color-danger-light-9);
  border-color: var(--el-color-danger-light-7);
  box-shadow: none;
}

.msg__text {
  margin: 0 0 var(--ac-space-8);
  color: var(--ac-ink);
  font-size: var(--ac-text-body);
  line-height: var(--ac-line-height);
  white-space: pre-wrap;
}

.msg__text:last-child {
  margin-bottom: 0;
}

.msg__explanation {
  margin: 0 0 var(--ac-space-12);
  padding-left: var(--ac-space-12);
  color: var(--ac-text);
  font-size: var(--ac-text-body);
  line-height: var(--ac-line-height);
  border-left: 3px solid var(--el-color-primary-light-7);
}

/* Citation badges sit directly under the answer text; no empty area otherwise. */
.msg__citations {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--ac-space-4);
  margin: 0 0 var(--ac-space-12);
}

.msg__citations-label {
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
  line-height: 1.4;
}

.msg__cursor {
  display: inline-block;
  width: 7px;
  height: 14px;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--ac-primary);
  animation: blink 1s step-end infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

/* ---------- Steps -------------------------------------------------------- */

.steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0 0 var(--ac-space-12);
  padding: 0;
  list-style: none;
}

.steps__item {
  display: flex;
  align-items: flex-start;
  gap: var(--ac-space-8);
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
  line-height: var(--ac-line-height);
}

.steps__dot {
  flex: none;
  width: 6px;
  height: 6px;
  margin-top: 7px;
  background: var(--ac-accent);
  border-radius: 50%;
}

.steps--live .steps__item:last-child .steps__dot {
  box-shadow: 0 0 0 3px var(--el-color-success-light-9);
  animation: step-pulse 1.2s ease-in-out infinite;
}

@keyframes step-pulse {
  50% {
    opacity: 0.25;
  }
}

/* ---------- Prose code + markdown tables --------------------------------- */

.code {
  margin: var(--ac-space-8) 0;
  padding: var(--ac-space-12);
  overflow-x: auto;
  color: var(--ac-ink);
  font-size: 13px;
  line-height: 1.6;
  background: var(--ac-fill);
  border: 1px solid var(--ac-border-soft);
  border-radius: var(--ac-radius-sm);
}

.md-table {
  max-width: 100%;
  margin: var(--ac-space-8) 0;
  overflow-x: auto;
  border: 1px solid var(--ac-border-soft);
  border-radius: var(--ac-radius-sm);
}

.md-table table {
  width: 100%;
  font-size: 13px;
  border-collapse: collapse;
}

.md-table th,
.md-table td {
  padding: 6px 10px;
  text-align: left;
  white-space: nowrap;
  border-bottom: 1px solid var(--ac-border-soft);
}

.md-table th {
  background: var(--ac-fill);
  font-weight: 600;
}

.md-table tbody tr:nth-child(even) td {
  background: var(--ac-fill-soft);
}

/* ---------- SQL ---------------------------------------------------------- */

.sql {
  margin-top: var(--ac-space-12);
  overflow: hidden;
  background: var(--ac-code-surface);
  border-radius: var(--ac-radius);
}

.sql__bar {
  /* Element Plus text-button colours are scoped to the ink header. */
  --el-text-color-regular: var(--ac-code-text);
  --el-color-primary: var(--el-color-white);
  --el-fill-color-light: var(--ac-code-wash);
  --el-fill-color: var(--ac-code-wash-strong);

  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ac-space-8);
  padding: var(--ac-space-8) var(--ac-space-12);
  border-bottom: 1px solid var(--ac-code-rule);
}

.sql__label {
  color: var(--el-color-white);
  font-family: var(--ac-font-mono);
  font-size: var(--ac-text-meta);
  font-weight: 600;
  letter-spacing: 0.04em;
}

.sql__tag {
  padding: 0 var(--ac-space-8);
  color: var(--ac-code-muted);
  font-size: var(--ac-text-meta);
  line-height: 20px;
  background: var(--ac-code-rule);
  border-radius: 999px;
}

.sql__tag--scope {
  color: var(--ac-code-accent);
  background: var(--ac-code-accent-wash);
}

.sql__copy {
  margin-left: auto;
}

.sql__code {
  margin: 0;
  padding: var(--ac-space-12) var(--ac-space-16);
  overflow-x: auto;
  color: var(--ac-code-text);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre;
}

/* ---------- Result ------------------------------------------------------- */

.result {
  min-width: 0;
  margin-top: var(--ac-space-12);
}

.result__chart {
  margin-bottom: var(--ac-space-12);
}

/* The table owns its horizontal scroll; the page never widens. */
.result__table {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--ac-border-soft);
  border-radius: var(--ac-radius-sm);
}

.result__table :deep(.el-table) {
  width: 100%;
}

.result__table :deep(.el-table th.el-table__cell) {
  color: var(--ac-text);
  font-weight: 600;
  background: var(--el-fill-color-light);
}

.result__table :deep(.el-table .el-table__cell) {
  font-size: 13px;
}

.result__footer {
  display: flex;
  align-items: center;
  gap: var(--ac-space-8);
  margin-top: 6px;
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
}

/* ---------- Composer ----------------------------------------------------- */

.chat__composer {
  display: flex;
  flex-direction: column;
  gap: var(--ac-space-8);
  padding: var(--ac-space-16) var(--ac-space-24) var(--ac-space-24);
  background: var(--ac-surface);
  border-top: 1px solid var(--ac-border-soft);
}

.chat__examples {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ac-space-8);
}

.composer {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--ac-surface);
  border: 1px solid var(--ac-border);
  border-radius: var(--ac-radius-lg);
  box-shadow: var(--ac-shadow-sm);
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.composer:focus-within {
  border-color: var(--ac-primary);
  box-shadow: 0 0 0 3px var(--el-color-primary-light-5);
}

.composer--busy {
  border-color: var(--el-color-primary-light-5);
}

.composer :deep(.el-textarea__inner) {
  padding: var(--ac-space-12) var(--ac-space-16);
  color: var(--ac-ink);
  font-size: var(--ac-text-body);
  line-height: var(--ac-line-height);
  background: transparent;
  border: none;
  box-shadow: none;
  resize: none;
}

.composer__bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ac-space-8);
  padding: 0 var(--ac-space-12) var(--ac-space-8) var(--ac-space-16);
}

.composer__hint {
  /* Supporting copy, not a placeholder: muted grey clears AA at 12px. */
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
}

.composer__actions {
  display: flex;
  gap: var(--ac-space-8);
}

/* ---------- Responsive --------------------------------------------------- */

@media (max-width: 1023px) {
  .chat__header {
    align-items: flex-start;
  }

  .chat__controls {
    width: 100%;
  }

  .chat__datasource {
    flex: 1 1 200px;
    width: auto;
    min-width: 0;
  }
}

@media (max-width: 767px) {
  .chat__header {
    padding: var(--ac-space-16);
  }

  .chat__datasource {
    flex: 1 1 160px;
  }

  .chat__messages {
    gap: var(--ac-space-12);
    padding: var(--ac-space-16);
  }

  .msg--user .msg__body {
    max-width: 88%;
  }

  .msg--assistant .msg__body {
    padding: var(--ac-space-12) var(--ac-space-16);
  }

  .chat__composer {
    padding: var(--ac-space-12) var(--ac-space-16) var(--ac-space-16);
  }
}
</style>
