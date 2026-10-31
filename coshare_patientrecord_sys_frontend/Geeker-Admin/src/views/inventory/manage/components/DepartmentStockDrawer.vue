<template>
  <el-drawer
    :model-value="modelValue"
    title="科室库存与申领"
    size="72%"
    append-to-body
    @update:model-value="value => emit('update:modelValue', value)"
    @open="onOpen"
  >
    <div class="dept-stock" v-loading="loading">
      <el-tabs v-model="tab">
        <el-tab-pane label="库存" name="stock">
          <div class="filter-bar">
            <el-input v-model="keyword" clearable placeholder="搜索物资 / 规格" />
            <span class="hint">{{ stock.month }} · 进/用按自然月</span>
          </div>
          <el-table :data="filteredStock" border height="calc(100vh - 330px)" empty-text="还没有库存，请先向库管申领">
            <el-table-column prop="name" label="物资" min-width="140" />
            <el-table-column prop="spec" label="规格" min-width="100" show-overflow-tooltip />
            <el-table-column label="本月进" width="90">
              <template #default="{ row }">{{ formatNumber(row.monthInbound) }}</template>
            </el-table-column>
            <el-table-column label="本月用" width="90">
              <template #default="{ row }">{{ formatNumber(row.monthUsed) }}</template>
            </el-table-column>
            <el-table-column label="当前存" width="95">
              <template #default="{ row }">
                <strong :class="{ danger: row.lowStock }">{{ formatNumber(row.deptAvailable) }}</strong>
                <small> {{ row.baseUnit || row.unit }}</small>
              </template>
            </el-table-column>
            <el-table-column label="中心可发" width="95">
              <template #default="{ row }">{{ formatNumber(row.centralAvailable) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.lowStock" type="warning" effect="plain" size="small">低于预警</el-tag>
                <span v-else class="muted">正常</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="addToCart(row, row.suggestedQuantity || 1)">申领</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="申领" name="request">
          <div class="request-panel">
            <div class="request-tools">
              <el-select v-model="addItemId" filterable clearable placeholder="搜索并添加物资" class="add-select" @change="onAddItem">
                <el-option
                  v-for="item in selectableItems"
                  :key="item.itemId"
                  :label="`${item.name}${item.spec ? '（' + item.spec + '）' : ''}`"
                  :value="item.itemId"
                />
              </el-select>
              <span class="hint">低库存物资已按“本月已用 - 当前存”预填，可修改</span>
            </div>
            <el-table :data="cart" border empty-text="还没有申领物资">
              <el-table-column label="物资" min-width="150">
                <template #default="{ row }">{{ row.name }}</template>
              </el-table-column>
              <el-table-column prop="spec" label="规格" min-width="100" show-overflow-tooltip />
              <el-table-column label="当前存" width="90">
                <template #default="{ row }">{{ formatNumber(row.deptAvailable) }}</template>
              </el-table-column>
              <el-table-column label="申领数量" width="150">
                <template #default="{ row }">
                  <el-input-number v-model="row.quantity" :min="0.01" :precision="2" :controls="false" />
                </template>
              </el-table-column>
              <el-table-column prop="unit" label="单位" width="80" />
              <el-table-column label="操作" width="80">
                <template #default="{ $index }">
                  <el-button link type="danger" @click="cart.splice($index, 1)">移除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="request-foot">
              <el-input v-model="reason" clearable placeholder="申领说明（如有）" class="reason-input" />
              <el-button type="primary" :loading="submitting" :disabled="!cart.length" @click="submitRequest">
                提交申领（{{ cart.length }} 项）
              </el-button>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="我的申领" name="mine">
          <div class="filter-bar">
            <el-select v-model="requestStatus" clearable placeholder="状态">
              <el-option label="待审核" value="pending" />
              <el-option label="部分发放" value="partially_issued" />
              <el-option label="已发放" value="received" />
              <el-option label="已驳回" value="rejected" />
            </el-select>
          </div>
          <el-table :data="filteredRequests" border height="calc(100vh - 330px)" empty-text="还没有申领记录">
            <el-table-column prop="createdAt" label="申请时间" width="160">
              <template #default="{ row }">{{ row.createdAt?.slice(0, 16).replace("T", " ") }}</template>
            </el-table-column>
            <el-table-column prop="itemSummary" label="物资" min-width="220" show-overflow-tooltip />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="requestTone(row.status)" effect="plain" size="small">{{ requestLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="rejectReason" label="原因" min-width="140" show-overflow-tooltip />
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button v-if="row.status === 'pending'" link type="danger" @click="cancelRequest(row)">撤销</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import dayjs from "dayjs";
import { useUserStore } from "@/stores/modules/user";
import {
  cancelInventoryRequestApi,
  createInventoryRequestApi,
  getInventoryDbApi,
  getInventoryDepartmentStockApi,
  type InventoryRequest
} from "@/api/modules/inventory";

defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; changed: [] }>();

const userStore = useUserStore();
const tab = ref<"stock" | "request" | "mine">("stock");
const loading = ref(false);
const submitting = ref(false);
const keyword = ref("");
const addItemId = ref("");
const reason = ref("");
const requestStatus = ref("");
const stock = reactive<{ month: string; departmentName: string; items: any[] }>({ month: "", departmentName: "", items: [] });
const requests = ref<InventoryRequest[]>([]);
const cart = ref<Array<{ itemId: string; name: string; spec: string; unit: string; deptAvailable: number; quantity: number }>>([]);

const filteredStock = computed(() => {
  const term = keyword.value.trim().toLowerCase();
  return stock.items.filter(item => !term || `${item.name} ${item.spec}`.toLowerCase().includes(term));
});
const selectableItems = computed(() =>
  stock.items.filter(item => !cart.value.some(line => line.itemId === item.itemId))
);
const filteredRequests = computed(() =>
  requests.value.filter(request =>
    (!requestStatus.value || request.status === requestStatus.value) &&
    ["pending", "approved", "partially_issued", "received", "rejected", "cancelled"].includes(request.status)
  )
);

const formatNumber = (value?: number | string) => Number(value || 0).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
const requestLabel = (status: string) =>
  ({ pending: "待审核", approved: "已通过待发放", partially_issued: "部分发放", issued: "已发放", received: "已发放", rejected: "已驳回", cancelled: "已撤销" })[status] || status;
const requestTone = (status: string) =>
  status === "rejected" ? "danger" : status === "received" ? "success" : status === "partially_issued" ? "warning" : "info";

const onOpen = async () => {
  loading.value = true;
  try {
    const [stockResult, dbResult] = await Promise.all([getInventoryDepartmentStockApi(), getInventoryDbApi()]);
    const data = stockResult.data;
    stock.month = data?.month || "";
    stock.departmentName = data?.departmentName || "";
    stock.items = data?.items ?? [];
    requests.value = dbResult.data?.requests ?? [];
    cart.value = stock.items
      .filter(item => item.lowStock && Number(item.suggestedQuantity || 0) > 0)
      .map(item => ({
        itemId: item.itemId,
        name: item.name,
        spec: item.spec,
        unit: item.baseUnit || item.unit,
        deptAvailable: Number(item.deptAvailable || 0),
        quantity: Number(item.suggestedQuantity || 1)
      }));
    tab.value = cart.value.length ? "request" : "stock";
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取科室库存失败");
  } finally {
    loading.value = false;
  }
};

const addToCart = (item: any, quantity: number) => {
  const existing = cart.value.find(line => line.itemId === item.itemId);
  if (existing) {
    existing.quantity += quantity;
  } else {
    cart.value.push({
      itemId: item.itemId,
      name: item.name,
      spec: item.spec,
      unit: item.baseUnit || item.unit,
      deptAvailable: Number(item.deptAvailable || 0),
      quantity: Math.max(0.01, Number(quantity || 1))
    });
  }
  tab.value = "request";
};

const onAddItem = (itemId: string) => {
  if (!itemId) return;
  const item = stock.items.find(row => row.itemId === itemId);
  if (item) addToCart(item, 1);
  addItemId.value = "";
};

const submitRequest = async () => {
  if (!cart.value.length) return;
  submitting.value = true;
  try {
    await createInventoryRequestApi({
      department: stock.departmentName || userStore.userInfo.department,
      applicant: userStore.userInfo.name,
      owner: userStore.userInfo.name,
      reason: reason.value.trim() || "低库存补货",
      expectedUseWeek: dayjs().format("YYYY-MM-DD"),
      lines: cart.value.map(line => ({ itemId: line.itemId, quantity: Number(line.quantity) }))
    });
    ElMessage.success("申领已提交，等待中心库发放");
    reason.value = "";
    await onOpen();
    tab.value = "mine";
    emit("changed");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "申领提交失败");
  } finally {
    submitting.value = false;
  }
};

const cancelRequest = async (request: InventoryRequest) => {
  try {
    await ElMessageBox.confirm(`撤销申领“${request.itemSummary || request.itemId}”？`, "撤销申领", {
      confirmButtonText: "撤销",
      cancelButtonText: "取消",
      type: "warning"
    });
    await cancelInventoryRequestApi({ id: request.id, reason: "科室撤销" });
    ElMessage.success("已撤销");
    await onOpen();
    emit("changed");
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(error instanceof Error ? error.message : "撤销失败");
  }
};
</script>

<style scoped lang="scss">
.dept-stock {
  min-height: 420px;
}

.filter-bar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;

  .el-input {
    width: 240px;
  }
}

.hint {
  color: #9a9285;
  font-size: 12px;
}

.muted {
  color: #9a9285;
}

.danger {
  color: #c45454;
}

.request-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.request-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;

  .add-select {
    width: 300px;
  }
}

.request-foot {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: flex-end;

  .reason-input {
    flex: 1 1 300px;
  }
}
</style>
