<template>
  <div class="flip-card" :class="{ 'is-flipped': flipped, 'is-touch': coarsePointer }" @click="onToggle">
    <div class="flip-card-inner">
      <div class="flip-card-face flip-card-front"><slot name="front" /></div>
      <div class="flip-card-face flip-card-back"><slot name="back" /></div>
    </div>
  </div>
</template>

<script setup lang="ts">
// 翻转卡片（inspira 规范本地化）：桌面 hover 翻面，触屏点按翻面；reduced-motion 时无旋转动画直接切换
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
  perspective: 1600px;
}
.flip-card-inner {
  position: relative;
  width: 100%;
  height: 100%;
  transform-style: preserve-3d;
  transition: transform 0.6s cubic-bezier(0.4, 0.2, 0.2, 1);
}
.flip-card:hover .flip-card-inner,
.flip-card.is-flipped .flip-card-inner {
  transform: rotateY(180deg);
}
.flip-card-face {
  position: absolute;
  inset: 0;
  overflow: hidden;
  -webkit-backface-visibility: hidden;
  backface-visibility: hidden;
}
.flip-card-back {
  transform: rotateY(180deg);
}

@media (prefers-reduced-motion: reduce) {
  .flip-card-inner {
    transition: none;
  }
}
</style>
