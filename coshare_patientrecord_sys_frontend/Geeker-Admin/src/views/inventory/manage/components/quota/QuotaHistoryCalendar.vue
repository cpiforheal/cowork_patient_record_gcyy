<template>
  <section ref="rootRef" class="qhc" aria-label="定额历史日历">
    <header class="qhc-head">
      <div class="qhc-title">
        <span class="qhc-eyebrow">历史回顾</span>
        <strong>{{ monthTitle }}</strong>
      </div>
      <div class="qhc-nav">
        <button type="button" aria-label="上个月" @click="shiftMonth(-1)">‹</button>
        <button type="button" class="qhc-today" @click="goToday">今天</button>
        <button type="button" aria-label="下个月" @click="shiftMonth(1)">›</button>
      </div>
    </header>

    <dl class="qhc-stats">
      <div>
        <dt>已提交</dt>
        <dd><b>{{ stats.submittedDays }}</b> 天</dd>
      </div>
      <div>
        <dt>平均人次</dt>
        <dd><b>{{ stats.averageHeadcount ?? "—" }}</b><span v-if="stats.averageHeadcount !== null"> 人</span></dd>
      </div>
      <div>
        <dt>扣减项次</dt>
        <dd><b>{{ stats.deductedLines }}</b> 项次</dd>
      </div>
      <div>
        <dt>例外</dt>
        <dd><b>{{ stats.exceptions }}</b> 次<small v-if="stats.pendingExceptions">待审 {{ stats.pendingExceptions }}</small></dd>
      </div>
    </dl>

    <p v-if="stateNote" class="qhc-state">{{ stateNote }}</p>

    <div class="qhc-weekdays" aria-hidden="true">
      <span v-for="weekday in weekdays" :key="weekday">{{ weekday }}</span>
    </div>

    <div ref="gridRef" v-loading="loading" class="qhc-grid" role="grid" aria-label="日期">
      <button
        v-for="date in grid"
        :key="date"
        type="button"
        class="qhc-cell"
        :class="cellClass(date)"
        :data-date="date"
        :tabindex="cellTabIndex(date)"
        :disabled="cellDisabled(date)"
        :aria-label="ariaLabel(date)"
        :aria-expanded="openDate === date"
        :aria-controls="openDate === date ? 'qhc-accordion' : undefined"
        @focus="tabDate = date"
        @keydown="onCellKeydown($event, date)"
        @click="selectDate(date)"
      >
        <span class="qhc-date">
          <span class="qhc-num">{{ Number(date.slice(8)) }}</span>
          <span v-if="date === today" class="qhc-today-mark">今天</span>
        </span>
        <template v-if="!isOutside(date)">
          <span v-if="metaOf(date).label" class="qhc-capsule" :class="`is-${metaOf(date).tone}`">
            <i aria-hidden="true">{{ metaOf(date).glyph }}</i>{{ metaOf(date).label }}
          </span>
          <span v-if="headcountOf(date)" class="qhc-capsule is-headcount">{{ headcountOf(date) }}</span>
          <span
            v-if="thirdOf(date)"
            class="qhc-capsule"
            :class="`is-${thirdOf(date)?.tone}`"
            :title="thirdOf(date)?.full"
          >
            {{ thirdOf(date)?.text }}
          </span>
        </template>
      </button>
    </div>

    <div class="qhc-legend">
      <span><i class="is-ok" aria-hidden="true">✓</i>已确认</span>
      <span><i class="is-warm" aria-hidden="true">○</i>待填/草稿</span>
      <span><i class="is-warm" aria-hidden="true">!</i>例外/未足额</span>
      <span><i class="is-off" aria-hidden="true">–</i>未启用</span>
    </div>

    <QuotaDayAccordion
      v-if="openDate"
      id="qhc-accordion"
      ref="accordionRef"
      :date="openDate"
      :department-key="departmentKey"
      :department-name="departmentName"
      :today="today"
      :selected-date="selectedDate"
      @close="closeAccordion"
      @switch-date="switchDate"
      @open-exception="date => $emit('report-exception', date)"
      @loaded="scrollAccordion"
      @keydown.escape="closeAccordion"
    />

    <p class="qhc-hint">点击日期查看当日回执；左侧 7 天条用于切换填报日。</p>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import dayjs from "dayjs";
import type { InventoryDepartmentDailyReportCalendarDay } from "@/api/modules/inventory";
import QuotaDayAccordion from "./QuotaDayAccordion.vue";
import {
  calendarStatusMeta,
  calendarThirdCapsule,
  monthReviewStats
} from "./quotaReceipt";

const props = defineProps<{
  days: InventoryDepartmentDailyReportCalendarDay[];
  month: string;
  today: string;
  expectedFrom?: string;
  loading?: boolean;
  departmentKey: string;
  departmentName?: string;
  selectedDate: string;
}>();

const emit = defineEmits<{
  "update:month": [month: string];
  "select-date": [date: string];
  "report-exception": [date: string];
}>();

const weekdays = ["一", "二", "三", "四", "五", "六", "日"];
const rootRef = ref<HTMLElement>();
const gridRef = ref<HTMLElement>();
const accordionRef = ref<InstanceType<typeof QuotaDayAccordion>>();
const openDate = ref("");
const tabDate = ref(props.selectedDate);
const savedScroll = ref(0);

const monthTitle = computed(() => dayjs(props.month).format("YYYY 年 M 月"));
const grid = computed(() => {
  const first = dayjs(props.month).startOf("month");
  const start = first.subtract((first.day() + 6) % 7, "day");
  return Array.from({ length: 42 }, (_, index) => start.add(index, "day").format("YYYY-MM-DD"));
});
const byDate = computed(() => new Map(props.days.map(day => [day.date, day])));
const stats = computed(() => monthReviewStats(props.days, props.month, props.today, props.expectedFrom));
const stateNote = computed(() => {
  if (stats.value.state === "future") return "本月未开始";
  if (stats.value.state === "before") return "定额启用前";
  if (stats.value.draftDays) return `${stats.value.draftDays} 天草稿待提交`;
  if (!stats.value.submittedDays) return "暂无已提交记录";
  return "";
});
const dayOf = (date: string) => byDate.value.get(date);
const isOutside = (date: string) => date.slice(0, 7) !== props.month.slice(0, 7);
const isFuture = (date: string) => date > props.today;
const metaOf = (date: string) =>
  calendarStatusMeta(dayOf(date), date, props.today, props.expectedFrom);
const thirdOf = (date: string) => calendarThirdCapsule(dayOf(date));
const headcountOf = (date: string) => {
  const total = Number(dayOf(date)?.headcountTotal || 0);
  return total > 0 ? `${total}人` : "";
};

const ariaLabel = (date: string) => {
  if (isOutside(date)) return `${date}，非本月，点击切换到该月`;
  const meta = metaOf(date);
  const parts = [dayjs(date).format("M月D日 dddd"), meta.label || "无状态"];
  const headcount = headcountOf(date);
  if (headcount) parts.push(headcount);
  const third = thirdOf(date);
  if (third) parts.push(third.full);
  const pending = Number(dayOf(date)?.pendingExceptionCount || 0);
  if (pending) parts.push(`${pending} 条待审例外`);
  if (openDate.value === date) parts.push("已展开");
  return parts.join("，");
};

const cellClass = (date: string) => {
  const meta = metaOf(date);
  return {
    "is-outside": isOutside(date),
    "is-future": isFuture(date),
    "is-today": date === props.today,
    "is-working": date === props.selectedDate,
    "is-open": date === openDate.value,
    [`is-${meta.tone}`]: !isOutside(date) && Boolean(meta.label)
  };
};

const cellDisabled = (date: string) =>
  isFuture(date) || Boolean(props.expectedFrom && date < props.expectedFrom);

const cellTabIndex = (date: string) => (date === tabDate.value ? 0 : -1);

const focusCell = (date: string) => {
  gridRef.value?.querySelector<HTMLButtonElement>(`[data-date="${date}"]`)?.focus();
};

const focusGridIndex = (target: number) => {
  while (target >= 0 && target < grid.value.length && cellDisabled(grid.value[target])) target -= 1;
  if (target < 0 || target >= grid.value.length) return;
  tabDate.value = grid.value[target];
  nextTick(() => focusCell(grid.value[target]));
};

const moveFocus = (from: string, step: number) => {
  const index = grid.value.indexOf(from);
  if (index < 0) return;
  let target = index + step;
  while (target >= 0 && target < grid.value.length && cellDisabled(grid.value[target])) {
    target += step > 0 ? 1 : -1;
  }
  if (target < 0 || target >= grid.value.length) return;
  tabDate.value = grid.value[target];
  nextTick(() => focusCell(grid.value[target]));
};

const onCellKeydown = (event: KeyboardEvent, date: string) => {
  const index = grid.value.indexOf(date);
  if (event.key === "Escape" && openDate.value) {
    event.preventDefault();
    closeAccordion();
    return;
  }
  if (index < 0) return;
  if (event.key === "ArrowLeft") {
    event.preventDefault();
    moveFocus(date, -1);
  } else if (event.key === "ArrowRight") {
    event.preventDefault();
    moveFocus(date, 1);
  } else if (event.key === "ArrowUp") {
    event.preventDefault();
    moveFocus(date, -7);
  } else if (event.key === "ArrowDown") {
    event.preventDefault();
    moveFocus(date, 7);
  } else if (event.key === "Home") {
    event.preventDefault();
    focusGridIndex(index - (index % 7));
  } else if (event.key === "End") {
    event.preventDefault();
    focusGridIndex(index - (index % 7) + 6);
  }
};

const scrollAccordion = () => {
  nextTick(() => {
    const panel = rootRef.value;
    if (!panel) return;
    const accordion = panel.querySelector<HTMLElement>("#qhc-accordion");
    if (accordion && accordion !== observedAccordion && typeof ResizeObserver !== "undefined") {
      accordionObserver?.disconnect();
      observedAccordion = accordion;
      accordionObserver = new ResizeObserver(() => {
        if (openDate.value && rootRef.value) {
          rootRef.value.scrollTop = rootRef.value.scrollHeight;
        }
      });
      accordionObserver.observe(accordion);
    }
    accordion?.focus({ preventScroll: true });
    panel.scrollTop = panel.scrollHeight;
    requestAnimationFrame(() => {
      panel.scrollTop = panel.scrollHeight;
    });
  });
};

const selectDate = (date: string) => {
  if (isFuture(date)) return;
  if (isOutside(date)) {
    emit("update:month", dayjs(date).startOf("month").format("YYYY-MM-01"));
    return;
  }
  if (props.expectedFrom && date < props.expectedFrom) return;
  const wasOpen = Boolean(openDate.value);
  openDate.value = openDate.value === date ? "" : date;
  if (openDate.value) {
    if (!wasOpen) savedScroll.value = rootRef.value?.scrollTop || 0;
    scrollAccordion();
  }
};

const closeAccordion = () => {
  const date = openDate.value;
  openDate.value = "";
  accordionObserver?.disconnect();
  observedAccordion = null;
  if (date) {
    nextTick(() => {
      if (rootRef.value) rootRef.value.scrollTop = savedScroll.value;
      focusCell(date);
    });
  }
};

const switchDate = (date: string) => {
  openDate.value = "";
  emit("select-date", date);
};

const shiftMonth = (offset: number) => {
  emit("update:month", dayjs(props.month).add(offset, "month").startOf("month").format("YYYY-MM-01"));
};

const goToday = () => {
  if (props.month.slice(0, 7) !== props.today.slice(0, 7)) {
    emit("update:month", `${props.today.slice(0, 7)}-01`);
  }
  if (!openDate.value) savedScroll.value = rootRef.value?.scrollTop || 0;
  openDate.value = props.today;
  scrollAccordion();
};

watch(
  () => props.month,
  () => {
    if (openDate.value && openDate.value.slice(0, 7) !== props.month.slice(0, 7)) {
      openDate.value = "";
    }
    tabDate.value = grid.value.includes(props.today) ? props.today : grid.value[0];
  }
);
watch(
  () => props.selectedDate,
  date => {
    if (grid.value.includes(date)) tabDate.value = date;
  }
);

const invalidate = (date?: string) => {
  accordionRef.value?.invalidate(date);
};
defineExpose({ invalidate });

let accordionObserver: ResizeObserver | undefined;
let observedAccordion: HTMLElement | null = null;
onBeforeUnmount(() => {
  accordionObserver?.disconnect();
});
</script>

<style scoped lang="scss">
.qhc {
  display: flex;
  flex-direction: column;
  gap: 12px;
  box-sizing: border-box;
  max-height: calc(100vh - 32px);
  padding: 18px;
  overflow-y: auto;
  background: var(--qhw-surface, #fffdf8);
  border: 1px solid var(--qhw-line, #e8dfd2);
  border-radius: var(--qhw-radius, 20px);
  box-shadow: var(--qhw-shadow-soft, 0 8px 24px rgba(72, 55, 36, 0.05));
  overscroll-behavior: contain;
}

.qhc-grid,
.qhc-head,
.qhc-stats,
.qhc-legend,
.qhc-hint {
  flex-shrink: 0;
}

.qhc-head {
  position: sticky;
  top: -18px;
  z-index: 3;
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 2px 0 8px;
  background: var(--qhw-surface, #fffdf8);
}

.qhc-title {
  display: flex;
  flex-direction: column;
  gap: 1px;

  strong {
    color: var(--qhw-ink, #2f2a24);
    font-size: 17px;
    font-weight: 600;
    letter-spacing: -0.01em;
  }
}

.qhc-eyebrow {
  color: var(--qhw-muted, #7b7064);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.qhc-nav {
  display: flex;
  gap: 2px;
  align-items: center;

  button {
    padding: 3px 7px;
    font: inherit;
    font-size: 15px;
    color: var(--qhw-muted, #7b7064);
    cursor: pointer;
    background: none;
    border: 0;
    border-radius: 7px;

    &:hover {
      color: var(--qhw-accent, #2f6b58);
      background: var(--qhw-surface-2, #fbf6ee);
    }

    &:focus-visible {
      outline: 2px solid var(--qhw-accent, #2f6b58);
      outline-offset: 2px;
    }
  }

  .qhc-today {
    font-size: 11px;
    color: var(--qhw-accent, #2f6b58);
  }
}

.qhc-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  margin: 0;

  > div {
    min-width: 0;
    padding: 6px 8px;
    background: var(--qhw-surface-2, #fbf6ee);
    border: 1px solid var(--qhw-line, #e8dfd2);
    border-radius: 8px;
  }

  dt {
    overflow: hidden;
    color: var(--qhw-muted, #7b7064);
    font-size: 9px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  dd {
    display: flex;
    gap: 3px;
    align-items: baseline;
    margin: 1px 0 0;
    color: var(--qhw-muted, #7b7064);
    font-size: 9px;
    white-space: nowrap;
  }

  b {
    color: var(--qhw-ink, #2f2a24);
    font-size: 16px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    letter-spacing: -0.02em;
  }

  small {
    margin-left: auto;
    color: var(--qhw-warm, #b7793f);
    font-size: 8px;
  }
}

.qhc-state {
  margin: -4px 0 0;
  color: var(--qhw-muted, #7b7064);
  font-size: 10px;
}

.qhc-weekdays {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 4px;
  color: var(--qhw-muted, #7b7064);
  font-size: 9px;
  text-align: center;
}

.qhc-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  grid-auto-rows: 96px;
  gap: 4px;
  min-height: 584px;
}

.qhc-cell {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  padding: 5px 4px;
  font: inherit;
  color: var(--qhw-ink, #2f2a24);
  text-align: left;
  cursor: pointer;
  background: var(--qhw-surface-2, #fbf6ee);
  border: 1px solid transparent;
  border-radius: 9px;
  transition: border-color 150ms ease, background-color 150ms ease;

  &:hover {
    border-color: #d7c8b7;
  }

  &:focus-visible {
    outline: 2px solid var(--qhw-accent, #2f6b58);
    outline-offset: 1px;
  }

  &.is-outside {
    opacity: 0.3;
  }

  &.is-future,
  &:disabled {
    color: #b3aca0;
    cursor: default;
    background: transparent;
  }

  &.is-today {
    background: var(--qhw-accent-soft, #e9f1ed);
  }

  &.is-today .qhc-num {
    font-weight: 800;
  }

  &.is-working {
    border-color: #b9cfc3;
  }

  &.is-open {
    border-color: var(--qhw-accent, #2f6b58);
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--qhw-accent, #2f6b58) 22%, transparent);
  }
}

.qhc-date {
  display: flex;
  gap: 3px;
  align-items: baseline;
  min-width: 0;
}

.qhc-num {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  line-height: 15px;
}

.qhc-today-mark {
  overflow: hidden;
  color: var(--qhw-accent, #2f6b58);
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qhc-capsule {
  display: inline-flex;
  gap: 2px;
  align-items: center;
  max-width: 100%;
  height: 17px;
  padding: 0 5px;
  overflow: hidden;
  font-size: 9px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 1px solid transparent;
  border-radius: 5px;

  i {
    font-style: normal;
    font-size: 8px;
  }

  &.is-ok {
    color: #2f6b58;
    background: var(--qhw-accent-soft, #e9f1ed);
    border-color: #d5e5dc;
  }

  &.is-warm {
    color: #8a5a13;
    background: var(--qhw-warm-soft, #f7ecdd);
    border-color: #ebd8bd;
  }

  &.is-draft {
    color: #8a5a13;
    background: var(--qhw-warm-soft, #f7ecdd);
    border-color: #ebd8bd;
    border-style: dashed;
  }

  &.is-muted {
    color: var(--qhw-muted, #7b7064);
    background: rgb(255 255 255 / 52%);
    border-color: var(--qhw-line, #e8dfd2);
  }

  &.is-off {
    color: var(--qhw-muted, #7b7064);
    background: transparent;
    border-color: transparent;
  }

  &.is-headcount {
    color: var(--qhw-ink, #2f2a24);
    background: transparent;
    border-color: transparent;
    font-variant-numeric: tabular-nums;
  }
}

.qhc-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 9px;
  color: var(--qhw-muted, #7b7064);
  font-size: 9px;

  span {
    display: inline-flex;
    gap: 3px;
    align-items: center;
  }

  i {
    display: inline-grid;
    width: 13px;
    height: 13px;
    font-size: 8px;
    font-style: normal;
    place-items: center;
    border-radius: 4px;

    &.is-ok {
      color: #2f6b58;
      background: var(--qhw-accent-soft, #e9f1ed);
    }

    &.is-warm {
      color: #8a5a13;
      background: var(--qhw-warm-soft, #f7ecdd);
    }

    &.is-off {
      color: var(--qhw-muted, #7b7064);
      background: var(--qhw-surface-2, #fbf6ee);
    }
  }
}

.qhc-hint {
  margin: 0;
  color: var(--qhw-muted, #7b7064);
  font-size: 10px;
  text-align: center;
}

@media (max-width: 1180px) {
  .qhc-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 1024px) {
  .qhc {
    max-height: none;
    overflow-y: visible;
  }

  .qhc-head {
    position: static;
  }
}

@media (prefers-reduced-motion: reduce) {
  .qhc-cell {
    transition: none;
  }
}
</style>
