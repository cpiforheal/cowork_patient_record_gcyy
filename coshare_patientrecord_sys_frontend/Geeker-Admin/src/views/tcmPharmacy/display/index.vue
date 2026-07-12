<template>
  <main class="display-shell" :class="{ offline }">
    <header class="display-header">
      <div class="header-primary">
        <img class="brand-logo" src="@/assets/images/logo.jpg" alt="医院标识" />
        <strong class="brand-title">固始中医肛肠医院</strong>
        <span class="header-divider"></span>
        <strong class="clock-time">{{ clock }}</strong>
      </div>
      <span class="date-text">{{ dateText }}</span>
    </header>

    <section class="display-content">
      <article class="ready-board">
        <div class="board-title">
          <div><span class="section-seal">取</span><b>请取药</b></div>
          <strong>{{ snapshot.ready.length }}</strong>
        </div>
        <div v-if="snapshot.ready.length" class="ready-table-head">
          <span>取药号</span><span>患者</span><span>调剂方式</span><span>当前状态</span>
        </div>
        <transition-group v-if="snapshot.ready.length" name="ready-list" tag="div" class="ready-list">
          <div
            v-for="row in snapshot.ready"
            :key="row.id"
            class="ready-row"
            :class="{ calling: row.prescriptionStatus === 'CALLED' }"
          >
            <strong class="pickup-number">{{ row.pickupNo }}</strong>
            <b class="patient-name">{{ row.maskedName }}</b>
            <span class="cell-card type-card">{{ typeLabel(row.dispenseType) }}</span>
            <span class="cell-card status-card">
              {{ row.prescriptionStatus === "CALLED" ? "正在呼叫" : "等待领取" }}
            </span>
          </div>
        </transition-group>
        <div v-else class="empty-state"><i>候</i><strong>暂无待领取药品</strong></div>
      </article>

      <aside class="waiting-board">
        <div class="board-title">
          <div><span class="section-seal">制</span><b>制作进度</b></div>
          <strong>{{ snapshot.waiting.length }}</strong>
        </div>
        <div class="waiting-list">
          <div v-for="row in snapshot.waiting" :key="row.id" class="waiting-row">
            <b>{{ row.pickupNo || "待编号" }}</b>
            <span>{{ row.maskedName }}</span>
            <em>{{ progressLabel(row.prescriptionStatus, row.dispenseType) }}</em>
          </div>
          <div v-if="!snapshot.waiting.length" class="waiting-empty">暂无制作中处方</div>
        </div>
        <div class="count-strip">
          <div>
            <span>调剂中</span><b>{{ snapshot.counts.dispensing }}</b>
          </div>
          <div>
            <span>代煎中</span><b>{{ snapshot.counts.decocting }}</b>
          </div>
          <div>
            <span>今日领取</span><b>{{ snapshot.counts.collectedToday }}</b>
          </div>
        </div>
      </aside>
    </section>

    <footer class="display-footer">
      <div class="footer-mark"><span>本草</span>中药房</div>
      <div class="status">
        <span :class="offline ? 'bad' : 'good'"></span>{{ offline ? "连接中断，正在重连" : `更新于 ${lastUpdated}`
        }}<b v-if="audioBlocked">点击屏幕启用语音</b>
      </div>
    </footer>

    <transition name="call-overlay">
      <div v-if="currentCall" class="calling-overlay">
        <div class="calling-card">
          <span class="calling-seal">请</span>
          <p>{{ currentCall.pickupNo }} 号</p>
          <strong>{{ currentCall.maskedName }}</strong>
          <h2>请前往二楼中药房取药</h2>
        </div>
      </div>
    </transition>
  </main>
</template>

<script setup lang="ts" name="tcmPharmacyDisplay">
import { onBeforeUnmount, onMounted, reactive, ref } from "vue";
import {
  getPendingTcmAnnouncementsApi,
  getTcmDisplayApi,
  markTcmAnnouncementPlayedApi,
  type TcmAnnouncement,
  type TcmDisplaySnapshot
} from "@/api/modules/clinic/tcmPharmacy";
import { speakAiSummaryApi } from "@/api/modules/clinic/ai";

const snapshot = reactive<TcmDisplaySnapshot>({
  ready: [],
  waiting: [],
  counts: { waitingCharge: 0, waitingReview: 0, dispensing: 0, decocting: 0, ready: 0, collectedToday: 0, exception: 0 },
  serverTime: "",
  refreshSeconds: 5
});
const clock = ref("");
const dateText = ref("");
const lastUpdated = ref("--:--");
const offline = ref(false);
const audioBlocked = ref(true);
const currentCall = ref<TcmAnnouncement>();
const played = new Set<string>();
let refreshTimer = 0;
let clockTimer = 0;
let callBusy = false;
let audio: HTMLAudioElement | undefined;
let callChannel: BroadcastChannel | undefined;

function tick() {
  const now = new Date();
  clock.value = now.toLocaleTimeString("zh-CN", { hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit" });
  dateText.value = now.toLocaleDateString("zh-CN", { year: "numeric", month: "long", day: "numeric", weekday: "long" });
}
function typeLabel(type: string) {
  return type === "HOSPITAL_DECOCTION" ? "院内代煎" : "患者自煎";
}
function progressLabel(status: string, type: string) {
  return status === "DECOCTING" ? "代煎制作中" : type === "HOSPITAL_DECOCTION" ? "调剂复核中" : "抓药配药中";
}

async function refresh() {
  try {
    const [{ data }, announcements] = await Promise.all([getTcmDisplayApi(), getPendingTcmAnnouncementsApi()]);
    Object.assign(snapshot, data);
    lastUpdated.value = new Date().toLocaleTimeString("zh-CN", { hour12: false });
    offline.value = false;
    const next = announcements.data.rows.find(item => !played.has(item.id));
    if (next && !callBusy) void playAnnouncement(next);
  } catch {
    offline.value = true;
  }
}
function receiveCall(item?: TcmAnnouncement) {
  if (!item || played.has(item.id) || callBusy) return;
  void refresh();
  void playAnnouncement(item);
}
function handleStorageCall(event: StorageEvent) {
  if (event.key === "tcm-pharmacy-call-event" && event.newValue) void refresh();
}

async function playAnnouncement(item: TcmAnnouncement) {
  callBusy = true;
  currentCall.value = item;
  played.add(item.id);
  let spoken = false;
  try {
    const { data } = await speakAiSummaryApi({ text: item.content });
    if (data.audioBase64) await playBase64(data.audioBase64, data.mimeType || "audio/mpeg");
    else await browserSpeak(item.content);
    spoken = true;
  } catch {
    try {
      await browserSpeak(item.content);
      spoken = true;
    } catch {
      audioBlocked.value = true;
    }
  } finally {
    audioBlocked.value = !spoken;
    try {
      await markTcmAnnouncementPlayedApi(item.id);
    } catch {
      /* 播报确认失败不阻塞大屏恢复，后续轮询仍由本页去重 */
    }
    window.setTimeout(() => {
      if (currentCall.value?.id === item.id) currentCall.value = undefined;
      callBusy = false;
      void refresh();
    }, 5000);
  }
}

async function playBase64(base64: string, mime: string) {
  const binary = window.atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  const url = URL.createObjectURL(new Blob([bytes], { type: mime }));
  audio?.pause();
  audio = new Audio(url);
  audio.preload = "auto";
  try {
    await audio.play();
    await new Promise<void>((resolve, reject) => {
      if (!audio) return resolve();
      const timeout = window.setTimeout(() => reject(new Error("audio timeout")), 15000);
      audio.onended = () => {
        window.clearTimeout(timeout);
        resolve();
      };
      audio.onerror = () => {
        window.clearTimeout(timeout);
        reject(new Error("audio failed"));
      };
    });
  } finally {
    URL.revokeObjectURL(url);
  }
}
function browserSpeak(text: string) {
  return new Promise<void>((resolve, reject) => {
    if (!("speechSynthesis" in window)) return reject(new Error("unsupported"));
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = "zh-CN";
    utterance.rate = 0.9;
    utterance.onend = () => resolve();
    utterance.onerror = () => reject(new Error("speech failed"));
    window.speechSynthesis.speak(utterance);
  });
}
function enableAudio() {
  audioBlocked.value = false;
  if (window.speechSynthesis) {
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance("语音提示已启用");
    utterance.lang = "zh-CN";
    utterance.volume = 0.15;
    window.speechSynthesis.speak(utterance);
  }
}

onMounted(() => {
  tick();
  void refresh();
  clockTimer = window.setInterval(tick, 1000);
  refreshTimer = window.setInterval(refresh, 2000);
  if ("BroadcastChannel" in window) {
    callChannel = new BroadcastChannel("tcm-pharmacy-calls");
    callChannel.onmessage = event => {
      if (event.data?.type === "TCM_CALL_CREATED") receiveCall(event.data.announcement as TcmAnnouncement);
    };
  }
  window.addEventListener("storage", handleStorageCall);
  window.addEventListener("focus", refresh);
  document.addEventListener("visibilitychange", refresh);
  document.addEventListener("click", enableAudio, { once: true });
});
onBeforeUnmount(() => {
  window.clearInterval(clockTimer);
  window.clearInterval(refreshTimer);
  callChannel?.close();
  window.removeEventListener("storage", handleStorageCall);
  window.removeEventListener("focus", refresh);
  document.removeEventListener("visibilitychange", refresh);
  audio?.pause();
  window.speechSynthesis?.cancel();
});
</script>

<style scoped lang="scss">
.display-shell {
  --maroon-950: #f6faf8;
  --maroon-900: #fbfdfc;
  --maroon-800: #f0f7f4;
  --maroon-700: #e5f2ed;
  --paper: #29453f;
  --paper-muted: #789089;
  --bronze: #79b79e;
  --line: #e1eee9;
  min-height: 100vh;
  display: grid;
  grid-template-rows: auto 1fr auto;
  overflow: hidden;
  color: var(--paper);
  background: var(--maroon-950);
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
}

.display-header {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 104px;
  padding: 14px 42px;
  border-bottom: 1px solid var(--line);
  background: var(--maroon-900);
}

.header-primary {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 18px;
}

.brand-logo {
  width: 62px;
  height: 62px;
  padding: 3px;
  object-fit: cover;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #ffffff;
}

.brand-title {
  font-size: 30px;
  font-weight: 600;
  letter-spacing: 0.1em;
}

.header-divider {
  width: 1px;
  height: 38px;
  margin: 0 4px;
  background: #d9eae3;
}

.clock-time {
  color: #5a9b81;
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 38px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.06em;
  line-height: 1;
}

.date-text {
  position: absolute;
  right: 42px;
  bottom: 14px;
  color: var(--paper-muted);
  font-size: 13px;
  letter-spacing: 0.04em;
}

.display-content {
  display: grid;
  grid-template-columns: minmax(0, 1.8fr) minmax(340px, 0.72fr);
  gap: 22px;
  min-height: 0;
  padding: 24px 42px;
}

.ready-board,
.waiting-board {
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: var(--maroon-900);
  box-shadow: 0 8px 24px rgba(55, 104, 86, 0.05);
}

.board-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 70px;
  padding: 0 24px;
  border-bottom: 1px solid var(--line);
  background: var(--maroon-800);
  > div {
    display: flex;
    align-items: center;
    gap: 13px;
  }
  b {
    font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
    font-size: 22px;
    font-weight: 600;
    letter-spacing: 0.1em;
  }
  > strong {
    color: #5a9b81;
    font-size: 30px;
    font-variant-numeric: tabular-nums;
  }
}

.section-seal {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid #cce3da;
  border-radius: 9px;
  color: #629d85;
  font-family: "STSong", "SimSun", serif;
  font-size: 19px;
}

.ready-table-head,
.ready-row {
  display: grid;
  grid-template-columns: minmax(180px, 1.1fr) minmax(140px, 0.8fr) minmax(150px, 0.9fr) minmax(160px, 0.9fr);
  align-items: center;
  gap: 14px;
}

.ready-table-head {
  margin: 0 22px;
  padding: 18px 18px 12px;
  border-bottom: 1px solid #e7f1ed;
  color: var(--paper-muted);
  font-size: 14px;
  letter-spacing: 0.12em;
}

.ready-list {
  height: calc(100% - 118px);
  overflow: auto;
  padding: 4px 22px 18px;
  scrollbar-width: thin;
  scrollbar-color: rgba(116, 169, 147, 0.24) transparent;
}

.ready-row {
  min-height: 82px;
  margin-top: 8px;
  padding: 9px 18px;
  border: 1px solid #e5f0ec;
  border-radius: 14px;
  transition:
    background-color 0.35s ease,
    border-color 0.35s ease;
  &.calling {
    border-color: #b9dccc;
    background: var(--maroon-700);
    .status-card {
      border-color: #82bca5;
      color: #ffffff;
      background: #82bca5;
    }
  }
}

.pickup-number {
  color: #4f9277;
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: clamp(28px, 2.6vw, 44px);
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.04em;
  line-height: 1;
}

.patient-name {
  color: var(--paper);
  font-size: 20px;
  font-weight: 550;
  letter-spacing: 0.08em;
}

.cell-card {
  width: fit-content;
  min-width: 102px;
  padding: 7px 12px;
  border: 1px solid #dfede7;
  border-radius: 10px;
  text-align: center;
}

.type-card {
  color: #658078;
  background: #f5f9f7;
}

.status-card {
  color: #568f79;
  background: #edf6f2;
}

.waiting-list {
  height: calc(100% - 168px);
  overflow: auto;
  padding: 8px 20px;
}

.waiting-row {
  display: grid;
  grid-template-columns: 88px 1fr auto;
  align-items: center;
  gap: 12px;
  min-height: 68px;
  padding: 8px 4px;
  border-bottom: 1px solid #e8f1ed;
  b {
    color: #568f79;
    font-size: 20px;
    font-variant-numeric: tabular-nums;
  }
  span {
    font-size: 17px;
    letter-spacing: 0.08em;
  }
  em {
    padding: 7px 9px;
    border: 1px solid #dfede7;
    border-radius: 9px;
    color: #658078;
    background: #f5f9f7;
    font-size: 12px;
    font-style: normal;
  }
}

.waiting-empty,
.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(105, 132, 123, 0.62);
}

.waiting-empty {
  height: 100%;
  font-size: 17px;
}

.empty-state {
  height: calc(100% - 70px);
  flex-direction: column;
  i {
    display: grid;
    width: 76px;
    height: 76px;
    place-items: center;
    border: 1px solid #cce3da;
    border-radius: 18px;
    color: #629d85;
    font:
      normal 36px "STSong",
      "SimSun",
      serif;
  }
  strong {
    margin-top: 20px;
    color: var(--paper-muted);
    font-size: 21px;
    letter-spacing: 0.08em;
  }
}

.count-strip {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  min-height: 98px;
  border-top: 1px solid var(--line);
  background: var(--maroon-800);
  div {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    border-right: 1px solid #e1eee9;
    &:last-child {
      border-right: 0;
    }
  }
  span {
    color: var(--paper-muted);
    font-size: 13px;
  }
  b {
    margin-top: 6px;
    color: #5a9b81;
    font-size: 26px;
    font-variant-numeric: tabular-nums;
  }
}

.display-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 54px;
  padding: 0 42px;
  border-top: 1px solid var(--line);
  color: var(--paper-muted);
  background: #ffffff;
}

.footer-mark {
  font-family: "STSong", "SimSun", serif;
  letter-spacing: 0.12em;
  span {
    margin-right: 10px;
    color: #5a9b81;
  }
}

.status {
  display: flex;
  align-items: center;
  gap: 9px;
  span {
    width: 8px;
    height: 8px;
    border-radius: 50%;
  }
  .good {
    background: #82bca5;
  }
  .bad {
    background: #cf6a5e;
  }
  b {
    margin-left: 14px;
    color: #5a9b81;
    font-weight: 500;
  }
}

.calling-overlay {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: grid;
  place-items: center;
  background: rgba(56, 91, 79, 0.46);
}

.calling-card {
  position: relative;
  width: min(760px, 76vw);
  padding: 68px 50px 62px;
  border: 1px solid #c4dfd4;
  border-radius: 24px;
  outline: 0;
  background: #ffffff;
  box-shadow: 0 24px 70px rgba(48, 91, 75, 0.12);
  text-align: center;
  .calling-seal {
    position: absolute;
    top: 24px;
    left: 28px;
    display: grid;
    width: 46px;
    height: 46px;
    place-items: center;
    border: 1px solid #bdddd0;
    border-radius: 12px;
    color: #5a9b81;
    font:
      26px "STSong",
      "SimSun",
      serif;
  }
  p {
    margin: 0;
    color: #4f9277;
    font-size: 48px;
    font-weight: 700;
  }
  strong {
    display: block;
    margin-top: 18px;
    font-size: 88px;
    letter-spacing: 0.14em;
  }
  h2 {
    margin: 30px 0 0;
    padding-top: 24px;
    border-top: 1px solid #e1eee9;
    color: var(--paper-muted);
    font-size: 30px;
    font-weight: 500;
    letter-spacing: 0.08em;
  }
}

.ready-list-enter-active,
.ready-list-leave-active,
.call-overlay-enter-active,
.call-overlay-leave-active {
  transition: 0.45s ease;
}

.ready-list-enter-from,
.ready-list-leave-to {
  opacity: 0;
  transform: translateY(12px);
}

.call-overlay-enter-from,
.call-overlay-leave-to {
  opacity: 0;
}

.offline::after {
  content: "网络连接异常，正在自动恢复";
  position: fixed;
  top: 0;
  left: 50%;
  z-index: 30;
  transform: translateX(-50%);
  padding: 8px 20px;
  border-radius: 0 0 8px 8px;
  color: var(--paper);
  background: #9b4038;
}

@media (max-width: 1200px) {
  .display-shell {
    overflow: auto;
  }
  .display-header,
  .display-footer {
    padding-right: 24px;
    padding-left: 24px;
  }
  .display-content {
    grid-template-columns: 1fr;
    padding: 20px 24px;
  }
  .ready-board {
    min-height: 560px;
  }
  .waiting-board {
    min-height: 430px;
  }
}

@media (max-width: 760px) {
  .brand-title span,
  .ready-table-head {
    display: none;
  }
  .header-primary {
    gap: 10px;
  }
  .clock-time {
    font-size: 30px;
  }
  .date-text {
    display: none;
  }
  .ready-row {
    grid-template-columns: 1.1fr 0.8fr;
  }
  .brand-logo {
    width: 58px;
    height: 58px;
  }
  .brand-title strong {
    font-size: 24px;
  }
}
</style>
