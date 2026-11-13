<template>
  <el-dialog v-model="visible" title="记录滴注" width="420px" destroy-on-close>
    <div v-if="item" class="dd-summary">
      <strong>{{ item.drugName }}</strong>
      <span>{{ item.slotLabel }} · 计划 {{ item.plannedTime }}</span>
    </div>
    <el-form label-position="top">
      <el-form-item label="眼别">
        <el-radio-group v-model="form.eyeSide">
          <el-radio-button value="BOTH">双眼</el-radio-button>
          <el-radio-button value="LEFT">左眼</el-radio-button>
          <el-radio-button value="RIGHT">右眼</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="实际时间">
        <el-date-picker
          v-model="form.takenAt"
          type="datetime"
          value-format="YYYY-MM-DD HH:mm:ss"
          format="YYYY-MM-DD HH:mm"
          :clearable="false"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="备注（可选）">
        <el-input v-model="form.note" type="textarea" :rows="2" maxlength="200" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts" name="EyeDropDoseDialog">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { createEyeDropDoseApi, type EyeDropItem } from "@/api/modules/clinic/eyeDrop";

const props = defineProps<{
  modelValue: boolean;
  item?: EyeDropItem;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  saved: [];
}>();

const visible = computed({
  get: () => props.modelValue,
  set: value => emit("update:modelValue", value)
});

const saving = ref(false);
const form = reactive({ eyeSide: "BOTH" as "BOTH" | "LEFT" | "RIGHT", takenAt: "", note: "" });

const nowText = () => {
  const now = new Date();
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
};

watch(
  () => props.modelValue,
  value => {
    if (!value) return;
    form.eyeSide = "BOTH";
    form.takenAt = nowText();
    form.note = "";
  }
);

const submit = async () => {
  if (!props.item) return;
  if (!form.takenAt) {
    ElMessage.warning("请选择实际滴注时间");
    return;
  }
  saving.value = true;
  try {
    await createEyeDropDoseApi({
      planId: props.item.planId,
      eyeSide: form.eyeSide,
      takenAt: form.takenAt,
      note: form.note.trim(),
      requestId: `${props.item.planId}-${form.takenAt}-${Date.now()}`
    });
    ElMessage.success("已记录滴注");
    emit("saved");
    visible.value = false;
  } catch (error: any) {
    ElMessage.error(error?.message || "记录失败");
  } finally {
    saving.value = false;
  }
};
</script>

<style scoped lang="scss">
.dd-summary {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 14px;
  strong {
    font-size: 16px;
  }
  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}
</style>
