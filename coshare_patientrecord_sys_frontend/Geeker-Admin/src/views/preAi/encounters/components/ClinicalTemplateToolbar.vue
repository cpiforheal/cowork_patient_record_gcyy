<template>
  <section class="clinical-template-toolbar">
    <div class="toolbar-heading">
      <div>
        <strong>病种模板（自动匹配，可微调）</strong>
        <small>接诊室按症状点选会自动匹配病种并生成规范文本；此处仅微调病种、点位、病程等变量，患者原话请记录在“患者原话速记”中</small>
      </div>
      <div class="heading-tags">
        <el-tag v-if="autoMatchLabel" size="small" type="success" effect="plain">已自动匹配：{{ autoMatchLabel }}</el-tag>
        <el-tag size="small" effect="plain">{{ templateVersion }}</el-tag>
      </div>
    </div>
    <el-select
      v-model="selected"
      multiple
      filterable
      collapse-tags
      :disabled="disabled"
      placeholder="选择主病种（首位为主），可叠加次病种"
      class="template-select"
    >
      <el-option v-for="option in options" :key="option.value" :label="option.label" :value="option.value" />
    </el-select>

    <div v-if="primaryTemplate && primaryTemplate.slots.length" class="template-slots">
      <label v-for="item in primaryTemplate.slots" :key="item.key" class="template-slot">
        <span class="slot-label">{{ item.label }}</span>
        <el-select
          v-if="item.kind === 'select'"
          :model-value="slotValue(item.key, item.default)"
          filterable
          allow-create
          default-first-option
          clearable
          :disabled="disabled"
          placeholder="选择或输入"
          @update:model-value="value => patchSlot(item.key, value, item.default)"
        >
          <el-option v-for="option in item.options" :key="option" :label="option" :value="option" />
        </el-select>
        <el-select
          v-else
          :model-value="slotValue(item.key, item.default)"
          multiple
          filterable
          collapse-tags
          :disabled="disabled"
          placeholder="选择点位"
          @update:model-value="value => patchSlot(item.key, value, item.default)"
        >
          <el-option v-for="option in item.options" :key="option" :label="option" :value="option" />
        </el-select>
      </label>
    </div>

    <div class="template-actions">
      <el-button size="small" type="primary" plain :disabled="disabled || !selected.length" @click="emitApply('fill')"
        >填充空字段</el-button
      >
      <el-button size="small" plain :disabled="disabled || !selected.length" @click="emitApply('append')">追加模板</el-button>
      <el-button size="small" type="warning" plain :disabled="disabled || !selected.length" @click="emitApply('overwrite')"
        >覆盖模板字段</el-button
      >
    </div>
    <p class="template-hint">变量调整后自动重新生成未被手工修改的模板文本；覆盖已有人工内容前请确认，覆盖后需重新确认自动结论。</p>
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";
import {
  CLINICAL_TEMPLATE_VERSION,
  clinicalTemplateById,
  clinicalTemplateOptions,
  clinicalTemplateSlotDefaults
} from "../utils/clinicalTemplateCatalog";
import type { ClinicalTemplateMode } from "../utils/clinicalTemplateCatalog";

const props = defineProps<{ modelValue?: string[]; slotValues?: Record<string, any>; disabled?: boolean; autoMatchLabel?: string }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: string[]): void;
  (event: "update:slotValues", value: Record<string, any>): void;
  (event: "apply", mode: ClinicalTemplateMode, ids: string[]): void;
}>();
const options = clinicalTemplateOptions;
const templateVersion = CLINICAL_TEMPLATE_VERSION;
const selected = computed({
  get: () => props.modelValue || [],
  set: value => emit("update:modelValue", value)
});
const primaryTemplate = computed(() => clinicalTemplateById(selected.value[0] || ""));
const slotValue = (key: string, def: string | string[]) => {
  const saved = props.slotValues?.[key];
  if (Array.isArray(def)) return Array.isArray(saved) ? saved : saved ? [String(saved)] : [...def];
  return saved === undefined || saved === null ? def : saved;
};
const patchSlot = (key: string, value: any, def: string | string[]) => {
  if (!primaryTemplate.value) return;
  const next = { ...clinicalTemplateSlotDefaults(primaryTemplate.value.id), ...(props.slotValues || {}) };
  if (Array.isArray(def) ? Array.isArray(value) && value.length : String(value ?? "").trim()) next[key] = value;
  else delete next[key];
  emit("update:slotValues", next);
  emit("apply", "render", selected.value);
};
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
.heading-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
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
.template-slots {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(168px, 1fr));
  gap: 8px 12px;
}
.template-slot {
  display: grid;
  gap: 4px;
}
.slot-label {
  font-size: 12px;
  color: var(--el-text-color-regular);
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
