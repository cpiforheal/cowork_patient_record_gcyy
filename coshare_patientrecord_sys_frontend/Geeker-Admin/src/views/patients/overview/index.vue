<template>
  <div class="patient-overview-page">
    <header class="overview-header">
      <div>
        <span class="eyebrow">临床入口</span>
        <h2>患者概览</h2>
        <p>默认展示在途患者；点击卡片可查看浓缩基础检查摘要，再进入前置工作台。</p>
      </div>
      <div class="header-actions">
        <el-segmented v-model="scope" :options="scopeOptions" />
        <el-input
          v-model="keyword"
          clearable
          class="overview-search"
          placeholder="按姓名、就诊号或病例编号搜索"
          :prefix-icon="Search"
        />
        <el-button :icon="Refresh" :loading="loading" @click="loadPatients">刷新</el-button>
      </div>
    </header>

    <section class="summary-strip">
      <article>
        <span>在途患者</span>
        <strong>{{ activeCount }}</strong>
        <small>未归档/未终止</small>
      </article>
      <article>
        <span>退回异常</span>
        <strong>{{ returnedCount }}</strong>
        <small>需优先处理</small>
      </article>
      <article>
        <span>超 24 小时</span>
        <strong>{{ staleCount }}</strong>
        <small>长时间未更新</small>
      </article>
      <article>
        <span>全部患者</span>
        <strong>{{ overviewPatients.length }}</strong>
        <small>含历史病例</small>
      </article>
    </section>

    <section v-loading="loading" class="overview-body" element-loading-text="正在读取患者概览…">
      <el-empty v-if="!filteredPatients.length && !loading" description="暂无匹配患者" />
      <div v-else class="patient-grid">
        <button
          v-for="patient in filteredPatients"
          :key="patient.id"
          type="button"
          class="overview-card"
          :class="[`risk-${patient.riskType}`, { inactive: !patient.encounterId }]"
          @click="openOverview(patient)"
        >
          <div class="card-head">
            <div>
              <span class="patient-name">{{ patient.name || "（未登记姓名）" }}</span>
              <small>{{ patient.gender || "性别待补" }} · {{ patient.age || "年龄待补" }}</small>
            </div>
            <el-tag :type="patient.statusType" effect="plain">{{ encounterStatusLabel(patient.status) }}</el-tag>
          </div>

          <div class="stage-line">
            <el-tag effect="light">{{ stageLabel(patient.currentStage) }}</el-tag>
            <el-tag :type="patient.careType === '住院' ? 'warning' : 'success'" effect="plain">{{ patient.careType }}</el-tag>
          </div>

          <dl class="card-facts">
            <div>
              <dt>就诊号</dt>
              <dd>{{ patient.visitNo || "待生成" }}</dd>
            </div>
            <div>
              <dt>责任岗位</dt>
              <dd>{{ patient.nextOwner || "待分派" }}</dd>
            </div>
            <div>
              <dt>就诊次数</dt>
              <dd>{{ patient.visitCount || 1 }} 次</dd>
            </div>
            <div>
              <dt>最近更新</dt>
              <dd>{{ formatTime(patient.updatedAt) }}</dd>
            </div>
          </dl>

          <div class="card-foot">
            <span v-if="patient.returned" class="alert-text danger">存在退回，需重新处理</span>
            <span v-else-if="patient.stale" class="alert-text warning">超 24 小时未更新</span>
            <span v-else-if="!patient.encounterId" class="alert-text muted">历史档案，暂无前置就诊</span>
            <span v-else class="alert-text normal">点击查看检查摘要</span>
            <el-icon><ArrowRight /></el-icon>
          </div>
        </button>
      </div>
    </section>

    <PatientExamSummaryDrawer
      v-model="drawerVisible"
      :encounter-id="selectedPatient?.encounterId"
      :fallback-name="selectedPatient?.name"
      :fallback-stage="selectedPatient?.currentStage"
      show-workbench
    />
  </div>
</template>

<script setup lang="ts" name="patientsOverview">
import { computed, onMounted, ref } from "vue";
import { ArrowRight, Refresh, Search } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import {
  getPreAiPatientCasesApi,
  type PreAiPatientCase,
  type PreAiStageCode
} from "@/api/modules/clinic/preAi";
import { usePatientNavigation } from "@/hooks/usePatientNavigation";
import PatientExamSummaryDrawer from "@/components/PatientExamSummaryDrawer.vue";

type RiskType = "success" | "info" | "warning" | "danger";
type ProgressStage = PreAiStageCode | "LEGACY";
type StatusType = "success" | "info" | "warning" | "danger";

type OverviewPatient = {
  id: string;
  sourcePatientId?: string;
  encounterId?: string;
  name: string;
  gender: string;
  age: string;
  visitNo: string;
  visitCount: number;
  status: string;
  currentStage: ProgressStage;
  careType: string;
  searchText: string;
  nextOwner?: string;
  updatedAt: string;
  returned: boolean;
  stale: boolean;
  active: boolean;
  riskType: RiskType;
  statusType: StatusType;
};

const workflowStages: Array<{ key: PreAiStageCode; title: string }> = [
  { key: "REGISTRATION", title: "前台建档" },
  { key: "INSPECTION", title: "检查评估" },
  { key: "RECEPTION", title: "接诊" },
  { key: "NURSING", title: "护理部评估" },
  { key: "TCM", title: "中医辨证" },
  { key: "DOCTOR", title: "医生诊疗" },
  { key: "SURGERY", title: "手术处置" },
  { key: "REVIEW", title: "复盘归档" }
];

const { openPatientDetail } = usePatientNavigation();
const scope = ref<"active" | "all">("active");
const keyword = ref("");
const loading = ref(false);
const drawerVisible = ref(false);
const patientCases = ref<PreAiPatientCase[]>([]);
const selectedPatient = ref<OverviewPatient>();

const scopeOptions = [
  { label: "在途", value: "active" },
  { label: "全部", value: "all" }
];

const closedStatuses = new Set(["REVIEWED", "EXPORTED", "CANCELLED"]);
const stageLabel = (stage?: ProgressStage) =>
  stage === "LEGACY" ? "历史档案" : workflowStages.find(item => item.key === stage)?.title || "待分派";
const encounterStatusLabel = (status?: string) =>
  ({
    IN_PROGRESS: "处理中",
    PENDING_REVIEW: "待复核",
    REVIEWED: "已复核",
    EXPORTED: "已归档",
    CANCELLED: "已离院（终止治疗）",
    WITHDRAWN: "已撤回",
    LEGACY: "历史档案"
  })[status || ""] ||
  status ||
  "待核验";
const routeLabel = (value?: string) => {
  const text = String(value || "").toLowerCase();
  if (["inpatient", "in_patient", "住院"].includes(text) || value === "INPATIENT") return "住院";
  if (["outpatient", "out_patient", "门诊"].includes(text) || value === "OUTPATIENT") return "门诊";
  return "待核验";
};
const formatTime = (value?: string) =>
  String(value || "")
    .replace("T", " ")
    .slice(0, 16) || "—";
const isStaleTime = (value?: string) => {
  const timestamp = new Date(String(value || "").replace(/-/g, "/")).getTime();
  return Number.isFinite(timestamp) && Date.now() - timestamp > 24 * 36e5;
};

const toOverviewPatient = (patientCase: PreAiPatientCase): OverviewPatient => {
  const encounter = patientCase.latestEncounter;
  if (!encounter) {
    const searchText = [patientCase.patientName, patientCase.gender, patientCase.age, patientCase.sourcePatientId]
      .join(" ")
      .toLowerCase();
    return {
      id: `legacy-${patientCase.id}`,
      sourcePatientId: patientCase.sourcePatientId,
      name: patientCase.patientName,
      gender: patientCase.gender,
      age: patientCase.age,
      visitNo: "",
      visitCount: patientCase.visitCount || 1,
      status: "LEGACY",
      currentStage: "LEGACY",
      careType: "待核验",
      searchText,
      updatedAt: patientCase.updatedAt,
      returned: false,
      stale: false,
      active: false,
      riskType: "info",
      statusType: "info"
    };
  }
  const statuses = encounter.effectiveStageStatuses || encounter.stageStatuses || {};
  const returned = Object.values(statuses).some(status => status === "RETURNED");
  const stale = isStaleTime(encounter.updatedAt);
  const active = !closedStatuses.has(encounter.status);
  const riskType: RiskType = returned ? "danger" : stale && active ? "warning" : active ? "info" : "success";
  const statusType: StatusType = returned ? "danger" : active ? "info" : encounter.status === "CANCELLED" ? "warning" : "success";
  const visitNo = String(encounter.visitNo || "");
  const searchText = [patientCase.patientName, encounter.patientName, visitNo, encounter.caseToken, patientCase.sourcePatientId]
    .join(" ")
    .toLowerCase();
  return {
    id: encounter.id,
    sourcePatientId: encounter.sourcePatientId || patientCase.sourcePatientId,
    encounterId: encounter.id,
    name: encounter.patientName || patientCase.patientName,
    gender: encounter.gender || patientCase.gender,
    age: encounter.age || patientCase.age,
    visitNo,
    visitCount: patientCase.visitCount || 1,
    status: encounter.status,
    currentStage: encounter.effectiveCurrentStage || encounter.currentStage,
    careType: routeLabel(encounter.normalizedCareType || encounter.inventoryCareType || encounter.route),
    searchText,
    nextOwner: encounter.nextOwner,
    updatedAt: encounter.updatedAt || patientCase.updatedAt,
    returned,
    stale,
    active,
    riskType,
    statusType
  };
};

const overviewPatients = computed(() => patientCases.value.map(toOverviewPatient));
const activeCount = computed(() => overviewPatients.value.filter(patient => patient.active).length);
const returnedCount = computed(() => overviewPatients.value.filter(patient => patient.returned).length);
const staleCount = computed(() => overviewPatients.value.filter(patient => patient.stale && patient.active).length);
const filteredPatients = computed(() => {
  const needle = keyword.value.trim().toLowerCase();
  return overviewPatients.value
    .filter(patient => (scope.value === "active" ? patient.active : true))
    .filter(patient => !needle || patient.searchText.includes(needle))
    .sort(
      (a, b) =>
        Number(b.returned) - Number(a.returned) || Number(b.stale) - Number(a.stale) || b.updatedAt.localeCompare(a.updatedAt)
    );
});

const loadPatients = async () => {
  loading.value = true;
  try {
    const { data } = await getPreAiPatientCasesApi();
    patientCases.value = data.list || [];
  } catch (error) {
    ElMessage.error((error as Error).message || "患者概览读取失败");
  } finally {
    loading.value = false;
  }
};
const openOverview = (patient: OverviewPatient) => {
  if (!patient.encounterId) {
    if (patient.sourcePatientId) openPatientDetail(patient.sourcePatientId);
    return;
  }
  selectedPatient.value = patient;
  drawerVisible.value = true;
};

onMounted(loadPatients);
</script>

<style scoped lang="scss">
.patient-overview-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px 22px;
}

.overview-header,
.summary-strip article,
.overview-card {
  background: var(--hos-panel, var(--el-bg-color));
  border: 1px solid var(--hos-border, var(--el-border-color-lighter));
  border-radius: 14px;
  box-shadow: var(--hos-shadow-soft, 0 10px 30px rgb(15 23 42 / 6%));
}

.overview-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 14px;
  padding: 18px;

  h2 {
    margin: 2px 0 4px;
    font-size: 24px;
    color: var(--el-text-color-primary);
  }

  p {
    margin: 0;
    color: var(--el-text-color-secondary);
  }
}

.header-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.overview-search {
  width: min(360px, 100%);
}

.summary-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;

  article {
    padding: 13px 15px;
  }

  span,
  small {
    display: block;
    color: var(--el-text-color-secondary);
  }

  strong {
    display: block;
    margin: 3px 0;
    font-size: 28px;
    color: var(--el-text-color-primary);
    font-variant-numeric: tabular-nums;
  }
}

.overview-body {
  min-height: 360px;
}

.patient-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 14px;
}

.overview-card {
  position: relative;
  display: grid;
  gap: 12px;
  padding: 16px 18px 14px;
  overflow: hidden;
  text-align: left;
  cursor: pointer;
  transition:
    transform 0.18s ease,
    box-shadow 0.18s ease,
    border-color 0.18s ease;

  &::before {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0;
    width: 5px;
    content: "";
    background: var(--el-color-primary);
  }

  &:hover {
    border-color: var(--el-color-primary-light-5);
    box-shadow: var(--el-box-shadow-light);
    transform: translateY(-1px);
  }

  &.risk-danger::before {
    background: var(--el-color-danger);
  }

  &.risk-warning::before {
    background: var(--el-color-warning);
  }

  &.risk-success::before {
    background: var(--el-color-success);
  }

  &.inactive {
    opacity: 0.86;
  }
}

.card-head,
.stage-line,
.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.patient-name {
  display: block;
  color: var(--el-color-primary-dark-2);
  font-size: 26px;
  font-weight: 800;
  letter-spacing: 2px;
}

.card-head small,
.card-foot,
.card-facts dt {
  color: var(--el-text-color-secondary);
}

.stage-line {
  justify-content: flex-start;
}

.card-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 16px;
  margin: 0;

  dt {
    margin-bottom: 3px;
    font-size: 12px;
  }

  dd {
    margin: 0;
    color: var(--el-text-color-primary);
    font-weight: 700;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.alert-text {
  font-size: 13px;

  &.danger {
    color: var(--el-color-danger);
  }

  &.warning {
    color: var(--el-color-warning);
  }

  &.normal {
    color: var(--el-color-primary);
  }

  &.muted {
    color: var(--el-text-color-placeholder);
  }
}

@media (max-width: 900px) {
  .summary-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .patient-grid {
    grid-template-columns: 1fr;
  }
}
</style>
