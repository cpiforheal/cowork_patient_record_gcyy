<template>
  <aside class="assistant-sidebar">
    <header class="assistant-brand">
      <div class="assistant-avatar">
        <img :src="avatar" alt="豆包助手" />
      </div>
      <div>
        <strong>豆包院内助手</strong>
        <span>公共问答 · 多模态材料</span>
      </div>
    </header>

    <el-button class="new-chat-button" type="primary" plain :icon="CirclePlus" @click="$emit('newConversation')">
      新建会话
    </el-button>

    <nav class="mode-list" aria-label="助手模式">
      <button
        v-for="item in modes"
        :key="item.value"
        type="button"
        :class="{ active: activeType === item.value }"
        @click="$emit('switchType', item.value)"
      >
        <el-icon><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
        <small>{{ item.short }}</small>
      </button>
    </nav>

    <section class="sidebar-block">
      <div class="block-title">
        <strong>最近会话</strong>
        <span>{{ currentMessageCount }} 条</span>
      </div>
      <div class="recent-list">
        <button v-for="item in recentSessions" :key="item.type" type="button" @click="$emit('switchType', item.type)">
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
      <div v-if="showPatientContext" class="status-chip active">患者摘要仅用于本次问答</div>
      <div v-if="attachmentCount" class="status-chip active">{{ attachmentCount }} 张图片待发送</div>
    </section>
  </aside>
</template>

<script setup lang="ts">
import { CirclePlus } from "@element-plus/icons-vue";
import type { AiAssistantType } from "@/api/modules/clinic";

type AssistantMode = {
  value: AiAssistantType;
  label: string;
  short: string;
  icon: unknown;
};

type RecentSession = {
  type: AiAssistantType;
  title: string;
  last?: string;
};

defineProps<{
  avatar: string;
  modes: AssistantMode[];
  activeType: AiAssistantType;
  currentMessageCount: number;
  recentSessions: RecentSession[];
  materialCount: number;
  includeContext: boolean;
  hasContext: boolean;
  showPatientContext: boolean;
  attachmentCount: number;
}>();

defineEmits<{
  newConversation: [];
  switchType: [type: AiAssistantType];
}>();
</script>

<style scoped lang="scss">
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

.assistant-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: 14px;
  color: #fff;
  overflow: hidden;
  background: rgb(237 248 242 / 0.95);
  box-shadow: 0 0 0 6px rgb(77 123 99 / 0.1);
  font-weight: 800;

  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
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

@media (max-width: 860px) {
  .assistant-sidebar {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .mode-list button {
    transition: none;
  }
}
</style>
