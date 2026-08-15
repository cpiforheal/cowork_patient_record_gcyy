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
      </div>
      <div class="console-actions">
        <el-tooltip content="保存后立即为当日尚未填报的科室按新定额预播种草稿；已填报科室不受影响" placement="top">
          <el-checkbox v-model="applyToday">当日即时应用</el-checkbox>
        </el-tooltip>
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
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
      title="计算口径：参考使用量 = 每人次定额 × 科室流转患者人次 + 固定调整。保存到未来版本后，次日零点起各科室耗材表按新定额重建；勾选「当日即时应用」可让当日未填报科室立即使用新定额。"
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
      <el-input v-model="keyword" clearable placeholder="搜索耗材或服务项目" class="keyword-input" />
      <span class="rule-count">{{ visibleRows.length }} 项定额规则</span>
    </div>

    <el-table
      v-loading="loading"
      :data="visibleRows"
      row-key="rowKey"
      border
      stripe
      table-layout="fixed"
      height="calc(100vh - 330px)"
      empty-text="当前版本没有定额规则"
    >
      <el-table-column prop="departmentName" label="科室" width="110" />
      <el-table-column prop="serviceGroup" label="服务项目" min-width="130" show-overflow-tooltip />
      <el-table-column label="耗材" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.materialName }}</span>
          <el-tag v-if="row.pending" size="small" type="success" effect="plain" class="row-tag">新增</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="76" />
      <el-table-column label="每人次定额" width="150">
        <template #default="{ row }">
          <el-input-number
            v-model="row.standardQuantity"
            :min="0"
            :precision="6"
            controls-position="right"
            size="small"
            @change="markDirty(row as ConsoleRow)"
          />
        </template>
      </el-table-column>
      <el-table-column label="固定调整" width="140">
        <template #default="{ row }">
          <el-input-number
            v-model="row.fixedAdjustment"
            :precision="6"
            controls-position="right"
            size="small"
            @change="markDirty(row as ConsoleRow)"
          />
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
        <el-form-item label="每人次定额">
          <el-input-number v-model="createForm.standardQuantity" :min="0" :precision="6" controls-position="right" />
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
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Plus, Refresh } from "@element-plus/icons-vue";
import {
  consoleSaveInventoryQuotaApi,
  getInventoryQuotaGovernanceApi,
  type InventoryQuotaConsoleSaveResult,
  type InventoryQuotaRule,
  type InventoryQuotaRuleCreatePayload,
  type InventoryQuotaVersion
} from "@/api/modules/inventory";

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

const loading = ref(false);
const saving = ref(false);
const loadError = ref("");
const governance = ref<InventoryQuotaConsoleSaveResult>();
const selectedVersionId = ref("");
const departmentKey = ref("");
const keyword = ref("");
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
  measurementScope: "OUTPATIENT" as InventoryQuotaRuleCreatePayload["measurementScope"]
});
const today = new Date().toISOString().slice(0, 10);

const versions = computed<InventoryQuotaVersion[]>(() => governance.value?.versions || []);
const viewVersion = computed(() => {
  if (selectedVersionId.value) return versions.value.find(version => version.id === selectedVersionId.value) || null;
  return governance.value?.activeVersion || null;
});
const isEditable = computed(() => Boolean(viewVersion.value && viewVersion.value.effectiveDate > today));
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
    enabled: true,
    pending: true,
    rowKey: `pending-${index}`
  }));
  const existing: ConsoleRow[] = rules.value
    .filter(rule => !pendingDeletes.value.has(rule.id))
    .map(rule => ({ ...rule, rowKey: rule.id }));
  return [...created, ...existing].filter(row => {
    if (departmentKey.value && row.departmentKey !== departmentKey.value) return false;
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

const openAdd = () => {
  createForm.value = {
    departmentKey: departmentKey.value || departmentDirectory[0].key,
    materialName: "",
    unit: "",
    serviceGroup: "",
    careType: "outpatient",
    standardQuantity: 0,
    fixedAdjustment: 0,
    measurementScope: "OUTPATIENT"
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
      standardQuantity: form.standardQuantity,
      fixedAdjustment: Number(form.fixedAdjustment || 0),
      measurementScope: form.measurementScope,
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
