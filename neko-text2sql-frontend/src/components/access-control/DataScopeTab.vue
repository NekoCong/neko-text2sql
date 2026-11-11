<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Delete, Edit, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  deleteDataScope,
  getDatasourceMatrix,
  listDataScopes,
  listRoles,
  normalizeAccessControlError,
} from '@/api/accessControl'
import type { AccessRole, DataScope, DatasourceMatrixDatasource } from '@/types'
import { useAuthStore } from '@/stores/auth'
import DataScopeRuleDialog from '@/components/access-control/DataScopeRuleDialog.vue'

const auth = useAuthStore()
const canWrite = computed(() => auth.can('access.manage'))

type Policy = 'ALL' | 'FILTER' | 'DENY'

const policyLabels: Record<Policy, string> = { ALL: '全部', FILTER: '过滤', DENY: '拒绝' }
const policyTagTypes: Record<Policy, 'success' | 'primary' | 'danger'> = {
  ALL: 'success',
  FILTER: 'primary',
  DENY: 'danger',
}

const loading = ref(false)
const roles = ref<AccessRole[]>([])
const datasources = ref<DatasourceMatrixDatasource[]>([])
const rules = ref<DataScope[]>([])

const filterRoleId = ref('')
const filterDatasourceId = ref<string | undefined>('')

const dialogVisible = ref(false)
const dialogRule = ref<DataScope | null>(null)

async function loadRoles(): Promise<void> {
  try {
    roles.value = await listRoles()
    if (!filterRoleId.value && roles.value.length > 0) {
      filterRoleId.value = roles.value[0].id
    }
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '加载角色失败').message)
  }
}

async function loadDatasources(): Promise<void> {
  try {
    // The matrix endpoint is gated by `access.manage` like the rest of this console, so an operator
    // without `datasource.read` can still configure data scopes.
    datasources.value = (await getDatasourceMatrix()).datasources
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '加载数据源失败').message)
  }
}

async function loadRules(): Promise<void> {
  if (!filterRoleId.value) {
    rules.value = []
    return
  }
  loading.value = true
  try {
    rules.value = await listDataScopes({
      roleId: filterRoleId.value,
      datasourceId: filterDatasourceId.value || undefined,
    })
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '加载数据范围失败').message)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadRoles(), loadDatasources()])
  await loadRules()
})

function openCreate(): void {
  dialogRule.value = null
  dialogVisible.value = true
}

function openEdit(row: DataScope): void {
  dialogRule.value = row
  dialogVisible.value = true
}

function onDialogSaved(): void {
  void loadRules()
}

async function remove(row: DataScope): Promise<void> {
  try {
    await deleteDataScope(row.id)
    ElMessage.success('规则已删除')
    await loadRules()
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '删除数据范围失败').message)
  }
}

function datasourceName(id: string): string {
  return datasources.value.find((item) => item.id === id)?.name ?? id
}
</script>

<template>
  <div class="scope">
    <div class="scope__filters">
      <div class="scope__field">
        <span class="ac-text-meta">角色</span>
        <el-select
          v-model="filterRoleId"
          class="scope__select"
          placeholder="选择角色"
          filterable
          @change="loadRules"
        >
          <el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id" />
        </el-select>
      </div>
      <div class="scope__field">
        <span class="ac-text-meta">数据源</span>
        <el-select
          v-model="filterDatasourceId"
          class="scope__select"
          placeholder="全部"
          clearable
          filterable
          @change="loadRules"
        >
          <el-option
            v-for="datasource in datasources"
            :key="datasource.id"
            :label="datasource.name"
            :value="datasource.id"
          />
        </el-select>
      </div>
      <div class="ac-action-bar scope__actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadRules">刷新</el-button>
        <el-button type="primary" :icon="Plus" :disabled="!canWrite" @click="openCreate">
          新增规则
        </el-button>
      </div>
    </div>

    <el-alert
      class="scope__hint"
      type="info"
      :closable="false"
      show-icon
      title="未配置规则的表现在默认拒绝访问；仅拥有 access.bypass_data_scope 的角色不受影响。"
    />

    <div class="ac-table-wrapper">
      <el-table v-loading="loading" :data="rules" row-key="id" stripe>
        <el-table-column prop="tableName" label="表" min-width="160" />
        <el-table-column label="数据源" min-width="140">
          <template #default="{ row }">{{ datasourceName(row.datasourceId) }}</template>
        </el-table-column>
        <el-table-column label="策略" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="policyTagTypes[row.policy as Policy]">
              {{ policyLabels[row.policy as Policy] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="过滤字段 / 主体属性" min-width="220">
          <template #default="{ row }">
            <span v-if="row.policy === 'FILTER'" class="scope__pair">
              <code class="scope__code">{{ row.columnName }}</code>
              <span class="ac-text-meta">←</span>
              <code class="scope__code">{{ row.principalAttribute }}</code>
            </span>
            <span v-else class="ac-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text :icon="Edit" :disabled="!canWrite" @click="openEdit(row)">
              编辑
            </el-button>
            <el-popconfirm
              :title="`删除表「${row.tableName}」的规则？`"
              confirm-button-text="删除"
              cancel-button-text="取消"
              confirm-button-type="danger"
              @confirm="remove(row)"
            >
              <template #reference>
                <el-button size="small" type="danger" text :icon="Delete" :disabled="!canWrite">
                  删除
                </el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>

        <template #empty>
          <div class="ac-empty-state">
            <span class="ac-empty-state__title">暂无数据范围规则</span>
            <span class="ac-empty-state__text">
              未配置规则的表现在默认拒绝访问，点击「新增规则」为表授予访问或过滤条件。
            </span>
          </div>
        </template>
      </el-table>
    </div>

    <DataScopeRuleDialog
      v-model="dialogVisible"
      :roles="roles"
      :datasources="datasources"
      :rule="dialogRule"
      :default-role-id="filterRoleId"
      :default-datasource-id="filterDatasourceId || (datasources[0]?.id ?? '')"
      @saved="onDialogSaved"
    />
  </div>
</template>

<style scoped>
.scope__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ac-space-12) var(--ac-space-24);
}

.scope__field {
  display: inline-flex;
  align-items: center;
  gap: var(--ac-space-8);
}

.scope__select {
  width: 200px;
  max-width: 100%;
}

.scope__actions {
  margin-left: auto;
}

.scope__hint {
  padding: var(--ac-space-12) var(--ac-space-16);
}

.scope__pair {
  display: inline-flex;
  align-items: center;
  gap: var(--ac-space-8);
}

.scope__code {
  color: var(--ac-text);
  font-size: 13px;
}
</style>
