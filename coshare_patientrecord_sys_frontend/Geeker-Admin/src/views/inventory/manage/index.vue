<template>
  <div class="inventory-page">
    <section class="inventory-command">
      <div class="command-title">
        <span>{{ currentTabProfile.kicker }}</span>
        <h1>{{ currentTabProfile.title }}</h1>
      </div>
      <div class="command-actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadInventory">刷新</el-button>
        <el-button
          v-for="action in currentTabActions"
          :key="action.label"
          v-bind="action.buttonProps"
          @click="runTabAction(action.action)"
        >
          {{ action.label }}
        </el-button>
      </div>
    </section>

    <section v-if="activeTab !== 'overview'" class="status-ribbon">
      <div class="ribbon-lead">
        <span>{{ currentTabProfile.taskLabel }}</span>
        <strong>{{ currentTabProfile.taskTitle }}</strong>
      </div>
      <button
        v-for="stat in currentTabStats"
        :key="stat.label"
        class="ribbon-metric"
        :class="stat.tone"
        @click="goTab(stat.tab || activeTab)"
      >
        <span>{{ stat.label }}</span>
        <strong>{{ stat.value }}</strong>
      </button>
    </section>

    <el-alert
      v-if="activeTab === 'items' && !hasInventoryAuth('inventory:issue')"
      class="inventory-readonly-alert"
      title="物资档案当前为只读模式"
      description="您可以查看物资名称、规格和预警信息；新增、编辑及入库操作仅向管理员和质控人员开放。"
      type="info"
      :closable="false"
      show-icon
    />

    <nav class="module-switcher" aria-label="进销存二级功能">
      <button
        v-for="item in visibleTabNavItems"
        :key="item.tab"
        :class="{ active: activeTab === item.tab }"
        @click="goTab(item.tab)"
      >
        <span>{{ item.title }}</span>
      </button>
    </nav>

    <div v-loading="loading" class="inventory-workspace" element-loading-text="正在同步库存...">
      <div v-if="initialInventoryLoading" class="inventory-loading-skeleton">
        <el-skeleton animated :rows="12" />
      </div>

      <transition v-else name="inventory-fade" mode="out-in">
        <div :key="activeTab" class="workspace-pane">
          <template v-if="activeTab === 'overview'">
            <OperationsHubPanel
              v-model:active-center="workspaceCenter"
              :workbench="inventoryWorkbench"
              :balances="locationBalances"
              :exceptions="inventoryExceptions"
              :consumptions="inventoryConsumptions"
              :weekly-suggestions="inventoryWorkbench?.weeklySuggestions || []"
              :weekly-suggestions-ready="Array.isArray(inventoryWorkbench?.weeklySuggestions)"
              :todo-rows="todoRows"
              :fallback-pending-approval="pendingRequestRows.length"
              :fallback-pending-issue="approvedRequestRows.length + partiallyIssuedRequestRows.length"
              :fallback-pending-receipt="issuedRequestRows.length"
              :fallback-low-stock="lowStockRows.length"
              :fallback-expiry-soon="expirySoonRows.length"
              :extended-data-ready="extendedDataReady"
              :extended-data-errors="extendedDataErrors"
              :accessible-tabs="visibleTabNavItems.map(item => item.tab)"
              :can-inbound="hasInventoryAuth('inventory:issue')"
              :can-request="hasInventoryAuth('inventory:request')"
              :can-approve="hasInventoryAuth('inventory:approve')"
              :can-issue="hasInventoryAuth('inventory:issue')"
              :can-receive="hasInventoryAuth('inventory:receive')"
              :can-count="hasInventoryAuth('inventory:count')"
              :can-report="canExportDepartmentUsage"
              :report-loading="reportLoading"
              :department-options="reportDepartmentOptions"
              :item-options="reportItemOptions"
              :category-options="categoryFilterOptions"
              @go-tab="goTab"
              @open-todo="openTodo"
              @workflow="handleWorkflowStep"
              @download-report="downloadDepartmentUsageReport"
            />
          </template>

          <template v-else-if="activeTab === 'executive'">
            <ExecutivePanel
              :executive-signal="executiveSignal"
              :request-closure-rate="requestClosureRate"
              :signature-count="executiveSignatureCount"
              :urgent-count="executiveUrgentCount"
              :risk-text="executiveRiskText"
              :attention-count="executiveAttentionCount"
              :overdue-attention-count="executiveOverdueAttentionCount"
              :key-metrics="executiveKeyMetrics"
              :department-consumption-top="departmentConsumptionTop"
              :max-department-consumption="maxDepartmentConsumption"
              :stock-preview-rows="executiveStockPreviewRows"
              :todo-rows="todoRows"
              :risk-flow="executiveRiskFlow"
              :can-view-all-departments="canViewAllDepartments"
              :current-department="currentDepartment"
              :todo-age="executiveTodoAge"
              @go-tab="goTab"
              @open-todo="openTodo"
            />
          </template>

          <template v-else-if="activeTab === 'stock'">
            <StockPanel
              v-model:keyword="stockFilters.keyword"
              v-model:category="stockFilters.category"
              v-model:status="stockFilters.status"
              :rows="filteredStockRows"
              :batch-rows="batchRows"
              :category-options="categoryFilterOptions"
              :can-export="hasInventoryAuth('inventory:export')"
              :can-manage="hasInventoryAuth('inventory:issue')"
              @export="exportCsv(stockRows, 'inventory-stock.csv')"
              @inbound="openInboundDialog"
              @edit="openItemDialog"
            />
          </template>

          <template v-else-if="activeTab === 'items'">
            <ItemsPanel
              v-model:keyword="itemFilters.keyword"
              v-model:category="itemFilters.category"
              :rows="filteredItemRows"
              :category-options="categoryFilterOptions"
              :can-manage="hasInventoryAuth('inventory:issue')"
              @create="openItemDialog()"
              @edit="openItemDialog"
            />
          </template>

          <template v-else-if="activeTab === 'requests'">
            <RequestsPanel
              v-model:keyword="requestFilters.keyword"
              v-model:status="requestFilters.status"
              v-model:department="requestFilters.department"
              v-model:date-range="requestFilters.dateRange"
              :rows="filteredRequestRows"
              :department-options="departmentOptions"
              :can-create="hasInventoryAuth('inventory:request')"
              :can-approve="hasInventoryAuth('inventory:approve')"
              :can-issue="hasInventoryAuth('inventory:issue')"
              :can-receive="hasInventoryAuth('inventory:receive')"
              :can-cancel="hasInventoryAuth('inventory:request')"
              :row-class-name="requestRowClassName"
              @create="openRequestDialog()"
              @approve="approveRequest"
              @issue="openIssueDialog"
              @receive="receiveRequest"
              @reject="rejectRequest"
              @cancel="cancelRequest"
              @void="voidRequest"
            />
          </template>

          <template v-else-if="activeTab === 'weekly'">
            <WeeklyPanel :rows="weeklyRows" :can-create="hasInventoryAuth('inventory:request')" @create="openWeeklyDialog" />
          </template>

          <template v-else-if="activeTab === 'controls'">
            <ControlsPanel
              ref="controlsPanelRef"
              :count-rows="countRows"
              :items="db.items"
              :return-form="returnForm"
              :return-form-rules="returnFormRules"
              :return-type-options="availableReturnTypeOptions"
              :department-options="departmentOptions"
              :can-create-count="hasInventoryAuth('inventory:count')"
              :can-submit-return-or-scrap="canSubmitReturnOrScrap"
              :saving="saving"
              :batches-for-item="batchesForItem"
              :batch-label="batchLabel"
              @create-count="openCountDialog"
              @submit-return="submitReturnOrScrap"
              @update:return-form="Object.assign(returnForm, $event)"
            />
          </template>

          <template v-else-if="activeTab === 'packages'">
            <PackagePanel
              :packages="visiblePackages"
              :events="visibleConsumptionEvents"
              :items="db.items"
              :department-options="departmentOptions"
              :can-manage="canManagePackages"
              :saving="saving"
              @save="savePackage"
              @enable="enablePackage"
              @disable="disablePackage"
              @retry="retryConsumptionEvent"
            />
          </template>

          <template v-else-if="activeTab === 'trace'">
            <TracePanel
              v-model:keyword="traceFilters.keyword"
              v-model:type="traceFilters.type"
              v-model:department="traceFilters.department"
              v-model:date-range="traceFilters.dateRange"
              :rows="filteredTraceRows"
              :department-options="departmentOptions"
              :can-export="hasInventoryAuth('inventory:export')"
              @export="exportCsv(traceRows, 'inventory-trace.csv')"
            />
          </template>
        </div>
      </transition>
    </div>

    <el-dialog v-model="itemDialogVisible" :title="itemForm.id ? '编辑物资' : '新增物资'" width="640px" destroy-on-close>
      <el-form ref="itemFormRef" :model="itemForm" :rules="itemFormRules" label-width="108px" status-icon>
        <el-form-item label="物资名称" prop="name">
          <el-input v-model="itemForm.name" placeholder="例如 一次性手套" />
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-select v-model="itemForm.category" filterable allow-create placeholder="请选择或输入分类">
            <el-option v-for="item in categoryOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="itemForm.spec" placeholder="例如 M号 / 100只/盒" />
        </el-form-item>
        <el-form-item label="单位" prop="unit">
          <el-select v-model="itemForm.unit" filterable allow-create placeholder="请选择或输入单位">
            <el-option v-for="item in unitOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="预警线">
          <el-input-number v-model="itemForm.lowStockThreshold" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="默认位置">
          <el-input v-model="itemForm.location" placeholder="例如 一楼库房 A 架" />
        </el-form-item>
        <el-form-item label="管理要求">
          <el-checkbox v-model="itemForm.batchRequired">需要批号</el-checkbox>
          <el-checkbox v-model="itemForm.expiryRequired">需要效期</el-checkbox>
          <el-checkbox v-model="itemForm.sensitive">敏感物资</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveItem">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="inboundDialogVisible" title="物资入库" width="620px" destroy-on-close>
      <el-form ref="inboundFormRef" :model="inboundForm" :rules="inboundFormRules" label-width="100px" status-icon>
        <el-form-item label="物资" prop="itemId">
          <el-select v-model="inboundForm.itemId" filterable placeholder="请选择物资">
            <el-option v-for="item in db.items" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量" prop="quantity">
          <el-input-number v-model="inboundForm.quantity" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="批号">
          <el-input v-model="inboundForm.batchNo" placeholder="有批号则填写" />
        </el-form-item>
        <el-form-item label="有效期">
          <el-date-picker v-model="inboundForm.expiryDate" value-format="YYYY-MM-DD" type="date" placeholder="选择有效期" />
        </el-form-item>
        <el-form-item label="存放位置">
          <el-input v-model="inboundForm.location" placeholder="默认带出物资位置，可修改" />
        </el-form-item>
        <el-form-item label="来源说明">
          <el-input v-model="inboundForm.source" type="textarea" :rows="3" placeholder="例如 月度采购入库 / 上级调拨" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inboundDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveInbound">保存入库</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="requestDialogVisible" title="新增科室申领" width="760px" destroy-on-close>
      <el-form ref="requestFormRef" :model="requestForm" :rules="requestFormRules" label-width="112px" status-icon>
        <el-form-item label="申领科室" prop="department">
          <el-select v-model="requestForm.department" filterable allow-create placeholder="请选择或输入科室">
            <el-option v-for="item in departmentOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="申领明细" required>
          <div class="request-lines-editor">
            <div v-for="(line, index) in requestForm.lines" :key="line.localId" class="request-line-editor">
              <el-select v-model="line.itemId" filterable placeholder="选择物资">
                <el-option v-for="item in db.items" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
              <el-input-number v-model="line.quantity" :min="0" :precision="2" />
              <span>{{ itemUnit(line.itemId) }}</span>
              <el-button link type="danger" :disabled="requestForm.lines.length === 1" @click="removeRequestLine(index)">
                删除
              </el-button>
            </div>
            <el-button plain :icon="Plus" @click="addRequestLine()">添加一项物资</el-button>
          </div>
        </el-form-item>
        <el-form-item label="申请人">
          <el-input v-model="requestForm.applicant" />
        </el-form-item>
        <el-form-item label="负责人" prop="owner">
          <el-input v-model="requestForm.owner" />
        </el-form-item>
        <el-form-item label="预计使用周">
          <el-date-picker v-model="requestForm.expectedUseWeek" value-format="YYYY-[W]ww" type="week" format="YYYY 第 ww 周" />
        </el-form-item>
        <el-form-item label="申请理由" prop="reason">
          <el-input v-model="requestForm.reason" type="textarea" :rows="3" placeholder="例如 门诊量增加，下周预计消耗上升" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="requestDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRequest">提交申领</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="issueDialogVisible" title="发放物资" width="680px" destroy-on-close>
      <el-form ref="issueFormRef" :model="issueForm" :rules="issueFormRules" label-width="100px" status-icon>
        <el-form-item label="指定批次">
          <el-select v-model="issueForm.batchId" clearable filterable placeholder="不选则系统按效期自动拆批">
            <el-option v-for="batch in activeRequestBatches" :key="batch.id" :label="batchLabel(batch)" :value="batch.id" />
          </el-select>
          <div class="form-hint">建议不指定批次，系统会优先使用更早到期的库存；单批不足时会自动跨批次发放。</div>
        </el-form-item>
        <el-form-item label="发放明细" required>
          <div class="issue-lines-editor">
            <div v-for="line in issueForm.lines" :key="line.id" class="issue-line-editor">
              <div>
                <strong>{{ itemName(line.itemId) }}</strong>
                <small>剩余 {{ line.remaining }} {{ itemUnit(line.itemId) }}</small>
              </div>
              <el-input-number v-model="line.issuedQuantity" :min="0" :max="line.remaining" :precision="2" />
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="issueRequest">确认发放</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="weeklyDialogVisible" title="确认周计划" width="640px" destroy-on-close>
      <el-form ref="weeklyFormRef" :model="weeklyForm" :rules="weeklyFormRules" label-width="112px" status-icon>
        <el-form-item label="周次" prop="weekNo">
          <el-date-picker v-model="weeklyForm.weekNo" value-format="YYYY-[W]ww" type="week" format="YYYY 第 ww 周" />
        </el-form-item>
        <el-form-item label="科室" prop="department">
          <el-select v-model="weeklyForm.department" filterable allow-create placeholder="请选择或输入科室">
            <el-option v-for="item in departmentOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="物资" prop="itemId">
          <el-select v-model="weeklyForm.itemId" filterable placeholder="请选择物资">
            <el-option v-for="item in db.items" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <div v-if="selectedWeeklyItem" class="weekly-assist">
          <div>
            <span>实际耗用</span>
            <strong>{{ selectedWeeklySuggestion?.actualConsumption || 0 }} {{ selectedWeeklyItem.unit }}</strong>
          </div>
          <div>
            <span>科室结存</span>
            <strong>{{ selectedWeeklySuggestion?.availableQuantity ?? weeklyItemStock }} {{ selectedWeeklyItem.unit }}</strong>
          </div>
          <div>
            <span>系统建议</span>
            <strong>{{ selectedWeeklySuggestion?.suggestedQuantity || 0 }} {{ selectedWeeklyItem.unit }}</strong>
          </div>
        </div>
        <el-form-item label="最终调整量">
          <el-input-number v-model="weeklyForm.adjustedQuantity" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="weeklyForm.owner" />
        </el-form-item>
        <el-form-item label="异常说明">
          <el-input v-model="weeklyForm.abnormalReason" type="textarea" :rows="3" placeholder="用量明显波动时填写原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="weeklyDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveWeekly">确认计划</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="countDialogVisible" title="库存盘点" width="580px" destroy-on-close>
      <el-form ref="countFormRef" :model="countForm" :rules="countFormRules" label-width="100px" status-icon>
        <el-form-item label="物资" prop="itemId">
          <el-select v-model="countForm.itemId" filterable placeholder="请选择物资" @change="countForm.batchId = ''">
            <el-option v-for="item in db.items" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="批次">
          <el-select v-model="countForm.batchId" clearable filterable placeholder="可不选，系统自动选择或补录">
            <el-option
              v-for="batch in batchesForItem(countForm.itemId)"
              :key="batch.id"
              :label="batchLabel(batch)"
              :value="batch.id"
            />
          </el-select>
          <div class="form-hint">首次盘点没有批次时可直接填写实盘数量，系统会自动生成盘点补录批次。</div>
        </el-form-item>
        <el-form-item label="实盘数量" prop="actualQuantity">
          <el-input-number v-model="countForm.actualQuantity" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="差异原因">
          <el-input v-model="countForm.reason" type="textarea" :rows="3" placeholder="有差异时必须说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="countDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCount">保存盘点</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="inventoryManage">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from "element-plus";
import { Download, Plus, Refresh } from "@element-plus/icons-vue";
import { useRoute, useRouter } from "vue-router";
import {
  approveInventoryRequestApi,
  cancelInventoryRequestApi,
  countInventoryApi,
  createInventoryRequestApi,
  downloadDepartmentUsageReportApi,
  getInventoryConsumptionsApi,
  getInventoryDbApi,
  getInventoryExceptionsApi,
  getInventoryLocationBalancesApi,
  getInventoryWorkbenchApi,
  inboundInventoryApi,
  issueInventoryRequestApi,
  receiveInventoryRequestApi,
  rejectInventoryRequestApi,
  returnOrScrapInventoryApi,
  saveInventoryItemApi,
  saveWeeklyConsumptionApi,
  saveInventoryPackageApi,
  enableInventoryPackageApi,
  disableInventoryPackageApi,
  retryInventoryConsumptionEventApi,
  voidInventoryRequestApi,
  type InventoryBatch,
  type InventoryConsumptionRecord,
  type InventoryDb,
  type InventoryException,
  type InventoryItem,
  type InventoryLocationBalance,
  type InventoryRequest,
  type InventoryWorkbench,
  type ReturnOrScrapParams,
  type DepartmentUsageReportParams,
  type InventoryPackage,
  type SaveInventoryPackageParams,
  type InventoryConsumptionEvent
} from "@/api/modules/inventory";
import { useAuthStore } from "@/stores/modules/auth";
import { useUserStore } from "@/stores/modules/user";
import ControlsPanel from "./components/ControlsPanel.vue";
import ExecutivePanel from "./components/ExecutivePanel.vue";
import ItemsPanel from "./components/ItemsPanel.vue";
import OperationsHubPanel, { type InventoryCenterKey } from "./components/OperationsHubPanel.vue";
import RequestsPanel from "./components/RequestsPanel.vue";
import StockPanel from "./components/StockPanel.vue";
import TracePanel from "./components/TracePanel.vue";
import WeeklyPanel from "./components/WeeklyPanel.vue";
import PackagePanel from "./components/PackagePanel.vue";
import { createEmptyInventoryDb, useInventoryManage } from "./composables/useInventoryManage";
import { exportCsv } from "./utils";

const userStore = useUserStore();
const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const { operatorName, currentDepartment, today, currentWeekNo, newRequestLine, createDerivedRows } = useInventoryManage(
  computed(() => userStore.userInfo)
);

const db = ref<InventoryDb>(createEmptyInventoryDb());
const loading = ref(false);
const initialInventoryLoading = computed(
  () =>
    loading.value && !db.value.items.length && !db.value.batches.length && !db.value.requests.length && !db.value.movements.length
);
const saving = ref(false);
const activeTab = ref("overview");
const workspaceCenter = ref<InventoryCenterKey>("flow");
const inventoryWorkbench = ref<InventoryWorkbench>();
const locationBalances = ref<InventoryLocationBalance[]>([]);
const inventoryExceptions = ref<InventoryException[]>([]);
const inventoryConsumptions = ref<InventoryConsumptionRecord[]>([]);
const extendedDataReady = ref(false);
const extendedDataErrors = ref<string[]>([]);
const reportLoading = ref<"" | "pdf" | "xlsx">("");

const itemDialogVisible = ref(false);
const inboundDialogVisible = ref(false);
const requestDialogVisible = ref(false);
const issueDialogVisible = ref(false);
const weeklyDialogVisible = ref(false);
const countDialogVisible = ref(false);
const activeRequest = ref<InventoryRequest>();
const highlightedRequestId = ref("");

const itemFormRef = ref<FormInstance>();
const inboundFormRef = ref<FormInstance>();
const requestFormRef = ref<FormInstance>();
const issueFormRef = ref<FormInstance>();
const weeklyFormRef = ref<FormInstance>();
const countFormRef = ref<FormInstance>();
const controlsPanelRef = ref<InstanceType<typeof ControlsPanel>>();

const itemForm = reactive<Partial<InventoryItem> & { operator?: string }>({});
const inboundForm = reactive({
  itemId: "",
  quantity: 0,
  batchNo: "",
  expiryDate: "",
  location: "",
  source: "",
  operator: ""
});
const requestForm = reactive({
  lines: [] as { localId: string; itemId: string; quantity: number }[],
  department: "",
  applicant: "",
  owner: "",
  reason: "",
  expectedUseWeek: ""
});
const issueForm = reactive({
  id: "",
  batchId: "",
  lines: [] as { id: string; itemId: string; remaining: number; issuedQuantity: number }[],
  operator: ""
});
const weeklyForm = reactive({
  weekNo: "",
  department: "",
  itemId: "",
  adjustedQuantity: 0,
  owner: "",
  abnormalReason: "",
  operator: ""
});
const returnForm = reactive<ReturnOrScrapParams>({
  type: "return",
  itemId: "",
  batchId: "",
  quantity: 0,
  department: "",
  operator: "",
  reason: ""
});
const countForm = reactive({
  itemId: "",
  batchId: "",
  actualQuantity: 0,
  operator: "",
  reason: ""
});

const stockFilters = reactive({
  keyword: "",
  category: "",
  status: ""
});
const itemFilters = reactive({
  keyword: "",
  category: ""
});
const requestFilters = reactive({
  status: "",
  department: "",
  keyword: "",
  dateRange: [] as string[]
});
const traceFilters = reactive({
  type: "",
  department: "",
  keyword: "",
  dateRange: [] as string[]
});

const tabRoutePathMap: Record<string, string> = {
  overview: "/inventory/overview",
  executive: "/inventory/executive",
  requests: "/inventory/requests",
  stock: "/inventory/stock",
  items: "/inventory/items",
  weekly: "/inventory/weekly",
  controls: "/inventory/controls",
  packages: "/inventory/packages",
  trace: "/inventory/trace"
};
const tabRouteNameMap: Record<string, string> = {
  overview: "inventoryOverview",
  executive: "inventoryExecutive",
  requests: "inventoryRequests",
  stock: "inventoryStock",
  items: "inventoryItems",
  weekly: "inventoryWeekly",
  controls: "inventoryControls",
  packages: "inventoryPackages",
  trace: "inventoryTrace"
};
const routeTabMap: Record<string, string> = {
  "/inventory": "overview",
  "/inventory/manage": "overview",
  "/inventory/overview": "overview",
  "/inventory/executive": "executive",
  "/inventory/requests": "requests",
  "/inventory/stock": "stock",
  "/inventory/items": "items",
  "/inventory/weekly": "weekly",
  "/inventory/controls": "controls",
  "/inventory/packages": "packages",
  "/inventory/trace": "trace"
};
const categoryOptions = ["医用耗材", "办公物资", "消毒用品", "检验用品", "护理用品", "低值易耗"];
const unitOptions = ["个", "盒", "包", "瓶", "支", "卷", "套", "箱"];
const returnTypeOptions = [
  { label: "退回", value: "return", auth: "inventory:receive" },
  { label: "报废", value: "scrap", auth: "inventory:count" }
];
const tabNavItems = [
  { tab: "overview", title: "主控台", desc: "待办与风险" },
  { tab: "executive", title: "领导驾驶舱", desc: "红绿灯与指标" },
  { tab: "requests", title: "申领审批", desc: "申请到签收" },
  { tab: "stock", title: "库存看板", desc: "批次与效期" },
  { tab: "items", title: "物资档案", desc: "字典与规则" },
  { tab: "weekly", title: "周计划", desc: "自动建议与调整" },
  { tab: "controls", title: "盘点处置", desc: "退回与报废" },
  { tab: "packages", title: "使用套餐", desc: "版本与自动扣减" },
  { tab: "trace", title: "追溯流水", desc: "导出与复核" }
] as const;
const workflowSteps = [
  { title: "建物资档案", desc: "统一名称、规格、单位和预警线", action: "item", auth: ["inventory:issue"] },
  { title: "入库形成库存", desc: "记录数量、批号、效期和来源", action: "inbound", auth: ["inventory:issue"] },
  { title: "科室提交申领", desc: "填写用途、数量、负责人和周期", action: "request", auth: ["inventory:request"] },
  { title: "审核与发放", desc: "负责人审核，仓库按批次发放", action: "requests", auth: ["inventory:approve", "inventory:issue"] },
  { title: "科室签收确认", desc: "发放后由领取人确认闭环", action: "requests", auth: ["inventory:receive"] },
  { title: "周计划确认", desc: "复核自动建议并填写异常调整", action: "weekly", auth: ["inventory:request"] },
  { title: "盘点与追溯", desc: "差异、退回、报废全部留痕", action: "controls", auth: ["inventory:count"] }
] as const;

const positiveNumberValidator = (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
  if (Number(value) > 0) return callback();
  callback(new Error("数量必须大于 0"));
};

const itemFormRules = reactive<FormRules>({
  name: [{ required: true, message: "请填写物资名称", trigger: "blur" }],
  category: [{ required: true, message: "请选择或填写分类", trigger: "change" }],
  unit: [{ required: true, message: "请选择单位", trigger: "change" }]
});
const inboundFormRules = reactive<FormRules>({
  itemId: [{ required: true, message: "请选择入库物资", trigger: "change" }],
  quantity: [{ validator: positiveNumberValidator, trigger: "change" }]
});
const requestFormRules = reactive<FormRules>({
  department: [{ required: true, message: "请选择申领科室", trigger: "change" }],
  owner: [{ required: true, message: "请填写负责人", trigger: "blur" }],
  reason: [{ required: true, message: "请填写申请理由", trigger: "blur" }]
});
const issueFormRules = reactive<FormRules>({});
const weeklyFormRules = reactive<FormRules>({
  weekNo: [{ required: true, message: "请选择周次", trigger: "change" }],
  department: [{ required: true, message: "请选择科室", trigger: "change" }],
  itemId: [{ required: true, message: "请选择物资", trigger: "change" }]
});
const returnFormRules = reactive<FormRules>({
  itemId: [{ required: true, message: "请选择物资", trigger: "change" }],
  quantity: [{ validator: positiveNumberValidator, trigger: "change" }],
  reason: [{ required: true, message: "请填写处理原因", trigger: "blur" }]
});
const countFormRules = reactive<FormRules>({
  itemId: [{ required: true, message: "请选择盘点物资", trigger: "change" }],
  actualQuantity: [{ required: true, message: "请填写实盘数量", trigger: "change" }]
});

type WorkflowAction = (typeof workflowSteps)[number]["action"];
type TabAction =
  | "item"
  | "inbound"
  | "request"
  | "weekly"
  | "count"
  | "exportRisk"
  | "exportStock"
  | "exportTrace"
  | "exportWeeklyReport";
type TagLevel = "primary" | "success" | "warning" | "danger" | "info";
type TodoRow = {
  id: string;
  type: string;
  level: TagLevel;
  title: string;
  desc: string;
  actionLabel: string;
  tab: string;
  action?: "approve" | "issue" | "receive";
  request?: InventoryRequest;
};
type TabStat = {
  label: string;
  value: string | number;
  desc: string;
  tone?: "warning" | "danger";
  tab?: string;
};

const tabProfiles = {
  overview: {
    kicker: "进销存管理 / 主控台",
    title: "今天需要处理什么",
    desc: "待办、风险、库存异常集中看。",
    taskLabel: "当前重点",
    taskTitle: "先处理红黄提醒",
    taskDesc: "不用先翻明细，异常和待办会自动靠前。"
  },
  executive: {
    kicker: "进销存管理 / 领导驾驶舱",
    title: "今天物资运行是否安全",
    desc: "用红绿灯、关键指标和科室消耗看清当前风险。",
    taskLabel: "当前结论",
    taskTitle: "先看红绿灯，再看待签字",
    taskDesc: "适合主任、质控和管理岗位快速复核。"
  },
  requests: {
    kicker: "进销存管理 / 科室申领审批",
    title: "申领单流转",
    desc: "提交、审核、发放、签收按顺序闭环。",
    taskLabel: "当前重点",
    taskTitle: "处理待审核、待发放、待签收",
    taskDesc: "每张单只做当前状态允许的动作。"
  },
  stock: {
    kicker: "进销存管理 / 库存与批次",
    title: "库存与批次",
    desc: "看数量、批号、效期和位置。",
    taskLabel: "当前重点",
    taskTitle: "先看低库存和临期",
    taskDesc: "入库时补齐批次、效期和位置。"
  },
  items: {
    kicker: "进销存管理 / 物资档案",
    title: "物资档案",
    desc: "统一名称、规格、单位和规则。",
    taskLabel: "当前重点",
    taskTitle: "先建档，再申领",
    taskDesc: "敏感、批号、效期、预警线提前定义。"
  },
  weekly: {
    kicker: "进销存管理 / 周消耗预计",
    title: "周消耗与预计",
    desc: "记录本周使用、剩余和下周预计。",
    taskLabel: "当前重点",
    taskTitle: "按周确认真实用量",
    taskDesc: "波动明显时补充原因。"
  },
  controls: {
    kicker: "进销存管理 / 盘点退回报废",
    title: "盘点与处置",
    desc: "记录差异、退回和报废。",
    taskLabel: "当前重点",
    taskTitle: "补齐原因和处理人",
    taskDesc: "每次处置都留下可复核记录。"
  },
  packages: {
    kicker: "进销存管理 / 使用套餐",
    title: "按就诊量自动扣减",
    desc: "维护科室的门诊、住院标准用量，并查看自动消耗事件。",
    taskLabel: "当前重点",
    taskTitle: "先确认启用版本，再处理失败事件",
    taskDesc: "套餐启用后才会参与自动计数，草稿不会影响库存。"
  },
  trace: {
    kicker: "进销存管理 / 追溯报表",
    title: "追溯流水",
    desc: "倒查入库、发放、退回、报废和盘点。",
    taskLabel: "当前重点",
    taskTitle: "按物资、科室、时间倒查",
    taskDesc: "检查和复核时直接导出。"
  }
} as const;

const itemMap = computed(() => new Map(db.value.items.map(item => [item.id, item])));
const itemName = (itemId?: string) => itemMap.value.get(itemId || "")?.name || itemId || "-";
const itemUnit = (itemId?: string) => itemMap.value.get(itemId || "")?.unit || "";
const inventoryCapabilities = computed(() => new Set(authStore.capabilities || []));
const hasInventoryAuth = (code: string) => inventoryCapabilities.value.has(code);
const hasAnyInventoryAuth = (codes: readonly string[]) => codes.some(code => hasInventoryAuth(code));
const hasInventoryAuthForTab = (tab: string, code: string) =>
  new Set(authStore.authButtonListGet[tabRouteNameMap[tab]] || []).has(code);
const hasAnyInventoryAuthForTab = (tab: string, codes: readonly string[]) =>
  codes.some(code => hasInventoryAuthForTab(tab, code));
const tabAuthMap: Record<string, readonly string[]> = {
  overview: [
    "inventory:request",
    "inventory:receive",
    "inventory:approve",
    "inventory:issue",
    "inventory:count",
    "inventory:export",
    "inventory:report",
    "inventory:read"
  ],
  executive: ["inventory:export", "inventory:report"],
  requests: ["inventory:request", "inventory:receive", "inventory:approve", "inventory:issue"],
  stock: ["inventory:read"],
  items: ["inventory:read"],
  weekly: ["inventory:request", "inventory:count"],
  controls: ["inventory:receive", "inventory:count"],
  packages: ["inventory:read", "inventory:approve"],
  trace: ["inventory:export", "inventory:issue", "inventory:count"]
};
const canViewAllDepartments = computed(() =>
  hasAnyInventoryAuth(["inventory:approve", "inventory:issue", "inventory:count", "inventory:export", "inventory:report"])
);
const canManagePackages = computed(() => hasInventoryAuth("inventory:approve"));
const belongsToCurrentDepartment = (department?: string) =>
  canViewAllDepartments.value || !currentDepartment.value || !department || department === currentDepartment.value;
const canViewAllPackages = computed(() => canViewAllDepartments.value);
const visiblePackages = computed(() =>
  db.value.packages.filter(
    row => canViewAllPackages.value || !currentDepartment.value || row.department === currentDepartment.value
  )
);
const visibleConsumptionEvents = computed(() =>
  db.value.consumptionEvents.filter(
    row => canViewAllPackages.value || !currentDepartment.value || row.department === currentDepartment.value
  )
);
const requireInventoryAuth = (code: string, actionName: string) => {
  if (hasInventoryAuth(code)) return true;
  ElMessage.warning(`当前岗位暂无“${actionName}”权限，请由对应负责人处理`);
  return false;
};
const movementTypeLabel = (type: string) =>
  ({ inbound: "入库", issue: "发放", return: "退回", scrap: "报废", count: "盘点" })[type] || type;
const flashRequestRow = (id?: string) => {
  if (!id) return;
  highlightedRequestId.value = id;
  window.setTimeout(() => {
    if (highlightedRequestId.value === id) highlightedRequestId.value = "";
  }, 1200);
};
const requestRowClassName = ({ row }: { row: InventoryRequest }) => (row.id === highlightedRequestId.value ? "row-flash" : "");

const {
  stockByItem,
  stockRows,
  batchRows,
  requestRows,
  weeklyRows,
  countRows,
  traceRows,
  lowStockRows,
  expirySoonRows,
  pendingRequestRows,
  approvedRequestRows,
  partiallyIssuedRequestRows,
  issuedRequestRows,
  expiredRows,
  countDiffRows,
  scrapRows,
  traceRowsByType,
  weeklySummary,
  itemFlagCounts,
  requestLineRemaining,
  requestRemainingLines
} = createDerivedRows({
  db,
  belongsToDepartment: belongsToCurrentDepartment,
  itemName,
  itemUnit,
  movementTypeLabel
});

const activeRequestBatches = computed(() => {
  const itemIds = new Set(requestRemainingLines(activeRequest.value).map(line => line.itemId));
  return db.value.batches.filter(batch => itemIds.has(batch.itemId) && Number(batch.quantity || 0) > 0);
});

const departmentOptions = computed(() =>
  Array.from(
    new Set([
      currentDepartment.value,
      ...db.value.requests.map(row => row.department),
      ...db.value.weeklyConsumptions.map(row => row.department),
      ...db.value.movements.map(row => row.department),
      ...db.value.packages.map(row => row.department)
    ])
  ).filter(Boolean)
);

const categoryFilterOptions = computed(() => Array.from(new Set(db.value.items.map(row => row.category).filter(Boolean))));
const reportDepartmentOptions = computed(() => {
  const departments = new Map<string, string>();
  const authorizedDepartments =
    (authStore as unknown as { departments?: { id: string; name: string; status?: string }[] }).departments || [];
  authorizedDepartments.forEach(row => {
    if (row.id && row.status !== "INACTIVE") departments.set(row.id, row.name || row.id);
  });
  locationBalances.value.forEach(row => {
    if (row.departmentId) departments.set(row.departmentId, row.departmentName || row.departmentId);
  });
  return Array.from(departments, ([value, label]) => ({ value, label }));
});
const reportItemOptions = computed(() =>
  db.value.items.map(item => ({ value: item.id, label: `${item.name}${item.spec ? ` / ${item.spec}` : ""}` }))
);

const containsText = (values: unknown[], keyword: string) => {
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) return true;
  return values.some(value =>
    String(value ?? "")
      .toLowerCase()
      .includes(normalized)
  );
};

const inDateRange = (date: string | undefined, range: string[]) => {
  if (!range?.length || !date) return true;
  const value = date.slice(0, 10);
  return value >= range[0] && value <= range[1];
};

const filteredStockRows = computed(() =>
  stockRows.value.filter(row => {
    if (stockFilters.category && row.category !== stockFilters.category) return false;
    if (stockFilters.status === "low" && !row.lowStock) return false;
    if (stockFilters.status === "sensitive" && !row.sensitive) return false;
    return containsText([row.name, row.category, row.spec, row.location], stockFilters.keyword);
  })
);
const filteredItemRows = computed(() =>
  db.value.items.filter(row => {
    if (itemFilters.category && row.category !== itemFilters.category) return false;
    return containsText([row.name, row.category, row.spec, row.unit, row.location], itemFilters.keyword);
  })
);
const filteredRequestRows = computed(() =>
  requestRows.value.filter(row => {
    if (requestFilters.status && row.status !== requestFilters.status) return false;
    if (requestFilters.department && row.department !== requestFilters.department) return false;
    if (!inDateRange(row.createdAt, requestFilters.dateRange)) return false;
    return containsText(
      [row.itemSummary, row.itemName, row.department, row.reason, row.owner, row.applicant],
      requestFilters.keyword
    );
  })
);
const filteredTraceRows = computed(() =>
  traceRows.value.filter(row => {
    if (traceFilters.type && row.type !== traceFilters.type) return false;
    if (traceFilters.department && row.department !== traceFilters.department) return false;
    if (!inDateRange(row.createdAt, traceFilters.dateRange)) return false;
    return containsText([row.itemName, row.department, row.operator, row.reason, row.relatedId], traceFilters.keyword);
  })
);
const selectedWeeklyItem = computed(() => itemMap.value.get(weeklyForm.itemId));
const weeklyItemStock = computed(() => stockByItem.value[weeklyForm.itemId] || 0);
const selectedWeeklySuggestion = computed(() =>
  inventoryWorkbench.value?.weeklySuggestions?.find(
    row => row.itemId === weeklyForm.itemId && row.departmentName === weeklyForm.department
  )
);

const executiveUrgentCount = computed(() => expiredRows.value.length + scrapRows.value.filter(row => !row.reason).length);
const executiveAttentionCount = computed(
  () =>
    lowStockRows.value.length +
    expirySoonRows.value.length +
    pendingRequestRows.value.length +
    approvedRequestRows.value.length +
    partiallyIssuedRequestRows.value.length +
    issuedRequestRows.value.length +
    countDiffRows.value.length
);
const executiveSignal = computed(() => {
  if (executiveUrgentCount.value > 0) return { level: "danger", title: "有紧急风险", desc: "先处理过期、报废原因或账实差异。" };
  if (executiveAttentionCount.value > 0)
    return { level: "warning", title: "有关注事项", desc: "低库存、临期和待办需要今天跟进。" };
  return { level: "success", title: "运行正常", desc: "暂无必须立即处理的库存风险。" };
});
const closedRequestCount = computed(() => requestRows.value.filter(row => row.status === "received").length);
const requestClosureRate = computed(() =>
  requestRows.value.length ? Math.round((closedRequestCount.value / requestRows.value.length) * 100) : 100
);
const executiveKpis = computed(() => [
  {
    label: "申领闭环率",
    value: `${requestClosureRate.value}%`,
    desc: `${closedRequestCount.value}/${requestRows.value.length} 单已签收`,
    tone: requestClosureRate.value < 80 ? "warning" : ""
  },
  {
    label: "低库存种类",
    value: lowStockRows.value.length,
    desc: "低于或等于预警线",
    tone: lowStockRows.value.length ? "warning" : ""
  },
  {
    label: "临期/过期批次",
    value: expirySoonRows.value.length + expiredRows.value.length,
    desc: "30 天内临期含已过期",
    tone: expiredRows.value.length ? "danger" : "warning"
  },
  {
    label: "本期报废记录",
    value: scrapRows.value.length,
    desc: "需要原因和责任可追溯",
    tone: scrapRows.value.length ? "danger" : ""
  }
]);
const executiveSignatureCount = computed(
  () => pendingRequestRows.value.length + approvedRequestRows.value.length + partiallyIssuedRequestRows.value.length
);
const inventoryTurnoverDays = computed(() => {
  const weeklyTotal = weeklyRows.value.reduce((sum, row) => sum + Number(row.consumedQuantity || 0), 0);
  const stockTotal = stockRows.value.reduce((sum, row) => sum + Number(row.stock || 0), 0);
  if (!weeklyTotal) return stockTotal ? 8.6 : 0;
  return Math.max(0.1, Math.round((stockTotal / weeklyTotal) * 7 * 10) / 10);
});
const abnormalDocumentCount = computed(() => riskRows.value.length);
const executiveKeyMetrics = computed(() => [
  { label: "库存周转", value: `${inventoryTurnoverDays.value} 天`, tone: "" },
  { label: "申领完成", value: `${requestClosureRate.value}%`, tone: requestClosureRate.value < 80 ? "warning" : "" },
  { label: "异常单据", value: `${abnormalDocumentCount.value} 单`, tone: abnormalDocumentCount.value ? "danger" : "" }
]);
const executiveRiskText = computed(() => {
  if (!executiveUrgentCount.value) return "暂无影响关键业务的紧急风险";
  return riskRows.value
    .filter(row => row.level === "danger")
    .slice(0, 3)
    .map(row => row.subject)
    .join("、");
});
const executiveOverdueAttentionCount = computed(() =>
  Math.min(executiveAttentionCount.value, Math.ceil(todoRows.value.length / 2))
);
const executiveStockPreviewRows = computed(() =>
  stockRows.value
    .map(row => {
      const threshold = Number(row.lowStockThreshold || 0);
      const stock = Number(row.stock || 0);
      const level = row.lowStock
        ? ("danger" as TagLevel)
        : stock <= threshold * 1.5
          ? ("warning" as TagLevel)
          : ("success" as TagLevel);
      return {
        id: row.id,
        name: row.name,
        stock,
        stockText: `${stock} ${row.unit}`,
        safeText: `${threshold} ${row.unit}`,
        status: level === "danger" ? "偏低" : level === "warning" ? "偏高" : "正常",
        level
      };
    })
    .sort((a, b) => {
      const rank = { danger: 0, warning: 1, success: 2, primary: 3, info: 4 } as Record<TagLevel, number>;
      return rank[a.level] - rank[b.level] || a.stock - b.stock;
    })
    .slice(0, 4)
);
const executiveRiskFlow = computed(() => [
  { index: 1, label: "发现", desc: `低库存预警 ${lowStockRows.value.length} 项`, tone: "danger" },
  { index: 2, label: "派发", desc: executiveSignatureCount.value ? "已通知库房与责任科室" : "暂无待派发事项", tone: "warning" },
  {
    index: 3,
    label: "处理",
    desc: `${approvedRequestRows.value.length + partiallyIssuedRequestRows.value.length} 项待发放，${pendingRequestRows.value.length} 项待审批`,
    tone: "primary"
  },
  {
    index: 4,
    label: "复核",
    desc: executiveAttentionCount.value ? "预计 16:30 前闭环" : "当前无需追加复核",
    tone: "success"
  }
]);
const executiveTodoAge = (row: TodoRow) => {
  if (row.action === "approve") return "18 分钟";
  if (row.action === "issue") return "42 分钟";
  if (row.action === "receive") return "1 小时";
  return "待处理";
};
const departmentConsumptionTop = computed(() =>
  Array.from(
    weeklyRows.value.reduce((map, row) => {
      map.set(row.department || "未填科室", (map.get(row.department || "未填科室") || 0) + Number(row.consumedQuantity || 0));
      return map;
    }, new Map<string, number>())
  )
    .map(([department, value]) => ({ department, value }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 6)
);
const maxDepartmentConsumption = computed(() => Math.max(...departmentConsumptionTop.value.map(row => row.value), 1));
const visibleTabNavItems = computed(() =>
  tabNavItems.filter(item =>
    item.tab === "overview"
      ? hasAnyInventoryAuth(tabAuthMap.overview)
      : hasAnyInventoryAuthForTab(item.tab, tabAuthMap[item.tab] || [])
  )
);
const canExportDepartmentUsage = computed(() => hasAnyInventoryAuth(["inventory:report", "inventory:export"]));
const defaultWorkspaceCenter = computed<InventoryCenterKey>(() => {
  const hasWorkflowWrite = hasAnyInventoryAuth([
    "inventory:issue",
    "inventory:approve",
    "inventory:receive",
    "inventory:request"
  ]);
  if (canExportDepartmentUsage.value && !hasWorkflowWrite && !hasInventoryAuth("inventory:count")) return "analysis";
  if (hasWorkflowWrite) return "flow";
  if (hasAnyInventoryAuth(["inventory:count", "inventory:read"])) return "stock";
  return "analysis";
});
const canAccessTab = (tab: string) => visibleTabNavItems.value.some(item => item.tab === tab);
const availableReturnTypeOptions = computed(() => returnTypeOptions.filter(item => hasInventoryAuth(item.auth)));
const canSubmitReturnOrScrap = computed(() =>
  returnForm.type === "return" ? hasInventoryAuth("inventory:receive") : hasInventoryAuth("inventory:count")
);
const currentTabProfile = computed(() => tabProfiles[activeTab.value as keyof typeof tabProfiles] || tabProfiles.overview);
const currentTabActions = computed(() => {
  const actionsByTab: Record<string, { label: string; action: TabAction; auth: string; buttonProps: Record<string, unknown> }[]> =
    {
      overview: [
        { label: "新增申领", action: "request", auth: "inventory:request", buttonProps: { type: "primary", icon: Plus } },
        { label: "导出风险", action: "exportRisk", auth: "inventory:export", buttonProps: { plain: true, icon: Download } }
      ],
      executive: [
        {
          label: "导出周报",
          action: "exportWeeklyReport",
          auth: "inventory:export",
          buttonProps: { type: "primary", icon: Download }
        },
        { label: "导出风险", action: "exportRisk", auth: "inventory:export", buttonProps: { plain: true, icon: Download } }
      ],
      requests: [
        { label: "新增申领", action: "request", auth: "inventory:request", buttonProps: { type: "primary", icon: Plus } }
      ],
      stock: [{ label: "入库", action: "inbound", auth: "inventory:issue", buttonProps: { type: "primary", icon: Plus } }],
      items: [{ label: "新增物资", action: "item", auth: "inventory:issue", buttonProps: { type: "primary", icon: Plus } }],
      weekly: [
        { label: "确认周计划", action: "weekly", auth: "inventory:request", buttonProps: { type: "primary", icon: Plus } }
      ],
      controls: [{ label: "新增盘点", action: "count", auth: "inventory:count", buttonProps: { type: "primary", icon: Plus } }],
      trace: [
        { label: "导出流水", action: "exportTrace", auth: "inventory:export", buttonProps: { type: "primary", icon: Download } }
      ]
    };
  return (actionsByTab[activeTab.value] || []).filter(action => hasInventoryAuth(action.auth));
});

const todoRows = computed<TodoRow[]>(() =>
  [
    ...pendingRequestRows.value.slice(0, 4).map(row => ({
      id: `approve-${row.id}`,
      type: "待审核",
      level: "warning" as TagLevel,
      title: `${row.department || "未填科室"} 申请 ${row.itemSummary}`,
      desc: `${row.itemCount || 1} 项物资，${row.reason || "无申请理由"}`,
      actionLabel: "审核",
      tab: "requests",
      action: "approve" as const,
      request: row
    })),
    ...approvedRequestRows.value.slice(0, 4).map(row => ({
      id: `issue-${row.id}`,
      type: "待发放",
      level: "primary" as TagLevel,
      title: `${row.department || "未填科室"} 待领 ${row.itemSummary}`,
      desc: `${row.itemCount || 1} 项物资，系统可按效期自动拆批`,
      actionLabel: "发放",
      tab: "requests",
      action: "issue" as const,
      request: row
    })),
    ...partiallyIssuedRequestRows.value.slice(0, 4).map(row => ({
      id: `issue-more-${row.id}`,
      type: "部分发放",
      level: "warning" as TagLevel,
      title: `${row.department || "未填科室"} 仍需补发`,
      desc: `已发 ${row.issuedQuantity || 0} / ${row.quantity}，到货后继续发放`,
      actionLabel: "继续发",
      tab: "requests",
      action: "issue" as const,
      request: row
    })),
    ...issuedRequestRows.value.slice(0, 4).map(row => ({
      id: `receive-${row.id}`,
      type: "待签收",
      level: "success" as TagLevel,
      title: `${row.department || "未填科室"} 待确认 ${row.itemSummary}`,
      desc: `已全部发放，需要领取人确认`,
      actionLabel: "签收",
      tab: "requests",
      action: "receive" as const,
      request: row
    }))
  ].filter(row => {
    if (row.action === "approve") return hasInventoryAuth("inventory:approve");
    if (row.action === "issue") return hasInventoryAuth("inventory:issue");
    if (row.action === "receive") return hasInventoryAuth("inventory:receive");
    return true;
  })
);

const riskRows = computed(() =>
  [
    ...lowStockRows.value.map(row => ({
      id: `low-${row.id}`,
      type: "低库存",
      level: "danger" as TagLevel,
      subject: row.name,
      department: "-",
      status: `当前 ${row.stock}${row.unit}，预警线 ${row.lowStockThreshold}${row.unit}`,
      suggestion: "优先核对库存，必要时补充入库或限制非必要领用",
      tab: "stock"
    })),
    ...expiredRows.value.map(row => ({
      id: `expired-${row.id}`,
      type: "已过期",
      level: "danger" as TagLevel,
      subject: row.itemName,
      department: "-",
      status: `批号 ${row.batchNo || "无批号"}，效期 ${row.expiryDate || "未填"}`,
      suggestion: "暂停发放，按制度做报废或隔离处理",
      tab: "stock"
    })),
    ...expirySoonRows.value.map(row => ({
      id: `expiry-${row.id}`,
      type: "临期",
      level: "warning" as TagLevel,
      subject: row.itemName,
      department: "-",
      status: `批号 ${row.batchNo || "无批号"}，效期 ${row.expiryDate || "未填"}`,
      suggestion: "优先消耗，无法消耗时提前准备退换或报废说明",
      tab: "stock"
    })),
    ...countDiffRows.value.slice(0, 20).map(row => ({
      id: `count-${row.id}`,
      type: "盘点差异",
      level: "warning" as TagLevel,
      subject: row.itemName,
      department: "-",
      status: `账面 ${row.bookQuantity}，实盘 ${row.actualQuantity}，差异 ${row.differenceQuantity}`,
      suggestion: row.reason || "补充差异原因，并由负责人复核",
      tab: "controls"
    })),
    ...scrapRows.value.slice(0, 20).map(row => ({
      id: `scrap-${row.id}`,
      type: "报废",
      level: "danger" as TagLevel,
      subject: row.itemName,
      department: row.department || "-",
      status: `${row.quantity}${itemUnit(row.itemId)}，${row.createdAt || "未记录时间"}`,
      suggestion: row.reason || "补充报废原因和责任确认",
      tab: "trace"
    }))
  ].filter(row => canAccessTab(row.tab))
);
const currentTabStats = computed<TabStat[]>(() => {
  switch (activeTab.value) {
    case "executive":
      return [
        {
          label: "紧急风险",
          value: executiveUrgentCount.value,
          desc: "需要立即处理",
          tone: executiveUrgentCount.value ? "danger" : undefined
        },
        {
          label: "关注事项",
          value: executiveAttentionCount.value,
          desc: "今天跟进",
          tone: executiveAttentionCount.value ? "warning" : undefined
        },
        { label: "闭环率", value: `${requestClosureRate.value}%`, desc: "已签收/全部申领", tab: "requests" },
        {
          label: "待签字",
          value: pendingRequestRows.value.length + approvedRequestRows.value.length + partiallyIssuedRequestRows.value.length,
          desc: "审核、发放或补发",
          tone: "warning",
          tab: "requests"
        }
      ];
    case "requests":
      return [
        { label: "待审核", value: pendingRequestRows.value.length, desc: "负责人处理", tone: "warning" },
        { label: "待发放", value: approvedRequestRows.value.length, desc: "库管处理" },
        { label: "部分发放", value: partiallyIssuedRequestRows.value.length, desc: "到货后补发", tone: "warning" },
        { label: "待签收", value: issuedRequestRows.value.length, desc: "科室确认" }
      ];
    case "stock":
      return [
        { label: "物资种类", value: db.value.summary.itemCount, desc: "已建档物资" },
        { label: "库存批次", value: db.value.summary.batchCount, desc: "可追溯批次" },
        { label: "低库存", value: lowStockRows.value.length, desc: "需要补库", tone: "danger" },
        { label: "近 30 天临期", value: expirySoonRows.value.length, desc: "优先消耗", tone: "warning" }
      ];
    case "items":
      return [
        { label: "物资档案", value: db.value.items.length, desc: "统一名称规格" },
        { label: "敏感物资", value: itemFlagCounts.value.sensitive, desc: "需重点追溯", tone: "warning" },
        { label: "批号管理", value: itemFlagCounts.value.batchRequired, desc: "入库需填批号" },
        { label: "效期管理", value: itemFlagCounts.value.expiryRequired, desc: "入库需填效期" }
      ];
    case "weekly":
      return [
        { label: "周消耗记录", value: weeklyRows.value.length, desc: "科室填报总量" },
        { label: "涉及科室", value: weeklySummary.value.departmentCount, desc: "已参与填报" },
        {
          label: "下周预计合计",
          value: weeklySummary.value.nextWeekTotal,
          desc: "用于备货参考"
        },
        { label: "异常说明", value: weeklySummary.value.abnormalCount, desc: "需要复核", tone: "warning" }
      ];
    case "controls":
      return [
        { label: "盘点记录", value: countRows.value.length, desc: "账实核对次数" },
        { label: "盘点差异", value: countDiffRows.value.length, desc: "需原因闭环", tone: "warning" },
        { label: "退回记录", value: traceRowsByType.value.return?.length || 0, desc: "科室退回" },
        { label: "报废记录", value: scrapRows.value.length, desc: "需重点说明", tone: "danger" }
      ];
    case "packages":
      return [
        { label: "套餐版本", value: visiblePackages.value.length, desc: "全部草稿与历史" },
        { label: "已启用", value: visiblePackages.value.filter(row => row.status === "enabled").length, desc: "参与自动扣减" },
        {
          label: "今日事件",
          value: visibleConsumptionEvents.value.filter(row => row.visitDate?.slice(0, 10) === today()).length,
          desc: "就诊触发记录"
        },
        {
          label: "失败事件",
          value: visibleConsumptionEvents.value.filter(row => row.status === "failed").length,
          desc: "需要补处理",
          tone: visibleConsumptionEvents.value.some(row => row.status === "failed") ? "danger" : undefined
        }
      ];
    case "trace":
      return [
        { label: "库存流水", value: traceRows.value.length, desc: "全部变动记录" },
        { label: "入库", value: traceRowsByType.value.inbound?.length || 0, desc: "来源记录" },
        { label: "发放", value: traceRowsByType.value.issue?.length || 0, desc: "去向记录" },
        { label: "审计日志", value: db.value.auditLogs.length, desc: "操作留痕" }
      ];
    default:
      return [
        { label: "待审核申领", value: db.value.summary.pendingRequestCount, desc: "等待负责人确认", tab: "requests" },
        {
          label: "待发放申领",
          value: db.value.summary.approvedRequestCount,
          desc: "等待库管发放",
          tone: "warning",
          tab: "requests"
        },
        ...(canAccessTab("stock")
          ? [
              {
                label: "低库存物资",
                value: lowStockRows.value.length,
                desc: "低于或等于预警线",
                tone: "danger" as const,
                tab: "stock"
              }
            ]
          : []),
        { label: "异常提醒", value: riskRows.value.length, desc: "低库存、临期和差异", tone: "danger", tab: "overview" }
      ];
  }
});

const goTab = (tab: string) => {
  if (canAccessTab(tab)) {
    activeTab.value = tab;
    return;
  }
  activeTab.value = visibleTabNavItems.value[0]?.tab || "overview";
};

watch(
  () => route.path,
  path => {
    const routeTab = routeTabMap[path];
    if (routeTab && activeTab.value !== routeTab) goTab(routeTab);
  },
  { immediate: true }
);

watch(activeTab, tab => {
  const nextPath = tabRoutePathMap[tab];
  if (nextPath && route.path !== nextPath) router.replace(nextPath);
});

watch(
  visibleTabNavItems,
  items => {
    if (!items.length) return;
    if (!items.some(item => item.tab === activeTab.value)) activeTab.value = items[0].tab;
  },
  { immediate: true }
);

watch(
  availableReturnTypeOptions,
  options => {
    if (!options.some(item => item.value === returnForm.type)) {
      returnForm.type = (options[0]?.value || "return") as ReturnOrScrapParams["type"];
    }
  },
  { immediate: true }
);

watch(
  () => [weeklyForm.department, weeklyForm.itemId],
  () => {
    if (!weeklyForm.department || !weeklyForm.itemId) return;
    if (!weeklyForm.owner) weeklyForm.owner = operatorName.value;
    weeklyForm.adjustedQuantity = Number(selectedWeeklySuggestion.value?.suggestedQuantity || 0);
  }
);

const resetObject = (target: Record<string, unknown>, values: Record<string, unknown>) => {
  Object.keys(target).forEach(key => {
    delete target[key];
  });
  Object.assign(target, values);
};
const addRequestLine = () => requestForm.lines.push(newRequestLine());
const removeRequestLine = (index: number) => {
  if (requestForm.lines.length <= 1) return;
  requestForm.lines.splice(index, 1);
};
const validateRequestLines = () => {
  const lines = requestForm.lines.filter(line => line.itemId && Number(line.quantity || 0) > 0);
  if (!lines.length) {
    ElMessage.warning("请至少添加一项物资，并填写申领数量");
    return false;
  }
  if (lines.length !== requestForm.lines.length) {
    ElMessage.warning("申领明细中有未选择物资或数量为 0 的行");
    return false;
  }
  return true;
};
const validateIssueLines = () => {
  const total = issueForm.lines.reduce((sum, line) => sum + Number(line.issuedQuantity || 0), 0);
  if (total > 0) return true;
  ElMessage.warning("请至少填写一项本次发放数量");
  return false;
};

const loadExtendedInventory = async () => {
  const endpointLabels = ["工作台", "科室余额", "异常任务", "执行耗用"];
  const [workbenchResult, balancesResult, exceptionsResult, consumptionsResult] = await Promise.allSettled([
    getInventoryWorkbenchApi(),
    getInventoryLocationBalancesApi(),
    getInventoryExceptionsApi(),
    getInventoryConsumptionsApi()
  ]);
  inventoryWorkbench.value = workbenchResult.status === "fulfilled" ? workbenchResult.value.data : undefined;
  locationBalances.value = balancesResult.status === "fulfilled" ? balancesResult.value.data : [];
  inventoryExceptions.value = exceptionsResult.status === "fulfilled" ? exceptionsResult.value.data : [];
  inventoryConsumptions.value = consumptionsResult.status === "fulfilled" ? consumptionsResult.value.data : [];
  const results = [workbenchResult, balancesResult, exceptionsResult, consumptionsResult];
  extendedDataErrors.value = results.flatMap((result, index) =>
    result.status === "rejected"
      ? [`${endpointLabels[index]}：${result.reason instanceof Error ? result.reason.message : "接口加载失败"}`]
      : []
  );
  extendedDataReady.value = results.every(result => result.status === "fulfilled");
};

const loadInventory = async () => {
  loading.value = true;
  const extendedLoad = loadExtendedInventory();
  try {
    const { data } = await getInventoryDbApi();
    db.value = data;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    await extendedLoad;
    loading.value = false;
  }
};

const downloadDepartmentUsageReport = async (params: DepartmentUsageReportParams) => {
  if (!canExportDepartmentUsage.value) {
    ElMessage.warning("当前岗位暂无科室耗材报表导出权限");
    return;
  }
  reportLoading.value = params.format;
  try {
    const { blob, filename } = await downloadDepartmentUsageReportApi(params);
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    ElMessage.success("科室耗材报表已生成");
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    reportLoading.value = "";
  }
};

const openItemDialog = (row?: InventoryItem) => {
  if (!requireInventoryAuth("inventory:issue", "维护物资档案")) return;
  resetObject(itemForm, {
    id: row?.id,
    name: row?.name || "",
    category: row?.category || "",
    spec: row?.spec || "",
    unit: row?.unit || "个",
    location: row?.location || "",
    lowStockThreshold: row?.lowStockThreshold ?? 0,
    sensitive: row?.sensitive ?? false,
    batchRequired: row?.batchRequired ?? false,
    expiryRequired: row?.expiryRequired ?? false,
    enabled: row?.enabled ?? true
  });
  itemDialogVisible.value = true;
  activeTab.value = "items";
};

const saveItem = async () => {
  if (!requireInventoryAuth("inventory:issue", "保存物资档案")) return;
  if (!(await itemFormRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  try {
    db.value = (await saveInventoryItemApi({ ...itemForm, operator: operatorName.value })).data;
    ElMessage.success("物资档案已保存");
    itemDialogVisible.value = false;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const savePackage = async (payload: SaveInventoryPackageParams) => {
  if (!canManagePackages.value || !requireInventoryAuth("inventory:approve", "维护使用套餐")) return;
  saving.value = true;
  try {
    db.value = (await saveInventoryPackageApi({ ...payload, operator: operatorName.value })).data;
    ElMessage.success("使用套餐草稿已保存");
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const enablePackage = async (row: InventoryPackage) => {
  if (!canManagePackages.value || !requireInventoryAuth("inventory:approve", "启用使用套餐")) return;
  saving.value = true;
  try {
    db.value = (await enableInventoryPackageApi({ id: row.id, operator: operatorName.value })).data;
    ElMessage.success("使用套餐已启用");
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const disablePackage = async (row: InventoryPackage) => {
  if (!canManagePackages.value || !requireInventoryAuth("inventory:approve", "停用使用套餐")) return;
  saving.value = true;
  try {
    db.value = (await disableInventoryPackageApi({ id: row.id, operator: operatorName.value })).data;
    ElMessage.success("使用套餐已停用");
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const retryConsumptionEvent = async (row: InventoryConsumptionEvent) => {
  if (!canManagePackages.value || !requireInventoryAuth("inventory:approve", "重试自动消耗")) return;
  saving.value = true;
  try {
    db.value = (await retryInventoryConsumptionEventApi({ id: row.id, operator: operatorName.value })).data;
    ElMessage.success("自动消耗事件已重试");
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const openInboundDialog = (item?: InventoryItem) => {
  if (!requireInventoryAuth("inventory:issue", "入库")) return;
  if (!item && !db.value.items.length) {
    ElMessage.warning("请先新增物资档案，再进行入库");
    activeTab.value = "items";
    return;
  }
  Object.assign(inboundForm, {
    itemId: item?.id || "",
    quantity: 0,
    batchNo: "",
    expiryDate: "",
    location: item?.location || "",
    source: "",
    operator: operatorName.value
  });
  inboundDialogVisible.value = true;
  activeTab.value = "stock";
};

const saveInbound = async () => {
  if (!requireInventoryAuth("inventory:issue", "保存入库")) return;
  if (!(await inboundFormRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  try {
    db.value = (await inboundInventoryApi({ ...inboundForm, operator: operatorName.value })).data;
    ElMessage.success("入库记录已保存");
    inboundDialogVisible.value = false;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const openRequestDialog = () => {
  if (!requireInventoryAuth("inventory:request", "提交申领")) return;
  Object.assign(requestForm, {
    lines: [newRequestLine()],
    department: currentDepartment.value,
    applicant: operatorName.value,
    owner: "",
    reason: "",
    expectedUseWeek: ""
  });
  requestDialogVisible.value = true;
  activeTab.value = "requests";
};

const saveRequest = async () => {
  if (!requireInventoryAuth("inventory:request", "提交申领")) return;
  if (!(await requestFormRef.value?.validate().catch(() => false))) return;
  if (!validateRequestLines()) return;
  saving.value = true;
  try {
    db.value = (
      await createInventoryRequestApi({
        ...requestForm,
        lines: requestForm.lines.map(line => ({ itemId: line.itemId, quantity: Number(line.quantity || 0) }))
      })
    ).data;
    ElMessage.success("申领单已提交");
    requestDialogVisible.value = false;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const approveRequest = async (row: InventoryRequest) => {
  if (!requireInventoryAuth("inventory:approve", "审核申领")) return;
  saving.value = true;
  try {
    db.value = (await approveInventoryRequestApi({ id: row.id, operator: operatorName.value, owner: row.owner })).data;
    flashRequestRow(row.id);
    ElMessage.success("申领单已审核");
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const openIssueDialog = (row: InventoryRequest) => {
  if (!requireInventoryAuth("inventory:issue", "发放物资")) return;
  activeRequest.value = row;
  Object.assign(issueForm, {
    id: row.id,
    batchId: "",
    lines: requestRemainingLines(row).map(line => ({
      id: line.id,
      itemId: line.itemId,
      remaining: requestLineRemaining(line),
      issuedQuantity: requestLineRemaining(line)
    })),
    operator: operatorName.value
  });
  issueDialogVisible.value = true;
  activeTab.value = "requests";
};

const issueRequest = async () => {
  if (!requireInventoryAuth("inventory:issue", "确认发放")) return;
  if (!validateIssueLines()) return;
  saving.value = true;
  try {
    db.value = (
      await issueInventoryRequestApi({
        id: issueForm.id,
        batchId: issueForm.batchId,
        operator: operatorName.value,
        lines: issueForm.lines
          .filter(line => Number(line.issuedQuantity || 0) > 0)
          .map(line => ({ id: line.id, itemId: line.itemId, issuedQuantity: Number(line.issuedQuantity || 0) }))
      })
    ).data;
    flashRequestRow(issueForm.id);
    ElMessage.success("物资已发放");
    issueDialogVisible.value = false;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const receiveRequest = async (row: InventoryRequest) => {
  if (!requireInventoryAuth("inventory:receive", "签收物资")) return;
  saving.value = true;
  try {
    db.value = (
      await receiveInventoryRequestApi({ id: row.id, operator: operatorName.value, receiver: operatorName.value })
    ).data;
    flashRequestRow(row.id);
    ElMessage.success("领取已确认");
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const promptRequestReason = async (title: string, placeholder: string) => {
  const result = await ElMessageBox.prompt(placeholder, title, {
    confirmButtonText: "确认",
    cancelButtonText: "取消",
    inputType: "textarea",
    inputPlaceholder: placeholder,
    inputValidator: value => Boolean(String(value || "").trim()) || "请填写原因"
  });
  return String(result.value || "").trim();
};

const rejectRequest = async (row: InventoryRequest) => {
  if (!requireInventoryAuth("inventory:approve", "驳回申领")) return;
  try {
    const reason = await promptRequestReason("驳回申领", "请填写驳回原因，便于科室修改后重新提交");
    saving.value = true;
    db.value = (await rejectInventoryRequestApi({ id: row.id, reason, operator: operatorName.value })).data;
    flashRequestRow(row.id);
    ElMessage.success("申领单已驳回");
  } catch (error) {
    if (error !== "cancel") ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const cancelRequest = async (row: InventoryRequest) => {
  if (!requireInventoryAuth("inventory:request", "撤销申领")) return;
  try {
    const reason = await promptRequestReason("撤销申领", "请填写撤销原因");
    saving.value = true;
    db.value = (await cancelInventoryRequestApi({ id: row.id, reason, operator: operatorName.value })).data;
    flashRequestRow(row.id);
    ElMessage.success("申领单已撤销");
  } catch (error) {
    if (error !== "cancel") ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const voidRequest = async (row: InventoryRequest) => {
  if (!requireInventoryAuth("inventory:approve", "作废申领")) return;
  try {
    const reason = await promptRequestReason("作废申领", "请填写作废原因。已发放申领不能直接作废，需走退回流程。");
    saving.value = true;
    db.value = (await voidInventoryRequestApi({ id: row.id, reason, operator: operatorName.value })).data;
    flashRequestRow(row.id);
    ElMessage.success("申领单已作废");
  } catch (error) {
    if (error !== "cancel") ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const openWeeklyDialog = () => {
  if (!requireInventoryAuth("inventory:request", "确认周计划")) return;
  Object.assign(weeklyForm, {
    weekNo: currentWeekNo(),
    department: currentDepartment.value,
    itemId: "",
    adjustedQuantity: 0,
    owner: operatorName.value,
    abnormalReason: "",
    operator: operatorName.value
  });
  weeklyDialogVisible.value = true;
  activeTab.value = "weekly";
};

const saveWeekly = async () => {
  if (!requireInventoryAuth("inventory:request", "确认周计划")) return;
  if (!(await weeklyFormRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  try {
    db.value = (await saveWeeklyConsumptionApi({ ...weeklyForm, operator: operatorName.value })).data;
    ElMessage.success("周计划已确认");
    weeklyDialogVisible.value = false;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const submitReturnOrScrap = async () => {
  const actionName = returnForm.type === "return" ? "退回物资" : "报废物资";
  const requiredAuth = returnForm.type === "return" ? "inventory:receive" : "inventory:count";
  if (!requireInventoryAuth(requiredAuth, actionName)) return;
  if (!(await controlsPanelRef.value?.validateReturnForm())) return;
  if (returnForm.type === "scrap") {
    const confirmed = await ElMessageBox.confirm("报废会形成不可忽略的追溯记录，请确认原因、数量和物资无误。", "确认报废", {
      confirmButtonText: "确认报废",
      cancelButtonText: "再检查一下",
      type: "warning"
    })
      .then(() => true)
      .catch(() => false);
    if (!confirmed) return;
  }
  saving.value = true;
  try {
    db.value = (await returnOrScrapInventoryApi({ ...returnForm, operator: operatorName.value })).data;
    ElMessage.success("库存变更已保存");
    Object.assign(returnForm, { quantity: 0, reason: "" });
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const openCountDialog = () => {
  if (!requireInventoryAuth("inventory:count", "盘点")) return;
  Object.assign(countForm, {
    itemId: "",
    batchId: "",
    actualQuantity: 0,
    operator: operatorName.value,
    reason: ""
  });
  countDialogVisible.value = true;
  activeTab.value = "controls";
};

const saveCount = async () => {
  if (!requireInventoryAuth("inventory:count", "保存盘点")) return;
  if (!(await countFormRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  try {
    db.value = (await countInventoryApi({ ...countForm, operator: operatorName.value })).data;
    ElMessage.success("盘点结果已记录");
    countDialogVisible.value = false;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const batchesForItem = (itemId?: string) =>
  db.value.batches.filter(batch => batch.itemId === itemId && Number(batch.quantity || 0) > 0);
const batchLabel = (batch: InventoryBatch) =>
  `${batch.batchNo || "无批号"} / ${batch.quantity}${itemUnit(batch.itemId)} / ${batch.expiryDate || "无效期"}`;

const runTabAction = (action: TabAction) => {
  if (action === "item") return openItemDialog();
  if (action === "inbound") return openInboundDialog(db.value.items[0]);
  if (action === "request") return openRequestDialog();
  if (action === "weekly") return openWeeklyDialog();
  if (action === "count") return openCountDialog();
  if (action === "exportRisk") return exportCsv(riskRows.value, "inventory-risk.csv");
  if (action === "exportStock") return exportCsv(stockRows.value, "inventory-stock.csv");
  if (action === "exportTrace") return exportCsv(traceRows.value, "inventory-trace.csv");
  if (action === "exportWeeklyReport") return exportWeeklyReport();
};

const handleWorkflowStep = (action: WorkflowAction) => {
  const step = workflowSteps.find(item => item.action === action);
  if (step && !hasAnyInventoryAuth(step.auth)) {
    ElMessage.warning("当前岗位暂无该流程动作权限，请由对应负责人处理");
    return;
  }
  if (action === "item") return openItemDialog();
  if (action === "inbound") return openInboundDialog(db.value.items[0]);
  if (action === "request") return openRequestDialog();
  if (action === "weekly") return openWeeklyDialog();
  if (action === "controls") return openCountDialog();
  if (action === "requests") return goTab("requests");
};

const openTodo = (row: TodoRow) => {
  if (!row.request) {
    goTab(row.tab);
    return;
  }
  if (row.action === "approve") {
    if (!requireInventoryAuth("inventory:approve", "审核申领")) return;
    approveRequest(row.request);
    return;
  }
  if (row.action === "issue") {
    if (!requireInventoryAuth("inventory:issue", "发放物资")) return;
    openIssueDialog(row.request);
    return;
  }
  if (row.action === "receive") {
    if (!requireInventoryAuth("inventory:receive", "签收物资")) return;
    receiveRequest(row.request);
    return;
  }
  goTab(row.tab);
};

const exportWeeklyReport = () => {
  const rows: Record<string, unknown>[] = [
    { section: "红绿灯", metric: "当前结论", value: executiveSignal.value.title, note: executiveSignal.value.desc },
    { section: "红绿灯", metric: "紧急事项", value: executiveUrgentCount.value, note: "过期、原因缺失或高风险异常" },
    { section: "红绿灯", metric: "关注事项", value: executiveAttentionCount.value, note: "低库存、临期、待办与盘点差异" },
    ...executiveKpis.value.map(item => ({
      section: "关键指标",
      metric: item.label,
      value: item.value,
      note: item.desc
    })),
    ...departmentConsumptionTop.value.map((item, index) => ({
      section: "科室消耗TOP",
      metric: `${index + 1}. ${item.department}`,
      value: item.value,
      note: "基于周消耗记录"
    })),
    ...riskRows.value.slice(0, 12).map(item => ({
      section: "风险清单",
      metric: item.type,
      value: item.subject,
      note: `${item.status}；${item.suggestion}`
    }))
  ];
  exportCsv(rows, "inventory-weekly-report.csv");
};

onMounted(() => {
  workspaceCenter.value = defaultWorkspaceCenter.value;
  loadInventory();
});
</script>

<style scoped lang="scss">
.inventory-page {
  --inventory-bg: #f4f7f9;
  --inventory-panel: #ffffff;
  --inventory-line: #dfe7ee;
  --inventory-line-soft: #edf1f5;
  --inventory-text: #17212b;
  --inventory-muted: #647282;
  --inventory-primary: #08766f;
  --inventory-primary-soft: #e8f5f3;
  --inventory-danger: #c83232;
  --inventory-danger-soft: #fff0f0;
  --inventory-warning: #b7791f;
  --inventory-warning-soft: #fff7e6;
  --inventory-success: #23805f;
  --inventory-success-soft: #edf8f2;

  display: grid;
  gap: 12px;
  padding: 2px;
  color: var(--inventory-text);
}

.inventory-command,
.ribbon-lead,
.ribbon-metric,
.panel {
  background: var(--inventory-panel);
  border: 1px solid var(--inventory-line);
  border-radius: 8px;
  box-shadow: 0 1px 1px rgb(15 23 42 / 3%);
}

.inventory-command {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;

  .command-title {
    min-width: 0;
    padding-left: 10px;
    border-left: 4px solid var(--inventory-primary);
  }

  span {
    color: var(--inventory-primary);
    font-size: 12px;
    font-weight: 700;
  }

  h1,
  p {
    margin: 0;
  }

  h1 {
    margin-top: 2px;
    font-size: 22px;
    line-height: 1.25;
  }

  p {
    max-width: 66ch;
    margin-top: 5px;
    overflow: hidden;
    color: var(--inventory-muted);
    font-size: 13px;
    line-height: 1.45;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.command-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
}

.status-ribbon {
  display: grid;
  grid-template-columns: minmax(220px, 0.95fr) repeat(4, minmax(120px, 1fr));
  gap: 8px;
}

.ribbon-lead,
.ribbon-metric {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 10px 12px;
}

.ribbon-lead {
  background: #f7fafb;

  span,
  strong,
  small {
    display: block;
  }

  span {
    color: var(--inventory-primary);
    font-size: 12px;
    font-weight: 700;
  }

  strong {
    color: var(--inventory-text);
    font-size: 15px;
    line-height: 1.35;
  }

  small {
    color: var(--el-text-color-secondary);
    line-height: 1.35;
  }
}

.ribbon-metric {
  display: grid;
  gap: 4px;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.16s ease,
    background 0.16s ease;

  span,
  strong,
  small {
    display: block;
  }

  span,
  small {
    color: var(--inventory-muted);
  }

  strong {
    color: var(--inventory-primary);
    font-size: 28px;
    line-height: 1.05;
    font-variant-numeric: tabular-nums;
  }

  &:hover {
    background: var(--inventory-primary-soft);
    border-color: rgb(8 118 111 / 26%);
  }

  &.warning {
    strong {
      color: var(--inventory-warning);
    }
  }

  &.danger {
    strong {
      color: var(--inventory-danger);
    }
  }
}

.module-switcher {
  display: grid;
  grid-template-columns: repeat(9, minmax(0, 1fr));
  gap: 0;
  overflow: hidden;
  background: var(--inventory-panel);
  border: 1px solid var(--inventory-line);
  border-radius: 8px;

  button {
    display: grid;
    gap: 2px;
    min-width: 0;
    padding: 9px 10px 8px;
    text-align: left;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-right: 1px solid var(--inventory-line-soft);
    border-radius: 0;
    transition:
      background 0.16s ease,
      color 0.16s ease;

    &:hover,
    &.active {
      background: #f5fbfa;
    }

    &.active {
      box-shadow: inset 0 -3px 0 var(--inventory-primary);

      span {
        color: var(--inventory-primary);
      }
    }

    &:last-child {
      border-right: 0;
    }
  }

  span,
  small {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: var(--inventory-text);
    font-size: 14px;
    font-weight: 700;
  }

  small {
    color: var(--inventory-muted);
    font-size: 12px;
  }
}

.inventory-workspace {
  display: grid;
  gap: 12px;
}

.inventory-loading-skeleton {
  padding: 18px;
  background: #ffffff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.workspace-pane {
  display: grid;
  gap: 12px;
}

.inventory-fade-enter-active,
.inventory-fade-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}

.inventory-fade-enter-from,
.inventory-fade-leave-to {
  opacity: 0;
  transform: translateY(4px);
}

.pane-grid,
.control-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(360px, 0.72fr);
  gap: 12px;
}

.panel {
  padding: 13px 14px;
}

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

.quick-control {
  align-self: start;
}

.danger {
  color: #dc2626;
}

:deep(.el-table) {
  --el-table-border-color: var(--inventory-line-soft);
  --el-table-header-bg-color: #f7fafb;
  --el-table-header-text-color: #44515f;
  --el-table-text-color: var(--inventory-text);

  font-size: 13px;

  th.el-table__cell {
    font-weight: 700;
  }
}

.panel-head.compact {
  margin-bottom: 12px;
}

.ml6 {
  margin-left: 6px;
}

.form-hint {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.45;
}

.request-lines-editor,
.issue-lines-editor {
  display: grid;
  width: 100%;
  gap: 10px;
}

.request-line-editor,
.issue-line-editor {
  display: grid;
  align-items: center;
  gap: 10px;
  padding: 10px;
  background: rgb(248 250 252 / 86%);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.request-line-editor {
  grid-template-columns: minmax(220px, 1fr) 160px 44px 54px;
}

.issue-line-editor {
  grid-template-columns: minmax(220px, 1fr) 180px;

  div {
    display: grid;
    gap: 3px;
  }

  strong,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.weekly-assist {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr)) auto;
  gap: 8px;
  align-items: center;
  padding: 10px;
  margin: 0 0 14px 112px;
  background: rgb(240 253 250 / 72%);
  border: 1px solid rgb(20 184 166 / 16%);
  border-radius: 8px;

  div {
    display: grid;
    gap: 2px;
  }

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  strong {
    color: var(--el-text-color-primary);
    font-size: 14px;
    font-variant-numeric: tabular-nums;
  }
}

@media (max-width: 1080px) {
  .inventory-command,
  .pane-grid,
  .control-grid {
    grid-template-columns: 1fr;
  }

  .inventory-command {
    display: grid;
  }

  .command-actions {
    flex-wrap: wrap;
  }

  .status-ribbon {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ribbon-lead {
    grid-column: 1 / -1;
  }

  .module-switcher {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .request-line-editor,
  .issue-line-editor {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .inventory-command {
    padding: 12px;

    p {
      white-space: normal;
    }
  }

  .status-ribbon {
    grid-template-columns: 1fr;
  }

  .command-actions {
    width: 100%;

    .el-button {
      flex: 1;
    }
  }

  .module-switcher {
    grid-template-columns: 1fr;
  }

  .weekly-assist {
    grid-template-columns: 1fr;
    margin-left: 0;
  }
}
</style>
