<template>
  <el-autocomplete
    class="creatable-select"
    :model-value="displayValue"
    :fetch-suggestions="querySuggestions"
    :placeholder="placeholder"
    :disabled="disabled"
    clearable
    select-when-unmatched
    @update:model-value="value => emit('update:modelValue', value)"
    @select="selectSuggestion"
  />
</template>

<script setup lang="ts">
import { computed } from "vue";

interface SelectOption {
  label: string;
  value: any;
}

interface Suggestion {
  value: string;
  rawValue: any;
}

const props = defineProps<{
  modelValue: any;
  options?: SelectOption[];
  placeholder?: string;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: any): void;
}>();

const displayValue = computed(() => {
  const option = (props.options || []).find(item => item.value === props.modelValue);
  return option?.label || (props.modelValue == null ? "" : String(props.modelValue));
});

const querySuggestions = (query: string, callback: (suggestions: Suggestion[]) => void) => {
  const keyword = query.trim().toLocaleLowerCase();
  const suggestions = (props.options || [])
    .filter(option => !keyword || `${option.label} ${String(option.value)}`.toLocaleLowerCase().includes(keyword))
    .map(option => ({ value: option.label, rawValue: option.value }));
  callback(suggestions);
};

const selectSuggestion = (suggestion: Record<string, any>) => emit("update:modelValue", suggestion.rawValue);
</script>

<style scoped>
.creatable-select {
  width: 100%;
}
</style>
