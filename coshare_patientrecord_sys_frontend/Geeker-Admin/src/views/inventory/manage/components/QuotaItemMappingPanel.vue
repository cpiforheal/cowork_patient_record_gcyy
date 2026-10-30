<template>
  <section class="mapping-panel" v-loading="loading">
    <header class="mapping-toolbar">
      <div class="mapping-copy">
        <span class="eyebrow">定额治理 / 物资映射</span>
        <h2>耗材物资映射</h2>
        <p>把尚未绑定库存主数据的定额规则映射到物资档案。未映射规则不会出现在患者耗材清单中，也不能审核扣库。</p>
      </div>
      <div class="mapping-actions">
        <el-select v-model="departmentKey" clearable placeholder="全部科室" class="department-filter" @change="load">
          <el-option v-for="department in departments" :key="department.key" :label="department.name" :value="department.key" />
        </el-select>
        <el-input
          v-model="keyword"
          clearable
          placeholder="搜索耗材名称"
          class="keyword-filter"
          @keyup.enter="load"
          @clear="load"
        />
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :loading="saving" :disabled="!dirtyCount" @click="save">
          保存映射{{ dirtyCount ? `（${dirtyCount}）` : "" }}
        </el-button>
      </div>
    </header>

    <div class="mapping-summary">
      <div>
        <small>待映射规则</small>
        <strong>{{ worklist.unmappedCount }}</strong>
      </div>
      <div>
        <small>本次已选择</small>
        <strong>{{ dirtyCount }}</strong>
      </div>
      <p>映射只改变定额规则与物资档案的关联，不会修改历史库存流水或已提交的患者清单。</p>
    </div>

    <el-table
      :data="worklist.rules"
      row-key="ruleId"
      table-layout="fixed"
      height="calc(100vh - 340px)"
      empty-text="当前筛选下没有待映射规则"
    >
      <el-table-column prop="departmentName" label="科室" width="100" />
      <el-table-column prop="serviceGroup" label="服务项目" min-width="130" show-overflow-tooltip />
      <el-table-column label="耗材 / 单位" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <div class="material-cell">
            <strong>{{ row.materialName }}</strong>
            <small>{{ row.unit || "未填单位" }}<template v-if="row.bindingType"> · {{ bindingLabel(row.bindingType) }}</template></small>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="定额" width="90" align="right">
        <template #default="{ row }">
          <span class="number">{{ formatQuantity(row.standardQuantity) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="物资主数据" min-width="300">
        <template #default="{ row }">
          <el-select
            v-model="selections[row.ruleId]"
            filterable
            clearable
            placeholder="选择库存物资"
            class="item-select"
            @change="onSelect(row)"
          >
            <el-option
              v-for="item in worklist.items"
              :key="item.itemId"
              :label="`${item.itemName}（${item.unit}）`"
              :value="item.itemId"
            />
          </el-select>
          <span v-if="suggestions[row.ruleId]" class="suggestion">可能的匹配：{{ suggestions[row.ruleId] }}</span>
        </template>
      </el-table-column>
      <el-table-column label="常用项" width="82" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="Boolean(commonFlags[row.ruleId])"
            size="small"
            :disabled="!selections[row.ruleId]"
            @update:model-value="value => (commonFlags[row.ruleId] = Boolean(value))"
          />
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import {
  getInventoryQuotaMappingWorklistApi,
  saveInventoryQuotaMappingsApi,
  type InventoryQuotaMappingWorklist
} from "@/api/modules/inventory";

const emit = defineEmits<{ saved: [] }>();

const departments = [
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

const emptyWorklist = (): InventoryQuotaMappingWorklist => ({ unmappedCount: 0, rules: [], items: [] });

const departmentKey = ref("");
const keyword = ref("");
const worklist = ref<InventoryQuotaMappingWorklist>(emptyWorklist());
const loading = ref(false);
const saving = ref(false);
const selections = reactive<Record<string, string>>({});
const commonFlags = reactive<Record<string, boolean>>({});

const dirtyCount = computed(() =>
  worklist.value.rules.filter(rule => Boolean(selections[rule.ruleId])).length
);

const suggestions = computed(() => {
  const result: Record<string, string> = {};
  const items = worklist.value.items;
  for (const rule of worklist.value.rules) {
    const target = normalize(rule.materialName);
    if (!target) continue;
    const match = items.find(item => {
      const candidate = normalize(item.itemName);
      return candidate === target || candidate.includes(target) || target.includes(candidate);
    });
    if (match) result[rule.ruleId] = `${match.itemName}（${match.unit}）`;
  }
  return result;
});

const load = async () => {
  loading.value = true;
  try {
    const result = await getInventoryQuotaMappingWorklistApi({
      departmentKey: departmentKey.value || undefined,
      keyword: keyword.value.trim() || undefined
    });
    worklist.value = result.data ?? emptyWorklist();
    Object.keys(selections).forEach(key => delete selections[key]);
    Object.keys(commonFlags).forEach(key => delete commonFlags[key]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取物资映射工作清单失败");
  } finally {
    loading.value = false;
  }
};

const onSelect = (rule: { ruleId?: string; commonItem?: boolean }) => {
  if (!rule.ruleId) return;
  if (selections[rule.ruleId]) {
    commonFlags[rule.ruleId] = Boolean(rule.commonItem);
  } else {
    commonFlags[rule.ruleId] = false;
  }
};

const save = async () => {
  const mappings = worklist.value.rules
    .filter(rule => Boolean(selections[rule.ruleId]))
    .map(rule => ({
      ruleId: rule.ruleId,
      itemId: selections[rule.ruleId],
      commonItem: Boolean(commonFlags[rule.ruleId])
    }));
  if (!mappings.length) return;
  saving.value = true;
  try {
    const result = await saveInventoryQuotaMappingsApi({ mappings });
    worklist.value = result.data ?? emptyWorklist();
    Object.keys(selections).forEach(key => delete selections[key]);
    Object.keys(commonFlags).forEach(key => delete commonFlags[key]);
    emit("saved");
    ElMessage.success(`已保存 ${mappings.length} 条物资映射`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存物资映射失败");
  } finally {
    saving.value = false;
  }
};

const normalize = (value: string) =>
  (value || "")
    .replace(/[（(].*?[）)]/g, "")
    .replace(/[\s/]/g, "")
    .trim()
    .toLowerCase();

const bindingLabel = (binding?: string) => {
  switch (binding) {
    case "FIXED_DAILY":
      return "固定日耗";
    case "ON_DEMAND":
      return "按需领取";
    case "EQUIPMENT":
      return "仪器触发";
    default:
      return "每人次";
  }
};

const formatQuantity = (value?: number | null) => {
  if (value === null || value === undefined) return "—";
  return Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
};

onMounted(() => {
  void load();
});
</script>

<style scoped lang="scss">
.mapping-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
  background: var(--hos-bg-surface);
}

.mapping-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 20px 22px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 16px;
}

.mapping-copy {
  max-width: 720px;

  .eyebrow {
    display: inline-flex;
    margin-bottom: 6px;
    color: var(--hos-primary);
    font-weight: 600;
    font-size: 12px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  h2 {
    margin: 0 0 6px;
    color: var(--hos-text-primary);
    font-size: 22px;
    font-weight: 700;
  }

  p {
    margin: 0;
    color: var(--hos-text-secondary);
    font-size: 13px;
    line-height: 1.7;
  }
}

.mapping-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.department-filter {
  width: 132px;
}

.keyword-filter {
  width: 200px;
}

.mapping-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  align-items: center;
  padding: 12px 18px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;

  > div {
    display: flex;
    gap: 8px;
    align-items: baseline;
  }

  small {
    color: var(--hos-text-muted);
    font-size: 12px;
  }

  strong {
    color: var(--hos-text-primary);
    font-size: 20px;
    font-variant-numeric: tabular-nums;
  }

  p {
    flex: 1 1 320px;
    margin: 0;
    color: var(--hos-text-muted);
    font-size: 12px;
    line-height: 1.6;
  }
}

.material-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;

  strong {
    color: var(--hos-text-primary);
    font-weight: 500;
    font-size: 13px;
  }

  small {
    color: var(--hos-text-muted);
    font-size: 11px;
  }
}

.number {
  color: var(--hos-text-primary);
  font-variant-numeric: tabular-nums;
}

.item-select {
  width: 100%;
}

.suggestion {
  display: block;
  margin-top: 4px;
  color: var(--hos-text-muted);
  font-size: 11px;
}

@media (max-width: 900px) {
  .mapping-panel {
    padding: 10px;
  }

  .department-filter,
  .keyword-filter {
    width: 100%;
  }
}
</style>
