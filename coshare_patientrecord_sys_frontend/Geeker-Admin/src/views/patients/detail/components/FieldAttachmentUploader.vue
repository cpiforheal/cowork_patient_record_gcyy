<template>
  <div class="field-attachment-uploader">
    <div class="field-attachment-actions">
      <label class="field-attachment-button" :class="{ disabled }">
        <span>{{ attachments.length ? "继续上传图片/视频" : "上传图片/视频" }}</span>
        <input ref="inputRef" type="file" accept="image/*,video/*" multiple :disabled="disabled" @change="handleFiles" />
      </label>

      <el-input
        v-model="remark"
        placeholder="可选备注：体位、点位、图片编号或需医生重点查看的位置"
        :disabled="disabled"
        clearable
        class="field-attachment-remark"
      />
    </div>

    <FieldAttachmentEvidence
      :attachments="attachments"
      :can-open-attachment="canOpenAttachment"
      :is-image-attachment="isImageAttachment"
      :attachment-preview-url="attachmentPreviewUrl"
      :open-attachment="openAttachment"
      :role-label="roleLabel"
    />

    <small class="field-attachment-hint">
      {{ field.placeholder || "该字段以图片/视频证据为主，无需填写大段文字。" }}
    </small>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import type { RecordAttachment, RecordField } from "@/config/fieldPermissions";
import FieldAttachmentEvidence from "./FieldAttachmentEvidence.vue";

const props = withDefaults(
  defineProps<{
    field: RecordField;
    attachments?: RecordAttachment[];
    disabled?: boolean;
    canOpenAttachment: (attachmentOrUrl?: RecordAttachment | string) => boolean;
    isImageAttachment: (attachment: RecordAttachment) => boolean;
    attachmentPreviewUrl: (url?: string) => string;
    openAttachment: (url: string) => void | Promise<void>;
    roleLabel: (role?: string) => string;
  }>(),
  {
    attachments: () => [],
    disabled: false
  }
);

const emit = defineEmits<{
  upload: [field: RecordField, files: File[], remark: string];
}>();

const remark = ref("");
const inputRef = ref<HTMLInputElement>();

const handleFiles = (event: Event) => {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  if (files.length) emit("upload", props.field, files, remark.value);
  if (inputRef.value) inputRef.value.value = "";
};
</script>

<style scoped lang="scss">
.field-attachment-uploader {
  display: grid;
  gap: 8px;
}

.field-attachment-actions {
  display: grid;
  grid-template-columns: 156px minmax(180px, 1fr);
  gap: 8px;
  align-items: center;
}

.field-attachment-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 38px;
  padding: 0 14px;
  color: #ffffff;
  cursor: pointer;
  background: var(--el-color-primary);
  border-radius: 6px;

  input {
    display: none;
  }

  &.disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }
}

.field-attachment-remark {
  width: 100%;
}

.field-attachment-hint {
  color: var(--el-text-color-secondary);
}
</style>
