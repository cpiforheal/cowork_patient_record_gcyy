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
                  v-if="fixedUsagePilot && !completed && !locked"
                  plain
                  type="primary"
                  size="small"
                  @click="openFixedUsage"
                >
                  确认固定耗材<template v-if="fixedUsageSummary"> · {{ fixedUsageSummary }}</template>
                </el-button>
                <el-button v-if="canReportException" plain type="warning" size="small" @click="openExceptionFor()">
                  上报例外
                </el-button>
                <el-button v-if="completed" text type="primary" size="small" @click="openTodayReceipt">
                  查看今日回执
                </el-button>
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
                :row-class-name="rowClassName"
                height="calc(100vh - 340px)"
                class="qhw-table"
                empty-text="今日没有应扣耗材"
              >
                <el-table-column label="耗材" min-width="200" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span class="qhw-item-cell">
                      <span class="qhw-item-name">{{ row.itemName }}</span>
                      <span class="qhw-billing-tag" :class="`is-${billingOf(row).key}`">
                        {{ billingOf(row).label }}
                      </span>
                      <span
                        v-if="showUsageTag(row)"
                        class="qhw-usage-tag"
                        :class="`is-${usageTone(row)}`"
                      >
                        {{ usageLabel(row) }}
                      </span>
                    </span>
                  </template>
                </el-table-column>
                <el-table-column prop="unit" label="单位" width="70" />
                <el-table-column label="原用量" width="120" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.sourceText || "—" }}</template>
                </el-table-column>
                <el-table-column width="132" align="right">
                  <template #header>
                    <span class="qhw-quota-head" title="按当前人数实时折算，提交后锁定">
                      应耗（定额）
                      <span v-if="!completed" class="qhw-live-pill">实时</span>
                    </span>
                  </template>
                  <template #default="{ row }">
                    <span
                      :key="liveQuotaOf(row)"
                      class="qhw-quota-value"
                      :class="{ 'is-live': !completed, 'is-zero': !liveQuotaOf(row) }"
                      :title="!completed ? '按当前人数实时折算，提交后锁定' : ''"
                    >
                      {{ formatQuantity(liveQuotaOf(row)) }}
                    </span>
                    <small v-if="approxCountOf(row)" class="qhw-count-approx">
                      ≈ {{ approxCountOf(row) }} {{ row.countUnit }}
                    </small>
                  </template>
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

      <QuotaFixedUsageDrawer
        v-if="fixedUsagePilot"
        v-model="fixedUsageVisible"
        :report-id="draft?.id || ''"
        :department-name="departmentName"
        :business-date="businessDate"
        :lines="allLines"
        @saved="onFixedUsageSaved"
      />

      <el-dialog v-model="confirmVisible" title="确认提交今日人数？" width="780px" style="max-width: 92vw" class="qhw-modal qhw-confirm-dialog">
        <div class="qhw-confirm-summary">
          <div>
            <span>接诊人次</span>
            <strong>{{ headcountTotal }} 人</strong>
            <small v-if="confirmComposition">{{ confirmComposition }}</small>
          </div>
          <div>
            <span>应扣耗材</span>
            <strong>{{ autoItemCount }} 项</strong>
            <small>提交后扣减库存</small>
          </div>
          <div>
            <span>按需 / 未纳入</span>
            <strong>{{ onDemandLines.length }} 项</strong>
            <small>不参与自动扣库</small>
          </div>
        </div>

        <div class="qhw-confirm-columns">
          <section class="qhw-confirm-col">
            <header>
              <span class="qhw-billing-tag is-per">按人次</span>
              <strong>按人次消耗</strong>
              <small>{{ confirmPerLines.length }} 项</small>
            </header>
            <p class="qhw-confirm-sub">随门诊、住院人数变化</p>
            <div v-for="line in confirmPerLines" :key="line.lineId || line.quotaRuleId" class="qhw-confirm-item">
              <span>
                {{ line.itemName }}
                <small v-if="line.sourceText">· {{ line.sourceText }}</small>
              </span>
              <strong>{{ formatQuantity(liveQuotaOf(line)) }} {{ line.unit }}</strong>
            </div>
            <p v-if="!confirmPerLines.length" class="qhw-confirm-empty">无</p>
          </section>

          <section class="qhw-confirm-col">
            <header>
              <span class="qhw-billing-tag is-fixed">固定/周期</span>
              <strong>固定与周期消耗</strong>
              <small>{{ confirmDailyLines.length + confirmPeriodLines.length }} 项</small>
            </header>
            <p class="qhw-confirm-sub">不随人数变化</p>
            <div v-if="confirmDailyLines.length" class="qhw-confirm-subhead">每日固定</div>
            <div v-for="line in confirmDailyLines" :key="line.lineId || line.quotaRuleId" class="qhw-confirm-item">
              <span class="qhw-confirm-name">
                {{ line.itemName }}
                <small v-if="line.sourceText">· {{ line.sourceText }}</small>
                <small v-if="usageDetail(line)" class="qhw-confirm-usage">{{ usageDetail(line) }}</small>
              </span>
              <strong>{{ formatQuantity(confirmQuantityOf(line)) }} {{ line.unit }}</strong>
            </div>
            <div v-if="confirmPeriodLines.length" class="qhw-confirm-subhead">周期摊消</div>
            <div v-for="line in confirmPeriodLines" :key="line.lineId || line.quotaRuleId" class="qhw-confirm-item">
              <span class="qhw-confirm-name">
                {{ line.itemName }}
                <small v-if="line.sourceText">· {{ line.sourceText }}</small>
                <small v-if="usageDetail(line)" class="qhw-confirm-usage">{{ usageDetail(line) }}</small>
              </span>
              <strong>{{ formatQuantity(confirmQuantityOf(line)) }} {{ line.unit }}</strong>
            </div>
            <p v-if="!confirmDailyLines.length && !confirmPeriodLines.length" class="qhw-confirm-empty">无</p>
          </section>
        </div>

        <div v-if="onDemandLines.length" class="qhw-confirm-ondemand">
          <header>
            <span class="qhw-billing-tag is-ondemand">按需</span>
            <strong>按需 / 未纳入 {{ onDemandLines.length }} 项</strong>
            <small>不参与自动扣库，实际使用请提交后上报例外</small>
          </header>
          <div v-for="line in onDemandLines" :key="line.lineId || line.quotaRuleId" class="qhw-confirm-item">
            <span>
              {{ line.itemName }}
              <small v-if="line.sourceText">· {{ line.sourceText }}</small>
            </span>
          </div>
        </div>

        <p class="qhw-confirm-note">提交后人数仍可修正，耗材数量不可修改；提交后可在“查看今日回执”查看完整回顾。</p>
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

      <el-dialog v-model="exceptionVisible" title="上报定额例外" width="580px" destroy-on-close>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="科室只能上报事实，最终补扣量由管理员审核确定。"
          style="margin-bottom: 16px"
        />
        <el-form label-position="top">
          <el-form-item label="耗材" required>
            <el-select
              v-model="exceptionLineId"
              filterable
              placeholder="搜索当日耗材或按需/未纳入项"
              style="width: 100%"
            >
              <el-option
                v-for="line in exceptionOptions"
                :key="line.lineId || line.quotaRuleId || line.itemId"
                :label="`${line.itemName}${line.sourceText ? ` · ${line.sourceText}` : ''} · 定额 ${formatQuotaExact(line.quotaQuantity)} ${line.unit}`"
                :value="line.lineId || ''"
              />
            </el-select>
          </el-form-item>
          <template v-if="exceptionLine">
            <div class="qhw-exception-target">
              <div>
                <strong>{{ exceptionLine.itemName }}</strong>
                <span v-if="exceptionLine.sourceText">· {{ exceptionLine.sourceText }}</span>
              </div>
              <span>每日定额 {{ formatQuotaExact(exceptionLine.quotaQuantity) }} {{ exceptionLine.unit }}</span>
            </div>
            <el-form-item :label="`多用了多少（${exceptionLine.unit}）`" required>
              <el-input-number
                v-model="exceptionExtraQuantity"
                :min="1"
                :step="1"
                :precision="0"
                controls-position="right"
              />
              <span class="qhw-exception-hint">填实物整数，系统自动折算成上报总量</span>
            </el-form-item>
            <div class="qhw-exception-calc">
              <span>定额 <b>{{ formatQuotaExact(exceptionQuota) }}</b> {{ exceptionLine.unit }}</span>
              <span>+</span>
              <span>多用 <b>{{ exceptionExtraQuantity }}</b> {{ exceptionLine.unit }}</span>
              <span>=</span>
              <span>上报总量 <b>{{ formatQuotaExact(exceptionReportedTotal) }}</b> {{ exceptionLine.unit }}</span>
            </div>
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
          </template>
        </el-form>
        <p v-if="!exceptionLine" class="qhw-exception-empty">请选择耗材后填写多用数量。</p>
        <template #footer>
          <div class="qhw-drawer-footer">
            <el-button @click="exceptionVisible = false">取消</el-button>
            <el-button type="primary" :loading="exceptionSubmitting" :disabled="!exceptionLine" @click="submitException">上报例外</el-button>
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
import QuotaFixedUsageDrawer from "./QuotaFixedUsageDrawer.vue";
import {
  billingCategory,
  deductionStatus,
  exceptionStatusLabel,
  exceptionStatusTone,
  formatReceiptQuantity,
  groupReceiptLines,
  liveQuotaQuantity,
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
const fixedUsageVisible = ref(false);
const dayDetailRef = ref<InstanceType<typeof QuotaDayDetailDrawer>>();
const fillCalendarRef = ref<InstanceType<typeof DepartmentFillCalendar>>();
const savedAt = ref("");
const headcountReason = ref("");
const prefillDate = ref("");
const headcountForm = reactive({ outpatient: 0, inpatient: 0 });
const exceptionLineId = ref("");
const exceptionExtraQuantity = ref(1);
const exceptionForm = reactive({ reason: "" });
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
const exceptionQuota = computed(() => Number(exceptionLine.value?.quotaQuantity || 0));
const exceptionReportedTotal = computed(() =>
  Math.round((exceptionQuota.value + Number(exceptionExtraQuantity.value || 0)) * 1000) / 1000
);
const headcountInputs = computed(() => {
  const outpatient = Number(headcountForm.outpatient || 0);
  const inpatient = Number(headcountForm.inpatient || 0);
  return { outpatient, inpatient, manual: 0, total: outpatient + inpatient };
});
const exceptionOptions = computed(() => allLines.value);
const confirmGroups = computed(() => groupReceiptLines(allLines.value));
const confirmPerLines = computed(() => confirmGroups.value.find(group => group.key === "per")?.lines || []);
const confirmDailyLines = computed(() => confirmGroups.value.find(group => group.key === "daily")?.lines || []);
const confirmPeriodLines = computed(() => confirmGroups.value.find(group => group.key === "period")?.lines || []);
const onDemandLines = computed(() => allLines.value.filter(line => line.exceptionOnly));
const fixedUsagePilot = computed(() => props.departmentKey === "tcm-pharmacy");
const fixedLines = computed(() =>
  allLines.value.filter(
    line =>
      !line.exceptionOnly &&
      line.includedInQuota !== false &&
      line.bindingType !== "PER_PERSON" &&
      Number(line.quotaQuantity || 0) > 0
  )
);
const fixedUsageSummary = computed(() => {
  if (!fixedUsagePilot.value) return "";
  const notUsed = fixedLines.value.filter(line => line.usageStatus === "NOT_USED").length;
  const short = fixedLines.value.filter(line => line.usageStatus === "SHORT_USE").length;
  const more = fixedLines.value.filter(line => line.usageStatus === "MORE_USE").length;
  const parts: string[] = [];
  if (notUsed) parts.push(`未使用${notUsed}`);
  if (short) parts.push(`少用${short}`);
  if (more) parts.push(`多用${more}`);
  return parts.join(" / ");
});
const confirmComposition = computed(() => {
  const source =
    draft.value?.status === "DRAFT"
      ? { outpatient: headcountForm.outpatient, inpatient: headcountForm.inpatient, manual: 0 }
      : draft.value?.headcount;
  if (!source) return "";
  const parts: string[] = [];
  if (Number(source.outpatient || 0)) parts.push(`门诊 ${source.outpatient}`);
  if (Number(source.inpatient || 0)) parts.push(`住院 ${source.inpatient}`);
  if (Number((source as { manual?: number }).manual || 0)) parts.push(`手工 ${(source as { manual?: number }).manual}`);
  return parts.join(" · ");
});
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
const formatQuotaExact = (value?: number | null) => Number(value || 0).toFixed(3);
const formatDate = (value: string) => dayjs(value).format("M月D日 dddd");
const rowKey = (row: any) => row.lineId || row.quotaRuleId || row.itemId;
const liveQuotaOf = (row: any) => {
  const line = row as InventoryDepartmentDailyReportLine;
  return completed.value ? Number(line.quotaQuantity || 0) : liveQuotaQuantity(line, headcountInputs.value);
};
const billingOf = (row: any) => billingCategory(row as InventoryDepartmentDailyReportLine);
const isFixedUsageLine = (line: InventoryDepartmentDailyReportLine) =>
  !line.exceptionOnly &&
  line.includedInQuota !== false &&
  line.bindingType !== "PER_PERSON" &&
  Number(line.quotaQuantity || 0) > 0;
const showUsageTag = (row: any) =>
  fixedUsagePilot.value && isFixedUsageLine(row as InventoryDepartmentDailyReportLine) && Boolean(row.usageStatus);
const usageLabel = (row: any) => {
  if (row.usageStatus === "NOT_USED") return "未使用";
  if (row.usageStatus === "SHORT_USE") return "少用";
  if (row.usageStatus === "MORE_USE") return "多用";
  return "按定额";
};
const usageTone = (row: any) => {
  if (row.usageStatus === "NOT_USED") return "not-used";
  if (row.usageStatus === "SHORT_USE") return "short";
  if (row.usageStatus === "MORE_USE") return "more";
  return "quota";
};
const usageDetail = (line: InventoryDepartmentDailyReportLine) => {
  if (!fixedUsagePilot.value || !isFixedUsageLine(line)) return "";
  if (line.usageStatus === "NOT_USED") return "今日未使用";
  if (line.usageStatus === "SHORT_USE" || line.usageStatus === "MORE_USE") {
    const reported = Number(line.reportedQuantity || 0);
    const confirmed = Number(line.confirmedQuantity || 0).toFixed(3);
    const prefix = line.usageStatus === "MORE_USE" ? "多用" : "少用";
    return `${prefix} ${reported} ${line.reportedUnit || line.unit} = ${confirmed} ${line.unit}`;
  }
  return "";
};
const confirmQuantityOf = (line: InventoryDepartmentDailyReportLine) => {
  if (fixedUsagePilot.value && isFixedUsageLine(line)) {
    if (line.usageStatus === "NOT_USED") return 0;
    if (line.usageStatus === "SHORT_USE" || line.usageStatus === "MORE_USE") return Number(line.confirmedQuantity || 0);
  }
  return liveQuotaOf(line);
};
const approxCountOf = (row: any) => {
  if (!fixedUsagePilot.value) return "";
  const line = row as InventoryDepartmentDailyReportLine;
  const factor = Number(line.countPerUnit || 0);
  if (!line.countUnit || factor <= 0) return "";
  return (Math.round(Number(liveQuotaOf(line)) * factor * 10) / 10).toFixed(1);
};
const rowClassName = ({ row }: { row: any }) => `is-${billingOf(row).key}`;
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
const openTodayReceipt = () => {
  calendarDate.value = businessDate.value;
  dayDetailVisible.value = true;
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

const openFixedUsage = async () => {
  if (!draft.value?.id) {
    const saved = await saveHeadcount();
    if (!saved?.id) return;
  }
  fixedUsageVisible.value = true;
};

const onFixedUsageSaved = (report: InventoryDepartmentDailyReport) => {
  applyDraft(report);
};

const submitReport = async () => {
  if (!draft.value?.id) return;
  submitting.value = true;
  try {
    if (draft.value.status === "DRAFT" && entryMode.value === "DAILY") {
      const saved = await saveHeadcount();
      if (!saved?.id) return;
    }
    if (fixedUsagePilot.value) {
      const invalid = allLines.value.find(
        line =>
          (line.usageStatus === "SHORT_USE" || line.usageStatus === "MORE_USE") &&
          (line.reportedQuantity == null || line.confirmedQuantity == null)
      );
      if (invalid) {
        ElMessage.warning(`${invalid.itemName} 已选择${invalid.usageStatus === "MORE_USE" ? "多用" : "少用"}，请先填写实际使用数量`);
        return;
      }
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

const openExceptionFor = (row?: any) => {
  if (row) {
    const line = row as InventoryDepartmentDailyReportLine;
    exceptionLineId.value = line.lineId || "";
  } else {
    exceptionLineId.value = "";
  }
  exceptionExtraQuantity.value = 1;
  exceptionForm.reason = "";
  exceptionFiles.value = [];
  exceptionVisible.value = true;
};

const submitException = async () => {
  const line = exceptionLine.value;
  if (!line) {
    ElMessage.warning("请选择耗材");
    return;
  }
  if (!exceptionForm.reason.trim()) {
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
      reportedActualQuantity: exceptionReportedTotal.value,
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
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: flex-end;
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

  :deep(.el-table__row.is-per td:first-child) {
    box-shadow: inset 3px 0 0 #9cc6b5;
  }

  :deep(.el-table__row.is-fixed td:first-child) {
    box-shadow: inset 3px 0 0 #d9a86c;
  }

  :deep(.el-table__row.is-ondemand td:first-child) {
    box-shadow: inset 3px 0 0 #cfc6b8;
  }
}

.qhw-item-cell {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
}

.qhw-item-name {
  min-width: 0;
  overflow: hidden;
  color: var(--qhw-ink);
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qhw-billing-tag {
  flex-shrink: 0;
  padding: 1px 6px;
  font-size: 9px;
  font-weight: 600;
  white-space: nowrap;
  border: 1px solid transparent;
  border-radius: 999px;

  &.is-per {
    color: var(--qhw-accent);
    background: var(--qhw-accent-soft);
    border-color: #d5e5dc;
  }

  &.is-fixed {
    color: #8a5a13;
    background: var(--qhw-warm-soft);
    border-color: #ebd8bd;
  }

  &.is-ondemand {
    color: var(--qhw-muted);
    background: var(--qhw-surface-2);
    border-color: var(--qhw-line);
  }
}

.qhw-usage-tag {
  flex-shrink: 0;
  padding: 1px 6px;
  font-size: 9px;
  font-weight: 600;
  white-space: nowrap;
  border: 1px solid transparent;
  border-radius: 999px;

  &.is-quota {
    color: var(--qhw-muted);
    background: var(--qhw-surface-2);
    border-color: var(--qhw-line);
  }

  &.is-not-used {
    color: #8a5a13;
    background: var(--qhw-warm-soft);
    border-color: #ebd8bd;
  }

  &.is-short {
    color: #2f6b58;
    background: var(--qhw-accent-soft);
    border-color: #d5e5dc;
  }

  &.is-more {
    color: #a8452f;
    background: #fbeee9;
    border-color: #f0d4ca;
  }
}

.qhw-count-approx {
  display: block;
  color: var(--qhw-muted);
  font-size: 10px;
  font-weight: 400;
  text-align: right;
}

.qhw-quota-head {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  justify-content: flex-end;
  width: 100%;
  white-space: nowrap;
}

.qhw-live-pill {
  display: inline-flex;
  align-items: center;
  height: 15px;
  padding: 0 5px;
  color: var(--qhw-accent);
  font-size: 9px;
  font-weight: 600;
  background: var(--qhw-accent-soft);
  border: 1px solid #d5e5dc;
  border-radius: 999px;
}

.qhw-quota-value {
  display: inline-block;
  min-width: 52px;
  padding: 2px 5px;
  color: var(--qhw-ink);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  text-align: right;
  border-radius: 5px;

  &.is-live {
    color: var(--qhw-accent);
    background: var(--qhw-accent-soft);
    animation: qhw-quota-flash 220ms ease-out;
  }

  &.is-zero {
    color: var(--qhw-muted);
    font-weight: 500;
    background: transparent;
  }
}

@keyframes qhw-quota-flash {
  from {
    background: var(--qhw-warm-soft);
  }

  to {
    background: var(--qhw-accent-soft);
  }
}

.qhw-exception-hint {
  margin-left: 8px;
  color: var(--qhw-muted);
  font-size: 11px;
}

.qhw-exception-calc {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  align-items: baseline;
  padding: 9px 12px;
  margin: -4px 0 16px;
  color: var(--qhw-muted);
  font-size: 12px;
  background: var(--qhw-accent-soft);
  border: 1px solid #d5e5dc;
  border-radius: 9px;

  b {
    color: var(--qhw-accent);
    font-size: 13px;
    font-variant-numeric: tabular-nums;
  }
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

.qhw-confirm-dialog {
  max-width: 92vw;
}

.qhw-confirm-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 14px;

  > div {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
    padding: 9px 11px;
    background: var(--qhw-surface-2);
    border: 1px solid var(--qhw-line);
    border-radius: 9px;
  }

  span {
    color: var(--qhw-muted);
    font-size: 11px;
  }

  strong {
    color: var(--qhw-ink);
    font-size: 17px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }

  small {
    overflow: hidden;
    color: var(--qhw-muted);
    font-size: 10px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.qhw-confirm-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  max-height: 52vh;
  overflow-y: auto;
}

.qhw-confirm-col {
  min-width: 0;
  padding: 12px;
  background: var(--qhw-surface-2);
  border: 1px solid var(--qhw-line);
  border-radius: 10px;

  header {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: center;

    strong {
      color: var(--qhw-ink);
      font-size: 14px;
      font-weight: 600;
    }

    small {
      margin-left: auto;
      color: var(--qhw-muted);
      font-size: 11px;
    }
  }
}

.qhw-confirm-sub {
  margin: 5px 0 8px;
  color: var(--qhw-muted);
  font-size: 11px;
}

.qhw-confirm-subhead {
  margin: 10px 0 2px;
  color: var(--qhw-muted);
  font-size: 11px;
  font-weight: 600;

  &:first-of-type {
    margin-top: 2px;
  }
}

.qhw-confirm-item {
  display: flex;
  gap: 10px;
  align-items: baseline;
  justify-content: space-between;
  padding: 5px 0;
  border-top: 1px dashed var(--qhw-line);
  color: var(--qhw-ink);
  font-size: 12px;

  > span {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  > .qhw-confirm-name {
    white-space: normal;
  }

  .qhw-confirm-usage {
    display: block;
    margin-top: 2px;
    color: var(--qhw-warm);
  }

  small {
    color: var(--qhw-muted);
    font-size: 10px;
  }

  strong {
    flex-shrink: 0;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }
}

.qhw-confirm-empty {
  margin: 8px 0 0;
  color: var(--qhw-muted);
  font-size: 12px;
}

.qhw-confirm-ondemand {
  margin-top: 12px;
  padding: 10px 12px;
  background: var(--qhw-surface-2);
  border: 1px dashed var(--qhw-line);
  border-radius: 10px;

  header {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: center;
    margin-bottom: 2px;

    strong {
      color: var(--qhw-ink);
      font-size: 12px;
    }

    small {
      color: var(--qhw-muted);
      font-size: 10px;
    }
  }
}

.qhw-exception-empty {
  margin: 8px 0 0;
  padding: 10px 12px;
  color: var(--qhw-muted);
  font-size: 12px;
  background: var(--qhw-surface-2);
  border-radius: 9px;
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

  .qhw-confirm-summary,
  .qhw-confirm-columns {
    grid-template-columns: 1fr;
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
