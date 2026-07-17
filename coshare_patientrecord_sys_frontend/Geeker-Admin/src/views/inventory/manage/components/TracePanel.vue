<template>
  <section class="panel">
    <div class="panel-head">
      <div>
        <h2>库存流水与操作日志</h2>
        <p>按时间倒序记录所有库存变化和关键操作。</p>
      </div>
      <el-button v-if="canExport" plain :icon="Download" @click="$emit('export')">导出</el-button>
    </div>
    <div class="table-toolbar wide">
      <el-input :model-value="keyword" clearable placeholder="搜索物资、科室、经办人、原因" @update:model-value="updateKeyword" />
      <el-select :model-value="type" clearable placeholder="类型" @update:model-value="updateType">
        <el-option label="入库" value="inbound" />
        <el-option label="发放" value="issue" />
        <el-option label="退回" value="return" />
        <el-option label="报废" value="scrap" />
        <el-option label="盘点" value="count" />
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
    <el-table :data="rows" border>
      <el-table-column prop="createdAt" label="时间" width="160" />
      <el-table-column prop="typeLabel" label="类型" width="110" />
      <el-table-column prop="itemName" label="物资" min-width="150" />
      <el-table-column prop="quantity" label="数量变化" width="110" />
      <el-table-column prop="department" label="科室" width="120" />
      <el-table-column prop="operator" label="经办人" width="120" />
      <el-table-column prop="reason" label="原因/摘要" min-width="260" />
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { Download } from "@element-plus/icons-vue";
import type { InventoryMovement } from "@/api/modules/inventory";

type TraceRow = InventoryMovement & {
  typeLabel: string;
  itemName: string;
};

defineProps<{
  rows: TraceRow[];
  keyword: string;
  type: string;
  department: string;
  dateRange: string[];
  departmentOptions: string[];
  canExport: boolean;
}>();

const emit = defineEmits<{
  export: [];
  "update:keyword": [value: string];
  "update:type": [value: string];
  "update:department": [value: string];
  "update:dateRange": [value: string[]];
}>();

const updateKeyword = (value: string | number) => emit("update:keyword", String(value || ""));
const updateType = (value: string | number) => emit("update:type", String(value || ""));
const updateDepartment = (value: string | number) => emit("update:department", String(value || ""));
const updateDateRange = (value: string[] | null) => emit("update:dateRange", value || []);
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
    grid-template-columns: minmax(240px, 1fr) minmax(130px, 160px) minmax(140px, 180px) minmax(260px, 320px);
  }
}

@media (max-width: 760px) {
  .table-toolbar,
  .table-toolbar.wide {
    grid-template-columns: 1fr;
  }
}
</style>
