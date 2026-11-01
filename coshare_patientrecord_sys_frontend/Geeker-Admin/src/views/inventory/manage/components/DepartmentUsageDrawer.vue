<template>
  <el-drawer
    :model-value="modelValue"
    title="今日已用耗材"
    size="64%"
    append-to-body
    @update:model-value="value => emit('update:modelValue', value)"
    @open="load"
  >
    <div class="usage-drawer" v-loading="loading">
      <div class="usage-head">
        <strong>{{ date }} · {{ departmentName || "本科室" }}</strong>
        <span>{{ totals.itemCount || 0 }} 种 · 合计 {{ formatNumber(totals.totalQuantity) }} · {{ totals.patientCount || 0 }} 位患者</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>

      <el-table :data="items" border height="300" empty-text="今天还没有已录耗材" @row-click="openItem">
        <el-table-column prop="itemName" label="耗材" min-width="180" />
        <el-table-column prop="unit" label="单位" width="80" />
        <el-table-column label="使用总量" width="110">
          <template #default="{ row }">{{ formatNumber(row.quantity) }}</template>
        </el-table-column>
        <el-table-column prop="patientCount" label="患者数" width="90" />
        <el-table-column label="已通过" width="100">
          <template #default="{ row }">{{ formatNumber(row.approvedQuantity) }}</template>
        </el-table-column>
        <el-table-column label="待审核" width="100">
          <template #default="{ row }">{{ formatNumber(row.pendingQuantity) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openItem(row)">患者明细</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="selectedItem" class="patient-block">
        <div class="patient-head">
          <strong>{{ selectedItem.itemName }} · 使用患者</strong>
          <span>{{ patients.length }} 位</span>
        </div>
        <el-table :data="patients" border size="small" empty-text="该耗材今天没有患者使用记录">
          <el-table-column prop="patientName" label="患者" min-width="130" />
          <el-table-column prop="visitNo" label="就诊号" min-width="120" />
          <el-table-column prop="departmentName" label="科室" width="110" />
          <el-table-column prop="serviceName" label="服务" min-width="120" show-overflow-tooltip />
          <el-table-column label="用量" width="90">
            <template #default="{ row }">{{ formatNumber(row.quantity) }} {{ selectedItem.unit }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="statusTone(row.reviewStatus)">
                {{ statusLabel(row.reviewStatus) }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <el-empty v-else :image-size="60" description="点上方耗材查看具体患者" />
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import {
  getInventoryConsumableUsageApi,
  type InventoryConsumableUsageItem,
  type InventoryConsumableUsagePatient
} from "@/api/modules/inventory";

const props = defineProps<{ modelValue: boolean; departmentKey: string; departmentName?: string; date: string }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean] }>();

const loading = ref(false);
const items = ref<InventoryConsumableUsageItem[]>([]);
const patients = ref<InventoryConsumableUsagePatient[]>([]);
const selectedItem = ref<InventoryConsumableUsageItem | null>(null);
const totals = reactive({ itemCount: 0, totalQuantity: 0, patientCount: 0 });

const formatNumber = (value?: number | string) => Number(value || 0).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
const statusLabel = (status: string) =>
  status === "APPROVED" ? "已通过" : status === "REJECTED" ? "已驳回" : "待审核";
const statusTone = (status: string) => (status === "APPROVED" ? "success" : status === "REJECTED" ? "danger" : "warning");

const load = async () => {
  loading.value = true;
  selectedItem.value = null;
  patients.value = [];
  try {
    const result = await getInventoryConsumableUsageApi({ date: props.date, departmentKey: props.departmentKey });
    items.value = result.data?.items ?? [];
    Object.assign(totals, result.data?.totals ?? { itemCount: 0, totalQuantity: 0, patientCount: 0 });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取今日耗材使用失败");
  } finally {
    loading.value = false;
  }
};

const openItem = async (row: InventoryConsumableUsageItem) => {
  selectedItem.value = row;
  try {
    const result = await getInventoryConsumableUsageApi({
      date: props.date,
      departmentKey: props.departmentKey,
      itemId: row.itemId
    });
    patients.value = result.data?.patients ?? [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取患者明细失败");
  }
};
</script>

<style scoped lang="scss">
.usage-drawer {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.usage-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: baseline;

  strong {
    color: #2f2d27;
    font-size: 14px;
  }

  span {
    color: #9a9285;
    font-size: 12px;
  }

  .el-button {
    margin-left: auto;
  }
}

.patient-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: 8px;
  border-top: 1px solid #efe8dd;
}

.patient-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;

  strong {
    color: #2f2d27;
    font-size: 13px;
  }

  span {
    color: #9a9285;
    font-size: 11px;
  }
}
</style>
