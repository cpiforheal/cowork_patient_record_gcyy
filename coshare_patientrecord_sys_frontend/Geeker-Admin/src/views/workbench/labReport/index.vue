<template>
  <div class="table-box lab-report-page">
    <section class="page-head">
      <div>
        <h2>检验报告模板填写</h2>
        <p>左侧填写可变指标，右侧按检验报告样式即时预览；保存后同步到健康档案、附件索引和时间轴。</p>
      </div>
      <el-tag effect="plain" size="large">{{ roleName }} · 检验室模板</el-tag>
    </section>

    <section class="patient-strip">
      <div class="search-row">
        <el-input
          v-model="keyword"
          size="large"
          clearable
          placeholder="输入患者姓名、门诊/住院号或手机号后四位"
          @keyup.enter="searchPatients"
        />
        <el-button type="primary" size="large" :icon="Search" :loading="searching" @click="searchPatients">查找患者</el-button>
      </div>

      <div v-if="matchedPatients.length" class="patient-results">
        <button
          v-for="patient in matchedPatients"
          :key="patient.id"
          :class="{ active: selectedPatient?.id === patient.id }"
          @click="selectPatient(patient)"
        >
          <strong>{{ patient.name }}</strong>
          <span>{{ patient.visitNo }} · {{ patient.visitType }} · {{ patient.visitDate }}</span>
          <el-tag :type="patient.riskType || 'info'" effect="plain">{{ patient.status }}</el-tag>
        </button>
      </div>

      <el-alert
        v-if="selectedPatient"
        type="success"
        show-icon
        :closable="false"
        :title="`已选择：${selectedPatient.name} / ${selectedPatient.visitNo}，性别：${patientGender || '未填写'}`"
      />
    </section>

    <section class="workspace-layout">
      <aside class="template-sidebar">
        <button
          v-for="item in labReportTemplates"
          :key="item.id"
          :class="{ active: activeTemplateId === item.id }"
          @click="activeTemplateId = item.id"
        >
          <strong>{{ item.name }}</strong>
          <span>{{ item.subtitle }}</span>
        </button>
      </aside>

      <main class="editor-preview-grid">
        <section class="editor-panel">
          <div class="panel-title">
            <div>
              <strong>{{ activeTemplate.name }}</strong>
              <span>{{ activeTemplate.description }}</span>
            </div>
            <el-tag v-if="activeTemplate.id === 'biochemistry'" type="info" effect="plain">
              参考范围：{{ patientGender || "未填写性别" }}
            </el-tag>
          </div>

          <template v-if="activeTemplate.id === 'ecgImage'">
            <el-alert
              type="info"
              show-icon
              :closable="false"
              title="心电图来自专用设备，本页只做拍照或图片上传；保存后回填心电图状态为已查。"
            />
            <el-upload
              v-model:file-list="ecgFiles"
              drag
              action="#"
              :auto-upload="false"
              multiple
              accept="image/*"
              class="ecg-uploader"
            >
              <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
              <div class="el-upload__text">拖入心电图照片，或点击选择图片</div>
            </el-upload>
          </template>

          <template v-else>
            <el-form label-position="top" class="report-form">
              <el-row :gutter="12">
                <el-col :span="12">
                  <el-form-item label="检验日期">
                    <el-date-picker v-model="reportDate" type="date" value-format="YYYY-MM-DD" class="full-width" />
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="样本号/备注">
                    <el-input v-model="reportRemark" placeholder="可不填" />
                  </el-form-item>
                </el-col>
              </el-row>

              <div class="metric-list">
                <article v-for="metric in activeTemplate.metrics" :key="metric.key" class="metric-row">
                  <div class="metric-label">
                    <strong>{{ metric.name }}</strong>
                    <span>{{ metric.shortName }} {{ metric.unit ? `· ${metric.unit}` : "" }}</span>
                  </div>
                  <el-select
                    v-if="metric.input === 'select'"
                    v-model="formValues[metric.key]"
                    filterable
                    allow-create
                    default-first-option
                    placeholder="选择结果"
                  >
                    <el-option v-for="option in metric.options || []" :key="option" :label="option" :value="option" />
                  </el-select>
                  <el-input-number
                    v-else-if="metric.input === 'number'"
                    v-model="numericValues[metric.key]"
                    :precision="2"
                    :controls="false"
                    class="metric-number"
                    placeholder="填写数值"
                    @change="syncNumberValue(metric.key)"
                  />
                  <el-input v-else v-model="formValues[metric.key]" placeholder="填写结果" />
                  <small>参考：{{ metricReference(metric, patientGender) || "按报告单" }}</small>
                </article>
              </div>
            </el-form>
          </template>

          <div class="actions">
            <el-button :icon="Refresh" @click="resetTemplateValues">重置当前模板</el-button>
            <el-button :icon="Printer" :disabled="activeTemplate.id === 'ecgImage'" @click="printPreview">
              打印/导出预览
            </el-button>
            <el-button type="primary" :icon="FolderChecked" :loading="saving" @click="saveToArchive">保存入档</el-button>
          </div>
        </section>

        <section class="preview-panel">
          <div class="panel-title">
            <div>
              <strong>报告预览</strong>
              <span>预览只展示模板化报告，不替代原始 LIS 报告。</span>
            </div>
          </div>

          <div v-if="activeTemplate.id === 'ecgImage'" class="image-preview-grid">
            <article v-for="file in ecgFiles" :key="file.uid || file.name">
              <span>{{ file.name }}</span>
            </article>
            <el-empty v-if="!ecgFiles.length" description="请选择心电图图片" />
          </div>

          <article v-else ref="previewRef" class="report-paper">
            <header>
              <h3>固始中医肛肠医院检验报告单</h3>
              <p>{{ activeTemplate.name }}</p>
            </header>
            <div class="patient-line">
              <span>姓名：{{ selectedPatient?.name || "待选择" }}</span>
              <span>性别：{{ patientGender || "待补充" }}</span>
              <span>门诊/住院号：{{ selectedPatient?.visitNo || "待选择" }}</span>
              <span>日期：{{ reportDate }}</span>
            </div>
            <table>
              <thead>
                <tr>
                  <th>项目</th>
                  <th>简称</th>
                  <th>结果</th>
                  <th>单位</th>
                  <th>参考范围</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="metric in activeTemplate.metrics" :key="metric.key">
                  <td>{{ metric.name }}</td>
                  <td>{{ metric.shortName }}</td>
                  <td class="result-cell">{{ formValues[metric.key] || "" }}</td>
                  <td>{{ metric.unit || "" }}</td>
                  <td>{{ metricReference(metric, patientGender) }}</td>
                </tr>
              </tbody>
            </table>
            <footer>
              <span>检验者：{{ roleName }}</span>
              <span>备注：{{ reportRemark || "无" }}</span>
            </footer>
          </article>
        </section>
      </main>
    </section>
  </div>
</template>

<script setup lang="ts" name="labReportWorkbench">
import { computed, nextTick, reactive, ref, watch } from "vue";
import { ElMessage, type UploadUserFile } from "element-plus";
import { FolderChecked, Printer, Refresh, Search, UploadFilled } from "@element-plus/icons-vue";
import {
  getPatientDetailApi,
  getPatientListApi,
  savePatientRecordApi,
  uploadDocumentsApi,
  type PatientRow
} from "@/api/modules/clinic";
import { roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";
import { labReportTemplates, labTemplateById, metricReference, type LabTemplateId } from "./templates";

const today = () => new Date().toISOString().slice(0, 10);

const userStore = useUserStore();
const currentRole = computed(() => userStore.userInfo.role || "lab");
const roleName = computed(() => userStore.userInfo.name || roleLabel(currentRole.value));

const keyword = ref("");
const searching = ref(false);
const saving = ref(false);
const matchedPatients = ref<PatientRow[]>([]);
const selectedPatient = ref<PatientRow | null>(null);
const patientGender = ref("");
const activeTemplateId = ref<LabTemplateId>("bloodRoutine");
const reportDate = ref(today());
const reportRemark = ref("");
const formValues = reactive<Record<string, string>>({});
const numericValues = reactive<Record<string, number | undefined>>({});
const ecgFiles = ref<UploadUserFile[]>([]);
const previewRef = ref<HTMLElement>();

const activeTemplate = computed(() => labTemplateById(activeTemplateId.value));

const resetTemplateValues = () => {
  Object.keys(formValues).forEach(key => delete formValues[key]);
  Object.keys(numericValues).forEach(key => delete numericValues[key]);
  activeTemplate.value.metrics.forEach(metric => {
    formValues[metric.key] = metric.defaultValue || "";
    numericValues[metric.key] = undefined;
  });
  if (activeTemplate.value.id === "ecgImage") ecgFiles.value = [];
};

watch(activeTemplateId, resetTemplateValues, { immediate: true });

const searchPatients = async () => {
  searching.value = true;
  try {
    const { data } = await getPatientListApi({ pageNum: 1, pageSize: 5000 });
    const normalized = keyword.value.trim().toLowerCase();
    matchedPatients.value = data.list.filter(patient => {
      if (!normalized) return true;
      return (
        patient.name.toLowerCase().includes(normalized) ||
        patient.visitNo.toLowerCase().includes(normalized) ||
        patient.doctor.toLowerCase().includes(normalized)
      );
    });
    if (!matchedPatients.value.length) ElMessage.warning("没有找到匹配患者");
  } finally {
    searching.value = false;
  }
};

const selectPatient = async (patient: PatientRow) => {
  selectedPatient.value = patient;
  patientGender.value = "";
  try {
    const { data } = await getPatientDetailApi(patient.id);
    patientGender.value = data.fieldValues.gender || "";
  } catch {
    patientGender.value = "";
  }
};

const syncNumberValue = (key: string) => {
  const value = numericValues[key];
  formValues[key] = value === undefined || value === null ? "" : String(value);
};

const reportSummary = () => {
  const filled = activeTemplate.value.metrics
    .map(metric => {
      const value = String(formValues[metric.key] || "").trim();
      return value ? `${metric.shortName} ${value}${metric.unit || ""}` : "";
    })
    .filter(Boolean);
  return `${activeTemplate.value.name}：${filled.length ? filled.join("，") : "已按模板填写"}。`;
};

const htmlReport = () => {
  const rows = activeTemplate.value.metrics
    .map(
      metric =>
        `<tr><td>${metric.name}</td><td>${metric.shortName}</td><td>${formValues[metric.key] || ""}</td><td>${
          metric.unit || ""
        }</td><td>${metricReference(metric, patientGender.value)}</td></tr>`
    )
    .join("");
  return `<!doctype html><html><head><meta charset="utf-8"><title>${activeTemplate.value.name}</title><style>body{font-family:"Microsoft YaHei",Arial,sans-serif;padding:24px;color:#111}h1{text-align:center;font-size:22px}p{margin:6px 0}table{width:100%;border-collapse:collapse;margin-top:14px}th,td{border:1px solid #222;padding:7px;text-align:center}footer{margin-top:18px;display:flex;justify-content:space-between}</style></head><body><h1>固始中医肛肠医院检验报告单</h1><p>报告类型：${activeTemplate.value.name}</p><p>患者：${
    selectedPatient.value?.name || ""
  }　性别：${patientGender.value || ""}　门诊/住院号：${selectedPatient.value?.visitNo || ""}　日期：${
    reportDate.value
  }</p><table><thead><tr><th>项目</th><th>简称</th><th>结果</th><th>单位</th><th>参考范围</th></tr></thead><tbody>${rows}</tbody></table><footer><span>检验者：${
    roleName.value
  }</span><span>备注：${reportRemark.value || "无"}</span></footer></body></html>`;
};

const textToDataUrl = (text: string, mime = "text/html;charset=utf-8") => `data:${mime},${encodeURIComponent(text)}`;

const fileToDataUrl = (file?: File) =>
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

const buildArchiveValues = () => {
  if (activeTemplate.value.id === "ecgImage") {
    return { ecgStatus: "已查" };
  }
  const values: Record<string, string> = {
    [activeTemplate.value.fieldKey]: reportSummary()
  };
  activeTemplate.value.statusKeys.forEach(key => {
    values[key] = "已查";
  });
  if (activeTemplate.value.id === "bloodRoutine") {
    Object.assign(values, {
      bloodWbc: formValues.wbc || "",
      bloodNeuPercent: formValues.neuPercent || "",
      bloodLymPercent: formValues.lymPercent || "",
      bloodMonPercent: formValues.monPercent || "",
      bloodRbc: formValues.rbc || "",
      bloodHgb: formValues.hgb || "",
      bloodPlt: formValues.plt || ""
    });
  }
  if (activeTemplate.value.id === "postprandialGlucose") {
    values.postprandialGlucose = formValues.postprandialGlucose || "";
  }
  return values;
};

const validateBeforeSave = () => {
  if (!selectedPatient.value) return "请先选择患者";
  if (activeTemplate.value.id === "ecgImage" && !ecgFiles.value.length) return "请先选择心电图图片";
  if (activeTemplate.value.id === "ecgImage" && !["admin", "doctor", "nurse", "ecg"].includes(currentRole.value)) {
    return "当前账号不能回填心电图状态，请使用心电室、医生或管理员账号保存";
  }
  if (
    activeTemplate.value.id !== "ecgImage" &&
    activeTemplate.value.metrics.every(metric => !String(formValues[metric.key] || "").trim())
  ) {
    return "请至少填写一个检验指标";
  }
  return "";
};

const saveToArchive = async () => {
  const message = validateBeforeSave();
  if (message) {
    ElMessage.warning(message);
    return;
  }
  if (!selectedPatient.value) return;

  saving.value = true;
  try {
    const batchId = `lab-${selectedPatient.value.id}-${Date.now()}`;
    await savePatientRecordApi({
      id: selectedPatient.value.id,
      role: currentRole.value,
      operator: roleName.value,
      values: buildArchiveValues()
    });
    if (activeTemplate.value.id === "ecgImage") {
      const documents = await Promise.all(
        ecgFiles.value.map(async file => ({
          type: activeTemplate.value.documentType,
          typeLabel: activeTemplate.value.documentTypeLabel,
          fileName: file.name,
          contentDataUrl: await fileToDataUrl(file.raw),
          remark: reportRemark.value
        }))
      );
      await uploadDocumentsApi({
        patientId: selectedPatient.value.id,
        role: currentRole.value,
        operator: roleName.value,
        sourceRole: "ecg",
        batchId,
        batchName: `${activeTemplate.value.name}-${reportDate.value}`,
        documents
      });
    } else {
      await uploadDocumentsApi({
        patientId: selectedPatient.value.id,
        role: currentRole.value,
        operator: roleName.value,
        sourceRole: "lab",
        batchId,
        batchName: `${activeTemplate.value.name}-${reportDate.value}`,
        documents: [
          {
            type: activeTemplate.value.documentType,
            typeLabel: activeTemplate.value.documentTypeLabel,
            fileName: `${selectedPatient.value.name}-${activeTemplate.value.name}-${reportDate.value}.html`,
            contentDataUrl: textToDataUrl(htmlReport()),
            remark: reportRemark.value
          }
        ]
      });
    }
    ElMessage.success("检验报告已保存入档，并同步到附件索引与时间轴");
  } catch (error) {
    ElMessage.error((error as Error).message || "保存失败");
  } finally {
    saving.value = false;
  }
};

const printPreview = async () => {
  if (activeTemplate.value.id === "ecgImage") return;
  await nextTick();
  const html = htmlReport();
  const printWindow = window.open("", "_blank", "width=920,height=680");
  if (!printWindow) {
    ElMessage.warning("浏览器阻止了打印窗口，请允许弹窗后重试");
    return;
  }
  printWindow.document.open();
  printWindow.document.write(html);
  printWindow.document.close();
  printWindow.focus();
  printWindow.print();
};
</script>

<style scoped lang="scss">
.lab-report-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.page-head,
.patient-strip,
.editor-panel,
.preview-panel {
  padding: 16px;
  background: #ffffff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;

  h2,
  p {
    margin: 0;
  }

  p {
    margin-top: 8px;
    color: var(--el-text-color-regular);
  }
}

.patient-strip {
  display: grid;
  gap: 12px;
}

.search-row {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) 128px;
  gap: 10px;
}

.patient-results {
  display: grid;
  gap: 8px;

  button {
    display: grid;
    grid-template-columns: 110px minmax(0, 1fr) auto;
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

.workspace-layout {
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.template-sidebar {
  display: grid;
  gap: 8px;
  padding: 10px;
  background: #ffffff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;

  button {
    display: grid;
    gap: 4px;
    padding: 10px;
    text-align: left;
    cursor: pointer;
    background: #f8fafc;
    border: 1px solid transparent;
    border-radius: 6px;

    &.active {
      color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);
      border-color: var(--el-color-primary-light-5);
    }

    span {
      color: var(--el-text-color-regular);
      font-size: 12px;
      line-height: 1.4;
    }
  }
}

.editor-preview-grid {
  display: grid;
  grid-template-columns: minmax(380px, 0.9fr) minmax(420px, 1.1fr);
  gap: 12px;
  min-width: 0;
}

.panel-title {
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

.full-width {
  width: 100%;
}

.metric-list {
  display: grid;
  gap: 8px;
}

.metric-row {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) 150px 112px;
  gap: 10px;
  align-items: center;
  padding: 10px;
  background: #f8fafc;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;

  small {
    color: var(--el-text-color-secondary);
  }
}

.metric-label {
  min-width: 0;

  strong,
  span {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: var(--el-text-color-regular);
    font-size: 12px;
  }
}

.metric-number {
  width: 100%;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
}

.ecg-uploader {
  margin-top: 12px;
}

.report-paper {
  padding: 20px;
  color: #111827;
  background: #ffffff;
  border: 1px solid #111827;
  box-shadow: 0 8px 24px rgb(15 23 42 / 8%);

  header {
    text-align: center;

    h3,
    p {
      margin: 0;
    }

    h3 {
      font-size: 22px;
      font-weight: 700;
    }

    p {
      margin-top: 6px;
      font-size: 15px;
    }
  }

  .patient-line {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 8px;
    margin: 16px 0 10px;
    font-size: 13px;
  }

  table {
    width: 100%;
    border-collapse: collapse;
  }

  th,
  td {
    padding: 7px 6px;
    text-align: center;
    border: 1px solid #111827;
  }

  th {
    background: #f3f4f6;
  }

  .result-cell {
    font-weight: 700;
  }

  footer {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    margin-top: 14px;
    font-size: 13px;
  }
}

.image-preview-grid {
  display: grid;
  gap: 10px;

  article {
    padding: 10px;
    background: #f8fafc;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;
  }
}

@media (max-width: 1180px) {
  .workspace-layout,
  .editor-preview-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .page-head,
  .panel-title {
    flex-direction: column;
  }

  .search-row,
  .patient-results button,
  .metric-row,
  .report-paper .patient-line {
    grid-template-columns: 1fr;
  }
}
</style>
