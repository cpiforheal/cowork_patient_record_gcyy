<template>
  <div class="role-policy-page">
    <header class="page-header">
      <div>
        <h2>角色与菜单权限</h2>
        <p>岗位职责、可见菜单与按钮权限均由服务端统一控制，此处只读展示。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadRoles">刷新</el-button>
    </header>

    <el-tabs v-model="activeTab" class="policy-tabs">
      <el-tab-pane label="岗位权限" name="roles">
        <el-alert type="info" :closable="false" show-icon>
          <template #title>权限目录只读</template>
          页面展示的是当前实际生效的岗位能力，不再使用历史权限标签。修改权限需要更新服务端策略并重新发布。
        </el-alert>

        <section class="role-table-panel">
          <div class="table-toolbar">
            <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索岗位、职责或入口" />
            <span>共 {{ filteredRoles.length }} 个岗位</span>
          </div>

          <el-table v-loading="loading" :data="filteredRoles" row-key="role" stripe>
            <el-table-column label="岗位" width="160" fixed="left">
              <template #default="{ row }">
                <strong class="role-name">{{ row.name }}</strong>
                <code>{{ row.role }}</code>
              </template>
            </el-table-column>
            <el-table-column prop="responsibility" label="核心职责" min-width="220" />
            <el-table-column label="可见入口" min-width="240">
              <template #default="{ row }">
                <el-space wrap>
                  <el-tag v-for="entry in row.entries" :key="entry" effect="plain">{{ entry }}</el-tag>
                  <span v-if="!row.entries.length" class="empty-value">无业务入口</span>
                </el-space>
              </template>
            </el-table-column>
            <el-table-column label="允许操作" min-width="280">
              <template #default="{ row }">
                <span class="plain-list">{{ row.actions.join("、") || "仅查看" }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="dataScope" label="数据范围" min-width="220" />
            <el-table-column prop="memberCount" label="成员数" width="90" align="center" />
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="菜单权限" name="menus">
        <el-alert
          class="mb10"
          title="菜单、按钮权限和首页快捷入口均来自 GET /auth/navigation；需要变更时请修改服务端版本化配置并重新发布。"
          type="info"
          :closable="false"
          show-icon
        />

        <section class="role-table-panel">
          <ProTable title="当前账号可见菜单" row-key="path" :indent="20" :columns="menuColumns" :data="menuData">
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
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts" name="roleManage">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh, Search } from "@element-plus/icons-vue";
import { getAdminRoleCatalogApi, type RoleDescriptor } from "@/api/modules/authAdmin";
import type { ColumnProps } from "@/components/ProTable/interface";
import ProTable from "@/components/ProTable/index.vue";
import { useAuthStore } from "@/stores/modules/auth";

const loading = ref(false);
const keyword = ref("");
const roles = ref<RoleDescriptor[]>([]);
const activeTab = ref("roles");

const authStore = useAuthStore();
const menuData = computed(() => authStore.authMenuListGet);

const filteredRoles = computed(() => {
  const query = keyword.value.trim().toLowerCase();
  if (!query) return roles.value;
  return roles.value.filter(role =>
    [role.name, role.role, role.responsibility, role.dataScope, ...role.entries, ...role.actions]
      .join(" ")
      .toLowerCase()
      .includes(query)
  );
});

const menuColumns: ColumnProps[] = [
  { prop: "meta.title", label: "菜单名称", align: "left", search: { el: "input" } },
  { prop: "meta.icon", label: "图标", width: 90 },
  { prop: "name", label: "路由名称", search: { el: "input" } },
  { prop: "visibility", label: "导航状态", width: 170 },
  { prop: "path", label: "访问路径", minWidth: 280, search: { el: "input" } }
];

const loadRoles = async () => {
  loading.value = true;
  try {
    roles.value = await getAdminRoleCatalogApi();
    if (!authStore.navigationVersion) await authStore.getNavigation();
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    loading.value = false;
  }
};

onMounted(loadRoles);
</script>

<style scoped lang="scss">
.role-policy-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  height: 100%;
}

.page-header,
.table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.page-header {
  h2,
  p {
    margin: 0;
  }

  h2 {
    font-size: 20px;
    letter-spacing: 0;
  }

  p {
    margin-top: 6px;
    color: var(--el-text-color-secondary);
  }
}

.policy-tabs {
  :deep(.el-tabs__content) {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  :deep(.el-tab-pane) {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
}

.role-table-panel {
  min-width: 0;
  padding: 16px;
  overflow: hidden;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.table-toolbar {
  margin-bottom: 14px;
  color: var(--el-text-color-secondary);

  .el-input {
    width: min(360px, 100%);
  }
}

.role-name,
code {
  display: block;
}

code {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.plain-list {
  line-height: 1.7;
}

.empty-value {
  color: var(--el-text-color-placeholder);
}

@media (max-width: 720px) {
  .page-header,
  .table-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
