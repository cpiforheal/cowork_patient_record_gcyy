<template>
  <section v-if="template" class="patient-consumption-workspace">
    <div class="patient-toolbar">
      <div>
        <h2>患者耗用草稿</h2>
        <p>一条草稿对应一次实际服务；保存和导出均不扣库存、不生成流水。</p>
      </div>
      <div class="toolbar-actions">
        <el-date-picker v-model="businessDate" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        <el-button @click="createDraft">新建服务草稿</el-button>
        <el-button :loading="exporting === 'details'" @click="exportDrafts('details')">导出患者明细</el-button>
        <el-button :loading="exporting === 'summary'" @click="exportDrafts('summary')">导出科室汇总</el-button>
        <el-button type="primary" :loading="saving" :icon="DocumentChecked" @click="saveDraft">保存患者草稿</el-button>
      </div>
    </div>

    <div class="patient-workspace-grid">
      <section class="input-pane">
        <div class="pane-heading">
          <div>
            <h3>填写与修正</h3>
            <p>先确定患者、就诊记录和服务项目，再填写实际耗用。</p>
          </div>
        </div>
        <div class="patient-form-grid">
          <label
            ><span>患者</span>
            <el-select
              v-model="draft.patientId"
              filterable
              remote
              :remote-method="searchPatients"
              :loading="patientSearching"
              placeholder="按姓名或就诊号搜索"
              @focus="searchPatients('')"
              @change="selectPatient"
            >
              <el-option
                v-if="draft.patientId && draft.patientName && !patientOptions.some(patient => patient.id === draft.patientId)"
                :label="patientLabel({ id: draft.patientId, name: draft.patientName, visitNo: draft.visitNo || '' })"
                :value="draft.patientId"
              />
              <el-option v-for="patient in patientOptions" :key="patient.id" :label="patientLabel(patient)" :value="patient.id" />
            </el-select>
          </label>
          <label
            ><span>就诊记录</span>
            <el-select
              v-model="draft.encounterId"
              :disabled="!draft.patientId"
              placeholder="请选择具体就诊记录"
              @change="selectEncounter"
            >
              <el-option
                v-for="encounter in encounterOptions"
                :key="encounter.id"
                :label="encounterLabel(encounter)"
                :value="encounter.id"
              />
            </el-select>
          </label>
          <label
            ><span>实际服务时间</span
            ><el-date-picker v-model="draft.serviceAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" :clearable="false"
          /></label>
          <label
            ><span>服务项目</span>
            <el-select
              v-model="selectedServiceIds"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="可选择多个服务项目"
              @change="buildLinesFromServices"
            >
              <el-option v-for="service in serviceItems" :key="service.id" :label="service.name" :value="service.id" />
            </el-select>
          </label>
        </div>

        <el-table :data="draft.lines" class="input-table" height="calc(100vh - 485px)" min-height="300" table-layout="fixed">
          <el-table-column prop="serviceItemName" label="服务项目" min-width="128" show-overflow-tooltip />
          <el-table-column prop="materialName" label="耗材" min-width="155" show-overflow-tooltip />
          <el-table-column prop="unit" label="单位" width="74" />
          <el-table-column prop="standardQuantity" label="模板定额" width="96"
            ><template #default="{ row }">{{
              row.standardQuantity === null ? "待核定" : formatQuantity(row.standardQuantity)
            }}</template></el-table-column
          >
          <el-table-column label="实际数量" width="120"
            ><template #default="{ row }"
              ><el-input-number
                v-model="row.actualQuantity"
                :min="0"
                :precision="6"
                controls-position="right"
                @change="normalizeActualQuantity(row)" /></template
          ></el-table-column>
          <el-table-column label="例外原因" min-width="150"
            ><template #default="{ row }"
              ><el-input v-model="row.exceptionReason" maxlength="120" placeholder="与模板不同时填写" /></template
          ></el-table-column>
        </el-table>
      </section>

      <section class="preview-pane">
        <div class="pane-heading">
          <div>
            <h3>实时预览</h3>
            <p>展示本次实际服务耗材，不包含月度预测量。</p>
          </div>
          <span class="draft-state">{{ draft.revision ? `已保存 v${draft.revision}` : "未保存" }}</span>
        </div>
        <div class="preview-summary">
          <span
            ><small>服务项目</small><strong>{{ selectedServiceIds.length }}</strong></span
          >
          <span
            ><small>耗材行</small><strong>{{ draft.lines.length }}</strong></span
          >
          <span
            ><small>实际用量（按单位）</small
            ><strong class="unit-summary">{{ quantitySummary || "暂无可汇总数量" }}</strong></span
          >
          <span
            ><small>待核定/无单位</small><strong>{{ excludedLineCount }} 行</strong></span
          >
        </div>
        <el-table :data="draft.lines" class="preview-table" height="300" table-layout="fixed">
          <el-table-column prop="serviceItemName" label="服务项目" min-width="130" show-overflow-tooltip />
          <el-table-column prop="materialName" label="耗材" min-width="150" show-overflow-tooltip />
          <el-table-column label="实际数量" width="104"
            ><template #default="{ row }">{{ formatQuantity(row.actualQuantity) }} {{ row.unit }}</template></el-table-column
          >
          <el-table-column prop="exceptionReason" label="例外原因" min-width="150" show-overflow-tooltip />
        </el-table>
        <div class="recent-heading">
          <h3>当日已保存草稿</h3>
          <el-button text @click="loadDrafts">刷新</el-button>
        </div>
        <el-table :data="savedDrafts" height="calc(100vh - 610px)" min-height="160" table-layout="fixed" @row-click="openDraft">
          <el-table-column prop="patientName" label="患者" min-width="105" />
          <el-table-column prop="visitNo" label="就诊号" min-width="120" />
          <el-table-column prop="serviceAt" label="服务时间" min-width="155" />
          <el-table-column label="服务项目" min-width="150"
            ><template #default="{ row }">{{ row.serviceItems.map(item => item.name).join("、") }}</template></el-table-column
          >
          <el-table-column prop="operator" label="操作人" width="100" />
        </el-table>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { DocumentChecked } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { getPatientListApi, type PatientRow } from "@/api/modules/clinic";
import type { PatientEncounter } from "@/api/modules/clinic/types";
import {
  downloadInventoryPatientConsumptionDraftsApi,
  getInventoryPatientConsumptionDraftApi,
  listInventoryPatientConsumptionDraftsApi,
  saveInventoryPatientConsumptionDraftApi,
  type InventoryPatientConsumptionDraft
} from "@/api/modules/inventory";
import { departmentTemplateByKey } from "../departmentConsumptionTemplates";

const props = defineProps<{ departmentKey: string; today: string }>();
const template = computed(() => departmentTemplateByKey.get(props.departmentKey));
const businessDate = ref(props.today);
const saving = ref(false);
const exporting = ref<"details" | "summary" | "">("");
const patientSearching = ref(false);
const patientOptions = ref<PatientRow[]>([]);
const encounterOptions = ref<PatientEncounter[]>([]);
const selectedServiceIds = ref<string[]>([]);
const savedDrafts = ref<InventoryPatientConsumptionDraft[]>([]);
const latestListKey = ref("");
const latestPatientSearch = ref("");

const emptyDraft = (): InventoryPatientConsumptionDraft => ({
  departmentKey: props.departmentKey,
  departmentName: template.value?.department || "",
  patientId: "",
  encounterId: "",
  businessDate: businessDate.value,
  serviceAt: `${businessDate.value} 09:00:00`,
  serviceItems: [],
  templateVersion: "xlsx-20260808",
  revision: 0,
  lines: []
});
const draft = ref<InventoryPatientConsumptionDraft>(emptyDraft());
const serviceItems = computed(() => {
  const unique = new Map<string, { id: string; name: string }>();
  template.value?.lines.forEach(line => {
    if (!line.serviceGroup || unique.has(line.serviceGroup)) return;
    unique.set(line.serviceGroup, { id: `service-${props.departmentKey}-${line.sourceRow}`, name: line.serviceGroup });
  });
  return [...unique.values()];
});
const serviceNameById = computed(() => new Map(serviceItems.value.map(item => [item.id, item.name])));
const patientLabel = (patient: Pick<PatientRow, "id" | "name" | "visitNo">) =>
  `${patient.name} · ${patient.visitNo || "无就诊号"}`;
const encounterLabel = (encounter: PatientEncounter) =>
  `${encounter.visitNo || "无就诊号"} · ${encounter.visitDate || "未填日期"} · ${encounter.visitType || "未分类"}`;
const formatQuantity = (value: number) => Number(value || 0).toLocaleString("zh-CN", { maximumFractionDigits: 6 });
const nonNegativeNumber = (value: unknown) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(0, Number(parsed.toFixed(6))) : 0;
};
const quantitySummary = computed(() => {
  const totals = new Map<string, number>();
  draft.value.lines.forEach(line => {
    const unit = line.unit.trim();
    if (line.standardQuantity === null || !unit) return;
    totals.set(unit, Number(((totals.get(unit) || 0) + nonNegativeNumber(line.actualQuantity)).toFixed(6)));
  });
  return [...totals.entries()].map(([unit, value]) => `${formatQuantity(value)} ${unit}`).join(" · ");
});
const excludedLineCount = computed(
  () => draft.value.lines.filter(line => line.standardQuantity === null || !line.unit.trim()).length
);

const createDraft = () => {
  draft.value = emptyDraft();
  selectedServiceIds.value = [];
  encounterOptions.value = [];
};

const searchPatients = async (keyword: string) => {
  const requestKey = keyword.trim();
  latestPatientSearch.value = requestKey;
  patientSearching.value = true;
  try {
    const params = {
      pageNum: 1,
      pageSize: 50,
      ...(requestKey ? (/^\d/.test(requestKey) ? { visitNo: requestKey } : { name: requestKey }) : {})
    };
    const response = await getPatientListApi(params);
    if (latestPatientSearch.value === requestKey) patientOptions.value = response.data.list;
  } catch (error) {
    if (latestPatientSearch.value === requestKey) ElMessage.error(error instanceof Error ? error.message : "患者查询失败");
  } finally {
    if (latestPatientSearch.value === requestKey) patientSearching.value = false;
  }
};

const selectPatient = () => {
  const patient = patientOptions.value.find(item => item.id === draft.value.patientId);
  draft.value.patientName = patient?.name || draft.value.patientName;
  draft.value.visitNo = patient?.visitNo || draft.value.visitNo;
  draft.value.encounterId = "";
  encounterOptions.value = patient?.encounterHistory || [];
};

const selectEncounter = () => {
  const encounter = encounterOptions.value.find(item => item.id === draft.value.encounterId);
  if (encounter) draft.value.visitNo = encounter.visitNo;
};

const buildLinesFromServices = () => {
  const selectedNames = new Set(selectedServiceIds.value.map(id => serviceNameById.value.get(id)).filter(Boolean));
  draft.value.serviceItems = selectedServiceIds.value.map(id => ({ id, name: serviceNameById.value.get(id) || id }));
  draft.value.lines = (template.value?.lines || [])
    .filter(line => selectedNames.has(line.serviceGroup))
    .map(line => ({
      id: `service-${props.departmentKey}-${line.sourceRow}-line-${line.sourceRow}`,
      serviceItemId: serviceItems.value.find(item => item.name === line.serviceGroup)?.id || "",
      serviceItemName: line.serviceGroup,
      materialName: line.materialName,
      unit: line.unit,
      standardQuantity: line.standardQuantity,
      actualQuantity: nonNegativeNumber(line.standardQuantity),
      exceptionReason: ""
    }));
};

const normalizeActualQuantity = (line: { actualQuantity?: number | null }) => {
  line.actualQuantity = nonNegativeNumber(line.actualQuantity);
};

const loadDrafts = async () => {
  if (!template.value || !businessDate.value) return;
  const requestKey = `${template.value.key}:${businessDate.value}`;
  latestListKey.value = requestKey;
  try {
    const response = await listInventoryPatientConsumptionDraftsApi({
      departmentKey: template.value.key,
      date: businessDate.value
    });
    if (latestListKey.value === requestKey) savedDrafts.value = response.data.list;
  } catch (error) {
    if (latestListKey.value === requestKey) ElMessage.error(error instanceof Error ? error.message : "读取患者耗用草稿失败");
  }
};

const openDraft = async (row: InventoryPatientConsumptionDraft) => {
  try {
    const response = await getInventoryPatientConsumptionDraftApi(row.id || "");
    if (businessDate.value !== response.data.businessDate) {
      businessDate.value = response.data.businessDate;
      await nextTick();
    }
    draft.value = response.data;
    selectedServiceIds.value = response.data.serviceItems.map(item => item.id);
    encounterOptions.value = [
      {
        id: response.data.encounterId,
        visitNo: response.data.visitNo || "",
        visitDate: response.data.businessDate,
        visitType: "",
        doctor: "",
        createdAt: ""
      }
    ];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取患者耗用草稿失败");
  }
};

const saveDraft = async () => {
  if (!draft.value.patientId || !draft.value.encounterId) return ElMessage.warning("请选择患者及具体就诊记录");
  if (!draft.value.serviceItems.length || !draft.value.lines.length)
    return ElMessage.warning("请至少选择一个有耗材模板的服务项目");
  saving.value = true;
  try {
    const response = await saveInventoryPatientConsumptionDraftApi({ ...draft.value, businessDate: businessDate.value });
    draft.value = response.data;
    selectedServiceIds.value = response.data.serviceItems.map(item => item.id);
    await loadDrafts();
    ElMessage.success("患者耗用草稿已保存，未扣减库存");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存患者耗用草稿失败");
  } finally {
    saving.value = false;
  }
};

const exportDrafts = async (kind: "details" | "summary") => {
  if (!template.value) return;
  exporting.value = kind;
  try {
    const { blob, filename } = await downloadInventoryPatientConsumptionDraftsApi(kind, {
      departmentKey: template.value.key,
      date: businessDate.value
    });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "导出患者耗用草稿失败");
  } finally {
    exporting.value = "";
  }
};

watch(
  [() => props.departmentKey, businessDate],
  () => {
    createDraft();
    void loadDrafts();
  },
  { immediate: true }
);
</script>

<style scoped lang="scss">
.patient-consumption-workspace {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.patient-toolbar,
.toolbar-actions,
.pane-heading,
.recent-heading {
  display: flex;
  align-items: center;
  gap: 12px;
}
.patient-toolbar,
.pane-heading,
.recent-heading {
  justify-content: space-between;
  flex-wrap: wrap;
}
.patient-toolbar h2,
.pane-heading h3,
.recent-heading h3 {
  margin: 0;
  color: var(--inventory-text);
}
.patient-toolbar h2 {
  font-size: 20px;
}
.pane-heading h3,
.recent-heading h3 {
  font-size: 16px;
}
.patient-toolbar p,
.pane-heading p {
  margin: 5px 0 0;
  color: var(--inventory-muted);
  font-size: 13px;
}
.toolbar-actions {
  flex-wrap: wrap;
}
.patient-workspace-grid {
  display: grid;
  grid-template-columns: minmax(500px, 0.95fr) minmax(560px, 1.35fr);
  gap: 18px;
  min-width: 0;
}
.input-pane,
.preview-pane {
  display: grid;
  align-content: start;
  gap: 14px;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--inventory-line);
  border-radius: 6px;
  background: #fff;
}
.patient-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 12px;
}
.patient-form-grid label {
  display: grid;
  gap: 5px;
  color: var(--inventory-text);
  font-size: 13px;
}
.patient-form-grid :deep(.el-select),
.patient-form-grid :deep(.el-date-editor) {
  width: 100%;
}
.input-table :deep(.el-input-number) {
  width: 100%;
}
.preview-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  border-block: 1px solid var(--inventory-line);
}
.preview-summary span {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border-right: 1px solid var(--inventory-line);
}
.preview-summary span:last-child {
  border-right: 0;
}
.preview-summary small,
.draft-state {
  color: var(--inventory-muted);
  font-size: 12px;
}
.preview-summary strong {
  color: var(--inventory-text);
  font-size: 17px;
  font-weight: 600;
}
.preview-summary .unit-summary {
  font-size: 14px;
  line-height: 1.45;
  word-break: break-word;
}
@media (max-width: 1240px) {
  .patient-workspace-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 680px) {
  .patient-form-grid,
  .preview-summary {
    grid-template-columns: 1fr;
  }
  .preview-summary span {
    border-right: 0;
    border-bottom: 1px solid var(--inventory-line);
  }
  .preview-summary span:last-child {
    border-bottom: 0;
  }
}
</style>
