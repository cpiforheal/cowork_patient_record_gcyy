<template>
  <el-drawer
    :model-value="modelValue"
    :title="drawerTitle"
    size="480px"
    append-to-body
    @update:model-value="value => $emit('update:modelValue', value)"
  >
    <div v-loading="loading" class="qddd">
      <div v-if="error" class="qddd-empty">
        <strong>当日详情加载失败</strong>
        <span>{{ error }}</span>
        <el-button text type="primary" size="small" @click="load">重试</el-button>
      </div>

      <div v-else-if="!loading && !report?.id" class="qddd-empty">
        <strong>这天没有填报记录</strong>
        <span>可以打开这天补看或补填。</span>
      </div>

      <template v-else-if="report">
        <header class="qddd-head">
          <div>
            <span class="qddd-eyebrow">{{ departmentName || report.departmentName }}</span>
            <h3>{{ formatDate(date) }}</h3>
            <small>{{ submittedText }}</small>
          </div>
          <span class="qddd-status" :class="`is-${status.tone}`">{{ status.label }}</span>
        </header>

        <p v-if="!isQuota" class="qddd-note">旧口径日报，没有定额详情；可打开这天查看原始日报。</p>

        <template v-else>
          <dl class="qddd-facts">
            <div>
              <dt>接诊</dt>
              <dd>{{ headcountTotal }} 人</dd>
              <small v-if="composition">{{ composition }}</small>
            </div>
            <div>
              <dt>应扣</dt>
              <dd>{{ counts.auto }} 项</dd>
            </div>
            <div>
              <dt>实扣</dt>
              <dd>{{ report.status === "DRAFT" ? "—" : counts.deducted }} 项</dd>
            </div>
            <div>
              <dt>按需 / 待审</dt>
              <dd>{{ counts.onDemand }} / {{ pendingExceptions }}</dd>
            </div>
          </dl>

          <QuotaReceiptGroups v-if="groups.length" :groups="groups" mode="formal" readonly />
          <p v-else class="qddd-note">这天没有自动扣减项。</p>

          <section v-if="exceptionLines.length" class="qddd-section">
            <h4>例外记录</h4>
            <div v-for="line in exceptionLines" :key="lineKey(line)" class="qddd-exception">
              <span class="qddd-exception-name">{{ line.itemName }}</span>
              <span class="qddd-exception-qty">
                应扣 {{ quantity(line.quotaQuantity) }} · 上报 {{ quantity(line.actualQuantity) }} {{ line.unit }}
              </span>
              <span class="qddd-exception-status" :class="`is-${exceptionStatusTone(line.exceptionStatus)}`">
                {{ exceptionStatusLabel(line.exceptionStatus) }}
              </span>
            </div>
          </section>

          <section class="qddd-section">
            <button type="button" class="qddd-toggle" @click="showAll = !showAll">
              {{ showAll ? "收起完整清单" : `查看全部 ${report.lines.length} 项` }}
            </button>
            <div v-if="showAll" class="qddd-all">
              <div v-for="line in report.lines" :key="lineKey(line)" class="qddd-all-row">
                <span class="qddd-all-name">{{ line.itemName }}</span>
                <span class="qddd-all-qty">
                  应耗 {{ quantity(line.quotaQuantity) }} · 实扣 {{ quantity(line.quotaDeductedQuantity) }} {{ line.unit }}
                </span>
                <span class="qddd-all-status">{{ lineStatus(line) }}</span>
              </div>
            </div>
          </section>

          <p v-if="counts.shortage" class="qddd-shortage">{{ counts.shortage }} 项未足额扣减，已生成库管待办。</p>
        </template>
      </template>

      <footer class="qddd-actions">
        <el-button
          v-if="canExport && report?.id && report.status !== 'DRAFT'"
          text
          type="primary"
          @click="exportReport"
        >
          导出
        </el-button>
        <span class="qddd-spacer" />
        <el-button @click="$emit('update:modelValue', false)">关闭</el-button>
        <el-button v-if="date !== workingDate" type="primary" plain @click="$emit('open-day', date)">
          打开这天
        </el-button>
      </footer>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import dayjs from "dayjs";
import {
  downloadInventoryDepartmentDailyReportApi,
  getInventoryDepartmentDailyReportApi,
  type InventoryDepartmentDailyReport,
  type InventoryDepartmentDailyReportLine
} from "@/api/modules/inventory";
import QuotaReceiptGroups from "./QuotaReceiptGroups.vue";
import {
  deductionStatus,
  exceptionStatusLabel,
  exceptionStatusTone,
  formatReceiptQuantity,
  groupReceiptLines,
  quotaDayStatus,
  receiptCounts
} from "./quotaReceipt";

const props = defineProps<{
  modelValue: boolean;
  date: string;
  departmentKey: string;
  departmentName?: string;
  today: string;
  workingDate: string;
  canExport?: boolean;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  "open-day": [date: string];
}>();

const loading = ref(false);
const report = ref<InventoryDepartmentDailyReport>();
const error = ref("");
const showAll = ref(false);
const cache = new Map<string, InventoryDepartmentDailyReport>();

const cacheKey = () => `${props.departmentKey}|${props.date}`;
const load = async () => {
  if (!props.departmentKey || !props.date) return;
  const key = cacheKey();
  if (cache.has(key)) {
    report.value = cache.get(key);
    error.value = "";
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
    error.value = e?.message || "当日详情加载失败";
  } finally {
    loading.value = false;
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
  () => [props.modelValue, props.departmentKey, props.date],
  () => {
    showAll.value = false;
    if (props.modelValue) void load();
  },
  { immediate: true }
);

const drawerTitle = computed(() => `${dayjs(props.date).format("M月D日")} 详情`);
const formatDate = (value: string) => dayjs(value).format("M月D日 dddd");
const isQuota = computed(() => report.value?.reportMode === "QUOTA");
const status = computed(() => quotaDayStatus(report.value));
const groups = computed(() => groupReceiptLines(report.value?.lines));
const counts = computed(() => receiptCounts(report.value));
const pendingExceptions = computed(() => Number(report.value?.pendingExceptions || 0));
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
const submittedText = computed(() => {
  if (!report.value?.id) return "无提交记录";
  if (report.value.status === "DRAFT") return "草稿未提交";
  const value = report.value.closure?.submittedAt;
  return value ? `提交于 ${dayjs(value).format("MM-DD HH:mm")}` : "已提交";
});
const quantity = formatReceiptQuantity;
const lineKey = (line: InventoryDepartmentDailyReportLine) =>
  line.lineId || line.quotaRuleId || `${line.itemId}-${line.sourceText || ""}`;
const lineStatus = (line: InventoryDepartmentDailyReportLine) =>
  report.value?.status === "DRAFT" ? "待提交" : deductionStatus(line).label;

const exportReport = async () => {
  if (!props.canExport || !report.value?.id) return;
  try {
    await downloadInventoryDepartmentDailyReportApi(report.value.id, "xlsx");
  } catch (e: any) {
    ElMessage.error(e?.message || "导出失败");
  }
};
</script>

<style scoped lang="scss">
.qddd {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 180px;
}

.qddd-head {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  justify-content: space-between;

  > div {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  h3 {
    margin: 0;
    color: var(--qhw-ink, #2f2a24);
    font-size: 19px;
    font-weight: 600;
  }

  small {
    color: var(--qhw-muted, #7b7064);
    font-size: 12px;
  }
}

.qddd-eyebrow {
  color: var(--qhw-muted, #7b7064);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

.qddd-status {
  flex-shrink: 0;
  padding: 3px 9px;
  font-size: 11px;
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

  &.is-locked,
  &.is-info {
    color: var(--qhw-muted, #7b7064);
    background: var(--qhw-surface-2, #fbf6ee);
    border-color: var(--qhw-line, #e8dfd2);
  }
}

.qddd-empty {
  display: flex;
  flex-direction: column;
  gap: 5px;
  align-items: flex-start;
  padding: 16px 0;
  color: var(--qhw-muted, #7b7064);
  font-size: 12px;

  strong {
    color: var(--qhw-ink, #2f2a24);
    font-size: 14px;
    font-weight: 600;
  }
}

.qddd-note,
.qddd-shortage {
  margin: 0;
  padding: 10px 12px;
  color: var(--qhw-muted, #7b7064);
  font-size: 12px;
  line-height: 1.6;
  background: var(--qhw-surface-2, #fbf6ee);
  border-radius: 10px;
}

.qddd-shortage {
  color: #8a5a13;
  background: var(--qhw-warm-soft, #f7ecdd);
}

.qddd-facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin: 0;

  > div {
    min-width: 0;
    padding: 9px 10px;
    background: var(--qhw-surface-2, #fbf6ee);
    border: 1px solid var(--qhw-line, #e8dfd2);
    border-radius: 10px;
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
}

.qddd-section {
  h4 {
    margin: 0 0 4px;
    color: var(--qhw-ink, #2f2a24);
    font-size: 13px;
    font-weight: 600;
  }
}

.qddd-exception {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 2px 8px;
  padding: 7px 0;
  border-top: 1px dashed var(--qhw-line, #e8dfd2);
}

.qddd-exception-name {
  overflow: hidden;
  color: var(--qhw-ink, #2f2a24);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qddd-exception-qty {
  grid-column: 1;
  color: var(--qhw-muted, #7b7064);
  font-size: 11px;
}

.qddd-exception-status {
  grid-column: 2;
  grid-row: 1 / span 2;
  align-self: center;
  padding: 2px 8px;
  font-size: 10px;
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

.qddd-toggle {
  padding: 0;
  font: inherit;
  font-size: 12px;
  color: var(--qhw-accent, #2f6b58);
  text-decoration: underline;
  text-underline-offset: 2px;
  cursor: pointer;
  background: none;
  border: 0;

  &:focus-visible {
    outline: 2px solid var(--qhw-accent, #2f6b58);
    outline-offset: 2px;
  }
}

.qddd-all {
  margin-top: 6px;
  border-top: 1px dashed var(--qhw-line, #e8dfd2);
}

.qddd-all-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 2px 8px;
  padding: 7px 0;
  border-bottom: 1px dashed var(--qhw-line, #e8dfd2);
}

.qddd-all-name {
  overflow: hidden;
  color: var(--qhw-ink, #2f2a24);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qddd-all-qty {
  grid-column: 1;
  color: var(--qhw-muted, #7b7064);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}

.qddd-all-status {
  grid-column: 2;
  grid-row: 1 / span 2;
  align-self: center;
  color: var(--qhw-muted, #7b7064);
  font-size: 10px;
  white-space: nowrap;
}

.qddd-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  padding-top: 4px;
  border-top: 1px dashed var(--qhw-line, #e8dfd2);
}

.qddd-spacer {
  flex: 1;
}
</style>
