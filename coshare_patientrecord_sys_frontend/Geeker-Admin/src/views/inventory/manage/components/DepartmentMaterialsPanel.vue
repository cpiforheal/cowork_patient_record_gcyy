<template>
  <section class="department-package-page" aria-label="科室套餐">
    <header class="page-toolbar">
      <div class="page-title">
        <h1>科室套餐</h1>
        <span>{{ workspace === "quota" ? "全院耗材每人次定额总控制台" : `${selectedDepartment || "全部科室"} · ${visibleRows.length} 项耗材` }}</span>
      </div>
      <el-radio-group v-model="workspace">
        <el-radio-button value="quota">每人次定额</el-radio-button>
        <el-radio-button value="packages">自动扣减套餐</el-radio-button>
      </el-radio-group>
    </header>

    <QuotaConsolePanel v-if="workspace === 'quota'" />

    <template v-else>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="此处数量用于患者完成阶段时自动扣减库存，与「每人次定额」相互独立，不会影响科室耗材表的理论量。"
      />
      <div class="filters">
        <el-select v-model="selectedDepartment" filterable clearable placeholder="选择科室" class="department-select">
          <el-option v-for="department in departments" :key="department" :label="department" :value="department" />
        </el-select>
        <el-input v-model="keyword" clearable placeholder="搜索套餐或耗材" class="keyword-input" />
        <span class="filter-summary">{{ dirtyPackageIds.size ? `${dirtyPackageIds.size} 个套餐待保存` : "已同步" }}</span>
        <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="visibleRows"
        row-key="rowKey"
        border
        stripe
        class="package-table"
        height="calc(100vh - 250px)"
      >
        <el-table-column prop="department" label="科室" width="130" show-overflow-tooltip />
        <el-table-column prop="packageName" label="预置套餐" min-width="180" show-overflow-tooltip />
        <el-table-column prop="itemName" label="耗材" min-width="220" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="80" />
        <el-table-column prop="careTypeLabel" label="类型" width="78" />
        <el-table-column label="状态" width="82">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.package.status)" effect="plain" size="small">
              {{ statusLabel(row.package.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="使用量" width="150" align="right">
          <template #default="{ row }">
            <el-input-number
              v-model="row.package.lines[row.lineIndex].quantity"
              :min="0"
              :precision="2"
              :step="1"
              controls-position="right"
              size="small"
              @change="markChanged(row.package.id)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="88" fixed="right" align="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="!isDirty(row.package.id)"
              :loading="savingId === row.package.id"
              @click="savePackage(row.package)"
            >
              保存
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无预置耗材清单" />
        </template>
      </el-table>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import { useUserStore } from "@/stores/modules/user";
import QuotaConsolePanel from "./QuotaConsolePanel.vue";
import {
  getInventoryDbApi,
  saveInventoryPackageApi,
  type InventoryDb,
  type InventoryItem,
  type InventoryPackage,
  type InventoryPackageStatus
} from "@/api/modules/inventory";

const workspace = ref<"quota" | "packages">("quota");

const departmentCatalog = [
  "理疗室",
  "检验科",
  "护理部",
  "中医科",
  "手术室",
  "麻醉室",
  "胃肠镜",
  "检查室",
  "后勤保洁",
  "西药房",
  "收费室",
  "中药房"
];

const userStore = useUserStore();
const loading = ref(false);
const savingId = ref("");
const selectedDepartment = ref("");
const keyword = ref("");
const db = ref<InventoryDb | null>(null);
const drafts = ref<InventoryPackage[]>([]);
const dirtyPackageIds = ref(new Set<string>());

const departments = computed(() => {
  const values = new Set(departmentCatalog);
  drafts.value.forEach(row => row.department && values.add(row.department));
  return Array.from(values);
});

const itemMap = computed(() => new Map((db.value?.items || []).map((item: InventoryItem) => [item.id, item])));

const visibleRows = computed(() => {
  const search = keyword.value.trim().toLowerCase();
  return drafts.value
    .flatMap(packageRow =>
      (packageRow.lines || []).map((line, lineIndex) => {
        const item = itemMap.value.get(line.itemId);
        return {
          rowKey: `${packageRow.id}-${line.id || lineIndex}`,
          package: packageRow,
          lineIndex,
          department: packageRow.department || "未分配科室",
          packageName: packageRow.name || "未命名套餐",
          itemName: item?.name || "未命名耗材",
          unit: item?.unit || "-",
          careTypeLabel: packageRow.careType === "inpatient" ? "住院" : "门诊"
        };
      })
    )
    .filter(row => {
      if (selectedDepartment.value && row.department !== selectedDepartment.value) return false;
      if (!search) return true;
      return [row.department, row.packageName, row.itemName].some(value => value.toLowerCase().includes(search));
    });
});

const clonePackages = (packages: InventoryPackage[]) =>
  packages.map(row => ({
    ...row,
    lines: (row.lines || []).map(line => ({ ...line, quantity: Number(line.quantity) || 0 }))
  }));

const loadData = async () => {
  loading.value = true;
  try {
    const result = await getInventoryDbApi();
    db.value = result.data;
    drafts.value = clonePackages(result.data.packages || []);
    dirtyPackageIds.value = new Set();
  } catch (error) {
    ElMessage.error((error as Error).message || "科室套餐加载失败");
  } finally {
    loading.value = false;
  }
};

const markChanged = (id: string) => {
  const next = new Set(dirtyPackageIds.value);
  next.add(id);
  dirtyPackageIds.value = next;
};

const isDirty = (id: string) => dirtyPackageIds.value.has(id);

const savePackage = async (row: InventoryPackage) => {
  if (row.lines.some(line => !Number.isFinite(Number(line.quantity)) || Number(line.quantity) < 0)) {
    ElMessage.warning("使用量不能小于 0");
    return;
  }

  savingId.value = row.id;
  try {
    const result = await saveInventoryPackageApi({
      id: row.id,
      name: row.name,
      department: row.department,
      careType: row.careType,
      triggerStage: row.triggerStage,
      effectiveDate: row.effectiveDate,
      lines: row.lines.map(line => ({ ...line, quantity: Number(line.quantity) })),
      operator: userStore.userInfo?.name || userStore.userInfo?.department || "当前账号"
    });
    db.value = result.data;
    const returned = result.data.packages?.find(item => item.id === row.id);
    if (returned) {
      const index = drafts.value.findIndex(item => item.id === row.id);
      if (index >= 0) drafts.value[index] = clonePackages([returned])[0];
    }
    const next = new Set(dirtyPackageIds.value);
    next.delete(row.id);
    dirtyPackageIds.value = next;
    ElMessage.success("耗材使用量已同步");
  } catch (error) {
    ElMessage.error((error as Error).message || "保存失败，当前修改仍保留");
  } finally {
    savingId.value = "";
  }
};

const statusLabel = (status: InventoryPackageStatus) => ({ draft: "草稿", enabled: "启用", disabled: "停用" })[status] || status;

const statusTag = (status: InventoryPackageStatus) =>
  ({ draft: "warning", enabled: "success", disabled: "info" })[status] as "warning" | "success" | "info";

onMounted(() => {
  if (workspace.value === "packages") loadData();
});

watch(workspace, next => {
  if (next === "packages" && !db.value && !loading.value) loadData();
});
</script>

<style scoped lang="scss">
.department-package-page {
  display: grid;
  gap: 12px;
  min-height: 0;
}

.page-toolbar,
.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.page-toolbar {
  justify-content: space-between;
}

.page-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;

  h1 {
    margin: 0;
    color: var(--el-text-color-primary);
    font-size: 18px;
  }

  span {
    overflow: hidden;
    color: var(--el-text-color-secondary);
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.department-select {
  width: 180px;
}

.keyword-input {
  width: 240px;
}

.filter-summary {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.package-table {
  width: 100%;
}

@media (max-width: 720px) {
  .page-title {
    align-items: flex-start;
    flex-direction: column;
    gap: 2px;
  }

  .department-select,
  .keyword-input {
    width: 100%;
  }
}
</style>
