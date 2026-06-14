<template>
  <div class="main-box account-manage-page">
    <TreeFilter
      id="name"
      label="name"
      title="科室"
      :data="departmentTree"
      :default-value="initParam.department"
      @change="changeDepartment"
    />

    <div class="table-box account-table-box">
      <ProTable
        ref="proTable"
        :columns="columns"
        :request-api="getAccountListApi"
        :data-callback="dataCallback"
        :init-param="initParam"
        :search-col="{ xs: 1, sm: 1, md: 2, lg: 3, xl: 3 }"
      >
        <template #tableHeader>
          <el-space wrap>
            <el-button v-auth="'user:create'" type="primary" :icon="CirclePlus" @click="openAccountDrawer()">
              新增账号
            </el-button>
            <el-button :icon="Refresh" @click="refresh">刷新</el-button>
          </el-space>
        </template>

        <template #department="{ row }">
          <el-tag effect="plain">{{ row.department }}</el-tag>
        </template>

        <template #roleLabel="{ row }">
          <el-tag type="success" effect="plain">{{ row.roleLabel }}</el-tag>
        </template>

        <template #status="{ row }">
          <el-tag :type="row.status === '启用' ? 'success' : 'info'" effect="plain">{{ row.status }}</el-tag>
        </template>

        <template #operation="{ row }">
          <el-button v-auth="'user:update'" type="primary" link @click="openAccountDrawer(row)">详情</el-button>
          <el-button v-auth="'user:resetPassword'" type="primary" link @click="resetPassword(row)">重置密码</el-button>
          <el-button v-auth="'user:disable'" :type="row.status === '启用' ? 'danger' : 'primary'" link @click="toggleStatus(row)">
            {{ row.status === "启用" ? "停用" : "启用" }}
          </el-button>
        </template>
      </ProTable>
    </div>

    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="560px" destroy-on-close>
      <div class="account-drawer">
        <section class="account-identity">
          <div>
            <strong>{{ accountForm.name || "新账号" }}</strong>
            <span>{{ accountForm.username || "待填写登录账号" }}</span>
          </div>
          <el-tag :type="accountForm.status === '启用' ? 'success' : 'info'" effect="plain">
            {{ accountForm.status || "启用" }}
          </el-tag>
        </section>

        <el-tabs v-model="activeDrawerTab">
          <el-tab-pane label="基础信息" name="profile">
            <el-form :model="accountForm" label-width="92px">
              <el-form-item label="登录账号">
                <el-input v-model="accountForm.username" placeholder="例如 lab-a" />
              </el-form-item>
              <el-form-item label="姓名">
                <el-input v-model="accountForm.name" placeholder="请输入姓名" />
              </el-form-item>
              <el-form-item label="所属科室">
                <el-select v-model="accountForm.department" filterable placeholder="请选择科室">
                  <el-option v-for="item in departments" :key="item" :label="item" :value="item" />
                </el-select>
              </el-form-item>
              <el-form-item label="岗位角色">
                <el-select v-model="accountForm.role" placeholder="请选择角色">
                  <el-option v-for="item in roles" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="账号状态">
                <el-radio-group v-model="accountForm.status">
                  <el-radio-button label="启用" />
                  <el-radio-button label="停用" />
                </el-radio-group>
              </el-form-item>
              <el-form-item label="范围说明">
                <el-input v-model="accountForm.scope" type="textarea" :rows="4" placeholder="说明该账号可维护的资料范围" />
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="权限与安全" name="security">
            <el-descriptions :column="1" border>
              <el-descriptions-item label="角色">{{ currentRoleLabel }}</el-descriptions-item>
              <el-descriptions-item label="科室">{{ accountForm.department || "未设置" }}</el-descriptions-item>
              <el-descriptions-item label="创建时间">{{ accountForm.createdAt || "保存后生成" }}</el-descriptions-item>
              <el-descriptions-item label="更新时间">{{ accountForm.updatedAt || "保存后生成" }}</el-descriptions-item>
            </el-descriptions>

            <div class="security-actions">
              <el-button v-if="accountForm.id" v-auth="'user:resetPassword'" @click="resetPasswordFromDrawer">重置密码</el-button>
              <el-button
                v-if="accountForm.id"
                v-auth="'user:disable'"
                :type="accountForm.status === '启用' ? 'danger' : 'primary'"
                @click="toggleStatusFromDrawer"
              >
                {{ accountForm.status === "启用" ? "停用账号" : "启用账号" }}
              </el-button>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>

      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" @click="saveAccount">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts" name="accountManage">
import { computed, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { CirclePlus, Refresh } from "@element-plus/icons-vue";
import TreeFilter from "@/components/TreeFilter/index.vue";
import ProTable from "@/components/ProTable/index.vue";
import { ColumnProps, ProTableInstance } from "@/components/ProTable/interface";
import {
  getAccountListApi,
  resetAccountPasswordApi,
  saveAccountApi,
  setAccountStatusApi,
  type AccountRow
} from "@/api/modules/clinic";
import { roleLabel, type UserRole } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";

const userStore = useUserStore();
const proTable = ref<ProTableInstance>();
const drawerVisible = ref(false);
const activeDrawerTab = ref("profile");

const departments = ["前台", "门诊", "化验室", "心电室", "B超/放射", "治疗室", "质控/病案", "信息/院办"];
const departmentTree = departments.map(name => ({ name }));
const roles: { label: string; value: UserRole }[] = [
  { label: "管理员", value: "admin" },
  { label: "前台", value: "frontdesk" },
  { label: "化验室", value: "lab" },
  { label: "心电室", value: "ecg" },
  { label: "B超/放射", value: "ultrasound" },
  { label: "医生", value: "doctor" },
  { label: "护士/治疗室", value: "nurse" },
  { label: "质控", value: "quality" }
];

const initParam = reactive({
  department: ""
});

const accountForm = reactive<Partial<AccountRow>>({});

const roleEnum = roles.map(item => ({ label: item.label, value: item.value }));

const columns = reactive<ColumnProps<AccountRow>[]>([
  { type: "index", label: "#", width: 70 },
  { prop: "username", label: "账号", width: 130, search: { el: "input" } },
  { prop: "name", label: "姓名", width: 120, search: { el: "input" } },
  { prop: "department", label: "科室", width: 140 },
  { prop: "roleLabel", label: "角色", width: 130 },
  {
    prop: "role",
    label: "角色筛选",
    isShow: false,
    enum: roleEnum,
    search: { el: "select" }
  },
  {
    prop: "status",
    label: "状态",
    width: 100,
    enum: [
      { label: "启用", value: "启用" },
      { label: "停用", value: "停用" }
    ],
    search: { el: "select" }
  },
  { prop: "scope", label: "可操作范围", minWidth: 260 },
  { prop: "operation", label: "操作", fixed: "right", width: 230 }
]);

const drawerTitle = computed(() => (accountForm.id ? "账号详情" : "新增账号"));
const currentRoleLabel = computed(() => roleLabel(accountForm.role));
const operatorRole = computed(() => userStore.userInfo.role || "frontdesk");
const operatorName = computed(() => roleLabel(operatorRole.value));

const dataCallback = (data: { list: AccountRow[]; total: number }) => data;

const refresh = () => proTable.value?.getTableList();

const changeDepartment = (department: string) => {
  initParam.department = department || "";
  if (proTable.value) proTable.value.pageable.pageNum = 1;
  refresh();
};

const clearAccountForm = () => {
  Object.keys(accountForm).forEach(key => delete accountForm[key as keyof AccountRow]);
};

const openAccountDrawer = (row?: AccountRow) => {
  clearAccountForm();
  Object.assign(
    accountForm,
    row || {
      status: "启用",
      role: "frontdesk",
      department: initParam.department || "前台"
    }
  );
  activeDrawerTab.value = "profile";
  drawerVisible.value = true;
};

const saveAccount = async () => {
  await saveAccountApi({ ...accountForm, operator: operatorName.value, operatorRole: operatorRole.value });
  ElMessage.success("账号已保存");
  drawerVisible.value = false;
  refresh();
};

const toggleStatus = async (row: AccountRow) => {
  await setAccountStatusApi(row.id, row.status === "启用" ? "停用" : "启用", {
    operator: operatorName.value,
    operatorRole: operatorRole.value
  });
  ElMessage.success(row.status === "启用" ? "账号已停用" : "账号已启用");
  refresh();
};

const toggleStatusFromDrawer = async () => {
  if (!accountForm.id || !accountForm.status) return;
  const nextStatus = accountForm.status === "启用" ? "停用" : "启用";
  await setAccountStatusApi(accountForm.id, nextStatus, { operator: operatorName.value, operatorRole: operatorRole.value });
  accountForm.status = nextStatus;
  ElMessage.success(nextStatus === "停用" ? "账号已停用" : "账号已启用");
  refresh();
};

const resetPassword = async (row: AccountRow) => {
  await resetAccountPasswordApi(row.id, { operator: operatorName.value, operatorRole: operatorRole.value });
  ElMessage.success("密码已重置为 123456");
};

const resetPasswordFromDrawer = async () => {
  if (!accountForm.id) return;
  await resetAccountPasswordApi(accountForm.id, { operator: operatorName.value, operatorRole: operatorRole.value });
  ElMessage.success("密码已重置为 123456");
};
</script>

<style scoped lang="scss">
.account-manage-page {
  align-items: stretch;
}

.account-table-box {
  min-width: 0;
}

.account-drawer {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.account-identity {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 14px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;

  strong,
  span {
    display: block;
  }

  strong {
    color: var(--el-text-color-primary);
    font-size: 18px;
  }

  span {
    margin-top: 4px;
    color: var(--el-text-color-regular);
  }
}

.security-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

@media (max-width: 900px) {
  .account-manage-page {
    flex-direction: column;
  }

  :deep(.filter) {
    width: 100%;
    height: 280px;
    margin-right: 0;
    margin-bottom: 10px;
  }
}
</style>
