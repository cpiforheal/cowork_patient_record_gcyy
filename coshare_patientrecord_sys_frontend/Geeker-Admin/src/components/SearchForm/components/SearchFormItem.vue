<template>
  <component
    :is="column.search?.render"
    v-if="column.search?.render"
    v-bind="renderScope"
    v-model.trim="searchValue"
    @keyup.enter="emitSearch"
  />
  <el-input
    v-else-if="searchEl === 'input'"
    v-model.trim="searchValue"
    v-bind="baseSearchProps"
    :placeholder="placeholder.placeholder"
    :clearable="clearable"
    @keyup.enter="emitSearch"
  />
  <el-input-number
    v-else-if="searchEl === 'input-number'"
    v-model="searchValue"
    v-bind="baseSearchProps"
    :placeholder="placeholder.placeholder"
    @keyup.enter="emitSearch"
  />
  <el-select
    v-else-if="searchEl === 'select'"
    v-model="searchValue"
    v-bind="baseSearchProps"
    :placeholder="placeholder.placeholder"
    :clearable="clearable"
    @keyup.enter="emitSearch"
  >
    <el-option
      v-for="(col, index) in columnEnum"
      :key="`${optionValue(col)}-${index}`"
      :label="optionLabel(col)"
      :value="optionValue(col)"
    />
  </el-select>
  <el-select-v2
    v-else-if="searchEl === 'select-v2'"
    v-model="searchValue"
    v-bind="baseSearchProps"
    :options="selectV2Options"
    :placeholder="placeholder.placeholder"
    :clearable="clearable"
    @keyup.enter="emitSearch"
  />
  <el-tree-select
    v-else-if="searchEl === 'tree-select'"
    v-model="searchValue"
    v-bind="treeSelectProps"
    :data="columnEnum"
    :placeholder="placeholder.placeholder"
    :clearable="clearable"
    @keyup.enter="emitSearch"
  />
  <el-cascader
    v-else-if="searchEl === 'cascader'"
    v-model="searchValue"
    v-bind="cascaderProps"
    :options="columnEnum"
    :placeholder="placeholder.placeholder"
    :clearable="clearable"
    @keyup.enter="emitSearch"
  >
    <template #default="{ data }">
      <span>{{ data[fieldNames.label] }}</span>
    </template>
  </el-cascader>
  <el-date-picker
    v-else-if="searchEl === 'date-picker'"
    v-model="searchValue"
    v-bind="{ ...baseSearchProps, ...placeholder }"
    :clearable="clearable"
    @keyup.enter="emitSearch"
  />
  <el-time-picker
    v-else-if="searchEl === 'time-picker'"
    v-model="searchValue"
    v-bind="baseSearchProps"
    :placeholder="placeholder.placeholder"
    :clearable="clearable"
    @keyup.enter="emitSearch"
  />
  <el-time-select
    v-else-if="searchEl === 'time-select'"
    v-model="searchValue"
    v-bind="baseSearchProps"
    :placeholder="placeholder.placeholder"
    :clearable="clearable"
    @keyup.enter="emitSearch"
  />
  <el-switch v-else-if="searchEl === 'switch'" v-model="searchValue" v-bind="baseSearchProps" />
  <el-slider v-else-if="searchEl === 'slider'" v-model="searchValue" v-bind="baseSearchProps" />
  <el-input
    v-else
    v-model.trim="searchValue"
    v-bind="baseSearchProps"
    :placeholder="placeholder.placeholder"
    :clearable="clearable"
    @keyup.enter="emitSearch"
  />
</template>

<script setup lang="ts" name="SearchFormItem">
import { computed, inject, ref } from "vue";
import { handleProp } from "@/utils";
import { ColumnProps, type EnumProps, type SearchType } from "@/components/ProTable/interface";

interface SearchFormItemProps {
  column: ColumnProps;
  searchParam: { [key: string]: any };
}

const props = defineProps<SearchFormItemProps>();
const emit = defineEmits<{
  search: [];
  change: [key: string, value: any];
}>();

const enumMap = inject("enumMap", ref(new Map<string, EnumProps[]>()));
const searchEl = computed<SearchType>(() => props.column.search?.el ?? "input");
const searchKey = computed(() => props.column.search?.key ?? handleProp(props.column.prop!));
const searchValue = computed({
  get: () => props.searchParam[searchKey.value],
  set: value => {
    emit("change", searchKey.value, value);
  }
});

const fieldNames = computed(() => ({
  label: props.column.fieldNames?.label ?? "label",
  value: props.column.fieldNames?.value ?? "value",
  children: props.column.fieldNames?.children ?? "children"
}));

const columnEnum = computed<EnumProps[]>(() => enumMap.value.get(props.column.prop!) ?? []);
const baseSearchProps = computed(() => props.column.search?.props ?? {});

const selectV2Options = computed(() =>
  columnEnum.value.map(item => ({
    ...item,
    label: optionLabel(item),
    value: optionValue(item)
  }))
);

const treeSelectProps = computed(() => {
  const label = fieldNames.value.label;
  const children = fieldNames.value.children;
  const value = fieldNames.value.value;
  return {
    ...baseSearchProps.value,
    props: { ...baseSearchProps.value.props, label, children },
    nodeKey: value
  };
});

const cascaderProps = computed(() => {
  const label = fieldNames.value.label;
  const value = fieldNames.value.value;
  const children = fieldNames.value.children;
  return {
    ...baseSearchProps.value,
    props: { ...baseSearchProps.value.props, label, value, children }
  };
});

const placeholder = computed(() => {
  const search = props.column.search;
  if (["datetimerange", "daterange", "monthrange"].includes(search?.props?.type) || search?.props?.isRange) {
    return {
      rangeSeparator: search?.props?.rangeSeparator ?? "至",
      startPlaceholder: search?.props?.startPlaceholder ?? "开始时间",
      endPlaceholder: search?.props?.endPlaceholder ?? "结束时间"
    };
  }
  return {
    placeholder: search?.props?.placeholder ?? (searchEl.value.includes("input") ? "请输入" : "请选择")
  };
});

const clearable = computed(() => {
  const search = props.column.search;
  return search?.props?.clearable ?? (search?.defaultValue === null || search?.defaultValue === undefined);
});

const renderScope = computed(() => ({
  searchParam: props.searchParam,
  placeholder: placeholder.value.placeholder ?? "",
  clearable: clearable.value,
  options: columnEnum.value,
  data: columnEnum.value
}));

function optionLabel(item: EnumProps) {
  return item[fieldNames.value.label];
}

function optionValue(item: EnumProps) {
  return item[fieldNames.value.value];
}

function emitSearch() {
  emit("search");
}
</script>
