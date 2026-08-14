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
        <el-table :data="report?.summary || []" border max-height="360" empty-text="所选范围内暂无可核查耗材明细">
          <el-table-column prop="materialName" label="耗材" min-width="210" show-overflow-tooltip /><el-table-column
            prop="unit"
            label="单位"
            width="90"
            align="center"
          />
          <el-table-column label="理论使用量" width="130" align="right"
            ><template #default="{ row }">{{ number(row.theoreticalQuantity) }}</template></el-table-column
          >
          <el-table-column label="实际使用量" width="130" align="right"
            ><template #default="{ row }">{{ number(row.actualQuantity) }}</template></el-table-column
          >
          <el-table-column label="管理主口径" width="130" align="right"
            ><template #default="{ row }">{{ number(row.mainQuantity) }}</template></el-table-column
          >
          <el-table-column label="理论金额" width="135" align="right">
            <template #default="{ row }">{{ amount(row.theoreticalAmount) }}</template>
          </el-table-column>
          <el-table-column label="已核价实际金额" width="145" align="right">
            <template #default="{ row }">{{ amount(row.actualAmount) }}</template>
          </el-table-column>
          <el-table-column label="管理主口径金额" width="145" align="right">
            <template #default="{ row }">{{ amount(row.mainAmount) }}</template>
          </el-table-column>
          <el-table-column label="实际填报覆盖率" width="135" align="right">
            <template #default="{ row }">{{ deviation(row.actualCoverageRate) }}</template>
          </el-table-column>
          <el-table-column label="核价覆盖率" width="120" align="right">
            <template #default="{ row }">{{ deviation(row.pricingCoverageRate) }}</template>
          </el-table-column>
          <el-table-column label="覆盖科室" width="120" align="center"
            ><template #default="{ row }"
              ><el-popover placement="left" :width="260" trigger="click"
                ><template #reference
                  ><el-button link type="primary">{{ row.departmentCount }} 个科室</el-button></template
                >
                <div class="department-list">{{ (row.departments || []).join("、") || "无" }}</div></el-popover
              ></template
            ></el-table-column
          >
          <el-table-column label="核查状态" min-width="210"
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
      <div class="inventory-table-shell">
        <el-table :data="filteredDetails" border max-height="560" :row-key="detailKey" empty-text="没有符合筛选条件的核查明细">
          <el-table-column prop="businessDate" label="业务日期" width="110" /><el-table-column
            prop="departmentName"
            label="科室"
            width="120"
            show-overflow-tooltip
          /><el-table-column prop="materialName" label="耗材" min-width="170" show-overflow-tooltip /><el-table-column
            prop="unit"
            label="单位"
            width="78"
            align="center"
          /><el-table-column prop="serviceGroup" label="业务组" width="115" show-overflow-tooltip />
          <el-table-column label="业务量" width="88" align="right"
            ><template #default="{ row }">{{ number(row.volume) }}</template></el-table-column
          ><el-table-column label="理论量" width="105" align="right"
            ><template #default="{ row }">{{ number(row.theoreticalQuantity) }}</template></el-table-column
          ><el-table-column label="实际量" width="112" align="right"
            ><template #default="{ row }"
              ><span :class="{ 'not-reported': row.actualStatus === 'UNVERIFIED' }">{{
                row.actualStatus === "UNVERIFIED" ? "待核验" : number(row.actualQuantity)
              }}</span></template
            ></el-table-column
          ><el-table-column label="差额" width="96" align="right"
            ><template #default="{ row }">{{ row.difference == null ? "—" : number(row.difference) }}</template></el-table-column
          ><el-table-column label="偏差率" width="102" align="right"
            ><template #default="{ row }">{{ deviation(row.deviationRate) }}</template></el-table-column
          >
          <el-table-column label="核查结果" width="122"
            ><template #default="{ row }"
              ><el-tag :type="riskTagType(row.riskLevel)" size="small">{{ riskLabel(row.riskLevel) }}</el-tag></template
            ></el-table-column
          ><el-table-column label="特殊说明" min-width="160" show-overflow-tooltip
            ><template #default="{ row }"
              ><span v-if="row.isSpecial">{{ row.specialDailyNote || row.specialAdminNote || "未填写" }}</span
              ><span v-else>—</span></template
            ></el-table-column
          ><el-table-column label="复核" width="120"
            ><template #default="{ row }"
              ><el-tag :type="reviewTagType(row.reviewStatus)" size="small">{{ reviewLabel(row.reviewStatus) }}</el-tag></template
            ></el-table-column
          ><el-table-column fixed="right" label="操作" width="82"
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
  font-size: 18px;
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
  color: var(--el-text-color-primary);
  font-size: 14px;
}
.panel-section {
  display: grid;
  gap: 6px;
}
.inventory-table-shell {
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
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
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.daily-panel :deep(th.el-table__cell) {
  background: var(--el-fill-color-light);
  font-weight: 700;
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
