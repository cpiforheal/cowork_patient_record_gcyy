<template>
  <div class="disease-template-manage">
    <header class="dtm-head">
      <div>
        <strong>病种模板库</strong>
        <small
          >预置与孵化模板统一管理 · 编辑即时生效（历史病历按应用时文本固化，不受影响） · 候选同类
          {{ threshold }} 例提醒转正</small
        >
      </div>
      <div class="dtm-head-actions">
        <el-button :loading="loading" @click="load">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
        </el-button>
        <el-button v-if="canManage" type="primary" @click="openCreate">新建模板</el-button>
      </div>
    </header>

    <!-- 候选模板观察区 -->
    <section v-if="candidates.length" class="dtm-candidates">
      <div class="dtm-section-title">
        🔬 候选模板观察区
        <el-tag size="small" type="warning" effect="light">来自「其他/未分型」通用路径 · 同类 {{ threshold }} 例提醒转正</el-tag>
      </div>
      <el-table :data="candidates" size="small" border>
        <el-table-column prop="disease" label="候选病种" min-width="140" />
        <el-table-column prop="usageCount" label="累计同类" width="96">
          <template #default="{ row }">
            <el-tag size="small" :type="row.usageCount >= threshold ? 'success' : 'info'" effect="light">
              {{ row.usageCount }} 例
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="摘要" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.payload?.chiefComplaint || row.payload?.inspectionConclusion || "—" }}</template>
        </el-table-column>
        <el-table-column prop="updatedBy" label="最近操作" width="100" />
        <el-table-column prop="updatedAt" label="更新时间" width="160" />
        <el-table-column v-if="canManage" label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="success" :disabled="row.usageCount < threshold && !forcePromote" @click="promote(row)">
              {{ row.usageCount >= threshold ? "转正" : "未达阈值" }}
            </el-button>
            <el-button size="small" text @click="openEdit(row)">查看/编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-checkbox v-model="forcePromote" size="small">越过阈值强制转正（谨慎使用）</el-checkbox>
    </section>

    <!-- 正式模板列表 -->
    <section class="dtm-list">
      <div class="dtm-section-title">📚 正式模板（接诊下拉即取即用）</div>
      <el-table v-loading="loading" :data="actives" size="small" border>
        <el-table-column prop="disease" label="病种" min-width="120" />
        <el-table-column prop="version" label="版本" width="130" />
        <el-table-column label="主诉模板" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.payload?.chiefComplaint || "—" }}</template>
        </el-table-column>
        <el-table-column prop="usageCount" label="应用累计" width="96" />
        <el-table-column prop="updatedBy" label="最近操作" width="100" />
        <el-table-column prop="updatedAt" label="更新时间" width="160" />
        <el-table-column v-if="canManage" label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="warning" plain @click="changeStatus(row, 'archive')">归档</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section v-if="archived.length" class="dtm-list">
      <div class="dtm-section-title">🗄 已归档</div>
      <el-table :data="archived" size="small" border>
        <el-table-column prop="disease" label="病种" min-width="120" />
        <el-table-column prop="version" label="版本" width="130" />
        <el-table-column prop="updatedBy" label="最近操作" width="100" />
        <el-table-column prop="updatedAt" label="更新时间" width="160" />
        <el-table-column v-if="canManage" label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="success" plain @click="changeStatus(row, 'activate')">重新启用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 新建/编辑对话框 -->
    <el-dialog
      v-model="editorVisible"
      :title="editingId ? `编辑模板 · ${editorForm.disease}` : '新建病种模板'"
      width="720px"
      destroy-on-close
    >
      <div class="dtm-editor">
        <div class="dtm-row">
          <div class="dtm-field">
            <label>病种名称（必填）</label>
            <el-input
              v-model="editorForm.disease"
              placeholder="如：藏毛窦"
              :disabled="Boolean(editingId) && editingOrigin === 'preset'"
            />
          </div>
          <div class="dtm-field">
            <label>显示名称</label>
            <el-input v-model="editorForm.label" placeholder="默认同病种名称" />
          </div>
        </div>
        <label>主诉模板（支持 {duration} 等槽位变量）</label>
        <el-input
          v-model="editorForm.chiefComplaint"
          type="textarea"
          :rows="2"
          placeholder="如：肛周肿痛{duration}{aggravationPhrase}"
        />
        <label>现病史模板</label>
        <el-input
          v-model="editorForm.presentIllness"
          type="textarea"
          :rows="4"
          placeholder="支持 {duration}/{aggravationClause}/{diagnosis} 等槽位变量"
        />
        <label>专科检查结论模板</label>
        <el-input v-model="editorForm.inspectionConclusion" type="textarea" :rows="3" placeholder="截石位检查所见描述模板" />
        <label>症状词库（自动匹配用，逗号分隔）</label>
        <el-input v-model="editorForm.symptomsText" placeholder="便血, 肿物脱出, 肛门坠胀" />
        <label>视诊所见词库（逗号分隔）</label>
        <el-input v-model="editorForm.visualText" placeholder="肛缘赘皮增生, 肛管可见裂口" />
        <label>指诊所见词库（逗号分隔）</label>
        <el-input v-model="editorForm.digitalText" placeholder="触及痔核, 肛门括约肌紧张" />
        <label>镜检所见词库（逗号分隔）</label>
        <el-input v-model="editorForm.anoscopyText" placeholder="齿线上黏膜充血隆起" />
        <div v-if="editorForm.slots.length" class="dtm-slots">
          <label>槽位变量（{{ editorForm.slots.length }} 项）</label>
          <div v-for="(slotItem, index) in editorForm.slots" :key="slotItem.key" class="dtm-slot-row">
            <el-input v-model="slotItem.label" size="small" style="width: 120px" placeholder="槽位名" />
            <el-input v-model="slotItem.optionsText" size="small" placeholder="选项（逗号分隔）" />
            <el-input v-model="slotItem.default" size="small" style="width: 140px" placeholder="默认值" />
            <el-button size="small" text type="danger" @click="editorForm.slots.splice(index, 1)">删除</el-button>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存{{ editingId ? "（版本自动升级）" : "" }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="diseaseTemplateManage">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import { useUserStore } from "@/stores/modules/user";
import { clinicFetch, clinicJsonHeaders, parseClinicApiResponse } from "@/api/modules/clinic/http";
import { authHeaders } from "@/api/modules/authToken";

interface TemplateRow {
  id: string;
  disease: string;
  label: string;
  version: string;
  status: string;
  payload: any;
  usageCount: number;
  readyToPromote: boolean;
  updatedBy: string;
  updatedAt: string;
}

const userStore = useUserStore();
const role = computed(() => userStore.userInfo.role || "");
const canManage = computed(() => ["doctor", "admin", "inspection"].includes(role.value));

const templates = ref<TemplateRow[]>([]);
const threshold = ref(3);
const loading = ref(false);
const saving = ref(false);
const forcePromote = ref(false);

const editorVisible = ref(false);
const editingId = ref("");
const editingOrigin = ref("");
const editorForm = reactive({
  disease: "",
  label: "",
  chiefComplaint: "",
  presentIllness: "",
  inspectionConclusion: "",
  symptomsText: "",
  visualText: "",
  digitalText: "",
  anoscopyText: "",
  slots: [] as { key: string; label: string; optionsText: string; default: string }[]
});

const actives = computed(() => templates.value.filter(item => item.status === "ACTIVE"));
const candidates = computed(() => templates.value.filter(item => item.status === "CANDIDATE"));
const archived = computed(() => templates.value.filter(item => item.status === "ARCHIVED"));

const load = async () => {
  loading.value = true;
  try {
    const result = await clinicFetch("/disease-templates/manage", { headers: authHeaders() });
    const data = await parseClinicApiResponse<{ templates: TemplateRow[]; threshold: number }>(result);
    templates.value = data.templates || [];
    threshold.value = data.threshold || 3;
  } catch (error: any) {
    ElMessage.error(error?.message || "模板库加载失败");
  } finally {
    loading.value = false;
  }
};

const openCreate = () => {
  editingId.value = "";
  editingOrigin.value = "";
  Object.assign(editorForm, {
    disease: "",
    label: "",
    chiefComplaint: "",
    presentIllness: "",
    inspectionConclusion: "",
    symptomsText: "",
    visualText: "",
    digitalText: "",
    anoscopyText: "",
    slots: []
  });
  editorVisible.value = true;
};

const openEdit = (row: TemplateRow) => {
  editingId.value = row.id;
  editingOrigin.value = row.id.startsWith("tpl-candidate-") ? "candidate" : "preset";
  const payload = row.payload || {};
  Object.assign(editorForm, {
    disease: row.disease,
    label: row.label,
    chiefComplaint: payload.chiefComplaint || "",
    presentIllness: payload.presentIllness || "",
    inspectionConclusion: payload.inspectionConclusion || "",
    symptomsText: (payload.symptoms || []).join(", "),
    visualText: (payload.visual || []).join(", "),
    digitalText: (payload.digital || []).join(", "),
    anoscopyText: (payload.anoscopy || []).join(", "),
    slots: (payload.slots || []).map((slotItem: any) => ({
      key: slotItem.key,
      label: slotItem.label,
      optionsText: (slotItem.options || []).join(", "),
      default: Array.isArray(slotItem.default) ? slotItem.default.join(", ") : String(slotItem.default ?? "")
    }))
  });
  editorVisible.value = true;
};

const buildPayload = () => ({
  disease: editorForm.disease.trim(),
  label: editorForm.label.trim() || editorForm.disease.trim(),
  chiefComplaint: editorForm.chiefComplaint,
  presentIllness: editorForm.presentIllness,
  inspectionConclusion: editorForm.inspectionConclusion,
  symptoms: editorForm.symptomsText
    .split(/[,，]/)
    .map(item => item.trim())
    .filter(Boolean),
  visual: editorForm.visualText
    .split(/[,，]/)
    .map(item => item.trim())
    .filter(Boolean),
  digital: editorForm.digitalText
    .split(/[,，]/)
    .map(item => item.trim())
    .filter(Boolean),
  anoscopy: editorForm.anoscopyText
    .split(/[,，]/)
    .map(item => item.trim())
    .filter(Boolean),
  slots: editorForm.slots.map(slotItem => ({
    key: slotItem.key,
    label: slotItem.label,
    kind: slotItem.default.includes(",") ? "multi" : "select",
    options: slotItem.optionsText
      .split(/[,，]/)
      .map(item => item.trim())
      .filter(Boolean),
    default: slotItem.default.includes(",") ? slotItem.default.split(/[,，]/).map(item => item.trim()) : slotItem.default
  }))
});

const save = async () => {
  if (!editorForm.disease.trim()) {
    ElMessage.warning("请填写病种名称");
    return;
  }
  saving.value = true;
  try {
    const payload = buildPayload();
    if (editingId.value) {
      const result = await clinicFetch(`/disease-templates/${encodeURIComponent(editingId.value)}`, {
        method: "PUT",
        headers: clinicJsonHeaders(),
        body: JSON.stringify({
          disease: payload.disease,
          label: payload.label,
          payload
        })
      });
      await parseClinicApiResponse(result);
    } else {
      const result = await clinicFetch("/disease-templates", {
        method: "POST",
        headers: clinicJsonHeaders(),
        body: JSON.stringify({
          disease: payload.disease,
          label: payload.label,
          status: "ACTIVE",
          payload
        })
      });
      await parseClinicApiResponse(result);
    }
    ElMessage.success("模板已保存并即时生效");
    editorVisible.value = false;
    await load();
  } catch (error: any) {
    ElMessage.error(error?.message || "模板保存失败");
  } finally {
    saving.value = false;
  }
};

const changeStatus = async (row: TemplateRow, action: "archive" | "activate" | "promote") => {
  if (action === "archive") {
    try {
      await ElMessageBox.confirm(`确认归档「${row.disease}」？归档后接诊下拉不再显示，可随时重新启用。`, "归档模板", {
        type: "warning",
        confirmButtonText: "确认归档",
        cancelButtonText: "取消"
      });
    } catch {
      return;
    }
  }
  try {
    const result = await clinicFetch(`/disease-templates/${encodeURIComponent(row.id)}/${action}`, {
      method: "POST",
      headers: authHeaders()
    });
    await parseClinicApiResponse(result);
    ElMessage.success(action === "promote" ? `「${row.disease}」已转正为正式模板` : "操作完成");
    await load();
  } catch (error: any) {
    ElMessage.error(error?.message || "操作失败");
  }
};

const promote = async (row: TemplateRow) => {
  if (row.usageCount < threshold.value && !forcePromote.value) {
    ElMessage.warning(`同类病例累计 ${row.usageCount} 例，未达 ${threshold.value} 例阈值；如确需转正请勾选强制转正`);
    return;
  }
  try {
    await ElMessageBox.confirm(`确认将候选模板「${row.disease}」转正为正式模板？转正后接诊下拉即可选用。`, "候选转正", {
      type: "warning",
      confirmButtonText: "确认转正",
      cancelButtonText: "再观察"
    });
  } catch {
    return;
  }
  try {
    const result = await clinicFetch(`/clinic-api/disease-templates/${encodeURIComponent(row.id)}/promote`, {
      method: "POST",
      headers: authHeaders()
    });
    await parseClinicApiResponse(result);
    ElMessage.success("候选模板已转正");
    await load();
  } catch (error: any) {
    ElMessage.error(error?.message || "转正失败");
  }
};

onMounted(() => {
  void load();
});
</script>

<style scoped lang="scss">
.disease-template-manage {
  display: grid;
  gap: 16px;
}
.dtm-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  strong {
    font-size: 17px;
    color: var(--el-text-color-primary);
  }
  small {
    display: block;
    margin-top: 3px;
    color: var(--el-text-color-secondary);
  }
  .dtm-head-actions {
    display: flex;
    gap: 8px;
  }
}
.dtm-section-title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 700;
  color: var(--el-text-color-primary);
}
.dtm-candidates,
.dtm-list {
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: var(--el-bg-color);
}
.dtm-candidates {
  border-color: var(--el-color-warning-light-5);
  background: var(--el-color-warning-light-9);
}
.dtm-editor {
  display: grid;
  gap: 8px;
  label {
    display: block;
    margin-top: 6px;
    font-size: 12.5px;
    color: var(--el-text-color-regular);
  }
  .dtm-row {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
  }
  .dtm-slots {
    display: grid;
    gap: 6px;
    .dtm-slot-row {
      display: flex;
      gap: 8px;
      align-items: center;
    }
  }
}
</style>
