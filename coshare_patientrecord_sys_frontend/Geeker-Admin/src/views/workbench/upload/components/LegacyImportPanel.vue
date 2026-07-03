<template>
  <div class="legacy-import-panel">
    <el-alert
      class="mb15"
      title="先进行旧共享病历智能预检，确认字段建议和附件归属后再采纳入档；已有字段默认不覆盖。"
      type="info"
      show-icon
      :closable="false"
    />

    <el-form :model="legacyForm" class="legacy-form" label-width="112px">
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="共享文件夹名">
            <el-input v-model="legacyFolderName" placeholder="患者姓名-门诊号-资料日期" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="经办科室">
            <el-select v-model="legacyOwnerDepartment" filterable>
              <el-option v-for="item in departments" :key="item" :label="item" :value="item" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="患者姓名">
            <el-input v-model="legacyPatientName" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="门诊/住院号">
            <el-input v-model="legacyVisitNo" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="就诊类型">
            <el-select v-model="legacyVisitType">
              <el-option label="门诊" value="门诊" />
              <el-option label="住院" value="住院" />
              <el-option label="门诊医保" value="门诊医保" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="就诊日期">
        <el-date-picker v-model="legacyVisitDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
      </el-form-item>

      <el-form-item label="选择旧文件">
        <el-upload
          v-model:file-list="fileListProxy"
          drag
          multiple
          action="#"
          :auto-upload="false"
          :on-change="handleSyncLegacyFiles"
          :on-remove="handleSyncLegacyFiles"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">拖入旧共享病历中的 .doc/.docx、图片报告、复查照片，或点击批量选择</div>
          <template #tip>
            <div class="el-upload__tip">系统会先识别字段建议和附件归属，图片报告暂不做 OCR，异常结论需人工确认。</div>
          </template>
        </el-upload>
      </el-form-item>

      <section v-if="legacyCandidates.length" class="legacy-batch-panel">
        <div class="legacy-panel-head">
          <div>
            <strong>批量导入分拣</strong>
            <span>
              已选 {{ legacyCandidates.length }} 份，已归类 {{ classifiedLegacyCount }} 份，待分拣 {{ pendingLegacyCount }} 份
            </span>
          </div>
          <el-tag :type="pendingLegacyCount ? 'warning' : 'success'" effect="plain">
            {{ pendingLegacyCount ? "需要人工确认" : "可直接导入" }}
          </el-tag>
        </div>

        <div class="legacy-file-grid">
          <article v-for="item in legacyCandidates" :key="item.id" class="legacy-file-card">
            <el-image
              v-if="item.previewUrl"
              class="legacy-thumb"
              :src="item.previewUrl"
              fit="cover"
              :preview-src-list="[item.previewUrl]"
              preview-teleported
            />
            <div v-else class="legacy-thumb placeholder">文件</div>
            <div class="legacy-file-info">
              <strong :title="item.fileName">{{ item.fileName }}</strong>
              <el-select v-model="item.type" size="small" clearable placeholder="手工分拣资料类型">
                <el-option v-for="type in documentTypes" :key="type.value" :label="type.label" :value="type.value" />
              </el-select>
            </div>
          </article>
        </div>
      </section>

      <el-form-item label="或粘贴清单">
        <el-input
          v-model="legacyFileTextProxy"
          type="textarea"
          :rows="5"
          placeholder="每行一个文件名，例如：患者姓名-门诊号-资料类型.pdf"
        />
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :icon="Search" :loading="previewingLegacy" @click="$emit('preview')">智能预检</el-button>
        <el-button type="success" :icon="Upload" :disabled="!legacyPreview" :loading="importing" @click="$emit('commit')">
          采纳入档
        </el-button>
        <el-button @click="$emit('clear')">清空</el-button>
      </el-form-item>
    </el-form>

    <section v-if="legacyPreview" class="legacy-preview-panel">
      <div class="legacy-panel-head">
        <div>
          <strong>导入预检结果</strong>
          <span>
            {{ patientMatchLabel[legacyPreview.patientMatch] }}，建议字段 {{ legacyPreview.fieldMappings.length }} 项，附件归属
            {{ legacyPreview.attachmentMappings.length }} 项
          </span>
        </div>
        <el-tag :type="legacyPreview.unassigned.length ? 'warning' : 'success'" effect="plain">
          {{ legacyPreview.status }}
        </el-tag>
      </div>

      <el-table v-if="legacyPreview.fieldMappings.length" :data="legacyPreview.fieldMappings" border class="mt15">
        <el-table-column width="54">
          <template #default="{ row }">
            <el-checkbox v-model="row.selected" :disabled="row.conflict && !allowOverwriteLegacyConflictsProxy" />
          </template>
        </el-table-column>
        <el-table-column prop="fieldLabel" label="建议字段" width="160" />
        <el-table-column prop="sectionTitle" label="所属模块" width="180" />
        <el-table-column prop="importValue" label="识别内容" min-width="240" show-overflow-tooltip />
        <el-table-column prop="currentValue" label="当前值" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.currentValue || "空" }}</template>
        </el-table-column>
        <el-table-column prop="sourceFile" label="来源文件" width="180" show-overflow-tooltip />
        <el-table-column prop="confidence" label="置信度" width="90" />
        <el-table-column label="冲突" width="92">
          <template #default="{ row }">
            <el-tag v-if="row.conflict" type="warning" effect="plain">需确认</el-tag>
            <el-tag v-else type="success" effect="plain">空字段</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂未从旧病历正文识别到可填充字段，可先导入附件后人工补充。" />

      <div class="legacy-overwrite-line">
        <el-checkbox v-model="allowOverwriteLegacyConflictsProxy">允许采纳时覆盖已有字段</el-checkbox>
      </div>

      <el-table v-if="legacyPreview.attachmentMappings.length" :data="legacyPreview.attachmentMappings" border class="mt15">
        <el-table-column width="54">
          <template #default="{ row }">
            <el-checkbox v-model="row.selected" />
          </template>
        </el-table-column>
        <el-table-column prop="fileName" label="文件" min-width="240" show-overflow-tooltip />
        <el-table-column prop="fieldLabel" label="关联字段" width="150" />
        <el-table-column prop="department" label="归属科室" width="130" />
        <el-table-column prop="source" label="来源类型" width="120">
          <template #default="{ row }">{{ legacySourceLabel[row.source] || row.source }}</template>
        </el-table-column>
      </el-table>
    </section>

    <el-result
      v-if="importResult"
      icon="success"
      title="旧共享病历已采纳入档"
      sub-title="采纳字段、复查记录和附件索引已同步到患者健康管理档案。"
    >
      <template #extra>
        <el-space wrap>
          <el-button type="primary" @click="$emit('viewPatient', importResult.patient.id)">查看患者档案</el-button>
          <el-button @click="$emit('continueImport')">继续导入</el-button>
        </el-space>
      </template>
    </el-result>

    <el-descriptions v-if="importResult" class="import-summary" :column="3" border>
      <el-descriptions-item label="患者">{{ importResult.patient.name }}</el-descriptions-item>
      <el-descriptions-item label="门诊/住院号">{{ importResult.patient.visitNo }}</el-descriptions-item>
      <el-descriptions-item label="状态">{{ importResult.patient.status }}</el-descriptions-item>
      <el-descriptions-item label="采纳字段">{{ importResult.appliedFields?.length || 0 }} 项</el-descriptions-item>
      <el-descriptions-item label="跳过字段">{{ importResult.skippedFields?.length || 0 }} 项</el-descriptions-item>
      <el-descriptions-item label="导入附件">{{ importResult.documents.length }} 份</el-descriptions-item>
    </el-descriptions>

    <el-table v-if="importResult" :data="importResult.documents" border class="mt15">
      <el-table-column type="index" label="#" width="60" />
      <el-table-column prop="fileName" label="已归属文件" min-width="240" />
      <el-table-column prop="fieldLabel" label="关联字段" width="140" />
      <el-table-column prop="department" label="负责科室" width="140" />
      <el-table-column prop="uploadedAt" label="导入时间" width="180" />
    </el-table>

    <el-table v-if="importResult?.unassigned.length" :data="unassignedRows" border class="mt15">
      <el-table-column prop="fileName" label="待人工分拣文件" min-width="260" />
      <el-table-column label="建议动作" width="220">
        <template #default>
          <el-tag type="warning" effect="plain">在患者详情中补充关联字段</el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { UploadFile, UploadUserFile } from "element-plus";
import { Search, Upload, UploadFilled } from "@element-plus/icons-vue";
import type { SharedCaseImportResult, SharedCasePreviewResult } from "@/api/modules/clinic";

type LegacyForm = {
  folderName: string;
  patientName: string;
  visitNo: string;
  visitDate: string;
  visitType: string;
  ownerDepartment: string;
};

type DocumentTypeOption = {
  label: string;
  value: string;
};

type LegacyFileCandidate = {
  id: string;
  fileName: string;
  type: string;
  previewUrl: string;
  contentDataUrl: string;
  source: "file" | "text";
};

const props = defineProps<{
  legacyForm: LegacyForm;
  departments: string[];
  documentTypes: DocumentTypeOption[];
  legacyFileList: UploadUserFile[];
  legacyFileText: string;
  legacyCandidates: LegacyFileCandidate[];
  classifiedLegacyCount: number;
  pendingLegacyCount: number;
  previewingLegacy: boolean;
  importing: boolean;
  legacyPreview: SharedCasePreviewResult | null;
  allowOverwriteLegacyConflicts: boolean;
  patientMatchLabel: Record<SharedCasePreviewResult["patientMatch"], string>;
  legacySourceLabel: Record<string, string>;
  importResult: SharedCaseImportResult | null;
  unassignedRows: Array<{ fileName: string }>;
}>();

const emit = defineEmits<{
  "update:legacyForm": [value: LegacyForm];
  "update:legacyFileList": [value: UploadUserFile[]];
  "update:legacyFileText": [value: string];
  "update:allowOverwriteLegacyConflicts": [value: boolean];
  syncLegacyFiles: [uploadFile?: UploadFile, uploadFiles?: UploadFile[]];
  preview: [];
  commit: [];
  clear: [];
  viewPatient: [patientId: string];
  continueImport: [];
}>();

const updateLegacyForm = (patch: Partial<LegacyForm>) => emit("update:legacyForm", { ...props.legacyForm, ...patch });
const legacyFieldProxy = (key: keyof LegacyForm) =>
  computed({
    get: () => props.legacyForm[key],
    set: value => updateLegacyForm({ [key]: String(value || "") })
  });

const legacyFolderName = legacyFieldProxy("folderName");
const legacyPatientName = legacyFieldProxy("patientName");
const legacyVisitNo = legacyFieldProxy("visitNo");
const legacyVisitDate = legacyFieldProxy("visitDate");
const legacyVisitType = legacyFieldProxy("visitType");
const legacyOwnerDepartment = legacyFieldProxy("ownerDepartment");

const fileListProxy = computed({
  get: () => props.legacyFileList,
  set: value => emit("update:legacyFileList", value)
});

const legacyFileTextProxy = computed({
  get: () => props.legacyFileText,
  set: value => emit("update:legacyFileText", value)
});

const allowOverwriteLegacyConflictsProxy = computed({
  get: () => props.allowOverwriteLegacyConflicts,
  set: value => emit("update:allowOverwriteLegacyConflicts", value)
});

const handleSyncLegacyFiles = (uploadFile: UploadFile, uploadFiles: UploadFile[]) =>
  emit("syncLegacyFiles", uploadFile, uploadFiles);
</script>

<style scoped lang="scss">
.legacy-import-panel {
  min-width: 0;
}

.legacy-form {
  margin-top: 12px;
}

.legacy-batch-panel {
  padding: 12px;
  margin-bottom: 16px;
  background: #f8fafc;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.legacy-preview-panel {
  padding: 16px;
  margin: 12px 0 18px;
  background: #ffffff;
  border: 1px solid #cfe7df;
  border-radius: 8px;
}

.legacy-overwrite-line {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}

.legacy-panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;

  strong,
  span {
    display: block;
  }

  span {
    margin-top: 4px;
    color: var(--el-text-color-regular);
    font-size: 13px;
  }
}

.legacy-file-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 10px;
}

.legacy-file-card {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  padding: 8px;
  background: #ffffff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
}

.legacy-thumb {
  width: 72px;
  height: 58px;
  overflow: hidden;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;

  &.placeholder {
    display: grid;
    place-items: center;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.legacy-file-info {
  min-width: 0;

  strong {
    display: block;
    margin-bottom: 8px;
    overflow: hidden;
    color: var(--el-text-color-primary);
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  :deep(.el-select) {
    width: 100%;
  }
}

.import-summary,
.mt15 {
  margin-top: 15px;
}

.mb15 {
  margin-bottom: 15px;
}

@media (max-width: 760px) {
  .legacy-panel-head,
  .legacy-file-card {
    grid-template-columns: 1fr;
  }

  .legacy-panel-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
