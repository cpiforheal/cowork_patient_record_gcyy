<template>
  <section class="panel">
    <div class="panel-head">
      <div>
        <h2>科室周消耗</h2>
        <p>记录本周实际消耗、剩余数量和下周预计领用，用于 P1 趋势与异常管理。</p>
      </div>
      <el-button v-if="canCreate" type="primary" :icon="Plus" @click="$emit('create')">新增周消耗</el-button>
    </div>
    <el-table :data="rows" border>
      <el-table-column prop="weekNo" label="周次" width="130" />
      <el-table-column prop="department" label="科室" width="120" />
      <el-table-column prop="itemName" label="物资" min-width="150" />
      <el-table-column prop="consumedQuantity" label="本周消耗" width="110" />
      <el-table-column prop="remainingQuantity" label="科室剩余" width="110" />
      <el-table-column prop="nextWeekQuantity" label="下周预计" width="110" />
      <el-table-column prop="owner" label="负责人" width="110" />
      <el-table-column prop="abnormalReason" label="异常说明" min-width="220" />
      <el-table-column prop="confirmedAt" label="确认时间" width="160" />
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { Plus } from "@element-plus/icons-vue";
import type { WeeklyConsumption } from "@/api/modules/inventory";

type WeeklyRow = WeeklyConsumption & { itemName: string };

defineProps<{
  rows: WeeklyRow[];
  canCreate: boolean;
}>();

defineEmits<{
  create: [];
}>();
</script>

<style scoped lang="scss">
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;

  h2,
  p {
    margin: 0;
  }

  h2 {
    color: var(--inventory-text);
    font-size: 16px;
    line-height: 1.35;
  }

  p {
    margin-top: 4px;
    color: var(--inventory-muted);
    font-size: 13px;
  }
}
</style>
