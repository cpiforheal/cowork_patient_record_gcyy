<template>
  <section class="daily-consumption">
    <div class="consumption-toolbar">
      <div>
        <h2>今日耗用</h2>
        <p>按已启用套餐试算业务量；试算结果不会扣减库存或生成流水。</p>
      </div>
      <div class="toolbar-actions">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        <el-button :loading="loading" :icon="Refresh" @click="emitRefresh">刷新已入账记录</el-button>
      </div>
    </div>

    <div class="calculation-inputs" aria-label="耗材用量试算">
      <el-form label-position="top" class="calculation-form">
        <el-form-item label="科室">
          <el-select v-model="department" placeholder="请选择科室" @change="resetPackageSelection">
            <el-option v-for="option in departmentOptions" :key="option" :label="option" :value="option" />
          </el-select>
        </el-form-item>
        <el-form-item label="门诊人数">
          <el-input-number v-model="outpatientCount" :min="0" :precision="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="住院患者日">
          <el-input-number v-model="inpatientPatientDays" :min="0" :precision="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="业务日期">
          <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        </el-form-item>
      </el-form>

      <div class="package-picker">
        <span>纳入试算的服务项目</span>
        <el-checkbox-group v-model="selectedPackageIds" :disabled="!availablePackages.length">
          <el-checkbox v-for="pack in availablePackages" :key="pack.id" :label="pack.id">
            {{ packageLabel(pack) }}
          </el-checkbox>
        </el-checkbox-group>
        <p v-if="!department" class="field-hint">先选择科室，再选择需要按本次业务量试算的服务项目。</p>
        <p v-else-if="!availablePackages.length" class="field-hint">该科室在此日期没有已启用套餐，暂不能试算。</p>
        <p v-else-if="!selectedPackageIds.length" class="field-hint">请选择服务项目，避免把同一患者的多个业务环节重复计算。</p>
      </div>
    </div>

    <el-tabs v-model="activeView" class="consumption-tabs">
      <el-tab-pane label="测试试算" name="preview">
        <div class="event-strip" aria-label="测试试算汇总">
          <label><span>门诊人数</span><strong>{{ outpatientCount }}</strong></label>
          <label><span>住院患者日</span><strong>{{ inpatientPatientDays }}</strong></label>
          <label><span>计算耗材种类</span><strong>{{ calculationRows.length }}</strong></label>
        </div>
        <el-table :data="calculationRows" height="calc(100vh - 505px)" min-height="260" empty-text="填写业务量并选择服务项目后显示试算结果" table-layout="fixed">
          <el-table-column prop="itemName" label="耗材" min-width="190" show-overflow-tooltip />
          <el-table-column prop="serviceSummary" label="服务项目" min-width="220" show-overflow-tooltip />
          <el-table-column prop="volumeSummary" label="业务量" min-width="155" show-overflow-tooltip />
          <el-table-column prop="quantity" label="计算用量" width="145">
            <template #default="{ row }">{{ formatQuantity(row.quantity) }} {{ row.unit }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="已入账记录" name="posted">
        <div class="event-strip" aria-label="当日已入账自动耗用">
          <label><span>已入账自动耗用</span><strong>{{ dateRecords.length }}</strong></label>
        </div>
        <el-table :data="postedRows" height="calc(100vh - 505px)" min-height="260" :empty-text="date === today ? '今日暂无自动耗用记录' : '该日期暂无自动耗用记录'" table-layout="fixed">
          <el-table-column prop="department" label="科室" min-width="130" />
          <el-table-column prop="stage" label="业务环节" min-width="150" show-overflow-tooltip />
          <el-table-column prop="itemName" label="耗材" min-width="180" show-overflow-tooltip />
          <el-table-column prop="quantity" label="实际用量" width="120">
            <template #default="{ row }">{{ row.quantity }} {{ row.unit }}</template>
          </el-table-column>
          <el-table-column prop="packageName" label="套餐版本" min-width="150" show-overflow-tooltip />
          <el-table-column prop="statusLabel" label="状态" width="110">
            <template #default="{ row }"><el-tag :type="row.statusType" effect="plain">{{ row.statusLabel }}</el-tag></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { Refresh } from "@element-plus/icons-vue";
import type { InventoryConsumptionRecord, InventoryItem, InventoryPackage } from "@/api/modules/inventory";

type EventRow = {
  id: string;
  department: string;
  stage: string;
  itemName: string;
  quantity: number;
  unit: string;
  packageName: string;
  statusLabel: string;
  statusType: "success" | "warning" | "danger" | "info";
};

type CalculationRow = {
  itemId: string;
  itemName: string;
  unit: string;
  quantity: number;
  serviceSummary: string;
  volumeSummary: string;
};

const props = defineProps<{
  records: InventoryConsumptionRecord[];
  packages: InventoryPackage[];
  items: InventoryItem[];
  currentDepartment?: string;
  loading?: boolean;
  today: string;
}>();
const emit = defineEmits<{ refresh: [date: string] }>();
const date = ref(props.today);
const department = ref(props.currentDepartment || "");
const outpatientCount = ref(0);
const inpatientPatientDays = ref(0);
const selectedPackageIds = ref<string[]>([]);
const activeView = ref<"preview" | "posted">("preview");

watch(
  () => props.today,
  value => {
    if (!date.value) date.value = value;
  }
);
watch(
  () => props.currentDepartment,
  value => {
    if (value && !department.value) department.value = value;
  }
);

const departmentOptions = computed(() => [...new Set(props.packages.map(pack => pack.department).filter(Boolean))].sort());
const availablePackages = computed(() =>
  props.packages.filter(
    pack => pack.status === "enabled" && pack.department === department.value && (!pack.effectiveDate || pack.effectiveDate.slice(0, 10) <= date.value)
  )
);
const selectedPackages = computed(() => availablePackages.value.filter(pack => selectedPackageIds.value.includes(pack.id)));
const itemById = computed(() => new Map(props.items.map(item => [item.id, item])));

const resetPackageSelection = () => {
  selectedPackageIds.value = [];
};
const packageLabel = (pack: InventoryPackage) => `${pack.name} (${pack.careType === "outpatient" ? "门诊" : "住院"} / ${pack.triggerStage})`;
const businessDate = (value?: string) => String(value || "").slice(0, 10);
const dateRecords = computed(() => props.records.filter(record => businessDate(record.consumedAt) === date.value));
const statusMeta = (status: InventoryConsumptionRecord["status"]) => {
  const map = {
    succeeded: { label: "已入账", type: "success" },
    pending: { label: "待处理", type: "warning" },
    failed: { label: "异常", type: "danger" },
    reversed: { label: "已冲销", type: "info" },
    partially_reversed: { label: "部分冲销", type: "warning" }
  } as const;
  return map[status];
};

const calculationRows = computed<CalculationRow[]>(() => {
  const rows = new Map<string, CalculationRow>();
  selectedPackages.value.forEach(pack => {
    const volume = pack.careType === "outpatient" ? Number(outpatientCount.value || 0) : Number(inpatientPatientDays.value || 0);
    if (volume <= 0) return;
    pack.lines.forEach(line => {
      const item = itemById.value.get(line.itemId);
      const existing = rows.get(line.itemId);
      const quantity = Number((Number(line.quantity || 0) * volume).toFixed(6));
      const source = `${pack.name} (${pack.careType === "outpatient" ? "门诊" : "住院"})`;
      const volumeText = `${pack.careType === "outpatient" ? "门诊" : "住院"}${volume}`;
      if (existing) {
        existing.quantity = Number((existing.quantity + quantity).toFixed(6));
        existing.serviceSummary = `${existing.serviceSummary}；${source}`;
        existing.volumeSummary = `${existing.volumeSummary}；${volumeText}`;
        return;
      }
      rows.set(line.itemId, {
        itemId: line.itemId,
        itemName: item?.name || line.itemId,
        unit: item?.unit || "",
        quantity,
        serviceSummary: source,
        volumeSummary: volumeText
      });
    });
  });
  return [...rows.values()].sort((left, right) => left.itemName.localeCompare(right.itemName, "zh-CN"));
});

const postedRows = computed<EventRow[]>(() =>
  [...dateRecords.value].map(record => {
    const status = statusMeta(record.status);
    return {
      id: record.id,
      department: record.departmentName || "未归属科室",
      stage: record.stage || "业务完成",
      itemName: record.itemName,
      quantity: Number(record.quantity || 0),
      unit: record.unit || "",
      packageName: record.packageName ? `${record.packageName}${record.packageVersion ? ` v${record.packageVersion}` : ""}` : "自动耗用",
      statusLabel: status.label,
      statusType: status.type
    };
  })
);

const formatQuantity = (value: number) => Number(value || 0).toLocaleString("zh-CN", { maximumFractionDigits: 6 });
const emitRefresh = () => emit("refresh", date.value);
</script>

<style scoped lang="scss">
.daily-consumption { display: grid; gap: 16px; min-width: 0; }
.consumption-toolbar, .toolbar-actions { display: flex; align-items: center; gap: 12px; }
.consumption-toolbar { justify-content: space-between; flex-wrap: wrap; }
.consumption-toolbar h2 { margin: 0; font-size: 20px; color: var(--inventory-text); }
.consumption-toolbar p, .field-hint { margin: 6px 0 0; color: var(--inventory-muted); font-size: 13px; }
.toolbar-actions { flex-wrap: wrap; }
.calculation-inputs { display: grid; grid-template-columns: minmax(0, 1fr) minmax(280px, 1.4fr); gap: 18px; padding: 14px 0; border-block: 1px solid var(--inventory-line); }
.calculation-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }
.calculation-form :deep(.el-form-item) { margin-bottom: 10px; }
.calculation-form :deep(.el-select), .calculation-form :deep(.el-input-number), .calculation-form :deep(.el-date-editor) { width: 100%; }
.package-picker { display: grid; align-content: start; gap: 8px; min-width: 0; }
.package-picker > span { font-size: 14px; font-weight: 600; color: var(--inventory-text); }
.package-picker :deep(.el-checkbox-group) { display: flex; flex-wrap: wrap; gap: 4px 12px; }
.package-picker :deep(.el-checkbox) { margin-right: 0; max-width: 100%; }
.consumption-tabs :deep(.el-tabs__header) { margin: 0 0 12px; }
.event-strip { display: grid; grid-template-columns: repeat(3, minmax(150px, 280px)); border-block: 1px solid var(--inventory-line); margin-bottom: 12px; }
.event-strip label { display: grid; gap: 5px; padding: 12px 14px; }
.event-strip span { color: var(--inventory-muted); font-size: 12px; }
.event-strip strong { color: var(--inventory-text); font-size: 21px; }
@media (max-width: 900px) { .calculation-inputs { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .calculation-form, .event-strip { grid-template-columns: 1fr; } }
</style>
