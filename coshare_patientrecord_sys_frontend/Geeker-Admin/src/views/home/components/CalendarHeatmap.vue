<template>
  <div class="calendar-heatmap-card">
    <div class="calendar-toolbar">
      <div>
        <span class="scope-eyebrow">主控区 · 月历热力</span>
        <h2>{{ monthTitle }}</h2>
        <p>本月收录 {{ monthTotal }} 人，单日峰值 {{ peakCount }} 人</p>
      </div>
      <div class="calendar-actions">
        <el-button :icon="ArrowLeft" circle aria-label="上个月" @click="$emit('shiftMonth', -1)" />
        <el-button @click="$emit('currentMonth')">本月</el-button>
        <el-button type="primary" plain @click="$emit('selectMonth')">整月</el-button>
        <el-button :icon="ArrowRight" circle aria-label="下个月" @click="$emit('shiftMonth', 1)" />
      </div>
    </div>
    <div class="calendar-weekdays">
      <span v-for="weekday in weekdayLabels" :key="weekday">{{ weekday }}</span>
    </div>
    <div class="calendar-grid">
      <button
        v-for="day in cells"
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
        @click="$emit('selectDate', day)"
      >
        <span class="day-number">{{ day.day || "" }}</span>
        <span v-if="!day.isBlank" class="day-count">{{ day.count ? `${day.count} 人` : "空" }}</span>
      </button>
    </div>
    <div class="heatmap-legend">
      <span class="legend-anchor">0 人</span>
      <i v-for="level in [0, 1, 2, 3, 4]" :key="level" :class="`is-level-${level}`" />
      <span class="legend-anchor">1-3 人</span>
      <span class="legend-anchor">4+ 人</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ArrowLeft, ArrowRight } from "@element-plus/icons-vue";

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

defineProps<{
  monthTitle: string;
  monthTotal: number;
  peakCount: number;
  weekdayLabels: string[];
  cells: CalendarDayCell[];
}>();

defineEmits<{
  shiftMonth: [offset: number];
  currentMonth: [];
  selectMonth: [];
  selectDate: [day: CalendarDayCell];
}>();
</script>

<style scoped lang="scss">
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

  .legend-anchor {
    font-variant-numeric: tabular-nums;
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

@media (max-width: 760px) {
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
