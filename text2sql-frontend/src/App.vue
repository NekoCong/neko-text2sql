<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ComponentPublicInstance } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Expand, Fold, User } from '@element-plus/icons-vue'
import ConversationSidebar from '@/components/ConversationSidebar.vue'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { useConversationStore } from '@/stores/conversation'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const chat = useChatStore()
const conversation = useConversationStore()

/** Public routes (login) render bare, without the app chrome. */
const showChrome = computed(() => route.meta.public !== true)

/** The history sidebar belongs to the chat screen. */
const showSidebar = computed(() => showChrome.value && route.name === 'chat')

/** Desktop collapse state of the sidebar: 260px expanded, 0 collapsed. */
const collapsed = ref(false)

/** Below this width the sidebar turns into an off-canvas panel. */
const narrowQuery = window.matchMedia('(max-width: 767px)')
const isNarrow = ref(narrowQuery.matches)

/** Off-canvas visibility. The panel starts closed on narrow viewports. */
const mobileOpen = ref(false)

/** Template refs used for the off-canvas focus handoff. */
const asideRef = ref<ComponentPublicInstance | null>(null)
const collapseButtonRef = ref<ComponentPublicInstance | null>(null)

/** True when the sidebar is on screen, whichever layout mode is active. */
const sidebarVisible = computed(() => (isNarrow.value ? mobileOpen.value : !collapsed.value))

/** Accessible label for the collapse control in both modes. */
const sidebarToggleLabel = computed(() =>
  sidebarVisible.value ? '收起对话历史' : '展开对话历史',
)

/** Selector for the first control the off-canvas panel should focus. */
const FOCUSABLE_SELECTOR = [
  'button:not([disabled])',
  'a[href]',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

/** Moves focus back to the header control that owns the panel. */
function focusSidebarToggle(): void {
  const toggle = collapseButtonRef.value?.$el
  if (toggle instanceof HTMLElement) {
    toggle.focus()
  }
}

/** Opens the panel and puts focus on its first control ("新建对话"). */
async function openMobileSidebar(): Promise<void> {
  mobileOpen.value = true
  await nextTick()
  const panel = asideRef.value?.$el
  if (panel instanceof HTMLElement) {
    panel.querySelector<HTMLElement>(FOCUSABLE_SELECTOR)?.focus()
  }
}

/**
 * The single dismissal path for the off-canvas panel: backdrop, Escape, route
 * changes and conversation selection all land here so focus returns to the
 * collapse control.
 */
async function closeMobileSidebar(): Promise<void> {
  if (!mobileOpen.value) {
    return
  }
  mobileOpen.value = false
  await nextTick()
  focusSidebarToggle()
}

/** One control drives both the desktop collapse and the off-canvas panel. */
function toggleSidebar(): void {
  if (isNarrow.value) {
    if (mobileOpen.value) {
      void closeMobileSidebar()
      return
    }
    void openMobileSidebar()
    return
  }
  collapsed.value = !collapsed.value
}

/** Escape dismisses the off-canvas panel. */
function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && mobileOpen.value) {
    void closeMobileSidebar()
  }
}

/** Keeps the mode in sync when the viewport crosses the breakpoint. */
function onMediaChange(event: MediaQueryListEvent): void {
  isNarrow.value = event.matches
  if (!event.matches) {
    mobileOpen.value = false
  }
}

onMounted(() => {
  narrowQuery.addEventListener('change', onMediaChange)
  document.addEventListener('keydown', onKeydown)
  // Refresh the profile in the background so the permission-aware navigation reflects the
  // server's current permission set, not a stale localStorage snapshot. Not awaited: the app
  // must not block on it, and a failure simply keeps the stored profile.
  void auth.refreshProfile()
})

onBeforeUnmount(() => {
  narrowQuery.removeEventListener('change', onMediaChange)
  document.removeEventListener('keydown', onKeydown)
})

/** Navigating away always dismisses the off-canvas panel. */
watch(
  () => route.fullPath,
  () => {
    void closeMobileSidebar()
  },
)

/** The panel cannot stay open once the chat sidebar is no longer rendered. */
watch(showSidebar, (visible) => {
  if (!visible) {
    void closeMobileSidebar()
  }
})

/** Header label: display name first, username as fallback. */
const userName = computed(() => auth.user?.displayName || auth.user?.username || '')

/** Account dropdown handler. */
async function onUserCommand(command: string | number | object): Promise<void> {
  if (command === 'logout') {
    auth.logout()
    await router.replace('/login')
    return
  }
  if (command === 'models') {
    await router.push('/settings/models')
  }
}

/** Selecting a conversation always returns to the chat screen. */
function onConversationSelect(): void {
  void closeMobileSidebar()
  if (route.name !== 'chat') {
    void router.push('/')
  }
}

// Session lifecycle: load the history once signed in, drop it again on logout.
watch(
  () => auth.isAuthenticated,
  (authenticated) => {
    if (authenticated) {
      void conversation.load().catch(() => {
        // The sidebar stays empty; an expired token is handled by the http interceptor.
      })
      return
    }
    conversation.clear()
    chat.reset()
  },
  { immediate: true },
)
</script>

<template>
  <router-view v-if="!showChrome" />

  <el-container v-else class="layout">
    <el-header class="layout__header" height="64px">
      <div class="layout__brand">
        <span class="layout__brand-mark" aria-hidden="true">T2S</span>
        <span class="layout__brand-copy">
          <span class="layout__brand-name">Text2SQL</span>
          <span class="layout__brand-desc">智慧校园数据问答</span>
        </span>
      </div>

      <el-button
        v-if="showSidebar"
        ref="collapseButtonRef"
        class="layout__collapse"
        text
        :icon="sidebarVisible ? Fold : Expand"
        :title="sidebarToggleLabel"
        :aria-label="sidebarToggleLabel"
        :aria-expanded="sidebarVisible"
        aria-controls="conversation-sidebar"
        @click="toggleSidebar"
      />

      <el-menu
        :default-active="route.path"
        mode="horizontal"
        router
        :ellipsis="false"
        class="layout__menu"
      >
        <el-menu-item index="/">对话</el-menu-item>
        <el-menu-item index="/datasources">数据源</el-menu-item>
        <el-menu-item v-if="auth.user?.role === 'admin'" index="/admin/datasource-access">
          数据源权限
        </el-menu-item>
        <el-menu-item v-if="auth.can('access.manage')" index="/admin/access-control">
          权限管理
        </el-menu-item>
        <el-menu-item v-if="auth.user?.role === 'admin'" index="/knowledge">知识库</el-menu-item>
      </el-menu>

      <el-dropdown class="layout__user" trigger="click" @command="onUserCommand">
        <span class="layout__user-trigger">
          <el-icon class="layout__user-icon"><User /></el-icon>
          <span class="layout__user-name">{{ userName }}</span>
          <el-icon class="layout__user-caret"><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="models">模型配置</el-dropdown-item>
            <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </el-header>

    <el-container class="layout__body">
      <div
        v-if="showSidebar && isNarrow && mobileOpen"
        class="layout__backdrop"
        aria-hidden="true"
        @click="closeMobileSidebar"
      />

      <el-aside
        v-if="showSidebar"
        id="conversation-sidebar"
        ref="asideRef"
        class="layout__aside"
        :class="{
          'layout__aside--collapsed': !isNarrow && collapsed,
          'layout__aside--offcanvas': isNarrow,
          'layout__aside--open': isNarrow && mobileOpen,
        }"
        :aria-hidden="!sidebarVisible ? 'true' : undefined"
        :inert="!sidebarVisible"
      >
        <ConversationSidebar @select="onConversationSelect" />
      </el-aside>

      <el-main class="layout__main">
        <div
          class="layout__page"
          :class="{ 'layout__page--chat': route.name === 'chat' }"
        >
          <router-view />
        </div>
      </el-main>
    </el-container>

    <el-footer class="layout__footer" height="48px">
      智慧校园数据问答 · 结果仅限当前账号的数据权限范围
    </el-footer>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
  background: var(--ac-canvas);
}

/* ---------- Top bar ------------------------------------------------------ */

.layout__header {
  display: flex;
  align-items: center;
  gap: var(--ac-space-16);
  height: var(--ac-header-height);
  padding: 0 var(--ac-space-24);
  background: var(--ac-surface);
  border-bottom: 1px solid var(--ac-border-soft);
}

.layout__brand {
  display: flex;
  flex: none;
  align-items: center;
  gap: var(--ac-space-12);
  min-width: 0;
}

/* CSS-only brand mark: swap the monogram without touching layout. */
.layout__brand-mark {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  color: var(--ac-surface);
  font-family: var(--ac-font-sans);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
  background: linear-gradient(135deg, var(--ac-primary), var(--ac-primary-pressed));
  border-radius: var(--ac-radius-sm);
  box-shadow: var(--ac-shadow-sm);
}

.layout__brand-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.25;
}

.layout__brand-name {
  color: var(--ac-ink);
  font-family: var(--ac-font-serif);
  font-size: var(--ac-text-hero);
  font-weight: 600;
  letter-spacing: 0.01em;
  white-space: nowrap;
}

.layout__brand-desc {
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
  white-space: nowrap;
}

.layout__collapse {
  flex: none;
  color: var(--ac-muted);
}

.layout__collapse:hover {
  color: var(--ac-primary);
}

.layout__menu {
  flex: 1;
  min-width: 0;
  height: auto;
  border-bottom: none;
}

.layout__menu :deep(.el-menu-item) {
  height: 38px;
  padding: 0 var(--ac-space-16);
  margin: 0 var(--ac-space-4);
  color: var(--el-text-color-regular);
  font-size: var(--ac-text-body);
  line-height: 38px;
  border-radius: var(--ac-radius-sm);
  transition:
    color 0.15s ease,
    background-color 0.15s ease;
}

.layout__menu :deep(.el-menu-item:hover) {
  color: var(--ac-ink);
  background: var(--ac-fill-soft);
}

.layout__menu :deep(.el-menu-item.is-active) {
  color: var(--ac-primary);
  font-weight: 600;
  background: var(--el-color-primary-light-9);
  border-bottom: none;
}

.layout__user {
  flex: none;
  margin-left: auto;
}

.layout__user-trigger {
  display: flex;
  align-items: center;
  gap: var(--ac-space-8);
  height: 36px;
  padding: 0 var(--ac-space-12);
  color: var(--ac-text);
  cursor: pointer;
  border: 1px solid transparent;
  border-radius: var(--ac-radius-sm);
  transition:
    color 0.15s ease,
    background-color 0.15s ease,
    border-color 0.15s ease;
}

.layout__user-trigger:hover,
.layout__user-trigger:focus-visible {
  color: var(--ac-ink);
  background: var(--ac-fill-soft);
  border-color: var(--ac-border-soft);
}

.layout__user-icon {
  color: var(--ac-muted);
}

.layout__user-name {
  max-width: 160px;
  overflow: hidden;
  font-size: var(--ac-text-body);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.layout__user-caret {
  color: var(--ac-placeholder);
  font-size: 12px;
}

/* ---------- Body --------------------------------------------------------- */

.layout__body {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.layout__aside {
  flex: none;
  width: var(--ac-sidebar-width);
  overflow: hidden;
  background: var(--ac-surface);
  border-right: 1px solid var(--ac-border-soft);
  transition:
    width 0.2s ease,
    transform 0.2s ease,
    visibility 0.2s ease;
}

.layout__aside.layout__aside--collapsed {
  width: 0;
  border-right: none;
}

/* The page canvas owns the gutter; every route uses the same rhythm. */
.layout__main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  min-height: 0;
  padding: 0;
  overflow: auto;
  background: var(--ac-canvas);
}

.layout__page {
  flex: 1;
  width: 100%;
  max-width: var(--ac-content-max);
  min-height: 0;
  margin: 0 auto;
  padding: var(--ac-gutter);
}

.layout__page--chat {
  display: flex;
  flex-direction: column;
  max-width: var(--ac-content-max-wide);
  height: 100%;
}

/* ---------- Footer ------------------------------------------------------- */

.layout__footer {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  height: 48px;
  padding: 0 var(--ac-space-24);
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
  background: var(--ac-surface);
  border-top: 1px solid var(--ac-border-soft);
}

/* ---------- Off-canvas sidebar (< 768px) --------------------------------- */

.layout__backdrop {
  position: absolute;
  inset: 0;
  z-index: 90;
  background: var(--ac-backdrop);
}

/* Anchored inside the body row so the header (and its toggle) stays reachable. */
.layout__aside--offcanvas {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  z-index: 100;
  width: min(280px, 85vw);
  overflow: hidden;
  box-shadow: var(--ac-shadow-md);
  transform: translateX(-100%);
  visibility: hidden;
  pointer-events: none;
}

.layout__aside--offcanvas.layout__aside--open {
  transform: translateX(0);
  visibility: visible;
  pointer-events: auto;
}

/* ---------- Responsive --------------------------------------------------- */

/* The wordmark steps down so the nav and account menu fit from tablet down. */
@media (max-width: 1199px) {
  .layout__brand-name {
    font-size: var(--ac-text-title);
  }
}

@media (max-width: 1199px) and (min-width: 768px) {
  .layout__aside {
    width: var(--ac-sidebar-width-md);
  }

  .layout__page {
    padding: var(--ac-space-16);
  }
}

@media (max-width: 767px) {
  .layout__header {
    flex-wrap: wrap;
    height: auto;
    min-height: var(--ac-header-height);
    padding: var(--ac-space-8) var(--ac-space-16);
    gap: var(--ac-space-8) var(--ac-space-12);
  }

  .layout__brand-desc {
    display: none;
  }

  .layout__brand-mark {
    width: 32px;
    height: 32px;
    font-size: 11px;
  }

  .layout__menu {
    flex: 1 0 100%;
    order: 3;
    height: 44px;
    overflow-x: auto;
    overflow-y: hidden;
  }

  .layout__menu :deep(.el-menu-item) {
    height: 44px;
    padding: 0 var(--ac-space-12);
    margin: 0;
    line-height: 44px;
  }

  .layout__user {
    margin-left: auto;
  }

  .layout__user-name {
    display: none;
  }

  .layout__page {
    padding: var(--ac-space-16);
  }

  .layout__main {
    overflow-x: hidden;
  }

  .layout__footer {
    height: auto;
    min-height: 44px;
    padding: var(--ac-space-8) var(--ac-space-16);
    text-align: center;
  }
}
</style>
