<template>
  <div class="trace-page" v-loading="loading">
    <header class="page-head">
      <div>
        <span class="eyebrow">进销存 · 全院追溯</span>
        <h1>全链路追溯</h1>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" :loading="loading" aria-label="刷新" @click="search" />
        <el-dropdown @command="exportTrace">
          <el-button type="primary" plain>导出 ▾</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="xlsx">XLSX 明细</el-dropdown-item>
              <el-dropdown-item command="pdf">PDF 摘要</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <section class="search-card">
      <el-input
        v-model="keyword"
        class="trace-search"
        clearable
        placeholder="物资 / 拼音 / 批号 / 入库单号 / 申领单号 / 患者"
        @keyup.enter="search"
      />
      <el-button type="primary" :icon="Search" :loading="searching" @click="search">搜索</el-button>
    </section>

    <section class="filter-row">
      <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" @change="reloadLifecycle" />
      <el-select v-model="department" clearable filterable placeholder="科室" @change="reloadLifecycle">
        <el-option v-for="item in departmentOptions" :key="item" :label="item" :value="item" />
      </el-select>
      <el-select v-model="eventType" clearable placeholder="类型" @change="reloadLifecycle">
        <el-option label="入库" value="INBOUND" />
        <el-option label="发放" value="TRANSFER" />
        <el-option label="签收" value="RECEIVE" />
        <el-option label="使用" value="CONSUMPTION" />
        <el-option label="退回" value="RETURN" />
        <el-option label="报废" value="SCRAP" />
        <el-option label="盘点" value="COUNT" />
        <el-option label="红冲" value="REVERSAL" />
        <el-option label="待补扣" value="PENDING_DEDUCTION" />
      </el-select>
      <el-input v-model="operator" clearable placeholder="经办人" @keyup.enter="reloadLifecycle" />
      <el-checkbox v-model="abnormalOnly" @change="reloadLifecycle">只看异常</el-checkbox>
    </section>

    <el-tabs v-model="activeTab" class="trace-tabs">
      <el-tab-pane label="生命周期" name="lifecycle">
        <div v-if="subjects.length" class="subject-list">
          <button
            v-for="subject in subjects"
            :key="`${subject.kind}-${subject.id}`"
            type="button"
            class="subject-card"
            :class="{ 'is-active': selected?.id === subject.id }"
            @click="selectSubject(subject)"
          >
            <span class="subject-kind">{{ kindLabel(subject.kind) }}</span>
            <strong>{{ subject.label }}</strong>
            <small>{{ subject.note }}{{ subject.pendingCount ? ` · 待补扣 ${subject.pendingCount}` : "" }}</small>
          </button>
        </div>

        <template v-if="lifecycle">
          <div class="summary-strip">
            <div class="summary-title">
              <strong>{{ lifecycle.summary.itemName || selected?.label }}</strong>
              <small v-if="lifecycle.summary.spec">{{ lifecycle.summary.spec }}</small>
            </div>
            <span>进 <b>{{ formatNumber(lifecycle.summary.inboundTotal) }}</b> {{ lifecycle.summary.unit }}</span>
            <span>用 <b>{{ formatNumber(lifecycle.summary.usedTotal) }}</b> {{ lifecycle.summary.unit }}</span>
            <span>中心存 <b>{{ formatNumber(lifecycle.summary.centralAvailable) }}</b></span>
            <span>科室存 <b>{{ formatNumber(lifecycle.summary.departmentAvailable) }}</b></span>
            <span :class="{ warning: lifecycle.summary.pendingDeductionCount }">
              待补扣 <b>{{ lifecycle.summary.pendingDeductionCount }}</b>
            </span>
          </div>

          <div v-if="groupedEvents.length" class="timeline">
            <section v-for="group in groupedEvents" :key="group.date" class="timeline-day">
              <h3>{{ group.date }}</h3>
              <article
                v-for="event in group.events"
                :key="event.eventId"
                class="timeline-node"
                :class="`is-${event.status.toLowerCase()}`"
              >
                <span class="node-dot" aria-hidden="true" />
                <div class="node-main">
                  <div class="node-title">
                    <strong>{{ event.eventLabel }}</strong>
                    <span class="node-qty" :class="{ negative: event.quantity < 0 }">
                      {{ event.quantity > 0 ? "+" : "" }}{{ formatNumber(event.quantity) }} {{ event.unit }}
                    </span>
                    <el-tag size="small" effect="plain" :type="statusTone(event.status)">{{ statusLabel(event.status) }}</el-tag>
                  </div>
                  <div class="node-meta">
                    <button v-if="event.documentNo" type="button" class="link-button" @click="openDocument(event)">
                      {{ event.documentNo }}
                    </button>
                    <span v-if="event.batchNo">批号 {{ event.batchNo }}</span>
                    <span v-if="event.department">{{ event.department }}</span>
                    <span v-if="event.operator">{{ event.operator }}</span>
                    <span v-if="event.patient">患者 {{ event.patient.name }}{{ event.patient.visitNo ? ` · ${event.patient.visitNo}` : "" }}</span>
                  </div>
                  <p v-if="event.note" class="node-note">{{ event.note }}</p>
                </div>
              </article>
            </section>
          </div>
          <el-empty v-else :image-size="70" description="当前筛选下没有节点" />
          <div v-if="lifecycle && lifecycle.events.length < lifecycle.total" class="load-more">
            <el-button :loading="loadingMore" @click="loadMore">加载更多（已显示 {{ lifecycle.events.length }} / {{ lifecycle.total }}）</el-button>
          </div>
        </template>
        <el-empty v-else :image-size="80" description="搜索物资、批号、单号或患者开始追溯" />
      </el-tab-pane>

      <el-tab-pane label="流水明细" name="ledger">
        <el-table :data="ledgerRows" border height="calc(100vh - 380px)" empty-text="没有流水">
          <el-table-column prop="createdAt" label="时间" width="160" />
          <el-table-column prop="typeLabel" label="类型" width="110" />
          <el-table-column prop="itemName" label="物资" min-width="150" />
          <el-table-column prop="quantity" label="数量" width="100" />
          <el-table-column prop="department" label="科室" width="120" />
          <el-table-column prop="operator" label="经办人" width="110" />
          <el-table-column prop="reason" label="原因/摘要" min-width="220" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { Refresh, Search } from "@element-plus/icons-vue";
import dayjs from "dayjs";
import {
  exportInventoryTraceApi,
  getInventoryDbApi,
  getInventoryLedgerMovementsApi,
  getInventoryTraceLifecycleApi,
  getInventoryTraceSubjectsApi,
  type InventoryTraceEvent,
  type InventoryTraceLifecycle,
  type InventoryTraceSubject
} from "@/api/modules/inventory";

const router = useRouter();
const keyword = ref("");
const searching = ref(false);
const loading = ref(false);
const loadingMore = ref(false);
const subjects = ref<InventoryTraceSubject[]>([]);
const selected = ref<InventoryTraceSubject | null>(null);
const lifecycle = ref<InventoryTraceLifecycle | null>(null);
const activeTab = ref<"lifecycle" | "ledger">("lifecycle");
const dateRange = ref<string[]>([]);
const department = ref("");
const eventType = ref("");
const operator = ref("");
const abnormalOnly = ref(false);
const departmentOptions = ref<string[]>([]);
const ledgerRows = ref<any[]>([]);

const params = () => ({
  itemId: selected.value?.itemId || undefined,
  batchId: selected.value?.batchId || undefined,
  orderNo: selected.value?.kind === "INBOUND" ? selected.value.documentNo || selected.value.orderNo : undefined,
  requestId: selected.value?.kind === "REQUEST" ? selected.value.id : undefined,
  patientKeyword: selected.value?.kind === "PATIENT" ? selected.value.patientKeyword || selected.value.label : undefined,
  keyword: keyword.value || undefined,
  from: dateRange.value?.[0],
  to: dateRange.value?.[1],
  department: department.value || undefined,
  type: eventType.value || undefined,
  operator: operator.value || undefined,
  abnormal: abnormalOnly.value
});

const search = async () => {
  searching.value = true;
  try {
    const subjectResult = await getInventoryTraceSubjectsApi({
      keyword: keyword.value,
      from: dateRange.value?.[0],
      to: dateRange.value?.[1],
      department: department.value || undefined,
      type: eventType.value || undefined,
      operator: operator.value || undefined
    });
    subjects.value = subjectResult.data ?? [];
    if (!subjects.value.length) {
      selected.value = null;
      lifecycle.value = null;
      return;
    }
    const first = subjects.value.find(item => item.itemId || item.batchId) || subjects.value[0];
    await selectSubject(first);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "追溯搜索失败");
  } finally {
    searching.value = false;
  }
};

const selectSubject = async (subject: InventoryTraceSubject) => {
  selected.value = subject;
  activeTab.value = "lifecycle";
  await reloadLifecycle();
};

const reloadLifecycle = async () => {
  if (!selected.value) return;
  loading.value = true;
  try {
    const result = await getInventoryTraceLifecycleApi({ ...params(), page: 1, size: 20 });
    lifecycle.value = result.data ?? null;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取生命周期失败");
  } finally {
    loading.value = false;
  }
};

const loadMore = async () => {
  if (!lifecycle.value) return;
  loadingMore.value = true;
  try {
    const nextPage = lifecycle.value.page + 1;
    const result = await getInventoryTraceLifecycleApi({ ...params(), page: nextPage, size: 20 });
    const next = result.data;
    if (!next) return;
    lifecycle.value = {
      ...next,
      events: [...lifecycle.value.events, ...next.events]
    };
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "加载更多失败");
  } finally {
    loadingMore.value = false;
  }
};

const groupedEvents = computed(() => {
  const groups = new Map<string, InventoryTraceEvent[]>();
  (lifecycle.value?.events ?? []).forEach(event => {
    const date = event.occurredAt ? dayjs(event.occurredAt).format("YYYY-MM-DD") : "未知日期";
    if (!groups.has(date)) groups.set(date, []);
    groups.get(date)!.push(event);
  });
  return Array.from(groups.entries()).map(([date, events]) => ({ date, events }));
});

const kindLabel = (kind: string) =>
  ({ ITEM: "物资", BATCH: "批次", INBOUND: "入库单", REQUEST: "申领单", PATIENT: "患者" })[kind] || kind;
const statusLabel = (status: string) => (status === "PENDING" ? "待处理" : status === "WARNING" ? "异常" : "已完成");
const statusTone = (status: string) => (status === "PENDING" ? "warning" : status === "WARNING" ? "danger" : "success");
const formatNumber = (value?: number | string) => Number(value || 0).toLocaleString("zh-CN", { maximumFractionDigits: 2 });

const exportTrace = async (format: "xlsx" | "pdf") => {
  if (!selected.value) return ElMessage.warning("请先选择一个追溯对象");
  try {
    const file = await exportInventoryTraceApi(params(), format);
    const url = URL.createObjectURL(file.blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = file.filename;
    link.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "导出失败");
  }
};

const openDocument = (event: InventoryTraceEvent) => {
  if (event.documentType === "INBOUND") router.push("/inventory-system/warehouse");
  else if (event.documentType === "PATIENT") router.push("/inventory-system/patient-trace");
  else if (event.documentType === "REVIEW") router.push("/inventory-system/patient-review");
  else if (event.documentType === "REQUEST") router.push("/inventory-system/warehouse");
};

const loadLedger = async () => {
  try {
    const result = await getInventoryLedgerMovementsApi({
      from: dateRange.value?.[0],
      to: dateRange.value?.[1],
      department: department.value || undefined,
      keyword: keyword.value || undefined
    });
    ledgerRows.value = result.data ?? [];
  } catch {
    ledgerRows.value = [];
  }
};

onMounted(async () => {
  try {
    const db = await getInventoryDbApi();
    departmentOptions.value = Array.from(new Set((db.data?.requests ?? []).map((row: any) => row.department).filter(Boolean))) as string[];
  } catch {
    departmentOptions.value = [];
  }
  await search();
  void loadLedger();
});
</script>

<style scoped lang="scss">
.trace-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 20px;
  min-height: calc(100vh - 150px);
  background: #faf7f1;
  --ink: #2f2d27;
  --muted: #9a9285;
  --line: #efe8dd;
  --soft: #fffdf9;
  --green: #347d68;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;

  .eyebrow {
    color: var(--green);
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.08em;
  }

  h1 {
    margin: 2px 0 0;
    color: var(--ink);
    font-size: 22px;
  }
}

.head-actions {
  display: flex;
  gap: 8px;
}

.search-card {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 12px 14px;
  background: var(--soft);
  border: 1px solid var(--line);
  border-radius: 14px;

  .trace-search {
    flex: 1;
  }
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;

  .el-input {
    width: 150px;
  }
}

.trace-tabs {
  padding: 10px 16px 16px;
  background: var(--soft);
  border: 1px solid var(--line);
  border-radius: 14px;
}

.subject-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.subject-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 190px;
  padding: 8px 10px;
  color: var(--ink);
  background: #fbf9f5;
  border: 1px solid var(--line);
  border-radius: 10px;
  cursor: pointer;
  text-align: left;

  .subject-kind {
    color: var(--green);
    font-size: 10px;
  }

  small {
    color: var(--muted);
    font-size: 11px;
  }

  &.is-active {
    border-color: var(--green);
    background: #edf7f1;
  }
}

.summary-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: baseline;
  padding: 10px 2px;
  color: var(--muted);
  font-size: 12px;

  .summary-title {
    display: flex;
    gap: 6px;
    align-items: baseline;
    color: var(--ink);

    small {
      color: var(--muted);
    }
  }

  b {
    color: var(--ink);
    font-variant-numeric: tabular-nums;
  }

  .warning b {
    color: #b45309;
  }
}

.timeline {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.timeline-day {
  h3 {
    margin: 0 0 6px;
    color: var(--muted);
    font-size: 12px;
    font-weight: 600;
  }
}

.timeline-node {
  position: relative;
  display: flex;
  gap: 10px;
  padding: 8px 0 8px 6px;

  &::before {
    position: absolute;
    top: 20px;
    bottom: -10px;
    left: 11px;
    width: 1px;
    background: var(--line);
    content: "";
  }

  &:last-child::before {
    display: none;
  }

  .node-dot {
    z-index: 1;
    width: 10px;
    height: 10px;
    margin-top: 4px;
    background: var(--green);
    border: 2px solid var(--soft);
    border-radius: 50%;
    box-shadow: 0 0 0 1px var(--line);
  }

  &.is-pending .node-dot {
    background: #b45309;
  }

  &.is-warning .node-dot {
    background: #c45454;
  }

  .node-main {
    flex: 1;
    min-width: 0;
  }

  .node-title {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;

    strong {
      color: var(--ink);
      font-size: 13px;
    }
  }

  .node-qty {
    color: var(--green);
    font-variant-numeric: tabular-nums;
    font-size: 12px;

    &.negative {
      color: #b45309;
    }
  }

  .node-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-top: 3px;
    color: var(--muted);
    font-size: 11px;
  }

  .node-note {
    margin: 3px 0 0;
    color: #6d675c;
    font-size: 11px;
  }
}

.load-more {
  display: flex;
  justify-content: center;
  padding: 10px 0;
}

.link-button {
  padding: 0;
  color: var(--green);
  background: none;
  border: 0;
  cursor: pointer;
  font-size: 11px;
}
</style>
