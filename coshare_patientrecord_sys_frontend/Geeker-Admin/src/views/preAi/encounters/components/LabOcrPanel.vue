<template>
  <section class="lab-ocr-panel">
    <header class="ocr-head">
      <div>
        <strong>化验单 AI 识别</strong>
        <small>拍摄或上传化验单照片，按模板自动提取数值；识别结果仅供预填，保存后仍需人工核对。</small>
      </div>
      <el-tag effect="plain" type="info">识别引擎 · glm-5.3-flash</el-tag>
    </header>

    <el-alert
      v-if="existingReport"
      type="warning"
      :closable="false"
      show-icon
      :title="`该模板已有一份报告（v${existingReport.version}），保存后识别值将覆盖同名指标`"
    />

    <div class="ocr-step">
      <span class="ocr-step-no">1</span>
      <div class="ocr-step-body">
        <strong>选择化验模板</strong>
        <el-select v-model="templateId" class="template-select" filterable>
          <el-option
            v-for="item in labReportTemplates"
            :key="item.id"
            :label="`${item.name}（${item.subtitle}）`"
            :value="item.id"
          />
        </el-select>
      </div>
    </div>

    <div class="ocr-step">
      <span class="ocr-step-no">2</span>
      <div class="ocr-step-body">
        <strong>拍摄 / 上传化验单照片</strong>
        <div class="upload-row">
          <label class="upload-button">
            <input type="file" accept="image/*,.pdf" @change="onPickFile" />
            <el-icon><Upload /></el-icon> 选择图片或 PDF
          </label>
          <label class="upload-button camera-button">
            <input type="file" accept="image/*" capture="environment" @change="onPickFile" />
            <el-icon><Camera /></el-icon> 手机拍照
          </label>
          <span v-if="file" class="file-name">{{ file.name }}</span>
        </div>
        <img v-if="previewUrl" :src="previewUrl" class="preview-image" alt="化验单预览" />
      </div>
    </div>

    <div class="ocr-step">
      <span class="ocr-step-no">3</span>
      <div class="ocr-step-body">
        <strong>AI 识别与核对</strong>
        <div class="recognize-row">
          <el-button type="primary" :loading="recognizing" :disabled="!file || !canEdit" @click="recognize">
            {{ recognizing ? "识别中（约 10~30 秒）…" : "开始 AI 识别" }}
          </el-button>
          <span v-if="recognizedAt" class="recognized-at">{{ recognizedAt }} 识别 · 结果仅供预填</span>
        </div>
        <div v-if="unmatched.length" class="unmatched-row">
          <span>图片中存在但模板外（未采用）：</span>
          <el-tag v-for="name in unmatched" :key="name" size="small" effect="plain">{{ name }}</el-tag>
        </div>
        <table v-if="rows.length" class="result-table">
          <thead>
            <tr>
              <th>指标</th>
              <th>识别值（可修改）</th>
              <th>单位</th>
              <th>参考范围</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.key" :class="{ abnormal: row.abnormal && row.value }">
              <td>{{ row.name }}</td>
              <td>
                <el-input v-model="row.value" size="small" :placeholder="row.matched ? '' : '图片中未识别到，可手动补填'" />
              </td>
              <td>{{ row.unit }}</td>
              <td>{{ row.reference }}</td>
              <td>
                <el-tag v-if="row.value && row.abnormal" type="warning" size="small" effect="dark">超出参考</el-tag>
                <el-tag v-else-if="row.matched" type="success" size="small" effect="plain">已识别</el-tag>
                <el-tag v-else type="info" size="small" effect="plain">待手动</el-tag>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <footer class="ocr-actions">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="AI 识别结果仅为预填草稿；保存后请在「化验指标」页签逐项核对，危急值必须人工复核确认。"
      />
      <el-button type="primary" :loading="saving" :disabled="!filledCount || !canEdit" @click="saveDraft">
        保存为报告草稿（{{ filledCount }} 项）
      </el-button>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from "vue";
import { ElMessage } from "element-plus";
import { Camera, Upload } from "@element-plus/icons-vue";
import { ocrLabReportApi, savePreAiLabReportApi, type LabReportMetricSnapshot, type PreAiWorkspace } from "@/api/modules/clinic";
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

const templateId = ref<LabTemplateId>(labReportTemplates[0].id);
const template = computed(() => labReportTemplates.find(item => item.id === templateId.value) ?? labReportTemplates[0]);
const gender = computed(() => props.workspace?.encounter?.patient?.gender || "");
const existingReport = computed(() => props.workspace?.labReports?.find(report => report.templateId === templateId.value));

const file = ref<File | null>(null);
const previewUrl = ref("");
const recognizing = ref(false);
const saving = ref(false);
const rows = ref<
  Array<{ key: string; name: string; unit: string; reference: string; value: string; abnormal: boolean; matched: boolean }>
>([]);
const unmatched = ref<string[]>([]);
const recognizedAt = ref("");

const filledCount = computed(() => rows.value.filter(row => String(row.value || "").trim()).length);

const onPickFile = (event: Event) => {
  const input = event.target as HTMLInputElement;
  const picked = input.files?.[0];
  if (!picked) return;
  file.value = picked;
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = picked.type.startsWith("image/") ? URL.createObjectURL(picked) : "";
  input.value = "";
};

const recognize = async () => {
  if (!file.value) return;
  recognizing.value = true;
  try {
    const metrics = template.value.metrics.map(metric => ({
      key: metric.key,
      name: metric.name,
      shortName: metric.shortName,
      unit: metric.unit || "",
      reference: metricReference(metric, gender.value)
    }));
    const { data } = await ocrLabReportApi(props.encounterId, {
      file: file.value,
      metrics,
      templateName: template.value.name
    });
    const byKey = new Map((data.items || []).map(item => [String(item.key || item.name).toLowerCase(), item]));
    const byName = new Map((data.items || []).map(item => [String(item.name).toLowerCase(), item]));
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
    unmatched.value = data.unmatched || [];
    recognizedAt.value = new Date().toLocaleString("zh-CN", { hour12: false });
    ElMessage.success(`识别完成：匹配 ${rows.value.filter(row => row.matched).length} 项，请逐项核对后保存`);
  } catch (error: any) {
    ElMessage.error(error?.message || "化验单识别失败，请重试或手动填写");
  } finally {
    recognizing.value = false;
  }
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
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
});
</script>

<style scoped lang="scss">
.lab-ocr-panel {
  display: grid;
  gap: 14px;
}
.ocr-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  > div:first-child {
    display: grid;
    gap: 4px;
  }

  strong {
    font-size: 15px;
  }

  small {
    color: var(--el-text-color-secondary);
  }
}
.ocr-step {
  display: flex;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: var(--el-fill-color-extra-light);
}
.ocr-step-no {
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
.ocr-step-body {
  display: grid;
  gap: 10px;
  align-items: start;
  min-width: 0;
  flex: 1;

  > strong {
    font-size: 14px;
  }
}
.template-select {
  max-width: 420px;
  width: 100%;
}
.upload-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.file-name {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.preview-image {
  max-width: min(420px, 100%);
  max-height: 300px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}
.recognize-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.recognized-at {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.unmatched-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.result-table {
  width: 100%;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  border-collapse: collapse;

  th,
  td {
    padding: 8px 10px;
    font-size: 13px;
    text-align: left;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }

  th {
    color: var(--el-text-color-secondary);
    font-weight: 600;
    background: var(--el-fill-color-light);
  }

  tr:last-child td {
    border-bottom: 0;
  }

  tr.abnormal td {
    background: var(--el-color-warning-light-9);
  }

  .value-input {
    max-width: 160px;
  }
}
.ocr-actions {
  display: grid;
  gap: 10px;
  justify-items: start;
}
</style>
