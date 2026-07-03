<template>
  <el-dialog
    :model-value="visible"
    title="打印预检"
    width="520px"
    append-to-body
    destroy-on-close
    class="print-preflight-dialog"
    :z-index="3200"
    @update:model-value="$emit('update:visible', $event)"
  >
    <div class="print-preflight-list">
      <article v-for="item in items" :key="item.key" :class="`is-${item.level}`">
        <span class="preflight-dot" aria-hidden="true"></span>

        <div>
          <strong>{{ item.label }}</strong>

          <small>{{ item.value }}</small>
        </div>
      </article>
    </div>

    <template #footer>
      <el-button @click="$emit('update:visible', false)">返回补齐</el-button>

      <el-button type="primary" :icon="Printer" @click="$emit('confirm')">继续打印</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { Printer } from "@element-plus/icons-vue";

export type PrintPreflightItem = {
  key: string;
  level: "success" | "warning";
  label: string;
  value: string;
};

defineProps<{
  visible: boolean;
  items: PrintPreflightItem[];
}>();

defineEmits<{
  "update:visible": [visible: boolean];
  confirm: [];
}>();
</script>

<style scoped lang="scss">
.print-preflight-list {
  display: grid;
  gap: 10px;

  article {
    display: grid;
    grid-template-columns: 12px minmax(0, 1fr);
    gap: 10px;
    align-items: start;
    padding: 12px;
    background: var(--hos-glass);
    border: 1px solid var(--hos-border-light);
    border-radius: var(--hos-radius-lg);

    &.is-warning {
      background: var(--hos-status-warning-soft);
      border-color: rgb(217 119 6 / 20%);

      .preflight-dot {
        background: var(--hos-status-warning);
        box-shadow: 0 0 0 5px rgb(217 119 6 / 10%);
      }
    }

    &.is-success .preflight-dot {
      background: var(--hos-status-success);
      box-shadow: 0 0 0 5px rgb(22 163 74 / 10%);
    }
  }

  strong,
  small {
    display: block;
  }

  small {
    margin-top: 4px;
    color: var(--hos-text-secondary);
  }
}

.preflight-dot {
  width: 10px;
  height: 10px;
  margin-top: 4px;
  border-radius: 999px;
}
</style>
