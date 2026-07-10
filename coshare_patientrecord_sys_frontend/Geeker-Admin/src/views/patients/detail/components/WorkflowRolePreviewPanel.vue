<template>
  <section class="workflow-role-preview">
    <aside class="workflow-role-timeline" aria-label="岗位流程">
      <article
        v-for="(preview, index) in previews"
        :key="preview.key"
        class="workflow-timeline-item"
        :class="[
          `runtime-${preview.runtimeStatus}`,
          {
            active: preview.key === selectedKey,
            complete: preview.runtimeStatus === 'done' || preview.runtimeStatus === 'archived',
            actionable: preview.taskSummary.canHandle
          }
        ]"
      >
        <button
          type="button"
          class="workflow-role-node"
          :aria-current="preview.key === selectedKey ? 'step' : undefined"
          :aria-pressed="preview.key === selectedKey"
          @click="$emit('update:selectedKey', preview.key)"
        >
          <span class="timeline-marker" aria-hidden="true">
            <i>{{ index + 1 }}</i>
          </span>

          <span class="role-node-body">
            <span class="role-card-head">
              <strong>{{ preview.title }}</strong>

              <el-tag :type="preview.statusTone" effect="plain" size="small">{{ preview.runtimeStatusLabel }}</el-tag>
            </span>

            <small>{{ preview.subtitle }}</small>

            <span class="role-task-goal">{{ preview.taskSummary.primaryTodo || preview.processGoal }}</span>

            <span class="role-card-stats">
              <em>已填 {{ preview.completedCount }}/{{ preview.totalCount || 0 }}</em>

              <em v-if="preview.taskSummary.todoCount">待办 {{ preview.taskSummary.todoCount }}</em>

              <em v-if="preview.taskSummary.blockingCount">阻塞 {{ preview.taskSummary.blockingCount }}</em>

              <em>图片 {{ preview.imageCount }}</em>

              <em>附件 {{ preview.attachmentCount }}</em>
            </span>

            <span v-if="preview.taskSummary.blockingReason" class="role-blocking-reason">
              {{ preview.taskSummary.blockingReason }}
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
      <Transition name="role-preview-switch" mode="out-in">
        <el-empty
          v-if="!activePreview"
          key="empty"
          description="点击左侧岗位卡片，查看该岗位已完成内容、附件证据和下一步处理建议"
        />

        <article v-else :key="activePreview.key" class="role-workspace-paper">
          <header class="report-head">
            <div>
              <span>岗位流程工作区</span>

              <h3>{{ activePreview.title }}</h3>

              <p>{{ activePreview.description }}</p>
            </div>

            <el-tag :type="activePreview.statusTone" effect="plain">{{ activePreview.statusLabel }}</el-tag>
          </header>

          <section class="decision-summary-card" aria-label="当前决策摘要">
            <div class="decision-summary-main">
              <span>当前阶段</span>

              <strong>{{ activePreview.decisionSummary.stageLabel }}</strong>

              <small>{{ activePreview.decisionSummary.progressText }}</small>
            </div>

            <div class="decision-summary-grid">
              <article>
                <span>当前责任</span>

                <strong>{{ activePreview.stageOwner || activePreview.decisionSummary.ownerLabel }}</strong>
              </article>

              <article>
                <span>关键结论</span>

                <strong>{{ activePreview.keyConclusion }}</strong>
              </article>

              <article>
                <span>阻塞原因</span>

                <strong>{{ activePreview.blockingReason }}</strong>
              </article>

              <article>
                <span>下一步</span>

                <strong>{{ activePreview.nextAction }}</strong>
              </article>
            </div>
          </section>

          <section class="role-mode-bar">
            <el-radio-group v-model="rolePanelMode" size="small">
              <el-radio-button label="maintain">填写任务</el-radio-button>

              <el-radio-button label="preview">报告预览</el-radio-button>
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

          <Transition name="role-panel-mode-fade" mode="out-in">
            <section v-if="rolePanelMode === 'maintain'" key="maintain" class="maintain-panel">
              <div class="maintain-head">
                <div>
                  <strong>{{ activePreview.key === "inspection" ? "检查室填写任务" : "本岗位填写任务" }}</strong>

                  <span>
                    {{
                      activePreview.key === "inspection"
                        ? "先上传图片/视频，再补充镜下所见和初步检查描述。"
                        : activePreview.key === "doctorDecision"
                          ? "承接上游检查、问诊和化验结果，分别维护西医诊疗判断与中医辨证治法。"
                          : "只维护当前岗位负责字段，最终目标病历仍由医生汇总生成。"
                    }}
                  </span>

                  <span class="maintain-objective">处理目标：{{ activePreview.processGoal }}</span>
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
                  保存并完成本岗位内容
                </el-button>
              </div>

              <section v-if="maintenanceFields.length" class="task-overview-strip" aria-label="本岗位任务概览">
                <article>
                  <span>必须完成</span>
                  <strong>{{ requiredMaintenanceFields.length }}</strong>
                </article>

                <article>
                  <span>建议补充</span>
                  <strong>{{ optionalMaintenanceFields.length }}</strong>
                </article>

                <article>
                  <span>参考信息</span>
                  <strong>{{ activePreview.referenceItems.items.length }}</strong>
                </article>

                <article>
                  <span>附件证据</span>
                  <strong>{{ activePreview.attachmentCount }}</strong>
                </article>
              </section>

              <el-empty
                v-if="!maintenanceFields.length"
                description="当前岗位暂无可直接维护字段，请进入目标病历或完整档案处理。"
              />

              <div v-else class="task-workspace">
                <section class="task-section must-do">
                  <header class="task-section-head">
                    <div>
                      <strong>{{ activePreview.requiredTasks.title }}</strong>

                      <span>{{ activePreview.requiredTasks.description }}</span>
                    </div>

                    <el-tag type="warning" effect="plain">{{ requiredMaintenanceFields.length }} 项</el-tag>
                  </header>

                  <el-empty v-if="!requiredMaintenanceFields.length" description="暂无必须完成项" />

                  <div v-else class="maintain-field-list">
                    <article
                      v-for="item in requiredMaintenanceFields"
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
                        <el-tag v-else type="warning" effect="plain" size="small">必须完成</el-tag>
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
                        :textarea-rows="activePreview.key === 'inspection' || activePreview.key === 'doctorDecision' ? 3 : 2"
                        @update:model-value="$emit('updateField', item.field.key, $event)"
                        @update-lab-metric="(field, value) => $emit('updateLabMetric', field, value)"
                        @upload="(field, files, remark) => $emit('upload', field, files, remark)"
                        @add-followup-record="$emit('addFollowupRecord')"
                        @remove-followup-record="$emit('removeFollowupRecord', $event)"
                      />
                    </article>
                  </div>
                </section>

                <section class="task-section optional-do">
                  <header class="task-section-head">
                    <div>
                      <strong>{{ activePreview.optionalTasks.title }}</strong>

                      <span>{{ activePreview.optionalTasks.description }}</span>
                    </div>

                    <el-tag effect="plain">{{ optionalMaintenanceFields.length }} 项</el-tag>
                  </header>

                  <el-empty v-if="!optionalMaintenanceFields.length" description="暂无建议补充项" />

                  <details v-else class="task-collapsible">
                    <summary>展开 {{ optionalMaintenanceFields.length }} 项建议补充字段</summary>

                    <div class="maintain-field-list compact">
                      <article v-for="item in optionalMaintenanceFields" :key="item.field.key" class="maintain-field-card">
                        <header>
                          <div>
                            <strong>{{ item.field.label }}</strong>

                            <small>{{ item.sectionTitle }}</small>
                          </div>

                          <el-tag v-if="!isFieldEditable(item.field)" effect="plain" size="small">只读</el-tag>
                          <el-tag v-else effect="plain" size="small">建议补充</el-tag>
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
                          :textarea-rows="2"
                          @update:model-value="$emit('updateField', item.field.key, $event)"
                          @update-lab-metric="(field, value) => $emit('updateLabMetric', field, value)"
                          @upload="(field, files, remark) => $emit('upload', field, files, remark)"
                          @add-followup-record="$emit('addFollowupRecord')"
                          @remove-followup-record="$emit('removeFollowupRecord', $event)"
                        />
                      </article>
                    </div>
                  </details>
                </section>

                <section class="task-section reference-do">
                  <header class="task-section-head">
                    <div>
                      <strong>{{ activePreview.referenceItems.title }}</strong>

                      <span>{{ activePreview.referenceItems.description }}</span>
                    </div>

                    <el-tag effect="plain">{{ activePreview.referenceItems.items.length }} 项</el-tag>
                  </header>

                  <el-empty v-if="!activePreview.referenceItems.items.length" description="暂无上游参考信息" />

                  <div v-else class="reference-list">
                    <button
                      v-for="item in activePreview.referenceItems.items"
                      :key="`reference-${item.source}-${item.key}`"
                      type="button"
                      class="reference-row"
                      @click="$emit('focusField', item.key)"
                    >
                      <span>
                        <strong>{{ item.label }}</strong>

                        <small>{{ item.section }} · {{ item.source === "medicalRecord" ? "目标病历" : "完整档案" }}</small>
                      </span>

                      <em>{{ item.value }}</em>
                    </button>
                  </div>
                </section>
              </div>
            </section>

            <section v-else key="preview" class="preview-panel">
              <section class="report-paper-head">
                <div>
                  <span>岗位报告</span>

                  <h4>{{ activePreview.reportTitle }}</h4>

                  <p>{{ activePreview.description }}</p>
                </div>

                <el-tag :type="activePreview.reviewAdvice.statusTone" effect="plain">
                  {{ activePreview.reviewAdvice.statusLabel }}
                </el-tag>
              </section>

              <section class="report-section">
                <div class="report-section-title">
                  <strong>患者信息</strong>

                  <span>{{ activePreview.runtimeStatusLabel }}</span>
                </div>

                <div class="patient-report-line">
                  <span>姓名：{{ fieldValues.patientName || "待补充" }}</span>

                  <span>性别：{{ fieldValues.gender || "待补充" }}</span>

                  <span>门诊/住院号：{{ fieldValues.visitNo || "待补充" }}</span>
                </div>
              </section>

              <LabReportPreview
                v-if="activePreview.key === 'lab'"
                :field-values="fieldValues"
                :patient-name="fieldValues.patientName"
                :patient-gender="fieldValues.gender"
                :visit-no="fieldValues.visitNo"
                compact
              />

              <template v-else>
                <section v-for="section in activePreview.reportSections" :key="section.key" class="report-section">
                  <div class="report-section-title">
                    <strong>{{ section.title }}</strong>

                    <span>{{ section.items.length ? `证据 ${section.items.length} 项` : "待形成" }}</span>
                  </div>

                  <p class="report-section-summary">{{ section.summary }}</p>

                  <el-empty v-if="!section.items.length" :description="section.emptyText" />

                  <div v-else class="report-evidence-list">
                    <button
                      v-for="item in section.items"
                      :key="`${section.key}-${item.source}-${item.key}`"
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
                  </div>
                </section>
              </template>

              <section class="report-section">
                <div class="report-section-title">
                  <strong>附件证据</strong>

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

              <section class="review-advice-card">
                <header>
                  <div>
                    <strong>医生审阅建议</strong>

                    <span>{{ activePreview.reviewAdvice.recommendation }}</span>
                  </div>

                  <el-tag :type="activePreview.reviewAdvice.statusTone" effect="plain">
                    {{ activePreview.reviewAdvice.statusLabel }}
                  </el-tag>
                </header>

                <el-empty v-if="!activePreview.reviewAdvice.issues.length" description="暂无明确审阅问题" />

                <div v-else class="review-issue-list">
                  <button
                    v-for="issue in activePreview.reviewAdvice.issues"
                    :key="issue.key"
                    type="button"
                    class="review-issue-row"
                    @click="issue.fieldKey && $emit('focusField', issue.fieldKey)"
                  >
                    <span>
                      <strong>{{ issue.label }}</strong>

                      <small>{{ issue.reason }}</small>
                    </span>

                    <el-tag :type="issue.tone" effect="plain" size="small">定位</el-tag>
                  </button>
                </div>

                <footer>
                  <span>下一步：{{ activePreview.reviewAdvice.nextStep }}</span>

                  <el-button
                    size="small"
                    plain
                    :disabled="!activePreview.reviewAdvice.focusFieldKeys.length"
                    @click="$emit('focusField', activePreview.reviewAdvice.focusFieldKeys[0])"
                  >
                    定位缺失字段
                  </el-button>
                </footer>
              </section>
            </section>
          </Transition>
        </article>
      </Transition>
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
const requiredMaintenanceFields = computed(() => {
  const preview = activePreview.value;
  if (!preview) return [];

  const requiredKeys = new Set([
    ...preview.requiredTasks.fieldKeys,
    ...preview.missingItems.map(item => item.key),
    ...preview.taskItems
      .filter(task => task.status !== "complete" && (task.required || task.archiveRequired))
      .map(task => task.fieldKey)
  ]);
  const matched = props.maintenanceFields.filter(item => requiredKeys.has(item.field.key) || item.field.required);

  if (matched.length) return matched;
  return props.maintenanceFields.filter(item => props.issueForField(item.field));
});
const optionalMaintenanceFields = computed(() => {
  const preview = activePreview.value;
  const requiredKeys = new Set(requiredMaintenanceFields.value.map(item => item.field.key));
  const optionalKeys = new Set(preview?.optionalTasks.fieldKeys || []);
  const matched = props.maintenanceFields.filter(item => optionalKeys.has(item.field.key) && !requiredKeys.has(item.field.key));

  if (matched.length) return matched.slice(0, 5);
  return props.maintenanceFields.filter(item => !requiredKeys.has(item.field.key) && props.issueForField(item.field)).slice(0, 5);
});

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
      background: var(--hos-accent);
      border-color: var(--hos-accent);
      box-shadow: 0 0 0 5px rgb(37 99 235 / 12%);
      transform: scale(1.04);
    }

    .workflow-role-node {
      background: linear-gradient(135deg, #fff 0%, var(--hos-accent-soft) 100%);
      border-color: var(--hos-accent);
      box-shadow: 0 14px 32px rgb(37 99 235 / 16%);
    }

    .workflow-role-node::before {
      opacity: 1;
    }

    .role-card-stats em {
      color: var(--hos-accent);
      background: rgb(255 255 255 / 72%);
      border-color: rgb(37 99 235 / 16%);
    }
  }

  &.complete:not(.active) .timeline-marker {
    color: #047857;
    background: #ecfdf5;
    border-color: #34d399;
  }

  &.runtime-needsSupplement,
  &.runtime-returned {
    .workflow-role-node {
      border-color: #fbbf24;
      background: #fffbeb;
    }

    .timeline-marker {
      color: #92400e;
      background: #fef3c7;
      border-color: #f59e0b;
    }
  }

  &.runtime-returned {
    .workflow-role-node {
      border-color: #fca5a5;
      background: #fff1f2;
    }

    .timeline-marker {
      color: #b91c1c;
      background: #fee2e2;
      border-color: #ef4444;
    }
  }

  &.runtime-notStarted {
    opacity: 0.78;
  }

  &.actionable:not(.active) .workflow-role-node {
    border-color: #bfdbfe;
  }
}

.workflow-role-node {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 10px;
  width: 100%;
  min-width: 0;
  padding: 13px 14px;
  overflow: hidden;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);
  box-shadow: var(--hos-shadow-card);
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;

  &::before {
    position: absolute;
    inset: 0 auto 0 0;
    width: 3px;
    content: "";
    background: var(--hos-accent);
    opacity: 0;
    transition: opacity 0.18s ease;
  }

  &:hover {
    border-color: #93c5fd;
    box-shadow: var(--hos-shadow-card-hover);
    transform: translateY(-1px);
  }

  &:active {
    transform: translateY(0);
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
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    color 0.18s ease,
    transform 0.18s ease;

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
    padding: 2px 7px;
    font-style: normal;
    background: var(--hos-bg-surface);
    border: 1px solid var(--hos-border-light);
    border-radius: 999px;
    transition:
      background-color 0.18s ease,
      border-color 0.18s ease,
      color 0.18s ease;
  }
}

.role-task-goal,
.role-blocking-reason {
  display: block;
  min-width: 0;
  overflow-wrap: anywhere;
  line-height: 1.5;
}

.role-task-goal {
  color: #334155;
  font-size: 13px;
}

.role-blocking-reason {
  padding: 6px 8px;
  color: #92400e;
  font-size: 12px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 7px;
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
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;

  &:hover {
    border-color: #93c5fd;
    box-shadow: 0 8px 18px rgb(15 23 42 / 12%);
    transform: translateY(-1px) scale(1.02);
  }

  &:hover img {
    transform: scale(1.04);
  }

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    transition: transform 0.18s ease;
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
  background: linear-gradient(180deg, #f8fafc 0%, #f1f5f9 100%);
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-card);
}

.role-workspace-paper {
  display: grid;
  gap: 16px;
  max-width: 980px;
  min-width: 0;
  padding: 22px;
  margin: 0 auto;
  overflow: hidden;
  color: var(--hos-text-primary);
  background: #fff;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-card-hover);
}

.role-mode-bar,
.maintain-head {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  min-width: 0;
}

.decision-summary-card {
  display: grid;
  grid-template-columns: minmax(180px, 0.75fr) minmax(0, 1.75fr);
  gap: 12px;
  min-width: 0;
  padding: 14px;
  background: #f8fafc;
  border: 1px solid #dbe4ef;
  border-radius: var(--hos-radius-md);
}

.decision-summary-main,
.decision-summary-grid article,
.role-objective-card {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.decision-summary-main {
  align-content: start;
  padding: 12px;
  background: #fff;
  border: 1px solid var(--hos-border-light);
  border-radius: 8px;
}

.decision-summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  min-width: 0;
}

.decision-summary-grid article {
  padding: 10px 12px;
  background: #fff;
  border: 1px solid var(--hos-border-light);
  border-radius: 8px;
}

.decision-summary-card span,
.decision-summary-card small,
.role-objective-card span {
  color: #64748b;
  line-height: 1.5;
}

.decision-summary-card strong,
.role-objective-card strong {
  min-width: 0;
  overflow-wrap: anywhere;
  color: #0f172a;
  line-height: 1.5;
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
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);
}

.maintain-panel,
.preview-panel,
.task-workspace,
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

.maintain-objective {
  color: #1d4ed8 !important;
}

.task-overview-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  min-width: 0;
}

.task-overview-strip article {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 10px 12px;
  background: #ffffff;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);
}

.task-overview-strip span {
  color: #64748b;
  font-size: 12px;
}

.task-overview-strip strong {
  color: #0f172a;
  font-size: 20px;
  line-height: 1;
}

.task-section {
  display: grid;
  gap: 12px;
  min-width: 0;
  padding: 14px;
  background: #fff;
  border: 1px solid #dbe4ef;
  border-radius: var(--hos-radius-md);

  &.must-do {
    border-color: #fbbf24;
    background: #fffdf7;
  }

  &.reference-do {
    background: #f8fafc;
  }
}

.task-section-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  min-width: 0;

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
    line-height: 1.55;
  }
}

.maintain-field-group {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.maintain-group-head {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 10px 12px;
  background: #f9fafb;
  border-left: 4px solid #2563eb;

  strong {
    color: #111827;
    font-size: 15px;
  }

  span {
    color: #64748b;
    line-height: 1.55;
  }
}

.maintain-field-card {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 14px;
  background: #fff;
  border: 1px solid #dbe4ef;
  border-radius: var(--hos-radius-md);
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;

  &:hover {
    border-color: #bfdbfe;
    box-shadow: 0 8px 20px rgb(37 99 235 / 8%);
    transform: translateY(-1px);
  }

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

.maintain-field-list.compact .maintain-field-card {
  padding: 12px;
  box-shadow: none;
}

.task-collapsible {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.task-collapsible summary {
  padding: 9px 11px;
  color: #475569;
  font-size: 13px;
  cursor: pointer;
  background: #f8fafc;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);
}

.task-collapsible[open] summary {
  color: #1d4ed8;
  background: #eff6ff;
  border-color: #bfdbfe;
}

.reference-list {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.reference-row {
  display: grid;
  grid-template-columns: minmax(150px, 0.42fr) minmax(0, 1fr);
  gap: 12px;
  width: 100%;
  padding: 10px 12px;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;

  &:hover {
    background: #f8fafc;
    border-color: #bfdbfe;
    box-shadow: 0 8px 18px rgb(15 23 42 / 6%);
    transform: translateY(-1px);
  }

  &:active {
    transform: translateY(0);
  }

  span {
    display: grid;
    gap: 3px;
    min-width: 0;
  }

  strong,
  em {
    min-width: 0;
    overflow-wrap: anywhere;
  }

  small {
    color: #64748b;
  }

  em {
    color: #111827;
    font-size: 13px;
    font-style: normal;
    line-height: 1.6;
  }
}

.report-section {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding-top: 2px;
}

.report-paper-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  min-width: 0;
  padding: 16px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
  border: 1px solid #dbe4ef;
  border-radius: var(--hos-radius-md);

  > div {
    display: grid;
    gap: 5px;
    min-width: 0;
  }

  span,
  p {
    color: #64748b;
  }

  h4,
  p {
    margin: 0;
  }

  h4 {
    color: #0f172a;
    font-size: 20px;
  }
}

.patient-report-line {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  min-width: 0;
  padding: 10px 12px;
  color: #334155;
  background: #fff;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);
}

.report-section-summary {
  margin: 0;
  padding: 11px 13px;
  color: #334155;
  font-size: 14px;
  line-height: 1.8;
  background: #ffffff;
  border: 1px solid var(--hos-border-light);
  border-left: 4px solid #cbd5e1;
  border-radius: 6px;
}

.report-evidence-list {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.role-objective-card {
  padding: 12px 14px;
  background: #fff;
  border: 1px solid var(--hos-border-light);
  border-left: 4px solid var(--hos-accent);
  border-radius: var(--hos-radius-md);
}

.report-section-title {
  padding: 8px 10px;
  background: #f8fafc;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);

  strong {
    color: var(--hos-text-primary);
  }

  span {
    color: var(--hos-text-secondary);
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
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;

  &:hover {
    background: #f8fafc;
    border-color: #bfdbfe;
    box-shadow: 0 8px 20px rgb(15 23 42 / 6%);
    transform: translateY(-1px);
  }

  &:active {
    transform: translateY(0);
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
    border-left: 3px solid var(--hos-status-warning);
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
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;

  &:hover {
    background: #f8fafc;
    border-color: #93c5fd;
    box-shadow: 0 8px 20px rgb(15 23 42 / 7%);
    transform: translateY(-1px);
  }

  &:active {
    transform: translateY(0);
  }

  img,
  > span {
    width: 58px;
    height: 44px;
    border-radius: var(--hos-radius-sm);
  }

  &:hover img {
    transform: scale(1.04);
  }

  img {
    overflow: hidden;
    object-fit: cover;
    transition: transform 0.18s ease;
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

.review-advice-card {
  display: grid;
  gap: 12px;
  min-width: 0;
  padding: 14px;
  background: #f8fafc;
  border: 1px solid #dbe4ef;
  border-radius: var(--hos-radius-md);

  header,
  footer {
    display: flex;
    gap: 12px;
    align-items: flex-start;
    justify-content: space-between;
    min-width: 0;
  }

  header > div {
    display: grid;
    gap: 4px;
    min-width: 0;
  }

  strong {
    color: #0f172a;
  }

  span,
  small,
  footer {
    color: #64748b;
    line-height: 1.55;
  }
}

.review-issue-list {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.review-issue-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 10px 12px;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;

  &:hover {
    background: #fff;
    border-color: #bfdbfe;
    box-shadow: 0 8px 18px rgb(15 23 42 / 6%);
    transform: translateY(-1px);
  }

  &:active {
    transform: translateY(0);
  }

  span {
    display: grid;
    gap: 3px;
    min-width: 0;
  }

  strong,
  small {
    min-width: 0;
    overflow-wrap: anywhere;
  }
}

.role-preview-switch-enter-active,
.role-preview-switch-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}

.role-preview-switch-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.role-preview-switch-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.role-panel-mode-fade-enter-active,
.role-panel-mode-fade-leave-active {
  transition:
    opacity 0.16s ease,
    transform 0.16s ease;
}

.role-panel-mode-fade-enter-from {
  opacity: 0;
  transform: translateY(6px);
}

.role-panel-mode-fade-leave-to {
  opacity: 0;
  transform: translateY(-3px);
}

.role-mode-bar :deep(.el-radio-button__inner) {
  transition:
    background-color 0.16s ease,
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    color 0.16s ease;
}

.role-mode-bar :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  box-shadow: 0 6px 16px rgb(37 99 235 / 14%);
}

@media (min-width: 961px) {
  .workflow-role-timeline {
    position: sticky;
    top: 68px;
    max-height: calc(100vh - 96px);
    overflow: auto;
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

  .decision-summary-card,
  .decision-summary-grid,
  .task-overview-strip {
    grid-template-columns: 1fr;
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
  .report-paper-head,
  .report-section-title,
  .role-mode-bar,
  .maintain-head,
  .task-section-head,
  .maintain-field-card > header,
  .review-advice-card header,
  .review-advice-card footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .report-field-row.pending,
  .reference-row,
  .review-issue-row {
    grid-template-columns: 1fr;
  }
}
</style>
