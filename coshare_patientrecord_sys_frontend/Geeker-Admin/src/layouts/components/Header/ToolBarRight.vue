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
</style>
