<template>
  <div class="table-box encounter-page">
    <section class="board-toolbar">
      <div>
        <h2>患者流程进度</h2>
        <p>以 Pre-AI 就诊流程的实时阶段为准，优先处理当前岗位、退回和异常病例。</p>
      </div>
      <div class="toolbar-actions">
        <el-radio-group v-model="viewMode" size="large">
          <el-radio-button label="kanban">看板</el-radio-button>
          <el-radio-button label="list">列表</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" :loading="loading" @click="loadProgress">刷新</el-button>
      </div>
    </section>

    <section class="progress-summary">
      <button
        v-for="item in summaryItems"
        :key="item.key"
        type="button"
        class="summary-item"
        :class="{ active: activeSummary === item.key }"
        @click="activeSummary = item.key"
      >
        <span>{{ item.label }}</span>
        <strong>{{ item.count }}</strong>
      </button>
    </section>

    <section class="progress-filters">
      <el-input v-model="filters.keyword" clearable placeholder="患者姓名、就诊号" />
      <el-select v-model="filters.careType" clearable placeholder="就诊类型">
        <el-option label="门诊" value="outpatient" />
        <el-option label="住院" value="inpatient" />
      </el-select>
      <el-select v-model="filters.stage" clearable placeholder="当前阶段">
        <el-option v-for="stage in workflowStages" :key="stage.key" :label="stage.title" :value="stage.key" />
        <el-option label="待核验旧流程" value="LEGACY" />
      </el-select>
      <el-button v-if="hasFilters" text @click="clearFilters">清除筛选</el-button>
    </section>

    <section v-if="viewMode === 'kanban'" v-loading="loading" class="kanban-board">
      <article v-for="column in kanbanColumns" :key="column.key" class="kanban-column">
        <header>
          <span>{{ column.title }}</span>
          <el-tag effect="plain">{{ column.patients.length }}</el-tag>
          <small>{{ column.owner }}</small>
        </header>
        <div class="kanban-list">
          <button
            v-for="patient in column.patients"
            :key="patient.id"
            type="button"
            class="patient-card"
            :class="riskClass(patient)"
            @click="openPatient(patient)"
          >
            <div class="patient-card-head">
              <strong><span class="patient-status-dot"></span>{{ patient.name }}</strong>
              <el-tag :type="patient.riskType" effect="plain">{{ encounterStatusLabel(patient.status) }}</el-tag>
            </div>
            <span>{{ careTypeLabel(patient.normalizedCareType) }} · {{ patient.visitNo || "未登记号" }}</span>
            <small>{{ stageLabel(patient.currentStage) }} · {{ patient.nextOwner || "待分派" }}</small>
            <span v-if="patient.returned" class="return-note">存在退回，需重新处理</span>
            <span v-else-if="patient.skippedStages.includes('TCM')" class="encounter-count">门诊跳过中医</span>
            <div class="closed-loop-progress" :class="riskClass(patient)">
              <span><em :style="{ width: patient.progressPercent + '%' }"></em></span>
              <small>{{ patient.completed }}/{{ patient.total }}</small>
            </div>
            <div class="stay-line" :class="{ timeout: isTimeout(patient) }">
              <span>{{ patient.updatedAt ? stayDuration(patient.updatedAt) : "等待核验" }}</span>
              <em>{{ patient.progressPercent }}%</em>
            </div>
          </button>
          <el-empty v-if="!column.patients.length" :image-size="54" description="暂无待处理病例" />
        </div>
      </article>
    </section>

    <section v-else v-loading="loading" class="progress-table-wrap">
      <el-table :data="filteredPatients" row-key="id" max-height="calc(100vh - 330px)" @row-click="openTablePatient">
        <el-table-column prop="name" label="患者" min-width="120" />
        <el-table-column prop="visitNo" label="就诊号" min-width="130" />
        <el-table-column label="就诊类型" width="110">
          <template #default="{ row }">{{ careTypeLabel(row.normalizedCareType) }}</template>
        </el-table-column>
        <el-table-column label="当前阶段" min-width="135">
          <template #default="{ row }"
            ><el-tag effect="plain">{{ stageLabel(row.currentStage) }}</el-tag></template
          >
        </el-table-column>
        <el-table-column label="流程" min-width="300">
          <template #default="{ row }">
            <div class="step-indicator">
              <el-tooltip
                v-for="step in row.steps"
                :key="step.key"
                :content="step.title + (step.status === 'skipped' ? '（门诊跳过）' : '')"
              >
                <span class="step-segment" :class="step.status">{{ step.shortTitle }}</span>
              </el-tooltip>
            </div>
            <div class="progress-meta">
              <span>{{ row.completed }}/{{ row.total }}</span
              ><strong>{{ row.nextOwner || "待分派" }}</strong>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }"
            ><el-tag :type="row.riskType">{{ encounterStatusLabel(row.status) }}</el-tag></template
          >
        </el-table-column>
        <el-table-column label="最近更新" min-width="160">
          <template #default="{ row }">{{ row.updatedAt || "--" }}</template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="100">
          <template #default="{ row }"
            ><el-button type="primary" :icon="ArrowRight" link @click.stop="openTablePatient(row)">进入</el-button></template
          >
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script setup lang="ts" name="encounterActive">
import { computed, onMounted, reactive, ref } from "vue";
import { ArrowRight, Refresh } from "@element-plus/icons-vue";
import { useRouter } from "vue-router";
import {
  getPreAiPatientCasesApi,
  type PreAiPatientCase,
  type PreAiStageCode,
  type PreAiStageStatus
} from "@/api/modules/clinic/preAi";
import { useUserStore } from "@/stores/modules/user";
import { usePatientNavigation } from "@/hooks/usePatientNavigation";

type RiskType = "success" | "info" | "warning" | "danger";
type ProgressStage = PreAiStageCode | "LEGACY";
type ProgressStep = {
  key: PreAiStageCode;
  title: string;
  shortTitle: string;
  owner: string;
  status: "done" | "active" | "waiting" | "skipped" | "returned";
};
type ProgressPatient = {
  id: string;
  sourcePatientId?: string;
  encounterId?: string;
  name: string;
  visitNo: string;
  status: string;
  currentStage: ProgressStage;
  skippedStages: PreAiStageCode[];
  normalizedCareType?: "outpatient" | "inpatient";
  nextOwner?: string;
  updatedAt: string;
  returned: boolean;
  riskType: RiskType;
  completed: number;
  total: number;
  progressPercent: number;
  steps: ProgressStep[];
};

const workflowStages: Array<{ key: PreAiStageCode; title: string; shortTitle: string; owner: string; roles: string[] }> = [
  { key: "REGISTRATION", title: "前台建档", shortTitle: "建档", owner: "前台", roles: ["frontdesk"] },
  { key: "INSPECTION", title: "检查评估", shortTitle: "检查", owner: "检查医生", roles: ["inspection"] },
  { key: "RECEPTION", title: "接诊", shortTitle: "接诊", owner: "接诊医生", roles: ["reception"] },
  { key: "NURSING", title: "护理部评估", shortTitle: "护理", owner: "护理部", roles: ["nurse", "nursing"] },
  { key: "TCM", title: "中医辨证", shortTitle: "中医", owner: "中医医生", roles: ["tcm"] },
  { key: "DOCTOR", title: "医生诊疗", shortTitle: "诊疗", owner: "主治医生", roles: ["doctor"] },
  { key: "SURGERY", title: "手术处置", shortTitle: "手术", owner: "手术岗位", roles: ["surgeon"] },
  { key: "REVIEW", title: "复盘归档", shortTitle: "归档", owner: "复核医生", roles: ["review"] }
];

const router = useRouter();
const userStore = useUserStore();
const { openPatientDetail } = usePatientNavigation();
const viewMode = ref<"kanban" | "list">("kanban");
const loading = ref(false);
const patientCases = ref<PreAiPatientCase[]>([]);
const activeSummary = ref<"all" | "todo" | "returned" | "completed" | "legacy">("all");
const filters = reactive({ keyword: "", careType: "", stage: "" });

const currentRole = computed(() => userStore.userInfo.role || "");
const hasFilters = computed(() => Boolean(filters.keyword || filters.careType || filters.stage || activeSummary.value !== "all"));
const stageLabel = (stage: ProgressStage) => {
  if (stage === "LEGACY") return "待核验旧流程";
  return workflowStages.find(item => item.key === stage)?.title || stage;
};
const careTypeLabel = (careType?: string) => (careType === "inpatient" ? "住院" : careType === "outpatient" ? "门诊" : "待核验");
const encounterStatusLabel = (status: string) =>
  ({
    IN_PROGRESS: "处理中",
    PENDING_REVIEW: "待复核",
    REVIEWED: "已复核",
    EXPORTED: "已归档",
    CANCELLED: "已终止",
    LEGACY: "待核验"
  })[status] || status;

const toProgressPatient = (patientCase: PreAiPatientCase): ProgressPatient => {
  const encounter = patientCase.latestEncounter;
  if (!encounter) {
    return {
      id: "legacy-" + patientCase.id,
      sourcePatientId: patientCase.sourcePatientId,
      name: patientCase.patientName,
      visitNo: "",
      status: "LEGACY",
      currentStage: "LEGACY",
      skippedStages: [],
      updatedAt: patientCase.updatedAt,
      returned: false,
      riskType: "info",
      completed: 0,
      total: 0,
      progressPercent: 0,
      steps: []
    };
  }
  const statuses = encounter.effectiveStageStatuses || encounter.stageStatuses || {};
  const skippedStages = encounter.skippedStages || [];
  const currentStage = encounter.effectiveCurrentStage || encounter.currentStage;
  const closed = ["REVIEWED", "EXPORTED"].includes(encounter.status) && statuses.REVIEW === "COMPLETED";
  const steps: ProgressStep[] = workflowStages.map(stage => {
    const stageStatus = statuses[stage.key] as PreAiStageStatus | undefined;
    const skipped = skippedStages.includes(stage.key) || stageStatus === "SKIPPED";
    const returned = stageStatus === "RETURNED";
    return {
      ...stage,
      status: skipped
        ? "skipped"
        : returned
          ? "returned"
          : stageStatus === "COMPLETED" || (closed && stage.key === "REVIEW")
            ? "done"
            : stage.key === currentStage
              ? "active"
              : "waiting"
    };
  });
  const effectiveSteps = steps.filter(step => step.status !== "skipped");
  const completed = effectiveSteps.filter(step => step.status === "done").length;
  const returned = steps.some(step => step.status === "returned");
  const timestamp = new Date(encounter.updatedAt.replace(/-/g, "/")).getTime();
  const stale = Number.isFinite(timestamp) && Date.now() - timestamp > 24 * 36e5;
  const riskType: RiskType = returned ? "danger" : closed ? "success" : stale ? "warning" : "info";
  return {
    id: encounter.id,
    sourcePatientId: encounter.sourcePatientId || patientCase.sourcePatientId,
    encounterId: encounter.id,
    name: encounter.patientName || patientCase.patientName,
    visitNo: String(encounter.visitNo || ""),
    status: encounter.status,
    currentStage,
    skippedStages,
    normalizedCareType: encounter.normalizedCareType || encounter.inventoryCareType,
    nextOwner: encounter.nextOwner,
    updatedAt: encounter.updatedAt,
    returned,
    riskType,
    completed,
    total: effectiveSteps.length,
    progressPercent: effectiveSteps.length ? Math.round((completed / effectiveSteps.length) * 100) : 0,
    steps
  };
};

const progressPatients = computed(() => patientCases.value.map(toProgressPatient));
const filteredPatients = computed(() =>
  progressPatients.value.filter(patient => {
    const keyword = filters.keyword.trim().toLowerCase();
    if (keyword && !(patient.name + " " + patient.visitNo).toLowerCase().includes(keyword)) return false;
    if (filters.careType && patient.normalizedCareType !== filters.careType) return false;
    if (filters.stage && patient.currentStage !== filters.stage) return false;
    if (
      activeSummary.value === "todo" &&
      (patient.currentStage === "LEGACY" || ["REVIEWED", "EXPORTED", "CANCELLED"].includes(patient.status))
    )
      return false;
    if (activeSummary.value === "returned" && !patient.returned) return false;
    if (activeSummary.value === "completed" && !["REVIEWED", "EXPORTED"].includes(patient.status)) return false;
    return !(activeSummary.value === "legacy" && patient.currentStage !== "LEGACY");
  })
);
const summaryItems = computed(() => [
  { key: "all" as const, label: "全部病例", count: progressPatients.value.length },
  {
    key: "todo" as const,
    label: "待处理",
    count: progressPatients.value.filter(
      item => item.currentStage !== "LEGACY" && !["REVIEWED", "EXPORTED", "CANCELLED"].includes(item.status)
    ).length
  },
  { key: "returned" as const, label: "退回异常", count: progressPatients.value.filter(item => item.returned).length },
  {
    key: "completed" as const,
    label: "已完成",
    count: progressPatients.value.filter(item => ["REVIEWED", "EXPORTED"].includes(item.status)).length
  },
  {
    key: "legacy" as const,
    label: "待核验旧流程",
    count: progressPatients.value.filter(item => item.currentStage === "LEGACY").length
  }
]);
const kanbanColumns = computed(() => {
  const columns = [
    ...workflowStages,
    { key: "LEGACY" as const, title: "待核验旧流程", shortTitle: "核验", owner: "历史档案", roles: [] }
  ].map(stage => ({ ...stage, patients: [] as ProgressPatient[] }));
  filteredPatients.value.forEach(patient => {
    const column = columns.find(item => item.key === patient.currentStage) || columns[columns.length - 1];
    column.patients.push(patient);
  });
  return columns;
});

const riskClass = (patient: ProgressPatient) => ({
  timeout: isTimeout(patient),
  current: isCurrentRoleFocus(patient),
  ["risk-" + patient.riskType]: true
});
const clearFilters = () => {
  filters.keyword = "";
  filters.careType = "";
  filters.stage = "";
  activeSummary.value = "all";
};
const stayDuration = (updatedAt: string) => {
  const timestamp = new Date(updatedAt.replace(/-/g, "/")).getTime();
  if (!Number.isFinite(timestamp)) return "更新时间待补充";
  const hours = Math.max(1, Math.round((Date.now() - timestamp) / 36e5));
  return hours >= 24 ? "停留 " + Math.round(hours / 24) + " 天" : "停留 " + hours + " 小时";
};
const isTimeout = (patient: ProgressPatient) => patient.riskType === "warning" || patient.riskType === "danger";
const isCurrentRoleFocus = (patient: ProgressPatient) =>
  workflowStages.find(stage => stage.key === patient.currentStage)?.roles.includes(currentRole.value) || false;
const loadProgress = async () => {
  loading.value = true;
  try {
    const { data } = await getPreAiPatientCasesApi();
    patientCases.value = data.list || [];
  } finally {
    loading.value = false;
  }
};
const openPatient = (patient: ProgressPatient) => {
  if (patient.encounterId) {
    router.push({ path: "/pre-ai/encounters", query: { encounterId: patient.encounterId } });
  } else if (patient.sourcePatientId) {
    openPatientDetail(patient.sourcePatientId);
  }
};
const openTablePatient = (row: unknown) => openPatient(row as ProgressPatient);

onMounted(loadProgress);
</script>

<style scoped lang="scss">
.encounter-page {
  display: flex;
  flex-direction: column;
  min-height: 0;
  gap: 12px;
}
.board-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px;
  background: var(--hos-panel);
  border: 1px solid var(--hos-border);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-soft);
}
.board-toolbar h2,
.board-toolbar p {
  margin: 0;
}
.board-toolbar h2 {
  font-size: 20px;
}
.board-toolbar p {
  margin-top: 5px;
  color: var(--el-text-color-regular);
}
.toolbar-actions,
.progress-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.progress-summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}
.summary-item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  min-width: 0;
  padding: 10px 12px;
  color: var(--hos-text-secondary);
  cursor: pointer;
  background: var(--hos-panel);
  border: 1px solid var(--hos-border);
  border-radius: var(--hos-radius-card);
}
.summary-item strong {
  color: var(--hos-text-primary);
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}
.summary-item.active {
  border-color: var(--hos-border-interactive);
  box-shadow: 0 0 0 2px rgb(var(--hos-primary-rgb) / 10%);
}
.progress-filters {
  padding: 0 2px;
}
.progress-filters :deep(.el-input),
.progress-filters :deep(.el-select) {
  width: min(100%, 210px);
}
.kanban-board {
  display: grid;
  grid-template-columns: repeat(9, minmax(220px, 1fr));
  min-height: 0;
  gap: 12px;
  overflow-x: auto;
}
.kanban-column {
  display: flex;
  flex-direction: column;
  min-height: 0;
  max-height: calc(100vh - 320px);
  padding: 12px;
  overflow: hidden;
  background: var(--hos-glass);
  border: 1px solid var(--hos-border);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-soft);
}
.kanban-column header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 4px 8px;
  margin-bottom: 10px;
  font-weight: 600;
}
.kanban-column header small {
  grid-column: 1 / -1;
  color: var(--hos-text-secondary);
  font-size: 12px;
  font-weight: 400;
}
.kanban-list {
  display: grid;
  min-height: 0;
  gap: 8px;
  padding-right: 4px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.kanban-list :deep(.el-empty) {
  min-height: 96px;
  padding: 12px 0;
}
.patient-card {
  position: relative;
  display: grid;
  min-width: 0;
  gap: 7px;
  padding: 12px;
  overflow: hidden;
  text-align: left;
  cursor: pointer;
  background: var(--hos-panel);
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-card);
}
.patient-card::before {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 4px;
  content: "";
  background: var(--hos-status-info);
}
.patient-card:hover,
.patient-card.current {
  border-color: var(--hos-border-interactive);
  box-shadow: var(--hos-shadow-card-hover);
}
.patient-card.timeout {
  border-color: var(--el-color-danger-light-5);
}
.patient-card.risk-success::before {
  background: var(--hos-status-success);
}
.patient-card.risk-warning::before {
  background: var(--hos-status-warning);
}
.patient-card.risk-danger::before {
  background: var(--hos-status-danger);
}
.patient-card strong {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
  color: var(--hos-text-primary);
  font-size: 16px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.patient-card span,
.patient-card small {
  overflow: hidden;
  color: var(--hos-text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.patient-card-head,
.stay-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.patient-status-dot {
  width: 10px;
  height: 10px;
  flex: 0 0 auto;
  background: var(--hos-primary);
  border-radius: 999px;
}
.encounter-count,
.return-note {
  width: fit-content;
  padding: 2px 8px;
  color: var(--hos-primary-deep) !important;
  background: var(--hos-primary-soft);
  border-radius: 999px;
  font-size: 12px;
}
.return-note {
  color: var(--el-color-danger) !important;
  background: var(--el-color-danger-light-9);
}
.closed-loop-progress {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
}
.closed-loop-progress > span {
  height: 8px;
  overflow: hidden;
  background: rgb(255 255 255 / 46%);
  border: 1px solid var(--hos-border-light);
  border-radius: 999px;
}
.closed-loop-progress em {
  display: block;
  height: 100%;
  background: var(--hos-status-info);
  border-radius: inherit;
}
.closed-loop-progress.risk-success em {
  background: var(--hos-status-success);
}
.closed-loop-progress.risk-warning em {
  background: var(--hos-status-warning);
}
.closed-loop-progress.risk-danger em {
  background: var(--hos-status-danger);
}
.closed-loop-progress small {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.stay-line {
  padding-top: 7px;
  border-top: 1px solid var(--hos-border-light);
}
.stay-line em {
  color: var(--hos-primary-deep);
  font-style: normal;
  font-weight: 600;
}
.stay-line.timeout span {
  color: var(--el-color-danger);
  font-weight: 600;
}
.progress-table-wrap {
  min-height: 0;
  overflow: auto;
}
.step-indicator {
  display: grid;
  grid-template-columns: repeat(8, minmax(34px, 1fr));
  gap: 3px;
}
.step-segment {
  display: grid;
  min-height: 24px;
  place-items: center;
  color: var(--hos-text-secondary);
  background: var(--hos-glass);
  border: 1px solid var(--hos-border-light);
  border-radius: 4px;
  font-size: 12px;
}
.step-segment.done {
  color: var(--hos-status-success);
  background: var(--hos-status-success-soft);
}
.step-segment.active {
  color: var(--hos-status-warning);
  background: var(--hos-status-warning-soft);
}
.step-segment.returned {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}
.step-segment.skipped {
  color: var(--hos-text-muted);
  border-style: dashed;
  opacity: 0.65;
}
.progress-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 6px;
  color: var(--el-text-color-regular);
  font-size: 12px;
}
@media (max-width: 980px) {
  .board-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
  .progress-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .kanban-column {
    max-height: calc(100vh - 380px);
  }
}
</style>
