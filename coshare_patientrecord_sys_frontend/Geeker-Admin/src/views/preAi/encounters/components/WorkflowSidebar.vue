<template>
  <section class="workflow-sidebar" aria-label="院内流转时间轴">
    <div class="workflow-header">
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
        <small>按岗位节点切换填写区</small>
      </div>
      <span>当前岗位已加重标识</span>
    </div>

    <el-scrollbar class="workflow-scrollbar">
      <div class="workflow-flow">
        <div v-for="card in cards" :key="card.key" class="workflow-card-wrap">
          <button
            type="button"
            class="workflow-card"
            :class="{
              active: isActive(card),
              mine: card.editable,
              current: isCurrent(card),
              skipped: statusOf(card) === 'SKIPPED'
            }"
            @click="$emit('select', card)"
          >
            <span class="workflow-order">{{ card.order }}</span>
            <div class="workflow-card-main">
              <strong>{{ card.title }}</strong>
              <small>{{ card.owner }}</small>
              <em v-if="card.editable">当前岗位可办理</em>
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
  encounterStatusLabel: Record<string, string>;
  encounterStatusType: (status: PreAiEncounterStatus) => "success" | "warning" | "info";
  routeLabel: (route?: string) => string;
  statusOf: (card: WorkflowCard) => PreAiStageStatus;
  statusLabel: (card: WorkflowCard) => string;
  statusType: (status: PreAiStageStatus) => "success" | "warning" | "info";
  isActive: (card: WorkflowCard) => boolean;
  isCurrent: (card: WorkflowCard) => boolean;
}>();

const completedCount = computed(() => props.cards.filter(card => ["COMPLETED", "SKIPPED"].includes(props.statusOf(card))).length);
const returnedCount = computed(() => props.cards.filter(card => props.statusOf(card) === "RETURNED").length);

defineEmits<{
  select: [card: WorkflowCard];
}>();
</script>

<style scoped lang="scss">
.workflow-sidebar {
  display: grid;
  gap: 12px;
  padding: 14px;
  margin-bottom: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 16px;
  background: var(--el-bg-color);
  box-shadow: 0 10px 30px rgb(31 78 120 / 8%);
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
.workflow-scrollbar {
  width: 100%;
}
.workflow-flow {
  min-width: max-content;
  display: flex;
  gap: 18px;
  padding: 4px 4px 10px;
}
.workflow-card-wrap {
  position: relative;
  flex: 0 0 186px;
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
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    background-color 0.18s ease,
    transform 0.18s ease;
}
.workflow-card:hover,
.workflow-card.active {
  border-color: var(--el-color-primary-light-3);
  box-shadow: 0 8px 20px rgb(64 158 255 / 14%);
  transform: translateY(-2px);
}
.workflow-card.active {
  outline: 2px solid color-mix(in srgb, var(--el-color-primary) 20%, transparent);
  outline-offset: 1px;
}
.workflow-card.current {
  border-color: var(--el-color-primary);
  background: linear-gradient(135deg, color-mix(in srgb, var(--el-color-primary) 13%, var(--el-bg-color)), var(--el-bg-color));
  box-shadow: 0 12px 28px rgb(0 150 136 / 18%);
}
.workflow-card.current::after {
  position: absolute;
  inset: 0 0 auto;
  height: 4px;
  content: "";
  border-radius: 12px 12px 0 0;
  background: var(--el-color-primary);
}
.workflow-card.mine .workflow-order,
.workflow-card.current .workflow-order {
  color: white;
  background: var(--el-color-primary);
  box-shadow: 0 8px 18px rgb(0 150 136 / 24%);
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
.workflow-card-main em {
  color: var(--el-color-primary);
  font-style: normal;
  font-size: 11px;
}
.workflow-card :deep(.el-tag) {
  grid-column: 2;
  justify-self: start;
  max-width: 100%;
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
