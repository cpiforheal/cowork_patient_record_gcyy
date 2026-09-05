<template>
  <span class="inspira-shiny-text" :style="{ '--shiny-duration': `${duration}s` }">
    <slot />
  </span>
</template>

<script setup lang="ts">
/** Inspira AnimatedShinyText 本地化：周期扫过的微光文字（纯 CSS，主题色自适应）。 */
withDefaults(defineProps<{ duration?: number }>(), { duration: 3.2 });
</script>

<style scoped lang="scss">
.inspira-shiny-text {
  display: inline-block;
  background: linear-gradient(110deg, currentColor 40%, color-mix(in srgb, currentColor 30%, #ffffff) 50%, currentColor 60%);
  background-clip: text;
  background-size: 220% 100%;
  animation: inspira-shiny var(--shiny-duration, 3.2s) linear infinite;
}

@keyframes inspira-shiny {
  to {
    background-position: -220% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .inspira-shiny-text {
    animation: none;
  }
}
</style>
