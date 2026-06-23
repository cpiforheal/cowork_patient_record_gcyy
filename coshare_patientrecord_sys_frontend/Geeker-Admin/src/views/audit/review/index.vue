<template>
  <div class="table-box quality-page">
    <section class="card mb10 quality-head">
      <div>
        <h2>档案审核</h2>
        <p>按患者健康管理档案提交状态读取待审队列，退回会进入整改，严重问题清零后可通过归档。</p>
      </div>
      <div class="head-actions">
        <el-input v-model="keyword" clearable placeholder="搜索患者、门诊号或医生" @keyup.enter="loadReviewList" />
        <el-select v-model="statusFilter" clearable placeholder="审核状态" @change="loadReviewList">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button :icon="Refresh" :loading="listLoading" @click="loadReviewList">刷新</el-button>
      </div>
    </section>

    <section class="review-layout">
      <aside class="card review-list">
        <div class="panel-title">
          <h3>审核队列</h3>
          <span>{{ reviewRows.length }} 条</span>
        </div>
        <el-table v-loading="listLoading" :data="reviewRows" highlight-current-row class="review-table" @row-click="selectReview">
          <el-table-column prop="name" label="患者" min-width="150">
            <template #default="{ row }">
              <div class="patient-cell">
                <strong>{{ row.name }}</strong>
                <span>{{ row.visitNo }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.riskType || 'info'" effect="plain">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="score" label="分数" width="82">
            <template #default="{ row }">
              <strong :class="row.score >= 85 ? 'score-ok' : 'score-warn'">{{ row.score }}</strong>
            </template>
          </el-table-column>
          <el-table-column prop="issueCount" label="问题" width="82" />
        </el-table>
      </aside>

      <main class="card review-main" v-loading="detailLoading">
        <el-empty v-if="!activeDetail" description="暂无待审核患者" />
        <template v-else>
          <section class="patient-card">
            <div>
              <span>当前审核患者</span>
              <strong>{{ activeDetail.patient.name }}</strong>
              <small>{{ activeDetail.patient.visitNo }} · {{ activeDetail.patient.doctor }}</small>
            </div>
            <div>
              <span>审核结论</span>
              <strong :class="activeDetail.score >= 85 ? 'score-ok' : 'score-warn'">{{ activeDetail.score }} 分</strong>
              <small>{{ qualitySummary }}</small>
            </div>
            <div>
              <span>问题数量</span>
              <strong>{{ activeDetail.issues.length }} 项</strong>
              <small>严重 {{ activeDetail.criticalCount }}，提醒 {{ activeDetail.warningCount }}</small>
            </div>
            <div>
              <span>归档版本</span>
              <strong>{{ activeDetail.archiveVersion }}</strong>
              <small>{{ activeDetail.generatedAt }}</small>
            </div>
          </section>

          <section class="action-panel">
            <el-input
              v-model="reviewComment"
              type="textarea"
              :rows="3"
              maxlength="160"
              show-word-limit
              placeholder="填写档案审核意见。退回时会作为整改说明，通过时会写入归档日志。"
            />
            <div class="action-buttons">
              <el-button type="primary" plain @click="router.push(`/patients/detail/${activeDetail.patient.id}`)">
                查看档案
              </el-button>
              <el-button v-auth="'quality:reject'" type="warning" :loading="actionLoading === 'reject'" @click="rejectReview">
                退回整改
              </el-button>
              <el-button
                v-auth="'quality:approve'"
                type="primary"
                :disabled="!canApprove"
                :loading="actionLoading === 'approve'"
                @click="approveReview"
              >
                通过归档
              </el-button>
            </div>
          </section>

          <section class="review-checks">
            <div v-for="item in archiveChecks" :key="item.label">
              <span :class="item.ok ? 'pass' : 'fail'">{{ item.ok ? "通过" : "待处理" }}</span>
              <strong>{{ item.label }}</strong>
            </div>
          </section>

          <div class="filter-row">
            <el-segmented v-model="issueFilter" :options="issueFilterOptions" />
            <el-input v-model="issueKeyword" clearable placeholder="搜索字段、科室或问题" />
          </div>

          <el-table :data="filteredIssues" border class="issue-table">
            <el-table-column prop="levelLabel" label="等级" width="86">
              <template #default="{ row }">
                <el-tag :type="row.level === 'critical' ? 'danger' : 'warning'" effect="plain">{{ row.levelLabel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="section" label="档案章节" width="150" />
            <el-table-column prop="field" label="字段" width="140" />
            <el-table-column prop="owner" label="责任岗位" width="120" />
            <el-table-column prop="message" label="问题说明" min-width="240" />
            <el-table-column prop="suggestion" label="处理建议" min-width="240" />
          </el-table>
        </template>
      </main>
    </section>
  </div>
</template>

<script setup lang="ts" name="auditReview">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import {
  approveQualityReviewApi,
  getQualityReviewDetailApi,
  getQualityReviewListApi,
  rejectQualityReviewApi,
  type QualityIssue,
  type QualityReviewDetail,
  type QualityReviewRow
} from "@/api/modules/clinic";
import { roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";

const router = useRouter();
const userStore = useUserStore();

const keyword = ref("");
const issueKeyword = ref("");
const statusFilter = ref("");
const issueFilter = ref<"all" | QualityIssue["level"]>("all");
const reviewRows = ref<QualityReviewRow[]>([]);
const activeDetail = ref<QualityReviewDetail>();
const activeId = ref("");
const reviewComment = ref("");
const listLoading = ref(false);
const detailLoading = ref(false);
const actionLoading = ref<"" | "reject" | "approve">("");

const currentRole = computed(() => userStore.userInfo.role || "frontdesk");
const currentOperator = computed(() => userStore.userInfo.name || roleLabel(currentRole.value));

const statusOptions = [
  { label: "待档案审核", value: "待档案审核" },
  { label: "退回整改", value: "退回整改" },
  { label: "可提交档案审核", value: "可提交档案审核" },
  { label: "资料待核对", value: "资料待核对" }
];

const issueFilterOptions = [
  { label: "全部", value: "all" },
  { label: "严重", value: "critical" },
  { label: "提醒", value: "warning" }
];

const qualitySummary = computed(() => {
  if (!activeDetail.value) return "";
  if (!activeDetail.value.archiveSubmitted) return "尚未提交档案审核，建议先确认提交状态";
  return activeDetail.value.criticalCount ? "建议退回补正后归档" : "可通过归档";
});

const canApprove = computed(() => Boolean(activeDetail.value?.archiveSubmitted && activeDetail.value.criticalCount === 0));

const archiveChecks = computed(() => {
  if (!activeDetail.value) return [];
  return [
    { label: "已提交档案审核", ok: activeDetail.value.archiveSubmitted },
    { label: "严重问题清零", ok: activeDetail.value.criticalCount === 0 },
    { label: "附件索引可追溯", ok: activeDetail.value.attachments.length > 0 },
    { label: "可进入正式归档", ok: canApprove.value }
  ];
});

const filteredIssues = computed(() => {
  const text = issueKeyword.value.trim();
  return (activeDetail.value?.issues || []).filter(item => {
    const levelMatched = issueFilter.value === "all" || item.level === issueFilter.value;
    const textMatched = !text || `${item.section}${item.field}${item.owner}${item.message}${item.suggestion}`.includes(text);
    return levelMatched && textMatched;
  });
});

const loadReviewDetail = async (id: string) => {
  activeId.value = id;
  detailLoading.value = true;
  try {
    const { data } = await getQualityReviewDetailApi(id);
    activeDetail.value = data;
  } catch (error) {
    activeDetail.value = undefined;
    ElMessage.error((error as Error).message);
  } finally {
    detailLoading.value = false;
  }
};

const loadReviewList = async () => {
  listLoading.value = true;
  try {
    const { data } = await getQualityReviewListApi({
      pageNum: 1,
      pageSize: 50,
      keyword: keyword.value.trim(),
      status: statusFilter.value
    });
    reviewRows.value = data.list;
    const nextId = reviewRows.value.find(item => item.id === activeId.value)?.id || reviewRows.value[0]?.id || "";
    if (nextId) await loadReviewDetail(nextId);
    else {
      activeId.value = "";
      activeDetail.value = undefined;
    }
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    listLoading.value = false;
  }
};

const selectReview = (row: QualityReviewRow) => {
  if (row.id === activeId.value) return;
  reviewComment.value = "";
  loadReviewDetail(row.id);
};

const rejectReview = async () => {
  if (!activeDetail.value) return;
  actionLoading.value = "reject";
  try {
    await rejectQualityReviewApi({
      id: activeDetail.value.patient.id,
      role: currentRole.value,
      operator: currentOperator.value,
      comment: reviewComment.value
    });
    ElMessage.success("已退回整改，患者状态已更新");
    reviewComment.value = "";
    await loadReviewList();
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    actionLoading.value = "";
  }
};

const approveReview = async () => {
  if (!activeDetail.value) return;
  actionLoading.value = "approve";
  try {
    await approveQualityReviewApi({
      id: activeDetail.value.patient.id,
      role: currentRole.value,
      operator: currentOperator.value,
      comment: reviewComment.value
    });
    ElMessage.success("质控已通过，病历进入正式归档");
    reviewComment.value = "";
    await loadReviewList();
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    actionLoading.value = "";
  }
};

onMounted(loadReviewList);
</script>

<style scoped lang="scss">
.quality-page {
  display: grid;
  gap: 14px;
  color: #1f2937;
}

.quality-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;

  h2,
  p {
    margin: 0;
  }

  p {
    margin-top: 6px;
    color: #6b7280;
  }
}

.head-actions {
  display: grid;
  grid-template-columns: minmax(220px, 300px) 150px auto;
  gap: 8px;
  align-items: center;
}

.review-layout {
  display: grid;
  grid-template-columns: minmax(360px, 0.42fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.review-list,
.review-main {
  padding: 14px;
}

.panel-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;

  h3 {
    margin: 0;
    color: #0f172a;
    font-size: 17px;
  }

  span {
    color: #64748b;
  }
}

.review-table {
  :deep(.el-table__row) {
    cursor: pointer;
  }
}

.patient-cell {
  display: grid;
  gap: 2px;
  text-align: left;

  strong {
    color: #0f172a;
  }

  span {
    color: #64748b;
    font-size: 12px;
  }
}

.patient-card {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid #eef2f7;
  border-radius: 8px;

  div {
    min-width: 0;
    padding: 14px 16px;
    border-right: 1px solid #eef2f7;

    &:last-child {
      border-right: 0;
    }
  }

  span,
  strong,
  small {
    display: block;
  }

  span {
    color: #64748b;
    font-size: 12px;
  }

  strong {
    margin-top: 5px;
    overflow: hidden;
    color: #0f172a;
    font-size: 20px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    margin-top: 4px;
    overflow: hidden;
    color: #6b7280;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.score-ok {
  color: #166534;
}

.score-warn {
  color: #b45309;
}

.action-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: start;
  padding: 14px 0;
}

.action-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

.review-checks {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;

  div {
    display: flex;
    gap: 8px;
    align-items: center;
    min-width: 0;
    padding: 9px 10px;
    background: #f8fafc;
    border: 1px solid #e2e8f0;
    border-radius: 8px;
  }

  span {
    flex-shrink: 0;
    padding: 2px 6px;
    border-radius: 999px;
    font-size: 12px;

    &.pass {
      color: #166534;
      background: #dcfce7;
    }

    &.fail {
      color: #b45309;
      background: #fef3c7;
    }
  }

  strong {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.filter-row {
  display: grid;
  grid-template-columns: auto minmax(220px, 320px);
  gap: 10px;
  justify-content: space-between;
  margin-bottom: 12px;
}

.issue-table {
  :deep(.el-table__cell) {
    vertical-align: top;
  }
}

@media (max-width: 1180px) {
  .review-layout,
  .patient-card,
  .review-checks,
  .action-panel {
    grid-template-columns: 1fr;
  }

  .patient-card div {
    border-right: 0;
    border-bottom: 1px solid #eef2f7;
  }
}

@media (max-width: 760px) {
  .quality-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .head-actions,
  .filter-row {
    grid-template-columns: 1fr;
    width: 100%;
  }
}
</style>
