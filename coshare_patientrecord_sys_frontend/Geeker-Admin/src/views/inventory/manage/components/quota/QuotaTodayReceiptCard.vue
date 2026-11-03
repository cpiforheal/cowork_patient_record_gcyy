<template>
  <section class="qhw-tile receipt-card" :class="{ 'is-formal': completed, 'is-just-submitted': justSubmitted }">
    <header class="receipt-head">
      <div class="receipt-title">
        <span class="receipt-eyebrow">工作回执</span>
        <h3>{{ title }}</h3>
        <small>{{ subtitle }}</small>
      </div>
      <div class="receipt-actions">
        <span class="receipt-chip" :class="completed ? 'is-formal' : 'is-estimated'">
          {{ completed ? "正式回执" : "预计回执" }}
        </span>
        <el-button
          v-if="completed && canExport && report?.id"
          text
          type="primary"
          size="small"
          :aria-label="exportLabel"
          @click="$emit('export')"
        >
          导出
        </el-button>
      </div>
    </header>

    <QuotaReceiptGroups
      v-if="groups.length"
      :groups="groups"
      :mode="completed ? 'formal' : 'estimated'"
      @open-detail="key => $emit('open-detail', key)"
    />

    <div v-else class="receipt-empty">
      <strong>今日没有需要自动扣减的耗材</strong>
      <span>
        {{ counts.onDemand ? `另有 ${counts.onDemand} 项按需耗材，` : "" }}按需或未列耗材请通过上报例外处理。
      </span>
      <el-button v-if="completed" text type="primary" size="small" @click="$emit('open-exception')">
        上报例外
      </el-button>
      <small v-else>提交后可上报例外</small>
    </div>

    <footer class="receipt-foot">
      <span>应扣 <b>{{ counts.auto }}</b> 项</span>
      <span>实扣 <b>{{ counts.deducted }}</b> 项</span>
      <span>按需 <b>{{ counts.onDemand }}</b> 项</span>
      <span v-if="counts.exceptions" class="is-pending">待审例外 <b>{{ counts.exceptions }}</b></span>
    </footer>

    <p v-if="counts.shortage" class="receipt-shortage">
      {{ counts.shortage }} 项未足额扣减，已生成库管待办；本回执保留应扣数量。
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";
import dayjs from "dayjs";
import type { InventoryDepartmentDailyReport } from "@/api/modules/inventory";
import QuotaReceiptGroups from "./QuotaReceiptGroups.vue";
import type { ReceiptCounts, ReceiptGroup, ReceiptGroupKey } from "./quotaReceipt";

const props = withDefaults(
  defineProps<{
    report?: InventoryDepartmentDailyReport;
    groups: ReceiptGroup[];
    counts: ReceiptCounts;
    businessDate: string;
    today: string;
    canExport?: boolean;
    justSubmitted?: boolean;
  }>(),
  { canExport: false, justSubmitted: false }
);

defineEmits<{
  "open-detail": [key: ReceiptGroupKey];
  "open-exception": [];
  export: [];
}>();

const completed = computed(() => Boolean(props.report?.id) && props.report?.status !== "DRAFT");
const title = computed(() =>
  props.businessDate === props.today ? "今日回执" : `${dayjs(props.businessDate).format("M月D日")}回执`
);
const statusLabel = computed(() => {
  if (props.report?.locked) return "已锁定";
  if (props.report?.status === "APPROVED") return "已确认";
  if (props.report?.status === "SUBMITTED") return "已提交";
  if (props.report?.status === "PARTIALLY_APPROVED") return "部分确认";
  return "待提交";
});
const subtitle = computed(() => {
  if (!completed.value) return "提交后转为正式回执，数量以系统定额为准。";
  const submittedAt = props.report?.closure?.submittedAt;
  const time = submittedAt ? dayjs(submittedAt).format("M月D日 HH:mm") : "";
  return time ? `${time} · ${statusLabel.value}` : statusLabel.value;
});
const exportLabel = computed(
  () => `下载 ${dayjs(props.businessDate).format("M月D日")} 定额回执（xlsx）`
);
</script>

<style scoped lang="scss">
.receipt-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 22px 24px;
  background: var(--qhw-surface, #fffdf8);
}

.receipt-head {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  justify-content: space-between;
}

.receipt-title {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;

  h3 {
    margin: 0;
    color: var(--qhw-ink, #2f2a24);
    font-size: 18px;
    font-weight: 600;
    letter-spacing: -0.01em;
  }

  small {
    color: var(--qhw-muted, #7b7064);
    font-size: 12px;
    line-height: 1.5;
  }
}

.receipt-eyebrow {
  color: var(--qhw-muted, #7b7064);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.receipt-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
  align-items: center;
}

.receipt-chip {
  padding: 3px 9px;
  font-size: 11px;
  font-weight: 600;
  border: 1px solid transparent;
  border-radius: 999px;

  &.is-formal {
    color: var(--qhw-success, #3f7f62);
    background: var(--qhw-success-soft, #eaf4ee);
    border-color: #cfe3d6;
  }

  &.is-estimated {
    color: #8a5a13;
    background: var(--qhw-warm-soft, #f7ecdd);
    border-color: #ebd8bd;
  }
}

.receipt-empty {
  display: flex;
  flex-direction: column;
  gap: 5px;
  align-items: flex-start;
  padding: 14px 16px;
  background: var(--qhw-surface-2, #fbf6ee);
  border: 1px dashed var(--qhw-line, #e8dfd2);
  border-radius: 12px;

  strong {
    color: var(--qhw-ink, #2f2a24);
    font-size: 14px;
    font-weight: 600;
  }

  span,
  small {
    color: var(--qhw-muted, #7b7064);
    font-size: 12px;
    line-height: 1.6;
  }
}

.receipt-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  padding-top: 11px;
  color: var(--qhw-muted, #7b7064);
  font-size: 12px;
  border-top: 1px dashed var(--qhw-line, #e8dfd2);

  b {
    color: var(--qhw-ink, #2f2a24);
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }

  .is-pending b {
    color: var(--qhw-warm, #b7793f);
  }
}

.receipt-shortage {
  margin: 0;
  padding: 8px 10px;
  color: #8a5a13;
  font-size: 12px;
  background: var(--qhw-warm-soft, #f7ecdd);
  border-radius: 9px;
}

.receipt-card.is-formal {
  background: linear-gradient(180deg, var(--qhw-success-soft, #eaf4ee) 0%, var(--qhw-surface, #fffdf8) 46%);
}

.receipt-card.is-just-submitted {
  animation: receipt-unfold 320ms cubic-bezier(0.22, 0.8, 0.3, 1) both;
}

@keyframes receipt-unfold {
  from {
    opacity: 0;
    transform: translateY(6px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .receipt-card.is-just-submitted {
    animation: none;
  }
}
</style>
