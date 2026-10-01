<template>
  <section class="ra" aria-labelledby="ra-title">
    <header class="ra-head">
      <div>
        <h3 id="ra-title">来访患者居住地</h3>
        <p>固始县 / 县外 → 乡镇/街道 → 村/社区 → 患者病史</p>
      </div>
      <div class="ra-tools">
        <el-segmented v-model="period" :options="PERIODS" size="small" />
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <ul class="ra-kpis" aria-label="概况">
      <li>
        <span>患者</span><b>{{ tree.count }}</b>
      </li>
      <li>
        <span>县内占比</span><b>{{ pct(localNode?.count, tree.count) }}</b>
      </li>
      <li>
        <span>覆盖乡镇</span><b>{{ coveredTownships }}</b
        ><small>/ {{ townshipTotal }}</small>
      </li>
      <li>
        <span>县外</span><b>{{ outsideNode?.count || 0 }}</b>
      </li>
      <li v-if="topTownship">
        <span>TOP1</span><b class="text">{{ topTownship.label }}</b
        ><small>{{ topTownship.count }} 人</small>
      </li>
    </ul>

    <div v-loading="loading" class="ra-body">
      <div class="ra-map-col">
        <ResidenceMap
          v-model:hover="hoverTownship"
          :counts="townshipCounts"
          :active-township="activeTownship"
          :dimmed="!inLocal"
          @pick="pickTownship"
        />
        <p class="ra-note">乡镇分块按驻地位置近似划分，仅示意分布；县外与待核实地址见右侧。</p>
      </div>

      <div class="ra-side">
        <div class="ra-regions" role="tablist" aria-label="来源大区">
          <button
            v-for="region in regions"
            :key="region.key"
            type="button"
            role="tab"
            :aria-selected="path[0] === region.key"
            class="ra-region"
            :class="{ on: path[0] === region.key, pending: region.pending }"
            :disabled="!region.count"
            @click="go(0, region.key)"
          >
            <span>{{ region.label }}</span>
            <b>{{ region.count }}</b>
            <small>{{ pct(region.count, tree.count) }}</small>
          </button>
        </div>

        <nav class="ra-crumbs" aria-label="层级">
          <template v-for="(crumb, i) in crumbs" :key="crumb.key">
            <span v-if="i" class="sep">/</span>
            <button type="button" :class="{ current: i === crumbs.length - 1 }" @click="go(i, crumb.key)">
              {{ crumb.label }}
            </button>
          </template>
          <span class="ra-level">{{ levelHint }}</span>
        </nav>

        <el-empty v-if="!current || !current.count" description="该范围暂无来访患者" :image-size="56" />

        <!-- 下钻列表 -->
        <TransitionGroup v-else-if="!leaf" :key="current.key" name="ra-row" tag="ol" class="ra-list" appear>
          <li v-for="(row, i) in rows" :key="row.key" :style="{ '--i': Math.min(i, 12) }">
            <button
              type="button"
              class="ra-row"
              :class="{ pending: row.pending, hot: row.kind === 'township' && inLocal && hoverTownship === row.label }"
              @click="go(path.length, row.key)"
              @pointerenter="syncHover(row, true)"
              @pointerleave="syncHover(row, false)"
            >
              <span class="rank">{{ row.pending ? "?" : i + 1 }}</span>
              <span class="name">{{ row.label }}</span>
              <span class="bar"><i :style="{ width: `${(row.count / maxRow) * 100}%` }" /></span>
              <b>{{ row.count }}</b>
              <small>{{ pct(row.count, current.count) }}</small>
            </button>
          </li>
        </TransitionGroup>

        <!-- 叶子：患者卡片 -->
        <template v-else>
          <TransitionGroup :key="current.key" name="ra-row" tag="ul" class="ra-people" appear>
            <li v-for="(person, i) in visiblePeople" :key="person.key" :style="{ '--i': Math.min(i, 12) }">
              <button type="button" class="ra-person" @click="openPerson(person)">
                <span class="avatar" aria-hidden="true">{{ person.name.slice(0, 1) }}</span>
                <span class="who">
                  <b>{{ person.name }}</b>
                  <small>{{ [person.gender, person.age].filter(Boolean).join(" · ") || "—" }}</small>
                </span>
                <span class="meta">
                  <small>{{ person.visitDate?.slice(0, 10) || "—" }}</small>
                  <em v-if="person.visitCount > 1">{{ person.visitCount }} 次</em>
                  <em v-else-if="person.source === 'billing'" class="grey">仅收费</em>
                </span>
              </button>
            </li>
          </TransitionGroup>
          <el-button v-if="leafPeople.length > peopleLimit" class="ra-more" text type="primary" @click="peopleLimit += PAGE">
            再显示 {{ Math.min(PAGE, leafPeople.length - peopleLimit) }} 人（共 {{ leafPeople.length }}）
          </el-button>
        </template>
      </div>
    </div>

    <PatientHistoryDialog v-model="historyVisible" :person="selectedPerson" :place="placeText" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, shallowRef, watch } from "vue";
import { ElMessage } from "element-plus";
import dayjs from "dayjs";
import { getBillingPatientsApi, type BillingPatientInfo } from "@/api/modules/clinic/billing";
import { getPreAiPatientCasesApi, type PreAiPatientCase } from "@/api/modules/clinic/preAi";
import ResidenceMap from "./residence/ResidenceMap.vue";
import PatientHistoryDialog from "./residence/PatientHistoryDialog.vue";
import {
  REGION_LOCAL,
  REGION_OUTSIDE,
  REGION_UNKNOWN,
  TOWNSHIP_COORDS,
  buildResidenceTree,
  isLeaf,
  sortedChildren,
  type ResidenceNode,
  type ResidencePerson
} from "./residence/model";

const PERIODS = [
  { label: "全部", value: 0 },
  { label: "近一年", value: 365 },
  { label: "近90天", value: 90 },
  { label: "近30天", value: 30 }
];
const PAGE = 24;
const LOCAL_KEY = `/${REGION_LOCAL}`;
const townshipTotal = Object.keys(TOWNSHIP_COORDS).length;

const period = ref(0);
const loading = ref(false);
const cases = shallowRef<PreAiPatientCase[]>([]);
const billing = shallowRef<BillingPatientInfo[]>([]);

const load = async () => {
  loading.value = true;
  const [billingResult, caseResult] = await Promise.allSettled([getBillingPatientsApi(""), getPreAiPatientCasesApi()]);
  billing.value = billingResult.status === "fulfilled" ? billingResult.value.data.patients || [] : [];
  cases.value = caseResult.status === "fulfilled" ? caseResult.value.data.list || [] : [];
  if (billingResult.status === "rejected" && caseResult.status === "rejected") ElMessage.error("居住地数据加载失败");
  loading.value = false;
};
onMounted(load);

/** 统一人口：前置病历为权威来源，收费登记按姓名补充未建病历的患者。 */
const people = computed<ResidencePerson[]>(() => {
  const list: ResidencePerson[] = cases.value.map(item => ({
    key: `preai:${item.id}`,
    name: item.patientName || "未命名",
    gender: item.gender || "",
    age: item.age || "",
    phone: String(item.patient?.phone || ""),
    address: String(item.patient?.address || ""),
    visitDate: String(item.latestEncounter?.visitDate || item.patient?.visitDate || item.updatedAt || ""),
    visitCount: item.visitCount || 1,
    caseId: item.id,
    encounterId: item.latestEncounter?.id || "",
    source: "preai"
  }));
  const names = new Set(list.map(person => person.name));
  billing.value.forEach(item => {
    if (names.has(item.patientName)) return;
    list.push({
      key: `billing:${item.id}`,
      name: item.patientName || "未命名",
      gender: "",
      age: "",
      phone: String(item.phone || ""),
      address: String(item.address || ""),
      visitDate: String(item.updatedAt || ""),
      visitCount: 1,
      caseId: "",
      encounterId: "",
      source: "billing"
    });
  });
  if (!period.value) return list;
  const since = dayjs().subtract(period.value, "day").format("YYYY-MM-DD");
  return list.filter(person => person.visitDate.slice(0, 10) >= since);
});

const tree = computed(() => buildResidenceTree(people.value));
const localNode = computed(() => tree.value.children.get(LOCAL_KEY));
const outsideNode = computed(() => tree.value.children.get(`/${REGION_OUTSIDE}`));
const regions = computed(() =>
  [REGION_LOCAL, REGION_OUTSIDE, REGION_UNKNOWN].map(label => {
    const key = `/${label}`;
    return { key, label, count: tree.value.children.get(key)?.count || 0, pending: label === REGION_UNKNOWN };
  })
);

const townshipCounts = computed(() => {
  const counts = new Map<string, { count: number; visits: number }>();
  localNode.value?.children.forEach(node => counts.set(node.label, { count: node.count, visits: node.visits }));
  return counts;
});
const localTownships = computed(() => (localNode.value ? sortedChildren(localNode.value).filter(node => !node.pending) : []));
const coveredTownships = computed(() => localTownships.value.filter(node => node.label in TOWNSHIP_COORDS).length);
const topTownship = computed(() => localTownships.value[0]);

// ---------- 层级导航：path 存每一级节点 key ----------
const path = ref<string[]>([LOCAL_KEY]);
const nodeAt = (depth: number): ResidenceNode | undefined => {
  let node: ResidenceNode | undefined = tree.value;
  for (let i = 0; i <= depth && node; i++) node = node.children.get(path.value[i]);
  return node;
};
const current = computed(() => nodeAt(path.value.length - 1));
const crumbs = computed(() =>
  path.value.map((key, i) => ({ key, label: nodeAt(i)?.label || key.split("/").pop() || "" })).filter(c => c.label)
);
const leaf = computed(() => !!current.value && isLeaf(current.value));
const inLocal = computed(() => path.value[0] === LOCAL_KEY);
const activeTownship = computed(() => (inLocal.value && path.value.length > 1 ? nodeAt(1)?.label || "" : ""));
const rows = computed(() => (current.value ? sortedChildren(current.value) : []));
const maxRow = computed(() => Math.max(1, ...rows.value.map(row => row.count)));

const LEVEL_HINT: Record<string, string> = {
  region: "下一级：乡镇/街道",
  county: "下一级：乡镇/街道",
  township: "下一级：村/社区",
  village: "患者"
};
const levelHint = computed(() => {
  const node = current.value;
  if (!node) return "";
  if (leaf.value) return `${node.count} 位患者`;
  if (node.kind === "region" && node.label === REGION_OUTSIDE) return "下一级：县区";
  return LEVEL_HINT[node.kind] || "";
});

const go = (depth: number, key: string) => {
  path.value = [...path.value.slice(0, depth), key];
};
const pickTownship = (name: string) => {
  if (!townshipCounts.value.get(name)?.count) return;
  path.value = [LOCAL_KEY, `${LOCAL_KEY}/${name}`];
};
const hoverTownship = ref("");
/** 列表 hover 与地图凸起联动（仅县内乡镇层） */
const syncHover = (row: ResidenceNode, enter: boolean) => {
  if (row.kind !== "township" || !inLocal.value) return;
  hoverTownship.value = enter ? row.label : "";
};

// 周期切换后，若当前路径已无数据则逐级回退
watch(tree, () => {
  while (path.value.length > 1 && !current.value?.count) path.value = path.value.slice(0, -1);
});

// ---------- 患者卡片 ----------
const peopleLimit = ref(PAGE);
const leafPeople = computed(() =>
  leaf.value && current.value ? [...current.value.people].sort((a, b) => b.visitDate.localeCompare(a.visitDate)) : []
);
const visiblePeople = computed(() => leafPeople.value.slice(0, peopleLimit.value));
watch(current, () => (peopleLimit.value = PAGE));

const historyVisible = ref(false);
const selectedPerson = ref<ResidencePerson | null>(null);
const placeText = computed(() => crumbs.value.map(c => c.label).join(" / "));
const openPerson = (person: ResidencePerson) => {
  selectedPerson.value = person;
  historyVisible.value = true;
};

const pct = (part = 0, whole = 0) => (whole ? `${Math.round((part / whole) * 1000) / 10}%` : "—");
</script>

<style scoped lang="scss">
.ra {
  --ra-brand: #0f766e;
  --ra-teal: #009688;
  --ra-soft: rgb(0 150 136 / 8%);
  --ra-spring: cubic-bezier(0.34, 1.56, 0.64, 1);

  display: grid;
  gap: 12px;
  padding: 16px 18px;
}
.ra-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  align-items: flex-start;
  justify-content: space-between;
  h3 {
    margin: 0;
    font-size: 16px;
    color: var(--el-text-color-primary);
  }
  p {
    margin: 2px 0 0;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}
.ra-tools {
  display: flex;
  gap: 8px;
  align-items: center;
}
.ra-kpis {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0;
  margin: 0;
  list-style: none;
  li {
    display: flex;
    gap: 6px;
    align-items: baseline;
    padding: 6px 12px;
    background: var(--ra-soft);
    border-radius: 999px;
  }
  span,
  small {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
  b {
    font-size: 16px;
    font-variant-numeric: tabular-nums;
    color: var(--ra-brand);
    &.text {
      font-size: 14px;
    }
  }
}
.ra-body {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(300px, 1fr);
  gap: 16px;
  min-height: 380px;
}
.ra-map-col {
  min-width: 0;
}
.ra-note {
  margin: 0;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.ra-side {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
.ra-regions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.ra-region {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: baseline;
  padding: 10px 12px;
  font: inherit;
  color: var(--el-text-color-regular);
  text-align: left;
  cursor: pointer;
  background: var(--el-fill-color-lighter);
  border: 1px solid transparent;
  border-radius: 10px;
  transition:
    transform 0.32s var(--ra-spring),
    box-shadow 0.2s ease,
    border-color 0.2s ease,
    background 0.2s ease;
  span {
    font-size: 13px;
  }
  b {
    font-size: 18px;
    font-variant-numeric: tabular-nums;
    color: var(--el-text-color-primary);
  }
  small {
    grid-column: 1 / -1;
    font-size: 11px;
    color: var(--el-text-color-secondary);
  }
  &:hover:not(:disabled) {
    box-shadow: 0 8px 18px rgb(15 118 110 / 16%);
    transform: translateY(-4px);
  }
  &:active:not(:disabled) {
    transform: translateY(-1px) scale(0.98);
  }
  &.on {
    background: var(--ra-soft);
    border-color: var(--ra-teal);
    b {
      color: var(--ra-brand);
    }
  }
  &.pending.on {
    background: rgb(180 83 9 / 8%);
    border-color: #b45309;
    b {
      color: #b45309;
    }
  }
  &:disabled {
    cursor: not-allowed;
    opacity: 0.5;
  }
}
.ra-crumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
  font-size: 13px;
  button {
    padding: 2px 4px;
    font: inherit;
    color: var(--ra-teal);
    cursor: pointer;
    background: none;
    border: none;
    border-radius: 4px;
    &:hover {
      background: var(--ra-soft);
    }
    &.current {
      font-weight: 600;
      color: var(--el-text-color-primary);
      cursor: default;
      background: none;
    }
  }
  .sep {
    color: var(--el-text-color-placeholder);
  }
}
.ra-level {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ra-list,
.ra-people {
  display: grid;
  gap: 6px;
  max-height: 420px;
  padding: 4px 2px 8px;
  margin: 0;
  overflow-y: auto;
  list-style: none;
}
.ra-row {
  display: grid;
  grid-template-columns: 22px 84px minmax(40px, 1fr) 40px 48px;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 8px 10px;
  font: inherit;
  color: var(--el-text-color-regular);
  text-align: left;
  cursor: pointer;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  transition:
    transform 0.32s var(--ra-spring),
    box-shadow 0.2s ease,
    border-color 0.2s ease;
  .rank {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    text-align: center;
  }
  .name {
    overflow: hidden;
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .bar {
    height: 8px;
    overflow: hidden;
    background: var(--el-fill-color);
    border-radius: 999px;
    i {
      display: block;
      height: 100%;
      background: linear-gradient(90deg, #5b9e96, var(--ra-teal));
      border-radius: inherit;
      transition:
        width 0.4s ease,
        transform 0.32s var(--ra-spring);
      transform-origin: left;
    }
  }
  b {
    font-variant-numeric: tabular-nums;
    color: var(--el-text-color-primary);
    text-align: right;
  }
  small {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    text-align: right;
  }
  &:hover,
  &.hot,
  &:focus-visible {
    border-color: var(--ra-teal);
    box-shadow: 0 8px 18px rgb(15 118 110 / 14%);
    transform: translateY(-3px);
    .bar i {
      transform: scaleY(1.5);
    }
  }
  &:active {
    transform: translateY(0) scale(0.99);
  }
  &.pending {
    background: var(--el-fill-color-lighter);
    .name,
    b {
      color: #b45309;
    }
    .bar i {
      background: #d6a873;
    }
  }
}
.ra-people {
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
}
.ra-person {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 4px 8px;
  align-items: center;
  width: 100%;
  padding: 10px;
  font: inherit;
  text-align: left;
  cursor: pointer;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  transition:
    transform 0.32s var(--ra-spring),
    box-shadow 0.2s ease,
    border-color 0.2s ease;
  .avatar {
    display: grid;
    place-items: center;
    width: 34px;
    height: 34px;
    font-weight: 600;
    color: #ffffff;
    background: linear-gradient(135deg, var(--ra-teal), var(--ra-brand));
    border-radius: 50%;
    transition: transform 0.32s var(--ra-spring);
  }
  .who {
    display: grid;
    min-width: 0;
    b {
      overflow: hidden;
      font-size: 14px;
      color: var(--el-text-color-primary);
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    small {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }
  .meta {
    display: flex;
    grid-column: 1 / -1;
    justify-content: space-between;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    em {
      font-style: normal;
      color: var(--ra-brand);
      &.grey {
        color: var(--el-text-color-placeholder);
      }
    }
  }
  &:hover,
  &:focus-visible {
    border-color: var(--ra-teal);
    box-shadow: 0 10px 20px rgb(15 118 110 / 16%);
    transform: translateY(-4px);
    .avatar {
      transform: scale(1.12) rotate(-6deg);
    }
  }
  &:active {
    transform: translateY(-1px) scale(0.98);
  }
}
.ra-more {
  align-self: center;
}

/* 列表进场：自下而上依次弹入 */
.ra-row-enter-active {
  transition:
    opacity 0.28s ease,
    transform 0.42s var(--ra-spring);
  transition-delay: calc(var(--i) * 28ms);
}
.ra-row-enter-from {
  opacity: 0;
  transform: translateY(10px);
}

@media (width <= 1100px) {
  .ra-body {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ra-region,
  .ra-row,
  .ra-person,
  .ra-person .avatar,
  .ra-row-enter-active {
    transition: none;
  }
  .ra-region:hover,
  .ra-row:hover,
  .ra-row.hot,
  .ra-person:hover {
    transform: none;
  }
}
</style>
