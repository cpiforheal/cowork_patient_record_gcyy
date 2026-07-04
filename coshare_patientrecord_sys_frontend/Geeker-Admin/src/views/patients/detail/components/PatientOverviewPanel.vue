<template>
  <section class="patient-overview-panel">
    <div class="overview-head">
      <div>
        <span>质控总览</span>

        <h3>{{ state.currentStage.title }}</h3>

        <p>{{ state.currentStage.department }} · 下一节点 {{ state.nextStage?.owner || "待归档确认" }}</p>
      </div>

      <div class="overview-readiness" :class="{ ready: state.archiveReadiness.ready }">
        <strong>{{ state.archiveReadiness.percent }}%</strong>
        <span>{{ state.archiveReadiness.ready ? "归档就绪" : "仍需补齐" }}</span>
      </div>
    </div>

    <div class="overview-metrics">
      <button
        v-for="metric in metrics"
        :key="metric.key"
        type="button"
        class="overview-metric"
        :class="[metric.tone, { disabled: metric.value === 0 }]"
        :disabled="metric.value === 0"
        @click="focusMetric(metric.key)"
      >
        <span>{{ metric.label }}</span>

        <strong>{{ metric.value }}</strong>
      </button>
    </div>

    <div class="overview-body">
      <article>
        <div class="overview-title">
          <strong>流程进度</strong>

          <span>{{ state.archiveReadiness.completed }}/{{ state.archiveReadiness.total }}</span>
        </div>

        <div class="overview-stage-list">
          <button
            v-for="stage in state.stageNodes"
            :key="stage.key"
            type="button"
            class="overview-stage"
            :class="stage.status"
            @click="$emit('focusStage', stage)"
          >
            <span>{{ stage.shortTitle || stage.title }}</span>

            <small>{{ stage.completed }}/{{ stage.total }}</small>
          </button>
        </div>
      </article>

      <article>
        <div class="overview-title">
          <strong>优先处理</strong>

          <span>{{ state.blockingItems.length }} 项</span>
        </div>

        <el-empty v-if="!priorityItems.length" description="暂无优先阻塞项" />

        <button
          v-for="task in priorityItems"
          v-else
          :key="`${task.sectionKey}-${task.fieldKey}`"
          type="button"
          class="overview-row"
          @click="$emit('focusField', task)"
        >
          <span>
            <strong>{{ task.fieldLabel }}</strong>
            <small>{{ task.sectionTitle }} · {{ task.issueMessage || task.relationLabel }}</small>
          </span>

          <el-tag :type="task.issueLevel === 'invalid' ? 'danger' : 'warning'" effect="plain" size="small">
            {{ task.primaryOwner || task.department }}
          </el-tag>
        </button>
      </article>
    </div>

    <div class="overview-footer">
      <span>下一步建议</span>

      <strong>{{ state.nextStepAdvice }}</strong>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type {
  PatientWorkflowTaskState,
  WorkflowAttachmentTask,
  WorkflowFieldTask,
  WorkflowStageNode
} from "../composables/usePatientWorkflowTasks";

type MetricKey = "missing" | "invalid" | "attachment" | "review" | "critical";

const props = defineProps<{
  state: PatientWorkflowTaskState;
}>();

const emit = defineEmits<{
  focusField: [task: WorkflowFieldTask];
  focusAttachment: [task: WorkflowAttachmentTask];
  focusStage: [stage: WorkflowStageNode];
}>();

const metrics = computed(() => [
  { key: "missing" as const, label: "必填缺失", value: props.state.qualitySummary.missingCount, tone: "warning" },
  { key: "invalid" as const, label: "格式异常", value: props.state.qualitySummary.invalidCount, tone: "danger" },
  { key: "attachment" as const, label: "附件待补", value: props.state.qualitySummary.attachmentMissingCount, tone: "warning" },
  { key: "review" as const, label: "待复核", value: props.state.qualitySummary.reviewCount, tone: "info" },
  { key: "critical" as const, label: "关键未闭环", value: props.state.qualitySummary.criticalOpenCount, tone: "danger" }
]);

const priorityItems = computed(() =>
  [...props.state.blockingItems, ...props.state.reviewTasks]
    .filter((task, index, tasks) => tasks.findIndex(item => item.fieldKey === task.fieldKey) === index)
    .slice(0, 5)
);

const metricCandidates = computed(() =>
  [...props.state.blockingItems, ...props.state.ownerTasks, ...props.state.supportTasks, ...props.state.reviewTasks].filter(
    (task, index, tasks) => tasks.findIndex(item => item.fieldKey === task.fieldKey) === index
  )
);

const focusMetric = (key: MetricKey) => {
  if (key === "attachment") {
    const attachmentTask = props.state.attachmentTasks.find(task => task.required && task.attachmentCount === 0);
    if (attachmentTask) emit("focusAttachment", attachmentTask);
    return;
  }

  const task =
    key === "review"
      ? props.state.reviewTasks[0]
      : key === "critical"
        ? metricCandidates.value.find(item => item.critical || item.status === "critical")
        : metricCandidates.value.find(item => item.issueLevel === key || item.status === key);

  if (task) emit("focusField", task);
};
</script>

<style scoped lang="scss">
.patient-overview-panel {
  display: grid;
  gap: 12px;
  padding: 14px;
  margin-bottom: 14px;
  background: #ffffff;
  border: 1px solid var(--hos-border);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-soft);
}

.overview-head {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;

  h3,
  p {
    margin: 0;
  }

  h3 {
    margin-top: 3px;
    color: var(--hos-text-main);
    font-size: 18px;
  }

  span,
  p {
    color: var(--hos-text-secondary);
  }
}

.overview-readiness {
  display: grid;
  flex: 0 0 96px;
  place-items: center;
  min-height: 76px;
  color: #d97706;
  background: rgba(255, 251, 235, 0.92);
  border: 1px solid rgba(217, 119, 6, 0.22);
  border-radius: var(--hos-radius-md);

  &.ready {
    color: #15803d;
    background: rgba(240, 253, 244, 0.92);
    border-color: rgba(22, 163, 74, 0.22);
  }

  strong {
    font-size: 24px;
  }

  span {
    font-size: 12px;
  }
}

.overview-metrics {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}

.overview-metric {
  display: grid;
  gap: 4px;
  min-width: 0;
  min-height: 58px;
  padding: 9px 10px;
  text-align: left;
  cursor: pointer;
  background: #f8fafc;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);

  span {
    color: var(--hos-text-secondary);
    font-size: 12px;
  }

  strong {
    color: var(--hos-text-main);
    font-size: 20px;
  }

  &.warning strong {
    color: #d97706;
  }

  &.danger strong {
    color: #dc2626;
  }

  &.info strong {
    color: #2563eb;
  }

  &.disabled {
    cursor: default;
    opacity: 0.58;
  }
}

.overview-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 1.2fr);
  gap: 10px;

  article {
    display: grid;
    align-content: start;
    gap: 8px;
    min-width: 0;
    padding: 12px;
    background: #f8fafc;
    border: 1px solid var(--hos-border-light);
    border-radius: var(--hos-radius-md);
  }
}

.overview-title,
.overview-row {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
}

.overview-title {
  strong {
    color: var(--hos-text-main);
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 13px;
  }
}

.overview-stage-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(92px, 1fr));
  gap: 6px;
}

.overview-stage,
.overview-row {
  border: 0;
  appearance: none;
  font: inherit;
  cursor: pointer;
}

.overview-stage {
  display: grid;
  gap: 3px;
  min-height: 48px;
  padding: 8px;
  color: var(--hos-text-main);
  text-align: left;
  background: #ffffff;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-sm);

  small {
    color: var(--hos-text-secondary);
  }

  &.done {
    background: rgba(240, 253, 244, 0.9);
    border-color: rgba(22, 163, 74, 0.22);
  }

  &.attention {
    background: rgba(255, 251, 235, 0.92);
    border-color: rgba(217, 119, 6, 0.22);
  }
}

.overview-row {
  width: 100%;
  min-height: 58px;
  padding: 9px 10px;
  text-align: left;
  background: #ffffff;
  border: 1px solid transparent;
  border-radius: var(--hos-radius-sm);

  &:hover {
    border-color: rgba(37, 99, 235, 0.22);
  }

  > span:first-child {
    display: grid;
    gap: 3px;
    min-width: 0;
  }

  strong,
  small {
    min-width: 0;
    overflow-wrap: anywhere;
  }

  strong {
    color: var(--hos-text-main);
  }

  small {
    color: var(--hos-text-secondary);
  }
}

.overview-footer {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 12px;
  background: #f8fafc;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);

  span {
    color: var(--hos-text-secondary);
  }

  strong {
    color: var(--hos-text-main);
    overflow-wrap: anywhere;
  }
}

@media (max-width: 1200px) {
  .overview-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .overview-readiness {
    width: 100%;
  }

  .overview-metrics,
  .overview-body {
    grid-template-columns: 1fr;
  }
}
</style>
