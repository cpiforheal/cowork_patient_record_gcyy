import { onBeforeUnmount, ref, watch, type Ref } from "vue";

/** 数字滚动：筛选变化时大数字平滑过渡到新值；系统声明减少动效时直接跟随。 */
export function useCountUp(source: () => number, duration = 520): Ref<number> {
  const display = ref(source());
  if (typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) {
    watch(source, value => (display.value = value));
    return display;
  }
  let frame = 0;
  watch(source, value => {
    cancelAnimationFrame(frame);
    const from = display.value;
    if (from === value) return;
    const start = performance.now();
    const tick = (now: number) => {
      const progress = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - progress, 3);
      display.value = Math.round(from + (value - from) * eased);
      if (progress < 1) frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
  });
  onBeforeUnmount(() => cancelAnimationFrame(frame));
  return display;
}
