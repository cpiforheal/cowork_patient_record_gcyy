<template>
  <section class="usage-workbench" v-loading="loading">
    <header class="usage-toolbar">
      <div class="usage-copy">
        <span class="eyebrow">耗材工作台</span>
        <h2>今日耗材使用汇总</h2>
        <p>按当天已记录耗材汇总；点击任意一行查看使用该耗材的患者名单和审核状态。</p>
      </div>
      <div class="usage-actions">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="load" />
        <el-select
          v-if="canViewAllDepartments"
          v-model="departmentKey"
          clearable
          placeholder="全院"
          class="department-select"
          @change="load"
        >
          <el-option v-for="department in departments" :key="department.key" :label="department.name" :value="department.key" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <div class="usage-summary">
      <div><small>耗材种类</small><strong>{{ totals.itemCount || 0 }}</strong></div>
      <div><small>使用总量</small><strong>{{ formatQuantity(totals.totalQuantity) }}</strong></div>
      <div><small>涉及患者</small><strong>{{ totals.patientCount || 0 }}</strong></div>
    </div>

    <el-table :data="items" class="usage-table" height="calc(100vh - 320px)" @row-click="openItem">
      <el-table-column prop="itemName" label="耗材" min-width="220" show-overflow-tooltip />
      <el-table-column prop="unit" label="单位" width="90" />
      <el-table-column label="使用总量" width="120" align="right">
        <template #default="{ row }">{{ formatQuantity(row.quantity) }}</template>
      </el-table-column>
      <el-table-column prop="patientCount" label="患者数" width="90" align="right" />
      <el-table-column label="已通过" width="110" align="right">
        <template #default="{ row }">{{ formatQuantity(row.approvedQuantity) }}</template>
      </el-table-column>
      <el-table-column label="待审核" width="110" align="right">
        <template #default="{ row }">{{ formatQuantity(row.pendingQuantity) }}</template>
      </el-table-column>
      <el-table-column width="100" align="right">
        <template #default>
          <el-button text type="primary">患者名单</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!items.length && !loading" :image-size="80" description="今天还没有耗材记录" />

    <el-drawer v-model="detailVisible" :title="`${selectedItem?.itemName || ''} · 患者名单`" size="70%" append-to-body>
      <div v-loading="detailLoading" class="patient-list">
        <el-table :data="patients">
          <el-table-column prop="patientName" label="患者" width="120">
            <template #default="{ row }">
              {{ row.patientName }}
              <el-tag v-if="row.attentionFlag" size="small" type="danger" effect="plain">重点</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="visitNo" label="就诊号" width="120" />
          <el-table-column prop="departmentName" label="科室" width="110" />
          <el-table-column prop="serviceName" label="服务" min-width="140" />
          <el-table-column label="用量" width="90" align="right">
            <template #default="{ row }">{{ formatQuantity(row.quantity) }}{{ selectedItem?.unit }}</template>
          </el-table-column>
          <el-table-column label="审核" width="100">
            <template #default="{ row }">
              <el-tag :type="lineStatusType(row.reviewStatus)" size="small" effect="plain">{{ lineStatusLabel(row.reviewStatus) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!patients.length && !detailLoading" description="该耗材今天没有患者记录" />
      </div>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import {
  getInventoryConsumableUsageApi,
  type InventoryConsumableUsageItem,
  type InventoryConsumableUsagePatient
} from "@/api/modules/inventory";

const props = defineProps<{
  canViewAllDepartments?: boolean;
  today?: string;
}>();

const departments = [
  { key: "physiotherapy", name: "理疗室" },
  { key: "laboratory", name: "检验科" },
  { key: "nursing", name: "护理部" },
  { key: "tcm", name: "中医科" },
  { key: "operating", name: "手术室" },
  { key: "anesthesia", name: "麻醉室" },
  { key: "endoscopy", name: "胃肠镜" },
  { key: "inspection", name: "检查室" },
  { key: "logistics", name: "后勤" },
  { key: "western-pharmacy", name: "西药房" },
  { key: "cashier", name: "收费室" },
  { key: "tcm-pharmacy", name: "中药房" }
];

const date = ref(props.today || new Date().toISOString().slice(0, 10));
const departmentKey = ref("");
const items = ref<InventoryConsumableUsageItem[]>([]);
const totals = ref({ itemCount: 0, totalQuantity: 0, patientCount: 0 });
const loading = ref(false);
const detailVisible = ref(false);
const detailLoading = ref(false);
const selectedItem = ref<InventoryConsumableUsageItem | null>(null);
const patients = ref<InventoryConsumableUsagePatient[]>([]);

const load = async () => {
  loading.value = true;
  try {
    const result = await getInventoryConsumableUsageApi({
      date: date.value,
      departmentKey: departmentKey.value || undefined
    });
    items.value = result.data?.items ?? [];
    totals.value = result.data?.totals ?? { itemCount: 0, totalQuantity: 0, patientCount: 0 };
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取耗材汇总失败");
  } finally {
    loading.value = false;
  }
};

const openItem = async (row: InventoryConsumableUsageItem) => {
  selectedItem.value = row;
  detailVisible.value = true;
  detailLoading.value = true;
  try {
    const result = await getInventoryConsumableUsageApi({
      date: date.value,
      departmentKey: departmentKey.value || undefined,
      itemId: row.itemId
    });
    patients.value = result.data?.patients ?? [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取患者名单失败");
  } finally {
    detailLoading.value = false;
  }
};

const formatQuantity = (value?: number | null) => {
  if (value === null || value === undefined) return "0";
  return Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
};

const lineStatusLabel = (status: string) => {
  if (status === "APPROVED") return "已通过";
  if (status === "REJECTED") return "已驳回";
  return "待审核";
};

const lineStatusType = (status: string): "success" | "warning" | "danger" | "info" => {
  if (status === "APPROVED") return "success";
  if (status === "REJECTED") return "danger";
  return "warning";
};

onMounted(() => {
  void load();
});
</script>

<style scoped lang="scss">
.usage-workbench {
  --hos-bg-primary: #fffdf9;
  --hos-bg-surface: #faf7f1;
  --hos-border-light: #f1ebe1;
  --hos-text-primary: #2f2d27;
  --hos-text-secondary: #6d675c;
  --hos-text-muted: #9a9285;
  --hos-primary: #347d68;
  --hos-primary-soft: #edf7f1;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
  min-height: calc(100vh - 150px);
  background: var(--hos-bg-surface);
}

.usage-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 18px 20px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 16px;

  h2 {
    margin: 4px 0 4px;
    color: var(--hos-text-primary);
    font-size: 20px;
    font-weight: 700;
  }

  p {
    margin: 0;
    color: var(--hos-text-secondary);
    font-size: 12px;
  }
}

.eyebrow {
  color: var(--hos-primary);
  font-weight: 600;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.usage-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.department-select {
  width: 140px;
}

.usage-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;

  > div {
    display: flex;
    flex-direction: column;
    gap: 3px;
    padding: 12px 16px;
    background: var(--hos-bg-primary);
    border: 1px solid var(--hos-border-light);
    border-radius: 14px;
  }

  small {
    color: var(--hos-text-muted);
    font-size: 12px;
  }

  strong {
    color: var(--hos-text-primary);
    font-size: 24px;
    font-variant-numeric: tabular-nums;
  }
}

.usage-table {
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;
}

.patient-list {
  min-height: 300px;
}

@media (max-width: 760px) {
  .usage-workbench {
    padding: 10px;
  }

  .usage-summary {
    grid-template-columns: 1fr;
  }
}
</style>
