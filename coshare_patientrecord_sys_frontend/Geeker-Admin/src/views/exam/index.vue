<template>
  <div class="exam-page">
    <div v-if="phase === 'loading'" class="exam-loading">
      <span class="exam-spinner" />
      <p>正在准备试卷…</p>
    </div>

    <div v-else-if="phase === 'error'" class="exam-shell exam-error">
      <h1>暂时无法打开试卷</h1>
      <p>{{ errorMessage }}</p>
      <button class="exam-primary" type="button" @click="load">重新加载</button>
    </div>

    <div v-else-if="phase === 'landing'" class="exam-shell">
      <header class="exam-brand">
        <span class="exam-mark">医保</span>
        <span>培训考核</span>
      </header>

      <section class="exam-hero">
        <span class="exam-eyebrow">内网答题 · 无需登录</span>
        <h1>{{ paper?.title || "医保合规与DIP管理考核" }}</h1>
        <p>{{ paper?.description }}</p>
        <div class="exam-chips">
          <span>{{ paper?.questionCount || 40 }} 题</span>
          <span>{{ paper?.totalScore || 100 }} 分</span>
          <span>不限时</span>
        </div>
      </section>

      <section class="exam-card">
        <el-alert
          v-if="paper && paper.status !== 'OPEN'"
          type="warning"
          :closable="false"
          show-icon
          title="本场考试尚未开放"
          description="请联系管理员开放后再进入；已开始答题的同事仍可继续交卷。"
        />
        <label class="exam-field">
          <span>姓名</span>
          <el-input v-model="nameInput" maxlength="40" placeholder="请输入真实姓名" />
        </label>
        <label class="exam-field">
          <span>科室</span>
          <button type="button" class="exam-select" @click="openDepartments">
            <span :class="{ 'is-placeholder': !department }">{{ department || "请选择科室" }}</span>
            <span class="exam-select-arrow">›</span>
          </button>
        </label>
        <button
          type="button"
          class="exam-primary exam-start"
          :disabled="starting || (paper?.status !== 'OPEN' && !canResume)"
          @click="start"
        >
          {{ starting ? "正在进入…" : canResume ? "继续上次答题" : "开始答题" }}
        </button>
      </section>

      <p class="exam-footnote">
        本卷全部为不定项选择，多选、少选、错选均不得分；交卷后不可修改。姓名和科室将用于成绩归档。
      </p>
    </div>

    <div v-else-if="phase === 'quiz'" class="exam-quiz">
      <header class="exam-quiz-head">
        <button type="button" class="exam-back" :disabled="index === 0" aria-label="上一题" @click="goBack">
          <el-icon><ArrowLeft /></el-icon>
        </button>
        <div class="exam-progress">
          <div class="exam-progress-bar"><i :style="{ width: `${progressPct}%` }" /></div>
          <span>第 {{ index + 1 }} / {{ questions.length }} 题</span>
        </div>
        <span class="exam-answered">{{ answeredCount }}/{{ questions.length }}</span>
      </header>

      <section v-if="current" :key="current.id" class="exam-question">
        <p class="exam-stem">{{ current.stem }}</p>
        <div class="exam-options">
          <button
            v-for="(option, optionIndex) in current.options"
            :key="optionIndex"
            type="button"
            class="exam-option"
            :class="{ selected: selectedLetters.includes(optionLetter(optionIndex)) }"
            @click="toggleOption(optionLetter(optionIndex))"
          >
            <i class="exam-option-letter">{{ optionLetter(optionIndex) }}</i>
            <span class="exam-option-text">{{ option }}</span>
            <el-icon v-if="selectedLetters.includes(optionLetter(optionIndex))" class="exam-option-check">
              <Check />
            </el-icon>
          </button>
        </div>
        <p class="exam-stem-tip">不定项选择：选全正确项才得分</p>
        <p v-if="draftWarning" class="exam-draft-warning">草稿暂存本机，网络恢复后会自动重试保存。</p>
      </section>

      <footer class="exam-quiz-footer">
        <button v-if="index > 0" type="button" class="exam-ghost" @click="goBack">上一题</button>
        <button
          type="button"
          class="exam-primary exam-next"
          :disabled="!selectedLetters.length || advancing || submitting"
          @click="confirmCurrent"
        >
          {{ isLast ? (submitting ? "正在交卷…" : "交卷") : advancing ? "下一题…" : "确定" }}
        </button>
      </footer>
    </div>

    <div v-else-if="phase === 'result' && result" class="exam-result">
      <section class="exam-result-hero">
        <span class="exam-eyebrow">已交卷</span>
        <h1>{{ result.score >= result.totalScore ? "满分完成" : "答题完成" }}</h1>
        <div class="exam-score">
          {{ formatScore(result.score) }}
          <small>/ {{ result.totalScore }}</small>
        </div>
        <p>答对 {{ result.correctCount }} / {{ result.totalQuestions }} 题 · 用时 {{ formatDuration(result.durationSeconds) }}</p>
      </section>

      <section v-if="result.wrongReview.length" class="exam-review">
        <header>
          <h2>错题解析</h2>
          <span>{{ result.wrongReview.length }} 题</span>
        </header>
        <article v-for="item in result.wrongReview" :key="item.seq" class="exam-review-item">
          <p class="exam-review-stem">{{ item.seq }}. {{ item.stem }}</p>
          <p class="exam-review-answer">
            你的选择 <b class="is-wrong">{{ item.chosen }}</b>
            <span>·</span>
            正确答案 <b class="is-right">{{ item.answer }}</b>
          </p>
          <p v-if="item.explanation" class="exam-review-explain">{{ item.explanation }}</p>
        </article>
      </section>
      <p v-else class="exam-full">全部答对，没有错题。</p>

      <button type="button" class="exam-primary" @click="backToLanding">返回首页</button>
      <p class="exam-footnote">本次答卷已提交，不能修改；如需重考请联系管理员作废原答卷。</p>
    </div>

    <el-drawer
      v-model="departmentSheetVisible"
      direction="btt"
      size="62%"
      title="选择科室"
      class="exam-department-drawer"
    >
      <el-input v-model="departmentKeyword" placeholder="搜索科室" clearable :prefix-icon="Search" />
      <div class="exam-department-list">
        <button
          v-for="item in filteredDepartments"
          :key="item"
          type="button"
          class="exam-department-item"
          :class="{ active: item === department }"
          @click="chooseDepartment(item)"
        >
          {{ item }}
          <el-icon v-if="item === department"><Check /></el-icon>
        </button>
        <p v-if="!filteredDepartments.length" class="exam-department-empty">没有匹配的科室</p>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { ArrowLeft, Check, Search } from "@element-plus/icons-vue";
import {
  getExamAttemptApi,
  getExamPaperApi,
  saveExamDraftApi,
  startExamApi,
  submitExamApi,
  type ExamAttempt,
  type ExamPaperView,
  type ExamQuestion,
  type ExamResult
} from "@/api/modules/exam/public";

defineOptions({ name: "clinicExam" });

type Phase = "loading" | "landing" | "quiz" | "result" | "error";

const SESSION_KEY = "hos-exam-session:v1";
const DRAFT_PREFIX = "hos-exam-draft:v1:";

const phase = ref<Phase>("loading");
const errorMessage = ref("");
const paperView = ref<ExamPaperView | null>(null);
const nameInput = ref("");
const department = ref("");
const departmentSheetVisible = ref(false);
const departmentKeyword = ref("");
const starting = ref(false);
const submitting = ref(false);
const advancing = ref(false);
const draftWarning = ref(false);
const hasSession = ref(false);
const attempt = ref<ExamAttempt | null>(null);
const answers = ref<Record<string, string>>({});
const index = ref(0);
const result = ref<ExamResult | null>(null);
let draftTimer: ReturnType<typeof setTimeout> | undefined;
let draftQueue = Promise.resolve();

const paper = computed(() => paperView.value?.paper);
const questions = computed<ExamQuestion[]>(() => paperView.value?.questions || []);
const current = computed(() => questions.value[index.value] || null);
const selectedLetters = computed(() => {
  const id = current.value?.id;
  if (!id) return [];
  return (answers.value[id] || "").split("").filter(Boolean);
});
const answeredCount = computed(() => questions.value.filter(question => Boolean(answers.value[question.id])).length);
const progressPct = computed(() => Math.round((answeredCount.value / Math.max(1, questions.value.length)) * 100));
const isLast = computed(() => index.value >= questions.value.length - 1);
const canResume = computed(() => hasSession.value);

const filteredDepartments = computed(() => {
  const keyword = departmentKeyword.value.trim();
  const list = paperView.value?.departments || [];
  return keyword ? list.filter(item => item.includes(keyword)) : list;
});

const optionLetter = (value: number) => String.fromCharCode(65 + value);

const readSession = (): { attemptId: string; token: string; name: string; department: string } | null => {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed?.attemptId || !parsed?.token) return null;
    return parsed;
  } catch {
    return null;
  }
};

const saveSession = (value: { attemptId: string; token: string; name: string; department: string }) => {
  try {
    localStorage.setItem(SESSION_KEY, JSON.stringify(value));
    hasSession.value = true;
  } catch {
    // 忽略隐私模式下的存储失败
  }
};

const clearSession = () => {
  try {
    localStorage.removeItem(SESSION_KEY);
  } catch {
    // ignore
  }
  hasSession.value = false;
};

const draftKey = () => (attempt.value ? `${DRAFT_PREFIX}${attempt.value.id}` : "");

const saveLocalDraft = () => {
  const key = draftKey();
  if (!key) return;
  try {
    localStorage.setItem(key, JSON.stringify({ answers: answers.value, pending: true }));
  } catch {
    // ignore
  }
};

const clearLocalDraft = () => {
  const key = draftKey();
  if (!key) return;
  try {
    localStorage.removeItem(key);
  } catch {
    // ignore
  }
};

const saveDraftNow = async () => {
  if (!attempt.value?.id || !attempt.value.token || phase.value !== "quiz") return;
  clearTimeout(draftTimer);
  saveLocalDraft();
  const id = attempt.value.id;
  const token = attempt.value.token;
  const snapshot = { ...answers.value };
  draftQueue = draftQueue.then(async () => {
    try {
      await saveExamDraftApi(id, { token, answers: snapshot });
      if (JSON.stringify(answers.value) === JSON.stringify(snapshot)) {
        clearLocalDraft();
        draftWarning.value = false;
      }
    } catch {
      draftWarning.value = true;
    }
  });
  await draftQueue;
};

const scheduleDraftSave = () => {
  saveLocalDraft();
  clearTimeout(draftTimer);
  draftTimer = setTimeout(() => void saveDraftNow(), 600);
};

const resolveFirstUnanswered = () => {
  const found = questions.value.findIndex(question => !answers.value[question.id]);
  return found >= 0 ? found : Math.max(0, questions.value.length - 1);
};

const applyAttempt = (data: { attempt: ExamAttempt; result: ExamResult | null }) => {
  attempt.value = data.attempt;
  nameInput.value = data.attempt.name;
  department.value = data.attempt.department;
  if (data.attempt.token) {
    saveSession({
      attemptId: data.attempt.id,
      token: data.attempt.token,
      name: data.attempt.name,
      department: data.attempt.department
    });
  }
  if (data.result) {
    result.value = data.result;
    phase.value = "result";
    clearLocalDraft();
    window.scrollTo(0, 0);
    return;
  }
  answers.value = { ...(data.attempt.answers || {}) };
  try {
    const local = JSON.parse(localStorage.getItem(draftKey()) || "null");
    if (local?.pending && local.answers && typeof local.answers === "object") {
      answers.value = { ...answers.value, ...local.answers };
    }
  } catch {}
  index.value = resolveFirstUnanswered();
  draftWarning.value = false;
  phase.value = "quiz";
  window.scrollTo(0, 0);
};

const load = async () => {
  phase.value = "loading";
  errorMessage.value = "";
  try {
    paperView.value = await getExamPaperApi();
    const session = readSession();
    hasSession.value = Boolean(session);
    if (session) {
      try {
        applyAttempt(await getExamAttemptApi(session.attemptId, session.token));
        return;
      } catch (error: any) {
        clearSession();
        if (error?.status !== 409) {
          // 会话失效时回到落地页，不阻塞重新输入
        }
      }
    }
    phase.value = "landing";
  } catch (error: any) {
    errorMessage.value = error?.message || "试卷加载失败，请稍后重试";
    phase.value = "error";
  }
};

const openDepartments = () => {
  departmentKeyword.value = "";
  departmentSheetVisible.value = true;
};

const chooseDepartment = (value: string) => {
  department.value = value;
  departmentSheetVisible.value = false;
};

const start = async () => {
  const name = nameInput.value.trim();
  if (name.length < 2) {
    ElMessage.warning("请输入真实姓名");
    return;
  }
  if (!department.value) {
    ElMessage.warning("请选择科室");
    return;
  }
  starting.value = true;
  try {
    const data = await startExamApi({ name, department: department.value });
    applyAttempt(data);
  } catch (error: any) {
    ElMessage.error(error?.message || "进入考试失败");
  } finally {
    starting.value = false;
  }
};

const toggleOption = (letter: string) => {
  if (!current.value) return;
  const next = new Set(selectedLetters.value);
  if (next.has(letter)) next.delete(letter);
  else next.add(letter);
  const value = [...next].sort().join("");
  answers.value = { ...answers.value, [current.value.id]: value };
  scheduleDraftSave();
};

const goBack = () => {
  if (index.value > 0) {
    index.value -= 1;
  }
};

const confirmCurrent = async () => {
  if (!current.value) return;
  if (!selectedLetters.value.length) {
    ElMessage.warning("请至少选择一个选项");
    return;
  }
  if (isLast.value) {
    await handleSubmit();
    return;
  }
  advancing.value = true;
  await saveDraftNow();
  setTimeout(() => {
    index.value += 1;
    advancing.value = false;
    window.scrollTo({ top: 0, behavior: "smooth" });
  }, 300);
};

const handleSubmit = async () => {
  const missing = questions.value.findIndex(question => !answers.value[question.id]);
  if (missing >= 0) {
    index.value = missing;
    ElMessage.warning(`第 ${missing + 1} 题还未作答，请补完后再交卷`);
    return;
  }
  try {
    await ElMessageBox.confirm(
      `已答 ${questions.value.length} 题，确认交卷？交卷后不能修改。`,
      "确认交卷",
      { confirmButtonText: "交卷", cancelButtonText: "再检查一下", type: "warning" }
    );
  } catch {
    return;
  }
  if (!attempt.value?.id || !attempt.value.token) return;
  submitting.value = true;
  try {
    await draftQueue;
    clearTimeout(draftTimer);
    const data = await submitExamApi(attempt.value.id, {
      token: attempt.value.token,
      answers: answers.value
    });
    if (data.result) {
      attempt.value = data.attempt;
      result.value = data.result;
      phase.value = "result";
      clearLocalDraft();
      window.scrollTo(0, 0);
    }
  } catch (error: any) {
    if (error?.status === 409) {
      const first = questions.value.findIndex(question => !answers.value[question.id]);
      if (first >= 0) index.value = first;
    }
    ElMessage.error(error?.message || "交卷失败，请重试");
  } finally {
    submitting.value = false;
  }
};

const backToLanding = () => {
  clearSession();
  phase.value = "landing";
  result.value = null;
  attempt.value = null;
  answers.value = {};
  index.value = 0;
  window.scrollTo(0, 0);
};

const formatScore = (value: number) => {
  const number = Number(value || 0);
  return Number.isInteger(number) ? String(number) : number.toFixed(1);
};

const formatDuration = (seconds: number) => {
  const value = Math.max(0, Number(seconds || 0));
  const minutes = Math.floor(value / 60);
  const rest = value % 60;
  return minutes ? `${minutes} 分 ${rest} 秒` : `${rest} 秒`;
};

onMounted(() => {
  void load();
});

onBeforeUnmount(() => {
  clearTimeout(draftTimer);
});
</script>

<style scoped lang="scss">
.exam-page {
  --exam-accent: #0f766e;
  --exam-accent-soft: #e6f3f0;
  --exam-ink: #17332b;
  --exam-muted: #6e8179;
  --exam-line: #e0eae6;
  --exam-warm: #b7793f;
  --exam-danger: #b04a4a;

  min-height: 100dvh;
  color: var(--exam-ink);
  background: linear-gradient(180deg, #f3f8f6 0%, #fbfdfc 42%, #fff 100%);
  -webkit-font-smoothing: antialiased;
}

.exam-shell,
.exam-quiz,
.exam-result {
  box-sizing: border-box;
  width: min(100%, 560px);
  margin: 0 auto;
  padding: calc(20px + env(safe-area-inset-top)) 18px calc(24px + env(safe-area-inset-bottom));
}

.exam-loading,
.exam-error {
  display: flex;
  flex-direction: column;
  gap: 14px;
  align-items: center;
  justify-content: center;
  min-height: 100dvh;
  padding: 24px;
  text-align: center;
}

.exam-spinner {
  width: 28px;
  height: 28px;
  border: 3px solid var(--exam-accent-soft);
  border-top-color: var(--exam-accent);
  border-radius: 50%;
  animation: exam-spin 800ms linear infinite;
}

@keyframes exam-spin {
  to {
    transform: rotate(360deg);
  }
}

.exam-brand {
  display: flex;
  gap: 8px;
  align-items: center;
  color: var(--exam-muted);
  font-size: 13px;
  letter-spacing: 0.04em;
}

.exam-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  background: var(--exam-accent);
  border-radius: 10px;
}

.exam-hero {
  margin: 26px 0 20px;

  h1 {
    margin: 8px 0 10px;
    font-size: 25px;
    line-height: 1.35;
  }

  p {
    margin: 0;
    color: var(--exam-muted);
    font-size: 13px;
    line-height: 1.7;
  }
}

.exam-eyebrow {
  color: var(--exam-accent);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.exam-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;

  span {
    padding: 5px 10px;
    color: var(--exam-accent);
    font-size: 12px;
    font-weight: 600;
    background: var(--exam-accent-soft);
    border-radius: 999px;
  }
}

.exam-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 18px;
  background: #fff;
  border: 1px solid var(--exam-line);
  border-radius: 18px;
  box-shadow: 0 14px 36px rgb(23 51 43 / 6%);
}

.exam-field {
  display: flex;
  flex-direction: column;
  gap: 7px;

  > span {
    color: var(--exam-muted);
    font-size: 12px;
    font-weight: 600;
  }
}

.exam-select {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-height: 48px;
  padding: 0 14px;
  color: var(--exam-ink);
  font-size: 15px;
  text-align: left;
  cursor: pointer;
  background: #fbfdfc;
  border: 1px solid var(--exam-line);
  border-radius: 12px;

  .is-placeholder {
    color: #9aada5;
  }
}

.exam-select-arrow {
  color: var(--exam-muted);
  font-size: 20px;
  line-height: 1;
}

.exam-primary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 52px;
  padding: 0 18px;
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
  background: var(--exam-accent);
  border: 0;
  border-radius: 14px;
  transition: opacity 150ms ease, transform 150ms ease;

  &:active {
    transform: scale(0.99);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.45;
  }
}

.exam-start {
  margin-top: 2px;
}

.exam-footnote {
  margin: 14px 2px 0;
  color: var(--exam-muted);
  font-size: 11px;
  line-height: 1.7;
}

.exam-quiz {
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
  padding-bottom: calc(92px + env(safe-area-inset-bottom));
}

.exam-quiz-head {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 4px 0 14px;
}

.exam-back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  color: var(--exam-ink);
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--exam-line);
  border-radius: 50%;

  &:disabled {
    cursor: not-allowed;
    opacity: 0.35;
  }
}

.exam-progress {
  flex: 1;
  min-width: 0;

  span {
    display: block;
    margin-top: 5px;
    color: var(--exam-muted);
    font-size: 11px;
  }
}

.exam-progress-bar {
  height: 4px;
  overflow: hidden;
  background: #e2eee9;
  border-radius: 999px;

  i {
    display: block;
    height: 100%;
    background: var(--exam-accent);
    border-radius: inherit;
    transition: width 220ms ease;
  }
}

.exam-answered {
  color: var(--exam-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.exam-question {
  flex: 1;
  padding-top: 8px;
  animation: exam-question-in 200ms ease-out;
}

.exam-stem {
  margin: 0 0 20px;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.7;
}

.exam-options {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.exam-option {
  display: flex;
  gap: 12px;
  align-items: center;
  width: 100%;
  min-height: 62px;
  padding: 12px 14px;
  color: var(--exam-ink);
  font-size: 15px;
  line-height: 1.55;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1.5px solid var(--exam-line);
  border-radius: 14px;
  transition: border-color 140ms ease, background 140ms ease;

  &.selected {
    background: var(--exam-accent-soft);
    border-color: var(--exam-accent);
  }

  &:focus-visible {
    outline: 2px solid var(--exam-accent);
    outline-offset: 2px;
  }
}

.exam-option-letter {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  color: var(--exam-muted);
  font-size: 13px;
  font-style: normal;
  font-weight: 700;
  background: #f5f9f7;
  border-radius: 50%;

  .selected & {
    color: #fff;
    background: var(--exam-accent);
  }
}

.exam-option-text {
  flex: 1;
}

.exam-option-check {
  flex-shrink: 0;
  color: var(--exam-accent);
  font-size: 18px;
}

.exam-stem-tip {
  margin: 16px 2px 0;
  color: #9aada5;
  font-size: 11px;
}

.exam-draft-warning {
  margin: 8px 2px 0;
  color: var(--exam-warm);
  font-size: 11px;
}

.exam-quiz-footer {
  box-sizing: border-box;
  position: fixed;
  bottom: 0;
  left: 50%;
  display: flex;
  gap: 10px;
  width: min(100%, 560px);
  padding: 12px 18px calc(12px + env(safe-area-inset-bottom));
  background: rgb(255 255 255 / 96%);
  border-top: 1px solid var(--exam-line);
  transform: translateX(-50%);
  backdrop-filter: blur(10px);
}

.exam-ghost {
  flex: 0 0 92px;
  min-height: 52px;
  color: var(--exam-muted);
  font-size: 14px;
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--exam-line);
  border-radius: 14px;
}

.exam-next {
  flex: 1;
}

.exam-result {
  padding-bottom: calc(32px + env(safe-area-inset-bottom));
}

.exam-result-hero {
  padding: 18px 0 22px;
  text-align: center;

  h1 {
    margin: 6px 0 14px;
    font-size: 24px;
  }

  p {
    margin: 10px 0 0;
    color: var(--exam-muted);
    font-size: 12px;
  }
}

.exam-score {
  display: flex;
  gap: 6px;
  align-items: baseline;
  justify-content: center;
  color: var(--exam-accent);
  font-size: 58px;
  font-weight: 800;
  line-height: 1;
  font-variant-numeric: tabular-nums;

  small {
    color: var(--exam-muted);
    font-size: 15px;
    font-weight: 500;
  }
}

.exam-review {
  margin: 8px 0 20px;

  > header {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    margin-bottom: 10px;

    h2 {
      margin: 0;
      font-size: 16px;
    }

    span {
      color: var(--exam-muted);
      font-size: 11px;
    }
  }
}

.exam-review-item {
  padding: 14px;
  margin-bottom: 10px;
  background: #fff;
  border: 1px solid var(--exam-line);
  border-radius: 14px;
}

.exam-review-stem {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.6;
}

.exam-review-answer {
  margin: 0;
  color: var(--exam-muted);
  font-size: 12px;

  span {
    margin: 0 6px;
    color: #c7d4cf;
  }

  b {
    font-weight: 700;
  }

  .is-wrong {
    color: var(--exam-danger);
  }

  .is-right {
    color: var(--exam-accent);
  }
}

.exam-review-explain {
  padding-top: 8px;
  margin: 8px 0 0;
  color: #51655d;
  font-size: 12px;
  line-height: 1.7;
  border-top: 1px dashed var(--exam-line);
}

.exam-full {
  padding: 18px;
  color: var(--exam-accent);
  font-size: 13px;
  text-align: center;
  background: var(--exam-accent-soft);
  border-radius: 14px;
}

.exam-department-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 12px;
  overflow: auto;
}

.exam-department-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 46px;
  padding: 0 12px;
  color: var(--exam-ink);
  font-size: 14px;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 10px;

  &.active {
    color: var(--exam-accent);
    background: var(--exam-accent-soft);
  }
}

.exam-department-empty {
  padding: 24px;
  color: var(--exam-muted);
  font-size: 12px;
  text-align: center;
}

@keyframes exam-question-in {
  from {
    opacity: 0;
    transform: translateX(14px);
  }

  to {
    opacity: 1;
    transform: translateX(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .exam-question {
    animation: none;
  }

  .exam-progress-bar i {
    transition: none;
  }

  .exam-spinner {
    animation: none;
  }
}

@media (min-width: 720px) {
  .exam-shell,
  .exam-quiz,
  .exam-result {
    padding-top: 42px;
  }

  .exam-card {
    padding: 22px;
  }
}
</style>