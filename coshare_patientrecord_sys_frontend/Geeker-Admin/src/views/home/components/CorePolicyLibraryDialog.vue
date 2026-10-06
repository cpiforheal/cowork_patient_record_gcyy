<template>
  <el-dialog v-model="visible" width="860px" append-to-body destroy-on-close title="医疗质量安全核心制度（18 项）">
    <div v-loading="loading" class="library-body" element-loading-text="正在读取制度库…">
      <aside class="library-nav">
        <el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索制度或条款" size="small" />
        <el-scrollbar class="library-nav-scroll">
          <button
            v-for="policy in filteredPolicies"
            :key="policy.id"
            type="button"
            class="library-nav-item"
            :class="{ active: selectedId === policy.id }"
            @click="selectedId = policy.id"
          >
            <i class="library-nav-code">{{ policy.code }}</i>
            <span>{{ policy.title }}</span>
          </button>
        </el-scrollbar>
      </aside>
      <section v-if="selected" class="library-detail">
        <header class="library-detail-head">
          <span class="library-detail-code">{{ selected.code }}</span>
          <div>
            <h4>{{ selected.title }}</h4>
            <p>{{ selected.summary }}</p>
          </div>
        </header>
        <ol class="library-clauses">
          <li v-for="clause in filteredClauses" :key="clause.id" class="library-clause">
            <i>{{ clause.clauseNo }}</i>
            <span>{{ clause.content }}</span>
          </li>
        </ol>
      </section>
      <el-empty v-else-if="!loading" description="没有匹配的制度" :image-size="64" />
    </div>
  </el-dialog>
</template>

<script setup lang="ts" name="corePolicyLibraryDialog">
import { computed, ref, watch } from "vue";
import { Search } from "@element-plus/icons-vue";
import { getCorePolicyPoliciesApi, type CorePolicy } from "@/api/modules/clinic/corePolicy";

const visible = defineModel<boolean>({ default: false });
const loading = ref(false);
const keyword = ref("");
const policies = ref<CorePolicy[]>([]);
const selectedId = ref("");

const filteredPolicies = computed(() => {
  const needle = keyword.value.trim().toLowerCase();
  if (!needle) return policies.value;
  return policies.value.filter(
    policy =>
      policy.title.toLowerCase().includes(needle) ||
      policy.summary.toLowerCase().includes(needle) ||
      policy.clauses.some(clause => clause.content.toLowerCase().includes(needle))
  );
});
const selected = computed(() => policies.value.find(policy => policy.id === selectedId.value));
const filteredClauses = computed(() => {
  const needle = keyword.value.trim().toLowerCase();
  if (!selected.value) return [];
  if (!needle) return selected.value.clauses;
  return selected.value.clauses.filter(clause => clause.content.toLowerCase().includes(needle));
});

// 弹窗每次打开时懒加载制度树（destroy-on-close 下重新拉取，保证内容最新）
const load = async () => {
  loading.value = true;
  try {
    const { data: list } = await getCorePolicyPoliciesApi();
    policies.value = list;
    if (!selectedId.value && list.length) selectedId.value = list[0].id;
    if (selectedId.value && !list.some(policy => policy.id === selectedId.value)) {
      selectedId.value = list.length ? list[0].id : "";
    }
  } finally {
    loading.value = false;
  }
};
watch(visible, open => {
  if (open) void load();
});
</script>

<style scoped lang="scss">
.library-body {
  display: flex;
  gap: 14px;
  min-height: 420px;
}

.library-nav {
  display: grid;
  grid-template-rows: auto 1fr;
  gap: 10px;
  width: 240px;
  flex-shrink: 0;
}

.library-nav-scroll {
  height: 100%;
}

// 左列制度项：hover 高亮 + 指示条滑入
.library-nav-item {
  position: relative;
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 9px 10px;
  text-align: left;
  font: inherit;
  font-size: 13px;
  color: var(--el-text-color-regular);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 8px;
  transition:
    background-color var(--motion-control, 180ms) var(--ease-out, ease),
    color var(--motion-control, 180ms) var(--ease-out, ease);

  &::before {
    position: absolute;
    top: 9px;
    bottom: 9px;
    left: 0;
    width: 3px;
    content: "";
    background: var(--el-color-primary);
    border-radius: 0 3px 3px 0;
    transform: scaleY(0);
    transition: transform var(--motion-control, 180ms) var(--ease-out, ease);
  }

  @media (hover: hover) and (pointer: fine) {
    &:hover {
      color: var(--el-color-primary);
      background: color-mix(in srgb, var(--el-color-primary) 6%, transparent);

      &::before {
        transform: scaleY(1);
      }
    }
  }

  &.active {
    color: var(--el-color-primary);
    font-weight: 600;
    background: color-mix(in srgb, var(--el-color-primary) 10%, transparent);

    &::before {
      transform: scaleY(1);
    }
  }

  .library-nav-code {
    flex-shrink: 0;
    font-size: 11px;
    font-style: normal;
    font-weight: 800;
    color: var(--el-color-primary);
    font-variant-numeric: tabular-nums;
  }
}

.library-detail {
  flex: 1;
  min-width: 0;
}

.library-detail-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding-bottom: 12px;
  margin-bottom: 12px;
  border-bottom: 1px dashed var(--el-border-color-lighter);

  .library-detail-code {
    flex-shrink: 0;
    padding: 4px 12px;
    font-size: 18px;
    font-weight: 800;
    color: var(--el-color-primary);
    background: color-mix(in srgb, var(--el-color-primary) 8%, transparent);
    border-radius: 10px;
    font-variant-numeric: tabular-nums;
  }

  h4 {
    margin: 0 0 4px;
    font-size: 17px;
  }

  p {
    margin: 0;
    font-size: 13px;
    line-height: 1.6;
    color: var(--el-text-color-secondary);
  }
}

.library-clauses {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

// 条款行：hover 左移序号块着色
.library-clause {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 9px 10px;
  border-radius: 8px;
  transition: background-color var(--motion-control, 180ms) var(--ease-out, ease);

  @media (hover: hover) and (pointer: fine) {
    &:hover {
      background: var(--el-fill-color-lighter);

      i {
        color: #fff;
        background: var(--el-color-primary);
      }
    }
  }

  i {
    flex-shrink: 0;
    display: grid;
    place-items: center;
    min-width: 22px;
    height: 22px;
    font-size: 11px;
    font-style: normal;
    font-weight: 700;
    color: var(--el-color-primary);
    background: color-mix(in srgb, var(--el-color-primary) 10%, transparent);
    border-radius: 6px;
    font-variant-numeric: tabular-nums;
    transition:
      background-color var(--motion-control, 180ms) var(--ease-out, ease),
      color var(--motion-control, 180ms) var(--ease-out, ease);
  }

  span {
    font-size: 13px;
    line-height: 1.7;
    color: var(--el-text-color-regular);
  }
}

@media (prefers-reduced-motion: reduce) {
  .library-nav-item,
  .library-nav-item::before,
  .library-clause,
  .library-clause i {
    transition: none;
  }
}
</style>
