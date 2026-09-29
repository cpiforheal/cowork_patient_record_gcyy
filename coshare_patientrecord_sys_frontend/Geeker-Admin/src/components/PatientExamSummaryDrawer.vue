<template>
  <el-drawer v-model="visible" append-to-body size="720px" class="overview-drawer" destroy-on-close>
    <template #header>
      <div class="drawer-title">
        <span class="eyebrow">患者基础检查摘要</span>
        <h3>{{ fallbackName || overview?.patient.name || "患者" }}</h3>
        <div class="drawer-tags">
          <el-tag>{{
            stageLabel((overview?.visit.effectiveCurrentStage as ProgressStage) || fallbackStage || "LEGACY")
          }}</el-tag>
          <el-tag effect="plain">{{
            routeLabel(overview?.visit.normalizedCareType || overview?.visit.inventoryCareType || overview?.visit.route)
          }}</el-tag>
          <el-tag v-if="overview?.visit.caseToken" type="info" effect="plain">{{ overview.visit.caseToken }}</el-tag>
        </div>
      </div>
    </template>

    <div v-loading="loading" class="drawer-content" element-loading-text="正在汇总检查信息…">
      <el-empty v-if="error" :description="error" />
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
        <el-button @click="visible = false">关闭</el-button>
        <el-button v-if="showWorkbench" type="primary" :disabled="!encounterId" @click="enterWorkbench">进入工作台</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts" name="patientExamSummaryDrawer">
// 患者基础检查摘要抽屉：患者概览与运营看板明细共用，数据源为 getEncounterOverviewApi。
import { computed, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { getEncounterOverviewApi, type PreAiEncounterOverview, type PreAiStageCode } from "@/api/modules/clinic/preAi";

type ProgressStage = PreAiStageCode | "LEGACY";
type StatusType = "success" | "info" | "warning" | "danger";

const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    encounterId?: string;
    fallbackName?: string;
    fallbackStage?: ProgressStage;
    showWorkbench?: boolean;
  }>(),
  { encounterId: "", fallbackName: "", fallbackStage: undefined, showWorkbench: false }
);
const emit = defineEmits<{ "update:modelValue": [value: boolean] }>();

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
const visible = computed({
  get: () => props.modelValue,
  set: value => emit("update:modelValue", value)
});
const loading = ref(false);
const illnessExpanded = ref(false);
const error = ref("");
const overview = ref<PreAiEncounterOverview>();

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
const treatmentPathLabel = (value?: string) =>
  ({ CONSERVATIVE: "保守治疗", SURGICAL: "手术治疗" })[String(value || "").toUpperCase()] || value || "待补充";
const taskStatusLabel = (status?: string) =>
  ({ DRAFT: "待完成", COMPLETED: "已完成", RETURNED: "已退回" })[status || ""] || status || "待处理";
const taskStatusType = (status?: string): StatusType =>
  status === "COMPLETED" ? "success" : status === "RETURNED" ? "danger" : "info";
const auxiliaryTaskLabel = (type?: string) =>
  ({ LAB: "化验", ECG: "心电", IMAGING: "影像", VITAL_SIGNS: "四测", COLONOSCOPY: "肠镜", SURGERY_CONSENT: "手术同意书" })[
    type || ""
  ] ||
  type ||
  "检查";
const formatTime = (value?: string) =>
  String(value || "")
    .replace("T", " ")
    .slice(0, 16) || "—";
const joinDisplay = (values?: Array<string | undefined> | string[], separator = "、") => {
  const list = (values || []).map(item => String(item || "").trim()).filter(Boolean);
  return list.length ? list.join(separator) : "待补充";
};

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

const load = async () => {
  if (!props.encounterId) return;
  overview.value = undefined;
  error.value = "";
  illnessExpanded.value = false;
  loading.value = true;
  try {
    const { data } = await getEncounterOverviewApi(props.encounterId);
    overview.value = data;
  } catch (caught) {
    error.value = (caught as Error).message || "检查摘要读取失败";
  } finally {
    loading.value = false;
  }
};
watch(
  [() => props.modelValue, () => props.encounterId] as const,
  ([open, id], [previousOpen, previousId]) => {
    if (open && id && (id !== previousId || !previousOpen)) void load();
  },
  { immediate: true }
);

const enterWorkbench = () => {
  if (!props.encounterId) return;
  router.push({ path: "/pre-ai/encounters", query: { encounterId: props.encounterId } });
};
</script>

<style scoped lang="scss">
.eyebrow {
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.12em;
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
  background: var(--hos-panel, var(--el-bg-color));
  border: 1px solid var(--hos-border, var(--el-border-color-lighter));
  border-radius: 14px;
  box-shadow: var(--hos-shadow-soft, 0 10px 30px rgb(15 23 42 / 6%));
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
  .metric-list,
  .compact-grid dl,
  .diagnosis-section dl {
    grid-template-columns: 1fr;
  }
}
</style>
