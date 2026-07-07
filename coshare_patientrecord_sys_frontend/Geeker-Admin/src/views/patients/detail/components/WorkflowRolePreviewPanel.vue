<template>
  <section class="workflow-role-preview">
    <aside class="workflow-role-list" aria-label="岗位流程">
      <article v-for="preview in previews" :key="preview.key" class="role-card-stack">
        <button
          type="button"
          class="workflow-role-card"
          :class="{ active: preview.key === selectedKey }"
          @click="$emit('update:selectedKey', preview.key)"
        >
          <span class="role-card-head">
            <strong>{{ preview.title }}</strong>

            <el-tag :type="preview.statusTone" effect="plain" size="small">{{ preview.statusLabel }}</el-tag>
          </span>

          <small>{{ preview.subtitle }}</small>

          <span class="role-card-stats">
            <em>已填 {{ preview.completedCount }}/{{ preview.totalCount || 0 }}</em>

            <em>附件 {{ preview.attachmentCount }}</em>
          </span>
        </button>

        <section v-if="preview.attachments.length" class="role-card-attachments" aria-label="岗位图片与附件摘要">
          <button
            v-for="attachment in preview.attachments.slice(0, 4)"
            :key="attachment.key"
            type="button"
            class="role-attachment-chip"
            @click="$emit('openAttachments', preview)"
          >
            <img
              v-if="attachment.isImage && attachment.url"
              :src="attachment.url"
              :alt="attachment.title || attachment.fileName"
            />

            <i v-else>{{ attachment.fileName || attachment.title }}</i>
          </button>
        </section>
      </article>
    </aside>

    <main class="workflow-role-detail">
      <el-empty v-if="!activePreview" description="点击左侧岗位卡片，查看该岗位已完成内容、附件证据和下一步处理建议" />

      <article v-else class="role-detail-panel">
        <header class="role-detail-head">
          <div>
            <span>岗位预览</span>

            <h3>{{ activePreview.title }}</h3>

            <p>{{ activePreview.description }}</p>
          </div>

          <el-tag :type="activePreview.statusTone" effect="plain">{{ activePreview.statusLabel }}</el-tag>
        </header>

        <section class="role-detail-actions">
          <el-button type="primary" plain @click="$emit('edit', activePreview)">
            {{ activePreview.primaryActionLabel }}
          </el-button>

          <el-button plain :disabled="!activePreview.attachmentCount" @click="$emit('openAttachments', activePreview)">
            查看图片/附件
          </el-button>
        </section>

        <section class="role-preview-grid">
          <article class="role-preview-block">
            <div class="role-preview-title">
              <strong>已完成信息</strong>

              <span>{{ activePreview.summaryItems.length }} 项</span>
            </div>

            <el-empty v-if="!activePreview.summaryItems.length" description="该岗位尚未形成可预览内容" />

            <button
              v-for="item in activePreview.summaryItems"
              v-else
              :key="`${item.source}-${item.key}`"
              type="button"
              class="role-field-row"
              @click="$emit('focusField', item.key)"
            >
              <span>
                <strong>{{ item.label }}</strong>

                <small>{{ item.section }} · {{ item.source === "medicalRecord" ? "目标病历" : "完整档案" }}</small>
              </span>

              <em>{{ item.value }}</em>
            </button>
          </article>

          <article class="role-preview-block">
            <div class="role-preview-title">
              <strong>待补/待复核</strong>

              <span>{{ activePreview.missingCount }} 项</span>
            </div>

            <el-empty
              v-if="!activePreview.missingItems.length && !activePreview.taskItems.length"
              description="暂无明确待处理项"
            />

            <button
              v-for="item in activePreview.missingItems"
              v-else
              :key="`${item.source}-${item.key}`"
              type="button"
              class="role-field-row pending"
              @click="$emit('focusField', item.key)"
            >
              <span>
                <strong>{{ item.label }}</strong>

                <small>{{ item.section }} · 必填缺失</small>
              </span>

              <el-tag type="warning" effect="plain" size="small">待补</el-tag>
            </button>

            <button
              v-for="task in activePreview.taskItems"
              :key="`task-${task.sectionKey}-${task.fieldKey}`"
              type="button"
              class="role-field-row pending"
              @click="$emit('focusField', task.fieldKey)"
            >
              <span>
                <strong>{{ task.fieldLabel }}</strong>

                <small>{{ task.sectionTitle }} · {{ task.reason }}</small>
              </span>

              <el-tag :type="task.statusTone" effect="plain" size="small">{{ task.statusLabel }}</el-tag>
            </button>
          </article>
        </section>

        <section class="role-attachment-preview">
          <div class="role-preview-title">
            <strong>图片与附件</strong>

            <span>{{ activePreview.imageCount }} 张图片 · {{ activePreview.attachmentCount }} 份附件</span>
          </div>

          <el-empty v-if="!activePreview.attachments.length" description="该岗位暂无附件证据" />

          <button
            v-for="attachment in activePreview.attachments"
            v-else
            :key="attachment.key"
            type="button"
            class="role-attachment-row"
            @click="$emit('openAttachments', activePreview)"
          >
            <img
              v-if="attachment.isImage && attachment.url"
              :src="attachment.url"
              :alt="attachment.title || attachment.fileName"
            />

            <span v-else>{{ attachment.fileName?.slice(0, 1) || "附" }}</span>

            <em>
              <strong>{{ attachment.title || attachment.fieldLabel || "附件资料" }}</strong>

              <small>{{ attachment.fileName }} · {{ attachment.uploadedAt || "待记录时间" }}</small>
            </em>
          </button>
        </section>
      </article>
    </main>
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { WorkflowRolePreview } from "../composables/usePatientRolePreview";

const props = defineProps<{
  previews: WorkflowRolePreview[];
  selectedKey: string;
}>();

defineEmits<{
  "update:selectedKey": [key: string];
  edit: [preview: WorkflowRolePreview];
  openAttachments: [preview: WorkflowRolePreview];
  focusField: [fieldKey: string];
}>();

const activePreview = computed(() => props.previews.find(preview => preview.key === props.selectedKey));
</script>

<style scoped lang="scss">
.workflow-role-preview {
  display: grid;
  grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.workflow-role-list {
  display: grid;
  gap: 10px;
}

.role-card-stack {
  display: grid;
  gap: 6px;
}

.workflow-role-card {
  display: grid;
  gap: 8px;
  width: 100%;
  min-width: 0;
  padding: 14px;
  text-align: left;
  cursor: pointer;
  background: var(--hos-panel);
  border: 1px solid var(--hos-border);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-soft);
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;

  &.active {
    border-color: var(--hos-border-interactive);
    box-shadow:
      inset 4px 0 0 var(--hos-primary),
      var(--hos-shadow-soft);
    transform: translateY(-1px);
  }

  small {
    color: var(--hos-text-secondary);
    line-height: 1.45;
  }
}

.role-card-head,
.role-card-stats,
.role-preview-title,
.role-detail-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  min-width: 0;
}

.role-card-head strong {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--hos-text-primary);
  font-size: 15px;
}

.role-card-stats {
  justify-content: flex-start;
  color: var(--hos-text-secondary);
  font-size: 12px;

  em {
    font-style: normal;
  }
}

.role-card-attachments {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  min-width: 0;
  padding: 8px;
  background: #f8fafc;
  border: 1px dashed var(--hos-border-light);
  border-radius: var(--hos-radius-sm);
}

.role-attachment-chip {
  display: grid;
  place-items: center;
  width: 100%;
  height: 42px;
  overflow: hidden;
  color: var(--hos-text-secondary);
  cursor: pointer;
  background: var(--hos-glass);
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-sm);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  i {
    max-width: 100%;
    padding: 0 5px;
    overflow: hidden;
    font-size: 11px;
    font-style: normal;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.workflow-role-detail {
  min-width: 0;
  min-height: 520px;
  padding: 16px;
  background: var(--hos-panel);
  border: 1px solid var(--hos-border);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-soft);
}

.role-detail-panel {
  display: grid;
  gap: 14px;
}

.role-detail-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;

  span,
  p {
    color: var(--hos-text-secondary);
  }

  h3,
  p {
    margin: 0;
  }

  h3 {
    margin-top: 4px;
    color: var(--hos-text-primary);
    font-size: 22px;
  }
}

.role-detail-actions {
  justify-content: flex-start;
  flex-wrap: wrap;
}

.role-preview-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.role-preview-block,
.role-attachment-preview {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 12px;
  background: var(--hos-glass);
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);
}

.role-preview-title {
  strong {
    color: var(--hos-text-primary);
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 12px;
  }
}

.role-field-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 6px;
  width: 100%;
  padding: 10px;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-sm);

  span {
    display: grid;
    gap: 2px;
    min-width: 0;
  }

  strong,
  em {
    overflow-wrap: anywhere;
  }

  small {
    color: var(--hos-text-secondary);
  }

  em {
    display: -webkit-box;
    overflow: hidden;
    color: var(--hos-text-primary);
    font-size: 13px;
    font-style: normal;
    line-height: 1.55;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 3;
  }

  &.pending {
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
  }
}

.role-attachment-row {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 8px;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-sm);

  img,
  > span {
    width: 58px;
    height: 44px;
    border-radius: var(--hos-radius-sm);
  }

  img {
    object-fit: cover;
  }

  > span {
    display: grid;
    place-items: center;
    color: var(--hos-primary-deep);
    background: var(--hos-primary-soft);
  }

  em {
    display: grid;
    gap: 3px;
    min-width: 0;
    font-style: normal;
  }

  strong,
  small {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--hos-text-secondary);
  }
}

@media (max-width: 960px) {
  .workflow-role-preview,
  .role-preview-grid {
    grid-template-columns: 1fr;
  }

  .workflow-role-detail {
    min-height: 360px;
  }
}
</style>
