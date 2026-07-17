<template>
  <section class="panel">
    <div class="panel-head">
      <div>
        <h2>物资字典</h2>
        <p>统一名称、规格、单位、批号效期要求，后续申领与统计都从这里选择。</p>
      </div>
      <el-button v-if="canManage" type="primary" :icon="Plus" @click="$emit('create')">新增物资</el-button>
    </div>
    <div class="table-toolbar">
      <el-input :model-value="keyword" clearable placeholder="搜索名称、规格、位置" @update:model-value="updateKeyword" />
      <el-select :model-value="category" clearable placeholder="分类" @update:model-value="updateCategory">
        <el-option v-for="item in categoryOptions" :key="item" :label="item" :value="item" />
      </el-select>
    </div>
    <el-table :data="rows" border>
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="category" label="分类" width="120" />
      <el-table-column prop="spec" label="规格" min-width="140" />
      <el-table-column prop="unit" label="单位" width="90" />
      <el-table-column prop="lowStockThreshold" label="预警线" width="90" />
      <el-table-column label="管理要求" min-width="180">
        <template #default="{ row }">
          <el-tag v-if="row.batchRequired" effect="plain">批号</el-tag>
          <el-tag v-if="row.expiryRequired" class="ml6" effect="plain">效期</el-tag>
          <el-tag v-if="row.sensitive" class="ml6" type="warning" effect="plain">敏感</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="location" label="默认位置" width="140" />
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canManage" link type="primary" @click="editItem(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { Plus } from "@element-plus/icons-vue";
import type { InventoryItem } from "@/api/modules/inventory";

defineProps<{
  rows: InventoryItem[];
  categoryOptions: string[];
  keyword: string;
  category: string;
  canManage: boolean;
}>();

const emit = defineEmits<{
  create: [];
  edit: [row: InventoryItem];
  "update:keyword": [value: string];
  "update:category": [value: string];
}>();

const updateKeyword = (value: string | number) => emit("update:keyword", String(value || ""));
const updateCategory = (value: string | number) => emit("update:category", String(value || ""));
const editItem = (row: unknown) => emit("edit", row as InventoryItem);
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
}

.ml6 {
  margin-left: 6px;
}

@media (max-width: 760px) {
  .table-toolbar {
    grid-template-columns: 1fr;
  }
}
</style>
