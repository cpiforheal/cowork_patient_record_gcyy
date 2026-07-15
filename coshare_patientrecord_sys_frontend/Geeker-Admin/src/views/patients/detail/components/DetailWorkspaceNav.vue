<template>
  <aside class="detail-side-nav" aria-label="患者详情模块">
    <button type="button" :class="{ active: modelValue === 'archive' }" @click="selectMode('archive')">
      <strong>健康管理总览</strong>
      <span>状态、变化与随访计划</span>
    </button>

    <button type="button" :class="{ active: modelValue === 'flow' }" @click="selectMode('flow')">
      <strong>岗位工作任务</strong>
      <span>待办、风险与协作处理</span>
    </button>

    <button type="button" :class="{ active: modelValue === 'attachments' }" @click="selectMode('attachments')">
      <strong>检查与附件</strong>
      <span>{{ attachmentCount }} 份资料</span>
    </button>

    <button type="button" :class="{ active: modelValue === 'timeline' }" @click="selectMode('timeline')">
      <strong>时间轴</strong>
      <span>{{ timelineCount }} 条追溯记录</span>
    </button>
  </aside>
</template>

<script setup lang="ts">
export type DetailWorkspaceMode = "flow" | "archive" | "attachments" | "timeline";

interface Props {
  modelValue: DetailWorkspaceMode;
  attachmentCount: number;
  timelineCount: number;
}

withDefaults(defineProps<Props>(), {
  attachmentCount: 0,
  timelineCount: 0
});

const emit = defineEmits<{
  change: [mode: DetailWorkspaceMode];
}>();

const selectMode = (mode: DetailWorkspaceMode) => emit("change", mode);
</script>

<style scoped lang="scss">
.detail-side-nav {
  position: sticky;
  top: 68px;
  z-index: 8;
  display: grid;
  gap: 8px;
  padding: 10px;
  background: var(--hos-panel);
  border: 1px solid var(--hos-border);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-soft);

  button {
    display: grid;
    gap: 4px;
    min-width: 0;
    padding: 12px;
    text-align: left;
    cursor: pointer;
    background: var(--hos-glass);
    border: 1px solid var(--hos-border-light);
    border-radius: var(--hos-radius-md);
    transition:
      background-color 0.18s ease,
      border-color 0.18s ease,
      box-shadow 0.18s ease,
      color 0.18s ease,
      transform 0.18s ease;

    &:hover {
      border-color: var(--hos-border-interactive);
      box-shadow: var(--hos-shadow-card-hover);
      transform: translateY(-1px);
    }

    &:active {
      transform: translateY(0);
    }

    strong {
      color: var(--hos-text-primary);
      font-size: 14px;
      line-height: 1.35;
    }

    span {
      overflow: hidden;
      color: var(--hos-text-secondary);
      font-size: 12px;
      line-height: 1.45;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &.active {
      background: var(--hos-primary-soft);
      border-color: var(--hos-border-interactive);
      box-shadow:
        inset 3px 0 0 var(--hos-primary),
        var(--hos-shadow-card);

      strong {
        color: var(--hos-primary-deep);
      }
    }
  }
}

@media (max-width: 1180px) {
  .detail-side-nav {
    position: static;
  }
}
</style>
