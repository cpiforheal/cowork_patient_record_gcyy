<template>
  <div class="ai-config-page">
    <section class="ai-status-panel">
      <div>
        <span class="section-label">运行状态</span>
        <h2>AI接口配置</h2>
      </div>
      <el-tag :type="statusTagType" effect="plain">{{ statusTagText }}</el-tag>
      <div class="status-grid">
        <article>
          <span>当前模型</span>
          <strong>{{ config.model || "待配置" }}</strong>
        </article>
        <article>
          <span>Key 状态</span>
          <strong>{{ config.apiKeyConfigured ? config.apiKeyMasked || "已配置" : "未配置" }}</strong>
        </article>
        <article>
          <span>配置来源</span>
          <strong>{{ config.usingRuntimeConfig ? "后台运行时配置" : "环境变量默认值" }}</strong>
        </article>
        <article>
          <span>最近更新</span>
          <strong>{{ config.updatedAt || "未记录" }}</strong>
        </article>
      </div>
    </section>

    <section class="ai-form-panel">
      <div class="panel-head">
        <div>
          <span class="section-label">接口参数</span>
          <h3>OpenAI-Compatible Endpoint</h3>
        </div>
        <el-button :icon="Refresh" :loading="loading" @click="loadConfig">刷新</el-button>
      </div>

      <el-form :model="form" label-width="108px" class="ai-config-form">
        <el-form-item label="启用 AI">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="例如 https://code.mrzengchn.com" clearable />
        </el-form-item>
        <el-form-item label="模型">
          <el-input v-model="form.model" placeholder="例如 gpt-5.5" clearable />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="留空并勾选保留现有 Key，则不会替换"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.keepExistingApiKey" :disabled="!config.apiKeyConfigured">保留现有 API Key</el-checkbox>
        </el-form-item>
      </el-form>

      <div class="form-actions">
        <el-button :icon="Refresh" @click="resetForm">还原</el-button>
        <el-button type="primary" :icon="Check" :loading="saving" @click="saveConfig">保存并立即生效</el-button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts" name="aiConfig">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { Check, Refresh } from "@element-plus/icons-vue";
import {
  getAiRuntimeConfigApi,
  saveAiRuntimeConfigApi,
  type AiRuntimeConfig,
  type AiRuntimeConfigPayload
} from "@/api/modules/clinic";

const emptyConfig: AiRuntimeConfig = {
  baseUrl: "",
  model: "gpt-5.5",
  enabled: true,
  apiKeyConfigured: false,
  apiKeyMasked: "",
  usingRuntimeConfig: false,
  updatedAt: "",
  updatedBy: ""
};

const config = ref<AiRuntimeConfig>({ ...emptyConfig });
const loading = ref(false);
const saving = ref(false);
const form = reactive<AiRuntimeConfigPayload>({
  baseUrl: "",
  model: "gpt-5.5",
  enabled: true,
  apiKey: "",
  keepExistingApiKey: true
});

const statusTagType = computed(() => {
  if (!config.value.enabled) return "info";
  return config.value.baseUrl && config.value.apiKeyConfigured ? "success" : "warning";
});

const statusTagText = computed(() => {
  if (!config.value.enabled) return "已停用";
  return config.value.baseUrl && config.value.apiKeyConfigured ? "可用配置" : "待补充";
});

const syncForm = () => {
  form.baseUrl = config.value.baseUrl || "";
  form.model = config.value.model || "gpt-5.5";
  form.enabled = config.value.enabled;
  form.apiKey = "";
  form.keepExistingApiKey = config.value.apiKeyConfigured;
};

const loadConfig = async () => {
  loading.value = true;
  try {
    const { data } = await getAiRuntimeConfigApi();
    config.value = data;
    syncForm();
  } finally {
    loading.value = false;
  }
};

const resetForm = () => {
  syncForm();
  ElMessage.info("已还原为当前运行配置");
};

const saveConfig = async () => {
  if (!form.baseUrl.trim()) {
    ElMessage.warning("请填写 Base URL");
    return;
  }
  if (!form.model.trim()) {
    ElMessage.warning("请填写模型名称");
    return;
  }
  if (!form.apiKey?.trim() && !form.keepExistingApiKey) {
    ElMessage.warning("请填写 API Key，或选择保留现有 Key");
    return;
  }

  saving.value = true;
  try {
    const { data } = await saveAiRuntimeConfigApi({
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      enabled: form.enabled,
      apiKey: form.apiKey?.trim(),
      keepExistingApiKey: form.keepExistingApiKey
    });
    config.value = data;
    syncForm();
    ElMessage.success("AI接口配置已保存，下一次生成总结立即使用新配置");
  } finally {
    saving.value = false;
  }
};

onMounted(loadConfig);
</script>

<style scoped lang="scss">
@import "@/styles/var.scss";

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
  border-radius: 6px;
}

.ai-status-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
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

.status-grid {
  display: grid;
  gap: 10px;

  article {
    padding: 12px;
    background: var(--el-fill-color-lighter);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;
  }

  span {
    display: block;
    margin-bottom: 8px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  strong {
    display: block;
    overflow-wrap: anywhere;
    font-size: 14px;
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
  margin-top: 22px;
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
}
</style>
