<template>
  <div class="control-grid">
    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>盘点差异</h2>
          <p>账实不一致必须记录原因，形成 P2 盘点追溯。</p>
        </div>
        <el-button v-if="canCreateCount" type="primary" :icon="Plus" @click="$emit('createCount')">新增盘点</el-button>
      </div>
      <el-table :data="countRows" border height="360">
        <el-table-column prop="countedAt" label="时间" width="160" />
        <el-table-column prop="itemName" label="物资" min-width="150" />
        <el-table-column prop="bookQuantity" label="账面" width="90" />
        <el-table-column prop="actualQuantity" label="实盘" width="90" />
        <el-table-column prop="differenceQuantity" label="差异" width="90" />
        <el-table-column prop="operator" label="盘点人" width="110" />
        <el-table-column prop="reason" label="原因" min-width="180" />
      </el-table>
    </section>

    <section class="panel quick-control">
      <div class="panel-head">
        <div>
          <h2>退回 / 报废</h2>
          <p>用于记录科室退回、库存报废和损耗说明。</p>
        </div>
      </div>
      <el-form ref="returnFormRef" :model="returnForm" :rules="returnFormRules" label-width="96px" status-icon>
        <el-form-item label="类型">
          <el-segmented :model-value="returnForm.type" :options="returnTypeOptions" @update:model-value="updateType" />
        </el-form-item>
        <el-form-item label="物资" prop="itemId">
          <el-select :model-value="returnForm.itemId" filterable placeholder="请选择物资" @update:model-value="updateItemId">
            <el-option v-for="item in items" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="批次">
          <el-select
            :model-value="returnForm.batchId"
            clearable
            filterable
            placeholder="自动选择或指定批次"
            @update:model-value="updateBatchId"
          >
            <el-option
              v-for="batch in batchesForItem(returnForm.itemId)"
              :key="batch.id"
              :label="batchLabel(batch)"
              :value="batch.id"
            />
          </el-select>
          <div class="form-hint">退回时可不选批次，系统会自动落到可用批次；没有库存批次时会补录一条退回批次。</div>
        </el-form-item>
        <el-form-item label="数量" prop="quantity">
          <el-input-number :model-value="returnForm.quantity" :min="0" :precision="2" @update:model-value="updateQuantity" />
        </el-form-item>
        <el-form-item label="科室">
          <el-select
            :model-value="returnForm.department"
            filterable
            allow-create
            placeholder="请选择或输入科室"
            @update:model-value="updateDepartment"
          >
            <el-option v-for="item in departmentOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="原因" prop="reason">
          <el-input
            :model-value="returnForm.reason"
            type="textarea"
            :rows="3"
            placeholder="例如：科室未使用退回 / 临期报废 / 破损损耗"
            @update:model-value="updateReason"
          />
        </el-form-item>
        <el-form-item>
          <el-button v-if="canSubmitReturnOrScrap" type="primary" :loading="saving" @click="$emit('submitReturn')">
            保存变更
          </el-button>
        </el-form-item>
      </el-form>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { Plus } from "@element-plus/icons-vue";
import type { FormInstance, FormRules } from "element-plus";
import type { InventoryBatch, InventoryCount, InventoryItem, ReturnOrScrapParams } from "@/api/modules/inventory";

type CountRow = InventoryCount & { itemName: string };
type ReturnTypeOption = {
  label: string;
  value: string;
  auth: string;
};

const props = defineProps<{
  countRows: CountRow[];
  items: InventoryItem[];
  returnForm: ReturnOrScrapParams;
  returnFormRules: FormRules;
  returnTypeOptions: ReturnTypeOption[];
  departmentOptions: string[];
  canCreateCount: boolean;
  canSubmitReturnOrScrap: boolean;
  saving: boolean;
  batchesForItem: (itemId?: string) => InventoryBatch[];
  batchLabel: (batch: InventoryBatch) => string;
}>();

const emit = defineEmits<{
  createCount: [];
  submitReturn: [];
  "update:returnForm": [value: ReturnOrScrapParams];
}>();

const returnFormRef = ref<FormInstance>();

const updateReturnForm = (patch: Partial<ReturnOrScrapParams>) => {
  emit("update:returnForm", { ...props.returnForm, ...patch });
};

const updateType = (value: string | number) => updateReturnForm({ type: value as ReturnOrScrapParams["type"] });
const updateItemId = (value: string | number) => updateReturnForm({ itemId: String(value || ""), batchId: "" });
const updateBatchId = (value: string | number) => updateReturnForm({ batchId: String(value || "") });
const updateQuantity = (value: number | undefined) => updateReturnForm({ quantity: Number(value || 0) });
const updateDepartment = (value: string | number) => updateReturnForm({ department: String(value || "") });
const updateReason = (value: string | number) => updateReturnForm({ reason: String(value || "") });

defineExpose({
  validateReturnForm: () => returnFormRef.value?.validate().catch(() => false)
});
</script>

<style scoped lang="scss">
.control-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(360px, 0.72fr);
  gap: 12px;
}

.panel {
  padding: 13px 14px;
  background: var(--inventory-panel);
  border: 1px solid var(--inventory-line);
  border-radius: 8px;
  box-shadow: 0 1px 1px rgb(15 23 42 / 3%);
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

.quick-control {
  align-self: start;
}

.form-hint {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.45;
}

@media (max-width: 1080px) {
  .control-grid {
    grid-template-columns: 1fr;
  }
}
</style>
