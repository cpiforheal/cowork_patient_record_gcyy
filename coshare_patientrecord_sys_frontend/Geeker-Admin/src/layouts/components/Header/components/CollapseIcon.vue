<template>
  <button class="collapse-button" type="button" aria-label="折叠或展开侧边栏" @click="changeCollapse">
    <el-icon class="collapse-icon">
      <component :is="globalStore.isCollapse ? 'expand' : 'fold'"></component>
    </el-icon>
  </button>
</template>

<script setup lang="ts">
import { useGlobalStore } from "@/stores/modules/global";

const globalStore = useGlobalStore();
const changeCollapse = () => globalStore.setGlobalState("isCollapse", !globalStore.isCollapse);
</script>

<style scoped lang="scss">
.collapse-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  margin-right: 20px;
  padding: 0;
  border: 0;
  color: var(--el-header-text-color);
  background: transparent;
  cursor: pointer;
}

.collapse-icon {
  font-size: 22px;
}

@media (max-width: 768px) {
  .collapse-button {
    display: none;
  }
}
</style>
