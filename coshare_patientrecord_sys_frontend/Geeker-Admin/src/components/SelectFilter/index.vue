<template>
  <div class="select-filter">
    <div v-for="item in data" :key="item.key" class="select-filter-item">
      <div class="select-filter-item-title">
        <span>{{ item.title }}：</span>
      </div>
      <span v-if="!safeOptions(item).length" class="select-filter-notData">暂无数据 ~</span>
      <el-scrollbar>
        <ul class="select-filter-list">
          <li
            v-for="option in safeOptions(item)"
            :key="option.value"
            :class="{ active: isSelected(item, option) }"
            @click="select(item, option)"
          >
            <slot :row="option">
              <el-icon v-if="option.icon">
                <component :is="option.icon" />
              </el-icon>
              <span>{{ option.label }}</span>
            </slot>
          </li>
        </ul>
      </el-scrollbar>
    </div>
  </div>
</template>

<script setup lang="ts" name="selectFilter">
import { ref, watch } from "vue";

interface OptionsProps {
  value: string | number;
  label: string;
  icon?: string;
}

interface SelectDataProps {
  title: string;
  key: string;
  multiple?: boolean;
  options?: OptionsProps[];
}

interface SelectFilterProps {
  data?: SelectDataProps[];
  defaultValues?: { [key: string]: any };
}

const props = withDefaults(defineProps<SelectFilterProps>(), {
  data: () => [],
  defaultValues: () => ({})
});

const selected = ref<{ [key: string]: any }>({});

const safeOptions = (item: SelectDataProps) => (Array.isArray(item.options) ? item.options : []);

const normalizeSelectedArray = (item: SelectDataProps) => {
  const defaultValue = safeOptions(item)[0]?.value ?? "";
  if (!Array.isArray(selected.value[item.key])) selected.value[item.key] = [defaultValue];
  return selected.value[item.key] as Array<string | number>;
};

const isSelected = (item: SelectDataProps, option: OptionsProps) => {
  const current = selected.value[item.key];
  return option.value === current || (Array.isArray(current) && current.includes(option.value));
};

watch(
  () => props.defaultValues,
  () => {
    props.data.forEach(item => {
      selected.value[item.key] = item.multiple ? (props.defaultValues[item.key] ?? [""]) : (props.defaultValues[item.key] ?? "");
    });
  },
  { deep: true, immediate: true }
);

const emit = defineEmits<{
  change: [value: any];
}>();

const select = (item: SelectDataProps, option: OptionsProps) => {
  if (!item.multiple) {
    if (selected.value[item.key] !== option.value) selected.value[item.key] = option.value;
  } else {
    const defaultValue = safeOptions(item)[0]?.value ?? "";
    const current = normalizeSelectedArray(item);
    if (defaultValue === option.value) {
      selected.value[item.key] = [option.value];
    } else if (current.includes(option.value)) {
      const currentIndex = current.findIndex(value => value === option.value);
      current.splice(currentIndex, 1);
      if (!current.length) selected.value[item.key] = [defaultValue];
    } else {
      current.push(option.value);
      const defaultIndex = current.findIndex(value => value === defaultValue);
      if (defaultIndex >= 0) current.splice(defaultIndex, 1);
    }
  }
  emit("change", selected.value);
};
</script>

<style scoped lang="scss">
@use "./index.scss" as *;
</style>
