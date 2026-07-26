<template>
  <div class="home-page">
    <GreetingBanner
      :user-name="userName"
      :role-name="roleName"
      :department="department"
      :task-title="focusTask?.title"
      @open-first="openFocusTask"
    />

    <section class="stat-strip">
      <button v-for="card in statCards" :key="card.id" class="stat-card" :class="`is-${card.tone}`" @click="openStatCard(card)">
        <span>{{ card.label }}</span>
        <strong>{{ card.count }}</strong>
        <small>{{ card.desc }}</small>
      </button>
    </section>

    <div class="workbench-grid">
      <div class="workbench-main">
        <template v-if="showPatientBoard">
          <HomeTaskPanel
            v-loading="dashboardLoading"
            class="board-card"
            :role-name="roleName"
            :action-tasks="actionTasks"
            :task-cards="taskCards"
            @refresh="reloadAll"
            @open-task="openTask"
            @open-action-task="openActionTask"
          />
          <div class="board-card chart-row">
            <MiniBarChart title="近 7 日就诊收录" subtitle="按就诊日期" :items="trendItems" unit=" 人" />
            <MiniBarChart title="在办阶段分布" subtitle="当前流程所处阶段" :items="stageItems" unit=" 人" />
          </div>
          <CalendarHeatmap
            class="board-card"
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
        </template>
        <template v-else-if="showPharmacyBoard">
          <div class="board-card">
            <MiniBarChart title="处方状态分布" subtitle="中药房当前流水线" :items="pharmacyChartItems" unit=" 张" />
          </div>
        </template>
        <template v-else-if="showInventoryBoard">
          <div class="board-card">
            <MiniBarChart title="库存工作概况" subtitle="本科室范围" :items="inventoryChartItems" unit=" 项" />
          </div>
        </template>
      </div>

      <aside class="workbench-side board-card">
        <ShortcutPanel :quick-entries="quickEntries" :reminders="roleReminders" @navigate="navigateTo" />
        <MaintenancePanel
          v-if="isAdmin"
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
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts" name="home">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
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
import { getInventoryWorkbenchApi, type InventoryWorkbench } from "@/api/modules/inventory";
import { canEditSection, recordSections, roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";
import { useAuthStore } from "@/stores/modules/auth";
import { classifyPatientStatus } from "@/utils/patientStatusClassifier";
import CalendarHeatmap from "./components/CalendarHeatmap.vue";
import HomeTaskPanel from "./components/HomeTaskPanel.vue";
import GreetingBanner from "./components/GreetingBanner.vue";
import MiniBarChart from "./components/MiniBarChart.vue";
import ShortcutPanel from "./components/ShortcutPanel.vue";
import MaintenancePanel from "./components/MaintenancePanel.vue";
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
const inventoryStats = ref<InventoryWorkbench>();
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
const editableSectionCount = computed(() => editableSections.value.length);
const firstEditableSection = computed(() => editableSections.value[0] ?? recordSections[0]);

// 严格按后端下发的菜单权限决定加载哪块工作面板——绝不调用本岗位无权的接口。
const menuPaths = computed(() => new Set(authStore.flatMenuListGet.map(item => item.path)));
const showPatientBoard = computed(() => menuPaths.value.has("/patients/list"));
const showPharmacyBoard = computed(() => !showPatientBoard.value && menuPaths.value.has("/tcm-pharmacy/workbench"));
const showInventoryBoard = computed(
  () => !showPatientBoard.value && !showPharmacyBoard.value && menuPaths.value.has("/inventory/overview")
);

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
        actionText: "去患者列表",
        path: "/patients/list"
      },
      {
        id: "frontdesk-basic",
        title: "基础信息待补",
        desc: "优先处理建档不完整或今日未闭环患者",
        count: registrationTodoRows.value.length,
        level: registrationTodoRows.value.length ? "warning" : "success",
        actionText: "查看看板",
        path: "/encounters/active"
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
        actionText: "进入看板",
        path: "/encounters/active"
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
        actionText: "看流程",
        path: "/encounters/active"
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
      path: "/encounters/active"
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

// 岗位统计卡：不同岗位组呈现完全不同的数据面。
const statCards = computed<StatCard[]>(() => {
  if (showPatientBoard.value) {
    return [
      {
        id: "pending",
        label: "待处理",
        count: pendingRows.value.length,
        desc: "本岗位相关在办患者",
        tone: pendingRows.value.length ? "warning" : "success",
        path: "/encounters/active"
      },
      {
        id: "review",
        label: "待档案审核",
        count: stats.value.reviewPatients,
        desc: "等待质控审核",
        tone: stats.value.reviewPatients ? "warning" : "success",
        path: "/audit/review"
      },
      {
        id: "returned",
        label: "退回整改",
        count: stats.value.returnedPatients,
        desc: "需按原因补齐后重新提交",
        tone: stats.value.returnedPatients ? "danger" : "success",
        path: "/audit/review"
      },
      {
        id: "attachment",
        label: "附件待补",
        count: attachmentTodoRows.value.length,
        desc: "缺少证据附件的档案",
        tone: attachmentTodoRows.value.length ? "warning" : "success",
        path: "/workbench/upload"
      },
      {
        id: "sections",
        label: "可写章节",
        count: editableSectionCount.value,
        desc: `${roleName.value}可编辑的档案章节`,
        tone: "info",
        path: "/encounters/active"
      }
    ];
  }
  if (showPharmacyBoard.value) {
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
  }
  if (showInventoryBoard.value) {
    const workbench = inventoryStats.value;
    return [
      {
        id: "issue",
        label: "待发放",
        count: workbench?.workflow.pendingIssue ?? 0,
        desc: "已审批等待中央仓发放",
        tone: "info",
        path: "/inventory/requests"
      },
      {
        id: "transit",
        label: "在途",
        count: workbench?.workflow.inTransit ?? 0,
        desc: "已发放等待科室签收",
        tone: "info",
        path: "/inventory/requests"
      },
      {
        id: "failed",
        label: "异常任务",
        count: workbench?.automation.failed ?? 0,
        desc: "自动扣减失败待处理",
        tone: (workbench?.automation.failed ?? 0) ? "danger" : "success",
        path: "/inventory/overview"
      },
      {
        id: "low",
        label: "低库存",
        count: workbench?.lowStockCount ?? 0,
        desc: "低于安全库存的物资",
        tone: (workbench?.lowStockCount ?? 0) ? "warning" : "success",
        path: "/inventory/overview"
      },
      {
        id: "expiry",
        label: "临期物资",
        count: workbench?.expirySoonCount ?? 0,
        desc: "请优先安排使用或隔离",
        tone: (workbench?.expirySoonCount ?? 0) ? "warning" : "success",
        path: "/inventory/overview"
      }
    ];
  }
  return [];
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
        path: "/encounters/active"
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
  if (showInventoryBoard.value && inventoryStats.value) {
    if (inventoryStats.value.automation.failed) {
      reminders.push({
        id: "inv-failed",
        title: "扣减异常任务",
        desc: "修复根因后重试原幂等任务",
        count: inventoryStats.value.automation.failed,
        level: "danger",
        path: "/inventory/overview"
      });
    }
  }
  return reminders;
});

const patientEncounterDates = (patient: PatientRow) => {
  const history = patient.encounterHistory?.length
    ? patient.encounterHistory
    : [{ visitDate: patient.visitDate, visitNo: patient.visitNo, visitType: patient.visitType, doctor: patient.doctor }];
  return [...new Set(history.map(item => item.visitDate).filter(Boolean))];
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

// 近 7 日趋势（含今天）。
const trendItems = computed(() => {
  const items: { label: string; value: number }[] = [];
  for (let offset = 6; offset >= 0; offset--) {
    const date = new Date();
    date.setDate(date.getDate() - offset);
    const dateText = toDateText(date);
    items.push({
      label: offset === 0 ? "今天" : `${date.getMonth() + 1}/${date.getDate()}`,
      value: countByDate.value.get(dateText) || 0
    });
  }
  return items;
});

const stageItems = computed(() =>
  stats.value.stageBuckets.slice(0, 6).map(bucket => ({ label: bucket.stage, value: bucket.count }))
);

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

const inventoryChartItems = computed(() => {
  const workbench = inventoryStats.value;
  if (!workbench) return [];
  return [
    { label: "待发放", value: workbench.workflow.pendingIssue ?? 0 },
    { label: "在途", value: workbench.workflow.inTransit ?? 0 },
    { label: "待签收", value: workbench.workflow.pendingReceipt ?? 0 },
    { label: "异常任务", value: workbench.automation.failed ?? 0 },
    { label: "低库存", value: workbench.lowStockCount ?? 0 },
    { label: "临期", value: workbench.expirySoonCount ?? 0 }
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
    ariaLabel: "空白日期"
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
      ariaLabel: `${date} 收录 ${count} 人`
    };
  });
  return [...blanks, ...days];
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
};

const loadPharmacyBoard = async () => {
  try {
    const { data } = await getTcmDashboardApi();
    tcmCounts.value = data.counts;
  } catch (error) {
    ElMessage.error((error as Error).message);
  }
};

const loadInventoryBoard = async () => {
  try {
    const { data } = await getInventoryWorkbenchApi();
    inventoryStats.value = data;
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
  if (showInventoryBoard.value) jobs.push(loadInventoryBoard());
  if (isAdmin.value) jobs.push(loadMaintenanceDashboard());
  await Promise.allSettled(jobs);
};

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
  if (task.path === "/") return;
  router.push({ path: task.path, query: task.query });
};

const openStatCard = (card: StatCard) => {
  router.push({ path: card.path, query: card.query });
};

const openFocusTask = () => {
  if (!focusTask.value) return;
  router.push({ path: focusTask.value.path, query: focusTask.value.query });
};

const navigateTo = (path: string) => {
  router.push(path);
};

onMounted(reloadAll);
</script>

<style scoped lang="scss">
.home-page {
  --clinic-success: #15803d;
  --clinic-warning: #b45309;
  --clinic-danger: #b91c1c;
  --clinic-info: #0f766e;
  --clinic-success-soft: #ecf8f0;
  --clinic-warning-soft: #fef7e8;
  --clinic-danger-soft: #fdeeee;

  display: grid;
  gap: 14px;
  padding: 4px 2px 16px;
}

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
    transform 160ms ease,
    box-shadow 160ms ease,
    border-color 160ms ease;
  &:hover {
    border-color: rgb(15 118 110 / 26%);
    box-shadow: 0 8px 20px rgb(15 118 110 / 10%);
    transform: translateY(-2px);
  }
  span {
    color: var(--el-text-color-secondary);
    font-size: 13px;
    font-weight: 600;
  }
  strong {
    color: var(--el-text-color-primary);
    font-size: 26px;
    font-variant-numeric: tabular-nums;
    line-height: 1.15;
  }
  small {
    overflow: hidden;
    color: var(--el-text-color-placeholder);
    font-size: 12px;
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

.workbench-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 360px);
  gap: 14px;
  align-items: start;
}
.workbench-main {
  min-width: 0;
  display: grid;
  gap: 14px;
}
.board-card {
  padding: 16px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
}
.chart-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 22px;
}
.workbench-side {
  min-width: 0;
}

@media (max-width: 1080px) {
  .workbench-grid {
    grid-template-columns: 1fr;
  }
  .chart-row {
    grid-template-columns: 1fr;
  }
}
</style>
