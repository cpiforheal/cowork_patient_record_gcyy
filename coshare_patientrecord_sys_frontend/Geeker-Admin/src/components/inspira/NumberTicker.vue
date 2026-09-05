<script setup lang="ts">
import { onMounted, watch } from "vue";
import { ref } from "vue";
import { useSpring } from "motion-v";

/** Inspira NumberTicker 本地化：数字滚动动画（motion-v spring 驱动，暗色/主题色天然兼容）。 */
const props = withDefaults(
  defineProps<{
    value: number;
    delay?: number;
    decimalPlaces?: number;
  }>(),
  { value: 0, delay: 0, decimalPlaces: 0 }
);

const display = ref((0).toFixed(props.decimalPlaces));
const springValue = useSpring(0, { damping: 30, stiffness: 120 });

onMounted(() => {
  springValue.on("change", (latest: number) => {
    display.value = latest.toFixed(props.decimalPlaces);
  });
  window.setTimeout(() => springValue.set(props.value), props.delay * 1000);
});

watch(
  () => props.value,
  newValue => {
    springValue.set(newValue);
  }
);
</script>

<template>
  <span class="inline-block tabular-nums">{{ display }}</span>
</template>
