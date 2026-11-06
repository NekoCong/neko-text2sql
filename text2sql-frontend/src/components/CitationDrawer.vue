<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  ArrowLeft,
  ArrowRight,
  Download,
  Refresh,
  ZoomIn,
  ZoomOut,
} from '@element-plus/icons-vue'
import type { PDFDocumentProxy, PDFPageProxy, PageViewport, RenderTask } from 'pdfjs-dist'
import { getDocumentContent, getDocumentPreviewUrl } from '@/api/knowledge'
import type { Citation } from '@/api/knowledge'

/**
 * Original-document preview for one citation.
 *
 * The rendering mode follows the citation's mime type: PDF pages are rasterised with
 * `pdfjs-dist` (lazily imported the first time the drawer opens) and the hit box is
 * overlaid on the canvas; images get a percentage-positioned box; everything else
 * falls back to the normalised text snapshot with the cited range wrapped in `<mark>`.
 * When the original cannot be loaded the drawer still shows the quote and offers
 * retry / download, so the reader is never left with a blank pane.
 */
const props = defineProps<{
  modelValue: boolean
  citation: Citation | null
  /** Optional citation list; enables 上一条 / 下一条 navigation. */
  citations?: Citation[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  navigate: [index: number]
}>()

type PreviewMode = 'pdf' | 'image' | 'text'

/** CSS pixels for the highlight overlay. */
interface BoxPixels {
  left: number
  top: number
  width: number
  height: number
}

/** Percentage box for the image overlay. */
interface BoxPercent {
  left: string
  top: string
  width: string
  height: string
}

const resolvedUrl = ref<string | null>(null)
const resolvedExpiry = ref<string | null>(null)
const resolvingUrl = ref(false)
const unavailable = ref(false)
const unavailableReason = ref('')
const showHighlight = ref(true)

const content = ref<{ plainText: string; pageCount: number | null } | null>(null)
const contentLoading = ref(false)

const imageFailed = ref(false)
const imageBox = ref<BoxPercent | null>(null)

const pdfPage = ref(1)
const pdfPageCount = ref(0)
const zoom = ref(1)
const pdfRendering = ref(false)
const overlay = ref<BoxPixels | null>(null)
const canvasRef = ref<HTMLCanvasElement | null>(null)
const stageRef = ref<HTMLElement | null>(null)
const markRef = ref<HTMLElement | null>(null)

/** Live pdf.js document; destroyed on close so the worker is released. */
let pdfDocument: PDFDocumentProxy | null = null
let renderTask: RenderTask | null = null
/** Guards every async step against a drawer that was reopened or closed meanwhile. */
let session = 0

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const mode = computed<PreviewMode>(() => {
  const mime = props.citation?.mimeType?.toLowerCase() ?? ''
  if (mime === 'application/pdf' || mime.endsWith('/pdf')) {
    return 'pdf'
  }
  if (mime.startsWith('image/')) {
    return 'image'
  }
  return 'text'
})

const citationList = computed<Citation[]>(() =>
  props.citations && props.citations.length > 0
    ? props.citations
    : props.citation
      ? [props.citation]
      : [],
)

const currentIndex = computed(() => {
  const citation = props.citation
  if (!citation) {
    return -1
  }
  const exact = citationList.value.indexOf(citation)
  if (exact >= 0) {
    return exact
  }
  return citationList.value.findIndex(
    (item) =>
      item.documentId === citation.documentId &&
      item.pageNo === citation.pageNo &&
      item.quote === citation.quote,
  )
})

const drawerTitle = computed(() => {
  const citation = props.citation
  if (!citation) {
    return '原文预览'
  }
  const page = citation.pageNo ? ` · 第 ${citation.pageNo} 页` : ''
  return `${citation.filename}${page}`
})

const canToggleHighlight = computed(
  () => mode.value === 'text' || Boolean(props.citation?.bbox && props.citation.bbox.length >= 4),
)

const overlayStyle = computed(() => {
  const box = overlay.value
  if (!box) {
    return undefined
  }
  return {
    left: `${box.left}px`,
    top: `${box.top}px`,
    width: `${box.width}px`,
    height: `${box.height}px`,
  }
})

/** Where to wrap `<mark>` inside the text snapshot. */
const highlight = computed<{ before: string; mark: string; after: string } | null>(() => {
  const text = content.value?.plainText
  const citation = props.citation
  if (!text || !citation) {
    return null
  }
  const quote = citation.quote ?? ''
  const start = citation.charStart
  const end = citation.charEnd
  if (
    typeof start === 'number' &&
    typeof end === 'number' &&
    start >= 0 &&
    end <= text.length &&
    end > start
  ) {
    const slice = text.slice(start, end)
    // The stored offsets win only when they still describe the quoted fragment;
    // a re-parse can shift them, in which case the quote match is authoritative.
    if (!quote.trim() || normalize(slice).includes(normalize(quote).slice(0, 24))) {
      return { before: text.slice(0, start), mark: slice, after: text.slice(end) }
    }
  }
  const at = quote.trim() ? text.indexOf(quote.trim()) : -1
  if (at >= 0) {
    return {
      before: text.slice(0, at),
      mark: text.slice(at, at + quote.trim().length),
      after: text.slice(at + quote.trim().length),
    }
  }
  return { before: text, mark: '', after: '' }
})

/** Collapses whitespace so offset/quote comparisons ignore formatting drift. */
function normalize(value: string): string {
  return value.replace(/\s+/g, ' ').trim()
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/** True when a stored preview URL is past its expiry. */
function isExpired(value?: string | null): boolean {
  if (!value) {
    return false
  }
  const time = new Date(value).getTime()
  return !Number.isNaN(time) && time <= Date.now()
}

/** Human-readable timestamp; falls back to the raw value when unparseable. */
function formatTime(value?: string | null): string {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('zh-CN', { hour12: false })
}

/** Marks the preview as unavailable; the quote and the footer actions stay usable. */
function markUnavailable(reason: string): void {
  unavailable.value = true
  unavailableReason.value = reason
}

/** Releases the pdf.js document and any in-flight render. */
function resetPdf(): void {
  if (renderTask) {
    renderTask.cancel()
    renderTask = null
  }
  const document = pdfDocument
  pdfDocument = null
  pdfPageCount.value = 0
  overlay.value = null
  if (document) {
    void document.destroy()
  }
}

/** Stops all work and drops the loaded preview (called on close). */
function teardown(): void {
  session += 1
  resetPdf()
  content.value = null
  imageBox.value = null
  resolvedUrl.value = null
  resolvedExpiry.value = null
  imageFailed.value = false
  unavailable.value = false
  unavailableReason.value = ''
  resolvingUrl.value = false
  pdfRendering.value = false
  contentLoading.value = false
}

/** Resolves a fresh short-lived preview URL when the citation did not carry one. */
async function resolveUrl(citation: Citation, current: number): Promise<void> {
  resolvingUrl.value = true
  try {
    const result = await getDocumentPreviewUrl(citation.documentId)
    if (current !== session) {
      return
    }
    resolvedUrl.value = result.url
    resolvedExpiry.value = result.expiresAt
  } catch {
    if (current === session) {
      unavailableReason.value = '预览地址获取失败，可能已过期或原文已被移除'
    }
  } finally {
    if (current === session) {
      resolvingUrl.value = false
    }
  }
}

/** Loads and rasterises the cited page. */
async function loadPdf(url: string, citation: Citation, current: number): Promise<void> {
  pdfRendering.value = true
  try {
    // Both imports stay lazy: pdf.js (~1 MB) only enters the bundle that the drawer
    // needs, never the chat first paint.
    const pdfjs = await import('pdfjs-dist')
    const worker = await import('pdfjs-dist/build/pdf.worker.min.mjs?url')
    pdfjs.GlobalWorkerOptions.workerSrc = worker.default

    const document = await pdfjs.getDocument({ url }).promise
    if (current !== session) {
      await document.destroy()
      return
    }
    pdfDocument = document
    pdfPageCount.value = document.numPages
    pdfPage.value = clamp(citation.pageNo ?? 1, 1, document.numPages)
    await renderPage(current)
  } catch {
    if (current === session) {
      markUnavailable('PDF 加载失败，可能是预览地址已过期或文件已移除')
    }
  } finally {
    if (current === session) {
      pdfRendering.value = false
    }
  }
}

/** Renders the current page and re-aligns the highlight box. */
async function renderPage(current: number): Promise<void> {
  const document = pdfDocument
  const canvas = canvasRef.value
  if (!document || !canvas) {
    return
  }
  pdfRendering.value = true
  try {
    if (renderTask) {
      renderTask.cancel()
      renderTask = null
    }
    const page = await document.getPage(pdfPage.value)
    if (current !== session) {
      return
    }
    const base = page.getViewport({ scale: 1 })
    const available = Math.max(280, (stageRef.value?.clientWidth ?? 720) - 32)
    const fit = clamp(available / base.width, 0.2, 1.6)
    const viewport = page.getViewport({ scale: fit * zoom.value })
    const ratio = Math.min(window.devicePixelRatio || 1, 2)

    canvas.width = Math.floor(viewport.width * ratio)
    canvas.height = Math.floor(viewport.height * ratio)
    canvas.style.width = `${Math.round(viewport.width)}px`
    canvas.style.height = `${Math.round(viewport.height)}px`

    renderTask = page.render({
      canvas,
      viewport,
      transform: ratio === 1 ? undefined : [ratio, 0, 0, ratio, 0, 0],
    })
    await renderTask.promise
    if (current !== session) {
      return
    }
    overlay.value = computeOverlay(page, viewport, props.citation?.bbox ?? null)
  } catch (error) {
    const name = (error as { name?: string } | null)?.name
    if (current === session && name !== 'RenderingCancelledException') {
      markUnavailable('这一页渲染失败，可尝试重试')
    }
  } finally {
    if (current === session) {
      pdfRendering.value = false
    }
  }
}

/**
 * Converts the normalised 0–1000 bbox into CSS pixels for the rendered viewport.
 * The corners go through `convertToViewportPoint`, so rotated pages stay aligned.
 */
function computeOverlay(
  page: PDFPageProxy,
  viewport: PageViewport,
  bbox: number[] | null,
): BoxPixels | null {
  if (!bbox || bbox.length < 4) {
    return null
  }
  const [x0, y0, x1, y1] = bbox
  const view = page.view
  const width = view[2] - view[0]
  const height = view[3] - view[1]
  // bbox origin is top-left; PDF user space origin is bottom-left.
  const toX = (value: number) => view[0] + (clamp(value, 0, 1000) / 1000) * width
  const toY = (value: number) => view[3] - (clamp(value, 0, 1000) / 1000) * height

  const [ax, ay] = viewport.convertToViewportPoint(toX(x0), toY(y0))
  const [bx, by] = viewport.convertToViewportPoint(toX(x1), toY(y1))
  return {
    left: Math.min(ax, bx),
    top: Math.min(ay, by),
    width: Math.abs(bx - ax),
    height: Math.abs(by - ay),
  }
}

/** Reads the normalised text snapshot used by Office / web-page citations. */
async function loadText(citation: Citation, current: number): Promise<void> {
  contentLoading.value = true
  try {
    const result = await getDocumentContent(citation.documentId)
    if (current !== session) {
      return
    }
    content.value = { plainText: result.plainText, pageCount: result.pageCount ?? null }
    await nextTick()
    markRef.value?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  } catch {
    if (current === session) {
      markUnavailable('原文内容加载失败，可稍后重试')
    }
  } finally {
    if (current === session) {
      contentLoading.value = false
    }
  }
}

/** Percentage box for the image overlay (bbox is already 0–1000 normalised). */
function computeImageBox(bbox: number[] | null): BoxPercent | null {
  if (!bbox || bbox.length < 4) {
    return null
  }
  const [x0, y0, x1, y1] = bbox
  return {
    left: `${clamp(Math.min(x0, x1), 0, 1000) / 10}%`,
    top: `${clamp(Math.min(y0, y1), 0, 1000) / 10}%`,
    width: `${Math.abs(x1 - x0) / 10}%`,
    height: `${Math.abs(y1 - y0) / 10}%`,
  }
}

/** (Re)initialises the preview for the current citation. */
async function start(forceRefreshUrl = false): Promise<void> {
  const current = ++session
  resetPdf()
  content.value = null
  imageBox.value = null
  imageFailed.value = false
  unavailable.value = false
  unavailableReason.value = ''
  showHighlight.value = true
  zoom.value = 1
  pdfPage.value = 1

  const citation = props.citation
  if (!citation) {
    markUnavailable('没有可预览的引用')
    return
  }

  const cachedUrl =
    !forceRefreshUrl && !isExpired(citation.previewExpiresAt) ? citation.previewUrl : null
  resolvedUrl.value = cachedUrl ?? null
  resolvedExpiry.value = cachedUrl ? (citation.previewExpiresAt ?? null) : null

  if (mode.value === 'text') {
    await loadText(citation, current)
    return
  }

  if (!resolvedUrl.value) {
    await resolveUrl(citation, current)
    if (current !== session) {
      return
    }
  }

  if (!resolvedUrl.value) {
    markUnavailable('原文地址暂不可用，可能已过期或原文已被移除')
    return
  }

  if (mode.value === 'pdf') {
    await loadPdf(resolvedUrl.value, citation, current)
    return
  }

  imageBox.value = computeImageBox(citation.bbox ?? null)
}

/** Image load failure: keep the quote and offer retry instead of a broken frame. */
function onImageError(): void {
  imageFailed.value = true
  markUnavailable('图片加载失败，可能是预览地址已过期')
}

function stepPage(delta: number): void {
  const next = clamp(pdfPage.value + delta, 1, pdfPageCount.value || 1)
  if (next === pdfPage.value) {
    return
  }
  pdfPage.value = next
  void renderPage(session)
}

function zoomBy(delta: number): void {
  const next = clamp(Number((zoom.value + delta).toFixed(2)), 0.5, 3)
  if (next === zoom.value) {
    return
  }
  zoom.value = next
  void renderPage(session)
}

function retry(): void {
  void start(true)
}

function navigate(delta: number): void {
  const next = currentIndex.value + delta
  if (next >= 0 && next < citationList.value.length) {
    emit('navigate', next)
  }
}

function close(): void {
  visible.value = false
}

watch(
  [() => props.modelValue, () => props.citation],
  ([open]) => {
    if (open) {
      void start()
      return
    }
    teardown()
  },
)
</script>

<template>
  <el-drawer
    v-model="visible"
    class="ac-drawer citation"
    size="min(920px, 94vw)"
    :title="drawerTitle"
    append-to-body
    destroy-on-close
    @closed="teardown"
  >
    <div v-if="citation" class="citation__body">
      <div class="citation__meta">
        <span class="citation__meta-item">{{ citation.mimeType || '未知类型' }}</span>
        <span v-if="citation.pageNo" class="citation__meta-item">第 {{ citation.pageNo }} 页</span>
        <span v-if="citation.sourceType === 'URL' && citation.fetchedAt" class="citation__meta-item">
          快照抓取于 {{ formatTime(citation.fetchedAt) }}
        </span>
        <span v-if="resolvedExpiry" class="citation__meta-item">
          预览有效期至 {{ formatTime(resolvedExpiry) }}
        </span>
        <span class="citation__meta-spacer" />
        <span v-if="citationList.length > 1" class="citation__nav">
          <el-button
            size="small"
            text
            :icon="ArrowLeft"
            :disabled="currentIndex <= 0"
            @click="navigate(-1)"
          >
            上一条
          </el-button>
          <span class="ac-text-meta">{{ currentIndex + 1 }} / {{ citationList.length }}</span>
          <el-button
            size="small"
            text
            :disabled="currentIndex < 0 || currentIndex >= citationList.length - 1"
            @click="navigate(1)"
          >
            下一条
            <el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>
        </span>
      </div>

      <div class="citation__toolbar">
        <div v-if="mode === 'pdf' && !unavailable" class="citation__toolbar-group">
          <el-button
            size="small"
            :icon="ArrowLeft"
            :disabled="pdfPage <= 1 || pdfRendering"
            aria-label="上一页"
            @click="stepPage(-1)"
          />
          <span class="citation__page">{{ pdfPage }} / {{ pdfPageCount || '—' }}</span>
          <el-button
            size="small"
            :icon="ArrowRight"
            :disabled="pdfPage >= pdfPageCount || pdfRendering"
            aria-label="下一页"
            @click="stepPage(1)"
          />
        </div>
        <div v-if="mode === 'pdf' && !unavailable" class="citation__toolbar-group">
          <el-button
            size="small"
            :icon="ZoomOut"
            :disabled="zoom <= 0.5 || pdfRendering"
            aria-label="缩小"
            @click="zoomBy(-0.25)"
          />
          <span class="citation__page">{{ Math.round(zoom * 100) }}%</span>
          <el-button
            size="small"
            :icon="ZoomIn"
            :disabled="zoom >= 3 || pdfRendering"
            aria-label="放大"
            @click="zoomBy(0.25)"
          />
        </div>
        <div class="citation__toolbar-group citation__toolbar-group--end">
          <span class="ac-text-meta">高亮</span>
          <el-switch v-model="showHighlight" size="small" :disabled="!canToggleHighlight" />
        </div>
      </div>

      <div v-if="unavailable" class="citation__unavailable">
        <el-alert type="warning" :closable="false" show-icon title="原文暂时无法预览">
          <p class="citation__alert-text">
            {{ unavailableReason || '预览地址不可用或已过期。' }}
          </p>
          <p class="citation__alert-text">仍可查看下方引用文本，或点击「重试」重新获取原文。</p>
        </el-alert>
      </div>

      <div v-else-if="mode === 'pdf'" ref="stageRef" v-loading="pdfRendering" class="citation__stage">
        <div class="citation__canvas-wrap">
          <canvas ref="canvasRef" class="citation__canvas" />
          <div
            v-if="showHighlight && overlayStyle"
            class="citation__highlight"
            :style="overlayStyle"
          />
        </div>
      </div>

      <div
        v-else-if="mode === 'image'"
        v-loading="resolvingUrl"
        class="citation__stage citation__stage--image"
      >
        <div class="citation__image-wrap">
          <img
            v-if="resolvedUrl"
            :src="resolvedUrl"
            :alt="citation.filename"
            class="citation__image"
            @error="onImageError"
          />
          <div
            v-if="showHighlight && imageBox && !imageFailed"
            class="citation__highlight"
            :style="imageBox"
          />
        </div>
      </div>

      <div v-else v-loading="contentLoading" class="citation__text">
        <div class="citation__text-body"><template v-if="highlight">{{ highlight.before }}<mark ref="markRef" class="citation__mark">{{ highlight.mark }}</mark>{{ highlight.after }}</template></div>
      </div>

      <blockquote v-if="citation.quote" class="citation__quote">{{ citation.quote }}</blockquote>
    </div>

    <div v-else class="ac-empty-state ac-empty-state--bordered">
      <span class="ac-empty-state__title">没有可预览的引用</span>
      <span class="ac-empty-state__text">这条回答没有附带原文位置。</span>
    </div>

    <template #footer>
      <div class="citation__footer">
        <el-button
          :icon="Refresh"
          :loading="resolvingUrl || pdfRendering || contentLoading"
          @click="retry"
        >
          重试
        </el-button>
        <a
          v-if="resolvedUrl"
          class="citation__download"
          :href="resolvedUrl"
          target="_blank"
          rel="noopener noreferrer"
        >
          <el-button :icon="Download">下载原文</el-button>
        </a>
        <el-button v-else :icon="Download" disabled>下载原文</el-button>
        <span class="citation__spacer" />
        <el-button @click="close">关闭</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.citation__body {
  display: flex;
  flex-direction: column;
  gap: var(--ac-space-12);
  min-height: 100%;
}

.citation__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ac-space-8);
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
}

.citation__meta-item {
  padding: 2px var(--ac-space-8);
  background: var(--ac-fill);
  border-radius: 999px;
}

.citation__meta-spacer {
  flex: 1;
}

.citation__nav {
  display: inline-flex;
  align-items: center;
  gap: var(--ac-space-4);
}

.citation__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ac-space-8) var(--ac-space-16);
  padding: var(--ac-space-8) var(--ac-space-12);
  background: var(--ac-fill-soft);
  border: 1px solid var(--ac-border-soft);
  border-radius: var(--ac-radius-sm);
}

.citation__toolbar-group {
  display: inline-flex;
  align-items: center;
  gap: var(--ac-space-4);
}

.citation__toolbar-group--end {
  margin-left: auto;
  gap: var(--ac-space-8);
}

.citation__page {
  min-width: 56px;
  color: var(--ac-text);
  font-family: var(--ac-font-mono);
  font-size: var(--ac-text-meta);
  text-align: center;
}

/* The stage owns the scroll; the canvas is centred and never wider than the drawer. */
.citation__stage {
  display: flex;
  justify-content: center;
  min-height: 280px;
  max-height: 62vh;
  padding: var(--ac-space-16);
  overflow: auto;
  background: var(--ac-fill);
  border: 1px solid var(--ac-border-soft);
  border-radius: var(--ac-radius-sm);
}

.citation__stage--image {
  align-items: flex-start;
}

.citation__canvas-wrap,
.citation__image-wrap {
  position: relative;
  flex: none;
  align-self: flex-start;
  line-height: 0;
}

.citation__canvas {
  display: block;
  background: #fff;
  box-shadow: var(--ac-shadow-md);
}

.citation__image {
  display: block;
  max-width: 100%;
  height: auto;
  box-shadow: var(--ac-shadow-md);
}

.citation__image-wrap {
  max-width: 100%;
}

/* Translucent teal wash: readable over text, still obviously a marker. */
.citation__highlight {
  position: absolute;
  pointer-events: none;
  background: rgba(15, 157, 138, 0.26);
  border: 1px solid var(--ac-accent);
  border-radius: 2px;
  box-shadow: 0 0 0 2px rgba(15, 157, 138, 0.12);
  mix-blend-mode: multiply;
}

.citation__text {
  min-height: 280px;
  max-height: 62vh;
  padding: var(--ac-space-16) var(--ac-space-24);
  overflow: auto;
  background: var(--ac-surface);
  border: 1px solid var(--ac-border-soft);
  border-radius: var(--ac-radius-sm);
}

.citation__text-body {
  margin: 0;
  color: var(--ac-ink);
  font-family: var(--ac-font-sans);
  font-size: var(--ac-text-body);
  line-height: var(--ac-line-height);
  white-space: pre-wrap;
  word-break: break-word;
}

.citation__mark {
  padding: 0 1px;
  color: var(--ac-ink);
  background: rgba(15, 157, 138, 0.24);
  border-bottom: 2px solid var(--ac-accent);
  border-radius: 2px;
}

.citation__quote {
  margin: 0;
  padding: var(--ac-space-12) var(--ac-space-16);
  color: var(--ac-text);
  font-size: var(--ac-text-body);
  line-height: var(--ac-line-height);
  background: var(--ac-fill-soft);
  border-left: 3px solid var(--el-color-primary-light-5);
  border-radius: var(--ac-radius-sm);
}

.citation__unavailable {
  min-height: 200px;
}

.citation__alert-text {
  margin: var(--ac-space-4) 0 0;
  color: var(--ac-text);
  font-size: var(--ac-text-body);
  line-height: var(--ac-line-height);
}

.citation__footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ac-space-8);
}

.citation__download {
  display: inline-flex;
  text-decoration: none;
}

.citation__spacer {
  flex: 1;
}

@media (max-width: 767px) {
  .citation__text {
    padding: var(--ac-space-12);
  }

  .citation__stage {
    padding: var(--ac-space-8);
  }

  .citation__toolbar-group--end {
    margin-left: 0;
  }
}
</style>
