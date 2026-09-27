<template>
  <section class="follow-view" v-loading="loading">
    <div class="follow-kpis">
      <article v-for="item in kpis" :key="item.label" class="follow-kpi" :class="item.tone"><span>{{ item.label }}</span><strong>{{ item.value }}<small v-if="item.unit">{{ item.unit }}</small></strong></article>
    </div>
    <div class="follow-grid">
      <article class="panel"><header class="panel-head"><div><h3>随访运营口径</h3><p>{{ stats?.from || period.from }} 至 {{ stats?.to || period.to }}</p></div><el-segmented v-model="basis" :options="basisOptions" @change="load" /></header><div class="rate-row"><div><span>完成率</span><strong>{{ stats?.completionRate ?? 0 }}%</strong></div><div><span>按时率</span><strong>{{ stats?.onTimeRate ?? 0 }}%</strong></div></div><div class="definition">完成以有效联系留痕为准；回院记录不会单独计为完成。按时率以节点规定日期为截止时间。</div></article>
      <article class="panel"><header class="panel-head"><div><h3>当前待处理</h3><p>点击任务进入随访工作台</p></div><el-button type="primary" plain @click="router.push('/follow-up-dashboard')">打开工作台</el-button></header><div class="task-list"><button v-for="row in tasks" :key="row.visitId" type="button" class="task-row" @click="router.push('/follow-up-dashboard')"><span><b>{{ row.name || '待补姓名' }}</b><small>{{ row.node || '复查节点' }} · {{ row.dueDate }}</small></span><el-tag :type="row.priority === 'CRITICAL' ? 'danger' : row.priority === 'TODAY' ? 'warning' : 'info'" size="small">{{ priorityLabel(row.priority) }}</el-tag></button><el-empty v-if="!tasks.length" description="当前没有待处理任务" :image-size="54" /></div></article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { loadFollowUpStatisticsApi, loadRecallSummaryApi, type FollowUpStatistics, type RecallRow } from "@/api/modules/clinic/followUp";

const router = useRouter();
const loading = ref(false);
const basis = ref<"due" | "contact">("due");
const stats = ref<FollowUpStatistics>();
const tasks = ref<RecallRow[]>([]);
const basisOptions = [{ label: "按节点到期", value: "due" }, { label: "按实际联系", value: "contact" }];
const period = computed(() => { const now = new Date(); const from = new Date(now.getFullYear(), now.getMonth(), 1); const fmt = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`; return { from: fmt(from), to: fmt(now) }; });
const kpis = computed(() => [
  { label: "应随访节点", value: stats.value?.total ?? 0, unit: "个", tone: "" },
  { label: "已完成联系", value: stats.value?.completed ?? 0, unit: "个", tone: "is-success" },
  { label: "严重逾期", value: stats.value?.overduePending ?? 0, unit: "个", tone: "is-danger" },
  { label: "未排期", value: 0, unit: "个", tone: "is-warning" }
]);
const priorityLabel = (value?: string) => value === "CRITICAL" ? "严重逾期" : value === "TODAY" ? "今日" : value === "TOMORROW" ? "明日" : "待处理";
const load = async () => { loading.value = true; try { const [{ data: next }, { data: recall }] = await Promise.all([loadFollowUpStatisticsApi({ ...period.value, basis: basis.value }), loadRecallSummaryApi()]); stats.value = next; tasks.value = [...(recall.overdue || []), ...(recall.dueSoon || [])].slice(0, 12); } catch (error: any) { ElMessage.warning(error?.message || "随访运营数据加载失败"); } finally { loading.value = false; } };
onMounted(() => void load());
</script>

<style scoped lang="scss">
.follow-view { display: grid; gap: 14px; }.follow-kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }.follow-kpi { display: grid; gap: 8px; padding: 16px; background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }.follow-kpi span { color: var(--el-text-color-secondary); font-size: 12px; }.follow-kpi strong { font-size: 28px; color: var(--el-text-color-primary); }.follow-kpi small { margin-left: 4px; font-size: 12px; font-weight: 400; }.follow-kpi.is-success strong { color: #3e8f64; }.follow-kpi.is-danger strong { color: #b94d45; }.follow-kpi.is-warning strong { color: #b7791f; }.follow-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 14px; }.panel { padding: 16px; background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }.panel-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 14px; }.panel-head h3 { margin: 0; font-size: 15px; }.panel-head p { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 12px; }.rate-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }.rate-row div { padding: 16px; background: var(--el-fill-color-light); border-radius: 6px; }.rate-row span { display: block; color: var(--el-text-color-secondary); font-size: 12px; }.rate-row strong { display: block; margin-top: 6px; font-size: 30px; color: #176b87; }.definition { margin-top: 14px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.7; }.task-list { display: grid; gap: 6px; }.task-row { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding: 10px; text-align: left; background: transparent; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; cursor: pointer; }.task-row:hover { border-color: var(--el-color-primary); }.task-row b, .task-row small { display: block; }.task-row small { margin-top: 3px; color: var(--el-text-color-secondary); font-size: 12px; }@media (max-width: 800px) { .follow-kpis, .follow-grid { grid-template-columns: 1fr 1fr; } }@media (max-width: 520px) { .follow-kpis, .follow-grid { grid-template-columns: 1fr; } }
</style>

