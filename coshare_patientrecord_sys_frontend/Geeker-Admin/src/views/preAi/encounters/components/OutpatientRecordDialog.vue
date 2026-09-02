<template>
  <el-dialog
    :model-value="modelValue"
    title="门诊病历信息汇总"
    width="860px"
    top="6vh"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', Boolean($event))"
  >
    <el-alert
      class="summary-tip"
      type="info"
      show-icon
      :closable="false"
      title="以下汇总由本系统各岗位录入的事实自动整理；空缺段落会明确标注（空），医生确认无误后再生成并下载门诊病历 DOCX。"
    />
    <div v-if="loading" class="summary-loading">正在加载患者信息汇总…</div>
    <template v-else-if="summary">
      <section class="summary-basic">
        <div v-for="row in summary.basic" :key="row.label" class="summary-basic-row">
          <span>{{ row.label }}</span>
          <strong>{{ row.value || "—" }}</strong>
        </div>
      </section>
      <section v-for="section in summary.sections" :key="section.code" class="summary-section">
        <h4>{{ section.title }}</h4>
        <template v-if="section.empty">
          <p class="summary-empty">（空）</p>
        </template>
        <template v-else>
          <p v-for="(paragraph, index) in section.paragraphs" :key="index" class="summary-paragraph">{{ paragraph }}</p>
        </template>
      </section>
    </template>
    <el-alert
      v-if="versions.length"
      class="summary-versions"
      type="success"
      show-icon
      :closable="false"
      :title="`已生成 ${versions.length} 个门诊病历版本，最新 V${versions[0].version}（${versions[0].generatedAt}），可在下方重新下载。`"
    />
    <template #footer>
      <div class="summary-footer">
        <el-button v-if="versions.length" plain @click="$emit('download', versions[0])">下载最新版本</el-button>
        <el-button @click="$emit('update:modelValue', false)">关 闭</el-button>
        <el-button type="primary" :loading="generating" @click="$emit('generate')">确认无误，生成 DOCX 并下载</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { PreAiOutpatientRecordVersion, PreAiOutpatientSummary } from "@/api/modules/clinic";

defineProps<{
  modelValue: boolean;
  loading: boolean;
  generating: boolean;
  summary?: PreAiOutpatientSummary | null;
  versions: PreAiOutpatientRecordVersion[];
}>();

defineEmits<{
  "update:modelValue": [value: boolean];
  generate: [];
  download: [version: PreAiOutpatientRecordVersion];
}>();
</script>

<style scoped lang="scss">
.summary-tip {
  margin-bottom: 12px;
}
.summary-loading {
  padding: 40px 0;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.summary-basic {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px 16px;
  padding: 12px 14px;
  margin-bottom: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.summary-basic-row {
  display: grid;
  gap: 2px;
  min-width: 0;
}
.summary-basic-row span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.summary-basic-row strong {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.summary-section {
  padding: 10px 14px;
  margin-bottom: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}
.summary-section h4 {
  margin: 0 0 6px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-size: 14px;
  color: var(--el-text-color-primary);
}
.summary-paragraph {
  margin: 4px 0;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.summary-empty {
  margin: 4px 0;
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}
.summary-versions {
  margin-top: 12px;
}
.summary-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
@media (max-width: 760px) {
  .summary-basic {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
