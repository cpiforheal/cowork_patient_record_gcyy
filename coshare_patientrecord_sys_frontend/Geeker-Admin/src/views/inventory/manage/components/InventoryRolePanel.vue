<template>
  <section class="role-panel">
    <el-alert
      title="岗位决定可见入口与可执行动作"
      description="这里单独配置进销存岗位，不会改动账号的门诊岗位、科室归属或患者数据权限；保存后重新登录即可按新权限进入进销存。"
      type="info"
      :closable="false"
      show-icon
    />

    <div class="panel-heading">
      <div>
        <p>岗位权限</p>
        <h2>按岗位分配进销存职责</h2>
      </div>
      <el-button :loading="loading" :icon="Refresh" @click="$emit('refresh')">刷新</el-button>
    </div>

    <div class="role-cards">
      <article v-for="role in roles" :key="role.code" class="role-card">
        <div class="role-card-top">
          <strong>{{ role.name }}</strong>
          <el-tag size="small" effect="plain">{{ role.memberCount }} 人</el-tag>
        </div>
        <p>{{ role.responsibility || '按系统配置执行相应职责。' }}</p>
        <span>{{ role.dataScope || '按账号所属科室' }}</span>
        <div class="permission-tags">
          <el-tag v-for="permission in role.permissions" :key="permission" size="small" effect="plain">{{ permission }}</el-tag>
        </div>
      </article>
    </div>

    <div class="panel-heading account-heading">
      <div>
        <p>账号归属</p>
        <h2>所属岗位与科室</h2>
      </div>
    </div>
    <el-table :data="accounts" v-loading="loading" class="role-table" max-height="460">
      <el-table-column prop="name" label="人员" min-width="120" />
      <el-table-column prop="username" label="登录账号" min-width="140" />
      <el-table-column prop="department" label="所属科室" min-width="140">
        <template #default="{ row }">{{ row.department || '未设置' }}</template>
      </el-table-column>
      <el-table-column prop="clinicalRole" label="门诊岗位" min-width="120" />
      <el-table-column label="当前岗位" min-width="180">
        <template #default="{ row }">
          <el-select
            :model-value="row.inventoryRole"
            size="small"
            :disabled="row.systemAssigned || savingAccountId === row.id"
            @change="changeRole(row, $event)"
          >
            <el-option label="未开通进销存" value="" />
            <el-option
              v-for="role in roles.filter(item => !item.systemAssigned)"
              :key="role.code"
              :label="role.name"
              :value="role.code"
            />
          </el-select>
          <el-tag v-if="row.systemAssigned" size="small" type="success">管理员自动拥有</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === '启用' ? 'success' : 'info'" size="small">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { Refresh } from "@element-plus/icons-vue";
import type { InventoryAccountAssignment, InventoryRoleDescriptor } from "@/api/modules/inventory";

defineProps<{
  roles: InventoryRoleDescriptor[];
  accounts: InventoryAccountAssignment[];
  loading: boolean;
  savingAccountId: string;
}>();

const emit = defineEmits<{
  refresh: [];
  "change-role": [{ account: InventoryAccountAssignment; roleCode: string }];
}>();

const changeRole = (account: unknown, roleCode: string) => emit("change-role", { account: account as InventoryAccountAssignment, roleCode });
</script>

<style scoped lang="scss">
.role-panel { display: grid; gap: 16px; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; }
.panel-heading p { margin: 0 0 4px; color: var(--el-color-primary); font-size: 13px; font-weight: 700; }
.panel-heading h2 { margin: 0; color: var(--el-text-color-primary); font-size: 20px; }
.role-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 12px; }
.role-card { min-height: 130px; padding: 16px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: #fff; }
.role-card-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.role-card p { min-height: 38px; margin: 12px 0; color: var(--el-text-color-regular); font-size: 13px; line-height: 1.5; }
.role-card span { color: var(--el-text-color-secondary); font-size: 12px; }
.permission-tags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; }
.account-heading { margin-top: 8px; }
.role-table { border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
</style>
