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

      <div v-if="view === 'evidence'" class="qhw-evidence">
        <PatientEvidencePanel :department-key="departmentKey" :date="businessDate" embedded />
      </div>

      <div v-else class="qhw-columns">
        <div class="qhw-main">
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

          <section class="qhw-tile qhw-hero">
            <div v-if="completed" class="qhw-hero-main qhw-hero-done">
              <div class="qhw-done-mark" aria-hidden="true">
                <span>✓</span>
              </div>
              <div>
                <small class="qhw-label">今日已确认</small>
                <div class="qhw-number-row">
                  <strong class="qhw-number">{{ headcountTotal }}</strong>
                  <span class="qhw-unit">人次</span>
                  <span class="qhw-source-pill">{{ headcountSourceLabel || "已提交" }}</span>
                </div>
                <div v-if="composition.length" class="qhw-composition">
                  <span v-for="part in composition" :key="part.label">
                    {{ part.label }} <b>{{ part.value }}</b>
                  </span>
                </div>
                <p class="qhw-system-note">系统已按院定定额完成计算，耗材数量不可修改。</p>
                <div class="qhw-breakdown" aria-label="今日定额构成">
                  <button type="button" @click="openDetail('receipt')">按人次 <b>{{ groupCounts.per }}</b> 项</button>
                  <button type="button" @click="openDetail('receipt')">每日固定 <b>{{ groupCounts.daily }}</b> 项</button>
                  <button type="button" @click="openDetail('receipt')">周期摊消 <b>{{ groupCounts.period }}</b> 项</button>
                  <button type="button" @click="openDetail('receipt')">按需 <b>{{ groupCounts.onDemand }}</b> 项</button>
                </div>
              </div>
              <div class="qhw-done-actions">
                <el-button text @click="openDetail()">查看明细</el-button>
                <el-button v-if="!locked" text @click="openCorrection">修正人数</el-button>
                <el-button v-if="!locked" type="primary" plain @click="openException">上报例外</el-button>
              </div>
            </div>

            <div v-else class="qhw-hero-main">
              <div>
                <small class="qhw-label">{{ entryMode === "DAILY" ? "今日服务人次" : "今日接诊人次" }}</small>
                <div class="qhw-number-row">
                  <strong class="qhw-number">{{ animatedHeadcount }}</strong>
                  <span class="qhw-unit">人</span>
                  <span v-if="headcountSourceLabel" class="qhw-source-pill">{{ headcountSourceLabel }}</span>
                  <el-button v-if="entryMode === 'PATIENT' && !locked" text type="primary" @click="openCorrection">修正</el-button>
                </div>
                <div v-if="composition.length || previousDelta" class="qhw-composition">
                  <span v-for="part in composition" :key="part.label">
                    {{ part.label }} <b>{{ part.value }}</b>
                  </span>
                  <span v-if="previousDelta" class="qhw-compare">
                    较 {{ previousDelta.date }} {{ previousDelta.previous }} 人 <b>{{ previousDelta.text }}</b>
                  </span>
                </div>
              </div>

              <div v-if="entryMode === 'DAILY'" class="qhw-manual">
                <label>门诊人次<el-input-number v-model="headcountForm.outpatient" :min="0" :precision="0" size="large" /></label>
                <label>住院人次<el-input-number v-model="headcountForm.inpatient" :min="0" :precision="0" size="large" /></label>
                <small v-if="prefillDate">已沿用 {{ prefillDate }} 的人数，可直接修改</small>
              </div>

              <p v-if="headcountTotal === 0" class="qhw-zero-hint">今日暂未统计到就诊，可修正后提交；固定日耗仍会计算。</p>
              <p class="qhw-system-note">系统已按院定定额计算 <strong>{{ autoItemCount }}</strong> 项耗材，确认后将扣减科室库存。</p>

              <div class="qhw-breakdown" aria-label="今日定额构成">
                <button type="button" @click="openDetail(completed ? 'receipt' : 'lines')">按人次 <b>{{ groupCounts.per }}</b> 项</button>
                <button type="button" @click="openDetail(completed ? 'receipt' : 'lines')">每日固定 <b>{{ groupCounts.daily }}</b> 项</button>
                <button type="button" @click="openDetail(completed ? 'receipt' : 'lines')">周期摊消 <b>{{ groupCounts.period }}</b> 项</button>
                <button type="button" @click="openDetail(completed ? 'receipt' : 'lines')">按需 <b>{{ groupCounts.onDemand }}</b> 项</button>
              </div>

              <el-button class="qhw-cta" type="primary" size="large" :disabled="locked || submitting" :loading="submitting" @click="openConfirm">
                确认人数并提交
              </el-button>
              <p class="qhw-foot-hint">提交后可修正人数；耗材数量不可修改。提交后可上报超量/未列耗材。</p>
            </div>
          </section>

          <QuotaTodayReceiptCard
            :report="draft"
            :groups="receiptGroups"
            :counts="receiptSummary"
            :business-date="businessDate"
            :today="today"
            :can-export="canExport"
            :just-submitted="justSubmitted"
            @open-detail="openReceiptGroup"
            @open-exception="openException"
            @export="exportReceipt"
          />

          <div class="qhw-glance">
            <section class="qhw-tile qhw-status" :class="`is-${statusTone}`">
              <div class="qhw-tile-head">
                <span>今日状态</span>
                <i class="qhw-status-dot" aria-hidden="true" />
              </div>
              <strong class="qhw-status-text">{{ statusText }}</strong>
              <div class="qhw-timeline">
                <div class="qhw-timeline-row">
                  <i class="is-saved" aria-hidden="true" />
                  <span>自动保存</span>
                  <small>{{ savedAt || "修改人数后自动保存" }}</small>
                </div>
                <div class="qhw-timeline-row">
                  <i class="is-submit" aria-hidden="true" />
                  <span>提交</span>
                  <small>{{ submittedAtLabel }}</small>
                </div>
                <div class="qhw-timeline-row">
                  <i :class="`is-${statusTone}`" aria-hidden="true" />
                  <span>状态</span>
                  <small>{{ statusHint }}</small>
                </div>
                <button
                  v-if="draft?.pendingExceptions"
                  type="button"
                  class="qhw-exception-link"
                  @click="openDetail('exceptions')"
                >
                  {{ draft.pendingExceptions }} 条例外待审核
                </button>
              </div>
              <div v-if="prefillDate" class="qhw-mini-row">沿用 {{ prefillDate }} 人数</div>
            </section>

            <section class="qhw-tile qhw-quota" :class="{ 'has-shortage': shortageCount > 0 }">
              <div class="qhw-tile-head">
                <span>预计耗材</span>
                <el-button text type="primary" @click="openDetail()">查看明细 →</el-button>
              </div>
              <div class="qhw-quota-number">
                <strong>{{ autoItemCount }}</strong>
                <span>项自动扣库</span>
              </div>
              <div class="qhw-quota-sub">
                <span>{{ onDemandCount }} 项按需</span>
                <span>{{ draft?.pendingExceptions || 0 }} 条待审例外</span>
              </div>
              <div v-if="shortageCount" class="qhw-shortage">
                {{ shortageCount }} 项因库存不足未扣减，已生成库管待办
              </div>
            </section>
          </div>
        </div>

        <aside class="qhw-aside">
          <QuotaHistoryCalendar
            ref="historyRef"
            :days="calendarDays"
            :month="calendarMonth"
            :today="today"
            :expected-from="calendarExpectedFrom"
            :loading="calendarLoading"
            :department-key="departmentKey"
            :department-name="departmentName"
            :selected-date="businessDate"
            @update:month="changeMonth"
            @select-date="selectDate"
            @report-exception="openExceptionFromHistory"
          />
        </aside>
      </div>

      <el-drawer v-model="detailVisible" :title="completed ? '今日扣减回执' : '今日应耗明细'" size="560px">
        <div class="qhw-drawer-body">
          <div class="qhw-drawer-summary">
            <span>应扣 <b>{{ autoItemCount }}</b> 项</span>
            <span>实扣 <b>{{ receiptSummary.deducted }}</b> 项</span>
            <span v-if="receiptSummary.shortage">未足额 <b>{{ receiptSummary.shortage }}</b> 项</span>
            <span>按需 <b>{{ receiptSummary.onDemand }}</b> 项</span>
          </div>
          <el-tabs v-model="detailTab" class="qhw-detail-tabs">
            <el-tab-pane label="应耗明细" name="lines">
              <div v-for="group in detailGroups" :key="group.key" class="qhw-group">
                <div class="qhw-group-head">
                  <span>{{ group.label }}</span>
                  <small>{{ group.lines.length }} 项</small>
                </div>
                <div v-for="line in group.lines" :key="line.lineId || line.quotaRuleId" class="qhw-line">
                  <span>
                    {{ line.itemName }}
                    <small v-if="line.sourceText"> · {{ line.sourceText }}</small>
                  </span>
                  <strong>{{ formatQuantity(line.quotaQuantity) }} {{ line.unit }}</strong>
                </div>
              </div>
              <p v-if="onDemandCount" class="qhw-on-demand">
                另有 {{ onDemandCount }} 项按需/未纳入耗材，不计入自动扣库；实际使用请提交后上报例外。
              </p>
              <el-empty v-if="!autoItemCount && !onDemandCount" :image-size="60" description="今日没有需要计算的耗材" />
            </el-tab-pane>

            <el-tab-pane label="扣减回执" name="receipt">
              <div v-for="group in detailGroups" :key="group.key" class="qhw-group">
                <div class="qhw-group-head">
                  <span>{{ group.label }}</span>
                  <small>{{ group.lines.length }} 项</small>
                </div>
                <div v-for="line in group.lines" :key="line.lineId || line.quotaRuleId" class="qhw-line qhw-line-receipt">
                  <span>
                    {{ line.itemName }}
                    <small v-if="line.sourceText"> · {{ line.sourceText }}</small>
                  </span>
                  <span class="qhw-line-value">
                    <span>
                      应扣 {{ formatQuantity(line.quotaQuantity) }}
                      <template v-if="completed"> → 实扣 {{ formatQuantity(line.quotaDeductedQuantity) }}</template>
                      {{ line.unit }}
                    </span>
                    <span class="qhw-line-status" :class="`is-${deductionStatus(line).tone}`">
                      {{ deductionStatus(line).label }}
                    </span>
                  </span>
                </div>
              </div>
              <p v-if="onDemandCount" class="qhw-on-demand">
                另有 {{ onDemandCount }} 项按需耗材不参与自动扣库，如需补扣请上报例外。
              </p>
              <el-empty v-if="!autoItemCount" :image-size="60" description="今日没有自动扣减项" />
            </el-tab-pane>

            <el-tab-pane label="例外记录" name="exceptions">
              <div v-for="line in exceptionLines" :key="line.lineId || line.quotaRuleId" class="qhw-line qhw-line-exception">
                <span>
                  {{ line.itemName }}
                  <small v-if="line.sourceText"> · {{ line.sourceText }}</small>
                </span>
                <span class="qhw-line-value">
                  <span>
                    应扣 {{ formatQuantity(line.quotaQuantity) }} · 上报 {{ formatQuantity(line.actualQuantity) }}
                    <template v-if="Number(line.exceptionApprovedQuantity || 0)"> · 通过 {{ formatQuantity(line.exceptionApprovedQuantity) }}</template>
                    {{ line.unit }}
                  </span>
                  <span class="qhw-line-status" :class="`is-${exceptionStatusTone(line.exceptionStatus)}`">
                    {{ exceptionStatusLabel(line.exceptionStatus) }}
                  </span>
                </span>
              </div>
              <el-empty v-if="!exceptionLines.length" :image-size="60" description="暂无例外记录" />
            </el-tab-pane>
          </el-tabs>
        </div>
      </el-drawer>

      <el-drawer v-model="exceptionVisible" title="上报定额例外" size="560px" destroy-on-close>
        <div class="qhw-drawer-body">
          <el-alert type="warning" :closable="false" show-icon title="科室只能上报事实，最终补扣量由管理员审核确定。" style="margin-bottom: 16px" />
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
        </div>
        <template #footer>
          <div class="qhw-drawer-footer">
            <el-button @click="exceptionVisible = false">取消</el-button>
            <el-button type="primary" :loading="exceptionSubmitting" @click="submitException">上报例外</el-button>
          </div>
        </template>
      </el-drawer>

      <el-dialog v-model="confirmVisible" title="确认提交今日人数？" width="440px" class="qhw-modal">
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

      <el-dialog v-model="correctionVisible" title="修正今日人数" width="460px" class="qhw-modal">
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
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import dayjs from "dayjs";
import DepartmentDailyReportPanel from "../DepartmentDailyReportPanel.vue";
import PatientConsumptionWorkspace from "../PatientConsumptionWorkspace.vue";
import PatientEvidencePanel from "../PatientEvidencePanel.vue";
import QuotaTodayReceiptCard from "./QuotaTodayReceiptCard.vue";
import QuotaHistoryCalendar from "./QuotaHistoryCalendar.vue";
import {
  deductionStatus,
  exceptionStatusLabel,
  exceptionStatusTone,
  groupReceiptLines,
  receiptCounts
} from "./quotaReceipt";
import {
  correctInventoryQuotaHeadcountApi,
  downloadInventoryDepartmentDailyReportApi,
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

type DetailTab = "lines" | "receipt" | "exceptions";

const props = withDefaults(
  defineProps<{ departmentKey: string; departmentName?: string; today?: string; canExport?: boolean }>(),
  { canExport: false }
);
const today = props.today || dayjs().format("YYYY-MM-DD");
const businessDate = ref(today);
const loading = ref(false);
const saving = ref(false);
const submitting = ref(false);
const exceptionSubmitting = ref(false);
const draft = ref<InventoryDepartmentDailyReport>();
const legacyMode = computed(() => Boolean(draft.value) && draft.value?.reportMode !== "QUOTA");
const entryMode = computed<"PATIENT" | "DAILY">(() => (draft.value?.entryMode === "DAILY" ? "DAILY" : "PATIENT"));
const view = ref<"entry" | "evidence">("entry");
const detailVisible = ref(false);
const detailTab = ref<DetailTab>("lines");
const confirmVisible = ref(false);
const correctionVisible = ref(false);
const exceptionVisible = ref(false);
const justSubmitted = ref(false);
const historyRef = ref<InstanceType<typeof QuotaHistoryCalendar>>();
const savedAt = ref("");
const headcountReason = ref("");
const prefillDate = ref("");
const animatedHeadcount = ref(0);
const headcountForm = reactive({ outpatient: 0, inpatient: 0 });
let autosaveTimer: ReturnType<typeof setTimeout> | undefined;
let countFrame: number | undefined;
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
const onDemandCount = computed(() => allLines.value.filter(line => line.exceptionOnly).length);
const deductedCount = computed(() => completed.value ? autoLines.value.filter(line => Number(line.quotaDeductedQuantity || 0) >= Number(line.quotaQuantity || 0)).length : 0);
const shortageCount = computed(() => completed.value ? autoLines.value.filter(line => Number(line.quotaDeductedQuantity || 0) < Number(line.quotaQuantity || 0)).length : 0);
const completed = computed(() => Boolean(draft.value?.id) && draft.value?.status !== "DRAFT");
const locked = computed(() => Boolean(draft.value?.locked));
const statusText = computed(() => (locked.value ? "今日已锁定" : completed.value ? "今日已确认" : "待提交"));
const statusTone = computed(() => (locked.value ? "locked" : completed.value ? "success" : "pending"));
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

const receiptGroups = computed(() => groupReceiptLines(allLines.value));
const receiptSummary = computed(() => receiptCounts(draft.value));
const groupCounts = computed(() => {
  const counts = { per: 0, daily: 0, period: 0, onDemand: onDemandCount.value };
  for (const group of receiptGroups.value) counts[group.key] = group.lines.length;
  return counts;
});
const composition = computed(() => {
  const source =
    draft.value?.status === "DRAFT" && entryMode.value === "DAILY"
      ? { outpatient: headcountForm.outpatient, inpatient: headcountForm.inpatient }
      : draft.value?.headcount;
  if (!source) return [] as Array<{ label: string; value: number }>;
  const parts: Array<{ label: string; value: number }> = [];
  if (Number(source.outpatient || 0) > 0) parts.push({ label: "门诊", value: Number(source.outpatient) });
  if (Number(source.inpatient || 0) > 0) parts.push({ label: "住院", value: Number(source.inpatient) });
  if (Number((source as { manual?: number }).manual || 0) > 0) {
    parts.push({ label: "手工", value: Number((source as { manual?: number }).manual) });
  }
  return parts;
});
const previousDelta = computed(() => {
  if (entryMode.value !== "DAILY" || completed.value) return null;
  const previous = Number(draft.value?.previousHeadcount?.total || 0);
  if (!previous) return null;
  const delta = headcountTotal.value - previous;
  return {
    date: draft.value?.previousHeadcountDate
      ? dayjs(draft.value.previousHeadcountDate).format("MM-DD")
      : "上次",
    previous,
    text: delta === 0 ? "持平" : delta > 0 ? `+${delta}` : `${delta}`
  };
});
const submittedAtLabel = computed(() => {
  const value = draft.value?.closure?.submittedAt;
  return value ? dayjs(value).format("M月D日 HH:mm") : "未提交";
});
const statusHint = computed(() =>
  locked.value
    ? "已锁定"
    : completed.value
      ? shortageCount.value
        ? `${shortageCount.value} 项未足额扣减`
        : "耗材已按定额扣减"
      : "确认人数后生成回执"
);
const exceptionLines = computed(() =>
  allLines.value.filter(line => line.exceptionStatus && line.exceptionStatus !== "NONE")
);

const animateHeadcount = (target: number) => {
  if (window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) {
    animatedHeadcount.value = target;
    return;
  }
  if (countFrame) cancelAnimationFrame(countFrame);
  const from = animatedHeadcount.value;
  const start = performance.now();
  const duration = 480;
  const tick = (now: number) => {
    const progress = Math.min(1, (now - start) / duration);
    const eased = 1 - Math.pow(1 - progress, 3);
    animatedHeadcount.value = Math.round(from + (target - from) * eased);
    if (progress < 1) countFrame = requestAnimationFrame(tick);
  };
  countFrame = requestAnimationFrame(tick);
};
watch(headcountTotal, value => animateHeadcount(value), { immediate: true });

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
  detailVisible.value = false;
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
    historyRef.value?.invalidate(businessDate.value);
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
    justSubmitted.value = true;
    window.setTimeout(() => (justSubmitted.value = false), 480);
    historyRef.value?.invalidate(businessDate.value);
    void loadCalendar();
    ElMessage.success("已提交，定额部分已扣库");
  } catch (error: any) {
    ElMessage.error(error?.message || "提交失败");
  } finally {
    submitting.value = false;
  }
};

const openDetail = (tab?: DetailTab) => {
  detailTab.value = tab || (completed.value ? "receipt" : "lines");
  detailVisible.value = true;
};

const openReceiptGroup = () => {
  openDetail(completed.value ? "receipt" : "lines");
};

const exportReceipt = async () => {
  if (!props.canExport || !draft.value?.id) return;
  try {
    await downloadInventoryDepartmentDailyReportApi(draft.value.id, "xlsx");
  } catch (error: any) {
    ElMessage.error(error?.message || "回执导出失败");
  }
};

const openExceptionFromHistory = async (date: string) => {
  if (date !== today) return;
  if (businessDate.value !== today) {
    businessDate.value = today;
    await load();
  }
  openException();
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
    historyRef.value?.invalidate(businessDate.value);
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
  if (countFrame) cancelAnimationFrame(countFrame);
});
</script>

<style scoped lang="scss">
.qhw {
  --qhw-bg: #f4eee5;
  --qhw-surface: #fffdf8;
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
  --qhw-shadow: 0 18px 45px rgba(72, 55, 36, 0.08);
  --qhw-shadow-soft: 0 8px 24px rgba(72, 55, 36, 0.05);
  --qhw-radius: 20px;

  display: flex;
  flex-direction: column;
  gap: 18px;
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  max-width: 1440px;
  margin: 0 auto;
  padding: 24px 28px 36px;
  color: var(--qhw-ink);
  background: transparent;
}
.qhw-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
}
.qhw-head-copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
  h1 {
    margin: 0;
    font-size: 20px;
    font-weight: 600;
    letter-spacing: -0.01em;
  }
}
.qhw-eyebrow {
  color: var(--qhw-muted);
  font-size: 13px;
}
.qhw-head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.qhw-week {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 4px;
  scrollbar-width: none;
  &::-webkit-scrollbar { display: none; }
}
.qhw-day {
  position: relative;
  flex: 0 0 auto;
  min-width: 66px;
  padding: 9px 11px;
  border: 1px solid var(--qhw-line);
  border-radius: 12px;
  background: var(--qhw-surface);
  color: var(--qhw-muted);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
  transition: border-color 160ms ease, background 160ms ease, color 160ms ease, transform 160ms ease;
  span { font-size: 11px; }
  strong { font-size: 14px; font-variant-numeric: tabular-nums; }
  &:hover { transform: translateY(-1px); border-color: #d7c8b7; }
  &:focus-visible { outline: 2px solid var(--qhw-accent); outline-offset: 2px; }
  &.is-active {
    border-color: var(--qhw-accent);
    background: var(--qhw-accent);
    color: #fffdf8;
    box-shadow: 0 6px 16px rgba(47, 107, 88, 0.18);
  }
}
.qhw-day-dot {
  position: absolute;
  right: 8px;
  top: 8px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--qhw-warm);
  &.is-APPROVED { background: var(--qhw-success); }
  &.is-SUBMITTED { background: #5b8db8; }
  &.is-LOCKED { background: var(--qhw-muted); }
  .is-active & { background: #f7e7cf; }
}
.qhw-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}
.qhw-main {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}
.qhw-aside {
  position: sticky;
  top: 16px;
  align-self: start;
  min-width: 0;
}
.qhw-glance {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 18px;
}
.qhw-evidence {
  min-width: 0;
}
.qhw-tile {
  position: relative;
  box-sizing: border-box;
  border: 1px solid var(--qhw-line);
  border-radius: var(--qhw-radius);
  background: var(--qhw-surface);
  box-shadow: var(--qhw-shadow-soft);
  animation: qhw-rise 240ms cubic-bezier(.22,.8,.3,1) both;
}
.qhw-hero {
  min-height: 300px;
  padding: 28px 30px;
  overflow: hidden;
  &::before {
    content: "";
    position: absolute;
    inset: 0 auto 0 0;
    width: 4px;
    background: var(--qhw-accent);
  }
}
.qhw-glance .qhw-tile:nth-child(1) { animation-delay: 60ms; }
.qhw-glance .qhw-tile:nth-child(2) { animation-delay: 120ms; }
.qhw-hero-main {
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
}
.qhw-label {
  color: var(--qhw-muted);
  font-size: 13px;
}
.qhw-number-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-top: 2px;
  flex-wrap: wrap;
}
.qhw-number {
  font-size: 56px;
  line-height: 1;
  font-weight: 700;
  letter-spacing: -0.04em;
  font-variant-numeric: tabular-nums;
}
.qhw-unit {
  color: var(--qhw-muted);
  font-size: 16px;
}
.qhw-source-pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--qhw-accent-soft);
  color: var(--qhw-accent);
  font-size: 12px;
}
.qhw-manual {
  display: flex;
  align-items: flex-end;
  gap: 18px;
  flex-wrap: wrap;
  label {
    display: flex;
    flex-direction: column;
    gap: 5px;
    color: var(--qhw-muted);
    font-size: 13px;
  }
  small { color: var(--qhw-muted); font-size: 12px; }
}
.qhw-system-note {
  margin: 4px 0 0;
  color: var(--qhw-muted);
  font-size: 14px;
  line-height: 1.65;
  strong { color: var(--qhw-ink); font-variant-numeric: tabular-nums; }
}
.qhw-composition {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  margin-top: 8px;
  color: var(--qhw-muted);
  font-size: 12px;

  b {
    color: var(--qhw-ink);
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
}
.qhw-compare {
  padding-left: 14px;
  border-left: 1px solid var(--qhw-line);

  b {
    margin-left: 2px;
    color: var(--qhw-warm);
  }
}
.qhw-breakdown {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;

  button {
    padding: 5px 10px;
    font: inherit;
    font-size: 12px;
    color: var(--qhw-muted);
    cursor: pointer;
    background: var(--qhw-surface-2);
    border: 1px solid var(--qhw-line);
    border-radius: 999px;
    transition: border-color 160ms ease, color 160ms ease;

    b {
      color: var(--qhw-ink);
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }

    &:hover {
      color: var(--qhw-accent);
      border-color: #c8d8cf;
    }

    &:focus-visible {
      outline: 2px solid var(--qhw-accent);
      outline-offset: 2px;
    }
  }
}
.qhw-zero-hint {
  margin: 0;
  padding: 9px 11px;
  border-radius: 9px;
  background: var(--qhw-warm-soft);
  color: #8a5a13;
  font-size: 13px;
}
.qhw-cta {
  width: 100%;
  height: 46px;
  margin-top: auto;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.01em;
  transition: transform 140ms ease, box-shadow 140ms ease;
  &:hover { transform: translateY(-1px); box-shadow: 0 8px 18px rgba(47, 107, 88, 0.2); }
  &:active { transform: scale(0.99); }
}
.qhw-foot-hint {
  margin: 0;
  color: var(--qhw-muted);
  font-size: 12px;
  text-align: center;
}
.qhw-tile-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: var(--qhw-muted);
  font-size: 13px;
}
.qhw-status,
.qhw-quota {
  padding: 20px 22px;
  transition: background 240ms ease, border-color 240ms ease;
}
.qhw-status {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 132px;
}
.qhw-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--qhw-warm);
}
.qhw-status-text {
  font-size: 20px;
  font-weight: 600;
}
.qhw-tile-note {
  color: var(--qhw-muted);
  font-size: 12px;
}
.qhw-timeline {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-top: 4px;
}
.qhw-timeline-row {
  display: grid;
  grid-template-columns: 7px 54px minmax(0, 1fr);
  gap: 7px;
  align-items: center;
  color: var(--qhw-muted);
  font-size: 12px;

  > span {
    white-space: nowrap;
  }

  i {
    width: 6px;
    height: 6px;
    background: var(--qhw-muted);
    border-radius: 50%;

    &.is-saved { background: var(--qhw-accent); }
    &.is-submit { background: #5b8db8; }
    &.is-success { background: var(--qhw-success); }
    &.is-locked { background: var(--qhw-muted); }
    &.is-pending { background: var(--qhw-warm); }
  }

  small {
    overflow: hidden;
    color: var(--qhw-muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
.qhw-exception-link {
  align-self: flex-start;
  padding: 0;
  font: inherit;
  font-size: 11px;
  color: var(--qhw-warm);
  text-decoration: underline;
  text-underline-offset: 2px;
  cursor: pointer;
  background: none;
  border: 0;

  &:focus-visible {
    outline: 2px solid var(--qhw-accent);
    outline-offset: 2px;
  }
}
.qhw-mini-row {
  margin-top: 2px;
  color: var(--qhw-muted);
  font-size: 12px;
}
.qhw-status.is-success {
  background: var(--qhw-success-soft);
  border-color: #cfe3d6;
  .qhw-status-dot { background: var(--qhw-success); }
  .qhw-status-text { color: var(--qhw-success); }
}
.qhw-status.is-locked {
  background: var(--qhw-surface-2);
  .qhw-status-dot { background: var(--qhw-muted); }
}
.qhw-quota {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.qhw-quota-number {
  display: flex;
  align-items: baseline;
  gap: 7px;
  strong { font-size: 34px; line-height: 1; font-variant-numeric: tabular-nums; }
  span { color: var(--qhw-muted); font-size: 13px; }
}
.qhw-quota-sub {
  display: flex;
  gap: 14px;
  color: var(--qhw-muted);
  font-size: 12px;
  flex-wrap: wrap;
}
.qhw-shortage {
  margin-top: 4px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--qhw-warm-soft);
  color: #8a5a13;
  font-size: 12px;
}
.qhw-hero-done {
  gap: 16px;
  justify-content: center;
  background: var(--qhw-success-soft);
  border-radius: 14px;
}
.qhw-done-mark {
  width: 54px;
  height: 54px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--qhw-success);
  color: #fff;
  font-size: 26px;
  font-weight: 700;
  animation: qhw-check 320ms cubic-bezier(.22,.8,.3,1) both;
}
.qhw-done-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.qhw-drawer-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  padding: 10px 12px;
  margin-bottom: 10px;
  color: var(--qhw-muted);
  font-size: 12px;
  background: var(--qhw-surface-2);
  border: 1px solid var(--qhw-line);
  border-radius: 10px;

  b {
    color: var(--qhw-ink);
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
}
.qhw-detail-tabs {
  :deep(.el-tabs__header) {
    margin-bottom: 12px;
  }
}
.qhw-line-receipt,
.qhw-line-exception {
  align-items: flex-start;

  > span:first-child {
    min-width: 0;
  }
}
.qhw-line-value {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  gap: 3px;
  align-items: flex-end;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.qhw-line-status {
  padding: 1px 7px;
  font-size: 10px;
  border: 1px solid transparent;
  border-radius: 999px;

  &.is-success {
    color: var(--qhw-success);
    background: var(--qhw-success-soft);
  }

  &.is-warning {
    color: #8a5a13;
    background: var(--qhw-warm-soft);
  }

  &.is-danger {
    color: #a33b3b;
    background: #fbefef;
  }

  &.is-info {
    color: #4f6f8f;
    background: #eef4fa;
  }

  &.is-muted {
    color: var(--qhw-muted);
    background: var(--qhw-surface-2);
  }
}
.qhw-drawer-body {
  padding: 4px 4px 12px;
}
.qhw-group + .qhw-group {
  margin-top: 18px;
}
.qhw-group-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 6px;
  font-size: 14px;
  font-weight: 600;
  small { color: var(--qhw-muted); font-weight: 400; }
}
.qhw-line {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  padding: 9px 2px;
  border-top: 1px dashed var(--qhw-line);
  font-size: 14px;
  small { color: var(--qhw-muted); }
  strong { font-variant-numeric: tabular-nums; white-space: nowrap; }
}
.qhw-on-demand {
  margin: 18px 0 0;
  color: var(--qhw-muted);
  font-size: 13px;
  line-height: 1.6;
}
.qhw-drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.qhw-confirm {
  display: flex;
  flex-direction: column;
  gap: 10px;
  div { display: flex; justify-content: space-between; gap: 16px; font-size: 14px; }
  span { color: var(--qhw-muted); }
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
@keyframes qhw-rise {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
@keyframes qhw-check {
  0% { opacity: 0; transform: scale(.6); }
  60% { opacity: 1; transform: scale(1.08); }
  100% { opacity: 1; transform: scale(1); }
}
@media (max-width: 1100px) {
  .qhw-columns { grid-template-columns: 1fr; }
  .qhw-aside { position: static; }
  .qhw-hero { min-height: 320px; }
}
@media (max-width: 760px) {
  .qhw { padding: 16px 16px 28px; }
  .qhw-glance { grid-template-columns: 1fr; }
  .qhw-number { font-size: 44px; }
}
@media (prefers-reduced-motion: reduce) {
  .qhw *, .qhw *::before, .qhw *::after {
    animation: none !important;
    transition: none !important;
  }
}
</style>