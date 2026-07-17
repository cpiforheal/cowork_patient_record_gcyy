<template>
  <el-watermark id="watermark" :font="font" :content="watermark ? ['门诊信息管理平台', '操作留痕'] : ''">
    <LayoutVertical />
    <ThemeDrawer />
    <button class="doubao-global-entry" type="button" @click="assistantVisible = true">
      <span>
        <el-icon><ChatDotRound /></el-icon>
      </span>
      <strong>豆包助手</strong>
      <small>院内问答</small>
    </button>
    <AiAssistantPanel v-model="assistantVisible" assistant-type="public" title="豆包院内助手" :context="assistantContext" />
  </el-watermark>
</template>

<script setup lang="ts" name="layout">
import { computed, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ChatDotRound } from "@element-plus/icons-vue";
import { useGlobalStore } from "@/stores/modules/global";
import { useUserStore } from "@/stores/modules/user";
import AiAssistantPanel from "@/components/AiAssistantPanel/index.vue";
import ThemeDrawer from "./components/ThemeDrawer/index.vue";
import LayoutVertical from "./LayoutVertical/index.vue";

const globalStore = useGlobalStore();
const userStore = useUserStore();
const route = useRoute();

const isDark = computed(() => globalStore.isDark);
const watermark = computed(() => globalStore.watermark);
const assistantVisible = ref(false);
const assistantContext = computed(() => ({
  source: "global_layout",
  path: route.fullPath,
  pageTitle: route.meta?.title || route.name || "",
  operator: userStore.userInfo.name,
  role: userStore.userInfo.role,
  department: userStore.userInfo.department
}));

const font = reactive({ color: "rgba(0, 0, 0, .15)" });
watch(isDark, () => (font.color = isDark.value ? "rgba(255, 255, 255, .15)" : "rgba(0, 0, 0, .15)"), {
  immediate: true
});
</script>

<style scoped lang="scss">
.layout {
  min-width: 600px;
}

.doubao-global-entry {
  position: fixed;
  right: 26px;
  bottom: 28px;
  z-index: 1000;
  display: grid;
  grid-template-columns: auto 1fr;
  column-gap: 10px;
  row-gap: 1px;
  align-items: center;
  min-width: 142px;
  padding: 10px 14px 10px 10px;
  border: 1px solid rgb(77 123 99 / 0.24);
  border-radius: 999px;
  color: var(--hos-primary-deep, var(--el-color-primary));
  background: linear-gradient(135deg, rgb(255 255 255 / 0.92), rgb(245 255 250 / 0.82)), var(--el-bg-color);
  box-shadow:
    0 16px 42px rgb(48 72 58 / 0.2),
    inset 0 1px 0 rgb(255 255 255 / 0.72);
  cursor: pointer;
  backdrop-filter: blur(16px);
  transition:
    transform 180ms var(--liquid-ease, ease),
    box-shadow 180ms ease;

  &:hover {
    transform: translateY(-2px);
    box-shadow:
      0 20px 54px rgb(48 72 58 / 0.26),
      inset 0 1px 0 rgb(255 255 255 / 0.8);
  }

  &:active {
    transform: translateY(0);
  }

  span {
    grid-row: span 2;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 38px;
    height: 38px;
    border-radius: 999px;
    color: #fff;
    background: var(--hos-primary-deep, var(--el-color-primary));
    box-shadow: 0 0 0 6px rgb(77 123 99 / 0.12);
  }

  strong,
  small {
    line-height: 1.1;
    text-align: left;
    white-space: nowrap;
  }

  strong {
    font-size: 14px;
  }

  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

@media (max-width: 768px) {
  .layout {
    min-width: 0;
    padding-bottom: calc(64px + env(safe-area-inset-bottom));
  }

  .doubao-global-entry {
    right: 16px;
    bottom: calc(76px + env(safe-area-inset-bottom));
    min-width: 0;
    padding: 10px;

    strong,
    small {
      display: none;
    }

    span {
      grid-row: auto;
    }
  }
}

@media (prefers-reduced-motion: reduce) {
  .doubao-global-entry {
    transition: none;
  }
}
</style>
