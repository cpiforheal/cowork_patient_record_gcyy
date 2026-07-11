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
      <section v-for="section in sections" :key="section.key" class="document-section">
        <h3>{{ section.title }}</h3>
        <p v-if="section.note" class="document-section-note">{{ section.note }}</p>
        <div v-if="section.rows.length" class="document-fields">
          <div v-for="row in section.rows" :key="row.key" :class="{ wide: row.wide, abnormal: row.abnormal }">
            <strong>{{ row.label }}：</strong>
            <span>{{ row.value }}</span>
            <el-tag v-if="row.abnormal" type="danger" size="small" effect="dark">异常</el-tag>
          </div>
        </div>
        <p v-else class="document-empty">本节暂无已维护内容</p>
      </section>
      <footer class="document-footer">各岗位维护事实 · 医生统一复核 · 导出自动脱敏</footer>
    </article>
  </section>
</template>

<script setup lang="ts">
export interface DocumentPreviewRow {
  key: string;
  label: string;
  value: string;
  empty: boolean;
  wide: boolean;
  abnormal?: boolean;
}

export interface DocumentPreviewSection {
  key: string;
  title: string;
  note?: string;
  rows: DocumentPreviewRow[];
}

defineProps<{
  caseToken: string;
  visitDate?: string;
  routeLabel: string;
  sections: DocumentPreviewSection[];
}>();
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
.document-section h3 {
  margin: 0 0 12px;
  padding-left: 10px;
  color: #1f4e78;
  font-family: "SimHei", "黑体", sans-serif;
  font-size: 17px;
  border-left: 4px solid #1f4e78;
}
.document-section-note {
  margin: -3px 0 12px;
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
  .document-fields {
    grid-template-columns: 1fr;
  }
  .document-fields > div.wide {
    grid-column: span 1;
  }
}
</style>
