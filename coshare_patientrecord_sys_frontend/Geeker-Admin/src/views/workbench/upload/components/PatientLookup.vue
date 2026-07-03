<template>
  <div>
    <div class="scan-row">
      <el-input
        ref="keywordInput"
        :model-value="keyword"
        size="large"
        clearable
        placeholder="扫码或输入 门诊号 / 姓名 / 手机号后四位"
        @update:model-value="updateKeyword"
        @keyup.enter="$emit('search')"
      />
      <el-button type="primary" size="large" :icon="Search" :loading="searching" @click="$emit('search')">识别患者</el-button>
    </div>

    <div v-if="patients.length" class="patient-results">
      <button
        v-for="patient in patients"
        :key="patient.id"
        :class="{ active: selectedPatient?.id === patient.id }"
        @click="$emit('update:selectedPatient', patient)"
      >
        <strong>{{ patient.name }}</strong>
        <span>{{ patient.visitNo }} · {{ patient.visitType }}</span>
        <el-tag :type="patient.riskType || 'info'" effect="plain">{{ patient.status }}</el-tag>
      </button>
    </div>

    <el-alert
      v-if="selectedPatient"
      class="mt12"
      type="success"
      show-icon
      :closable="false"
      :title="`已选择：${selectedPatient.name}，${selectedPatient.visitNo}`"
    />
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { Search } from "@element-plus/icons-vue";
import type { PatientRow } from "@/api/modules/clinic";

defineProps<{
  keyword: string;
  patients: PatientRow[];
  selectedPatient?: PatientRow;
  searching: boolean;
}>();

const emit = defineEmits<{
  search: [];
  "update:keyword": [value: string];
  "update:selectedPatient": [patient?: PatientRow];
}>();

const keywordInput = ref<{ focus: () => void }>();

const updateKeyword = (value: string | number) => emit("update:keyword", String(value || ""));

defineExpose({
  focus: () => keywordInput.value?.focus()
});
</script>

<style scoped lang="scss">
.scan-row {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) 128px;
  gap: 10px;
}

.patient-results {
  display: grid;
  gap: 8px;
  margin-top: 12px;

  button {
    display: grid;
    grid-template-columns: 120px minmax(0, 1fr) auto;
    gap: 10px;
    align-items: center;
    padding: 10px 12px;
    text-align: left;
    cursor: pointer;
    background: #ffffff;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;

    &.active {
      background: var(--el-color-primary-light-9);
      border-color: var(--el-color-primary-light-5);
    }
  }
}

@media (max-width: 760px) {
  .scan-row,
  .patient-results button {
    grid-template-columns: 1fr;
  }
}
</style>
