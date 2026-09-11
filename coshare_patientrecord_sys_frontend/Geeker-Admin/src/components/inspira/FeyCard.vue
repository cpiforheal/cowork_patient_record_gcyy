<template>
  <div ref="cardRef" class="fey-card" @mousemove="onMove" @mouseleave="onLeave">
    <!-- Fey 标志性点阵纹理底 -->
    <div class="fey-card__dots" aria-hidden="true"></div>
    <!-- 鼠标跟随聚光灯 -->
    <div class="fey-card__spotlight" :style="spotlightStyle" aria-hidden="true"></div>
    <div class="fey-card__content">
      <slot />
    </div>
    <slot name="overlay" />
  </div>
</template>

<script setup lang="ts">
// Fey Card（inspira 规范本地化）：点阵纹理 + 鼠标跟随聚光灯。
// 位置经 CSS 变量写入，不触发组件重渲染；主题色由使用方通过
// --fey-dot-color / --fey-spotlight 覆盖。
import { computed, ref } from "vue";

const cardRef = ref<HTMLElement | null>(null);
const mouseX = ref(0);
const mouseY = ref(0);
const active = ref(false);

const onMove = (event: MouseEvent) => {
  if (!cardRef.value) return;
  const rect = cardRef.value.getBoundingClientRect();
  mouseX.value = Math.round(event.clientX - rect.left);
  mouseY.value = Math.round(event.clientY - rect.top);
  active.value = true;
};
const onLeave = () => {
  active.value = false;
};

const spotlightStyle = computed(() => ({
  background: `radial-gradient(260px circle at ${mouseX.value}px ${mouseY.value}px, var(--fey-spotlight, rgb(59 130 246 / 12%)), transparent 70%)`,
  opacity: active.value ? 1 : 0
}));
</script>

<style scoped lang="scss">
.fey-card {
  position: relative;
  height: 100%;
  overflow: hidden;
  contain: paint;
}
.fey-card__dots {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(circle, var(--fey-dot-color, rgb(15 23 42 / 8%)) 1px, transparent 1.4px);
  background-size: 18px 18px;
  -webkit-mask-image: linear-gradient(to bottom, black 20%, transparent 92%);
  mask-image: linear-gradient(to bottom, black 20%, transparent 92%);
}
.fey-card__spotlight {
  position: absolute;
  inset: 0;
  opacity: 0;
  transition: opacity 0.3s ease;
}
.fey-card:hover .fey-card__spotlight {
  opacity: 1;
}
.fey-card__content {
  position: relative;
  z-index: 1;
  height: 100%;
}
</style>
