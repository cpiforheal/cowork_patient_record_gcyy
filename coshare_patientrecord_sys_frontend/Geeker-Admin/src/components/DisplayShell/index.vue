<template>
  <main class="display-shell" :class="{ offline }" :data-display-theme="theme">
    <header class="shell-header">
      <div class="header-primary">
        <img class="brand-logo" src="@/assets/images/logo.jpg" alt="医院标识" />
        <div class="brand-copy">
          <strong class="brand-title">{{ title }}</strong>
          <span v-if="subtitle">{{ subtitle }}</span>
        </div>
      </div>
      <div class="header-status">
        <div class="date-clock">
          <span>{{ dateText }}</span
          ><strong>{{ clock }}</strong>
        </div>
        <span class="header-divider"></span>
        <div class="health-chip">
          <b>✓</b><span>{{ offline ? "连接恢复中" : "数据正常" }}</span>
        </div>
        <span class="header-divider"></span>
        <button
          v-if="audioBlocked"
          class="header-audio"
          type="button"
          :disabled="audioEnabling"
          @click.stop="$emit('enable-audio')"
        >
          <b>♪</b><span>{{ audioEnabling ? "开启中" : "开启语音" }}</span>
        </button>
        <div v-else class="health-chip audio"><b>♪</b><span>语音已开启</span></div>
      </div>
    </header>

    <div v-if="$slots.subheader" class="shell-subheader">
      <slot name="subheader" />
    </div>

    <section class="shell-content">
      <slot />
    </section>

    <footer class="shell-footer">
      <div class="footer-guide"><slot name="footer-guide">请留意屏幕及语音播报</slot></div>
      <div class="status">
        <span :class="offline ? 'bad' : 'good'"></span>{{ offline ? "连接中断，正在自动重连" : `最后更新 ${lastUpdated}` }}
      </div>
    </footer>

    <slot name="overlay" />

    <transition name="stale-mask">
      <div v-if="showStaleMask" class="stale-mask" role="alert">
        <strong>数据更新中断</strong>
        <p>请以现场叫号为准，系统正在自动重连</p>
      </div>
    </transition>

    <div v-if="sessionExpired" class="session-expired" role="alert">
      <div class="session-expired-card">
        <strong>大屏会话已失效</strong>
        <p>请管理员使用展示终端账号重新登录本机后刷新页面</p>
        <p class="hint">重新登录后大屏会自动恢复，无需其他操作</p>
      </div>
    </div>
  </main>
</template>

<script setup lang="ts" name="DisplayShell">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
// 打包等宽数字字体：内网播放主机上没有 SF Mono/Roboto Mono，避免号码回退成 Courier。
import "@fontsource/jetbrains-mono/400.css";
import "@fontsource/jetbrains-mono/700.css";

interface DisplayShellProps {
  title: string;
  subtitle?: string;
  clock: string;
  dateText: string;
  offline: boolean;
  lastUpdated: string;
  sessionExpired?: boolean;
  offlineSince?: number | null;
  audioBlocked?: boolean;
  audioEnabling?: boolean;
}

const props = withDefaults(defineProps<DisplayShellProps>(), {
  subtitle: "",
  sessionExpired: false,
  offlineSince: null,
  audioBlocked: false,
  audioEnabling: false
});
defineEmits<{ "enable-audio": [] }>();

const STALE_MASK_AFTER_MS = 60 * 1000;
const THEME_STORAGE_KEY = "clinic-display-theme";

const route = useRoute();
const theme = ref(resolveTheme());

function resolveTheme() {
  const queryTheme = String(route.query.theme ?? "");
  if (queryTheme === "dark" || queryTheme === "light") {
    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, queryTheme);
    } catch {
      // 写入失败时仅本次会话生效。
    }
    return queryTheme;
  }
  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    if (stored === "dark" || stored === "light") return stored;
  } catch {
    // 读取失败回退浅色。
  }
  return "light";
}
watch(
  () => route.query.theme,
  () => (theme.value = resolveTheme())
);

// 离线超过 1 分钟才升级为整屏遮罩。
const nowTick = ref(Date.now());
let staleTimer = 0;
onMounted(() => {
  staleTimer = window.setInterval(() => (nowTick.value = Date.now()), 1000);
});
onBeforeUnmount(() => window.clearInterval(staleTimer));
const showStaleMask = computed(
  () => props.offline && props.offlineSince !== null && nowTick.value - props.offlineSince > STALE_MASK_AFTER_MS
);
</script>

<style lang="scss">
@use "@/styles/display-tokens.scss";
</style>

<style scoped lang="scss">
.display-shell {
  box-sizing: border-box;
  height: 100vh;
  height: 100dvh;
  min-height: 0;
  display: grid;
  grid-template-rows: clamp(64px, 9dvh, 96px) auto minmax(0, 1fr) clamp(44px, 6dvh, 68px);
  overflow: hidden;
  color: var(--dsp-ink);
  background: var(--dsp-bg);
  font-family: var(--dsp-font-ui);
  font-weight: 400;
}
.display-shell :deep(strong),
.display-shell :deep(b),
.display-shell :deep(h2) {
  font-weight: 700;
}
.shell-header {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 40px;
  border-bottom: 1px solid var(--dsp-line);
  background: var(--dsp-surface-header);
  backdrop-filter: blur(16px);
}
.shell-header::before {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  height: 4px;
  background: linear-gradient(90deg, var(--dsp-mint) 0 42%, var(--dsp-blue) 100%);
  content: "";
}
.header-primary,
.header-status,
.health-chip,
.header-audio,
.date-clock {
  display: flex;
  align-items: center;
}
.header-primary {
  gap: 16px;
}
.brand-logo {
  width: 56px;
  height: 56px;
  flex: 0 0 56px;
  object-fit: cover;
  border: 3px solid rgba(255, 255, 255, 0.96);
  border-radius: 18px;
  background: #ffffff;
  box-shadow:
    0 0 0 1px rgba(11, 177, 234, 0.15),
    0 9px 22px rgba(8, 127, 169, 0.16);
}
.brand-copy {
  display: grid;
  gap: 2px;
}
.brand-title {
  font-size: var(--dsp-fs-brand);
  letter-spacing: 0.06em;
}
.brand-copy span {
  color: var(--dsp-muted);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.18em;
}
.header-status {
  gap: 16px;
  color: var(--dsp-muted);
  font-size: 16px;
  font-weight: 600;
}
.date-clock {
  gap: 14px;
}
.date-clock strong {
  color: var(--dsp-ink);
  font-family: var(--dsp-font-numeric);
  font-size: var(--dsp-fs-clock);
  font-variant-numeric: tabular-nums;
}
.header-divider {
  width: 1px;
  height: 32px;
  background: var(--dsp-line);
}
.health-chip,
.header-audio {
  gap: 7px;
  padding: 7px 11px;
  border: 1px solid var(--dsp-line);
  border-radius: 999px;
  white-space: nowrap;
  background: var(--dsp-surface-soft);
}
.health-chip b,
.header-audio b {
  display: grid;
  width: 23px;
  height: 23px;
  place-items: center;
  border-radius: 50%;
  color: var(--dsp-blue-deep);
  background: rgba(11, 177, 234, 0.1);
  font-size: 14px;
}
.header-audio {
  color: inherit;
  cursor: pointer;
  font: inherit;
  font-weight: 600;
  transition:
    transform 140ms cubic-bezier(0.23, 1, 0.32, 1),
    background-color 140ms ease,
    border-color 140ms ease;
}
.header-audio:hover {
  border-color: var(--dsp-blue);
}
.header-audio:active {
  transform: scale(0.97);
}
.header-audio:disabled {
  cursor: wait;
  opacity: 0.65;
}
.shell-subheader {
  min-height: 0;
}
.shell-content {
  min-height: 0;
  overflow: hidden;
}
.shell-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 40px;
  border-top: 1px solid var(--dsp-line);
  background: var(--dsp-surface-footer);
}
.footer-guide {
  color: var(--dsp-muted);
  font-size: 15px;
  font-weight: 600;
}
.status {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--dsp-muted);
  font-size: 16px;
  font-weight: 600;
}
.status > span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.status .good {
  background: var(--dsp-status-good);
  box-shadow: 0 0 0 5px rgba(53, 185, 141, 0.1);
}
.status .bad {
  background: var(--dsp-status-bad);
}
.offline::after {
  content: "数据连接异常 · 当前信息可能未更新";
  position: fixed;
  top: 0;
  left: 50%;
  z-index: 30;
  transform: translateX(-50%);
  padding: 8px 20px;
  border-radius: 0 0 8px 8px;
  color: #ffffff;
  background: #9b4038;
}
.stale-mask {
  position: fixed;
  inset: 0;
  z-index: 25;
  display: grid;
  place-content: center;
  gap: 10px;
  text-align: center;
  color: #ffffff;
  background: rgba(8, 34, 48, 0.72);
  backdrop-filter: blur(4px);
}
.stale-mask strong {
  font-size: clamp(40px, 5vw, 64px);
  letter-spacing: 0.08em;
}
.stale-mask p {
  margin: 0;
  font-size: clamp(20px, 2.2vw, 30px);
  opacity: 0.92;
}
.stale-mask-enter-active,
.stale-mask-leave-active {
  transition: opacity 0.4s ease;
}
.stale-mask-enter-from,
.stale-mask-leave-to {
  opacity: 0;
}
.session-expired {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  background: rgba(8, 34, 48, 0.86);
  backdrop-filter: blur(6px);
}
.session-expired-card {
  max-width: min(720px, 84vw);
  padding: clamp(36px, 6vmin, 60px) clamp(30px, 5vmin, 56px);
  text-align: center;
  border: 1px solid var(--dsp-line);
  border-radius: 24px;
  color: var(--dsp-ink);
  background: var(--dsp-surface);
  box-shadow: 0 30px 100px rgba(2, 49, 75, 0.4);
}
.session-expired-card strong {
  display: block;
  margin-bottom: 14px;
  font-size: clamp(32px, 4vw, 48px);
  letter-spacing: 0.06em;
}
.session-expired-card p {
  margin: 6px 0 0;
  font-size: clamp(18px, 2vw, 26px);
  color: var(--dsp-muted);
}
.session-expired-card .hint {
  font-size: clamp(14px, 1.5vw, 18px);
}
@media (max-width: 1280px) {
  .shell-header {
    padding: 0 24px;
  }
  .header-status {
    gap: 12px;
  }
  .shell-footer {
    padding: 0 24px;
  }
}
@media (max-height: 760px) and (min-width: 801px) {
  .display-shell {
    grid-template-rows: clamp(58px, 8dvh, 72px) auto minmax(0, 1fr) clamp(38px, 5dvh, 48px);
  }
  .brand-logo {
    width: 42px;
    height: 42px;
    flex-basis: 42px;
    border-radius: 12px;
  }
  .brand-title {
    font-size: clamp(24px, 2vw, 31px);
  }
  .date-clock strong {
    font-size: 21px;
  }
  .health-chip,
  .header-audio {
    padding: 5px 9px;
  }
}
@media (max-width: 800px) {
  .display-shell {
    height: auto;
    min-height: 100vh;
    min-height: 100dvh;
    grid-template-rows: auto auto auto auto;
    overflow: auto;
  }
  .shell-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
    padding: 12px 18px;
  }
  .header-status {
    width: 100%;
    justify-content: space-between;
    font-size: 14px;
  }
  .health-chip span,
  .header-audio span,
  .brand-copy span {
    display: none;
  }
  .brand-title {
    font-size: 28px;
  }
  .brand-logo {
    width: 48px;
    height: 48px;
    flex-basis: 48px;
    border-radius: 15px;
  }
  .date-clock > span,
  .header-divider {
    display: none;
  }
  .shell-content {
    overflow: visible;
  }
  .shell-footer {
    min-height: 100px;
    align-items: flex-start;
    flex-direction: column;
    justify-content: center;
    gap: 8px;
    padding: 12px 18px;
  }
  .footer-guide {
    width: 100%;
    text-align: left;
  }
  .status {
    font-size: 14px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .display-shell *,
  .display-shell *::before,
  .display-shell *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    scroll-behavior: auto !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
