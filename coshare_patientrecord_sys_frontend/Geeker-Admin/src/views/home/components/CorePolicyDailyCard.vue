<template>
  <button v-if="today" type="button" class="board-card core-policy-card" @click="libraryVisible = true">
    <span class="policy-card-head">
      <el-icon class="policy-card-icon"><Medal /></el-icon>
      <strong>质量安全每日一条</strong>
      <el-tag size="small" effect="plain" round>{{ today.code }} 号制度</el-tag>
      <small class="policy-streak"
        ><NumberTicker :value="today.streak" /> 天连续打卡</small
      >
      <transition name="policy-pop"
        ><span v-if="today.checked" class="policy-checked-badge"
          ><el-icon><Check /></el-icon>今日已打卡</span
        ></transition
      >
      <span class="fold-spacer"></span>
      <span class="policy-card-more"
        >全部 18 项制度 <el-icon><ArrowRight /></el-icon
      ></span>
    </span>
    <span class="policy-card-body">
      <i class="policy-card-no">{{ today.clauseNo }}</i>
      <span class="policy-card-text">
        <strong class="policy-card-title">{{ today.title }}</strong>
        <span class="policy-card-content">{{ today.content }}</span>
      </span>
    </span>
    <span class="policy-card-foot" @click.stop>
      <el-button
        size="small"
        :type="today.checked ? 'success' : 'primary'"
        :plain="today.checked"
        class="policy-check-btn"
        :class="{ 'is-done': today.checked }"
        @click="checkIn"
      >
        <el-icon v-if="today.checked" class="policy-check-icon"><Check /></el-icon>
        {{ today.checked ? "今日已记住" : "记住了" }}
      </el-button>
      <el-button size="small" type="warning" plain @click="openQuiz">
        <el-icon class="policy-quiz-icon"><EditPen /></el-icon>
        考我一下
      </el-button>
    </span>
  </button>
  <CorePolicyQuizDialog v-model="quizVisible" />
  <CorePolicyLibraryDialog v-model="libraryVisible" />
</template>

<script setup lang="ts" name="corePolicyDailyCard">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { ArrowRight, Check, EditPen, Medal } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import NumberTicker from "@/components/inspira/NumberTicker.vue";
import {
  getCorePolicyTodayApi,
  postCorePolicyCheckInApi,
  type CorePolicyToday
} from "@/api/modules/clinic/corePolicy";
import CorePolicyQuizDialog from "./CorePolicyQuizDialog.vue";
import CorePolicyLibraryDialog from "./CorePolicyLibraryDialog.vue";

// 数据失败时整卡静默隐藏（与医政早报卡同款容错），学习入口不打断工作台
const today = ref<CorePolicyToday>();
const quizVisible = ref(false);
const libraryVisible = ref(false);
let controller: AbortController | undefined;

const load = async () => {
  controller?.abort();
  controller = new AbortController();
  try {
    today.value = (await getCorePolicyTodayApi(controller.signal)).data;
  } catch (caught) {
    if (controller.signal.aborted) return;
    console.warn("[core-policy] 每日一条加载失败", caught);
  }
};
const checkIn = async () => {
  if (!today.value || today.value.checked) return;
  try {
    today.value = (await postCorePolicyCheckInApi()).data;
    ElMessage.success("已打卡，明天继续");
  } catch (caught) {
    ElMessage.warning((caught as Error).message || "打卡失败，请重试");
  }
};
const openQuiz = () => {
  quizVisible.value = true;
};
onMounted(load);
onBeforeUnmount(() => controller?.abort());
defineExpose({ load });
</script>

<style scoped lang="scss">
// 整卡可点：hover 抬升 + 边框着色 + 左侧编号色条加宽 + 箭头右移
.core-policy-card {
  position: relative;
  display: grid;
  gap: 10px;
  padding: 16px 18px;
  overflow: hidden;
  text-align: left;
  cursor: pointer;
  // 自带卡片壳，welcome/home 两处落地页均可直接复用
  background: var(--hos-chart-panel, var(--el-bg-color));
  border: 1px solid var(--hos-chart-line-soft, var(--el-border-color-light));
  border-radius: 12px;
  transition:
    border-color var(--motion-control, 180ms) var(--ease-out, ease),
    box-shadow var(--motion-control, 180ms) var(--ease-out, ease),
    transform var(--motion-control, 180ms) var(--ease-out, ease);

  &::before {
    position: absolute;
    top: 14px;
    bottom: 14px;
    left: 0;
    width: 3px;
    content: "";
    background: var(--el-color-primary);
    border-radius: 0 3px 3px 0;
    transition: width var(--motion-control, 180ms) var(--ease-out, ease);
  }

  @media (hover: hover) and (pointer: fine) {
    &:hover {
      border-color: color-mix(in srgb, var(--el-color-primary) 45%, var(--el-border-color-light));
      box-shadow: 0 12px 28px color-mix(in srgb, var(--el-color-primary) 14%, transparent);
      transform: translateY(-2px);

      &::before {
        width: 5px;
      }

      .policy-card-more {
        color: var(--el-color-primary);

        .el-icon {
          transform: translateX(3px);
        }
      }

      .policy-card-content {
        color: var(--el-text-color-primary);
      }
    }
  }

  &:active {
    transform: translateY(0);
  }
}

.policy-card-head {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 15px;
  color: var(--el-text-color-primary);

  .policy-card-icon {
    color: var(--el-color-primary);
  }

  .policy-streak {
    color: var(--el-text-color-secondary);
    font-variant-numeric: tabular-nums;
  }

  .policy-checked-badge {
    display: inline-flex;
    gap: 3px;
    align-items: center;
    padding: 1px 8px;
    font-size: 11px;
    color: var(--el-color-success);
    background: var(--el-color-success-light-9);
    border-radius: 999px;
  }

  .fold-spacer {
    flex: 1;
  }

  .policy-card-more {
    display: inline-flex;
    gap: 2px;
    align-items: center;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    transition: color var(--motion-control, 180ms) var(--ease-out, ease);

    .el-icon {
      transition: transform var(--motion-control, 180ms) var(--ease-out, ease);
    }
  }
}

.policy-card-body {
  display: flex;
  gap: 12px;
  align-items: flex-start;

  .policy-card-no {
    flex-shrink: 0;
    padding: 2px 9px;
    font-size: 15px;
    font-style: normal;
    font-weight: 800;
    color: var(--el-color-primary);
    background: color-mix(in srgb, var(--el-color-primary) 8%, transparent);
    border-radius: 8px;
    font-variant-numeric: tabular-nums;
  }

  .policy-card-title {
    display: block;
    margin-bottom: 3px;
    font-size: 14px;
  }

  .policy-card-content {
    display: -webkit-box;
    overflow: hidden;
    font-size: 13px;
    line-height: 1.6;
    color: var(--el-text-color-regular);
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    transition: color var(--motion-control, 180ms) var(--ease-out, ease);
  }
}

.policy-card-foot {
  display: flex;
  gap: 8px;

  // 已打卡：勾选图标 spring 弹出
  .policy-check-icon {
    display: inline-block;
    animation: policy-check-pop 0.42s cubic-bezier(0.34, 1.56, 0.64, 1);
  }

  .policy-quiz-icon {
    transition: transform var(--motion-control, 180ms) var(--ease-out, ease);
  }

  .policy-check-btn:hover .policy-check-icon,
  .policy-check-btn.is-done .policy-check-icon {
    transform: scale(1.12);
  }
}

@keyframes policy-check-pop {
  0% {
    transform: scale(0.4);
    opacity: 0;
  }

  100% {
    transform: scale(1);
    opacity: 1;
  }
}

// 打卡徽标进出场
.policy-pop-enter-active {
  transition:
    transform 0.32s cubic-bezier(0.34, 1.56, 0.64, 1),
    opacity 0.24s ease-out;
}

.policy-pop-leave-active {
  transition: opacity 0.16s ease-in;
}

.policy-pop-enter-from,
.policy-pop-leave-to {
  transform: scale(0.6);
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .core-policy-card,
  .core-policy-card::before,
  .policy-card-more .el-icon,
  .policy-check-icon {
    transition: none;
    animation: none;
  }
}
</style>
