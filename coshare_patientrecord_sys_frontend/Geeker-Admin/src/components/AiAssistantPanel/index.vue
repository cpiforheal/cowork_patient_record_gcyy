<template>
  <el-drawer
    :model-value="modelValue"
    :with-header="false"
    size="min(1120px, 94vw)"
    class="ai-assistant-workbench-drawer"
    @update:model-value="value => emit('update:modelValue', value)"
    @closed="stopSpeech"
  >
    <div class="assistant-workbench">
      <aside class="assistant-sidebar">
        <header class="assistant-brand">
          <div class="assistant-avatar">
            <img :src="doubaoAvatar" alt="豆包助手" />
          </div>
          <div>
            <strong>豆包院内助手</strong>
            <span>公共问答 · 多模态材料</span>
          </div>
        </header>

        <el-button class="new-chat-button" type="primary" plain :icon="CirclePlus" @click="newConversation">新建会话</el-button>

        <nav class="mode-list" aria-label="助手模式">
          <button
            v-for="item in assistantModes"
            :key="item.value"
            type="button"
            :class="{ active: activeType === item.value }"
            @click="switchType(item.value)"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
            <small>{{ item.short }}</small>
          </button>
        </nav>

        <section class="sidebar-block">
          <div class="block-title">
            <strong>最近会话</strong>
            <span>{{ currentSession.messages.length }} 条</span>
          </div>
          <div class="recent-list">
            <button v-for="item in recentSessions" :key="item.type" type="button" @click="switchType(item.type)">
              <span>{{ item.title }}</span>
              <small>{{ item.last || "暂无提问" }}</small>
            </button>
          </div>
        </section>

        <section class="sidebar-block material-status">
          <div class="block-title">
            <strong>已带入资料</strong>
            <span>{{ materialCount }} 项</span>
          </div>
          <div class="status-chip active">知识库优先，通用问题可回答</div>
          <div class="status-chip" :class="{ active: includeContext && hasContext }">
            {{ includeContext && hasContext ? "当前页面上下文已带入" : "未带入页面上下文" }}
          </div>
          <div v-if="props.patientId && shouldSendPatientContext" class="status-chip active">患者摘要仅用于本次问答</div>
          <div v-if="currentSession.attachments.length" class="status-chip active">
            {{ currentSession.attachments.length }} 张图片待发送
          </div>
        </section>
      </aside>

      <main class="assistant-main">
        <header class="assistant-topbar">
          <div>
            <span class="eyebrow">RAgent · {{ activeMode.label }}</span>
            <h2>{{ panelTitle }}</h2>
            <p>{{ description || activeMode.description }}</p>
          </div>
          <div class="topbar-actions">
            <el-tag effect="plain">{{ includeContext && hasContext ? "页面上下文开启" : "知识库优先" }}</el-tag>
            <el-button circle text aria-label="关闭豆包助手" @click="emit('update:modelValue', false)">
              <el-icon><Close /></el-icon>
            </el-button>
          </div>
        </header>

        <section class="context-strip">
          <button type="button" class="context-chip active">
            <el-icon><Collection /></el-icon>
            <span>{{ knowledgeHint }}</span>
          </button>
          <button
            type="button"
            class="context-chip"
            :class="{ active: includeContext && hasContext }"
            :disabled="!hasContext"
            @click="toggleContext"
          >
            <el-icon><Link /></el-icon>
            <span>{{ contextHint }}</span>
          </button>
          <button v-if="currentSession.attachments.length" type="button" class="context-chip active" @click="clearMaterials">
            <el-icon><Picture /></el-icon>
            <span>已选择 {{ currentSession.attachments.length }} 张图片，点击清空</span>
          </button>
        </section>

        <div ref="messageListRef" class="message-area">
          <section v-if="!currentSession.messages.length && !loading" class="assistant-empty">
            <div class="empty-mark">
              <img :src="doubaoAvatar" alt="豆包助手" />
            </div>
            <h3>{{ activeMode.emptyTitle }}</h3>
            <p>
              我会优先依据系统知识库、当前角色和页面上下文回答；通用问题可直接回答，实时检索类问题会说明边界，不替代诊断、处方或质控结论。
            </p>
            <div class="prompt-suggestions">
              <button v-for="question in recommendedPrompts" :key="question" type="button" @click="usePrompt(question)">
                {{ question }}
              </button>
            </div>
          </section>

          <article
            v-for="message in currentSession.messages"
            :key="message.id"
            class="message-row"
            :class="[`is-${message.role}`, { error: message.error }]"
          >
            <div class="message-avatar" :class="{ 'is-user': message.role === 'user' }">
              <span v-if="message.role === 'user'">我</span>
              <img v-else :src="doubaoAvatar" alt="豆包助手" />
            </div>
            <div class="message-card">
              <div class="message-meta">
                <span>{{ message.role === "user" ? "我" : activeMode.label }}</span>
                <small>{{ message.time }}</small>
              </div>
              <div v-if="message.role === 'assistant'" class="message-markdown" v-html="renderMarkdown(message.content)"></div>
              <p v-else>{{ message.content }}</p>
              <div v-if="message.attachments?.length" class="message-attachments">
                <div v-for="file in message.attachments" :key="file.id" class="attachment-chip">
                  <img v-if="file.dataUrl" :src="file.dataUrl" :alt="file.name" />
                  <div>
                    <strong>{{ file.name }}</strong>
                    <small>{{ formatFileSize(file.size) }}</small>
                  </div>
                </div>
              </div>
              <div v-if="message.role === 'assistant' && !message.error" class="message-actions">
                <el-button link type="primary" @click="copyMessage(message.content)">复制回答</el-button>
                <el-button link type="primary" :loading="speakingMessageId === message.id" @click="toggleSpeech(message)">
                  {{ speakingMessageId === message.id ? "停止朗读" : "朗读回答" }}
                </el-button>
                <el-button link :disabled="loading" @click="retry">重新生成</el-button>
              </div>
            </div>
          </article>

          <article v-if="loading" class="message-row is-assistant">
            <div class="message-avatar">
              <img :src="doubaoAvatar" alt="豆包助手" />
            </div>
            <div class="message-card loading-card">
              <div class="message-meta">
                <span>{{ activeMode.label }}</span>
                <small>正在生成</small>
              </div>
              <div class="typing-dot"><i></i><i></i><i></i></div>
            </div>
          </article>
        </div>

        <footer class="assistant-composer-shell">
          <section v-if="currentSession.attachments.length" class="composer-files">
            <div v-for="file in currentSession.attachments" :key="file.id" class="composer-file">
              <img v-if="file.dataUrl" :src="file.dataUrl" :alt="file.name" />
              <div>
                <strong>{{ file.name }}</strong>
                <small>{{ file.type || "图片" }} · {{ formatFileSize(file.size) }}</small>
              </div>
              <el-button text circle aria-label="移除图片" @click="removeAttachment(file.id)">
                <el-icon><Close /></el-icon>
              </el-button>
            </div>
          </section>

          <section class="assistant-composer">
            <input ref="fileInputRef" type="file" accept="image/*" multiple hidden @change="handleFileChange" />
            <el-dropdown trigger="click" placement="top-start" @command="handleMaterialCommand">
              <el-button circle class="material-button" aria-label="添加材料">
                <el-icon><Plus /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="upload">上传图片</el-dropdown-item>
                  <el-dropdown-item command="context" :disabled="!hasContext">
                    {{ includeContext ? "关闭当前页面上下文" : "带入当前页面上下文" }}
                  </el-dropdown-item>
                  <el-dropdown-item command="clearMaterials" :disabled="!currentSession.attachments.length && !includeContext">
                    清除材料
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>

            <el-input
              v-model="currentSession.promptText"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 6 }"
              maxlength="1600"
              resize="none"
              placeholder="输入问题，Ctrl + Enter 发送。可以问院内流程、系统操作、病历缺项、质控建议或管理风险。"
              @keydown.ctrl.enter.prevent="submit"
            />
            <el-button
              type="primary"
              class="send-button"
              :icon="Promotion"
              :loading="loading"
              :disabled="!canSend"
              @click="submit"
            >
              发送
            </el-button>
          </section>

          <div class="composer-meta">
            <span>AI 仅供院内辅助，不替代医生诊断、处方、质控结论或正式病历。</span>
            <div>
              <el-button
                link
                :disabled="!currentSession.messages.length && !currentSession.promptText && !currentSession.attachments.length"
                @click="clearConversation"
              >
                清空会话
              </el-button>
              <span v-if="currentSession.lastMeta">{{ currentSession.lastMeta }}</span>
            </div>
          </div>
        </footer>
      </main>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import {
  ChatDotRound,
  CirclePlus,
  Close,
  Collection,
  DataAnalysis,
  FirstAidKit,
  Link,
  Picture,
  Plus,
  Promotion,
  UserFilled
} from "@element-plus/icons-vue";
import {
  askAiAssistantApi,
  getAiAssistantTemplatesApi,
  speakAiSummaryApi,
  type AiAssistantAttachment,
  type AiAssistantMessage,
  type AiAssistantType,
  type AiPromptTemplateCandidate
} from "@/api/modules/clinic";
import doubaoAvatar from "@/assets/images/doubao.webp";

interface LocalAttachment extends AiAssistantAttachment {
  id: string;
}

interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  time: string;
  error?: boolean;
  attachments?: LocalAttachment[];
}

interface ChatSession {
  messages: ChatMessage[];
  attachments: LocalAttachment[];
  promptText: string;
  lastUserPrompt: string;
  lastMeta: string;
  knowledgeSources: string[];
}

const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    assistantType: AiAssistantType;
    title?: string;
    description?: string;
    defaultPrompt?: string;
    patientId?: string;
    context?: Record<string, unknown>;
    attachmentIds?: string[];
  }>(),
  {
    title: "",
    description: "",
    defaultPrompt: "",
    patientId: "",
    context: () => ({}),
    attachmentIds: () => []
  }
);

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
}>();

const assistantModes: Array<{
  value: AiAssistantType;
  label: string;
  short: string;
  description: string;
  emptyTitle: string;
  icon: unknown;
  fallbackPrompts: string[];
}> = [
  {
    value: "public",
    label: "公共助手",
    short: "制度流程",
    description: "回答院内流程、岗位协作、系统使用和日常医疗场景问题。",
    emptyTitle: "今天想了解哪个院内流程？",
    icon: ChatDotRound,
    fallbackPrompts: ["检查室需要填写诊断吗？", "前台建档后下一步由谁处理？", "质控退回后应该怎么整改？"]
  },
  {
    value: "patient",
    label: "患者助手",
    short: "病历资料",
    description: "辅助梳理患者资料、缺失项、附件摘要和宣教草稿。",
    emptyTitle: "可以围绕当前患者继续追问",
    icon: UserFilled,
    fallbackPrompts: ["这个患者还缺哪些关键资料？", "帮我整理一份宣教沟通提纲。", "附件和检查记录有什么需要补齐？"]
  },
  {
    value: "quality",
    label: "质控助手",
    short: "缺项冲突",
    description: "辅助发现缺项、逻辑冲突，并生成退回原因草稿。",
    emptyTitle: "把质控问题提前说清楚",
    icon: FirstAidKit,
    fallbackPrompts: ["为什么这份档案不能归档？", "退回原因怎么写更清楚？", "哪些字段最容易导致反复退回？"]
  },
  {
    value: "leader",
    label: "管理助手",
    short: "风险闭环",
    description: "汇总待办风险、科室异常、闭环进度和管理建议。",
    emptyTitle: "从风险和闭环开始看",
    icon: DataAnalysis,
    fallbackPrompts: ["今天管理层最应该看什么？", "哪些事项可能超过闭环时限？", "如何把高频 AI 问题沉淀成模板？"]
  }
];

const createSession = (): ChatSession => ({
  messages: [],
  attachments: [],
  promptText: "",
  lastUserPrompt: "",
  lastMeta: "",
  knowledgeSources: []
});

const sessions = reactive<Record<AiAssistantType, ChatSession>>({
  public: createSession(),
  patient: createSession(),
  quality: createSession(),
  leader: createSession()
});

const activeType = ref<AiAssistantType>(props.assistantType);
const loading = ref(false);
const speakingMessageId = ref("");
const includeContext = ref(true);
const templates = ref<AiPromptTemplateCandidate[]>([]);
const fileInputRef = ref<HTMLInputElement>();
const messageListRef = ref<HTMLElement>();
let speechAudio: HTMLAudioElement | undefined;
let speechAudioUrl = "";
let speechStoppedManually = false;

const currentSession = computed(() => sessions[activeType.value]);
const activeMode = computed(() => assistantModes.find(item => item.value === activeType.value) || assistantModes[0]);
const panelTitle = computed(() => props.title || activeMode.value.label);
const canSend = computed(() => Boolean(currentSession.value.promptText.trim()) && !loading.value);
const hasContext = computed(
  () => Object.keys(props.context || {}).length > 0 || Boolean(props.patientId) || props.attachmentIds.length > 0
);
const shouldSendPatientContext = computed(() => includeContext.value && ["patient", "quality"].includes(activeType.value));
const materialCount = computed(
  () => 1 + (includeContext.value && hasContext.value ? 1 : 0) + currentSession.value.attachments.length
);

const knowledgeHint = computed(() => {
  const sources = currentSession.value.knowledgeSources;
  if (!sources.length) return "已带入系统知识库";
  return `知识来源：${sources.slice(0, 3).join("、")}${sources.length > 3 ? ` 等 ${sources.length} 条` : ""}`;
});

const contextHint = computed(() => {
  if (!hasContext.value) return "当前入口暂无额外页面资料";
  const parts: string[] = [];
  if (props.patientId && shouldSendPatientContext.value) parts.push("患者摘要");
  if (Object.keys(props.context || {}).length) parts.push("页面摘要");
  if (props.attachmentIds.length && shouldSendPatientContext.value) parts.push(`${props.attachmentIds.length} 份附件索引`);
  return parts.join("、") || "页面上下文";
});

const recommendedPrompts = computed(() => {
  const matched = templates.value
    .filter(item => item.assistantType === activeType.value || item.assistantType === "public")
    .map(item => item.recommendedPrompt)
    .filter(Boolean)
    .slice(0, 4);
  return matched.length ? matched : activeMode.value.fallbackPrompts;
});

const recentSessions = computed(() =>
  assistantModes.map(item => {
    const session = sessions[item.value];
    const lastMessage = [...session.messages].reverse().find(message => message.role === "user");
    return {
      type: item.value,
      title: item.label,
      last: lastMessage?.content.slice(0, 24)
    };
  })
);

const seedDefaultPrompt = () => {
  const session = currentSession.value;
  if (props.defaultPrompt && !session.promptText && !session.messages.length) {
    session.promptText = props.defaultPrompt;
  }
};

watch(
  () => props.assistantType,
  value => {
    activeType.value = value;
    seedDefaultPrompt();
  }
);

watch(
  () => props.defaultPrompt,
  () => seedDefaultPrompt(),
  { immediate: true }
);

watch(
  () => props.modelValue,
  value => {
    if (value) {
      seedDefaultPrompt();
      scrollToBottom();
    } else {
      stopSpeech();
    }
  }
);

const switchType = (type: AiAssistantType) => {
  activeType.value = type;
  seedDefaultPrompt();
  scrollToBottom();
};

const nowTime = () =>
  new Date().toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit"
  });

const createId = () => `${Date.now()}-${Math.random().toString(16).slice(2)}`;

const scrollToBottom = async () => {
  await nextTick();
  const el = messageListRef.value;
  if (el) el.scrollTop = el.scrollHeight;
};

const buildRecentMessages = (): AiAssistantMessage[] =>
  currentSession.value.messages
    .filter(item => !item.error)
    .slice(-8)
    .map(item => ({
      role: item.role,
      content: item.content
    }));

const toRequestAttachment = (file: LocalAttachment): AiAssistantAttachment => ({
  name: file.name,
  type: file.type,
  size: file.size,
  dataUrl: file.dataUrl,
  source: file.source || "upload"
});

const submit = async () => {
  const session = currentSession.value;
  const text = session.promptText.trim();
  if (!text || loading.value) return;

  const sendingType = activeType.value;
  const historyMessages = buildRecentMessages();
  const currentAttachments = session.attachments.map(file => ({ ...file }));
  session.messages.push({
    id: createId(),
    role: "user",
    content: text,
    time: nowTime(),
    attachments: currentAttachments
  });
  session.lastUserPrompt = text;
  session.promptText = "";
  session.attachments = [];
  loading.value = true;
  session.lastMeta = "";
  await scrollToBottom();

  try {
    const { data } = await askAiAssistantApi({
      assistantType: sendingType,
      prompt: text,
      messages: historyMessages,
      patientId: shouldSendPatientContext.value ? props.patientId : "",
      context: includeContext.value ? props.context : {},
      attachmentIds: shouldSendPatientContext.value ? props.attachmentIds : [],
      attachments: currentAttachments.map(toRequestAttachment)
    });
    const targetSession = sessions[sendingType];
    targetSession.messages.push({
      id: createId(),
      role: "assistant",
      content: data.answer || "豆包助手暂时没有生成有效内容。",
      time: nowTime()
    });
    targetSession.knowledgeSources = data.knowledgeSources || [];
    targetSession.lastMeta = `${data.model || "豆包模型"} · ${data.generatedAt || "刚刚"}`;
  } catch (error) {
    const message = error instanceof Error ? error.message : "豆包助手调用失败，请检查配置或稍后重试。";
    sessions[sendingType].messages.push({
      id: createId(),
      role: "assistant",
      content: message,
      time: nowTime(),
      error: true
    });
    ElMessage.error(message);
  } finally {
    loading.value = false;
    await scrollToBottom();
  }
};

const retry = () => {
  const session = currentSession.value;
  if (!session.lastUserPrompt || loading.value) return;
  session.promptText = session.lastUserPrompt;
  submit();
};

const clearConversation = () => {
  const session = currentSession.value;
  session.messages = [];
  session.attachments = [];
  session.promptText = props.defaultPrompt || "";
  session.lastUserPrompt = "";
  session.lastMeta = "";
  session.knowledgeSources = [];
};

const newConversation = () => {
  clearConversation();
  ElMessage.success("已开启新的会话");
};

const usePrompt = (prompt: string) => {
  currentSession.value.promptText = prompt;
};

const copyMessage = async (content: string) => {
  try {
    await navigator.clipboard.writeText(content);
    ElMessage.success("已复制回答");
  } catch {
    ElMessage.warning("复制失败，请手动选择文本复制");
  }
};

const escapeHtml = (value: string) =>
  value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");

const renderInlineMarkdown = (value: string) =>
  escapeHtml(value)
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/__([^_]+)__/g, "<strong>$1</strong>")
    .replace(/\*([^*\n]+)\*/g, "<em>$1</em>");

const renderMarkdown = (content: string) => {
  const lines = content.replace(/\r\n/g, "\n").split("\n");
  const html: string[] = [];
  let inList = false;
  const closeList = () => {
    if (inList) {
      html.push("</ul>");
      inList = false;
    }
  };

  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line) {
      closeList();
      continue;
    }
    const heading = line.match(/^(#{1,3})\s+(.+)$/);
    if (heading) {
      closeList();
      const level = Math.min(heading[1].length + 3, 6);
      html.push(`<h${level}>${renderInlineMarkdown(heading[2])}</h${level}>`);
      continue;
    }
    const listItem = line.match(/^[-*]\s+(.+)$/) || line.match(/^\d+\.\s+(.+)$/);
    if (listItem) {
      if (!inList) {
        html.push("<ul>");
        inList = true;
      }
      html.push(`<li>${renderInlineMarkdown(listItem[1])}</li>`);
      continue;
    }
    closeList();
    html.push(`<p>${renderInlineMarkdown(line)}</p>`);
  }
  closeList();
  return html.join("");
};

const stripMarkdownForSpeech = (content: string) =>
  content
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/`([^`]+)`/g, "$1")
    .replace(/!\[[^\]]*]\([^)]*\)/g, " ")
    .replace(/\[([^\]]+)]\([^)]*\)/g, "$1")
    .replace(/^#{1,6}\s+/gm, "")
    .replace(/^\s*[-*+]\s+/gm, "")
    .replace(/^\s*\d+\.\s+/gm, "")
    .replace(/[*_~>#|]/g, "")
    .replace(/\r?\n+/g, "。")
    .replace(/\s+/g, " ")
    .trim();

const base64ToBlob = (base64: string, mimeType: string) => {
  const binary = window.atob(base64);
  const chunks: ArrayBuffer[] = [];
  for (let offset = 0; offset < binary.length; offset += 1024) {
    const slice = binary.slice(offset, offset + 1024);
    const bytes = new Uint8Array(slice.length);
    for (let index = 0; index < slice.length; index += 1) {
      bytes[index] = slice.charCodeAt(index);
    }
    chunks.push(bytes.buffer);
  }
  return new Blob(chunks, { type: mimeType || "audio/mpeg" });
};

const releaseSpeechAudio = () => {
  if (speechAudioUrl) {
    URL.revokeObjectURL(speechAudioUrl);
    speechAudioUrl = "";
  }
  speechAudio = undefined;
  speakingMessageId.value = "";
};

const stopSpeech = () => {
  speechStoppedManually = true;
  if (speechAudio) {
    speechAudio.onended = null;
    speechAudio.onerror = null;
    speechAudio.pause();
  }
  releaseSpeechAudio();
};

const toggleSpeech = async (message: ChatMessage) => {
  if (speakingMessageId.value === message.id) {
    stopSpeech();
    return;
  }
  const text = stripMarkdownForSpeech(message.content).slice(0, 1800);
  if (!text) {
    ElMessage.warning("暂无可朗读的回答内容");
    return;
  }
  stopSpeech();
  speechStoppedManually = false;
  speakingMessageId.value = message.id;
  try {
    const { data } = await speakAiSummaryApi({ text });
    const blob = base64ToBlob(data.audioBase64, data.mimeType);
    speechAudioUrl = URL.createObjectURL(blob);
    speechAudio = new Audio(speechAudioUrl);
    speechAudio.onended = releaseSpeechAudio;
    speechAudio.onerror = () => {
      releaseSpeechAudio();
      if (!speechStoppedManually) {
        ElMessage.error("语音播放失败，请检查浏览器音频权限或 TTS 配置");
      }
    };
    await speechAudio.play();
  } catch (error) {
    stopSpeech();
    ElMessage.error(error instanceof Error ? error.message : "豆包语音朗读失败");
  }
};

const toggleContext = () => {
  if (!hasContext.value) return;
  includeContext.value = !includeContext.value;
};

const clearMaterials = () => {
  currentSession.value.attachments = [];
  includeContext.value = false;
};

const handleMaterialCommand = (command: string | number | object) => {
  if (command === "upload") {
    fileInputRef.value?.click();
    return;
  }
  if (command === "context") {
    toggleContext();
    return;
  }
  if (command === "clearMaterials") {
    clearMaterials();
  }
};

const handleFileChange = async (event: Event) => {
  const target = event.target as HTMLInputElement;
  const files = Array.from(target.files || []);
  if (!files.length) return;
  const room = Math.max(0, 4 - currentSession.value.attachments.length);
  const imageFiles = files.filter(file => file.type.startsWith("image/")).slice(0, room);
  if (imageFiles.length < files.length) {
    ElMessage.warning("本轮最多上传 4 张图片，非图片文件已忽略");
  }

  const loaded = await Promise.all(imageFiles.map(file => readImageFile(file)));
  currentSession.value.attachments.push(...loaded);
  target.value = "";
};

const readImageFile = (file: File) =>
  new Promise<LocalAttachment>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () =>
      resolve({
        id: createId(),
        name: file.name,
        type: file.type,
        size: file.size,
        dataUrl: String(reader.result || ""),
        source: "upload"
      });
    reader.onerror = () => reject(new Error(`读取图片失败：${file.name}`));
    reader.readAsDataURL(file);
  });

const removeAttachment = (id: string) => {
  currentSession.value.attachments = currentSession.value.attachments.filter(file => file.id !== id);
};

const formatFileSize = (size?: number) => {
  const value = Number(size || 0);
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
};

const loadTemplates = async () => {
  try {
    const { data } = await getAiAssistantTemplatesApi();
    templates.value = data.list || [];
  } catch {
    templates.value = [];
  }
};

onMounted(loadTemplates);

onBeforeUnmount(stopSpeech);
</script>

<style scoped lang="scss">
:global(.ai-assistant-workbench-drawer .el-drawer__body) {
  padding: 0;
  overflow: hidden;
  background: var(--hos-page-bg, #f7fbf8);
}

.assistant-workbench {
  display: grid;
  grid-template-columns: 276px minmax(0, 1fr);
  height: 100%;
  min-height: 0;
  color: var(--hos-text-main, #203529);
  background: linear-gradient(135deg, rgb(255 255 255 / 0.94), rgb(241 250 245 / 0.86)), var(--hos-page-bg, #f7fbf8);
}

.assistant-sidebar {
  display: grid;
  grid-template-rows: auto auto auto 1fr;
  gap: 16px;
  min-height: 0;
  padding: 18px;
  border-right: 1px solid var(--hos-line, #dfe9e2);
  background: rgb(255 255 255 / 0.72);
  backdrop-filter: blur(18px);
}

.assistant-brand {
  display: flex;
  gap: 12px;
  align-items: center;

  strong,
  span {
    display: block;
  }

  strong {
    font-size: 16px;
  }

  span {
    margin-top: 2px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.assistant-avatar,
.message-avatar,
.empty-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  overflow: hidden;
  background: rgb(237 248 242 / 0.95);
  box-shadow: 0 0 0 6px rgb(77 123 99 / 0.1);

  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.assistant-avatar {
  width: 42px;
  height: 42px;
  border-radius: 14px;
  font-weight: 800;
}

.new-chat-button {
  width: 100%;
}

.mode-list,
.recent-list {
  display: grid;
  gap: 8px;
}

.mode-list button,
.recent-list button {
  width: 100%;
  border: 1px solid transparent;
  border-radius: 8px;
  color: var(--hos-text-main, #203529);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition:
    background 180ms ease,
    border-color 180ms ease,
    transform 180ms var(--liquid-ease, ease);
}

.mode-list button {
  display: grid;
  grid-template-columns: 24px 1fr;
  gap: 2px 10px;
  padding: 12px;

  .el-icon {
    grid-row: span 2;
    align-self: center;
    color: var(--hos-primary-deep, #4d7b63);
  }

  span {
    font-weight: 700;
  }

  small {
    color: var(--el-text-color-secondary);
  }

  &.active,
  &:hover {
    border-color: rgb(77 123 99 / 0.2);
    background: rgb(232 247 239 / 0.78);
  }

  &:hover {
    transform: translateY(-1px);
  }
}

.sidebar-block {
  display: grid;
  gap: 10px;
  min-height: 0;
}

.block-title {
  display: flex;
  justify-content: space-between;
  color: var(--el-text-color-secondary);
  font-size: 12px;

  strong {
    color: var(--hos-text-main, #203529);
  }
}

.recent-list {
  overflow: auto;

  button {
    padding: 10px;
    background: rgb(255 255 255 / 0.62);

    span,
    small {
      display: block;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    small {
      margin-top: 3px;
      color: var(--el-text-color-secondary);
    }

    &:hover {
      border-color: rgb(77 123 99 / 0.2);
      background: #fff;
    }
  }
}

.material-status {
  align-self: end;
}

.status-chip {
  padding: 8px 10px;
  border: 1px solid var(--hos-line, #dfe9e2);
  border-radius: 999px;
  color: var(--el-text-color-secondary);
  background: rgb(255 255 255 / 0.7);
  font-size: 12px;

  &.active {
    border-color: rgb(77 123 99 / 0.24);
    color: var(--hos-primary-deep, #4d7b63);
    background: rgb(232 247 239 / 0.84);
  }
}

.assistant-main {
  display: grid;
  grid-template-rows: auto auto 1fr auto;
  min-width: 0;
  min-height: 0;
}

.assistant-topbar {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  padding: 20px 24px 14px;
  border-bottom: 1px solid var(--hos-line, #dfe9e2);
  background: rgb(255 255 255 / 0.74);

  h2 {
    margin: 4px 0 6px;
    font-size: 22px;
    line-height: 1.25;
  }

  p {
    margin: 0;
    color: var(--el-text-color-secondary);
    line-height: 1.6;
  }
}

.eyebrow {
  color: var(--hos-primary-deep, #4d7b63);
  font-size: 12px;
  font-weight: 800;
}

.topbar-actions {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.context-strip {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 12px 24px;
  border-bottom: 1px solid var(--hos-line, #dfe9e2);
  background: rgb(250 253 251 / 0.82);
}

.context-chip {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  max-width: 360px;
  padding: 7px 10px;
  border: 1px solid var(--hos-line, #dfe9e2);
  border-radius: 999px;
  color: var(--el-text-color-secondary);
  background: #fff;
  white-space: nowrap;

  &.active {
    border-color: rgb(77 123 99 / 0.24);
    color: var(--hos-primary-deep, #4d7b63);
    background: rgb(232 247 239 / 0.84);
  }
}

.message-area {
  overflow: auto;
  min-height: 0;
  padding: 24px clamp(18px, 4vw, 54px);
}

.assistant-empty {
  display: grid;
  justify-items: center;
  max-width: 720px;
  margin: 52px auto;
  text-align: center;

  h3 {
    margin: 18px 0 8px;
    font-size: 24px;
  }

  p {
    max-width: 58ch;
    margin: 0;
    color: var(--el-text-color-secondary);
    line-height: 1.7;
  }
}

.empty-mark {
  width: 58px;
  height: 58px;
  border-radius: 18px;
  font-weight: 900;
}

.prompt-suggestions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-top: 24px;

  button {
    padding: 10px 14px;
    border: 1px solid var(--hos-line, #dfe9e2);
    border-radius: 999px;
    color: var(--hos-text-main, #203529);
    background: rgb(255 255 255 / 0.88);
    cursor: pointer;

    &:hover {
      border-color: rgb(77 123 99 / 0.28);
      color: var(--hos-primary-deep, #4d7b63);
    }
  }
}

.message-row {
  display: flex;
  gap: 12px;
  max-width: 820px;
  margin: 0 auto 18px;

  &.is-user {
    flex-direction: row-reverse;

    .message-card {
      border-color: rgb(77 123 99 / 0.16);
      background: rgb(232 247 239 / 0.9);
    }
  }

  &.error .message-card {
    border-color: rgb(220 80 80 / 0.28);
    background: rgb(255 244 244 / 0.92);
  }
}

.message-avatar {
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  border-radius: 12px;
  font-size: 13px;
  font-weight: 800;

  &.is-user {
    background: var(--hos-primary-deep, #4d7b63);
  }
}

.message-card {
  min-width: 0;
  max-width: min(720px, 100%);
  padding: 13px 15px;
  border: 1px solid var(--hos-line, #dfe9e2);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.92);
  box-shadow: 0 12px 28px rgb(45 76 59 / 0.07);

  p {
    margin: 8px 0 0;
    color: var(--el-text-color-regular);
    line-height: 1.75;
    white-space: pre-wrap;
  }
}

.message-markdown {
  margin-top: 8px;
  color: var(--el-text-color-regular);
  line-height: 1.75;
  word-break: break-word;

  :deep(p) {
    margin: 0 0 8px;
  }

  :deep(p:last-child),
  :deep(ul:last-child) {
    margin-bottom: 0;
  }

  :deep(h4),
  :deep(h5),
  :deep(h6) {
    margin: 10px 0 6px;
    color: var(--hos-text-main, #203529);
    font-size: 14px;
    font-weight: 800;
  }

  :deep(ul) {
    margin: 4px 0 10px;
    padding-left: 18px;
  }

  :deep(li) {
    margin: 3px 0;
  }

  :deep(strong) {
    color: var(--hos-text-main, #203529);
    font-weight: 800;
  }

  :deep(code) {
    padding: 1px 5px;
    color: #285b45;
    background: rgb(77 123 99 / 0.1);
    border-radius: 4px;
  }
}

.message-meta,
.message-actions {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}

.message-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;

  span {
    color: var(--hos-text-main, #203529);
    font-weight: 700;
  }
}

.message-attachments,
.composer-files {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.message-attachments {
  margin-top: 10px;
}

.attachment-chip,
.composer-file {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 180px;
  padding: 8px;
  border: 1px solid var(--hos-line, #dfe9e2);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.78);

  img {
    width: 42px;
    height: 42px;
    border-radius: 6px;
    object-fit: cover;
  }

  strong,
  small {
    display: block;
    overflow: hidden;
    max-width: 170px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--el-text-color-secondary);
  }
}

.typing-dot {
  display: flex;
  gap: 5px;
  padding: 10px 0 2px;

  i {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--hos-primary-deep, #4d7b63);
    animation: typing-bounce 1s infinite ease-in-out;

    &:nth-child(2) {
      animation-delay: 0.12s;
    }

    &:nth-child(3) {
      animation-delay: 0.24s;
    }
  }
}

.assistant-composer-shell {
  padding: 12px 24px 18px;
  border-top: 1px solid var(--hos-line, #dfe9e2);
  background: rgb(255 255 255 / 0.78);
}

.composer-files {
  margin-bottom: 10px;
}

.assistant-composer {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 10px;
  align-items: end;
  padding: 10px;
  border: 1px solid rgb(77 123 99 / 0.2);
  border-radius: 8px;
  background: #fff;
  box-shadow:
    0 18px 42px rgb(45 76 59 / 0.12),
    inset 0 1px 0 rgb(255 255 255 / 0.9);

  :deep(.el-textarea__inner) {
    min-height: 48px !important;
    border: 0;
    box-shadow: none;
    font-size: 15px;
  }
}

.material-button {
  margin-bottom: 4px;
}

.send-button {
  min-width: 88px;
  margin-bottom: 4px;
}

.composer-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

@keyframes typing-bounce {
  0%,
  80%,
  100% {
    transform: translateY(0);
    opacity: 0.45;
  }

  40% {
    transform: translateY(-4px);
    opacity: 1;
  }
}

@media (max-width: 860px) {
  .assistant-workbench {
    grid-template-columns: 1fr;
  }

  .assistant-sidebar {
    display: none;
  }

  .assistant-topbar,
  .context-strip,
  .assistant-composer-shell {
    padding-right: 16px;
    padding-left: 16px;
  }

  .assistant-composer {
    grid-template-columns: auto 1fr;

    .send-button {
      grid-column: 1 / -1;
      width: 100%;
    }
  }

  .composer-meta {
    flex-direction: column;
  }
}

@media (prefers-reduced-motion: reduce) {
  .mode-list button,
  .typing-dot i {
    transition: none;
    animation: none;
  }
}
</style>
