<template>
  <section class="daily-report" v-loading="loading">
    <header class="report-toolbar">
      <div>
        <span class="eyebrow">科室日报 · {{ draft?.departmentName || departmentName }}</span>
        <h2>科室耗材日报</h2>
        <p>固定模板自动带出，可按当天实际使用修改数量或追加耗材；提交后进入库管审核。</p>
      </div>
      <div class="report-actions">
        <el-date-picker v-model="businessDate" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="load" />
        <el-tag :type="statusType(draft?.status)" effect="plain">{{ statusLabel(draft?.status) }}</el-tag>
        <el-button :disabled="!editable" :loading="saving" @click="save">保存草稿</el-button>
        <el-button type="primary" :disabled="!editable" :loading="submitting" @click="submit">提交审核</el-button>
        <el-button v-if="draft?.id" :disabled="exporting" :loading="exporting" @click="exportReport">导出</el-button>
      </div>
    </header>

    <div class="report-summary">
      <span>耗材行 <b>{{ lines.length }}</b></span>
      <span>实际总量 <b>{{ totalQuantity }}</b></span>
      <span v-if="draft?.locked" class="locked">已锁定</span>
      <span v-else-if="draft?.closure?.submittedAt" class="submitted">提交于 {{ formatDateTime(draft.closure.submittedAt) }}</span>
    </div>

    <el-alert
      v-if="draft?.locked"
      type="warning"
      :closable="false"
      show-icon
      title="该业务日已锁定"
      description="历史日报不能直接修改；如需更正，请发起更正单。"
    />

    <el-table :data="lines" class="report-table" height="calc(100vh - 340px)">
      <el-table-column prop="itemName" label="耗材" min-width="220" show-overflow-tooltip />
      <el-table-column prop="unit" label="单位" width="90" />
      <el-table-column label="模板数量" width="110" align="right">
        <template #default="{ row }">{{ formatQuantity(row.presetQuantity) }}</template>
      </el-table-column>
      <el-table-column label="实际使用" width="150">
        <template #default="{ row }">
          <el-input-number
            v-model="row.actualQuantity"
            :min="0"
            :precision="2"
            :step="1"
            size="small"
            controls-position="right"
            :disabled="!editable"
          />
        </template>
      </el-table-column>
      <el-table-column label="来源" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="row.source === 'MANUAL' ? 'warning' : 'info'">
            {{ row.source === "MANUAL" ? "追加" : "模板" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="200">
        <template #default="{ row }">
          <el-input v-model="row.exceptionReason" size="small" :disabled="!editable" placeholder="选填" />
        </template>
      </el-table-column>
      <el-table-column width="70" align="center">
        <template #default="{ $index }">
          <el-button text type="danger" :disabled="!editable" @click="removeLine($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="report-footer">
      <el-button :icon="Plus" :disabled="!editable" @click="addDialogVisible = true">追加耗材</el-button>
      <span>模板按当前定额规则带出；追加耗材请填写说明。</span>
    </div>

    <el-dialog v-model="addDialogVisible" title="追加科室日报耗材" width="540px">
      <el-form label-position="top">
        <el-form-item label="耗材">
          <el-select v-model="addItemId" filterable placeholder="搜索物资主数据" class="add-item-select">
            <el-option
              v-for="item in availableItems"
              :key="item.id"
              :label="`${item.name}（${item.unit}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="实际使用量">
          <el-input-number v-model="addItemQuantity" :min="0.01" :precision="2" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="addItemReason" placeholder="例如：临时追加、设备耗材" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="addLine">加入日报</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import dayjs from "dayjs";
import {
  downloadInventoryDepartmentDailyReportApi,
  getInventoryDepartmentDailyReportApi,
  saveInventoryDepartmentDailyReportApi,
  submitInventoryDepartmentDailyReportApi,
  type InventoryDepartmentDailyReport,
  type InventoryDepartmentDailyReportLine,
  type InventoryItem
} from "@/api/modules/inventory";

const props = defineProps<{
  departmentKey: string;
  departmentName?: string;
  today?: string;
  items?: InventoryItem[];
}>();

const businessDate = ref(props.today || dayjs().format("YYYY-MM-DD"));
const draft = ref<InventoryDepartmentDailyReport | null>(null);
const lines = ref<InventoryDepartmentDailyReportLine[]>([]);
const loading = ref(false);
const saving = ref(false);
const submitting = ref(false);
const exporting = ref(false);
const addDialogVisible = ref(false);
const addItemId = ref("");
const addItemQuantity = ref(1);
const addItemReason = ref("");

const editable = computed(() => {
  const status = draft.value?.status;
  return Boolean(draft.value && !draft.value.locked && ["DRAFT", "RETURNED"].includes(status || ""));
});
const totalQuantity = computed(() =>
  lines.value.reduce((sum, line) => sum + Number(line.actualQuantity || 0), 0).toFixed(2)
);
const availableItems = computed(() => (props.items ?? []).filter(item => !lines.value.some(line => line.itemId === item.id)));

const load = async () => {
  loading.value = true;
  try {
    const result = await getInventoryDepartmentDailyReportApi({
      departmentKey: props.departmentKey,
      date: businessDate.value
    });
    draft.value = result.data ?? null;
    lines.value = (result.data?.lines ?? []).map(line => ({ ...line, actualQuantity: Number(line.actualQuantity || 0) }));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取科室日报失败");
  } finally {
    loading.value = false;
  }
};

const save = async () => {
  if (!draft.value) return null;
  saving.value = true;
  try {
    const result = await saveInventoryDepartmentDailyReportApi({
      departmentKey: props.departmentKey,
      businessDate: businessDate.value,
      revision: draft.value.revision || 0,
      note: "",
      lines: lines.value.map(line => ({
        itemId: line.itemId,
        itemName: line.itemName,
        unit: line.unit,
        presetQuantity: line.presetQuantity,
        actualQuantity: Number(line.actualQuantity || 0),
        source: line.source,
        exceptionReason: line.exceptionReason
      }))
    });
    draft.value = result.data ?? draft.value;
    lines.value = (result.data?.lines ?? []).map(line => ({ ...line, actualQuantity: Number(line.actualQuantity || 0) }));
    ElMessage.success("科室日报已保存");
    return result.data ?? null;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存科室日报失败");
    return null;
  } finally {
    saving.value = false;
  }
};

const submit = async () => {
  let current = draft.value;
  if (!current) return;
  if (!current.id) {
    current = await save();
    if (!current) return;
  }
  submitting.value = true;
  try {
    const result = await submitInventoryDepartmentDailyReportApi(current.id);
    draft.value = result.data ?? draft.value;
    lines.value = (result.data?.lines ?? lines.value).map(line => ({ ...line, actualQuantity: Number(line.actualQuantity || 0) }));
    ElMessage.success("科室日报已提交库管审核");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "提交科室日报失败");
  } finally {
    submitting.value = false;
  }
};

const exportReport = async () => {
  if (!draft.value?.id) return;
  exporting.value = true;
  try {
    await downloadInventoryDepartmentDailyReportApi(draft.value.id, "xlsx");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "导出科室日报失败");
  } finally {
    exporting.value = false;
  }
};

const addLine = () => {
  const item = (props.items ?? []).find(option => option.id === addItemId.value);
  if (!item) {
    ElMessage.warning("请选择耗材");
    return;
  }
  lines.value.push({
    itemId: item.id,
    itemName: item.name,
    unit: item.unit,
    presetQuantity: null,
    actualQuantity: Number(addItemQuantity.value || 1),
    source: "MANUAL",
    exceptionReason: addItemReason.value.trim() || "科室临时追加"
  });
  addDialogVisible.value = false;
  addItemId.value = "";
  addItemQuantity.value = 1;
  addItemReason.value = "";
};

const removeLine = (index: number) => {
  lines.value.splice(index, 1);
};

const statusLabel = (status?: string) => {
  switch (status) {
    case "SUBMITTED":
      return "已提交";
    case "APPROVED":
      return "已通过";
    case "RETURNED":
      return "已驳回";
    default:
      return "草稿";
  }
};

const statusType = (status?: string): "success" | "warning" | "danger" | "info" => {
  if (status === "APPROVED") return "success";
  if (status === "RETURNED") return "danger";
  if (status === "SUBMITTED") return "warning";
  return "info";
};

const formatQuantity = (value?: number | null) => {
  if (value === null || value === undefined) return "—";
  return Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
};

const formatDateTime = (value?: string) => {
  if (!value) return "";
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format("MM-DD HH:mm") : value;
};

watch(
  () => props.departmentKey,
  () => load()
);

onMounted(() => {
  void load();
});
</script>

<style scoped lang="scss">
.daily-report {
  --hos-bg-primary: #fffdf9;
  --hos-bg-surface: #faf7f1;
  --hos-border-light: #f1ebe1;
  --hos-text-primary: #2f2d27;
  --hos-text-secondary: #6d675c;
  --hos-text-muted: #9a9285;
  --hos-primary: #347d68;
  --hos-primary-soft: #edf7f1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  background: var(--hos-bg-surface);
}

.report-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 18px 20px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 16px;

  h2 {
    margin: 4px 0 4px;
    color: var(--hos-text-primary);
    font-size: 20px;
    font-weight: 700;
  }

  p {
    margin: 0;
    color: var(--hos-text-secondary);
    font-size: 12px;
  }
}

.eyebrow {
  color: var(--hos-primary);
  font-weight: 600;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.report-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.report-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  color: var(--hos-text-secondary);
  font-size: 12px;

  b {
    color: var(--hos-text-primary);
    font-variant-numeric: tabular-nums;
  }

  .locked {
    color: var(--hos-status-warning);
  }

  .submitted {
    color: var(--hos-status-success);
  }
}

.report-table {
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;
}

.report-footer {
  display: flex;
  gap: 10px;
  align-items: center;
  color: var(--hos-text-muted);
  font-size: 11px;
}

.add-item-select {
  width: 100%;
}

@media (max-width: 760px) {
  .daily-report {
    padding: 10px;
  }
}
</style>
