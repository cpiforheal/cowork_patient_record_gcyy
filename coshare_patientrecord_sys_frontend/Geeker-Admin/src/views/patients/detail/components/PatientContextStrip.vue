<template>
  <section class="record-context-strip screen-only">
    <article class="context-card fixed">
      <span>固定带入</span>
      <strong>{{ patientName || "未登记姓名" }}</strong>
      <small
        >{{ visitNoLabel }}：{{ visitNo || patientId }} · {{ visitType }} · {{ visitDateLabel }}：{{
          visitDate || "待补录"
        }}</small
      >
    </article>

    <article class="context-card editable">
      <span>可填写区</span>
      <strong>{{ visibleEditableCount }}/{{ editableCount }} 项显示</strong>
      <small>当前层 {{ fieldLayerLabel }} · 必填待补 {{ requiredMissingCount }} 项</small>
    </article>

    <article class="context-card attachments">
      <span>附件区</span>
      <strong>{{ attachmentCount }} 份证据</strong>
      <small>{{ validAttachmentCount }} 份可打开 · {{ invalidAttachmentCount }} 份待补源文件</small>
    </article>
  </section>
</template>

<script setup lang="ts">
interface Props {
  patientName?: string;
  patientId: string;
  visitNo?: string;
  visitNoLabel: string;
  visitType: string;
  visitDateLabel: string;
  visitDate?: string;
  visibleEditableCount: number;
  editableCount: number;
  fieldLayerLabel: string;
  requiredMissingCount: number;
  attachmentCount: number;
  validAttachmentCount: number;
  invalidAttachmentCount: number;
}

defineProps<Props>();
</script>

<style scoped lang="scss">
.record-context-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

.context-card {
  position: relative;
  min-width: 0;
  padding: 13px 14px;
  overflow: hidden;
  background: var(--hos-glass);
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-lg);
  box-shadow: var(--hos-shadow-soft);

  &::before {
    position: absolute;
    inset: 0 auto 0 0;
    width: 4px;
    content: "";
    background: #cbd5e1;
  }

  span,
  strong,
  small {
    display: block;
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 12px;
    font-weight: 700;
  }

  strong {
    margin-top: 4px;
    overflow: hidden;
    color: var(--hos-text-primary);
    font-size: 17px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    margin-top: 4px;
    overflow: hidden;
    color: var(--hos-text-secondary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &.fixed {
    background: var(--hos-accent-soft);
    border-color: var(--hos-border);

    &::before {
      background: var(--record-fixed);
    }
  }

  &.editable {
    background: var(--hos-primary-soft);
    border-color: var(--hos-border-interactive);

    &::before {
      background: var(--record-accent);
    }

    span,
    strong {
      color: var(--hos-primary-deep);
    }
  }

  &.attachments {
    background: var(--record-warning-soft);
    border-color: #fed7aa;

    &::before {
      background: var(--record-warning);
    }

    span,
    strong {
      color: #b45309;
    }
  }
}

@media (max-width: 960px) {
  .record-context-strip {
    grid-template-columns: 1fr;
  }
}
</style>
