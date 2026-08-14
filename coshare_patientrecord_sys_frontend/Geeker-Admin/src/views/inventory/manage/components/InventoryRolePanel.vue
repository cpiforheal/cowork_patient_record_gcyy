<template>
  <section class="role-panel">
    <el-alert title="进销存门户账号管理" description="仅维护独立进销存门户的 13 个预置账号；不会读取、展示或修改病历端账号。账号变更或重置密码后，原会话会立即失效。" type="info" :closable="false" show-icon />

    <div class="panel-heading">
      <div><p>按岗位分组</p><h2>进销存门户账号</h2></div>
      <el-button :loading="loading" :icon="Refresh" @click="emit('refresh')">刷新</el-button>
    </div>

    <el-table :data="treeRows" row-key="id" :tree-props="{ children: 'children' }" v-loading="loading" class="role-table" max-height="560">
      <el-table-column prop="name" label="岗位 / 科室 / 姓名" min-width="190">
        <template #default="{ row }"><strong v-if="row.isGroup">{{ row.name }}</strong><span v-else>{{ row.department || row.name }}</span></template>
      </el-table-column>
      <el-table-column prop="username" label="登录账号" min-width="130"><template #default="{ row }">{{ row.isGroup ? '—' : row.username }}</template></el-table-column>
      <el-table-column label="岗位" width="142">
        <template #default="{ row }"><span v-if="row.isGroup">{{ row.memberCount }} 个账号</span><el-select v-else :model-value="row.portalRole" size="small" :disabled="savingAccountId === row.id" @change="changeRole(row, $event)"><el-option label="进销存管理员" value="admin" /><el-option label="科室填报员" value="inventory_reporter" /></el-select></template>
      </el-table-column>
      <el-table-column label="科室绑定" min-width="150">
        <template #default="{ row }"><span v-if="row.isGroup">—</span><el-select v-else :model-value="row.departmentKey" size="small" :disabled="row.portalRole === 'admin' || savingAccountId === row.id" @change="changeDepartment(row, $event)"><el-option v-for="department in departments" :key="department.key" :label="department.name" :value="department.key" /></el-select></template>
      </el-table-column>
      <el-table-column label="状态" width="110" align="center"><template #default="{ row }"><el-tag v-if="!row.isGroup" :type="row.status === '启用' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }"><template v-if="!row.isGroup"><el-button link type="primary" :loading="savingAccountId === row.id" @click="toggleStatus(row)">{{ row.status === '启用' ? '停用' : '启用' }}</el-button><el-button link type="warning" :loading="savingAccountId === row.id" @click="emit('reset-password', asAccount(row))">重置密码</el-button></template></template>
      </el-table-column>
    </el-table>
    <p class="panel-tip">重置后的初始密码为 <code>123456</code>，账号将在下次登录时强制修改密码。管理员岗位固定绑定“管理端”。</p>
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { Refresh } from "@element-plus/icons-vue";
import type { InventoryPortalAccount } from "@/api/modules/inventory";

type TreeRow = InventoryPortalAccount & { isGroup?: boolean; memberCount?: number; children?: TreeRow[] };
const props = defineProps<{ accounts: InventoryPortalAccount[]; departments: Array<{ key: string; name: string }>; loading: boolean; savingAccountId: string }>();
const emit = defineEmits<{ refresh: []; update: [{ account: InventoryPortalAccount; changes: Partial<Pick<InventoryPortalAccount, "portalRole" | "departmentKey" | "status">> }]; "reset-password": [account: InventoryPortalAccount] }>();
const treeRows = computed<TreeRow[]>(() => [
  { id: "group-admin", name: "进销存管理员", username: "", departmentKey: "", department: "", portalRole: "admin", portalRoleLabel: "进销存管理员", status: "启用", mustChangePassword: false, displayOrder: 0, isGroup: true, memberCount: props.accounts.filter(account => account.portalRole === "admin").length, children: props.accounts.filter(account => account.portalRole === "admin") },
  { id: "group-reporter", name: "科室填报员", username: "", departmentKey: "", department: "", portalRole: "inventory_reporter", portalRoleLabel: "科室填报员", status: "启用", mustChangePassword: false, displayOrder: 1, isGroup: true, memberCount: props.accounts.filter(account => account.portalRole !== "admin").length, children: props.accounts.filter(account => account.portalRole !== "admin") }
]);
const asAccount = (row: unknown) => row as InventoryPortalAccount;
const changeRole = (row: unknown, role: string) => {
  const account = asAccount(row);
  emit("update", { account, changes: { portalRole: role as InventoryPortalAccount["portalRole"], departmentKey: role === "admin" ? "inventory-admin" : account.departmentKey } });
};
const changeDepartment = (row: unknown, departmentKey: string) => emit("update", { account: asAccount(row), changes: { departmentKey } });
const toggleStatus = (row: unknown) => {
  const account = asAccount(row);
  emit("update", { account, changes: { status: account.status === "启用" ? "停用" : "启用" } });
};
</script>

<style scoped lang="scss">
.role-panel { display: grid; gap: 16px; }.panel-heading { display:flex; align-items:center; justify-content:space-between; }.panel-heading p { margin:0 0 4px; color:var(--el-color-primary); font-size:13px; font-weight:700; }.panel-heading h2 { margin:0; font-size:20px; }.role-table { border:1px solid var(--el-border-color-lighter); border-radius:10px; }.panel-tip { margin:0; color:var(--el-text-color-secondary); font-size:12px; }code { padding:1px 4px; border-radius:4px; color:var(--el-color-primary); background:var(--el-fill-color-light); }
</style>
