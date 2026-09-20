<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="520px"
    destroy-on-close
    @closed="reset"
  >
    <div class="fuc-form">
      <label class="fuc-label">复诊原因（必填）</label>
      <el-select v-model="form.reason" filterable allow-create placeholder="选择常用原因或直接输入">
        <el-option v-for="item in REASONS" :key="item" :label="item" :value="item" />
      </el-select>
      <label class="fuc-label">病情描述</label>
      <el-input
        v-model="form.conditionNote"
        type="textarea"
        :rows="4"
        placeholder="本次复诊查体所见、创面情况、处理措施等"
      />
      <label class="fuc-label">下次安排复查时间</label>
      <el-date-picker
        v-model="form.nextReviewDate"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="选择或留空"
        style="width: 100%"
      />
      <label class="fuc-label">图片信息采集</label>
      <el-upload
        :file-list="imageFileList"
        list-type="picture-card"
        multiple
        accept="image/*"
        :auto-upload="false"
        :on-change="onImageChange"
        :on-remove="onImageRemove"
      >
        <el-icon><Plus /></el-icon>
      </el-upload>
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存复诊记录</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts" name="FollowUpCreateDialog">
// 新增复诊对话框（自 FollowUpTimeline 的新增表单抽取复用）：检查室/医生在任意入口就地记录复诊
import { reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import { addFollowUpVisitImageApi, createFollowUpVisitApi, type FollowUpVisit } from "@/api/modules/clinic/followUp";

const REASONS = ["术后复查", "换药", "拆线", "不适随诊", "复查结果解读", "其他"];

const props = defineProps<{
  modelValue: boolean;
  patientCaseId: string;
  encounterId?: string;
  patientName?: string;
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "created", visit: FollowUpVisit): void;
}>();

const visible = ref(props.modelValue);
watch(
  () => props.modelValue,
  value => (visible.value = value)
);
watch(visible, value => emit("update:modelValue", value));

const dialogTitle = `新增复诊记录${props.patientName ? ` · ${props.patientName}` : ""}`;
const saving = ref(false);
const imageFileList = ref<{ name: string; raw: File }[]>([]);
const form = reactive({ reason: "", conditionNote: "", nextReviewDate: "" });

const onImageChange = (_file: any, fileList: any[]) => {
  imageFileList.value = fileList.map(item => ({ name: item.name, raw: item.raw as File }));
};
const onImageRemove = (_file: any, fileList: any[]) => {
  imageFileList.value = fileList.map(item => ({ name: item.name, raw: item.raw as File }));
};

const readAsDataUrl = (file: File) =>
  new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(new Error("图片读取失败"));
    reader.readAsDataURL(file);
  });

const reset = () => {
  form.reason = "";
  form.conditionNote = "";
  form.nextReviewDate = "";
  imageFileList.value = [];
};

const submit = async () => {
  if (!form.reason.trim()) {
    ElMessage.warning("请填写复诊原因");
    return;
  }
  saving.value = true;
  try {
    const { data } = await createFollowUpVisitApi({
      patientCaseId: props.patientCaseId,
      encounterId: props.encounterId || undefined,
      reason: form.reason.trim(),
      conditionNote: form.conditionNote.trim(),
      nextReviewDate: form.nextReviewDate || "",
      images: []
    });
    for (const item of imageFileList.value) {
      const dataUrl = await readAsDataUrl(item.raw);
      await addFollowUpVisitImageApi(data.id, { fileName: item.name, dataUrl });
    }
    ElMessage.success(`第 ${data.seq} 次复诊记录已保存`);
    emit("created", data);
    visible.value = false;
  } catch (error: any) {
    ElMessage.error(error?.message || "复诊记录保存失败");
  } finally {
    saving.value = false;
  }
};
</script>

<style scoped lang="scss">
.fuc-form {
  display: grid;
}
.fuc-label {
  display: block;
  margin: 10px 0 4px;
  font-size: 12px;
  color: var(--el-text-color-regular);
}
</style>
