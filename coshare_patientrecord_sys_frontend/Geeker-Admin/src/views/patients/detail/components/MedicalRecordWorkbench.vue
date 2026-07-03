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

    <section v-if="fieldSections.length" class="medical-record-workspace">
      <div class="medical-record-workspace-head">
        <div>
          <strong>医生目标病历填写</strong>

          <span v-if="variant === 'inline'">控件类型由后端字段矩阵驱动，下拉、日期、勾选类字段不会降级为文本框。</span>

          <span v-else>已填 {{ completedCount }}/{{ totalCount }} 项 · 必填缺失 {{ missingItems.length }} 项</span>
        </div>

        <div>
          <el-button plain :loading="loading" @click="emit('precheck')">生成预检</el-button>

          <el-button type="primary" plain :loading="loading" @click="emit('saveWorkspace')">保存填写</el-button>

          <el-button v-if="variant === 'inline'" type="primary" :loading="loading" @click="emit('generate')">生成 docx</el-button>
        </div>
      </div>

      <el-collapse v-model="activeSectionsModel" class="medical-record-sections">
        <el-collapse-item
          v-for="section in fieldSections"
          :key="section.section"
          :title="section.section"
          :name="section.section"
        >
          <div class="medical-record-field-grid">
            <label
              v-for="medicalField in section.fields"
              :key="medicalField.key"
              class="medical-record-field"
              :class="{
                wide: isTextareaField(medicalField),
                missing: isFieldMissing(medicalField)
              }"
            >
              <span>
                {{ medicalField.label }}

                <sup v-if="medicalField.required">*</sup>

                <em>{{ fieldAssistText(medicalField) }}</em>
              </span>

              <el-select
                v-if="isSelectField(medicalField)"
                :model-value="fieldValues[medicalField.key]"
                allow-create
                clearable
                default-first-option
                filterable
                :placeholder="medicalField.placeholder || '选择或输入'"
                @update:model-value="value => updateFieldValue(medicalField.key, value)"
              >
                <el-option v-for="option in fieldOptions(medicalField)" :key="option" :label="option" :value="option" />
              </el-select>

              <el-date-picker
                v-else-if="isDateField(medicalField)"
                :model-value="fieldValues[medicalField.key]"
                type="date"
                value-format="YYYY-MM-DD"
                :placeholder="medicalField.placeholder || '选择日期'"
                @update:model-value="value => updateFieldValue(medicalField.key, value)"
              />

              <el-input
                v-else
                :model-value="fieldValues[medicalField.key]"
                :type="isTextareaField(medicalField) ? 'textarea' : 'text'"
                :rows="isTextareaField(medicalField) ? 4 : undefined"
                :placeholder="medicalField.placeholder || medicalField.defaultValue || '请填写'"
                @update:model-value="value => updateFieldValue(medicalField.key, value)"
              />
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

          <el-button v-if="currentRecord?.status === 'draft'" type="success" plain @click="emit('finalize')">确认定稿</el-button>
        </div>

        <el-button v-else type="primary" plain @click="emit('download')">下载 docx</el-button>
      </div>
    </section>

    <el-empty v-else-if="variant === 'dialog'" description="暂无医生目标病历">
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

      <el-button :loading="loading" @click="emit('saveWorkspace')">保存填写</el-button>

      <el-button :disabled="!currentRecord" @click="emit('download')">下载 docx</el-button>

      <el-button v-if="canVoidCurrentRecord" type="danger" plain @click="emit('void')">作废版本</el-button>

      <el-button v-if="isDraftCurrentRecord" type="success" :loading="loading" @click="emit('finalize')">确认定稿</el-button>

      <el-button type="primary" :loading="loading" @click="emit('generate')">生成 docx 新版本</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { GeneratedMedicalRecord, MedicalRecordTemplateField, MedicalRecordTemplateStatus } from "@/api/modules/clinic";

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
    currentRecord?: GeneratedMedicalRecord;
    versions?: GeneratedMedicalRecord[];
    completedCount?: number;
    totalCount?: number;
    isTextareaField: (field: MedicalRecordTemplateField) => boolean;
    isFieldMissing: (field: MedicalRecordTemplateField) => boolean;
    fieldAssistText: (field: MedicalRecordTemplateField) => string;
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
    versions: () => [],
    completedCount: 0,
    totalCount: 0
  }
);

const emit = defineEmits<{
  "update:activeSections": [sections: string[]];
  updateField: [key: string, value: string];
  precheck: [];
  saveWorkspace: [];
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

const canVoidCurrentRecord = computed(() => Boolean(props.currentRecord && props.currentRecord.status !== "voided"));
const isDraftCurrentRecord = computed(() => props.currentRecord?.status === "draft");

const updateFieldValue = (key: string, value: string | number | boolean | undefined) => {
  emit("updateField", key, String(value ?? ""));
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
