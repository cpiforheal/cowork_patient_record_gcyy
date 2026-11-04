<template>
  <div class="receipt-groups">
    <button
      v-for="group in groups"
      :key="group.key"
      type="button"
      class="receipt-group"
      :class="{ 'is-readonly': readonly }"
      :aria-disabled="readonly || undefined"
      :tabindex="readonly ? -1 : undefined"
      @click="!readonly && $emit('open-detail', group.key)"
    >
      <span class="receipt-group-head">
        <i class="receipt-group-dot" :class="group.key === 'per' ? 'is-per' : 'is-fixed'" aria-hidden="true" />
        <strong>{{ group.label }}</strong>
        <small>{{ group.lines.length }} 项</small>
      </span>
      <span class="receipt-arrow" aria-hidden="true">→</span>
      <span class="receipt-samples">
        <span
          v-for="line in samples(group)"
          :key="lineKey(line)"
          class="receipt-sample"
          :class="{ 'is-short': isShort(line) }"
        >
          {{ line.itemName }}
          <b v-if="isShort(line)">{{ quantity(line.quotaQuantity) }} → {{ quantity(line.quotaDeductedQuantity) }}</b>
          <b v-else>{{ quantity(mode === "formal" ? line.quotaDeductedQuantity : line.quotaQuantity) }}</b>
          <i>{{ line.unit }}</i>
        </span>
        <span v-if="group.lines.length > maxSamples" class="receipt-more">
          +{{ group.lines.length - maxSamples }} 项
        </span>
      </span>
    </button>
  </div>
</template>

<script setup lang="ts">
import type { InventoryDepartmentDailyReportLine } from "@/api/modules/inventory";
import { formatReceiptQuantity, type ReceiptGroup, type ReceiptGroupKey } from "./quotaReceipt";

const props = withDefaults(
  defineProps<{
    groups: ReceiptGroup[];
    mode?: "estimated" | "formal";
    maxSamples?: number;
    readonly?: boolean;
  }>(),
  { mode: "estimated", maxSamples: 3, readonly: false }
);

defineEmits<{ "open-detail": [key: ReceiptGroupKey] }>();

const samples = (group: ReceiptGroup) => group.lines.slice(0, props.maxSamples);
const quantity = formatReceiptQuantity;
const lineKey = (line: InventoryDepartmentDailyReportLine) =>
  line.lineId || line.quotaRuleId || `${line.itemId}-${line.sourceText || ""}`;
const isShort = (line: InventoryDepartmentDailyReportLine) =>
  props.mode === "formal" &&
  Number(line.quotaDeductedQuantity || 0) < Number(line.quotaQuantity || 0);
</script>

<style scoped lang="scss">
.receipt-groups {
  display: flex;
  flex-direction: column;
}

.receipt-group {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 4px 10px;
  padding: 11px 0;
  font: inherit;
  color: inherit;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-top: 1px dashed var(--qhw-line, #e8dfd2);

  &:first-child {
    border-top: 0;
  }

  &:hover .receipt-group-head strong {
    color: var(--qhw-accent, #2f6b58);
  }

  &:focus-visible {
    border-radius: 8px;
    outline: 2px solid var(--qhw-accent, #2f6b58);
    outline-offset: 2px;
  }

  &.is-readonly {
    cursor: default;

    &:hover .receipt-group-head strong {
      color: inherit;
    }
  }
}

.receipt-group-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  min-width: 0;

  strong {
    font-size: 14px;
    font-weight: 600;
    transition: color 160ms ease;
  }

  small {
    color: var(--qhw-muted, #7b7064);
    font-size: 12px;
    font-variant-numeric: tabular-nums;
  }
}

.receipt-group-dot {
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  border-radius: 50%;

  &.is-per {
    background: #9cc6b5;
  }

  &.is-fixed {
    background: #d9a86c;
  }
}

.receipt-arrow {
  grid-column: 2;
  grid-row: 1;
  color: var(--qhw-muted, #7b7064);
  font-size: 13px;
  opacity: 0.5;
}

.receipt-samples {
  display: flex;
  flex-wrap: wrap;
  grid-column: 1 / -1;
  gap: 4px 10px;
  color: var(--qhw-muted, #7b7064);
  font-size: 12px;
  line-height: 1.6;
}

.receipt-sample {
  b {
    color: var(--qhw-ink, #2f2a24);
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }

  i {
    font-style: normal;
  }

  &.is-short b {
    color: var(--qhw-warm, #b7793f);
  }
}

.receipt-more {
  color: var(--qhw-muted, #7b7064);
}

@media (prefers-reduced-motion: reduce) {
  .receipt-group-head strong {
    transition: none;
  }
}
</style>
