<template>
  <div class="patient-analysis">
    <header class="analysis-header">
      <div>
        <h1>患者与诊疗分析</h1>
        <div class="header-meta">
          <span class="status-dot" />服务器已保存记录<span class="meta-separator">/</span>{{ basisLabel }}
        </div>
      </div>
      <div class="header-actions">
        <span v-if="result" class="updated-time">更新于 {{ result.meta.generatedAt.slice(11) }}</span>
        <el-tooltip content="刷新数据" :show-after="300"
          ><el-button :icon="Refresh" :loading="loading" aria-label="刷新数据" @click="load"
        /></el-tooltip>
        <el-button :icon="Document" :disabled="!result?.detailsAllowed || stale" @click="openDetails">查看明细</el-button>
      </div>
    </header>
    <section class="filter-band" aria-label="分析筛选">
      <div class="primary-filters">
        <div class="date-field">
          <label>日期范围</label>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :clearable="false"
            @change="commit"
          />
        </div>
        <div class="filter-field">
          <label>时间粒度</label
          ><el-segmented v-model="query.granularity" :options="granularities" aria-label="时间粒度" @change="commit" />
        </div>
        <div class="filter-field">
          <label>{{ query.view === "followup" ? "随访日期基准" : "分类统计单位" }}</label>
          <el-segmented
            v-if="query.view === 'followup'"
            v-model="query.basis"
            :options="bases"
            aria-label="随访日期基准"
            @change="changeBasis"
          />
          <el-segmented v-else v-model="query.metric" :options="metrics" aria-label="分类统计单位" @change="commit" />
        </div>
        <div class="filter-buttons">
          <el-button
            :icon="Filter"
            :type="advanced ? 'primary' : 'default'"
            plain
            :aria-expanded="advanced"
            @click="advanced = !advanced"
            >筛选<span v-if="chips.length" class="filter-count">{{ chips.length }}</span></el-button
          >
          <el-tooltip content="重置全部筛选" :show-after="300"
            ><el-button :icon="RefreshLeft" aria-label="重置全部筛选" @click="reset"
          /></el-tooltip>
        </div>
      </div>
      <div v-if="advanced" class="advanced-filters">
        <div v-for="field in visibleFields" :key="field.key" class="filter-field">
          <label :for="`analysis-${field.key}`">{{ field.label }}</label>
          <el-select
            :id="`analysis-${field.key}`"
            :model-value="query.filters[field.key] || []"
            multiple
            filterable
            clearable
            collapse-tags
            collapse-tags-tooltip
            :max-collapse-tags="1"
            :placeholder="`全部${field.label}`"
            :aria-label="field.label"
            @update:model-value="setFilter(field.key, $event)"
          >
            <el-option v-for="option in options(field.key)" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </div>
        <div class="filter-field age-filter">
          <label>年龄范围（岁）</label>
          <div>
            <el-input-number
              v-model="query.ageMin"
              :min="0"
              :max="130"
              :controls="false"
              placeholder="下限"
              aria-label="年龄下限"
              @change="commit"
            /><span>至</span
            ><el-input-number
              v-model="query.ageMax"
              :min="0"
              :max="130"
              :controls="false"
              placeholder="上限"
              aria-label="年龄上限"
              @change="commit"
            />
          </div>
        </div>
      </div>
      <div v-if="chips.length" class="selected-filters" aria-label="已选条件">
        <span class="selected-label">已选</span>
        <el-tag v-for="chip in chips" :key="chip.id" closable effect="plain" @close="removeChip(chip)">{{ chip.label }}</el-tag>
      </div>
    </section>
    <nav class="analysis-tabs" role="tablist" aria-label="分析视图">
      <button
        v-for="view in views"
        :key="view.key"
        type="button"
        role="tab"
        :aria-selected="query.view === view.key"
        :class="{ active: query.view === view.key }"
        @click="changeView(view.key)"
      >
        <el-icon><component :is="view.icon" /></el-icon>{{ view.label }}
      </button>
    </nav>
    <div v-if="loading" class="analysis-message" role="status">
      <el-icon class="is-loading"><Loading /></el-icon>{{ result ? "筛选结果更新中，下方为上次结果" : "正在读取分析数据" }}
    </div>
    <div v-else-if="error" class="analysis-message is-error" role="alert">
      <el-icon><Warning /></el-icon><span>{{ error }}{{ result ? "；下方保留上次结果" : "" }}</span
      ><el-button link @click="load">重试</el-button>
    </div>
    <main :aria-busy="loading" :class="{ 'is-stale': stale }">
      <template v-if="result">
        <section class="sample-band" aria-label="当前样本">
          <div class="sample-primary">
            <span>匹配患者</span><strong>{{ number(patientCount) }}</strong
            ><small>按病例标识去重</small>
          </div>
          <div class="sample-stat">
            <span>有效来访</span><strong>{{ number(result.summary.visits) }}<small>人次</small></strong>
          </div>
          <template v-if="query.view === 'followup'">
            <div class="sample-stat">
              <span>随访节点</span><strong>{{ number(result.summary.nodes) }}<small>个</small></strong>
            </div>
            <div class="sample-stat">
              <span>区间联系留痕</span><strong>{{ number(result.summary.contacts) }}<small>次</small></strong>
            </div>
          </template>
          <div class="sample-context">
            <span>{{ result.meta.from }} 至 {{ result.meta.to }}</span
            ><small
              >{{ result.meta.basis === "visit" ? "就诊日期" : result.meta.basis === "due" ? "节点到期日期" : "实际联系日期" }} ·
              {{ granularityLabel }}</small
            >
          </div>
        </section>
        <div class="quality-line">
          <el-tooltip content="未知值保留在统计中；患者去重不使用姓名或手机号，不等同于跨系统自然人去重。" placement="top"
            ><span class="quality-label"
              ><el-icon><InfoFilled /></el-icon>字段覆盖</span
            ></el-tooltip
          >
          <span :class="{ 'is-alert': result.meta.missing.agePatients > 0 }">年龄未记录 {{ result.meta.missing.agePatients }} 位</span
          ><span :class="{ 'is-alert': result.meta.missing.regionPatients > 0 }"
            >地址层级不完整 {{ result.meta.missing.regionPatients }} 位</span
          ><span :class="{ 'is-alert': result.meta.missing.diagnosisVisits > 0 }"
            >主诊断未记录 {{ result.meta.missing.diagnosisVisits }} 人次</span
          ><span v-if="result.meta.missing.fallbackDates" :class="{ 'is-alert': true }"
            >日期回退 {{ result.meta.missing.fallbackDates }} 人次</span
          >
        </div>
        <div v-if="query.view === 'followup'" class="followup-context">
          <span>仅服务器联系留痕，截至 {{ result.meta.to }}；不包含浏览器本地通话记录。</span>
          <el-button
            link
            :class="{ 'is-danger': result.summary.unscheduledNodes > 0 }"
            :disabled="stale"
            @click="toggleUnscheduled"
            >{{ query.unscheduled ? "返回有日期记录" : `未排期节点 ${result.summary.unscheduledNodes} 个` }}</el-button
          ><small>未排期数量不受日期过滤</small>
        </div>
        <div v-if="query.view === 'clinical'" class="clinical-mode">
          <el-segmented
            v-model="clinicalMode"
            :options="[
              { label: '西医诊断与术式', value: 'western' },
              { label: '中医病名与证型', value: 'tcm' }
            ]"
            aria-label="诊疗分类"
          /><span>已保存字段 · 不以拟行术式代替实际术式</span>
        </div>
        <nav v-if="query.view === 'population' && regionBreadcrumbs.length" class="region-breadcrumbs" aria-label="地区路径">
          <button type="button" :disabled="stale" @click="setFilter('region', [])">全部地区</button>
          <template v-for="crumb in regionBreadcrumbs" :key="crumb.path"
            ><span>/</span
            ><button type="button" :disabled="stale" @click="setFilter('region', [crumb.path])">
              {{ crumb.label }}
            </button></template
          >
        </nav>
        <div v-if="visibleCharts.length" class="analysis-grid" :class="`view-${query.view}`">
          <AnalysisChart
            v-for="(chart, index) in visibleCharts"
            :key="chart.id"
            :chart="chart"
            :main="index === 0"
            :disabled="stale"
            :class="{ 'full-width': chart.kind === 'matrix' || chart.kind === 'trend' || chart.kind === 'donut' }"
            @select="onChartSelect"
          />
        </div>
        <el-empty v-else description="没有匹配记录" :image-size="70" />
      </template>
      <div v-else-if="loading" class="initial-loading"><el-skeleton :rows="8" animated /></div>
    </main>
    <el-drawer v-model="detailsVisible" title="筛选结果明细" size="min(1080px, 96vw)" destroy-on-close @closed="cancelDetails">
      <div class="detail-toolbar">
        <span>{{ details?.total ?? 0 }} 条 · {{ details?.meta.unit || result?.meta.unit }}</span
        ><el-segmented
          v-model="detailSort"
          :options="[
            { label: '时间降序', value: 'desc' },
            { label: '时间升序', value: 'asc' }
          ]"
          aria-label="明细排序"
          @change="reloadDetails"
        />
      </div>
      <p class="detail-privacy">姓名掩码展示，完整资料仅在获授权的患者档案中查看。</p>
      <div v-if="detailError" class="analysis-message is-error" role="alert">
        {{ detailError }}<el-button link @click="loadDetails">重试</el-button>
      </div>
      <div v-loading="detailsLoading" class="details-table-wrap">
        <el-table :data="details?.rows || []" height="min(62vh, 650px)" border>
          <el-table-column prop="name" label="患者" width="90" fixed />
          <el-table-column prop="date" label="日期" width="155" />
          <el-table-column label="性别 / 年龄" width="110"
            ><template #default="{ row }">{{ row.gender }} / {{ row.age }}</template></el-table-column
          >
          <el-table-column prop="region" label="地区" width="180" show-overflow-tooltip />
          <el-table-column v-if="query.view === 'followup'" prop="node" label="节点" width="70" />
          <el-table-column v-if="query.view === 'followup'" prop="firstContactAt" label="首次联系留痕" width="170" />
          <el-table-column label="主诉标签" width="150" show-overflow-tooltip
            ><template #default="{ row }">{{ row.complaintTags.join("、") || "未记录" }}</template></el-table-column
          >
          <el-table-column label="复查" width="140" show-overflow-tooltip
            ><template #default="{ row }"
              ><el-tag v-if="row.recheck" size="small" type="warning" effect="plain">复查</el-tag
              ><span v-else>—</span><small v-if="row.recheckBasis"> {{ row.recheckBasis }}</small></template
            ></el-table-column
          >
          <el-table-column prop="patientSource" label="来诊途径" width="100" show-overflow-tooltip />
          <el-table-column label="主诊断" width="150" show-overflow-tooltip
            ><template #default="{ row }">{{ row.diagnosis.join("、") }}</template></el-table-column
          >
          <el-table-column label="实际术式" width="160" show-overflow-tooltip
            ><template #default="{ row }">{{ row.operations.join("、") }}</template></el-table-column
          >
          <el-table-column prop="status" label="病历状态" width="100" />
          <el-table-column label="岗位记录状态" width="180" show-overflow-tooltip
            ><template #default="{ row }">{{ stageLabel(row.stageStatuses) }}</template></el-table-column
          >
          <el-table-column label="操作" width="96" fixed="right"
            ><template #default="{ row }"
              ><el-tooltip content="基础检查摘要"
                ><el-button
                  link
                  :icon="Document"
                  aria-label="查看基础检查摘要"
                  :disabled="detailsLoading"
                  @click="openSummary(row)" /></el-tooltip
              ><el-tooltip content="打开患者档案"
                ><el-button
                  link
                  :icon="TopRight"
                  aria-label="打开患者档案"
                  :disabled="detailsLoading"
                  @click="openArchive(row)" /></el-tooltip></template
          ></el-table-column>
        </el-table>
      </div>
      <el-pagination
        v-model:current-page="detailPage"
        :page-size="25"
        :total="details?.total || 0"
        layout="prev, pager, next"
        :pager-count="5"
        @current-change="loadDetails"
      />
    </el-drawer>
    <PatientExamSummaryDrawer v-model="summaryVisible" :encounter-id="summaryEncounterId" :fallback-name="summaryName" />
  </div>
</template>

<script setup lang="ts" name="opsDashboard">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ChatDotRound,
  DataAnalysis,
  Document,
  Filter,
  FirstAidKit,
  InfoFilled,
  Loading,
  Location,
  Refresh,
  RefreshLeft,
  TopRight,
  Warning,
  Calendar
} from "@element-plus/icons-vue";
import {
  loadPatientAnalysis,
  loadPatientAnalysisDetails,
  loadPatientAnalysisFacets,
  type AnalysisDetailResult,
  type AnalysisFacetResult,
  type AnalysisResult
} from "@/api/modules/clinic/patientAnalysis";
import AnalysisChart from "./AnalysisChart.vue";
import { useCountUp } from "./useCountUp";
import PatientExamSummaryDrawer from "@/components/PatientExamSummaryDrawer.vue";
import {
  analysisRoute,
  applyChartFilters,
  defaultAnalysisQuery,
  readAnalysisQuery,
  type AnalysisDimension,
  type AnalysisView
} from "./analysisQuery";

const router = useRouter();
const route = useRoute();
const query = ref(readAnalysisQuery(route.query));
const loading = ref(false);
const error = ref("");
const result = ref<AnalysisResult>();
const facets = ref<AnalysisFacetResult>();
const advanced = ref(false);
const clinicalMode = ref("western");
const stale = computed(() => loading.value || !!error.value);
const patientCount = useCountUp(() => result.value?.summary.patients ?? 0);
let requestSequence = 0;
let controller: AbortController | undefined;
const views: Array<{ key: AnalysisView; label: string; icon: typeof DataAnalysis }> = [
  { key: "overview", label: "数据概览", icon: DataAnalysis },
  { key: "population", label: "人群画像", icon: Location },
  { key: "clinical", label: "诊疗分布", icon: FirstAidKit },
  { key: "complaints", label: "主诉分析", icon: ChatDotRound },
  { key: "followup", label: "随访记录", icon: Calendar }
];
const granularities = [
  { label: "日", value: "day" },
  { label: "周", value: "week" },
  { label: "月", value: "month" }
];
const metrics = [
  { label: "人次", value: "visits" },
  { label: "患者数", value: "patients" }
];
const bases = [
  { label: "节点到期", value: "due" },
  { label: "实际联系", value: "contact" }
];
const fields: Array<{ key: AnalysisDimension; label: string }> = [
  { key: "gender", label: "性别" },
  { key: "region", label: "地区" },
  { key: "diagnosis", label: "主诊断" },
  { key: "operation", label: "实际术式" },
  { key: "complaint", label: "主诉" },
  { key: "status", label: "病历状态" },
  { key: "tcmDisease", label: "中医病名" },
  { key: "syndrome", label: "中医证型" }
];
const dimensionLabels: Record<string, string> = {
  ...Object.fromEntries(fields.map(field => [field.key, field.label])),
  ageBand: "年龄段",
  primaryOperation: "实际主术式",
  contactState: "联系留痕",
  delayBand: "首次联系间隔"
};
const visibleFields = computed(() =>
  fields.filter(f => !["tcmDisease", "syndrome"].includes(f.key) || query.value.view === "clinical")
);
const dateRange = computed<[string, string]>({
  get: (): [string, string] => [query.value.from, query.value.to],
  set: value => {
    if (value?.length === 2) [query.value.from, query.value.to] = value;
  }
});
const basisLabel = computed(() =>
  query.value.view === "followup" ? (query.value.basis === "due" ? "按节点到期日期" : "按实际联系日期") : "按就诊日期"
);
const granularityLabel = computed(
  () => granularities.find(item => item.value === result.value?.meta.granularity)?.label + "粒度"
);
const visibleCharts = computed(() =>
  (result.value?.charts || []).filter(
    chart =>
      query.value.view !== "clinical" ||
      (clinicalMode.value === "tcm" ? ["tcm", "syndrome"].includes(chart.id) : !["tcm", "syndrome"].includes(chart.id))
  )
);
const regionBreadcrumbs = computed(() => {
  const regions = query.value.filters.region || [];
  return regions.length === 1
    ? regions[0].split(" / ").map((label, index, parts) => ({ label, path: parts.slice(0, index + 1).join(" / ") }))
    : [];
});
type Chip = { id: string; key: string; value: string; label: string };
const chips = computed<Chip[]>(() => {
  const rows: Chip[] = [];
  Object.entries(query.value.filters).forEach(([key, values]) =>
    (values || []).forEach(value => {
      const label = options(key).find(item => item.value === value)?.label || value;
      rows.push({ id: `${key}:${value}`, key, value, label: `${dimensionLabels[key] || key}：${label}` });
    })
  );
  if (query.value.ageMin !== undefined || query.value.ageMax !== undefined)
    rows.push({ id: "age", key: "age", value: "", label: `年龄：${query.value.ageMin ?? 0}-${query.value.ageMax ?? 130}岁` });
  if (query.value.unscheduled) rows.push({ id: "unscheduled", key: "unscheduled", value: "", label: "未排期（不受日期过滤）" });
  return rows;
});
function options(key: string) {
  const existing = facets.value?.facets[key] || [];
  const selected = query.value.filters[key as AnalysisDimension] || [];
  return [
    ...existing,
    ...selected.filter(value => !existing.some(item => item.value === value)).map(value => ({ value, label: value }))
  ];
}
function number(value: number) {
  return value.toLocaleString("zh-CN");
}
function commit() {
  void router.replace({ query: analysisRoute(query.value) });
}
function setFilter(key: AnalysisDimension, values: string[]) {
  query.value.filters = { ...query.value.filters, [key]: values };
  commit();
}
function removeChip(chip: Chip) {
  if (chip.key === "age") {
    query.value.ageMin = undefined;
    query.value.ageMax = undefined;
  } else if (chip.key === "unscheduled") query.value.unscheduled = false;
  else {
    const key = chip.key as AnalysisDimension;
    query.value.filters[key] = query.value.filters[key]?.filter(value => value !== chip.value);
  }
  commit();
}
function reset() {
  query.value = { ...defaultAnalysisQuery(), view: query.value.view };
  commit();
}
function changeView(view: AnalysisView) {
  query.value.view = view;
  if (view !== "followup") {
    delete query.value.filters.contactState;
    delete query.value.filters.delayBand;
    query.value.unscheduled = false;
  }
  commit();
}
function changeBasis() {
  delete query.value.filters.delayBand;
  delete query.value.filters.contactState;
  query.value.unscheduled = false;
  commit();
}
function toggleUnscheduled() {
  query.value.unscheduled = !query.value.unscheduled;
  delete query.value.filters.delayBand;
  commit();
}
function onChartSelect(filters: Record<string, string[]>) {
  if (stale.value) return;
  const region = filters.region;
  if (region?.length === 1 && region[0].split(" / ").length >= 3 && query.value.filters.region?.[0] === region[0]) {
    if (result.value?.detailsAllowed) openDetails();
    return;
  }
  query.value = applyChartFilters(query.value, filters);
  // 主诉图的每一行都是叶级（无更深维度），点击即落到患者明细
  if (query.value.view === "complaints" && !filters.from && !filters.to && result.value?.detailsAllowed) openDetails();
  commit();
}
async function load() {
  const sequence = ++requestSequence;
  controller?.abort();
  controller = new AbortController();
  const signal = controller.signal;
  loading.value = true;
  error.value = "";
  detailsVisible.value = false;
  cancelDetails();
  try {
    const snapshot = readAnalysisQuery(analysisRoute(query.value));
    const [next, nextFacets] = await Promise.all([
      loadPatientAnalysis(snapshot, signal),
      loadPatientAnalysisFacets(snapshot, signal)
    ]);
    if (sequence !== requestSequence) return;
    result.value = next;
    facets.value = nextFacets;
  } catch (caught) {
    if (sequence !== requestSequence || signal.aborted) return;
    error.value = caught instanceof Error ? caught.message : "分析数据加载失败";
  } finally {
    if (sequence === requestSequence) loading.value = false;
  }
}
const detailsVisible = ref(false);
const details = ref<AnalysisDetailResult>();
const detailsLoading = ref(false);
const detailError = ref("");
const detailPage = ref(1);
const detailSort = ref<"asc" | "desc">("desc");
let detailController: AbortController | undefined;
let detailSequence = 0;
function cancelDetails() {
  detailSequence++;
  detailController?.abort();
  detailsLoading.value = false;
}
function openDetails() {
  if (!result.value?.detailsAllowed || stale.value) return;
  detailPage.value = 1;
  details.value = undefined;
  detailsVisible.value = true;
  void loadDetails();
}
function reloadDetails() {
  detailPage.value = 1;
  void loadDetails();
}
async function loadDetails() {
  const sequence = ++detailSequence;
  detailController?.abort();
  detailController = new AbortController();
  const signal = detailController.signal;
  detailsLoading.value = true;
  detailError.value = "";
  try {
    const next = await loadPatientAnalysisDetails(query.value, detailPage.value, detailSort.value, signal);
    if (sequence === detailSequence) details.value = next;
  } catch (caught) {
    if (sequence === detailSequence && !signal.aborted) {
      details.value = undefined;
      detailError.value = caught instanceof Error ? caught.message : "明细加载失败";
    }
  } finally {
    if (sequence === detailSequence) detailsLoading.value = false;
  }
}
function openArchive(row: { encounterId?: unknown; patientCaseId?: unknown }) {
  if (typeof row.encounterId !== "string" || !row.encounterId) return;
  detailsVisible.value = false;
  const caseId = typeof row.patientCaseId === "string" && row.patientCaseId ? row.patientCaseId : undefined;
  void router.push({ path: "/health-archive", query: { encounterId: row.encounterId, ...(caseId ? { patientCaseId: caseId } : {}) } });
}
const summaryVisible = ref(false);
const summaryEncounterId = ref("");
const summaryName = ref("");
function openSummary(row: { encounterId?: unknown; name?: unknown }) {
  if (typeof row.encounterId !== "string" || !row.encounterId) return;
  summaryEncounterId.value = row.encounterId;
  summaryName.value = typeof row.name === "string" ? row.name : "";
  summaryVisible.value = true;
}
function stageLabel(statuses: Record<string, string>) {
  const labels: Record<string, string> = {
    DOCTOR: "医生",
    SURGERY: "手术",
    TCM: "中医",
    DRAFT: "草稿",
    COMPLETED: "已完成",
    RETURNED: "已退回",
    SKIPPED: "已跳过",
    PENDING_CONFIRMATION: "待确认"
  };
  return ["DOCTOR", "SURGERY", "TCM"]
    .filter(key => statuses[key])
    .map(key => `${labels[key]}：${labels[statuses[key]] || statuses[key]}`)
    .join("；");
}
watch(
  () => route.fullPath,
  () => {
    if (route.path !== "/ops/dashboard") return;
    query.value = readAnalysisQuery(route.query);
    void load();
  },
  { immediate: true }
);
onBeforeUnmount(() => {
  controller?.abort();
  requestSequence++;
  cancelDetails();
});
</script>

<style scoped lang="scss">
.patient-analysis {
  max-width: 1520px;
  margin: 0 auto;
  padding: 22px 28px 36px;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  letter-spacing: 0;
}
.analysis-header,
.header-actions,
.header-meta,
.primary-filters,
.filter-buttons,
.selected-filters,
.sample-band,
.quality-line,
.followup-context,
.clinical-mode,
.detail-toolbar {
  display: flex;
  align-items: center;
}
.analysis-header {
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 24px;
}
.analysis-header h1 {
  margin: 0 0 9px;
  font-size: 23px;
  font-weight: 650;
  line-height: 1.4;
}
.header-meta {
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  flex-wrap: wrap;
}
.status-dot {
  width: 6px;
  height: 6px;
  background: #67a382;
  border-radius: 50%;
}
.meta-separator {
  color: var(--el-border-color);
}
.header-actions {
  gap: 8px;
  flex-wrap: wrap;
}
.header-actions :deep(.el-button + .el-button),
.filter-buttons :deep(.el-button + .el-button) {
  margin-left: 0;
}
.updated-time {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-right: 6px;
}
.filter-band {
  padding: 18px;
  background: var(--el-fill-color-lighter);
  border-top: 1px solid var(--el-border-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.primary-filters {
  gap: 18px;
  align-items: flex-end;
  flex-wrap: wrap;
}
.date-field,
.filter-field {
  display: grid;
  gap: 7px;
  min-width: 0;
}
.date-field {
  flex: 1 1 305px;
  max-width: 370px;
}
.date-field :deep(.el-date-editor) {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}
.filter-field label,
.date-field label {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.filter-buttons {
  gap: 6px;
  margin-left: auto;
}
.filter-count {
  margin-left: 7px;
  font-variant-numeric: tabular-nums;
}
.advanced-filters {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 15px 16px;
  padding-top: 20px;
  margin-top: 18px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.age-filter > div {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.age-filter :deep(.el-input-number) {
  width: 100%;
  min-width: 0;
}
.selected-filters {
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 15px;
}
.selected-label {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-right: 3px;
}
.selected-filters :deep(.el-tag) {
  max-width: 100%;
  height: auto;
  min-height: 25px;
  padding: 3px 7px;
}
.selected-filters :deep(.el-tag__content) {
  white-space: normal;
  overflow-wrap: anywhere;
}
.analysis-tabs {
  display: flex;
  gap: 24px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding-top: 10px;
  overflow-x: auto;
}
.analysis-tabs button {
  display: inline-flex;
  position: relative;
  align-items: center;
  justify-content: center;
  gap: 7px;
  flex-shrink: 0;
  padding: 16px 2px 14px;
  background: none;
  border: 0;
  font: inherit;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  transition: color 0.18s ease-out;
}
.analysis-tabs button .el-icon {
  transition: transform 0.18s ease-out;
}
.analysis-tabs button:hover {
  color: #247b91;
}
.analysis-tabs button:hover .el-icon {
  transform: translateY(-1px);
}
.analysis-tabs button.active {
  color: #247b91;
  font-weight: 600;
}
.analysis-tabs button.active .el-icon {
  transform: translateY(-1px);
}
.analysis-tabs button::after {
  content: "";
  display: block;
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 2px;
  background: #247b91;
  border-radius: 2px;
  transform: scaleX(0);
  transform-origin: left;
  transition: transform 0.22s ease-out;
}
.analysis-tabs button.active::after {
  transform: scaleX(1);
}
.analysis-tabs button:focus-visible {
  outline: 2px solid #247b91;
  outline-offset: -3px;
}
.analysis-message {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
  color: #247b91;
  font-size: 12px;
  line-height: 1.6;
}
.analysis-message.is-error {
  color: #a9584d;
}
.is-stale {
  opacity: 0.5;
  pointer-events: none;
}
.sample-band {
  gap: 32px;
  padding: 25px 0 20px;
  align-items: stretch;
  flex-wrap: wrap;
}
.sample-primary {
  display: grid;
  grid-template-columns: auto auto;
  gap: 5px 14px;
  padding-right: 30px;
  border-right: 1px solid var(--el-border-color-lighter);
}
.sample-primary > span,
.sample-stat > span {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.sample-primary strong {
  font-size: 34px;
  font-weight: 600;
  line-height: 1.1;
  grid-row: span 2;
  color: #247b91;
  font-variant-numeric: tabular-nums;
}
.sample-primary small {
  font-size: 10px;
  color: var(--el-text-color-placeholder);
}
.sample-stat {
  display: grid;
  gap: 8px;
}
.sample-stat strong {
  font-size: 23px;
  line-height: 1;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
}
.sample-stat small {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.sample-context {
  margin-left: auto;
  display: grid;
  align-content: center;
  gap: 7px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  text-align: right;
}
.sample-context small {
  color: var(--el-text-color-placeholder);
  font-size: 11px;
}
.quality-line {
  gap: 18px;
  flex-wrap: wrap;
  padding-bottom: 19px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
.quality-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #6e8578;
}
.quality-line .is-alert {
  color: #b94d45;
  font-weight: 600;
}
.followup-context .el-button.is-danger {
  color: #b94d45;
  font-weight: 600;
}
.analysis-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 34px;
}
.full-width {
  grid-column: 1 / -1;
}
.followup-context {
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px 0 20px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.followup-context small {
  font-size: 11px;
}
.clinical-mode {
  gap: 14px;
  padding-bottom: 20px;
  flex-wrap: wrap;
}
.clinical-mode > span {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.region-breadcrumbs {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 0 0 15px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.region-breadcrumbs button {
  background: none;
  border: 0;
  padding: 3px 0;
  color: #247b91;
  font: inherit;
  cursor: pointer;
}
.initial-loading {
  padding: 38px 0;
}
.detail-toolbar {
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 13px;
}
.detail-privacy {
  margin: 14px 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.details-table-wrap {
  overflow: auto;
}
:deep(.el-pagination) {
  justify-content: flex-end;
  margin-top: 16px;
}
@media (max-width: 1050px) {
  .patient-analysis {
    padding: 20px;
  }
  .advanced-filters {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .sample-context {
    display: none;
  }
  .sample-band {
    gap: 24px;
  }
}
@media (max-width: 760px) {
  .analysis-header {
    align-items: flex-start;
    flex-wrap: wrap;
    padding-bottom: 18px;
  }
  .analysis-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .advanced-filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .analysis-tabs {
    gap: 18px;
  }
  .sample-band {
    gap: 20px;
  }
}
@media (max-width: 480px) {
  .patient-analysis {
    padding: 16px 12px 26px;
  }
  .analysis-header h1 {
    font-size: 21px;
  }
  .updated-time {
    display: none;
  }
  .filter-band {
    padding: 12px;
  }
  .primary-filters {
    gap: 14px 10px;
  }
  .date-field {
    flex-basis: 100%;
    max-width: none;
  }
  .filter-buttons {
    margin-left: 0;
  }
  .advanced-filters {
    grid-template-columns: minmax(0, 1fr);
  }
  .sample-band {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 22px 18px;
  }
  .sample-primary {
    grid-column: 1 / -1;
    justify-content: start;
    padding-right: 0;
    border-right: 0;
  }
  .sample-primary strong {
    margin-left: 18px;
  }
  .quality-line {
    gap: 8px 14px;
  }
  .analysis-tabs button {
    font-size: 12px;
  }
}
</style>
