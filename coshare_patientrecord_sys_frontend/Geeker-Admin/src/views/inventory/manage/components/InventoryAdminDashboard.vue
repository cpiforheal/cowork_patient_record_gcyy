<template>
  <section ref="dashboardRootRef" class="dashboard" :class="{ 'is-loading': loading }" aria-label="管理员耗材数据驾驶舱">
    <div class="dashboard-head">
      <div>
        <div class="eyebrow">管理总览 · {{ periodLabel }}</div>
        <h3>耗材日报驾驶舱</h3>
        <p>优先查看实际使用量、填报覆盖与风险分布；点击图表或明细可直接定位核查。</p>
      </div>
      <div class="dashboard-actions">
        <el-button size="small" @click="emit('reset')">重置下钻</el-button>
        <el-button size="small" type="primary" plain @click="emit('drill', { riskLevel: 'ABNORMAL' })">仅看异常</el-button>
        <el-button size="small" @click="emit('drill', {})">查看全部</el-button>
      </div>
    </div>
    <div v-if="!report" class="dashboard-empty"><el-empty description="查询后显示驾驶舱" /></div>
    <template v-else>
      <div class="metric-grid">
        <article
          v-for="(metric, index) in metrics"
          :key="metric.label"
          class="metric-card"
          :class="metric.tone"
          data-reveal
          :style="{ '--i': index }"
        >
          <div class="metric-label">{{ metric.label }}</div>
          <div class="metric-value">{{ metric.value }}</div>
          <div class="metric-note">{{ metric.note }}</div>
        </article>
      </div>
      <div class="chart-grid">
        <article class="chart-card risk-card" data-reveal>
          <header>
            <div><strong>12 科室填报完成度</strong><span>每瓣对应一个科室，点击定位核查</span></div>
            <el-button link size="small" class="chart-expand-btn" @click="enlargeChart('risk')">放大</el-button>
          </header>
          <div class="pie-layout">
            <div ref="riskChartRoot" class="chart-box pie-box">
              <VChart
                v-if="chartLoaded.risk && hasDepartmentRisk"
                :option="riskOption"
                :update-options="{ replaceMerge: ['series'] }"
                autoresize
                @click="handleChartClick"
              />
              <el-empty v-else-if="chartLoaded.risk" description="暂无科室填报数据" />
              <div v-else class="chart-placeholder" aria-hidden="true"><el-skeleton :rows="4" animated /></div>
            </div>
            <div class="risk-list" aria-label="12科室填报明细">
              <div class="list-heading">
                <strong>科室填报明细</strong><span>{{ departmentCompletionRows.length }} 个科室</span>
              </div>
              <button
                v-for="row in departmentCompletionRows"
                :key="row.departmentKey"
                type="button"
                class="drill-list-item"
                :class="{ active: selectedDepartmentKey === row.departmentKey }"
                @click="handleDepartmentRowClick(row.departmentKey)"
              >
                <span class="item-name" :title="row.departmentName + ' · 期间业务人次 ' + number(row.volumeTotal)">{{
                  row.departmentName
                }}</span>
                <span class="item-value">
                  <small class="item-volume">{{ number(row.volumeTotal) }}人次</small>
                  <span class="risk-total">{{ row.submittedDayCount }} / {{ row.expectedDayCount }} 日</span>
                </span>
              </button>
              <el-empty v-if="!departmentCompletionRows.length" :image-size="48" description="暂无科室填报数据" />
            </div>
          </div>
        </article>
        <article class="chart-card trend-card" data-reveal>
          <header>
            <div><strong>每日实际量填报覆盖</strong><span>浅柱=应填报行 · 深柱=已填实际量 · 虚线=行覆盖率</span></div>
            <el-button link size="small" class="chart-expand-btn" @click="enlargeChart('coverageTrend')">放大</el-button>
          </header>
          <div ref="coverageTrendChartRoot" class="chart-box">
            <VChart
              v-if="chartLoaded.coverageTrend && hasDailyTrend"
              :option="coverageTrendOption"
              :update-options="{ replaceMerge: ['series'] }"
              autoresize
              @click="handleChartClick"
            />
            <el-empty v-else-if="chartLoaded.coverageTrend" description="暂无填报趋势" />
            <div v-else class="chart-placeholder" aria-hidden="true"><el-skeleton :rows="4" animated /></div>
          </div>
        </article>
        <article class="chart-card material-card" data-reveal>
          <header>
            <div>
              <strong>耗材实际使用量</strong><span>{{ materialScopeNote }}</span>
            </div>
            <div class="chart-controls">
              <el-button link size="small" class="chart-expand-btn" @click="enlargeChart('material')">放大</el-button>
              <el-select v-model="materialMode" size="small" class="chart-select" aria-label="耗材指标">
                <el-option label="实际使用量" value="quantity" />
                <el-option label="理论-实际量偏差" value="deviation" />
                <el-option label="填报覆盖率" value="coverage" />
              </el-select>
              <el-select v-model="materialScope" size="small" class="chart-select" aria-label="耗材范围">
                <el-option label="Top 10" value="top" />
                <el-option label="全部（当前返回数据）" value="all" />
              </el-select>
            </div>
          </header>
          <div class="material-view">
            <div ref="materialChartRoot" class="chart-box pie-box material-pie-box">
              <template v-if="chartLoaded.material && hasMaterialChart">
                <VChart
                  :option="materialOption"
                  :update-options="{ replaceMerge: ['series'] }"
                  autoresize
                  @click="handleChartClick"
                />
                <div v-if="!hasMaterialBars" class="chart-empty-overlay">
                  <el-empty :description="materialEmptyText" :image-size="56" />
                </div>
              </template>
              <el-empty v-else-if="chartLoaded.material" :description="materialEmptyText" />
              <div v-else class="chart-placeholder" aria-hidden="true"><el-skeleton :rows="4" animated /></div>
            </div>
            <div class="material-list" aria-label="耗材使用量明细">
              <div class="list-heading">
                <strong>耗材明细</strong><span>{{ materialRows.length }} 项 · {{ materialModeLabel }}</span>
              </div>
              <button
                v-for="(row, index) in materialRows"
                :key="materialKey(row)"
                type="button"
                class="drill-list-item material-item"
                :class="{ active: selectedMaterialIndex === index }"
                @click="handleMaterialRowClick(row, index)"
              >
                <span class="item-name" :title="row.materialName"
                  >{{ row.materialName }}<small>{{ row.unit }}</small></span
                >
                <span class="item-value">{{ materialDisplayValue(row) }}</span>
              </button>
              <el-empty v-if="!materialRows.length" :image-size="48" description="暂无耗材数据" />
            </div>
          </div>
        </article>
        <article class="chart-card trend-card" data-reveal>
          <header>
            <div><strong>每日风险趋势</strong><span>异常峰值标记</span></div>
            <el-button link size="small" class="chart-expand-btn" @click="enlargeChart('riskTrend')">放大</el-button>
          </header>
          <div ref="riskTrendChartRoot" class="chart-box">
            <VChart
              v-if="chartLoaded.riskTrend && hasDailyTrend"
              :option="riskTrendOption"
              :update-options="{ replaceMerge: ['series'] }"
              autoresize
              @click="handleChartClick"
            />
            <el-empty v-else-if="chartLoaded.riskTrend" description="暂无风险趋势" />
            <div v-else class="chart-placeholder" aria-hidden="true"><el-skeleton :rows="4" animated /></div>
          </div>
        </article>
      </div>
    </template>
    <el-dialog
      v-model="enlargedDialogVisible"
      :title="enlargedTitle"
      width="min(1100px, 94vw)"
      top="4vh"
      destroy-on-close
      append-to-body
      class="enlarge-dialog"
    >
      <div class="enlarged-chart-box" :style="enlargedChartStyle">
        <VChart
          v-if="enlargedChart"
          :option="enlargedOption"
          :update-options="{ replaceMerge: ['series'] }"
          autoresize
          @click="handleChartClick"
        />
      </div>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { LineChart, PieChart, BarChart } from "echarts/charts";
import { DataZoomComponent, GridComponent, LegendComponent, MarkPointComponent, TooltipComponent } from "echarts/components";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import VChart from "vue-echarts";
import type { EChartsOption } from "echarts";
import type { InventoryAdminDepartmentDailyRollup, InventoryAdminMaterialSummary } from "@/api/modules/inventory";

use([
  CanvasRenderer,
  LineChart,
  PieChart,
  BarChart,
  GridComponent,
  DataZoomComponent,
  LegendComponent,
  MarkPointComponent,
  TooltipComponent
]);

type Drill = { businessDate?: string; departmentKey?: string; materialName?: string; riskLevel?: string };
type MaterialMode = "quantity" | "deviation" | "coverage";
type MaterialScope = "top" | "all";

const props = defineProps<{ report?: InventoryAdminDepartmentDailyRollup; loading?: boolean }>();
const emit = defineEmits<{ drill: [payload: Drill]; reset: [] }>();
const report = computed(() => props.report);
const dashboard = computed(() => props.report?.dashboard);
const periodLabel = computed(() => (props.report ? props.report.periodStart + " 至 " + props.report.periodEnd : ""));
const number = (value: number | null | undefined) =>
  value == null ? "—" : Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
const percent = (value: number | null | undefined) =>
  value == null ? "—" : (Number(value) * 100).toLocaleString("zh-CN", { maximumFractionDigits: 1 }) + "%";
const tooltipSurface = {
  backgroundColor: "#fff",
  borderColor: "rgb(23 33 43 / 12%)",
  borderWidth: 1,
  borderRadius: 8,
  padding: [10, 12],
  textStyle: { color: "#17212b", fontSize: 12, lineHeight: 19 },
  extraCssText: "box-shadow:0 10px 28px rgb(23 33 43 / 14%);"
};

const detailLineTotal = computed(() => (props.report?.details || []).length);
const actualCoverageOverall = computed(() => {
  const reported = dashboard.value?.reportedLineCount;
  if (!reported || detailLineTotal.value <= 0) return null;
  return reported / detailLineTotal.value;
});

const metrics = computed(() => {
  const d = dashboard.value;
  if (!d) return [];
  return [
    {
      label: "日报完成率",
      value: percent(d.completionRate),
      note: d.submittedDepartmentDays + " / " + d.expectedDepartmentDays + " 个科室日已提交",
      tone: "tone-primary"
    },
    { label: "未填报科室日", value: number(d.missingDepartmentDays), note: "风险分布中查看科室明细", tone: "tone-danger" },
    {
      label: "实际量填报覆盖率",
      value: percent(actualCoverageOverall.value),
      note: number(d.reportedLineCount) + " / " + number(detailLineTotal.value) + " 行已填实际量",
      tone: "tone-success"
    },
    {
      label: "关注 / 异常",
      value: number(d.attentionCount) + " / " + number(d.abnormalCount),
      note: "异常优先处理",
      tone: "tone-warning"
    },
    { label: "待核验耗材行", value: number(d.unverifiedCount), note: "实际量为空，不计入偏差", tone: "tone-info" },
    { label: "特殊待说明", value: number(d.specialPendingNoteCount), note: "特殊耗材说明未完成", tone: "tone-purple" }
  ];
});

const materialMode = ref<MaterialMode>("quantity");
const materialScope = ref<MaterialScope>("top");
const selectedMaterialIndex = ref<number | null>(null);
const selectedDepartmentKey = ref<string | null>(null);
type ChartId = "risk" | "coverageTrend" | "material" | "riskTrend";
const chartLoaded = reactive<Record<ChartId, boolean>>({ risk: false, coverageTrend: false, material: false, riskTrend: false });
const riskChartRoot = ref<HTMLElement | null>(null);
const coverageTrendChartRoot = ref<HTMLElement | null>(null);
const materialChartRoot = ref<HTMLElement | null>(null);
const riskTrendChartRoot = ref<HTMLElement | null>(null);
let chartObserver: IntersectionObserver | null = null;
let chartLoadFallbackTimer: number | null = null;
let revealObserver: IntersectionObserver | null = null;
const dashboardRootRef = ref<HTMLElement | null>(null);
const revealVisibleCards = () => {
  dashboardRootRef.value?.querySelectorAll<HTMLElement>("[data-reveal]:not(.revealed)").forEach(node => {
    node.classList.add("revealed");
  });
};
const observeRevealCards = () => {
  const cards = dashboardRootRef.value?.querySelectorAll<HTMLElement>("[data-reveal]:not(.revealed)");
  if (!cards?.length) return;
  if (prefersReducedMotion.value || typeof IntersectionObserver === "undefined") {
    revealVisibleCards();
    return;
  }
  revealObserver?.disconnect();
  revealObserver = new IntersectionObserver(
    entries => {
      entries.forEach(entry => {
        if (!entry.isIntersecting) return;
        (entry.target as HTMLElement).classList.add("revealed");
        revealObserver?.unobserve(entry.target);
      });
    },
    { rootMargin: "0px 0px -6% 0px", threshold: 0.08 }
  );
  cards.forEach(node => revealObserver?.observe(node));
};
let dashboardMounted = false;
let motionMediaQuery: MediaQueryList | null = null;
const prefersReducedMotion = ref(false);
const handleMotionChange = (event: MediaQueryListEvent) => {
  prefersReducedMotion.value = event.matches;
};
const handleChartViewportChange = () => observeChartRoots();
watch([materialMode, materialScope], () => {
  selectedMaterialIndex.value = null;
});
watch(
  () => props.report,
  () => {
    selectedDepartmentKey.value = null;
    if (dashboardMounted)
      void nextTick(() => {
        observeChartRoots();
        observeRevealCards();
      });
  }
);

const hasDailyTrend = computed(() => Boolean(dashboard.value?.dailyTrend?.length));
const departmentCompletionRows = computed(() => {
  const expectedDayCount = report.value?.departmentCount
    ? Math.max(1, Math.round((dashboard.value?.expectedDepartmentDays || 0) / report.value.departmentCount))
    : Math.max(1, dashboard.value?.dailyTrend?.length || 1);
  const volumeTotals = new Map<string, number>();
  (report.value?.departmentDays || []).forEach(day => {
    if (day.businessVolume == null) return;
    volumeTotals.set(day.departmentKey, (volumeTotals.get(day.departmentKey) || 0) + day.businessVolume);
  });
  return (report.value?.departments || []).map(row => {
    const submittedDayCount = Math.min(
      expectedDayCount,
      Math.max(0, row.submittedDayCount ?? (row.status === "SUBMITTED" ? 1 : 0))
    );
    return {
      ...row,
      expectedDayCount,
      submittedDayCount,
      completionRate: submittedDayCount / expectedDayCount,
      volumeTotal: volumeTotals.get(row.departmentKey) ?? 0
    };
  });
});
const hasDepartmentRisk = computed(() => departmentCompletionRows.value.length > 0);
const materialKey = (row: InventoryAdminMaterialSummary) => row.materialName + "\u0000" + row.unit;
// Full-period summary (not the pricing-filtered Top list) so unpriced materials still render in quantity mode.
// Keep all summary rows for list stability; null values for the current mode are dropped only in materialChartRows.
const materialSourceRows = computed<InventoryAdminMaterialSummary[]>(() => report.value?.summary || []);
const materialRows = computed<InventoryAdminMaterialSummary[]>(() => {
  const direction = materialMode.value === "coverage" ? 1 : -1;
  const rows = [...materialSourceRows.value].sort(
    (left, right) => direction * (materialMetricValue(left) - materialMetricValue(right))
  );
  return materialScope.value === "top" ? rows.slice(0, 10) : rows;
});
const materialModeLabel = computed(
  () => ({ quantity: "实际使用量", deviation: "理论-实际量偏差", coverage: "填报覆盖率" })[materialMode.value]
);
const materialScopeNote = computed(() => {
  if (materialScope.value === "top") return "按当前口径排序取前 10 · 覆盖率模式按最低在前 · 点击下钻";
  return "全院口径全部耗材（" + materialRows.value.length + " 项）· 滚轮可滚动查看";
});
const materialQuantityDifference = (row: InventoryAdminMaterialSummary) =>
  row.actualQuantity == null ? null : row.actualQuantity - row.theoreticalQuantity;
const materialRawValue = (row: InventoryAdminMaterialSummary) => {
  if (materialMode.value === "quantity") return row.actualQuantity;
  if (materialMode.value === "deviation") return materialQuantityDifference(row);
  return row.actualCoverageRate;
};
const materialReferenceValue = (row: InventoryAdminMaterialSummary) => row.theoreticalQuantity;
const materialMetricValue = (row: InventoryAdminMaterialSummary) => {
  const value = Number(materialRawValue(row) ?? 0);
  return materialMode.value === "deviation" ? Math.abs(value) : Math.max(0, value);
};
const materialDisplayValue = (row: InventoryAdminMaterialSummary) => {
  const value = materialRawValue(row);
  if (value == null) return "未填报";
  if (materialMode.value === "quantity") return number(value) + " " + row.unit;
  if (materialMode.value === "coverage") return percent(value);
  const text = number(Math.abs(value));
  return value > 0 ? "+" + text : value < 0 ? "-" + text : "0";
};
// Bars keep the signed deviation value so negative differences render leftwards.
const materialChartValue = (row: InventoryAdminMaterialSummary) => {
  const value = Number(materialRawValue(row) ?? 0);
  return materialMode.value === "deviation" ? value : Math.max(0, value);
};
const materialChartRows = computed(() => {
  const rows = materialRows.value.map((row, index) => ({ row, index, value: materialChartValue(row) }));
  if (materialMode.value === "coverage") return rows;
  // Keep bars with a reported value for the current mode; drop null and zero so unreported materials don't clutter.
  return rows.filter(item => materialRawValue(item.row) != null && item.value !== 0);
});
// Base on summary existence so VChart stays mounted across mode switches, preventing destroy/recreate flicker.
const hasMaterialChart = computed(() => (report.value?.summary || []).length > 0);
const hasMaterialBars = computed(() => materialChartRows.value.length > 0);
const materialEmptyText = computed(() => {
  const summary = report.value?.summary || [];
  if (!summary.length) return "该时间段暂无科室填报";
  const hasReported = summary.some(row => materialRawValue(row) != null);
  if (!hasReported) {
    if (materialMode.value === "quantity") return "该时间段暂无耗材实际量填报";
    if (materialMode.value === "coverage") return "该时间段暂无可计算的覆盖率";
    return "该时间段暂无可计算的理论-实际量偏差";
  }
  return "当前口径数值均为 0";
});

// ---- Chart enlarge dialog ----
const enlargedChart = ref<ChartId | null>(null);
const enlargedDialogVisible = computed({
  get: () => enlargedChart.value !== null,
  set: (val: boolean) => {
    if (!val) enlargedChart.value = null;
  }
});
const enlargedTitle = computed(() => {
  switch (enlargedChart.value) {
    case "risk":
      return "12 科室填报完成度";
    case "coverageTrend":
      return "每日实际量填报覆盖";
    case "material":
      return "耗材实际使用量 · " + materialModeLabel.value;
    case "riskTrend":
      return "每日风险趋势";
    default:
      return "图表放大";
  }
});
const enlargeChart = (id: ChartId) => {
  enlargedChart.value = id;
};
const enlargedChartStyle = computed(() => {
  if (enlargedChart.value === "material") {
    const count = materialChartRows.value.length;
    return { height: Math.max(480, count * 26) + "px", padding: "4px 8px" };
  }
  return { height: "min(74vh, 680px)", padding: "4px 8px" };
});
const enlargedOption = computed<EChartsOption | null>(() => {
  const id = enlargedChart.value;
  if (!id) return null;
  if (id === "material") {
    const opt = materialOption.value;
    return {
      ...opt,
      dataZoom: undefined,
      grid: { left: 10, right: 90, top: (opt.grid as any)?.top ?? 14, bottom: 10, containLabel: true },
      yAxis: {
        ...(opt.yAxis as any),
        axisLabel: { color: palette.text, fontSize: 12, width: 220, overflow: "truncate" }
      },
      series: (opt.series as any[])?.map(s => ({
        ...s,
        barWidth: 18,
        label: s.name === "实际" ? { ...s.label, fontSize: 12 } : s.label
      }))
    } as EChartsOption;
  }
  if (id === "risk") {
    const opt = riskOption.value;
    return {
      ...opt,
      series: opt.series?.map(s => ({
        ...s,
        radius: ["26%", "74%"],
        label: { show: true, formatter: "{b}\n{d}%", fontSize: 12, color: palette.text },
        labelLine: { show: true, length: 8, length2: 12 }
      }))
    } as EChartsOption;
  }
  const trendOpt = (
    {
      coverageTrend: coverageTrendOption.value,
      riskTrend: riskTrendOption.value
    } as Record<ChartId, EChartsOption>
  )[id];
  return {
    ...trendOpt,
    grid: { left: 20, right: 30, top: 30, bottom: 50, containLabel: true },
    xAxis: {
      ...(trendOpt.xAxis as any),
      axisLabel: { ...(trendOpt.xAxis as any)?.axisLabel, interval: 0, fontSize: 11 }
    }
  } as EChartsOption;
});

const palette = {
  primary: "#08766f",
  info: "#4f7cac",
  warning: "#c9822b",
  danger: "#c83232",
  purple: "#7655b7",
  text: "#17212b",
  muted: "#647282"
};
const materialColors = [
  "#08766f",
  "#2f9d91",
  "#4f7cac",
  "#6ca6cf",
  "#c9822b",
  "#d99a4a",
  "#7655b7",
  "#9274c5",
  "#c83232",
  "#e06b6b"
];
const grid = { left: 52, right: 24, top: 54, bottom: 44, containLabel: true };
const chartMotion = computed(() => ({
  animation: !prefersReducedMotion.value,
  animationDuration: prefersReducedMotion.value ? 0 : 520,
  animationDurationUpdate: prefersReducedMotion.value ? 0 : 360,
  animationEasing: "cubicOut" as const,
  animationEasingUpdate: "cubicOut" as const,
  animationThreshold: 2200
}));
const trendLegend = (items: string[]) => ({
  top: 10,
  left: "center",
  data: items,
  icon: "roundRect",
  itemWidth: 10,
  itemHeight: 6,
  itemGap: 16,
  textStyle: { color: palette.muted, fontSize: 11 }
});
const trendXAxis = (labels: string[]) => ({
  type: "category" as const,
  boundaryGap: false,
  data: labels,
  axisTick: { show: false },
  axisLine: { lineStyle: { color: "#d9e2e7" } },
  axisLabel: { color: palette.muted, fontSize: 11, hideOverlap: true, margin: 12 }
});
const trendYAxis = (name: string, extra: Record<string, unknown> = {}) => ({
  type: "value" as const,
  name,
  nameTextStyle: { color: palette.muted, fontSize: 11, padding: [0, 0, 0, 4] },
  axisLabel: { color: palette.muted, fontSize: 11 },
  axisLine: { show: false },
  axisTick: { show: false },
  splitLine: { lineStyle: { color: "rgb(23 33 43 / 7%)", type: "dashed" as const } },
  ...extra
});
const trendDataZoom = [
  { type: "inside" as const, start: 0, end: 100 },
  {
    type: "slider" as const,
    height: 14,
    bottom: 6,
    start: 0,
    end: 100,
    borderColor: "transparent",
    backgroundColor: "rgb(23 33 43 / 4%)",
    fillerColor: "rgb(8 118 111 / 14%)",
    handleStyle: { color: "#08766f", borderColor: "#08766f" },
    textStyle: { color: palette.muted }
  }
];

const departmentCompletionTotal = computed(() =>
  departmentCompletionRows.value.reduce((total, row) => total + row.submittedDayCount, 0)
);

const riskOption = computed<EChartsOption>(() => ({
  ...chartMotion.value,
  tooltip: {
    ...tooltipSurface,
    trigger: "item",
    formatter: (params: any) => {
      const row = departmentCompletionRows.value[params.dataIndex];
      if (!row) return "";
      return (
        row.departmentName +
        "<br/>已填报：<b>" +
        row.submittedDayCount +
        " / " +
        row.expectedDayCount +
        "</b> 个科室日<br/>完成率：<b>" +
        percent(row.completionRate) +
        "</b>"
      );
    }
  },
  graphic: [
    {
      type: "text",
      left: "50%",
      top: "37%",
      style: {
        text:
          number(departmentCompletionTotal.value) +
          " / " +
          number(dashboard.value?.expectedDepartmentDays) +
          "\n已填报科室日\n点击扇区定位",
        textAlign: "center",
        fill: palette.text,
        fontSize: 14,
        fontWeight: 700,
        lineHeight: 22
      }
    }
  ],
  series: [
    {
      name: "12科室填报完成度",
      type: "pie",
      roseType: "area",
      radius: ["28%", "72%"],
      center: ["50%", "52%"],
      minAngle: 5,
      padAngle: 2,
      animationType: "expansion",
      animationDelay: (index: number) => index * 55,
      animationDelayUpdate: (index: number) => index * 20,
      itemStyle: { borderRadius: 8, borderColor: "#fff", borderWidth: 3 },
      emphasis: {
        focus: "self",
        scale: true,
        scaleSize: 8,
        itemStyle: { shadowBlur: 16, shadowOffsetY: 4, shadowColor: "rgb(23 33 43 / 20%)" }
      },
      blur: { itemStyle: { opacity: 0.38 } },
      label: { show: false },
      labelLine: { show: false },
      data: departmentCompletionRows.value.map((row, index) => ({
        name: row.departmentName,
        // Keep missing departments visible as a thin sector while preserving the real value in tooltip.
        value: row.submittedDayCount || 0.25,
        itemStyle: { color: materialColors[index % materialColors.length] }
      }))
    }
  ]
}));

const materialAxisName = (row: InventoryAdminMaterialSummary) => {
  const name = row.materialName.length > 11 ? row.materialName.slice(0, 10) + "…" : row.materialName;
  return name + " " + row.unit;
};
const materialBarLabelFormatter = (value: number) => {
  if (materialMode.value === "coverage") return Math.round(value * 1000) / 10 + "%";
  const text = number(Math.abs(value));
  if (materialMode.value === "deviation") return value > 0 ? "+" + text : value < 0 ? "-" + text : "0";
  return text;
};
const materialDeviationRate = (row: InventoryAdminMaterialSummary) => {
  const difference = materialQuantityDifference(row);
  if (difference == null || row.theoreticalQuantity <= 0) return null;
  return difference / row.theoreticalQuantity;
};
const materialOption = computed<EChartsOption>(() => {
  const rows = materialChartRows.value;
  const isDeviation = materialMode.value === "deviation";
  const isCoverage = materialMode.value === "coverage";
  const hasReference = materialMode.value === "quantity";
  const axisLabels = rows.map(item => materialAxisName(item.row));
  return {
    ...chartMotion.value,
    grid: { left: 10, right: 74, top: hasReference ? 34 : 14, bottom: 10, containLabel: true },
    legend: hasReference
      ? {
          top: 6,
          left: 6,
          data: ["实际", "理论参考"],
          icon: "roundRect",
          itemWidth: 10,
          itemHeight: 6,
          itemGap: 14,
          textStyle: { color: palette.muted, fontSize: 11 }
        }
      : undefined,
    tooltip: {
      ...tooltipSurface,
      trigger: "axis",
      axisPointer: { type: "shadow", shadowStyle: { color: "rgb(8 118 111 / 6%)" } },
      formatter: (raw: any) => {
        const params = Array.isArray(raw) ? raw : [raw];
        const item = rows[params[0]?.dataIndex];
        if (!item) return "";
        const row = item.row;
        const header =
          row.materialName +
          "（" +
          row.unit +
          "）<br/>已填报：<b>" +
          number(row.filledActualLineCount) +
          " / " +
          number(row.lineCount) +
          " 行</b> · 覆盖率 " +
          percent(row.actualCoverageRate);
        if (materialMode.value === "quantity") {
          return (
            header +
            "<br/>实际使用量：<b>" +
            number(row.actualQuantity) +
            " " +
            row.unit +
            "</b><br/>理论参考：" +
            number(row.theoreticalQuantity) +
            " " +
            row.unit +
            "<br/>覆盖科室：" +
            number(row.departmentCount) +
            " 个"
          );
        }
        if (isDeviation) {
          return (
            header +
            "<br/>量偏差：<b>" +
            materialDisplayValue(row) +
            " " +
            row.unit +
            "</b><br/>实际：" +
            number(row.actualQuantity) +
            " · 理论：" +
            number(row.theoreticalQuantity) +
            "<br/>偏差率：" +
            (materialDeviationRate(row) == null ? "—" : percent(materialDeviationRate(row)))
          );
        }
        return (
          header +
          "<br/>填报覆盖率：<b>" +
          percent(row.actualCoverageRate) +
          "</b><br/>风险行：" +
          number(row.unverifiedCount + row.attentionCount + row.abnormalCount + row.specialPendingNoteCount) +
          "（未核验 " +
          number(row.unverifiedCount) +
          " · 关注 " +
          number(row.attentionCount) +
          " · 异常 " +
          number(row.abnormalCount) +
          "）<br/>覆盖科室：" +
          number(row.departmentCount) +
          " 个"
        );
      }
    },
    xAxis: {
      type: "value",
      ...(isCoverage ? { max: 1 } : {}),
      axisLabel: {
        color: palette.muted,
        fontSize: 11,
        formatter: (value: number) => materialBarLabelFormatter(value)
      },
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: "rgb(23 33 43 / 7%)", type: "dashed" } }
    },
    yAxis: {
      type: "category",
      inverse: true,
      data: axisLabels,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: "#d9e2e7" } },
      axisLabel: { color: palette.text, fontSize: 11, width: 118, overflow: "truncate" }
    },
    dataZoom:
      rows.length > 12
        ? [
            { type: "inside", yAxisIndex: 0, startValue: 0, endValue: 11, zoomOnMouseWheel: true },
            {
              type: "slider",
              yAxisIndex: 0,
              width: 10,
              startValue: 0,
              endValue: 11,
              labelFormatter: (value: number) => {
                const r = rows[value]?.row;
                return r ? materialAxisName(r) : "";
              }
            }
          ]
        : undefined,
    series: [
      ...(hasReference
        ? [
            {
              name: "理论参考",
              type: "bar" as const,
              barWidth: 14,
              barGap: "-100%",
              z: 1,
              itemStyle: { color: "rgb(8 118 111 / 10%)", borderColor: "rgb(8 118 111 / 28%)", borderWidth: 1, borderRadius: 3 },
              emphasis: { disabled: true },
              silent: true,
              data: rows.map(item => materialReferenceValue(item.row) ?? 0)
            }
          ]
        : []),
      {
        name: "实际",
        type: "bar" as const,
        barWidth: 14,
        z: 3,
        itemStyle: {
          borderRadius: 3,
          color: (params: any) => {
            if (isDeviation) return params.value >= 0 ? palette.warning : palette.info;
            if (isCoverage) return params.value >= 0.8 ? palette.primary : params.value >= 0.5 ? palette.warning : palette.danger;
            return palette.primary;
          }
        },
        emphasis: { itemStyle: { shadowBlur: 12, shadowOffsetY: 3, shadowColor: "rgb(23 33 43 / 22%)" } },
        label: {
          show: true,
          color: palette.muted,
          fontSize: 11,
          formatter: (params: any) => materialBarLabelFormatter(params.value)
        },
        selectedMode: false,
        data: rows.map(item => ({
          value: item.value,
          label: { position: item.value < 0 ? ("left" as const) : ("right" as const) },
          itemStyle:
            selectedMaterialIndex.value === item.index
              ? { borderColor: isDeviation ? palette.text : palette.primary, borderWidth: 1.5 }
              : undefined
        }))
      }
    ]
  };
});

const coverageTrendOption = computed<EChartsOption>(() => {
  const rows = dashboard.value?.dailyTrend || [];
  return {
    ...chartMotion.value,
    grid,
    tooltip: {
      ...tooltipSurface,
      trigger: "axis",
      axisPointer: { type: "shadow", shadowStyle: { color: "rgb(8 118 111 / 7%)" } },
      formatter: (raw: any) => {
        const p = Array.isArray(raw) ? raw : [raw];
        const row = rows[p[0]?.dataIndex];
        if (!row) return "";
        const coverage = row.lineCount ? row.reportedLineCount / row.lineCount : null;
        return (
          row.businessDate +
          "<br/>已填实际量行：<b>" +
          number(row.reportedLineCount) +
          " / " +
          number(row.lineCount) +
          " 行</b><br/>行覆盖率：<b>" +
          percent(coverage) +
          "</b><br/>提交科室日：" +
          number(row.submittedDepartmentDays) +
          " / " +
          number(row.expectedDepartmentDays) +
          "<br/>未核验行：" +
          number(row.unverifiedCount)
        );
      }
    },
    legend: trendLegend(["应填报行", "已填实际量行", "行覆盖率"]),
    xAxis: { ...trendXAxis(rows.map(row => row.businessDate.slice(5))), boundaryGap: true },
    dataZoom: trendDataZoom,
    yAxis: [
      trendYAxis("行数", { minInterval: 1 }),
      trendYAxis("覆盖率", {
        max: 1,
        splitLine: { show: false },
        axisLabel: { color: palette.muted, fontSize: 11, formatter: (value: number) => value * 100 + "%" }
      })
    ],
    series: [
      {
        name: "应填报行",
        type: "bar",
        barWidth: 14,
        z: 1,
        itemStyle: { color: "rgb(23 33 43 / 8%)", borderRadius: 3 },
        silent: true,
        emphasis: { disabled: true },
        data: rows.map(row => row.lineCount),
        animationDelay: 0
      },
      {
        name: "已填实际量行",
        type: "bar",
        barWidth: 14,
        z: 3,
        itemStyle: { color: palette.primary, borderRadius: 3 },
        emphasis: { itemStyle: { shadowBlur: 12, shadowOffsetY: 3, shadowColor: "rgb(23 33 43 / 20%)" } },
        data: rows.map(row => row.reportedLineCount),
        animationDelay: 60
      },
      {
        name: "行覆盖率",
        type: "line",
        yAxisIndex: 1,
        smooth: true,
        data: rows.map(row => (row.lineCount ? row.reportedLineCount / row.lineCount : null)),
        symbol: "circle",
        showSymbol: false,
        lineStyle: { width: 2.25, type: "dashed" },
        itemStyle: { color: palette.warning },
        emphasis: { focus: "series", lineStyle: { width: 3.25 }, itemStyle: { borderWidth: 3 } },
        animationDelay: 120
      }
    ]
  };
});

const riskTrendOption = computed<EChartsOption>(() => {
  const rows = dashboard.value?.dailyTrend || [];
  return {
    ...chartMotion.value,
    grid,
    tooltip: {
      ...tooltipSurface,
      trigger: "axis",
      axisPointer: { type: "shadow", shadowStyle: { color: "rgb(8 118 111 / 7%)" } },
      formatter: (raw: any) => {
        const params = Array.isArray(raw) ? raw : [raw];
        const row = rows[params[0]?.dataIndex];
        if (!row) return "";
        const total =
          Number(row.unverifiedCount || 0) +
          Number(row.attentionCount || 0) +
          Number(row.abnormalCount || 0) +
          Number(row.specialPendingNoteCount || 0);
        return (
          row.businessDate +
          "<br/>" +
          params
            .map((item: any) => item.marker + item.seriesName + "：<b>" + number(Number(item.value || 0)) + "</b>")
            .join("<br/>") +
          "<br/>风险合计：<b>" +
          number(total) +
          "</b>"
        );
      }
    },
    legend: trendLegend(["未核验", "关注", "异常", "特殊待说明"]),
    xAxis: trendXAxis(rows.map(row => row.businessDate.slice(5))),
    yAxis: trendYAxis("风险数", { minInterval: 1 }),
    dataZoom: trendDataZoom,
    series: [
      {
        name: "未核验",
        type: "line",
        stack: "risk",
        smooth: true,
        symbol: "circle",
        showSymbol: false,
        lineStyle: { width: 2 },
        emphasis: { focus: "series", lineStyle: { width: 3.25 }, itemStyle: { borderWidth: 3 } },
        areaStyle: { opacity: 0.1 },
        data: rows.map(row => row.unverifiedCount),
        itemStyle: { color: "#8aa8c3" },
        animationDelay: 0
      },
      {
        name: "关注",
        type: "line",
        stack: "risk",
        smooth: true,
        symbol: "circle",
        showSymbol: false,
        lineStyle: { width: 2 },
        emphasis: { focus: "series", lineStyle: { width: 3.25 }, itemStyle: { borderWidth: 3 } },
        areaStyle: { opacity: 0.11 },
        data: rows.map(row => row.attentionCount),
        itemStyle: { color: palette.warning },
        animationDelay: 45
      },
      {
        name: "异常",
        type: "line",
        stack: "risk",
        smooth: true,
        symbol: "circle",
        showSymbol: false,
        lineStyle: { width: 2.5 },
        emphasis: { focus: "series", lineStyle: { width: 3.5 }, itemStyle: { borderWidth: 3 } },
        areaStyle: { opacity: 0.2 },
        data: rows.map(row => row.abnormalCount),
        itemStyle: { color: palette.danger },
        markPoint: {
          symbolSize: 32,
          label: { color: "#fff", fontSize: 10 },
          itemStyle: { color: palette.danger },
          data: rows.length ? [{ type: "max", name: "异常峰值" }] : []
        },
        animationDelay: 90
      },
      {
        name: "特殊待说明",
        type: "line",
        stack: "risk",
        smooth: true,
        symbol: "circle",
        showSymbol: false,
        lineStyle: { width: 2 },
        emphasis: { focus: "series", lineStyle: { width: 3.25 }, itemStyle: { borderWidth: 3 } },
        areaStyle: { opacity: 0.11 },
        data: rows.map(row => row.specialPendingNoteCount),
        itemStyle: { color: palette.purple },
        animationDelay: 135
      }
    ]
  };
});

const handleDepartmentRowClick = (departmentKey: string) => {
  selectedDepartmentKey.value = departmentKey;
  emit("drill", { departmentKey });
};

function observeChartRoots() {
  if (!dashboardMounted) return;
  const roots: Array<[ChartId, HTMLElement | null]> = [
    ["risk", riskChartRoot.value],
    ["coverageTrend", coverageTrendChartRoot.value],
    ["material", materialChartRoot.value],
    ["riskTrend", riskTrendChartRoot.value]
  ];
  if (typeof IntersectionObserver === "undefined") {
    roots.forEach(([id]) => (chartLoaded[id] = true));
    return;
  }
  if (!chartObserver) {
    chartObserver = new IntersectionObserver(
      entries => {
        entries.forEach(entry => {
          if (!entry.isIntersecting) return;
          const id = (entry.target as HTMLElement).dataset.chartId as ChartId | undefined;
          if (id) chartLoaded[id] = true;
          chartObserver?.unobserve(entry.target);
        });
      },
      { rootMargin: "160px 0px" }
    );
  }
  roots.forEach(([id, root]) => {
    if (!root || chartLoaded[id]) return;
    root.dataset.chartId = id;
    chartObserver?.observe(root);
  });
  if (chartLoadFallbackTimer != null) window.clearTimeout(chartLoadFallbackTimer);
  chartLoadFallbackTimer = window.setTimeout(() => {
    roots.forEach(([id, root]) => {
      if (!root || chartLoaded[id]) return;
      const rect = root.getBoundingClientRect();
      if (rect.top <= window.innerHeight + 160 && rect.bottom >= -160) {
        chartLoaded[id] = true;
        chartObserver?.unobserve(root);
      }
    });
    chartLoadFallbackTimer = null;
  }, 0);
}

const handleMaterialRowClick = (row: InventoryAdminMaterialSummary, index: number) => {
  selectedMaterialIndex.value = index;
  emit("drill", { materialName: row.materialName });
};

onMounted(() => {
  dashboardMounted = true;
  motionMediaQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
  prefersReducedMotion.value = motionMediaQuery.matches;
  motionMediaQuery.addEventListener("change", handleMotionChange);
  window.addEventListener("resize", handleChartViewportChange);
  window.addEventListener("scroll", handleChartViewportChange, true);
  void nextTick(() => {
    observeChartRoots();
    observeRevealCards();
  });
});

onBeforeUnmount(() => {
  chartObserver?.disconnect();
  chartObserver = null;
  revealObserver?.disconnect();
  revealObserver = null;
  motionMediaQuery?.removeEventListener("change", handleMotionChange);
  window.removeEventListener("resize", handleChartViewportChange);
  window.removeEventListener("scroll", handleChartViewportChange, true);
  if (chartLoadFallbackTimer != null) window.clearTimeout(chartLoadFallbackTimer);
});

const handleChartClick = (params: any) => {
  const trend = dashboard.value?.dailyTrend || [];
  if (params.componentType === "series" && params.seriesType === "pie") {
    if (params.seriesName === "12科室填报完成度") {
      const item = departmentCompletionRows.value[params.dataIndex];
      if (item) {
        selectedDepartmentKey.value = item.departmentKey;
        emit("drill", { departmentKey: item.departmentKey });
      }
      return;
    }
    const item = materialChartRows.value[params.dataIndex];
    if (item) {
      selectedMaterialIndex.value = item.index;
      emit("drill", { materialName: item.row.materialName });
    }
    return;
  }
  if (params.componentType === "series" && params.seriesType === "bar" && params.seriesName === "实际") {
    const item = materialChartRows.value[params.dataIndex];
    if (item) {
      selectedMaterialIndex.value = item.index;
      emit("drill", { materialName: item.row.materialName });
    }
    return;
  }
  if (
    params.dataIndex != null &&
    trend[params.dataIndex] &&
    params.seriesName &&
    ["未核验", "关注", "异常", "特殊待说明", "应填报行", "已填实际量行", "行覆盖率"].includes(params.seriesName)
  ) {
    const riskMap: Record<string, string> = {
      未核验: "UNVERIFIED",
      关注: "ATTENTION",
      异常: "ABNORMAL",
      特殊待说明: "SPECIAL_PENDING_NOTE"
    };
    const businessDate = trend[params.dataIndex].businessDate;
    const riskLevel = riskMap[params.seriesName];
    emit("drill", riskLevel ? { businessDate, riskLevel } : { businessDate });
  }
};
</script>
<style scoped lang="scss">
.dashboard {
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--inventory-line-soft);
  border-radius: 12px;
  background: #fcfdfe;
  box-shadow: 0 6px 20px rgb(23 33 43 / 3%);
}
.dashboard.is-loading .metric-grid,
.dashboard.is-loading .chart-grid {
  opacity: 0.55;
  transition: opacity 220ms ease-out;
  pointer-events: none;
}
.dashboard-head {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  align-items: flex-start;
}
.dashboard-head h3 {
  margin: 3px 0 4px;
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--inventory-text);
}
.dashboard-head p,
.eyebrow {
  margin: 0;
  color: var(--inventory-muted);
  font-size: 13px;
}
.eyebrow {
  font-weight: 500;
  letter-spacing: 0.06em;
}
.dashboard-actions,
.chart-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chart-controls {
  justify-content: flex-end;
}
.chart-select {
  width: 132px;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(130px, 1fr));
  gap: 10px;
}
.metric-card {
  min-height: 108px;
  padding: 14px;
  border: 1px solid var(--inventory-line-soft);
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 1px 2px rgb(23 33 43 / 2%);
  transition:
    transform 220ms ease-out,
    box-shadow 220ms ease-out,
    border-color 220ms ease-out;
}
.metric-card:hover {
  transform: translateY(-1px);
  border-color: var(--inventory-line);
  box-shadow: 0 6px 16px rgb(23 33 43 / 5%);
}
.metric-label {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--inventory-muted);
  font-size: 12px;
  font-weight: 500;
}
.metric-label::before {
  content: "";
  width: 6px;
  height: 6px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--inventory-line);
}
.metric-value {
  margin-top: 10px;
  color: var(--inventory-text);
  font-size: clamp(20px, 2vw, 28px);
  font-weight: 600;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
}
.metric-note {
  margin-top: 8px;
  color: var(--inventory-muted);
  font-size: 11px;
  line-height: 1.4;
}
.tone-danger .metric-label::before {
  background: var(--inventory-danger);
}
.tone-warning .metric-label::before {
  background: var(--inventory-warning);
}
.tone-info .metric-label::before {
  background: #4f7cac;
}
.tone-success .metric-label::before {
  background: var(--inventory-success);
}
.tone-purple .metric-label::before {
  background: #7655b7;
}
.tone-primary .metric-label::before {
  background: var(--inventory-primary);
}
.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.chart-card {
  min-width: 0;
  border: 1px solid var(--inventory-line-soft);
  border-radius: 10px;
  background: #fff;
  overflow: hidden;
  box-shadow: 0 1px 3px rgb(23 33 43 / 2.5%);
  transition:
    border-color 220ms ease-out,
    box-shadow 220ms ease-out;
}
.chart-card:hover {
  border-color: var(--inventory-line);
  box-shadow: 0 5px 14px rgb(23 33 43 / 5%);
}
.chart-card header {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: flex-start;
  padding: 12px 14px 0;
  color: var(--inventory-text);
}
.chart-card header strong {
  font-weight: 600;
}
.chart-card header > div:first-child {
  display: grid;
  gap: 4px;
}
.chart-card header span {
  color: var(--inventory-muted);
  font-size: 11px;
  font-weight: 400;
}
.chart-box {
  height: 280px;
  padding: 4px 8px 8px;
}
.pie-layout,
.material-view {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(190px, 0.7fr);
  gap: 10px;
  align-items: stretch;
  padding: 6px 10px 12px;
}
.pie-box {
  height: 330px;
  padding: 0;
}
.material-pie-box {
  min-width: 0;
  position: relative;
}
.chart-empty-overlay {
  position: absolute;
  inset: 0;
  z-index: 5;
  display: grid;
  place-items: center;
  background: #fff;
  border-radius: inherit;
}
.chart-expand-btn {
  flex: 0 0 auto;
  margin-top: 2px;
  font-size: 12px;
  color: var(--inventory-muted);
  transition: color 200ms ease-out;
}
.chart-expand-btn:hover {
  color: var(--inventory-primary);
}
.enlarge-dialog .el-dialog__body {
  padding: 8px 16px 16px;
  max-height: calc(100vh - 120px);
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: rgb(23 33 43 / 18%) transparent;
}
.enlarged-chart-box {
  width: 100%;
}
.risk-list,
.material-list {
  min-width: 0;
  max-height: 330px;
  padding: 8px 5px 5px 10px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: rgb(23 33 43 / 18%) transparent;
}
.list-heading {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  padding-bottom: 7px;
  border-bottom: 1px solid var(--inventory-line-soft);
  color: var(--inventory-text);
  font-size: 12px;
}
.list-heading span {
  color: var(--inventory-muted);
  font-size: 11px;
  font-weight: 400;
}
.drill-list-item {
  display: flex;
  width: 100%;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
  padding: 8px 7px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--inventory-text);
  text-align: left;
  cursor: pointer;
  transition:
    background 160ms ease-out,
    border-color 160ms ease-out,
    transform 160ms ease-out;
}
.drill-list-item:hover,
.drill-list-item.active {
  border-color: rgb(8 118 111 / 14%);
  background: rgb(8 118 111 / 5%);
}
.drill-list-item.active {
  background: rgb(8 118 111 / 8%);
}
.drill-list-item:hover {
  transform: translateX(1px);
}
.item-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}
.item-name small {
  margin-left: 5px;
  color: var(--inventory-muted);
  font-size: 10px;
}
.item-value {
  flex: 0 0 auto;
  display: grid;
  justify-items: end;
  gap: 2px;
  color: var(--inventory-text);
  font-size: 12px;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
}
.item-volume {
  color: var(--inventory-muted);
  font-size: 10px;
  font-weight: 450;
}
.risk-total {
  color: var(--inventory-danger);
}
.dashboard-empty {
  min-height: 220px;
  display: grid;
  place-items: center;
}
.chart-placeholder {
  display: grid;
  height: 100%;
  align-items: center;
  padding: 24px;
}
.chart-placeholder :deep(.el-skeleton__item) {
  background: linear-gradient(90deg, var(--el-fill-color-light) 25%, var(--el-fill-color) 37%, var(--el-fill-color-light) 63%);
}
@media (max-width: 1280px) {
  .metric-grid {
    grid-template-columns: repeat(3, minmax(150px, 1fr));
  }
}
@media (max-width: 980px) {
  .pie-layout,
  .material-view {
    grid-template-columns: 1fr;
  }
  .risk-list,
  .material-list {
    max-height: 220px;
  }
}
@media (max-width: 760px) {
  .dashboard-head {
    flex-direction: column;
  }
  .metric-grid,
  .chart-grid {
    grid-template-columns: 1fr;
  }
  .chart-card header {
    flex-direction: column;
  }
  .chart-controls {
    justify-content: flex-start;
  }
  .chart-select {
    width: 145px;
  }
  .chart-box,
  .pie-box {
    height: 300px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .metric-card,
  .drill-list-item,
  .chart-placeholder :deep(.el-skeleton__item) {
    transition: none;
  }
  .metric-card:hover,
  .drill-list-item:hover {
    transform: none;
  }
}
[data-reveal] {
  opacity: 0;
}
[data-reveal].revealed {
  animation: reveal-rise 540ms cubic-bezier(0.22, 0.61, 0.36, 1) backwards;
  animation-delay: calc(var(--i, 0) * 45ms);
  opacity: 1;
}
@keyframes reveal-rise {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
@media (prefers-reduced-motion: reduce) {
  [data-reveal] {
    opacity: 1;
  }
  [data-reveal].revealed {
    animation: none;
  }
}
</style>
