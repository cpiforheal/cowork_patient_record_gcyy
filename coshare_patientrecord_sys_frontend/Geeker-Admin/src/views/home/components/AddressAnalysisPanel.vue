<template>
  <section class="address-analysis">
    <header class="analysis-head">
      <div>
        <strong>来访患者住址分布</strong>
        <small
          >数据来源：患者收费信息 · 固始县周边乡镇对照 · 总样本 {{ patients.length }} 人 · 本地样本
          {{ localSampleCount }} 人</small
        >
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

    <div v-if="loading" class="analysis-loading" v-loading="true" element-loading-text="住址分布加载中…" />
    <el-empty v-else-if="!patients.length" description="暂无患者收费信息，无法分析住址分布" :image-size="56" />
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

      <!-- 二级悬浮窗：乡镇来访患者卡片（患者概览卡片样式 · 住址重心强调） -->
      <el-dialog
        v-model="townshipDialogVisible"
        :title="townshipDialogTitle"
        width="min(1280px, 94vw)"
        top="5vh"
        append-to-body
        destroy-on-close
        class="township-patient-dialog"
      >
        <p class="township-subtitle">{{ townshipDialogSubtitle }}</p>
        <el-empty v-if="!townshipPatients.length" description="该乡镇暂无来访患者（可能地址未登记或归属县外）" :image-size="56" />
        <div v-else class="township-card-grid">
          <button
            v-for="(card, index) in townshipPatients"
            :key="card.caseId"
            type="button"
            class="patient-card"
            :style="{ '--row-delay': `${index * 0.05}s` }"
            @click="openCourseDialog(card)"
          >
            <span class="card-head">
              <span class="card-id">
                <span class="card-name">{{ card.name || "未登记姓名" }}</span>
                <small>{{ [card.gender, card.age].filter(Boolean).join(" · ") || "信息未登记" }}</small>
              </span>
              <el-tag v-if="card.visitCount > 0" size="small" effect="plain" round>就诊 {{ card.visitCount }} 次</el-tag>
            </span>
            <span class="card-address">
              <el-icon><Location /></el-icon>
              <span class="address-text">{{ card.address || "住址未登记" }}</span>
            </span>
            <span class="card-facts">
              <span class="fact"
                ><label>最近就诊</label><b>{{ card.visitDate || "—" }}</b></span
              >
              <span class="fact"
                ><label>联系电话</label><b>{{ card.phone || "—" }}</b></span
              >
            </span>
            <span class="card-foot">
              <span>点击查看病程</span>
              <el-icon><ArrowRight /></el-icon>
            </span>
          </button>
        </div>
      </el-dialog>

      <!-- 三级弹窗：主要病程（模板预览态层级：文档头 + 节段标题/描述 + 字段网格） -->
      <el-dialog
        v-model="courseDialogVisible"
        :title="`${coursePatient?.name || '患者'} · 主要病程`"
        width="min(1080px, 94vw)"
        top="5vh"
        append-to-body
        destroy-on-close
        class="course-dialog"
      >
        <div v-loading="courseLoading" class="course-body" element-loading-text="病程加载中…">
          <el-alert v-if="courseError" type="warning" :closable="false" show-icon :title="courseError" />
          <template v-else-if="courseOverview">
            <header class="doc-header">
              <h2>{{ courseOverview.patient.name || coursePatient?.name || "—" }}</h2>
              <p class="doc-subtitle">
                主要病程事实预览 ·
                {{ [courseOverview.patient.gender, courseOverview.patient.age].filter(Boolean).join(" / ") || "性别/年龄未登记" }}
                · 联系电话 {{ coursePatient?.phone || courseOverview.patient.phone || "—" }}
              </p>
              <div class="doc-meta">
                <span><label>就诊日期</label>{{ courseOverview.visit.visitDate || "—" }}</span>
                <span><label>归属乡镇</label>{{ coursePatient?.township || "—" }}</span>
                <span><label>就诊次数</label>第 {{ courseOverview.visit.visitNo || 1 }} 次</span>
              </div>
            </header>

            <section class="doc-section">
              <div class="doc-section-heading">
                <h3>主要病情</h3>
                <p class="doc-section-note">主诉与现病史为本次诊疗核心事实</p>
              </div>
              <div class="doc-fields">
                <div class="doc-field wide chief">
                  <strong>主诉</strong>
                  <span>{{ courseOverview.clinical.chiefComplaint || "—" }}</span>
                </div>
                <div class="doc-field wide">
                  <strong>现病史</strong>
                  <span>{{ courseOverview.clinical.presentIllness || "—" }}</span>
                </div>
                <div v-if="courseOverview.clinical.specialistExam" class="doc-field wide">
                  <strong>专科检查</strong>
                  <span>{{ courseOverview.clinical.specialistExam }}</span>
                </div>
                <div class="doc-field wide allergy">
                  <strong>过敏史</strong>
                  <span>{{ courseOverview.clinical.allergyHistory || "未记录" }}</span>
                </div>
              </div>
            </section>

            <section class="doc-section">
              <div class="doc-section-heading">
                <h3>诊断与治疗</h3>
                <p class="doc-section-note">中西医诊断结论与本次治疗路径</p>
              </div>
              <div class="doc-fields">
                <div class="doc-field diagnosis">
                  <strong>西医诊断</strong>
                  <span>{{ courseOverview.clinical.diagnosis.westernPrimary || "—" }}</span>
                </div>
                <div class="doc-field diagnosis">
                  <strong>中医诊断</strong>
                  <span>{{ courseOverview.clinical.diagnosis.tcm || "—" }}</span>
                </div>
                <div class="doc-field">
                  <strong>治疗路径</strong>
                  <span>{{ courseOverview.clinical.treatment.treatmentPath || "—" }}</span>
                </div>
                <div class="doc-field">
                  <strong>手术 · 麻醉</strong>
                  <span>
                    {{ courseOverview.clinical.surgery.actualPrimaryOperation || "未手术 / 未记录"
                    }}{{
                      courseOverview.clinical.surgery.anesthesiaMethod
                        ? `（${courseOverview.clinical.surgery.anesthesiaMethod}）`
                        : ""
                    }}
                  </span>
                </div>
              </div>
            </section>

            <section v-if="courseOverview.auxiliary?.labSummary" class="doc-section">
              <div class="doc-section-heading">
                <h3>辅助检查摘要</h3>
                <p class="doc-section-note">化验异常与危急值提示</p>
              </div>
              <div class="doc-fields">
                <div class="doc-field">
                  <strong>化验概览</strong>
                  <span>
                    报告 {{ courseOverview.auxiliary.labReportCount || 0 }} 份 ·
                    <em class="abnormal-num" :class="{ none: !courseOverview.auxiliary.labSummary.abnormalCount }">
                      异常 {{ courseOverview.auxiliary.labSummary.abnormalCount || 0 }} 项</em
                    >
                    <template v-if="courseOverview.auxiliary.labSummary.criticalCount">
                      · <em class="critical-num">危急 {{ courseOverview.auxiliary.labSummary.criticalCount }} 项</em></template
                    >
                  </span>
                </div>
                <div v-if="abnormalMetricList.length" class="doc-field wide">
                  <strong>异常指标</strong>
                  <span class="metric-tags">
                    <el-tag
                      v-for="metric in abnormalMetricList"
                      :key="`${metric.reportName}-${metric.name}-${metric.value}`"
                      size="small"
                      :type="metric.severity === 'CRITICAL' ? 'danger' : 'warning'"
                      effect="plain"
                    >
                      {{ metric.name || "未知指标" }} {{ metric.value || "—" }}{{ metric.unit || "" }}
                    </el-tag>
                  </span>
                </div>
              </div>
            </section>

            <section class="doc-section">
              <div class="doc-section-heading">
                <h3>照片资料</h3>
                <p class="doc-section-note">该就诊全部附件 · 点击可放大查看</p>
              </div>
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
          <el-button type="warning" plain :disabled="!coursePatient?.encounterId" @click="healthArchiveVisible = true">
            健康管理档案
          </el-button>
          <el-button type="primary" :disabled="!coursePatient?.patientId" @click="gotoPatientArchive"> 进入完整档案 </el-button>
        </template>
      </el-dialog>

      <!-- 四级弹窗：健康管理档案只读预览（复用右侧合并文档预览态） -->
      <HealthArchiveDialog
        v-model="healthArchiveVisible"
        preview-only
        :encounter-id="coursePatient?.encounterId || ''"
        :encounter-patient-name="coursePatient?.name"
        :workspace="courseWorkspace || undefined"
      />
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { ArrowRight, Location } from "@element-plus/icons-vue";
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
import HealthArchiveDialog from "@/views/preAi/encounters/components/HealthArchiveDialog.vue";
import type { PreAiWorkspace } from "@/api/modules/clinic";

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
interface BarTone {
  color: string;
  family: string;
}
/** Dashboard 图表色块：参考高饱和积极色值，但加入 mint/sky/violet/rose 扩展，避免刻板复制。 */
const LIGHT_BAR_TONES: BarTone[] = [
  { color: "#ffb84d", family: "orange" },
  { color: "#b9ca5c", family: "lime" },
  { color: "#6fc779", family: "green" },
  { color: "#58c7a4", family: "mint" },
  { color: "#55b6e8", family: "sky" },
  { color: "#7f92f2", family: "blue" },
  { color: "#a983e8", family: "violet" },
  { color: "#ffcf4a", family: "yellow" },
  { color: "#ff7a61", family: "coral" },
  { color: "#f27aa3", family: "rose" }
];
const DARK_BAR_TONES: BarTone[] = [
  { color: "#ffc866", family: "orange" },
  { color: "#d2e36f", family: "lime" },
  { color: "#86df8d", family: "green" },
  { color: "#74dec1", family: "mint" },
  { color: "#72c9ff", family: "sky" },
  { color: "#9aa9ff", family: "blue" },
  { color: "#bd9cff", family: "violet" },
  { color: "#ffda6b", family: "yellow" },
  { color: "#ff9278", family: "coral" },
  { color: "#ff91b7", family: "rose" }
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

// ---------- 乡镇下钻：二级患者卡片悬浮窗 + 三级病程弹窗 + 四级健康档案预览 ----------
interface TownshipPatientCard {
  caseId: string;
  patientId: string;
  encounterId: string;
  name: string;
  gender: string;
  age: string;
  visitCount: number;
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
const courseWorkspace = ref<PreAiWorkspace | null>(null);
const healthArchiveVisible = ref(false);

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
const localSampleCount = computed(() => distribution.value.urban + distribution.value.townshipTotal);
const localRatioText = computed(() => {
  const total = patients.value.length;
  if (!total) return "—";
  return `${Math.round((localSampleCount.value / total) * 100)}%`;
});

const chartPalette = computed(() => ({
  text: isDark.value ? "#a8b3c2" : "#6f7680",
  label: isDark.value ? "#eef5ff" : "#4f5661",
  split: isDark.value ? "rgba(166, 184, 205, 0.18)" : "rgba(91, 108, 130, 0.16)",
  tooltipBg: isDark.value ? "#111827" : "#ffffff",
  tooltipBorder: isDark.value ? "rgba(166, 184, 205, 0.26)" : "rgba(91, 108, 130, 0.16)",
  maskBorder: isDark.value ? "rgba(15, 23, 42, 0.70)" : "rgba(255, 255, 255, 0.86)",
  primary: isDark.value ? "#7ddf8f" : "#66bb7a",
  info: isDark.value ? "#6cc4ff" : "#5aa9e6",
  success: isDark.value ? "#9de27a" : "#8bc06f",
  warning: isDark.value ? "#ffc85a" : "#ffb347",
  danger: isDark.value ? "#ff8a6b" : "#ff7a59",
  purple: isDark.value ? "#b59cff" : "#9b7bd8",
  mutedFill: isDark.value ? "#c6d76a" : "#b7c85b",
  yellow: isDark.value ? "#ffd166" : "#ffca3a",
  orange: isDark.value ? "#ffb86b" : "#ff9f43",
  unknown: isDark.value ? "#6c7a89" : "#cfd7df",
  mapArea: isDark.value ? "#2b3850" : "#eef3f8",
  mapBorder: isDark.value ? "rgba(166, 184, 205, 0.20)" : "rgba(91, 108, 130, 0.18)",
  mapShadow: isDark.value ? "rgba(0, 0, 0, 0.28)" : "rgba(91, 108, 130, 0.16)",
  dataZoomBg: isDark.value ? "rgba(166, 184, 205, 0.10)" : "rgba(91, 108, 130, 0.06)",
  dataZoomFill: isDark.value ? "rgba(125, 223, 143, 0.28)" : "rgba(102, 187, 122, 0.18)",
  barTop: isDark.value ? "#ffc85a" : "#ffb347",
  barTones: isDark.value ? DARK_BAR_TONES : LIGHT_BAR_TONES
}));

const stableToneIndex = (name: string, size: number) => {
  if (!size) return 0;
  let total = 0;
  for (const char of name) total = (total * 31 + char.charCodeAt(0)) >>> 0;
  return total % size;
};

const greedyBarColors = (names: string[], tones: BarTone[]) => {
  let previous: BarTone | undefined;
  return names.map((name, index) => {
    const start = index === 0 ? 0 : stableToneIndex(`${name}-${index}`, tones.length);
    const ordered = tones.slice(start).concat(tones.slice(0, start));
    const picked = ordered.find(tone => tone.family !== previous?.family && tone.color !== previous?.color) || ordered[0];
    previous = picked;
    return picked?.color || "#66bb7a";
  });
};

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
      formatter: (params: any) =>
        `${params.name}：${params.value} 人（${params.percent}%）<br/>总样本：${patients.value.length} 人`,
      confine: true,
      backgroundColor: palette.tooltipBg,
      borderColor: palette.tooltipBorder,
      textStyle: { color: palette.label }
    },
    legend: { bottom: 0, icon: "circle", itemGap: 14, textStyle: { color: palette.text, fontSize: 12 } },
    series: [
      {
        // 移植官方 pie-borderRadius 示例：分块间留缝 + 圆角切片 + 中心 hover 明细
        type: "pie",
        radius: ["46%", "70%"],
        center: ["50%", "44%"],
        avoidLabelOverlap: false,
        padAngle: 2,
        itemStyle: { borderRadius: 8, borderColor: palette.maskBorder, borderWidth: 1 },
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
              color: palette.primary,
              fontVariantNumeric: "tabular-nums",
              lineHeight: 30
            }
          }
        },
        labelLine: { show: false },
        data: [
          { name: "周边乡镇", value: d.townshipTotal, itemStyle: { color: palette.primary } },
          { name: "城区", value: d.urban, itemStyle: { color: palette.info } },
          { name: "县外", value: d.outside, itemStyle: { color: palette.yellow } },
          { name: "固始县其他", value: d.gushiOther, itemStyle: { color: palette.orange } },
          { name: "未登记地址", value: d.unknown, itemStyle: { color: palette.unknown } }
        ].filter(item => item.value > 0),
        animationEasing: "cubicOut",
        animationEasingUpdate: "cubicOut",
        animationDuration: 420,
        animationDurationUpdate: 220,
        animationDelay: (idx: number) => idx * 32
      }
    ]
  };
});

const barOption = computed<EChartsOption>(() => {
  // 粒度对齐全量病历患者：全部乡镇逐行展示，不再合并"其他乡镇"
  const rows = townshipRanking.value.map(row => ({ name: row.name, count: row.count }));
  const palette = chartPalette.value;
  const hasScrollableRows = rows.length > 12;
  const zoomEnd = Math.min(100, Math.round((12 / Math.max(rows.length, 1)) * 100));
  const rowColors = greedyBarColors(
    rows.map(row => row.name),
    palette.barTones
  );
  return {
    tooltip: {
      trigger: "axis",
      axisPointer: { type: "shadow" },
      confine: true,
      backgroundColor: palette.tooltipBg,
      borderColor: palette.tooltipBorder,
      textStyle: { color: palette.label }
    },
    grid: { left: 8, right: hasScrollableRows ? 44 : 26, top: 8, bottom: 8, containLabel: true },
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
    dataZoom: hasScrollableRows
      ? [
          { type: "inside", yAxisIndex: 0, start: 0, end: zoomEnd, zoomOnMouseWheel: false, moveOnMouseWheel: true },
          {
            type: "slider",
            yAxisIndex: 0,
            right: 4,
            width: 14,
            start: 0,
            end: zoomEnd,
            brushSelect: false,
            borderColor: "transparent",
            backgroundColor: palette.dataZoomBg,
            fillerColor: palette.dataZoomFill,
            handleStyle: { color: palette.primary, borderColor: palette.primary },
            textStyle: { color: palette.text }
          }
        ]
      : [],
    series: [
      {
        type: "bar",
        data: rows.map((row, index) => ({
          name: row.name,
          value: row.count,
          itemStyle: {
            borderRadius: [0, 7, 7, 0],
            color: rowColors[index]
          }
        })),
        barWidth: 14,
        barMaxWidth: 18,
        barCategoryGap: "36%",
        label: { show: true, position: "right", color: palette.text, fontSize: 12 },
        animationEasing: "cubicOut",
        animationEasingUpdate: "cubicOut",
        animationDuration: 420,
        animationDurationUpdate: 220,
        animationDelay: (idx: number) => Math.min(idx * 28, 240)
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

  // 相邻地区 hover 强调色不重复：按 Voronoi 邻接关系贪心分配低饱和语义色
  const hoverPalette = ["#b7c85b", "#66bb7a", "#8bc06f", "#ffb347", "#ffca3a", "#ff9f43", "#ff7a59", "#5aa9e6", "#9b7bd8"];
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
    // 每个地区预分配专属强调色（贪心保证相邻不重复），hover 时只做局部轻量高亮
    emphasis: {
      label: { show: true, fontSize: 14, fontWeight: 700 as const, color: isDark.value ? "#0f172a" : "#ffffff" },
      itemStyle: {
        areaColor: regionHoverColors.value[name] || palette.primary,
        shadowBlur: 8,
        shadowOffsetY: 3,
        shadowColor: palette.mapShadow
      }
    }
  }));
  const scatterData = [...mapTotalByRegion.value.entries()]
    .filter(([name, value]) => value > 0 && TOWNSHIP_COORDS[name])
    .map(([name, value]) => ({ name, value: [...localCoord(name), value] }));
  return {
    tooltip: {
      trigger: "item",
      confine: true,
      backgroundColor: palette.tooltipBg,
      borderColor: palette.tooltipBorder,
      textStyle: { color: palette.label },
      formatter: (params: any) => {
        const value = Number(params.value?.[2] ?? params.value ?? 0);
        const total = localSampleCount.value;
        const ratio = total ? Math.round((value / total) * 100) : 0;
        return `${params.name}<br/>来访患者：${value} 人<br/>占本地样本：${ratio}%<br/>本地样本：${total} 人`;
      }
    },
    geo: {
      map: "gushi-townships",
      roam: true,
      zoom: 1.05,
      scaleLimit: { min: 0.8, max: 4 },
      label: { show: true, color: palette.label, fontSize: 10 },
      itemStyle: {
        areaColor: palette.mapArea,
        borderColor: palette.mapBorder,
        borderWidth: 1
      },
      emphasis: {
        label: { show: true, color: isDark.value ? "#0f172a" : "#ffffff", fontWeight: 700 as const, fontSize: 14 },
        itemStyle: {
          shadowBlur: 8,
          shadowOffsetY: 3,
          shadowColor: palette.mapShadow
        }
      },
      select: { disabled: true }
    },
    series: [
      {
        type: "map",
        geoIndex: 0,
        data: regionData,
        animationEasing: "cubicOut",
        animationEasingUpdate: "cubicOut",
        animationDuration: 420,
        animationDurationUpdate: 220
      },
      {
        type: "effectScatter",
        coordinateSystem: "geo",
        zlevel: 3,
        data: scatterData.map(item => ({ name: item.name, value: item.value })),
        symbolSize: (value: unknown) => 10 + (Number((value as number[])?.[2] ?? 0) / maxCount) * 22,
        rippleEffect: { brushType: "stroke", scale: 1.8, period: 4.2 },
        label: { show: false },
        itemStyle: { color: palette.primary },
        animationEasing: "cubicOut",
        animationEasingUpdate: "cubicOut",
        animationDuration: 420,
        animationDurationUpdate: 220
      },
      {
        type: "effectScatter",
        coordinateSystem: "geo",
        zlevel: 4,
        data: [{ name: "本院", value: [HOSPITAL_COORD[0], HOSPITAL_COORD[1], distribution.value.urban] }],
        symbolSize: 14,
        rippleEffect: { brushType: "stroke", scale: 2.2, numberRipples: 2, period: 4.5 },
        label: { show: true, position: "top", formatter: "本院", color: palette.label, fontSize: 12, fontWeight: 700 as const },
        itemStyle: { color: palette.warning },
        animationEasing: "cubicOut",
        animationEasingUpdate: "cubicOut",
        animationDuration: 420,
        animationDurationUpdate: 220
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

/** 点击乡镇柱形条：按乡镇归类打开二级患者卡片悬浮窗（概览卡片样式，聚合其覆盖的全部乡镇患者）。 */
const onBarClick = (params: any) => {
  const rowName = String(params?.name || "");
  if (!rowName) return;
  const cards: TownshipPatientCard[] = patients.value
    .map(patient => {
      const classified = classifyAddress(patient.address);
      const caseInfo = caseMapById.value.get(patient.id);
      return {
        caseId: patient.id,
        classified,
        patientId: caseInfo?.sourcePatientId || "",
        encounterId: caseInfo?.latestEncounter?.id || "",
        visitDate: caseInfo?.latestEncounter?.visitDate || patient.updatedAt,
        name: patient.patientName,
        phone: patient.phone,
        address: patient.address,
        gender: caseInfo?.gender || "",
        age: caseInfo?.age || "",
        visitCount: caseInfo?.visitCount || 0
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
      township: card.classified.township,
      gender: card.gender,
      age: card.age,
      visitCount: card.visitCount
    }))
    .sort((a, b) => (a.visitDate < b.visitDate ? 1 : -1));
  townshipDialogTitle.value = `${rowName} · 来访患者`;
  townshipDialogSubtitle.value = `共 ${cards.length} 位患者 · 数据来源：患者收费信息（全部病历患者）`;
  townshipPatients.value = cards;
  townshipDialogVisible.value = true;
};

/** 三级病程弹窗：复用患者概览的 encounter overview 接口，同时保留 workspace 供四级健康档案预览回填。 */
const openCourseDialog = async (card: TownshipPatientCard) => {
  if (!card.encounterId) return;
  coursePatient.value = card;
  courseOverview.value = null;
  courseAttachments.value = [];
  courseWorkspace.value = null;
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
      courseWorkspace.value = workspaceResult.value.data;
      courseAttachments.value = workspaceResult.value.data.attachments || [];
    }
  } catch (error: any) {
    courseError.value = error?.message || "病程信息加载失败";
  } finally {
    courseLoading.value = false;
  }
};

/** 辅助检查摘要：异常指标列表（对象结构，拼接名称+值展示）。 */
const abnormalMetricList = computed(() => courseOverview.value?.auxiliary?.labSummary?.abnormalMetrics || []);

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
    color: var(--hos-chart-text, var(--el-text-color-primary));
  }
  small {
    color: var(--hos-chart-muted, var(--el-text-color-secondary));
  }
}
.summary-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  .chip {
    padding: 4px 10px;
    font-size: 12px;
    color: var(--hos-chart-muted, var(--el-text-color-secondary));
    background: var(--hos-chart-panel-soft, var(--el-fill-color-light));
    border: 1px solid var(--hos-chart-line-soft, var(--el-border-color-lighter));
    border-radius: 999px;
    b {
      font-variant-numeric: tabular-nums;
      color: var(--hos-chart-primary, var(--el-color-primary));
    }
  }
}
.chart-row {
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 7fr);
  gap: 16px;
}
.analysis-loading {
  min-height: 340px;
  background: var(--hos-chart-panel-soft, var(--el-fill-color-extra-light));
  border: 1px solid var(--hos-chart-line-soft, var(--el-border-color-lighter));
  border-radius: 10px;
}
.chart-block {
  display: grid;
  gap: 6px;
  align-content: start;
  min-width: 0;
  padding: 10px;
  background: var(--hos-chart-panel, var(--el-bg-color));
  border: 1px solid var(--hos-chart-line-soft, var(--el-border-color-lighter));
  border-radius: 10px;
  box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
  .chart-title {
    font-size: 13px;
    color: var(--hos-chart-muted, var(--el-text-color-secondary));
  }
}
.map-block {
  .map-loading {
    height: 440px;
  }
  .map-note {
    font-size: 11px;
    line-height: 1.6;
    color: var(--hos-chart-muted, var(--el-text-color-placeholder));
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

@keyframes township-row-in {
  from {
    opacity: 0;
    transform: translateX(-12px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .patient-card {
    transition: none;
    animation: none;
  }
}
.township-subtitle {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

// 患者概览卡片式网格（对齐 patients/overview 的 overview-card 风格）
.township-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
  max-height: min(560px, calc(88vh - 190px));
  padding-right: 4px;
  overflow-y: auto;
}

// 悬浮窗：body 限高滚动（不全屏、保留灰暗遮罩）
.township-patient-dialog,
.course-dialog {
  :deep(.el-dialog__header) {
    padding-bottom: 10px;
    margin-right: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }
  :deep(.el-dialog__body) {
    max-height: calc(92vh - 150px);
    padding-top: 10px;
    overflow-y: auto;
  }
}
.patient-card {
  position: relative;
  display: grid;
  gap: 10px;
  align-content: start;
  padding: 14px 16px 12px;
  overflow: hidden;
  text-align: left;
  cursor: pointer;
  background: var(--hos-chart-panel, var(--el-bg-color));
  border: 1px solid var(--hos-chart-line-soft, var(--el-border-color-lighter));
  border-radius: 14px;
  box-shadow: 0 10px 30px rgb(15 23 42 / 6%);
  transition:
    border-color var(--motion-control, 180ms) var(--ease-out, ease),
    box-shadow var(--motion-control, 180ms) var(--ease-out, ease),
    transform var(--motion-control, 180ms) var(--ease-out, ease);
  animation: township-row-in var(--motion-panel, 240ms) var(--ease-out, ease) both;
  animation-delay: var(--row-delay, 0s);

  // 左侧主色竖条（对齐患者概览卡片的风险标识位）
  &::before {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0;
    width: 5px;
    content: "";
    background: var(--hos-chart-primary, var(--el-color-primary));
  }

  @media (hover: hover) and (pointer: fine) {
    &:hover {
      border-color: color-mix(
        in srgb,
        var(--hos-chart-primary, var(--el-color-primary)) 45%,
        var(--hos-chart-line-soft, var(--el-border-color-lighter))
      );
      box-shadow: 0 14px 34px color-mix(in srgb, var(--hos-chart-primary, var(--el-color-primary)) 16%, transparent);
      transform: translateY(-2px);
      .card-foot {
        color: var(--hos-chart-primary, var(--el-color-primary));
        .el-icon {
          transform: translateX(3px);
        }
      }
    }
  }
  .card-head {
    display: flex;
    gap: 8px;
    align-items: flex-start;
    justify-content: space-between;
    .card-id {
      display: grid;
      gap: 2px;
      min-width: 0;
      .card-name {
        font-size: 20px;
        font-weight: 800;
        color: var(--el-text-color-primary);
        letter-spacing: 1px;
      }
      small {
        font-size: 12px;
        color: var(--el-text-color-secondary);
      }
    }
  }

  // 住址重心强调块：主色浅底渐变 + 定位图标 + 加粗
  .card-address {
    display: flex;
    gap: 8px;
    align-items: flex-start;
    padding: 9px 12px;
    background: linear-gradient(
      135deg,
      color-mix(in srgb, var(--hos-chart-primary, var(--el-color-primary)) 12%, transparent),
      color-mix(in srgb, var(--hos-chart-primary, var(--el-color-primary)) 3%, transparent)
    );
    border-radius: 8px;
    .el-icon {
      flex-shrink: 0;
      margin-top: 2px;
      font-size: 15px;
      color: var(--hos-chart-primary, var(--el-color-primary));
    }
    .address-text {
      font-size: 13px;
      font-weight: 700;
      line-height: 1.55;
      color: var(--el-text-color-primary);
      word-break: break-all;
    }
  }
  .card-facts {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px 14px;
    .fact {
      display: grid;
      gap: 2px;
      min-width: 0;
      label {
        font-size: 11px;
        color: var(--el-text-color-secondary);
      }
      b {
        overflow: hidden;
        font-size: 13px;
        font-weight: 600;
        color: var(--el-text-color-primary);
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }
  }
  .card-foot {
    display: flex;
    gap: 4px;
    align-items: center;
    justify-content: flex-end;
    font-size: 12px;
    font-weight: 600;
    color: var(--el-text-color-secondary);
    transition: color var(--motion-control, 180ms) var(--ease-out, ease);
    .el-icon {
      transition: transform var(--motion-control, 180ms) var(--ease-out, ease);
    }
  }
}
.course-body {
  display: grid;
  gap: 18px;
  min-height: 160px;
}

// 文档头：姓名大标题 + 副标题 + meta（对齐模板预览态 document-header 层级）
.doc-header {
  display: grid;
  gap: 6px;
  padding-bottom: 12px;
  border-bottom: 2px solid var(--hos-chart-primary, var(--el-color-primary));
  h2 {
    margin: 0;
    font-size: 22px;
    font-weight: 800;
    color: var(--el-text-color-primary);
    letter-spacing: 1px;
  }
  .doc-subtitle {
    margin: 0;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
  .doc-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 24px;
    margin-top: 4px;
    font-size: 13px;
    color: var(--el-text-color-primary);
    span {
      display: inline-flex;
      gap: 6px;
      align-items: baseline;
    }
    label {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }
}

// 节段：标题 + 描述 note + 字段网格（点线分隔，对齐模板预览态 document-section 层级）
.doc-section {
  display: grid;
  gap: 10px;
  .doc-section-heading {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 12px;
    align-items: baseline;
    h3 {
      padding-left: 10px;
      margin: 0;
      font-size: 16px;
      font-weight: 800;
      color: var(--el-text-color-primary);
      border-left: 4px solid var(--hos-chart-primary, var(--el-color-primary));
    }
    .doc-section-note {
      margin: 0;
      font-size: 12px;
      color: var(--el-text-color-placeholder);
    }
  }
  .doc-fields {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px 26px;
  }
  .doc-field {
    display: grid;
    gap: 3px;
    align-content: start;
    padding-bottom: 8px;
    border-bottom: 1px dotted var(--el-border-color);
    strong {
      font-size: 12px;
      font-weight: 600;
      color: var(--el-text-color-secondary);
    }
    span {
      font-size: 13px;
      line-height: 1.65;
      color: var(--el-text-color-primary);
    }
    &.wide {
      grid-column: 1 / -1;
    }

    // 主诉：一级视觉重心
    &.chief span {
      font-size: 16px;
      font-weight: 700;
      color: var(--hos-chart-primary, var(--el-color-primary));
    }

    // 诊断：二级重心
    &.diagnosis span {
      font-weight: 700;
    }

    // 过敏史：警示重心（warning 左条 + 浅底）
    &.allergy {
      padding: 8px 10px;
      background: color-mix(in srgb, var(--el-color-warning) 8%, transparent);
      border-bottom: none;
      border-left: 3px solid var(--el-color-warning);
      border-radius: 6px;
      span {
        font-weight: 600;
      }
    }
    .abnormal-num {
      font-style: normal;
      font-weight: 700;
      color: var(--el-color-warning);
      &.none {
        color: var(--el-text-color-secondary);
      }
    }
    .critical-num {
      font-style: normal;
      font-weight: 700;
      color: var(--el-color-danger);
    }
    .metric-tags {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
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

@media (width <= 720px) {
  .doc-section .doc-fields {
    grid-template-columns: 1fr;
  }
}

@media (width <= 1080px) {
  .chart-row {
    grid-template-columns: 1fr;
  }
}
</style>
