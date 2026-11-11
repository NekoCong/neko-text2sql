import { isAxiosError } from 'axios'
import { http } from './http'

/**
 * Client for the knowledge-base API (`/api/text2sql/knowledge`).
 *
 * The three knowledge types share one item model; documents, term mappings and the
 * retrieval endpoints hang off the same base path.
 */
const BASE = '/text2sql/knowledge'

/** Original-source preview is open to any authenticated caller, not only administrators. */
const CITATION_BASE = '/text2sql/citations'

/** Which of the three knowledge bases an entry belongs to. */
export type KnowledgeType = 'METADATA' | 'BUSINESS' | 'AGENT'

/** Visibility of an entry: one datasource, one agent, or every caller. */
export type KnowledgeScope = 'GLOBAL' | 'DATASOURCE' | 'AGENT'

/** Ingestion state of a knowledge item. */
export type KnowledgeStatus = 'PENDING' | 'PARSING' | 'EMBEDDING' | 'READY' | 'FAILED'

/** Stage an item failed in; `null` when it did not fail. */
export type FailureStage = 'PARSE' | 'CHUNK' | 'EMBED' | null

/** Parse state of a source document (before/while it becomes knowledge items). */
export type ParseStatus = 'PENDING' | 'PARSING' | 'PARSED' | 'FAILED'

/** Where a document came from. */
export type DocumentSourceType = 'UPLOAD' | 'URL'

/** What a term mapping points at. */
export type TermTargetType = 'TABLE' | 'COLUMN'

/** Parser a document can be (re)parsed with. */
export type ParseEngine = 'mineru' | 'tika'

/** One uploaded / imported source document and its parse state. */
export interface KnowledgeDocumentView {
  id: string
  itemId: string
  sourceType: DocumentSourceType
  sourceUrl?: string | null
  filename: string
  mimeType: string
  sizeBytes: number
  pageCount?: number | null
  parseEngine?: string | null
  parseEngineVersion?: string | null
  parseStatus: ParseStatus
  parseFailureReason?: string | null
  fetchedAt?: string | null
  createdAt: string
  itemStatus: KnowledgeStatus
  itemTitle: string
}

/** A single knowledge entry, with its source document when it has one. */
export interface KnowledgeItemView {
  id: string
  knowledgeType: KnowledgeType
  scope: KnowledgeScope
  datasourceId?: string | null
  agentId?: string | null
  itemKey: string
  title: string
  tableName?: string | null
  content?: string | null
  status: KnowledgeStatus
  failureStage?: FailureStage
  failureReason?: string | null
  embeddingModel?: string | null
  createdAt: string
  updatedAt: string
  document?: KnowledgeDocumentView | null
}

/** Where a citation points inside its source document. */
export interface Citation {
  documentId: string
  filename: string
  mimeType: string
  sourceType: DocumentSourceType
  pageNo?: number | null
  /** Normalised 0–1000 box, `[x0, y0, x1, y1]`, origin top-left. */
  bbox?: number[] | null
  charStart?: number | null
  charEnd?: number | null
  quote: string
  previewUrl?: string | null
  previewExpiresAt?: string | null
  fetchedAt?: string | null
}

/** One retrieval hit. */
export interface KnowledgeHit {
  itemId: string
  knowledgeType: KnowledgeType
  title: string
  content: string
  score: number
  citation?: Citation | null
}

/** Retrieval trace returned by the debug endpoint. */
export interface SearchTrace {
  rewrittenQuery: string
  businessHits: number
  agentHits: number
  candidateTables: string[]
  finalTables: string[]
  fallback: boolean
  fallbackReason?: string | null
  citations: Citation[]
}

/** Full retrieval response: hits, their citations and the decision trace. */
export interface SearchResultView {
  hits: KnowledgeHit[]
  citations: Citation[]
  trace: SearchTrace
}

/** One normalised block of a parsed document. */
export interface ContentBlock {
  blockId: string
  type: string
  text: string
  pageNo: number
  bbox?: number[] | null
  charStart: number
  charEnd: number
  imagePath?: string | null
}

/** Normalised parse result, used by the text preview. */
export interface KnowledgeDocumentContent {
  documentId: string
  plainText: string
  pageCount: number | null
  blocks: ContentBlock[]
}

/** Request body for creating or updating a knowledge item. */
export interface KnowledgeItemRequest {
  knowledgeType: KnowledgeType
  scope: KnowledgeScope
  datasourceId?: string | null
  agentId?: string | null
  title: string
  tableName?: string | null
  content?: string | null
}

/** Request body for a term mapping. */
export interface TermMappingRequest {
  datasourceId: string
  term: string
  targetType: TermTargetType
  tableName?: string | null
  columnName?: string | null
  description?: string | null
}

/** A stored term mapping. */
export interface TermMappingView {
  id: string
  datasourceId: string
  term: string
  targetType: TermTargetType
  tableName?: string | null
  columnName?: string | null
  description?: string | null
  createdAt?: string | null
}

/** Result of a metadata sync run. */
export interface MetadataSyncResult {
  datasourceId: string
  tableCount: number
  itemIds: string[]
}

/** Short-lived preview URL for a document original. */
export interface PreviewUrlResult {
  url: string
  expiresAt: string
}

/** Query filters shared by the item and document list endpoints. */
export interface KnowledgeQuery {
  type?: KnowledgeType
  datasourceId?: string
  agentId?: string
  status?: KnowledgeStatus
}

/** Retrieval request; the same body feeds `/search` and `/search/debug`. */
export interface KnowledgeSearchRequest {
  query: string
  datasourceId?: string
  agentId?: string
  topK?: number
}

/** Multipart metadata for a document upload. */
export interface KnowledgeUploadMeta {
  knowledgeType: KnowledgeType
  datasourceId?: string
  agentId?: string
  title?: string
}

/** Body of the URL import endpoint. */
export interface KnowledgeUrlImportRequest extends KnowledgeUploadMeta {
  url: string
}

/** Lists knowledge items, optionally narrowed by type / datasource / agent / status. */
export async function listItems(query: KnowledgeQuery = {}): Promise<KnowledgeItemView[]> {
  const { data } = await http.get<KnowledgeItemView[]>(`${BASE}/items`, { params: query })
  return data
}

/** Creates a knowledge item. */
export async function createItem(payload: KnowledgeItemRequest): Promise<KnowledgeItemView> {
  const { data } = await http.post<KnowledgeItemView>(`${BASE}/items`, payload)
  return data
}

/** Updates an existing knowledge item. */
export async function updateItem(
  id: string,
  payload: KnowledgeItemRequest,
): Promise<KnowledgeItemView> {
  const { data } = await http.put<KnowledgeItemView>(
    `${BASE}/items/${encodeURIComponent(id)}`,
    payload,
  )
  return data
}

/** Deletes a knowledge item together with its derived content. */
export async function deleteItem(id: string): Promise<void> {
  await http.delete(`${BASE}/items/${encodeURIComponent(id)}`)
}

/** Re-syncs table metadata for one datasource. */
export async function syncMetadata(datasourceId: string): Promise<MetadataSyncResult> {
  const { data } = await http.post<MetadataSyncResult>(`${BASE}/metadata/sync`, null, {
    params: { datasourceId },
  })
  return data
}

/** Retries ingestion of a failed item from its failure stage. */
export async function retryItem(id: string): Promise<KnowledgeItemView> {
  const { data } = await http.post<KnowledgeItemView>(
    `${BASE}/items/${encodeURIComponent(id)}/retry`,
  )
  return data
}

/** Lists the term mappings of one datasource. */
export async function listTermMappings(datasourceId: string): Promise<TermMappingView[]> {
  const { data } = await http.get<TermMappingView[]>(`${BASE}/term-mappings`, {
    params: { datasourceId },
  })
  return data
}

/** Creates a term mapping. */
export async function createTermMapping(
  payload: TermMappingRequest,
): Promise<TermMappingView> {
  const { data } = await http.post<TermMappingView>(`${BASE}/term-mappings`, payload)
  return data
}

/** Deletes a term mapping. */
export async function deleteTermMapping(id: string): Promise<void> {
  await http.delete(`${BASE}/term-mappings/${encodeURIComponent(id)}`)
}

/** Uploads a source document as multipart form data. */
export async function uploadDocument(
  file: File,
  meta: KnowledgeUploadMeta,
): Promise<KnowledgeDocumentView> {
  const form = new FormData()
  form.append('file', file)
  form.append('knowledgeType', meta.knowledgeType)
  if (meta.datasourceId) {
    form.append('datasourceId', meta.datasourceId)
  }
  if (meta.agentId) {
    form.append('agentId', meta.agentId)
  }
  if (meta.title) {
    form.append('title', meta.title)
  }
  const { data } = await http.post<KnowledgeDocumentView>(`${BASE}/documents`, form)
  return data
}

/** Imports a web page as a knowledge document (the backend snapshots the page). */
export async function importDocumentUrl(
  payload: KnowledgeUrlImportRequest,
): Promise<KnowledgeDocumentView> {
  const { data } = await http.post<KnowledgeDocumentView>(`${BASE}/documents/url`, payload)
  return data
}

/** Lists documents (and their parse state) by knowledge type / scope. */
export async function listDocuments(
  query: KnowledgeQuery = {},
): Promise<KnowledgeDocumentView[]> {
  const { data } = await http.get<KnowledgeDocumentView[]>(`${BASE}/documents`, {
    params: query,
  })
  return data
}

/** Re-parses a document, optionally with another engine. */
export async function reparseDocument(
  id: string,
  engine?: ParseEngine,
): Promise<KnowledgeDocumentView> {
  const { data } = await http.post<KnowledgeDocumentView>(
    `${BASE}/documents/${encodeURIComponent(id)}/reparse`,
    null,
    { params: engine ? { engine } : undefined },
  )
  return data
}

/** Issues a short-lived preview URL for a document original. */
export async function getDocumentPreviewUrl(id: string): Promise<PreviewUrlResult> {
  const { data } = await http.get<PreviewUrlResult>(
    `${CITATION_BASE}/${encodeURIComponent(id)}/preview-url`,
  )
  return data
}

/**
 * Downloads a document's original bytes through the API (same origin, bearer token).
 *
 * Preferred over the presigned URL for rendering: no CORS, no expiry and no need to expose the
 * object store to the browser.
 *
 * @param id document id
 * @returns the file payload
 */
export async function getDocumentFile(id: string): Promise<Blob> {
  const { data } = await http.get<Blob>(`${CITATION_BASE}/${encodeURIComponent(id)}/file`, {
    responseType: 'blob',
  })
  return data
}

/** Reads the normalised parse result of a document (text preview). */
export async function getDocumentContent(id: string): Promise<KnowledgeDocumentContent> {
  const { data } = await http.get<KnowledgeDocumentContent>(
    `${CITATION_BASE}/${encodeURIComponent(id)}/content`,
  )
  return data
}

/** Retrieves knowledge for the caller's own scope. */
export async function searchKnowledge(
  payload: KnowledgeSearchRequest,
): Promise<SearchResultView> {
  const { data } = await http.post<SearchResultView>(`${BASE}/search`, payload)
  return data
}

/** Retrieves knowledge and returns the decision trace (admin only). */
export async function searchKnowledgeDebug(
  payload: KnowledgeSearchRequest,
): Promise<SearchResultView> {
  const { data } = await http.post<SearchResultView>(`${BASE}/search/debug`, payload)
  return data
}

/**
 * Extracts a user-facing message from a knowledge API failure. The backend reports
 * errors as plain-text bodies, so surface that text whenever present.
 *
 * @param error error thrown by the API call
 * @param fallback message used when the backend sent no readable body
 * @returns the message to display
 */
export function knowledgeErrorMessage(error: unknown, fallback: string): string {
  if (isAxiosError(error)) {
    const body: unknown = error.response?.data
    if (typeof body === 'string' && body.trim().length > 0) {
      return body
    }
    const status = error.response?.status
    if (status === 400) {
      return '请求参数无效，请检查填写内容'
    }
    if (status === 403) {
      return '没有权限执行该操作'
    }
    if (status === 404) {
      return '条目不存在，可能已被删除'
    }
    if (status === 413) {
      return '文件过大，无法上传'
    }
  }
  return fallback
}
