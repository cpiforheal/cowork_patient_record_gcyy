<template>
  <div ref="messageListRef" class="message-area">
    <section v-if="!messages.length && !loading" class="assistant-empty">
      <div class="empty-mark">
        <img :src="avatar" alt="豆包助手" />
      </div>
      <h3>{{ emptyTitle }}</h3>
      <p>
        我会优先依据系统知识库、当前角色和页面上下文回答；通用问题可直接回答，实时检索类问题会说明边界，不替代诊断、处方或质控结论。
      </p>
      <div class="prompt-suggestions">
        <button v-for="question in recommendedPrompts" :key="question" type="button" @click="$emit('usePrompt', question)">
          {{ question }}
        </button>
      </div>
    </section>

    <article
      v-for="message in messages"
      :key="message.id"
      class="message-row"
      :class="[`is-${message.role}`, { error: message.error }]"
    >
      <div class="message-avatar" :class="{ 'is-user': message.role === 'user' }">
        <span v-if="message.role === 'user'">我</span>
        <img v-else :src="avatar" alt="豆包助手" />
      </div>
      <div class="message-card">
        <div class="message-meta">
          <span>{{ message.role === "user" ? "我" : assistantLabel }}</span>
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
          <el-button link type="primary" @click="$emit('copy', message.content)">复制回答</el-button>
          <el-button link type="primary" :loading="speakingMessageId === message.id" @click="$emit('toggleSpeech', message)">
            {{ speakingMessageId === message.id ? "停止朗读" : "朗读回答" }}
          </el-button>
          <el-button link :disabled="loading" @click="$emit('retry')">重新生成</el-button>
        </div>
      </div>
    </article>

    <article v-if="loading" class="message-row is-assistant">
      <div class="message-avatar">
        <img :src="avatar" alt="豆包助手" />
      </div>
      <div class="message-card loading-card">
        <div class="message-meta">
          <span>{{ assistantLabel }}</span>
          <small>正在生成</small>
        </div>
        <div class="typing-dot"><i></i><i></i><i></i></div>
      </div>
    </article>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref } from "vue";

type LocalAttachment = {
  id: string;
  name: string;
  type?: string;
  size?: number;
  dataUrl?: string;
  source?: string;
};

type ChatMessage = {
  id: string;
  role: "user" | "assistant";
  content: string;
  time: string;
  error?: boolean;
  attachments?: LocalAttachment[];
};

defineProps<{
  messages: ChatMessage[];
  loading: boolean;
  avatar: string;
  assistantLabel: string;
  emptyTitle: string;
  recommendedPrompts: string[];
  speakingMessageId: string;
  renderMarkdown: (content: string) => string;
  formatFileSize: (size?: number) => string;
}>();

defineEmits<{
  usePrompt: [prompt: string];
  copy: [content: string];
  toggleSpeech: [message: ChatMessage];
  retry: [];
}>();

const messageListRef = ref<HTMLElement>();

defineExpose({
  scrollToBottom: async () => {
    await nextTick();
    const el = messageListRef.value;
    if (el) el.scrollTop = el.scrollHeight;
  }
});
</script>

<style scoped lang="scss">
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

.empty-mark,
.message-avatar {
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

.message-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 10px;
}

.attachment-chip {
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

@media (prefers-reduced-motion: reduce) {
  .typing-dot i {
    animation: none;
  }
}
</style>
