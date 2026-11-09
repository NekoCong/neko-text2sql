<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, Edit, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createRole,
  listRoles,
  normalizeAccessControlError,
  updateRole,
} from '@/api/accessControl'
import type { AccessRole, AccessRoleRequest } from '@/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
/** The role catalog itself is `access.manage`-gated, so a caller here may always write. */
const canWrite = computed(() => auth.can('access.manage'))

const loading = ref(false)
const roles = ref<AccessRole[]>([])
const savingToggleId = ref<string | null>(null)

/** Role-code pattern mirroring the backend (`^[a-z][a-z0-9_-]{1,63}$`, i.e. 2-64 chars). */
const CODE_PATTERN = /^[a-z][a-z0-9_-]{1,63}$/

async function load(): Promise<void> {
  loading.value = true
  try {
    roles.value = await listRoles()
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '加载角色失败').message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ---------- Create / edit --------------------------------------------------

interface RoleFormState {
  code: string
  name: string
  description: string
  parentId: string
  enabled: boolean
}

const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const saving = ref(false)
const formError = ref('')
const formRef = ref<FormInstance>()
const form = reactive<RoleFormState>({ code: '', name: '', description: '', parentId: '', enabled: true })

const dialogTitle = computed(() => (editingId.value ? '编辑角色' : '新增角色'))

/** Roles that may be a parent: for edit, exclude the role itself and its descendants. */
const parentOptions = computed(() => {
  const excluded = new Set<string>()
  if (editingId.value) {
    excluded.add(editingId.value)
    for (const id of descendantsOf(editingId.value)) {
      excluded.add(id)
    }
  }
  return roles.value.filter((role) => !excluded.has(role.id))
})

const rules: FormRules<RoleFormState> = {
  code: [
    { required: true, message: '请输入角色编码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (!value || CODE_PATTERN.test(value.trim())) {
          callback()
        } else {
          callback(new Error('编码需以小写字母开头，仅含小写字母、数字、下划线和短横线（2-64 位）'))
        }
      },
      trigger: 'blur',
    },
  ],
  name: [
    { required: true, message: '请输入角色名称', trigger: 'blur' },
    { max: 100, message: '角色名称不能超过 100 个字符', trigger: 'blur' },
  ],
  description: [{ max: 512, message: '角色描述不能超过 512 个字符', trigger: 'blur' }],
}

/** Descendants of a role (by id), used to forbid a cycle via the parent selector. */
function descendantsOf(id: string): string[] {
  const result = new Set<string>()
  const walk = (parentId: string): void => {
    for (const role of roles.value) {
      if (role.parentId === parentId && !result.has(role.id)) {
        result.add(role.id)
        walk(role.id)
      }
    }
  }
  walk(id)
  return [...result]
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, { code: '', name: '', description: '', parentId: '', enabled: true })
  formError.value = ''
  dialogVisible.value = true
}

function openEdit(row: AccessRole): void {
  editingId.value = row.id
  Object.assign(form, {
    code: row.code,
    name: row.name,
    description: row.description ?? '',
    parentId: row.parentId ?? '',
    enabled: row.enabled,
  })
  formError.value = ''
  dialogVisible.value = true
}

function buildPayload(): AccessRoleRequest {
  const description = form.description.trim()
  const enabled = form.enabled
  if (editingId.value) {
    return {
      name: form.name.trim(),
      description,
      // An empty parent id clears the parent on update; a non-empty one is sent verbatim.
      parentId: form.parentId,
      enabled,
    }
  }
  const payload: AccessRoleRequest = {
    code: form.code.trim(),
    name: form.name.trim(),
    description,
    enabled,
  }
  if (form.parentId) {
    payload.parentId = form.parentId
  }
  return payload
}

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  formError.value = ''
  try {
    if (editingId.value) {
      await updateRole(editingId.value, buildPayload())
      ElMessage.success('角色已更新')
    } else {
      await createRole(buildPayload())
      ElMessage.success('角色已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    formError.value = normalizeAccessControlError(error, editingId.value ? '更新角色失败' : '创建角色失败').message
  } finally {
    saving.value = false
  }
}

// ---------- Enabled toggle ---------------------------------------------------

/** The built-in administrator role cannot be disabled (backend enforced). */
function toggleDisabled(row: AccessRole): boolean {
  return row.builtIn && row.code === 'admin'
}

async function toggleEnabled(row: AccessRole, value: string | number | boolean): Promise<void> {
  const enabled = value === true
  savingToggleId.value = row.id
  try {
    await updateRole(row.id, { enabled })
    row.enabled = enabled
    ElMessage.success(enabled ? '角色已启用' : '角色已停用')
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '更新角色状态失败').message)
    await load()
  } finally {
    savingToggleId.value = null
  }
}

function parentName(parentId: string | null): string {
  if (!parentId) {
    return '—'
  }
  return roles.value.find((role) => role.id === parentId)?.name ?? parentId
}
</script>

<template>
  <div class="roles">
    <div class="roles__bar">
      <span class="ac-text-meta">共 {{ roles.length }} 个角色</span>
      <div class="ac-action-bar">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" :disabled="!canWrite" @click="openCreate">
          新增角色
        </el-button>
      </div>
    </div>

    <div class="ac-table-wrapper">
      <el-table v-loading="loading" :data="roles" row-key="id" stripe>
        <el-table-column label="编码" min-width="160">
          <template #default="{ row }">
            <code class="roles__code">{{ row.code }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="描述" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="父角色" min-width="140">
          <template #default="{ row }">{{ parentName(row.parentId) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.builtIn" size="small" effect="plain" type="info">内置</el-tag>
            <el-tag v-else size="small" effect="plain">自定义</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90">
          <template #default="{ row }">
            <el-tooltip
              :disabled="!toggleDisabled(row)"
              content="内置管理员角色不能停用"
              placement="top"
            >
              <span>
                <el-switch
                  :model-value="row.enabled"
                  :disabled="!canWrite || toggleDisabled(row)"
                  :loading="savingToggleId === row.id"
                  @change="(value: string | number | boolean) => toggleEnabled(row, value)"
                />
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              text
              :icon="Edit"
              :disabled="!canWrite"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="ac-empty-state">
            <span class="ac-empty-state__title">暂无角色</span>
            <span class="ac-empty-state__text">点击「新增角色」创建第一个角色。</span>
          </div>
        </template>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogVisible"
      class="ac-dialog"
      :title="dialogTitle"
      width="min(600px, calc(100vw - 32px))"
      :close-on-click-modal="false"
    >
      <el-alert
        v-if="formError"
        class="roles__error"
        type="error"
        :closable="false"
        show-icon
        :title="formError"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="编码" prop="code">
          <el-input
            v-model="form.code"
            :disabled="editingId !== null"
            placeholder="例如 teacher"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="角色名称" maxlength="100" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="512"
            show-word-limit
            placeholder="角色职责说明（可选）"
          />
        </el-form-item>
        <el-form-item label="父角色">
          <el-select v-model="form.parentId" class="roles__control" placeholder="无（顶层角色）" clearable>
            <el-option label="无（顶层角色）" value="" />
            <el-option
              v-for="option in parentOptions"
              :key="option.id"
              :label="option.name"
              :value="option.id"
              :disabled="!option.enabled"
            >
              <span>{{ option.name }}</span>
              <span v-if="!option.enabled" class="roles__option-disabled">（已停用）</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="roles__footer">
          <span class="roles__spacer" />
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.roles__bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ac-space-8) var(--ac-space-16);
}

.roles__code {
  color: var(--ac-text);
  font-size: 13px;
}

.roles__error {
  margin-bottom: var(--ac-space-16);
}

.roles__control {
  width: 100%;
}

.roles__option-disabled {
  margin-left: var(--ac-space-8);
  color: var(--ac-placeholder);
  font-size: var(--ac-text-meta);
}

.roles__footer {
  display: flex;
  align-items: center;
  gap: var(--ac-space-8);
}

.roles__spacer {
  flex: 1;
}
</style>
