<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Key, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createUser,
  listRoles,
  listUsers,
  normalizeAccessControlError,
  replaceUserRoles,
  resetUserPassword,
  updateUserDisplayName,
  updateUserStatus,
} from '@/api/accessControl'
import type { AccessRole, AccessUser, AccessUserPage } from '@/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const canRead = computed(() => auth.can('user.read'))
const canWrite = computed(() => auth.can('user.manage'))

/** Tooltip shown on write controls when the caller can read users but not manage them. */
const writeTip = computed(() => (canWrite.value ? '' : '缺少 user.manage 权限，无法修改用户'))

/** Mirrors the backend's username and password rules. */
const USERNAME_PATTERN = /^[A-Za-z0-9._-]{3,64}$/

const loading = ref(false)
const roles = ref<AccessRole[]>([])
const page = ref<AccessUserPage>({ items: [], total: 0, page: 0, size: 20 })

const query = ref('')
const enabledFilter = ref<string>('')
const roleFilter = ref('')
const currentPage = ref(1)
const pageSize = ref(20)

let debounceTimer: number | undefined

async function loadRoles(): Promise<void> {
  try {
    roles.value = await listRoles()
  } catch {
    // The role filter and role pickers degrade to empty; the user list still works.
    roles.value = []
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
      enabled: enabledFilter.value === '' ? undefined : enabledFilter.value === 'true',
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

// ---------- Create ----------------------------------------------------------

interface CreateFormState {
  username: string
  displayName: string
  password: string
  confirmPassword: string
  roleIds: string[]
}

const createVisible = ref(false)
const createSaving = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive<CreateFormState>({
  username: '',
  displayName: '',
  password: '',
  confirmPassword: '',
  roleIds: [],
})

const createRules: FormRules<CreateFormState> = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (!value || USERNAME_PATTERN.test(value.trim())) {
          callback()
        } else {
          callback(new Error('用户名需为 3-64 位字母、数字、点、短横线或下划线'))
        }
      },
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value.length >= 8 && value.length <= 72) {
          callback()
        } else {
          callback(new Error('密码长度必须为 8-72 个字符'))
        }
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value === createForm.password) {
          callback()
        } else {
          callback(new Error('两次输入的密码不一致'))
        }
      },
      trigger: 'blur',
    },
  ],
  roleIds: [
    {
      validator: (_rule, value: string[], callback) => {
        if (value && value.length > 0) {
          callback()
        } else {
          callback(new Error('请至少选择一个角色'))
        }
      },
      trigger: 'change',
    },
  ],
}

function openCreate(): void {
  Object.assign(createForm, {
    username: '',
    displayName: '',
    password: '',
    confirmPassword: '',
    roleIds: [],
  })
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  const valid = await createFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  createSaving.value = true
  try {
    await createUser({
      username: createForm.username.trim(),
      password: createForm.password,
      displayName: createForm.displayName.trim() || undefined,
      roleIds: createForm.roleIds,
    })
    ElMessage.success('用户已创建')
    createVisible.value = false
    await loadUsers()
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '创建用户失败').message)
  } finally {
    createSaving.value = false
  }
}

// ---------- Edit display name -------------------------------------------------

const editNameVisible = ref(false)
const editNameSaving = ref(false)
const editNameForm = reactive({ displayName: '' })
const editingUser = ref<AccessUser | null>(null)

function openEditName(row: AccessUser): void {
  editingUser.value = row
  editNameForm.displayName = row.displayName ?? ''
  editNameVisible.value = true
}

async function submitEditName(): Promise<void> {
  if (!editingUser.value) {
    return
  }
  editNameSaving.value = true
  try {
    await updateUserDisplayName(editingUser.value.id, {
      displayName: editNameForm.displayName.trim() || null,
    })
    ElMessage.success('显示名称已更新')
    editNameVisible.value = false
    await loadUsers()
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '更新显示名称失败').message)
  } finally {
    editNameSaving.value = false
  }
}

// ---------- Enable / disable -------------------------------------------------

async function toggleStatus(row: AccessUser): Promise<void> {
  try {
    await updateUserStatus(row.id, { enabled: !row.enabled })
    ElMessage.success(row.enabled ? '用户已停用' : '用户已启用')
    await loadUsers()
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '更新用户状态失败').message)
  }
}

// ---------- Reset password ---------------------------------------------------

const resetVisible = ref(false)
const resetSaving = ref(false)
const resetForm = reactive({ password: '', confirmPassword: '' })
const resetFormRef = ref<FormInstance>()

const resetRules: FormRules<{ password: string; confirmPassword: string }> = {
  password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value.length >= 8 && value.length <= 72) {
          callback()
        } else {
          callback(new Error('密码长度必须为 8-72 个字符'))
        }
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value === resetForm.password) {
          callback()
        } else {
          callback(new Error('两次输入的密码不一致'))
        }
      },
      trigger: 'blur',
    },
  ],
}

function openReset(row: AccessUser): void {
  editingUser.value = row
  resetForm.password = ''
  resetForm.confirmPassword = ''
  resetVisible.value = true
}

async function submitReset(): Promise<void> {
  if (!editingUser.value) {
    return
  }
  const valid = await resetFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  resetSaving.value = true
  try {
    await resetUserPassword(editingUser.value.id, { password: resetForm.password })
    ElMessage.success('密码已重置')
    resetVisible.value = false
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '重置密码失败').message)
  } finally {
    resetSaving.value = false
  }
}

// ---------- Assign roles -----------------------------------------------------

const assignVisible = ref(false)
const assignSaving = ref(false)
const assignForm = reactive({ roleIds: [] as string[] })

function openAssign(row: AccessUser): void {
  editingUser.value = row
  assignForm.roleIds = row.roles.map((role) => role.id)
  assignVisible.value = true
}

async function submitAssign(): Promise<void> {
  if (!editingUser.value) {
    return
  }
  assignSaving.value = true
  try {
    await replaceUserRoles(editingUser.value.id, { roleIds: assignForm.roleIds })
    ElMessage.success('角色分配已保存')
    assignVisible.value = false
    await loadUsers()
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '保存角色分配失败').message)
  } finally {
    assignSaving.value = false
  }
}

// ---------- Presentation -----------------------------------------------------

function formatTime(value: number): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

watch(() => canRead.value, (readable) => {
  if (readable) {
    void loadUsers()
  }
})

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
                  <el-button
                    size="small"
                    text
                    :icon="Key"
                    :disabled="!canWrite"
                    @click="openReset(row)"
                  >
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

      <!-- Create -->
      <el-dialog
        v-model="createVisible"
        class="ac-dialog"
        title="新增用户"
        width="min(560px, calc(100vw - 32px))"
        :close-on-click-modal="false"
      >
        <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="96px">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="createForm.username" maxlength="64" placeholder="3-64 位字母、数字、点、短横线或下划线" />
          </el-form-item>
          <el-form-item label="显示名称">
            <el-input v-model="createForm.displayName" maxlength="100" placeholder="可选" />
          </el-form-item>
          <el-form-item label="初始密码" prop="password">
            <el-input
              v-model="createForm.password"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="8-72 个字符"
            />
          </el-form-item>
          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input
              v-model="createForm.confirmPassword"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="再次输入密码"
            />
          </el-form-item>
          <el-form-item label="角色" prop="roleIds">
            <el-select v-model="createForm.roleIds" class="users__control" multiple filterable>
              <el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id" />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <div class="users__footer">
            <span class="users__spacer" />
            <el-button @click="createVisible = false">取消</el-button>
            <el-button type="primary" :loading="createSaving" @click="submitCreate">创建</el-button>
          </div>
        </template>
      </el-dialog>

      <!-- Edit display name -->
      <el-dialog
        v-model="editNameVisible"
        class="ac-dialog"
        title="编辑显示名称"
        width="min(440px, calc(100vw - 32px))"
        :close-on-click-modal="false"
      >
        <el-form label-width="96px">
          <el-form-item label="显示名称">
            <el-input v-model="editNameForm.displayName" maxlength="100" placeholder="留空清除显示名称" />
          </el-form-item>
        </el-form>
        <template #footer>
          <div class="users__footer">
            <span class="users__spacer" />
            <el-button @click="editNameVisible = false">取消</el-button>
            <el-button type="primary" :loading="editNameSaving" @click="submitEditName">保存</el-button>
          </div>
        </template>
      </el-dialog>

      <!-- Reset password -->
      <el-dialog
        v-model="resetVisible"
        class="ac-dialog"
        title="重置密码"
        width="min(440px, calc(100vw - 32px))"
        :close-on-click-modal="false"
      >
        <p class="ac-text-meta">为用户「{{ editingUser?.username }}」设置新密码，密码不会回显。</p>
        <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-width="96px">
          <el-form-item label="新密码" prop="password">
            <el-input
              v-model="resetForm.password"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="8-72 个字符"
            />
          </el-form-item>
          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input
              v-model="resetForm.confirmPassword"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="再次输入密码"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <div class="users__footer">
            <span class="users__spacer" />
            <el-button @click="resetVisible = false">取消</el-button>
            <el-button type="primary" :loading="resetSaving" @click="submitReset">重置</el-button>
          </div>
        </template>
      </el-dialog>

      <!-- Assign roles -->
      <el-dialog
        v-model="assignVisible"
        class="ac-dialog"
        title="分配角色"
        width="min(480px, calc(100vw - 32px))"
        :close-on-click-modal="false"
      >
        <p class="ac-text-meta">为用户「{{ editingUser?.username }}」选择角色，保存后整体替换现有角色。</p>
        <el-form label-width="96px">
          <el-form-item label="角色">
            <el-select v-model="assignForm.roleIds" class="users__control" multiple filterable>
              <el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id" />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <div class="users__footer">
            <span class="users__spacer" />
            <el-button @click="assignVisible = false">取消</el-button>
            <el-button type="primary" :loading="assignSaving" @click="submitAssign">保存</el-button>
          </div>
        </template>
      </el-dialog>
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

.users__control {
  width: 100%;
}

.users__footer {
  display: flex;
  align-items: center;
  gap: var(--ac-space-8);
}

.users__spacer {
  flex: 1;
}
</style>
