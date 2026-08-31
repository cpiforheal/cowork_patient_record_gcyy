<template>
  <div ref="rootRef" class="welcome-page" :class="{ 'is-splash': splashing }" :data-sky="sky.key" :data-sky-text="sky.text">
    <!-- 天空背景：主题切换时旧背景淡出、新背景淡入 -->
    <transition name="sky-fade">
      <div :key="sky.key" class="sky-layer" :style="{ background: sky.bg }"></div>
    </transition>

    <!-- 日月星辰装饰 -->
    <div class="celestial" aria-hidden="true">
      <template v-if="sky.celestial === 'sun'">
        <div class="sun">
          <i v-for="ray in 8" :key="ray" class="sun-ray" :style="{ transform: `rotate(${ray * 45}deg)` }"></i>
        </div>
      </template>
      <template v-else-if="sky.celestial === 'glow'">
        <div class="horizon-glow"></div>
        <div class="sun low"></div>
      </template>
      <template v-else>
        <div class="moon"></div>
        <i v-for="star in 6" :key="star" class="star" :class="`star-${star}`"></i>
      </template>
    </div>

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
        <span class="solar-term">{{ solarTerm }}</span>
      </div>
      <div class="care-line">
        <span v-if="paceChip" class="pace-chip" :class="`pace-${paceChip.tone}`">{{ paceChip.label }}</span>
        <transition name="care-fade" mode="out-in">
          <p :key="careTip" class="care-tip">{{ careTip }}</p>
        </transition>
      </div>

      <div class="welcome-archive-entry">
        <button v-if="canOpenPatientArchive" class="archive-direct-btn" type="button" @click.stop="openPatientArchive">
          <span class="archive-direct-glyph">档</span>
          <span class="archive-direct-copy">
            <strong>患者主档案 · 直达</strong>
            <small>点击选择患者，直接进入登记与事实采集</small>
          </span>
          <span class="archive-direct-arrow">›</span>
        </button>
      </div>
    </section>

    <el-dialog
      v-model="archiveDialogOpen"
      width="min(640px, 92vw)"
      append-to-body
      destroy-on-close
      class="welcome-archive-dialog"
    >
      <template #header>
        <div>
          <strong>患者主档案 · 选择患者进入</strong>
          <small style="display: block; color: var(--el-text-color-secondary)">按姓名检索，点击进入该患者的登记与事实采集</small>
        </div>
      </template>
      <div v-loading="archiveLoading" class="archive-picker">
        <el-input v-model="archiveSearch" placeholder="输入患者姓名检索" clearable />
        <div class="archive-picker-list">
          <div v-for="item in archiveFiltered" :key="item.id" class="archive-picker-row" @click="enterPatient(item)">
            <div class="archive-picker-main">
              <strong>{{ item.patientName }}</strong>
              <small>{{ item.gender || "—" }} · {{ item.age || "—" }} · 共 {{ item.visitCount }} 次就诊 · 更新于 {{ item.updatedAt }}</small>
            </div>
            <el-button size="small" type="primary" plain>进入</el-button>
          </div>
          <el-empty v-if="!archiveFiltered.length" description="未找到匹配患者" :image-size="56" />
        </div>
      </div>
    </el-dialog>

    <section class="summary-strip" aria-label="今日运行概况">
      <button
        v-for="tile in tiles"
        :key="tile.label"
        class="summary-tile"
        :class="[tile.tone, { 'is-clickable': tile.path }]"
        type="button"
        @click="openTile(tile)"
      >
        <span class="tile-label">{{ tile.label }}</span>
        <strong class="tile-value">{{ tile.value }}</strong>
        <small class="tile-note">{{ tile.note }}</small>
      </button>
    </section>

    <p class="welcome-footnote">数据每分钟自动更新 · 以各工作台实际业务为准</p>
    <transition name="care-fade">
      <p v-if="splashing" class="splash-hint">点击任意处进入工作台</p>
    </transition>
  </div>
</template>

<script setup lang="ts" name="welcome">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useUserStore } from "@/stores/modules/user";
import { useAuthStore } from "@/stores/modules/auth";
import { Search } from "@element-plus/icons-vue";
import { getPreAiPatientCasesApi, type PreAiPatientCase } from "@/api/modules/clinic/preAi";
import { ElMessage } from "element-plus";
import { roleLabel } from "@/config/fieldPermissions";
import { getHomeSummaryApi, type HomeSummary } from "@/api/modules/clinic/homeSummary";

const router = useRouter();
const userStore = useUserStore();
const authStore = useAuthStore();

const archiveDialogOpen = ref(false);
const archiveLoading = ref(false);
const archiveSearch = ref("");
const archiveCases = ref<PreAiPatientCase[]>([]);
const canOpenPatientArchive = computed(() => {
  const menus = authStore.flatMenuListGet || [];
  return menus.some((item: { path?: string }) => String(item?.path || "") === "/pre-ai/encounters");
});
const archiveFiltered = computed(() => {
  const keyword = archiveSearch.value.trim();
  if (!keyword) return archiveCases.value;
  return archiveCases.value.filter(item => String(item.patientName || "").includes(keyword));
});
const openPatientArchive = async () => {
  archiveDialogOpen.value = true;
  archiveLoading.value = true;
  try {
    const { data } = await getPreAiPatientCasesApi();
    archiveCases.value = data.list || [];
  } catch {
    archiveCases.value = [];
    ElMessage.error("患者主档案加载失败");
  } finally {
    archiveLoading.value = false;
  }
};
const enterPatient = (item: PreAiPatientCase) => {
  const encounterId = item.latestEncounter?.id || "";
  if (!encounterId) {
    ElMessage.warning("该患者暂无进行中就诊，请先在登记与事实采集页新建就诊");
    return;
  }
  archiveDialogOpen.value = false;
  void router.push(`/pre-ai/encounters?encounterId=${encodeURIComponent(encounterId)}`);
};
const userName = computed(() => userStore.userInfo.name || "同事");
const roleName = computed(() => roleLabel(userStore.userInfo.role));
const department = computed(() => userStore.userInfo.department || "门诊");

interface SummaryTile {
  label: string;
  value: string;
  note: string;
  tone: "tone-blue" | "tone-teal" | "tone-green" | "tone-amber" | "tone-plain";
  path?: string;
}

/* ---------------- 时钟与问候 ---------------- */

const now = ref(new Date());
const clock = ref("");
const dateText = ref("");
let clockTimer = 0;

function tick() {
  now.value = new Date();
  clock.value = now.value.toLocaleTimeString("zh-CN", {
    hour12: false,
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  });
  dateText.value = now.value.toLocaleDateString("zh-CN", {
    year: "numeric",
    month: "long",
    day: "numeric",
    weekday: "long"
  });
}

const greeting = computed(() => {
  const hour = now.value.getHours();
  if (hour < 6) return "凌晨好";
  if (hour < 9) return "早上好";
  if (hour < 12) return "上午好";
  if (hour < 14) return "中午好";
  if (hour < 18) return "下午好";
  return "晚上好";
});

/* ---------------- 24 小时天空主题 ---------------- */

interface SkyTheme {
  key: string;
  bg: string;
  text: "dark" | "light";
  celestial: "sun" | "glow" | "moon";
}

const SKY_THEMES: Record<string, SkyTheme> = {
  night: {
    key: "night",
    bg: "linear-gradient(172deg, #0a1730 0%, #11264a 55%, #1a3a5a 100%)",
    text: "light",
    celestial: "moon"
  },
  dawn: {
    key: "dawn",
    bg: "linear-gradient(168deg, #31406b 0%, #8a6d9e 42%, #eaa97e 78%, #f9e0ba 100%)",
    text: "light",
    celestial: "glow"
  },
  morning: {
    key: "morning",
    bg: "linear-gradient(165deg, #d8f0fd 0%, #f4fbff 55%, #fbf6e6 100%)",
    text: "dark",
    celestial: "sun"
  },
  noon: {
    key: "noon",
    bg: "linear-gradient(170deg, #e6f6ff 0%, #ffffff 50%, #f0faf3 100%)",
    text: "dark",
    celestial: "sun"
  },
  afternoon: {
    key: "afternoon",
    bg: "linear-gradient(165deg, #fdeecb 0%, #fff8e9 48%, #edf6ef 100%)",
    text: "dark",
    celestial: "sun"
  },
  dusk: {
    key: "dusk",
    bg: "linear-gradient(168deg, #f6c48e 0%, #f0997a 48%, #a96b92 88%, #7a5a8a 100%)",
    text: "light",
    celestial: "glow"
  },
  evening: {
    key: "evening",
    bg: "linear-gradient(170deg, #232f56 0%, #37436f 55%, #4d4a80 100%)",
    text: "light",
    celestial: "moon"
  }
};

const sky = computed<SkyTheme>(() => {
  const hour = now.value.getHours() + now.value.getMinutes() / 60;
  if (hour < 5) return SKY_THEMES.night;
  if (hour < 7) return SKY_THEMES.dawn;
  if (hour < 11) return SKY_THEMES.morning;
  if (hour < 14) return SKY_THEMES.noon;
  if (hour < 17) return SKY_THEMES.afternoon;
  if (hour < 19.5) return SKY_THEMES.dusk;
  if (hour < 22) return SKY_THEMES.evening;
  return SKY_THEMES.night;
});

/* ---------------- 今日节气（按公历近似日期查表） ---------------- */

const SOLAR_TERMS: Array<[number, number, string]> = [
  [1, 5, "小寒"],
  [1, 20, "大寒"],
  [2, 4, "立春"],
  [2, 19, "雨水"],
  [3, 5, "惊蛰"],
  [3, 20, "春分"],
  [4, 5, "清明"],
  [4, 20, "谷雨"],
  [5, 5, "立夏"],
  [5, 21, "小满"],
  [6, 5, "芒种"],
  [6, 21, "夏至"],
  [7, 7, "小暑"],
  [7, 22, "大暑"],
  [8, 7, "立秋"],
  [8, 23, "处暑"],
  [9, 7, "白露"],
  [9, 23, "秋分"],
  [10, 8, "寒露"],
  [10, 23, "霜降"],
  [11, 7, "立冬"],
  [11, 22, "小雪"],
  [12, 7, "大雪"],
  [12, 21, "冬至"]
];

const solarTerm = computed(() => {
  const month = now.value.getMonth() + 1;
  const day = now.value.getDate();
  let current = "冬至";
  for (const [m, d, name] of SOLAR_TERMS) {
    if (month > m || (month === m && day >= d)) current = name;
  }
  return `${current} · 顺时养生`;
});

/* ---------------- 今日运行概况 ---------------- */

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

const tiles = computed<SummaryTile[]>(() => {
  const value = (key: keyof Omit<HomeSummary, "serverTime">) => (summary.value === undefined ? "—" : String(summary.value[key]));
  return [
    { label: "今日登记", value: value("todayRegistered"), note: "就诊登记", tone: "tone-blue", path: "/pre-ai/encounters" },
    { label: "当前候诊", value: value("queueWaiting"), note: "检查 + 接诊", tone: "tone-teal" },
    { label: "今日完成", value: value("queueCompletedToday"), note: "已完成就诊", tone: "tone-green" },
    { label: "待取药", value: value("tcmReady"), note: "中药房", tone: "tone-amber" },
    { label: "制作中", value: value("tcmInProgress"), note: "调剂 / 代煎", tone: "tone-plain" }
  ];
});

function openTile(tile: SummaryTile) {
  if (!tile.path || splashing.value) return;
  void router.push(tile.path);
}

/* ---------------- 忙闲节奏与关怀语 ---------------- */

type Pace = "calm" | "steady" | "busy";

const pace = computed<Pace | null>(() => {
  if (!summary.value) return null;
  const load = summary.value.queueWaiting + summary.value.tcmReady;
  if (load >= 10) return "busy";
  if (load <= 2) return "calm";
  return "steady";
});

const paceChip = computed(() => {
  if (pace.value === "busy") return { label: "今日节奏 · 繁忙", tone: "busy" };
  if (pace.value === "calm") return { label: "今日节奏 · 从容", tone: "calm" };
  if (pace.value === "steady") return { label: "今日节奏 · 平稳", tone: "steady" };
  return null;
});

/** 关怀语文案池：按时段 + 忙闲组合，院方可在此自行增改。 */
const CARE_BY_SLOT: Record<string, string[]> = {
  night: ["夜深了，值守辛苦，注意保暖", "夜班的灯，也是患者的安心", "低峰时段，让眼睛休息片刻"],
  dawn: ["晨光初现，新的一天慢慢来", "开诊前，先给自己倒杯温水", "早班到岗辛苦了，今天也会顺利的"],
  morning: ["上午门诊高峰，记得抽空喝水", "接诊间隙起身活动两分钟", "微笑是给患者最好的第一句话"],
  noon: ["午间小憩十分钟，下午更有精神", "按时吃午饭，肠胃健康从自己做起", "晒晒太阳，缓一缓上午的疲惫"],
  afternoon: ["午后容易倦，伸个懒腰再继续", "复杂病历不着急，逐项核对更稳妥", "给候诊久的患者一句安抚，胜过十句解释"],
  dusk: ["夕阳正好，今天的辛苦快到站了", "下班前记得交接与锁屏", "收尾工作慢慢做，别赶"],
  evening: ["今天辛苦了，整理好台面明天更轻松", "离开前确认无未提交的草稿", "晚间值守的同事，晚饭要按时吃"]
};

const CARE_BY_PACE: Record<Pace, string[]> = {
  busy: ["候诊的患者有点多，一步一步来", "忙起来也别忘了喝口水", "先深呼吸，再叫下一位", "高峰时段，同事之间多搭把手"],
  calm: ["此刻不忙，正好整理一下台面", "难得从容，给自己泡杯茶", "空闲时翻翻操作指引，忙时更从容"],
  steady: []
};

const tipIndex = ref(0);
let tipTimer = 0;

const careTip = computed(() => {
  const slotPool = CARE_BY_SLOT[sky.value.key] ?? CARE_BY_SLOT.morning;
  const pacePool = pace.value ? CARE_BY_PACE[pace.value] : [];
  const pool = [...pacePool, ...slotPool];
  return pool[tipIndex.value % pool.length];
});

/* ---------------- 开屏动画：首次进入全屏展示后收纳回工作区 ---------------- */

const SPLASH_KEY = "welcome-splash-played";
const SPLASH_SETTLE_MS = 900;

const rootRef = ref<HTMLElement>();
const splashing = ref(false);
let splashSettleTimer = 0;

function playSplash() {
  const el = rootRef.value;
  if (!el) return;
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  try {
    if (window.sessionStorage.getItem(SPLASH_KEY) === "1") return;
    window.sessionStorage.setItem(SPLASH_KEY, "1");
  } catch {
    return;
  }
  splashing.value = true;
  el.style.position = "fixed";
  el.style.top = "0";
  el.style.left = "0";
  el.style.width = "100vw";
  el.style.height = "100vh";
  el.style.zIndex = "3000";
  el.style.borderRadius = "0";
  // 全屏常驻展示，由用户点击任意处触发回收。
  el.addEventListener("click", settleSplash, { once: true });
}

/** 回收动画：从全屏过渡到布局主区域的实际位置，结束后交还文档流。 */
function settleSplash() {
  if (!splashing.value) return;
  splashing.value = false;
  const el = rootRef.value;
  const target = el?.parentElement?.getBoundingClientRect();
  if (!el || !target || target.width < 80) {
    finishSplash();
    return;
  }
  const ease = "cubic-bezier(0.25, 0.8, 0.3, 1)";
  el.style.transition = `top ${SPLASH_SETTLE_MS}ms ${ease}, left ${SPLASH_SETTLE_MS}ms ${ease}, width ${SPLASH_SETTLE_MS}ms ${ease}, height ${SPLASH_SETTLE_MS}ms ${ease}, border-radius ${SPLASH_SETTLE_MS}ms ${ease}`;
  el.style.top = `${target.top}px`;
  el.style.left = `${target.left}px`;
  el.style.width = `${target.width}px`;
  el.style.height = `${target.height}px`;
  el.style.borderRadius = "12px";
  splashSettleTimer = window.setTimeout(finishSplash, SPLASH_SETTLE_MS + 60);
}

function finishSplash() {
  splashing.value = false;
  const el = rootRef.value;
  if (el) el.style.cssText = "";
}

/* ---------------- 生命周期 ---------------- */

function handleVisibilityChange() {
  if (document.visibilityState === "visible") void loadSummary();
}

onMounted(() => {
  tick();
  clockTimer = window.setInterval(tick, 1000);
  tipTimer = window.setInterval(() => (tipIndex.value += 1), 30 * 1000);
  void loadSummary();
  summaryTimer = window.setInterval(() => {
    if (document.visibilityState === "visible") void loadSummary();
  }, SUMMARY_REFRESH_MS);
  document.addEventListener("visibilitychange", handleVisibilityChange);
  playSplash();
});
onBeforeUnmount(() => {
  window.clearInterval(clockTimer);
  window.clearInterval(summaryTimer);
  window.clearInterval(tipTimer);
  window.clearTimeout(splashSettleTimer);
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
  gap: 32px;
  padding: 40px 24px 32px;
  overflow: hidden;
  box-sizing: border-box;
  border-radius: 12px;
}
.sky-layer {
  position: absolute;
  inset: 0;
  z-index: 0;
}
.sky-fade-enter-active,
.sky-fade-leave-active {
  transition: opacity 3s ease;
}
.sky-fade-enter-from,
.sky-fade-leave-to {
  opacity: 0;
}

/* ---------- 日月星辰 ---------- */
.celestial {
  position: absolute;
  top: 6%;
  right: 8%;
  z-index: 0;
  pointer-events: none;
}
.sun {
  position: relative;
  width: 74px;
  height: 74px;
  border-radius: 50%;
  background: radial-gradient(circle at 38% 35%, #fff4c9, #ffd76e 62%, #f7b93e);
  box-shadow: 0 0 46px rgba(255, 199, 89, 0.55);
  animation: float-slow 7s ease-in-out infinite alternate;
}
.sun-ray {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 3px;
  height: 108px;
  margin: -54px 0 0 -1.5px;
  background: linear-gradient(to bottom, rgba(255, 205, 100, 0.55), transparent 42%, transparent 58%, rgba(255, 205, 100, 0.55));
  border-radius: 3px;
}
.sun.low {
  width: 92px;
  height: 92px;
  opacity: 0.9;
  box-shadow: 0 0 70px rgba(255, 170, 96, 0.65);
  background: radial-gradient(circle at 40% 35%, #ffe6b0, #ffb46a 60%, #f08a4b);
}
.horizon-glow {
  position: absolute;
  top: -30px;
  right: -60px;
  width: 300px;
  height: 300px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 176, 104, 0.4), transparent 65%);
}
.moon {
  width: 62px;
  height: 62px;
  border-radius: 50%;
  background: radial-gradient(circle at 36% 34%, #fdfbee, #ece5c8 70%, #d9d0ab);
  box-shadow:
    inset -12px -8px 0 rgba(178, 170, 140, 0.28),
    0 0 34px rgba(240, 232, 190, 0.35);
  animation: float-slow 8s ease-in-out infinite alternate;
}
.star {
  position: absolute;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #f4efd8;
  animation: twinkle 3.2s ease-in-out infinite;
}
.star-1 {
  top: -14px;
  left: -76px;
}
.star-2 {
  top: 44px;
  left: -110px;
  width: 3px;
  height: 3px;
  animation-delay: 0.7s;
}
.star-3 {
  top: 96px;
  left: -38px;
  animation-delay: 1.3s;
}
.star-4 {
  top: 10px;
  left: 96px;
  width: 3px;
  height: 3px;
  animation-delay: 1.9s;
}
.star-5 {
  top: 118px;
  left: 70px;
  animation-delay: 2.4s;
}
.star-6 {
  top: -32px;
  left: 30px;
  width: 3px;
  height: 3px;
  animation-delay: 0.4s;
}

@keyframes float-slow {
  from {
    transform: translateY(0);
  }
  to {
    transform: translateY(10px);
  }
}
@keyframes twinkle {
  0%,
  100% {
    opacity: 0.25;
  }
  50% {
    opacity: 1;
  }
}

/* ---------- 主体 ---------- */
.welcome-decoration {
  position: absolute;
  right: 3%;
  bottom: 4%;
  z-index: 0;
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
  border: 4px solid rgba(255, 255, 255, 0.92);
  border-radius: 28%;
  box-shadow:
    0 0 0 1px rgba(15, 118, 110, 0.14),
    0 18px 44px rgba(15, 60, 80, 0.24);
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
  background: rgba(15, 118, 110, 0.1);
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
.solar-term {
  padding: 2px 10px;
  border: 1px solid rgba(15, 118, 110, 0.24);
  border-radius: 999px;
  color: var(--hos-primary, #0f766e);
  font-weight: 600;
}
.care-line {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 28px;
  margin-top: 12px;
}
.pace-chip {
  flex: 0 0 auto;
  padding: 3px 11px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.05em;
}
.pace-calm {
  color: #2f7d5f;
  background: rgba(96, 200, 150, 0.18);
}
.pace-steady {
  color: #1f6e96;
  background: rgba(80, 170, 220, 0.18);
}
.pace-busy {
  color: #b05a1c;
  background: rgba(250, 170, 90, 0.24);
}
.care-tip {
  margin: 0;
  color: var(--el-text-color-regular);
  font-size: clamp(14px, 1.3vw, 16px);
  letter-spacing: 0.04em;
}
.care-fade-enter-active,
.care-fade-leave-active {
  transition:
    opacity 0.6s ease,
    transform 0.6s ease;
}
.care-fade-enter-from {
  opacity: 0;
  transform: translateY(6px);
}
.care-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

/* ---------- 数据带 ---------- */
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
  border: 1px solid rgba(255, 255, 255, 0.55);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(8px);
  box-shadow: 0 6px 18px rgba(15, 60, 80, 0.08);
  appearance: none;
  color: inherit;
  font: inherit;
  text-align: center;
  transition:
    background 3s ease,
    border-color 3s ease,
    box-shadow 180ms ease,
    transform 180ms ease;
}
.summary-tile.is-clickable {
  cursor: pointer;
}
.summary-tile.is-clickable:hover {
  border-color: rgba(8, 127, 169, 0.28);
  box-shadow: 0 10px 24px rgba(15, 60, 80, 0.12);
  transform: translateY(-2px);
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

.welcome-archive-entry {
  margin-top: 18px;
  display: flex;
  justify-content: center;
}
.archive-direct-btn {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 13px 22px;
  border: 1px solid rgb(255 255 255 / 45%);
  border-radius: 999px;
  background: rgb(255 255 255 / 14%);
  backdrop-filter: blur(6px);
  cursor: pointer;
  color: inherit;
  text-align: left;
  transition: background 0.18s linear, transform 0.18s linear, border-color 0.18s linear;
}
.archive-direct-btn:hover {
  background: rgb(255 255 255 / 24%);
  border-color: rgb(255 255 255 / 75%);
  transform: translateY(-1px);
}
.archive-direct-glyph {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  font-weight: 800;
  font-size: 17px;
  background: rgb(255 255 255 / 22%);
}
.archive-direct-copy {
  display: grid;
  gap: 2px;

  strong {
    font-size: 15px;
  }

  small {
    font-size: 12px;
    opacity: 0.78;
  }
}
.archive-direct-arrow {
  font-size: 20px;
  opacity: 0.8;
}
.welcome-archive-dialog .archive-picker-list {
  margin-top: 10px;
  max-height: 46vh;
  overflow-y: auto;
  display: grid;
  gap: 6px;
}
.welcome-archive-dialog .archive-picker-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  cursor: pointer;
  transition: border-color 0.18s linear, background 0.18s linear;
}
.welcome-archive-dialog .archive-picker-row:hover {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.welcome-archive-dialog .archive-picker-main {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 2px;

  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}
.welcome-footnote {
  position: relative;
  z-index: 1;
  margin: 0;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
  letter-spacing: 0.06em;
}

/* ---------- 开屏动画 ---------- */
.welcome-stage,
.summary-strip {
  transition: transform 0.9s cubic-bezier(0.25, 0.8, 0.3, 1);
}
.welcome-page.is-splash {
  .welcome-stage {
    transform: scale(1.06);
  }
  .summary-strip {
    transform: scale(1.03);
  }
}
.splash-hint {
  position: absolute;
  bottom: 3.5%;
  left: 50%;
  z-index: 2;
  margin: 0;
  padding: 6px 16px;
  transform: translateX(-50%);
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.85);
  background: rgba(10, 30, 46, 0.35);
  backdrop-filter: blur(6px);
  font-size: 13px;
  letter-spacing: 0.08em;
  pointer-events: none;
  animation: hint-breathe 2.2s ease-in-out infinite;
}
.welcome-page[data-sky-text="dark"] .splash-hint {
  color: rgba(30, 60, 75, 0.85);
  background: rgba(255, 255, 255, 0.55);
}
@keyframes hint-breathe {
  0%,
  100% {
    opacity: 0.65;
  }
  50% {
    opacity: 1;
  }
}

/* ---------- 深色天空（夜/晨曦/黄昏/夜晚）下的文字与卡片适配 ---------- */
.welcome-page[data-sky-text="light"] {
  .welcome-stage h1,
  .welcome-clock strong,
  .care-tip {
    color: #f5f8fb;
  }
  .welcome-subtitle,
  .welcome-clock,
  .welcome-footnote,
  .tile-note {
    color: rgba(235, 242, 248, 0.72);
  }
  .welcome-greeting {
    color: rgba(245, 249, 252, 0.95);
  }
  .greeting-meta {
    color: #bfeee2;
    background: rgba(255, 255, 255, 0.14);
  }
  .solar-term {
    border-color: rgba(255, 255, 255, 0.32);
    color: #cfeee4;
  }
  .summary-tile {
    border-color: rgba(255, 255, 255, 0.18);
    background: rgba(16, 40, 62, 0.42);
    box-shadow: 0 6px 18px rgba(0, 0, 0, 0.22);
  }
  .tile-label {
    color: rgba(235, 242, 248, 0.75);
  }
  .tone-blue .tile-value {
    color: #7fd4ff;
  }
  .tone-teal .tile-value {
    color: #79dfc4;
  }
  .tone-green .tile-value {
    color: #8fe3ac;
  }
  .tone-amber .tile-value {
    color: #ffd58a;
  }
  .tone-plain .tile-value {
    color: #f2f6f9;
  }
  .welcome-decoration {
    color: #9fd8c9;
    opacity: 0.1;
  }
}

@media (max-width: 1080px) {
  .summary-strip {
    grid-template-columns: repeat(3, minmax(120px, 1fr));
    width: min(560px, 92%);
  }
  .celestial {
    top: 4%;
    right: 5%;
    transform: scale(0.8);
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
  .care-line {
    flex-direction: column;
    gap: 6px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .sun,
  .sun-ray,
  .moon,
  .star {
    animation: none !important;
  }
  .sky-fade-enter-active,
  .sky-fade-leave-active {
    transition-duration: 0.01ms;
  }
}
</style>
