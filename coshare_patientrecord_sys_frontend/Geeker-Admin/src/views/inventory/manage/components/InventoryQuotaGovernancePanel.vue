<template>
  <section class="quota-governance">
    <div class="governance-toolbar">
      <div>
        <h2>每人次定额管理</h2>
        <p>定额按版本生效。已生效版本不可改；新建未来版本后，可动态调整每种耗材的每人次定额。</p>
      </div>
      <div class="toolbar-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" :disabled="!governance?.activeVersion" @click="openCreateVersion">新建未来定额版本</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="计算口径：参考使用量 = 每人次定额 × 当前科室流转患者人次 + 固定调整。实际使用量保持选填，历史日报继续使用其保存时冻结的定额。"
    />

    <div v-if="governance?.activeVersion" class="version-card">
      <div><small>当前查看版本</small><strong>{{ governance.activeVersion.versionCode }}</strong></div>
      <div><small>生效日期</small><strong>{{ governance.activeVersion.effectiveDate }}</strong></div>
      <el-tag :type="isEditable ? 'warning' : 'info'" effect="plain">{{ isEditable ? "可编辑（未来生效）" : "已生效，已冻结" }}</el-tag>
    </div>

    <div class="rule-filters">
      <el-select v-model="departmentKey" filterable placeholder="筛选科室">
        <el-option label="全部科室" value="" />
        <el-option v-for="department in departments" :key="department.key" :label="department.name" :value="department.key" />
      </el-select>
      <el-input v-model="keyword" clearable placeholder="按耗材或服务项目筛选" />
    </div>

    <el-table v-loading="loading" :data="filteredRules" height="calc(100vh - 370px)" min-height="360" table-layout="fixed" empty-text="当前版本没有定额规则">
      <el-table-column prop="departmentName" label="科室" width="120" />
      <el-table-column prop="serviceGroup" label="服务项目" min-width="150" show-overflow-tooltip />
      <el-table-column prop="materialName" label="耗材" min-width="180" show-overflow-tooltip />
      <el-table-column prop="unit" label="单位" width="76" />
      <el-table-column label="每人次定额" width="145">
        <template #default="{ row }">
          <el-input-number v-model="row.standardQuantity" :disabled="!isEditable" :min="0" :precision="6" controls-position="right" />
        </template>
      </el-table-column>
      <el-table-column label="固定调整" width="135">
        <template #default="{ row }">
          <el-input-number v-model="row.fixedAdjustment" :disabled="!isEditable" :precision="6" controls-position="right" />
        </template>
      </el-table-column>
      <el-table-column label="计量人次范围" width="135">
        <template #default="{ row }">
          <el-select v-model="row.measurementScope" :disabled="!isEditable">
            <el-option label="门诊人次" value="OUTPATIENT" />
            <el-option label="住院人次" value="INPATIENT" />
            <el-option label="门诊 + 住院" value="COMBINED" />
            <el-option label="其他人次" value="OTHER" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="82" align="center">
        <template #default="{ row }"><el-switch v-model="row.enabled" :disabled="!isEditable" /></template>
      </el-table-column>
      <el-table-column label="操作" width="96" fixed="right">
        <template #default="{ row }"><el-button link type="primary" :disabled="!isEditable || savingRuleId === row.id" :loading="savingRuleId === row.id" @click="saveRule(row as InventoryQuotaRule)">保存</el-button></template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createOpen" title="新建未来生效定额版本" width="min(480px, calc(100vw - 32px))" destroy-on-close>
      <el-form label-width="112px">
        <el-form-item label="复制来源"><el-input :model-value="governance?.activeVersion?.versionCode" disabled /></el-form-item>
        <el-form-item label="新版本号" required><el-input v-model="createForm.versionCode" placeholder="例如 Q-2026-09" /></el-form-item>
        <el-form-item label="生效日期" required><el-date-picker v-model="createForm.effectiveDate" type="date" value-format="YYYY-MM-DD" :disabled-date="disablePastDate" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createVersion">创建并编辑</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { Plus, Refresh } from "@element-plus/icons-vue";
import {
  createInventoryQuotaVersionApi,
  getInventoryQuotaGovernanceApi,
  updateInventoryQuotaRuleApi,
  type InventoryQuotaGovernance,
  type InventoryQuotaRule
} from "@/api/modules/inventory";

const loading = ref(false);
const creating = ref(false);
const createOpen = ref(false);
const savingRuleId = ref("");
const governance = ref<InventoryQuotaGovernance>();
const departmentKey = ref("");
const keyword = ref("");
const createForm = ref({ versionCode: "", effectiveDate: "" });
const today = new Date().toISOString().slice(0, 10);
const isEditable = computed(() => Boolean(governance.value?.activeVersion?.effectiveDate && governance.value.activeVersion.effectiveDate > today));
const departments = computed(() => {
  const values = new Map<string, string>();
  governance.value?.rules.forEach(rule => values.set(rule.departmentKey, rule.departmentName));
  return [...values.entries()].map(([key, name]) => ({ key, name }));
});
const filteredRules = computed(() => {
  const search = keyword.value.trim().toLocaleLowerCase();
  return (governance.value?.rules || []).filter(rule => {
    if (departmentKey.value && rule.departmentKey !== departmentKey.value) return false;
    return !search || `${rule.materialName} ${rule.serviceGroup}`.toLocaleLowerCase().includes(search);
  });
});
const disablePastDate = (date: Date) => date.getTime() <= new Date(`${today}T00:00:00`).getTime();
const load = async () => {
  loading.value = true;
  try {
    governance.value = (await getInventoryQuotaGovernanceApi()).data;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取定额治理数据失败");
  } finally {
    loading.value = false;
  }
};
const openCreateVersion = () => {
  const nextMonth = new Date();
  nextMonth.setMonth(nextMonth.getMonth() + 1, 1);
  createForm.value = { versionCode: "", effectiveDate: nextMonth.toISOString().slice(0, 10) };
  createOpen.value = true;
};
const createVersion = async () => {
  if (!createForm.value.versionCode.trim() || !createForm.value.effectiveDate) {
    ElMessage.warning("请填写新版本号和未来生效日期");
    return;
  }
  creating.value = true;
  try {
    governance.value = (await createInventoryQuotaVersionApi({
      ...createForm.value,
      versionCode: createForm.value.versionCode.trim(),
      baseVersionId: governance.value?.activeVersion?.id
    })).data;
    createOpen.value = false;
    departmentKey.value = "";
    ElMessage.success("未来定额版本已创建，可逐条调整每人次定额");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "创建定额版本失败");
  } finally {
    creating.value = false;
  }
};
const saveRule = async (rule: InventoryQuotaRule) => {
  savingRuleId.value = rule.id;
  try {
    governance.value = (await updateInventoryQuotaRuleApi(rule.id, {
      standardQuantity: rule.standardQuantity,
      fixedAdjustment: Number(rule.fixedAdjustment || 0),
      measurementScope: rule.measurementScope,
      enabled: rule.enabled
    })).data;
    ElMessage.success(`${rule.materialName} 的定额已保存`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存定额规则失败");
  } finally {
    savingRuleId.value = "";
  }
};
onMounted(load);
</script>

<style scoped lang="scss">
.quota-governance { display: grid; gap: 14px; min-width: 0; }
.governance-toolbar, .toolbar-actions, .rule-filters, .version-card { display: flex; align-items: center; gap: 12px; }
.governance-toolbar { justify-content: space-between; flex-wrap: wrap; }
.governance-toolbar h2 { margin: 0; color: var(--inventory-text); font-size: 20px; }
.governance-toolbar p { margin: 5px 0 0; color: var(--inventory-muted); font-size: 13px; }
.toolbar-actions, .rule-filters { flex-wrap: wrap; }
.rule-filters :deep(.el-select), .rule-filters :deep(.el-input) { width: min(280px, 100%); }
.version-card { padding: 10px 12px; background: #f8fafc; border: 1px solid var(--inventory-line); border-radius: 6px; }
.version-card > div { display: grid; gap: 2px; min-width: 150px; }
.version-card small { color: var(--inventory-muted); font-size: 12px; }
.version-card strong { color: var(--inventory-text); font-variant-numeric: tabular-nums; }
@media (max-width: 680px) { .version-card { align-items: stretch; flex-direction: column; } }
</style>
