<template>
  <section class="lab-ocr-panel">
    <!-- 段 1 · 上传 -->
    <section class="ocr-block">
      <div class="block-title">
        <span class="step-no">1</span>
        <div><strong>上传化验单</strong><small>选择模板 → 拍摄或上传照片</small></div>
      </div>
      <div class="select-grid">
        <label class="field-label">化验模板</label>
        <el-select v-model="templateId" filterable>
          <el-option
            v-for="item in labReportTemplates"
            :key="item.id"
            :label="`${item.name}（${item.subtitle}）`"
            :value="item.id"
          />
        </el-select>
        <label class="field-label">识别模型 <small>仅 GLM 系列已验证图像识别</small></label>
        <el-select v-model="model" :loading="modelsLoading">
          <el-option v-for="item in modelOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </div>
      <el-alert
        v-if="existingReport"
        type="warning"
        :closable="false"
        show-icon
        :title="`该模板已有一份报告（v${existingReport.version}），保存后识别值将覆盖同名指标`"
      />
      <div class="capture-row">
        <label class="capture-main">
          <input type="file" accept="image/*" capture="environment" @change="onPickFile" />
          <el-icon><Camera /></el-icon>
          <span>{{ file ? "重新拍摄" : "拍摄化验单" }}</span>
        </label>
        <label class="upload-sub">
          <input type="file" accept="image/*,.pdf" @change="onPickFile" />
          <el-icon><Upload /></el-icon>
          <span>相册 / PDF</span>
        </label>
      </div>
      <img v-if="previewUrl" :src="previewUrl" class="preview-image" alt="化验单预览" />
      <div v-else-if="file" class="file-pill">{{ file.name }}</div>
      <el-button
        class="recognize-button"
        type="primary"
        size="large"
        :disabled="!file || !canEdit || recognizing"
        @click="startStream"
      >
        {{ recognizing ? "识别中…" : "开始 AI 识别" }}
      </el-button>
      <p v-if="!canEdit" class="readonly-hint">当前账号无化验报告填写权限，仅可查看。</p>
    </section>

    <!-- 段 2 · 修改 -->
    <section v-if="recognizing || rows.length" class="ocr-block">
      <div class="block-title">
        <span class="step-no">2</span>
        <div><strong>修改</strong><small>核对并修正识别值，超出参考范围的卡片已标黄</small></div>
      </div>
      <div v-if="recognizing" class="stream-pane">
        <div class="stream-status">
          <span class="stream-dot" aria-hidden="true"></span>
          <span>{{ streamStatus }}</span>
          <el-button link size="small" type="danger" @click="cancelStream">取消</el-button>
        </div>
        <pre ref="streamBox" class="stream-text">{{ streamText || "等待模型输出…" }}</pre>
      </div>
      <template v-else>
        <div v-if="unmatched.length" class="unmatched-row">
          <span>图片中存在但模板外（未采用）：</span>
          <el-tag v-for="name in unmatched" :key="name" size="small" effect="plain">{{ name }}</el-tag>
        </div>
        <div
          v-for="row in rows"
          :key="row.key"
          class="metric-card"
          :class="{ abnormal: row.abnormal && row.value, empty: !row.value }"
        >
          <div class="metric-head">
            <strong>{{ row.name }}</strong>
            <el-tag v-if="row.value && row.abnormal" type="warning" size="small" effect="dark">超出参考</el-tag>
            <el-tag v-else-if="row.matched" type="success" size="small" effect="plain">已识别</el-tag>
            <el-tag v-else type="info" size="small" effect="plain">待手动</el-tag>
          </div>
          <el-input v-model="row.value" size="large" placeholder="填写数值" />
          <div class="metric-meta">单位 {{ row.unit || "—" }} · 参考 {{ row.reference || "—" }}</div>
        </div>
      </template>
    </section>

    <!-- 段 3 · 确认 -->
    <section v-if="rows.length && !recognizing" class="ocr-block confirm-block">
      <div class="block-title">
        <span class="step-no">3</span>
        <div><strong>确认</strong><small>保存为报告草稿，稍后在「化验指标」页签逐项核对</small></div>
      </div>
      <el-button
        class="confirm-button"
        type="primary"
        size="large"
        :loading="saving"
        :disabled="!filledCount || !canEdit"
        @click="saveDraft"
      >
        确认保存（{{ filledCount }} 项）
      </el-button>
      <p class="confirm-note">AI 识别结果仅为预填草稿；危急值必须人工复核确认。</p>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Camera, Upload } from "@element-plus/icons-vue";
import {
  ocrLabReportStreamRequest,
  savePreAiLabReportApi,
  type LabReportMetricSnapshot,
  type PreAiWorkspace
} from "@/api/modules/clinic";
import { getRelayModelsApi } from "@/api/modules/clinic/medicalRecord";
import { labReportTemplates, metricReference, type LabTemplateId } from "@/views/workbench/labReport/templates";

const props = defineProps<{
  workspace: PreAiWorkspace;
  encounterId: string;
  canEdit?: boolean;
}>();

const emit = defineEmits<{
  (event: "updated", workspace: PreAiWorkspace): void;
  (event: "saved"): void;
}>();

const DEFAULT_OCR_MODEL = "glm-5.3-flash";
const templateId = ref<LabTemplateId>(labReportTemplates[0].id);
const template = computed(() => labReportTemplates.find(item => item.id === templateId.value) ?? labReportTemplates[0]);
const gender = computed(() => props.workspace?.encounter?.patient?.gender || "");
const existingReport = computed(() => props.workspace?.labReports?.find(report => report.templateId === templateId.value));

const model = ref(DEFAULT_OCR_MODEL);
const modelsLoading = ref(false);
const modelOptions = ref<Array<{ label: string; value: string }>>([
  { label: `${DEFAULT_OCR_MODEL}（默认）`, value: DEFAULT_OCR_MODEL }
]);

const file = ref<File | null>(null);
const previewUrl = ref("");
const rows = ref<
  Array<{ key: string; name: string; unit: string; reference: string; value: string; abnormal: boolean; matched: boolean }>
>([]);
const unmatched = ref<string[]>([]);
const recognizedAt = ref("");
const filledCount = computed(() => rows.value.filter(row => String(row.value || "").trim()).length);

const recognizing = ref(false);
const saving = ref(false);
const streamStatus = ref("");
const streamText = ref("");
const streamBox = ref<HTMLElement | null>(null);
let abortController: AbortController | null = null;
let streamFinished = false;

const loadModels = async () => {
  modelsLoading.value = true;
  try {
    const { data } = await getRelayModelsApi();
    const others = (data.models || [])
      .filter((item: string) => item !== DEFAULT_OCR_MODEL && /^glm/i.test(item))
      .map((item: string) => ({ label: item, value: item }));
    modelOptions.value = [{ label: `${DEFAULT_OCR_MODEL}（默认）`, value: DEFAULT_OCR_MODEL }, ...others];
  } catch {
    // 模型列表拉取失败时保底默认项
  } finally {
    modelsLoading.value = false;
  }
};
onMounted(() => {
  void loadModels();
});

const onPickFile = (event: Event) => {
  const input = event.target as HTMLInputElement;
  const picked = input.files?.[0];
  if (!picked) return;
  file.value = picked;
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = picked.type.startsWith("image/") ? URL.createObjectURL(picked) : "";
  input.value = "";
};

const scrollStream = async () => {
  await nextTick();
  if (streamBox.value) streamBox.value.scrollTop = streamBox.value.scrollHeight;
};
watch(streamText, () => {
  void scrollStream();
});

const applyResult = (event: { items?: any[]; unmatched?: string[] }) => {
  const byKey = new Map((event.items || []).map(item => [String(item.key || item.name).toLowerCase(), item]));
  const byName = new Map((event.items || []).map(item => [String(item.name).toLowerCase(), item]));
  rows.value = template.value.metrics.map(metric => {
    const item =
      byKey.get(metric.key) ??
      byName.get(metric.name.toLowerCase()) ??
      (metric.shortName ? byName.get(metric.shortName.toLowerCase()) : undefined);
    return {
      key: metric.key,
      name: metric.name,
      unit: metric.unit || item?.unit || "",
      reference: metricReference(metric, gender.value) || item?.reference || "",
      value: item?.value ?? "",
      abnormal: Boolean(item?.abnormal),
      matched: Boolean(item)
    };
  });
  unmatched.value = event.unmatched || [];
  recognizedAt.value = new Date().toLocaleString("zh-CN", { hour12: false });
};

const startStream = async () => {
  if (!file.value) return;
  recognizing.value = true;
  streamFinished = false;
  streamStatus.value = "正在发送识别请求…";
  streamText.value = "";
  rows.value = [];
  unmatched.value = [];
  abortController = new AbortController();
  const timeout = window.setTimeout(() => abortController?.abort(), 180000);
  try {
    const metrics = template.value.metrics.map(metric => ({
      key: metric.key,
      name: metric.name,
      shortName: metric.shortName,
      unit: metric.unit || "",
      reference: metricReference(metric, gender.value)
    }));
    const response = await ocrLabReportStreamRequest(
      props.encounterId,
      { file: file.value, metrics, templateName: template.value.name, model: model.value },
      abortController.signal
    );
    if (!response.ok) {
      let message = `识别请求失败（HTTP ${response.status}）`;
      try {
        const payload = await response.json();
        message = payload?.msg || message;
      } catch {
        // 非 JSON 错误体则用默认信息
      }
      throw new Error(message);
    }
    const reader = response.body?.getReader();
    if (!reader) throw new Error("当前浏览器不支持流式读取，请更换浏览器或使用手动填写");
    const decoder = new TextDecoder();
    let buffer = "";
    while (!streamFinished) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let separator = buffer.indexOf("\n\n");
      while (separator >= 0) {
        const rawEvent = buffer.slice(0, separator);
        buffer = buffer.slice(separator + 2);
        separator = buffer.indexOf("\n\n");
        for (const line of rawEvent.split("\n")) {
          if (!line.startsWith("data:")) continue;
          const payloadText = line.slice(5).trim();
          if (!payloadText) continue;
          let event: any;
          try {
            event = JSON.parse(payloadText);
          } catch {
            continue;
          }
          if (event.type === "status") {
            streamStatus.value = event.message || "";
          } else if (event.type === "delta") {
            streamText.value += event.text || "";
          } else if (event.type === "done") {
            applyResult(event);
            streamFinished = true;
          } else if (event.type === "error") {
            throw new Error(event.message || "识别失败，请重试");
          }
        }
      }
    }
    if (!streamFinished) throw new Error("识别连接中断，请重试或手动填写");
    ElMessage.success(`识别完成：匹配 ${rows.value.filter(row => row.matched).length} 项，请逐项核对后确认保存`);
  } catch (error: any) {
    const message =
      error?.name === "AbortError"
        ? "识别超时（3 分钟），请检查网络后重试，或手动填写"
        : error?.message || "化验单识别失败，请重试或手动填写";
    streamStatus.value = message;
    ElMessage.error(message);
  } finally {
    window.clearTimeout(timeout);
    recognizing.value = false;
    abortController = null;
  }
};

const cancelStream = () => {
  abortController?.abort();
};

const todayText = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
};

const saveDraft = async () => {
  const metrics: LabReportMetricSnapshot[] = [];
  for (const metric of template.value.metrics) {
    const row = rows.value.find(item => item.key === metric.key);
    const existingMetric = existingReport.value?.metrics.find(item => item.key === metric.key);
    const value = String(row?.value ?? existingMetric?.value ?? "").trim();
    if (!value) continue;
    const snapshot: LabReportMetricSnapshot = {
      key: metric.key,
      name: metric.name,
      shortName: metric.shortName,
      value,
      unit: metric.unit || existingMetric?.unit || "",
      reference: metricReference(metric, gender.value) || existingMetric?.reference || ""
    };
    if (row?.abnormal) snapshot.severity = "ABNORMAL";
    else if (existingMetric?.severity) snapshot.severity = existingMetric.severity;
    metrics.push(snapshot);
  }
  if (!metrics.length) {
    ElMessage.warning("没有可保存的指标值，请先识别或手动补填");
    return;
  }
  saving.value = true;
  try {
    const { data } = await savePreAiLabReportApi(props.encounterId, {
      templateId: template.value.id,
      templateName: template.value.name,
      reportDate: existingReport.value?.reportDate || todayText(),
      remark: "AI识别录入·待人工核对",
      metrics,
      expectedVersion: existingReport.value?.version ?? 0
    });
    emit("updated", data);
    ElMessage.success("识别结果已保存为报告草稿，请在「化验指标」页签核对后完成交接");
    emit("saved");
  } catch (error: any) {
    ElMessage.error(error?.message || "报告草稿保存失败");
  } finally {
    saving.value = false;
  }
};

onBeforeUnmount(() => {
  abortController?.abort();
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
});
</script>

<style scoped lang="scss">
.lab-ocr-panel {
  display: grid;
  gap: 14px;
}
.ocr-block {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  background: var(--el-bg-color);
}
.block-title {
  display: flex;
  align-items: center;
  gap: 10px;

  > div {
    display: grid;
    gap: 2px;
  }

  strong {
    font-size: 14.5px;
  }

  small {
    color: var(--el-text-color-secondary);
  }
}
.step-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  color: #fff;
  font-weight: 700;
  border-radius: 999px;
  background: var(--el-color-primary);
}
.select-grid {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr);
  gap: 10px 12px;
  align-items: center;

  .field-label {
    font-size: 13px;
    color: var(--el-text-color-regular);

    small {
      display: block;
      color: var(--el-text-color-secondary);
      font-size: 11px;
    }
  }
}
.capture-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 10px;
}
.capture-main,
.upload-sub {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 52px;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-color-primary);
  cursor: pointer;
  border: 1.5px dashed var(--el-color-primary);
  border-radius: 12px;
  background: var(--el-color-primary-light-9);
  transition:
    background-color 0.18s var(--ease-standard),
    border-color 0.18s var(--ease-standard);

  &:active {
    background: var(--el-color-primary-light-8);
  }

  input {
    display: none;
  }

  .el-icon {
    font-size: 20px;
  }
}
.upload-sub {
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-regular);
  border-style: solid;
  border-color: var(--el-border-color);
  background: var(--el-fill-color-light);
}
.preview-image {
  width: 100%;
  max-height: 320px;
  object-fit: contain;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
}
.file-pill {
  padding: 8px 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  border-radius: 10px;
  background: var(--el-fill-color-light);
  overflow-wrap: anywhere;
}
.recognize-button {
  width: 100%;
  height: 48px;
  font-size: 16px;
}
.readonly-hint {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.stream-pane {
  display: grid;
  gap: 8px;
}
.stream-status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--el-color-primary);
  font-weight: 600;

  .el-button {
    margin-left: auto;
  }
}
.stream-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: var(--el-color-primary);
  animation: ocr-pulse 1s ease-in-out infinite;
}
@keyframes ocr-pulse {
  0%,
  100% {
    opacity: 0.35;
  }
  50% {
    opacity: 1;
  }
}
@media (prefers-reduced-motion: reduce) {
  .stream-dot {
    animation: none;
  }
}
.stream-text {
  max-height: 200px;
  margin: 0;
  padding: 10px 12px;
  overflow-y: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  border-radius: 10px;
  background: var(--el-fill-color-dark, var(--el-fill-color));
}
.unmatched-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.metric-card {
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;

  &.abnormal {
    border-color: var(--el-color-warning-light-5);
    background: var(--el-color-warning-light-9);
  }

  &.empty {
    border-style: dashed;
  }
}
.metric-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;

  strong {
    font-size: 14px;
  }
}
.metric-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.confirm-block {
  position: sticky;
  bottom: 0;
  z-index: 3;
  border-color: var(--el-color-primary-light-5);
  box-shadow: 0 -6px 18px rgb(0 0 0 / 6%);
  background: color-mix(in srgb, var(--el-bg-color) 96%, var(--el-color-primary-light-9));
}
.confirm-button {
  width: 100%;
  height: 48px;
  font-size: 16px;
}
.confirm-note {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
@media (max-width: 760px) {
  .select-grid {
    grid-template-columns: 1fr;

    .field-label {
      margin-bottom: -4px;
    }
  }
  .capture-row {
    grid-template-columns: 1fr;
  }
  .upload-sub {
    min-height: 44px;
  }
}
</style>
