<template>
  <div class="pane-grid">
    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>当前库存</h2>
          <p>按物资汇总所有批次库存，低库存和临期会突出显示。</p>
        </div>
        <el-button v-if="canExport" type="primary" plain :icon="Download" @click="$emit('export')">导出</el-button>
      </div>
      <div class="table-toolbar">
        <el-input :model-value="keyword" clearable placeholder="搜索物资、规格、位置" @update:model-value="updateKeyword" />
        <el-select :model-value="category" clearable placeholder="分类" @update:model-value="updateCategory">
          <el-option v-for="item in categoryOptions" :key="item" :label="item" :value="item" />
        </el-select>
        <el-select :model-value="status" clearable placeholder="状态" @update:model-value="updateStatus">
          <el-option label="低库存" value="low" />
          <el-option label="敏感物资" value="sensitive" />
        </el-select>
      </div>
      <el-table :data="rows" border height="420">
        <el-table-column prop="name" label="物资" min-width="150" />
        <el-table-column prop="category" label="分类" width="110" />
        <el-table-column prop="spec" label="规格" min-width="130" />
        <el-table-column prop="stock" label="库存" width="110">
          <template #default="{ row }">
            <strong :class="{ danger: row.lowStock }">{{ row.stock }} {{ row.unit }}</strong>
          </template>
        </el-table-column>
        <el-table-column prop="lowStockThreshold" label="预警线" width="100" />
        <el-table-column prop="location" label="存放位置" width="130" />
        <el-table-column label="状态" width="160">
          <template #default="{ row }">
            <el-tag v-if="row.lowStock" type="danger" effect="plain">低库存</el-tag>
            <el-tag v-else type="success" effect="plain">正常</el-tag>
            <el-tag v-if="row.sensitive" class="ml6" type="warning" effect="plain">敏感</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canManage" link type="primary" @click="$emit('inbound', row.item)">入库</el-button>
            <el-button v-if="canManage" link @click="$emit('edit', row.item)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>批次与效期</h2>
          <p>用于 P2 批号、效期、临期追溯。</p>
        </div>
      </div>
      <el-table :data="batchRows" border height="420">
        <el-table-column prop="itemName" label="物资" min-width="140" />
        <el-table-column prop="batchNo" label="批号" width="120" />
        <el-table-column prop="quantity" label="数量" width="90" />
        <el-table-column prop="expiryDate" label="有效期" width="120" />
        <el-table-column prop="location" label="位置" width="120" />
        <el-table-column label="提醒" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.expired" type="danger" effect="plain">已过期</el-tag>
            <el-tag v-else-if="row.expirySoon" type="warning" effect="plain">临期</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { Download } from "@element-plus/icons-vue";
import type { InventoryItem } from "@/api/modules/inventory";
import type { InventoryBatchRow, InventoryStockRow } from "../composables/useInventoryManage";

defineProps<{
  rows: InventoryStockRow[];
  batchRows: InventoryBatchRow[];
  categoryOptions: string[];
  keyword: string;
  category: string;
  status: string;
  canExport: boolean;
  canManage: boolean;
}>();

const emit = defineEmits<{
  export: [];
  inbound: [item: InventoryItem];
  edit: [item: InventoryItem];
  "update:keyword": [value: string];
  "update:category": [value: string];
  "update:status": [value: string];
}>();

const updateKeyword = (value: string | number) => emit("update:keyword", String(value || ""));
const updateCategory = (value: string | number) => emit("update:category", String(value || ""));
const updateStatus = (value: string | number) => emit("update:status", String(value || ""));
</script>

<style scoped lang="scss">
.pane-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
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

.table-toolbar {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(140px, 180px) minmax(140px, 180px);
  gap: 8px;
  margin-bottom: 10px;
}

.danger {
  color: var(--el-color-danger);
}

.ml6 {
  margin-left: 6px;
}

@media (max-width: 1080px) {
  .pane-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .table-toolbar {
    grid-template-columns: 1fr;
  }
}
</style>
