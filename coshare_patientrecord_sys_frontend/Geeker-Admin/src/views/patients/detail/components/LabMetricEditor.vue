<template>
  <div class="lab-metric-editor">
    <div class="lab-editor-head">
      <div>
        <strong>{{ panel.title }}</strong>
        <span>{{ panel.description }}</span>
      </div>
      <div class="lab-editor-actions">
        <el-button size="small" plain :disabled="disabled" @click="applyNormal">一键正常</el-button>
        <el-button size="small" plain :disabled="disabled" @click="applyPending">待回报</el-button>
      </div>
    </div>

    <div class="lab-metric-grid">
      <div v-for="metric in panel.metrics" :key="metric.key" class="lab-metric-card">
        <div class="metric-label">
          <strong>{{ metric.label }}</strong>
          <span>{{ metric.reference }}</span>
        </div>
        <div class="metric-control">
          <el-input
            v-if="metric.mode !== 'qualitative'"
            v-model="draftValues[metric.key]"
            :disabled="disabled"
            :placeholder="metric.placeholder || '数值'"
            type="number"
            step="0.01"
            @input="syncText"
          >
            <template v-if="metric.unit" #suffix>{{ metric.unit }}</template>
          </el-input>
          <el-select v-model="draftStatuses[metric.key]" :disabled="disabled" @change="syncText">
            <el-option v-for="option in statusOptions(metric)" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </div>
      </div>
    </div>

    <el-input
      v-model="textValue"
      :disabled="disabled"
      type="textarea"
      :rows="2"
      resize="none"
      placeholder="指标面板会自动生成病历文本，也可以在这里少量补充"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from "vue";
import type { LabPanelKey, RecordField } from "@/config/fieldPermissions";

type MetricStatus = "normal" | "high" | "low" | "positive" | "negative" | "pending";

type MetricOption = {
  key: string;
  label: string;
  unit?: string;
  reference: string;
  placeholder?: string;
  mode?: "number" | "qualitative";
  defaultStatus?: MetricStatus;
};

type LabPanel = {
  title: string;
  description: string;
  normalText: string;
  pendingText: string;
  metrics: MetricOption[];
};

const props = defineProps<{
  field: RecordField;
  modelValue: string;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: string];
}>();

const statusLabel: Record<MetricStatus, string> = {
  normal: "正常",
  high: "偏高",
  low: "偏低",
  positive: "阳性",
  negative: "阴性",
  pending: "待回报"
};

const statusOptions = (metric: MetricOption) =>
  metric.mode === "qualitative"
    ? [
        { label: "阴性", value: "negative" },
        { label: "阳性", value: "positive" },
        { label: "待回报", value: "pending" }
      ]
    : [
        { label: "正常", value: "normal" },
        { label: "偏高", value: "high" },
        { label: "偏低", value: "low" },
        { label: "待回报", value: "pending" }
      ];

const labPanels: Record<LabPanelKey, LabPanel> = {
  bloodRoutine: {
    title: "血常规指标",
    description: "白细胞、血红蛋白、血小板等可点选状态，少量数值补充后自动生成文本。",
    normalText: "血常规：WBC、NEUT%、LYM%、HGB、PLT 余无异常。",
    pendingText: "血常规待回报。",
    metrics: [
      { key: "WBC", label: "白细胞 WBC", unit: "x10^9/L", reference: "3.5-9.5", placeholder: "6.20" },
      { key: "NEUT", label: "中性粒 NEUT%", unit: "%", reference: "40-75", placeholder: "62.0" },
      { key: "LYM", label: "淋巴 LYM%", unit: "%", reference: "20-50", placeholder: "28.0" },
      { key: "HGB", label: "血红蛋白 HGB", unit: "g/L", reference: "115-150", placeholder: "130" },
      { key: "PLT", label: "血小板 PLT", unit: "x10^9/L", reference: "125-350", placeholder: "220" }
    ]
  },
  biochemistry: {
    title: "生化/糖化指标",
    description: "优先录入门诊常用关键值，完整报告可作为附件留存。",
    normalText: "生化/糖化：ALT、AST、GLU、HbA1c、CREA、UA、UREA 余无异常。",
    pendingText: "生化/糖化检查待回报。",
    metrics: [
      { key: "ALT", label: "ALT", unit: "U/L", reference: "7-40", placeholder: "22" },
      { key: "AST", label: "AST", unit: "U/L", reference: "13-35", placeholder: "20" },
      { key: "GLU", label: "血糖 GLU", unit: "mmol/L", reference: "3.9-6.1", placeholder: "5.6" },
      { key: "HbA1c", label: "糖化 HbA1c", unit: "%", reference: "4.0-6.0", placeholder: "5.8" },
      { key: "CREA", label: "肌酐 CREA", unit: "umol/L", reference: "41-81", placeholder: "65" },
      { key: "UA", label: "尿酸 UA", unit: "umol/L", reference: "155-357", placeholder: "300" },
      { key: "UREA", label: "尿素 UREA", unit: "mmol/L", reference: "2.6-7.5", placeholder: "5.0" }
    ]
  },
  coagulation: {
    title: "凝血功能",
    description: "PT、APTT、FIB、INR 结构化填写，减少整段手输。",
    normalText: "凝血功能：PT、APTT、TT、FIB、INR 余无异常。",
    pendingText: "凝血功能待回报。",
    metrics: [
      { key: "PT", label: "PT", unit: "s", reference: "9.8-12.1", placeholder: "11.0" },
      { key: "APTT", label: "APTT", unit: "s", reference: "23-35", placeholder: "29.0" },
      { key: "TT", label: "TT", unit: "s", reference: "14-21", placeholder: "17.0" },
      { key: "FIB", label: "FIB", unit: "g/L", reference: "2.0-4.0", placeholder: "2.8" },
      { key: "INR", label: "INR", reference: "0.8-1.2", placeholder: "1.00" }
    ]
  },
  urineRoutine: {
    title: "尿常规",
    description: "定性项目用阴性/阳性/待回报选择，避免重复输入。",
    normalText: "尿常规：尿蛋白、尿糖、隐血、白细胞、红细胞、酮体、亚硝酸盐均阴性，余无异常。",
    pendingText: "尿常规待回报。",
    metrics: [
      { key: "PRO", label: "尿蛋白", reference: "阴性", mode: "qualitative", defaultStatus: "negative" },
      { key: "GLU", label: "尿糖", reference: "阴性", mode: "qualitative", defaultStatus: "negative" },
      { key: "BLD", label: "隐血", reference: "阴性", mode: "qualitative", defaultStatus: "negative" },
      { key: "WBC", label: "白细胞", reference: "阴性", mode: "qualitative", defaultStatus: "negative" },
      { key: "RBC", label: "红细胞", reference: "阴性", mode: "qualitative", defaultStatus: "negative" },
      { key: "KET", label: "酮体", reference: "阴性", mode: "qualitative", defaultStatus: "negative" },
      { key: "NIT", label: "亚硝酸盐", reference: "阴性", mode: "qualitative", defaultStatus: "negative" }
    ]
  },
  preOpEight: {
    title: "术前八项",
    description: "感染筛查类项目以阴性/阳性为主，阳性项会自动突出在文本中。",
    normalText: "术前八项：乙肝五项、丙肝抗体、梅毒抗体、艾滋病抗体未见明显异常。",
    pendingText: "术前八项待回报。",
    metrics: [
      { key: "HBsAg", label: "乙肝表面抗原", reference: "阴性", mode: "qualitative", defaultStatus: "negative" },
      { key: "HCV", label: "丙肝抗体", reference: "阴性", mode: "qualitative", defaultStatus: "negative" },
      { key: "TP", label: "梅毒抗体", reference: "阴性", mode: "qualitative", defaultStatus: "negative" },
      { key: "HIV", label: "艾滋病抗体", reference: "阴性", mode: "qualitative", defaultStatus: "negative" }
    ]
  }
};

const fallbackPanel = labPanels.bloodRoutine;
const panel = computed(() => labPanels[props.field.labPanel || (props.field.key as LabPanelKey)] || fallbackPanel);
const draftValues = reactive<Record<string, string>>({});
const draftStatuses = reactive<Record<string, MetricStatus>>({});

const resetDraft = (status?: MetricStatus) => {
  panel.value.metrics.forEach(metric => {
    draftValues[metric.key] = "";
    draftStatuses[metric.key] = status || metric.defaultStatus || (metric.mode === "qualitative" ? "negative" : "normal");
  });
};

watch(
  panel,
  () => {
    resetDraft();
  },
  { immediate: true }
);

const textValue = computed({
  get: () => props.modelValue,
  set: value => emit("update:modelValue", value)
});

const buildMetricText = () => {
  const parts = panel.value.metrics
    .map(metric => {
      const status = draftStatuses[metric.key] || metric.defaultStatus || "normal";
      if (status === "pending") return `${metric.label}待回报`;
      if (metric.mode === "qualitative") return `${metric.label}${statusLabel[status]}`;
      const value = String(draftValues[metric.key] || "").trim();
      if (!value && status === "normal") return "";
      const valueText = value ? `${value}${metric.unit || ""}` : "";
      return `${metric.label}${valueText ? ` ${valueText}` : ""}（${statusLabel[status]}）`;
    })
    .filter(Boolean);

  if (!parts.length) return panel.value.normalText;
  return `${props.field.label}：${parts.join("；")}，余按报告单。`;
};

const syncText = () => {
  emit("update:modelValue", buildMetricText());
};

const applyNormal = () => {
  resetDraft();
  emit("update:modelValue", panel.value.normalText);
};

const applyPending = () => {
  resetDraft("pending");
  emit("update:modelValue", panel.value.pendingText);
};
</script>

<style scoped lang="scss">
.lab-metric-editor {
  display: grid;
  gap: 10px;
  padding: 12px;
  background: #f8fbff;
  border: 1px solid #dce8f5;
  border-radius: 8px;
}

.lab-editor-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;

  strong,
  span {
    display: block;
  }

  strong {
    color: #1f2937;
    font-weight: 700;
  }

  span {
    margin-top: 3px;
    color: #64748b;
    font-size: 12px;
    line-height: 1.45;
  }
}

.lab-editor-actions {
  display: flex;
  flex-shrink: 0;
  gap: 6px;
}

.lab-metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.lab-metric-card {
  display: grid;
  gap: 7px;
  min-width: 0;
  padding: 9px;
  background: #ffffff;
  border: 1px solid #e6edf5;
  border-radius: 6px;
}

.metric-label {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;

  strong {
    min-width: 0;
    overflow: hidden;
    color: #334155;
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    flex-shrink: 0;
    color: #94a3b8;
    font-size: 12px;
  }
}

.metric-control {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 92px;
  gap: 8px;
}

@media (width <= 1280px) {
  .lab-metric-grid {
    grid-template-columns: 1fr;
  }
}
</style>
