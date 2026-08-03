<template>
  <section class="panel daily-verification-panel">
    <div class="panel-head">
      <div>
        <h2>患者变量耗材日核表</h2>
        <p>仅核验已启用套餐产生的自动扣减；固定消耗、按需申领和待确认耗材不会进入本表。</p>
      </div>
      <div class="daily-actions">
        <el-button :loading="loading" @click="emitLoad">查询</el-button>
        <el-button :disabled="!canExport" :loading="exporting === 'xlsx'" @click="emitExport('xlsx')">导出 Excel</el-button>
        <el-button type="primary" :disabled="!canExport" :loading="exporting === 'pdf'" @click="emitExport('pdf')">打印 / 导出 PDF</el-button>
      </div>
    </div>

    <div class="daily-toolbar">
      <el-date-picker v-model="selectedDate" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="emitLoad" />
      <el-select v-model="departmentId" clearable filterable placeholder="全部授权科室" @change="emitLoad">
        <el-option v-for="option in departmentOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
    </div>

    <el-alert
      title="统计口径：患者完成已配置的就诊环节后，系统按套餐标准自动扣减一次。"
      description="“患者数”按当日就诊记录去重；“扣减人次”按就诊记录与触发环节计数。若同一患者完成两个已配置环节，会产生两次不同环节的标准扣减。"
      type="info"
      :closable="false"
      show-icon
    />

    <div class="daily-stats">
      <div><span>患者数（去重）</span><strong>{{ patientCount }}</strong></div>
      <div><span>自动扣减人次</span><strong>{{ triggerCount }}</strong></div>
      <div><span>涉及耗材</span><strong>{{ itemCount }}</strong></div>
      <div><span>净扣减数量</span><strong>{{ netQuantity }}</strong></div>
    </div>

    <div class="inventory-table-shell">
      <el-table :data="report?.summary || []" border max-height="480" empty-text="该日期暂无患者变量耗材自动扣减记录">
        <el-table-column prop="department" label="科室" min-width="130" />
        <el-table-column prop="itemName" label="耗材" min-width="180" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="80" />
        <el-table-column label="自动扣减" width="120"><template #default="{ row }">{{ number(row.consumedQuantity) }}</template></el-table-column>
        <el-table-column label="冲销" width="100"><template #default="{ row }">{{ number(row.reversalQuantity) }}</template></el-table-column>
        <el-table-column label="净扣减" width="120"><template #default="{ row }">{{ number(row.consumedQuantity) - number(row.reversalQuantity) }}</template></el-table-column>
        <el-table-column label="当前结存" width="120"><template #default="{ row }">{{ number(row.closingQuantity) }}</template></el-table-column>
      </el-table>
    </div>
    <p class="daily-signature-tip">PDF 日核表包含科室确认、仓库确认与日期签字栏，可作为试运行期纸质复核依据。</p>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import type { InventoryDepartmentUsageReport } from "@/api/modules/inventory";

const props = defineProps<{
  report?: InventoryDepartmentUsageReport;
  loading?: boolean;
  exporting?: "" | "pdf" | "xlsx";
  today: string;
  canExport: boolean;
  departmentOptions: { value: string; label: string }[];
}>();

const emit = defineEmits<{
  load: [payload: { date: string; departmentId: string }];
  export: [payload: { date: string; departmentId: string; format: "pdf" | "xlsx" }];
}>();

const selectedDate = ref(props.today);
const departmentId = ref("");
const number = (value: unknown) => Number(value || 0);
const patientCount = computed(() => new Set((props.report?.details || []).map(row => `${row.departmentId}:${row.encounterId}`)).size);
const triggerCount = computed(() => new Set((props.report?.details || []).map(row => `${row.departmentId}:${row.encounterId}:${row.triggerStage}`)).size);
const itemCount = computed(() => (props.report?.summary || []).filter(row => number(row.consumedQuantity) - number(row.reversalQuantity) > 0).length);
const netQuantity = computed(() => (props.report?.summary || []).reduce((total, row) => total + number(row.consumedQuantity) - number(row.reversalQuantity), 0));
const emitLoad = () => emit("load", { date: selectedDate.value, departmentId: departmentId.value });
const emitExport = (format: "pdf" | "xlsx") => emit("export", { date: selectedDate.value, departmentId: departmentId.value, format });
</script>

<style scoped lang="scss">
.daily-verification-panel { display: grid; gap: 16px; }
.daily-actions, .daily-toolbar { display: flex; gap: 10px; flex-wrap: wrap; }
.daily-toolbar :deep(.el-select) { width: 220px; }
.daily-stats { display: grid; grid-template-columns: repeat(4, minmax(130px, 1fr)); border: 1px solid var(--el-border-color-lighter); border-radius: 8px; overflow: hidden; }
.daily-stats > div { padding: 14px 16px; border-right: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-lighter); }
.daily-stats > div:last-child { border-right: 0; }
.daily-stats span { display: block; color: var(--el-text-color-secondary); font-size: 13px; }
.daily-stats strong { display: block; margin-top: 6px; font-size: 24px; color: var(--el-color-primary); }
.daily-signature-tip { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
@media (max-width: 900px) { .daily-stats { grid-template-columns: repeat(2, minmax(130px, 1fr)); } .daily-stats > div:nth-child(2) { border-right: 0; } }
</style>
