<template>
  <section class="daily-panel">
    <header class="daily-header">
      <h2>日报核查</h2>
      <div class="daily-actions">
        <el-button :loading="loading" @click="emitLoad">查询</el-button
        ><el-button type="primary" :disabled="!canExport" :loading="exportingCsv" @click="emit('export-csv', query)"
          >导出 CSV</el-button
        ><el-button :disabled="!canExport" :loading="exportingXlsx" @click="emit('export-xlsx', query)">导出 XLSX</el-button>
      </div>
    </header>
    <div class="daily-toolbar">
      <label class="export-date-filter">
        <span>导出日期</span>
        <el-date-picker
          v-model="selectedRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :clearable="false"
          :shortcuts="shortcuts"
          @change="emitLoad"
        />
      </label>
      <el-select v-model="filters.departmentKey" clearable placeholder="全部科室"
        ><el-option
          v-for="department in report?.departments || []"
          :key="department.departmentKey"
          :label="department.departmentName"
          :value="department.departmentKey"
      /></el-select>
      <el-input v-model="filters.keyword" clearable placeholder="搜索耗材" />
      <el-select v-model="filters.riskLevel" clearable placeholder="全部核查状态"
        ><el-option v-for="item in riskOptions" :key="item.value" :label="item.label" :value="item.value"
      /></el-select>
      <el-select v-model="filters.special" clearable placeholder="普通与特殊"
        ><el-option label="普通耗材" value="ordinary" /><el-option label="特殊耗材" value="special"
      /></el-select>
      <el-checkbox v-model="filters.unverifiedOnly">仅看未核验</el-checkbox>
    </div>
    <el-alert
      v-if="report && !report.savedDepartmentCount"
      title="所选范围暂无已保存日报"
      type="warning"
      :closable="false"
      show-icon
    />
    <InventoryAdminDashboard :report="report" @drill="applyDrill" @reset="resetDrill" />
    <div class="daily-stats">
      <span>应填报 <strong>{{ report?.dashboard?.expectedDepartmentDays ?? 0 }}</strong> 科室日</span>
      <span>已提交 <strong>{{ report?.dashboard?.submittedDepartmentDays ?? 0 }}</strong> 科室日</span>
      <span>未填报 <strong>{{ report?.dashboard?.missingDepartmentDays ?? 0 }}</strong> 科室日</span>
      <span>待核验 <strong>{{ unverifiedCount }}</strong></span>
      <span>关注 / 异常 <strong>{{ attentionCount }} / {{ abnormalCount }}</strong></span>
    </div>
    <section class="panel-section">
      <div class="section-heading"><h3>理论与实际汇总</h3></div>
      <div class="inventory-table-shell">
        <el-table :data="report?.summary || []" max-height="360" empty-text="所选范围内暂无可核查耗材明细">
          <el-table-column label="耗材" min-width="200" show-overflow-tooltip>
            <template #default="{ row }"><span>{{ row.materialName }}</span><span class="unit-suffix"> / {{ row.unit }}</span></template>
          </el-table-column>
          <el-table-column label="理论 / 实际" width="150" align="right">
            <template #default="{ row }">
              <div class="dual-value"><span class="dual-theory">{{ number(row.theoreticalQuantity) }}</span><span class="dual-actual">{{ row.actualQuantity == null ? "—" : number(row.actualQuantity) }}</span></div>
            </template>
          </el-table-column>
          <el-table-column label="金额（理论 / 实际）" width="170" align="right">
            <template #default="{ row }">
              <div class="dual-value"><span class="dual-theory">{{ amount(row.theoreticalAmount) }}</span><span class="dual-actual">{{ amount(row.actualAmount) }}</span></div>
            </template>
          </el-table-column>
          <el-table-column label="覆盖率（填报 / 核价）" width="160" align="right">
            <template #default="{ row }">
              <div class="dual-value"><span class="dual-theory">{{ deviation(row.actualCoverageRate) }}</span><span class="dual-actual">{{ deviation(row.pricingCoverageRate) }}</span></div>
            </template>
          </el-table-column>
          <el-table-column label="覆盖科室" width="100" align="center"
            ><template #default="{ row }"
              ><el-popover placement="left" :width="260" trigger="click"
                ><template #reference
                  ><el-button link type="primary">{{ row.departmentCount }} 个科室</el-button></template
                >
                <div class="department-list">{{ (row.departments || []).join("、") || "无" }}</div></el-popover
              ></template
            ></el-table-column
          >
          <el-table-column label="核查状态" min-width="180"
            ><template #default="{ row }"
              ><el-tag v-if="row.unverifiedCount" type="info" size="small">未核验 {{ row.unverifiedCount }}</el-tag
              ><el-tag v-if="row.attentionCount" type="warning" size="small">关注 {{ row.attentionCount }}</el-tag
              ><el-tag v-if="row.abnormalCount" type="danger" size="small">异常 {{ row.abnormalCount }}</el-tag
              ><el-tag v-if="row.specialLineCount" type="success" size="small">特殊 {{ row.specialLineCount }}</el-tag></template
            ></el-table-column
          >
        </el-table>
      </div>
    </section>
    <section class="panel-section">
      <div class="section-heading"><h3>逐日科室核查明细</h3></div>
      <div class="binding-legend">
        <span class="legend-item"><i class="legend-dot" style="background:#ff9800"></i>固定日耗</span>
        <span class="legend-item"><i class="legend-dot" style="background:#2196f3"></i>按需领取</span>
        <span class="legend-item"><i class="legend-dot" style="background:#4caf50"></i>仪器触发</span>
        <span class="legend-hint">非"每人次定额"耗材以颜色标记区分</span>
      </div>
      <div class="inventory-table-shell">
        <el-table :data="filteredDetails" max-height="560" :row-key="detailKey" :row-class-name="bindingRowClass" empty-text="没有符合筛选条件的核查明细">
          <el-table-column label="日期 / 科室" width="150" show-overflow-tooltip>
            <template #default="{ row }"><div class="dual-value"><span class="dual-theory">{{ row.businessDate }}</span><span class="dual-actual">{{ row.departmentName }}</span></div></template>
          </el-table-column>
          <el-table-column prop="materialName" label="耗材" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ row.materialName }}</span><span class="unit-suffix"> / {{ row.unit }}</span>
              <el-tag v-if="bindingLabels[row.bindingType || '']" :type="bindingTagTypes[row.bindingType]" size="small" effect="plain" class="detail-binding-tag">
                {{ bindingLabels[row.bindingType] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="理论 / 实际" width="130" align="right"
            ><template #default="{ row }"
              ><div class="dual-value"><span class="dual-theory">{{ number(row.theoreticalQuantity) }}</span><span class="dual-actual" :class="{ 'not-reported': row.actualStatus === 'UNVERIFIED' }">{{ row.actualStatus === "UNVERIFIED" ? "待核验" : number(row.actualQuantity) }}</span></div></template
            ></el-table-column>
          <el-table-column label="差异 / 偏差" width="120" align="right"
            ><template #default="{ row }"
              ><div class="dual-value"><span class="dual-theory">{{ row.difference == null ? "—" : number(row.difference) }}</span><span class="dual-actual">{{ deviation(row.deviationRate) }}</span></div></template
            ></el-table-column
          >
          <el-table-column label="核查结果" width="110"
            ><template #default="{ row }"
              ><el-tag :type="riskTagType(row.riskLevel)" size="small">{{ riskLabel(row.riskLevel) }}</el-tag></template
            ></el-table-column
          ><el-table-column label="特殊说明" min-width="140" show-overflow-tooltip
            ><template #default="{ row }"
              ><span v-if="row.isSpecial">{{ row.specialDailyNote || row.specialAdminNote || "未填写" }}</span
              ><span v-else>—</span></template
            ></el-table-column
          ><el-table-column label="复核" width="100"
            ><template #default="{ row }"
              ><el-tag :type="reviewTagType(row.reviewStatus)" size="small">{{ reviewLabel(row.reviewStatus) }}</el-tag></template
            ></el-table-column
          ><el-table-column fixed="right" label="操作" width="72"
            ><template #default="{ row }"
              ><el-button link type="primary" @click="openReview(rollupDetail(row))">复核</el-button></template
            ></el-table-column
          >
        </el-table>
      </div>
    </section>
    <el-dialog v-model="reviewOpen" title="登记管理复核" width="min(520px, calc(100vw - 32px))" append-to-body>
      <template v-if="reviewingDetail"
        ><div class="review-target">
          <strong>{{ reviewingDetail.departmentName }} · {{ reviewingDetail.materialName }}</strong
          ><span
            >{{ reviewingDetail.businessDate }}｜理论 {{ number(reviewingDetail.theoreticalQuantity) }}｜实际
            {{ reviewingDetail.actualStatus === "UNVERIFIED" ? "未填报" : number(reviewingDetail.actualQuantity) }}</span
          >
        </div>
        <el-form label-width="92px"
          ><el-form-item label="复核状态"
            ><el-select v-model="reviewForm.reviewStatus"
              ><el-option label="待核查" value="PENDING" /><el-option label="已说明" value="EXPLAINED" /><el-option
                label="已复核"
                value="REVIEWED" /><el-option label="已关闭" value="CLOSED" /></el-select></el-form-item
          ><el-form-item label="复核备注"
            ><el-input
              v-model="reviewForm.reviewNote"
              type="textarea"
              :rows="4"
              maxlength="2000"
              show-word-limit
              placeholder="记录核查结论或处理要求" /></el-form-item></el-form
      ></template>
      <template #footer
        ><el-button @click="reviewOpen = false">取消</el-button
        ><el-button type="primary" :loading="reviewSaving" @click="saveReview">保存复核</el-button></template
      >
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import {
  saveInventoryQuotaReviewApi,
  type InventoryAdminDepartmentDailyRollup,
  type InventoryDailyRollupQuery
} from "@/api/modules/inventory";
import InventoryAdminDashboard from "./InventoryAdminDashboard.vue";
const props = defineProps<{
  report?: InventoryAdminDepartmentDailyRollup;
  loading?: boolean;
  exportingCsv?: boolean;
  exportingXlsx?: boolean;
  today: string;
  canExport: boolean;
}>();
const emit = defineEmits<{
  load: [payload: InventoryDailyRollupQuery];
  "export-csv": [payload: InventoryDailyRollupQuery];
  "export-xlsx": [payload: InventoryDailyRollupQuery];
}>();
const selectedRange = ref<string[]>([props.today, props.today]);
const filters = reactive({ departmentKey: "", keyword: "", riskLevel: "", special: "", unverifiedOnly: false });
const reviewOpen = ref(false);
const reviewSaving = ref(false);
const reviewingDetail = ref<InventoryAdminDepartmentDailyRollup["details"][number]>();
const reviewForm = reactive<{ reviewStatus: "PENDING" | "EXPLAINED" | "REVIEWED" | "CLOSED"; reviewNote: string }>({
  reviewStatus: "PENDING",
  reviewNote: ""
});
watch(
  () => props.today,
  value => {
    if (!selectedRange.value.length || selectedRange.value[0] === selectedRange.value[1]) selectedRange.value = [value, value];
  }
);
const query = computed<InventoryDailyRollupQuery>(() => ({
  from: selectedRange.value[0] || props.today,
  to: selectedRange.value[1] || selectedRange.value[0] || props.today
}));
const periodLabel = computed(() => query.value.from + " 至 " + query.value.to);
const number = (value: unknown) =>
  value == null ? "—" : Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 6 });
const bindingLabels: Record<string, string> = { FIXED_DAILY: "固定日耗", ON_DEMAND: "按需领取", EQUIPMENT: "仪器触发" };
const bindingTagTypes: Record<string, "warning" | "info" | "success"> = { FIXED_DAILY: "warning", ON_DEMAND: "info", EQUIPMENT: "success" };
const amount = (value?: number | null) => (value == null ? "未核价" : "¥" + number(value));
const deviation = (value?: number | null) =>
  value == null ? "—" : (Number(value) * 100).toLocaleString("zh-CN", { maximumFractionDigits: 2 }) + "%";
const riskOptions = [
  { value: "UNVERIFIED", label: "未核验" },
  { value: "ATTENTION", label: "关注" },
  { value: "ABNORMAL", label: "异常" },
  { value: "SPECIAL_PENDING_NOTE", label: "特殊待说明" },
  { value: "SPECIAL", label: "特殊耗材" },
  { value: "HISTORICAL_UNFROZEN", label: "历史未冻结" },
  { value: "NORMAL", label: "正常" }
];
const riskLabel = (value: string) =>
  (
    ({
      UNVERIFIED: "未核验",
      ATTENTION: "关注",
      ABNORMAL: "异常",
      SPECIAL_PENDING_NOTE: "特殊待说明",
      SPECIAL: "特殊耗材",
      HISTORICAL_UNFROZEN: "历史未冻结",
      NORMAL: "正常"
    }) as Record<string, string>
  )[value] || value;
const riskTagType = (value: string) =>
  value === "ABNORMAL" || value === "SPECIAL_PENDING_NOTE"
    ? "danger"
    : value === "ATTENTION"
      ? "warning"
      : value === "UNVERIFIED" || value === "HISTORICAL_UNFROZEN"
        ? "info"
        : value === "SPECIAL"
          ? "success"
          : "primary";
const reviewLabel = (value?: string) =>
  (({ PENDING: "待核查", EXPLAINED: "已说明", REVIEWED: "已复核", CLOSED: "已关闭" }) as Record<string, string>)[value || ""] ||
  "未登记";
const reviewTagType = (value?: string) =>
  value === "CLOSED" ? "success" : value === "REVIEWED" ? "primary" : value === "EXPLAINED" ? "warning" : "info";
const detailKey = (row: InventoryAdminDepartmentDailyRollup["details"][number]) =>
  [row.businessDate, row.departmentKey, row.lineKey].join(":");
const bindingRowClass = ({ row }: { row: { bindingType?: string; materialName?: string; unit?: string } }) => {
  const bt = row.bindingType;
  if (bt && bt !== "PER_PERSON") return `binding-row binding-row-${bt.toLowerCase().replace(/_/g, "-")}`;
  if (bt === "PER_PERSON") return "";
  const name = (row.materialName || "").trim();
  const unit = (row.unit || "").trim();
  if (!name) return "";
  if (name.includes("试剂") || name.includes("探") || name.includes("溶血") || name.includes("清洗液")) return "binding-row binding-row-equipment";
  if (name.includes("利器盒") || name.includes("打印纸") || name.includes("处方") || name.includes("签字笔") || name.includes("卫生纸") || name.includes("橡胶检查手套") || name.includes("固体胶") || name.includes("拖把") || name.includes("过氧化氢") || name.includes("橡胶管") || name === "CRP试剂" || name.includes("C14") || name.includes("糖化")) return "binding-row binding-row-on-demand";
  if (unit.includes("/天") || name.includes("口罩") || name.includes("帽子") || name.includes("手套") || name.includes("消毒") || name.includes("垃圾袋") || name.includes("中单") || name.includes("手术衣") || name.includes("注射器") || name.includes("洗手液") || name.includes("手消") || name.includes("A4纸") || name.includes("标签贴")) return "binding-row binding-row-fixed-daily";
  return "";
};
const filteredDetails = computed(() =>
  (props.report?.details || []).filter(row => {
    const keyword = filters.keyword.trim().toLowerCase();
    return (
      (!filters.departmentKey || row.departmentKey === filters.departmentKey) &&
      (!keyword || (row.materialName + " " + row.unit).toLowerCase().includes(keyword)) &&
      (!filters.riskLevel || row.riskLevel === filters.riskLevel) &&
      (!filters.special || (filters.special === "special" ? Boolean(row.isSpecial) : !row.isSpecial)) &&
      (!filters.unverifiedOnly || row.actualStatus === "UNVERIFIED")
    );
  })
);
const unverifiedCount = computed(() => props.report?.dashboard?.unverifiedCount ?? (props.report?.details || []).filter(row => row.actualStatus === "UNVERIFIED").length);
const attentionCount = computed(() => props.report?.dashboard?.attentionCount ?? (props.report?.details || []).filter(row => row.riskLevel === "ATTENTION").length);
const abnormalCount = computed(
  () =>
    props.report?.dashboard?.abnormalCount ??
    (props.report?.details || []).filter(row => row.riskLevel === "ABNORMAL" || row.riskLevel === "SPECIAL_PENDING_NOTE").length
);
const emitLoad = () => emit("load", query.value);
const applyDrill = (drill: { businessDate?: string; departmentKey?: string; materialName?: string; riskLevel?: string }) => {
  if (drill.businessDate) selectedRange.value = [drill.businessDate, drill.businessDate];
  if (drill.departmentKey !== undefined) filters.departmentKey = drill.departmentKey;
  if (drill.materialName !== undefined) filters.keyword = drill.materialName;
  if (drill.riskLevel !== undefined) filters.riskLevel = drill.riskLevel;
  nextTick(() =>
    document.querySelector(".inventory-table-shell:last-of-type")?.scrollIntoView({ behavior: "smooth", block: "start" })
  );
};
const resetDrill = () => {
  filters.departmentKey = "";
  filters.keyword = "";
  filters.riskLevel = "";
  filters.special = "";
  filters.unverifiedOnly = false;
};
const rollupDetail = (row: unknown) => row as InventoryAdminDepartmentDailyRollup["details"][number];
const openReview = (row: InventoryAdminDepartmentDailyRollup["details"][number]) => {
  reviewingDetail.value = row;
  reviewForm.reviewStatus = row.reviewStatus || "PENDING";
  reviewForm.reviewNote = row.reviewNote || "";
  reviewOpen.value = true;
};
const saveReview = async () => {
  if (!reviewingDetail.value) return;
  reviewSaving.value = true;
  try {
    await saveInventoryQuotaReviewApi({
      businessDate: reviewingDetail.value.businessDate,
      departmentKey: reviewingDetail.value.departmentKey,
      lineKey: reviewingDetail.value.lineKey,
      materialName: reviewingDetail.value.materialName,
      unit: reviewingDetail.value.unit,
      reviewStatus: reviewForm.reviewStatus,
      reviewNote: reviewForm.reviewNote.trim() || undefined
    });
    ElMessage.success("复核记录已保存");
    reviewOpen.value = false;
    emitLoad();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存复核记录失败");
  } finally {
    reviewSaving.value = false;
  }
};
const dayOffset = (days: number) => {
  const date = new Date();
  date.setHours(0, 0, 0, 0);
  date.setDate(date.getDate() - days);
  return date;
};
const shortcuts = [
  { text: "过去 3 天", value: () => [dayOffset(2), dayOffset(0)] },
  { text: "过去 5 天", value: () => [dayOffset(4), dayOffset(0)] },
  { text: "近一周", value: () => [dayOffset(6), dayOffset(0)] },
  {
    text: "本月",
    value: () => {
      const now = dayOffset(0);
      return [new Date(now.getFullYear(), now.getMonth(), 1), now];
    }
  }
];
</script>
<style scoped lang="scss">
.daily-panel {
  display: grid;
  gap: 12px;
}
.detail-binding-tag {
  margin-left: 4px;
}
.daily-header,
.daily-actions,
.daily-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.daily-header {
  justify-content: space-between;
}
.daily-header h2,
.section-heading h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--inventory-text, var(--el-text-color-primary));
}
.daily-toolbar {
  justify-content: flex-start;
}
.export-date-filter {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-regular);
  font-size: 14px;
  white-space: nowrap;
}
.export-date-filter :deep(.el-date-editor) {
  width: 260px;
}
.daily-toolbar :deep(.el-select),
.daily-toolbar :deep(.el-input) {
  width: 160px;
}
.daily-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.daily-stats span {
  padding: 0 12px;
  border-right: 1px solid var(--el-border-color-lighter);
}
.daily-stats span:first-child {
  padding-left: 0;
}
.daily-stats span:last-child {
  border-right: 0;
}
.daily-stats strong {
  color: var(--inventory-text, var(--el-text-color-primary));
  font-size: 14px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.panel-section {
  display: grid;
  gap: 6px;
}
.panel-section + .panel-section {
  animation: section-in 420ms ease-out 60ms backwards;
}
.inventory-table-shell {
  overflow: hidden;
  border: 1px solid var(--inventory-line-soft, var(--el-border-color-lighter));
  border-radius: 10px;
  background: #fff;
}
.unit-suffix {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}
.dual-value {
  display: flex;
  flex-direction: column;
  line-height: 1.5;
  gap: 1px;
}
.dual-value .dual-theory {
  font-variant-numeric: tabular-nums;
  font-size: 13px;
}
.dual-value .dual-actual {
  font-variant-numeric: tabular-nums;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.inventory-table-shell :deep(.el-table) {
  --el-table-border-color: var(--inventory-line-soft, #edf1f5);
  --el-table-header-bg-color: transparent;
}
.inventory-table-shell :deep(th.el-table__cell) {
  background: transparent;
  color: var(--inventory-muted, var(--el-text-color-secondary));
  font-weight: 600;
}
.inventory-table-shell :deep(td.el-table__cell) {
  border-bottom: 1px solid var(--inventory-line-soft, #edf1f5);
}
.inventory-table-shell :deep(.el-table .cell) {
  transition: color 160ms ease-out;
}
:deep(.binding-row) > td.el-table__cell {
  position: relative;
}
:deep(.binding-row) > td.el-table__cell:first-child {
  box-shadow: inset 5px 0 0 0 var(--binding-color, #ff9800);
}
:deep(.binding-row-fixed-daily) > td.el-table__cell {
  background: #fff3e0 !important;
  --binding-color: #ff9800;
}
:deep(.binding-row-on-demand) > td.el-table__cell {
  background: #e3f2fd !important;
  --binding-color: #2196f3;
}
:deep(.binding-row-equipment) > td.el-table__cell {
  background: #e8f5e9 !important;
  --binding-color: #4caf50;
}
:deep(.binding-row:hover) > td.el-table__cell {
  filter: brightness(0.95);
}
.binding-legend {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 6px 12px;
  margin-bottom: 8px;
  background: #f8fafc;
  border-radius: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.legend-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.legend-hint {
  margin-left: auto;
  color: var(--el-text-color-placeholder);
}
@keyframes section-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
.department-list {
  line-height: 1.8;
}
.not-reported {
  color: var(--el-text-color-secondary);
}
.review-target {
  display: grid;
  gap: 6px;
  margin-bottom: 12px;
  padding: 10px 12px;
  background: var(--el-fill-color-light);
  border-radius: var(--el-border-radius-base);
}
.review-target span {
  color: var(--inventory-muted, var(--el-text-color-secondary));
  font-size: 13px;
}
@media (max-width: 960px) {
  .daily-header {
    align-items: flex-start;
    flex-direction: column;
  }
  .export-date-filter {
    width: 100%;
  }
  .export-date-filter :deep(.el-date-editor),
  .daily-toolbar :deep(.el-select),
  .daily-toolbar :deep(.el-input) {
    width: 100%;
  }
  .daily-stats span {
    padding: 0 8px;
  }
}
</style>
