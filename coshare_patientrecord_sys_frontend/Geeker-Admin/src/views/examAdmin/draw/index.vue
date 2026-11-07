<template>
  <section class="draw-page" v-loading="loading">
    <header class="draw-head">
      <div>
        <span class="draw-eyebrow">周一例会 · 培训考核</span>
        <h1>抽人抽题</h1>
        <p>{{ meetingDate }} · 候选 {{ selectedIds.length }} 人 · 题库 {{ questions.length }} 题</p>
      </div>
      <div class="draw-head-actions">
        <el-button :icon="User" @click="attendanceVisible = true">选择在场人员</el-button>
        <el-button :icon="FullScreen" @click="toggleFullscreen">全屏</el-button>
        <el-button :icon="Refresh" :disabled="!drawnPeople.length && !drawnQuestions.length" @click="resetDraw">
          重置抽签
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="!roster.length"
      type="warning"
      show-icon
      :closable="false"
      title="花名册为空"
      description="请先到「答卷管理 → 花名册」导入姓名和科室，再回来抽人。"
    />

    <div ref="stageRef" class="draw-stage">
      <section class="draw-panel draw-person">
        <header class="draw-panel-head">
          <span class="draw-panel-label">抽人</span>
          <span class="draw-panel-meta">已抽 {{ drawnPeople.length }} / 候选 {{ selectedIds.length }}</span>
        </header>

        <div class="draw-person-card" :class="{ 'is-rolling': personRolling, 'is-empty': !currentPerson && !personRolling }">
          <template v-if="personRolling">
            <span class="draw-person-name is-rolling">抽取中…</span>
            <span class="draw-person-dept">从 {{ eligiblePeople.length }} 位在场人员中抽取</span>
          </template>
          <template v-else-if="currentPerson">
            <span class="draw-person-name">{{ currentPerson.name }}</span>
            <span class="draw-person-dept">{{ currentPerson.department }}</span>
          </template>
          <template v-else>
            <span class="draw-placeholder">点击下方按钮抽取</span>
            <span class="draw-placeholder-sub">从勾选的在场人员中随机产生</span>
          </template>
        </div>

        <el-button
          class="draw-action"
          type="primary"
          size="large"
          :loading="personRolling"
          :disabled="!eligiblePeople.length || personRolling || questionRolling"
          @click="drawPerson"
        >
          {{ eligiblePeople.length ? "抽一个人" : "本轮已抽完" }}
        </el-button>
      </section>

      <section class="draw-panel draw-question">
        <header class="draw-panel-head">
          <span class="draw-panel-label">抽题</span>
          <span class="draw-panel-meta">已抽 {{ drawnQuestions.length }} / 题库 {{ questions.length }}</span>
        </header>

        <div class="draw-question-card">
          <template v-if="questionRolling">
            <p class="draw-question-rolling">正在抽取题目…</p>
            <span class="draw-question-rolling-seq">题库剩余 {{ eligibleQuestions.length }} 题</span>
          </template>
          <template v-else-if="currentQuestion">
            <p class="draw-question-seq">第 {{ currentQuestion.seq }} 题 · {{ currentQuestion.score }} 分</p>
            <p class="draw-question-stem">{{ currentQuestion.stem }}</p>
            <div class="draw-options">
              <div
                v-for="(option, optionIndex) in currentQuestion.options"
                :key="optionIndex"
                class="draw-option"
                :class="{ 'is-correct': answerRevealed && currentQuestion.answer.includes(letter(optionIndex)) }"
              >
                <i>{{ letter(optionIndex) }}</i>
                <span>{{ option }}</span>
              </div>
            </div>
            <div v-if="answerRevealed" class="draw-answer">
              <strong>正确答案：{{ currentQuestion.answer.join("") }}</strong>
              <p v-if="currentQuestion.explanation">{{ currentQuestion.explanation }}</p>
            </div>
          </template>
          <template v-else>
            <span class="draw-placeholder">点击下方按钮抽取</span>
            <span class="draw-placeholder-sub">题目来自本次 40 题题库</span>
          </template>
        </div>

        <div class="draw-question-actions">
          <el-button
            class="draw-action"
            type="primary"
            size="large"
            :loading="questionRolling"
            :disabled="!eligibleQuestions.length || questionRolling || personRolling"
            @click="drawQuestion"
          >
            {{ eligibleQuestions.length ? "抽一道题" : "本轮已抽完" }}
          </el-button>
          <el-button
            v-if="currentQuestion && !questionRolling"
            size="large"
            :type="answerRevealed ? 'default' : 'success'"
            :icon="View"
            @click="answerRevealed = !answerRevealed"
          >
            {{ answerRevealed ? "隐藏答案" : "显示答案" }}
          </el-button>
        </div>
      </section>
    </div>

    <section v-if="drawnPeople.length || drawnQuestions.length" class="draw-history">
      <div>
        <strong>本轮已抽</strong>
        <span v-if="drawnPeople.length">人员：{{ drawnPeople.map(id => rosterById.get(id)?.name).filter(Boolean).join("、") }}</span>
        <span v-if="drawnQuestions.length">题目：{{ drawnQuestions.map(seq => `第${seq}题`).join("、") }}</span>
      </div>
    </section>

    <el-drawer v-model="attendanceVisible" title="选择在场人员" size="480px" append-to-body>
      <div class="attendance-body">
        <div class="attendance-toolbar">
          <el-input v-model="rosterKeyword" placeholder="搜索姓名或科室" clearable :prefix-icon="Search" />
          <div>
            <el-button text @click="selectAll">全选</el-button>
            <el-button text @click="selectedIds = []">清空</el-button>
          </div>
        </div>
        <div class="attendance-list">
          <section v-for="group in rosterGroups" :key="group.department" class="attendance-group">
            <header>{{ group.department }} · {{ group.people.length }} 人</header>
            <el-checkbox
              v-for="person in group.people"
              :key="person.id"
              :model-value="selectedIds.includes(person.id)"
              class="attendance-person"
              @change="togglePerson(person.id)"
            >
              {{ person.name }}
            </el-checkbox>
          </section>
          <p v-if="!rosterGroups.length" class="attendance-empty">没有匹配的花名册人员</p>
        </div>
        <p class="attendance-note">只从勾选的人里抽人；勾选结果保存在当前浏览器。</p>
      </div>
      <template #footer>
        <el-button @click="attendanceVisible = false">关闭</el-button>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { FullScreen, Refresh, Search, User, View } from "@element-plus/icons-vue";
import screenfull from "screenfull";
import dayjs from "dayjs";
import {
  getExamAdminQuestionsApi,
  getExamRosterApi,
  type ExamAdminQuestion,
  type ExamRosterItem
} from "@/api/modules/clinic/examAdmin";

defineOptions({ name: "examAdminDraw" });

const STORAGE_PREFIX = "hos-meeting-draw:v1:";

const meetingDate = dayjs().format("YYYY-MM-DD");
const loading = ref(false);
const roster = ref<ExamRosterItem[]>([]);
const questions = ref<ExamAdminQuestion[]>([]);
const selectedIds = ref<string[]>([]);
const drawnPeople = ref<string[]>([]);
const drawnQuestions = ref<number[]>([]);
const currentPerson = ref<ExamRosterItem | null>(null);
const currentQuestion = ref<ExamAdminQuestion | null>(null);
const answerRevealed = ref(false);
const personRolling = ref(false);
const questionRolling = ref(false);
const attendanceVisible = ref(false);
const rosterKeyword = ref("");
const stageRef = ref<HTMLElement | null>(null);
let personTimer: ReturnType<typeof setTimeout> | undefined;
let questionTimer: ReturnType<typeof setTimeout> | undefined;

const rosterById = computed(() => {
  const map = new Map<string, ExamRosterItem>();
  for (const item of roster.value) map.set(item.id, item);
  return map;
});

const eligiblePeople = computed(() => {
  const drawn = new Set(drawnPeople.value);
  return selectedIds.value
    .map(id => rosterById.value.get(id))
    .filter((item): item is ExamRosterItem => item !== undefined && !drawn.has(item.id));
});

const eligibleQuestions = computed(() =>
  questions.value.filter(question => !drawnQuestions.value.includes(question.seq))
);

const rosterGroups = computed(() => {
  const keyword = rosterKeyword.value.trim();
  const filtered = keyword
    ? roster.value.filter(item => item.name.includes(keyword) || item.department.includes(keyword))
    : roster.value;
  const groups = new Map<string, ExamRosterItem[]>();
  for (const item of filtered) {
    if (!groups.has(item.department)) groups.set(item.department, []);
    groups.get(item.department)!.push(item);
  }
  return Array.from(groups.entries())
    .map(([department, people]) => ({ department, people }))
    .sort((a, b) => a.department.localeCompare(b.department, "zh-CN"));
});

const letter = (index: number) => String.fromCharCode(65 + index);

const randomIndex = (length: number) => {
  if (length <= 1) return 0;
  const values = new Uint32Array(1);
  crypto.getRandomValues(values);
  return values[0] % length;
};

const storageKey = () => `${STORAGE_PREFIX}${meetingDate}`;

const saveState = () => {
  try {
    localStorage.setItem(
      storageKey(),
      JSON.stringify({
        selectedIds: selectedIds.value,
        drawnPeople: drawnPeople.value,
        drawnQuestions: drawnQuestions.value,
        currentPersonId: currentPerson.value?.id || "",
        currentQuestionSeq: currentQuestion.value?.seq || 0,
        answerRevealed: answerRevealed.value
      })
    );
  } catch {
    // ignore
  }
};

const restoreState = () => {
  try {
    const raw = localStorage.getItem(storageKey());
    if (!raw) {
      selectedIds.value = roster.value.map(item => item.id);
      return;
    }
    const parsed = JSON.parse(raw);
    const validIds = new Set(roster.value.map(item => item.id));
    selectedIds.value = Array.isArray(parsed.selectedIds)
      ? parsed.selectedIds.filter((id: string) => validIds.has(id))
      : roster.value.map(item => item.id);
    drawnPeople.value = Array.isArray(parsed.drawnPeople) ? parsed.drawnPeople : [];
    drawnQuestions.value = Array.isArray(parsed.drawnQuestions) ? parsed.drawnQuestions : [];
    currentPerson.value = parsed.currentPersonId ? rosterById.value.get(parsed.currentPersonId) || null : null;
    currentQuestion.value = parsed.currentQuestionSeq
      ? questions.value.find(question => question.seq === parsed.currentQuestionSeq) || null
      : null;
    answerRevealed.value = Boolean(parsed.answerRevealed && currentQuestion.value);
  } catch {
    selectedIds.value = roster.value.map(item => item.id);
  }
};

const load = async () => {
  loading.value = true;
  try {
    const [rosterData, questionData] = await Promise.all([getExamRosterApi(), getExamAdminQuestionsApi()]);
    roster.value = rosterData.list;
    questions.value = questionData.questions;
    restoreState();
  } catch (error: any) {
    ElMessage.error(error?.message || "抽题数据加载失败");
  } finally {
    loading.value = false;
  }
};

const togglePerson = (id: string) => {
  selectedIds.value = selectedIds.value.includes(id)
    ? selectedIds.value.filter(item => item !== id)
    : [...selectedIds.value, id];
};

const selectAll = () => {
  selectedIds.value = roster.value.map(item => item.id);
};

const drawPerson = () => {
  const pool = eligiblePeople.value;
  if (!pool.length) {
    ElMessage.warning("本轮在场人员已抽完，请重置或重新勾选");
    return;
  }
  personRolling.value = true;
  currentPerson.value = null;
  answerRevealed.value = false;
  clearTimeout(personTimer);
  personTimer = setTimeout(() => {
    const picked = pool[randomIndex(pool.length)];
    currentPerson.value = picked;
    drawnPeople.value = [...drawnPeople.value, picked.id];
    personRolling.value = false;
    saveState();
  }, 900);
};

const drawQuestion = () => {
  const pool = eligibleQuestions.value;
  if (!pool.length) {
    ElMessage.warning("本轮题目已抽完，请重置");
    return;
  }
  questionRolling.value = true;
  currentQuestion.value = null;
  answerRevealed.value = false;
  clearTimeout(questionTimer);
  questionTimer = setTimeout(() => {
    const picked = pool[randomIndex(pool.length)];
    currentQuestion.value = picked;
    drawnQuestions.value = [...drawnQuestions.value, picked.seq];
    questionRolling.value = false;
    saveState();
  }, 900);
};

const resetDraw = async () => {
  try {
    await ElMessageBox.confirm("清空本轮已抽的人员和题目？在场勾选会保留。", "重置抽签", {
      type: "warning",
      confirmButtonText: "重置",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  drawnPeople.value = [];
  drawnQuestions.value = [];
  currentPerson.value = null;
  currentQuestion.value = null;
  answerRevealed.value = false;
  saveState();
};

const toggleFullscreen = async () => {
  if (!screenfull.isEnabled) {
    ElMessage.warning("当前浏览器不支持全屏");
    return;
  }
  if (!stageRef.value) return;
  await screenfull.toggle(stageRef.value);
};

watch([selectedIds, drawnPeople, drawnQuestions, answerRevealed], saveState, { deep: true });

onMounted(load);
onBeforeUnmount(() => {
  clearTimeout(personTimer);
  clearTimeout(questionTimer);
});
</script>

<style scoped lang="scss">
.draw-page {
  --draw-ink: #17332b;
  --draw-muted: #6e8179;
  --draw-line: #e0eae6;
  --draw-accent: #0f766e;
  --draw-soft: #e6f3f0;
  --draw-warm: #b7793f;

  box-sizing: border-box;
  max-width: 1440px;
  min-height: 100%;
  padding: 18px 24px 28px;
  margin: 0 auto;
  color: var(--draw-ink);
  background: #f6faf8;
}

.draw-head {
  display: flex;
  gap: 16px;
  align-items: flex-end;
  justify-content: space-between;
  padding: 0 2px 16px;

  h1 {
    margin: 2px 0 4px;
    font-size: 28px;
  }

  p {
    margin: 0;
    color: var(--draw-muted);
    font-size: 12px;
  }
}

.draw-eyebrow {
  color: var(--draw-accent);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.draw-head-actions {
  display: flex;
  gap: 8px;
}

.draw-stage {
  display: grid;
  grid-template-columns: 0.8fr 1.2fr;
  gap: 16px;
  padding: 16px;
  background: #fff;
  border: 1px solid var(--draw-line);
  border-radius: 18px;
  box-shadow: 0 18px 46px rgb(23 51 43 / 6%);
}

.draw-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  padding: 16px;
  background: #fbfdfc;
  border: 1px solid var(--draw-line);
  border-radius: 14px;
}

.draw-panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.draw-panel-label {
  color: var(--draw-accent);
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.draw-panel-meta {
  color: var(--draw-muted);
  font-size: 11px;
}

.draw-person-card {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  align-items: center;
  justify-content: center;
  min-height: 300px;
  padding: 28px 18px;
  text-align: center;
  background: linear-gradient(160deg, #f0f8f5 0%, #fff 70%);
  border: 1px dashed #c9ddd6;
  border-radius: 14px;

  &.is-rolling .draw-person-name {
    color: var(--draw-warm);
  }
}

.draw-person-name {
  font-size: clamp(48px, 6vw, 76px);
  font-weight: 800;
  line-height: 1.15;
  letter-spacing: 0.04em;

  &.is-rolling {
    color: var(--draw-muted);
    font-size: clamp(26px, 3vw, 38px);
    font-weight: 600;
    animation: draw-pulse 900ms ease-in-out infinite;
  }
}

@keyframes draw-pulse {
  0%,
  100% {
    opacity: 0.45;
  }

  50% {
    opacity: 1;
  }
}

.draw-person-dept {
  color: var(--draw-muted);
  font-size: 16px;
}

.draw-placeholder {
  color: #9aada5;
  font-size: 20px;
  font-weight: 600;
}

.draw-placeholder-sub {
  color: #b3c2bc;
  font-size: 12px;
}

.draw-question-card {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  min-height: 300px;
  padding: 18px;
  background: #fff;
  border: 1px solid var(--draw-line);
  border-radius: 14px;
}

.draw-question-seq {
  margin: 0;
  color: var(--draw-warm);
  font-size: 12px;
  font-weight: 700;
}

.draw-question-stem {
  margin: 0;
  font-size: clamp(20px, 2vw, 28px);
  font-weight: 700;
  line-height: 1.55;
}

.draw-options {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.draw-option {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 8px 10px;
  font-size: 14px;
  line-height: 1.6;
  border: 1px solid transparent;
  border-radius: 10px;

  i {
    display: inline-flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    color: var(--draw-muted);
    font-size: 11px;
    font-style: normal;
    font-weight: 700;
    background: #f2f7f5;
    border-radius: 50%;
  }

  &.is-correct {
    background: var(--draw-soft);
    border-color: #b9dcd2;

    i {
      color: #fff;
      background: var(--draw-accent);
    }
  }
}

.draw-answer {
  padding: 12px;
  background: var(--draw-soft);
  border-radius: 10px;

  strong {
    color: var(--draw-accent);
    font-size: 14px;
  }

  p {
    margin: 8px 0 0;
    color: #51655d;
    font-size: 12px;
    line-height: 1.7;
  }
}

.draw-question-rolling {
  margin: 0;
  color: var(--draw-muted);
  font-size: 16px;
}

.draw-question-rolling-seq {
  color: var(--draw-warm);
  font-size: 40px;
  font-weight: 800;
}

.draw-question-actions {
  display: flex;
  gap: 10px;
}

.draw-action {
  flex: 1;
  height: 46px;
  font-size: 15px;
}

.draw-history {
  padding: 12px 16px;
  margin-top: 14px;
  color: var(--draw-muted);
  font-size: 12px;
  background: #fff;
  border: 1px solid var(--draw-line);
  border-radius: 12px;

  div {
    display: flex;
    flex-wrap: wrap;
    gap: 10px 18px;
  }

  strong {
    color: var(--draw-ink);
  }
}

.attendance-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
}

.attendance-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
}

.attendance-list {
  flex: 1;
  overflow: auto;
}

.attendance-group {
  padding: 10px 0;
  border-bottom: 1px dashed var(--draw-line);

  header {
    margin-bottom: 6px;
    color: var(--draw-muted);
    font-size: 11px;
    font-weight: 700;
  }
}

.attendance-person {
  display: flex;
  height: 34px;
  margin-right: 0;
}

.attendance-empty,
.attendance-note {
  color: var(--draw-muted);
  font-size: 11px;
  line-height: 1.6;
}

:fullscreen .draw-stage {
  box-sizing: border-box;
  width: 100vw;
  height: 100vh;
  padding: 3vh 3vw;
  overflow: auto;
  background: #f6faf8;
  border: 0;
  border-radius: 0;
}

@media (max-width: 1080px) {
  .draw-stage {
    grid-template-columns: 1fr;
  }

  .draw-head {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>