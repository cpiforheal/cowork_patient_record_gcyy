<template>
  <div class="flip-card" :class="{ 'is-flipped': flipped, 'is-touch': coarsePointer }" @click="onToggle">
    <div class="flip-card-inner">
      <div class="flip-card-face flip-card-front"><slot name="front" /></div>
      <div class="flip-card-face flip-card-back"><slot name="back" /></div>
    </div>
  </div>
</template>

<script setup lang="ts">
// 翻转卡片（inspira 规范本地化）：桌面 hover 翻面，触屏点按翻面。
// 采用 2D 翻面过渡（不使用 preserve-3d/backface-visibility），
// 规避 Chrome 中 3D 变换逃逸 overflow 裁剪导致内容堆叠渲染的已知问题。
import { onMounted, ref } from "vue";

const flipped = ref(false);
const coarsePointer = ref(false);

onMounted(() => {
  coarsePointer.value = Boolean(window.matchMedia?.("(hover: none)").matches);
});

const onToggle = () => {
  if (coarsePointer.value) flipped.value = !flipped.value;
};
</script>

<style scoped lang="scss">
.flip-card {
  height: 100%;
  contain: paint;
  border-radius: inherit;
}
.flip-card-inner {
  position: relative;
  width: 100%;
  height: 100%;
}
.flip-card-face {
  position: absolute;
  inset: 0;
  overflow: hidden;
  transition:
    opacity 0.28s ease,
    transform 0.4s cubic-bezier(0.4, 0.2, 0.2, 1),
    visibility 0.28s ease;
}
.flip-card-front {
  visibility: visible;
  opacity: 1;
  transform: rotateY(0deg) scale(1);
}
.flip-card-back {
  visibility: hidden;
  opacity: 0;
  transform: rotateY(14deg) scale(0.96);
}
.flip-card:hover .flip-card-front,
.flip-card.is-flipped .flip-card-front {
  visibility: hidden;
  opacity: 0;
  transform: rotateY(-14deg) scale(0.96);
}
.flip-card:hover .flip-card-back,
.flip-card.is-flipped .flip-card-back {
  visibility: visible;
  opacity: 1;
  transform: rotateY(0deg) scale(1);
}

@media (prefers-reduced-motion: reduce) {
  .flip-card-face {
    transition: opacity 0.2s ease;
  }
}
</style>
