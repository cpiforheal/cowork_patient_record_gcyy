<template>
  <section class="weekly-workbench">
    <el-tabs v-model="activeSection" class="weekly-tabs">
      <el-tab-pane label="每患者标准量" name="standards">
        <div class="panel weekly-section">
          <div class="panel-head">
            <div>
              <h2>周度标准清单</h2>
              <p>维护门诊 / 住院计划患者数、每患者标准耗材量和单位换算，发布后用于生成不可变周度快照。</p>
            </div>
            <el-button v-if="canApprove" type="primary" :icon="Plus" @click="openStandardDialog()">新建标准</el-button>
          </div>
          <el-table :data="standards" border>
            <el-table-column prop="name" label="标准名称" min-width="180" />
            <el-table-column prop="standardCode" label="编码" width="130" />
            <el-table-column label="版本" width="80">
              <template #default="{ row }">v{{ row.version }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="standardStatusTag(row.status)" effect="light">{{ standardStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="effectiveWeek" label="生效周" width="120" />
            <el-table-column prop="expiresWeek" label="失效周" width="120" />
            <el-table-column label="清单行" width="90">
              <template #default="{ row }">{{ row.lineCount || row.lines?.length || 0 }} 项</template>
            </el-table-column>
            <el-table-column label="操作" width="210" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="viewStandard(row as InventoryWeeklyStandard)">查看</el-button>
                <el-button
                  v-if="canApprove && row.status === 'DRAFT'"
                  link
                  type="primary"
                  @click="openStandardDialog(row as InventoryWeeklyStandard)"
                >
                  编辑
                </el-button>
                <el-button
                  v-if="canApprove && row.status === 'DRAFT'"
                  link
                  type="success"
                  @click="emit('publishStandard', row as InventoryWeeklyStandard)"
                >
                  发布
                </el-button>
                <el-button
                  v-if="canApprove && row.status === 'DRAFT'"
                  link
                  type="danger"
                  @click="emit('deleteStandard', row as InventoryWeeklyStandard)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="暂无周度标准" /></template>
          </el-table>
        </div>
      </el-tab-pane>

      <el-tab-pane label="周度快照" name="snapshots">
        <div class="panel weekly-section">
          <div class="panel-head">
            <div>
              <h2>周度库存快照</h2>
              <p>按患者量预估和关键阶段实际扣减聚合 expected / actual / adjusted 差异，确认后作为审计口径保留。</p>
            </div>
            <div class="snapshot-tools">
              <el-date-picker v-model="snapshotWeek" value-format="YYYY-[W]ww" type="week" format="YYYY 第 ww 周" />
              <el-select v-model="snapshotDepartmentId" clearable filterable placeholder="科室">
                <el-option v-for="item in departmentOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
              <el-button
                :icon="Refresh"
                :loading="loading"
                @click="emit('refreshWeekly', { weekNo: snapshotWeek, departmentId: snapshotDepartmentId })"
              >
                查询
              </el-button>
              <el-button v-if="canCount" type="primary" :icon="Plus" :loading="loading" @click="generateSnapshot"
                >生成快照</el-button
              >
            </div>
          </div>
          <el-table :data="snapshots" border>
            <el-table-column type="expand" width="44">
              <template #default="{ row }">
                <div class="snapshot-detail">
                  <div class="snapshot-detail-title">
                    周度对账明细
                    <el-tag :type="snapshotStatusTag(row.status)" effect="plain" size="small">{{
                      snapshotStatusLabel(row.status)
                    }}</el-tag>
                  </div>
                  <el-table v-if="row.lines?.length" :data="row.lines" border size="small">
                    <el-table-column prop="itemName" label="物资" min-width="150" />
                    <el-table-column label="类型" width="80">
                      <template #default="{ row: line }">{{
                        careTypeLabel(line.careType || line.sourceSummary?.careType)
                      }}</template>
                    </el-table-column>
                    <el-table-column label="患者数" width="110">
                      <template #default="{ row: line }">
                        {{ formatQty(linePatientVolume(line)) }}
                        <span class="muted">/{{ formatQty(linePlannedPatientVolume(line)) }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="每患者标准" width="120">
                      <template #default="{ row: line }">{{ formatQty(linePerPatientStandard(line)) }}</template>
                    </el-table-column>
                    <el-table-column label="预估量" width="100">
                      <template #default="{ row: line }">{{ formatQty(line.expectedQuantity) }}</template>
                    </el-table-column>
                    <el-table-column label="实际量" width="100">
                      <template #default="{ row: line }">{{ formatQty(lineActualConsumed(line)) }}</template>
                    </el-table-column>
                    <el-table-column label="差异" width="100">
                      <template #default="{ row: line }">
                        <span :class="{ negative: Number(line.expectedActualVariance || 0) < 0 }">{{
                          formatQty(line.expectedActualVariance)
                        }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="建议补领" width="100">
                      <template #default="{ row: line }">{{ formatQty(line.suggestedQuantity) }}</template>
                    </el-table-column>
                    <el-table-column label="人工调整" width="100">
                      <template #default="{ row: line }">{{ formatQty(line.adjustedQuantity) }}</template>
                    </el-table-column>
                    <el-table-column label="审计来源" min-width="220" show-overflow-tooltip>
                      <template #default="{ row: line }">{{ lineSourceSummary(line) }}</template>
                    </el-table-column>
                  </el-table>
                  <el-empty v-else description="点击“明细”加载快照明细" />
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="weekNo" label="周次" width="120" />
            <el-table-column prop="departmentName" label="科室" min-width="140" />
            <el-table-column label="版本" width="80">
              <template #default="{ row }">R{{ row.revision }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.validityStatus === 'INVALIDATED'" type="danger" effect="light">已失效</el-tag>
                <el-tag v-else :type="snapshotStatusTag(row.status)" effect="light">{{ snapshotStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="invalidatedReason" label="失效原因" min-width="160" show-overflow-tooltip />
            <el-table-column label="预估量" width="110">
              <template #default="{ row }">{{ formatQty(row.totalExpectedQuantity) }}</template>
            </el-table-column>
            <el-table-column label="实际量" width="110">
              <template #default="{ row }">{{ formatQty(row.totalActualConsumedQuantity) }}</template>
            </el-table-column>
            <el-table-column label="差异" width="110">
              <template #default="{ row }">
                <span :class="{ negative: snapshotVariance(row) < 0 }">{{ formatQty(snapshotVariance(row)) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="调整" width="110">
              <template #default="{ row }">{{ formatQty(row.totalAdjustedQuantity) }}</template>
            </el-table-column>
            <el-table-column prop="confirmedAt" label="确认时间" width="160" show-overflow-tooltip />
            <el-table-column label="操作" width="330" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="emit('viewSnapshot', row as InventoryWeeklySnapshot)">明细</el-button>
                <el-button
                  v-if="canApprove && row.status === 'DRAFT' && row.validityStatus !== 'INVALIDATED'"
                  link
                  type="success"
                  @click="emit('confirmSnapshot', row as InventoryWeeklySnapshot)"
                >
                  确认
                </el-button>
                <el-button v-if="canApprove" link type="warning" @click="openRevise(row as InventoryWeeklySnapshot)"
                  >更正</el-button
                >
                <el-dropdown
                  v-if="canExport"
                  @command="
                    format => emit('exportSnapshot', row as InventoryWeeklySnapshot, format as InventoryWeeklyExportFormat)
                  "
                >
                  <el-button link type="primary" :icon="Download">导出</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="xlsx">XLSX</el-dropdown-item>
                      <el-dropdown-item command="pdf">PDF</el-dropdown-item>
                      <el-dropdown-item command="docx">DOCX</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </template>
            </el-table-column>
            <template #empty><el-empty description="暂无周度快照" /></template>
          </el-table>
        </div>

        <el-collapse class="legacy-collapse">
          <el-collapse-item title="历史兼容数据（旧周计划记录）" name="legacy">
            <p class="legacy-description">保留原有科室填报数据，便于和周度快照口径核对。</p>
            <el-table :data="rows" border max-height="260">
              <el-table-column prop="weekNo" label="周次" width="120" />
              <el-table-column prop="department" label="科室" width="120" />
              <el-table-column prop="itemName" label="物资" min-width="150" />
              <el-table-column prop="actualConsumedQuantity" label="实际耗用" width="100" />
              <el-table-column prop="remainingQuantity" label="剩余" width="90" />
              <el-table-column prop="suggestedQuantity" label="建议" width="90" />
              <el-table-column prop="adjustedQuantity" label="调整" width="90" />
              <el-table-column prop="abnormalReason" label="异常说明" min-width="180" />
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="standardDialogVisible"
      :title="editingStandardId ? '编辑周度标准' : '新建周度标准'"
      width="980px"
      destroy-on-close
    >
      <el-form ref="standardFormRef" :model="standardForm" :rules="standardRules" label-width="96px" status-icon>
        <div class="standard-form-grid">
          <el-form-item label="名称" prop="name">
            <el-input v-model="standardForm.name" placeholder="例如：肛肠科周度耗材标准" />
          </el-form-item>
          <el-form-item label="编码">
            <el-input v-model="standardForm.standardCode" placeholder="STD-WEEKLY" />
          </el-form-item>
          <el-form-item label="生效周" prop="effectiveWeek">
            <el-date-picker v-model="standardForm.effectiveWeek" value-format="YYYY-[W]ww" type="week" format="YYYY 第 ww 周" />
          </el-form-item>
          <el-form-item label="失效周">
            <el-date-picker
              v-model="standardForm.expiresWeek"
              clearable
              value-format="YYYY-[W]ww"
              type="week"
              format="YYYY 第 ww 周"
            />
          </el-form-item>
        </div>
        <div class="line-head">
          <strong>标准清单行</strong>
          <el-button link type="primary" :icon="Plus" @click="addStandardLine">添加行</el-button>
        </div>
        <div v-for="(line, index) in standardForm.lines" :key="line.localId" class="standard-line-editor">
          <el-select v-model="line.departmentId" filterable placeholder="科室">
            <el-option
              v-for="department in departmentOptions"
              :key="department.value"
              :label="department.label"
              :value="department.value"
            />
          </el-select>
          <el-select v-model="line.itemId" filterable placeholder="物资" @change="syncLineUnit(line)">
            <el-option v-for="item in items" :key="item.id" :label="`${item.name} / ${item.unit}`" :value="item.id" />
          </el-select>
          <el-select v-model="line.careType" placeholder="类型">
            <el-option label="门诊" value="outpatient" />
            <el-option label="住院" value="inpatient" />
          </el-select>
          <el-input-number v-model="line.businessVolume" :min="0" :precision="0" controls-position="right" />
          <el-input-number v-model="line.standardQuantity" :min="0" :precision="2" controls-position="right" />
          <el-input-number v-model="line.conversionFactor" :min="0.0001" :precision="4" controls-position="right" />
          <el-input-number v-model="line.safetyStockQuantity" :min="0" :precision="2" controls-position="right" />
          <el-button circle text type="danger" :icon="Delete" aria-label="删除标准行" @click="removeStandardLine(index)" />
        </div>
        <div class="line-labels">
          <span>科室</span><span>物资</span><span>类型</span><span>计划患者数</span><span>每患者标准量</span><span>换算系数</span
          ><span>安全库存</span><span />
        </div>
      </el-form>
      <template #footer>
        <el-button @click="standardDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitStandard">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reviseDialogVisible" title="更正周度快照" width="900px" destroy-on-close>
      <el-form label-width="92px">
        <el-form-item label="更正原因" required>
          <el-input v-model="revisionReason" type="textarea" :rows="3" placeholder="说明本次 revision 的业务原因" />
        </el-form-item>
        <el-table :data="revisionLines" border max-height="360">
          <el-table-column prop="itemName" label="物资" min-width="160" />
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ careTypeLabel(row.careType || row.sourceSummary?.careType) }}</template>
          </el-table-column>
          <el-table-column label="患者数" width="110">
            <template #default="{ row }">{{ formatQty(linePatientVolume(row)) }}</template>
          </el-table-column>
          <el-table-column label="预估/实际" width="120">
            <template #default="{ row }"
              >{{ formatQty(row.expectedQuantity) }} / {{ formatQty(lineActualConsumed(row)) }}</template
            >
          </el-table-column>
          <el-table-column label="系统建议" width="110">
            <template #default="{ row }">{{ formatQty(row.suggestedQuantity) }}</template>
          </el-table-column>
          <el-table-column label="原调整" width="110">
            <template #default="{ row }">{{ formatQty(row.originalAdjustedQuantity) }}</template>
          </el-table-column>
          <el-table-column label="新调整" width="150">
            <template #default="{ row }">
              <el-input-number v-model="row.adjustedQuantity" :min="0" :precision="2" controls-position="right" />
            </template>
          </el-table-column>
          <el-table-column label="原因" min-width="220">
            <template #default="{ row }">
              <el-input v-model="row.adjustmentReason" placeholder="可逐项说明" />
            </template>
          </el-table-column>
        </el-table>
      </el-form>
      <template #footer>
        <el-button @click="reviseDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitRevision">提交更正</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { Delete, Download, Plus, Refresh } from "@element-plus/icons-vue";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import type {
  InventoryCareType,
  InventoryItem,
  InventoryWeeklyExportFormat,
  InventoryWeeklySnapshot,
  InventoryWeeklySnapshotLine,
  InventoryWeeklyStandard,
  InventoryWeeklyStandardLine,
  SaveInventoryWeeklyStandardParams,
  WeeklyConsumption
} from "@/api/modules/inventory";

type WeeklyRow = WeeklyConsumption & { itemName: string };
type DepartmentOption = { value: string; label: string };
type LooseWeeklyLine = Partial<InventoryWeeklySnapshotLine> & Record<string, unknown>;
type LooseWeeklySnapshot = Partial<InventoryWeeklySnapshot> & Record<string, unknown>;
type EditableStandardLine = {
  localId: string;
  departmentId: string;
  itemId: string;
  careType: InventoryCareType;
  businessVolume: number;
  standardQuantity: number;
  standardUnit: string;
  conversionFactor: number;
  baseUnit: string;
  safetyStockQuantity: number;
};
type RevisionLine = InventoryWeeklySnapshotLine & {
  originalAdjustedQuantity: number;
  adjustedQuantity: number;
  adjustmentReason: string;
};

const props = defineProps<{
  rows: WeeklyRow[];
  standards: InventoryWeeklyStandard[];
  snapshots: InventoryWeeklySnapshot[];
  items: InventoryItem[];
  departmentOptions: DepartmentOption[];
  currentWeekNo: string;
  canApprove: boolean;
  canCount: boolean;
  canExport: boolean;
  saving: boolean;
  loading: boolean;
}>();

const emit = defineEmits<{
  refreshWeekly: [filters: { weekNo?: string; departmentId?: string }];
  saveStandard: [payload: SaveInventoryWeeklyStandardParams];
  publishStandard: [row: InventoryWeeklyStandard];
  deleteStandard: [row: InventoryWeeklyStandard];
  generateSnapshot: [payload: { weekNo: string; departmentId?: string }];
  viewSnapshot: [row: InventoryWeeklySnapshot];
  confirmSnapshot: [row: InventoryWeeklySnapshot];
  reviseSnapshot: [payload: { snapshot: InventoryWeeklySnapshot; revisionReason: string; lines: RevisionLine[] }];
  exportSnapshot: [row: InventoryWeeklySnapshot, format: InventoryWeeklyExportFormat];
}>();

const standardFormRef = ref<FormInstance>();
const activeSection = ref("standards");
const standardDialogVisible = ref(false);
const reviseDialogVisible = ref(false);
const editingStandardId = ref("");
const snapshotWeek = ref(props.currentWeekNo);
const snapshotDepartmentId = ref("");
const revisingSnapshot = ref<InventoryWeeklySnapshot>();
const revisionReason = ref("");
const revisionLines = ref<RevisionLine[]>([]);
const standardForm = reactive({
  name: "",
  standardCode: "STD-WEEKLY",
  effectiveWeek: props.currentWeekNo,
  expiresWeek: "",
  hospitalTimezone: "Asia/Shanghai",
  lines: [] as EditableStandardLine[]
});
const standardRules = reactive<FormRules>({
  name: [{ required: true, message: "请输入标准名称", trigger: "blur" }],
  effectiveWeek: [{ required: true, message: "请选择生效周", trigger: "change" }]
});

watch(
  () => props.currentWeekNo,
  value => {
    if (!snapshotWeek.value) snapshotWeek.value = value;
    if (!standardForm.effectiveWeek) standardForm.effectiveWeek = value;
  }
);

const itemMap = computed(() => new Map(props.items.map(item => [item.id, item])));
const formatQty = (value?: number | string) => Number(value || 0).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
const standardStatusLabel = (value: string) => ({ DRAFT: "草稿", PUBLISHED: "已发布", RETIRED: "已归档" })[value] || value;
const standardStatusTag = (value: string) =>
  ({ DRAFT: "info", PUBLISHED: "success", RETIRED: "warning" })[value] as "info" | "success" | "warning";
const snapshotStatusLabel = (value: string) => ({ DRAFT: "草稿", CONFIRMED: "已确认", REVISED: "已更正" })[value] || value;
const snapshotStatusTag = (value: string) =>
  ({ DRAFT: "info", CONFIRMED: "success", REVISED: "warning" })[value] as "info" | "success" | "warning";
const careTypeLabel = (value?: unknown) => (value === "inpatient" || value === "住院" ? "住院" : "门诊");
const sourceSummary = (line: LooseWeeklyLine) => (line.sourceSummary || {}) as Record<string, unknown>;
const sourceNumber = (line: LooseWeeklyLine, field: string, fallback = 0) => Number(sourceSummary(line)[field] ?? fallback ?? 0);
const linePatientVolume = (line: LooseWeeklyLine) =>
  sourceNumber(line, "actualPatientVolume", sourceNumber(line, "actualBusinessVolume"));
const linePlannedPatientVolume = (line: LooseWeeklyLine) =>
  sourceNumber(line, "plannedPatientVolume", sourceNumber(line, "businessVolume"));
const linePerPatientStandard = (line: LooseWeeklyLine) =>
  sourceNumber(line, "perPatientStandardQuantity", sourceNumber(line, "standardQuantity"));
const lineActualConsumed = (line: LooseWeeklyLine) => Number(line.consumedQuantity || 0) - Number(line.reversalQuantity || 0);
const snapshotVariance = (row: LooseWeeklySnapshot) =>
  Number(row.totalExpectedQuantity || 0) - Number(row.totalActualConsumedQuantity || 0);
const lineSourceSummary = (line: LooseWeeklyLine) => {
  const source = sourceSummary(line);
  const patientSource = source.patientVolumeSource === "pre_ai_encounters" ? "患者登记" : "计划患者数";
  const eventVolume = Number(source.consumptionEventVolume || 0);
  const flag = source.varianceFlag ? `；异常：${source.varianceFlag}` : "";
  return `${patientSource}；实际患者 ${formatQty(linePatientVolume(line))}，计划 ${formatQty(linePlannedPatientVolume(line))}，扣减流水 ${eventVolume}${flag}`;
};
const newStandardLine = (): EditableStandardLine => ({
  localId: `${Date.now()}-${Math.random()}`,
  departmentId: props.departmentOptions[0]?.value || "",
  itemId: props.items[0]?.id || "",
  careType: "outpatient",
  businessVolume: 1,
  standardQuantity: 1,
  standardUnit: props.items[0]?.unit || "",
  conversionFactor: 1,
  baseUnit: props.items[0]?.unit || "",
  safetyStockQuantity: 0
});
const linePolicy = (line: InventoryWeeklyStandardLine) => line.linePolicy || ({} as InventoryWeeklyStandardLine["linePolicy"]);
const viewStandard = (row: InventoryWeeklyStandard) => openStandardDialog(row);
const openStandardDialog = (row?: InventoryWeeklyStandard) => {
  editingStandardId.value = row?.status === "DRAFT" ? row.id : "";
  const lines = row?.lines?.length
    ? row.lines.map(line => ({
        localId: `${line.id || line.itemId}-${Date.now()}`,
        departmentId: line.departmentId,
        itemId: line.itemId,
        careType: linePolicy(line)?.careType || line.careType || "outpatient",
        businessVolume: Number(
          linePolicy(line)?.plannedPatientVolume ?? linePolicy(line)?.businessVolume ?? line.businessVolume ?? 1
        ),
        standardQuantity: Number(linePolicy(line)?.standardQuantity ?? line.standardQuantity ?? line.expectedQuantity ?? 1),
        standardUnit:
          linePolicy(line)?.standardUnit || line.standardUnit || line.itemUnit || itemMap.value.get(line.itemId)?.unit || "",
        conversionFactor: Number(linePolicy(line)?.conversionFactor ?? line.conversionFactor ?? 1),
        baseUnit: linePolicy(line)?.baseUnit || line.baseUnit || line.itemUnit || itemMap.value.get(line.itemId)?.unit || "",
        safetyStockQuantity: Number(linePolicy(line)?.safetyStockQuantity ?? line.safetyStockQuantity ?? 0)
      }))
    : [newStandardLine()];
  Object.assign(standardForm, {
    name: row?.name || "",
    standardCode: row?.standardCode || "STD-WEEKLY",
    effectiveWeek: row?.effectiveWeek || props.currentWeekNo,
    expiresWeek: row?.expiresWeek || "",
    hospitalTimezone: row?.hospitalTimezone || "Asia/Shanghai",
    lines
  });
  standardDialogVisible.value = true;
};
const addStandardLine = () => standardForm.lines.push(newStandardLine());
const removeStandardLine = (index: number) => standardForm.lines.splice(index, 1);
const syncLineUnit = (line: EditableStandardLine) => {
  const unit = itemMap.value.get(line.itemId)?.unit || "";
  if (!line.standardUnit) line.standardUnit = unit;
  if (!line.baseUnit) line.baseUnit = unit;
};
const submitStandard = async () => {
  if (!(await standardFormRef.value?.validate().catch(() => false))) return;
  const validLines = standardForm.lines.filter(line => line.departmentId && line.itemId && Number(line.conversionFactor) > 0);
  if (!validLines.length || validLines.length !== standardForm.lines.length) {
    ElMessage.warning("请补齐标准清单行，并确认换算系数大于 0");
    return;
  }
  emit("saveStandard", {
    id: editingStandardId.value || undefined,
    standardCode: standardForm.standardCode || "STD-WEEKLY",
    name: standardForm.name.trim(),
    effectiveWeek: standardForm.effectiveWeek,
    expiresWeek: standardForm.expiresWeek || undefined,
    hospitalTimezone: standardForm.hospitalTimezone,
    lines: validLines.map(line => ({
      departmentId: line.departmentId,
      itemId: line.itemId,
      careType: line.careType,
      plannedPatientVolume: Number(line.businessVolume || 0),
      businessVolume: Number(line.businessVolume || 0),
      standardQuantity: Number(line.standardQuantity || 0),
      standardUnit: line.standardUnit || itemMap.value.get(line.itemId)?.unit || "",
      conversionFactor: Number(line.conversionFactor || 1),
      baseUnit: line.baseUnit || itemMap.value.get(line.itemId)?.unit || "",
      safetyStockQuantity: Number(line.safetyStockQuantity || 0)
    }))
  });
  standardDialogVisible.value = false;
};
const generateSnapshot = () => {
  if (!snapshotWeek.value) {
    ElMessage.warning("请选择周次");
    return;
  }
  emit("generateSnapshot", { weekNo: snapshotWeek.value, departmentId: snapshotDepartmentId.value || undefined });
};
const openRevise = (snapshot: InventoryWeeklySnapshot) => {
  revisingSnapshot.value = snapshot;
  revisionReason.value = snapshot.revisionReason || "";
  revisionLines.value = (snapshot.lines || []).map(line => ({
    ...line,
    originalAdjustedQuantity: Number(line.adjustedQuantity || 0),
    adjustedQuantity: Number(line.adjustedQuantity || 0),
    adjustmentReason: line.adjustmentReason || ""
  }));
  reviseDialogVisible.value = true;
};
const submitRevision = () => {
  if (!revisingSnapshot.value) return;
  if (!revisionReason.value.trim()) {
    ElMessage.warning("请填写更正原因");
    return;
  }
  emit("reviseSnapshot", {
    snapshot: revisingSnapshot.value,
    revisionReason: revisionReason.value.trim(),
    lines: revisionLines.value
  });
  reviseDialogVisible.value = false;
};
</script>

<style scoped lang="scss">
.weekly-workbench {
  display: grid;
  gap: 12px;
}

.weekly-section {
  padding: 13px 14px;
}

.panel-head,
.line-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;

  h2,
  p {
    margin: 0;
  }

  h2 {
    color: var(--inventory-text);
    font-size: 16px;
    line-height: 1.35;
  }

  p {
    margin-top: 4px;
    color: var(--inventory-muted);
    font-size: 13px;
  }
}

.snapshot-tools,
.standard-form-grid,
.standard-line-editor,
.line-labels {
  display: grid;
  gap: 8px;
}

.snapshot-tools {
  grid-template-columns: 170px 180px auto auto;
  align-items: center;
}

.standard-form-grid {
  grid-template-columns: minmax(240px, 1fr) 160px 180px 180px;
}

.standard-line-editor,
.line-labels {
  grid-template-columns: minmax(120px, 1fr) minmax(170px, 1.2fr) 92px 116px 116px 116px 116px 40px;
  align-items: center;
  margin-bottom: 8px;
}

.line-labels {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.weekly-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.legacy-collapse {
  margin-top: 12px;
  border-top: 1px solid var(--inventory-line-soft);
}

.legacy-description {
  margin: 0 0 10px;
  color: var(--inventory-muted);
  font-size: 13px;
}

@media (max-width: 1180px) {
  .panel-head,
  .snapshot-tools,
  .standard-form-grid,
  .standard-line-editor,
  .line-labels {
    grid-template-columns: 1fr;
  }

  .panel-head {
    display: grid;
  }
}
</style>
