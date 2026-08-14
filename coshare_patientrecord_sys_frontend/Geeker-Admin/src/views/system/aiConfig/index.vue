<template>
  <div class="ai-config-page">
    <section class="ai-status-panel">
      <div>
        <span class="section-label">运行状态</span>
        <h2>AI 接口配置</h2>
        <p>病历 AI 使用 GPT/OpenAI 兼容接口，与豆包助手分开配置、互不覆盖。</p>
      </div>

      <article v-for="item in statusCards" :key="item.key" class="status-card">
        <div>
          <span>{{ item.title }}</span>
          <el-tag :type="item.tagType" effect="plain">{{ item.tagText }}</el-tag>
        </div>
        <strong>{{ item.model || "待配置" }}</strong>
        <small>Key：{{ item.apiKeyText }}</small>
        <small>来源：{{ item.sourceText }}</small>
        <small>更新：{{ item.updatedAt || "未记录" }}</small>
      </article>
    </section>

    <section class="ai-form-stack">
      <article class="ai-form-panel">
        <div class="panel-head">
          <div>
            <span class="section-label">病历 AI 设置</span>
            <h3>GPT/OpenAI 兼容接口，用于病历总结与目标病历生成</h3>
          </div>
          <el-button :icon="Refresh" :loading="medicalLoading" @click="loadMedicalConfig">刷新</el-button>
        </div>
        <el-alert
          v-if="medicalConfig.apiKeyRequiresReset"
          title="现有病历 AI API Key 已无法解密，必须重新填写并保存"
          description="请取消“保留现有 API Key”，输入有效 Key 后保存。旧密文无法自动恢复；完成重置前住院病历 AI 生成不可用。"
          type="error"
          show-icon
          :closable="false"
        />
        <el-form :model="medicalForm" label-width="112px" class="ai-config-form">
          <el-form-item label="启用 AI">
            <el-switch v-model="medicalForm.enabled" active-text="启用" inactive-text="停用" />
          </el-form-item>
          <el-form-item label="Base URL">
            <el-input v-model="medicalForm.baseUrl" placeholder="例如 https://api.openai.com/v1/chat/completions" clearable />
          </el-form-item>
          <el-form-item label="Model">
            <el-input v-model="medicalForm.model" placeholder="例如 gpt-5.5" clearable />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input
              v-model="medicalForm.apiKey"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="留空并勾选保留现有 Key，则不会替换"
            />
          </el-form-item>
          <el-form-item>
            <el-checkbox
              v-model="medicalForm.keepExistingApiKey"
              :disabled="!medicalConfig.apiKeyConfigured || medicalConfig.apiKeyRequiresReset"
            >
              保留现有 API Key
            </el-checkbox>
          </el-form-item>
        </el-form>
        <div class="form-actions">
          <el-button :icon="Refresh" @click="resetMedicalForm">还原</el-button>
          <el-button type="primary" :icon="Check" :loading="medicalSaving" @click="saveMedicalConfig">保存病历 AI</el-button>
        </div>
      </article>

      <article class="ai-form-panel doubao-panel">
        <div class="panel-head">
          <div>
            <span class="section-label">豆包助手设置</span>
            <h3>4 个院内助手统一复用这套模型</h3>
          </div>
          <el-button :icon="Refresh" :loading="doubaoLoading" @click="loadDoubaoConfig">刷新</el-button>
        </div>
        <el-alert
          title="公共助手、患者助手、质控助手和管理助手共用此配置；原病历 AI 不受影响。"
          description="火山方舟一般填写 Base URL：https://ark.cn-beijing.volces.com/api/v3；Model 请填写控制台可调用的模型或推理接入点 ID；API Key 只填 key 本身，不要带 Bearer、中文逗号或空格。"
          type="info"
          show-icon
          :closable="false"
        />
        <el-form :model="doubaoForm" label-width="112px" class="ai-config-form">
          <el-form-item label="启用助手">
            <el-switch v-model="doubaoForm.enabled" active-text="启用" inactive-text="停用" />
          </el-form-item>
          <el-form-item label="Base URL">
            <el-input v-model="doubaoForm.baseUrl" placeholder="例如豆包 OpenAI-compatible 代理地址" clearable />
          </el-form-item>
          <el-form-item label="Model">
            <div class="model-picker">
              <el-select
                v-model="doubaoForm.model"
                filterable
                allow-create
                default-first-option
                clearable
                placeholder="先检测可用模型，或手动填写推理接入点 ID"
              >
                <el-option
                  v-for="model in doubaoModelOptions"
                  :key="model.id"
                  :label="model.name && model.name !== model.id ? `${model.name}（${model.id}）` : model.id"
                  :value="model.id"
                >
                  <div class="model-option">
                    <span>{{ model.name || model.id }}</span>
                    <small>{{ model.id }}</small>
                  </div>
                </el-option>
              </el-select>
              <el-button :icon="Search" :loading="doubaoModelDetecting" @click="detectDoubaoModels"> 检测可用模型 </el-button>
            </div>
            <small v-if="doubaoModelCheckedAt" class="model-hint">
              上次检测：{{ doubaoModelCheckedAt }}，也可以继续手动输入控制台中的模型或推理接入点 ID。
            </small>
            <small v-else class="model-hint">检测会临时使用当前 URL 和 Key，不会自动保存配置。</small>
          </el-form-item>
          <el-form-item label="API Key">
            <el-input
              v-model="doubaoForm.apiKey"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="留空并勾选保留现有 Key，则不会替换"
            />
          </el-form-item>
          <el-form-item>
            <el-checkbox v-model="doubaoForm.keepExistingApiKey" :disabled="!doubaoConfig.apiKeyConfigured">
              保留现有 API Key
            </el-checkbox>
          </el-form-item>
        </el-form>
        <div class="form-actions">
          <el-button :icon="Refresh" @click="resetDoubaoForm">还原</el-button>
          <el-button type="primary" :icon="Check" :loading="doubaoSaving" @click="saveDoubaoConfig">保存豆包助手</el-button>
        </div>
      </article>

      <article class="ai-form-panel tts-panel">
        <div class="panel-head">
          <div>
            <span class="section-label">豆包语音朗读</span>
            <h3>用于 AI 总结、随访建议和医生提醒的文本朗读</h3>
          </div>
          <el-button :icon="Refresh" :loading="ttsLoading" @click="loadTtsConfig">刷新</el-button>
        </div>
        <el-alert
          title="语音朗读使用独立配置，不会覆盖病历 AI 或豆包助手。Base URL 建议填写豆包 TTS 的完整调用地址。"
          description="朗读只处理当前弹窗中已生成的摘要文本，不重新调用总结模型，也不会把音频保存到病历字段。"
          type="info"
          show-icon
          :closable="false"
        />
        <el-form :model="ttsForm" label-width="112px" class="ai-config-form">
          <el-form-item label="启用朗读">
            <el-switch v-model="ttsForm.enabled" active-text="启用" inactive-text="停用" />
          </el-form-item>
          <el-form-item label="Base URL">
            <el-input v-model="ttsForm.baseUrl" placeholder="例如豆包语音合成 TTS 的完整接口地址" clearable />
          </el-form-item>
          <el-form-item label="Model">
            <el-input v-model="ttsForm.model" placeholder="例如 doubao-tts / seed-tts 资源模型" clearable />
          </el-form-item>
          <el-form-item label="Resource ID">
            <el-input v-model="ttsForm.resourceId" placeholder="可选：语音合成资源 ID / App ID / 接入点 ID" clearable />
          </el-form-item>
          <el-form-item label="Voice Type">
            <el-input v-model="ttsForm.voiceType" placeholder="可选：音色编码，例如 zh_female_xxx" clearable />
          </el-form-item>
          <el-form-item label="语速">
            <el-input-number
              v-model="ttsForm.speedRatio"
              :min="0.5"
              :max="2"
              :step="0.1"
              :precision="1"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input
              v-model="ttsForm.apiKey"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="留空并勾选保留现有 Key，则不会替换"
            />
          </el-form-item>
          <el-form-item>
            <el-checkbox v-model="ttsForm.keepExistingApiKey" :disabled="!ttsHasExistingKey">保留现有 API Key</el-checkbox>
          </el-form-item>
        </el-form>
        <div class="form-actions">
          <el-button :icon="Refresh" @click="resetTtsForm">还原</el-button>
          <el-button :icon="Search" :loading="ttsDetecting" @click="detectTtsConfig">检测并试听</el-button>
          <el-button type="primary" :icon="Check" :loading="ttsSaving" @click="saveTtsConfig">保存语音朗读</el-button>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts" name="aiConfig">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, type TagProps } from "element-plus";
import { Check, Refresh, Search } from "@element-plus/icons-vue";
import {
  detectDoubaoAiModelsApi,
  getAiRuntimeConfigApi,
  getDoubaoAiRuntimeConfigApi,
  getDoubaoTtsConfigApi,
  saveAiRuntimeConfigApi,
  saveDoubaoAiRuntimeConfigApi,
  saveDoubaoTtsConfigApi,
  testDoubaoTtsConfigApi,
  type AiModelOption,
  type AiRuntimeConfig,
  type AiRuntimeConfigPayload
} from "@/api/modules/clinic";

const emptyMedicalConfig: AiRuntimeConfig = {
  baseUrl: "",
  model: "gpt-5.5",
  enabled: true,
  apiKeyConfigured: false,
  apiKeyMasked: "",
  usingRuntimeConfig: false,
  updatedAt: "",
  updatedBy: ""
};

const emptyDoubaoConfig: AiRuntimeConfig = {
  ...emptyMedicalConfig,
  model: "doubao-seed-1-6"
};

const emptyTtsConfig: AiRuntimeConfig = {
  ...emptyMedicalConfig,
  model: "doubao-tts",
  resourceId: "",
  voiceType: "",
  speedRatio: 1
};

const createForm = (model: string, extra: Partial<AiRuntimeConfigPayload> = {}): AiRuntimeConfigPayload => ({
  baseUrl: "",
  model,
  enabled: true,
  apiKey: "",
  keepExistingApiKey: true,
  ...extra
});

const medicalConfig = ref<AiRuntimeConfig>({ ...emptyMedicalConfig });
const doubaoConfig = ref<AiRuntimeConfig>({ ...emptyDoubaoConfig });
const ttsConfig = ref<AiRuntimeConfig>({ ...emptyTtsConfig });
const medicalForm = reactive<AiRuntimeConfigPayload>(createForm("gpt-5.5"));
const doubaoForm = reactive<AiRuntimeConfigPayload>(createForm("doubao-seed-1-6"));
const ttsForm = reactive<AiRuntimeConfigPayload>(createForm("doubao-tts", { resourceId: "", voiceType: "", speedRatio: 1 }));
const medicalLoading = ref(false);
const doubaoLoading = ref(false);
const ttsLoading = ref(false);
const medicalSaving = ref(false);
const doubaoSaving = ref(false);
const ttsSaving = ref(false);
const ttsDetecting = ref(false);
const doubaoModelDetecting = ref(false);
const doubaoModelOptions = ref<AiModelOption[]>([]);
const doubaoModelCheckedAt = ref("");
const ttsHasExistingKey = computed(() => ttsConfig.value.apiKeyConfigured);

const statusOf = (config: AiRuntimeConfig) => {
  if (config.apiKeyRequiresReset) return { tagType: "danger" as TagProps["type"], tagText: "Key 待重置" };
  if (!config.enabled) return { tagType: "info" as TagProps["type"], tagText: "已停用" };
  return config.baseUrl && config.apiKeyConfigured
    ? { tagType: "success" as TagProps["type"], tagText: "可用" }
    : { tagType: "warning" as TagProps["type"], tagText: "待补全" };
};

const statusCards = computed(() => [
  {
    key: "medical",
    title: "病历 AI",
    model: medicalConfig.value.model,
    apiKeyText: medicalConfig.value.apiKeyConfigured ? medicalConfig.value.apiKeyMasked || "已配置" : "未配置",
    sourceText: medicalConfig.value.usingRuntimeConfig ? "后台运行时配置" : "环境变量默认值",
    updatedAt: medicalConfig.value.updatedAt,
    ...statusOf(medicalConfig.value)
  },
  {
    key: "doubao",
    title: "豆包助手",
    model: doubaoConfig.value.model,
    apiKeyText: doubaoConfig.value.apiKeyConfigured ? doubaoConfig.value.apiKeyMasked || "已配置" : "未配置",
    sourceText: doubaoConfig.value.usingRuntimeConfig ? "后台运行时配置" : "环境变量默认值",
    updatedAt: doubaoConfig.value.updatedAt,
    ...statusOf(doubaoConfig.value)
  },
  {
    key: "doubao-tts",
    title: "豆包语音朗读",
    model: ttsConfig.value.model,
    apiKeyText: ttsConfig.value.apiKeyConfigured ? ttsConfig.value.apiKeyMasked || "已配置" : "未配置",
    sourceText: ttsConfig.value.usingRuntimeConfig ? "后台运行时配置" : "环境变量默认值",
    updatedAt: ttsConfig.value.updatedAt,
    ...statusOf(ttsConfig.value)
  }
]);

const syncForm = (form: AiRuntimeConfigPayload, config: AiRuntimeConfig, fallbackModel: string) => {
  form.baseUrl = config.baseUrl || "";
  form.model = config.model || fallbackModel;
  form.resourceId = config.resourceId || "";
  form.voiceType = config.voiceType || "";
  form.speedRatio = config.speedRatio || 1;
  form.enabled = config.enabled;
  form.apiKey = "";
  form.keepExistingApiKey = config.apiKeyConfigured && !config.apiKeyRequiresReset;
};

const loadMedicalConfig = async () => {
  medicalLoading.value = true;
  try {
    const { data } = await getAiRuntimeConfigApi();
    medicalConfig.value = data;
    syncForm(medicalForm, data, "gpt-5.5");
  } finally {
    medicalLoading.value = false;
  }
};

const loadDoubaoConfig = async () => {
  doubaoLoading.value = true;
  try {
    const { data } = await getDoubaoAiRuntimeConfigApi();
    doubaoConfig.value = data;
    syncForm(doubaoForm, data, "doubao-seed-1-6");
    if (data.model && !doubaoModelOptions.value.some(item => item.id === data.model)) {
      doubaoModelOptions.value = [{ id: data.model, name: data.model }, ...doubaoModelOptions.value];
    }
  } finally {
    doubaoLoading.value = false;
  }
};

const loadTtsConfig = async () => {
  ttsLoading.value = true;
  try {
    const { data } = await getDoubaoTtsConfigApi();
    ttsConfig.value = data;
    syncForm(ttsForm, data, "doubao-tts");
  } finally {
    ttsLoading.value = false;
  }
};

const validateForm = (form: AiRuntimeConfigPayload, hasExistingKey: boolean) => {
  if (!form.baseUrl.trim()) {
    ElMessage.warning("请填写 Base URL");
    return false;
  }
  if (!form.model.trim()) {
    ElMessage.warning("请填写模型名称");
    return false;
  }
  if (!form.apiKey?.trim() && !form.keepExistingApiKey && !hasExistingKey) {
    ElMessage.warning("请填写 API Key，或保留现有 Key");
    return false;
  }
  return true;
};

const savePayload = (form: AiRuntimeConfigPayload) => ({
  baseUrl: form.baseUrl.trim(),
  model: form.model.trim(),
  resourceId: form.resourceId?.trim() || "",
  voiceType: form.voiceType?.trim() || "",
  speedRatio: form.speedRatio || 1,
  enabled: form.enabled,
  apiKey: form.apiKey?.trim(),
  keepExistingApiKey: form.keepExistingApiKey
});

const resetMedicalForm = () => {
  syncForm(medicalForm, medicalConfig.value, "gpt-5.5");
  ElMessage.info("已还原病历 AI 当前配置");
};

const resetDoubaoForm = () => {
  syncForm(doubaoForm, doubaoConfig.value, "doubao-seed-1-6");
  ElMessage.info("已还原豆包助手当前配置");
};

const resetTtsForm = () => {
  syncForm(ttsForm, ttsConfig.value, "doubao-tts");
  ElMessage.info("已还原豆包语音朗读当前配置");
};

const playTtsAudio = async (audioBase64: string, mimeType: string) => {
  const binary = window.atob(audioBase64);
  const chunks: ArrayBuffer[] = [];
  for (let offset = 0; offset < binary.length; offset += 1024) {
    const slice = binary.slice(offset, offset + 1024);
    const bytes = new Uint8Array(slice.length);
    for (let index = 0; index < slice.length; index += 1) {
      bytes[index] = slice.charCodeAt(index);
    }
    chunks.push(bytes.buffer);
  }
  const url = URL.createObjectURL(new Blob(chunks, { type: mimeType || "audio/mpeg" }));
  const audio = new Audio(url);
  audio.onended = () => URL.revokeObjectURL(url);
  audio.onerror = () => {
    URL.revokeObjectURL(url);
    ElMessage.error("检测音频已生成，但浏览器播放失败");
  };
  await audio.play();
};

const detectDoubaoModels = async () => {
  if (!doubaoForm.baseUrl.trim()) {
    ElMessage.warning("请先填写豆包 Base URL");
    return;
  }
  const hasTypedKey = Boolean(doubaoForm.apiKey?.trim());
  const canUseExistingKey = Boolean(doubaoForm.keepExistingApiKey && doubaoConfig.value.apiKeyConfigured);
  if (!hasTypedKey && !canUseExistingKey) {
    ElMessage.warning("请先填写 API Key，或勾选保留现有 Key 后再检测");
    return;
  }
  doubaoModelDetecting.value = true;
  try {
    const { data } = await detectDoubaoAiModelsApi({
      baseUrl: doubaoForm.baseUrl.trim(),
      apiKey: hasTypedKey ? doubaoForm.apiKey?.trim() : "",
      keepExistingApiKey: !hasTypedKey && canUseExistingKey
    });
    doubaoModelOptions.value = data.models || [];
    doubaoModelCheckedAt.value = data.checkedAt || "";
    if (!doubaoForm.model.trim() && doubaoModelOptions.value.length) {
      doubaoForm.model = doubaoModelOptions.value[0].id;
    }
    if (doubaoModelOptions.value.length) {
      ElMessage.success(`已检测到 ${doubaoModelOptions.value.length} 个可用模型`);
    } else {
      ElMessage.warning(data.warning || "已连通接口，但没有检测到模型，可手动填写模型或推理接入点 ID");
    }
  } finally {
    doubaoModelDetecting.value = false;
  }
};

const detectTtsConfig = async () => {
  if (!validateForm(ttsForm, ttsConfig.value.apiKeyConfigured)) return;
  ttsDetecting.value = true;
  try {
    const { data } = await testDoubaoTtsConfigApi({
      ...savePayload(ttsForm),
      text: "豆包语音朗读配置检测成功。"
    });
    await playTtsAudio(data.audioBase64, data.mimeType);
    ElMessage.success("豆包语音朗读配置可用，已播放测试音频");
  } finally {
    ttsDetecting.value = false;
  }
};

const saveMedicalConfig = async () => {
  if (medicalConfig.value.apiKeyRequiresReset && !medicalForm.apiKey?.trim()) {
    ElMessage.warning("现有 API Key 已无法解密，请重新填写 API Key 后保存");
    return;
  }
  if (!validateForm(medicalForm, medicalConfig.value.apiKeyConfigured && !medicalConfig.value.apiKeyRequiresReset)) return;
  medicalSaving.value = true;
  try {
    const { data } = await saveAiRuntimeConfigApi(savePayload(medicalForm));
    medicalConfig.value = data;
    syncForm(medicalForm, data, "gpt-5.5");
    ElMessage.success("病历 AI 配置已保存");
  } finally {
    medicalSaving.value = false;
  }
};

const saveDoubaoConfig = async () => {
  if (!validateForm(doubaoForm, doubaoConfig.value.apiKeyConfigured)) return;
  doubaoSaving.value = true;
  try {
    const { data } = await saveDoubaoAiRuntimeConfigApi(savePayload(doubaoForm));
    doubaoConfig.value = data;
    syncForm(doubaoForm, data, "doubao-seed-1-6");
    ElMessage.success("豆包助手配置已保存");
  } finally {
    doubaoSaving.value = false;
  }
};

const saveTtsConfig = async () => {
  if (!validateForm(ttsForm, ttsConfig.value.apiKeyConfigured)) return;
  ttsSaving.value = true;
  try {
    const { data } = await saveDoubaoTtsConfigApi(savePayload(ttsForm));
    ttsConfig.value = data;
    syncForm(ttsForm, data, "doubao-tts");
    ElMessage.success("豆包语音朗读配置已保存");
  } finally {
    ttsSaving.value = false;
  }
};

onMounted(() => {
  loadMedicalConfig();
  loadDoubaoConfig();
  loadTtsConfig();
});
</script>

<style scoped lang="scss">
@use "@/styles/var.scss" as *;

.ai-config-page {
  display: grid;
  grid-template-columns: minmax(280px, 360px) minmax(0, 1fr);
  gap: 16px;
  height: 100%;
}

.ai-status-panel,
.ai-form-panel {
  min-width: 0;
  padding: 18px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.ai-status-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;

  p {
    margin: 8px 0 0;
    color: var(--el-text-color-secondary);
    line-height: 1.6;
  }
}

.ai-form-stack {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.section-label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

h2,
h3 {
  margin: 0;
  color: var(--el-text-color-primary);
  letter-spacing: 0;
}

.status-card {
  display: grid;
  gap: 8px;
  padding: 14px;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;

  div {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }

  span,
  small {
    color: var(--el-text-color-secondary);
  }

  strong {
    overflow-wrap: anywhere;
    color: var(--el-text-color-primary);
  }
}

.panel-head,
.form-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.ai-config-form {
  max-width: 760px;
  margin-top: 18px;
}

.ai-form-panel > .el-alert {
  max-width: 760px;
  margin: 14px 0;
}

.doubao-panel .ai-config-form {
  margin-top: 14px;
}

.model-picker {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  width: 100%;

  .el-select {
    width: 100%;
  }
}

.model-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  small {
    max-width: 52%;
    overflow: hidden;
    color: var(--el-text-color-secondary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.model-hint {
  display: block;
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.form-actions {
  max-width: 760px;
  padding-top: 14px;
  border-top: 1px solid var(--el-border-color-lighter);
}

@media (max-width: 900px) {
  .ai-config-page {
    grid-template-columns: 1fr;
  }

  .panel-head,
  .form-actions {
    align-items: flex-start;
    flex-direction: column;
  }

  .model-picker {
    grid-template-columns: 1fr;
  }
}
</style>
