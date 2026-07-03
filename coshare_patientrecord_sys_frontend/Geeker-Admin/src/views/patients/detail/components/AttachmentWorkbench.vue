<template>
  <section class="attachment-workbench">
    <div class="attachment-workbench-head">
      <div>
        <h3>检查与附件</h3>

        <p>检查报告、旧共享资料、图片证据统一在这里回查；目标病历只引用医生确认后的字段值。</p>
      </div>

      <el-button :icon="Upload" type="primary" plain @click="$emit('upload')">补充图片</el-button>
    </div>

    <el-empty v-if="!attachments.length" description="暂无附件资料" />

    <div v-else class="attachment-workbench-list">
      <article v-for="attachment in attachments" :key="attachment.key">
        <div>
          <strong>{{ attachment.title || attachment.fileName }}</strong>

          <span>{{ attachment.fieldLabel || "未关联字段" }} · {{ attachment.department || "未记录科室" }}</span>

          <small>{{ attachment.uploadedAt || "上传时间待补" }} · {{ attachment.uploader || "上传人待补" }}</small>
        </div>

        <el-button plain :disabled="!canOpenAttachment(attachment)" @click="$emit('open', attachment.url)">打开</el-button>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { Upload } from "@element-plus/icons-vue";
import type { RecordAttachment } from "@/config/fieldPermissions";

defineProps<{
  attachments: RecordAttachment[];
  canOpenAttachment: (attachmentOrUrl?: RecordAttachment | string) => boolean;
}>();

defineEmits<{
  upload: [];
  open: [url: string];
}>();
</script>

<style scoped lang="scss">
.attachment-workbench {
  display: grid;
  gap: 12px;
  padding-bottom: 24px;
}

.attachment-workbench-head {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  background: var(--hos-panel);
  border: 1px solid var(--hos-border);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-soft);

  h3,
  p {
    margin: 0;
  }

  h3 {
    font-size: 18px;
  }

  p {
    margin-top: 4px;
    color: var(--hos-text-secondary);
  }
}

.attachment-workbench-list {
  display: grid;
  gap: 8px;
}

.attachment-workbench-list article {
  display: flex;
  gap: 14px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  background: var(--hos-panel);
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);

  div {
    display: grid;
    gap: 3px;
    min-width: 0;
  }

  strong,
  p {
    margin: 0;
    overflow-wrap: anywhere;
  }

  span,
  small,
  p {
    color: var(--hos-text-secondary);
  }
}
</style>
