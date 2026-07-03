<template>
  <div class="table-box">
    <ProTable ref="proTable" :columns="columns" :request-api="getRoleListApi" :data-callback="dataCallback">
      <template #tableHeader>
        <el-button v-auth="'role:create'" type="primary" :icon="CirclePlus" @click="openRoleDialog()">新增角色</el-button>
      </template>

      <template #permissions="{ row }">
        <el-space wrap>
          <el-tag v-for="permission in row.permissions" :key="permission" effect="plain">{{ permission }}</el-tag>
        </el-space>
      </template>

      <template #editableSections="{ row }">
        <el-space wrap>
          <el-tag v-for="section in row.editableSections" :key="section" type="success" effect="plain">{{ section }}</el-tag>
        </el-space>
      </template>

      <template #operation="{ row }">
        <el-button v-auth="'role:update'" type="primary" link @click="openRoleDialog(row)">编辑</el-button>
        <el-button v-auth="'role:grant'" type="primary" link @click="openRoleDialog(row)">授权</el-button>
        <el-button
          v-auth="'role:delete'"
          type="danger"
          link
          :disabled="row.role === 'admin' || row.members > 0"
          @click="deleteRole(row)"
        >
          删除
        </el-button>
      </template>
    </ProTable>

    <el-dialog v-model="dialogVisible" :title="roleForm.id ? '编辑角色权限' : '新增角色'" width="680px" destroy-on-close>
      <el-form :model="roleForm" label-width="96px">
        <el-form-item label="角色名称">
          <el-input v-model="roleForm.name" />
        </el-form-item>
        <el-form-item label="角色编码">
          <el-select v-model="roleForm.role">
            <el-option v-for="item in roleOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="roleForm.desc" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="按钮权限">
          <el-select v-model="roleForm.permissions" multiple filterable allow-create>
            <el-option v-for="item in permissionOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="字段章节">
          <el-select v-model="roleForm.editableSections" multiple filterable>
            <el-option v-for="item in recordSections" :key="item.key" :label="item.title" :value="item.key" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="roleManage">
import { computed, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { CirclePlus } from "@element-plus/icons-vue";
import ProTable from "@/components/ProTable/index.vue";
import { ColumnProps, ProTableInstance } from "@/components/ProTable/interface";
import { deleteRoleApi, getRoleListApi, saveRoleApi, type RoleRow } from "@/api/modules/clinic";
import { USER_ROLE_OPTIONS, recordSections } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";

const userStore = useUserStore();
const proTable = ref<ProTableInstance>();
const dialogVisible = ref(false);
const roleForm = reactive<Partial<RoleRow>>({});
const roleOptions = USER_ROLE_OPTIONS;
const permissionOptions = [
  "patient:create",
  "patient:read",
  "patient:update",
  "field:read",
  "field:edit",
  "document:upload",
  "document:download",
  "document:restore",
  "audit:read",
  "audit:export",
  "role:grant"
];
const operatorRole = computed(() => userStore.userInfo.role || "frontdesk");
const operatorName = computed(() => roleOptions.find(item => item.value === operatorRole.value)?.label || "前台");

const columns = reactive<ColumnProps<RoleRow>[]>([
  { type: "index", label: "#", width: 80 },
  { prop: "name", label: "角色", width: 140, search: { el: "input" } },
  { prop: "members", label: "成员数", width: 100 },
  { prop: "desc", label: "说明", minWidth: 220 },
  { prop: "permissions", label: "按钮权限", minWidth: 260 },
  { prop: "editableSections", label: "可编辑章节", minWidth: 240 },
  { prop: "operation", label: "操作", fixed: "right", width: 210 }
]);

const dataCallback = (data: { list: RoleRow[]; total: number }) => data;

const openRoleDialog = (row?: RoleRow) => {
  Object.keys(roleForm).forEach(key => delete roleForm[key as keyof RoleRow]);
  Object.assign(roleForm, row || { role: "frontdesk", permissions: [], editableSections: [] });
  dialogVisible.value = true;
};

const saveRole = async () => {
  await saveRoleApi({ ...roleForm, operator: operatorName.value, operatorRole: operatorRole.value });
  ElMessage.success("角色权限已保存");
  dialogVisible.value = false;
  proTable.value?.getTableList();
};

const deleteRole = async (row: RoleRow) => {
  if (row.role === "admin") {
    ElMessage.warning("内置管理员角色不能删除");
    return;
  }
  if (row.members > 0) {
    ElMessage.warning(`该角色仍有 ${row.members} 个账号使用，请先调整账号角色`);
    return;
  }
  await ElMessageBox.confirm(`确定删除角色“${row.name}”吗？`, "删除角色", {
    confirmButtonText: "删除",
    cancelButtonText: "取消",
    type: "warning"
  });
  await deleteRoleApi(row.id, { operator: operatorName.value, operatorRole: operatorRole.value });
  ElMessage.success("角色已删除");
  proTable.value?.getTableList();
};
</script>
