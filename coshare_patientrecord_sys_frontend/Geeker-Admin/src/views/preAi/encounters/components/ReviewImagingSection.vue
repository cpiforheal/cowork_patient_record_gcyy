<template>
  <section v-if="visibleCategories.length" class="imaging-review-section">
    <header class="imaging-review-heading">
      <div>
        <strong>影像回看与补充</strong>
        <small>仅作最终复核记录，不进入病历生成</small>
      </div>
      <el-tag effect="plain">{{ visibleCategories.length }} 类资料</el-tag>
    </header>
    <div class="imaging-review-grid">
      <article v-for="category in visibleCategories" :key="category.code" class="imaging-card">
        <header class="imaging-card-head">
          <div>
            <strong>{{ category.label }}</strong>
            <small>{{ attachmentsFor(category).length }} 份资料</small>
          </div>
          <el-tag :type="category.note ? 'success' : 'info'" size="small" effect="plain">
            {{ category.note ? "已补充" : "待补充" }}
          </el-tag>
        </header>
        <div v-if="category.conclusion" class="imaging-conclusion">
          <span>现有结论</span>
          <p>{{ category.conclusion }}</p>
        </div>
        <AttachmentPreviewGallery :attachments="attachmentsFor(category)" compact @download="downloadPreAiAttachmentApi" />
        <el-input
          v-model="drafts[category.code]"
          type="textarea"
          :rows="4"
          maxlength="2000"
          show-word-limit
          :disabled="!canReview"
          placeholder="补充该影像的阅片说明、异常所见或复核意见（不进入病历生成）"
          @update:model-value="markDirty(category.code)"
        />
        <footer class="imaging-card-foot">
          <small v-if="category.note">最后保存：{{ category.updatedBy || "医师" }} · {{ category.updatedAt || "" }}</small>
          <small v-else>尚未补充</small>
          <el-button
            size="small"
            type="primary"
            :disabled="!canReview || !isDirty(category) || loading"
            :loading="savingCategory === category.code"
            @click="save(category)"
          >
            {{ !draftOf(category).trim() && category.note ? "清空补充" : "保存补充" }}
          </el-button>
        </footer>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from "vue";
import { ElMessageBox } from "element-plus";
import {
  downloadPreAiAttachmentApi,
  type PreAiAttachment,
  type PreAiImagingNote,
  type PreAiImagingReview
} from "@/api/modules/clinic";
import AttachmentPreviewGallery from "./AttachmentPreviewGallery.vue";

const props = defineProps<{
  review?: PreAiImagingReview;
  attachments: PreAiAttachment[];
  canReview: boolean;
  loading?: boolean;
  savingCategory?: string;
}>();

const emit = defineEmits<{
  save: [category: PreAiImagingNote["code"], note: string];
}>();

const drafts = reactive<Record<string, string>>({});
const dirty = reactive<Record<string, boolean>>({});

const attachmentById = computed(() => new Map(props.attachments.map(attachment => [attachment.id, attachment])));
const attachmentsFor = (category: PreAiImagingNote) =>
  category.attachmentIds
    .map(id => attachmentById.value.get(id))
    .filter((attachment): attachment is PreAiAttachment => Boolean(attachment));
const visibleCategories = computed(() =>
  (props.review?.categories || []).filter(category => attachmentsFor(category).length > 0)
);
const draftOf = (category: PreAiImagingNote) => drafts[category.code] || "";
const isDirty = (category: PreAiImagingNote) => draftOf(category).trim() !== (category.note || "").trim();

const markDirty = (category: string) => {
  dirty[category] = true;
};

watch(
  () => props.review?.categories,
  categories => {
    for (const category of categories || []) {
      const currentDraft = draftOf(category).trim();
      const savedNote = (category.note || "").trim();
      if (!(category.code in drafts) || (!dirty[category.code] && currentDraft !== savedNote)) {
        drafts[category.code] = category.note || "";
      }
      if (dirty[category.code] && currentDraft === savedNote) dirty[category.code] = false;
    }
  },
  { immediate: true, deep: true }
);

const save = (category: PreAiImagingNote) => {
  const value = draftOf(category).trim();
  if (!value && category.note) {
    ElMessageBox.confirm(`清空「${category.label}」的补充描述？`, "确认清空", {
      type: "warning",
      confirmButtonText: "清空",
      cancelButtonText: "取消"
    })
      .then(() => emit("save", category.code, ""))
      .catch(() => undefined);
    return;
  }
  emit("save", category.code, value);
};
</script>

<style scoped lang="scss">
.imaging-review-section {
  display: grid;
  gap: 12px;
  margin-top: 15px;
  padding: 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 14px;
  background: var(--el-fill-color-extra-light);
}

.imaging-review-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;

  > div {
    display: grid;
    gap: 4px;
  }

  strong {
    font-size: 16px;
  }

  small {
    color: var(--el-text-color-secondary);
  }
}

.imaging-review-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px;
}

.imaging-card {
  min-width: 0;
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: var(--el-bg-color);
}

.imaging-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;

  > div {
    display: grid;
    gap: 2px;
  }

  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.imaging-conclusion {
  padding: 8px 10px;
  border-radius: 9px;
  background: var(--el-fill-color-light);

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  p {
    max-height: 90px;
    margin: 4px 0 0;
    overflow: auto;
    line-height: 1.6;
    white-space: pre-wrap;
  }
}

.imaging-card :deep(.attachment-gallery) {
  max-height: 220px;
  padding-right: 2px;
  overflow: auto;
}

.imaging-card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;

  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
    line-height: 1.5;
  }
}

@media (max-width: 760px) {
  .imaging-review-heading,
  .imaging-card-foot {
    align-items: stretch;
    flex-direction: column;
  }

  .imaging-card-foot :deep(.el-button) {
    width: 100%;
    margin-left: 0;
  }
}
</style>
