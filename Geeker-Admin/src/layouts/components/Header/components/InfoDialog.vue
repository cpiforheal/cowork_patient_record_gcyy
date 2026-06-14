<template>
  <el-dialog v-model="dialogVisible" title="账号信息" width="500px" draggable>
    <el-descriptions :column="1" border>
      <el-descriptions-item label="账号名称">{{ userStore.userInfo.name }}</el-descriptions-item>
      <el-descriptions-item label="所属科室">{{ userStore.userInfo.department }}</el-descriptions-item>
      <el-descriptions-item label="角色标识">{{ userStore.userInfo.role }}</el-descriptions-item>
    </el-descriptions>
    <template #footer>
      <span class="dialog-footer">
        <el-button type="primary" @click="dialogVisible = false">知道了</el-button>
      </span>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useUserStore } from "@/stores/modules/user";

const userStore = useUserStore();
const dialogVisible = ref(false);
const openDialog = () => {
  dialogVisible.value = true;
};

defineExpose({ openDialog });
</script>
