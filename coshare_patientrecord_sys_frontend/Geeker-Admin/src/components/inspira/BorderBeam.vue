<script setup lang="ts">
import { computed } from "vue";

/** Inspira BorderBeam 本地化：沿边框旋转的流光（纯 CSS conic + mask，自动继承容器圆角）。 */
const props = withDefaults(
  defineProps<{
    duration?: number;
    colorFrom?: string;
    colorTo?: string;
    reverse?: boolean;
    width?: number;
  }>(),
  { duration: 6, colorFrom: "", colorTo: "", reverse: false, width: 1.5 }
);

const beamStyle = computed(() => ({
  "--beam-duration": `${props.duration}s`,
  "--beam-direction": props.reverse ? "reverse" : "normal",
  "--beam-width": `${props.width}px`,
  "--beam-from": props.colorFrom || "var(--el-color-primary-light-3)",
  "--beam-to": props.colorTo || "var(--el-color-primary)"
}));
</script>

<template>
  <div class="inspira-border-beam" :style="beamStyle" aria-hidden="true">
    <div class="beam"></div>
  </div>
</template>

<style scoped lang="scss">
.inspira-border-beam {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
  border-radius: inherit;
  &::before {
    position: absolute;
    inset: 0;
    padding: var(--beam-width, 1.5px);
    content: "";
    background: var(--el-border-color-lighter);
    border-radius: inherit;
    mask:
      linear-gradient(#ffffff 0 0) content-box,
      linear-gradient(#ffffff 0 0);
    mask-composite: xor;
    mask-composite: exclude;
  }
  .beam {
    position: absolute;
    inset: -75%;
    background: conic-gradient(from 0deg, transparent 0deg 328deg, var(--beam-from) 348deg, var(--beam-to) 360deg);
    animation: inspira-beam-spin var(--beam-duration, 6s) linear infinite;
    animation-direction: var(--beam-direction, normal);
  }
}

@keyframes inspira-beam-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .beam {
    animation: none;
  }
}
</style>
