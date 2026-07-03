<template>
  <div v-if="failedUploads.length || staleFailedUploadSummaries.length" class="upload-failure-panel">
    <div class="failure-panel-head">
      <strong>待重试上传</strong>
      <span>上传失败不会清空患者和资料上下文，本页内可直接恢复后重试。</span>
    </div>
    <article v-for="failure in failedUploads" :key="failure.id" class="failure-card">
      <div>
        <strong>{{ failure.patient.name }} · {{ failure.patient.visitNo }}</strong>
        <small>{{ failure.failedAt }} · {{ failure.message }}</small>
        <em>{{ failure.fileNames.join("、") }}</em>
      </div>
      <div class="failure-actions">
        <el-button size="small" @click="$emit('restore', failure)">恢复编辑</el-button>
        <el-button size="small" type="primary" :loading="uploading" @click="$emit('retry', failure)">一键重试</el-button>
        <el-button size="small" text type="danger" @click="$emit('dismiss', failure.id)">移除</el-button>
      </div>
    </article>
    <article v-for="summary in staleFailedUploadSummaries" :key="summary.id" class="failure-card is-stale">
      <div>
        <strong>{{ summary.patientName }} · {{ summary.visitNo }}</strong>
        <small>{{ summary.failedAt }} · 页面已刷新，请重新选择文件后提交</small>
        <em>{{ summary.fileNames.join("、") }}</em>
      </div>
      <el-button size="small" text type="danger" @click="$emit('dismissStale', summary.id)">移除记录</el-button>
    </article>
  </div>
</template>

<script setup lang="ts">
import type { PatientRow } from "@/api/modules/clinic";
import type { UploadUserFile } from "element-plus";

type UploadItem = {
  id: string;
  type: string;
  files: UploadUserFile[];
};

type FailedUploadSummary = {
  id: string;
  patientId: string;
  patientName: string;
  visitNo: string;
  failedAt: string;
  message: string;
  fileNames: string[];
};

type FailedUpload = Omit<FailedUploadSummary, "patientId" | "patientName" | "visitNo"> & {
  patient: PatientRow;
  keyword: string;
  items: UploadItem[];
};

defineProps<{
  failedUploads: FailedUpload[];
  staleFailedUploadSummaries: FailedUploadSummary[];
  uploading: boolean;
}>();

defineEmits<{
  restore: [failure: FailedUpload];
  retry: [failure: FailedUpload];
  dismiss: [id: string];
  dismissStale: [id: string];
}>();
</script>

<style scoped lang="scss">
.upload-failure-panel {
  display: grid;
  gap: 10px;
  padding: 12px;
  margin-top: 14px;
  background: var(--hos-status-warning-soft);
  border: 1px solid rgb(217 119 6 / 22%);
  border-radius: 8px;
}

.failure-panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;

  strong,
  span {
    display: block;
  }

  span {
    color: var(--el-text-color-regular);
    font-size: 13px;
  }
}

.failure-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding: 10px 12px;
  background: rgb(255 255 255 / 78%);
  border: 1px solid rgb(217 119 6 / 18%);
  border-radius: 8px;

  &.is-stale {
    background: rgb(255 255 255 / 56%);
  }

  strong,
  small,
  em {
    display: block;
  }

  small {
    margin-top: 3px;
    color: var(--el-text-color-regular);
  }

  em {
    margin-top: 5px;
    overflow: hidden;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    font-style: normal;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.failure-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

@media (max-width: 760px) {
  .failure-panel-head,
  .failure-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .failure-card {
    grid-template-columns: 1fr;
  }
}
</style>
