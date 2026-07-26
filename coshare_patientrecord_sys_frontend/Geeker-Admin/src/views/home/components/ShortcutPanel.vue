<template>
  <div class="shortcut-panel">
    <div class="panel-head">
      <div>
        <h2>常用入口</h2>
        <p>只显示当前岗位已授权的高频动作。</p>
      </div>
    </div>
    <div class="shortcut-list">
      <button v-for="item in quickEntries" :key="item.path" @click="$emit('navigate', item.path)">
        <el-icon><component :is="item.icon" /></el-icon>
        <span>{{ item.title }}</span>
        <small>{{ item.desc }}</small>
      </button>
      <el-empty v-if="!quickEntries.length" description="暂无可用入口" :image-size="52" />
    </div>

    <div v-if="reminders.length" class="reminder-section">
      <div class="panel-head compact">
        <div>
          <h2>岗位提醒</h2>
          <p>与你相关的待跟进事项。</p>
        </div>
      </div>
      <div class="reminder-list">
        <button
          v-for="item in reminders"
          :key="item.id"
          class="reminder-item"
          :class="`is-${item.level}`"
          @click="$emit('navigate', item.path)"
        >
          <span>{{ item.title }}</span>
          <strong>{{ item.count }}</strong>
          <small>{{ item.desc }}</small>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { WorkReminder } from "@/api/modules/clinic";

type QuickEntry = {
  title: string;
  desc: string;
  icon: string;
  path: string;
};

defineProps<{
  quickEntries: QuickEntry[];
  reminders: WorkReminder[];
}>();

defineEmits<{ navigate: [path: string] }>();
</script>

<style scoped lang="scss">
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  h2,
  p {
    margin: 0;
  }
  h2 {
    font-size: 18px;
    line-height: 1.35;
  }
  p {
    margin-top: 4px;
    color: var(--el-text-color-secondary);
  }
}
.panel-head.compact {
  margin-bottom: 10px;
  h2 {
    font-size: 16px;
  }
  p {
    font-size: 12px;
  }
}
.shortcut-list {
  display: grid;
  gap: 8px;
  button {
    display: grid;
    grid-template-columns: 28px minmax(0, 1fr);
    gap: 2px 10px;
    align-items: center;
    padding: 11px 12px;
    text-align: left;
    cursor: pointer;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;
    transition:
      background 160ms ease,
      border-color 160ms ease,
      transform 160ms ease;
    &:hover {
      background: #f8fffd;
      border-color: rgb(15 118 110 / 22%);
      transform: translateX(2px);
    }
    .el-icon {
      grid-row: span 2;
      color: var(--clinic-info, #0f766e);
      font-size: 22px;
    }
    span {
      color: var(--el-text-color-primary);
      font-weight: 600;
    }
    small {
      color: var(--el-text-color-secondary);
    }
  }
}
.reminder-section {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.reminder-list {
  display: grid;
  gap: 8px;
}
.reminder-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 3px 10px;
  padding: 10px 11px;
  text-align: left;
  cursor: pointer;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  span,
  strong,
  small {
    display: block;
  }
  span {
    color: var(--el-text-color-primary);
    font-weight: 700;
  }
  strong {
    color: var(--el-color-primary);
    font-size: 20px;
  }
  small {
    grid-column: 1 / -1;
    color: var(--el-text-color-secondary);
    line-height: 1.45;
  }
  &.is-warning {
    background: var(--clinic-warning-soft, #fef7e8);
    border-color: rgb(245 158 11 / 25%);
    strong {
      color: var(--clinic-warning, #b45309);
    }
  }
  &.is-danger {
    background: var(--clinic-danger-soft, #fdeeee);
    border-color: rgb(239 68 68 / 22%);
    strong {
      color: var(--clinic-danger, #b91c1c);
    }
  }
  &.is-success strong {
    color: var(--clinic-success, #15803d);
  }
}
</style>
