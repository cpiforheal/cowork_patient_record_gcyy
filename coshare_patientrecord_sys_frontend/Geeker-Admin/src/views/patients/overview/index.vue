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

    <el-drawer v-model="drawerVisible" append-to-body size="720px" class="overview-drawer" destroy-on-close>
      <template #header>
        <div class="drawer-title">
          <span class="eyebrow">患者基础检查摘要</span>
          <h3>{{ selectedPatient?.name || overview?.patient.name || "患者" }}</h3>
          <div class="drawer-tags">
            <el-tag>{{
              stageLabel((overview?.visit.effectiveCurrentStage as ProgressStage) || selectedPatient?.currentStage || "LEGACY")
            }}</el-tag>
            <el-tag effect="plain">{{
              routeLabel(overview?.visit.normalizedCareType || overview?.visit.inventoryCareType || overview?.visit.route)
            }}</el-tag>
            <el-tag v-if="overview?.visit.caseToken" type="info" effect="plain">{{ overview.visit.caseToken }}</el-tag>
          </div>
        </div>
      </template>

      <div v-loading="overviewLoading" class="drawer-content" element-loading-text="正在汇总检查信息…">
        <el-empty v-if="overviewError" :description="overviewError" />
        <template v-else-if="overview">
          <section class="info-section compact-grid">
            <div class="section-head">
              <strong>基础信息</strong>
              <small>{{ formatTime(overview.visit.updatedAt) }} 更新</small>
            </div>
            <dl>
              <div>
                <dt>姓名</dt>
                <dd>{{ overview.patient.name || "待补充" }}</dd>
              </div>
              <div>
                <dt>性别/年龄</dt>
                <dd>{{ joinDisplay([overview.patient.gender, overview.patient.age], " · ") }}</dd>
              </div>
              <div>
                <dt>电话</dt>
                <dd>{{ overview.patient.phone || "待补充" }}</dd>
              </div>
              <div>
                <dt>来院日期</dt>
                <dd>{{ overview.visit.visitDate || "待补充" }}</dd>
              </div>
              <div>
                <dt>就诊号</dt>
                <dd>{{ overview.visit.visitNo || "待生成" }}</dd>
              </div>
              <div>
                <dt>当前状态</dt>
                <dd>{{ encounterStatusLabel(overview.visit.status) }}</dd>
              </div>
            </dl>
          </section>

          <section class="info-section">
            <div class="section-head"><strong>主要病情</strong><small>主诉 / 现病史 / 过敏史</small></div>
            <div class="narrative-list">
              <article>
                <span>主诉</span>
                <p>{{ chiefComplaintText }}</p>
              </article>
              <article>
                <span>现病史</span>
                <p :class="{ clamp: !illnessExpanded }">{{ overview.clinical.presentIllness || "待补充" }}</p>
                <el-button v-if="canExpandIllness" link type="primary" @click="illnessExpanded = !illnessExpanded">
                  {{ illnessExpanded ? "收起" : "展开" }}
                </el-button>
              </article>
              <article>
                <span>过敏史</span>
                <p>{{ overview.clinical.allergyHistory || "待补充" }}</p>
              </article>
              <article>
                <span>专科检查结论</span>
                <p>{{ overview.clinical.specialistExam || "待补充" }}</p>
              </article>
            </div>
          </section>

          <section class="info-section diagnosis-section">
            <div class="section-head"><strong>诊断与治疗</strong><small>中西医诊断、治疗路径、手术安排</small></div>
            <dl>
              <div>
                <dt>西医主诊断</dt>
                <dd>{{ overview.clinical.diagnosis.westernPrimary || "待补充" }}</dd>
              </div>
              <div>
                <dt>西医次诊断</dt>
                <dd>{{ joinDisplay(overview.clinical.diagnosis.westernSecondary) }}</dd>
              </div>
              <div>
                <dt>中医诊断</dt>
                <dd>{{ overview.clinical.diagnosis.tcm || "待补充" }}</dd>
              </div>
              <div>
                <dt>病名/主证</dt>
                <dd>
                  {{ joinDisplay([overview.clinical.tcmDetail.disease, overview.clinical.tcmDetail.primarySyndrome], " · ") }}
                </dd>
              </div>
              <div>
                <dt>治法治则</dt>
                <dd>{{ overview.clinical.tcmDetail.treatmentPrinciple || "待补充" }}</dd>
              </div>
              <div>
                <dt>治疗路径</dt>
                <dd>{{ treatmentPathLabel(overview.clinical.treatment.treatmentPath || overview.visit.treatmentPath) }}</dd>
              </div>
              <div v-if="operationText">
                <dt>拟/实际术式</dt>
                <dd>{{ operationText }}</dd>
              </div>
              <div v-if="overview.clinical.surgery.anesthesiaMethod">
                <dt>麻醉方式</dt>
                <dd>{{ overview.clinical.surgery.anesthesiaMethod }}</dd>
              </div>
              <div v-if="overview.clinical.surgery.operationDate">
                <dt>手术日期</dt>
                <dd>{{ overview.clinical.surgery.operationDate }}</dd>
              </div>
            </dl>
          </section>

          <section class="info-section aux-section">
            <div class="section-head"><strong>辅助检查</strong><small>化验异常 / 心电结论 / 检查任务</small></div>
            <div class="aux-summary">
              <el-tag :type="overview.auxiliary.labSummary.criticalCount ? 'danger' : 'success'" effect="light">
                危急值 {{ overview.auxiliary.labSummary.criticalCount }} 项
              </el-tag>
              <el-tag :type="overview.auxiliary.labSummary.abnormalCount ? 'warning' : 'success'" effect="light">
                异常指标 {{ overview.auxiliary.labSummary.abnormalCount }} 项
              </el-tag>
              <el-tag effect="plain">化验单 {{ overview.auxiliary.labReportCount }} 份</el-tag>
            </div>
            <div v-if="overview.auxiliary.tasks.length" class="task-chip-row">
              <el-tag
                v-for="task in overview.auxiliary.tasks"
                :key="task.taskType + task.title"
                :type="taskStatusType(task.status)"
                effect="plain"
              >
                {{ task.title || auxiliaryTaskLabel(task.taskType) }} · {{ taskStatusLabel(task.status) }}
              </el-tag>
            </div>
            <div v-if="ecgConclusion" class="ecg-note">
              <span>心电结论</span>
              <p>{{ ecgConclusion }}</p>
            </div>
            <div v-if="visibleAbnormalMetrics.length" class="metric-list">
              <article
                v-for="metric in visibleAbnormalMetrics"
                :key="`${metric.reportName}-${metric.name}-${metric.value}`"
                :class="metric.severity === 'CRITICAL' ? 'critical' : 'abnormal'"
              >
                <strong>{{ metric.name || "异常指标" }}</strong>
                <span>{{ metric.value || "—" }}{{ metric.unit || "" }}</span>
                <small>{{ metric.reference ? `参考：${metric.reference}` : metric.reportName || "化验报告" }}</small>
              </article>
            </div>
            <el-empty
              v-else-if="!overview.auxiliary.tasks.length && !ecgConclusion"
              :image-size="64"
              description="暂无辅助检查摘要"
            />
          </section>

          <section class="info-section review-section">
            <div class="section-head"><strong>复查安排</strong><small>来自检查室复查建议</small></div>
            <p>{{ nextReviewText }}</p>
          </section>
        </template>
      </div>

      <template #footer>
        <div class="drawer-footer">
          <el-button @click="drawerVisible = false">关闭</el-button>
          <el-button type="primary" :disabled="!selectedPatient?.encounterId" @click="enterWorkbench">进入工作台</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts" name="patientsOverview">
import { computed, onMounted, ref } from "vue";
import { ArrowRight, Refresh, Search } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { useRouter } from "vue-router";
import {
  getEncounterOverviewApi,
  getPreAiPatientCasesApi,
  type PreAiEncounterOverview,
  type PreAiPatientCase,
  type PreAiStageCode
} from "@/api/modules/clinic/preAi";
import { usePatientNavigation } from "@/hooks/usePatientNavigation";

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

const router = useRouter();
const { openPatientDetail } = usePatientNavigation();
const scope = ref<"active" | "all">("active");
const keyword = ref("");
const loading = ref(false);
const overviewLoading = ref(false);
const drawerVisible = ref(false);
const illnessExpanded = ref(false);
const overviewError = ref("");
const patientCases = ref<PreAiPatientCase[]>([]);
const selectedPatient = ref<OverviewPatient>();
const overview = ref<PreAiEncounterOverview>();

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
    CANCELLED: "已终止",
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
const treatmentPathLabel = (value?: string) =>
  ({ CONSERVATIVE: "保守治疗", SURGICAL: "手术治疗" })[String(value || "").toUpperCase()] || value || "待补充";
const taskStatusLabel = (status?: string) =>
  ({ DRAFT: "待完成", COMPLETED: "已完成", RETURNED: "已退回" })[status || ""] || status || "待处理";
const taskStatusType = (status?: string): StatusType =>
  status === "COMPLETED" ? "success" : status === "RETURNED" ? "danger" : "info";
const auxiliaryTaskLabel = (type?: string) =>
  ({ LAB: "化验", ECG: "心电", IMAGING: "影像", VITAL_SIGNS: "四测", COLONOSCOPY: "肠镜" })[type || ""] || type || "检查";
const formatTime = (value?: string) =>
  String(value || "")
    .replace("T", " ")
    .slice(0, 16) || "—";
const isStaleTime = (value?: string) => {
  const timestamp = new Date(String(value || "").replace(/-/g, "/")).getTime();
  return Number.isFinite(timestamp) && Date.now() - timestamp > 24 * 36e5;
};
const joinDisplay = (values?: Array<string | undefined> | string[], separator = "、") => {
  const list = (values || []).map(item => String(item || "").trim()).filter(Boolean);
  return list.length ? list.join(separator) : "待补充";
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

const chiefComplaintText = computed(() => {
  if (!overview.value) return "待补充";
  return joinDisplay([overview.value.clinical.chiefComplaint, overview.value.clinical.chiefComplaintSupplement], "；");
});
const canExpandIllness = computed(() => (overview.value?.clinical.presentIllness || "").length > 110);
const visibleAbnormalMetrics = computed(() => (overview.value?.auxiliary.labSummary.abnormalMetrics || []).slice(0, 8));
const ecgConclusion = computed(
  () => overview.value?.auxiliary.tasks.find(task => task.taskType === "ECG" && task.conclusion)?.conclusion || ""
);
const operationText = computed(() => {
  if (!overview.value) return "";
  return joinDisplay([
    overview.value.clinical.treatment.plannedPrimaryOperation,
    overview.value.clinical.surgery.actualPrimaryOperation
  ]).replace("待补充", "");
});
const nextReviewText = computed(() => {
  if (!overview.value) return "待补充";
  return joinDisplay([overview.value.clinical.nextReviewAt, overview.value.clinical.nextReviewNote], "；");
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
const openOverview = async (patient: OverviewPatient) => {
  if (!patient.encounterId) {
    if (patient.sourcePatientId) openPatientDetail(patient.sourcePatientId);
    return;
  }
  selectedPatient.value = patient;
  drawerVisible.value = true;
  overview.value = undefined;
  overviewError.value = "";
  illnessExpanded.value = false;
  overviewLoading.value = true;
  try {
    const { data } = await getEncounterOverviewApi(patient.encounterId);
    overview.value = data;
  } catch (error) {
    overviewError.value = (error as Error).message || "检查摘要读取失败";
  } finally {
    overviewLoading.value = false;
  }
};
const enterWorkbench = () => {
  if (!selectedPatient.value?.encounterId) return;
  router.push({ path: "/pre-ai/encounters", query: { encounterId: selectedPatient.value.encounterId } });
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
.overview-card,
.info-section {
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

.eyebrow {
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.12em;
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

.drawer-title {
  h3 {
    margin: 2px 0 8px;
    font-size: 25px;
  }
}

.drawer-tags,
.aux-summary,
.task-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.drawer-content {
  display: grid;
  gap: 12px;
  min-height: 320px;
}

.info-section {
  padding: 14px;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--el-border-color-lighter);

  strong {
    font-size: 16px;
  }

  small {
    color: var(--el-text-color-secondary);
  }
}

.compact-grid dl,
.diagnosis-section dl {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px 16px;
  margin: 0;

  dt {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  dd {
    margin: 2px 0 0;
    color: var(--el-text-color-primary);
    font-weight: 700;
    line-height: 1.45;
  }
}

.narrative-list {
  display: grid;
  gap: 10px;

  article {
    display: grid;
    grid-template-columns: 88px minmax(0, 1fr) auto;
    gap: 10px;
    align-items: start;
    padding: 9px 10px;
    background: var(--el-fill-color-lighter);
    border-radius: 10px;
  }

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
    font-weight: 700;
  }

  p {
    margin: 0;
    line-height: 1.6;
  }

  .clamp {
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }
}

.aux-section {
  .aux-summary {
    margin-bottom: 10px;
  }
}

.ecg-note {
  margin: 10px 0;
  padding: 10px;
  background: var(--el-color-primary-light-9);
  border-radius: 10px;

  span {
    color: var(--el-color-primary);
    font-size: 12px;
    font-weight: 700;
  }

  p {
    margin: 4px 0 0;
  }
}

.metric-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 10px;

  article {
    display: grid;
    gap: 2px;
    padding: 10px;
    background: var(--el-color-warning-light-9);
    border: 1px solid var(--el-color-warning-light-7);
    border-radius: 10px;

    &.critical {
      background: var(--el-color-danger-light-9);
      border-color: var(--el-color-danger-light-7);
    }
  }

  strong {
    color: var(--el-text-color-primary);
  }

  span {
    color: var(--el-color-danger);
    font-weight: 800;
    font-variant-numeric: tabular-nums;
  }

  small {
    color: var(--el-text-color-secondary);
  }
}

.review-section p {
  margin: 0;
  line-height: 1.65;
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 900px) {
  .summary-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .patient-grid,
  .metric-list,
  .compact-grid dl,
  .diagnosis-section dl {
    grid-template-columns: 1fr;
  }
}
</style>
