import type { PreAiAttachment } from "@/api/modules/clinic";

export interface AttachmentGroup {
  id: string;
  name: string;
  items: PreAiAttachment[];
}

const imageFilePattern = /\.(?:avif|bmp|gif|heic|heif|jpe?g|png|svg|webp)$/i;

export const isImageAttachment = (attachment: PreAiAttachment) =>
  attachment.mimeType?.startsWith("image/") || imageFilePattern.test(attachment.fileName || "");

export const groupAttachments = (attachments: PreAiAttachment[], sortBySequence = false): AttachmentGroup[] => {
  const groups = new Map<string, AttachmentGroup>();

  attachments.forEach(attachment => {
    const id = attachment.batchId || attachment.id;
    const group = groups.get(id) || { id, name: attachment.batchName || "独立上传", items: [] };
    group.items.push(attachment);
    groups.set(id, group);
  });

  return Array.from(groups.values()).map(group => ({
    ...group,
    items: sortBySequence ? group.items.sort((left, right) => (left.sequenceNo || 0) - (right.sequenceNo || 0)) : group.items
  }));
};
