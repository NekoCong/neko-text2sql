<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Connection, Delete, Edit, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createModelConfig,
  deleteModelConfig,
  listModelConfigs,
  modelConfigErrorMessage,
  testModelConfig,
  updateModelConfig,
} from '@/api/modelConfigs'
import type { ConnectionTestResult, ModelConfig, ModelConfigPayload } from '@/types'

const PROVIDERS = [
  { value: 'dashscope', label: 'DashScope（阿里云百炼）' },
  { value: 'openai', label: 'OpenAI 兼容（DeepSeek / Kimi / GLM / vLLM…）' },
]

type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

/** Maps a provider id to an el-tag type. */
function providerTagType(provider: string): TagType {
  return provider.toLowerCase().startsWith('openai') ? 'success' : 'primary'
}

/** A pristine create form. */
function emptyForm(): ModelConfigPayload {
  return { name: '', provider: 'dashscope', model: '', apiKey: '', baseUrl: '', isDefault: false }
}

const configs = ref<ModelConfig[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const testingId = ref<string | null>(null)
const deletingId = ref<string | null>(null)
const formRef = ref<FormInstance>()

const form = reactive<ModelConfigPayload>(emptyForm())

const rules: FormRules<ModelConfigPayload> = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  provider: [{ required: true, message: '请选择提供方', trigger: 'change' }],
  model: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
}

const dialogTitle = computed(() => (editingId.value !== null ? '编辑模型' : '新增模型'))

/** The stored key is never sent back, so editing shows a hint instead of a value. */
const apiKeyPlaceholder = computed(() => {
  if (editingId.value === null) {
    return '请输入 API Key（可留空，稍后补充）'
  }
  const editing = configs.value.find((item) => item.id === editingId.value)
  return editing?.hasApiKey ? '已保存（留空保持不变）' : '请输入 API Key'
})

/** Loads the model config list and refreshes the table. */
async function load(): Promise<void> {
  loading.value = true
  try {
    configs.value = await listModelConfigs()
  } catch (error) {
    ElMessage.error(modelConfigErrorMessage(error, '加载模型配置失败'))
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

/** Opens the dialog prefilled with an existing config (the API key stays blank). */
function openEdit(row: ModelConfig): void {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    provider: row.provider.toLowerCase().startsWith('openai') ? 'openai' : 'dashscope',
    model: row.model,
    apiKey: '',
    baseUrl: row.baseUrl ?? '',
    isDefault: row.isDefault,
  })
  dialogVisible.value = true
}

/** Builds the request payload; a blank apiKey is omitted so the stored key survives. */
function payload(): ModelConfigPayload {
  const result: ModelConfigPayload = {
    name: form.name.trim(),
    provider: form.provider,
    model: form.model.trim(),
    baseUrl: (form.baseUrl ?? '').trim(),
    isDefault: form.isDefault ?? false,
  }
  const apiKey = (form.apiKey ?? '').trim()
  if (apiKey) {
    result.apiKey = apiKey
  }
  return result
}

/** Saves the form: creates a new config or updates the one being edited. */
async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    if (editingId.value !== null) {
      await updateModelConfig(editingId.value, payload())
      ElMessage.success('模型配置已更新')
    } else {
      await createModelConfig(payload())
      ElMessage.success('模型配置已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(
      modelConfigErrorMessage(error, editingId.value !== null ? '更新模型配置失败' : '创建模型配置失败'),
    )
  } finally {
    saving.value = false
  }
}

/** Probes connectivity of an already stored config. */
async function testRow(row: ModelConfig): Promise<void> {
  testingId.value = row.id
  try {
    const result = await testModelConfig(row.id)
    showTestResult(row.name, result)
  } catch (error) {
    ElMessage.error(modelConfigErrorMessage(error, '测试连接失败'))
  } finally {
    testingId.value = null
  }
}

/** Deletes a config after the inline confirmation. */
async function remove(row: ModelConfig): Promise<void> {
  deletingId.value = row.id
  try {
    await deleteModelConfig(row.id)
    ElMessage.success('模型配置已删除')
    await load()
  } catch (error) {
    ElMessage.error(modelConfigErrorMessage(error, '删除模型配置失败'))
  } finally {
    deletingId.value = null
  }
}

/** Renders a connectivity test outcome, including the elapsed milliseconds. */
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
  <section class="ac-page models">
    <header class="ac-page-header">
      <div>
        <h1 class="ac-page-title">模型配置</h1>
        <p class="ac-page-description">
          维护对话可用的模型提供方、模型名称与 API Key，默认模型会被优先调用。
        </p>
      </div>
      <div class="ac-action-bar">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增模型</el-button>
      </div>
    </header>

    <div class="ac-table-wrapper">
      <el-table v-loading="loading" :data="configs" row-key="id" stripe>
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="提供方" width="130">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="providerTagType(row.provider)">
              {{ row.provider }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="model" label="模型" min-width="160" show-overflow-tooltip />
        <el-table-column label="Base URL" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.baseUrl">{{ row.baseUrl }}</span>
            <span v-else class="ac-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="API Key" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.hasApiKey" size="small" effect="plain" type="success">已保存</el-tag>
            <el-tag v-else size="small" effect="plain" type="info">未配置</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="默认" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault" size="small" type="success">默认</el-tag>
            <span v-else class="ac-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
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
              :title="`确定删除模型配置「${row.name}」？`"
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
            <span class="ac-empty-state__title">暂无模型配置</span>
            <span class="ac-empty-state__text">
              新增一个模型配置后，对话才能调用大模型生成查询。
            </span>
          </div>
        </template>
      </el-table>
    </div>

    <el-dialog
      v-model="dialogVisible"
      class="ac-dialog"
      :title="dialogTitle"
      width="min(560px, calc(100vw - 32px))"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="104px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="64" placeholder="展示名称，例如 通义千问" />
        </el-form-item>
        <el-form-item label="提供方" prop="provider">
          <el-select v-model="form.provider" class="models__control" placeholder="选择提供方">
            <el-option
              v-for="provider in PROVIDERS"
              :key="provider.value"
              :label="provider.label"
              :value="provider.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="模型" prop="model">
          <el-input
            v-model="form.model"
            maxlength="128"
            placeholder="例如 qwen-plus / deepseek-chat"
          />
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="apiKeyPlaceholder"
          />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="form.baseUrl" placeholder="兼容服务地址，可留空走默认" />
        </el-form-item>
        <el-form-item label="设为默认" prop="isDefault">
          <el-switch v-model="form.isDefault" />
          <span class="models__hint">对话将优先使用默认模型</span>
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="models__footer">
          <span class="models__spacer" />
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
/* The shell gutter is the only page inset; no local padding here. */
.models__control {
  width: 100%;
}

.models__hint {
  margin-left: var(--ac-space-12);
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
  line-height: var(--ac-line-height-control);
}

.models__footer {
  display: flex;
  align-items: center;
  gap: var(--ac-space-8);
}

.models__spacer {
  flex: 1;
}
</style>
