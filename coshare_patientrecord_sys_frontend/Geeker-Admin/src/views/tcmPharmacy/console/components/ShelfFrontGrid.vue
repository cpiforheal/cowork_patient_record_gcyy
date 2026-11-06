<template>
  <div class="sfg" :class="{ 'is-editing': editMode }">
    <div class="sfg-grid">
      <div class="sfg-corner">层 / 格</div>
      <div v-for="column in columns" :key="`head-${column}`" class="sfg-col-head">{{ column }}</div>

      <template v-for="layer in layers" :key="`layer-${layer}`">
        <div class="sfg-row-head">{{ layer }}层</div>
        <button
          v-for="column in columns"
          :key="`${layer}-${column}`"
          type="button"
          class="sfg-cell"
          :class="cellClass(slotAt(layer, column))"
          :draggable="editMode && Boolean(slotAt(layer, column)?.herbCode)"
          :title="cellTitle(slotAt(layer, column))"
          :aria-label="cellTitle(slotAt(layer, column))"
          @click="onSelect(slotAt(layer, column))"
          @dblclick="onEdit(slotAt(layer, column))"
          @keydown.enter.prevent="onEnter(slotAt(layer, column))"
          @keydown.space.prevent="onSelect(slotAt(layer, column))"
          @dragstart="onDragStart($event, slotAt(layer, column))"
          @dragover="onDragOver"
          @drop="onDrop($event, slotAt(layer, column))"
          @dragend="draggingKey = ''"
        >
          <template v-if="slotAt(layer, column)?.herbCode">
            <span class="sfg-name">{{ slotAt(layer, column)?.name }}</span>
            <span class="sfg-stock" :class="`is-${stockState(slotAt(layer, column))}`">
              {{ stockText(slotAt(layer, column)) }}
            </span>
          </template>
          <span v-else class="sfg-empty">{{ editMode ? "+ 放入" : "空" }}</span>
        </button>
      </template>
    </div>
    <p class="sfg-hint">
      <template v-if="editMode">拖拽换格；单击选中后点目标格也可交换；双击编辑药品。</template>
      <template v-else>单击格位查看药品；双击也可打开详情。</template>
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import type { TcmHerbShelf, TcmHerbSlot } from "@/api/modules/clinic/tcmPharmacyConsole";
import { slotKeyOf } from "../composables/usePharmacyConsole";

const props = defineProps<{
  shelf: TcmHerbShelf;
  slots: TcmHerbSlot[];
  editMode: boolean;
  focusKey: string;
  highlightCodes: Set<string>;
}>();

const emit = defineEmits<{
  (event: "select", slot: TcmHerbSlot): void;
  (event: "edit", slot: TcmHerbSlot): void;
  (event: "move", payload: { from: string; to: string }): void;
}>();

const layers = computed(() => Array.from({ length: props.shelf.layerCount }, (_, index) => index + 1));
const columns = computed(() => Array.from({ length: props.shelf.columnCount }, (_, index) => index + 1));

const slotMap = computed(() => {
  const map = new Map<string, TcmHerbSlot>();
  for (const slot of props.slots) map.set(slotKeyOf(slot), slot);
  return map;
});

const slotAt = (layer: number, column: number) =>
  slotMap.value.get(`${props.shelf.shelfNo}-${layer}-${column}`) || null;

const stockState = (slot: TcmHerbSlot | null) => {
  if (!slot?.herbCode) return "empty";
  if (Number(slot.stock || 0) <= 0) return "out";
  if (Number(slot.stock || 0) <= Number(slot.warnThreshold || 0)) return "low";
  return "ok";
};

const stockText = (slot: TcmHerbSlot | null) => {
  const state = stockState(slot);
  if (state === "out") return "缺货";
  if (state === "low") return `存 ${formatNumber(slot?.stock)}`;
  return `存 ${formatNumber(slot?.stock)}`;
};

const formatNumber = (value?: number | null) => {
  const number = Number(value || 0);
  return Number.isInteger(number) ? String(number) : number.toFixed(1);
};

const cellClass = (slot: TcmHerbSlot | null) => ({
  [`is-${stockState(slot)}`]: true,
  "is-selected": Boolean(slot && slotKeyOf(slot) === props.focusKey),
  "is-hit": Boolean(slot?.herbCode && props.highlightCodes.has(slot.herbCode)),
  "is-flash": Boolean(slot && slotKeyOf(slot) === flashKey.value),
  "is-dragging": Boolean(slot && slotKeyOf(slot) === draggingKey.value)
});

const cellTitle = (slot: TcmHerbSlot | null) => {
  if (!slot?.herbCode) return `${props.shelf.name} ${slot?.layerNo || ""}层 ${slot?.columnNo || ""}格：空`;
  const state = stockState(slot);
  const status = state === "out" ? "缺货" : state === "low" ? "低库存" : "正常";
  return `${slot.name} · ${slot.layerNo}层 ${slot.columnNo}格 · 库存 ${formatNumber(slot.stock)} · ${status}`;
};

const draggingKey = ref("");
const flashKey = ref("");
let flashTimer: ReturnType<typeof setTimeout> | undefined;
let clickTimer: ReturnType<typeof setTimeout> | undefined;

watch(
  () => props.focusKey,
  key => {
    if (!key) return;
    flashKey.value = key;
    clearTimeout(flashTimer);
    flashTimer = setTimeout(() => (flashKey.value = ""), 1400);
  }
);

onBeforeUnmount(() => {
  clearTimeout(flashTimer);
  clearTimeout(clickTimer);
});

const onSelect = (slot: TcmHerbSlot | null) => {
  if (!slot) return;
  clearTimeout(clickTimer);
  clickTimer = setTimeout(() => emit("select", slot), 220);
};

const onEdit = (slot: TcmHerbSlot | null) => {
  clearTimeout(clickTimer);
  if (props.editMode && slot) emit("edit", slot);
};

const onEnter = (slot: TcmHerbSlot | null) => {
  if (!slot) return;
  if (props.editMode) emit("edit", slot);
  else emit("select", slot);
};

const onDragStart = (event: DragEvent, slot: TcmHerbSlot | null) => {
  if (!props.editMode || !slot?.herbCode) {
    event.preventDefault();
    return;
  }
  draggingKey.value = slotKeyOf(slot);
  event.dataTransfer?.setData("text/plain", draggingKey.value);
  if (event.dataTransfer) event.dataTransfer.effectAllowed = "move";
};

const onDragOver = (event: DragEvent) => {
  if (props.editMode) event.preventDefault();
};

const onDrop = (event: DragEvent, slot: TcmHerbSlot | null) => {
  if (!props.editMode) return;
  event.preventDefault();
  const from = draggingKey.value || event.dataTransfer?.getData("text/plain") || "";
  const to = slot ? slotKeyOf(slot) : "";
  draggingKey.value = "";
  if (from && to && from !== to) emit("move", { from, to });
};
</script>

<style scoped lang="scss">
.sfg-grid {
  display: grid;
  grid-template-columns: 48px repeat(12, minmax(0, 1fr));
  gap: 6px;
  align-items: stretch;
}

.sfg-corner,
.sfg-col-head,
.sfg-row-head {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #8a7a67;
  font-size: 11px;
  font-weight: 600;
}

.sfg-corner {
  justify-content: flex-start;
  padding-left: 2px;
}

.sfg-col-head {
  padding-bottom: 1px;
}

.sfg-row-head {
  justify-content: flex-start;
  padding-left: 2px;
}

.sfg-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  align-items: flex-start;
  justify-content: space-between;
  min-height: 62px;
  padding: 6px 6px 5px;
  overflow: hidden;
  color: #2f2a24;
  text-align: left;
  cursor: pointer;
  background: #fffdf9;
  border: 1px solid #e8dfd2;
  border-radius: 8px;
  transition: border-color 120ms ease, background 120ms ease, box-shadow 120ms ease;

  &:hover {
    border-color: #b7793f;
  }

  &:focus-visible {
    outline: 2px solid #2f6b58;
    outline-offset: 1px;
  }

  &.is-ok {
    border-color: #d5e5dc;
    background: #fbfdfb;
  }

  &.is-low {
    border-color: #e0bc8d;
    background: #fdf6ea;
  }

  &.is-out {
    border-color: #e0a3a3;
    background: #fdf1f1;
  }

  &.is-empty {
    justify-content: center;
    align-items: center;
    color: #b3a696;
    background: #fbf8f2;
    border-style: dashed;
  }

  &.is-hit {
    box-shadow: 0 0 0 2px rgba(183, 121, 63, 0.35);
  }

  &.is-selected {
    border-color: #2f6b58;
    box-shadow: 0 0 0 2px rgba(47, 107, 88, 0.35);
  }

  &.is-dragging {
    opacity: 0.45;
  }

  &.is-flash {
    animation: sfg-flash 420ms ease-in-out 3;
  }
}

.is-editing .sfg-cell[draggable="true"] {
  cursor: grab;
}

.sfg-name {
  display: -webkit-box;
  overflow: hidden;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.25;
  word-break: break-all;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.sfg-stock {
  color: #7b7064;
  font-size: 10px;
  font-variant-numeric: tabular-nums;

  &.is-out {
    color: #b04a4a;
    font-weight: 700;
  }

  &.is-low {
    color: #a2671f;
    font-weight: 600;
  }
}

.sfg-empty {
  font-size: 10px;
  white-space: nowrap;
}

.sfg-hint {
  margin: 10px 0 0;
  color: #8a7a67;
  font-size: 11px;
}

@keyframes sfg-flash {
  0%,
  100% {
    background: #fffdf9;
  }
  50% {
    background: #f7ecdd;
  }
}
</style>
