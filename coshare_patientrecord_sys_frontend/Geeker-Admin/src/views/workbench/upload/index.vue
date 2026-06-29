<template>
  <div class="table-box upload-workbench">
    <section class="quick-upload-panel" :class="{ 'is-upload-success': uploadSuccess }">
      <div class="page-head">
        <div>
          <h2>上传资料</h2>
          <p>输入门诊号或姓名，确认患者后连续上传本科室资料。</p>
        </div>
        <el-tag size="large" effect="plain">{{ roleName }}模式</el-tag>
      </div>

      <section v-if="isInspectionMode" class="inspection-uploader">
        <div class="scan-row">
          <el-input
            ref="uploadKeywordInput"
            v-model="uploadKeyword"
            size="large"
            clearable
            placeholder="扫码或输入 门诊号 / 姓名 / 手机号后四位"
            @keyup.enter="searchPatients"
          />
          <el-button type="primary" size="large" :icon="Search" :loading="searching" @click="searchPatients">识别患者</el-button>
        </div>

        <div v-if="matchedPatients.length" class="patient-results">
          <button
            v-for="patient in matchedPatients"
            :key="patient.id"
            :class="{ active: selectedPatient?.id === patient.id }"
            @click="selectedPatient = patient"
          >
            <strong>{{ patient.name }}</strong>
            <span>{{ patient.visitNo }} · {{ patient.visitType }}</span>
            <el-tag :type="patient.riskType || 'info'" effect="plain">{{ patient.status }}</el-tag>
          </button>
        </div>

        <el-alert
          v-if="selectedPatient"
          class="mt12"
          type="success"
          show-icon
          :closable="false"
          :title="`已选择：${selectedPatient.name}，${selectedPatient.visitNo}`"
        />

        <section class="inspection-upload-panel">
          <div class="inspection-upload-head">
            <div>
              <strong>检查室图片目录上传</strong>
              <span>选择一个图片目录后批量入档，系统按文件名自动归类；无法识别的图片进入检查室待分拣。</span>
            </div>
            <el-tag effect="plain">{{ inspectionFiles.length }} 张图片</el-tag>
          </div>

          <div class="inspection-controls">
            <el-select v-model="inspectionType" size="large" placeholder="检查类型">
              <el-option label="自动识别" value="inspectionPending" />
              <el-option label="专科检查/肛门镜图片" value="inspectionImage" />
              <el-option label="B超/影像" value="ultrasound" />
              <el-option label="心电图" value="ecg" />
              <el-option label="复查照片" value="followup" />
            </el-select>
            <el-date-picker v-model="inspectionDate" size="large" type="date" value-format="YYYY-MM-DD" placeholder="检查日期" />
            <el-input v-model="inspectionRemark" size="large" clearable placeholder="备注，可不填" />
            <label class="directory-picker">
              <el-icon><UploadFilled /></el-icon>
              <span>选择图片目录</span>
              <input
                ref="inspectionDirectoryInput"
                type="file"
                accept="image/*"
                multiple
                webkitdirectory
                @change="handleInspectionDirectory"
              />
            </label>
          </div>

          <div v-if="inspectionFiles.length" class="inspection-preview-grid">
            <article v-for="item in inspectionFiles" :key="item.id">
              <el-image :src="item.previewUrl" fit="cover" :preview-src-list="inspectionPreviewUrls" preview-teleported />
              <div>
                <strong :title="item.fileName">{{ item.fileName }}</strong>
                <span>{{ item.sizeLabel }}</span>
              </div>
              <el-button link type="danger" @click="removeInspectionFile(item.id)">移除</el-button>
            </article>
          </div>

          <el-empty v-else description="还没有选择图片目录" />

          <div class="upload-actions">
            <el-button @click="clearInspectionFiles">清空</el-button>
            <el-button type="primary" size="large" :icon="Upload" :loading="uploading" @click="submitInspectionUpload">
              上传检查室图片
            </el-button>
          </div>

          <el-progress
            v-if="uploading || uploadSuccess"
            class="mt12"
            :percentage="uploadProgress"
            :status="uploadSuccess ? 'success' : undefined"
          />
        </section>
      </section>

      <el-tabs v-else v-model="activeTab">
        <el-tab-pane label="快捷上传" name="upload">
          <div class="quick-form">
            <div class="scan-row">
              <el-input
                ref="uploadKeywordInput"
                v-model="uploadKeyword"
                size="large"
                clearable
                placeholder="扫码或输入 门诊号 / 姓名 / 手机号后四位"
                @keyup.enter="searchPatients"
              />
              <el-button type="primary" size="large" :icon="Search" :loading="searching" @click="searchPatients">
                识别患者
              </el-button>
            </div>

            <div v-if="matchedPatients.length" class="patient-results">
              <button
                v-for="patient in matchedPatients"
                :key="patient.id"
                :class="{ active: selectedPatient?.id === patient.id }"
                @click="selectedPatient = patient"
              >
                <strong>{{ patient.name }}</strong>
                <span>{{ patient.visitNo }} · {{ patient.visitType }}</span>
                <el-tag :type="patient.riskType || 'info'" effect="plain">{{ patient.status }}</el-tag>
              </button>
            </div>

            <el-alert
              v-if="selectedPatient"
              class="mt12"
              type="success"
              show-icon
              :closable="false"
              :title="`已选择：${selectedPatient.name}，${selectedPatient.visitNo}`"
            />

            <div class="batch-upload-list">
              <div v-for="(item, index) in uploadItems" :key="item.id" class="upload-item">
                <el-select v-model="item.type" size="large" placeholder="资料类型">
                  <el-option v-for="type in documentTypes" :key="type.value" :label="type.label" :value="type.value" />
                </el-select>
                <el-upload v-model:file-list="item.files" action="#" :auto-upload="false" multiple class="compact-uploader">
                  <el-button :icon="UploadFilled">选择文件</el-button>
                </el-upload>
                <label class="camera-capture">
                  <el-icon><Camera /></el-icon>
                  <span>拍照直传</span>
                  <input type="file" accept="image/*" capture="environment" @change="handleCameraCapture($event, item)" />
                </label>
                <el-button v-if="uploadItems.length > 1" link type="danger" @click="removeUploadItem(index)">移除</el-button>
              </div>
            </div>

            <div class="upload-actions">
              <el-button :icon="CirclePlus" @click="addUploadItem">继续添加资料</el-button>
              <el-button type="primary" size="large" :icon="Upload" :loading="uploading" @click="() => submitQuickUpload()">
                一键提交
              </el-button>
            </div>

            <el-progress
              v-if="uploading || uploadSuccess"
              class="mt12"
              :percentage="uploadProgress"
              :status="uploadSuccess ? 'success' : undefined"
            />
            <div v-if="failedUploads.length || staleFailedUploadSummaries.length" class="upload-failure-panel">
              <div class="failure-panel-head">
                <strong>待重试上传</strong>
                <span>上传失败不会清空患者和资料上下文，本页内可直接恢复后重试。</span>
              </div>
              <article v-for="failure in failedUploads" :key="failure.id" class="failure-card">
                <div>
                  <strong>{{ failure.patient.name }} · {{ failure.patient.visitNo }}</strong>
                  <small>{{ failure.failedAt }} · {{ failure.message }}</small>
                  <em>{{ failure.fileNames.join("、") }}</em>
                </div>
                <div class="failure-actions">
                  <el-button size="small" @click="restoreFailedUpload(failure)">恢复编辑</el-button>
                  <el-button size="small" type="primary" :loading="uploading" @click="retryFailedUpload(failure)">
                    一键重试
                  </el-button>
                  <el-button size="small" text type="danger" @click="dismissFailedUpload(failure.id)">移除</el-button>
                </div>
              </article>
              <article v-for="summary in staleFailedUploadSummaries" :key="summary.id" class="failure-card is-stale">
                <div>
                  <strong>{{ summary.patientName }} · {{ summary.visitNo }}</strong>
                  <small>{{ summary.failedAt }} · 页面已刷新，请重新选择文件后提交</small>
                  <em>{{ summary.fileNames.join("、") }}</em>
                </div>
                <el-button size="small" text type="danger" @click="dismissStaleFailure(summary.id)">移除记录</el-button>
              </article>
            </div>
            <el-result
              v-if="uploadSuccess"
              class="upload-result"
              icon="success"
              title="上传已登记"
              sub-title="资料已进入患者档案，原始文件已写入磁盘文件服务，并同步留下操作日志。"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="旧共享病历导入" name="legacy">
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
                  <el-input v-model="legacyForm.folderName" placeholder="患者姓名-门诊号-资料日期" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="经办科室">
                  <el-select v-model="legacyForm.ownerDepartment" filterable>
                    <el-option v-for="item in departments" :key="item" :label="item" :value="item" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>

            <el-row :gutter="16">
              <el-col :span="8">
                <el-form-item label="患者姓名">
                  <el-input v-model="legacyForm.patientName" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="门诊/住院号">
                  <el-input v-model="legacyForm.visitNo" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="就诊类型">
                  <el-select v-model="legacyForm.visitType">
                    <el-option label="门诊" value="门诊" />
                    <el-option label="住院" value="住院" />
                    <el-option label="门诊医保" value="门诊医保" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>

            <el-form-item label="就诊日期">
              <el-date-picker v-model="legacyForm.visitDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
            </el-form-item>

            <el-form-item label="选择旧文件">
              <el-upload
                v-model:file-list="legacyFileList"
                drag
                multiple
                action="#"
                :auto-upload="false"
                :on-change="syncLegacyFiles"
                :on-remove="syncLegacyFiles"
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
                    已选 {{ legacyCandidates.length }} 份，已归类 {{ classifiedLegacyCount }} 份，待分拣
                    {{ pendingLegacyCount }} 份
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
                v-model="legacyFileText"
                type="textarea"
                :rows="5"
                placeholder="每行一个文件名，例如：患者姓名-门诊号-资料类型.pdf"
              />
            </el-form-item>

            <el-form-item>
              <el-button type="primary" :icon="Search" :loading="previewingLegacy" @click="previewLegacyImport">
                智能预检
              </el-button>
              <el-button
                type="success"
                :icon="Upload"
                :disabled="!legacyPreview"
                :loading="importing"
                @click="commitLegacyImport"
              >
                采纳入档
              </el-button>
              <el-button @click="clearLegacyForm">清空</el-button>
            </el-form-item>
          </el-form>

          <section v-if="legacyPreview" class="legacy-preview-panel">
            <div class="legacy-panel-head">
              <div>
                <strong>导入预检结果</strong>
                <span>
                  {{ patientMatchLabel[legacyPreview.patientMatch] }}，建议字段
                  {{ legacyPreview.fieldMappings.length }} 项，附件归属 {{ legacyPreview.attachmentMappings.length }} 项
                </span>
              </div>
              <el-tag :type="legacyPreview.unassigned.length ? 'warning' : 'success'" effect="plain">
                {{ legacyPreview.status }}
              </el-tag>
            </div>

            <el-table v-if="legacyPreview.fieldMappings.length" :data="legacyPreview.fieldMappings" border class="mt15">
              <el-table-column width="54">
                <template #default="{ row }">
                  <el-checkbox v-model="row.selected" :disabled="row.conflict && !allowOverwriteLegacyConflicts" />
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
              <el-checkbox v-model="allowOverwriteLegacyConflicts">允许采纳时覆盖已有字段</el-checkbox>
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
                <el-button type="primary" @click="router.push(`/patients/detail/${importResult.patient.id}`)">
                  查看患者档案
                </el-button>
                <el-button @click="importResult = null">继续导入</el-button>
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
        </el-tab-pane>
      </el-tabs>
    </section>
  </div>
</template>

<script setup lang="ts" name="workbenchUpload">
import { computed, nextTick, onMounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import type { UploadFile, UploadUserFile } from "element-plus";
import { useRoute, useRouter } from "vue-router";
import { Camera, CirclePlus, Search, Upload, UploadFilled } from "@element-plus/icons-vue";
import {
  commitSharedCaseImportApi,
  getPatientListApi,
  previewSharedCaseImportApi,
  uploadDocumentsApi,
  type UploadDocumentItem,
  type PatientRow,
  type SharedCaseImportResult,
  type SharedCasePreviewResult
} from "@/api/modules/clinic";
import { roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";

interface UploadItem {
  id: string;
  type: string;
  files: UploadUserFile[];
}

interface FailedUploadSummary {
  id: string;
  patientId: string;
  patientName: string;
  visitNo: string;
  failedAt: string;
  message: string;
  fileNames: string[];
}

interface FailedUpload extends Omit<FailedUploadSummary, "patientId" | "patientName" | "visitNo"> {
  patient: PatientRow;
  keyword: string;
  items: UploadItem[];
}

interface LegacyFileCandidate {
  id: string;
  fileName: string;
  type: string;
  previewUrl: string;
  contentDataUrl: string;
  source: "file" | "text";
}

interface InspectionFileItem {
  id: string;
  fileName: string;
  file: File;
  previewUrl: string;
  contentDataUrl: string;
  sizeLabel: string;
}

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();
const currentRole = computed(() => userStore.userInfo.role || "frontdesk");
const roleName = computed(() => roleLabel(currentRole.value));
const isInspectionMode = computed(() => currentRole.value === "inspection");
const departments = ["前台", "门诊", "化验室", "心电室", "B超/放射", "治疗室", "质控/病案", "信息/院办"];
const documentTypes = [
  { label: "血常规", value: "bloodRoutine" },
  { label: "凝血功能", value: "coagulation" },
  { label: "心电图", value: "ecg" },
  { label: "B超/影像", value: "ultrasound" },
  { label: "复查照片", value: "followup" }
];

const activeTab = ref(route.path.includes("/legacy") || route.query.tab === "legacy" ? "legacy" : "upload");
const uploadKeyword = ref("");
const searching = ref(false);
const uploading = ref(false);
const uploadSuccess = ref(false);
const uploadProgress = ref(0);
const uploadKeywordInput = ref<{ focus: () => void }>();
const matchedPatients = ref<PatientRow[]>([]);
const selectedPatient = ref<PatientRow>();
const uploadItems = ref<UploadItem[]>([{ id: "upload-1", type: "", files: [] }]);
const inspectionType = ref("inspectionPending");
const inspectionDate = ref("");
const inspectionRemark = ref("");
const inspectionFiles = ref<InspectionFileItem[]>([]);
const inspectionDirectoryInput = ref<HTMLInputElement>();
const inspectionPreviewUrls = computed(() => inspectionFiles.value.map(item => item.previewUrl));
const failedUploads = ref<FailedUpload[]>([]);
const staleFailedUploadSummaries = ref<FailedUploadSummary[]>([]);
const UPLOAD_FAILURE_SUMMARY_KEY = "clinic-upload-failure-summaries";

const legacyFileList = ref<UploadUserFile[]>([]);
const legacyFileText = ref("");
const legacyCandidates = ref<LegacyFileCandidate[]>([]);
const previewingLegacy = ref(false);
const importing = ref(false);
const importResult = ref<SharedCaseImportResult | null>(null);
const legacyPreview = ref<SharedCasePreviewResult | null>(null);
const allowOverwriteLegacyConflicts = ref(false);

const legacyForm = reactive({
  folderName: "",
  patientName: "",
  visitNo: "",
  visitDate: "",
  visitType: "门诊",
  ownerDepartment: "信息/院办"
});

const unassignedRows = computed(() => importResult.value?.unassigned.map(fileName => ({ fileName })) ?? []);
const classifiedLegacyCount = computed(() => legacyCandidates.value.filter(item => item.type).length);
const pendingLegacyCount = computed(() => legacyCandidates.value.length - classifiedLegacyCount.value);
const patientMatchLabel: Record<SharedCasePreviewResult["patientMatch"], string> = {
  matchedByVisitNo: "已按门诊/住院号匹配患者",
  matchedByName: "已按姓名匹配患者",
  newPatient: "将新建患者档案"
};
const legacySourceLabel: Record<string, string> = {
  document: "病历正文",
  report: "检查报告",
  followup: "复查随访",
  unassigned: "待分拣"
};

const readQueryString = (value: unknown) => (Array.isArray(value) ? value[0] : typeof value === "string" ? value : "");

const syncRouteState = () => {
  activeTab.value = route.path.includes("/legacy") || route.query.tab === "legacy" ? "legacy" : "upload";
  const keyword = readQueryString(route.query.keyword);
  if (keyword && !uploadKeyword.value) uploadKeyword.value = keyword;
};

const searchPatients = async () => {
  if (!uploadKeyword.value.trim()) {
    ElMessage.warning("请先输入门诊号或患者姓名");
    return;
  }
  searching.value = true;
  try {
    const keyword = uploadKeyword.value.trim();
    const { data } = await getPatientListApi({ pageNum: 1, pageSize: 100 });
    matchedPatients.value = data.list.filter(
      patient =>
        String(patient.name || "").includes(keyword) ||
        String(patient.visitNo || "").includes(keyword) ||
        String(patient.doctor || "").includes(keyword)
    );
    selectedPatient.value = matchedPatients.value[0];
    if (!matchedPatients.value.length) ElMessage.warning("未找到匹配患者");
  } finally {
    searching.value = false;
  }
};

const addUploadItem = () => {
  uploadItems.value.push({ id: `upload-${Date.now()}`, type: "", files: [] });
};

const removeUploadItem = (index: number) => {
  uploadItems.value.splice(index, 1);
};

const cloneUploadItems = (items: UploadItem[]) =>
  items.map(item => ({
    id: `${item.id}-${Date.now()}`,
    type: item.type,
    files: item.files.map(file => ({ ...file }))
  }));

const collectUploadFileNames = (items: UploadItem[]) => items.flatMap(item => item.files.map(file => file.name));

const loadFailedUploadSummaries = () => {
  try {
    const raw = sessionStorage.getItem(UPLOAD_FAILURE_SUMMARY_KEY);
    staleFailedUploadSummaries.value = raw ? (JSON.parse(raw) as FailedUploadSummary[]) : [];
  } catch {
    staleFailedUploadSummaries.value = [];
  }
};

const persistFailedUploadSummaries = () => {
  const summaries: FailedUploadSummary[] = failedUploads.value.map(failure => ({
    id: failure.id,
    patientId: failure.patient.id,
    patientName: failure.patient.name,
    visitNo: failure.patient.visitNo,
    failedAt: failure.failedAt,
    message: failure.message,
    fileNames: failure.fileNames
  }));
  sessionStorage.setItem(UPLOAD_FAILURE_SUMMARY_KEY, JSON.stringify([...summaries, ...staleFailedUploadSummaries.value]));
};

const rememberFailedUpload = (error: unknown) => {
  if (!selectedPatient.value) return;
  const failure: FailedUpload = {
    id: `failure-${Date.now()}`,
    patient: selectedPatient.value,
    keyword: uploadKeyword.value,
    items: cloneUploadItems(uploadItems.value),
    failedAt: new Date().toLocaleString("zh-CN", { hour12: false }),
    message: (error as Error)?.message || "上传失败，请稍后重试",
    fileNames: collectUploadFileNames(uploadItems.value)
  };
  failedUploads.value = [failure, ...failedUploads.value].slice(0, 5);
  persistFailedUploadSummaries();
};

const restoreFailedUpload = (failure: FailedUpload) => {
  uploadKeyword.value = failure.keyword || failure.patient.visitNo || failure.patient.name;
  matchedPatients.value = [failure.patient];
  selectedPatient.value = failure.patient;
  uploadItems.value = cloneUploadItems(failure.items);
  uploadSuccess.value = false;
  uploadProgress.value = 0;
};

const dismissFailedUpload = (id: string) => {
  failedUploads.value = failedUploads.value.filter(item => item.id !== id);
  persistFailedUploadSummaries();
};

const dismissStaleFailure = (id: string) => {
  staleFailedUploadSummaries.value = staleFailedUploadSummaries.value.filter(item => item.id !== id);
  persistFailedUploadSummaries();
};

const retryFailedUpload = async (failure: FailedUpload) => {
  restoreFailedUpload(failure);
  await nextTick();
  await submitQuickUpload(failure.id);
};

const timestampName = () => {
  const date = new Date();
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}-${pad(date.getHours())}${pad(
    date.getMinutes()
  )}${pad(date.getSeconds())}`;
};

const photoFileName = (file: File) => {
  const ext = file.name.includes(".") ? file.name.slice(file.name.lastIndexOf(".")) : ".jpg";
  return `现场拍照-${timestampName()}${ext}`;
};

const handleCameraCapture = (event: Event, item: UploadItem) => {
  if (!item.type) {
    ElMessage.warning("请先选择资料类型，再拍照直传");
    (event.target as HTMLInputElement).value = "";
    return;
  }
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  item.files.push({
    name: photoFileName(file),
    status: "ready",
    raw: file as UploadUserFile["raw"]
  });
  input.value = "";
  uploadSuccess.value = false;
  ElMessage.success("照片已加入待提交资料");
};

const imageFilePattern = /\.(png|jpe?g|gif|webp|bmp|svg)$/i;
const isImageFile = (file?: UploadUserFile["raw"], fileName = "") =>
  Boolean(file?.type?.startsWith("image/") || imageFilePattern.test(fileName));

const fileToDataUrl = (file?: UploadUserFile["raw"]) =>
  new Promise<string>(resolve => {
    if (!file) {
      resolve("");
      return;
    }
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => resolve("");
    reader.readAsDataURL(file);
  });

const imageOnly = (file: File) => file.type.startsWith("image/") || imageFilePattern.test(file.name);

const formatFileSize = (size: number) => {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
};

const handleInspectionDirectory = async (event: Event) => {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []).filter(imageOnly);
  if (!files.length) {
    ElMessage.warning("请选择包含图片的目录");
    input.value = "";
    return;
  }
  inspectionFiles.value.forEach(item => URL.revokeObjectURL(item.previewUrl));
  inspectionFiles.value = await Promise.all(
    files.map(async (file, index) => ({
      id: `inspection-${Date.now()}-${index}-${file.name}`,
      fileName: file.name,
      file,
      previewUrl: URL.createObjectURL(file),
      contentDataUrl: await fileToDataUrl(file as UploadUserFile["raw"]),
      sizeLabel: formatFileSize(file.size)
    }))
  );
  uploadSuccess.value = false;
  input.value = "";
};

const removeInspectionFile = (id: string) => {
  const target = inspectionFiles.value.find(item => item.id === id);
  if (target) URL.revokeObjectURL(target.previewUrl);
  inspectionFiles.value = inspectionFiles.value.filter(item => item.id !== id);
};

const clearInspectionFiles = () => {
  inspectionFiles.value.forEach(item => URL.revokeObjectURL(item.previewUrl));
  inspectionFiles.value = [];
  uploadProgress.value = 0;
  uploadSuccess.value = false;
  if (inspectionDirectoryInput.value) inspectionDirectoryInput.value.value = "";
};

const submitInspectionUpload = async () => {
  if (!selectedPatient.value) {
    ElMessage.warning("请先识别并选择患者");
    return;
  }
  if (!inspectionFiles.value.length) {
    ElMessage.warning("请先选择检查图片目录");
    return;
  }
  uploading.value = true;
  uploadSuccess.value = false;
  uploadProgress.value = 15;
  window.setTimeout(() => (uploadProgress.value = 55), 160);
  try {
    const batchName = `检查室图片-${inspectionDate.value || timestampName()}`;
    const typeLabel =
      documentTypes.find(type => type.value === inspectionType.value)?.label ||
      (inspectionType.value === "inspectionPending" ? "检查室待分拣" : "检查室图片");
    const { data } = await uploadDocumentsApi({
      patientId: selectedPatient.value.id,
      role: currentRole.value,
      operator: roleName.value,
      sourceRole: "inspection",
      batchId: `inspection-${selectedPatient.value.id}-${Date.now()}`,
      batchName,
      autoClassify: inspectionType.value === "inspectionPending",
      remark: inspectionRemark.value,
      documents: inspectionFiles.value.map(item => ({
        type: inspectionType.value,
        typeLabel,
        fileName: item.fileName,
        contentDataUrl: item.contentDataUrl,
        remark: inspectionRemark.value
      }))
    });
    uploadProgress.value = 100;
    uploadSuccess.value = true;
    selectedPatient.value = data.patient;
    clearInspectionFiles();
    ElMessage.success(`已上传 ${data.documents.length} 张检查室图片`);
  } catch (error) {
    uploadProgress.value = 0;
    ElMessage.error((error as Error).message || "检查室图片上传失败");
  } finally {
    uploading.value = false;
  }
};

const quickUploadDocuments = async () => {
  const documents: UploadDocumentItem[] = [];
  for (const item of uploadItems.value) {
    const typeLabel = documentTypes.find(type => type.value === item.type)?.label || item.type;
    for (const file of item.files) {
      documents.push({
        type: item.type,
        typeLabel,
        fileName: file.name,
        contentDataUrl: await fileToDataUrl(file.raw)
      });
    }
  }
  return documents;
};

const submitQuickUpload = async (retryFailureId?: string) => {
  if (!selectedPatient.value) {
    ElMessage.warning("请先识别并选择患者");
    return;
  }
  if (uploadItems.value.some(item => !item.type || !item.files.length)) {
    ElMessage.warning("请为每条资料选择类型并添加文件");
    return;
  }

  uploading.value = true;
  uploadSuccess.value = false;
  uploadProgress.value = 15;
  window.setTimeout(() => (uploadProgress.value = 55), 160);
  try {
    const { data } = await uploadDocumentsApi({
      patientId: selectedPatient.value.id,
      role: currentRole.value,
      operator: roleName.value,
      documents: await quickUploadDocuments()
    });
    uploadProgress.value = 100;
    uploadSuccess.value = true;
    selectedPatient.value = data.patient;
    uploadItems.value = [{ id: `upload-${Date.now()}`, type: "", files: [] }];
    if (retryFailureId) dismissFailedUpload(retryFailureId);
    ElMessage.success("上传登记已完成");
  } catch (error) {
    uploadProgress.value = 0;
    rememberFailedUpload(error);
    ElMessage.error((error as Error).message);
  } finally {
    uploading.value = false;
  }
};

const inferLegacyType = (fileName: string) => {
  if (/血常规/i.test(fileName)) return "bloodRoutine";
  if (/凝血|凝血功能/i.test(fileName)) return "coagulation";
  if (/心电|ecg/i.test(fileName)) return "ecg";
  if (/B超|彩超|超声|影像|放射/i.test(fileName)) return "ultrasound";
  if (/照片|复查|肛门|创面/i.test(fileName)) return "followup";
  return "";
};

const legacyTypeLabel = (type: string) => documentTypes.find(item => item.value === type)?.label || type;

const syncLegacyFiles = async (_uploadFile?: UploadFile, uploadFiles?: UploadFile[]) => {
  if (!uploadFiles) return;
  legacyFileList.value = uploadFiles;
  legacyPreview.value = null;
  importResult.value = null;
  legacyCandidates.value = await Promise.all(
    uploadFiles.map(async (file, index) => {
      const contentDataUrl = await fileToDataUrl(file.raw);
      return {
        id: `${file.uid || index}-${file.name}`,
        fileName: file.name,
        type: legacyCandidates.value.find(item => item.fileName === file.name)?.type || inferLegacyType(file.name),
        previewUrl: isImageFile(file.raw, file.name) ? contentDataUrl : "",
        contentDataUrl,
        source: "file" as const
      };
    })
  );
};

const fileNamesFromText = () =>
  legacyFileText.value
    .split(/\r?\n/)
    .map(item => item.trim())
    .filter(Boolean);

const legacyFiles = () => {
  const selected = legacyCandidates.value.map(item => ({
    fileName: item.fileName,
    type: item.type,
    typeLabel: legacyTypeLabel(item.type),
    contentDataUrl: item.contentDataUrl
  }));
  const typedNames = new Set(selected.map(item => item.fileName));
  const textOnly = fileNamesFromText()
    .filter(fileName => !typedNames.has(fileName))
    .map(fileName => {
      const type = inferLegacyType(fileName);
      return {
        fileName,
        type,
        typeLabel: legacyTypeLabel(type),
        url: ""
      };
    });
  return [...selected, ...textOnly];
};

const validateLegacyForm = () => {
  if (!legacyForm.patientName.trim()) return "请填写患者姓名";
  if (!legacyForm.visitNo.trim()) return "请填写门诊/住院号";
  if (!legacyForm.visitDate) return "请选择就诊日期";
  if (!legacyFiles().length) return "请至少选择文件或粘贴一条旧文件名";
  return "";
};

const legacyImportPayload = async () => ({
  ...legacyForm,
  folderName: legacyForm.folderName || `${legacyForm.patientName}-${legacyForm.visitNo}`,
  operator: roleName.value,
  role: currentRole.value,
  files: legacyFiles()
});

const previewLegacyImport = async () => {
  const message = validateLegacyForm();
  if (message) {
    ElMessage.warning(message);
    return;
  }

  previewingLegacy.value = true;
  importResult.value = null;
  try {
    const { data } = await previewSharedCaseImportApi(await legacyImportPayload());
    legacyPreview.value = data;
    ElMessage.success("旧共享病历预检完成，请确认采纳项");
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    previewingLegacy.value = false;
  }
};

const commitLegacyImport = async () => {
  const message = validateLegacyForm();
  if (message) {
    ElMessage.warning(message);
    return;
  }
  if (!legacyPreview.value) {
    ElMessage.warning("请先完成智能预检");
    return;
  }

  importing.value = true;
  try {
    const preview = legacyPreview.value;
    const { data } = await commitSharedCaseImportApi({
      ...(await legacyImportPayload()),
      preview,
      acceptedFieldMappingIds: preview.fieldMappings.filter(item => item.selected).map(item => item.id),
      acceptedAttachmentMappingIds: preview.attachmentMappings.filter(item => item.selected).map(item => item.id),
      overwriteConflicts: allowOverwriteLegacyConflicts.value
    });
    importResult.value = data;
    legacyPreview.value = data.preview || null;
    ElMessage.success("旧共享病历已采纳入档");
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    importing.value = false;
  }
};

const clearLegacyForm = () => {
  Object.assign(legacyForm, {
    folderName: "",
    patientName: "",
    visitNo: "",
    visitDate: "",
    visitType: "门诊",
    ownerDepartment: "信息/院办"
  });
  legacyFileList.value = [];
  legacyFileText.value = "";
  legacyCandidates.value = [];
  importResult.value = null;
  legacyPreview.value = null;
  allowOverwriteLegacyConflicts.value = false;
};

watch(() => route.fullPath, syncRouteState);
onMounted(() => {
  loadFailedUploadSummaries();
  syncRouteState();
});
</script>

<style scoped lang="scss">
.upload-workbench {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.quick-upload-panel {
  padding: 16px;
  background: #ffffff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    background-color 0.2s ease;

  &.is-upload-success {
    background: linear-gradient(180deg, #ffffff 0%, var(--hos-status-success-soft) 100%);
    border-color: #86efac;
    box-shadow: 0 0 0 3px rgb(34 197 94 / 12%);
  }
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;

  h2,
  p {
    margin: 0;
  }

  p {
    margin-top: 8px;
    color: var(--el-text-color-regular);
  }
}

.quick-form,
.legacy-form {
  margin-top: 12px;
}

.scan-row {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) 128px;
  gap: 10px;
}

.patient-results {
  display: grid;
  gap: 8px;
  margin-top: 12px;

  button {
    display: grid;
    grid-template-columns: 120px minmax(0, 1fr) auto;
    gap: 10px;
    align-items: center;
    padding: 10px 12px;
    text-align: left;
    cursor: pointer;
    background: #ffffff;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;

    &.active {
      background: var(--el-color-primary-light-9);
      border-color: var(--el-color-primary-light-5);
    }
  }
}

.batch-upload-list {
  display: grid;
  gap: 10px;
  margin-top: 14px;
}

.upload-item {
  display: grid;
  grid-template-columns: 200px minmax(220px, 1fr) 112px 60px;
  gap: 10px;
  align-items: center;
  padding: 10px;
  background: #f8fafc;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  transition:
    border-color 0.18s ease,
    background-color 0.18s ease,
    transform 0.18s ease;

  &:hover {
    background: #ffffff;
    border-color: var(--el-color-primary-light-5);
    transform: translateY(-1px);
  }
}

.camera-capture {
  position: relative;
  display: inline-flex;
  gap: 6px;
  align-items: center;
  justify-content: center;
  height: 32px;
  padding: 0 12px;
  color: var(--el-color-primary);
  white-space: nowrap;
  cursor: pointer;
  background: #ffffff;
  border: 1px solid var(--el-color-primary-light-5);
  border-radius: 4px;
  transition:
    color 0.2s,
    border-color 0.2s,
    background 0.2s;

  &:hover {
    color: #ffffff;
    background: var(--el-color-primary);
    border-color: var(--el-color-primary);
  }

  input {
    position: absolute;
    width: 1px;
    height: 1px;
    opacity: 0;
    pointer-events: none;
  }
}

.compact-uploader {
  :deep(.el-upload-list) {
    display: inline-flex;
    flex-wrap: wrap;
    gap: 4px 10px;
    margin-left: 10px;
    vertical-align: middle;
  }
}

.upload-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
}

.upload-result {
  padding: 8px 0 0;
  animation: upload-result-pop 0.22s ease-out;
}

.inspection-uploader {
  display: grid;
  gap: 14px;
}

.inspection-upload-panel {
  padding: 14px;
  background: #f8fafc;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.inspection-upload-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;

  strong,
  span {
    display: block;
  }

  strong {
    color: var(--el-text-color-primary);
    font-size: 16px;
  }

  span {
    margin-top: 4px;
    color: var(--el-text-color-regular);
    font-size: 13px;
  }
}

.inspection-controls {
  display: grid;
  grid-template-columns: 180px 180px minmax(220px, 1fr) 156px;
  gap: 10px;
  align-items: center;
}

.directory-picker {
  position: relative;
  display: inline-flex;
  gap: 7px;
  align-items: center;
  justify-content: center;
  height: 40px;
  color: #ffffff;
  font-weight: 700;
  cursor: pointer;
  background: var(--el-color-primary);
  border-radius: 6px;

  input {
    position: absolute;
    width: 1px;
    height: 1px;
    opacity: 0;
    pointer-events: none;
  }
}

.inspection-preview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(172px, 1fr));
  gap: 10px;
  margin-top: 14px;

  article {
    display: grid;
    gap: 8px;
    min-width: 0;
    padding: 8px;
    background: #ffffff;
    border: 1px solid var(--el-border-color-light);
    border-radius: 8px;
  }

  :deep(.el-image) {
    width: 100%;
    height: 118px;
    overflow: hidden;
    background: var(--el-fill-color-light);
    border-radius: 6px;
  }

  strong,
  span {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong {
    color: var(--el-text-color-primary);
    font-size: 13px;
  }

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.upload-failure-panel {
  display: grid;
  gap: 10px;
  padding: 12px;
  margin-top: 14px;
  background: var(--hos-status-warning-soft);
  border: 1px solid rgb(217 119 6 / 22%);
  border-radius: 8px;
}

.failure-panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;

  strong,
  span {
    display: block;
  }

  span {
    color: var(--el-text-color-regular);
    font-size: 13px;
  }
}

.failure-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding: 10px 12px;
  background: rgb(255 255 255 / 78%);
  border: 1px solid rgb(217 119 6 / 18%);
  border-radius: 8px;

  &.is-stale {
    background: rgb(255 255 255 / 56%);
  }

  strong,
  small,
  em {
    display: block;
  }

  small {
    margin-top: 3px;
    color: var(--el-text-color-regular);
  }

  em {
    margin-top: 5px;
    overflow: hidden;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    font-style: normal;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.failure-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

@keyframes upload-result-pop {
  from {
    opacity: 0;
    transform: translateY(6px) scale(0.98);
  }

  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
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
.mt12 {
  margin-top: 12px;
}

.mt15 {
  margin-top: 15px;
}

.mb15 {
  margin-bottom: 15px;
}

@media (max-width: 760px) {
  .page-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .scan-row,
  .inspection-controls,
  .patient-results button,
  .upload-item,
  .failure-card {
    grid-template-columns: 1fr;
  }

  .camera-capture {
    width: 100%;
  }

  .upload-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .failure-panel-head,
  .failure-actions {
    align-items: stretch;
    flex-direction: column;
  }

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
