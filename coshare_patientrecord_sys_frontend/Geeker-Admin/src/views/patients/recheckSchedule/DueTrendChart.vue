<template>
  <section class="due-trend" aria-label="每日应到预约人数趋势">
    <h4>每日应到预约（人）</h4>
    <VChart v-if="hasData" class="due-trend-canvas" :option="option" autoresize />
    <p v-else class="due-trend-empty">当前月份暂无预约数据</p>
  </section>
</template>

<script setup lang="ts" name="dueTrendChart">
import { computed } from "vue";
import { LineChart } from "echarts/charts";
import { GridComponent, TooltipComponent } from "echarts/components";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import VChart from "vue-echarts";
import type { EChartsOption } from "echarts";
import type { RecheckDay } from "@/api/modules/clinic/recheckSchedule";

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent]);

const props = defineProps<{ days: RecheckDay[]; today: string }>();

// 应到预约人数 = 当日登记且未撤销/未改走的条目（含已到、未到、待到）
const hasData = computed(() => props.days.some(day => day.entries.length > 0));

const reducedMotion = typeof window !== "undefined" && !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

const option = computed<EChartsOption>(() => {
  const days = [...props.days].sort((a, b) => a.date.localeCompare(b.date));
  const due = days.map(day => ({
    value: day.stats.planned + day.stats.arrived + day.stats.absent,
    isToday: day.date === props.today
  }));
  return {
    animation: !reducedMotion,
    animationDuration: 420,
    animationDurationUpdate: 600,
    animationEasing: "cubicOut",
    textStyle: { fontFamily: "inherit", fontSize: 10, color: "#53636b" },
    grid: { left: 30, right: 10, top: 14, bottom: 22 },
    tooltip: {
      trigger: "axis",
      confine: true,
      formatter: (input: unknown) => {
        const items = (Array.isArray(input) ? input : [input]) as Array<{ dataIndex: number }>;
        const index = items[0]?.dataIndex ?? -1;
        if (index < 0 || !days[index]) return "";
        const day = days[index];
        const label = day.date === props.today ? `${day.date}（今天）` : day.date;
        return `${label} ${day.weekday}\n应到预约 ${due[index].value} 人 · 已到 ${day.stats.arrived}`;
      }
    },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: days.map(day => day.date.slice(5)),
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: { interval: 4, hideOverlap: true }
    },
    yAxis: {
      type: "value",
      minInterval: 1,
      splitLine: { lineStyle: { color: "#e7edf1", type: "dashed" } }
    },
    series: [
      {
        type: "line",
        smooth: 0.42,
        showSymbol: false,
        symbol: "circle",
        symbolSize: 7,
        lineStyle: { width: 3, cap: "round", color: "#0f766e" },
        itemStyle: { color: "#0f766e", borderColor: "#fff", borderWidth: 2 },
        areaStyle: { color: "rgba(15, 118, 110, 0.06)" },
        emphasis: { focus: "series" },
        markLine: {
          silent: true,
          symbol: "none",
          label: { show: false },
          lineStyle: { color: "#0f766e", type: "dashed", width: 1, opacity: 0.45 },
          data: [{ xAxis: Math.max(0, days.findIndex(day => day.date === props.today)) }]
        },
        data: due
      }
    ]
  };
});
</script>

<style scoped lang="scss">
.due-trend {
  display: grid;
  gap: 6px;
  padding: 12px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;

  h4 {
    margin: 0;
    font-size: 12px;
    font-weight: 600;
    color: var(--el-text-color-secondary);
  }
}
.due-trend-canvas {
  width: 100%;
  height: 170px;
}
.due-trend-empty {
  margin: 0;
  padding: 26px 0;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  text-align: center;
}
</style>
