<template>
  <div class="animate-grid">
    <div
      class="ag-transform"
      :style="{
        transform: `perspective(${perspective}px) rotateX(${rotateX}deg) rotateY(${rotateY}deg)`
      }"
    >
      <div
        v-for="(item, index) in cards"
        :key="index"
        :ref="el => setCardRef(el, index)"
        class="card"
        :style="{ zIndex: index + 1 }"
      >
        <slot name="logo" :logo="item.logo" :index="index" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
// inspira-ui AnimateGrid 1:1 本地化移植：
// 整体 perspective/rotate 倾斜，hover 卡片放大上浮 + 绿色辉光呼吸，相邻卡片（左右/上下各一）同步小幅上浮。
// 原实现：https://inspira-ui.com/docs/en/components/miscellaneous/animate-grid
import { onMounted, ref, watch } from "vue";
import { useDebounceFn, useMouseInElement } from "@vueuse/core";

interface Cards {
  logo: string;
}

const props = withDefaults(
  defineProps<{
    cards: Cards[];
    textGlowStartColor?: string;
    textGlowEndColor?: string;
    perspective?: number;
    rotateX?: number;
    rotateY?: number;
  }>(),
  {
    textGlowStartColor: "#38ef7d80",
    textGlowEndColor: "#38ef7d",
    perspective: 600,
    rotateX: -1,
    rotateY: -15
  }
);

const card = ref<HTMLElement[]>([]);

const setCardRef = (el: unknown, index: number) => {
  if (el instanceof HTMLElement) card.value[index] = el;
};

function adjacentCardItems(i: number): HTMLElement[] {
  const total = props.cards.length;
  const columns = total < 4 ? total : 4;
  return [i - 1, i + 1, i - columns, i + columns]
    .filter(index => {
      if (index < 0 || index > total - 1) return false;
      if (i % columns === 0 && index === i - 1) return false;
      if (i % columns === columns - 1 && index === i + 1) return false;
      return true;
    })
    .map(index => card.value?.[index]) as HTMLElement[];
}

function removeCardClasses(el: HTMLElement, adjacentCards: HTMLElement[]) {
  el.classList.remove("card-raised-big");
  adjacentCards.forEach(adjacentCard => {
    adjacentCard?.classList.remove("card-raised-small");
  });
}

onMounted(() => {
  const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduced) return;
  card.value.forEach((el, i) => {
    if (!el) return;
    const { isOutside } = useMouseInElement(el);
    const adjacentCards = adjacentCardItems(i);
    const removeClasses = useDebounceFn(() => removeCardClasses(el, adjacentCards), 200);
    watch(
      isOutside,
      isOut => {
        if (!isOut) {
          el.classList.add("card-raised-big");
          adjacentCards.forEach(adjacentCard => {
            adjacentCard?.classList.add("card-raised-small");
          });
        } else {
          removeClasses();
        }
      },
      { flush: "post" }
    );
  });
});
</script>

<style scoped lang="scss">
.animate-grid {
  position: relative;
  display: block;
  width: 100%;
}
.ag-transform {
  position: relative;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  width: 100%;
  align-items: center;
  justify-content: center;
}
.card {
  position: relative;
  display: block;
  padding: 20px 12px;
  border: 1px solid transparent;
  border-radius: 6px;
  transition: all 0.2s ease;
  box-shadow:
    2px 2px 5px rgb(31 41 55 / 20%),
    3px 3px 10px rgb(31 41 55 / 20%),
    6px 6px 20px rgb(31 41 55 / 10%);
}
.card :deep(svg),
.card :deep(img) {
  display: block;
  max-width: 100%;
  max-height: 100%;
  margin: 0 auto;
  opacity: 0.7;
  transition: 0.2s;
}
.card:hover {
  box-shadow:
    3px 3px 5px rgb(31 41 55 / 100%),
    5px 5px 10px rgb(31 41 55 / 100%),
    10px 10px 20px rgb(31 41 55 / 100%);
}
.card:hover :deep(svg),
.card:hover :deep(img) {
  opacity: 1;
}
.card-raised-small {
  border: 1px solid rgb(217 251 232 / 20%);
  transform: scale(1.05) translate(-5px, -5px) translateZ(0);
  animation: ag-text-glow-small 1.5s ease-in-out infinite alternate;
}
.card-raised-big {
  border: 1px solid rgb(217 251 232 / 30%);
  background-color: #020420;
  transform: scale(1.15) translate(-20px, -20px) translateZ(15px);
  animation: ag-text-glow 1.5s ease-in-out infinite alternate;
}

@keyframes ag-text-glow {
  0% {
    filter: drop-shadow(0 0 2px v-bind("props.textGlowStartColor"));
  }
  100% {
    filter: drop-shadow(0 1px 8px v-bind("props.textGlowEndColor"));
  }
}
@keyframes ag-text-glow-small {
  0% {
    filter: drop-shadow(0 0 2px rgb(56 239 125 / 10%));
  }
  100% {
    filter: drop-shadow(0 1px 4px rgb(56 239 125 / 50%));
  }
}

@media (prefers-reduced-motion: reduce) {
  .card,
  .card-raised-big,
  .card-raised-small {
    animation: none;
    transform: none;
  }
}
</style>
