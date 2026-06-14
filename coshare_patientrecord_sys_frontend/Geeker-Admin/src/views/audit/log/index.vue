<template>
  <div class="main-box audit-log-layout">
    <TreeFilter
      id="id"
      label="label"
      title="审计视角"
      :data="auditTree"
      :default-value="activeTreeValue"
      @change="changeAuditScope"
    >
      <template #default="{ row }">
        <span class="audit-tree-node" :class="{ 'is-group': row.data.kind === 'group' }">
          <span class="tree-label">{{ row.data.label }}</span>
          <span v-if="row.data.count !== undefined" class="tree-count">{{ row.data.count }}</span>
        </span>
      </template>
    </TreeFilter>

    <section class="table-box audit-log-page">
      <div class="audit-scope-card">
        <div>
          <span class="scope-eyebrow">当前审计范围</span>
          <h2>{{ activeScopeTitle }}</h2>
          <p>{{ activeScopeDesc }}</p>
        </div>
        <div class="scope-actions">
          <el-tag effect="plain">匹配 {{ currentTotal }} 条</el-tag>
          <el-button :icon="Refresh" @click="refreshAuditLogs">刷新</el-button>
          <el-button @click="changeAuditScope('')">全部日志</el-button>
        </div>
      </div>

      <ProTable
        ref="proTable"
        :columns="columns"
        :request-api="getAuditLogListApi"
        :data-callback="dataCallback"
        :init-param="initParam"
        :search-col="{ xs: 1, sm: 1, md: 2, lg: 3, xl: 4 }"
      >
        <template #tableHeader>
          <el-alert
            title="左侧按账号、患者、模块和结果聚合日志；右侧保留精确筛选，适合复现单个账号或单个患者的连续操作。"
            type="info"
            show-icon
            :closable="false"
          />
        </template>

        <template #module="{ row }">
          <el-tag effect="plain">{{ moduleLabel(row.module) }}</el-tag>
        </template>

        <template #result="{ row }">
          <el-tag :type="row.result === 'denied' ? 'danger' : 'success'" effect="plain">
            {{ row.result === "denied" ? "已拒绝" : "成功" }}
          </el-tag>
        </template>

        <template #targetLabel="{ row }">
          <span>{{ row.targetLabel || row.targetKey || "-" }}</span>
        </template>

        <template #operation="{ row }">
          <el-button type="primary" link @click="openDrawer(row)">复现详情</el-button>
        </template>
      </ProTable>

      <el-drawer v-model="drawerVisible" title="操作流程复现" size="680px" destroy-on-close>
        <div v-if="activeLog" class="audit-drawer">
          <section class="event-head">
            <div>
              <strong>{{ activeLog.action }}</strong>
              <span>{{ activeLog.time }} / {{ activeLog.operator }} / {{ activeLog.role }}</span>
            </div>
            <el-tag :type="activeLog.result === 'denied' ? 'danger' : 'success'" effect="plain">
              {{ activeLog.result === "denied" ? "已拒绝" : "成功" }}
            </el-tag>
          </section>

          <el-descriptions :column="1" border>
            <el-descriptions-item label="患者">{{ activeLog.patient || "-" }}</el-descriptions-item>
            <el-descriptions-item label="模块">{{ moduleLabel(activeLog.module) }}</el-descriptions-item>
            <el-descriptions-item label="对象类型">{{ activeLog.targetType || "-" }}</el-descriptions-item>
            <el-descriptions-item label="对象名称">{{
              activeLog.targetLabel || activeLog.targetKey || "-"
            }}</el-descriptions-item>
            <el-descriptions-item label="动作编码">{{ activeLog.actionCode || "-" }}</el-descriptions-item>
            <el-descriptions-item label="说明">{{ activeLog.detail }}</el-descriptions-item>
          </el-descriptions>

          <section class="change-panel">
            <h3>数据变化</h3>
            <div class="change-grid">
              <div>
                <span>修改前</span>
                <p>{{ activeLog.beforeValue || "空" }}</p>
              </div>
              <div>
                <span>修改后</span>
                <p>{{ activeLog.afterValue || "空" }}</p>
              </div>
            </div>
          </section>

          <section class="related-panel">
            <div class="related-head">
              <h3>同账号最近操作</h3>
              <el-tag effect="plain">{{ relatedOperatorLogs.length }} 条</el-tag>
            </div>
            <el-empty v-if="!relatedOperatorLogs.length" description="暂无同账号操作" />
            <el-timeline v-else>
              <el-timeline-item
                v-for="log in relatedOperatorLogs"
                :key="log.id"
                :timestamp="log.time"
                :type="log.result === 'denied' ? 'danger' : 'success'"
              >
                <div class="timeline-card" :class="{ active: log.id === activeLog.id }">
                  <strong>{{ log.action }}</strong>
                  <span>{{ moduleLabel(log.module) }} / {{ log.patient || "无患者" }}</span>
                  <p>{{ log.detail }}</p>
                </div>
              </el-timeline-item>
            </el-timeline>
          </section>

          <section v-if="relatedPatientLogs.length" class="related-panel">
            <div class="related-head">
              <h3>同患者操作链</h3>
              <el-tag type="success" effect="plain">{{ relatedPatientLogs.length }} 条</el-tag>
            </div>
            <el-timeline>
              <el-timeline-item
                v-for="log in relatedPatientLogs"
                :key="log.id"
                :timestamp="log.time"
                :type="log.result === 'denied' ? 'danger' : 'success'"
              >
                <div class="timeline-card" :class="{ active: log.id === activeLog.id }">
                  <strong>{{ log.operator }} / {{ log.action }}</strong>
                  <span>{{ moduleLabel(log.module) }} / {{ log.targetLabel || log.targetKey || "-" }}</span>
                  <p>{{ log.detail }}</p>
                </div>
              </el-timeline-item>
            </el-timeline>
          </section>
        </div>
      </el-drawer>
    </section>
  </div>
</template>

<script setup lang="ts" name="auditLog">
import { computed, onMounted, reactive, ref } from "vue";
import { Refresh } from "@element-plus/icons-vue";
import TreeFilter from "@/components/TreeFilter/index.vue";
import ProTable from "@/components/ProTable/index.vue";
import { ColumnProps, ProTableInstance } from "@/components/ProTable/interface";
import { getAuditLogListApi, type AuditLogRow } from "@/api/modules/clinic";

type AuditTreeNode = {
  id: string;
  label: string;
  count?: number;
  kind?: "group" | "leaf";
  children?: AuditTreeNode[];
};

const proTable = ref<ProTableInstance>();
const drawerVisible = ref(false);
const activeLog = ref<AuditLogRow>();
const activeTreeValue = ref("");
const allAuditLogs = ref<AuditLogRow[]>([]);
const currentPageLogs = ref<AuditLogRow[]>([]);
const currentTotal = ref(0);

const initParam = reactive<{
  operator?: string;
  patient?: string;
  module?: string;
  result?: string;
}>({});

const moduleOptions = [
  { label: "患者", value: "patient" },
  { label: "病历", value: "record" },
  { label: "附件", value: "document" },
  { label: "归档", value: "archive" },
  { label: "模板", value: "template" },
  { label: "系统", value: "system" }
];

const resultOptions = [
  { label: "成功", value: "success" },
  { label: "已拒绝", value: "denied" }
];

const moduleLabel = (module?: string) => moduleOptions.find(item => item.value === module)?.label || module || "旧日志";
const resultLabel = (result?: string) => resultOptions.find(item => item.value === result)?.label || result || "未知结果";

const columns = reactive<ColumnProps<AuditLogRow>[]>([
  { type: "index", label: "#", width: 70 },
  { prop: "time", label: "时间戳", width: 170 },
  { prop: "operator", label: "操作者", width: 120, search: { el: "input" } },
  { prop: "role", label: "岗位", width: 120 },
  { prop: "patient", label: "患者", width: 120, search: { el: "input" } },
  { prop: "module", label: "模块", width: 100, enum: moduleOptions, search: { el: "select" } },
  { prop: "action", label: "动作", width: 150, search: { el: "input" } },
  {
    prop: "result",
    label: "结果",
    width: 100,
    enum: resultOptions,
    search: { el: "select" }
  },
  { prop: "targetLabel", label: "对象", minWidth: 160 },
  { prop: "detail", label: "摘要", minWidth: 260 },
  { prop: "operation", label: "操作", fixed: "right", width: 100 }
]);

const countBy = (
  logs: AuditLogRow[],
  getter: (log: AuditLogRow) => string | undefined,
  labelGetter?: (value: string) => string
) => {
  const counter = new Map<string, number>();
  logs.forEach(log => {
    const value = getter(log)?.trim();
    if (!value) return;
    counter.set(value, (counter.get(value) || 0) + 1);
  });
  return [...counter.entries()]
    .sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0], "zh-Hans-CN"))
    .map(([value, count]) => ({
      value,
      label: labelGetter ? labelGetter(value) : value,
      count
    }));
};

const groupNode = (id: string, label: string, children: AuditTreeNode[]): AuditTreeNode => ({
  id: `group::${id}`,
  label,
  count: children.reduce((total, child) => total + (child.count || 0), 0),
  kind: "group",
  children
});

const auditTree = computed<AuditTreeNode[]>(() => [
  groupNode(
    "operator",
    "按账号",
    countBy(
      allAuditLogs.value,
      log => log.operator,
      value => {
        const role = allAuditLogs.value.find(log => log.operator === value)?.role;
        return role ? `${value} / ${role}` : value;
      }
    ).map(item => ({
      id: `operator::${item.value}`,
      label: item.label,
      count: item.count,
      kind: "leaf"
    }))
  ),
  groupNode(
    "patient",
    "按患者",
    countBy(allAuditLogs.value, log => log.patient).map(item => ({
      id: `patient::${item.value}`,
      label: item.label,
      count: item.count,
      kind: "leaf"
    }))
  ),
  groupNode(
    "module",
    "按模块",
    countBy(allAuditLogs.value, log => log.module, moduleLabel).map(item => ({
      id: `module::${item.value}`,
      label: item.label,
      count: item.count,
      kind: "leaf"
    }))
  ),
  groupNode(
    "result",
    "按结果",
    countBy(allAuditLogs.value, log => log.result, resultLabel).map(item => ({
      id: `result::${item.value}`,
      label: item.label,
      count: item.count,
      kind: "leaf"
    }))
  )
]);

const activeScopeTitle = computed(() => {
  if (!activeTreeValue.value) return "全部操作日志";
  const [type, value] = activeTreeValue.value.split("::");
  if (type === "group") {
    const group = auditTree.value.find(item => item.id === activeTreeValue.value);
    return group ? group.label : "全部操作日志";
  }
  if (type === "operator") return `账号：${value}`;
  if (type === "patient") return `患者：${value}`;
  if (type === "module") return `模块：${moduleLabel(value)}`;
  if (type === "result") return `结果：${resultLabel(value)}`;
  return "全部操作日志";
});

const activeScopeDesc = computed(() => {
  if (!activeTreeValue.value) return "按时间倒序展示所有可追溯操作，可继续使用上方搜索做精确筛选。";
  const [type] = activeTreeValue.value.split("::");
  if (type === "operator") return "正在查看该账号的连续操作记录，适合排查误操作、越权尝试和资料流转路径。";
  if (type === "patient") return "正在查看该患者相关操作，适合复现资料修改、附件上传、质控和归档过程。";
  if (type === "module") return "正在查看该业务模块下的操作，适合定位某类配置或流程变更。";
  if (type === "result") return "正在按执行结果聚合日志，适合集中排查拒绝、失败或异常操作。";
  return "选择左侧具体节点后，将自动收敛右侧表格范围。";
});

const sortedAllLogs = computed(() => [...allAuditLogs.value].sort((left, right) => right.time.localeCompare(left.time)));

const relatedOperatorLogs = computed(() => {
  if (!activeLog.value?.operator) return [];
  return sortedAllLogs.value.filter(log => log.operator === activeLog.value?.operator).slice(0, 12);
});

const relatedPatientLogs = computed(() => {
  if (!activeLog.value?.patient) return [];
  return sortedAllLogs.value.filter(log => log.patient === activeLog.value?.patient).slice(0, 12);
});

const dataCallback = (data: { list: AuditLogRow[]; total: number }) => {
  currentPageLogs.value = data.list;
  currentTotal.value = data.total;
  return data;
};

const loadAuditTree = async () => {
  const { data } = await getAuditLogListApi({ pageNum: 1, pageSize: 5000 });
  allAuditLogs.value = data.list;
};

const clearInitParam = () => {
  initParam.operator = undefined;
  initParam.patient = undefined;
  initParam.module = undefined;
  initParam.result = undefined;
};

const changeAuditScope = (value: string) => {
  activeTreeValue.value = value || "";
  clearInitParam();

  const [type, scopeValue] = activeTreeValue.value.split("::");
  if (!scopeValue || type === "group") return;
  if (type === "operator") initParam.operator = scopeValue;
  if (type === "patient") initParam.patient = scopeValue;
  if (type === "module") initParam.module = scopeValue;
  if (type === "result") initParam.result = scopeValue;
};

const refreshAuditLogs = async () => {
  await loadAuditTree();
  proTable.value?.getTableList();
};

const openDrawer = (row: AuditLogRow) => {
  activeLog.value = row;
  drawerVisible.value = true;
};

onMounted(loadAuditTree);
</script>

<style scoped lang="scss">
.audit-log-layout {
  align-items: stretch;
}

.audit-log-page {
  gap: 12px;
  min-width: 0;
}

.audit-scope-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  background: linear-gradient(135deg, rgb(236 253 245 / 68%), rgb(255 255 255 / 92%)), var(--el-bg-color);
  border: 1px solid rgb(20 184 166 / 18%);
  border-radius: 8px;

  h2 {
    margin: 4px 0 6px;
    color: var(--el-text-color-primary);
    font-size: 20px;
    font-weight: 700;
  }

  p {
    max-width: 760px;
    margin: 0;
    color: var(--el-text-color-secondary);
    line-height: 1.7;
  }
}

.scope-eyebrow {
  color: #008f84;
  font-size: 12px;
  font-weight: 700;
}

.scope-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  gap: 10px;
}

.audit-tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 10px;

  &.is-group {
    font-weight: 700;
  }
}

.tree-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-count {
  min-width: 28px;
  padding: 1px 7px;
  color: #008f84;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
  background: rgb(20 184 166 / 10%);
  border: 1px solid rgb(20 184 166 / 18%);
  border-radius: 999px;
}

.audit-drawer {
  display: grid;
  gap: 14px;
}

.event-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 14px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;

  strong,
  span {
    display: block;
  }

  strong {
    color: var(--el-text-color-primary);
    font-size: 18px;
  }

  span {
    margin-top: 5px;
    color: var(--el-text-color-regular);
  }
}

.change-panel,
.related-panel {
  h3 {
    margin: 0 0 10px;
    font-size: 16px;
  }
}

.change-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;

  div {
    min-height: 120px;
    padding: 12px;
    background: var(--el-fill-color-light);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
  }

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  p {
    margin: 8px 0 0;
    white-space: pre-wrap;
    word-break: break-word;
  }
}

.related-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.timeline-card {
  padding: 10px 12px;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;

  &.active {
    background: rgb(236 253 245 / 88%);
    border-color: rgb(20 184 166 / 34%);
  }

  strong,
  span {
    display: block;
  }

  span {
    margin-top: 4px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  p {
    margin: 8px 0 0;
    color: var(--el-text-color-regular);
    line-height: 1.6;
  }
}

@media (max-width: 960px) {
  .audit-log-layout {
    display: block;
  }

  .audit-scope-card,
  .scope-actions {
    align-items: flex-start;
    flex-direction: column;
  }

  .scope-actions {
    width: 100%;
  }
}
</style>
