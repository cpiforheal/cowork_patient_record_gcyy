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
            <template v-else-if="chart.kind === 'stack'">
              <th>正常</th>
              <th>异常</th>
              <th>危急</th>
              <th>未标记</th>
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
            <template v-else-if="chart.kind === 'stack'">
              <td v-for="(key, stateIndex) in stackKeys" :key="key">
                <button type="button" class="table-filter" :disabled="disabled" @click="select(row, stateIndex)">
                  {{ row[key] }}
                </button>
              </td>
            </template>
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
import { BarChart, HeatmapChart, LineChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent, VisualMapComponent } from "echarts/components";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import type { EChartsOption } from "echarts";
import VChart from "vue-echarts";
import type { AnalysisChartData, AnalysisRow } from "@/api/modules/clinic/patientAnalysis";

use([CanvasRenderer, BarChart, LineChart, HeatmapChart, GridComponent, LegendComponent, TooltipComponent, VisualMapComponent]);
const props = defineProps<{ chart: AnalysisChartData; main?: boolean; disabled?: boolean }>();
const emit = defineEmits<{ select: [filters: Record<string, string[]>] }>();
const table = ref(false);
const chartContainer = ref<HTMLElement>();
const narrow = ref(false);
let observer: ResizeObserver | undefined;
onMounted(() => {
  if (chartContainer.value) {
    observer = new ResizeObserver(entries => (narrow.value = entries[0].contentRect.width < 480));
    observer.observe(chartContainer.value);
  }
});
onBeforeUnmount(() => observer?.disconnect());
const tableRows = computed(() => props.chart.tableRows || props.chart.rows);
const stackKeys = ["normal", "abnormal", "critical", "unmarked"] as const;
const stackStates = ["NORMAL", "ABNORMAL", "CRITICAL", "未记录"];
const chartHeight = computed(() => {
  if (props.chart.kind === "matrix") return Math.max(340, new Set(props.chart.rows.map(r => r.y)).size * 29 + 100);
  if (props.chart.kind === "bar" || props.chart.kind === "stack")
    return Math.max(255, Math.min(430, props.chart.rows.length * 29 + 55));
  return props.main ? 320 : 285;
});

function select(row: AnalysisRow, seriesIndex?: number) {
  if (props.disabled) return;
  const filters = { ...row.filters };
  if (props.chart.kind === "stack" && seriesIndex !== undefined) filters.severity = [stackStates[seriesIndex]];
  emit("select", filters);
}

function onChartClick(event: { data?: unknown; seriesIndex?: number }) {
  const index = event.data && typeof event.data === "object" ? (event.data as { rowIndex?: number }).rowIndex : undefined;
  if (index !== undefined && props.chart.rows[index]) select(props.chart.rows[index], event.seriesIndex);
}

const option = computed<EChartsOption>(() => {
  const chart = props.chart;
  const rows = chart.rows;
  const textColor = "#53636b";
  const lineColor = "#e9eeec";
  const base: EChartsOption = {
    animation: false,
    textStyle: { fontFamily: "inherit", color: textColor, fontSize: 11 },
    color: ["#247b91", "#67a382", "#b99742", "#b4bebc"],
    tooltip: {
      renderMode: "richText",
      confine: true,
      trigger: chart.kind === "trend" ? "axis" : "item",
      formatter: (input: unknown) => {
        const items = (Array.isArray(input) ? input : [input]) as Array<{
          data?: { rowIndex?: number };
          seriesName?: string;
          value?: unknown;
        }>;
        const row = rows[items[0]?.data?.rowIndex ?? -1];
        if (!row) return "";
        if (chart.kind === "trend")
          return `${row.label}\n${chart.series?.[0]}  ${row.primary}\n${chart.series?.[1]}  ${row.secondary}`;
        if (chart.kind === "stack")
          return `${row.label}\n正常 ${row.normal}  异常 ${row.abnormal}\n危急 ${row.critical}  未标记 ${row.unmarked}\n分母 ${row.denominator} 项次`;
        return `${chart.kind === "matrix" ? `${row.x} / ${row.y}` : row.label}\n${row.value} ${chart.unit}\n分母 ${row.denominator} · 占比 ${row.share}%\n未知 ${row.unknownCount}`;
      }
    }
  };
  if (chart.kind === "trend") {
    return {
      ...base,
      grid: { left: 42, right: 18, top: 38, bottom: 34 },
      legend: { top: 0, right: 4, itemWidth: 12, itemHeight: 8, textStyle: { color: textColor, fontSize: 11 } },
      xAxis: {
        type: "category",
        data: rows.map(r => r.label),
        axisTick: { show: false },
        axisLine: { lineStyle: { color: lineColor } },
        axisLabel: { formatter: (label: string) => label.slice(2), hideOverlap: true }
      },
      yAxis: { type: "value", minInterval: 1, splitLine: { lineStyle: { color: lineColor, type: "dashed" } } },
      series: [
        {
          type: "bar",
          name: chart.series?.[0],
          barMaxWidth: 25,
          itemStyle: { borderRadius: [3, 3, 0, 0] },
          data: rows.map((r, i) => ({ value: r.primary, rowIndex: i }))
        },
        {
          type: "line",
          name: chart.series?.[1],
          symbol: "circle",
          symbolSize: 5,
          lineStyle: { width: 2 },
          data: rows.map((r, i) => ({ value: r.secondary, rowIndex: i }))
        }
      ]
    };
  }
  if (chart.kind === "matrix") {
    const xs = [...new Set(rows.map(r => r.x || ""))];
    const ys = [...new Set(rows.map(r => r.y || ""))];
    return {
      ...base,
      grid: { left: narrow.value ? 85 : 125, right: 12, top: 14, bottom: 80 },
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
          label: { show: xs.length <= 8, fontSize: 10 },
          itemStyle: { borderColor: "#ffffff", borderWidth: 3 },
          emphasis: { itemStyle: { borderColor: "#a3b8ae" } }
        }
      ]
    };
  }
  const horizontal: EChartsOption = {
    ...base,
    grid: {
      left: narrow.value ? 108 : chart.kind === "stack" ? 185 : 145,
      right: 38,
      top: chart.kind === "stack" ? 38 : 12,
      bottom: 26
    },
    xAxis: { type: "value", minInterval: 1, splitLine: { lineStyle: { color: lineColor, type: "dashed" } } },
    yAxis: {
      type: "category",
      data: rows.map(r => r.label),
      inverse: true,
      axisLabel: { width: narrow.value ? 98 : chart.kind === "stack" ? 173 : 132, overflow: "truncate" },
      axisLine: { show: false },
      axisTick: { show: false }
    }
  };
  if (chart.kind === "stack") {
    return {
      ...horizontal,
      color: ["#67a382", "#c39b43", "#b76864", "#b4bebc"],
      legend: { top: 0, itemWidth: 10, itemHeight: 8, textStyle: { fontSize: 10 } },
      series: stackKeys.map((key, seriesIndex) => ({
        type: "bar",
        stack: "total",
        name: ["正常", "异常", "危急", "未标记"][seriesIndex],
        barMaxWidth: 17,
        data: rows.map((r, index) => ({ value: r[key] || 0, rowIndex: index }))
      }))
    };
  }
  return {
    ...horizontal,
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
