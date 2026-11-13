<template>
  <el-dialog v-model="visible" title="记录眼压 / 症状" width="440px" destroy-on-close>
    <el-form label-position="top">
      <el-form-item label="测量时间">
        <el-date-picker
          v-model="form.measuredAt"
          type="datetime"
          value-format="YYYY-MM-DD HH:mm:ss"
          format="YYYY-MM-DD HH:mm"
          :clearable="false"
          style="width: 100%"
        />
      </el-form-item>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="左眼眼压 (mmHg)">
            <el-input-number v-model="form.iopLeft" :min="0" :max="80" :precision="1" :step="0.5" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="右眼眼压 (mmHg)">
            <el-input-number v-model="form.iopRight" :min="0" :max="80" :precision="1" :step="0.5" style="width: 100%" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="症状">
        <el-checkbox-group v-model="form.symptoms">
          <el-checkbox v-for="item in symptomOptions" :key="item" :value="item">{{ item }}</el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.note" type="textarea" :rows="2" maxlength="200" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts" name="EyeDropMeasurementDialog">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { createEyeDropMeasurementApi } from "@/api/modules/clinic/eyeDrop";

const props = defineProps<{
  modelValue: boolean;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  saved: [];
}>();

const symptomOptions = ["刺痛", "眼红", "异物感", "视物模糊", "眼睑色素沉着", "其他"];

const visible = computed({
  get: () => props.modelValue,
  set: value => emit("update:modelValue", value)
});

const saving = ref(false);
const form = reactive({
  measuredAt: "",
  iopLeft: undefined as number | undefined,
  iopRight: undefined as number | undefined,
  symptoms: [] as string[],
  note: ""
});

const nowText = () => {
  const now = new Date();
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
};

watch(
  () => props.modelValue,
  value => {
    if (!value) return;
    form.measuredAt = nowText();
    form.iopLeft = undefined;
    form.iopRight = undefined;
    form.symptoms = [];
    form.note = "";
  }
);

const submit = async () => {
  const hasIop = form.iopLeft != null || form.iopRight != null;
  if (!hasIop && !form.symptoms.length && !form.note.trim()) {
    ElMessage.warning("请至少填写眼压、症状或备注中的一项");
    return;
  }
  saving.value = true;
  try {
    await createEyeDropMeasurementApi({
      measuredAt: form.measuredAt,
      iopLeft: form.iopLeft ?? null,
      iopRight: form.iopRight ?? null,
      symptoms: form.symptoms,
      note: form.note.trim()
    });
    ElMessage.success("已保存");
    emit("saved");
    visible.value = false;
  } catch (error: any) {
    ElMessage.error(error?.message || "保存失败");
  } finally {
    saving.value = false;
  }
};
</script>
