<template>
  <section class="package-board" :class="{ 'is-embedded': embedded }" v-loading="loading">
    <header class="board-head" :class="{ 'is-embedded': embedded }">
      <div v-if="!embedded">
        <span class="eyebrow">科室预设套餐</span>
        <h3>常用组合与复诊批处理</h3>
        <p>查看、编辑本科室套餐；可直接把套餐用于当前患者，或发起复诊套餐批处理。</p>
      </div>
      <div class="board-head-actions">
        <el-button v-if="currentItems?.length" @click="openCreateFromCurrent">当前组合存为套餐</el-button>
        <el-button type="primary" plain :icon="Plus" @click="openCreate">新增套餐</el-button>
      </div>
    </header>

    <div v-if="packages.length" class="package-grid">
      <article v-for="pkg in packages" :key="pkg.id" class="package-card">
        <header class="package-card-head">
          <div>
            <strong>{{ pkg.name }}</strong>
            <small>{{ pkg.items.length }} 项 · {{ pkg.updatedAt ? pkg.updatedAt.slice(0, 16).replace("T", " ") : "未记录时间" }}</small>
          </div>
          <el-tag v-if="conflictCount(pkg)" type="warning" size="small" effect="plain">
            {{ conflictCount(pkg) }} 项映射冲突
          </el-tag>
        </header>
        <div class="package-items">
          <span v-for="item in pkg.items.slice(0, 4)" :key="item.itemId">
            {{ item.itemName }} × {{ formatQuantity(item.quantity) }}{{ item.unit }}
          </span>
          <span v-if="pkg.items.length > 4" class="more">另 {{ pkg.items.length - 4 }} 项</span>
        </div>
        <footer class="package-actions">
          <el-button size="small" type="primary" plain @click="openBatch(pkg)">复诊批处理</el-button>
          <el-button size="small" :disabled="!canApply" @click="emit('apply', pkg)">套用当前患者</el-button>
          <el-button size="small" text @click="openEdit(pkg)">编辑</el-button>
          <el-button size="small" text type="danger" @click="remove(pkg)">删除</el-button>
        </footer>
      </article>
    </div>
    <el-empty v-else-if="!loading" :image-size="70" description="本科室还没有预设套餐" />

    <el-dialog v-model="editorVisible" :title="editing ? '编辑套餐' : '新增套餐'" width="720px" append-to-body>
      <el-form label-position="top">
        <el-form-item label="套餐名称">
          <el-input v-model="editorName" maxlength="120" :disabled="Boolean(editing)" placeholder="例如：复诊常规组合" />
        </el-form-item>
      </el-form>
      <div class="editor-rows">
        <div v-for="(row, index) in editorRows" :key="row.key" class="editor-row">
          <el-select v-model="row.itemId" filterable placeholder="选择耗材" class="editor-item" @change="onItemChange(row)">
            <el-option v-for="item in availableItems(row.itemId)" :key="item.id" :label="`${item.name}（${item.unit}）`" :value="item.id" />
          </el-select>
          <el-input-number v-model="row.quantity" :min="0.01" :precision="2" :step="1" controls-position="right" class="editor-qty" />
          <span class="editor-unit">{{ row.unit }}</span>
          <el-button text type="danger" @click="editorRows.splice(index, 1)">移除</el-button>
        </div>
      </div>
      <el-button :icon="Plus" @click="addEditorRow">加一项耗材</el-button>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePackage">保存套餐</el-button>
      </template>
    </el-dialog>

    <FollowupBatchDialog
      v-model="batchVisible"
      :department-key="departmentKey"
      :date="date"
      :packages="packages"
      :existing-patient-ids="existingPatientIds"
      :initial-package-id="batchPackageId"
      @completed="emit('refresh')"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import {
  deleteInventoryPatientPackageApi,
  getInventoryDbApi,
  saveInventoryPatientPackageApi,
  type InventoryItem,
  type InventoryPatientConsumablePackage
} from "@/api/modules/inventory";
import FollowupBatchDialog from "./FollowupBatchDialog.vue";

type EditorRow = { key: number; itemId: string; itemName: string; unit: string; quantity: number };

const props = defineProps<{
  packages: InventoryPatientConsumablePackage[];
  departmentKey: string;
  date: string;
  existingPatientIds?: string[];
  canApply?: boolean;
  embedded?: boolean;
  currentItems?: { itemId: string; itemName: string; unit: string; quantity: number }[];
}>();
const emit = defineEmits<{
  refresh: [];
  apply: [pkg: InventoryPatientConsumablePackage];
}>();

const loading = ref(false);
const saving = ref(false);
const editorVisible = ref(false);
const editing = ref<InventoryPatientConsumablePackage | null>(null);
const editorName = ref("");
const editorRows = ref<EditorRow[]>([]);
const allItems = ref<InventoryItem[]>([]);
const nextKey = ref(1);
const batchVisible = ref(false);
const batchPackageId = ref("");

const conflictCount = (pkg: InventoryPatientConsumablePackage) => pkg.items.filter(item => item.mappingConflict).length;
const formatQuantity = (value?: number) => Number(value || 0).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
const availableItems = (currentId: string) =>
  allItems.value.filter(item => item.id === currentId || !editorRows.value.some(row => row.itemId === item.id));

const loadItems = async () => {
  if (allItems.value.length) return;
  loading.value = true;
  try {
    const result = await getInventoryDbApi();
    allItems.value = result.data?.items ?? [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取物资目录失败");
  } finally {
    loading.value = false;
  }
};

const openCreate = async () => {
  await loadItems();
  editing.value = null;
  editorName.value = "";
  editorRows.value = [];
  addEditorRow();
  editorVisible.value = true;
};

const openCreateFromCurrent = async () => {
  const rows = (props.currentItems ?? []).filter(item => item.quantity > 0);
  if (!rows.length) {
    ElMessage.warning("当前患者还没有可保存的耗材");
    return;
  }
  await loadItems();
  editing.value = null;
  editorName.value = "";
  editorRows.value = rows.map(item => ({
    key: nextKey.value++,
    itemId: item.itemId,
    itemName: item.itemName,
    unit: item.unit,
    quantity: Number(item.quantity || 1)
  }));
  editorVisible.value = true;
};

const openEdit = async (pkg: InventoryPatientConsumablePackage) => {
  await loadItems();
  editing.value = pkg;
  editorName.value = pkg.name;
  editorRows.value = pkg.items.map(item => ({
    key: nextKey.value++,
    itemId: item.itemId,
    itemName: item.itemName,
    unit: item.unit,
    quantity: Number(item.quantity || 1)
  }));
  editorVisible.value = true;
};

const addEditorRow = () => {
  editorRows.value.push({ key: nextKey.value++, itemId: "", itemName: "", unit: "", quantity: 1 });
};

const onItemChange = (row: EditorRow) => {
  const item = allItems.value.find(option => option.id === row.itemId);
  row.itemName = item?.name || "";
  row.unit = item?.unit || "";
};

const savePackage = async () => {
  if (!editorName.value.trim()) return ElMessage.warning("请填写套餐名称");
  const rows = editorRows.value.filter(row => row.itemId && row.quantity > 0);
  if (!rows.length) return ElMessage.warning("请至少加入一项耗材");
  saving.value = true;
  try {
    await saveInventoryPatientPackageApi({
      departmentKey: props.departmentKey,
      name: editorName.value.trim(),
      lines: rows.map(row => ({
        itemId: row.itemId,
        itemName: row.itemName,
        unit: row.unit,
        quantity: Number(row.quantity || 1),
        source: "PRESET"
      }))
    });
    ElMessage.success(editing.value ? "套餐已更新" : "套餐已新增");
    editorVisible.value = false;
    emit("refresh");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存套餐失败");
  } finally {
    saving.value = false;
  }
};

const remove = async (pkg: InventoryPatientConsumablePackage) => {
  try {
    await ElMessageBox.confirm(`删除套餐“${pkg.name}”？`, "删除套餐", { type: "warning" });
  } catch {
    return;
  }
  try {
    await deleteInventoryPatientPackageApi(pkg.id);
    ElMessage.success("套餐已删除");
    emit("refresh");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "删除套餐失败");
  }
};

const openBatch = (pkg: InventoryPatientConsumablePackage) => {
  batchPackageId.value = pkg.id;
  batchVisible.value = true;
};
</script>

<style scoped lang="scss">
.package-board {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  background: #fffdf9;
  border: 1px solid #efe8dd;
  border-radius: 16px;

  &.is-embedded {
    padding: 0;
    background: transparent;
    border: 0;
    border-radius: 0;
  }
}

.board-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;

  &.is-embedded {
    align-items: center;
    justify-content: flex-end;
  }

  .board-head-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    justify-content: flex-end;
  }

  h3 {
    margin: 3px 0;
    color: #2f2d27;
    font-size: 16px;
  }

  p {
    margin: 0;
    color: #9a9285;
    font-size: 11px;
  }
}

.eyebrow {
  color: #347d68;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.package-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 8px;
}

.package-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 11px 12px;
  background: #f8f5ef;
  border: 1px solid #efe8dd;
  border-radius: 12px;
}

.package-card-head {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  justify-content: space-between;

  div {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  strong {
    color: #2f2d27;
    font-size: 13px;
  }

  small {
    color: #9a9285;
    font-size: 10px;
  }
}

.package-items {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;

  span {
    padding: 2px 7px;
    color: #6d675c;
    font-size: 10px;
    background: #fffdf9;
    border: 1px solid #efe8dd;
    border-radius: 999px;
  }

  .more {
    color: #347d68;
  }
}

.package-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.editor-rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 330px;
  margin-bottom: 8px;
  overflow: auto;
}

.editor-row {
  display: flex;
  gap: 6px;
  align-items: center;
}

.editor-item {
  flex: 1;
}

.editor-qty {
  width: 120px;
}

.editor-unit {
  min-width: 30px;
  color: #9a9285;
  font-size: 11px;
}
</style>
