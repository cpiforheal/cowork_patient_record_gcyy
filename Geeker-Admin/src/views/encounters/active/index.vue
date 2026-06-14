<template>
  <div class="table-box encounter-page">
    <section class="board-toolbar">
      <div>
        <h2>患者流程看板</h2>
        <p>{{ roleName }} 可编辑 {{ editableSectionCount }} 个病历章节，优先处理停留时间较长的患者。</p>
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
      </template>

      <template #status="{ row }">
        <el-tag :type="row.riskType">{{ row.status }}</el-tag>
      </template>

      <template #operation="{ row }">
        <el-button type="primary" :icon="ArrowRight" link @click="openPatient(row.id)">进入详情</el-button>
      </template>
    </ProTable>
  </div>
</template>

<script setup lang="ts" name="encounterActive">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ArrowRight, Refresh } from "@element-plus/icons-vue";
import ProTable from "@/components/ProTable/index.vue";
import { ColumnProps, ProTableInstance } from "@/components/ProTable/interface";
import { getPatientListApi, type PatientRow } from "@/api/modules/clinic";
import { canEditSection, recordSections, roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";

const router = useRouter();
const userStore = useUserStore();
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
  const base = ["前台登记", "病历生成", "医生复核", "质控审核", "归档"].map(title => ({
    key: title,
    title,
    patients: [] as PatientRow[]
  }));
  patientRows.value.forEach(patient => {
    const index = patient.currentStage.includes("登记")
      ? 0
      : patient.currentStage.includes("复核")
        ? 2
        : patient.currentStage.includes("质控")
          ? 3
          : patient.currentStage.includes("归档")
            ? 4
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
  { prop: "stageProgress", label: "病历流程", minWidth: 360 },
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
  if (currentRole.value === "doctor") return stage.includes("病历") || stage.includes("复核");
  if (currentRole.value === "quality") return stage.includes("质控") || stage.includes("归档");
  return patient.status.includes("待") || stage.includes("病历生成");
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
  router.push(`/patients/detail/${id}`);
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
  background: #ffffff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;

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
  background: #f8fafc;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;

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
  background: #ffffff;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
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
    border-color: var(--el-color-primary-light-5);
    box-shadow: 0 10px 24px rgb(15 23 42 / 8%);
    transform: translateY(-2px);
  }

  &.current {
    border-color: var(--el-color-primary-light-3);
    box-shadow: 0 0 0 3px rgb(64 158 255 / 12%);
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
    color: var(--el-text-color-primary);
    font-size: 16px;
  }

  span,
  small {
    color: var(--el-text-color-secondary);
  }
}

.encounter-count {
  width: fit-content;
  padding: 2px 8px;
  color: #26745a !important;
  background: #eef9f3;
  border-radius: 999px;
  font-size: 12px;
}

.patient-status-dot {
  width: 8px;
  height: 8px;
  background: var(--el-color-primary);
  border-radius: 999px;
}

.risk-success .patient-status-dot {
  background: var(--hos-status-success);
}

.risk-warning .patient-status-dot {
  background: var(--hos-status-warning);
}

.risk-danger .patient-status-dot {
  background: var(--hos-status-danger);
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
  border-top: 1px solid var(--el-border-color-lighter);

  em {
    color: var(--el-color-primary);
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
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  font-size: 12px;

  &.done {
    color: #ffffff;
    background: var(--el-color-success);
    border-color: var(--el-color-success);
  }

  &.active {
    color: #ffffff;
    background: var(--el-color-warning);
    border-color: var(--el-color-warning);
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
