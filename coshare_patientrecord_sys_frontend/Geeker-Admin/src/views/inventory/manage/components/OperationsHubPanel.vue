<template>
  <div class="operations-hub">
    <nav class="center-nav" aria-label="进销存业务中心">
      <button
        v-for="center in centerCards"
        :key="center.key"
        :class="[center.tone, { active: activeCenter === center.key }]"
        @click="emit('update:activeCenter', center.key)"
      >
        <span>{{ center.title }}</span>
        <strong>{{ center.value }}</strong>
        <small>{{ center.badge }}</small>
      </button>
    </nav>

    <el-alert
      v-if="extendedDataErrors.length"
      title="新库存账本接口尚未全部就绪"
      :description="extendedDataErrors.join('；')"
      type="warning"
      :closable="false"
      show-icon
    />

    <section v-if="activeCenter === 'flow'" class="center-panel">
      <div class="section-head">
        <div>
          <span class="eyebrow">业务流转</span>
          <h2>从入库到科室签收</h2>
        </div>
        <div class="head-actions">
          <el-button v-if="canInbound" type="primary" @click="emit('workflow', 'inbound')">入库</el-button>
          <el-button v-if="canRequest" plain @click="emit('workflow', 'request')">新增申领</el-button>
        </div>
      </div>

      <div class="flow-lane">
        <button
          v-for="(node, index) in flowNodes"
          :key="node.key"
          :disabled="!canOpen(node.tab)"
          @click="emit('goTab', node.tab)"
        >
          <span class="node-index">{{ index + 1 }}</span>
          <div>
            <small>{{ node.label }}</small>
            <strong>{{ node.value }}</strong>
          </div>
          <el-tag v-if="node.pending" :type="node.type" effect="plain">待处理</el-tag>
          <span v-if="index < flowNodes.length - 1" class="node-arrow">→</span>
        </button>
      </div>

      <div class="content-grid">
        <article class="work-card todo-card">
          <header>
            <h3>当前待办</h3>
            <el-badge :value="todoRows.length" :hidden="!todoRows.length" type="warning" />
          </header>
          <div v-if="todoRows.length" class="todo-list">
            <button v-for="row in todoRows.slice(0, 8)" :key="row.id" @click="emit('openTodo', row)">
              <el-tag :type="row.level" effect="plain">{{ row.type }}</el-tag>
              <div>
                <strong>{{ row.title }}</strong>
                <small>{{ row.desc }}</small>
              </div>
              <span>{{ row.actionLabel }}</span>
            </button>
          </div>
          <el-empty v-else description="当前无待办" :image-size="64" />
        </article>

        <article class="work-card quick-card">
          <header><h3>下一步</h3></header>
          <div class="quick-actions">
            <button v-if="canApprove" @click="emit('goTab', 'requests')">
              <strong>审核申领</strong><span>待审 {{ flow.pendingApproval }}</span>
            </button>
            <button v-if="canIssue" @click="emit('goTab', 'requests')">
              <strong>配发物资</strong><span>待配 {{ flow.pendingIssue }}</span>
            </button>
            <button v-if="canReceive" @click="emit('goTab', 'requests')">
              <strong>科室签收</strong><span>在途 {{ flow.inTransit }}</span>
            </button>
            <button v-if="canInbound" @click="emit('workflow', 'inbound')"><strong>中央入库</strong><span>补充批次</span></button>
            <button v-if="canRequest" @click="emit('workflow', 'request')"><strong>科室申领</strong><span>创建申请</span></button>
            <button v-if="canOpen('items')" @click="emit('goTab', 'items')">
              <strong>物资档案</strong><span>规格与预警</span>
            </button>
          </div>
        </article>
      </div>
    </section>

    <section v-else-if="activeCenter === 'stock'" class="center-panel">
      <div class="section-head">
        <div>
          <span class="eyebrow">科室库存</span>
          <h2>余额、在途与执行耗用</h2>
        </div>
        <div class="head-actions">
          <el-button v-if="canOpen('stock')" @click="emit('goTab', 'stock')">查看全部库存</el-button>
          <el-button v-if="canCount" type="primary" @click="emit('workflow', 'controls')">盘点/退库</el-button>
        </div>
      </div>

      <div class="metric-strip">
        <div>
          <span>中央可用</span
          ><strong>{{ extendedDataReady && hasCentralBalance ? formatQuantity(centralAvailable) : "—" }}</strong>
        </div>
        <div>
          <span>本科可用</span
          ><strong>{{ extendedDataReady && hasDepartmentBalance ? formatQuantity(departmentAvailable) : "—" }}</strong>
        </div>
        <div>
          <span>在途</span><strong>{{ extendedDataReady && hasTransitBalance ? formatQuantity(inTransitTotal) : "—" }}</strong>
        </div>
        <div :class="{ danger: automation.failed }">
          <span>自动耗用失败</span><strong>{{ extendedDataReady ? automation.failed : "—" }}</strong>
        </div>
      </div>

      <div class="content-grid stock-grid">
        <article class="work-card">
          <header>
            <h3>库位余额</h3>
            <el-tag v-if="!extendedDataReady" type="info" effect="plain">账本待同步</el-tag>
          </header>
          <el-table :data="balances.slice(0, 12)" height="360" empty-text="暂无科室余额">
            <el-table-column prop="locationName" label="库位" min-width="130" />
            <el-table-column prop="itemName" label="物资" min-width="150" />
            <el-table-column label="可用" width="108" align="right">
              <template #default="{ row }"
                ><strong>{{ row.availableQuantity }} {{ row.unit }}</strong></template
              >
            </el-table-column>
            <el-table-column label="在途" width="100" align="right">
              <template #default="{ row }">{{ row.inTransitQuantity || 0 }} {{ row.unit }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag v-if="isLowBalance(row)" type="danger" effect="plain">低库存</el-tag>
                <el-tag v-else-if="row.openingConfirmed === false" type="warning" effect="plain">待确认</el-tag>
                <el-tag v-else type="success" effect="plain">正常</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </article>

        <article class="work-card">
          <header>
            <h3>最近自动耗用</h3>
            <el-badge :value="automation.pending" :hidden="!automation.pending" type="warning" />
          </header>
          <div v-if="consumptions.length" class="consumption-list">
            <button
              v-for="row in consumptions.slice(0, 8)"
              :key="row.id"
              :disabled="!canOpen('packages')"
              @click="emit('goTab', 'packages')"
            >
              <div>
                <strong>{{ row.itemName }}</strong>
                <small>{{ row.departmentName || "未标科室" }} · {{ stageLabel(row.stage) }}</small>
              </div>
              <span>{{ row.quantity }} {{ row.unit }}</span>
              <el-tag :type="consumptionTag(row.status)" effect="plain">{{ consumptionLabel(row.status) }}</el-tag>
            </button>
          </div>
          <el-empty v-else :description="extendedDataReady ? '暂无自动耗用记录' : '执行耗用接口未就绪'" :image-size="64" />
        </article>
      </div>
    </section>

    <section v-else-if="activeCenter === 'exceptions'" class="center-panel">
      <div class="section-head">
        <div>
          <span class="eyebrow danger-text">异常中心</span>
          <h2>先处理阻断项</h2>
        </div>
        <div class="head-actions">
          <el-button v-if="canOpen('trace')" @click="emit('goTab', 'trace')">追溯流水</el-button>
          <el-button v-if="canCount && canOpen('controls')" type="primary" @click="emit('goTab', 'controls')">
            盘点处置
          </el-button>
        </div>
      </div>

      <div class="exception-summary">
        <button @click="exceptionFilter = 'critical'">
          <span>紧急</span><strong>{{ exceptionCounts.critical }}</strong>
        </button>
        <button @click="exceptionFilter = 'warning'">
          <span>关注</span><strong>{{ exceptionCounts.warning }}</strong>
        </button>
        <button @click="exceptionFilter = 'retryable'">
          <span>可重试</span><strong>{{ exceptionCounts.retryable }}</strong>
        </button>
        <button @click="exceptionFilter = 'all'">
          <span>全部未闭环</span><strong>{{ exceptionCounts.open }}</strong>
        </button>
      </div>

      <article class="work-card">
        <header>
          <h3>异常待办</h3>
          <el-segmented v-model="exceptionFilter" :options="exceptionFilterOptions" size="small" />
        </header>
        <el-table :data="filteredExceptions" empty-text="当前无异常">
          <el-table-column label="级别" width="96">
            <template #default="{ row }">
              <el-tag :type="exceptionTag(row.severity)" effect="plain">{{ exceptionSeverityLabel(row.severity) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="type" label="类型" width="130" />
          <el-table-column prop="departmentName" label="科室" width="130" />
          <el-table-column prop="itemName" label="物资" min-width="140" />
          <el-table-column prop="message" label="原因" min-width="260" show-overflow-tooltip />
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ row.occurredAt || "-" }}</template>
          </el-table-column>
          <el-table-column label="处理" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openException(row)">{{ row.retryable ? "处理/重试" : "查看" }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </article>
    </section>

    <section v-else class="center-panel">
      <div class="section-head">
        <div>
          <span class="eyebrow">管理分析</span>
          <h2>科室耗材与周建议</h2>
        </div>
        <el-button v-if="canOpen('executive')" @click="emit('goTab', 'executive')">管理看板</el-button>
      </div>

      <div class="content-grid analysis-grid">
        <article class="work-card report-card">
          <header>
            <h3>科室耗材使用报表</h3>
            <el-tag v-if="!canReport" type="info" effect="plain">只读权限不足</el-tag>
          </header>
          <el-form :model="reportForm" label-position="top" class="report-filters">
            <el-form-item label="日期范围">
              <el-date-picker
                v-model="reportForm.dateRange"
                type="daterange"
                value-format="YYYY-MM-DD"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                range-separator="至"
              />
            </el-form-item>
            <el-form-item label="科室">
              <el-select v-model="reportForm.departmentIds" multiple collapse-tags clearable placeholder="全部科室">
                <el-option v-for="item in departmentOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="分类">
              <el-select v-model="reportForm.category" clearable placeholder="全部分类">
                <el-option v-for="item in categoryOptions" :key="item" :label="item" :value="item" />
              </el-select>
            </el-form-item>
            <el-form-item label="物资">
              <el-select v-model="reportForm.itemId" clearable filterable placeholder="全部物资">
                <el-option v-for="item in itemOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="执行环节">
              <el-select v-model="reportForm.stage" clearable placeholder="全部环节">
                <el-option v-for="item in stageOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
          </el-form>
          <div class="report-actions">
            <el-button
              type="primary"
              :loading="reportLoading === 'pdf'"
              :disabled="!canReport || Boolean(reportLoading)"
              @click="downloadReport('pdf')"
            >
              导出 A4 PDF
            </el-button>
            <el-button
              :loading="reportLoading === 'xlsx'"
              :disabled="!canReport || Boolean(reportLoading)"
              @click="downloadReport('xlsx')"
            >
              导出 XLSX
            </el-button>
            <span>实际耗用与调拨分列</span>
          </div>
        </article>

        <article class="work-card suggestion-card">
          <header>
            <h3>本周建议</h3>
            <el-badge :value="weeklySuggestions.length" :hidden="!weeklySuggestions.length" type="primary" />
          </header>
          <div v-if="weeklySuggestions.length" class="suggestion-list">
            <div v-for="row in weeklySuggestions.slice(0, 10)" :key="row.id">
              <div>
                <strong>{{ row.itemName }}</strong>
                <small>{{ row.departmentName }} · 近期待耗 {{ row.actualConsumption }} {{ row.unit }}</small>
              </div>
              <span>建议 {{ row.suggestedQuantity }} {{ row.unit }}</span>
            </div>
          </div>
          <el-empty v-else :description="weeklySuggestionsReady ? '暂无周建议' : '周建议接口未就绪'" :image-size="64" />
          <el-button v-if="canOpen('weekly')" class="full-button" @click="emit('goTab', 'weekly')">查看周计划</el-button>
        </article>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import type {
  DepartmentUsageReportParams,
  InventoryConsumptionRecord,
  InventoryException,
  InventoryExceptionSeverity,
  InventoryLocationBalance,
  InventoryWeeklySuggestion,
  InventoryWorkbench
} from "@/api/modules/inventory";
import type { InventoryRequest } from "@/api/modules/inventory";

export type InventoryCenterKey = "flow" | "stock" | "exceptions" | "analysis";
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
  activeCenter: InventoryCenterKey;
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
  "update:activeCenter": [center: InventoryCenterKey];
  goTab: [tab: string];
  workflow: [action: WorkflowAction];
  openTodo: [row: TodoRow];
  downloadReport: [params: DepartmentUsageReportParams];
}>();

const canOpen = (tab: string) => props.accessibleTabs.includes(tab);
const flow = computed(() => ({
  pendingInbound: props.workbench?.workflow?.pendingInbound ?? 0,
  pendingApproval: props.workbench?.workflow?.pendingApproval ?? props.fallbackPendingApproval,
  pendingIssue: props.workbench?.workflow?.pendingIssue ?? props.fallbackPendingIssue,
  inTransit: props.workbench?.workflow?.inTransit ?? props.fallbackPendingReceipt,
  pendingReceipt: props.workbench?.workflow?.pendingReceipt ?? props.fallbackPendingReceipt
}));
const automation = computed(() => ({
  pending: props.workbench?.automation?.pending ?? 0,
  succeededToday: props.workbench?.automation?.succeededToday ?? 0,
  failed: props.workbench?.automation?.failed ?? 0,
  reversalPending: props.workbench?.automation?.reversalPending ?? 0
}));
const hasCentralBalance = computed(
  () => props.workbench?.centralAvailable !== undefined || props.balances.some(row => row.locationType === "central")
);
const hasDepartmentBalance = computed(
  () => props.workbench?.departmentAvailable !== undefined || props.balances.some(row => row.locationType === "department")
);
const hasTransitBalance = computed(() => props.balances.some(row => row.locationType === "transit"));
const centralAvailable = computed(
  () =>
    props.workbench?.centralAvailable ??
    props.balances.filter(row => row.locationType === "central").reduce((sum, row) => sum + Number(row.availableQuantity || 0), 0)
);
const departmentAvailable = computed(
  () =>
    props.workbench?.departmentAvailable ??
    props.balances
      .filter(row => row.locationType === "department")
      .reduce((sum, row) => sum + Number(row.availableQuantity || 0), 0)
);
const inTransitTotal = computed(() => props.balances.reduce((sum, row) => sum + Number(row.inTransitQuantity || 0), 0));

const exceptionFilter = ref("all");
const exceptionFilterOptions = [
  { label: "全部", value: "all" },
  { label: "紧急", value: "critical" },
  { label: "关注", value: "warning" },
  { label: "可重试", value: "retryable" }
];
const openExceptions = computed(() => props.exceptions.filter(row => !["resolved", "ignored"].includes(row.status)));
const exceptionCounts = computed(() => ({
  open: openExceptions.value.length,
  critical: openExceptions.value.filter(row => row.severity === "critical").length,
  warning: openExceptions.value.filter(row => row.severity === "warning").length,
  retryable: openExceptions.value.filter(row => row.retryable).length
}));
const filteredExceptions = computed(() => {
  if (exceptionFilter.value === "all") return openExceptions.value;
  if (exceptionFilter.value === "retryable") return openExceptions.value.filter(row => row.retryable);
  return openExceptions.value.filter(row => row.severity === exceptionFilter.value);
});

const centerCards = computed(() => [
  {
    key: "flow" as const,
    title: "业务流转",
    value: flow.value.pendingApproval + flow.value.pendingIssue + flow.value.inTransit,
    badge: "审核 · 配发 · 签收",
    tone: "primary"
  },
  {
    key: "stock" as const,
    title: "科室库存",
    value: props.balances.length,
    badge: `在途 ${formatQuantity(inTransitTotal.value)}`,
    tone: "success"
  },
  {
    key: "exceptions" as const,
    title: "异常中心",
    value: exceptionCounts.value.open || props.fallbackLowStock + props.fallbackExpirySoon,
    badge: "缺货 · 失败 · 临期",
    tone: "danger"
  },
  {
    key: "analysis" as const,
    title: "管理分析",
    value: props.weeklySuggestions.length,
    badge: "周建议 · 科室报表",
    tone: "info"
  }
]);

const flowNodes = computed(() => [
  {
    key: "inbound",
    label: "入库",
    value: flow.value.pendingInbound,
    pending: flow.value.pendingInbound > 0,
    type: "primary" as const,
    tab: "stock"
  },
  {
    key: "approve",
    label: "申领审批",
    value: flow.value.pendingApproval,
    pending: flow.value.pendingApproval > 0,
    type: "warning" as const,
    tab: "requests"
  },
  {
    key: "issue",
    label: "配发/在途",
    value: flow.value.pendingIssue + flow.value.inTransit,
    pending: flow.value.pendingIssue + flow.value.inTransit > 0,
    type: "primary" as const,
    tab: "requests"
  },
  {
    key: "receive",
    label: "科室签收",
    value: flow.value.pendingReceipt,
    pending: flow.value.pendingReceipt > 0,
    type: "success" as const,
    tab: "requests"
  }
]);

const reportForm = reactive({
  dateRange: (() => {
    const now = new Date();
    const toDate = (value: Date) => {
      const year = value.getFullYear();
      const month = String(value.getMonth() + 1).padStart(2, "0");
      const day = String(value.getDate()).padStart(2, "0");
      return `${year}-${month}-${day}`;
    };
    return [toDate(new Date(now.getFullYear(), now.getMonth(), 1)), toDate(now)];
  })(),
  departmentIds: [] as string[],
  category: "",
  itemId: "",
  stage: ""
});
const stageOptions = [
  { label: "检查", value: "INSPECTION" },
  { label: "中药/治疗", value: "TCM" },
  { label: "医生治疗", value: "DOCTOR" },
  { label: "手术", value: "SURGERY" }
];

const formatQuantity = (value: number) => Number(value || 0).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
const isLowBalance = (value: unknown) => {
  const row = value as InventoryLocationBalance;
  return row.lowStockThreshold !== undefined && Number(row.availableQuantity || 0) <= Number(row.lowStockThreshold || 0);
};
const stageLabel = (stage?: string) => stageOptions.find(item => item.value === stage)?.label || stage || "未标环节";
const consumptionLabel = (status: InventoryConsumptionRecord["status"]) =>
  ({ pending: "待处理", succeeded: "已扣减", failed: "失败", reversed: "已冲销", partially_reversed: "部分冲销" })[status];
const consumptionTag = (status: InventoryConsumptionRecord["status"]): TagLevel =>
  status === "failed" ? "danger" : status === "pending" ? "warning" : status.includes("reversed") ? "info" : "success";
const exceptionTag = (severity: InventoryExceptionSeverity): TagLevel =>
  severity === "critical" ? "danger" : severity === "warning" ? "warning" : "info";
const exceptionSeverityLabel = (severity: InventoryExceptionSeverity) =>
  ({ critical: "紧急", warning: "关注", info: "提示" })[severity];
const openException = (value: unknown) => {
  const row = value as InventoryException;
  const type = row.type.toUpperCase();
  const stockRelated = ["STOCK", "SHORTAGE", "BATCH", "EXPIRY"].some(key => type.includes(key));
  const nextTab = stockRelated || /库存|缺货|批次|临期/.test(row.type) ? "stock" : "packages";
  if (canOpen(nextTab)) emit("goTab", nextTab);
};
const downloadReport = (format: "pdf" | "xlsx") => {
  if (reportForm.dateRange.length !== 2) return;
  emit("downloadReport", {
    format,
    from: reportForm.dateRange[0],
    to: reportForm.dateRange[1],
    departmentIds: reportForm.departmentIds,
    category: reportForm.category,
    itemId: reportForm.itemId,
    stage: reportForm.stage
  });
};
</script>

<style scoped lang="scss">
.operations-hub {
  display: grid;
  gap: 12px;
}

.center-nav {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;

  button {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 4px 10px;
    min-height: 84px;
    padding: 13px 14px;
    text-align: left;
    cursor: pointer;
    background: #ffffff;
    border: 1px solid var(--inventory-line);
    border-radius: 10px;
    transition: 0.16s ease;

    &:hover,
    &.active {
      border-color: rgb(8 118 111 / 48%);
      box-shadow: 0 5px 18px rgb(15 23 42 / 8%);
      transform: translateY(-1px);
    }

    &.active {
      box-shadow: inset 0 -4px 0 var(--inventory-primary);
    }

    &.danger strong {
      color: var(--inventory-danger);
    }

    span,
    small {
      color: var(--inventory-muted);
    }

    span {
      font-weight: 700;
    }

    strong {
      grid-row: 1 / span 2;
      grid-column: 2;
      align-self: center;
      color: var(--inventory-primary);
      font-size: 30px;
      font-variant-numeric: tabular-nums;
    }

    small {
      font-size: 12px;
    }
  }
}

.center-panel,
.work-card {
  background: #ffffff;
  border: 1px solid var(--inventory-line);
  border-radius: 10px;
}

.center-panel {
  display: grid;
  gap: 14px;
  padding: 16px;
}

.section-head,
.work-card > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.section-head {
  h2 {
    margin: 2px 0 0;
    font-size: 20px;
  }
}

.eyebrow {
  color: var(--inventory-primary);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.danger-text {
  color: var(--inventory-danger);
}

.head-actions {
  display: flex;
  gap: 8px;
}

.flow-lane {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  overflow: hidden;
  background: #f7fafb;
  border: 1px solid var(--inventory-line-soft);
  border-radius: 9px;

  button {
    position: relative;
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: 10px;
    padding: 14px;
    text-align: left;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-right: 1px solid var(--inventory-line-soft);

    &:hover {
      background: var(--inventory-primary-soft);
    }

    &:disabled {
      cursor: not-allowed;
      opacity: 0.5;
    }

    &:last-child {
      border-right: 0;
    }

    div {
      display: grid;
      gap: 2px;
    }

    small {
      color: var(--inventory-muted);
    }

    strong {
      color: var(--inventory-text);
      font-size: 22px;
    }
  }
}

.node-index {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  color: #ffffff;
  font-weight: 800;
  background: var(--inventory-primary);
  border-radius: 50%;
}

.node-arrow {
  position: absolute;
  right: -8px;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  color: var(--inventory-primary);
  background: #ffffff;
  border-radius: 50%;
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(320px, 0.75fr);
  gap: 12px;
}

.stock-grid,
.analysis-grid {
  grid-template-columns: minmax(0, 1.35fr) minmax(340px, 0.65fr);
}

.work-card {
  min-width: 0;
  padding: 13px;

  > header {
    min-height: 30px;
    margin-bottom: 10px;

    h3 {
      margin: 0;
      font-size: 16px;
    }
  }
}

.todo-list,
.quick-actions,
.consumption-list,
.suggestion-list {
  display: grid;
  gap: 8px;
}

.todo-list button,
.consumption-list button {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px;
  text-align: left;
  cursor: pointer;
  background: #ffffff;
  border: 1px solid var(--inventory-line-soft);
  border-radius: 8px;

  &:hover {
    background: #f7fcfb;
    border-color: rgb(8 118 111 / 25%);
  }

  div {
    min-width: 0;
  }

  strong,
  small {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    margin-top: 3px;
    color: var(--inventory-muted);
  }

  > span {
    color: var(--inventory-primary);
    font-weight: 700;
  }
}

.consumption-list button {
  grid-template-columns: minmax(0, 1fr) auto auto;
}

.quick-actions {
  grid-template-columns: repeat(2, minmax(0, 1fr));

  button {
    display: grid;
    gap: 4px;
    min-height: 72px;
    padding: 11px;
    text-align: left;
    cursor: pointer;
    background: #f8fbfb;
    border: 1px solid var(--inventory-line-soft);
    border-radius: 8px;

    &:hover {
      border-color: rgb(8 118 111 / 34%);
    }

    strong {
      color: var(--inventory-primary);
    }

    span {
      color: var(--inventory-muted);
      font-size: 12px;
    }
  }
}

.metric-strip,
.exception-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--inventory-line-soft);
  border-radius: 9px;

  > div,
  > button {
    display: grid;
    gap: 4px;
    padding: 12px 14px;
    text-align: left;
    background: #f8fafb;
    border: 0;
    border-right: 1px solid var(--inventory-line-soft);

    &:last-child {
      border-right: 0;
    }

    span {
      color: var(--inventory-muted);
      font-size: 12px;
    }

    strong {
      color: var(--inventory-primary);
      font-size: 24px;
      font-variant-numeric: tabular-nums;
    }

    &.danger strong {
      color: var(--inventory-danger);
    }
  }

  > button {
    cursor: pointer;

    &:hover {
      background: var(--inventory-primary-soft);
    }
  }
}

.exception-summary > button:first-child strong {
  color: var(--inventory-danger);
}

.report-filters {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 12px;

  :deep(.el-form-item) {
    margin-bottom: 12px;
  }

  :deep(.el-select),
  :deep(.el-date-editor) {
    width: 100%;
  }
}

.report-actions {
  display: flex;
  align-items: center;
  gap: 8px;

  span {
    margin-left: auto;
    color: var(--inventory-muted);
    font-size: 12px;
  }
}

.suggestion-list > div {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid var(--inventory-line-soft);

  &:last-child {
    border-bottom: 0;
  }

  strong,
  small {
    display: block;
  }

  small {
    margin-top: 3px;
    color: var(--inventory-muted);
  }

  > span {
    color: var(--inventory-primary);
    font-weight: 700;
  }
}

.full-button {
  width: 100%;
  margin-top: 12px;
}

@media (max-width: 1080px) {
  .center-nav,
  .flow-lane,
  .metric-strip,
  .exception-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .content-grid,
  .stock-grid,
  .analysis-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .center-nav,
  .flow-lane,
  .metric-strip,
  .exception-summary,
  .report-filters,
  .quick-actions {
    grid-template-columns: 1fr;
  }

  .section-head,
  .report-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .head-actions {
    flex-wrap: wrap;
  }

  .report-actions span {
    margin-left: 0;
  }
}
</style>
