<template>
  <div class="dip-warning">
    <header class="dw-head">
      <div class="dw-head-main">
        <h2>DIP 费用预警</h2>
        <p>
          按 DIP 病组预估费用监控在院患者费用水位：达到 <b class="t-warn">85%</b> 预警、<b class="t-danger">95%</b> 超限需停止计费。
        </p>
      </div>
      <div class="dw-head-actions">
        <el-button :icon="Upload" @click="openOcrDialog">上传 HIS 费用截图</el-button>
        <el-button :icon="EditPen" @click="openFeeDialog()">手动录入费用</el-button>
        <el-button :icon="Refresh" @click="reloadDemo">刷新</el-button>
      </div>
    </header>

    <nav class="dw-metrics" aria-label="费用预警汇总">
      <div class="dw-metric">
        <span>在院患者</span>
        <strong>{{ rows.length }}</strong>
      </div>
      <div class="dw-metric">
        <span>正常（&lt;85%）</span>
        <strong class="t-ok">{{ summary.normal }}</strong>
      </div>
      <div class="dw-metric" :class="{ 'is-warn': summary.warning > 0 }">
        <span>预警（85%~95%）</span>
        <strong class="t-warn">{{ summary.warning }}</strong>
      </div>
      <div class="dw-metric" :class="{ 'is-danger': summary.danger > 0 }">
        <span>超限（≥95%）</span>
        <strong class="t-danger">{{ summary.danger }}</strong>
      </div>
      <div class="dw-metric">
        <span>测算超支金额</span>
        <strong :class="summary.overrunAmount > 0 ? 't-danger' : 't-ok'">
          {{ summary.overrunAmount > 0 ? `¥${summary.overrunAmount.toFixed(2)}` : "¥0.00" }}
        </strong>
      </div>
    </nav>

    <el-alert
      class="dw-demo-notice"
      type="info"
      :closable="false"
      show-icon
      title="当前为演示数据"
      description="页面费用水位已按真实 DIP 病组映射表（24 个病组）与 HIS 截图费用计算，可作为功能预览；“上传 HIS 截图”暂未接入真实 OCR 服务。"
    />

    <section class="dw-toolbar">
      <el-input
        v-model="keyword"
        class="dw-search"
        placeholder="搜索姓名 / 住院号 / DIP 病组"
        clearable
        :prefix-icon="Search"
      />
      <el-radio-group v-model="levelFilter" size="small">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="danger">超限</el-radio-button>
        <el-radio-button value="warning">预警</el-radio-button>
        <el-radio-button value="normal">正常</el-radio-button>
      </el-radio-group>
      <el-select v-model="sortBy" class="dw-sort" size="default" placeholder="排序">
        <el-option label="按费用水位降序" value="ratioDesc" />
        <el-option label="按当前费用降序" value="feeDesc" />
        <el-option label="按入院日期降序" value="dateDesc" />
      </el-select>
    </section>

    <el-table :data="visibleRows" class="dw-table" row-key="admissionNo" :row-class-name="rowClass" stripe>
      <el-table-column prop="admissionNo" label="住院号" width="118" />
      <el-table-column prop="name" label="姓名" width="86" />
      <el-table-column prop="diagnosis" label="诊断" width="100" />
      <el-table-column label="DIP 病组" min-width="230">
        <template #default="{ row }">
          <div class="dw-group">
            <span class="dw-group-code">{{ row.dipCode }}</span>
            <span class="dw-group-name">{{ row.dipName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="预估费用" width="106" align="right">
        <template #default="{ row }">{{ money(row.estimatedFee) }}</template>
      </el-table-column>
      <el-table-column label="当前费用" width="106" align="right">
        <template #default="{ row }">
          <b>{{ money(row.currentFee) }}</b>
        </template>
      </el-table-column>
      <el-table-column label="费用水位" width="220">
        <template #default="{ row }">
          <div class="dw-waterline">
            <div class="dw-bar">
              <div class="dw-bar-fill" :class="`is-${row.level}`" :style="{ width: `${Math.min(row.ratio, 100)}%` }" />
              <i class="dw-bar-mark" style="left: 85%" />
              <i class="dw-bar-mark is-super" style="left: 95%" />
            </div>
            <span class="dw-ratio" :class="`t-${row.level}`">{{ row.ratio.toFixed(1) }}%</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="阈值（85% / 95%）" width="150" align="right">
        <template #default="{ row }">
          <span class="dw-threshold">{{ money(row.threshold85) }} / {{ money(row.threshold95) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="92">
        <template #default="{ row }">
          <el-tag :type="tagType(row.level)" effect="light" size="small">{{ levelText(row.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="费用更新时间" width="150" />
      <el-table-column label="操作" width="132" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button link type="primary" @click="openFeeDialog(row)">录费用</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有匹配的患者" :image-size="70" />
      </template>
    </el-table>

    <!-- 手动录入：当前唯一稳定的人工收敛入口 -->
    <el-dialog v-model="feeDialogVisible" title="录入当前费用" width="460px">
      <el-form v-if="feeTarget" label-width="96px">
        <el-form-item label="患者">
          <span>{{ feeTarget.name }}（{{ feeTarget.admissionNo }}）</span>
        </el-form-item>
        <el-form-item label="DIP 病组">
          <span class="dw-group-name">{{ feeTarget.dipName }}</span>
        </el-form-item>
        <el-form-item label="当前费用">
          <el-input-number v-model="feeInput" :min="0" :precision="2" :step="100" controls-position="right" />
        </el-form-item>
        <el-form-item label="录入后水位">
          <b :class="`t-${previewLevel}`">{{ previewRatio.toFixed(1) }}%</b>
          <span class="dw-hint">（85% = {{ money(feeTarget.threshold85) }}，95% = {{ money(feeTarget.threshold95) }}）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="feeDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitFee">保存</el-button>
      </template>
    </el-dialog>

    <!-- HIS 截图识别：演示解析流程与人工确认环节 -->
    <el-dialog v-model="ocrDialogVisible" title="上传 HIS 费用截图" width="640px">
      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="false"
        accept="image/png,image/jpeg"
        :on-change="handleOcrFile"
      >
        <div class="dw-upload">
          <el-icon><Upload /></el-icon>
          <p>把 HIS 住院患者费用列表截图拖到这里，或点击选择文件</p>
          <small>支持 PNG / JPG。识别结果会进入人工确认，确认后才写入费用。</small>
        </div>
      </el-upload>
      <el-alert
        class="dw-ocr-notice"
        type="warning"
        :closable="false"
        show-icon
        title="OCR 服务尚未接通"
        description="本演示用“载入示例识别结果”模拟识别输出；接入真实 OCR 后，这里会回填截图中的住院号与未结算费用并自动匹配病组。"
      />
      <div class="dw-ocr-actions">
        <el-button type="primary" plain @click="loadSampleOcr">载入示例识别结果</el-button>
      </div>
      <el-table v-if="ocrRows.length" :data="ocrRows" size="small" class="dw-ocr-table" max-height="240">
        <el-table-column prop="admissionNo" label="住院号" width="120" />
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column prop="currentFee" label="识别费用" width="110" align="right">
          <template #default="{ row }">{{ money(row.currentFee) }}</template>
        </el-table-column>
        <el-table-column prop="matched" label="匹配" width="80">
          <template #default="{ row }">
            <el-tag :type="row.matched ? 'success' : 'info'" size="small">{{ row.matched ? "已匹配" : "未匹配" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="识别后水位">
          <template #default="{ row }">
            <span v-if="row.matched" :class="`t-${row.level}`">{{ row.ratio.toFixed(1) }}%</span>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="ocrDialogVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!ocrRows.length" @click="applyOcrRows">确认写入费用</el-button>
      </template>
    </el-dialog>

    <!-- 患者费用水位详情 -->
    <el-drawer v-model="detailVisible" :title="detailTarget ? `${detailTarget.name} · 费用水位` : '费用水位'" size="420px">
      <div v-if="detailTarget" class="dw-detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="住院号">{{ detailTarget.admissionNo }}</el-descriptions-item>
          <el-descriptions-item label="诊断">{{ detailTarget.diagnosis }}</el-descriptions-item>
          <el-descriptions-item label="DIP 病组">{{ detailTarget.dipCode }}</el-descriptions-item>
          <el-descriptions-item label="病组名称">{{ detailTarget.dipName }}</el-descriptions-item>
          <el-descriptions-item label="预估费用">{{ money(detailTarget.estimatedFee) }}</el-descriptions-item>
          <el-descriptions-item label="当前费用">{{ money(detailTarget.currentFee) }}</el-descriptions-item>
          <el-descriptions-item label="85% 阈值">{{ money(detailTarget.threshold85) }}</el-descriptions-item>
          <el-descriptions-item label="95% 阈值">{{ money(detailTarget.threshold95) }}</el-descriptions-item>
          <el-descriptions-item label="剩余可用额度">{{ money(detailTarget.remainTo95) }}</el-descriptions-item>
        </el-descriptions>

        <div class="dw-detail-bar">
          <div class="dw-bar is-tall">
            <div
              class="dw-bar-fill"
              :class="`is-${detailTarget.level}`"
              :style="{ width: `${Math.min(detailTarget.ratio, 100)}%` }"
            />
            <i class="dw-bar-mark" style="left: 85%" />
            <i class="dw-bar-mark is-super" style="left: 95%" />
          </div>
          <div class="dw-detail-scale">
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
          description="如无特殊情况应停止计费并办理出院；继续产生费用将超出病组支付标准。"
        />
        <el-alert
          v-else-if="detailTarget.level === 'warning'"
          type="warning"
          :closable="false"
          show-icon
          title="已达 85% 预警线"
          description="请关注后续费用增长，提前安排出院或核对诊疗项目。"
        />
        <el-alert v-else type="success" :closable="false" show-icon title="费用水位正常" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts" name="dipWarning">
import { computed, ref } from "vue";
import { ElMessage, type UploadFile } from "element-plus";
import { EditPen, Refresh, Search, Upload } from "@element-plus/icons-vue";

type Level = "normal" | "warning" | "danger";

interface DipGroup {
  code: string;
  name: string;
  estimatedFee: number;
}

interface PatientRow {
  admissionNo: string;
  name: string;
  diagnosis: string;
  dipCode: string;
  dipName: string;
  estimatedFee: number;
  currentFee: number;
  threshold85: number;
  threshold95: number;
  ratio: number;
  remainTo95: number;
  level: Level;
  updatedAt: string;
}

interface WaterlineRow extends PatientRow {}

/** DIP 病组核心映射（来自《常用病组核心诊断、操作及费用_85_95对照》） */
const DIP_GROUPS: DipGroup[] = [
  { code: "K64:49.4500x004", name: "内镜下内痔套扎治疗", estimatedFee: 5062 },
  { code: "K64:49.4600_45.2302", name: "痔切除术_电子结肠镜检查", estimatedFee: 5021 },
  { code: "K64:49.4200", name: "痔注射", estimatedFee: 4090 },
  { code: "K64:49.4600_49.5903", name: "痔切除术_肛门括约肌切开术", estimatedFee: 4984 },
  { code: "K60:49.1200", name: "肛门瘘管切除术", estimatedFee: 4687 },
  { code: "K60:49.1200_49.4600", name: "肛门瘘管切除术_痔切除术", estimatedFee: 5566 },
  { code: "K60:49.3901_49.3906", name: "肛裂切除术_肛乳头切除术", estimatedFee: 5056 },
  { code: "K60:49.3901_49.3906+49.4600", name: "肛裂切除术_肛乳头切除术 + 痔切除术", estimatedFee: 5420 },
  { code: "K60:49.3901_49.5903", name: "肛裂切除术_肛门括约肌切开术", estimatedFee: 5306 },
  { code: "K61:49.0400x008", name: "肛周脓肿根治术", estimatedFee: 4524 },
  { code: "K61:49.0400x008_49.4500", name: "肛周脓肿根治术_痔结扎术", estimatedFee: 6130 },
  { code: "K61:49.1200", name: "肛门瘘管切除术（脓肿）", estimatedFee: 4822 },
  { code: "L05:86.2101", name: "藏毛囊肿切除术", estimatedFee: 4609 },
  { code: "K64:49.4601_49.3906", name: "痔切除术伴肛门成形术_肛乳头切除术", estimatedFee: 4405 },
  { code: "K64:BSZL", name: "痔疮保守治疗", estimatedFee: 3519 },
  { code: "K64:SS", name: "痔疮相关手术组", estimatedFee: 4579 },
  { code: "K60:SS", name: "裂和瘘相关手术组", estimatedFee: 3951 },
  { code: "K60:ZLXCZ", name: "裂和瘘治疗性操作组", estimatedFee: 4922 },
  { code: "K60:49.1200_49.7301", name: "肛门瘘管切除术_肛瘘挂线术", estimatedFee: 4771 },
  { code: "K61:48.8101", name: "直肠周围脓肿切开引流术", estimatedFee: 4776 },
  { code: "K61:ZLXCZ", name: "脓肿治疗性操作组", estimatedFee: 5753 },
  { code: "K61:SS", name: "脓肿相关手术组", estimatedFee: 4934 },
  { code: "K62:49.3906_49.4500", name: "肛乳头切除术_痔结扎术", estimatedFee: 5948 },
  { code: "K62:49.3906_49.5903", name: "肛乳头切除术_肛门括约肌切开术", estimatedFee: 6726 }
];

const groupByCode = new Map(DIP_GROUPS.map(group => [group.code, group]));

/** 演示患者：住院号与未结算费用取自 HIS 截图，病组按诊断匹配 */
const DEMO_SEED: Array<{ admissionNo: string; name: string; diagnosis: string; dipCode: string; currentFee: number; updatedAt: string }> = [
  { admissionNo: "0020224991", name: "黄金发", diagnosis: "混合痔", dipCode: "K64:49.4500x004", currentFee: 291.13, updatedAt: "2026-10-17 11:29" },
  { admissionNo: "0020224990", name: "李安生", diagnosis: "肛门狭窄", dipCode: "K60:49.1200", currentFee: 755.54, updatedAt: "2026-10-16 16:44" },
  { admissionNo: "0020224989", name: "宁若芬", diagnosis: "肛裂", dipCode: "K60:49.3901_49.3906", currentFee: 687.4, updatedAt: "2026-10-16 11:29" },
  { admissionNo: "0020224988", name: "许若芬", diagnosis: "肛裂", dipCode: "K60:49.3901_49.3906", currentFee: 3148.55, updatedAt: "2026-10-16 09:29" },
  { admissionNo: "0020224987", name: "万季荣", diagnosis: "混合痔", dipCode: "K64:49.4600_45.2302", currentFee: 1343.72, updatedAt: "2026-10-16 08:47" },
  { admissionNo: "0020224986", name: "申光兴", diagnosis: "内痔", dipCode: "K64:49.4200", currentFee: 3178.62, updatedAt: "2026-10-15 11:22" },
  { admissionNo: "0020224985", name: "朱云岩", diagnosis: "混合痔", dipCode: "K64:BSZL", currentFee: 3497.68, updatedAt: "2026-10-14 09:25" },
  { admissionNo: "0020224984", name: "沈光英", diagnosis: "混合痔", dipCode: "K64:49.4600_49.5903", currentFee: 3727.71, updatedAt: "2026-10-14 08:21" },
  { admissionNo: "0020224983", name: "黄芸", diagnosis: "混合痔", dipCode: "K64:SS", currentFee: 4267.83, updatedAt: "2026-10-13 11:30" },
  { admissionNo: "0020224982", name: "李继荣", diagnosis: "混合痔", dipCode: "K64:49.4500x004", currentFee: 2993.34, updatedAt: "2026-10-13 10:39" },
  { admissionNo: "0020224981", name: "宋惠英", diagnosis: "混合痔", dipCode: "K64:49.4500x004", currentFee: 4350.65, updatedAt: "2026-10-13 09:32" },
  { admissionNo: "0020224980", name: "汤幸祥", diagnosis: "肛裂", dipCode: "K60:49.3901_49.5903", currentFee: 3459.62, updatedAt: "2026-10-12 09:52" },
  { admissionNo: "0020224979", name: "唐光华", diagnosis: "肛裂", dipCode: "K60:ZLXCZ", currentFee: 3441.14, updatedAt: "2026-10-12 08:12" },
  { admissionNo: "0020224978", name: "盛天军", diagnosis: "混合痔", dipCode: "K64:49.4600_49.5903", currentFee: 4517.32, updatedAt: "2026-10-11 09:56" },
  { admissionNo: "0020224977", name: "周文宗", diagnosis: "肛裂", dipCode: "K60:SS", currentFee: 4343.72, updatedAt: "2026-10-11 08:14" }
];

const rows = ref<WaterlineRow[]>([]);
const keyword = ref("");
const levelFilter = ref<"all" | Level>("all");
const sortBy = ref<"ratioDesc" | "feeDesc" | "dateDesc">("ratioDesc");

const levelOf = (ratio: number): Level => (ratio >= 95 ? "danger" : ratio >= 85 ? "warning" : "normal");

const buildRow = (seed: (typeof DEMO_SEED)[number], currentFee: number, updatedAt: string): WaterlineRow => {
  const group = groupByCode.get(seed.dipCode) ?? DIP_GROUPS[0];
  const estimatedFee = group.estimatedFee;
  const threshold85 = Number((estimatedFee * 0.85).toFixed(2));
  const threshold95 = Number((estimatedFee * 0.95).toFixed(2));
  const ratio = estimatedFee > 0 ? (currentFee / estimatedFee) * 100 : 0;
  return {
    admissionNo: seed.admissionNo,
    name: seed.name,
    diagnosis: seed.diagnosis,
    dipCode: group.code,
    dipName: group.name,
    estimatedFee,
    currentFee,
    threshold85,
    threshold95,
    ratio,
    remainTo95: Number((threshold95 - currentFee).toFixed(2)),
    level: levelOf(ratio),
    updatedAt
  };
};

const reloadDemo = () => {
  rows.value = DEMO_SEED.map(seed => buildRow(seed, seed.currentFee, seed.updatedAt));
  ElMessage.success("已刷新费用数据");
};

reloadDemo();

const money = (value: number) => `¥${value.toFixed(2)}`;

const levelText = (level: Level) => (level === "danger" ? "超限" : level === "warning" ? "预警" : "正常");
const tagType = (level: Level) => (level === "danger" ? "danger" : level === "warning" ? "warning" : "success");

const summary = computed(() => {
  let normal = 0;
  let warning = 0;
  let danger = 0;
  let overrunAmount = 0;
  rows.value.forEach(row => {
    if (row.level === "danger") danger += 1;
    else if (row.level === "warning") warning += 1;
    else normal += 1;
    if (row.currentFee > row.threshold95) overrunAmount += row.currentFee - row.threshold95;
  });
  return { normal, warning, danger, overrunAmount };
});

const visibleRows = computed(() => {
  const key = keyword.value.trim().toLowerCase();
  const filtered = rows.value.filter(row => {
    if (levelFilter.value !== "all" && row.level !== levelFilter.value) return false;
    if (!key) return true;
    return (
      row.name.toLowerCase().includes(key) ||
      row.admissionNo.includes(key) ||
      row.dipCode.toLowerCase().includes(key) ||
      row.dipName.toLowerCase().includes(key)
    );
  });
  const sorted = [...filtered];
  if (sortBy.value === "ratioDesc") sorted.sort((a, b) => b.ratio - a.ratio);
  if (sortBy.value === "feeDesc") sorted.sort((a, b) => b.currentFee - a.currentFee);
  if (sortBy.value === "dateDesc") sorted.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  return sorted;
});

const rowClass = ({ row }: { row: WaterlineRow }) => (row.level === "danger" ? "dw-row-danger" : row.level === "warning" ? "dw-row-warning" : "");

/* ---------------- 手动录入 ---------------- */
const feeDialogVisible = ref(false);
const feeTarget = ref<WaterlineRow | null>(null);
const feeInput = ref(0);

const openFeeDialog = (row?: WaterlineRow) => {
  feeTarget.value = row ?? visibleRows.value[0] ?? rows.value[0] ?? null;
  if (!feeTarget.value) {
    ElMessage.warning("暂无可录入的患者");
    return;
  }
  feeInput.value = feeTarget.value.currentFee;
  feeDialogVisible.value = true;
};

const previewRatio = computed(() => {
  const target = feeTarget.value;
  if (!target || target.estimatedFee <= 0) return 0;
  return (feeInput.value / target.estimatedFee) * 100;
});

const previewLevel = computed(() => levelOf(previewRatio.value));

const submitFee = () => {
  const target = feeTarget.value;
  if (!target) return;
  const index = rows.value.findIndex(row => row.admissionNo === target.admissionNo);
  if (index < 0) return;
  const seed = DEMO_SEED.find(item => item.admissionNo === target.admissionNo);
  if (!seed) return;
  const now = new Date();
  const stamp = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")} ${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;
  rows.value[index] = buildRow(seed, feeInput.value, stamp);
  feeDialogVisible.value = false;
  ElMessage.success(`${target.name} 费用已更新`);
};

/* ---------------- HIS 截图识别（演示） ---------------- */
const ocrDialogVisible = ref(false);
const ocrRows = ref<Array<{ admissionNo: string; name: string; currentFee: number; matched: boolean; ratio: number; level: Level }>>([]);

const openOcrDialog = () => {
  ocrRows.value = [];
  ocrDialogVisible.value = true;
};

const handleOcrFile = (file: UploadFile) => {
  if (!file.raw) return;
  if (!/^image\/(png|jpeg)$/.test(file.raw.type)) {
    ElMessage.error("仅支持 PNG / JPG 截图");
    return;
  }
  ElMessage.info("已收到截图：OCR 服务未接通，可先“载入示例识别结果”预览流程");
};

const loadSampleOcr = () => {
  const samples = ["0020224981", "0020224978", "0020224977"];
  ocrRows.value = samples.map(admissionNo => {
    const seed = DEMO_SEED.find(item => item.admissionNo === admissionNo);
    if (!seed) return null;
    const group = groupByCode.get(seed.dipCode) ?? DIP_GROUPS[0];
    const ratio = (seed.currentFee / group.estimatedFee) * 100;
    return {
      admissionNo: seed.admissionNo,
      name: seed.name,
      currentFee: seed.currentFee,
      matched: true,
      ratio,
      level: levelOf(ratio)
    };
  }).filter((item): item is NonNullable<typeof item> => Boolean(item));
  ElMessage.success(`识别到 ${ocrRows.value.length} 条记录`);
};

const applyOcrRows = () => {
  let applied = 0;
  ocrRows.value.forEach(item => {
    if (!item.matched) return;
    const index = rows.value.findIndex(row => row.admissionNo === item.admissionNo);
    const seed = DEMO_SEED.find(row => row.admissionNo === item.admissionNo);
    if (index < 0 || !seed) return;
    rows.value[index] = buildRow(seed, item.currentFee, rows.value[index].updatedAt);
    applied += 1;
  });
  ocrDialogVisible.value = false;
  ElMessage.success(`已写入 ${applied} 条费用`);
};

/* ---------------- 详情 ---------------- */
const detailVisible = ref(false);
const detailTarget = ref<WaterlineRow | null>(null);

const openDetail = (row: WaterlineRow) => {
  detailTarget.value = row;
  detailVisible.value = true;
};
</script>

<style scoped lang="scss">
.dip-warning {
  padding: 18px 20px 32px;
  display: flex;
  flex-direction: column;
  gap: 14px;

  .dw-head {
    display: flex;
    align-items: flex-start;
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
      font-size: 13px;
      color: var(--el-text-color-secondary);
    }
  }

  .dw-head-actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }

  .dw-metrics {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }

  .dw-metric {
    flex: 1 1 150px;
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
      font-size: 20px;
      font-weight: 600;
    }

    &.is-warn {
      border-color: var(--el-color-warning-light-5);
      background: var(--el-color-warning-light-9);
    }

    &.is-danger {
      border-color: var(--el-color-danger-light-5);
      background: var(--el-color-danger-light-9);
    }
  }

  .dw-toolbar {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  .dw-search {
    width: 280px;
  }

  .dw-sort {
    width: 168px;
  }

  .dw-group {
    display: flex;
    flex-direction: column;
    line-height: 1.35;

    .dw-group-code {
      font-size: 12px;
      color: var(--el-color-primary);
    }

    .dw-group-name {
      font-size: 12px;
      color: var(--el-text-color-regular);
    }
  }

  .dw-threshold {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .dw-waterline {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .dw-bar {
    position: relative;
    flex: 1;
    height: 10px;
    border-radius: 6px;
    background: var(--el-fill-color-light);
    overflow: hidden;

    &.is-tall {
      height: 16px;
      border-radius: 8px;
    }
  }

  .dw-bar-fill {
    position: absolute;
    inset: 0 auto 0 0;
    border-radius: inherit;
    transition: width 0.25s ease;

    &.is-normal {
      background: var(--el-color-success);
    }

    &.is-warning {
      background: var(--el-color-warning);
    }

    &.is-danger {
      background: var(--el-color-danger);
    }
  }

  .dw-bar-mark {
    position: absolute;
    top: 0;
    bottom: 0;
    width: 1px;
    background: rgba(0, 0, 0, 0.35);

    &.is-super {
      background: rgba(0, 0, 0, 0.55);
    }
  }

  .dw-ratio {
    width: 52px;
    text-align: right;
    font-variant-numeric: tabular-nums;
    font-size: 13px;
  }

  .dw-hint {
    margin-left: 6px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .dw-upload {
    padding: 20px 0;
    text-align: center;

    .el-icon {
      font-size: 32px;
      color: var(--el-color-primary);
    }

    p {
      margin: 8px 0 4px;
      font-size: 14px;
    }

    small {
      color: var(--el-text-color-secondary);
    }
  }

  .dw-ocr-notice {
    margin-top: 12px;
  }

  .dw-ocr-actions {
    margin: 10px 0;
  }

  .dw-detail {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .dw-detail-bar {
    padding: 6px 2px;

    .dw-detail-scale {
      display: flex;
      justify-content: space-between;
      margin-top: 6px;
      font-size: 11px;
      color: var(--el-text-color-secondary);
      font-variant-numeric: tabular-nums;
    }
  }

  :deep(.dw-row-danger) {
    background: var(--el-color-danger-light-9);
  }

  :deep(.dw-row-warning) {
    background: var(--el-color-warning-light-9);
  }
}

.t-ok {
  color: var(--el-color-success);
}

.t-warn {
  color: var(--el-color-warning);
}

.t-danger {
  color: var(--el-color-danger);
}
</style>
