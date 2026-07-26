<template>
  <div class="operations-hub">
    <el-alert
      v-if="extendedDataErrors.length"
      title="库存账本接口尚未全部就绪"
      :description="extendedDataErrors.join('；')"
      type="warning"
      :closable="false"
      show-icon
    />

    <section class="operations-overview">
      <header class="section-head">
        <div>
          <span class="eyebrow">今日流转</span>
          <h2>从入库到科室签收</h2>
        </div>
        <div class="head-actions">
          <el-button v-if="canInbound" type="primary" @click="emit('workflow', 'inbound')">入库</el-button>
          <el-button v-if="canRequest" @click="emit('workflow', 'request')">新增申领</el-button>
        </div>
      </header>

      <div class="metric-strip">
        <button :disabled="!canOpen('requests')" @click="emit('goTab', 'requests')">
          <span>待审核</span><strong :class="{ warning: flow.pendingApproval }">{{ flow.pendingApproval }}</strong>
        </button>
        <button :disabled="!canOpen('requests')" @click="emit('goTab', 'requests')">
          <span>待发放</span><strong>{{ flow.pendingIssue }}</strong>
        </button>
        <button :disabled="!canOpen('requests')" @click="emit('goTab', 'requests')">
          <span>待签收</span><strong>{{ flow.pendingReceipt }}</strong>
        </button>
        <button :disabled="!canOpen('packages')" @click="emit('goTab', 'packages')">
          <span>扣减失败</span><strong :class="{ danger: automationFailed }">{{ automationFailed }}</strong>
        </button>
      </div>

      <div class="todo-head">
        <h3>当前待办</h3>
        <el-badge :value="todoRows.length" :hidden="!todoRows.length" type="warning" />
      </div>
      <el-table :data="todoRows.slice(0, 10)" empty-text="当前无待办" table-layout="fixed">
        <el-table-column label="类型" width="100">
          <template #default="{ row }"
            ><el-tag :type="row.level" effect="plain">{{ row.type }}</el-tag></template
          >
        </el-table-column>
        <el-table-column prop="title" label="事项" min-width="220" show-overflow-tooltip />
        <el-table-column prop="desc" label="说明" min-width="280" show-overflow-tooltip />
        <el-table-column label="操作" width="96" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="emitOpenTodo(row)">{{ row.actionLabel }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="quick-actions" aria-label="常用操作">
        <el-button v-if="canApprove" text @click="emit('goTab', 'requests')">审核申领</el-button>
        <el-button v-if="canIssue" text @click="emit('goTab', 'requests')">配发物资</el-button>
        <el-button v-if="canReceive" text @click="emit('goTab', 'requests')">科室签收</el-button>
        <el-button v-if="canOpen('items')" text @click="emit('goTab', 'items')">维护物资档案</el-button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type {
  DepartmentUsageReportParams,
  InventoryConsumptionRecord,
  InventoryException,
  InventoryLocationBalance,
  InventoryWeeklySuggestion,
  InventoryWorkbench
} from "@/api/modules/inventory";
import type { InventoryRequest } from "@/api/modules/inventory";

type TagLevel = "primary" | "success" | "warning" | "danger" | "info";
type WorkflowAction = "item" | "inbound" | "controls" | "requests" | "weekly" | "request";
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

const props = defineProps<{
  workbench?: InventoryWorkbench;
  balances: InventoryLocationBalance[];
  exceptions: InventoryException[];
  consumptions: InventoryConsumptionRecord[];
  weeklySuggestions: InventoryWeeklySuggestion[];
  weeklySuggestionsReady: boolean;
  todoRows: TodoRow[];
  fallbackPendingApproval: number;
  fallbackPendingIssue: number;
  fallbackPendingReceipt: number;
  fallbackLowStock: number;
  fallbackExpirySoon: number;
  extendedDataReady: boolean;
  extendedDataErrors: string[];
  accessibleTabs: string[];
  canInbound: boolean;
  canRequest: boolean;
  canApprove: boolean;
  canIssue: boolean;
  canReceive: boolean;
  canCount: boolean;
  canReport: boolean;
  reportLoading: "" | "pdf" | "xlsx";
  departmentOptions: { label: string; value: string }[];
  itemOptions: { label: string; value: string }[];
  categoryOptions: string[];
}>();

const emit = defineEmits<{
  goTab: [tab: string];
  workflow: [action: WorkflowAction];
  openTodo: [row: TodoRow];
  downloadReport: [params: DepartmentUsageReportParams];
}>();

const canOpen = (tab: string) => props.accessibleTabs.includes(tab);
const emitOpenTodo = (row: unknown) => emit("openTodo", row as TodoRow);
const flow = computed(() => ({
  pendingApproval: props.workbench?.workflow?.pendingApproval ?? props.fallbackPendingApproval,
  pendingIssue: props.workbench?.workflow?.pendingIssue ?? props.fallbackPendingIssue,
  pendingReceipt: props.workbench?.workflow?.pendingReceipt ?? props.fallbackPendingReceipt
}));
const automationFailed = computed(
  () => props.workbench?.automation?.failed ?? props.consumptions.filter(row => row.status === "failed").length
);
</script>

<style scoped lang="scss">
.operations-hub,
.operations-overview {
  display: grid;
  gap: 14px;
}

.operations-overview {
  padding: 16px;
  background: #ffffff;
  border: 1px solid var(--inventory-line);
  border-radius: 8px;
}

.section-head,
.todo-head,
.head-actions,
.quick-actions {
  display: flex;
  align-items: center;
}

.section-head,
.todo-head {
  justify-content: space-between;
  gap: 12px;
}

.section-head h2,
.todo-head h3 {
  margin: 2px 0 0;
  color: var(--inventory-text);
}

.section-head h2 {
  font-size: 18px;
}

.todo-head h3 {
  font-size: 15px;
}

.eyebrow {
  color: var(--inventory-primary);
  font-size: 12px;
  font-weight: 700;
}

.head-actions,
.quick-actions {
  gap: 8px;
}

.metric-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--inventory-line-soft);
  border-radius: 8px;
}

.metric-strip button {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  text-align: left;
  cursor: pointer;
  background: #f8fafb;
  border: 0;
  border-right: 1px solid var(--inventory-line-soft);
}

.metric-strip button:last-child {
  border-right: 0;
}

.metric-strip button:hover:not(:disabled) {
  background: var(--inventory-primary-soft);
}

.metric-strip button:disabled {
  cursor: default;
}

.metric-strip span {
  color: var(--inventory-muted);
  font-size: 12px;
}

.metric-strip strong {
  color: var(--inventory-text);
  font-size: 24px;
  font-variant-numeric: tabular-nums;
}

.metric-strip strong.warning {
  color: var(--inventory-warning);
}

.metric-strip strong.danger {
  color: var(--inventory-danger);
}

.quick-actions {
  flex-wrap: wrap;
  justify-content: flex-end;
  padding-top: 2px;
}

@media (max-width: 900px) {
  .metric-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .section-head {
    align-items: stretch;
    flex-direction: column;
  }

  .metric-strip {
    grid-template-columns: 1fr;
  }
}
</style>
