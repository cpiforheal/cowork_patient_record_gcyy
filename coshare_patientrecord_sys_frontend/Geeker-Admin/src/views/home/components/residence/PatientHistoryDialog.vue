<template>
  <el-dialog
    :model-value="modelValue"
    :title="`${person?.name || '患者'} · 病史概览`"
    width="min(980px, 94vw)"
    top="6vh"
    append-to-body
    destroy-on-close
    class="ph-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="person">
      <header class="ph-head">
        <div class="ph-id">
          <strong>{{ person.name || "未登记姓名" }}</strong>
          <span>{{ [person.gender, person.age].filter(Boolean).join(" · ") || "性别/年龄未登记" }}</span>
          <span class="ph-place">{{ place }}</span>
        </div>
        <div class="ph-actions">
          <el-button size="small" @click="infoVisible = true">个人信息</el-button>
          <el-button size="small" type="primary" plain :disabled="!currentEncounterId" @click="archiveVisible = true">
            健康档案预览
          </el-button>
        </div>
      </header>

      <el-alert
        v-if="!person.encounterId"
        type="info"
        :closable="false"
        show-icon
        title="该患者仅有收费登记，暂无前置病历，无法展示病史。"
      />

      <div v-else class="ph-body">
        <!-- 就诊时间线：点选切换下方概览 -->
        <aside class="ph-timeline" aria-label="就诊记录">
          <h4>就诊记录 · {{ encounters.length || person.visitCount || 1 }} 次</h4>
          <el-skeleton v-if="historyLoading" :rows="3" animated />
          <ol v-else>
            <li v-for="item in encounters" :key="item.id">
              <button type="button" :class="{ on: item.id === currentEncounterId }" @click="selectEncounter(item.id)">
                <b>{{ item.visitDate || item.createdAt?.slice(0, 10) || "—" }}</b>
                <span>第 {{ item.visitNo || 1 }} 次 · {{ item.visitType === "FOLLOW_UP" ? "复诊" : "初诊" }}</span>
                <small v-if="item.visitReason || item.description">{{ item.visitReason || item.description }}</small>
              </button>
            </li>
            <li v-if="!encounters.length" class="ph-muted">仅最近一次就诊</li>
          </ol>
        </aside>

        <section v-loading="overviewLoading" class="ph-main" element-loading-text="病史加载中…">
          <el-alert v-if="overviewError" type="warning" :closable="false" show-icon :title="overviewError" />
          <template v-else-if="overview">
            <div class="ph-block">
              <h4>既往病史</h4>
              <dl class="ph-grid">
                <template v-for="row in historyRows" :key="row.label">
                  <dt>{{ row.label }}</dt>
                  <dd :class="{ empty: !row.value, warn: row.warn && row.value }">{{ row.value || "未记录" }}</dd>
                </template>
              </dl>
            </div>

            <div class="ph-block">
              <h4>
                本次就诊 <small>{{ overview.visit.visitDate || "—" }} · 第 {{ overview.visit.visitNo || 1 }} 次</small>
              </h4>
              <dl class="ph-grid">
                <dt>主诉</dt>
                <dd class="strong">{{ overview.clinical.chiefComplaint || "—" }}</dd>
                <dt>现病史</dt>
                <dd>{{ overview.clinical.presentIllness || "—" }}</dd>
                <dt>西医诊断</dt>
                <dd class="strong">{{ westernDiagnosis || "—" }}</dd>
                <dt>中医诊断</dt>
                <dd>{{ overview.clinical.diagnosis.tcm || "—" }}</dd>
                <dt>治疗</dt>
                <dd>{{ treatmentText }}</dd>
                <template v-if="overview.clinical.nextReviewAt">
                  <dt>下次复查</dt>
                  <dd>{{ overview.clinical.nextReviewAt }} {{ overview.clinical.nextReviewNote }}</dd>
                </template>
              </dl>
            </div>

            <div v-if="overview.auxiliary?.labReportCount || abnormalMetrics.length" class="ph-block">
              <h4>检查提示</h4>
              <p class="ph-lab">
                化验 {{ overview.auxiliary.labReportCount || 0 }} 份 · 异常
                <b class="warn">{{ overview.auxiliary.labSummary?.abnormalCount || 0 }}</b> 项
                <template v-if="overview.auxiliary.labSummary?.criticalCount">
                  · 危急 <b class="crit">{{ overview.auxiliary.labSummary.criticalCount }}</b> 项
                </template>
              </p>
              <div v-if="abnormalMetrics.length" class="ph-tags">
                <el-tag
                  v-for="metric in abnormalMetrics"
                  :key="`${metric.reportName}-${metric.name}-${metric.value}`"
                  size="small"
                  effect="plain"
                  :type="metric.severity === 'CRITICAL' ? 'danger' : 'warning'"
                >
                  {{ metric.name || "未知指标" }} {{ metric.value || "—" }}{{ metric.unit || "" }}
                </el-tag>
              </div>
            </div>
          </template>
        </section>
      </div>
    </template>

    <!-- 二级弹窗：个人信息（电话默认脱敏） -->
    <el-dialog v-model="infoVisible" title="个人信息" width="420px" append-to-body class="ph-info">
      <dl v-if="person" class="ph-grid compact">
        <dt>姓名</dt>
        <dd>{{ person.name || "—" }}</dd>
        <dt>性别 / 年龄</dt>
        <dd>{{ [person.gender, person.age].filter(Boolean).join(" / ") || "—" }}</dd>
        <dt>联系电话</dt>
        <dd>
          {{ phoneVisible ? person.phone || "—" : maskPhone(person.phone) || "—" }}
          <el-button v-if="person.phone" link type="primary" size="small" @click="phoneVisible = !phoneVisible">
            {{ phoneVisible ? "隐藏" : "显示" }}
          </el-button>
        </dd>
        <dt>住址</dt>
        <dd>{{ person.address || "未登记" }}</dd>
        <dt>归属</dt>
        <dd>{{ place }}</dd>
        <dt>最近就诊</dt>
        <dd>{{ person.visitDate || "—" }}</dd>
        <dt>数据来源</dt>
        <dd>{{ person.source === "preai" ? "前置病历" : "收费登记" }}</dd>
      </dl>
    </el-dialog>

    <HealthArchiveDialog
      v-if="archiveVisible && currentEncounterId"
      v-model="archiveVisible"
      :encounter-id="currentEncounterId"
      :encounter-patient-name="person?.name"
      preview-only
    />
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import {
  getEncounterOverviewApi,
  getPreAiEncounterHistoryApi,
  type PreAiEncounterHistoryItem,
  type PreAiEncounterOverview
} from "@/api/modules/clinic/preAi";
import HealthArchiveDialog from "@/views/preAi/encounters/components/HealthArchiveDialog.vue";
import { maskPhone, type ResidencePerson } from "./model";

const props = defineProps<{ modelValue: boolean; person: ResidencePerson | null; place: string }>();
const emit = defineEmits<{ (e: "update:modelValue", value: boolean): void }>();

const encounters = ref<PreAiEncounterHistoryItem[]>([]);
const historyLoading = ref(false);
const overview = ref<PreAiEncounterOverview | null>(null);
const overviewLoading = ref(false);
const overviewError = ref("");
const currentEncounterId = ref("");
const infoVisible = ref(false);
const archiveVisible = ref(false);
const phoneVisible = ref(false);

let overviewSeq = 0;
const selectEncounter = async (encounterId: string) => {
  if (!encounterId) return;
  currentEncounterId.value = encounterId;
  const seq = ++overviewSeq;
  overviewLoading.value = true;
  overviewError.value = "";
  try {
    const { data } = await getEncounterOverviewApi(encounterId);
    if (seq === overviewSeq) overview.value = data;
  } catch (error) {
    if (seq === overviewSeq) overviewError.value = (error as Error).message || "病史加载失败";
  } finally {
    if (seq === overviewSeq) overviewLoading.value = false;
  }
};

const loadHistory = async (caseId: string) => {
  encounters.value = [];
  if (!caseId) return;
  historyLoading.value = true;
  try {
    const { data } = await getPreAiEncounterHistoryApi(caseId);
    encounters.value = [...(data.encounters || [])].sort((a, b) => (b.visitDate || "").localeCompare(a.visitDate || ""));
  } catch {
    encounters.value = [];
  } finally {
    historyLoading.value = false;
  }
};

watch(
  () => [props.modelValue, props.person?.key] as const,
  ([open]) => {
    if (!open || !props.person) return;
    overview.value = null;
    phoneVisible.value = false;
    void loadHistory(props.person.caseId);
    void selectEncounter(props.person.encounterId);
  },
  { immediate: true }
);

const historyRows = computed(() => {
  const h = overview.value?.history;
  if (!h) return [];
  return [
    { label: "既往史", value: h.pastHistory },
    { label: "慢性病", value: h.chronicDiseaseItems, warn: true },
    { label: "手术史", value: h.surgicalHistory },
    { label: "过敏史", value: h.allergyHistory || overview.value?.clinical.allergyHistory, warn: true },
    { label: "用药史", value: h.medicationHistory },
    { label: "家族史", value: h.familyHistory }
  ];
});

const westernDiagnosis = computed(() => {
  const d = overview.value?.clinical.diagnosis;
  return d ? [d.westernPrimary, ...(d.westernSecondary || [])].filter(Boolean).join("；") : "";
});
const treatmentText = computed(() => {
  const c = overview.value?.clinical;
  if (!c) return "—";
  const surgery = c.surgery.actualPrimaryOperation
    ? `${c.surgery.actualPrimaryOperation}${c.surgery.anesthesiaMethod ? `（${c.surgery.anesthesiaMethod}）` : ""}`
    : "";
  return [c.treatment.treatmentPath, surgery].filter(Boolean).join(" · ") || "—";
});
const abnormalMetrics = computed(() => overview.value?.auxiliary?.labSummary?.abnormalMetrics || []);
</script>

<style scoped lang="scss">
.ph-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 12px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.ph-id {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  align-items: baseline;
  strong {
    font-size: 20px;
    color: var(--el-text-color-primary);
  }
  span {
    color: var(--el-text-color-secondary);
  }
}
.ph-place {
  flex-basis: 100%;
  font-size: 13px;
  color: #0f766e !important;
}
.ph-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
}
.ph-body {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: 16px;
  min-height: 320px;
}
.ph-timeline {
  h4 {
    margin: 0 0 8px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
  ol {
    display: grid;
    gap: 6px;
    padding: 0;
    margin: 0;
    list-style: none;
  }
  button {
    display: grid;
    gap: 2px;
    width: 100%;
    padding: 8px 10px;
    font: inherit;
    color: var(--el-text-color-regular);
    text-align: left;
    cursor: pointer;
    background: var(--el-fill-color-lighter);
    border: 1px solid transparent;
    border-radius: 8px;
    transition:
      background 0.16s ease,
      border-color 0.16s ease,
      transform 0.16s ease;
    &:hover {
      transform: translateX(2px);
    }
    &.on {
      background: rgb(0 150 136 / 8%);
      border-color: #009688;
    }
    b {
      font-size: 14px;
      color: var(--el-text-color-primary);
    }
    span,
    small {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
    small {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }
}
.ph-muted {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.ph-main {
  display: grid;
  gap: 14px;
  align-content: start;
  min-height: 200px;
}
.ph-block h4 {
  margin: 0 0 8px;
  font-size: 14px;
  color: var(--el-text-color-primary);
  small {
    margin-left: 6px;
    font-weight: 400;
    color: var(--el-text-color-secondary);
  }
}
.ph-grid {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  gap: 6px 12px;
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  dt {
    color: var(--el-text-color-secondary);
  }
  dd {
    margin: 0;
    color: var(--el-text-color-regular);
    white-space: pre-wrap;
    &.empty {
      color: var(--el-text-color-placeholder);
    }
    &.strong {
      font-weight: 600;
      color: var(--el-text-color-primary);
    }
    &.warn {
      color: #b45309;
    }
  }
  &.compact {
    grid-template-columns: 90px minmax(0, 1fr);
  }
}
.ph-lab {
  margin: 0 0 6px;
  font-size: 13px;
  .warn {
    color: #b45309;
  }
  .crit {
    color: var(--el-color-danger);
  }
}
.ph-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

@media (width <= 720px) {
  .ph-body {
    grid-template-columns: 1fr;
  }
}
</style>
