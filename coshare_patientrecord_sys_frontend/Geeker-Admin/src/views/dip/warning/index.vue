<template>
  <div class="dip">
    <header class="head">
      <div>
        <h2>住院患者费用预警</h2>
        <p>按 DIP 病组预估费用监控未结算费用 · 85% 预警，95% 超限</p>
      </div>
      <div class="head-actions">
        <el-switch v-model="demoSwitch" size="small" inline-prompt active-text="演示" inactive-text="真实" />
        <el-button :icon="Refresh" :loading="loading" circle @click="reload" />
      </div>
    </header>

    <nav class="chips">
      <button
        v-for="chip in chips"
        :key="chip.key"
        type="button"
        class="chip"
        :class="[`is-${chip.key}`, { active: filter === chip.key, zero: chip.count === 0 }]"
        @click="toggleFilter(chip.key)"
      >
        <b>{{ chip.count }}</b>
        <span>{{ chip.label }}</span>
      </button>
    </nav>

    <div class="toolbar">
      <el-input v-model="keyword" class="search" placeholder="搜索姓名 / 诊断 / 病组" clearable :prefix-icon="Search" />
      <span v-if="filter !== 'all'" class="filter-note">
        已筛选：{{ filterLabel }}<button type="button" @click="filter = 'all'">清除</button>
      </span>
    </div>

    <el-table
      v-loading="loading"
      :data="visibleRows"
      row-key="patientId"
      class="table"
      :row-class-name="rowClass"
      empty-text="暂无患者"
    >
      <el-table-column label="患者" min-width="196">
        <template #default="{ row }">
          <div class="cell-patient">
            <strong>{{ row.patientName }}</strong>
            <span class="cell-sub">
              {{ [row.gender, row.age === null ? "" : `${row.age}岁`].filter(Boolean).join(" · ") || "—" }}
              <em>·</em>
              {{ row.diagnosis || "—" }}
            </span>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="DIP 病组" min-width="238">
        <template #default="{ row }">
          <div v-if="row.matched" class="cell-group">
            <div class="cell-group-top">
              <span class="cell-group-name">{{ row.groupName }}</span>
              <button type="button" class="link" @click="openGroupDialog(row)">改</button>
            </div>
            <span class="cell-sub">
              {{ row.groupCode }}
              <em v-if="row.groupSource === 'MANUAL'" class="tag-manual">已改选</em>
            </span>
          </div>
          <div v-else class="cell-group">
            <span class="cell-sub is-muted">该诊断未覆盖病组</span>
            <button type="button" class="link" @click="openGroupDialog(row)">指定</button>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="费用（当前 / 预估）" width="188" align="right">
        <template #default="{ row }">
          <div class="cell-fee" :class="`is-${row.level}`">
            <span class="cell-fee-main">{{ row.displayFee === null ? "待录入" : money(row.displayFee) }}</span>
            <span class="cell-sub">{{ row.matched ? `预估 ${money(row.estimatedFee)}` : "—" }}</span>
            <button v-if="row.displayFee !== null" type="button" class="link" @click="openFeeDialog(row)">改</button>
            <button v-else type="button" class="link" @click="openFeeDialog(row)">录入</button>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="水位" width="168">
        <template #default="{ row }">
          <div v-if="row.matched && row.displayFee !== null" class="cell-water" :class="`is-${row.level}`">
            <div class="water-top">
              <b>{{ row.ratio.toFixed(1) }}%</b>
              <span v-if="row.level === 'danger'" class="water-flag">超限</span>
              <span v-else-if="row.level === 'warning'" class="water-flag">预警</span>
            </div>
            <div class="water-bar">
              <i class="water-fill" :style="{ width: `${clamp(row.ratio)}%` }" />
              <i class="water-tick" style="left: 85%" />
              <i class="water-tick" style="left: 95%" />
            </div>
          </div>
          <span v-else class="cell-sub is-muted">—</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 录入费用 -->
    <el-dialog v-model="feeVisible" title="未结算费用" width="440px">
      <div v-if="feeTarget" class="dialog">
        <p class="dialog-title">{{ feeTarget.patientName }} · {{ feeTarget.diagnosis }}</p>
        <el-form label-width="88px">
          <el-form-item label="费用">
            <el-input-number v-model="feeInput" :min="0" :precision="2" :step="100" controls-position="right" />
          </el-form-item>
          <el-form-item v-if="feeTarget.matched" label="水位">
            <div class="preview" :class="`is-${previewLevel}`">
              <b>{{ previewRatio.toFixed(1) }}%</b>
              <span>预估 {{ money(feeTarget.estimatedFee) }} · 95% {{ money(feeTarget.threshold95) }}</span>
            </div>
          </el-form-item>
        </el-form>
        <el-collapse v-model="morePanel" class="more">
          <el-collapse-item title="HIS 附加信息（可选）" name="more">
            <el-form label-width="88px">
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
            </el-form>
          </el-collapse-item>
        </el-collapse>
      </div>
      <template #footer>
        <el-button @click="feeVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingFee" @click="submitFee">保存</el-button>
      </template>
    </el-dialog>

    <!-- 改选病组 -->
    <el-dialog v-model="groupVisible" title="改选病组" width="520px">
      <div v-if="groupTarget" class="dialog">
        <p class="dialog-title">{{ groupTarget.patientName }} · {{ groupTarget.diagnosis }}</p>
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
        <p v-if="selectedGroup" class="group-picked">
          预估 <b>{{ money(selectedGroup.estimatedFee) }}</b>
          <template v-if="groupTarget.displayFee !== null">
            → 水位 <b :class="`is-${previewGroupLevel}`">{{ previewGroupRatio.toFixed(1) }}%</b>
          </template>
        </p>
        <el-button v-if="groupTarget.groupSource === 'MANUAL'" link type="primary" @click="groupInput = ''">
          恢复自动匹配
        </el-button>
      </div>
      <template #footer>
        <el-button @click="groupVisible = false">取消</el-button>
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
type FilterKey = "danger" | "warning" | "normal" | "pending" | "unmatched";

interface Row {
  patientId: string;
  patientName: string;
  gender: string;
  age: number | null;
  diagnosis: string;
  matched: boolean;
  groupCode: string;
  groupName: string;
  groupSource: "AUTO" | "MANUAL";
  estimatedFee: number;
  threshold95: number;
  displayFee: number | null;
  ratio: number;
  level: Level;
}

const loading = ref(false);
const keyword = ref("");
const filter = ref<"all" | FilterKey>("all");
const demoMode = ref(false);
const demoFees = ref<Record<string, number>>({});
const rawRows = ref<DipPatientRow[]>([]);

const money = (value: number | null | undefined) =>
  value === null || value === undefined || Number.isNaN(Number(value)) ? "—" : `¥${Number(value).toFixed(2)}`;
const clamp = (ratio: number) => Math.max(0, Math.min(ratio, 100));
const levelOf = (ratio: number): Level => (ratio >= 95 ? "danger" : ratio >= 85 ? "warning" : "normal");

const rows = computed<Row[]>(() =>
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
      gender: row.gender ?? "",
      age: row.age ?? null,
      diagnosis: row.diagnosis ?? "",
      matched,
      groupCode: row.groupCode ?? "",
      groupName: row.groupName ?? "",
      groupSource: (row.groupSource as "AUTO" | "MANUAL") ?? "AUTO",
      estimatedFee,
      threshold95: Number(row.threshold95 ?? 0),
      displayFee,
      ratio,
      level
    };
  })
);

const countOf = (level: Level) => rows.value.filter(row => row.level === level).length;

const chips = computed(() => {
  const list = [
    { key: "danger" as FilterKey, label: "超限", count: countOf("danger") },
    { key: "warning" as FilterKey, label: "预警", count: countOf("warning") },
    { key: "normal" as FilterKey, label: "正常", count: countOf("normal") },
    { key: "pending" as FilterKey, label: "待录入", count: countOf("pending") }
  ];
  const unmatched = countOf("unmatched");
  if (unmatched > 0) list.push({ key: "unmatched" as FilterKey, label: "未覆盖", count: unmatched });
  return list;
});

const filterLabel = computed(() => chips.value.find(chip => chip.key === filter.value)?.label ?? "");

const toggleFilter = (key: FilterKey) => {
  filter.value = filter.value === key ? "all" : key;
};

const rankOf = (level: Level) => (level === "danger" ? 0 : level === "warning" ? 1 : level === "normal" ? 2 : level === "pending" ? 3 : 4);

const visibleRows = computed(() => {
  const key = keyword.value.trim().toLowerCase();
  const filtered = rows.value.filter(row => {
    if (filter.value !== "all" && row.level !== filter.value) return false;
    if (!key) return true;
    return [row.patientName, row.diagnosis, row.groupCode, row.groupName]
      .filter(Boolean)
      .some(value => String(value).toLowerCase().includes(key));
  });
  return [...filtered].sort((a, b) => {
    const rank = rankOf(a.level) - rankOf(b.level);
    return rank !== 0 ? rank : b.ratio - a.ratio;
  });
});

const rowClass = ({ row }: { row: Row }) => (row.level === "danger" ? "row-danger" : row.level === "warning" ? "row-warning" : "");

const reload = async () => {
  loading.value = true;
  try {
    const board = await getDipBoardApi();
    rawRows.value = board.patients ?? [];
    if (demoMode.value) buildDemoFees();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "数据加载失败");
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
      ElMessage.success("演示水位：仅前端预览，不写库");
    } else {
      demoFees.value = {};
    }
  }
});

/* ---------- 录入费用 ---------- */
const feeVisible = ref(false);
const savingFee = ref(false);
const feeTarget = ref<Row | null>(null);
const feeInput = ref(0);
const morePanel = ref<string[]>([]);
const feeForm = reactive({ admissionNo: "", patientType: "", bedNo: "", attendingDoctor: "", depositBalance: null as number | null });

const openFeeDialog = (row: Row) => {
  feeTarget.value = row;
  feeInput.value = row.displayFee ?? 0;
  const raw = rawRows.value.find(item => item.patientId === row.patientId);
  feeForm.admissionNo = raw?.admissionNo ?? "";
  feeForm.patientType = raw?.patientType ?? "";
  feeForm.bedNo = raw?.bedNo ?? "";
  feeForm.attendingDoctor = raw?.attendingDoctor ?? "";
  feeForm.depositBalance = raw?.depositBalance ?? null;
  morePanel.value = [];
  feeVisible.value = true;
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
    if (demoFees.value[target.patientId] !== undefined) {
      const next = { ...demoFees.value };
      delete next[target.patientId];
      demoFees.value = next;
    }
    feeVisible.value = false;
    await reload();
    ElMessage.success("已保存");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存失败");
  } finally {
    savingFee.value = false;
  }
};

/* ---------- 改选病组 ---------- */
const groupVisible = ref(false);
const savingGroup = ref(false);
const groupTarget = ref<Row | null>(null);
const groupInput = ref("");
const groupOptions = ref<DipGroupOption[]>([]);

const groupOptionsGrouped = computed(() => {
  const map = new Map<string, { categoryCode: string; categoryName: string; options: DipGroupOption[] }>();
  groupOptions.value.forEach(option => {
    const entry = map.get(option.categoryCode) ?? { categoryCode: option.categoryCode, categoryName: option.categoryName, options: [] };
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

const openGroupDialog = async (row: Row) => {
  groupTarget.value = row;
  groupInput.value = row.groupCode || "";
  groupVisible.value = true;
  if (!groupOptions.value.length) {
    try {
      groupOptions.value = await getDipGroupsApi();
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : "病组加载失败");
    }
  }
};

const submitGroup = async () => {
  const target = groupTarget.value;
  if (!target) return;
  savingGroup.value = true;
  try {
    await saveDipGroupApi({ patientId: target.patientId, groupCode: groupInput.value });
    groupVisible.value = false;
    await reload();
    ElMessage.success(groupInput.value ? "病组已更新" : "已恢复自动匹配");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存失败");
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

  padding: 18px 20px 28px;
  display: flex;
  flex-direction: column;
  gap: 14px;

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

  .head-actions {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  /* 四个统计 chip，可点击筛选 */
  .chips {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }

  .chip {
    display: inline-flex;
    align-items: baseline;
    gap: 5px;
    padding: 5px 12px;
    border: 1px solid var(--el-border-color);
    border-radius: 999px;
    background: var(--el-bg-color);
    cursor: pointer;
    transition: all 0.15s;

    b {
      font-size: 15px;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
      color: var(--el-text-color-primary);
    }

    span {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }

    &.zero {
      opacity: 0.45;
    }

    &.is-danger.active,
    &.is-danger:not(.zero):hover {
      border-color: var(--danger);
      background: var(--el-color-danger-light-9);

      b {
        color: var(--danger);
      }
    }

    &.is-warning.active,
    &.is-warning:not(.zero):hover {
      border-color: var(--warn);
      background: var(--el-color-warning-light-9);

      b {
        color: var(--warn);
      }
    }

    &.is-normal.active,
    &.is-normal:not(.zero):hover {
      border-color: var(--ok);
      background: var(--el-color-success-light-9);

      b {
        color: var(--ok);
      }
    }

    &.is-pending.active,
    &.is-unmatched.active {
      border-color: var(--el-text-color-secondary);
      background: var(--el-fill-color-light);
    }
  }

  .toolbar {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .search {
    width: 250px;
  }

  .filter-note {
    font-size: 12px;
    color: var(--el-text-color-secondary);

    button {
      margin-left: 6px;
      border: none;
      background: none;
      padding: 0;
      color: var(--el-color-primary);
      cursor: pointer;
      font-size: 12px;
    }
  }

  /* 表格：紧凑、去噪 */
  :deep(.el-table) {
    font-size: 13px;

    .el-table__cell {
      padding: 7px 0;
    }

    .cell {
      padding: 0 8px;
      line-height: 1.4;
    }
  }

  .cell-patient,
  .cell-group,
  .cell-fee {
    display: flex;
    flex-direction: column;
    gap: 1px;
  }

  .cell-patient strong {
    font-size: 13.5px;
    font-weight: 600;
  }

  .cell-sub {
    font-size: 11.5px;
    color: var(--el-text-color-secondary);

    em {
      font-style: normal;
      color: var(--el-text-color-placeholder);
      margin: 0 2px;
    }

    &.is-muted {
      color: var(--el-text-color-placeholder);
    }
  }

  .cell-group-top {
    display: flex;
    align-items: baseline;
    gap: 6px;
  }

  .cell-group-name {
    font-size: 12.5px;
    color: var(--el-text-color-regular);
  }

  .tag-manual {
    font-style: normal;
    font-size: 10.5px;
    padding: 0 4px;
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

  .cell-fee {
    align-items: flex-end;

    .link {
      font-size: 11px;
    }
  }

  .cell-fee-main {
    font-size: 14px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;

    .is-normal & {
      color: var(--el-text-color-primary);
    }

    .is-warning & {
      color: var(--warn);
    }

    .is-danger & {
      color: var(--danger);
      font-size: 15px;
    }

    .is-pending &,
    .is-unmatched & {
      color: var(--el-text-color-placeholder);
      font-size: 12px;
      font-weight: 400;
    }
  }

  .cell-water {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }

  .water-top {
    display: flex;
    align-items: center;
    gap: 6px;

    b {
      font-size: 14px;
      font-weight: 700;
      font-variant-numeric: tabular-nums;

      .is-normal & {
        color: var(--el-text-color-secondary);
      }

      .is-warning & {
        color: var(--warn);
      }

      .is-danger & {
        color: var(--danger);
        font-size: 15px;
      }
    }
  }

  .water-flag {
    font-size: 10px;
    padding: 0 4px;
    border-radius: 2px;
    color: #fff;

    .is-warning & {
      background: var(--warn);
    }

    .is-danger & {
      background: var(--danger);
    }
  }

  .water-bar {
    position: relative;
    height: 5px;
    border-radius: 3px;
    background: var(--el-fill-color-darker);
    overflow: hidden;
  }

  .water-fill {
    position: absolute;
    inset: 0 auto 0 0;
    background: var(--el-text-color-placeholder);

    .is-normal & {
      background: var(--ok);
    }

    .is-warning & {
      background: var(--warn);
    }

    .is-danger & {
      background: var(--danger);
    }
  }

  .water-tick {
    position: absolute;
    top: 0;
    bottom: 0;
    width: 1px;
    background: var(--el-bg-color);
    opacity: 0.85;
  }

  /* 行级强调只给异常 */
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

  /* 弹窗 */
  .dialog {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .dialog-title {
    margin: 0 0 6px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .preview {
    display: flex;
    align-items: baseline;
    gap: 8px;

    b {
      font-size: 17px;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }

    span {
      font-size: 11.5px;
      color: var(--el-text-color-secondary);
    }

    &.is-normal b {
      color: var(--ok);
    }

    &.is-warning b {
      color: var(--warn);
    }

    &.is-danger b {
      color: var(--danger);
    }
  }

  .more {
    margin-top: 4px;
    border-top: 1px solid var(--el-border-color-lighter);
    border-bottom: none;
  }

  .full {
    width: 100%;
  }

  .group-picked {
    margin: 2px 0 0;
    font-size: 12px;
    color: var(--el-text-color-secondary);

    b {
      color: var(--el-text-color-primary);
      font-variant-numeric: tabular-nums;
    }

    b.is-normal {
      color: var(--ok);
    }

    b.is-warning {
      color: var(--warn);
    }

    b.is-danger {
      color: var(--danger);
    }
  }
}
</style>
