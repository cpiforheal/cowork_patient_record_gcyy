<template>
  <section class="stage-panel auxiliary-panel">
    <div class="panel-heading">
      <h3>化验检验报告</h3>
      <el-button v-if="canOpenWorkbench" type="primary" @click="$emit('open-workbench')">填写报告</el-button>
    </div>
    <el-alert
      v-if="labTask?.status === 'RETURNED'"
      type="warning"
      show-icon
      :closable="false"
      title="医生已退回检验报告，请补充或更正报告后重新完成交接。"
    />
    <el-empty v-if="!workspace.labReports.length" :image-size="72" description="尚未保存检验报告，请进入化验报告模板填写" />
    <div v-else class="lab-report-body">
      <el-tabs
        :model-value="activeReportId"
        class="lab-report-tabs"
        @update:model-value="$emit('update:activeReportId', String($event))"
      >
        <el-tab-pane
          v-for="report in workspace.labReports"
          :key="report.id"
          :name="report.id"
          :label="`${report.templateName} · ${report.reportDate}`"
        >
          <article class="lab-report-paper">
            <header>
              <h3>固始中医肛肠医院检验报告单</h3>
              <p>{{ report.templateName }}</p>
            </header>
            <div class="lab-patient-line">
              <span>姓名：{{ workspace.encounter.patient.patientName }}</span>
              <span>性别：{{ workspace.encounter.patient.gender || "待补充" }}</span>
              <span>病例标识：{{ workspace.encounter.caseToken }}</span>
              <span>日期：{{ report.reportDate }}</span>
            </div>
            <table>
              <thead>
                <tr>
                  <th>项目</th>
                  <th>简称</th>
                  <th>结果</th>
                  <th>单位</th>
                  <th>参考范围</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="metric in materializeLabMetrics(report.templateId, report.metrics, workspace.encounter.patient.gender)"
                  :key="metric.key"
                  :class="{
                    'abnormal-metric': isMetricAbnormal(metric),
                    'critical-metric': metric.severity === 'CRITICAL' || metric.critical
                  }"
                >
                  <td>{{ metric.name }}</td>
                  <td>{{ metric.shortName }}</td>
                  <td>
                    <strong>{{ metric.value || "待补充" }}</strong>
                    <el-tag v-if="metric.severity === 'CRITICAL' || metric.critical" type="danger" size="small" effect="dark">
                      危急值
                    </el-tag>
                    <el-tag v-else-if="metric.value && isMetricAbnormal(metric)" type="warning" size="small" effect="dark">
                      {{ abnormalLabel(metric) }}
                    </el-tag>
                  </td>
                  <td>{{ metric.unit }}</td>
                  <td>{{ metric.reference }}</td>
                </tr>
              </tbody>
            </table>
            <footer>
              <span>报告版本：v{{ report.version }}</span
              ><span>备注：{{ report.remark || "无" }}</span>
              <el-button v-if="canDeleteReport" type="danger" plain size="small" @click="emit('delete-report', report.id)">
                删除该报告
              </el-button>
            </footer>
          </article>
        </el-tab-pane>
      </el-tabs>
      <section v-if="legacyTasks.length" class="legacy-auxiliary">
        <div class="section-caption">旧辅助资料（只读保留）</div>
        <div v-for="task in legacyTasks" :key="task.id" class="read-only-grid">
          <div>
            <span>{{ taskLabel[task.taskType] }}</span>
            <p>{{ humanValue(task.data) }}</p>
          </div>
        </div>
      </section>
    </div>
    <footer class="panel-actions compact-actions sticky-actions">
      <el-button
        v-if="canReview && labTask?.status === 'COMPLETED'"
        type="warning"
        plain
        @click="$emit('return-task', labTask.id)"
      >
        退回检验报告
      </el-button>
      <div></div>
      <span v-if="isPublisherRole" class="lab-portal-sync">
        患者端：
        <el-tag size="small" :type="syncTone">{{ syncLabel }}</el-tag>
        <el-button link size="small" @click="loadSync">刷新</el-button>
        <el-button link size="small" @click="resync">重新同步</el-button>
        <el-button v-if="canRevoke" link size="small" type="danger" @click="revokeSync">撤回</el-button>
      </span>
      <el-button
        v-if="canComplete"
        type="primary"
        :loading="loading"
        :disabled="!workspace.labReports.length || labTask?.status === 'COMPLETED'"
        @click="$emit('complete')"
        >完成并交接</el-button
      >
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { LabReportMetricSnapshot, PreAiAuxiliaryTask, PreAiWorkspace } from "@/api/modules/clinic";
import { materializeLabMetrics } from "@/views/workbench/labReport/templates";
import { useUserStore } from "@/stores/modules/user";
import { ElMessage, ElMessageBox } from "element-plus";
import { getLabPortalSyncApi, revokeLabPortalSyncApi, resyncLabPortalApi, type LabPortalSyncStatus } from "@/api/modules/clinic/preAi";

const props = defineProps<{
  workspace: PreAiWorkspace;
  labTask?: PreAiAuxiliaryTask;
  legacyTasks: PreAiAuxiliaryTask[];
  activeReportId: string;
  canOpenWorkbench: boolean;
  canReview: boolean;
  canComplete: boolean;
  loading: boolean;
  taskLabel: Record<string, string>;
  humanValue: (value: any) => string;
  abnormalLabel: (metric: LabReportMetricSnapshot) => string;
  isMetricAbnormal: (metric: LabReportMetricSnapshot) => boolean;
  /** 可删除化验报告（化验岗/管理员） */
  canDeleteReport: boolean;
}>();

const emit = defineEmits<{
  "update:activeReportId": [value: string];
  "open-workbench": [];
  "return-task": [taskId: string];
  "delete-report": [reportId: string];
  complete: [];
}>();

const userStore = useUserStore();
const activeReport = computed(
  () => props.workspace.labReports.find(report => report.id === props.activeReportId) || props.workspace.labReports[0]
);
const isPublisherRole = computed(() => ["admin", "doctor"].includes(userStore.userInfo.role || ""));
const syncStatus = ref<LabPortalSyncStatus>();
const syncLoading = ref(false);
const syncLabel = computed(() => {
  if (syncLoading.value) return "加载中";
  const status = syncStatus.value?.status;
  return (
    {
      NOT_SYNCED: "待化验完成",
      WAITING: "待化验完成",
      PENDING: "待推送",
      SENT: `已同步${syncStatus.value?.reportCount ? ` ${syncStatus.value.reportCount} 份` : ""}`,
      FAILED: "推送失败",
      NO_IDENTITY: "缺少身份证和手机号"
    }[status || "NOT_SYNCED"] || "待化验完成"
  );
});
const syncTone = computed<"success" | "warning" | "danger" | "info">(() => {
  const status = syncStatus.value?.status;
  if (status === "SENT") return "success";
  if (status === "FAILED" || status === "NO_IDENTITY") return "danger";
  if (status === "PENDING") return "warning";
  return "info";
});
const canRevoke = computed(() => ["SENT", "PENDING", "FAILED"].includes(syncStatus.value?.status || ""));

const loadSync = async () => {
  const encounterId = props.workspace.encounter.id;
  if (!encounterId || !isPublisherRole.value) return;
  syncLoading.value = true;
  try {
    const { data } = await getLabPortalSyncApi(encounterId);
    syncStatus.value = data;
  } catch {
    syncStatus.value = undefined;
  } finally {
    syncLoading.value = false;
  }
};

const revokeSync = async () => {
  const encounterId = props.workspace.encounter.id;
  try {
    await ElMessageBox.confirm("撤回后患者将立即无法查看本次报告，是否继续？", "撤回患者可见", {
      type: "warning",
      confirmButtonText: "撤回",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  try {
    await revokeLabPortalSyncApi(encounterId);
    ElMessage.success("已撤回患者可见报告");
    await loadSync();
  } catch (error: any) {
    ElMessage.error(error?.message || "撤回失败");
  }
};

const resync = async () => {
  const encounterId = props.workspace.encounter.id;
  try {
    await resyncLabPortalApi(encounterId);
    ElMessage.success("已重新同步，稍后自动推送");
    await loadSync();
  } catch (error: any) {
    ElMessage.error(error?.message || "重新同步失败");
  }
};

watch(
  () => [props.workspace.encounter.id, props.labTask?.status, props.workspace.labReports.length] as const,
  () => void loadSync(),
  { immediate: true }
);
</script>

<style scoped lang="scss">
.auxiliary-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  max-height: calc(100vh - 156px);
  overflow: hidden;
}
.panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}
.panel-heading h3 {
  margin: 0 0 6px;
}
.panel-heading p {
  margin: 0;
  color: var(--el-text-color-secondary);
}
.panel-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 18px;
}
.panel-actions > div {
  flex: 1 1 auto;
}
.panel-actions :deep(.el-button) {
  margin-left: 0;
}
.lab-portal-sync {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.compact-actions {
  flex: 0 0 auto;
  margin-top: 10px;
}
.lab-report-body {
  min-height: 0;
  flex: 1 1 auto;
  overflow: auto;
  overscroll-behavior: contain;
}
.lab-report-tabs {
  min-height: 0;
}
.lab-report-tabs :deep(.el-tabs__content),
.lab-report-tabs :deep(.el-tab-pane) {
  min-height: 0;
}
.lab-report-tabs :deep(.el-tabs__content) {
  overflow: visible;
}
.sticky-actions {
  position: sticky;
  z-index: 9;
  bottom: 10px;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
  background: color-mix(in srgb, var(--el-bg-color) 94%, transparent);
  box-shadow: 0 -8px 26px rgb(31 78 120 / 10%);
  backdrop-filter: blur(10px);
}
.lab-report-tabs {
  margin-top: 14px;
}
.lab-report-paper {
  min-width: 680px;
  padding: 22px;
  color: #1f2937;
  border: 1px solid #d6dce5;
  background: #fff;
  box-shadow: 0 10px 24px rgb(15 23 42 / 8%);
}
.lab-report-paper header {
  display: block;
  margin-bottom: 14px;
  text-align: center;
}
.lab-report-paper h3,
.lab-report-paper p {
  margin: 0 0 6px;
}
.lab-patient-line {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  padding: 10px 0;
  font-size: 13px;
}
.lab-report-paper table {
  width: 100%;
  border-collapse: collapse;
}
.lab-report-paper th,
.lab-report-paper td {
  padding: 8px;
  text-align: center;
  border: 1px solid #4b5563;
}
.lab-report-paper tr.abnormal-metric td {
  color: var(--el-color-warning-dark-2);
  background: var(--el-color-warning-light-9);
}
.lab-report-paper tr.critical-metric td {
  color: var(--el-color-danger-dark-2);
  background: var(--el-color-danger-light-9);
}
.lab-report-paper tr.abnormal-metric td:nth-child(3) {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
}
.lab-report-paper footer {
  display: flex;
  justify-content: space-between;
  margin-top: 14px;
}
.legacy-auxiliary {
  margin-top: 18px;
}
.section-caption {
  margin-bottom: 10px;
  font-weight: 700;
}
.read-only-grid {
  display: grid;
  gap: 10px;
}
.read-only-grid span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.read-only-grid p {
  margin: 4px 0 0;
  white-space: pre-wrap;
}
@media (max-width: 680px) {
  .panel-heading {
    flex-direction: column;
  }
  .panel-heading :deep(.el-button),
  .panel-actions :deep(.el-button) {
    width: 100%;
    margin-left: 0;
  }
  .panel-actions > div {
    display: none;
  }
  .lab-patient-line {
    grid-template-columns: 1fr 1fr;
  }
  .lab-report-paper {
    min-width: 0;
    padding: 8px;
    font-size: 12px;
    overflow-x: auto;
  }
  .lab-report-paper h3 {
    font-size: 15px;
  }
  .lab-report-paper th,
  .lab-report-paper td {
    padding: 4px 3px;
    font-size: 11px;
  }
  .lab-report-paper footer {
    flex-direction: column;
    gap: 3px;
  }
}
</style>
