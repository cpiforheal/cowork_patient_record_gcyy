<template>
  <section class="dashboard" aria-label="管理员耗材数据驾驶舱">
    <div class="dashboard-head">
      <div>
        <div class="eyebrow">管理总览 · {{ periodLabel }}</div>
        <h3>耗材日报驾驶舱</h3>
        <p>优先查看实际使用量与风险分布；点击图表或明细可直接定位核查。</p>
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
        <article v-for="metric in metrics" :key="metric.label" class="metric-card" :class="metric.tone">
          <div class="metric-label">{{ metric.label }}</div>
          <div class="metric-value">{{ metric.value }}</div>
          <div class="metric-note">{{ metric.note }}</div>
        </article>
      </div>
      <div class="chart-grid">
        <article class="chart-card risk-card">
          <header>
            <div><strong>12 科室填报完成度</strong><span>每瓣对应一个科室，点击定位核查</span></div>
          </header>
          <div class="pie-layout">
            <div ref="riskChartRoot" class="chart-box pie-box">
              <VChart v-if="chartLoaded.risk && hasDepartmentRisk" :option="riskOption" autoresize @click="handleChartClick" />
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
                <span class="item-name" :title="row.departmentName">{{ row.departmentName }}</span>
                <span class="item-value risk-total">{{ row.submittedDayCount }} / {{ row.expectedDayCount }} 日</span>
              </button>
              <el-empty v-if="!departmentCompletionRows.length" :image-size="48" description="暂无科室填报数据" />
            </div>
          </div>
        </article>
        <article class="chart-card trend-card">
          <header>
            <div><strong>理论与实际金额趋势</strong><span>缺失实际量不按零</span></div>
          </header>
          <div ref="amountTrendChartRoot" class="chart-box">
            <VChart
              v-if="chartLoaded.amountTrend && hasDailyTrend"
              :option="amountTrendOption"
              autoresize
              @click="handleChartClick"
            />
            <el-empty v-else-if="chartLoaded.amountTrend" description="暂无金额趋势" />
            <div v-else class="chart-placeholder" aria-hidden="true"><el-skeleton :rows="4" animated /></div>
          </div>
        </article>
        <article class="chart-card material-card">
          <header>
            <div>
              <strong>耗材实际使用量</strong><span>{{ materialScopeNote }}</span>
            </div>
            <div class="chart-controls">
              <el-select v-model="materialMode" size="small" class="chart-select" aria-label="耗材指标">
                <el-option label="实际使用量" value="quantity" />
                <el-option label="实际金额" value="amount" />
                <el-option label="理论与实际偏差" value="deviation" />
              </el-select>
              <el-select v-model="materialScope" size="small" class="chart-select" aria-label="耗材范围">
                <el-option label="Top 10" value="top" />
                <el-option label="全部（当前返回数据）" value="all" />
              </el-select>
            </div>
          </header>
          <div class="material-view">
            <div ref="materialChartRoot" class="chart-box pie-box material-pie-box">
              <VChart
                v-if="chartLoaded.material && hasMaterialChart"
                :option="materialOption"
                autoresize
                @click="handleChartClick"
              />
              <el-empty v-else-if="chartLoaded.material" description="当前口径暂无可绘制数据" />
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
        <article class="chart-card trend-card">
          <header>
            <div><strong>每日风险趋势</strong><span>异常峰值标记</span></div>
          </header>
          <div ref="riskTrendChartRoot" class="chart-box">
            <VChart
              v-if="chartLoaded.riskTrend && hasDailyTrend"
              :option="riskTrendOption"
              autoresize
              @click="handleChartClick"
            />
            <el-empty v-else-if="chartLoaded.riskTrend" description="暂无风险趋势" />
            <div v-else class="chart-placeholder" aria-hidden="true"><el-skeleton :rows="4" animated /></div>
          </div>
        </article>
      </div>
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { LineChart, PieChart } from "echarts/charts";
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
  GridComponent,
  DataZoomComponent,
  LegendComponent,
  MarkPointComponent,
  TooltipComponent
]);

type Drill = { businessDate?: string; departmentKey?: string; materialName?: string; riskLevel?: string };
type MaterialMode = "quantity" | "amount" | "deviation";
type MaterialScope = "top" | "all";

const props = defineProps<{ report?: InventoryAdminDepartmentDailyRollup }>();
const emit = defineEmits<{ drill: [payload: Drill]; reset: [] }>();
const report = computed(() => props.report);
const dashboard = computed(() => props.report?.dashboard);
const periodLabel = computed(() => (props.report ? props.report.periodStart + " 至 " + props.report.periodEnd : ""));
const number = (value: number | null | undefined) =>
  value == null ? "—" : Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
const percent = (value: number | null | undefined) =>
  value == null ? "—" : (Number(value) * 100).toLocaleString("zh-CN", { maximumFractionDigits: 1 }) + "%";
const amount = (value: number | null | undefined) => (value == null ? "未核价" : "¥" + number(value));
const tooltipSurface = {
  backgroundColor: "#fff",
  borderColor: "rgb(23 33 43 / 12%)",
  borderWidth: 1,
  borderRadius: 8,
  padding: [10, 12],
  textStyle: { color: "#17212b", fontSize: 12, lineHeight: 19 },
  extraCssText: "box-shadow:0 10px 28px rgb(23 33 43 / 14%);"
};

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
    { label: "待核验耗材行", value: number(d.unverifiedCount), note: "实际量为空，不参与金额", tone: "tone-info" },
    {
      label: "关注 / 异常",
      value: number(d.attentionCount) + " / " + number(d.abnormalCount),
      note: "异常优先处理",
      tone: "tone-warning"
    },
    {
      label: "已核价实际金额",
      value: amount(d.actualAmount),
      note: "核价覆盖率 " + percent(d.pricingCoverageRate),
      tone: "tone-success"
    },
    { label: "特殊待说明", value: number(d.specialPendingNoteCount), note: "特殊耗材说明未完成", tone: "tone-purple" }
  ];
});

const materialMode = ref<MaterialMode>("quantity");
const materialScope = ref<MaterialScope>("top");
const selectedMaterialIndex = ref<number | null>(null);
const selectedDepartmentKey = ref<string | null>(null);
type ChartId = "risk" | "amountTrend" | "material" | "riskTrend";
const chartLoaded = reactive<Record<ChartId, boolean>>({ risk: false, amountTrend: false, material: false, riskTrend: false });
const riskChartRoot = ref<HTMLElement | null>(null);
const amountTrendChartRoot = ref<HTMLElement | null>(null);
const materialChartRoot = ref<HTMLElement | null>(null);
const riskTrendChartRoot = ref<HTMLElement | null>(null);
let chartObserver: IntersectionObserver | null = null;
let chartLoadFallbackTimer: number | null = null;
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
    if (dashboardMounted) void nextTick(observeChartRoots);
  }
);

const hasDailyTrend = computed(() => Boolean(dashboard.value?.dailyTrend?.length));
const departmentCompletionRows = computed(() => {
  const expectedDayCount = report.value?.departmentCount
    ? Math.max(1, Math.round((dashboard.value?.expectedDepartmentDays || 0) / report.value.departmentCount))
    : Math.max(1, dashboard.value?.dailyTrend?.length || 1);
  return (report.value?.departments || []).map(row => {
    const submittedDayCount = Math.min(
      expectedDayCount,
      Math.max(0, row.submittedDayCount ?? (row.status === "SUBMITTED" ? 1 : 0))
    );
    return { ...row, expectedDayCount, submittedDayCount, completionRate: submittedDayCount / expectedDayCount };
  });
});
const hasDepartmentRisk = computed(() => departmentCompletionRows.value.length > 0);
const materialKey = (row: InventoryAdminMaterialSummary) => row.materialName + "\u0000" + row.unit;
const materialSourceRows = computed<InventoryAdminMaterialSummary[]>(() =>
  materialMode.value === "deviation" ? dashboard.value?.materialDeviationTop || [] : dashboard.value?.materialAmountTop || []
);
const materialRows = computed<InventoryAdminMaterialSummary[]>(() => {
  const rows = [...materialSourceRows.value].sort((left, right) => materialMetricValue(right) - materialMetricValue(left));
  return materialScope.value === "top" ? rows.slice(0, 10) : rows;
});
const materialModeLabel = computed(
  () => ({ quantity: "实际使用量", amount: "实际金额", deviation: "理论与实际偏差" })[materialMode.value]
);
const materialScopeNote = computed(() => {
  if (materialScope.value === "top") return "按当前口径重点展示 · 点击查看明细";
  return materialRows.value.length > 10 ? "当前接口返回的全部耗材数据" : "当前接口仅返回 Top 10，已安全降级";
});
const materialRawValue = (row: InventoryAdminMaterialSummary) => {
  if (materialMode.value === "quantity") return row.actualQuantity;
  if (materialMode.value === "amount") return row.actualAmount;
  return row.amountDifference;
};
const materialMetricValue = (row: InventoryAdminMaterialSummary) => {
  const value = Number(materialRawValue(row) ?? 0);
  return materialMode.value === "deviation" ? Math.abs(value) : Math.max(0, value);
};
const materialDisplayValue = (row: InventoryAdminMaterialSummary) => {
  const value = materialRawValue(row);
  if (value == null) return "未填报";
  return materialMode.value === "quantity" ? number(value) + " " + row.unit : "¥" + number(value);
};
const materialTotal = computed(() => materialRows.value.reduce((total, row) => total + materialMetricValue(row), 0));
const materialCenterText = computed(() => {
  const total = number(materialTotal.value);
  if (materialMode.value === "quantity") return total + "\n实际使用量\n单位见右侧明细";
  if (materialMode.value === "amount") return "¥" + total + "\n实际金额";
  return "¥" + total + "\n偏差绝对值";
});
const materialChartRows = computed(() =>
  materialRows.value.map((row, index) => ({ row, index, value: materialMetricValue(row) })).filter(item => item.value > 0)
);
const hasMaterialChart = computed(() => materialChartRows.value.length > 0 && materialTotal.value > 0);

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
  animationDuration: prefersReducedMotion.value ? 0 : 460,
  animationDurationUpdate: prefersReducedMotion.value ? 0 : 280,
  animationEasing: "cubicOut" as const,
  animationEasingUpdate: "cubicOut" as const
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
        text: number(departmentCompletionTotal.value) +
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

const materialOption = computed<EChartsOption>(() => ({
  ...chartMotion.value,
  tooltip: {
    ...tooltipSurface,
    trigger: "item",
    formatter: (params: any) => {
      const item = materialChartRows.value[params.dataIndex];
      if (!item) return "";
      const ratio = materialTotal.value ? ((item.value / materialTotal.value) * 100).toFixed(1) : "0.0";
      const row = item.row;
      const coverage =
        materialMode.value === "quantity"
          ? "<br/>已填报：" +
            number(row.filledActualLineCount) +
            " / " +
            number(row.lineCount) +
            " 行<br/>实际量覆盖率：" +
            percent(row.actualCoverageRate)
          : "";
      const deviationNote = materialMode.value === "deviation" ? "<br/>图形按偏差绝对值绘制" : "";
      return (
        row.materialName +
        "<br/>单位：" +
        row.unit +
        "<br/>" +
        materialModeLabel.value +
        "：<b>" +
        materialDisplayValue(row) +
        "</b><br/>占比：" +
        ratio +
        "%" +
        coverage +
        deviationNote
      );
    }
  },
  graphic: [
    {
      type: "text",
      left: "38%",
      top: "38%",
      style: {
        text: materialCenterText.value,
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
      name: materialModeLabel.value,
      type: "pie",
      radius: ["48%", "73%"],
      center: ["38%", "52%"],
      minAngle: 2,
      padAngle: 1.5,
      selectedMode: "single",
      selectedOffset: 8,
      animationType: "expansion",
      animationDelay: (index: number) => index * 28,
      animationDelayUpdate: (index: number) => index * 16,
      itemStyle: { borderRadius: 7, borderColor: "#fff", borderWidth: 2 },
      emphasis: {
        focus: "self",
        scale: true,
        scaleSize: 7,
        itemStyle: { shadowBlur: 16, shadowOffsetY: 4, shadowColor: "rgb(23 33 43 / 20%)" }
      },
      blur: { itemStyle: { opacity: 0.38 } },
      label: { show: false },
      labelLine: { show: false },
      data: materialChartRows.value.map(item => ({
        name: item.row.materialName + " · " + item.row.unit,
        value: item.value,
        selected: selectedMaterialIndex.value === item.index,
        itemStyle: { color: materialColors[item.index % materialColors.length] }
      }))
    }
  ]
}));

const amountTrendOption = computed<EChartsOption>(() => {
  const rows = dashboard.value?.dailyTrend || [];
  return {
    ...chartMotion.value,
    grid,
    tooltip: {
      ...tooltipSurface,
      trigger: "axis",
      axisPointer: { type: "cross", label: { backgroundColor: palette.primary } },
      formatter: (raw: any) => {
        const p = Array.isArray(raw) ? raw : [raw];
        const row = rows[p[0]?.dataIndex];
        return row
          ? row.businessDate +
              "<br/>理论金额：<b>" +
              amount(row.theoreticalAmount) +
              "</b><br/>实际金额：<b>" +
              amount(row.actualAmount) +
              "</b><br/>日报完成率：<b>" +
              percent(row.completionRate) +
              "</b><br/>未核价行：" +
              number(row.unpricedLineCount) +
              "<br/>未核验行：" +
              number(row.unverifiedCount)
          : "";
      }
    },
    legend: trendLegend(["理论金额", "实际金额", "日报完成率"]),
    xAxis: trendXAxis(rows.map(row => row.businessDate.slice(5))),
    dataZoom: trendDataZoom,
    yAxis: [
      trendYAxis("金额"),
      trendYAxis("完成率", {
        max: 1,
        splitLine: { show: false },
        axisLabel: { color: palette.muted, fontSize: 11, formatter: (value: number) => value * 100 + "%" }
      })
    ],
    series: [
      {
        name: "理论金额",
        type: "line",
        smooth: true,
        connectNulls: false,
        data: rows.map(row => row.theoreticalAmount),
        symbol: "circle",
        showSymbol: false,
        lineStyle: { width: 2.5 },
        itemStyle: { color: palette.primary },
        emphasis: { focus: "series", lineStyle: { width: 3.5 }, itemStyle: { borderWidth: 3 } },
        animationDelay: 0
      },
      {
        name: "实际金额",
        type: "line",
        smooth: true,
        connectNulls: false,
        data: rows.map(row => row.actualAmount),
        symbol: "circle",
        showSymbol: false,
        lineStyle: { width: 2.5 },
        itemStyle: { color: palette.warning },
        emphasis: { focus: "series", lineStyle: { width: 3.5 }, itemStyle: { borderWidth: 3 } },
        animationDelay: 60
      },
      {
        name: "日报完成率",
        type: "line",
        yAxisIndex: 1,
        smooth: true,
        data: rows.map(row => row.completionRate),
        symbol: "circle",
        showSymbol: false,
        lineStyle: { width: 2.25, type: "dashed" },
        itemStyle: { color: palette.info },
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
    ["amountTrend", amountTrendChartRoot.value],
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
  void nextTick(observeChartRoots);
});

onBeforeUnmount(() => {
  chartObserver?.disconnect();
  chartObserver = null;
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
  if (
    params.dataIndex != null &&
    trend[params.dataIndex] &&
    params.seriesName &&
    ["未核验", "关注", "异常", "特殊待说明", "理论金额", "实际金额", "日报完成率"].includes(params.seriesName)
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
  border: 1px solid var(--inventory-line);
  border-radius: 14px;
  background: linear-gradient(135deg, #fff 0%, #f7fbfa 100%);
  box-shadow: 0 10px 28px rgb(23 33 43 / 7%);
}
.dashboard-head {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  align-items: flex-start;
}
.dashboard-head h3 {
  margin: 3px 0 4px;
  font-size: 22px;
  letter-spacing: -0.02em;
  color: var(--inventory-text);
}
.dashboard-head p,
.eyebrow {
  margin: 0;
  color: var(--inventory-muted);
  font-size: 13px;
}
.eyebrow {
  font-weight: 700;
  letter-spacing: 0.04em;
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
  min-height: 112px;
  padding: 14px;
  border: 1px solid var(--inventory-line-soft);
  border-radius: 12px;
  background: rgb(255 255 255 / 86%);
  transition:
    transform 180ms ease-out,
    box-shadow 180ms ease-out;
}
.metric-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 18px rgb(23 33 43 / 8%);
}
.metric-label {
  color: var(--inventory-muted);
  font-size: 12px;
  font-weight: 700;
}
.metric-value {
  margin-top: 10px;
  color: var(--inventory-text);
  font-size: clamp(20px, 2vw, 30px);
  font-weight: 800;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}
.metric-note {
  margin-top: 8px;
  color: var(--inventory-muted);
  font-size: 11px;
  line-height: 1.4;
}
.tone-danger {
  border-top: 3px solid var(--inventory-danger);
}
.tone-warning {
  border-top: 3px solid var(--inventory-warning);
}
.tone-info {
  border-top: 3px solid #4f7cac;
}
.tone-success {
  border-top: 3px solid var(--inventory-success);
}
.tone-purple {
  border-top: 3px solid #7655b7;
}
.tone-primary {
  border-top: 3px solid var(--inventory-primary);
}
.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.chart-card {
  min-width: 0;
  border: 1px solid var(--inventory-line-soft);
  border-radius: 12px;
  background: #fff;
  overflow: hidden;
  box-shadow: 0 4px 14px rgb(23 33 43 / 4%);
}
.chart-card header {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: flex-start;
  padding: 12px 14px 0;
  color: var(--inventory-text);
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
  border-color: rgb(8 118 111 / 20%);
  background: rgb(8 118 111 / 8%);
}
.drill-list-item:hover {
  transform: translateX(2px);
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
  color: var(--inventory-text);
  font-size: 12px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
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
</style>
