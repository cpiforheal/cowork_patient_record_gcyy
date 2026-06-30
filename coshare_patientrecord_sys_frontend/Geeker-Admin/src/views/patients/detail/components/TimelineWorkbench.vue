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
