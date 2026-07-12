import type { LabReportSnapshot, PreAiStageCode, PreAiWorkspace } from "@/api/modules/clinic";
import type { PreAiStageConfig } from "../fieldConfig";
import type { DocumentPreviewLabMetric, DocumentPreviewLabReport, DocumentPreviewSection } from "../previewTypes";
import { labMetricAbnormalLabel } from "./labResult";

export const nonEmptyEntries = (value: Record<string, any> = {}) =>
  Object.entries(value).filter(
    ([, item]) => item !== undefined && item !== null && item !== "" && (!Array.isArray(item) || item.length)
  );

export const humanValue = (value: any) =>
  Array.isArray(value) ? value.join("、") : typeof value === "object" ? JSON.stringify(value) : String(value ?? "");

export const buildLabPreviewReports = (reports: LabReportSnapshot[]): DocumentPreviewLabReport[] =>
  reports
    .map(report => {
      const metrics = report.metrics
        .filter(metric => String(metric.value || "").trim())
        .map<DocumentPreviewLabMetric>((metric, index) => {
          const abnormal = labMetricAbnormalLabel(metric);
          return {
            key: `${report.id}-${metric.shortName || metric.name || index}`,
            name: metric.name || "未命名指标",
            shortName: metric.shortName || "",
            value: String(metric.value || "未填写"),
            unit: metric.unit || "",
            reference: metric.reference || "",
            abnormal,
            severity: metric.severity || (metric.critical ? "CRITICAL" : abnormal ? "ABNORMAL" : "NORMAL")
          };
        });

      return {
        key: report.id,
        title: report.templateName,
        reportDate: report.reportDate,
        abnormalMetrics: metrics.filter(metric => Boolean(metric.abnormal)),
        normalMetrics: metrics.filter(metric => !metric.abnormal)
      };
    })
    .filter(report => report.abnormalMetrics.length || report.normalMetrics.length);

interface DocumentPreviewBuilderOptions {
  workspace: PreAiWorkspace;
  stageForms: Record<PreAiStageCode, Record<string, any>>;
  reviewStatement: string;
  stageByCode: (code: PreAiStageCode) => PreAiStageConfig;
}

const routeLabel = (route?: string) => (route === "OUTPATIENT" ? "门诊" : route === "INPATIENT" ? "住院" : "分支待确认");

const treatmentPathLabel = (path?: string) =>
  path === "CONSERVATIVE" ? "保守治疗" : path === "SURGICAL" ? "手术治疗" : "方案待确认";

const formatPreviewValue = (key: string, value: any) => {
  if (value === undefined || value === null || value === "" || (Array.isArray(value) && !value.length)) return "________________";
  if (["finalRoute", "dispositionSuggestion"].includes(key)) return routeLabel(String(value));
  if (key === "treatmentPath") return treatmentPathLabel(String(value));
  if (key === "examinationTypes" && Array.isArray(value)) {
    const labels: Record<string, string> = {
      VISUAL: "外观检查",
      DIGITAL: "指检",
      ANOSCOPY: "肛门镜/镜下检查",
      OTHER: "其他检查"
    };
    return value.map(item => labels[item] || item).join("、");
  }
  return humanValue(value);
};

const buildStageSection = (
  code: PreAiStageCode,
  title: string,
  stageForms: Record<PreAiStageCode, Record<string, any>>,
  stageByCode: (code: PreAiStageCode) => PreAiStageConfig,
  note = "",
  excludedKeys: string[] = []
): DocumentPreviewSection => {
  const form = stageForms[code];
  const rows = stageByCode(code)
    .fields.filter(field => !excludedKeys.includes(field.key))
    .filter(field => !field.visible || field.visible(form))
    .filter(field => {
      const value = form[field.key];
      return value !== undefined && value !== null && value !== "" && (!Array.isArray(value) || value.length > 0);
    })
    .map(field => ({
      key: `${code}-${field.key}`,
      label: field.label,
      value: formatPreviewValue(field.key, form[field.key]),
      empty: false,
      wide: field.span === 2 || field.kind === "textarea" || field.kind === "multi"
    }));
  return { key: code, title, note, rows };
};

export const buildDocumentPreviewSections = ({
  workspace,
  stageForms,
  reviewStatement,
  stageByCode
}: DocumentPreviewBuilderOptions): DocumentPreviewSection[] => {
  const sections: DocumentPreviewSection[] = [
    buildStageSection("REGISTRATION", "患者基础信息", stageForms, stageByCode, "身份信息仅用于院内协作，正式导出时自动脱敏。", [
      "identityNumber"
    ]),
    buildStageSection("RECEPTION", "主诉和现病情况", stageForms, stageByCode),
    buildStageSection("INSPECTION", "专科检查事实", stageForms, stageByCode, "原始图片不进入导出文档，仅呈现确认后的文字所见。")
  ];

  sections.push({
    key: "AUX",
    title: "化验室检验报告",
    note: "异常指标优先展示；正常指标默认收起，可按需展开核对。",
    rows: [],
    labReports: buildLabPreviewReports(workspace.labReports)
  });
  sections.push(buildStageSection("TCM", "中医四诊、病名、证候和治法", stageForms, stageByCode));
  sections.push(buildStageSection("DOCTOR", "西医诊断与治疗方案", stageForms, stageByCode));

  const surgeryData = stageForms.SURGERY;
  const showSurgery =
    workspace.encounter.treatmentPath === "SURGICAL" ||
    Object.values(surgeryData).some(value => value !== undefined && value !== null && value !== "");
  if (showSurgery) sections.push(buildStageSection("SURGERY", "实际手术情况", stageForms, stageByCode));

  sections.push({
    key: "REVIEW",
    title: "医生复核信息",
    rows: [
      {
        key: "reviewStatus",
        label: "复核状态",
        value: workspace.encounter.reviewedAt ? "已复核" : "待医生复核",
        empty: !workspace.encounter.reviewedAt,
        wide: false
      },
      {
        key: "reviewedAt",
        label: "复核时间",
        value: workspace.encounter.reviewedAt || "________________",
        empty: !workspace.encounter.reviewedAt,
        wide: false
      },
      {
        key: "reviewStatement",
        label: "复核说明",
        value: reviewStatement || stageForms.REVIEW.reviewStatement || "________________",
        empty: !(reviewStatement || stageForms.REVIEW.reviewStatement),
        wide: true
      }
    ]
  });

  return sections;
};
