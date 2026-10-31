<template>
  <div class="ops-page" v-loading="loading">
    <header class="page-head">
      <div>
        <span class="eyebrow">进销存 · 运维</span>
        <h1>进销存运维</h1>
      </div>
      <el-button :icon="Refresh" :loading="loading" aria-label="刷新" @click="refreshAll" />
    </header>

    <section class="health-grid">
      <button
        v-for="item in healthItems"
        :key="item.key"
        type="button"
        class="health-card"
        :class="{ 'is-active': typeFilter === item.type, 'is-warning': item.value }"
        @click="filterByType(item.type)"
      >
        <strong>{{ item.value }}</strong>
        <span>{{ item.label }}</span>
      </button>
    </section>

    <section class="exceptions-card">
      <div class="filter-row">
        <el-select v-model="typeFilter" clearable placeholder="异常类型" @change="loadExceptions">
          <el-option v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-input v-model="department" clearable placeholder="科室" @keyup.enter="loadExceptions" />
        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" @change="loadExceptions" />
        <el-button @click="loadExceptions">查询</el-button>
      </div>
      <el-table :data="exceptions" border height="calc(100vh - 390px)" empty-text="没有异常">
        <el-table-column prop="occurredAt" label="时间" width="160">
          <template #default="{ row }">{{ (row.occurredAt || "").slice(0, 16).replace("T", " ") }}</template>
        </el-table-column>
        <el-table-column prop="typeLabel" label="类型" width="110" />
        <el-table-column prop="department" label="科室" width="110" />
        <el-table-column prop="itemName" label="物资" min-width="140" />
        <el-table-column prop="documentNo" label="单号" min-width="140" show-overflow-tooltip />
        <el-table-column prop="reason" label="原因" min-width="220" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(row.actionPath)">去处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import {
  getInventoryOpsExceptionsApi,
  getInventoryOpsHealthApi,
  type InventoryOpsException,
  type InventoryOpsHealth
} from "@/api/modules/inventory";

const router = useRouter();
const loading = ref(false);
const health = ref<InventoryOpsHealth>({
  openingUnconfirmed: 0,
  mappingConflicts: 0,
  missingBatchOrExpiry: 0,
  lowStock: 0,
  expiringBatches: 0,
  pendingDeduction: 0,
  openExceptions: 0,
  negativeStock: 0
});
const exceptions = ref<InventoryOpsException[]>([]);
const typeFilter = ref("");
const department = ref("");
const dateRange = ref<string[]>([]);

const typeOptions = [
  { label: "待补扣", value: "PENDING_DEDUCTION" },
  { label: "负库存", value: "NEGATIVE_STOCK" },
  { label: "入库红冲", value: "INBOUND_REVERSAL" }
];

const healthItems = computed(() => [
  { key: "opening", label: "期初未确认", value: health.value.openingUnconfirmed, type: "" },
  { key: "mapping", label: "映射冲突", value: health.value.mappingConflicts, type: "" },
  { key: "batch", label: "缺批号/效期", value: health.value.missingBatchOrExpiry, type: "" },
  { key: "low", label: "中心低库存", value: health.value.lowStock, type: "" },
  { key: "expiring", label: "临期批次", value: health.value.expiringBatches, type: "" },
  { key: "pending", label: "待补扣", value: health.value.pendingDeduction, type: "PENDING_DEDUCTION" },
  { key: "exceptions", label: "异常任务", value: health.value.openExceptions, type: "" },
  { key: "negative", label: "负库存", value: health.value.negativeStock, type: "NEGATIVE_STOCK" }
]);

const loadHealth = async () => {
  const result = await getInventoryOpsHealthApi();
  health.value = result.data ?? health.value;
};

const loadExceptions = async () => {
  const result = await getInventoryOpsExceptionsApi({
    type: typeFilter.value || undefined,
    department: department.value || undefined,
    from: dateRange.value?.[0],
    to: dateRange.value?.[1]
  });
  exceptions.value = result.data ?? [];
};

const filterByType = (type: string) => {
  typeFilter.value = type;
  void loadExceptions();
};

const refreshAll = async () => {
  loading.value = true;
  try {
    await Promise.all([loadHealth(), loadExceptions()]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取运维数据失败");
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  void refreshAll();
});
</script>

<style scoped lang="scss">
.ops-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 20px;
  min-height: calc(100vh - 150px);
  background: #faf7f1;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;

  .eyebrow {
    color: #347d68;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.08em;
  }

  h1 {
    margin: 2px 0 0;
    color: #2f2d27;
    font-size: 22px;
  }
}

.health-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.health-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  align-items: flex-start;
  padding: 12px 14px;
  background: #fffdf9;
  border: 1px solid #efe8dd;
  border-radius: 12px;
  cursor: pointer;
  text-align: left;

  strong {
    color: #2f2d27;
    font-size: 22px;
    font-variant-numeric: tabular-nums;
  }

  span {
    color: #9a9285;
    font-size: 11px;
  }

  &.is-warning strong {
    color: #b45309;
  }

  &.is-active {
    border-color: #347d68;
    background: #edf7f1;
  }
}

.exceptions-card {
  padding: 12px 16px;
  background: #fffdf9;
  border: 1px solid #efe8dd;
  border-radius: 14px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;

  .el-input {
    width: 160px;
  }
}
</style>
