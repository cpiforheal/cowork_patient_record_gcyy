<template>
  <div class="table-box home-page">
    <section class="today-panel">
      <div class="role-block">
        <span>当前岗位</span>
        <strong>{{ roleName }}</strong>
        <small>{{ userStore.userInfo.department || "未设置科室" }}</small>
      </div>

      <div class="today-summary">
        <p>今天要处理什么</p>
        <h1>{{ firstTask ? firstTask.title : "暂无待处理任务" }}</h1>
        <span>{{ firstTask ? firstTask.desc : "可以从患者流程看板查看全院门诊进度。" }}</span>
      </div>

      <el-button type="primary" size="large" :icon="ArrowRight" :disabled="!firstTask" @click="openTask(firstTask)">
        进入第一项待办
      </el-button>
    </section>

    <section class="exception-strip">
      <button class="exception-card warning" @click="router.push('/encounters/active')">
        <span>待处理</span>
        <strong>{{ stats.pendingPatients }}</strong>
        <small>今日未闭环</small>
      </button>
      <button class="exception-card danger" @click="router.push('/audit/review')">
        <span>待质控</span>
        <strong>{{ stats.reviewPatients }}</strong>
        <small>需要复核</small>
      </button>
      <button class="exception-card" @click="router.push('/audit/review')">
        <span>退回整改</span>
        <strong>{{ stats.returnedPatients }}</strong>
        <small>优先补齐</small>
      </button>
      <button class="exception-card danger" @click="router.push('/documents/recycle')">
        <span>附件作废</span>
        <strong>{{ stats.voidedDocumentCount }}</strong>
        <small>需要留痕</small>
      </button>
      <button class="exception-card" @click="router.push('/encounters/active')">
        <span>可写章节</span>
        <strong>{{ editableSectionCount }}</strong>
        <small>{{ roleName }}权限</small>
      </button>
    </section>

    <section class="workbench-grid">
      <div class="workbench-main">
        <div class="task-panel">
          <div class="panel-head">
            <div>
              <h2>我的待办</h2>
              <p>按当前岗位可编辑章节筛选，点击直接进入患者病历。</p>
            </div>
            <el-button :icon="Refresh" link @click="loadTasks">刷新</el-button>
          </div>

          <el-empty v-if="!taskCards.length" description="当前岗位暂无待处理患者" />
          <div v-else class="task-list">
            <button v-for="task in taskCards" :key="task.id" class="task-card" @click="openTask(task)">
              <div>
                <strong>{{ task.patient.name }}</strong>
                <span>{{ task.patient.visitNo }}</span>
              </div>
              <div>
                <em>{{ task.title }}</em>
                <small>{{ task.desc }}</small>
              </div>
              <el-tag :type="task.patient.riskType || 'info'" effect="plain">{{ task.patient.status }}</el-tag>
            </button>
          </div>
        </div>

        <div class="calendar-heatmap-card">
          <div class="calendar-toolbar">
            <div>
              <span class="scope-eyebrow">主控区 · 月历热力</span>
              <h2>{{ calendarMonthTitle }}</h2>
              <p>本月收录 {{ calendarMonthTotal }} 人，单日峰值 {{ calendarPeakCount }} 人</p>
            </div>
            <div class="calendar-actions">
              <el-button :icon="ArrowLeft" circle aria-label="上个月" @click="shiftCalendarMonth(-1)" />
              <el-button @click="jumpToCurrentMonth">本月</el-button>
              <el-button type="primary" plain @click="selectCalendarMonth">整月</el-button>
              <el-button :icon="ArrowRight" circle aria-label="下个月" @click="shiftCalendarMonth(1)" />
            </div>
          </div>
          <div class="calendar-weekdays">
            <span v-for="weekday in weekdayLabels" :key="weekday">{{ weekday }}</span>
          </div>
          <div class="calendar-grid">
            <button
              v-for="day in calendarCells"
              :key="day.key"
              type="button"
              class="calendar-day"
              :class="[
                `is-level-${day.level}`,
                {
                  'is-empty': day.isBlank,
                  'is-today': day.isToday,
                  'is-selected': day.isSelected
                }
              ]"
              :disabled="day.isBlank"
              :aria-label="day.ariaLabel"
              @click="selectCalendarDate(day)"
            >
              <span class="day-number">{{ day.day || "" }}</span>
              <span v-if="!day.isBlank" class="day-count">{{ day.count ? `${day.count} 人` : "空" }}</span>
            </button>
          </div>
          <div class="heatmap-legend">
            <span>空档</span>
            <i v-for="level in [0, 1, 2, 3, 4]" :key="level" :class="`is-level-${level}`" />
            <span>高峰</span>
          </div>
        </div>
      </div>

      <div class="shortcut-panel">
        <div class="panel-head">
          <div>
            <h2>常用入口</h2>
            <p>保留一线高频动作，系统配置入口靠后。</p>
          </div>
        </div>
        <div class="shortcut-list">
          <button v-for="item in quickEntries" :key="item.path" @click="router.push(item.path)">
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.title }}</span>
            <small>{{ item.desc }}</small>
          </button>
        </div>

        <div class="production-panel">
          <div class="panel-head compact">
            <div>
              <h2>生产提醒</h2>
              <p>面向内测运行的风险提示与数据维护入口。</p>
            </div>
            <el-button :icon="Refresh" link :loading="maintenanceLoading" @click="loadTasks">巡检</el-button>
          </div>

          <div class="reminder-list">
            <button
              v-for="item in workReminders"
              :key="item.id"
              class="reminder-item"
              :class="`is-${item.level}`"
              @click="router.push(item.path)"
            >
              <span>{{ item.title }}</span>
              <strong>{{ item.count }}</strong>
              <small>{{ item.desc }}</small>
            </button>
          </div>

          <div class="maintenance-card">
            <div>
              <span>附件存储</span>
              <strong>{{ storageSummary }}</strong>
              <small>{{ maintenanceStatus?.storage.attachmentDir || "等待后端巡检" }}</small>
            </div>
            <div>
              <span>数据快照</span>
              <strong>{{ snapshotSummary }}</strong>
              <small>{{ maintenanceStatus?.latestSnapshotAt || "尚未生成快照" }}</small>
            </div>
            <el-button type="primary" plain :loading="maintenanceLoading" @click="createSnapshot">生成快照</el-button>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts" name="home">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { ArrowLeft, ArrowRight, Refresh } from "@element-plus/icons-vue";
import {
  createMaintenanceSnapshotApi,
  getMaintenanceStatusApi,
  getOperationStatsApi,
  getPatientListApi,
  getWorkRemindersApi,
  type MaintenanceStatus,
  type OperationStats,
  type PatientRow,
  type WorkReminder
} from "@/api/modules/clinic";
import { canEditSection, recordSections, roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";

interface HomeTask {
  id: string;
  title: string;
  desc: string;
  sectionKey: string;
  patient: PatientRow;
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
const patientRows = ref<PatientRow[]>([]);
const workReminders = ref<WorkReminder[]>([]);
const maintenanceStatus = ref<MaintenanceStatus>();
const maintenanceLoading = ref(false);
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
const roleName = computed(() => roleLabel(currentRole.value));
const editableSections = computed(() => recordSections.filter(section => canEditSection(currentRole.value, section)));
const editableSectionCount = computed(() => editableSections.value.length);
const firstEditableSection = computed(() => editableSections.value[0] ?? recordSections[0]);

const allQuickEntries = [
  { title: "患者流程看板", desc: "查看今日患者卡片", icon: "Connection", path: "/encounters/active" },
  { title: "今日患者", desc: "按姓名和门诊号查询", icon: "UserFilled", path: "/patients/list" },
  { title: "上传资料", desc: "门诊号识别后一键上传", icon: "UploadFilled", path: "/workbench/upload" },
  { title: "旧病历导入", desc: "迁移共享文件夹资料", icon: "FolderOpened", path: "/workbench/legacy" },
  { title: "字段权限", desc: "查看字段归属", icon: "DocumentCopy", path: "/templates/record" },
  { title: "质控审核", desc: "退回整改或通过归档", icon: "Tickets", path: "/audit/review" },
  { title: "操作日志", desc: "追踪资料改动", icon: "DocumentChecked", path: "/audit/log" }
];

const roleEntries: Record<string, string[]> = {
  admin: [
    "/encounters/active",
    "/patients/list",
    "/workbench/upload",
    "/workbench/legacy",
    "/templates/record",
    "/audit/review",
    "/audit/log"
  ],
  frontdesk: ["/encounters/active", "/patients/list", "/workbench/upload", "/workbench/legacy", "/templates/record"],
  lab: ["/encounters/active", "/patients/list", "/workbench/upload", "/workbench/legacy", "/templates/record"],
  ecg: ["/encounters/active", "/patients/list", "/workbench/upload", "/workbench/legacy", "/templates/record"],
  ultrasound: ["/encounters/active", "/patients/list", "/workbench/upload", "/workbench/legacy", "/templates/record"],
  nurse: ["/encounters/active", "/patients/list", "/workbench/upload", "/workbench/legacy", "/templates/record"],
  doctor: ["/encounters/active", "/patients/list", "/templates/record"],
  quality: ["/encounters/active", "/patients/list", "/workbench/legacy", "/templates/record", "/audit/review", "/audit/log"]
};

const quickEntries = computed(() => {
  const allowPaths = roleEntries[currentRole.value] ?? roleEntries.frontdesk;
  return allQuickEntries.filter(item => allowPaths.includes(item.path));
});

const formatBytes = (bytes?: number) => {
  if (!bytes) return "0 MB";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value.toFixed(value >= 10 || unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
};

const storageSummary = computed(() => {
  const storage = maintenanceStatus.value?.storage;
  if (!storage) return "等待巡检";
  return `${storage.fileCount} 个文件 / ${formatBytes(storage.totalBytes)}，缺失 ${storage.missingFileCount}`;
});

const snapshotSummary = computed(() => {
  if (!maintenanceStatus.value) return "等待巡检";
  return `${maintenanceStatus.value.snapshotCount} 个快照`;
});

const pendingRows = computed(() => patientRows.value.filter(item => item.status !== "旧资料已归档" && item.status !== "待归档"));

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

const firstTask = computed(() => taskCards.value[0]);

const loadTasks = async () => {
  try {
    const [{ data: patients }, { data: operationStats }] = await Promise.all([
      getPatientListApi({ pageNum: 1, pageSize: 5000 }),
      getOperationStatsApi()
    ]);
    patientRows.value = patients.list;
    stats.value = operationStats;
  } catch (error) {
    ElMessage.error((error as Error).message);
  }

  maintenanceLoading.value = true;
  try {
    const [{ data: reminders }, { data: status }] = await Promise.all([getWorkRemindersApi(), getMaintenanceStatusApi()]);
    workReminders.value = reminders;
    maintenanceStatus.value = status;
  } catch (error) {
    ElMessage.warning(`生产巡检暂不可用：${(error as Error).message}`);
  } finally {
    maintenanceLoading.value = false;
  }
};

const createSnapshot = async () => {
  maintenanceLoading.value = true;
  try {
    const { data } = await createMaintenanceSnapshotApi();
    ElMessage.success(`快照已生成，当前共 ${data.snapshotCount} 个`);
    await loadTasks();
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

onMounted(loadTasks);
</script>

<style scoped lang="scss">
.home-page {
  --clinic-success: #16a34a;
  --clinic-success-soft: #f0fdf4;
  --clinic-warning: #d97706;
  --clinic-warning-soft: #fffbeb;
  --clinic-danger: #dc2626;
  --clinic-danger-soft: #fef2f2;
  --clinic-info: #0f766e;
  --clinic-info-soft: #ecfdf5;
  display: flex;
  flex-direction: column;
  gap: 12px;
  color: #1f2937;
}

.today-panel,
.calendar-heatmap-card,
.task-panel,
.shortcut-panel,
.exception-card {
  background: #ffffff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: 0 1px 2px rgb(15 23 42 / 4%);
}

.today-panel {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr) auto;
  gap: 18px;
  align-items: center;
  padding: 18px;
  overflow: hidden;
  background: linear-gradient(90deg, rgb(236 253 245 / 86%), rgb(255 255 255 / 98%) 48%), #ffffff;
  border-color: rgb(20 184 166 / 18%);
  box-shadow: 0 10px 24px rgb(15 118 110 / 8%);
}

.role-block {
  display: grid;
  gap: 4px;
  padding-right: 18px;
  border-right: 1px solid var(--el-border-color-lighter);

  span,
  small {
    color: var(--el-text-color-secondary);
  }

  strong {
    color: var(--clinic-info);
    font-size: 26px;
    line-height: 1.15;
  }
}

.today-summary {
  min-width: 0;

  p,
  h1,
  span {
    margin: 0;
  }

  p {
    color: var(--clinic-info);
    font-size: 13px;
    font-weight: 700;
  }

  h1 {
    margin-top: 4px;
    color: var(--el-text-color-primary);
    font-size: 24px;
    line-height: 1.35;
  }

  span {
    display: block;
    margin-top: 4px;
    color: var(--el-text-color-regular);
    line-height: 1.45;
  }
}

.exception-strip {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}

.exception-card {
  position: relative;
  display: grid;
  min-width: 0;
  gap: 2px;
  padding: 13px 14px;
  overflow: hidden;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease,
    transform 160ms ease;

  &::before {
    position: absolute;
    inset: 0 auto 0 0;
    width: 4px;
    content: "";
    background: var(--clinic-info);
    opacity: 0.72;
  }

  &:hover {
    border-color: rgb(15 118 110 / 28%);
    box-shadow: 0 10px 22px rgb(15 23 42 / 8%);
    transform: translateY(-1px);
  }

  span,
  strong,
  small {
    display: block;
  }

  span {
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }

  strong {
    margin-top: 2px;
    color: var(--el-text-color-primary);
    font-size: 22px;
  }

  small {
    overflow: hidden;
    color: var(--el-text-color-secondary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &.warning {
    border-color: rgb(245 158 11 / 28%);
    background: var(--clinic-warning-soft);

    &::before {
      background: var(--clinic-warning);
    }

    strong {
      color: var(--clinic-warning);
    }
  }

  &.danger {
    border-color: rgb(239 68 68 / 22%);
    background: var(--clinic-danger-soft);

    &::before {
      background: var(--clinic-danger);
    }

    strong {
      color: var(--clinic-danger);
    }
  }
}

.workbench-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.42fr) minmax(320px, 0.58fr);
  gap: 12px;
}

.workbench-main {
  display: grid;
  min-width: 0;
  gap: 12px;
}

.task-panel,
.shortcut-panel,
.calendar-heatmap-card {
  padding: 16px;
}

.calendar-heatmap-card {
  background: linear-gradient(135deg, rgb(236 253 245 / 58%), rgb(255 255 255 / 92%)), #ffffff;
  border-color: rgb(20 184 166 / 18%);
}

.scope-eyebrow {
  color: #008f84;
  font-size: 12px;
  font-weight: 700;
}

.calendar-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;

  h2,
  p {
    margin: 0;
  }

  h2 {
    margin-top: 4px;
    color: var(--el-text-color-primary);
    font-size: 18px;
    line-height: 1.35;
  }

  p {
    margin-top: 4px;
    color: var(--el-text-color-secondary);
  }
}

.calendar-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  gap: 8px;
}

.calendar-weekdays,
.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 6px;
}

.calendar-weekdays {
  margin: 14px 0 7px;

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
    font-weight: 700;
    text-align: center;
  }
}

.calendar-day {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 0;
  min-height: 52px;
  padding: 7px;
  color: var(--el-text-color-primary);
  text-align: left;
  cursor: pointer;
  background: #f8fbfa;
  border: 1px solid #dfeee9;
  border-radius: 8px;
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;

  &:not(.is-empty):hover {
    border-color: #0f9f8f;
    box-shadow: 0 7px 16px rgb(15 118 110 / 12%);
    transform: translateY(-1px);
  }

  &.is-empty {
    visibility: hidden;
    pointer-events: none;
  }

  &.is-level-1 {
    background: #e7f7f1;
    border-color: #ccecdf;
  }

  &.is-level-2 {
    background: #caefdf;
    border-color: #a4dfc8;
  }

  &.is-level-3 {
    color: #07594f;
    background: #8edcc3;
    border-color: #62c6a8;
  }

  &.is-level-4 {
    color: #ffffff;
    background: #0f9f8f;
    border-color: #0d857a;

    .day-count {
      color: rgb(255 255 255 / 86%);
    }
  }

  &.is-selected {
    border-color: #07594f;
    box-shadow: 0 0 0 2px rgb(15 118 110 / 20%);
  }

  &.is-today .day-number::after {
    margin-left: 4px;
    color: #b45309;
    font-size: 11px;
    font-weight: 700;
    content: "今";
  }
}

.day-number {
  font-size: 14px;
  font-weight: 700;
}

.day-count {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.heatmap-legend {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;

  i {
    width: 18px;
    height: 10px;
    border: 1px solid #dfeee9;
    border-radius: 3px;
  }

  .is-level-0 {
    background: #f8fbfa;
  }

  .is-level-1 {
    background: #e7f7f1;
  }

  .is-level-2 {
    background: #caefdf;
  }

  .is-level-3 {
    background: #8edcc3;
  }

  .is-level-4 {
    background: #0f9f8f;
    border-color: #0d857a;
  }
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;

  h2,
  p {
    margin: 0;
  }

  h2 {
    font-size: 18px;
    line-height: 1.35;
  }

  p {
    margin-top: 4px;
    color: var(--el-text-color-secondary);
  }
}

.task-list {
  display: grid;
  gap: 8px;
}

.task-card {
  display: grid;
  grid-template-columns: 160px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding: 12px;
  text-align: left;
  cursor: pointer;
  background: #ffffff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease,
    transform 160ms ease;

  &:hover {
    border-color: rgb(15 118 110 / 24%);
    box-shadow: 0 8px 18px rgb(15 23 42 / 7%);
    transform: translateY(-1px);
  }

  strong,
  span,
  em,
  small {
    display: block;
  }

  strong {
    color: var(--el-text-color-primary);
    font-size: 16px;
  }

  span,
  small {
    color: var(--el-text-color-secondary);
  }

  em {
    color: var(--el-text-color-primary);
    font-style: normal;
    font-weight: 600;
    line-height: 1.45;
  }
}

.shortcut-list {
  display: grid;
  gap: 8px;

  button {
    display: grid;
    grid-template-columns: 28px minmax(0, 1fr);
    gap: 2px 10px;
    align-items: center;
    padding: 11px 12px;
    text-align: left;
    cursor: pointer;
    background: #ffffff;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;
    transition:
      background 160ms ease,
      border-color 160ms ease,
      transform 160ms ease;

    &:hover {
      background: #f8fffd;
      border-color: rgb(15 118 110 / 22%);
      transform: translateX(2px);
    }

    .el-icon {
      grid-row: span 2;
      color: var(--clinic-info);
      font-size: 22px;
    }

    span {
      color: var(--el-text-color-primary);
      font-weight: 600;
    }

    small {
      color: var(--el-text-color-secondary);
    }
  }
}

.production-panel {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.panel-head.compact {
  align-items: flex-start;
  margin-bottom: 10px;

  h2 {
    font-size: 16px;
  }

  p {
    font-size: 12px;
  }
}

.reminder-list {
  display: grid;
  gap: 8px;
}

.reminder-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 3px 10px;
  padding: 10px 11px;
  text-align: left;
  cursor: pointer;
  background: #f8fafc;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;

  span,
  strong,
  small {
    display: block;
  }

  span {
    color: var(--el-text-color-primary);
    font-weight: 700;
  }

  strong {
    color: var(--el-color-primary);
    font-size: 20px;
  }

  small {
    grid-column: 1 / -1;
    color: var(--el-text-color-secondary);
    line-height: 1.45;
  }

  &.is-warning {
    background: var(--clinic-warning-soft);
    border-color: rgb(245 158 11 / 25%);

    strong {
      color: var(--clinic-warning);
    }
  }

  &.is-danger {
    background: var(--clinic-danger-soft);
    border-color: rgb(239 68 68 / 22%);

    strong {
      color: var(--clinic-danger);
    }
  }

  &.is-success strong {
    color: var(--clinic-success);
  }
}

.maintenance-card {
  display: grid;
  gap: 10px;
  margin-top: 12px;
  padding: 12px;
  background: #f7fbfa;
  border: 1px solid rgb(20 184 166 / 16%);
  border-radius: 6px;

  span,
  strong,
  small {
    display: block;
  }

  span,
  small {
    color: var(--el-text-color-secondary);
  }

  strong {
    margin-top: 2px;
    color: var(--el-text-color-primary);
  }

  small {
    overflow: hidden;
    margin-top: 2px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

@media (max-width: 1080px) {
  .today-panel,
  .workbench-grid {
    grid-template-columns: 1fr;
  }

  .role-block {
    padding-right: 0;
    padding-bottom: 12px;
    border-right: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }
}

@media (max-width: 760px) {
  .today-panel {
    padding: 14px;
  }

  .exception-strip,
  .task-card {
    grid-template-columns: 1fr;
  }

  .calendar-toolbar {
    flex-direction: column;
  }

  .calendar-actions {
    flex-wrap: wrap;
  }

  .calendar-day {
    min-height: 46px;
    padding: 6px;
  }
}
</style>
