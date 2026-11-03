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
        <div>
          <small class="qhw-eyebrow">科室定额 · {{ departmentName }}</small>
          <h1>{{ formatDate(businessDate) }}</h1>
        </div>
        <div class="qhw-head-actions">
          <el-radio-group v-if="entryMode === 'PATIENT'" v-model="view" size="small">
            <el-radio-button value="entry">今日填报</el-radio-button>
            <el-radio-button value="evidence">患者证据</el-radio-button>
          </el-radio-group>
          <el-button text @click="historyVisible = true">历史</el-button>
        </div>
      </header>

      <div class="qhw-week" role="group" aria-label="业务日期">
        <button
          v-for="day in weekDays"
          :key="day.date"
          type="button"
          class="qhw-day"
          :class="{ 'is-active': day.date === businessDate }"
          :aria-pressed="day.date === businessDate"
          @click="selectDate(day.date)"
        >
          <span>{{ day.weekday }}</span>
          <strong>{{ day.label }}</strong>
          <i v-if="day.status" class="qhw-day-dot" :class="`is-${day.status}`" />
        </button>
      </div>

      <PatientEvidencePanel v-if="view === 'evidence'" :department-key="departmentKey" :date="businessDate" embedded />

      <template v-else>
        <section v-if="completed" class="qhw-card qhw-complete" aria-live="polite">
          <div class="qhw-complete-head">
            <div>
              <small>今日已确认</small>
              <h2>{{ headcountTotal }} 人次</h2>
            </div>
            <el-tag type="success" effect="plain">已提交</el-tag>
          </div>
          <div class="qhw-metrics">
            <div><span>计算耗材</span><strong>{{ autoItemCount }} 项</strong></div>
            <div><span>已扣库存</span><strong>{{ deductedCount }} 项</strong></div>
            <div><span>待审例外</span><strong>{{ draft?.pendingExceptions || 0 }}</strong></div>
          </div>
          <el-alert
            v-if="shortageCount"
            type="warning"
            :closable="false"
            show-icon
            :title="`${shortageCount} 项因库存不足未扣减，已生成库管待办`"
          />
          <div class="qhw-complete-actions">
            <el-button text @click="detailsOpen = !detailsOpen">查看明细</el-button>
            <el-button v-if="!locked" text @click="openCorrection">修正人数</el-button>
            <el-button v-if="!locked" type="primary" plain @click="openException">上报例外</el-button>
          </div>
        </section>

        <section v-else class="qhw-card">
          <div class="qhw-count-row">
            <div>
              <small>{{ entryMode === "DAILY" ? "今日服务人次" : "今日接诊人次" }}</small>
              <div class="qhw-count">
                <strong>{{ headcountTotal }}</strong>
                <span>人</span>
                <el-tag v-if="headcountSourceLabel" size="small" effect="plain">{{ headcountSourceLabel }}</el-tag>
                <el-button v-if="entryMode === 'PATIENT' && !locked" text type="primary" @click="openCorrection">修正</el-button>
              </div>
            </div>
            <div class="qhw-save-state" aria-live="polite">{{ savedAt ? `已自动保存 ${savedAt}` : "" }}</div>
          </div>

          <div v-if="entryMode === 'DAILY'" class="qhw-manual">
            <label>门诊人次<el-input-number v-model="headcountForm.outpatient" :min="0" :precision="0" size="large" /></label>
            <label>住院人次<el-input-number v-model="headcountForm.inpatient" :min="0" :precision="0" size="large" /></label>
            <small v-if="prefillDate">已沿用 {{ prefillDate }} 的人数，可直接修改</small>
          </div>

          <p v-if="headcountTotal === 0" class="qhw-zero-hint">今日暂未统计到就诊，可修正后提交；固定日耗仍会计算。</p>
          <p class="qhw-system-note">系统已按院定定额计算 {{ autoItemCount }} 项耗材，确认后将扣减科室库存。</p>

          <el-button class="qhw-cta" type="primary" size="large" :disabled="locked || submitting" :loading="submitting" @click="openConfirm">
            确认人数并提交
          </el-button>
          <div class="qhw-foot-hint">提交后可修正人数；耗材数量不可修改。提交后可上报超量/未列耗材。</div>
        </section>

        <button type="button" class="qhw-detail-toggle" :aria-expanded="detailsOpen" @click="detailsOpen = !detailsOpen">
          <span>{{ detailsOpen ? "▾" : "▸" }} 查看应耗明细（{{ autoItemCount }} 项）</span>
          <small v-if="onDemandCount">另有 {{ onDemandCount }} 项按需耗材</small>
        </button>

        <section v-if="detailsOpen" class="qhw-detail">
          <div v-for="group in detailGroups" :key="group.key" class="qhw-group">
            <h3>{{ group.label }} <small>{{ group.lines.length }} 项</small></h3>
            <div v-for="line in group.lines" :key="line.lineId || line.quotaRuleId" class="qhw-line">
              <span>{{ line.itemName }}<small v-if="line.sourceText"> · {{ line.sourceText }}</small></span>
              <strong>{{ formatQuantity(line.quotaQuantity) }} {{ line.unit }}</strong>
            </div>
          </div>
          <p v-if="onDemandCount" class="qhw-on-demand">另有 {{ onDemandCount }} 项按需/未纳入耗材，不计入自动扣库；实际使用请提交后上报例外。</p>
          <el-empty v-if="!autoItemCount && !onDemandCount" :image-size="60" description="今日没有需要计算的耗材" />
        </section>
      </template>

      <el-dialog v-model="confirmVisible" title="确认提交今日人数？" width="420px">
        <div class="qhw-confirm">
          <div><span>接诊/服务人次</span><strong>{{ headcountTotal }} 人</strong></div>
          <div><span>自动计算耗材</span><strong>{{ autoItemCount }} 项</strong></div>
          <div><span>库存处理</span><strong>将扣减 {{ autoItemCount }} 项耗材库存</strong></div>
        </div>
        <p class="qhw-confirm-note">提交后人数仍可修正，耗材数量不可修改。</p>
        <template #footer>
          <el-button @click="confirmVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="submitReport">确认提交</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="correctionVisible" title="修正今日人数" width="460px">
        <el-form label-position="top">
          <div class="qhw-correction-grid">
            <el-form-item label="门诊人次"><el-input-number v-model="headcountForm.outpatient" :min="0" :precision="0" /></el-form-item>
            <el-form-item label="住院人次"><el-input-number v-model="headcountForm.inpatient" :min="0" :precision="0" /></el-form-item>
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

      <el-dialog v-model="exceptionVisible" title="上报定额例外" width="520px" destroy-on-close>
        <el-alert type="warning" :closable="false" show-icon title="科室只能上报事实，最终补扣量由管理员审核确定。" style="margin-bottom: 12px" />
        <el-form label-position="top">
          <el-form-item label="耗材" required>
            <el-select v-model="exceptionLineId" filterable placeholder="搜索当日耗材或按需/未纳入项" style="width: 100%">
              <el-option
                v-for="line in exceptionOptions"
                :key="line.lineId"
                :label="`${line.itemName}${line.sourceText ? ` · ${line.sourceText}` : ''}`"
                :value="line.lineId || ''"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="当日实际上报总量" required>
            <el-input-number v-model="exceptionForm.reportedActualQuantity" :min="0.001" :precision="2" controls-position="right" />
          </el-form-item>
          <el-form-item label="原因说明" required>
            <el-input v-model="exceptionForm.reason" type="textarea" :rows="3" placeholder="例如：急诊加台、术中污染更换、未列入定额的临时耗材" />
          </el-form-item>
          <el-form-item label="现场照片（选填，最多 3 张）">
            <el-upload v-model:file-list="exceptionFiles" list-type="picture-card" accept="image/*" :auto-upload="false" :limit="3">
              <el-icon><Plus /></el-icon>
            </el-upload>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="exceptionVisible = false">取消</el-button>
          <el-button type="primary" :loading="exceptionSubmitting" @click="submitException">上报例外</el-button>
        </template>
      </el-dialog>

      <el-drawer v-model="historyVisible" title="历史填报" size="440px">
        <DepartmentFillCalendar
          :month="calendarMonth"
          :today="today"
          :selected-date="businessDate"
          :mode="entryMode"
          :days="calendarDays"
          :expected-from="calendarExpectedFrom"
          :loading="calendarLoading"
          @update:month="changeMonth"
          @select-date="selectDate"
        />
      </el-drawer>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import dayjs from "dayjs";
import DepartmentDailyReportPanel from "../DepartmentDailyReportPanel.vue";
import PatientConsumptionWorkspace from "../PatientConsumptionWorkspace.vue";
import PatientEvidencePanel from "../PatientEvidencePanel.vue";
import DepartmentFillCalendar from "../DepartmentFillCalendar.vue";
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

const props = defineProps<{ departmentKey: string; departmentName?: string; today?: string }>();
const today = props.today || dayjs().format("YYYY-MM-DD");
const businessDate = ref(today);
const loading = ref(false);
const saving = ref(false);
const submitting = ref(false);
const exceptionSubmitting = ref(false);
const draft = ref<InventoryDepartmentDailyReport>();
const legacyMode = computed(() => Boolean(draft.value) && draft.value?.reportMode !== "QUOTA");
const entryMode = computed(() => draft.value?.entryMode || "DAILY");
const view = ref<"entry" | "evidence">("entry");
const detailsOpen = ref(false);
const confirmVisible = ref(false);
const correctionVisible = ref(false);
const exceptionVisible = ref(false);
const historyVisible = ref(false);
const savedAt = ref("");
const headcountReason = ref("");
const prefillDate = ref("");
const headcountForm = reactive({ outpatient: 0, inpatient: 0 });
let autosaveTimer: ReturnType<typeof setTimeout> | undefined;
let suppressAutosave = false;

const headcountTotal = computed(() => {
  if (draft.value?.headcount) return Number(draft.value.headcount.total || 0);
  return Number(headcountForm.outpatient || 0) + Number(headcountForm.inpatient || 0);
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
const onDemandCount = computed(() => allLines.value.filter(line => line.exceptionOnly).length);
const deductedCount = computed(() => autoLines.value.filter(line => Number(line.quotaDeductedQuantity || 0) >= Number(line.quotaQuantity || 0)).length);
const shortageCount = computed(() => autoLines.value.filter(line => Number(line.quotaDeductedQuantity || 0) < Number(line.quotaQuantity || 0)).length);
const completed = computed(() => Boolean(draft.value?.id) && draft.value?.status !== "DRAFT");
const locked = computed(() => Boolean(draft.value?.locked));
const detailGroups = computed(() => {
  const groups = [
    { key: "per", label: "按人次计", lines: [] as InventoryDepartmentDailyReportLine[] },
    { key: "daily", label: "每日固定", lines: [] as InventoryDepartmentDailyReportLine[] },
    { key: "period", label: "周期摊消", lines: [] as InventoryDepartmentDailyReportLine[] }
  ];
  for (const line of autoLines.value) {
    const group = line.bindingType === "PER_PERSON" ? groups[0] : Number(line.periodDays || 1) > 1 ? groups[2] : groups[1];
    group.lines.push(line);
  }
  return groups.filter(group => group.lines.length);
});
const exceptionOptions = computed(() => allLines.value);
const exceptionLineId = ref("");
const exceptionForm = reactive({ reportedActualQuantity: 0, reason: "" });
const exceptionFiles = ref<any[]>([]);
const calendarMonth = ref(dayjs(businessDate.value).format("YYYY-MM-01"));
const calendarDays = ref<InventoryDepartmentDailyReportCalendarDay[]>([]);
const calendarExpectedFrom = ref("");
const calendarLoading = ref(false);
const weekDays = computed(() => {
  const statusByDate = new Map(calendarDays.value.map(day => [day.date, day.status]));
  return Array.from({ length: 7 }, (_, index) => {
    const date = dayjs(businessDate.value).subtract(6 - index, "day");
    const key = date.format("YYYY-MM-DD");
    return {
      date: key,
      label: date.format("MM/DD"),
      weekday: date.format("ddd"),
      status: statusByDate.get(key) || ""
    };
  });
});

const formatQuantity = (value?: number | null) => Number(value || 0).toFixed(2);
const formatDate = (value: string) => dayjs(value).format("M月D日 dddd");

const applyDraft = (data?: InventoryDepartmentDailyReport) => {
  suppressAutosave = true;
  draft.value = data;
  const headcount = data?.headcount;
  const usePrevious = data?.entryMode === "DAILY" && (!data?.id || (data?.status === "DRAFT" && !headcount?.source)) && data?.previousHeadcount;
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
  view.value = "entry";
  setTimeout(() => (suppressAutosave = false), 0);
};

const load = async () => {
  loading.value = true;
  try {
    const result = await getInventoryDepartmentDailyReportApi({ departmentKey: props.departmentKey, date: businessDate.value });
    applyDraft(result.data);
    calendarMonth.value = dayjs(businessDate.value).format("YYYY-MM-01");
    void loadCalendar();
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

const selectDate = (date: string) => {
  if (!date) return;
  businessDate.value = date;
  historyVisible.value = false;
  detailsOpen.value = false;
  void load();
};

const changeMonth = (month: string) => {
  calendarMonth.value = month;
  void loadCalendar();
};

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
    const result = draft.value.id && draft.value.status !== "DRAFT"
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
    ElMessage.success("已提交，定额部分已扣库");
  } catch (error: any) {
    ElMessage.error(error?.message || "提交失败");
  } finally {
    submitting.value = false;
  }
};

const openException = () => {
  exceptionLineId.value = allLines.value[0]?.lineId || "";
  exceptionForm.reportedActualQuantity = Number(allLines.value[0]?.quotaQuantity || 0) + 1;
  exceptionForm.reason = "";
  exceptionFiles.value = [];
  exceptionVisible.value = true;
};

const submitException = async () => {
  const line = allLines.value.find(item => item.lineId === exceptionLineId.value);
  if (!line || !exceptionForm.reason.trim()) {
    ElMessage.warning("请选择耗材并填写原因");
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
    for (const file of exceptionFiles.value) if (file.raw) await uploadInventoryQuotaExceptionPhotoApi(result.data.id, file.raw);
    exceptionVisible.value = false;
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
</script>

<style scoped lang="scss">
.qhw {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px 22px 28px;
  color: var(--hos-text-primary, #2f2a24);
}
.qhw-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  h1 {
    margin: 2px 0 0;
    font-size: 20px;
    font-weight: 600;
  }
}
.qhw-eyebrow {
  color: var(--hos-text-secondary, #6f655a);
  font-size: 13px;
}
.qhw-head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.qhw-week {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.qhw-day {
  position: relative;
  min-width: 62px;
  padding: 8px 10px;
  border: 1px solid var(--hos-border-light, #e8e1d8);
  border-radius: 10px;
  background: var(--hos-bg-primary, #fffdf9);
  color: var(--hos-text-secondary, #6f655a);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
  span { font-size: 11px; }
  strong { font-size: 14px; font-variant-numeric: tabular-nums; }
  &.is-active {
    border-color: var(--hos-primary, #347d68);
    background: var(--hos-primary-soft, #eef6f2);
    color: var(--hos-primary, #347d68);
  }
}
.qhw-day-dot {
  position: absolute;
  right: 8px;
  top: 8px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--hos-status-warning, #d9982f);
  &.is-APPROVED { background: var(--hos-status-success, #3f8f6b); }
  &.is-SUBMITTED { background: var(--hos-status-info, #5b8db8); }
  &.is-LOCKED { background: var(--hos-text-muted, #9a9285); }
}
.qhw-card {
  padding: 22px 24px;
  border: 1px solid var(--hos-border-light, #e8e1d8);
  border-radius: 14px;
  background: var(--hos-bg-primary, #fffdf9);
  box-shadow: 0 6px 18px rgba(80, 63, 45, 0.04);
}
.qhw-count-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  small { color: var(--hos-text-secondary, #6f655a); font-size: 13px; }
}
.qhw-count {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 4px;
  strong { font-size: 40px; line-height: 1; font-variant-numeric: tabular-nums; }
  span { font-size: 15px; color: var(--hos-text-secondary, #6f655a); }
}
.qhw-save-state { color: var(--hos-text-secondary, #6f655a); font-size: 12px; }
.qhw-manual {
  display: flex;
  align-items: flex-end;
  gap: 18px;
  margin-top: 14px;
  flex-wrap: wrap;
  label {
    display: flex;
    flex-direction: column;
    gap: 4px;
    font-size: 13px;
    color: var(--hos-text-secondary, #6f655a);
  }
  small { color: var(--hos-text-secondary, #6f655a); font-size: 12px; }
}
.qhw-system-note {
  margin: 16px 0 0;
  color: var(--hos-text-secondary, #6f655a);
  font-size: 14px;
}
.qhw-zero-hint {
  margin: 12px 0 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--hos-status-warning-soft, #fbf3e3);
  color: var(--hos-status-warning-dark, #8a5a13);
  font-size: 13px;
}
.qhw-cta {
  width: 100%;
  height: 42px;
  margin-top: 18px;
  font-size: 15px;
}
.qhw-foot-hint {
  margin-top: 8px;
  color: var(--hos-text-secondary, #6f655a);
  font-size: 12px;
  text-align: center;
}
.qhw-complete-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  small { color: var(--hos-text-secondary, #6f655a); }
  h2 { margin: 4px 0 0; font-size: 28px; }
}
.qhw-metrics {
  display: flex;
  gap: 28px;
  margin: 16px 0;
  div { display: flex; flex-direction: column; gap: 2px; }
  span { color: var(--hos-text-secondary, #6f655a); font-size: 12px; }
  strong { font-size: 18px; font-variant-numeric: tabular-nums; }
}
.qhw-complete-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 14px;
}
.qhw-detail-toggle {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  padding: 12px 14px;
  border: 1px solid var(--hos-border-light, #e8e1d8);
  border-radius: 10px;
  background: var(--hos-bg-primary, #fffdf9);
  color: var(--hos-text-primary, #2f2a24);
  font-size: 14px;
  cursor: pointer;
  small { color: var(--hos-text-secondary, #6f655a); }
}
.qhw-detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px 18px;
  border: 1px solid var(--hos-border-light, #e8e1d8);
  border-radius: 12px;
  background: var(--hos-bg-primary, #fffdf9);
}
.qhw-group h3 {
  margin: 0 0 8px;
  font-size: 14px;
  small { color: var(--hos-text-secondary, #6f655a); font-weight: 400; }
}
.qhw-line {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
  border-top: 1px dashed var(--hos-border-light, #eee7de);
  font-size: 14px;
  small { color: var(--hos-text-secondary, #6f655a); }
  strong { font-variant-numeric: tabular-nums; white-space: nowrap; }
}
.qhw-on-demand {
  margin: 0;
  color: var(--hos-text-secondary, #6f655a);
  font-size: 13px;
}
.qhw-confirm {
  display: flex;
  flex-direction: column;
  gap: 10px;
  div { display: flex; justify-content: space-between; gap: 16px; font-size: 14px; }
  span { color: var(--hos-text-secondary, #6f655a); }
}
.qhw-confirm-note {
  margin: 14px 0 0;
  color: var(--hos-text-secondary, #6f655a);
  font-size: 12px;
}
.qhw-correction-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
@media (max-width: 1024px) {
  .qhw { padding: 12px 14px 24px; }
  .qhw-count strong { font-size: 32px; }
  .qhw-manual { gap: 10px; }
}
@media (prefers-reduced-motion: reduce) {
  .qhw * { transition: none !important; animation: none !important; }
}
</style>
