<template>
  <section class="quality-card" :class="{ 'is-ready': data?.ready }" v-loading="loading">
    <header class="quality-head">
      <div>
        <span class="eyebrow">管理就绪</span>
        <h3>{{ data?.ready ? "数据质量正常" : "还有阻塞项需要处理" }}</h3>
      </div>
      <span class="quality-state">{{ data?.ready ? "已就绪" : `${blockingCount} 项待处理` }}</span>
    </header>
    <div v-if="data?.items?.length" class="quality-grid">
      <button
        v-for="item in data.items"
        :key="item.key"
        type="button"
        class="quality-item"
        :class="`is-${item.severity.toLowerCase()}`"
        :disabled="item.count === 0"
        @click="open(item)"
      >
        <span class="quality-label">{{ item.label }}</span>
        <strong>{{ item.count }}</strong>
        <small>{{ item.detail }}</small>
        <span v-if="item.count" class="quality-go">去处理 →</span>
        <span v-else class="quality-ok">正常</span>
      </button>
    </div>
    <p v-else-if="!loading" class="quality-empty">暂无可检查项</p>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { getInventoryAdminDataQualityApi, type InventoryAdminQuality } from "@/api/modules/inventory";

const router = useRouter();
const data = ref<InventoryAdminQuality | null>(null);
const loading = ref(false);
const blockingCount = computed(() => (data.value?.items ?? []).filter(item => item.count > 0).length);

const load = async () => {
  loading.value = true;
  try {
    const result = await getInventoryAdminDataQualityApi();
    data.value = result.data ?? null;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取数据质量失败");
  } finally {
    loading.value = false;
  }
};

const open = (item: { route: string; count: number }) => {
  if (!item.count) return;
  router.push(item.route);
};

onMounted(() => {
  void load();
});
</script>

<style scoped lang="scss">
.quality-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  background: #fffdf9;
  border: 1px solid #efe8dd;
  border-radius: 14px;

  &.is-ready {
    background: linear-gradient(120deg, #f2faf6, #fffdf9 55%);
    border-color: #d8e9dd;
  }
}

.quality-head {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;

  h3 {
    margin: 3px 0 0;
    color: #2f2d27;
    font-size: 16px;
  }
}

.eyebrow {
  color: #347d68;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.quality-state {
  padding: 3px 10px;
  color: #8a5a13;
  font-size: 11px;
  background: #fdf6e7;
  border-radius: 999px;

  .is-ready & {
    color: #1f5a4a;
    background: #edf7f1;
  }
}

.quality-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.quality-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 2px 8px;
  align-items: baseline;
  padding: 10px 12px;
  color: #2f2d27;
  text-align: left;
  cursor: pointer;
  background: #f8f5ef;
  border: 1px solid #efe8dd;
  border-radius: 10px;

  &:disabled {
    cursor: default;
  }

  &:not(:disabled):hover {
    border-color: #d9cfc0;
  }

  &.is-danger {
    background: #fff7f4;
    border-color: #efd8d1;
  }

  &.is-warning {
    background: #fffaf0;
    border-color: #f0dfb8;
  }

  strong {
    color: #b54747;
    font-size: 20px;
    font-variant-numeric: tabular-nums;

    .is-warning & {
      color: #b7791f;
    }

    .is-ok & {
      color: #3f8a62;
    }
  }

  small {
    grid-column: 1 / -1;
    color: #9a9285;
    font-size: 10px;
  }
}

.quality-label {
  font-size: 12px;
  font-weight: 600;
}

.quality-go,
.quality-ok {
  grid-column: 1 / -1;
  margin-top: 3px;
  color: #347d68;
  font-size: 10px;
}

.quality-ok {
  color: #9a9285;
}

.quality-empty {
  margin: 0;
  color: #9a9285;
  font-size: 11px;
}

@media (max-width: 900px) {
  .quality-grid {
    grid-template-columns: 1fr;
  }
}
</style>
