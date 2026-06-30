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
