<template>
  <section class="population-panel">
    <header class="population-head"><div><h3>患者居住地分析</h3><p>县区 → 乡镇/街道 → 村/社区 → 脱敏患者卡片</p></div><div class="population-controls"><el-segmented v-model="period" :options="periodOptions" @change="resetAndLoad" /><el-segmented v-model="metric" :options="metricOptions" @change="load" /><el-input v-model="keyword" clearable placeholder="搜索当前层级" style="width: 170px" @keyup.enter="load" /><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button></div></header>
    <div class="summary"><span>患者数 <b>{{ data?.summary.patientCount ?? 0 }}</b></span><span>来访人次 <b>{{ data?.summary.visitCount ?? 0 }}</b></span><span>未识别地址 <b>{{ data?.summary.unidentifiedCount ?? 0 }}</b></span></div>
    <nav class="crumbs"><el-button v-for="item in breadcrumbs" :key="`${item.level}-${item.key}`" text size="small" @click="go(item)">{{ item.label }}</el-button></nav>
    <div v-loading="loading" class="population-body"><template v-if="level !== 'PATIENT'"><VChart v-if="rows.length" class="population-chart" :option="chartOption" autoresize @click="onChartClick" /><el-empty v-else description="当前周期暂无地址数据" :image-size="56" /></template><template v-else><div v-if="patients.length" class="patient-list"><button v-for="patient in patients" :key="patient.id" type="button" class="patient-row" @click="openPatient(patient)"><span><strong>{{ patient.name || '未登记姓名' }}</strong><small>{{ patient.township || '未识别乡镇' }} · {{ patient.visitCount }} 次来访 · 最近 {{ patient.latestVisitDate || '-' }}</small></span><el-icon><ArrowRight /></el-icon></button></div><el-empty v-else description="当前区域暂无可展示的患者卡片" :image-size="56" /></template></div>
    <el-drawer v-model="drawerVisible" title="患者摘要" size="min(420px, 92vw)" destroy-on-close><template v-if="selectedPatient"><div class="patient-detail"><div class="detail-name"><strong>{{ selectedPatient.name || '未登记姓名' }}</strong><el-tag size="small" effect="plain">脱敏信息</el-tag></div><dl><dt>年龄</dt><dd>{{ selectedPatient.age || '-' }}</dd><dt>乡镇</dt><dd>{{ selectedPatient.township || '-' }}</dd><dt>就诊次数</dt><dd>{{ selectedPatient.visitCount }}</dd><dt>最近就诊</dt><dd>{{ selectedPatient.latestVisitDate || '-' }}</dd><dt>联系电话</dt><dd>{{ selectedPatient.phone || '未提供' }}</dd><dt>住址</dt><dd>{{ selectedPatient.address || '未提供' }}</dd></dl><el-button type="primary" @click="openArchive">打开健康档案</el-button></div></template></el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { ArrowRight, Refresh } from "@element-plus/icons-vue";
import { BarChart } from "echarts/charts";
import { GridComponent, TooltipComponent } from "echarts/components";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import VChart from "vue-echarts";
import type { EChartsOption } from "echarts";
import { loadAddressAnalysisApi, type AddressAnalysisLevel, type AddressAnalysisResult, type AddressPatientCard } from "@/api/modules/clinic/opsDashboard";
use([CanvasRenderer, BarChart, GridComponent, TooltipComponent]);
const router = useRouter();
const period = ref("30");
const metric = ref<"visits" | "patients">("visits");
const keyword = ref("");
const level = ref<AddressAnalysisLevel>("COUNTY");
const parentKey = ref("");
const loading = ref(false);
const data = ref<AddressAnalysisResult>();
const selectedPatient = ref<AddressPatientCard>();
const drawerVisible = ref(false);
const periodOptions = [{ label: "近30天", value: "30" }, { label: "近90天", value: "90" }, { label: "近12个月", value: "365" }];
const metricOptions = [{ label: "来访人次", value: "visits" }, { label: "患者数", value: "patients" }];
const dateRange = computed(() => { const end = new Date(); const start = new Date(end); start.setDate(start.getDate() - Number(period.value) + 1); const fmt = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`; return { from: fmt(start), to: fmt(end) }; });
const rows = computed(() => data.value?.nodes || []);
const patients = computed(() => data.value?.patients || []);
const breadcrumbs = computed(() => data.value?.breadcrumb || [{ key: "", label: "全部地区", level: "COUNTY" }]);
const chartOption = computed<EChartsOption>(() => { const source = [...rows.value].reverse(); return { grid: { left: 116, right: 48, top: 16, bottom: 18 }, tooltip: { trigger: "axis", axisPointer: { type: "shadow" } }, xAxis: { type: "value", axisLabel: { color: "#8a94a6" }, splitLine: { lineStyle: { type: "dashed", color: "#e7edf1" } } }, yAxis: { type: "category", data: source.map(row => row.label), axisLabel: { color: "#5d6875" } }, series: [{ type: "bar", barWidth: 18, data: source.map(row => ({ value: metric.value === "patients" ? row.patientCount : row.visitCount, key: row.key, itemStyle: { color: row.hasChildren ? "#176b87" : "#9abfc9", borderRadius: [0, 4, 4, 0] } })), label: { show: true, position: "right", color: "#43505c", fontSize: 11 } }] }; });
const load = async () => { loading.value = true; try { const { data: next } = await loadAddressAnalysisApi({ ...dateRange.value, level: level.value, parentKey: parentKey.value, metric: metric.value, keyword: keyword.value, page: 1, pageSize: 80 }); data.value = next; } catch (error: any) { ElMessage.warning(error?.message || "人群分析加载失败"); } finally { loading.value = false; } };
const resetAndLoad = () => { level.value = "COUNTY"; parentKey.value = ""; keyword.value = ""; void load(); };
const onChartClick = (params: any) => { const row = rows.value.find(item => item.key === params?.data?.key || item.label === params?.name); if (!row || !row.hasChildren) return; level.value = row.level; parentKey.value = row.key; keyword.value = ""; void load(); };
const go = (item: { key: string; level: string }) => { level.value = item.level as AddressAnalysisLevel; parentKey.value = item.key; keyword.value = ""; void load(); };
const openPatient = (patient: AddressPatientCard) => { selectedPatient.value = patient; drawerVisible.value = true; };
const openArchive = () => {
  if (!selectedPatient.value?.encounterId) return;
  const caseId = selectedPatient.value.patientCaseId;
  router.push({ path: "/health-archive", query: { encounterId: selectedPatient.value.encounterId, ...(caseId ? { patientCaseId: caseId } : {}) } });
};
onMounted(() => void load());
</script>

<style scoped lang="scss">
.population-panel { display: grid; gap: 12px; }.population-head, .population-controls, .summary, .patient-row, .detail-name { display: flex; align-items: center; }.population-head { justify-content: space-between; gap: 14px; flex-wrap: wrap; }.population-head h3 { margin: 0; font-size: 15px; }.population-head p { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 12px; }.population-controls { gap: 8px; flex-wrap: wrap; }.summary { gap: 20px; color: var(--el-text-color-secondary); font-size: 12px; }.summary b { margin-left: 4px; color: var(--el-text-color-primary); font-size: 16px; }.crumbs { min-height: 28px; border-top: 1px dashed var(--el-border-color-lighter); }.population-chart { width: 100%; height: 320px; }.patient-list { display: grid; gap: 6px; }.patient-row { justify-content: space-between; gap: 12px; width: 100%; padding: 12px 10px; text-align: left; background: transparent; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; cursor: pointer; }.patient-row:hover { border-color: var(--el-color-primary); }.patient-row strong, .patient-row small { display: block; }.patient-row small { margin-top: 4px; color: var(--el-text-color-secondary); font-size: 12px; }.patient-detail { display: grid; gap: 18px; }.detail-name { justify-content: space-between; }.detail-name strong { font-size: 20px; }.patient-detail dl { display: grid; grid-template-columns: 78px 1fr; gap: 10px; margin: 0; font-size: 13px; }.patient-detail dt { color: var(--el-text-color-secondary); }.patient-detail dd { margin: 0; color: var(--el-text-color-primary); word-break: break-all; }
</style>

