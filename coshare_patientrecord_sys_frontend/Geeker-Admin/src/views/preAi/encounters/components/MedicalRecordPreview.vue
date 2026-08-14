<template>
  <section class="template-preview-panel">
    <div class="document-preview-toolbar">
      <div>
        <strong>前置病历事实资料预览</strong>
        <span>内部预览使用真实资料，正式导出时由系统自动脱敏</span>
      </div>
      <el-tag type="warning" effect="plain">非正式住院病历</el-tag>
    </div>
    <article class="document-sheet">
      <header class="document-header">
        <h2>前置病历事实资料</h2>
        <p>供医生复核并作为外部病历生成前的结构化事实来源</p>
        <div class="document-meta">
          <span>病例标识：{{ caseToken }}</span>
          <span>就诊日期：{{ visitDate || "待填写" }}</span>
          <span>就诊分支：{{ routeLabel }}</span>
        </div>
      </header>
      <section v-if="previewInspectionImages.length" class="document-section inspection-image-section">
        <div class="inspection-image-heading">
          <div>
            <h3>检查室原始图片</h3>
            <p>保留检查室上传的一手影像资料，点击图片可放大核对。</p>
          </div>
          <el-tag effect="plain">共 {{ previewInspectionImages.length }} 张</el-tag>
        </div>
        <div class="inspection-image-grid">
          <figure v-for="image in previewInspectionImages" :key="image.id">
            <el-image
              v-if="image.url"
              :src="image.url"
              :preview-src-list="inspectionImageUrls"
              :initial-index="inspectionImageUrls.indexOf(image.url)"
              fit="cover"
              preview-teleported
              hide-on-click-modal
            >
              <template #error>
                <div class="inspection-image-fallback">图片加载失败</div>
              </template>
            </el-image>
            <div v-else class="inspection-image-fallback">图片加载中或暂不可用</div>
            <figcaption>
              <strong :title="image.fileName">{{ image.fileName }}</strong>
              <span>{{ image.description || "检查室原始资料" }}</span>
              <small>{{ image.capturedAt || "采集时间未记录" }} · {{ image.uploader || "检查室" }}</small>
            </figcaption>
          </figure>
        </div>
      </section>
      <section v-for="section in sections" :key="section.key" class="document-section">
        <div class="document-section-heading">
          <div>
            <h3>{{ section.title }}</h3>
            <p v-if="section.note" class="document-section-note">{{ section.note }}</p>
          </div>
          <el-tag v-if="section.rows.length" effect="plain" size="small">{{ section.rows.length }} 项事实</el-tag>
        </div>
        <div v-if="section.labReports?.length" class="lab-report-groups">
          <article v-for="report in section.labReports" :key="report.key" class="lab-report-group">
            <header>
              <div>
                <strong>{{ report.title }}</strong>
                <span>{{ report.reportDate || "日期待填写" }}</span>
              </div>
              <el-tag :type="report.abnormalMetrics.length ? 'danger' : 'success'" size="small" effect="plain">
                {{ report.abnormalMetrics.length ? `${report.abnormalMetrics.length} 项异常` : "未见异常" }}
              </el-tag>
            </header>
            <div v-if="report.abnormalMetrics.length" class="lab-abnormal-list">
              <div
                v-for="metric in report.abnormalMetrics"
                :key="metric.key"
                :class="{ critical: metric.severity === 'CRITICAL' }"
              >
                <div>
                  <strong>{{ metric.name }}</strong>
                  <small v-if="metric.shortName">{{ metric.shortName }}</small>
                </div>
                <span class="lab-metric-value">{{ metric.value }}{{ metric.unit }}</span>
                <span class="lab-reference">参考：{{ metric.reference || "未设置" }}</span>
                <el-tag :type="metric.severity === 'CRITICAL' ? 'danger' : 'warning'" size="small" effect="dark">
                  {{ metric.severity === "CRITICAL" ? "危急值" : metric.abnormal }}
                </el-tag>
              </div>
            </div>
            <el-collapse v-if="report.normalMetrics.length" class="lab-normal-collapse">
              <el-collapse-item :title="`其余 ${report.normalMetrics.length} 项未见异常`">
                <div class="lab-normal-list">
                  <div v-for="metric in report.normalMetrics" :key="metric.key">
                    <span
                      >{{ metric.name }}<small v-if="metric.shortName">（{{ metric.shortName }}）</small></span
                    >
                    <strong>{{ metric.value }}{{ metric.unit }}</strong>
                    <small>参考：{{ metric.reference || "未设置" }}</small>
                  </div>
                </div>
              </el-collapse-item>
            </el-collapse>
          </article>
        </div>
        <template v-else-if="section.rows.length">
          <div class="document-fields summary-fields">
            <div v-for="row in summaryRows(section)" :key="row.key" :class="{ wide: row.wide, abnormal: row.abnormal }">
              <strong>{{ row.label }}：</strong>
              <span>{{ row.value }}</span>
              <el-tag v-if="row.abnormal" type="danger" size="small" effect="dark">异常</el-tag>
            </div>
          </div>
          <details v-if="detailRows(section).length" class="section-details">
            <summary>展开其余 {{ detailRows(section).length }} 项事实</summary>
            <div class="document-fields detail-fields">
              <div v-for="row in detailRows(section)" :key="row.key" :class="{ wide: row.wide, abnormal: row.abnormal }">
                <strong>{{ row.label }}：</strong>
                <span>{{ row.value }}</span>
                <el-tag v-if="row.abnormal" type="danger" size="small" effect="dark">异常</el-tag>
              </div>
            </div>
          </details>
        </template>
        <p v-else class="document-empty">本节暂无已维护内容</p>
      </section>
      <footer class="document-footer">各岗位维护事实 · 医生统一复核 · 导出自动脱敏</footer>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { DocumentPreviewImage, DocumentPreviewSection } from "../previewTypes";

const props = defineProps<{
  caseToken: string;
  visitDate?: string;
  routeLabel: string;
  sections: DocumentPreviewSection[];
  inspectionImages?: DocumentPreviewImage[];
}>();

const previewInspectionImages = computed(() => props.inspectionImages || []);
const inspectionImageUrls = computed(() =>
  previewInspectionImages.value.map(image => image.url).filter((url): url is string => Boolean(url))
);
const summaryRows = (section: DocumentPreviewSection) =>
  section.rows
    .filter(row => row.abnormal)
    .concat(section.rows.filter(row => !row.abnormal))
    .slice(0, 4);
const detailRows = (section: DocumentPreviewSection) => {
  const summaryKeys = new Set(summaryRows(section).map(row => row.key));
  return section.rows.filter(row => !summaryKeys.has(row.key));
};
</script>

<style scoped lang="scss">
.template-preview-panel {
  min-width: 0;
}
.document-preview-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.document-preview-toolbar > div {
  display: grid;
  gap: 4px;
}
.document-preview-toolbar span {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.document-sheet {
  width: min(100%, 860px);
  min-height: 1080px;
  padding: 46px 54px;
  margin: 0 auto;
  color: #202124;
  background: #fff;
  border: 1px solid #d9dde5;
  box-shadow: 0 14px 40px rgb(15 23 42 / 10%);
}
.document-header {
  padding-bottom: 22px;
  text-align: center;
  border-bottom: 2px solid #1f4e78;
}
.document-header h2 {
  margin: 0;
  font-family: "SimHei", "黑体", sans-serif;
  font-size: 26px;
}
.document-header p {
  margin: 8px 0 16px;
  color: #666;
}
.document-meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  font-size: 13px;
  text-align: left;
}
.document-section {
  padding-top: 21px;
}
.inspection-image-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.inspection-image-heading h3 {
  margin-bottom: 5px;
}
.inspection-image-heading p {
  margin: 0;
  color: #777;
  font-size: 12px;
}
.inspection-image-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.inspection-image-grid figure {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  border: 1px solid #d9dde5;
  border-radius: 8px;
  background: #fafbfc;
}
.inspection-image-grid :deep(.el-image),
.inspection-image-fallback {
  display: flex;
  width: 100%;
  height: 150px;
  align-items: center;
  justify-content: center;
  color: #8a9099;
  font-size: 12px;
  background: #eef1f4;
}
.inspection-image-grid :deep(.el-image) {
  cursor: zoom-in;
}
.inspection-image-grid figcaption {
  display: grid;
  gap: 4px;
  padding: 9px 10px 10px;
}
.inspection-image-grid figcaption strong,
.inspection-image-grid figcaption span,
.inspection-image-grid figcaption small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.inspection-image-grid figcaption strong {
  font-size: 13px;
}
.inspection-image-grid figcaption span,
.inspection-image-grid figcaption small {
  color: #777;
  font-size: 11px;
}
.document-section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.document-section h3 {
  margin: 0;
  padding-left: 10px;
  color: #1f4e78;
  font-family: "SimHei", "黑体", sans-serif;
  font-size: 17px;
  border-left: 4px solid #1f4e78;
}
.inspection-image-section {
  padding: 18px;
  margin-top: 22px;
  border: 1px solid #bfd7ec;
  border-radius: 12px;
  background: #f6fbff;
}
.inspection-image-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.inspection-image-heading h3 {
  margin-bottom: 6px;
}
.inspection-image-heading p {
  margin: 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.6;
}
.inspection-image-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 14px;
}
.inspection-image-card {
  display: grid;
  min-width: 0;
  padding: 0;
  overflow: hidden;
  color: inherit;
  text-align: left;
  background: #fff;
  border: 1px solid #d8e3ed;
  border-radius: 10px;
  cursor: pointer;
}
.inspection-image-card:hover {
  border-color: var(--el-color-primary);
  box-shadow: 0 8px 20px rgb(31 78 120 / 12%);
}
.inspection-image-card img,
.inspection-image-card > span {
  width: 100%;
  height: 132px;
  object-fit: cover;
  background: #edf3f8;
}
.inspection-image-card > span {
  display: grid;
  place-items: center;
  color: #667085;
  font-size: 12px;
}
.inspection-image-card strong,
.inspection-image-card small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.inspection-image-card strong {
  padding: 9px 10px 2px;
  font-size: 13px;
}
.inspection-image-card small {
  padding: 0 10px 9px;
  color: #667085;
}
.document-section-note {
  margin: 6px 0 0;
  color: #777;
  font-size: 12px;
}
.document-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 20px;
  line-height: 1.75;
}
.document-fields > div {
  min-width: 0;
  padding-bottom: 5px;
  border-bottom: 1px dotted #c8ccd3;
}
.document-fields > div.wide {
  grid-column: span 2;
}
.document-fields > div.abnormal {
  padding: 8px 10px;
  border: 1px solid var(--el-color-danger-light-5);
  border-radius: 8px;
  background: var(--el-color-danger-light-9);
}
.document-fields > div.abnormal .el-tag {
  margin-left: 8px;
}
.document-fields strong {
  font-family: "SimHei", "黑体", sans-serif;
}
.document-fields span {
  white-space: pre-wrap;
}
.section-details {
  margin-top: 12px;
  border-top: 1px solid #e3e6eb;
}
.section-details summary {
  padding: 11px 2px;
  color: #52606d;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.detail-fields {
  padding: 12px;
  background: #f8fafc;
  border-radius: 8px;
}
.lab-report-groups {
  display: grid;
  gap: 14px;
}
.lab-report-group {
  overflow: hidden;
  border: 1px solid #d9dde5;
  border-radius: 10px;
}
.lab-report-group > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 13px;
  background: #f7f9fb;
}
.lab-report-group > header > div {
  display: grid;
  gap: 2px;
}
.lab-report-group > header span,
.lab-reference,
.lab-abnormal-list small,
.lab-normal-list small {
  color: #777;
  font-size: 12px;
}
.lab-abnormal-list {
  display: grid;
  gap: 8px;
  padding: 12px;
}
.lab-abnormal-list > div {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) auto minmax(120px, auto) auto;
  align-items: center;
  gap: 12px;
  padding: 9px 10px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 8px;
  background: var(--el-color-warning-light-9);
}
.lab-abnormal-list > div.critical {
  border-color: var(--el-color-danger-light-3);
  background: var(--el-color-danger-light-9);
}
.lab-abnormal-list > div > div {
  display: grid;
}
.lab-metric-value {
  color: var(--el-color-danger-dark-2);
  font-weight: 700;
}
.lab-normal-collapse {
  padding: 0 12px 8px;
  border-top: none;
}
.lab-normal-collapse :deep(.el-collapse-item__header) {
  height: 42px;
  color: #667085;
  font-size: 13px;
}
.lab-normal-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px 16px;
}
.lab-normal-list > div {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 2px 10px;
  padding-bottom: 5px;
  color: #667085;
  border-bottom: 1px dotted #d8dce3;
}
.lab-normal-list > div > small {
  grid-column: span 2;
}
.document-empty {
  margin: 0;
  color: #aaa;
}
.document-footer {
  padding-top: 36px;
  margin-top: 32px;
  color: #888;
  font-size: 12px;
  text-align: center;
  border-top: 1px solid #ddd;
}
@media (max-width: 680px) {
  .document-preview-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
  .document-sheet {
    min-height: auto;
    padding: 24px 18px;
  }
  .document-meta,
  .document-fields,
  .lab-normal-list,
  .inspection-image-grid {
    grid-template-columns: 1fr;
  }
  .lab-abnormal-list > div {
    grid-template-columns: 1fr auto;
  }
  .lab-reference {
    grid-column: 1;
  }
  .document-fields > div.wide {
    grid-column: span 1;
  }
}
</style>
