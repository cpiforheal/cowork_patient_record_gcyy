<template>
  <div
    v-loading="loading"
    class="medical-record-generator"
    :class="{ inline: variant === 'inline' }"
    element-loading-text="正在处理目标病历..."
  >
    <el-alert
      v-if="variant === 'dialog'"
      title="先完整填写模板动态内容，再按固定 docx 母版生成；缺少必填项时不会生成半成品。"
      type="warning"
      :closable="false"
      show-icon
    />

    <section class="medical-record-template-strip">
      <div>
        <strong>{{ templateStatus?.name || "目标病历模板" }}</strong>

        <span>{{ templateStatus?.templateSource || "周xx病历模版.docx" }}</span>
      </div>

      <el-tag effect="plain">{{ templateStatus?.configured ? "模板已启用" : "模板待配置" }}</el-tag>
    </section>

    <section v-if="hasRoleFocus" class="medical-record-role-focus">
      <div>
        <span>当前岗位聚焦</span>

        <strong>{{ focusedRoleLabel || "岗位协作" }}</strong>

        <small>
          已定位 {{ focusedFieldKeySet.size }} 个目标病历字段，其中 {{ focusedEditableFields.length }} 个当前岗位可维护。
        </small>
      </div>

      <el-switch v-model="showFocusedOnly" active-text="只看本岗位" inactive-text="查看全部上下文" />
    </section>

    <section v-if="variant === 'inline'" class="medical-record-focus-strip">
      <article>
        <span>生成规则</span>

        <strong>固定模板 + 动态字段</strong>

        <small>固定文字不改，字段缺失时不生成半成品。</small>
      </article>

      <article>
        <span>中医诊断</span>

        <strong>{{ fieldValues.tcmDiagnosis || "待补充" }}</strong>

        <small>
          证型：{{ fieldValues.tcmSyndromeType || fieldValues.tcmSyndrome || "待选择" }}；证候：{{
            fieldValues.tcmSyndromeManifestation || "待补充"
          }}
        </small>
      </article>

      <article>
        <span>当前版本</span>

        <strong>{{ currentRecord ? `V${currentRecord.version}` : "暂无" }}</strong>

        <small>{{ currentRecord ? statusLabel(currentRecord.status) : "生成后可下载 docx" }}</small>
      </article>
    </section>

    <section v-if="variant === 'dialog' && templateStatus?.requiredFields?.length" class="medical-record-required">
      <strong>模板关键字段</strong>

      <el-tag v-for="requiredField in templateStatus.requiredFields" :key="requiredField.key" effect="plain">
        {{ requiredField.label }}
      </el-tag>
    </section>

    <section v-if="missingItems.length" class="medical-record-missing">
      <strong>{{ variant === "inline" ? "生成前必须补齐" : "生成前建议补齐" }}</strong>

      <el-tag v-for="item in missingItems" :key="item" type="warning" effect="plain">{{ item }}</el-tag>
    </section>

    <section v-if="unboundFields.length" class="medical-record-missing">
      <strong>模板占位未完成</strong>

      <el-tag v-for="item in unboundFields" :key="item" type="danger" effect="plain">{{ item }}</el-tag>
    </section>

    <section v-if="labReportValues" class="medical-record-lab-preview">
      <div class="medical-record-lab-preview-head">
        <div>
          <strong>化验室报告预览</strong>
          <span>来自检验报告模板，供医生确认辅助检查摘要，不直接替代最终病历文字。</span>
        </div>
      </div>
      <LabReportPreview
        compact
        :field-values="labReportValues"
        :patient-name="patientName"
        :patient-gender="patientGender"
        :visit-no="visitNo"
      />
    </section>

    <section v-if="fieldSections.length" class="medical-record-workspace">
      <div class="medical-record-workspace-head">
        <div>
          <strong>目标病历协作填写</strong>

          <span v-if="variant === 'inline'">各岗位共同维护目标病历，本岗位负责字段可编辑，其他字段保留只读上下文。</span>

          <span v-else>已填 {{ completedCount }}/{{ totalCount }} 项 · 必填缺失 {{ missingItems.length }} 项</span>
        </div>

        <div>
          <el-button plain :loading="loading" @click="emit('precheck')">生成预检</el-button>

          <el-button plain :loading="loading" @click="emit('syncFromArchive')">从档案补齐空白项</el-button>

          <el-button type="primary" plain :loading="loading" :disabled="!hasEditableFields" @click="emit('saveWorkspace')">
            保存填写
          </el-button>

          <el-button v-if="variant === 'inline' && canManageVersions" type="primary" :loading="loading" @click="emit('generate')">
            生成 docx
          </el-button>
        </div>
      </div>

      <el-collapse v-model="activeSectionsModel" class="medical-record-sections">
        <el-collapse-item
          v-for="section in visibleFieldSections"
          :key="section.section"
          :title="section.section"
          :name="section.section"
        >
          <div class="medical-record-field-grid">
            <label
              v-for="medicalField in section.fields"
              :key="medicalField.key"
              :id="`medical-record-field-${medicalField.key}`"
              class="medical-record-field"
              :class="{
                wide: isTextareaLikeField(medicalField),
                missing: isFieldMissing(medicalField),
                locked: Boolean(medicalField.templateLocked),
                'role-focused': isFocusedField(medicalField),
                readonly: !canEditMedicalField(medicalField)
              }"
            >
              <span>
                {{ medicalField.label }}

                <sup v-if="medicalField.required">*</sup>

                <em>{{ fieldAssistText(medicalField) }}</em>

                <small>{{ fieldRoleHint(medicalField) }}</small>
              </span>

              <el-checkbox-group
                v-if="isCheckboxField(medicalField)"
                :model-value="fieldArrayValue(medicalField)"
                class="medical-record-checklist"
                :disabled="!canEditMedicalField(medicalField)"
                @change="value => updateArrayField(medicalField, value)"
              >
                <el-checkbox v-for="option in fieldOptions(medicalField)" :key="option" :label="option" border>
                  <span class="medical-record-option-text">{{ option }}</span>
                </el-checkbox>
              </el-checkbox-group>

              <el-input
                v-if="isCheckboxField(medicalField)"
                :model-value="fieldValues[medicalField.key]"
                class="medical-record-paragraph-preview"
                type="textarea"
                :rows="4"
                :disabled="!canEditMedicalField(medicalField)"
                placeholder="勾选后自动合并为正式段落，可补充少量特殊说明"
                @update:model-value="value => updateFieldValue(medicalField, value)"
              />

              <el-select
                v-else-if="isSelectLikeField(medicalField)"
                :model-value="fieldValues[medicalField.key]"
                :allow-create="canEditMedicalField(medicalField) && !medicalField.templateLocked"
                clearable
                default-first-option
                :disabled="!canEditMedicalField(medicalField) || medicalField.templateLocked"
                filterable
                :placeholder="medicalField.placeholder || '选择或输入'"
                @update:model-value="value => updateFieldValue(medicalField, value)"
              >
                <el-option v-for="option in fieldOptions(medicalField)" :key="option" :label="option" :value="option" />
              </el-select>

              <el-date-picker
                v-else-if="isDateField(medicalField)"
                :model-value="fieldValues[medicalField.key]"
                type="date"
                value-format="YYYY-MM-DD"
                :disabled="!canEditMedicalField(medicalField)"
                :placeholder="medicalField.placeholder || '选择日期'"
                @update:model-value="value => updateFieldValue(medicalField, value)"
              />

              <el-input
                v-else
                :model-value="fieldValues[medicalField.key]"
                :type="isTextareaLikeField(medicalField) ? 'textarea' : 'text'"
                :rows="isTextareaLikeField(medicalField) ? 4 : undefined"
                :disabled="!canEditMedicalField(medicalField)"
                :placeholder="medicalField.placeholder || medicalField.defaultValue || '请填写'"
                @update:model-value="value => updateFieldValue(medicalField, value)"
              />

              <small v-if="fieldSourceHint(medicalField)" class="medical-record-source-hint">
                {{ fieldSourceHint(medicalField) }}
              </small>
            </label>
          </div>
        </el-collapse-item>
      </el-collapse>
    </section>

    <section v-if="currentRecord" class="medical-record-current">
      <div class="medical-record-current-head">
        <div>
          <strong> V{{ currentRecord.version }} · {{ statusLabel(currentRecord.status) }} </strong>

          <span>{{ currentRecord.generatedAt }} · {{ currentRecord.operator }} · {{ currentRecord.model }}</span>
        </div>

        <el-tag :type="statusType(currentRecord.status)" effect="plain">
          {{ currentRecord.contentHash ? `Hash ${currentRecord.contentHash.slice(0, 10)}` : "待校验" }}
        </el-tag>
      </div>

      <div class="medical-record-file-card">
        <div>
          <strong>{{ currentRecord.fileName || `医生目标病历-V${currentRecord.version}.docx` }}</strong>

          <span>{{ currentRecord.templateVersion || templateStatus?.templateVersion || "固定模板版本" }}</span>
        </div>

        <div v-if="variant === 'inline'">
          <el-button type="primary" plain @click="emit('download')">下载/打开目标病历</el-button>

          <el-button v-if="canManageVersions && currentRecord?.status === 'draft'" type="success" plain @click="emit('finalize')">
            确认定稿
          </el-button>
        </div>

        <el-button v-else type="primary" plain @click="emit('download')">下载 docx</el-button>
      </div>
    </section>

    <el-empty v-else-if="variant === 'dialog' && canManageVersions" description="暂无医生目标病历">
      <el-button type="primary" @click="emit('generate')">生成目标病历</el-button>
    </el-empty>

    <section v-if="versions.length" class="medical-record-history">
      <strong>历史版本</strong>

      <button
        v-for="record in versions"
        :key="record.id"
        type="button"
        :class="{ active: currentRecord?.id === record.id }"
        @click="emit('selectVersion', record)"
      >
        <span>V{{ record.version }} · {{ statusLabel(record.status) }}</span>

        <small>{{ record.generatedAt }}</small>
      </button>
    </section>

    <div v-if="variant === 'dialog'" class="medical-record-footer">
      <el-button @click="emit('close')">关闭</el-button>

      <el-button :loading="loading" @click="emit('precheck')">生成预检</el-button>

      <el-button :loading="loading" @click="emit('syncFromArchive')">从档案补齐空白项</el-button>

      <el-button :loading="loading" :disabled="!hasEditableFields" @click="emit('saveWorkspace')">保存填写</el-button>

      <el-button :disabled="!currentRecord" @click="emit('download')">下载 docx</el-button>

      <el-button v-if="canManageVersions && canVoidCurrentRecord" type="danger" plain @click="emit('void')">作废版本</el-button>

      <el-button v-if="canManageVersions && isDraftCurrentRecord" type="success" :loading="loading" @click="emit('finalize')">
        确认定稿
      </el-button>

      <el-button v-if="canManageVersions" type="primary" :loading="loading" @click="emit('generate')">生成 docx 新版本</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { GeneratedMedicalRecord, MedicalRecordTemplateField, MedicalRecordTemplateStatus } from "@/api/modules/clinic";
import LabReportPreview from "./LabReportPreview.vue";
import { roleLabel, type UserRole } from "@/config/fieldPermissions";

type MedicalRecordFieldSection = {
  section: string;
  fields: MedicalRecordTemplateField[];
};

type TagType = "success" | "warning" | "info" | "primary" | "danger";

const props = withDefaults(
  defineProps<{
    variant?: "inline" | "dialog";
    loading?: boolean;
    templateStatus?: MedicalRecordTemplateStatus;
    missingItems?: string[];
    unboundFields?: string[];
    fieldSections?: MedicalRecordFieldSection[];
    activeSections?: string[];
    fieldValues: Record<string, string>;
    currentRole?: UserRole;
    canGenerate?: boolean;
    labReportValues?: Record<string, string>;
    patientName?: string;
    patientGender?: string;
    visitNo?: string;
    focusedRoleKey?: string;
    focusedRoleLabel?: string;
    focusedFieldKeys?: string[];
    currentRecord?: GeneratedMedicalRecord;
    versions?: GeneratedMedicalRecord[];
    completedCount?: number;
    totalCount?: number;
    canManageVersions?: boolean;
    isTextareaField: (field: MedicalRecordTemplateField) => boolean;
    isFieldMissing: (field: MedicalRecordTemplateField) => boolean;
    fieldAssistText: (field: MedicalRecordTemplateField) => string;
    fieldSourceHint?: (field: MedicalRecordTemplateField) => string;
    isSelectField: (field: MedicalRecordTemplateField) => boolean;
    fieldOptions: (field: MedicalRecordTemplateField) => string[];
    isDateField: (field: MedicalRecordTemplateField) => boolean;
    statusLabel: (status: GeneratedMedicalRecord["status"]) => string;
    statusType: (status: GeneratedMedicalRecord["status"]) => TagType;
  }>(),
  {
    variant: "inline",
    loading: false,
    templateStatus: undefined,
    missingItems: () => [],
    unboundFields: () => [],
    fieldSections: () => [],
    activeSections: () => [],
    currentRecord: undefined,
    labReportValues: undefined,
    patientName: "",
    patientGender: "",
    visitNo: "",
    focusedRoleKey: "",
    focusedRoleLabel: "",
    focusedFieldKeys: () => [],
    versions: () => [],
    completedCount: 0,
    totalCount: 0,
    currentRole: undefined,
    canGenerate: false,
    canManageVersions: false,
    fieldSourceHint: () => ""
  }
);

const emit = defineEmits<{
  "update:activeSections": [sections: string[]];
  updateField: [key: string, value: string];
  precheck: [];
  saveWorkspace: [];
  syncFromArchive: [];
  generate: [];
  download: [];
  finalize: [];
  void: [];
  close: [];
  selectVersion: [record: GeneratedMedicalRecord];
}>();

const activeSectionsModel = computed({
  get: () => props.activeSections,
  set: value => emit("update:activeSections", value)
});

const roleCanGenerate = computed(() => props.canGenerate);
const canVoidCurrentRecord = computed(
  () => roleCanGenerate.value && Boolean(props.currentRecord && props.currentRecord.status !== "voided")
);
const isDraftCurrentRecord = computed(() => roleCanGenerate.value && props.currentRecord?.status === "draft");
const showFocusedOnly = ref(false);

const focusedFieldKeySet = computed(() => new Set(props.focusedFieldKeys.filter(Boolean)));
const hasRoleFocus = computed(() => Boolean(props.focusedRoleKey && focusedFieldKeySet.value.size));

const isCheckboxField = (field: MedicalRecordTemplateField) =>
  field.controlType === "checkboxParagraph" || field.renderMode === "paragraph" || field.kind === "checkboxParagraph";

const isTextareaLikeField = (field: MedicalRecordTemplateField) => props.isTextareaField(field) || isCheckboxField(field);

const isSelectLikeField = (field: MedicalRecordTemplateField) => !isCheckboxField(field) && props.isSelectField(field);
const canManageVersions = computed(() => props.canManageVersions);

const canViewMedicalField = (field: MedicalRecordTemplateField) => {
  if (!props.currentRole || props.currentRole === "admin") return true;
  if (!field.viewerRoles?.length) return true;

  return field.viewerRoles.includes(props.currentRole);
};

const canEditMedicalField = (field: MedicalRecordTemplateField) => {
  if (props.currentRole === "admin") return true;
  if (!props.currentRole) return false;
  if (!field.editorRoles?.length) return props.currentRole === "doctor";

  return field.editorRoles.includes(props.currentRole);
};

const isFocusedField = (field: MedicalRecordTemplateField) => focusedFieldKeySet.value.has(field.key);

const visibleFieldSections = computed(() =>
  props.fieldSections
    .map(section => ({
      ...section,
      fields: section.fields
        .filter(field => canViewMedicalField(field) && (!showFocusedOnly.value || isFocusedField(field)))
        .sort((left, right) => Number(isFocusedField(right)) - Number(isFocusedField(left)))
    }))
    .filter(section => section.fields.length > 0)
);

const focusedEditableFields = computed(() =>
  visibleFieldSections.value
    .flatMap(section => section.fields)
    .filter(field => isFocusedField(field) && canEditMedicalField(field))
);

const hasEditableFields = computed(() => visibleFieldSections.value.some(section => section.fields.some(canEditMedicalField)));

watch(
  () => props.focusedRoleKey,
  () => {
    showFocusedOnly.value = false;
  }
);

const fieldRoleHint = (field: MedicalRecordTemplateField) => {
  if (canEditMedicalField(field)) return "本岗位可维护";
  if (!field.editorRoles?.length) return "医生维护";

  const labels = field.editorRoles
    .slice(0, 3)
    .map(role => roleLabel(role))
    .join("、");
  const suffix = field.editorRoles.length > 3 ? "等维护" : "维护";

  return `${labels}${suffix}`;
};

const fieldArrayValue = (field: MedicalRecordTemplateField) => {
  const value = String(props.fieldValues[field.key] || "");
  return (props.fieldOptions(field) || []).filter(option => value.includes(String(option)));
};

const updateFieldValue = (field: MedicalRecordTemplateField, value: string | number | boolean | undefined) => {
  if (!canEditMedicalField(field)) return;

  emit("updateField", field.key, String(value ?? ""));
};

const updateArrayField = (field: MedicalRecordTemplateField, value: unknown) => {
  if (!canEditMedicalField(field)) return;

  const selected = Array.isArray(value) ? value.map(item => String(item)) : [];
  emit("updateField", field.key, selected.join(field.joiner ?? ""));
};
</script>

<style scoped lang="scss">
.medical-record-generator {
  display: grid;
  gap: 14px;
  min-height: 260px;

  &.inline {
    min-height: 420px;
  }
}

.medical-record-template-strip,
.medical-record-current-head {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.medical-record-template-strip {
  padding: 12px 14px;
  background: var(--hos-glass);
  border: 1px solid var(--hos-border-light);
  border-radius: 8px;

  div {
    display: grid;
    gap: 4px;
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 12px;
  }
}

.medical-record-role-focus {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  background: #f0f9ff;
  border: 1px solid #bae6fd;
  border-radius: 8px;

  div {
    display: grid;
    gap: 4px;
    min-width: 0;
  }

  span,
  small {
    color: #0369a1;
    font-size: 12px;
  }

  strong {
    overflow-wrap: anywhere;
    color: #0f172a;
  }
}

.medical-record-focus-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;

  article {
    display: grid;
    gap: 4px;
    min-width: 0;
    padding: 12px;
    background: var(--hos-panel);
    border: 1px solid var(--hos-border-light);
    border-radius: var(--hos-radius-md);
  }

  span,
  small {
    color: var(--hos-text-secondary);
  }

  strong,
  small {
    overflow-wrap: anywhere;
  }
}

.medical-record-required,
.medical-record-missing {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 10px 12px;
  border-radius: 8px;
}

.medical-record-required {
  background: #f7fbfa;
  border: 1px solid #d8ebe5;

  strong {
    color: var(--hos-text-primary);
  }
}

.medical-record-missing {
  background: #fff8e6;
  border: 1px solid #f3d19e;
}

.medical-record-workspace {
  display: grid;
  gap: 12px;
  padding: 12px;
  background: #ffffff;
  border: 1px solid var(--hos-border-light);
  border-radius: 8px;
}

.medical-record-lab-preview {
  display: grid;
  gap: 12px;
  padding: 12px;
  background: #f8fbff;
  border: 1px solid #dce8f5;
  border-radius: 8px;
}

.medical-record-lab-preview-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;

  div {
    display: grid;
    gap: 4px;
  }

  strong {
    color: var(--hos-text-primary);
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 12px;
  }
}

.medical-record-workspace-head {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;

  > div:first-child {
    display: grid;
    gap: 4px;
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 12px;
  }

  > div:last-child {
    display: flex;
    gap: 8px;
  }
}

.medical-record-sections {
  border-top: 1px solid var(--hos-border-light);
}

.medical-record-field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.medical-record-field {
  display: grid;
  gap: 6px;
  min-width: 0;
  padding: 10px;
  background: #f8fafc;
  border: 1px solid var(--hos-border-light);
  border-radius: 8px;

  &.wide {
    grid-column: 1 / -1;
  }

  &.missing {
    background: #fff8e6;
    border-color: #f3d19e;
  }

  &.role-focused {
    background: #eff6ff;
    border-color: #60a5fa;
    box-shadow: inset 3px 0 0 #2563eb;
  }

  &.locked {
    background: #f8fafc;
  }

  &.readonly {
    background: #f8fafc;
    border-color: #e5e7eb;

    > span {
      color: var(--hos-text-secondary);
    }

    em {
      color: #64748b;
      background: #eef2f7;
    }
  }

  > span {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: center;
    color: var(--hos-text-primary);
    font-size: 13px;
    font-weight: 700;
  }

  sup {
    color: var(--el-color-danger);
  }

  em {
    padding: 1px 6px;
    color: #047857;
    background: #ecfdf5;
    border-radius: 999px;
    font-size: 11px;
    font-style: normal;
    font-weight: 600;
  }

  small {
    padding: 1px 6px;
    color: #475569;
    background: #f1f5f9;
    border-radius: 999px;
    font-size: 11px;
    font-weight: 600;
  }
}

.medical-record-checklist {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;

  :deep(.el-checkbox) {
    height: auto;
    min-height: 36px;
    margin-right: 0;
    padding: 7px 10px;
    white-space: normal;
  }
}

.medical-record-option-text {
  line-height: 1.45;
}

.medical-record-paragraph-preview {
  margin-top: 4px;
}

.medical-record-source-hint {
  color: #047857;
  font-size: 12px;
  line-height: 1.5;
}

.medical-record-current {
  display: grid;
  gap: 10px;
}

.medical-record-current-head {
  strong {
    display: block;
    margin-bottom: 4px;
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 12px;
  }
}

.medical-record-file-card {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 14px;
  background: #f8fafc;
  border: 1px solid var(--hos-border-light);
  border-radius: 8px;

  div {
    display: grid;
    gap: 4px;
    min-width: 0;
  }

  strong {
    overflow: hidden;
    color: var(--hos-text-primary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 12px;
  }
}

.medical-record-history {
  display: grid;
  gap: 8px;

  > strong {
    font-size: 14px;
  }

  button {
    display: flex;
    justify-content: space-between;
    width: 100%;
    padding: 10px 12px;
    text-align: left;
    cursor: pointer;
    background: #ffffff;
    border: 1px solid var(--hos-border-light);
    border-radius: 8px;

    &.active {
      color: var(--el-color-primary);
      background: #f5f8ff;
      border-color: var(--el-color-primary);
    }

    small {
      color: var(--hos-text-secondary);
    }
  }
}

.medical-record-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: flex-end;
}

@media (width <= 768px) {
  .medical-record-focus-strip,
  .medical-record-field-grid {
    grid-template-columns: 1fr;
  }

  .medical-record-workspace-head,
  .medical-record-role-focus,
  .medical-record-template-strip,
  .medical-record-current-head,
  .medical-record-file-card {
    align-items: flex-start;
    flex-direction: column;
  }

  .medical-record-footer {
    justify-content: stretch;
  }
}
</style>
