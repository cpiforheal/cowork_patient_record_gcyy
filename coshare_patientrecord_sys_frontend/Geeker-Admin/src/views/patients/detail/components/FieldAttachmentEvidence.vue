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
