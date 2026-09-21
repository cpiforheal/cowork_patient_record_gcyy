<template>
  <section class="age-distribution-curve">
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
    <VChart v-if="hasData" class="curve-chart" :option="chartOption" autoresize />
    <el-empty v-else description="当前窗口内暂无带年龄的来访患者" :image-size="56" />
    <!-- 分布辅助描述：分段占比条 + 文字概述 -->
    <div v-if="hasData" class="age-summary">
      <div class="age-band-rows">
        <div v-for="band in bandStats" :key="band.name" class="age-band-row">
          <span class="band-dot" :style="{ background: band.color }" />
          <span class="band-name">{{ band.name }}</span>
          <div class="band-bar-track">
            <div class="band-bar" :style="{ width: band.percent + '%', background: band.color }" />
          </div>
          <span class="band-count">{{ band.count }} 人</span>
          <span class="band-percent">{{ band.percent }}%</span>
        </div>
      </div>
      <p class="age-summary-text">{{ summaryText }}</p>
    </div>
  </section>
</template>

<script setup lang="ts">
// 年龄分布折线图：与参考样式（多系列平滑曲线）对齐——
// 5 条年龄分段线、3px 圆角线宽、数据点实心圆点标记、顶部圆点图例、深色圆角 tooltip。
import { computed } from "vue";
import { LineChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import VChart from "vue-echarts";
import type { EChartsOption } from "echarts";
import { useGlobalStore } from "@/stores/modules/global";

use([CanvasRenderer, LineChart, GridComponent, LegendComponent, TooltipComponent]);

export interface AgeSeriesItem {
  /** 年龄分段名，如 "31-40 岁" */
  name: string;
  /** 与 labels 一一对应的当日来访人数 */
  data: number[];
}

const props = withDefaults(
  defineProps<{
    title?: string;
    subtitle?: string;
    /** X 轴标签（如 9月7日） */
    labels: string[];
    /** X 轴完整日期（tooltip 用） */
    dates: string[];
    /** 年龄分段系列（固定 5 条，与参考图条数一致） */
    series: AgeSeriesItem[];
  }>(),
  {
    title: "来访患者年龄分布",
    subtitle: "按就诊日期 × 年龄分段统计 · 数据粒度为病历年龄字段",
    labels: () => [],
    dates: () => [],
    series: () => []
  }
);

const globalStore = useGlobalStore();
const isDark = computed(() => globalStore.isDark);

const palette = computed(() => ({
  text: isDark.value ? "#eef5ff" : "#2d2f33",
  muted: isDark.value ? "#a8b3c2" : "#74777d",
  split: isDark.value ? "rgba(166, 184, 205, 0.16)" : "rgba(120, 128, 138, 0.12)",
  tooltipBg: isDark.value ? "rgba(17, 24, 39, 0.94)" : "rgba(255, 255, 255, 0.96)",
  tooltipBorder: isDark.value ? "rgba(166, 184, 205, 0.22)" : "rgba(120, 128, 138, 0.12)",
  blue: "#1683ff"
}));

// 与参考图一致的 5 色系列：蓝 / 绿 / 紫 / 红 / 橙
const SERIES_COLORS = ["#3b82f6", "#22c55e", "#8b5cf6", "#ef4444", "#f59e0b"];

const hasData = computed(() => props.labels.length > 0 && props.series.some(item => item.data.some(value => value > 0)));

const total = computed(() => props.series.reduce((sum, item) => sum + item.data.reduce((a, b) => a + b, 0), 0));

// 分段统计（辅助描述）：各段合计 / 占比 / 排序
const bandStats = computed(() => {
  const grand = total.value || 1;
  return props.series
    .map((item, index) => {
      const count = item.data.reduce((a, b) => a + b, 0);
      return { name: item.name, color: SERIES_COLORS[index], count, percent: Math.round((count / grand) * 1000) / 10 };
    })
    .sort((a, b) => b.count - a.count);
});

// 文字概述：主力年龄段 + 占比 + 单日高峰 + 覆盖说明
const summaryText = computed(() => {
  if (!hasData.value) return "";
  const bands = bandStats.value;
  const windowText = props.dates.length ? `${props.dates[0]} 至 ${props.dates[props.dates.length - 1]}` : "当前窗口";
  const parts: string[] = [];
  if (bands[0]?.count) {
    const second = bands[1]?.count ? `，其次为 ${bands[1].name}（${bands[1].count} 人，占 ${bands[1].percent}%）` : "";
    parts.push(`${windowText}窗口内来访患者以 ${bands[0].name} 为主（${bands[0].count} 人，占 ${bands[0].percent}%）${second}`);
  }
  // 单日高峰：所有系列中单日单段最大值
  let peak = { band: "", label: "", value: 0 };
  props.series.forEach(item => {
    item.data.forEach((value, index) => {
      if (value > peak.value) peak = { band: item.name, label: props.labels[index], value };
    });
  });
  if (peak.value) parts.push(`单日高峰出现在 ${peak.label}（${peak.band} ${peak.value} 人）`);
  const activeDays = props.labels.filter((_, index) => props.series.some(item => item.data[index] > 0)).length;
  parts.push(`窗口内 ${activeDays}/${props.labels.length} 天有带年龄记录的来访`);
  return `${parts.join("；")}。`;
});

const peakLabel = computed(() => {
  let best = { label: "—", value: 0 };
  props.series.forEach(item => {
    item.data.forEach((value, index) => {
      if (value > best.value) best = { label: `${props.labels[index]} ${item.name}`, value };
    });
  });
  return best.value ? `${best.label} ${best.value}人` : "—";
});

const chartOption = computed<EChartsOption>(() => {
  const p = palette.value;
  return {
    animation: true,
    animationDuration: 520,
    animationDurationUpdate: 600,
    animationEasing: "cubicOut",
    animationEasingUpdate: "cubicInOut",
    legend: {
      top: 0,
      left: 4,
      icon: "circle",
      itemWidth: 10,
      itemHeight: 10,
      itemGap: 18,
      textStyle: { color: p.text, fontSize: 13 }
    },
    tooltip: {
      trigger: "axis",
      confine: true,
      backgroundColor: p.tooltipBg,
      borderColor: p.tooltipBorder,
      borderWidth: 1,
      borderRadius: 12,
      padding: [12, 14],
      textStyle: { color: p.text, fontSize: 13, lineHeight: 22 },
      axisPointer: { type: "line", lineStyle: { color: p.blue, width: 1, type: "dashed" } },
      formatter: (params: any) => {
        const list = Array.isArray(params) ? params : [params];
        const first = list[0];
        const date = props.dates[first?.dataIndex] || first?.axisValue || "";
        const head = `<b>${date} · 来访患者 ${list.reduce((sum: number, item: any) => sum + (item?.value ?? 0), 0)} 人</b>`;
        const rows = list
          .filter((item: any) => (item?.value ?? 0) > 0)
          .map(
            (item: any) =>
              `<span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${item.color};margin-right:6px"></span>` +
              `<span style="color:${p.muted}">${item.seriesName}</span>　<b>${item.value ?? 0} 人</b>`
          )
          .join("<br/>");
        return rows ? `${head}<br/>${rows}` : `${head}<br/><span style="color:${p.muted}">当日无来访</span>`;
      }
    },
    grid: { left: 58, right: 34, top: 48, bottom: 42, containLabel: true },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: props.labels,
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: { color: p.muted, fontSize: 13, margin: 14 }
    },
    yAxis: {
      type: "value",
      min: 0,
      splitNumber: 4,
      axisLabel: { show: false },
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: p.split, type: "dashed" } }
    },
    series: props.series.map((item, index) => ({
      name: item.name,
      type: "line" as const,
      data: item.data,
      smooth: 0.42,
      symbol: "circle",
      symbolSize: 8,
      // 数据点默认隐藏，仅 hover / tooltip 悬浮时显示
      showSymbol: false,
      lineStyle: { width: 3, color: SERIES_COLORS[index], cap: "round", join: "round" },
      itemStyle: { color: SERIES_COLORS[index], borderColor: "#ffffff", borderWidth: 2 },
      emphasis: { focus: "series", scale: true },
      z: 6 - index
    }))
  };
});
</script>

<style scoped lang="scss">
.age-distribution-curve {
  display: grid;
  gap: 14px;
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
    b {
      color: var(--hos-chart-text, #2d2f33);
    }
  }
}
.curve-chart {
  width: 100%;
  height: 340px;
}
// 分布辅助描述：分段占比条 + 文字概述
.age-summary {
  display: grid;
  gap: 12px;
  padding: 14px 16px;
  background: color-mix(in srgb, var(--el-color-primary) 4%, var(--hos-chart-panel, #ffffff));
  border: 1px solid var(--hos-chart-line-soft, rgb(90 110 130 / 10%));
  border-radius: 12px;
  .age-band-rows {
    display: grid;
    gap: 8px;
  }
  .age-band-row {
    display: grid;
    grid-template-columns: 14px 76px minmax(0, 1fr) 56px 52px;
    gap: 10px;
    align-items: center;
    .band-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
    }
    .band-name {
      font-size: 12.5px;
      font-weight: 600;
      color: var(--hos-chart-text, #2d2f33);
    }
    .band-bar-track {
      height: 8px;
      overflow: hidden;
      background: var(--hos-chart-line-soft, rgb(90 110 130 / 10%));
      border-radius: 999px;
      .band-bar {
        min-width: 4px;
        height: 100%;
        border-radius: 999px;
        transition: width 0.5s cubic-bezier(0.23, 1, 0.32, 1);
      }
    }
    .band-count {
      font-size: 12.5px;
      font-weight: 650;
      color: var(--hos-chart-text, #2d2f33);
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
    .band-percent {
      font-size: 12px;
      color: var(--hos-chart-muted, #74777d);
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
  }
  .age-summary-text {
    margin: 0;
    font-size: 13px;
    line-height: 1.8;
    color: var(--hos-chart-text, #2d2f33);
  }
}
@media (width <= 760px) {
  .curve-head {
    flex-direction: column;
  }
  .curve-chart {
    height: 300px;
  }
}
</style>
