<template>
  <section class="workflow-role-preview">
    <aside class="workflow-role-timeline" aria-label="岗位流程">
      <article
        v-for="(preview, index) in previews"
        :key="preview.key"
        class="workflow-timeline-item"
        :class="{ active: preview.key === selectedKey, complete: preview.completedCount > 0 || preview.attachmentCount > 0 }"
      >
        <button type="button" class="workflow-role-node" @click="$emit('update:selectedKey', preview.key)">
          <span class="timeline-marker" aria-hidden="true">
            <i>{{ index + 1 }}</i>
          </span>

          <span class="role-node-body">
            <span class="role-card-head">
              <strong>{{ preview.title }}</strong>

              <el-tag :type="preview.statusTone" effect="plain" size="small">{{ preview.statusLabel }}</el-tag>
            </span>

            <small>{{ preview.subtitle }}</small>

            <span class="role-card-stats">
              <em>已填 {{ preview.completedCount }}/{{ preview.totalCount || 0 }}</em>

              <em>图片 {{ preview.imageCount }}</em>

              <em>附件 {{ preview.attachmentCount }}</em>
            </span>
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

      <article v-else class="role-workspace-paper">
        <header class="report-head">
          <div>
            <span>岗位流程工作区</span>

            <h3>{{ activePreview.title }}</h3>

            <p>{{ activePreview.description }}</p>
          </div>

          <el-tag :type="activePreview.statusTone" effect="plain">{{ activePreview.statusLabel }}</el-tag>
        </header>

        <section class="role-mode-bar">
          <el-radio-group v-model="rolePanelMode" size="small">
            <el-radio-button label="maintain">维护基础信息</el-radio-button>

            <el-radio-button label="preview">实时预览</el-radio-button>
          </el-radio-group>

          <div class="report-actions">
            <el-button type="primary" plain @click="$emit('edit', activePreview)">
              {{ activePreview.primaryActionLabel }}
            </el-button>

            <el-button plain :disabled="!activePreview.attachmentCount" @click="$emit('openAttachments', activePreview)">
              查看图片/附件
            </el-button>
          </div>
        </section>

        <section v-if="rolePanelMode === 'maintain'" class="maintain-panel">
          <div class="maintain-head">
            <div>
              <strong>{{ activePreview.key === "inspection" ? "检查室优先维护" : "本岗位维护内容" }}</strong>

              <span>
                {{
                  activePreview.key === "inspection"
                    ? "先上传图片/视频，再补充镜下所见和初步检查描述。"
                    : "只维护当前岗位负责字段，最终目标病历仍由医生汇总生成。"
                }}
              </span>
            </div>

            <el-button
              type="primary"
              :disabled="!editableMaintenanceFields.length"
              @click="
                $emit(
                  'saveMaintainedFields',
                  editableMaintenanceFields.map(item => item.field.key)
                )
              "
            >
              保存本岗位维护内容
            </el-button>
          </div>

          <el-empty v-if="!maintenanceFields.length" description="当前岗位暂无可直接维护字段，请进入目标病历或完整档案处理。" />

          <div v-else class="maintain-field-list">
            <article
              v-for="item in maintenanceFields"
              :key="item.field.key"
              class="maintain-field-card"
              :class="{ featured: activePreview.key === 'inspection' && item.field.key === 'inspectionImages' }"
            >
              <header>
                <div>
                  <strong>{{ item.field.label }}</strong>

                  <small>{{ item.sectionTitle }}</small>
                </div>

                <el-tag v-if="!isFieldEditable(item.field)" effect="plain" size="small">只读</el-tag>
                <el-tag v-else-if="item.field.required" type="warning" effect="plain" size="small">必填</el-tag>
              </header>

              <ArchiveFieldRenderer
                :field="item.field"
                :model-value="fieldValues[item.field.key]"
                :disabled="!isFieldEditable(item.field)"
                :issue="issueForField(item.field)"
                :attachments="matchedAttachments(item.field.key)"
                :select-options="selectOptions(item.field)"
                :followup-records="followupRecords"
                :role-label="roleLabel"
                :can-open-attachment="canOpenAttachment"
                :is-image-attachment="isImageAttachment"
                :attachment-preview-url="attachmentPreviewUrl"
                :open-attachment="openAttachment"
                :textarea-rows="activePreview.key === 'inspection' ? 3 : 2"
                @update:model-value="$emit('updateField', item.field.key, $event)"
                @update-lab-metric="(field, value) => $emit('updateLabMetric', field, value)"
                @upload="(field, files, remark) => $emit('upload', field, files, remark)"
                @add-followup-record="$emit('addFollowupRecord')"
                @remove-followup-record="$emit('removeFollowupRecord', $event)"
              />
            </article>
          </div>
        </section>

        <section v-else class="preview-panel">
          <LabReportPreview
            v-if="activePreview.key === 'lab'"
            :field-values="fieldValues"
            :patient-name="fieldValues.patientName"
            :patient-gender="fieldValues.gender"
            :visit-no="fieldValues.visitNo"
            compact
          />

          <template v-else>
            <section v-if="activePreview.contextItems.length" class="report-section">
              <div class="report-section-title">
                <strong>{{ activePreview.contextTitle }}</strong>

                <span>{{ activePreview.contextItems.length }} 项</span>
              </div>

              <button
                v-for="item in activePreview.contextItems"
                :key="`context-${item.source}-${item.key}`"
                type="button"
                class="report-field-row"
                @click="$emit('focusField', item.key)"
              >
                <span>{{ item.label }}</span>

                <em>{{ item.value }}</em>
              </button>
            </section>

            <section class="report-section">
              <div class="report-section-title">
                <strong>已形成内容</strong>

                <span>{{ activePreview.summaryItems.length }} 项</span>
              </div>

              <el-empty v-if="!activePreview.summaryItems.length" description="该岗位尚未形成可预览内容" />

              <button
                v-for="item in activePreview.summaryItems"
                v-else
                :key="`${item.source}-${item.key}`"
                type="button"
                class="report-field-row"
                @click="$emit('focusField', item.key)"
              >
                <span>
                  <strong>{{ item.label }}</strong>

                  <small>{{ item.section }} · {{ item.source === "medicalRecord" ? "目标病历" : "完整档案" }}</small>
                </span>

                <em>{{ item.value }}</em>
              </button>
            </section>
          </template>

          <section class="report-section">
            <div class="report-section-title">
              <strong>待补与下一步</strong>

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
              class="report-field-row pending"
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
              class="report-field-row pending"
              @click="$emit('focusField', task.fieldKey)"
            >
              <span>
                <strong>{{ task.fieldLabel }}</strong>

                <small>{{ task.sectionTitle }} · {{ task.reason }}</small>
              </span>

              <el-tag :type="task.statusTone" effect="plain" size="small">{{ task.statusLabel }}</el-tag>
            </button>
          </section>

          <section class="report-section">
            <div class="report-section-title">
              <strong>图片与附件</strong>

              <span>{{ activePreview.imageCount }} 张图片 · {{ activePreview.attachmentCount }} 份附件</span>
            </div>

            <el-empty v-if="!activePreview.attachments.length" description="该岗位暂无附件证据" />

            <button
              v-for="attachment in activePreview.attachments"
              v-else
              :key="attachment.key"
              type="button"
              class="report-attachment-row"
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
        </section>
      </article>
    </main>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { RecordAttachment, RecordField, UserRole } from "@/config/fieldPermissions";
import ArchiveFieldRenderer from "./ArchiveFieldRenderer.vue";
import LabReportPreview from "./LabReportPreview.vue";
import type { FieldIssue, FollowupRecord } from "./types";
import type { WorkflowRolePreview } from "../composables/usePatientRolePreview";

type RolePanelMode = "maintain" | "preview";
type WorkflowRoleMaintenanceField = {
  sectionTitle: string;
  field: RecordField;
};

const props = defineProps<{
  previews: WorkflowRolePreview[];
  selectedKey: string;
  currentRole: UserRole;
  maintenanceFields: WorkflowRoleMaintenanceField[];
  fieldValues: Record<string, string>;
  followupRecords: FollowupRecord[];
  isFieldEditable: (field: RecordField) => boolean;
  issueForField: (field: RecordField) => FieldIssue | undefined;
  matchedAttachments: (fieldKey: string) => RecordAttachment[];
  selectOptions: (field: RecordField) => string[];
  roleLabel: (role?: string) => string;
  canOpenAttachment: (attachmentOrUrl?: RecordAttachment | string) => boolean;
  isImageAttachment: (attachment: RecordAttachment) => boolean;
  attachmentPreviewUrl: (url?: string) => string;
  openAttachment: (url: string) => void | Promise<void>;
}>();

defineEmits<{
  "update:selectedKey": [key: string];
  updateField: [key: string, value: string];
  updateLabMetric: [field: RecordField, value: string];
  upload: [field: RecordField, files: File[], remark: string];
  saveMaintainedFields: [fieldKeys: string[]];
  addFollowupRecord: [];
  removeFollowupRecord: [id: string];
  edit: [preview: WorkflowRolePreview];
  openAttachments: [preview: WorkflowRolePreview];
  focusField: [fieldKey: string];
}>();

const activePreview = computed(() => props.previews.find(preview => preview.key === props.selectedKey));
const rolePanelMode = ref<RolePanelMode>("preview");
const editableMaintenanceFields = computed(() => props.maintenanceFields.filter(item => props.isFieldEditable(item.field)));

watch(
  () =>
    [activePreview.value?.key, activePreview.value?.canEdit, props.currentRole, editableMaintenanceFields.value.length] as const,
  () => {
    const preview = activePreview.value;
    const reviewerRole = props.currentRole === "admin" || props.currentRole === "doctor";
    rolePanelMode.value = preview?.canEdit && !reviewerRole && editableMaintenanceFields.value.length ? "maintain" : "preview";
  },
  { immediate: true }
);
</script>

<style scoped lang="scss">
.workflow-role-preview {
  display: grid;
  grid-template-columns: minmax(300px, 360px) minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

.workflow-role-timeline {
  position: relative;
  display: grid;
  gap: 0;
  min-width: 0;
}

.workflow-timeline-item {
  position: relative;
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 0 0 14px 34px;

  &::before {
    position: absolute;
    top: 26px;
    bottom: -2px;
    left: 14px;
    width: 2px;
    content: "";
    background: #dbe3ef;
  }

  &:last-child {
    padding-bottom: 0;

    &::before {
      display: none;
    }
  }

  &.active {
    .timeline-marker {
      color: #fff;
      background: #2563eb;
      border-color: #2563eb;
      box-shadow: 0 0 0 5px rgb(37 99 235 / 12%);
    }

    .workflow-role-node {
      border-color: #2563eb;
      box-shadow: 0 10px 28px rgb(37 99 235 / 12%);
    }
  }

  &.complete:not(.active) .timeline-marker {
    color: #047857;
    background: #ecfdf5;
    border-color: #34d399;
  }
}

.workflow-role-node {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 10px;
  width: 100%;
  min-width: 0;
  padding: 13px 14px;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--hos-border);
  border-radius: 8px;
  box-shadow: var(--hos-shadow-soft);
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease;

  &:hover {
    border-color: #93c5fd;
  }
}

.timeline-marker {
  position: absolute;
  top: 10px;
  left: 0;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  color: #64748b;
  background: #fff;
  border: 2px solid #cbd5e1;
  border-radius: 999px;

  i {
    font-size: 12px;
    font-style: normal;
    font-weight: 700;
    line-height: 1;
  }
}

.role-node-body {
  display: grid;
  gap: 7px;
  min-width: 0;

  > small {
    min-width: 0;
    color: var(--hos-text-secondary);
    line-height: 1.45;
    overflow-wrap: anywhere;
  }
}

.role-card-head,
.role-card-stats,
.report-section-title,
.report-actions {
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
  font-size: 16px;
}

.role-card-stats {
  flex-wrap: wrap;
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
  padding: 8px 10px;
  background: #f8fafc;
  border: 1px dashed var(--hos-border-light);
  border-radius: 8px;
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
  border-radius: 6px;

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
  padding: 18px;
  background: #f8fafc;
  border: 1px solid var(--hos-border);
  border-radius: 8px;
  box-shadow: var(--hos-shadow-soft);
}

.role-workspace-paper {
  display: grid;
  gap: 16px;
  max-width: 980px;
  min-width: 0;
  padding: 22px;
  margin: 0 auto;
  color: #111827;
  background: #fff;
  border: 1px solid #1f2937;
  box-shadow: 0 8px 24px rgb(15 23 42 / 8%);
}

.role-mode-bar,
.maintain-head {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  min-width: 0;
}

.report-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;

  span,
  p {
    color: #64748b;
  }

  h3,
  p {
    margin: 0;
  }

  h3 {
    margin-top: 4px;
    color: #111827;
    font-size: 22px;
  }
}

.report-actions {
  justify-content: flex-start;
  flex-wrap: wrap;
}

.role-mode-bar {
  flex-wrap: wrap;
  padding-bottom: 12px;
  border-bottom: 1px solid #1f2937;
}

.maintain-panel,
.preview-panel,
.maintain-field-list {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.maintain-head {
  align-items: flex-start;
  padding: 12px 14px;
  background: #f8fafc;
  border: 1px solid #dbe4ef;
  border-radius: 8px;

  > div {
    display: grid;
    gap: 4px;
    min-width: 0;
  }

  strong {
    color: #0f172a;
    font-size: 15px;
  }

  span {
    color: #64748b;
    line-height: 1.6;
  }
}

.maintain-field-card {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 14px;
  background: #fff;
  border: 1px solid #dbe4ef;
  border-radius: 8px;

  &.featured {
    border-color: #93c5fd;
    box-shadow: 0 10px 24px rgb(37 99 235 / 10%);
  }

  > header {
    display: flex;
    gap: 10px;
    align-items: flex-start;
    justify-content: space-between;
    min-width: 0;

    > div {
      display: grid;
      gap: 3px;
      min-width: 0;
    }

    strong {
      color: #172554;
      overflow-wrap: anywhere;
    }

    small {
      color: #64748b;
    }
  }
}

.report-section {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding-top: 2px;
}

.report-section-title {
  padding: 7px 10px;
  background: #f3f4f6;
  border: 1px solid #1f2937;

  strong {
    color: #111827;
  }

  span {
    color: #475569;
    font-size: 12px;
  }
}

.report-field-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 7px;
  width: 100%;
  padding: 10px 12px;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid #d1d5db;
  border-radius: 0;

  &:hover {
    background: #f8fafc;
  }

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
    color: #64748b;
  }

  em {
    display: -webkit-box;
    overflow: hidden;
    color: #111827;
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

.report-attachment-row {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 8px;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid #d1d5db;
  border-radius: 0;

  &:hover {
    background: #f8fafc;
  }

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
    color: #1d4ed8;
    background: #eff6ff;
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
    color: #64748b;
  }
}

@media (max-width: 960px) {
  .workflow-role-preview {
    grid-template-columns: 1fr;
  }

  .workflow-role-detail {
    min-height: 360px;
  }

  .role-workspace-paper {
    padding: 16px;
  }
}

@media (max-width: 640px) {
  .workflow-role-preview {
    gap: 12px;
  }

  .workflow-timeline-item {
    padding-left: 30px;
  }

  .role-card-head,
  .report-head,
  .report-section-title,
  .role-mode-bar,
  .maintain-head,
  .maintain-field-card > header {
    align-items: flex-start;
    flex-direction: column;
  }

  .report-field-row.pending {
    grid-template-columns: 1fr;
  }
}
</style>
