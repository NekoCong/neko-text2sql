import type { Citation } from '@/api/knowledge'

/** The authenticated user surfaced by the auth endpoints. */
export interface AuthUser {
  id: string
  username: string
  displayName: string | null
  /** Backend role: `admin` | `student` | `counselor`. */
  role?: string
}

/** Successful `register` / `login` response: the JWT plus the resolved user. */
export interface AuthResponse {
  token: string
  expiresIn: number
  user: AuthUser
}

/** A datasource the backend can generate SQL against. */
export interface Datasource {
  id: string
  name: string
  dialect: string
  /** True for the datasource the backend picks by default. */
  default?: boolean
}

/** A datasource record returned by the management endpoints. */
export interface DatasourceDetail {
  id: string
  name: string
  dialect: string
  url: string
  /** True for the datasource the backend picks by default. */
  default?: boolean
  /** JDBC driver class; only present when the backend exposes it. */
  driver?: string
  /** Connection username; only present when the backend exposes it. */
  username?: string
}

/** Request body for creating, updating or testing a datasource. */
export interface DatasourcePayload {
  id: string
  name: string
  driver: string
  url: string
  username: string
  password: string
  dialect: string
  seed: boolean
  maxPoolSize: number
}

/** Outcome of a datasource connection test. */
export interface ConnectionTestResult {
  ok: boolean
  message: string
  elapsedMs: number
}

/** Metadata of a single result column. */
export interface ColumnMeta {
  name: string
  typeName: string
}

/** Result rows returned by the execute phase. */
export interface QueryResult {
  columns: ColumnMeta[]
  rows: Array<Record<string, unknown>>
  rowCount: number
  truncated: boolean
  elapsedMs: number
}

/** Pipeline phase a progress step belongs to. */
export type RunPhase = 'introspect' | 'generate' | 'execute'

/** A single pipeline progress entry emitted while answering. */
export interface RunStep {
  phase: RunPhase
  detail: string
}

/** A tool step emitted by the agent stream. */
export interface ToolStep {
  phase: 'tool'
  tool: string
  status: string
  state?: string
  reason?: string
}

/** A pipeline progress entry or an agent tool step. */
export type ChatStep = RunStep | ToolStep

/** Payload of the `sql` SSE event. */
export interface SqlEvent {
  datasourceId: string
  sql: string
  dialect?: string
  explanation?: string | null
  /** Agent stream: rows produced by the enforced query. */
  rowCount?: number
  /** Agent stream: the SQL already carries the caller's data scope. */
  scopeApplied?: boolean
}

/** Chart kind suggested by the backend result visualization. */
export type ChartType = 'BAR' | 'LINE' | 'PIE' | 'NONE'

/** Chart suggestion emitted by the `chart` SSE event. */
export interface ChartSpec {
  type: ChartType
  xField: string | null
  yField: string | null
  title: string | null
}

/** A single chat message rendered in the conversation. */
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  /** True while the assistant answer is still streaming in. */
  streaming?: boolean
  /** True when the message represents an error. */
  error?: boolean
  /** Pipeline progress reported by the backend, in arrival order. */
  steps?: ChatStep[]
  /** Generated SQL, rendered from the `sql` event. */
  sql?: string
  /** Dialect of the generated SQL. */
  dialect?: string
  /** Short natural-language explanation of the SQL. */
  explanation?: string
  /** Result rows of the executed SQL. */
  rows?: QueryResult
  /** Chart suggestion rendered above the result table. */
  chart?: ChartSpec
  /** True when the displayed SQL was filtered for the caller's data scope. */
  scopeApplied?: boolean
  /** Rows produced by the agent's query (the agent stream has no result table). */
  rowCount?: number
  /** Document citations backing the answer; empty/absent means no citation area. */
  citations?: Citation[]
}

/** Payload of the `conversation` SSE event: the conversation the exchange persisted to. */
export interface ConversationEvent {
  id: string
  title: string
}

/** Callbacks for a streaming text-to-SQL request. */
export interface SseHandlers {
  onDelta: (text: string) => void
  /** The backend created (or reused) the conversation backing this exchange. */
  onConversation?: (conversation: ConversationEvent) => void
  onStep?: (step: RunStep) => void
  onSql?: (payload: SqlEvent) => void
  onRows?: (result: QueryResult) => void
  onChart?: (chart: ChartSpec) => void
  /** Citations attached to the answer, emitted once the retrieval step is done. */
  onCitations?: (citations: Citation[]) => void
  onDone?: () => void
  onError?: (message: string) => void
}

/** A persisted conversation (a row in the left-hand history sidebar). */
export interface Conversation {
  /** Server-generated UUID. */
  id: string
  title: string
  datasourceId: string | null
  modelConfigId: string | null
  /** Epoch millis. */
  createdAt: number
  /** Epoch millis. */
  updatedAt: number
}

/** Request body for creating a conversation; every field is optional. */
export interface ConversationCreatePayload {
  title?: string
  datasourceId?: string
  modelConfigId?: string
}

/** A persisted chat message loaded from the conversation history endpoint. */
export interface HistoryMessage {
  /** Server-generated UUID. */
  id: string
  role: 'user' | 'assistant'
  content: string
  sql: string | null
  chart: ChartSpec | null
  rows: QueryResult | null
  /** Document citations; persisted either as an array or as a JSON string. */
  citations?: Citation[] | string | null
  /** Epoch millis. */
  createdAt: number
}

/** A per-user model configuration. The stored API key is never returned. */
export interface ModelConfig {
  id: string
  name: string
  provider: string
  model: string
  baseUrl: string | null
  isDefault: boolean
  /** True when an API key is stored server-side. */
  hasApiKey: boolean
}

/** Request body for creating or updating a model configuration. */
export interface ModelConfigPayload {
  name: string
  provider: string
  model: string
  /** On update, a blank value keeps the stored key. */
  apiKey?: string
  baseUrl?: string
  isDefault?: boolean
}
