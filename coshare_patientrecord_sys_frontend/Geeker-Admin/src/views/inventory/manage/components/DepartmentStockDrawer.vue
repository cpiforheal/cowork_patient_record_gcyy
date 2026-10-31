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
              <el-input
                v-model="searchKeyword"
                clearable
                placeholder="输入物资名称 / 拼音，回车添加"
                class="add-select"
                @keydown="onSearchKeydown"
              />
              <el-button @click="openPaste">批量粘贴</el-button>
              <el-button :loading="copying" @click="copyLastRequest">复制上次申领</el-button>
              <span class="hint">低库存已预填，重复物资自动相加</span>
            </div>
            <div v-if="searchResults.length" class="search-results">
              <button
                v-for="(item, index) in searchResults"
                :key="item.itemId"
                type="button"
                :class="{ 'is-active': index === searchIndex }"
                @click="pickSearchItem(item)"
              >
                <span>{{ item.name }}<small v-if="item.spec">（{{ item.spec }}）</small></span>
                <em>{{ item.issueUnit || item.unit }}</em>
              </button>
            </div>
            <el-table :data="cart" border empty-text="还没有申领物资">
              <el-table-column label="物资" min-width="150">
                <template #default="{ row }">{{ row.name }}</template>
              </el-table-column>
              <el-table-column prop="spec" label="规格" min-width="100" show-overflow-tooltip />
              <el-table-column label="当前存" width="90">
                <template #default="{ row }">{{ formatNumber(row.deptAvailable) }}</template>
              </el-table-column>
              <el-table-column :label="`申领数量（领用单位）`" width="160">
                <template #default="{ row }">
                  <el-input-number v-model="row.quantity" :min="0.01" :precision="2" :controls="false" />
                  <small v-if="row.mergeCount > 1" class="merged" :title="row.sources.join('；')">由 {{ row.mergeCount }} 行合并</small>
                </template>
              </el-table-column>
              <el-table-column label="折算" width="110">
                <template #default="{ row }">
                  {{ formatNumber(Number(row.quantity || 0) * Number(row.factor || 1)) }} {{ row.baseUnit }}
                </template>
              </el-table-column>
              <el-table-column prop="unit" label="单位" width="70" />
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
  
    <el-dialog v-model="pasteVisible" title="批量粘贴申领" width="760px" append-to-body>
      <el-input
        v-model="pasteText"
        type="textarea"
        :rows="5"
        placeholder="每行一条：物资名称 [Tab/逗号] 数量（领用单位）"
      />
      <div class="paste-actions">
        <el-button @click="previewPaste">解析预览</el-button>
        <span>同名多规格需在预览中选择</span>
      </div>
      <el-table v-if="pasteRows.length" :data="pasteRows" border size="small" max-height="320">
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }"><el-input v-model="row.name" @change="rematchPaste" /></template>
        </el-table-column>
        <el-table-column label="数量" width="110">
          <template #default="{ row }"><el-input-number v-model="row.quantity" :min="0.01" :precision="2" :controls="false" /></template>
        </el-table-column>
        <el-table-column label="匹配" min-width="200">
          <template #default="{ row }">
            <el-select v-if="row.candidates?.length" :model-value="row.item?.itemId || ''" placeholder="选择规格" @change="(id: string) => { const c = row.candidates?.find(x => x.itemId === id); if (c) { row.item = c; row.candidates = []; row.error = ''; } }">
              <el-option v-for="candidate in row.candidates" :key="candidate.itemId" :label="`${candidate.name}${candidate.spec ? '（' + candidate.spec + '）' : ''}`" :value="candidate.itemId" />
            </el-select>
            <span v-else-if="row.item">{{ row.item.name }}<small v-if="row.item.spec">（{{ row.item.spec }}）</small></span>
            <span v-else class="danger">{{ row.error || "未匹配" }}</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <span class="paste-summary">{{ pasteRows.filter(row => row.item).length }} / {{ pasteRows.length }} 行可导入</span>
        <el-button @click="pasteVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!pasteRows.length || pasteRows.some(row => !row.item)" @click="importPaste">导入到申领</el-button>
      </template>
    </el-dialog>
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
import { conversionFactor, matchBulkRows, parseBulkText, type ParsedBulkRow } from "../composables/bulkItemImport";

defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; changed: [] }>();

const userStore = useUserStore();
const tab = ref<"stock" | "request" | "mine">("stock");
const loading = ref(false);
const submitting = ref(false);
const keyword = ref("");
const searchKeyword = ref("");
const searchIndex = ref(0);
const reason = ref("");
const pasteVisible = ref(false);
const pasteText = ref("");
const pasteRows = ref<ParsedBulkRow[]>([]);
const copying = ref(false);
const requestStatus = ref("");
const stock = reactive<{ month: string; departmentName: string; items: any[] }>({ month: "", departmentName: "", items: [] });
const requests = ref<InventoryRequest[]>([]);
const cart = ref<Array<{
  itemId: string; name: string; spec: string; unit: string; baseUnit: string;
  deptAvailable: number; quantity: number; factor: number; mergeCount: number; sources: string[];
}>>([]);

const filteredStock = computed(() => {
  const term = keyword.value.trim().toLowerCase();
  return stock.items.filter(item => !term || `${item.name} ${item.spec}`.toLowerCase().includes(term));
});
const searchResults = computed(() => {
  const term = searchKeyword.value.trim().toLowerCase();
  if (!term) return [];
  return stock.items
    .filter(item => `${item.name} ${item.spec || ""}`.toLowerCase().includes(term))
    .slice(0, 8);
});
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
    cart.value = [];
    stock.items
      .filter(item => item.lowStock && Number(item.suggestedQuantity || 0) > 0)
      .forEach(item => addToCart(item, issueQuantityOf(item, Number(item.suggestedQuantity)), "低库存预填"));
    tab.value = cart.value.length ? "request" : "stock";
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取科室库存失败");
  } finally {
    loading.value = false;
  }
};

const issueQuantityOf = (item: any, baseQuantity: number) => {
  const factor = conversionFactor(item);
  return Math.max(0.01, Number(baseQuantity || 0) / factor);
};

const addToCart = (item: any, quantity: number, source = "手工添加") => {
  const existing = cart.value.find(line => line.itemId === item.itemId);
  if (existing) {
    existing.quantity = Number(existing.quantity || 0) + Number(quantity || 0);
    existing.mergeCount += 1;
    existing.sources.push(source);
  } else {
    cart.value.push({
      itemId: item.itemId,
      name: item.name,
      spec: item.spec,
      unit: item.issueUnit || item.unit,
      baseUnit: item.baseUnit || item.unit,
      deptAvailable: Number(item.deptAvailable || 0),
      quantity: Math.max(0.01, Number(quantity || 1)),
      factor: conversionFactor(item),
      mergeCount: 1,
      sources: [source]
    });
  }
  tab.value = "request";
};

const pickSearchItem = (item: any) => {
  addToCart(item, 1, "搜索添加");
  searchKeyword.value = "";
  searchIndex.value = 0;
};

const onSearchKeydown = (event: KeyboardEvent) => {
  if (!searchResults.value.length) return;
  if (event.key === "ArrowDown") {
    event.preventDefault();
    searchIndex.value = (searchIndex.value + 1) % searchResults.value.length;
  } else if (event.key === "ArrowUp") {
    event.preventDefault();
    searchIndex.value = (searchIndex.value - 1 + searchResults.value.length) % searchResults.value.length;
  } else if (event.key === "Enter") {
    event.preventDefault();
    pickSearchItem(searchResults.value[searchIndex.value] || searchResults.value[0]);
  } else if (event.key === "Escape") {
    searchKeyword.value = "";
  }
};

const openPaste = () => {
  pasteText.value = "";
  pasteRows.value = [];
  pasteVisible.value = true;
};

const previewPaste = () => {
  const options = stock.items.map(item => ({
    itemId: item.itemId,
    name: item.name,
    spec: item.spec,
    issueUnit: item.issueUnit,
    baseUnit: item.baseUnit,
    conversionFactor: item.conversionFactor
  }));
  pasteRows.value = matchBulkRows(parseBulkText(pasteText.value, false), options);
};

const rematchPaste = () => {
  const options = stock.items.map(item => ({
    itemId: item.itemId,
    name: item.name,
    spec: item.spec,
    issueUnit: item.issueUnit,
    baseUnit: item.baseUnit,
    conversionFactor: item.conversionFactor
  }));
  pasteRows.value = matchBulkRows(
    pasteRows.value.map(row => ({ ...row, item: undefined, candidates: [], error: undefined })),
    options
  );
};

const importPaste = () => {
  const ready = pasteRows.value.filter(row => row.item && row.quantity > 0 && !row.error);
  if (!ready.length) return ElMessage.warning("没有可导入的匹配行");
  ready.forEach(row => {
    const item = stock.items.find(entry => entry.itemId === row.item!.itemId);
    if (item) addToCart(item, row.quantity, "批量粘贴");
  });
  pasteVisible.value = false;
  ElMessage.success(`已导入 ${ready.length} 行，重复物资数量已合并`);
};

const copyLastRequest = async () => {
  const last = requests.value
    .filter(request => request.lines?.length && request.status !== "cancelled")
    .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))[0];
  if (!last) return ElMessage.info("还没有可复制的申领");
  copying.value = true;
  try {
    (last.lines ?? []).forEach(line => {
      const item = stock.items.find(entry => entry.itemId === line.itemId);
      if (item) addToCart(item, issueQuantityOf(item, Number(line.quantity || 0)), "复制上次申领");
    });
    ElMessage.success("已复制上次申领，重复物资数量已合并");
  } finally {
    copying.value = false;
  }
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
      lines: cart.value.map(line => ({ itemId: line.itemId, quantity: Number((Number(line.quantity) * line.factor).toFixed(2)) }))
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

.search-results {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fffdf9;
  border: 1px solid #efe8dd;
  border-radius: 10px;

  button {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 7px 10px;
    color: #2f2d27;
    background: transparent;
    border: 0;
    cursor: pointer;
    text-align: left;

    small {
      color: #9a9285;
    }

    em {
      color: #9a9285;
      font-style: normal;
      font-size: 11px;
    }

    &:hover,
    &.is-active {
      background: #edf7f1;
    }
  }
}

.merged {
  display: block;
  color: #b45309;
  font-size: 10px;
}

.paste-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 8px 0;

  span {
    color: #9a9285;
    font-size: 11px;
  }
}

.paste-summary {
  margin-right: auto;
  color: #9a9285;
  font-size: 12px;
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
