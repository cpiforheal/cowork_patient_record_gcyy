<template>
  <div class="dip">
    <header class="head">
      <div class="head-main">
        <h2>DIP 费用预警</h2>
        <p>按病组预估费用监控未结算费用 · 达 <b class="t-warn">85%</b> 预警、<b class="t-danger">95%</b> 超限</p>
      </div>
      <div class="head-side">
        <span class="scope">{{ scopeLabel }}</span>
        <el-button :icon="Refresh" circle size="small" :loading="loading" @click="reload" />
      </div>
    </header>

    <nav class="strip">
      <button
        v-for="item in strips"
        :key="item.key"
        type="button"
        class="strip-item"
        :class="[`is-${item.key}`, { active: filter === item.key }]"
        @click="toggleFilter(item.key)"
      >
        <b>{{ item.count }}</b>
        <span>{{ item.label }}</span>
      </button>
    </nav>

    <section class="toolbar">
      <el-input
        v-model="keyword"
        class="search"
        placeholder="搜索姓名 / 住院号 / 诊断 / 病组"
        clearable
        :prefix-icon="Search"
      />
      <span v-if="filter !== 'all'" class="filter-note">
        已筛选 {{ filterLabel }}<button type="button" @click="filter = 'all'">清除</button>
      </span>
    </section>

    <el-table
      v-loading="loading"
      :data="visibleRows"
      class="table"
      row-key="patientId"
      :row-class-name="rowClass"
    >
      <el-table-column label="患者" min-width="200">
        <template #default="{ row }">
          <div class="cell-patient">
            <strong>{{ row.name }}</strong>
            <span class="sub">{{ patientMeta(row) }}</span>
            <span class="diag">{{ row.diagnosis || "—" }}</span>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="DIP 病组" min-width="210">
        <template #default="{ row }">
          <div v-if="row.matched" class="cell-group">
            <span class="group-name">{{ row.dipName }}</span>
            <span class="sub">
              {{ row.dipCode }}
              <i v-if="row.groupSource === 'MANUAL'" class="tag-manual">已改选</i>
            </span>
          </div>
          <div v-else class="cell-group">
            <span class="sub is-muted">病组表未覆盖该诊断</span>
            <button type="button" class="link" @click="openGroupDialog(row)">指定病组</button>
          </div>
        </template>
      </el-table-column>

      <!-- 主视觉：费用水位 -->
      <el-table-column label="费用水位" width="266">
        <template #default="{ row }">
          <div v-if="row.level === 'pending' || row.level === 'unmatched'" class="water is-pending">
            <span class="water-none">未录入未结算费用</span>
            <button type="button" class="link" @click="openFeeDialog(row)">录入费用</button>
          </div>
          <div v-else class="water" :class="`is-${row.level}`">
            <div class="water-top">
              <b class="fee">{{ money(row.currentFee) }}</b>
              <span class="ratio">{{ row.ratio.toFixed(1) }}%</span>
            </div>
            <div class="bar">
              <i class="fill" :style="{ width: `${Math.min(row.ratio, 100)}%` }" />
              <i class="tick" style="left: 85%" />
              <i class="tick" style="left: 95%" />
            </div>
            <div class="water-foot">
              <span>预估 {{ money(row.estimatedFee) }}</span>
              <span :class="`t-${row.level}`">{{ suggestion(row) }}</span>
            </div>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="处理" width="176" align="right">
        <template #default="{ row }">
          <div class="cell-action">
            <el-tag v-if="row.level !== 'pending' && row.level !== 'unmatched'" :type="tagType(row.level)" size="small" effect="light">
              {{ levelText(row.level) }}
            </el-tag>
            <el-tag v-else type="info" size="small" effect="plain">{{ levelText(row.level) }}</el-tag>
            <el-button link type="primary" size="small" @click="openFeeDialog(row)">录费用</el-button>
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          </div>
        </template>
      </el-table-column>

      <template #empty>
        <el-empty description="当前范围暂无在院患者" :image-size="70" />
      </template>
    </el-table>

    <!-- 详情：完整 HIS 与审计信息收在这里 -->
    <el-drawer v-model="detailVisible" :title="detailTarget ? `${detailTarget.name} · 费用水位` : '费用水位'" size="430px">
      <div v-if="detailTarget" class="detail">
        <section class="detail-hero" :class="`is-${detailTarget.level}`">
          <div class="hero-row">
            <b class="hero-fee">{{ detailTarget.level === "pending" ? "待录入" : money(detailTarget.currentFee) }}</b>
            <span v-if="detailTarget.level !== 'pending' && detailTarget.level !== 'unmatched'" class="hero-ratio">
              {{ detailTarget.ratio.toFixed(1) }}%
            </span>
          </div>
          <div v-if="detailTarget.level !== 'pending' && detailTarget.level !== 'unmatched'" class="bar is-tall">
            <i class="fill" :style="{ width: `${Math.min(detailTarget.ratio, 100)}%` }" />
            <i class="tick" style="left: 85%" />
            <i class="tick is-super" style="left: 95%" />
          </div>
          <div class="hero-scale">
            <span>¥0</span>
            <span>{{ money(detailTarget.threshold85) }}</span>
            <span>{{ money(detailTarget.threshold95) }}</span>
            <span>{{ money(detailTarget.estimatedFee) }}</span>
          </div>
          <p class="hero-tip">{{ detailTip(detailTarget) }}</p>
        </section>

        <el-descriptions :column="1" border size="small" class="detail-table">
          <el-descriptions-item label="诊断">{{ detailTarget.diagnosis || "—" }}</el-descriptions-item>
          <el-descriptions-item label="DIP 病组">
            {{ detailTarget.dipName || "—" }}
            <span class="sub"> {{ detailTarget.dipCode }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="剩余到 95%">{{ money(detailTarget.remainTo95) }}</el-descriptions-item>
          <el-descriptions-item label="住院号">{{ blank(detailTarget.admissionNo) }}</el-descriptions-item>
          <el-descriptions-item label="病人类型">{{ blank(detailTarget.patientType) }}</el-descriptions-item>
          <el-descriptions-item label="床号 / 护理">{{ pair(detailTarget.bedNo, detailTarget.nursingLevel) }}</el-descriptions-item>
          <el-descriptions-item label="押金（总额 / 余额）">
            {{ pair(detailTarget.depositTotal, detailTarget.depositBalance) }}
          </el-descriptions-item>
          <el-descriptions-item label="入院日期 / 天数">
            {{ pair(detailTarget.admissionDate, detailTarget.hospitalDays) }}
          </el-descriptions-item>
          <el-descriptions-item label="管床医生">{{ blank(detailTarget.attendingDoctor) }}</el-descriptions-item>
          <el-descriptions-item label="费用更新">
            {{ detailTarget.updatedAt }}<span v-if="detailTarget.feeUpdatedBy" class="sub"> · {{ detailTarget.feeUpdatedBy }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="detail-actions">
          <el-button size="small" @click="openFeeDialog(detailTarget)">录入费用</el-button>
          <el-button v-if="detailTarget.matched" size="small" @click="openGroupDialog(detailTarget)">改选病组</el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 录入费用 -->
    <el-dialog v-model="feeDialogVisible" title="录入未结算费用" width="430px">
      <el-form v-if="feeTarget" label-width="92px">
        <el-form-item label="患者">
          <span>{{ feeTarget.name }}<span v-if="feeTarget.admissionNo" class="sub"> · {{ feeTarget.admissionNo }}</span></span>
        </el-form-item>
        <el-form-item label="未结算费用">
          <el-input-number v-model="feeInput" :min="0" :precision="2" :step="100" controls-position="right" />
        </el-form-item>
        <el-form-item v-if="feeTarget.matched" label="录入后水位">
          <b :class="`t-${previewLevel}`">{{ previewRatio.toFixed(1) }}%</b>
          <span class="sub">（85% = {{ money(feeTarget.threshold85) }}，95% = {{ money(feeTarget.threshold95) }}）</span>
        </el-form-item>
        <el-collapse v-model="morePanel" class="more">
          <el-collapse-item title="HIS 附加信息（可选）" name="more">
            <el-form-item label="住院号">
              <el-input v-model="feeForm.admissionNo" placeholder="HIS 住院号" />
            </el-form-item>
            <el-form-item label="押金余额">
              <el-input-number v-model="feeForm.depositBalance" :precision="2" :step="100" controls-position="right" />
            </el-form-item>
            <el-form-item label="病人类型">
              <el-input v-model="feeForm.patientType" placeholder="如 国家医保（居民）" />
            </el-form-item>
            <el-form-item label="床号">
              <el-input v-model="feeForm.bedNo" placeholder="HIS 床号" />
            </el-form-item>
            <el-form-item label="管床医生">
              <el-input v-model="feeForm.attendingDoctor" placeholder="HIS 管床医生" />
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button @click="feeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingFee" @click="submitFee">保存</el-button>
      </template>
    </el-dialog>

    <!-- 改选病组 -->
    <el-dialog v-model="groupDialogVisible" title="改选病组" width="500px">
      <div v-if="groupTarget" class="group-dialog">
        <p class="sub">{{ groupTarget.name }} · {{ groupTarget.diagnosis }}</p>
        <el-select v-model="groupInput" filterable placeholder="选择病组" class="full">
          <el-option-group v-for="group in groupOptionsGrouped" :key="group.categoryCode" :label="group.categoryName">
            <el-option
              v-for="option in group.options"
              :key="option.groupCode"
              :label="`${option.groupName} · ${option.groupCode}`"
              :value="option.groupCode"
            />
          </el-option-group>
        </el-select>
        <p v-if="selectedGroup" class="sub">
          预估 <b class="t-normal">{{ money(selectedGroup.estimatedFee) }}</b>
          <template v-if="groupTarget.currentFee > 0">
            → 改选后水位 <b :class="`t-${previewGroupLevel}`">{{ previewGroupRatio.toFixed(1) }}%</b>
          </template>
        </p>
        <el-button v-if="groupTarget.groupSource === 'MANUAL'" link type="primary" size="small" @click="groupInput = ''">
          恢复主诊断自动匹配
        </el-button>
      </div>
      <template #footer>
        <el-button @click="groupDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingGroup" @click="submitGroup">保存</el-button>
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
type FilterKey = "danger" | "warning" | "normal" | "pending";

interface Row {
  patientId: string;
  name: string;
  gender: string;
  age: number | null;
  diagnosis: string;
  matched: boolean;
  dipCode: string;
  dipName: string;
  groupSource: "AUTO" | "MANUAL";
  estimatedFee: number;
  currentFee: number;
  threshold85: number;
  threshold95: number;
  ratio: number;
  remainTo95: number;
  level: Level;
  updatedAt: string;
  feeUpdatedBy: string;
  admissionNo: string;
  patientType: string;
  bedNo: string;
  nursingLevel: string;
  depositTotal: number | null;
  depositBalance: number | null;
  admissionDate: string;
  hospitalDays: number | null;
  attendingDoctor: string;
}

const loading = ref(false);
const keyword = ref("");
const filter = ref<"all" | FilterKey>("all");
const rawRows = ref<DipPatientRow[]>([]);
const rows = ref<Row[]>([]);

const money = (value: number | null | undefined) =>
  value === null || value === undefined ? "—" : `¥${Number(value).toFixed(2)}`;
const blank = (value: string | null | undefined) => (value && value.trim() ? value : "—");
const pair = (a: string | number | null | undefined, b: string | number | null | undefined) => {
  const left = a === null || a === undefined || a === "" ? "—" : a;
  const right = b === null || b === undefined || b === "" ? "—" : b;
  return `${left} / ${right}`;
};
const levelOf = (ratio: number): Level => (ratio >= 95 ? "danger" : ratio >= 85 ? "warning" : "normal");
const levelText = (level: Level) =>
  level === "danger" ? "超限" : level === "warning" ? "预警" : level === "pending" ? "待录入" : level === "unmatched" ? "未匹配" : "正常";
const tagType = (level: Level) => (level === "danger" ? "danger" : level === "warning" ? "warning" : "success");

const toRow = (item: DipPatientRow): Row => {
  const estimatedFee = Number(item.estimatedFee ?? 0);
  const hasFee = item.unsettledFee !== null && item.unsettledFee !== undefined;
  const currentFee = hasFee ? Number(item.unsettledFee) : 0;
  const threshold85 = Number(item.threshold85 ?? 0);
  const threshold95 = Number(item.threshold95 ?? 0);
  const ratio = estimatedFee > 0 && hasFee ? (currentFee / estimatedFee) * 100 : 0;
  const level: Level = !item.matched ? "unmatched" : !hasFee ? "pending" : levelOf(ratio);
  return {
    patientId: item.patientId,
    name: item.patientName,
    gender: item.gender ?? "",
    age: item.age ?? null,
    diagnosis: item.diagnosis ?? "",
    matched: item.matched,
    dipCode: item.groupCode ?? "",
    dipName: item.groupName ?? "",
    groupSource: (item.groupSource as "AUTO" | "MANUAL") ?? "AUTO",
    estimatedFee,
    currentFee,
    threshold85,
    threshold95,
    ratio,
    remainTo95: Number((threshold95 - currentFee).toFixed(2)),
    level,
    updatedAt: item.feeUpdatedAt ?? "",
    feeUpdatedBy: item.feeUpdatedBy ?? "",
    admissionNo: item.admissionNo ?? "",
    patientType: item.patientType ?? "",
    bedNo: item.bedNo ?? "",
    nursingLevel: item.nursingLevel ?? "",
    depositTotal: item.depositTotal ?? null,
    depositBalance: item.depositBalance ?? null,
    admissionDate: item.admissionDate ?? "",
    hospitalDays: item.hospitalDays ?? null,
    attendingDoctor: item.attendingDoctor ?? ""
  };
};

const reload = async () => {
  loading.value = true;
  try {
    const board = await getDipBoardApi();
    rawRows.value = board.patients ?? [];
    rows.value = rawRows.value.map(toRow);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "费用预警数据加载失败");
  } finally {
    loading.value = false;
  }
};

const scopeLabel = computed(() => {
  const total = rows.value.length;
  const visible = rows.value.filter(row => row.level === "danger" || row.level === "warning" || row.level === "pending").length;
  return total ? `可见 ${total} 例 · 待处理 ${visible} 例` : "";
});

const countOf = (level: Level) => rows.value.filter(row => row.level === level).length;
const strips = computed(() => [
  { key: "danger" as FilterKey, label: "超限", count: countOf("danger") },
  { key: "warning" as FilterKey, label: "预警", count: countOf("warning") },
  { key: "pending" as FilterKey, label: "待录入", count: countOf("pending") },
  { key: "normal" as FilterKey, label: "正常", count: countOf("normal") }
]);
const filterLabel = computed(() => strips.value.find(item => item.key === filter.value)?.label ?? "");

const toggleFilter = (key: FilterKey) => {
  filter.value = filter.value === key ? "all" : key;
};

const rankOf = (level: Level) =>
  level === "danger" ? 0 : level === "warning" ? 1 : level === "pending" ? 2 : level === "unmatched" ? 4 : 3;

const visibleRows = computed(() => {
  const key = keyword.value.trim().toLowerCase();
  const filtered = rows.value.filter(row => {
    if (filter.value !== "all" && row.level !== filter.value) return false;
    if (!key) return true;
    return [row.name, row.diagnosis, row.admissionNo, row.dipCode, row.dipName]
      .filter(Boolean)
      .some(value => String(value).toLowerCase().includes(key));
  });
  return [...filtered].sort((a, b) => {
    const rank = rankOf(a.level) - rankOf(b.level);
    return rank !== 0 ? rank : b.ratio - a.ratio;
  });
});

const rowClass = ({ row }: { row: Row }) =>
  row.level === "danger" ? "row-danger" : row.level === "warning" ? "row-warning" : "";

const patientMeta = (row: Row) =>
  [row.gender, row.age === null ? "" : `${row.age}岁`, row.admissionNo ? `住院号 ${row.admissionNo}` : ""]
    .filter(Boolean)
    .join(" · ") || "—";

const suggestion = (row: Row) => {
  if (row.level === "danger") return `超 ${money(row.currentFee - row.threshold95)}`;
  if (row.level === "warning") return `余 ${money(row.threshold95 - row.currentFee)}`;
  return `余 ${money(row.threshold95 - row.currentFee)}`;
};

const detailTip = (row: Row) => {
  if (row.level === "danger") return "已达 95% 超限线：如无特殊情况应停止计费并安排出院。";
  if (row.level === "warning") return "已达 85% 预警线：请关注后续费用，提前核对诊疗项目。";
  if (row.level === "pending") return "尚未录入 HIS 未结算费用，录入后才参与水位计算。";
  if (row.level === "unmatched") return "主诊断未匹配到病组，指定病组后才能估算费用。";
  return "费用水位正常。";
};

/* ---------- 详情 ---------- */
const detailVisible = ref(false);
const detailTarget = ref<Row | null>(null);
const openDetail = (row: Row) => {
  detailTarget.value = row;
  detailVisible.value = true;
};

/* ---------- 录入费用 ---------- */
const feeDialogVisible = ref(false);
const savingFee = ref(false);
const feeTarget = ref<Row | null>(null);
const feeInput = ref(0);
const morePanel = ref<string[]>([]);
const feeForm = reactive({
  admissionNo: "",
  patientType: "",
  bedNo: "",
  attendingDoctor: "",
  depositBalance: null as number | null
});

const openFeeDialog = (row: Row) => {
  feeTarget.value = row;
  feeInput.value = row.currentFee;
  feeForm.admissionNo = row.admissionNo;
  feeForm.patientType = row.patientType;
  feeForm.bedNo = row.bedNo;
  feeForm.attendingDoctor = row.attendingDoctor;
  feeForm.depositBalance = row.depositBalance;
  morePanel.value = [];
  feeDialogVisible.value = true;
};

const previewRatio = computed(() => {
  const target = feeTarget.value;
  if (!target || !target.estimatedFee) return 0;
  return (feeInput.value / target.estimatedFee) * 100;
});
const previewLevel = computed(() => levelOf(previewRatio.value));

const submitFee = async () => {
  const target = feeTarget.value;
  if (!target) return;
  savingFee.value = true;
  try {
    await saveDipFeeApi({
      patientId: target.patientId,
      unsettledFee: feeInput.value,
      source: "MANUAL",
      admissionNo: feeForm.admissionNo,
      patientType: feeForm.patientType,
      bedNo: feeForm.bedNo,
      attendingDoctor: feeForm.attendingDoctor,
      depositBalance: feeForm.depositBalance
    });
    feeDialogVisible.value = false;
    await reload();
    ElMessage.success(`${target.name} 费用已更新`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "费用保存失败");
  } finally {
    savingFee.value = false;
  }
};

/* ---------- 改选病组 ---------- */
const groupDialogVisible = ref(false);
const savingGroup = ref(false);
const groupTarget = ref<Row | null>(null);
const groupInput = ref("");
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

const selectedGroup = computed(
  () => groupOptions.value.find(option => option.groupCode === groupInput.value) ?? null
);

const previewGroupRatio = computed(() => {
  const target = groupTarget.value;
  const option = selectedGroup.value;
  if (!target || !option || !option.estimatedFee || target.currentFee <= 0) return 0;
  return (target.currentFee / option.estimatedFee) * 100;
});
const previewGroupLevel = computed(() => levelOf(previewGroupRatio.value));

const openGroupDialog = async (row: Row) => {
  groupTarget.value = row;
  groupInput.value = row.dipCode;
  groupDialogVisible.value = true;
  if (groupOptions.value.length) return;
  try {
    groupOptions.value = await getDipGroupsApi();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "病组加载失败");
  }
};

const submitGroup = async () => {
  const target = groupTarget.value;
  if (!target) return;
  savingGroup.value = true;
  try {
    await saveDipGroupApi({ patientId: target.patientId, groupCode: groupInput.value });
    groupDialogVisible.value = false;
    await reload();
    ElMessage.success(groupInput.value ? "病组已更新" : "已恢复自动匹配");
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
  --ok: var(--el-color-success);
  --warn: var(--el-color-warning);
  --danger: var(--el-color-danger);

  padding: 16px 20px 28px;
  display: flex;
  flex-direction: column;
  gap: 12px;

  .head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;

    h2 {
      margin: 0 0 3px;
      font-size: 18px;
      font-weight: 600;
    }

    p {
      margin: 0;
      font-size: 12.5px;
      color: var(--el-text-color-secondary);
    }
  }

  .head-side {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .scope {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .t-warn {
    color: var(--warn);
  }

  .t-danger {
    color: var(--danger);
  }

  .t-warning {
    color: var(--warn);
  }

  .t-normal {
    color: var(--ok);
  }

  /* 状态条：只有异常才上色 */
  .strip {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }

  .strip-item {
    display: inline-flex;
    align-items: baseline;
    gap: 5px;
    padding: 4px 11px;
    border: 1px solid var(--el-border-color);
    border-radius: 999px;
    background: var(--el-bg-color);
    cursor: pointer;
    transition: all 0.15s;

    b {
      font-size: 14px;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
      color: var(--el-text-color-primary);
    }

    span {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }

    &.is-danger:not(.active) b {
      color: var(--danger);
    }

    &.is-warning:not(.active) b {
      color: var(--warn);
    }

    &.is-danger.active {
      border-color: var(--danger);
      background: var(--el-color-danger-light-9);
    }

    &.is-warning.active {
      border-color: var(--warn);
      background: var(--el-color-warning-light-9);
    }

    &.is-pending.active,
    &.is-normal.active {
      border-color: var(--el-text-color-secondary);
      background: var(--el-fill-color-light);
    }

    &:hover {
      border-color: var(--el-color-primary-light-5);
    }
  }

  .toolbar {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .search {
    width: 260px;
  }

  .filter-note {
    font-size: 12px;
    color: var(--el-text-color-secondary);

    button {
      margin-left: 6px;
      border: none;
      background: none;
      padding: 0;
      font-size: 12px;
      color: var(--el-color-primary);
      cursor: pointer;
    }
  }

  .table {
    :deep(.el-table__cell) {
      padding: 8px 0;
    }

    :deep(.cell) {
      padding: 0 10px;
      line-height: 1.45;
    }
  }

  .sub {
    font-size: 11.5px;
    color: var(--el-text-color-secondary);

    &.is-muted {
      color: var(--el-text-color-placeholder);
    }
  }

  .cell-patient {
    display: flex;
    flex-direction: column;

    strong {
      font-size: 14.5px;
      font-weight: 600;
      line-height: 1.3;
    }

    .diag {
      font-size: 12.5px;
      color: var(--el-text-color-regular);
    }
  }

  .cell-group {
    display: flex;
    flex-direction: column;
    gap: 1px;

    .group-name {
      font-size: 12.5px;
      color: var(--el-text-color-regular);
    }
  }

  .tag-manual {
    font-style: normal;
    font-size: 10.5px;
    padding: 0 4px;
    margin-left: 4px;
    border-radius: 2px;
    color: var(--warn);
    background: var(--el-color-warning-light-9);
  }

  .link {
    border: none;
    background: none;
    padding: 0;
    font-size: 11.5px;
    color: var(--el-color-primary);
    cursor: pointer;

    &:hover {
      text-decoration: underline;
    }
  }

  /* 主视觉：水位 */
  .water {
    display: flex;
    flex-direction: column;
    gap: 3px;

    &.is-pending {
      gap: 2px;
      align-items: flex-start;
    }
  }

  .water-none {
    font-size: 12px;
    color: var(--el-text-color-placeholder);
  }

  .water-top {
    display: flex;
    align-items: baseline;
    gap: 8px;

    .fee {
      font-size: 17px;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
      line-height: 1.2;
    }

    .ratio {
      font-size: 13px;
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }

    .is-normal & .fee {
      color: var(--el-text-color-primary);
    }

    .is-normal & .ratio {
      color: var(--el-text-color-secondary);
    }

    .is-warning & .fee,
    .is-warning & .ratio {
      color: var(--warn);
    }

    .is-danger & .fee {
      color: var(--danger);
      font-size: 18px;
    }

    .is-danger & .ratio {
      color: var(--danger);
    }
  }

  .bar {
    position: relative;
    height: 6px;
    border-radius: 3px;
    background: var(--el-fill-color-darker);
    overflow: hidden;

    &.is-tall {
      height: 10px;
      border-radius: 5px;
      margin-top: 8px;
    }
  }

  .fill {
    position: absolute;
    inset: 0 auto 0 0;
    background: var(--ok);

    .is-warning & {
      background: var(--warn);
    }

    .is-danger & {
      background: var(--danger);
    }
  }

  .tick {
    position: absolute;
    top: 0;
    bottom: 0;
    width: 1px;
    background: var(--el-bg-color);
    opacity: 0.9;
  }

  .water-foot {
    display: flex;
    justify-content: space-between;
    gap: 8px;
    font-size: 11px;
    color: var(--el-text-color-secondary);
    font-variant-numeric: tabular-nums;
  }

  .cell-action {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
  }

  :deep(.row-danger) {
    background: var(--el-color-danger-light-9);

    td:first-child {
      box-shadow: inset 3px 0 0 var(--danger);
    }
  }

  :deep(.row-warning) {
    background: var(--el-color-warning-light-9);

    td:first-child {
      box-shadow: inset 3px 0 0 var(--warn);
    }
  }

  /* 详情 */
  .detail {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .detail-hero {
    padding: 12px 14px;
    border-radius: 8px;
    background: var(--el-fill-color-lighter);

    &.is-danger {
      background: var(--el-color-danger-light-9);
    }

    &.is-warning {
      background: var(--el-color-warning-light-9);
    }

    .hero-row {
      display: flex;
      align-items: baseline;
      gap: 10px;
    }

    .hero-fee {
      font-size: 24px;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }

    .hero-ratio {
      font-size: 15px;
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }

    &.is-danger .hero-fee,
    &.is-danger .hero-ratio {
      color: var(--danger);
    }

    &.is-warning .hero-fee,
    &.is-warning .hero-ratio {
      color: var(--warn);
    }
  }

  .hero-scale {
    display: flex;
    justify-content: space-between;
    margin-top: 5px;
    font-size: 11px;
    color: var(--el-text-color-secondary);
  }

  .hero-tip {
    margin: 10px 0 0;
    font-size: 12px;
    color: var(--el-text-color-regular);
  }

  .detail-actions {
    display: flex;
    gap: 8px;
  }

  .group-dialog {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .full {
    width: 100%;
  }

  .more {
    border-top: 1px solid var(--el-border-color-lighter);
    border-bottom: none;
  }
}
</style>
