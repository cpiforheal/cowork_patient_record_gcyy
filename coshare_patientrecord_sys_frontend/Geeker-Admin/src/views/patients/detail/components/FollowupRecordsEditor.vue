<template>
  <div class="followup-editor">
    <div class="followup-editor-head">
      <strong>术后分级复诊健康管理台账</strong>

      <span>默认覆盖术后7天、14天、30天、3/6/12月和痊愈归档节点</span>

      <el-button type="primary" plain :disabled="disabled" @click="$emit('add')">新增复查</el-button>
    </div>

    <div v-if="records.length" class="followup-record-list">
      <article v-for="(record, index) in records" :key="record.id" class="followup-record-item">
        <div class="followup-record-top">
          <span>{{ record.node || `第 ${index + 1} 次` }}</span>

          <el-button type="danger" link :disabled="disabled" @click="$emit('remove', record.id)">删除</el-button>
        </div>

        <div class="followup-record-grid">
          <label>
            <span>复诊层级</span>

            <el-input v-model="record.node" :disabled="disabled" placeholder="术后第7天/14天/30天/远期/痊愈" />
          </label>

          <label>
            <span>复查日期</span>

            <el-input v-model="record.date" :disabled="disabled" placeholder="YYYY-MM-DD" />
          </label>

          <label>
            <span>复查项目</span>

            <el-input v-model="record.project" :disabled="disabled" placeholder="换药/创面/肛门功能/肠镜等" />
          </label>

          <label>
            <span>完成状态</span>

            <el-input v-model="record.completed" :disabled="disabled" placeholder="未完成/已完成" />
          </label>
        </div>

        <div class="followup-record-grid wide">
          <label>
            <span>健康管理内容</span>

            <el-input
              v-model="record.management"
              :disabled="disabled"
              placeholder="宣教、换药、疼痛管理、饮食管理"
              type="textarea"
              :rows="2"
            />
          </label>

          <label>
            <span>影像归档要求</span>

            <el-input
              v-model="record.imagingRequirement"
              :disabled="disabled"
              placeholder="创面照片/报告/无需归档"
              type="textarea"
              :rows="2"
            />
          </label>

          <label>
            <span>恢复情况</span>

            <el-input v-model="record.recovery" :disabled="disabled" placeholder="每次检查的恢复情况" type="textarea" :rows="2" />
          </label>

          <label>
            <span>异常情况</span>

            <el-input v-model="record.abnormal" :disabled="disabled" placeholder="无异常可填无" type="textarea" :rows="2" />
          </label>

          <label>
            <span>医生建议</span>

            <el-input
              v-model="record.advice"
              :disabled="disabled"
              placeholder="用药、清洁、饮食、复查安排"
              type="textarea"
              :rows="2"
            />
          </label>

          <label>
            <span>下次复查</span>

            <el-input v-model="record.nextDate" :disabled="disabled" placeholder="YYYY-MM-DD" />
          </label>
        </div>
      </article>
    </div>

    <div v-else class="followup-empty">暂无复查记录</div>
  </div>
</template>

<script setup lang="ts">
import type { FollowupRecord } from "./types";

withDefaults(
  defineProps<{
    records: FollowupRecord[];
    disabled?: boolean;
  }>(),
  {
    disabled: false
  }
);

defineEmits<{
  add: [];
  remove: [id: string];
}>();
</script>

<style scoped lang="scss">
.followup-editor {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.followup-editor-head,
.followup-record-top {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
}

.followup-editor-head strong,
.followup-record-top span {
  color: var(--hos-text-primary);
  font-weight: 700;
}

.followup-record-list {
  display: grid;
  gap: 10px;
}

.followup-record-item {
  padding: 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.followup-record-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-top: 10px;

  &.wide {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  label {
    display: flex;
    flex-direction: column;
    min-width: 0;
    gap: 5px;
  }

  span {
    color: #64748b;
    font-size: 12px;
    font-weight: 700;
  }
}

.followup-empty {
  padding: 18px;
  color: #64748b;
  text-align: center;
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
}

@media (max-width: 760px) {
  .followup-record-grid,
  .followup-record-grid.wide {
    grid-template-columns: 1fr;
  }
}
</style>
