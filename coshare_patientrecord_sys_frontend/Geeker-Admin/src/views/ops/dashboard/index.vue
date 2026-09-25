<template>
  <div class="ops-page">
    <header class="ops-head">
      <div>
        <span class="ops-eyebrow">运营总览</span>
        <h2>运营数据看板</h2>
        <p>
          区间 {{ result?.from || "—" }} ~ {{ result?.to || "—" }} · 数据更新
          {{ result?.generatedAt || "—" }} · 全部为计数类聚合，不含患者隐私字段
        </p>
      </div>
      <div class="ops-head-actions">
        <el-radio-group v-model="months" size="small" @change="load">
          <el-radio-button :value="6">近 6 月</el-radio-button>
          <el-radio-button :value="12">近 12 月</el-radio-button>
          <el-radio-button :value="24">近 24 月</el-radio-button>
        </el-radio-group>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <!-- 段 1 · 关键数字：环比差异摆在最显眼处 -->
    <section class="ops-kpis">
      <article class="ops-kpi is-primary">
        <span class="ops-kpi-label">本月来访量</span>
        <div class="ops-kpi-value">
          <b>{{ kpi?.visitsThisMonth?.current ?? 0 }}</b>
          <em>人次</em>
        </div>
        <div class="ops-kpi-delta" :class="deltaClass(kpi?.visitsThisMonth?.deltaRate)">
          <span class="ops-delta-arrow">{{ deltaArrow(kpi?.visitsThisMonth?.deltaRate) }}</span>
          <span>{{ deltaText(kpi?.visitsThisMonth?.deltaRate) }}</span>
          <small>较上月 {{ kpi?.visitsThisMonth?.previous ?? 0 }}</small>
        </div>
      </article>

      <article class="ops-kpi">
        <span class="ops-kpi-label">本月新增患者</span>
        <div class="ops-kpi-value">
          <b>{{ kpi?.newCasesThisMonth ?? 0 }}</b>
          <em>人</em>
        </div>
        <div class="ops-kpi-foot">累计患者 {{ kpi?.patientCases ?? 0 }} 人</div>
      </article>

      <article class="ops-kpi is-success">
        <span class="ops-kpi-label">随访回院率</span>
        <div class="ops-kpi-value">
          <b>{{ kpi?.followUpArrivalRate ?? 0 }}</b>
          <em>%</em>
        </div>
        <div class="ops-kpi-foot">
          应随访 {{ kpi?.followUpDue ?? 0 }} · 已回院 {{ kpi?.followUpArrived ?? 0 }}
        </div>
      </article>

      <article class="ops-kpi is-danger">
        <span class="ops-kpi-label">逾期未回院</span>
        <div class="ops-kpi-value">
          <b>{{ kpi?.followUpOverdue ?? 0 }}</b>
          <em>人</em>
        </div>
        <div class="ops-kpi-foot">需要优先联系</div>
      </article>
    </section>

    <!-- 段 2 · 来访量趋势（主视觉）+ 上月对比条形 -->
    <section class="ops-card">
      <header class="ops-card-head">
        <h3>来访量趋势</h3>
        <small>按月统计，已排除取消就诊；虚线为上一个月基准</small>
      </header>
      <div v-if="trendHasData" class="ops-chart-block">
        <VChart class="ops-chart is-tall" :option="trendOption" autoresize />
      </div>
      <el-empty v-else description="暂无来访量数据" :image-size="70" />

      <div v-if="trendHasData" class="ops-compare">
        <div class="ops-compare-head">
          <strong>本月 vs 上月</strong>
          <span :class="deltaClass(kpi?.visitsThisMonth?.deltaRate)">
            {{ deltaArrow(kpi?.visitsThisMonth?.deltaRate) }} {{ deltaText(kpi?.visitsThisMonth?.deltaRate) }}
          </span>
        </div>
        <VChart class="ops-chart is-short" :option="compareOption" autoresize />
      </div>
    </section>

    <!-- 段 3 · 随访闭环漏斗 + 就诊状态分布 -->
    <section class="ops-grid-2">
      <article class="ops-card">
        <header class="ops-card-head">
          <h3>随访闭环</h3>
          <small>应随访、已回院、已触达为三个独立事实（回院未必登记过联系），此处并列展示绝对量</small>
        </header>
        <div v-if="(followUp?.dueTotal ?? 0) > 0" class="ops-chart-block">
          <VChart class="ops-chart" :option="funnelOption" autoresize />
        </div>
        <el-empty v-else description="暂无已排期的随访节点" :image-size="70" />
        <div v-if="(followUp?.dueTotal ?? 0) > 0" class="ops-funnel-facts">
          <span class="is-danger">逾期 <b>{{ followUp?.overdue ?? 0 }}</b></span>
          <span class="is-warning">今日应访 <b>{{ followUp?.dueToday ?? 0 }}</b></span>
          <span>未到期 <b>{{ followUp?.upcoming ?? 0 }}</b></span>
          <span>未排期 <b>{{ followUp?.notScheduled ?? 0 }}</b></span>
        </div>
      </article>

      <article class="ops-card">
        <header class="ops-card-head">
          <h3>就诊状态分布</h3>
          <small>全部就诊记录按当前流程状态归类</small>
        </header>
        <div v-if="(statusDistribution?.length ?? 0) > 0" class="ops-chart-block">
          <VChart class="ops-chart" :option="statusOption" autoresize />
        </div>
        <el-empty v-else description="暂无就诊记录" :image-size="70" />
      </article>
    </section>

    <!-- 段 4 · 科室来访量 -->
    <section class="ops-card">
      <header class="ops-card-head">
        <h3>科室来访量</h3>
        <small>按就诊归属科室统计，取前 12</small>
      </header>
      <div v-if="(departments?.length ?? 0) > 0" class="ops-chart-block">
        <VChart class="ops-chart" :option="departmentOption" autoresize />
      </div>
      <el-empty v-else description="暂无科室数据" :image-size="70" />
    </section>
  </div>
</template>

<script setup lang="ts" name="opsDashboard">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import { BarChart, LineChart, PieChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import VChart from "vue-echarts";
import type { EChartsOption } from "echarts";
import { loadOpsDashboardApi, type OpsDashboardResult } from "@/api/modules/clinic/opsDashboard";

use([CanvasRenderer, LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent]);

const loading = ref(false);
const months = ref(12);
const result = ref<OpsDashboardResult | null>(null);

const kpi = computed(() => result.value?.kpi);
const trend = computed(() => result.value?.trend || []);
const followUp = computed(() => result.value?.followUp);
const statusDistribution = computed(() => result.value?.statusDistribution || []);
const departments = computed(() => result.value?.departments || []);

const trendHasData = computed(() => trend.value.some(point => point.visits > 0));

const load = async () => {
  loading.value = true;
  try {
    const { data } = await loadOpsDashboardApi(months.value);
    result.value = data;
  } catch (error: any) {
    ElMessage.error(error?.message || "运营数据加载失败");
  } finally {
    loading.value = false;
  }
};

onMounted(() => void load());

// ---------- 环比展示 ----------

/** 上月为 0 时不做除法，返回 null，这里统一显示"—" */
const deltaArrow = (rate: number | null | undefined) => {
  if (rate === null || rate === undefined) return "—";
  if (rate > 0) return "▲";
  if (rate < 0) return "▼";
  return "＝";
};
const deltaText = (rate: number | null | undefined) => {
  if (rate === null || rate === undefined) return "无对比基准";
  const sign = rate > 0 ? "+" : "";
  return `${sign}${rate}%`;
};
const deltaClass = (rate: number | null | undefined) => {
  if (rate === null || rate === undefined) return "is-flat";
  if (rate > 0) return "is-up";
  if (rate < 0) return "is-down";
  return "is-flat";
};

// ---------- 图表 ----------

const AXIS_LABEL = { color: "#909399", fontSize: 11 };
const SPLIT_LINE = { lineStyle: { color: "#ebeef5", type: "dashed" as const } };

const trendOption = computed<EChartsOption>(() => {
  const points = trend.value;
  const labels = points.map(point => point.month.slice(2));
  const values = points.map(point => point.visits);
  // 上月基准虚线：取最后一个月的上一月值，做一条水平参考线
  const last = points[points.length - 1];
  const prev = points[points.length - 2];
  return {
    grid: { left: 46, right: 18, top: 28, bottom: 28 },
    tooltip: { trigger: "axis" },
    xAxis: { type: "category", data: labels, axisLabel: AXIS_LABEL, axisLine: { lineStyle: { color: "#dcdfe6" } } },
    yAxis: { type: "value", axisLabel: AXIS_LABEL, splitLine: SPLIT_LINE },
    series: [
      {
        name: "来访量",
        type: "line",
        smooth: true,
        symbolSize: 7,
        data: values,
        itemStyle: { color: "#0bb1ea" },
        lineStyle: { width: 2.5, color: "#0bb1ea" },
        areaStyle: {
          color: {
            type: "linear",
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: "rgba(11,177,234,0.28)" },
              { offset: 1, color: "rgba(11,177,234,0.02)" }
            ]
          }
        },
        // 上月基准：仅当有上一个月时画虚线参考
        markLine: prev
          ? {
              silent: true,
              symbol: "none",
              lineStyle: { color: "#f0a020", type: "dashed", width: 1.5 },
              label: { formatter: `上月 ${prev.visits}`, color: "#f0a020", fontSize: 11 },
              data: [{ yAxis: prev.visits }]
            }
          : undefined
      }
    ]
  };
});

const compareOption = computed<EChartsOption>(() => {
  const current = kpi.value?.visitsThisMonth?.current ?? 0;
  const previous = kpi.value?.visitsThisMonth?.previous ?? 0;
  return {
    grid: { left: 78, right: 40, top: 12, bottom: 12 },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    xAxis: { type: "value", axisLabel: AXIS_LABEL, splitLine: SPLIT_LINE, max: Math.max(current, previous, 1) },
    yAxis: { type: "category", data: ["上月", "本月"], axisLabel: AXIS_LABEL, axisLine: { show: false }, axisTick: { show: false } },
    series: [
      {
        type: "bar",
        barWidth: 18,
        data: [
          { value: previous, itemStyle: { color: "#c0c4cc" } },
          { value: current, itemStyle: { color: current >= previous ? "#0bb1ea" : "#e6a23c" } }
        ],
        label: { show: true, position: "right", formatter: "{c} 人次", fontSize: 12, color: "#303133" }
      }
    ]
  };
});

const funnelOption = computed<EChartsOption>(() => {
  // 不画成"应随访→已触达→已回院"的漏斗。
  // "已触达"（登记过联系）与"已回院"是两个独立事实——患者可能直接回院而没登记联系，
  // 那样漏斗会出现后级大于前级的矛盾图。这里改为并列的绝对量，语义不重叠。
  const stages = [
    { name: "应随访", value: followUp.value?.dueTotal ?? 0, color: "#909399" },
    { name: "已回院", value: followUp.value?.arrived ?? 0, color: "#67c23a" },
    { name: "已触达", value: followUp.value?.reached ?? 0, color: "#e6a23c" }
  ];
  const max = Math.max(...stages.map(stage => stage.value), 1);
  return {
    grid: { left: 70, right: 96, top: 16, bottom: 16 },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    xAxis: { type: "value", max, axisLabel: AXIS_LABEL, splitLine: SPLIT_LINE },
    yAxis: {
      type: "category",
      data: stages.map(stage => stage.name).reverse(),
      axisLabel: AXIS_LABEL,
      axisLine: { show: false },
      axisTick: { show: false }
    },
    series: [
      {
        type: "bar",
        barWidth: 22,
        data: stages
          .map(stage => ({
            value: stage.value,
            itemStyle: { color: stage.color, borderRadius: [0, 4, 4, 0] },
            label: {
              show: true,
              position: "right",
              fontSize: 12,
              color: "#303133",
              formatter: () => `${stage.value}（${Math.round((stage.value * 100) / max)}%）`
            }
          }))
          .reverse()
      }
    ]
  };
});

const statusOption = computed<EChartsOption>(() => {
  const palette = ["#0bb1ea", "#67c23a", "#e6a23c", "#909399", "#f56c6c", "#b37feb"];
  return {
    tooltip: { trigger: "item", formatter: "{b}<br/>{c} 例（{d}%）" },
    legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 11, color: "#606266" } },
    series: [
      {
        type: "pie",
        radius: ["46%", "68%"],
        center: ["50%", "44%"],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: "#fff", borderWidth: 2 },
        label: { show: false },
        data: statusDistribution.value.map((slice, index) => ({
          name: slice.label,
          value: slice.count,
          itemStyle: { color: palette[index % palette.length] }
        }))
      }
    ]
  };
});

const departmentOption = computed<EChartsOption>(() => {
  const rows = departments.value;
  return {
    grid: { left: 96, right: 48, top: 12, bottom: 16 },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    xAxis: { type: "value", axisLabel: AXIS_LABEL, splitLine: SPLIT_LINE },
    yAxis: {
      type: "category",
      data: rows.map(row => row.department).reverse(),
      axisLabel: AXIS_LABEL,
      axisLine: { show: false },
      axisTick: { show: false }
    },
    series: [
      {
        type: "bar",
        barWidth: 16,
        data: rows
          .map(row => ({ value: row.count, itemStyle: { color: "#5b8ff9", borderRadius: [0, 4, 4, 0] } }))
          .reverse(),
        label: { show: true, position: "right", fontSize: 11, color: "#303133" }
      }
    ]
  };
});
</script>

<style scoped lang="scss">
.ops-page {
  display: grid;
  gap: 14px;
  max-width: 1360px;
  padding: 16px;
  margin: 0 auto;
}

.ops-head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
  justify-content: space-between;

  .ops-eyebrow {
    font-size: 11px;
    font-weight: 600;
    color: var(--el-color-primary);
    letter-spacing: 0.08em;
  }

  h2 {
    margin: 4px 0 0;
    font-size: 20px;
    font-weight: 700;
    color: var(--el-text-color-primary);
  }

  p {
    margin: 4px 0 0;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .ops-head-actions {
    display: flex;
    gap: 8px;
    align-items: center;
  }
}

/* ---------- KPI ---------- */
.ops-kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 12px;
}

.ops-kpi {
  padding: 14px 16px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;

  &.is-primary {
    border-color: color-mix(in srgb, var(--el-color-primary) 30%, var(--el-border-color-lighter));
    background: color-mix(in srgb, var(--el-color-primary) 4%, var(--el-bg-color));
  }
  &.is-success {
    border-color: color-mix(in srgb, var(--el-color-success) 28%, var(--el-border-color-lighter));
  }
  &.is-danger {
    border-color: color-mix(in srgb, var(--el-color-danger) 28%, var(--el-border-color-lighter));
  }

  .ops-kpi-label {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .ops-kpi-value {
    display: flex;
    gap: 4px;
    align-items: baseline;
    margin-top: 6px;

    b {
      font-size: 30px;
      font-weight: 700;
      line-height: 1.1;
      font-variant-numeric: tabular-nums;
      color: var(--el-text-color-primary);
    }

    em {
      font-size: 12px;
      font-style: normal;
      color: var(--el-text-color-secondary);
    }
  }

  .ops-kpi-foot {
    margin-top: 6px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  /* 环比：红绿箭头 + 百分比，差异一眼可见 */
  .ops-kpi-delta {
    display: flex;
    gap: 6px;
    align-items: baseline;
    margin-top: 6px;
    font-size: 13px;
    font-weight: 600;

    .ops-delta-arrow {
      font-size: 12px;
    }

    small {
      margin-left: auto;
      font-size: 11px;
      font-weight: 400;
      color: var(--el-text-color-placeholder);
    }

    &.is-up {
      color: var(--el-color-success);
    }
    &.is-down {
      color: var(--el-color-danger);
    }
    &.is-flat {
      color: var(--el-text-color-secondary);
    }
  }
}

/* ---------- 卡片与图表 ---------- */
.ops-card {
  padding: 14px 16px 16px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}

.ops-card-head {
  margin-bottom: 8px;

  h3 {
    margin: 0;
    font-size: 15px;
    font-weight: 700;
    color: var(--el-text-color-primary);
  }

  small {
    display: block;
    margin-top: 3px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}

.ops-grid-2 {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(380px, 1fr));
  gap: 14px;
}

.ops-chart-block {
  width: 100%;
}

.ops-chart {
  width: 100%;
  height: 240px;

  &.is-tall {
    height: 300px;
  }
  &.is-short {
    height: 130px;
  }
}

.ops-compare {
  padding-top: 10px;
  margin-top: 12px;
  border-top: 1px dashed var(--el-border-color-lighter);

  .ops-compare-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    font-size: 13px;
    color: var(--el-text-color-regular);

    span {
      font-weight: 600;
      &.is-up {
        color: var(--el-color-success);
      }
      &.is-down {
        color: var(--el-color-danger);
      }
      &.is-flat {
        color: var(--el-text-color-secondary);
      }
    }
  }
}

.ops-funnel-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  padding-top: 10px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  border-top: 1px dashed var(--el-border-color-lighter);

  b {
    font-size: 15px;
    font-variant-numeric: tabular-nums;
    color: var(--el-text-color-primary);
  }

  .is-danger b {
    color: var(--el-color-danger);
  }
  .is-warning b {
    color: var(--el-color-warning);
  }
}
</style>
