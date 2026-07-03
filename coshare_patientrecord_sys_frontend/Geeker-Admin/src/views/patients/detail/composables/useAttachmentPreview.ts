import { ref } from "vue";

import { ElMessage } from "element-plus";

import { fetchClinicFileBlobUrl } from "@/api/modules/clinic/files";
import type { RecordAttachment } from "@/config/fieldPermissions";

const imageAttachmentPattern = /\.(png|jpe?g|gif|webp|bmp|svg)(\?.*)?$/i;

export const useAttachmentPreview = () => {
  const attachmentBlobUrls = ref<Record<string, string>>({});

  const normalizeAttachmentUrl = (url?: string) => String(url || "").trim();

  const attachmentUrlBase = (url?: string) => normalizeAttachmentUrl(url).replace(/\/+$/, "");

  const isInvalidAttachmentUrl = (url?: string) => {
    const normalized = attachmentUrlBase(url);

    return !normalized || normalized === "/clinic-api/files" || normalized.endsWith("/clinic-api/files");
  };

  const canOpenAttachment = (attachmentOrUrl?: RecordAttachment | string) => {
    const url = typeof attachmentOrUrl === "string" ? attachmentOrUrl : attachmentOrUrl?.url;

    return !isInvalidAttachmentUrl(url);
  };

  const isImageAttachment = (attachment: RecordAttachment) => {
    const url = normalizeAttachmentUrl(attachment.url);

    return url.startsWith("data:image/") || imageAttachmentPattern.test(attachment.fileName) || imageAttachmentPattern.test(url);
  };

  const attachmentPreviewUrl = (url?: string) => attachmentBlobUrls.value[normalizeAttachmentUrl(url)] || "";

  const loadAttachmentBlobUrl = async (url: string) => {
    const normalizedUrl = normalizeAttachmentUrl(url);

    if (!normalizedUrl || normalizedUrl.startsWith("data:")) return normalizedUrl;

    if (attachmentBlobUrls.value[normalizedUrl]) return attachmentBlobUrls.value[normalizedUrl];

    const blobUrl = await fetchClinicFileBlobUrl(normalizedUrl);

    attachmentBlobUrls.value = { ...attachmentBlobUrls.value, [normalizedUrl]: blobUrl };

    return blobUrl;
  };

  const preloadAttachmentPreviews = (attachments: RecordAttachment[]) => {
    attachments

      .filter(isImageAttachment)

      .filter(canOpenAttachment)

      .forEach(attachment => {
        loadAttachmentBlobUrl(attachment.url).catch(() => {
          // Preview failure should not block record rendering; opening the file still reports a visible error.
        });
      });
  };

  const openAttachment = async (url: string) => {
    const normalizedUrl = normalizeAttachmentUrl(url);

    if (isInvalidAttachmentUrl(normalizedUrl)) {
      ElMessage.warning("文件打不开，请重新上传");

      return;
    }

    try {
      const blobUrl = await loadAttachmentBlobUrl(normalizedUrl);

      window.open(blobUrl, "_blank", "noopener,noreferrer");
    } catch (error) {
      ElMessage.error((error as Error).message);
    }
  };

  const revokeAttachmentBlobUrls = () => {
    Object.values(attachmentBlobUrls.value).forEach(url => URL.revokeObjectURL(url));

    attachmentBlobUrls.value = {};
  };

  return {
    attachmentBlobUrls,
    normalizeAttachmentUrl,
    isInvalidAttachmentUrl,
    canOpenAttachment,
    isImageAttachment,
    attachmentPreviewUrl,
    loadAttachmentBlobUrl,
    preloadAttachmentPreviews,
    openAttachment,
    revokeAttachmentBlobUrls
  };
};
