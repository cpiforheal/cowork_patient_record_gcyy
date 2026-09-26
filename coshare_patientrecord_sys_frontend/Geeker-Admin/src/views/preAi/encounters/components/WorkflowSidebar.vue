<template>
  <section class="workflow-sidebar" :class="{ compact }" aria-label="院内流转时间轴" @pointermove="$emit('interact')">
    <div v-if="!compact" class="workflow-header">
      <section class="workflow-patient-card">
        <span>当前患者</span>
        <strong>{{ workspace.encounter.patient.patientName || "待补姓名" }}</strong>
        <small>{{ workspace.encounter.caseToken }}</small>
        <div>
          <el-tag size="small" :type="encounterStatusType(workspace.encounter.status)">
            {{ encounterStatusLabel[workspace.encounter.status] || workspace.encounter.status }}
          </el-tag>
          <em>{{ routeLabel(workspace.encounter.route) }}</em>
        </div>
      </section>

      <div class="workflow-summary" aria-label="流程进度概览">
        <div>
          <strong>{{ completedCount }}</strong>
          <span>已完成</span>
        </div>
        <div :class="{ warning: returnedCount }">
          <strong>{{ returnedCount }}</strong>
          <span>退回项</span>
        </div>
        <div>
          <strong>{{ cards.length - completedCount }}</strong>
          <span>待流转</span>
        </div>
      </div>
    </div>

    <div class="workflow-title">
      <div>
        <strong>院内流转</strong>
        <small v-if="!compact">按岗位节点切换填写区</small>
      </div>
      <button v-if="compact" type="button" class="workflow-restore" @click="$emit('restore')">展开流转信息</button>
      <span v-else>蓝环为已选中，左侧色条为当前环节</span>
    </div>

    <el-scrollbar class="workflow-scrollbar">
      <div class="workflow-flow">
        <div v-for="card in cards" :key="card.key" class="workflow-card-wrap">
          <button
            type="button"
            class="workflow-card"
            :class="{
              active: isActive(card),
              current: isCurrent(card),
              pending: isPending(card),
              mine: card.editable,
              skipped: statusOf(card) === 'SKIPPED'
            }"
            :aria-pressed="isActive(card)"
            :aria-current="isCurrent(card) ? 'step' : undefined"
            :title="`${card.order}. ${card.title} · ${card.owner}`"
            @click="$emit('select', card)"
          >
            <span class="workflow-order">{{ card.order }}</span>
            <div class="workflow-card-main">
              <strong>{{ card.title }}</strong>
              <small>{{ card.owner }}</small>
              <em v-if="cardFlag(card)">{{ cardFlag(card) }}</em>
            </div>
            <el-tag size="small" :type="statusType(statusOf(card))">
              {{ statusLabel(card) }}
            </el-tag>
          </button>
        </div>
      </div>
    </el-scrollbar>
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { PreAiEncounterStatus, PreAiStageCode, PreAiStageStatus, PreAiWorkspace } from "@/api/modules/clinic";

export interface WorkflowCard {
  key: string;
  order: number;
  kind: "STAGE" | "AUX";
  title: string;
  owner: string;
  editable: boolean;
  stageCode?: PreAiStageCode;
}

const props = defineProps<{
  workspace: PreAiWorkspace;
  cards: WorkflowCard[];
  compact?: boolean;
  encounterStatusLabel: Record<string, string>;
  encounterStatusType: (status: PreAiEncounterStatus) => "success" | "warning" | "info";
  routeLabel: (route?: string) => string;
  statusOf: (card: WorkflowCard) => PreAiStageStatus;
  statusLabel: (card: WorkflowCard) => string;
  statusType: (status: PreAiStageStatus) => "success" | "warning" | "info";
  isActive: (card: WorkflowCard) => boolean;
  isCurrent: (card: WorkflowCard) => boolean;
  isPending: (card: WorkflowCard) => boolean;
}>();

const completedCount = computed(() => props.cards.filter(card => ["COMPLETED", "SKIPPED"].includes(props.statusOf(card))).length);
const returnedCount = computed(() => props.cards.filter(card => props.statusOf(card) === "RETURNED").length);

// 卡片左下角只展示“语义状态”，选中反馈交给蓝环，避免两种高亮互相干扰
const cardFlag = (card: WorkflowCard) => {
  if (props.isCurrent(card)) return "当前环节";
  if (props.isPending(card)) return "待补化验";
  if (card.editable) return "当前岗位可办理";
  return "";
};

defineEmits<{
  select: [card: WorkflowCard];
  restore: [];
  interact: [];
}>();
</script>

<style scoped lang="scss">
.workflow-sidebar {
  --ease-standard: cubic-bezier(0.2, 0.8, 0.2, 1);
  display: grid;
  gap: 12px;
  padding: 14px;
  margin-bottom: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 16px;
  background: var(--el-bg-color);
  box-shadow: 0 10px 30px rgb(31 78 120 / 8%);
  transition:
    padding 0.24s var(--ease-standard),
    gap 0.24s var(--ease-standard),
    box-shadow 0.24s var(--ease-standard),
    background-color 0.24s var(--ease-standard);
}
.workflow-sidebar.compact {
  gap: 8px;
  padding: 10px 12px;
  box-shadow: 0 6px 18px rgb(31 78 120 / 5%);
}
.workflow-header {
  display: grid;
  grid-template-columns: minmax(230px, 1fr) minmax(220px, 320px);
  gap: 10px;
  align-items: stretch;
}
.workflow-patient-card {
  min-width: 0;
  display: grid;
  grid-template-columns: auto minmax(120px, 1fr) auto;
  align-items: center;
  gap: 6px 12px;
  padding: 12px 14px;
  color: var(--el-text-color-primary);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 12px;
  background: color-mix(in srgb, var(--el-color-primary-light-9) 78%, var(--el-bg-color));
}
.workflow-patient-card > span {
  color: var(--el-text-color-secondary);
}
.workflow-patient-card > strong {
  min-width: 0;
  overflow: hidden;
  font-size: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workflow-patient-card > small {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workflow-patient-card > div {
  display: flex;
  grid-column: 1 / -1;
  align-items: center;
  gap: 8px;
}
.workflow-patient-card em {
  overflow: hidden;
  color: var(--el-text-color-regular);
  font-style: normal;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workflow-summary {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}
.workflow-summary > div {
  display: grid;
  gap: 2px;
  padding: 9px 6px;
  text-align: center;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.workflow-summary strong {
  color: var(--el-color-primary);
  font-size: 18px;
}
.workflow-summary span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.workflow-summary .warning strong {
  color: var(--el-color-warning);
}
.workflow-title {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  padding: 0 2px;
  transition: margin 0.24s var(--ease-standard);
}
.workflow-title > div {
  min-width: 0;
  display: grid;
  gap: 2px;
}
.workflow-title strong {
  font-size: 16px;
}
.workflow-title small,
.workflow-title > span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.workflow-restore {
  padding: 4px 10px;
  color: var(--el-color-primary);
  font-size: 12px;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 999px;
  background: color-mix(in srgb, var(--el-color-primary-light-9) 70%, var(--el-bg-color));
  cursor: pointer;
}
.workflow-scrollbar {
  width: 100%;
}
.workflow-flow {
  min-width: max-content;
  display: flex;
  gap: 18px;
  padding: 4px 4px 10px;
  transition:
    gap 0.24s var(--ease-standard),
    padding 0.24s var(--ease-standard);
}
.workflow-sidebar.compact .workflow-flow {
  gap: 12px;
  padding-bottom: 6px;
}
.workflow-card-wrap {
  position: relative;
  flex: 0 0 186px;
  transition: flex-basis 0.24s var(--ease-standard);
}
.workflow-sidebar.compact .workflow-card-wrap {
  flex-basis: 154px;
}
.workflow-card-wrap:not(:last-child)::after {
  position: absolute;
  top: 50%;
  left: calc(100% + 4px);
  width: 10px;
  height: 2px;
  content: "";
  background: linear-gradient(90deg, var(--el-border-color), var(--el-color-primary-light-7));
}
.workflow-card-wrap:not(:last-child)::before {
  position: absolute;
  z-index: 2;
  top: calc(50% - 4px);
  left: calc(100% + 11px);
  width: 8px;
  height: 8px;
  content: "";
  border-top: 2px solid var(--el-color-primary-light-5);
  border-right: 2px solid var(--el-color-primary-light-5);
  transform: rotate(45deg);
}
.workflow-card {
  position: relative;
  width: 100%;
  min-height: 92px;
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  grid-template-rows: auto auto;
  align-items: center;
  gap: 8px 10px;
  padding: 12px;
  text-align: left;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  background: var(--el-bg-color);
  cursor: pointer;
  will-change: transform;
  transition:
    min-height 0.24s var(--ease-standard),
    padding 0.24s var(--ease-standard),
    border-color 0.18s var(--ease-standard),
    box-shadow 0.18s var(--ease-standard),
    background-color 0.18s var(--ease-standard),
    transform 0.16s var(--ease-standard);
}
.workflow-sidebar.compact .workflow-card {
  min-height: 72px;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: 6px 8px;
  padding: 9px 10px;
}
/* 悬停：轻微抬起 + 浅色提示，不再复用“已选中”的强填充 */
.workflow-card:hover {
  border-color: var(--el-color-primary-light-5);
  background: color-mix(in srgb, var(--el-color-primary) 4%, var(--el-bg-color));
  box-shadow: 0 6px 16px rgb(64 158 255 / 10%);
  transform: translateY(-2px);
}
/* 按下：真实点击回弹 */
.workflow-card:active {
  transform: translateY(0) scale(0.978);
  box-shadow: 0 2px 6px rgb(64 158 255 / 12%);
  transition-duration: 0.06s;
}
.workflow-card:focus-visible {
  outline: 2px solid var(--el-color-primary-light-3);
  outline-offset: 2px;
}
/* 已选中：场上唯一带蓝环与实心序号的卡片 */
.workflow-card.active {
  border-color: var(--el-color-primary);
  background: color-mix(in srgb, var(--el-color-primary) 12%, var(--el-bg-color));
  box-shadow:
    0 0 0 3px color-mix(in srgb, var(--el-color-primary) 16%, transparent),
    0 10px 24px rgb(64 158 255 / 18%);
}
/* 当前环节：左侧色条 + 文字标签，不再整块加重 */
.workflow-card.current {
  border-color: var(--el-color-primary-light-5);
  background: color-mix(in srgb, var(--el-color-primary) 6%, var(--el-bg-color));
}
/* 待补辅助检查：琥珀色阻塞提示，区别于“当前环节”与“已选中” */
.workflow-card.pending {
  border-color: var(--el-color-warning-light-5);
  background: color-mix(in srgb, var(--el-color-warning) 7%, var(--el-bg-color));
}
.workflow-card.current::before,
.workflow-card.pending::before {
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  content: "";
  border-radius: 12px 0 0 12px;
  background: var(--el-color-primary);
}
.workflow-card.pending::before {
  background: var(--el-color-warning);
}
.workflow-card.skipped {
  opacity: 0.58;
}
.workflow-order {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  color: var(--el-color-primary);
  font-weight: 700;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  transition:
    width 0.24s var(--ease-standard),
    height 0.24s var(--ease-standard),
    color 0.18s var(--ease-standard),
    background-color 0.18s var(--ease-standard),
    box-shadow 0.18s var(--ease-standard),
    transform 0.18s var(--ease-standard);
}
.workflow-sidebar.compact .workflow-order {
  width: 28px;
  height: 28px;
}
/* 可办理：只做描边提示，实心圆保留给“已选中” */
.workflow-card.mine .workflow-order {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-8);
  box-shadow: inset 0 0 0 1.5px var(--el-color-primary-light-5);
}
.workflow-card.active .workflow-order {
  color: white;
  background: var(--el-color-primary);
  box-shadow: 0 6px 14px rgb(0 150 136 / 22%);
}
.workflow-card:hover .workflow-order {
  transform: scale(1.06);
}
.workflow-card.active:hover .workflow-order,
.workflow-card:active .workflow-order {
  transform: scale(1);
}
.workflow-card-main {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.workflow-card-main strong,
.workflow-card-main small,
.workflow-card-main em {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.workflow-card-main small {
  color: var(--el-text-color-secondary);
}
.workflow-sidebar.compact .workflow-card-main small,
.workflow-sidebar.compact .workflow-card-main em {
  display: none;
}
.workflow-card-main em {
  justify-self: start;
  max-width: 100%;
  padding: 0 6px;
  color: var(--el-color-primary);
  font-style: normal;
  font-size: 10px;
  line-height: 17px;
  border-radius: 999px;
  background: var(--el-color-primary-light-9);
}
.workflow-card.current .workflow-card-main em {
  background: var(--el-color-primary-light-8);
}
.workflow-card.pending .workflow-card-main em {
  color: var(--el-color-warning-dark-2);
  background: var(--el-color-warning-light-9);
}
.workflow-card :deep(.el-tag) {
  grid-column: 2;
  justify-self: start;
  max-width: 100%;
}
.workflow-sidebar.compact .workflow-card :deep(.el-tag) {
  max-width: 76px;
}
@media (prefers-reduced-motion: reduce) {
  .workflow-sidebar,
  .workflow-title,
  .workflow-flow,
  .workflow-card-wrap,
  .workflow-card,
  .workflow-order {
    transition: none;
  }
  .workflow-card:hover,
  .workflow-card:active,
  .workflow-card:hover .workflow-order {
    transform: none;
  }
}
@media (max-width: 920px) {
  .workflow-header {
    grid-template-columns: 1fr;
  }
  .workflow-title {
    align-items: flex-start;
    flex-direction: column;
  }
}
@media (max-width: 680px) {
  .workflow-sidebar {
    padding: 12px;
  }
  .workflow-patient-card {
    grid-template-columns: 1fr;
  }
  .workflow-patient-card > div {
    grid-column: auto;
    flex-wrap: wrap;
  }
  .workflow-card-wrap {
    flex-basis: 168px;
  }
}
</style>
