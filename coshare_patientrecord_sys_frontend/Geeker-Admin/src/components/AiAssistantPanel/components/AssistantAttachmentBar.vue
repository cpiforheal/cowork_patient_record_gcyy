<template>
  <section v-if="attachments.length" class="composer-files">
    <div v-for="file in attachments" :key="file.id" class="composer-file">
      <img v-if="file.dataUrl" :src="file.dataUrl" :alt="file.name" />
      <div>
        <strong>{{ file.name }}</strong>
        <small>{{ file.type || "图片" }} · {{ formatFileSize(file.size) }}</small>
      </div>
      <el-button text circle aria-label="移除图片" @click="$emit('remove', file.id)">
        <el-icon><Close /></el-icon>
      </el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { Close } from "@element-plus/icons-vue";

type LocalAttachment = {
  id: string;
  name: string;
  type?: string;
  size?: number;
  dataUrl?: string;
  source?: string;
};

defineProps<{
  attachments: LocalAttachment[];
  formatFileSize: (size?: number) => string;
}>();

defineEmits<{
  remove: [id: string];
}>();
</script>

<style scoped lang="scss">
.composer-files {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 10px;
}

.composer-file {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 180px;
  padding: 8px;
  border: 1px solid var(--hos-line, #dfe9e2);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.78);

  img {
    width: 42px;
    height: 42px;
    border-radius: 6px;
    object-fit: cover;
  }

  strong,
  small {
    display: block;
    overflow: hidden;
    max-width: 170px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--el-text-color-secondary);
  }
}
</style>
