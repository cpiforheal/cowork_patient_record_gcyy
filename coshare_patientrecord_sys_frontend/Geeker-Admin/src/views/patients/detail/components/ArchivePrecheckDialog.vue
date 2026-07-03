<template>
  <el-dialog
    :model-value="visible"
    title="提交质控前预检"
    width="600px"
    append-to-body
    destroy-on-close
    class="archive-precheck-dialog"
    @update:model-value="$emit('update:visible', $event)"
  >
    <el-alert title="系统正在保护当前档案：以下问题处理后再提交，可减少质控退回。" type="warning" show-icon :closable="false" />

    <div class="archive-precheck-list">
      <button v-for="issue in issues" :key="issue.fieldKey" type="button" @click="$emit('focus', issue)">
        <span>{{ issue.level === "invalid" ? "格式异常" : "必填待补" }}</span>

        <strong>{{ issue.sectionTitle }} · {{ issue.fieldLabel }}</strong>

        <small>{{ issue.message }}</small>
      </button>
    </div>

    <template #footer>
      <el-button @click="$emit('update:visible', false)">稍后处理</el-button>

      <el-button type="primary" :disabled="!issues.length" @click="$emit('focus', issues[0])">定位第一项</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { FieldIssue } from "./types";

defineProps<{
  visible: boolean;
  issues: FieldIssue[];
}>();

defineEmits<{
  "update:visible": [visible: boolean];
  focus: [issue: FieldIssue];
}>();
</script>

<style scoped lang="scss">
.archive-precheck-list {
  display: grid;
  gap: 10px;
  margin-top: 14px;

  button {
    display: grid;
    gap: 4px;
    width: 100%;
    padding: 12px 14px;
    text-align: left;
    cursor: pointer;
    background: var(--hos-status-warning-soft);
    border: 1px solid rgb(217 119 6 / 22%);
    border-radius: var(--hos-radius-lg);
    transition:
      transform var(--hos-duration-fast) var(--liquid-ease),
      border-color var(--hos-duration-fast) var(--liquid-ease),
      box-shadow var(--hos-duration-fast) var(--liquid-ease);

    &:hover {
      border-color: rgb(217 119 6 / 36%);
      box-shadow: 0 12px 28px rgb(217 119 6 / 12%);
      transform: translateY(-1px);
    }
  }

  span {
    width: fit-content;
    padding: 2px 8px;
    color: var(--hos-status-warning);
    background: rgb(255 255 255 / 70%);
    border-radius: 999px;
    font-size: 12px;
    font-weight: 700;
  }

  strong,
  small {
    display: block;
  }

  strong {
    color: var(--hos-text-primary);
  }

  small {
    color: var(--hos-text-secondary);
  }
}
</style>
