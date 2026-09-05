<template>
  <section class="address-analysis">
    <header class="analysis-head">
      <div>
        <strong>来访患者住址分布</strong>
        <small>数据来源：患者收费信息 · 固始县周边乡镇对照 · 样本 {{ patients.length }} 人</small>
      </div>
      <div class="summary-chips">
        <span class="chip"
          >覆盖乡镇 <b>{{ coverageCount }}</b></span
        >
        <span class="chip"
          >本地占比 <b>{{ localRatioText }}</b></span
        >
        <span v-if="topTownship" class="chip"
          >TOP1 <b>{{ topTownship.name }} {{ topTownship.count }} 人</b></span
        >
      </div>
    </header>

    <el-empty v-if="!loading && !patients.length" description="暂无患者收费信息，无法分析住址分布" :image-size="56" />
    <template v-else>
      <div class="chart-row">
        <div class="chart-block">
          <span class="chart-title">本地构成占比</span>
          <VChart class="chart donut" :option="donutOption" autoresize />
        </div>
        <div class="chart-block">
          <span class="chart-title">乡镇来访排行（迁移分析）</span>
          <VChart class="chart bars" :option="barOption" autoresize />
        </div>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { BarChart, PieChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { use, graphic } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import VChart from "vue-echarts";
import type { EChartsOption } from "echarts";
import { getBillingPatientsApi, type BillingPatientInfo } from "@/api/modules/clinic/billing";
import { useGlobalStore } from "@/stores/modules/global";

use([CanvasRenderer, PieChart, BarChart, GridComponent, TooltipComponent, LegendComponent]);

/** 固始县乡镇对照表（可扩展）：按长度降序含匹配，先长后短避免"陈集/陈淋子"类误配。 */
const GUSHI_TOWNSHIPS = [
  "陈淋子",
  "郭陆滩",
  "胡族铺",
  "祖师庙",
  "张广庙",
  "沙河铺",
  "泉河铺",
  "分水亭",
  "柳树店",
  "石佛店",
  "马堽集",
  "草庙集",
  "南大桥",
  "黎集",
  "往流",
  "方集",
  "武庙集",
  "陈集",
  "蒋集",
  "汪棚",
  "段集",
  "徐集",
  "洪埠",
  "杨集",
  "观堂",
  "李店",
  "张老埠",
  "赵岗",
  "丰港",
  "马堽"
];
const URBAN_STREETS = ["蓼城", "秀水", "番城"];
const BAR_LIMIT = 12;

const globalStore = useGlobalStore();
const isDark = computed(() => globalStore.isDark);

const patients = ref<BillingPatientInfo[]>([]);
const loading = ref(false);

const classifyAddress = (
  raw: string
): { bucket: "township" | "urban" | "gushi-other" | "outside" | "unknown"; township: string } => {
  const address = String(raw || "").replace(/\s+/g, "");
  if (!address) return { bucket: "unknown", township: "" };
  const township = [...GUSHI_TOWNSHIPS].sort((a, b) => b.length - a.length).find(name => address.includes(name));
  if (township) {
    return { bucket: URBAN_STREETS.includes(township) ? "urban" : "township", township };
  }
  if (/(城区|城关|县城|产业集聚区)/.test(address)) return { bucket: "urban", township: "城区" };
  if (address.includes("固始")) return { bucket: "gushi-other", township: "固始县其他" };
  return { bucket: "outside", township: "县外" };
};

const distribution = computed(() => {
  const townshipCounts = new Map<string, number>();
  let urban = 0;
  let gushiOther = 0;
  let outside = 0;
  let unknown = 0;
  patients.value.forEach(patient => {
    const result = classifyAddress(patient.address);
    if (result.bucket === "township") townshipCounts.set(result.township, (townshipCounts.get(result.township) || 0) + 1);
    else if (result.bucket === "urban") urban += 1;
    else if (result.bucket === "gushi-other") gushiOther += 1;
    else if (result.bucket === "outside") outside += 1;
    else unknown += 1;
  });
  const townshipTotal = [...townshipCounts.values()].reduce((sum, count) => sum + count, 0);
  return { townshipCounts, townshipTotal, urban, gushiOther, outside, unknown };
});

const townshipRanking = computed(() =>
  [...distribution.value.townshipCounts.entries()].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count)
);
const coverageCount = computed(() => townshipRanking.value.length);
const topTownship = computed(() => townshipRanking.value[0]);
const localRatioText = computed(() => {
  const total = patients.value.length;
  if (!total) return "—";
  return `${Math.round(((distribution.value.urban + distribution.value.townshipTotal) / total) * 100)}%`;
});

const chartPalette = computed(() => ({
  text: isDark.value ? "#cbd5e1" : "#475569",
  label: isDark.value ? "#e5eaf1" : "#1e293b",
  split: isDark.value ? "#334155" : "#e2e8f0",
  tooltipBg: isDark.value ? "#1f2937" : "#ffffff",
  tooltipBorder: isDark.value ? "#374151" : "#e2e8f0",
  maskBorder: isDark.value ? "#111827" : "#ffffff",
  unknown: isDark.value ? "#374151" : "#cbd5e1"
}));

const donutOption = computed<EChartsOption>(() => {
  const d = distribution.value;
  const palette = chartPalette.value;
  const total = patients.value.length;
  const localRatio = total ? Math.round(((d.urban + d.townshipTotal) / total) * 100) : 0;
  return {
    tooltip: {
      trigger: "item",
      formatter: "{b}：{c} 人（{d}%）",
      backgroundColor: palette.tooltipBg,
      borderColor: palette.tooltipBorder,
      textStyle: { color: palette.label }
    },
    legend: { bottom: 0, icon: "circle", textStyle: { color: palette.text, fontSize: 12 } },
    title: {
      text: `${localRatio}%`,
      subtext: "本地占比",
      left: "center",
      top: "38%",
      textStyle: { fontSize: 24, color: isDark.value ? "#f1f5f9" : "#0f766e" },
      subtextStyle: { color: palette.text, fontSize: 12 }
    },
    series: [
      {
        type: "pie",
        radius: ["52%", "72%"],
        center: ["50%", "44%"],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 6, borderColor: palette.maskBorder, borderWidth: 2 },
        label: { show: false },
        data: [
          { name: "周边乡镇", value: d.townshipTotal, itemStyle: { color: "#0f766e" } },
          { name: "城区", value: d.urban, itemStyle: { color: "#14b8a6" } },
          { name: "县外", value: d.outside, itemStyle: { color: "#94a3b8" } },
          { name: "固始县其他", value: d.gushiOther, itemStyle: { color: "#d97706" } },
          { name: "未登记地址", value: d.unknown, itemStyle: { color: palette.unknown } }
        ].filter(item => item.value > 0),
        animationDuration: 800
      }
    ]
  };
});

const barOption = computed<EChartsOption>(() => {
  const ranking = townshipRanking.value;
  const top = ranking.slice(0, BAR_LIMIT);
  const restCount = ranking.slice(BAR_LIMIT).reduce((sum, item) => sum + item.count, 0);
  const rows = [...top];
  if (restCount > 0) rows.push({ name: `其他乡镇（${ranking.length - BAR_LIMIT} 个）`, count: restCount });
  const palette = chartPalette.value;
  return {
    tooltip: {
      trigger: "axis",
      axisPointer: { type: "shadow" },
      backgroundColor: palette.tooltipBg,
      borderColor: palette.tooltipBorder,
      textStyle: { color: palette.label }
    },
    grid: { left: 8, right: 44, top: 8, bottom: 8, containLabel: true },
    xAxis: {
      type: "value",
      splitLine: { lineStyle: { color: palette.split } },
      axisLabel: { color: palette.text, fontSize: 12 }
    },
    yAxis: {
      type: "category",
      inverse: true,
      data: rows.map(row => row.name),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: palette.split } },
      axisLabel: { color: palette.label, fontSize: 12 }
    },
    series: [
      {
        type: "bar",
        data: rows.map(row => row.count),
        barMaxWidth: 16,
        itemStyle: {
          borderRadius: [0, 8, 8, 0],
          color: new graphic.LinearGradient(0, 0, 1, 0, [
            { offset: 0, color: "#14b8a6" },
            { offset: 1, color: "#0f766e" }
          ])
        },
        label: { show: true, position: "right", color: palette.text, fontSize: 12 },
        animationDuration: 700
      }
    ]
  };
});

const loadPatients = async () => {
  loading.value = true;
  try {
    const { data } = await getBillingPatientsApi("");
    patients.value = data.patients || [];
  } catch (error) {
    ElMessage.error((error as Error).message || "患者收费信息加载失败");
  } finally {
    loading.value = false;
  }
};

onMounted(loadPatients);
</script>

<style scoped lang="scss">
.address-analysis {
  display: grid;
  gap: 12px;
}
.analysis-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  > div:first-child {
    display: grid;
    gap: 3px;
  }
  strong {
    font-size: 14px;
  }
  small {
    color: var(--el-text-color-secondary);
  }
}
.summary-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  .chip {
    padding: 4px 10px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    background: var(--el-fill-color-light);
    border-radius: 999px;
    b {
      font-variant-numeric: tabular-nums;
      color: var(--el-color-primary);
    }
  }
}
.chart-row {
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 7fr);
  gap: 16px;
}
.chart-block {
  display: grid;
  gap: 6px;
  align-content: start;
  .chart-title {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
}
.chart {
  width: 100%;
}
.donut {
  height: 280px;
}
.bars {
  height: 320px;
}

@media (width <= 1080px) {
  .chart-row {
    grid-template-columns: 1fr;
  }
}
</style>
