import { labReportTemplates, metricStoredValue } from "./templates";

const incompleteValues = new Set(["", "待补充", "未见记录", "未查"]);

const clean = (value?: string) => {
  const text = String(value || "").trim();
  return incompleteValues.has(text) ? "" : text;
};

export const hasLabReportData = (values: Record<string, string> = {}) =>
  labReportTemplates.some(template => {
    if (clean(values[template.fieldKey])) return true;
    if (template.statusKeys.some(key => clean(values[key]))) return true;
    return template.metrics.some(metric => clean(metricStoredValue(values, template.id, metric.key)));
  });

export const buildLabReportSummary = (values: Record<string, string> = {}) => {
  const summaries = labReportTemplates
    .map(template => {
      if (template.id === "ecgImage") {
        const ecg = clean(values.ecgResult || values.ecgStatus);
        return ecg ? `心电图：${ecg}` : "";
      }

      const metrics = template.metrics
        .map(metric => {
          const value = clean(metricStoredValue(values, template.id, metric.key));
          return value ? `${metric.shortName || metric.name} ${value}${metric.unit || ""}` : "";
        })
        .filter(Boolean);

      if (metrics.length) return `${template.name}：${metrics.join("，")}`;
      return clean(values[template.fieldKey]);
    })
    .filter(Boolean);

  return summaries.join("；");
};
