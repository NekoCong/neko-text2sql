<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Connection, Delete, Edit, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createDatasource,
  datasourceErrorMessage,
  deleteDatasource,
  listDatasources,
  testCandidateDatasource,
  testDatasource,
  updateDatasource,
} from '@/api/datasources'
import type { ConnectionTestResult, DatasourceDetail, DatasourcePayload } from '@/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
/** Read access is route-gated; every credential-bearing/write control also requires this key. */
const canWrite = computed(() => auth.can('datasource.write'))

const DIALECTS = ['H2', 'MySQL', 'PostgreSQL', 'SQLite', 'OTHER']

type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

const DIALECT_TAG_TYPES: Record<string, TagType> = {
  H2: 'success',
  MYSQL: 'primary',
  POSTGRESQL: 'warning',
  SQLITE: 'info',
  OTHER: 'danger',
}

/** Maps a dialect to an el-tag type, falling back to a neutral info tag. */
function dialectTagType(dialect: string): TagType {
  return DIALECT_TAG_TYPES[dialect.toUpperCase()] ?? 'info'
}

/** A pristine create form. */
function emptyForm(): DatasourcePayload {
  return {
    id: '',
    name: '',
    driver: '',
    url: '',
    username: '',
    password: '',
    dialect: 'H2',
    seed: false,
    maxPoolSize: 5,
  }
}

const datasources = ref<DatasourceDetail[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const dialogTesting = ref(false)
const editingId = ref<string | null>(null)
const testingId = ref<string | null>(null)
const deletingId = ref<string | null>(null)
const formRef = ref<FormInstance>()

const form = reactive<DatasourcePayload>(emptyForm())

const rules: FormRules<DatasourcePayload> = {
  id: [
    { required: true, message: '请输入数据源 ID', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_-]+$/,
      message: '仅允许字母、数字、中划线与下划线',
      trigger: 'blur',
    },
  ],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  driver: [{ required: true, message: '请输入 JDBC 驱动类', trigger: 'blur' }],
  url: [{ required: true, message: '请输入 JDBC URL', trigger: 'blur' }],
  dialect: [{ required: true, message: '请选择 SQL 方言', trigger: 'change' }],
}

const dialogTitle = computed(() => (editingId.value ? '编辑数据源' : '新增数据源'))

/** Loads the datasource list and refreshes the table. */
async function load(): Promise<void> {
  loading.value = true
  try {
    datasources.value = await listDatasources()
  } catch (error) {
    ElMessage.error(datasourceErrorMessage(error, '加载数据源失败'))
  } finally {
    loading.value = false
  }
}

/** Opens the dialog in create mode. */
function openCreate(): void {
  editingId.value = null
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

/** Opens the dialog prefilled with an existing datasource. */
function openEdit(row: DatasourceDetail): void {
  editingId.value = row.id
  Object.assign(form, {
    id: row.id,
    name: row.name,
    driver: row.driver ?? '',
    url: row.url,
    username: row.username ?? '',
    password: '',
    dialect: row.dialect,
    seed: false,
    maxPoolSize: 5,
  })
  dialogVisible.value = true
}

/** Saves the form: creates a new datasource or updates the one being edited. */
async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateDatasource(editingId.value, { ...form })
      ElMessage.success('数据源已更新')
    } else {
      await createDatasource({ ...form })
      ElMessage.success('数据源已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(datasourceErrorMessage(error, editingId.value ? '更新数据源失败' : '创建数据源失败'))
  } finally {
    saving.value = false
  }
}

/** Tests the dialog form without saving it. */
async function testCurrent(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  dialogTesting.value = true
  try {
    const result = await testCandidateDatasource({ ...form })
    showTestResult(form.name || form.id, result)
  } catch (error) {
    ElMessage.error(datasourceErrorMessage(error, '测试连接失败'))
  } finally {
    dialogTesting.value = false
  }
}

/** Tests the connection of an already registered datasource. */
async function testRow(row: DatasourceDetail): Promise<void> {
  testingId.value = row.id
  try {
    const result = await testDatasource(row.id)
    showTestResult(row.name, result)
  } catch (error) {
    ElMessage.error(datasourceErrorMessage(error, '测试连接失败'))
  } finally {
    testingId.value = null
  }
}

/** Deletes a datasource, surfacing 409 conflicts (e.g. the last datasource) gracefully. */
async function remove(row: DatasourceDetail): Promise<void> {
  deletingId.value = row.id
  try {
    await deleteDatasource(row.id)
    ElMessage.success('数据源已删除')
    await load()
  } catch (error) {
    ElMessage.error(datasourceErrorMessage(error, '删除数据源失败'))
  } finally {
    deletingId.value = null
  }
}

/** Renders a connection test outcome, including the elapsed milliseconds. */
function showTestResult(label: string, result: ConnectionTestResult): void {
  const elapsed = `耗时 ${result.elapsedMs} ms`
  const detail = result.message.trim()
  if (result.ok) {
    ElMessage.success(`${label} 连接成功（${elapsed}）${detail ? `：${detail}` : ''}`)
    return
  }
  ElMessage.error(`${label} 连接失败（${elapsed}）：${detail || '未知错误'}`)
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="ac-page datasources">
    <header class="ac-page-header">
      <div>
        <h1 class="ac-page-title">数据源</h1>
        <p class="ac-page-description">
          查看可供智能体查询的业务数据库；具备维护权限的账号可测试连接、编辑配置或移除数据源。
        </p>
      </div>
      <div class="ac-action-bar">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">
          新增数据源
        </el-button>
      </div>
    </header>

    <div class="ac-table-wrapper">
      <el-table v-loading="loading" :data="datasources" row-key="id" stripe>
        <el-table-column prop="id" label="ID" min-width="120" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="方言" width="130">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="dialectTagType(row.dialect)">
              {{ row.dialect }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="url" label="JDBC URL" min-width="280" show-overflow-tooltip />
        <el-table-column label="默认" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.default" size="small" type="success">默认</el-tag>
            <span v-else class="ac-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button
              size="small"
              text
              :icon="Connection"
              :loading="testingId === row.id"
              @click="testRow(row)"
            >
              测试连接
            </el-button>
            <el-popconfirm
              :title="`确定删除数据源「${row.name}」？`"
              confirm-button-text="删除"
              cancel-button-text="取消"
              confirm-button-type="danger"
              @confirm="remove(row)"
            >
              <template #reference>
                <el-button
                  size="small"
                  type="danger"
                  text
                  :icon="Delete"
                  :loading="deletingId === row.id"
                >
                  删除
                </el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>

        <template #empty>
          <div class="ac-empty-state">
            <span class="ac-empty-state__title">暂无数据源</span>
            <span class="ac-empty-state__text">
              {{
                canWrite
                  ? '新增数据源后，具备权限的账号即可向该数据库提问。'
                  : '暂无已登记的数据源；如需新增，请联系具备维护权限的管理员。'
              }}
            </span>
          </div>
        </template>
      </el-table>
    </div>

    <el-dialog
      v-if="canWrite"
      v-model="dialogVisible"
      class="ac-dialog"
      :title="dialogTitle"
      width="min(560px, calc(100vw - 32px))"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="104px">
        <el-form-item label="ID" prop="id">
          <el-input
            v-model="form.id"
            :disabled="editingId !== null"
            placeholder="唯一标识，例如 demo"
          />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="展示名称，例如 本地 H2" />
        </el-form-item>
        <el-form-item label="驱动类" prop="driver">
          <el-input v-model="form.driver" placeholder="例如 org.h2.Driver" />
        </el-form-item>
        <el-form-item label="JDBC URL" prop="url">
          <el-input v-model="form.url" placeholder="例如 jdbc:h2:mem:demo" />
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="可留空" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="可留空" />
        </el-form-item>
        <el-form-item label="方言" prop="dialect">
          <el-select v-model="form.dialect" class="datasources__control" placeholder="选择 SQL 方言">
            <el-option v-for="dialect in DIALECTS" :key="dialect" :label="dialect" :value="dialect" />
          </el-select>
        </el-form-item>
        <el-form-item label="初始化数据" prop="seed">
          <el-switch v-model="form.seed" />
          <span class="datasources__hint">注册时执行内置 schema / seed 脚本</span>
        </el-form-item>
        <el-form-item label="连接池上限" prop="maxPoolSize">
          <el-input-number v-model="form.maxPoolSize" :min="1" :max="100" />
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="datasources__footer">
          <el-button :icon="Connection" :loading="dialogTesting" @click="testCurrent">
            测试连接
          </el-button>
          <span class="datasources__spacer" />
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
/* The shell gutter is the only page inset; no local padding here. */
.datasources__control {
  width: 100%;
}

.datasources__hint {
  margin-left: var(--ac-space-12);
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
  line-height: var(--ac-line-height-control);
}

.datasources__footer {
  display: flex;
  align-items: center;
  gap: var(--ac-space-8);
}

.datasources__spacer {
  flex: 1;
}
</style>
