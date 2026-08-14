<template>
  <section class="clinical-template-toolbar">
    <div class="toolbar-heading">
      <div>
        <strong>五类病种模板辅助</strong>
        <small>仅生成可修改草稿，不替代医生诊断或收治决定</small>
      </div>
      <el-tag size="small" effect="plain">{{ templateVersion }}</el-tag>
    </div>
    <el-select
      v-model="selected"
      multiple
      filterable
      collapse-tags
      :disabled="disabled"
      placeholder="选择主病种，可叠加次病种"
      class="template-select"
    >
      <el-option v-for="option in options" :key="option.value" :label="option.label" :value="option.value" />
    </el-select>
    <div class="template-actions">
      <el-button size="small" type="primary" plain :disabled="disabled || !selected.length" @click="emitApply('fill')"
        >填充空字段</el-button
      >
      <el-button size="small" plain :disabled="disabled || !selected.length" @click="emitApply('append')">追加模板</el-button>
      <el-button size="small" type="warning" plain :disabled="disabled || !selected.length" @click="emitApply('overwrite')"
        >覆盖模板字段</el-button
      >
    </div>
    <p class="template-hint">覆盖已有人工内容前请确认；覆盖后需要重新确认自动结论。</p>
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { CLINICAL_TEMPLATE_VERSION, clinicalTemplateOptions } from "../utils/clinicalTemplateCatalog";
import type { ClinicalTemplateMode } from "../utils/clinicalTemplateCatalog";

const props = defineProps<{ modelValue?: string[]; disabled?: boolean }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: string[]): void;
  (event: "apply", mode: ClinicalTemplateMode, ids: string[]): void;
}>();
const options = clinicalTemplateOptions;
const templateVersion = CLINICAL_TEMPLATE_VERSION;
const selected = computed({
  get: () => props.modelValue || [],
  set: value => emit("update:modelValue", value)
});
const emitApply = (mode: ClinicalTemplateMode) => emit("apply", mode, selected.value);
</script>

<style scoped lang="scss">
.clinical-template-toolbar {
  display: grid;
  gap: 10px;
  margin: 0 0 16px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
}
.toolbar-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.toolbar-heading div {
  display: grid;
  gap: 3px;
}
.toolbar-heading small,
.template-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.template-select {
  width: 100%;
}
.template-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.template-actions :deep(.el-button) {
  margin: 0;
}
.template-hint {
  margin: 0;
}
</style>
