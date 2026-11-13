<template>
  <div class="eye-drop-page">
    <header class="ed-head">
      <div>
        <p class="eyebrow">PERSONAL · EYE DROPS</p>
        <h1>眼药水管理</h1>
        <p class="ed-sub">按固定时段记录滴注，注意同点多药的间隔与漏滴。</p>
      </div>
      <div class="ed-actions">
        <el-date-picker
          v-model="selectedDate"
          type="date"
          value-format="YYYY-MM-DD"
          :clearable="false"
          size="default"
          @change="loadDay"
        />
        <el-button :icon="Refresh" :loading="loading" @click="refresh">刷新</el-button>
        <el-button :icon="EditPen" @click="measurementVisible = true">记录眼压/症状</el-button>
        <el-button :icon="Download" @click="exportCsv">导出 CSV</el-button>
        <el-button :icon="Printer" @click="printPage">打印</el-button>
      </div>
    </header>

    <el-alert v-if="dueItems.length" class="ed-reminder" type="warning" :closable="false" show-icon>
      <template #title>
        现在该滴：{{ dueItems.map(item => `${item.drugName}（${item.slotLabel}）`).join("、") }}
      </template>
    </el-alert>

    <section class="ed-metrics">
      <article><span>今日应滴</span><strong>{{ today.summary.due }}</strong></article>
      <article><span>已滴</span><strong>{{ today.summary.taken }}</strong></article>
      <article><span>迟滴</span><strong>{{ today.summary.late }}</strong></article>
      <article><span>未滴</span><strong>{{ today.summary.missed }}</strong></article>
      <article>
        <span>依从率</span>
        <strong>{{ today.summary.adherence == null ? "—" : `${today.summary.adherence}%` }}</strong>
      </article>
    </section>

    <section v-loading="loading" class="ed-timeline">
      <div class="ed-section-head">
        <strong>{{ isTodayView ? "今日滴注" : `${selectedDate} 滴注记录` }}</strong>
        <span v-if="hasSameTimeGroup" class="ed-hint">同一时段两支药：先溶液后混悬，间隔 5 分钟</span>
        <span v-else-if="!isTodayView" class="ed-hint">历史日期仅供查看，打卡请在当天完成</span>
      </div>

      <div v-for="group in groups" :key="group.time" class="ed-group">
        <div class="ed-time">{{ group.time }}</div>
        <div class="ed-cards">
          <article
            v-for="item in group.items"
            :key="item.planId"
            class="ed-card"
            :class="`is-${item.state.toLowerCase()}`"
          >
            <div class="ed-card-main">
              <strong>{{ item.drugName }}</strong>
              <small>{{ item.slotLabel }} · 计划 {{ item.plannedTime }}</small>
              <small v-if="item.takenAt" class="ed-taken">
                {{ eyeLabel(item.eyeSide) }} {{ shortTime(item.takenAt) }}
                <template v-if="item.minutesLate"> · 迟 {{ item.minutesLate }} 分钟</template>
              </small>
              <small v-if="item.note" class="ed-note">{{ item.note }}</small>
            </div>
            <div class="ed-card-side">
              <el-tag size="small" :type="stateTag(item.state)">{{ stateLabel(item.state) }}</el-tag>
              <div class="ed-card-actions">
                <template v-if="item.state === 'TAKEN' || item.state === 'TAKEN_LATE'">
                  <el-button size="small" link type="danger" :disabled="!item.canVoid" @click="voidDose(item)">
                    撤销
                  </el-button>
                </template>
                <template v-else-if="isTodayView">
                  <el-button
                    size="small"
                    type="primary"
                    :loading="busyPlan === item.planId"
                    @click="quickTake(item)"
                    >打卡</el-button
                  >
                  <el-button size="small" link @click="openDose(item)">眼别</el-button>
                </template>
              </div>
            </div>
          </article>
        </div>
      </div>
    </section>

    <section class="ed-measurements">
      <header class="ed-section-head">
        <strong>{{ isTodayView ? "今日眼压与症状" : "当日眼压与症状" }}</strong>
        <el-button link type="primary" @click="measurementVisible = true">新增</el-button>
      </header>
      <el-table v-if="today.measurements.length" :data="today.measurements" size="small">
        <el-table-column prop="measuredAt" label="时间" width="140" />
        <el-table-column label="眼压" min-width="130">
          <template #default="{ row }">
            左 {{ row.iopLeft ?? "—" }} / 右 {{ row.iopRight ?? "—" }}
          </template>
        </el-table-column>
        <el-table-column label="症状" min-width="150">
          <template #default="{ row }">{{ row.symptoms.join("、") || "—" }}</template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="70">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeMeasurement(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-else class="ed-empty">还没有眼压或症状记录</p>
    </section>

    <section class="ed-month">
      <CalendarHeatmap
        :month-title="monthTitle"
        :month-total="monthTaken"
        :peak-count="monthPeak"
        :weekday-labels="weekdays"
        :cells="monthCells"
        unit="次"
        @shift-month="shiftMonth"
        @current-month="currentMonth"
        @select-month="currentMonth"
        @select-date="selectDate"
      />
    </section>

    <DoseDialog v-model="doseVisible" :item="doseItem" @saved="refresh" />
    <MeasurementDialog v-model="measurementVisible" @saved="refresh" />
  </div>
</template>

<script setup lang="ts" name="EyeDropsManage">
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Download, EditPen, Printer, Refresh } from "@element-plus/icons-vue";
import CalendarHeatmap from "@/views/home/components/CalendarHeatmap.vue";
import DoseDialog from "./components/DoseDialog.vue";
import MeasurementDialog from "./components/MeasurementDialog.vue";
import { useEyeDropReminder } from "./useEyeDropReminder";
import {
  createEyeDropDoseApi,
  deleteEyeDropMeasurementApi,
  downloadEyeDropCsv,
  getEyeDropMonthApi,
  getEyeDropTodayApi,
  voidEyeDropDoseApi,
  type EyeDropItem,
  type EyeDropMonth,
  type EyeDropState,
  type EyeDropToday
} from "@/api/modules/clinic/eyeDrop";

const pad = (value: number) => String(value).padStart(2, "0");
const todayStr = (() => {
  const now = new Date();
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
})();

const selectedDate = ref(todayStr);
const monthCursor = ref(todayStr.slice(0, 7));
const loading = ref(false);
const busyPlan = ref("");
const doseVisible = ref(false);
const doseItem = ref<EyeDropItem>();
const measurementVisible = ref(false);

const emptyToday = (): EyeDropToday => ({
  date: selectedDate.value,
  now: "",
  items: [],
  measurements: [],
  summary: { total: 0, due: 0, taken: 0, late: 0, missed: 0, upcoming: 0, adherence: null }
});
const emptyMonth = (): EyeDropMonth => ({
  month: monthCursor.value,
  monthLabel: "",
  days: [],
  peak: 0,
  summary: { due: 0, taken: 0, late: 0, missed: 0, adherence: null }
});

const today = ref<EyeDropToday>(emptyToday());
const month = ref<EyeDropMonth>(emptyMonth());
const { dueItems } = useEyeDropReminder(computed(() => today.value.items));

const isTodayView = computed(() => selectedDate.value === todayStr);
const groups = computed(() => {
  const map = new Map<string, EyeDropItem[]>();
  today.value.items.forEach(item => {
    if (!map.has(item.plannedTime)) map.set(item.plannedTime, []);
    map.get(item.plannedTime)!.push(item);
  });
  return [...map.entries()]
    .sort((left, right) => left[0].localeCompare(right[0]))
    .map(([time, items]) => ({ time, items }));
});
const hasSameTimeGroup = computed(() => groups.value.some(group => group.items.length > 1));

const weekdays = ["一", "二", "三", "四", "五", "六", "日"];
const monthTitle = computed(() => month.value.monthLabel || monthCursor.value);
const monthTaken = computed(() => month.value.days.reduce((sum, day) => sum + day.taken, 0));
const monthPeak = computed(() => month.value.peak);
const monthCells = computed(() => {
  const [year, monthNo] = monthCursor.value.split("-").map(Number);
  const offset = (new Date(year, monthNo - 1, 1).getDay() + 6) % 7;
  const blanks = Array.from({ length: offset }, (_, index) => ({
    key: `blank-${index}`,
    date: "",
    day: 0,
    count: 0,
    level: 0,
    isBlank: true,
    isToday: false,
    isSelected: false,
    ariaLabel: "",
    hoverColor: ""
  }));
  const cells = month.value.days.map(day => ({
    key: day.date,
    date: day.date,
    day: day.day,
    count: day.taken,
    level: month.value.peak ? Math.min(4, Math.ceil((day.taken / month.value.peak) * 4)) : 0,
    isBlank: false,
    isToday: day.date === todayStr,
    isSelected: day.date === selectedDate.value,
    ariaLabel: `${day.date}：已滴 ${day.taken} 次，漏滴 ${day.missed} 次`,
    hoverColor: ""
  }));
  return [...blanks, ...cells];
});

const stateLabel = (state: EyeDropState) =>
  ({
    UPCOMING: "未到",
    DUE: "待滴",
    LATE: "已过点",
    MISSED: "未滴",
    TAKEN: "已滴",
    TAKEN_LATE: "迟滴"
  })[state];
const stateTag = (state: EyeDropState): "success" | "warning" | "danger" | "info" =>
  state === "TAKEN" ? "success" : state === "TAKEN_LATE" || state === "LATE" ? "warning" : state === "MISSED" ? "danger" : "info";
const eyeLabel = (side?: string) => (side === "LEFT" ? "左眼" : side === "RIGHT" ? "右眼" : "双眼");
const shortTime = (value?: string) => (value ? value.slice(11, 16) : "—");

const loadDay = async () => {
  loading.value = true;
  try {
    today.value = await getEyeDropTodayApi(selectedDate.value);
  } catch (error: any) {
    ElMessage.error(error?.message || "滴注记录加载失败");
  } finally {
    loading.value = false;
  }
};
const loadMonth = async () => {
  try {
    month.value = await getEyeDropMonthApi(monthCursor.value);
  } catch (error: any) {
    ElMessage.error(error?.message || "月度统计加载失败");
  }
};
const refresh = async () => {
  await Promise.all([loadDay(), loadMonth()]);
};

const quickTake = async (item: EyeDropItem) => {
  busyPlan.value = item.planId;
  try {
    await createEyeDropDoseApi({
      planId: item.planId,
      eyeSide: "BOTH",
      requestId: `${item.planId}-${selectedDate.value}-${Date.now()}`
    });
    ElMessage.success("已记录");
    await refresh();
  } catch (error: any) {
    ElMessage.error(error?.message || "打卡失败");
  } finally {
    busyPlan.value = "";
  }
};

const openDose = (item: EyeDropItem) => {
  doseItem.value = item;
  doseVisible.value = true;
};

const voidDose = async (item: EyeDropItem) => {
  if (!item.logId) return;
  try {
    await ElMessageBox.confirm("撤销后该时段会回到待完成，确认撤销？", "撤销打卡", {
      type: "warning",
      confirmButtonText: "撤销",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  try {
    await voidEyeDropDoseApi(item.logId);
    ElMessage.success("已撤销");
    await refresh();
  } catch (error: any) {
    ElMessage.error(error?.message || "撤销失败");
  }
};

const removeMeasurement = async (row: any) => {
  try {
    await ElMessageBox.confirm("删除这条眼压/症状记录？", "删除记录", {
      type: "warning",
      confirmButtonText: "删除",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  try {
    await deleteEyeDropMeasurementApi(row.id);
    ElMessage.success("已删除");
    await loadDay();
  } catch (error: any) {
    ElMessage.error(error?.message || "删除失败");
  }
};

const shiftMonth = (offset: number) => {
  const [year, monthNo] = monthCursor.value.split("-").map(Number);
  const target = new Date(year, monthNo - 1 + offset, 1);
  monthCursor.value = `${target.getFullYear()}-${pad(target.getMonth() + 1)}`;
  void loadMonth();
};
const currentMonth = () => {
  monthCursor.value = todayStr.slice(0, 7);
  void loadMonth();
};
const selectDate = (day: any) => {
  if (!day?.date) return;
  selectedDate.value = day.date;
  void loadDay();
};

const exportCsv = async () => {
  try {
    await downloadEyeDropCsv(monthCursor.value);
  } catch (error: any) {
    ElMessage.error(error?.message || "导出失败");
  }
};
const printPage = () => window.print();

onMounted(refresh);
</script>

<style scoped lang="scss">
.eye-drop-page {
  padding: 18px 22px 28px;
  display: grid;
  gap: 16px;
}
.ed-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.eyebrow {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  letter-spacing: 0.12em;
}
.ed-head h1 {
  margin: 4px 0 2px;
  font-size: 24px;
}
.ed-sub {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.ed-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.ed-reminder {
  border-radius: 10px;
}
.ed-metrics {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}
.ed-metrics article {
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: var(--el-fill-color-blank);
  span {
    display: block;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
  strong {
    display: block;
    margin-top: 4px;
    font-size: 24px;
  }
}
.ed-section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  strong {
    font-size: 16px;
  }
}
.ed-hint {
  color: var(--el-color-warning-dark-2);
  font-size: 12px;
}
.ed-group {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 12px;
  padding: 10px 0;
  border-top: 1px dashed var(--el-border-color-lighter);
}
.ed-time {
  padding-top: 12px;
  color: var(--el-color-primary);
  font-weight: 600;
}
.ed-cards {
  display: grid;
  gap: 8px;
}
.ed-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-bg-color);
  &.is-due,
  &.is-late {
    border-color: var(--el-color-warning-light-5);
    background: color-mix(in srgb, var(--el-color-warning) 4%, var(--el-bg-color));
  }
  &.is-missed {
    border-color: var(--el-color-danger-light-5);
  }
  &.is-taken,
  &.is-taken_late {
    background: var(--el-fill-color-lighter);
  }
}
.ed-card-main {
  display: grid;
  gap: 2px;
  min-width: 0;
  strong {
    font-size: 14px;
  }
  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}
.ed-taken {
  color: var(--el-color-success) !important;
}
.ed-note {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ed-card-side {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.ed-card-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
.ed-measurements {
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
}
.ed-empty {
  padding: 12px 0;
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}
@media (max-width: 900px) {
  .ed-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .ed-group {
    grid-template-columns: 48px minmax(0, 1fr);
  }
}
@media print {
  :global(.desktop-aside),
  :global(.layout-header),
  .ed-actions,
  .ed-reminder,
  .ed-card-actions {
    display: none !important;
  }
  .eye-drop-page {
    padding: 0;
  }
}
</style>
