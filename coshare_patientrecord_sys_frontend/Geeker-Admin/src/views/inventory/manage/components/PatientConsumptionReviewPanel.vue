<template>
  <section class="review-workspace" v-loading="loading">
    <header class="review-toolbar">
      <div class="toolbar-copy">
        <span class="eyebrow">库管审核 · 患者耗材</span>
        <h2>患者耗材审核</h2>
        <p>按科室和业务日期审核患者耗材清单。逐行通过或驳回；通过后立即扣减科室库存，驳回项退回科室修改。</p>
      </div>
      <div class="toolbar-actions">
        <el-date-picker
          v-model="businessDate"
          type="date"
          value-format="YYYY-MM-DD"
          :clearable="false"
          @change="reload(false)"
        />
        <el-select v-model="departmentKey" class="department-filter" clearable placeholder="全部科室" @change="reload(false)">
          <el-option v-for="department in departments" :key="department.key" :label="department.name" :value="department.key" />
        </el-select>
        <el-select v-model="statusFilter" class="status-filter" clearable placeholder="全部状态" @change="reload(false)">
          <el-option label="待审核" value="PENDING" />
          <el-option label="部分通过" value="PARTIALLY_APPROVED" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已驳回" value="RETURNED" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" @click="reload(true)">刷新</el-button>
        <el-button :icon="Setting" @click="openSettings">切换设置</el-button>
      </div>
    </header>

    <div class="summary-strip">
      <div class="summary-item">
        <small>审核批次</small>
        <strong>{{ bundles.summary.total || 0 }}</strong>
      </div>
      <div class="summary-item is-pending">
        <small>待审核</small>
        <strong>{{ bundles.summary.pending || 0 }}</strong>
      </div>
      <div class="summary-item is-partial">
        <small>部分通过</small>
        <strong>{{ bundles.summary.partiallyApproved || 0 }}</strong>
      </div>
      <div class="summary-item is-approved">
        <small>已通过</small>
        <strong>{{ bundles.summary.approved || 0 }}</strong>
      </div>
      <div class="summary-item is-returned">
        <small>已驳回</small>
        <strong>{{ bundles.summary.returned || 0 }}</strong>
      </div>
    </div>

    <div class="review-layout">
      <aside class="batch-panel">
        <div class="panel-head">
          <h3>科室批次</h3>
          <span>{{ bundles.list.length }} 个</span>
        </div>
        <div class="batch-list">
          <button
            v-for="batch in bundles.list"
            :key="batch.batchId"
            type="button"
            class="batch-card"
            :class="{ 'is-active': selectedBatchId === batch.batchId }"
            @click="selectBatch(batch)"
          >
            <span class="batch-main">
              <strong>{{ batch.departmentName }}</strong>
              <span>{{ batch.businessDate }}</span>
            </span>
            <span class="batch-side">
              <el-tag :type="statusType(batch.status)" size="small" effect="plain">{{ statusLabel(batch.status) }}</el-tag>
              <small>{{ batch.approvedLineCount }}/{{ batch.lineCount }} 行</small>
            </span>
          </button>
          <el-empty v-if="!bundles.list.length" :image-size="70" description="当前日期没有审核批次" />
        </div>
      </aside>

      <main v-if="detail" class="batch-detail">
        <header class="detail-head">
          <div>
            <div class="detail-title-row">
              <h3>{{ detail.batch.departmentName }}</h3>
              <el-tag :type="statusType(detail.batch.status)" effect="plain">{{ statusLabel(detail.batch.status) }}</el-tag>
            </div>
            <p class="detail-meta">
              {{ detail.batch.businessDate }}
              <span>·</span>
              患者 {{ detail.documents.length }} 人
              <span>·</span>
              待审 {{ pendingLineCount }} 行
              <span>·</span>
              已通过 {{ detail.batch.approvedLineCount }} 行
              <span>·</span>
              已驳回 {{ detail.batch.rejectedLineCount }} 行
            </p>
          </div>
          <div class="detail-actions">
            <el-button
              type="primary"
              :loading="deciding"
              :disabled="!pendingLineIds.length"
              @click="approveAllPending"
            >
              通过全部待审
            </el-button>
          </div>
        </header>

        <el-alert
          v-if="decisionErrors.length"
          class="decision-alert"
          type="warning"
          :closable="false"
          show-icon
          title="部分耗材行未通过"
          :description="decisionErrors.map(error => error.message).join('；')"
        />

        <div class="document-list">
          <article v-for="document in detail.documents" :key="document.documentId" class="document-card">
            <header class="document-head">
              <div class="document-title">
                <strong>{{ document.patientName || "未命名患者" }}</strong>
                <el-tag :type="statusType(document.status)" size="small" effect="plain">{{ statusLabel(document.status) }}</el-tag>
                <el-tag v-if="document.sourceType === 'MANUAL'" size="small" type="warning" effect="plain">手动补录</el-tag>
                <span class="document-meta">
                  {{ document.serviceName || "服务" }}
                  <i aria-hidden="true">·</i>
                  {{ document.visitNo || "无就诊号" }}
                  <i aria-hidden="true">·</i>
                  {{ formatDateTime(document.serviceStartedAt) }}
                </span>
              </div>
              <el-button
                v-if="document.lines.some(line => line.reviewStatus === 'PENDING')"
                size="small"
                text
                type="primary"
                @click="approveDocument(document)"
              >
                通过本患者待审
              </el-button>
            </header>

            <div class="line-table">
              <div class="line-row line-header">
                <span>耗材</span>
                <span>预设</span>
                <span>实际</span>
                <span>来源</span>
                <span>状态</span>
                <span>审核</span>
              </div>
              <div
                v-for="line in document.lines"
                :key="line.lineId"
                class="line-row"
                :class="{ 'is-approved': line.reviewStatus === 'APPROVED', 'is-rejected': line.reviewStatus === 'REJECTED' }"
              >
                <span class="line-item">
                  <strong>{{ line.itemName }}</strong>
                  <small>{{ line.unit }}<template v-if="line.exceptionReason"> · {{ line.exceptionReason }}</template></small>
                </span>
                <span class="line-number">{{ formatQuantity(line.presetQuantity) }}</span>
                <span class="line-number is-actual">{{ formatQuantity(line.actualQuantity) }}</span>
                <span>
                  <el-tag size="small" effect="plain" :type="line.source === 'MANUAL' ? 'warning' : 'info'">
                    {{ sourceLabel(line.source) }}
                  </el-tag>
                </span>
                <span>
                  <el-tag size="small" effect="plain" :type="lineStatusType(line.reviewStatus)">
                    {{ lineStatusLabel(line.reviewStatus) }}
                  </el-tag>
                  <small v-if="line.reviewNote" class="line-note">{{ line.reviewNote }}</small>
                </span>
                <span class="line-actions">
                  <template v-if="line.reviewStatus === 'PENDING'">
                    <el-button size="small" type="success" plain @click="decide(line, 'APPROVED')">通过</el-button>
                    <el-button size="small" type="danger" plain @click="reject(line)">驳回</el-button>
                  </template>
                  <span v-else class="line-done">已处理</span>
                </span>
              </div>
            </div>
          </article>

          <el-empty v-if="!detail.documents.length" :image-size="80" description="该批次没有患者耗材清单" />
        </div>
      </main>

      <main v-else class="batch-detail detail-empty">
        <el-empty :image-size="96" description="选择左侧批次开始审核" />
      </main>
    </div>

    <el-dialog v-model="settingsVisible" title="患者耗材切换设置" width="520px">
      <div class="settings-body">
        <p>切换日前的旧日报保留只读；切换日起，患者耗材明细成为统计和扣库的唯一来源。</p>
        <el-date-picker
          v-model="cutoverDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择切换日期"
          :clearable="false"
          class="cutover-picker"
        />
        <p class="settings-hint">建议选择下一个完整业务日，并在切换前完成物资映射、期初库存和 12 科室培训。</p>
      </div>
      <template #footer>
        <el-button @click="settingsVisible = false">取消</el-button>
        <el-button type="primary" :loading="settingsSaving" @click="saveSettings">保存切换日期</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Refresh, Setting } from "@element-plus/icons-vue";
import dayjs from "dayjs";
import {
  decideInventoryPatientReviewApi,
  getInventoryPatientReviewBatchApi,
  getInventoryPatientReviewBatchesApi,
  getInventoryRuntimeSettingsApi,
  saveInventoryCutoverDateApi,
  type InventoryPatientConsumptionLine,
  type InventoryPatientReviewBatch,
  type InventoryPatientReviewBatchList,
  type InventoryPatientReviewDetail
} from "@/api/modules/inventory";

const props = defineProps<{
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

const emptyBundles = (): InventoryPatientReviewBatchList => ({
  businessDate: props.today || dayjs().format("YYYY-MM-DD"),
  summary: { total: 0, pending: 0, partiallyApproved: 0, approved: 0, returned: 0 },
  list: []
});

const businessDate = ref(props.today || dayjs().format("YYYY-MM-DD"));
const departmentKey = ref("");
const statusFilter = ref("");
const bundles = ref<InventoryPatientReviewBatchList>(emptyBundles());
const detail = ref<InventoryPatientReviewDetail | null>(null);
const selectedBatchId = ref("");
const loading = ref(false);
const deciding = ref(false);
const decisionErrors = ref<Array<{ lineId: string; message: string }>>([]);
const settingsVisible = ref(false);
const settingsSaving = ref(false);
const cutoverDate = ref("");

const pendingLineIds = computed(() =>
  (detail.value?.documents ?? []).flatMap(document =>
    document.lines.filter(line => line.reviewStatus === "PENDING").map(line => line.lineId)
  )
);
const pendingLineCount = computed(() => pendingLineIds.value.length);

const reload = async (keepSelection = true) => {
  loading.value = true;
  try {
    const result = await getInventoryPatientReviewBatchesApi({
      date: businessDate.value,
      departmentKey: departmentKey.value || undefined,
      status: statusFilter.value || undefined
    });
    bundles.value = result.data ?? emptyBundles();
    if (!keepSelection || !selectedBatchId.value) {
      const first = bundles.value.list[0];
      if (first) await selectBatch(first);
      else {
        selectedBatchId.value = "";
        detail.value = null;
      }
    } else {
      const current = bundles.value.list.find(batch => batch.batchId === selectedBatchId.value);
      if (current) await selectBatch(current);
      else {
        selectedBatchId.value = "";
        detail.value = null;
      }
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取审核批次失败");
  } finally {
    loading.value = false;
  }
};

const selectBatch = async (batch: InventoryPatientReviewBatch) => {
  selectedBatchId.value = batch.batchId;
  decisionErrors.value = [];
  try {
    const result = await getInventoryPatientReviewBatchApi(batch.batchId);
    detail.value = result.data ?? null;
    if (result.data?.errors?.length) decisionErrors.value = result.data.errors;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取审核明细失败");
  }
};

const refreshDetail = async () => {
  if (!selectedBatchId.value) return;
  try {
    const result = await getInventoryPatientReviewBatchApi(selectedBatchId.value);
    detail.value = result.data ?? null;
    decisionErrors.value = result.data?.errors ?? [];
    await reload(true);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "刷新审核明细失败");
  }
};

const decide = async (line: InventoryPatientConsumptionLine, decision: "APPROVED" | "REJECTED", note?: string) => {
  if (!detail.value) return;
  deciding.value = true;
  try {
    const result = await decideInventoryPatientReviewApi(detail.value.batch.batchId, {
      decisions: [{ lineId: line.lineId, decision, note }]
    });
    detail.value = result.data ?? detail.value;
    decisionErrors.value = result.data?.errors ?? [];
    await reload(true);
    if (decision === "APPROVED") ElMessage.success("已通过并生成库存流水");
    else ElMessage.success("已驳回，科室可修改后重新提交");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "审核失败");
  } finally {
    deciding.value = false;
  }
};

const reject = async (line: InventoryPatientConsumptionLine) => {
  try {
    const { value } = await ElMessageBox.prompt(
      `请填写驳回 ${line.itemName} 的原因，科室会在工作台看到这条意见。`,
      "驳回耗材行",
      {
        confirmButtonText: "确认驳回",
        cancelButtonText: "取消",
        inputPlaceholder: "例如：实际用量与患者服务不匹配",
        inputValidator: value => (value && value.trim() ? true : "请填写驳回原因")
      }
    );
    await decide(line, "REJECTED", value.trim());
  } catch {
    // cancelled
  }
};

const approveDocument = async (document: InventoryPatientReviewDetail["documents"][number]) => {
  const decisions = document.lines
    .filter(line => line.reviewStatus === "PENDING")
    .map(line => ({ lineId: line.lineId, decision: "APPROVED" as const }));
  if (!decisions.length) return;
  await submitDecisions(decisions, `已通过 ${document.patientName || "该患者"} 的 ${decisions.length} 行耗材`);
};

const approveAllPending = async () => {
  const decisions = (detail.value?.documents ?? []).flatMap(document =>
    document.lines
      .filter(line => line.reviewStatus === "PENDING")
      .map(line => ({ lineId: line.lineId, decision: "APPROVED" as const }))
  );
  if (!decisions.length) return;
  try {
    await ElMessageBox.confirm(
      `确认通过本批次 ${decisions.length} 行待审耗材？通过后将立即扣减科室库存。`,
      "通过全部待审",
      { confirmButtonText: "确认通过", cancelButtonText: "取消", type: "warning" }
    );
  } catch {
    return;
  }
  await submitDecisions(decisions, `已通过 ${decisions.length} 行耗材`);
};

const submitDecisions = async (
  decisions: Array<{ lineId: string; decision: "APPROVED" | "REJECTED"; note?: string }>,
  successMessage: string
) => {
  if (!detail.value) return;
  deciding.value = true;
  try {
    const result = await decideInventoryPatientReviewApi(detail.value.batch.batchId, { decisions });
    detail.value = result.data ?? detail.value;
    decisionErrors.value = result.data?.errors ?? [];
    await reload(true);
    if (decisionErrors.value.length) {
      ElMessage.warning(`已处理，但有 ${decisionErrors.value.length} 行因库存或状态问题未通过`);
    } else {
      ElMessage.success(successMessage);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "审核失败");
  } finally {
    deciding.value = false;
  }
};

const openSettings = async () => {
  settingsVisible.value = true;
  try {
    const result = await getInventoryRuntimeSettingsApi();
    cutoverDate.value = result.data?.patientDetailCutoverDate || "";
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取切换设置失败");
  }
};

const saveSettings = async () => {
  if (!cutoverDate.value) {
    ElMessage.warning("请选择切换日期");
    return;
  }
  settingsSaving.value = true;
  try {
    const result = await saveInventoryCutoverDateApi(cutoverDate.value);
    cutoverDate.value = result.data?.patientDetailCutoverDate || cutoverDate.value;
    settingsVisible.value = false;
    ElMessage.success("切换日期已保存");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存切换日期失败");
  } finally {
    settingsSaving.value = false;
  }
};

const statusLabel = (status: string) => {
  switch (status) {
    case "PENDING":
    case "IN_REVIEW":
      return "待审核";
    case "PARTIALLY_APPROVED":
      return "部分通过";
    case "APPROVED":
      return "已通过";
    case "RETURNED":
      return "已驳回";
    default:
      return status || "未知";
  }
};

const statusType = (status: string): "success" | "warning" | "danger" | "info" => {
  switch (status) {
    case "APPROVED":
      return "success";
    case "RETURNED":
      return "danger";
    case "PENDING":
    case "IN_REVIEW":
    case "PARTIALLY_APPROVED":
      return "warning";
    default:
      return "info";
  }
};

const lineStatusLabel = (status: string) => {
  switch (status) {
    case "APPROVED":
      return "已通过";
    case "REJECTED":
      return "已驳回";
    default:
      return "待审核";
  }
};

const lineStatusType = (status: string): "success" | "warning" | "danger" | "info" => {
  if (status === "APPROVED") return "success";
  if (status === "REJECTED") return "danger";
  return "warning";
};

const sourceLabel = (source?: string) => {
  switch (source) {
    case "COMMON":
      return "常用项";
    case "MANUAL":
      return "预设外";
    default:
      return "预设";
  }
};

const formatQuantity = (value?: number | null) => {
  if (value === null || value === undefined) return "—";
  return Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
};

const formatDateTime = (value?: string) => {
  if (!value) return "未记录服务时间";
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format("MM-DD HH:mm") : value;
};

onMounted(() => {
  void reload(false);
});
</script>

<style scoped lang="scss">
.review-workspace {
  --hos-bg-primary: #fffdf9;
  --hos-bg-surface: #faf7f1;
  --hos-border: #e6ded1;
  --hos-border-light: #f1ebe1;
  --hos-text-primary: #2f2d27;
  --hos-text-secondary: #6d675c;
  --hos-text-muted: #9a9285;
  --hos-primary: #347d68;
  --hos-primary-soft: #edf7f1;
  --hos-status-success: #3f8a62;
  --hos-status-success-soft: #eef8f1;
  --hos-status-warning: #b7791f;
  --hos-status-warning-soft: #fff7e8;
  --hos-status-danger: #c45454;
  --hos-status-danger-soft: #fdf0ef;

  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px;
  min-height: calc(100vh - 180px);
  background:
    radial-gradient(circle at 92% 0%, rgb(237 247 241 / 80%), transparent 30%),
    radial-gradient(circle at 4% 10%, rgb(255 247 232 / 55%), transparent 24%),
    var(--hos-bg-surface);
}

.review-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 22px 24px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 18px;
  box-shadow: var(--hos-shadow-card);
}

.toolbar-copy {
  max-width: 700px;

  .eyebrow {
    display: inline-flex;
    margin-bottom: 6px;
    color: var(--hos-primary);
    font-weight: 600;
    font-size: 12px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  h2 {
    margin: 0 0 6px;
    color: var(--hos-text-primary);
    font-size: 24px;
    font-weight: 700;
    letter-spacing: -0.02em;
  }

  p {
    margin: 0;
    color: var(--hos-text-secondary);
    font-size: 13px;
    line-height: 1.7;
  }
}

.toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.department-filter,
.status-filter {
  width: 132px;
}

.summary-strip {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 16px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;

  small {
    color: var(--hos-text-muted);
    font-size: 12px;
  }

  strong {
    color: var(--hos-text-primary);
    font-variant-numeric: tabular-nums;
    font-size: 24px;
    line-height: 1.1;
  }

  &.is-pending strong,
  &.is-partial strong {
    color: var(--hos-status-warning);
  }

  &.is-approved strong {
    color: var(--hos-status-success);
  }

  &.is-returned strong {
    color: var(--hos-status-danger);
  }
}

.review-layout {
  display: grid;
  grid-template-columns: minmax(270px, 330px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.batch-panel,
.batch-detail {
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 18px;
  box-shadow: var(--hos-shadow-card);
}

.batch-panel {
  position: sticky;
  top: 16px;
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 220px);
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--hos-border-light);

  h3 {
    margin: 0;
    color: var(--hos-text-primary);
    font-size: 14px;
    font-weight: 600;
  }

  span {
    color: var(--hos-text-muted);
    font-size: 12px;
  }
}

.batch-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  overflow: auto;
}

.batch-card {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 12px;
  background: var(--hos-bg-surface);
  border: 1px solid transparent;
  border-radius: 12px;
  cursor: pointer;
  text-align: left;
  transition:
    border-color 160ms ease,
    background 160ms ease,
    transform 160ms ease;

  &:hover {
    border-color: var(--hos-border);
    transform: translateY(-1px);
  }

  &.is-active {
    background: var(--hos-primary-soft);
    border-color: var(--hos-primary);
  }
}

.batch-main,
.batch-side {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.batch-main strong {
  color: var(--hos-text-primary);
  font-size: 14px;
}

.batch-main span,
.batch-side small {
  color: var(--hos-text-muted);
  font-size: 11px;
}

.batch-side {
  flex-shrink: 0;
  align-items: flex-end;
}

.batch-detail {
  display: flex;
  flex-direction: column;
  min-height: 640px;
  padding: 20px;
}

.detail-empty {
  display: grid;
  place-items: center;
}

.detail-head {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--hos-border-light);
}

.detail-title-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;

  h3 {
    margin: 0;
    color: var(--hos-text-primary);
    font-size: 22px;
    font-weight: 700;
  }
}

.detail-meta {
  margin: 6px 0 0;
  color: var(--hos-text-secondary);
  font-size: 12px;

  span {
    margin: 0 4px;
    opacity: 0.5;
  }
}

.decision-alert {
  margin-top: 14px;
}

.document-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-top: 14px;
  overflow: auto;
}

.document-card {
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;
  overflow: hidden;
}

.document-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  background: var(--hos-bg-surface);
}

.document-title {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;

  strong {
    color: var(--hos-text-primary);
    font-size: 14px;
  }
}

.document-meta {
  color: var(--hos-text-muted);
  font-size: 11px;

  i {
    margin: 0 4px;
    font-style: normal;
    opacity: 0.6;
  }
}

.line-table {
  display: flex;
  flex-direction: column;
}

.line-row {
  display: grid;
  grid-template-columns: minmax(180px, 1.7fr) 70px 80px 84px 120px 140px;
  gap: 8px;
  align-items: center;
  min-height: 48px;
  padding: 8px 14px;
  border-top: 1px solid var(--hos-border-light);

  &.line-header {
    min-height: 36px;
    color: var(--hos-text-muted);
    font-size: 11px;
    background: var(--hos-bg-primary);
  }

  &.is-approved {
    background: color-mix(in srgb, var(--hos-status-success-soft) 55%, transparent);
  }

  &.is-rejected {
    background: color-mix(in srgb, var(--hos-status-danger-soft) 55%, transparent);
  }
}

.line-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;

  strong {
    overflow: hidden;
    color: var(--hos-text-primary);
    font-weight: 500;
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    overflow: hidden;
    color: var(--hos-text-muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.line-number {
  color: var(--hos-text-secondary);
  font-variant-numeric: tabular-nums;
  font-size: 12px;

  &.is-actual {
    color: var(--hos-text-primary);
    font-weight: 600;
  }
}

.line-note {
  display: block;
  margin-top: 2px;
  color: var(--hos-status-danger);
  font-size: 11px;
}

.line-actions {
  display: flex;
  gap: 6px;
  align-items: center;
}

.line-done {
  color: var(--hos-text-muted);
  font-size: 11px;
}

.settings-body {
  display: flex;
  flex-direction: column;
  gap: 12px;

  p {
    margin: 0;
    color: var(--hos-text-secondary);
    font-size: 13px;
    line-height: 1.7;
  }
}

.cutover-picker {
  width: 100%;
}

.settings-hint {
  color: var(--hos-text-muted) !important;
  font-size: 12px !important;
}

@media (max-width: 1180px) {
  .review-layout {
    grid-template-columns: minmax(240px, 300px) minmax(0, 1fr);
  }

  .line-row {
    grid-template-columns: minmax(160px, 1.4fr) 60px 70px 74px 100px 130px;
  }
}

@media (max-width: 960px) {
  .review-workspace {
    padding: 12px;
  }

  .summary-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .review-layout {
    grid-template-columns: 1fr;
  }

  .batch-panel {
    position: static;
    max-height: 340px;
  }

  .line-row {
    grid-template-columns: 1fr 70px 70px;
  }

  .line-row > :nth-child(4),
  .line-row > :nth-child(5) {
    display: none;
  }

  .line-actions {
    grid-column: 1 / -1;
  }
}
</style>
