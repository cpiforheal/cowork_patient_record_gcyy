<template>
  <section class="package-layout">
    <el-tabs v-model="activeSection" class="package-tabs">
      <el-tab-pane label="门诊 / 住院套餐" name="packages">
        <div class="panel package-panel">
          <div class="panel-head">
            <div>
              <h2>门诊 / 住院使用套餐</h2>
              <p>按科室、照护类型和关键阶段维护套餐，仅最新启用且处于生效期的版本参与匹配。</p>
            </div>
            <el-button v-if="canManage" type="primary" :icon="Plus" @click="openCreate">新建套餐</el-button>
          </div>

          <div class="table-toolbar">
            <el-input v-model="filters.keyword" clearable placeholder="搜索套餐、科室" />
            <el-select v-model="filters.careType" clearable placeholder="照护类型">
              <el-option label="门诊" value="outpatient" />
              <el-option label="住院" value="inpatient" />
            </el-select>
            <el-select v-model="filters.status" clearable placeholder="状态">
              <el-option label="草稿" value="draft" />
              <el-option label="已启用" value="enabled" />
              <el-option label="已停用" value="disabled" />
            </el-select>
          </div>

          <el-table :data="filteredPackages" border>
            <el-table-column prop="name" label="套餐名称" min-width="180" />
            <el-table-column prop="department" label="科室" width="130" />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">{{ careTypeLabel(row.careType) }}</template>
            </el-table-column>
            <el-table-column label="触发阶段" width="100">
              <template #default="{ row }">{{ stageLabel(row.triggerStage) }}</template>
            </el-table-column>
            <el-table-column label="版本" width="90">
              <template #default="{ row }">v{{ row.version || 1 }}</template>
            </el-table-column>
            <el-table-column label="物资项" width="90">
              <template #default="{ row }">{{ row.lines?.length || 0 }} 项</template>
            </el-table-column>
            <el-table-column prop="effectiveDate" label="生效日期" width="120" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="statusTag(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="canManage && row.status === 'enabled'"
                  link
                  type="primary"
                  @click="openNewVersion(row as InventoryPackage)"
                >
                  创建新版本
                </el-button>
                <el-button v-else-if="canManage" link type="primary" @click="openEdit(row as InventoryPackage)">编辑</el-button>
                <el-button v-if="canManage && row.status !== 'enabled'" link type="success" @click="emitEnable(row)"
                  >启用</el-button
                >
                <el-button v-if="canManage && row.status === 'enabled'" link type="warning" @click="emitDisable(row)"
                  >停用</el-button
                >
              </template>
            </el-table-column>
            <template #empty><el-empty description="暂无使用套餐" /></template>
          </el-table>
        </div>
      </el-tab-pane>

      <el-tab-pane label="套餐覆盖检查" name="coverage">
        <div class="panel package-panel">
          <div class="panel-head">
            <div>
              <h2>科室套餐覆盖情况</h2>
              <p>正式运行前，门诊和住院的检查、中医、医生、手术四个关键阶段都应配置有效且非空的套餐。</p>
            </div>
            <el-tag :type="uncoveredCount ? 'danger' : 'success'" effect="plain">
              {{ uncoveredCount ? `${uncoveredCount} 项未覆盖` : "已全部覆盖" }}
            </el-tag>
          </div>
          <el-table :data="coverage" border max-height="520">
            <el-table-column prop="department" label="科室" min-width="130" />
            <el-table-column label="口径" width="90">
              <template #default="{ row }">{{ careTypeLabel(row.careType) }}</template>
            </el-table-column>
            <el-table-column label="关键阶段" width="110">
              <template #default="{ row }">{{ stageLabel(row.triggerStage) }}</template>
            </el-table-column>
            <el-table-column prop="packageName" label="当前有效套餐" min-width="180">
              <template #default="{ row }">{{ row.packageName || "未配置" }}</template>
            </el-table-column>
            <el-table-column label="版本" width="80">
              <template #default="{ row }">{{ row.packageVersion ? `v${row.packageVersion}` : "-" }}</template>
            </el-table-column>
            <el-table-column prop="lineCount" label="物资项" width="80" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.covered ? 'success' : 'danger'" effect="light">
                  {{ row.covered ? "可用" : "缺失" }}
                </el-tag>
              </template>
            </el-table-column>
            <template #empty><el-empty description="暂无科室覆盖数据" /></template>
          </el-table>
        </div>
      </el-tab-pane>

      <el-tab-pane label="映射待确认" name="mapping">
        <div v-loading="mappingLoading" class="panel mapping-panel">
          <div class="panel-head">
            <div>
              <h2>V18 耗材映射待确认</h2>
              <p>只维护映射治理数据；仅患者单次套餐在满足物资、阶段、单位和数量后可生成 draft 套餐。</p>
            </div>
            <el-tag effect="plain">共 {{ mappingSummary?.total || mappingTotal || 0 }} 条</el-tag>
          </div>

          <div class="mapping-summary-grid">
            <div v-for="card in mappingSummaryCards" :key="card.label" class="mapping-summary-card">
              <span>{{ card.label }}</span>
              <strong>{{ card.value }}</strong>
            </div>
          </div>

          <div class="table-toolbar mapping-toolbar">
            <el-select v-model="mappingFilters.department" clearable filterable placeholder="科室" @change="loadMappings(1)">
              <el-option v-for="department in mappingDepartmentOptions" :key="department" :label="department" :value="department" />
            </el-select>
            <el-select v-model="mappingFilters.ruleType" clearable placeholder="规则类型" @change="loadMappings(1)">
              <el-option label="患者单次套餐" value="患者单次套餐" />
              <el-option label="条件套餐" value="条件套餐" />
              <el-option label="固定运行消耗" value="固定运行消耗" />
              <el-option label="按需申领" value="按需申领" />
            </el-select>
            <el-select v-model="mappingFilters.status" clearable placeholder="状态" @change="loadMappings(1)">
              <el-option label="待确认" value="pending" />
              <el-option label="已确认" value="confirmed" />
              <el-option label="已搁置" value="held" />
            </el-select>
            <el-input
              v-model="mappingFilters.keyword"
              clearable
              placeholder="物资、用法、备注"
              @clear="loadMappings(1)"
              @keyup.enter="loadMappings(1)"
            />
            <el-button @click="loadMappings(1)">筛选</el-button>
          </div>

          <el-table :data="mappingEntries" border max-height="560">
            <el-table-column prop="department" label="科室" width="110" />
            <el-table-column prop="ruleType" label="规则类型" width="126" />
            <el-table-column label="状态" width="92">
              <template #default="{ row }">
                <el-tag :type="mappingStatusTag(row.status)" effect="light">{{ mappingStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="sourceItemName" label="原始物资" min-width="150" show-overflow-tooltip />
            <el-table-column prop="sourceUsage" label="用法" min-width="120" show-overflow-tooltip />
            <el-table-column prop="sourceNote" label="备注" min-width="140" show-overflow-tooltip />
            <el-table-column label="建议数量/单位" width="128">
              <template #default="{ row }">{{ quantityLabel(row as InventoryMappingEntry) }}</template>
            </el-table-column>
            <el-table-column label="匹配物资" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ row.matchedItemName || "未匹配" }}</template>
            </el-table-column>
            <el-table-column label="阶段" width="112">
              <template #default="{ row }">{{ stageLabel(row.triggerStage) || row.importStatus || "-" }}</template>
            </el-table-column>
            <el-table-column prop="maturity" label="成熟度" width="112" />
            <el-table-column prop="cannotPublishReason" label="不能发布原因" min-width="220" show-overflow-tooltip />
            <el-table-column v-if="canManageMapping" label="操作" width="210" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openMappingConfirm(row as InventoryMappingEntry)">确认</el-button>
                <el-button link type="warning" @click="emitHoldMapping(row)">搁置</el-button>
                <el-button link type="success" :disabled="!row.canCreatePackageDraft" @click="emitCreateMappingDraft(row)">
                  生成草稿
                </el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="暂无映射记录" /></template>
          </el-table>

          <div class="mapping-pagination">
            <el-pagination
              background
              layout="total, sizes, prev, pager, next"
              :total="mappingTotal"
              :page-size="mappingFilters.size"
              :current-page="mappingFilters.page"
              :page-sizes="[20, 50, 100, 200]"
              @current-change="loadMappings"
              @size-change="changeMappingPageSize"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane :label="`扣减失败任务${failedEvents.length ? ` (${failedEvents.length})` : ''}`" name="exceptions">
        <div class="panel event-panel">
          <div class="panel-head">
            <div>
              <h2>自动扣减任务</h2>
              <p>每个病历、阶段、完成版本和命令类型生成一条幂等任务；失败后修复原因再重试。</p>
            </div>
            <el-tag effect="plain">共 {{ events.length }} 条</el-tag>
          </div>
          <el-table :data="displayEvents" border max-height="460">
            <el-table-column prop="visitDate" label="就诊日期" width="112" />
            <el-table-column prop="department" label="科室" width="110" />
            <el-table-column label="业务类型" width="90">
              <template #default="{ row }">{{ careTypeLabel(row.careType || row.route) }}</template>
            </el-table-column>
            <el-table-column prop="triggerStage" label="触发阶段" width="100" />
            <el-table-column prop="packageName" label="使用套餐" min-width="170" />
            <el-table-column prop="packageVersion" label="套餐版本" width="90" />
            <el-table-column prop="encounterId" label="就诊标识" min-width="170" show-overflow-tooltip />
            <el-table-column prop="commandId" label="命令号" min-width="170" show-overflow-tooltip />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="eventTag(row.status)" effect="light">{{ eventLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="失败原因" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">{{ row.errorMessage || "-" }}</template>
            </el-table-column>
            <el-table-column v-if="canManage" label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'failed'" link type="primary" @click="emitRetry(row)">重试</el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="暂无自动消耗事件" /></template>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="760px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="92px" status-icon>
        <div class="form-grid">
          <el-form-item label="套餐名称" prop="name">
            <el-input v-model="form.name" maxlength="60" placeholder="例如：肛肠门诊基础耗材" />
          </el-form-item>
          <el-form-item label="科室" prop="department">
            <el-select v-model="form.department" filterable allow-create placeholder="请选择科室">
              <el-option v-for="department in departmentOptions" :key="department" :label="department" :value="department" />
            </el-select>
          </el-form-item>
          <el-form-item label="照护类型" prop="careType">
            <el-radio-group v-model="form.careType">
              <el-radio-button label="outpatient">门诊</el-radio-button>
              <el-radio-button label="inpatient">住院</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="触发阶段" prop="triggerStage">
            <el-select v-model="form.triggerStage" placeholder="选择实际扣减触发点">
              <el-option label="检查完成" value="INSPECTION" />
              <el-option label="中医完成" value="TCM" />
              <el-option label="医生完成" value="DOCTOR" />
              <el-option label="手术医生确认" value="SURGERY" />
            </el-select>
          </el-form-item>
          <el-form-item label="生效日期">
            <el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD" placeholder="留空立即可用" />
          </el-form-item>
        </div>
        <div class="line-head">
          <strong>套餐物资明细</strong>
          <el-button link type="primary" :icon="Plus" @click="addLine">添加物资</el-button>
        </div>
        <div v-for="(line, index) in form.lines" :key="line.localId" class="line-editor">
          <el-select v-model="line.itemId" filterable placeholder="选择物资">
            <el-option v-for="item in items" :key="item.id" :label="`${item.name} / ${item.unit}`" :value="item.id" />
          </el-select>
          <el-input-number v-model="line.quantity" :min="0.01" :precision="2" controls-position="right" />
          <el-tag class="consumption-mode" type="info" effect="plain">每患者/阶段</el-tag>
          <el-button circle text type="danger" :icon="Delete" aria-label="删除物资" @click="removeLine(index)" />
        </div>
        <el-empty v-if="!form.lines.length" description="请添加至少一项物资" :image-size="64" />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="mappingDialogVisible" title="确认耗材映射" width="720px" destroy-on-close>
      <el-form :model="mappingForm" label-width="110px">
        <div class="form-grid">
          <el-form-item label="匹配物资">
            <el-select v-model="mappingForm.itemId" filterable placeholder="选择系统物资">
              <el-option v-for="item in items" :key="item.id" :label="`${item.name} / ${item.unit}`" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="科室">
            <el-select v-model="mappingForm.department" filterable allow-create placeholder="选择科室">
              <el-option v-for="department in departmentOptions" :key="department" :label="department" :value="department" />
            </el-select>
          </el-form-item>
          <el-form-item label="门诊/住院">
            <el-radio-group v-model="mappingForm.careType">
              <el-radio-button label="outpatient">门诊</el-radio-button>
              <el-radio-button label="inpatient">住院</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="触发阶段">
            <el-select v-model="mappingForm.triggerStage" placeholder="选择触发阶段">
              <el-option label="检查完成" value="INSPECTION" />
              <el-option label="中医完成" value="TCM" />
              <el-option label="医生完成" value="DOCTOR" />
              <el-option label="手术确认" value="SURGERY" />
            </el-select>
          </el-form-item>
          <el-form-item label="数量">
            <el-input-number v-model="mappingForm.suggestedQuantity" :min="0" :precision="2" controls-position="right" />
          </el-form-item>
          <el-form-item label="单位">
            <el-input v-model="mappingForm.suggestedUnit" placeholder="如 个、支、mL" />
          </el-form-item>
        </div>
        <el-form-item label="备注">
          <el-input v-model="mappingForm.note" type="textarea" :rows="3" placeholder="记录业务确认口径" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mappingDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitMappingConfirm">保存确认</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { Delete, Plus } from "@element-plus/icons-vue";
import type { FormInstance, FormRules } from "element-plus";
import type {
  ConfirmInventoryMappingEntriesParams,
  InventoryCareType,
  InventoryConsumptionEvent,
  InventoryItem,
  InventoryMappingEntry,
  InventoryMappingEntryQueryParams,
  InventoryMappingSummary,
  InventoryPackage,
  InventoryPackageCoverage,
  InventoryPackageLine,
  InventoryPackageStatus,
  InventoryTriggerStage,
  SaveInventoryPackageParams
} from "@/api/modules/inventory";

const props = defineProps<{
  packages: InventoryPackage[];
  coverage: InventoryPackageCoverage[];
  events: InventoryConsumptionEvent[];
  items: InventoryItem[];
  mappingSummary?: InventoryMappingSummary;
  mappingEntries: InventoryMappingEntry[];
  mappingTotal: number;
  mappingLoading?: boolean;
  departmentOptions: string[];
  canManage: boolean;
  canManageMapping: boolean;
  saving?: boolean;
}>();

const emit = defineEmits<{
  save: [payload: SaveInventoryPackageParams];
  enable: [row: InventoryPackage];
  disable: [row: InventoryPackage];
  retry: [row: InventoryConsumptionEvent];
  "load-mappings": [params: InventoryMappingEntryQueryParams];
  "confirm-mapping": [payload: ConfirmInventoryMappingEntriesParams];
  "hold-mapping": [row: InventoryMappingEntry];
  "create-mapping-draft": [row: InventoryMappingEntry];
}>();

const filters = reactive({ keyword: "", careType: "", status: "" });
const mappingFilters = reactive<
  Required<Pick<InventoryMappingEntryQueryParams, "page" | "size">> & Omit<InventoryMappingEntryQueryParams, "page" | "size">
>({
  ruleType: "",
  status: "",
  department: "",
  keyword: "",
  page: 1,
  size: 20
});
const activeSection = ref("packages");
const dialogVisible = ref(false);
const mappingDialogVisible = ref(false);
const editingId = ref("");
const creatingVersion = ref(false);
const formRef = ref<FormInstance>();
const form = reactive<SaveInventoryPackageParams & { lines: (InventoryPackageLine & { localId: string })[] }>({
  name: "",
  department: "",
  careType: "outpatient",
  triggerStage: "INSPECTION",
  effectiveDate: "",
  lines: []
});
const mappingForm = reactive<ConfirmInventoryMappingEntriesParams>({
  id: "",
  itemId: "",
  department: "",
  careType: "outpatient",
  triggerStage: "INSPECTION",
  suggestedQuantity: 0,
  suggestedUnit: "",
  note: ""
});
const rules = reactive<FormRules>({
  name: [{ required: true, message: "请输入套餐名称", trigger: "blur" }],
  department: [{ required: true, message: "请选择科室", trigger: "change" }],
  careType: [{ required: true, message: "请选择照护类型", trigger: "change" }],
  triggerStage: [{ required: true, message: "请选择触发阶段", trigger: "change" }]
});

const filteredPackages = computed(() => {
  const keyword = filters.keyword.trim().toLowerCase();
  return props.packages.filter(row => {
    if (filters.careType && row.careType !== filters.careType) return false;
    if (filters.status && row.status !== filters.status) return false;
    return !keyword || `${row.name} ${row.department}`.toLowerCase().includes(keyword);
  });
});
const failedEvents = computed(() => props.events.filter(row => row.status === "failed"));
const uncoveredCount = computed(() => props.coverage.filter(row => !row.covered).length);
const displayEvents = computed(() =>
  [...props.events].sort((a, b) => Number(b.status === "failed") - Number(a.status === "failed"))
);
const mappingDepartmentOptions = computed(() =>
  [...new Set([...props.departmentOptions, ...props.mappingEntries.map(row => row.department).filter(Boolean)])].sort()
);
const ruleCount = (ruleType: string) => props.mappingSummary?.byRuleType?.find(row => row.label === ruleType)?.total || 0;
const mappingSummaryCards = computed(() => [
  { label: "总数", value: props.mappingSummary?.total ?? props.mappingTotal ?? 0 },
  { label: "患者单次", value: props.mappingSummary?.patientOnce ?? ruleCount("患者单次套餐") },
  { label: "条件套餐", value: props.mappingSummary?.conditionalPackage ?? ruleCount("条件套餐") },
  { label: "固定运行", value: props.mappingSummary?.fixedRunning ?? ruleCount("固定运行消耗") },
  { label: "按需申领", value: props.mappingSummary?.onDemand ?? ruleCount("按需申领") },
  { label: "可生成草稿", value: props.mappingSummary?.canCreatePackageDraft ?? 0 },
  { label: "待补充", value: props.mappingSummary?.needsSupplement ?? 0 }
]);

const dialogTitle = computed(() => {
  if (creatingVersion.value) return "创建使用套餐新版本";
  return editingId.value ? "编辑使用套餐" : "新建使用套餐";
});

const careTypeLabel = (value?: InventoryCareType | string) =>
  value === "inpatient" ? "住院" : value === "outpatient" ? "门诊" : value || "-";
const stageLabel = (value?: InventoryPackageCoverage["triggerStage"] | string) =>
  ({ INSPECTION: "检查", TCM: "中医", DOCTOR: "医生", SURGERY: "手术" })[value as InventoryPackageCoverage["triggerStage"]] ||
  value ||
  "-";
const statusLabel = (value: InventoryPackageStatus) => ({ draft: "草稿", enabled: "已启用", disabled: "已停用" })[value];
const statusTag = (value: InventoryPackageStatus) =>
  ({ draft: "info", enabled: "success", disabled: "warning" })[value] as "info" | "success" | "warning";
const eventLabel = (value: InventoryConsumptionEvent["status"]) =>
  ({ pending: "处理中", success: "已扣减", succeeded: "已扣减", failed: "失败", reversed: "已冲销" })[value];
const eventTag = (value: InventoryConsumptionEvent["status"]) =>
  ({ pending: "info", success: "success", succeeded: "success", failed: "danger", reversed: "warning" })[value] as
    | "info"
    | "success"
    | "danger"
    | "warning";
const newLine = (): InventoryPackageLine & { localId: string } => ({
  localId: `${Date.now()}-${Math.random()}`,
  itemId: "",
  quantity: 1,
  consumptionMode: "per_visit"
});
const emitEnable = (row: unknown) => emit("enable", row as InventoryPackage);
const emitDisable = (row: unknown) => emit("disable", row as InventoryPackage);
const emitRetry = (row: unknown) => emit("retry", row as InventoryConsumptionEvent);
const mappingStatusLabel = (value?: string) =>
  ({ pending: "待确认", confirmed: "已确认", held: "已搁置" })[value || ""] || value || "-";
const mappingStatusTag = (value?: string) =>
  ({ pending: "warning", confirmed: "success", held: "info" })[value || ""] as "warning" | "success" | "info";
const quantityLabel = (row: InventoryMappingEntry) =>
  row.suggestedQuantity === undefined || row.suggestedQuantity === null || row.suggestedQuantity === 0
    ? "待补充" + (row.suggestedUnit ? " / " + row.suggestedUnit : "")
    : String(row.suggestedQuantity) + (row.suggestedUnit || "");
const loadMappings = (page = mappingFilters.page) => {
  mappingFilters.page = page;
  emit("load-mappings", { ...mappingFilters });
};
const changeMappingPageSize = (size: number) => {
  mappingFilters.size = size;
  loadMappings(1);
};
const openMappingConfirm = (row: InventoryMappingEntry) => {
  Object.assign(mappingForm, {
    id: row.id,
    itemId: row.matchedItemId || "",
    department: row.department,
    departmentId: row.departmentId,
    careType: row.careType === "inpatient" ? "inpatient" : "outpatient",
    triggerStage: row.triggerStage && !["待确认", "待扩展", "不适用"].includes(String(row.triggerStage)) ? row.triggerStage : "INSPECTION",
    suggestedQuantity: Number(row.suggestedQuantity || 0),
    suggestedUnit: row.suggestedUnit || row.matchedItemUnit || "",
    note: row.note || row.sourceNote || ""
  });
  mappingDialogVisible.value = true;
};
const submitMappingConfirm = () => {
  emit("confirm-mapping", { ...mappingForm, suggestedQuantity: Number(mappingForm.suggestedQuantity || 0) });
  mappingDialogVisible.value = false;
};
const emitHoldMapping = (row: unknown) => emit("hold-mapping", row as InventoryMappingEntry);
const emitCreateMappingDraft = (row: unknown) => emit("create-mapping-draft", row as InventoryMappingEntry);

const resetForm = () => {
  editingId.value = "";
  creatingVersion.value = false;
  Object.assign(form, {
    name: "",
    department: props.departmentOptions[0] || "",
    careType: "outpatient",
    triggerStage: "INSPECTION",
    effectiveDate: "",
    lines: [newLine()]
  });
};
const openCreate = () => {
  resetForm();
  dialogVisible.value = true;
};
const openEdit = (row: InventoryPackage) => {
  if (row.status === "enabled") {
    openNewVersion(row);
    return;
  }
  editingId.value = row.id;
  creatingVersion.value = false;
  Object.assign(form, {
    name: row.name,
    department: row.department,
    careType: row.careType,
    triggerStage: row.triggerStage,
    effectiveDate: row.effectiveDate || "",
    lines: (row.lines || []).map(line => ({ ...line, localId: `${line.id || line.itemId}-${Date.now()}` }))
  });
  dialogVisible.value = true;
};
const openNewVersion = (row: InventoryPackage) => {
  editingId.value = "";
  creatingVersion.value = true;
  Object.assign(form, {
    name: row.name,
    department: row.department,
    careType: row.careType,
    triggerStage: row.triggerStage,
    effectiveDate: row.effectiveDate || "",
    // A new package version must receive fresh package-line IDs from the API.
    lines: (row.lines || []).map(line => ({
      itemId: line.itemId,
      quantity: Number(line.quantity),
      consumptionMode: "per_visit" as const,
      localId: `${line.itemId}-${Date.now()}-${Math.random()}`
    }))
  });
  dialogVisible.value = true;
};
const addLine = () => form.lines.push(newLine());
const removeLine = (index: number) => form.lines.splice(index, 1);
const submit = async () => {
  if (!(await formRef.value?.validate().catch(() => false))) return;
  if (!form.lines.length || form.lines.some(line => !line.itemId || Number(line.quantity) <= 0)) return;
  emit("save", {
    id: editingId.value || undefined,
    name: form.name.trim(),
    department: form.department,
    careType: form.careType,
    triggerStage: form.triggerStage as InventoryTriggerStage,
    effectiveDate: form.effectiveDate || undefined,
    lines: form.lines.map(line => ({
      id: editingId.value ? line.id : undefined,
      itemId: line.itemId,
      quantity: Number(line.quantity),
      consumptionMode: "per_visit"
    }))
  });
  dialogVisible.value = false;
};

watch(activeSection, value => {
  if (value === "mapping") loadMappings();
});
</script>

<style scoped lang="scss">
.package-layout {
  display: grid;
  gap: 12px;
}

.package-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.panel-head,
.line-head {
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

.table-toolbar,
.form-grid {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 180px 180px;
  gap: 8px;
  margin-bottom: 10px;
}

.form-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.mapping-toolbar {
  grid-template-columns: repeat(3, minmax(120px, 160px)) minmax(220px, 1fr) 88px;
}

.mapping-summary-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(96px, 1fr));
  gap: 8px;
  margin-bottom: 10px;
}

.mapping-summary-card {
  padding: 10px 12px;
  border: 1px solid var(--inventory-line-soft);
  border-radius: 6px;
  background: #ffffff;

  span {
    display: block;
    color: var(--inventory-muted);
    font-size: 12px;
  }

  strong {
    display: block;
    margin-top: 4px;
    color: var(--inventory-text);
    font-size: 18px;
  }
}

.mapping-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.line-head {
  align-items: center;
  padding-top: 4px;
  margin-top: 6px;
  border-top: 1px solid var(--inventory-line-soft);
}

.line-editor {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 130px 150px 40px;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

@media (max-width: 820px) {
  .table-toolbar,
  .mapping-toolbar,
  .mapping-summary-grid,
  .form-grid,
  .line-editor {
    grid-template-columns: 1fr;
  }
}
</style>
