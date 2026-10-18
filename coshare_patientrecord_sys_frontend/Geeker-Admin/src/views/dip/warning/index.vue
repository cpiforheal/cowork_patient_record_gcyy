<template>
  <div class="dip">
    <header class="dip-head">
      <div class="dip-head-main">
        <h2>DIP 费用预警</h2>
        <p>
          病组由医生确定的<span class="dip-em">主诊断</span>匹配本标准费用表得出；当前费用取自 HIS（系统无法直连，由截图识别或人工录入）。
          水位 ≥ <b class="is-warn">85%</b> 预警，≥ <b class="is-danger">95%</b> 需停止计费办理出院。
        </p>
      </div>
      <div class="dip-head-actions">
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
        <el-button :icon="EditPen" type="primary" @click="openFeeDialog()">录入当前费用</el-button>
      </div>
    </header>

    <!-- 超限告警横幅：页面最显眼的预警强调 -->
    <section v-if="dangerRows.length" class="dip-alarm">
      <div class="dip-alarm-main">
        <span class="dip-alarm-count">{{ dangerRows.length }}</span>
        <div class="dip-alarm-text">
          <strong>位患者已达 95% 超限线，应停止计费并办理出院</strong>
          <span>
            合计已超阈值
            <b>{{ money(overrunAmount) }}</b>
            <template v-if="dangerRows[0]">· 最高 {{ dangerRows[0].patientName }} {{ dangerRows[0].ratio.toFixed(1) }}%</template>
          </span>
        </div>
      </div>
      <el-button class="dip-alarm-btn" @click="focusDanger">只看超限患者</el-button>
    </section>

    <section class="dip-metrics">
      <article class="dip-metric">
        <span>在院患者</span>
        <strong>{{ summary.total }}</strong>
      </article>
      <article class="dip-metric is-ok">
        <span>正常 &lt;85%</span>
        <strong>{{ summary.normal }}</strong>
      </article>
      <article class="dip-metric is-warn" :class="{ 'is-hot': summary.warning > 0 }">
        <span>预警 85%~95%</span>
        <strong>{{ summary.warning }}</strong>
      </article>
      <article class="dip-metric is-danger" :class="{ 'is-critical': summary.danger > 0 }">
        <span>超限 ≥95%</span>
        <strong>{{ summary.danger }}</strong>
      </article>
      <article class="dip-metric is-pending">
        <span>待录费用</span>
        <strong>{{ pendingCount }}</strong>
      </article>
      <article class="dip-metric is-unmatched">
        <span>病组未匹配</span>
        <strong>{{ unmatchedCount }}</strong>
      </article>
    </section>

    <el-alert
      v-if="summary.unmatched"
      class="dip-coverage"
      type="warning"
      :closable="false"
      show-icon
      :title="`有 ${summary.unmatched} 位患者的主诊断不在标准病组表内，无法预估费用`"
    >
      <div class="dip-coverage-list">
        未覆盖主诊断：
        <el-tag v-for="item in unmatchedDiagnoses" :key="item.diagnosis" size="small" type="warning" effect="plain">
          {{ item.diagnosis }}（{{ item.count }}）
        </el-tag>
      </div>
    </el-alert>

    <section class="dip-toolbar">
      <el-input v-model="keyword" class="dip-search" placeholder="搜索姓名 / 主诊断 / 病组" clearable :prefix-icon="Search" />
      <el-radio-group v-model="levelFilter" size="small">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="danger">超限</el-radio-button>
        <el-radio-button value="warning">预警</el-radio-button>
        <el-radio-button value="pending">待录费用</el-radio-button>
        <el-radio-button value="unmatched">未匹配</el-radio-button>
      </el-radio-group>
      <div class="dip-toolbar-right">
        <el-switch v-model="toggleDemo" inline-prompt active-text="演示水位" inactive-text="真实数据" />
        <span class="dip-toolbar-hint">演示水位仅在前端预览，不写入数据库</span>
      </div>
    </section>

    <el-table
      v-loading="loading"
      :data="visibleRows"
      row-key="patientId"
      class="dip-table"
      :row-class-name="rowClass"
      :default-sort="{ prop: 'ratio', order: 'descending' }"
      empty-text="暂无患者数据"
    >
      <el-table-column label="患者" width="132">
        <template #default="{ row }">
          <div class="dip-patient">
            <strong>{{ row.patientName }}</strong>
            <small>{{ row.doctor || "未指定医生" }}</small>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="主诊断 / 匹配病组" min-width="248">
        <template #default="{ row }">
          <div class="dip-diag">
            <el-tag size="small" effect="dark" type="info">{{ row.diagnosis || "—" }}</el-tag>
            <template v-if="row.matched">
              <span class="dip-group-code">{{ row.groupCode }}</span>
              <span class="dip-group-name">{{ row.groupName }}</span>
            </template>
            <span v-else class="dip-group-none">标准病组表未覆盖此主诊断</span>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="预估费用" width="112" align="right">
        <template #default="{ row }">
          <span class="dip-num">{{ row.matched ? money(row.estimatedFee) : "—" }}</span>
        </template>
      </el-table-column>

      <el-table-column label="当前费用" width="118" align="right">
        <template #default="{ row }">
          <span v-if="row.displayFee !== null" class="dip-num is-strong">{{ money(row.displayFee) }}</span>
          <span v-else class="dip-none">待录入</span>
        </template>
      </el-table-column>

      <el-table-column label="预警阈值 85% / 95%" width="168" align="right">
        <template #default="{ row }">
          <template v-if="row.matched">
            <div class="dip-threshold" :class="{ 'is-cross-warn': row.ratio >= 85, 'is-cross-danger': row.ratio >= 95 }">
              <b>{{ money(row.threshold85) }}</b>
              <i>/</i>
              <b>{{ money(row.threshold95) }}</b>
            </div>
          </template>
          <span v-else class="dip-none">—</span>
        </template>
      </el-table-column>

      <el-table-column label="费用水位" width="260">
        <template #default="{ row }">
          <div v-if="row.matched" class="dip-waterline">
            <div class="dip-bar">
              <span class="dip-bar-zone is-ok" style="width: 85%" />
              <span class="dip-bar-zone is-warn" style="width: 10%" />
              <span class="dip-bar-zone is-danger" style="width: 5%" />
              <span
                v-if="row.displayFee !== null"
                class="dip-bar-fill"
                :class="`is-${row.level}`"
                :style="{ width: `${clamp(row.ratio)}%` }"
              />
              <span class="dip-bar-tick is-warn" style="left: 85%" />
              <span class="dip-bar-tick is-danger" style="left: 95%" />
            </div>
            <div class="dip-bar-legend">
              <span class="is-warn">85%</span>
              <span class="is-danger">95%</span>
            </div>
            <div class="dip-ratio" :class="`is-${row.level}`">
              <template v-if="row.displayFee !== null">
                <b>{{ row.ratio.toFixed(1) }}%</b>
                <em v-if="row.overTo95 > 0">超阈值 {{ money(row.overTo95) }}</em>
                <em v-else-if="row.restTo95 !== null">距 95% 还差 {{ money(Math.max(row.restTo95, 0)) }}</em>
              </template>
              <em v-else>尚未录入费用</em>
            </div>
          </div>
          <span v-else class="dip-none">—</span>
        </template>
      </el-table-column>

      <el-table-column label="状态" width="104">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.level)" effect="dark" size="small" class="dip-status">
            {{ statusText(row.level) }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="费用更新" width="150">
        <template #default="{ row }">
          <span class="dip-updated">
            {{ row.demoFee ? "演示预览" : row.feeUpdatedAt || "—" }}
            <small v-if="!row.demoFee && row.feeSource">{{ row.feeSource === "SCREENSHOT" ? "截图识别" : "手工录入" }}</small>
          </span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="128" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openFeeDialog(row)">录费用</el-button>
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 录入当前费用 -->
    <el-dialog v-model="feeDialogVisible" title="录入 HIS 当前费用" width="470px">
      <el-form v-if="feeTarget" label-width="104px">
        <el-form-item label="患者">
          <span>{{ feeTarget.patientName }} · {{ feeTarget.diagnosis }}</span>
        </el-form-item>
        <el-form-item label="匹配病组">
          <span v-if="feeTarget.matched">{{ feeTarget.groupCode }} {{ feeTarget.groupName }}</span>
          <span v-else class="dip-none">该主诊断未匹配到病组，需先维护映射表</span>
        </el-form-item>
        <el-form-item label="当前费用">
          <el-input-number v-model="feeInput" :min="0" :precision="2" :step="100" controls-position="right" />
        </el-form-item>
        <el-form-item v-if="feeTarget.matched" label="录入后水位">
          <div class="dip-fee-preview" :class="`is-${previewLevel}`">
            <b>{{ previewRatio.toFixed(1) }}%</b>
            <span>85% = {{ money(feeTarget.threshold85) }} / 95% = {{ money(feeTarget.threshold95) }}</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="feeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!feeTarget?.matched" @click="submitFee">保存</el-button>
      </template>
    </el-dialog>

    <!-- 费用水位详情 -->
    <el-drawer v-model="detailVisible" :title="detailTarget ? `${detailTarget.patientName} · 费用水位` : '费用水位'" size="440px">
      <div v-if="detailTarget" class="dip-detail">
        <div class="dip-detail-hero" :class="`is-${detailTarget.level}`">
          <span class="dip-detail-ratio">{{ detailTarget.displayFee === null ? "—" : `${detailTarget.ratio.toFixed(1)}%` }}</span>
          <span class="dip-detail-label">{{ statusText(detailTarget.level) }}</span>
        </div>

        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="主诊断">{{ detailTarget.diagnosis }}</el-descriptions-item>
          <el-descriptions-item label="病组">
            <template v-if="detailTarget.matched">{{ detailTarget.groupCode }} · {{ detailTarget.groupName }}</template>
            <span v-else class="dip-none">未匹配</span>
          </el-descriptions-item>
          <el-descriptions-item label="预估费用">{{ detailTarget.matched ? money(detailTarget.estimatedFee) : "—" }}</el-descriptions-item>
          <el-descriptions-item label="当前费用">
            {{ detailTarget.displayFee === null ? "待录入" : money(detailTarget.displayFee) }}
          </el-descriptions-item>
          <el-descriptions-item label="85% 阈值">{{ detailTarget.matched ? money(detailTarget.threshold85) : "—" }}</el-descriptions-item>
          <el-descriptions-item label="95% 阈值">{{ detailTarget.matched ? money(detailTarget.threshold95) : "—" }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="detailTarget.matched" class="dip-detail-bar">
          <div class="dip-bar is-tall">
            <span class="dip-bar-zone is-ok" style="width: 85%" />
            <span class="dip-bar-zone is-warn" style="width: 10%" />
            <span class="dip-bar-zone is-danger" style="width: 5%" />
            <span
              v-if="detailTarget.displayFee !== null"
              class="dip-bar-fill"
              :class="`is-${detailTarget.level}`"
              :style="{ width: `${clamp(detailTarget.ratio)}%` }"
            />
            <span class="dip-bar-tick is-warn" style="left: 85%" />
            <span class="dip-bar-tick is-danger" style="left: 95%" />
          </div>
          <div class="dip-detail-scale">
            <span>¥0</span>
            <span>{{ money(detailTarget.threshold85) }}</span>
            <span>{{ money(detailTarget.threshold95) }}</span>
            <span>{{ money(detailTarget.estimatedFee) }}</span>
          </div>
        </div>

        <el-alert
          v-if="detailTarget.level === 'danger'"
          type="error"
          :closable="false"
          show-icon
          title="已达 95% 超限线"
          description="如无特殊情况应停止计费并办理出院，继续产生费用将超出病组支付标准。"
        />
        <el-alert
          v-else-if="detailTarget.level === 'warning'"
          type="warning"
          :closable="false"
          show-icon
          title="已达 85% 预警线"
          description="请关注后续费用增长，提前核对诊疗项目并安排出院时间。"
        />
        <el-alert
          v-else-if="detailTarget.level === 'pending'"
          type="info"
          :closable="false"
          show-icon
          title="尚未录入当前费用"
          description="从 HIS 截图或手工录入该患者未结算费用后，即可计算费用水位。"
        />
        <el-alert v-else-if="detailTarget.level === 'unmatched'" type="info" :closable="false" show-icon title="病组未匹配" />
        <el-alert v-else type="success" :closable="false" show-icon title="费用水位正常" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts" name="dipWarning">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { EditPen, Refresh, Search } from "@element-plus/icons-vue";
import { getDipBoardApi, saveDipFeeApi, type DipPatientRow } from "@/api/modules/clinic/dipWarning";

type Level = "normal" | "warning" | "danger" | "pending" | "unmatched";

interface ViewRow {
  patientId: string;
  patientName: string;
  doctor: string;
  diagnosis: string;
  matched: boolean;
  groupCode: string;
  groupName: string;
  estimatedFee: number;
  threshold85: number;
  threshold95: number;
  displayFee: number | null;
  ratio: number;
  level: Level;
  overTo95: number;
  restTo95: number | null;
  feeSource: string;
  feeUpdatedAt: string;
  demoFee: boolean;
}

const loading = ref(false);
const saving = ref(false);
const keyword = ref("");
const levelFilter = ref<"all" | Level>("all");
const demoMode = ref(false);

const rawRows = ref<DipPatientRow[]>([]);
/** 演示水位：仅前端覆盖，不写库 */
const demoFees = ref<Record<string, number>>({});

const money = (value: number | null | undefined) => (value === null || value === undefined ? "—" : `¥${Number(value).toFixed(2)}`);
const clamp = (ratio: number) => Math.max(0, Math.min(ratio, 100));

const levelOf = (ratio: number): Level => (ratio >= 95 ? "danger" : ratio >= 85 ? "warning" : "normal");

const viewRows = computed<ViewRow[]>(() => {
  const list = rawRows.value.map(row => {
    const matched = row.matched;
    const estimatedFee = Number(row.estimatedFee ?? 0);
    const baseFee = row.currentFee === undefined || row.currentFee === null ? null : Number(row.currentFee);
    const demoFee = demoFees.value[row.patientId];
    const displayFee = demoFee !== undefined ? demoFee : baseFee;
    const ratio = matched && estimatedFee > 0 && displayFee !== null ? (displayFee / estimatedFee) * 100 : 0;
    const level: Level = !matched ? "unmatched" : displayFee === null ? "pending" : levelOf(ratio);
    return {
      patientId: row.patientId,
      patientName: row.patientName,
      doctor: row.doctor ?? "",
      diagnosis: row.diagnosis ?? "",
      matched,
      groupCode: row.groupCode ?? "",
      groupName: row.groupName ?? "",
      estimatedFee,
      threshold85: Number(row.threshold85 ?? 0),
      threshold95: Number(row.threshold95 ?? 0),
      displayFee,
      ratio,
      level,
      overTo95: matched && displayFee !== null ? Math.max(displayFee - Number(row.threshold95 ?? 0), 0) : 0,
      restTo95: row.restTo95 === undefined || row.restTo95 === null ? null : Number(row.restTo95),
      feeSource: row.feeSource ?? "",
      feeUpdatedAt: row.feeUpdatedAt ?? "",
      demoFee: demoFee !== undefined
    };
  });
  return list;
});

const dangerRows = computed(() => viewRows.value.filter(row => row.level === "danger").sort((a, b) => b.ratio - a.ratio));
const warningRows = computed(() => viewRows.value.filter(row => row.level === "warning"));

const liveSummary = computed(() => {
  const rows = viewRows.value;
  return {
    total: rows.length,
    normal: rows.filter(row => row.level === "normal").length,
    warning: warningRows.value.length,
    danger: dangerRows.value.length,
    pendingFee: rows.filter(row => row.level === "pending").length,
    unmatched: rows.filter(row => row.level === "unmatched").length
  };
});

/** 汇总直接取实时视图（含演示水位），不依赖后端汇总，避免两者不一致 */
const summary = liveSummary;

const pendingCount = computed(() => liveSummary.value.pendingFee);
const unmatchedCount = computed(() => liveSummary.value.unmatched);
const overrunAmount = computed(() => dangerRows.value.reduce((sum, row) => sum + row.overTo95, 0));

const unmatchedDiagnoses = computed(() => {
  const map = new Map<string, number>();
  viewRows.value.filter(row => !row.matched).forEach(row => map.set(row.diagnosis, (map.get(row.diagnosis) ?? 0) + 1));
  return [...map.entries()].map(([diagnosis, count]) => ({ diagnosis, count })).sort((a, b) => b.count - a.count);
});

const rankOf = (level: Level) => (level === "danger" ? 0 : level === "warning" ? 1 : level === "normal" ? 2 : level === "pending" ? 3 : 4);

const visibleRows = computed(() => {
  const key = keyword.value.trim().toLowerCase();
  const filtered = viewRows.value.filter(row => {
    if (levelFilter.value !== "all" && row.level !== levelFilter.value) return false;
    if (!key) return true;
    return (
      row.patientName.toLowerCase().includes(key) ||
      row.diagnosis.toLowerCase().includes(key) ||
      row.groupCode.toLowerCase().includes(key) ||
      row.groupName.toLowerCase().includes(key)
    );
  });
  return [...filtered].sort((a, b) => {
    const rank = rankOf(a.level) - rankOf(b.level);
    if (rank !== 0) return rank;
    return b.ratio - a.ratio;
  });
});

const statusText = (level: Level) =>
  level === "danger" ? "超限" : level === "warning" ? "预警" : level === "normal" ? "正常" : level === "pending" ? "待录费用" : "未匹配";
const statusTagType = (level: Level) =>
  level === "danger" ? "danger" : level === "warning" ? "warning" : level === "normal" ? "success" : "info";

const rowClass = ({ row }: { row: ViewRow }) =>
  row.level === "danger" ? "dip-row-danger" : row.level === "warning" ? "dip-row-warning" : row.level === "pending" ? "dip-row-pending" : "";

const focusDanger = () => {
  levelFilter.value = "danger";
};

const reload = async () => {
  loading.value = true;
  try {
    const board = await getDipBoardApi();
    rawRows.value = board.patients ?? [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "费用预警数据加载失败");
  } finally {
    loading.value = false;
  }
};

/** 演示水位：按固定比例生成，仅用于预览预警视觉，不写库 */
const buildDemoFees = () => {
  const targets = viewRows.value.filter(row => row.matched);
  const ratios = [101.4, 97.6, 93.2, 88.5, 82.1, 74.3, 66.8, 59.2, 51.7, 44.3, 38.6, 31.5];
  const next: Record<string, number> = {};
  targets.forEach((row, index) => {
    const ratio = ratios[index % ratios.length];
    next[row.patientId] = Number(((row.estimatedFee * ratio) / 100).toFixed(2));
  });
  demoFees.value = next;
};

const toggleDemo = computed({
  get: () => demoMode.value,
  set: (value: boolean) => {
    demoMode.value = value;
    if (value) {
      buildDemoFees();
      ElMessage.success("已开启演示水位（仅前端预览，不写入数据库）");
    } else {
      demoFees.value = {};
    }
  }
});

/* ---------------- 录入费用 ---------------- */
const feeDialogVisible = ref(false);
const feeTarget = ref<ViewRow | null>(null);
const feeInput = ref(0);

const openFeeDialog = (row?: ViewRow) => {
  const target = row ?? visibleRows.value.find(item => item.matched) ?? viewRows.value.find(item => item.matched) ?? null;
  if (!target) {
    ElMessage.warning("没有可录入费用的患者");
    return;
  }
  feeTarget.value = target;
  feeInput.value = target.displayFee ?? 0;
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
  saving.value = true;
  try {
    await saveDipFeeApi({ patientId: target.patientId, currentFee: feeInput.value, source: "MANUAL" });
    feeDialogVisible.value = false;
    delete demoFees.value[target.patientId];
    await reload();
    ElMessage.success(`${target.patientName} 费用已更新`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "费用保存失败");
  } finally {
    saving.value = false;
  }
};

/* ---------------- 详情 ---------------- */
const detailVisible = ref(false);
const detailTarget = ref<ViewRow | null>(null);
const openDetail = (row: ViewRow) => {
  detailTarget.value = row;
  detailVisible.value = true;
};

onMounted(reload);
</script>

<style scoped lang="scss">
.dip {
  --dip-ok: var(--el-color-success);
  --dip-warn: var(--el-color-warning);
  --dip-danger: var(--el-color-danger);

  padding: 18px 20px 32px;
  display: flex;
  flex-direction: column;
  gap: 14px;

  .dip-head {
    display: flex;
    justify-content: space-between;
    gap: 16px;
    flex-wrap: wrap;

    h2 {
      margin: 0 0 4px;
      font-size: 20px;
      font-weight: 600;
    }

    p {
      margin: 0;
      max-width: 900px;
      font-size: 13px;
      line-height: 1.6;
      color: var(--el-text-color-secondary);
    }
  }

  .dip-head-actions {
    display: flex;
    gap: 8px;
    align-items: flex-start;
  }

  .dip-em {
    color: var(--el-color-primary);
    font-weight: 600;
  }

  .is-warn {
    color: var(--dip-warn);
  }

  .is-danger {
    color: var(--dip-danger);
  }

  /* 超限横幅 */
  .dip-alarm {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 14px 18px;
    border: 1px solid var(--dip-danger);
    border-left: 6px solid var(--dip-danger);
    border-radius: 10px;
    background: linear-gradient(90deg, rgba(245, 108, 108, 0.16), rgba(245, 108, 108, 0.06));
    animation: dip-alarm-in 0.4s ease-out;
  }

  .dip-alarm-main {
    display: flex;
    align-items: center;
    gap: 14px;
  }

  .dip-alarm-count {
    font-size: 32px;
    font-weight: 800;
    line-height: 1;
    color: var(--dip-danger);
    font-variant-numeric: tabular-nums;
  }

  .dip-alarm-text {
    display: flex;
    flex-direction: column;
    gap: 2px;

    strong {
      font-size: 15px;
      color: var(--dip-danger);
    }

    span {
      font-size: 12px;
      color: var(--el-text-color-regular);
    }

    b {
      color: var(--dip-danger);
    }
  }

  .dip-alarm-btn {
    border-color: var(--dip-danger);
    color: var(--dip-danger);
  }

  /* 指标卡 */
  .dip-metrics {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
  }

  .dip-metric {
    flex: 1 1 132px;
    padding: 10px 14px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
    background: var(--el-bg-color);

    span {
      display: block;
      font-size: 12px;
      color: var(--el-text-color-secondary);
      margin-bottom: 4px;
    }

    strong {
      font-size: 22px;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }

    &.is-ok strong {
      color: var(--dip-ok);
    }

    &.is-warn strong {
      color: var(--dip-warn);
    }

    &.is-danger strong {
      color: var(--dip-danger);
    }

    &.is-pending strong,
    &.is-unmatched strong {
      color: var(--el-text-color-secondary);
    }

    &.is-hot {
      border-color: var(--el-color-warning-light-3);
      background: var(--el-color-warning-light-9);
    }

    &.is-critical {
      border-color: var(--dip-danger);
      background: var(--el-color-danger-light-9);
      animation: dip-pulse 1.8s ease-in-out infinite;
    }
  }

  .dip-coverage {
    :deep(.el-alert__content) {
      width: 100%;
    }
  }

  .dip-coverage-list {
    margin-top: 6px;
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: center;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .dip-toolbar {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  .dip-search {
    width: 260px;
  }

  .dip-toolbar-right {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .dip-toolbar-hint {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  /* 表格 */
  .dip-patient {
    display: flex;
    flex-direction: column;

    strong {
      font-size: 14px;
    }

    small {
      font-size: 11px;
      color: var(--el-text-color-secondary);
    }
  }

  .dip-diag {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px 6px;

    .dip-group-code {
      font-size: 11px;
      color: var(--el-color-primary);
    }

    .dip-group-name {
      font-size: 12px;
      color: var(--el-text-color-regular);
    }

    .dip-group-none {
      font-size: 12px;
      color: var(--el-text-color-placeholder);
    }
  }

  .dip-num {
    font-variant-numeric: tabular-nums;

    &.is-strong {
      font-weight: 700;
    }
  }

  .dip-none {
    color: var(--el-text-color-placeholder);
    font-size: 12px;
  }

  .dip-threshold {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-variant-numeric: tabular-nums;
    font-size: 12px;
    color: var(--el-text-color-regular);

    b {
      font-weight: 600;
    }

    i {
      color: var(--el-text-color-placeholder);
      font-style: normal;
    }

    &.is-cross-warn b:first-child {
      color: var(--dip-warn);
    }

    &.is-cross-danger b {
      color: var(--dip-danger);
    }

    &.is-cross-danger b:last-child {
      background: var(--el-color-danger-light-8);
      border-radius: 3px;
      padding: 0 3px;
    }
  }

  /* 水位条 */
  .dip-waterline {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }

  .dip-bar {
    position: relative;
    display: flex;
    height: 10px;
    border-radius: 6px;
    overflow: hidden;
    background: var(--el-fill-color-light);

    &.is-tall {
      height: 18px;
      border-radius: 9px;
    }
  }

  .dip-bar-zone {
    height: 100%;

    &.is-ok {
      background: var(--el-color-success-light-7);
    }

    &.is-warn {
      background: var(--el-color-warning-light-7);
    }

    &.is-danger {
      background: var(--el-color-danger-light-7);
    }
  }

  .dip-bar-fill {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0;
    border-radius: 6px 0 0 6px;
    transition: width 0.3s ease;

    &.is-normal {
      background: var(--dip-ok);
    }

    &.is-warning {
      background: var(--dip-warn);
    }

    &.is-danger {
      background: var(--dip-danger);
    }
  }

  .dip-bar-tick {
    position: absolute;
    top: 0;
    bottom: 0;
    width: 2px;

    &.is-warn {
      background: var(--el-color-warning-dark-2);
    }

    &.is-danger {
      background: var(--el-color-danger-dark-2);
    }
  }

  .dip-bar-legend {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    font-size: 10px;
    color: var(--el-text-color-placeholder);

    span {
      &.is-warn {
        color: var(--dip-warn);
      }

      &.is-danger {
        color: var(--dip-danger);
      }
    }
  }

  .dip-ratio {
    display: flex;
    align-items: baseline;
    gap: 6px;
    font-variant-numeric: tabular-nums;

    b {
      font-size: 15px;
      font-weight: 800;
    }

    em {
      font-style: normal;
      font-size: 11px;
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
      font-size: 16px;
    }

    &.is-danger em {
      color: var(--dip-danger);
    }
  }

  .dip-status {
    font-weight: 700;
    letter-spacing: 1px;
  }

  .dip-updated {
    display: flex;
    flex-direction: column;
    font-size: 12px;
    color: var(--el-text-color-secondary);

    small {
      font-size: 10px;
      color: var(--el-text-color-placeholder);
    }
  }

  /* 行强调 */
  :deep(.dip-row-danger) {
    background: var(--el-color-danger-light-9) !important;

    td:first-child {
      box-shadow: inset 4px 0 0 var(--dip-danger);
    }
  }

  :deep(.dip-row-warning) {
    background: var(--el-color-warning-light-9) !important;

    td:first-child {
      box-shadow: inset 4px 0 0 var(--dip-warn);
    }
  }

  :deep(.dip-row-pending) {
    td:first-child {
      box-shadow: inset 4px 0 0 var(--el-border-color);
    }
  }

  /* 录入预览 */
  .dip-fee-preview {
    display: flex;
    align-items: baseline;
    gap: 8px;

    b {
      font-size: 20px;
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

  /* 详情 */
  .dip-detail {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .dip-detail-hero {
    display: flex;
    align-items: baseline;
    gap: 10px;
    padding: 14px 16px;
    border-radius: 10px;
    background: var(--el-fill-color-light);

    &.is-danger {
      background: var(--el-color-danger-light-9);
      border-left: 5px solid var(--dip-danger);
    }

    &.is-warning {
      background: var(--el-color-warning-light-9);
      border-left: 5px solid var(--dip-warn);
    }

    &.is-normal {
      background: var(--el-color-success-light-9);
      border-left: 5px solid var(--dip-ok);
    }
  }

  .dip-detail-ratio {
    font-size: 34px;
    font-weight: 800;
    line-height: 1;
    font-variant-numeric: tabular-nums;

    .is-danger & {
      color: var(--dip-danger);
    }

    .is-warning & {
      color: var(--dip-warn);
    }

    .is-normal & {
      color: var(--dip-ok);
    }
  }

  .dip-detail-label {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .dip-detail-bar {
    padding: 4px 2px;

    .dip-detail-scale {
      display: flex;
      justify-content: space-between;
      margin-top: 6px;
      font-size: 11px;
      color: var(--el-text-color-secondary);
      font-variant-numeric: tabular-nums;
    }
  }
}

@keyframes dip-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(245, 108, 108, 0);
  }

  50% {
    box-shadow: 0 0 0 3px rgba(245, 108, 108, 0.16);
  }
}

@keyframes dip-alarm-in {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
