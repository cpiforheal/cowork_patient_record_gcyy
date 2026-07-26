<template>
  <section class="mini-bar-chart">
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
  }>(),
  { title: "", subtitle: "", maxBars: 7, unit: "" }
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
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  h3 {
    margin: 0;
    font-size: 15px;
  }
  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}
.bar-list {
  display: grid;
  gap: 8px;
}
.bar-row {
  display: grid;
  grid-template-columns: minmax(56px, 92px) minmax(0, 1fr) 48px;
  gap: 10px;
  align-items: center;
}
.bar-label {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
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
    height: 100%;
    min-width: 2px;
    border-radius: 10px;
    background: linear-gradient(90deg, #14b8a6, #0f766e);
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
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  text-align: right;
}
</style>
