<template>
  <section class="package-layout">
    <div class="panel package-panel">
      <div class="panel-head">
        <div>
          <h2>门诊 / 住院使用套餐</h2>
          <p>按科室维护标准用量，启用后用于就诊消耗事件的自动计数。</p>
        </div>
        <el-button v-if="canManage" type="primary" :icon="Plus" @click="openCreate">新建套餐</el-button>
      </div>

      <div class="table-toolbar">
        <el-input v-model="filters.keyword" clearable placeholder="搜索套餐、科室" />
        <el-select v-model="filters.careType" clearable placeholder="照护类型">
          <el-option label="门诊" value="outpatient" />
          <el-option label="住院" value="inpatient" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="状态">
          <el-option label="草稿" value="draft" />
          <el-option label="已启用" value="enabled" />
          <el-option label="已停用" value="disabled" />
        </el-select>
      </div>

      <el-table :data="filteredPackages" border>
        <el-table-column prop="name" label="套餐名称" min-width="180" />
        <el-table-column prop="department" label="科室" width="130" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ careTypeLabel(row.careType) }}</template>
        </el-table-column>
        <el-table-column label="版本" width="90">
          <template #default="{ row }">v{{ row.version || 1 }}</template>
        </el-table-column>
        <el-table-column label="物资项" width="90">
          <template #default="{ row }">{{ row.lines?.length || 0 }} 项</template>
        </el-table-column>
        <el-table-column prop="effectiveDate" label="生效日期" width="120" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="canManage && row.status === 'enabled'"
              link
              type="primary"
              @click="openNewVersion(row as InventoryPackage)"
            >
              创建新版本
            </el-button>
            <el-button v-else-if="canManage" link type="primary" @click="openEdit(row as InventoryPackage)">编辑</el-button>
            <el-button v-if="canManage && row.status !== 'enabled'" link type="success" @click="emitEnable(row)">启用</el-button>
            <el-button v-if="canManage && row.status === 'enabled'" link type="warning" @click="emitDisable(row)">停用</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无使用套餐" /></template>
      </el-table>
    </div>

    <div class="panel event-panel">
      <div class="panel-head">
        <div>
          <h2>自动消耗事件</h2>
          <p>每次就诊只生成一条事件，失败原因会保留在这里便于补处理。</p>
        </div>
        <el-tag effect="plain">共 {{ events.length }} 条</el-tag>
      </div>
      <el-table :data="events" border max-height="360">
        <el-table-column prop="visitDate" label="就诊日期" width="112" />
        <el-table-column prop="department" label="科室" width="110" />
        <el-table-column prop="route" label="业务类型" width="110" />
        <el-table-column prop="packageName" label="使用套餐" min-width="170" />
        <el-table-column prop="encounterId" label="就诊标识" min-width="170" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="eventTag(row.status)" effect="light">{{ eventLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.errorMessage || "-" }}</template>
        </el-table-column>
        <el-table-column v-if="canManage" label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'failed'" link type="primary" @click="emitRetry(row)">重试</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无自动消耗事件" /></template>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="760px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="92px" status-icon>
        <div class="form-grid">
          <el-form-item label="套餐名称" prop="name">
            <el-input v-model="form.name" maxlength="60" placeholder="例如：肛肠门诊基础耗材" />
          </el-form-item>
          <el-form-item label="科室" prop="department">
            <el-select v-model="form.department" filterable allow-create placeholder="请选择科室">
              <el-option v-for="department in departmentOptions" :key="department" :label="department" :value="department" />
            </el-select>
          </el-form-item>
          <el-form-item label="照护类型" prop="careType">
            <el-radio-group v-model="form.careType">
              <el-radio-button label="outpatient">门诊</el-radio-button>
              <el-radio-button label="inpatient">住院</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="生效日期">
            <el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD" placeholder="留空立即可用" />
          </el-form-item>
        </div>
        <div class="line-head">
          <strong>套餐物资明细</strong>
          <el-button link type="primary" :icon="Plus" @click="addLine">添加物资</el-button>
        </div>
        <div v-for="(line, index) in form.lines" :key="line.localId" class="line-editor">
          <el-select v-model="line.itemId" filterable placeholder="选择物资">
            <el-option v-for="item in items" :key="item.id" :label="`${item.name} / ${item.unit}`" :value="item.id" />
          </el-select>
          <el-input-number v-model="line.quantity" :min="0.01" :precision="2" controls-position="right" />
          <el-tag class="consumption-mode" type="info" effect="plain">按次就诊</el-tag>
          <el-button circle text type="danger" :icon="Delete" aria-label="删除物资" @click="removeLine(index)" />
        </div>
        <el-empty v-if="!form.lines.length" description="请添加至少一项物资" :image-size="64" />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存草稿</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { Delete, Plus } from "@element-plus/icons-vue";
import type { FormInstance, FormRules } from "element-plus";
import type {
  InventoryCareType,
  InventoryConsumptionEvent,
  InventoryItem,
  InventoryPackage,
  InventoryPackageLine,
  InventoryPackageStatus,
  SaveInventoryPackageParams
} from "@/api/modules/inventory";

const props = defineProps<{
  packages: InventoryPackage[];
  events: InventoryConsumptionEvent[];
  items: InventoryItem[];
  departmentOptions: string[];
  canManage: boolean;
  saving?: boolean;
}>();

const emit = defineEmits<{
  save: [payload: SaveInventoryPackageParams];
  enable: [row: InventoryPackage];
  disable: [row: InventoryPackage];
  retry: [row: InventoryConsumptionEvent];
}>();

const filters = reactive({ keyword: "", careType: "", status: "" });
const dialogVisible = ref(false);
const editingId = ref("");
const creatingVersion = ref(false);
const formRef = ref<FormInstance>();
const form = reactive<SaveInventoryPackageParams & { lines: (InventoryPackageLine & { localId: string })[] }>({
  name: "",
  department: "",
  careType: "outpatient",
  effectiveDate: "",
  lines: []
});
const rules = reactive<FormRules>({
  name: [{ required: true, message: "请输入套餐名称", trigger: "blur" }],
  department: [{ required: true, message: "请选择科室", trigger: "change" }],
  careType: [{ required: true, message: "请选择照护类型", trigger: "change" }]
});

const filteredPackages = computed(() => {
  const keyword = filters.keyword.trim().toLowerCase();
  return props.packages.filter(row => {
    if (filters.careType && row.careType !== filters.careType) return false;
    if (filters.status && row.status !== filters.status) return false;
    return !keyword || `${row.name} ${row.department}`.toLowerCase().includes(keyword);
  });
});

const dialogTitle = computed(() => {
  if (creatingVersion.value) return "创建使用套餐新版本";
  return editingId.value ? "编辑使用套餐" : "新建使用套餐";
});

const careTypeLabel = (value: InventoryCareType) => (value === "inpatient" ? "住院" : "门诊");
const statusLabel = (value: InventoryPackageStatus) => ({ draft: "草稿", enabled: "已启用", disabled: "已停用" })[value];
const statusTag = (value: InventoryPackageStatus) =>
  ({ draft: "info", enabled: "success", disabled: "warning" })[value] as "info" | "success" | "warning";
const eventLabel = (value: InventoryConsumptionEvent["status"]) =>
  ({ pending: "处理中", success: "已扣减", succeeded: "已扣减", failed: "失败", reversed: "已冲销" })[value];
const eventTag = (value: InventoryConsumptionEvent["status"]) =>
  ({ pending: "info", success: "success", succeeded: "success", failed: "danger", reversed: "warning" })[value] as
    | "info"
    | "success"
    | "danger"
    | "warning";
const newLine = (): InventoryPackageLine & { localId: string } => ({
  localId: `${Date.now()}-${Math.random()}`,
  itemId: "",
  quantity: 1,
  consumptionMode: "per_visit"
});
const emitEnable = (row: unknown) => emit("enable", row as InventoryPackage);
const emitDisable = (row: unknown) => emit("disable", row as InventoryPackage);
const emitRetry = (row: unknown) => emit("retry", row as InventoryConsumptionEvent);

const resetForm = () => {
  editingId.value = "";
  creatingVersion.value = false;
  Object.assign(form, {
    name: "",
    department: props.departmentOptions[0] || "",
    careType: "outpatient",
    effectiveDate: "",
    lines: [newLine()]
  });
};
const openCreate = () => {
  resetForm();
  dialogVisible.value = true;
};
const openEdit = (row: InventoryPackage) => {
  if (row.status === "enabled") {
    openNewVersion(row);
    return;
  }
  editingId.value = row.id;
  creatingVersion.value = false;
  Object.assign(form, {
    name: row.name,
    department: row.department,
    careType: row.careType,
    effectiveDate: row.effectiveDate || "",
    lines: (row.lines || []).map(line => ({ ...line, localId: `${line.id || line.itemId}-${Date.now()}` }))
  });
  dialogVisible.value = true;
};
const openNewVersion = (row: InventoryPackage) => {
  editingId.value = "";
  creatingVersion.value = true;
  Object.assign(form, {
    name: row.name,
    department: row.department,
    careType: row.careType,
    effectiveDate: row.effectiveDate || "",
    // A new package version must receive fresh package-line IDs from the API.
    lines: (row.lines || []).map(line => ({
      itemId: line.itemId,
      quantity: Number(line.quantity),
      consumptionMode: "per_visit" as const,
      localId: `${line.itemId}-${Date.now()}-${Math.random()}`
    }))
  });
  dialogVisible.value = true;
};
const addLine = () => form.lines.push(newLine());
const removeLine = (index: number) => form.lines.splice(index, 1);
const submit = async () => {
  if (!(await formRef.value?.validate().catch(() => false))) return;
  if (!form.lines.length || form.lines.some(line => !line.itemId || Number(line.quantity) <= 0)) return;
  emit("save", {
    id: editingId.value || undefined,
    name: form.name.trim(),
    department: form.department,
    careType: form.careType,
    effectiveDate: form.effectiveDate || undefined,
    lines: form.lines.map(line => ({
      id: editingId.value ? line.id : undefined,
      itemId: line.itemId,
      quantity: Number(line.quantity),
      consumptionMode: "per_visit"
    }))
  });
  dialogVisible.value = false;
};
</script>

<style scoped lang="scss">
.package-layout {
  display: grid;
  gap: 12px;
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

.table-toolbar,
.form-grid {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 180px 180px;
  gap: 8px;
  margin-bottom: 10px;
}

.form-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.line-head {
  align-items: center;
  padding-top: 4px;
  margin-top: 6px;
  border-top: 1px solid var(--inventory-line-soft);
}

.line-editor {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 130px 150px 40px;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

@media (max-width: 820px) {
  .table-toolbar,
  .form-grid,
  .line-editor {
    grid-template-columns: 1fr;
  }
}
</style>
