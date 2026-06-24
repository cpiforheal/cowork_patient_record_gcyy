<template>
  <div class="table-box">
    <section class="card mb10 page-head">
      <div>
        <h2>菜单权限</h2>
        <p>当前菜单已经收窄到门诊资料流转。后续这里用于给角色分配菜单可见范围。</p>
      </div>
    </section>

    <section class="card">
      <ProTable ref="proTable" title="菜单列表" row-key="path" :indent="20" :columns="columns" :data="menuData">
        <template #icon="scope">
          <el-icon :size="18">
            <component :is="scope.row.meta.icon"></component>
          </el-icon>
        </template>
        <template #roles="{ row }">
          <div class="role-tags">
            <el-tag v-for="role in menuRoleMap[row.path] || defaultVisibleRoles(row)" :key="role" effect="plain">
              {{ roleLabel(role) }}
            </el-tag>
          </div>
        </template>
        <template #operation="{ row }">
          <el-button v-auth="'menu:update'" type="primary" link :icon="EditPen" @click="openRoleConfig(row)">配置角色</el-button>
        </template>
      </ProTable>
    </section>

    <el-dialog v-model="roleDialogVisible" title="配置菜单可见角色" width="560px" destroy-on-close>
      <div v-if="activeMenu" class="menu-role-dialog">
        <section class="menu-role-target">
          <span>当前菜单</span>
          <strong>{{ activeMenu.meta.title }}</strong>
          <small>{{ activeMenu.path }}</small>
        </section>
        <el-alert
          title="保存后会更新当前页面的角色可见范围提示；正式控制菜单显示还需要后端持久化菜单-角色关系。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-checkbox-group v-model="roleForm.roles" class="menu-role-checks">
          <el-checkbox v-for="role in roleOptions" :key="role.value" :label="role.value">
            {{ role.label }}
          </el-checkbox>
        </el-checkbox-group>
      </div>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!roleForm.roles.length" @click="saveMenuRoles">保存配置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="menuMange">
import { reactive, ref } from "vue";
import { ColumnProps } from "@/components/ProTable/interface";
import { ElMessage } from "element-plus";
import { EditPen } from "@element-plus/icons-vue";
import authMenuList from "@/assets/json/authMenuList.json";
import ProTable from "@/components/ProTable/index.vue";
import { USER_ROLE_OPTIONS, roleLabel, type UserRole } from "@/config/fieldPermissions";

type MenuRow = (typeof authMenuList.data)[number] & {
  children?: MenuRow[];
};

const proTable = ref();
const menuData = ref<MenuRow[]>(authMenuList.data as MenuRow[]);
const roleDialogVisible = ref(false);
const activeMenu = ref<MenuRow>();
const roleForm = reactive<{ roles: UserRole[] }>({ roles: [] });
const roleOptions = USER_ROLE_OPTIONS;
const menuRoleMap = reactive<Record<string, UserRole[]>>({});

const defaultVisibleRoles = (row: MenuRow): UserRole[] => {
  if (row.path.startsWith("/system")) return ["admin"];
  if (row.path.startsWith("/audit")) return ["admin", "quality"];
  if (row.path.startsWith("/workbench")) {
    return ["admin", "frontdesk", "reception", "inspection", "lab", "ecg", "ultrasound", "nurse", "nursing"];
  }
  if (row.path.startsWith("/templates")) return ["admin", "quality"];
  return ["admin", "frontdesk", "doctor", "nurse", "quality"];
};

const openRoleConfig = (row: MenuRow) => {
  activeMenu.value = row;
  roleForm.roles = [...(menuRoleMap[row.path] || defaultVisibleRoles(row))];
  roleDialogVisible.value = true;
};

const saveMenuRoles = () => {
  if (!activeMenu.value) return;
  menuRoleMap[activeMenu.value.path] = [...roleForm.roles];
  roleDialogVisible.value = false;
  ElMessage.success(`已保存“${activeMenu.value.meta.title}”可见角色，下一步可接入后端持久化后参与登录菜单过滤`);
};

const columns: ColumnProps[] = [
  { prop: "meta.title", label: "菜单名称", align: "left", search: { el: "input" } },
  { prop: "meta.icon", label: "图标" },
  { prop: "name", label: "权限标识", search: { el: "input" } },
  { prop: "roles", label: "可见角色", minWidth: 220 },
  { prop: "path", label: "访问路径", width: 280, search: { el: "input" } },
  { prop: "operation", label: "操作", width: 160, fixed: "right" }
];
</script>

<style scoped lang="scss">
.page-head {
  h2,
  p {
    margin: 0;
  }

  p {
    margin-top: 8px;
    color: var(--el-text-color-regular);
  }
}

.role-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.menu-role-dialog {
  display: grid;
  gap: 14px;
}

.menu-role-target {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;

  span,
  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  strong {
    color: var(--el-text-color-primary);
    font-size: 16px;
  }
}

.menu-role-checks {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 16px;
}
</style>
