<template>
  <div class="policy-brief">
    <header class="brief-head">
      <div class="head-text">
        <strong>医政早报</strong>
        <small>每天 7:30 自动采集医疗政策 / 医保 DIP / 肛肠学术资讯，AI 生成一句话导读</small>
      </div>
      <div class="head-actions">
        <el-date-picker
          v-model="briefDate"
          type="date"
          value-format="YYYY-MM-DD"
          :clearable="false"
          placeholder="选择日期"
          style="width: 140px"
          @change="loadItems"
        />
        <el-segmented v-model="category" :options="categoryOptions" size="default" @change="loadItems" />
        <el-button :loading="loading" @click="loadItems">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
        </el-button>
        <el-button v-if="isAdmin" type="primary" plain :loading="collecting" @click="onCollect">立即采集</el-button>
      </div>
    </header>

    <p class="brief-status" :class="{ running: lastRun?.running }">
      <template v-if="lastRun?.running">⏳ 采集中…（约 1-3 分钟，完成后点「刷新」查看）</template>
      <template v-else-if="lastRun?.finishedAt"> 上次采集 {{ lastRun.finishedAt }} · {{ lastRun.message }} </template>
      <template v-else>今日尚未采集（每天 7:30 自动运行，也可点「立即采集」）</template>
    </p>

    <div v-loading="loading" class="brief-list">
      <el-empty v-if="!loading && !items.length" description="该日期暂无资讯，可换一天或点「立即采集」" :image-size="72" />
      <article v-for="item in items" :key="item.id" class="brief-card">
        <div class="brief-meta">
          <el-tag size="small" :type="categoryTagType(item.category)" effect="plain">{{ categoryLabel(item.category) }}</el-tag>
          <el-tag size="small" effect="plain" type="info">{{ item.sourceName }}</el-tag>
          <small v-if="item.publishedAt">{{ item.publishedAt }}</small>
          <el-tag v-if="item.status === 'FAILED'" size="small" type="warning" effect="plain">摘要失败</el-tag>
        </div>
        <h3 class="brief-title" @click="openOriginal(item)">{{ item.title }}</h3>
        <p v-if="item.aiSummary" class="brief-summary">{{ item.aiSummary }}</p>
        <footer class="brief-foot">
          <a :href="item.url" target="_blank" rel="noopener noreferrer">
            查看原文 <el-icon><TopRight /></el-icon>
          </a>
        </footer>
      </article>
    </div>

    <p class="brief-disclaimer">摘要由 AI 生成，仅供参考，政策内容以官方原文为准。</p>
  </div>
</template>

<script setup lang="ts" name="policyBrief">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh, TopRight } from "@element-plus/icons-vue";
import { useUserStore } from "@/stores/modules/user";
import {
  getPolicyBriefItemsApi,
  getPolicyBriefLatestApi,
  triggerPolicyBriefCollectApi,
  type PolicyBriefItem,
  type PolicyBriefLastRun
} from "@/api/modules/clinic/policyBrief";

const userStore = useUserStore();
const isAdmin = computed(() => (userStore.userInfo.role || "") === "admin");

const briefDate = ref("");
const category = ref("全部");
const categoryOptions = ["全部", "政策法规", "医保DIP", "肛肠学术", "行业动态"];
const items = ref<PolicyBriefItem[]>([]);
const lastRun = ref<PolicyBriefLastRun | null>(null);
const loading = ref(false);
const collecting = ref(false);

const CATEGORY_LABELS: Record<string, string> = {
  POLICY: "政策法规",
  DIP: "医保DIP",
  ANORECTAL: "肛肠学术",
  GENERAL: "行业动态"
};
const categoryLabel = (value: string) => CATEGORY_LABELS[value] || "行业动态";
const categoryTagType = (value: string): "primary" | "warning" | "success" | "info" =>
  ({ POLICY: "primary", DIP: "warning", ANORECTAL: "success", GENERAL: "info" })[value] || "info";
const categoryParam = computed(() => CATEGORY_KEY_BY_LABEL[category.value] || "");
const CATEGORY_KEY_BY_LABEL: Record<string, string> = {
  全部: "",
  政策法规: "POLICY",
  医保DIP: "DIP",
  肛肠学术: "ANORECTAL",
  行业动态: "GENERAL"
};

const loadItems = async () => {
  if (!briefDate.value) return;
  loading.value = true;
  try {
    const { data } = await getPolicyBriefItemsApi(briefDate.value, categoryParam.value);
    items.value = data.items || [];
    if (data.lastRun) lastRun.value = data.lastRun;
  } catch (error) {
    ElMessage.error((error as Error).message || "医政早报加载失败");
  } finally {
    loading.value = false;
  }
};

const onCollect = async () => {
  collecting.value = true;
  try {
    const { data } = await triggerPolicyBriefCollectApi();
    ElMessage[data.started ? "success" : "warning"](data.message || "已触发采集");
    if (data.started) {
      const timer = window.setInterval(async () => {
        try {
          const { data: latest } = await getPolicyBriefLatestApi();
          lastRun.value = latest.lastRun || null;
          if (latest.lastRun && !latest.lastRun.running) {
            window.clearInterval(timer);
            await loadItems();
          }
        } catch {
          window.clearInterval(timer);
        }
      }, 10_000);
    }
  } catch (error) {
    ElMessage.error((error as Error).message || "触发采集失败");
  } finally {
    collecting.value = false;
  }
};

const openOriginal = (item: PolicyBriefItem) => {
  window.open(item.url, "_blank", "noopener,noreferrer");
};

onMounted(async () => {
  loading.value = true;
  try {
    const { data } = await getPolicyBriefLatestApi();
    lastRun.value = data.lastRun || null;
    briefDate.value = data.briefDate || new Date().toISOString().slice(0, 10);
  } catch {
    briefDate.value = new Date().toISOString().slice(0, 10);
  } finally {
    loading.value = false;
  }
  await loadItems();
});
</script>

<style scoped lang="scss">
.policy-brief {
  display: grid;
  gap: 12px;
}
.brief-head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  box-shadow: 0 10px 30px rgb(15 23 42 / 6%);
  .head-text {
    display: grid;
    gap: 3px;
    strong {
      font-size: 16px;
      color: var(--el-text-color-primary);
    }
    small {
      color: var(--el-text-color-secondary);
    }
  }
  .head-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }
}
.brief-status {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  &.running {
    color: var(--el-color-primary);
  }
}
.brief-list {
  display: grid;
  gap: 12px;
  min-height: 200px;
}
.brief-card {
  display: grid;
  gap: 8px;
  padding: 14px 18px;
  cursor: default;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  box-shadow: 0 10px 30px rgb(15 23 42 / 6%);
  transition:
    border-color var(--motion-control, 180ms) var(--ease-out, ease),
    box-shadow var(--motion-control, 180ms) var(--ease-out, ease);
  &:hover {
    border-color: var(--el-color-primary-light-5);
    box-shadow: var(--el-box-shadow-light);
  }
  .brief-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    small {
      color: var(--el-text-color-secondary);
    }
  }
  .brief-title {
    margin: 0;
    font-size: 15px;
    line-height: 1.55;
    color: var(--el-text-color-primary);
    cursor: pointer;
    &:hover {
      color: var(--el-color-primary);
    }
  }
  .brief-summary {
    margin: 0;
    font-size: 13px;
    line-height: 1.7;
    color: var(--el-text-color-regular);
  }
  .brief-foot a {
    display: inline-flex;
    gap: 3px;
    align-items: center;
    font-size: 12px;
    color: var(--el-color-primary);
    text-decoration: none;
    &:hover {
      text-decoration: underline;
    }
  }
}
.brief-disclaimer {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>
