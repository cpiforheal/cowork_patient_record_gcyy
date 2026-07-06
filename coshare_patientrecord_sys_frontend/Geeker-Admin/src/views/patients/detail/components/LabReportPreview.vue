<template>
  <div class="lab-report-preview" :class="{ compact }">
    <article v-for="report in visibleReports" :key="report.id" class="lab-report-preview-card">
      <header>
        <div>
          <strong>{{ report.name }}</strong>
          <span>{{ report.subtitle }}</span>
        </div>
        <el-tag :type="statusType(report.status)" effect="plain">{{ report.status }}</el-tag>
      </header>

      <div class="patient-line">
        <span>姓名：{{ patientName || "待选择" }}</span>
        <span>性别：{{ patientGender || "待补充" }}</span>
        <span>门诊/住院号：{{ visitNo || "待补充" }}</span>
      </div>

      <table v-if="report.metrics.length">
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
          <tr v-for="metric in report.metrics" :key="metric.key" :class="{ empty: !metric.value }">
            <td>{{ metric.name }}</td>
            <td>{{ metric.shortName }}</td>
            <td>{{ metric.value || "待补充" }}</td>
            <td>{{ metric.unit || "" }}</td>
            <td>{{ metric.reference || "按报告单" }}</td>
          </tr>
        </tbody>
      </table>

      <p v-else class="report-summary">{{ report.summary || "请上传图片或补充检查结论。" }}</p>
      <p v-if="report.summary && report.metrics.length" class="report-summary">{{ report.summary }}</p>
    </article>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import {
  labReportTemplates,
  metricReference,
  metricStoredValue,
  type LabTemplateDefinition
} from "@/views/workbench/labReport/templates";

const props = withDefaults(
  defineProps<{
    fieldValues: Record<string, string>;
    patientName?: string;
    patientGender?: string;
    visitNo?: string;
    compact?: boolean;
    showEmpty?: boolean;
  }>(),
  {
    patientName: "",
    patientGender: "",
    visitNo: "",
    compact: false,
    showEmpty: true
  }
);

const hasReportData = (template: LabTemplateDefinition) => {
  if (String(props.fieldValues[template.fieldKey] || "").trim()) return true;
  if (template.statusKeys.some(key => String(props.fieldValues[key] || "").trim())) return true;
  return template.metrics.some(metric => String(metricStoredValue(props.fieldValues, template.id, metric.key)).trim());
};

const reportStatus = (template: LabTemplateDefinition) => {
  const explicit = template.statusKeys.map(key => String(props.fieldValues[key] || "").trim()).find(Boolean);
  if (explicit) return explicit;
  return hasReportData(template) ? "已录入" : "待补充";
};

const statusType = (status: string) => {
  if (status.includes("异常") || status.includes("阳性")) return "danger";
  if (status.includes("已") || status.includes("录入")) return "success";
  return "info";
};

const visibleReports = computed(() =>
  labReportTemplates
    .filter(template => props.showEmpty || hasReportData(template))
    .map(template => ({
      id: template.id,
      name: template.name,
      subtitle: template.subtitle,
      status: reportStatus(template),
      summary: props.fieldValues[template.fieldKey] || "",
      metrics: template.metrics.map(metric => ({
        key: metric.key,
        name: metric.name,
        shortName: metric.shortName,
        unit: metric.unit,
        reference: metricReference(metric, props.patientGender),
        value: metricStoredValue(props.fieldValues, template.id, metric.key)
      }))
    }))
);
</script>

<style scoped lang="scss">
.lab-report-preview {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 14px;
}

.lab-report-preview.compact {
  grid-template-columns: 1fr;
}

.lab-report-preview-card {
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 14px;
  background: #ffffff;
  border: 1px solid #dce8f5;
  border-radius: 8px;
}

.lab-report-preview-card header {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  justify-content: space-between;
}

.lab-report-preview-card header div {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.lab-report-preview-card strong {
  color: #172554;
  font-size: 15px;
}

.lab-report-preview-card span,
.report-summary {
  color: #64748b;
  font-size: 12px;
}

.patient-line {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  padding: 8px 10px;
  background: #f8fafc;
  border-radius: 6px;
}

.lab-report-preview-card table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.lab-report-preview-card th,
.lab-report-preview-card td {
  padding: 7px 6px;
  text-align: center;
  border: 1px solid #dbe4ef;
}

.lab-report-preview-card th {
  color: #334155;
  background: #f1f5f9;
  font-weight: 700;
}

.lab-report-preview-card tr.empty td {
  color: #94a3b8;
}

.report-summary {
  margin: 0;
  line-height: 1.7;
}
</style>
