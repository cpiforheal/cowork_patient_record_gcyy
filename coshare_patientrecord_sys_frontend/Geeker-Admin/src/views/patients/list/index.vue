<template>
  <div class="main-box patient-list-layout">
    <TreeFilter
      id="id"
      label="label"
      title="日期收录索引"
      :data="dateTree"
      :default-value="activeDateScope"
      @change="changeDateScope"
    >
      <template #default="{ row }">
        <span class="date-tree-node" :class="{ 'is-group': row.data.kind === 'group' }">
          <span class="tree-label">{{ row.data.label }}</span>
          <span v-if="row.data.count !== undefined" class="tree-count">{{ row.data.count }}</span>
        </span>
      </template>
    </TreeFilter>

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
            <span class="patient-table-hint">左侧按日期回看，表格继续保留姓名、门诊/住院号、状态和操作入口。</span>
          </div>
        </template>

        <template #name="{ row }">
          <div class="patient-identity-cell">
            <strong>{{ row.name }}</strong>
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
            <el-tag :type="statusTagType(row.status)" effect="plain">
              {{ row.status }}
            </el-tag>
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
          <el-button v-auth="'patient:read'" type="primary" link @click="router.push(`/patients/detail/${row.id}`)">
            打开病历
          </el-button>
          <el-button
            v-auth="'patient:update'"
            type="primary"
            link
            @click="router.push({ path: '/workbench/upload', query: { keyword: row.visitNo } })"
          >
            上传资料
          </el-button>
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
            <el-input
              v-model="createForm.phone"
              maxlength="11"
              show-word-limit
              type="tel"
              placeholder="可选，用于合并同一患者多次就诊"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="createDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="creating" @click="submitCreatePatient">确认创建</el-button>
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
import TreeFilter from "@/components/TreeFilter/index.vue";
import ProTable from "@/components/ProTable/index.vue";
import { ColumnProps, ProTableInstance } from "@/components/ProTable/interface";
import { createPatientApi, getPatientListApi, type CreatePatientParams, type PatientRow } from "@/api/modules/clinic";
import { roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";

type DateScopeNode = {
  id: string;
  label: string;
  count?: number;
  from?: string;
  to?: string;
  kind?: "group" | "leaf";
  children?: DateScopeNode[];
};

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();
const proTable = ref<ProTableInstance>();
const createFormRef = ref<FormInstance>();
const createDialogVisible = ref(false);
const creating = ref(false);
const activeDateScope = ref("");
const allPatients = ref<PatientRow[]>([]);
const currentTotal = ref(0);

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
  { prop: "name", label: "患者姓名", minWidth: 150, search: { el: "input" } },
  { prop: "visitNo", label: "最近门诊/住院号", minWidth: 185, search: { el: "input" } },
  { prop: "visitType", label: "最近类型", width: 110 },
  { prop: "visitDate", label: "就诊日期", width: 150 },
  { prop: "encounterCount", label: "就诊次数", width: 110 },
  { prop: "doctor", label: "接诊医生", width: 120 },
  {
    prop: "status",
    label: "资料状态",
    width: 150,
    enum: [
      { label: "待上传", value: "待上传" },
      { label: "资料已上传", value: "资料已上传" },
      { label: "旧资料已归档", value: "旧资料已归档" },
      { label: "有作废记录", value: "有作废记录" }
    ],
    search: { el: "select" }
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

const recentDayNodes = computed<DateScopeNode[]>(() => {
  const today = new Date();
  return Array.from({ length: 30 }, (_, index) => {
    const date = toDateText(addDays(today, -index));
    const count = countByDate.value.get(date) || 0;
    return {
      id: `date::${date}`,
      label: count ? date : `${date} · 空档`,
      count,
      kind: "leaf" as const
    };
  });
});

const monthNodes = computed<DateScopeNode[]>(() => {
  const monthMap = new Map<string, number>();
  countByDate.value.forEach((count, date) => {
    const month = date.slice(0, 7);
    monthMap.set(month, (monthMap.get(month) || 0) + count);
  });
  return [...monthMap.entries()]
    .sort((left, right) => right[0].localeCompare(left[0]))
    .map(([month, count]) => ({
      id: `month::${month}`,
      label: month,
      count,
      kind: "leaf"
    }));
});

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

const groupNode = (id: string, label: string, children: DateScopeNode[]): DateScopeNode => ({
  id: `group::${id}`,
  label,
  count: children.reduce((total, child) => total + (child.count || 0), 0),
  kind: "group",
  children
});

const dateTree = computed<DateScopeNode[]>(() => [
  groupNode("quick", "快捷回看", quickRangeNodes.value),
  groupNode("recent-day", "近 30 天日历", recentDayNodes.value),
  groupNode("month", "按月份", monthNodes.value),
  groupNode("day", "有收录日期归档", dayNodes.value)
]);

const activeScopeTitle = computed(() => {
  if (!activeDateScope.value) return "全部患者收录";
  const [type, value] = activeDateScope.value.split("::");
  if (type === "range") {
    const range = quickRangeNodes.value.find(item => item.id === activeDateScope.value);
    return range?.label || "快捷回看";
  }
  if (type === "month") return `${value} 月收录`;
  if (type === "date") return `${value} 当日收录`;
  const group = dateTree.value.find(item => item.id === activeDateScope.value);
  return group?.label || "全部患者收录";
});

const activeScopeDesc = computed(() => {
  if (!activeDateScope.value)
    return "展示所有患者，可从左侧选择某天、某月或近 7/30 天，快速回滚查看历史病历收录。近 30 天日历会保留空档日期，方便确认某天确实无人收录。";
  const [type, value] = activeDateScope.value.split("::");
  if (type === "date") {
    const count = countByDate.value.get(value) || 0;
    return count ? `该日共收录 ${count} 位患者，可继续按姓名或门诊/住院号缩小范围。` : "该日暂无患者收录。";
  }
  if (type === "month") return "当前按月份聚合，适合回看患者激增或空档时间段。";
  if (type === "range") return "当前按常用时间窗口聚合，适合日常交接和近期补录核对。";
  return "选择具体日期节点后，右侧表格会自动收束到对应就诊日期，空档日期会直接呈现 0 条结果。";
});

const clearDateParam = () => {
  initParam.visitDateFrom = undefined;
  initParam.visitDateTo = undefined;
};

const changeDateScope = (value: string) => {
  activeDateScope.value = value || "";
  clearDateParam();

  const [type, scopeValue] = activeDateScope.value.split("::");
  if (!scopeValue || type === "group") return;
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

const statusTagType = (status: string) => {
  if (status.includes("归档") || status.includes("完整") || status.includes("上传")) return "success";
  if (status.includes("待") || status.includes("补")) return "warning";
  if (status.includes("作废")) return "danger";
  return "info";
};

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
      router.push(`/patients/detail/${data.id}`);
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
}

.patient-list-page {
  gap: 12px;
  min-width: 0;
}

.date-scope-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
  background: linear-gradient(135deg, rgb(236 253 245 / 72%), rgb(255 255 255 / 95%)), var(--el-bg-color);
  border: 1px solid rgb(20 184 166 / 18%);
  border-radius: 8px;

  h2 {
    margin: 3px 0 4px;
    color: var(--el-text-color-primary);
    font-size: 18px;
    font-weight: 700;
  }

  p {
    max-width: 760px;
    margin: 0;
    color: var(--el-text-color-secondary);
    line-height: 1.7;
  }
}

.scope-eyebrow {
  color: #008f84;
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

.date-tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 10px;

  &.is-group .tree-label {
    color: var(--el-text-color-primary);
    font-weight: 700;
  }
}

.tree-label {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-count {
  flex: 0 0 auto;
  min-width: 28px;
  padding: 1px 7px;
  color: #008f84;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
  background: rgb(20 184 166 / 10%);
  border-radius: 999px;
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

.visit-no-cell strong {
  font-family: Arial, "Microsoft YaHei", sans-serif;
}

.status-cell {
  align-items: flex-start;
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

  .date-scope-card,
  .patient-table-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .scope-actions {
    flex-wrap: wrap;
  }
}
</style>
