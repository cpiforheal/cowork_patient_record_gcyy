<template>
  <el-dialog v-model="visible" :title="`固定耗材 · ${departmentName || departmentKey}`" width="760px" append-to-body>
    <div class="fixed-shell" v-loading="loading">
      <div class="fixed-head">
        <div>
          <span class="eyebrow">每日固定基线</span>
          <p>{{ date }} · 默认取昨日实际，没有昨日记录时取规则预设</p>
        </div>
        <el-tag :type="statusType" effect="plain">{{ statusLabel }}</el-tag>
      </div>
      <el-table :data="rows" max-height="420">
        <el-table-column prop="itemName" label="耗材" min-width="180" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="80" />
        <el-table-column label="规则预设" width="100" align="right">
          <template #default="{ row }">{{ formatQuantity(row.standardQuantity) }}</template>
        </el-table-column>
        <el-table-column label="昨日实际" width="100" align="right">
          <template #default="{ row }">{{ formatQuantity(row.yesterdayQuantity) }}</template>
        </el-table-column>
        <el-table-column label="今日实际" width="150">
          <template #default="{ row }">
            <el-input-number v-model="row.actualQuantity" :min="0" :precision="2" :step="1" size="small" controls-position="right" :disabled="!editable" />
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="160">
          <template #default="{ row }">
            <el-input v-model="row.exceptionReason" size="small" :disabled="!editable" placeholder="追加时必填" />
          </template>
        </el-table-column>
        <el-table-column width="70" align="center">
          <template #default="{ $index }">
            <el-button v-if="rows[$index].source === 'MANUAL'" text type="danger" :disabled="!editable" @click="removeRow($index)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="fixed-actions">
        <el-button :icon="Plus" :disabled="!editable" @click="openAddItem">临时追加固定耗材</el-button>
        <span class="fixed-hint">{{ rows.length }} 项 · {{ locked ? "已锁定" : "确认后进入同日结审核" }}</span>
      </div>
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button :disabled="!editable" :loading="saving" @click="save">保存草稿</el-button>
      <el-button type="primary" :disabled="!editable" :loading="submitting" @click="submit">提交固定耗材</el-button>
    </template>

    <el-dialog v-model="addVisible" title="临时追加固定耗材" width="520px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="耗材">
          <el-select v-model="addItemId" filterable placeholder="搜索物资" class="fixed-select">
            <el-option v-for="item in availableItems" :key="item.id" :label="`${item.name}（${item.unit}）`" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="addQuantity" :min="0.01" :precision="2" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="addReason" maxlength="200" placeholder="例如：临时清洗、设备消毒" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" @click="addItem">加入固定耗材</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import {
  getInventoryDbApi,
  getInventoryFixedConsumablesApi,
  saveInventoryDepartmentDailyReportApi,
  submitInventoryDepartmentDailyReportApi,
  type InventoryFixedConsumable,
  type InventoryItem
} from "@/api/modules/inventory";

type FixedRow = InventoryFixedConsumable & { exceptionReason?: string };

const props = defineProps<{
  modelValue: boolean;
  departmentKey: string;
  departmentName?: string;
  date: string;
}>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; updated: [] }>();

const visible = computed({
  get: () => props.modelValue,
  set: value => emit("update:modelValue", value)
});
const rows = ref<FixedRow[]>([]);
const reportId = ref("");
const status = ref("DRAFT");
const revision = ref(0);
const locked = ref(false);
const loading = ref(false);
const saving = ref(false);
const submitting = ref(false);
const addVisible = ref(false);
const addItemId = ref("");
const addQuantity = ref(1);
const addReason = ref("");
const allItems = ref<InventoryItem[]>([]);

const editable = computed(() => !locked.value && ["DRAFT", "RETURNED"].includes(status.value));
const statusLabel = computed(() => {
  if (locked.value) return "已锁定";
  if (status.value === "APPROVED") return "已通过";
  if (status.value === "SUBMITTED") return "待审核";
  if (status.value === "RETURNED") return "已驳回";
  return "待确认";
});
const statusType = computed<"success" | "warning" | "danger" | "info">(() => {
  if (status.value === "APPROVED") return "success";
  if (status.value === "RETURNED") return "danger";
  if (status.value === "SUBMITTED") return "warning";
  return "info";
});
const availableItems = computed(() => allItems.value.filter(item => !rows.value.some(row => row.itemId === item.id)));

const load = async () => {
  loading.value = true;
  try {
    const result = await getInventoryFixedConsumablesApi({ departmentKey: props.departmentKey, date: props.date });
    const data = result.data;
    rows.value = (data?.items ?? []).map(item => ({ ...item, actualQuantity: Number(item.actualQuantity || 0) }));
    reportId.value = data?.reportId || "";
    status.value = data?.status || "DRAFT";
    revision.value = data?.revision || 0;
    locked.value = Boolean(data?.locked);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取固定耗材失败");
  } finally {
    loading.value = false;
  }
};

const save = async () => {
  if (!editable.value) return null;
  saving.value = true;
  try {
    const result = await saveInventoryDepartmentDailyReportApi({
      departmentKey: props.departmentKey,
      businessDate: props.date,
      revision: revision.value,
      note: "",
      zeroUsage: false,
      lines: rows.value.map(row => ({
        itemId: row.itemId,
        itemName: row.itemName,
        unit: row.unit,
        presetQuantity: row.standardQuantity,
        actualQuantity: Number(row.actualQuantity || 0),
        source: row.source,
        exceptionReason: row.exceptionReason
      }))
    });
    reportId.value = result.data?.id || reportId.value;
    status.value = result.data?.status || "DRAFT";
    revision.value = result.data?.revision || revision.value + 1;
    emit("updated");
    ElMessage.success("固定耗材已保存");
    return result.data ?? null;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存固定耗材失败");
    return null;
  } finally {
    saving.value = false;
  }
};

const submit = async () => {
  let currentId = reportId.value;
  if (!currentId || status.value === "DRAFT") {
    const saved = await save();
    if (!saved?.id) return;
    currentId = saved.id;
  }
  submitting.value = true;
  try {
    const result = await submitInventoryDepartmentDailyReportApi(currentId);
    reportId.value = result.data?.id || currentId;
    status.value = result.data?.status || "SUBMITTED";
    revision.value = result.data?.revision || revision.value;
    emit("updated");
    ElMessage.success("固定耗材已提交审核");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "提交固定耗材失败");
  } finally {
    submitting.value = false;
  }
};

const openAddItem = async () => {
  if (!allItems.value.length) {
    try {
      const result = await getInventoryDbApi();
      allItems.value = result.data?.items ?? [];
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : "读取物资目录失败");
      return;
    }
  }
  addItemId.value = "";
  addQuantity.value = 1;
  addReason.value = "";
  addVisible.value = true;
};

const addItem = () => {
  const item = allItems.value.find(option => option.id === addItemId.value);
  if (!item) {
    ElMessage.warning("请选择耗材");
    return;
  }
  if (!addReason.value.trim()) {
    ElMessage.warning("临时追加固定耗材必须填写说明");
    return;
  }
  rows.value.push({
    itemId: item.id,
    itemName: item.name,
    unit: item.unit,
    standardQuantity: null,
    yesterdayQuantity: null,
    actualQuantity: Number(addQuantity.value || 1),
    source: "MANUAL",
    reviewStatus: "DRAFT",
    exceptionReason: addReason.value.trim()
  });
  addVisible.value = false;
};

const removeRow = (index: number) => {
  rows.value.splice(index, 1);
};

const formatQuantity = (value?: number | null) => {
  if (value === null || value === undefined) return "—";
  return Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
};

watch(
  () => [props.modelValue, props.departmentKey, props.date],
  () => {
    if (props.modelValue && props.departmentKey) void load();
  },
  { immediate: true }
);
</script>

<style scoped lang="scss">
.fixed-shell {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.fixed-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;

  p {
    margin: 4px 0 0;
    color: #9a9285;
    font-size: 11px;
  }
}

.eyebrow {
  color: #347d68;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.fixed-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.fixed-hint {
  color: #9a9285;
  font-size: 11px;
}

.fixed-select {
  width: 100%;
}
</style>
