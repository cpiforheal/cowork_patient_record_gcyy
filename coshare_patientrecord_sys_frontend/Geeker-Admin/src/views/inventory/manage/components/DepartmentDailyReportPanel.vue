<template>
  <section class="daily-report" v-loading="loading">
    <header class="report-toolbar">
      <div>
        <span class="eyebrow">科室日报 · {{ draft?.departmentName || departmentName }}</span>
        <h2>科室耗材日报</h2>
        <p v-if="!isQuotaMode">固定模板自动带出，可按当天实际使用修改数量或追加耗材；提交后进入库管审核。</p>
        <p v-else>院方已核定每人次定额。科室只需确认接诊人数；定额由系统自动计算并扣库，超量请单独上报例外。</p>
      </div>
      <div class="report-actions">
        <el-date-picker v-model="businessDate" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="load" />
        <el-tag :type="statusType(draft?.status)" effect="plain">{{ statusLabel(draft?.status) }}</el-tag>
        <el-button :disabled="!editable" :loading="saving" @click="save">{{ isQuotaMode ? "保存人数" : "保存草稿" }}</el-button>
        <el-button type="primary" :disabled="!editable" :loading="submitting" @click="submit">
          {{ isQuotaMode ? "提交并扣库" : "提交审核" }}
        </el-button>
        <el-button v-if="!isQuotaMode && editable && !draft?.zeroUsage" :loading="submitting" @click="submitZeroUse">今日无使用</el-button>
        <el-button v-if="!isQuotaMode && draft?.zeroUsage" :loading="submitting" @click="withdrawZeroUse">撤回零使用</el-button>
        <el-button v-if="draft?.id" :disabled="exporting" :loading="exporting" @click="exportReport">导出</el-button>
      </div>
    </header>

    <div class="report-body">
      <div class="report-main">
        <div class="report-summary">
          <span>耗材行 <b>{{ lines.length }}</b></span>
          <template v-if="isQuotaMode">
            <span>接诊人次 <b>{{ headcountTotal }}</b></span>
            <span>应耗 <b>{{ quotaComputedTotal }}</b></span>
            <span>待审例外 <b>{{ draft?.pendingExceptions || 0 }}</b></span>
          </template>
          <span v-else>实际总量 <b>{{ totalQuantity }}</b></span>
          <span v-if="draft?.locked" class="locked">已锁定</span>
          <span v-else-if="draft?.closure?.submittedAt" class="submitted">提交于 {{ formatDateTime(draft.closure.submittedAt) }}</span>
        </div>

        <div v-if="isQuotaMode" class="quota-headcount">
          <div class="quota-headcount-title">
            <strong>接诊人数</strong>
            <small v-if="draft?.entryMode === 'PATIENT'">按当日未取消就诊记录自动统计，同一患者多次就诊算多次</small>
            <small v-else>本科室无临床接诊记录，请手工填写今日服务人次</small>
          </div>
          <div class="quota-headcount-fields">
            <template v-if="draft?.entryMode === 'PATIENT'">
              <label>门诊 <el-input-number v-model="headcountForm.outpatient" :min="0" :precision="0" size="small" :disabled="!headcountEditing" /></label>
              <label>住院 <el-input-number v-model="headcountForm.inpatient" :min="0" :precision="0" size="small" :disabled="!headcountEditing" /></label>
              <el-button v-if="!headcountEditing" size="small" :disabled="!canCorrectHeadcount" @click="startHeadcountEdit">修正人数</el-button>
              <template v-else>
                <el-input v-model="headcountForm.reason" size="small" placeholder="修正原因（必填）" style="width: 220px" />
                <el-button v-if="draft?.status !== 'DRAFT'" size="small" type="primary" :loading="saving" @click="save">保存修正</el-button>
                <el-button size="small" @click="cancelHeadcountEdit">取消</el-button>
              </template>
            </template>
            <template v-else>
              <label>门诊人次 <el-input-number v-model="headcountForm.outpatient" :min="0" :precision="0" size="small" :disabled="!editable && !headcountEditing" /></label>
              <label>住院人次 <el-input-number v-model="headcountForm.inpatient" :min="0" :precision="0" size="small" :disabled="!editable && !headcountEditing" /></label>
              <el-button v-if="canCorrectHeadcount && !headcountEditing" size="small" @click="startHeadcountEdit">修正人数</el-button>
              <template v-if="headcountEditing">
                <el-input v-model="headcountForm.reason" size="small" placeholder="修正原因（必填）" style="width: 220px" />
                <el-button v-if="draft?.status !== 'DRAFT'" size="small" type="primary" :loading="saving" @click="save">保存修正</el-button>
                <el-button size="small" @click="cancelHeadcountEdit">取消</el-button>
              </template>
            </template>
          </div>
        </div>

        <el-alert
          v-if="draft?.locked"
          type="warning"
          :closable="false"
          show-icon
          title="该业务日已锁定"
          description="历史日报不能直接修改；如需更正，请发起更正单。"
        />

        <el-table :data="lines" class="report-table" height="calc(100vh - 340px)">
          <el-table-column prop="itemName" label="耗材" min-width="200" show-overflow-tooltip />
          <el-table-column prop="unit" label="单位" width="80" />
          <template v-if="isQuotaMode">
            <el-table-column label="原用量" width="120" show-overflow-tooltip>
              <template #default="{ row }">{{ row.sourceText || "—" }}</template>
            </el-table-column>
            <el-table-column label="应耗（定额）" width="120" align="right">
              <template #default="{ row }">{{ formatQuantity(row.quotaQuantity ?? row.presetQuantity) }}</template>
            </el-table-column>
            <el-table-column label="免审上浮" width="110" align="right">
              <template #default="{ row }">{{ formatQuantity(row.toleranceQuantity) }}</template>
            </el-table-column>
            <el-table-column label="已扣 / 已通过" width="130" align="right">
              <template #default="{ row }">{{ formatQuantity(row.actualQuantity) }}</template>
            </el-table-column>
            <el-table-column label="上报超量" width="100" align="right">
              <template #default="{ row }">{{ formatQuantity(row.extraQuantity) }}</template>
            </el-table-column>
            <el-table-column label="例外状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="exceptionTagType(row.exceptionStatus)">{{ exceptionLabel(row.exceptionStatus) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button
                  size="small"
                  type="warning"
                  plain
                  :disabled="!canReportException"
                  @click="openExceptionRow(row)"
                >上报例外</el-button>
              </template>
            </el-table-column>
          </template>
          <template v-else>
            <el-table-column label="模板数量" width="110" align="right">
              <template #default="{ row }">{{ formatQuantity(row.presetQuantity) }}</template>
            </el-table-column>
            <el-table-column label="实际使用" width="150">
              <template #default="{ row }">
                <el-input-number
                  v-model="row.actualQuantity"
                  :min="0"
                  :precision="2"
                  :step="1"
                  size="small"
                  controls-position="right"
                  :disabled="!editable"
                />
              </template>
            </el-table-column>
            <el-table-column label="来源" width="90">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="row.source === 'MANUAL' ? 'warning' : 'info'">
                  {{ row.source === "MANUAL" ? "追加" : "模板" }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="200">
              <template #default="{ row }">
                <el-input v-model="row.exceptionReason" size="small" :disabled="!editable" placeholder="选填" />
              </template>
            </el-table-column>
            <el-table-column width="70" align="center">
              <template #default="{ $index }">
                <el-button text type="danger" :disabled="!editable" @click="removeLine($index)">删除</el-button>
              </template>
            </el-table-column>
          </template>
        </el-table>

        <div class="report-footer">
          <template v-if="!isQuotaMode">
            <el-button :icon="Plus" :disabled="!editable" @click="addDialogVisible = true">追加耗材</el-button>
            <span>模板按当前定额规则带出；追加耗材请填写说明。</span>
          </template>
          <span v-else>科室不能修改定额或已扣数量；超量请上报例外，由管理员审核确定补扣量。</span>
        </div>
      </div>

      <DepartmentFillCalendar
        class="report-calendar"
        :month="calendarMonth"
        :today="calendarToday"
        :selected-date="businessDate"
        :mode="draft?.entryMode || 'DAILY'"
        :days="calendarDays"
        :expected-from="calendarExpectedFrom"
        :loading="calendarLoading"
        @update:month="changeCalendarMonth"
        @select-date="selectCalendarDate"
      />
    </div>

    <el-dialog v-model="addDialogVisible" title="追加科室日报耗材" width="540px">
      <el-form label-position="top">
        <el-form-item label="耗材">
          <el-select v-model="addItemId" filterable placeholder="搜索物资主数据" class="add-item-select">
            <el-option
              v-for="item in availableItems"
              :key="item.id"
              :label="`${item.name}（${item.unit}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="实际使用量">
          <el-input-number v-model="addItemQuantity" :min="0.01" :precision="2" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="addItemReason" placeholder="例如：临时追加、设备耗材" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="addLine">加入日报</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="exceptionVisible" title="上报定额例外" width="560px" destroy-on-close>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="科室只能上报事实，不能修改定额或已扣数量；最终补扣量由管理员审核确定。"
        style="margin-bottom: 12px"
      />
      <el-form label-position="top">
        <el-form-item label="耗材">
          <span>{{ exceptionLine?.itemName }}（每日定额 {{ formatQuantity(exceptionLine?.quotaQuantity) }}{{ exceptionLine?.unit }}）</span>
        </el-form-item>
        <el-form-item label="当日实际上报总量" required>
          <el-input-number
            v-model="exceptionForm.reportedActualQuantity"
            :min="Number(exceptionLine?.quotaQuantity || 0) + 0.001"
            :precision="2"
            :step="1"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item label="原因说明" required>
          <el-input v-model="exceptionForm.reason" type="textarea" :rows="3" placeholder="请说明超量原因，例如：急诊加台、术中污染更换" />
        </el-form-item>
        <el-form-item label="现场照片（选填，最多 3 张）">
          <el-upload
            v-model:file-list="exceptionFileList"
            list-type="picture-card"
            accept="image/*"
            :auto-upload="false"
            :limit="3"
          >
            <el-icon><Plus /></el-icon>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exceptionVisible = false">取消</el-button>
        <el-button type="primary" :loading="exceptionSubmitting" @click="submitException">上报例外</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import dayjs from "dayjs";
import DepartmentFillCalendar from "./DepartmentFillCalendar.vue";
import {
  correctInventoryQuotaHeadcountApi,
  downloadInventoryDepartmentDailyReportApi,
  getInventoryDepartmentDailyReportApi,
  getInventoryDepartmentDailyReportCalendarApi,
  reportInventoryQuotaExceptionApi,
  saveInventoryDepartmentDailyReportApi,
  saveInventoryQuotaDailyReportApi,
  submitInventoryDepartmentDailyReportApi,
  uploadInventoryQuotaExceptionPhotoApi,
  withdrawInventoryDepartmentZeroUseApi,
  type InventoryDepartmentDailyReport,
  type InventoryDepartmentDailyReportCalendarDay,
  type InventoryDepartmentDailyReportLine,
  type InventoryItem
} from "@/api/modules/inventory";

const props = defineProps<{
  departmentKey: string;
  departmentName?: string;
  today?: string;
  items?: InventoryItem[];
}>();

const businessDate = ref(props.today || dayjs().format("YYYY-MM-DD"));
const draft = ref<InventoryDepartmentDailyReport | null>(null);
const lines = ref<InventoryDepartmentDailyReportLine[]>([]);
const loading = ref(false);
const saving = ref(false);
const submitting = ref(false);
const exporting = ref(false);
const addDialogVisible = ref(false);
const addItemId = ref("");
const addItemQuantity = ref(1);
const addItemReason = ref("");
const calendarMonth = ref(dayjs(businessDate.value).format("YYYY-MM-01"));
const calendarDays = ref<InventoryDepartmentDailyReportCalendarDay[]>([]);
const calendarExpectedFrom = ref("");
const calendarLoading = ref(false);
const calendarToday = computed(() => props.today || dayjs().format("YYYY-MM-DD"));

const editable = computed(() => {
  if (isQuotaMode.value) return Boolean(draft.value && !draft.value.locked && draft.value.status === "DRAFT");
  const status = draft.value?.status;
  return Boolean(draft.value && !draft.value.locked && ["DRAFT", "RETURNED"].includes(status || ""));
});
const isQuotaMode = computed(() => draft.value?.reportMode === "QUOTA");
const headcountEditing = ref(false);
const headcountForm = reactive({
  outpatient: 0,
  inpatient: 0,
  manual: 0,
  reason: ""
});
const headcountTotal = computed(() => headcountForm.outpatient + headcountForm.inpatient + headcountForm.manual);
const quotaComputedTotal = computed(() => Number(draft.value?.quotaComputedTotal || 0).toFixed(2));
const canCorrectHeadcount = computed(() => isQuotaMode.value && Boolean(draft.value?.id) && !draft.value?.locked);
const canReportException = computed(
  () => isQuotaMode.value && Boolean(draft.value?.id) && !draft.value?.locked && draft.value?.status !== "DRAFT"
);
const exceptionVisible = ref(false);
const exceptionSubmitting = ref(false);
const exceptionLine = ref<InventoryDepartmentDailyReportLine>();
const exceptionForm = reactive({ reportedActualQuantity: 0, reason: "" });
const exceptionFileList = ref<any[]>([]);
const totalQuantity = computed(() =>
  lines.value.reduce((sum, line) => sum + Number(line.actualQuantity || 0), 0).toFixed(2)
);
const availableItems = computed(() => (props.items ?? []).filter(item => !lines.value.some(line => line.itemId === item.id)));

const load = async () => {
  loading.value = true;
  try {
    const result = await getInventoryDepartmentDailyReportApi({
      departmentKey: props.departmentKey,
      date: businessDate.value
    });
    draft.value = result.data ?? null;
    lines.value = (result.data?.lines ?? []).map(line => ({ ...line, actualQuantity: Number(line.actualQuantity || 0) }));
    headcountForm.outpatient = Number(result.data?.headcount?.outpatient || 0);
    headcountForm.inpatient = Number(result.data?.headcount?.inpatient || 0);
    headcountForm.manual = Number(result.data?.headcount?.manual || 0);
    headcountForm.reason = "";
    headcountEditing.value = false;
    calendarMonth.value = dayjs(businessDate.value).startOf("month").format("YYYY-MM-01");
    void loadCalendar();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取科室日报失败");
  } finally {
    loading.value = false;
  }
};

const loadCalendar = async () => {
  calendarLoading.value = true;
  try {
    const result = await getInventoryDepartmentDailyReportCalendarApi({
      departmentKey: props.departmentKey,
      month: calendarMonth.value
    });
    calendarDays.value = result.data?.days ?? [];
    calendarExpectedFrom.value = result.data?.expectedFrom ?? "";
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取填写日历失败");
  } finally {
    calendarLoading.value = false;
  }
};

const changeCalendarMonth = (month: string) => {
  calendarMonth.value = month;
  void loadCalendar();
};

const selectCalendarDate = (date: string) => {
  businessDate.value = date;
  void load();
};

const applyReportData = (data?: InventoryDepartmentDailyReport) => {
  draft.value = data ?? null;
  lines.value = (data?.lines ?? []).map(line => ({ ...line, actualQuantity: Number(line.actualQuantity || 0) }));
  headcountForm.outpatient = Number(data?.headcount?.outpatient || 0);
  headcountForm.inpatient = Number(data?.headcount?.inpatient || 0);
  headcountForm.manual = Number(data?.headcount?.manual || 0);
  headcountForm.reason = "";
  headcountEditing.value = false;
};

const startHeadcountEdit = () => {
  if (!canCorrectHeadcount.value) return;
  headcountEditing.value = true;
};

const cancelHeadcountEdit = () => {
  headcountForm.outpatient = Number(draft.value?.headcount?.outpatient || 0);
  headcountForm.inpatient = Number(draft.value?.headcount?.inpatient || 0);
  headcountForm.manual = Number(draft.value?.headcount?.manual || 0);
  headcountForm.reason = "";
  headcountEditing.value = false;
};

const saveQuota = async () => {
  if (!draft.value) return null;
  if (headcountEditing.value && !headcountForm.reason.trim()) {
    ElMessage.warning("修正人数必须填写原因");
    return null;
  }
  saving.value = true;
  try {
    const result =
      draft.value.id && draft.value.status !== "DRAFT"
        ? await correctInventoryQuotaHeadcountApi(draft.value.id, {
            headcountOutpatient: headcountForm.outpatient,
            headcountInpatient: headcountForm.inpatient,
            headcountManual: headcountForm.manual,
            reason: headcountForm.reason
          })
        : await saveInventoryQuotaDailyReportApi({
            departmentKey: props.departmentKey,
            businessDate: businessDate.value,
            revision: draft.value.revision || 0,
            note: draft.value.note || "",
            headcountOverride: headcountEditing.value,
            headcountOutpatient: headcountForm.outpatient,
            headcountInpatient: headcountForm.inpatient,
            headcountManual: headcountForm.manual,
            headcountReason: headcountForm.reason
          });
    applyReportData(result.data);
    ElMessage.success(draft.value?.status === "DRAFT" ? "人数与应耗已更新" : "人数已修正，差额已补扣/冲回");
    void loadCalendar();
    return result.data ?? null;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存定额日报失败");
    return null;
  } finally {
    saving.value = false;
  }
};

const save = async () => {
  if (!draft.value) return null;
  if (isQuotaMode.value) return saveQuota();
  saving.value = true;
  try {
    const result = await saveInventoryDepartmentDailyReportApi({
      departmentKey: props.departmentKey,
      businessDate: businessDate.value,
      revision: draft.value.revision || 0,
      note: "",
      zeroUsage: Boolean(draft.value.zeroUsage),
      lines: lines.value.map(line => ({
        itemId: line.itemId,
        itemName: line.itemName,
        unit: line.unit,
        presetQuantity: line.presetQuantity,
        actualQuantity: Number(line.actualQuantity || 0),
        source: line.source,
        exceptionReason: line.exceptionReason
      }))
    });
    draft.value = result.data ?? draft.value;
    lines.value = (result.data?.lines ?? []).map(line => ({ ...line, actualQuantity: Number(line.actualQuantity || 0) }));
    ElMessage.success("科室日报已保存");
    void loadCalendar();
    return result.data ?? null;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存科室日报失败");
    return null;
  } finally {
    saving.value = false;
  }
};

const submit = async () => {
  let current = draft.value;
  if (!current) return;
  if (!current.id) {
    current = await save();
    if (!current) return;
  }
  submitting.value = true;
  try {
    const result = await submitInventoryDepartmentDailyReportApi(current.id);
    applyReportData(result.data ?? draft.value);
    ElMessage.success(isQuotaMode.value ? "已提交，定额部分已扣库" : "科室日报已提交库管审核");
    void loadCalendar();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "提交科室日报失败");
  } finally {
    submitting.value = false;
  }
};

const submitZeroUse = async () => {
  if (!draft.value) return;
  try {
    await ElMessageBox.confirm("确认今天科室没有使用任何耗材？确认后按零使用日完成，之后新产生的使用需要撤回或更正。", "今日无使用", {
      confirmButtonText: "确认无使用",
      cancelButtonText: "取消",
      type: "warning"
    });
  } catch {
    return;
  }
  submitting.value = true;
  try {
    draft.value = { ...draft.value, zeroUsage: true };
    lines.value = lines.value.map(line => ({ ...line, actualQuantity: 0 }));
    const saved = await save();
    if (!saved?.id) return;
    const result = await submitInventoryDepartmentDailyReportApi(saved.id);
    draft.value = result.data ?? draft.value;
    lines.value = result.data?.lines ?? lines.value;
    ElMessage.success("已标记今日无使用并完成");
    void loadCalendar();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "零使用提交失败");
  } finally {
    submitting.value = false;
  }
};

const withdrawZeroUse = async () => {
  if (!draft.value?.id) return;
  try {
    await ElMessageBox.confirm("撤回今日零使用并恢复为草稿？", "撤回零使用", {
      confirmButtonText: "撤回",
      cancelButtonText: "取消",
      type: "warning"
    });
  } catch {
    return;
  }
  submitting.value = true;
  try {
    const result = await withdrawInventoryDepartmentZeroUseApi(draft.value.id);
    draft.value = result.data ?? draft.value;
    lines.value = result.data?.lines ?? lines.value;
    ElMessage.success("已撤回，恢复为草稿");
    void loadCalendar();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "撤回零使用失败");
  } finally {
    submitting.value = false;
  }
};

const exportReport = async () => {
  if (!draft.value?.id) return;
  exporting.value = true;
  try {
    const { blob, filename } = await downloadInventoryDepartmentDailyReportApi(draft.value.id, "xlsx");
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
    URL.revokeObjectURL(url);
    ElMessage.success("科室日报已导出");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "导出科室日报失败");
  } finally {
    exporting.value = false;
  }
};

const addLine = () => {
  const item = (props.items ?? []).find(option => option.id === addItemId.value);
  if (!item) {
    ElMessage.warning("请选择耗材");
    return;
  }
  lines.value.push({
    itemId: item.id,
    itemName: item.name,
    unit: item.unit,
    presetQuantity: null,
    actualQuantity: Number(addItemQuantity.value || 1),
    source: "MANUAL",
    exceptionReason: addItemReason.value.trim() || "科室临时追加"
  });
  addDialogVisible.value = false;
  addItemId.value = "";
  addItemQuantity.value = 1;
  addItemReason.value = "";
};

const removeLine = (index: number) => {
  lines.value.splice(index, 1);
};

const statusLabel = (status?: string) => {
  switch (status) {
    case "SUBMITTED":
      return "已提交";
    case "APPROVED":
      return "已通过";
    case "PARTIALLY_APPROVED":
      return "部分通过";
    case "RETURNED":
      return "已驳回";
    default:
      return "草稿";
  }
};

const statusType = (status?: string): "success" | "warning" | "danger" | "info" => {
  if (status === "APPROVED") return "success";
  if (status === "RETURNED") return "danger";
  if (status === "SUBMITTED" || status === "PARTIALLY_APPROVED") return "warning";
  return "info";
};

const formatQuantity = (value?: number | null) => {
  if (value === null || value === undefined) return "—";
  return Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
};

const formatDateTime = (value?: string) => {
  if (!value) return "";
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format("MM-DD HH:mm") : value;
};

watch(
  () => props.departmentKey,
  () => load()
);

const exceptionLabel = (status?: string) => {
  switch (status) {
    case "PENDING":
      return "待审核";
    case "APPROVED":
      return "已通过";
    case "PARTIALLY_APPROVED":
      return "部分通过";
    case "REJECTED":
      return "已驳回";
    default:
      return "无";
  }
};
const exceptionTagType = (status?: string) => {
  if (status === "APPROVED") return "success";
  if (status === "PARTIALLY_APPROVED") return "warning";
  if (status === "REJECTED") return "danger";
  if (status === "PENDING") return "primary";
  return "info";
};

const openException = (line: InventoryDepartmentDailyReportLine) => {
  exceptionLine.value = line;
  exceptionForm.reportedActualQuantity = Number(line.quotaQuantity || line.presetQuantity || 0) + 1;
  exceptionForm.reason = "";
  exceptionFileList.value = [];
  exceptionVisible.value = true;
};
const openExceptionRow = (row: any) => openException(row as InventoryDepartmentDailyReportLine);

const submitException = async () => {
  if (!exceptionLine.value || !draft.value) return;
  if (!exceptionForm.reason.trim()) {
    ElMessage.warning("请填写例外原因");
    return;
  }
  exceptionSubmitting.value = true;
  try {
    const result = await reportInventoryQuotaExceptionApi({
      departmentKey: props.departmentKey,
      businessDate: businessDate.value,
      itemId: exceptionLine.value.itemId,
      reportedActualQuantity: exceptionForm.reportedActualQuantity,
      reason: exceptionForm.reason.trim()
    });
    for (const file of exceptionFileList.value) {
      if (file.raw) await uploadInventoryQuotaExceptionPhotoApi(result.data.id, file.raw);
    }
    exceptionVisible.value = false;
    ElMessage.success("例外已上报，等待管理员审核");
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "例外上报失败");
  } finally {
    exceptionSubmitting.value = false;
  }
};

onMounted(() => {
  void load();
});
</script>

<style scoped lang="scss">
.daily-report {
  --hos-bg-primary: #fffdf9;
  --hos-bg-surface: #faf7f1;
  --hos-border-light: #f1ebe1;
  --hos-text-primary: #2f2d27;
  --hos-text-secondary: #6d675c;
  --hos-text-muted: #9a9285;
  --hos-primary: #347d68;
  --hos-primary-soft: #edf7f1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  background: var(--hos-bg-surface);
}

.report-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 18px 20px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 16px;

  h2 {
    margin: 4px 0 4px;
    color: var(--hos-text-primary);
    font-size: 20px;
    font-weight: 700;
  }

  p {
    margin: 0;
    color: var(--hos-text-secondary);
    font-size: 12px;
  }
}

.eyebrow {
  color: var(--hos-primary);
  font-weight: 600;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.report-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.report-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 318px;
  gap: 14px;
  align-items: start;
}

.report-main {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.report-calendar {
  position: sticky;
  top: 12px;
}

.report-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  color: var(--hos-text-secondary);
  font-size: 12px;

  b {
    color: var(--hos-text-primary);
    font-variant-numeric: tabular-nums;
  }

  .locked {
    color: var(--hos-status-warning);
  }

  .submitted {
    color: var(--hos-status-success);
  }
}

.report-table {
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;
}

.report-footer {
  display: flex;
  gap: 10px;
  align-items: center;
  color: var(--hos-text-muted);
  font-size: 11px;
}

.quota-headcount {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--hos-border-light);
  border-radius: 12px;
  background: var(--hos-bg-subtle, var(--el-fill-color-lighter));
}

.quota-headcount-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  small {
    color: var(--hos-text-muted);
    font-size: 11px;
  }
}

.quota-headcount-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: center;
  label {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: var(--hos-text-secondary);
    font-size: 12px;
  }
}

.add-item-select {
  width: 100%;
}

@media (max-width: 1180px) {
  .report-body {
    grid-template-columns: 1fr;
  }

  .report-calendar {
    position: static;
    order: -1;
  }
}

@media (max-width: 760px) {
  .daily-report {
    padding: 10px;
  }
}
</style>
