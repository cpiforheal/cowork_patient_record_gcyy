<template>
  <section class="overview-view">
    <div class="metric-grid">
      <button v-for="item in metrics" :key="item.key" type="button" class="metric-card" :class="`is-${item.tone}`" @click="$emit('attention', item.key)">
        <span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.note }}</small>
      </button>
    </div>
    <div class="overview-grid">
      <article class="panel trend-panel">
        <header class="panel-head"><div><h3>有效来访与去重患者</h3><p>{{ data.period.from }} 至 {{ data.period.to }}</p></div><el-segmented v-model="granularity" :options="granularityOptions" @change="$emit('granularity', granularity)" /></header>
        <VChart class="trend-chart" :option="trendOption" autoresize />
      </article>
      <article class="panel attention-panel">
        <header class="panel-head"><div><h3>需要关注</h3><p>只保留当前需要动作的事项</p></div></header>
        <button class="attention-row is-danger" type="button" @click="$emit('attention', 'overdue')"><span>严重逾期随访</span><strong>{{ data.attention.overdueFollowUps }}</strong><el-icon><ArrowRight /></el-icon></button>
        <button class="attention-row is-warning" type="button" @click="$emit('attention', 'today')"><span>今日待随访</span><strong>{{ data.attention.dueTodayFollowUps }}</strong><el-icon><ArrowRight /></el-icon></button>
        <button class="attention-row" type="button" @click="$emit('attention', 'address')"><span>未识别地址患者</span><strong>{{ data.attention.unidentifiedAddresses }}</strong><el-icon><ArrowRight /></el-icon></button>
      </article>
    </div>
    <article v-if="data.departments.length" class="panel department-panel">
      <header class="panel-head"><div><h3>科室来访概览</h3><p>仅展示当前周期聚合结果</p></div></header>
      <div class="department-list"><div v-for="row in data.departments" :key="row.department" class="department-row"><span>{{ row.department }}</span><i><b :style="{ width: `${Math.max(row.share, 2)}%` }" /></i><strong>{{ row.visits }}</strong><small>{{ row.uniquePatients }} 位患者</small></div></div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { ArrowRight } from "@element-plus/icons-vue";
import { BarChart, LineChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import VChart from "vue-echarts";
import type { EChartsOption } from "echarts";
import type { OpsDashboardAnalysisResult } from "@/api/modules/clinic/opsDashboard";

use([CanvasRenderer, LineChart, BarChart, GridComponent, LegendComponent, TooltipComponent]);
defineEmits<{ attention: [key: string]; granularity: [value: "day" | "week" | "month"] }>();
const props = defineProps<{ data: OpsDashboardAnalysisResult }>();
const granularity = ref<"day" | "week" | "month">("day");
const granularityOptions = [{ label: "日", value: "day" }, { label: "周", value: "week" }, { label: "月", value: "month" }];
const metrics = computed(() => [
  { key: "visits", label: "有效来访人次", value: props.data.kpi.visits, note: "当前统计周期", tone: "primary" },
  { key: "patients", label: "去重患者", value: props.data.kpi.uniquePatients, note: "按病例标识去重", tone: "info" },
  { key: "new", label: "首次来访患者", value: props.data.kpi.newPatients, note: "患者历史首次来访", tone: "success" },
  { key: "overdue", label: "严重逾期随访", value: props.data.kpi.overdueFollowUps, note: "需要优先处理", tone: "danger" }
]);
const trendOption = computed<EChartsOption>(() => ({
  color: ["#176b87", "#8bbf9f"],
  grid: { left: 38, right: 18, top: 28, bottom: 30 },
  legend: { top: 0, right: 0, textStyle: { color: "#6b7280", fontSize: 11 } },
  tooltip: { trigger: "axis" },
  xAxis: { type: "category", data: props.data.trend.map(item => item.period.slice(5)), axisLabel: { color: "#8a94a6", fontSize: 11 } },
  yAxis: { type: "value", axisLabel: { color: "#8a94a6", fontSize: 11 }, splitLine: { lineStyle: { type: "dashed", color: "#e7edf1" } } },
  series: [
    { name: "来访人次", type: "line", smooth: false, symbolSize: 5, data: props.data.trend.map(item => item.visits) },
    { name: "去重患者", type: "line", smooth: false, symbolSize: 5, data: props.data.trend.map(item => item.uniquePatients) }
  ]
}));
</script>

<style scoped lang="scss">
.overview-view { display: grid; gap: 14px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.metric-card, .attention-row { border: 0; background: transparent; text-align: left; cursor: pointer; }
.metric-card { display: grid; gap: 7px; padding: 16px; background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.metric-card span, .metric-card small { color: var(--el-text-color-secondary); font-size: 12px; }
.metric-card strong { font-size: 30px; line-height: 1; color: var(--el-text-color-primary); }
.metric-card.is-primary { border-top: 3px solid #176b87; }.metric-card.is-info { border-top: 3px solid #6b9db0; }.metric-card.is-success { border-top: 3px solid #8bbf9f; }.metric-card.is-danger { border-top: 3px solid #c96b62; }.metric-card.is-danger strong { color: #b94d45; }
.overview-grid { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(280px, .85fr); gap: 14px; }.panel { padding: 16px; background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }.panel-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 12px; }.panel-head h3 { margin: 0; font-size: 15px; }.panel-head p { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 12px; }.trend-chart { width: 100%; height: 290px; }.attention-panel { display: grid; align-content: start; gap: 8px; }.attention-row { display: grid; grid-template-columns: 1fr auto auto; gap: 10px; align-items: center; width: 100%; padding: 14px 10px; border-bottom: 1px solid var(--el-border-color-lighter); color: var(--el-text-color-regular); }.attention-row strong { font-size: 20px; color: var(--el-text-color-primary); }.attention-row .el-icon { color: var(--el-text-color-secondary); }.attention-row.is-danger strong { color: #b94d45; }.attention-row.is-warning strong { color: #b7791f; }.department-list { display: grid; gap: 10px; }.department-row { display: grid; grid-template-columns: 120px minmax(80px, 1fr) 52px 90px; gap: 10px; align-items: center; font-size: 12px; }.department-row i { display: block; height: 7px; background: var(--el-fill-color-light); border-radius: 4px; overflow: hidden; }.department-row i b { display: block; height: 100%; background: #6b9db0; border-radius: inherit; }.department-row strong { text-align: right; }.department-row small { color: var(--el-text-color-secondary); }
@media (max-width: 900px) { .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.overview-grid { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .metric-grid { grid-template-columns: 1fr 1fr; }.department-row { grid-template-columns: 86px minmax(50px, 1fr) 42px; }.department-row small { display: none; } }
</style>

