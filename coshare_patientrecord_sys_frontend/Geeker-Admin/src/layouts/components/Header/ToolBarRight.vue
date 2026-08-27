<template>
  <div class="tool-bar-ri">
    <div class="header-icon">
      <SearchMenu id="searchMenu" />
      <Fullscreen id="fullscreen" />
    </div>
    <span class="system-badge" :class="authStore.activeSystem">{{ systemName }}</span>
    <span class="username">{{ username }}</span>
    <Avatar />
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useUserStore } from "@/stores/modules/user";
import { useAuthStore } from "@/stores/modules/auth";
import SearchMenu from "./components/SearchMenu.vue";
import Fullscreen from "./components/Fullscreen.vue";
import Avatar from "./components/Avatar.vue";

const userStore = useUserStore();
const authStore = useAuthStore();
const systemName = computed(() => (authStore.activeSystem === "inventory" ? "进销存系统" : "前置病历"));
const username = computed(() => {
  const { name, department } = userStore.userInfo;
  return department ? `${name}（${department}）` : name;
});
</script>

<style scoped lang="scss">
.tool-bar-ri {
  display: flex;
  align-items: center;
  justify-content: center;
  padding-right: 25px;

  .header-icon {
    display: flex;
    align-items: center;

    & > * {
      margin-left: 18px;
      color: var(--el-header-text-color);
    }
  }

  .username {
    max-width: 220px;
    margin: 0 18px;
    overflow: hidden;
    font-size: 15px;
    color: var(--el-header-text-color);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .system-badge {
    padding: 4px 9px;
    margin-left: 18px;
    color: var(--el-color-primary);
    font-size: 12px;
    font-weight: 600;
    line-height: 1;
    white-space: nowrap;
    background: var(--el-color-primary-light-9);
    border: 1px solid var(--el-color-primary-light-7);
    border-radius: 999px;

    &.inventory {
      color: var(--el-color-success);
      background: var(--el-color-success-light-9);
      border-color: var(--el-color-success-light-7);
    }
  }
}

/* 移动端收敛顶栏：隐藏全屏按钮、收紧用户名，保证头像与系统徽标可见 */
@media (max-width: 768px) {
  .tool-bar-ri {
    padding-right: 10px;

    .header-icon > * {
      margin-left: 10px;
    }

    .header-icon :deep(#fullscreen) {
      display: none;
    }

    .username {
      max-width: 96px;
      margin: 0 10px;
      font-size: 13px;
    }

    .system-badge {
      margin-left: 10px;
    }
  }
}
</style>
