<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  Collection,
  Delete,
  Edit,
  Link,
  Plus,
  Refresh,
  RefreshRight,
  Search,
  Upload,
  View,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules, UploadFile } from 'element-plus'
import { listDatasources } from '@/api/datasources'
import {
  createItem,
  createTermMapping,
  deleteItem,
  deleteTermMapping,
  importDocumentUrl,
  knowledgeErrorMessage,
  listDocuments,
  listItems,
  listTermMappings,
  reparseDocument,
  retryItem,
  searchKnowledgeDebug,
  syncMetadata,
  updateItem,
  uploadDocument,
} from '@/api/knowledge'
import type {
  Citation,
  FailureStage,
  KnowledgeDocumentView,
  KnowledgeHit,
  KnowledgeItemRequest,
  KnowledgeItemView,
  KnowledgeScope,
  KnowledgeStatus,
  KnowledgeType,
  ParseEngine,
  ParseStatus,
  SearchResultView,
  TermMappingView,
  TermTargetType,
} from '@/api/knowledge'
import type { DatasourceDetail } from '@/types'
import { useAuthStore } from '@/stores/auth'
import CitationBadge from '@/components/CitationBadge.vue'
import CitationDrawer from '@/components/CitationDrawer.vue'

type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

const tabs: Array<{ type: KnowledgeType; label: string; hint: string }> = [
  {
    type: 'METADATA',
    label: '元数据',
    hint: '由「元数据同步」从数据源自动生成，检索时用于召回相关表与字段。',
  },
  {
    type: 'BUSINESS',
    label: '业务知识',
    hint: '业务口径、指标定义与文档；可挂原文，回答会带引用角标。',
  },
  {
    type: 'AGENT',
    label: '智能体知识',
    hint: '供 Agent 使用的文档与说明；按智能体范围隔离。',
  },
]

const typeLabels: Record<KnowledgeType, string> = {
  METADATA: '元数据',
  BUSINESS: '业务知识',
  AGENT: '智能体知识',
}

const statusLabels: Record<KnowledgeStatus, string> = {
  PENDING: '待处理',
  PARSING: '解析中',
  EMBEDDING: '向量化',
  READY: '已就绪',
  FAILED: '失败',
}

const statusTagTypes: Record<KnowledgeStatus, TagType> = {
  PENDING: 'info',
  PARSING: 'primary',
  EMBEDDING: 'warning',
  READY: 'success',
  FAILED: 'danger',
}

const parseStatusLabels: Record<ParseStatus, string> = {
  PENDING: '待解析',
  PARSING: '解析中',
  PARSED: '已解析',
  FAILED: '解析失败',
}

const parseStatusTagTypes: Record<ParseStatus, TagType> = {
  PENDING: 'info',
  PARSING: 'primary',
  PARSED: 'success',
  FAILED: 'danger',
}

const hitTagTypes: Record<KnowledgeType, TagType> = {
  METADATA: 'info',
  BUSINESS: 'primary',
  AGENT: 'warning',
}

const auth = useAuthStore()

/**
 * The route already carries `meta.admin` (the router guard redirects non-admins);
 * this second check keeps the page itself from issuing any knowledge request.
 */
const isAdmin = computed(() => auth.user?.role === 'admin')

const datasources = ref<DatasourceDetail[]>([])
/** Tab id is kept as a plain string because `el-tabs` emits string | number. */
const activeTab = ref<string>('METADATA')
const activeType = computed<KnowledgeType>(() => activeTab.value as KnowledgeType)
const activeTabLabel = computed(() => typeLabels[activeType.value])
const activeTabHint = computed(
  () => tabs.find((tab) => tab.type === activeType.value)?.hint ?? '',
)

const items = ref<KnowledgeItemView[]>([])
const documents = ref<KnowledgeDocumentView[]>([])
const loading = ref(false)
const documentsLoading = ref(false)

/** Datasource / agent the toolbar actions apply to. */
const toolbarDatasourceId = ref('')
const toolbarAgentId = ref('')

// ---------- Item create / edit ---------------------------------------------

interface ItemFormState {
  scope: string
  datasourceId: string
  agentId: string
  title: string
  tableName: string
  content: string
}

const itemDialogVisible = ref(false)
const itemSaving = ref(false)
const editingId = ref<string | null>(null)
const itemFormRef = ref<FormInstance>()
const itemForm = reactive<ItemFormState>(emptyItemForm())

const itemDialogTitle = computed(() => (editingId.value ? '编辑条目' : '新增条目'))

const scopeOptions = computed(() => {
  if (activeType.value === 'METADATA') {
    return [{ value: 'DATASOURCE', label: '数据源' }]
  }
  if (activeType.value === 'AGENT') {
    return [
      { value: 'AGENT', label: '智能体' },
      { value: 'GLOBAL', label: '全局' },
    ]
  }
  return [
    { value: 'DATASOURCE', label: '数据源' },
    { value: 'GLOBAL', label: '全局' },
  ]
})

const itemRules = computed<FormRules<ItemFormState>>(() => ({
  scope: [{ required: true, message: '请选择范围', trigger: 'change' }],
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  datasourceId:
    itemForm.scope === 'DATASOURCE'
      ? [{ required: true, message: '请选择数据源', trigger: 'change' }]
      : [],
  agentId:
    itemForm.scope === 'AGENT'
      ? [{ required: true, message: '请输入智能体标识', trigger: 'blur' }]
      : [],
  tableName:
    activeType.value === 'METADATA'
      ? [{ required: true, message: '请输入表名', trigger: 'blur' }]
      : [],
  content:
    activeType.value === 'METADATA'
      ? []
      : [{ required: true, message: '请输入知识内容', trigger: 'blur' }],
}))

/** A pristine form for the active tab. */
function emptyItemForm(): ItemFormState {
  return {
    scope: 'DATASOURCE',
    datasourceId: '',
    agentId: '',
    title: '',
    tableName: '',
    content: '',
  }
}

// ---------- Documents -------------------------------------------------------

const uploading = ref(false)
const reparsingId = ref<string | null>(null)
/** Per-row engine choice for 重新解析. */
const engineChoice = reactive<Record<string, string>>({})

const urlDialogVisible = ref(false)
const urlSaving = ref(false)
const urlForm = reactive({ url: '', title: '' })

// ---------- Term mappings ---------------------------------------------------

const termDialogVisible = ref(false)
const termLoading = ref(false)
const termSaving = ref(false)
const terms = ref<TermMappingView[]>([])
const termDatasourceId = ref('')
const termForm = reactive({
  term: '',
  targetType: 'TABLE' as string,
  tableName: '',
  columnName: '',
  description: '',
})

// ---------- Search debug ----------------------------------------------------

const searchDrawerVisible = ref(false)
const searchLoading = ref(false)
const searchQuery = ref('')
const searchAgentId = ref('')
const searchTopK = ref<number | undefined>(5)
const searchResult = ref<SearchResultView | null>(null)

// ---------- Citation preview ------------------------------------------------

const citationDrawerVisible = ref(false)
const activeCitation = ref<Citation | null>(null)
const activeCitationList = ref<Citation[]>([])

/** Pending refresh timers scheduled after an ingest action. */
let pollTimers: number[] = []

// ---------- Loading ---------------------------------------------------------

/** Loads the datasource catalog used by every picker on the page. */
async function loadDatasources(): Promise<void> {
  try {
    datasources.value = await listDatasources()
    if (!toolbarDatasourceId.value && datasources.value.length > 0) {
      const preferred = datasources.value.find((item) => item.default) ?? datasources.value[0]
      toolbarDatasourceId.value = preferred.id
    }
  } catch {
    // An empty picker is enough: the actions that need it stay disabled.
    datasources.value = []
  }
}

/** Loads the items of the active tab. */
async function loadItems(): Promise<void> {
  loading.value = true
  try {
    items.value = await listItems({ type: activeType.value })
  } catch (error) {
    ElMessage.error(knowledgeErrorMessage(error, '加载知识条目失败'))
  } finally {
    loading.value = false
  }
}

/** Loads the source documents of the active tab and seeds the engine pickers. */
async function loadDocuments(): Promise<void> {
  if (activeType.value === 'METADATA') {
    documents.value = []
    return
  }
  documentsLoading.value = true
  try {
    documents.value = await listDocuments({ type: activeType.value })
    for (const document of documents.value) {
      if (!engineChoice[document.id]) {
        engineChoice[document.id] =
          document.parseEngine === 'tika' ? 'tika' : document.parseEngine === 'mineru' ? 'mineru' : 'mineru'
      }
    }
  } catch (error) {
    ElMessage.error(knowledgeErrorMessage(error, '加载文档列表失败'))
  } finally {
    documentsLoading.value = false
  }
}

async function refresh(): Promise<void> {
  await Promise.all([loadItems(), loadDocuments()])
}

/** Ingestion is asynchronous: poll a few times so the new state shows up. */
function scheduleRefresh(): void {
  clearPollTimers()
  pollTimers = [1500, 4000, 8000].map((delay) =>
    window.setTimeout(() => {
      void refresh()
    }, delay),
  )
}

function clearPollTimers(): void {
  pollTimers.forEach((timer) => window.clearTimeout(timer))
  pollTimers = []
}

// ---------- Item CRUD -------------------------------------------------------

function openCreate(): void {
  editingId.value = null
  Object.assign(itemForm, emptyItemForm())
  itemForm.datasourceId = toolbarDatasourceId.value || datasources.value[0]?.id || ''
  itemForm.scope = activeType.value === 'AGENT' ? 'AGENT' : 'DATASOURCE'
  itemForm.agentId = activeType.value === 'AGENT' ? toolbarAgentId.value : ''
  itemDialogVisible.value = true
}

function openEdit(row: KnowledgeItemView): void {
  editingId.value = row.id
  Object.assign(itemForm, {
    scope: row.scope,
    datasourceId: row.datasourceId ?? '',
    agentId: row.agentId ?? '',
    title: row.title,
    tableName: row.tableName ?? '',
    content: row.content ?? '',
  })
  itemDialogVisible.value = true
}

async function submitItem(): Promise<void> {
  const valid = await itemFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  const payload: KnowledgeItemRequest = {
    knowledgeType: activeType.value,
    scope: itemForm.scope as KnowledgeScope,
    datasourceId: itemForm.scope === 'DATASOURCE' ? itemForm.datasourceId : null,
    agentId: itemForm.scope === 'AGENT' ? itemForm.agentId.trim() : null,
    title: itemForm.title.trim(),
    tableName: activeType.value === 'METADATA' ? itemForm.tableName.trim() : null,
    content: itemForm.content.trim() ? itemForm.content : null,
  }
  itemSaving.value = true
  try {
    if (editingId.value) {
      await updateItem(editingId.value, payload)
      ElMessage.success('条目已更新')
    } else {
      await createItem(payload)
      ElMessage.success('条目已创建，正在入库')
    }
    itemDialogVisible.value = false
    scheduleRefresh()
  } catch (error) {
    ElMessage.error(knowledgeErrorMessage(error, editingId.value ? '更新条目失败' : '创建条目失败'))
  } finally {
    itemSaving.value = false
  }
}

async function removeItem(row: KnowledgeItemView): Promise<void> {
  try {
    await deleteItem(row.id)
    ElMessage.success('条目已删除')
    await refresh()
  } catch (error) {
    ElMessage.error(knowledgeErrorMessage(error, '删除条目失败'))
  }
}

async function retry(row: KnowledgeItemView): Promise<void> {
  try {
    await retryItem(row.id)
    ElMessage.success('已提交重试')
    scheduleRefresh()
  } catch (error) {
    ElMessage.error(knowledgeErrorMessage(error, '重试失败'))
  }
}

// ---------- Metadata sync ---------------------------------------------------

async function runSync(): Promise<void> {
  if (!toolbarDatasourceId.value) {
    ElMessage.warning('请先选择数据源')
    return
  }
  syncing.value = true
  try {
    const result = await syncMetadata(toolbarDatasourceId.value)
    ElMessage.success(`已同步 ${result.tableCount} 张表，正在生成元数据条目`)
    scheduleRefresh()
  } catch (error) {
    ElMessage.error(knowledgeErrorMessage(error, '元数据同步失败'))
  } finally {
    syncing.value = false
  }
}

const syncing = ref(false)

// ---------- Documents -------------------------------------------------------

async function onFileChange(uploadFile: UploadFile): Promise<void> {
  const file = uploadFile.raw
  if (!file) {
    return
  }
  if (activeType.value === 'METADATA') {
    ElMessage.warning('元数据由「元数据同步」生成，无需上传文档')
    return
  }
  uploading.value = true
  try {
    await uploadDocument(file, {
      knowledgeType: activeType.value,
      datasourceId:
        activeType.value === 'BUSINESS' && toolbarDatasourceId.value
          ? toolbarDatasourceId.value
          : undefined,
      agentId: activeType.value === 'AGENT' ? toolbarAgentId.value || undefined : undefined,
      title: file.name,
    })
    ElMessage.success('文档已上传，正在解析')
    scheduleRefresh()
  } catch (error) {
    ElMessage.error(knowledgeErrorMessage(error, '上传文档失败'))
  } finally {
    uploading.value = false
  }
}

function openUrlDialog(): void {
  urlForm.url = ''
  urlForm.title = ''
  urlDialogVisible.value = true
}

async function submitUrl(): Promise<void> {
  const url = urlForm.url.trim()
  if (!/^https?:\/\//i.test(url)) {
    ElMessage.warning('请输入以 http:// 或 https:// 开头的网页地址')
    return
  }
  urlSaving.value = true
  try {
    await importDocumentUrl({
      url,
      knowledgeType: activeType.value,
      datasourceId:
        activeType.value === 'BUSINESS' && toolbarDatasourceId.value
          ? toolbarDatasourceId.value
          : undefined,
      agentId: activeType.value === 'AGENT' ? toolbarAgentId.value || undefined : undefined,
      title: urlForm.title.trim() || undefined,
    })
    ElMessage.success('网页已导入，正在抓取与解析')
    urlDialogVisible.value = false
    scheduleRefresh()
  } catch (error) {
    ElMessage.error(knowledgeErrorMessage(error, '网页导入失败'))
  } finally {
    urlSaving.value = false
  }
}

async function reparse(document: KnowledgeDocumentView): Promise<void> {
  reparsingId.value = document.id
  try {
    await reparseDocument(document.id, engineChoice[document.id] as ParseEngine)
    ElMessage.success('已提交重新解析')
    scheduleRefresh()
  } catch (error) {
    ElMessage.error(knowledgeErrorMessage(error, '重新解析失败'))
  } finally {
    reparsingId.value = null
  }
}

// ---------- Term mappings ---------------------------------------------------

function openTermDialog(): void {
  termDialogVisible.value = true
  if (!termDatasourceId.value) {
    termDatasourceId.value = toolbarDatasourceId.value || datasources.value[0]?.id || ''
  }
  void loadTerms()
}

async function loadTerms(): Promise<void> {
  if (!termDatasourceId.value) {
    terms.value = []
    return
  }
  termLoading.value = true
  try {
    terms.value = await listTermMappings(termDatasourceId.value)
  } catch (error) {
    ElMessage.error(knowledgeErrorMessage(error, '加载术语映射失败'))
  } finally {
    termLoading.value = false
  }
}

async function addTerm(): Promise<void> {
  if (!termDatasourceId.value) {
    ElMessage.warning('请先选择数据源')
    return
  }
  if (!termForm.term.trim()) {
    ElMessage.warning('请输入术语')
    return
  }
  if (!termForm.tableName.trim()) {
    ElMessage.warning('请输入表名')
    return
  }
  if (termForm.targetType === 'COLUMN' && !termForm.columnName.trim()) {
    ElMessage.warning('字段级映射需要填写字段名')
    return
  }
  termSaving.value = true
  try {
    await createTermMapping({
      datasourceId: termDatasourceId.value,
      term: termForm.term.trim(),
      targetType: termForm.targetType as TermTargetType,
      tableName: termForm.tableName.trim(),
      columnName: termForm.targetType === 'COLUMN' ? termForm.columnName.trim() : null,
      description: termForm.description.trim() || null,
    })
    ElMessage.success('术语映射已添加')
    termForm.term = ''
    termForm.columnName = ''
    termForm.description = ''
    await loadTerms()
  } catch (error) {
    ElMessage.error(knowledgeErrorMessage(error, '添加术语映射失败'))
  } finally {
    termSaving.value = false
  }
}

async function removeTerm(row: TermMappingView): Promise<void> {
  try {
    await deleteTermMapping(row.id)
    ElMessage.success('术语映射已删除')
    await loadTerms()
  } catch (error) {
    ElMessage.error(knowledgeErrorMessage(error, '删除术语映射失败'))
  }
}

// ---------- Search debug ----------------------------------------------------

async function runSearch(): Promise<void> {
  const query = searchQuery.value.trim()
  if (!query) {
    ElMessage.warning('请输入检索问题')
    return
  }
  searchLoading.value = true
  try {
    searchResult.value = await searchKnowledgeDebug({
      query,
      datasourceId: toolbarDatasourceId.value || undefined,
      agentId: searchAgentId.value.trim() || undefined,
      topK: searchTopK.value,
    })
  } catch (error) {
    ElMessage.error(knowledgeErrorMessage(error, '检索失败'))
  } finally {
    searchLoading.value = false
  }
}

// ---------- Citations -------------------------------------------------------

function openCitations(list: Citation[], index: number): void {
  const citation = list[index]
  if (!citation) {
    return
  }
  activeCitationList.value = list
  activeCitation.value = citation
  citationDrawerVisible.value = true
}

function onCitationNavigate(index: number): void {
  const citation = activeCitationList.value[index]
  if (citation) {
    activeCitation.value = citation
  }
}

function hitCitations(hit: KnowledgeHit): Citation[] {
  return hit.citation ? [hit.citation] : []
}

function openHitCitation(hit: KnowledgeHit): void {
  if (hit.citation) {
    openCitations([hit.citation], 0)
  }
}

function openTraceCitation(index: number): void {
  const citations = searchResult.value?.trace.citations ?? []
  openCitations(citations, index)
}

/** Builds a citation-like preview handle for an item's source document. */
function openDocument(document: KnowledgeDocumentView): void {
  openCitations(
    [
      {
        documentId: document.id,
        filename: document.filename,
        mimeType: document.mimeType,
        sourceType: document.sourceType,
        pageNo: document.pageCount ? 1 : null,
        quote: document.filename,
        fetchedAt: document.fetchedAt ?? null,
      },
    ],
    0,
  )
}

// ---------- Presentation helpers -------------------------------------------

function statusText(status: KnowledgeStatus): string {
  return statusLabels[status] ?? status
}

function statusTagType(status: KnowledgeStatus): TagType {
  return statusTagTypes[status] ?? 'info'
}

function parseStatusText(status: ParseStatus): string {
  return parseStatusLabels[status] ?? status
}

function parseStatusTagType(status: ParseStatus): TagType {
  return parseStatusTagTypes[status] ?? 'info'
}

function typeLabel(type: KnowledgeType): string {
  return typeLabels[type] ?? type
}

function failureStageText(stage?: FailureStage): string {
  if (!stage) {
    return '未知阶段'
  }
  const labels: Record<string, string> = { PARSE: '解析', CHUNK: '分块', EMBED: '向量化' }
  return labels[stage] ?? stage
}

function datasourceName(id?: string | null): string {
  if (!id) {
    return ''
  }
  return datasources.value.find((item) => item.id === id)?.name ?? id
}

function scopeText(item: KnowledgeItemView): string {
  if (item.scope === 'GLOBAL') {
    return '全局'
  }
  if (item.scope === 'AGENT') {
    return item.agentId ? `智能体 · ${item.agentId}` : '智能体'
  }
  return item.datasourceId ? `数据源 · ${datasourceName(item.datasourceId)}` : '数据源'
}

function formatTime(value?: string | null): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('zh-CN', { hour12: false })
}

function formatBytes(value?: number | null): string {
  if (!value || value <= 0) {
    return ''
  }
  const units = ['B', 'KB', 'MB', 'GB']
  let size = value
  let index = 0
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024
    index += 1
  }
  return `${size >= 10 || index === 0 ? Math.round(size) : size.toFixed(1)} ${units[index]}`
}

function formatScore(value?: number | null): string {
  return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(3) : '—'
}

/** Trace counters are counts, but tolerate an array payload from a newer backend. */
function asCount(value: unknown): string {
  if (typeof value === 'number') {
    return String(value)
  }
  if (Array.isArray(value)) {
    return String(value.length)
  }
  return value ? String(value) : '0'
}

function targetText(mapping: TermMappingView): string {
  if (mapping.targetType === 'COLUMN') {
    return mapping.columnName ? `字段 · ${mapping.columnName}` : '字段'
  }
  return '表'
}

// ---------- Lifecycle -------------------------------------------------------

watch(activeTab, () => {
  searchResult.value = null
  void refresh()
})

onMounted(async () => {
  if (!isAdmin.value) {
    return
  }
  await loadDatasources()
  await refresh()
})

onBeforeUnmount(clearPollTimers)
</script>

<template>
  <section v-if="isAdmin" class="ac-page knowledge">
    <header class="ac-page-header">
      <div>
        <h1 class="ac-page-title">知识库</h1>
        <p class="ac-page-description">
          管理元数据、业务知识与智能体知识，查看文档解析状态，并用真实问题调试检索与选表效果。
        </p>
      </div>
      <div class="ac-action-bar">
        <el-button :icon="Refresh" :loading="loading" @click="refresh">刷新</el-button>
        <el-button :icon="Search" @click="searchDrawerVisible = true">检索调试</el-button>
      </div>
    </header>

    <div class="knowledge__toolbar">
      <div class="knowledge__group">
        <span class="ac-text-meta">数据源</span>
        <el-select
          v-model="toolbarDatasourceId"
          class="knowledge__datasource"
          placeholder="选择数据源"
          filterable
        >
          <el-option
            v-for="datasource in datasources"
            :key="datasource.id"
            :label="datasource.name"
            :value="datasource.id"
          />
        </el-select>
      </div>

      <div v-if="activeType === 'AGENT'" class="knowledge__group">
        <span class="ac-text-meta">智能体</span>
        <el-input
          v-model="toolbarAgentId"
          class="knowledge__agent"
          placeholder="例如 default"
          clearable
        />
      </div>

      <div class="knowledge__group">
        <el-button
          :icon="RefreshRight"
          :loading="syncing"
          :disabled="!toolbarDatasourceId"
          @click="runSync"
        >
          元数据同步
        </el-button>

        <el-upload
          class="knowledge__upload"
          action="#"
          :auto-upload="false"
          :show-file-list="false"
          :limit="1"
          accept=".pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.txt,.md,.csv,.html,.htm"
          :on-change="onFileChange"
        >
          <el-tooltip
            content="元数据由同步生成，无需上传文档"
            :disabled="activeType !== 'METADATA'"
          >
            <span>
              <el-button
                :icon="Upload"
                :loading="uploading"
                :disabled="activeType === 'METADATA'"
              >
                上传文档
              </el-button>
            </span>
          </el-tooltip>
        </el-upload>

        <el-tooltip
          content="元数据没有原文，无需网页导入"
          :disabled="activeType !== 'METADATA'"
        >
          <span>
            <el-button :icon="Link" :disabled="activeType === 'METADATA'" @click="openUrlDialog">
              网页导入
            </el-button>
          </span>
        </el-tooltip>

        <el-button :icon="Collection" @click="openTermDialog">术语映射</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="knowledge__tabs">
      <el-tab-pane v-for="tab in tabs" :key="tab.type" :label="tab.label" :name="tab.type" />
    </el-tabs>

    <p class="knowledge__hint">{{ activeTabHint }}</p>

    <section class="knowledge__section">
      <header class="knowledge__section-head">
        <div class="knowledge__section-heading">
          <h2 class="knowledge__section-title">{{ activeTabLabel }}条目</h2>
          <span class="knowledge__count">{{ items.length }} 条</span>
        </div>
        <div class="ac-action-bar">
          <el-button :icon="Plus" @click="openCreate">新增条目</el-button>
        </div>
      </header>

      <div class="ac-table-wrapper">
        <el-table v-loading="loading" :data="items" row-key="id" stripe>
          <el-table-column label="标题" min-width="220">
            <template #default="{ row }">
              <div class="knowledge__title">
                <span class="knowledge__title-main">{{ row.title }}</span>
                <span v-if="row.tableName" class="knowledge__title-key">{{ row.tableName }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="范围" min-width="150">
            <template #default="{ row }">
              <span class="knowledge__scope" :title="scopeText(row)">{{ scopeText(row) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="statusTagType(row.status)">
                {{ statusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="失败阶段与原因" min-width="210">
            <template #default="{ row }">
              <el-tooltip
                v-if="row.failureReason"
                :content="`${failureStageText(row.failureStage)}：${row.failureReason}`"
                placement="top"
                :show-after="200"
              >
                <span class="knowledge__failure">
                  {{ failureStageText(row.failureStage) }} · {{ row.failureReason }}
                </span>
              </el-tooltip>
              <span v-else class="ac-muted">—</span>
            </template>
          </el-table-column>

          <el-table-column label="更新时间" width="170">
            <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
          </el-table-column>

          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text :icon="Edit" @click="openEdit(row)">编辑</el-button>
              <el-button
                v-if="row.status === 'FAILED'"
                size="small"
                text
                :icon="RefreshRight"
                @click="retry(row)"
              >
                重试
              </el-button>
              <el-button
                v-if="row.document"
                size="small"
                text
                :icon="View"
                @click="openDocument(row.document)"
              >
                原文
              </el-button>
              <el-popconfirm
                :title="`确定删除条目「${row.title}」？`"
                confirm-button-text="删除"
                cancel-button-text="取消"
                confirm-button-type="danger"
                @confirm="removeItem(row)"
              >
                <template #reference>
                  <el-button size="small" type="danger" text :icon="Delete">删除</el-button>
                </template>
              </el-popconfirm>
            </template>
          </el-table-column>

          <template #empty>
            <div class="ac-empty-state">
              <span class="ac-empty-state__title">暂无{{ activeTabLabel }}条目</span>
              <span class="ac-empty-state__text">
                {{
                  activeType === 'METADATA'
                    ? '选择数据源后点击「元数据同步」，系统会自动生成表与字段条目。'
                    : '点击「新增条目」手动录入，或通过上传文档 / 网页导入创建。'
                }}
              </span>
            </div>
          </template>
        </el-table>
      </div>
    </section>

    <section v-if="activeType !== 'METADATA'" class="knowledge__section">
      <header class="knowledge__section-head">
        <div class="knowledge__section-heading">
          <h2 class="knowledge__section-title">文档与解析状态</h2>
          <span class="knowledge__count">{{ documents.length }} 份</span>
        </div>
        <span class="ac-text-meta">解析完成后条目才会进入检索范围</span>
      </header>

      <div class="ac-table-wrapper">
        <el-table v-loading="documentsLoading" :data="documents" row-key="id" stripe>
          <el-table-column label="文件名" min-width="230">
            <template #default="{ row }">
              <div class="knowledge__title">
                <span class="knowledge__title-main">{{ row.filename }}</span>
                <span class="knowledge__title-key">
                  {{ row.sourceType === 'URL' ? row.sourceUrl || '网页快照' : formatBytes(row.sizeBytes) }}
                </span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="页数" width="80">
            <template #default="{ row }">
              {{ row.pageCount ?? '—' }}
            </template>
          </el-table-column>

          <el-table-column label="解析引擎" width="140">
            <template #default="{ row }">
              <div class="knowledge__title">
                <span class="knowledge__title-main">{{ row.parseEngine || '—' }}</span>
                <span v-if="row.parseEngineVersion" class="knowledge__title-key">
                  {{ row.parseEngineVersion }}
                </span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="解析状态" width="130">
            <template #default="{ row }">
              <el-tooltip
                :disabled="!row.parseFailureReason"
                :content="row.parseFailureReason || ''"
                placement="top"
              >
                <el-tag size="small" effect="plain" :type="parseStatusTagType(row.parseStatus)">
                  {{ parseStatusText(row.parseStatus) }}
                </el-tag>
              </el-tooltip>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="250" fixed="right">
            <template #default="{ row }">
              <div class="knowledge__doc-actions">
                <el-select v-model="engineChoice[row.id]" size="small" class="knowledge__engine">
                  <el-option label="MinerU" value="mineru" />
                  <el-option label="Tika" value="tika" />
                </el-select>
                <el-button
                  size="small"
                  text
                  :icon="RefreshRight"
                  :loading="reparsingId === row.id"
                  @click="reparse(row)"
                >
                  重新解析
                </el-button>
                <el-button size="small" text :icon="View" @click="openDocument(row)">原文</el-button>
              </div>
            </template>
          </el-table-column>

          <template #empty>
            <div class="ac-empty-state">
              <span class="ac-empty-state__title">暂无文档</span>
              <span class="ac-empty-state__text">
                上传 PDF / Word / 图片，或导入网页；解析状态会在这里更新。
              </span>
            </div>
          </template>
        </el-table>
      </div>
    </section>

    <!-- Create / edit item -->
    <el-dialog
      v-model="itemDialogVisible"
      class="ac-dialog"
      :title="itemDialogTitle"
      width="min(640px, calc(100vw - 32px))"
      :close-on-click-modal="false"
    >
      <el-form ref="itemFormRef" :model="itemForm" :rules="itemRules" label-width="96px">
        <el-form-item label="知识类型">
          <el-tag effect="plain">{{ activeTabLabel }}</el-tag>
        </el-form-item>

        <el-form-item label="范围" prop="scope">
          <el-radio-group v-model="itemForm.scope">
            <el-radio-button
              v-for="option in scopeOptions"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item
          v-if="itemForm.scope === 'DATASOURCE'"
          label="数据源"
          prop="datasourceId"
        >
          <el-select
            v-model="itemForm.datasourceId"
            class="knowledge__control"
            placeholder="选择数据源"
            filterable
          >
            <el-option
              v-for="datasource in datasources"
              :key="datasource.id"
              :label="datasource.name"
              :value="datasource.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item v-if="itemForm.scope === 'AGENT'" label="智能体" prop="agentId">
          <el-input v-model="itemForm.agentId" placeholder="智能体标识，例如 default" />
        </el-form-item>

        <el-form-item label="标题" prop="title">
          <el-input
            v-model="itemForm.title"
            maxlength="120"
            show-word-limit
            placeholder="用于列表与检索结果展示"
          />
        </el-form-item>

        <el-form-item v-if="activeType === 'METADATA'" label="表名" prop="tableName">
          <el-input v-model="itemForm.tableName" placeholder="例如 student" />
        </el-form-item>

        <el-form-item
          :label="activeType === 'METADATA' ? '补充说明' : '内容'"
          prop="content"
        >
          <el-input
            v-model="itemForm.content"
            type="textarea"
            :rows="7"
            resize="vertical"
            :placeholder="
              activeType === 'METADATA'
                ? '表与字段的业务说明、口径备注（可选）'
                : '写清楚业务口径、指标定义或文档要点，检索会按此召回'
            "
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="knowledge__footer">
          <span class="knowledge__spacer" />
          <el-button @click="itemDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="itemSaving" @click="submitItem">保存</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- URL import -->
    <el-dialog
      v-model="urlDialogVisible"
      class="ac-dialog"
      title="网页导入"
      width="min(560px, calc(100vw - 32px))"
      :close-on-click-modal="false"
    >
      <el-form label-width="96px">
        <el-form-item label="知识类型">
          <el-tag effect="plain">{{ activeTabLabel }}</el-tag>
        </el-form-item>
        <el-form-item label="网页地址" required>
          <el-input v-model="urlForm.url" placeholder="https://example.com/page" />
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="urlForm.title" placeholder="可留空，默认取网页标题或地址" />
        </el-form-item>
      </el-form>
      <p class="knowledge__hint">
        导入后系统会抓取网页正文并保存快照；引用预览展示的是导入时的内容。
      </p>

      <template #footer>
        <div class="knowledge__footer">
          <span class="knowledge__spacer" />
          <el-button @click="urlDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="urlSaving" @click="submitUrl">导入</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- Term mappings -->
    <el-dialog
      v-model="termDialogVisible"
      class="ac-dialog"
      title="术语映射"
      width="min(900px, calc(100vw - 32px))"
      :close-on-click-modal="false"
    >
      <p class="knowledge__hint">
        把业务口语里的说法映射到具体的表或字段，检索改写时优先使用映射结果。
      </p>

      <div class="knowledge__term-form">
        <el-select
          v-model="termDatasourceId"
          class="knowledge__datasource"
          placeholder="选择数据源"
          filterable
          @change="loadTerms"
        >
          <el-option
            v-for="datasource in datasources"
            :key="datasource.id"
            :label="datasource.name"
            :value="datasource.id"
          />
        </el-select>
        <el-input v-model="termForm.term" placeholder="术语，例如 挂科" />
        <el-select v-model="termForm.targetType" class="knowledge__term-type">
          <el-option label="表" value="TABLE" />
          <el-option label="字段" value="COLUMN" />
        </el-select>
        <el-input v-model="termForm.tableName" placeholder="表名" />
        <el-input
          v-if="termForm.targetType === 'COLUMN'"
          v-model="termForm.columnName"
          placeholder="字段名"
        />
        <el-input v-model="termForm.description" placeholder="说明（可选）" />
        <el-button type="primary" :icon="Plus" :loading="termSaving" @click="addTerm">
          添加
        </el-button>
      </div>

      <div class="ac-table-wrapper knowledge__term-table">
        <el-table v-loading="termLoading" :data="terms" row-key="id" size="small">
          <el-table-column prop="term" label="术语" min-width="120" />
          <el-table-column label="目标" min-width="120">
            <template #default="{ row }">{{ targetText(row) }}</template>
          </el-table-column>
          <el-table-column prop="tableName" label="表" min-width="120" />
          <el-table-column label="字段" min-width="110">
            <template #default="{ row }">{{ row.columnName || '—' }}</template>
          </el-table-column>
          <el-table-column label="说明" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-popconfirm
                :title="`删除术语「${row.term}」的映射？`"
                confirm-button-text="删除"
                cancel-button-text="取消"
                confirm-button-type="danger"
                @confirm="removeTerm(row)"
              >
                <template #reference>
                  <el-button size="small" type="danger" text :icon="Delete">删除</el-button>
                </template>
              </el-popconfirm>
            </template>
          </el-table-column>
          <template #empty>
            <div class="ac-empty-state">
              <span class="ac-empty-state__title">暂无术语映射</span>
              <span class="ac-empty-state__text">选择数据源后，在上方补充业务术语与表字段的对应关系。</span>
            </div>
          </template>
        </el-table>
      </div>
    </el-dialog>

    <!-- Search debug -->
    <el-drawer
      v-model="searchDrawerVisible"
      class="ac-drawer"
      title="检索调试"
      size="min(760px, 94vw)"
      append-to-body
    >
      <div class="knowledge__search">
        <div class="knowledge__search-bar">
          <el-input
            v-model="searchQuery"
            placeholder="输入一个真实问题，例如：1班数学平均分是多少"
            clearable
            @keydown.enter="runSearch"
          />
          <el-button type="primary" :icon="Search" :loading="searchLoading" @click="runSearch">
            检索
          </el-button>
        </div>

        <div class="knowledge__search-filters">
          <div class="knowledge__group">
            <span class="ac-text-meta">数据源</span>
            <el-select
              v-model="toolbarDatasourceId"
              class="knowledge__datasource"
              placeholder="全部"
              clearable
              filterable
            >
              <el-option
                v-for="datasource in datasources"
                :key="datasource.id"
                :label="datasource.name"
                :value="datasource.id"
              />
            </el-select>
          </div>
          <div class="knowledge__group">
            <span class="ac-text-meta">智能体</span>
            <el-input
              v-model="searchAgentId"
              class="knowledge__agent"
              placeholder="可留空"
              clearable
            />
          </div>
          <div class="knowledge__group">
            <span class="ac-text-meta">召回条数</span>
            <el-input-number v-model="searchTopK" :min="1" :max="20" size="small" />
          </div>
        </div>

        <template v-if="searchResult">
          <el-alert
            v-if="searchResult.trace.fallback"
            type="warning"
            :closable="false"
            show-icon
            :title="`已回退到全量 schema${searchResult.trace.fallbackReason ? `：${searchResult.trace.fallbackReason}` : ''}`"
          />

          <section class="knowledge__section">
            <header class="knowledge__section-head">
              <div class="knowledge__section-heading">
                <h3 class="knowledge__section-title">命中知识</h3>
                <span class="knowledge__count">{{ searchResult.hits.length }} 条</span>
              </div>
            </header>

            <div v-if="searchResult.hits.length === 0" class="ac-empty-state ac-empty-state--bordered">
              <span class="ac-empty-state__title">没有命中任何知识</span>
              <span class="ac-empty-state__text">
                确认条目状态为「已就绪」，或换一个更贴近业务口径的问法再试。
              </span>
            </div>

            <article v-for="hit in searchResult.hits" :key="hit.itemId" class="knowledge__hit">
              <div class="knowledge__hit-head">
                <el-tag size="small" effect="plain" :type="hitTagTypes[hit.knowledgeType]">
                  {{ typeLabel(hit.knowledgeType) }}
                </el-tag>
                <span class="knowledge__hit-title">{{ hit.title }}</span>
                <span class="knowledge__hit-score">score {{ formatScore(hit.score) }}</span>
                <CitationBadge :citations="hitCitations(hit)" @open="openHitCitation(hit)" />
              </div>
              <p class="knowledge__hit-text">{{ hit.content }}</p>
              <blockquote v-if="hit.citation?.quote" class="knowledge__hit-quote">
                {{ hit.citation.quote }}
              </blockquote>
            </article>
          </section>

          <section class="knowledge__section">
            <header class="knowledge__section-head">
              <div class="knowledge__section-heading">
                <h3 class="knowledge__section-title">检索轨迹</h3>
              </div>
            </header>

            <dl class="knowledge__trace">
              <div class="knowledge__trace-row">
                <dt>改写查询</dt>
                <dd>{{ searchResult.trace.rewrittenQuery || '—' }}</dd>
              </div>
              <div class="knowledge__trace-row">
                <dt>业务命中</dt>
                <dd>{{ asCount(searchResult.trace.businessHits) }}</dd>
              </div>
              <div class="knowledge__trace-row">
                <dt>智能体命中</dt>
                <dd>{{ asCount(searchResult.trace.agentHits) }}</dd>
              </div>
              <div class="knowledge__trace-row">
                <dt>候选表</dt>
                <dd class="knowledge__tags">
                  <el-tag
                    v-for="table in searchResult.trace.candidateTables"
                    :key="table"
                    size="small"
                    effect="plain"
                  >
                    {{ table }}
                  </el-tag>
                  <span v-if="searchResult.trace.candidateTables.length === 0" class="ac-muted">
                    —
                  </span>
                </dd>
              </div>
              <div class="knowledge__trace-row">
                <dt>最终表</dt>
                <dd class="knowledge__tags">
                  <el-tag
                    v-for="table in searchResult.trace.finalTables"
                    :key="table"
                    size="small"
                    type="success"
                    effect="plain"
                  >
                    {{ table }}
                  </el-tag>
                  <span v-if="searchResult.trace.finalTables.length === 0" class="ac-muted">—</span>
                </dd>
              </div>
              <div class="knowledge__trace-row">
                <dt>是否回退</dt>
                <dd>{{ searchResult.trace.fallback ? '是' : '否' }}</dd>
              </div>
              <div class="knowledge__trace-row">
                <dt>引用</dt>
                <dd>
                  <CitationBadge
                    :citations="searchResult.trace.citations"
                    @open="openTraceCitation"
                  />
                  <span v-if="searchResult.trace.citations.length === 0" class="ac-muted">—</span>
                </dd>
              </div>
            </dl>
          </section>
        </template>

        <div v-else class="ac-empty-state ac-empty-state--bordered">
          <span class="ac-empty-state__title">输入问题开始调试</span>
          <span class="ac-empty-state__text">
            这里会展示命中的知识、候选表、最终表集合与是否回退，帮助判断检索是否生效。
          </span>
        </div>
      </div>
    </el-drawer>

    <CitationDrawer
      v-model="citationDrawerVisible"
      :citation="activeCitation"
      :citations="activeCitationList"
      @navigate="onCitationNavigate"
    />
  </section>

  <section v-else class="ac-page">
    <header class="ac-page-header">
      <div>
        <h1 class="ac-page-title">知识库</h1>
        <p class="ac-page-description">该页面仅管理员可见。</p>
      </div>
    </header>
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="没有权限访问知识库"
      description="如需管理知识库，请联系管理员开通权限。"
    />
  </section>
</template>

<style scoped>
.knowledge__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ac-space-12) var(--ac-space-24);
  padding: var(--ac-space-12) var(--ac-space-16);
  background: var(--ac-surface);
  border: 1px solid var(--ac-border-soft);
  border-radius: var(--ac-radius);
  box-shadow: var(--ac-shadow-sm);
}

.knowledge__group {
  display: inline-flex;
  align-items: center;
  gap: var(--ac-space-8);
  min-width: 0;
}

.knowledge__datasource {
  width: 200px;
  max-width: 100%;
}

.knowledge__agent {
  width: 150px;
  max-width: 100%;
}

.knowledge__upload {
  display: inline-flex;
}

/* The tab strip is a type switch: the tables below are shared by all three tabs. */
.knowledge__tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.knowledge__tabs :deep(.el-tabs__item) {
  font-size: var(--ac-text-body);
}

.knowledge__tabs :deep(.el-tabs__content) {
  display: none;
}

.knowledge__hint {
  margin: 0;
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
  line-height: var(--ac-line-height);
}

.knowledge__section {
  display: flex;
  flex-direction: column;
  gap: var(--ac-space-12);
  min-width: 0;
}

.knowledge__section-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ac-space-8) var(--ac-space-16);
}

.knowledge__section-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--ac-space-8);
}

.knowledge__section-title {
  margin: 0;
  color: var(--ac-ink);
  font-family: var(--ac-font-serif);
  font-size: var(--ac-text-lead);
  font-weight: 600;
}

.knowledge__count {
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
}

.knowledge__title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.knowledge__title-main {
  overflow: hidden;
  color: var(--ac-ink);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge__title-key {
  overflow: hidden;
  color: var(--ac-muted);
  font-family: var(--ac-font-mono);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge__scope {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  color: var(--ac-text);
  font-size: var(--ac-text-meta);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge__failure {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  color: var(--el-color-danger-dark-2);
  font-size: var(--ac-text-meta);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.knowledge__doc-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ac-space-4);
}

.knowledge__engine {
  width: 96px;
}

.knowledge__control {
  width: 100%;
}

.knowledge__footer {
  display: flex;
  align-items: center;
  gap: var(--ac-space-8);
}

.knowledge__spacer {
  flex: 1;
}

/* Term mapping composer: one responsive row of small controls. */
.knowledge__term-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--ac-space-8);
  align-items: center;
  margin: var(--ac-space-12) 0;
}

.knowledge__term-type {
  width: 100%;
}

.knowledge__term-table {
  margin-top: var(--ac-space-8);
}

.knowledge__search {
  display: flex;
  flex-direction: column;
  gap: var(--ac-space-16);
}

.knowledge__search-bar {
  display: flex;
  gap: var(--ac-space-8);
}

.knowledge__search-filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ac-space-12) var(--ac-space-24);
}

/* One hit: header line, snippet, optional quoted source. */
.knowledge__hit {
  padding: var(--ac-space-12) var(--ac-space-16);
  background: var(--ac-surface);
  border: 1px solid var(--ac-border-soft);
  border-radius: var(--ac-radius-sm);
}

.knowledge__hit-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ac-space-8);
  margin-bottom: var(--ac-space-8);
}

.knowledge__hit-title {
  color: var(--ac-ink);
  font-weight: 600;
}

.knowledge__hit-score {
  margin-left: auto;
  color: var(--ac-muted);
  font-family: var(--ac-font-mono);
  font-size: 11px;
}

.knowledge__hit-text {
  margin: 0;
  color: var(--ac-text);
  font-size: var(--ac-text-body);
  line-height: var(--ac-line-height);
  white-space: pre-wrap;
}

.knowledge__hit-quote {
  margin: var(--ac-space-8) 0 0;
  padding: var(--ac-space-8) var(--ac-space-12);
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
  line-height: var(--ac-line-height);
  background: var(--ac-fill-soft);
  border-left: 2px solid var(--ac-border);
}

.knowledge__trace {
  display: flex;
  flex-direction: column;
  gap: var(--ac-space-8);
  margin: 0;
  padding: var(--ac-space-16);
  background: var(--ac-surface);
  border: 1px solid var(--ac-border-soft);
  border-radius: var(--ac-radius-sm);
}

.knowledge__trace-row {
  display: grid;
  grid-template-columns: 96px 1fr;
  gap: var(--ac-space-12);
  align-items: start;
}

.knowledge__trace-row dt {
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
  line-height: var(--ac-line-height);
}

.knowledge__trace-row dd {
  margin: 0;
  color: var(--ac-text);
  font-size: var(--ac-text-body);
  line-height: var(--ac-line-height);
  word-break: break-word;
}

.knowledge__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ac-space-4);
}

@media (max-width: 767px) {
  .knowledge__toolbar {
    padding: var(--ac-space-12);
  }

  .knowledge__datasource,
  .knowledge__agent {
    width: 100%;
  }

  .knowledge__group {
    width: 100%;
  }

  .knowledge__trace-row {
    grid-template-columns: 1fr;
    gap: 2px;
  }
}
</style>
