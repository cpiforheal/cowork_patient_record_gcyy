<template>
  <section>
    <div class="panel-heading">
      <h3>医生最终复核</h3>
      <el-button :icon="Refresh" @click="$emit('refresh')">刷新</el-button>
    </div>
    <div class="review-summary">
      <div :class="{ ready: preview?.ready }">
        <span>复核状态</span>
        <strong>{{ preview?.ready ? "资料齐备" : "等待补齐" }}</strong>
      </div>
      <div :class="{ warning: preview?.blockers?.length }">
        <span>阻断事项</span>
        <strong>{{ preview?.blockers?.length || 0 }}</strong>
      </div>
      <div>
        <span>有效章节</span>
        <strong>{{ sections.length }}</strong>
      </div>
      <div>
        <span>历史导出</span>
        <strong>{{ exports.length }}</strong>
      </div>
    </div>
    <el-alert v-if="preview?.blockers?.length" type="warning" show-icon :closable="false" title="当前不能完成复核">
      <template #default>{{ preview.blockers.join("；") }}</template>
    </el-alert>
    <el-alert
      v-else-if="preview?.ready"
      class="ready-alert"
      type="success"
      show-icon
      :closable="false"
      title="前置资料已齐备，可确认事实并生成脱敏文档"
    />
    <div v-if="preview" class="masked-preview">
      <section v-for="section in sections" :key="section.title">
        <h4>{{ section.title }}</h4>
        <div class="read-only-grid">
          <div v-for="entry in section.entries" :key="entry[0]">
            <span>{{ fieldLabel(entry[0]) }}</span>
            <p>{{ humanValue(entry[1]) }}</p>
          </div>
        </div>
      </section>
    </div>
    <el-input
      v-if="canReview"
      :model-value="statement"
      type="textarea"
      :rows="3"
      placeholder="复核说明（选填）"
      @update:model-value="$emit('update:statement', String($event))"
    />
    <footer class="panel-actions">
      <div></div>
      <el-button
        v-if="canReview && !['REVIEWED', 'EXPORTED'].includes(encounterStatus)"
        type="primary"
        :disabled="!preview?.ready"
        :loading="loading"
        @click="$emit('confirm')"
        >确认事实无误</el-button
      >
      <el-button
        v-if="canReview && ['REVIEWED', 'EXPORTED'].includes(encounterStatus)"
        type="success"
        :loading="loading"
        @click="$emit('generate')"
        >生成脱敏 DOCX</el-button
      >
    </footer>
    <section v-if="exports.length" class="export-list">
      <div class="section-caption">历史导出版本（新版本不覆盖旧文件）</div>
      <div v-for="version in exports" :key="version.id" class="export-row">
        <div>
          <strong>{{ version.fileName }}</strong
          ><small>{{ version.generatedAt }} · {{ version.generatedByRole || "医生" }}</small>
        </div>
        <el-button type="primary" plain @click="$emit('download', version)">下载</el-button>
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { Refresh } from "@element-plus/icons-vue";
import type { PreAiEncounterStatus, PreAiExportVersion, PreAiReviewPreview } from "@/api/modules/clinic";

export interface MaskedReviewSection {
  title: string;
  entries: Array<[string, any]>;
}

defineProps<{
  preview?: PreAiReviewPreview;
  sections: MaskedReviewSection[];
  statement: string;
  canReview: boolean;
  loading: boolean;
  encounterStatus: PreAiEncounterStatus;
  exports: PreAiExportVersion[];
  fieldLabel: (key: string) => string;
  humanValue: (value: any) => string;
}>();

defineEmits<{
  refresh: [];
  confirm: [];
  generate: [];
  download: [version: PreAiExportVersion];
  "update:statement": [value: string];
}>();
</script>

<style scoped lang="scss">
.panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}
.panel-heading h3 {
  margin: 0 0 6px;
}
.panel-heading p {
  margin: 0;
  color: var(--el-text-color-secondary);
}
.review-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 9px;
  margin: 15px 0;
}
.review-summary > div {
  display: grid;
  gap: 4px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 11px;
  background: var(--el-fill-color-light);
}
.review-summary span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.review-summary strong {
  font-size: 17px;
}
.review-summary .ready strong {
  color: var(--el-color-success);
}
.review-summary .warning strong {
  color: var(--el-color-warning);
}
.ready-alert {
  margin-bottom: 14px;
}
.masked-preview {
  display: grid;
  gap: 14px;
  margin-top: 15px;
}
.masked-preview section {
  padding: 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
}
.masked-preview h4 {
  margin: 0 0 10px;
  color: var(--el-color-primary);
}
.read-only-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 16px;
}
.read-only-grid span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.read-only-grid p {
  margin: 4px 0 0;
  white-space: pre-wrap;
}
.panel-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 18px;
}
.panel-actions > div {
  flex: 1 1 auto;
}
.panel-actions :deep(.el-button) {
  margin-left: 0;
}
.export-list {
  margin-top: 20px;
}
.section-caption {
  margin-bottom: 10px;
  font-weight: 700;
}
.export-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.export-row div {
  display: grid;
  gap: 4px;
}
.export-row small {
  color: var(--el-text-color-secondary);
}
@media (max-width: 680px) {
  .panel-heading {
    flex-direction: column;
  }
  .panel-heading :deep(.el-button),
  .panel-actions :deep(.el-button) {
    width: 100%;
    margin-left: 0;
  }
  .panel-actions > div {
    display: none;
  }
  .review-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .read-only-grid {
    grid-template-columns: 1fr;
  }
}
</style>
