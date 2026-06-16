<template>
  <el-form ref="loginFormRef" class="clinic-login-form" :model="loginForm" :rules="loginRules" size="large">
    <el-form-item prop="department">
      <el-select
        v-model="loginForm.department"
        filterable
        placeholder="请选择科室"
        :loading="departmentLoading"
        @change="handleDepartmentChange"
        @focus="emit('fieldFocus', 'username')"
        @blur="emit('fieldFocus', 'idle')"
      >
        <template #prefix>
          <el-icon class="el-input__icon">
            <office-building />
          </el-icon>
        </template>
        <el-option v-for="department in departmentOptions" :key="department" :label="department" :value="department" />
      </el-select>
    </el-form-item>
    <el-form-item prop="username">
      <el-select
        v-model="loginForm.username"
        filterable
        :disabled="!loginForm.department"
        :loading="accountLoading"
        placeholder="请选择账号"
        @focus="emit('fieldFocus', 'username')"
        @blur="emit('fieldFocus', 'idle')"
      >
        <template #prefix>
          <el-icon class="el-input__icon">
            <user />
          </el-icon>
        </template>
        <el-option
          v-for="account in filteredAccountOptions"
          :key="account.id"
          :label="accountLabel(account)"
          :value="account.username"
        >
          <div class="account-option">
            <span class="account-name">{{ account.name }}</span>
            <span class="account-meta">{{ account.username }} · {{ account.roleLabel }}</span>
          </div>
        </el-option>
      </el-select>
    </el-form-item>
    <el-form-item prop="password">
      <el-input
        v-model="loginForm.password"
        type="password"
        placeholder="请输入登录密码"
        show-password
        autocomplete="new-password"
        @focus="emit('fieldFocus', 'password')"
        @blur="emit('fieldFocus', 'idle')"
      >
        <template #prefix>
          <el-icon class="el-input__icon">
            <lock />
          </el-icon>
        </template>
      </el-input>
    </el-form-item>
  </el-form>
  <div class="login-btn">
    <el-button :icon="CircleClose" round size="large" @click="resetForm(loginFormRef)">重置</el-button>
    <el-button :icon="UserFilled" round size="large" type="primary" :loading="loading" @click="login(loginFormRef)">
      登录
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { HOME_URL } from "@/config";
import { Login } from "@/api/interface";
import { ElNotification } from "element-plus";
import { loginApi } from "@/api/modules/login";
import { getAccountListApi, getDepartmentListApi } from "@/api/modules/clinic";
import type { AccountRow } from "@/api/modules/clinic/types";
import { useUserStore } from "@/stores/modules/user";
import { useTabsStore } from "@/stores/modules/tabs";
import { useKeepAliveStore } from "@/stores/modules/keepAlive";
import { initDynamicRouter } from "@/routers/modules/dynamicRouter";
import { CircleClose, OfficeBuilding, UserFilled } from "@element-plus/icons-vue";
import type { ElForm } from "element-plus";

const emit = defineEmits<{
  fieldFocus: ["idle" | "username" | "password"];
}>();

const router = useRouter();
const userStore = useUserStore();
const tabsStore = useTabsStore();
const keepAliveStore = useKeepAliveStore();

type FormInstance = InstanceType<typeof ElForm>;
const loginFormRef = ref<FormInstance>();
const loginRules = reactive({
  department: [{ required: true, message: "请选择科室", trigger: "change" }],
  username: [{ required: true, message: "请选择账号", trigger: "change" }],
  password: [{ required: true, message: "请输入登录密码", trigger: "blur" }]
});

const loading = ref(false);
const departmentLoading = ref(false);
const accountLoading = ref(false);
const accountOptions = ref<AccountRow[]>([]);
const departmentOptions = ref<string[]>([]);
const loginForm = reactive<Login.ReqLoginForm & { department: string }>({
  department: "",
  username: "",
  password: ""
});

const activeAccounts = computed(() => accountOptions.value.filter(account => account.status === "启用"));
const filteredAccountOptions = computed(() =>
  activeAccounts.value.filter(account => !loginForm.department || account.department === loginForm.department)
);

const accountLabel = (account: AccountRow) => `${account.name}（${account.username}）`;

const syncDepartmentOptions = (departmentNames: string[]) => {
  const names = new Set<string>();
  departmentNames.forEach(name => {
    if (name.trim()) names.add(name.trim());
  });
  activeAccounts.value.forEach(account => {
    if (account.department.trim()) names.add(account.department.trim());
  });
  departmentOptions.value = Array.from(names).sort((left, right) => left.localeCompare(right, "zh-Hans-CN"));
};

const loadDepartments = async () => {
  departmentLoading.value = true;
  try {
    const [{ data: departmentData }, { data: accountData }] = await Promise.all([
      getDepartmentListApi({ pageNum: 1, pageSize: 500 }),
      getAccountListApi({ pageNum: 1, pageSize: 500, status: "启用" })
    ]);
    accountOptions.value = accountData.list;
    syncDepartmentOptions(departmentData.list.map(item => item.name));
  } catch (error) {
    ElNotification({
      title: "登录数据加载失败",
      message: (error as Error).message,
      type: "error",
      duration: 3000
    });
  } finally {
    departmentLoading.value = false;
  }
};

const loadAccountsByDepartment = async (department: string) => {
  if (!department) {
    loginForm.username = "";
    return;
  }
  accountLoading.value = true;
  try {
    const { data } = await getAccountListApi({ pageNum: 1, pageSize: 500, department, status: "启用" });
    const remoteAccounts = data.list.filter(account => account.status === "启用");
    const otherAccounts = accountOptions.value.filter(account => account.department !== department);
    accountOptions.value = [...otherAccounts, ...remoteAccounts];
    if (remoteAccounts.length === 1) loginForm.username = remoteAccounts[0].username;
  } catch (error) {
    ElNotification({
      title: "账号列表加载失败",
      message: (error as Error).message,
      type: "error",
      duration: 3000
    });
  } finally {
    accountLoading.value = false;
  }
};

const handleDepartmentChange = async (department: string) => {
  loginForm.username = "";
  loginForm.password = "";
  await loadAccountsByDepartment(department);
};

const login = (formEl: FormInstance | undefined) => {
  if (!formEl) return;
  formEl.validate(async valid => {
    if (!valid) return;
    loading.value = true;
    try {
      const { data } = await loginApi({ username: loginForm.username.trim(), password: loginForm.password });
      const profile = data.userInfo || { name: "管理员", role: "admin", department: "信息/院办" };
      userStore.setToken(data.access_token);
      userStore.setUserInfo(profile);

      await initDynamicRouter();
      tabsStore.setTabs([]);
      keepAliveStore.setKeepAliveName([]);
      await router.replace({ path: HOME_URL });

      ElNotification({
        title: "登录成功",
        message: `${profile.department}：只显示本岗位需要处理的功能`,
        type: "success",
        duration: 3000
      });
    } catch (error) {
      ElNotification({
        title: "登录失败",
        message: (error as Error).message,
        type: "error",
        duration: 3000
      });
    } finally {
      loading.value = false;
    }
  });
};

const resetForm = (formEl: FormInstance | undefined) => {
  if (!formEl) return;
  formEl.resetFields();
  loginForm.password = "";
};

onMounted(() => {
  loadDepartments();
  document.onkeydown = (e: KeyboardEvent) => {
    if (e.code === "Enter" || e.code === "enter" || e.code === "NumpadEnter") {
      if (loading.value) return;
      login(loginFormRef.value);
    }
  };
});

onBeforeUnmount(() => {
  document.onkeydown = null;
});
</script>

<style scoped lang="scss">
.clinic-login-form {
  :deep(.el-form-item) {
    margin-bottom: 24px;
  }

  :deep(.el-input__wrapper) {
    min-height: 48px;
    background: #f8fbfd;
    border-radius: 12px;
    box-shadow: 0 0 0 1px #dbe6ef inset;
    transition:
      background 180ms ease,
      box-shadow 180ms ease,
      transform 180ms ease;
  }

  :deep(.el-input__wrapper.is-focus) {
    background: #ffffff;
    box-shadow:
      0 0 0 1px var(--clinic-green) inset,
      0 10px 24px rgb(15 159 130 / 13%);
    transform: translateY(-1px);
  }

  :deep(.el-select) {
    width: 100%;
  }

  :deep(.el-select__wrapper) {
    min-height: 48px;
    background: #f8fbfd;
    border-radius: 12px;
    box-shadow: 0 0 0 1px #dbe6ef inset;
    transition:
      background 180ms ease,
      box-shadow 180ms ease,
      transform 180ms ease;
  }

  :deep(.el-select__wrapper.is-focused) {
    background: #ffffff;
    box-shadow:
      0 0 0 1px var(--clinic-green) inset,
      0 10px 24px rgb(15 159 130 / 13%);
    transform: translateY(-1px);
  }

  :deep(.el-select__prefix) {
    margin-right: 6px;
    color: #7a8fa2;
  }
}

.account-option {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  width: 100%;

  .account-name {
    min-width: 0;
    overflow: hidden;
    font-weight: 700;
    color: #223c56;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .account-meta {
    flex: 0 0 auto;
    max-width: 58%;
    overflow: hidden;
    font-size: 12px;
    color: #7a8fa2;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.login-btn {
  display: grid;
  grid-template-columns: 1fr 1.2fr;
  gap: 14px;
  width: 100%;
  margin-top: 34px;

  .el-button {
    width: 100%;
    height: 46px;
    margin: 0;
    font-weight: 700;
    border-radius: 999px;
  }

  :deep(.el-button--primary) {
    background: linear-gradient(135deg, #0f9f82, #0b8d9c);
    border-color: transparent;
    box-shadow: 0 14px 26px rgb(15 159 130 / 22%);
  }
}

@media screen and (width <= 600px) {
  .login-btn {
    grid-template-columns: 1fr;
  }
}
</style>
