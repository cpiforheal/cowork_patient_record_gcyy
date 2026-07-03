export type LabTemplateId =
  | "bloodRoutine"
  | "biochemistry"
  | "hbvFive"
  | "infectious"
  | "crpSaa"
  | "urineRoutine"
  | "hba1c"
  | "ecgImage";

export type LabMetricInput = "number" | "text" | "select";

export interface LabMetricDefinition {
  key: string;
  name: string;
  shortName: string;
  unit?: string;
  input: LabMetricInput;
  reference?: string;
  maleReference?: string;
  femaleReference?: string;
  options?: string[];
  defaultValue?: string;
}

export interface LabTemplateDefinition {
  id: LabTemplateId;
  name: string;
  subtitle: string;
  fieldKey: string;
  statusKeys: string[];
  documentType: string;
  documentTypeLabel: string;
  description: string;
  metrics: LabMetricDefinition[];
}

export const qualitativeOptions = ["阴性", "阳性", "弱阳性", "可疑", "未查"];

export const labReportTemplates: LabTemplateDefinition[] = [
  {
    id: "bloodRoutine",
    name: "血常规五分类",
    subtitle: "WBC / NeU% / Lym% / Mon% / RBC / HGB / PLT",
    fieldKey: "bloodRoutine",
    statusKeys: ["bloodRoutineStatus"],
    documentType: "bloodRoutine",
    documentTypeLabel: "血常规五分类模板报告",
    description: "复刻血常规五分类报告，医生端优先查看 WBC、NeU%、Lym%、Mon%、RBC、HGB、PLT 等核心指标。",
    metrics: [
      { key: "wbc", name: "白细胞数目", shortName: "WBC", unit: "10^9/L", input: "number", reference: "4.00-10.00" },
      { key: "neuPercent", name: "中性粒细胞百分比", shortName: "NeU%", unit: "%", input: "number", reference: "50.0-70.0" },
      { key: "lymPercent", name: "淋巴细胞百分比", shortName: "Lym%", unit: "%", input: "number", reference: "20.0-40.0" },
      { key: "monPercent", name: "单核细胞百分比", shortName: "Mon%", unit: "%", input: "number", reference: "3.0-12.0" },
      { key: "rbc", name: "红细胞数目", shortName: "RBC", unit: "10^12/L", input: "number", reference: "3.50-5.50" },
      { key: "hgb", name: "血红蛋白", shortName: "HGB", unit: "g/L", input: "number", reference: "110-160" },
      { key: "plt", name: "血小板数目", shortName: "PLT", unit: "10^9/L", input: "number", reference: "100-300" },
      { key: "neuCount", name: "中性粒细胞数目", shortName: "NeU#", unit: "10^9/L", input: "number", reference: "2.00-7.00" },
      { key: "lymCount", name: "淋巴细胞数目", shortName: "Lym#", unit: "10^9/L", input: "number", reference: "0.80-4.00" },
      { key: "monCount", name: "单核细胞数目", shortName: "Mon#", unit: "10^9/L", input: "number", reference: "0.12-1.20" }
    ]
  },
  {
    id: "biochemistry",
    name: "生化肝肾功",
    subtitle: "肝功能 / 肾功能 / 电解质 / 血脂 / 血糖",
    fieldKey: "biochemistry",
    statusKeys: ["liverFunctionStatus", "renalFunctionStatus", "fastingGlucoseStatus", "bloodLipidStatus"],
    documentType: "labBiochemistry",
    documentTypeLabel: "生化肝肾功模板报告",
    description: "复刻参考单中的生化项目，肌酐、尿酸等参考范围按性别自动切换。",
    metrics: [
      { key: "glu", name: "葡萄糖", shortName: "Glu", unit: "mmol/L", input: "number", reference: "3.9-6.1" },
      { key: "tbil", name: "总胆红素", shortName: "T-Bil", unit: "umol/L", input: "number", reference: "5.0-21.0" },
      { key: "dbil", name: "直接胆红素", shortName: "D-Bil", unit: "umol/L", input: "number", reference: "0-6.8" },
      { key: "alt", name: "谷丙转氨酶", shortName: "ALT", unit: "U/L", input: "number", reference: "7-40" },
      { key: "ast", name: "谷草转氨酶", shortName: "AST", unit: "U/L", input: "number", reference: "13-35" },
      { key: "alp", name: "碱性磷酸酶", shortName: "ALP", unit: "U/L", input: "number", reference: "35-100" },
      { key: "ggt", name: "γ-谷氨酰转肽酶", shortName: "γ-GT", unit: "U/L", input: "number", reference: "7-45" },
      { key: "tp", name: "总蛋白", shortName: "TP", unit: "g/L", input: "number", reference: "65-85" },
      { key: "alb", name: "白蛋白", shortName: "ALB", unit: "g/L", input: "number", reference: "40-55" },
      { key: "glo", name: "球蛋白", shortName: "Glo", unit: "g/L", input: "number", reference: "20-40" },
      { key: "ag", name: "白球比", shortName: "A/G", input: "number", reference: "1.2-2.4" },
      { key: "tg", name: "甘油三酯", shortName: "TG", unit: "mmol/L", input: "number", reference: "0-1.70" },
      { key: "tc", name: "总胆固醇", shortName: "TC", unit: "mmol/L", input: "number", reference: "0-5.18" },
      {
        key: "crea",
        name: "肌酐",
        shortName: "CREA",
        unit: "umol/L",
        input: "number",
        maleReference: "57-97",
        femaleReference: "41-73"
      },
      {
        key: "ua",
        name: "尿酸",
        shortName: "UA",
        unit: "umol/L",
        input: "number",
        maleReference: "208-428",
        femaleReference: "155-357"
      },
      { key: "urea", name: "尿素", shortName: "UREA", unit: "mmol/L", input: "number", reference: "2.9-8.2" },
      { key: "k", name: "钾", shortName: "K", unit: "mmol/L", input: "number", reference: "3.5-5.3" },
      { key: "na", name: "钠", shortName: "Na", unit: "mmol/L", input: "number", reference: "137-147" },
      { key: "cl", name: "氯", shortName: "CL", unit: "mmol/L", input: "number", reference: "99-110" },
      { key: "ca", name: "钙", shortName: "Ca", unit: "mmol/L", input: "number", reference: "2.11-2.52" }
    ]
  },
  {
    id: "hbvFive",
    name: "乙肝五项",
    subtitle: "乙肝表面抗原 / 抗体 / e抗原 / e抗体 / 核心抗体",
    fieldKey: "preOpEight",
    statusKeys: ["preOpEightStatus"],
    documentType: "labHbvFive",
    documentTypeLabel: "乙肝五项模板报告",
    description: "用于术前八项中的乙肝五项结果记录。",
    metrics: [
      {
        key: "hbsag",
        name: "乙肝表面抗原",
        shortName: "HBsAg",
        input: "select",
        options: qualitativeOptions,
        defaultValue: "阴性"
      },
      {
        key: "hbsab",
        name: "乙肝表面抗体",
        shortName: "HBsAb",
        input: "select",
        options: qualitativeOptions,
        defaultValue: "阴性"
      },
      { key: "hbeag", name: "乙肝e抗原", shortName: "HBeAg", input: "select", options: qualitativeOptions, defaultValue: "阴性" },
      { key: "hbeab", name: "乙肝e抗体", shortName: "HBeAb", input: "select", options: qualitativeOptions, defaultValue: "阴性" },
      {
        key: "hbcab",
        name: "乙肝核心抗体",
        shortName: "HBcAb",
        input: "select",
        options: qualitativeOptions,
        defaultValue: "阴性"
      }
    ]
  },
  {
    id: "infectious",
    name: "术前感染筛查",
    subtitle: "HIV / 梅毒 / 丙肝",
    fieldKey: "preOpEight",
    statusKeys: ["preOpEightStatus"],
    documentType: "labInfectious",
    documentTypeLabel: "术前感染筛查模板报告",
    description: "用于术前筛查中的 HIV、TPPA、HCV 结果记录。",
    metrics: [
      {
        key: "hiv",
        name: "人免疫缺陷病毒抗体",
        shortName: "HIV",
        input: "select",
        options: qualitativeOptions,
        defaultValue: "阴性"
      },
      {
        key: "tppa",
        name: "梅毒螺旋体抗体",
        shortName: "TPPA",
        input: "select",
        options: qualitativeOptions,
        defaultValue: "阴性"
      },
      {
        key: "hcv",
        name: "丙型肝炎病毒抗体",
        shortName: "HCV",
        input: "select",
        options: qualitativeOptions,
        defaultValue: "阴性"
      }
    ]
  },
  {
    id: "crpSaa",
    name: "CRP/SAA",
    subtitle: "C反应蛋白 / 血清淀粉样蛋白A",
    fieldKey: "crpStatus",
    statusKeys: ["crpStatus"],
    documentType: "labCrpSaa",
    documentTypeLabel: "CRP/SAA模板报告",
    description: "按参考单记录炎症指标。",
    metrics: [
      { key: "crp", name: "C反应蛋白", shortName: "CRP", unit: "mg/L", input: "number", reference: "<10" },
      { key: "saa", name: "血清淀粉样蛋白A", shortName: "SAA", unit: "mg/L", input: "number", reference: "<10" }
    ]
  },
  {
    id: "urineRoutine",
    name: "尿常规",
    subtitle: "白细胞 / 亚硝酸盐 / PH / 比重 / 尿蛋白等",
    fieldKey: "urineRoutine",
    statusKeys: ["urineRoutineStatus"],
    documentType: "labUrineRoutine",
    documentTypeLabel: "尿常规模板报告",
    description: "复刻尿常规拍照报告中的常见项目。",
    metrics: [
      {
        key: "wbc",
        name: "白细胞",
        shortName: "LEU",
        input: "select",
        options: ["-", "+", "++", "+++", "未查"],
        defaultValue: "-"
      },
      { key: "nit", name: "亚硝酸盐", shortName: "NIT", input: "select", options: ["-", "+", "未查"], defaultValue: "-" },
      { key: "uro", name: "尿胆原", shortName: "URO", input: "text", reference: "正常" },
      {
        key: "pro",
        name: "蛋白",
        shortName: "PRO",
        input: "select",
        options: ["-", "+", "++", "+++", "未查"],
        defaultValue: "-"
      },
      { key: "ph", name: "PH", shortName: "PH", input: "number", reference: "5.0-8.0" },
      {
        key: "bld",
        name: "潜血",
        shortName: "BLD",
        input: "select",
        options: ["-", "+", "++", "+++", "未查"],
        defaultValue: "-"
      },
      { key: "sg", name: "比重", shortName: "SG", input: "number", reference: "1.003-1.030" },
      {
        key: "ket",
        name: "酮体",
        shortName: "KET",
        input: "select",
        options: ["-", "+", "++", "+++", "未查"],
        defaultValue: "-"
      },
      {
        key: "bil",
        name: "胆红素",
        shortName: "BIL",
        input: "select",
        options: ["-", "+", "++", "+++", "未查"],
        defaultValue: "-"
      },
      {
        key: "glu",
        name: "葡萄糖",
        shortName: "GLU",
        input: "select",
        options: ["-", "+", "++", "+++", "未查"],
        defaultValue: "-"
      },
      {
        key: "vc",
        name: "维生素C",
        shortName: "VC",
        input: "select",
        options: ["-", "+", "++", "+++", "未查"],
        defaultValue: "-"
      }
    ]
  },
  {
    id: "hba1c",
    name: "糖化血红蛋白",
    subtitle: "HbA1c",
    fieldKey: "biochemistry",
    statusKeys: ["fastingGlucoseStatus"],
    documentType: "labHba1c",
    documentTypeLabel: "糖化血红蛋白模板报告",
    description: "按参考单记录 HbA1c。",
    metrics: [{ key: "hba1c", name: "糖化血红蛋白", shortName: "HbA1c", unit: "%", input: "number", reference: "4.0-6.0" }]
  },
  {
    id: "ecgImage",
    name: "心电图图片",
    subtitle: "专用心电设备拍照上传",
    fieldKey: "ecgResult",
    statusKeys: ["ecgStatus"],
    documentType: "ecg",
    documentTypeLabel: "心电图图片",
    description: "心电图来自专用设备，本模块只做图片上传和状态回填。",
    metrics: []
  }
];

export const labTemplateById = (id: LabTemplateId) => labReportTemplates.find(item => item.id === id) ?? labReportTemplates[0];

export const metricReference = (metric: LabMetricDefinition, gender = "") => {
  if (gender.includes("女") && metric.femaleReference) return metric.femaleReference;
  if (gender.includes("男") && metric.maleReference) return metric.maleReference;
  return metric.reference || metric.maleReference || metric.femaleReference || "";
};
