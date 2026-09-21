<template>
  <Teleport to="body">
    <AnimatePresence>
      <div v-if="open" class="apple-card-overlay">
        <Motion
          as="div"
          :initial="{ opacity: 0 }"
          :animate="{ opacity: 1 }"
          :exit="{ opacity: 0 }"
          class="apple-card-backdrop"
          @click="handleClose"
        />
        <Motion
          ref="containerRef"
          as="div"
          :initial="{ opacity: 0, scale: 0.96, y: 12 }"
          :animate="{ opacity: 1, scale: 1, y: 0, transition: { duration: 0.22, ease: 'easeOut' } }"
          :exit="{ opacity: 0, scale: 0.97, transition: { duration: 0.14 } }"
          class="apple-card-panel"
        >
          <button class="apple-card-close" aria-label="关闭" @click="handleClose">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">
              <path d="M5 5l14 14M19 5L5 19" />
            </svg>
          </button>
          <div class="apple-card-panel-category">{{ card.category }}</div>
          <div class="apple-card-panel-title">{{ card.title }}</div>
          <div class="apple-card-panel-body">
            <slot />
          </div>
        </Motion>
      </div>
    </AnimatePresence>
  </Teleport>

  <div class="apple-card" :style="gradient ? { background: gradient } : undefined" @click="handleOpen">
    <div class="apple-card-top">
      <div class="apple-card-category">
        <slot name="category">{{ card.category }}</slot>
      </div>
      <div class="apple-card-title">{{ card.title }}</div>
      <div class="apple-card-summary">
        <slot name="summary" />
      </div>
    </div>
    <div class="apple-card-bottom">
      <slot name="footer" />
    </div>
  </div>
</template>

<script setup lang="ts">
// inspira-ui AppleCard 本地化移植（轻色版）：
// 卡面贴系统浅色 UI（文字用系统文字色 + emoji 分级），点击 → 全屏毛玻璃遮罩 + 居中白卡二级弹层
// （category/大标题布局、右上吸顶关闭钮、ESC / 点击遮罩关闭、打开时锁定 body 滚动、
// 关闭后通知轮播滚回原卡片；打开时向父级派发 opened 以便懒加载详情）。
// 注意：刻意不使用 motion-v 的 layout-id 共享元素动画——关闭弹层时与轨道平滑滚动
// 同时发生会令布局投影反复重定向，产生"放大缩小循环"（已修复：改用纯透明度/缩放过场）。
import { inject, onUnmounted, ref, watch } from "vue";
import { AnimatePresence, Motion } from "motion-v";
import { onClickOutside } from "@vueuse/core";
import { CarouselKey } from "./AppleCarouselContext";

interface Card {
  src?: string;
  title: string;
  category: string;
}

const props = withDefaults(
  defineProps<{
    card: Card;
    index?: number;
    /** 卡面浅色渐变背景（按就诊类型区分），如 linear-gradient(...) */
    gradient?: string;
  }>(),
  { index: 0, gradient: "" }
);

const emit = defineEmits<{ (event: "opened"): void }>();

const open = ref(false);
const containerRef = ref<HTMLElement | null>(null);
const carouselContext = inject(CarouselKey);

if (!carouselContext) {
  throw new Error("AppleCard must be used within AppleCardCarousel");
}

const { onCardClose } = carouselContext;

function handleKeyDown(event: KeyboardEvent) {
  if (event.key === "Escape") {
    handleClose();
  }
}

window.addEventListener("keydown", handleKeyDown);
onUnmounted(() => {
  window.removeEventListener("keydown", handleKeyDown);
});

watch(open, newVal => {
  document.body.style.overflow = newVal ? "hidden" : "";
  if (newVal) emit("opened");
});

onClickOutside(containerRef, () => handleClose());

function handleOpen() {
  open.value = true;
}

function handleClose() {
  if (!open.value) return;
  open.value = false;
  onCardClose(props.index ?? 0);
}

defineExpose({ close: handleClose });
</script>

<style scoped lang="scss">
// ---------- 卡片本体（系统轻色调） ----------
.apple-card {
  position: relative;
  z-index: 10;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: space-between;
  width: 340px;
  height: 600px;
  overflow: hidden;
  cursor: pointer;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 24px;
  background: var(--el-color-primary-light-9);
  box-shadow: 0 10px 26px rgb(31 78 120 / 8%);
  transition:
    box-shadow 0.25s ease,
    transform 0.25s ease,
    border-color 0.25s ease;
  &:hover {
    transform: translateY(-4px);
    border-color: var(--el-color-primary-light-5);
    box-shadow: 0 18px 40px rgb(0 150 136 / 16%);
  }
}
.apple-card-top {
  position: relative;
  z-index: 2;
  width: 100%;
  padding: 24px 26px 0;
}
.apple-card-category {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-secondary);
}
.apple-card-title {
  max-width: 280px;
  margin-top: 8px;
  font-size: 26px;
  font-weight: 650;
  letter-spacing: 0.5px;
  color: var(--el-text-color-primary);
  text-wrap: balance;
}
.apple-card-summary {
  display: grid;
  gap: 10px;
  max-height: 360px;
  margin-top: 18px;
  font-size: 13.5px;
  text-align: left;
  // 信息扩容后允许卡面内部滚动兜底
  overflow-y: auto;
  scrollbar-width: none;
  &::-webkit-scrollbar {
    display: none;
  }
}
.apple-card-bottom {
  position: relative;
  z-index: 2;
  display: grid;
  gap: 8px;
  width: 100%;
  padding: 0 26px 20px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  :deep(.apple-card-enter) {
    display: inline-flex;
    gap: 3px;
    align-items: center;
    font-weight: 600;
    color: var(--el-color-primary);
  }
}

// ---------- 二级弹层 ----------
.apple-card-overlay {
  position: fixed;
  inset: 0;
  // 低于 Element Plus 弹窗层级（2000+），保证"进入档案维护"弹窗压在本弹层之上
  z-index: 1500;
  height: 100vh;
  overflow: auto;
}
.apple-card-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1;
  width: 100%;
  height: 100%;
  background: rgb(0 0 0 / 62%);
  backdrop-filter: blur(18px);
}
.apple-card-panel {
  position: relative;
  z-index: 2;
  // 用 100% 而非 100vw：Windows 滚动条占位时 100vw 会撑出横向溢出
  width: min(64rem, calc(100% - 48px));
  height: fit-content;
  padding: 16px;
  margin: 40px auto;
  overflow-x: hidden;
  border-radius: 24px;
  background: var(--el-bg-color);
  box-shadow: 0 30px 90px rgb(0 0 0 / 40%);
}
.apple-card-close {
  position: sticky;
  top: 16px;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  margin-left: auto;
  color: #f5f5f5;
  cursor: pointer;
  background: #0a0a0a;
  border: none;
  border-radius: 50%;
  transition: transform 0.15s ease;
  &:hover {
    transform: scale(1.08);
  }
}
.apple-card-panel-category {
  font-size: 15px;
  font-weight: 500;
  color: var(--el-text-color-secondary);
}
.apple-card-panel-title {
  margin-top: 12px;
  font-size: clamp(26px, 4.6vw, 42px);
  font-weight: 650;
  color: var(--el-text-color-primary);
}
.apple-card-panel-body {
  min-width: 0;
  padding: 26px 4px 12px;
  overflow-x: hidden;
}

@media (prefers-reduced-motion: reduce) {
  .apple-card,
  .apple-card-panel,
  .apple-card-backdrop {
    transition: none;
    animation: none;
  }
}
</style>
