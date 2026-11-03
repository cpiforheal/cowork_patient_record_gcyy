<template>
  <section class="qhw" v-loading="loading">
    <template v-if="legacyMode">
      <DepartmentDailyReportPanel
        v-if="entryMode === 'DAILY'"
        :department-key="departmentKey"
        :department-name="departmentName"
        :today="businessDate"
      />
      <PatientConsumptionWorkspace v-else :department-key="departmentKey" :today="businessDate" />
    </template>

    <template v-else>
      <header class="qhw-head">
        <div class="qhw-head-copy">
          <span class="qhw-eyebrow">科室定额 · {{ departmentName }}</span>
          <h1>{{ formatDate(businessDate) }}</h1>
        </div>
        <div class="qhw-head-actions">
          <el-radio-group v-if="entryMode === 'PATIENT'" v-model="view" size="small">
            <el-radio-button value="entry">今日填报</el-radio-button>
            <el-radio-button value="evidence">患者证据</el-radio-button>
          </el-radio-group>
        </div>
      </header>

      <div class="qhw-canvas">
        <div class="qhw-main">
          <PatientEvidencePanel
            v-if="view === 'evidence'"
            :department-key="departmentKey"
            :date="businessDate"
            embedded
          />
          <template v-else>
            <div class="qhw-summary" aria-label="今日摘要">
              <span>耗材行 <b>{{ allLines.length }}</b></span>
              <span>接诊 <b>{{ headcountTotal }}</b></span>
              <span>应扣 <b>{{ receiptSummary.auto }}</b> 项</span>
              <span>实扣 <b>{{ completed ? receiptSummary.deducted : "—" }}</b><template v-if="completed"> 项</template></span>
              <span>待审例外 <b>{{ pendingExceptions }}</b></span>
              <span class="qhw-summary-status" :class="`is-${summaryStatus.tone}`">{{ summaryStatus.label }}</span>
            </div>

            <section class="qhw-headcount">
              <div class="qhw-headcount-main">
                <div class="qhw-headcount-title">
                  <strong>接诊人数</strong>
                  <span v-if="headcountSourceLabel" class="qhw-source-pill">{{ headcountSourceLabel }}</span>
                </div>
                <small class="qhw-headcount-hint">
                  {{ entryMode === "PATIENT"
                    ? "按当日未取消就诊记录自动统计，同一患者多次就诊算多次。"
                    : "本科室无临床接诊记录，请手工填写今日服务人次。" }}
                </small>
                <div class="qhw-headcount-fields">
                  <label>
                    门诊
                    <el-input-number
                      v-model="headcountForm.outpatient"
                      :min="0"
                      :precision="0"
                      size="small"
                      :disabled="!headcountEditable"
                    />
                  </label>
                  <label>
                    住院
                    <el-input-number
                      v-model="headcountForm.inpatient"
                      :min="0"
                      :precision="0"
                      size="small"
                      :disabled="!headcountEditable"
                    />
                  </label>
                  <el-button v-if="canCorrectHeadcount" text type="primary" size="small" @click="openCorrection">
                    修正人数
                  </el-button>
                  <small v-if="prefillDate" class="qhw-muted">已沿用 {{ prefillDate }} 人数</small>
                </div>
              </div>
              <div class="qhw-headcount-actions">
                <span class="qhw-headcount-total">合计 <b>{{ headcountTotal }}</b> 人</span>
                <el-button
                  v-if="!completed && !locked"
                  type="primary"
                  :loading="submitting"
                  @click="openConfirm"
                >
                  确认人数并提交
                </el-button>
                <el-tag v-else-if="locked" size="small" type="info">已锁定</el-tag>
                <el-tag v-else size="small" type="success">已提交</el-tag>
              </div>
            </section>

            <section class="qhw-table-card">
              <el-table
                :data="allLines"
                :row-key="rowKey"
                height="calc(100vh - 340px)"
                class="qhw-table"
                empty-text="今日没有应扣耗材"
              >
                <el-table-column label="耗材" min-width="200" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span class="qhw-item-name">{{ row.itemName }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="unit" label="单位" width="70" />
                <el-table-column label="原用量" width="120" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.sourceText || "—" }}</template>
                </el-table-column>
                <el-table-column label="应耗（定额）" width="110" align="right">
                  <template #default="{ row }">{{ formatQuantity(row.quotaQuantity) }}</template>
                </el-table-column>
                <el-table-column label="实扣" width="100" align="right">
                  <template #default="{ row }">
                    {{ completed ? formatQuantity(row.quotaDeductedQuantity) : "—" }}
                  </template>
                </el-table-column>
                <el-table-column label="扣减状态" width="120">
                  <template #default="{ row }">
                    <span v-if="!completed" class="qhw-muted">待提交</span>
                    <el-tag v-else size="small" :type="deductionTagType(row)" effect="light">
                      {{ deductionOf(row).label }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="例外状态" width="100">
                  <template #default="{ row }">
                    <span v-if="!completed" class="qhw-muted">—</span>
                    <el-tag
                      v-else-if="row.exceptionStatus && row.exceptionStatus !== 'NONE'"
                      size="small"
                      :type="exceptionStatusTone(row.exceptionStatus)"
                      effect="light"
                    >
                      {{ exceptionStatusLabel(row.exceptionStatus) }}
                    </el-tag>
                    <span v-else class="qhw-muted">无</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="96" fixed="right">
                  <template #default="{ row }">
                    <el-button
                      link
                      type="warning"
                      size="small"
                      :disabled="!canReportException"
                      :title="canReportException ? `上报 ${row.itemName} 例外` : '提交后可上报例外'"
                      @click="openExceptionFor(row)"
                    >
                      上报例外
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
              <p class="qhw-table-hint">
                科室不能修改定额或已扣数量；超量请上报例外，由管理员审核确定补扣量。
              </p>
            </section>
          </template>
        </div>

        <aside class="qhw-calendar">
          <DepartmentFillCalendar
            ref="fillCalendarRef"
            review-mode
            :month="calendarMonth"
            :today="today"
            :selected-date="calendarDate"
            :working-date="businessDate"
            :mode="entryMode"
            :days="calendarDays"
            :expected-from="calendarExpectedFrom"
            :loading="calendarLoading"
            @update:month="changeMonth"
            @review-date="openDayDetail"
            @select-date="changeWorkingDate"
          />
        </aside>
      </div>

      <QuotaDayDetailDrawer
        ref="dayDetailRef"
        v-model="dayDetailVisible"
        :date="calendarDate"
        :department-key="departmentKey"
        :department-name="departmentName"
        :today="today"
        :working-date="businessDate"
        :can-export="canExport"
        @open-day="openDayFromDrawer"
      />

      <el-dialog v-model="confirmVisible" title="确认提交今日人数？" width="440px" class="qhw-modal">
        <div class="qhw-confirm">
          <div><span>接诊人次</span><strong>{{ headcountTotal }} 人</strong></div>
          <div><span>自动计算耗材</span><strong>{{ autoItemCount }} 项</strong></div>
          <div><span>库存处理</span><strong>将扣减 {{ autoItemCount }} 项耗材库存</strong></div>
        </div>
        <p class="qhw-confirm-note">提交后人数仍可修正，耗材数量不可修改。</p>
        <template #footer>
          <el-button @click="confirmVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="submitReport">确认提交</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="correctionVisible" title="修正今日人数" width="460px" class="qhw-modal">
        <el-form label-position="top">
          <div class="qhw-correction-grid">
            <el-form-item label="门诊人次">
              <el-input-number v-model="headcountForm.outpatient" :min="0" :precision="0" />
            </el-form-item>
            <el-form-item label="住院人次">
              <el-input-number v-model="headcountForm.inpatient" :min="0" :precision="0" />
            </el-form-item>
          </div>
          <el-form-item label="修正原因" required>
            <el-input v-model="headcountReason" type="textarea" :rows="2" placeholder="例如：补录晚到患者、登记归属调整" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="correctionVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="saveCorrection">保存修正</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="exceptionVisible" title="上报定额例外" width="560px" destroy-on-close>
        <div class="qhw-exception-target" v-if="exceptionLine">
          <div>
            <strong>{{ exceptionLine.itemName }}</strong>
            <span v-if="exceptionLine.sourceText">· {{ exceptionLine.sourceText }}</span>
          </div>
          <span>应耗 {{ formatQuantity(exceptionLine.quotaQuantity) }} {{ exceptionLine.unit }}</span>
        </div>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="科室只能上报事实，最终补扣量由管理员审核确定。"
          style="margin-bottom: 16px"
        />
        <el-form label-position="top">
          <el-form-item label="当日实际上报总量" required>
            <el-input-number
              v-model="exceptionForm.reportedActualQuantity"
              :min="0.001"
              :precision="2"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="原因说明" required>
            <el-input
              v-model="exceptionForm.reason"
              type="textarea"
              :rows="3"
              placeholder="例如：急诊加台、术中污染更换、未列入定额的临时耗材"
            />
          </el-form-item>
          <el-form-item label="现场照片（选填，最多 3 张）">
            <el-upload
              v-model:file-list="exceptionFiles"
              list-type="picture-card"
              accept="image/*"
              :auto-upload="false"
              :limit="3"
            >
              <el-icon><Plus /></el-icon>
            </el-upload>
          </el-form-item>
        </el-form>
        <template #footer>
          <div class="qhw-drawer-footer">
            <el-button @click="exceptionVisible = false">取消</el-button>
            <el-button type="primary" :loading="exceptionSubmitting" @click="submitException">上报例外</el-button>
          </div>
        </template>
      </el-dialog>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import dayjs from "dayjs";
import DepartmentDailyReportPanel from "../DepartmentDailyReportPanel.vue";
import PatientConsumptionWorkspace from "../PatientConsumptionWorkspace.vue";
import PatientEvidencePanel from "../PatientEvidencePanel.vue";
import DepartmentFillCalendar from "../DepartmentFillCalendar.vue";
import QuotaDayDetailDrawer from "./QuotaDayDetailDrawer.vue";
import {
  deductionStatus,
  exceptionStatusLabel,
  exceptionStatusTone,
  formatReceiptQuantity,
  receiptCounts
} from "./quotaReceipt";
import {
  correctInventoryQuotaHeadcountApi,
  getInventoryDepartmentDailyReportApi,
  getInventoryDepartmentDailyReportCalendarApi,
  reportInventoryQuotaExceptionApi,
  saveInventoryQuotaDailyReportApi,
  submitInventoryDepartmentDailyReportApi,
  uploadInventoryQuotaExceptionPhotoApi,
  type InventoryDepartmentDailyReport,
  type InventoryDepartmentDailyReportCalendarDay,
  type InventoryDepartmentDailyReportLine
} from "@/api/modules/inventory";

const props = withDefaults(
  defineProps<{ departmentKey: string; departmentName?: string; today?: string; canExport?: boolean }>(),
  { canExport: false }
);

const today = props.today || dayjs().format("YYYY-MM-DD");
const businessDate = ref(today);
const calendarDate = ref(today);
const loading = ref(false);
const saving = ref(false);
const submitting = ref(false);
const exceptionSubmitting = ref(false);
const draft = ref<InventoryDepartmentDailyReport>();
const legacyMode = computed(() => Boolean(draft.value) && draft.value?.reportMode !== "QUOTA");
const entryMode = computed<"PATIENT" | "DAILY">(() => (draft.value?.entryMode === "DAILY" ? "DAILY" : "PATIENT"));
const view = ref<"entry" | "evidence">("entry");
const confirmVisible = ref(false);
const correctionVisible = ref(false);
const exceptionVisible = ref(false);
const dayDetailVisible = ref(false);
const dayDetailRef = ref<InstanceType<typeof QuotaDayDetailDrawer>>();
const fillCalendarRef = ref<InstanceType<typeof DepartmentFillCalendar>>();
const savedAt = ref("");
const headcountReason = ref("");
const prefillDate = ref("");
const headcountForm = reactive({ outpatient: 0, inpatient: 0 });
const exceptionLineId = ref("");
const exceptionForm = reactive({ reportedActualQuantity: 0, reason: "" });
const exceptionFiles = ref<any[]>([]);
let autosaveTimer: ReturnType<typeof setTimeout> | undefined;
let suppressAutosave = false;

const headcountTotal = computed(() => {
  if (!draft.value || draft.value.status === "DRAFT") {
    return Number(headcountForm.outpatient || 0) + Number(headcountForm.inpatient || 0);
  }
  return Number(draft.value.headcount?.total || 0);
});
const headcountSourceLabel = computed(() => {
  const source = draft.value?.headcount?.source || "";
  if (source === "AUTO") return "系统自动统计";
  if (source === "MIXED") return "已修正";
  if (source === "MANUAL") return "手工填写";
  return "";
});
const allLines = computed(() => draft.value?.lines || []);
const autoLines = computed(() => allLines.value.filter(line => !line.exceptionOnly && Number(line.quotaQuantity || 0) > 0));
const autoItemCount = computed(() => autoLines.value.length);
const receiptSummary = computed(() => receiptCounts(draft.value));
const pendingExceptions = computed(() => Number(draft.value?.pendingExceptions || 0));
const completed = computed(() => Boolean(draft.value?.id) && draft.value?.status !== "DRAFT");
const locked = computed(() => Boolean(draft.value?.locked));
const headcountEditable = computed(
  () => entryMode.value === "DAILY" && !completed.value && !locked.value
);
const canCorrectHeadcount = computed(
  () =>
    !locked.value &&
    Boolean(draft.value) &&
    (entryMode.value === "PATIENT" || completed.value)
);
const canReportException = computed(() => completed.value && !locked.value);
const exceptionLine = computed(() =>
  allLines.value.find(line => line.lineId === exceptionLineId.value)
);
const summaryStatus = computed(() => {
  if (locked.value) return { label: "已锁定", tone: "locked" };
  if (completed.value) {
    const value = draft.value?.closure?.submittedAt;
    return {
      label: value ? `提交于 ${dayjs(value).format("MM-DD HH:mm")}` : "已提交",
      tone: "success"
    };
  }
  return {
    label: savedAt.value ? `未提交 · 自动保存 ${savedAt.value}` : "未提交",
    tone: "draft"
  };
});

const calendarMonth = ref(dayjs(businessDate.value).format("YYYY-MM-01"));
const calendarDays = ref<InventoryDepartmentDailyReportCalendarDay[]>([]);
const calendarExpectedFrom = ref("");
const calendarLoading = ref(false);

const formatQuantity = (value?: number | null) => Number(value || 0).toFixed(2);
const formatDate = (value: string) => dayjs(value).format("M月D日 dddd");
const rowKey = (row: any) => row.lineId || row.quotaRuleId || row.itemId;
const deductionOf = (row: any) => deductionStatus(row as InventoryDepartmentDailyReportLine);
const deductionTagType = (row: any) => {
  const tone = deductionOf(row).tone;
  if (tone === "success") return "success";
  if (tone === "warning") return "warning";
  if (tone === "danger") return "danger";
  return "info";
};
const quantity = formatReceiptQuantity;

const applyDraft = (data?: InventoryDepartmentDailyReport) => {
  suppressAutosave = true;
  draft.value = data;
  const headcount = data?.headcount;
  const usePrevious =
    data?.entryMode === "DAILY" &&
    (!data?.id || (data?.status === "DRAFT" && !headcount?.source)) &&
    data?.previousHeadcount;
  if (usePrevious) {
    headcountForm.outpatient = Number(data?.previousHeadcount?.outpatient || 0);
    headcountForm.inpatient = Number(data?.previousHeadcount?.inpatient || 0);
    prefillDate.value = data?.previousHeadcountDate || "";
  } else if (headcount) {
    headcountForm.outpatient = Number(headcount.outpatient || 0);
    headcountForm.inpatient = Number(headcount.inpatient || 0);
    prefillDate.value = "";
  }
  headcountReason.value = data?.headcountReason || "";
  setTimeout(() => (suppressAutosave = false), 0);
};

const load = async () => {
  loading.value = true;
  savedAt.value = "";
  try {
    const result = await getInventoryDepartmentDailyReportApi({
      departmentKey: props.departmentKey,
      date: businessDate.value
    });
    applyDraft(result.data);
    calendarMonth.value = dayjs(businessDate.value).format("YYYY-MM-01");
    await loadCalendar();
  } catch (error: any) {
    ElMessage.error(error?.message || "填报数据加载失败");
  } finally {
    loading.value = false;
  }
};

const loadCalendar = async () => {
  calendarLoading.value = true;
  try {
    const result = await getInventoryDepartmentDailyReportCalendarApi({
      departmentKey: props.departmentKey,
      month: calendarMonth.value
    });
    calendarDays.value = result.data?.days || [];
    calendarExpectedFrom.value = result.data?.expectedFrom || "";
  } catch {
    calendarDays.value = [];
  } finally {
    calendarLoading.value = false;
  }
};

const changeMonth = (month: string) => {
  calendarMonth.value = month;
  void loadCalendar();
};

const changeWorkingDate = (date: string) => {
  if (!date) return;
  businessDate.value = date;
  calendarDate.value = date;
  dayDetailVisible.value = false;
  void load();
};

const openDayDetail = (date: string) => {
  calendarDate.value = date;
  dayDetailVisible.value = true;
};

const openDayFromDrawer = (date: string) => {
  dayDetailVisible.value = false;
  changeWorkingDate(date);
};

watch(dayDetailVisible, visible => {
  if (!visible) {
    nextTick(() => fillCalendarRef.value?.focusDate(calendarDate.value));
  }
});

const saveHeadcount = async (reason?: string) => {
  if (!draft.value || legacyMode.value) return;
  saving.value = true;
  try {
    const payload = {
      departmentKey: props.departmentKey,
      businessDate: businessDate.value,
      revision: draft.value.revision || 0,
      note: draft.value.note || "",
      headcountOverride: Boolean(reason || draft.value.headcount?.source === "MIXED"),
      headcountOutpatient: headcountForm.outpatient,
      headcountInpatient: headcountForm.inpatient,
      headcountManual: 0,
      headcountReason: reason || headcountReason.value
    };
    const result =
      draft.value.id && draft.value.status !== "DRAFT"
        ? await correctInventoryQuotaHeadcountApi(draft.value.id, {
            headcountOutpatient: payload.headcountOutpatient,
            headcountInpatient: payload.headcountInpatient,
            headcountManual: 0,
            reason: payload.headcountReason
          })
        : await saveInventoryQuotaDailyReportApi(payload);
    applyDraft(result.data);
    savedAt.value = dayjs().format("HH:mm");
    return result.data;
  } catch (error: any) {
    ElMessage.error(error?.message || "人数保存失败");
    return null;
  } finally {
    saving.value = false;
  }
};

const openCorrection = () => {
  headcountForm.outpatient = Number(draft.value?.headcount?.outpatient || headcountForm.outpatient || 0);
  headcountForm.inpatient = Number(draft.value?.headcount?.inpatient || headcountForm.inpatient || 0);
  headcountReason.value = "";
  correctionVisible.value = true;
};

const saveCorrection = async () => {
  if (!headcountReason.value.trim()) {
    ElMessage.warning("请填写修正原因");
    return;
  }
  const result = await saveHeadcount(headcountReason.value.trim());
  if (result) {
    correctionVisible.value = false;
    dayDetailRef.value?.invalidate(businessDate.value);
    void loadCalendar();
    ElMessage.success("人数已修正");
  }
};

const openConfirm = async () => {
  if (!draft.value || !draft.value.id) {
    const saved = await saveHeadcount();
    if (!saved) return;
  }
  confirmVisible.value = true;
};

const submitReport = async () => {
  if (!draft.value?.id) return;
  submitting.value = true;
  try {
    if (draft.value.status === "DRAFT") {
      const saved = await saveHeadcount();
      if (!saved?.id) return;
    }
    const result = await submitInventoryDepartmentDailyReportApi(draft.value.id);
    applyDraft(result.data);
    confirmVisible.value = false;
    calendarDate.value = businessDate.value;
    dayDetailRef.value?.invalidate(businessDate.value);
    void loadCalendar();
    ElMessage.success("已提交，定额部分已扣库");
  } catch (error: any) {
    ElMessage.error(error?.message || "提交失败");
  } finally {
    submitting.value = false;
  }
};

const openExceptionFor = (row: any) => {
  const line = row as InventoryDepartmentDailyReportLine;
  exceptionLineId.value = line.lineId || "";
  exceptionForm.reportedActualQuantity = Number(line.quotaQuantity || 0) + 0.01;
  exceptionForm.reason = "";
  exceptionFiles.value = [];
  exceptionVisible.value = true;
};

const submitException = async () => {
  const line = exceptionLine.value;
  if (!line || !exceptionForm.reason.trim()) {
    ElMessage.warning("请填写原因");
    return;
  }
  exceptionSubmitting.value = true;
  try {
    const result = await reportInventoryQuotaExceptionApi({
      departmentKey: props.departmentKey,
      businessDate: businessDate.value,
      itemId: line.itemId,
      lineId: line.lineId,
      quotaRuleId: line.quotaRuleId,
      reportedActualQuantity: exceptionForm.reportedActualQuantity,
      reason: exceptionForm.reason.trim()
    });
    for (const file of exceptionFiles.value) {
      if (file.raw) await uploadInventoryQuotaExceptionPhotoApi(result.data.id, file.raw);
    }
    exceptionVisible.value = false;
    dayDetailRef.value?.invalidate(businessDate.value);
    ElMessage.success("例外已上报，等待管理员审核");
    await load();
  } catch (error: any) {
    ElMessage.error(error?.message || "例外上报失败");
  } finally {
    exceptionSubmitting.value = false;
  }
};

watch(
  () => [headcountForm.outpatient, headcountForm.inpatient],
  () => {
    if (suppressAutosave || entryMode.value !== "DAILY" || completed.value || locked.value) return;
    clearTimeout(autosaveTimer);
    autosaveTimer = setTimeout(() => {
      if (entryMode.value === "DAILY" && !completed.value && !locked.value) void saveHeadcount();
    }, 700);
  }
);

onMounted(load);
onBeforeUnmount(() => {
  clearTimeout(autosaveTimer);
});
</script>

<style scoped lang="scss">
.qhw {
  --qhw-bg: #faf7f1;
  --qhw-surface: #fffdf9;
  --qhw-surface-2: #fbf6ee;
  --qhw-ink: #2f2a24;
  --qhw-muted: #7b7064;
  --qhw-line: #e8dfd2;
  --qhw-accent: #2f6b58;
  --qhw-accent-soft: #e9f1ed;
  --qhw-warm: #b7793f;
  --qhw-warm-soft: #f7ecdd;
  --qhw-success: #3f7f62;
  --qhw-success-soft: #eaf4ee;

  display: flex;
  flex-direction: column;
  gap: 12px;
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  max-width: 1440px;
  margin: 0 auto;
  padding: 18px 24px 28px;
  color: var(--qhw-ink);
  background: var(--qhw-bg);
}

.qhw-head {
  display: flex;
  gap: 14px;
  align-items: flex-end;
  justify-content: space-between;
  padding: 0 2px;
}

.qhw-head-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;

  h1 {
    margin: 0;
    font-size: 20px;
    font-weight: 600;
    letter-spacing: -0.01em;
  }
}

.qhw-eyebrow {
  color: var(--qhw-muted);
  font-size: 12px;
}

.qhw-head-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.qhw-canvas {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 318px;
  gap: 14px;
  align-items: start;
}

.qhw-main {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.qhw-calendar {
  position: sticky;
  top: 12px;
  min-width: 0;
}

.qhw-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  align-items: center;
  padding: 10px 14px;
  color: var(--qhw-muted);
  font-size: 12px;
  background: var(--qhw-surface);
  border: 1px solid var(--qhw-line);
  border-radius: 12px;

  b {
    color: var(--qhw-ink);
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
}

.qhw-summary-status {
  margin-left: auto;
  padding: 2px 8px;
  font-size: 11px;
  border: 1px solid transparent;
  border-radius: 999px;

  &.is-success {
    color: var(--qhw-success);
    background: var(--qhw-success-soft);
    border-color: #cfe3d6;
  }

  &.is-draft {
    color: #8a5a13;
    background: var(--qhw-warm-soft);
    border-color: #ebd8bd;
  }

  &.is-locked {
    color: var(--qhw-muted);
    background: var(--qhw-surface-2);
    border-color: var(--qhw-line);
  }
}

.qhw-headcount {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: flex-end;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--qhw-surface);
  border: 1px solid var(--qhw-line);
  border-radius: 12px;
}

.qhw-headcount-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.qhw-headcount-title {
  display: flex;
  gap: 8px;
  align-items: baseline;

  strong {
    font-size: 15px;
    font-weight: 600;
  }
}

.qhw-source-pill {
  padding: 1px 7px;
  color: var(--qhw-accent);
  font-size: 10px;
  background: var(--qhw-accent-soft);
  border-radius: 999px;
}

.qhw-headcount-hint {
  color: var(--qhw-muted);
  font-size: 11px;
}

.qhw-headcount-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;

  label {
    display: flex;
    flex-direction: column;
    gap: 3px;
    color: var(--qhw-muted);
    font-size: 11px;
  }
}

.qhw-headcount-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.qhw-headcount-total {
  color: var(--qhw-muted);
  font-size: 12px;
  white-space: nowrap;

  b {
    color: var(--qhw-ink);
    font-size: 18px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
}

.qhw-table-card {
  overflow: hidden;
  background: var(--qhw-surface);
  border: 1px solid var(--qhw-line);
  border-radius: 12px;
}

.qhw-table {
  width: 100%;

  :deep(.el-table__header th) {
    color: var(--qhw-muted);
    font-size: 12px;
    background: var(--qhw-surface-2);
  }

  :deep(.el-table__row td) {
    font-size: 13px;
  }
}

.qhw-item-name {
  color: var(--qhw-ink);
  font-weight: 500;
}

.qhw-muted {
  color: var(--qhw-muted);
  font-size: 12px;
}

.qhw-table-hint {
  margin: 0;
  padding: 8px 14px;
  color: var(--qhw-muted);
  font-size: 11px;
  border-top: 1px dashed var(--qhw-line);
}

.qhw-exception-target {
  display: flex;
  gap: 12px;
  align-items: baseline;
  justify-content: space-between;
  padding: 10px 12px;
  margin-bottom: 14px;
  background: var(--qhw-surface-2);
  border: 1px solid var(--qhw-line);
  border-radius: 10px;

  > div {
    display: flex;
    gap: 6px;
    align-items: baseline;
    min-width: 0;
  }

  strong {
    font-size: 14px;
  }

  span {
    color: var(--qhw-muted);
    font-size: 12px;
  }
}

.qhw-confirm {
  display: flex;
  flex-direction: column;
  gap: 10px;

  div {
    display: flex;
    justify-content: space-between;
    gap: 16px;
    font-size: 14px;
  }

  span {
    color: var(--qhw-muted);
  }
}

.qhw-confirm-note {
  margin: 14px 0 0;
  color: var(--qhw-muted);
  font-size: 12px;
}

.qhw-correction-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.qhw-drawer-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

@media (max-width: 1180px) {
  .qhw-canvas {
    grid-template-columns: 1fr;
  }

  .qhw-calendar {
    position: static;
  }
}

@media (max-width: 760px) {
  .qhw {
    padding: 14px;
  }

  .qhw-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .qhw-summary-status {
    margin-left: 0;
  }

  .qhw-headcount {
    align-items: stretch;
  }

  .qhw-headcount-actions {
    justify-content: space-between;
  }
}

@media (prefers-reduced-motion: reduce) {
  .qhw *,
  .qhw *::before,
  .qhw *::after {
    animation: none !important;
    transition: none !important;
  }
}
</style>
