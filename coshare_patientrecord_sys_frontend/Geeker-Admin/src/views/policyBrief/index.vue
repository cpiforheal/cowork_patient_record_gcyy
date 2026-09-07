<template>
  <div class="policy-brief">
    <header class="brief-toolbar">
      <div class="head-actions">
        <el-date-picker
          v-model="briefDate"
          type="date"
          value-format="YYYY-MM-DD"
          :clearable="false"
          placeholder="选择日期"
          style="width: 136px"
          @change="loadItems"
        />
        <el-segmented v-model="category" :options="categoryOptions" size="default" @change="loadItems" />
        <el-button :loading="loading" @click="loadItems">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
        </el-button>
        <el-button v-if="isAdmin" type="primary" plain :loading="collecting" @click="onCollect">立即采集</el-button>
      </div>
      <p class="brief-status" :class="{ running: lastRun?.running }">
        <template v-if="lastRun?.running">⏳ 采集中…（约 1-3 分钟，完成后点「刷新」）</template>
        <template v-else-if="lastRun?.finishedAt"> 上次采集 {{ lastRun.finishedAt }} · {{ lastRun.message }} </template>
        <template v-else>今日尚未采集（每天 7:30 自动运行）</template>
      </p>
    </header>

    <div v-loading="loading" class="brief-report">
      <template v-if="!loading && items.length">
        <h2 class="report-title">医政早报 ｜ {{ briefDate }}</h2>
        <div class="report-divider" role="separator"></div>
        <ol class="report-list">
          <li v-for="(item, index) in mainItems" :key="item.id" class="report-item">
            <span class="report-text">
              <b class="report-index">{{ index + 1 }}.</b>
              {{ item.aiSummary || item.title }}
              <el-tag v-if="item.status === 'FAILED'" size="small" type="warning" effect="plain">摘要失败</el-tag>
            </span>
            <a class="report-link" :href="item.url" target="_blank" rel="noopener noreferrer">
              原文·{{ shortSource(item.sourceName) }} <el-icon><TopRight /></el-icon>
            </a>
          </li>
        </ol>
        <template v-if="hotItems.length">
          <div class="report-divider" role="separator"></div>
          <h3 class="report-hot-title">🔥 热点医疗<span>微博 / 今日头条聚合 · 最多 10 条</span></h3>
          <ol class="report-list report-hot">
            <li v-for="item in hotItems" :key="item.id" class="report-item">
              <span class="report-text">
                🔥 {{ item.aiSummary || item.title }}
                <el-tag v-if="item.status === 'FAILED'" size="small" type="warning" effect="plain">摘要失败</el-tag>
              </span>
              <a class="report-link" :href="item.url" target="_blank" rel="noopener noreferrer">
                原文·{{ shortSource(item.sourceName) }} <el-icon><TopRight /></el-icon>
              </a>
            </li>
          </ol>
        </template>
        <div class="report-divider" role="separator"></div>
        <p v-if="digest" class="report-digest">今日综述：{{ digest }}</p>
        <p class="report-footnote">摘要与综述由 AI 生成，仅供参考，政策内容以官方原文为准；点击每条末尾「原文」查看来源全文。</p>
      </template>
      <el-empty v-else-if="!loading" description="该日期暂无资讯；点上方「立即采集」或等每天 7:30 自动运行" :image-size="72" />
    </div>
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
const categoryOptions = ["全部", "医保DIP", "政策法规", "热点", "肛肠学术", "行业动态"];
const items = ref<PolicyBriefItem[]>([]);
const lastRun = ref<PolicyBriefLastRun | null>(null);
const digest = ref("");
const loading = ref(false);
const collecting = ref(false);

const CATEGORY_KEY_BY_LABEL: Record<string, string> = {
  全部: "",
  医保DIP: "DIP",
  政策法规: "POLICY",
  热点: "HOT",
  肛肠学术: "ANORECTAL",
  行业动态: "GENERAL"
};
const categoryParam = computed(() => CATEGORY_KEY_BY_LABEL[category.value] || "");

// 主列表与热点板块拆分（热点单列，不占编号）
const mainItems = computed(() => items.value.filter(item => item.category !== "HOT"));
const hotItems = computed(() => items.value.filter(item => item.category === "HOT"));

const shortSource = (sourceName: string) =>
  String(sourceName || "")
    .split("·")[0]
    .trim() || "来源";

const loadItems = async () => {
  if (!briefDate.value) return;
  loading.value = true;
  try {
    const { data } = await getPolicyBriefItemsApi(briefDate.value, categoryParam.value);
    items.value = data.items || [];
    if (data.lastRun) lastRun.value = data.lastRun;
    digest.value = data.digest || "";
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
.brief-toolbar {
  display: grid;
  gap: 6px;
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

// 早报版式：标题 + 分隔线 + 编号条目（尾部原文链接）+ 今日综述
.brief-report {
  min-height: 240px;
  padding: 26px 34px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  box-shadow: 0 10px 30px rgb(15 23 42 / 6%);
}
.report-title {
  margin: 0 0 12px;
  font-size: 19px;
  font-weight: 700;
  color: var(--el-text-color-primary);
}
.report-divider {
  margin: 14px 0;
  border-top: 1px dashed var(--el-border-color);
}
.report-list {
  display: grid;
  gap: 12px;
  padding: 0;
  margin: 0;
  list-style: none;
  .report-item {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 10px;
    align-items: baseline;
    justify-content: space-between;
    .report-text {
      flex: 1 1 480px;
      font-size: 14px;
      line-height: 1.8;
      color: var(--el-text-color-primary);
      .report-index {
        margin-right: 2px;
      }
    }
    .report-link {
      display: inline-flex;
      flex-shrink: 0;
      gap: 2px;
      align-items: center;
      font-size: 12px;
      color: var(--el-color-primary);
      text-decoration: none;
      &:hover {
        text-decoration: underline;
      }
    }
  }
}
.report-digest {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.8;
  color: var(--el-text-color-primary);
}
.report-hot-title {
  display: flex;
  gap: 10px;
  align-items: baseline;
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 700;
  color: var(--el-text-color-primary);
  span {
    font-size: 12px;
    font-weight: 400;
    color: var(--el-text-color-placeholder);
  }
}
.report-hot .report-text {
  color: var(--el-text-color-regular);
}
.report-footnote {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>
