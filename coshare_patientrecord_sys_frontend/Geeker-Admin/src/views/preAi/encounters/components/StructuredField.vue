<template>
  <div v-if="field.kind === 'measurement'" class="measurement-field">
    <el-input-number
      :model-value="measurement.value"
      :precision="2"
      :controls="false"
      :disabled="disabled"
      placeholder="数值"
      @update:model-value="value => patchMeasurement('value', value)"
    />
    <el-select
      :model-value="measurement.unit"
      :disabled="disabled"
      placeholder="单位"
      @update:model-value="value => patchMeasurement('unit', value)"
    >
      <el-option v-for="unit in field.unitOptions || []" :key="unit" :label="unit" :value="unit" />
    </el-select>
    <el-select
      :model-value="measurement.status"
      :disabled="disabled"
      clearable
      placeholder="异常状态"
      @update:model-value="value => patchMeasurement('status', value)"
    >
      <el-option
        v-for="option in field.abnormalOptions || []"
        :key="String(option.value)"
        :label="option.label"
        :value="option.value"
      />
    </el-select>
  </div>

  <div v-else-if="field.kind === 'repeatable'" class="repeatable-field">
    <div v-for="(row, rowIndex) in rows" :key="rowIndex" class="repeatable-row">
      <div class="repeatable-grid">
        <label v-for="child in visibleChildren(row)" :key="child.key" :class="{ 'span-2': child.span === 2 }">
          <span>{{ child.label }}<i v-if="child.required">*</i></span>
          <el-input
            v-if="child.kind === 'input' || child.kind === 'number'"
            :model-value="row[child.key]"
            :type="child.kind === 'number' ? 'number' : 'text'"
            :disabled="disabled"
            :placeholder="child.placeholder"
            @update:model-value="value => patchRow(rowIndex, child.key, value)"
          />
          <el-input
            v-else-if="child.kind === 'textarea'"
            :model-value="row[child.key]"
            type="textarea"
            :rows="child.rows || 2"
            :disabled="disabled"
            @update:model-value="value => patchRow(rowIndex, child.key, value)"
          />
          <CreatableSelect
            v-else-if="child.kind === 'select' && child.creatable"
            :model-value="row[child.key]"
            :options="childOptions(child, row)"
            :placeholder="child.placeholder || `请选择或直接输入${child.label}`"
            :disabled="disabled"
            @update:model-value="value => patchRow(rowIndex, child.key, value)"
          />
          <el-select
            v-else-if="child.kind === 'select' || child.kind === 'multi'"
            :model-value="row[child.key]"
            :multiple="child.kind === 'multi'"
            clearable
            filterable
            :allow-create="child.creatable"
            :disabled="disabled"
            @update:model-value="value => patchRow(rowIndex, child.key, value)"
          >
            <el-option
              v-for="option in childOptions(child, row)"
              :key="String(option.value)"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-date-picker
            v-else-if="child.kind === 'date' || child.kind === 'datetime'"
            :model-value="row[child.key]"
            :type="child.kind === 'date' ? 'date' : 'datetime'"
            :value-format="child.kind === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DD HH:mm:ss'"
            :disabled="disabled"
            @update:model-value="value => patchRow(rowIndex, child.key, value)"
          />
        </label>
      </div>
      <el-button v-if="!disabled" link type="danger" @click="removeRow(rowIndex)">删除本项</el-button>
    </div>
    <el-button v-if="!disabled" plain type="primary" @click="addRow">{{ field.addLabel || `添加${field.label}` }}</el-button>
    <el-empty v-if="!rows.length" :image-size="42" description="尚未添加记录" />
  </div>

  <div v-else class="template-text-field">
    <el-alert
      v-if="needsReconfirm"
      type="warning"
      :closable="false"
      show-icon
      title="来源字段已变化，原手工修订内容需要重新确认"
    />
    <div class="template-preview">
      <span>自动生成预览</span>
      <p>{{ generatedText || "请先完成上方结构化字段" }}</p>
      <small v-if="field.templateGenerator === 'chiefComplaint'">{{ (generatedText || "").length }}/20 字</small>
    </div>
    <div v-if="field.overrideKey" class="manual-toolbar">
      <el-switch
        v-model="manualEnabled"
        :disabled="disabled"
        active-text="手工修订"
        inactive-text="跟随结构化字段"
        @change="toggleManual"
      />
    </div>
    <el-input
      v-if="manualEnabled && field.overrideKey"
      :model-value="form[field.overrideKey]"
      type="textarea"
      :rows="field.rows || 3"
      :disabled="disabled"
      placeholder="仅在模板无法准确表达时修订"
      @update:model-value="value => updateOverride(String(value || ''))"
    />
    <el-checkbox
      v-if="manualEnabled && field.confirmedKey"
      :model-value="Boolean(form[field.confirmedKey])"
      :disabled="disabled"
      @update:model-value="value => emitPatch({ [field.confirmedKey!]: Boolean(value) })"
    >
      已核对当前结构化事实并确认修订文本
    </el-checkbox>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { PreAiFieldConfig } from "../fieldConfig";
import CreatableSelect from "./CreatableSelect.vue";

const props = defineProps<{
  field: PreAiFieldConfig;
  modelValue: any;
  form: Record<string, any>;
  generatedText?: string;
  sourceHash?: string;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: any): void;
  (event: "patch", value: Record<string, any>): void;
}>();

const measurement = computed(() =>
  props.modelValue && typeof props.modelValue === "object" && !Array.isArray(props.modelValue)
    ? props.modelValue
    : { value: props.modelValue ?? "", unit: props.field.unitOptions?.[0] || "", status: "" }
);
const rows = computed<Record<string, any>[]>(() => (Array.isArray(props.modelValue) ? props.modelValue : []));
const manualEnabled = ref(Boolean(props.field.overrideKey && props.form[props.field.overrideKey] !== undefined));
const storedHash = computed(() => (props.field.sourceHashKey ? String(props.form[props.field.sourceHashKey] || "") : ""));
const needsReconfirm = computed(
  () => manualEnabled.value && Boolean(storedHash.value) && Boolean(props.sourceHash) && storedHash.value !== props.sourceHash
);

const emitPatch = (value: Record<string, any>) => emit("patch", value);

const patchMeasurement = (key: string, value: any) => {
  emit("update:modelValue", { ...measurement.value, [key]: value });
};

const childOptions = (field: PreAiFieldConfig, row: Record<string, any>) => field.optionsFor?.(row) || field.options || [];
const visibleChildren = (row: Record<string, any>) =>
  (props.field.fields || []).filter(field => !field.visible || field.visible(row));

const patchRow = (index: number, key: string, value: any) => {
  const next = rows.value.map((row, rowIndex) => (rowIndex === index ? { ...row, [key]: value } : row));
  emit("update:modelValue", next);
};

const addRow = () => {
  const row: Record<string, any> = {};
  for (const child of props.field.fields || []) {
    if (child.kind === "multi") row[child.key] = [];
  }
  emit("update:modelValue", [...rows.value, row]);
};

const removeRow = (index: number) =>
  emit(
    "update:modelValue",
    rows.value.filter((_, rowIndex) => rowIndex !== index)
  );

const patchIfChanged = (value: Record<string, any>) => {
  const patch = Object.fromEntries(Object.entries(value).filter(([key, next]) => !Object.is(props.form[key], next)));
  if (Object.keys(patch).length) emitPatch(patch);
};

const updateGeneratedValue = () => {
  if (props.field.kind !== "template-text" || manualEnabled.value) return;
  const generatedValue = props.generatedText || "";
  if (!Object.is(props.modelValue, generatedValue)) emit("update:modelValue", generatedValue);
  const patch: Record<string, any> = {};
  if (props.field.sourceHashKey) patch[props.field.sourceHashKey] = props.sourceHash || "";
  if (props.field.confirmedKey) patch[props.field.confirmedKey] = true;
  patchIfChanged(patch);
};

const toggleManual = (enabled: string | number | boolean) => {
  if (!props.field.overrideKey) return;
  if (Boolean(enabled)) {
    emitPatch({
      [props.field.overrideKey]: String(props.modelValue || props.generatedText || ""),
      ...(props.field.sourceHashKey ? { [props.field.sourceHashKey]: props.sourceHash || "" } : {}),
      ...(props.field.confirmedKey ? { [props.field.confirmedKey]: true } : {})
    });
  } else {
    emitPatch({
      [props.field.overrideKey]: undefined,
      ...(props.field.sourceHashKey ? { [props.field.sourceHashKey]: props.sourceHash || "" } : {}),
      ...(props.field.confirmedKey ? { [props.field.confirmedKey]: true } : {})
    });
    emit("update:modelValue", props.generatedText || "");
  }
};

const updateOverride = (value: string) => {
  if (!props.field.overrideKey) return;
  emitPatch({
    [props.field.overrideKey]: value,
    ...(props.field.sourceHashKey ? { [props.field.sourceHashKey]: props.sourceHash || "" } : {}),
    ...(props.field.confirmedKey ? { [props.field.confirmedKey]: true } : {})
  });
  emit("update:modelValue", value);
};

watch(() => props.generatedText, updateGeneratedValue, { immediate: true });
watch(
  () => props.sourceHash,
  value => {
    const patch: Record<string, any> = {};
    if (needsReconfirm.value && props.field.confirmedKey) patch[props.field.confirmedKey] = false;
    if (!manualEnabled.value && props.field.sourceHashKey) patch[props.field.sourceHashKey] = value || "";
    patchIfChanged(patch);
  }
);
watch(
  () => (props.field.overrideKey ? props.form[props.field.overrideKey] : undefined),
  value => {
    manualEnabled.value = value !== undefined;
  }
);
</script>

<style scoped lang="scss">
.measurement-field {
  display: grid;
  grid-template-columns: minmax(110px, 1fr) minmax(92px, 0.7fr) minmax(120px, 0.9fr);
  gap: 8px;
}

.repeatable-field,
.template-text-field {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.repeatable-row {
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-extra-light);
}

.repeatable-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.repeatable-grid label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.repeatable-grid label > span i {
  color: var(--el-color-danger);
  font-style: normal;
}

.span-2 {
  grid-column: 1 / -1;
}

.template-preview {
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}

.template-preview span,
.template-preview small {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.template-preview p {
  margin: 6px 0 0;
  line-height: 1.7;
  white-space: pre-wrap;
}

.manual-toolbar {
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 760px) {
  .measurement-field,
  .repeatable-grid {
    grid-template-columns: 1fr;
  }
}
</style>
