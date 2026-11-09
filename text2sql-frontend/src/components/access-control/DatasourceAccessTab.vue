<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  getDatasourceMatrix,
  normalizeAccessControlError,
  replaceRoleDatasources,
} from '@/api/accessControl'
import type { DatasourceMatrix } from '@/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const canWrite = computed(() => auth.can('access.manage'))

const loading = ref(false)
const matrix = ref<DatasourceMatrix>({ roles: [], datasources: [] })
const savingRoleId = ref<string | null>(null)

/** Per-role drafts of the datasource grant set; only changed roles are saved. */
const drafts = reactive<Record<string, string[]>>({})

async function load(): Promise<void> {
  loading.value = true
  try {
    matrix.value = await getDatasourceMatrix()
    for (const role of matrix.value.roles) {
      drafts[role.id] = [...role.datasourceIds]
    }
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '加载数据源授权失败').message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function isBypass(roleId: string): boolean {
  const role = matrix.value.roles.find((entry) => entry.id === roleId)
  return role?.builtIn === true && role.code === 'admin'
}

function isChecked(roleId: string, datasourceId: string): boolean {
  return (drafts[roleId] ?? []).includes(datasourceId)
}

function toggle(roleId: string, datasourceId: string, value: string | number | boolean): void {
  const set = new Set(drafts[roleId] ?? [])
  if (value === true) {
    set.add(datasourceId)
  } else {
    set.delete(datasourceId)
  }
  drafts[roleId] = [...set]
}

function isDirty(roleId: string): boolean {
  const role = matrix.value.roles.find((entry) => entry.id === roleId)
  if (!role) {
    return false
  }
  const before = [...role.datasourceIds].sort()
  const after = [...(drafts[roleId] ?? [])].sort()
  return before.length !== after.length || before.some((id, index) => id !== after[index])
}

async function saveRole(roleId: string): Promise<void> {
  if (!isDirty(roleId)) {
    return
  }
  savingRoleId.value = roleId
  try {
    await replaceRoleDatasources(roleId, drafts[roleId] ?? [])
    const role = matrix.value.roles.find((entry) => entry.id === roleId)
    if (role) {
      role.datasourceIds = [...(drafts[roleId] ?? [])]
    }
    ElMessage.success('已保存，下一个请求立即生效')
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '保存数据源授权失败').message)
    await load()
  } finally {
    savingRoleId.value = null
  }
}
</script>

<template>
  <div class="matrix">
    <div class="matrix__bar">
      <span class="ac-text-meta">按角色勾选可访问的数据源，逐行保存</span>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert
      class="matrix__hint"
      type="info"
      :closable="false"
      show-icon
      title="数据源授权仅在角色拥有 datasource.read 时才有意义；内置管理员绕过数据源限制，可访问全部数据源。"
    />

    <div class="ac-table-wrapper">
      <el-table v-loading="loading" :data="matrix.roles" row-key="id">
        <el-table-column label="角色" min-width="180" fixed="left">
          <template #default="{ row }">
            <div class="matrix__role">
              <span class="matrix__role-name">{{ row.name }}</span>
              <div class="matrix__role-meta">
                <code class="matrix__code">{{ row.code }}</code>
                <el-tag v-if="isBypass(row.id)" size="small" type="warning" effect="plain">绕过</el-tag>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column
          v-for="datasource in matrix.datasources"
          :key="datasource.id"
          :label="datasource.name"
          min-width="120"
          align="center"
        >
          <template #default="{ row }">
            <el-checkbox
              :model-value="isChecked(row.id, datasource.id)"
              :disabled="!canWrite"
              @change="(value: string | number | boolean) => toggle(row.id, datasource.id, value)"
            />
          </template>
        </el-table-column>

        <el-table-column label="操作" width="120" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              :disabled="!canWrite || !isDirty(row.id)"
              :loading="savingRoleId === row.id"
              @click="saveRole(row.id)"
            >
              保存
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="ac-empty-state">
            <span class="ac-empty-state__title">暂无可授权数据源</span>
            <span class="ac-empty-state__text">
              先到「数据源」页面登记数据源，再回到这里为角色授权。
            </span>
          </div>
        </template>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.matrix__bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ac-space-8) var(--ac-space-16);
}

.matrix__hint {
  padding: var(--ac-space-12) var(--ac-space-16);
}

.matrix__role {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.matrix__role-name {
  color: var(--ac-ink);
  font-weight: 600;
}

.matrix__role-meta {
  display: flex;
  align-items: center;
  gap: var(--ac-space-8);
}

.matrix__code {
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
}
</style>
