<template>
  <section class="mc" aria-label="复查日历">
    <header class="mc-head">
      <el-button link aria-label="上个月" @click="emit('update:month', shiftMonth(-1))">‹</el-button>
      <strong>{{ dayjs(month).format("YYYY 年 M 月") }}</strong>
      <el-button link aria-label="下个月" @click="emit('update:month', shiftMonth(1))">›</el-button>
      <el-button size="small" link class="mc-today" @click="backToToday">今天</el-button>
    </header>

    <div v-loading="loading" class="mc-grid" role="grid">
      <span v-for="w in WEEK_HEAD" :key="w" class="mc-week" role="columnheader">{{ w }}</span>
      <el-popover
        v-for="date in grid"
        :key="date"
        placement="left"
        :width="240"
        trigger="hover"
        :show-after="200"
        :disabled="!activeOf(date).length"
      >
        <template #reference>
          <button
            type="button"
            class="mc-cell"
            :class="cellClass(date)"
            :aria-label="`${dateTitle(date, today)}，${activeOf(date).length} 位复查`"
            :aria-pressed="date === selected"
            @click="select(date)"
          >
            <span class="mc-num">{{ Number(date.slice(8)) }}</span>
            <span v-if="activeOf(date).length" class="mc-pill">{{ activeOf(date).length }}人</span>
          </button>
        </template>
        <div class="mc-pop">
          <strong>{{ dateTitle(date, today) }} · {{ activeOf(date).length }} 位</strong>
          <ul>
            <li v-for="e in activeOf(date).slice(0, POP_LIMIT)" :key="e.id">
              <span class="mc-pop-name">{{ e.patientName }}</span>
              <span class="mc-pop-phone">{{ e.phone || "无电话" }}</span>
              <span class="mc-pop-st" :class="`s-${e.status.toLowerCase()}`">{{ STATUS_TEXT[e.status] }}</span>
            </li>
          </ul>
          <p v-if="activeOf(date).length > POP_LIMIT">另有 {{ activeOf(date).length - POP_LIMIT }} 位，点击日期查看全部</p>
        </div>
      </el-popover>
    </div>

    <!-- 点击日期后的当天明细 -->
    <div class="mc-detail" aria-live="polite">
      <div class="mc-detail-head">
        <strong>{{ dateTitle(selected, today) }}</strong>
        <span>{{ activeOf(selected).length }} 位复查</span>
        <el-button v-if="canEdit" size="small" type="primary" plain @click="emit('register', selected)">登记到这天</el-button>
      </div>
      <ul v-if="activeOf(selected).length" class="mc-detail-list">
        <li v-for="e in activeOf(selected)" :key="e.id">
          <span class="mc-pop-name">{{ e.patientName }}</span>
          <span class="mc-pop-phone">{{ e.phone || "—" }}</span>
          <span class="mc-pop-st" :class="`s-${e.status.toLowerCase()}`">{{ STATUS_TEXT[e.status] }}</span>
          <span v-if="e.note" class="mc-detail-note">{{ e.note }}</span>
        </li>
      </ul>
      <p v-else class="mc-detail-empty">这天没有复查登记</p>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import dayjs from "dayjs";
import type { RecheckDay, RecheckEntry, RecheckStatus } from "@/api/modules/clinic/recheckSchedule";
import { dateTitle, monthGrid } from "./dates";

const props = defineProps<{
  /** 当前显示月份，YYYY-MM-01 */
  month: string;
  today: string;
  days: RecheckDay[];
  canEdit: boolean;
  loading?: boolean;
}>();

const emit = defineEmits<{ "update:month": [month: string]; register: [date: string] }>();

const WEEK_HEAD = ["一", "二", "三", "四", "五", "六", "日"];
const POP_LIMIT = 8;
const STATUS_TEXT: Record<RecheckStatus, string> = {
  PLANNED: "预约",
  ARRIVED: "已到",
  ABSENT: "没来",
  RESCHEDULED: "已改期",
  CANCELLED: "已撤销"
};

const selected = ref(props.today);
watch(
  () => props.today,
  value => (selected.value = value)
);

const grid = computed(() => monthGrid(props.month));

/** 日期 → 当天有效登记（不含已改期走的） */
const byDate = computed(() => {
  const map = new Map<string, RecheckEntry[]>();
  for (const day of props.days)
    map.set(
      day.date,
      day.entries.filter(e => e.status !== "RESCHEDULED")
    );
  return map;
});
const activeOf = (date: string) => byDate.value.get(date) ?? [];

const cellClass = (date: string) => {
  const entries = activeOf(date);
  return {
    out: date.slice(0, 7) !== props.month.slice(0, 7),
    past: date < props.today,
    today: date === props.today,
    selected: date === selected.value,
    has: entries.length > 0,
    pending: date < props.today && entries.some(e => e.status === "PLANNED")
  };
};

const shiftMonth = (n: number) => dayjs(props.month).add(n, "month").startOf("month").format("YYYY-MM-DD");

const select = (date: string) => {
  selected.value = date;
  if (date.slice(0, 7) !== props.month.slice(0, 7)) emit("update:month", dayjs(date).startOf("month").format("YYYY-MM-DD"));
};

const backToToday = () => select(props.today);
</script>

<style scoped lang="scss">
.mc {
  --brand: #0f766e;

  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}
.mc-head {
  display: flex;
  gap: 4px;
  align-items: center;
  strong {
    flex: 1;
    font-size: 15px;
    text-align: center;
  }
  .el-button.is-link {
    font-size: 18px;
  }
  .mc-today {
    font-size: 12px !important;
    color: var(--brand);
  }
}
.mc-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 3px;
}
.mc-week {
  padding-bottom: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.mc-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  align-items: center;
  justify-content: flex-start;
  height: 46px;
  padding: 4px 0 0;
  font: inherit;
  color: var(--el-text-color-primary);
  cursor: pointer;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 8px;
  transition:
    background-color 150ms ease,
    border-color 150ms ease;
  &:hover {
    background: var(--el-fill-color-light);
  }
  &:focus-visible {
    outline: 2px solid var(--brand);
    outline-offset: 1px;
  }
  &.has {
    background: color-mix(in srgb, var(--brand) 6%, transparent);
  }
  &.out {
    opacity: 0.4;
  }
  &.past .mc-num {
    color: var(--el-text-color-placeholder);
  }
  &.today {
    border-color: var(--brand);
    .mc-num {
      font-weight: 700;
      color: var(--brand);
    }
  }
  &.selected {
    background: color-mix(in srgb, var(--brand) 14%, transparent);
    border-color: var(--brand);
  }
}
.mc-num {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  line-height: 18px;
}
.mc-pill {
  padding: 0 6px;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  line-height: 16px;
  color: #ffffff;
  white-space: nowrap;
  background: var(--brand);
  border-radius: 8px;
}
.mc-cell.past .mc-pill {
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-darker);
}
.mc-cell.pending .mc-pill {
  color: #ffffff;
  background: #b45309;
}
.mc-pop {
  strong {
    font-size: 13px;
  }
  ul {
    padding: 0;
    margin: 6px 0 0;
    list-style: none;
  }
  li {
    display: grid;
    grid-template-columns: minmax(4em, max-content) 1fr auto;
    gap: 8px;
    padding: 3px 0;
    font-size: 13px;
  }
  p {
    margin: 6px 0 0;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}
.mc-pop-name {
  font-weight: 600;
}
.mc-pop-phone {
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-regular);
}
.mc-pop-st {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  &.s-arrived {
    color: #15803d;
  }
  &.s-absent {
    color: #b45309;
  }
}
.mc-detail {
  padding-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.mc-detail-head {
  display: flex;
  gap: 8px;
  align-items: center;
  strong {
    font-size: 14px;
  }
  span {
    flex: 1;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}
.mc-detail-list {
  max-height: 260px;
  padding: 0;
  margin: 8px 0 0;
  overflow-y: auto;
  list-style: none;
  li {
    display: grid;
    grid-template-columns: minmax(4em, max-content) 1fr auto;
    gap: 2px 8px;
    padding: 5px 0;
    font-size: 13px;
    border-top: 1px dashed var(--el-border-color-lighter);
    &:first-child {
      border-top: none;
    }
  }
}
.mc-detail-note {
  grid-column: 1 / -1;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.mc-detail-empty {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
