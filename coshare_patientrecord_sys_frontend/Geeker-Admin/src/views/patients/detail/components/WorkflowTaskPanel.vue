<template>
  <section class="workflow-task-panel">
    <div class="workflow-task-head">
      <div>
        <span>当前节点任务</span>

        <h3>{{ state.currentStage.title }}</h3>

        <p>{{ state.currentStage.department }} · 主责 {{ state.currentStage.owner }}</p>
      </div>

      <el-tag :type="state.archiveReadiness.ready ? 'success' : state.ownerTasks.length ? 'warning' : 'info'" effect="plain">
        {{ state.archiveReadiness.ready ? "可提交质控" : state.ownerTasks.length ? "待处理" : "推进中" }}
      </el-tag>
    </div>

    <div class="workflow-progress-strip">
      <button
        v-for="stage in state.stageNodes"
        :key="stage.key"
        type="button"
        class="workflow-stage-chip"
        :class="stage.status"
        @click="emitStageFocus(stage)"
      >
        <strong>{{ stage.shortTitle || stage.title }}</strong>

        <span>{{ stage.completed }}/{{ stage.total }}</span>

        <em v-if="stage.missing">{{ stage.missing }}</em>
      </button>
    </div>

    <div class="workflow-tabbar" role="tablist" aria-label="患者流程任务">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        type="button"
        :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >
        <strong>{{ tab.label }}</strong>
        <span>{{ tab.count }}</span>
      </button>
    </div>

    <div class="workflow-task-board">
      <article class="workflow-task-card primary">
        <div class="workflow-card-title">
          <strong>{{ activeTabMeta.title }}</strong>

          <span>{{ activeTabMeta.count }} 项</span>
        </div>

        <el-empty v-if="!visibleTasks.length" :description="activeTabMeta.emptyText" />

        <button
          v-for="task in visibleTasks"
          v-else
          :key="`${activeTab}-${task.sectionKey}-${task.fieldKey}`"
          type="button"
          class="workflow-task-row"
          @click="$emit('focusField', task)"
        >
          <span>
            <strong>{{ task.fieldLabel }}</strong>

            <small>{{ task.sectionTitle }} · {{ task.issueMessage || task.categoryLabel }}</small>

            <small class="workflow-task-meta"> {{ task.relationLabel }} · 主责 {{ task.primaryOwner || task.department }} </small>
          </span>

          <span class="workflow-task-tags">
            <el-tag v-if="task.critical" type="danger" effect="plain" size="small">关键</el-tag>

            <el-tag
              :type="task.issueLevel === 'invalid' ? 'danger' : task.complete ? 'info' : 'warning'"
              effect="plain"
              size="small"
            >
              {{ task.issueLevel === "invalid" ? "异常" : task.required || task.archiveRequired ? "必填" : "待补" }}
            </el-tag>
          </span>
        </button>
      </article>

      <article class="workflow-task-card">
        <div class="workflow-card-title">
          <strong>附件证据</strong>

          <span>{{ attachmentSummary }}</span>
        </div>

        <el-empty v-if="!state.attachmentTasks.length" description="当前节点暂无附件要求" />

        <button
          v-for="task in state.attachmentTasks"
          v-else
          :key="task.fieldKey"
          type="button"
          class="workflow-task-row compact"
          @click="$emit('focusAttachment', task)"
        >
          <span>
            <strong>{{ task.fieldLabel }}</strong>

            <small>{{ task.sectionTitle }} · {{ task.attachmentCount ? `${task.attachmentCount} 份证据` : "待补附件" }}</small>
          </span>

          <el-tag :type="task.attachmentCount ? 'success' : task.required ? 'warning' : 'info'" effect="plain" size="small">
            {{ task.attachmentCount ? "已上传" : task.required ? "待补" : "按需" }}
          </el-tag>
        </button>
      </article>
    </div>

    <div class="workflow-next-step">
      <div>
        <span>下一步建议</span>

        <strong>{{ state.nextStepAdvice }}</strong>
      </div>

      <div class="workflow-readiness">
        <span>归档准备度</span>

        <strong>{{ state.archiveReadiness.percent }}%</strong>

        <small>
          已填 {{ state.archiveReadiness.completed }}/{{ state.archiveReadiness.total }} · 待补
          {{ state.archiveReadiness.missing }}
        </small>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import type {
  PatientWorkflowTaskState,
  WorkflowAttachmentTask,
  WorkflowFieldTask,
  WorkflowStageNode
} from "../composables/usePatientWorkflowTasks";

type TaskTabKey = "owner" | "support" | "review" | "pending";

const props = defineProps<{
  state: PatientWorkflowTaskState;
}>();

const emit = defineEmits<{
  focusField: [task: WorkflowFieldTask];
  focusAttachment: [task: WorkflowAttachmentTask];
  focusStage: [stage: WorkflowStageNode];
}>();

const activeTab = ref<TaskTabKey>("owner");

const pendingTasks = computed(() => {
  const byKey = new Map<string, WorkflowFieldTask>();
  [...props.state.ownerTasks, ...props.state.supportTasks, ...props.state.reviewTasks, ...props.state.blockingItems].forEach(
    task => {
      byKey.set(`${task.sectionKey}-${task.fieldKey}`, task);
    }
  );

  return Array.from(byKey.values()).slice(0, 10);
});

const tabs = computed(() => [
  { key: "owner" as const, label: "我负责填写", count: props.state.ownerTasks.length },
  { key: "support" as const, label: "我可以补充", count: props.state.supportTasks.length },
  { key: "review" as const, label: "我需要复核", count: props.state.reviewTasks.length },
  { key: "pending" as const, label: "只看待处理", count: pendingTasks.value.length }
]);

const activeTabMeta = computed(() => {
  const meta = {
    owner: { title: "我负责填写", emptyText: "当前节点暂无主责待处理字段", count: props.state.ownerTasks.length },
    support: { title: "我可以补充", emptyText: "当前节点暂无协作补充字段", count: props.state.supportTasks.length },
    review: { title: "我需要复核", emptyText: "当前节点暂无复核项", count: props.state.reviewTasks.length },
    pending: { title: "只看待处理", emptyText: "暂无缺失、异常或附件待补项", count: pendingTasks.value.length }
  };

  return meta[activeTab.value];
});

const visibleTasks = computed(() => {
  if (activeTab.value === "owner") return props.state.ownerTasks;
  if (activeTab.value === "support") return props.state.supportTasks;
  if (activeTab.value === "review") return props.state.reviewTasks;
  return pendingTasks.value;
});

const attachmentSummary = computed(() => {
  const uploaded = props.state.attachmentTasks.filter(task => task.attachmentCount > 0).length;
  const total = props.state.attachmentTasks.length;

  return total ? `${uploaded}/${total}` : "0";
});

const emitStageFocus = (stage: WorkflowStageNode) => {
  emit("focusStage", stage);
};
</script>

<style scoped lang="scss">
.workflow-task-panel {
  display: grid;
  gap: 12px;
  padding: 14px;
  margin-bottom: 14px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(248, 250, 252, 0.96));
  border: 1px solid var(--hos-border);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-soft);
}

.workflow-task-head,
.workflow-next-step {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
}

.workflow-task-head {
  h3,
  p {
    margin: 0;
  }

  h3 {
    margin-top: 3px;
    font-size: 18px;
    color: var(--hos-text-main);
  }

  span,
  p {
    color: var(--hos-text-secondary);
  }
}

.workflow-progress-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(88px, 1fr));
  gap: 8px;
}

.workflow-stage-chip,
.workflow-tabbar button,
.workflow-task-row {
  border: 0;
  appearance: none;
  cursor: pointer;
  text-align: left;
  font: inherit;
}

.workflow-stage-chip {
  position: relative;
  display: grid;
  gap: 4px;
  min-height: 58px;
  padding: 9px 10px;
  color: var(--hos-text-main);
  background: #ffffff;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);

  strong {
    font-size: 13px;
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 12px;
  }

  em {
    position: absolute;
    top: 7px;
    right: 7px;
    min-width: 18px;
    height: 18px;
    padding: 0 5px;
    color: #ffffff;
    font-size: 12px;
    font-style: normal;
    line-height: 18px;
    text-align: center;
    background: #d97706;
    border-radius: 999px;
  }

  &.done {
    border-color: rgba(22, 163, 74, 0.28);
    background: rgba(240, 253, 244, 0.88);
  }

  &.active,
  &.attention {
    border-color: rgba(37, 99, 235, 0.28);
    box-shadow: inset 0 0 0 1px rgba(37, 99, 235, 0.12);
  }

  &.attention {
    background: rgba(255, 251, 235, 0.92);
  }
}

.workflow-tabbar {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;

  button {
    display: flex;
    gap: 8px;
    align-items: center;
    justify-content: space-between;
    min-width: 0;
    min-height: 40px;
    padding: 8px 10px;
    color: var(--hos-text-secondary);
    background: #ffffff;
    border: 1px solid var(--hos-border-light);
    border-radius: var(--hos-radius-md);

    &.active {
      color: #1d4ed8;
      background: rgba(239, 246, 255, 0.92);
      border-color: rgba(37, 99, 235, 0.26);
    }

    strong {
      min-width: 0;
      overflow: hidden;
      font-size: 13px;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    span {
      flex: 0 0 auto;
      color: var(--hos-text-secondary);
      font-size: 12px;
    }
  }
}

.workflow-task-board {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(260px, 1fr);
  gap: 10px;
}

.workflow-task-card {
  display: grid;
  align-content: start;
  gap: 8px;
  min-width: 0;
  padding: 12px;
  background: #ffffff;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);

  &.primary {
    border-color: rgba(37, 99, 235, 0.2);
  }
}

.workflow-card-title,
.workflow-task-row {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
}

.workflow-card-title {
  strong {
    color: var(--hos-text-main);
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 13px;
  }
}

.workflow-task-row {
  width: 100%;
  min-height: 64px;
  padding: 9px 10px;
  background: #f8fafc;
  border: 1px solid transparent;
  border-radius: var(--hos-radius-sm);

  &.compact {
    min-height: 58px;
  }

  &:hover {
    border-color: rgba(37, 99, 235, 0.22);
    background: #ffffff;
  }

  > span:first-child {
    display: grid;
    gap: 3px;
    min-width: 0;
  }

  strong,
  small {
    overflow-wrap: anywhere;
  }

  strong {
    color: var(--hos-text-main);
  }

  small {
    color: var(--hos-text-secondary);
  }
}

.workflow-task-meta {
  font-size: 12px;
}

.workflow-task-tags {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  gap: 4px;
  justify-content: flex-end;
  max-width: 110px;
}

.workflow-next-step {
  padding: 12px;
  background: #f8fafc;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);

  > div {
    display: grid;
    gap: 3px;
    min-width: 0;
  }

  span,
  small {
    color: var(--hos-text-secondary);
  }

  strong {
    color: var(--hos-text-main);
    overflow-wrap: anywhere;
  }
}

.workflow-readiness {
  min-width: 190px;
  text-align: right;
}

@media (max-width: 1200px) {
  .workflow-task-board,
  .workflow-tabbar {
    grid-template-columns: 1fr;
  }

  .workflow-task-head,
  .workflow-next-step {
    align-items: flex-start;
    flex-direction: column;
  }

  .workflow-readiness {
    min-width: 0;
    text-align: left;
  }
}
</style>
