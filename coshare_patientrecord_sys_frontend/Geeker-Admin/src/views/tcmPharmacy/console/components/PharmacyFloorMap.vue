<template>
  <svg
    class="pfm-svg"
    :viewBox="`0 0 ${MAP_VIEWBOX.width} ${MAP_VIEWBOX.height}`"
    role="img"
    aria-label="中药房药架俯视图"
  >
    <rect class="pfm-board" x="8" y="8" :width="MAP_VIEWBOX.width - 16" :height="MAP_VIEWBOX.height - 16" rx="16" />

    <g v-for="fixture in fixtures" :key="fixture.key">
      <template v-if="fixture.type === 'aisle'">
        <rect
          class="pfm-aisle"
          :x="fixture.x"
          :y="fixture.y"
          :width="fixture.w"
          :height="fixture.h"
          rx="12"
        />
        <text class="pfm-aisle-label" :x="fixture.x + (fixture.w || 0) / 2" :y="fixture.y + (fixture.h || 0) / 2">
          {{ fixture.label }}
        </text>
      </template>
      <template v-else-if="fixture.type === 'entrance'">
        <path
          class="pfm-entrance-arrow"
          :d="`M ${fixture.x} ${fixture.y + 26} L ${fixture.x} ${fixture.y + 2} M ${fixture.x} ${fixture.y + 2} l -8 9 M ${fixture.x} ${fixture.y + 2} l 8 9`"
        />
        <text class="pfm-entrance-label" :x="fixture.x" :y="fixture.y + 44">{{ fixture.label }}</text>
      </template>
      <template v-else>
        <rect
          class="pfm-fixture"
          :class="`is-${fixture.type}`"
          :x="fixture.x"
          :y="fixture.y"
          :width="fixture.w"
          :height="fixture.h"
          rx="8"
        />
        <text
          class="pfm-fixture-label"
          :x="fixture.x + (fixture.w || 0) / 2"
          :y="fixture.y + (fixture.h || 0) / 2 + 4"
          text-anchor="middle"
        >
          {{ fixture.label }}
        </text>
        <text
          v-if="fixture.type === 'workbench'"
          class="pfm-fixture-sub"
          :x="fixture.x + (fixture.w || 0) / 2"
          :y="fixture.y + (fixture.h || 0) / 2 + 20"
          text-anchor="middle"
        >
          拣药 · 复核
        </text>
      </template>
    </g>

    <g
      v-for="shelf in shelves"
      :key="shelf.shelfNo"
      class="pfm-shelf"
      :class="{
        'is-selected': shelf.shelfNo === selectedShelfNo,
        'is-hit': hitCount(shelf.shelfNo) > 0
      }"
      role="button"
      tabindex="0"
      :aria-label="`${shelf.name}，已用 ${occupiedByShelf.get(shelf.shelfNo) || 0} 格，点击查看层格`"
      @click="emit('select', shelf.shelfNo)"
      @keydown.enter.prevent="emit('select', shelf.shelfNo)"
      @keydown.space.prevent="emit('select', shelf.shelfNo)"
    >
      <rect
        class="pfm-shelf-box"
        :class="`is-${shelf.orientation.toLowerCase()}`"
        :x="shelf.x"
        :y="shelf.y"
        :width="shelf.w"
        :height="shelf.h"
        rx="10"
      />
      <g v-if="shelf.orientation === 'V'" :transform="`translate(${shelf.x + shelf.w / 2}, ${shelf.y + shelf.h / 2}) rotate(-90)`">
        <text class="pfm-shelf-name" text-anchor="middle" dy="-4">{{ shelf.name }}</text>
        <text class="pfm-shelf-sub" text-anchor="middle" dy="14">
          已用 {{ occupiedByShelf.get(shelf.shelfNo) || 0 }}/{{ shelf.layerCount * shelf.columnCount }}
        </text>
      </g>
      <g v-else>
        <text class="pfm-shelf-name" :x="shelf.x + shelf.w / 2" :y="shelf.y + shelf.h / 2 - 4" text-anchor="middle">
          {{ shelf.name }}
        </text>
        <text class="pfm-shelf-sub" :x="shelf.x + shelf.w / 2" :y="shelf.y + shelf.h / 2 + 16" text-anchor="middle">
          已用 {{ occupiedByShelf.get(shelf.shelfNo) || 0 }}/{{ shelf.layerCount * shelf.columnCount }}
        </text>
      </g>
      <g v-if="hitCount(shelf.shelfNo)" :transform="`translate(${shelf.x + shelf.w - 14}, ${shelf.y + 14})`">
        <circle class="pfm-hit-circle" r="12" />
        <text class="pfm-hit-text" text-anchor="middle" dy="4">{{ hitCount(shelf.shelfNo) }}</text>
      </g>
      <title>{{ shelf.name }} · 点击查看 5 层 × 12 格</title>
    </g>
  </svg>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { TcmHerbShelf, TcmHerbSlot } from "@/api/modules/clinic/tcmPharmacyConsole";
import { MAP_FIXTURES, MAP_VIEWBOX } from "../constants/mapFixtures";

const props = defineProps<{
  shelves: TcmHerbShelf[];
  slots: TcmHerbSlot[];
  hitCounts: Map<number, number>;
  selectedShelfNo: number | null;
}>();

const emit = defineEmits<{
  (event: "select", shelfNo: number): void;
}>();

const fixtures = MAP_FIXTURES;

const occupiedByShelf = computed(() => {
  const counts = new Map<number, number>();
  for (const slot of props.slots) {
    if (slot.herbCode) counts.set(slot.shelfNo, (counts.get(slot.shelfNo) || 0) + 1);
  }
  return counts;
});

const hitCount = (shelfNo: number) => props.hitCounts.get(shelfNo) || 0;
</script>

<style scoped lang="scss">
.pfm-svg {
  display: block;
  width: 100%;
  height: auto;
  user-select: none;
}

.pfm-board {
  fill: #fffdf9;
  stroke: #e8dfd2;
  stroke-width: 1.5;
}

.pfm-aisle {
  fill: #fbf6ee;
  stroke: #d8cdbd;
  stroke-dasharray: 6 6;
}

.pfm-aisle-label {
  fill: #b3a696;
  font-size: 12px;
  text-anchor: middle;
}

.pfm-fixture {
  fill: #f2ece2;
  stroke: #cfc6b8;

  &.is-workbench {
    fill: #e9f1ed;
    stroke: #2f6b58;
  }
}

.pfm-fixture-label {
  fill: #2f2a24;
  font-size: 14px;
  font-weight: 600;
}

.pfm-fixture-sub {
  fill: #4d7a69;
  font-size: 10px;
}

.pfm-entrance-arrow {
  fill: none;
  stroke: #b7793f;
  stroke-width: 2.4;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.pfm-entrance-label {
  fill: #b7793f;
  font-size: 12px;
  font-weight: 600;
  text-anchor: middle;
}

.pfm-shelf {
  cursor: pointer;
  outline: none;

  &:focus-visible .pfm-shelf-box {
    stroke: #0f766e;
    stroke-width: 3;
  }
}

.pfm-shelf-box {
  fill: #f7ecdd;
  stroke: #d9a86c;
  stroke-width: 1.6;
  transition: fill 160ms ease, stroke 160ms ease;

  .pfm-shelf:hover & {
    fill: #f3e2cc;
  }

  .pfm-shelf.is-hit & {
    stroke: #b7793f;
    stroke-width: 3;
    stroke-dasharray: 8 5;
  }

  .pfm-shelf.is-selected & {
    fill: #e9f1ed;
    stroke: #2f6b58;
    stroke-width: 3;
    stroke-dasharray: none;
  }
}

.pfm-shelf-name {
  fill: #473a2c;
  font-size: 15px;
  font-weight: 700;
}

.pfm-shelf-sub {
  fill: #8a7a67;
  font-size: 11px;
}

.pfm-hit-circle {
  fill: #b7793f;
}

.pfm-hit-text {
  fill: #fffdf9;
  font-size: 10px;
  font-weight: 700;
}
</style>
