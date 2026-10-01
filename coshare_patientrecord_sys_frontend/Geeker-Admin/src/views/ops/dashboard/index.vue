<template>
  <div class="patient-analysis">
    <!-- 顶部细进度条：加载时内容保持原位，不再整页半透明 -->
    <div
      class="pa-progress"
      :class="{ 'is-active': loading }"
      role="progressbar"
      aria-label="数据加载中"
      :aria-hidden="!loading"
    />

    <header class="pa-header">
      <div class="pa-title">
        <span class="pa-eyebrow"
          ><i class="pa-live-dot" :class="{ 'is-error': !!error }" />服务器已保存记录 · {{ basisLabel }}</span
        >
        <h1>患者与诊疗分析</h1>
      </div>
      <div class="pa-header-actions">
        <span v-if="result" class="pa-updated">更新于 {{ result.meta.generatedAt.slice(11, 16) }}</span>
        <el-tooltip content="刷新数据" :show-after="300">
          <button type="button" class="pa-icon-btn" :class="{ 'is-spinning': loading }" aria-label="刷新数据" @click="load">
            <el-icon><Refresh /></el-icon>
          </button>
        </el-tooltip>
        <el-button type="primary" :icon="Document" :disabled="!result?.detailsAllowed || stale" @click="openDetails"
          >查看明细</el-button
        >
      </div>
    </header>

    <nav ref="tabsEl" class="pa-tabs" role="tablist" aria-label="分析视图">
      <span
        class="pa-tabs-thumb"
        :class="{ 'is-ready': tabThumb.ready }"
        :style="{ width: `${tabThumb.w}px`, transform: `translateX(${tabThumb.x}px)` }"
        aria-hidden="true"
      />
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

    <section class="pa-toolbar" aria-label="分析筛选">
      <div class="pa-toolbar-row">
        <div class="pa-field pa-date">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="→"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :clearable="false"
            aria-label="日期范围"
            @change="commit"
          />
        </div>
        <span class="pa-divider" aria-hidden="true" />
        <div class="pa-field">
          <span class="pa-field-label">粒度</span>
          <el-segmented v-model="query.granularity" :options="granularities" aria-label="时间粒度" @change="commit" />
        </div>
        <div class="pa-field">
          <span class="pa-field-label">{{ query.view === "followup" ? "日期基准" : "统计单位" }}</span>
          <el-segmented
            v-if="query.view === 'followup'"
            v-model="query.basis"
            :options="bases"
            aria-label="随访日期基准"
            @change="changeBasis"
          />
          <el-segmented v-else v-model="query.metric" :options="metrics" aria-label="分类统计单位" @change="commit" />
        </div>
        <div class="pa-toolbar-end">
          <button
            type="button"
            class="pa-filter-btn"
            :class="{ 'is-open': advanced, 'has-value': chips.length }"
            :aria-expanded="advanced"
            aria-controls="pa-advanced"
            @click="advanced = !advanced"
          >
            <el-icon><Filter /></el-icon>筛选<span v-if="chips.length" class="pa-badge">{{ chips.length }}</span
            ><el-icon class="pa-caret"><ArrowDown /></el-icon>
          </button>
          <el-tooltip content="重置全部筛选" :show-after="300">
            <button type="button" class="pa-icon-btn" aria-label="重置全部筛选" @click="reset">
              <el-icon><RefreshLeft /></el-icon>
            </button>
          </el-tooltip>
        </div>
      </div>

      <div id="pa-advanced" class="pa-collapse" :class="{ 'is-open': advanced }" :inert="!advanced">
        <div class="pa-collapse-inner">
          <div class="pa-advanced">
            <div v-for="field in visibleFields" :key="field.key" class="pa-adv-field">
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
            <div class="pa-adv-field pa-age">
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
                /><span>—</span
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
        </div>
      </div>

      <TransitionGroup v-if="chips.length" tag="div" name="pa-chip" class="pa-chips" aria-label="已选条件">
        <span v-for="chip in chips" :key="chip.id" class="pa-chip">
          {{ chip.label }}
          <button type="button" :aria-label="`移除条件 ${chip.label}`" @click="removeChip(chip)">×</button>
        </span>
        <button key="__clear" type="button" class="pa-chip-clear" @click="reset">清空</button>
      </TransitionGroup>
    </section>

    <Transition name="pa-fade">
      <div v-if="error" class="pa-alert" role="alert">
        <el-icon><Warning /></el-icon>
        <span>{{ error }}{{ result ? "，下方保留上次结果" : "" }}</span>
        <button type="button" @click="load">重试</button>
      </div>
    </Transition>

    <main class="pa-main" :aria-busy="loading">
      <template v-if="result">
        <section class="pa-kpis" :class="{ 'is-busy': stale }" aria-label="当前样本">
          <div class="pa-card pa-kpi is-primary" :style="{ '--pa-enter-index': 0 }">
            <span class="pa-kpi-label">匹配患者</span>
            <strong>{{ number(patientCount) }}</strong>
            <small>按病例标识去重</small>
          </div>
          <div class="pa-card pa-kpi" :style="{ '--pa-enter-index': 1 }">
            <span class="pa-kpi-label">有效来访</span>
            <strong>{{ number(visitCount) }}<em>人次</em></strong>
            <small v-if="patientCount"
              >人均 {{ (result.summary.visits / Math.max(1, result.summary.patients)).toFixed(1) }} 次</small
            >
          </div>
          <template v-if="query.view === 'followup'">
            <div class="pa-card pa-kpi" :style="{ '--pa-enter-index': 2 }">
              <span class="pa-kpi-label">随访节点</span>
              <strong>{{ number(nodeCount) }}<em>个</em></strong>
              <small>已联系 {{ number(result.summary.contactedNodes) }} 个</small>
            </div>
            <div class="pa-card pa-kpi" :style="{ '--pa-enter-index': 3 }">
              <span class="pa-kpi-label">区间联系留痕</span>
              <strong>{{ number(contactCount) }}<em>次</em></strong>
              <small>仅服务器留痕</small>
            </div>
          </template>
          <div v-else class="pa-card pa-kpi" :style="{ '--pa-enter-index': 2 }">
            <span class="pa-kpi-label">统计区间</span>
            <strong>{{ dayCount }}<em>天</em></strong>
            <small>{{ result.meta.from.slice(5) }} → {{ result.meta.to.slice(5) }} · {{ granularityLabel }}</small>
          </div>
          <el-popover placement="bottom-end" :width="280" trigger="click" popper-class="pa-quality-popper">
            <template #reference>
              <button
                type="button"
                class="pa-card pa-kpi pa-quality"
                :class="{ 'has-issue': qualityIssues }"
                :style="{ '--pa-enter-index': 4 }"
                aria-haspopup="dialog"
              >
                <span class="pa-kpi-label"
                  >数据质量<el-icon><component :is="qualityIssues ? InfoFilled : CircleCheck" /></el-icon
                ></span>
                <strong>{{ qualityIssues ? `${qualityIssues} 项` : "完整" }}</strong>
                <small>{{ qualityIssues ? "存在字段缺失，点击查看" : "关键字段均已记录" }}</small>
              </button>
            </template>
            <div class="pa-quality-pop">
              <p class="pa-quality-title">字段覆盖</p>
              <ul>
                <li v-for="item in qualityItems" :key="item.label" :class="{ 'is-alert': item.value > 0 }">
                  <span>{{ item.label }}</span
                  ><b>{{ item.value }} {{ item.unit }}</b>
                </li>
              </ul>
              <p class="pa-quality-note">未知值保留在统计中；患者去重不使用姓名或手机号，不等同于跨系统自然人去重。</p>
            </div>
          </el-popover>
        </section>

        <div v-if="query.view === 'followup'" class="pa-subbar">
          <span class="pa-subbar-text">仅服务器联系留痕，截至 {{ result.meta.to }}；不含浏览器本地通话记录</span>
          <button
            type="button"
            class="pa-pill"
            :class="{ 'is-danger': !query.unscheduled && result.summary.unscheduledNodes > 0, 'is-active': query.unscheduled }"
            :disabled="stale"
            @click="toggleUnscheduled"
          >
            {{ query.unscheduled ? "← 返回有日期记录" : `未排期节点 ${result.summary.unscheduledNodes} 个` }}
          </button>
          <small>未排期数量不受日期过滤</small>
        </div>
        <div v-if="query.view === 'clinical'" class="pa-subbar">
          <el-segmented
            v-model="clinicalMode"
            :options="[
              { label: '西医诊断与术式', value: 'western' },
              { label: '中医病名与证型', value: 'tcm' }
            ]"
            aria-label="诊疗分类"
          />
          <span class="pa-subbar-text">已保存字段 · 不以拟行术式代替实际术式</span>
        </div>
        <nav v-if="query.view === 'population' && regionBreadcrumbs.length" class="pa-crumbs" aria-label="地区路径">
          <button type="button" :disabled="stale" @click="setFilter('region', [])">全部地区</button>
          <TransitionGroup name="pa-chip">
            <span v-for="(crumb, crumbIndex) in regionBreadcrumbs" :key="crumb.path" class="pa-crumb">
              <i aria-hidden="true">›</i>
              <button
                type="button"
                :disabled="stale"
                :aria-current="crumbIndex === regionBreadcrumbs.length - 1 ? 'location' : undefined"
                @click="setFilter('region', [crumb.path])"
              >
                {{ crumb.label }}
              </button>
            </span>
          </TransitionGroup>
        </nav>

        <div v-if="visibleCharts.length" :key="`${query.view}-${clinicalMode}`" class="pa-grid">
          <AnalysisChart
            v-for="(chart, index) in visibleCharts"
            :key="chart.id"
            :chart="chart"
            :main="index === 0"
            :index="index + 2"
            :disabled="stale"
            :class="{ 'is-wide': isWide(index) }"
            @select="onChartSelect"
          />
        </div>
        <div v-else class="pa-card pa-empty">
          <span class="pa-empty-mark" aria-hidden="true" />
          <strong>没有匹配记录</strong>
          <small>当前条件下没有可统计的数据，可以放宽日期或清空筛选</small>
          <el-button v-if="chips.length" size="small" @click="reset">清空筛选</el-button>
        </div>
      </template>

      <div v-else-if="loading" class="pa-skeleton" aria-hidden="true">
        <div class="pa-kpis">
          <div v-for="n in 4" :key="n" class="pa-card pa-sk-kpi"><i /><b /><i /></div>
        </div>
        <div class="pa-grid">
          <div class="pa-card pa-sk-chart is-wide"><i /><b /></div>
          <div class="pa-card pa-sk-chart"><i /><b /></div>
          <div class="pa-card pa-sk-chart"><i /><b /></div>
        </div>
      </div>
    </main>

    <el-drawer
      v-model="detailsVisible"
      title="筛选结果明细"
      size="min(1080px, 96vw)"
      class="pa-drawer"
      destroy-on-close
      @closed="cancelDetails"
    >
      <div class="pa-detail-toolbar">
        <span
          ><b>{{ number(details?.total ?? 0) }}</b> 条 · {{ details?.meta.unit || result?.meta.unit }}</span
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
      <p class="pa-detail-privacy">
        <el-icon><InfoFilled /></el-icon>姓名掩码展示，完整资料仅在获授权的患者档案中查看。
      </p>
      <div v-if="detailError" class="pa-alert" role="alert">
        <el-icon><Warning /></el-icon><span>{{ detailError }}</span
        ><button type="button" @click="loadDetails">重试</button>
      </div>
      <div v-loading="detailsLoading" class="pa-details-table">
        <el-table :data="details?.rows || []" height="min(62vh, 650px)">
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
              ><el-tag v-if="row.recheck" size="small" type="warning" effect="light" round>复查</el-tag
              ><span v-else class="pa-muted">—</span><small v-if="row.recheckBasis"> {{ row.recheckBasis }}</small></template
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
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ChatDotRound,
  DataAnalysis,
  Document,
  Filter,
  FirstAidKit,
  InfoFilled,
  Location,
  Refresh,
  RefreshLeft,
  TopRight,
  Warning,
  Calendar,
  CircleCheck,
  ArrowDown
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
const visitCount = useCountUp(() => result.value?.summary.visits ?? 0);
const nodeCount = useCountUp(() => result.value?.summary.nodes ?? 0);
const contactCount = useCountUp(() => result.value?.summary.contacts ?? 0);
/** 字段覆盖：收进“数据质量”卡片的 popover，只在有缺失时提示 */
const qualityItems = computed(() => {
  const missing = result.value?.meta.missing;
  if (!missing) return [];
  return [
    { label: "年龄未记录", value: missing.agePatients, unit: "位" },
    { label: "地址层级不完整", value: missing.regionPatients, unit: "位" },
    { label: "主诊断未记录", value: missing.diagnosisVisits, unit: "人次" },
    { label: "日期回退", value: missing.fallbackDates, unit: "人次" }
  ];
});
const qualityIssues = computed(() => qualityItems.value.filter(item => item.value > 0).length);
const dayCount = computed(() => {
  if (!result.value) return 0;
  const ms = new Date(result.value.meta.to).getTime() - new Date(result.value.meta.from).getTime();
  return Number.isFinite(ms) ? Math.round(ms / 86400000) + 1 : 0;
});
/** 卡片布局：趋势/矩阵通栏；半宽卡落单时最后一张补成通栏，避免右侧留白 */
function isWide(index: number) {
  const list = visibleCharts.value;
  const kind = list[index]?.kind;
  if (kind === "trend" || kind === "matrix") return true;
  let run = 0;
  for (let i = index; i >= 0 && list[i].kind !== "trend" && list[i].kind !== "matrix"; i--) run++;
  const next = list[index + 1];
  const runEnds = !next || next.kind === "trend" || next.kind === "matrix";
  return runEnds && run % 2 === 1;
}
// 视图切换：共享滑块，定位到当前 tab
const tabsEl = ref<HTMLElement>();
const tabThumb = ref({ x: 0, w: 0, ready: false });
function syncThumb() {
  const el = tabsEl.value?.querySelector<HTMLElement>("button.active");
  if (el) tabThumb.value = { x: el.offsetLeft, w: el.offsetWidth, ready: true };
}
let tabsObserver: ResizeObserver | undefined;
onMounted(() => {
  void nextTick(syncThumb);
  if (tabsEl.value) {
    tabsObserver = new ResizeObserver(syncThumb);
    tabsObserver.observe(tabsEl.value);
  }
});
watch(
  () => query.value.view,
  () => void nextTick(syncThumb)
);
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
  void router.push({
    path: "/health-archive",
    query: { encounterId: row.encounterId, ...(caseId ? { patientCaseId: caseId } : {}) }
  });
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
  tabsObserver?.disconnect();
  controller?.abort();
  requestSequence++;
  cancelDetails();
});
</script>

<style scoped lang="scss">
/* ── 设计 token：只作用于本页，子组件通过 CSS 变量继承 ── */
.patient-analysis {
  --pa-bg: #f5f7f8;
  --pa-surface: #ffffff;
  --pa-subtle: #f1f4f5;
  --pa-subtle-strong: #e6ebed;
  --pa-border: #e8ecee;
  --pa-border-strong: #d3dadd;
  --pa-text: #1f2a30;
  --pa-text-2: #4a565d;
  --pa-text-3: #7a868d;
  --pa-brand: #1f7a8c;
  --pa-brand-soft: rgb(31 122 140 / 10%);
  --pa-danger: #c2410c;
  --pa-danger-soft: rgb(194 65 12 / 8%);
  --pa-warn: #b7791f;
  --pa-shadow: 0 1px 2px rgb(16 24 40 / 4%);
  --pa-shadow-hover: 0 4px 16px rgb(16 24 40 / 7%);
  --pa-radius: 12px;
  --pa-ease: cubic-bezier(0.22, 1, 0.36, 1);
  --el-color-primary: var(--pa-brand);

  position: relative;
  box-sizing: border-box;
  min-height: 100%;
  padding: 20px 24px 32px;
  font-variant-numeric: tabular-nums;
  color: var(--pa-text);
  background: var(--pa-bg);
}
:global(html.dark) .patient-analysis {
  --pa-bg: #141a1d;
  --pa-surface: #1d2326;
  --pa-subtle: #242b2f;
  --pa-subtle-strong: #2e363b;
  --pa-border: #2a3236;
  --pa-border-strong: #3a4449;
  --pa-text: #e4e9eb;
  --pa-text-2: #b6c0c4;
  --pa-text-3: #8b979d;
  --pa-brand: #4fb3c4;
  --pa-brand-soft: rgb(79 179 196 / 14%);
  --pa-danger: #f08a5d;
  --pa-danger-soft: rgb(240 138 93 / 12%);
  --pa-shadow: 0 1px 2px rgb(0 0 0 / 30%);
  --pa-shadow-hover: 0 6px 20px rgb(0 0 0 / 35%);
}

/* 通用卡片（子组件也使用） */
.patient-analysis :deep(.pa-card) {
  box-sizing: border-box;
  background: var(--pa-surface);
  border: 1px solid var(--pa-border);
  border-radius: var(--pa-radius);
  box-shadow: var(--pa-shadow);
  transition:
    box-shadow 150ms ease,
    border-color 150ms ease,
    opacity 200ms ease;
}

@media (hover: hover) {
  .patient-analysis :deep(.pa-card:hover) {
    border-color: var(--pa-border-strong);
    box-shadow: var(--pa-shadow-hover);
  }
}

/* ── 顶部进度条 ── */
.pa-progress {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  z-index: 5;
  height: 2px;
  overflow: hidden;
  pointer-events: none;
  opacity: 0;
  transition: opacity 200ms ease;
}
.pa-progress::after {
  position: absolute;
  inset: 0;
  width: 40%;
  content: "";
  background: linear-gradient(90deg, transparent, var(--pa-brand), transparent);
  animation: pa-progress 1.1s var(--pa-ease) infinite;
}
.pa-progress.is-active {
  opacity: 1;
}

@keyframes pa-progress {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(250%);
  }
}

/* ── 页头 ── */
.pa-header {
  display: flex;
  gap: 16px;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 16px;
}
.pa-eyebrow {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  color: var(--pa-text-3);
}
.pa-live-dot {
  width: 6px;
  height: 6px;
  background: #2f9e6e;
  border-radius: 50%;
  box-shadow: 0 0 0 3px rgb(47 158 110 / 15%);
}
.pa-live-dot.is-error {
  background: var(--pa-danger);
  box-shadow: 0 0 0 3px var(--pa-danger-soft);
}
.pa-title h1 {
  margin: 4px 0 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--pa-text);
  letter-spacing: 0.01em;
}
.pa-header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.pa-updated {
  margin-right: 4px;
  font-size: 12px;
  color: var(--pa-text-3);
}
.pa-icon-btn {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  padding: 0;
  font-size: 15px;
  color: var(--pa-text-2);
  cursor: pointer;
  background: var(--pa-surface);
  border: 1px solid var(--pa-border);
  border-radius: 8px;
  transition:
    color 150ms ease,
    border-color 150ms ease,
    background-color 150ms ease,
    transform 100ms ease;
}
.pa-icon-btn:hover {
  color: var(--pa-brand);
  border-color: var(--pa-border-strong);
}
.pa-icon-btn:active {
  transform: scale(0.96);
}
.pa-icon-btn.is-spinning .el-icon {
  animation: pa-spin 0.9s linear infinite;
}

@keyframes pa-spin {
  to {
    transform: rotate(360deg);
  }
}

/* ── 视图 tab：药丸分段 + 共享滑块 ── */
.pa-tabs {
  position: relative;
  display: inline-flex;
  max-width: 100%;
  padding: 3px;
  margin-bottom: 12px;
  overflow-x: auto;
  scrollbar-width: none;
  background: var(--pa-subtle-strong);
  border-radius: 10px;
}
.pa-tabs::-webkit-scrollbar {
  display: none;
}
.pa-tabs-thumb {
  position: absolute;
  top: 3px;
  bottom: 3px;
  left: 0;
  background: var(--pa-surface);
  border-radius: 8px;
  box-shadow:
    0 1px 2px rgb(16 24 40 / 8%),
    0 1px 1px rgb(16 24 40 / 4%);
  opacity: 0;
}
.pa-tabs-thumb.is-ready {
  opacity: 1;
  transition:
    transform 220ms var(--pa-ease),
    width 220ms var(--pa-ease);
}
.pa-tabs button {
  position: relative;
  display: inline-flex;
  gap: 6px;
  align-items: center;
  height: 32px;
  padding: 0 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--pa-text-2);
  white-space: nowrap;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 8px;
  transition: color 150ms ease;
}
.pa-tabs button:hover {
  color: var(--pa-text);
}
.pa-tabs button.active {
  color: var(--pa-brand);
}
.pa-tabs button:active {
  transform: scale(0.97);
}

/* ── 粘性工具条 ── */
.pa-toolbar {
  position: sticky;
  top: 0;
  z-index: 4;
  padding: 10px 12px;
  margin: 0 -8px 16px;
  background: color-mix(in srgb, var(--pa-surface) 88%, transparent);
  backdrop-filter: saturate(1.4) blur(10px);
  border: 1px solid var(--pa-border);
  border-radius: var(--pa-radius);
  box-shadow: var(--pa-shadow);
}
.pa-toolbar-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 14px;
  align-items: center;
}
.pa-field {
  display: flex;
  gap: 8px;
  align-items: center;
}
.pa-field-label {
  font-size: 12px;
  color: var(--pa-text-3);
  white-space: nowrap;
}
.pa-date :deep(.el-date-editor) {
  width: 252px;

  --el-input-border-radius: 8px;
}
.pa-divider {
  width: 1px;
  height: 20px;
  background: var(--pa-border);
}
.pa-toolbar :deep(.el-segmented) {
  --el-segmented-item-selected-color: var(--pa-brand);
  --el-segmented-item-selected-bg-color: var(--pa-surface);
  --el-segmented-bg-color: var(--pa-subtle);
  --el-segmented-item-hover-bg-color: transparent;
  --el-border-radius-base: 7px;

  padding: 2px;
  font-size: 13px;
}
.pa-toolbar :deep(.el-segmented__item-selected) {
  box-shadow: 0 1px 2px rgb(16 24 40 / 10%);
}
.pa-toolbar-end {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-left: auto;
}
.pa-filter-btn {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  height: 32px;
  padding: 0 10px 0 12px;
  font-size: 13px;
  color: var(--pa-text-2);
  cursor: pointer;
  background: var(--pa-surface);
  border: 1px solid var(--pa-border);
  border-radius: 8px;
  transition:
    color 150ms ease,
    border-color 150ms ease,
    background-color 150ms ease;
}
.pa-filter-btn:hover,
.pa-filter-btn.is-open {
  color: var(--pa-brand);
  border-color: color-mix(in srgb, var(--pa-brand) 40%, var(--pa-border));
}
.pa-filter-btn.has-value {
  color: var(--pa-brand);
  background: var(--pa-brand-soft);
}
.pa-caret {
  font-size: 12px;
  transition: transform 200ms var(--pa-ease);
}
.pa-filter-btn.is-open .pa-caret {
  transform: rotate(180deg);
}
.pa-badge {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  color: #ffffff;
  text-align: center;
  background: var(--pa-brand);
  border-radius: 9px;
}

/* 高级筛选：grid-rows 0fr→1fr 高度过渡 */
.pa-collapse {
  display: grid;
  grid-template-rows: 0fr;
  opacity: 0;
  transition:
    grid-template-rows 220ms var(--pa-ease),
    opacity 180ms ease;
}
.pa-collapse.is-open {
  grid-template-rows: 1fr;
  opacity: 1;
}
.pa-collapse-inner {
  min-height: 0;
  overflow: hidden;
}
.pa-advanced {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px 16px;
  padding-top: 12px;
  margin-top: 12px;
  border-top: 1px dashed var(--pa-border);
}
.pa-adv-field {
  display: grid;
  gap: 6px;
  min-width: 0;
}
.pa-adv-field label {
  font-size: 12px;
  color: var(--pa-text-3);
}
.pa-adv-field :deep(.el-select) {
  width: 100%;
}
.pa-age > div {
  display: flex;
  gap: 6px;
  align-items: center;
}
.pa-age span {
  color: var(--pa-text-3);
}
.pa-age :deep(.el-input-number) {
  width: 100%;
}

/* 已选条件 chips */
.pa-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 10px;
}
.pa-chip {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  height: 26px;
  padding: 0 4px 0 10px;
  font-size: 12px;
  color: var(--pa-brand);
  background: var(--pa-brand-soft);
  border-radius: 13px;
}
.pa-chip button {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  padding: 0;
  font-size: 14px;
  line-height: 1;
  color: inherit;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 50%;
  transition: background-color 120ms ease;
}
.pa-chip button:hover {
  background: color-mix(in srgb, var(--pa-brand) 18%, transparent);
}
.pa-chip-clear {
  padding: 0 6px;
  font-size: 12px;
  color: var(--pa-text-3);
  cursor: pointer;
  background: none;
  border: 0;
}
.pa-chip-clear:hover {
  color: var(--pa-danger);
}
.pa-chip-enter-active,
.pa-chip-leave-active {
  transition:
    opacity 160ms ease,
    transform 160ms var(--pa-ease);
}
.pa-chip-enter-from,
.pa-chip-leave-to {
  opacity: 0;
  transform: scale(0.92);
}
.pa-chip-leave-active {
  position: absolute;
}
.pa-chip-move {
  transition: transform 200ms var(--pa-ease);
}

/* ── 错误条 ── */
.pa-alert {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 10px 14px;
  margin-bottom: 16px;
  font-size: 13px;
  color: var(--pa-danger);
  background: var(--pa-danger-soft);
  border: 1px solid color-mix(in srgb, var(--pa-danger) 22%, transparent);
  border-radius: 10px;
}
.pa-alert span {
  flex: 1;
  min-width: 0;
}
.pa-alert button {
  padding: 2px 10px;
  font-size: 12px;
  font-weight: 500;
  color: var(--pa-danger);
  cursor: pointer;
  background: var(--pa-surface);
  border: 1px solid color-mix(in srgb, var(--pa-danger) 30%, transparent);
  border-radius: 6px;
}
.pa-fade-enter-active,
.pa-fade-leave-active {
  transition:
    opacity 180ms ease,
    transform 180ms var(--pa-ease);
}
.pa-fade-enter-from,
.pa-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

/* ── KPI ── */
.pa-kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
  transition: opacity 200ms ease;
}
.pa-kpis.is-busy {
  opacity: 0.6;
}
.pa-kpi {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  padding: 16px 18px;
  font: inherit;
  color: inherit;
  text-align: left;
  animation: pa-enter 420ms var(--pa-ease) both;
  animation-delay: calc(var(--pa-enter-index, 0) * 40ms);
}
.pa-kpi-label {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  font-size: 13px;
  color: var(--pa-text-2);
}
.pa-kpi strong {
  display: flex;
  gap: 4px;
  align-items: baseline;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.15;
  color: var(--pa-text);
  letter-spacing: -0.01em;
}
.pa-kpi strong em {
  font-size: 13px;
  font-style: normal;
  font-weight: 400;
  color: var(--pa-text-3);
}
.pa-kpi small {
  font-size: 12px;
  color: var(--pa-text-3);
  overflow-wrap: anywhere;
}
.pa-kpi.is-primary::before {
  position: absolute;
  top: 16px;
  bottom: 16px;
  left: 0;
  width: 3px;
  content: "";
  background: var(--pa-brand);
  border-radius: 0 3px 3px 0;
}
.pa-kpi.is-primary strong {
  color: var(--pa-brand);
}
.pa-quality {
  cursor: pointer;
}
.pa-quality .pa-kpi-label .el-icon {
  color: #2f9e6e;
}
.pa-quality.has-issue .pa-kpi-label .el-icon,
.pa-quality.has-issue strong {
  color: var(--pa-warn);
}
.pa-quality:focus-visible {
  outline: 2px solid var(--pa-brand);
  outline-offset: 2px;
}

@keyframes pa-enter {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

/* ── 视图上下文条 ── */
.pa-subbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  align-items: center;
  margin-bottom: 16px;
  font-size: 12px;
  color: var(--pa-text-3);
}
.pa-subbar-text {
  color: var(--pa-text-2);
}
.pa-subbar :deep(.el-segmented) {
  --el-segmented-item-selected-color: var(--pa-brand);
  --el-segmented-item-selected-bg-color: var(--pa-surface);
  --el-segmented-bg-color: var(--pa-subtle-strong);

  padding: 2px;
}
.pa-pill {
  height: 26px;
  padding: 0 12px;
  font-size: 12px;
  font-weight: 500;
  color: var(--pa-text-2);
  cursor: pointer;
  background: var(--pa-surface);
  border: 1px solid var(--pa-border);
  border-radius: 13px;
  transition:
    color 150ms ease,
    border-color 150ms ease,
    background-color 150ms ease;
}
.pa-pill.is-danger {
  color: var(--pa-danger);
  background: var(--pa-danger-soft);
  border-color: transparent;
}
.pa-pill.is-active {
  color: var(--pa-brand);
  background: var(--pa-brand-soft);
  border-color: transparent;
}
.pa-pill:disabled {
  cursor: default;
  opacity: 0.6;
}
.pa-crumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  align-items: center;
  margin-bottom: 16px;
  font-size: 13px;
}
.pa-crumbs button {
  padding: 3px 8px;
  font: inherit;
  color: var(--pa-text-2);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
  transition:
    color 120ms ease,
    background-color 120ms ease;
}
.pa-crumbs button:hover:not(:disabled) {
  color: var(--pa-brand);
  background: var(--pa-brand-soft);
}
.pa-crumbs button[aria-current] {
  font-weight: 600;
  color: var(--pa-text);
}
.pa-crumb {
  display: inline-flex;
  align-items: center;
}
.pa-crumb i {
  font-style: normal;
  color: var(--pa-text-3);
}

/* ── 图表网格 ── */
.pa-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.pa-grid > .is-wide {
  grid-column: 1 / -1;
}

/* ── 空状态 / 骨架 ── */
.pa-empty {
  display: grid;
  gap: 8px;
  justify-items: center;
  padding: 56px 24px;
  text-align: center;
}
.pa-empty-mark {
  width: 48px;
  height: 48px;
  margin-bottom: 4px;
  background: repeating-linear-gradient(135deg, transparent 0 6px, var(--pa-subtle) 6px 12px);
  border: 1.5px dashed var(--pa-border-strong);
  border-radius: 14px;
}
.pa-empty strong {
  font-size: 14px;
  font-weight: 600;
  color: var(--pa-text-2);
}
.pa-empty small {
  font-size: 12px;
  color: var(--pa-text-3);
}
.pa-skeleton {
  animation: pa-enter 200ms ease 300ms both;
}
.pa-sk-kpi,
.pa-sk-chart {
  display: grid;
  gap: 10px;
  padding: 18px;
}
.pa-sk-kpi {
  height: 112px;
}
.pa-sk-chart {
  grid-template-rows: auto 1fr;
  height: 320px;
}
.pa-skeleton i,
.pa-skeleton b {
  display: block;
  background: linear-gradient(90deg, var(--pa-subtle) 0%, var(--pa-subtle-strong) 50%, var(--pa-subtle) 100%);
  background-size: 200% 100%;
  border-radius: 6px;
  animation: pa-shimmer 1.4s ease-in-out infinite;
}
.pa-sk-kpi i {
  width: 40%;
  height: 12px;
}
.pa-sk-kpi b {
  width: 60%;
  height: 28px;
}
.pa-sk-chart i {
  width: 30%;
  height: 14px;
}
.pa-sk-chart b {
  height: 100%;
}

@keyframes pa-shimmer {
  from {
    background-position: 100% 0;
  }
  to {
    background-position: -100% 0;
  }
}

/* ── 明细抽屉 ── */
.pa-detail-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--pa-text-2);
}
.pa-detail-toolbar b {
  font-size: 16px;
  font-weight: 600;
  color: var(--pa-text);
}
.pa-detail-privacy {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 8px 12px;
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--pa-text-2);
  background: var(--pa-subtle);
  border-radius: 8px;
}
.pa-details-table {
  overflow: hidden;
  border: 1px solid var(--pa-border);
  border-radius: 10px;
}
.pa-details-table :deep(.el-table) {
  --el-table-header-bg-color: var(--pa-subtle);
  --el-table-header-text-color: var(--pa-text-2);
  --el-table-row-hover-bg-color: var(--pa-brand-soft);

  font-size: 13px;
}
.pa-details-table :deep(.el-table th.el-table__cell) {
  font-weight: 500;
}
.pa-muted {
  color: var(--pa-text-3);
}
.pa-drawer :deep(.el-pagination) {
  justify-content: flex-end;
  margin-top: 12px;
}
:deep(.el-drawer__body) .el-pagination {
  justify-content: flex-end;
  margin-top: 12px;
}

/* ── 响应式：办公 PC 为主，兼顾 1366 与窄屏 ── */
@media (width <= 1280px) {
  .pa-date :deep(.el-date-editor) {
    width: 232px;
  }
}

@media (width <= 1100px) {
  .pa-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .pa-divider {
    display: none;
  }
}

@media (width <= 720px) {
  .patient-analysis {
    padding: 16px 12px 24px;
  }
  .pa-header {
    flex-direction: column;
    align-items: stretch;
  }
  .pa-toolbar {
    position: static;
    margin: 0 0 16px;
  }
  .pa-toolbar-end {
    margin-left: 0;
  }
  .pa-date,
  .pa-date :deep(.el-date-editor) {
    width: 100%;
  }
  .pa-kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }
  .pa-kpi strong {
    font-size: 22px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .pa-progress::after,
  .pa-kpi,
  .pa-skeleton,
  .pa-skeleton i,
  .pa-skeleton b,
  .pa-icon-btn.is-spinning .el-icon {
    animation: none;
  }
  .pa-tabs-thumb.is-ready,
  .pa-collapse,
  .pa-caret,
  .pa-chip-enter-active,
  .pa-chip-leave-active,
  .pa-chip-move,
  .pa-fade-enter-active,
  .pa-fade-leave-active {
    transition: none;
  }
}
</style>

<!-- popover 挂在 body 下，需非 scoped -->
<style lang="scss">
.pa-quality-popper.el-popover {
  padding: 14px 16px;
  border-radius: 12px;
}
.pa-quality-pop {
  font-size: 12px;
}
.pa-quality-title {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.pa-quality-pop ul {
  padding: 0;
  margin: 0;
  list-style: none;
}
.pa-quality-pop li {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-regular);
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.pa-quality-pop li.is-alert b {
  color: #b7791f;
}
.pa-quality-note {
  margin: 10px 0 0;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
