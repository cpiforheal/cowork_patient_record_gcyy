<template>
  <el-dialog v-model="dialogVisible" title="修改密码" width="500px" draggable>
    <el-alert title="当前为前端原型阶段，正式密码修改会接入后端账号接口。" type="info" show-icon :closable="false" />
    <template #footer>
      <span class="dialog-footer">
        <el-button type="primary" @click="dialogVisible = false">知道了</el-button>
      </span>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from "vue";

const dialogVisible = ref(false);
const openDialog = () => {
  dialogVisible.value = true;
};

defineExpose({ openDialog });
</script>
