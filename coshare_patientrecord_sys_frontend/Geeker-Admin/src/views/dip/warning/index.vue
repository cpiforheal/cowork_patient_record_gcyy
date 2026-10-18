<template>
  <div class="dip">
    <header class="dip-head">
      <div class="dip-head-main">
        <h2>住院患者费用预警</h2>
        <p>
          字段与 HIS 住院费用界面保持一致；仅在本系统内对<b class="t-danger">未结算费用</b>按 DIP 病组预估费用做
          <b class="t-warn">85%</b> / <b class="t-danger">95%</b> 预警标记。病组由主诊断自动匹配，医生可改选。
        </p>
      </div>
      <div class="dip-head-actions">
        <el-switch v-model="demoSwitch" inline-prompt active-text="演示" inactive-text="真实" />
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      </div>
    </header>

    <div class="dip-summary">
      <span>在院 <b>{{ rows.length }}</b></span>
      <span class="t-ok">正常 <b>{{ stats.normal }}</b></span>
      <span class="t-warn">预警 <b>{{ stats.warning }}</b></span>
      <span class="t-danger">超限 <b>{{ stats.danger }}</b></span>
      <span class="t-muted">待录费用 <b>{{ stats.pending }}</b></span>
      <span class="t-muted">未匹配 <b>{{ stats.unmatched }}</b></span>
      <span class="dip-summary-gap" />
      <span v-if="stats.overrunTotal > 0" class="t-danger">超阈值合计 <b>{{ money(stats.overrunTotal) }}</b></span>
      <span class="t-muted dip-generated">口径：未结算费用 / 病组预估费用</span>
    </div>

    <section class="dip-toolbar">
      <el-input v-model="keyword" class="dip-search" placeholder="搜索姓名 / 住院号 / 诊断 / 病组" clearable :prefix-icon="Search" />
      <el-radio-group v-model="levelFilter" size="small">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="danger">超限</el-radio-button>
        <el-radio-button value="warning">预警</el-radio-button>
        <el-radio-button value="pending">待录费用</el-radio-button>
        <el-radio-button value="unmatched">未匹配</el-radio-button>
      </el-radio-group>
    </section>

    <el-table
      v-loading="loading"
      :data="visibleRows"
      row-key="patientId"
      class="dip-table"
      :row-class-name="rowClass"
      empty-text="暂无患者数据"
    >
      <el-table-column type="index" label="序号" width="60" align="center" />
      <el-table-column label="病人类型" width="132">
        <template #default="{ row }">
          <span class="dip-type">{{ row.patientType || "—" }}</span>
        </template>
      </el-table-column>
      <el-table-column label="住院号" width="118">
        <template #default="{ row }">{{ row.admissionNo || "—" }}</template>
      </el-table-column>
      <el-table-column prop="patientName" label="姓名" width="92" />
      <el-table-column label="床号" width="70" align="center">
        <template #default="{ row }">{{ row.bedNo || "—" }}</template>
      </el-table-column>
      <el-table-column label="性别" width="66" align="center">
        <template #default="{ row }">{{ row.gender || "—" }}</template>
      </el-table-column>
      <el-table-column label="年龄" width="72" align="center">
        <template #default="{ row }">{{ row.age === null || row.age === undefined ? "—" : `${row.age}岁` }}</template>
      </el-table-column>
      <el-table-column label="诊断" width="108">
        <template #default="{ row }">
          <span class="dip-diag-text">{{ row.diagnosis || "—" }}</span>
        </template>
      </el-table-column>

      <!-- 唯一做预警着色的列 -->
      <el-table-column label="未结算费用" width="188" align="right" column-key="unsettledFee">
        <template #default="{ row }">
          <div class="dip-fee" :class="`is-${row.level}`">
            <div class="dip-fee-main">
              <span class="dip-fee-value">{{ row.displayFee === null ? "待录入" : money(row.displayFee) }}</span>
              <span v-if="row.level === 'danger'" class="dip-fee-badge">超限</span>
              <span v-else-if="row.level === 'warning'" class="dip-fee-badge">预警</span>
            </div>
            <div v-if="row.matched && row.displayFee !== null" class="dip-fee-bar">
              <span class="dip-fee-bar-track">
                <i class="dip-fee-bar-fill" :style="{ width: `${clamp(row.ratio)}%` }" />
                <i class="dip-fee-bar-tick" style="left: 85%" />
                <i class="dip-fee-bar-tick" style="left: 95%" />
              </span>
              <em>{{ row.ratio.toFixed(1) }}%</em>
            </div>
            <div v-if="row.matched" class="dip-fee-hint">
              95% {{ money(row.threshold95) }}
              <template v-if="row.displayFee !== null">
                <span v-if="row.overTo95 > 0" class="t-danger">· 超 {{ money(row.overTo95) }}</span>
                <span v-else class="t-muted">· 余 {{ money(Math.max(row.restTo95 ?? 0, 0)) }}</span>
              </template>
            </div>
            <button type="button" class="dip-fee-edit" @click="openFeeDialog(row)">录入/修改</button>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="押金总额" width="104" align="right">
        <template #default="{ row }">{{ row.depositTotal === null || row.depositTotal === undefined ? "—" : money(row.depositTotal) }}</template>
      </el-table-column>
      <el-table-column label="押金余额" width="110" align="right">
        <template #default="{ row }">
          <span
            v-if="row.depositBalance !== null && row.depositBalance !== undefined"
            :class="{ 't-danger': Number(row.depositBalance) < 0 }"
          >
            {{ money(row.depositBalance) }}
          </span>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="入院日期" width="150">
        <template #default="{ row }">{{ shortDate(row.admissionDate) }}</template>
      </el-table-column>
      <el-table-column label="入院天数" width="86" align="center">
        <template #default="{ row }">{{ row.hospitalDays ?? "—" }}</template>
      </el-table-column>
      <el-table-column label="护理级别" width="92">
        <template #default="{ row }">{{ row.nursingLevel || "—" }}</template>
      </el-table-column>
      <el-table-column label="管床医生" width="94">
        <template #default="{ row }">{{ row.attendingDoctor || "—" }}</template>
      </el-table-column>

      <!-- 本系统增值列：可改选的 DIP 病组 -->
      <el-table-column label="DIP 病组（可改）" min-width="222">
        <template #default="{ row }">
          <div v-if="row.matched" class="dip-group">
            <div class="dip-group-line">
              <el-tag size="small" effect="plain" type="primary">{{ row.groupCode }}</el-tag>
              <el-tag v-if="row.groupSource === 'MANUAL'" size="small" type="warning" effect="plain">已改选</el-tag>
            </div>
            <span class="dip-group-name">{{ row.groupName }}</span>
            <span class="dip-group-fee">预估 {{ money(row.estimatedFee) }} · 85% {{ money(row.threshold85) }}</span>
            <button type="button" class="dip-group-edit" @click="openGroupDialog(row)">改选病组</button>
          </div>
          <div v-else class="dip-group is-none">
            <span class="dip-group-none">病组表未覆盖此主诊断</span>
            <button type="button" class="dip-group-edit" @click="openGroupDialog(row)">手动指定</button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <!-- 录入 HIS 费用与住院信息 -->
    <el-dialog v-model="feeDialogVisible" title="录入 HIS 住院费用信息" width="620px">
      <el-form v-if="feeTarget" :model="feeForm" label-width="104px" class="dip-form">
        <div class="dip-form-grid">
          <el-form-item label="患者">
            <span>{{ feeTarget.patientName }} · {{ feeTarget.diagnosis }}</span>
          </el-form-item>
          <el-form-item label="住院号">
            <el-input v-model="feeForm.admissionNo" placeholder="HIS 住院号" />
          </el-form-item>
          <el-form-item label="病人类型">
            <el-input v-model="feeForm.patientType" placeholder="如 国家医保（居民）" />
          </el-form-item>
          <el-form-item label="床号">
            <el-input v-model="feeForm.bedNo" placeholder="HIS 床号" />
          </el-form-item>
          <el-form-item label="护理级别">
            <el-input v-model="feeForm.nursingLevel" placeholder="如 一级护理" />
          </el-form-item>
          <el-form-item label="管床医生">
            <el-input v-model="feeForm.attendingDoctor" placeholder="HIS 管床医生" />
          </el-form-item>
          <el-form-item label="入院日期">
            <el-input v-model="feeForm.admissionDate" placeholder="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="押金总额">
            <el-input-number v-model="feeForm.depositTotal" :min="0" :precision="2" :step="500" controls-position="right" />
          </el-form-item>
          <el-form-item label="押金余额">
            <el-input-number v-model="feeForm.depositBalance" :precision="2" :step="100" controls-position="right" />
          </el-form-item>
        </div>
        <el-form-item label="未结算费用">
          <el-input-number v-model="feeForm.unsettledFee" :min="0" :precision="2" :step="100" controls-position="right" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="feeForm.remark" placeholder="可选" />
        </el-form-item>
        <el-form-item v-if="feeTarget.matched" label="录入后水位">
          <div class="dip-preview" :class="`is-${previewLevel}`">
            <b>{{ previewRatio.toFixed(1) }}%</b>
            <span>85% = {{ money(feeTarget.threshold85) }} / 95% = {{ money(feeTarget.threshold95) }}</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="feeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingFee" @click="submitFee">保存</el-button>
      </template>
    </el-dialog>

    <!-- 改选病组 -->
    <el-dialog v-model="groupDialogVisible" title="改选 DIP 病组" width="560px">
      <div v-if="groupTarget" class="dip-group-dialog">
        <div class="dip-group-current">
          <div>
            <span>患者</span>
            <strong>{{ groupTarget.patientName }}</strong>
            <em>{{ groupTarget.diagnosis }}</em>
          </div>
          <div v-if="groupTarget.groupSource === 'MANUAL'">
            <span>当前</span>
            <strong class="t-warn">已手工改选</strong>
            <em>自动匹配为 {{ groupTarget.autoGroupCode || "无" }}</em>
          </div>
        </div>
        <el-select v-model="groupInput" filterable placeholder="选择病组（按编码或名称搜索）" class="dip-group-select">
          <el-option-group v-for="group in groupOptionsGrouped" :key="group.categoryCode" :label="`${group.categoryCode} ${group.categoryName}`">
            <el-option
              v-for="option in group.options"
              :key="option.groupCode"
              :label="`${option.groupCode} ${option.groupName} · 预估 ${option.estimatedFee}`"
              :value="option.groupCode"
            />
          </el-option-group>
        </el-select>
        <div v-if="selectedGroup" class="dip-group-picked">
          预估费用 <b>{{ money(selectedGroup.estimatedFee) }}</b> · 85% <b>{{ money(selectedGroup.estimatedFee * 0.85) }}</b> ·
          95% <b>{{ money(selectedGroup.estimatedFee * 0.95) }}</b>
          <template v-if="groupTarget.displayFee !== null">
            → 改选后水位 <b :class="`is-${previewGroupLevel}`">{{ previewGroupRatio.toFixed(1) }}%</b>
          </template>
        </div>
        <el-input v-model="groupNote" placeholder="改选原因（可选，记录留痕）" class="dip-group-note" />
        <el-button link type="primary" @click="restoreAutoGroup">恢复主诊断自动匹配</el-button>
      </div>
      <template #footer>
        <el-button @click="groupDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingGroup" @click="submitGroup">保存病组</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="dipWarning">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh, Search } from "@element-plus/icons-vue";
import {
  getDipBoardApi,
  getDipGroupsApi,
  saveDipFeeApi,
  saveDipGroupApi,
  type DipGroupOption,
  type DipPatientRow
} from "@/api/modules/clinic/dipWarning";

type Level = "normal" | "warning" | "danger" | "pending" | "unmatched";

interface ViewRow {
  patientId: string;
  patientName: string;
  patientType: string;
  admissionNo: string;
  bedNo: string;
  gender: string;
  age: number | null;
  diagnosis: string;
  matched: boolean;
  groupCode: string;
  groupName: string;
  groupSource: "AUTO" | "MANUAL";
  autoGroupCode: string | null;
  categoryCode: string;
  estimatedFee: number;
  threshold85: number;
  threshold95: number;
  depositTotal: number | null;
  depositBalance: number | null;
  admissionDate: string;
  hospitalDays: number | null;
  nursingLevel: string;
  attendingDoctor: string;
  displayFee: number | null;
  ratio: number;
  level: Level;
  overTo95: number;
  restTo95: number | null;
  demoFee: boolean;
}

const loading = ref(false);
const keyword = ref("");
const levelFilter = ref<"all" | Level>("all");
const demoMode = ref(false);
const demoFees = ref<Record<string, number>>({});

const rawRows = ref<DipPatientRow[]>([]);

const money = (value: number | null | undefined) =>
  value === null || value === undefined || Number.isNaN(Number(value)) ? "—" : `¥${Number(value).toFixed(2)}`;
const clamp = (ratio: number) => Math.max(0, Math.min(ratio, 100));
const shortDate = (value?: string) => (value && value.length >= 10 ? value.slice(0, 10) : value || "—");
const levelOf = (ratio: number): Level => (ratio >= 95 ? "danger" : ratio >= 85 ? "warning" : "normal");

const rows = computed<ViewRow[]>(() =>
  rawRows.value.map(row => {
    const matched = row.matched;
    const estimatedFee = Number(row.estimatedFee ?? 0);
    const baseFee = row.unsettledFee === undefined || row.unsettledFee === null ? null : Number(row.unsettledFee);
    const demoFee = demoFees.value[row.patientId];
    const displayFee = demoFee !== undefined ? demoFee : baseFee;
    const ratio = matched && estimatedFee > 0 && displayFee !== null ? (displayFee / estimatedFee) * 100 : 0;
    const level: Level = !matched ? "unmatched" : displayFee === null ? "pending" : levelOf(ratio);
    return {
      patientId: row.patientId,
      patientName: row.patientName,
      patientType: row.patientType ?? "",
      admissionNo: row.admissionNo ?? "",
      bedNo: row.bedNo ?? "",
      gender: row.gender ?? "",
      age: row.age === undefined ? null : row.age,
      diagnosis: row.diagnosis ?? "",
      matched,
      groupCode: row.groupCode ?? "",
      groupName: row.groupName ?? "",
      groupSource: (row.groupSource as "AUTO" | "MANUAL") ?? "AUTO",
      autoGroupCode: row.autoGroupCode ?? null,
      categoryCode: row.categoryCode ?? "",
      estimatedFee,
      threshold85: Number(row.threshold85 ?? 0),
      threshold95: Number(row.threshold95 ?? 0),
      depositTotal: row.depositTotal === undefined ? null : row.depositTotal,
      depositBalance: row.depositBalance === undefined ? null : row.depositBalance,
      admissionDate: row.admissionDate ?? "",
      hospitalDays: row.hospitalDays === undefined ? null : row.hospitalDays,
      nursingLevel: row.nursingLevel ?? "",
      attendingDoctor: row.attendingDoctor ?? "",
      displayFee,
      ratio,
      level,
      overTo95: matched && displayFee !== null ? Math.max(displayFee - Number(row.threshold95 ?? 0), 0) : 0,
      restTo95: row.restTo95 === undefined || row.restTo95 === null ? null : Number(row.restTo95),
      demoFee: demoFee !== undefined
    };
  })
);

const stats = computed(() => {
  const list = rows.value;
  return {
    normal: list.filter(row => row.level === "normal").length,
    warning: list.filter(row => row.level === "warning").length,
    danger: list.filter(row => row.level === "danger").length,
    pending: list.filter(row => row.level === "pending").length,
    unmatched: list.filter(row => row.level === "unmatched").length,
    overrunTotal: list.reduce((sum, row) => sum + row.overTo95, 0)
  };
});

const rankOf = (level: Level) => (level === "danger" ? 0 : level === "warning" ? 1 : level === "normal" ? 2 : level === "pending" ? 3 : 4);

const visibleRows = computed(() => {
  const key = keyword.value.trim().toLowerCase();
  const filtered = rows.value.filter(row => {
    if (levelFilter.value !== "all" && row.level !== levelFilter.value) return false;
    if (!key) return true;
    return [row.patientName, row.admissionNo, row.diagnosis, row.groupCode, row.groupName, row.attendingDoctor]
      .filter(Boolean)
      .some(value => String(value).toLowerCase().includes(key));
  });
  return [...filtered].sort((a, b) => {
    const rank = rankOf(a.level) - rankOf(b.level);
    return rank !== 0 ? rank : b.ratio - a.ratio;
  });
});

const rowClass = ({ row }: { row: ViewRow }) =>
  row.level === "danger" ? "dip-row-danger" : row.level === "warning" ? "dip-row-warning" : "";

const reload = async () => {
  loading.value = true;
  try {
    const board = await getDipBoardApi();
    rawRows.value = board.patients ?? [];
    if (demoMode.value) buildDemoFees();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "费用预警数据加载失败");
  } finally {
    loading.value = false;
  }
};

const buildDemoFees = () => {
  const targets = rows.value.filter(row => row.matched);
  const ratios = [103.2, 97.8, 92.4, 88.1, 84.6, 76.2, 68.5, 59.4, 51.2, 43.8, 36.1, 28.4];
  const next: Record<string, number> = {};
  targets.forEach((row, index) => {
    next[row.patientId] = Number(((row.estimatedFee * ratios[index % ratios.length]) / 100).toFixed(2));
  });
  demoFees.value = next;
};

const demoSwitch = computed({
  get: () => demoMode.value,
  set: (value: boolean) => {
    demoMode.value = value;
    if (value) {
      buildDemoFees();
      ElMessage.success("演示水位：仅前端预览，不写入数据库");
    } else {
      demoFees.value = {};
    }
  }
});

/* ---------------- 录入费用 ---------------- */
const feeDialogVisible = ref(false);
const savingFee = ref(false);
const feeTarget = ref<ViewRow | null>(null);
const feeForm = reactive({
  admissionNo: "",
  patientType: "",
  bedNo: "",
  nursingLevel: "",
  attendingDoctor: "",
  admissionDate: "",
  depositTotal: 0 as number | null,
  depositBalance: 0 as number | null,
  unsettledFee: 0,
  remark: ""
});

const openFeeDialog = (row: ViewRow) => {
  feeTarget.value = row;
  feeForm.admissionNo = row.admissionNo;
  feeForm.patientType = row.patientType;
  feeForm.bedNo = row.bedNo;
  feeForm.nursingLevel = row.nursingLevel;
  feeForm.attendingDoctor = row.attendingDoctor;
  feeForm.admissionDate = shortDate(row.admissionDate) === "—" ? "" : shortDate(row.admissionDate);
  feeForm.depositTotal = row.depositTotal;
  feeForm.depositBalance = row.depositBalance;
  feeForm.unsettledFee = row.displayFee ?? 0;
  feeForm.remark = "";
  feeDialogVisible.value = true;
};

const previewRatio = computed(() => {
  const target = feeTarget.value;
  if (!target || !target.estimatedFee) return 0;
  return (feeForm.unsettledFee / target.estimatedFee) * 100;
});
const previewLevel = computed(() => levelOf(previewRatio.value));

const submitFee = async () => {
  const target = feeTarget.value;
  if (!target) return;
  savingFee.value = true;
  try {
    await saveDipFeeApi({
      patientId: target.patientId,
      unsettledFee: feeForm.unsettledFee,
      source: "MANUAL",
      admissionNo: feeForm.admissionNo,
      patientType: feeForm.patientType,
      bedNo: feeForm.bedNo,
      nursingLevel: feeForm.nursingLevel,
      attendingDoctor: feeForm.attendingDoctor,
      admissionDate: feeForm.admissionDate,
      depositTotal: feeForm.depositTotal,
      depositBalance: feeForm.depositBalance,
      remark: feeForm.remark
    });
    feeDialogVisible.value = false;
    if (demoFees.value[target.patientId] !== undefined) {
      const next = { ...demoFees.value };
      delete next[target.patientId];
      demoFees.value = next;
    }
    await reload();
    ElMessage.success(`${target.patientName} 费用已保存`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "费用保存失败");
  } finally {
    savingFee.value = false;
  }
};

/* ---------------- 改选病组 ---------------- */
const groupDialogVisible = ref(false);
const savingGroup = ref(false);
const groupTarget = ref<ViewRow | null>(null);
const groupInput = ref("");
const groupNote = ref("");
const groupOptions = ref<DipGroupOption[]>([]);

const groupOptionsGrouped = computed(() => {
  const map = new Map<string, { categoryCode: string; categoryName: string; options: DipGroupOption[] }>();
  groupOptions.value.forEach(option => {
    const entry = map.get(option.categoryCode) ?? {
      categoryCode: option.categoryCode,
      categoryName: option.categoryName,
      options: []
    };
    entry.options.push(option);
    map.set(option.categoryCode, entry);
  });
  return [...map.values()];
});

const selectedGroup = computed(() => groupOptions.value.find(option => option.groupCode === groupInput.value) ?? null);

const previewGroupRatio = computed(() => {
  const target = groupTarget.value;
  const option = selectedGroup.value;
  if (!target || !option || target.displayFee === null || !option.estimatedFee) return 0;
  return (target.displayFee / option.estimatedFee) * 100;
});
const previewGroupLevel = computed(() => levelOf(previewGroupRatio.value));

const openGroupDialog = async (row: ViewRow) => {
  groupTarget.value = row;
  groupInput.value = row.groupCode || "";
  groupNote.value = "";
  groupDialogVisible.value = true;
  if (!groupOptions.value.length) {
    try {
      groupOptions.value = await getDipGroupsApi();
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : "病组列表加载失败");
    }
  }
};

const restoreAutoGroup = () => {
  groupInput.value = "";
  ElMessage.info("已清空选择，保存后恢复主诊断自动匹配");
};

const submitGroup = async () => {
  const target = groupTarget.value;
  if (!target) return;
  savingGroup.value = true;
  try {
    await saveDipGroupApi({ patientId: target.patientId, groupCode: groupInput.value, note: groupNote.value });
    groupDialogVisible.value = false;
    await reload();
    ElMessage.success(groupInput.value ? "病组已改选" : "已恢复自动匹配");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "病组保存失败");
  } finally {
    savingGroup.value = false;
  }
};

onMounted(reload);
</script>

<style scoped lang="scss">
.dip {
  --dip-ok: var(--el-color-success);
  --dip-warn: var(--el-color-warning);
  --dip-danger: var(--el-color-danger);

  padding: 16px 18px 28px;
  display: flex;
  flex-direction: column;
  gap: 12px;

  .dip-head {
    display: flex;
    justify-content: space-between;
    gap: 16px;
    flex-wrap: wrap;
  }

  .dip-head-main {
    h2 {
      margin: 0 0 4px;
      font-size: 19px;
      font-weight: 600;
    }

    p {
      margin: 0;
      max-width: 980px;
      font-size: 12.5px;
      line-height: 1.6;
      color: var(--el-text-color-secondary);
    }
  }

  .dip-head-actions {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .t-ok {
    color: var(--dip-ok);
  }

  .t-warn {
    color: var(--dip-warn);
  }

  .t-danger {
    color: var(--dip-danger);
  }

  .t-muted {
    color: var(--el-text-color-secondary);
  }

  .dip-summary {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 14px;
    padding: 8px 12px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;
    background: var(--el-fill-color-lighter);
    font-size: 12.5px;
    color: var(--el-text-color-regular);

    b {
      font-size: 14px;
      font-variant-numeric: tabular-nums;
    }
  }

  .dip-summary-gap {
    flex: 1;
  }

  .dip-generated {
    font-size: 11.5px;
  }

  .dip-toolbar {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  .dip-search {
    width: 268px;
  }

  /* 表格贴近 HIS 密度 */
  :deep(.el-table) {
    font-size: 13px;

    .el-table__cell {
      padding: 5px 0;
    }

    .cell {
      padding: 0 6px;
      line-height: 1.5;
    }
  }

  .dip-type {
    color: var(--el-text-color-regular);
  }

  .dip-diag-text {
    color: var(--el-color-danger);
  }

  /* 未结算费用：唯一预警着色区 */
  .dip-fee {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 2px;
    margin: -3px -6px;
    padding: 3px 6px;
    border-radius: 4px;
    border-left: 3px solid transparent;

    &.is-normal {
      border-left-color: var(--dip-ok);
      background: var(--el-color-success-light-9);
    }

    &.is-warning {
      border-left-color: var(--dip-warn);
      background: var(--el-color-warning-light-9);
    }

    &.is-danger {
      border-left-color: var(--dip-danger);
      background: var(--el-color-danger-light-9);
    }

    &.is-pending,
    &.is-unmatched {
      border-left-color: var(--el-border-color);
    }
  }

  .dip-fee-main {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .dip-fee-value {
    font-weight: 700;
    font-variant-numeric: tabular-nums;

    .is-normal & {
      color: var(--dip-ok);
    }

    .is-warning & {
      color: var(--dip-warn);
    }

    .is-danger & {
      color: var(--dip-danger);
      font-size: 14px;
    }

    .is-pending &,
    .is-unmatched & {
      color: var(--el-text-color-placeholder);
      font-weight: 400;
      font-size: 12px;
    }
  }

  .dip-fee-badge {
    font-size: 10px;
    line-height: 14px;
    padding: 0 3px;
    border-radius: 2px;
    color: #fff;

    .is-warning & {
      background: var(--dip-warn);
    }

    .is-danger & {
      background: var(--dip-danger);
      animation: dip-blink 1.6s ease-in-out infinite;
    }
  }

  .dip-fee-bar {
    display: flex;
    align-items: center;
    gap: 4px;
    width: 100%;
    justify-content: flex-end;

    .dip-fee-bar-track {
      position: relative;
      flex: 1;
      height: 4px;
      border-radius: 3px;
      background: var(--el-fill-color-dark);
      overflow: hidden;
    }

    .dip-fee-bar-fill {
      position: absolute;
      top: 0;
      bottom: 0;
      left: 0;
      background: currentcolor;

      .is-normal & {
        background: var(--dip-ok);
      }

      .is-warning & {
        background: var(--dip-warn);
      }

      .is-danger & {
        background: var(--dip-danger);
      }
    }

    .dip-fee-bar-tick {
      position: absolute;
      top: 0;
      bottom: 0;
      width: 1px;
      background: var(--el-text-color-placeholder);
    }

    em {
      font-style: normal;
      font-size: 10px;
      font-variant-numeric: tabular-nums;

      .is-normal & {
        color: var(--dip-ok);
      }

      .is-warning & {
        color: var(--dip-warn);
      }

      .is-danger & {
        color: var(--dip-danger);
        font-weight: 700;
      }
    }
  }

  .dip-fee-hint {
    font-size: 10px;
    color: var(--el-text-color-placeholder);
  }

  .dip-fee-edit {
    border: none;
    background: none;
    padding: 0;
    font-size: 10.5px;
    color: var(--el-color-primary);
    cursor: pointer;

    &:hover {
      text-decoration: underline;
    }
  }

  /* 病组列 */
  .dip-group {
    display: flex;
    flex-direction: column;
    gap: 2px;

    &.is-none {
      color: var(--el-text-color-placeholder);
    }
  }

  .dip-group-line {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .dip-group-name {
    font-size: 12px;
    color: var(--el-text-color-regular);
  }

  .dip-group-fee {
    font-size: 10.5px;
    color: var(--el-text-color-placeholder);
    font-variant-numeric: tabular-nums;
  }

  .dip-group-none {
    font-size: 11.5px;
    color: var(--el-text-color-placeholder);
  }

  .dip-group-edit {
    align-self: flex-start;
    border: none;
    background: none;
    padding: 0;
    font-size: 10.5px;
    color: var(--el-color-primary);
    cursor: pointer;

    &:hover {
      text-decoration: underline;
    }
  }

  :deep(.dip-row-danger) {
    td:first-child {
      box-shadow: inset 3px 0 0 var(--dip-danger);
    }
  }

  :deep(.dip-row-warning) {
    td:first-child {
      box-shadow: inset 3px 0 0 var(--dip-warn);
    }
  }

  /* 表单 */
  .dip-form-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0 12px;
  }

  .dip-preview {
    display: flex;
    align-items: baseline;
    gap: 8px;

    b {
      font-size: 18px;
      font-weight: 800;
      font-variant-numeric: tabular-nums;
    }

    span {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }

    &.is-normal b {
      color: var(--dip-ok);
    }

    &.is-warning b {
      color: var(--dip-warn);
    }

    &.is-danger b {
      color: var(--dip-danger);
    }
  }

  /* 病组弹窗 */
  .dip-group-dialog {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .dip-group-current {
    display: flex;
    gap: 24px;
    padding: 10px 12px;
    border-radius: 6px;
    background: var(--el-fill-color-lighter);
    font-size: 12.5px;

    > div {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    span {
      color: var(--el-text-color-secondary);
      font-size: 11.5px;
    }

    strong {
      font-size: 13.5px;
    }

    em {
      font-style: normal;
      font-size: 11px;
      color: var(--el-text-color-placeholder);
    }
  }

  .dip-group-select,
  .dip-group-note {
    width: 100%;
  }

  .dip-group-picked {
    font-size: 12px;
    color: var(--el-text-color-secondary);

    b {
      color: var(--el-text-color-primary);
      font-variant-numeric: tabular-nums;
    }
  }
}

@keyframes dip-blink {
  0%,
  100% {
    opacity: 1;
  }

  50% {
    opacity: 0.55;
  }
}
</style>
