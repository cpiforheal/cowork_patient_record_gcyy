<template>
  <footer class="assistant-composer-shell">
    <AssistantAttachmentBar
      :attachments="attachments"
      :format-file-size="formatFileSize"
      @remove="$emit('removeAttachment', $event)"
    />

    <section class="assistant-composer">
      <input ref="fileInputRef" type="file" accept="image/*" multiple hidden @change="$emit('fileChange', $event)" />
      <el-dropdown trigger="click" placement="top-start" @command="$emit('materialCommand', $event)">
        <el-button circle class="material-button" aria-label="添加材料">
          <el-icon><Plus /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="upload">上传图片</el-dropdown-item>
            <el-dropdown-item command="context" :disabled="!hasContext">
              {{ includeContext ? "关闭当前页面上下文" : "带入当前页面上下文" }}
            </el-dropdown-item>
            <el-dropdown-item command="clearMaterials" :disabled="!canClearMaterials">清除材料</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <el-input
        :model-value="promptText"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 6 }"
        maxlength="1600"
        resize="none"
        placeholder="输入问题，Ctrl + Enter 发送。可以问院内流程、系统操作、病历缺项、质控建议或管理风险。"
        @update:model-value="updatePrompt"
        @keydown.ctrl.enter.prevent="$emit('submit')"
      />
      <el-button
        type="primary"
        class="send-button"
        :icon="Promotion"
        :loading="loading"
        :disabled="!canSend"
        @click="$emit('submit')"
      >
        发送
      </el-button>
    </section>

    <div class="composer-meta">
      <span>AI 仅供院内辅助，不替代医生诊断、处方、质控结论或正式病历。</span>
      <div>
        <el-button link :disabled="!hasConversationContent" @click="$emit('clearConversation')">清空会话</el-button>
        <span v-if="lastMeta">{{ lastMeta }}</span>
      </div>
    </div>
  </footer>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { Plus, Promotion } from "@element-plus/icons-vue";
import AssistantAttachmentBar from "./AssistantAttachmentBar.vue";

type LocalAttachment = {
  id: string;
  name: string;
  type?: string;
  size?: number;
  dataUrl?: string;
  source?: string;
};

const props = defineProps<{
  attachments: LocalAttachment[];
  promptText: string;
  lastMeta: string;
  messageCount: number;
  loading: boolean;
  canSend: boolean;
  hasContext: boolean;
  includeContext: boolean;
  formatFileSize: (size?: number) => string;
}>();

const emit = defineEmits<{
  "update:promptText": [value: string];
  fileChange: [event: Event];
  materialCommand: [command: string | number | object];
  removeAttachment: [id: string];
  submit: [];
  clearConversation: [];
}>();

const fileInputRef = ref<HTMLInputElement>();
const hasConversationContent = computed(() => Boolean(props.messageCount || props.promptText || props.attachments.length));
const canClearMaterials = computed(() => Boolean(props.attachments.length || props.includeContext));
const updatePrompt = (value: string | number) => emit("update:promptText", String(value || ""));

defineExpose({
  openFileDialog: () => fileInputRef.value?.click()
});
</script>

<style scoped lang="scss">
.assistant-composer-shell {
  padding: 12px 24px 18px;
  border-top: 1px solid var(--hos-line, #dfe9e2);
  background: rgb(255 255 255 / 0.78);
}

.assistant-composer {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 10px;
  align-items: end;
  padding: 10px;
  border: 1px solid rgb(77 123 99 / 0.2);
  border-radius: 8px;
  background: #fff;
  box-shadow:
    0 18px 42px rgb(45 76 59 / 0.12),
    inset 0 1px 0 rgb(255 255 255 / 0.9);

  :deep(.el-textarea__inner) {
    min-height: 48px !important;
    border: 0;
    box-shadow: none;
    font-size: 15px;
  }
}

.material-button {
  margin-bottom: 4px;
}

.send-button {
  min-width: 88px;
  margin-bottom: 4px;
}

.composer-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

@media (max-width: 860px) {
  .assistant-composer-shell {
    padding-right: 16px;
    padding-left: 16px;
  }

  .assistant-composer {
    grid-template-columns: auto 1fr;

    .send-button {
      grid-column: 1 / -1;
      width: 100%;
    }
  }

  .composer-meta {
    flex-direction: column;
  }
}
</style>
