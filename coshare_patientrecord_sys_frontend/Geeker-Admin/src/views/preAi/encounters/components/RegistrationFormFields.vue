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
import { watch } from "vue";
import type { PreAiFieldConfig } from "../fieldConfig";
import CreatableSelect from "./CreatableSelect.vue";

const props = defineProps<{
  fields: PreAiFieldConfig[];
  form: Record<string, any>;
}>();

const emit = defineEmits<{
  (event: "patch", key: string, value: any): void;
}>();

const update = (key: string, value: any) => emit("patch", key, value);

let lastDerivedAge = "";

const ageFromResidentIdentity = (identityNumber: unknown, visitDate: unknown) => {
  const normalized = String(identityNumber || "").trim().toUpperCase();
  const birth = /^\d{15}$/.test(normalized)
    ? `19${normalized.slice(6, 12)}`
    : /^\d{17}[\dX]$/.test(normalized) && validResidentIdentityChecksum(normalized)
      ? normalized.slice(6, 14)
      : "";
  if (!/^\d{8}$/.test(birth)) return "";
  const year = Number(birth.slice(0, 4));
  const month = Number(birth.slice(4, 6));
  const day = Number(birth.slice(6, 8));
  const reference = new Date(String(visitDate || "").replace(" ", "T"));
  const birthday = new Date(year, month - 1, day);
  if (!Number.isFinite(reference.getTime()) || birthday.getFullYear() !== year || birthday.getMonth() !== month - 1 || birthday.getDate() !== day || birthday > reference) {
    return "";
  }
  let age = reference.getFullYear() - year;
  if (reference.getMonth() < month - 1 || (reference.getMonth() === month - 1 && reference.getDate() < day)) age -= 1;
  return String(age);
};

const validResidentIdentityChecksum = (value: string) => {
  const weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2];
  const checks = ["1", "0", "X", "9", "8", "7", "6", "5", "4", "3", "2"];
  const total = weights.reduce((sum, weight, index) => sum + Number(value[index]) * weight, 0);
  return checks[total % 11] === value[17];
};

watch(
  () => [props.form.identityType, props.form.identityNumber, props.form.visitDate],
  () => {
    if (props.form.identityType !== "居民身份证") return;
    const derivedAge = ageFromResidentIdentity(props.form.identityNumber, props.form.visitDate);
    const currentAge = String(props.form.age || "").trim();
    if (!derivedAge || (currentAge && currentAge !== lastDerivedAge)) return;
    lastDerivedAge = derivedAge;
    if (currentAge !== derivedAge) update("age", derivedAge);
  },
  { immediate: true }
);
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
