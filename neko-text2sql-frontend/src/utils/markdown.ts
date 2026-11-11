/** A fenced code block extracted from an assistant answer. */
export interface CodeSegment {
  type: 'code'
  lang: string
  value: string
}

/** Plain text outside of code fences and tables. */
export interface TextSegment {
  type: 'text'
  value: string
}

/** A GitHub-flavoured markdown table extracted from an assistant answer. */
export interface TableSegment {
  type: 'table'
  headers: string[]
  rows: string[][]
}

/** Ordered piece of a parsed assistant answer. */
export type Segment = CodeSegment | TextSegment | TableSegment

const FENCE = /```(\w*)\n([\s\S]*?)```/g
const SEPARATOR = /^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)+\|?\s*$/

/**
 * Splits an assistant answer into text, fenced-code and table segments so SQL and tabular
 * results are rendered distinctly from prose.
 *
 * @param content raw assistant markdown
 * @returns the ordered segments
 */
export function parseSegments(content: string): Segment[] {
  const segments: Segment[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null

  FENCE.lastIndex = 0
  while ((match = FENCE.exec(content)) !== null) {
    if (match.index > lastIndex) {
      segments.push(...splitTables(content.slice(lastIndex, match.index)))
    }
    segments.push({
      type: 'code',
      lang: match[1] || 'sql',
      value: match[2].replace(/\n$/, ''),
    })
    lastIndex = match.index + match[0].length
  }

  if (lastIndex < content.length) {
    segments.push(...splitTables(content.slice(lastIndex)))
  }

  return segments.filter((segment) => segment.type !== 'text' || segment.value.trim().length > 0)
}

/**
 * Extracts markdown tables from a text block, keeping the surrounding prose.
 *
 * A table is a header row containing pipes, immediately followed by a separator row
 * (`---|---`, optionally with alignment colons) with the same number of columns.
 *
 * @param text a text chunk without code fences
 * @returns text and table segments in order
 */
function splitTables(text: string): Segment[] {
  const lines = text.split('\n')
  const segments: Segment[] = []
  let buffer: string[] = []
  let index = 0

  const flush = (): void => {
    if (buffer.length > 0) {
      segments.push({ type: 'text', value: buffer.join('\n') })
      buffer = []
    }
  }

  while (index < lines.length) {
    const header = lines[index]
    const separator = lines[index + 1]
    if (
      separator !== undefined &&
      header.includes('|') &&
      SEPARATOR.test(separator) &&
      cells(header).length === cells(separator).length
    ) {
      const headers = cells(header)
      const rows: string[][] = []
      let rowIndex = index + 2
      while (rowIndex < lines.length && lines[rowIndex].includes('|') && lines[rowIndex].trim() !== '') {
        rows.push(fit(cells(lines[rowIndex]), headers.length))
        rowIndex++
      }
      flush()
      segments.push({ type: 'table', headers, rows })
      index = rowIndex
      continue
    }
    buffer.push(header)
    index++
  }

  flush()
  return segments
}

/** Splits one table row into trimmed cells, honouring escaped pipes. */
function cells(line: string): string[] {
  let value = line.trim()
  if (value.startsWith('|')) {
    value = value.slice(1)
  }
  if (value.endsWith('|')) {
    value = value.slice(0, -1)
  }
  const result: string[] = []
  let current = ''
  for (let i = 0; i < value.length; i++) {
    const char = value[i]
    if (char === '\\' && value[i + 1] === '|') {
      current += '|'
      i++
      continue
    }
    if (char === '|') {
      result.push(current.trim())
      current = ''
      continue
    }
    current += char
  }
  result.push(current.trim())
  return result
}

/** Pads or trims a row to the header width. */
function fit(row: string[], width: number): string[] {
  if (row.length === width) {
    return row
  }
  if (row.length > width) {
    return row.slice(0, width)
  }
  return [...row, ...Array.from({ length: width - row.length }, () => '')]
}
