<template>
  <section class="trace-workspace" v-loading="loading">
    <header class="trace-toolbar">
      <div class="trace-copy">
        <span class="eyebrow">对账与追溯 / 患者耗材</span>
        <h2>患者耗材追溯</h2>
        <p>从患者出发，逐层查看就诊、科室服务实例、耗材明细、审核结果和库存流水，确认“谁在什么时候用了什么”。</p>
      </div>
      <div class="trace-actions">
        <el-input
          v-model="keyword"
          clearable
          placeholder="患者姓名 / 就诊号"
          class="trace-search"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" :icon="Search" :loading="searching" @click="search">搜索患者</el-button>
        <el-button :icon="Refresh" :disabled="!selectedPatient" @click="reloadTrace">刷新追溯</el-button>
      </div>
    </header>

    <div class="trace-layout">
      <aside class="result-panel">
        <div class="panel-head">
          <h3>患者</h3>
          <span>{{ results.length }} 条结果</span>
        </div>
        <div class="result-list">
          <button
            v-for="patient in results"
            :key="patient.encounterId"
            type="button"
            class="result-card"
            :class="{ 'is-active': selectedPatient?.patientId === patient.patientId }"
            @click="selectPatient(patient)"
          >
            <span class="result-main">
              <strong>{{ patient.patientName || "未命名患者" }}</strong>
              <span>{{ patient.visitNo || "无就诊号" }} · {{ patient.departmentName || "未归属科室" }}</span>
            </span>
            <span class="result-side">
              <small>{{ patient.visitDate || "" }}</small>
              <el-tag size="small" effect="plain" type="info">{{ patient.careType === "inpatient" ? "住院" : "门诊" }}</el-tag>
            </span>
          </button>
          <el-empty v-if="!results.length" :image-size="70" description="输入患者姓名或就诊号开始追溯" />
        </div>
      </aside>

      <main v-if="trace" class="trace-panel">
        <header class="trace-summary">
          <div>
            <div class="trace-title">
              <h3>{{ selectedPatient?.patientName || "患者耗材记录" }}</h3>
              <el-tag v-if="selectedPatient" effect="plain" type="info">{{ selectedPatient.visitNo || "无就诊号" }}</el-tag>
            </div>
            <p>
              共 {{ trace.instances.length }} 个科室服务实例
              <span>·</span>
              {{ totalLines }} 条耗材明细
              <span>·</span>
              {{ approvedLines }} 条已审核通过
            </p>
          </div>
          <span class="cutover-note">切换日期：{{ trace.cutoverDate || "未设置" }}</span>
        </header>

        <div class="instance-list">
          <article v-for="instance in trace.instances" :key="instance.serviceInstanceId" class="instance-card">
            <header class="instance-head">
              <div class="instance-title">
                <strong>{{ instance.departmentName }}</strong>
                <el-tag :type="statusType(instance.status)" size="small" effect="plain">{{ statusLabel(instance.status) }}</el-tag>
                <el-tag v-if="instance.sourceType === 'MANUAL'" size="small" type="warning" effect="plain">手动补录</el-tag>
              </div>
              <div class="instance-meta">
                {{ instance.serviceName || "服务" }}
                <span>·</span>
                {{ formatDateTime(instance.serviceStartedAt) }}
                <span>·</span>
                就诊 {{ instance.encounterId }}
              </div>
            </header>

            <div class="line-table">
              <div class="line-row line-header">
                <span>耗材</span>
                <span>单位</span>
                <span>实际用量</span>
                <span>审核</span>
                <span>库存流水</span>
              </div>
              <div v-for="line in instance.lines" :key="line.lineId" class="line-row">
                <span class="item-name">
                  {{ line.itemName }}
                  <el-tag v-if="line.source === 'MANUAL'" size="small" type="warning" effect="plain">预设外</el-tag>
                </span>
                <span>{{ line.unit }}</span>
                <span class="number">{{ formatQuantity(line.actualQuantity) }}</span>
                <span>
                  <el-tag :type="lineStatusType(line.reviewStatus)" size="small" effect="plain">
                    {{ lineStatusLabel(line.reviewStatus) }}
                  </el-tag>
                </span>
                <span class="event-cell">
                  <code v-if="line.consumptionEventId">{{ line.consumptionEventId }}</code>
                  <span v-else>未生成</span>
                </span>
              </div>
              <div v-if="!instance.lines.length" class="line-row empty-row">该服务未使用耗材</div>
            </div>

            <p v-if="instance.lines.some(line => line.reviewNote)" class="review-notes">
              审核意见：
              <template v-for="line in instance.lines.filter(line => line.reviewNote)" :key="line.lineId">
                {{ line.itemName }}：{{ line.reviewNote }}；
              </template>
            </p>
          </article>

          <el-empty v-if="!trace.instances.length" :image-size="86" description="该患者暂无耗材服务记录" />
        </div>
      </main>

      <main v-else class="trace-panel empty-panel">
        <el-empty :image-size="96" description="选择左侧患者查看耗材追溯" />
      </main>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh, Search } from "@element-plus/icons-vue";
import dayjs from "dayjs";
import {
  getInventoryPatientTraceApi,
  searchInventoryPatientsApi,
  type InventoryPatientDetail,
  type InventoryPatientSearchResult
} from "@/api/modules/inventory";

const keyword = ref("");
const searching = ref(false);
const loading = ref(false);
const results = ref<InventoryPatientSearchResult["list"]>([]);
const selectedPatient = ref<InventoryPatientSearchResult["list"][number] | null>(null);
const trace = ref<{
  patientId: string;
  cutoverDate?: string;
  instances: InventoryPatientDetail[];
} | null>(null);

const totalLines = computed(() => (trace.value?.instances ?? []).reduce((sum, instance) => sum + instance.lines.length, 0));
const approvedLines = computed(() =>
  (trace.value?.instances ?? []).reduce(
    (sum, instance) => sum + instance.lines.filter(line => line.reviewStatus === "APPROVED").length,
    0
  )
);

const search = async () => {
  searching.value = true;
  try {
    const result = await searchInventoryPatientsApi(keyword.value.trim());
    results.value = result.data?.list ?? [];
    if (!results.value.length) {
      trace.value = null;
      selectedPatient.value = null;
      ElMessage.info("没有找到匹配患者");
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "搜索患者失败");
  } finally {
    searching.value = false;
  }
};

const selectPatient = async (patient: InventoryPatientSearchResult["list"][number]) => {
  selectedPatient.value = patient;
  await loadTrace(patient.patientId);
};

const loadTrace = async (patientId: string) => {
  loading.value = true;
  try {
    const result = await getInventoryPatientTraceApi(patientId);
    trace.value = result.data ?? null;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取患者追溯失败");
  } finally {
    loading.value = false;
  }
};

const reloadTrace = async () => {
  if (selectedPatient.value) await loadTrace(selectedPatient.value.patientId);
};

const statusLabel = (status: string) => {
  switch (status) {
    case "NOT_STARTED":
      return "未开始";
    case "DRAFT":
      return "草稿";
    case "SUBMITTED":
      return "待审核";
    case "PARTIALLY_APPROVED":
      return "部分通过";
    case "RETURNED":
      return "已驳回";
    case "APPROVED":
      return "已通过";
    default:
      return status || "未知";
  }
};

const statusType = (status: string): "success" | "warning" | "danger" | "info" => {
  switch (status) {
    case "APPROVED":
      return "success";
    case "RETURNED":
      return "danger";
    case "SUBMITTED":
    case "PARTIALLY_APPROVED":
      return "warning";
    default:
      return "info";
  }
};

const lineStatusLabel = (status: string) => {
  switch (status) {
    case "APPROVED":
      return "已通过";
    case "REJECTED":
      return "已驳回";
    default:
      return "待审核";
  }
};

const lineStatusType = (status: string): "success" | "warning" | "danger" | "info" => {
  if (status === "APPROVED") return "success";
  if (status === "REJECTED") return "danger";
  return "warning";
};

const formatQuantity = (value?: number | null) => {
  if (value === null || value === undefined) return "—";
  return Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
};

const formatDateTime = (value?: string) => {
  if (!value) return "未记录服务时间";
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format("YYYY-MM-DD HH:mm") : value;
};
</script>

<style scoped lang="scss">
.trace-workspace {
  --hos-bg-primary: #fffdf9;
  --hos-bg-surface: #faf7f1;
  --hos-border: #e6ded1;
  --hos-border-light: #f1ebe1;
  --hos-text-primary: #2f2d27;
  --hos-text-secondary: #6d675c;
  --hos-text-muted: #9a9285;
  --hos-primary: #347d68;
  --hos-primary-soft: #edf7f1;
  --hos-status-success: #3f8a62;
  --hos-status-success-soft: #eef8f1;
  --hos-status-warning: #b7791f;
  --hos-status-warning-soft: #fff7e8;
  --hos-status-danger: #c45454;
  --hos-status-danger-soft: #fdf0ef;

  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px;
  min-height: calc(100vh - 180px);
  background:
    radial-gradient(circle at 10% 0%, rgb(237 247 241 / 85%), transparent 30%),
    var(--hos-bg-surface);
}

.trace-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 22px 24px;
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 18px;
  box-shadow: var(--hos-shadow-card);
}

.trace-copy {
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
    font-size: 24px;
    font-weight: 700;
  }

  p {
    margin: 0;
    color: var(--hos-text-secondary);
    font-size: 13px;
    line-height: 1.7;
  }
}

.trace-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.trace-search {
  width: 260px;
}

.trace-layout {
  display: grid;
  grid-template-columns: minmax(270px, 330px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.result-panel,
.trace-panel {
  background: var(--hos-bg-primary);
  border: 1px solid var(--hos-border-light);
  border-radius: 18px;
  box-shadow: var(--hos-shadow-card);
}

.result-panel {
  position: sticky;
  top: 16px;
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 220px);
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--hos-border-light);

  h3 {
    margin: 0;
    color: var(--hos-text-primary);
    font-size: 14px;
    font-weight: 600;
  }

  span {
    color: var(--hos-text-muted);
    font-size: 12px;
  }
}

.result-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  overflow: auto;
}

.result-card {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 12px;
  background: var(--hos-bg-surface);
  border: 1px solid transparent;
  border-radius: 12px;
  cursor: pointer;
  text-align: left;
  transition:
    border-color 160ms ease,
    background 160ms ease,
    transform 160ms ease;

  &:hover {
    border-color: var(--hos-border);
    transform: translateY(-1px);
  }

  &.is-active {
    background: var(--hos-primary-soft);
    border-color: var(--hos-primary);
  }
}

.result-main,
.result-side {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.result-main strong {
  color: var(--hos-text-primary);
  font-size: 14px;
}

.result-main span,
.result-side small {
  color: var(--hos-text-muted);
  font-size: 11px;
}

.result-side {
  flex-shrink: 0;
  align-items: flex-end;
}

.trace-panel {
  display: flex;
  flex-direction: column;
  min-height: 640px;
  padding: 20px;
}

.empty-panel {
  display: grid;
  place-items: center;
}

.trace-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--hos-border-light);

  p {
    margin: 6px 0 0;
    color: var(--hos-text-secondary);
    font-size: 12px;

    span {
      margin: 0 4px;
      opacity: 0.5;
    }
  }
}

.trace-title {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;

  h3 {
    margin: 0;
    color: var(--hos-text-primary);
    font-size: 22px;
    font-weight: 700;
  }
}

.cutover-note {
  color: var(--hos-text-muted);
  font-size: 11px;
}

.instance-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-top: 14px;
  overflow: auto;
}

.instance-card {
  border: 1px solid var(--hos-border-light);
  border-radius: 14px;
  overflow: hidden;
}

.instance-head {
  padding: 12px 14px;
  background: var(--hos-bg-surface);
}

.instance-title {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;

  strong {
    color: var(--hos-text-primary);
    font-size: 14px;
  }
}

.instance-meta {
  margin-top: 4px;
  color: var(--hos-text-muted);
  font-size: 11px;

  span {
    margin: 0 4px;
    opacity: 0.6;
  }
}

.line-table {
  display: flex;
  flex-direction: column;
}

.line-row {
  display: grid;
  grid-template-columns: minmax(180px, 1.6fr) 70px 90px 90px minmax(160px, 1fr);
  gap: 8px;
  align-items: center;
  min-height: 44px;
  padding: 8px 14px;
  border-top: 1px solid var(--hos-border-light);
  color: var(--hos-text-secondary);
  font-size: 12px;

  &.line-header {
    min-height: 34px;
    color: var(--hos-text-muted);
    font-size: 11px;
  }

  &.empty-row {
    display: block;
    color: var(--hos-text-muted);
    text-align: center;
  }
}

.item-name {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  color: var(--hos-text-primary);
}

.number {
  color: var(--hos-text-primary);
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.event-cell {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  code {
    color: var(--hos-primary);
    font-size: 11px;
  }

  span {
    color: var(--hos-text-muted);
  }
}

.review-notes {
  margin: 0;
  padding: 10px 14px;
  color: var(--hos-status-danger);
  font-size: 11px;
  background: color-mix(in srgb, var(--hos-status-danger-soft) 40%, transparent);
  border-top: 1px solid var(--hos-border-light);
}

@media (max-width: 960px) {
  .trace-workspace {
    padding: 12px;
  }

  .trace-layout {
    grid-template-columns: 1fr;
  }

  .result-panel {
    position: static;
    max-height: 340px;
  }

  .trace-search {
    width: 100%;
  }

  .line-row {
    grid-template-columns: 1fr 80px 90px;
  }

  .line-row > :nth-child(4),
  .line-row > :nth-child(5) {
    display: none;
  }
}
</style>
