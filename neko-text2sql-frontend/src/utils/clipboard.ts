/**
 * Copies text to the clipboard, with a fallback for non-secure contexts.
 *
 * `navigator.clipboard` is only exposed in secure contexts (HTTPS or
 * localhost). The frontend is also served over plain HTTP (see nginx.conf),
 * where the API is undefined and `writeText` throws. In that case, fall back
 * to the legacy `document.execCommand('copy')` path instead of failing the
 * user's click.
 *
 * @param text text to copy
 * @returns whether the text was copied
 */
export async function copyText(text: string): Promise<boolean> {
  if (canUseAsyncClipboard()) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // Permission denied, document not focused, or the browser blocked it.
      // Try the legacy path instead of surfacing a failure.
    }
  }
  return copyWithExecCommand(text)
}

/** True when the page may use the async Clipboard API. */
function canUseAsyncClipboard(): boolean {
  return (
    typeof window !== 'undefined' &&
    window.isSecureContext &&
    typeof navigator !== 'undefined' &&
    typeof navigator.clipboard?.writeText === 'function'
  )
}

/** Legacy copy path, supported by every browser this app targets. */
function copyWithExecCommand(text: string): boolean {
  if (typeof document === 'undefined' || typeof document.execCommand !== 'function') {
    return false
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.setAttribute('aria-hidden', 'true')
  textarea.style.position = 'fixed'
  textarea.style.top = '0'
  textarea.style.left = '0'
  textarea.style.width = '1px'
  textarea.style.height = '1px'
  textarea.style.padding = '0'
  textarea.style.border = 'none'
  textarea.style.outline = 'none'
  textarea.style.boxShadow = 'none'
  textarea.style.background = 'transparent'
  textarea.style.opacity = '0'

  const selection = document.getSelection()
  const previousRange = selection && selection.rangeCount > 0 ? selection.getRangeAt(0) : null

  document.body.appendChild(textarea)
  textarea.focus()
  textarea.select()
  textarea.setSelectionRange(0, text.length)

  try {
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    textarea.remove()
    if (selection && previousRange) {
      selection.removeAllRanges()
      selection.addRange(previousRange)
    }
  }
}
