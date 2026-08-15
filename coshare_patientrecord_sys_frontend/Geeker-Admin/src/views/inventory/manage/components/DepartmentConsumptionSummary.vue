<template>
  <section class="department-summary">
    <div class="summary-toolbar">
      <div>
        <h2>全院汇总</h2>
        <p>查看各科室已保存的日核算草稿；这里只汇总试算结果，不产生库存流水。</p>
      </div>
      <div class="toolbar-actions">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>
    <el-alert v-if="error" type="warning" :closable="false" show-icon :title="error" />
    <el-table :data="rows" height="calc(100vh - 245px)" min-height="360" table-layout="fixed" empty-text="该日期还没有已保存的科室草稿">
      <el-table-column prop="departmentName" label="科室" min-width="150" />
      <el-table-column prop="lineCount" label="耗材行" width="100" />
      <el-table-column prop="dailyQuantity" label="日使用量合计" width="150" />
      <el-table-column prop="monthlyQuantity" label="月使用量合计" width="150" />
      <el-table-column prop="pricedMonthlyAmount" label="已核价月金额" width="150" />
      <el-table-column prop="updatedAt" label="最近保存" min-width="170" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }"><el-button link type="primary" @click="$emit('open-department', row.departmentKey)">打开</el-button></template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { Refresh } from "@element-plus/icons-vue";
import { getInventoryDepartmentDailyDraftSummaryApi, type InventoryDepartmentDailyDraft } from "@/api/modules/inventory";

const props = defineProps<{ today: string }>();
defineEmits<{ "open-department": [key: string] }>();
const date = ref(props.today);
const loading = ref(false);
const error = ref("");
const drafts = ref<InventoryDepartmentDailyDraft[]>([]);
const round = (value: number, digits: number) => Number(value.toFixed(digits));
const rows = computed(() =>
  drafts.value.map(draft => {
    let daily = 0;
    let monthly = 0;
    let priced: number | null = 0;
    (draft.lines || []).forEach(line => {
      const volume = Number(line.volumeOverride ?? draft.groupVolumes?.[line.serviceGroup] ?? 0);
      const dailyQuantity = Number(line.standardQuantity || 0) * volume;
      const monthlyQuantity = round(dailyQuantity * Number(draft.monthDays || 30), 2);
      daily += dailyQuantity;
      monthly += monthlyQuantity;
      if (line.unitPrice === null || line.unitPrice === undefined) priced = null;
      else if (priced !== null) priced += monthlyQuantity * line.unitPrice;
    });
    return {
      ...draft,
      lineCount: (draft.lines || []).length,
      dailyQuantity: daily.toFixed(6).replace(/\.0+$/, "").replace(/(\.\d*?)0+$/, "$1"),
      monthlyQuantity: round(monthly, 2).toFixed(2),
      pricedMonthlyAmount: priced === null ? "未核价" : `¥${round(priced, 2).toFixed(2)}`,
      updatedAt: draft.updatedAt ? draft.updatedAt.replace("T", " ").slice(0, 16) : "-"
    };
  })
);
const load = async () => {
  loading.value = true;
  error.value = "";
  try {
    const response = await getInventoryDepartmentDailyDraftSummaryApi(date.value);
    drafts.value = response.data.list || [];
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : "读取全院草稿汇总失败";
  } finally {
    loading.value = false;
  }
};
watch(() => props.today, value => { if (!date.value) date.value = value; });
watch(date, load);
onMounted(load);
defineExpose({ reload: load });
</script>

<style scoped lang="scss">
.department-summary { display: grid; gap: 16px; min-width: 0; }
.summary-toolbar, .toolbar-actions { display: flex; align-items: center; gap: 12px; }
.summary-toolbar { justify-content: space-between; flex-wrap: wrap; }
.summary-toolbar h2 { margin: 0; color: var(--inventory-text); font-size: 20px; }
.summary-toolbar p { margin: 5px 0 0; color: var(--inventory-muted); font-size: 13px; }
.toolbar-actions { flex-wrap: wrap; }
</style>
