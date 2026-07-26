<template>
  <div class="account-manage-page">
    <header class="page-header">
      <div>
        <h2>账号与岗位</h2>
        <p>每个账号必须绑定一个规范岗位、至少一个授权科室，并指定主科室。</p>
      </div>
      <el-space>
        <el-button :icon="Refresh" :loading="loading" @click="loadPageData">刷新</el-button>
        <el-button v-auth="'user:create'" type="primary" :icon="CirclePlus" @click="openAccountDrawer()">新增账号</el-button>
      </el-space>
    </header>

    <section class="account-table-panel">
      <div class="filters">
        <el-input v-model="filters.keyword" :prefix-icon="Search" clearable placeholder="搜索账号或姓名" />
        <el-select v-model="filters.departmentId" clearable filterable placeholder="全部科室">
          <el-option v-for="item in activeDepartments" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-select v-model="filters.role" clearable filterable placeholder="全部岗位">
          <el-option v-for="item in roles" :key="item.role" :label="item.name" :value="item.role" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="全部状态">
          <el-option label="启用" value="启用" />
          <el-option label="停用" value="停用" />
        </el-select>
        <span class="result-count">共 {{ filteredAccounts.length }} 个账号</span>
      </div>

      <el-table v-loading="loading" :data="filteredAccounts" row-key="id" stripe>
        <el-table-column prop="username" label="登录账号" width="150" fixed="left" />
        <el-table-column prop="name" label="姓名" width="120" />
        <el-table-column label="岗位" width="150">
          <template #default="{ row }">
            <el-tag effect="plain">{{ roleName(row.role, row.roleLabel) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="授权科室" min-width="240">
          <template #default="{ row }">
            <el-space wrap>
              <el-tag v-for="departmentId in row.departmentIds" :key="departmentId" type="info" effect="plain">
                {{ departmentName(departmentId) }}{{ departmentId === row.primaryDepartmentId ? "（主）" : "" }}
              </el-tag>
            </el-space>
          </template>
        </el-table-column>
        <el-table-column prop="scope" label="数据范围" min-width="240" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '启用' ? 'success' : 'info'" effect="plain">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button v-auth="'user:update'" type="primary" link @click="openAccountDrawer(row)">编辑</el-button>
            <el-button v-auth="'user:resetPassword'" type="primary" link @click="resetPassword(row)">重置密码</el-button>
            <el-button
              v-auth="'user:disable'"
              :type="row.status === '启用' ? 'danger' : 'primary'"
              link
              @click="toggleStatus(row)"
            >
              {{ row.status === "启用" ? "停用" : "启用" }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="drawerVisible" :title="form.id ? '编辑账号' : '新增账号'" size="560px" destroy-on-close>
      <el-form label-width="96px">
        <el-form-item label="登录账号" required>
          <el-input v-model="form.username" placeholder="唯一登录账号" :disabled="Boolean(form.id)" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="form.name" placeholder="账号使用人姓名" />
        </el-form-item>
        <el-form-item v-if="!form.id" label="初始密码" required>
          <el-input v-model="form.password" type="password" show-password autocomplete="new-password" placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="岗位角色" required>
          <el-select v-model="form.role" filterable placeholder="选择规范岗位">
            <el-option v-for="item in roles" :key="item.role" :label="item.name" :value="item.role" />
          </el-select>
        </el-form-item>
        <el-form-item label="授权科室" required>
          <el-select v-model="form.departmentIds" multiple filterable placeholder="选择一个或多个已启用科室">
            <el-option v-for="item in activeDepartments" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="主科室" required>
          <el-select v-model="form.primaryDepartmentId" filterable placeholder="选择授权科室中的一个">
            <el-option v-for="item in selectedDepartments" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="账号状态">
          <el-radio-group v-model="form.status">
            <el-radio-button label="启用" />
            <el-radio-button label="停用" />
          </el-radio-group>
        </el-form-item>
        <el-form-item label="范围说明">
          <el-input v-model="form.scope" type="textarea" :rows="4" placeholder="可选；说明特殊的数据范围限制" />
        </el-form-item>
      </el-form>

      <el-alert v-if="selectedRole" type="info" :closable="false" show-icon>
        <template #title>{{ selectedRole.name }}：{{ selectedRole.responsibility }}</template>
        数据范围：{{ selectedRole.dataScope }}
      </el-alert>

      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveAccount">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts" name="accountManage">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { CirclePlus, Refresh, Search } from "@element-plus/icons-vue";
import {
  createAdminAccountApi,
  getAdminAccountsApi,
  getAdminRoleCatalogApi,
  resetAdminAccountPasswordApi,
  updateAdminAccountApi,
  type AccountUpsertRequest,
  type AdminAccountSummary,
  type RoleDescriptor
} from "@/api/modules/authAdmin";
import { getDepartmentListApi, type DepartmentRow } from "@/api/modules/clinic";

type AccountForm = Partial<AdminAccountSummary> & { password?: string };

const loading = ref(false);
const saving = ref(false);
const drawerVisible = ref(false);
const accounts = ref<AdminAccountSummary[]>([]);
const roles = ref<RoleDescriptor[]>([]);
const departments = ref<DepartmentRow[]>([]);
const form = reactive<AccountForm>({});
const filters = reactive({ keyword: "", departmentId: "", role: "", status: "" });

const activeDepartments = computed(() => departments.value.filter(item => (item.status || "ACTIVE") === "ACTIVE"));
const selectedDepartments = computed(() => activeDepartments.value.filter(item => form.departmentIds?.includes(item.id)));
const selectedRole = computed(() => roles.value.find(item => item.role === form.role));
const filteredAccounts = computed(() => {
  const keyword = filters.keyword.trim().toLowerCase();
  return accounts.value.filter(account => {
    const keywordMatched = !keyword || `${account.username} ${account.name}`.toLowerCase().includes(keyword);
    const departmentMatched = !filters.departmentId || account.departmentIds.includes(filters.departmentId);
    const roleMatched = !filters.role || account.role === filters.role;
    const statusMatched = !filters.status || account.status === filters.status;
    return keywordMatched && departmentMatched && roleMatched && statusMatched;
  });
});

const roleName = (role: string, fallback = "") => roles.value.find(item => item.role === role)?.name || fallback || role;
const departmentName = (id: string) => departments.value.find(item => item.id === id)?.name || id;

const loadPageData = async () => {
  loading.value = true;
  try {
    const [accountRows, roleRows, departmentResult] = await Promise.all([
      getAdminAccountsApi(),
      getAdminRoleCatalogApi(),
      getDepartmentListApi({ pageNum: 1, pageSize: 1000 })
    ]);
    accounts.value = accountRows;
    roles.value = roleRows;
    departments.value = departmentResult.data.list || [];
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    loading.value = false;
  }
};

const clearForm = () => Object.keys(form).forEach(key => delete form[key as keyof AccountForm]);

const openAccountDrawer = (rawRow?: unknown) => {
  clearForm();
  const row = rawRow as AdminAccountSummary | undefined;
  if (row) {
    Object.assign(form, { ...row, departmentIds: [...row.departmentIds] });
  } else {
    const initialDepartmentId = activeDepartments.value[0]?.id || "";
    Object.assign(form, {
      username: "",
      name: "",
      password: "",
      role: roles.value.find(item => item.role === "frontdesk")?.role || roles.value[0]?.role || "",
      status: "启用",
      departmentIds: initialDepartmentId ? [initialDepartmentId] : [],
      primaryDepartmentId: initialDepartmentId,
      scope: ""
    });
  }
  drawerVisible.value = true;
};

const validateForm = () => {
  if (!form.username?.trim() || !form.name?.trim()) return "请填写登录账号和姓名";
  if (!form.role || !roles.value.some(item => item.role === form.role)) return "请选择规范岗位";
  if (!form.departmentIds?.length) return "请至少选择一个授权科室";
  if (!form.primaryDepartmentId || !form.departmentIds.includes(form.primaryDepartmentId)) return "主科室必须属于授权科室";
  if (!form.id && (form.password?.length || 0) < 8) return "初始密码至少需要 8 位";
  return "";
};

const buildPayload = (status = form.status): AccountUpsertRequest => ({
  username: form.username?.trim() || "",
  name: form.name?.trim() || "",
  role: form.role || "",
  status: status || "启用",
  password: form.id ? undefined : form.password,
  departmentIds: [...(form.departmentIds || [])],
  primaryDepartmentId: form.primaryDepartmentId || "",
  scope: form.scope?.trim() || ""
});

const saveAccount = async () => {
  const errorMessage = validateForm();
  if (errorMessage) {
    ElMessage.warning(errorMessage);
    return;
  }
  saving.value = true;
  try {
    if (form.id) await updateAdminAccountApi(form.id, buildPayload());
    else await createAdminAccountApi(buildPayload());
    ElMessage.success("账号已保存，相关旧会话已失效");
    drawerVisible.value = false;
    await loadPageData();
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const toggleStatus = async (rawRow: unknown) => {
  const row = rawRow as AdminAccountSummary;
  const nextStatus = row.status === "启用" ? "停用" : "启用";
  await ElMessageBox.confirm(`确定${nextStatus}账号“${row.name}”吗？该账号的现有会话会立即失效。`, `${nextStatus}账号`, {
    confirmButtonText: nextStatus,
    cancelButtonText: "取消",
    type: nextStatus === "停用" ? "warning" : "info"
  });
  try {
    await updateAdminAccountApi(row.id, {
      username: row.username,
      name: row.name,
      role: row.role,
      status: nextStatus,
      departmentIds: [...row.departmentIds],
      primaryDepartmentId: row.primaryDepartmentId,
      scope: row.scope
    });
    ElMessage.success(`账号已${nextStatus}`);
    await loadPageData();
  } catch (error) {
    ElMessage.error((error as Error).message);
  }
};

const resetPassword = async (rawRow: unknown) => {
  const row = rawRow as AdminAccountSummary;
  const result = await ElMessageBox.prompt(
    "输入至少 8 位的新密码。重置成功后，该账号的所有旧会话会立即失效。",
    `重置密码 - ${row.name}`,
    {
      confirmButtonText: "重置",
      cancelButtonText: "取消",
      inputType: "password",
      inputPlaceholder: "至少 8 位",
      inputValidator: value => String(value || "").length >= 8 || "新密码至少需要 8 位"
    }
  ).catch(() => null);
  if (!result) return;
  try {
    await resetAdminAccountPasswordApi(row.id, result.value);
    ElMessage.success("密码已重置，旧会话已失效");
  } catch (error) {
    ElMessage.error((error as Error).message);
  }
};

onMounted(loadPageData);
</script>

<style scoped lang="scss">
.account-manage-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  height: 100%;
}

.page-header,
.filters {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-header {
  justify-content: space-between;

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

.account-table-panel {
  min-width: 0;
  padding: 16px;
  overflow: hidden;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.filters {
  flex-wrap: wrap;
  margin-bottom: 14px;

  .el-input,
  .el-select {
    width: 200px;
  }
}

.result-count {
  margin-left: auto;
  color: var(--el-text-color-secondary);
}

:deep(.el-drawer__footer) {
  border-top: 1px solid var(--el-border-color-lighter);
}

@media (max-width: 720px) {
  .page-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .filters .el-input,
  .filters .el-select {
    width: 100%;
  }

  .result-count {
    margin-left: 0;
  }
}
</style>
