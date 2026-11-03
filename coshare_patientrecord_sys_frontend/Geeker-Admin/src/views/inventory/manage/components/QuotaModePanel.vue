<template>
  <section class="quota-mode-panel">
    <header class="qmp-head">
      <div>
        <strong>定额消耗模式</strong>
        <small>院方核定每人次定额，科室只确认接诊人数；定额与扣减数量不允许科室修改。</small>
      </div>
      <div class="qmp-actions">
        <el-tag :type="mode?.mode === 'QUOTA' ? 'success' : 'info'" effect="plain">
          {{ mode?.mode === "QUOTA" ? "定额模式" : "实耗模式" }}
        </el-tag>
        <span v-if="mode?.effectiveDate" class="qmp-effective">生效日 {{ mode.effectiveDate }}</span>
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button v-if="mode?.mode !== 'QUOTA'" type="primary" @click="activeTab = 'departments'">
          按科室启用
        </el-button>
        <el-button v-else type="warning" plain @click="rollback">回退实耗模式</el-button>
      </div>
    </header>

    <div class="qmp-stats">
      <span>待核定 <b :class="{ danger: (mode?.completeness.total || 0) > 0 }">{{ mode?.completeness.total || 0 }}</b></span>
      <span>定额规则 <b>{{ tolerances.length }}</b></span>
      <span>免审配置 <b>{{ tolerances.filter(item => item.toleranceMode !== "NONE").length }}</b></span>
      <span v-if="!mode?.readyToSwitch" class="qmp-warn">请先补齐“待核定”清单，否则不能启用</span>
    </div>

    <el-tabs v-model="activeTab" class="qmp-tabs">
      <el-tab-pane label="科室启用" name="departments">
        <el-table :data="mode?.departments || []" size="small" max-height="320">
          <el-table-column prop="departmentName" label="科室" min-width="120" />
          <el-table-column prop="entryMode" label="填报方式" width="100" />
          <el-table-column label="当前模式" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.quotaMode === 'QUOTA' ? 'success' : 'info'" effect="plain">
                {{ row.quotaMode === "QUOTA" ? "定额" : "实耗" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="effectiveDate" label="生效日" width="120" />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button
                v-if="row.quotaMode !== 'QUOTA'"
                size="small"
                type="primary"
                link
                :disabled="!mode?.readyToSwitch"
                @click="switchDepartment(row, 'QUOTA')"
              >启用定额</el-button>
              <el-button v-else size="small" type="warning" link @click="switchDepartment(row, 'LEGACY')">回退实耗</el-button>
            </template>
          </el-table-column>
        </el-table>
        <small class="qmp-hint">麻醉室、中医科不在本次 Excel 清单内，保持实耗。</small>
      </el-tab-pane>
      <el-tab-pane label="待核定清单" name="missing">
        <el-table :data="mode?.completeness.items || []" size="small" max-height="320">
          <el-table-column prop="departmentName" label="科室" width="110" />
          <el-table-column prop="itemName" label="耗材" min-width="180" show-overflow-tooltip />
          <el-table-column prop="unit" label="单位" width="80" />
          <el-table-column prop="reason" label="原因" min-width="200" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="免审上浮" name="tolerance">
        <div class="qmp-filter">
          <el-input v-model="keyword" clearable placeholder="搜索科室或耗材" style="width: 220px" />
          <small>默认 0：超量全部进入例外审核；可设百分比或绝对量。</small>
        </div>
        <el-table :data="visibleTolerances" size="small" max-height="360">
          <el-table-column prop="departmentName" label="科室" width="100" />
          <el-table-column prop="itemName" label="耗材" min-width="160" show-overflow-tooltip />
          <el-table-column prop="unit" label="单位" width="70" />
          <el-table-column label="定额" width="90" align="right">
            <template #default="{ row }">{{ row.standardQuantity ?? "—" }}</template>
          </el-table-column>
          <el-table-column label="免审方式" width="120">
            <template #default="{ row }">
              <el-select v-model="row.toleranceMode" size="small">
                <el-option label="无免审" value="NONE" />
                <el-option label="按百分比" value="PERCENT" />
                <el-option label="按绝对量" value="ABSOLUTE" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="免审值" width="140">
            <template #default="{ row }">
              <el-input-number
                v-model="row.toleranceValue"
                size="small"
                :min="0"
                :precision="2"
                :disabled="row.toleranceMode === 'NONE'"
                controls-position="right"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button size="small" type="primary" link :loading="savingRule === row.ruleId" @click="saveToleranceRow(row)">保存</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="switchDialog" title="启用定额消耗模式" width="480px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="切换后科室只能确认人数；定额与扣减数量由系统按院方核定值生成，科室不能修改。历史日报不重算。"
        style="margin-bottom: 12px"
      />
      <el-form label-position="top">
        <el-form-item label="生效日期" required>
          <el-date-picker v-model="effectiveDate" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="switchDialog = false">取消</el-button>
        <el-button type="primary" :loading="switching" @click="switchMode">确认启用</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import dayjs from "dayjs";
import {
  getInventoryQuotaModeApi,
  getInventoryQuotaTolerancesApi,
  switchInventoryQuotaModeApi,
  updateInventoryQuotaToleranceApi,
  type InventoryQuotaModeState,
  type InventoryQuotaToleranceRow
} from "@/api/modules/inventory";

const mode = ref<InventoryQuotaModeState>();
const tolerances = ref<InventoryQuotaToleranceRow[]>([]);
const loading = ref(false);
const switching = ref(false);
const savingRule = ref("");
const activeTab = ref<"departments" | "missing" | "tolerance">("departments");
const keyword = ref("");
const switchDialog = ref(false);
const effectiveDate = ref(dayjs().add(1, "day").format("YYYY-MM-DD"));

const visibleTolerances = computed(() => {
  const term = keyword.value.trim().toLowerCase();
  if (!term) return tolerances.value;
  return tolerances.value.filter(item =>
    `${item.departmentName} ${item.itemName}`.toLowerCase().includes(term)
  );
});

const load = async () => {
  loading.value = true;
  try {
    const [modeResult, toleranceResult] = await Promise.all([getInventoryQuotaModeApi(), getInventoryQuotaTolerancesApi()]);
    mode.value = modeResult.data;
    tolerances.value = toleranceResult.data.list ?? [];
    if (mode.value?.completeness.total) activeTab.value = "missing";
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "定额模式加载失败");
  } finally {
    loading.value = false;
  }
};

const switchMode = async () => {
  switching.value = true;
  try {
    const result = await switchInventoryQuotaModeApi({ mode: "QUOTA", effectiveDate: effectiveDate.value });
    mode.value = result.data;
    switchDialog.value = false;
    ElMessage.success("定额模式已启用");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "启用失败");
  } finally {
    switching.value = false;
  }
};

const rollback = async () => {
  try {
    await ElMessageBox.confirm("回退到实耗模式只影响未来业务日，已产生的定额日报和扣减不会逆转。确认回退？", "回退实耗模式", {
      type: "warning",
      confirmButtonText: "确认回退",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  switching.value = true;
  try {
    const result = await switchInventoryQuotaModeApi({ mode: "LEGACY", effectiveDate: dayjs().format("YYYY-MM-DD") });
    mode.value = result.data;
    ElMessage.success("已回退实耗模式");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "回退失败");
  } finally {
    switching.value = false;
  }
};

const saveToleranceRow = (row: any) => saveTolerance(row as InventoryQuotaToleranceRow);

const switchDepartment = async (row: any, nextMode: "QUOTA" | "LEGACY") => {
  const effectiveDate = nextMode === "QUOTA" ? dayjs().add(1, "day").format("YYYY-MM-DD") : dayjs().format("YYYY-MM-DD");
  try {
    const result = await switchInventoryQuotaModeApi({ mode: nextMode, effectiveDate, departmentKey: row.departmentKey });
    mode.value = result.data;
    ElMessage.success(nextMode === "QUOTA" ? `${row.departmentName} 已启用定额模式` : `${row.departmentName} 已回退实耗模式`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "科室模式切换失败");
  }
};

const saveTolerance = async (row: InventoryQuotaToleranceRow) => {
  savingRule.value = row.ruleId;
  try {
    const result = await updateInventoryQuotaToleranceApi({
      ruleId: row.ruleId,
      toleranceMode: row.toleranceMode,
      toleranceValue: Number(row.toleranceValue || 0)
    });
    tolerances.value = result.data.list ?? tolerances.value;
    ElMessage.success("免审上浮已保存");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存失败");
  } finally {
    savingRule.value = "";
  }
};

onMounted(load);
</script>

<style scoped lang="scss">
.quota-mode-panel {
  padding: 14px 16px;
  margin-bottom: 14px;
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;
  background: var(--hos-bg-primary);
}
.qmp-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  flex-wrap: wrap;
  strong {
    font-size: 15px;
    color: var(--hos-text-primary);
  }
  small {
    display: block;
    margin-top: 2px;
    color: var(--hos-text-muted);
    font-size: 11px;
  }
}
.qmp-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.qmp-effective {
  color: var(--hos-text-muted);
  font-size: 12px;
}
.qmp-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  margin: 12px 0 4px;
  color: var(--hos-text-secondary);
  font-size: 12px;
  b {
    color: var(--hos-text-primary);
    font-variant-numeric: tabular-nums;
  }
  b.danger {
    color: var(--el-color-danger);
  }
}
.qmp-warn {
  color: var(--el-color-warning);
}
.qmp-filter {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  small {
    color: var(--hos-text-muted);
  }
}
</style>
