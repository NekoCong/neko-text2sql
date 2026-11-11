<script setup lang="ts">
import { computed } from 'vue'
import type { Citation } from '@/api/knowledge'

/**
 * Numbered superscript badges for the citations of one answer.
 *
 * The numbering is 1-based and follows the citation order, so badge `[2]` always
 * refers to the second entry of the same list. Renders nothing when there are no
 * citations, which keeps answers without sources free of an empty citation area.
 */
const props = defineProps<{
  citations: Citation[]
}>()

const emit = defineEmits<{
  open: [index: number]
}>()

/** One tooltip line per citation: file name, page and a short quote preview. */
const labels = computed(() =>
  props.citations.map((citation, index) => {
    const page = citation.pageNo ? `第 ${citation.pageNo} 页` : '原文'
    const quote = citation.quote.trim().replace(/\s+/g, ' ')
    const preview = quote.length > 48 ? `${quote.slice(0, 48)}…` : quote
    return `[${index + 1}] ${citation.filename} · ${page}${preview ? `：${preview}` : ''}`
  }),
)
</script>

<template>
  <span v-if="citations.length > 0" class="citation-badge" aria-label="原文引用">
    <button
      v-for="(citation, index) in citations"
      :key="`${citation.documentId}-${citation.pageNo ?? 0}-${index}`"
      type="button"
      class="citation-badge__item"
      :title="labels[index]"
      :aria-label="labels[index]"
      @click="emit('open', index)"
    >
      [{{ index + 1 }}]
    </button>
  </span>
</template>

<style scoped>
.citation-badge {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 2px;
  vertical-align: super;
  line-height: 1;
}

/* Superscript-looking, but still a full-size hit target via padding. */
.citation-badge__item {
  padding: 2px 3px;
  color: var(--ac-primary);
  font-family: var(--ac-font-mono);
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: 4px;
  transition:
    color 0.15s ease,
    background-color 0.15s ease;
}

.citation-badge__item:hover,
.citation-badge__item:focus-visible {
  color: var(--ac-primary-pressed);
  background: var(--el-color-primary-light-9);
  text-decoration: underline;
}
</style>
