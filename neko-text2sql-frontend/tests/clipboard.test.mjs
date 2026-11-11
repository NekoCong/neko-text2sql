import test from 'node:test'
import assert from 'node:assert/strict'
import { copyText } from '../src/utils/clipboard.ts'

/**
 * Installs the minimal browser globals the clipboard helper touches.
 * `secure` controls `window.isSecureContext`, which is what decides whether
 * the async Clipboard API is even exposed to the page.
 */
function installEnvironment({ secure, clipboard, execResult = true }) {
  const created = []

  class FakeTextarea {
    constructor() {
      this.style = {}
      this.value = ''
      this.parentNode = null
      this.attributes = {}
    }

    setAttribute(name, value) {
      this.attributes[name] = value
    }

    select() {
      this.selected = true
    }

    focus() {
      this.focused = true
    }

    setSelectionRange(start, end) {
      this.selectionRange = [start, end]
    }

    remove() {
      this.parentNode = null
    }
  }

  const body = {
    appendChild(element) {
      element.parentNode = body
      created.push(element)
      return element
    },
    removeChild(element) {
      element.parentNode = null
    },
  }

  const document = {
    body,
    activeElement: null,
    createElement(tag) {
      assert.equal(tag, 'textarea')
      return new FakeTextarea()
    },
    execCommand(command) {
      assert.equal(command, 'copy')
      return execResult
    },
    getSelection() {
      return {
        rangeCount: 0,
        removeAllRanges() {},
        addRange() {},
      }
    },
  }

  Object.defineProperty(globalThis, 'document', { value: document, configurable: true, writable: true })
  Object.defineProperty(globalThis, 'window', {
    value: { isSecureContext: secure },
    configurable: true,
    writable: true,
  })
  Object.defineProperty(globalThis.navigator, 'clipboard', { value: clipboard, configurable: true })

  return { created }
}

test('uses the async Clipboard API in a secure context', async () => {
  let copied
  installEnvironment({
    secure: true,
    clipboard: {
      writeText: async (value) => {
        copied = value
      },
    },
  })

  assert.equal(await copyText('SELECT 1'), true)
  assert.equal(copied, 'SELECT 1')
})

test('falls back to execCommand when the page is not a secure context', async () => {
  const { created } = installEnvironment({ secure: false, clipboard: undefined })

  assert.equal(await copyText('SELECT 2'), true)
  assert.equal(created.length, 1)
  assert.equal(created[0].value, 'SELECT 2')
  assert.equal(created[0].parentNode, null)
})

test('falls back when the async Clipboard API rejects', async () => {
  let attempts = 0
  installEnvironment({
    secure: true,
    clipboard: {
      writeText: async () => {
        attempts += 1
        throw new Error('denied')
      },
    },
  })

  assert.equal(await copyText('SELECT 3'), true)
  assert.equal(attempts, 1)
})

test('returns false when both clipboard paths fail', async () => {
  installEnvironment({
    secure: true,
    clipboard: {
      writeText: async () => {
        throw new Error('denied')
      },
    },
    execResult: false,
  })

  assert.equal(await copyText('SELECT 4'), false)
})
