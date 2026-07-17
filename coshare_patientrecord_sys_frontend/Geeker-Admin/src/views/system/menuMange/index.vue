<template>
  <div class="table-box">
    <section class="card mb10 page-head">
      <div>
        <h2>菜单权限</h2>
        <p>当前页面仅展示服务端固定导航配置，不在浏览器内保存或修改角色权限。</p>
      </div>
      <div class="source-status" aria-label="菜单配置来源">
        <el-tag type="success" effect="plain">服务端固定配置</el-tag>
        <span>版本 {{ authStore.navigationVersion || "加载中" }}</span>
      </div>
    </section>

    <el-alert
      class="mb10"
      title="菜单、按钮权限和首页快捷入口均来自 GET /auth/navigation；需要变更时请修改服务端版本化配置并重新发布。"
      type="info"
      :closable="false"
      show-icon
    />

    <section class="card">
      <ProTable title="当前账号可见菜单" row-key="path" :indent="20" :columns="columns" :data="menuData">
        <template #icon="scope">
          <el-icon :size="18">
            <component :is="scope.row.meta.icon"></component>
          </el-icon>
        </template>
        <template #visibility="{ row }">
          <el-tag :type="row.meta.isHide ? 'info' : 'success'" effect="plain">
            {{ row.meta.isHide ? "兼容路由/隐藏入口" : "侧边栏显示" }}
          </el-tag>
        </template>
      </ProTable>
    </section>
  </div>
</template>

<script setup lang="ts" name="menuMange">
import { computed, onMounted } from "vue";
import type { ColumnProps } from "@/components/ProTable/interface";
import ProTable from "@/components/ProTable/index.vue";
import { useAuthStore } from "@/stores/modules/auth";

const authStore = useAuthStore();
const menuData = computed(() => authStore.authMenuListGet);

onMounted(async () => {
  if (!authStore.navigationVersion) await authStore.getNavigation();
});

const columns: ColumnProps[] = [
  { prop: "meta.title", label: "菜单名称", align: "left", search: { el: "input" } },
  { prop: "meta.icon", label: "图标", width: 90 },
  { prop: "name", label: "路由名称", search: { el: "input" } },
  { prop: "visibility", label: "导航状态", width: 170 },
  { prop: "path", label: "访问路径", minWidth: 280, search: { el: "input" } }
];
</script>

<style scoped lang="scss">
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;

  h2,
  p {
    margin: 0;
  }

  p {
    margin-top: 8px;
    color: var(--el-text-color-regular);
  }
}

.source-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

@media (max-width: 768px) {
  .page-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .source-status {
    justify-content: flex-start;
  }
}
</style>
