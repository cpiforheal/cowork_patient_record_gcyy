<template>
  <section class="answer-admin" v-loading="loading">
    <header class="answer-head">
      <div>
        <span class="answer-eyebrow">培训考核 · 管理端</span>
        <h1>答卷管理</h1>
        <p>{{ paper?.title || "医保合规与DIP管理考核" }}</p>
      </div>
      <el-button :icon="Download" @click="handleExport">导出 xlsx</el-button>
    </header>

    <div class="answer-overview">
      <section class="answer-status-card">
        <div class="answer-status-main">
          <span class="answer-status-label">考试状态</span>
          <strong :class="{ 'is-open': paper?.status === 'OPEN' }">
            {{ paper?.status === "OPEN" ? "开放答题" : "已关闭" }}
          </strong>
          <small>{{ paper?.questionCount || 40 }} 题 · {{ paper?.totalScore || 100 }} 分 · 一人一次</small>
        </div>
        <el-switch
          :model-value="paper?.status === 'OPEN'"
          active-text="开放"
          inactive-text="关闭"
          :loading="statusSaving"
          @change="toggleStatus"
        />
        <div class="answer-stats">
          <span>答卷 <b>{{ stats.total }}</b></span>
          <span>已交 <b>{{ stats.submitted }}</b></span>
          <span>答题中 <b>{{ stats.inProgress }}</b></span>
          <span>已作废 <b>{{ stats.void }}</b></span>
          <span>平均分 <b>{{ Number(stats.averageScore || 0).toFixed(1) }}</b></span>
        </div>
      </section>

      <section class="answer-entry-card">
        <div class="answer-entry-copy">
          <span class="answer-status-label">科室答题入口</span>
          <strong>扫码或打开链接答题</strong>
          <small>手机需与 8848 在同一内网；链接不要用 localhost。</small>
          <el-input v-model="linkBase" class="answer-link-input">
            <template #prepend>地址</template>
          </el-input>
          <div class="answer-entry-actions">
            <el-button @click="useCurrentOrigin">使用当前地址</el-button>
            <el-button @click="copyLink">复制链接</el-button>
          </div>
          <el-alert
            v-if="isLocalhost"
            type="warning"
            :closable="false"
            show-icon
            title="当前是 localhost，手机无法访问"
            description="请把地址改成服务器的局域网 IP，例如 http://192.168.1.10:8848。"
          />
          <p class="answer-link-preview">{{ examLink }}</p>
        </div>
        <div class="answer-qr">
          <img v-if="qrDataUrl" :src="qrDataUrl" alt="答题入口二维码" />
          <span v-else class="answer-qr-empty">二维码生成中</span>
        </div>
      </section>
    </div>

    <el-tabs v-model="activeTab" class="answer-tabs">
      <el-tab-pane label="答卷记录" name="attempts">
        <div class="answer-toolbar">
          <el-select v-model="attemptFilter.department" placeholder="全部科室" clearable filterable>
            <el-option v-for="item in departments" :key="item" :label="item" :value="item" />
          </el-select>
          <el-select v-model="attemptFilter.status" placeholder="全部状态" clearable>
            <el-option label="答题中" value="IN_PROGRESS" />
            <el-option label="已交卷" value="SUBMITTED" />
            <el-option label="已作废" value="VOID" />
          </el-select>
          <el-button type="primary" @click="loadAttempts">查询</el-button>
          <span class="answer-toolbar-note">共 {{ attempts?.total || 0 }} 条</span>
        </div>

        <el-table :data="attempts?.list || []" height="calc(100vh - 520px)" class="answer-table">
          <el-table-column prop="name" label="姓名" width="120" />
          <el-table-column prop="department" label="科室" width="130" />
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTone(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="score" label="得分" width="80" align="right" />
          <el-table-column prop="correctCount" label="答对" width="76" align="right" />
          <el-table-column label="用时" width="100">
            <template #default="{ row }">{{ formatDuration(row.durationSeconds) }}</template>
          </el-table-column>
          <el-table-column prop="submittedAt" label="交卷时间" min-width="150" />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
              <el-button
                link
                type="danger"
                size="small"
                :disabled="row.status === 'VOID'"
                @click="voidAttempt(row)"
              >
                作废
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="花名册" name="roster">
        <div class="answer-toolbar">
          <el-input v-model="newRoster.name" placeholder="姓名" class="answer-roster-name" />
          <el-select
            v-model="newRoster.department"
            placeholder="科室"
            filterable
            allow-create
            default-first-option
            class="answer-roster-dept"
          >
            <el-option v-for="item in departments" :key="item" :label="item" :value="item" />
          </el-select>
          <el-button type="primary" @click="addRoster">添加</el-button>
          <el-button @click="pasteVisible = true">粘贴导入</el-button>
          <el-button @click="fileInput?.click()">Excel/CSV 导入</el-button>
          <el-button @click="importAccounts">从现有账号导入</el-button>
          <input ref="fileInput" type="file" accept=".xlsx,.xls,.csv" hidden @change="onFileChange" />
          <span class="answer-toolbar-note">共 {{ roster?.list.length || 0 }} 人</span>
        </div>

        <el-table :data="roster?.list || []" height="calc(100vh - 520px)" class="answer-table">
          <el-table-column prop="name" label="姓名" width="140" />
          <el-table-column prop="department" label="科室" min-width="160" />
          <el-table-column prop="source" label="来源" width="110" />
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="deleteRoster(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="题库（只读）" name="questions">
        <el-table :data="questions?.questions || []" height="calc(100vh - 520px)" class="answer-table">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="answer-question-expand">
                <p v-for="(option, index) in row.options" :key="index">
                  <b>{{ String.fromCharCode(65 + index) }}.</b> {{ option }}
                </p>
                <p class="answer-question-explain">正确答案：{{ row.answer.join("") }}</p>
                <p class="answer-question-explain">{{ row.explanation }}</p>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="seq" label="题号" width="80" />
          <el-table-column prop="stem" label="题干" min-width="320" show-overflow-tooltip />
          <el-table-column label="答案" width="90">
            <template #default="{ row }">{{ row.answer.join("") }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="pasteVisible" title="粘贴导入花名册" width="560px" append-to-body>
      <el-input
        v-model="pasteText"
        type="textarea"
        :rows="10"
        placeholder="每行一位：张三,内科&#10;李四,护理部"
      />
      <p class="answer-dialog-note">支持逗号、制表符或空格分隔；姓名+科室重复会自动跳过。</p>
      <template #footer>
        <el-button @click="pasteVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="importPaste">导入</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" title="答卷详情" size="640px" append-to-body>
      <div v-if="detail" class="answer-detail">
        <header class="answer-detail-head">
          <div>
            <strong>{{ detail.attempt.name }}</strong>
            <span>{{ detail.attempt.department }}</span>
          </div>
          <div class="answer-detail-score">
            <strong>{{ detail.attempt.score }}</strong>
            <span>/ {{ paper?.totalScore || 100 }}</span>
          </div>
        </header>
        <p class="answer-detail-meta">
          答对 {{ detail.attempt.correctCount }} / {{ detail.questions.length }} 题 ·
          用时 {{ formatDuration(detail.attempt.durationSeconds) }} ·
          {{ detail.attempt.submittedAt || "未交卷" }}
        </p>
        <article v-for="item in detail.questions" :key="item.id" class="answer-detail-item">
          <p class="answer-detail-stem">{{ item.seq }}. {{ item.stem }}</p>
          <p class="answer-detail-answer">
            选择 <b :class="{ 'is-wrong': !item.correct }">{{ item.chosen || "未作答" }}</b>
            · 正确 <b class="is-right">{{ item.answer.join("") }}</b>
          </p>
          <p v-if="item.explanation" class="answer-detail-explain">{{ item.explanation }}</p>
        </article>
      </div>
      <div v-else v-loading="detailLoading" class="answer-detail-loading" />
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Download } from "@element-plus/icons-vue";
import QRCode from "qrcode";
import {
  addExamRosterApi,
  deleteExamRosterApi,
  downloadExamAttemptsApi,
  getExamAdminPaperApi,
  getExamAdminQuestionsApi,
  getExamAttemptDetailApi,
  getExamAttemptsApi,
  getExamRosterApi,
  importExamAccountsApi,
  importExamRosterApi,
  updateExamPaperStatusApi,
  voidExamAttemptApi,
  type ExamAdminPaperView,
  type ExamAdminQuestionView,
  type ExamAttemptDetailView,
  type ExamAttemptListView,
  type ExamRosterView
} from "@/api/modules/clinic/examAdmin";

defineOptions({ name: "examAdminAnswers" });

const loading = ref(false);
const statusSaving = ref(false);
const importing = ref(false);
const activeTab = ref("attempts");
const paperData = ref<ExamAdminPaperView | null>(null);
const roster = ref<ExamRosterView | null>(null);
const attempts = ref<ExamAttemptListView | null>(null);
const questions = ref<ExamAdminQuestionView | null>(null);
const attemptFilter = reactive({ department: "", status: "" });
const newRoster = reactive({ name: "", department: "" });
const pasteVisible = ref(false);
const pasteText = ref("");
const fileInput = ref<HTMLInputElement>();
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<ExamAttemptDetailView | null>(null);
const linkBase = ref(window.location.origin);
const qrDataUrl = ref("");
let qrTimer: ReturnType<typeof setTimeout> | undefined;

const paper = computed(() => paperData.value?.paper);
const stats = computed(
  () => paperData.value?.stats || { total: 0, inProgress: 0, submitted: 0, void: 0, averageScore: 0 }
);
const departments = computed(() => roster.value?.departments || []);
const examLink = computed(() => `${linkBase.value.replace(/\/+$/, "")}/#/exam`);
const isLocalhost = computed(() => /localhost|127\.0\.0\.1/i.test(linkBase.value));

const statusLabel = (status: string) => {
  if (status === "IN_PROGRESS") return "答题中";
  if (status === "SUBMITTED") return "已交卷";
  if (status === "VOID") return "已作废";
  return status;
};

const statusTone = (status: string) => {
  if (status === "SUBMITTED") return "success";
  if (status === "VOID") return "info";
  return "warning";
};

const formatDuration = (seconds: number) => {
  const value = Math.max(0, Number(seconds || 0));
  const minutes = Math.floor(value / 60);
  const rest = value % 60;
  return minutes ? `${minutes}分${rest}秒` : `${rest}秒`;
};

const generateQr = async () => {
  try {
    qrDataUrl.value = await QRCode.toDataURL(examLink.value, {
      width: 220,
      margin: 1,
      color: { dark: "#0f766e", light: "#ffffff" }
    });
  } catch {
    qrDataUrl.value = "";
  }
};

const loadPaper = async () => {
  paperData.value = await getExamAdminPaperApi();
};

const loadRoster = async () => {
  roster.value = await getExamRosterApi();
};

const loadAttempts = async () => {
  attempts.value = await getExamAttemptsApi({
    department: attemptFilter.department || undefined,
    status: attemptFilter.status || undefined
  });
};

const loadQuestions = async () => {
  questions.value = await getExamAdminQuestionsApi();
};

const loadAll = async () => {
  loading.value = true;
  try {
    await Promise.all([loadPaper(), loadRoster(), loadAttempts(), loadQuestions()]);
    await generateQr();
  } catch (error: any) {
    ElMessage.error(error?.message || "培训考核数据加载失败");
  } finally {
    loading.value = false;
  }
};

const toggleStatus = async (value: string | number | boolean) => {
  const open = Boolean(value);
  const next = open ? "OPEN" : "CLOSED";
  if (next === "CLOSED") {
    try {
      await ElMessageBox.confirm("关闭后不能新开考，已开始的同事仍可交卷。", "关闭考试？", {
        type: "warning",
        confirmButtonText: "关闭",
        cancelButtonText: "取消"
      });
    } catch {
      return;
    }
  }
  statusSaving.value = true;
  try {
    paperData.value = await updateExamPaperStatusApi(next);
    ElMessage.success(next === "OPEN" ? "考试已开放" : "考试已关闭");
  } catch (error: any) {
    ElMessage.error(error?.message || "状态更新失败");
  } finally {
    statusSaving.value = false;
  }
};

const useCurrentOrigin = () => {
  linkBase.value = window.location.origin;
};

const copyLink = async () => {
  const value = examLink.value;
  try {
    await navigator.clipboard.writeText(value);
    ElMessage.success("答题链接已复制");
  } catch {
    const textarea = document.createElement("textarea");
    textarea.value = value;
    document.body.appendChild(textarea);
    textarea.select();
    document.execCommand("copy");
    textarea.remove();
    ElMessage.success("答题链接已复制");
  }
};

const addRoster = async () => {
  if (!newRoster.name.trim() || !newRoster.department.trim()) {
    ElMessage.warning("请填写姓名和科室");
    return;
  }
  try {
    roster.value = await addExamRosterApi({
      name: newRoster.name.trim(),
      department: newRoster.department.trim()
    });
    newRoster.name = "";
    newRoster.department = "";
    ElMessage.success("已添加");
    await loadPaper();
  } catch (error: any) {
    ElMessage.error(error?.message || "添加失败");
  }
};

const deleteRoster = async (row: any) => {
  try {
    await ElMessageBox.confirm(`删除 ${row.name}（${row.department}）？`, "删除花名册人员", {
      type: "warning",
      confirmButtonText: "删除",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  try {
    roster.value = await deleteExamRosterApi(row.id);
    ElMessage.success("已删除");
  } catch (error: any) {
    ElMessage.error(error?.message || "删除失败");
  }
};

const normalizePaste = (text: string) =>
  text
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line && !/姓名|科室|name|department/i.test(line))
    .map(line => {
      const parts = line.split(/[,，、\t]+|\s{2,}/).map(part => part.trim()).filter(Boolean);
      const [name, department] = parts.length >= 2 ? parts : line.split(/\s+/);
      return name && department ? `${name},${department}` : "";
    })
    .filter(Boolean)
    .join("\n");

const importPaste = async () => {
  const csv = normalizePaste(pasteText.value);
  if (!csv.trim()) {
    ElMessage.warning("没有可导入的姓名和科室");
    return;
  }
  importing.value = true;
  try {
    const result = await importExamRosterApi(new File([csv], "roster.csv", { type: "text/csv" }));
    ElMessage.success(`导入 ${result.inserted} 条，跳过 ${result.skipped} 条`);
    if (result.errors?.length) ElMessage.warning(result.errors.slice(0, 3).join("；"));
    pasteVisible.value = false;
    pasteText.value = "";
    await Promise.all([loadRoster(), loadPaper()]);
  } catch (error: any) {
    ElMessage.error(error?.message || "导入失败");
  } finally {
    importing.value = false;
  }
};

const onFileChange = async (event: Event) => {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  importing.value = true;
  try {
    const result = await importExamRosterApi(file);
    ElMessage.success(`导入 ${result.inserted} 条，跳过 ${result.skipped} 条`);
    if (result.errors?.length) ElMessage.warning(result.errors.slice(0, 3).join("；"));
    await Promise.all([loadRoster(), loadPaper()]);
  } catch (error: any) {
    ElMessage.error(error?.message || "导入失败");
  } finally {
    importing.value = false;
    input.value = "";
  }
};

const importAccounts = async () => {
  try {
    const result = await importExamAccountsApi();
    ElMessage.success(`从账号导入 ${result.inserted} 条，跳过 ${result.skipped} 条`);
    await Promise.all([loadRoster(), loadPaper()]);
  } catch (error: any) {
    ElMessage.error(error?.message || "导入失败");
  }
};

const openDetail = async (row: any) => {
  detailVisible.value = true;
  detail.value = null;
  detailLoading.value = true;
  try {
    detail.value = await getExamAttemptDetailApi(row.id);
  } catch (error: any) {
    ElMessage.error(error?.message || "答卷详情加载失败");
    detailVisible.value = false;
  } finally {
    detailLoading.value = false;
  }
};

const voidAttempt = async (row: any) => {
  let reason = "";
  try {
    const result = await ElMessageBox.prompt(
      `作废 ${row.name}（${row.department}）的答卷？作废后该姓名+科室可以重新答题。`,
      "作废答卷",
      {
        confirmButtonText: "作废并允许重考",
        cancelButtonText: "取消",
        inputType: "textarea",
        inputPlaceholder: "请填写作废原因，例如：姓名填错、误交卷",
        inputValidator: value => (value && value.trim().length >= 2) || "请填写作废原因"
      }
    );
    reason = result.value.trim();
  } catch {
    return;
  }
  try {
    await voidExamAttemptApi(row.id, reason);
    ElMessage.success("已作废，可重新答题");
    await Promise.all([loadPaper(), loadAttempts()]);
  } catch (error: any) {
    ElMessage.error(error?.message || "作废失败");
  }
};

const handleExport = async () => {
  try {
    await downloadExamAttemptsApi();
    ElMessage.success("已开始下载");
  } catch (error: any) {
    ElMessage.error(error?.message || "导出失败");
  }
};

watch(
  examLink,
  () => {
    clearTimeout(qrTimer);
    qrTimer = setTimeout(() => void generateQr(), 200);
  },
  { immediate: false }
);

onMounted(loadAll);
</script>

<style scoped lang="scss">
.answer-admin {
  --ans-ink: #17332b;
  --ans-muted: #6e8179;
  --ans-line: #e0eae6;
  --ans-accent: #0f766e;
  --ans-soft: #e6f3f0;
  --ans-warm: #b7793f;

  box-sizing: border-box;
  max-width: 1440px;
  min-height: 100%;
  padding: 18px 24px 28px;
  margin: 0 auto;
  color: var(--ans-ink);
  background: #f6faf8;
}

.answer-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  padding: 0 2px 16px;

  h1 {
    margin: 2px 0 4px;
    font-size: 28px;
  }

  p {
    margin: 0;
    color: var(--ans-muted);
    font-size: 12px;
  }
}

.answer-eyebrow {
  color: var(--ans-accent);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.answer-overview {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 14px;
}

.answer-status-card,
.answer-entry-card {
  padding: 16px;
  background: #fff;
  border: 1px solid var(--ans-line);
  border-radius: 16px;
}

.answer-status-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.answer-status-main {
  display: flex;
  flex-direction: column;
  gap: 4px;

  strong {
    color: var(--ans-muted);
    font-size: 24px;

    &.is-open {
      color: var(--ans-accent);
    }
  }

  small {
    color: var(--ans-muted);
    font-size: 11px;
  }
}

.answer-status-label {
  color: var(--ans-muted);
  font-size: 11px;
  font-weight: 600;
}

.answer-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  padding-top: 10px;
  color: var(--ans-muted);
  font-size: 12px;
  border-top: 1px dashed var(--ans-line);

  b {
    color: var(--ans-ink);
    font-size: 15px;
  }
}

.answer-entry-card {
  display: flex;
  gap: 18px;
  align-items: center;
  justify-content: space-between;
}

.answer-entry-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  min-width: 0;

  strong {
    font-size: 16px;
  }

  small {
    color: var(--ans-muted);
    font-size: 11px;
  }
}

.answer-link-input {
  max-width: 420px;
}

.answer-entry-actions {
  display: flex;
  gap: 6px;
}

.answer-link-preview {
  overflow: hidden;
  color: var(--ans-accent);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.answer-qr {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 168px;
  height: 168px;
  background: #fbfdfc;
  border: 1px solid var(--ans-line);
  border-radius: 12px;

  img {
    width: 156px;
    height: 156px;
  }
}

.answer-qr-empty {
  color: var(--ans-muted);
  font-size: 11px;
}

.answer-tabs {
  padding: 6px 16px 16px;
  background: #fff;
  border: 1px solid var(--ans-line);
  border-radius: 16px;
}

.answer-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 4px 0 12px;
}

.answer-roster-name {
  width: 140px;
}

.answer-roster-dept {
  width: 180px;
}

.answer-toolbar-note {
  margin-left: auto;
  color: var(--ans-muted);
  font-size: 11px;
}

.answer-table {
  width: 100%;
}

.answer-question-expand {
  padding: 4px 16px 12px;
  color: #51655d;
  font-size: 12px;
  line-height: 1.7;

  p {
    margin: 4px 0;
  }
}

.answer-question-explain {
  color: var(--ans-accent);
}

.answer-dialog-note {
  margin: 8px 0 0;
  color: var(--ans-muted);
  font-size: 11px;
}

.answer-detail {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.answer-detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;

  div:first-child {
    display: flex;
    flex-direction: column;
    gap: 4px;

    strong {
      font-size: 20px;
    }

    span {
      color: var(--ans-muted);
      font-size: 12px;
    }
  }
}

.answer-detail-score {
  color: var(--ans-accent);

  strong {
    font-size: 30px;
  }

  span {
    color: var(--ans-muted);
    font-size: 12px;
  }
}

.answer-detail-meta {
  margin: 0 0 4px;
  color: var(--ans-muted);
  font-size: 11px;
}

.answer-detail-item {
  padding: 12px;
  background: #fbfdfc;
  border: 1px solid var(--ans-line);
  border-radius: 10px;
}

.answer-detail-stem {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.6;
}

.answer-detail-answer {
  margin: 0;
  color: var(--ans-muted);
  font-size: 12px;

  .is-wrong {
    color: #b04a4a;
  }

  .is-right {
    color: var(--ans-accent);
  }
}

.answer-detail-explain {
  padding-top: 6px;
  margin: 6px 0 0;
  color: #51655d;
  font-size: 11px;
  line-height: 1.7;
  border-top: 1px dashed var(--ans-line);
}

.answer-detail-loading {
  min-height: 220px;
}

@media (max-width: 1080px) {
  .answer-overview {
    grid-template-columns: 1fr;
  }

  .answer-entry-card {
    align-items: flex-start;
  }
}
</style>