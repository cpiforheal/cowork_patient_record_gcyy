<template>
  <section class="patient-field-search">
    <el-select
      v-model="selectedKey"
      filterable
      clearable
      class="patient-field-search-select"
      placeholder="搜索字段、附件、复查节点"
      :filter-method="updateQuery"
      @change="selectItem"
      @clear="clearSearch"
      @visible-change="handleVisibleChange"
    >
      <el-option v-for="item in visibleItems" :key="item.key" :label="item.label" :value="item.key">
        <div class="search-option">
          <strong>{{ item.label }}</strong>

          <small>{{ item.description }}</small>

          <span>{{ item.typeLabel }}</span>
        </div>
      </el-option>
    </el-select>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";

export type PatientFieldSearchItem = {
  key: string;
  label: string;
  description: string;
  typeLabel: string;
  keywords: string;
};

const props = defineProps<{
  items: PatientFieldSearchItem[];
}>();

const emit = defineEmits<{
  select: [key: string];
}>();

const selectedKey = ref("");
const query = ref("");

const normalize = (value: string) => value.trim().toLowerCase();

const visibleItems = computed(() => {
  const keyword = normalize(query.value);
  const pool = keyword
    ? props.items.filter(item =>
        normalize(`${item.label} ${item.description} ${item.typeLabel} ${item.keywords}`).includes(keyword)
      )
    : props.items;

  return pool.slice(0, 18);
});

const updateQuery = (value = "") => {
  query.value = value;
};

const clearSearch = () => {
  selectedKey.value = "";
  query.value = "";
};

const handleVisibleChange = (visible: boolean) => {
  if (!visible) query.value = "";
};

const selectItem = (key: string) => {
  if (!key) return;

  emit("select", key);
  selectedKey.value = "";
  query.value = "";
};
</script>

<style scoped lang="scss">
.patient-field-search {
  min-width: 260px;
}

.patient-field-search-select {
  width: 100%;
}

.search-option {
  display: grid;
  gap: 2px;
  min-width: 0;
  padding: 4px 0;

  strong,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    color: var(--hos-text-main);
    font-size: 13px;
  }

  small {
    color: var(--hos-text-secondary);
    font-size: 12px;
  }

  span {
    color: #2563eb;
    font-size: 12px;
  }
}

@media (max-width: 820px) {
  .patient-field-search {
    width: 100%;
  }
}
</style>
