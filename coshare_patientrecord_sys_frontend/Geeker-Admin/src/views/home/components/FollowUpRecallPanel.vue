<template>
  <section v-if="visible" class="recall-panel">
    <div class="recall-head">
      <div>
        <h3>复查召回</h3>
        <p>应复查未回访的患者清单 · 数据来自复诊记录的下次复查安排 · 电话召回直接转化复诊到访</p>
      </div>
      <el-button size="small" :loading="loading" @click="load">
        <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <div class="recall-metrics">
      <div class="metric is-danger">
        <b>{{ summary?.overdue.length ?? 0 }}</b>
        <span>逾期未复</span>
      </div>
      <div class="metric is-warning">
        <b>{{ summary?.dueSoon.length ?? 0 }}</b>
        <span>3 日内到期</span>
      </div>
      <div class="metric">
        <b>{{ summary?.upcomingCount ?? 0 }}</b>
        <span>已安排（更远期）</span>
      </div>
    </div>

    <div v-if="loading" class="recall-loading" v-loading="true" element-loading-text="召回清单生成中…" />
    <el-empty
      v-else-if="!overdueRows.length && !dueSoonRows.length"
      description="当前没有逾期或即将到期的复查，保持节奏"
      :image-size="56"
    />
    <el-table v-else :data="mergedRows" size="small" border class="recall-table">
      <el-table-column label="患者" min-width="120">
        <template #default="{ row }">
          <strong>{{ row.name || "未登记姓名" }}</strong>
          <small class="row-sub">{{ row.gender || "—" }} · {{ row.age || "—" }} 岁</small>
        </template>
      </el-table-column>
      <el-table-column label="电话" width="126">
        <template #default="{ row }">
          <span v-if="row.phone" class="row-phone" title="点击复制" @click="copyPhone(row.phone)">📞 {{ row.phone }}</span>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="住址 / 乡镇" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.address || "—" }}</template>
      </el-table-column>
      <el-table-column label="术式" min-width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.surgery || "—" }}</template>
      </el-table-column>
      <el-table-column label="应复查日" width="104">
        <template #default="{ row }">{{ row.dueDate }}</template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag size="small" :type="row.overdueDays > 0 ? 'danger' : 'warning'" effect="light">
            {{ row.overdueDays > 0 ? `逾期 ${row.overdueDays} 天` : "即将到期" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="末次复诊" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.reason }}{{ row.conditionNote ? ` · ${row.conditionNote}` : "" }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" plain :disabled="Boolean(row.lastContactAt)" @click="markContacted(row)">
            {{ row.lastContactAt ? "已联系" : "标记已联系" }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <small v-if="summary" class="recall-foot">
      共 {{ mergedRows.length }} 人待召回（逾期 {{ overdueRows.length }} · 即将到期 {{ dueSoonRows.length }}）· 生成于
      {{ summary.generatedAt }} · "已联系"以审计留痕为准，不影响复诊记录内容
    </small>
  </section>
</template>

<script setup lang="ts" name="FollowUpRecallPanel">
// 复查召回看板：到期/逾期未复查患者清单，供医生岗电话召回（创建时间系统记录，不做推送）
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import { useUserStore } from "@/stores/modules/user";
import {
  loadRecallSummaryApi,
  markRecallContactedApi,
  type RecallRow,
  type RecallSummary
} from "@/api/modules/clinic/followUp";

const userStore = useUserStore();
const role = computed(() => userStore.userInfo.role || "");
const visible = computed(() => ["doctor", "admin", "inspection"].includes(role.value));

const summary = ref<RecallSummary | null>(null);
const loading = ref(false);

const overdueRows = computed(() => summary.value?.overdue || []);
const dueSoonRows = computed(() => summary.value?.dueSoon || []);
const mergedRows = computed(() => [...overdueRows.value, ...dueSoonRows.value]);

const load = async () => {
  if (!visible.value) return;
  loading.value = true;
  try {
    const { data } = await loadRecallSummaryApi();
    summary.value = data;
  } catch (error: any) {
    if (error?.name !== "AbortError") ElMessage.error(error?.message || "复查召回清单加载失败");
  } finally {
    loading.value = false;
  }
};

const copyPhone = async (phone: string) => {
  try {
    await navigator.clipboard.writeText(phone);
    ElMessage.success(`已复制 ${phone}`);
  } catch {
    ElMessage.warning("复制失败，请手动记录");
  }
};

const markContacted = async (row: RecallRow) => {
  try {
    await markRecallContactedApi(row.visitId);
    row.lastContactAt = new Date().toISOString().slice(0, 19).replace("T", " ");
    ElMessage.success(`已标记联系 ${row.name || ""}`);
  } catch (error: any) {
    ElMessage.error(error?.message || "标记失败");
  }
};

onMounted(() => {
  void load();
});
</script>

<style scoped lang="scss">
.recall-panel {
  display: grid;
  gap: 12px;
  padding: 16px 18px;
  border: 1px solid var(--hos-chart-line-soft, rgb(90 110 130 / 8%));
  border-radius: 18px;
  background: color-mix(in srgb, var(--el-color-warning) 4%, var(--hos-chart-panel, #ffffff));
}
.recall-head {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  justify-content: space-between;
  h3 {
    margin: 0;
    font-size: 18px;
    font-weight: 800;
    color: var(--hos-chart-text, #2d2f33);
  }
  p {
    margin: 6px 0 0;
    font-size: 13px;
    color: var(--hos-chart-muted, #74777d);
  }
}
.recall-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  .metric {
    display: grid;
    gap: 2px;
    justify-items: center;
    min-width: 120px;
    padding: 10px 18px;
    background: var(--hos-chart-panel, #ffffff);
    border: 1px solid var(--hos-chart-line-soft, rgb(90 110 130 / 10%));
    border-radius: 14px;
    b {
      font-size: 24px;
      font-weight: 800;
      color: var(--hos-chart-text, #2d2f33);
      font-variant-numeric: tabular-nums;
    }
    span {
      font-size: 12px;
      color: var(--hos-chart-muted, #74777d);
    }
    &.is-danger b {
      color: var(--el-color-danger);
    }
    &.is-warning b {
      color: var(--el-color-warning);
    }
  }
}
.recall-loading {
  min-height: 120px;
}
.recall-table {
  width: 100%;
  .row-sub {
    display: block;
    font-size: 11px;
    color: var(--el-text-color-secondary);
  }
  .row-phone {
    cursor: pointer;
    font-weight: 600;
    color: var(--el-color-primary);
    font-variant-numeric: tabular-nums;
    &:hover {
      text-decoration: underline;
    }
  }
}
.recall-foot {
  color: var(--hos-chart-muted, #74777d);
}
</style>
