<template>
  <div class="attachment-gallery" :class="{ compact }">
    <article v-for="attachment in attachments" :key="attachment.id" class="attachment-card">
      <template v-if="isImage(attachment)">
        <div v-if="loading[attachment.id]" class="preview-state">正在加载缩略图…</div>
        <div v-else-if="errors[attachment.id]" class="preview-state error-state">
          <span>{{ errors[attachment.id] }}</span>
          <el-button link type="primary" @click="retryAttachment(attachment)">重试</el-button>
        </div>
        <template v-else-if="objectUrls[attachment.id]">
          <el-image
            class="image-thumbnail"
            :src="objectUrls[attachment.id]"
            :preview-src-list="imagePreviewUrls"
            :initial-index="imagePreviewIndex(attachment)"
            fit="cover"
            preview-teleported
            hide-on-click-modal
          />
          <el-button class="original-button" link type="primary" @click="openOriginal(attachment)">原图</el-button>
        </template>
      </template>

      <template v-else-if="isPdf(attachment)">
        <button class="pdf-preview" type="button" @click="openPdf(attachment)">
          <span class="pdf-badge">PDF</span>
          <span>站内预览</span>
        </button>
        <div v-if="errors[attachment.id]" class="inline-error">
          {{ errors[attachment.id] }}
          <el-button link type="primary" @click="openPdf(attachment)">重试</el-button>
        </div>
      </template>

      <div v-else class="file-fallback">
        <span class="file-badge">FILE</span>
        <el-button link type="primary" @click="$emit('download', attachment)">下载</el-button>
      </div>

      <div class="attachment-meta">
        <strong :title="attachment.fileName">{{ attachment.fileName }}</strong>
        <span
          >{{ formatFileSize(attachment.fileSize)
          }}<template v-if="attachment.description"> · {{ attachment.description }}</template></span
        >
      </div>
    </article>

    <el-dialog v-model="pdfDialogVisible" width="min(1120px, 92vw)" top="4vh" destroy-on-close append-to-body>
      <template #header>
        <div class="pdf-dialog-title">{{ activePdf?.fileName || "检查资料预览" }}</div>
      </template>
      <div v-if="activePdf && loading[activePdf.id]" class="pdf-dialog-state">正在加载 PDF…</div>
      <div v-else-if="activePdf && errors[activePdf.id]" class="pdf-dialog-state error-state">
        <span>{{ errors[activePdf.id] }}</span>
        <el-button type="primary" @click="openPdf(activePdf)">重新加载</el-button>
      </div>
      <iframe
        v-else-if="activePdf && objectUrls[activePdf.id]"
        class="pdf-frame"
        :src="objectUrls[activePdf.id]"
        :title="`${activePdf.fileName} PDF 预览`"
      />
      <template #footer>
        <el-button v-if="activePdf" @click="$emit('download', activePdf)">下载原文件</el-button>
        <el-button type="primary" @click="pdfDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { getPreAiAttachmentObjectUrlApi, type PreAiAttachment } from "@/api/modules/clinic/preAi";

const props = withDefaults(
  defineProps<{
    attachments: PreAiAttachment[];
    compact?: boolean;
  }>(),
  { compact: false }
);

defineEmits<{
  download: [attachment: PreAiAttachment];
}>();

const objectUrls = reactive<Record<string, string>>({});
const errors = reactive<Record<string, string>>({});
const loading = reactive<Record<string, boolean>>({});
const pdfDialogVisible = ref(false);
const activePdf = ref<PreAiAttachment>();
let requestController = new AbortController();
let generation = 0;

const extensionOf = (attachment: PreAiAttachment) => attachment.fileName.split(".").pop()?.toLowerCase() || "";
const isImage = (attachment: PreAiAttachment) =>
  attachment.mimeType?.toLowerCase().startsWith("image/") ||
  ["jpg", "jpeg", "png", "gif", "bmp", "webp"].includes(extensionOf(attachment));
const isPdf = (attachment: PreAiAttachment) =>
  attachment.mimeType?.toLowerCase() === "application/pdf" || extensionOf(attachment) === "pdf";

const imageAttachments = computed(() => props.attachments.filter(item => isImage(item) && objectUrls[item.id]));
const imagePreviewUrls = computed(() => imageAttachments.value.map(item => objectUrls[item.id]));
const imagePreviewIndex = (attachment: PreAiAttachment) =>
  Math.max(
    0,
    imageAttachments.value.findIndex(item => item.id === attachment.id)
  );

const revokeUrl = (attachmentId: string) => {
  const url = objectUrls[attachmentId];
  if (url) URL.revokeObjectURL(url);
  delete objectUrls[attachmentId];
};

const resetResources = () => {
  requestController.abort();
  requestController = new AbortController();
  Object.keys(objectUrls).forEach(revokeUrl);
  Object.keys(errors).forEach(key => delete errors[key]);
  Object.keys(loading).forEach(key => delete loading[key]);
};

const loadAttachment = async (attachment: PreAiAttachment, expectedGeneration = generation) => {
  loading[attachment.id] = true;
  delete errors[attachment.id];
  revokeUrl(attachment.id);
  try {
    const url = await getPreAiAttachmentObjectUrlApi(attachment, requestController.signal);
    if (expectedGeneration !== generation || requestController.signal.aborted) {
      URL.revokeObjectURL(url);
      return;
    }
    objectUrls[attachment.id] = url;
  } catch (error: any) {
    if (error?.name !== "AbortError" && expectedGeneration === generation) {
      errors[attachment.id] = error?.message || "资料加载失败";
    }
  } finally {
    if (expectedGeneration === generation) loading[attachment.id] = false;
  }
};

const loadImageThumbnails = async () => {
  const expectedGeneration = generation;
  const queue = props.attachments.filter(isImage);
  const worker = async () => {
    while (queue.length && expectedGeneration === generation) {
      const attachment = queue.shift();
      if (attachment) await loadAttachment(attachment, expectedGeneration);
    }
  };
  await Promise.all(Array.from({ length: Math.min(4, queue.length) }, worker));
};

const retryAttachment = async (attachment: PreAiAttachment) => {
  if (requestController.signal.aborted) requestController = new AbortController();
  await loadAttachment(attachment);
};

const openPdf = async (attachment: PreAiAttachment) => {
  activePdf.value = attachment;
  pdfDialogVisible.value = true;
  if (!objectUrls[attachment.id]) await retryAttachment(attachment);
};

const openOriginal = (attachment: PreAiAttachment) => {
  const url = objectUrls[attachment.id];
  if (url) window.open(url, "_blank", "noopener,noreferrer");
};

const formatFileSize = (size: number) => {
  if (!Number.isFinite(size) || size <= 0) return "未知大小";
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
};

watch(
  () => props.attachments.map(item => `${item.id}:${item.downloadUrl}`).join("|"),
  () => {
    generation += 1;
    resetResources();
    activePdf.value = undefined;
    pdfDialogVisible.value = false;
    void loadImageThumbnails();
  },
  { immediate: true }
);

onBeforeUnmount(() => {
  generation += 1;
  resetResources();
});
</script>

<style scoped lang="scss">
.attachment-gallery {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;

  &.compact {
    grid-template-columns: repeat(auto-fill, minmax(132px, 1fr));
  }
}

.attachment-card {
  position: relative;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-blank);
}

.image-thumbnail,
.preview-state,
.pdf-preview,
.file-fallback {
  width: 100%;
  height: 118px;
}

.image-thumbnail {
  display: block;
  cursor: zoom-in;
}

.preview-state,
.file-fallback,
.pdf-preview {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
}

.pdf-preview {
  flex-direction: column;
  border: 0;
  cursor: pointer;
  font: inherit;
}

.pdf-preview:hover,
.pdf-preview:focus-visible {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: -2px;
}

.pdf-badge,
.file-badge {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.pdf-badge {
  color: var(--el-color-danger);
}

.original-button {
  position: absolute;
  top: 6px;
  right: 8px;
  padding: 4px 8px;
  border-radius: 6px;
  background: rgb(255 255 255 / 90%);
}

.attachment-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 9px 10px 10px;

  strong {
    overflow: hidden;
    color: var(--el-text-color-primary);
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    overflow: hidden;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.error-state,
.inline-error {
  color: var(--el-color-danger);
}

.error-state {
  flex-direction: column;
  text-align: center;
}

.inline-error {
  padding: 6px 10px 0;
  font-size: 12px;
}

.pdf-dialog-title {
  overflow: hidden;
  padding-right: 36px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pdf-frame {
  display: block;
  width: 100%;
  height: 76vh;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.pdf-dialog-state {
  display: flex;
  min-height: 45vh;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

@media (max-width: 768px) {
  .attachment-gallery,
  .attachment-gallery.compact {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
