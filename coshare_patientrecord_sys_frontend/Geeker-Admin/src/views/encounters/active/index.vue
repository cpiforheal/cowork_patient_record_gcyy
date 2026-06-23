<template>
  <div class="table-box encounter-page">
    <section class="board-toolbar">
      <div>
        <h2>患者流程看板</h2>
        <p>{{ roleName }} 可填写 {{ editableSectionCount }} 个章节。</p>
      </div>
      <div class="toolbar-actions">
        <el-radio-group v-model="viewMode" size="large">
          <el-radio-button label="kanban">看板</el-radio-button>
          <el-radio-button label="list">列表</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" @click="refreshBoard">刷新</el-button>
      </div>
    </section>

    <section v-if="viewMode === 'kanban'" class="kanban-board">
      <article v-for="column in kanbanColumns" :key="column.key" class="kanban-column">
        <header>
          <span>{{ column.title }}</span>
          <el-tag effect="plain">{{ column.patients.length }}</el-tag>
        </header>
        <div class="kanban-list">
          <button
            v-for="patient in column.patients"
            :key="patient.id"
            type="button"
            class="patient-card"
            :class="{
              timeout: isTimeout(patient),
              current: isCurrentRoleFocus(patient),
              [`risk-${patient.riskType || 'info'}`]: true
            }"
            @click="openPatient(patient.id)"
          >
            <div class="patient-card-head">
              <strong>
                <span class="patient-status-dot"></span>
                {{ patient.name }}
              </strong>
              <el-tag :type="patient.riskType || 'info'" effect="plain">{{ patient.status }}</el-tag>
            </div>
            <span>{{ patient.visitType }} · {{ patient.visitNo }}</span>
            <span v-if="(patient.encounterCount || 1) > 1" class="encounter-count">累计 {{ patient.encounterCount }} 次就诊</span>
            <small>{{ patient.currentStage }}</small>
            <div class="closed-loop-progress" :class="`risk-${patient.riskType || 'info'}`">
              <span><em :style="{ width: `${patient.progressPercent || 0}%` }"></em></span>
              <small>{{ patient.completedCount || 0 }}/{{ recordSections.length }} 章</small>
            </div>
            <div class="stay-line" :class="{ timeout: isTimeout(patient) }">
              <span>{{ stayDuration(patient.updatedAt) }}</span>
              <em>{{ patient.progressPercent }}%</em>
            </div>
          </button>
          <el-empty v-if="!column.patients.length" :image-size="70" description="暂无患者" />
        </div>
      </article>
    </section>

    <ProTable
      v-else
      ref="proTable"
      :columns="columns"
      :request-api="getEncounterList"
      :data-callback="dataCallback"
      :init-param="initParam"
      :search-col="{ xs: 1, sm: 1, md: 2, lg: 3, xl: 3 }"
    >
      <template #stageProgress="{ row }">
        <div class="step-indicator">
          <el-tooltip v-for="(section, index) in compactSections" :key="section.key" :content="section.title" placement="top">
            <span class="step-segment" :class="sectionFlowStatus(row, index)">
              {{ section.short }}
            </span>
          </el-tooltip>
        </div>
        <div class="progress-meta">
          <span>{{ row.completedCount }}/{{ recordSections.length }}</span>
          <strong>{{ row.currentStage }}</strong>
        </div>
        <div class="closed-loop-progress table-progress" :class="`risk-${row.riskType || 'info'}`">
          <span><em :style="{ width: `${row.progressPercent || 0}%` }"></em></span>
          <small>{{ row.progressPercent || 0 }}%</small>
        </div>
      </template>

      <template #status="{ row }">
        <el-tag :type="row.riskType">{{ row.status }}</el-tag>
      </template>

      <template #operation="{ row }">
        <el-button type="primary" :icon="ArrowRight" link @click.stop="openPatient(row.id)">进入详情</el-button>
      </template>
    </ProTable>
  </div>
</template>

<script setup lang="ts" name="encounterActive">
import { computed, onMounted, reactive, ref } from "vue";
import { ArrowRight, Refresh } from "@element-plus/icons-vue";
import ProTable from "@/components/ProTable/index.vue";
import { ColumnProps, ProTableInstance } from "@/components/ProTable/interface";
import { getPatientListApi, type PatientRow } from "@/api/modules/clinic";
import { canEditSection, recordSections, roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";
import { usePatientNavigation } from "@/hooks/usePatientNavigation";

const userStore = useUserStore();
const { openPatientDetail } = usePatientNavigation();
const proTable = ref<ProTableInstance>();
const viewMode = ref<"kanban" | "list">("kanban");
const patientRows = ref<PatientRow[]>([]);

const currentRole = computed(() => userStore.userInfo.role || "frontdesk");
const roleName = computed(() => roleLabel(currentRole.value));
const editableSectionCount = computed(() => recordSections.filter(section => canEditSection(currentRole.value, section)).length);
const initParam = reactive({ sectionKey: "" });

const compactSections = computed(() =>
  recordSections.map((section, index) => ({
    ...section,
    short: shortTitle(section.title).slice(0, 2) || String(index + 1)
  }))
);

const kanbanColumns = computed(() => {
  const base = ["前台登记", "基础诊疗", "治疗记录", "复查随访", "档案审核", "已归档"].map(title => ({
    key: title,
    title,
    patients: [] as PatientRow[]
  }));
  patientRows.value.forEach(patient => {
    const index = patient.currentStage.includes("登记")
      ? 0
      : patient.currentStage.includes("基础") || patient.currentStage.includes("初诊") || patient.currentStage.includes("复核")
        ? 1
        : patient.currentStage.includes("治疗") || patient.currentStage.includes("手术")
          ? 2
          : patient.currentStage.includes("复查") || patient.currentStage.includes("随访")
            ? 3
            : patient.currentStage.includes("质控") || patient.currentStage.includes("档案审核")
              ? 4
              : patient.currentStage.includes("归档")
                ? 5
                : 1;
    base[index].patients.push(patient);
  });
  return base;
});

const dataCallback = (data: { list: PatientRow[]; total: number; pageNum: number; pageSize: number }) => data;
const getEncounterList = getPatientListApi;

const columns = reactive<ColumnProps[]>([
  { type: "index", label: "#", width: 70 },
  { prop: "name", label: "患者姓名", search: { el: "input" }, width: 120 },
  { prop: "visitNo", label: "门诊/住院号", search: { el: "input" }, width: 160 },
  {
    prop: "visitType",
    label: "就诊类型",
    width: 120,
    enum: [
      { label: "门诊", value: "门诊" },
      { label: "门诊医保", value: "门诊医保" },
      { label: "住院", value: "住院" }
    ],
    search: { el: "select" }
  },
  { prop: "encounterCount", label: "就诊次数", width: 110 },
  { prop: "doctor", label: "接诊医生", width: 120 },
  { prop: "currentStage", label: "当前节点", width: 130 },
  { prop: "stageProgress", label: "档案流程", minWidth: 360 },
  { prop: "status", label: "状态", width: 130 },
  { prop: "operation", label: "操作", fixed: "right", width: 120 }
]);

const shortTitle = (title: string) => title.replace(/^.*?、/, "");

const sectionFlowStatus = (row: PatientRow, index: number) => {
  if (index < row.completedCount) return "done";
  if (index === row.completedCount) return "active";
  return "waiting";
};

const stayDuration = (updatedAt: string) => {
  const timestamp = new Date(updatedAt.replace(/-/g, "/")).getTime();
  const hours = Math.max(1, Math.round((Date.now() - timestamp) / 36e5));
  return hours >= 24 ? `停留 ${Math.round(hours / 24)} 天` : `停留 ${hours} 小时`;
};

const isTimeout = (patient: PatientRow) => Date.now() - new Date(patient.updatedAt.replace(/-/g, "/")).getTime() > 24 * 36e5;
const isCurrentRoleFocus = (patient: PatientRow) => {
  const stage = patient.currentStage;
  if (currentRole.value === "admin") return isTimeout(patient);
  if (currentRole.value === "frontdesk") return stage.includes("登记");
  if (currentRole.value === "doctor") return stage.includes("诊疗") || stage.includes("治疗") || stage.includes("复核");
  if (currentRole.value === "quality") return stage.includes("质控") || stage.includes("档案审核") || stage.includes("归档");
  return patient.status.includes("待") || stage.includes("档案生成") || stage.includes("病历生成");
};

const loadBoard = async () => {
  const { data } = await getPatientListApi({ pageNum: 1, pageSize: 100, sectionKey: initParam.sectionKey });
  patientRows.value = data.list;
};

const refreshBoard = () => {
  loadBoard();
  proTable.value?.getTableList();
};

const openPatient = (id: string) => {
  openPatientDetail(id);
};

onMounted(loadBoard);
</script>

<style scoped lang="scss">
.encounter-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.board-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px;
  background: var(--hos-panel);
  border: 1px solid var(--hos-border);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-soft);
  backdrop-filter: blur(16px) saturate(132%);
  -webkit-backdrop-filter: blur(16px) saturate(132%);

  h2,
  p {
    margin: 0;
  }

  h2 {
    font-size: 20px;
  }

  p {
    margin-top: 5px;
    color: var(--el-text-color-regular);
  }
}

.toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

.kanban-board {
  display: grid;
  grid-template-columns: repeat(5, minmax(220px, 1fr));
  gap: 12px;
  overflow-x: auto;
}

.kanban-column {
  min-height: 520px;
  padding: 12px;
  background: var(--hos-glass);
  border: 1px solid var(--hos-border);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-soft);
  backdrop-filter: blur(14px) saturate(128%);
  -webkit-backdrop-filter: blur(14px) saturate(128%);

  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 10px;
    font-weight: 600;
  }
}

.kanban-list {
  display: grid;
  gap: 8px;
}

.patient-card {
  position: relative;
  display: grid;
  gap: 7px;
  padding: 12px;
  overflow: hidden;
  text-align: left;
  cursor: pointer;
  background: var(--hos-panel);
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-lg);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 36%);
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;

  &::before {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0;
    width: 4px;
    content: "";
    background: var(--hos-status-info);
  }

  &:hover {
    border-color: var(--hos-border-interactive);
    box-shadow: var(--hos-shadow-card-hover);
    transform: translateY(-2px);
  }

  &.current {
    border-color: var(--hos-border-interactive);
    box-shadow:
      0 0 0 3px rgb(var(--hos-primary-rgb) / 10%),
      var(--hos-shadow-soft);
  }

  &.timeout {
    border-color: var(--el-color-danger-light-5);
    animation: timeout-pulse 1.8s ease-in-out infinite;
  }

  &.risk-success::before {
    background: var(--hos-status-success);
  }

  &.risk-warning::before {
    background: var(--hos-status-warning);
  }

  &.risk-danger::before {
    background: var(--hos-status-danger);
  }

  strong {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: var(--hos-text-primary);
    font-size: 16px;
  }

  span,
  small {
    color: var(--hos-text-secondary);
  }
}

.encounter-count {
  width: fit-content;
  padding: 2px 8px;
  color: var(--hos-primary-deep) !important;
  background: var(--hos-primary-soft);
  border-radius: 999px;
  font-size: 12px;
}

.patient-status-dot {
  width: 10px;
  height: 10px;
  background: var(--hos-primary);
  border: 1px solid rgb(255 255 255 / 70%);
  border-radius: 999px;
  box-shadow: 0 0 0 4px rgb(var(--hos-primary-rgb) / 10%);
}

.risk-success .patient-status-dot {
  background: var(--hos-status-success);
  box-shadow: 0 0 0 4px rgb(22 163 74 / 12%);
}

.risk-warning .patient-status-dot {
  background: var(--hos-status-warning);
  box-shadow: 0 0 0 4px rgb(217 119 6 / 12%);
}

.risk-danger .patient-status-dot {
  background: var(--hos-status-danger);
  box-shadow: 0 0 0 4px rgb(220 38 38 / 12%);
}

.closed-loop-progress {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;

  > span {
    height: 9px;
    overflow: hidden;
    background: rgb(255 255 255 / 46%);
    border: 1px solid var(--hos-border-light);
    border-radius: 999px;
  }

  em {
    display: block;
    height: 100%;
    background: var(--hos-status-info);
    border-radius: inherit;
    box-shadow: inset 0 1px 0 rgb(255 255 255 / 42%);
    transition: width 220ms var(--liquid-ease, ease);
  }

  small {
    color: var(--hos-text-secondary);
    font-size: 12px;
    font-variant-numeric: tabular-nums;
    font-weight: 700;
  }

  &.risk-success em {
    background: var(--hos-status-success);
  }

  &.risk-warning em {
    background: var(--hos-status-warning);
  }

  &.risk-danger em {
    background: var(--hos-status-danger);
  }
}

.table-progress {
  max-width: 240px;
  margin-top: 7px;
}

@keyframes timeout-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgb(248 113 113 / 0%);
  }

  50% {
    box-shadow: 0 0 0 4px rgb(248 113 113 / 14%);
  }
}

.patient-card-head,
.stay-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.stay-line {
  padding-top: 7px;
  border-top: 1px solid var(--hos-border-light);

  em {
    color: var(--hos-primary-deep);
    font-style: normal;
    font-weight: 600;
  }

  &.timeout span {
    color: var(--el-color-danger);
    font-weight: 600;
  }
}

.step-indicator {
  display: grid;
  grid-template-columns: repeat(15, minmax(18px, 1fr));
  gap: 3px;
}

.step-segment {
  display: grid;
  place-items: center;
  min-height: 24px;
  color: var(--hos-text-secondary);
  background: var(--hos-glass);
  border: 1px solid var(--hos-border-light);
  border-radius: 4px;
  font-size: 12px;

  &.done {
    color: var(--hos-status-success);
    background: var(--hos-status-success-soft);
    border-color: rgb(22 163 74 / 18%);
  }

  &.active {
    color: var(--hos-status-warning);
    background: var(--hos-status-warning-soft);
    border-color: rgb(217 119 6 / 22%);
  }
}

.progress-meta {
  display: flex;
  gap: 8px;
  margin-top: 6px;
  color: var(--el-text-color-regular);
  font-size: 12px;

  strong {
    font-weight: 600;
  }
}

@media (max-width: 980px) {
  .board-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .toolbar-actions {
    justify-content: flex-start;
  }
}
</style>
