<template>
  <section class="usage-workbench" v-loading="loading">
    <header class="usage-toolbar">
      <div class="usage-copy">
        <span class="eyebrow">耗材工作台 · 日结</span>
        <div class="title-line">
          <h2>{{ title }}</h2>
          <el-tag :type="closureTagType" effect="plain" round>{{ closureLabel }}</el-tag>
          <span v-if="isPastDate" class="lock-note">{{ closure?.locked ? "已过业务日，已锁定" : "补结历史业务日" }}</span>
        </div>
        <p>汇总当天已录耗材；先补齐未录项，再提交日结。提交后进入库管审核。</p>
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
        <el-dropdown trigger="click" @command="exportDay">
          <el-button :loading="exporting">
            导出<el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="xlsx">导出 XLSX</el-dropdown-item>
              <el-dropdown-item command="csv">导出 CSV</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button type="primary" :disabled="closure?.locked" @click="openCloseDialog">提交日结</el-button>
      </div>
    </header>

    <div class="usage-progress">
      <div class="progress-main">
        <strong>{{ completedCount }}<span>/{{ scopeCount }}</span></strong>
        <small>{{ mode === "DAILY" ? "日报已提交" : mode === "ALL" ? "已完成科室项" : "患者已提交" }}</small>
      </div>
      <div class="progress-pills">
        <span v-if="mode !== 'DAILY'" class="pill" :class="{ danger: totals.missingPatientCount }">未录 {{ totals.missingPatientCount || 0 }}</span>
        <span v-if="mode !== 'DAILY'" class="pill" :class="{ warn: totals.draft }">草稿 {{ totals.draft || 0 }}</span>
        <span v-if="mode !== 'DAILY'" class="pill" :class="{ danger: totals.returned }">被驳回 {{ totals.returned || 0 }}</span>
        <span v-if="mode !== 'DAILY'" class="pill">未使用 {{ totals.noConsumption || 0 }}</span>
        <span v-if="mode !== 'ALL'" class="pill">耗材 {{ totals.itemCount || 0 }} 种</span>
        <span v-if="mode === 'DAILY'" class="pill" :class="{ danger: totals.reportMissing }">缺失日报 {{ totals.reportMissing || 0 }}</span>
      </div>
      <div class="progress-track" :aria-label="`完成度 ${progressPercent}%`">
        <span :style="{ width: `${progressPercent}%` }" />
      </div>
    </div>

    <div v-if="missingRows.length" class="missing-panel">
      <button type="button" class="missing-head" @click="missingExpanded = !missingExpanded">
        <span class="missing-dot" />
        <strong>还有 {{ missingRows.length }} 项需要补录</strong>
        <span class="missing-hint">{{ missingExpanded ? "收起" : "展开查看" }}</span>
      </button>
      <div v-if="missingExpanded" class="missing-list">
        <button
          v-for="row in missingRows"
          :key="row.key"
          type="button"
          class="missing-row"
          @click="goFill(row.departmentKey)"
        >
          <span class="missing-dept">{{ row.departmentName }}</span>
          <span class="missing-name">{{ row.name }}</span>
          <span class="missing-reason">{{ row.reason }}</span>
          <span class="missing-go">去补录 →</span>
        </button>
      </div>
    </div>

    <div v-if="items.length" class="usage-table-wrap">
      <el-table :data="items" class="usage-table" height="100%" @row-click="openItem">
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
            <el-button text type="primary">使用名单</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <div v-else-if="!loading" class="usage-empty">
      <el-empty :image-size="72" description="今天还没有耗材记录" />
      <p>科室录入后，这里会按耗材汇总；也可以先提交日结检查缺失项。</p>
    </div>

    <el-drawer v-model="detailVisible" :title="`${selectedItem?.itemName || ''} · 使用名单`" size="70%" append-to-body>
      <div v-loading="detailLoading" class="patient-list">
        <el-table :data="patients">
          <el-table-column prop="patientName" label="患者 / 日报" min-width="140">
            <template #default="{ row }">
              {{ row.patientName }}
              <el-tag v-if="row.sourceType === 'DAILY'" size="small" type="info" effect="plain">科室日报</el-tag>
              <el-tag v-if="row.attentionFlag" size="small" type="danger" effect="plain">重点</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="visitNo" label="就诊号" width="120">
            <template #default="{ row }">{{ row.visitNo || "—" }}</template>
          </el-table-column>
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
        <el-empty v-if="!patients.length && !detailLoading" description="该耗材今天没有使用记录" />
      </div>
    </el-drawer>

    <el-dialog v-model="closeVisible" title="提交日结" width="560px" append-to-body>
      <div class="close-body">
        <div class="close-summary">
          <div><small>业务日期</small><strong>{{ date }}</strong></div>
          <div><small>范围</small><strong>{{ scopeLabel }}</strong></div>
          <div><small>待完成</small><strong :class="{ danger: missingRows.length }">{{ missingRows.length }}</strong></div>
        </div>
        <p v-if="!closeResult" class="close-note">
          将提交当天所有患者清单或科室日报。未录患者、空草稿和未提交日报会阻止日结，提交后不能在本页继续修改。
        </p>
        <template v-else>
          <el-alert
            :type="closeResult.blocked ? 'warning' : 'success'"
            :title="closeResult.message"
            :closable="false"
            show-icon
          />
          <div v-if="closeResult.missingPatients.length" class="close-issues">
            <h4>未完成患者</h4>
            <button
              v-for="row in closeResult.missingPatients"
              :key="`${row.departmentKey}-${row.serviceInstanceId}`"
              type="button"
              class="missing-row"
              @click="goFill(row.departmentKey)"
            >
              <span class="missing-dept">{{ row.departmentName }}</span>
              <span class="missing-name">{{ row.patientName }}</span>
              <span class="missing-reason">{{ row.reason }}</span>
              <span class="missing-go">去补录 →</span>
            </button>
          </div>
          <div v-if="closeResult.failedPatients.length" class="close-issues">
            <h4>需要修改后重提</h4>
            <button
              v-for="row in closeResult.failedPatients"
              :key="`${row.departmentKey}-${row.serviceInstanceId}-failed`"
              type="button"
              class="missing-row"
              @click="goFill(row.departmentKey)"
            >
              <span class="missing-dept">{{ row.departmentName }}</span>
              <span class="missing-name">{{ row.patientName }}</span>
              <span class="missing-reason">{{ row.reason }}</span>
              <span class="missing-go">去处理 →</span>
            </button>
          </div>
          <div v-if="closeResult.missingReports.length" class="close-issues">
            <h4>未提交日报</h4>
            <button
              v-for="row in closeResult.missingReports"
              :key="`${row.departmentKey}-report`"
              type="button"
              class="missing-row"
              @click="goFill(row.departmentKey)"
            >
              <span class="missing-dept">{{ row.departmentName }}</span>
              <span class="missing-name">科室日报</span>
              <span class="missing-reason">{{ row.reason }}</span>
              <span class="missing-go">去处理 →</span>
            </button>
          </div>
        </template>
      </div>
      <template #footer>
        <el-button @click="closeVisible = false">关闭</el-button>
        <el-button v-if="!closeResult" type="primary" :loading="closing" :disabled="closure?.locked" @click="submitDay">
          确认提交
        </el-button>
        <el-button v-else type="primary" @click="closeVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { ArrowDown, Refresh } from "@element-plus/icons-vue";
import dayjs from "dayjs";
import {
  closeInventoryConsumableUsageDayApi,
  downloadInventoryConsumableUsageDayApi,
  getInventoryConsumableUsageApi,
  type InventoryConsumableUsageClosure,
  type InventoryConsumableUsageItem,
  type InventoryConsumableUsagePatient,
  type InventoryConsumableUsageTotals,
  type InventoryDayCloseResult
} from "@/api/modules/inventory";

const props = defineProps<{
  canViewAllDepartments?: boolean;
  today?: string;
}>();

const emit = defineEmits<{
  (event: "open-department", departmentKey: string): void;
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

const date = ref(props.today || dayjs().format("YYYY-MM-DD"));
const departmentKey = ref("");
const mode = ref<"PATIENT" | "DAILY" | "ALL">("ALL");
const items = ref<InventoryConsumableUsageItem[]>([]);
const totals = ref<InventoryConsumableUsageTotals>({ itemCount: 0, totalQuantity: 0, patientCount: 0 });
const closure = ref<InventoryConsumableUsageClosure | null>(null);
const missingPatients = ref<Array<{ departmentKey: string; departmentName: string; serviceInstanceId: string; patientName: string; reason: string }>>([]);
const missingReports = ref<Array<{ departmentKey: string; departmentName: string; reportId?: string; reason: string }>>([]);
const loading = ref(false);
const exporting = ref(false);
const detailVisible = ref(false);
const detailLoading = ref(false);
const selectedItem = ref<InventoryConsumableUsageItem | null>(null);
const patients = ref<InventoryConsumableUsagePatient[]>([]);
const missingExpanded = ref(true);
const closeVisible = ref(false);
const closing = ref(false);
const closeResult = ref<InventoryDayCloseResult | null>(null);

const title = computed(() => {
  if (mode.value === "DAILY") return `${departmentName.value} · 科室日报日结`;
  if (mode.value === "PATIENT") return `${departmentName.value} · 患者耗材日结`;
  return "全院耗材日结";
});

const departmentName = computed(
  () => departments.find(department => department.key === departmentKey.value)?.name || "全院"
);

const scopeLabel = computed(() =>
  departmentKey.value ? `${departmentName.value} · ${date.value}` : `全院有录入的科室 · ${date.value}`
);

const isPastDate = computed(() => dayjs(date.value).isBefore(dayjs().startOf("day")));

const closureLabel = computed(() => {
  if (!closure.value) return "未日结";
  switch (closure.value.status) {
    case "SUBMITTED":
      return "已日结";
    case "APPROVED":
      return "审核通过";
    case "LOCKED":
      return "已锁定";
    case "PARTIAL":
      return `部分完成 ${closure.value.closedDepartments || 0}/${closure.value.totalDepartments || 0}`;
    default:
      return "未日结";
  }
});

const closureTagType = computed<"success" | "warning" | "danger" | "info">(() => {
  switch (closure.value?.status) {
    case "SUBMITTED":
      return "success";
    case "APPROVED":
      return "success";
    case "LOCKED":
      return "info";
    case "PARTIAL":
      return "warning";
    default:
      return "info";
  }
});

const scopeCount = computed(() => {
  const total = (totals.value.patientTotal || 0) + (totals.value.reportTotal || 0) + (totals.value.reportMissing || 0);
  return total;
});

const completedCount = computed(() => (totals.value.entered || 0) + (totals.value.reportSubmitted || 0));

const progressPercent = computed(() => {
  if (closure.value && ["SUBMITTED", "APPROVED", "LOCKED"].includes(closure.value.status)) return 100;
  if (!scopeCount.value) return 0;
  return Math.min(100, Math.round((completedCount.value / scopeCount.value) * 100));
});

const missingRows = computed(() => [
  ...missingPatients.value.map(row => ({
    key: `patient-${row.departmentKey}-${row.serviceInstanceId}`,
    departmentKey: row.departmentKey,
    departmentName: row.departmentName,
    name: row.patientName,
    reason: row.reason
  })),
  ...missingReports.value.map(row => ({
    key: `report-${row.departmentKey}`,
    departmentKey: row.departmentKey,
    departmentName: row.departmentName,
    name: "科室日报",
    reason: row.reason
  }))
]);

const load = async () => {
  loading.value = true;
  try {
    const result = await getInventoryConsumableUsageApi({
      date: date.value,
      departmentKey: departmentKey.value || undefined
    });
    mode.value = result.data?.mode ?? "ALL";
    items.value = result.data?.items ?? [];
    totals.value = result.data?.totals ?? { itemCount: 0, totalQuantity: 0, patientCount: 0 };
    closure.value = result.data?.closure ?? null;
    missingPatients.value = (result.data?.missingPatients ?? []) as typeof missingPatients.value;
    missingReports.value = (result.data?.missingReports ?? []) as typeof missingReports.value;
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
    ElMessage.error(error instanceof Error ? error.message : "读取使用名单失败");
  } finally {
    detailLoading.value = false;
  }
};

const openCloseDialog = () => {
  closeResult.value = null;
  closeVisible.value = true;
};

const submitDay = async () => {
  closing.value = true;
  try {
    const result = await closeInventoryConsumableUsageDayApi({
      businessDate: date.value,
      departmentKey: departmentKey.value || undefined
    });
    closeResult.value = result.data ?? null;
    if (!result.data?.blocked) {
      ElMessage.success(result.data?.message || "日结已完成");
      await load();
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "提交日结失败");
  } finally {
    closing.value = false;
  }
};

const exportDay = async (format: string) => {
  exporting.value = true;
  try {
    const { blob, filename } = await downloadInventoryConsumableUsageDayApi(
      { date: date.value, departmentKey: departmentKey.value || undefined },
      format === "csv" ? "csv" : "xlsx"
    );
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
    URL.revokeObjectURL(url);
    ElMessage.success("日结明细已导出");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "导出日结明细失败");
  } finally {
    exporting.value = false;
  }
};

const goFill = (key: string) => {
  closeVisible.value = false;
  detailVisible.value = false;
  emit("open-department", key);
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
  --hos-bg-surface: #f8f5ef;
  --hos-border-light: #efe8dd;
  --hos-border-strong: #e2d8c9;
  --hos-text-primary: #2f2d27;
  --hos-text-secondary: #6d675c;
  --hos-text-muted: #9a9285;
  --hos-primary: #347d68;
  --hos-primary-soft: #edf7f1;
  --hos-warn: #b7791f;
  --hos-warn-soft: #fdf6e7;
  --hos-danger: #b54747;
  --hos-danger-soft: #fbefef;
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: calc(100vh - 150px);
  padding: 16px;
  overflow: hidden;
  background: var(--hos-bg-surface);
  box-sizing: border-box;
}

.usage-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 16px 18px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 16px;

  h2 {
    margin: 0;
    color: var(--hos-text-primary);
    font-size: 20px;
    font-weight: 700;
    letter-spacing: -0.01em;
  }

  p {
    margin: 6px 0 0;
    color: var(--hos-text-secondary);
    font-size: 12px;
    line-height: 1.5;
  }
}

.eyebrow {
  display: block;
  margin-bottom: 4px;
  color: var(--hos-primary);
  font-weight: 600;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.title-line {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.lock-note {
  color: var(--hos-warn);
  font-size: 12px;
}

.usage-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.department-select {
  width: 136px;
}

.usage-progress {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 16px;
  align-items: center;
  padding: 12px 18px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;
}

.progress-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 96px;

  strong {
    color: var(--hos-text-primary);
    font-size: 22px;
    font-variant-numeric: tabular-nums;
    line-height: 1.1;

    span {
      margin-left: 2px;
      color: var(--hos-text-muted);
      font-size: 13px;
      font-weight: 500;
    }
  }

  small {
    color: var(--hos-text-muted);
    font-size: 11px;
  }
}

.progress-pills {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  gap: 6px;
}

.pill {
  padding: 4px 10px;
  color: var(--hos-text-secondary);
  font-size: 12px;
  background: var(--hos-bg-surface);
  border: 1px solid var(--hos-border-light);
  border-radius: 999px;

  &.warn {
    color: var(--hos-warn);
    background: var(--hos-warn-soft);
    border-color: #f0dfb8;
  }

  &.danger {
    color: var(--hos-danger);
    background: var(--hos-danger-soft);
    border-color: #efd3d3;
  }
}

.progress-track {
  flex-basis: 100%;
  height: 4px;
  overflow: hidden;
  background: var(--hos-bg-surface);
  border-radius: 999px;

  span {
    display: block;
    height: 100%;
    background: var(--hos-primary);
    border-radius: inherit;
    transition: width 0.24s ease;
  }
}

.missing-panel {
  padding: 0 4px;
  background: var(--hos-bg-primary);
  border: 1px solid #f0dfb8;
  border-radius: 14px;
}

.missing-head {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 10px 14px;
  color: var(--hos-text-primary);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;

  &:focus-visible {
    outline: 2px solid var(--hos-primary);
    outline-offset: 2px;
    border-radius: 12px;
  }
}

.missing-dot {
  width: 8px;
  height: 8px;
  background: var(--hos-warn);
  border-radius: 50%;
}

.missing-hint {
  margin-left: auto;
  color: var(--hos-text-muted);
  font-size: 12px;
}

.missing-list {
  max-height: 148px;
  padding: 0 10px 10px;
  overflow: auto;
}

.missing-row {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 8px 10px;
  color: var(--hos-text-primary);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  background: var(--hos-bg-surface);
  border: 1px solid transparent;
  border-radius: 10px;

  & + & {
    margin-top: 4px;
  }

  &:hover,
  &:focus-visible {
    background: var(--hos-warn-soft);
    border-color: #f0dfb8;
    outline: none;
  }
}

.missing-dept {
  min-width: 64px;
  color: var(--hos-text-secondary);
}

.missing-name {
  font-weight: 600;
}

.missing-reason {
  flex: 1;
  color: var(--hos-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.missing-go {
  color: var(--hos-primary);
  white-space: nowrap;
}

.usage-table-wrap {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.usage-table {
  height: 100%;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;
}

.usage-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 0;
  color: var(--hos-text-muted);
  text-align: center;
  background: var(--hos-bg-primary);
  border: 1px dashed var(--hos-border-strong);
  border-radius: 14px;

  p {
    margin: -4px 0 0;
    font-size: 12px;
  }
}

.patient-list {
  min-height: 300px;
}

.close-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.close-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;

  > div {
    display: flex;
    flex-direction: column;
    gap: 3px;
    padding: 10px 12px;
    background: #f8f5ef;
    border-radius: 10px;
  }

  small {
    color: #9a9285;
    font-size: 11px;
  }

  strong {
    color: #2f2d27;
    font-size: 15px;

    &.danger {
      color: #b54747;
    }
  }
}

.close-note {
  margin: 0;
  color: #6d675c;
  font-size: 13px;
  line-height: 1.6;
}

.close-issues {
  h4 {
    margin: 0 0 6px;
    color: #2f2d27;
    font-size: 13px;
  }

  & + & {
    margin-top: 8px;
  }
}

@media (max-width: 760px) {
  .usage-workbench {
    height: auto;
    min-height: calc(100vh - 120px);
    padding: 10px;
    overflow: visible;
  }

  .close-summary {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .progress-track span {
    transition: none;
  }
}
</style>
