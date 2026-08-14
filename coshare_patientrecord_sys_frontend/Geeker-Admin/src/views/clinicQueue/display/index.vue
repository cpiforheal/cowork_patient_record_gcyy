<template>
  <DisplayShell
    title="门诊候诊叫号"
    subtitle="OUTPATIENT QUEUE DISPLAY"
    :clock="clock"
    :date-text="dateText"
    :offline="offline"
    :last-updated="lastUpdated"
    :session-expired="sessionExpired"
    :offline-since="offlineSince"
    :audio-blocked="audioBlocked"
    :audio-enabling="audioEnabling"
    @enable-audio="enableAudio"
  >
    <template #subheader>
      <div class="stage-flow-bar">
        <span><b>01</b>检查室</span><i><em>检查完成 · 同号流转</em></i
        ><span><b>02</b>接诊室</span>
      </div>
    </template>

    <div class="display-content">
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
            <span class="room-medical-icon">
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
              <p class="focus-guide">请前往 {{ panel.room.room.roomName }}</p>
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
              v-for="(row, index) in visibleWaiting(panel.room)"
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
            <div v-if="overflowCount(panel.room)" key="waiting-overflow" class="waiting-item overflow">
              <strong>…还有 {{ overflowCount(panel.room) }} 位</strong>
            </div>
          </transition-group>
          <div v-else class="waiting-empty">当前暂无等候号码</div>
          <div v-if="panel.room.missed?.length" class="missed-strip">
            <b>过号</b>
            <strong v-for="row in panel.room.missed" :key="row.id">{{ row.publicNo }}</strong>
            <em>请到前台重新排队</em>
          </div>
        </section>
      </article>
    </div>

    <template #overlay>
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
    </template>
  </DisplayShell>
</template>

<script setup lang="ts" name="clinicQueueDisplay">
import { computed, onBeforeUnmount, onMounted, reactive } from "vue";
import DisplayShell from "@/components/DisplayShell/index.vue";
import { useDisplayClock } from "@/hooks/useDisplayClock";
import { useDisplayPolling } from "@/hooks/useDisplayPolling";
import { useAnnouncementPlayer } from "@/hooks/useAnnouncementPlayer";
import { setAuthRedirectSuppressed } from "@/api/modules/authToken";
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
  waiting: [],
  missed: []
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

const panels = computed(() => [
  { stage: "INSPECTION" as QueueStage, room: snapshot.inspection },
  { stage: "RECEPTION" as QueueStage, room: snapshot.reception }
]);

const { clock, dateText, calibrate } = useDisplayClock();

const { offline, sessionExpired, lastUpdated, offlineSince, refreshNow, setIntervalSeconds } = useDisplayPolling({
  // 连续离线 10 分钟后整页重载自愈（无人值守大屏的最后兜底）。
  watchdogReloadSeconds: 600,
  refresh: async () => {
    const [{ data }, announcements] = await Promise.all([getQueueDisplayApi(), getPendingQueueAnnouncementsApi()]);
    Object.assign(snapshot, data);
    setIntervalSeconds(data.refreshSeconds);
    calibrate(data.serverTime);
    playIfNew(announcements.data.rows.find(item => !hasPlayed(item.id)));
  }
});

const { currentCall, audioBlocked, audioEnabling, enableAudio, playIfNew, hasPlayed } = useAnnouncementPlayer<QueueAnnouncement>({
  unlockStorageKey: "clinic-queue-audio-enabled",
  markPlayed: markQueueAnnouncementPlayedApi,
  requestRefresh: refreshNow,
  popupDurationMs: 9000,
  repeat: 2,
  channelName: "clinic-queue-calls",
  channelMessageType: "CLINIC_QUEUE_CALL_CREATED",
  storageEventKey: "clinic-queue-call-event",
  confirmationText: "检查接诊叫号语音已开启"
});

function visitTypeLabel(type: QueueVisitType) {
  return type === "FOLLOW_UP" ? "复诊" : "初诊";
}

// 等待网格 6 格：超出时末格显示"…还有 N 位"，避免第 7 人以为自己不在队。
const WAITING_TILES = 6;
function visibleWaiting(room: QueueDisplayRoom) {
  if (room.waiting.length > WAITING_TILES) return room.waiting.slice(0, WAITING_TILES - 1);
  return room.waiting.slice(0, WAITING_TILES);
}
function overflowCount(room: QueueDisplayRoom) {
  return room.waiting.length > WAITING_TILES ? room.waiting.length - (WAITING_TILES - 1) : 0;
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

onMounted(() => setAuthRedirectSuppressed(true));
onBeforeUnmount(() => setAuthRedirectSuppressed(false));
</script>

<style scoped lang="scss">
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
  color: var(--dsp-ink);
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
  background: var(--dsp-mint);
  font-size: 13px;
}
.stage-flow-bar i {
  position: relative;
  height: 1px;
  background: var(--dsp-accent-green);
  font-style: normal;
  opacity: 0.6;
}
.stage-flow-bar i::after {
  position: absolute;
  top: -4px;
  right: -1px;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  border-left: 8px solid var(--dsp-accent-green);
  content: "";
}
.stage-flow-bar em {
  position: absolute;
  top: -20px;
  left: 50%;
  padding: 0 8px;
  color: var(--dsp-accent-green-deep);
  background: var(--dsp-surface);
  font-size: 12px;
  font-style: normal;
  transform: translateX(-50%);
  white-space: nowrap;
}
.display-content {
  height: 100%;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
  min-height: 0;
  padding: 12px 32px 22px;
  overflow: hidden;
  box-sizing: border-box;
}
.room-board {
  --stage-accent: var(--dsp-blue);
  --stage-index-bg: rgba(11, 177, 234, 0.12);
  --stage-index-color: var(--dsp-blue-deep);
  position: relative;
  min-height: 0;
  display: grid;
  grid-template-rows: clamp(64px, 14%, 100px) minmax(0, 36%) minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--stage-accent) 25%, var(--dsp-line-strong));
  border-radius: var(--dsp-radius-card);
  background: var(--dsp-surface);
  box-shadow:
    var(--dsp-shadow-card),
    inset 0 4px 0 color-mix(in srgb, var(--stage-accent) 62%, transparent);
  transition:
    border-color 180ms ease,
    box-shadow 180ms ease;
}
.room-board.has-calling {
  box-shadow:
    0 20px 52px color-mix(in srgb, var(--stage-accent) 16%, transparent),
    inset 0 4px 0 var(--stage-accent);
}
.room-board.reception-board {
  --stage-accent: var(--dsp-accent-green);
  --stage-index-bg: color-mix(in srgb, var(--dsp-mint) 50%, transparent);
  --stage-index-color: var(--dsp-accent-green-deep);
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
  border-color: var(--dsp-state-warn-line);
  background: linear-gradient(180deg, var(--dsp-state-warn-bg), var(--dsp-surface) 60%);
}
.room-board.emergency {
  border-color: var(--dsp-state-danger-line);
  background: linear-gradient(180deg, var(--dsp-state-danger-bg), var(--dsp-surface) 60%);
}
.room-board.closed {
  border-color: var(--dsp-state-closed-line);
  background: var(--dsp-state-closed-bg);
}
/* 异常状态下让提示语接管焦点区：6 米外一眼可辨 */
.room-board.paused .empty-state-title {
  color: var(--dsp-state-warn);
  font-size: clamp(30px, 2.6vw, 44px);
}
.room-board.emergency .empty-state-title {
  color: var(--dsp-state-danger);
  font-size: clamp(30px, 2.6vw, 44px);
}
.room-board.closed .empty-state-title {
  color: var(--dsp-state-closed);
  font-size: clamp(30px, 2.6vw, 44px);
}
.board-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 26px;
  border-bottom: 1px solid var(--dsp-line);
  background:
    radial-gradient(circle at 92% 22%, color-mix(in srgb, var(--stage-accent) 13%, transparent), transparent 25%),
    linear-gradient(90deg, color-mix(in srgb, var(--stage-accent) 6%, transparent), transparent);
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
  border: 1px solid color-mix(in srgb, var(--stage-accent) 28%, var(--dsp-line-strong));
  border-radius: 50%;
  color: var(--stage-index-color);
  background: var(--dsp-tile);
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
  font-variant-numeric: tabular-nums;
}
.room-identity > b {
  overflow: hidden;
  font-size: var(--dsp-fs-room-title);
  letter-spacing: 0.06em;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.room-status-pill {
  padding: 4px 9px;
  border: 1px solid transparent;
  border-radius: 7px;
  color: var(--dsp-muted);
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
}
.room-board.paused .room-status-pill {
  border-color: var(--dsp-state-warn-line);
  color: var(--dsp-state-warn);
  background: var(--dsp-state-warn-bg);
}
.room-board.emergency .room-status-pill {
  border-color: var(--dsp-state-danger-line);
  color: var(--dsp-state-danger);
  background: var(--dsp-state-danger-bg);
}
.room-board.closed .room-status-pill {
  border-color: var(--dsp-state-closed-line);
  color: var(--dsp-state-closed);
  background: var(--dsp-state-closed-bg);
}
.waiting-count {
  display: flex;
  flex: 0 0 auto;
  align-items: baseline;
  gap: 7px;
  color: var(--dsp-muted);
  font-size: 17px;
  font-weight: 700;
}
.waiting-count strong {
  color: var(--dsp-ink);
  font-family: var(--dsp-font-numeric);
  font-size: var(--dsp-fs-count);
  font-variant-numeric: tabular-nums;
  line-height: 1;
}
.calling-section {
  display: flex;
  min-height: 0;
  flex-direction: column;
  padding: 16px 24px 18px;
  border-bottom: 1px solid var(--dsp-line);
}
.section-caption,
.waiting-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--dsp-ink);
  font-size: var(--dsp-fs-caption);
  font-weight: 800;
  letter-spacing: 0.03em;
}
.section-caption {
  padding-left: 10px;
  border-left: 4px solid var(--stage-accent);
}
.waiting-head {
  padding-left: 10px;
  border-left: 4px solid var(--dsp-line-strong);
}
.waiting-head em {
  color: var(--dsp-muted);
  font-size: 13px;
  font-style: normal;
  font-weight: 600;
}
.room-focus-card {
  min-height: 0;
  display: grid;
  flex: 1;
  place-content: center;
  gap: 6px;
  margin-top: 10px;
  padding: 10px 20px;
  text-align: center;
  border: 1px solid color-mix(in srgb, var(--stage-accent) 17%, var(--dsp-line));
  border-radius: 17px;
  background:
    radial-gradient(circle at 12% 20%, color-mix(in srgb, var(--stage-accent) 10%, transparent), transparent 30%),
    linear-gradient(135deg, var(--dsp-surface), color-mix(in srgb, var(--stage-accent) 5%, var(--dsp-surface)));
  box-shadow: 0 7px 18px rgba(32, 92, 112, 0.045);
}
.focus-number {
  display: block;
  color: var(--dsp-blue-deep);
  font-family: var(--dsp-font-numeric);
  font-size: var(--dsp-fs-focus);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.02em;
  line-height: 1;
}
.reception-board .focus-number {
  color: var(--dsp-accent-green-deep);
}
.focus-guide {
  margin: 0;
  overflow: hidden;
  color: var(--dsp-muted);
  font-size: var(--dsp-fs-focus-guide);
  font-weight: 700;
  letter-spacing: 0.1em;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.room-focus-card.empty {
  display: grid;
  grid-template-columns: 1fr;
  place-items: center;
  margin-top: 4px;
  padding: 0;
  border-style: dashed;
  background: color-mix(in srgb, var(--stage-accent) 3%, var(--dsp-surface));
}
.empty-state-title {
  color: var(--dsp-muted);
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
  border: 1px solid var(--dsp-line);
  border-radius: var(--dsp-radius-tile);
  background: var(--dsp-tile);
  box-shadow: var(--dsp-shadow-tile);
  transition:
    transform 160ms cubic-bezier(0.23, 1, 0.32, 1),
    border-color 160ms ease,
    box-shadow 160ms ease;
}
.waiting-item.next {
  border-width: 2px;
  border-color: color-mix(in srgb, var(--stage-accent) 30%, var(--dsp-line-strong));
  background: color-mix(in srgb, var(--stage-accent) 9%, var(--dsp-surface));
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
  color: var(--dsp-blue-deep);
  background: rgba(11, 177, 234, 0.1);
  font-size: 12px;
  font-weight: 800;
}
.waiting-item strong {
  overflow: hidden;
  color: var(--dsp-ink);
  font-family: var(--dsp-font-numeric);
  font-size: var(--dsp-fs-list-number);
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
  color: var(--dsp-blue-deep);
  background: rgba(11, 177, 234, 0.1);
}
.waiting-item .follow-up {
  color: var(--dsp-accent-green-deep);
  background: color-mix(in srgb, var(--dsp-mint) 45%, transparent);
}
.waiting-item.overflow {
  justify-content: center;
  border-style: dashed;
  background: transparent;
  box-shadow: none;
}
.waiting-item.overflow strong {
  color: var(--dsp-muted);
  font-family: var(--dsp-font-ui);
  font-size: clamp(18px, 1.5vw, 24px);
}
.missed-strip {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  padding: 8px 14px;
  overflow: hidden;
  border: 1px solid var(--dsp-state-warn-line);
  border-radius: 10px;
  color: var(--dsp-state-warn);
  background: var(--dsp-state-warn-bg);
  white-space: nowrap;
}
.missed-strip b {
  flex: 0 0 auto;
  padding: 3px 8px;
  border-radius: 6px;
  color: #ffffff;
  background: var(--dsp-state-warn);
  font-size: 14px;
}
.missed-strip strong {
  font-family: var(--dsp-font-numeric);
  font-size: clamp(20px, 1.8vw, 28px);
  font-variant-numeric: tabular-nums;
}
.missed-strip em {
  overflow: hidden;
  margin-left: auto;
  font-size: 14px;
  font-style: normal;
  font-weight: 600;
  text-overflow: ellipsis;
}
.waiting-empty {
  min-height: 0;
  display: grid;
  flex: 1;
  place-items: center;
  color: var(--dsp-muted);
  font-size: 19px;
}
.calling-overlay {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: grid;
  place-items: center;
  background: rgba(5, 52, 76, 0.62);
  backdrop-filter: blur(8px);
}
.calling-card {
  position: relative;
  width: min(780px, 78vw);
  max-height: calc(100dvh - 32px);
  box-sizing: border-box;
  padding: clamp(42px, 7vmin, 68px) clamp(24px, 5vmin, 50px) clamp(34px, 6vmin, 62px);
  overflow: auto;
  text-align: center;
  border: 3px solid var(--dsp-blue);
  border-radius: 30px;
  background: var(--dsp-surface);
  box-shadow: 0 30px 100px rgba(2, 49, 75, 0.25);
  animation: calling-breathe 2.2s ease-in-out infinite;
}
@keyframes calling-breathe {
  50% {
    border-color: color-mix(in srgb, var(--dsp-blue) 45%, #ffffff);
    box-shadow:
      0 30px 100px rgba(2, 49, 75, 0.25),
      0 0 0 10px color-mix(in srgb, var(--dsp-blue) 18%, transparent);
  }
}
.calling-card .calling-seal {
  position: absolute;
  top: 24px;
  left: 28px;
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border: 1px solid var(--dsp-line);
  border-radius: 12px;
  color: var(--dsp-blue-deep);
  font-size: 26px;
}
.calling-card em {
  display: inline-block;
  margin-bottom: 12px;
  padding: 7px 18px;
  border-radius: 999px;
  color: #ffffff;
  background: linear-gradient(135deg, var(--dsp-blue-deep), var(--dsp-blue));
  font-size: 18px;
  font-style: normal;
  font-weight: 600;
  letter-spacing: 0.12em;
}
.calling-card p {
  margin: 0;
  color: var(--dsp-blue-deep);
  font-family: var(--dsp-font-numeric);
  font-size: var(--dsp-fs-overlay-number);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.08em;
}
.calling-card strong {
  display: block;
  margin-top: 18px;
  font-size: var(--dsp-fs-overlay-guide);
  letter-spacing: 0.14em;
}
.calling-card h2 {
  margin: 30px 0 0;
  padding-top: 24px;
  border-top: 1px solid var(--dsp-line);
  color: var(--dsp-muted);
  font-size: clamp(18px, 3vmin, 27px);
  font-weight: 500;
  letter-spacing: 0.06em;
  line-height: 1.6;
}
.queue-list-enter-active,
.queue-list-leave-active {
  transition:
    opacity 180ms ease-out,
    transform 180ms cubic-bezier(0.23, 1, 0.32, 1);
}
.queue-list-enter-from,
.queue-list-leave-to {
  opacity: 0;
  transform: translateY(10px);
}
.call-overlay-enter-active {
  transition: opacity 180ms ease-out;
}
.call-overlay-enter-active .calling-card {
  transition:
    opacity 220ms ease-out,
    transform 220ms cubic-bezier(0.23, 1, 0.32, 1);
}
.call-overlay-enter-from .calling-card {
  opacity: 0;
  transform: scale(0.95) translateY(8px);
}
.call-overlay-leave-active {
  transition: opacity 140ms ease-out;
}
.call-overlay-leave-to .calling-card {
  opacity: 0;
  transform: scale(0.98);
}
.call-overlay-enter-from,
.call-overlay-leave-to {
  opacity: 0;
}
@media (max-width: 1280px) {
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
    padding: 6px 10px;
  }
  .focus-number {
    font-size: clamp(60px, 8vw, 110px);
  }
  .focus-guide {
    font-size: clamp(16px, 1.6vw, 22px);
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
}
@media (max-width: 800px) {
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
  .room-identity > b {
    font-size: 28px;
  }
  .room-focus-card {
    padding: 16px;
  }
  .focus-number {
    font-size: 96px;
  }
  .focus-guide {
    font-size: 20px;
  }
  .waiting-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: repeat(3, minmax(78px, 1fr));
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
}
@media (max-height: 760px) and (min-width: 801px) {
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
    font-size: clamp(56px, 7.5vw, 96px);
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
}
</style>
