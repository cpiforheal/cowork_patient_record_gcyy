<template>
  <el-dialog v-model="visible" width="560px" append-to-body destroy-on-close @open="onOpen">
    <template #header>
      <span class="quiz-title"><el-icon><EditPen /></el-icon>质量安全每月小测</span>
    </template>

    <div v-if="phase === 'loading'" v-loading="true" class="quiz-loading" element-loading-text="正在出题…" />

    <!-- 已测过当月：只读成绩 -->
    <div v-else-if="attempted" class="quiz-result">
      <p class="quiz-result-note">本月已完成小测（{{ submittedAt }}），成绩如下，下月再接再厉。</p>
      <div class="quiz-score">
        <strong><NumberTicker :value="score" /></strong>
        <span>/ {{ total }}</span>
      </div>
    </div>

    <!-- 答题态：单题推进 + 进度 -->
    <template v-else-if="phase === 'quiz' && questions.length">
      <div class="quiz-progress">
        <el-progress :percentage="progressPct" :show-text="false" :stroke-width="6" />
        <small>{{ index + 1 }} / {{ questions.length }}</small>
      </div>
      <transition name="quiz-slide" mode="out-in">
        <div :key="current.id" class="quiz-question">
          <p class="quiz-stem">{{ current.stem }}</p>
          <div class="quiz-options">
            <button
              v-for="(option, optionIndex) in current.options"
              :key="optionIndex"
              type="button"
              class="quiz-option"
              :class="{ selected: answers[current.id] === optionLetter(optionIndex) }"
              @click="choose(optionLetter(optionIndex))"
            >
              <i class="quiz-option-key">{{ optionLetter(optionIndex) }}</i>
              <span>{{ option }}</span>
            </button>
          </div>
        </div>
      </transition>
    </template>

    <!-- 结果态：得分滚动 + 错题解析 -->
    <div v-else-if="phase === 'result' && result" class="quiz-result">
      <div class="quiz-score" :class="{ 'is-full': result.score === result.total }">
        <strong><NumberTicker :value="result.score" /></strong>
        <span>/ {{ result.total }}</span>
      </div>
      <p class="quiz-result-note">
        {{ result.score === result.total ? "满分！制度要求掌握得很扎实。" : "错题解析如下，点击条款可回顾。" }}
      </p>
      <div v-if="wrongReview.length" class="quiz-review">
        <article v-for="item in wrongReview" :key="item.questionId" class="quiz-review-item">
          <p class="quiz-review-stem">{{ item.stem }}</p>
          <p class="quiz-review-answer">
            你的选择 <b :class="item.chosen ? 'is-wrong' : ''">{{ item.chosen || "未作答" }}</b>
            <span class="quiz-review-arrow">→</span>
            正确答案 <b class="is-right">{{ item.answer }}</b>
          </p>
          <p v-if="item.explanation" class="quiz-review-explain">{{ item.explanation }}</p>
        </article>
      </div>
    </div>

    <template #footer>
      <div v-if="phase === 'quiz'" class="quiz-footer">
        <el-button :disabled="index === 0" @click="index--">上一题</el-button>
        <el-button v-if="index < questions.length - 1" type="primary" @click="index++">下一题</el-button>
        <el-button v-else type="primary" :loading="submitting" :disabled="answeredCount < questions.length" @click="submit">
          交卷
        </el-button>
      </div>
      <el-button v-else @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts" name="corePolicyQuizDialog">
import { computed, ref } from "vue";
import { EditPen } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import NumberTicker from "@/components/inspira/NumberTicker.vue";
import {
  getCorePolicyQuizCurrentApi,
  submitCorePolicyQuizApi,
  type CorePolicyQuestion,
  type CorePolicyQuizResult
} from "@/api/modules/clinic/corePolicy";

const visible = defineModel<boolean>({ default: false });
const phase = ref<"loading" | "quiz" | "result">("loading");
const submitting = ref(false);
const attempted = ref(false);
const score = ref(0);
const total = ref(0);
const submittedAt = ref("");
const questions = ref<CorePolicyQuestion[]>([]);
const answers = ref<Record<string, string>>({});
const index = ref(0);
const result = ref<CorePolicyQuizResult>();

const current = computed(() => questions.value[index.value]);
const progressPct = computed(() => Math.round(((index.value + 1) / Math.max(1, questions.value.length)) * 100));
const answeredCount = computed(() => questions.value.filter(question => answers.value[question.id]).length);
const wrongReview = computed(() => (result.value?.review || []).filter(item => !item.correct));
const optionLetter = (index: number) => String.fromCharCode(65 + index);

const onOpen = async () => {
  phase.value = "loading";
  questions.value = [];
  answers.value = {};
  index.value = 0;
  result.value = undefined;
  attempted.value = false;
  try {
    const { data: current } = await getCorePolicyQuizCurrentApi();
    if (current.attempted) {
      attempted.value = true;
      score.value = current.score || 0;
      total.value = current.total || 10;
      submittedAt.value = current.submittedAt || "";
      phase.value = "result";
      return;
    }
    questions.value = current.questions || [];
    phase.value = "quiz";
  } catch (caught) {
    ElMessage.warning((caught as Error).message || "小测题目加载失败");
    visible.value = false;
  }
};
const choose = (letter: string) => {
  if (!current.value) return;
  answers.value = { ...answers.value, [current.value.id]: letter };
};
const submit = async () => {
  submitting.value = true;
  try {
    const { data } = await submitCorePolicyQuizApi(answers.value);
    result.value = data;
    score.value = data.score;
    total.value = data.total;
    attempted.value = false;
    phase.value = "result";
  } catch (caught) {
    ElMessage.warning((caught as Error).message || "提交失败，请重试");
    phase.value = "quiz";
  } finally {
    submitting.value = false;
  }
};
</script>

<style scoped lang="scss">
.quiz-title {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  font-size: 16px;
  font-weight: 600;

  .el-icon {
    color: var(--el-color-primary);
  }
}

.quiz-loading {
  min-height: 220px;
}

.quiz-progress {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;

  .el-progress {
    flex: 1;
  }

  small {
    flex-shrink: 0;
    color: var(--el-text-color-secondary);
    font-variant-numeric: tabular-nums;
  }
}

.quiz-stem {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.6;
}

.quiz-options {
  display: grid;
  gap: 10px;
}

// 选项卡：hover 底色渐染 + 左侧指示条滑入 + 按压缩放，选中弹性标出
.quiz-option {
  position: relative;
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 11px 14px;
  text-align: left;
  font: inherit;
  color: var(--el-text-color-regular);
  cursor: pointer;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  transition:
    background-color var(--motion-control, 180ms) var(--ease-out, ease),
    border-color var(--motion-control, 180ms) var(--ease-out, ease),
    transform var(--motion-fast, 140ms) var(--ease-out, ease);

  &::before {
    position: absolute;
    top: 10px;
    bottom: 10px;
    left: 0;
    width: 3px;
    content: "";
    background: var(--el-color-primary);
    border-radius: 0 3px 3px 0;
    transform: scaleY(0);
    transition: transform var(--motion-control, 180ms) var(--ease-out, ease);
  }

  @media (hover: hover) and (pointer: fine) {
    &:hover {
      background: color-mix(in srgb, var(--el-color-primary) 6%, var(--el-bg-color));
      border-color: color-mix(in srgb, var(--el-color-primary) 30%, var(--el-border-color-light));

      &::before {
        transform: scaleY(1);
      }
    }
  }

  &:active {
    transform: scale(0.98);
  }

  &.selected {
    background: color-mix(in srgb, var(--el-color-primary) 10%, var(--el-bg-color));
    border-color: var(--el-color-primary);

    &::before {
      transform: scaleY(1);
    }

    .quiz-option-key {
      color: #fff;
      background: var(--el-color-primary);
      border-color: var(--el-color-primary);
    }
  }

  .quiz-option-key {
    display: grid;
    flex-shrink: 0;
    place-items: center;
    width: 24px;
    height: 24px;
    font-size: 12px;
    font-weight: 700;
    font-style: normal;
    color: var(--el-text-color-secondary);
    border: 1px solid var(--el-border-color);
    border-radius: 50%;
    transition:
      background-color var(--motion-control, 180ms) var(--ease-out, ease),
      color var(--motion-control, 180ms) var(--ease-out, ease),
      transform 0.32s cubic-bezier(0.34, 1.56, 0.64, 1);
  }

  &.selected .quiz-option-key {
    transform: scale(1.1);
  }
}

// 单题切换滑入滑出
.quiz-slide-enter-active {
  transition:
    transform 0.24s var(--ease-out, ease),
    opacity 0.2s ease-out;
}

.quiz-slide-leave-active {
  transition:
    transform 0.16s ease-in,
    opacity 0.14s ease-in;
}

.quiz-slide-enter-from {
  transform: translateX(18px);
  opacity: 0;
}

.quiz-slide-leave-to {
  transform: translateX(-14px);
  opacity: 0;
}

.quiz-score {
  display: flex;
  gap: 6px;
  align-items: baseline;
  justify-content: center;
  padding: 10px 0 2px;

  strong {
    font-size: 52px;
    font-weight: 800;
    color: var(--el-color-primary);
    font-variant-numeric: tabular-nums;
    line-height: 1;
  }

  span {
    color: var(--el-text-color-secondary);
  }

  &.is-full strong {
    color: var(--el-color-success);
  }
}

.quiz-result-note {
  margin: 4px 0 14px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  text-align: center;
}

.quiz-review {
  display: grid;
  gap: 10px;
  max-height: 280px;
  overflow: auto;
}

.quiz-review-item {
  padding: 10px 12px;
  background: var(--el-fill-color-lighter);
  border-radius: 10px;

  .quiz-review-stem {
    margin: 0 0 6px;
    font-size: 13px;
    font-weight: 600;
    line-height: 1.5;
  }

  .quiz-review-answer {
    display: flex;
    gap: 6px;
    align-items: center;
    margin: 0 0 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);

    b {
      font-variant-numeric: tabular-nums;
    }

    .is-wrong {
      color: var(--el-color-danger);
    }

    .is-right {
      color: var(--el-color-success);
    }
  }

  .quiz-review-explain {
    margin: 0;
    font-size: 12px;
    line-height: 1.6;
    color: var(--el-text-color-secondary);
  }
}

.quiz-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (prefers-reduced-motion: reduce) {
  .quiz-option,
  .quiz-option::before,
  .quiz-option .quiz-option-key,
  .quiz-slide-enter-active,
  .quiz-slide-leave-active {
    transition: none;
  }
}
</style>
