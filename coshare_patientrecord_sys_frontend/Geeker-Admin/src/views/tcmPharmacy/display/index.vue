<template>
  <DisplayShell
    title="中药房取药"
    subtitle="TCM PHARMACY PICKUP DISPLAY"
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
    <div class="display-content">
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
    </div>

    <template #footer-guide>
      <div class="footer-mark"><span>本草</span>中药房 · 请留意屏幕及语音播报</div>
    </template>

    <template #overlay>
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
    </template>
  </DisplayShell>
</template>

<script setup lang="ts" name="tcmPharmacyDisplay">
import { onBeforeUnmount, onMounted, reactive } from "vue";
import DisplayShell from "@/components/DisplayShell/index.vue";
import { useDisplayClock } from "@/hooks/useDisplayClock";
import { useDisplayPolling } from "@/hooks/useDisplayPolling";
import { useAnnouncementPlayer } from "@/hooks/useAnnouncementPlayer";
import { setAuthRedirectSuppressed } from "@/api/modules/authToken";
import {
  getPendingTcmAnnouncementsApi,
  getTcmDisplayApi,
  markTcmAnnouncementPlayedApi,
  type TcmAnnouncement,
  type TcmDisplaySnapshot
} from "@/api/modules/clinic/tcmPharmacy";

const snapshot = reactive<TcmDisplaySnapshot>({
  ready: [],
  waiting: [],
  counts: { waitingCharge: 0, waitingReview: 0, dispensing: 0, decocting: 0, ready: 0, collectedToday: 0, exception: 0 },
  serverTime: "",
  refreshSeconds: 5
});

const { clock, dateText, calibrate } = useDisplayClock();

const { offline, sessionExpired, lastUpdated, offlineSince, refreshNow, setIntervalSeconds } = useDisplayPolling({
  refresh: async () => {
    const [{ data }, announcements] = await Promise.all([getTcmDisplayApi(), getPendingTcmAnnouncementsApi()]);
    Object.assign(snapshot, data);
    setIntervalSeconds(data.refreshSeconds);
    calibrate(data.serverTime);
    playIfNew(announcements.data.rows.find(item => !hasPlayed(item.id)));
  }
});

const { currentCall, audioBlocked, audioEnabling, enableAudio, playIfNew, hasPlayed } = useAnnouncementPlayer<TcmAnnouncement>({
  unlockStorageKey: "tcm-pharmacy-audio-enabled",
  markPlayed: markTcmAnnouncementPlayedApi,
  requestRefresh: refreshNow,
  popupDurationMs: 9000,
  repeat: 2,
  channelName: "tcm-pharmacy-calls",
  channelMessageType: "TCM_CALL_CREATED",
  storageEventKey: "tcm-pharmacy-call-event",
  confirmationText: "中药房取药叫号语音已开启"
});

function typeLabel(type: string) {
  return type === "HOSPITAL_DECOCTION" ? "院内代煎" : "患者自煎";
}
function progressLabel(status: string, type: string) {
  return status === "DECOCTING" ? "代煎制作中" : type === "HOSPITAL_DECOCTION" ? "调剂复核中" : "抓药配药中";
}

onMounted(() => setAuthRedirectSuppressed(true));
onBeforeUnmount(() => setAuthRedirectSuppressed(false));
</script>

<style scoped lang="scss">
.display-content {
  height: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1.8fr) minmax(340px, 0.72fr);
  gap: 22px;
  min-height: 0;
  padding: 24px 42px;
  box-sizing: border-box;
}
.ready-board,
.waiting-board {
  min-height: 0;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--dsp-accent-green) 22%, var(--dsp-line-strong));
  border-radius: var(--dsp-radius-card);
  background: var(--dsp-surface);
  box-shadow: var(--dsp-shadow-card);
}
.board-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 70px;
  padding: 0 24px;
  border-bottom: 1px solid var(--dsp-line);
  background: linear-gradient(90deg, color-mix(in srgb, var(--dsp-accent-green) 8%, transparent), transparent);
  > div {
    display: flex;
    align-items: center;
    gap: 13px;
  }
  b {
    font-size: 22px;
    letter-spacing: 0.1em;
  }
  > strong {
    color: var(--dsp-accent-green-deep);
    font-family: var(--dsp-font-numeric);
    font-size: 30px;
    font-variant-numeric: tabular-nums;
  }
}
.section-seal {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--dsp-accent-green) 32%, var(--dsp-line));
  border-radius: 9px;
  color: var(--dsp-accent-green-deep);
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
  border-bottom: 1px solid var(--dsp-line);
  color: var(--dsp-muted);
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
  position: relative;
  min-height: 88px;
  margin-top: 8px;
  padding: 9px 18px 9px 22px;
  border: 1px solid var(--dsp-line);
  border-radius: 14px;
  background: var(--dsp-tile);
  transition:
    background-color 0.35s ease,
    border-color 0.35s ease;
  &.calling {
    border-color: color-mix(in srgb, var(--dsp-accent-green) 45%, var(--dsp-line));
    background: color-mix(in srgb, var(--dsp-accent-green) 10%, var(--dsp-surface));
    animation: ready-calling-blink 2.4s ease-in-out infinite;
    &::before {
      content: "";
      position: absolute;
      top: 12%;
      bottom: 12%;
      left: 6px;
      width: 4px;
      border-radius: 4px;
      background: var(--dsp-accent-green);
    }
    .status-card {
      border-color: var(--dsp-accent-green);
      color: #ffffff;
      background: var(--dsp-accent-green);
    }
  }
}
@keyframes ready-calling-blink {
  50% {
    background: color-mix(in srgb, var(--dsp-accent-green) 20%, var(--dsp-surface));
  }
}
.pickup-number {
  color: var(--dsp-accent-green-deep);
  font-family: var(--dsp-font-numeric);
  font-size: clamp(34px, 3vw, 52px);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.04em;
  line-height: 1;
}
.patient-name {
  color: var(--dsp-ink);
  font-size: 22px;
  letter-spacing: 0.08em;
}
.cell-card {
  width: fit-content;
  min-width: 102px;
  padding: 7px 12px;
  border: 1px solid var(--dsp-line);
  border-radius: 10px;
  text-align: center;
}
.type-card {
  color: var(--dsp-muted);
  background: var(--dsp-surface-soft);
}
.status-card {
  color: var(--dsp-accent-green-deep);
  background: color-mix(in srgb, var(--dsp-mint) 40%, transparent);
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
  border-bottom: 1px solid var(--dsp-line);
  b {
    color: var(--dsp-accent-green-deep);
    font-family: var(--dsp-font-numeric);
    font-size: 20px;
    font-variant-numeric: tabular-nums;
  }
  span {
    font-size: 17px;
    letter-spacing: 0.08em;
  }
  em {
    padding: 7px 9px;
    border: 1px solid var(--dsp-line);
    border-radius: 9px;
    color: var(--dsp-muted);
    background: var(--dsp-surface-soft);
    font-size: 12px;
    font-style: normal;
  }
}
.waiting-empty,
.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--dsp-muted);
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
    border: 1px solid color-mix(in srgb, var(--dsp-accent-green) 32%, var(--dsp-line));
    border-radius: 18px;
    color: var(--dsp-accent-green-deep);
    font:
      normal 36px "STSong",
      "SimSun",
      serif;
  }
  strong {
    margin-top: 20px;
    color: var(--dsp-muted);
    font-size: 21px;
    letter-spacing: 0.08em;
  }
}
.count-strip {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  min-height: 98px;
  border-top: 1px solid var(--dsp-line);
  background: linear-gradient(90deg, color-mix(in srgb, var(--dsp-accent-green) 6%, transparent), transparent);
  div {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    border-right: 1px solid var(--dsp-line);
    &:last-child {
      border-right: 0;
    }
  }
  span {
    color: var(--dsp-muted);
    font-size: 13px;
  }
  b {
    margin-top: 6px;
    color: var(--dsp-accent-green-deep);
    font-family: var(--dsp-font-numeric);
    font-size: 26px;
    font-variant-numeric: tabular-nums;
  }
}
.footer-mark {
  letter-spacing: 0.12em;
  span {
    margin-right: 10px;
    color: var(--dsp-accent-green-deep);
    font-family: "STSong", "SimSun", serif;
  }
}
.calling-overlay {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: grid;
  place-items: center;
  background: rgba(56, 91, 79, 0.46);
  backdrop-filter: blur(8px);
}
.calling-card {
  position: relative;
  width: min(760px, 76vw);
  max-height: calc(100dvh - 32px);
  box-sizing: border-box;
  padding: clamp(42px, 7vmin, 68px) clamp(24px, 5vmin, 50px) clamp(34px, 6vmin, 62px);
  overflow: auto;
  border: 3px solid var(--dsp-accent-green);
  border-radius: 24px;
  background: var(--dsp-surface);
  box-shadow: 0 24px 70px rgba(48, 91, 75, 0.2);
  text-align: center;
  animation: tcm-calling-breathe 2.2s ease-in-out infinite;
  .calling-seal {
    position: absolute;
    top: 24px;
    left: 28px;
    display: grid;
    width: 46px;
    height: 46px;
    place-items: center;
    border: 1px solid var(--dsp-line);
    border-radius: 12px;
    color: var(--dsp-accent-green-deep);
    font:
      26px "STSong",
      "SimSun",
      serif;
  }
  p {
    margin: 0;
    color: var(--dsp-accent-green-deep);
    font-family: var(--dsp-font-numeric);
    font-size: var(--dsp-fs-overlay-number);
    font-variant-numeric: tabular-nums;
    letter-spacing: 0.08em;
  }
  strong {
    display: block;
    margin-top: 18px;
    font-size: var(--dsp-fs-overlay-guide);
    letter-spacing: 0.14em;
  }
  h2 {
    margin: 30px 0 0;
    padding-top: 24px;
    border-top: 1px solid var(--dsp-line);
    color: var(--dsp-muted);
    font-size: clamp(18px, 3vmin, 27px);
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
@keyframes tcm-calling-breathe {
  50% {
    border-color: color-mix(in srgb, var(--dsp-accent-green) 55%, #ffffff);
    box-shadow:
      0 24px 70px rgba(48, 91, 75, 0.2),
      0 0 0 10px color-mix(in srgb, var(--dsp-accent-green) 18%, transparent);
  }
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
@media (max-width: 1200px) {
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
  .ready-table-head {
    display: none;
  }
  .ready-row {
    grid-template-columns: 1.1fr 0.8fr;
  }
}
</style>
