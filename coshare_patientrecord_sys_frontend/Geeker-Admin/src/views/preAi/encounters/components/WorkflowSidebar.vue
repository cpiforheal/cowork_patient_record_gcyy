<template>
  <aside class="workflow-sidebar">
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

    <div class="workflow-summary">
      <div>
        <strong>{{ completedCount }}</strong
        ><span>已完成</span>
      </div>
      <div :class="{ warning: returnedCount }">
        <strong>{{ returnedCount }}</strong
        ><span>退回项</span>
      </div>
      <div>
        <strong>{{ cards.length - completedCount }}</strong
        ><span>待流转</span>
      </div>
    </div>

    <div class="workflow-title">
      <strong>院内流转</strong>
    </div>

    <el-scrollbar height="calc(100vh - 385px)">
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
  </aside>
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
  position: sticky;
  top: 14px;
  align-self: start;
  max-height: calc(100vh - 28px);
  padding: 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 16px;
  background: var(--el-bg-color);
  box-shadow: 0 10px 30px rgb(31 78 120 / 8%);
}
.workflow-patient-card {
  display: grid;
  gap: 5px;
  padding: 13px;
  margin-bottom: 14px;
  color: var(--el-text-color-primary);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 12px;
  background: var(--el-color-primary-light-9);
}
.workflow-patient-card > span,
.workflow-patient-card > small {
  color: var(--el-text-color-secondary);
}
.workflow-patient-card > strong {
  font-size: 18px;
}
.workflow-patient-card > div {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.workflow-patient-card em {
  color: var(--el-text-color-regular);
  font-style: normal;
  font-size: 12px;
}
.workflow-summary {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
  margin-bottom: 14px;
}
.workflow-summary > div {
  display: grid;
  gap: 2px;
  padding: 8px 4px;
  text-align: center;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.workflow-summary strong {
  color: var(--el-color-primary);
  font-size: 17px;
}
.workflow-summary span {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}
.workflow-summary .warning strong {
  color: var(--el-color-warning);
}
.workflow-title {
  padding: 0 2px 10px;
}
.workflow-flow {
  display: grid;
  gap: 15px;
  padding: 2px 3px 10px;
}
.workflow-card-wrap {
  position: relative;
}
.workflow-card-wrap:not(:last-child)::after {
  position: absolute;
  left: 19px;
  top: calc(100% + 2px);
  width: 2px;
  height: 12px;
  content: "";
  background: linear-gradient(var(--el-border-color), var(--el-color-primary-light-7));
}
.workflow-card-wrap:not(:last-child)::before {
  position: absolute;
  z-index: 2;
  left: 15px;
  top: calc(100% + 8px);
  width: 8px;
  height: 8px;
  content: "";
  border-right: 2px solid var(--el-color-primary-light-5);
  border-bottom: 2px solid var(--el-color-primary-light-5);
  transform: rotate(45deg);
}
.workflow-card {
  position: relative;
  width: 100%;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
  padding: 11px 9px;
  text-align: left;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  background: var(--el-bg-color);
  cursor: pointer;
  transition: 0.2s ease;
}
.workflow-card:hover,
.workflow-card.active {
  border-color: var(--el-color-primary);
  box-shadow: 0 8px 20px rgb(64 158 255 / 14%);
  transform: translateX(4px);
}
.workflow-card.active {
  background: linear-gradient(135deg, var(--el-color-primary-light-9), var(--el-bg-color));
}
.workflow-card.current::after {
  position: absolute;
  inset: 7px auto 7px 0;
  width: 3px;
  content: "";
  border-radius: 0 4px 4px 0;
  background: var(--el-color-warning);
  animation: current-stage-pulse 1.8s ease-in-out infinite;
}
@keyframes current-stage-pulse {
  50% {
    opacity: 0.42;
    box-shadow: 0 0 0 5px rgb(230 162 60 / 10%);
  }
}
.workflow-card.mine .workflow-order {
  color: white;
  background: var(--el-color-primary);
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
@media (max-width: 680px) {
  .workflow-sidebar {
    position: static;
    max-height: none;
  }
}
</style>
