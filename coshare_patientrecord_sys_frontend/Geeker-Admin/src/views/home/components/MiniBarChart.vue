<template>
  <section class="mini-bar-chart" :class="{ 'is-compact': compact }">
    <div v-if="title" class="chart-head">
      <h3>{{ title }}</h3>
      <span v-if="subtitle">{{ subtitle }}</span>
    </div>
    <div v-if="visibleItems.length" class="bar-list">
      <div
        v-for="item in visibleItems"
        :key="item.label"
        class="bar-row"
        :class="{ 'is-peak': item.value === peak && peak > 0 }"
        :title="`${item.label}：${item.value}${unit}`"
      >
        <span class="bar-label" :title="item.label">{{ item.label }}</span>
        <div class="bar-track">
          <i :style="{ width: `${percent(item.value)}%` }"></i>
        </div>
        <strong class="bar-value">{{ item.value }}{{ unit }}</strong>
      </div>
    </div>
    <el-empty v-else description="暂无数据" :image-size="52" />
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";

export interface MiniBarItem {
  label: string;
  value: number;
}

const props = withDefaults(
  defineProps<{
    title?: string;
    subtitle?: string;
    items: MiniBarItem[];
    maxBars?: number;
    unit?: string;
    compact?: boolean;
  }>(),
  { title: "", subtitle: "", maxBars: 7, unit: "", compact: false }
);

const visibleItems = computed(() => props.items.slice(0, props.maxBars));
const peak = computed(() => Math.max(0, ...visibleItems.value.map(item => item.value)));
const percent = (value: number) => (peak.value ? Math.round((Math.max(0, value) / peak.value) * 100) : 0);
</script>

<style scoped lang="scss">
.mini-bar-chart {
  display: grid;
  gap: 10px;
  min-height: 120px;
}
.chart-head {
  display: flex;
  gap: 10px;
  align-items: baseline;
  justify-content: space-between;
  h3 {
    margin: 0;
    font-size: 15px;
  }
  span {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}
.bar-list {
  display: grid;
  gap: 8px;
}
.mini-bar-chart.is-compact .bar-list {
  gap: 5px;
  max-height: 360px;
  padding-right: 4px;
  overflow-y: auto;
}
.mini-bar-chart.is-compact .bar-row {
  min-height: 24px;
}
.mini-bar-chart.is-compact .bar-track {
  height: 8px;
}
.bar-row {
  display: grid;
  grid-template-columns: minmax(56px, 92px) minmax(0, 1fr) 48px;
  gap: 10px;
  align-items: center;
  min-height: 28px;
}
.bar-label {
  overflow: hidden;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bar-track {
  height: 10px;
  overflow: hidden;
  background: var(--hos-chart-panel-soft, var(--el-fill-color-light));
  border-radius: 10px;
  i {
    display: block;
    min-width: 2px;
    height: 100%;

    --bar-tone: var(--hos-chart-primary, #66bb7a);

    background: linear-gradient(90deg, color-mix(in srgb, var(--bar-tone) 58%, #ffffff), var(--bar-tone));
    border-radius: 10px;
    box-shadow: 0 4px 12px color-mix(in srgb, var(--bar-tone) 24%, transparent);
    transition:
      width var(--motion-chart-update, 220ms) var(--ease-out, ease),
      background-color var(--motion-control, 180ms) var(--ease-out, ease),
      box-shadow var(--motion-control, 180ms) var(--ease-out, ease);
  }
}
.bar-row:nth-child(6n + 1) .bar-track i {
  --bar-tone: var(--hos-chart-muted-fill, #b7c85b);
}
.bar-row:nth-child(6n + 2) .bar-track i {
  --bar-tone: var(--hos-chart-primary, #66bb7a);
}
.bar-row:nth-child(6n + 3) .bar-track i {
  --bar-tone: var(--hos-chart-success, #8bc06f);
}
.bar-row:nth-child(6n + 4) .bar-track i {
  --bar-tone: var(--hos-chart-warning, #ffb347);
}
.bar-row:nth-child(6n + 5) .bar-track i {
  --bar-tone: #ffca3a;
}
.bar-row:nth-child(6n) .bar-track i {
  --bar-tone: var(--hos-chart-danger, #ff7a59);
}
.bar-row.is-peak .bar-track i {
  --bar-tone: var(--hos-chart-warning, #ffb347);

  box-shadow: 0 6px 14px color-mix(in srgb, var(--bar-tone) 30%, transparent);
}

@media (hover: hover) and (pointer: fine) {
  .bar-row:hover .bar-track i {
    --bar-tone: var(--hos-chart-info, #5aa9e6);

    box-shadow: 0 7px 16px color-mix(in srgb, var(--bar-tone) 28%, transparent);
  }
}
.bar-value {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-primary);
  text-align: right;
}

@media (prefers-reduced-motion: reduce) {
  .bar-track i {
    transition: none;
  }
}
</style>
