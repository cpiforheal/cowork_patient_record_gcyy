<template>
  <main class="display-shell" :class="{ offline }">
    <header class="display-header">
      <div class="header-primary">
        <img class="brand-logo" src="@/assets/images/logo.jpg" alt="医院标识" />
        <div class="brand-copy">
          <strong class="brand-title">门诊候诊叫号</strong>
          <span>OUTPATIENT QUEUE DISPLAY</span>
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
        <button v-if="audioBlocked" class="header-audio" type="button" :disabled="audioEnabling" @click.stop="enableAudio">
          <b>♪</b><span>{{ audioEnabling ? "开启中" : "开启语音" }}</span>
        </button>
        <div v-else class="health-chip audio"><b>♪</b><span>语音已开启</span></div>
      </div>
    </header>

    <div class="stage-flow-bar">
      <span><b>01</b>检查室</span><i><em>检查完成 · 同号流转</em></i
      ><span><b>02</b>接诊室</span>
    </div>

    <section class="display-content">
      <article
        v-for="panel in panels"
        :key="panel.stage"
        class="room-board"
        :class="[
          roomClass(panel.room.room.status),
          panel.stage === 'INSPECTION' ? 'inspection-board' : 'reception-board',
          panel.room.calling.length ? 'has-calling' : 'is-idle'
        ]"
      >
        <div class="board-decoration" aria-hidden="true">
          <svg class="colon-mascot" viewBox="0 0 96 96" fill="none">
            <path
              d="M29 18c-9 0-16 7-16 16v25c0 11 8 19 19 19h25c12 0 21-9 21-21V35c0-9-7-16-16-16H35c-6 0-10 4-10 10v24c0 6 4 10 10 10h17c6 0 10-4 10-10V38c0-4-3-7-7-7s-7 3-7 7v10"
            />
            <circle cx="34" cy="35" r="2.2" />
            <circle cx="43" cy="35" r="2.2" />
            <path d="M34 42c3 3 7 3 10 0" />
            <path class="mascot-spark" d="m75 13 2 5 5 2-5 2-2 5-2-5-5-2 5-2 2-5Z" />
          </svg>
        </div>
        <div class="board-title">
          <div class="room-identity">
            <span class="stage-index">{{ panel.stage === "INSPECTION" ? "01" : "02" }}</span>
            <span class="room-medical-icon" :class="panel.stage === 'INSPECTION' ? 'inspection-icon' : 'reception-icon'">
              <svg v-if="panel.stage === 'INSPECTION'" viewBox="0 0 48 48" fill="none" aria-hidden="true">
                <rect x="9" y="7" width="25" height="32" rx="5" />
                <path d="M17 7.5V5h9v2.5M15 17h13M15 23h8" />
                <circle cx="31" cy="31" r="7" />
                <path d="m36 36 5 5" />
              </svg>
              <svg v-else viewBox="0 0 48 48" fill="none" aria-hidden="true">
                <path d="M12 8v10c0 7 5 12 12 12s12-5 12-12V8" />
                <path d="M8 8h8M32 8h8M24 30v3c0 5 4 9 9 9s9-4 9-9v-2" />
                <circle cx="42" cy="27" r="4" />
              </svg>
            </span>
            <b>{{ panel.room.room.roomName }}</b>
            <span class="room-status-pill">{{ roomStatusLabel(panel.room.room.status) }}</span>
          </div>
          <div class="waiting-count">
            <span>等候</span><strong>{{ panel.room.waiting.length }}</strong
            ><span>人</span>
          </div>
        </div>

        <section class="calling-section">
          <div class="section-caption">当前叫号</div>
          <div class="room-focus-card" :class="{ empty: !panel.room.calling.length }">
            <template v-if="panel.room.calling[0]">
              <strong class="focus-number">{{ panel.room.calling[0].publicNo }}</strong>
              <div class="focus-guidance">
                <small>请前往</small>
                <p>{{ panel.room.room.roomName }}</p>
              </div>
            </template>
            <template v-else
              ><strong class="empty-state-title">{{ roomEmptyText(panel.room.room.status) }}</strong></template
            >
          </div>
        </section>

        <section class="waiting-section">
          <div class="waiting-head">
            <span>接下来</span><em>{{ panel.room.waiting.length }} 人等候</em>
          </div>
          <transition-group v-if="panel.room.waiting.length" name="queue-list" tag="div" class="waiting-grid">
            <div
              v-for="(row, index) in panel.room.waiting.slice(0, 6)"
              :key="row.id"
              class="waiting-item"
              :class="{ next: index === 0 }"
            >
              <div class="waiting-number">
                <small v-if="index === 0">下一位</small>
                <strong>{{ row.publicNo }}</strong>
              </div>
              <span class="visit-tag" :class="row.visitType === 'FOLLOW_UP' ? 'follow-up' : 'first-visit'">
                {{ visitTypeLabel(row.visitType) }}
              </span>
            </div>
          </transition-group>
          <div v-else class="waiting-empty">当前暂无等候号码</div>
        </section>
      </article>
    </section>

    <footer class="display-footer">
      <div class="footer-guide">请留意屏幕及语音播报</div>
      <div class="status">
        <span :class="offline ? 'bad' : 'good'"></span>{{ offline ? "连接中断，正在自动重连" : `最后更新 ${lastUpdated}` }}
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
  return "当前暂无叫号";
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
/* 2026 门诊候诊导视：楷体信息层级、医疗蓝绿双区与轻量肠道健康漫画点缀 */
.display-shell {
  --mint: #c6f8dd;
  --blue: #0bb1ea;
  --blue-deep: #087fa9;
  --navy: #0a3d55;
  --muted: #617d89;
  --line: #d5e7eb;
  --soft-blue: #f1faff;
  --soft-mint: #f1fbf5;
  box-sizing: border-box;
  height: 100vh;
  height: 100dvh;
  min-height: 0;
  display: grid;
  grid-template-rows: clamp(64px, 9dvh, 96px) clamp(38px, 5dvh, 52px) minmax(0, 1fr) clamp(44px, 6dvh, 68px);
  overflow: hidden;
  color: var(--navy);
  background:
    radial-gradient(circle at 5% 3%, rgba(198, 248, 221, 0.68), transparent 25%),
    radial-gradient(circle at 96% 90%, rgba(151, 220, 246, 0.3), transparent 28%),
    radial-gradient(circle at 85% 8%, rgba(255, 224, 181, 0.2), transparent 17%),
    linear-gradient(155deg, #f9fdfb 0%, #f1fbff 50%, #f4fbf7 100%);
  font-family: "KaiTi", "STKaiti", "楷体", "FangSong", serif;
  font-weight: 400;
}
.display-header {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 40px;
  border-bottom: 1px solid var(--line);
  color: var(--navy);
  background: rgba(255, 255, 255, 0.94);
}
.display-header::before {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  height: 4px;
  background: linear-gradient(90deg, var(--mint) 0 42%, var(--blue) 100%);
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
  background: #fff;
  box-shadow:
    0 0 0 1px rgba(11, 177, 234, 0.15),
    0 9px 22px rgba(8, 127, 169, 0.16);
}
.brand-copy {
  display: grid;
  gap: 2px;
}
.brand-title {
  font-size: clamp(30px, 2.3vw, 40px);
  font-weight: 800;
  letter-spacing: 0.06em;
}
.brand-copy span {
  color: #8096a0;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.18em;
}
.header-status {
  gap: 16px;
  color: var(--muted);
  font-size: 16px;
  font-weight: 600;
}
.date-clock {
  gap: 14px;
}
.date-clock strong {
  color: var(--navy);
  font-size: 25px;
  font-variant-numeric: tabular-nums;
}
.header-divider {
  width: 1px;
  height: 32px;
  background: var(--line);
}
.health-chip,
.header-audio {
  gap: 7px;
  padding: 7px 11px;
  border: 1px solid #dcebef;
  border-radius: 999px;
  white-space: nowrap;
  background: #f8fbfc;
}
.health-chip b,
.header-audio b {
  display: grid;
  width: 23px;
  height: 23px;
  place-items: center;
  border-radius: 50%;
  color: var(--blue-deep);
  background: rgba(11, 177, 234, 0.1);
  font-size: 14px;
}
.header-audio {
  color: inherit;
  cursor: pointer;
  font: inherit;
  font-weight: 600;
}
.header-audio:disabled {
  cursor: wait;
  opacity: 0.65;
}
.stage-flow-bar {
  display: grid;
  grid-template-columns: 1fr minmax(240px, 0.7fr) 1fr;
  align-items: center;
  gap: 18px;
  padding: 10px 32px 0;
}
.stage-flow-bar > span {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--navy);
  font-size: 17px;
  font-weight: 800;
}
.stage-flow-bar > span:last-child {
  justify-content: flex-end;
}
.stage-flow-bar b {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 7px;
  background: var(--mint);
  font-size: 13px;
}
.stage-flow-bar i {
  position: relative;
  height: 1px;
  background: #a9d7c0;
  font-style: normal;
}
.stage-flow-bar i::after {
  position: absolute;
  top: -4px;
  right: -1px;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  border-left: 8px solid #69ae8a;
  content: "";
}
.stage-flow-bar em {
  position: absolute;
  top: -20px;
  left: 50%;
  padding: 0 8px;
  color: #5f7e70;
  background: #fff;
  font-size: 12px;
  font-style: normal;
  transform: translateX(-50%);
  white-space: nowrap;
}
.display-content {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
  min-height: 0;
  padding: 12px 32px 22px;
  overflow: hidden;
}
.room-board {
  --stage-accent: var(--blue);
  --stage-soft: rgba(11, 177, 234, 0.045);
  --stage-index-bg: #e9f8fc;
  --stage-index-color: var(--blue-deep);
  position: relative;
  min-height: 0;
  display: grid;
  grid-template-rows: clamp(64px, 14%, 100px) minmax(0, 36%) minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid #cfe2e7;
  border-color: color-mix(in srgb, var(--stage-accent) 25%, #dce8eb);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.97);
  box-shadow: 0 15px 40px rgba(32, 92, 112, 0.09);
  box-shadow:
    0 15px 40px rgba(32, 92, 112, 0.09),
    inset 0 4px 0 color-mix(in srgb, var(--stage-accent) 62%, transparent);
}
.room-board.is-idle {
  grid-template-rows: clamp(64px, 14%, 100px) minmax(0, 36%) minmax(0, 1fr);
}
.room-board.reception-board {
  --stage-accent: #55b98b;
  --stage-soft: rgba(198, 248, 221, 0.16);
  --stage-index-bg: rgba(198, 248, 221, 0.5);
  --stage-index-color: #287f60;
}
.board-decoration {
  position: absolute;
  right: 14px;
  bottom: 8px;
  z-index: 0;
  width: clamp(76px, 6.5vw, 108px);
  color: var(--stage-accent);
  opacity: 0.13;
  pointer-events: none;
  transform: rotate(-4deg);
}
.colon-mascot {
  display: block;
  width: 100%;
  overflow: visible;
  stroke: currentColor;
  stroke-width: 5;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.colon-mascot circle {
  fill: currentColor;
  stroke: none;
}
.colon-mascot .mascot-spark {
  fill: #f4c96b;
  fill: color-mix(in srgb, var(--stage-accent) 42%, #ffd36a);
  stroke: none;
}
.board-title,
.calling-section,
.waiting-section {
  position: relative;
  z-index: 1;
}
.room-board.paused {
  border-color: #e2c57d;
}
.room-board.emergency {
  border-color: #e59a94;
}
.room-board.closed {
  border-color: #d8e0e3;
  background: #fafcfc;
}
.board-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 26px;
  border-bottom: 1px solid var(--line);
  background: var(--stage-soft);
  background:
    radial-gradient(circle at 92% 22%, color-mix(in srgb, var(--stage-accent) 13%, transparent), transparent 25%),
    linear-gradient(90deg, var(--stage-soft), rgba(255, 255, 255, 0.72));
}
.room-identity {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 14px;
}
.room-medical-icon {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  place-items: center;
  border: 1px solid #d6e7eb;
  border-color: color-mix(in srgb, var(--stage-accent) 28%, #dce8eb);
  border-radius: 50%;
  color: var(--stage-index-color);
  background: rgba(255, 255, 255, 0.78);
  box-shadow: 0 5px 13px rgba(32, 92, 112, 0.08);
  box-shadow: 0 5px 13px color-mix(in srgb, var(--stage-accent) 12%, transparent);
}
.room-medical-icon svg {
  width: 29px;
  height: 29px;
  stroke: currentColor;
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.stage-index {
  display: grid;
  width: 46px;
  height: 46px;
  flex: 0 0 46px;
  place-items: center;
  border-radius: 10px;
  color: var(--stage-index-color);
  background: var(--stage-index-bg);
  font-size: 20px;
  font-weight: 800;
  font-style: normal;
  font-variant-numeric: tabular-nums;
}
.room-identity > b {
  overflow: hidden;
  font-size: clamp(31px, 2.4vw, 42px);
  letter-spacing: 0.06em;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.room-status-pill {
  color: #748a93;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
}
.room-board.paused .room-status-pill {
  border-color: #edd79e;
  color: #8a6408;
  background: #fff8e7;
}
.room-board.emergency .room-status-pill {
  border-color: #efb9b5;
  color: #a8443d;
  background: #fff0ee;
}
.room-board.closed .room-status-pill {
  border-color: #d5dfe2;
  color: #6f8088;
  background: #f0f4f5;
}
.waiting-count {
  display: flex;
  flex: 0 0 auto;
  align-items: baseline;
  gap: 7px;
  color: var(--muted);
  font-size: 17px;
  font-weight: 700;
}
.waiting-count strong {
  color: var(--navy);
  font-size: 38px;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}
.calling-section {
  display: flex;
  min-height: 0;
  flex-direction: column;
  padding: 16px 24px 18px;
  border-bottom: 1px solid var(--line);
}
.section-caption,
.waiting-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--navy);
  font-size: 19px;
  font-weight: 800;
  letter-spacing: 0.03em;
}
.section-caption {
  padding-left: 10px;
  border-left: 4px solid var(--stage-accent);
}
.waiting-head {
  padding-left: 10px;
  border-left: 4px solid #c8d9df;
}
.waiting-head em {
  color: #879ca5;
  font-size: 13px;
  font-style: normal;
  font-weight: 600;
}
.room-focus-card {
  min-height: 0;
  display: grid;
  flex: 1;
  grid-template-columns: minmax(0, 1.3fr) minmax(170px, 0.7fr);
  align-items: center;
  margin-top: 10px;
  padding: 10px 20px;
  border: 1px solid #dce9ec;
  border-color: color-mix(in srgb, var(--stage-accent) 17%, #e3ecef);
  border-radius: 17px;
  background: #f9fcfd;
  background:
    radial-gradient(circle at 12% 20%, color-mix(in srgb, var(--stage-accent) 10%, transparent), transparent 30%),
    linear-gradient(135deg, #fff, color-mix(in srgb, var(--stage-accent) 5%, #fff));
  box-shadow: 0 7px 18px rgba(32, 92, 112, 0.045);
}
.focus-number {
  display: block;
  justify-self: center;
  color: var(--blue-deep);
  font-size: clamp(64px, 6vw, 92px);
  font-weight: 900;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.04em;
  line-height: 1;
}
.focus-guidance {
  min-width: 0;
  display: flex;
  align-self: stretch;
  flex-direction: column;
  justify-content: center;
  padding-left: 24px;
  border-left: 1px solid #d8e7eb;
}
.focus-guidance small {
  color: var(--muted);
  font-size: 15px;
  font-weight: 700;
}
.focus-guidance p {
  margin: 3px 0 0;
  overflow: hidden;
  font-size: clamp(28px, 2.25vw, 38px);
  font-weight: 800;
  letter-spacing: 0.06em;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.focus-guidance span {
  display: block;
  margin-top: 7px;
  color: var(--muted);
  font-size: 16px;
}
.room-focus-card.empty {
  display: grid;
  grid-template-columns: 1fr;
  place-items: center;
  margin-top: 4px;
  padding: 0;
  border-style: dashed;
  background: #f9fcfd;
  background: color-mix(in srgb, var(--stage-accent) 3%, #fff);
}
.empty-state-title {
  color: #78919b;
  font-size: clamp(24px, 2vw, 30px);
  font-weight: 600;
  letter-spacing: 0.05em;
}
.waiting-section {
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 16px 24px 22px;
}
.waiting-head {
  margin-bottom: 12px;
}
.waiting-grid {
  min-height: 0;
  display: grid;
  flex: 1;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-template-rows: repeat(2, minmax(70px, 1fr));
  gap: 12px;
  overflow: hidden;
}
.waiting-item {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  padding: 10px 15px;
  overflow: hidden;
  border: 1px solid #deeaed;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 4px 12px rgba(32, 92, 112, 0.035);
}
.waiting-item.next {
  border-color: #b8dfe7;
  border-color: color-mix(in srgb, var(--stage-accent) 30%, #dce8eb);
  background: #eef9f5;
  background: color-mix(in srgb, var(--stage-accent) 9%, #fff);
  box-shadow: 0 6px 16px rgba(32, 92, 112, 0.07);
  box-shadow: 0 6px 16px color-mix(in srgb, var(--stage-accent) 10%, transparent);
}
.waiting-number {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.waiting-number small {
  flex: 0 0 auto;
  padding: 3px 6px;
  border-radius: 5px;
  color: var(--blue-deep);
  background: rgba(11, 177, 234, 0.1);
  font-size: 12px;
  font-weight: 800;
}
.waiting-item strong {
  overflow: hidden;
  color: var(--navy);
  font-size: clamp(27px, 2.25vw, 38px);
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.visit-tag {
  flex: 0 0 auto;
  margin-left: 8px;
  padding: 5px 9px;
  border-radius: 7px;
  font-size: 14px;
  font-weight: 700;
}
.waiting-item .first-visit {
  color: var(--blue-deep);
  background: #eaf8fd;
}
.waiting-item .follow-up {
  color: #287f60;
  background: #edf8f2;
}
.waiting-empty {
  min-height: 0;
  display: grid;
  flex: 1;
  place-items: center;
  color: #8ca3ac;
  font-size: 19px;
}
.display-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 40px;
  border-top: 1px solid #dbe8eb;
  background: #f9fbfc;
}
.footer-guide {
  color: #617b87;
  font-size: 15px;
  font-weight: 600;
}
.status {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--muted);
  font-size: 16px;
  font-weight: 600;
}
.status > span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.status .good {
  background: #35b98d;
  box-shadow: 0 0 0 5px rgba(53, 185, 141, 0.1);
}
.status .bad {
  background: #e35f54;
}
.calling-overlay {
  background: rgba(5, 52, 76, 0.62);
  backdrop-filter: blur(8px);
}
.calling-card {
  border: 3px solid var(--blue);
  border-radius: 30px;
  background: linear-gradient(145deg, #fff, var(--soft-mint));
  box-shadow: 0 30px 100px rgba(2, 49, 75, 0.25);
}
.calling-card em {
  background: linear-gradient(135deg, var(--blue-deep), var(--blue));
}
.calling-card p {
  color: var(--blue-deep);
}
.calling-card .calling-seal {
  color: var(--blue-deep);
  border-color: var(--line);
}
@media (max-width: 1280px) {
  .display-header {
    padding: 0 24px;
  }
  .header-status {
    gap: 12px;
  }
  .display-content {
    gap: 14px;
    padding: 8px 18px 14px;
  }
  .room-board {
    grid-template-rows: clamp(60px, 14%, 82px) minmax(0, 35%) minmax(0, 1fr);
    border-radius: 16px;
  }
  .board-title {
    padding: 0 18px;
  }
  .room-identity {
    gap: 9px;
  }
  .stage-index {
    width: 38px;
    height: 38px;
    flex-basis: 38px;
    font-size: 16px;
  }
  .room-identity > b {
    font-size: clamp(25px, 2.5vw, 34px);
  }
  .waiting-count {
    gap: 4px;
    font-size: 14px;
  }
  .waiting-count strong {
    font-size: 30px;
  }
  .calling-section,
  .waiting-section {
    padding-right: 16px;
    padding-left: 16px;
  }
  .room-focus-card {
    grid-template-columns: minmax(0, 1.2fr) minmax(125px, 0.8fr);
    padding: 6px 10px;
  }
  .focus-number {
    font-size: clamp(48px, 5.5vw, 70px);
  }
  .focus-guidance {
    padding-left: 14px;
  }
  .focus-guidance p {
    font-size: clamp(22px, 2.25vw, 30px);
  }
  .waiting-grid {
    gap: 8px;
  }
  .waiting-item {
    padding: 8px 10px;
  }
  .waiting-item strong {
    font-size: clamp(22px, 2.25vw, 30px);
  }
  .display-footer {
    padding-right: 24px;
    padding-left: 24px;
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
  .display-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
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
  .display-content {
    grid-template-columns: 1fr;
    padding: 14px;
    overflow: visible;
  }
  .room-board {
    min-height: 790px;
    grid-template-rows: 92px 260px minmax(400px, 1fr);
    border-radius: 14px;
  }
  .board-title {
    padding: 0 16px;
  }
  .stage-index {
    width: 44px;
    height: 44px;
    flex-basis: 44px;
    border-radius: 11px;
    font-size: 17px;
  }
  .room-identity {
    gap: 9px;
  }
  .room-identity > b {
    font-size: 28px;
  }
  .room-status-pill {
    padding: 5px 9px;
    font-size: 12px;
  }
  .waiting-count {
    gap: 4px;
    font-size: 14px;
  }
  .waiting-count strong {
    font-size: 30px;
  }
  .calling-section,
  .waiting-section {
    padding-right: 16px;
    padding-left: 16px;
  }
  .room-focus-card {
    grid-template-columns: 1fr;
    padding: 16px;
    text-align: center;
  }
  .focus-guidance {
    align-items: center;
    padding: 12px 0 0;
    border-top: 1px solid #cbe7ef;
    border-left: 0;
  }
  .focus-guidance p {
    font-size: 28px;
  }
  .waiting-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: repeat(3, minmax(78px, 1fr));
  }
  .waiting-number {
    gap: 5px;
  }
  .waiting-number small {
    display: none;
  }
  .waiting-item strong {
    font-size: 27px;
  }
  .visit-tag {
    margin-left: 5px;
    padding: 4px 6px;
    font-size: 12px;
  }
  .display-footer {
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

.calling-overlay {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: grid;
  place-items: center;
}
.calling-card {
  position: relative;
  width: min(780px, 78vw);
  max-height: calc(100dvh - 32px);
  box-sizing: border-box;
  padding: clamp(42px, 7vmin, 68px) clamp(24px, 5vmin, 50px) clamp(34px, 6vmin, 62px);
  overflow: auto;
  text-align: center;
}
.calling-card .calling-seal {
  position: absolute;
  top: 24px;
  left: 28px;
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border-radius: 12px;
  font-size: 26px;
}
.calling-card em {
  display: inline-block;
  margin-bottom: 12px;
  padding: 7px 18px;
  border-radius: 999px;
  color: #fff;
  font-size: 18px;
  font-style: normal;
  font-weight: 600;
  letter-spacing: 0.12em;
}
.calling-card p {
  margin: 0;
  font-size: clamp(50px, 9vmin, 78px);
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.08em;
}
.calling-card strong {
  display: block;
  margin-top: 18px;
  font-size: clamp(30px, 5.5vmin, 48px);
  letter-spacing: 0.14em;
}
.calling-card h2 {
  margin: 30px 0 0;
  padding-top: 24px;
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: clamp(18px, 3vmin, 27px);
  font-weight: 500;
  letter-spacing: 0.06em;
  line-height: 1.6;
}

@media (max-height: 760px) and (min-width: 801px) {
  .display-shell {
    grid-template-rows: clamp(58px, 8dvh, 72px) 34px minmax(0, 1fr) clamp(38px, 5dvh, 48px);
  }
  .display-header {
    padding-right: 24px;
    padding-left: 24px;
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
  .stage-flow-bar {
    padding: 3px 24px 0;
  }
  .stage-flow-bar b {
    width: 25px;
    height: 25px;
  }
  .stage-flow-bar em {
    top: -16px;
  }
  .display-content {
    gap: 12px;
    padding: 6px 18px 10px;
  }
  .room-board,
  .room-board.is-idle {
    grid-template-rows: minmax(54px, 13%) minmax(0, 34%) minmax(0, 1fr);
  }
  .calling-section,
  .waiting-section {
    padding-top: 9px;
    padding-bottom: 10px;
  }
  .section-caption,
  .waiting-head {
    font-size: 16px;
  }
  .waiting-head {
    margin-bottom: 6px;
  }
  .room-focus-card {
    margin-top: 3px;
  }
  .focus-number {
    font-size: clamp(43px, 5vw, 62px);
  }
  .focus-guidance small,
  .focus-guidance span {
    font-size: 12px;
  }
  .waiting-grid {
    grid-template-rows: repeat(2, minmax(48px, 1fr));
    gap: 5px;
  }
  .waiting-item {
    padding: 5px 8px;
  }
  .waiting-item strong {
    font-size: clamp(20px, 2vw, 27px);
  }
  .visit-tag {
    padding: 3px 6px;
    font-size: 11px;
  }
  .display-footer,
  .status {
    font-size: 13px;
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

/* 大屏统一使用楷体常规字重，避免粗体在远距离观看时挤压笔画。 */
.display-shell,
.display-shell button {
  font-family: "KaiTi", "STKaiti", "楷体", "FangSong", serif;
}
.display-shell * {
  font-weight: 400 !important;
}
</style>
