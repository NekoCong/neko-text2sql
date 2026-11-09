<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh, Search, View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listAudit, normalizeAccessControlError } from '@/api/accessControl'
import type { AccessAudit, AccessAuditPage } from '@/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const canRead = computed(() => auth.can('audit.read'))

const loading = ref(false)
const page = ref<AccessAuditPage>({ items: [], total: 0, page: 0, size: 50 })

const actorFilter = ref('')
const actionFilter = ref('')
const entityTypeFilter = ref('')
const resultFilter = ref<string>('')
const timeRange = ref<[Date, Date] | null>(null)
const currentPage = ref(1)
const pageSize = ref(50)

async function load(): Promise<void> {
  if (!canRead.value) {
    return
  }
  loading.value = true
  try {
    page.value = await listAudit({
      actorId: actorFilter.value.trim() || undefined,
      action: actionFilter.value.trim() || undefined,
      entityType: entityTypeFilter.value.trim() || undefined,
      result: resultFilter.value === '' ? undefined : (resultFilter.value as 'SUCCESS' | 'DENIED'),
      from: timeRange.value ? timeRange.value[0].getTime() : undefined,
      to: timeRange.value ? timeRange.value[1].getTime() + 1 : undefined,
      page: currentPage.value - 1,
      size: pageSize.value,
    })
  } catch (error) {
    ElMessage.error(normalizeAccessControlError(error, '加载审计记录失败').message)
  } finally {
    loading.value = false
  }
}

function search(): void {
  currentPage.value = 1
  void load()
}

function resetFilters(): void {
  actorFilter.value = ''
  actionFilter.value = ''
  entityTypeFilter.value = ''
  resultFilter.value = ''
  timeRange.value = null
  search()
}

function onPageChange(value: number): void {
  currentPage.value = value
  void load()
}

function onSizeChange(value: number): void {
  pageSize.value = value
  currentPage.value = 1
  void load()
}

// ---------- Detail drawer ----------------------------------------------------

const drawerVisible = ref(false)
const activeAudit = ref<AccessAudit | null>(null)

function openDetail(row: AccessAudit): void {
  activeAudit.value = row
  drawerVisible.value = true
}

/** Renders a stored JSON payload for display; a malformed payload is echoed as its raw string. */
function jsonText(value: unknown): string {
  if (value == null) {
    return 'null'
  }
  if (typeof value === 'string') {
    return value
  }
  return JSON.stringify(value, null, 2)
}

function resultTagType(result: string): 'success' | 'danger' {
  return result === 'SUCCESS' ? 'success' : 'danger'
}

function resultLabel(result: string): string {
  return result === 'SUCCESS' ? '成功' : '拒绝'
}

function formatTime(value: number): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  if (canRead.value) {
    void load()
  }
})
</script>

<template>
  <div class="audit">
    <div v-if="!canRead" class="ac-empty-state ac-empty-state--bordered">
      <span class="ac-empty-state__title">没有查看审计记录的权限</span>
      <span class="ac-empty-state__text">需要 audit.read 权限才能查看访问控制审计，请联系管理员。</span>
    </div>

    <template v-else>
      <div class="audit__filters">
        <el-input
          v-model="actorFilter"
          class="audit__input"
          placeholder="操作者 ID"
          clearable
          @keyup.enter="search"
        />
        <el-input
          v-model="actionFilter"
          class="audit__input"
          placeholder="操作（如 role.create）"
          clearable
          @keyup.enter="search"
        />
        <el-input
          v-model="entityTypeFilter"
          class="audit__input"
          placeholder="实体类型"
          clearable
          @keyup.enter="search"
        />
        <el-select v-model="resultFilter" class="audit__result" placeholder="结果" clearable>
          <el-option label="成功" value="SUCCESS" />
          <el-option label="拒绝" value="DENIED" />
        </el-select>
        <el-date-picker
          v-model="timeRange"
          class="audit__range"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
        />
        <div class="ac-action-bar">
          <el-button type="primary" :icon="Search" :loading="loading" @click="search">查询</el-button>
          <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
        </div>
      </div>

      <div class="ac-table-wrapper">
        <el-table v-loading="loading" :data="page.items" row-key="id" stripe>
          <el-table-column label="时间" width="180">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="actorId" label="操作者" min-width="140" show-overflow-tooltip />
          <el-table-column label="操作" min-width="180">
            <template #default="{ row }">
              <code class="audit__code">{{ row.action }}</code>
            </template>
          </el-table-column>
          <el-table-column label="实体" min-width="160">
            <template #default="{ row }">
              <span class="audit__entity">
                <span>{{ row.entityType }}</span>
                <span v-if="row.entityId" class="ac-text-meta">{{ row.entityId }}</span>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="resultTagType(row.result)">
                {{ resultLabel(row.result) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="原因" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.reason || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text :icon="View" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>

          <template #empty>
            <div class="ac-empty-state">
              <span class="ac-empty-state__title">暂无审计记录</span>
              <span class="ac-empty-state__text">权限变更会在此留下记录，调整筛选条件再试。</span>
            </div>
          </template>
        </el-table>
      </div>

      <el-pagination
        class="audit__pagination"
        background
        layout="total, sizes, prev, pager, next"
        :total="page.total"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-sizes="[20, 50, 100]"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />

      <el-drawer
        v-model="drawerVisible"
        class="ac-drawer"
        title="审计详情"
        size="min(860px, 94vw)"
        append-to-body
      >
        <template v-if="activeAudit">
          <dl class="audit__meta">
            <div class="audit__meta-row">
              <dt>时间</dt>
              <dd>{{ formatTime(activeAudit.createdAt) }}</dd>
            </div>
            <div class="audit__meta-row">
              <dt>操作者</dt>
              <dd>{{ activeAudit.actorId }}</dd>
            </div>
            <div class="audit__meta-row">
              <dt>操作</dt>
              <dd><code class="audit__code">{{ activeAudit.action }}</code></dd>
            </div>
            <div class="audit__meta-row">
              <dt>实体</dt>
              <dd>{{ activeAudit.entityType }}{{ activeAudit.entityId ? ` · ${activeAudit.entityId}` : '' }}</dd>
            </div>
            <div class="audit__meta-row">
              <dt>结果</dt>
              <dd>
                <el-tag size="small" :type="resultTagType(activeAudit.result)">
                  {{ resultLabel(activeAudit.result) }}
                </el-tag>
              </dd>
            </div>
            <div v-if="activeAudit.reason" class="audit__meta-row">
              <dt>原因</dt>
              <dd>{{ activeAudit.reason }}</dd>
            </div>
          </dl>

          <div class="audit__diff">
            <div class="audit__pane">
              <h4 class="audit__pane-title">变更前</h4>
              <pre class="audit__json">{{ jsonText(activeAudit.before) }}</pre>
            </div>
            <div class="audit__pane">
              <h4 class="audit__pane-title">变更后</h4>
              <pre class="audit__json">{{ jsonText(activeAudit.after) }}</pre>
            </div>
          </div>
        </template>
      </el-drawer>
    </template>
  </div>
</template>

<style scoped>
.audit__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ac-space-8);
}

.audit__input {
  width: 180px;
  max-width: 100%;
}

.audit__result {
  width: 120px;
}

.audit__range {
  width: 320px;
  max-width: 100%;
}

.audit__code {
  color: var(--ac-text);
  font-size: 13px;
}

.audit__entity {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.audit__pagination {
  justify-content: flex-end;
  margin-top: var(--ac-space-12);
}

.audit__meta {
  display: flex;
  flex-direction: column;
  gap: var(--ac-space-8);
  margin: 0 0 var(--ac-space-16);
}

.audit__meta-row {
  display: flex;
  gap: var(--ac-space-12);
}

.audit__meta-row dt {
  flex: none;
  width: 72px;
  color: var(--ac-muted);
}

.audit__meta-row dd {
  margin: 0;
  color: var(--ac-text);
  overflow-wrap: anywhere;
}

.audit__diff {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--ac-space-16);
}

.audit__pane {
  min-width: 0;
}

.audit__pane-title {
  margin: 0 0 var(--ac-space-8);
  color: var(--ac-ink);
  font-size: var(--ac-text-body);
  font-weight: 600;
}

.audit__json {
  margin: 0;
  padding: var(--ac-space-12) var(--ac-space-16);
  overflow: auto;
  color: var(--ac-code-text);
  font-size: 12px;
  line-height: 1.5;
  background: var(--ac-code-surface);
  border-radius: var(--ac-radius-sm);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 767px) {
  .audit__diff {
    grid-template-columns: 1fr;
  }
}
</style>
