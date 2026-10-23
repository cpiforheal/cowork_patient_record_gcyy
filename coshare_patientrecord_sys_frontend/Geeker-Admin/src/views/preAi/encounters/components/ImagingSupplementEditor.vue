<template>
  <section class="imaging-supplement-editor">
    <header>
      <div>
        <strong>{{ label }}补充描述</strong>
        <small>保存后同步到医生最终复核，不进入病历生成</small>
      </div>
      <el-tag v-if="note" type="success" size="small" effect="plain">已填写</el-tag>
    </header>
    <template v-if="locked">
      <p class="locked-note">{{ note || "未填写" }}</p>
      <small class="locked-hint">医生已确认复核，科室补充已锁定</small>
    </template>
    <template v-else>
      <el-input
        v-model="draft"
        type="textarea"
        :rows="3"
        maxlength="2000"
        show-word-limit
        :disabled="!canEdit || loading"
        placeholder="填写该影像的补充描述，保存后同步到医生最终复核"
      />
      <footer>
        <small v-if="note">最后保存：{{ updatedBy || "科室" }} · {{ updatedAt || "" }}</small>
        <small v-else>{{ canEdit ? "尚未填写（选填）" : "当前岗位只读" }}</small>
        <el-button v-if="canEdit" size="small" type="primary" :disabled="!isDirty || loading" :loading="loading" @click="save">
          {{ !draft.trim() && note ? "清空补充" : "保存补充" }}
        </el-button>
      </footer>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessageBox } from "element-plus";
import type { PreAiImagingCategory } from "@/api/modules/clinic";

const props = defineProps<{
  category: PreAiImagingCategory;
  label: string;
  note?: string;
  updatedBy?: string;
  updatedAt?: string;
  canEdit: boolean;
  locked?: boolean;
  loading?: boolean;
}>();

const emit = defineEmits<{
  save: [category: PreAiImagingCategory, note: string];
}>();

const draft = ref(props.note || "");
const isDirty = computed(() => draft.value.trim() !== (props.note || "").trim());

watch(
  () => [props.category, props.note] as const,
  ([, note]) => {
    draft.value = note || "";
  },
  { immediate: true }
);

const save = () => {
  const value = draft.value.trim();
  if (!value && props.note) {
    ElMessageBox.confirm(`清空「${props.label}」的科室补充描述？`, "确认清空", {
      type: "warning",
      confirmButtonText: "清空",
      cancelButtonText: "取消"
    })
      .then(() => emit("save", props.category, ""))
      .catch(() => undefined);
    return;
  }
  emit("save", props.category, value);
};
</script>

<style scoped lang="scss">
.imaging-supplement-editor {
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-lighter);

  header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 10px;
  }

  header > div {
    display: grid;
    gap: 2px;
  }

  strong {
    font-size: 13px;
  }

  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
    line-height: 1.5;
  }

  footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }

  .locked-note {
    margin: 0;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    line-height: 1.65;
  }

  .locked-hint {
    color: var(--el-color-warning-dark-2);
  }

  footer :deep(.el-button) {
    flex-shrink: 0;
  }
}

@media (max-width: 760px) {
  .imaging-supplement-editor footer {
    align-items: stretch;
    flex-direction: column;
  }

  .imaging-supplement-editor footer :deep(.el-button) {
    width: 100%;
    margin-left: 0;
  }
}
</style>
