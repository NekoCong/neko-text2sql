<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Delete, Edit, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listDatasources } from '@/api/datasources'
import {
  deleteDataScope,
  getDatasourceSchema,
  listDataScopes,
  listRoles,
  normalizeAccessControlError,
  upsertDataScope,
} from '@/api/accessControl'
import type {
  AccessRole,
  DataScope,
  DataScopeRequest,
  DatasourceDetail,
  DatasourceSchema,
} from '@/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const canWrite = computed(() => auth.can('access.manage'))

type Policy = 'ALL' | 'FILTER' | 'DENY'

/** The closed principal-attribute catalog, with the pairing kind used to validate a column. */
interface PrincipalAttributeOption {
  code: string
  label: string
  kind: 'numeric' | 'text' | 'multi'
}

const PRINCIPAL_ATTRIBUTES: PrincipalAttributeOption[] = [
  { code: 'userId', label: '用户 ID（userId）', kind: 'text' },
  { code: 'username', label: '用户名（username）', kind: 'text' },
  { code: 'studentId', label: '学号 ID（studentId，数值）', kind: 'numeric' },
  { code: 'studentNo', label: '学号（studentNo）', kind: 'text' },
  { code: 'classIds', label: '班级 ID 集合（classIds）', kind: 'multi' },
]

/** SQL type names that hold text (mirrors the backend's normalization). */
const TEXT_TYPES = new Set([
  'CHAR', 'VARCHAR', 'VARCHAR2', 'NCHAR', 'NVARCHAR', 'NVARCHAR2', 'TEXT', 'CLOB',
  'MEDIUMTEXT', 'LONGTEXT', 'TINYTEXT', 'STRING',
])
const NUMERIC_TYPES = new Set([
  'TINYINT', 'SMALLINT', 'INT', 'INTEGER', 'BIGINT', 'DECIMAL', 'NUMERIC', 'FLOAT',
  'DOUBLE', 'REAL', 'DOUBLE PRECISION', 'SMALLSERIAL', 'SERIAL', 'BIGSERIAL', 'MONEY',
])

const policyLabels: Record<Policy, string> = { ALL: '全部', FILTER: '过滤', DENY: '拒绝' }
const policyTagTypes: Record<Policy, 'success' | 'primary' | 'danger'> = {
  ALL: 'success',
  FILTER: 'primary',
  DENY: 'danger',
}

const loading = ref(false)
const roles = ref<AccessRole[]>([])
const datasources = ref<DatasourceDetail[]>([])
const rules = ref<DataScope[]>([])

const filterRoleId = ref('')
const filterDatasourceId = ref('')

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
    datasources.value = await listDatasources()
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

// ---------- Upsert dialog ---------------------------------------------------

interface DataScopeFormState {
  roleId: string
  datasourceId: string
  tableName: string
  policy: Policy
  columnName: string
  principalAttribute: string
}

const dialogVisible = ref(false)
const saving = ref(false)
const formError = ref('')
const schema = ref<DatasourceSchema | null>(null)
const schemaLoading = ref(false)
const form = reactive<DataScopeFormState>({
  roleId: '',
  datasourceId: '',
  tableName: '',
  policy: 'ALL',
  columnName: '',
  principalAttribute: '',
})

const tables = computed(() => schema.value?.tables ?? [])
const selectedTable = computed(() =>
  tables.value.find((table) => table.name === form.tableName),
)
const columns = computed(() => selectedTable.value?.columns ?? [])

function openCreate(): void {
  Object.assign(form, {
    roleId: filterRoleId.value || roles.value[0]?.id || '',
    datasourceId: filterDatasourceId.value || datasources.value[0]?.id || '',
    tableName: '',
    policy: 'ALL' as Policy,
    columnName: '',
    principalAttribute: '',
  })
  formError.value = ''
  schema.value = null
  dialogVisible.value = true
  if (form.datasourceId) {
    void loadSchema(form.datasourceId)
  }
}

function openEdit(row: DataScope): void {
  Object.assign(form, {
    roleId: row.roleId,
    datasourceId: row.datasourceId,
    tableName: row.tableName,
    policy: row.policy as Policy,
    columnName: row.columnName ?? '',
    principalAttribute: row.principalAttribute ?? '',
  })
  formError.value = ''
  schema.value = null
  dialogVisible.value = true
  void loadSchema(row.datasourceId)
}

async function loadSchema(datasourceId: string): Promise<void> {
  schemaLoading.value = true
  schema.value = null
  try {
    schema.value = await getDatasourceSchema(datasourceId)
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '加载数据源结构失败').message)
  } finally {
    schemaLoading.value = false
  }
}

function onDatasourceChange(datasourceId: string): void {
  form.tableName = ''
  form.columnName = ''
  schema.value = null
  if (datasourceId) {
    void loadSchema(datasourceId)
  }
}

function onTableChange(): void {
  form.columnName = ''
}

/** Rejects a FILTER pairing that the backend (or the type rule) would refuse. */
function incompatibleReason(): string | null {
  if (form.policy !== 'FILTER' || !form.columnName || !form.principalAttribute) {
    return null
  }
  const attribute = PRINCIPAL_ATTRIBUTES.find((entry) => entry.code === form.principalAttribute)
  const column = columns.value.find((entry) => entry.name === form.columnName)
  if (!attribute || !column) {
    return null
  }
  const kind = columnKind(column.typeName)
  if (attribute.kind === 'numeric' && kind === 'text') {
    return 'studentId 按数值比较，请选择数值类型字段'
  }
  if (attribute.kind === 'text' && kind === 'numeric') {
    return '该主体属性按文本比较，请选择文本类型字段'
  }
  return null
}

/** Save is blocked while the FILTER combination is incomplete or type-incompatible. */
const blockedReason = computed(() => {
  if (form.policy !== 'FILTER') {
    return null
  }
  if (!form.columnName || !form.principalAttribute) {
    return 'FILTER 策略必须同时选择过滤字段与主体属性'
  }
  return incompatibleReason()
})

const canSubmit = computed(() => {
  if (!form.roleId || !form.datasourceId || !form.tableName || !form.policy) {
    return false
  }
  return blockedReason.value == null
})

async function submit(): Promise<void> {
  const block = blockedReason.value
  if (block) {
    formError.value = block
    return
  }
  if (!form.roleId || !form.datasourceId || !form.tableName) {
    formError.value = '请选择角色、数据源与表'
    return
  }
  saving.value = true
  formError.value = ''
  const request: DataScopeRequest = {
    roleId: form.roleId,
    datasourceId: form.datasourceId,
    tableName: form.tableName,
    policy: form.policy,
  }
  if (form.policy === 'FILTER') {
    request.columnName = form.columnName
    request.principalAttribute = form.principalAttribute
  }
  try {
    await upsertDataScope(request)
    ElMessage.success('数据范围规则已保存')
    dialogVisible.value = false
    await loadRules()
  } catch (error) {
    formError.value = normalizeAccessControlError(error, '保存数据范围失败').message
  } finally {
    saving.value = false
  }
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

/** Normalizes a type name and classifies it as text, numeric, or other. */
function columnKind(typeName: string): 'text' | 'numeric' | 'other' {
  const normalized = normalizeType(typeName)
  if (TEXT_TYPES.has(normalized)) {
    return 'text'
  }
  if (NUMERIC_TYPES.has(normalized)) {
    return 'numeric'
  }
  return 'other'
}

function normalizeType(typeName: string): string {
  const upper = typeName.trim().toUpperCase()
  const paren = upper.indexOf('(')
  const base = paren > 0 ? upper.substring(0, paren).trim() : upper
  if (base === 'CHARACTER VARYING' || base === 'CHAR VARYING') {
    return 'VARCHAR'
  }
  if (base === 'CHARACTER') {
    return 'CHAR'
  }
  if (base === 'NATIONAL CHARACTER VARYING') {
    return 'NVARCHAR'
  }
  if (base === 'CHARACTER LARGE OBJECT') {
    return 'CLOB'
  }
  return base
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

    <el-dialog
      v-model="dialogVisible"
      class="ac-dialog"
      title="数据范围规则"
      width="min(640px, calc(100vw - 32px))"
      :close-on-click-modal="false"
    >
      <el-alert
        v-if="formError"
        class="scope__error"
        type="error"
        :closable="false"
        show-icon
        :title="formError"
      />

      <el-form label-width="96px">
        <el-form-item label="角色" required>
          <el-select v-model="form.roleId" class="scope__control" placeholder="选择角色" filterable>
            <el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据源" required>
          <el-select
            v-model="form.datasourceId"
            class="scope__control"
            placeholder="选择数据源"
            filterable
            @change="onDatasourceChange"
          >
            <el-option
              v-for="datasource in datasources"
              :key="datasource.id"
              :label="datasource.name"
              :value="datasource.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="表" required>
          <el-select
            v-model="form.tableName"
            class="scope__control"
            placeholder="选择表"
            filterable
            :loading="schemaLoading"
            :disabled="!form.datasourceId"
            @change="onTableChange"
          >
            <el-option v-for="table in tables" :key="table.name" :label="table.name" :value="table.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="策略" required>
          <el-radio-group v-model="form.policy">
            <el-radio-button value="ALL">全部</el-radio-button>
            <el-radio-button value="FILTER">过滤</el-radio-button>
            <el-radio-button value="DENY">拒绝</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <template v-if="form.policy === 'FILTER'">
          <el-form-item label="过滤字段" required>
            <el-select
              v-model="form.columnName"
              class="scope__control"
              placeholder="选择该表的字段"
              filterable
              :disabled="!form.tableName"
            >
              <el-option
                v-for="column in columns"
                :key="column.name"
                :label="`${column.name}（${column.typeName}）`"
                :value="column.name"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="主体属性" required>
            <el-select
              v-model="form.principalAttribute"
              class="scope__control"
              placeholder="选择主体属性"
            >
              <el-option
                v-for="attribute in PRINCIPAL_ATTRIBUTES"
                :key="attribute.code"
                :label="attribute.label"
                :value="attribute.code"
              />
            </el-select>
          </el-form-item>
          <p v-if="blockedReason" class="scope__blocked">
            {{ blockedReason }}
          </p>
        </template>
      </el-form>

      <template #footer>
        <div class="scope__footer">
          <span class="scope__spacer" />
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" :disabled="!canSubmit" @click="submit">
            保存
          </el-button>
        </div>
      </template>
    </el-dialog>
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

.scope__error {
  margin-bottom: var(--ac-space-16);
}

.scope__control {
  width: 100%;
}

.scope__blocked {
  margin: 0 0 0 var(--ac-space-8);
  color: var(--el-color-danger);
  font-size: var(--ac-text-meta);
}

.scope__footer {
  display: flex;
  align-items: center;
  gap: var(--ac-space-8);
}

.scope__spacer {
  flex: 1;
}
</style>
