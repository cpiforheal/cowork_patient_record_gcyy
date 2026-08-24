<template>
  <section class="quota-console" aria-label="每人次定额总控制台">
    <div class="console-status">
      <div class="version-info">
        <div class="version-item">
          <small>编辑版本</small>
          <strong>{{ viewVersion?.versionCode || "-" }}</strong>
        </div>
        <div class="version-item">
          <small>生效日期</small>
          <strong>{{ viewVersion?.effectiveDate || "-" }}</strong>
        </div>
        <el-tag :type="isEditable ? 'warning' : 'info'" effect="plain">
          {{ isEditable ? "可编辑（未来生效）" : "已生效，已冻结" }}
        </el-tag>
        <el-select v-model="selectedVersionId" size="small" filterable class="version-select" placeholder="切换查看版本">
          <el-option v-for="version in versions" :key="version.id" :value="version.id" :label="`${version.versionCode}（${version.effectiveDate} 生效）`" />
        </el-select>
        <el-button link type="primary" size="small" :disabled="!previousVersion" @click="openDiff">对比上一版本</el-button>
        <el-button link type="primary" size="small" @click="openAudit">变更记录</el-button>
      </div>
      <div class="console-actions">
        <el-tooltip content="保存后立即为当日尚未填报的科室按新定额预播种草稿；已填报科室不受影响" placement="top">
          <el-checkbox v-model="applyToday">当日即时应用</el-checkbox>
        </el-tooltip>
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
        <el-tooltip content="导出当前查看版本的已保存定额规则（含版本清单与口径说明）" placement="top">
          <el-button :icon="Download" :loading="exporting" @click="exportXlsx">导出XLSX</el-button>
        </el-tooltip>
        <el-button :icon="Plus" type="primary" plain @click="openAdd">新增耗材</el-button>
        <el-button type="primary" :loading="saving" :disabled="!hasChanges" @click="save">
          {{ isEditable ? "保存修改" : "保存并创建明日版本" }}
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="loadError"
      type="error"
      :title="loadError"
      :closable="false"
      show-icon
    />
    <el-alert
      v-else
      type="info"
      :closable="false"
      show-icon
      title="计算口径：每人次 = 定额 × 科室流转人次 + 固定调整；固定日耗 = 每日固定用量 + 固定调整；按需领取与仪器触发不参与自动测算，按实际领取填写。保存到未来版本后，次日零点起各科室耗材表按新定额重建；勾选「当日即时应用」可让当日未填报科室立即使用新定额。"
    />

    <div v-if="hasChanges" class="pending-bar">
      <span>
        待保存：{{ dirtyIds.size }} 项修改<template v-if="pendingCreates.length"> · {{ pendingCreates.length }} 项新增</template
        ><template v-if="pendingDeletes.size"> · {{ pendingDeletes.size }} 项删除</template>
      </span>
      <el-button link type="primary" @click="reload">撤销全部未保存修改</el-button>
    </div>

    <div class="rule-filters">
      <el-select v-model="departmentKey" filterable clearable placeholder="筛选科室" class="department-select">
        <el-option v-for="department in departments" :key="department.key" :label="department.name" :value="department.key" />
      </el-select>
      <el-select v-model="bindingFilter" clearable placeholder="筛选绑定方式" class="binding-select">
        <el-option v-for="(meta, key) in bindingMeta" :key="key" :label="meta.label" :value="key" />
      </el-select>
      <el-input v-model="keyword" clearable placeholder="搜索耗材或服务项目" class="keyword-input" />
      <span class="rule-count">{{ visibleRows.length }} 项定额规则</span>
    </div>

    <div class="binding-legend">
      <span class="legend-item"><i class="legend-dot" style="background:#ff9800"></i>固定日耗</span>
      <span class="legend-item"><i class="legend-dot" style="background:#2196f3"></i>按需领取</span>
      <span class="legend-item"><i class="legend-dot" style="background:#4caf50"></i>仪器触发</span>
      <span class="legend-hint">非"每人次定额"耗材以颜色标记区分</span>
    </div>

    <el-table
      v-loading="loading"
      :data="visibleRows"
      row-key="rowKey"
      table-layout="fixed"
      height="calc(100vh - 330px)"
      empty-text="当前版本没有定额规则"
      class="clean-table"
      :row-class-name="bindingRowClass"
    >
      <el-table-column prop="departmentName" label="科室" width="100" />
      <el-table-column prop="serviceGroup" label="服务项目" min-width="120" show-overflow-tooltip />
      <el-table-column label="耗材" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.materialName }}</span><span class="unit-suffix"> / {{ row.unit }}</span>
          <el-tag
            v-if="row.bindingType && row.bindingType !== 'PER_PERSON'"
            size="small"
            effect="plain"
            :type="bindingMeta[row.bindingType]?.tag"
            class="row-tag"
          >
            {{ bindingMeta[row.bindingType]?.short || row.bindingType }}
          </el-tag>
          <el-tag v-if="row.pending" size="small" type="success" effect="plain" class="row-tag">新增</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="绑定方式" width="118">
        <template #default="{ row }">
          <el-select v-model="row.bindingType" size="small" @change="onBindingChange(row as ConsoleRow)">
            <el-option v-for="(meta, key) in bindingMeta" :key="key" :label="meta.label" :value="key" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="定额 / 调整" width="170">
        <template #default="{ row }">
          <div class="dual-input-cell">
            <el-input-number
              v-model="row.standardQuantity"
              :min="0"
              :precision="6"
              controls-position="right"
              size="small"
              :disabled="row.bindingType === 'ON_DEMAND' || row.bindingType === 'EQUIPMENT'"
              @change="markDirty(row as ConsoleRow)"
              placeholder="定额"
            />
            <el-input-number
              v-model="row.fixedAdjustment"
              :precision="6"
              controls-position="right"
              size="small"
              @change="markDirty(row as ConsoleRow)"
              placeholder="调整"
            />
          </div>
        </template>
      </el-table-column>
      <el-table-column label="计量人次范围" width="140">
        <template #default="{ row }">
          <el-select v-model="row.measurementScope" size="small" @change="markDirty(row as ConsoleRow)">
            <el-option label="门诊人次" value="OUTPATIENT" />
            <el-option label="住院人次" value="INPATIENT" />
            <el-option label="门诊 + 住院" value="COMBINED" />
            <el-option label="其他人次" value="OTHER" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="72" align="center">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" size="small" @change="markDirty(row as ConsoleRow)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="84" fixed="right" align="center">
        <template #default="{ row }">
          <el-button v-if="row.pending" link type="warning" @click="cancelCreate(row as ConsoleRow)">取消</el-button>
          <el-button v-else link type="danger" @click="confirmDelete(row as ConsoleRow)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="addOpen" title="新增耗材定额（加入待保存队列）" size="min(440px, 100%)" destroy-on-close>
      <el-form label-width="100px">
        <el-form-item label="科室" required>
          <el-select v-model="createForm.departmentKey" filterable placeholder="选择科室">
            <el-option v-for="department in departments" :key="department.key" :label="department.name" :value="department.key" />
          </el-select>
        </el-form-item>
        <el-form-item label="耗材名称" required>
          <el-input v-model="createForm.materialName" maxlength="255" placeholder="例如 一次性输液器" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="createForm.unit" maxlength="64" placeholder="例如 支、片、副" />
        </el-form-item>
        <el-form-item label="服务项目" required>
          <el-input v-model="createForm.serviceGroup" maxlength="128" placeholder="例如 静脉输液、针灸" />
        </el-form-item>
        <el-form-item label="照护类型">
          <el-select v-model="createForm.careType">
            <el-option label="门诊" value="outpatient" />
            <el-option label="住院" value="inpatient" />
            <el-option label="通用" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="绑定方式" required>
          <el-select v-model="createForm.bindingType">
            <el-option v-for="(meta, key) in bindingMeta" :key="key" :label="meta.label" :value="key" />
          </el-select>
          <div class="form-hint">{{ bindingMeta[createForm.bindingType]?.hint }}</div>
        </el-form-item>
        <el-form-item label="定额值">
          <el-input-number
            v-model="createForm.standardQuantity"
            :min="0"
            :precision="6"
            controls-position="right"
            :disabled="createForm.bindingType === 'ON_DEMAND' || createForm.bindingType === 'EQUIPMENT'"
          />
        </el-form-item>
        <el-form-item label="固定调整">
          <el-input-number v-model="createForm.fixedAdjustment" :precision="6" controls-position="right" />
        </el-form-item>
        <el-form-item label="计量范围" required>
          <el-select v-model="createForm.measurementScope">
            <el-option label="门诊人次" value="OUTPATIENT" />
            <el-option label="住院人次" value="INPATIENT" />
            <el-option label="门诊 + 住院" value="COMBINED" />
            <el-option label="其他人次" value="OTHER" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addOpen = false">取消</el-button>
        <el-button type="primary" @click="appendCreate">加入待保存</el-button>
      </template>
    </el-drawer>

    <el-drawer v-model="diffOpen" :title="diffTitle" size="min(560px, 100%)" destroy-on-close>
      <div v-loading="diffLoading">
        <el-alert
          v-if="!diffLoading && diffRows.length"
          type="info"
          :closable="false"
          show-icon
          :title="`新增 ${diffSummary.added} 项 · 删除 ${diffSummary.removed} 项 · 修改 ${diffSummary.changed} 项（按科室 + 耗材 + 单位对齐）`"
        />
        <el-alert
          v-else-if="!diffLoading"
          type="success"
          :closable="false"
          show-icon
          title="两个版本的定额规则完全一致"
        />
        <el-table v-if="diffRows.length" :data="diffRows" height="calc(100vh - 220px)" class="clean-table">
          <el-table-column prop="departmentName" label="科室" width="96" />
          <el-table-column prop="materialName" label="耗材" min-width="150" show-overflow-tooltip />
          <el-table-column prop="unit" label="单位" width="64" />
          <el-table-column label="类型" width="76" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.kind === 'added' ? 'success' : row.kind === 'removed' ? 'danger' : 'warning'" effect="plain">
                {{ row.kind === "added" ? "新增" : row.kind === "removed" ? "删除" : "修改" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="变更明细" min-width="220">
            <template #default="{ row }">
              <div v-for="(change, index) in row.changes" :key="index" class="diff-line">
                {{ change.label }}：{{ change.from }} → {{ change.to }}
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <el-drawer v-model="auditOpen" :title="auditTitle" size="min(680px, 100%)" destroy-on-close>
      <div v-loading="auditLoading">
        <el-alert
          v-if="!auditLoading && !auditRows.length"
          type="info"
          :closable="false"
          show-icon
          title="当前查看版本还没有变更记录"
        />
        <el-table v-if="auditRows.length" :data="auditRows" height="calc(100vh - 220px)" class="clean-table">
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ formatAuditTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="departmentName" label="科室" width="96" />
          <el-table-column prop="materialName" label="耗材" min-width="140" show-overflow-tooltip />
          <el-table-column label="操作" width="72" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.action === 'CREATE' ? 'success' : row.action === 'DELETE' ? 'danger' : 'warning'" effect="plain">
                {{ row.action === "CREATE" ? "新增" : row.action === "DELETE" ? "删除" : "修改" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="变更明细" min-width="200">
            <template #default="{ row }">
              <div v-for="(change, index) in auditChangesOf(row)" :key="index" class="diff-line">
                {{ change.label }}：{{ change.from }} → {{ change.to }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作人" width="140">
            <template #default="{ row }">{{ row.operatorName }}（{{ row.operatorUsername }}）</template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Download, Plus, Refresh } from "@element-plus/icons-vue";
import {
  consoleSaveInventoryQuotaApi,
  downloadInventoryQuotaGovernanceXlsxApi,
  getInventoryQuotaAuditLogApi,
  getInventoryQuotaGovernanceApi,
  type InventoryQuotaAuditEntry,
  type InventoryQuotaBindingType,
  type InventoryQuotaConsoleSaveResult,
  type InventoryQuotaRule,
  type InventoryQuotaRuleCreatePayload,
  type InventoryQuotaVersion
} from "@/api/modules/inventory";

const emit = defineEmits<{ saved: [] }>();

const departmentDirectory: Array<{ key: string; name: string }> = [
  { key: "physiotherapy", name: "理疗室" },
  { key: "laboratory", name: "检验科" },
  { key: "nursing", name: "护理部" },
  { key: "tcm", name: "中医科" },
  { key: "operating", name: "手术室" },
  { key: "anesthesia", name: "麻醉室" },
  { key: "endoscopy", name: "胃肠镜" },
  { key: "inspection", name: "检查室" },
  { key: "logistics", name: "后勤" },
  { key: "western-pharmacy", name: "西药房" },
  { key: "cashier", name: "收费室" },
  { key: "tcm-pharmacy", name: "中药房" }
];

type ConsoleRow = InventoryQuotaRule & { pending?: boolean; rowKey: string };

const bindingMeta: Record<InventoryQuotaBindingType, { label: string; short: string; tag: "primary" | "warning" | "info" | "success"; hint: string }> = {
  PER_PERSON: {
    label: "每人次定额",
    short: "人次",
    tag: "primary",
    hint: "用量与患者人次线性相关，测算口径 = 定额 × 计量人次 + 固定调整。"
  },
  FIXED_DAILY: {
    label: "固定日耗",
    short: "日耗",
    tag: "warning",
    hint: "按天固定消耗（如垃圾袋、口罩、酶液），测算口径 = 每日固定用量 + 固定调整，不随人次变化。"
  },
  ON_DEMAND: {
    label: "按需领取",
    short: "按需",
    tag: "info",
    hint: "无固定规律（如签字笔芯、打印纸），不参与自动测算，岗位人员按实际领取填写。"
  },
  EQUIPMENT: {
    label: "仪器触发",
    short: "仪器",
    tag: "success",
    hint: "由设备开关机与报警损耗驱动（如稀释液、溶血剂），不参与自动测算，按实际使用填写。"
  }
};

const loading = ref(false);
const saving = ref(false);
const exporting = ref(false);
const loadError = ref("");
const governance = ref<InventoryQuotaConsoleSaveResult>();
const selectedVersionId = ref("");
const departmentKey = ref("");
const keyword = ref("");
const bindingFilter = ref<InventoryQuotaBindingType | "">("");
const applyToday = ref(false);
const addOpen = ref(false);
const rules = ref<InventoryQuotaRule[]>([]);
const pendingCreates = ref<InventoryQuotaRuleCreatePayload[]>([]);
const pendingDeletes = ref(new Set<string>());
const dirtyIds = ref(new Set<string>());
const createForm = ref({
  departmentKey: "",
  materialName: "",
  unit: "",
  serviceGroup: "",
  careType: "outpatient",
  standardQuantity: 0,
  fixedAdjustment: 0,
  measurementScope: "OUTPATIENT" as InventoryQuotaRuleCreatePayload["measurementScope"],
  bindingType: "PER_PERSON" as InventoryQuotaBindingType
});
const today = new Date().toISOString().slice(0, 10);

const versions = computed<InventoryQuotaVersion[]>(() => governance.value?.versions || []);
const viewVersion = computed(() => {
  if (selectedVersionId.value) return versions.value.find(version => version.id === selectedVersionId.value) || null;
  return governance.value?.activeVersion || null;
});
const isEditable = computed(() => Boolean(viewVersion.value && viewVersion.value.effectiveDate > today));

type DiffChange = { label: string; from: string; to: string };
type DiffRow = {
  departmentName: string;
  materialName: string;
  unit: string;
  kind: "added" | "removed" | "changed";
  changes: DiffChange[];
};

const diffOpen = ref(false);
const diffLoading = ref(false);
const diffRows = ref<DiffRow[]>([]);
const scopeLabels: Record<string, string> = {
  OUTPATIENT: "门诊人次",
  INPATIENT: "住院床日",
  COMBINED: "门诊+住院",
  OTHER: "手工人次"
};
const previousVersion = computed(() => {
  const currentId = selectedVersionId.value || governance.value?.activeVersion?.id || "";
  const index = versions.value.findIndex(version => version.id === currentId);
  return index >= 0 && index + 1 < versions.value.length ? versions.value[index + 1] : null;
});
const diffTitle = computed(() =>
  previousVersion.value && viewVersion.value
    ? `版本对比：${previousVersion.value.versionCode} → ${viewVersion.value.versionCode}`
    : "版本对比"
);
const diffSummary = computed(() => ({
  added: diffRows.value.filter(row => row.kind === "added").length,
  removed: diffRows.value.filter(row => row.kind === "removed").length,
  changed: diffRows.value.filter(row => row.kind === "changed").length
}));

const ruleKeyOf = (rule: InventoryQuotaRule) => `${rule.departmentKey}\u0000${rule.materialName.trim()}\u0000${rule.unit.trim()}`;
const scopeLabel = (scope: string) => scopeLabels[scope] || scope || "-";
const bindingLabel = (binding?: string | null) => (binding && bindingMeta[binding as InventoryQuotaBindingType]?.label) || binding || "-";
const bindingRowClass = ({ row }: { row: { bindingType?: string } }) => {
  const bt = row.bindingType;
  if (!bt || bt === "PER_PERSON") return "";
  return `binding-row binding-row-${bt.toLowerCase().replace(/_/g, "-")}`;
};

const buildDiffRows = (current: InventoryQuotaRule[], previous: InventoryQuotaRule[]): DiffRow[] => {
  const previousByKey = new Map(previous.map(rule => [ruleKeyOf(rule), rule]));
  const currentByKey = new Map(current.map(rule => [ruleKeyOf(rule), rule]));
  const rows: DiffRow[] = [];
  for (const rule of current) {
    const before = previousByKey.get(ruleKeyOf(rule));
    if (!before) {
      rows.push({ departmentName: rule.departmentName, materialName: rule.materialName, unit: rule.unit, kind: "added", changes: [] });
      continue;
    }
    const changes: DiffChange[] = [];
    if ((before.standardQuantity ?? null) !== (rule.standardQuantity ?? null))
      changes.push({ label: "每人次定额", from: before.standardQuantity ?? "未设", to: rule.standardQuantity ?? "未设" });
    if ((before.fixedAdjustment ?? 0) !== (rule.fixedAdjustment ?? 0))
      changes.push({ label: "固定调整", from: String(before.fixedAdjustment ?? 0), to: String(rule.fixedAdjustment ?? 0) });
    if (before.measurementScope !== rule.measurementScope)
      changes.push({ label: "计量范围", from: scopeLabel(before.measurementScope), to: scopeLabel(rule.measurementScope) });
    if ((before.bindingType || "PER_PERSON") !== (rule.bindingType || "PER_PERSON"))
      changes.push({ label: "绑定方式", from: bindingLabel(before.bindingType), to: bindingLabel(rule.bindingType) });
    if (before.serviceGroup !== rule.serviceGroup)
      changes.push({ label: "服务项目", from: before.serviceGroup || "-", to: rule.serviceGroup || "-" });
    if (before.enabled !== rule.enabled)
      changes.push({ label: "状态", from: before.enabled ? "启用" : "停用", to: rule.enabled ? "启用" : "停用" });
    if (changes.length)
      rows.push({ departmentName: rule.departmentName, materialName: rule.materialName, unit: rule.unit, kind: "changed", changes });
  }
  for (const rule of previous) {
    if (!currentByKey.has(ruleKeyOf(rule)))
      rows.push({ departmentName: rule.departmentName, materialName: rule.materialName, unit: rule.unit, kind: "removed", changes: [] });
  }
  return rows;
};

const openDiff = async () => {
  const base = previousVersion.value;
  if (!base) return;
  diffOpen.value = true;
  diffLoading.value = true;
  try {
    const previous = await loadVersion(base.id);
    diffRows.value = buildDiffRows(governance.value?.rules || [], previous.rules || []);
  } catch (error) {
    ElMessage.error((error as Error).message || "读取上一版本失败");
    diffRows.value = [];
  } finally {
    diffLoading.value = false;
  }
};

const auditOpen = ref(false);
const auditLoading = ref(false);
const auditRows = ref<InventoryQuotaAuditEntry[]>([]);
const auditTitle = computed(() =>
  viewVersion.value ? `定额变更记录（${viewVersion.value.versionCode}）` : "定额变更记录"
);
const formatAuditTime = (value: string) => value.replace("T", " ").slice(0, 19);
const auditNum = (value: number | null) => (value === null || value === undefined ? "未设" : String(value));
const auditChangesOf = (row: InventoryQuotaAuditEntry): DiffChange[] => {
  if (row.action === "CREATE" || row.action === "DELETE")
    return [{ label: "每人次定额", from: auditNum(row.beforeStandardQuantity), to: auditNum(row.afterStandardQuantity) }];
  const changes: DiffChange[] = [];
  if ((row.beforeStandardQuantity ?? null) !== (row.afterStandardQuantity ?? null))
    changes.push({ label: "每人次定额", from: auditNum(row.beforeStandardQuantity), to: auditNum(row.afterStandardQuantity) });
  if ((row.beforeFixedAdjustment ?? null) !== (row.afterFixedAdjustment ?? null))
    changes.push({ label: "固定调整", from: auditNum(row.beforeFixedAdjustment), to: auditNum(row.afterFixedAdjustment) });
  if ((row.beforeMeasurementScope ?? null) !== (row.afterMeasurementScope ?? null))
    changes.push({
      label: "计量范围",
      from: row.beforeMeasurementScope ? scopeLabel(row.beforeMeasurementScope) : "-",
      to: row.afterMeasurementScope ? scopeLabel(row.afterMeasurementScope) : "-"
    });
  if ((row.beforeBindingType ?? null) !== (row.afterBindingType ?? null))
    changes.push({ label: "绑定方式", from: bindingLabel(row.beforeBindingType), to: bindingLabel(row.afterBindingType) });
  if ((row.beforeEnabled ?? null) !== (row.afterEnabled ?? null))
    changes.push({
      label: "状态",
      from: row.beforeEnabled === null ? "-" : row.beforeEnabled ? "启用" : "停用",
      to: row.afterEnabled === null ? "-" : row.afterEnabled ? "启用" : "停用"
    });
  return changes;
};
const openAudit = async () => {
  auditOpen.value = true;
  auditLoading.value = true;
  try {
    const versionId = viewVersion.value?.id;
    auditRows.value = (await getInventoryQuotaAuditLogApi(versionId ? { versionId } : {})).data.list || [];
  } catch (error) {
    ElMessage.error((error as Error).message || "读取变更记录失败");
    auditRows.value = [];
  } finally {
    auditLoading.value = false;
  }
};

const departments = computed(() => {
  const values = new Map<string, string>(departmentDirectory.map(entry => [entry.key, entry.name]));
  rules.value.forEach(rule => values.set(rule.departmentKey, rule.departmentName));
  return [...values.entries()].map(([key, name]) => ({ key, name }));
});
const hasChanges = computed(
  () => dirtyIds.value.size > 0 || pendingCreates.value.length > 0 || pendingDeletes.value.size > 0
);
const visibleRows = computed<ConsoleRow[]>(() => {
  const search = keyword.value.trim().toLowerCase();
  const created: ConsoleRow[] = pendingCreates.value.map((create, index) => ({
    id: `pending-${index}`,
    versionId: selectedVersionId.value,
    departmentKey: create.departmentKey,
    departmentName: departmentDirectory.find(entry => entry.key === create.departmentKey)?.name || create.departmentKey,
    sourceRow: -1,
    serviceGroup: create.serviceGroup,
    careType: create.careType,
    materialName: create.materialName,
    unit: create.unit,
    standardQuantity: create.standardQuantity,
    fixedAdjustment: create.fixedAdjustment,
    measurementScope: create.measurementScope,
    bindingType: create.bindingType || "PER_PERSON",
    enabled: true,
    pending: true,
    rowKey: `pending-${index}`
  }));
  const existing: ConsoleRow[] = rules.value
    .filter(rule => !pendingDeletes.value.has(rule.id))
    .map(rule => ({ ...rule, bindingType: rule.bindingType || "PER_PERSON", rowKey: rule.id }));
  return [...created, ...existing].filter(row => {
    if (departmentKey.value && row.departmentKey !== departmentKey.value) return false;
    if (bindingFilter.value && row.bindingType !== bindingFilter.value) return false;
    return !search || `${row.materialName} ${row.serviceGroup}`.toLowerCase().includes(search);
  });
});

const loadVersion = (versionId?: string) =>
  getInventoryQuotaGovernanceApi(undefined, versionId).then(result => result.data);

const resetPending = () => {
  rules.value = (governance.value?.rules || []).map(rule => ({ ...rule }));
  pendingCreates.value = [];
  pendingDeletes.value = new Set();
  dirtyIds.value = new Set();
};

const load = async () => {
  loading.value = true;
  loadError.value = "";
  try {
    const first = await loadVersion();
    const future = [...(first.versions || [])]
      .filter(version => version.effectiveDate > today)
      .sort((a, b) => a.effectiveDate.localeCompare(b.effectiveDate))
      .pop();
    if (future) {
      governance.value = await loadVersion(future.id);
      selectedVersionId.value = future.id;
    } else {
      governance.value = first;
      selectedVersionId.value = first.activeVersion?.id || "";
    }
    resetPending();
  } catch (error) {
    loadError.value = (error as Error).message || "读取定额数据失败";
  } finally {
    loading.value = false;
  }
};

const reload = () => {
  if (dirtyIds.value.size || pendingCreates.value.length || pendingDeletes.value.size) {
    ElMessage.info("已撤销未保存的修改");
  }
  load();
};

const exportXlsx = async () => {
  exporting.value = true;
  try {
    const { blob, filename } = await downloadInventoryQuotaGovernanceXlsxApi({
      date: today,
      versionId: selectedVersionId.value || undefined
    });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) {
    ElMessage.error((error as Error).message || "定额总表导出失败");
  } finally {
    exporting.value = false;
  }
};

watch(selectedVersionId, async next => {
  if (!governance.value) return;
  const loadedId = viewVersion.value?.id;
  if (next === loadedId && governance.value.rules[0]?.versionId === next) return;
  if (!next) return;
  loading.value = true;
  try {
    governance.value = await loadVersion(next);
    resetPending();
  } catch (error) {
    ElMessage.error((error as Error).message || "切换定额版本失败");
  } finally {
    loading.value = false;
  }
});

const markDirty = (row: ConsoleRow) => {
  if (row.pending) return;
  const next = new Set(dirtyIds.value);
  next.add(row.id);
  dirtyIds.value = next;
};

const onBindingChange = (row: ConsoleRow) => {
  if ((row.bindingType === "ON_DEMAND" || row.bindingType === "EQUIPMENT") && row.standardQuantity !== null) {
    row.standardQuantity = null;
  }
  markDirty(row);
};

const openAdd = () => {
  createForm.value = {
    departmentKey: departmentKey.value || departmentDirectory[0].key,
    materialName: "",
    unit: "",
    serviceGroup: "",
    careType: "outpatient",
    standardQuantity: 0,
    fixedAdjustment: 0,
    measurementScope: "OUTPATIENT",
    bindingType: "PER_PERSON"
  };
  addOpen.value = true;
};

const appendCreate = () => {
  const form = createForm.value;
  if (!form.departmentKey || !form.materialName.trim() || !form.serviceGroup.trim()) {
    ElMessage.warning("请填写科室、耗材名称和服务项目");
    return;
  }
  const duplicate =
    pendingCreates.value.some(item => item.departmentKey === form.departmentKey && item.materialName === form.materialName.trim())
    || rules.value.some(
      rule => rule.departmentKey === form.departmentKey && rule.materialName === form.materialName.trim() && rule.unit === form.unit.trim()
    );
  if (duplicate) {
    ElMessage.warning("该科室已存在同名耗材的定额规则");
    return;
  }
  pendingCreates.value = [
    ...pendingCreates.value,
    {
      departmentKey: form.departmentKey,
      materialName: form.materialName.trim(),
      unit: form.unit.trim(),
      serviceGroup: form.serviceGroup.trim(),
      careType: form.careType,
      standardQuantity: form.bindingType === "ON_DEMAND" || form.bindingType === "EQUIPMENT" ? null : form.standardQuantity,
      fixedAdjustment: Number(form.fixedAdjustment || 0),
      measurementScope: form.measurementScope,
      bindingType: form.bindingType,
      enabled: true
    }
  ];
  addOpen.value = false;
  ElMessage.success("已加入待保存队列");
};

const cancelCreate = (row: ConsoleRow) => {
  pendingCreates.value = pendingCreates.value.filter((_, index) => `pending-${index}` !== row.rowKey);
};

const confirmDelete = async (row: ConsoleRow) => {
  try {
    await ElMessageBox.confirm(
      `删除「${row.departmentName} · ${row.materialName}」？保存后该耗材定额将从生效版本移除；当日未填报科室草稿中的对应行会一并清理，已填报科室的历史行将降级为补充行快照保留。`,
      "删除定额规则",
      { type: "warning", confirmButtonText: "标记删除", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  const next = new Set(pendingDeletes.value);
  next.add(row.id);
  pendingDeletes.value = next;
  dirtyIds.value = new Set([...dirtyIds.value].filter(id => id !== row.id));
};

const save = async () => {
  saving.value = true;
  try {
    const updates = rules.value
      .filter(rule => dirtyIds.value.has(rule.id) && !pendingDeletes.value.has(rule.id))
      .map(rule => ({
        id: rule.id,
        standardQuantity: rule.standardQuantity,
        fixedAdjustment: Number(rule.fixedAdjustment || 0),
        measurementScope: rule.measurementScope,
        bindingType: rule.bindingType || "PER_PERSON",
        enabled: rule.enabled
      }));
    const result = (
      await consoleSaveInventoryQuotaApi({
        versionId: isEditable.value ? viewVersion.value?.id : undefined,
        effectiveDate: isEditable.value ? undefined : new Date(Date.now() + 86400000).toISOString().slice(0, 10),
        updates,
        creates: pendingCreates.value,
        deletes: [...pendingDeletes.value],
        applyToday: applyToday.value
      })
    ).data;
    governance.value = result;
    selectedVersionId.value = result.savedVersionId || selectedVersionId.value;
    resetPending();
    emit("saved");
    if (result.applyTodayRequested && result.applyTodayResult) {
      ElMessage.success(
        `定额已保存（${result.savedVersionCode}，${result.savedEffectiveDate} 生效），并为 ${result.applyTodayResult.seededCount} 个未填报科室预播种当日草稿`
      );
    } else {
      ElMessage.success(`定额已保存（${result.savedVersionCode}，${result.savedEffectiveDate} 生效）`);
    }
  } catch (error) {
    ElMessage.error((error as Error).message || "保存定额失败");
  } finally {
    saving.value = false;
  }
};

onMounted(load);
</script>

<style scoped lang="scss">
.quota-console {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.clean-table {
  --el-table-border-color: #edf1f5;
  --el-table-header-bg-color: transparent;
  border-radius: 10px;
  overflow: hidden;
}
.clean-table :deep(th.el-table__cell) {
  background: transparent;
  color: var(--el-text-color-secondary);
  font-weight: 500;
  border-bottom: 1px solid #edf1f5;
}
.clean-table :deep(td.el-table__cell) {
  border-bottom: 1px solid #edf1f5;
  border-right: none;
}
.clean-table :deep(th.el-table__cell) {
  border-right: none;
}
.clean-table :deep(.el-table__row:hover > td.el-table__cell) {
  background: #f8fafc;
}
:deep(.binding-row) > td.el-table__cell {
  position: relative;
}
:deep(.binding-row) > td.el-table__cell:first-child {
  box-shadow: inset 5px 0 0 0 var(--binding-color, #ff9800);
}
:deep(.binding-row-fixed-daily) > td.el-table__cell {
  background: #fff3e0 !important;
  --binding-color: #ff9800;
}
:deep(.binding-row-on-demand) > td.el-table__cell {
  background: #e3f2fd !important;
  --binding-color: #2196f3;
}
:deep(.binding-row-equipment) > td.el-table__cell {
  background: #e8f5e9 !important;
  --binding-color: #4caf50;
}
:deep(.binding-row:hover) > td.el-table__cell {
  filter: brightness(0.95);
}

.binding-legend {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 6px 12px;
  margin-bottom: 8px;
  background: #f8fafc;
  border-radius: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.legend-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.legend-hint {
  margin-left: auto;
  color: var(--el-text-color-placeholder);
}

.diff-line {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}
.unit-suffix {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}
.dual-input-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.console-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
}

.version-info,
.console-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.version-item {
  display: grid;
  gap: 2px;

  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  strong {
    color: var(--el-text-color-primary);
    font-variant-numeric: tabular-nums;
  }
}

.version-select {
  width: min(240px, 100%);
}

.pending-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--el-color-warning-light-7);
  border-radius: 6px;
  background: var(--el-color-warning-light-9);
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.rule-filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.department-select {
  width: 180px;
}

.binding-select {
  width: 150px;
}

.form-hint {
  width: 100%;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.keyword-input {
  width: 240px;
}

.rule-count {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.row-tag {
  margin-left: 6px;
}

@media (max-width: 720px) {
  .department-select,
  .keyword-input,
  .version-select {
    width: 100%;
  }
}
</style>
