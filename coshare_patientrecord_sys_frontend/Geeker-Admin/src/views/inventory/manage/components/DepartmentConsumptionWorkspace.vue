<template>
  <section v-if="template" class="department-consumption-workspace">
    <div class="department-toolbar">
      <div>
        <h2>{{ template.department }}核算单</h2>
        <p>按原始科室用量表试算，保存的仅是核算草稿，不扣库存、不生成流水。</p>
      </div>
      <div class="toolbar-actions">
        <el-date-picker v-model="businessDate" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="loadDraft" />
        <el-tooltip content="恢复该科室的原始表格模板" placement="bottom">
          <el-button :icon="RefreshLeft" circle aria-label="恢复原始表格模板" @click="restoreTemplate" />
        </el-tooltip>
        <el-button type="primary" :loading="saving" :icon="DocumentChecked" @click="saveDraft">保存日草稿</el-button>
      </div>
    </div>

    <div class="department-workspace-grid">
      <section class="input-pane">
        <div class="pane-heading">
          <div>
            <h3>填写与修正</h3>
            <p>业务量按服务项目填写一次；明细行可单独覆盖。</p>
          </div>
          <el-button type="primary" plain :icon="Plus" @click="addLine">新增耗材</el-button>
        </div>

        <div class="volume-grid">
          <label v-for="group in serviceGroups" :key="group" class="volume-field">
            <span>{{ group }}</span>
            <el-input-number
              v-model="draft.groupVolumes[group]"
              :min="0"
              :precision="0"
              controls-position="right"
              @change="normalizeGroupVolume(group)"
            />
          </label>
          <label class="volume-field month-days">
            <span>本月天数</span>
            <el-input-number
              v-model="draft.monthDays"
              :min="1"
              :max="31"
              :precision="0"
              controls-position="right"
              @change="normalizeMonthDays"
            />
          </label>
        </div>

        <el-table :data="draft.lines" class="input-table" height="calc(100vh - 390px)" min-height="380" table-layout="fixed">
          <el-table-column label="服务项目 / 类型" min-width="180">
            <template #default="{ row }">
              <el-select v-model="row.serviceGroup" filterable allow-create default-first-option>
                <el-option v-for="group in serviceGroups" :key="group" :label="group" :value="group" />
              </el-select>
              <el-select v-model="row.careType" class="care-type-select">
                <el-option v-for="option in careTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="耗材" min-width="190">
            <template #default="{ row }">
              <el-select v-model="row.materialName" filterable allow-create default-first-option placeholder="选择或输入耗材">
                <el-option v-for="name in materialOptions" :key="name" :label="name" :value="name" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="单位" width="96">
            <template #default="{ row }"><el-input v-model="row.unit" placeholder="单位" /></template>
          </el-table-column>
          <el-table-column label="每人次定额" width="122">
            <template #default="{ row }"
              ><el-input-number v-model="row.standardQuantity" :min="0" :precision="6" controls-position="right"
            /></template>
          </el-table-column>
          <el-table-column label="行内人次" width="122">
            <template #default="{ row }"
              ><el-input-number
                v-model="row.volumeOverride"
                :min="0"
                :precision="0"
                controls-position="right"
                placeholder="跟随分组"
                @change="normalizeLineVolume(row)"
            /></template>
          </el-table-column>
          <el-table-column label="单价" width="116">
            <template #default="{ row }"
              ><el-input-number v-model="row.unitPrice" :min="0" :precision="4" controls-position="right"
            /></template>
          </el-table-column>
          <el-table-column width="54" align="center">
            <template #default="{ $index }">
              <el-tooltip content="删除本次草稿中的耗材行" placement="left">
                <el-button :icon="Delete" circle text type="danger" aria-label="删除耗材行" @click="removeLine($index)" />
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="preview-pane">
        <div class="pane-heading">
          <div>
            <h3>实时预览</h3>
            <p>原表计算口径：标准用量 × 业务量 × 本月天数。</p>
          </div>
          <span class="draft-state">{{ draft.revision ? `已保存 v${draft.revision}` : "未保存" }}</span>
        </div>

        <div class="preview-summary">
          <span
            ><small>耗材行</small><strong>{{ previewRows.length }}</strong></span
          >
          <span
            ><small>日使用量（按单位）</small
            ><strong class="unit-summary">{{ dailyQuantitySummary || "暂无可汇总数量" }}</strong></span
          >
          <span
            ><small>月使用量（按单位）</small
            ><strong class="unit-summary">{{ monthlyQuantitySummary || "暂无可汇总数量" }}</strong></span
          >
          <span
            ><small>待核定/无单位</small><strong>{{ excludedQuantityLineCount }} 行</strong></span
          >
          <span
            ><small>已核价月金额</small
            ><strong>{{ pricedMonthlyAmount === null ? "未核价" : formatMoney(pricedMonthlyAmount) }}</strong></span
          >
        </div>

        <el-table :data="previewRows" class="preview-table" height="calc(100vh - 390px)" min-height="380" table-layout="fixed">
          <el-table-column prop="serviceGroup" label="服务项目" min-width="140" show-overflow-tooltip />
          <el-table-column prop="materialName" label="耗材" min-width="178" show-overflow-tooltip />
          <el-table-column prop="standardQuantity" label="标准用量" width="104">
            <template #default="{ row }">{{
              row.standardQuantity === null ? "待核定" : formatQuantity(row.standardQuantity)
            }}</template>
          </el-table-column>
          <el-table-column prop="volume" label="业务量" width="88" />
          <el-table-column prop="dailyQuantity" label="日使用量" width="104">
            <template #default="{ row }">{{ formatQuantity(row.dailyQuantity) }}</template>
          </el-table-column>
          <el-table-column prop="monthlyQuantity" label="月使用量" width="104">
            <template #default="{ row }">{{ formatQuantity(row.monthlyQuantity) }}</template>
          </el-table-column>
          <el-table-column prop="monthlyAmount" label="月金额" width="104">
            <template #default="{ row }">{{ row.monthlyAmount === null ? "未核价" : formatMoney(row.monthlyAmount) }}</template>
          </el-table-column>
          <el-table-column label="库存关联" width="118">
            <template #default="{ row }">
              <el-tag v-if="row.stock !== null" effect="plain" type="success">库存 {{ formatQuantity(row.stock) }}</el-tag>
              <el-tag v-else effect="plain" type="info">待关联</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { Delete, DocumentChecked, Plus, RefreshLeft } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import {
  getInventoryDepartmentDailyDraftApi,
  saveInventoryDepartmentDailyDraftApi,
  type InventoryBatch,
  type InventoryDepartmentDailyDraft,
  type InventoryDepartmentDraftCareType,
  type InventoryDepartmentDraftLine,
  type InventoryItem
} from "@/api/modules/inventory";
import { departmentTemplateByKey, type DepartmentTemplate, type DepartmentTemplateLine } from "../departmentConsumptionTemplates";

type DraftState = Required<Pick<InventoryDepartmentDailyDraft, "monthDays" | "revision" | "groupVolumes" | "lines">>;
type PreviewRow = InventoryDepartmentDraftLine & {
  volume: number;
  dailyQuantity: number;
  monthlyQuantity: number;
  monthlyAmount: number | null;
  stock: number | null;
};

const props = defineProps<{
  departmentKey: string;
  items: InventoryItem[];
  batches: InventoryBatch[];
  today: string;
}>();

const businessDate = ref(props.today);
const saving = ref(false);
const latestLoadKey = ref("");
const template = computed(() => departmentTemplateByKey.get(props.departmentKey));
const careTypeOptions: { label: string; value: InventoryDepartmentDraftCareType }[] = [
  { label: "门诊", value: "outpatient" },
  { label: "住院", value: "inpatient" },
  { label: "其他", value: "other" }
];

const lineFromTemplate = (line: DepartmentTemplateLine): InventoryDepartmentDraftLine => ({
  id: `source-${line.sourceRow}`,
  sourceRow: line.sourceRow,
  serviceGroup: line.serviceGroup,
  careType: line.careType,
  materialName: line.materialName,
  unit: line.unit,
  standardQuantity: line.standardQuantity,
  unitPrice: null,
  volumeOverride: null
});

const blankState = (source: DepartmentTemplate): DraftState => ({
  monthDays: source.monthDays,
  revision: 0,
  groupVolumes: Object.fromEntries(
    source.lines.reduce((groups, line) => groups.set(line.serviceGroup, line.defaultVolume), new Map<string, number>())
  ),
  lines: source.lines.map(lineFromTemplate)
});

const draft = ref<DraftState>(
  template.value ? blankState(template.value) : { monthDays: 30, revision: 0, groupVolumes: {}, lines: [] }
);
const serviceGroups = computed(() => [...new Set(draft.value.lines.map(line => line.serviceGroup).filter(Boolean))]);
const materialOptions = computed(() =>
  [...new Set(props.items.map(item => item.name).filter(Boolean))].sort((left, right) => left.localeCompare(right, "zh-CN"))
);
const itemByName = computed(() => new Map(props.items.map(item => [item.name, item])));
const stockByItemId = computed(() => {
  const result = new Map<string, number>();
  props.batches.forEach(batch =>
    result.set(batch.itemId, Number(((result.get(batch.itemId) || 0) + Number(batch.quantity || 0)).toFixed(6)))
  );
  return result;
});
const nonNegativeInteger = (value: unknown) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(0, Math.floor(parsed)) : 0;
};
const volumeFor = (line: InventoryDepartmentDraftLine) =>
  nonNegativeInteger(line.volumeOverride ?? draft.value.groupVolumes[line.serviceGroup] ?? 0);
const calculateQuantity = (quantity: number | null, volume: number) => Number(((quantity || 0) * volume).toFixed(6));
const previewRows = computed<PreviewRow[]>(() =>
  draft.value.lines.map(line => {
    const volume = volumeFor(line);
    const dailyQuantity = calculateQuantity(line.standardQuantity, volume);
    const monthlyQuantity = Number((dailyQuantity * draft.value.monthDays).toFixed(2));
    const monthlyAmount =
      line.unitPrice === null || line.unitPrice === undefined ? null : Number((monthlyQuantity * line.unitPrice).toFixed(2));
    const item = itemByName.value.get(line.materialName);
    return {
      ...line,
      volume,
      dailyQuantity,
      monthlyQuantity,
      monthlyAmount,
      stock: item ? stockByItemId.value.get(item.id) || 0 : null
    };
  })
);
const quantitySummary = (field: "dailyQuantity" | "monthlyQuantity") => {
  const totals = new Map<string, number>();
  previewRows.value.forEach(row => {
    const unit = row.unit.trim();
    if (row.standardQuantity === null || !unit) return;
    totals.set(unit, Number(((totals.get(unit) || 0) + row[field]).toFixed(6)));
  });
  return [...totals.entries()].map(([unit, quantity]) => `${formatQuantity(quantity)} ${unit}`).join(" · ");
};
const dailyQuantitySummary = computed(() => quantitySummary("dailyQuantity"));
const monthlyQuantitySummary = computed(() => quantitySummary("monthlyQuantity"));
const excludedQuantityLineCount = computed(
  () => previewRows.value.filter(row => row.standardQuantity === null || !row.unit.trim()).length
);
const pricedMonthlyAmount = computed(() =>
  previewRows.value.some(row => row.monthlyAmount === null)
    ? null
    : Number(previewRows.value.reduce((sum, row) => sum + Number(row.monthlyAmount || 0), 0).toFixed(2))
);

const restoreTemplate = () => {
  if (!template.value) return;
  draft.value = blankState(template.value);
  ElMessage.info("已恢复原始表格模板，保存后才会覆盖当前日草稿");
};

const normalizeGroupVolume = (group: string) => {
  draft.value.groupVolumes[group] = nonNegativeInteger(draft.value.groupVolumes[group]);
};

const normalizeLineVolume = (line: { volumeOverride?: number | null }) => {
  if (line.volumeOverride !== null && line.volumeOverride !== undefined)
    line.volumeOverride = nonNegativeInteger(line.volumeOverride);
};

const normalizeMonthDays = () => {
  draft.value.monthDays = Math.min(31, Math.max(1, nonNegativeInteger(draft.value.monthDays)));
};

const addLine = () => {
  const group = serviceGroups.value[0] || "门诊患者";
  if (!(group in draft.value.groupVolumes)) draft.value.groupVolumes[group] = 0;
  draft.value.lines.push({
    id: `manual-${Date.now()}-${draft.value.lines.length}`,
    serviceGroup: group,
    careType: "other",
    materialName: "",
    unit: "",
    standardQuantity: 0,
    unitPrice: null,
    volumeOverride: null
  });
};

const removeLine = (index: number) => draft.value.lines.splice(index, 1);
const formatQuantity = (value: number) => Number(value || 0).toLocaleString("zh-CN", { maximumFractionDigits: 6 });
const formatMoney = (value: number) =>
  `¥${Number(value || 0).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

const loadDraft = async () => {
  if (!template.value || !businessDate.value) return;
  const requestKey = `${template.value.key}:${businessDate.value}`;
  latestLoadKey.value = requestKey;
  try {
    const response = await getInventoryDepartmentDailyDraftApi({ departmentKey: template.value.key, date: businessDate.value });
    if (latestLoadKey.value !== requestKey) return;
    const saved = response.data;
    if (!saved.exists) {
      draft.value = blankState(template.value);
      return;
    }
    draft.value = {
      monthDays: saved.monthDays || template.value.monthDays,
      revision: saved.revision || 0,
      groupVolumes: { ...blankState(template.value).groupVolumes, ...(saved.groupVolumes || {}) },
      lines: (saved.lines || []).map(line => ({
        ...line,
        unitPrice: line.unitPrice ?? null,
        volumeOverride: line.volumeOverride ?? null
      }))
    };
  } catch (error) {
    if (latestLoadKey.value !== requestKey) return;
    ElMessage.error(error instanceof Error ? error.message : "读取科室日草稿失败");
  }
};

const saveDraft = async () => {
  if (!template.value) return;
  saving.value = true;
  try {
    const response = await saveInventoryDepartmentDailyDraftApi({
      departmentKey: template.value.key,
      departmentName: template.value.department,
      businessDate: businessDate.value,
      templateVersion: "xlsx-20260808",
      monthDays: draft.value.monthDays,
      revision: draft.value.revision,
      groupVolumes: draft.value.groupVolumes,
      lines: draft.value.lines
    });
    draft.value.revision = response.data.revision;
    ElMessage.success("科室耗材日草稿已保存，未扣减库存");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存科室日草稿失败");
  } finally {
    saving.value = false;
  }
};

watch(
  () => props.today,
  today => {
    if (today && businessDate.value !== today) businessDate.value = today;
  },
  { immediate: true }
);

watch(
  [() => props.departmentKey, businessDate],
  ([key]) => {
    if (key) void loadDraft();
  },
  { immediate: true }
);
</script>

<style scoped lang="scss">
.department-consumption-workspace {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.department-toolbar,
.toolbar-actions,
.pane-heading {
  display: flex;
  align-items: center;
  gap: 12px;
}
.department-toolbar,
.pane-heading {
  justify-content: space-between;
  flex-wrap: wrap;
}
.department-toolbar h2,
.pane-heading h3 {
  margin: 0;
  color: var(--inventory-text);
}
.department-toolbar h2 {
  font-size: 20px;
}
.pane-heading h3 {
  font-size: 16px;
}
.department-toolbar p,
.pane-heading p {
  margin: 5px 0 0;
  color: var(--inventory-muted);
  font-size: 13px;
}
.toolbar-actions {
  flex-wrap: wrap;
}
.department-workspace-grid {
  display: grid;
  grid-template-columns: minmax(460px, 0.95fr) minmax(560px, 1.35fr);
  gap: 18px;
  min-width: 0;
}
.input-pane,
.preview-pane {
  display: grid;
  gap: 14px;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--inventory-line);
  border-radius: 6px;
  background: #fff;
}
.volume-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 12px;
  max-height: 150px;
  overflow: auto;
  padding-right: 4px;
}
.volume-field {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px;
  align-items: center;
  gap: 8px;
  color: var(--inventory-text);
  font-size: 13px;
}
.volume-field :deep(.el-input-number) {
  width: 100%;
}
.month-days {
  border-top: 1px solid var(--inventory-line);
  padding-top: 8px;
}
.care-type-select {
  margin-top: 6px;
}
.input-table :deep(.el-input-number),
.input-table :deep(.el-select) {
  width: 100%;
}
.preview-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  border-block: 1px solid var(--inventory-line);
}
.preview-summary span {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border-right: 1px solid var(--inventory-line);
}
.preview-summary span:last-child {
  border-right: 0;
}
.preview-summary small {
  color: var(--inventory-muted);
  font-size: 12px;
}
.preview-summary strong {
  color: var(--inventory-text);
  font-size: 17px;
  font-weight: 600;
}
.preview-summary .unit-summary {
  font-size: 14px;
  line-height: 1.45;
  word-break: break-word;
}
.draft-state {
  color: var(--inventory-muted);
  font-size: 13px;
}
@media (max-width: 1240px) {
  .department-workspace-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 680px) {
  .volume-grid,
  .preview-summary {
    grid-template-columns: 1fr;
  }
  .preview-summary span {
    border-right: 0;
    border-bottom: 1px solid var(--inventory-line);
  }
  .preview-summary span:last-child {
    border-bottom: 0;
  }
}
</style>
