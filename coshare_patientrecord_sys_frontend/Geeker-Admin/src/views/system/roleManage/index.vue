<template>
  <div class="table-box role-policy-page">
    <el-alert type="info" :closable="false" show-icon>
      <template #title>角色策略为服务端固定只读配置</template>
      当前生效策略版本：{{
        authStore.policyVersion || "尚未加载"
      }}。菜单、按钮、主病历阶段和辅助任务权限以登录后服务端下发结果为准。
    </el-alert>

    <ProTable ref="proTable" :columns="columns" :request-api="getRoleListApi" :data-callback="dataCallback">
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
    </ProTable>
  </div>
</template>

<script setup lang="ts" name="roleManage">
import { reactive, ref } from "vue";
import ProTable from "@/components/ProTable/index.vue";
import { ColumnProps, ProTableInstance } from "@/components/ProTable/interface";
import { getRoleListApi, type RoleRow } from "@/api/modules/clinic";
import { useAuthStore } from "@/stores/modules/auth";

const authStore = useAuthStore();
const proTable = ref<ProTableInstance>();

const columns = reactive<ColumnProps<RoleRow>[]>([
  { type: "index", label: "#", width: 80 },
  { prop: "name", label: "角色", width: 140, search: { el: "input" } },
  { prop: "members", label: "成员数", width: 100 },
  { prop: "desc", label: "固定角色说明", minWidth: 220 },
  { prop: "permissions", label: "历史权限标签（只读）", minWidth: 260 },
  { prop: "editableSections", label: "历史章节标签（只读）", minWidth: 240 }
]);

const dataCallback = (data: { list: RoleRow[]; total: number }) => data;
</script>

<style scoped>
.role-policy-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
</style>
