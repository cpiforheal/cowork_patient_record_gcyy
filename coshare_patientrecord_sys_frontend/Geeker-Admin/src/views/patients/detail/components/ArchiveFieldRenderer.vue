<template>
  <div class="field-input">
    <div v-if="disabled" class="locked-note">
      <el-icon><Lock /></el-icon>

      <span>{{ lockedText }}</span>
    </div>

    <FollowupRecordsEditor
      v-if="field.key === 'followupRecordsJson'"
      :records="followupRecords"
      :disabled="disabled"
      @add="emit('addFollowupRecord')"
      @remove="emit('removeFollowupRecord', $event)"
    />

    <LabMetricEditor
      v-else-if="isLabMetric"
      :field="field"
      :disabled="disabled"
      :model-value="modelValue"
      @update:model-value="value => emit('updateLabMetric', field, value)"
    />

    <FieldAttachmentUploader
      v-else-if="field.kind === 'attachment'"
      :field="field"
      :attachments="attachments"
      :disabled="disabled"
      :can-open-attachment="canOpenAttachment"
      :is-image-attachment="isImageAttachment"
      :attachment-preview-url="attachmentPreviewUrl"
      :open-attachment="openAttachment"
      :role-label="roleLabel"
      @upload="(targetField, files, remark) => emit('upload', targetField, files, remark)"
    />

    <el-select
      v-else-if="selectOptions.length"
      :model-value="modelValue"
      allow-create
      clearable
      default-first-option
      filterable
      class="preset-select"
      :disabled="disabled"
      :placeholder="field.placeholder || '选择或输入'"
      @update:model-value="value => emit('update:modelValue', String(value || ''))"
    >
      <el-option v-for="option in selectOptions" :key="option" :label="option" :value="option" />
    </el-select>

    <el-input
      v-else-if="field.inputType === 'number'"
      :model-value="modelValue"
      :disabled="disabled"
      :max="field.max"
      :min="field.min"
      :placeholder="field.placeholder"
      type="number"
      controls-position="right"
      class="meta-number-input"
      @update:model-value="value => emit('update:modelValue', String(value || ''))"
    >
      <template v-if="field.unit" #suffix>{{ field.unit }}</template>
    </el-input>

    <el-date-picker
      v-else-if="field.inputType === 'date'"
      :model-value="modelValue"
      type="date"
      value-format="YYYY-MM-DD"
      class="meta-date-input"
      :disabled="disabled"
      :placeholder="field.placeholder || '选择日期'"
      @update:model-value="value => emit('update:modelValue', String(value || ''))"
    />

    <el-input
      v-else
      :model-value="modelValue"
      :disabled="disabled"
      :placeholder="field.placeholder"
      :maxlength="field.maxLength"
      :show-word-limit="field.inputType === 'tel'"
      :rows="textareaRows"
      :type="field.kind === 'textarea' ? 'textarea' : field.inputType === 'tel' ? 'tel' : 'text'"
      @update:model-value="value => emit('update:modelValue', String(value || ''))"
    />

    <FieldAttachmentEvidence
      v-if="field.kind !== 'attachment'"
      :attachments="attachments"
      :can-open-attachment="canOpenAttachment"
      :is-image-attachment="isImageAttachment"
      :attachment-preview-url="attachmentPreviewUrl"
      :open-attachment="openAttachment"
      :role-label="roleLabel"
    />

    <p v-if="issue" class="field-inline-issue">
      {{ issue.message }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { Lock } from "@element-plus/icons-vue";
import { editorLabels, type RecordAttachment, type RecordField } from "@/config/fieldPermissions";
import FieldAttachmentEvidence from "./FieldAttachmentEvidence.vue";
import FieldAttachmentUploader from "./FieldAttachmentUploader.vue";
import FollowupRecordsEditor from "./FollowupRecordsEditor.vue";
import LabMetricEditor from "./LabMetricEditor.vue";
import type { FieldIssue, FollowupRecord } from "./types";

const props = withDefaults(
  defineProps<{
    field: RecordField;
    modelValue?: string;
    disabled?: boolean;
    issue?: FieldIssue;
    attachments?: RecordAttachment[];
    selectOptions?: string[];
    followupRecords?: FollowupRecord[];
    roleLabel: (role?: string) => string;
    canOpenAttachment: (attachmentOrUrl?: RecordAttachment | string) => boolean;
    isImageAttachment: (attachment: RecordAttachment) => boolean;
    attachmentPreviewUrl: (url?: string) => string;
    openAttachment: (url: string) => void | Promise<void>;
    textareaRows?: number;
    lockedText?: string;
  }>(),
  {
    modelValue: "",
    disabled: false,
    issue: undefined,
    attachments: () => [],
    selectOptions: () => [],
    followupRecords: () => [],
    textareaRows: 2,
    lockedText: ""
  }
);

const emit = defineEmits<{
  "update:modelValue": [value: string];
  updateLabMetric: [field: RecordField, value: string];
  upload: [field: RecordField, files: File[], remark: string];
  addFollowupRecord: [];
  removeFollowupRecord: [id: string];
}>();

const isLabMetric = computed(() => Boolean(props.field.labPanel));
const lockedText = computed(() => props.lockedText || `${editorLabels(props.field.editors)} 填写，当前只读`);
</script>

<style scoped lang="scss">
.field-input {
  :deep(.el-select),
  :deep(.el-input),
  :deep(.el-textarea) {
    width: 100%;
  }

  :deep(.el-input__wrapper),
  :deep(.el-textarea__inner) {
    background: var(--hos-field-editable-bg);
    box-shadow: 0 0 0 1px var(--hos-border) inset;
  }

  :deep(.is-disabled .el-input__wrapper),
  :deep(.el-textarea.is-disabled .el-textarea__inner) {
    background: var(--hos-field-locked-bg);
    box-shadow: 0 0 0 1px var(--hos-border-light) inset;
  }
}

.locked-note {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 6px;
  color: #9ca3af;
  font-size: 12px;
}

.preset-select {
  :deep(.el-select__wrapper) {
    min-height: 38px;
    background: var(--hos-field-editable-bg);
    box-shadow: 0 0 0 1px var(--hos-border) inset;
  }

  :deep(.el-select__placeholder),
  :deep(.el-select__selected-item) {
    color: #334155;
    font-size: 13px;
  }
}

.field-inline-issue {
  margin: 0;
  color: #c2410c;
  font-size: 12px;
  line-height: 1.45;
}
</style>
