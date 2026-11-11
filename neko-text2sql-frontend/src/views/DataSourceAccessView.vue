<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  getAccessMatrix,
  grantAccess,
  revokeAccess,
  type DatasourceAccessMatrix,
  type DatasourceAccessRow,
} from '@/api/admin'

/** Human labels for the grantable backend roles. */
const roleLabels: Record<string, string> = {
  student: '学生',
  counselor: '辅导员',
}

const loading = ref(false)
const matrix = ref<DatasourceAccessMatrix>({ roles: [], datasources: [] })

/** Loads the current matrix. */
async function load(): Promise<void> {
  loading.value = true
  try {
    matrix.value = await getAccessMatrix()
  } catch {
    ElMessage.error('加载数据源权限失败')
  } finally {
    loading.value = false
  }
}

/**
 * Grants or revokes one role on one datasource and applies the returned matrix.
 *
 * @param row the datasource row
 * @param role the role being toggled
 * @param granted the new checked state
 */
async function toggle(
  row: DatasourceAccessRow,
  role: string,
  granted: boolean,
): Promise<void> {
  try {
    matrix.value = granted
      ? await grantAccess(role, row.id)
      : await revokeAccess(role, row.id)
    ElMessage.success('已保存，下一次请求立即生效')
  } catch {
    ElMessage.error('保存失败')
    await load()
  }
}

onMounted(load)
</script>

<template>
  <section class="ac-page access">
    <header class="ac-page-header">
      <div>
        <h1 class="ac-page-title">数据源权限</h1>
        <p class="ac-page-description">
          按角色分配可访问的数据源范围；管理员可访问全部数据源，不受下表限制。
        </p>
      </div>
      <div class="ac-action-bar">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <el-alert
      class="access__hint"
      type="info"
      :closable="false"
      show-icon
      title="角色只能使用被授权的数据源；管理员不受限制。修改保存后对下一个请求立即生效。"
    />

    <div class="ac-table-wrapper">
      <el-table v-loading="loading" :data="matrix.datasources">
        <el-table-column prop="name" label="数据源" min-width="180" />
        <el-table-column prop="id" label="ID" min-width="120" />
        <el-table-column label="默认" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.defaultDatasource" size="small" type="success">默认</el-tag>
            <span v-else class="ac-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="角色授权" min-width="220">
          <template #default="{ row }">
            <div class="access__roles">
              <el-checkbox
                v-for="role in matrix.roles"
                :key="role"
                class="access__role"
                :model-value="row.grantedRoles.includes(role)"
                @change="(value: string | number | boolean) => toggle(row, role, value === true)"
              >
                {{ roleLabels[role] ?? role }}
              </el-checkbox>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <div class="ac-empty-state">
            <span class="ac-empty-state__title">暂无可授权数据源</span>
            <span class="ac-empty-state__text">
              先到「数据源」页面登记数据源，再回到这里分配角色权限。
            </span>
          </div>
        </template>
      </el-table>
    </div>
  </section>
</template>

<style scoped>
/* Alert uses the shared info tint; only its box treatment is local. */
.access__hint {
  padding: var(--ac-space-12) var(--ac-space-16);
  border: 1px solid var(--el-color-info-light-8);
  border-radius: var(--ac-radius);
}

.access__hint :deep(.el-alert__title) {
  color: var(--ac-text);
  font-size: var(--ac-text-body);
  line-height: var(--ac-line-height);
}

.access__hint :deep(.el-alert__icon) {
  color: var(--el-color-info);
}

.access__roles {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ac-space-16);
  align-items: center;
}

.access__role {
  height: auto;
  margin-right: 0;
}

.access__role :deep(.el-checkbox__label) {
  color: var(--ac-text);
  font-size: var(--ac-text-body);
}
</style>
