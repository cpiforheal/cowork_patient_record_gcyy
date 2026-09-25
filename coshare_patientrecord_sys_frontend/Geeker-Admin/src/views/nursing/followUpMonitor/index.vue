<template>
  <div class="nfm-page">
    <!-- 段 1 · 今日速览：紧凑计数条，明确让位给下方时间轴 -->
    <section class="nfm-strip">
      <div class="nfm-strip-item">
        <span>所选区间操作</span>
        <b>{{ result?.summary?.rangeOps ?? 0 }}</b>
      </div>
      <div class="nfm-strip-item">
        <span>今日操作</span>
        <b>{{ result?.summary?.todayOps ?? 0 }}</b>
      </div>
      <div class="nfm-strip-item is-warning">
        <span>今日触达</span>
        <b>{{ result?.summary?.todayReached ?? 0 }}</b>
      </div>
      <div class="nfm-strip-item is-success">
        <span>今日回院</span>
        <b>{{ result?.summary?.todayArrived ?? 0 }}</b>
      </div>
      <div class="nfm-strip-item is-danger">
        <span>逾期未回院</span>
        <b>{{ result?.summary?.overduePending ?? 0 }}</b>
      </div>
    </section>

    <!-- 筛选与导出 -->
    <section class="nfm-toolbar">
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        size="small"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        class="nfm-range"
      />
      <el-select v-model="filterOperator" size="small" clearable placeholder="全部操作人" class="nfm-select">
        <el-option v-for="name in result?.operators || []" :key="name" :label="name" :value="name" />
      </el-select>
      <el-select v-model="filterAction" size="small" clearable placeholder="全部动作" class="nfm-select">
        <el-option v-for="item in NURSING_FOLLOWUP_ACTIONS" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-button size="small" :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      <span class="nfm-spacer"></span>
      <el-button size="small" :icon="Document" :loading="exportingXlsx" @click="exportXlsx">导出 XLSX</el-button>
      <el-button size="small" type="primary" :icon="Printer" :loading="exportingDocx" @click="exportDocx">
        导出纸质件 DOCX
      </el-button>
    </section>

    <!-- 段 2 · 操作时间轴 ★ 主视觉 -->
    <section v-loading="loading" class="nfm-timeline-card">
      <header class="nfm-card-head">
        <div>
          <h3>随访操作时间轴</h3>
          <p>
            共 <b>{{ result?.total ?? 0 }}</b> 条留痕 · 倒序 · 撤销同样留痕，不删除历史
          </p>
        </div>
      </header>

      <div v-if="dayGroups.length" class="nfm-timeline">
        <div v-for="group in dayGroups" :key="group.dateKey" class="nfm-day">
          <div class="nfm-day-head">
            <span class="nfm-day-date">{{ group.dateKey }}</span>
            <span class="nfm-day-week">{{ group.weekday }}</span>
            <span class="nfm-day-count">共 {{ group.items.length }} 条</span>
          </div>
          <ul class="nfm-day-list">
            <li v-for="item in group.items" :key="item.id" class="nfm-node">
              <div class="nfm-node-time">
                <span class="nfm-time-hm">{{ hm(item.createdAt) }}</span>
                <span class="nfm-time-sec">{{ sec(item.createdAt) }}</span>
              </div>
              <div class="nfm-node-axis"><span class="nfm-dot" :class="`is-${actionTone(item.action)}`"></span></div>
              <div class="nfm-node-body">
                <div class="nfm-node-title">
                  <el-tag :type="actionTag(item.action)" effect="plain" size="small">{{ item.actionLabel }}</el-tag>
                  <span class="nfm-node-patient">{{ patientOf(item) }}</span>
                  <span class="nfm-node-operator">
                    {{ item.operator }}<template v-if="item.operatorRole"> · {{ item.operatorRole }}</template>
                  </span>
                </div>
                <p class="nfm-node-detail">{{ item.detail || "—" }}</p>
              </div>
            </li>
          </ul>
        </div>
      </div>
      <el-empty v-else-if="!loading" description="所选时间内没有随访操作记录" :image-size="80" />

      <div v-if="(result?.total ?? 0) > pageSize" class="nfm-pager">
        <el-pagination
          layout="prev, pager, next, total"
          :total="result?.total ?? 0"
          :page-size="pageSize"
          :current-page="pageNum"
          @current-change="onPageChange"
        />
      </div>
    </section>

    <!-- 段 3 · 节点状态监控 -->
    <section class="nfm-table-card">
      <header class="nfm-card-head">
        <div>
          <h3>随访节点状态</h3>
          <p>每患者取最近一次复诊安排；逾期的行按严重度着色</p>
        </div>
      </header>
      <el-table :data="result?.nodes || []" size="small" border :row-class-name="nodeRowClass" max-height="420">
        <el-table-column prop="patientName" label="患者" min-width="120" show-overflow-tooltip />
        <el-table-column prop="seq" label="第N次" width="76" align="center" />
        <el-table-column prop="dueDate" label="应复查日" width="116" />
        <el-table-column label="已触达" width="88" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.reached" type="warning" effect="plain" size="small">已触达</el-tag>
            <span v-else class="nfm-muted">未触达</span>
          </template>
        </el-table-column>
        <el-table-column label="已回院" width="88" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.arrived" type="success" effect="plain" size="small">已回院</el-tag>
            <span v-else class="nfm-muted">未回院</span>
          </template>
        </el-table-column>
        <el-table-column prop="arrivedAt" label="回院日期" width="116" />
        <el-table-column label="逾期" width="96" align="center">
          <template #default="{ row }">
            <span v-if="row.overdueDays > 0" class="nfm-overdue">{{ row.overdueDays }} 天</span>
            <span v-else class="nfm-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastOperator" label="最近操作人" width="120" show-overflow-tooltip />
        <el-table-column prop="lastActionAt" label="最近操作时间" min-width="160" />
      </el-table>
      <p class="nfm-note">
        说明：本表与时间轴只覆盖<strong>服务端已入库</strong>的随访操作留痕与节点状态。通话内容（接通状态、患者反馈、下次跟进）
        目前仍暂存在各操作电脑本地，尚未入库，因此暂不纳入本页与导出资料。
      </p>
    </section>
  </div>
</template>

<script setup lang="ts" name="nursingFollowUpMonitor">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Document, Printer, Refresh } from "@element-plus/icons-vue";
import {
  NURSING_FOLLOWUP_ACTIONS,
  downloadNursingFollowUpMonitorDocxApi,
  downloadNursingFollowUpMonitorXlsxApi,
  loadNursingFollowUpMonitorApi,
  type NursingFollowUpMonitorResult,
  type NursingFollowUpTimelineRow
} from "@/api/modules/clinic/nursingFollowUpMonitor";

const loading = ref(false);
const exportingXlsx = ref(false);
const exportingDocx = ref(false);
const result = ref<NursingFollowUpMonitorResult | null>(null);

const today = () => {
  const now = new Date();
  const pad = (v: number) => String(v).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
};
const daysAgo = (days: number) => {
  const date = new Date();
  date.setDate(date.getDate() - days);
  const pad = (v: number) => String(v).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
};

const dateRange = ref<[string, string]>([daysAgo(6), today()]);
const filterOperator = ref("");
const filterAction = ref("");
const pageNum = ref(1);
const pageSize = ref(50);

/** 当前筛选条件（导出与查询共用，保证导出内容与页面所见一致） */
const currentQuery = computed(() => ({
  from: dateRange.value?.[0] || "",
  to: dateRange.value?.[1] || "",
  operator: filterOperator.value,
  action: filterAction.value,
  pageNum: pageNum.value,
  pageSize: pageSize.value
}));

const load = async () => {
  loading.value = true;
  try {
    const { data } = await loadNursingFollowUpMonitorApi(currentQuery.value);
    result.value = data;
  } catch (error: any) {
    ElMessage.error(error?.message || "护理随访留痕加载失败");
  } finally {
    loading.value = false;
  }
};

const reload = () => {
  pageNum.value = 1;
  void load();
};

const onPageChange = (page: number) => {
  pageNum.value = page;
  void load();
};

// 筛选条件变化即回到第一页重查，避免停留在越界页码导致空列表
watch([dateRange, filterOperator, filterAction], () => {
  pageNum.value = 1;
  void load();
});

onMounted(() => {
  void load();
});

// ---------- 时间轴展示 ----------

/** yyyy-MM-dd HH:mm:ss → HH:mm / :ss 分段，让时间戳成为视觉重心 */
const hm = (value: string) => (value && value.length >= 16 ? value.slice(11, 16) : "--:--");
const sec = (value: string) => (value && value.length >= 19 ? `:${value.slice(17, 19)}` : "");

const weekdayOf = (dateKey: string) => {
  const parts = dateKey.split("-").map(Number);
  if (parts.length !== 3 || parts.some(Number.isNaN)) return "";
  const date = new Date(parts[0], parts[1] - 1, parts[2]);
  return ["周日", "周一", "周二", "周三", "周四", "周五", "周六"][date.getDay()] || "";
};

const dayGroups = computed(() => {
  const timeline = result.value?.timeline || [];
  const groups: { dateKey: string; weekday: string; items: NursingFollowUpTimelineRow[] }[] = [];
  for (const item of timeline) {
    const key = item.dateKey || (item.createdAt || "").slice(0, 10);
    let group = groups.find(g => g.dateKey === key);
    if (!group) {
      group = { dateKey: key, weekday: weekdayOf(key), items: [] };
      groups.push(group);
    }
    group.items.push(item);
  }
  return groups;
});

const patientOf = (item: NursingFollowUpTimelineRow) => item.patientName || "";

/** 动作 → 语义色调，便于一眼分辨"达成/提醒/纠错/常规" */
const actionTone = (action: string) => {
  if (action === "followup.recall.arrived") return "success";
  if (action === "followup.recall.contact") return "warning";
  if (action === "followup.recall.arrived.undo") return "info";
  if (action === "followup.monitor.export") return "export";
  return "normal";
};
const actionTag = (action: string): "success" | "warning" | "info" | "primary" | "danger" => {
  if (action === "followup.recall.arrived") return "success";
  if (action === "followup.recall.contact") return "warning";
  if (action === "followup.recall.arrived.undo") return "info";
  if (action === "followup.image.remove") return "danger";
  return "primary";
};

const nodeRowClass = ({ row }: { row: { overdueDays: number } }) => {
  if (row.overdueDays >= 30) return "nfm-row-critical";
  if (row.overdueDays >= 7) return "nfm-row-late";
  if (row.overdueDays > 0) return "nfm-row-mild";
  return "";
};

// ---------- 导出 ----------

const download = async (
  fetcher: typeof downloadNursingFollowUpMonitorXlsxApi,
  format: string
): Promise<void> => {
  const { blob, filename } = await fetcher(currentQuery.value);
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  ElMessage.success(`已导出${format}：${filename}`);
};

const exportXlsx = async () => {
  exportingXlsx.value = true;
  try {
    await download(downloadNursingFollowUpMonitorXlsxApi, "XLSX");
    void load();
  } catch (error: any) {
    ElMessage.error(error?.message || "XLSX 导出失败");
  } finally {
    exportingXlsx.value = false;
  }
};

const exportDocx = async () => {
  exportingDocx.value = true;
  try {
    await download(downloadNursingFollowUpMonitorDocxApi, "纸质件");
    void load();
  } catch (error: any) {
    ElMessage.error(error?.message || "纸质件导出失败");
  } finally {
    exportingDocx.value = false;
  }
};
</script>

<style scoped lang="scss">
.nfm-page {
  display: grid;
  gap: 12px;
  max-width: 1360px;
  padding: 16px;
  margin: 0 auto;
}

/* ---------- 段 1 · 今日速览 ---------- */
.nfm-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  align-items: center;
  padding: 8px 14px;
  background: var(--el-fill-color-lighter);
  border-radius: 8px;

  .nfm-strip-item {
    display: flex;
    gap: 6px;
    align-items: baseline;
    font-size: 12px;
    color: var(--el-text-color-secondary);

    b {
      font-size: 17px;
      font-variant-numeric: tabular-nums;
      color: var(--el-text-color-primary);
    }

    &.is-warning b {
      color: var(--el-color-warning);
    }
    &.is-success b {
      color: var(--el-color-success);
    }
    &.is-danger b {
      color: var(--el-color-danger);
    }
  }
}

/* ---------- 工具条 ---------- */
.nfm-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;

  .nfm-range {
    width: 250px;
  }
  .nfm-select {
    width: 148px;
  }
  .nfm-spacer {
    flex: 1;
  }
}

/* ---------- 段 2 · 时间轴（主视觉） ---------- */
.nfm-timeline-card,
.nfm-table-card {
  padding: 14px 16px 16px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}

.nfm-card-head {
  margin-bottom: 10px;

  h3 {
    margin: 0;
    font-size: 15px;
    font-weight: 700;
    color: var(--el-text-color-primary);
  }

  p {
    margin: 3px 0 0;
    font-size: 12px;
    color: var(--el-text-color-secondary);

    b {
      color: var(--el-color-primary);
    }
  }
}

.nfm-timeline {
  display: grid;
  gap: 14px;
}

.nfm-day-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  padding-bottom: 6px;
  border-bottom: 1px dashed var(--el-border-color-lighter);

  .nfm-day-date {
    font-family: "JetBrains Mono", Consolas, monospace;
    font-size: 14px;
    font-weight: 700;
    color: var(--el-text-color-primary);
  }
  .nfm-day-week {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
  .nfm-day-count {
    margin-left: auto;
    font-size: 12px;
    color: var(--el-text-color-placeholder);
  }
}

.nfm-day-list {
  padding: 0;
  margin: 4px 0 0;
  list-style: none;
}

/* 时间戳左置 + 垂直轴：让"时间"成为第一眼焦点 */
.nfm-node {
  display: grid;
  grid-template-columns: 74px 22px minmax(0, 1fr);
  gap: 0 8px;
}

.nfm-node-time {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  padding-top: 8px;

  .nfm-time-hm {
    font-family: "JetBrains Mono", Consolas, monospace;
    font-size: 17px;
    font-weight: 700;
    line-height: 1.1;
    font-variant-numeric: tabular-nums;
    color: var(--el-text-color-primary);
  }
  .nfm-time-sec {
    font-family: "JetBrains Mono", Consolas, monospace;
    font-size: 11px;
    color: var(--el-text-color-placeholder);
  }
}

.nfm-node-axis {
  position: relative;
  display: flex;
  justify-content: center;

  /* 贯穿的垂直轴线 */
  &::before {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 50%;
    width: 1px;
    content: "";
    background: var(--el-border-color-lighter);
  }

  .nfm-dot {
    position: relative;
    z-index: 1;
    width: 9px;
    height: 9px;
    margin-top: 12px;
    background: var(--el-bg-color);
    border: 2px solid var(--el-border-color);
    border-radius: 50%;

    &.is-success {
      border-color: var(--el-color-success);
    }
    &.is-warning {
      border-color: var(--el-color-warning);
    }
    &.is-info {
      border-color: var(--el-color-info);
    }
    &.is-export {
      border-color: #8b5cf6;
    }
    &.is-normal {
      border-color: var(--el-color-primary);
    }
  }
}

.nfm-node-body {
  padding: 6px 0 12px;
  border-bottom: 1px solid var(--el-fill-color-lighter);

  .nfm-node-title {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }

  .nfm-node-patient {
    font-size: 13px;
    font-weight: 600;
    color: var(--el-text-color-primary);
  }

  .nfm-node-operator {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .nfm-node-detail {
    margin: 4px 0 0;
    font-size: 12px;
    line-height: 1.6;
    color: var(--el-text-color-regular);
  }
}

.nfm-pager {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}

/* ---------- 段 3 · 节点表 ---------- */
.nfm-muted {
  color: var(--el-text-color-placeholder);
}

.nfm-overdue {
  font-weight: 700;
  color: var(--el-color-danger);
}

.nfm-note {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);

  strong {
    color: var(--el-color-warning);
  }
}

:deep(.nfm-row-critical) {
  --el-table-tr-bg-color: color-mix(in srgb, #b91c1c 7%, var(--el-bg-color));
}
:deep(.nfm-row-late) {
  --el-table-tr-bg-color: color-mix(in srgb, #dc2626 5%, var(--el-bg-color));
}
:deep(.nfm-row-mild) {
  --el-table-tr-bg-color: color-mix(in srgb, #d97706 4%, var(--el-bg-color));
}
</style>
