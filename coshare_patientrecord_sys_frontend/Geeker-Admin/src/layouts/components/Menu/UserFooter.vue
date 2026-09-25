<template>
  <div class="user-footer">
    <el-dropdown trigger="click" placement="top-start">
      <div class="uf-trigger" :class="{ 'is-collapsed': collapsed }">
        <img class="uf-avatar" src="@/assets/images/avatar.gif" alt="" />
        <div v-show="!collapsed" class="uf-meta">
          <strong>{{ userStore.userInfo.name || "未登录" }}</strong>
          <small>{{ userStore.userInfo.department || "—" }}</small>
        </div>
      </div>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item @click="openDialog('infoRef')">
            <el-icon><User /></el-icon>账号信息
          </el-dropdown-item>
          <el-dropdown-item @click="openDialog('passwordRef')">
            <el-icon><Edit /></el-icon>修改密码
          </el-dropdown-item>
          <el-dropdown-item v-if="authStore.hasInventorySystemAccessGet && authStore.hasMedicalSystemAccessGet" @click="switchSystem">
            <el-icon><Grid /></el-icon>切换系统
          </el-dropdown-item>
          <el-dropdown-item divided @click="logout">
            <el-icon><SwitchButton /></el-icon>退出登录
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <InfoDialog ref="infoRef"></InfoDialog>
    <PasswordDialog ref="passwordRef"></PasswordDialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { LOGIN_URL } from "@/config";
import { useRouter } from "vue-router";
import { logoutApi } from "@/api/modules/login";
import { useUserStore } from "@/stores/modules/user";
import { useAuthStore } from "@/stores/modules/auth";
import { useTabsStore } from "@/stores/modules/tabs";
import { useKeepAliveStore } from "@/stores/modules/keepAlive";
import { ElMessageBox, ElMessage } from "element-plus";
import { Edit, Grid, SwitchButton, User } from "@element-plus/icons-vue";
import InfoDialog from "@/layouts/components/Header/components/InfoDialog.vue";
import PasswordDialog from "@/layouts/components/Header/components/PasswordDialog.vue";

defineProps<{ collapsed: boolean }>();

/**
 * 侧边栏底部固定用户区（参考图 Sticky Footer）。
 * 与顶栏头像共用同一套账号动作，不新增权限逻辑。
 */
const router = useRouter();
const userStore = useUserStore();
const authStore = useAuthStore();
const tabsStore = useTabsStore();
const keepAliveStore = useKeepAliveStore();

const infoRef = ref<InstanceType<typeof InfoDialog> | null>(null);
const passwordRef = ref<InstanceType<typeof PasswordDialog> | null>(null);
const openDialog = (refName: string) => {
  if (refName === "infoRef") infoRef.value?.openDialog();
  if (refName === "passwordRef") passwordRef.value?.openDialog();
};

const switchSystem = () => router.push("/system-select");

const logout = () => {
  ElMessageBox.confirm("确认退出当前账号？", "提示", {
    confirmButtonText: "确定",
    cancelButtonText: "取消",
    type: "warning"
  }).then(async () => {
    try {
      await logoutApi();
    } catch {
      ElMessage.warning("后端会话未确认清理，已先退出本机登录");
    } finally {
      userStore.setToken("");
      userStore.setUserInfo({ name: "未登录", role: "frontdesk", department: "前台" });
      await router.replace(LOGIN_URL);
      authStore.$reset();
      tabsStore.$reset();
      keepAliveStore.$reset();
      ElMessage.success("已退出登录");
    }
  });
};
</script>

<style scoped lang="scss">
.user-footer {
  padding: 6px 10px 10px;
}

.uf-trigger {
  display: flex;
  gap: 9px;
  align-items: center;
  padding: 7px 8px;
  cursor: pointer;
  border-radius: 8px;
  transition: background-color 0.2s ease;

  &:hover {
    background-color: var(--el-fill-color-light);
  }

  &.is-collapsed {
    justify-content: center;
    padding: 7px 0;
  }
}

.uf-avatar {
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 50%;
}

.uf-meta {
  min-width: 0;
  line-height: 1.25;

  strong {
    display: block;
    overflow: hidden;
    font-size: 13px;
    font-weight: 600;
    color: var(--el-text-color-primary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    display: block;
    overflow: hidden;
    font-size: 11px;
    color: var(--el-text-color-secondary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
</style>
