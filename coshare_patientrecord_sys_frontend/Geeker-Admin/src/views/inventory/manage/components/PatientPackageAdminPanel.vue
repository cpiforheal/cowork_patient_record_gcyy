<template>
  <section class="package-admin" v-loading="loading">
    <header class="package-toolbar">
      <div>
        <span class="eyebrow">管理端 / 患者套餐</span>
        <h2>各科室患者套餐设置</h2>
        <p>查看各科室保存的耗材组合，直接修改数量后保存；含映射冲突的套餐会标出实际物资。</p>
      </div>
      <div class="package-actions">
        <el-select v-model="departmentKey" clearable placeholder="全部科室" class="department-filter" @change="load">
          <el-option v-for="department in departmentOptions" :key="department.key" :label="department.name" :value="department.key" />
        </el-select>
        <el-input v-model="keyword" clearable placeholder="搜索套餐 / 耗材" class="keyword-filter" />
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <div v-if="filteredPackages.length" class="package-grid">
      <article v-for="pkg in filteredPackages" :key="pkg.id" class="package-card">
        <header class="package-head">
          <div>
            <strong>{{ pkg.name }}</strong>
            <span>{{ pkg.departmentName }} · {{ pkg.items.length }} 项</span>
          </div>
          <el-button size="small" type="primary" :loading="savingId === pkg.id" @click="save(pkg)">保存数量</el-button>
        </header>
        <el-alert
          v-if="pkg.items.some(item => item.mappingConflict)"
          type="warning"
          :closable="false"
          show-icon
          title="该套餐包含映射冲突，请先在物资映射中修正"
          class="mapping-alert"
        />
        <div class="package-lines">
          <div v-for="(item, index) in pkg.items" :key="item.itemId + '-' + index" class="package-line">
            <div class="line-copy">
              <strong>{{ item.itemName }}</strong>
              <small>
                {{ item.unit }}
                <template v-if="item.mappingConflict"> · 实际物资：{{ item.mappedItemName || "未知" }}</template>
              </small>
            </div>
            <el-input-number
              v-model="pkg.items[index].quantity"
              :min="0.01"
              :precision="2"
              :step="1"
              size="small"
              controls-position="right"
            />
          </div>
        </div>
      </article>
    </div>
    <el-empty v-else :image-size="80" description="还没有患者套餐；科室工作台保存后会出现在这里" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import {
  getAllInventoryPatientPackagesApi,
  saveInventoryPatientPackageApi,
  type InventoryPatientConsumablePackage
} from "@/api/modules/inventory";

const packages = ref<InventoryPatientConsumablePackage[]>([]);
const departmentKey = ref("");
const keyword = ref("");
const loading = ref(false);
const savingId = ref("");

const departmentOptions = computed(() => {
  const map = new Map<string, string>();
  packages.value.forEach(pkg => map.set(pkg.departmentKey, pkg.departmentName));
  return Array.from(map.entries()).map(([key, name]) => ({ key, name }));
});

const filteredPackages = computed(() => {
  const search = keyword.value.trim().toLowerCase();
  return packages.value.filter(pkg => {
    if (departmentKey.value && pkg.departmentKey !== departmentKey.value) return false;
    if (!search) return true;
    return [pkg.name, pkg.departmentName, ...pkg.items.map(item => item.itemName)]
      .join(" ")
      .toLowerCase()
      .includes(search);
  });
});

const load = async () => {
  loading.value = true;
  try {
    const result = await getAllInventoryPatientPackagesApi();
    packages.value = result.data?.packages ?? [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取患者套餐失败");
  } finally {
    loading.value = false;
  }
};

const save = async (pkg: InventoryPatientConsumablePackage) => {
  savingId.value = pkg.id;
  try {
    const result = await saveInventoryPatientPackageApi({
      departmentKey: pkg.departmentKey,
      name: pkg.name,
      lines: pkg.items.map(item => ({
        itemId: item.itemId,
        itemName: item.itemName,
        unit: item.unit,
        quantity: Number(item.quantity || 1),
        source: item.source || "PRESET",
        exceptionReason: item.exceptionReason
      }))
    });
    packages.value = result.data?.packages ?? packages.value;
    ElMessage.success(`已保存“${pkg.name}”`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存患者套餐失败");
  } finally {
    savingId.value = "";
  }
};

onMounted(() => {
  void load();
});
</script>

<style scoped lang="scss">
.package-admin {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
  background: var(--hos-bg-surface);
}

.package-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 18px 20px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 16px;

  h2 {
    margin: 4px 0 4px;
    color: var(--hos-text-primary);
    font-size: 20px;
    font-weight: 700;
  }

  p {
    margin: 0;
    color: var(--hos-text-secondary);
    font-size: 12px;
  }
}

.eyebrow {
  color: var(--hos-primary);
  font-weight: 600;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.package-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.department-filter {
  width: 140px;
}

.keyword-filter {
  width: 220px;
}

.package-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(420px, 1fr));
  gap: 12px;
}

.package-card {
  padding: 14px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;
}

.package-head {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--hos-border-light);

  div {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  strong {
    color: var(--hos-text-primary);
    font-size: 14px;
  }

  span {
    color: var(--hos-text-muted);
    font-size: 11px;
  }
}

.mapping-alert {
  margin-top: 8px;
}

.package-lines {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 360px;
  margin-top: 8px;
  overflow: auto;
}

.package-line {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
  border-bottom: 1px solid var(--hos-border-light);

  :deep(.el-input-number) {
    width: 120px;
  }
}

.line-copy {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;

  strong {
    color: var(--hos-text-primary);
    font-weight: 500;
    font-size: 12px;
  }

  small {
    color: var(--hos-text-muted);
    font-size: 10px;
  }
}

@media (max-width: 700px) {
  .package-admin {
    padding: 10px;
  }

  .package-grid {
    grid-template-columns: 1fr;
  }

  .department-filter,
  .keyword-filter {
    width: 100%;
  }
}
</style>
