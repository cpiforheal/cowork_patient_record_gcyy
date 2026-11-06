<template>
  <el-drawer
    :model-value="modelValue"
    title="今日固定耗材确认"
    size="560px"
    class="qfud"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="qfud-body">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="默认全部按定额使用"
        description="今日确实没用就选“未使用”；少于定额就选“少用”并填实际数量。确认后按确认量扣库。"
      />

      <p v-if="!rows.length" class="qfud-empty">今日没有需要确认的固定或周期耗材。</p>

      <article v-for="line in rows" :key="line.lineId" class="qfud-row">
        <header class="qfud-row-head">
          <div>
            <strong>{{ line.itemName }}</strong>
            <small v-if="line.sourceText"> · {{ line.sourceText }}</small>
          </div>
          <span class="qfud-row-quota">
            今日定额 <b>{{ exact(line.quotaQuantity) }}</b> {{ line.unit }}
            <small v-if="hasCountUnit(line)">（≈ {{ approxQuota(line) }} {{ line.countUnit }}）</small>
          </span>
        </header>

        <el-radio-group v-model="stateOf(line).status" size="small" class="qfud-radio">
          <el-radio-button value="AS_QUOTA">按定额使用</el-radio-button>
          <el-radio-button value="NOT_USED">今日未使用</el-radio-button>
          <el-radio-button value="SHORT_USE">少用</el-radio-button>
        </el-radio-group>

        <div v-if="stateOf(line).status === 'SHORT_USE'" class="qfud-short">
          <label>
            实际使用
            <el-input-number
              v-model="stateOf(line).reportedQuantity"
              :min="0"
              :step="1"
              :precision="2"
              :controls="false"
              size="small"
              class="qfud-input"
            />
            <span class="qfud-unit">{{ countUnitOf(line) }}</span>
          </label>
          <span class="qfud-convert" :class="{ 'is-invalid': shortInvalid(line) }">
            <template v-if="!hasReported(line)">填写数量后自动折算成 {{ line.unit }}</template>
            <template v-else-if="hasCountUnit(line)">
              {{ Number(stateOf(line).reportedQuantity || 0) }} {{ line.countUnit }} = {{ exact(confirmedOf(line)) }} {{ line.unit }}
            </template>
            <template v-else>
              计入 {{ exact(confirmedOf(line)) }} {{ line.unit }}
            </template>
          </span>
          <small v-if="shortInvalid(line)" class="qfud-invalid">
            少用数量必须小于定额 {{ exact(line.quotaQuantity) }} {{ line.unit }}；用满请选“按定额使用”
          </small>
        </div>

        <small v-if="line.usageConfirmedBy && line.usageStatus !== 'AS_QUOTA'" class="qfud-confirmed">
          上次由 {{ line.usageConfirmedBy }} 确认于 {{ formatConfirmedAt(line.usageConfirmedAt) }}
        </small>
      </article>
    </div>

    <template #footer>
      <div class="qfud-footer">
        <el-button text @click="resetAll">全部按定额</el-button>
        <div class="qfud-footer-main">
          <el-button @click="emit('update:modelValue', false)">取消</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存确认</el-button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import dayjs from "dayjs";
import {
  confirmInventoryQuotaFixedUsageApi,
  type InventoryDepartmentDailyReport,
  type InventoryDepartmentDailyReportLine,
  type InventoryQuotaFixedUsageStatus
} from "@/api/modules/inventory";

const props = defineProps<{
  modelValue: boolean;
  reportId: string;
  departmentName?: string;
  businessDate: string;
  lines: InventoryDepartmentDailyReportLine[];
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved", report: InventoryDepartmentDailyReport): void;
}>();

interface UsageState {
  status: InventoryQuotaFixedUsageStatus;
  reportedQuantity?: number;
}

const states = ref<Record<string, UsageState>>({});
const saving = ref(false);

const isFixedUsage = (line: InventoryDepartmentDailyReportLine) =>
  Boolean(line.lineId) &&
  !line.exceptionOnly &&
  line.includedInQuota !== false &&
  line.bindingType !== "PER_PERSON" &&
  Number(line.quotaQuantity || 0) > 0;

const rows = computed(() => props.lines.filter(isFixedUsage));

const keyOf = (line: InventoryDepartmentDailyReportLine) => line.lineId || "";

const stateOf = (line: InventoryDepartmentDailyReportLine): UsageState => {
  const key = keyOf(line);
  if (!states.value[key]) states.value[key] = { status: "AS_QUOTA" };
  return states.value[key];
};

const factorOf = (line: InventoryDepartmentDailyReportLine) => Number(line.countPerUnit || 0);
const hasCountUnit = (line: InventoryDepartmentDailyReportLine) =>
  Boolean(line.countUnit) && factorOf(line) > 0;
const countUnitOf = (line: InventoryDepartmentDailyReportLine) => line.countUnit || line.unit;

const round3 = (value: number) => Math.round(value * 1000) / 1000;
const exact = (value?: number | null) => Number(value || 0).toFixed(3);
const approxQuota = (line: InventoryDepartmentDailyReportLine) =>
  (Math.round(Number(line.quotaQuantity || 0) * factorOf(line) * 10) / 10).toFixed(1);

const confirmedOf = (line: InventoryDepartmentDailyReportLine) => {
  const state = stateOf(line);
  if (state.status === "NOT_USED") return 0;
  if (state.status === "SHORT_USE") {
    const quantity = Number(state.reportedQuantity || 0);
    return round3(hasCountUnit(line) ? quantity / factorOf(line) : quantity);
  }
  return Number(line.quotaQuantity || 0);
};

const shortInvalid = (line: InventoryDepartmentDailyReportLine) => {
  const state = stateOf(line);
  if (state.status !== "SHORT_USE" || !hasReported(line)) return false;
  const quantity = Number(state.reportedQuantity);
  if (!Number.isFinite(quantity) || quantity < 0) return true;
  return confirmedOf(line) >= Number(line.quotaQuantity || 0);
};

const hasReported = (line: InventoryDepartmentDailyReportLine) => {
  const value = stateOf(line).reportedQuantity;
  return value !== undefined && value !== null && Number.isFinite(Number(value));
};

const formatConfirmedAt = (value?: string) => (value ? dayjs(value).format("MM-DD HH:mm") : "—");

const reset = () => {
  const next: Record<string, UsageState> = {};
  for (const line of rows.value) {
    next[keyOf(line)] = {
      status: (line.usageStatus as InventoryQuotaFixedUsageStatus) || "AS_QUOTA",
      reportedQuantity: line.reportedQuantity == null ? undefined : Number(line.reportedQuantity)
    };
  }
  states.value = next;
};

const resetAll = () => {
  const next: Record<string, UsageState> = {};
  for (const line of rows.value) next[keyOf(line)] = { status: "AS_QUOTA" };
  states.value = next;
};

const save = async () => {
  const missing = rows.value.filter(line => stateOf(line).status === "SHORT_USE" && !hasReported(line));
  if (missing.length) {
    ElMessage.warning(`${missing[0].itemName} 已选择少用，请填写实际使用数量`);
    return;
  }
  const invalid = rows.value.filter(shortInvalid);
  if (invalid.length) {
    ElMessage.warning(`${invalid[0].itemName} 的少用数量不正确，请检查`);
    return;
  }
  if (!props.reportId) {
    ElMessage.warning("请先保存今日草稿");
    return;
  }
  saving.value = true;
  try {
    const items = rows.value.map(line => {
      const state = stateOf(line);
      if (state.status === "SHORT_USE") {
        return { lineId: keyOf(line), status: state.status, reportedQuantity: Number(state.reportedQuantity || 0) };
      }
      return { lineId: keyOf(line), status: state.status };
    });
    const result = await confirmInventoryQuotaFixedUsageApi(props.reportId, items);
    emit("saved", result.data);
    ElMessage.success("固定耗材已确认");
    emit("update:modelValue", false);
  } catch (error: any) {
    ElMessage.error(error?.message || "固定耗材确认失败");
  } finally {
    saving.value = false;
  }
};

watch(
  () => props.modelValue,
  visible => {
    if (visible) reset();
  }
);
</script>

<style scoped lang="scss">
.qfud-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-bottom: 8px;
}

.qfud-empty {
  margin: 24px 0;
  color: #7b7064;
  text-align: center;
}

.qfud-row {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  background: #fffdf9;
  border: 1px solid #e8dfd2;
  border-radius: 10px;
}

.qfud-row-head {
  display: flex;
  gap: 12px;
  align-items: baseline;
  justify-content: space-between;

  strong {
    font-size: 14px;
    color: #2f2a24;
  }

  small {
    color: #7b7064;
  }
}

.qfud-row-quota {
  font-size: 12px;
  color: #7b7064;
  white-space: nowrap;

  b {
    color: #2f2a24;
  }
}

.qfud-radio {
  align-self: flex-start;
}

.qfud-short {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  background: #fbf6ee;
  border-radius: 8px;

  label {
    display: flex;
    gap: 8px;
    align-items: center;
    font-size: 12px;
    color: #7b7064;
  }
}

.qfud-input {
  width: 110px;
}

.qfud-unit {
  color: #2f2a24;
}

.qfud-convert {
  font-size: 13px;
  color: #2f6b58;

  &.is-invalid {
    color: #c45656;
  }
}

.qfud-invalid {
  color: #c45656;
}

.qfud-confirmed {
  color: #9a8f80;
}

.qfud-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.qfud-footer-main {
  display: flex;
  gap: 8px;
}
</style>
