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
          <VChart class="chart donut" :option="donutOption" autoresize @mouseover="onDonutHover" @globalout="resetDonutCenter" />
        </div>
        <div class="chart-block">
          <span class="chart-title">乡镇来访排行（点击条形下钻 · 迁移分析）</span>
          <VChart class="chart bars" :option="barOption" autoresize @click="onBarClick" />
        </div>
      </div>

      <!-- 二级悬浮窗：乡镇来访患者卡片（横向滚动） -->
      <el-dialog
        v-model="townshipDialogVisible"
        :title="townshipDialogTitle"
        fullscreen
        append-to-body
        destroy-on-close
        class="township-patient-dialog is-fullscreen"
      >
        <p class="township-subtitle">{{ townshipDialogSubtitle }}</p>
        <el-empty v-if="!townshipPatients.length" description="该乡镇暂无来访患者（可能地址未登记或归属县外）" :image-size="56" />
        <div v-else class="township-patient-rows">
          <button
            v-for="card in townshipPatients"
            :key="card.caseId"
            type="button"
            class="patient-row"
            @click="openCourseDialog(card)"
          >
            <span class="row-name">{{ card.name || "未登记姓名" }}</span>
            <span class="row-cell"><label>就诊时间</label>{{ card.visitDate || "—" }}</span>
            <span class="row-cell"><label>手机号</label>{{ card.phone || "—" }}</span>
            <span class="row-cell address"><label>详细住址</label>{{ card.address || "—" }}</span>
            <span class="row-cell"><label>乡镇</label>{{ card.township }}</span>
            <em class="row-more">查看病程 »</em>
          </button>
        </div>
      </el-dialog>

      <!-- 三级弹窗：主要病程信息（复用患者概览 encounter overview 接口） -->
      <el-dialog
        v-model="courseDialogVisible"
        :title="`${coursePatient?.name || '患者'} · 主要病程`"
        fullscreen
        append-to-body
        destroy-on-close
        class="course-dialog is-fullscreen"
      >
        <div v-loading="courseLoading" class="course-body" element-loading-text="病程加载中…">
          <el-alert v-if="courseError" type="warning" :closable="false" show-icon :title="courseError" />
          <template v-else-if="courseOverview">
            <section class="course-sec">
              <span class="course-sec-title">基础信息</span>
              <div class="course-grid">
                <span><label>姓名</label>{{ courseOverview.patient.name || "—" }}</span>
                <span
                  ><label>性别/年龄</label
                  >{{ [courseOverview.patient.gender, courseOverview.patient.age].filter(Boolean).join(" / ") || "—" }}</span
                >
                <span><label>联系电话</label>{{ coursePatient?.phone || courseOverview.patient.phone || "—" }}</span>
                <span><label>就诊日期</label>{{ courseOverview.visit.visitDate || "—" }}</span>
              </div>
            </section>
            <section class="course-sec">
              <span class="course-sec-title">主要病情</span>
              <p><label>主诉</label>{{ courseOverview.clinical.chiefComplaint || "—" }}</p>
              <p><label>现病史</label>{{ courseOverview.clinical.presentIllness || "—" }}</p>
              <p><label>过敏史</label>{{ courseOverview.clinical.allergyHistory || "—" }}</p>
            </section>
            <section class="course-sec">
              <span class="course-sec-title">诊断与治疗</span>
              <p><label>西医诊断</label>{{ courseOverview.clinical.diagnosis.westernPrimary || "—" }}</p>
              <p><label>中医诊断</label>{{ courseOverview.clinical.diagnosis.tcm || "—" }}</p>
              <p><label>治疗路径</label>{{ courseOverview.clinical.treatment.treatmentPath || "—" }}</p>
              <p v-if="courseOverview.clinical.surgery.actualPrimaryOperation">
                <label>手术</label>{{ courseOverview.clinical.surgery.actualPrimaryOperation
                }}{{
                  courseOverview.clinical.surgery.anesthesiaMethod
                    ? `（${courseOverview.clinical.surgery.anesthesiaMethod}）`
                    : ""
                }}
              </p>
            </section>
            <section class="course-sec">
              <span class="course-sec-title">照片资料（点击放大）</span>
              <AttachmentPreviewGallery
                v-if="courseAttachments.length"
                :attachments="courseAttachments"
                compact
                @download="downloadCourseAttachment"
              />
              <p v-else class="course-empty">该就诊暂无照片资料</p>
            </section>
          </template>
        </div>
        <template #footer>
          <el-button @click="courseDialogVisible = false">关闭</el-button>
          <el-button type="primary" :disabled="!coursePatient?.patientId" @click="gotoPatientArchive"> 进入完整档案 </el-button>
        </template>
      </el-dialog>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { BarChart, EffectScatterChart, MapChart, PieChart } from "echarts/charts";
import { GeoComponent, GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { registerMap, use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import VChart from "vue-echarts";
import type { EChartsOption } from "echarts";
import { Delaunay } from "d3-delaunay";
import polygonClipping from "polygon-clipping";
import { getBillingPatientsApi, type BillingPatientInfo } from "@/api/modules/clinic/billing";
import {
  downloadPreAiAttachmentApi,
  getEncounterOverviewApi,
  getPreAiPatientCasesApi,
  getPreAiWorkspaceApi,
  type PreAiAttachment,
  type PreAiEncounterOverview,
  type PreAiPatientCase
} from "@/api/modules/clinic/preAi";
import { useGlobalStore } from "@/stores/modules/global";
import gushiCountyGeo from "@/assets/geo/gushi-county.json";
import { usePatientNavigation } from "@/hooks/usePatientNavigation";
import AttachmentPreviewGallery from "@/views/preAi/encounters/components/AttachmentPreviewGallery.vue";

use([
  CanvasRenderer,
  PieChart,
  BarChart,
  MapChart,
  EffectScatterChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  GeoComponent
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
/** 柱状图逐行配色（相邻行不同色；饱和度与主题协调） */
const BAR_ROW_PALETTE = [
  "#0f766e",
  "#0284c7",
  "#7c3aed",
  "#db2777",
  "#d97706",
  "#059669",
  "#dc2626",
  "#0891b2",
  "#9333ea",
  "#ea580c",
  "#4f46e5",
  "#16a34a",
  "#b45309"
];
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

// ---------- 乡镇下钻：二级患者卡片悬浮窗 + 三级病程弹窗 ----------
interface TownshipPatientCard {
  caseId: string;
  patientId: string;
  encounterId: string;
  name: string;
  phone: string;
  address: string;
  visitDate: string;
  township: string;
}
const caseMapById = ref(new Map<string, PreAiPatientCase>());
const townshipDialogVisible = ref(false);
const townshipDialogTitle = ref("");
const townshipDialogSubtitle = ref("");
const townshipPatients = ref<TownshipPatientCard[]>([]);
const courseDialogVisible = ref(false);
const courseLoading = ref(false);
const coursePatient = ref<TownshipPatientCard | null>(null);
const courseOverview = ref<PreAiEncounterOverview | null>(null);
const courseError = ref("");
const courseAttachments = ref<PreAiAttachment[]>([]);

const { openPatientDetail } = usePatientNavigation();

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

// 环形图中心单一 label 的响应式内容：hover 切换分块明细，移开恢复本地占比
const centerText = ref({ title: "本地占比", value: "—" });
const onDonutHover = (params: any) => {
  if (params?.name == null) return;
  centerText.value = { title: String(params.name), value: `${params.value} 人（${params.percent}%）` };
};
const resetDonutCenter = () => {
  centerText.value = { title: "本地占比", value: localRatioText.value };
};

const donutOption = computed<EChartsOption>(() => {
  const d = distribution.value;
  const palette = chartPalette.value;
  return {
    tooltip: {
      trigger: "item",
      formatter: "{b}：{c} 人（{d}%）",
      backgroundColor: palette.tooltipBg,
      borderColor: palette.tooltipBorder,
      textStyle: { color: palette.label }
    },
    legend: { bottom: 0, icon: "circle", textStyle: { color: palette.text, fontSize: 12 } },
    series: [
      {
        // 移植官方 pie-borderRadius 示例：分块间留缝 + 圆角切片 + 中心 hover 明细
        type: "pie",
        radius: ["46%", "70%"],
        center: ["50%", "44%"],
        avoidLabelOverlap: false,
        padAngle: 2,
        itemStyle: { borderRadius: 8, borderColor: palette.maskBorder, borderWidth: 2 },
        label: {
          show: true,
          position: "center",
          // 中心单一 label：hover 事件驱动内容切换，杜绝静态文字与 emphasis 文字叠影
          formatter: `{t|${centerText.value.title}}\n{v|${centerText.value.value}}`,
          lineHeight: 22,
          rich: {
            t: {
              color: palette.text,
              fontSize: 12,
              lineHeight: 18
            },
            v: {
              fontSize: 26,
              fontWeight: 700,
              color: isDark.value ? "#f1f5f9" : "#0f766e",
              fontVariantNumeric: "tabular-nums",
              lineHeight: 30
            }
          }
        },
        labelLine: { show: false },
        data: [
          { name: "周边乡镇", value: d.townshipTotal, itemStyle: { color: "#0f766e" } },
          { name: "城区", value: d.urban, itemStyle: { color: "#14b8a6" } },
          { name: "县外", value: d.outside, itemStyle: { color: "#94a3b8" } },
          { name: "固始县其他", value: d.gushiOther, itemStyle: { color: "#d97706" } },
          { name: "未登记地址", value: d.unknown, itemStyle: { color: palette.unknown } }
        ].filter(item => item.value > 0),
        // 载入动画：线性过渡 + 逐分块错峰
        animationEasing: "linear",
        animationDuration: 900,
        animationDelay: (idx: number) => idx * 150
      }
    ]
  };
});

const barOption = computed<EChartsOption>(() => {
  // 粒度对齐全量病历患者：全部乡镇逐行展示，不再合并"其他乡镇"
  const rows = townshipRanking.value.map(row => ({ name: row.name, count: row.count }));
  const palette = chartPalette.value;
  // 行数多时 dataZoom 平移滚动：默认展示前 12 行，可滚轮/拖拽查看全部
  const zoomEnd = Math.min(100, Math.round((12 / Math.max(rows.length, 1)) * 100));
  // 每行独立色块，相邻行颜色必不相同
  const rowColor = (index: number) => BAR_ROW_PALETTE[index % BAR_ROW_PALETTE.length];
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
    dataZoom: [
      { type: "inside", yAxisIndex: 0, start: 0, end: zoomEnd, zoomOnMouseWheel: false, moveOnMouseWheel: true },
      { type: "slider", yAxisIndex: 0, right: 4, width: 14, start: 0, end: zoomEnd, brushSelect: false }
    ],
    series: [
      {
        type: "bar",
        data: rows.map((row, index) => ({
          name: row.name,
          value: row.count,
          itemStyle: {
            borderRadius: [0, 8, 8, 0],
            color: rowColor(index)
          }
        })),
        barMaxWidth: 16,
        label: { show: true, position: "right", color: palette.text, fontSize: 12 },
        animationDuration: 700,
        animationDelay: (idx: number) => idx * 45
      }
    ]
  };
});

// ---------- 迁移地图：Voronoi 近似分块（锯齿边界）+ 灰底 hover 彩色强调 + 涟漪散点 ----------
const countyGeo = ref<unknown>(null);
const regionHoverColors = ref<Record<string, string>>({});

const mapTotalByRegion = computed(() => {
  const d = distribution.value;
  const map = new Map<string, number>();
  d.townshipCounts.forEach((count, name) => map.set(name, count));
  map.set("城区", d.urban);
  return map;
});

const maxRegionCount = computed(() => Math.max(1, ...mapTotalByRegion.value.values()));

const localCoord = (name: string): [number, number] => TOWNSHIP_COORDS[name] || HOSPITAL_COORD;

const hash32 = (str: string) => {
  let h = 2166136261;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
};
const mulberry32 = (seedNum: number) => () => {
  seedNum |= 0;
  seedNum = (seedNum + 0x6d2b79f5) | 0;
  let t = Math.imul(seedNum ^ (seedNum >>> 15), 1 | seedNum);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};

/** 共享边确定性锯齿化：同一条 Voronoi 边在相邻分块中生成完全一致的锯齿路径，避免缝隙与重叠。 */
const jaggedSegmentCache = new Map<string, number[][]>();
const jaggedSegment = (a: number[], b: number[]): number[][] => {
  const samePoint = (p: number[], q: number[]) => p[0] === q[0] && p[1] === q[1];
  const first = a[0] < b[0] || (a[0] === b[0] && a[1] < b[1]) ? a : b;
  const second = samePoint(first, a) ? b : a;
  const key = `${first[0]},${first[1]}->${second[0]},${second[1]}`;
  const cached = jaggedSegmentCache.get(key);
  if (cached) return samePoint(first, a) ? cached : [...cached].reverse();
  const rand = mulberry32(hash32(key));
  const dx = second[0] - first[0];
  const dy = second[1] - first[1];
  const len = Math.hypot(dx, dy) || 1e-9;
  const nx = -dy / len;
  const ny = dx / len;
  const amp = Math.min(0.02, len * 0.14);
  const steps = 4;
  const jag: number[][] = [];
  for (let s = 1; s <= steps; s++) {
    const t = s / (steps + 1);
    const disp = (rand() - 0.5) * 2 * amp;
    jag.push([first[0] + dx * t + nx * disp, first[1] + dy * t + ny * disp]);
  }
  jaggedSegmentCache.set(key, jag);
  return jag;
};

/** 以乡镇驻点为种子生成 Voronoi 分块（锯齿化边界），再用固始县真实轮廓裁剪，得到近似乡镇分区 GeoJSON。 */
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
      const ring: number[][] = [];
      for (let i = 0; i < cell.length - 1; i++) {
        const edgeStart = cell[i] as number[];
        const edgeEnd = cell[i + 1] as number[];
        ring.push(edgeStart);
        ring.push(...jaggedSegment(edgeStart, edgeEnd));
      }
      ring.push(cell[0] as number[]);
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

  // 相邻地区 hover 强调色不重复：按 Voronoi 邻接关系贪心分配饱和色
  const hoverPalette = ["#0d9488", "#2563eb", "#e11d48", "#d97706", "#7c3aed", "#16a34a", "#db2777", "#0284c7"];
  const assigned: Record<string, string> = {};
  seeds.forEach((seed, index) => {
    const usedByNeighbors = new Set(
      [...voronoi.neighbors(index)].map(neighborIndex => assigned[seeds[neighborIndex].name]).filter(Boolean)
    );
    assigned[seed.name] = hoverPalette.find(color => !usedByNeighbors.has(color)) ?? hoverPalette[index % hoverPalette.length];
  });
  regionHoverColors.value = assigned;
  return { type: "FeatureCollection" as const, features };
};

const geoOption = computed(() => {
  if (!mapRegistered.value) return {} as EChartsOption;
  const palette = chartPalette.value;
  const maxCount = maxRegionCount.value;
  const regionData = [...mapTotalByRegion.value.entries()].map(([name, value]) => ({
    name,
    value,
    // 每个地区预分配专属强调色（贪心保证相邻不重复），hover 时灰底浮起并亮出该色
    emphasis: {
      label: { show: true, fontSize: 14, fontWeight: 700 as const, color: "#ffffff" },
      itemStyle: {
        areaColor: regionHoverColors.value[name] || "#0d9488",
        shadowBlur: 18,
        shadowOffsetY: 10,
        shadowColor: isDark.value ? "rgba(0, 0, 0, 0.55)" : "rgba(15, 23, 42, 0.4)"
      }
    }
  }));
  const scatterData = [...mapTotalByRegion.value.entries()]
    .filter(([name, value]) => value > 0 && TOWNSHIP_COORDS[name])
    .map(([name, value]) => ({ name, value: [...localCoord(name), value] }));
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
      itemStyle: {
        areaColor: isDark.value ? "#414b5a" : "#d5dbe4",
        borderColor: isDark.value ? "#232b36" : "#ffffff",
        borderWidth: 1
      },
      emphasis: {
        label: { show: true, color: "#ffffff", fontWeight: 700 as const, fontSize: 14 },
        itemStyle: {
          shadowBlur: 18,
          shadowOffsetY: 10,
          shadowColor: isDark.value ? "rgba(0, 0, 0, 0.55)" : "rgba(15, 23, 42, 0.4)"
        }
      },
      select: { disabled: true }
    },
    series: [
      {
        type: "map",
        geoIndex: 0,
        data: regionData,
        animationDuration: 700
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
    const [{ data }, casesResult] = await Promise.all([
      getBillingPatientsApi(""),
      getPreAiPatientCasesApi().catch(() => ({ data: { list: [] as PreAiPatientCase[] } }))
    ]);
    patients.value = data.patients || [];
    caseMapById.value = new Map((casesResult.data.list || []).map(item => [item.id, item]));
    resetDonutCenter();
  } catch (error) {
    ElMessage.error((error as Error).message || "患者收费信息加载失败");
  } finally {
    loading.value = false;
  }
};

/** 点击乡镇柱形条：按乡镇归类打开二级患者卡片悬浮窗（聚合行展示其覆盖的全部乡镇患者）。 */
const onBarClick = (params: any) => {
  const rowName = String(params?.name || "");
  if (!rowName) return;
  const cards: TownshipPatientCard[] = patients.value
    .map(patient => {
      const classified = classifyAddress(patient.address);
      return {
        caseId: patient.id,
        classified,
        patientId: caseMapById.value.get(patient.id)?.sourcePatientId || "",
        encounterId: caseMapById.value.get(patient.id)?.latestEncounter?.id || "",
        visitDate: caseMapById.value.get(patient.id)?.latestEncounter?.visitDate || patient.updatedAt,
        name: patient.patientName,
        phone: patient.phone,
        address: patient.address
      };
    })
    .filter(card => card.classified.bucket === "township" && card.classified.township === rowName)
    .map(card => ({
      caseId: card.caseId,
      patientId: card.patientId,
      encounterId: card.encounterId,
      name: card.name,
      phone: card.phone,
      address: card.address,
      visitDate: card.visitDate,
      township: card.classified.township
    }))
    .sort((a, b) => (a.visitDate < b.visitDate ? 1 : -1));
  townshipDialogTitle.value = `${rowName} · 来访患者`;
  townshipDialogSubtitle.value = `共 ${cards.length} 位患者 · 数据来源：患者收费信息（全部病历患者）`;
  townshipPatients.value = cards;
  townshipDialogVisible.value = true;
};

/** 三级病程弹窗：复用患者概览的 encounter overview 接口。 */
const openCourseDialog = async (card: TownshipPatientCard) => {
  if (!card.encounterId) return;
  coursePatient.value = card;
  courseOverview.value = null;
  courseAttachments.value = [];
  courseError.value = "";
  courseDialogVisible.value = true;
  courseLoading.value = true;
  try {
    const [overviewResult, workspaceResult] = await Promise.allSettled([
      getEncounterOverviewApi(card.encounterId),
      getPreAiWorkspaceApi(card.encounterId)
    ]);
    if (overviewResult.status === "fulfilled") {
      courseOverview.value = overviewResult.value.data;
    } else {
      courseError.value = overviewResult.reason?.message || "病程信息加载失败";
    }
    if (workspaceResult.status === "fulfilled") {
      courseAttachments.value = workspaceResult.value.data.attachments || [];
    }
  } catch (error: any) {
    courseError.value = error?.message || "病程信息加载失败";
  } finally {
    courseLoading.value = false;
  }
};

const downloadCourseAttachment = (attachment: PreAiAttachment) => {
  void downloadPreAiAttachmentApi(attachment);
};

const gotoPatientArchive = () => {
  const card = coursePatient.value;
  if (!card?.patientId) return;
  courseDialogVisible.value = false;
  townshipDialogVisible.value = false;
  openPatientDetail(card.patientId);
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
  height: 460px;
}
.township-subtitle {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

// 档案化纵向行式列表（对齐患者主档案视图）
.township-patient-rows {
  display: grid;
  gap: 8px;
  max-height: 480px;
  padding-right: 4px;
  overflow-y: auto;
}

// 全屏弹窗：body 占满并可滚动，内容居中收窄
.township-patient-dialog.is-fullscreen,
.course-dialog.is-fullscreen {
  :deep(.el-dialog__header) {
    padding: 14px 24px;
    margin-right: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }
  :deep(.el-dialog__body) {
    width: 100%;
    max-width: 1200px;
    height: calc(100vh - 130px);
    margin: 0 auto;
    overflow-y: auto;
  }
  :deep(.el-dialog__footer) {
    padding: 12px 24px;
    border-top: 1px solid var(--el-border-color-lighter);
  }
}
.township-patient-dialog.is-fullscreen .township-patient-rows {
  max-height: none;
  overflow: visible;
}
.patient-row {
  display: grid;
  grid-template-columns: 96px minmax(96px, 0.9fr) 112px minmax(0, 1.6fr) minmax(72px, 0.6fr) auto;
  gap: 14px;
  align-items: center;
  padding: 10px 14px;
  text-align: left;
  cursor: pointer;
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;
  &:hover {
    border-color: var(--el-color-primary);
    box-shadow: 0 8px 18px color-mix(in srgb, var(--el-color-primary) 14%, transparent);
    transform: translateY(-1px);
    .row-more {
      opacity: 1;
    }
  }
  .row-name {
    font-size: 14px;
    font-weight: 700;
    color: var(--el-text-color-primary);
  }
  .row-cell {
    display: grid;
    gap: 1px;
    min-width: 0;
    font-size: 12px;
    color: var(--el-text-color-regular);
    label {
      font-size: 11px;
      color: var(--el-text-color-secondary);
    }
    &.address {
      display: -webkit-box;
      overflow: hidden;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
    }
  }
  .row-more {
    flex-shrink: 0;
    font-size: 12px;
    font-style: normal;
    font-weight: 600;
    color: var(--el-color-primary);
    opacity: 0.75;
  }
}
.course-body {
  display: grid;
  gap: 12px;
  min-height: 160px;
}
.course-sec {
  display: grid;
  gap: 6px;
  .course-sec-title {
    padding-left: 8px;
    font-size: 13px;
    font-weight: 700;
    color: var(--el-color-primary);
    border-left: 3px solid var(--el-color-primary);
  }
  p {
    display: grid;
    gap: 1px;
    margin: 0;
    font-size: 13px;
    line-height: 1.55;
    label {
      margin-right: 8px;
      font-size: 11px;
      color: var(--el-text-color-secondary);
    }
  }
  .course-empty {
    margin: 0;
    font-size: 12px;
    color: var(--el-text-color-placeholder);
  }
  :deep(.attachment-gallery) {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    .attachment-card {
      width: 132px;
      padding: 6px;
      border-radius: 8px;
    }
    .image-thumbnail {
      width: 100%;
      height: 86px;
      border-radius: 6px;
    }
  }
}

@media (width <= 1080px) {
  .chart-row {
    grid-template-columns: 1fr;
  }
}
</style>
