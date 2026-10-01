<template>
  <li class="er" :class="`s-${entry.status.toLowerCase()}`">
    <span class="er-state">{{ stateText }}</span>
    <span v-if="showDate" class="er-date">{{ entry.planDate.slice(5) }}</span>
    <span class="er-name">{{ entry.patientName }}</span>
    <span class="er-phone">{{ entry.phone || "—" }}</span>
    <span class="er-note" :title="noteText">{{ noteText }}</span>

    <span v-if="canEdit && entry.status !== 'RESCHEDULED'" class="er-ops">
      <template v-if="entry.status === 'PLANNED' && markable">
        <el-button size="small" type="success" plain @click="emit('mark', entry, 'ARRIVED')">到了</el-button>
        <el-button size="small" type="warning" plain @click="emit('mark', entry, 'ABSENT')">没来</el-button>
      </template>
      <el-button v-else-if="entry.status !== 'PLANNED'" size="small" link @click="emit('mark', entry, 'PLANNED')">撤回</el-button>
      <el-dropdown trigger="click" @command="(cmd: RowAction) => emit('action', entry, cmd)">
        <el-button size="small" link :aria-label="`${entry.patientName} 更多操作`">更多</el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="entry.status !== 'ARRIVED'" command="reschedule">改期</el-dropdown-item>
            <el-dropdown-item command="edit">修改信息</el-dropdown-item>
            <el-dropdown-item command="cancel" divided>撤销（登记错了）</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </span>
  </li>
</template>

<script setup lang="ts">
import { computed } from "vue";
import dayjs from "dayjs";
import type { RecheckEntry } from "@/api/modules/clinic/recheckSchedule";

export type RowAction = "reschedule" | "edit" | "cancel";

const props = defineProps<{
  entry: RecheckEntry;
  canEdit: boolean;
  /** 复查日期已到（今天或以前）才允许标记到/没来 */
  markable: boolean;
  showDate?: boolean;
}>();

const emit = defineEmits<{
  mark: [entry: RecheckEntry, status: "ARRIVED" | "ABSENT" | "PLANNED"];
  action: [entry: RecheckEntry, action: RowAction];
}>();

const stateText = computed(() => {
  switch (props.entry.status) {
    case "ARRIVED":
      return "已到";
    case "ABSENT":
      return "没来";
    case "RESCHEDULED":
      return "已改期";
    default:
      return props.markable ? "待到" : "预约";
  }
});

const noteText = computed(() => {
  const { entry } = props;
  const parts = [entry.note];
  if (entry.status === "RESCHEDULED" && entry.rescheduledTo) parts.push(`改到 ${dayjs(entry.rescheduledTo).format("MM/DD")}`);
  if (entry.statusNote) parts.push(entry.statusNote);
  if (entry.sourceId) parts.push("改期而来");
  return parts.filter(Boolean).join(" · ") || "";
});
</script>

<style scoped lang="scss">
.er {
  --c: #475569;

  display: grid;
  grid-template-columns: 56px auto minmax(5em, max-content) 120px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  min-height: 44px;
  padding: 6px 14px;
  border-top: 1px solid var(--el-border-color-lighter);
  &:first-child {
    border-top: none;
  }
  &.s-arrived {
    --c: #15803d;
  }
  &.s-absent {
    --c: #b45309;
  }
  &.s-rescheduled {
    --c: #94a3b8;

    color: var(--el-text-color-secondary);
    .er-name {
      text-decoration: line-through;
    }
  }
}
.er:not(:has(.er-date)) {
  grid-template-columns: 56px minmax(5em, max-content) 120px minmax(0, 1fr) auto;
}
.er-state {
  padding: 2px 0;
  font-size: 12px;
  color: var(--c);
  text-align: center;
  background: color-mix(in srgb, var(--c) 10%, transparent);
  border-radius: 6px;
}
.er-date {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-secondary);
}
.er-name {
  font-size: 16px;
  font-weight: 600;
}
.er-phone {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-regular);
}
.er-note {
  overflow: hidden;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.er-ops {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  justify-self: end;
}

@media (width <= 760px) {
  .er,
  .er:not(:has(.er-date)) {
    grid-template-columns: 56px 1fr auto;
  }
  .er-date,
  .er-phone,
  .er-note {
    grid-column: 2 / -1;
  }
}
</style>
