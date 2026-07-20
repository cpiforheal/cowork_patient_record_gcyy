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
        <span>有效字段</span>
        <strong>{{ preview?.effectiveFieldCount || 0 }}</strong>
      </div>
      <div>
        <span>文档版本</span>
        <strong>{{ targetVersions.length + exports.length }}</strong>
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
    <section v-if="preview?.labSummary?.abnormalCount" class="lab-risk-summary">
      <header>
        <div>
          <strong>化验异常摘要</strong>
          <small>复核前请优先确认异常及危急值</small>
        </div>
        <div>
          <el-tag type="warning" effect="plain">异常 {{ preview.labSummary.abnormalCount }} 项</el-tag>
          <el-tag v-if="preview.labSummary.criticalCount" type="danger" effect="dark">
            危急值 {{ preview.labSummary.criticalCount }} 项
          </el-tag>
        </div>
      </header>
      <div class="lab-risk-list">
        <article
          v-for="(metric, index) in preview.labSummary.abnormalMetrics"
          :key="`${metric.reportName}-${metric.name}-${index}`"
          :class="{ critical: metric.severity === 'CRITICAL' }"
        >
          <div>
            <strong>{{ metric.name }}</strong>
            <small>{{ metric.reportName }} · {{ metric.reportDate }}</small>
          </div>
          <span>{{ metric.value }}{{ metric.unit || "" }}</span>
          <small>参考：{{ metric.reference || "未设置" }}</small>
          <el-tag :type="metric.severity === 'CRITICAL' ? 'danger' : 'warning'" size="small" effect="dark">
            {{ metric.severity === "CRITICAL" ? "危急值" : metric.abnormal }}
          </el-tag>
        </article>
      </div>
      <el-checkbox
        v-if="preview.labSummary.criticalCount && canReview"
        :model-value="criticalAcknowledged"
        class="critical-confirm"
        @update:model-value="$emit('update:criticalAcknowledged', Boolean($event))"
      >
        我已逐项查看以上危急值，并确认进入后续临床处置
      </el-checkbox>
    </section>
    <div v-if="preview" class="masked-preview">
      <div class="template-meta">
        <span>12 章标准模板</span>
        <small>{{ preview.templateVersion || "当前版本" }}</small>
      </div>
      <section v-for="section in sections" :key="section.code">
        <h4>{{ section.title }}</h4>
        <div v-if="section.rows.length" class="document-grid">
          <div
            v-for="row in section.rows"
            :key="row.id"
            class="document-row"
            :class="{
              emphasized: row.emphasis,
              abnormal: row.severity === 'ABNORMAL',
              critical: row.severity === 'CRITICAL'
            }"
          >
            <span>{{ row.label }}</span>
            <p>{{ row.value }}</p>
          </div>
        </div>
        <p v-else class="section-empty">本节无有效内容</p>
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
    <section class="review-status" :class="{ confirmed: reviewConfirmed }">
      <div>
        <strong>{{ reviewConfirmed ? "最终医生复核已确认" : "最终医生复核尚未确认" }}</strong>
        <small>
          {{
            reviewConfirmed
              ? "现在可以生成目标病历模板或脱敏前置资料。"
              : "请先点击“确认事实无误”，确认成功后两个生成入口将自动启用。"
          }}
        </small>
      </div>
      <el-tag :type="reviewConfirmed ? 'success' : 'warning'" effect="dark">
        {{ reviewConfirmed ? "已确认" : "待确认" }}
      </el-tag>
    </section>
    <footer v-if="canReview" class="panel-actions review-actions">
      <el-button
        v-if="!reviewConfirmed"
        type="primary"
        :loading="loading"
        :disabled="!preview?.ready || Boolean(preview?.labSummary?.criticalCount && !criticalAcknowledged)"
        @click="$emit('confirm')"
        >确认事实无误</el-button
      >
      <div v-else></div>
      <div class="generate-actions">
        <el-tooltip :disabled="targetGenerationAvailable" :content="targetGenerationDisabledReason" placement="top">
          <span>
            <el-button type="primary" :loading="loading" :disabled="!targetGenerationAvailable" @click="$emit('generateTarget')"
              >生成目标病历新版本</el-button
            >
          </span>
        </el-tooltip>
        <el-tooltip :disabled="reviewConfirmed" content="请先完成最终医生复核" placement="top">
          <span>
            <el-button type="success" plain :loading="loading" :disabled="!reviewConfirmed" @click="$emit('generate')"
              >生成脱敏资料新版本</el-button
            >
          </span>
        </el-tooltip>
      </div>
    </footer>
    <section v-if="reviewConfirmed" class="version-control">
      <header class="version-control-heading">
        <div>
          <strong>文档版本控制</strong>
          <small>生成只建立新版本，不自动下载、不覆盖历史文件；确认版本后再下载。</small>
        </div>
        <el-tag type="info" effect="plain">共 {{ targetVersions.length + exports.length }} 个版本</el-tag>
      </header>

      <div v-if="latestTargetVersion" class="generation-result">
        <span class="version-badge">V{{ latestTargetVersion.version }}</span>
        <div>
          <strong>目标病历新版本已建立</strong>
          <small>{{ latestTargetVersion.fileName || "医生目标病历" }} · 已单独保存，可回看历史版本</small>
        </div>
        <el-button type="primary" @click="$emit('downloadTarget', latestTargetVersion)">下载此版本</el-button>
      </div>
      <div v-else-if="latestExportVersion" class="generation-result export-result">
        <span class="version-badge">V{{ latestExportVersion.version }}</span>
        <div>
          <strong>脱敏资料新版本已建立</strong>
          <small>{{ latestExportVersion.fileName }} · 已单独保存，可回看历史版本</small>
        </div>
        <el-button type="success" @click="$emit('download', latestExportVersion)">下载此版本</el-button>
      </div>

      <div class="version-groups">
        <article class="version-group">
          <header>
            <div>
              <strong>目标病历版本</strong>
              <small>按患者保存，适用于正式目标病历的版本追溯</small>
            </div>
            <el-tag effect="plain">{{ targetVersions.length }}</el-tag>
          </header>
          <div v-if="versionLoading" class="version-empty">正在加载版本记录…</div>
          <div v-else-if="!orderedTargetVersions.length" class="version-empty">尚未生成目标病历版本</div>
          <template v-else>
            <div
              v-for="version in orderedTargetVersions"
              :key="version.id"
              class="version-row"
              :class="{ latest: version.id === latestTargetVersionId }"
            >
              <span class="version-no">V{{ version.version }}</span>
              <div class="version-detail">
                <div>
                  <strong>{{ version.fileName || `医生目标病历-V${version.version}.docx` }}</strong>
                  <el-tag :type="targetStatusType(version.status)" size="small" effect="plain">
                    {{ targetStatusLabel(version.status) }}
                  </el-tag>
                  <el-tag v-if="version.id === latestTargetVersionId" type="success" size="small" effect="dark">刚刚生成</el-tag>
                </div>
                <small>
                  {{ version.generatedAt }} · {{ version.operatorRole || "医生" }} ·
                  {{ version.templateVersion || version.templateName || "当前模板" }}
                </small>
              </div>
              <div class="version-actions">
                <el-button type="primary" plain :disabled="version.status === 'voided'" @click="$emit('downloadTarget', version)">
                  下载
                </el-button>
                <el-button
                  v-if="version.status !== 'finalized'"
                  type="danger"
                  plain
                  :loading="deletingTargetVersionId === version.id"
                  @click="$emit('deleteTarget', version)"
                >
                  删除
                </el-button>
              </div>
            </div>
          </template>
        </article>

        <article class="version-group">
          <header>
            <div>
              <strong>脱敏前置资料版本</strong>
              <small>按本次就诊保存，用于前置资料归档与复核</small>
            </div>
            <el-tag type="success" effect="plain">{{ exports.length }}</el-tag>
          </header>
          <div v-if="!orderedExports.length" class="version-empty">尚未生成脱敏资料版本</div>
          <template v-else>
            <div
              v-for="version in orderedExports"
              :key="version.id"
              class="version-row"
              :class="{ latest: version.id === latestExportVersionId }"
            >
              <span class="version-no export-version">V{{ version.version }}</span>
              <div class="version-detail">
                <div>
                  <strong>{{ version.fileName }}</strong>
                  <el-tag :type="exportStatusType(version.status)" size="small" effect="plain">
                    {{ exportStatusLabel(version.status) }}
                  </el-tag>
                  <el-tag v-if="version.id === latestExportVersionId" type="success" size="small" effect="dark">刚刚生成</el-tag>
                </div>
                <small>
                  {{ version.generatedAt }} · {{ version.generatedByRole || "医生" }} ·
                  {{ version.templateVersion || "旧版模板" }}
                </small>
              </div>
              <el-button type="success" plain @click="$emit('download', version)">下载</el-button>
            </div>
          </template>
        </article>
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { Refresh } from "@element-plus/icons-vue";
import type {
  GeneratedMedicalRecord,
  PreAiDocumentSection,
  PreAiEncounterStatus,
  PreAiExportVersion,
  PreAiReviewPreview
} from "@/api/modules/clinic";

const props = defineProps<{
  preview?: PreAiReviewPreview;
  sections: PreAiDocumentSection[];
  statement: string;
  canReview: boolean;
  canGenerateTarget: boolean;
  criticalAcknowledged: boolean;
  loading: boolean;
  versionLoading: boolean;
  encounterStatus: PreAiEncounterStatus;
  exports: PreAiExportVersion[];
  targetVersions: GeneratedMedicalRecord[];
  latestTargetVersionId: string;
  latestExportVersionId: string;
  deletingTargetVersionId: string;
}>();

const reviewConfirmed = computed(() => ["REVIEWED", "EXPORTED"].includes(props.encounterStatus));
const targetGenerationAvailable = computed(() => reviewConfirmed.value && props.canGenerateTarget);
const targetGenerationDisabledReason = computed(() => {
  if (!reviewConfirmed.value) return "请先完成最终医生复核";
  if (!props.canGenerateTarget) return "当前账号无权为该前置病例生成目标病历";
  return "";
});
const orderedTargetVersions = computed(() => [...props.targetVersions].sort((left, right) => right.version - left.version));
const orderedExports = computed(() => [...props.exports].sort((left, right) => right.version - left.version));
const latestTargetVersion = computed(() => props.targetVersions.find(version => version.id === props.latestTargetVersionId));
const latestExportVersion = computed(() => props.exports.find(version => version.id === props.latestExportVersionId));

const targetStatusLabel = (status: GeneratedMedicalRecord["status"]) =>
  ({ draft: "草稿", finalized: "已定稿", voided: "已作废" })[status] || status;
const targetStatusType = (status: GeneratedMedicalRecord["status"]) => {
  if (status === "finalized") return "success" as const;
  if (status === "voided") return "danger" as const;
  return "warning" as const;
};
const exportStatusLabel = (status: string) => {
  const normalized = status.toUpperCase();
  if (["INVALIDATED", "VOIDED"].includes(normalized)) return "已失效";
  if (["GENERATED", "COMPLETED", "READY"].includes(normalized)) return "可下载";
  return status || "已生成";
};
const exportStatusType = (status: string) =>
  ["INVALIDATED", "VOIDED"].includes(status.toUpperCase()) ? ("danger" as const) : ("success" as const);

defineEmits<{
  refresh: [];
  confirm: [];
  generate: [];
  generateTarget: [];
  download: [version: PreAiExportVersion];
  downloadTarget: [version: GeneratedMedicalRecord];
  deleteTarget: [version: GeneratedMedicalRecord];
  "update:statement": [value: string];
  "update:criticalAcknowledged": [value: boolean];
}>();
</script>

<style scoped lang="scss">
.review-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 10px;
  background: var(--el-color-warning-light-9);

  > div {
    display: grid;
    gap: 4px;
  }

  strong {
    color: var(--el-text-color-primary);
  }

  small {
    color: var(--el-text-color-secondary);
    line-height: 1.6;
  }

  &.confirmed {
    border-color: var(--el-color-success-light-5);
    background: var(--el-color-success-light-9);
  }
}
.review-actions {
  align-items: center;
}
.version-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
}
.generate-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}
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
.lab-risk-summary {
  display: grid;
  gap: 12px;
  padding: 14px;
  margin-top: 15px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 12px;
  background: var(--el-color-warning-light-9);
}
.lab-risk-summary > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.lab-risk-summary > header > div {
  display: flex;
  align-items: center;
  gap: 8px;
}
.lab-risk-summary > header > div:first-child {
  display: grid;
  gap: 3px;
}
.lab-risk-summary small {
  color: var(--el-text-color-secondary);
}
.lab-risk-list {
  display: grid;
  gap: 7px;
}
.lab-risk-list article {
  display: grid;
  grid-template-columns: minmax(150px, 1fr) auto minmax(130px, auto) auto;
  align-items: center;
  gap: 12px;
  padding: 9px 11px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 9px;
  background: var(--el-bg-color);
}
.lab-risk-list article.critical {
  border-color: var(--el-color-danger-light-3);
  background: var(--el-color-danger-light-9);
}
.lab-risk-list article > div {
  display: grid;
  gap: 2px;
}
.critical-confirm {
  padding-top: 10px;
  border-top: 1px solid var(--el-color-danger-light-5);
}
.critical-confirm :deep(.el-checkbox__label) {
  color: var(--el-color-danger-dark-2);
  font-weight: 700;
  white-space: normal;
}
.masked-preview {
  display: grid;
  gap: 14px;
  margin-top: 15px;
}
.template-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
}
.template-meta small {
  color: var(--el-text-color-secondary);
}
.masked-preview section {
  overflow: hidden;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
}
.masked-preview h4 {
  margin: 0;
  padding: 10px 14px;
  border-bottom: 1px solid var(--el-border-color-light);
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-size: 15px;
}
.document-grid {
  display: grid;
}
.document-row {
  display: grid;
  grid-template-columns: minmax(150px, 24%) minmax(0, 1fr);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.document-row:last-child {
  border-bottom: 0;
}
.document-row > span {
  padding: 10px 14px;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.document-row > p {
  margin: 0;
  padding: 10px 14px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.document-row.emphasized > p,
.document-row.abnormal > p,
.document-row.critical > p {
  font-weight: 700;
}
.document-row.abnormal,
.document-row.critical {
  box-shadow: inset 3px 0 0 var(--el-text-color-primary);
}
.section-empty {
  margin: 0;
  padding: 11px 14px;
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}
.lab-review-section {
  background: var(--el-fill-color-lighter);
}
.lab-report-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 16px;
  margin-bottom: 12px;
}
.lab-report-meta > div {
  display: grid;
  gap: 4px;
}
.lab-report-meta span,
.metric-value small,
.metric-name small {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.lab-metric-list {
  display: grid;
  gap: 8px;
}
.lab-metric-list article {
  display: grid;
  grid-template-columns: minmax(160px, 1fr) minmax(170px, auto) auto;
  align-items: center;
  gap: 14px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-bg-color);
}
.lab-metric-list article.abnormal {
  border-color: var(--el-color-warning-light-5);
  background: var(--el-color-warning-light-9);
}
.lab-metric-list article.critical {
  border-color: var(--el-color-danger-light-3);
  background: var(--el-color-danger-light-9);
}
.metric-name,
.metric-value {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.metric-value {
  text-align: right;
}
.lab-empty {
  margin: 0;
  color: var(--el-text-color-secondary);
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
.version-control {
  display: grid;
  gap: 14px;
  margin-top: 20px;
  padding: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 14px;
  background: var(--el-fill-color-extra-light);
}
.version-control-heading,
.version-group > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.version-control-heading > div,
.version-group > header > div,
.generation-result > div {
  display: grid;
  gap: 4px;
}
.version-control-heading strong {
  font-size: 16px;
}
.version-control-heading small,
.version-group small,
.generation-result small {
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.generation-result {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 13px;
  padding: 13px 14px;
  border: 1px solid var(--el-color-primary-light-5);
  border-radius: 12px;
  background: var(--el-color-primary-light-9);
}
.generation-result.export-result {
  border-color: var(--el-color-success-light-5);
  background: var(--el-color-success-light-9);
}
.version-badge,
.version-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  border-radius: 9px;
  background: var(--el-color-primary);
  color: #ffffff;
  font-weight: 700;
  letter-spacing: 0.02em;
}
.version-badge {
  min-width: 52px;
  height: 42px;
  font-size: 17px;
}
.version-groups {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.version-group {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: var(--el-bg-color);
}
.version-group > header {
  padding: 12px 13px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-light);
}
.version-empty {
  padding: 24px 14px;
  color: var(--el-text-color-placeholder);
  text-align: center;
}
.version-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.version-row:last-child {
  border-bottom: 0;
}
.version-row.latest {
  box-shadow: inset 3px 0 0 var(--el-color-success);
  background: var(--el-color-success-light-9);
}
.version-no {
  min-width: 42px;
  height: 32px;
  font-size: 13px;
}
.version-no.export-version,
.export-result .version-badge {
  background: var(--el-color-success);
}
.version-detail {
  min-width: 0;
  display: grid;
  gap: 4px;
}
.version-detail > div {
  min-width: 0;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.version-detail strong {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  .lab-risk-summary > header {
    align-items: flex-start;
    flex-direction: column;
  }
  .lab-risk-list article {
    grid-template-columns: 1fr auto;
  }
  .read-only-grid,
  .lab-report-meta {
    grid-template-columns: 1fr;
  }
  .document-row {
    grid-template-columns: 1fr;
  }
  .document-row > span {
    padding-bottom: 4px;
  }
  .document-row > p {
    padding-top: 4px;
  }
  .lab-metric-list article {
    grid-template-columns: 1fr auto;
  }
  .metric-value {
    text-align: left;
  }
  .lab-metric-list article :deep(.el-tag) {
    grid-column: 2;
    grid-row: 1 / span 2;
  }
  .version-control-heading,
  .version-group > header {
    align-items: flex-start;
  }
  .version-control-heading {
    flex-direction: column;
  }
  .version-groups {
    grid-template-columns: 1fr;
  }
  .generation-result,
  .version-row {
    grid-template-columns: auto minmax(0, 1fr);
  }
  .generation-result :deep(.el-button),
  .version-row :deep(.el-button) {
    width: 100%;
    grid-column: 1 / -1;
  }
}
</style>
