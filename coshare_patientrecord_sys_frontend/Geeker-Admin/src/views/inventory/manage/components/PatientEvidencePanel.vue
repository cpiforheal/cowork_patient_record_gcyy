<template>
  <section class="patient-evidence" v-loading="loading">
    <header class="pe-head">
      <div>
        <span class="eyebrow">定额模式 · 患者证据</span>
        <h2>患者耗材归属依据</h2>
        <p>定额由系统按接诊人数生成并扣库；本页只读展示患者、人次与每人次定额，不能修改任何耗材数量。</p>
      </div>
      <div class="pe-actions">
        <el-date-picker v-if="!embedded" v-model="businessDate" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="load" />
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <div class="pe-stats">
      <span>接诊人次 <b>{{ headcount.total }}</b></span>
      <span>门诊 <b>{{ headcount.outpatient }}</b></span>
      <span>住院 <b>{{ headcount.inpatient }}</b></span>
      <span>手工 <b>{{ headcount.manual }}</b></span>
      <span>待审例外 <b>{{ pendingExceptions }}</b></span>
    </div>

    <el-table :data="lines" size="small" max-height="320">
      <el-table-column prop="itemName" label="耗材" min-width="180" show-overflow-tooltip />
      <el-table-column prop="unit" label="单位" width="70" />
      <el-table-column label="当日应耗" width="110" align="right">
        <template #default="{ row }">{{ formatQuantity(row.quotaQuantity ?? row.presetQuantity) }}</template>
      </el-table-column>
      <el-table-column label="每人次份额" width="120" align="right">
        <template #default="{ row }">{{ perVisit(row) }}</template>
      </el-table-column>
      <el-table-column label="已通过补扣" width="120" align="right">
        <template #default="{ row }">{{ formatQuantity(row.actualQuantity) }}</template>
      </el-table-column>
      <el-table-column label="例外" width="100">
        <template #default="{ row }">{{ row.exceptionStatus || "无" }}</template>
      </el-table-column>
    </el-table>

    <el-table :data="patients" size="small" max-height="260">
      <el-table-column prop="patientName" label="患者" min-width="140" />
      <el-table-column prop="careType" label="类型" width="90" />
      <el-table-column prop="visitCount" label="计入人次" width="90" />
    </el-table>

    <el-table v-if="exceptions.length" :data="exceptions" size="small" max-height="220">
      <el-table-column prop="itemName" label="例外耗材" min-width="160" />
      <el-table-column prop="reason" label="原因" min-width="200" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column prop="approvedQuantity" label="已通过补扣" width="110" align="right" />
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import dayjs from "dayjs";
import { getInventoryPatientEvidenceApi, type InventoryPatientEvidence } from "@/api/modules/inventory";

const props = defineProps<{ departmentKey: string; today?: string; date?: string; embedded?: boolean }>();

const businessDate = ref(props.date || props.today || dayjs().format("YYYY-MM-DD"));
watch(
  () => props.date,
  value => {
    if (value && value !== businessDate.value) {
      businessDate.value = value;
      void load();
    }
  }
);
const loading = ref(false);
const evidence = ref<InventoryPatientEvidence>();
const headcount = computed(() => evidence.value?.headcount || { outpatient: 0, inpatient: 0, manual: 0, total: 0, source: "" });
const lines = computed(() => evidence.value?.lines || []);
const patients = computed(() => evidence.value?.patients || []);
const exceptions = computed(() => evidence.value?.exceptions || []);
const pendingExceptions = computed(() => exceptions.value.filter(item => item.status === "PENDING").length);

const formatQuantity = (value?: number | null) => Number(value || 0).toFixed(2);
const perVisit = (row: any) => {
  const total = Number(headcount.value.total || 0);
  if (!total) return "—";
  return (Number(row.quotaQuantity ?? row.presetQuantity ?? 0) / total).toFixed(2);
};

const load = async () => {
  loading.value = true;
  try {
    const result = await getInventoryPatientEvidenceApi({ departmentKey: props.departmentKey, date: businessDate.value });
    evidence.value = result.data;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "患者证据加载失败");
  } finally {
    loading.value = false;
  }
};

onMounted(load);
</script>

<style scoped lang="scss">
.patient-evidence {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px 20px;
}
.pe-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  flex-wrap: wrap;
  h2 {
    margin: 2px 0;
    font-size: 18px;
  }
  p {
    margin: 0;
    color: var(--hos-text-muted);
    font-size: 12px;
  }
}
.pe-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.pe-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  color: var(--hos-text-secondary);
  font-size: 12px;
  b {
    color: var(--hos-text-primary);
    font-variant-numeric: tabular-nums;
  }
}
</style>
