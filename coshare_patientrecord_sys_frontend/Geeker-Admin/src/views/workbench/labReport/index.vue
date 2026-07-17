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
        <el-select
          v-model="selectedPatientKey"
          size="large"
          filterable
          clearable
          class="patient-picker"
          :loading="searching"
          placeholder="搜索今日患者姓名、门诊/住院号后选择"
          @focus="loadTodayPatients"
          @change="handlePatientSelection"
          @clear="clearPatientSelection"
        >
          <el-option
            v-for="patient in matchedPatients"
            :key="patientPickerKey(patient)"
            :label="`${patient.name} ${patient.visitNo} ${patient.visitDate}`"
            :value="patientPickerKey(patient)"
          >
            <div class="patient-option">
              <strong>{{ patient.name }}</strong>
              <span>{{ patient.visitNo }} · {{ patient.visitType }} · {{ patient.visitDate || "今日" }}</span>
            </div>
          </el-option>
        </el-select>
        <el-button type="primary" plain size="large" :icon="Search" :loading="searching" @click="refreshTodayPatients">
          刷新患者
        </el-button>
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
            <el-tag v-if="!canSaveActiveTemplate" type="warning" effect="plain">当前岗位只读</el-tag>
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
              :disabled="!canSaveActiveTemplate"
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
                    :disabled="!canSaveActiveTemplate"
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
                    :disabled="!canSaveActiveTemplate"
                    :precision="2"
                    :controls="false"
                    class="metric-number"
                    placeholder="填写数值"
                    @change="syncNumberValue(metric.key)"
                  />
                  <el-input v-else v-model="formValues[metric.key]" :disabled="!canSaveActiveTemplate" placeholder="填写结果" />
                  <small>参考：{{ metricReference(metric, patientGender) || "按报告单" }}</small>
                </article>
              </div>
            </el-form>
          </template>

          <div class="actions">
            <el-button :icon="Refresh" :disabled="!canSaveActiveTemplate" @click="resetTemplateValues">重置当前模板</el-button>
            <el-button :icon="Printer" :disabled="activeTemplate.id === 'ecgImage'" @click="printPreview">
              打印/导出预览
            </el-button>
            <el-button
              type="primary"
              :icon="FolderChecked"
              :loading="saving"
              :disabled="!canSaveActiveTemplate"
              @click="saveToArchive"
            >
              保存入档
            </el-button>
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
import { computed, nextTick, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, type UploadUserFile } from "element-plus";
import { FolderChecked, Printer, Refresh, Search, UploadFilled } from "@element-plus/icons-vue";
import { useRoute } from "vue-router";
import {
  getPatientDetailApi,
  getPatientListApi,
  getPreAiEncountersApi,
  getPreAiWorkspaceApi,
  savePreAiLabReportApi,
  savePatientRecordApi,
  uploadDocumentsApi,
  type PatientRow
} from "@/api/modules/clinic";
import { roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";
import { labReportTemplates, labTemplateById, metricReference, metricStorageKey, type LabTemplateId } from "./templates";
import { buildLabReportSummary } from "./summary";
import type { PreAiEncounterSummary } from "@/api/modules/clinic/preAi";

const today = () => new Date().toISOString().slice(0, 10);

const route = useRoute();
const userStore = useUserStore();
const currentRole = computed(() => userStore.userInfo.role || "lab");
const roleName = computed(() => userStore.userInfo.name || roleLabel(currentRole.value));
const canEditLabMetrics = computed(() => ["admin", "doctor", "lab"].includes(currentRole.value));

type LabPatientCandidate = PatientRow & {
  preAiEncounterId?: string;
  legacyPatientId?: string;
  preAiGender?: string;
};

const searching = ref(false);
const selectedPatientKey = ref("");
const todayPatientsLoaded = ref(false);
const saving = ref(false);
const matchedPatients = ref<LabPatientCandidate[]>([]);
const selectedPatient = ref<LabPatientCandidate | null>(null);
const patientGender = ref("");
const patientFieldValues = ref<Record<string, string>>({});
const activeTemplateId = ref<LabTemplateId>("bloodRoutine");
const reportDate = ref(today());
const reportRemark = ref("");
const formValues = reactive<Record<string, string>>({});
const numericValues = reactive<Record<string, number | undefined>>({});
const ecgFiles = ref<UploadUserFile[]>([]);
const previewRef = ref<HTMLElement>();

const patientPickerKey = (patient: LabPatientCandidate) =>
  patient.preAiEncounterId ? `encounter:${patient.preAiEncounterId}` : `patient:${patient.legacyPatientId || patient.id}`;

const mergeCandidates = (legacyPatients: PatientRow[], encounters: PreAiEncounterSummary[]) => {
  const candidates = new Map<string, LabPatientCandidate>();
  legacyPatients.forEach(patient => candidates.set(`legacy:${patient.id}`, { ...patient, legacyPatientId: patient.id }));
  encounters.forEach(encounter => {
    const linkedKey = encounter.sourcePatientId ? `legacy:${encounter.sourcePatientId}` : "";
    if (linkedKey && candidates.has(linkedKey)) {
      Object.assign(candidates.get(linkedKey)!, { preAiEncounterId: encounter.id, preAiGender: encounter.gender });
      return;
    }
    candidates.set(`preai:${encounter.id}`, {
      id: `preai:${encounter.id}`,
      name: encounter.patientName,
      visitNo: encounter.caseToken,
      visitDate: encounter.visitDate,
      visitType: encounter.route === "INPATIENT" ? "住院" : "门诊",
      doctor: "",
      currentStage: encounter.currentStage,
      completedCount: 0,
      progressPercent: 0,
      status: encounter.status,
      riskType: "info",
      createdAt: encounter.createdAt,
      updatedAt: encounter.updatedAt,
      preAiEncounterId: encounter.id,
      preAiGender: encounter.gender
    });
  });
  return Array.from(candidates.values());
};

const activeTemplate = computed(() => labTemplateById(activeTemplateId.value));
const canSaveActiveTemplate = computed(() =>
  activeTemplate.value.id === "ecgImage"
    ? ["admin", "doctor", "nurse", "ecg"].includes(currentRole.value)
    : canEditLabMetrics.value
);

const resetTemplateValues = () => {
  Object.keys(formValues).forEach(key => delete formValues[key]);
  Object.keys(numericValues).forEach(key => delete numericValues[key]);
  activeTemplate.value.metrics.forEach(metric => {
    formValues[metric.key] = metric.defaultValue || "";
    numericValues[metric.key] = undefined;
  });
  if (activeTemplate.value.id === "ecgImage") ecgFiles.value = [];
};

const hydrateTemplateValues = () => {
  resetTemplateValues();
  activeTemplate.value.metrics.forEach(metric => {
    const storedValue = String(patientFieldValues.value[metricStorageKey(activeTemplate.value.id, metric.key)] || "").trim();
    if (!storedValue) return;
    formValues[metric.key] = storedValue;
    if (metric.input === "number") numericValues[metric.key] = Number(storedValue);
  });
};

watch(activeTemplateId, hydrateTemplateValues, { immediate: true });

const loadTodayPatients = async () => {
  if (todayPatientsLoaded.value || searching.value) return;
  searching.value = true;
  try {
    const currentDate = today();
    const [{ data }, { data: preAiData }] = await Promise.all([
      getPatientListApi({
        pageNum: 1,
        pageSize: 5000,
        visitDateFrom: `${currentDate} 00:00:00`,
        visitDateTo: `${currentDate} 23:59:59`
      }),
      getPreAiEncountersApi()
    ]);
    const todayEncounters = preAiData.list.filter(encounter => encounter.visitDate?.startsWith(currentDate));
    matchedPatients.value = mergeCandidates(data.list, todayEncounters);
    todayPatientsLoaded.value = true;
  } catch {
    matchedPatients.value = [];
    ElMessage.warning("今日患者列表加载失败，可点击刷新重试");
  } finally {
    searching.value = false;
  }
};

const refreshTodayPatients = async () => {
  todayPatientsLoaded.value = false;
  await loadTodayPatients();
};

const handlePatientSelection = async (key?: string) => {
  const patient = matchedPatients.value.find(item => patientPickerKey(item) === key);
  if (patient) await selectPatient(patient);
};

const clearPatientSelection = () => {
  selectedPatient.value = null;
  selectedPatientKey.value = "";
  patientGender.value = "";
  patientFieldValues.value = {};
  resetTemplateValues();
};

const selectPatient = async (patient: LabPatientCandidate) => {
  selectedPatient.value = patient;
  selectedPatientKey.value = patientPickerKey(patient);
  patientGender.value = patient.preAiGender || "";
  patientFieldValues.value = {};
  if (!patient.legacyPatientId) {
    hydrateTemplateValues();
    return;
  }
  try {
    const { data } = await getPatientDetailApi(patient.legacyPatientId);
    patientGender.value = data.fieldValues.gender || "";
    patientFieldValues.value = data.fieldValues || {};
    hydrateTemplateValues();
  } catch {
    patientGender.value = "";
  }
};

const loadPatientFromRoute = async () => {
  const queryEncounterId = route.query.encounterId;
  const encounterId = String(Array.isArray(queryEncounterId) ? queryEncounterId[0] : queryEncounterId || "").trim();
  const queryPatientId = route.query.patientId;
  const routePatientId = String(Array.isArray(queryPatientId) ? queryPatientId[0] : queryPatientId || "").trim();
  if (!encounterId && (!routePatientId || String(selectedPatient.value?.legacyPatientId || "") === routePatientId)) return;

  searching.value = true;
  try {
    if (encounterId) {
      const { data: workspace } = await getPreAiWorkspaceApi(encounterId);
      let candidate: LabPatientCandidate = {
        id: workspace.encounter.sourcePatientId || `preai:${encounterId}`,
        name: workspace.encounter.patient.patientName || "待补姓名",
        visitNo: workspace.encounter.caseToken,
        visitDate: workspace.encounter.patient.visitDate || "",
        visitType: workspace.encounter.route === "INPATIENT" ? "住院" : "门诊",
        doctor: "",
        currentStage: workspace.encounter.currentStage,
        completedCount: 0,
        progressPercent: 0,
        status: workspace.encounter.status,
        riskType: "info",
        createdAt: workspace.encounter.createdAt,
        updatedAt: workspace.encounter.updatedAt,
        preAiEncounterId: encounterId,
        legacyPatientId: workspace.encounter.sourcePatientId || undefined,
        preAiGender: workspace.encounter.patient.gender || ""
      };
      if (candidate.legacyPatientId) {
        try {
          const { data: detail } = await getPatientDetailApi(candidate.legacyPatientId);
          candidate = {
            ...detail.patient,
            legacyPatientId: detail.patient.id,
            preAiEncounterId: encounterId,
            preAiGender: workspace.encounter.patient.gender || detail.fieldValues.gender || ""
          };
          patientFieldValues.value = detail.fieldValues || {};
        } catch {
          patientFieldValues.value = {};
        }
      }
      selectedPatient.value = candidate;
      selectedPatientKey.value = patientPickerKey(candidate);
      matchedPatients.value = [candidate];
      patientGender.value = candidate.preAiGender || "";
      hydrateTemplateValues();
      return;
    }
    const { data } = await getPatientDetailApi(routePatientId);
    const candidate: LabPatientCandidate = { ...data.patient, legacyPatientId: data.patient.id };
    selectedPatient.value = candidate;
    selectedPatientKey.value = patientPickerKey(candidate);
    matchedPatients.value = [candidate];
    patientGender.value = data.fieldValues.gender || "";
    patientFieldValues.value = data.fieldValues || {};
    hydrateTemplateValues();
  } catch (error) {
    ElMessage.error((error as Error).message || "患者信息加载失败");
  } finally {
    searching.value = false;
  }
};

onMounted(async () => {
  await loadTodayPatients();
  await loadPatientFromRoute();
});
watch(() => route.query.patientId, loadPatientFromRoute);
watch(() => route.query.encounterId, loadPatientFromRoute);

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

const textToDataUrl = (text: string, mime = "text/html;charset=utf-8") => {
  const bytes = new TextEncoder().encode(text);
  let binary = "";
  bytes.forEach(byte => {
    binary += String.fromCharCode(byte);
  });
  return `data:${mime};base64,${btoa(binary)}`;
};

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
    const values: Record<string, string> = { ecgStatus: "已查" };
    const summary = buildLabReportSummary({ ...patientFieldValues.value, ...values });
    if (summary) values.auxiliaryExamSummary = summary;
    return values;
  }
  const values: Record<string, string> = {
    [activeTemplate.value.fieldKey]: reportSummary()
  };
  activeTemplate.value.statusKeys.forEach(key => {
    values[key] = "已查";
  });
  activeTemplate.value.metrics.forEach(metric => {
    values[metricStorageKey(activeTemplate.value.id, metric.key)] = formValues[metric.key] || "";
  });
  const summary = buildLabReportSummary({ ...patientFieldValues.value, ...values });
  if (summary) values.auxiliaryExamSummary = summary;
  return values;
};

const validateBeforeSave = () => {
  if (!selectedPatient.value) return "请先选择患者";
  if (activeTemplate.value.id === "ecgImage" && !selectedPatient.value.legacyPatientId)
    return "心电图不属于本次前置病历化验室节点";
  if (!canSaveActiveTemplate.value) return "当前岗位只能查看该检验报告模板，不能保存检验数值";
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
    const archiveValues = buildArchiveValues();
    if (selectedPatient.value.legacyPatientId) {
      await savePatientRecordApi({
        id: selectedPatient.value.legacyPatientId,
        role: currentRole.value,
        operator: roleName.value,
        values: archiveValues
      });
      patientFieldValues.value = { ...patientFieldValues.value, ...archiveValues };
    }
    if (activeTemplate.value.id === "ecgImage" && selectedPatient.value.legacyPatientId) {
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
        patientId: selectedPatient.value.legacyPatientId,
        role: currentRole.value,
        operator: roleName.value,
        sourceRole: "ecg",
        batchId,
        batchName: `${activeTemplate.value.name}-${reportDate.value}`,
        documents
      });
    } else if (activeTemplate.value.id !== "ecgImage" && selectedPatient.value.legacyPatientId) {
      await uploadDocumentsApi({
        patientId: selectedPatient.value.legacyPatientId,
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
    if (selectedPatient.value.preAiEncounterId && activeTemplate.value.id !== "ecgImage") {
      const metrics = activeTemplate.value.metrics
        .map(metric => ({
          key: metric.key,
          name: metric.name,
          shortName: metric.shortName,
          value: String(formValues[metric.key] || "").trim(),
          unit: metric.unit || "",
          reference: metricReference(metric, patientGender.value) || ""
        }))
        .filter(metric => metric.value);
      await savePreAiLabReportApi(selectedPatient.value.preAiEncounterId, {
        templateId: activeTemplate.value.id,
        templateName: activeTemplate.value.name,
        reportDate: reportDate.value,
        remark: reportRemark.value,
        metrics
      });
    }
    ElMessage.success(
      selectedPatient.value.preAiEncounterId ? "检验报告已保存，并同步到前置病历" : "检验报告已保存入档，并同步到附件索引与时间轴"
    );
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

.patient-picker {
  width: 100%;
}

.patient-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
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
  .metric-row,
  .report-paper .patient-line {
    grid-template-columns: 1fr;
  }
}
</style>
