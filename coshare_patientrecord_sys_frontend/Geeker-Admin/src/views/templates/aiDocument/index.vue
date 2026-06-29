<template>
  <div class="table-box ai-document-page">
    <section class="tool-head">
      <div>
        <h2>AI 文稿生成 DOCX</h2>
        <p>粘贴领导整理的提纲、草稿或自然语言要求，系统调用已配置的 AI 直接生成最终文稿并输出 Word 文件。</p>
      </div>
      <el-tag effect="plain" size="large">调用 AI · 生成 DOCX · 不进入患者档案</el-tag>
    </section>

    <div class="tool-layout">
      <section class="editor-panel">
        <div class="panel-title">
          <span>文稿需求与素材</span>
          <small>{{ form.content.length }}/60000</small>
        </div>
        <el-form label-position="top">
          <el-form-item label="文档标题">
            <el-input v-model="form.title" maxlength="120" show-word-limit placeholder="例如：院内信息化建设工作提纲" />
          </el-form-item>
          <el-form-item label="文档类型">
            <el-select v-model="form.docType" filterable class="full-width" :loading="templateLoading">
              <el-option v-for="item in templates" :key="item.id" :label="item.name" :value="item.id" :disabled="!item.id">
                <span>{{ item.name }}</span>
                <small class="option-desc">{{ item.description }}</small>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="粘贴内容">
            <el-input
              v-model="form.content"
              type="textarea"
              :rows="20"
              resize="vertical"
              placeholder="可以粘贴豆包输出、自然语言要求、会议记录、提纲、表格草稿。点击生成后由 AI 直接给出最终成稿。"
            />
          </el-form-item>
        </el-form>
        <div class="actions">
          <el-button :icon="Refresh" :disabled="generateLoading" @click="resetForm">清空</el-button>
          <el-button :icon="View" :loading="previewLoading" @click="handlePreview">预检</el-button>
          <el-button type="primary" :icon="DocumentAdd" :loading="generateLoading" @click="handleGenerate">AI生成DOCX</el-button>
        </div>
      </section>

      <section class="preview-panel">
        <div class="panel-title">
          <span>AI 成稿预览</span>
          <el-button v-if="generatedDocument" type="primary" link :icon="Download" @click="downloadGenerated">下载</el-button>
        </div>

        <div v-if="generateLoading" class="ai-progress-card">
          <div class="ai-orbit">
            <span></span>
          </div>
          <div class="ai-progress-content">
            <strong>AI 正在分析并整理文稿</strong>
            <p>{{ progressText }}</p>
            <el-progress :percentage="progressPercent" :stroke-width="10" striped striped-flow />
          </div>
        </div>

        <el-empty v-else-if="!preview" description="粘贴内容后可先预检，也可以直接点击 AI生成DOCX" />
        <template v-else>
          <div class="preview-summary">
            <div>
              <strong>{{ preview.title }}</strong>
              <span>{{ preview.templateName }} · {{ preview.aiRequired ? "等待 AI 生成最终成稿" : "AI 已生成最终成稿" }}</span>
            </div>
            <el-space wrap>
              <el-tag effect="plain">段落 {{ preview.paragraphCount }}</el-tag>
            </el-space>
          </div>

          <div v-if="generatedDocument" class="result-card">
            <div>
              <strong>{{ generatedDocument.fileName }}</strong>
              <span>{{ generatedDocument.generatedAt }} · {{ generatedDocument.operator }}</span>
            </div>
            <el-button type="success" :icon="Download" @click="downloadGenerated">下载 DOCX</el-button>
          </div>

          <div class="doc-preview">
            <p v-for="(line, index) in previewTextLines" :key="index">{{ line }}</p>
          </div>
        </template>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts" name="aiDocumentGenerator">
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { DocumentAdd, Download, Refresh, View } from "@element-plus/icons-vue";
import {
  downloadAiDocumentApi,
  generateAiDocumentApi,
  getAiDocumentTemplatesApi,
  previewAiDocumentApi,
  type AiDocumentPreview,
  type AiDocumentTemplate,
  type GeneratedAiDocument
} from "@/api/modules/clinic";

const templates = ref<AiDocumentTemplate[]>([]);
const preview = ref<AiDocumentPreview | null>(null);
const generatedDocument = ref<GeneratedAiDocument | null>(null);
const templateLoading = ref(false);
const previewLoading = ref(false);
const generateLoading = ref(false);
const progressPercent = ref(0);
const progressText = ref("正在读取粘贴内容...");
let progressTimer: number | undefined;

const progressSteps = [
  "正在读取粘贴内容...",
  "AI 正在理解文稿目标...",
  "AI 正在组织成稿结构和表达...",
  "正在把 AI 成稿写入 Word 文件...",
  "正在写入 DOCX 文件..."
];

const form = reactive({
  title: "",
  docType: "",
  content: ""
});

const selectedTemplate = computed(() => templates.value.find(item => item.id === form.docType));

const loadTemplates = async () => {
  templateLoading.value = true;
  try {
    const { data } = await getAiDocumentTemplatesApi();
    templates.value = data.templates || [];
    form.docType = data.defaultTemplateId || templates.value[0]?.id || "general-outline";
  } finally {
    templateLoading.value = false;
  }
};

const validatePayload = () => {
  if (!form.content.trim()) {
    ElMessage.warning("请先粘贴需要 AI 整理的文稿内容");
    return false;
  }
  if (form.content.length > 60000) {
    ElMessage.warning("文稿内容过长，请控制在 60000 字以内");
    return false;
  }
  return true;
};

const payload = () => ({
  title: form.title.trim(),
  docType: form.docType || selectedTemplate.value?.id || "general-outline",
  content: form.content
});

const startProgress = () => {
  stopProgress();
  progressPercent.value = 8;
  progressText.value = progressSteps[0];
  progressTimer = window.setInterval(() => {
    const next = Math.min(progressPercent.value + Math.floor(Math.random() * 8) + 3, 92);
    progressPercent.value = next;
    const stepIndex = Math.min(Math.floor(next / 22), progressSteps.length - 1);
    progressText.value = progressSteps[stepIndex];
  }, 900);
};

const stopProgress = () => {
  if (progressTimer) {
    window.clearInterval(progressTimer);
    progressTimer = undefined;
  }
};

const handlePreview = async () => {
  if (!validatePayload()) return;
  previewLoading.value = true;
  try {
    const { data } = await previewAiDocumentApi(payload());
    preview.value = data;
    generatedDocument.value = null;
  } finally {
    previewLoading.value = false;
  }
};

const handleGenerate = async () => {
  if (!validatePayload()) return;
  generateLoading.value = true;
  preview.value = null;
  generatedDocument.value = null;
  startProgress();
  try {
    const { data } = await generateAiDocumentApi(payload());
    progressPercent.value = 100;
    progressText.value = "DOCX 文稿已生成";
    generatedDocument.value = data.document;
    preview.value = {
      title: data.document.title,
      docType: data.document.docType,
      templateName: data.document.templateName,
      paragraphCount: data.document.preview?.filter(item => item.type === "paragraph").length || 0,
      headingCount: data.document.preview?.filter(item => item.type === "heading").length || 0,
      listCount: data.document.preview?.filter(item => item.type === "list").length || 0,
      tableCount: data.document.preview?.filter(item => item.type === "table").length || 0,
      content: data.document.content || data.document.preview?.map(item => item.text).join("\n") || "",
      blocks: data.document.preview || []
    };
    ElMessage.success("AI 文稿 DOCX 已生成");
  } finally {
    stopProgress();
    window.setTimeout(() => {
      generateLoading.value = false;
    }, 350);
  }
};

const previewTextLines = computed(() => {
  if (!preview.value) return [];
  if (preview.value.content) {
    return preview.value.content
      .split(/\r?\n/)
      .map(line => line.trim())
      .filter(Boolean);
  }
  return (preview.value.blocks || []).map(block => block.text).filter(Boolean);
});

const downloadGenerated = async () => {
  if (!generatedDocument.value) return;
  await downloadAiDocumentApi(generatedDocument.value);
};

const resetForm = () => {
  form.title = "";
  form.content = "";
  preview.value = null;
  generatedDocument.value = null;
  progressPercent.value = 0;
  progressText.value = progressSteps[0];
};

onMounted(loadTemplates);
onUnmounted(stopProgress);
</script>

<style scoped lang="scss">
.ai-document-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tool-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  background: #ffffff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;

  h2 {
    margin: 0 0 6px;
    font-size: 20px;
    color: #1f2a37;
  }

  p {
    margin: 0;
    color: #667085;
  }
}

.tool-layout {
  display: grid;
  grid-template-columns: minmax(360px, 0.92fr) minmax(420px, 1.08fr);
  gap: 12px;
  min-height: 680px;
}

.editor-panel,
.preview-panel {
  padding: 18px;
  background: #ffffff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
}

.panel-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  color: #1f2a37;
  font-size: 16px;
  font-weight: 600;

  small {
    color: #98a2b3;
    font-weight: 400;
  }
}

.full-width {
  width: 100%;
}

.option-desc {
  margin-left: 10px;
  color: #98a2b3;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.ai-progress-card {
  display: grid;
  grid-template-columns: 92px 1fr;
  gap: 18px;
  align-items: center;
  padding: 22px;
  background: #f5fbff;
  border: 1px solid #bfdbfe;
  border-radius: 6px;
}

.ai-orbit {
  position: relative;
  width: 72px;
  height: 72px;
  border: 2px solid #93c5fd;
  border-radius: 50%;
  animation: pulse-ring 1.6s ease-in-out infinite;

  span {
    position: absolute;
    top: 13px;
    left: 13px;
    width: 42px;
    height: 42px;
    background: linear-gradient(135deg, #2563eb, #14b8a6);
    border-radius: 50%;
    animation: breathe 1.4s ease-in-out infinite;
  }
}

.ai-progress-content {
  strong {
    display: block;
    margin-bottom: 6px;
    color: #1f2a37;
    font-size: 16px;
  }

  p {
    margin: 0 0 14px;
    color: #667085;
  }
}

.preview-summary,
.result-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px;
  margin-bottom: 12px;
  background: #f8fafc;
  border: 1px solid #e4e7ed;
  border-radius: 6px;

  strong,
  span {
    display: block;
  }

  span {
    margin-top: 4px;
    color: #667085;
    font-size: 13px;
  }
}

.result-card {
  background: #f0f9f6;
  border-color: #b7e4cf;
}

.doc-preview {
  max-height: 560px;
  padding: 18px 20px;
  overflow: auto;
  color: #1f2a37;
  line-height: 1.8;
  background: #ffffff;
  border: 1px solid #ebeef5;
  border-radius: 6px;

  h3 {
    margin: 18px 0 8px;
    color: #111827;
  }

  .level-1 {
    font-size: 20px;
  }

  .level-2 {
    font-size: 18px;
  }

  .level-3 {
    font-size: 16px;
  }

  p {
    margin: 7px 0;
    white-space: pre-wrap;
  }

  .list-row {
    padding-left: 12px;
  }

  table {
    width: 100%;
    margin: 12px 0;
    border-collapse: collapse;
    font-size: 14px;
  }

  td {
    padding: 8px 10px;
    border: 1px solid #d0d5dd;
    vertical-align: top;
  }
}

@keyframes pulse-ring {
  0%,
  100% {
    transform: scale(1);
    opacity: 0.82;
  }

  50% {
    transform: scale(1.05);
    opacity: 1;
  }
}

@keyframes breathe {
  0%,
  100% {
    transform: scale(0.9);
  }

  50% {
    transform: scale(1);
  }
}

@media (max-width: 960px) {
  .tool-head,
  .preview-summary,
  .result-card,
  .ai-progress-card {
    align-items: flex-start;
    grid-template-columns: 1fr;
    flex-direction: column;
  }

  .tool-layout {
    grid-template-columns: 1fr;
  }
}
</style>
