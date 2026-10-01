<template>
  <section
    ref="root"
    class="pa-card analysis-chart"
    :class="{ 'is-main': main, 'is-busy': disabled }"
    :style="{ '--pa-enter-index': index, '--pa-accent': accentColor, '--pa-accent-2': accentColor2 }"
    :aria-label="chart.title"
    :aria-busy="disabled"
  >
    <header class="chart-heading">
      <div class="chart-title">
        <h3>{{ chart.title }}</h3>
        <el-tooltip :content="chart.note" placement="top" :show-after="250">
          <button class="chart-info" type="button" :aria-label="`口径说明：${chart.note}`">
            <el-icon><InfoFilled /></el-icon>
          </button>
        </el-tooltip>
      </div>
      <div class="chart-tools">
        <span class="chart-unit">{{ chart.unit }}</span>
        <button
          v-if="chart.kind === 'matrix' && !table && !matrixSingle && chart.rows.length"
          type="button"
          class="chart-normalize"
          :aria-pressed="normalize"
          :title="normalize ? '当前显示行内占比，点击切回数量' : '当前显示数量，点击切换为行内占比'"
          @click="normalize = !normalize"
        >
          {{ normalize ? "行内占比" : "数量" }}
        </button>
        <div class="chart-mode" role="group" aria-label="图表或数据表" :class="{ 'is-table': table }">
          <span class="chart-mode-thumb" aria-hidden="true" />
          <button type="button" :aria-pressed="!table" aria-label="图表视图" title="图表" @click="table = false">
            <el-icon><DataAnalysis /></el-icon>
          </button>
          <button type="button" :aria-pressed="table" aria-label="数据表视图" title="数据表" @click="table = true">
            <el-icon><Grid /></el-icon>
          </button>
        </div>
      </div>
    </header>

    <Transition name="pa-swap" mode="out-in">
      <div v-if="table" key="table" class="chart-table-scroll" tabindex="0" :aria-label="`${chart.title}数据表`">
        <table class="chart-table">
          <thead>
            <tr>
              <th>{{ chart.kind === "trend" ? "时间段" : "分类" }}</th>
              <template v-if="chart.kind === 'trend'">
                <th v-for="name in seriesNames" :key="name">{{ name }}</th>
              </template>
              <template v-else>
                <th>数量</th>
                <th>分母</th>
                <th class="share-col">占比</th>
                <th>未知</th>
              </template>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, rowIndex) in tableRows" :key="rowIndex">
              <th>
                <button type="button" class="table-filter" :disabled="disabled" @click="select(row)">
                  {{ chart.kind === "matrix" ? `${row.x} / ${row.y}` : row.label }}
                </button>
              </th>
              <template v-if="chart.kind === 'trend'">
                <td v-for="(name, seriesIndex) in seriesNames" :key="name">{{ number(seriesValue(row, seriesIndex)) }}</td>
              </template>
              <template v-else>
                <td class="strong">{{ number(row.value ?? 0) }}</td>
                <td>{{ number(row.denominator ?? 0) }}</td>
                <td class="share-col">
                  <span class="share-cell"
                    ><i><b :style="{ width: `${Math.min(100, row.share ?? 0)}%` }" /></i>{{ row.share ?? 0 }}%</span
                  >
                </td>
                <td :class="{ muted: !row.unknownCount }">{{ row.unknownCount ?? 0 }}</td>
              </template>
            </tr>
            <tr v-if="!tableRows.length">
              <td colspan="5" class="no-rows">没有匹配记录</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 下钻把行列都收敛到单个组合时，单格热力图会撑满整卡；降级为大数字卡 -->
      <div v-else-if="matrixSingle" key="single" class="matrix-single">
        <div class="matrix-single-value">
          <strong>{{ number(matrixSingle.value) }}</strong
          ><span>{{ chart.unit }}</span>
        </div>
        <p class="matrix-single-label">{{ matrixSingle.x }} / {{ matrixSingle.y }}</p>
        <p class="matrix-single-meta">分母 {{ number(matrixSingle.denominator) }} · 占比 {{ matrixSingle.share }}%</p>
        <small>放宽任一筛选条件可回到矩阵视图，或切换到数据表</small>
      </div>

      <div v-else key="chart" class="chart-canvas" :style="{ height: `${chartHeight}px` }">
        <VChart v-if="chart.rows.length" :option="option" autoresize @click="onChartClick" />
        <div v-else class="chart-empty">
          <span class="chart-empty-mark" aria-hidden="true" />
          <strong>没有匹配记录</strong>
          <small>试着放宽日期范围或移除部分筛选条件</small>
        </div>
      </div>
    </Transition>

    <footer v-if="chart.kind === 'trend' || chart.kind === 'matrix'" class="chart-footnote">
      {{ chart.kind === "matrix" ? `行列均按当前样本聚合 · ${chart.note}` : chart.note }}
    </footer>
    <footer v-else-if="chart.rows.length && !table" class="chart-footnote is-hint">点击图形可下钻筛选</footer>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { storeToRefs } from "pinia";
import { DataAnalysis, Grid, InfoFilled } from "@element-plus/icons-vue";
import { BarChart, HeatmapChart, LineChart, PieChart } from "echarts/charts";
import { GraphicComponent, GridComponent, LegendComponent, TooltipComponent, VisualMapComponent } from "echarts/components";
import { use } from "echarts/core";
import { LegacyGridContainLabel } from "echarts/features";
import { CanvasRenderer } from "echarts/renderers";
import type { EChartsOption } from "echarts";
import VChart from "vue-echarts";
import type { AnalysisChartData, AnalysisRow } from "@/api/modules/clinic/patientAnalysis";
import { useGlobalStore } from "@/stores/modules/global";
import { analysisPalette, categoricalGradient, chartMotion, delayColor, escapeHtml, isUnknownLabel } from "./analysisTheme";

use([
  CanvasRenderer,
  LegacyGridContainLabel,
  BarChart,
  LineChart,
  PieChart,
  HeatmapChart,
  GraphicComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  VisualMapComponent
]);

const props = defineProps<{
  chart: AnalysisChartData;
  main?: boolean;
  disabled?: boolean;
  index?: number;
  /** 上一周期同口径的趋势行，按序号与当前对齐，只用于虚线对比 */
  previous?: AnalysisRow[];
}>();
const emit = defineEmits<{ select: [filters: Record<string, string[]>] }>();
const { isDark } = storeToRefs(useGlobalStore());
const table = ref(false);
// 矩阵行内占比：消除各行样本量差异，便于比较构成
const normalize = ref(false);
const root = ref<HTMLElement>();
const containerWidth = ref(0);
const narrow = computed(() => containerWidth.value > 0 && containerWidth.value < 480);
let observer: ResizeObserver | undefined;
// 观察卡片根节点：图表/表格切换会替换内部节点，观察根节点才不会失效
onMounted(() => {
  if (!root.value) return;
  observer = new ResizeObserver(entries => (containerWidth.value = entries[0].contentRect.width));
  observer.observe(root.value);
});
onBeforeUnmount(() => observer?.disconnect());

const tableRows = computed(() => props.chart.tableRows || props.chart.rows);
// 多系列趋势用 values[]；旧的双系列口径回退到 primary/secondary
const multiSeries = computed(() => props.chart.rows.some(row => Array.isArray(row.values)));
const seriesNames = computed(() => {
  const names = props.chart.series || [];
  return multiSeries.value ? names : [names[0] || "", names[1] || ""];
});
function seriesValue(row: AnalysisRow, index: number) {
  if (multiSeries.value) return row.values?.[index] ?? 0;
  return (index === 0 ? row.primary : row.secondary) ?? 0;
}
const previousRows = computed(() => (props.chart.kind === "trend" && props.previous?.length ? props.previous : undefined));
// 每张卡片一个主题色：顶部色条、单位徽标随卡片序号轮换
const accentColor = computed(() => {
  const p = analysisPalette(isDark.value);
  return p.categorical[(props.index ?? 0) % p.categorical.length];
});
const accentColor2 = computed(() => {
  const p = analysisPalette(isDark.value);
  return p.categorical[((props.index ?? 0) + 1) % p.categorical.length];
});
const chartHeight = computed(() => {
  const { kind, rows } = props.chart;
  if (kind === "matrix") return Math.max(280, Math.min(460, new Set(rows.map(r => r.y)).size * 44 + 140));
  if (kind === "bar") return Math.max(240, Math.min(440, rows.length * 32 + 40));
  if (kind === "donut") return 300;
  if (kind === "stack") return 150;
  return props.main ? 320 : 280;
});
const matrixSingle = computed(() => {
  if (props.chart.kind !== "matrix" || props.chart.rows.length !== 1) return undefined;
  const row = props.chart.rows[0];
  return { value: row.value || 0, x: row.x || "", y: row.y || "", denominator: row.denominator || 0, share: row.share || 0 };
});
const number = (value: number) => value.toLocaleString("zh-CN");

function select(row: AnalysisRow) {
  if (props.disabled) return;
  emit("select", { ...row.filters });
}
function onChartClick(event: { data?: unknown }) {
  const index = event.data && typeof event.data === "object" ? (event.data as { rowIndex?: number }).rowIndex : undefined;
  if (index !== undefined && props.chart.rows[index]) select(props.chart.rows[index]);
}

/** 结构化 tooltip：标题 + 主数值 + 占比条 + 次要信息 */
function tooltipHtml(row: AnalysisRow) {
  const chart = props.chart;
  const p = analysisPalette(isDark.value);
  const title = escapeHtml(chart.kind === "matrix" ? `${row.x} / ${row.y}` : row.label);
  if (chart.kind === "trend") {
    const line = (color: string, name: unknown, value: unknown, dashed = false) =>
      `<div class="pa-tt-row"><i style="background:${color}${dashed ? ";opacity:.55" : ""}"></i><span>${escapeHtml(name)}</span><b>${escapeHtml(number(Number(value) || 0))}</b></div>`;
    const colors = trendColors(p);
    const lines = seriesNames.value.map((name, i) => line(colors[i], name, seriesValue(row, i))).join("");
    const rowIndex = chart.rows.indexOf(row);
    const prior = previousRows.value?.[rowIndex];
    const priorLine = prior ? line(p.textMuted, `上期（${prior.label}）`, prior.primary, true) : "";
    return `<div class="pa-tt"><div class="pa-tt-title">${title}</div>${lines}${priorLine}</div>`;
  }
  if (chart.kind === "matrix" && normalize.value) {
    const total = chart.rows.filter(r => r.y === row.y).reduce((sum, r) => sum + (r.value || 0), 0);
    const rowShare = total ? Math.round(((row.value || 0) / total) * 1000) / 10 : 0;
    return `<div class="pa-tt"><div class="pa-tt-title">${title}</div>
    <div class="pa-tt-value"><b>${rowShare}%</b><span>占「${escapeHtml(row.y)}」行</span></div>
    <div class="pa-tt-meta">${escapeHtml(number(row.value ?? 0))} / ${escapeHtml(number(total))} ${escapeHtml(chart.unit)}</div>
    <div class="pa-tt-hint">点击下钻</div></div>`;
  }
  const share = Math.min(100, Math.max(0, row.share ?? 0));
  return `<div class="pa-tt"><div class="pa-tt-title">${title}</div>
    <div class="pa-tt-value"><b>${escapeHtml(number(row.value ?? 0))}</b><span>${escapeHtml(chart.unit)}</span><em>${share}%</em></div>
    <div class="pa-tt-bar"><i style="width:${share}%;background:linear-gradient(90deg,${p.categorical[1]},${p.brand})"></i></div>
    <div class="pa-tt-meta">分母 ${escapeHtml(number(row.denominator ?? 0))}${row.unknownCount ? ` · 未知 ${escapeHtml(row.unknownCount)}` : ""}</div>
    <div class="pa-tt-hint">点击下钻</div></div>`;
}

// 两条线沿用品牌色 + 琥珀；多条线使用分类色
function trendColors(p: ReturnType<typeof analysisPalette>) {
  return multiSeries.value ? p.categorical : [p.brand, p.accent];
}

const option = computed<EChartsOption>(() => {
  const chart = props.chart;
  const rows = chart.rows;
  const p = analysisPalette(isDark.value);
  const axisLabel = { color: p.textMuted, fontSize: 12 };
  const base: EChartsOption = {
    ...chartMotion(),
    textStyle: { fontFamily: "inherit", color: p.text, fontSize: 12 },
    color: p.categorical,
    tooltip: {
      confine: true,
      trigger: chart.kind === "trend" ? "axis" : "item",
      renderMode: "html",
      backgroundColor: p.tooltipBg,
      borderColor: p.tooltipBorder,
      borderWidth: 1,
      padding: [10, 12],
      textStyle: { color: p.text, fontSize: 12 },
      extraCssText: `border-radius:10px;box-shadow:${p.tooltipShadow};backdrop-filter:blur(6px);`,
      axisPointer: { type: "line", lineStyle: { color: p.textMuted, type: "dashed", width: 1 } },
      formatter: (input: unknown) => {
        const items = (Array.isArray(input) ? input : [input]) as Array<{ data?: { rowIndex?: number } }>;
        const row = rows[items[0]?.data?.rowIndex ?? -1];
        return row ? tooltipHtml(row) : "";
      }
    }
  };

  if (chart.kind === "trend") {
    const colors = trendColors(p);
    const soft = [p.brandSoft, p.accentSoft];
    const filled = !multiSeries.value;
    const lines = seriesNames.value.map((name, seriesIndex) => ({
      type: "line" as const,
      name,
      smooth: 0.35,
      showSymbol: false,
      symbol: "circle" as const,
      symbolSize: 8,
      lineStyle: { width: seriesIndex === 0 ? 2.5 : 2, cap: "round" as const, color: colors[seriesIndex % colors.length] },
      itemStyle: { color: colors[seriesIndex % colors.length], borderColor: p.surface, borderWidth: 2 },
      ...(filled
        ? {
            areaStyle: {
              color: {
                type: "linear" as const,
                x: 0,
                y: 0,
                x2: 0,
                y2: 1,
                colorStops: [
                  { offset: 0, color: soft[seriesIndex] },
                  { offset: 1, color: "rgba(255,255,255,0)" }
                ]
              }
            }
          }
        : {}),
      emphasis: { focus: "series" as const },
      data: rows.map((r, i) => ({ value: seriesValue(r, seriesIndex), rowIndex: i }))
    }));
    const prior = previousRows.value;
    if (prior) {
      lines.push({
        type: "line" as const,
        name: `上期${seriesNames.value[0] || ""}`,
        smooth: 0.35,
        showSymbol: false,
        symbol: "circle" as const,
        symbolSize: 6,
        lineStyle: { width: 1.5, cap: "round" as const, color: p.textMuted, type: "dashed" } as never,
        itemStyle: { color: p.textMuted, borderColor: p.surface, borderWidth: 2 },
        emphasis: { focus: "series" as const },
        // 按序号对齐；上期桶数不足时留空
        data: rows.map((_, i) => ({ value: prior[i]?.primary ?? (null as unknown as number), rowIndex: i }))
      });
    }
    return {
      ...base,
      grid: { left: 8, right: 16, top: 40, bottom: 8, containLabel: true },
      legend: {
        top: 0,
        right: 0,
        icon: "roundRect",
        itemWidth: 12,
        itemHeight: 4,
        itemGap: 18,
        textStyle: { color: p.textMuted, fontSize: 12 }
      },
      xAxis: {
        type: "category",
        data: rows.map(r => r.label),
        boundaryGap: false,
        axisTick: { show: false },
        axisLine: { lineStyle: { color: p.grid } },
        axisLabel: { ...axisLabel, formatter: (label: string) => label.slice(5) || label, hideOverlap: true, margin: 12 }
      },
      yAxis: {
        type: "value",
        minInterval: 1,
        axisLabel,
        splitLine: { lineStyle: { color: p.grid } }
      },
      series: lines
    };
  }

  // 100% 堆叠条：状态构成一眼看清，每段可点击下钻
  if (chart.kind === "stack") {
    const total = rows.reduce((sum, row) => sum + (row.value || 0), 0) || 1;
    const visible = rows.map((row, index) => ({ row, index })).filter(({ row }) => (row.value || 0) > 0);
    const byName = new Map(rows.map(row => [row.label, row]));
    return {
      ...base,
      grid: { left: 4, right: 4, top: 8, height: 34 },
      legend: {
        bottom: 0,
        left: 0,
        icon: "circle",
        itemWidth: 8,
        itemHeight: 8,
        itemGap: 16,
        textStyle: { color: p.text, fontSize: 12 },
        formatter: (name: string) => {
          const row = byName.get(name);
          return `${name} ${number(row?.value ?? 0)} · ${row?.share ?? 0}%`;
        }
      },
      xAxis: { type: "value", max: 100, show: false },
      yAxis: { type: "category", data: [""], show: false },
      series: visible.map(({ row, index }, order) => ({
        type: "bar" as const,
        name: row.label,
        stack: "all",
        barWidth: 30,
        itemStyle: {
          color: isUnknownLabel(row.label) ? p.unknown : p.categorical[index % p.categorical.length],
          borderColor: p.surface,
          borderWidth: 2,
          borderRadius: [
            order === 0 ? 8 : 0,
            order === visible.length - 1 ? 8 : 0,
            order === visible.length - 1 ? 8 : 0,
            order === 0 ? 8 : 0
          ]
        },
        label: {
          show: (row.value || 0) / total >= 0.12,
          color: "#ffffff",
          fontSize: 12,
          fontWeight: 600,
          formatter: () => `${row.share ?? 0}%`
        },
        emphasis: { focus: "series" as const },
        blur: { itemStyle: { opacity: 0.35 } },
        data: [{ value: ((row.value || 0) / total) * 100, rowIndex: index }]
      }))
    };
  }

  if (chart.kind === "donut") {
    const wide = containerWidth.value >= 520;
    const total = rows.reduce((sum, row) => sum + (row.value || 0), 0);
    const byName = new Map(rows.map(row => [row.label, row]));
    const center: [string, string] = wide ? ["32%", "50%"] : ["50%", "42%"];
    return {
      ...base,
      legend: {
        ...(wide ? { orient: "vertical", right: "6%", top: "middle" } : { bottom: 0, left: "center" }),
        icon: "circle",
        itemWidth: 8,
        itemHeight: 8,
        itemGap: 14,
        textStyle: { color: p.text, fontSize: 12 },
        formatter: (name: string) => {
          const row = byName.get(name);
          const share = total > 0 && row ? Math.round(((row.value || 0) / total) * 1000) / 10 : 0;
          return wide ? `${name}    ${number(row?.value ?? 0)} · ${share}%` : name;
        }
      },
      series: [
        {
          type: "pie",
          radius: ["58%", "78%"],
          center,
          padAngle: 1.5,
          minAngle: 3,
          itemStyle: { borderRadius: 6, borderColor: p.surface, borderWidth: 2 },
          label: { show: false },
          labelLine: { show: false },
          emphasis: { scale: true, scaleSize: 4, focus: "self" },
          blur: { itemStyle: { opacity: 0.35 } },
          animationType: "expansion",
          data: rows.map((row, index) => ({
            value: row.value || 0,
            name: row.label,
            rowIndex: index,
            itemStyle: { color: isUnknownLabel(row.label) ? p.unknown : p.categorical[index % p.categorical.length] }
          }))
        }
      ],
      graphic: [
        {
          type: "text",
          left: wide ? "26%" : "center",
          top: wide ? "41%" : "33%",
          style: {
            text: number(chart.centerValue ?? total),
            fontSize: 28,
            fontWeight: 600,
            fill: p.text,
            align: "center",
            width: wide ? 120 : undefined
          } as Record<string, unknown>
        },
        {
          type: "text",
          left: wide ? "26%" : "center",
          top: wide ? "53%" : "45%",
          style: {
            text: chart.centerLabel || chart.unit || "",
            fontSize: 12,
            fill: p.textMuted,
            align: "center",
            width: wide ? 120 : undefined
          } as Record<string, unknown>
        }
      ]
    };
  }

  if (chart.kind === "matrix") {
    const xs = [...new Set(rows.map(r => r.x || ""))];
    const ys = [...new Set(rows.map(r => r.y || ""))];
    const rowTotals = new Map<string, number>();
    rows.forEach(r => rowTotals.set(r.y || "", (rowTotals.get(r.y || "") || 0) + (r.value || 0)));
    const cell = (row: AnalysisRow) => {
      if (!normalize.value) return row.value || 0;
      const total = rowTotals.get(row.y || "") || 0;
      return total ? Math.round(((row.value || 0) / total) * 1000) / 10 : 0;
    };
    const leftWidth = narrow.value ? 88 : 128;
    // 单元格宽度封顶：列少时网格不再被拉伸到整卡宽
    const available = containerWidth.value ? containerWidth.value - leftWidth - 56 : 620;
    const gridWidth = Math.max(140, Math.min(available, xs.length * 92 + 24));
    return {
      ...base,
      grid: { left: leftWidth, width: gridWidth, top: 8, bottom: 72 },
      xAxis: {
        type: "category",
        data: xs,
        axisLabel: {
          ...axisLabel,
          interval: 0,
          rotate: xs.length > 5 ? 36 : 0,
          width: narrow.value ? 44 : 72,
          overflow: "truncate"
        },
        axisTick: { show: false },
        axisLine: { show: false },
        splitArea: { show: false }
      },
      yAxis: {
        type: "category",
        data: ys,
        inverse: true,
        axisLabel: { ...axisLabel, color: p.text, width: narrow.value ? 78 : 116, overflow: "truncate" },
        axisTick: { show: false },
        axisLine: { show: false }
      },
      visualMap: {
        min: 0,
        max: normalize.value ? 100 : Math.max(1, ...rows.map(r => r.value || 0)),
        formatter: (value: unknown) => (normalize.value ? `${Math.round(Number(value))}%` : String(Math.round(Number(value)))),
        calculable: false,
        orient: "horizontal",
        left: leftWidth,
        bottom: 0,
        itemWidth: 8,
        itemHeight: 120,
        inRange: { color: [p.ramp[0], p.ramp[2], p.ramp[4], p.ramp[5]] },
        textStyle: { color: p.textMuted, fontSize: 11 }
      },
      series: [
        {
          type: "heatmap",
          data: rows.map((row, index) => ({
            value: [xs.indexOf(row.x || ""), ys.indexOf(row.y || ""), cell(row)],
            rowIndex: index
          })),
          label: {
            show: xs.length * ys.length <= 30,
            fontSize: 11,
            fontWeight: 600,
            color: p.text,
            textBorderColor: p.surface,
            textBorderWidth: 2,
            formatter: ({ value }: { value: unknown }) => {
              const v = Array.isArray(value) ? Number(value[2]) : 0;
              return normalize.value ? `${v}%` : number(v);
            }
          },
          itemStyle: { borderColor: p.surface, borderWidth: 3, borderRadius: 6 },
          emphasis: { itemStyle: { borderColor: p.brand, borderWidth: 2 } }
        }
      ]
    };
  }

  // 横向条形：每个分类一种渐变色，含未知的类别弱化为灰绿；
  // 有序分组（环节、星期）用单一品牌色，间隔分布用语义色
  const barColor = (row: AnalysisRow, index: number) => {
    if ((row.unknownCount || 0) > 0 || isUnknownLabel(row.label)) return p.unknown;
    if (chart.id === "delay") return delayColor(p, row.label) ?? categoricalGradient(p, index);
    if (chart.ordered) return categoricalGradient(p, 0);
    return categoricalGradient(p, index);
  };
  return {
    ...base,
    grid: { left: 8, right: 48, top: 4, bottom: 4, containLabel: true },
    xAxis: { type: "value", minInterval: 1, show: false },
    yAxis: {
      type: "category",
      data: rows.map(r => r.label),
      inverse: true,
      axisLabel: { ...axisLabel, color: p.text, width: narrow.value ? 96 : 136, overflow: "truncate", margin: 14 },
      axisLine: { show: false },
      axisTick: { show: false }
    },
    series: [
      {
        type: "bar",
        barWidth: 14,
        showBackground: true,
        backgroundStyle: { color: p.track, borderRadius: 7 },
        data: rows.map((row, index) => ({
          value: row.value || 0,
          rowIndex: index,
          itemStyle: { color: barColor(row, index), borderRadius: 7 }
        })),
        label: {
          show: true,
          position: "right",
          distance: 8,
          color: p.text,
          fontSize: 12,
          fontWeight: 500,
          formatter: ({ value }: { value: unknown }) => number(Number(value) || 0)
        },
        emphasis: { focus: "self" as const, itemStyle: { shadowBlur: 12, shadowColor: p.brandSoft } },
        blur: { itemStyle: { opacity: 0.4 } }
      }
    ]
  };
});
</script>

<style scoped lang="scss">
.analysis-chart {
  position: relative;
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 18px 20px 14px;
  overflow: hidden;
  transition:
    opacity 200ms ease,
    translate 180ms var(--pa-ease),
    box-shadow 180ms ease,
    border-color 180ms ease;

  /* backwards：入场结束后不再锁定 transform，悬停位移用独立的 translate 属性 */
  animation: pa-card-enter 420ms var(--pa-ease) backwards;
  animation-delay: calc(var(--pa-enter-index, 0) * 40ms);
}

@media (hover: hover) {
  .analysis-chart:hover {
    border-color: color-mix(in srgb, var(--pa-brand) 32%, var(--pa-border));
    box-shadow: var(--pa-shadow-hover);
    translate: 0 -2px;
  }
}
.chart-normalize {
  height: 26px;
  padding: 0 10px;
  font: inherit;
  font-size: 12px;
  color: var(--pa-text-2);
  cursor: pointer;
  background: var(--pa-subtle);
  border: 0;
  border-radius: 8px;
  transition:
    color 150ms ease,
    background-color 150ms ease;
}
.chart-normalize[aria-pressed="true"] {
  color: var(--pa-brand);
  background: var(--pa-brand-soft);
}
.chart-normalize:active {
  transform: scale(0.97);
}
.chart-normalize:focus-visible {
  outline: 2px solid var(--pa-brand);
  outline-offset: 2px;
}
.analysis-chart::before {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  height: 3px;
  content: "";
  background: linear-gradient(90deg, var(--pa-accent), var(--pa-accent-2));
}
.analysis-chart.is-busy {
  pointer-events: none;
  opacity: 0.6;
}

@keyframes pa-card-enter {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.chart-heading,
.chart-title,
.chart-tools {
  display: flex;
  align-items: center;
}
.chart-heading {
  gap: 12px;
  justify-content: space-between;
  margin-bottom: 14px;
}
.chart-title {
  gap: 4px;
  min-width: 0;
}
.chart-title h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--pa-text);
  overflow-wrap: anywhere;
}
.is-main .chart-title h3 {
  font-size: 16px;
}
.chart-info {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  padding: 0;
  color: var(--pa-text-3);
  cursor: help;
  background: transparent;
  border: 0;
  border-radius: 6px;
  transition: color 150ms ease;
}
.chart-info:hover {
  color: var(--pa-brand);
}
.chart-tools {
  flex-shrink: 0;
  gap: 10px;
}
.chart-unit {
  padding: 2px 8px;
  font-size: 12px;
  font-weight: 500;
  color: var(--pa-accent);
  background: color-mix(in srgb, var(--pa-accent) 12%, transparent);
  border-radius: 999px;
}

/* 图表 / 数据表 切换：共享滑块 */
.chart-mode {
  position: relative;
  display: inline-grid;
  grid-template-columns: 28px 28px;
  padding: 2px;
  background: var(--pa-subtle);
  border-radius: 8px;
}
.chart-mode-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 28px;
  height: 26px;
  background: var(--pa-surface);
  border-radius: 6px;
  box-shadow: 0 1px 2px rgb(16 24 40 / 10%);
  transition: transform 220ms var(--pa-ease);
}
.chart-mode.is-table .chart-mode-thumb {
  transform: translateX(28px);
}
.chart-mode button {
  position: relative;
  display: grid;
  place-items: center;
  height: 26px;
  padding: 0;
  color: var(--pa-text-3);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 6px;
  transition: color 150ms ease;
}
.chart-mode button[aria-pressed="true"] {
  color: var(--pa-brand);
}
.chart-mode button:active {
  transform: scale(0.96);
}
.chart-info:focus-visible,
.chart-mode button:focus-visible,
.table-filter:focus-visible,
.chart-table-scroll:focus-visible {
  outline: 2px solid var(--pa-brand);
  outline-offset: 2px;
}

/* 不能用 flex: 1——列方向 flex 下 basis 0 会覆盖内联高度，图表容器被压成 0 高 */
.chart-canvas {
  flex: none;
  width: 100%;
  min-width: 0;
}

/* 内容切换 */
.pa-swap-enter-active,
.pa-swap-leave-active {
  transition:
    opacity 150ms ease,
    transform 150ms ease;
}
.pa-swap-enter-from {
  opacity: 0;
  transform: translateY(4px);
}
.pa-swap-leave-to {
  opacity: 0;
}
.chart-empty {
  display: grid;
  gap: 6px;
  place-content: center;
  justify-items: center;
  height: 100%;
  min-height: 200px;
  text-align: center;
}
.chart-empty-mark {
  width: 40px;
  height: 40px;
  margin-bottom: 6px;
  background: repeating-linear-gradient(135deg, transparent 0 5px, var(--pa-subtle) 5px 10px);
  border: 1.5px dashed var(--pa-border-strong);
  border-radius: 12px;
}
.chart-empty strong {
  font-size: 14px;
  font-weight: 600;
  color: var(--pa-text-2);
}
.chart-empty small {
  font-size: 12px;
  color: var(--pa-text-3);
}
.matrix-single {
  display: grid;
  gap: 8px;
  place-content: center;
  justify-items: center;
  min-height: 260px;
  padding: 24px 16px;
  background: var(--pa-subtle);
  border-radius: 10px;
}
.matrix-single-value {
  display: flex;
  gap: 6px;
  align-items: baseline;
}
.matrix-single-value strong {
  font-size: 40px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1;
  color: var(--pa-brand);
}
.matrix-single-value span,
.matrix-single-meta,
.matrix-single small {
  font-size: 12px;
  color: var(--pa-text-3);
}
.matrix-single-label {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--pa-text);
  text-align: center;
  overflow-wrap: anywhere;
}
.matrix-single-meta {
  margin: 0;
  font-variant-numeric: tabular-nums;
}
.chart-footnote {
  padding-top: 10px;
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--pa-text-3);
  overflow-wrap: anywhere;
  border-top: 1px dashed var(--pa-border);
}
.chart-footnote.is-hint {
  padding-top: 0;
  border-top: 0;
  opacity: 0;
  transition: opacity 150ms ease;
}
.analysis-chart:hover .chart-footnote.is-hint {
  opacity: 1;
}

/* 数据表 */
.chart-table-scroll {
  min-height: 240px;
  max-height: 440px;
  overflow: auto;
  border-radius: 8px;
}
.chart-table {
  width: 100%;
  font-size: 13px;
  border-spacing: 0;
  border-collapse: separate;
}
.chart-table th,
.chart-table td {
  padding: 9px 10px;
  font-variant-numeric: tabular-nums;
  color: var(--pa-text-2);
  text-align: right;
  white-space: nowrap;
  border-bottom: 1px solid var(--pa-border);
}
.chart-table th:first-child {
  max-width: 240px;
  text-align: left;
  overflow-wrap: anywhere;
  white-space: normal;
}
.chart-table thead th {
  position: sticky;
  top: 0;
  z-index: 1;
  font-size: 12px;
  font-weight: 500;
  color: var(--pa-text-3);
  background: var(--pa-surface);
}
.chart-table tbody tr {
  transition: background-color 120ms ease;
}
.chart-table tbody tr:hover {
  background: var(--pa-subtle);
}
.chart-table td.strong {
  font-weight: 600;
  color: var(--pa-text);
}
.chart-table td.muted {
  color: var(--pa-text-3);
}
.share-cell {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  min-width: 110px;
}
.share-cell i {
  display: block;
  width: 56px;
  height: 4px;
  overflow: hidden;
  background: var(--pa-subtle-strong);
  border-radius: 2px;
}
.share-cell b {
  display: block;
  height: 100%;
  background: var(--pa-brand);
  border-radius: inherit;
}
.table-filter {
  padding: 0;
  font: inherit;
  font-weight: 500;
  color: var(--pa-text);
  text-align: inherit;
  cursor: pointer;
  background: none;
  border: 0;
  transition: color 120ms ease;
}
.table-filter:hover {
  color: var(--pa-brand);
}
.table-filter:disabled {
  color: var(--pa-text-3);
  cursor: default;
}
.no-rows {
  color: var(--pa-text-3);
  text-align: center !important;
}

@media (prefers-reduced-motion: reduce) {
  .analysis-chart,
  .analysis-chart:hover,
  .chart-mode-thumb,
  .pa-swap-enter-active,
  .pa-swap-leave-active {
    transition: none;
    animation: none;
  }
  .analysis-chart:hover {
    translate: none;
  }
}

@media (width <= 520px) {
  .analysis-chart {
    padding: 16px 14px 12px;
  }
  .chart-unit {
    display: none;
  }
}
</style>

<!-- tooltip 由 ECharts 挂到图表容器内，不受 scoped 约束，这里用非 scoped 的限定类名 -->
<style lang="scss">
.pa-tt {
  min-width: 150px;
  font-size: 12px;
  line-height: 1.5;
}
.pa-tt-title {
  max-width: 260px;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  overflow-wrap: anywhere;
  white-space: normal;
}
.pa-tt-row {
  display: grid;
  grid-template-columns: 8px 1fr auto;
  gap: 8px;
  align-items: center;
  margin-top: 2px;
}
.pa-tt-row i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.pa-tt-row b,
.pa-tt-value b {
  font-variant-numeric: tabular-nums;
}
.pa-tt-value {
  display: flex;
  gap: 4px;
  align-items: baseline;
}
.pa-tt-value b {
  font-size: 18px;
  font-weight: 600;
}
.pa-tt-value span {
  opacity: 0.7;
}
.pa-tt-value em {
  margin-left: auto;
  font-style: normal;
  font-weight: 600;
}
.pa-tt-bar {
  height: 4px;
  margin: 6px 0;
  overflow: hidden;
  background: rgb(127 140 141 / 18%);
  border-radius: 2px;
}
.pa-tt-bar i {
  display: block;
  height: 100%;
  border-radius: inherit;
}
.pa-tt-meta {
  opacity: 0.72;
}
.pa-tt-hint {
  padding-top: 6px;
  margin-top: 6px;
  font-size: 11px;
  border-top: 1px dashed rgb(127 140 141 / 30%);
  opacity: 0.6;
}
</style>
