<template>
  <el-drawer
    :model-value="modelValue"
    size="min(560px, 96vw)"
    :with-header="true"
    :close-on-click-modal="false"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="chat-header">
        <div>
          <strong>AI 病历生成</strong>
          <small>提示词已预置 · 确认后直接生成可下载的住院病历 DOCX</small>
        </div>
        <el-button size="small" :icon="Refresh" @click="resetSession">新对话</el-button>
      </div>
    </template>

    <div ref="messageListRef" class="chat-body">
      <div v-if="!messages.length" class="chat-hint">
        标准生成口径已固定（见下方标签），可直接点「发送」；需要针对本例特殊强调时（如中医辨证侧重、方剂取舍），在输入框写备注后发送。AI
        会结合该患者的已复核资料与历轮备注逐轮收敛生成整套住院病历。
      </div>

      <template v-for="item in messages" :key="item.id">
        <div v-if="item.role === 'user'" class="bubble user-bubble">
          <pre>{{ item.text }}</pre>
        </div>

        <div v-else class="bubble assistant-bubble" :class="bubbleClass(item.card!)">
          <template v-if="cardActive(item.card!)">
            <p class="assistant-line">
              <el-icon class="is-loading"><Loading /></el-icon>
              {{ runningText(item.card!) }}
            </p>
            <p v-if="item.card!.stageMessage" class="assistant-sub">{{ item.card!.stageMessage }}</p>
          </template>

          <template v-else-if="item.card!.status === 'SUCCEEDED'">
            <p class="assistant-line">已按您的要求生成住院病历{{ versionText(item.card!) }}，请下载后逐字复核。</p>
            <p class="assistant-sub">如需调整（如更换方剂、修改某段病程），直接在下方输入即可继续精修。</p>
            <div class="bubble-actions">
              <el-button size="small" type="primary" @click="download(item.card!)">下载 DOCX</el-button>
            </div>
          </template>

          <template v-else>
            <p class="assistant-line error-line">生成失败：{{ item.card!.errorMessage || "请重试" }}</p>
            <div class="bubble-actions">
              <el-button size="small" type="warning" plain :loading="busy" @click="retry(item.card!)">重试</el-button>
            </div>
          </template>

          <div v-if="item.card!.progressMessages.length" class="chapter-progress">
            <pre v-for="(message, index) in item.card!.progressMessages" :key="`${item.card!.taskId || item.id}-${index}`">{{
              message
            }}</pre>
          </div>
        </div>
      </template>
    </div>

    <div class="chat-footer">
      <div class="attachment-bar">
        <el-tooltip content="AI 首轮按此范本的章节结构与查房时序生成；范本中的患者信息均已脱敏，不会带入当前病历" placement="top">
          <el-tag type="primary" effect="plain">
            <el-icon><DocumentCopy /></el-icon>
            已固定：住院病历范本（周xx·脱敏版）
          </el-tag>
        </el-tooltip>
        <el-tooltip :content="personalizedPrompt" placement="top">
          <el-tag type="warning" effect="plain">
            <el-icon><DocumentCopy /></el-icon>
            已固定：标准生成口径（已代入本例患者与主诊断）
          </el-tag>
        </el-tooltip>
        <template v-if="pinnedExport">
          <el-tag
            v-if="attachExport"
            type="success"
            effect="light"
            closable
            :title="pinnedExport.fileName"
            @close="attachExport = false"
          >
            <el-icon><Document /></el-icon>
            已附加：脱敏前置资料 V{{ pinnedExport.version }}
          </el-tag>
          <el-button v-else link type="primary" size="small" @click="attachExport = true">
            + 附加脱敏前置资料 V{{ pinnedExport.version }}
          </el-button>
          <small class="attachment-note">AI 将以范本定结构、以该资料定事实</small>
        </template>
        <small v-else class="attachment-note muted">当前病例暂无有效脱敏资料，可先在复核面板生成</small>
      </div>
      <div class="model-bar">
        <span class="model-label">生成模型</span>
        <el-select v-model="selectedModel" class="model-select" size="small" :disabled="busy" placeholder="系统默认">
          <el-option :label="defaultModelOptionLabel" value="" />
          <el-option v-for="model in relayModels.models" :key="model" :label="model" :value="model" />
        </el-select>
        <small v-if="!relayModels.configured" class="model-hint">未检测到模型服务列表，将按系统配置生成</small>
      </div>
      <el-input
        v-model="notes"
        type="textarea"
        :autosize="{ minRows: 3, maxRows: 8 }"
        :placeholder="notesPlaceholder"
        :disabled="busy"
        @keydown.enter.exact.prevent="send"
      />
      <div class="footer-actions">
        <small>标准口径已固定，本框仅记录医生本例特殊备注；生成仅供医生复核使用</small>
        <el-button type="primary" :loading="busy" :disabled="busy" @click="send">发送</el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts" name="RecordAiChat">
import { computed, nextTick, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Document, DocumentCopy, Loading, Refresh } from "@element-plus/icons-vue";
import {
  downloadMedicalRecordAssetV2Api,
  generateMedicalRecordApi,
  inspectBuiltinMedicalRecordTemplateApi,
  getRelayModelsApi,
  pollMedicalRecordWorkflowTask,
  retryMedicalRecordWorkflowTaskApi,
  submitMedicalRecordWorkflowTaskApi
} from "@/api/modules/clinic/medicalRecord";
import type { RelayModelsInfo } from "@/api/modules/clinic/medicalRecord";
import type { MedicalRecordWorkflowTask } from "@/api/modules/clinic/types";
import type { PreAiExportVersion } from "@/api/modules/clinic/preAi";

const DEFAULT_PROMPT =
  "请按照周xx病历的格式、结构、段落、排版、查房时序，完整生成【姓名】【西医主诊断+次诊断 】的住院病历，要求自动生成中药方剂参考主病、主证及兼证、四诊内容，理法一致，不改动任何格式与写法，排版相同。";

interface GenerationCard {
  taskId: string;
  round: number;
  status: string;
  stage: string;
  stageMessage: string;
  progressMessages: string[];
  outputAssetId: string;
  version: string;
  errorCode: string;
  errorMessage: string;
}

interface ChatItem {
  id: string;
  role: "user" | "card";
  text?: string;
  card?: GenerationCard;
}

const props = defineProps<{
  modelValue: boolean;
  encounterId: string;
  patientCaseId?: string;
  patientName?: string;
  mainDiagnosisText?: string;
  exports: PreAiExportVersion[];
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "record-generated"): void;
}>();

const messages = ref<ChatItem[]>([]);
const busy = ref(false);
const baseRecordId = ref("");
const builtinReportId = ref("");
const lastOutputAssetId = ref("");
const roundCount = ref(0);
const controller = ref<AbortController>();
const messageListRef = ref<HTMLElement>();
const attachExport = ref(true);
const relayModels = ref<RelayModelsInfo>({ configured: false, defaultModel: "", models: [] });
const selectedModel = ref("");

const defaultModelOptionLabel = computed(() =>
  relayModels.value.defaultModel ? `系统默认（${relayModels.value.defaultModel}）` : "系统默认"
);

const pinnedExport = computed(
  () => [...props.exports].filter(item => item.status && item.status !== "INVALIDATED").sort((a, b) => b.version - a.version)[0]
);

const personalizedPrompt = computed(() => {
  let text = DEFAULT_PROMPT;
  if (props.patientName) text = text.replace("【姓名】", `【${props.patientName}】`);
  if (props.mainDiagnosisText) text = text.replace("【西医主诊断+次诊断 】", `【${props.mainDiagnosisText}】`);
  return text;
});

const notes = ref("");
const notesHistory = ref<string[]>([]);
const notesPlaceholder =
  "输入本例特殊备注（如中医辨证侧重、方剂取舍、章节详略），留空则按标准口径生成；Enter 发送，Shift+Enter 换行。每轮备注会带入后续轮次作为上下文，直至开新对话。";

const scrollToEnd = async () => {
  await nextTick();
  messageListRef.value?.scrollTo({ top: messageListRef.value.scrollHeight, behavior: "smooth" });
};

const resetSession = () => {
  if (busy.value) {
    ElMessage.warning("正在生成，请等待完成后再开新对话");
    return;
  }
  controller.value?.abort();
  messages.value = [];
  notes.value = "";
  notesHistory.value = [];
  baseRecordId.value = "";
  builtinReportId.value = "";
  lastOutputAssetId.value = "";
  roundCount.value = 0;
  attachExport.value = true;
  selectedModel.value = "";
};

watch(
  () => props.modelValue,
  visible => {
    if (!visible) {
      controller.value?.abort();
      return;
    }
    if (messages.value.length) return;
    notes.value = "";
  }
);

watch(
  () => props.modelValue,
  async visible => {
    if (!visible || relayModels.value.models.length) return;
    try {
      const { data } = await getRelayModelsApi();
      relayModels.value = data;
    } catch {
      relayModels.value = { configured: false, defaultModel: "", models: [] };
    }
  }
);

watch(
  () => props.encounterId,
  () => resetSession()
);

const applyTaskToCard = (card: GenerationCard, task: MedicalRecordWorkflowTask) => {
  card.taskId = task.taskId;
  card.status = task.status;
  card.stage = task.currentStage;
  const progressMessages = (task.events || [])
    .filter(event => event.eventType === "CHAPTER_PROGRESS" && event.message)
    .map(event => event.message);
  card.progressMessages = progressMessages;
  const latestStageEvent = [...(task.events || [])].reverse().find(event => event.eventType !== "CHAPTER_PROGRESS");
  card.stageMessage = latestStageEvent?.message || "";
  card.outputAssetId = task.outputAssetId || card.outputAssetId;
  card.errorCode = task.errorCode || "";
  card.errorMessage = task.errorMessage || "";
  const recordVersion = (task.result as { record?: { version?: number | string } } | undefined)?.record?.version;
  if (recordVersion !== undefined) card.version = String(recordVersion);
};

const ensureBaseRecord = async () => {
  if (baseRecordId.value) return;
  const { data } = await generateMedicalRecordApi({
    encounterId: props.encounterId,
    patientCaseId: props.patientCaseId
  });
  baseRecordId.value = data.record.id;
  emit("record-generated");
};

const send = async () => {
  const trimmedNotes = notes.value.trim();
  if (busy.value || !props.encounterId) return;
  roundCount.value += 1;
  const round = roundCount.value;
  const composed = trimmedNotes ? `${personalizedPrompt.value}\n【医生本例备注】${trimmedNotes}` : personalizedPrompt.value;
  messages.value.push({
    id: `u-${round}-${Date.now()}`,
    role: "user",
    text: trimmedNotes ? `【本例备注】${trimmedNotes}` : "按标准口径生成本例病历"
  });
  const card: GenerationCard = {
    taskId: "",
    round,
    status: "PREPARING",
    stage: "QUEUED",
    stageMessage: "",
    progressMessages: [],
    outputAssetId: "",
    version: "",
    errorCode: "",
    errorMessage: ""
  };
  messages.value.push({ id: `c-${round}-${Date.now()}`, role: "card", card });
  notes.value = "";
  busy.value = true;
  const requestController = new AbortController();
  controller.value = requestController;
  await scrollToEnd();
  try {
    await ensureBaseRecord();
    const attachExportId = attachExport.value && pinnedExport.value ? pinnedExport.value.id : undefined;
    let submitParams: Parameters<typeof submitMedicalRecordWorkflowTaskApi>[0];
    if (lastOutputAssetId.value) {
      submitParams = {
        referenceAssetId: lastOutputAssetId.value,
        sourceRecordId: baseRecordId.value,
        prompt: composed,
        mappingMode: "LEGACY_ORDINAL",
        preAiExportId: attachExportId,
        conversationHistory: [...notesHistory.value],
        model: selectedModel.value || undefined
      };
    } else {
      if (!builtinReportId.value) {
        const { data: inspection } = await inspectBuiltinMedicalRecordTemplateApi(
          { encounterId: props.encounterId },
          requestController.signal
        );
        if (!inspection.canGenerate) throw new Error("内置范本安全检查未通过，请联系管理员");
        builtinReportId.value = inspection.reportId;
      }
      submitParams = {
        reportId: builtinReportId.value,
        sourceRecordId: baseRecordId.value,
        prompt: composed,
        mappingMode: "LEGACY_ORDINAL",
        preAiExportId: attachExportId,
        conversationHistory: [...notesHistory.value],
        model: selectedModel.value || undefined
      };
    }
    const { data: submitted } = await submitMedicalRecordWorkflowTaskApi(submitParams, requestController.signal);
    if (trimmedNotes) notesHistory.value.push(trimmedNotes);
    applyTaskToCard(card, submitted);
    const finalTask = await pollMedicalRecordWorkflowTask(submitted.taskId, {
      signal: requestController.signal,
      onUpdate: task => applyTaskToCard(card, task)
    });
    applyTaskToCard(card, finalTask);
    if (finalTask.status === "SUCCEEDED") {
      lastOutputAssetId.value = finalTask.outputAssetId || lastOutputAssetId.value;
      emit("record-generated");
      ElMessage.success("住院病历已生成，可在对话中下载");
    } else {
      ElMessage.error(finalTask.errorMessage || "生成失败，可在对话中重试");
    }
  } catch (error: any) {
    if (error?.name !== "AbortError") {
      if (String(error?.message || "").includes("轮询超时")) {
        // 后端任务仍在执行：转后台提示，完成后可在版本列表查看
        card.status = "RUNNING";
        card.stageMessage = "生成转入后台执行，完成后将出现在文档版本列表";
        ElMessage.info("生成仍在后台进行，完成后可在文档版本列表查看并下载");
      } else {
        card.status = "FAILED";
        card.errorCode = card.errorCode || "REQUEST_FAILED";
        card.errorMessage = error?.message || "生成请求失败";
      }
    }
  } finally {
    if (controller.value === requestController) controller.value = undefined;
    busy.value = false;
    await scrollToEnd();
  }
};

const retry = async (card: GenerationCard) => {
  if (busy.value || !card.taskId) return;
  busy.value = true;
  const requestController = new AbortController();
  controller.value = requestController;
  card.status = "RUNNING";
  card.stageMessage = "";
  card.progressMessages = [];
  try {
    const { data: retried } = await retryMedicalRecordWorkflowTaskApi(card.taskId, requestController.signal);
    applyTaskToCard(card, retried);
    const finalTask = await pollMedicalRecordWorkflowTask(retried.taskId, {
      signal: requestController.signal,
      onUpdate: task => applyTaskToCard(card, task)
    });
    applyTaskToCard(card, finalTask);
    if (finalTask.status === "SUCCEEDED") {
      lastOutputAssetId.value = finalTask.outputAssetId || lastOutputAssetId.value;
      emit("record-generated");
    }
  } catch (error: any) {
    if (error?.name !== "AbortError") {
      card.status = "FAILED";
      card.errorMessage = error?.message || card.errorMessage || "重试失败";
    }
  } finally {
    if (controller.value === requestController) controller.value = undefined;
    busy.value = false;
    await scrollToEnd();
  }
};

const download = async (card: GenerationCard) => {
  if (!card.outputAssetId) return;
  try {
    const downloadResult = await downloadMedicalRecordAssetV2Api(card.outputAssetId);
    const url = URL.createObjectURL(downloadResult.blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = downloadResult.filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 500);
  } catch (error: any) {
    ElMessage.error(error?.message || "下载失败");
  }
};

const cardActive = (card: GenerationCard) => card.status !== "SUCCEEDED" && card.status !== "FAILED";
const runningText = (card: GenerationCard) => {
  if (card.status === "PREPARING") return "正在准备患者资料与病历范本…";
  switch (card.stage) {
    case "ASSET_LOADING":
      return "正在调取病历范本…";
    case "AI_GENERATION":
      return "AI 正在生成整套住院病历…";
    case "OUTPUT_ASSET":
    case "NODE_MAPPING":
      return "正在整理生成结果…";
    default:
      return "正在生成…";
  }
};
const versionText = (card: GenerationCard) => (card.version ? `（V${card.version}）` : "");
const bubbleClass = (card: GenerationCard) => ({
  failed: card.status === "FAILED",
  success: card.status === "SUCCEEDED"
});
</script>

<style scoped lang="scss">
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  small {
    display: block;
    margin-top: 2px;
    color: var(--el-text-color-secondary);
  }
}

.chat-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  padding-right: 4px;
  overflow-y: auto;
}

.chat-hint {
  padding: 12px 14px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: 10px;
}

.bubble {
  max-width: 88%;
  padding: 10px 14px;
  border-radius: 10px;
  word-break: break-word;

  pre {
    margin: 0;
    font-family: inherit;
    white-space: pre-wrap;
  }

  p {
    margin: 0;
  }
}

.user-bubble {
  align-self: flex-end;
  color: #fff;
  background: var(--el-color-primary);

  pre {
    color: inherit;
  }
}

.assistant-bubble {
  align-self: flex-start;
  background: var(--el-fill-color-light);

  &.failed {
    background: var(--el-color-danger-light-9);
  }

  &.success {
    background: var(--el-color-success-light-9);
  }
}

.assistant-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  line-height: 1.6;

  &.error-line {
    color: var(--el-color-danger);
  }
}

.assistant-sub {
  margin-top: 4px !important;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.chapter-progress {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 320px;
  margin-top: 10px;
  padding: 8px 10px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;

  pre {
    margin: 0;
    font-family: inherit;
    white-space: pre-wrap;
  }
}

.bubble-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.model-bar {
  display: flex;
  align-items: center;
  gap: 8px;

  .model-label {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .model-select {
    width: 260px;
  }

  .model-hint {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}

.chat-footer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.attachment-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 24px;

  .el-tag {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }
}

.attachment-note {
  color: var(--el-text-color-secondary);

  &.muted {
    font-size: 12px;
  }
}

.footer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  small {
    color: var(--el-text-color-secondary);
  }
}
</style>
