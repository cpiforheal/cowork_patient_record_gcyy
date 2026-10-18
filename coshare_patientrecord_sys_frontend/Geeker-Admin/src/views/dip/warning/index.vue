<template>
  <div class="dip">
    <header class="head">
      <div class="head-main">
        <h2>DIP 费用预警</h2>
        <p>按病组预估费用监控未结算费用 · 达 <b class="t-warn">85%</b> 预警、<b class="t-danger">95%</b> 超限</p>
      </div>
      <div class="head-side">
        <span class="scope">可见 {{ allRows.length }} 例 · 待处理 {{ pendingCount }} 例</span>
        <el-button size="small" @click="ocrVisible = true">导入 HIS 费用截图</el-button>
      </div>
    </header>

    <!-- 聚合指标：始终跟随当前筛选结果 -->
    <nav class="metrics">
      <div class="metric">
        <span>当前结果</span>
        <strong>{{ filtered.length }}</strong>
      </div>
      <div class="metric is-danger" :class="{ muted: stats.danger === 0 }">
        <span>超限（≥95%）</span>
        <strong>{{ stats.danger }}</strong>
      </div>
      <div class="metric is-warning" :class="{ muted: stats.warning === 0 }">
        <span>预警（85%~95%）</span>
        <strong>{{ stats.warning }}</strong>
      </div>
      <div class="metric">
        <span>待录入 / 未匹配</span>
        <strong>{{ stats.pending + stats.unmatched }}</strong>
      </div>
      <div class="metric">
        <span>未结算合计</span>
        <strong>{{ money(stats.feeTotal) }}</strong>
      </div>
      <div class="metric" :class="{ 'is-danger': stats.overrunTotal > 0 }">
        <span>超支合计</span>
        <strong>{{ money(stats.overrunTotal) }}</strong>
      </div>
    </nav>

    <ProTable
      v-if="loaded"
      ref="proTable"
      :columns="columns"
      :request-api="tableRequest"
      row-key="patientId"
      :search-col="{ xs: 1, sm: 2, md: 2, lg: 3, xl: 4 }"
    >
      <template #tableHeader>
        <div class="table-hint">
          按风险、病组大类、管床医生或关键词组合筛选；列可在右上角设置中增减。
        </div>
      </template>

      <!-- 患者 -->
      <template #patient="{ row }">
        <div class="cell-patient">
          <strong>{{ row.name }}</strong>
          <span class="sub">{{ patientMeta(row) }}</span>
          <span class="diag">{{ row.diagnosis || "—" }}</span>
        </div>
      </template>

      <!-- 风险 -->
      <template #risk="{ row }">
        <el-tag :type="tagType(row.level)" size="small" :effect="row.level === 'normal' ? 'plain' : 'light'">
          {{ levelText(row.level) }}
        </el-tag>
      </template>

      <!-- 病组 -->
      <template #group="{ row }">
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

      <!-- 主视觉：费用水位 -->
      <template #waterline="{ row }">
        <div v-if="row.level === 'pending' || row.level === 'unmatched'" class="water is-pending">
          <span class="water-none">未录入未结算费用</span>
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

      <!-- HIS 摘要 -->
      <template #his="{ row }">
        <div class="cell-his">
          <span>{{ blank(row.bedNo) }}<template v-if="row.nursingLevel"> · {{ row.nursingLevel }}</template></span>
          <span class="sub">押金 {{ money(row.depositBalance) }}</span>
          <span class="sub">{{ blank(row.attendingDoctor) }}</span>
        </div>
      </template>

      <!-- 处理 -->
      <template #operation="{ row }">
        <div class="cell-action">
          <el-button
            v-if="row.level === 'pending' || row.level === 'unmatched'"
            link
            type="primary"
            size="small"
            @click="openFeeDialog(row)"
          >
            录费用
          </el-button>
          <el-button v-else link type="primary" size="small" @click="openDetail(row)">详情</el-button>
        </div>
      </template>
    </ProTable>

    <!-- HIS 截图导入：只识别最小字段，确认后才写入费用 -->
    <el-dialog v-model="ocrVisible" title="导入 HIS 费用截图" width="720px">
      <div class="ocr">
        <el-upload
          drag
          :auto-upload="false"
          :show-file-list="false"
          accept="image/png,image/jpeg"
          :on-change="handleOcrFile"
        >
          <div class="ocr-upload">
            <p>拖入或点击选择 HIS 住院费用列表截图</p>
            <small>仅提取住院号、姓名、未结算费用、押金余额；截图不保存到病历附件。</small>
          </div>
        </el-upload>

        <div v-if="ocrLoading" class="ocr-status">正在识别，请稍候…</div>

        <template v-if="ocrItems.length">
          <div class="ocr-summary">
            识别 {{ ocrRecognized }} 条 · 已匹配 {{ ocrMatched }} 条 · 需确认 {{ ocrPending }} 条
          </div>
          <el-table :data="ocrItems" size="small" max-height="300" @selection-change="onOcrSelection">
            <el-table-column type="selection" width="42" :selectable="row => row.matchStatus === 'MATCHED'" />
            <el-table-column label="识别" width="150">
              <template #default="{ row }">
                <div class="ocr-cell">
                  <span>{{ row.ocrName || "—" }}</span>
                  <span class="sub">{{ row.ocrAdmissionNo || "无住院号" }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="匹配患者" width="150">
              <template #default="{ row }">
                <div class="ocr-cell">
                  <span>{{ row.patientName || "—" }}</span>
                  <span class="sub">{{ row.matchReason }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="未结算费用" width="120" align="right">
              <template #default="{ row }">{{ money(row.unsettledFee) }}</template>
            </el-table-column>
            <el-table-column label="原费用" width="110" align="right">
              <template #default="{ row }">{{ money(row.previousFee) }}</template>
            </el-table-column>
            <el-table-column label="识别后水位" width="110" align="right">
              <template #default="{ row }">
                <span v-if="row.waterlineRatio !== null">{{ row.waterlineRatio.toFixed(1) }}%</span>
                <span v-else class="sub">—</span>
              </template>
            </el-table-column>
            <el-table-column label="置信度" width="90" align="right">
              <template #default="{ row }">{{ Math.round(row.confidence * 100) }}%</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
      <template #footer>
        <el-button @click="ocrVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!ocrChecked.length" :loading="ocrWriting" @click="confirmOcr">
          写入选中 {{ ocrChecked.length }} 条
        </el-button>
      </template>
    </el-dialog>

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
            {{ detailTarget.updatedAt || "—"
            }}<span v-if="detailTarget.feeUpdatedBy" class="sub"> · {{ detailTarget.feeUpdatedBy }}</span>
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
import ProTable from "@/components/ProTable/index.vue";
import type { ColumnProps, ProTableInstance } from "@/components/ProTable/interface";
import {
  getDipBoardApi,
  getDipGroupsApi,
  parseDipScreenshotApi,
  saveDipFeeApi,
  saveDipGroupApi,
  type DipGroupOption,
  type DipOcrItem,
  type DipPatientRow
} from "@/api/modules/clinic/dipWarning";

type Level = "normal" | "warning" | "danger" | "pending" | "unmatched";

interface Row {
  patientId: string;
  name: string;
  gender: string;
  age: number | null;
  diagnosis: string;
  matched: boolean;
  dipCode: string;
  dipName: string;
  categoryName: string;
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

const proTable = ref<ProTableInstance>();
const loaded = ref(false);
const allRows = ref<Row[]>([]);
const filtered = ref<Row[]>([]);

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
const tagType = (level: Level) =>
  level === "danger" ? "danger" : level === "warning" ? "warning" : level === "pending" ? "info" : "success";

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
    categoryName: item.categoryName ?? "",
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

const levelOptions = [
  { label: "超限", value: "danger", tagType: "danger" },
  { label: "预警", value: "warning", tagType: "warning" },
  { label: "待录入", value: "pending", tagType: "info" },
  { label: "未匹配", value: "unmatched", tagType: "info" },
  { label: "正常", value: "normal", tagType: "success" }
];

const columns = reactive<ColumnProps<Row>[]>([]);

/** 保持同一数组实例，ProTable 内部持有该引用；重建时原地替换。 */
const buildColumns = () => {
  const categories = [...new Set(allRows.value.map(row => row.categoryName).filter(Boolean))].sort();
  const next: ColumnProps<Row>[] = [
    {
      prop: "patient",
      label: "患者",
      minWidth: 200,
      search: { el: "input", key: "keyword", order: 1, props: { placeholder: "姓名 / 住院号 / 诊断 / 病组" } }
    },
    {
      prop: "risk",
      label: "风险",
      width: 96,
      enum: levelOptions,
      isFilterEnum: false,
      search: { el: "select", key: "risk", order: 2, props: { placeholder: "风险状态" } }
    },
    {
      prop: "group",
      label: "DIP 病组",
      minWidth: 200,
      enum: categories.map(name => ({ label: name, value: name })),
      ...(categories.length
        ? { search: { el: "select" as const, key: "categoryName", order: 3, props: { placeholder: "病组大类" } } }
        : {})
    },
    { prop: "waterline", label: "费用水位", width: 250 },
    {
      prop: "his",
      label: "HIS 摘要",
      minWidth: 150,
      search: { el: "input", key: "attendingDoctor", order: 4, props: { placeholder: "管床医生" } }
    },
    { prop: "operation", label: "处理", width: 120, fixed: "right" }
  ];
  columns.splice(0, columns.length, ...next);
};

const matches = (row: Row, params: Record<string, any>) => {
  const keyword = String(params.keyword ?? "").trim().toLowerCase();
  if (keyword) {
    const hay = [row.name, row.admissionNo, row.diagnosis, row.dipCode, row.dipName].join(" ").toLowerCase();
    if (!hay.includes(keyword)) return false;
  }
  if (params.risk && row.level !== params.risk) return false;
  if (params.categoryName && row.categoryName !== params.categoryName) return false;
  const doctor = String(params.attendingDoctor ?? "").trim();
  if (doctor && !row.attendingDoctor.includes(doctor)) return false;
  return true;
};

const rankOf = (level: Level) =>
  level === "danger" ? 0 : level === "warning" ? 1 : level === "pending" ? 2 : level === "unmatched" ? 4 : 3;

const tableRequest = async (params: Record<string, any>) => {
  const list = allRows.value
    .filter(row => matches(row, params))
    .sort((a, b) => {
      const rank = rankOf(a.level) - rankOf(b.level);
      return rank !== 0 ? rank : b.ratio - a.ratio;
    });
  filtered.value = list;
  const pageNum = Number(params.pageNum ?? 1);
  const pageSize = Number(params.pageSize ?? 10);
  const start = (pageNum - 1) * pageSize;
  return { data: { list: list.slice(start, start + pageSize), total: list.length } };
};

const stats = computed(() => {
  const result = { danger: 0, warning: 0, pending: 0, unmatched: 0, feeTotal: 0, overrunTotal: 0 };
  filtered.value.forEach(row => {
    if (row.level === "danger") result.danger += 1;
    else if (row.level === "warning") result.warning += 1;
    else if (row.level === "pending") result.pending += 1;
    else if (row.level === "unmatched") result.unmatched += 1;
    result.feeTotal += row.currentFee;
    if (row.level === "danger" && row.currentFee > row.threshold95) {
      result.overrunTotal += row.currentFee - row.threshold95;
    }
  });
  return result;
});

const pendingCount = computed(
  () => allRows.value.filter(row => row.level !== "normal").length
);

const reload = async () => {
  try {
    const board = await getDipBoardApi();
    allRows.value = (board.patients ?? []).map(toRow);
    filtered.value = allRows.value;
    buildColumns();
    if (!loaded.value) {
      // 首次挂载时 ProTable 会自动发起一次查询
      loaded.value = true;
      return;
    }
    proTable.value?.getTableList();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "费用预警数据加载失败");
  }
};

const patientMeta = (row: Row) =>
  [row.gender, row.age === null ? "" : `${row.age}岁`, row.admissionNo ? `住院号 ${row.admissionNo}` : ""]
    .filter(Boolean)
    .join(" · ") || "—";

const suggestion = (row: Row) => {
  if (row.level === "danger") return `超 ${money(row.currentFee - row.threshold95)}`;
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

/* ---------- HIS 截图识别 ---------- */
const ocrVisible = ref(false);
const ocrLoading = ref(false);
const ocrWriting = ref(false);
const ocrItems = ref<DipOcrItem[]>([]);
const ocrChecked = ref<DipOcrItem[]>([]);
const ocrRecognized = ref(0);
const ocrMatched = ref(0);
const ocrPending = ref(0);

const onOcrSelection = (rows: DipOcrItem[]) => {
  ocrChecked.value = rows;
};

const handleOcrFile = async (file: { raw?: File }) => {
  if (!file.raw) return;
  if (!/^image\/(png|jpeg)$/.test(file.raw.type)) {
    ElMessage.error("仅支持 PNG / JPG 截图");
    return;
  }
  ocrLoading.value = true;
  ocrItems.value = [];
  ocrChecked.value = [];
  try {
    const result = await parseDipScreenshotApi(file.raw);
    ocrItems.value = result.items;
    ocrRecognized.value = result.recognized;
    ocrMatched.value = result.matched;
    ocrPending.value = result.pending;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "识别失败，请重试或手动录入");
  } finally {
    ocrLoading.value = false;
  }
};

const confirmOcr = async () => {
  if (!ocrChecked.value.length) return;
  ocrWriting.value = true;
  let written = 0;
  const failed: string[] = [];
  for (const item of ocrChecked.value) {
    if (!item.patientId || item.unsettledFee === null) continue;
    try {
      await saveDipFeeApi({
        patientId: item.patientId,
        unsettledFee: item.unsettledFee,
        source: "SCREENSHOT",
        admissionNo: item.ocrAdmissionNo || undefined,
        depositBalance: item.depositBalance
      });
      written += 1;
    } catch {
      failed.push(item.ocrName || item.patientName || item.patientId);
    }
  }
  ocrWriting.value = false;
  ocrVisible.value = false;
  await reload();
  if (failed.length) {
    ElMessage.warning(`已写入 ${written} 条，${failed.length} 条失败：${failed.join("、")}`);
  } else {
    ElMessage.success(`已写入 ${written} 条费用`);
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

  /* 聚合指标 */
  .metrics {
    display: grid;
    grid-template-columns: repeat(6, minmax(0, 1fr));
    gap: 8px;
  }

  .metric {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 8px 12px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
    background: var(--el-bg-color);

    span {
      font-size: 11.5px;
      color: var(--el-text-color-secondary);
    }

    strong {
      font-size: 18px;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
      line-height: 1.2;
    }

    &.is-danger strong {
      color: var(--danger);
    }

    &.is-warning strong {
      color: var(--warn);
    }

    &.muted {
      opacity: 0.55;
    }
  }

  .table-hint {
    font-size: 12px;
    color: var(--el-text-color-secondary);
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
    text-align: left;

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
    text-align: left;

    .group-name {
      font-size: 12.5px;
      color: var(--el-text-color-regular);
    }
  }

  .cell-his {
    display: flex;
    flex-direction: column;
    gap: 1px;
    text-align: left;
    font-size: 12px;
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
    text-align: left;

    &.is-pending {
      gap: 2px;
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
    gap: 6px;
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

  .ocr {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .ocr-upload {
    padding: 8px 0;

    p {
      margin: 0 0 4px;
      font-size: 13px;
      color: var(--el-text-color-regular);
    }

    small {
      font-size: 11.5px;
      color: var(--el-text-color-secondary);
    }
  }

  .ocr-status {
    font-size: 12.5px;
    color: var(--el-color-primary);
  }

  .ocr-summary {
    font-size: 12.5px;
    color: var(--el-text-color-regular);
  }

  .ocr-cell {
    display: flex;
    flex-direction: column;
    text-align: left;
    font-size: 12.5px;
  }
}
</style>
