<template>
  <section class="admin-review" v-loading="loading">
    <header class="review-toolbar">
      <div>
        <span class="eyebrow">管理审查 · 异常与趋势</span>
        <h2>今日哪里需要看</h2>
        <p>先看阻断项，再看未使用异常和录入时效；趋势与冻结报表单独复盘。</p>
      </div>
      <div class="review-actions">
        <el-button :icon="Setting" @click="openThresholds">审查阈值</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="refreshAll">刷新</el-button>
      </div>
    </header>

    <nav class="section-tabs" aria-label="管理审查分区">
      <button
        v-for="tab in sectionTabs"
        :key="tab.key"
        type="button"
        :class="{ 'is-active': activeSection === tab.key }"
        @click="activeSection = tab.key"
      >
        {{ tab.label }}
        <b v-if="tab.count">{{ tab.count }}</b>
      </button>
    </nav>

    <template v-if="activeSection === 'ANOMALIES'">
      <div class="filter-row">
        <el-date-picker v-model="anomalyDate" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="loadAnomalies" />
        <el-radio-group v-model="anomalyRange" @change="loadAnomalies">
          <el-radio-button :label="1">今天</el-radio-button>
          <el-radio-button :label="7">最近 7 天</el-radio-button>
        </el-radio-group>
        <el-select v-model="anomalyDepartment" clearable filterable placeholder="全部科室" @change="loadAnomalies">
          <el-option v-for="dept in departments" :key="dept.key" :label="dept.name" :value="dept.key" />
        </el-select>
        <div class="severity-filter">
          <button
            v-for="item in severityOptions"
            :key="item.key"
            type="button"
            :class="{ 'is-active': anomalySeverity === item.key }"
            @click="anomalySeverity = item.key; loadAnomalies()"
          >
            {{ item.label }}<b v-if="item.count">{{ item.count }}</b>
          </button>
        </div>
      </div>

      <div class="summary-row">
        <div><small>全部异常</small><strong>{{ anomalyData?.summary.total || 0 }}</strong></div>
        <div class="is-blocking"><small>阻断项</small><strong>{{ anomalyData?.summary.blocking || 0 }}</strong></div>
        <div class="is-anomaly"><small>未使用/比例</small><strong>{{ anomalyData?.summary.anomaly || 0 }}</strong></div>
        <div class="is-timeliness"><small>迟录/集中补录</small><strong>{{ anomalyData?.summary.timeliness || 0 }}</strong></div>
      </div>

      <div v-if="anomalyItems.length" class="anomaly-list">
        <button
          v-for="item in anomalyItems"
          :key="item.id"
          type="button"
          class="anomaly-row"
          :class="`is-${item.severity.toLowerCase()}`"
          @click="openAnomaly(item)"
        >
          <span class="severity-bar" />
          <span class="anomaly-main">
            <strong>{{ item.patientName || "科室日报" }}</strong>
            <small>{{ item.departmentName }} · {{ item.businessDate }} · {{ typeLabel(item.type) }}</small>
            <p>{{ item.reason }}</p>
          </span>
          <span class="anomaly-values">
            <b>{{ item.currentValue || "—" }}</b>
            <small>{{ item.thresholdValue ? `阈值 ${item.thresholdValue}` : "" }}</small>
          </span>
          <span class="anomaly-side">
            <el-tag :type="severityType(item.severity)" size="small" effect="plain">{{ severityLabel(item.severity) }}</el-tag>
            <small>{{ statusLabel(item.status) }}</small>
          </span>
          <span class="anomaly-go">查看 →</span>
        </button>
      </div>
      <el-empty v-else-if="!loading" :image-size="80" description="当前时间范围内没有需要审查的异常" />
    </template>

    <template v-else-if="activeSection === 'MATRIX'">
      <div class="filter-row">
        <el-select v-model="matrixDepartment" filterable placeholder="选择科室" @change="loadMatrix">
          <el-option v-for="dept in departments" :key="dept.key" :label="dept.name" :value="dept.key" />
        </el-select>
        <el-date-picker v-model="matrixFrom" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="loadMatrix" />
        <span class="range-sep">至</span>
        <el-date-picker v-model="matrixTo" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="loadMatrix" />
        <el-radio-group v-model="matrixMetric" @change="loadMatrix">
          <el-radio-button label="QUANTITY">绝对量</el-radio-button>
          <el-radio-button label="PER_PATIENT">人均</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" :loading="matrixLoading" @click="loadMatrix">刷新</el-button>
      </div>

      <div class="matrix-panel" v-loading="matrixLoading">
        <el-table
          v-if="matrixData?.items?.length"
          :data="matrixData.items"
          height="420"
          class="matrix-table"
          :cell-class-name="matrixCellClass"
          @cell-click="openMatrixCell"
        >
          <el-table-column prop="itemName" label="耗材" width="200" fixed show-overflow-tooltip />
          <el-table-column prop="unit" label="单位" width="70" fixed />
          <el-table-column
            v-for="date in matrixData.dates"
            :key="date"
            :prop="`cells.${date}`"
            :label="date.slice(5)"
            width="74"
            align="right"
          >
            <template #default="{ row }">{{ formatCell(row.cells?.[date]) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!matrixLoading" :image-size="80" description="当前范围没有耗材记录" />
      </div>

      <section class="trend-section">
        <header class="subhead">
          <div>
            <h3>周趋势异常</h3>
            <p>{{ trendsData?.from || "" }} 至 {{ trendsData?.to || "" }}，对比 {{ trendsData?.previousFrom || "" }} 至 {{ trendsData?.previousTo || "" }}</p>
          </div>
          <el-button :loading="trendLoading" @click="loadTrends">刷新趋势</el-button>
        </header>
        <el-table :data="trendAnomalies" v-loading="trendLoading" height="260">
          <el-table-column prop="itemName" label="耗材" min-width="180" show-overflow-tooltip />
          <el-table-column prop="type" label="类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ trendTypeLabel(row.type) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="currentQuantity" label="本期" width="100" align="right" />
          <el-table-column prop="previousQuantity" label="上期" width="100" align="right" />
          <el-table-column label="变化" width="110" align="right">
            <template #default="{ row }">
              {{ row.delta ?? row.currentPerPatient ?? "" }}
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" min-width="220" show-overflow-tooltip />
        </el-table>
      </section>
    </template>

    <template v-else>
      <div class="filter-row">
        <el-radio-group v-model="reportType" @change="resetReportPeriod">
          <el-radio-button label="WEEK">周报</el-radio-button>
          <el-radio-button label="MONTH">月报</el-radio-button>
        </el-radio-group>
        <el-date-picker v-model="reportStart" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        <span class="range-sep">至</span>
        <el-date-picker v-model="reportEnd" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        <el-button type="primary" :loading="reportGenerating" @click="generateReport">生成冻结快照</el-button>
        <el-button :icon="Refresh" :loading="reportLoading" @click="loadReports">刷新</el-button>
      </div>

      <el-table :data="reports" v-loading="reportLoading" height="360">
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ row.reportType === "WEEK" ? "周报" : "月报" }}</template>
        </el-table-column>
        <el-table-column label="周期" min-width="180">
          <template #default="{ row }">{{ row.periodStart }} ~ {{ row.periodEnd }}</template>
        </el-table-column>
        <el-table-column prop="generatedBy" label="生成人" width="120" />
        <el-table-column prop="generatedAt" label="生成时间" min-width="160" />
        <el-table-column label="操作" width="250" align="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openReport(row)">查看</el-button>
            <el-button text @click="downloadReport(row, 'xlsx')">XLSX</el-button>
            <el-button text @click="downloadReport(row, 'csv')">CSV</el-button>
            <el-button text @click="downloadReport(row, 'pdf')">PDF</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <el-drawer v-model="anomalyDrawer" title="异常详情" size="62%" append-to-body>
      <div v-if="selectedAnomaly" class="anomaly-detail">
        <header class="detail-head">
          <div>
            <h3>{{ selectedAnomaly.patientName || "科室日报" }}</h3>
            <p>{{ selectedAnomaly.departmentName }} · {{ selectedAnomaly.businessDate }} · {{ typeLabel(selectedAnomaly.type) }}</p>
          </div>
          <el-tag :type="severityType(selectedAnomaly.severity)" effect="plain">{{ severityLabel(selectedAnomaly.severity) }}</el-tag>
        </header>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="原因">{{ selectedAnomaly.reason }}</el-descriptions-item>
          <el-descriptions-item label="当前值">{{ selectedAnomaly.currentValue || "—" }}</el-descriptions-item>
          <el-descriptions-item label="阈值">{{ selectedAnomaly.thresholdValue || "—" }}</el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ selectedAnomaly.submittedAt || "—" }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusLabel(selectedAnomaly.status) }}</el-descriptions-item>
          <el-descriptions-item label="操作人">{{ selectedAnomaly.operatorName || "—" }}</el-descriptions-item>
        </el-descriptions>
        <h4 v-if="selectedAnomaly.patients?.length">涉及患者</h4>
        <el-table v-if="selectedAnomaly.patients?.length" :data="selectedAnomaly.patients" max-height="240">
          <el-table-column prop="patientName" label="患者" width="130" />
          <el-table-column prop="visitNo" label="就诊号" width="120" />
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column prop="submittedAt" label="提交时间" min-width="150" />
          <el-table-column width="110" align="right">
            <template #default="{ row }">
              <el-button v-if="row.serviceInstanceId" text type="primary" @click="loadPatientLines(row.serviceInstanceId)">
                耗材明细
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <template v-if="patientLines.length">
          <h4>患者耗材行</h4>
          <el-table :data="patientLines" max-height="300">
            <el-table-column prop="itemName" label="耗材" min-width="180" />
            <el-table-column prop="unit" label="单位" width="80" />
            <el-table-column prop="actualQuantity" label="数量" width="90" align="right" />
            <el-table-column prop="reviewStatus" label="审核" width="100" />
          </el-table>
        </template>
        <el-empty v-else-if="!selectedAnomaly.patients?.length" :image-size="70" description="该异常为科室日报级，请到科室日报处理" />
      </div>
    </el-drawer>

    <el-drawer v-model="matrixDrawer" :title="matrixDrawerTitle" size="58%" append-to-body>
      <el-table :data="matrixPatients" v-loading="matrixDetailLoading">
        <el-table-column prop="patientName" label="患者/日报" min-width="150" />
        <el-table-column prop="visitNo" label="就诊号" width="120" />
        <el-table-column prop="quantity" label="用量" width="90" align="right" />
        <el-table-column prop="reviewStatus" label="审核" width="100" />
      </el-table>
      <el-empty v-if="!matrixPatients.length && !matrixDetailLoading" :image-size="70" description="该单元格没有明细" />
    </el-drawer>

    <el-drawer v-model="reportDrawer" title="冻结报表" size="66%" append-to-body>
      <div v-if="reportDetail" class="report-detail">
        <header class="detail-head">
          <div>
            <h3>{{ reportDetail.reportType === "WEEK" ? "周报" : "月报" }} · {{ reportDetail.periodStart }} ~ {{ reportDetail.periodEnd }}</h3>
            <p>冻结生成 {{ reportDetail.generatedAt }} · {{ reportDetail.generatedBy }}</p>
          </div>
        </header>
        <div class="summary-row">
          <div><small>患者数</small><strong>{{ reportDetail.summary?.patients || 0 }}</strong></div>
          <div><small>填报天数</small><strong>{{ reportDetail.summary?.reportDays || 0 }}</strong></div>
          <div><small>耗材种类</small><strong>{{ reportDetail.summary?.itemCount || 0 }}</strong></div>
          <div><small>使用总量</small><strong>{{ formatCell(reportDetail.summary?.totalQuantity) }}</strong></div>
        </div>
        <h4>科室用量</h4>
        <el-table :data="reportDetail.departments || []" max-height="220">
          <el-table-column prop="departmentName" label="科室" />
          <el-table-column prop="quantity" label="使用量" width="100" align="right" />
          <el-table-column prop="patients" label="患者数" width="90" align="right" />
        </el-table>
        <h4>耗材明细</h4>
        <el-table :data="reportDetail.items || []" max-height="260">
          <el-table-column prop="itemName" label="耗材" min-width="180" />
          <el-table-column prop="unit" label="单位" width="80" />
          <el-table-column prop="quantity" label="使用量" width="100" align="right" />
        </el-table>
      </div>
    </el-drawer>

    <el-dialog v-model="thresholdVisible" title="审查阈值" width="760px" append-to-body>
      <el-table :data="thresholdRows" max-height="440">
        <el-table-column prop="scope" label="范围" width="110" />
        <el-table-column prop="departmentKey" label="科室" width="140" />
        <el-table-column label="阈值项" min-width="220">
          <template #default="{ row }">{{ thresholdLabel(row.key) }}</template>
        </el-table-column>
        <el-table-column label="值" width="150">
          <template #default="{ row }">
            <el-input-number v-model="row.value" :min="0" :precision="4" :step="0.1" size="small" controls-position="right" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="thresholdVisible = false">取消</el-button>
        <el-button type="primary" :loading="thresholdSaving" @click="saveThresholds">保存阈值</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh, Setting } from "@element-plus/icons-vue";
import dayjs from "dayjs";
import {
  downloadInventoryAdminReportApi,
  generateInventoryAdminReportApi,
  getInventoryAdminAnomaliesApi,
  getInventoryAdminMatrixApi,
  getInventoryAdminReportApi,
  getInventoryAdminThresholdsApi,
  getInventoryAdminTrendsApi,
  getInventoryConsumableUsageApi,
  getInventoryPatientDetailApi,
  listInventoryAdminReportsApi,
  saveInventoryAdminThresholdsApi,
  type InventoryAdminAnomaly,
  type InventoryAdminAnomalyResponse,
  type InventoryAdminMatrix,
  type InventoryAdminReportSnapshot,
  type InventoryAdminThresholdRow,
  type InventoryAdminTrend,
  type InventoryPatientDetail
} from "@/api/modules/inventory";

const props = defineProps<{ today?: string }>();
const today = props.today || dayjs().format("YYYY-MM-DD");

const loading = ref(false);
const activeSection = ref<"ANOMALIES" | "MATRIX" | "REPORTS">("ANOMALIES");
const departments = ref<Array<{ id: string; key: string; name: string; entryMode: string }>>([]);

const anomalyDate = ref(today);
const anomalyRange = ref(1);
const anomalySeverity = ref("");
const anomalyDepartment = ref("");
const anomalyData = ref<InventoryAdminAnomalyResponse | null>(null);
const anomalyItems = computed(() => anomalyData.value?.items ?? []);
const severityOptions = computed(() => [
  { key: "", label: "全部", count: anomalyData.value?.summary.total || 0 },
  { key: "BLOCKING", label: "阻断", count: anomalyData.value?.summary.blocking || 0 },
  { key: "ANOMALY", label: "异常", count: anomalyData.value?.summary.anomaly || 0 },
  { key: "TIMELINESS", label: "时效", count: anomalyData.value?.summary.timeliness || 0 }
]);
const sectionTabs = computed(() => [
  { key: "ANOMALIES" as const, label: "每日异常", count: anomalyData.value?.summary.total || 0 },
  { key: "MATRIX" as const, label: "趋势与矩阵", count: 0 },
  { key: "REPORTS" as const, label: "周月报表", count: reports.value.length }
]);

const anomalyDrawer = ref(false);
const selectedAnomaly = ref<InventoryAdminAnomaly | null>(null);
const patientLines = ref<InventoryPatientDetail["lines"]>([]);

const matrixDepartment = ref("");
const matrixFrom = ref(dayjs().subtract(13, "day").format("YYYY-MM-DD"));
const matrixTo = ref(today);
const matrixMetric = ref<"QUANTITY" | "PER_PATIENT">("QUANTITY");
const matrixData = ref<InventoryAdminMatrix | null>(null);
const matrixLoading = ref(false);
const trendsData = ref<InventoryAdminTrend | null>(null);
const trendLoading = ref(false);
const matrixDrawer = ref(false);
const matrixDrawerTitle = ref("");
const matrixPatients = ref<Array<{ patientName: string; visitNo?: string; quantity: number; reviewStatus: string }>>([]);
const matrixDetailLoading = ref(false);

const reportType = ref<"WEEK" | "MONTH">("WEEK");
const reportStart = ref(dayjs().startOf("week").add(1, "day").format("YYYY-MM-DD"));
const reportEnd = ref(dayjs(reportStart.value).add(6, "day").format("YYYY-MM-DD"));
const reports = ref<InventoryAdminReportSnapshot[]>([]);
const reportLoading = ref(false);
const reportGenerating = ref(false);
const reportDrawer = ref(false);
const reportDetail = ref<InventoryAdminReportSnapshot | null>(null);

const thresholdVisible = ref(false);
const thresholdRows = ref<InventoryAdminThresholdRow[]>([]);
const thresholdSaving = ref(false);

const trendAnomalies = computed(() => [
  ...(trendsData.value?.anomalies ?? []),
  ...(trendsData.value?.presetDeviations ?? [])
]);

const loadDepartments = async () => {
  const result = await getInventoryAdminThresholdsApi();
  departments.value = result.data?.departments ?? [];
  if (!matrixDepartment.value) {
    const firstPatient = departments.value.find(dept => dept.entryMode === "PATIENT");
    matrixDepartment.value = firstPatient?.key || departments.value[0]?.key || "";
  }
};

const loadAnomalies = async () => {
  loading.value = true;
  try {
    const result = await getInventoryAdminAnomaliesApi({
      date: anomalyDate.value,
      range: anomalyRange.value,
      type: anomalySeverity.value || undefined,
      departmentKey: anomalyDepartment.value || undefined
    });
    anomalyData.value = result.data ?? null;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取异常队列失败");
  } finally {
    loading.value = false;
  }
};

const loadMatrix = async () => {
  if (!matrixDepartment.value) return;
  matrixLoading.value = true;
  try {
    const [matrix, trends] = await Promise.all([
      getInventoryAdminMatrixApi({
        departmentKey: matrixDepartment.value,
        from: matrixFrom.value,
        to: matrixTo.value,
        metric: matrixMetric.value
      }),
      getInventoryAdminTrendsApi({
        periodStart: matrixFrom.value,
        periodEnd: matrixTo.value,
        departmentKey: matrixDepartment.value
      })
    ]);
    matrixData.value = matrix.data ?? null;
    trendsData.value = trends.data ?? null;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取趋势矩阵失败");
  } finally {
    matrixLoading.value = false;
    trendLoading.value = false;
  }
};

const loadTrends = async () => {
  trendLoading.value = true;
  try {
    const result = await getInventoryAdminTrendsApi({
      periodStart: matrixFrom.value,
      periodEnd: matrixTo.value,
      departmentKey: matrixDepartment.value || undefined
    });
    trendsData.value = result.data ?? null;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取趋势失败");
  } finally {
    trendLoading.value = false;
  }
};

const loadReports = async () => {
  reportLoading.value = true;
  try {
    const result = await listInventoryAdminReportsApi();
    reports.value = result.data?.items ?? [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取报表快照失败");
  } finally {
    reportLoading.value = false;
  }
};

const openAnomaly = async (item: InventoryAdminAnomaly) => {
  selectedAnomaly.value = item;
  patientLines.value = [];
  anomalyDrawer.value = true;
  if (item.serviceInstanceId) await loadPatientLines(item.serviceInstanceId);
};

const loadPatientLines = async (serviceInstanceId: string) => {
  try {
    const result = await getInventoryPatientDetailApi(serviceInstanceId);
    patientLines.value = result.data?.lines ?? [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取患者耗材失败");
  }
};

const openMatrixCell = async (row: { itemId: string; itemName: string }, column: { property?: string }) => {
  if (!column.property?.startsWith("cells.")) return;
  const date = column.property.slice("cells.".length);
  matrixDrawerTitle.value = `${row.itemName} · ${date}`;
  matrixDrawer.value = true;
  matrixDetailLoading.value = true;
  try {
    const result = await getInventoryConsumableUsageApi({
      date,
      departmentKey: matrixDepartment.value,
      itemId: row.itemId
    });
    matrixPatients.value = (result.data?.patients ?? []).map(patient => ({
      patientName: patient.patientName,
      visitNo: patient.visitNo,
      quantity: patient.quantity,
      reviewStatus: patient.reviewStatus
    }));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取矩阵明细失败");
  } finally {
    matrixDetailLoading.value = false;
  }
};

const resetReportPeriod = () => {
  if (reportType.value === "WEEK") {
    reportStart.value = dayjs().startOf("week").add(1, "day").format("YYYY-MM-DD");
    reportEnd.value = dayjs(reportStart.value).add(6, "day").format("YYYY-MM-DD");
  } else {
    reportStart.value = dayjs().startOf("month").format("YYYY-MM-DD");
    reportEnd.value = dayjs().endOf("month").format("YYYY-MM-DD");
  }
};

const generateReport = async () => {
  reportGenerating.value = true;
  try {
    const result = await generateInventoryAdminReportApi({
      reportType: reportType.value,
      periodStart: reportStart.value,
      periodEnd: reportEnd.value
    });
    ElMessage.success("报表快照已冻结生成");
    await loadReports();
    if (result.data) openReport(result.data);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "生成报表失败");
  } finally {
    reportGenerating.value = false;
  }
};

const openReport = async (row: InventoryAdminReportSnapshot | Record<string, any>) => {
  const snapshot = row as InventoryAdminReportSnapshot;
  reportDetail.value = snapshot;
  reportDrawer.value = true;
  try {
    const result = await getInventoryAdminReportApi(snapshot.id);
    reportDetail.value = result.data ?? snapshot;
  } catch {
    // Keep the list row when detail cannot be loaded.
  }
};

const downloadReport = async (row: InventoryAdminReportSnapshot | Record<string, any>, format: "xlsx" | "csv" | "pdf") => {
  try {
    const { blob, filename } = await downloadInventoryAdminReportApi(row.id, format);
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "下载报表失败");
  }
};

const openThresholds = async () => {
  try {
    const result = await getInventoryAdminThresholdsApi();
    thresholdRows.value = (result.data?.items ?? []).map(row => ({ ...row, value: Number(row.value) }));
    thresholdVisible.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取阈值失败");
  }
};

const saveThresholds = async () => {
  thresholdSaving.value = true;
  try {
    await saveInventoryAdminThresholdsApi({
      items: thresholdRows.value.map(row => ({
        scope: row.scope,
        departmentKey: row.departmentKey || undefined,
        itemId: row.itemId || undefined,
        key: row.key,
        value: Number(row.value)
      }))
    });
    ElMessage.success("审查阈值已保存");
    thresholdVisible.value = false;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存阈值失败");
  } finally {
    thresholdSaving.value = false;
  }
};

const refreshAll = async () => {
  if (activeSection.value === "ANOMALIES") await loadAnomalies();
  else if (activeSection.value === "MATRIX") await loadMatrix();
  else await loadReports();
};

const matrixCellClass = ({ row, column }: { row: { cells?: Record<string, number | null> }; column: { property?: string } }) => {
  if (!column.property?.startsWith("cells.")) return "";
  const date = column.property.slice(6);
  const value = Number(row.cells?.[date] || 0);
  if (!matrixData.value?.items?.length) return "";
  const max = Math.max(
    ...matrixData.value.items.flatMap(item => Object.values(item.cells || {}).map(cell => Number(cell || 0)))
  );
  if (!max || value === 0) return "";
  const level = Math.min(4, Math.max(1, Math.ceil((value / max) * 4)));
  return `heat-${level}`;
};

const formatCell = (value?: number | null) => {
  if (value === null || value === undefined) return "—";
  return Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
};

const severityLabel = (severity: string) =>
  severity === "BLOCKING" ? "阻断" : severity === "ANOMALY" ? "异常" : "时效";
const severityType = (severity: string): "danger" | "warning" | "info" =>
  severity === "BLOCKING" ? "danger" : severity === "ANOMALY" ? "warning" : "info";
const typeLabel = (type: string) =>
  ({
    MISSING: "未录",
    EMPTY_DRAFT: "空草稿",
    RETURNED: "已驳回",
    NO_CONSUMPTION_RATIO: "未使用比例",
    LATE: "迟录",
    CENTRALIZED: "集中补录",
    REPORT_MISSING: "日报未创建",
    REPORT_DRAFT: "日报草稿",
    REPORT_RETURNED: "日报驳回"
  })[type] ?? type;
const trendTypeLabel = (type: string) =>
  ({ TREND: "环比突增", CROSS_DEPT: "科室差异", PRESET_DEVIATION: "预置偏差" })[type] ?? type;
const statusLabel = (status?: string) =>
  ({
    NOT_STARTED: "未开始",
    DRAFT: "草稿",
    SUBMITTED: "待审核",
    PARTIALLY_APPROVED: "部分通过",
    RETURNED: "已驳回",
    APPROVED: "已通过"
  })[status || ""] ?? (status || "—");
const thresholdLabel = (key: string) =>
  ({
    NO_CONSUMPTION_RATIO: "未使用比例阈值",
    NO_CONSUMPTION_MIN_DOCS: "未使用最小单据数",
    TREND_WOW_RATIO: "环比阈值",
    TREND_MIN_DELTA: "最小增量",
    CROSS_DEPT_MULTIPLE: "科室差异倍数",
    CROSS_DEPT_MIN_BASE: "科室差异最小基数",
    PRESET_DEVIATION_RATIO: "预置偏差比例",
    PRESET_DEVIATION_MIN_DOCS: "预置偏差最小单据数",
    LATE_HOUR: "迟录起算小时",
    CENTRALIZED_WINDOW_MINUTES: "集中补录时间窗（分钟）",
    CENTRALIZED_MIN_DOCS: "集中补录最少人数"
  })[key] ?? key;

onMounted(async () => {
  await loadDepartments();
  await loadAnomalies();
  await loadReports();
  void loadMatrix();
});
</script>

<style scoped lang="scss">
.admin-review {
  --review-bg: #f8f5ef;
  --review-card: #fffdf9;
  --review-line: #efe8dd;
  --review-text: #2f2d27;
  --review-muted: #9a9285;
  --review-brand: #347d68;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  background: var(--review-bg);
}

.review-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 16px 18px;
  background: var(--review-card);
  border: 1px solid var(--review-line);
  border-radius: 16px;

  h2 {
    margin: 4px 0;
    color: var(--review-text);
    font-size: 20px;
  }

  p {
    margin: 0;
    color: var(--review-muted);
    font-size: 12px;
  }
}

.eyebrow {
  color: var(--review-brand);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

.review-actions {
  display: flex;
  gap: 8px;
}

.section-tabs {
  display: flex;
  gap: 4px;
  padding: 4px;
  background: var(--review-card);
  border: 1px solid var(--review-line);
  border-radius: 12px;

  button {
    display: inline-flex;
    gap: 5px;
    align-items: center;
    padding: 7px 14px;
    color: var(--review-muted);
    font-size: 12px;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-radius: 9px;

    b {
      padding: 0 5px;
      font-size: 10px;
      background: rgb(0 0 0 / 5%);
      border-radius: 999px;
    }

    &.is-active {
      color: #fff;
      background: var(--review-brand);
    }
  }
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 10px 12px;
  background: var(--review-card);
  border: 1px solid var(--review-line);
  border-radius: 12px;
}

.range-sep {
  color: var(--review-muted);
  font-size: 12px;
}

.severity-filter {
  display: flex;
  gap: 3px;
  margin-left: auto;

  button {
    padding: 4px 9px;
    color: var(--review-muted);
    font-size: 11px;
    cursor: pointer;
    background: #f8f5ef;
    border: 1px solid var(--review-line);
    border-radius: 999px;

    b {
      margin-left: 4px;
      font-variant-numeric: tabular-nums;
    }

    &.is-active {
      color: var(--review-brand);
      background: #edf7f1;
      border-color: var(--review-brand);
    }
  }
}

.summary-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;

  > div {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 10px 14px;
    background: var(--review-card);
    border: 1px solid var(--review-line);
    border-radius: 12px;
  }

  small {
    color: var(--review-muted);
    font-size: 10px;
  }

  strong {
    color: var(--review-text);
    font-size: 22px;
    font-variant-numeric: tabular-nums;
  }

  .is-blocking strong {
    color: #b54747;
  }

  .is-anomaly strong {
    color: #b7791f;
  }

  .is-timeliness strong {
    color: #4f7cac;
  }
}

.anomaly-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.anomaly-row {
  display: grid;
  grid-template-columns: 4px minmax(0, 1fr) 120px 150px 60px;
  gap: 10px;
  align-items: center;
  padding: 10px 12px;
  text-align: left;
  cursor: pointer;
  background: var(--review-card);
  border: 1px solid var(--review-line);
  border-radius: 12px;

  &:hover {
    border-color: #d9cfc0;
  }

  .severity-bar {
    width: 4px;
    height: 36px;
    background: #4f7cac;
    border-radius: 999px;
  }

  &.is-blocking .severity-bar {
    background: #c45454;
  }

  &.is-anomaly .severity-bar {
    background: #b7791f;
  }
}

.anomaly-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;

  strong {
    color: var(--review-text);
    font-size: 13px;
  }

  small {
    color: var(--review-muted);
    font-size: 10px;
  }

  p {
    margin: 0;
    color: #6d675c;
    font-size: 11px;
  }
}

.anomaly-values {
  display: flex;
  flex-direction: column;
  gap: 1px;
  text-align: right;

  b {
    color: var(--review-text);
    font-size: 13px;
    font-variant-numeric: tabular-nums;
  }

  small {
    color: var(--review-muted);
    font-size: 10px;
  }
}

.anomaly-side {
  display: flex;
  flex-direction: column;
  gap: 3px;
  align-items: flex-end;

  small {
    color: var(--review-muted);
    font-size: 10px;
  }
}

.anomaly-go {
  color: var(--review-brand);
  font-size: 11px;
  text-align: right;
}

.matrix-panel,
.trend-section {
  background: var(--review-card);
  border: 1px solid var(--review-line);
  border-radius: 14px;
  overflow: hidden;
}

.matrix-table {
  :deep(.heat-1) {
    background: #f2faf6;
  }

  :deep(.heat-2) {
    background: #e4f4ea;
  }

  :deep(.heat-3) {
    background: #fdf3df;
  }

  :deep(.heat-4) {
    background: #fbe8d5;
  }
}

.trend-section {
  padding: 12px;
}

.subhead {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;

  h3 {
    margin: 0;
    color: var(--review-text);
    font-size: 14px;
  }

  p {
    margin: 2px 0 0;
    color: var(--review-muted);
    font-size: 10px;
  }
}

.detail-head {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 12px;

  h3 {
    margin: 0;
    color: var(--review-text);
    font-size: 18px;
  }

  p {
    margin: 3px 0 0;
    color: var(--review-muted);
    font-size: 11px;
  }
}

.anomaly-detail,
.report-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;

  h4 {
    margin: 4px 0 0;
    color: var(--review-text);
    font-size: 13px;
  }
}

@media (max-width: 1000px) {
  .summary-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .anomaly-row {
    grid-template-columns: 4px minmax(0, 1fr) 110px;

    .anomaly-values,
    .anomaly-go {
      display: none;
    }
  }

  .severity-filter {
    width: 100%;
    margin-left: 0;
  }
}
</style>
