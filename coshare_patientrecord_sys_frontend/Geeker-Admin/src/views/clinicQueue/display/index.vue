<template>
  <main class="display-shell" :class="{ offline }">
    <header class="display-header">
      <div class="header-primary">
        <img class="brand-logo" src="@/assets/images/logo.jpg" alt="医院标识" />
        <strong class="brand-title">固始中医肛肠医院</strong>
        <span class="header-divider"></span>
        <strong class="system-title">检查接诊排队</strong>
        <span class="header-divider"></span>
        <strong class="clock-time">{{ clock }}</strong>
      </div>
      <span class="date-text">{{ dateText }}</span>
    </header>

    <section class="display-content">
      <article v-for="panel in panels" :key="panel.stage" class="room-board" :class="roomClass(panel.room.room.status)">
        <div class="board-title">
          <div>
            <span class="section-seal">{{ panel.stage === "INSPECTION" ? "检" : "诊" }}</span>
            <div>
              <b>{{ panel.room.room.roomName }}</b>
              <span>{{ roomStatusLabel(panel.room.room.status) }}</span>
            </div>
          </div>
          <strong>{{ panel.room.waiting.length }}</strong>
        </div>

        <section class="calling-section">
          <span class="section-caption">{{ panel.stage === "INSPECTION" ? "检查候诊区" : "接诊候诊区" }}</span>
          <div class="room-focus-card">
            <i>{{ panel.stage === "INSPECTION" ? "检" : "诊" }}</i>
            <strong>{{ panel.stage === "INSPECTION" ? "请等候检查叫号" : "请等候接诊叫号" }}</strong>
            <p>{{ roomEmptyText(panel.room.room.status) }}</p>
            <span>叫号时将弹窗提示并语音播报</span>
          </div>
        </section>

        <section class="waiting-section">
          <div class="waiting-head">
            <span>等待号码</span>
            <em>请留意屏幕并保持安静</em>
          </div>
          <transition-group v-if="panel.room.waiting.length" name="queue-list" tag="div" class="waiting-grid">
            <div v-for="row in panel.room.waiting.slice(0, 12)" :key="row.id" class="waiting-item">
              <strong>{{ row.publicNo }}</strong>
              <span :class="row.visitType === 'FOLLOW_UP' ? 'follow-up' : 'first-visit'">
                {{ visitTypeLabel(row.visitType) }}
              </span>
            </div>
          </transition-group>
          <div v-else class="waiting-empty">暂无等待患者</div>
        </section>
      </article>
    </section>

    <footer class="display-footer">
      <div class="footer-mark"><span>同号流转</span>检查完成后自动进入接诊队列</div>
      <div class="status">
        <span :class="offline ? 'bad' : 'good'"></span>
        {{ offline ? "连接中断，正在自动重连" : `更新于 ${lastUpdated}` }}
        <button v-if="audioBlocked" class="audio-enable-button" type="button" :disabled="audioEnabling" @click.stop="enableAudio">
          {{ audioEnabling ? "正在开启…" : "开启叫号语音" }}
        </button>
        <b v-else class="audio-enabled-text">✓ 叫号语音已开启</b>
        <em v-if="audioMessage" class="audio-message">{{ audioMessage }}</em>
      </div>
    </footer>

    <transition name="call-overlay">
      <div v-if="currentCall" class="calling-overlay">
        <div class="calling-card">
          <span class="calling-seal">请</span>
          <em>{{ currentCall.stageCode === "INSPECTION" ? "检查室叫号" : "接诊室叫号" }}</em>
          <p>{{ currentCall.publicNo }} 号</p>
          <strong>请前往{{ currentCall.roomName }}</strong>
          <h2>{{ currentCall.content }}</h2>
        </div>
      </div>
    </transition>
  </main>
</template>

<script setup lang="ts" name="clinicQueueDisplay">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { speakAiSummaryApi } from "@/api/modules/clinic/ai";
import {
  getPendingQueueAnnouncementsApi,
  getQueueDisplayApi,
  markQueueAnnouncementPlayedApi,
  type QueueAnnouncement,
  type QueueDisplayRoom,
  type QueueDisplaySnapshot,
  type QueueRoom,
  type QueueStage,
  type QueueVisitType
} from "@/api/modules/clinic/clinicQueue";

const emptyRoom = (roomCode: string, roomName: string, stageCode: QueueStage): QueueDisplayRoom => ({
  room: {
    roomCode,
    roomName,
    stageCode,
    status: "ACTIVE",
    pauseReason: "",
    followUpStreak: 0,
    version: 0,
    updatedAt: ""
  },
  calling: [],
  waiting: []
});

const snapshot = reactive<QueueDisplaySnapshot>({
  inspection: emptyRoom("INSPECTION_ROOM", "检查室", "INSPECTION"),
  reception: emptyRoom("RECEPTION_ROOM", "接诊室", "RECEPTION"),
  counts: {
    inspectionWaiting: 0,
    inspectionActive: 0,
    receptionWaiting: 0,
    receptionActive: 0,
    completedToday: 0,
    exceptions: 0
  },
  serverTime: "",
  refreshSeconds: 3
});

const clock = ref("");
const dateText = ref("");
const lastUpdated = ref("--:--");
const offline = ref(false);
const audioBlocked = ref(true);
const audioEnabling = ref(false);
const audioMessage = ref("请先点击开启语音");
const currentCall = ref<QueueAnnouncement>();
const played = new Set<string>();
const CALL_POPUP_DURATION = 3800;
const SPEECH_TIMEOUT = 10000;
let refreshTimer = 0;
let clockTimer = 0;
let overlayTimer = 0;
let callBusy = false;
let audio: HTMLAudioElement | undefined;
let deferredCall: QueueAnnouncement | undefined;
let callChannel: BroadcastChannel | undefined;

const panels = computed(() => [
  { stage: "INSPECTION" as QueueStage, room: snapshot.inspection },
  { stage: "RECEPTION" as QueueStage, room: snapshot.reception }
]);

function tick() {
  const now = new Date();
  clock.value = now.toLocaleTimeString("zh-CN", {
    hour12: false,
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  });
  dateText.value = now.toLocaleDateString("zh-CN", {
    year: "numeric",
    month: "long",
    day: "numeric",
    weekday: "long"
  });
}

function visitTypeLabel(type: QueueVisitType) {
  return type === "FOLLOW_UP" ? "复诊" : "初诊";
}

function roomStatusLabel(status: QueueRoom["status"]) {
  return {
    ACTIVE: "正常接诊",
    EMERGENCY_PAUSED: "急症暂停",
    MANUAL_PAUSED: "临时暂停",
    CLOSED: "已停诊",
    OFFLINE: "终端离线"
  }[status];
}

function roomClass(status: QueueRoom["status"]) {
  return {
    paused: ["EMERGENCY_PAUSED", "MANUAL_PAUSED"].includes(status),
    closed: ["CLOSED", "OFFLINE"].includes(status),
    emergency: status === "EMERGENCY_PAUSED"
  };
}

function roomEmptyText(status: QueueRoom["status"]) {
  if (status === "EMERGENCY_PAUSED") return "急症处理中，请耐心等候";
  if (status === "MANUAL_PAUSED") return "房间临时暂停叫号";
  if (status === "CLOSED") return "房间当前已停诊";
  if (status === "OFFLINE") return "房间终端暂时离线";
  return "请留意下一次叫号";
}

async function refresh() {
  try {
    const [{ data }, announcements] = await Promise.all([getQueueDisplayApi(), getPendingQueueAnnouncementsApi()]);
    Object.assign(snapshot, data);
    lastUpdated.value = new Date().toLocaleTimeString("zh-CN", { hour12: false });
    offline.value = false;
    const next = announcements.data.rows.find(item => !played.has(item.id));
    if (next && !callBusy) void playAnnouncement(next);
  } catch {
    offline.value = true;
  }
}

function receiveCall(item?: QueueAnnouncement) {
  if (!item || played.has(item.id) || callBusy) return;
  void refresh();
  void playAnnouncement(item);
}

function handleStorageCall(event: StorageEvent) {
  if (event.key === "clinic-queue-call-event" && event.newValue) void refresh();
}

async function playAnnouncement(item: QueueAnnouncement) {
  callBusy = true;
  currentCall.value = item;
  played.add(item.id);
  window.clearTimeout(overlayTimer);
  overlayTimer = window.setTimeout(() => {
    if (currentCall.value?.id === item.id) currentCall.value = undefined;
  }, CALL_POPUP_DURATION);

  let spoken = false;
  try {
    if (audioBlocked.value) throw new Error("audio interaction required");
    try {
      const { data } = await speakAiSummaryApi({ text: item.content });
      if (data.audioBase64) await playBase64(data.audioBase64, data.mimeType || "audio/mpeg");
      else await browserSpeak(item.content);
    } catch {
      await browserSpeak(item.content);
    }
    spoken = true;
  } catch {
    deferredCall = item;
    audioBlocked.value = true;
  } finally {
    if (spoken) {
      deferredCall = undefined;
      audioBlocked.value = false;
      try {
        await markQueueAnnouncementPlayedApi(item.id);
      } catch {
        // 播放确认失败不阻塞大屏，当前页面通过 played 集合避免重复播报。
      }
    }
    callBusy = false;
    void refresh();
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
    const voices = window.speechSynthesis.getVoices();
    utterance.voice = voices.find(voice => /zh-CN/i.test(voice.lang)) || voices.find(voice => /^zh/i.test(voice.lang)) || null;
    utterance.lang = "zh-CN";
    utterance.rate = 0.88;
    utterance.pitch = 1;
    utterance.volume = 1;
    const timeout = window.setTimeout(() => {
      window.speechSynthesis.cancel();
      reject(new Error("speech timeout"));
    }, SPEECH_TIMEOUT);
    utterance.onend = () => {
      window.clearTimeout(timeout);
      resolve();
    };
    utterance.onerror = () => {
      window.clearTimeout(timeout);
      reject(new Error("speech failed"));
    };
    window.speechSynthesis.speak(utterance);
    window.setTimeout(() => {
      if (window.speechSynthesis.paused) window.speechSynthesis.resume();
    }, 120);
  });
}

async function unlockWebAudio() {
  const safariWindow = window as typeof window & { webkitAudioContext?: typeof AudioContext };
  const AudioContextClass = window.AudioContext || safariWindow.webkitAudioContext;
  if (!AudioContextClass) return;
  const context = new AudioContextClass();
  try {
    if (context.state === "suspended") await context.resume();
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    gain.gain.value = 0.0001;
    oscillator.connect(gain);
    gain.connect(context.destination);
    oscillator.start();
    oscillator.stop(context.currentTime + 0.03);
  } finally {
    window.setTimeout(() => void context.close(), 100);
  }
}

async function enableAudio() {
  if (audioEnabling.value) return;
  audioEnabling.value = true;
  audioMessage.value = "正在请求浏览器播放权限";

  try {
    await unlockWebAudio();
    audioBlocked.value = false;
    audioMessage.value = "语音已开启，可进行叫号测试";
    window.sessionStorage.setItem("clinic-queue-audio-enabled", "1");

    if ("speechSynthesis" in window) {
      window.speechSynthesis.cancel();
      const confirmation = new SpeechSynthesisUtterance("检查接诊叫号语音已开启");
      confirmation.lang = "zh-CN";
      confirmation.rate = 0.9;
      confirmation.volume = 1;
      window.speechSynthesis.speak(confirmation);
    }

    const pending = deferredCall;
    deferredCall = undefined;
    if (pending) {
      played.delete(pending.id);
      window.setTimeout(() => void playAnnouncement(pending), 800);
    }
  } catch {
    audioBlocked.value = true;
    audioMessage.value = "开启失败，请检查浏览器声音权限后重试";
  } finally {
    audioEnabling.value = false;
  }
}

onMounted(() => {
  tick();
  if (window.sessionStorage.getItem("clinic-queue-audio-enabled") === "1") {
    audioBlocked.value = false;
    audioMessage.value = "语音已开启";
  }
  void refresh();
  clockTimer = window.setInterval(tick, 1000);
  refreshTimer = window.setInterval(refresh, 2000);
  if ("BroadcastChannel" in window) {
    callChannel = new BroadcastChannel("clinic-queue-calls");
    callChannel.onmessage = event => {
      if (event.data?.type === "CLINIC_QUEUE_CALL_CREATED") receiveCall(event.data.announcement as QueueAnnouncement);
    };
  }
  window.addEventListener("storage", handleStorageCall);
  window.addEventListener("focus", refresh);
  document.addEventListener("visibilitychange", refresh);
  window.speechSynthesis?.getVoices();
});

onBeforeUnmount(() => {
  window.clearInterval(clockTimer);
  window.clearInterval(refreshTimer);
  window.clearTimeout(overlayTimer);
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
  --paper-bg: #f6faf8;
  --paper-card: #fbfdfc;
  --paper-soft: #f0f7f4;
  --paper-active: #e5f2ed;
  --ink: #29453f;
  --ink-muted: #789089;
  --green: #5a9b81;
  --green-soft: #79b79e;
  --line: #e1eee9;
  min-height: 100vh;
  display: grid;
  grid-template-rows: auto 1fr auto;
  overflow: hidden;
  color: var(--ink);
  background: var(--paper-bg);
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
  background: var(--paper-card);
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
  font-size: 29px;
  font-weight: 600;
  letter-spacing: 0.1em;
}

.system-title {
  color: var(--green);
  font-size: 22px;
  font-weight: 600;
  letter-spacing: 0.12em;
}

.header-divider {
  width: 1px;
  height: 38px;
  margin: 0 4px;
  background: #d9eae3;
}

.clock-time {
  color: var(--green);
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
  color: var(--ink-muted);
  font-size: 13px;
  letter-spacing: 0.04em;
}

.display-content {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 22px;
  min-height: 0;
  padding: 24px 42px;
}

.room-board {
  min-height: 0;
  display: grid;
  grid-template-rows: auto minmax(250px, 0.9fr) minmax(230px, 1.1fr);
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: var(--paper-card);
  box-shadow: 0 8px 24px rgba(55, 104, 86, 0.05);

  &.paused .board-title {
    background: #fff8e8;
  }

  &.emergency .board-title {
    color: #9c4338;
    background: #fff0ed;
  }

  &.closed {
    filter: saturate(0.55);
  }
}

.board-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 78px;
  padding: 0 24px;
  border-bottom: 1px solid var(--line);
  background: var(--paper-soft);

  > div {
    display: flex;
    align-items: center;
    gap: 13px;
  }

  > div > div {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }

  b {
    color: #244f41;
    font-size: clamp(30px, 2.6vw, 42px);
    font-weight: 750;
    letter-spacing: 0.12em;
    text-shadow: 0 2px 0 rgba(255, 255, 255, 0.85);
  }

  span:not(.section-seal) {
    color: var(--ink-muted);
    font-size: 12px;
  }

  > strong {
    color: var(--green);
    font-size: 32px;
    font-variant-numeric: tabular-nums;

    &::after {
      content: " 人等待";
      color: var(--ink-muted);
      font-size: 13px;
      font-weight: 400;
    }
  }
}

.section-seal {
  display: grid;
  width: 54px;
  height: 54px;
  place-items: center;
  border: 1px solid #cce3da;
  border-radius: 15px;
  color: #ffffff;
  background: linear-gradient(145deg, #4f9277, #79b79e);
  box-shadow: 0 8px 18px rgba(79, 146, 119, 0.18);
  font:
    29px "STSong",
    "SimSun",
    serif;
}

.calling-section {
  position: relative;
  display: grid;
  place-items: center;
  padding: 36px 24px 24px;
  border-bottom: 1px solid var(--line);
  background: linear-gradient(180deg, #ffffff 0%, #fbfdfc 100%);
}

.section-caption {
  position: absolute;
  top: 16px;
  left: 24px;
  color: var(--ink-muted);
  font-size: 13px;
  letter-spacing: 0.12em;
}

.room-focus-card {
  width: min(500px, 94%);
  padding: 24px 30px 22px;
  border: 1px solid #c8e2d7;
  border-radius: 20px;
  background: linear-gradient(145deg, #ffffff, var(--paper-active));
  text-align: center;
  box-shadow: 0 14px 34px rgba(70, 132, 106, 0.1);

  i {
    display: grid;
    width: 72px;
    height: 72px;
    margin: 0 auto;
    place-items: center;
    border-radius: 22px;
    color: #ffffff;
    background: linear-gradient(145deg, #4f9277, #79b79e);
    box-shadow: 0 10px 22px rgba(79, 146, 119, 0.2);
    font:
      normal 38px "STSong",
      "SimSun",
      serif;
  }

  strong {
    display: block;
    margin-top: 16px;
    color: #315f50;
    font-size: clamp(25px, 2.3vw, 36px);
    letter-spacing: 0.1em;
  }

  p {
    margin: 10px 0 0;
    color: var(--ink);
    font-size: 17px;
  }

  span {
    display: block;
    margin-top: 12px;
    color: var(--ink-muted);
    font-size: 13px;
    letter-spacing: 0.05em;
  }
}

.waiting-section {
  min-height: 0;
  padding: 18px 22px 22px;
}

.waiting-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px solid #e7f1ed;

  span {
    font-size: 16px;
    font-weight: 600;
    letter-spacing: 0.08em;
  }

  em {
    color: var(--ink-muted);
    font-size: 12px;
    font-style: normal;
  }
}

.waiting-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  max-height: calc(100% - 45px);
  overflow: auto;
  padding-top: 12px;
  scrollbar-width: thin;
  scrollbar-color: rgba(116, 169, 147, 0.24) transparent;
}

.waiting-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 58px;
  padding: 8px 12px;
  border: 1px solid #e5f0ec;
  border-radius: 12px;
  background: #ffffff;

  strong {
    color: #568f79;
    font-size: clamp(22px, 2vw, 31px);
    font-variant-numeric: tabular-nums;
  }

  span {
    padding: 4px 7px;
    border-radius: 7px;
    font-size: 11px;
  }

  .first-visit {
    color: #658078;
    background: #edf6f2;
  }

  .follow-up {
    color: #926f32;
    background: #fff4d9;
  }
}

.waiting-empty {
  display: grid;
  height: calc(100% - 45px);
  min-height: 120px;
  place-items: center;
  color: rgba(105, 132, 123, 0.62);
  font-size: 17px;
}

.display-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 54px;
  padding: 0 42px;
  border-top: 1px solid var(--line);
  color: var(--ink-muted);
  background: #ffffff;
}

.footer-mark {
  font-family: "STSong", "SimSun", serif;
  letter-spacing: 0.08em;

  span {
    margin-right: 10px;
    color: var(--green);
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
    color: var(--green);
    font-weight: 500;
  }
}

.audio-enable-button {
  min-width: 132px;
  padding: 9px 17px;
  border: 0;
  border-radius: 999px;
  color: #ffffff;
  background: #4f9277;
  box-shadow: 0 5px 14px rgba(79, 146, 119, 0.22);
  cursor: pointer;
  font: inherit;
  font-weight: 650;

  &:active {
    transform: translateY(1px);
  }

  &:disabled {
    cursor: wait;
    opacity: 0.72;
  }
}

.audio-enabled-text {
  color: #3d8067 !important;
  font-weight: 650 !important;
}

.audio-message {
  color: var(--ink-muted);
  font-size: 12px;
  font-style: normal;
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
  width: min(780px, 78vw);
  padding: 68px 50px 62px;
  border: 1px solid #c4dfd4;
  border-radius: 24px;
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
    color: var(--green);
    font:
      26px "STSong",
      "SimSun",
      serif;
  }

  em {
    display: inline-block;
    margin-bottom: 12px;
    padding: 7px 18px;
    border-radius: 999px;
    color: #ffffff;
    background: #4f9277;
    font-size: 18px;
    font-style: normal;
    font-weight: 600;
    letter-spacing: 0.12em;
  }

  p {
    margin: 0;
    color: #4f9277;
    font-size: 78px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
    letter-spacing: 0.08em;
  }

  strong {
    display: block;
    margin-top: 18px;
    font-size: 48px;
    letter-spacing: 0.14em;
  }

  h2 {
    margin: 30px 0 0;
    padding-top: 24px;
    border-top: 1px solid var(--line);
    color: var(--ink-muted);
    font-size: 27px;
    font-weight: 500;
    letter-spacing: 0.06em;
    line-height: 1.6;
  }
}

.queue-list-enter-active,
.queue-list-leave-active,
.call-overlay-enter-active,
.call-overlay-leave-active {
  transition: 0.28s ease;
}

.queue-list-enter-from,
.queue-list-leave-to {
  opacity: 0;
  transform: translateY(10px);
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
  color: #ffffff;
  background: #9b4038;
}

@media (max-width: 1280px) {
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

  .room-board {
    min-height: 680px;
  }
}

@media (max-width: 800px) {
  .brand-title,
  .system-title,
  .date-text {
    display: none;
  }

  .header-primary {
    gap: 12px;
  }

  .clock-time {
    font-size: 30px;
  }

  .waiting-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .display-footer {
    align-items: flex-start;
    flex-direction: column;
    justify-content: center;
    gap: 4px;
    padding-top: 8px;
    padding-bottom: 8px;
  }
}
</style>
