<template>
  <section class="qda" tabindex="-1" role="region" aria-live="polite" :aria-label="`${formatDate(date)} 回执详情`">
    <header class="qda-head">
      <div>
        <span class="qda-eyebrow">当日回顾</span>
        <strong>{{ formatDate(date) }}</strong>
        <small>{{ submittedText }}</small>
      </div>
      <button type="button" class="qda-close" aria-label="收起当日详情" @click="$emit('close')">收起</button>
    </header>

    <div v-if="loading" class="qda-loading">
      <span class="qda-spinner" aria-hidden="true" />
      正在加载当日回执
    </div>

    <div v-else-if="error" class="qda-empty">
      <strong>回执加载失败</strong>
      <span>{{ error }}</span>
      <el-button text type="primary" size="small" @click="load">重试</el-button>
    </div>

    <div v-else-if="!report?.id" class="qda-empty">
      <strong>这天没有填报记录</strong>
      <span>{{ date === today ? "今天还没有提交，切回去填写即可生成回执。" : "可以切换到该日补看或补填。" }}</span>
    </div>

    <template v-else>
      <div class="qda-status-row">
        <span class="qda-status" :class="`is-${statusTone}`">{{ statusLabel }}</span>
        <span v-if="isQuota" class="qda-fact">应扣 <b>{{ counts.auto }}</b> · 实扣 <b>{{ counts.deducted }}</b> · 按需 <b>{{ counts.onDemand }}</b></span>
      </div>

      <p v-if="!isQuota" class="qda-note">旧口径日报，没有定额回执；可切换到该日查看原始日报。</p>

      <template v-else>
        <dl class="qda-facts">
          <div>
            <dt>服务人次</dt>
            <dd>{{ headcountTotal }} 人</dd>
            <small v-if="composition">{{ composition }}</small>
          </div>
          <div>
            <dt>提交时间</dt>
            <dd class="is-time">{{ submittedAt || "未提交" }}</dd>
          </div>
        </dl>

        <QuotaReceiptGroups v-if="groups.length" :groups="groups" mode="formal" readonly />
        <p v-else class="qda-note">这天没有自动扣减项。</p>

        <div v-if="exceptionLines.length" class="qda-exceptions">
          <h4>例外记录</h4>
          <div v-for="line in exceptionLines" :key="lineKey(line)" class="qda-exception">
            <span>{{ line.itemName }}</span>
            <span class="qda-exception-qty">
              应扣 {{ quantity(line.quotaQuantity) }} · 上报 {{ quantity(line.actualQuantity) }} {{ line.unit }}
            </span>
            <span class="qda-exception-status" :class="`is-${exceptionStatusTone(line.exceptionStatus)}`">
              {{ exceptionStatusLabel(line.exceptionStatus) }}
            </span>
          </div>
        </div>

        <p v-if="counts.shortage" class="qda-shortage">{{ counts.shortage }} 项未足额扣减，已生成库管待办。</p>
      </template>
    </template>

    <footer class="qda-actions">
      <el-button v-if="date !== selectedDate" text type="primary" @click="$emit('switch-date', date)">
        切换到该日
      </el-button>
      <el-button v-if="canReportException" type="primary" plain @click="$emit('open-exception', date)">
        上报例外
      </el-button>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import dayjs from "dayjs";
import {
  getInventoryDepartmentDailyReportApi,
  type InventoryDepartmentDailyReport,
  type InventoryDepartmentDailyReportLine
} from "@/api/modules/inventory";
import QuotaReceiptGroups from "./QuotaReceiptGroups.vue";
import {
  exceptionStatusLabel,
  exceptionStatusTone,
  formatReceiptQuantity,
  groupReceiptLines,
  receiptCounts
} from "./quotaReceipt";

const props = defineProps<{
  date: string;
  departmentKey: string;
  departmentName?: string;
  today: string;
  selectedDate: string;
}>();

const emit = defineEmits<{
  close: [];
  "switch-date": [date: string];
  "open-exception": [date: string];
  loaded: [];
}>();

const loading = ref(false);
const report = ref<InventoryDepartmentDailyReport>();
const error = ref("");
const cache = new Map<string, InventoryDepartmentDailyReport>();

const cacheKey = () => `${props.departmentKey}|${props.date}`;
const load = async () => {
  if (!props.departmentKey || !props.date) return;
  const key = cacheKey();
  if (cache.has(key)) {
    report.value = cache.get(key);
    error.value = "";
    nextTick(() => emit("loaded"));
    return;
  }
  loading.value = true;
  error.value = "";
  report.value = undefined;
  try {
    const result = await getInventoryDepartmentDailyReportApi({
      departmentKey: props.departmentKey,
      date: props.date
    });
    cache.set(key, result.data);
    report.value = result.data;
  } catch (e: any) {
    error.value = e?.message || "当日回执加载失败";
  } finally {
    loading.value = false;
    nextTick(() => emit("loaded"));
  }
};

const invalidate = (date?: string) => {
  if (!date) {
    cache.clear();
    return;
  }
  cache.delete(`${props.departmentKey}|${date}`);
};

defineExpose({ invalidate });

watch(
  () => [props.departmentKey, props.date],
  () => {
    void load();
  },
  { immediate: true }
);

const formatDate = (value: string) => dayjs(value).format("M月D日 dddd");
const isQuota = computed(() => report.value?.reportMode === "QUOTA");
const groups = computed(() => groupReceiptLines(report.value?.lines));
const counts = computed(() => receiptCounts(report.value));
const headcountTotal = computed(() => Number(report.value?.headcount?.total || 0));
const composition = computed(() => {
  const headcount = report.value?.headcount;
  if (!headcount) return "";
  const parts: string[] = [];
  if (Number(headcount.outpatient || 0)) parts.push(`门诊 ${headcount.outpatient}`);
  if (Number(headcount.inpatient || 0)) parts.push(`住院 ${headcount.inpatient}`);
  if (Number(headcount.manual || 0)) parts.push(`手工 ${headcount.manual}`);
  return parts.join(" · ");
});
const exceptionLines = computed(() =>
  (report.value?.lines || []).filter(
    line => line.exceptionStatus && line.exceptionStatus !== "NONE"
  )
);
const statusLabel = computed(() => {
  if (report.value?.locked) return "已锁定";
  if (report.value?.status === "APPROVED") return "已确认";
  if (report.value?.status === "SUBMITTED") return "已提交";
  if (report.value?.status === "PARTIALLY_APPROVED") return "部分确认";
  if (report.value?.status === "DRAFT") return "草稿";
  return report.value?.status || "未提交";
});
const statusTone = computed(() => {
  if (report.value?.locked) return "locked";
  if (report.value?.status === "DRAFT") return "draft";
  return "success";
});
const submittedAt = computed(() => {
  const value = report.value?.closure?.submittedAt;
  return value ? dayjs(value).format("M月D日 HH:mm") : "";
});
const submittedText = computed(() => {
  if (report.value?.status === "DRAFT") return "草稿未提交";
  return submittedAt.value ? `${submittedAt.value} 已提交` : "无提交记录";
});
const canReportException = computed(
  () =>
    props.date === props.today &&
    props.date === props.selectedDate &&
    Boolean(report.value?.id) &&
    report.value?.status !== "DRAFT" &&
    !report.value?.locked &&
    isQuota.value
);
const quantity = formatReceiptQuantity;
const lineKey = (line: InventoryDepartmentDailyReportLine) =>
  line.lineId || line.quotaRuleId || `${line.itemId}-${line.sourceText || ""}`;
</script>

<style scoped lang="scss">
.qda {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  gap: 12px;
  box-sizing: border-box;
  max-height: 320px;
  padding: 14px 16px;
  overflow-y: auto;
  background: var(--qhw-surface-2, #fbf6ee);
  border-top: 1px dashed var(--qhw-line, #e8dfd2);
  overscroll-behavior: contain;

  &:focus {
    outline: none;
  }
}

.qda-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;

  > div {
    display: flex;
    flex-direction: column;
    gap: 1px;
    min-width: 0;
  }

  strong {
    color: var(--qhw-ink, #2f2a24);
    font-size: 15px;
    font-weight: 600;
  }

  small {
    color: var(--qhw-muted, #7b7064);
    font-size: 11px;
  }
}

.qda-eyebrow {
  color: var(--qhw-muted, #7b7064);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.qda-close {
  flex-shrink: 0;
  padding: 2px 6px;
  font: inherit;
  font-size: 11px;
  color: var(--qhw-muted, #7b7064);
  cursor: pointer;
  background: none;
  border: 0;

  &:hover {
    color: var(--qhw-accent, #2f6b58);
  }

  &:focus-visible {
    outline: 2px solid var(--qhw-accent, #2f6b58);
    outline-offset: 2px;
  }
}

.qda-loading,
.qda-empty {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
  padding: 12px 0;
  color: var(--qhw-muted, #7b7064);
  font-size: 12px;
  line-height: 1.6;

  strong {
    color: var(--qhw-ink, #2f2a24);
    font-size: 13px;
    font-weight: 600;
  }
}

.qda-loading {
  flex-direction: row;
  gap: 8px;
  align-items: center;
}

.qda-spinner {
  width: 13px;
  height: 13px;
  border: 2px solid var(--qhw-line, #e8dfd2);
  border-top-color: var(--qhw-accent, #2f6b58);
  border-radius: 50%;
  animation: qda-spin 700ms linear infinite;
}

@keyframes qda-spin {
  to {
    transform: rotate(360deg);
  }
}

.qda-status-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  align-items: center;
}

.qda-status {
  padding: 2px 8px;
  font-size: 10px;
  font-weight: 600;
  border: 1px solid transparent;
  border-radius: 999px;

  &.is-success {
    color: var(--qhw-success, #3f7f62);
    background: var(--qhw-success-soft, #eaf4ee);
    border-color: #cfe3d6;
  }

  &.is-draft {
    color: #8a5a13;
    background: var(--qhw-warm-soft, #f7ecdd);
    border-color: #ebd8bd;
  }

  &.is-locked {
    color: var(--qhw-muted, #7b7064);
    background: var(--qhw-surface, #fffdf8);
    border-color: var(--qhw-line, #e8dfd2);
  }
}

.qda-fact {
  color: var(--qhw-muted, #7b7064);
  font-size: 11px;

  b {
    color: var(--qhw-ink, #2f2a24);
    font-variant-numeric: tabular-nums;
  }
}

.qda-note,
.qda-shortage {
  margin: 0;
  padding: 8px 10px;
  color: var(--qhw-muted, #7b7064);
  font-size: 11px;
  line-height: 1.6;
  background: var(--qhw-surface, #fffdf8);
  border-radius: 8px;
}

.qda-shortage {
  color: #8a5a13;
  background: var(--qhw-warm-soft, #f7ecdd);
}

.qda-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin: 0;

  > div {
    min-width: 0;
    padding: 8px 10px;
    background: var(--qhw-surface, #fffdf8);
    border: 1px solid var(--qhw-line, #e8dfd2);
    border-radius: 9px;
  }

  dt {
    color: var(--qhw-muted, #7b7064);
    font-size: 10px;
  }

  dd {
    margin: 2px 0 0;
    color: var(--qhw-ink, #2f2a24);
    font-size: 15px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }

  small {
    color: var(--qhw-muted, #7b7064);
    font-size: 10px;
  }

  .is-time {
    font-size: 12px;
    font-weight: 500;
  }
}

.qda-exceptions {
  h4 {
    margin: 0 0 4px;
    color: var(--qhw-ink, #2f2a24);
    font-size: 12px;
    font-weight: 600;
  }
}

.qda-exception {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 2px 8px;
  padding: 6px 0;
  border-top: 1px dashed var(--qhw-line, #e8dfd2);
  color: var(--qhw-ink, #2f2a24);
  font-size: 12px;
}

.qda-exception-qty {
  grid-column: 1;
  color: var(--qhw-muted, #7b7064);
  font-size: 10px;
}

.qda-exception-status {
  grid-column: 2;
  grid-row: 1 / span 2;
  align-self: center;
  padding: 1px 7px;
  font-size: 9px;
  border-radius: 999px;

  &.is-info {
    color: #4f6f8f;
    background: #eef4fa;
  }

  &.is-success {
    color: var(--qhw-success, #3f7f62);
    background: var(--qhw-success-soft, #eaf4ee);
  }

  &.is-warning {
    color: #8a5a13;
    background: var(--qhw-warm-soft, #f7ecdd);
  }

  &.is-danger {
    color: #a33b3b;
    background: #fbefef;
  }
}

.qda-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: auto;
  padding-top: 4px;
}

@media (prefers-reduced-motion: reduce) {
  .qda-spinner {
    animation: none;
  }
}
</style>
