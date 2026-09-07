<template>
  <section class="daily-patient-curve">
    <div class="curve-head">
      <div>
        <h3>{{ title }}</h3>
        <p>{{ subtitle }}</p>
      </div>
      <div class="curve-summary">
        <span>
          窗口合计
          <b>{{ total }}</b>
          人
        </span>
        <span>
          峰值
          <b>{{ peakLabel }}</b>
        </span>
      </div>
    </div>
    <VChart v-if="items.length" class="curve-chart" :option="chartOption" autoresize />
    <el-empty v-else description="暂无每日患者数据" :image-size="56" />
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { LineChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import VChart from "vue-echarts";
import type { EChartsOption } from "echarts";
import { useGlobalStore } from "@/stores/modules/global";

use([CanvasRenderer, LineChart, GridComponent, LegendComponent, TooltipComponent]);

export interface DailyCurveItem {
  date: string;
  label: string;
  /** 当日来访患者数（数据源：患者收费信息，无工作流状态字段，其余状态系列已移除） */
  total: number;
}

const props = withDefaults(
  defineProps<{
    title?: string;
    subtitle?: string;
    items: DailyCurveItem[];
  }>(),
  {
    title: "每日患者趋势图",
    subtitle: "按就诊日期统计 · 与每日患者数据粒度一致"
  }
);

const globalStore = useGlobalStore();
const isDark = computed(() => globalStore.isDark);

const palette = computed(() => ({
  panel: isDark.value ? "#16213e" : "#f5f5f5",
  text: isDark.value ? "#eef5ff" : "#2d2f33",
  muted: isDark.value ? "#a8b3c2" : "#74777d",
  split: isDark.value ? "rgba(166, 184, 205, 0.16)" : "rgba(120, 128, 138, 0.12)",
  tooltipBg: isDark.value ? "rgba(17, 24, 39, 0.94)" : "rgba(255, 255, 255, 0.94)",
  tooltipBorder: isDark.value ? "rgba(166, 184, 205, 0.22)" : "rgba(120, 128, 138, 0.12)",
  blue: "#1683ff"
}));

const total = computed(() => props.items.reduce((sum, item) => sum + item.total, 0));
const peak = computed(() =>
  props.items.reduce((max, item) => (item.total > max.total ? item : max), props.items[0] || { label: "—", total: 0 })
);
const peakLabel = computed(() => (peak.value.total ? `${peak.value.label} ${peak.value.total}人` : "—"));
const maxValue = computed(() => Math.max(5, ...props.items.map(item => item.total)));

const chartOption = computed<EChartsOption>(() => {
  const p = palette.value;
  const labels = props.items.map(item => item.label);
  const actual = props.items.map(item => item.total);
  return {
    animation: true,
    animationDuration: 520,
    animationDurationUpdate: 240,
    animationEasing: "cubicOut",
    animationEasingUpdate: "cubicOut",
    tooltip: {
      trigger: "axis",
      confine: true,
      backgroundColor: p.tooltipBg,
      borderColor: p.tooltipBorder,
      borderWidth: 1,
      borderRadius: 12,
      padding: [12, 14],
      textStyle: { color: p.text, fontSize: 13, lineHeight: 22 },
      axisPointer: {
        type: "line",
        lineStyle: { color: p.blue, width: 1, type: "dashed" }
      },
      formatter: (params: any) => {
        const point = Array.isArray(params) ? params[0] : params;
        const date = props.items[point?.dataIndex]?.date || point?.axisValue || "";
        return `<b>${date}</b><br/><span style="display:inline-block;width:9px;height:9px;border-radius:50%;background:${p.blue};margin-right:8px"></span>来访患者<span style="float:right;margin-left:20px;font-weight:700;color:${p.text}">${point?.value ?? 0} 人</span>`;
      }
    },
    grid: { left: 58, right: 34, top: 40, bottom: 42, containLabel: true },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: labels,
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: { color: p.muted, fontSize: 13, margin: 14 }
    },
    yAxis: {
      type: "value",
      min: 0,
      max: Math.ceil(maxValue.value * 1.2),
      splitNumber: 4,
      axisLabel: { show: false },
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: p.split, type: "dashed" } }
    },
    series: [
      {
        name: "来访患者",
        type: "line",
        data: actual,
        smooth: 0.42,
        symbol: "circle",
        symbolSize: 9,
        showSymbol: false,
        lineStyle: { width: 4, color: p.blue, cap: "round", join: "round" },
        itemStyle: { color: p.blue, borderColor: "#ffffff", borderWidth: 2 },
        emphasis: { focus: "series", scale: true },
        areaStyle: { color: "rgba(22, 131, 255, 0.05)" },
        z: 6
      }
    ]
  };
});
</script>

<style scoped lang="scss">
.daily-patient-curve {
  display: grid;
  gap: 14px;
  min-height: 430px;
  padding: 18px 18px 12px;
  overflow: hidden;
  background: color-mix(in srgb, var(--hos-chart-panel-soft, #f5f5f5) 82%, #ffffff);
  border: 1px solid var(--hos-chart-line-soft, rgb(90 110 130 / 8%));
  border-radius: 18px;
}
.curve-head {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  justify-content: space-between;
  h3 {
    margin: 0;
    font-size: 18px;
    font-weight: 800;
    color: var(--hos-chart-text, #2d2f33);
  }
  p {
    margin: 6px 0 0;
    font-size: 13px;
    color: var(--hos-chart-muted, #74777d);
  }
}
.curve-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
  span {
    padding: 5px 10px;
    font-size: 12px;
    color: var(--hos-chart-muted, #74777d);
    background: var(--hos-chart-panel, #ffffff);
    border: 1px solid var(--hos-chart-line-soft, rgb(90 110 130 / 8%));
    border-radius: 999px;
  }
  b {
    color: var(--hos-chart-text, #2d2f33);
  }
}
.curve-chart {
  width: 100%;
  height: 340px;
}

@media (width <= 760px) {
  .curve-head {
    flex-direction: column;
  }
  .curve-summary {
    justify-content: flex-start;
  }
  .curve-chart {
    height: 300px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .daily-patient-curve :deep(canvas) {
    transition: none;
  }
}
</style>
