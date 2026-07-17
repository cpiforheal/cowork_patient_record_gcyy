<template>
  <el-form ref="loginFormRef" class="clinic-login-form" :model="loginForm" :rules="loginRules" size="large">
    <el-form-item prop="department">
      <el-select
        v-model="loginForm.department"
        clearable
        filterable
        placeholder="选择科室（可选）"
        :loading="departmentLoading"
        @change="handleDepartmentChange"
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
        :disabled="accountLoading || accountOptions.length === 0"
        :loading="accountLoading"
        placeholder="选择岗位账号"
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
            <span class="account-meta">{{ account.department }}</span>
          </div>
        </el-option>
      </el-select>
    </el-form-item>
    <el-form-item prop="password">
      <el-input v-model="loginForm.password" type="password" placeholder="输入登录密码" show-password autocomplete="new-password">
        <template #prefix>
          <el-icon class="el-input__icon">
            <lock />
          </el-icon>
        </template>
      </el-input>
    </el-form-item>
  </el-form>
  <div class="login-btn">
    <el-button :icon="CircleClose" round size="large" @click="resetForm(loginFormRef)">清空</el-button>
    <el-button :icon="UserFilled" round size="large" type="primary" :loading="loading" @click="login(loginFormRef)">
      进入工作台
    </el-button>
  </div>

  <el-dialog
    v-model="forcePasswordVisible"
    title="首次登录必须修改密码"
    width="420px"
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
  >
    <el-alert title="初始密码只能用于本次引导，修改后所有旧会话会立即失效。" type="warning" :closable="false" show-icon />
    <el-form class="force-password-form" label-position="top">
      <el-form-item label="新密码">
        <el-input v-model="passwordChange.newPassword" type="password" show-password autocomplete="new-password" />
      </el-form-item>
      <el-form-item label="确认新密码">
        <el-input v-model="passwordChange.confirmPassword" type="password" show-password autocomplete="new-password" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button type="primary" :loading="passwordChanging" @click="completeForcedPasswordChange">修改并进入系统</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { HOME_URL } from "@/config";
import { Login } from "@/api/interface";
import { ElMessage, ElNotification } from "element-plus";
import {
  changePasswordApi,
  getLoginAccountsApi,
  getLoginOptionsApi,
  loginApi,
  type LoginAccountOption
} from "@/api/modules/login";
import { useUserStore } from "@/stores/modules/user";
import { useTabsStore } from "@/stores/modules/tabs";
import { useKeepAliveStore } from "@/stores/modules/keepAlive";
import { initDynamicRouter } from "@/routers/modules/dynamicRouter";
import { CircleClose, OfficeBuilding, UserFilled } from "@element-plus/icons-vue";
import type { ElForm } from "element-plus";

const router = useRouter();
const userStore = useUserStore();
const tabsStore = useTabsStore();
const keepAliveStore = useKeepAliveStore();

type FormInstance = InstanceType<typeof ElForm>;
const loginFormRef = ref<FormInstance>();
const loginRules = reactive({
  username: [{ required: true, message: "请选择登录账号", trigger: "change" }],
  password: [{ required: true, message: "请输入登录密码", trigger: "blur" }]
});

const loading = ref(false);
const departmentLoading = ref(false);
const accountLoading = ref(false);
const forcePasswordVisible = ref(false);
const passwordChanging = ref(false);
const accountOptions = ref<LoginAccountOption[]>([]);
const departmentOptions = ref<string[]>([]);
const loginForm = reactive<Login.ReqLoginForm & { department: string }>({
  department: "",
  username: "",
  password: ""
});
const passwordChange = reactive({ newPassword: "", confirmPassword: "" });

const completeLogin = async (data: Login.ResLogin) => {
  if (!data.userInfo) throw new Error("登录响应缺少用户信息");
  userStore.setToken(data.access_token);
  userStore.setUserInfo(data.userInfo);
  await initDynamicRouter();
  tabsStore.setTabs([]);
  keepAliveStore.setKeepAliveName([]);
  await router.replace({ path: HOME_URL });
  ElNotification({
    title: "登录成功",
    message: `${data.userInfo.department || "当前科室"}：仅显示本岗位已授权的功能`,
    type: "success",
    duration: 3000
  });
};

const completeForcedPasswordChange = async () => {
  if (passwordChange.newPassword.length < 8) {
    ElMessage.warning("新密码至少需要 8 位");
    return;
  }
  if (passwordChange.newPassword !== passwordChange.confirmPassword) {
    ElMessage.warning("两次输入的新密码不一致");
    return;
  }
  passwordChanging.value = true;
  try {
    await changePasswordApi({ newPassword: passwordChange.newPassword });
    const newPassword = passwordChange.newPassword;
    userStore.setToken("");
    const { data } = await loginApi({ username: loginForm.username.trim(), password: newPassword });
    forcePasswordVisible.value = false;
    passwordChange.newPassword = "";
    passwordChange.confirmPassword = "";
    loginForm.password = "";
    await completeLogin(data);
  } catch (error) {
    ElNotification({ title: "密码修改失败", message: (error as Error).message, type: "error", duration: 4000 });
  } finally {
    passwordChanging.value = false;
  }
};

const filteredAccountOptions = computed(() => accountOptions.value);

const accountLabel = (account: LoginAccountOption) => account.name;

const syncDepartmentOptions = (departmentNames: string[]) => {
  const names = new Set<string>();
  departmentNames.forEach(name => {
    if (name.trim()) names.add(name.trim());
  });
  departmentOptions.value = Array.from(names).sort((left, right) => left.localeCompare(right, "zh-Hans-CN"));
};

const loadDepartments = async () => {
  departmentLoading.value = true;
  try {
    const { data } = await getLoginOptionsApi();
    accountOptions.value = data.accounts ?? [];
    syncDepartmentOptions(data.departments);
    if (accountOptions.value.length === 1) loginForm.username = accountOptions.value[0].username;
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
  accountLoading.value = true;
  try {
    const { data } = await getLoginAccountsApi(department);
    accountOptions.value = data.accounts ?? [];
    if (accountOptions.value.length === 1) loginForm.username = accountOptions.value[0].username;
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
  accountOptions.value = [];
  await loadAccountsByDepartment(department);
};

const login = (formEl: FormInstance | undefined) => {
  if (!formEl) return;
  formEl.validate(async valid => {
    if (!valid) return;
    loading.value = true;
    try {
      const { data } = await loginApi({ username: loginForm.username.trim(), password: loginForm.password });
      userStore.setToken(data.access_token);
      if (!data.userInfo) throw new Error("登录响应缺少用户信息");
      userStore.setUserInfo(data.userInfo);
      if (data.mustChangePassword) {
        forcePasswordVisible.value = true;
        return;
      }
      await completeLogin(data);
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
  loadAccountsByDepartment(loginForm.department);
};

onMounted(() => {
  loadDepartments();
  document.onkeydown = (e: KeyboardEvent) => {
    if (e.code === "Enter" || e.code === "enter" || e.code === "NumpadEnter") {
      if (loading.value || forcePasswordVisible.value) return;
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
    margin-bottom: 20px;
  }

  :deep(.el-input__wrapper) {
    min-height: 50px;
    padding: 0 16px;
    background: #f5fcf8;
    border-radius: 999px;
    box-shadow: 0 0 0 1px #d8efe3 inset;
    transition:
      background 180ms ease,
      box-shadow 180ms ease,
      transform 180ms ease;
  }

  :deep(.el-input__wrapper.is-focus) {
    background: #ffffff;
    box-shadow:
      0 0 0 1px #26a876 inset,
      0 12px 26px rgb(38 168 118 / 14%);
    transform: translateY(-2px);
  }

  :deep(.el-select) {
    width: 100%;
  }

  :deep(.el-select__wrapper) {
    min-height: 50px;
    padding: 0 16px;
    background: #f5fcf8;
    border-radius: 999px;
    box-shadow: 0 0 0 1px #d8efe3 inset;
    transition:
      background 180ms ease,
      box-shadow 180ms ease,
      transform 180ms ease;
  }

  :deep(.el-select__wrapper.is-focused) {
    background: #ffffff;
    box-shadow:
      0 0 0 1px #26a876 inset,
      0 12px 26px rgb(38 168 118 / 14%);
    transform: translateY(-2px);
  }

  :deep(.el-select__prefix) {
    margin-right: 6px;
    color: #76a393;
  }

  :deep(.el-input__prefix) {
    color: #76a393;
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
    color: #173b35;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .account-meta {
    flex: 0 0 auto;
    max-width: 58%;
    overflow: hidden;
    font-size: 12px;
    color: #719287;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.login-btn {
  display: grid;
  grid-template-columns: 1fr 1.2fr;
  gap: 14px;
  width: 100%;
  margin-top: 30px;

  .el-button {
    width: 100%;
    height: 48px;
    margin: 0;
    font-weight: 800;
    border-radius: 999px;
  }

  :deep(.el-button:not(.el-button--primary)) {
    color: #58786d;
    background: #ffffff;
    border-color: #d8efe3;
  }

  :deep(.el-button--primary) {
    background: linear-gradient(135deg, #5fc999, #26a876);
    border-color: transparent;
    box-shadow: 0 16px 28px rgb(38 168 118 / 24%);
    transition:
      transform 180ms ease,
      box-shadow 180ms ease,
      filter 180ms ease;
  }

  :deep(.el-button--primary:hover) {
    filter: saturate(1.08);
    box-shadow: 0 18px 34px rgb(38 168 118 / 28%);
    transform: translateY(-1px);
  }
}

.force-password-form {
  margin-top: 18px;
}

@media screen and (width <= 600px) {
  .login-btn {
    grid-template-columns: 1fr;
  }
}
</style>
