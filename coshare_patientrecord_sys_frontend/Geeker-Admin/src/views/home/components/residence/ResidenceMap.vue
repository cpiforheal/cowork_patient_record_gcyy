<template>
  <div ref="wrap" class="rm" :class="{ dimmed }" @pointermove="onMove" @pointerleave="onLeave">
    <div class="rm-stage" :style="stageStyle">
      <svg
        v-if="geo"
        class="rm-svg"
        :viewBox="`0 ${-LIFT_MAX} ${geo.width} ${geo.height + PLINTH + LIFT_MAX}`"
        role="img"
        aria-label="固始县乡镇来访患者分布图"
      >
        <!-- 底座：多层下移轮廓叠出厚度 -->
        <g class="rm-plinth" aria-hidden="true">
          <path
            v-for="i in PLINTH_LAYERS"
            :key="i"
            :d="geo.outline"
            :transform="`translate(0 ${(i * PLINTH) / PLINTH_LAYERS})`"
          />
        </g>

        <!-- 基础分块：只负责着色与命中 -->
        <g class="rm-base">
          <path
            v-for="shape in geo.townships"
            :key="shape.name"
            :d="shape.d"
            class="rm-cell"
            :class="{ empty: !countOf(shape.name), muted: activeTownship && activeTownship !== shape.name }"
            :fill="fillOf(shape.name)"
            tabindex="0"
            role="button"
            :aria-label="`${shape.name} ${countOf(shape.name)} 人`"
            @pointerenter="emit('update:hover', shape.name)"
            @pointerleave="emit('update:hover', '')"
            @focus="emit('update:hover', shape.name)"
            @blur="emit('update:hover', '')"
            @click="emit('pick', shape.name)"
            @keydown.enter.prevent="emit('pick', shape.name)"
          />
        </g>

        <!-- 名称：常驻；人数气泡按 √人数 缩放 -->
        <g class="rm-labels" aria-hidden="true">
          <template v-for="shape in geo.townships" :key="shape.name">
            <circle v-if="countOf(shape.name)" :cx="shape.x" :cy="shape.y" :r="dotRadius(shape.name)" class="rm-dot" />
            <text :x="shape.x" :y="shape.y + dotRadius(shape.name) + 15" class="rm-name">{{ shape.name }}</text>
          </template>
        </g>

        <!-- 凸起层：hover / 选中的乡镇单独重绘在最上层，侧壁 + 顶面整体弹跳抬升 -->
        <TransitionGroup name="rm-lift" tag="g" class="rm-lifted" aria-hidden="true">
          <g v-for="shape in liftedShapes" :key="shape.name" class="rm-block" :class="{ active: shape.name === activeTownship }">
            <g class="rm-rise">
              <path
                v-for="i in WALL_LAYERS"
                :key="i"
                :d="shape.d"
                class="rm-wall"
                :style="{ '--wall': `${(WALL_LAYERS - i + 1) * WALL_STEP}px` }"
              />
              <path :d="shape.d" class="rm-top" :fill="liftFill(shape.name)" />
              <text :x="shape.x" :y="shape.y + 5" class="rm-top-name">{{ shape.name }}</text>
              <text :x="shape.x" :y="shape.y + 26" class="rm-top-count">{{ countOf(shape.name) }} 人</text>
            </g>
          </g>
        </TransitionGroup>

        <!-- 本院锚点 -->
        <g class="rm-hospital" :transform="`translate(${geo.hospital[0]} ${geo.hospital[1]})`" aria-hidden="true">
          <circle r="16" class="rm-ring" />
          <circle r="6" class="rm-core" />
          <text y="-14" class="rm-hospital-name">本院</text>
        </g>
      </svg>
      <el-empty v-else description="地图边界数据加载失败" :image-size="48" />
    </div>

    <div v-if="hover && tip.show" class="rm-tip" :style="{ transform: `translate(${tip.x}px, ${tip.y}px)` }" role="status">
      <strong>{{ hover }}</strong>
      <span>
        <b>{{ countOf(hover) }}</b> 人 · 来访 {{ visitsOf(hover) }} 次<template v-if="total">
          · 占县内 {{ shareOf(hover) }}</template
        >
      </span>
      <small>{{ countOf(hover) ? "点击下钻到村/社区" : "暂无来访患者" }}</small>
    </div>

    <ul class="rm-legend" aria-label="颜色图例">
      <li v-for="(color, i) in palette" :key="color"><i :style="{ background: color }" />{{ legendText(i) }}</li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { useGlobalStore } from "@/stores/modules/global";
import { residenceGeometry } from "./model";

const props = defineProps<{
  counts: Map<string, { count: number; visits: number }>;
  hover: string;
  /** 已下钻的乡镇：保持凸起，其余压暗 */
  activeTownship?: string;
  /** 当前浏览县外/待核实时，整张地图退为背景 */
  dimmed?: boolean;
}>();
const emit = defineEmits<{ (e: "update:hover", name: string): void; (e: "pick", name: string): void }>();

const PLINTH = 14;
const PLINTH_LAYERS = 7;
const WALL_LAYERS = 6;
const WALL_STEP = 3;
const LIFT_MAX = 40;

const geo = residenceGeometry();
const globalStore = useGlobalStore();

const LIGHT = ["#e6f2f0", "#b8ded8", "#7cc3b8", "#2fa595", "#0f766e"];
const DARK = ["#22333a", "#24524f", "#2b7a72", "#2fa595", "#5fd0bf"];
const palette = computed(() => (globalStore.isDark ? DARK : LIGHT));

const countOf = (name: string) => props.counts.get(name)?.count || 0;
const visitsOf = (name: string) => props.counts.get(name)?.visits || 0;
const total = computed(() => [...props.counts.values()].reduce((sum, row) => sum + row.count, 0));
const max = computed(() => Math.max(1, ...[...props.counts.values()].map(row => row.count)));
const shareOf = (name: string) => `${Math.round((countOf(name) / Math.max(1, total.value)) * 1000) / 10}%`;

const step = (count: number) => (count <= 0 ? 0 : Math.min(4, 1 + Math.floor((count / max.value) * 3.999)));
const fillOf = (name: string) => palette.value[step(countOf(name))];
const liftFill = (name: string) => (countOf(name) ? palette.value[Math.max(3, step(countOf(name)))] : palette.value[2]);
const dotRadius = (name: string) => (countOf(name) ? 4 + Math.sqrt(countOf(name) / max.value) * 12 : 0);
const legendText = (i: number) => {
  if (i === 0) return "0";
  const lo = Math.ceil(((i - 1) / 4) * max.value) || 1;
  const hi = Math.floor((i / 4) * max.value);
  return i === 4 ? `≥${lo}` : lo >= hi ? `${lo}` : `${lo}–${hi}`;
};

const liftedShapes = computed(() => {
  if (!geo) return [];
  const names = new Set([props.activeTownship, props.hover].filter(Boolean));
  // 选中项先画，hover 项后画，保证 hover 凸起永远在最上
  return geo.townships
    .filter(shape => names.has(shape.name))
    .sort((a, b) => Number(a.name === props.hover) - Number(b.name === props.hover));
});

// ---------- 指针：tooltip 跟随 + 舞台轻微视差倾斜 ----------
const wrap = ref<HTMLElement>();
const tip = reactive({ show: false, x: 0, y: 0 });
const tilt = reactive({ x: 0, y: 0 });
const reduceMotion = typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

const onMove = (event: PointerEvent) => {
  const rect = wrap.value?.getBoundingClientRect();
  if (!rect) return;
  const px = event.clientX - rect.left;
  const py = event.clientY - rect.top;
  tip.show = true;
  tip.x = Math.min(px + 14, rect.width - 190);
  tip.y = Math.max(py - 70, 4);
  if (!reduceMotion) {
    tilt.x = (px / rect.width - 0.5) * 2;
    tilt.y = (py / rect.height - 0.5) * 2;
  }
};
const onLeave = () => {
  tip.show = false;
  tilt.x = 0;
  tilt.y = 0;
  emit("update:hover", "");
};

const stageStyle = computed(() => ({
  transform: `rotateX(${24 - tilt.y * 3}deg) rotateZ(${tilt.x * -1.5}deg)`
}));
</script>

<style scoped lang="scss">
.rm {
  --rm-wall: #0b5d56;
  --rm-stroke: rgb(255 255 255 / 90%);
  --rm-label: #36504c;
  --rm-plinth: #c9dcd8;
  --rm-hospital: #b45309;

  position: relative;
  min-height: 320px;
  padding: 8px 4px 32px;
  transition:
    opacity 0.24s ease,
    filter 0.24s ease;
  perspective: 1100px;
  &.dimmed {
    filter: saturate(0.4);
    opacity: 0.45;
  }
}
:global(html.dark) .rm {
  --rm-wall: #134e4a;
  --rm-stroke: rgb(15 23 42 / 85%);
  --rm-label: #cfe3df;
  --rm-plinth: #1d2b30;
}
.rm-stage {
  transition: transform 0.5s cubic-bezier(0.22, 1, 0.36, 1);
  transform-origin: 50% 60%;
}
.rm-svg {
  display: block;
  width: 100%;
  height: auto;
  max-height: 540px;
  overflow: visible;
}
.rm-plinth path {
  fill: var(--rm-plinth);
  stroke: none;
  &:last-child {
    filter: drop-shadow(0 10px 14px rgb(15 118 110 / 18%));
  }
}
.rm-cell {
  cursor: pointer;
  outline: none;
  stroke: var(--rm-stroke);
  stroke-width: 1.4;
  transition:
    fill 0.24s ease,
    opacity 0.24s ease;
  &.empty {
    cursor: default;
  }
  &.muted {
    opacity: 0.55;
  }
}
.rm-labels {
  pointer-events: none;
}
.rm-dot {
  fill: rgb(255 255 255 / 55%);
  stroke: #0f766e;
  stroke-width: 1.5;
}
.rm-name {
  font-size: 13px;
  font-weight: 500;
  text-anchor: middle;
  fill: var(--rm-label);
  stroke: rgb(255 255 255 / 70%);
  stroke-width: 3px;
  paint-order: stroke;
}
:global(html.dark) .rm-name {
  stroke: rgb(15 23 42 / 70%);
}

/* ---------- 凸起块：侧壁层层下移形成厚度，顶面带投影；整体弹跳抬升 ---------- */
.rm-lifted {
  pointer-events: none;
}
.rm-rise {
  animation: rm-bounce 0.62s cubic-bezier(0.34, 1.56, 0.64, 1) both;
}
.rm-wall {
  fill: var(--rm-wall);
  stroke: var(--rm-wall);
  stroke-width: 1;
  transform: translateY(var(--wall));
}
.rm-top {
  filter: drop-shadow(0 14px 12px rgb(15 118 110 / 35%));
  stroke: #ffffff;
  stroke-width: 2;
}
.rm-top-name,
.rm-top-count {
  text-anchor: middle;
  fill: #ffffff;
  paint-order: stroke;
  stroke: rgb(11 93 86 / 55%);
  stroke-width: 3px;
}
.rm-top-name {
  font-size: 17px;
  font-weight: 700;
}
.rm-top-count {
  font-size: 14px;
  font-weight: 600;
}
.rm-block.active .rm-rise {
  animation-name: rm-bounce-hold;
}

/* 先冲过头再回落，结尾停在 -18px（hover）/ -14px（已选） */
@keyframes rm-bounce {
  0% {
    transform: translateY(0);
  }
  45% {
    transform: translateY(-26px);
  }
  70% {
    transform: translateY(-15px);
  }
  85% {
    transform: translateY(-20px);
  }
  100% {
    transform: translateY(-18px);
  }
}

@keyframes rm-bounce-hold {
  0% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-20px);
  }
  100% {
    transform: translateY(-14px);
  }
}
.rm-lift-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}
.rm-lift-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

/* ---------- 本院锚点：单圈呼吸 ---------- */
.rm-hospital {
  pointer-events: none;
}
.rm-core {
  fill: var(--rm-hospital);
  stroke: #ffffff;
  stroke-width: 2;
}
.rm-ring {
  fill: none;
  stroke: var(--rm-hospital);
  stroke-width: 2;
  transform-box: fill-box;
  transform-origin: center;
  animation: rm-pulse 2.4s ease-out infinite;
}
.rm-hospital-name {
  font-size: 13px;
  font-weight: 700;
  text-anchor: middle;
  fill: var(--rm-hospital);
  stroke: #ffffff;
  stroke-width: 3px;
  paint-order: stroke;
}

@keyframes rm-pulse {
  0% {
    opacity: 0.9;
    transform: scale(0.4);
  }
  100% {
    opacity: 0;
    transform: scale(1.6);
  }
}

/* ---------- tooltip / 图例 ---------- */
.rm-tip {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 3;
  display: grid;
  gap: 2px;
  min-width: 170px;
  padding: 8px 12px;
  font-size: 12px;
  color: var(--el-text-color-regular);
  pointer-events: none;
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgb(15 118 110 / 14%);
  transition: transform 0.08s linear;
  strong {
    font-size: 14px;
    color: var(--el-text-color-primary);
  }
  b {
    font-size: 15px;
    color: #0f766e;
  }
  small {
    color: var(--el-text-color-secondary);
  }
}
.rm-legend {
  position: absolute;
  bottom: 4px;
  left: 8px;
  display: flex;
  gap: 10px;
  padding: 0;
  margin: 0;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  list-style: none;
  li {
    display: flex;
    gap: 4px;
    align-items: center;
  }
  i {
    width: 12px;
    height: 8px;
    border-radius: 2px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .rm-rise,
  .rm-block.active .rm-rise {
    transform: translateY(-14px);
    animation: none;
  }
  .rm-ring {
    animation: none;
  }
  .rm-stage {
    transition: none;
  }
}
</style>
