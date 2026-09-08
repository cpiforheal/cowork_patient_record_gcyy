<template>
  <div class="home-page">
    <!-- 紧凑 header：问候 + 角色 + 日期 + 当前焦点 -->
    <header class="home-header board-card">
      <div class="header-main">
        <h2>{{ greetingText }}，{{ userName }}</h2>
        <p class="header-meta">
          <AnimatedShinyText class="role-badge">{{ roleName }} · {{ department }}</AnimatedShinyText>
          <span>{{ headerDateText }}</span>
          <span v-if="focusTask" class="header-focus">今天要处理什么：{{ focusTask.title }}</span>
        </p>
      </div>
      <el-button circle :icon="Refresh" :loading="dashboardLoading" title="刷新待办" @click="reloadAll" />
    </header>

    <template v-if="showPatientBoard">
      <!-- 真待办聚合：可执行的大数字入口卡 -->
      <section v-loading="dashboardLoading" class="todo-grid">
        <BlurFade v-for="(card, index) in primaryTodoCards" :key="card.id" :delay="index * 0.05">
          <button type="button" class="todo-card" :class="`is-${card.tone}`" @click="openStatCard(card)">
            <BorderBeam v-if="card.urgent" :duration="4.5" />
            <span class="todo-label">
              {{ card.label }}
              <el-icon><ArrowRight /></el-icon>
            </span>
            <strong class="todo-count">
              <NumberTicker :value="typeof card.count === 'number' ? card.count : 0" />
            </strong>
            <small>{{ card.desc }}</small>
          </button>
        </BlurFade>
        <el-empty v-if="!primaryTodoCards.length && !dashboardLoading" description="暂无待办，一切尽在掌握" :image-size="60" />
      </section>

      <!-- 来访患者住址分布分析（数据源：患者收费信息，仅管理员） -->
      <AddressAnalysisPanel v-if="isAdmin" class="board-card" />

      <!-- 医政早报（每日医疗政策资讯，仅管理员）：统计卡样式 + emoji 概览 + 悬浮呼吸动画 -->
      <button v-if="isAdmin" type="button" class="board-card policy-brief-card" @click="router.push('/policy-brief')">
        <span class="brief-card-head">
          <el-icon><Reading /></el-icon>
          <strong>医政早报</strong>
          <el-tag size="small" effect="plain" round>早报</el-tag>
          <small v-if="policyBriefLatest">{{ policyBriefLatest.briefDate }} · {{ policyBriefLatest.total }} 条</small>
          <small v-else>每日 7:30 自动采集</small>
          <span class="fold-spacer"></span>
          <span class="brief-card-more"
            >查看 <el-icon><ArrowRight /></el-icon
          ></span>
        </span>
        <span v-if="briefRows.length" class="brief-card-rows">
          <span v-for="row in briefRows" :key="row.id" class="brief-row">
            <i class="brief-row-emoji">{{ row.emoji }}</i>
            <span class="brief-row-text">{{ row.text }}</span>
          </span>
        </span>
        <small v-else class="brief-card-empty">今日暂无资讯，进入「业务工作台 → 医政早报」可立即采集</small>
      </button>

      <div class="workbench-grid">
        <div class="workbench-main">
          <!-- 数据看板（升主位，默认展开） -->
          <section class="board-card dashboard-fold">
            <button type="button" class="fold-head" @click="dashboardOpen = !dashboardOpen">
              <el-icon><TrendCharts /></el-icon>
              <strong>数据看板</strong>
              <small>收录趋势 · 月历热力</small>
              <span class="fold-spacer"></span>
              <el-icon class="fold-arrow" :class="{ open: dashboardOpen }"><ArrowDown /></el-icon>
            </button>
            <div v-if="dashboardOpen" class="fold-body">
              <div class="trend-toolbar">
                <el-segmented v-model="trendRange" :options="trendRangeOptions" size="small" />
                <div class="summary-chips">
                  <span class="chip"
                    >窗口合计 <b>{{ trendSummary.total }}</b> 人</span
                  >
                  <span class="chip"
                    >日均 <b>{{ trendSummary.avg }}</b> 人</span
                  >
                  <span v-if="trendSummary.peakDay" class="chip"
                    >峰值 <b>{{ trendSummary.peakDay.label }} {{ trendSummary.peakDay.value }}</b> 人</span
                  >
                </div>
              </div>
              <el-progress
                v-if="trendSwitching"
                class="trend-progress"
                :percentage="100"
                :indeterminate="true"
                :duration="1"
                :show-text="false"
                :stroke-width="6"
              />
              <DailyPatientCurve :items="dailyCurveItems" :disease-stats="diseaseStats" is-admin @retag="onRetagDiseases" />
              <MiniBarChart
                compact
                :title="trendTitle"
                subtitle="按就诊日期"
                :items="trendItems"
                :max-bars="trendItems.length"
                unit=" 人"
              />
              <CalendarHeatmap
                :month-title="calendarMonthTitle"
                :month-total="calendarMonthTotal"
                :peak-count="calendarPeakCount"
                :weekday-labels="weekdayLabels"
                :cells="calendarCells"
                @shift-month="shiftCalendarMonth"
                @current-month="jumpToCurrentMonth"
                @select-month="selectCalendarMonth"
                @select-date="selectCalendarDate"
              />
            </div>
          </section>

          <!-- 我的待办（可折叠，默认收起） -->
          <section class="board-card dashboard-fold todo-fold">
            <div class="fold-head" @click="todoOpen = !todoOpen">
              <el-icon><List /></el-icon>
              <strong>我的待办</strong>
              <span class="maint-badge" :class="pendingRows.length ? 'is-warning' : 'is-success'"
                >待处理 {{ pendingRows.length }}</span
              >
              <span class="fold-spacer"></span>
              <el-button :icon="Refresh" link size="small" :loading="dashboardLoading" @click.stop="reloadAll">刷新</el-button>
              <el-icon class="fold-arrow" :class="{ open: todoOpen }"><ArrowDown /></el-icon>
            </div>
            <HomeTaskPanel
              v-if="todoOpen"
              class="todo-panel-body"
              :role-name="roleName"
              :action-tasks="actionTasks"
              :task-cards="taskCards"
              @refresh="reloadAll"
              @open-task="openTask"
              @open-action-task="openActionTask"
            />
          </section>
        </div>

        <aside class="workbench-side board-card">
          <ShortcutPanel :quick-entries="quickEntries" :reminders="roleReminders" @navigate="navigateTo" />
          <template v-if="isAdmin">
            <button type="button" class="maintenance-summary" @click="maintenanceOpen = !maintenanceOpen">
              <el-icon><Setting /></el-icon>
              <strong>生产维护</strong>
              <span class="maint-badge" :class="`is-${maintenanceTone}`">{{ maintenanceBadgeText }}</span>
              <el-icon class="fold-arrow" :class="{ open: maintenanceOpen }"><ArrowDown /></el-icon>
            </button>
            <MaintenancePanel
              v-if="maintenanceOpen"
              v-model:backup-enabled="backupEnabled"
              v-model:backup-path="backupPath"
              :maintenance-loading="maintenanceLoading"
              :storage-summary="storageSummary"
              :snapshot-summary="snapshotSummary"
              :maintenance-status="maintenanceStatus"
              :latest-backup-summary="latestBackupSummary"
              :backup-status="backupStatus"
              :backup-loading="backupLoading"
              :choosing-backup-dir="choosingBackupDir"
              :backup-storage-summary="backupStorageSummary"
              :backup-health-items="backupHealthItems"
              @refresh="loadMaintenanceDashboard({ fullMaintenanceScan: true })"
              @create-snapshot="createSnapshot"
              @choose-backup-directory="chooseBackupDirectory"
              @save-backup-config="saveBackupConfig"
              @run-backup-now="runBackupNow"
            />
          </template>
        </aside>
      </div>
    </template>

    <template v-else-if="showPharmacyBoard">
      <section v-loading="dashboardLoading" class="stat-strip">
        <BlurFade v-for="(card, index) in statCards" :key="card.id" :delay="index * 0.04">
          <button type="button" class="stat-card" :class="`is-${card.tone}`" @click="openStatCard(card)">
            <span>{{ card.label }}</span>
            <strong>{{ card.count }}</strong>
            <small>{{ card.desc }}</small>
          </button>
        </BlurFade>
      </section>
      <div class="board-card pharmacy-chart">
        <MiniBarChart title="处方状态分布" subtitle="中药房当前流水线" :items="pharmacyChartItems" unit=" 张" />
      </div>
      <aside class="workbench-side board-card">
        <ShortcutPanel :quick-entries="quickEntries" :reminders="roleReminders" @navigate="navigateTo" />
      </aside>
    </template>
  </div>
</template>

<script setup lang="ts" name="home">
import { computed, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { ArrowDown, ArrowRight, List, Reading, Refresh, Setting, TrendCharts } from "@element-plus/icons-vue";
import {
  chooseBackupDirectoryApi,
  createMaintenanceSnapshotApi,
  getBackupStatusApi,
  getMaintenanceSummaryApi,
  getMaintenanceStatusApi,
  getOperationStatsApi,
  getPatientListApi,
  getWorkRemindersApi,
  runBackupNowApi,
  saveBackupConfigApi,
  type BackupStatus,
  type MaintenanceStatus,
  type OperationStats,
  type PatientRow,
  type WorkReminder
} from "@/api/modules/clinic";
import { getTcmDashboardApi, type TcmStatusCounts } from "@/api/modules/clinic/tcmPharmacy";
import { getPolicyBriefLatestApi, type PolicyBriefResult } from "@/api/modules/clinic/policyBrief";
import { getPreAiPatientCasesApi, runDiseaseTaggingApi, type PreAiPatientCase } from "@/api/modules/clinic/preAi";
import { canEditSection, recordSections, roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";
import { useAuthStore } from "@/stores/modules/auth";
import { classifyPatientStatus } from "@/utils/patientStatusClassifier";
import CalendarHeatmap from "./components/CalendarHeatmap.vue";
import DailyPatientCurve from "./components/DailyPatientCurve.vue";
import HomeTaskPanel from "./components/HomeTaskPanel.vue";
import MiniBarChart from "./components/MiniBarChart.vue";
import ShortcutPanel from "./components/ShortcutPanel.vue";
import MaintenancePanel from "./components/MaintenancePanel.vue";
import AddressAnalysisPanel from "./components/AddressAnalysisPanel.vue";
import AnimatedShinyText from "@/components/inspira/AnimatedShinyText.vue";
import BlurFade from "@/components/inspira/BlurFade.vue";
import BorderBeam from "@/components/inspira/BorderBeam.vue";
import NumberTicker from "@/components/inspira/NumberTicker.vue";
import { useHomeDashboard } from "./composables/useHomeDashboard";

interface HomeTask {
  id: string;
  title: string;
  desc: string;
  sectionKey: string;
  patient: PatientRow;
}

interface ActionTask {
  id: string;
  roleLabel: string;
  title: string;
  desc: string;
  count: number | string;
  level: "success" | "warning" | "danger" | "info";
  actionText: string;
  path: string;
  query?: Record<string, string>;
}

interface StatCard {
  id: string;
  label: string;
  count: number | string;
  desc: string;
  tone: "success" | "warning" | "danger" | "info";
  path: string;
  query?: Record<string, string>;
  urgent?: boolean;
}

type CalendarDayCell = {
  key: string;
  date: string;
  day: number;
  count: number;
  level: number;
  isBlank: boolean;
  isToday: boolean;
  isSelected: boolean;
  ariaLabel: string;
  hoverColor: string;
};

const router = useRouter();
const userStore = useUserStore();
const authStore = useAuthStore();

const patientRows = ref<PatientRow[]>([]);
const dashboardLoading = ref(false);
const workReminders = ref<WorkReminder[]>([]);
const maintenanceStatus = ref<MaintenanceStatus>();
const maintenanceLoading = ref(false);
const backupStatus = ref<BackupStatus>();
const backupPath = ref("");
const backupEnabled = ref(true);
const backupLoading = ref(false);
const choosingBackupDir = ref(false);
const tcmCounts = ref<TcmStatusCounts>();
const stats = ref<OperationStats>({
  totalPatients: 0,
  pendingPatients: 0,
  reviewPatients: 0,
  returnedPatients: 0,
  archivedPatients: 0,
  overduePatients: 0,
  documentCount: 0,
  voidedDocumentCount: 0,
  qualityPassRate: 0,
  averageArchiveHours: 0,
  stageBuckets: [],
  departmentWorkloads: []
});

const padDateUnit = (value: number) => String(value).padStart(2, "0");
const toDateText = (date: Date) => `${date.getFullYear()}-${padDateUnit(date.getMonth() + 1)}-${padDateUnit(date.getDate())}`;
const toMonthText = (date: Date) => `${date.getFullYear()}-${padDateUnit(date.getMonth() + 1)}`;
const getMonthRange = (monthText: string) => {
  const [year, month] = monthText.split("-").map(Number);
  const lastDate = new Date(year, month, 0).getDate();
  return {
    from: `${monthText}-01`,
    to: `${monthText}-${padDateUnit(lastDate)}`
  };
};

const todayText = toDateText(new Date());
const activeCalendarMonth = ref(todayText.slice(0, 7));
const selectedCalendarDate = ref("");
const currentRole = computed(() => userStore.userInfo.role || "frontdesk");
const isAdmin = computed(() => currentRole.value === "admin");
const roleName = computed(() => roleLabel(currentRole.value));
const userName = computed(() => userStore.userInfo.name || "同事");
const department = computed(() => userStore.userInfo.department || "门诊");
const editableSections = computed(() => recordSections.filter(section => canEditSection(currentRole.value, section)));
const firstEditableSection = computed(() => editableSections.value[0] ?? recordSections[0]);

// 紧凑 header 的问候语与日期（替代原 GreetingBanner 的 30 秒轮换横幅）
const greetingText = computed(() => {
  const hour = new Date().getHours();
  if (hour < 6) return "夜深了";
  if (hour < 9) return "早上好";
  if (hour < 12) return "上午好";
  if (hour < 14) return "中午好";
  if (hour < 18) return "下午好";
  return "晚上好";
});
const headerDateText = computed(() => {
  const now = new Date();
  const weekdays = ["日", "一", "二", "三", "四", "五", "六"];
  return `${now.getMonth() + 1} 月 ${now.getDate()} 日 · 周${weekdays[now.getDay()]}`;
});
const dashboardOpen = ref(true);
const todoOpen = ref(false);
const maintenanceOpen = ref(false);

// 严格按后端下发的菜单权限决定加载哪块工作面板——绝不调用本岗位无权的接口。
const menuPaths = computed(() => new Set(authStore.flatMenuListGet.map(item => item.path)));
const showPatientBoard = computed(() => menuPaths.value.has("/patients/list"));
const showPharmacyBoard = computed(() => !showPatientBoard.value && menuPaths.value.has("/tcm-pharmacy/workbench"));

// 真待办聚合：只保留可直接执行、数字与列表页同源的入口卡；按菜单权限自适应裁剪。
const primaryTodoCards = computed<StatCard[]>(() => {
  if (!showPatientBoard.value) return [];
  const cards: StatCard[] = [
    {
      id: "todo-pending",
      label: "本岗位待处理",
      count: pendingRows.value.length,
      desc: "点名到人，点击直达患者档案",
      tone: pendingRows.value.length ? "warning" : "success",
      path: "/patients/overview"
    }
  ];
  if (menuPaths.value.has("/audit/review")) {
    cards.push(
      {
        id: "todo-review",
        label: "待档案审核",
        count: stats.value.reviewPatients,
        desc: "等待质控审核的档案",
        tone: stats.value.reviewPatients ? "warning" : "success",
        path: "/audit/review"
      },
      {
        id: "todo-returned",
        label: "退回整改",
        count: stats.value.returnedPatients,
        desc: "需按原因补齐后重新提交",
        tone: stats.value.returnedPatients ? "danger" : "success",
        path: "/audit/review"
      }
    );
  }
  cards.push({
    id: "todo-overdue",
    label: "超 24h 未更新",
    count: stats.value.overduePatients,
    desc: "长时间停留在同一阶段",
    tone: stats.value.overduePatients ? "warning" : "success",
    path: "/patients/overview"
  });
  if (menuPaths.value.has("/workbench/upload")) {
    cards.push({
      id: "todo-attachment",
      label: "附件待补",
      count: attachmentTodoRows.value.length,
      desc: "缺少证据附件的档案",
      tone: attachmentTodoRows.value.length ? "warning" : "success",
      path: "/workbench/upload"
    });
  }
  if (isAdmin.value) {
    const duplicateReminder = workReminders.value.find(item => /重复/.test(item.title));
    if (duplicateReminder) {
      cards.push({
        id: "todo-duplicates",
        label: "疑似重复患者",
        count: duplicateReminder.count ?? 0,
        desc: duplicateReminder.desc || "合并前请人工核对",
        tone: duplicateReminder.level === "danger" ? "danger" : duplicateReminder.count ? "warning" : "success",
        path: duplicateReminder.path || "/patients/overview"
      });
    }
    const fileReminder = workReminders.value.find(item => /缺失|附件完整/.test(item.title));
    if (fileReminder) {
      cards.push({
        id: "todo-files",
        label: "附件磁盘缺失",
        count: fileReminder.count ?? 0,
        desc: fileReminder.desc || "巡检发现的磁盘缺失附件",
        tone: fileReminder.level === "danger" ? "danger" : fileReminder.count ? "warning" : "success",
        path: fileReminder.path || "/documents/recycle"
      });
    }
  }
  const urgentCard = cards.find(card => card.tone === "danger" && Number(card.count) > 0);
  if (urgentCard) urgentCard.urgent = true;
  return cards;
});

// admin 生产维护摘要行的徽标（展开后才显示完整维护面板）
const maintenanceBadgeText = computed(() => {
  if (maintenanceLoading.value) return "巡检中…";
  if (backupStatus.value?.running) return "备份运行中";
  if (backupStatus.value?.latestRun?.status === "failed") return "备份失败";
  const missing = maintenanceStatus.value?.storage?.missingFileCount;
  if (typeof missing === "number" && missing > 0) return `附件缺失 ${missing}`;
  return "运行正常";
});
const maintenanceTone = computed(() => {
  if (backupStatus.value?.latestRun?.status === "failed") return "danger";
  const missing = maintenanceStatus.value?.storage?.missingFileCount;
  if (typeof missing === "number" && missing > 0) return "warning";
  return "success";
});

const {
  quickEntries,
  formatBytes,
  storageSummary,
  snapshotSummary,
  latestBackupSummary,
  backupStorageSummary,
  backupHealthItems
} = useHomeDashboard({
  maintenanceStatus,
  backupStatus
});

const patientStatusFlags = computed(
  () => new Map(patientRows.value.map(patient => [patient.id, classifyPatientStatus(patient)] as const))
);
const statusFlagsForPatient = (patient: PatientRow) => patientStatusFlags.value.get(patient.id) || classifyPatientStatus(patient);
const pendingRows = computed(() => patientRows.value.filter(item => statusFlagsForPatient(item).isPending));
const returnedRows = computed(() => patientRows.value.filter(item => statusFlagsForPatient(item).isReturned));
const reviewRows = computed(() => patientRows.value.filter(item => statusFlagsForPatient(item).isReviewPending));
const attachmentTodoRows = computed(() => patientRows.value.filter(item => statusFlagsForPatient(item).isAttachmentTodo));
const registrationTodoRows = computed(() => patientRows.value.filter(item => statusFlagsForPatient(item).isRegistrationTodo));
const rolePendingRows = computed(() =>
  pendingRows.value.filter(patient => {
    const stage = patient.currentStage || "";
    if (["lab", "ecg", "ultrasound", "inspection"].includes(currentRole.value)) {
      return /检查|检验|影像|心电|B超|筛查|附件/.test(stage);
    }
    if (currentRole.value === "doctor")
      return /医师|诊断|治疗|方案|手术|中医/.test(stage) || statusFlagsForPatient(patient).riskTone === "warning";
    if (["nurse", "nursing"].includes(currentRole.value)) return /护理|宣教|住院|出院|随访/.test(stage);
    if (currentRole.value === "quality") return reviewRows.value.some(row => row.id === patient.id);
    if (currentRole.value === "frontdesk") return registrationTodoRows.value.some(row => row.id === patient.id);
    return true;
  })
);

const roleActionConfig = computed(() => {
  const role = currentRole.value;
  if (role === "frontdesk") {
    return [
      {
        id: "frontdesk-create",
        title: "新建/登记患者",
        desc: "录入基础信息、来院来源和分诊入口",
        count: "建档",
        level: "info",
        actionText: "去前置病历",
        path: "/pre-ai/encounters"
      },
      {
        id: "frontdesk-basic",
        title: "基础信息待补",
        desc: "优先处理建档不完整或今日未闭环患者",
        count: registrationTodoRows.value.length,
        level: registrationTodoRows.value.length ? "warning" : "success",
        actionText: "查看概览",
        path: "/patients/overview"
      },
      {
        id: "frontdesk-legacy",
        title: "旧资料待迁移",
        desc: "共享文件夹资料先预检再采纳入档",
        count: stats.value.documentCount,
        level: "info",
        actionText: "导入资料",
        path: "/workbench/legacy"
      }
    ];
  }
  if (["lab", "ecg", "ultrasound", "inspection"].includes(role)) {
    return [
      {
        id: "inspection-upload",
        title: "待上传检查证据",
        desc: "检查室以图片/附件证据为主，可选补充简短备注",
        count: attachmentTodoRows.value.length,
        level: attachmentTodoRows.value.length ? "warning" : "success",
        actionText: "上传资料",
        path: "/workbench/upload"
      },
      {
        id: "inspection-fields",
        title: "本科室待填字段",
        desc: "只处理当前岗位可编辑的检查/筛查字段",
        count: rolePendingRows.value.length,
        level: rolePendingRows.value.length ? "warning" : "success",
        actionText: "进入概览",
        path: "/patients/overview"
      },
      {
        id: "inspection-returned",
        title: "退回整改",
        desc: "质控退回后优先补齐原始证据",
        count: returnedRows.value.length,
        level: returnedRows.value.length ? "danger" : "success",
        actionText: "查看退回",
        path: "/audit/review"
      }
    ];
  }
  if (role === "quality") {
    return [
      {
        id: "quality-review",
        title: "待审核档案",
        desc: "通过、退回或标记资料异常",
        count: stats.value.reviewPatients,
        level: stats.value.reviewPatients ? "warning" : "success",
        actionText: "开始审核",
        path: "/audit/review"
      },
      {
        id: "quality-returned",
        title: "退回未整改",
        desc: "跟踪仍未闭环的退回档案",
        count: stats.value.returnedPatients,
        level: stats.value.returnedPatients ? "danger" : "success",
        actionText: "查看整改",
        path: "/audit/review"
      },
      {
        id: "quality-log",
        title: "关键操作留痕",
        desc: "查看提交、作废、打印、导入等操作轨迹",
        count: stats.value.voidedDocumentCount,
        level: stats.value.voidedDocumentCount ? "warning" : "info",
        actionText: "看日志",
        path: "/audit/log"
      }
    ];
  }
  if (role === "admin") {
    return [
      {
        id: "admin-backup",
        title: "备份健康巡检",
        desc: latestBackupSummary.value,
        count: backupStatus.value?.running ? "运行中" : backupStatus.value?.backupFileCount || 0,
        level: backupStatus.value?.latestRun?.status === "failed" ? "danger" : "info",
        actionText: "查看面板",
        path: "/"
      },
      {
        id: "admin-overdue",
        title: "今日未闭环",
        desc: "关注超过规则时限仍未闭环的患者",
        count: stats.value.pendingPatients,
        level: stats.value.pendingPatients ? "warning" : "success",
        actionText: "看概览",
        path: "/patients/overview"
      },
      {
        id: "admin-review",
        title: "审核与归档",
        desc: "跟进质控审核、退回整改和资料异常",
        count: stats.value.reviewPatients + stats.value.returnedPatients,
        level: stats.value.returnedPatients ? "danger" : stats.value.reviewPatients ? "warning" : "success",
        actionText: "去审核",
        path: "/audit/review"
      }
    ];
  }
  return [
    {
      id: "clinical-fields",
      title: "我负责的待填字段",
      desc: `${roleName.value}只处理当前岗位可编辑的档案节点`,
      count: rolePendingRows.value.length,
      level: rolePendingRows.value.length ? "warning" : "success",
      actionText: "进入工作台",
      path: "/patients/overview"
    },
    {
      id: "clinical-submit",
      title: "待提交档案",
      desc: "保存后提交质控，减少事后退回",
      count: pendingRows.value.length,
      level: pendingRows.value.length ? "info" : "success",
      actionText: "查看患者",
      path: "/patients/list"
    },
    {
      id: "clinical-returned",
      title: "被退回整改",
      desc: "按退回原因定位到字段或章节补齐",
      count: returnedRows.value.length,
      level: returnedRows.value.length ? "danger" : "success",
      actionText: "处理退回",
      path: "/audit/review"
    }
  ];
});

const actionTasks = computed<ActionTask[]>(() =>
  roleActionConfig.value.slice(0, 5).map(item => ({
    roleLabel: roleName.value,
    ...item,
    level: item.level as ActionTask["level"]
  }))
);

// 岗位统计卡：仅中药房板块使用（患者板块已升级为 primaryTodoCards 真待办聚合）。
const statCards = computed<StatCard[]>(() => {
  if (!showPharmacyBoard.value) return [];
  const counts = tcmCounts.value;
  const value = (key: keyof TcmStatusCounts) => counts?.[key] ?? 0;
  return [
    {
      id: "charge",
      label: "待收费",
      count: value("waitingCharge"),
      desc: "医师已签署提交",
      tone: value("waitingCharge") ? "warning" : "success",
      path: "/tcm-pharmacy/workbench"
    },
    {
      id: "reviewRx",
      label: "待审方",
      count: value("waitingReview"),
      desc: "收费完成待药师审核",
      tone: value("waitingReview") ? "warning" : "success",
      path: "/tcm-pharmacy/workbench"
    },
    {
      id: "dispensing",
      label: "调剂中",
      count: value("dispensing"),
      desc: "抓药与复核进行中",
      tone: "info",
      path: "/tcm-pharmacy/workbench"
    },
    {
      id: "decocting",
      label: "代煎中",
      count: value("decocting"),
      desc: "浸泡、煎制与包装",
      tone: "info",
      path: "/tcm-pharmacy/workbench"
    },
    {
      id: "ready",
      label: "待取药",
      count: value("ready"),
      desc: "可叫号发药",
      tone: value("ready") ? "warning" : "success",
      path: "/tcm-pharmacy/workbench"
    },
    {
      id: "exception",
      label: "异常处方",
      count: value("exception"),
      desc: "缺药或设备异常",
      tone: value("exception") ? "danger" : "success",
      path: "/tcm-pharmacy/workbench"
    }
  ];
});

// 横幅"今天要处理什么"：优先取有告警的项。
const focusTask = computed(() => {
  if (showPatientBoard.value) {
    const task = actionTasks.value.find(item => item.level !== "success") || actionTasks.value[0];
    return task ? { title: task.title, path: task.path, query: task.query } : undefined;
  }
  const card = statCards.value.find(item => ["warning", "danger"].includes(item.tone));
  return card ? { title: card.label, path: card.path, query: card.query } : undefined;
});

// 岗位提醒：admin 用后端全院巡检提醒；其余岗位从自身可见数据本地推导，不发无权请求。
const roleReminders = computed<WorkReminder[]>(() => {
  if (isAdmin.value) return workReminders.value;
  const reminders: WorkReminder[] = [];
  if (showPatientBoard.value) {
    if (stats.value.returnedPatients) {
      reminders.push({
        id: "role-returned",
        title: "退回整改待处理",
        desc: "质控退回的档案请尽快补齐并重新提交",
        count: stats.value.returnedPatients,
        level: "danger",
        path: "/audit/review"
      });
    }
    if (stats.value.overduePatients) {
      reminders.push({
        id: "role-overdue",
        title: "超 24 小时未更新",
        desc: "关注长时间停留在同一阶段的患者",
        count: stats.value.overduePatients,
        level: "warning",
        path: "/patients/overview"
      });
    }
  }
  if (showPharmacyBoard.value && tcmCounts.value) {
    if (tcmCounts.value.exception) {
      reminders.push({
        id: "tcm-exception",
        title: "异常处方待跟进",
        desc: "缺药、设备或生产异常需登记处理",
        count: tcmCounts.value.exception,
        level: "danger",
        path: "/tcm-pharmacy/workbench"
      });
    }
    if (tcmCounts.value.ready) {
      reminders.push({
        id: "tcm-ready",
        title: "成品待叫号",
        desc: "已完成的处方尽快叫号发药",
        count: tcmCounts.value.ready,
        level: "warning",
        path: "/tcm-pharmacy/workbench"
      });
    }
  }
  return reminders;
});

const patientEncounterDates = (patient: PatientRow) => {
  const history = patient.encounterHistory?.length
    ? patient.encounterHistory
    : [{ visitDate: patient.visitDate, visitNo: patient.visitNo, visitType: patient.visitType, doctor: patient.doctor }];
  // 归一化为 YYYY-MM-DD：visitDate 可能是带时间的完整串（含 T 分隔），整串当键会导致趋势/热力全 0
  return [
    ...new Set(
      history
        .map(item => String(item.visitDate || ""))
        .filter(Boolean)
        .map(date => date.replace("T", " ").slice(0, 10))
    )
  ];
};

const countByDate = computed(() => {
  const counter = new Map<string, number>();
  patientRows.value.forEach(patient => {
    patientEncounterDates(patient).forEach(date => {
      counter.set(date, (counter.get(date) || 0) + 1);
    });
  });
  return counter;
});

const rangeCount = (from: string, to: string) =>
  patientRows.value.filter(patient => patientEncounterDates(patient).some(date => date >= from && date <= to)).length;

// 趋势窗口：近 7 日 / 近 14 天 / 近一个月（近 30 天滚动，含今天）
const trendRange = ref(7);
const trendRangeOptions = [
  { label: "近 7 日", value: 7 },
  { label: "近 14 天", value: 14 },
  { label: "近一个月", value: 30 }
];
const trendSwitching = ref(false);
watch(trendRange, () => {
  trendSwitching.value = true;
  window.setTimeout(() => {
    trendSwitching.value = false;
  }, 320);
});

const patientsByEncounterDate = computed(() => {
  const grouped = new Map<string, PatientRow[]>();
  patientRows.value.forEach(patient => {
    patientEncounterDates(patient).forEach(date => {
      if (!grouped.has(date)) grouped.set(date, []);
      grouped.get(date)?.push(patient);
    });
  });
  return grouped;
});

// 前置病例（仅管理员加载）：登记主诉 → 曲线悬浮词典卡
const preAiCases = ref<PreAiPatientCase[]>([]);
const complaintKeyMap = computed(() => {
  const map = new Map<string, string>();
  preAiCases.value.forEach(cases => {
    const complaint = String(cases.patient?.registrationChiefComplaint || cases.patient?.registrationSymptoms || "").trim();
    if (!complaint) return;
    if (cases.sourcePatientId) map.set(cases.sourcePatientId, complaint);
    if (cases.patientName) map.set(cases.patientName, complaint);
  });
  return map;
});
const complaintForPatient = (patient: PatientRow) =>
  complaintKeyMap.value.get(patient.id) || complaintKeyMap.value.get(patient.name) || "";

// 病种分布（仅管理员）：登记病种模板分类 → 窗口内来访患者按病种去重计数
const diseasesKeyMap = computed(() => {
  const map = new Map<string, string[]>();
  preAiCases.value.forEach(cases => {
    const templateDiseases = Array.isArray(cases.patient?.clinicalTemplateDiseases)
      ? cases.patient.clinicalTemplateDiseases.map(String)
      : [];
    const aiTags = Array.isArray(cases.patient?.aiDiseaseTags) ? cases.patient.aiDiseaseTags.map(String) : [];
    // 模板明确分类（医生确认）与 AI 归类标签合并去重
    const names = [...new Set([...templateDiseases, ...aiTags])].filter(Boolean);
    if (!names.length) return;
    if (cases.sourcePatientId) map.set(cases.sourcePatientId, names);
    if (cases.patientName) map.set(cases.patientName, names);
  });
  return map;
});
const diseaseStats = computed(() => {
  if (!preAiCases.value.length) return [];
  const windowKeys = new Set<string>();
  const patientsByDisease = new Map<string, Set<string>>();
  for (let offset = trendRange.value - 1; offset >= 0; offset--) {
    const date = new Date();
    date.setDate(date.getDate() - offset);
    const dateText = toDateText(date);
    for (const patient of patientsByEncounterDate.value.get(dateText) || []) {
      const key = patient.id || patient.name;
      windowKeys.add(key);
      for (const disease of diseasesKeyMap.value.get(patient.id) || diseasesKeyMap.value.get(patient.name) || []) {
        if (!patientsByDisease.has(disease)) patientsByDisease.set(disease, new Set());
        patientsByDisease.get(disease)!.add(key);
      }
    }
  }
  const stats = [...patientsByDisease.entries()]
    .map(([disease, patients]) => ({ disease, count: patients.size }))
    .sort((a, b) => b.count - a.count);
  // 待归类兜底：窗口总人数 - 已有任一病种标签的人数，保证与窗口合计对账
  const tagged = new Set<string>();
  patientsByDisease.values().forEach(keys => keys.forEach(key => tagged.add(key)));
  if (windowKeys.size > tagged.size) stats.push({ disease: "待归类", count: windowKeys.size - tagged.size });
  return stats;
});
const loadPreAiCases = async () => {
  try {
    const { data } = await getPreAiPatientCasesApi();
    preAiCases.value = data.list || [];
  } catch {
    preAiCases.value = [];
  }
};
const onRetagDiseases = async () => {
  try {
    const { data } = await runDiseaseTaggingApi();
    ElMessage[data.started ? "success" : "warning"](data.message || "AI 归类任务已启动");
    if (data.started) window.setTimeout(() => void loadPreAiCases(), 90_000);
  } catch (error) {
    ElMessage.error((error as Error).message || "触发 AI 归类失败");
  }
};

const dailyCurveItems = computed(() => {
  const items: {
    date: string;
    label: string;
    total: number;
    patients?: { name: string; complaint: string }[];
  }[] = [];
  for (let offset = trendRange.value - 1; offset >= 0; offset--) {
    const date = new Date();
    date.setDate(date.getDate() - offset);
    const dateText = toDateText(date);
    const rows = patientsByEncounterDate.value.get(dateText) || [];
    items.push({
      date: dateText,
      label: offset === 0 ? "今天" : `${date.getMonth() + 1}/${date.getDate()}`,
      total: rows.length,
      // 悬浮词典卡明细：最多 10 条（主诉仅管理员可见）
      patients: rows.slice(0, 10).map(patient => ({ name: patient.name, complaint: complaintForPatient(patient) }))
    });
  }
  return items;
});

const trendItems = computed(() => dailyCurveItems.value.map(({ label, total }) => ({ label, value: total })));

const trendSummary = computed(() => {
  const values = trendItems.value.map(item => item.value);
  const total = values.reduce((sum, value) => sum + value, 0);
  let peakIndex = 0;
  values.forEach((value, index) => {
    if (value > values[peakIndex]) peakIndex = index;
  });
  return {
    total,
    avg: values.length ? Math.round(total / values.length) : 0,
    peakDay: values.length ? trendItems.value[peakIndex] : undefined
  };
});

const trendTitle = computed(() => `近 ${trendRange.value} 日就诊收录`);

const pharmacyChartItems = computed(() => {
  const counts = tcmCounts.value;
  if (!counts) return [];
  return [
    { label: "待收费", value: counts.waitingCharge },
    { label: "待审方", value: counts.waitingReview },
    { label: "调剂中", value: counts.dispensing },
    { label: "代煎中", value: counts.decocting },
    { label: "待取药", value: counts.ready },
    { label: "今日已取", value: counts.collectedToday }
  ];
});

const weekdayLabels = ["一", "二", "三", "四", "五", "六", "日"];
const calendarMonthRange = computed(() => getMonthRange(activeCalendarMonth.value));
const calendarMonthTitle = computed(() => {
  const [year, month] = activeCalendarMonth.value.split("-");
  return `${year} 年 ${Number(month)} 月`;
});
const currentMonthDateTexts = computed(() => {
  const [year, month] = activeCalendarMonth.value.split("-").map(Number);
  const dayCount = new Date(year, month, 0).getDate();
  return Array.from({ length: dayCount }, (_, index) => `${activeCalendarMonth.value}-${padDateUnit(index + 1)}`);
});
const calendarPeakCount = computed(() =>
  Math.max(0, ...currentMonthDateTexts.value.map(date => countByDate.value.get(date) || 0))
);
const calendarMonthTotal = computed(() => rangeCount(calendarMonthRange.value.from, calendarMonthRange.value.to));

const heatLevel = (count: number) => {
  if (!count) return 0;
  const peak = calendarPeakCount.value || 1;
  return Math.max(1, Math.min(4, Math.ceil((count / peak) * 4)));
};

const calendarCells = computed<CalendarDayCell[]>(() => {
  const [year, month] = activeCalendarMonth.value.split("-").map(Number);
  const firstDate = new Date(year, month - 1, 1);
  const leadingBlankCount = (firstDate.getDay() + 6) % 7;
  const blanks = Array.from({ length: leadingBlankCount }, (_, index) => ({
    key: `blank::${activeCalendarMonth.value}::${index}`,
    date: "",
    day: 0,
    count: 0,
    level: 0,
    isBlank: true,
    isToday: false,
    isSelected: false,
    ariaLabel: "空白日期",
    hoverColor: ""
  }));
  const days = currentMonthDateTexts.value.map(date => {
    const count = countByDate.value.get(date) || 0;
    const day = Number(date.slice(-2));
    return {
      key: `date::${date}`,
      date,
      day,
      count,
      level: heatLevel(count),
      isBlank: false,
      isToday: date === todayText,
      isSelected: date === selectedCalendarDate.value,
      ariaLabel: `${date} 收录 ${count} 人`,
      hoverColor: ""
    };
  });
  const allCells = [...blanks, ...days];
  // hover 强调色：按日历网格邻接（同行左右、同列上下跨行）贪心分配，相邻格必不同色
  const hoverPalette = ["#0d9488", "#2563eb", "#d97706", "#7c3aed", "#db2777"];
  const hoverColors: string[] = new Array(allCells.length).fill("");
  allCells.forEach((cell, index) => {
    if (cell.isBlank) return;
    const col = index % 7;
    const used = new Set<string>();
    const neighbors: number[] = [];
    // 尾行可能不满 7 格：左右邻接必须同时校验数组边界，否则越界 undefined 读取 isBlank 崩页
    if (col > 0 && index - 1 >= 0) neighbors.push(index - 1);
    if (col < 6 && index + 1 < allCells.length) neighbors.push(index + 1);
    if (index - 7 >= 0) neighbors.push(index - 7);
    if (index + 7 < allCells.length) neighbors.push(index + 7);
    neighbors.forEach(neighborIndex => {
      if (!allCells[neighborIndex].isBlank && hoverColors[neighborIndex]) used.add(hoverColors[neighborIndex]);
    });
    hoverColors[index] = hoverPalette.find(color => !used.has(color)) ?? hoverPalette[index % hoverPalette.length];
  });
  allCells.forEach((cell, index) => {
    cell.hoverColor = hoverColors[index];
  });
  return allCells;
});

const taskCards = computed<HomeTask[]>(() =>
  pendingRows.value.slice(0, 8).map(patient => ({
    id: `${patient.id}-${firstEditableSection.value.key}`,
    title: patient.currentStage || firstEditableSection.value.stage,
    desc: `${firstEditableSection.value.owner}处理：${firstEditableSection.value.department}`,
    sectionKey: firstEditableSection.value.key,
    patient
  }))
);

const loadPrimaryDashboard = async () => {
  dashboardLoading.value = true;
  try {
    const [{ data: patients }, { data: operationStats }] = await Promise.all([
      getPatientListApi({ pageNum: 1, pageSize: 5000 }),
      getOperationStatsApi()
    ]);
    patientRows.value = patients.list;
    stats.value = operationStats;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    dashboardLoading.value = false;
  }
  // 管理员额外加载前置病例（登记主诉/病种标签）供曲线词典卡与病种分布使用；失败静默不打扰主视图
  if (isAdmin.value) await loadPreAiCases();
};

const loadPharmacyBoard = async () => {
  try {
    const { data } = await getTcmDashboardApi();
    tcmCounts.value = data.counts;
  } catch (error) {
    ElMessage.error((error as Error).message);
  }
};

// 仅管理员拉取全院巡检/备份数据，其余岗位不发起这些无权请求。
const loadMaintenanceDashboard = async (options: { fullMaintenanceScan?: boolean } = {}) => {
  if (!isAdmin.value) return;
  maintenanceLoading.value = true;
  try {
    const maintenanceRequest = options.fullMaintenanceScan ? getMaintenanceStatusApi : getMaintenanceSummaryApi;
    const [{ data: reminders }, { data: status }] = await Promise.all([getWorkRemindersApi(), maintenanceRequest()]);
    workReminders.value = reminders;
    maintenanceStatus.value = status;
    const { data: backup } = await getBackupStatusApi();
    backupStatus.value = backup;
    backupPath.value = backup.backupDir;
    backupEnabled.value = backup.enabled;
  } catch (error) {
    ElMessage.warning(`生产巡检暂不可用：${(error as Error).message}`);
  } finally {
    maintenanceLoading.value = false;
  }
};

const reloadAll = async () => {
  const jobs: Promise<unknown>[] = [];
  if (showPatientBoard.value) jobs.push(loadPrimaryDashboard());
  if (showPharmacyBoard.value) jobs.push(loadPharmacyBoard());
  if (isAdmin.value) {
    jobs.push(loadMaintenanceDashboard());
    jobs.push(loadPolicyBriefLatest());
  }
  await Promise.allSettled(jobs);
};

// 医政早报首页卡：最新一日概览（失败静默，不打扰待办主视图）
const policyBriefLatest = ref<PolicyBriefResult | null>(null);
const loadPolicyBriefLatest = async () => {
  try {
    const { data } = await getPolicyBriefLatestApi();
    policyBriefLatest.value = data;
  } catch {
    policyBriefLatest.value = null;
  }
};

// 卡片概览行：最多 10 条，按分类配 emoji 前缀（DIP💰 政策📋 肛肠🔬 热点🔥 兜底📰）
const BRIEF_EMOJI: Record<string, string> = { DIP: "💰", POLICY: "📋", ANORECTAL: "🔬", HOT: "🔥" };
const briefRows = computed(() =>
  (policyBriefLatest.value?.items ?? []).slice(0, 10).map(item => ({
    id: item.id,
    emoji: BRIEF_EMOJI[item.category] || "📰",
    text: item.aiSummary || item.title
  }))
);

const saveBackupConfig = async () => {
  const path = backupPath.value.trim();
  if (!path) {
    ElMessage.warning("请先填写备份路径");
    return false;
  }
  backupLoading.value = true;
  try {
    const { data } = await saveBackupConfigApi({ backupDir: path, enabled: backupEnabled.value });
    backupStatus.value = data;
    backupPath.value = data.backupDir;
    backupEnabled.value = data.enabled;
    ElMessage.success("备份路径已保存");
    return true;
  } catch (error) {
    ElMessage.error((error as Error).message);
    return false;
  } finally {
    backupLoading.value = false;
  }
};

const chooseBackupDirectory = async () => {
  choosingBackupDir.value = true;
  try {
    const { data } = await chooseBackupDirectoryApi(backupPath.value.trim() || backupStatus.value?.backupDir || "");
    backupPath.value = data.backupDir;
    ElMessage.success("已选择备份目录，请确认后保存路径");
  } catch (error) {
    const message = (error as Error).message;
    if (!message.includes("取消")) ElMessage.warning(message);
  } finally {
    choosingBackupDir.value = false;
  }
};

const runBackupNow = async () => {
  if (backupPath.value.trim() && backupPath.value.trim() !== backupStatus.value?.backupDir) {
    const saved = await saveBackupConfig();
    if (!saved) return;
  }
  backupLoading.value = true;
  try {
    const { data } = await runBackupNowApi();
    ElMessage.success(`备份已完成：${formatBytes(data.sizeBytes)}`);
    const { data: status } = await getBackupStatusApi();
    backupStatus.value = status;
    backupPath.value = status.backupDir;
    backupEnabled.value = status.enabled;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    backupLoading.value = false;
  }
};

const createSnapshot = async () => {
  maintenanceLoading.value = true;
  try {
    const { data } = await createMaintenanceSnapshotApi();
    ElMessage.success(`快照已生成，当前共 ${data.snapshotCount} 个`);
    await reloadAll();
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    maintenanceLoading.value = false;
  }
};

const shiftCalendarMonth = (offset: number) => {
  const [year, month] = activeCalendarMonth.value.split("-").map(Number);
  activeCalendarMonth.value = toMonthText(new Date(year, month - 1 + offset, 1));
};

const jumpToCurrentMonth = () => {
  activeCalendarMonth.value = todayText.slice(0, 7);
  selectedCalendarDate.value = "";
};

const selectCalendarMonth = () => {
  selectedCalendarDate.value = "";
  router.push({ path: "/patients/list", query: { month: activeCalendarMonth.value } });
};

const selectCalendarDate = (day: CalendarDayCell) => {
  if (day.isBlank) return;
  selectedCalendarDate.value = day.date;
  router.push({ path: "/patients/list", query: { date: day.date } });
};

const openTask = (task?: HomeTask) => {
  if (!task) return;
  router.push({ path: `/patients/detail/${task.patient.id}`, query: { section: task.sectionKey } });
};

const openActionTask = (task?: ActionTask) => {
  if (!task) return;
  // 备份健康卡：展开同页的生产维护面板（替代原先指向 "/" 的死链）
  if (task.path === "/") {
    maintenanceOpen.value = true;
    return;
  }
  router.push({ path: task.path, query: task.query });
};

const openStatCard = (card: StatCard) => {
  router.push({ path: card.path, query: card.query });
};

const navigateTo = (path: string) => {
  router.push(path);
};

onMounted(reloadAll);
</script>

<style scoped lang="scss">
// 页面级状态 token：全部指向全局 --hos-* 变量，html.dark 下自动切换，无硬编码浅色
.home-page {
  --clinic-success: var(--hos-status-success, #16a34a);
  --clinic-warning: var(--hos-status-warning, #b45309);
  --clinic-danger: var(--hos-status-danger, #dc2626);
  --clinic-info: var(--hos-chart-info, var(--hos-primary, #0f766e));
  --clinic-success-soft: var(--hos-status-success-soft, #ecf8f0);
  --clinic-warning-soft: var(--hos-status-warning-soft, #fef7e8);
  --clinic-danger-soft: var(--hos-status-danger-soft, #fdeeee);

  display: grid;
  gap: 14px;
  padding: 4px 2px 16px;
}
.board-card {
  padding: 16px;
  background: var(--hos-chart-panel, var(--el-bg-color));
  border: 1px solid var(--hos-chart-line-soft, var(--el-border-color-light));
  border-radius: 12px;
}

// 医政早报首页卡（仅管理员）：统计卡行式布局 + emoji 概览 + 悬浮呼吸光晕
@keyframes brief-breathe {
  0%,
  100% {
    box-shadow: 0 10px 26px color-mix(in srgb, var(--el-color-primary) 12%, transparent);
  }
  50% {
    box-shadow: 0 16px 38px color-mix(in srgb, var(--el-color-primary) 26%, transparent);
  }
}
.policy-brief-card {
  display: grid;
  gap: 10px;
  padding: 16px 18px 10px;
  text-align: left;
  cursor: pointer;
  transition:
    border-color var(--motion-control, 180ms) var(--ease-out, ease),
    box-shadow var(--motion-control, 180ms) var(--ease-out, ease),
    transform var(--motion-control, 180ms) var(--ease-out, ease);

  @media (hover: hover) and (pointer: fine) {
    &:hover {
      border-color: color-mix(in srgb, var(--el-color-primary) 45%, var(--el-border-color-light));
      box-shadow: 0 12px 30px color-mix(in srgb, var(--el-color-primary) 16%, transparent);
      transform: translateY(-2px);
      animation: brief-breathe 2.4s var(--ease-in-out, ease-in-out) infinite;
      .brief-card-more {
        color: var(--el-color-primary);
      }
      .brief-row-text {
        color: var(--el-text-color-primary);
      }
    }
  }
  .brief-card-head {
    display: flex;
    gap: 8px;
    align-items: center;
    font-size: 15px;
    color: var(--el-text-color-primary);
    .el-icon {
      color: var(--el-color-primary);
    }
    small {
      color: var(--el-text-color-secondary);
    }
    .brief-card-more {
      display: inline-flex;
      gap: 2px;
      align-items: center;
      font-size: 12px;
      color: var(--el-text-color-secondary);
      transition: color var(--motion-control, 180ms) var(--ease-out, ease);
    }
  }

  // 概览行：虚线分隔（对齐统计卡行式布局），末行无分隔线
  .brief-card-rows {
    display: grid;
  }
  .brief-row {
    display: flex;
    gap: 8px;
    align-items: center;
    padding: 7px 2px;
    border-bottom: 1px dashed var(--el-border-color-lighter);
    &:last-child {
      border-bottom: none;
    }
    .brief-row-emoji {
      flex-shrink: 0;
      font-size: 14px;
      font-style: normal;
    }
    .brief-row-text {
      overflow: hidden;
      font-size: 13px;
      line-height: 1.5;
      color: var(--el-text-color-regular);
      text-overflow: ellipsis;
      white-space: nowrap;
      transition: color var(--motion-control, 180ms) var(--ease-out, ease);
    }
  }
  .brief-card-empty {
    font-size: 12px;
    color: var(--el-text-color-placeholder);
  }
}

@media (prefers-reduced-motion: reduce) {
  .policy-brief-card:hover {
    animation: none;
  }
}

// 紧凑 header
.home-header {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}
.header-main {
  display: grid;
  gap: 6px;
  min-width: 0;
  h2 {
    margin: 0;
    font-size: 20px;
  }
}
.header-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.role-badge {
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--hos-primary, var(--el-color-primary));
  background: var(--el-color-primary-light-9);
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 22%, transparent);
  border-radius: 999px;
}
.header-focus {
  font-weight: 500;
  color: var(--el-text-color-regular);
}

// 真待办聚合入口卡
.todo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 12px;
}
.todo-card {
  position: relative;
  display: grid;
  gap: 4px;
  width: 100%;
  padding: 16px;
  overflow: hidden;
  text-align: left;
  cursor: pointer;
  background: var(--hos-chart-panel, var(--el-bg-color));
  border: 1px solid var(--hos-chart-line-soft, var(--el-border-color-light));
  border-radius: 12px;
  transition:
    transform var(--motion-fast, 140ms) var(--ease-out, ease),
    box-shadow var(--motion-fast, 140ms) var(--ease-out, ease),
    border-color var(--motion-fast, 140ms) var(--ease-out, ease);

  @media (hover: hover) and (pointer: fine) {
    &:hover {
      border-color: color-mix(
        in srgb,
        var(--hos-chart-primary, var(--el-color-primary)) 30%,
        var(--hos-chart-line-soft, var(--el-border-color-light))
      );
      box-shadow: 0 8px 20px color-mix(in srgb, var(--hos-chart-primary, var(--el-color-primary)) 10%, transparent);
      transform: translateY(-2px);
    }
  }
  .todo-label {
    display: inline-flex;
    gap: 4px;
    align-items: center;
    font-size: 13px;
    font-weight: 600;
    color: var(--el-text-color-secondary);
  }
  .todo-count {
    font-size: 30px;
    font-variant-numeric: tabular-nums;
    line-height: 1.15;
    color: var(--el-text-color-primary);
  }
  small {
    overflow: hidden;
    font-size: 12px;
    color: var(--hos-chart-muted, var(--el-text-color-placeholder));
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  &.is-warning .todo-count {
    color: var(--clinic-warning);
  }
  &.is-danger .todo-count {
    color: var(--clinic-danger);
  }
  &.is-success .todo-count {
    color: var(--clinic-success);
  }
  &.is-info .todo-count {
    color: var(--clinic-info);
  }
}

// 数据看板折叠区
.dashboard-fold {
  display: grid;
  gap: 12px;
  padding: 0;
  .fold-head {
    display: flex;
    gap: 10px;
    align-items: center;
    width: 100%;
    padding: 13px 16px;
    text-align: left;
    cursor: pointer;
    background: transparent;
    border: 0;
    strong {
      font-size: 14px;
    }
    small {
      color: var(--el-text-color-secondary);
    }
    &:hover strong {
      color: var(--hos-chart-primary, var(--el-color-primary));
    }
  }
  .fold-spacer {
    flex: 1;
  }
  .fold-arrow {
    color: var(--el-text-color-secondary);
    transition: transform var(--motion-control, 180ms) var(--ease-out, ease);
    &.open {
      transform: rotate(180deg);
    }
  }
  .fold-body {
    display: grid;
    gap: 16px;
    padding: 4px 16px 16px;
    border-top: 1px solid var(--hos-chart-line-soft, var(--el-border-color-lighter));
  }
}
.chart-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 22px;
}
.trend-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}
.trend-progress {
  width: 100%;
}
.summary-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-left: auto;
  .chip {
    padding: 3px 10px;
    font-size: 12px;
    color: var(--hos-chart-muted, var(--el-text-color-secondary));
    background: var(--hos-chart-panel-soft, var(--el-fill-color-light));
    border-radius: 999px;
    b {
      font-variant-numeric: tabular-nums;
      color: var(--el-color-primary);
    }
  }
}
.todo-fold :deep(.panel-head) {
  display: none;
}
.todo-panel-body {
  border-top: 1px solid var(--el-border-color-lighter);
}

// 侧栏维护摘要行（admin）
.maintenance-summary {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 10px 0 2px;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-top: 1px solid var(--el-border-color-lighter);
  strong {
    flex: 1;
    font-size: 13px;
  }
  &:hover strong {
    color: var(--el-color-primary);
  }
}
.maint-badge {
  padding: 2px 9px;
  font-size: 12px;
  font-weight: 600;
  border-radius: 999px;
  &.is-success {
    color: var(--clinic-success);
    background: var(--clinic-success-soft);
  }
  &.is-warning {
    color: var(--clinic-warning);
    background: var(--clinic-warning-soft);
  }
  &.is-danger {
    color: var(--clinic-danger);
    background: var(--clinic-danger-soft);
  }
}
.workbench-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 360px);
  gap: 14px;
  align-items: start;
}
.workbench-main {
  display: grid;
  gap: 14px;
  min-width: 0;
}
.workbench-side {
  min-width: 0;
}
.pharmacy-chart {
  min-width: 0;
}

// 药房统计卡（沿用原视觉，token 已接全局变量）
.stat-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}
.stat-card {
  display: grid;
  gap: 3px;
  padding: 14px 16px;
  text-align: left;
  cursor: pointer;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  transition:
    transform var(--motion-fast, 140ms) var(--ease-out, ease),
    box-shadow var(--motion-fast, 140ms) var(--ease-out, ease),
    border-color var(--motion-fast, 140ms) var(--ease-out, ease);

  @media (hover: hover) and (pointer: fine) {
    &:hover {
      border-color: color-mix(
        in srgb,
        var(--hos-chart-primary, var(--el-color-primary)) 26%,
        var(--hos-chart-line-soft, var(--el-border-color-light))
      );
      box-shadow: 0 7px 18px color-mix(in srgb, var(--hos-chart-primary, var(--el-color-primary)) 9%, transparent);
      transform: translateY(-2px);
    }
  }
  span {
    font-size: 13px;
    font-weight: 600;
    color: var(--el-text-color-secondary);
  }
  strong {
    font-size: 26px;
    font-variant-numeric: tabular-nums;
    line-height: 1.15;
    color: var(--el-text-color-primary);
  }
  small {
    overflow: hidden;
    font-size: 12px;
    color: var(--hos-chart-muted, var(--el-text-color-placeholder));
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  &.is-warning strong {
    color: var(--clinic-warning);
  }
  &.is-danger strong {
    color: var(--clinic-danger);
  }
  &.is-success strong {
    color: var(--clinic-success);
  }
}

@media (prefers-reduced-motion: reduce) {
  .todo-card,
  .stat-card,
  .fold-arrow {
    transition: none;
  }
  .todo-card:hover,
  .stat-card:hover {
    transform: none;
  }
}

@media (width <= 1080px) {
  .workbench-grid {
    grid-template-columns: 1fr;
  }
  .chart-row {
    grid-template-columns: 1fr;
  }
}
</style>
