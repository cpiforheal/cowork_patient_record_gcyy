<template>
  <div class="patient-archive-page">
    <!-- 患者档案统一入口：把原先并列的「患者概览 / 患者档案查询」收敛为一个页面内的视图切换。
         两个视图都是既有成品页，此处只做入口合并与视图切换，不改写其内部实现。 -->
    <header class="pa-head">
      <div>
        <span class="pa-eyebrow">患者就诊</span>
        <h2>患者档案</h2>
        <p>卡片视图便于快速浏览与跟进，列表视图便于按条件检索与批量核对；点任一条目进入完整档案详情。</p>
      </div>
      <el-radio-group :model-value="view" size="small" @change="switchView">
        <el-radio-button value="cards">
          <el-icon><Grid /></el-icon>卡片视图
        </el-radio-button>
        <el-radio-button value="list">
          <el-icon><Tickets /></el-icon>列表视图
        </el-radio-button>
      </el-radio-group>
    </header>

    <div class="pa-body">
      <!-- keep-alive 保留来回切换时的检索条件与滚动位置 -->
      <KeepAlive>
        <component :is="activeView" />
      </KeepAlive>
    </div>
  </div>
</template>

<script setup lang="ts" name="patientArchive">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Grid, Tickets } from "@element-plus/icons-vue";
import PatientOverview from "@/views/patients/overview/index.vue";
import PatientList from "@/views/patients/list/index.vue";

const route = useRoute();
const router = useRouter();

const VIEWS = { cards: PatientOverview, list: PatientList } as const;
type ViewKey = keyof typeof VIEWS;

const view = computed<ViewKey>(() => (route.query.view === "list" ? "list" : "cards"));
const activeView = computed(() => VIEWS[view.value]);

/** 切换视图只改 view 参数，保留 date / month 等既有筛选条件（深链兼容） */
const switchView = (next: string | number | boolean | undefined) => {
  const target = next === "list" ? "list" : "cards";
  void router.replace({ path: "/patients/archive", query: { ...route.query, view: target } });
};
</script>

<style scoped lang="scss">
.patient-archive-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.pa-head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
  justify-content: space-between;
  padding: 16px 16px 12px;

  .pa-eyebrow {
    font-size: 11px;
    font-weight: 600;
    color: var(--el-color-primary);
    letter-spacing: 0.08em;
  }

  h2 {
    margin: 4px 0 0;
    font-size: 20px;
    font-weight: 700;
    color: var(--el-text-color-primary);
  }

  p {
    margin: 4px 0 0;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}

.pa-body {
  flex: 1;
  min-height: 0;
}
</style>
