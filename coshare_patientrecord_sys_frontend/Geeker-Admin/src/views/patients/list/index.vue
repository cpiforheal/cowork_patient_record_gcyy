<template>
  <div class="main-box patient-list-layout">
    <aside class="date-scope-panel" aria-label="日期筛选">
      <div class="date-panel-head">
        <div>
          <span>日期筛选</span>
          <strong>{{ activeScopeShortTitle }}</strong>
        </div>
        <el-tag effect="plain">{{ currentTotal }} 人</el-tag>
      </div>

      <div class="quick-range-grid">
        <button
          v-for="scope in quickScopeNodes"
          :key="scope.id || 'all'"
          class="date-chip"
          :class="{ active: activeDateScope === scope.id }"
          type="button"
          @click="changeDateScope(scope.id)"
        >
          <span>{{ scope.label }}</span>
          <small>{{ scope.count || 0 }}</small>
        </button>
      </div>

      <div class="date-picker-card">
        <span class="date-panel-label">按天查看</span>
        <el-date-picker
          v-model="selectedDateValue"
          type="date"
          value-format="YYYY-MM-DD"
          clearable
          placeholder="选择日期"
          @change="changeCalendarDate"
        />
      </div>

      <div v-if="visibleRecordedDays.length" class="recorded-days">
        <span class="date-panel-label">有收录的日期</span>
        <button
          v-for="scope in visibleRecordedDays"
          :key="scope.id"
          class="recorded-day"
          :class="{ active: activeDateScope === scope.id }"
          type="button"
          @click="changeDateScope(scope.id)"
        >
          <span>{{ scope.label }}</span>
          <small>{{ scope.count }} 人</small>
        </button>
      </div>
    </aside>

    <section class="table-box patient-list-page">
      <div class="date-scope-card">
        <div>
          <span class="scope-eyebrow">当前回看范围</span>
          <h2>{{ activeScopeTitle }}</h2>
          <p>{{ activeScopeDesc }}</p>
        </div>
        <div class="scope-actions">
          <el-tag effect="plain">匹配 {{ currentTotal }} 人</el-tag>
          <el-button :icon="Refresh" @click="refreshPatients">刷新</el-button>
          <el-button @click="changeDateScope('')">全部日期</el-button>
        </div>
      </div>

      <ProTable
        ref="proTable"
        :columns="columns"
        :request-api="getPatientList"
        :data-callback="dataCallback"
        :init-param="initParam"
        :search-col="{ xs: 1, sm: 1, md: 2, lg: 3, xl: 3 }"
      >
        <template #tableHeader>
          <div class="patient-table-header">
            <el-button v-auth="'patient:create'" type="primary" :icon="CirclePlus" @click="createDialogVisible = true">
              新建患者
            </el-button>
            <span class="patient-table-hint">新建后自动打开患者详情流程视图。</span>
          </div>
        </template>

        <template #name="{ row }">
          <div class="patient-identity-cell" :class="`risk-${patientRiskTone(row)}`">
            <strong class="patient-name-line">
              <i class="patient-signal" aria-hidden="true"></i>
              {{ row.name }}
            </strong>
            <span>{{ row.currentStage || "待分配阶段" }}</span>
          </div>
        </template>

        <template #visitNo="{ row }">
          <div class="visit-no-cell">
            <strong>{{ row.visitNo }}</strong>
            <span v-if="row.encounterHistory?.length > 1">最近一次 · 共 {{ row.encounterHistory.length }} 次</span>
            <span v-else>单次就诊</span>
          </div>
        </template>

        <template #visitType="{ row }">
          <el-tag :type="visitTypeTagType(row.visitType)" effect="light">
            {{ row.visitType }}
          </el-tag>
        </template>

        <template #status="{ row }">
          <div class="status-cell">
            <div class="patient-status-top">
              <el-tag :type="statusTagType(row.status)" effect="plain">
                {{ row.status }}
              </el-tag>
              <span>{{ row.completedCount || 0 }}/{{ recordSections.length }}</span>
            </div>
            <div class="closed-loop-meter" :class="`risk-${patientRiskTone(row)}`">
              <i :style="{ width: `${row.progressPercent || 0}%` }"></i>
            </div>
            <span>{{ row.progressPercent }}%</span>
          </div>
        </template>

        <template #encounterCount="{ row }">
          <el-tag effect="plain" type="success">累计 {{ row.encounterCount || 1 }} 次</el-tag>
        </template>

        <template #visitDate="{ row }">
          <div class="visit-date-cell">
            <strong>{{ row.visitDate }}</strong>
            <span v-if="row.encounterHistory?.length > 1">含 {{ row.encounterHistory.length }} 次历史</span>
          </div>
        </template>

        <template #operation="{ row }">
          <el-button v-auth="'patient:read'" type="primary" link @click.stop="openPatientDetail(row.id)"> 打开档案 </el-button>
          <el-button v-auth="'patient:update'" type="primary" link @click.stop="openPatientUpload(row)"> 上传资料 </el-button>
        </template>
      </ProTable>

      <el-dialog v-model="createDialogVisible" title="新建患者" width="560px" destroy-on-close>
        <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="110px">
          <el-form-item label="患者姓名" prop="name">
            <el-input v-model="createForm.name" placeholder="请输入患者姓名" />
          </el-form-item>
          <el-form-item label="门诊/住院号" prop="visitNo">
            <el-input v-model="createForm.visitNo" placeholder="请输入唯一门诊/住院号" />
          </el-form-item>
          <el-form-item label="就诊日期" prop="visitDate">
            <el-date-picker v-model="createForm.visitDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="就诊类型" prop="visitType">
            <el-select v-model="createForm.visitType">
              <el-option label="门诊" value="门诊" />
              <el-option label="门诊医保" value="门诊医保" />
              <el-option label="住院" value="住院" />
            </el-select>
          </el-form-item>
          <el-form-item label="接诊医生" prop="doctor">
            <el-input v-model="createForm.doctor" placeholder="请输入接诊医生" />
          </el-form-item>
          <el-form-item label="联系电话" prop="phone">
            <el-input v-model="createForm.phone" maxlength="11" show-word-limit type="tel" placeholder="同一患者可填写" />
          </el-form-item>
        </el-form>
        <div class="create-flow-hint">
          <strong>创建成功后将进入患者详情页</strong>
          <span>默认展示岗位流程视图，可继续打开目标病历或完整档案。</span>
        </div>
        <template #footer>
          <el-button @click="createDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="creating" @click="submitCreatePatient">确认创建并打开档案</el-button>
        </template>
      </el-dialog>
    </section>
  </div>
</template>

<script setup lang="ts" name="patientList">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import { CirclePlus, Refresh } from "@element-plus/icons-vue";
import ProTable from "@/components/ProTable/index.vue";
import { ColumnProps, ProTableInstance } from "@/components/ProTable/interface";
import { createPatientApi, getPatientListApi, type CreatePatientParams, type PatientRow } from "@/api/modules/clinic";
import { recordSections, roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";
import { usePatientNavigation } from "@/hooks/usePatientNavigation";
import { classifyPatientStatus } from "@/utils/patientStatusClassifier";

type DateScopeNode = {
  id: string;
  label: string;
  count?: number;
  from?: string;
  to?: string;
  kind?: "leaf";
};

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();
const { openPatientDetail } = usePatientNavigation();
const proTable = ref<ProTableInstance>();
const createFormRef = ref<FormInstance>();
const createDialogVisible = ref(false);
const creating = ref(false);
const activeDateScope = ref("");
const selectedDateValue = ref("");
const allPatients = ref<PatientRow[]>([]);
const currentTotal = ref(0);

const patientRiskTone = (row: PatientRow) => classifyPatientStatus(row).riskTone;

const initParam = reactive<{
  visitDateFrom?: string;
  visitDateTo?: string;
}>({});

const padDateUnit = (value: number) => String(value).padStart(2, "0");
const toDateText = (date: Date) => `${date.getFullYear()}-${padDateUnit(date.getMonth() + 1)}-${padDateUnit(date.getDate())}`;
const getMonthRange = (monthText: string) => {
  const [year, month] = monthText.split("-").map(Number);
  const lastDate = new Date(year, month, 0).getDate();
  return {
    from: `${monthText}-01`,
    to: `${monthText}-${padDateUnit(lastDate)}`
  };
};

const todayText = toDateText(new Date());
const createForm = reactive<CreatePatientParams>({
  name: "",
  visitNo: "",
  visitDate: todayText,
  visitType: "门诊",
  doctor: "",
  phone: ""
});
const validateOptionalMobile = (_rule: unknown, value: string, callback: (error?: Error) => void) => {
  if (!value || /^1[3-9]\d{9}$/.test(value)) {
    callback();
    return;
  }
  callback(new Error("请输入正确的11位手机号"));
};
const createRules = reactive<FormRules<CreatePatientParams>>({
  name: [{ required: true, message: "请输入患者姓名", trigger: "blur" }],
  visitNo: [{ required: true, message: "请输入门诊/住院号", trigger: "blur" }],
  visitDate: [{ required: true, message: "请选择就诊日期", trigger: "change" }],
  visitType: [{ required: true, message: "请选择就诊类型", trigger: "change" }],
  doctor: [{ required: true, message: "请输入接诊医生", trigger: "blur" }],
  phone: [{ validator: validateOptionalMobile, trigger: "blur" }]
});

const columns = reactive<ColumnProps<PatientRow>[]>([
  { type: "index", label: "#", width: 70 },
  {
    prop: "name",
    label: "患者姓名",
    minWidth: 150,
    search: { el: "input", props: { placeholder: "输入姓名关键词" } }
  },
  {
    prop: "visitNo",
    label: "最近门诊/住院号",
    minWidth: 185,
    search: { el: "input", props: { placeholder: "输入门诊/住院号" } }
  },
  {
    prop: "visitType",
    label: "最近类型",
    width: 110,
    enum: [
      { label: "门诊", value: "门诊" },
      { label: "门诊医保", value: "门诊医保" },
      { label: "住院", value: "住院" }
    ],
    search: { el: "select", props: { placeholder: "选择就诊类型" } }
  },
  { prop: "visitDate", label: "就诊日期", width: 150 },
  { prop: "encounterCount", label: "就诊次数", width: 110 },
  { prop: "doctor", label: "接诊医生", width: 120 },
  {
    prop: "status",
    label: "资料状态",
    width: 150,
    enum: [
      { label: "待补充资料", value: "待补充资料" },
      { label: "新增就诊待补充", value: "新增就诊待补充" },
      { label: "资料已上传", value: "资料已上传" },
      { label: "可提交档案审核", value: "可提交档案审核" },
      { label: "待档案审核", value: "待档案审核" },
      { label: "退回整改", value: "退回整改" },
      { label: "已归档", value: "已归档" },
      { label: "旧资料已归档", value: "旧资料已归档" },
      { label: "有作废记录", value: "有作废记录" }
    ],
    search: { el: "select", props: { placeholder: "选择档案状态" } }
  },
  { prop: "operation", label: "操作", fixed: "right", width: 180 }
]);

const operatorRole = computed(() => userStore.userInfo.role || "frontdesk");
const operatorName = computed(() => roleLabel(operatorRole.value));

const addDays = (date: Date, days: number) => {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
};

const patientEncounterDates = (patient: PatientRow) => {
  const history = patient.encounterHistory?.length
    ? patient.encounterHistory
    : [{ visitDate: patient.visitDate, visitNo: patient.visitNo, visitType: patient.visitType, doctor: patient.doctor }];
  return [...new Set(history.map(item => item.visitDate).filter(Boolean))];
};

const countByDate = computed(() => {
  const counter = new Map<string, number>();
  allPatients.value.forEach(patient => {
    patientEncounterDates(patient).forEach(date => {
      counter.set(date, (counter.get(date) || 0) + 1);
    });
  });
  return counter;
});

const rangeCount = (from: string, to: string) =>
  allPatients.value.filter(patient => patientEncounterDates(patient).some(date => date >= from && date <= to)).length;

const quickRangeNodes = computed<DateScopeNode[]>(() => {
  const today = new Date();
  const ranges = [
    { id: "range::today", label: "今天", from: todayText, to: todayText },
    { id: "range::7", label: "近 7 天", from: toDateText(addDays(today, -6)), to: todayText },
    { id: "range::30", label: "近 30 天", from: toDateText(addDays(today, -29)), to: todayText }
  ];
  return ranges.map(item => ({ ...item, count: rangeCount(item.from, item.to), kind: "leaf" }));
});

const quickScopeNodes = computed<DateScopeNode[]>(() => [
  { id: "", label: "全部", count: allPatients.value.length, kind: "leaf" },
  ...quickRangeNodes.value
]);

const dayNodes = computed<DateScopeNode[]>(() =>
  [...countByDate.value.entries()]
    .sort((left, right) => right[0].localeCompare(left[0]))
    .map(([date, count]) => ({
      id: `date::${date}`,
      label: date,
      count,
      kind: "leaf"
    }))
);

const visibleRecordedDays = computed(() => dayNodes.value.slice(0, 12));

const activeScopeTitle = computed(() => {
  if (!activeDateScope.value) return "全部患者收录";
  const [type, value] = activeDateScope.value.split("::");
  if (type === "range") {
    const range = quickRangeNodes.value.find(item => item.id === activeDateScope.value);
    return range?.label || "快捷回看";
  }
  if (type === "month") return `${value} 月收录`;
  if (type === "date") return `${value} 当日收录`;
  return "全部患者收录";
});

const activeScopeShortTitle = computed(() => activeScopeTitle.value.replace("患者收录", "").replace("收录", "") || "全部");

const activeScopeDesc = computed(() => {
  if (!activeDateScope.value) return "选择日期查看患者。";
  const [type, value] = activeDateScope.value.split("::");
  if (type === "date") {
    const count = countByDate.value.get(value) || 0;
    return count ? `该日共收录 ${count} 位患者，可继续按姓名或门诊/住院号缩小范围。` : "该日暂无患者收录。";
  }
  if (type === "month") return "当前月份的患者列表。";
  if (type === "range") return "当前时间段的患者列表。";
  return "当前日期的患者列表。";
});

const clearDateParam = () => {
  initParam.visitDateFrom = undefined;
  initParam.visitDateTo = undefined;
};

const changeDateScope = (value: string) => {
  activeDateScope.value = value || "";
  clearDateParam();

  const [type, scopeValue] = activeDateScope.value.split("::");
  selectedDateValue.value = type === "date" ? scopeValue || "" : "";
  if (!scopeValue) return;
  if (type === "date") {
    initParam.visitDateFrom = scopeValue;
    initParam.visitDateTo = scopeValue;
  }
  if (type === "month") {
    const range = getMonthRange(scopeValue);
    initParam.visitDateFrom = range.from;
    initParam.visitDateTo = range.to;
  }
  if (type === "range") {
    const range = quickRangeNodes.value.find(item => item.id === activeDateScope.value);
    initParam.visitDateFrom = range?.from;
    initParam.visitDateTo = range?.to;
  }
};

const changeCalendarDate = (value: string | null) => {
  changeDateScope(value ? `date::${value}` : "");
};

const statusTagType = (status: string) => classifyPatientStatus({ status }).statusTagType;

const visitTypeTagType = (visitType: string) => {
  if (visitType.includes("住院")) return "warning";
  if (visitType.includes("医保")) return "success";
  return "primary";
};

const dataCallback = (data: { list: PatientRow[]; total: number; pageNum: number; pageSize: number }) => {
  currentTotal.value = data.total;
  return data;
};

const getPatientList = getPatientListApi;

const loadDateTree = async () => {
  const { data } = await getPatientListApi({ pageNum: 1, pageSize: 5000 });
  allPatients.value = data.list;
};

const refreshPatients = async () => {
  await loadDateTree();
  proTable.value?.getTableList();
};

const openPatientUpload = (row: PatientRow) => {
  router.push({
    path: "/workbench/upload",
    query: {
      patientId: row.id,
      keyword: row.visitNo || row.name
    }
  });
};

const readQueryValue = (value: unknown) => (Array.isArray(value) ? value[0] : typeof value === "string" ? value : "");
const queryDateScope = () => {
  const date = readQueryValue(route.query.date);
  const month = readQueryValue(route.query.month);
  if (/^\d{4}-\d{2}-\d{2}$/.test(date)) return `date::${date}`;
  if (/^\d{4}-\d{2}$/.test(month)) return `month::${month}`;
  return "";
};
const applyQueryDateScope = () => {
  const nextScope = queryDateScope();
  if (nextScope !== activeDateScope.value) changeDateScope(nextScope);
};

const submitCreatePatient = () => {
  createFormRef.value?.validate(async valid => {
    if (!valid) return;
    creating.value = true;
    try {
      const { data, msg } = await createPatientApi({ ...createForm, operator: operatorName.value, role: operatorRole.value });
      ElMessage.success(msg || "患者已创建");
      createDialogVisible.value = false;
      await refreshPatients();
      openPatientDetail(data.id);
    } catch (error) {
      ElMessage.error((error as Error).message);
    } finally {
      creating.value = false;
    }
  });
};

applyQueryDateScope();

watch(() => [route.query.date, route.query.month], applyQueryDateScope);

onMounted(loadDateTree);
</script>

<style scoped lang="scss">
.patient-list-layout {
  align-items: stretch;
  gap: 14px;
  overflow: visible;
}

.patient-list-page {
  gap: 12px;
  min-width: 0;
  overflow: visible;
  color: var(--hos-text-primary);
}

.date-scope-panel {
  position: relative;
  display: flex;
  flex: 0 0 238px;
  flex-direction: column;
  gap: 12px;
  height: fit-content;
  padding: 14px;
  overflow: visible;
  color: var(--hos-text-primary);
  background: var(--hos-panel);
  border: 1px solid var(--hos-border);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-soft);
  backdrop-filter: blur(18px) saturate(132%);
  -webkit-backdrop-filter: blur(18px) saturate(132%);
}

.date-panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;

  span,
  strong {
    display: block;
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 12px;
  }

  strong {
    margin-top: 4px;
    color: var(--hos-text-primary);
    font-size: 17px;
  }
}

.quick-range-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.date-chip,
.recorded-day {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-width: 0;
  color: var(--hos-text-primary);
  cursor: pointer;
  background: var(--hos-glass);
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);
  transition:
    transform 0.18s ease,
    border-color 0.18s ease,
    background-color 0.18s ease,
    box-shadow 0.18s ease;

  small {
    color: var(--hos-text-secondary);
    font-size: 12px;
  }

  &:hover {
    background: var(--hos-panel-hover);
    border-color: var(--hos-border-interactive);
    box-shadow: 0 10px 24px rgb(92 154 122 / 10%);
    transform: translateY(-1px);
  }

  &.active {
    color: var(--hos-primary-deep);
    background: var(--hos-primary-soft);
    border-color: var(--hos-border-interactive);
    box-shadow: 0 10px 26px rgb(92 154 122 / 14%);

    small {
      color: var(--hos-primary-deep);
      font-weight: 700;
    }
  }
}

.date-chip {
  flex-direction: column;
  align-items: flex-start;
  min-height: 58px;
  padding: 10px;

  span {
    font-weight: 700;
  }
}

.date-picker-card,
.recorded-days {
  display: grid;
  gap: 8px;
  padding: 10px;
  background: rgb(255 255 255 / 28%);
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-lg);
}

.date-picker-card {
  :deep(.el-date-editor) {
    width: 100%;
  }
}

.date-panel-label {
  color: var(--hos-text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.recorded-days {
  max-height: 352px;
  overflow: auto;
}

.recorded-day {
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  text-align: left;

  span {
    overflow: hidden;
    font-size: 13px;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.date-scope-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
  background: var(--hos-panel);
  border: 1px solid var(--hos-border);
  border-radius: var(--hos-radius-card);
  box-shadow: var(--hos-shadow-soft);
  backdrop-filter: blur(16px) saturate(132%);
  -webkit-backdrop-filter: blur(16px) saturate(132%);

  h2 {
    margin: 3px 0 4px;
    color: var(--hos-text-primary);
    font-size: 18px;
    font-weight: 700;
  }

  p {
    max-width: 760px;
    margin: 0;
    color: var(--hos-text-secondary);
    line-height: 1.7;
  }
}

.scope-eyebrow {
  color: var(--hos-primary-deep);
  font-size: 12px;
  font-weight: 700;
}

.scope-actions,
.patient-table-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.scope-actions {
  flex-shrink: 0;
}

.patient-table-header {
  width: 100%;

  .patient-table-hint {
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }
}

.create-flow-hint {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  margin-top: 4px;
  background: var(--hos-primary-soft);
  border: 1px solid var(--hos-border-light);
  border-radius: var(--hos-radius-md);

  strong {
    color: var(--hos-text-primary);
    font-size: 14px;
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 13px;
    line-height: 1.5;
  }
}

.patient-identity-cell,
.visit-no-cell,
.status-cell {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 3px;

  strong {
    overflow: hidden;
    color: var(--el-text-color-primary);
    font-weight: 700;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    overflow: hidden;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.patient-identity-cell strong {
  font-size: 15px;
}

.patient-name-line {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.patient-signal {
  flex: 0 0 auto;
  width: 10px;
  height: 10px;
  background: var(--hos-status-info);
  border: 1px solid rgb(255 255 255 / 70%);
  border-radius: 999px;
  box-shadow: 0 0 0 4px rgb(100 116 139 / 10%);
}

.risk-success .patient-signal {
  background: var(--hos-status-success);
  box-shadow: 0 0 0 4px rgb(22 163 74 / 12%);
}

.risk-warning .patient-signal {
  background: var(--hos-status-warning);
  box-shadow: 0 0 0 4px rgb(217 119 6 / 12%);
}

.risk-danger .patient-signal {
  background: var(--hos-status-danger);
  box-shadow: 0 0 0 4px rgb(220 38 38 / 12%);
}

.visit-no-cell strong {
  font-family: Arial, "Microsoft YaHei", sans-serif;
}

.status-cell {
  align-items: flex-start;
}

.patient-status-top {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;

  span {
    color: var(--hos-text-secondary);
    font-size: 12px;
    font-variant-numeric: tabular-nums;
    font-weight: 700;
  }
}

.closed-loop-meter {
  position: relative;
  width: min(128px, 100%);
  height: 8px;
  overflow: hidden;
  background: rgb(255 255 255 / 48%);
  border: 1px solid var(--hos-border-light);
  border-radius: 999px;

  i {
    display: block;
    height: 100%;
    background: var(--hos-status-info);
    border-radius: inherit;
    box-shadow: inset 0 1px 0 rgb(255 255 255 / 45%);
    transition: width 220ms var(--liquid-ease, ease);
  }

  &.risk-success i {
    background: var(--hos-status-success);
  }

  &.risk-warning i {
    background: var(--hos-status-warning);
  }

  &.risk-danger i {
    background: var(--hos-status-danger);
  }
}

.visit-date-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;

  strong {
    color: var(--el-text-color-primary);
    font-weight: 650;
  }

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

@media (width <= 992px) {
  .patient-list-layout {
    flex-direction: column;
  }

  .date-scope-panel {
    flex: none;
    width: 100%;
  }

  .quick-range-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .recorded-days {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    max-height: none;
  }

  .date-scope-card,
  .patient-table-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .scope-actions {
    flex-wrap: wrap;
  }
}

@media (width <= 620px) {
  .quick-range-grid,
  .recorded-days {
    grid-template-columns: 1fr;
  }
}
</style>
