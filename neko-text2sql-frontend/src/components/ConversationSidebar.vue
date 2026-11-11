<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { ChatDotRound, Delete, Edit, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { InputInstance } from 'element-plus'
import { conversationErrorMessage } from '@/api/conversations'
import { useConversationStore } from '@/stores/conversation'
import type { Conversation } from '@/types'

/**
 * Left-hand conversation history: grouped by date, with inline rename and a delete
 * confirmation. The component performs its own store calls so failures surface inline
 * as a message; the emitted events let the surrounding shell react (e.g. route back
 * to the chat screen when another page was open).
 */
const emit = defineEmits<{
  select: [id: string]
  create: []
  rename: [id: string, title: string]
  delete: [id: string]
}>()

const conversation = useConversationStore()

const renamingId = ref<string | null>(null)
const renameDraft = ref('')
const renameInput = ref<InputInstance | null>(null)

/** Binds the (single) inline rename input so it can be focused after opening. */
function setRenameInput(el: unknown): void {
  renameInput.value = (el as InputInstance | null) ?? null
}

/** Creates a conversation and selects it. */
async function onCreate(): Promise<void> {
  emit('create')
  try {
    await conversation.create()
  } catch (error) {
    ElMessage.error(conversationErrorMessage(error, '新建对话失败'))
  }
}

/** Selects a conversation unless its title is being edited. */
function onSelect(item: Conversation): void {
  if (renamingId.value === item.id) {
    return
  }
  emit('select', item.id)
  conversation.select(item.id)
}

/** Turns a row into an inline rename input. */
function startRename(item: Conversation): void {
  renamingId.value = item.id
  renameDraft.value = item.title
  void nextTick(() => {
    renameInput.value?.focus()
  })
}

/** Abandons the inline rename without saving. */
function cancelRename(): void {
  renamingId.value = null
  renameDraft.value = ''
}

/** Saves the inline rename; blank or unchanged titles are dropped. */
async function commitRename(item: Conversation): Promise<void> {
  if (renamingId.value !== item.id) {
    return
  }
  const title = renameDraft.value.trim()
  cancelRename()
  if (!title || title === item.title) {
    return
  }
  emit('rename', item.id, title)
  try {
    await conversation.rename(item.id, title)
  } catch (error) {
    ElMessage.error(conversationErrorMessage(error, '重命名失败'))
  }
}

/** Deletes a conversation after the inline confirmation. */
async function onDelete(item: Conversation): Promise<void> {
  emit('delete', item.id)
  try {
    await conversation.remove(item.id)
    ElMessage.success('对话已删除')
  } catch (error) {
    ElMessage.error(conversationErrorMessage(error, '删除对话失败'))
  }
}
</script>

<template>
  <aside class="sidebar" aria-label="对话历史">
    <div class="sidebar__header">
      <span class="sidebar__title">对话历史</span>
    </div>

    <el-button class="sidebar__new" type="primary" size="large" :icon="Plus" @click="onCreate">
      新建对话
    </el-button>

    <div v-loading="conversation.loading" class="sidebar__list">
      <template v-for="group in conversation.groups" :key="group.label">
        <div class="sidebar__group">{{ group.label }}</div>
        <div
          v-for="item in group.items"
          :key="item.id"
          class="conv"
          :class="{ 'conv--active': item.id === conversation.activeId }"
        >
          <template v-if="renamingId === item.id">
            <el-icon class="conv__icon"><ChatDotRound /></el-icon>
            <el-input
              :ref="setRenameInput"
              v-model="renameDraft"
              class="conv__input"
              size="small"
              maxlength="64"
              @click.stop
              @keydown.enter="commitRename(item)"
              @keydown.esc.stop="cancelRename"
              @blur="commitRename(item)"
            />
          </template>

          <template v-else>
            <button
              type="button"
              class="conv__select"
              :aria-current="item.id === conversation.activeId ? 'true' : undefined"
              @click="onSelect(item)"
            >
              <el-icon class="conv__icon"><ChatDotRound /></el-icon>
              <span class="conv__title" :title="item.title">{{ item.title }}</span>
            </button>
            <span class="conv__actions">
              <button
                type="button"
                class="conv__action"
                title="重命名"
                aria-label="重命名对话"
                @click.stop="startRename(item)"
              >
                <el-icon><Edit /></el-icon>
              </button>
              <el-popconfirm
                :title="`确定删除对话「${item.title}」？`"
                width="220"
                confirm-button-text="删除"
                cancel-button-text="取消"
                confirm-button-type="danger"
                @confirm="onDelete(item)"
              >
                <template #reference>
                  <button
                    type="button"
                    class="conv__action conv__action--danger"
                    title="删除"
                    aria-label="删除对话"
                    @click.stop
                  >
                    <el-icon><Delete /></el-icon>
                  </button>
                </template>
              </el-popconfirm>
            </span>
          </template>
        </div>
      </template>

      <div
        v-if="conversation.groups.length === 0"
        class="ac-empty-state ac-empty-state--bordered sidebar__empty"
      >
        <span class="ac-empty-state__title">暂无历史对话</span>
        <span class="ac-empty-state__text">提出第一个问题后，对话会保存在这里</span>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  gap: var(--ac-space-16);
  width: 100%;
  min-width: 0;
  height: 100%;
  padding: var(--ac-space-24) var(--ac-space-16) var(--ac-space-16);
  background: var(--ac-surface);
}

.sidebar__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--ac-space-4);
}

.sidebar__title {
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
  font-weight: 600;
  letter-spacing: 0.02em;
}

.sidebar__new {
  width: 100%;
  height: 40px;
  font-weight: 600;
}

.sidebar__list {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  margin: 0 calc(var(--ac-space-4) * -1);
  padding: 0 var(--ac-space-4);
  overflow-y: auto;
}

.sidebar__group {
  padding: var(--ac-space-12) var(--ac-space-8) var(--ac-space-4);
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
  font-weight: 600;
}

.sidebar__group:first-child {
  padding-top: var(--ac-space-4);
}

.sidebar__empty {
  margin-top: var(--ac-space-8);
}

.conv {
  display: flex;
  align-items: center;
  gap: var(--ac-space-8);
  padding: 0 var(--ac-space-8) 0 0;
  color: var(--ac-text);
  font-size: var(--ac-text-body);
  line-height: var(--ac-line-height-control);
  cursor: pointer;
  background: transparent;
  border-left: 3px solid transparent;
  border-radius: var(--ac-radius-sm);
  transition:
    color 0.15s ease,
    background-color 0.15s ease,
    border-color 0.15s ease;
}

.conv:hover {
  background: var(--ac-fill-soft);
}

/* The row is a container; the label is the real (single) select control. */
.conv__select {
  display: flex;
  flex: 1;
  align-items: center;
  gap: var(--ac-space-8);
  min-width: 0;
  padding: var(--ac-space-8) 0 var(--ac-space-8) var(--ac-space-12);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: var(--ac-radius-sm);
}

.conv__select:focus-visible {
  outline: 2px solid var(--ac-primary);
  outline-offset: -2px;
}

.conv--active {
  color: var(--ac-primary);
  font-weight: 500;
  background: var(--el-color-primary-light-9);
  border-left-color: var(--ac-primary);
}

.conv__icon {
  flex: none;
  font-size: 16px;
  color: var(--ac-placeholder);
}

.conv--active .conv__icon {
  color: var(--ac-primary);
}

.conv__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 2px;
  opacity: 0;
  visibility: hidden;
  transition: opacity 0.15s ease;
}

.conv:hover .conv__actions,
.conv:focus-within .conv__actions,
.conv--active .conv__actions {
  opacity: 1;
  visibility: visible;
}

.conv__action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  color: var(--ac-muted);
  font-size: 14px;
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: 6px;
  transition:
    color 0.15s ease,
    background-color 0.15s ease;
}

.conv__action:hover {
  color: var(--ac-primary);
  background: var(--ac-fill);
}

.conv__action:focus-visible {
  outline: 2px solid var(--ac-primary);
  outline-offset: 1px;
}

.conv__action--danger:hover {
  color: var(--ac-danger);
}

.conv__input {
  flex: 1;
  min-width: 0;
  margin: var(--ac-space-4) 0 var(--ac-space-4) var(--ac-space-12);
}
</style>
