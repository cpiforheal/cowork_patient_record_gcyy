<template>
  <section class="address-analysis">
    <header class="analysis-head">
      <div>
        <strong>患者居住地分析</strong>
        <small>县区 → 乡镇/街道 → 村/社区 → 患者卡片</small>
      </div>
      <div class="analysis-actions">
        <el-segmented v-model="period" :options="periodOptions" @change="load" />
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" plain :icon="ArrowRight" @click="openDashboard">查看下钻分析</el-button>
      </div>
    </header>

    <div v-loading="loading" class="analysis-body">
      <template v-if="data">
        <div class="summary-row">
          <span>患者数 <b>{{ data.summary.patientCount }}</b></span>
          <span>来访人次 <b>{{ data.summary.visitCount }}</b></span>
          <span>未识别地址 <b>{{ data.summary.unidentifiedCount }}</b></span>
          <small>{{ data.from }} 至 {{ data.to }}</small>
        </div>

        <div v-if="nodes.length" class="node-list">
          <button v-for="node in nodes" :key="node.key" type="button" class="node-row" @click="openDashboard">
            <span class="node-label">{{ node.label }}</span>
            <span class="node-track"><i :style="{ width: `${Math.max(node.share, 2)}%` }" /></span>
            <span class="node-value">{{ node.visitCount }} 次</span>
            <el-icon><ArrowRight /></el-icon>
          </button>
        </div>
        <el-empty v-else description="当前周期暂无地址数据" :image-size="56" />
      </template>
      <el-empty v-else-if="!loading" description="地址分析暂不可用" :image-size="56" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { ArrowRight, Refresh } from "@element-plus/icons-vue";
import { loadAddressAnalysisApi, type AddressAnalysisResult } from "@/api/modules/clinic/opsDashboard";

const router = useRouter();
const period = ref("365");
const loading = ref(false);
const data = ref<AddressAnalysisResult>();

const periodOptions = [
  { label: "近30天", value: "30" },
  { label: "近90天", value: "90" },
  { label: "近12个月", value: "365" }
];

const dateRange = computed(() => {
  const end = new Date();
  const start = new Date(end);
  start.setDate(start.getDate() - Number(period.value) + 1);
  const format = (value: Date) => {
    const year = value.getFullYear();
    const month = String(value.getMonth() + 1).padStart(2, "0");
    const day = String(value.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
  };
  return { from: format(start), to: format(end) };
});

const nodes = computed(() => data.value?.nodes || []);

const load = async () => {
  loading.value = true;
  try {
    const { data: next } = await loadAddressAnalysisApi({
      ...dateRange.value,
      level: "COUNTY",
      metric: "visits",
      page: 1,
      pageSize: 8
    });
    data.value = next;
  } catch (error: any) {
    ElMessage.warning(error?.message || "地址分析加载失败");
  } finally {
    loading.value = false;
  }
};

const openDashboard = () => router.push({ path: "/ops/dashboard", query: { view: "population" } });

onMounted(() => void load());
</script>

<style scoped lang="scss">
.address-analysis {
  display: grid;
  gap: 12px;
}
.analysis-head,
.analysis-actions,
.summary-row,
.node-row {
  display: flex;
  align-items: center;
}
.analysis-head {
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.analysis-head strong {
  display: block;
  font-size: 15px;
}
.analysis-head small {
  display: block;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.analysis-actions {
  gap: 8px;
  flex-wrap: wrap;
}
.analysis-body {
  min-height: 96px;
}
.summary-row {
  gap: 18px;
  flex-wrap: wrap;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.summary-row b {
  margin-left: 4px;
  color: var(--el-text-color-primary);
  font-size: 16px;
}
.summary-row small {
  margin-left: auto;
  color: var(--el-text-color-placeholder);
}
.node-list {
  display: grid;
  gap: 6px;
  margin-top: 10px;
}
.node-row {
  gap: 10px;
  width: 100%;
  padding: 8px 4px;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-regular);
}
.node-row:hover .node-label,
.node-row:hover .el-icon {
  color: var(--el-color-primary);
}
.node-label {
  width: 112px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-track {
  flex: 1;
  height: 7px;
  overflow: hidden;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
.node-track i {
  display: block;
  height: 100%;
  background: var(--el-color-primary);
  border-radius: inherit;
}
.node-value {
  width: 60px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-align: right;
}
@media (width <= 760px) {
  .summary-row small {
    margin-left: 0;
  }
  .node-label {
    width: 86px;
  }
}
</style>

