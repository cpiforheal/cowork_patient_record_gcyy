<script setup lang="ts">
import { motion } from "motion-v";

/** Inspira BlurFade 本地化：交错入场淡入（进入视口时触发一次）。 */
const props = withDefaults(
  defineProps<{
    delay?: number;
    duration?: number;
    y?: number;
    blur?: string;
  }>(),
  { delay: 0, duration: 0.5, y: 10, blur: "6px" }
);
</script>

<template>
  <motion.div
    :initial="{ opacity: 0, y: props.y, filter: `blur(${props.blur})` }"
    :while-in-view="{ opacity: 1, y: 0, filter: 'blur(0px)' }"
    :in-view-options="{ once: true, margin: '-24px' }"
    :transition="{ duration: props.duration, delay: props.delay, ease: [0.21, 0.47, 0.32, 0.98] }"
  >
    <slot />
  </motion.div>
</template>
