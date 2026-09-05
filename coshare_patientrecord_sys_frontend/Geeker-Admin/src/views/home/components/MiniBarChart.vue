<template>
  <section class="mini-bar-chart" :class="{ 'is-compact': compact }">
    <div v-if="title" class="chart-head">
      <h3>{{ title }}</h3>
      <span v-if="subtitle">{{ subtitle }}</span>
    </div>
    <div v-if="visibleItems.length" class="bar-list">
      <div v-for="item in visibleItems" :key="item.label" class="bar-row">
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
const peak = computed(() => Math.max(1, ...visibleItems.value.map(item => item.value)));
const percent = (value: number) => Math.round((Math.max(0, value) / peak.value) * 100);
</script>

<style scoped lang="scss">
.mini-bar-chart {
  display: grid;
  gap: 10px;
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
  background: var(--el-fill-color-light);
  border-radius: 10px;
  i {
    display: block;
    min-width: 2px;
    height: 100%;
    background: linear-gradient(90deg, #14b8a6, #0f766e);
    border-radius: 10px;
    transition: width 320ms ease;
  }
}
.bar-row:nth-child(4n + 2) .bar-track i {
  background: linear-gradient(90deg, #38bdf8, #087fa9);
}
.bar-row:nth-child(4n + 3) .bar-track i {
  background: linear-gradient(90deg, #86dcb1, #2f9461);
}
.bar-row:nth-child(4n + 4) .bar-track i {
  background: linear-gradient(90deg, #fbd38a, #d9a114);
}
.bar-value {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-primary);
  text-align: right;
}
</style>
