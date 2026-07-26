<template>
  <div class="welcome-page">
    <div class="welcome-decoration" aria-hidden="true">
      <svg viewBox="0 0 96 96" fill="none">
        <path
          d="M29 18c-9 0-16 7-16 16v25c0 11 8 19 19 19h25c12 0 21-9 21-21V35c0-9-7-16-16-16H35c-6 0-10 4-10 10v24c0 6 4 10 10 10h17c6 0 10-4 10-10V38c0-4-3-7-7-7s-7 3-7 7v10"
        />
        <circle cx="34" cy="35" r="2.2" />
        <circle cx="43" cy="35" r="2.2" />
        <path d="M34 42c3 3 7 3 10 0" />
      </svg>
    </div>

    <section class="welcome-stage">
      <img class="welcome-logo" src="@/assets/images/logo.jpg" alt="医院标识" />
      <h1>固始中医肛肠医院</h1>
      <p class="welcome-subtitle">门诊信息统一管理平台</p>
      <p class="welcome-greeting">
        {{ greeting }}，{{ userName }}
        <span class="greeting-meta">{{ roleName }} · {{ department }}</span>
      </p>
      <div class="welcome-clock">
        <strong>{{ clock }}</strong>
        <span>{{ dateText }}</span>
      </div>
    </section>

    <section class="summary-strip" aria-label="今日运行概况">
      <article v-for="tile in tiles" :key="tile.label" class="summary-tile" :class="tile.tone">
        <span class="tile-label">{{ tile.label }}</span>
        <strong class="tile-value">{{ tile.value }}</strong>
        <small class="tile-note">{{ tile.note }}</small>
      </article>
    </section>

    <p class="welcome-footnote">数据每分钟自动更新 · 以各工作台实际业务为准</p>
  </div>
</template>

<script setup lang="ts" name="welcome">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useUserStore } from "@/stores/modules/user";
import { roleLabel } from "@/config/fieldPermissions";
import { getTimeState } from "@/utils";
import { getHomeSummaryApi, type HomeSummary } from "@/api/modules/clinic/homeSummary";

const userStore = useUserStore();
const userName = computed(() => userStore.userInfo.name || "同事");
const roleName = computed(() => roleLabel(userStore.userInfo.role));
const department = computed(() => userStore.userInfo.department || "门诊");

const greeting = ref(getTimeState());
const clock = ref("");
const dateText = ref("");
let clockTimer = 0;

function tick() {
  const now = new Date();
  clock.value = now.toLocaleTimeString("zh-CN", { hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit" });
  dateText.value = now.toLocaleDateString("zh-CN", { year: "numeric", month: "long", day: "numeric", weekday: "long" });
  greeting.value = getTimeState();
}

const summary = ref<HomeSummary>();
const SUMMARY_REFRESH_MS = 60 * 1000;
let summaryTimer = 0;

async function loadSummary() {
  try {
    const { data } = await getHomeSummaryApi();
    summary.value = data;
  } catch {
    // 欢迎页为纯展示：概况拉取失败保持"—"占位，绝不打扰用户。
  }
}

const tiles = computed(() => {
  const value = (key: keyof Omit<HomeSummary, "serverTime">) => (summary.value === undefined ? "—" : String(summary.value[key]));
  return [
    { label: "今日登记", value: value("todayRegistered"), note: "就诊登记", tone: "tone-blue" },
    { label: "当前候诊", value: value("queueWaiting"), note: "检查 + 接诊", tone: "tone-teal" },
    { label: "今日完成", value: value("queueCompletedToday"), note: "已完成就诊", tone: "tone-green" },
    { label: "待取药", value: value("tcmReady"), note: "中药房", tone: "tone-amber" },
    { label: "制作中", value: value("tcmInProgress"), note: "调剂 / 代煎", tone: "tone-plain" }
  ];
});

function handleVisibilityChange() {
  if (document.visibilityState === "visible") void loadSummary();
}

onMounted(() => {
  tick();
  clockTimer = window.setInterval(tick, 1000);
  void loadSummary();
  summaryTimer = window.setInterval(() => {
    if (document.visibilityState === "visible") void loadSummary();
  }, SUMMARY_REFRESH_MS);
  document.addEventListener("visibilitychange", handleVisibilityChange);
});
onBeforeUnmount(() => {
  window.clearInterval(clockTimer);
  window.clearInterval(summaryTimer);
  document.removeEventListener("visibilitychange", handleVisibilityChange);
});
</script>

<style scoped lang="scss">
.welcome-page {
  position: relative;
  min-height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 34px;
  padding: 40px 24px 32px;
  overflow: hidden;
  box-sizing: border-box;
  border-radius: 12px;
  background:
    radial-gradient(circle at 8% 6%, rgba(198, 248, 221, 0.5), transparent 30%),
    radial-gradient(circle at 94% 90%, rgba(151, 220, 246, 0.28), transparent 32%),
    radial-gradient(circle at 88% 10%, rgba(255, 224, 181, 0.16), transparent 20%),
    linear-gradient(160deg, var(--el-bg-color) 0%, var(--el-fill-color-light) 100%);
}
.welcome-decoration {
  position: absolute;
  right: 3%;
  bottom: 4%;
  width: clamp(120px, 14vw, 200px);
  color: var(--hos-primary, #0f766e);
  opacity: 0.07;
  pointer-events: none;
  transform: rotate(-6deg);
  svg {
    width: 100%;
    stroke: currentColor;
    stroke-width: 5;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  circle {
    fill: currentColor;
    stroke: none;
  }
}
.welcome-stage {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
}
.welcome-logo {
  width: clamp(96px, 12vw, 148px);
  height: clamp(96px, 12vw, 148px);
  object-fit: cover;
  border: 4px solid var(--el-bg-color);
  border-radius: 28%;
  box-shadow:
    0 0 0 1px rgba(15, 118, 110, 0.14),
    0 18px 44px rgba(15, 118, 110, 0.18);
  margin-bottom: 10px;
}
.welcome-stage h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: clamp(28px, 3.2vw, 42px);
  font-weight: 800;
  letter-spacing: 0.12em;
}
.welcome-subtitle {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: clamp(14px, 1.4vw, 18px);
  font-weight: 600;
  letter-spacing: 0.3em;
  text-indent: 0.3em;
}
.welcome-greeting {
  margin: 14px 0 0;
  color: var(--el-text-color-primary);
  font-size: clamp(16px, 1.6vw, 20px);
  font-weight: 600;
}
.greeting-meta {
  margin-left: 10px;
  padding: 3px 10px;
  border-radius: 999px;
  color: var(--hos-primary, #0f766e);
  background: rgba(15, 118, 110, 0.08);
  font-size: 0.8em;
  font-weight: 600;
  white-space: nowrap;
}
.welcome-clock {
  display: flex;
  align-items: baseline;
  gap: 14px;
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  strong {
    color: var(--el-text-color-primary);
    font-size: clamp(26px, 2.6vw, 36px);
    font-variant-numeric: tabular-nums;
    letter-spacing: 0.04em;
  }
  span {
    font-size: clamp(13px, 1.2vw, 15px);
  }
}
.summary-strip {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(5, minmax(128px, 172px));
  gap: 16px;
}
.summary-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 18px 14px 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 14px;
  background: var(--el-bg-color);
  box-shadow: 0 6px 18px rgba(15, 118, 110, 0.06);
}
.tile-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.08em;
}
.tile-value {
  font-size: clamp(28px, 2.6vw, 38px);
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}
.tile-note {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}
.tone-blue .tile-value {
  color: #087fa9;
}
.tone-teal .tile-value {
  color: var(--hos-primary, #0f766e);
}
.tone-green .tile-value {
  color: #2f9461;
}
.tone-amber .tile-value {
  color: #b97a08;
}
.tone-plain .tile-value {
  color: var(--el-text-color-primary);
}
.welcome-footnote {
  position: relative;
  z-index: 1;
  margin: 0;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
  letter-spacing: 0.06em;
}
@media (max-width: 1080px) {
  .summary-strip {
    grid-template-columns: repeat(3, minmax(120px, 1fr));
    width: min(560px, 92%);
  }
}
@media (max-width: 640px) {
  .welcome-page {
    gap: 26px;
    padding: 28px 14px 22px;
  }
  .summary-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    width: 100%;
  }
}
</style>
