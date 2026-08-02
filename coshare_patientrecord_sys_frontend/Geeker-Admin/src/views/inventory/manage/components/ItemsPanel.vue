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
      <el-table-column prop="baseUnit" label="基准单位" width="96" />
      <el-table-column prop="issueUnit" label="领用单位" width="96" />
      <el-table-column prop="normalizationStatus" label="规范化" width="96" />
      <el-table-column prop="lowStockThreshold" label="预警线" width="90" />
      <el-table-column label="管理要求" min-width="180">
        <template #default="{ row }">
          <el-tag v-if="row.batchRequired" effect="plain">批号</el-tag>
          <el-tag v-if="row.expiryRequired" class="ml6" effect="plain">效期</el-tag>
          <el-tag v-if="row.effectiveLifeManaged" class="ml6" effect="plain">效期治理</el-tag>
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

    <el-collapse class="governance-collapse">
      <el-collapse-item title="别名与单位换算" name="mapping-governance">
        <div class="governance-head">
          <span>仅服务映射确认，不改变库存扣减内核。</span>
          <el-button :loading="governanceLoading" @click="$emit('load-governance')">刷新治理数据</el-button>
        </div>
        <div class="governance-grid">
          <section>
            <div class="governance-title">
              <strong>物资别名</strong>
              <el-button v-if="canManageGovernance" link type="primary" :icon="Plus" @click="saveAlias">保存别名</el-button>
            </div>
            <div v-if="canManageGovernance" class="governance-form">
              <el-select v-model="aliasForm.itemId" filterable clearable placeholder="匹配物资">
                <el-option v-for="item in items" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
              <el-input v-model="aliasForm.aliasName" clearable placeholder="别名/原始名称" />
            </div>
            <el-table :data="aliases" border size="small" max-height="260">
              <el-table-column prop="aliasName" label="别名" min-width="150" show-overflow-tooltip />
              <el-table-column prop="itemName" label="匹配物资" min-width="150" show-overflow-tooltip />
              <el-table-column prop="status" label="状态" width="90" />
            </el-table>
          </section>

          <section>
            <div class="governance-title">
              <strong>单位换算</strong>
              <el-button v-if="canManageGovernance" link type="primary" :icon="Plus" @click="saveConversion">保存换算</el-button>
            </div>
            <div v-if="canManageGovernance" class="governance-form conversion-form">
              <el-select v-model="conversionForm.itemId" filterable clearable placeholder="限定物资">
                <el-option v-for="item in items" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
              <el-input v-model="conversionForm.sourceUnit" clearable placeholder="来源单位" />
              <el-input v-model="conversionForm.targetUnit" clearable placeholder="目标单位" />
              <el-input-number v-model="conversionForm.factor" :min="0.000001" :precision="6" controls-position="right" />
            </div>
            <el-table :data="unitConversions" border size="small" max-height="260">
              <el-table-column prop="itemName" label="物资" min-width="140" show-overflow-tooltip />
              <el-table-column prop="sourceUnit" label="来源" width="80" />
              <el-table-column prop="targetUnit" label="目标" width="80" />
              <el-table-column prop="factor" label="系数" width="100" />
              <el-table-column prop="status" label="状态" width="90" />
            </el-table>
          </section>
        </div>
      </el-collapse-item>
    </el-collapse>
  </section>
</template>

<script setup lang="ts">
import { reactive } from "vue";
import { Plus } from "@element-plus/icons-vue";
import type { InventoryItem, InventoryItemAlias, InventoryUnitConversion } from "@/api/modules/inventory";

defineProps<{
  rows: InventoryItem[];
  categoryOptions: string[];
  keyword: string;
  category: string;
  canManage: boolean;
  canManageGovernance: boolean;
  governanceLoading?: boolean;
  items: InventoryItem[];
  aliases: InventoryItemAlias[];
  unitConversions: InventoryUnitConversion[];
}>();

const emit = defineEmits<{
  create: [];
  edit: [row: InventoryItem];
  "update:keyword": [value: string];
  "update:category": [value: string];
  "load-governance": [];
  "save-aliases": [rows: InventoryItemAlias[]];
  "save-unit-conversions": [rows: InventoryUnitConversion[]];
}>();

const aliasForm = reactive<InventoryItemAlias>({
  itemId: "",
  aliasName: "",
  sourceName: "manual",
  status: "active"
});
const conversionForm = reactive<InventoryUnitConversion>({
  itemId: "",
  sourceUnit: "",
  targetUnit: "",
  factor: 1,
  status: "active"
});

const updateKeyword = (value: string | number) => emit("update:keyword", String(value || ""));
const updateCategory = (value: string | number) => emit("update:category", String(value || ""));
const editItem = (row: unknown) => emit("edit", row as InventoryItem);
const saveAlias = () => {
  if (!aliasForm.aliasName.trim()) return;
  emit("save-aliases", [{ ...aliasForm, aliasName: aliasForm.aliasName.trim() }]);
  aliasForm.aliasName = "";
};
const saveConversion = () => {
  if (!conversionForm.sourceUnit.trim() || !conversionForm.targetUnit.trim() || Number(conversionForm.factor) <= 0) return;
  emit("save-unit-conversions", [
    {
      ...conversionForm,
      sourceUnit: conversionForm.sourceUnit.trim(),
      targetUnit: conversionForm.targetUnit.trim(),
      factor: Number(conversionForm.factor)
    }
  ]);
};
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

.governance-collapse {
  margin-top: 12px;
}

.governance-head,
.governance-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  color: var(--inventory-muted);
  font-size: 13px;
}

.governance-title {
  color: var(--inventory-text);
}

.governance-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.governance-form {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) minmax(160px, 1fr);
  gap: 8px;
  margin-bottom: 8px;
}

.conversion-form {
  grid-template-columns: minmax(140px, 1fr) 100px 100px 120px;
}

@media (max-width: 760px) {
  .table-toolbar,
  .governance-grid,
  .governance-form,
  .conversion-form {
    grid-template-columns: 1fr;
  }
}
</style>
