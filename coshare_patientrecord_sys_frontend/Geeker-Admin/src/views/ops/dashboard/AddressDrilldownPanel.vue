<template>
  <section class="address-drilldown ops-card">
    <header class="drill-head">
      <div>
        <h3>患者居住地分析</h3>
        <p>县区 → 乡镇/街道 → 村/社区 → 患者卡片</p>
      </div>
      <div class="drill-controls">
        <el-segmented v-model="period" :options="periodOptions" @change="loadRoot" />
        <el-segmented v-model="metric" :options="metricOptions" @change="reloadCurrent" />
        <el-input v-model="keyword" clearable placeholder="搜索当前层级" style="width: 180px" @keyup.enter="reloadCurrent" />
        <el-button :icon="Refresh" :loading="loading" @click="reloadCurrent">刷新</el-button>
      </div>
    </header>

    <div class="drill-summary">
      <span>患者数 <b>{{ data?.summary.patientCount ?? 0 }}</b></span>
      <span>来访人次 <b>{{ data?.summary.visitCount ?? 0 }}</b></span>
      <span>未识别地址 <b>{{ data?.summary.unidentifiedCount ?? 0 }}</b></span>
      <small>{{ data?.from || "-" }} 至 {{ data?.to || "-" }}</small>
    </div>

    <div class="drill-breadcrumb">
      <el-button
        v-for="item in visibleBreadcrumb"
        :key="`${item.level}-${item.key}`"
        text
        size="small"
        @click="goBreadcrumb(item)"
      >
        {{ item.label }}
      </el-button>
    </div>

    <div v-loading="loading" class="drill-body">
      <template v-if="level !== 'PATIENT'">
        <VChart v-if="chartRows.length" class="drill-chart" :option="chartOption" autoresize @click="onChartClick" />
        <el-empty v-else description="当前周期暂无地址数据" :image-size="64" />
      </template>

      <template v-else>
        <div v-if="patients.length" class="patient-grid">
          <button v-for="patient in patients" :key="patient.id" type="button" class="patient-card" @click="openPatient(patient)">
            <span class="patient-card-head">
              <strong>{{ patient.name || "未登记姓名" }}</strong>
              <el-tag size="small" effect="plain">{{ patient.visitCount }} 次来访</el-tag>
            </span>
            <span class="patient-meta">{{ [patient.gender, patient.age].filter(Boolean).join(" · ") || "基础信息未完善" }}</span>
            <span class="patient-address">{{ patient.address || "地址未登记" }}</span>
            <span class="patient-foot">
              <span>最近就诊 {{ patient.latestVisitDate || "-" }}</span>
              <span v-if="examTotal(patient)" :class="{ 'has-abnormal': examAbnormal(patient) }">
                检查 {{ examTotal(patient) }} 项<span v-if="examAbnormal(patient)"> · 异常 {{ examAbnormal(patient) }}</span>
              </span>
            </span>
          </button>
        </div>
        <el-empty v-else description="当前村/社区暂无患者" :image-size="64" />
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import { BarChart } from "echarts/charts";
import { GridComponent, TooltipComponent } from "echarts/components";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import VChart from "vue-echarts";
import type { EChartsOption } from "echarts";
import {
  loadAddressAnalysisApi,
  type AddressAnalysisLevel,
  type AddressAnalysisNode,
  type AddressAnalysisResult,
  type AddressPatientCard
} from "@/api/modules/clinic/opsDashboard";

use([CanvasRenderer, BarChart, GridComponent, TooltipComponent]);

const router = useRouter();
const period = ref("365");
const metric = ref<"visits" | "patients">("visits");
const keyword = ref("");
const level = ref<AddressAnalysisLevel>("COUNTY");
const parentKey = ref("");
const loading = ref(false);
const data = ref<AddressAnalysisResult>();

const periodOptions = [
  { label: "近30天", value: "30" },
  { label: "近90天", value: "90" },
  { label: "近12个月", value: "365" }
];
const metricOptions = [
  { label: "来访人次", value: "visits" },
  { label: "患者数", value: "patients" }
];

const dateRange = computed(() => {
  const end = new Date();
  const start = new Date(end);
  start.setDate(start.getDate() - Number(period.value) + 1);
  const format = (date: Date) => date.toISOString().slice(0, 10);
  return { from: format(start), to: format(end) };
});
const chartRows = computed(() => data.value?.nodes || []);
const patients = computed(() => data.value?.patients || []);
const visibleBreadcrumb = computed(() => data.value?.breadcrumb || [{ key: "", label: "全部地区", level: "COUNTY" }]);

const chartOption = computed<EChartsOption>(() => {
  const rows = [...chartRows.value].reverse();
  return {
    grid: { left: 112, right: 60, top: 16, bottom: 18 },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    xAxis: { type: "value", axisLabel: { color: "#909399" }, splitLine: { lineStyle: { type: "dashed", color: "#ebeef5" } } },
    yAxis: { type: "category", data: rows.map(row => row.label), axisLabel: { color: "#606266" } },
    series: [{
      type: "bar",
      barWidth: 18,
      data: rows.map(row => ({
        value: metric.value === "patients" ? row.patientCount : row.visitCount,
        key: row.key,
        itemStyle: { color: row.hasChildren ? "#3d8bfd" : "#91caff", borderRadius: [0, 4, 4, 0] }
      })),
      label: { show: true, position: "right", color: "#303133", fontSize: 11 }
    }]
  };
});

const load = async () => {
  loading.value = true;
  try {
    const { data: next } = await loadAddressAnalysisApi({
      ...dateRange.value,
      level: level.value,
      parentKey: parentKey.value,
      metric: metric.value,
      keyword: keyword.value,
      page: 1,
      pageSize: 80
    });
    data.value = next;
  } catch (error: any) {
    ElMessage.error(error?.message || "地址分析加载失败");
  } finally {
    loading.value = false;
  }
};

const loadRoot = () => {
  level.value = "COUNTY";
  parentKey.value = "";
  keyword.value = "";
  void load();
};
const reloadCurrent = () => void load();

const onChartClick = (params: any) => {
  const row = chartRows.value.find(item => item.label === params?.name || item.key === params?.data?.key);
  if (!row || !row.hasChildren) return;
  level.value = row.level as AddressAnalysisLevel;
  parentKey.value = row.key;
  keyword.value = "";
  void load();
};

const goBreadcrumb = (item: { key: string; level: string }) => {
  level.value = item.level as AddressAnalysisLevel;
  parentKey.value = item.key;
  keyword.value = "";
  void load();
};

const openPatient = (patient: AddressPatientCard) => {
  if (!patient.encounterId) return;
  router.push({ path: "/health-archive", query: { encounterId: patient.encounterId } });
};

const examEntries = (patient: AddressPatientCard) => Object.values(patient.examSummary || {});
const examTotal = (patient: AddressPatientCard) => examEntries(patient).reduce((sum, item) => sum + Number(item.reports || 0), 0);
const examAbnormal = (patient: AddressPatientCard) => examEntries(patient).reduce((sum, item) => sum + Number(item.abnormal || 0), 0);

onMounted(() => void load());
</script>

<style scoped lang="scss">
.address-drilldown {
  display: grid;
  gap: 12px;
}
.drill-head,
.drill-controls,
.drill-summary,
.drill-breadcrumb,
.patient-card-head,
.patient-foot {
  display: flex;
  align-items: center;
}
.drill-head { justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.drill-head h3 { margin: 0; font-size: 15px; }
.drill-head p { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 12px; }
.drill-controls { gap: 8px; flex-wrap: wrap; }
.drill-summary { gap: 20px; flex-wrap: wrap; color: var(--el-text-color-secondary); font-size: 12px; }
.drill-summary b { margin-left: 4px; color: var(--el-text-color-primary); font-size: 16px; }
.drill-summary small { margin-left: auto; color: var(--el-text-color-placeholder); }
.drill-breadcrumb { gap: 2px; min-height: 28px; border-top: 1px dashed var(--el-border-color-lighter); }
.drill-chart { width: 100%; height: 320px; }
.patient-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 10px; }
.patient-card { display: grid; gap: 8px; padding: 14px; text-align: left; cursor: pointer; background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.patient-card:hover { border-color: var(--el-color-primary); }
.patient-card-head { justify-content: space-between; gap: 8px; }
.patient-card-head strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.patient-meta, .patient-address, .patient-foot { color: var(--el-text-color-secondary); font-size: 12px; }
.patient-address { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.patient-foot { justify-content: space-between; gap: 8px; }
.has-abnormal { color: var(--el-color-danger); }
@media (width <= 760px) { .drill-controls, .drill-summary small { margin-left: 0; } .drill-chart { height: 280px; } }
</style>
