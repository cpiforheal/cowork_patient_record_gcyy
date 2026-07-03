<template>
  <div class="task-panel">
    <div class="panel-head">
      <div>
        <h2>我的待办</h2>
        <p>{{ roleName }}登录后只看最该处理的动作，点击直接进入对应患者、章节或审核页。</p>
      </div>
      <el-button :icon="Refresh" link @click="$emit('refresh')">刷新</el-button>
    </div>

    <div class="action-task-list">
      <button
        v-for="task in actionTasks"
        :key="task.id"
        class="action-task-card"
        :class="`is-${task.level}`"
        @click="$emit('openActionTask', task)"
      >
        <span>{{ task.roleLabel }}</span>
        <strong>{{ task.count }}</strong>
        <em>{{ task.title }}</em>
        <small>{{ task.desc }}</small>
        <b>{{ task.actionText }}</b>
      </button>
    </div>

    <el-empty v-if="!taskCards.length" description="当前岗位暂无待处理患者" />
    <div v-else class="task-list patient-task-list">
      <button v-for="task in taskCards" :key="task.id" class="task-card" @click="$emit('openTask', task)">
        <div>
          <strong>{{ task.patient.name }}</strong>
          <span>{{ task.patient.visitNo }}</span>
        </div>
        <div>
          <em>{{ task.title }}</em>
          <small>{{ task.desc }}</small>
        </div>
        <el-tag :type="task.patient.riskType || 'info'" effect="plain">{{ task.patient.status }}</el-tag>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Refresh } from "@element-plus/icons-vue";
import type { PatientRow } from "@/api/modules/clinic";

type ActionTask = {
  id: string;
  roleLabel: string;
  title: string;
  desc: string;
  count: number | string;
  level: "success" | "warning" | "danger" | "info";
  actionText: string;
  path: string;
  query?: Record<string, string>;
};

type HomeTask = {
  id: string;
  title: string;
  desc: string;
  sectionKey: string;
  patient: PatientRow;
};

defineProps<{
  roleName: string;
  actionTasks: ActionTask[];
  taskCards: HomeTask[];
}>();

defineEmits<{
  refresh: [];
  openActionTask: [task: ActionTask];
  openTask: [task: HomeTask];
}>();
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

.task-list {
  display: grid;
  gap: 8px;
}

.action-task-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

.action-task-card {
  position: relative;
  display: grid;
  min-width: 0;
  gap: 4px;
  padding: 13px 14px 36px;
  overflow: hidden;
  text-align: left;
  cursor: pointer;
  background: linear-gradient(135deg, rgb(236 253 245 / 62%), #ffffff);
  border: 1px solid rgb(15 118 110 / 14%);
  border-radius: 8px;
  transition:
    border-color 180ms ease,
    box-shadow 180ms ease,
    transform 180ms ease;

  &:hover {
    border-color: rgb(15 118 110 / 26%);
    box-shadow: 0 12px 24px rgb(15 118 110 / 10%);
    transform: translateY(-2px);
  }

  span,
  strong,
  em,
  small,
  b {
    display: block;
  }

  span {
    color: var(--clinic-info);
    font-size: 12px;
    font-weight: 700;
  }

  strong {
    color: var(--hos-primary-deep, #3d6b54);
    font-size: 34px;
    line-height: 1;
    font-variant-numeric: tabular-nums;
  }

  em {
    color: var(--el-text-color-primary);
    font-style: normal;
    font-weight: 700;
    line-height: 1.35;
  }

  small {
    min-height: 34px;
    color: var(--el-text-color-secondary);
    line-height: 1.45;
  }

  b {
    position: absolute;
    right: 12px;
    bottom: 10px;
    color: var(--clinic-info);
    font-size: 12px;
  }

  &.is-warning {
    background: var(--clinic-warning-soft);
    border-color: rgb(245 158 11 / 26%);

    strong,
    b {
      color: var(--clinic-warning);
    }
  }

  &.is-danger {
    background: var(--clinic-danger-soft);
    border-color: rgb(239 68 68 / 22%);

    strong,
    b {
      color: var(--clinic-danger);
    }
  }

  &.is-success {
    background: var(--clinic-success-soft);
    border-color: rgb(22 163 74 / 18%);

    strong,
    b {
      color: var(--clinic-success);
    }
  }
}

.patient-task-list {
  padding-top: 10px;
  border-top: 1px dashed var(--el-border-color-lighter);
}

.task-card {
  display: grid;
  grid-template-columns: 160px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding: 12px;
  text-align: left;
  cursor: pointer;
  background: #ffffff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease,
    transform 160ms ease;

  &:hover {
    border-color: rgb(15 118 110 / 24%);
    box-shadow: 0 8px 18px rgb(15 23 42 / 7%);
    transform: translateY(-1px);
  }

  strong,
  span,
  em,
  small {
    display: block;
  }

  strong {
    color: var(--el-text-color-primary);
    font-size: 16px;
  }

  span,
  small {
    color: var(--el-text-color-secondary);
  }

  em {
    color: var(--el-text-color-primary);
    font-style: normal;
    font-weight: 600;
    line-height: 1.45;
  }
}

@media (max-width: 760px) {
  .action-task-list,
  .task-card {
    grid-template-columns: 1fr;
  }
}
</style>
