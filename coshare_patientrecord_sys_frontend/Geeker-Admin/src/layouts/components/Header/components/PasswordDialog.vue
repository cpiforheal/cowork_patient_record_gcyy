<template>
  <el-dialog v-model="dialogVisible" title="修改密码" width="500px" draggable destroy-on-close>
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" show-password autocomplete="new-password" />
      </el-form-item>
      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" />
      </el-form-item>
    </el-form>

    <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </span>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import { changePasswordApi } from "@/api/modules/login";

const dialogVisible = ref(false);
const submitting = ref(false);
const formRef = ref<FormInstance>();
const form = reactive({
  newPassword: "",
  confirmPassword: ""
});

const rules = reactive<FormRules>({
  newPassword: [
    { required: true, message: "请输入新密码", trigger: "blur" },
    { min: 6, message: "新密码至少 6 位", trigger: "blur" }
  ],
  confirmPassword: [
    { required: true, message: "请再次输入新密码", trigger: "blur" },
    {
      validator: (_rule, value, callback) => {
        if (value === form.newPassword) return callback();
        callback(new Error("两次输入的新密码不一致"));
      },
      trigger: "blur"
    }
  ]
});

const resetForm = () => {
  form.newPassword = "";
  form.confirmPassword = "";
  formRef.value?.clearValidate();
};

const openDialog = () => {
  resetForm();
  dialogVisible.value = true;
};

const submit = async () => {
  await formRef.value?.validate();
  submitting.value = true;
  try {
    await changePasswordApi({ newPassword: form.newPassword });
    ElMessage.success("密码已修改");
    dialogVisible.value = false;
  } finally {
    submitting.value = false;
  }
};

defineExpose({ openDialog });
</script>
