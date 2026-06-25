<template>
  <div class="main-box ai-analysis-page">
    <section class="analysis-hero">
      <div>
        <span class="eyebrow">AI 使用分析</span>
        <h2>院内豆包助手审计与模板沉淀</h2>
        <p>追踪同事问了什么、系统带入了哪些上下文和知识来源，用于发现高频需求并沉淀标准提问模板。</p>
      </div>
      <el-button type="primary" :icon="Refresh" :loading="loading" @click="loadAll">刷新数据</el-button>
    </section>

    <section class="metric-grid">
      <article v-for="item in metrics" :key="item.label" class="metric-card">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <small>{{ item.hint }}</small>
      </article>
    </section>

    <section class="filter-panel">
      <el-form :model="filters" inline>
        <el-form-item label="时间">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            unlink-panels
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
          />
        </el-form-item>
        <el-form-item label="助手">
          <el-select v-model="filters.assistantType" clearable placeholder="全部" style="width: 150px">
            <el-option v-for="item in assistantOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部" style="width: 120px">
            <el-option label="成功" value="success" />
            <el-option label="失败" value="failed" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="filters.role" clearable filterable placeholder="全部角色" style="width: 150px">
            <el-option v-for="item in roleOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="科室">
          <el-input v-model="filters.department" clearable placeholder="输入科室" />
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="filters.keyword" clearable placeholder="问题/回答/错误" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="applyFilters">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="insight-grid">
      <article class="insight-card">
        <header>
          <strong>高频问题</strong>
          <span>可转为快捷模板</span>
        </header>
        <button
          v-for="item in analytics.frequentPrompts || []"
          :key="`${item.assistantType}-${item.prompt}`"
          type="button"
          class="prompt-row"
          @click="prefillFromPrompt(item)"
        >
          <span>{{ item.prompt }}</span>
          <small>{{ assistantLabel(item.assistantType) }} · {{ item.count }} 次</small>
        </button>
        <el-empty v-if="!(analytics.frequentPrompts || []).length" description="暂无高频问题" />
      </article>

      <article class="insight-card">
        <header>
          <strong>知识库未覆盖</strong>
          <span>用于补充系统说明</span>
        </header>
        <div v-for="item in analytics.knowledgeMisses || []" :key="item.id" class="miss-row">
          <span>{{ item.prompt }}</span>
          <small>{{ item.createdAt }} · {{ assistantLabel(item.assistantType) }}</small>
        </div>
        <el-empty v-if="!(analytics.knowledgeMisses || []).length" description="暂无未覆盖记录" />
      </article>

      <article class="insight-card">
        <header>
          <strong>页面来源</strong>
          <span>识别入口使用习惯</span>
        </header>
        <div v-for="item in analytics.pageBuckets || []" :key="item.label" class="bucket-row">
          <span>{{ item.label || "未知页面" }}</span>
          <el-progress :percentage="bucketPercent(item.count, analytics.totalCalls)" :show-text="false" />
          <small>{{ item.count }}</small>
        </div>
        <el-empty v-if="!(analytics.pageBuckets || []).length" description="暂无页面来源" />
      </article>
    </section>

    <section class="log-panel">
      <header class="section-title">
        <div>
          <strong>AI 调用日志</strong>
          <span>记录 prompt、上下文摘要、知识来源、模型耗时和失败原因</span>
        </div>
        <el-tag effect="plain">共 {{ total }} 条</el-tag>
      </header>
      <el-table :data="logs" v-loading="loading" border stripe>
        <el-table-column prop="createdAt" label="时间" width="168" />
        <el-table-column label="助手" width="108">
          <template #default="{ row }">{{ assistantLabel(row.assistantType) }}</template>
        </el-table-column>
        <el-table-column label="提问人" width="150">
          <template #default="{ row }">
            <div class="operator-cell">
              <strong>{{ row.operatorName || "未记录" }}</strong>
              <span>{{ roleLabel(row.operatorRole) }} · {{ row.operatorDepartment || "未填科室" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="问题摘要" min-width="260">
          <template #default="{ row }">
            <span class="line-clamp">{{ row.promptPreview || row.prompt || "无问题文本" }}</span>
          </template>
        </el-table-column>
        <el-table-column label="知识来源" min-width="180">
          <template #default="{ row }">
            <div class="source-tags">
              <el-tag v-for="item in (row.knowledgeSources || []).slice(0, 2)" :key="item" effect="plain" size="small">
                {{ item }}
              </el-tag>
              <span v-if="!(row.knowledgeSources || []).length">未命中</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <el-tag :type="row.status === 'success' ? 'success' : 'danger'" effect="light">
              {{ row.status === "success" ? "成功" : "失败" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时" width="96">
          <template #default="{ row }">{{ row.latencyMs || 0 }}ms</template>
        </el-table-column>
        <el-table-column label="操作" width="168" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row as AiAssistantLog)">详情</el-button>
            <el-button link type="primary" :disabled="row.templateCandidate" @click="openTemplate(row as AiAssistantLog)">
              {{ row.templateCandidate ? "已候选" : "转模板" }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="filters.pageNum"
          v-model:page-size="filters.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @change="loadLogs"
        />
      </div>
    </section>

    <el-drawer v-model="detailVisible" title="AI 调用详情" size="560px">
      <div v-if="activeLog" class="detail-stack">
        <el-alert
          v-if="activeLog.sensitive"
          title="该记录来自患者/质控场景，系统已默认脱敏保存。"
          type="warning"
          show-icon
          :closable="false"
        />
        <el-descriptions :column="1" border>
          <el-descriptions-item label="助手">{{ assistantLabel(activeLog.assistantType) }}</el-descriptions-item>
          <el-descriptions-item label="页面">{{ activeLog.pageTitle || activeLog.pagePath || "未记录" }}</el-descriptions-item>
          <el-descriptions-item label="模型">{{ activeLog.model || "未记录" }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ activeLog.latencyMs || 0 }}ms</el-descriptions-item>
          <el-descriptions-item label="上下文">{{ activeLog.contextSummary || "未带入页面上下文" }}</el-descriptions-item>
        </el-descriptions>
        <section>
          <strong>用户问题</strong>
          <p>{{ activeLog.prompt || activeLog.promptPreview || "无" }}</p>
        </section>
        <section>
          <strong>系统 Prompt 摘要</strong>
          <p>{{ activeLog.systemPromptSummary || "未记录" }}</p>
        </section>
        <section>
          <strong>知识来源</strong>
          <div class="source-tags">
            <el-tag v-for="item in activeLog.knowledgeSources || []" :key="item" effect="plain">{{ item }}</el-tag>
            <span v-if="!(activeLog.knowledgeSources || []).length">未命中知识来源</span>
          </div>
        </section>
        <section>
          <strong>AI 回答摘要</strong>
          <p>{{ activeLog.answer || activeLog.answerPreview || activeLog.errorMessage || "无" }}</p>
        </section>
      </div>
    </el-drawer>

    <el-dialog v-model="templateVisible" title="标记为模板候选" width="560px" destroy-on-close>
      <el-form :model="templateForm" label-width="104px">
        <el-form-item label="模板标题" required>
          <el-input v-model="templateForm.title" placeholder="例如：检查室岗位填写边界" />
        </el-form-item>
        <el-form-item label="助手类型">
          <el-select v-model="templateForm.assistantType">
            <el-option v-for="item in assistantOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="适用角色">
          <el-select v-model="templateForm.roleScope" clearable placeholder="全部角色">
            <el-option label="全部角色" value="all" />
            <el-option v-for="item in roleOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="推荐问法" required>
          <el-input v-model="templateForm.recommendedPrompt" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="上下文说明">
          <el-input v-model="templateForm.contextNote" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="templateVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="savingTemplate"
          :disabled="!templateForm.title || !templateForm.recommendedPrompt"
          @click="saveTemplate"
        >
          保存候选
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="aiAssistantAnalysis">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import {
  getAiAssistantAnalyticsApi,
  getAiAssistantLogsApi,
  markAiAssistantTemplateCandidateApi,
  type AiAssistantAnalytics,
  type AiAssistantFrequentPrompt,
  type AiAssistantLog,
  type AiAssistantLogListParams,
  type AiAssistantType,
  type AiPromptTemplatePayload
} from "@/api/modules/clinic";
import { USER_ROLE_OPTIONS, roleLabel } from "@/config/fieldPermissions";

const assistantOptions: Array<{ label: string; value: AiAssistantType }> = [
  { label: "公共助手", value: "public" },
  { label: "患者助手", value: "patient" },
  { label: "质控助手", value: "quality" },
  { label: "管理助手", value: "leader" }
];

const roleOptions = USER_ROLE_OPTIONS;
const loading = ref(false);
const savingTemplate = ref(false);
const logs = ref<AiAssistantLog[]>([]);
const total = ref(0);
const dateRange = ref<[string, string] | "">("");
const detailVisible = ref(false);
const templateVisible = ref(false);
const activeLog = ref<AiAssistantLog>();

const analytics = ref<AiAssistantAnalytics>({
  totalCalls: 0,
  todayCalls: 0,
  failedCalls: 0,
  failureRate: 0,
  averageLatencyMs: 0
});

const filters = reactive<AiAssistantLogListParams>({
  pageNum: 1,
  pageSize: 20,
  assistantType: "",
  status: "",
  role: "",
  department: "",
  keyword: ""
});

const templateForm = reactive<AiPromptTemplatePayload>({
  assistantType: "public",
  title: "",
  roleScope: "all",
  recommendedPrompt: "",
  contextNote: ""
});

const queryParams = computed(() => {
  const [dateFrom, dateTo] = Array.isArray(dateRange.value) ? dateRange.value : ["", ""];
  return {
    ...filters,
    dateFrom,
    dateTo
  };
});

const metrics = computed(() => [
  { label: "今日调用", value: analytics.value.todayCalls || 0, hint: "院内同事今天使用次数" },
  { label: "总调用", value: analytics.value.totalCalls || 0, hint: "当前筛选范围内" },
  { label: "失败率", value: `${analytics.value.failureRate || 0}%`, hint: `${analytics.value.failedCalls || 0} 次失败` },
  { label: "平均耗时", value: `${Math.round(analytics.value.averageLatencyMs || 0)}ms`, hint: "模型响应与后端处理耗时" }
]);

watch(dateRange, () => {
  filters.pageNum = 1;
});

const assistantLabel = (type?: AiAssistantType | string) =>
  assistantOptions.find(item => item.value === type)?.label || "公共助手";
const bucketPercent = (count: number, totalCount: number) =>
  totalCount ? Math.min(100, Math.round((count / totalCount) * 100)) : 0;

const readWarning = (data: unknown) => {
  const warning = (data as { warning?: unknown })?.warning;
  return typeof warning === "string" ? warning : "";
};

const loadLogs = async () => {
  loading.value = true;
  try {
    const { data } = await getAiAssistantLogsApi(queryParams.value);
    logs.value = data.list || [];
    total.value = data.total || 0;
    const warning = readWarning(data);
    if (warning) ElMessage.warning(warning);
  } catch (error) {
    logs.value = [];
    total.value = 0;
    ElMessage.warning("AI 使用日志暂不可用，已显示空列表");
  } finally {
    loading.value = false;
  }
};

const loadAnalytics = async () => {
  try {
    const { data } = await getAiAssistantAnalyticsApi(queryParams.value);
    analytics.value = {
      ...data,
      totalCalls: data.totalCalls || 0,
      todayCalls: data.todayCalls || 0,
      failedCalls: data.failedCalls || 0,
      failureRate: data.failureRate || 0,
      averageLatencyMs: data.averageLatencyMs || 0
    };
    const warning = readWarning(data);
    if (warning) ElMessage.warning(warning);
  } catch (error) {
    analytics.value = {
      totalCalls: 0,
      todayCalls: 0,
      failedCalls: 0,
      failureRate: 0,
      averageLatencyMs: 0,
      assistantTypeBuckets: [],
      roleBuckets: [],
      departmentBuckets: [],
      intentBuckets: [],
      pageBuckets: [],
      modelErrorBuckets: [],
      frequentPrompts: [],
      knowledgeMisses: []
    };
    ElMessage.warning("AI 使用统计暂不可用，已显示空面板");
  }
};

const loadAll = async () => {
  await Promise.all([loadLogs(), loadAnalytics()]);
};

const applyFilters = () => {
  filters.pageNum = 1;
  loadAll();
};

const resetFilters = () => {
  dateRange.value = "";
  Object.assign(filters, {
    pageNum: 1,
    pageSize: 20,
    assistantType: "",
    status: "",
    role: "",
    department: "",
    keyword: ""
  });
  loadAll();
};

const openDetail = (row: AiAssistantLog) => {
  activeLog.value = row;
  detailVisible.value = true;
};

const openTemplate = (row: AiAssistantLog) => {
  activeLog.value = row;
  Object.assign(templateForm, {
    assistantType: row.assistantType || "public",
    title: row.promptPreview ? `${assistantLabel(row.assistantType)}：${row.promptPreview.slice(0, 28)}` : "AI 问题模板",
    roleScope: row.operatorRole || "all",
    recommendedPrompt: row.prompt || row.promptPreview || "",
    contextNote: row.contextSummary || ""
  });
  templateVisible.value = true;
};

const prefillFromPrompt = (item: AiAssistantFrequentPrompt) => {
  activeLog.value = item.sourceLogId
    ? ({
        id: item.sourceLogId,
        assistantType: item.assistantType,
        prompt: item.prompt,
        promptPreview: item.prompt,
        operatorRole: "all",
        contextSummary: "来自 AI 使用分析高频问题统计"
      } as AiAssistantLog)
    : undefined;
  Object.assign(templateForm, {
    assistantType: item.assistantType || "public",
    title: `${assistantLabel(item.assistantType)}高频问题`,
    roleScope: "all",
    recommendedPrompt: item.prompt,
    contextNote: "来自 AI 使用分析高频问题统计"
  });
  templateVisible.value = true;
};

const saveTemplate = async () => {
  if (!activeLog.value?.id) {
    ElMessage.warning("请从日志明细中选择一条记录转为模板候选");
    return;
  }
  savingTemplate.value = true;
  try {
    await markAiAssistantTemplateCandidateApi(activeLog.value.id, { ...templateForm });
    ElMessage.success("模板候选已保存");
    templateVisible.value = false;
    loadAll();
  } finally {
    savingTemplate.value = false;
  }
};

onMounted(loadAll);
</script>

<style scoped lang="scss">
.ai-analysis-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.analysis-hero,
.filter-panel,
.log-panel,
.insight-card,
.metric-card {
  border: 1px solid var(--hos-line, #dfe9e2);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.86);
  box-shadow: 0 12px 34px rgb(45 76 59 / 0.06);
}

.analysis-hero {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  align-items: center;
  padding: 20px 22px;

  h2 {
    margin: 4px 0 8px;
    color: var(--hos-text-main, #203529);
    font-size: 22px;
  }

  p {
    margin: 0;
    color: var(--el-text-color-secondary);
  }
}

.eyebrow {
  color: var(--hos-primary-deep, #4d7b63);
  font-size: 12px;
  font-weight: 700;
}

.metric-grid,
.insight-grid {
  display: grid;
  gap: 14px;
}

.metric-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.insight-grid {
  grid-template-columns: 1.2fr 1fr 1fr;
}

.metric-card {
  padding: 16px;

  span,
  small {
    color: var(--el-text-color-secondary);
  }

  strong {
    display: block;
    margin: 8px 0 4px;
    color: var(--hos-primary-deep, #4d7b63);
    font-size: 30px;
    font-variant-numeric: tabular-nums;
  }
}

.filter-panel {
  padding: 16px 16px 0;
}

.insight-card {
  min-height: 220px;
  padding: 16px;

  header,
  .bucket-row,
  .prompt-row,
  .miss-row {
    display: flex;
    gap: 10px;
  }

  header {
    justify-content: space-between;
    margin-bottom: 12px;

    span {
      color: var(--el-text-color-secondary);
      font-size: 12px;
    }
  }
}

.prompt-row,
.miss-row,
.bucket-row {
  width: 100%;
  padding: 10px 0;
  border: 0;
  border-top: 1px solid var(--hos-line, #dfe9e2);
  color: var(--hos-text-main, #203529);
  background: transparent;
  text-align: left;

  span {
    flex: 1;
    min-width: 0;
  }

  small {
    color: var(--el-text-color-secondary);
    white-space: nowrap;
  }
}

.prompt-row {
  cursor: pointer;
}

.bucket-row {
  align-items: center;

  .el-progress {
    flex: 1;
  }
}

.log-panel {
  padding: 16px;
}

.section-title {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: center;
  margin-bottom: 14px;

  strong {
    display: block;
    color: var(--hos-text-main, #203529);
  }

  span {
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }
}

.operator-cell {
  display: grid;
  gap: 2px;

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.line-clamp {
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.source-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.detail-stack {
  display: grid;
  gap: 16px;

  section {
    display: grid;
    gap: 8px;
    padding-bottom: 14px;
    border-bottom: 1px solid var(--hos-line, #dfe9e2);
  }

  p {
    margin: 0;
    color: var(--el-text-color-regular);
    line-height: 1.7;
    white-space: pre-wrap;
  }
}

@media (max-width: 1180px) {
  .metric-grid,
  .insight-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .analysis-hero,
  .metric-grid,
  .insight-grid {
    grid-template-columns: 1fr;
  }

  .analysis-hero {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
