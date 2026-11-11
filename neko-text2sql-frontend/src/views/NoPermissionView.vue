<template>
  <section class="ac-page no-permission" aria-label="暂无可用功能">
    <div class="no-permission__panel">
      <el-result icon="warning" title="暂无可用功能" sub-title="当前账号没有可访问的功能，请联系管理员分配权限。">
        <template #icon>
          <span class="no-permission__mark" aria-hidden="true">!</span>
        </template>
      </el-result>
      <p class="no-permission__note">
        权限更新后，重新进入系统即可使用已开放的功能。
      </p>
    </div>
  </section>
</template>

<style scoped>
.no-permission {
  display: grid;
  min-height: 100%;
  place-items: center;
}

.no-permission__panel {
  width: min(560px, 100%);
  padding: var(--ac-space-24);
  text-align: center;
  background: var(--ac-surface);
  border: 1px solid var(--ac-border-soft);
  border-radius: var(--ac-radius);
  box-shadow: var(--ac-shadow-sm);
}

.no-permission__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  color: var(--el-color-warning-dark-2);
  font-family: var(--ac-font-serif);
  font-size: 30px;
  font-weight: 600;
  background: var(--el-color-warning-light-9);
  border: 1px solid var(--el-color-warning-light-7);
  border-radius: 50%;
}

.no-permission__note {
  margin: calc(var(--ac-space-16) * -1) 0 var(--ac-space-8);
  color: var(--ac-muted);
  font-size: var(--ac-text-meta);
  line-height: var(--ac-line-height);
}
</style>
