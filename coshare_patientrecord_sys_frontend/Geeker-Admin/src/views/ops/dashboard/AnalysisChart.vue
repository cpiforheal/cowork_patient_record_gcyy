<template>
  <section class="analysis-chart" :class="{ 'is-main': main }" :aria-label="chart.title">
    <header class="chart-heading">
      <div class="chart-title">
        <h3>{{ chart.title }}</h3>
        <el-tooltip :content="chart.note" placement="top" :show-after="300">
          <button class="chart-icon definition-icon" type="button" :aria-label="chart.note">
            <el-icon><InfoFilled /></el-icon>
          </button>
        </el-tooltip>
      </div>
      <div class="chart-tools">
        <span class="chart-unit">{{ chart.unit }}</span>
        <div class="chart-mode" role="group" aria-label="图表或数据表">
          <el-tooltip content="图表" :show-after="300">
            <button class="chart-icon" type="button" :aria-pressed="!table" aria-label="图表" @click="table = false">
              <el-icon><DataAnalysis /></el-icon>
            </button>
          </el-tooltip>
          <el-tooltip content="数据表" :show-after="300">
            <button class="chart-icon" type="button" :aria-pressed="table" aria-label="数据表" @click="table = true">
              <el-icon><Grid /></el-icon>
            </button>
          </el-tooltip>
        </div>
      </div>
    </header>
    <div v-if="table" class="chart-table-scroll" tabindex="0" :aria-label="`${chart.title}数据表`">
      <table class="chart-table">
        <thead>
          <tr>
            <th>{{ chart.kind === "trend" ? "时间段" : "分类" }}</th>
            <template v-if="chart.kind === 'trend'">
              <th>{{ chart.series?.[0] }}</th>
              <th>{{ chart.series?.[1] }}</th>
            </template>
            <template v-else
              ><th>数量</th>
              <th>分母</th>
              <th>占比</th>
              <th>未知</th></template
            >
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in tableRows" :key="index">
            <th>
              <button type="button" class="table-filter" :disabled="disabled" @click="select(row)">
                {{ chart.kind === "matrix" ? `${row.x} / ${row.y}` : row.label }}
              </button>
            </th>
            <template v-if="chart.kind === 'trend'"
              ><td>{{ row.primary }}</td>
              <td>{{ row.secondary }}</td></template
            >
            <template v-else
              ><td>{{ row.value }}</td>
              <td>{{ row.denominator }}</td>
              <td>{{ row.share }}%</td>
              <td>{{ row.unknownCount }}</td></template
            >
          </tr>
          <tr v-if="!tableRows.length">
            <td colspan="5" class="no-rows">没有匹配记录</td>
          </tr>
        </tbody>
      </table>
    </div>
    <!-- 下钻把行列都收敛到单个组合时，单格热力图会撑满整卡；降级为大数字卡 -->
    <div v-else-if="matrixSingle" class="matrix-single">
      <div class="matrix-single-value">
        <strong>{{ number(matrixSingle.value) }}</strong
        ><span>{{ chart.unit }}</span>
      </div>
      <p class="matrix-single-label">{{ matrixSingle.x }} / {{ matrixSingle.y }}</p>
      <p class="matrix-single-meta">分母 {{ matrixSingle.denominator }} · 占比 {{ matrixSingle.share }}%</p>
      <small>放宽任一筛选条件可回到矩阵视图，或切换右上角数据表</small>
    </div>
    <div v-else ref="chartContainer" class="chart-canvas" :style="{ height: `${chartHeight}px` }">
      <VChart v-if="chart.rows.length" :option="option" :update-options="{ notMerge: true }" autoresize @click="onChartClick" />
      <el-empty v-else description="没有匹配记录" :image-size="64" />
    </div>
    <footer v-if="chart.kind === 'trend'" class="chart-footnote">{{ chart.note }}</footer>
    <footer v-else-if="chart.kind === 'matrix'" class="chart-footnote">行列均按当前样本聚合 · {{ chart.note }}</footer>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { DataAnalysis, Grid, InfoFilled } from "@element-plus/icons-vue";
import { BarChart, HeatmapChart, LineChart, PieChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent, VisualMapComponent } from "echarts/components";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import type { EChartsOption } from "echarts";
import VChart from "vue-echarts";
import type { AnalysisChartData, AnalysisRow } from "@/api/modules/clinic/patientAnalysis";

use([CanvasRenderer, BarChart, LineChart, PieChart, HeatmapChart, GridComponent, LegendComponent, TooltipComponent, VisualMapComponent]);
const props = defineProps<{ chart: AnalysisChartData; main?: boolean; disabled?: boolean }>();
const emit = defineEmits<{ select: [filters: Record<string, string[]>] }>();
const table = ref(false);
const chartContainer = ref<HTMLElement>();
const narrow = ref(false);
const containerWidth = ref(0);
let observer: ResizeObserver | undefined;
onMounted(() => {
  if (chartContainer.value) {
    observer = new ResizeObserver(entries => {
      narrow.value = entries[0].contentRect.width < 480;
      containerWidth.value = entries[0].contentRect.width;
    });
    observer.observe(chartContainer.value);
  }
});
onBeforeUnmount(() => observer?.disconnect());
const tableRows = computed(() => props.chart.tableRows || props.chart.rows);
const chartHeight = computed(() => {
  if (props.chart.kind === "matrix") {
    const ys = new Set(props.chart.rows.map(r => r.y)).size;
    return Math.max(280, Math.min(460, ys * 46 + 150));
  }
  if (props.chart.kind === "bar") return Math.max(255, Math.min(430, props.chart.rows.length * 29 + 55));
  if (props.chart.kind === "donut") return props.main ? 340 : 300;
  return props.main ? 320 : 285;
});
const matrixSingle = computed(() => {
  if (props.chart.kind !== "matrix" || props.chart.rows.length !== 1) return undefined;
  const row = props.chart.rows[0];
  return { value: row.value || 0, x: row.x || "", y: row.y || "", denominator: row.denominator || 0, share: row.share || 0 };
});
function number(value: number) {
  return value.toLocaleString("zh-CN");
}

function select(row: AnalysisRow) {
  if (props.disabled) return;
  emit("select", { ...row.filters });
}

function onChartClick(event: { data?: unknown }) {
  const index = event.data && typeof event.data === "object" ? (event.data as { rowIndex?: number }).rowIndex : undefined;
  if (index !== undefined && props.chart.rows[index]) select(props.chart.rows[index]);
}

// 图表动画统一节奏；系统声明减少动效时降级为无动画
const reducedMotion = typeof window !== "undefined" && !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
const motion = {
  animation: !reducedMotion,
  animationDuration: 420,
  animationDurationUpdate: 600,
  animationEasing: "cubicOut" as const,
  animationEasingUpdate: "cubicInOut" as const
};

const option = computed<EChartsOption>(() => {
  const chart = props.chart;
  const rows = chart.rows;
  const textColor = "#53636b";
  const lineColor = "#e9eeec";
  const base: EChartsOption = {
    ...motion,
    textStyle: { fontFamily: "inherit", color: textColor, fontSize: 11 },
    color: ["#247b91", "#67a382", "#b99742", "#b4bebc"],
    tooltip: {
      confine: true,
      trigger: chart.kind === "trend" ? "axis" : "item",
      formatter: (input: unknown) => {
        const items = (Array.isArray(input) ? input : [input]) as Array<{ data?: { rowIndex?: number } }>;
        const row = rows[items[0]?.data?.rowIndex ?? -1];
        if (!row) return "";
        if (chart.kind === "trend")
          return `${row.label}\n${chart.series?.[0]}  ${row.primary}\n${chart.series?.[1]}  ${row.secondary}`;
        return `${chart.kind === "matrix" ? `${row.x} / ${row.y}` : row.label}\n${row.value} ${chart.unit}\n分母 ${row.denominator} · 占比 ${row.share}%\n未知 ${row.unknownCount}`;
      }
    }
  };
  if (chart.kind === "trend") {
    // 折线样式对齐首页 DailyPatientCurve：平滑 + 圆头 + 淡色面积
    return {
      ...base,
      grid: { left: 42, right: 18, top: 38, bottom: 34 },
      legend: { top: 0, right: 4, icon: "circle", itemWidth: 10, itemHeight: 10, textStyle: { color: textColor, fontSize: 11 } },
      xAxis: {
        type: "category",
        data: rows.map(r => r.label),
        boundaryGap: false,
        axisTick: { show: false },
        axisLine: { show: false },
        axisLabel: { formatter: (label: string) => label.slice(2), hideOverlap: true }
      },
      yAxis: { type: "value", minInterval: 1, splitLine: { lineStyle: { color: lineColor, type: "dashed" } } },
      series: (["primary", "secondary"] as const).map((key, seriesIndex) => ({
        type: "line" as const,
        name: chart.series?.[seriesIndex],
        smooth: 0.42,
        showSymbol: false,
        symbol: "circle" as const,
        symbolSize: 8,
        lineStyle: { width: 3, cap: "round" as const },
        itemStyle: { color: seriesIndex === 0 ? "#1683ff" : "#67a382", borderColor: "#fff", borderWidth: 2 },
        areaStyle: seriesIndex === 0 ? { color: "rgba(22,131,255,0.05)" } : undefined,
        emphasis: { focus: "series" as const },
        data: rows.map((r, i) => ({ value: r[key], rowIndex: i }))
      }))
    };
  }
  if (chart.kind === "donut") {
    return {
      ...base,
      legend: { bottom: 0, icon: "circle", itemWidth: 10, itemHeight: 10, textStyle: { color: textColor, fontSize: 11 } },
      series: [
        {
          type: "pie",
          radius: ["46%", "70%"],
          center: ["50%", "44%"],
          padAngle: 2,
          itemStyle: { borderRadius: 8, borderColor: "#fff", borderWidth: 2 },
          label: { show: false },
          emphasis: { scale: true, scaleSize: 6 },
          animationType: "expansion",
          animationDelay: (index: number) => index * 55,
          data: rows.map((row, index) => ({
            value: row.value || 0,
            name: row.label,
            rowIndex: index,
            itemStyle: { color: ["#b99742", "#247b91", "#b4bebc"][index % 3] }
          }))
        }
      ],
      graphic: [
        {
          type: "text",
          left: "center",
          top: "37%",
          style: { text: number(chart.centerValue ?? 0), fontSize: 30, fontWeight: 700, fill: "#b99742", textAlign: "center" }
        },
        { type: "text", left: "center", top: "52%", style: { text: chart.centerLabel || "", fontSize: 11, fill: textColor } }
      ]
    };
  }
  if (chart.kind === "matrix") {
    const xs = [...new Set(rows.map(r => r.x || ""))];
    const ys = [...new Set(rows.map(r => r.y || ""))];
    const leftWidth = narrow.value ? 85 : 125;
    // 单元格宽度封顶：列少时网格不再被拉伸到整卡宽
    const gridWidth = Math.max(140, Math.min(containerWidth.value - leftWidth - 90 || 620, xs.length * 96 + 30));
    return {
      ...base,
      grid: { left: leftWidth, width: gridWidth, top: 14, bottom: 80 },
      xAxis: {
        type: "category",
        data: xs,
        axisLabel: { interval: 0, rotate: xs.length > 5 ? 40 : 0, width: narrow.value ? 44 : 70, overflow: "truncate" },
        axisTick: { show: false },
        axisLine: { show: false }
      },
      yAxis: {
        type: "category",
        data: ys,
        inverse: true,
        axisLabel: { width: narrow.value ? 76 : 115, overflow: "truncate" },
        axisTick: { show: false },
        axisLine: { show: false }
      },
      visualMap: {
        min: 0,
        max: Math.max(1, ...rows.map(r => r.value || 0)),
        calculable: false,
        orient: "horizontal",
        left: "center",
        bottom: 0,
        itemWidth: 9,
        itemHeight: 95,
        inRange: { color: ["#f1f5f2", "#c5ddd4", "#70aaa3", "#247b91"] },
        textStyle: { fontSize: 10 }
      },
      series: [
        {
          type: "heatmap",
          data: rows.map((row, index) => ({
            value: [xs.indexOf(row.x || ""), ys.indexOf(row.y || ""), row.value || 0],
            rowIndex: index
          })),
          label: { show: xs.length * ys.length <= 24, fontSize: 10 },
          itemStyle: { borderColor: "#ffffff", borderWidth: 3 },
          emphasis: { itemStyle: { borderColor: "#a3b8ae" } }
        }
      ]
    };
  }
  return {
    ...base,
    grid: { left: narrow.value ? 108 : 145, right: 38, top: 12, bottom: 26 },
    xAxis: { type: "value", minInterval: 1, splitLine: { lineStyle: { color: lineColor, type: "dashed" } } },
    yAxis: {
      type: "category",
      data: rows.map(r => r.label),
      inverse: true,
      axisLabel: { width: narrow.value ? 98 : 132, overflow: "truncate" },
      axisLine: { show: false },
      axisTick: { show: false }
    },
    series: [
      {
        type: "bar",
        barMaxWidth: 17,
        data: rows.map((row, index) => ({
          value: row.value || 0,
          rowIndex: index,
          itemStyle: {
            color: (row.unknownCount || 0) > 0 ? "#b4bebc" : ["#247b91", "#67a382", "#b99742"][index % 3],
            borderRadius: [0, 3, 3, 0]
          }
        })),
        label: { show: true, position: "right", color: textColor, fontSize: 11 }
      }
    ]
  };
});
</script>

<style scoped lang="scss">
.analysis-chart {
  min-width: 0;
  padding: 20px 0 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  transition: background-color 0.18s ease-out;
}
.analysis-chart:hover {
  background: rgba(36, 123, 145, 0.035);
}
@media (prefers-reduced-motion: reduce) {
  .analysis-chart {
    transition: none;
  }
}
.chart-heading,
.chart-title,
.chart-tools,
.chart-mode {
  display: flex;
  align-items: center;
}
.chart-heading {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.chart-title {
  min-width: 0;
  gap: 6px;
}
.chart-title h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.chart-tools {
  gap: 8px;
  flex-shrink: 0;
}
.chart-unit {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.chart-mode {
  gap: 2px;
}
.chart-icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  padding: 0;
  color: var(--el-text-color-secondary);
  border: 0;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
  flex-shrink: 0;
}
.chart-icon[aria-pressed="true"] {
  color: #247b91;
  background: #ecf4f2;
}
.chart-icon:focus-visible,
.table-filter:focus-visible {
  outline: 2px solid #247b91;
  outline-offset: 2px;
}
.definition-icon {
  width: 22px;
  font-size: 12px;
}
.chart-icon:active {
  transform: scale(0.97);
}
.chart-canvas {
  min-width: 0;
  width: 100%;
}
.matrix-single {
  display: grid;
  justify-items: center;
  gap: 6px;
  min-height: 280px;
  padding: 28px 16px;
  border: 1px dashed var(--el-border-color-lighter);
  border-radius: 10px;
  background: linear-gradient(180deg, #f7fbfa, #fff);
}
.matrix-single-value {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.matrix-single-value strong {
  font-size: 44px;
  font-weight: 700;
  color: #247b91;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}
.matrix-single-value span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.matrix-single-label {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  overflow-wrap: anywhere;
  text-align: center;
}
.matrix-single-meta {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.matrix-single small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}
.chart-footnote {
  margin: 10px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.chart-table-scroll {
  overflow: auto;
  max-height: 420px;
  min-height: 255px;
}
.chart-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.chart-table th,
.chart-table td {
  padding: 10px 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  text-align: right;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.chart-table th:first-child {
  text-align: left;
  max-width: 240px;
  white-space: normal;
  overflow-wrap: anywhere;
}
.chart-table thead {
  position: sticky;
  top: 0;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
}
.table-filter {
  padding: 0;
  background: none;
  border: 0;
  color: #247b91;
  cursor: pointer;
  font: inherit;
  text-align: inherit;
}
.table-filter:disabled {
  cursor: default;
  color: var(--el-text-color-secondary);
}
.no-rows {
  text-align: center !important;
  color: var(--el-text-color-secondary);
}
@media (max-width: 520px) {
  .chart-heading {
    align-items: flex-start;
    gap: 6px;
  }
  .chart-unit {
    display: none;
  }
}
</style>
