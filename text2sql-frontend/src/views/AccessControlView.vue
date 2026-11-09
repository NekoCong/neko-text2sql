<script setup lang="ts">
import { ref } from 'vue'
import RolesTab from '@/components/access-control/RolesTab.vue'
import PermissionsTab from '@/components/access-control/PermissionsTab.vue'
import DataScopeTab from '@/components/access-control/DataScopeTab.vue'
import UsersTab from '@/components/access-control/UsersTab.vue'
import DatasourceAccessTab from '@/components/access-control/DatasourceAccessTab.vue'
import AuditTab from '@/components/access-control/AuditTab.vue'

/**
 * The permission-management area: six tabs over the typed access-control client. The route is gated
 * by `access.manage`, so the page never renders for a caller without it; each tab is lazy-rendered
 * on first activation so a visit does not fire six parallel loads.
 */
const activeTab = ref('roles')
</script>

<template>
  <section class="ac-page ac-control">
    <header class="ac-page-header">
      <div>
        <h1 class="ac-page-title">权限管理</h1>
        <p class="ac-page-description">
          管理角色、权限、数据范围、用户与数据源授权，并查看权限变更审计记录。后端接口始终是权限边界。
        </p>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="ac-control__tabs">
      <el-tab-pane label="角色" name="roles" lazy>
        <RolesTab />
      </el-tab-pane>
      <el-tab-pane label="权限" name="permissions" lazy>
        <PermissionsTab />
      </el-tab-pane>
      <el-tab-pane label="数据范围" name="data-scope" lazy>
        <DataScopeTab />
      </el-tab-pane>
      <el-tab-pane label="用户" name="users" lazy>
        <UsersTab />
      </el-tab-pane>
      <el-tab-pane label="数据源授权" name="datasource-access" lazy>
        <DatasourceAccessTab />
      </el-tab-pane>
      <el-tab-pane label="审计" name="audit" lazy>
        <AuditTab />
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<style scoped>
.ac-control__tabs :deep(.el-tabs__header) {
  margin-bottom: var(--ac-space-16);
}

/* On narrow screens the tab strip scrolls horizontally instead of wrapping or overflowing. */
.ac-control__tabs :deep(.el-tabs__nav-wrap) {
  overflow-x: auto;
  overflow-y: hidden;
}

.ac-control__tabs :deep(.el-tabs__item) {
  font-size: var(--ac-text-body);
}
</style>
