<template>
  <Motion
    as="div"
    class="apple-carousel-item"
    :initial="{ opacity: 0, y: 20 }"
    :animate="{ opacity: 1, y: 0, transition: { duration: 0.5, delay, ease: 'easeOut' } }"
  >
    <slot />
  </Motion>
</template>

<script setup lang="ts">
// inspira-ui AppleCarouselItem 1:1 本地化移植：逐张渐入上浮（延迟随序号递增，封顶防长列表久等）
import { computed } from "vue";
import { Motion } from "motion-v";

const props = withDefaults(
  defineProps<{
    index: number;
  }>(),
  { index: 0 }
);

const delay = computed(() => Math.min(props.index, 10) * 0.06);
</script>

<style scoped lang="scss">
.apple-carousel-item {
  flex: 0 0 auto;
  border-radius: 24px;
}
</style>
