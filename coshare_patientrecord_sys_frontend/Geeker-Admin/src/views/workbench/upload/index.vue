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

      <el-tabs v-model="activeTab">
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
              <el-button type="primary" size="large" :icon="Upload" :loading="uploading" @click="submitQuickUpload">
                一键提交
              </el-button>
            </div>

            <el-progress
              v-if="uploading || uploadSuccess"
              class="mt12"
              :percentage="uploadProgress"
              :status="uploadSuccess ? 'success' : undefined"
            />
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
            title="导入后不会覆盖既有病历字段，只会把旧共享文件作为附件证据进入患者档案；无法自动归属的文件会进入待分拣清单。"
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
                <div class="el-upload__text">拖入旧共享病历中的图片 / 报告文件，或点击批量选择</div>
                <template #tip>
                  <div class="el-upload__tip">系统会先按文件名自动归类，无法识别的文件可在下方手工分拣。</div>
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
              <el-button type="primary" :icon="Upload" :loading="importing" @click="submitLegacyImport">导入旧共享病历</el-button>
              <el-button @click="clearLegacyForm">清空</el-button>
            </el-form-item>
          </el-form>

          <el-result
            v-if="importResult"
            icon="success"
            title="旧共享病历已导入"
            sub-title="资料已进入患者附件索引，可在病历预览中统一查看。"
          >
            <template #extra>
              <el-space wrap>
                <el-button type="primary" @click="router.push(`/patients/detail/${importResult.patient.id}`)">
                  查看患者病历
                </el-button>
                <el-button @click="importResult = null">继续导入</el-button>
              </el-space>
            </template>
          </el-result>

          <el-descriptions v-if="importResult" class="import-summary" :column="3" border>
            <el-descriptions-item label="患者">{{ importResult.patient.name }}</el-descriptions-item>
            <el-descriptions-item label="门诊/住院号">{{ importResult.patient.visitNo }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ importResult.patient.status }}</el-descriptions-item>
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
  getPatientListApi,
  importSharedCaseApi,
  uploadDocumentsApi,
  type UploadDocumentItem,
  type PatientRow,
  type SharedCaseImportResult
} from "@/api/modules/clinic";
import { roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";

interface UploadItem {
  id: string;
  type: string;
  files: UploadUserFile[];
}

interface LegacyFileCandidate {
  id: string;
  fileName: string;
  type: string;
  previewUrl: string;
  contentDataUrl: string;
  source: "file" | "text";
}

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();
const currentRole = computed(() => userStore.userInfo.role || "frontdesk");
const roleName = computed(() => roleLabel(currentRole.value));
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

const legacyFileList = ref<UploadUserFile[]>([]);
const legacyFileText = ref("");
const legacyCandidates = ref<LegacyFileCandidate[]>([]);
const importing = ref(false);
const importResult = ref<SharedCaseImportResult | null>(null);

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
      patient => patient.name.includes(keyword) || patient.visitNo.includes(keyword) || patient.doctor.includes(keyword)
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

const resetQuickUploadForNextPatient = async () => {
  uploadKeyword.value = "";
  matchedPatients.value = [];
  selectedPatient.value = undefined;
  uploadSuccess.value = false;
  uploadProgress.value = 0;
  await nextTick();
  uploadKeywordInput.value?.focus();
};

const submitQuickUpload = async () => {
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
    ElMessage.success("上传登记已完成");
    window.setTimeout(resetQuickUploadForNextPatient, 1500);
  } catch (error) {
    uploadProgress.value = 0;
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

const submitLegacyImport = async () => {
  const message = validateLegacyForm();
  if (message) {
    ElMessage.warning(message);
    return;
  }

  importing.value = true;
  try {
    const { data } = await importSharedCaseApi({
      ...legacyForm,
      folderName: legacyForm.folderName || `${legacyForm.patientName}-${legacyForm.visitNo}`,
      operator: roleName.value,
      role: currentRole.value,
      files: legacyFiles()
    });
    importResult.value = data;
    ElMessage.success("旧共享病历已导入患者档案");
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
};

watch(() => route.fullPath, syncRouteState);
onMounted(syncRouteState);
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
  .patient-results button,
  .upload-item {
    grid-template-columns: 1fr;
  }

  .camera-capture {
    width: 100%;
  }

  .upload-actions {
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
