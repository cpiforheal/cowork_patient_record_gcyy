<template>
  <section class="panel">
    <div class="panel-head">
      <div>
        <h2>科室申领闭环</h2>
        <p>提交、审核、发放、签收全部留痕，避免口头领用和纸质单据断链。</p>
      </div>
      <el-button v-if="canCreate" type="primary" :icon="Plus" @click="$emit('create')">新增申领</el-button>
    </div>

    <div class="table-toolbar wide">
      <el-input :model-value="keyword" clearable placeholder="搜索物资、科室、理由、负责人" @update:model-value="updateKeyword" />
      <el-select :model-value="status" clearable placeholder="状态" @update:model-value="updateStatus">
        <el-option label="待审核" value="pending" />
        <el-option label="待发放" value="approved" />
        <el-option label="部分发放" value="partially_issued" />
        <el-option label="待签收" value="issued" />
        <el-option label="已签收" value="received" />
        <el-option label="已驳回" value="rejected" />
        <el-option label="已撤销" value="cancelled" />
        <el-option label="已作废" value="void" />
      </el-select>
      <el-select :model-value="department" clearable filterable placeholder="科室" @update:model-value="updateDepartment">
        <el-option v-for="item in departmentOptions" :key="item" :label="item" :value="item" />
      </el-select>
      <el-date-picker
        :model-value="dateRange"
        value-format="YYYY-MM-DD"
        type="daterange"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        @update:model-value="updateDateRange"
      />
    </div>

    <el-table :data="rows" border :row-class-name="rowClassName">
      <el-table-column prop="createdAt" label="申请时间" width="160" />
      <el-table-column prop="department" label="科室" width="120" />
      <el-table-column prop="itemSummary" label="物资明细" min-width="220">
        <template #default="{ row }">
          <div class="request-line-summary">
            <strong>{{ row.itemSummary || row.itemName }}</strong>
            <small>{{ row.itemCount || row.lines?.length || 1 }} 项，合计 {{ row.quantity }}</small>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="发放" width="120">
        <template #default="{ row }">{{ row.issuedQuantity || 0 }} / {{ row.quantity }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="理由" min-width="220" />
      <el-table-column prop="owner" label="负责人" width="110" />
      <el-table-column label="闭环进度" min-width="250">
        <template #default="{ row }">
          <el-steps
            class="request-steps"
            :active="requestStepActive(row.status)"
            finish-status="success"
            process-status="process"
            simple
          >
            <el-step title="审核" />
            <el-step title="发放" />
            <el-step title="签收" />
          </el-steps>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="requestStatusMeta(row.status).type" effect="plain">{{ requestStatusMeta(row.status).label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'pending' && canApprove" link type="primary" @click="emitApprove(row)">审核</el-button>
          <el-button v-if="row.status === 'approved' && canIssue" link type="primary" @click="emitIssue(row)">发放</el-button>
          <el-button v-if="row.status === 'partially_issued' && canIssue" link type="warning" @click="emitIssue(row)">
            继续发
          </el-button>
          <el-button v-if="row.status === 'issued' && canReceive" link type="success" @click="emitReceive(row)">签收</el-button>
          <el-button v-if="row.status === 'pending' && canApprove" link type="warning" @click="emitReject(row)">驳回</el-button>
          <el-button v-if="row.status === 'pending' && canCancel" link type="info" @click="emitCancel(row)">撤销</el-button>
          <el-button v-if="['pending', 'approved'].includes(row.status) && canApprove" link type="danger" @click="emitVoid(row)">
            作废
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { Plus } from "@element-plus/icons-vue";
import type { InventoryRequestRow } from "../composables/useInventoryManage";

type InventoryTagType = "success" | "warning" | "info" | "primary" | "danger";

defineProps<{
  rows: InventoryRequestRow[];
  departmentOptions: string[];
  keyword: string;
  status: string;
  department: string;
  dateRange: string[];
  canCreate: boolean;
  canApprove: boolean;
  canIssue: boolean;
  canReceive: boolean;
  canCancel: boolean;
  rowClassName: ({ row }: { row: InventoryRequestRow }) => string;
}>();

const emit = defineEmits<{
  create: [];
  approve: [row: InventoryRequestRow];
  issue: [row: InventoryRequestRow];
  receive: [row: InventoryRequestRow];
  reject: [row: InventoryRequestRow];
  cancel: [row: InventoryRequestRow];
  void: [row: InventoryRequestRow];
  "update:keyword": [value: string];
  "update:status": [value: string];
  "update:department": [value: string];
  "update:dateRange": [value: string[]];
}>();

const requestStatusMeta = (status: string): { label: string; type: InventoryTagType } => {
  const meta = {
    pending: { label: "待审核", type: "warning" },
    approved: { label: "待发放", type: "primary" },
    partially_issued: { label: "部分发放", type: "warning" },
    issued: { label: "待签收", type: "success" },
    received: { label: "已签收", type: "info" },
    rejected: { label: "已驳回", type: "danger" },
    cancelled: { label: "已撤销", type: "info" },
    void: { label: "已作废", type: "danger" }
  }[status] || { label: status, type: "info" };
  return meta as { label: string; type: InventoryTagType };
};

const requestStepActive = (status: string) => {
  const stepMap: Record<string, number> = {
    pending: 0,
    approved: 1,
    partially_issued: 2,
    issued: 2,
    received: 3
  };
  return stepMap[status] ?? 0;
};

const updateKeyword = (value: string | number) => emit("update:keyword", String(value || ""));
const updateStatus = (value: string | number) => emit("update:status", String(value || ""));
const updateDepartment = (value: string | number) => emit("update:department", String(value || ""));
const updateDateRange = (value: string[] | null) => emit("update:dateRange", Array.isArray(value) ? value : []);

const toRequestRow = (row: unknown) => row as InventoryRequestRow;
const emitApprove = (row: unknown) => emit("approve", toRequestRow(row));
const emitIssue = (row: unknown) => emit("issue", toRequestRow(row));
const emitReceive = (row: unknown) => emit("receive", toRequestRow(row));
const emitReject = (row: unknown) => emit("reject", toRequestRow(row));
const emitCancel = (row: unknown) => emit("cancel", toRequestRow(row));
const emitVoid = (row: unknown) => emit("void", toRequestRow(row));
</script>

<style scoped lang="scss">
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

.table-toolbar {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(140px, 180px) minmax(140px, 180px);
  gap: 8px;
  margin-bottom: 10px;

  &.wide {
    grid-template-columns: minmax(220px, 1fr) 150px 160px minmax(260px, 320px);
  }
}

.request-steps {
  min-width: 220px;
}

.request-line-summary {
  display: grid;
  gap: 3px;

  strong,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

:deep(.request-steps.el-steps--simple) {
  padding: 6px 8px;
  background: rgb(248 250 252 / 92%);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

:deep(.row-flash) {
  animation: inventory-request-row-flash 1.1s ease;
}

@keyframes inventory-request-row-flash {
  0% {
    background: rgb(220 252 231 / 92%);
  }
  100% {
    background: transparent;
  }
}

@media (max-width: 760px) {
  .table-toolbar,
  .table-toolbar.wide {
    grid-template-columns: 1fr;
  }
}
</style>
