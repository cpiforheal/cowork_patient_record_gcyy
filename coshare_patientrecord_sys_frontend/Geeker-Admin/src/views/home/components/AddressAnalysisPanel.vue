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
      <!-- 迁移地图：近似乡镇分块（hover 凸起强调） + 涟漪散点 + 迁移光线 -->
      <div class="chart-block map-block">
        <span class="chart-title">患者来源迁移地图（乡镇 → 本院，hover 查看明细，滚轮缩放）</span>
        <div v-if="mapLoading" class="map-loading" v-loading="true" element-loading-text="地图数据加载中…" />
        <VChart v-else-if="mapRegistered" class="chart geo" :option="geoOption" autoresize />
        <el-empty v-else description="地图边界数据加载失败" :image-size="48" />
        <small class="map-note">
          乡镇分块为基于驻地的近似分区（无官方乡镇边界数据）；县外、固始县其他与未登记地址不计入地图。点大小与光线数量随来访人数变化。
        </small>
      </div>

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
import { BarChart, EffectScatterChart, LinesChart, MapChart, PieChart } from "echarts/charts";
import { GeoComponent, GridComponent, LegendComponent, TooltipComponent, VisualMapComponent } from "echarts/components";
import { registerMap, use, graphic } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import VChart from "vue-echarts";
import type { EChartsOption } from "echarts";
import { Delaunay } from "d3-delaunay";
import polygonClipping from "polygon-clipping";
import { getBillingPatientsApi, type BillingPatientInfo } from "@/api/modules/clinic/billing";
import { useGlobalStore } from "@/stores/modules/global";
import gushiCountyGeo from "@/assets/geo/gushi-county.json";

use([
  CanvasRenderer,
  PieChart,
  BarChart,
  MapChart,
  EffectScatterChart,
  LinesChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  GeoComponent,
  VisualMapComponent
]);

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
/** 本院（城区）锚点 */
const HOSPITAL_COORD: [number, number] = [115.65, 32.17];
/** 乡镇驻地近似经纬度（仅用于分布可视化，非精确行政边界） */
const TOWNSHIP_COORDS: Record<string, [number, number]> = {
  城区: HOSPITAL_COORD,
  沙河铺: [115.76, 32.18],
  泉河铺: [115.83, 32.22],
  分水亭: [115.83, 32.13],
  蒋集: [115.9, 32.24],
  陈集: [115.95, 32.35],
  观堂: [115.85, 32.38],
  徐集: [116.05, 32.32],
  张广庙: [115.97, 32.18],
  石佛店: [116.02, 32.12],
  柳树店: [115.93, 32.05],
  往流: [115.95, 32.45],
  丰港: [115.75, 32.4],
  洪埠: [115.62, 32.3],
  李店: [115.55, 32.35],
  杨集: [115.48, 32.3],
  胡族铺: [115.45, 32.15],
  马堽集: [115.42, 32.02],
  汪棚: [115.55, 32.05],
  赵岗: [115.65, 32.0],
  草庙集: [115.48, 31.95],
  南大桥: [115.68, 31.98],
  方集: [115.72, 31.9],
  段集: [115.8, 31.85],
  祖师庙: [115.85, 31.78],
  武庙集: [115.92, 31.75],
  张老埠: [115.85, 31.95],
  黎集: [115.95, 31.9],
  陈淋子: [116.05, 31.8]
};

const globalStore = useGlobalStore();
const isDark = computed(() => globalStore.isDark);

const patients = ref<BillingPatientInfo[]>([]);
const loading = ref(false);
const mapLoading = ref(false);
const mapRegistered = ref(false);

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
  unknown: isDark.value ? "#374151" : "#cbd5e1",
  areaBase: isDark.value ? "#1b2a2b" : "#e8f6f2",
  areaBorder: isDark.value ? "#2f4a48" : "#9fd6cb"
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

// ---------- 迁移地图：Voronoi 近似分块 + choropleth + 涟漪散点 + 迁移光线 ----------
const countyGeo = ref<unknown>(null);

const mapTotalByRegion = computed(() => {
  const d = distribution.value;
  const map = new Map<string, number>();
  d.townshipCounts.forEach((count, name) => map.set(name, count));
  map.set("城区", d.urban);
  return map;
});

const maxRegionCount = computed(() => Math.max(1, ...mapTotalByRegion.value.values()));

const localCoord = (name: string): [number, number] => TOWNSHIP_COORDS[name] || HOSPITAL_COORD;

/** 以乡镇驻点为种子生成 Voronoi 分块，再用固始县真实轮廓裁剪，得到近似乡镇分区 GeoJSON。 */
const buildTownshipMapFeatureCollection = () => {
  const county = countyGeo.value as { features: Array<{ geometry: { coordinates: number[][][][] } }> } | null;
  if (!county?.features?.length) return null;
  const countyMulti = county.features[0].geometry.coordinates;
  const flat: number[][] = [];
  countyMulti.forEach(polygon =>
    polygon.forEach(ring =>
      ring.forEach(point => {
        flat.push(point);
      })
    )
  );
  const xs = flat.map(point => point[0]);
  const ys = flat.map(point => point[1]);
  const bbox: [number, number, number, number] = [
    Math.min(...xs) - 0.02,
    Math.min(...ys) - 0.02,
    Math.max(...xs) + 0.02,
    Math.max(...ys) + 0.02
  ];

  const seeds = Object.keys(TOWNSHIP_COORDS).map(name => ({ name, coord: TOWNSHIP_COORDS[name] }));
  const delaunay = Delaunay.from(
    seeds,
    seed => seed.coord[0],
    seed => seed.coord[1]
  );
  const voronoi = delaunay.voronoi(bbox);

  const features = seeds.map((seed, index) => {
    const cell = voronoi.cellPolygon(index) as Array<[number, number]> | null;
    let geometry: { type: "MultiPolygon"; coordinates: number[][][][] } | null = null;
    if (cell && cell.length >= 4) {
      const ring = cell.slice(0, -1).map(point => [point[0], point[1]]);
      ring.push(ring[0]);
      // polygon-clipping 的 Geom 类型与裸坐标数组不兼容，此处统一按未知类型桥接
      const clip = polygonClipping.intersection as (a: unknown, b: unknown) => number[][][][] | null;
      const clipped = clip(countyMulti, [ring]);
      if (clipped && clipped.length) {
        geometry = { type: "MultiPolygon", coordinates: clipped };
      }
    }
    return {
      type: "Feature" as const,
      properties: { name: seed.name },
      geometry: geometry ?? { type: "MultiPolygon", coordinates: [] as number[][][][] }
    };
  });
  return { type: "FeatureCollection" as const, features };
};

const geoOption = computed(() => {
  if (!mapRegistered.value) return {} as EChartsOption;
  const palette = chartPalette.value;
  const maxCount = maxRegionCount.value;
  const regionData = [...mapTotalByRegion.value.entries()].map(([name, value]) => ({ name, value }));
  const scatterData = [...mapTotalByRegion.value.entries()]
    .filter(([name, value]) => value > 0 && TOWNSHIP_COORDS[name])
    .map(([name, value]) => ({
      name,
      value: [...localCoord(name), value],
      lineStyle: { width: 0.8 + (value / maxCount) * 2.4 }
    }));
  const linesData = scatterData
    .filter(item => item.name !== "城区" && item.value[2] > 0)
    .map(item => ({
      coords: [[...item.value.slice(0, 2)] as number[], HOSPITAL_COORD],
      value: item.value[2],
      lineStyle: { width: 0.6 + (item.value[2] / maxCount) * 2.6, curveness: 0.18 }
    }));
  return {
    tooltip: {
      trigger: "item",
      backgroundColor: palette.tooltipBg,
      borderColor: palette.tooltipBorder,
      textStyle: { color: palette.label },
      formatter: (params: any) => {
        const value = Number(params.value?.[2] ?? params.value ?? 0);
        const total = distribution.value.urban + distribution.value.townshipTotal;
        const ratio = total ? Math.round((value / total) * 100) : 0;
        return `${params.name}<br/>来访患者：${value} 人<br/>占本地样本：${ratio}%`;
      }
    },
    geo: {
      map: "gushi-townships",
      roam: true,
      zoom: 1.05,
      scaleLimit: { min: 0.8, max: 4 },
      label: { show: true, color: palette.label, fontSize: 10 },
      itemStyle: { areaColor: palette.areaBase, borderColor: palette.areaBorder, borderWidth: 1 },
      emphasis: {
        label: { show: true, color: isDark.value ? "#f8fafc" : "#14532d", fontWeight: 700 as const },
        itemStyle: {
          // 凸起浮出的高度差错觉：亮色填充 + 下沉阴影
          areaColor: isDark.value ? "#0d9488" : "#5eead4",
          shadowBlur: 20,
          shadowOffsetY: 12,
          shadowColor: isDark.value ? "rgba(13, 148, 136, 0.5)" : "rgba(15, 118, 110, 0.45)"
        }
      },
      select: { disabled: true }
    },
    visualMap: {
      type: "continuous",
      min: 0,
      max: maxCount,
      left: 12,
      bottom: 12,
      text: ["来访多", "来访少"],
      calculable: false,
      inRange: { color: isDark.value ? ["#134e4a", "#0f766e", "#2dd4bf"] : ["#ccfbf1", "#14b8a6", "#0f766e"] },
      textStyle: { color: palette.text, fontSize: 11 }
    },
    series: [
      {
        type: "map",
        geoIndex: 0,
        data: regionData,
        animationDuration: 700
      },
      {
        type: "lines",
        coordinateSystem: "geo",
        zlevel: 2,
        data: linesData,
        lineStyle: { color: isDark.value ? "#5eead4" : "#0d9488", opacity: 0.55, curveness: 0.18 },
        effect: {
          show: true,
          period: 4.5,
          trailLength: 0.28,
          symbol: "arrow",
          symbolSize: 6,
          color: isDark.value ? "#99f6e4" : "#ccfbf1"
        }
      },
      {
        type: "effectScatter",
        coordinateSystem: "geo",
        zlevel: 3,
        data: scatterData.map(item => ({ name: item.name, value: item.value })),
        symbolSize: (value: unknown) => 10 + (Number((value as number[])?.[2] ?? 0) / maxCount) * 22,
        rippleEffect: { brushType: "stroke", scale: 2.4 },
        label: { show: false },
        itemStyle: { color: isDark.value ? "#5eead4" : "#0d9488" },
        animationDuration: 700
      },
      {
        type: "effectScatter",
        coordinateSystem: "geo",
        zlevel: 4,
        data: [{ name: "本院", value: [HOSPITAL_COORD[0], HOSPITAL_COORD[1], distribution.value.urban] }],
        symbolSize: 14,
        rippleEffect: { brushType: "stroke", scale: 3.2, numberRipples: 2 },
        label: { show: true, position: "top", formatter: "本院", color: palette.label, fontSize: 12, fontWeight: 700 as const },
        itemStyle: { color: "#f59e0b" },
        animationDuration: 700
      }
    ]
  };
});

const registerTownshipMap = () => {
  try {
    countyGeo.value = gushiCountyGeo;
    const featureCollection = buildTownshipMapFeatureCollection();
    if (!featureCollection) throw new Error("乡镇分区构建失败");
    registerMap("gushi-townships", featureCollection as never);
    mapRegistered.value = true;
  } catch (error) {
    ElMessage.warning(`迁移地图加载失败：${(error as Error).message}`);
  } finally {
    mapLoading.value = false;
  }
};

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

onMounted(() => {
  void loadPatients();
  registerTownshipMap();
});
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
.map-block {
  .map-loading {
    height: 440px;
  }
  .map-note {
    font-size: 11px;
    line-height: 1.6;
    color: var(--el-text-color-placeholder);
  }
}
.chart {
  width: 100%;
}
.geo {
  height: 460px;
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
