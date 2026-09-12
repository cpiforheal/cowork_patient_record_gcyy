<template>
  <div class="health-archive-page">
    <header class="hap-head">
      <div class="hap-title">
        <strong>住院 · 门诊患者健康档案</strong>
        <small>入院确认为住院即由护理部维护跟进 · 门诊患者同步建档 · 鼠标横移聚焦病历夹，点击进入维护</small>
      </div>
      <div class="hap-actions">
        <el-input v-model="keyword" placeholder="按姓名或病例编号搜索" clearable :prefix-icon="Search" style="width: 220px" />
        <el-segmented v-model="careFilter" :options="careFilterOptions" />
        <el-button :loading="loading" @click="loadCases">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </header>

    <!-- 3D 病历夹：患者病历像实体病历夹竖着摞起，鼠标横移聚焦 -->
    <div
      ref="stageRef"
      class="deck-stage"
      :class="{ 'is-dragging': dragging }"
      @mousedown="onDragStart"
      @mousemove="onDragMove"
      @mouseup="onDragEnd"
      @mouseleave="onDragEnd"
      @wheel.prevent="onWheel"
    >
      <div class="deck-scene">
        <div
          v-for="card in deckCards"
          :key="card.key"
          class="deck-card"
          :class="{ 'is-focus': card.isFocus }"
          :style="card.style"
          @mouseenter="onCardHover(card)"
          @click="onCardClick(card)"
        >
          <div class="deck-band">
            <span>{{ card.patient.latestEncounter?.caseToken || "—" }}</span>
            <span>{{ card.patient.visitCount }} 次</span>
          </div>
          <div class="deck-body">
            <div class="deck-name">{{ card.patient.patientName || "待补姓名" }}</div>
            <div class="deck-complaint">主诉：{{ truncate(complaintOf(card.patient), 26) || "—" }}</div>
            <div class="deck-phone">📱 {{ card.patient.phone || "手机号未登记" }}</div>
            <div class="deck-diseases">
              <span v-for="disease in diseasesOf(card.patient)" :key="disease" class="deck-disease">{{ disease }}</span>
            </div>
            <div class="deck-date">🗓 {{ visitDate(card.patient) || "—" }}</div>
          </div>
          <div class="deck-tab">第 {{ card.patient.visitCount }} 次</div>
          <div v-if="card.isFocus" class="deck-focus-pill">点击进入健康档案</div>
        </div>
        <div v-if="!deckCards.length && !loading" class="deck-empty">暂无患者病历</div>
      </div>
      <button type="button" class="deck-arrow deck-arrow-left" @click="step(-1)">‹</button>
      <button type="button" class="deck-arrow deck-arrow-right" @click="step(1)">›</button>
      <div class="deck-progress" v-if="list.length">{{ focusIdx + 1 }} / {{ list.length }}</div>
    </div>

    <!-- 聚焦患者信息条 -->
    <div class="focus-bar">
      <template v-if="focusedPatient">
        <div class="focus-info">
          <strong class="focus-name">{{ focusedPatient.patientName || "待补姓名" }}</strong>
          <span class="focus-care">{{ careLabelOf(focusedPatient) }}患者</span>
          <span class="focus-line">📞 {{ focusedPatient.patient?.phone || "手机号未登记" }}</span>
          <span class="focus-line hap-address">📍 {{ focusedPatient.patient?.address || "住址未登记" }}</span>
          <span class="focus-line">🗓 接诊日期 {{ visitDate(focusedPatient) || "—" }}</span>
        </div>
        <div class="focus-tags">
          <span v-for="disease in diseasesOf(focusedPatient)" :key="disease" class="hap-tag-disease">{{ disease }}</span>
        </div>
        <div class="focus-spacer"></div>
        <el-button type="primary" :disabled="!focusedPatient.latestEncounter" @click="openArchive(focusedPatient)">
          进入健康档案
        </el-button>
      </template>
      <template v-else>
        <span class="focus-empty">当前筛选条件下没有患者病历</span>
      </template>
    </div>

    <HealthArchiveDialog v-model="archiveVisible" :encounter-id="activeEncounterId" :encounter-patient-name="activePatientName" />
  </div>
</template>

<script setup lang="ts" name="healthArchive">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh, Search } from "@element-plus/icons-vue";
import HealthArchiveDialog from "@/views/preAi/encounters/components/HealthArchiveDialog.vue";
import { getPreAiPatientCasesApi, type PreAiPatientCase } from "@/api/modules/clinic/preAi";

const cases = ref<PreAiPatientCase[]>([]);
const loading = ref(false);
const keyword = ref("");
const careFilter = ref("全部");
const careFilterOptions = ["全部", "住院", "门诊"];

const archiveVisible = ref(false);
const activeEncounterId = ref("");
const activePatientName = ref("");

// ---------- 数据与筛选 ----------
const list = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  return cases.value.filter(item => {
    if (careFilter.value !== "全部" && careLabelOf(item) !== careFilter.value) return false;
    if (!kw) return true;
    return (
      String(item.patientName || "")
        .toLowerCase()
        .includes(kw) ||
      String(item.latestEncounter?.caseToken || "")
        .toLowerCase()
        .includes(kw)
    );
  });
});

function careLabelOf(item: PreAiPatientCase) {
  const careType = String(
    item.latestEncounter?.normalizedCareType || item.latestEncounter?.inventoryCareType || item.latestEncounter?.route || ""
  );
  return careType.includes("inpatient") ? "住院" : "门诊";
}
const complaintOf = (item: PreAiPatientCase) =>
  String(
    item.patient?.registrationChiefComplaint || item.patient?.registrationSymptoms || item.patient?.chiefComplaint || ""
  ).trim();
const visitDate = (item: PreAiPatientCase) =>
  String(item.patient?.visitDate || item.latestEncounter?.visitDate || "")
    .replace("T", " ")
    .slice(0, 16);
const truncate = (value: string, maxLength = 26) => {
  const text = String(value || "")
    .replace(/\s+/g, " ")
    .trim();
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
};

// ---------- 3D 病历夹 ----------
const stageRef = ref<HTMLElement | null>(null);
const focusFloat = ref(0);
const focusIdx = ref(0);
const focusedPatient = computed(() => list.value[focusIdx.value]);

const CARD_GAP = 158;
const DECK_TILT = -12;

interface DeckCard {
  key: string;
  index: number;
  patient: PreAiPatientCase;
  offset: number;
  isFocus: boolean;
  style: Record<string, string>;
}
const deckCards = computed<DeckCard[]>(() => {
  const total = list.value.length;
  if (!total) return [];
  const focus = dragging.value ? focusFloat.value : focusIdx.value;
  const start = Math.max(0, Math.floor(focus) - 3);
  const end = Math.min(total, Math.floor(focus) + 5);
  const cards: DeckCard[] = [];
  for (let i = start; i < end; i++) {
    const item = list.value[i];
    const offset = i - focus;
    const abs = Math.abs(offset);
    if (abs > 2.6) continue;
    cards.push({
      key: item.id || `idx-${i}`,
      index: i,
      patient: item,
      offset,
      isFocus: abs < 0.5,
      style: {
        left: `calc(50% + ${Math.round(offset * CARD_GAP - 116)}px)`,
        zIndex: String(100 - Math.round(abs * 10)),
        transform: `rotateX(5deg) rotateY(${DECK_TILT}deg) translateZ(${-Math.round(abs * 80)}px)`,
        filter: `brightness(${Math.max(0.5, 1 - abs * 0.15)})`,
        opacity: String(Math.max(0, 1 - abs * 0.22))
      }
    });
  }
  return cards;
});
const followFocus = (index: number) => {
  focusIdx.value = Math.max(0, Math.min(index, list.value.length - 1));
  focusFloat.value = focusIdx.value;
};
const onCardClick = (card: DeckCard) => {
  if (dragMoved) {
    dragMoved = false;
    return;
  }
  if (card.isFocus) {
    openArchive(card.patient);
    return;
  }
  followFocus(card.index);
};
const step = (dir: number) => followFocus(focusIdx.value + dir);
// 拖拽滑动：按住横向拖动，整摞病历夹随拖拽量平滑滑动，松手吸附到最近一份
const dragging = ref(false);
let dragStartX = 0;
let dragStartFocus = 0;
let dragMoved = false;
let wheelAcc = 0;
const onDragStart = (event: MouseEvent) => {
  if (event.button !== 0) return;
  dragging.value = true;
  dragMoved = false;
  dragStartX = event.clientX;
  dragStartFocus = focusFloat.value;
};
const onDragMove = (event: MouseEvent) => {
  if (!dragging.value) return;
  const dx = event.clientX - dragStartX;
  if (Math.abs(dx) > 8) dragMoved = true;
  focusFloat.value = Math.max(0, Math.min(list.value.length - 1, dragStartFocus - dx / CARD_GAP));
};
const onDragEnd = () => {
  if (!dragging.value) return;
  dragging.value = false;
  focusIdx.value = Math.max(0, Math.min(Math.round(focusFloat.value), list.value.length - 1));
  focusFloat.value = focusIdx.value;
};
const onCardHover = (card: DeckCard) => {
  if (dragging.value || dragMoved) return;
  followFocus(card.index);
};
const onWheel = (event: WheelEvent) => {
  if (Math.abs(event.deltaY) <= Math.abs(event.deltaX)) return;
  event.preventDefault();
  wheelAcc += event.deltaY;
  if (Math.abs(wheelAcc) >= 60) {
    step(wheelAcc > 0 ? 1 : -1);
    wheelAcc = 0;
  }
};

// ---------- 档案弹窗 ----------
const openArchive = (patient: PreAiPatientCase) => {
  if (!patient.latestEncounter) return;
  activeEncounterId.value = patient.latestEncounter.id;
  activePatientName.value = patient.patientName || "";
  archiveVisible.value = true;
};

const diseasesOf = (item: PreAiPatientCase) => {
  const diseases = item.patient?.clinicalTemplateDiseases;
  return Array.isArray(diseases) ? diseases.map(String).filter(Boolean) : [];
};

const loadCases = async () => {
  loading.value = true;
  try {
    const { data } = await getPreAiPatientCasesApi();
    cases.value = data.list || [];
    focusIdx.value = 0;
    focusFloat.value = 0;
  } catch (error) {
    ElMessage.error((error as Error).message || "患者列表加载失败");
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  void loadCases();
});
</script>

<style scoped lang="scss">
.health-archive-page {
  display: grid;
  gap: 14px;
}
.hap-head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  .hap-title {
    display: grid;
    gap: 3px;
    strong {
      font-size: 17px;
      color: var(--el-text-color-primary);
    }
    small {
      color: var(--el-text-color-secondary);
    }
  }
  .hap-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }
}

// 3D 病历夹舞台：transform 使其成为 3D 子元素的包含块，防止内容逃逸裁剪
.deck-stage {
  position: relative;
  height: 460px;
  overflow: hidden;
  cursor: ew-resize;
  background:
    radial-gradient(900px 320px at 50% 0%, color-mix(in srgb, var(--el-color-primary) 7%, transparent), transparent 72%),
    var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 16px;
  transform: translateZ(0);
}
.deck-scene {
  position: absolute;
  inset: 0;
  transform: rotateY(-10deg) rotateX(4deg) translateZ(0);
  perspective: 1400px;
  transform-style: preserve-3d;
}
.deck-stage.is-dragging {
  cursor: grabbing;
}
.deck-stage.is-dragging .deck-card {
  transition: none;
}
.deck-phone {
  font-size: 12px;
  color: var(--el-color-primary);
}
.deck-card {
  position: absolute;
  top: 34px;
  width: 232px;
  height: 356px;
  overflow: hidden;
  cursor: pointer;
  background: linear-gradient(180deg, #ffffff 0%, #eef2f7 100%);
  border: 1px solid var(--el-border-color-light);
  border-radius: 14px;
  box-shadow: 0 24px 48px rgb(15 23 42 / 22%);
  transition:
    left 0.45s cubic-bezier(0.4, 0.2, 0.2, 1),
    transform 0.45s cubic-bezier(0.4, 0.2, 0.2, 1),
    filter 0.4s ease,
    opacity 0.4s ease;
  &.is-focus {
    border-color: var(--el-color-primary);
    box-shadow:
      0 26px 56px rgb(15 23 42 / 26%),
      0 0 0 2px color-mix(in srgb, var(--el-color-primary) 45%, transparent);
  }
}
.deck-band {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 7px 12px;
  font-size: 11px;
  color: #ffffff;
  white-space: nowrap;
  background: linear-gradient(90deg, var(--el-color-primary), var(--el-color-primary-dark-2, #2e7d32));
}
.deck-body {
  display: grid;
  gap: 8px;
  align-content: start;
  padding: 14px;
}
.deck-name {
  overflow: hidden;
  font-size: 20px;
  font-weight: 700;
  color: var(--el-text-color-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.deck-complaint {
  display: -webkit-box;
  min-height: 38px;
  overflow: hidden;
  font-size: 12px;
  line-height: 1.55;
  color: var(--el-text-color-secondary);
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.deck-diseases {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  .deck-disease {
    padding: 2px 8px;
    font-size: 11px;
    font-weight: 600;
    color: #92400e;
    background: #fef3c7;
    border-radius: 999px;
  }
}
.deck-date {
  font-size: 12px;
  color: var(--el-color-success);
}
.deck-tab {
  position: absolute;
  right: 12px;
  bottom: 12px;
  padding: 3px 9px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color);
  border-radius: 999px;
}
.deck-focus-pill {
  position: absolute;
  bottom: 14px;
  left: 14px;
  padding: 5px 12px;
  font-size: 12px;
  font-weight: 600;
  color: #ffffff;
  background: var(--el-color-primary);
  border-radius: 999px;
  box-shadow: 0 6px 16px color-mix(in srgb, var(--el-color-primary) 40%, transparent);
}
.deck-empty {
  padding: 80px 0;
  font-size: 14px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.deck-arrow {
  position: absolute;
  top: 50%;
  z-index: 200;
  width: 38px;
  height: 38px;
  font-size: 20px;
  line-height: 1;
  color: var(--el-text-color-primary);
  cursor: pointer;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 50%;
  box-shadow: 0 6px 18px rgb(15 23 42 / 12%);
  transform: translateY(-50%);
  &:hover {
    color: var(--el-color-primary);
    border-color: var(--el-color-primary);
  }
}
.deck-arrow-left {
  left: 14px;
}
.deck-arrow-right {
  right: 14px;
}
.deck-progress {
  position: absolute;
  right: 16px;
  bottom: 12px;
  z-index: 200;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-secondary);
}

// 聚焦患者信息条
.focus-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  padding: 14px 18px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  box-shadow: 0 10px 24px rgb(15 23 42 / 6%);
}
.focus-info {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  align-items: center;
  .focus-name {
    font-size: 17px;
    font-weight: 700;
    color: var(--el-text-color-primary);
  }
  .focus-care {
    padding: 2px 10px;
    font-size: 12px;
    color: var(--el-color-success);
    background: var(--el-color-success-light-9);
    border-radius: 999px;
  }
  .focus-line {
    font-size: 13px;
    color: var(--el-text-color-regular);
  }
  .hap-address {
    max-width: 260px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
.focus-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  .hap-tag-disease {
    padding: 3px 10px;
    font-size: 12px;
    font-weight: 600;
    color: #92400e;
    background: #fef3c7;
    border-radius: 999px;
  }
}
.focus-spacer {
  flex: 1 1 auto;
}
.focus-empty {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
