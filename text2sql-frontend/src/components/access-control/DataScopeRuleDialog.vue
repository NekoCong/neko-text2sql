<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getDatasourceSchema, normalizeAccessControlError, upsertDataScope } from '@/api/accessControl'
import type {
  AccessRole,
  DataScope,
  DataScopeRequest,
  DatasourceDetail,
  DatasourceSchema,
} from '@/types'

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

/** SQL type names that hold text / numeric values (mirrors the backend's normalization). */
const TEXT_TYPES = new Set([
  'CHAR', 'VARCHAR', 'VARCHAR2', 'NCHAR', 'NVARCHAR', 'NVARCHAR2', 'TEXT', 'CLOB',
  'MEDIUMTEXT', 'LONGTEXT', 'TINYTEXT', 'STRING',
])
const NUMERIC_TYPES = new Set([
  'TINYINT', 'SMALLINT', 'INT', 'INTEGER', 'BIGINT', 'DECIMAL', 'NUMERIC', 'FLOAT',
  'DOUBLE', 'REAL', 'DOUBLE PRECISION', 'SMALLSERIAL', 'SERIAL', 'BIGSERIAL', 'MONEY',
])

const props = defineProps<{
  modelValue: boolean
  roles: AccessRole[]
  datasources: DatasourceDetail[]
  /** The rule to edit, or `null` to create a new one. */
  rule: DataScope | null
  defaultRoleId: string
  defaultDatasourceId: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

interface DataScopeFormState {
  roleId: string
  datasourceId: string
  tableName: string
  policy: Policy
  columnName: string
  principalAttribute: string
}

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
const selectedTable = computed(() => tables.value.find((table) => table.name === form.tableName))
const columns = computed(() => selectedTable.value?.columns ?? [])

/** Re-initializes the form and loads the schema each time the dialog opens. */
watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    if (props.rule) {
      Object.assign(form, {
        roleId: props.rule.roleId,
        datasourceId: props.rule.datasourceId,
        tableName: props.rule.tableName,
        policy: props.rule.policy as Policy,
        columnName: props.rule.columnName ?? '',
        principalAttribute: props.rule.principalAttribute ?? '',
      })
    } else {
      Object.assign(form, {
        roleId: props.defaultRoleId,
        datasourceId: props.defaultDatasourceId,
        tableName: '',
        policy: 'ALL' as Policy,
        columnName: '',
        principalAttribute: '',
      })
    }
    formError.value = ''
    schema.value = null
    if (form.datasourceId) {
      void loadSchema(form.datasourceId)
    }
  },
)

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
  if (attribute.kind === 'multi' && kind !== 'numeric') {
    return 'classIds 是数值 ID 集合，请选择数值类型字段'
  }
  if (attribute.kind === 'numeric' && kind !== 'numeric') {
    return 'studentId 按数值比较，请选择数值类型字段'
  }
  if (attribute.kind === 'text' && kind !== 'text') {
    return '该主体属性按文本比较，请选择文本类型字段'
  }
  return null
}

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
    visible.value = false
    emit('saved')
  } catch (error) {
    formError.value = normalizeAccessControlError(error, '保存数据范围失败').message
  } finally {
    saving.value = false
  }
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
  <el-dialog
    v-model="visible"
    class="ac-dialog"
    title="数据范围规则"
    width="min(640px, calc(100vw - 32px))"
    :close-on-click-modal="false"
  >
    <el-alert
      v-if="formError"
      class="scope-dialog__error"
      type="error"
      :closable="false"
      show-icon
      :title="formError"
    />

    <el-form label-width="96px">
      <el-form-item label="角色" required>
        <el-select v-model="form.roleId" class="scope-dialog__control" placeholder="选择角色" filterable>
          <el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="数据源" required>
        <el-select
          v-model="form.datasourceId"
          class="scope-dialog__control"
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
          class="scope-dialog__control"
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
            class="scope-dialog__control"
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
            class="scope-dialog__control"
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
        <p v-if="blockedReason" class="scope-dialog__blocked">
          {{ blockedReason }}
        </p>
      </template>
    </el-form>

    <template #footer>
      <div class="scope-dialog__footer">
        <span class="scope-dialog__spacer" />
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canSubmit" @click="submit">
          保存
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.scope-dialog__error {
  margin-bottom: var(--ac-space-16);
}

.scope-dialog__control {
  width: 100%;
}

.scope-dialog__blocked {
  margin: 0 0 0 var(--ac-space-8);
  color: var(--el-color-danger);
  font-size: var(--ac-text-meta);
}

.scope-dialog__footer {
  display: flex;
  align-items: center;
  gap: var(--ac-space-8);
}

.scope-dialog__spacer {
  flex: 1;
}
</style>
