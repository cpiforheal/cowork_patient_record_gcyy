<template>
  <div class="apple-carousel">
    <!-- 固定在轨道上方右侧的翻页箭头（进入页面即见，无需滚动） -->
    <div class="apple-carousel-nav">
      <button class="apple-carousel-btn" :disabled="!canScrollLeft" aria-label="向左翻页" @click="scrollPrev">
        <el-icon :size="22"><ArrowLeft /></el-icon>
      </button>
      <button class="apple-carousel-btn" :disabled="!canScrollRight" aria-label="向右翻页" @click="scrollNext">
        <el-icon :size="22"><ArrowRight /></el-icon>
      </button>
    </div>
    <div ref="carouselRef" class="apple-carousel-track" @scroll="checkScrollability">
      <div class="apple-carousel-inner">
        <slot />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
// inspira-ui AppleCardCarousel 本地化移植：
// 横向滚动轨道（隐藏滚动条）+ 固定在下方右侧的逐卡翻页箭头（不可滚动方向禁用置灰）；
// 卡片关闭时平滑滚回该卡片位置。
import { onBeforeUnmount, onMounted, provide, ref, watch } from "vue";
import { ArrowLeft, ArrowRight } from "@element-plus/icons-vue";
import { CarouselKey } from "./AppleCarouselContext";

const props = withDefaults(
  defineProps<{
    initialScroll?: number;
  }>(),
  { initialScroll: 0 }
);

const carouselRef = ref<HTMLDivElement | null>(null);
const canScrollLeft = ref(false);
const canScrollRight = ref(true);
const currentIndex = ref(0);
let contentObserver: ResizeObserver | null = null;

onMounted(() => {
  if (carouselRef.value) {
    carouselRef.value.scrollLeft = props.initialScroll;
    checkScrollability();
    // 卡片数据异步加载后会改变内容宽度，需重新检测可滚动方向
    const inner = carouselRef.value.querySelector(".apple-carousel-inner");
    if (inner && "ResizeObserver" in window) {
      contentObserver = new ResizeObserver(() => checkScrollability());
      contentObserver.observe(inner);
    }
  }
});

onBeforeUnmount(() => {
  contentObserver?.disconnect();
  contentObserver = null;
});

watch(
  () => props.initialScroll,
  newVal => {
    if (carouselRef.value) {
      carouselRef.value.scrollLeft = newVal;
      checkScrollability();
    }
  }
);

function checkScrollability() {
  if (carouselRef.value) {
    const { scrollLeft, scrollWidth, clientWidth } = carouselRef.value;
    canScrollLeft.value = scrollLeft > 0;
    canScrollRight.value = scrollLeft < scrollWidth - clientWidth - 1;
  }
}

function scrollByCard(direction: 1 | -1) {
  const track = carouselRef.value;
  if (!track) return;
  // 逐卡步进：以相邻卡片实测间距为步长，滚动到上一份/下一份病历卡
  const cards = Array.from(track.querySelectorAll<HTMLElement>(".apple-carousel-item"));
  if (!cards.length) return;
  const currentLeft = track.scrollLeft;
  const positions = cards.map(card => Math.max(card.offsetLeft - 16, 0));
  const target =
    direction === 1
      ? positions.find(left => left > currentLeft + 4)
      : [...positions].reverse().find(left => left < currentLeft - 4);
  track.scrollTo({ left: target ?? 0, behavior: "smooth" });
}

function scrollPrev() {
  scrollByCard(-1);
}

function scrollNext() {
  scrollByCard(1);
}

function handleCardClose(index: number) {
  if (!carouselRef.value) return;
  // 与原版一致：关闭弹层后滚动定位回所点击的卡片（元素实测位置，比固定宽度更稳）
  const target = carouselRef.value.querySelectorAll<HTMLElement>(".apple-carousel-item")[index];
  if (target) {
    carouselRef.value.scrollTo({ left: Math.max(target.offsetLeft - 16, 0), behavior: "smooth" });
  }
  currentIndex.value = index;
}

provide(CarouselKey, {
  onCardClose: handleCardClose,
  currentIndex
});
</script>

<style scoped lang="scss">
.apple-carousel {
  position: relative;
  width: 100%;
}
.apple-carousel-track {
  display: flex;
  width: 100%;
  padding: 28px 0 20px;
  overflow-x: scroll;
  overscroll-behavior-x: auto;
  scroll-behavior: smooth;
  scrollbar-width: none;
  &::-webkit-scrollbar {
    display: none;
  }
}
.apple-carousel-inner {
  display: flex;
  flex: 1 0 auto;
  flex-direction: row;
  gap: 16px;
  justify-content: flex-start;
  padding: 0 24px 0 16px;
  margin: 0 auto;
}
.apple-carousel-nav {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  padding: 0 24px 6px 0;
}
.apple-carousel-btn {
  position: relative;
  z-index: 40;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  color: #fff;
  cursor: pointer;
  background: var(--el-color-primary);
  border: none;
  border-radius: 50%;
  box-shadow: 0 6px 16px rgb(0 150 136 / 30%);
  transition:
    box-shadow 0.2s ease,
    opacity 0.2s ease,
    transform 0.2s ease;
  &:hover:not(:disabled) {
    box-shadow: 0 10px 24px rgb(0 150 136 / 40%);
    transform: scale(1.06);
  }
  // 禁用态仍保持可见，仅降透明度
  &:disabled {
    opacity: 0.35;
    cursor: default;
  }
}
</style>
