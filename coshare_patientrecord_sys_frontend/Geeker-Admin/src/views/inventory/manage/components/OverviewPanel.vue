<template>
  <div class="overview-panel">
    <section class="overview-command-board">
      <div class="board-signal" :class="executiveSignal.level">
        <span>今日物资运行</span>
        <strong>{{ executiveSignal.title }}</strong>
        <small>{{ executiveSignal.desc }}</small>
      </div>
      <div class="board-metrics">
        <button
          v-for="stat in stats"
          :key="`overview-${stat.label}`"
          class="board-metric"
          :class="stat.tone"
          @click="$emit('goTab', stat.tab || activeTab)"
        >
          <span>{{ stat.label }}</span>
          <strong>{{ stat.value }}</strong>
          <small>{{ stat.desc }}</small>
        </button>
      </div>
    </section>

    <div class="operations-grid">
      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>待处理事项</h2>
            <p>先处理审核、发放、签收这些会卡住闭环的事项。</p>
          </div>
        </div>
        <div v-if="todoRows.length" class="todo-list">
          <button v-for="row in todoRows" :key="row.id" class="todo-card" @click="$emit('openTodo', row)">
            <el-tag :type="row.level" effect="plain">{{ row.type }}</el-tag>
            <div>
              <strong>{{ row.title }}</strong>
              <small>{{ row.desc }}</small>
            </div>
            <span>{{ row.actionLabel }}</span>
          </button>
        </div>
        <el-empty v-else description="暂无待处理事项" :image-size="72" />
      </section>

      <section class="panel quick-actions-panel">
        <div class="panel-head">
          <div>
            <h2>常用动作</h2>
            <p>把一线高频动作放在第一屏，减少找菜单。</p>
          </div>
        </div>
        <div class="workflow-steps">
          <button
            v-for="(step, index) in workflowSteps"
            :key="step.title"
            class="workflow-step"
            @click="$emit('workflow', step.action)"
          >
            <span class="step-index">{{ index + 1 }}</span>
            <strong>{{ step.title }}</strong>
            <small>{{ step.desc }}</small>
          </button>
        </div>
      </section>
    </div>

    <div class="operations-grid secondary">
      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>风险清单</h2>
            <p>红色立即处理，黄色当天跟进。</p>
          </div>
          <el-button v-if="canExportRisk" plain :icon="Download" @click="$emit('exportRisk')">导出</el-button>
        </div>
        <el-table :data="riskRows" border>
          <el-table-column prop="type" label="类型" width="120">
            <template #default="{ row }">
              <el-tag :type="row.level" effect="plain">{{ row.type }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="subject" label="对象" min-width="160" />
          <el-table-column prop="department" label="科室" width="120" />
          <el-table-column prop="status" label="当前情况" min-width="220" />
          <el-table-column prop="suggestion" label="建议动作" min-width="240" />
          <el-table-column label="处理" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="goRiskTab(row)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="panel role-entry-panel">
        <div class="panel-head">
          <div>
            <h2>岗位入口</h2>
            <p>只保留当前角色常用入口。</p>
          </div>
        </div>
        <div class="role-entry-grid">
          <button v-for="card in roleEntryCards" :key="card.title" class="role-entry-card" @click="$emit('goTab', card.tab)">
            <span>{{ card.scene }}</span>
            <strong>{{ card.title }}</strong>
            <small>{{ card.desc }}</small>
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Download } from "@element-plus/icons-vue";
import type { InventoryRequest } from "@/api/modules/inventory";

type TagLevel = "primary" | "success" | "warning" | "danger" | "info";

type TodoRow = {
  id: string;
  type: string;
  level: TagLevel;
  title: string;
  desc: string;
  actionLabel: string;
  tab: string;
  action?: "approve" | "issue" | "receive";
  request?: InventoryRequest;
};

type TabStat = {
  label: string;
  value: string | number;
  desc: string;
  tone?: "warning" | "danger";
  tab?: string;
};

type WorkflowAction = "item" | "inbound" | "controls" | "requests" | "weekly" | "request";

type WorkflowStep = {
  title: string;
  desc: string;
  action: WorkflowAction;
};

type RiskRow = {
  id: string;
  type: string;
  level: TagLevel;
  subject: string;
  department: string;
  status: string;
  suggestion: string;
  tab: string;
};

type RoleEntryCard = {
  scene: string;
  title: string;
  desc: string;
  tab: string;
};

defineProps<{
  activeTab: string;
  executiveSignal: { level: string; title: string; desc: string };
  stats: TabStat[];
  todoRows: TodoRow[];
  workflowSteps: WorkflowStep[];
  riskRows: RiskRow[];
  roleEntryCards: RoleEntryCard[];
  canExportRisk: boolean;
}>();

const emit = defineEmits<{
  goTab: [tab: string];
  openTodo: [row: TodoRow];
  workflow: [action: WorkflowAction];
  exportRisk: [];
}>();

const goRiskTab = (row: unknown) => emit("goTab", (row as RiskRow).tab);
</script>

<style scoped lang="scss">
.overview-panel {
  display: grid;
  gap: 12px;
}

.overview-command-board,
.panel {
  background: var(--inventory-panel);
  border: 1px solid var(--inventory-line);
  border-radius: 8px;
  box-shadow: 0 1px 1px rgb(15 23 42 / 3%);
}

.panel {
  padding: 13px 14px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;

  h2,
  p {
    margin: 0;
  }

  h2 {
    color: var(--inventory-text);
    font-size: 16px;
    line-height: 1.35;
  }

  p {
    margin-top: 4px;
    color: var(--inventory-muted);
    font-size: 13px;
  }
}

.overview-command-board {
  display: grid;
  grid-template-columns: minmax(240px, 0.8fr) minmax(0, 1fr);
  gap: 0;
  overflow: hidden;
}

.board-signal {
  display: grid;
  align-content: center;
  gap: 5px;
  padding: 16px;
  background: var(--inventory-success-soft);
  border-right: 1px solid var(--inventory-line);

  span,
  strong,
  small {
    display: block;
  }

  span {
    color: var(--inventory-success);
    font-size: 12px;
    font-weight: 700;
  }

  strong {
    color: var(--inventory-text);
    font-size: 28px;
    line-height: 1.18;
  }

  small {
    color: var(--inventory-muted);
    line-height: 1.5;
  }

  &.warning {
    background: var(--inventory-warning-soft);

    span {
      color: var(--inventory-warning);
    }
  }

  &.danger {
    background: var(--inventory-danger-soft);

    span {
      color: var(--inventory-danger);
    }
  }
}

.board-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.board-metric {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 14px;
  text-align: left;
  cursor: pointer;
  background: #ffffff;
  border: 0;
  border-right: 1px solid var(--inventory-line-soft);
  transition:
    border-color 0.16s ease,
    background 0.16s ease;

  span,
  strong,
  small {
    display: block;
  }

  span,
  small {
    color: var(--inventory-muted);
  }

  strong {
    color: var(--inventory-primary);
    font-size: 28px;
    line-height: 1.05;
    font-variant-numeric: tabular-nums;
  }

  &:hover {
    background: var(--inventory-primary-soft);
    border-color: rgb(8 118 111 / 26%);
  }

  &:last-child {
    border-right: 0;
  }

  &.warning strong {
    color: var(--inventory-warning);
  }

  &.danger strong {
    color: var(--inventory-danger);
  }
}

.operations-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(340px, 0.62fr);
  gap: 12px;

  &.secondary {
    grid-template-columns: minmax(0, 1fr) minmax(280px, 0.36fr);
  }
}

.quick-actions-panel {
  .panel-head {
    margin-bottom: 8px;
  }
}

.workflow-steps {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.workflow-step,
.todo-card {
  text-align: left;
  cursor: pointer;
  background: #ffffff;
  border: 1px solid var(--inventory-line-soft);
  border-radius: 8px;
  transition:
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.16s ease;

  &:hover {
    background: #f9fdfc;
    border-color: rgb(8 118 111 / 28%);
  }
}

.workflow-step {
  display: grid;
  gap: 6px;
  min-height: 96px;
  padding: 10px;

  strong,
  small {
    display: block;
  }

  strong {
    color: var(--inventory-primary);
    font-size: 15px;
    line-height: 1.35;
  }

  small {
    color: var(--inventory-muted);
    line-height: 1.45;
  }
}

.step-index {
  display: inline-grid;
  place-items: center;
  width: 26px;
  height: 26px;
  color: #ffffff;
  font-weight: 700;
  background: var(--inventory-primary);
  border-radius: 7px;
}

.todo-list {
  display: grid;
  gap: 10px;
}

.todo-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 11px;

  strong,
  small {
    display: block;
  }

  strong {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    margin-top: 3px;
    color: var(--el-text-color-secondary);
  }

  > span:last-child {
    color: var(--inventory-primary);
    font-weight: 700;
  }
}

.role-entry-panel {
  .panel-head p {
    display: block;
  }
}

.role-entry-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 8px;
}

.role-entry-card {
  display: grid;
  gap: 5px;
  min-height: 76px;
  padding: 10px;
  text-align: left;
  cursor: pointer;
  background: #ffffff;
  border: 1px solid var(--inventory-line-soft);
  border-radius: 8px;
  transition:
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.16s ease;

  &:hover {
    background: #f9fdfc;
    border-color: rgb(8 118 111 / 28%);
  }

  span,
  strong,
  small {
    display: block;
  }

  span {
    color: var(--inventory-primary);
    font-size: 12px;
    font-weight: 700;
  }

  strong {
    color: #111827;
    font-size: 14px;
    line-height: 1.35;
  }

  small {
    color: var(--inventory-muted);
    line-height: 1.45;
  }
}

:deep(.el-table) {
  --el-table-border-color: var(--inventory-line-soft);
  --el-table-header-bg-color: #f7fafb;
  --el-table-header-text-color: #44515f;
  --el-table-text-color: var(--inventory-text);

  font-size: 13px;

  th.el-table__cell {
    font-weight: 700;
  }
}

@media (max-width: 1080px) {
  .overview-command-board,
  .operations-grid,
  .operations-grid.secondary {
    grid-template-columns: 1fr;
  }

  .board-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .board-signal {
    grid-column: 1 / -1;
    border-right: 0;
    border-bottom: 1px solid var(--inventory-line);
  }

  .workflow-steps {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .role-entry-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .board-metrics {
    grid-template-columns: 1fr;
  }

  .workflow-steps,
  .todo-card,
  .role-entry-grid {
    grid-template-columns: 1fr;
  }

  .todo-card > span:last-child {
    justify-self: start;
  }

  .board-signal strong {
    font-size: 24px;
  }
}
</style>
