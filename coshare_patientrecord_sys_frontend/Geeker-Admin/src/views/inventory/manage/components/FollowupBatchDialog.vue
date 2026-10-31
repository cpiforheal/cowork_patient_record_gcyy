<template>
  <el-dialog v-model="visible" title="复诊套餐批处理" width="820px" append-to-body>
    <div class="batch-shell" v-loading="running">
      <div class="batch-mode">
        <el-radio-group v-model="mode">
          <el-radio-button label="PACKAGE">统一套餐</el-radio-button>
          <el-radio-button label="LAST">每人上次组合</el-radio-button>
        </el-radio-group>
        <el-select v-if="mode === 'PACKAGE'" v-model="packageId" filterable placeholder="选择复诊套餐" class="package-select">
          <el-option v-for="pkg in packages" :key="pkg.id" :label="`${pkg.name}（${pkg.items.length} 项）`" :value="pkg.id" />
        </el-select>
        <el-input
          ref="searchInputRef"
          v-model="keyword"
          placeholder="扫描或输入姓名 / 就诊号，回车搜索/加入"
          clearable
          :prefix-icon="Search"
          class="patient-search"
          @keydown.enter.prevent="handleSearchEnter"
          @keydown.up.prevent="moveHighlight(-1)"
          @keydown.down.prevent="moveHighlight(1)"
          @keyup.esc="clearSearch"
        />
        <el-button :icon="Search" :loading="searching" @click="search">搜索</el-button>
      </div>

      <p class="keyboard-hint">回车：唯一结果直接加入；多个结果用 ↑↓ 选择、回车加入；加入后自动回到搜索框。</p>

      <div v-if="searchResults.length" class="search-results">
        <button
          v-for="(row, index) in searchResults"
          :key="row.encounterId"
          type="button"
          :class="{ 'is-highlighted': highlightIndex === index }"
          @click="addPatient(row)"
        >
          <span><strong>{{ row.patientName }}</strong><small>{{ row.visitNo || row.caseToken || row.encounterId }}</small></span>
          <span class="add-hint">{{ highlightIndex === index ? "回车加入 ↵" : "加入批次 +" }}</span>
        </button>
      </div>

      <div class="batch-table">
        <el-table :data="selected" height="300">
          <el-table-column prop="patientName" label="患者" min-width="130" />
          <el-table-column prop="visitNo" label="就诊号" width="120" />
          <el-table-column label="来源" width="110">
            <template #default="{ row }">{{ row.source === "appointment" ? "复诊预约" : "搜索加入" }}</template>
          </el-table-column>
          <el-table-column label="处理" min-width="150">
            <template #default="{ row }">
              <el-tag v-if="row.skipped" type="info" size="small" effect="plain">已存在当天单据，将跳过</el-tag>
              <el-tag v-else type="success" size="small" effect="plain">待生成并提交</el-tag>
            </template>
          </el-table-column>
          <el-table-column width="80" align="right">
            <template #default="{ $index }">
              <el-button text type="danger" @click="selected.splice($index, 1)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <p class="batch-hint">
        {{ mode === "PACKAGE" ? "统一套餐会应用到批次内全部患者。" : "每位患者带出各自最近一次已提交/通过的组合。" }}
        生成后直接提交审核；当天已有草稿/待审/已通过的患者会跳过。
      </p>
      <div v-if="batchErrors.length" class="batch-errors" role="alert">
        <strong>以下患者未生成：</strong>
        <span v-for="error in batchErrors" :key="error">{{ error }}</span>
      </div>
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :disabled="!selected.length || running" :loading="running" @click="run">
        生成并提交 {{ selected.filter(row => !row.skipped).length }} 位
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Search } from "@element-plus/icons-vue";
import dayjs from "dayjs";
import {
  addInventoryManualPatientApi,
  getInventoryPatientLastCombinationApi,
  saveInventoryPatientDocumentApi,
  searchInventoryPatientsApi,
  submitInventoryPatientDocumentApi,
  type InventoryPatientConsumablePackage
} from "@/api/modules/inventory";

type SearchRow = {
  source: string;
  encounterId: string;
  patientId: string;
  patientName: string;
  visitNo?: string;
  caseToken?: string;
  careType?: string;
  visitDate?: string;
};
type BatchRow = SearchRow & { source: "appointment" | "search"; skipped?: boolean };

const props = defineProps<{
  modelValue: boolean;
  departmentKey: string;
  date: string;
  packages: InventoryPatientConsumablePackage[];
  existingPatientIds?: string[];
  initialPackageId?: string;
}>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; completed: [] }>();

const visible = computed({
  get: () => props.modelValue,
  set: value => emit("update:modelValue", value)
});
const mode = ref<"PACKAGE" | "LAST">("PACKAGE");
const packageId = ref("");
const keyword = ref("");
const searching = ref(false);
const running = ref(false);
const searchResults = ref<SearchRow[]>([]);
const selected = ref<BatchRow[]>([]);
const searchInputRef = ref<{ focus: () => void } | null>(null);
const highlightIndex = ref(0);
const batchErrors = ref<string[]>([]);

const focusSearch = () => nextTick(() => searchInputRef.value?.focus());

const search = async () => {
  if (!keyword.value.trim()) return;
  searching.value = true;
  try {
    const result = await searchInventoryPatientsApi(keyword.value.trim());
    searchResults.value = (result.data?.list ?? []) as SearchRow[];
    highlightIndex.value = 0;
    if (searchResults.value.length === 1) addPatient(searchResults.value[0]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "搜索患者失败");
  } finally {
    searching.value = false;
  }
};

const handleSearchEnter = async () => {
  if (!keyword.value.trim() && !searchResults.value.length) return;
  if (searchResults.value.length) {
    addPatient(searchResults.value[highlightIndex.value] || searchResults.value[0]);
    return;
  }
  await search();
};

const moveHighlight = (offset: number) => {
  if (!searchResults.value.length) return;
  highlightIndex.value =
    (highlightIndex.value + offset + searchResults.value.length) % searchResults.value.length;
};

const clearSearch = () => {
  keyword.value = "";
  searchResults.value = [];
  focusSearch();
};

const addPatient = (row: SearchRow) => {
  if (selected.value.some(item => item.patientId === row.patientId)) {
    ElMessage.info(`${row.patientName} 已在批次中`);
    keyword.value = "";
    searchResults.value = [];
    focusSearch();
    return;
  }
  const skipped = (props.existingPatientIds ?? []).includes(row.patientId);
  selected.value.push({ ...row, source: "search", skipped });
  keyword.value = "";
  searchResults.value = [];
  focusSearch();
};

const run = async () => {
  if (!selected.value.length) return;
  const pkg = props.packages.find(item => item.id === packageId.value);
  if (mode.value === "PACKAGE" && !pkg) {
    ElMessage.warning("请选择复诊套餐");
    return;
  }
  running.value = true;
  batchErrors.value = [];
  let created = 0;
  let skipped = 0;
  const errors: string[] = [];
  try {
    for (const row of selected.value) {
      if (row.skipped) {
        skipped++;
        continue;
      }
      try {
        const added = await addInventoryManualPatientApi({
          departmentKey: props.departmentKey,
          encounterId: row.encounterId,
          patientId: row.patientId,
          patientName: row.patientName,
          visitNo: row.visitNo,
          careType: row.careType,
          serviceCode: "FOLLOWUP",
          serviceName: "复诊",
          sourceType: "REGISTRATION",
          serviceStartedAt: `${(row.visitDate || props.date).slice(0, 10)}T08:00:00`,
          businessDate: props.date,
          note: "复诊套餐批处理"
        });
        const detail = added.data;
        if (!detail?.serviceInstanceId) throw new Error("未生成服务实例");
        const lines = mode.value === "LAST"
          ? ((await getInventoryPatientLastCombinationApi({ patientId: row.patientId, departmentKey: props.departmentKey })).data?.items ?? [])
          : (pkg?.items ?? []).map(item => ({
              itemId: item.itemId,
              itemName: item.itemName,
              unit: item.unit,
              actualQuantity: Number(item.quantity || 1),
              source: item.source || "PRESET",
              exceptionReason: item.exceptionReason
            }));
        if (!lines.length) throw new Error("没有可套用的套餐/历史组合");
        const saved = await saveInventoryPatientDocumentApi({
          serviceInstanceId: detail.serviceInstanceId,
          revision: 0,
          noConsumption: false,
          lines: lines.map(line => ({
            itemId: line.itemId,
            itemName: line.itemName,
            unit: line.unit,
            actualQuantity: Number(line.actualQuantity || 1),
            source: line.source || "PRESET",
            exceptionReason: line.exceptionReason
          }))
        });
        if (saved.data?.documentId) {
          await submitInventoryPatientDocumentApi(saved.data.documentId);
        }
        created++;
        row.skipped = true;
      } catch (error) {
        errors.push(`${row.patientName}：${error instanceof Error ? error.message : "处理失败"}`);
      }
    }
    batchErrors.value = errors;
    if (errors.length) {
      ElMessage.warning(`已生成 ${created} 位，${errors.length} 位失败，原因见弹窗下方`);
    } else {
      ElMessage.success(`已生成并提交 ${created} 位${skipped ? `，跳过 ${skipped} 位` : ""}`);
      emit("completed");
      visible.value = false;
    }
  } finally {
    running.value = false;
  }
};

watch(
  () => props.modelValue,
  value => {
    if (!value) return;
    mode.value = "PACKAGE";
    packageId.value = props.initialPackageId || "";
    keyword.value = "";
    searchResults.value = [];
    selected.value = [];
    highlightIndex.value = 0;
    focusSearch();
  }
);
</script>

<style scoped lang="scss">
.batch-shell {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.batch-mode {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.package-select {
  width: 220px;
}

.patient-search {
  flex: 1;
  min-width: 200px;
}

.search-results {
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-height: 150px;
  padding: 4px;
  overflow: auto;
  background: #f8f5ef;
  border-radius: 10px;

  button {
    display: flex;
    gap: 8px;
    align-items: center;
    justify-content: space-between;
    padding: 6px 8px;
    text-align: left;
    cursor: pointer;
    background: #fffdf9;
    border: 1px solid #efe8dd;
    border-radius: 8px;

    &.is-highlighted {
      background: #edf7f1;
      border-color: #347d68;
    }

    span:first-child {
      display: flex;
      flex-direction: column;
    }

    strong {
      font-size: 12px;
    }

    small {
      color: #9a9285;
      font-size: 10px;
    }
  }
}

.add-hint {
  color: #347d68;
  font-size: 10px;
}

.keyboard-hint {
  margin: -4px 0 0;
  color: #9a9285;
  font-size: 10px;
}

.batch-hint {
  margin: 0;
  color: #9a9285;
  font-size: 11px;
}

.batch-errors {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 8px 10px;
  color: #8a5a13;
  font-size: 11px;
  background: #fdf6e7;
  border: 1px solid #f0dfb8;
  border-radius: 8px;
}
</style>
