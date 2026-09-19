<template>
  <el-dialog
    :model-value="visible"
    title="随访话术 · 患者病史参考"
    width="1080px"
    top="4vh"
    append-to-body
    destroy-on-close
    class="fud-script-dialog"
    @update:model-value="$emit('update:visible', $event)"
  >
    <div class="fsd-layout">
      <!-- 左栏 · 话术 + 通话留痕 -->
      <div class="fsd-col">
        <div class="fsd-col-head">
          <strong>随访话术</strong>
          <small>按「{{ patientName || "该患者" }}」最新复诊记录（第 {{ visit?.seq ?? "—" }} 次）生成</small>
        </div>
        <div v-if="sections.length" class="fsd-progress">
          <div class="fsd-progress-head">
            <span>沟通进度</span>
            <strong>{{ completedSections.length }}/{{ sections.length }}</strong>
          </div>
          <el-progress :percentage="progressPercent" :show-text="false" :stroke-width="6" />
          <small>当前：{{ activeSection?.title || `第 ${activeIndex + 1} 个沟通节点` }}</small>
        </div>
        <div class="fsd-sections">
          <section
            v-for="(section, index) in sections"
            :key="index"
            class="fsd-section"
            :class="{ 'is-active': index === activeIndex, 'is-complete': completedSections.includes(index) }"
          >
            <button type="button" class="fsd-section-toggle" @click="activeIndex = index">
              <span class="fsd-section-index">{{ completedSections.includes(index) ? "✓" : index + 1 }}</span>
              <span class="fsd-section-title">{{ section.title || `沟通节点 ${index + 1}` }}</span>
              <span class="fsd-section-state">{{
                completedSections.includes(index) ? "已完成" : index === activeIndex ? "进行中" : "待进行"
              }}</span>
            </button>
            <div v-if="index === activeIndex" class="fsd-section-content">
              <p class="fsd-section-body">{{ section.body }}</p>
              <div class="fsd-section-actions">
                <el-button size="small" @click="copySection(section.body)">复制当前节点</el-button>
                <el-button size="small" type="primary" @click="completeSection(index)">
                  {{ index === sections.length - 1 ? "完成沟通节点" : "完成并进入下一项" }}
                </el-button>
              </div>
            </div>
          </section>
        </div>
        <div class="fsd-col-actions">
          <el-button type="primary" size="small" :disabled="!sections.length" @click="copyScript">复制话术</el-button>
          <el-button size="small" :disabled="!visit?.encounterId" @click="openCourseDialog">📋 完整病程弹窗</el-button>
        </div>

        <el-divider content-position="left">通话留痕</el-divider>

        <div class="fsd-log-list">
          <article v-for="log in logs" :key="log.id" class="fsd-log-item">
            <div class="fsd-log-top">
              <strong>{{ log.time }}</strong>
              <el-tag size="small" :type="log.connected.includes('未接') ? 'warning' : 'success'">{{ log.connected }}</el-tag>
            </div>
            <p v-if="log.feedback" class="fsd-log-line">患者反馈：{{ log.feedback }}</p>
            <p v-if="log.nextPlan" class="fsd-log-line">下次跟进：{{ log.nextPlan }}</p>
            <p class="fsd-log-line muted">记录人：{{ log.operator }}</p>
          </article>
          <div v-if="!logs.length" class="fsd-empty">本患者暂无通话留痕</div>
        </div>

        <el-form label-width="70px" class="fsd-log-form" :disabled="!visit">
          <el-form-item label="接通状态">
            <el-select v-model="callForm.connected" size="small">
              <el-option label="已接通" value="已接通" />
              <el-option label="未接通" value="未接通" />
              <el-option label="无人接听" value="无人接听" />
            </el-select>
          </el-form-item>
          <el-form-item label="患者反馈">
            <el-input v-model="callForm.feedback" type="textarea" :rows="2" size="small" placeholder="本次沟通反馈（可选）" />
          </el-form-item>
          <el-form-item label="下次跟进">
            <el-input v-model="callForm.nextPlan" type="textarea" :rows="2" size="small" placeholder="下次跟进计划（可选）" />
          </el-form-item>
          <el-button type="success" :disabled="!visit" @click="saveCallLog">保存留痕</el-button>
        </el-form>
      </div>

      <!-- 右栏 · 病史参考（完整粒度：病情 + 全景病史 + 诊断治疗 + 化验 + 照片） -->
      <div class="fsd-col is-side">
        <div class="fsd-col-head">
          <strong>病史参考</strong>
          <small>基础检查概要 · 全景病史 · 随访照片</small>
        </div>
        <div v-loading="courseLoading" class="fsd-side-scroll" element-loading-text="病史加载中…">
          <el-alert v-if="courseError" type="warning" :closable="false" show-icon :title="courseError" />

          <template v-if="overview">
            <!-- 病情事实 -->
            <div class="fsd-group-title">主要病情</div>
            <div class="fsd-doc-fields">
              <div class="fsd-doc-field wide chief">
                <strong>主诉</strong>
                <span>{{ overview.clinical?.chiefComplaint || "—" }}</span>
              </div>
              <div v-if="overview.clinical?.presentIllness" class="fsd-doc-field wide">
                <strong>现病史</strong>
                <span>{{ overview.clinical.presentIllness }}</span>
              </div>
              <div v-if="overview.history?.physicalExam" class="fsd-doc-field wide">
                <strong>体格检查</strong>
                <span>{{ overview.history.physicalExam }}</span>
              </div>
              <div v-if="overview.clinical?.specialistExam" class="fsd-doc-field wide">
                <strong>专科检查</strong>
                <span>{{ overview.clinical.specialistExam }}</span>
              </div>
            </div>

            <!-- 全景病史 -->
            <div class="fsd-group-title">全景病史</div>
            <div class="fsd-doc-fields">
              <div class="fsd-doc-field wide allergy">
                <strong>过敏史</strong>
                <span>{{ overview.history?.allergyHistory || "未记录" }}</span>
              </div>
              <div class="fsd-doc-field">
                <strong>既往史</strong>
                <span>{{ overview.history?.pastHistory || "未记录" }}</span>
              </div>
              <div class="fsd-doc-field">
                <strong>慢性病史</strong>
                <span>{{ overview.history?.chronicDiseaseItems || "未记录" }}</span>
              </div>
              <div class="fsd-doc-field">
                <strong>手术史</strong>
                <span>{{ overview.history?.surgicalHistory || "未记录" }}</span>
              </div>
              <div class="fsd-doc-field">
                <strong>个人史</strong>
                <span>{{ overview.history?.personalHistory || "未记录" }}</span>
              </div>
              <div class="fsd-doc-field">
                <strong>家族史</strong>
                <span>{{ overview.history?.familyHistory || "未记录" }}</span>
              </div>
              <div class="fsd-doc-field">
                <strong>外伤史</strong>
                <span>{{ overview.history?.traumaHistory || "未记录" }}</span>
              </div>
              <div class="fsd-doc-field">
                <strong>输血史</strong>
                <span>{{ overview.history?.transfusionHistory || "未记录" }}</span>
              </div>
              <div class="fsd-doc-field">
                <strong>接种史</strong>
                <span>{{ overview.history?.vaccinationHistory || "未记录" }}</span>
              </div>
              <div class="fsd-doc-field">
                <strong>用药史</strong>
                <span>{{ overview.history?.medicationHistory || "未记录" }}</span>
              </div>
            </div>

            <!-- 诊断与治疗 -->
            <div class="fsd-group-title">诊断与治疗</div>
            <div class="fsd-doc-fields">
              <div class="fsd-doc-field">
                <strong>西医诊断</strong>
                <span>{{ overview.clinical?.diagnosis?.westernPrimary || "—" }}</span>
              </div>
              <div class="fsd-doc-field">
                <strong>中医诊断</strong>
                <span>{{ overview.clinical?.diagnosis?.tcm || "—" }}</span>
              </div>
              <div class="fsd-doc-field">
                <strong>手术 · 麻醉</strong>
                <span>
                  {{ overview.clinical?.surgery?.actualPrimaryOperation || "未手术 / 未记录"
                  }}{{ overview.clinical?.surgery?.anesthesiaMethod ? `（${overview.clinical.surgery.anesthesiaMethod}）` : "" }}
                </span>
              </div>
              <div class="fsd-doc-field">
                <strong>手术日期</strong>
                <span>{{ overview.clinical?.surgery?.operationDate || "—" }}</span>
              </div>
            </div>

            <!-- 化验摘要 -->
            <template v-if="overview.auxiliary?.labSummary">
              <div class="fsd-group-title">辅助检查摘要</div>
              <div class="fsd-doc-fields">
                <div class="fsd-doc-field wide">
                  <strong>化验概览</strong>
                  <span>
                    <em class="fsd-abnormal" :class="{ none: !overview.auxiliary.labSummary.abnormalCount }">
                      异常 {{ overview.auxiliary.labSummary.abnormalCount || 0 }} 项</em
                    >
                    <template v-if="overview.auxiliary.labSummary.criticalCount">
                      · <em class="fsd-critical">危急 {{ overview.auxiliary.labSummary.criticalCount }} 项</em></template
                    >
                  </span>
                </div>
                <div v-if="abnormalMetricList.length" class="fsd-doc-field wide">
                  <strong>异常指标</strong>
                  <span class="fsd-metric-tags">
                    <el-tag
                      v-for="metric in abnormalMetricList"
                      :key="`${metric.reportName}-${metric.name}-${metric.value}`"
                      size="small"
                      :type="metric.severity === 'CRITICAL' ? 'danger' : 'warning'"
                      effect="plain"
                    >
                      {{ metric.name || "未知指标" }} {{ metric.value || "—" }}{{ metric.unit || "" }}
                    </el-tag>
                  </span>
                </div>
              </div>
            </template>

            <!-- 随访照片 -->
            <div class="fsd-group-title">
              随访照片
              <small v-if="visitImages.length">（{{ visitImages.length }} 张）</small>
            </div>
            <div v-if="visitImages.length" class="fsd-images">
              <el-image
                v-for="image in visitImages"
                :key="image.id"
                class="fsd-image"
                :src="image.url"
                :preview-src-list="visitImages.map(item => item.url)"
                :initial-index="visitImages.indexOf(image)"
                :preview-teleported="true"
                fit="cover"
                hide-on-click-modal
              />
            </div>
            <p v-else class="fsd-empty">本次复诊未采集照片</p>
          </template>
          <div v-else-if="!courseLoading" class="fsd-empty">暂无该患者病史数据（可能跨科室无授权）</div>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="fsd-footer">
        <span class="fsd-footer-note">话术由模板库自动渲染 · 病史数据来自接诊档案</span>
        <span class="fsd-footer-actions">
          <el-button @click="$emit('update:visible', false)">关闭</el-button>
          <el-button type="primary" :disabled="!visit?.encounterId" @click="$emit('open-archive', visit?.encounterId || '')">
            健康管理档案
          </el-button>
        </span>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { getEncounterOverviewApi, getPreAiWorkspaceApi, type PreAiEncounterOverview } from "@/api/modules/clinic";
import { fetchFollowUpImageApi, type FollowUpVisit } from "@/api/modules/clinic/followUp";
import { loadFollowUpScriptTemplates, renderFollowUpScript, type FollowUpScriptTemplate } from "@/utils/followUpScript";

defineEmits<{
  "update:visible": [visible: boolean];
  "open-archive": [encounterId: string];
}>();

interface FollowUpCallLog {
  id: string;

  time: string;

  connected: string;

  scriptSnapshot: string;

  feedback: string;

  nextPlan: string;

  operator: string;
}

const props = withDefaults(
  defineProps<{
    visible: boolean;

    visit: FollowUpVisit | null;

    patientCaseId?: string;

    patientName?: string;

    gender?: string;

    department?: string;

    userName?: string;

    templates?: FollowUpScriptTemplate;
  }>(),
  {
    patientCaseId: "",
    patientName: "",
    gender: "",
    department: "",
    userName: "",
    templates: undefined
  }
);

const STORAGE_PREFIX = "followup-call-logs:";

const callForm = reactive({ connected: "已接通", feedback: "", nextPlan: "" });

// ---------- 病史数据（overview 完整加载，含 history 全景段） ----------
const overview = ref<PreAiEncounterOverview | null>(null);
const courseLoading = ref(false);
const courseError = ref("");
const visitImages = ref<{ id: string; url: string }[]>([]);

const abnormalMetricList = computed(() => overview.value?.auxiliary?.labSummary?.abnormalMetrics || []);

const loadOverview = async () => {
  const encounterId = props.visit?.encounterId || "";
  if (!encounterId) return;
  courseLoading.value = true;
  courseError.value = "";
  try {
    const [overviewResult] = await Promise.allSettled([getEncounterOverviewApi(encounterId), getPreAiWorkspaceApi(encounterId)]);
    if (overviewResult.status === "fulfilled") {
      overview.value = overviewResult.value.data;
    } else {
      overview.value = null;
      courseError.value = "";
    }
  } catch (error: any) {
    overview.value = null;
    courseError.value = error?.message || "病史信息加载失败";
  } finally {
    courseLoading.value = false;
  }
  const images = props.visit?.images || [];
  visitImages.value = [];
  for (const image of images) {
    try {
      const url = await fetchFollowUpImageApi(image.id);
      visitImages.value.push({ id: image.id, url });
    } catch {
      // 单张失败跳过
    }
  }
};

/** 完整病程弹窗沿用：无独立弹窗页时直接在当前弹窗内滚动查看，保留按钮以备后用 */
const openCourseDialog = () => {
  ElMessage.info("当前弹窗已展示完整病史，可滚动查看全部分区");
};

// ---------- 话术 ----------
const sections = computed(() =>
  props.visit
    ? renderFollowUpScript(
        props.visit,
        {
          patientName: props.patientName || overview.value?.patient?.name,
          gender: props.gender || overview.value?.patient?.gender,
          department: props.department,
          userName: props.userName
        },
        props.templates
      )
    : []
);

const scriptPlainText = computed(() => sections.value.map(section => section.body).join("\n"));

const activeIndex = ref(0);
const completedSections = ref<number[]>([]);
const activeSection = computed(() => sections.value[activeIndex.value]);
const progressPercent = computed(() =>
  sections.value.length ? Math.round((completedSections.value.length / sections.value.length) * 100) : 0
);

const copySection = async (body: string) => {
  try {
    await navigator.clipboard.writeText(body);
    ElMessage.success("当前沟通节点已复制");
  } catch {
    ElMessage.warning("复制失败，请手动选择文本复制");
  }
};

const completeSection = (index: number) => {
  if (!completedSections.value.includes(index)) {
    completedSections.value = [...completedSections.value, index].sort((a, b) => a - b);
  }
  if (index < sections.value.length - 1) activeIndex.value = index + 1;
};

const copyScript = async () => {
  try {
    await navigator.clipboard.writeText(scriptPlainText.value);
    ElMessage.success("话术已复制，可直接粘贴到通话备忘");
  } catch {
    ElMessage.warning("复制失败，请手动选择文本复制");
  }
};

// ---------- 通话留痕（本地暂存） ----------
const loadLogs = (key: string): FollowUpCallLog[] => {
  try {
    const parsed = JSON.parse(String(localStorage.getItem(STORAGE_PREFIX + key) || "[]"));
    return Array.isArray(parsed) ? (parsed as FollowUpCallLog[]) : [];
  } catch {
    return [];
  }
};

const logKey = computed(() => props.patientCaseId || props.visit?.encounterId || props.visit?.id || "");

const logs = computed<FollowUpCallLog[]>(() => (logKey.value ? loadLogs(logKey.value) : []));

const saveCallLog = () => {
  if (!props.visit || !logKey.value) return;

  const now = new Date();
  const pad = (value: number) => String(value).padStart(2, "0");
  const log: FollowUpCallLog = {
    id: `call-${now.getTime()}`,
    time: `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`,
    connected: callForm.connected,
    scriptSnapshot: scriptPlainText.value,
    feedback: callForm.feedback.trim(),
    nextPlan: callForm.nextPlan.trim(),
    operator: props.userName || ""
  };

  const next = [...loadLogs(logKey.value), log];
  localStorage.setItem(STORAGE_PREFIX + logKey.value, JSON.stringify(next));

  callForm.feedback = "";
  callForm.nextPlan = "";
  callForm.connected = "已接通";
  ElMessage.success("通话留痕已登记");
};

watch(
  () => props.visible,
  visible => {
    if (visible) {
      overview.value = null;
      visitImages.value = [];
      courseError.value = "";
      callForm.feedback = "";
      callForm.nextPlan = "";
      callForm.connected = "已接通";
      activeIndex.value = 0;
      completedSections.value = [];
      void loadOverview();
      void loadFollowUpScriptTemplates();
    }
  }
);

onMounted(() => {
  void loadFollowUpScriptTemplates();
});
</script>

<style scoped lang="scss">
.fsd-layout {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
  gap: 16px;
  // 固定视口高度，双栏各自内部滚动，杜绝弹窗被内容撑高溢出屏幕
  height: calc(100vh - 220px);
  min-height: 420px;
  overflow: hidden;
}

.fsd-col {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  min-width: 0;
  overflow: hidden;

  &.is-side {
    padding-left: 14px;
    border-left: 1px solid var(--el-border-color-lighter);
  }
}

.fsd-col-head {
  display: flex;
  align-items: baseline;
  gap: 8px;

  strong {
    font-size: 14px;
    color: var(--el-text-color-primary);
  }

  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.fsd-sections {
  flex: 1;
  display: grid;
  gap: 10px;
  padding: 12px;
  overflow-y: auto;
  align-content: start;
  min-height: 140px;
  background: linear-gradient(180deg, #f8fafc 0%, #f1f5f9 100%);
  border: 1px solid #e2e8f0;
  border-radius: 10px;
}

.fsd-progress {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  background: color-mix(in srgb, var(--el-color-primary) 6%, var(--el-bg-color));
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 18%, var(--el-border-color-lighter));
  border-radius: 10px;

  .fsd-progress-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    color: var(--el-text-color-regular);
    font-size: 12px;

    strong {
      color: var(--el-color-primary);
      font-variant-numeric: tabular-nums;
    }
  }

  small {
    color: var(--el-text-color-secondary);
    font-size: 11px;
  }
}

.fsd-section-title {
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 700;
}

.fsd-section {
  padding: 8px 10px;
  background: var(--el-bg-color);
  border: 1px solid transparent;
  border-radius: 8px;
  transition:
    border-color 0.2s ease,
    background-color 0.2s ease;

  &.is-active {
    border-color: var(--el-color-primary-light-5);
    box-shadow: 0 4px 12px color-mix(in srgb, var(--el-color-primary) 10%, transparent);
  }

  &.is-complete {
    .fsd-section-title,
    .fsd-section-state {
      color: var(--el-color-success);
    }
  }
}

.fsd-section-toggle {
  display: flex;
  align-items: center;
  width: 100%;
  gap: 8px;
  padding: 0;
  color: inherit;
  text-align: left;
  background: transparent;
  border: 0;
  cursor: pointer;

  .fsd-section-index {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex: 0 0 22px;
    width: 22px;
    height: 22px;
    color: var(--el-color-primary);
    font-size: 11px;
    font-weight: 700;
    background: var(--el-color-primary-light-9);
    border-radius: 50%;
  }

  .fsd-section-state {
    margin-left: auto;
    color: var(--el-text-color-secondary);
    font-size: 11px;
  }
}

.fsd-section-content {
  padding: 10px 0 2px 30px;
}

.fsd-section-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

.fsd-section-body {
  margin: 0;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.fsd-col-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

// ---------- 右栏 · 病史参考 ----------
.fsd-side-scroll {
  flex: 1;
  min-height: 0;
  display: grid;
  gap: 10px;
  align-content: start;
  overflow-y: auto;
  padding-right: 4px;
}

.fsd-group-title {
  margin-top: 2px;
  padding-left: 8px;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 700;
  border-left: 3px solid var(--el-color-primary);

  small {
    color: var(--el-text-color-secondary);
    font-weight: 400;
    font-size: 11px;
  }
}

.fsd-doc-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.fsd-doc-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 8px 10px;
  background: var(--el-fill-color-lighter);
  border-radius: 8px;

  &.wide {
    grid-column: 1 / -1;
  }

  &.chief {
    background: color-mix(in srgb, var(--el-color-primary) 7%, var(--el-bg-color));
    border-left: 3px solid var(--el-color-primary);

    strong {
      color: var(--el-color-primary);
    }
  }

  &.allergy {
    background: color-mix(in srgb, var(--el-color-danger) 5%, var(--el-bg-color));
    border-left: 3px solid var(--el-color-danger);

    strong {
      color: var(--el-color-danger);
    }
  }

  strong {
    color: var(--el-text-color-secondary);
    font-size: 11px;
    font-weight: 700;
  }

  span {
    color: var(--el-text-color-regular);
    font-size: 12px;
    line-height: 1.6;
    white-space: pre-wrap;
  }
}

.fsd-abnormal {
  color: var(--el-color-warning);
  font-style: normal;
  font-weight: 700;

  &.none {
    color: var(--el-color-success);
  }
}

.fsd-critical {
  color: var(--el-color-danger);
  font-style: normal;
  font-weight: 700;
}

.fsd-metric-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.fsd-images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;

  .fsd-image {
    width: 72px;
    height: 72px;
    border-radius: 8px;
    cursor: pointer;
    transition: transform 0.2s ease;

    &:hover {
      transform: scale(1.05);
    }
  }
}

.fsd-empty {
  padding: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-align: center;
  background: var(--el-fill-color-lighter);
  border: 1px dashed var(--el-border-color-lighter);
  border-radius: 8px;
}

// ---------- 通话留痕 ----------
.fsd-log-list {
  flex-shrink: 0;
  display: grid;
  gap: 6px;
  max-height: 110px;
  overflow-y: auto;
}

.fsd-log-item {
  padding: 8px 10px;
  background: var(--el-fill-color-lighter);
  border-radius: 8px;
}

.fsd-log-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;

  strong {
    font-size: 12px;
  }
}

.fsd-log-line {
  margin: 2px 0;
  color: var(--el-text-color-regular);
  font-size: 12px;

  &.muted {
    color: var(--el-text-color-secondary);
  }
}

.fsd-log-form {
  flex-shrink: 0;
  margin-top: 2px;

  .el-button {
    width: 100%;
  }
}

.fsd-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.fsd-footer-note {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.fsd-footer-actions {
  display: flex;
  gap: 8px;
}
</style>
