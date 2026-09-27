<template>
  <div class="ops-shell">
    <header class="ops-header">
      <div><span class="eyebrow">医疗运营控制台</span><h2>运营数据看板</h2><p>{{ periodLabel }} · 更新于 {{ data?.period.generatedAt || '加载中' }}</p></div>
      <div class="header-actions"><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" @change="load" /><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button></div>
    </header>
    <nav class="view-tabs"><button v-for="item in views" :key="item.key" type="button" :class="{ active: activeView === item.key }" @click="selectView(item.key)"><el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span><small>{{ item.note }}</small></button></nav>
    <main v-loading="loading" class="ops-content">
      <OpsOverviewView v-if="activeView === 'overview' && data" :data="data" @attention="handleAttention" @granularity="handleGranularity" />
      <OpsFollowUpView v-else-if="activeView === 'followup'" />
      <OpsPopulationView v-else-if="activeView === 'population'" />
      <el-empty v-else description="暂无运营数据" :image-size="70" />
    </main>
  </div>
</template>

<script setup lang="ts" name="opsDashboard">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { DataAnalysis, DataLine, Location, Refresh } from "@element-plus/icons-vue";
import { loadOpsDashboardApi, type OpsDashboardAnalysisResult } from "@/api/modules/clinic/opsDashboard";
import OpsOverviewView from "./OpsOverviewView.vue";
import OpsFollowUpView from "./OpsFollowUpView.vue";
import OpsPopulationView from "./AddressDrilldownPanel.vue";

const router = useRouter();
const route = useRoute();
const loading = ref(false);
const data = ref<OpsDashboardAnalysisResult>();
const activeView = ref(String(route.query.view || "overview"));
const granularity = ref<"day" | "week" | "month">("day");
const dateRange = ref<[string, string]>(monthRange());
const views = [
  { key: "overview", label: "经营概览", note: "来访、患者与趋势", icon: DataAnalysis },
  { key: "followup", label: "随访运营", note: "完成、按时与逾期", icon: DataLine },
  { key: "population", label: "人群分析", note: "住址分层与下钻", icon: Location }
];
const periodLabel = computed(() => `${dateRange.value[0]} 至 ${dateRange.value[1]}`);
function monthRange(): [string, string] { const now = new Date(); const fmt = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`; return [fmt(new Date(now.getFullYear(), now.getMonth(), 1)), fmt(now)]; }
const load = async () => { loading.value = true; try { const { data: next } = await loadOpsDashboardApi({ from: dateRange.value[0], to: dateRange.value[1], granularity: granularity.value, view: activeView.value, months: 1 }); data.value = next; } catch (error: any) { ElMessage.error(error?.message || "运营概览加载失败"); } finally { loading.value = false; } };
const handleGranularity = (value: "day" | "week" | "month") => { granularity.value = value; void load(); };
const handleAttention = (key: string) => { if (key === "overdue" || key === "today") selectView("followup"); else if (key === "address") selectView("population"); };
const selectView = (view: string) => { activeView.value = view; syncView(); if (view === "overview") void load(); };
const syncView = () => { router.replace({ query: { ...route.query, view: activeView.value } }); };
const onViewChange = () => { syncView(); if (activeView.value === "overview") void load(); };
void onViewChange;
onMounted(() => void load());
</script>

<style scoped lang="scss">
.ops-shell { display: grid; gap: 16px; max-width: 1440px; padding: 18px; margin: 0 auto; }.ops-header { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; flex-wrap: wrap; }.eyebrow { color: #176b87; font-size: 11px; font-weight: 700; letter-spacing: .08em; }.ops-header h2 { margin: 5px 0 0; font-size: 22px; color: var(--el-text-color-primary); }.ops-header p { margin: 5px 0 0; color: var(--el-text-color-secondary); font-size: 12px; }.header-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }.view-tabs { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; padding: 5px; background: var(--el-fill-color-light); border-radius: 8px; }.view-tabs button { display: grid; grid-template-columns: auto 1fr; gap: 2px 8px; align-items: center; padding: 12px 14px; text-align: left; color: var(--el-text-color-secondary); background: transparent; border: 0; border-radius: 6px; cursor: pointer; }.view-tabs button .el-icon { grid-row: span 2; font-size: 18px; }.view-tabs button span { font-size: 13px; font-weight: 700; }.view-tabs button small { font-size: 11px; }.view-tabs button.active { color: #176b87; background: var(--el-bg-color); box-shadow: 0 1px 5px rgba(31, 55, 67, .08); }.ops-content { min-height: 360px; }@media (max-width: 700px) { .ops-shell { padding: 12px; }.view-tabs button { padding: 10px 8px; }.view-tabs button small { display: none; }.header-actions, .header-actions :deep(.el-date-editor) { width: 100%; } }
</style>

