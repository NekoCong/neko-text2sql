<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getRolePermissions,
  listPermissions,
  listRoles,
  normalizeAccessControlError,
  replaceRolePermissions,
} from '@/api/accessControl'
import type { AccessPermission, AccessRole } from '@/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const canWrite = computed(() => auth.can('access.manage'))

const loading = ref(false)
const saving = ref(false)
const roles = ref<AccessRole[]>([])
const catalog = ref<AccessPermission[]>([])

const selectedRoleId = ref('')
const originalKeys = ref<string[]>([])
const selectedKeys = ref<string[]>([])

/** The two permission keys the built-in administrator must keep. */
const ADMIN_LOCKED_KEYS = ['access.manage', 'access.bypass_data_scope']

const moduleLabels: Record<string, string> = {
  chat: '对话',
  datasource: '数据源',
  model: '模型',
  access: '权限管理',
  user: '用户',
  audit: '审计',
}

/** Built-in roles first, then custom roles, each in code order (the API already sorts by code). */
const builtInRoles = computed(() => roles.value.filter((role) => role.builtIn))
const customRoles = computed(() => roles.value.filter((role) => !role.builtIn))

const selectedRole = computed(() => roles.value.find((role) => role.id === selectedRoleId.value))

/** Whether the selected role is the built-in administrator (its two keys are locked). */
const isBuiltInAdmin = computed(
  () => selectedRole.value?.builtIn === true && selectedRole.value.code === 'admin',
)

/** The catalog grouped by module, preserving catalog order within each module. */
const groupedCatalog = computed(() => {
  const groups: Array<{ module: string; permissions: AccessPermission[] }> = []
  const byModule = new Map<string, AccessPermission[]>()
  for (const permission of catalog.value) {
    const list = byModule.get(permission.module) ?? []
    list.push(permission)
    byModule.set(permission.module, list)
  }
  for (const [module, permissions] of byModule) {
    groups.push({ module, permissions })
  }
  return groups
})

const dirty = computed(() => {
  const a = [...selectedKeys.value].sort()
  const b = [...originalKeys.value].sort()
  return a.length !== b.length || a.some((key, index) => key !== b[index])
})

function moduleLabel(module: string): string {
  return moduleLabels[module] ?? module
}

/** Every key of a module's catalog. */
function moduleKeys(module: string): string[] {
  return catalog.value.filter((permission) => permission.module === module).map((permission) => permission.key)
}

function isModuleAllChecked(module: string): boolean {
  const keys = moduleKeys(module)
  return keys.length > 0 && keys.every((key) => selectedKeys.value.includes(key))
}

function isModuleIndeterminate(module: string): boolean {
  const keys = moduleKeys(module)
  const selected = keys.filter((key) => selectedKeys.value.includes(key)).length
  return selected > 0 && selected < keys.length
}

function toggleModule(module: string, checked: string | number | boolean): void {
  const keys = moduleKeys(module)
  const set = new Set(selectedKeys.value)
  if (checked === true) {
    keys.forEach((key) => set.add(key))
  } else {
    keys.forEach((key) => set.delete(key))
  }
  selectedKeys.value = [...set]
}

/** A locked key cannot be toggled off for the built-in administrator. */
function isKeyDisabled(key: string): boolean {
  return isBuiltInAdmin.value && ADMIN_LOCKED_KEYS.includes(key)
}

async function loadRoles(): Promise<void> {
  try {
    roles.value = await listRoles()
    if (!selectedRoleId.value && roles.value.length > 0) {
      selectedRoleId.value = roles.value[0].id
    }
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '加载角色失败').message)
  }
}

async function loadCatalog(): Promise<void> {
  try {
    catalog.value = await listPermissions()
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '加载权限目录失败').message)
  }
}

async function loadRolePermissions(): Promise<void> {
  if (!selectedRoleId.value) {
    originalKeys.value = []
    selectedKeys.value = []
    return
  }
  loading.value = true
  try {
    const result = await getRolePermissions(selectedRoleId.value)
    originalKeys.value = [...result.permissions]
    selectedKeys.value = [...result.permissions]
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '加载角色权限失败').message)
  } finally {
    loading.value = false
  }
}

function onRoleChange(): void {
  void loadRolePermissions()
}

async function save(): Promise<void> {
  if (!selectedRoleId.value) {
    return
  }
  saving.value = true
  try {
    await replaceRolePermissions(selectedRoleId.value, [...selectedKeys.value])
    ElMessage.success('权限已保存')
    // Reload the stored set so the UI reflects the exact state the backend persisted.
    await loadRolePermissions()
  } catch (error) {
    // A denied escalation (403) keeps the previous state and surfaces the backend reason.
    ElMessage.error(normalizeAccessControlError(error, '保存权限失败').message)
    await loadRolePermissions()
  } finally {
    saving.value = false
  }
}

function reset(): void {
  selectedKeys.value = [...originalKeys.value]
}

onMounted(async () => {
  await Promise.all([loadRoles(), loadCatalog()])
  await loadRolePermissions()
})
</script>

<template>
  <div class="permissions">
    <div class="permissions__bar">
      <div class="permissions__selector">
        <span class="ac-text-meta">角色</span>
        <el-select
          v-model="selectedRoleId"
          class="permissions__role"
          placeholder="选择角色"
          @change="onRoleChange"
        >
          <el-option-group v-if="builtInRoles.length" label="内置角色">
            <el-option
              v-for="role in builtInRoles"
              :key="role.id"
              :label="role.name"
              :value="role.id"
            />
          </el-option-group>
          <el-option-group v-if="customRoles.length" label="自定义角色">
            <el-option
              v-for="role in customRoles"
              :key="role.id"
              :label="role.name"
              :value="role.id"
            />
          </el-option-group>
        </el-select>
      </div>

      <div class="ac-action-bar">
        <el-button :disabled="!dirty" @click="reset">重置</el-button>
        <el-button
          type="primary"
          :disabled="!dirty || !canWrite"
          :loading="saving"
          @click="save"
        >
          保存
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="isBuiltInAdmin"
      class="permissions__hint"
      type="warning"
      :closable="false"
      show-icon
      title="内置管理员必须保留 access.manage 与 access.bypass_data_scope，无法移除。"
    />

    <p v-if="selectedRole" class="ac-text-meta">
      {{ selectedRole.name }}（{{ selectedRole.code }}）当前拥有
      {{ selectedKeys.length }} 项权限，已选择后点击「保存」整体替换。
    </p>

    <div v-loading="loading" class="permissions__groups">
      <section
        v-for="group in groupedCatalog"
        :key="group.module"
        class="permissions__group"
      >
        <header class="permissions__group-head">
          <el-checkbox
            :model-value="isModuleAllChecked(group.module)"
            :indeterminate="isModuleIndeterminate(group.module)"
            :disabled="!canWrite"
            @change="(value: string | number | boolean) => toggleModule(group.module, value)"
          >
            {{ moduleLabel(group.module) }}
          </el-checkbox>
        </header>

        <el-checkbox-group v-model="selectedKeys" class="permissions__list">
          <el-checkbox
            v-for="permission in group.permissions"
            :key="permission.key"
            class="permissions__item"
            :value="permission.key"
            :disabled="!canWrite || isKeyDisabled(permission.key)"
          >
            <span class="permissions__item-main">
              <code class="permissions__key">{{ permission.key }}</code>
              <span v-if="permission.description" class="permissions__desc">
                {{ permission.description }}
              </span>
            </span>
          </el-checkbox>
        </el-checkbox-group>
      </section>
    </div>

    <div v-if="!loading && catalog.length === 0" class="ac-empty-state ac-empty-state--bordered">
      <span class="ac-empty-state__title">暂无权限目录</span>
      <span class="ac-empty-state__text">权限目录由后端初始化，请联系管理员。</span>
    </div>
  </div>
</template>

<style scoped>
.permissions__bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ac-space-8) var(--ac-space-16);
}

.permissions__selector {
  display: inline-flex;
  align-items: center;
  gap: var(--ac-space-8);
}

.permissions__role {
  width: 220px;
  max-width: 100%;
}

.permissions__hint {
  margin-top: var(--ac-space-8);
}

.permissions__groups {
  display: flex;
  flex-direction: column;
  gap: var(--ac-space-16);
  min-height: 60px;
}

.permissions__group {
  padding: var(--ac-space-12) var(--ac-space-16);
  background: var(--ac-surface);
  border: 1px solid var(--ac-border-soft);
  border-radius: var(--ac-radius);
  box-shadow: var(--ac-shadow-sm);
}

.permissions__group-head {
  padding-bottom: var(--ac-space-8);
  border-bottom: 1px solid var(--ac-border-soft);
}

.permissions__list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--ac-space-4) var(--ac-space-16);
  padding-top: var(--ac-space-8);
}

.permissions__item {
  height: auto;
  margin-right: 0;
  align-items: flex-start;
}

.permissions__item-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  white-space: normal;
}

.permissions__key {
  color: var(--ac-text);
  font-size: 13px;
}

.permissions__desc {
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
  line-height: 1.4;
}
</style>
