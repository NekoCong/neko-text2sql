<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  listRoles,
  listUsers,
  normalizeAccessControlError,
  updateUserStatus,
} from '@/api/accessControl'
import type { AccessRole, AccessUser, AccessUserPage } from '@/types'
import { useAuthStore } from '@/stores/auth'
import UserDialogs from '@/components/access-control/UserDialogs.vue'

const auth = useAuthStore()
const canRead = computed(() => auth.can('user.read'))
const canWrite = computed(() => auth.can('user.manage'))

/** Tooltip shown on write controls when the caller can read users but not manage them. */
const writeTip = computed(() => (canWrite.value ? '' : '缺少 user.manage 权限，无法修改用户'))

const loading = ref(false)
const roles = ref<AccessRole[]>([])
const page = ref<AccessUserPage>({ items: [], total: 0, page: 0, size: 20 })

const query = ref('')
// Element Plus sets a cleared select to undefined rather than the initial empty string.
const enabledFilter = ref<string | undefined>('')
const roleFilter = ref<string | undefined>('')
const currentPage = ref(1)
const pageSize = ref(20)

let debounceTimer: number | undefined

// Row-dialog state handed to the shared dialog component.
const editingUser = ref<AccessUser | null>(null)
const createOpen = ref(false)
const editNameOpen = ref(false)
const resetOpen = ref(false)
const assignOpen = ref(false)

async function loadRoles(): Promise<void> {
  try {
    roles.value = await listRoles()
  } catch (error) {
    // The role filter and role pickers degrade to empty; the user list still works.
    roles.value = []
    ElMessage.error(normalizeAccessControlError(error, '加载角色失败').message)
  }
}

async function loadUsers(): Promise<void> {
  if (!canRead.value) {
    return
  }
  loading.value = true
  try {
    page.value = await listUsers({
      query: query.value.trim() || undefined,
      enabled:
        enabledFilter.value == null || enabledFilter.value === ''
          ? undefined
          : enabledFilter.value === 'true',
      roleId: roleFilter.value || undefined,
      page: currentPage.value - 1,
      size: pageSize.value,
    })
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '加载用户失败').message)
  } finally {
    loading.value = false
  }
}

function onQueryChange(): void {
  currentPage.value = 1
  window.clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(() => {
    void loadUsers()
  }, 300)
}

function onFilterChange(): void {
  currentPage.value = 1
  void loadUsers()
}

function onPageChange(value: number): void {
  currentPage.value = value
  void loadUsers()
}

function onSizeChange(value: number): void {
  pageSize.value = value
  currentPage.value = 1
  void loadUsers()
}

function openCreate(): void {
  createOpen.value = true
}

function openEditName(row: AccessUser): void {
  editingUser.value = row
  editNameOpen.value = true
}

function openReset(row: AccessUser): void {
  editingUser.value = row
  resetOpen.value = true
}

function openAssign(row: AccessUser): void {
  editingUser.value = row
  assignOpen.value = true
}

function onDialogsSaved(): void {
  void loadUsers()
}

async function toggleStatus(row: AccessUser): Promise<void> {
  try {
    await updateUserStatus(row.id, { enabled: !row.enabled })
    ElMessage.success(row.enabled ? '用户已停用' : '用户已启用')
    await loadUsers()
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '更新用户状态失败').message)
  }
}

function formatTime(value: number): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

watch(
  () => canRead.value,
  (readable) => {
    if (readable) {
      void loadUsers()
    }
  },
)

onMounted(() => {
  void loadRoles()
  if (canRead.value) {
    void loadUsers()
  }
})

onBeforeUnmount(() => {
  window.clearTimeout(debounceTimer)
})
</script>

<template>
  <div class="users">
    <div v-if="!canRead" class="ac-empty-state ac-empty-state--bordered">
      <span class="ac-empty-state__title">没有查看用户的权限</span>
      <span class="ac-empty-state__text">需要 user.read 权限才能查看用户列表，请联系管理员。</span>
    </div>

    <template v-else>
      <div class="users__filters">
        <el-input
          v-model="query"
          class="users__search"
          placeholder="搜索用户名或显示名称"
          clearable
          :prefix-icon="Search"
          @input="onQueryChange"
        />
        <el-select
          v-model="enabledFilter"
          class="users__select"
          placeholder="状态"
          clearable
          @change="onFilterChange"
        >
          <el-option label="启用" value="true" />
          <el-option label="停用" value="false" />
        </el-select>
        <el-select
          v-model="roleFilter"
          class="users__select"
          placeholder="角色"
          clearable
          filterable
          @change="onFilterChange"
        >
          <el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id" />
        </el-select>
        <div class="ac-action-bar users__actions">
          <el-button :icon="Refresh" :loading="loading" @click="loadUsers">刷新</el-button>
          <el-tooltip :disabled="canWrite" :content="writeTip" placement="top">
            <span>
              <el-button type="primary" :icon="Plus" :disabled="!canWrite" @click="openCreate">
                新增用户
              </el-button>
            </span>
          </el-tooltip>
        </div>
      </div>

      <div class="ac-table-wrapper">
        <el-table v-loading="loading" :data="page.items" row-key="id" stripe>
          <el-table-column label="用户名" min-width="140">
            <template #default="{ row }">
              <div class="users__name">
                <span class="users__name-main">{{ row.username }}</span>
                <span v-if="row.displayName" class="users__name-sub">{{ row.displayName }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="角色" min-width="200">
            <template #default="{ row }">
              <div class="users__tags">
                <el-tag v-for="role in row.roles" :key="role.id" size="small" effect="plain">
                  {{ role.name }}
                </el-tag>
                <span v-if="row.roles.length === 0" class="ac-muted">—</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
                {{ row.enabled ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-tooltip :disabled="canWrite" :content="writeTip" placement="top">
                <span class="users__row-actions">
                  <el-button size="small" text :disabled="!canWrite" @click="openEditName(row)">
                    编辑名称
                  </el-button>
                  <el-button size="small" text :disabled="!canWrite" @click="openAssign(row)">
                    分配角色
                  </el-button>
                  <el-button size="small" text :disabled="!canWrite" @click="openReset(row)">
                    重置密码
                  </el-button>
                  <el-popconfirm
                    :title="row.enabled ? '确定停用该用户？' : '确定启用该用户？'"
                    confirm-button-text="确定"
                    cancel-button-text="取消"
                    :confirm-button-type="row.enabled ? 'danger' : 'primary'"
                    @confirm="toggleStatus(row)"
                  >
                    <template #reference>
                      <el-button
                        size="small"
                        :type="row.enabled ? 'danger' : 'success'"
                        text
                        :disabled="!canWrite"
                      >
                        {{ row.enabled ? '停用' : '启用' }}
                      </el-button>
                    </template>
                  </el-popconfirm>
                </span>
              </el-tooltip>
            </template>
          </el-table-column>

          <template #empty>
            <div class="ac-empty-state">
              <span class="ac-empty-state__title">暂无用户</span>
              <span class="ac-empty-state__text">调整筛选条件，或点击「新增用户」创建账号。</span>
            </div>
          </template>
        </el-table>
      </div>

      <el-pagination
        class="users__pagination"
        background
        layout="total, sizes, prev, pager, next"
        :total="page.total"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />

      <UserDialogs
        :roles="roles"
        :editing-user="editingUser"
        v-model:create-open="createOpen"
        v-model:edit-name-open="editNameOpen"
        v-model:reset-open="resetOpen"
        v-model:assign-open="assignOpen"
        @saved="onDialogsSaved"
      />
    </template>
  </div>
</template>

<style scoped>
.users__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ac-space-8);
}

.users__search {
  width: 240px;
  max-width: 100%;
}

.users__select {
  width: 140px;
}

.users__actions {
  margin-left: auto;
}

.users__name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.users__name-main {
  color: var(--ac-ink);
  font-weight: 600;
}

.users__name-sub {
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
}

.users__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ac-space-4);
}

.users__row-actions {
  display: inline-flex;
  align-items: center;
}

.users__pagination {
  justify-content: flex-end;
  margin-top: var(--ac-space-12);
}
</style>
