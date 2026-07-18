<template>
  <div class="registration-form-grid">
    <el-form-item
      v-for="field in fields"
      :key="field.key"
      :label="field.label"
      :required="field.required"
      :class="{ 'span-2': field.span === 2 }"
    >
      <el-input
        v-if="field.kind === 'input' || field.kind === 'number'"
        :model-value="form[field.key]"
        :type="field.kind === 'number' ? 'number' : 'text'"
        :placeholder="field.placeholder"
        @update:model-value="value => update(field.key, value)"
      />
      <el-input
        v-else-if="field.kind === 'textarea'"
        :model-value="form[field.key]"
        type="textarea"
        :rows="field.rows || 3"
        :placeholder="field.placeholder"
        @update:model-value="value => update(field.key, value)"
      />
      <CreatableSelect
        v-else-if="field.kind === 'select' && field.creatable"
        :model-value="form[field.key]"
        :options="field.optionsFor?.(form) || field.options || []"
        :placeholder="field.placeholder || `请选择或直接输入${field.label}`"
        @update:model-value="value => update(field.key, value)"
      />
      <el-select
        v-else-if="field.kind === 'select' || field.kind === 'multi'"
        :model-value="form[field.key]"
        :multiple="field.kind === 'multi'"
        clearable
        filterable
        default-first-option
        :placeholder="field.placeholder || `请选择${field.label}`"
        @update:model-value="value => update(field.key, value)"
      >
        <el-option
          v-for="option in field.optionsFor?.(form) || field.options || []"
          :key="String(option.value)"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <el-date-picker
        v-else-if="field.kind === 'date' || field.kind === 'datetime'"
        :model-value="form[field.key]"
        :type="field.kind === 'date' ? 'date' : 'datetime'"
        :value-format="field.kind === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DD HH:mm:ss'"
        @update:model-value="value => update(field.key, value)"
      />
    </el-form-item>
  </div>
</template>

<script setup lang="ts">
import type { PreAiFieldConfig } from "../fieldConfig";
import CreatableSelect from "./CreatableSelect.vue";

defineProps<{
  fields: PreAiFieldConfig[];
  form: Record<string, any>;
}>();

const emit = defineEmits<{
  (event: "patch", key: string, value: any): void;
}>();

const update = (key: string, value: any) => emit("patch", key, value);
</script>

<style scoped lang="scss">
.registration-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 18px;
}
.registration-form-grid .span-2 {
  grid-column: span 2;
}
.registration-form-grid :deep(.el-select),
.registration-form-grid :deep(.el-date-editor) {
  width: 100%;
}
@media (max-width: 760px) {
  .registration-form-grid {
    grid-template-columns: 1fr;
  }
  .registration-form-grid .span-2 {
    grid-column: auto;
  }
}
</style>
