<template>
  <section v-if="visible" class="fud-panel">
    <header class="fud-head">
      <div>
        <h3>随访工作台</h3>
        <p>待随访闭环：找患者 → 看话术 → 交流 → 留痕 · 最后更新 {{ lastUpdatedText }}</p>
      </div>
      <div class="fud-head-actions">
        <el-button size="small" :loading="loading" @click="loadAll">刷新</el-button>
        <el-button v-if="canManageScripts" size="small" type="primary" plain @click="openScriptManage">话术模板库</el-button>
      </div>
    </header>

    <!-- 板块 1 · 待随访总览（点击卡片联动筛选） -->
    <div class="fud-metrics">
      <button type="button" class="fud-metric is-danger" :class="{ active: filterStatus === 'overdue' }" @click="toggleStatus('overdue')">
        <strong>{{ overdue.length }}</strong>
        <span>逾期未随访</span>
      </button>
      <button type="button" class="fud-metric is-warning" :class="{ active: filterStatus === 'dueSoon' }" @click="toggleStatus('dueSoon')">
        <strong>{{ dueSoon.length }}</strong>
        <span>3 日内到期</span>
      </button>
      <button type="button" class="fud-metric is-info">
        <strong>{{ upcomingCount }}</strong>
        <span>未来已排期</span>
      </button>
      <button type="button" class="fud-metric is-success">
        <strong>{{ createdThisWeek }}</strong>
        <span>本周新建复诊</span>
      </button>
      <button type="button" class="fud-metric is-neutral" :class="{ active: filterUncontacted }" @click="filterUncontacted = !filterUncontacted">
        <strong>{{ uncontactedCount }}</strong>
        <span>从未联系</span>
      </button>
      <button type="button" class="fud-metric is-plain" @click="openScriptManage">
        <strong>{{ scriptTemplateCount }}</strong>
        <span>话术模板</span>
      </button>
    </div>

    <section class="fud-statistics">
      <header class="fud-statistics-head">
        <div>
          <strong>随访运营统计</strong>
          <small>按复查节点或实际联系月份查看履约情况</small>
        </div>
        <div class="fud-statistics-filters">
          <el-segmented v-model="statisticsBasis" :options="[{ label: '节点到期月份', value: 'due' }, { label: '实际联系月份', value: 'contact' }]" size="small" />
          <el-date-picker v-model="statisticsMonth" type="month" value-format="YYYY-MM" size="small" />
        </div>
      </header>
      <div class="fud-statistics-metrics">
        <span>应随访 <b>{{ statistics?.total || 0 }}</b></span>
        <span>已完成 <b>{{ statistics?.completed || 0 }}</b></span>
        <span>完成率 <b>{{ statistics?.completionRate || 0 }}%</b></span>
        <span>按时率 <b>{{ statistics?.onTimeRate || 0 }}%</b></span>
        <span class="is-danger">逾期未完成 <b>{{ statistics?.overduePending || 0 }}</b></span>
      </div>
      <div v-if="statistics" class="fud-statistics-grid">
        <div>
          <small>科室</small>
          <p v-for="item in statistics.departments.slice(0, 6)" :key="item.label">{{ item.label }} <b>{{ item.completed }}/{{ item.total }}</b></p>
        </div>
        <div>
          <small>护理人员 / 操作人</small>
          <p v-for="item in statistics.operators.slice(0, 6)" :key="item.label">{{ item.label }} <b>{{ item.completed }}/{{ item.total }}</b></p>
        </div>
      </div>
    </section>

    <!-- 板块 1.5 · 筛选工具条 -->
    <div class="fud-toolbar">
      <el-input v-model="filterKeyword" placeholder="按姓名 / 电话搜索" clearable size="small" class="fud-search" :prefix-icon="Search" />
      <el-select v-model="filterStatus" size="small" class="fud-status" placeholder="随访状态">
        <el-option label="全部待随访" value="all" />
        <el-option label="仅逾期" value="overdue" />
        <el-option label="3 日内到期" value="dueSoon" />
      </el-select>
      <el-select v-model="filterOverdueDays" size="small" class="fud-days" placeholder="逾期程度">
        <el-option label="全部逾期程度" value="all" />
        <el-option label="≥ 7 天" value="7" />
        <el-option label="≥ 14 天" value="14" />
        <el-option label="≥ 30 天" value="30" />
      </el-select>
      <el-date-picker
        v-model="filterDueDate"
        type="date"
        size="small"
        placeholder="按应复查日期"
        value-format="YYYY-MM-DD"
        class="fud-date"
        :clearable="true"
      />
      <el-checkbox v-model="filterUncontacted" size="small">仅未联系</el-checkbox>
      <span class="fud-toolbar-count">筛选结果 <b>{{ filteredRows.length }}</b> 人</span>
      <el-button v-if="hasActiveFilter" link size="small" type="primary" @click="resetFilters">清除筛选</el-button>
    </div>

    <!-- 板块 2 · 患者卡流 -->
    <TransitionGroup v-if="filteredRows.length" name="fud-card" tag="div" class="fud-cards">
      <article
        v-for="(row, index) in filteredRows"
        :key="row.patientCaseId"
        class="fud-card"
        :class="urgencyClass(row)"
        :style="{ '--stagger': `${Math.min(index, 12) * 40}ms` }"
      >
        <div class="fud-card-head">
          <span class="fud-card-name">{{ row.name || "待补姓名" }}</span>
          <el-tag :type="urgencyTagType(row)" effect="dark" size="small" round>{{ urgencyLabel(row) }}</el-tag>
          <span v-if="row.__overdue" class="fud-time-badge is-overdue">{{ row.overdueDays }} 天</span>
        </div>
        <div class="fud-card-facts">
          <span class="fud-card-phone" :title="'点击复制'" @click="copyPhone(row)">
            <el-icon><Phone /></el-icon>{{ row.phone || "未登记" }}
          </span>
          <span>{{ row.gender || "—" }} · {{ row.age || "—" }} 岁</span>
          <span v-if="row.surgery" class="fud-card-surgery">{{ row.surgery }}</span>
        </div>
        <div class="fud-card-brief">
          <p v-if="row.reason" class="fud-brief-line"><label>复诊事由</label>{{ row.reason }}</p>
          <p v-if="row.conditionNote" class="fud-brief-line"><label>恢复备注</label>{{ row.conditionNote }}</p>
          <p class="fud-brief-line">
            <label>应复查</label>
            <span class="fud-time-value" :class="urgencyClass(row)">{{ row.dueDate }}</span>
            <span class="fud-time-relative">{{ dueDateRelative(row) }}</span>
            <template v-if="row.lastContactAt"> · <span class="fud-time-value is-contacted">已联系 {{ row.lastContactAt.slice(0, 10) }}</span></template>
          </p>
        </div>
        <div class="fud-card-actions">
          <el-button size="small" type="primary" plain @click="openScript(row)">查看话术</el-button>
          <el-button v-if="canOperateFollowUp" size="small" type="warning" plain :disabled="Boolean(row.lastContactAt)" @click="markContacted(row)">
            {{ row.lastContactAt ? "已联系" : "标记已联系" }}
          </el-button>
          <el-button size="small" type="success" plain @click="openArchive(row)">进档案</el-button>
        </div>
      </article>
    </TransitionGroup>
    <el-empty v-else-if="loaded" description="当前筛选条件下没有待随访患者" :image-size="70" />

    <!-- 板块 4 · 最近随访动态 -->
    <div v-if="recentActions.length" class="fud-recent">
      <h4>最近随访动态</h4>
      <ul>
        <li v-for="(action, index) in recentActions" :key="index">
          <span class="fud-recent-time">{{ action.createdAt }}</span>
          <span class="fud-recent-operator">{{ action.operator }}</span>
          <span class="fud-recent-detail">{{ action.detail }}</span>
        </li>
      </ul>
    </div>

    <FollowUpScriptDialog
      v-model:visible="scriptDialogVisible"
      :visit="scriptVisit"
      :patient-case-id="scriptPatientCaseId"
      :patient-name="scriptPatientName"
      :gender="scriptGender"
      :user-name="userStore.userInfo.name"
      @open-archive="encounterId => router.push({ path: '/health-archive', query: { encounterId } })"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { Phone, Search } from "@element-plus/icons-vue";
import { useUserStore } from "@/stores/modules/user";
import {
  loadFollowUpStatisticsApi,
  loadFollowUpVisitsApi,
  loadRecallSummaryApi,
  markRecallContactedApi,
  type FollowUpStatistics,
  type FollowUpVisit,
  type RecallRow
} from "@/api/modules/clinic/followUp";
import { loadFollowUpScriptTemplates, followUpScriptSource } from "@/utils/followUpScript";
import { getEncounterOverviewApi, getPreAiEncounterHistoryApi } from "@/api/modules/clinic";
import { clinicFetch, parseClinicApiResponse } from "@/api/modules/clinic/http";
import { authHeaders } from "@/api/modules/authToken";
import FollowUpScriptDialog from "@/views/preAi/encounters/components/FollowUpScriptDialog.vue";

type DashboardRow = RecallRow & { __overdue: boolean };

defineEmits<{ "open-script-manage": [] }>();

interface DashboardSummary {
  createdThisWeek: number;
  recentActions: { action: string; operator: string; operatorRole?: string; detail: string; createdAt: string }[];
  generatedAt: string;
}

const router = useRouter();
const userStore = useUserStore();

const role = computed(() => userStore.userInfo.role || "");
const visible = computed(() => ["doctor", "admin", "inspection", "nurse", "nursing"].includes(role.value));
const canManageScripts = computed(() => ["nurse", "nursing", "doctor", "admin"].includes(role.value));
const canOperateFollowUp = computed(() => ["doctor", "nurse", "admin"].includes(role.value));

const loading = ref(false);
const loaded = ref(false);
const overdue = ref<RecallRow[]>([]);
const dueSoon = ref<RecallRow[]>([]);
const upcomingCount = ref(0);
const createdThisWeek = ref(0);
const recentActions = ref<DashboardSummary["recentActions"]>([]);
const generatedAt = ref("");
const scriptTemplateCount = ref(0);
const todayContacts = ref(0);
const statistics = ref<FollowUpStatistics | null>(null);
const statisticsBasis = ref<"due" | "contact">("due");
const statisticsMonth = ref(new Date().toISOString().slice(0, 7));
let refreshTimer: ReturnType<typeof setInterval> | null = null;

const rows = computed<DashboardRow[]>(() => [
  ...overdue.value.map(row => ({ ...row, __overdue: true })),
  ...dueSoon.value.map(row => ({ ...row, __overdue: false }))
]);

// ---------- 筛选 ----------
const filterKeyword = ref("");
const filterStatus = ref<"all" | "overdue" | "dueSoon">("all");
const filterOverdueDays = ref("all");
const filterDueDate = ref("");
const filterUncontacted = ref(false);

const uncontactedCount = computed(() => rows.value.filter(row => !row.lastContactAt).length);

const filteredRows = computed(() =>
  rows.value.filter(row => {
    if (filterStatus.value === "overdue" && !row.__overdue) return false;
    if (filterStatus.value === "dueSoon" && row.__overdue) return false;
    if (filterOverdueDays.value !== "all") {
      const minDays = Number(filterOverdueDays.value);
      // 逾期行 overdueDays 为正整天数；dueSoon 行为负数（未来到期），按天数筛选时天然排除
      if (row.overdueDays < minDays) return false;
    }
    // 指定应复查日期：字符串精确匹配（dueDate 后端格式 YYYY-MM-DD）
    if (filterDueDate.value && row.dueDate !== filterDueDate.value) return false;
    if (filterUncontacted.value && row.lastContactAt) return false;
    const keyword = filterKeyword.value.trim().toLowerCase();
    if (keyword) {
      const name = String(row.name || "").toLowerCase();
      const phone = String(row.phone || "").toLowerCase();
      if (!name.includes(keyword) && !phone.includes(keyword)) return false;
    }
    return true;
  })
);

const hasActiveFilter = computed(
  () =>
    filterKeyword.value.trim() !== "" ||
    filterStatus.value !== "all" ||
    filterOverdueDays.value !== "all" ||
    filterDueDate.value !== "" ||
    filterUncontacted.value
);

const toggleStatus = (status: "overdue" | "dueSoon") => {
  filterStatus.value = filterStatus.value === status ? "all" : status;
};

// ---------- 时间视觉分级（随访核心视觉：紧迫度着色） ----------
// dueSoon 行 overdueDays 为负数（距到期天数），逾期行为正整天数
const urgencyClass = (row: DashboardRow) => {
  if (!row.__overdue) return "is-urgent-soon";
  if (row.overdueDays >= 30) return "is-critical";
  if (row.overdueDays >= 7) return "is-severe";
  return "is-overdue-mild";
};

const urgencyTagType = (row: DashboardRow): "danger" | "warning" | "info" => {
  if (!row.__overdue) return "info";
  if (row.overdueDays >= 30) return "danger";
  if (row.overdueDays >= 7) return "danger";
  return "warning";
};

const urgencyLabel = (row: DashboardRow) => {
  if (!row.__overdue) return `${Math.abs(row.overdueDays)} 日内到期`;
  if (row.overdueDays >= 30) return "严重逾期";
  if (row.overdueDays >= 7) return "逾期较久";
  return "轻度逾期";
};

const dueDateRelative = (row: DashboardRow) => {
  if (row.__overdue) return `已逾期 ${row.overdueDays} 天`;
  const days = Math.abs(row.overdueDays);
  return days === 0 ? "今天到期" : `${days} 天后到期`;
};

const resetFilters = () => {
  filterKeyword.value = "";
  filterStatus.value = "all";
  filterOverdueDays.value = "all";
  filterDueDate.value = "";
  filterUncontacted.value = false;
};

// ---------- 数据 ----------
const lastUpdatedText = computed(() => (generatedAt.value ? generatedAt.value.slice(11, 16) : "加载中"));

const loadAll = async () => {
  loading.value = true;
  try {
    const [year, month] = statisticsMonth.value.split("-").map(Number);
    const lastDay = new Date(year, month, 0).getDate();
    const { data: statisticsData } = await loadFollowUpStatisticsApi({
      from: `${statisticsMonth.value}-01`,
      to: `${statisticsMonth.value}-${String(lastDay).padStart(2, "0")}`,
      basis: statisticsBasis.value
    });
    statistics.value = statisticsData;
  } catch {
    statistics.value = null;
  }
  try {
    const { data } = await loadRecallSummaryApi();
    overdue.value = data.overdue || [];
    dueSoon.value = data.dueSoon || [];
    upcomingCount.value = data.upcomingCount || 0;
    generatedAt.value = data.generatedAt || "";
  } catch (error: any) {
    ElMessage.error(error?.message || "随访召回数据加载失败");
  }
  try {
    const result = await clinicFetch("/follow-up/dashboard/summary", { headers: authHeaders() });
    const summary = await parseClinicApiResponse<DashboardSummary>(result);
    createdThisWeek.value = summary.createdThisWeek || 0;
    recentActions.value = summary.recentActions || [];
    todayContacts.value = summary.recentActions.filter(action => action.action === "followup.recall.contact").length;
  } catch {
    // summary 失败不阻断主列表
  }
  loading.value = false;
  loaded.value = true;
};

watch([statisticsBasis, statisticsMonth], () => void loadAll(), { flush: "post" });

const countScriptTemplates = async () => {
  try {
    const result = await clinicFetch("/follow-up-script-templates", { headers: authHeaders() });
    const list = await parseClinicApiResponse<unknown[]>(result);
    scriptTemplateCount.value = Array.isArray(list) ? list.length : 0;
  } catch {
    scriptTemplateCount.value = 0;
  }
};

// ---------- 话术弹窗 ----------
const scriptDialogVisible = ref(false);
const scriptVisit = ref<FollowUpVisit | null>(null);
const scriptPatientCaseId = ref("");
const scriptPatientName = ref("");
const scriptGender = ref("");

const openScript = async (row: DashboardRow) => {
  try {
    const { data } = await loadFollowUpVisitsApi(row.patientCaseId);
    const visits = data.visits || [];
    const visit = visits.slice().sort((a, b) => b.seq - a.seq)[0] || null;
    if (!visit) {
      ElMessage.warning("该患者暂无复诊记录");
      return;
    }
    scriptVisit.value = visit;
    scriptPatientCaseId.value = row.patientCaseId;
    scriptPatientName.value = row.name;
    scriptGender.value = row.gender || "";
    scriptDialogVisible.value = true;
  } catch (error: any) {
    ElMessage.error(error?.message || "复诊记录加载失败");
  }
};

const markContacted = async (row: DashboardRow) => {
  try {
    await markRecallContactedApi(row.visitId);
    const now = new Date().toISOString().slice(0, 19).replace("T", " ");
    // 本地行内更新：不整表重拉，顶部指标即时重算
    overdue.value = overdue.value.filter(item => item.visitId !== row.visitId);
    dueSoon.value = dueSoon.value.filter(item => item.visitId !== row.visitId);
    recentActions.value = [
      { action: "followup.recall.contact", operator: userStore.userInfo.name || "", detail: `复查召回已联系 ${row.name}（${row.reason || ""}）`, createdAt: now },
      ...recentActions.value
    ];
    todayContacts.value += 1;
    ElMessage.success(`已标记联系 ${row.name}，该患者已移出待随访清单`);
  } catch (error: any) {
    ElMessage.error(error?.message || "标记失败");
  }
};

const openArchive = (row: DashboardRow) => {
  void router.push({ path: "/health-archive", query: { patientCaseId: row.patientCaseId } });
};

const openScriptManage = () => {
  void router.push("/pre-ai/script-manage");
};

const copyPhone = async (row: DashboardRow) => {
  try {
    await navigator.clipboard.writeText(row.phone || "");
    ElMessage.success("电话已复制");
  } catch {
    ElMessage.warning("复制失败，请手动记录");
  }
};

onMounted(() => {
  void loadAll();
  void countScriptTemplates();
  void loadFollowUpScriptTemplates();
  refreshTimer = setInterval(() => {
    void loadAll();
  }, 60000);
});

onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer);
});
</script>

<style scoped lang="scss">
.fud-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.fud-statistics {
  display: grid;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--hos-chart-line-soft, rgb(90 110 130 / 10%));
  border-radius: 12px;
  background: var(--hos-chart-panel-soft, var(--el-fill-color-light));
}

.fud-statistics-head,
.fud-statistics-metrics {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.fud-statistics-head small {
  display: block;
  margin-top: 3px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.fud-statistics-filters {
  display: flex;
  align-items: center;
  gap: 8px;
}

.fud-statistics-metrics span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.fud-statistics-metrics b {
  margin-left: 4px;
  color: var(--el-text-color-primary);
  font-size: 18px;
}

.fud-statistics-metrics .is-danger b {
  color: var(--el-color-danger);
}

.fud-statistics-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.fud-statistics-grid > div {
  padding: 10px;
  border-radius: 8px;
  background: var(--hos-chart-panel, #fff);
}

.fud-statistics-grid small {
  color: var(--el-text-color-secondary);
}

.fud-statistics-grid p {
  display: flex;
  justify-content: space-between;
  margin: 7px 0 0;
  font-size: 12px;
}

@media (max-width: 760px) {
  .fud-statistics-grid {
    grid-template-columns: 1fr;
  }
}

.fud-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;

  h3 {
    margin: 0 0 4px;
    font-size: 15px;
    color: var(--el-text-color-primary);
  }

  p {
    margin: 0;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  .fud-head-actions {
    display: flex;
    gap: 8px;
  }
}

// ---------- 指标卡：渐变底色 + hover 流动 ----------
.fud-metrics {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
}

.fud-metric {
  --metric-color: var(--el-color-primary);
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px 14px;
  text-align: left;
  border: 1px solid transparent;
  border-radius: 12px;
  background:
    linear-gradient(var(--el-bg-color), var(--el-bg-color)) padding-box,
    linear-gradient(120deg, color-mix(in srgb, var(--metric-color) 55%, transparent), color-mix(in srgb, var(--metric-color) 10%, transparent)) border-box;
  background-size: 100% 100%, 220% 100%;
  background-position: 0 0, 0% 0%;
  cursor: pointer;
  transition: transform 0.25s cubic-bezier(0.22, 1, 0.36, 1), box-shadow 0.25s ease, background-position 0.6s ease;

  strong {
    font-size: 22px;
    line-height: 1.2;
    color: var(--metric-color);
  }

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  &:hover {
    transform: translateY(-2px);
    background-position: 0 0, 100% 0%;
    box-shadow: 0 8px 20px color-mix(in srgb, var(--metric-color) 18%, transparent);
  }

  &.active {
    border-width: 1.5px;
    transform: translateY(-1px);
  }

  &.is-danger {
    --metric-color: var(--el-color-danger);
  }
  &.is-warning {
    --metric-color: var(--el-color-warning);
  }
  &.is-info {
    --metric-color: var(--el-color-primary);
  }
  &.is-success {
    --metric-color: var(--el-color-success);
  }
  &.is-neutral {
    --metric-color: #8b5cf6;
  }
  &.is-plain {
    --metric-color: var(--el-text-color-regular);
  }
}

// ---------- 筛选工具条 ----------
.fud-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: color-mix(in srgb, var(--el-color-primary) 4%, var(--el-bg-color));
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 14%, var(--el-border-color-lighter));
  border-radius: 10px;

  .fud-search {
    width: 200px;
  }

  .fud-status {
    width: 130px;
  }

  .fud-days {
    width: 130px;
  }

  .fud-date {
    width: 150px;
  }

  .fud-toolbar-count {
    margin-left: auto;
    color: var(--el-text-color-secondary);
    font-size: 12px;

    b {
      color: var(--el-color-primary);
    }
  }
}

// ---------- 患者卡流 ----------
.fud-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}

.fud-card {
  --urgency-color: var(--el-border-color-lighter);
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 14px 14px 16px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-left: 3px solid var(--urgency-color);
  border-radius: 14px;
  transition: transform 0.25s cubic-bezier(0.22, 1, 0.36, 1), box-shadow 0.25s ease, border-color 0.25s ease;

  &.is-critical {
    --urgency-color: #b91c1c;
    background: linear-gradient(135deg, color-mix(in srgb, #b91c1c 6%, var(--el-bg-color)) 0%, var(--el-bg-color) 42%);
  }
  &.is-severe {
    --urgency-color: #dc2626;
    background: linear-gradient(135deg, color-mix(in srgb, #dc2626 4%, var(--el-bg-color)) 0%, var(--el-bg-color) 42%);
  }
  &.is-overdue-mild {
    --urgency-color: #ea580c;
  }
  &.is-urgent-soon {
    --urgency-color: #d97706;
    background: linear-gradient(135deg, color-mix(in srgb, #d97706 5%, var(--el-bg-color)) 0%, var(--el-bg-color) 42%);
  }

  &:hover {
    transform: translateY(-3px);
    border-color: var(--urgency-color);
    box-shadow: 0 10px 24px color-mix(in srgb, var(--urgency-color) 16%, transparent);
  }

  .fud-time-badge {
    margin-left: auto;
    padding: 1px 8px;
    color: #fff;
    font-size: 12px;
    font-weight: 700;
    background: var(--urgency-color);
    border-radius: 999px;
  }

  .fud-time-value {
    font-weight: 700;

    &.is-critical {
      color: #b91c1c;
    }
    &.is-severe {
      color: #dc2626;
    }
    &.is-overdue-mild {
      color: #ea580c;
    }
    &.is-urgent-soon {
      color: #d97706;
    }
    &.is-contacted {
      color: var(--el-color-success);
      font-weight: 400;
    }
  }

  .fud-time-relative {
    color: var(--el-text-color-secondary);
    font-size: 11px;
  }

  .fud-card-head {
    display: flex;
    align-items: center;
    gap: 8px;

    .fud-card-name {
      font-size: 15px;
      font-weight: 700;
      color: var(--el-text-color-primary);
    }
  }

  .fud-card-facts {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    color: var(--el-text-color-regular);
    font-size: 12px;

    .fud-card-phone {
      display: inline-flex;
      align-items: center;
      gap: 3px;
      color: var(--el-color-primary);
      cursor: pointer;

      &:hover {
        text-decoration: underline;
      }
    }

    .fud-card-surgery {
      max-width: 100%;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .fud-card-brief {
    display: grid;
    gap: 4px;
    padding: 8px 10px;
    background: color-mix(in srgb, var(--el-fill-color) 55%, transparent);
    border-radius: 8px;

    .fud-brief-line {
      display: flex;
      gap: 6px;
      margin: 0;
      color: var(--el-text-color-regular);
      font-size: 12px;
      line-height: 1.5;

      label {
        flex-shrink: 0;
        color: var(--el-text-color-secondary);
      }
    }
  }

  .fud-card-actions {
    display: flex;
    gap: 6px;
    margin-top: auto;

    .el-button {
      flex: 1;
      margin: 0;
    }
  }
}

// 卡片入场 stagger 渐入 + 离场缩淡
.fud-card-enter-active {
  transition: opacity 0.4s ease var(--stagger, 0ms), transform 0.4s cubic-bezier(0.22, 1, 0.36, 1) var(--stagger, 0ms);
}

.fud-card-enter-from {
  opacity: 0;
  transform: translateY(10px);
}

.fud-card-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.fud-card-leave-to {
  opacity: 0;
  transform: scale(0.96);
}

// ---------- 最近动态 ----------
.fud-recent {
  h4 {
    margin: 0 0 8px;
    font-size: 13px;
    color: var(--el-text-color-primary);
  }

  ul {
    margin: 0;
    padding: 0;
    list-style: none;
    display: grid;
    gap: 6px;
    max-height: 180px;
    overflow-y: auto;

    li {
      display: flex;
      gap: 10px;
      font-size: 12px;
      color: var(--el-text-color-regular);

      .fud-recent-time {
        color: var(--el-text-color-secondary);
        flex-shrink: 0;
      }

      .fud-recent-operator {
        color: var(--el-color-primary);
        flex-shrink: 0;
      }
    }
  }
}
</style>
