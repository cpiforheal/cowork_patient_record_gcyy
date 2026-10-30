<template>
  <aside class="fill-calendar" :class="{ 'is-wide': wide }" aria-label="填写日历">
    <header class="calendar-head">
      <div class="calendar-title">
        <span class="eyebrow">填写日历</span>
        <strong>{{ monthTitle }}</strong>
      </div>
      <div class="calendar-nav">
        <el-button link aria-label="上个月" @click="shiftMonth(-1)">‹</el-button>
        <el-button link class="today-button" @click="select(today)">今天</el-button>
        <el-button link aria-label="下个月" @click="shiftMonth(1)">›</el-button>
      </div>
    </header>

    <div class="calendar-summary">
      <span><b>{{ filledDays }}</b> 天已填完</span>
      <span v-if="mode === 'PATIENT'"><b>{{ monthExpected }}</b> 人应填</span>
      <span v-else><b>{{ monthLines }}</b> 项已填</span>
      <span v-if="mode === 'PATIENT'"><b>{{ monthPeople }}</b> 人已填</span>
      <span v-if="missingDays" class="is-missing"><b>{{ missingDays }}</b> 天未填</span>
      <span v-if="suggestDays" class="is-suggest"><b>{{ suggestDays }}</b> 天待补</span>
    </div>

    <div v-loading="loading" class="calendar-grid" role="grid">
      <span v-for="weekday in weekdays" :key="weekday" class="weekday" role="columnheader">{{ weekday }}</span>
      <el-popover
        v-for="date in grid"
        :key="date"
        placement="left"
        :width="236"
        trigger="hover"
        :show-after="160"
        :disabled="isFuture(date)"
      >
        <template #reference>
          <button
            type="button"
            class="calendar-cell"
            :class="cellClass(date)"
            :disabled="isFuture(date)"
            :aria-label="ariaLabel(date)"
            :aria-pressed="date === selectedDate"
            @click="select(date)"
          >
            <span class="cell-number">{{ Number(date.slice(8)) }}</span>
            <span v-if="badge(date)" class="cell-badge">{{ badge(date) }}</span>
          </button>
        </template>
        <div class="cell-popover">
          <strong>{{ dateTitle(date) }}</strong>
          <span class="pop-status" :class="`is-${stateOf(date)}`">{{ stateLabel(date) }}</span>
          <dl>
            <template v-if="mode === 'PATIENT'">
              <div><dt>已填写</dt><dd>{{ dayOf(date)?.patientCount || 0 }} 人</dd></div>
              <div><dt>应填写</dt><dd>{{ dayOf(date)?.patientTotal || 0 }} 人</dd></div>
              <div v-if="Number(dayOf(date)?.missing || 0)"><dt>缺录</dt><dd>{{ dayOf(date)?.missing }} 人</dd></div>
              <div v-if="Number(dayOf(date)?.draft || 0)"><dt>草稿</dt><dd>{{ dayOf(date)?.draft }} 人</dd></div>
              <div v-if="Number(dayOf(date)?.returned || 0)"><dt>被驳回</dt><dd>{{ dayOf(date)?.returned }} 人</dd></div>
            </template>
            <template v-else-if="dayOf(date)">
              <div><dt>耗材项</dt><dd>{{ dayOf(date)?.lineCount || 0 }} 项</dd></div>
              <div v-if="dayOf(date)?.submittedAt"><dt>提交时间</dt><dd>{{ formatDateTime(dayOf(date)?.submittedAt) }}</dd></div>
            </template>
            <div v-else><dt>状态</dt><dd>{{ date < today ? "这天还没有填写记录" : "等待今天填写" }}</dd></div>
          </dl>
          <p v-if="!isFuture(date)">点击日期可切换到这天查看或补录</p>
        </div>
      </el-popover>
    </div>

    <div class="calendar-legend">
      <span><i class="is-filled" />已填</span>
      <span><i class="is-due" />应填</span>
      <span><i class="is-missing" />未填</span>
      <span><i class="is-suggest" />建议补充</span>
    </div>

    <div class="calendar-selected">
      <span>
        <strong>{{ selectedTitle }}</strong>
        <small>{{ stateLabel(selectedDate) }}</small>
      </span>
      <el-button size="small" text type="primary" @click="select(selectedDate)">打开这天</el-button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed } from "vue";
import dayjs from "dayjs";
import type { InventoryDepartmentDailyReportCalendarDay } from "@/api/modules/inventory";

const props = defineProps<{
  month: string;
  today: string;
  selectedDate: string;
  mode: "PATIENT" | "DAILY";
  days: InventoryDepartmentDailyReportCalendarDay[];
  expectedFrom?: string;
  loading?: boolean;
  wide?: boolean;
}>();

const emit = defineEmits<{
  "update:month": [month: string];
  "select-date": [date: string];
}>();

const weekdays = ["一", "二", "三", "四", "五", "六", "日"];
const weekdayNames = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

type CalendarState = "filled" | "due" | "missing" | "suggest" | "none" | "future" | "outside";

const monthTitle = computed(() => dayjs(props.month).format("YYYY 年 M 月"));
const grid = computed(() => {
  const first = dayjs(props.month).startOf("month");
  const start = first.subtract((first.day() + 6) % 7, "day");
  return Array.from({ length: 42 }, (_, index) => start.add(index, "day").format("YYYY-MM-DD"));
});

const byDate = computed(() => new Map(props.days.map(day => [day.date, day])));
const dayOf = (date: string) => byDate.value.get(date);

const currentMonthDays = computed(() =>
  props.days.filter(
    day => day.date.slice(0, 7) === props.month.slice(0, 7) && (!props.expectedFrom || day.date >= props.expectedFrom)
  )
);
const filledDays = computed(() => {
  if (props.mode === "PATIENT") {
    return currentMonthDays.value.filter(
      day => Number(day.patientTotal || 0) > 0 && Number(day.missing || 0) === 0
        && Number(day.draft || 0) === 0 && Number(day.returned || 0) === 0
    ).length;
  }
  return currentMonthDays.value.filter(day => ["SUBMITTED", "PARTIALLY_APPROVED", "APPROVED"].includes(day.status)).length;
});
const suggestDays = computed(() => {
  if (props.mode === "PATIENT") {
    return currentMonthDays.value.filter(
      day => Number(day.draft || 0) > 0 || Number(day.returned || 0) > 0
        || (Number(day.patientTotal || 0) > 0 && Number(day.missing || 0) > 0 && Number(day.patientCount || 0) > 0)
    ).length;
  }
  return currentMonthDays.value.filter(day => ["DRAFT", "RETURNED"].includes(day.status)).length;
});
const monthLines = computed(() => currentMonthDays.value.reduce((sum, day) => sum + Number(day.lineCount || 0), 0));
const monthPeople = computed(() => currentMonthDays.value.reduce((sum, day) => sum + Number(day.patientCount || 0), 0));
const monthExpected = computed(() => currentMonthDays.value.reduce((sum, day) => sum + Number(day.patientTotal || 0), 0));
const missingDays = computed(() => {
  if (props.month.slice(0, 7) > props.today.slice(0, 7)) return 0;
  return grid.value.filter(date => stateOf(date) === "missing").length;
});

const isFuture = (date: string) => date > props.today;

const stateOf = (date: string): CalendarState => {
  if (date.slice(0, 7) !== props.month.slice(0, 7)) return "outside";
  if (props.expectedFrom && date < props.expectedFrom) return "none";
  if (isFuture(date)) return "future";
  const day = dayOf(date);
  if (props.mode === "PATIENT") {
    if (day && Number(day.patientTotal || 0) > 0) {
      if (Number(day.missing || 0) === 0 && Number(day.draft || 0) === 0 && Number(day.returned || 0) === 0) return "filled";
      if (Number(day.patientCount || 0) > 0 || Number(day.draft || 0) > 0 || Number(day.returned || 0) > 0) return "suggest";
      return date === props.today ? "due" : "missing";
    }
    return date === props.today ? "due" : "none";
  }
  if (day) {
    if (["SUBMITTED", "PARTIALLY_APPROVED", "APPROVED"].includes(day.status)) return "filled";
    if (["DRAFT", "RETURNED"].includes(day.status)) return "suggest";
  }
  return date === props.today ? "due" : "missing";
};

const cellClass = (date: string) => ({
  [`is-${stateOf(date)}`]: true,
  "is-outside": date.slice(0, 7) !== props.month.slice(0, 7),
  "is-today": date === props.today,
  "is-selected": date === props.selectedDate
});

const badge = (date: string) => {
  const state = stateOf(date);
  if (state === "outside" || state === "future" || state === "none") return "";
  const day = dayOf(date);
  if (props.mode === "PATIENT") {
    if (day && Number(day.patientTotal || 0) > 0) {
      return `${day.patientCount || 0}/${day.patientTotal}人`;
    }
    return state === "due" ? "待填" : "";
  }
  if (day) {
    if (Number(day.lineCount || 0) > 0) return `${day.lineCount}项`;
    return "已填";
  }
  return state === "due" ? "待填" : "未填";
};

const stateLabel = (date: string) => {
  switch (stateOf(date)) {
    case "filled":
      return "已填写";
    case "due":
      return "今天应填";
    case "missing":
      return "实际未填";
    case "suggest":
      return "建议补充";
    case "none":
      return "当天无患者";
    case "future":
      return "未到日期";
    default:
      return "非本月";
  }
};

const dateTitle = (date: string) => {
  const diff = dayjs(date).diff(dayjs(props.today), "day");
  if (diff === 0) return `${dayjs(date).format("MM/DD")} 今天`;
  if (diff === -1) return `${dayjs(date).format("MM/DD")} 昨天`;
  return `${dayjs(date).format("MM/DD")} ${weekdayNames[dayjs(date).day()]}`;
};

const selectedTitle = computed(() => dateTitle(props.selectedDate));
const ariaLabel = (date: string) => `${dateTitle(date)}，${stateLabel(date)}${badge(date) ? `，${badge(date)}` : ""}`;
const formatDateTime = (value?: string) => (value ? dayjs(value).format("MM-DD HH:mm") : "");

const shiftMonth = (offset: number) => {
  emit("update:month", dayjs(props.month).add(offset, "month").startOf("month").format("YYYY-MM-01"));
};

const select = (date: string) => {
  if (isFuture(date)) return;
  if (date.slice(0, 7) !== props.month.slice(0, 7)) {
    emit("update:month", dayjs(date).startOf("month").format("YYYY-MM-01"));
  }
  emit("select-date", date);
};
</script>

<style scoped lang="scss">
.fill-calendar {
  --fill-brand: #347d68;
  --fill-brand-soft: #edf7f1;
  --fill-warn: #b7791f;
  --fill-warn-soft: #fdf6e7;
  --fill-danger: #b54747;
  --fill-danger-soft: #fbefef;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  background: #fffdf9;
  border: 1px solid #efe8dd;
  border-radius: 14px;
}

.fill-calendar.is-wide {
  display: grid;
  grid-template-areas:
    "head grid"
    "summary grid"
    "legend grid"
    "selected grid";
  grid-template-columns: minmax(200px, 240px) minmax(0, 1fr);
  gap: 10px 22px;
  align-items: start;

  .calendar-head {
    grid-area: head;
    flex-direction: column;
    align-items: flex-start;
  }

  .calendar-summary {
    grid-area: summary;
    flex-direction: column;
    align-items: flex-start;
  }

  .calendar-grid {
    grid-area: grid;
    min-height: 252px;
  }

  .calendar-cell {
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    height: 50px;
    padding: 8px 10px;
  }

  .calendar-legend {
    grid-area: legend;
  }

  .calendar-selected {
    grid-area: selected;
  }
}

.calendar-head {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  justify-content: space-between;
}

.calendar-title {
  display: flex;
  flex-direction: column;
  gap: 3px;

  strong {
    color: #2f2d27;
    font-size: 15px;
  }
}

.eyebrow {
  color: var(--fill-brand);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

.calendar-nav {
  display: flex;
  gap: 2px;
  align-items: center;

  .el-button {
    padding: 2px 5px;
    font-size: 16px;
  }
}

.today-button {
  font-size: 12px !important;
  color: var(--fill-brand) !important;
}

.calendar-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  padding: 7px 9px;
  color: #6d675c;
  font-size: 11px;
  background: #f8f5ef;
  border-radius: 9px;

  b {
    color: #2f2d27;
    font-size: 13px;
    font-variant-numeric: tabular-nums;
  }

  .is-missing b {
    color: var(--fill-danger);
  }

  .is-suggest b {
    color: var(--fill-warn);
  }

  .is-new b {
    color: var(--fill-brand);
  }
}

.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 3px;
  min-height: 224px;
}

.weekday {
  padding-bottom: 2px;
  color: #9a9285;
  font-size: 11px;
  text-align: center;
}

.calendar-cell {
  display: flex;
  flex-direction: column;
  gap: 1px;
  align-items: center;
  justify-content: flex-start;
  min-width: 0;
  height: 46px;
  padding: 4px 1px 2px;
  font: inherit;
  color: #2f2d27;
  cursor: pointer;
  background: #faf7f1;
  border: 1px solid transparent;
  border-radius: 8px;
  transition:
    background-color 150ms ease,
    border-color 150ms ease;

  &:hover {
    border-color: #d9cfc0;
  }

  &:focus-visible {
    outline: 2px solid var(--fill-brand);
    outline-offset: 1px;
  }

  &.is-outside {
    opacity: 0.28;
  }

  &.is-future {
    color: #b3aca0;
    cursor: default;
    background: transparent;
  }

  &.is-filled {
    color: #1f5a4a;
    background: var(--fill-brand-soft);
    border-color: #cfe6da;
  }

  &.is-due {
    background: #f2faf6;
    border-color: var(--fill-brand);
    border-style: dashed;
  }

  &.is-missing {
    color: var(--fill-danger);
    background: var(--fill-danger-soft);
    border-color: #efd3d3;
  }

  &.is-suggest {
    color: #8a5a13;
    background: var(--fill-warn-soft);
    border-color: #f0dfb8;
  }

  &.is-today .cell-number {
    font-weight: 800;
    text-decoration: underline;
    text-underline-offset: 2px;
  }

  &.is-selected {
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--fill-brand) 26%, transparent);
  }
}

.cell-number {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  line-height: 16px;
}

.cell-badge {
  max-width: 100%;
  padding: 0 4px;
  overflow: hidden;
  font-size: 9px;
  font-variant-numeric: tabular-nums;
  line-height: 13px;
  white-space: nowrap;
  text-overflow: ellipsis;
  background: rgb(255 255 255 / 62%);
  border-radius: 6px;
}

.calendar-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  color: #9a9285;
  font-size: 10px;

  span {
    display: inline-flex;
    gap: 4px;
    align-items: center;
  }

  i {
    width: 9px;
    height: 9px;
    border: 1px solid transparent;
    border-radius: 3px;
  }

  .is-filled {
    background: var(--fill-brand-soft);
    border-color: #cfe6da;
  }

  .is-due {
    background: #f2faf6;
    border-color: var(--fill-brand);
    border-style: dashed;
  }

  .is-missing {
    background: var(--fill-danger-soft);
    border-color: #efd3d3;
  }

  .is-suggest {
    background: var(--fill-warn-soft);
    border-color: #f0dfb8;
  }
}

.calendar-selected {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding-top: 8px;
  border-top: 1px dashed #efe8dd;

  > span {
    display: flex;
    flex-direction: column;
    gap: 1px;
    min-width: 0;
  }

  strong {
    color: #2f2d27;
    font-size: 12px;
  }

  small {
    color: #9a9285;
    font-size: 10px;
  }
}

.cell-popover {
  display: flex;
  flex-direction: column;
  gap: 6px;

  strong {
    color: #2f2d27;
    font-size: 13px;
  }

  p {
    margin: 0;
    color: #9a9285;
    font-size: 11px;
  }

  dl {
    display: flex;
    flex-direction: column;
    gap: 3px;
    margin: 0;
  }

  dl > div {
    display: flex;
    justify-content: space-between;
    font-size: 12px;
  }

  dt {
    color: #9a9285;
  }

  dd {
    margin: 0;
    color: #2f2d27;
    font-variant-numeric: tabular-nums;
  }
}

.pop-status {
  align-self: flex-start;
  padding: 1px 7px;
  font-size: 11px;
  border-radius: 999px;

  &.is-filled {
    color: #1f5a4a;
    background: var(--fill-brand-soft);
  }

  &.is-due {
    color: var(--fill-brand);
    background: #f2faf6;
  }

  &.is-missing {
    color: var(--fill-danger);
    background: var(--fill-danger-soft);
  }

  &.is-suggest {
    color: var(--fill-warn);
    background: var(--fill-warn-soft);
  }
}

@media (max-width: 1180px) {
  .fill-calendar.is-wide {
    display: flex;
    flex-direction: column;

    .calendar-head {
      flex-direction: row;
      align-items: flex-start;
    }

    .calendar-summary {
      flex-direction: row;
      align-items: center;
    }

    .calendar-grid {
      min-height: 224px;
    }

    .calendar-cell {
      height: 46px;
    }
  }
}

@media (prefers-reduced-motion: reduce) {
  .calendar-cell {
    transition: none;
  }
}
</style>
