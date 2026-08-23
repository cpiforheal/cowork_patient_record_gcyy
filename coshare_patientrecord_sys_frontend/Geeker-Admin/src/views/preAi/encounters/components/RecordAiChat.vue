<template>
  <el-drawer
    :model-value="modelValue"
    size="min(620px, 96vw)"
    :with-header="true"
    :close-on-click-modal="false"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="chat-header">
        <div>
          <strong>AI 病历生成助手</strong>
          <small>内置住院病历范本 · 依据已复核前置事实生成 · 逐轮精修</small>
        </div>
        <el-button size="small" :icon="Refresh" @click="resetSession">新对话</el-button>
      </div>
    </template>

    <div ref="messageListRef" class="chat-body">
      <div v-if="!messages.length" class="chat-intro">
        <h4>对话式生成整套住院病历</h4>
        <ol>
          <li>发送首条指令（已预填标准口令），系统自动生成基础目标病历并加载内置范本；</li>
          <li>AI 按范本的格式、结构与查房时序生成完整病历，完成后卡片内可直接下载 DOCX；</li>
          <li>继续输入修改意见（如"术后第 5 天方剂调整"），以上一轮结果为底稿逐轮精修；</li>
          <li>每轮产物均进入版本链，前置事实变更后旧版本会自动标记过期。</li>
        </ol>
        <p class="intro-note">AI 生成内容仅供医生复核，下载后请逐字核对再使用。</p>
      </div>

      <template v-for="item in messages" :key="item.id">
        <div v-if="item.role === 'user'" class="bubble user-bubble">
          <pre>{{ item.text }}</pre>
        </div>

        <div v-else class="generation-card" :class="cardClass(item.card!)">
          <header class="card-head">
            <strong>{{ cardTitle(item.card!) }}</strong>
            <el-tag size="small" :type="cardTagType(item.card!)" effect="plain">
              {{ cardStatusText(item.card!) }}
            </el-tag>
          </header>

          <el-steps
            v-if="cardActive(item.card!)"
            :active="stageStep(item.card!)"
            align-center
            finish-status="success"
            class="card-steps"
          >
            <el-step title="准备" />
            <el-step title="AI 生成" />
            <el-step title="产物安检" />
            <el-step title="节点回填" />
          </el-steps>
          <p v-if="item.card!.stageMessage" class="card-message">{{ item.card!.stageMessage }}</p>

          <div v-if="item.card!.status === 'FAILED'" class="card-error">
            <p>{{ item.card!.errorCode || "GENERATION_FAILED" }}：{{ item.card!.errorMessage || "生成失败，可重试" }}</p>
            <el-button size="small" type="warning" plain :loading="busy" @click="retry(item.card!)">重试本轮</el-button>
          </div>

          <footer v-if="item.card!.status === 'SUCCEEDED'" class="card-success">
            <p>
              病历 V{{ item.card!.version || "-" }} 已生成
              <span v-if="item.card!.round === 1">· 首轮（内置范本）</span>
              <span v-else>· 第 {{ item.card!.round }} 轮精修</span>
            </p>
            <el-button size="small" type="primary" @click="download(item.card!)">下载 DOCX</el-button>
          </footer>
        </div>
      </template>
    </div>

    <div class="chat-footer">
      <el-input
        v-model="prompt"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 6 }"
        placeholder="输入生成指令或修改意见，Enter 发送（Shift+Enter 换行）"
        :disabled="busy"
        @keydown.enter.exact.prevent="send"
      />
      <div class="footer-actions">
        <small v-if="roundHint">{{ roundHint }}</small>
        <el-button type="primary" :loading="busy" :disabled="!prompt.trim()" @click="send">
          {{ lastOutputAssetId ? "发送精修指令" : "生成病历" }}
        </el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts" name="RecordAiChat">
import { computed, nextTick, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import {
  downloadMedicalRecordAssetV2Api,
  generateMedicalRecordApi,
  inspectBuiltinMedicalRecordTemplateApi,
  pollMedicalRecordWorkflowTask,
  retryMedicalRecordWorkflowTaskApi,
  submitMedicalRecordWorkflowTaskApi
} from "@/api/modules/clinic/medicalRecord";
import type { MedicalRecordWorkflowTask } from "@/api/modules/clinic/types";

const DEFAULT_PROMPT =
  "请按照周xx病历的格式、结构、段落、排版、查房时序，完整生成【姓名】【西医主诊断+次诊断 】的住院病历，要求自动生成中药方剂参考主病、主证及兼证、四诊内容，理法一致，不改动任何格式与写法，排版相同。";

interface GenerationCard {
  taskId: string;
  round: number;
  status: string;
  stage: string;
  stageMessage: string;
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
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "record-generated"): void;
}>();

const messages = ref<ChatItem[]>([]);
const prompt = ref(DEFAULT_PROMPT);
const busy = ref(false);
const baseRecordId = ref("");
const baseRecordVersion = ref("");
const builtinReportId = ref("");
const lastOutputAssetId = ref("");
const roundCount = ref(0);
const controller = ref<AbortController>();
const messageListRef = ref<HTMLElement>();

const roundHint = computed(() => {
  if (!baseRecordId.value) return "";
  const base = `基础目标病历 V${baseRecordVersion.value}`;
  return lastOutputAssetId.value ? `${base} · 下一轮将以上一轮产物为底稿精修` : `${base} · 首轮使用内置范本`;
});

const scrollToEnd = async () => {
  await nextTick();
  messageListRef.value?.scrollTo({ top: messageListRef.value.scrollHeight, behavior: "smooth" });
};

const resetSession = () => {
  if (busy.value) {
    ElMessage.warning("本轮生成仍在进行，请等待完成后再开新对话");
    return;
  }
  controller.value?.abort();
  messages.value = [];
  prompt.value = DEFAULT_PROMPT;
  baseRecordId.value = "";
  baseRecordVersion.value = "";
  builtinReportId.value = "";
  lastOutputAssetId.value = "";
  roundCount.value = 0;
};

watch(
  () => props.modelValue,
  visible => {
    if (!visible) {
      controller.value?.abort();
      return;
    }
    if (messages.value.length) return;
    prompt.value = DEFAULT_PROMPT;
  }
);

watch(() => props.encounterId, () => resetSession());

const applyTaskToCard = (card: GenerationCard, task: MedicalRecordWorkflowTask) => {
  card.taskId = task.taskId;
  card.status = task.status;
  card.stage = task.currentStage;
  const latestEvent = task.events?.[task.events.length - 1];
  card.stageMessage = latestEvent?.message || "";
  card.outputAssetId = task.outputAssetId || card.outputAssetId;
  card.errorCode = task.errorCode || "";
  card.errorMessage = task.errorMessage || "";
  const recordVersion = (task.result as { record?: { version?: number | string } } | undefined)?.record?.version;
  if (recordVersion !== undefined) card.version = String(recordVersion);
};

const ensureBaseRecord = async (signal: AbortSignal) => {
  if (baseRecordId.value) return;
  const { data } = await generateMedicalRecordApi({
    encounterId: props.encounterId,
    patientCaseId: props.patientCaseId
  });
  baseRecordId.value = data.record.id;
  baseRecordVersion.value = String(data.record.version);
  emit("record-generated");
};

const send = async () => {
  const text = prompt.value.trim();
  if (!text || busy.value || !props.encounterId) return;
  roundCount.value += 1;
  const round = roundCount.value;
  messages.value.push({ id: `u-${round}-${Date.now()}`, role: "user", text });
  const card: GenerationCard = {
    taskId: "",
    round,
    status: "PREPARING",
    stage: "QUEUED",
    stageMessage: round === 1 ? "正在生成基础目标病历并加载内置范本…" : "正在准备精修底稿…",
    outputAssetId: "",
    version: "",
    errorCode: "",
    errorMessage: ""
  };
  messages.value.push({ id: `c-${round}-${Date.now()}`, role: "card", card });
  prompt.value = "";
  busy.value = true;
  const requestController = new AbortController();
  controller.value = requestController;
  await scrollToEnd();
  try {
    await ensureBaseRecord(requestController.signal);
    let submitParams: Parameters<typeof submitMedicalRecordWorkflowTaskApi>[0];
    if (lastOutputAssetId.value) {
      submitParams = {
        referenceAssetId: lastOutputAssetId.value,
        sourceRecordId: baseRecordId.value,
        prompt: text,
        mappingMode: "LEGACY_ORDINAL"
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
        prompt: text,
        mappingMode: "LEGACY_ORDINAL"
      };
    }
    const { data: submitted } = await submitMedicalRecordWorkflowTaskApi(submitParams, requestController.signal);
    applyTaskToCard(card, submitted);
    const finalTask = await pollMedicalRecordWorkflowTask(submitted.taskId, {
      signal: requestController.signal,
      onUpdate: task => applyTaskToCard(card, task)
    });
    applyTaskToCard(card, finalTask);
    if (finalTask.status === "SUCCEEDED") {
      lastOutputAssetId.value = finalTask.outputAssetId || lastOutputAssetId.value;
      emit("record-generated");
      ElMessage.success(`第 ${round} 轮病历已生成${card.version ? `（V${card.version}）` : ""}，可在卡片中下载`);
    } else {
      ElMessage.error(`${finalTask.errorCode || "GENERATION_FAILED"}：${finalTask.errorMessage || "生成失败"}`);
    }
  } catch (error: any) {
    if (error?.name !== "AbortError") {
      card.status = "FAILED";
      card.errorCode = card.errorCode || "REQUEST_FAILED";
      card.errorMessage = error?.message || "生成请求失败";
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
  card.stageMessage = "任务已重新提交…";
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
const cardTitle = (card: GenerationCard) =>
  card.round === 1 ? "生成整套住院病历" : `第 ${card.round} 轮精修`;
const cardClass = (card: GenerationCard) => ({
  running: cardActive(card),
  failed: card.status === "FAILED",
  success: card.status === "SUCCEEDED"
});
const cardTagType = (card: GenerationCard) =>
  card.status === "SUCCEEDED" ? "success" : card.status === "FAILED" ? "danger" : "primary";
const cardStatusText = (card: GenerationCard) => {
  if (card.status === "SUCCEEDED") return "已完成";
  if (card.status === "FAILED") return "失败";
  if (card.status === "PREPARING") return "准备中";
  return "生成中";
};
const stageStep = (card: GenerationCard) => {
  switch (card.stage) {
    case "ASSET_LOADING":
      return 1;
    case "AI_GENERATION":
      return 2;
    case "OUTPUT_ASSET":
      return 3;
    case "NODE_MAPPING":
      return 4;
    default:
      return card.status === "SUCCEEDED" ? 4 : 0;
  }
};
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

.chat-intro {
  padding: 16px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border-radius: 8px;

  h4 {
    margin: 0 0 8px;
  }

  ol {
    padding-left: 18px;
    margin: 0;
  }

  .intro-note {
    margin: 10px 0 0;
    color: var(--el-color-warning);
  }
}

.bubble {
  max-width: 86%;
  padding: 10px 14px;
  border-radius: 10px;
  white-space: pre-wrap;
  word-break: break-word;

  pre {
    margin: 0;
    font-family: inherit;
    white-space: pre-wrap;
  }
}

.user-bubble {
  align-self: flex-end;
  color: #fff;
  background: var(--el-color-primary);
}

.generation-card {
  padding: 12px 14px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;

  &.running {
    border-color: var(--el-color-primary-light-5);
  }

  &.failed {
    border-color: var(--el-color-danger-light-5);
  }

  &.success {
    border-color: var(--el-color-success-light-5);
  }
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-steps {
  margin: 10px 0 4px;
}

.card-message {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.card-error {
  margin-top: 10px;
  font-size: 13px;
  color: var(--el-color-danger);

  p {
    margin: 0 0 8px;
  }
}

.card-success {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--el-border-color-lighter);

  p {
    margin: 0;
    font-size: 13px;
  }
}

.chat-footer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
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
