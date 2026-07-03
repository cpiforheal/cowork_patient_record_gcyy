<template>
  <section class="timeline-workbench">
    <div class="attachment-workbench-head">
      <div>
        <h3>档案时间轴</h3>

        <p>用于回查字段、附件、复查和归档动作的来源、人员和时间。</p>
      </div>

      <el-button :loading="loading" @click="$emit('refresh')">刷新</el-button>
    </div>

    <el-empty v-if="!events.length" description="暂无档案时间轴" />

    <div v-else class="timeline-workbench-list">
      <article v-for="event in events" :key="event.id">
        <span>{{ timelineDisplayTime(event.time) }}</span>

        <div>
          <strong>{{ event.title }}</strong>

          <p>{{ event.detail || "暂无详情" }}</p>

          <small>
            {{ timelineSourceLabel(event.source) }} · {{ event.module || "档案" }}

            <template v-if="event.operator"> · {{ event.operator }}</template>
          </small>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { PatientTimelineEvent } from "@/api/modules/clinic";

defineProps<{
  events: PatientTimelineEvent[];
  loading: boolean;
  timelineDisplayTime: (time: string) => string;
  timelineSourceLabel: (source?: string) => string;
}>();

defineEmits<{
  refresh: [];
}>();
</script>

<style scoped lang="scss">
.timeline-workbench {
  display: grid;
  gap: 12px;
  padding-bottom: 24px;
}

.attachment-workbench-head {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  background: var(--hos-panel);
  border: 1px solid var(--hos-border);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-soft);

  h3,
  p {
    margin: 0;
  }

  h3 {
    font-size: 18px;
  }

  p {
    margin-top: 4px;
    color: var(--hos-text-secondary);
  }
}

.timeline-workbench-list {
  display: grid;
  gap: 8px;
}

.timeline-workbench-list article {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  justify-content: flex-start;
  padding: 12px 14px;
  background: var(--hos-panel);
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);

  > span {
    flex: 0 0 150px;
    color: var(--hos-primary-deep);
    font-size: 13px;
    font-variant-numeric: tabular-nums;
  }

  div {
    display: grid;
    gap: 3px;
    min-width: 0;
  }

  strong,
  p {
    margin: 0;
    overflow-wrap: anywhere;
  }

  span,
  small,
  p {
    color: var(--hos-text-secondary);
  }
}
</style>
