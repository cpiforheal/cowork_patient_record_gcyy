<template>
  <div v-if="normalizedAttachments.length" class="field-evidence-grid">
    <template v-for="attachment in normalizedAttachments" :key="attachment.key">
      <article
        v-if="isImageAttachment(attachment) && canOpenAttachment(attachment) && attachmentPreviewUrl(attachment.url)"
        class="field-evidence-card image"
      >
        <el-image
          :src="attachmentPreviewUrl(attachment.url)"
          fit="cover"
          :preview-src-list="[attachmentPreviewUrl(attachment.url)]"
          preview-teleported
          hide-on-click-modal
        />

        <div>
          <strong>{{ attachment.title || attachment.fileName || "检查图片" }}</strong>

          <small>{{ attachmentMeta(attachment) || "检查图片" }}</small>
        </div>
      </article>

      <button
        v-else
        class="field-evidence-card file"
        :class="{ disabled: !canOpenAttachment(attachment) }"
        type="button"
        :disabled="!canOpenAttachment(attachment)"
        @click="openAttachment(attachment.url)"
      >
        <strong>{{ attachment.title || attachment.fileName || "附件" }}</strong>

        <small>{{ attachmentMeta(attachment) || "点击打开附件" }}</small>
      </button>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { RecordAttachment } from "@/config/fieldPermissions";

const props = defineProps<{
  attachments?: RecordAttachment[] | null;
  canOpenAttachment: (attachmentOrUrl?: RecordAttachment | string) => boolean;
  isImageAttachment: (attachment: RecordAttachment) => boolean;
  attachmentPreviewUrl: (url?: string) => string;
  openAttachment: (url: string) => void | Promise<void>;
  roleLabel: (role?: string) => string;
}>();

const normalizedAttachments = computed(() => (Array.isArray(props.attachments) ? props.attachments : []));

const attachmentMeta = (attachment: RecordAttachment) => {
  const sourceRole = attachment.sourceRole ? props.roleLabel(attachment.sourceRole) : "";
  const batchName = attachment.batchName || "";

  return [attachment.department, sourceRole, attachment.uploader, attachment.uploadedAt, batchName].filter(Boolean).join(" / ");
};
</script>

<style scoped lang="scss">
.field-evidence-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(128px, 1fr));
  gap: 8px;
  margin-top: 8px;
}

.field-evidence-card {
  min-width: 0;
  overflow: hidden;
  text-align: left;
  background: #f8fbfa;
  border: 1px solid #d8ebe5;
  border-radius: 8px;

  strong,
  small {
    display: block;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    color: #1f3d35;
    font-size: 12px;
    font-weight: 650;
  }

  small {
    margin-top: 2px;
    color: var(--hos-text-secondary);
    font-size: 11px;
  }

  &.image {
    :deep(.el-image) {
      display: block;
      width: 100%;
      height: 84px;
      background: #eef6f3;
    }

    div {
      padding: 7px 8px 8px;
    }
  }

  &.file {
    display: grid;
    gap: 2px;
    width: 100%;
    min-height: 62px;
    padding: 8px 10px;
    cursor: pointer;
  }

  &.disabled {
    cursor: not-allowed;
    opacity: 0.58;
  }
}
</style>
