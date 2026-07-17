const list = (value: unknown): string[] =>
  (Array.isArray(value) ? value : value ? [value] : []).map(item => String(item || "").trim()).filter(Boolean);

const join = (value: unknown, separator = "、") => list(value).join(separator);

const sentence = (value: unknown) => {
  const text = String(value || "").trim();
  if (!text) return "";
  return /[。；！？]$/.test(text) ? text : `${text}。`;
};

export const stableSourceHash = (values: unknown[]) => {
  const source = JSON.stringify(values);
  let hash = 5381;
  for (let index = 0; index < source.length; index += 1) hash = (hash * 33) ^ source.charCodeAt(index);
  return (hash >>> 0).toString(36);
};

export const buildChiefComplaintText = (form: Record<string, any>) => {
  const symptoms = join(form.chiefComplaint);
  const duration = String(form.symptomDuration || "").trim();
  const aggravation = String(form.recentAggravation || "").trim();
  const text = [symptoms, duration, aggravation && aggravation !== "近期无明显变化" ? aggravation : ""]
    .filter(Boolean)
    .join("，");
  return text.slice(0, 20);
};

export const buildPresentIllnessText = (form: Record<string, any>) => {
  const parts = [
    form.onsetTrigger && `患者${form.onsetTrigger}出现${join(form.chiefComplaint)}`,
    form.symptomDuration && `病程${form.symptomDuration}`,
    join(form.symptomPattern) && `发作特点为${join(form.symptomPattern)}`,
    join(form.aggravatingFactors) && `可因${join(form.aggravatingFactors)}加重`,
    join(form.bleedingFeatures) && `便血表现为${join(form.bleedingFeatures)}`,
    join(form.painFeatures) && `疼痛表现为${join(form.painFeatures)}`,
    form.prolapseReduction && `脱出及回纳情况：${form.prolapseReduction}`,
    join(form.associatedSymptoms) && `伴${join(form.associatedSymptoms)}`,
    form.recentAggravation,
    join(form.previousTreatment) && `既往相关处理：${join(form.previousTreatment)}`,
    join(form.generalCondition) && `一般情况：${join(form.generalCondition)}`,
    [form.stoolFrequency, join(form.stoolCharacteristics)].filter(Boolean).length &&
      `大便${[form.stoolFrequency, join(form.stoolCharacteristics)].filter(Boolean).join("，")}`
  ];
  return parts.filter(Boolean).map(sentence).join("");
};

const measurementText = (value: any) => {
  if (!value || typeof value !== "object") return String(value || "").trim();
  return [value.value, value.unit, value.status && value.status !== "NORMAL" ? `（${value.status}）` : ""]
    .filter(Boolean)
    .join("");
};

export const buildInspectionConclusion = (form: Record<string, any>) => {
  const parts = [
    form.lesionLocation && `病变位于${form.lesionLocation}`,
    form.clockPosition && `钟点位${form.clockPosition}`,
    measurementText(form.lesionSize) && `大小${measurementText(form.lesionSize)}`,
    measurementText(form.lesionExtent) && `范围${measurementText(form.lesionExtent)}`,
    measurementText(form.lesionDepth) && `深度${measurementText(form.lesionDepth)}`,
    join(form.visualFindings) && `视诊见${join(form.visualFindings)}`,
    join(form.digitalExamFindings) && `指诊见${join(form.digitalExamFindings)}`,
    join(form.anoscopyFindings) && `肛门镜见${join(form.anoscopyFindings)}`,
    form.otherFindings
  ];
  return parts.filter(Boolean).map(sentence).join("");
};

export const buildSyndromeBasis = (form: Record<string, any>) => {
  const fourDiagnostics = [
    join(form.inspection) && `望诊：${join(form.inspection)}`,
    join(form.auscultationOlfaction) && `闻诊：${join(form.auscultationOlfaction)}`,
    join(form.inquiry) && `问诊：${join(form.inquiry)}`,
    join(form.palpation) && `切诊：${join(form.palpation)}`,
    join(form.tongue) && `舌象：${join(form.tongue)}`,
    join(form.pulse) && `脉象：${join(form.pulse)}`
  ].filter(Boolean);
  const conclusion = form.primarySyndrome
    ? `四诊合参，辨为${form.primarySyndrome}${join(form.concurrentSyndrome) ? `，兼${join(form.concurrentSyndrome)}` : ""}`
    : "";
  return [...fourDiagnostics, conclusion].filter(Boolean).map(sentence).join("");
};

export const buildDiagnosisBasis = (form: Record<string, any>) => {
  const evidence = join(form.diagnosisEvidence);
  return evidence ? `依据${evidence}，考虑西医主诊断为${form.primaryWesternDiagnosis || "待确认"}。` : "";
};

export const buildTreatmentPlan = (form: Record<string, any>) => {
  const parts = [
    form.treatmentPath && `治疗方式：${form.treatmentPath === "SURGICAL" ? "手术治疗" : "保守治疗"}`,
    join(form.treatmentMeasures) && `主要措施：${join(form.treatmentMeasures)}`,
    join(form.medicationDirections) && `用药方向：${join(form.medicationDirections)}`,
    join(form.examPlans) && `检查安排：${join(form.examPlans)}`,
    join(form.surgeryArrangements) && `手术安排：${join(form.surgeryArrangements)}`,
    join(form.observationFocus) && `观察重点：${join(form.observationFocus)}`
  ];
  return parts.filter(Boolean).map(sentence).join("");
};

export const buildSurgeryFindings = (form: Record<string, any>) =>
  [join(form.intraoperativeFindingOptions)].filter(Boolean).map(sentence).join("");

export const buildProcedureSteps = (form: Record<string, any>) =>
  [join(form.procedureStepOptions)].filter(Boolean).map(sentence).join("");

export const buildHandoffText = (form: Record<string, any>) =>
  [join(form.postoperativeHandoffOptions)].filter(Boolean).map(sentence).join("");

export const buildColonoscopyConclusion = (form: Record<string, any>) => {
  if (form.status && form.status !== "COMPLETED") {
    const labels: Record<string, string> = { NOT_DONE: "未查", REFUSED: "患者拒绝", DEFERRED: "暂缓" };
    return labels[form.status] || String(form.status);
  }
  const parts = [
    form.scope && `检查范围：${form.scope}`,
    join(form.findings) && `所见：${join(form.findings)}`,
    form.lesionLocation && `病变部位：${form.lesionLocation}`,
    form.lesionCount && `数量：${form.lesionCount}`,
    measurementText(form.lesionSize) && `大小：${measurementText(form.lesionSize)}`,
    join(form.lesionMorphology) && `形态：${join(form.lesionMorphology)}`,
    form.biopsyPerformed === "已活检" && "已活检",
    ["已切除", "部分切除"].includes(form.resectionPerformed) && form.resectionPerformed,
    form.pathologySubmitted === "已送病理" && "已送病理",
    form.abnormalDescription
  ];
  return parts.filter(Boolean).map(sentence).join("");
};
