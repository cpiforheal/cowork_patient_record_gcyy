<template>
  <svg class="animated-grid" :width="width" :height="height" aria-hidden="true">
    <defs>
      <pattern :id="patternId" :width="size" :height="size" patternUnits="userSpaceOnUse">
        <path :d="`M ${size} 0 L 0 0 0 ${size}`" fill="none" stroke="currentColor" stroke-width="1" />
      </pattern>
    </defs>
    <rect width="100%" height="100%" :fill="`url(#${patternId})`" />
    <rect
      v-for="sq in squares"
      :key="sq.key"
      :x="sq.x"
      :y="sq.y"
      :width="size"
      :height="size"
      fill="currentColor"
      class="grid-square"
      :style="{ animationDelay: sq.delay + 's' }"
    />
  </svg>
</template>

<script setup lang="ts">
// 动态网格底纹（inspira 规范本地化）：网格线 + 方块按序渐显的扫掠动效。
// 颜色跟随父级 color 属性（自动适配明暗主题），纯 SVG+CSS 实现，无额外依赖。
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    /** 网格单元尺寸 px */
    size?: number;
    /** 参与渐显动效的方块数 */
    numSquares?: number;
    width?: string | number;
    height?: string | number;
  }>(),
  { size: 44, numSquares: 36, width: "100%", height: "100%" }
);

const patternId = `animated-grid-${Math.round(Math.random() * 1e6)}`;

// 稳定伪随机序列：方块分布固定，避免每次渲染闪烁
const seeded = (seedValue: number) => () => {
  seedValue = (seedValue * 1664525 + 1013904223) % 4294967296;
  return seedValue / 4294967296;
};

const squares = computed(() => {
  const rand = seeded(20260909);
  const list: { key: string; x: number; y: number; delay: number }[] = [];
  const seen = new Set<string>();
  while (list.length < props.numSquares) {
    const x = Math.floor(rand() * 32) * props.size;
    const y = Math.floor(rand() * 16) * props.size;
    const key = `${x}-${y}`;
    if (seen.has(key)) continue;
    seen.add(key);
    list.push({ key: `${key}-${list.length}`, x, y, delay: Math.round(rand() * 60) / 10 });
    if (list.length > 200) break;
  }
  return list;
});
</script>

<style scoped lang="scss">
.animated-grid {
  display: block;
  color: var(--hap-grid-color, var(--el-color-primary));
}
.grid-square {
  opacity: 0;
  fill: currentColor;
  animation: animated-grid-square 6s linear infinite;
}

@keyframes animated-grid-square {
  0% {
    opacity: 0;
  }
  12% {
    opacity: 0.16;
  }
  55% {
    opacity: 0.05;
  }
  100% {
    opacity: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .grid-square {
    opacity: 0.06;
    animation: none;
  }
}
</style>
