<template>
  <Maximize v-show="maximize" />
  <Tabs v-show="tabs" />
  <el-main>
    <router-view v-slot="{ Component, route }">
      <transition appear name="fade-transform" mode="default">
        <keep-alive :include="keepAliveName">
          <component :is="Component" v-if="Component && isRouterShow" :key="route.name || route.path" />
          <div v-else class="route-loading-placeholder" aria-live="polite">
            <span class="route-loading-placeholder__dot"></span>
            <span>页面加载中</span>
          </div>
        </keep-alive>
      </transition>
    </router-view>
  </el-main>
  <el-footer v-show="footer">
    <Footer />
  </el-footer>
</template>

<script setup lang="ts">
import { ref, onBeforeUnmount, onMounted, provide, watch } from "vue";
import { storeToRefs } from "pinia";
import { useDebounceFn } from "@vueuse/core";
import { ElNotification } from "element-plus";
import { authHeaders, handleUnauthorizedResponse } from "@/api/modules/authToken";
import { clinicFetch } from "@/api/modules/clinic/http";
import { useGlobalStore } from "@/stores/modules/global";
import { useKeepAliveStore } from "@/stores/modules/keepAlive";
import Maximize from "./components/Maximize.vue";
import Tabs from "@/layouts/components/Tabs/index.vue";
import Footer from "@/layouts/components/Footer/index.vue";

const globalStore = useGlobalStore();
const { maximize, isCollapse, layout, tabs, footer } = storeToRefs(globalStore);

const keepAliveStore = useKeepAliveStore();
const { keepAliveName } = storeToRefs(keepAliveStore);

// 注入刷新页面方法
const isRouterShow = ref(true);
const refreshCurrentPage = (val: boolean) => (isRouterShow.value = val);
provide("refresh", refreshCurrentPage);

let queueUpdateAbort: AbortController | undefined;
let queueUpdateTimer: number | undefined;
let queueUpdateVersion = 1;
let queueUpdateActive = false;
let queueUpdateFailCount = 0;
const isInventoryPortal = import.meta.env.VITE_PORTAL_MODE === "inventory";

const scheduleQueueUpdateWait = (delay = 200) => {
  if (!queueUpdateActive) return;
  queueUpdateTimer = window.setTimeout(() => void waitForQueueUpdate(), delay);
};

const scheduleQueueUpdateBackoff = () => {
  queueUpdateFailCount = Math.min(queueUpdateFailCount + 1, 10);
  scheduleQueueUpdateWait(Math.min(3000 * queueUpdateFailCount, 30000));
};

const waitForQueueUpdate = async () => {
  if (!queueUpdateActive) return;
  if (document.visibilityState !== "visible") {
    scheduleQueueUpdateWait(5000);
    return;
  }
  queueUpdateAbort = new AbortController();
  try {
    const response = await clinicFetch(`/clinic-queue/updates/wait?after=${queueUpdateVersion}`, {
      headers: authHeaders(),
      signal: queueUpdateAbort.signal
    });
    if (response.status === 401) handleUnauthorizedResponse();
    if (response.status === 403) {
      // 会话受限（如首登强制改密）或网关拦截长轮询：停止轮询，避免固定 200ms 重试刷屏
      queueUpdateActive = false;
      return;
    }
    if (!response.ok) {
      scheduleQueueUpdateBackoff();
      return;
    }
    queueUpdateFailCount = 0;
    const nextVersion = Number(response.headers.get("X-Clinic-Queue-Version"));
    const changed = response.headers.get("X-Clinic-Queue-Changed") === "true";
    if (Number.isFinite(nextVersion) && nextVersion > queueUpdateVersion) queueUpdateVersion = nextVersion;
    if (changed) {
      window.dispatchEvent(new CustomEvent("clinic-queue-updated", { detail: { version: queueUpdateVersion } }));
      ElNotification({ title: "业务待办已更新", message: "前台或岗位已更新患者流程，已同步最新待办。", type: "info", duration: 3200 });
    }
  } catch (error: any) {
    if (error?.name === "AuthExpiredError") return;
    if (error?.name !== "AbortError" && queueUpdateActive) scheduleQueueUpdateBackoff();
    return;
  } finally {
    queueUpdateAbort = undefined;
  }
  scheduleQueueUpdateWait();
};

const resumeQueueUpdateWait = () => {
  if (document.visibilityState === "visible" && !queueUpdateAbort) scheduleQueueUpdateWait(0);
};

// 监听当前页面是否最大化，动态添加 class
watch(
  () => maximize.value,
  () => {
    const app = document.getElementById("app") as HTMLElement;
    if (maximize.value) app.classList.add("main-maximize");
    else app.classList.remove("main-maximize");
  },
  { immediate: true }
);

// 监听布局变化，在 body 上添加相对应的 layout class
watch(
  () => layout.value,
  () => {
    const body = document.body as HTMLElement;
    body.setAttribute("class", layout.value);
  },
  { immediate: true }
);

// 监听窗口大小变化，折叠侧边栏
const screenWidth = ref(0);
const listeningWindow = useDebounceFn(() => {
  screenWidth.value = document.body.clientWidth;
  if (!isCollapse.value && screenWidth.value < 1200) globalStore.setGlobalState("isCollapse", true);
  if (isCollapse.value && screenWidth.value > 1200) globalStore.setGlobalState("isCollapse", false);
}, 100);
window.addEventListener("resize", listeningWindow, false);
onMounted(() => {
  if (isInventoryPortal) return;
  queueUpdateActive = true;
  queueUpdateFailCount = 0;
  scheduleQueueUpdateWait(0);
  document.addEventListener("visibilitychange", resumeQueueUpdateWait);
});
onBeforeUnmount(() => {
  window.removeEventListener("resize", listeningWindow);
  document.removeEventListener("visibilitychange", resumeQueueUpdateWait);
  queueUpdateActive = false;
  queueUpdateAbort?.abort();
  if (queueUpdateTimer) clearTimeout(queueUpdateTimer);
});
</script>

<style scoped lang="scss">
@use "./index.scss" as *;
</style>
