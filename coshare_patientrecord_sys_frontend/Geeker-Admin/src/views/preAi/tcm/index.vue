<template>
  <div class="tcm-workbench">
    <header class="tw-head">
      <div class="tw-title">
        <strong>中医工作台</strong>
        <small>中医科消化内科 · 只做四诊与辨证记录，不发号</small>
      </div>
      <div class="tw-actions">
        <el-date-picker
          v-model="selectedDate"
          type="date"
          value-format="YYYY-MM-DD"
          :clearable="false"
          size="default"
          @change="load"
        />
        <el-radio-group v-model="mode" size="default" @change="load">
          <el-radio-button value="today">今日</el-radio-button>
          <el-radio-button value="recent">近 7 天</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="keyword"
          class="tw-search"
          placeholder="姓名 / 电话"
          clearable
          :prefix-icon="Search"
          @keyup.enter="load"
          @clear="load"
        />
        <el-button :loading="loading" :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </header>

    <section class="tw-status-row">
      <button
        v-for="item in statusTabs"
        :key="item.value"
        type="button"
        class="tw-status"
        :class="[`is-${item.tone}`, { 'is-active': statusFilter === item.value }]"
        @click="statusFilter = statusFilter === item.value ? '' : item.value"
      >
        <span class="tw-dot" />
        <span class="tw-status-label">{{ item.label }}</span>
        <strong>{{ result.counts[item.value] || 0 }}</strong>
      </button>
      <span class="tw-range">{{ rangeLabel }}</span>
    </section>

    <section v-loading="loading" class="tw-list">
      <el-table v-if="visibleRows.length" :data="visibleRows" size="small" @row-click="handleRowClick">
        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <span class="tw-pill" :class="`is-${statusTone(row.tcmStatus)}`">{{ statusLabel(row.tcmStatus) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="患者" min-width="150">
          <template #default="{ row }">
            <div class="tw-patient">
              <strong>{{ row.patientName || "未命名" }}</strong>
              <small>{{ [row.gender, row.age && `${row.age} 岁`].filter(Boolean).join(" · ") || "—" }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="主诉 / 消化症状" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="tw-complaint">{{ row.registrationChiefComplaint || "—" }}</div>
            <small class="tw-sub">{{ (row.digestiveSymptoms || []).join("、") || "未点选症状" }}</small>
          </template>
        </el-table-column>
        <el-table-column label="辨证" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.tcmDisease || row.primarySyndrome">
              <div>{{ row.tcmDisease || "—" }}</div>
              <small class="tw-sub">{{ row.primarySyndrome || "未定主证" }}</small>
            </template>
            <span v-else class="tw-muted">未填写</span>
          </template>
        </el-table-column>
        <el-table-column label="登记时间" width="150">
          <template #default="{ row }">{{ shortTime(row.visitDate || row.registeredAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="96" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click.stop="handleRowClick(row)">
              {{ row.tcmStatus === "COMPLETED" ? "查看" : "接诊" }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else :image-size="80" :description="statusFilter ? '当前状态下没有患者' : '今天还没有中医科消化内科患者'" />
    </section>

    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="720px" destroy-on-close>
      <div v-if="currentRow" v-loading="detailLoading" class="tw-drawer">
        <section class="tw-summary">
          <div class="tw-summary-head">
            <span class="tw-pill" :class="`is-${statusTone(currentRow.tcmStatus)}`">{{ statusLabel(currentRow.tcmStatus) }}</span>
            <small>{{ currentRow.owningDepartmentName || "—" }} · 第 {{ currentRow.visitNo }} 次就诊</small>
            <el-button v-if="currentRow.patientCaseId" link type="primary" size="small" @click="openFullRecord">查看完整病历</el-button>
          </div>
          <div class="tw-summary-grid">
            <div><label>主诉</label><span>{{ currentRow.registrationChiefComplaint || "—" }}</span></div>
            <div><label>消化症状</label><span>{{ (currentRow.digestiveSymptoms || []).join("、") || "—" }}</span></div>
            <div><label>持续时间</label><span>{{ currentRow.digestiveDuration || "—" }}</span></div>
            <div><label>大便情况</label><span>{{ currentRow.stoolPattern || "—" }}</span></div>
            <div><label>过敏史</label><span>{{ displayValue(registration.allergyHistory) }}</span></div>
            <div><label>个人史</label><span>{{ displayValue(registration.personalHistory) }}</span></div>
          </div>
        </section>

        <section class="tw-form">
          <div class="tw-form-title">
            <strong>四诊与辨证</strong>
            <span v-if="completedAt">完成于 {{ completedAt }}</span>
            <span v-else>暂存草稿后状态为“诊疗中”</span>
          </div>
          <el-form label-position="top" :disabled="readOnly">
            <RegistrationFormFields :fields="tcmFields" :form="form" @patch="patchForm" />
          </el-form>
        </section>

        <footer class="tw-drawer-footer">
          <el-button @click="drawerVisible = false">关闭</el-button>
          <el-button :disabled="readOnly" :loading="saving" @click="saveDraft">保存草稿</el-button>
          <el-button type="primary" :disabled="readOnly" :loading="completing" @click="completeRecord">完成辨证</el-button>
        </footer>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts" name="TcmWorkbench">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { Refresh, Search } from "@element-plus/icons-vue";
import RegistrationFormFields from "@/views/preAi/encounters/components/RegistrationFormFields.vue";
import { preAiStages, type PreAiFieldConfig } from "@/views/preAi/encounters/fieldConfig";
import {
  completePreAiStageApi,
  getPreAiWorkspaceApi,
  getTcmWorkbenchApi,
  savePreAiStageApi,
  type PreAiWorkspace,
  type TcmWorkbenchResult,
  type TcmWorkbenchRow,
  type TcmWorkbenchStatus
} from "@/api/modules/clinic/preAi";

const router = useRouter();

const TCM_FIELD_KEYS = new Set([
  "tcmDisease",
  "primarySyndrome",
  "concurrentSyndrome",
  "inspection",
  "auscultationOlfaction",
  "inquiry",
  "palpation",
  "tongue",
  "pulse",
  "treatmentPrinciple"
]);

const statusTabs: Array<{ value: TcmWorkbenchStatus; label: string; tone: string }> = [
  { value: "WAITING", label: "待接诊", tone: "waiting" },
  { value: "IN_PROGRESS", label: "诊疗中", tone: "progress" },
  { value: "COMPLETED", label: "已完成", tone: "done" }
];

const todayString = () => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
};

const shiftDate = (value: string, days: number) => {
  const base = new Date(`${value}T00:00:00`);
  base.setDate(base.getDate() + days);
  const month = String(base.getMonth() + 1).padStart(2, "0");
  const day = String(base.getDate()).padStart(2, "0");
  return `${base.getFullYear()}-${month}-${day}`;
};

const emptyResult = (): TcmWorkbenchResult => ({
  date: todayString(),
  from: todayString(),
  to: todayString(),
  counts: { WAITING: 0, IN_PROGRESS: 0, COMPLETED: 0 },
  list: []
});

const selectedDate = ref(todayString());
const mode = ref<"today" | "recent">("today");
const keyword = ref("");
const statusFilter = ref<TcmWorkbenchStatus | "">("");
const loading = ref(false);
const result = ref<TcmWorkbenchResult>(emptyResult());

const rangeLabel = computed(() =>
  mode.value === "today" ? selectedDate.value : `${shiftDate(selectedDate.value, -6)} ~ ${selectedDate.value}`
);
const visibleRows = computed(() =>
  statusFilter.value ? result.value.list.filter(row => row.tcmStatus === statusFilter.value) : result.value.list
);

const load = async () => {
  loading.value = true;
  try {
    const params =
      mode.value === "today"
        ? { date: selectedDate.value, keyword: keyword.value }
        : { from: shiftDate(selectedDate.value, -6), to: selectedDate.value, keyword: keyword.value };
    const { data } = await getTcmWorkbenchApi(params);
    result.value = data;
  } catch (error: any) {
    ElMessage.error(error?.message || "中医患者列表加载失败");
  } finally {
    loading.value = false;
  }
};

const drawerVisible = ref(false);
const detailLoading = ref(false);
const saving = ref(false);
const completing = ref(false);
const currentRow = ref<TcmWorkbenchRow>();
const workspace = ref<PreAiWorkspace>();
const form = reactive<Record<string, any>>({});
const registration = computed<Record<string, any>>(() => workspace.value?.encounter?.patient || {});
const tcmSubmission = computed(() => workspace.value?.stages.find(stage => stage.stageCode === "TCM"));
const readOnly = computed(() => tcmSubmission.value?.status === "COMPLETED");
const completedAt = computed(() => tcmSubmission.value?.completedAt || "");
const tcmFields = computed<PreAiFieldConfig[]>(() => {
  const stage = preAiStages.find(item => item.code === "TCM");
  return (stage?.fields || []).filter(
    field => TCM_FIELD_KEYS.has(field.key) && ["input", "textarea", "select", "multi"].includes(field.kind)
  );
});
const drawerTitle = computed(() =>
  currentRow.value ? `辨证记录 · ${currentRow.value.patientName || "未命名患者"}` : "辨证记录"
);

const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value ?? {}));
const patchForm = (key: string, value: any) => {
  form[key] = value;
};

const handleRowClick = (row: any) => openRecord(row as TcmWorkbenchRow);

const openRecord = async (row: TcmWorkbenchRow) => {
  currentRow.value = row;
  workspace.value = undefined;
  Object.keys(form).forEach(key => delete form[key]);
  drawerVisible.value = true;
  detailLoading.value = true;
  try {
    const { data } = await getPreAiWorkspaceApi(row.encounterId);
    workspace.value = data;
    const stage = data.stages.find(item => item.stageCode === "TCM");
    Object.assign(form, clone(stage?.data || {}));
  } catch (error: any) {
    ElMessage.error(error?.message || "中医病历加载失败");
    drawerVisible.value = false;
  } finally {
    detailLoading.value = false;
  }
};

const refreshAfterSave = async (next: PreAiWorkspace) => {
  workspace.value = next;
  const stage = next.stages.find(item => item.stageCode === "TCM");
  Object.keys(form).forEach(key => delete form[key]);
  Object.assign(form, clone(stage?.data || {}));
  await load();
};

const saveDraft = async () => {
  if (!currentRow.value || !tcmSubmission.value) return;
  saving.value = true;
  try {
    const { data } = await savePreAiStageApi(currentRow.value.encounterId, "TCM", clone(form), tcmSubmission.value.version);
    await refreshAfterSave(data);
    ElMessage.success("辨证草稿已保存");
  } catch (error: any) {
    ElMessage.error(error?.message || "辨证草稿保存失败");
  } finally {
    saving.value = false;
  }
};

const completeRecord = async () => {
  if (!currentRow.value || !tcmSubmission.value) return;
  try {
    await ElMessageBox.confirm("完成本次辨证后，记录将进入已完成并提交复核，是否继续？", "完成辨证", {
      type: "warning",
      confirmButtonText: "完成辨证",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  completing.value = true;
  try {
    const { data } = await completePreAiStageApi(currentRow.value.encounterId, "TCM", clone(form), tcmSubmission.value.version);
    await refreshAfterSave(data);
    ElMessage.success("辨证已完成");
  } catch (error: any) {
    ElMessage.error(error?.message || "完成辨证失败");
  } finally {
    completing.value = false;
  }
};

const openFullRecord = () => {
  if (!currentRow.value) return;
  router.push({ path: "/pre-ai/encounters", query: { id: currentRow.value.encounterId, stage: "TCM" } });
};

const statusLabel = (status: TcmWorkbenchStatus) =>
  status === "COMPLETED" ? "已完成" : status === "IN_PROGRESS" ? "诊疗中" : "待接诊";
const statusTone = (status: TcmWorkbenchStatus) =>
  status === "COMPLETED" ? "done" : status === "IN_PROGRESS" ? "progress" : "waiting";
const shortTime = (value?: string) => (value ? String(value).slice(0, 16) : "—");
const displayValue = (value: unknown) => {
  if (Array.isArray(value)) return value.filter(Boolean).join("、") || "—";
  return String(value ?? "").trim() || "—";
};

onMounted(load);
</script>

<style scoped lang="scss">
.tcm-workbench {
  padding: 16px 20px 24px;
}
.tw-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.tw-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  strong {
    font-size: 18px;
    color: var(--el-text-color-primary);
  }
  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}
.tw-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.tw-search {
  width: 200px;
}
.tw-status-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 16px 0 12px;
  flex-wrap: wrap;
}
.tw-status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 14px;
  border-radius: 999px;
  border: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-blank);
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
  .tw-status-label {
    font-size: 13px;
    color: var(--el-text-color-regular);
  }
  strong {
    font-size: 14px;
    color: var(--el-text-color-primary);
  }
  &.is-active {
    border-color: var(--el-color-primary-light-5);
    background: var(--el-color-primary-light-9);
  }
}
.tw-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--el-color-info);
}
.tw-status.is-waiting .tw-dot {
  background: var(--el-color-warning);
}
.tw-status.is-progress .tw-dot {
  background: var(--el-color-primary);
}
.tw-status.is-done .tw-dot {
  background: var(--el-color-success);
}
.tw-range {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.tw-list {
  min-height: 240px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-blank);
  overflow: hidden;
}
.tw-patient {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
  small {
    color: var(--el-text-color-secondary);
  }
}
.tw-complaint {
  color: var(--el-text-color-primary);
}
.tw-sub {
  color: var(--el-text-color-secondary);
}
.tw-muted {
  color: var(--el-text-color-placeholder);
}
.tw-pill {
  display: inline-block;
  padding: 1px 9px;
  border-radius: 999px;
  font-size: 12px;
  line-height: 20px;
  &.is-waiting {
    color: var(--el-color-warning-dark-2);
    background: var(--el-color-warning-light-9);
  }
  &.is-progress {
    color: var(--el-color-primary-dark-2);
    background: var(--el-color-primary-light-9);
  }
  &.is-done {
    color: var(--el-color-success-dark-2);
    background: var(--el-color-success-light-9);
  }
}
.tw-drawer {
  display: flex;
  flex-direction: column;
  gap: 18px;
  height: 100%;
}
.tw-summary {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 14px 16px;
  background: var(--el-fill-color-lighter);
}
.tw-summary-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  small {
    color: var(--el-text-color-secondary);
  }
  .el-button {
    margin-left: auto;
  }
}
.tw-summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 18px;
  div {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  label {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
  span {
    font-size: 13px;
    color: var(--el-text-color-primary);
    line-height: 1.5;
  }
}
.tw-form {
  flex: 1;
}
.tw-form-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
  strong {
    font-size: 15px;
  }
  span {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}
.tw-drawer-footer {
  position: sticky;
  bottom: 0;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 12px;
  background: var(--el-bg-color);
  border-top: 1px solid var(--el-border-color-lighter);
}
@media (max-width: 900px) {
  .tw-actions {
    width: 100%;
  }
  .tw-search {
    flex: 1;
    min-width: 160px;
  }
  .tw-summary-grid {
    grid-template-columns: 1fr;
  }
}
</style>
