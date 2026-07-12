<template>
  <section class="stage-panel auxiliary-panel">
    <div class="panel-heading">
      <h3>化验室检验报告</h3>
      <el-button v-if="canOpenWorkbench" type="primary" @click="$emit('open-workbench')">填写报告</el-button>
    </div>
    <el-alert
      v-if="labTask?.status === 'RETURNED'"
      type="warning"
      show-icon
      :closable="false"
      title="医生已退回化验室，请补充或更正报告后重新完成交接。"
    />
    <el-empty v-if="!workspace.labReports.length" :image-size="72" description="尚未保存检验报告，请进入化验报告模板填写" />
    <el-tabs
      v-else
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
                v-for="metric in report.metrics"
                :key="metric.key"
                :class="{
                  'abnormal-metric': isMetricAbnormal(metric),
                  'critical-metric': metric.severity === 'CRITICAL' || metric.critical
                }"
              >
                <td>{{ metric.name }}</td>
                <td>{{ metric.shortName }}</td>
                <td>
                  <strong>{{ metric.value }}</strong>
                  <el-tag v-if="metric.severity === 'CRITICAL' || metric.critical" type="danger" size="small" effect="dark">
                    危急值
                  </el-tag>
                  <el-tag v-else-if="isMetricAbnormal(metric)" type="warning" size="small" effect="dark">
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
    <footer class="panel-actions compact-actions">
      <el-button
        v-if="canReview && labTask?.status === 'COMPLETED'"
        type="warning"
        plain
        @click="$emit('return-task', labTask.id)"
      >
        退回化验室
      </el-button>
      <div></div>
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
import type { LabReportMetricSnapshot, PreAiAuxiliaryTask, PreAiWorkspace } from "@/api/modules/clinic";

defineProps<{
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
}>();

defineEmits<{
  "update:activeReportId": [value: string];
  "open-workbench": [];
  "return-task": [taskId: string];
  complete: [];
}>();
</script>

<style scoped lang="scss">
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
.compact-actions {
  margin-top: 10px;
}
.lab-report-tabs {
  margin-top: 14px;
}
.lab-report-paper {
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
    padding: 14px;
    overflow-x: auto;
  }
}
</style>
