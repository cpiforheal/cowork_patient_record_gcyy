<template>
  <div class="pd" :class="`t-${level.tone}`">
    <el-date-picker
      :model-value="modelValue"
      type="date"
      value-format="YYYY-MM-DD"
      format="YYYY-MM-DD"
      :clearable="false"
      :editable="false"
      :aria-label="ariaLabel"
      popper-class="rk-pd-popper"
      :cell-class-name="cellClass"
      :style="{ width }"
      @update:model-value="(value: string) => emit('update:modelValue', value)"
    >
      <template #default="cell">
        <div class="el-date-table-cell">
          <span class="el-date-table-cell__text">{{ cell.text }}</span>
          <span v-if="countOf(cell)" class="rk-pd-count">{{ countOf(cell) }}</span>
        </div>
      </template>
    </el-date-picker>
    <span class="pd-tag">{{ level.text }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import dayjs from "dayjs";
import { distance, fmt } from "./dates";

const props = withDefaults(
  defineProps<{
    modelValue: string;
    today: string;
    /** 日期 → 已登记人数，用于弹出面板里的人数角标 */
    counts?: Record<string, number>;
    width?: string;
    ariaLabel?: string;
  }>(),
  { counts: () => ({}), width: "136px", ariaLabel: "复查日期" }
);

const emit = defineEmits<{ "update:modelValue": [value: string] }>();

const level = computed(() => distance(props.modelValue, props.today));

const countOf = (cell: { dayjs?: dayjs.Dayjs }) => (cell.dayjs ? props.counts[fmt(cell.dayjs)] || 0 : 0);

/** 面板单元格层级：已过日期变淡，有登记的日期带底色 */
const cellClass = (date: Date) => {
  const key = fmt(dayjs(date));
  const classes: string[] = [];
  if (key < props.today) classes.push("rk-pd-past");
  if (props.counts[key]) classes.push("rk-pd-has");
  return classes.join(" ");
};
</script>

<style scoped lang="scss">
.pd {
  --tc: #0f766e;
  --tag-bg: color-mix(in srgb, #0f766e 12%, transparent);
  --tag-fg: #0f766e;

  display: inline-flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
  &.t-today {
    --tag-bg: #0f766e;
    --tag-fg: #ffffff;
  }
  &.t-far {
    --tc: #5b9e96;
    --tag-bg: var(--el-fill-color);
    --tag-fg: var(--el-text-color-regular);
  }
  &.t-past {
    --tc: #b45309;
    --tag-bg: color-mix(in srgb, #b45309 12%, transparent);
    --tag-fg: #b45309;
  }
  :deep(.el-input__wrapper) {
    box-shadow: 0 0 0 1px var(--tc) inset;
  }
  :deep(.el-input__inner) {
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    color: var(--tc);
    cursor: pointer;
  }
  :deep(.el-input__prefix) {
    color: var(--tc);
  }
}
.pd-tag {
  flex: none;
  padding: 1px 8px;
  font-size: 12px;
  line-height: 20px;
  color: var(--tag-fg);
  white-space: nowrap;
  background: var(--tag-bg);
  border-radius: 11px;
}
</style>

<style lang="scss">
/* 弹出面板挂在 body 下，需非 scoped 样式 */
.rk-pd-popper {
  .el-date-table td .el-date-table-cell {
    position: relative;
  }
  .el-date-table td.today .el-date-table-cell__text {
    font-weight: 700;
    color: #0f766e;
    box-shadow: 0 0 0 1.5px #0f766e inset;
  }
  .el-date-table td.rk-pd-has:not(.current) .el-date-table-cell__text {
    background: color-mix(in srgb, #0f766e 12%, transparent);
  }
  .el-date-table td.rk-pd-past:not(.current) .el-date-table-cell__text {
    color: var(--el-text-color-placeholder);
  }
  .el-date-table td.current:not(.disabled) .el-date-table-cell__text {
    color: #ffffff;
    background: #0f766e;
  }
  .rk-pd-count {
    position: absolute;
    top: 0;
    right: 0;
    min-width: 14px;
    height: 14px;
    padding: 0 3px;
    font-size: 10px;
    line-height: 14px;
    color: #ffffff;
    text-align: center;
    pointer-events: none;
    background: #0f766e;
    border-radius: 7px;
  }
  .rk-pd-past .rk-pd-count {
    background: #94a3b8;
  }
}
</style>
