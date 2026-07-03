<template>
  <el-dialog
    :model-value="visible"
    title="作废附件"
    width="460px"
    destroy-on-close
    @update:model-value="$emit('update:visible', $event)"
  >
    <div class="void-document-dialog">
      <p>
        <span>附件：</span>

        <strong>{{ target?.fileName }}</strong>
      </p>

      <el-input
        :model-value="reason"
        type="textarea"
        :rows="4"
        maxlength="120"
        show-word-limit
        placeholder="请填写作废原因，例如：传错患者、重复上传、报告版本错误"
        @update:model-value="$emit('update:reason', $event)"
      />
    </div>

    <template #footer>
      <el-button @click="$emit('update:visible', false)">取消</el-button>

      <el-button type="danger" :loading="voiding" :disabled="!reason.trim()" @click="$emit('confirm')">确认作废</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { RecordAttachment } from "@/config/fieldPermissions";

defineProps<{
  visible: boolean;
  target?: RecordAttachment;
  reason: string;
  voiding: boolean;
}>();

defineEmits<{
  "update:visible": [visible: boolean];
  "update:reason": [reason: string];
  confirm: [];
}>();
</script>

<style scoped lang="scss">
.void-document-dialog {
  display: grid;
  gap: 12px;

  p {
    display: flex;
    gap: 6px;
    margin: 0;
    color: var(--el-text-color-regular);

    strong {
      color: var(--el-text-color-primary);
      word-break: break-word;
    }
  }
}
</style>
