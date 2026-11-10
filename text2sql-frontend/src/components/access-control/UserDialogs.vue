<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createUser,
  normalizeAccessControlError,
  replaceUserRoles,
  resetUserPassword,
  updateUserDisplayName,
} from '@/api/accessControl'
import type { AccessRole, AccessUser } from '@/types'

/** Mirrors the backend's username and password rules. */
const USERNAME_PATTERN = /^[A-Za-z0-9._-]{3,64}$/

const props = defineProps<{
  roles: AccessRole[]
  /** The user the row dialogs operate on, or `null` when no row dialog is open. */
  editingUser: AccessUser | null
  createOpen: boolean
  editNameOpen: boolean
  resetOpen: boolean
  assignOpen: boolean
}>()

const emit = defineEmits<{
  (e: 'update:createOpen', value: boolean): void
  (e: 'update:editNameOpen', value: boolean): void
  (e: 'update:resetOpen', value: boolean): void
  (e: 'update:assignOpen', value: boolean): void
  (e: 'saved'): void
}>()

const createOpenModel = computed({
  get: () => props.createOpen,
  set: (value: boolean) => emit('update:createOpen', value),
})
const editNameOpenModel = computed({
  get: () => props.editNameOpen,
  set: (value: boolean) => emit('update:editNameOpen', value),
})
const resetOpenModel = computed({
  get: () => props.resetOpen,
  set: (value: boolean) => emit('update:resetOpen', value),
})
const assignOpenModel = computed({
  get: () => props.assignOpen,
  set: (value: boolean) => emit('update:assignOpen', value),
})

// ---------- Create ----------------------------------------------------------

interface CreateFormState {
  username: string
  displayName: string
  password: string
  confirmPassword: string
  roleIds: string[]
}

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

watch(
  () => props.createOpen,
  (open) => {
    if (open) {
      Object.assign(createForm, {
        username: '',
        displayName: '',
        password: '',
        confirmPassword: '',
        roleIds: [],
      })
    }
  },
)

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
    createOpenModel.value = false
    emit('saved')
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '创建用户失败').message)
  } finally {
    createSaving.value = false
  }
}

// ---------- Edit display name -------------------------------------------------

const editNameSaving = ref(false)
const editNameForm = reactive({ displayName: '' })

watch(
  () => props.editNameOpen,
  (open) => {
    if (open) {
      editNameForm.displayName = props.editingUser?.displayName ?? ''
    }
  },
)

async function submitEditName(): Promise<void> {
  if (!props.editingUser) {
    return
  }
  editNameSaving.value = true
  try {
    await updateUserDisplayName(props.editingUser.id, {
      displayName: editNameForm.displayName.trim() || null,
    })
    ElMessage.success('显示名称已更新')
    editNameOpenModel.value = false
    emit('saved')
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '更新显示名称失败').message)
  } finally {
    editNameSaving.value = false
  }
}

// ---------- Reset password ---------------------------------------------------

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

watch(
  () => props.resetOpen,
  (open) => {
    if (open) {
      resetForm.password = ''
      resetForm.confirmPassword = ''
    }
  },
)

async function submitReset(): Promise<void> {
  if (!props.editingUser) {
    return
  }
  const valid = await resetFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  resetSaving.value = true
  try {
    await resetUserPassword(props.editingUser.id, { password: resetForm.password })
    ElMessage.success('密码已重置')
    resetOpenModel.value = false
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '重置密码失败').message)
  } finally {
    resetSaving.value = false
  }
}

// ---------- Assign roles -----------------------------------------------------

const assignSaving = ref(false)
const assignForm = reactive({ roleIds: [] as string[] })

watch(
  () => props.assignOpen,
  (open) => {
    if (open) {
      assignForm.roleIds = props.editingUser?.roles.map((role) => role.id) ?? []
    }
  },
)

async function submitAssign(): Promise<void> {
  if (!props.editingUser) {
    return
  }
  assignSaving.value = true
  try {
    await replaceUserRoles(props.editingUser.id, { roleIds: assignForm.roleIds })
    ElMessage.success('角色分配已保存')
    assignOpenModel.value = false
    emit('saved')
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '保存角色分配失败').message)
  } finally {
    assignSaving.value = false
  }
}
</script>

<template>
  <!-- Create -->
  <el-dialog
    v-model="createOpenModel"
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
        <el-select v-model="createForm.roleIds" class="user-dialogs__control" multiple filterable>
          <el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <div class="user-dialogs__footer">
        <span class="user-dialogs__spacer" />
        <el-button @click="createOpenModel = false">取消</el-button>
        <el-button type="primary" :loading="createSaving" @click="submitCreate">创建</el-button>
      </div>
    </template>
  </el-dialog>

  <!-- Edit display name -->
  <el-dialog
    v-model="editNameOpenModel"
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
      <div class="user-dialogs__footer">
        <span class="user-dialogs__spacer" />
        <el-button @click="editNameOpenModel = false">取消</el-button>
        <el-button type="primary" :loading="editNameSaving" @click="submitEditName">保存</el-button>
      </div>
    </template>
  </el-dialog>

  <!-- Reset password -->
  <el-dialog
    v-model="resetOpenModel"
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
      <div class="user-dialogs__footer">
        <span class="user-dialogs__spacer" />
        <el-button @click="resetOpenModel = false">取消</el-button>
        <el-button type="primary" :loading="resetSaving" @click="submitReset">重置</el-button>
      </div>
    </template>
  </el-dialog>

  <!-- Assign roles -->
  <el-dialog
    v-model="assignOpenModel"
    class="ac-dialog"
    title="分配角色"
    width="min(480px, calc(100vw - 32px))"
    :close-on-click-modal="false"
  >
    <p class="ac-text-meta">为用户「{{ editingUser?.username }}」选择角色，保存后整体替换现有角色。</p>
    <el-form label-width="96px">
      <el-form-item label="角色">
        <el-select v-model="assignForm.roleIds" class="user-dialogs__control" multiple filterable>
          <el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <div class="user-dialogs__footer">
        <span class="user-dialogs__spacer" />
        <el-button @click="assignOpenModel = false">取消</el-button>
        <el-button type="primary" :loading="assignSaving" @click="submitAssign">保存</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.user-dialogs__control {
  width: 100%;
}

.user-dialogs__footer {
  display: flex;
  align-items: center;
  gap: var(--ac-space-8);
}

.user-dialogs__spacer {
  flex: 1;
}
</style>
