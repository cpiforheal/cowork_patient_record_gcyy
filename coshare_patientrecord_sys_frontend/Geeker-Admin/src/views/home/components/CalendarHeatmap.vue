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
        :style="day.hoverColor ? { '--hover-color': day.hoverColor } : undefined"
        :disabled="day.isBlank"
        :aria-label="day.ariaLabel"
        :title="day.ariaLabel"
        @click="$emit('selectDate', day)"
      >
        <span class="day-number">{{ day.day || "" }}</span>
        <span v-if="!day.isBlank" class="day-count">{{ day.count ? `${day.count} 人` : "空" }}</span>
      </button>
    </div>
    <div class="heatmap-legend" aria-label="热力图颜色说明">
      <span class="legend-anchor">0 人</span>
      <i v-for="level in [0, 1, 2, 3, 4]" :key="level" :class="`is-level-${level}`" />
      <span class="legend-anchor">低 → 高（按当月峰值分级）</span>
      <span class="legend-anchor">峰值 {{ peakCount }} 人</span>
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
  hoverColor: string;
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
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--hos-chart-primary-soft, var(--el-color-primary-light-9)) 58%, transparent),
    var(--hos-chart-panel, var(--el-bg-color))
  );
  border-color: var(--hos-chart-line-soft, rgb(20 184 166 / 18%));
}
.scope-eyebrow {
  font-size: 12px;
  font-weight: 700;
  color: var(--hos-chart-primary, #008f84);
}
.calendar-toolbar {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  justify-content: space-between;
  h2,
  p {
    margin: 0;
  }
  h2 {
    margin-top: 4px;
    font-size: 18px;
    line-height: 1.35;
    color: var(--el-text-color-primary);
  }
  p {
    margin-top: 4px;
    color: var(--el-text-color-secondary);
  }
}
.calendar-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
  align-items: center;
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
    font-size: 12px;
    font-weight: 700;
    color: var(--el-text-color-secondary);
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
  background: var(--hos-chart-panel-soft, var(--el-fill-color-light));
  border: 1px solid var(--hos-chart-line-soft, #dfeee9);
  border-radius: 8px;
  transition:
    border-color var(--motion-control, 180ms) var(--ease-out, ease),
    box-shadow var(--motion-control, 180ms) var(--ease-out, ease),
    transform var(--motion-control, 180ms) var(--ease-out, ease);
  @media (hover: hover) and (pointer: fine) {
    &:not(.is-empty):hover {
      // hover 贪心色块：每格专属强调色（父组件按网格邻接贪心分配，相邻格不重复）
      background: var(--hover-color, var(--hos-chart-primary, #0f9f8f));
      border-color: var(--hover-color, var(--hos-chart-primary, #0f9f8f));
      box-shadow: 0 6px 14px color-mix(in srgb, var(--hos-chart-primary, #0f766e) 15%, transparent);
      transform: translateY(-1px);
      .day-number,
      .day-count {
        color: #ffffff;
      }
    }
  }
  &.is-empty {
    pointer-events: none;
    visibility: hidden;
  }
  &.is-level-1 {
    background: color-mix(
      in srgb,
      var(--hos-chart-primary, var(--el-color-primary)) 12%,
      var(--hos-chart-panel, var(--el-bg-color))
    );
    border-color: color-mix(in srgb, var(--hos-chart-primary, var(--el-color-primary)) 18%, transparent);
  }
  &.is-level-2 {
    background: color-mix(
      in srgb,
      var(--hos-chart-primary, var(--el-color-primary)) 30%,
      var(--hos-chart-panel, var(--el-bg-color))
    );
    border-color: color-mix(in srgb, var(--hos-chart-primary, var(--el-color-primary)) 34%, transparent);
  }
  &.is-level-3 {
    color: var(--hos-chart-text, #07594f);
    background: color-mix(
      in srgb,
      var(--hos-chart-primary, var(--el-color-primary)) 52%,
      var(--hos-chart-panel, var(--el-bg-color))
    );
    border-color: color-mix(in srgb, var(--hos-chart-primary, var(--el-color-primary)) 56%, transparent);
  }
  &.is-level-4 {
    color: #ffffff;
    background: var(--hos-chart-primary, #0f9f8f);
    border-color: color-mix(in srgb, var(--hos-chart-primary, #0f766e) 70%, #000000);
    .day-count {
      color: rgb(255 255 255 / 86%);
    }
  }
  &.is-selected {
    border-color: var(--hos-chart-primary, #07594f);
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--hos-chart-primary, #0f766e) 22%, transparent);
  }
  &.is-today .day-number::after {
    margin-left: 4px;
    font-size: 11px;
    font-weight: 700;
    color: var(--hos-chart-warning, #b45309);
    content: "今";
  }
}
.day-number {
  font-size: 14px;
  font-weight: 700;
}
.day-count {
  overflow: hidden;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.heatmap-legend {
  display: flex;
  gap: 6px;
  align-items: center;
  justify-content: flex-end;
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  i {
    width: 18px;
    height: 10px;
    border: 1px solid var(--hos-chart-line-soft, #dfeee9);
    border-radius: 3px;
  }
  .legend-anchor {
    font-variant-numeric: tabular-nums;
  }
  .is-level-0 {
    background: var(--hos-chart-panel-soft, var(--el-fill-color-light));
  }
  .is-level-1 {
    background: color-mix(
      in srgb,
      var(--hos-chart-primary, var(--el-color-primary)) 12%,
      var(--hos-chart-panel, var(--el-bg-color))
    );
  }
  .is-level-2 {
    background: color-mix(
      in srgb,
      var(--hos-chart-primary, var(--el-color-primary)) 30%,
      var(--hos-chart-panel, var(--el-bg-color))
    );
  }
  .is-level-3 {
    background: color-mix(
      in srgb,
      var(--hos-chart-primary, var(--el-color-primary)) 52%,
      var(--hos-chart-panel, var(--el-bg-color))
    );
  }
  .is-level-4 {
    background: var(--hos-chart-primary, #0f9f8f);
    border-color: color-mix(in srgb, var(--hos-chart-primary, #0f766e) 70%, #000000);
  }
}

@media (width <= 760px) {
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

@media (prefers-reduced-motion: reduce) { .calendar-day { transition: none; } }
