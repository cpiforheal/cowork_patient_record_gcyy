import type { PreAiStageCode } from "@/api/modules/clinic";

export type ClinicalTemplateMode = "fill" | "append" | "overwrite";
export type ClinicalDisease = "肛瘘" | "肛周脓肿" | "混合痔" | "肛裂" | "内痔";

export const CLINICAL_TEMPLATE_VERSION = "anorectal-v1.0";

export interface ClinicalTemplate {
  id: string;
  disease: ClinicalDisease;
  label: string;
  chiefComplaint: string;
  presentIllness: string;
  symptoms: string[];
  visual: string[];
  digital: string[];
  anoscopy: string[];
}

export const clinicalTemplates: ClinicalTemplate[] = [
  {
    id: "mixed-hemorrhoid",
    disease: "混合痔",
    label: "混合痔",
    chiefComplaint: "间断便血伴肛门坠胀、肿物脱出",
    presentIllness:
      "患者间断出现便时鲜红色便血及肛门坠胀、排便不尽感，肿物可在排便时脱出，回纳情况及近期变化待结合患者实际补充。",
    symptoms: ["便血", "肛门坠胀", "肿物脱出", "便不尽感"],
    visual: ["肛缘皮赘样隆起", "蹲位或努挣时肛内肿物脱出"],
    digital: ["触及痔核"],
    anoscopy: ["齿线上黏膜隆起", "黏膜糜烂"]
  },
  {
    id: "anal-fissure",
    disease: "肛裂",
    label: "肛裂",
    chiefComplaint: "便时或便后肛门疼痛伴便血",
    presentIllness: "患者排便时或便后出现肛门疼痛，伴鲜红色便血，常与大便干燥、排便困难相关，病程及诱因待按实际情况补充。",
    symptoms: ["肛周疼痛", "便血", "排便异常"],
    visual: ["可见裂口", "裂口边缘改变"],
    digital: ["括约肌紧张"],
    anoscopy: ["肛窦充血凹陷"]
  },
  {
    id: "anal-fistula",
    disease: "肛瘘",
    label: "肛瘘",
    chiefComplaint: "肛周肿块反复疼痛、溢脓",
    presentIllness: "患者肛周肿块反复疼痛或破溃溢脓，分泌物多少及既往是否行切开引流待据实补充。",
    symptoms: ["肛周肿块", "肛周疼痛", "溢脓", "肛周潮湿"],
    visual: ["肛周可见外口", "外口溢脓"],
    digital: ["触及条索硬结", "脓性分泌物"],
    anoscopy: ["对应肛窦溢脓"]
  },
  {
    id: "perianal-abscess",
    disease: "肛周脓肿",
    label: "肛周脓肿",
    chiefComplaint: "肛周肿痛，伴局部肿块",
    presentIllness: "患者肛周出现肿块及疼痛，局部红肿、触痛或波动感需结合检查记录，是否破溃及有无发热按实际情况填写。",
    symptoms: ["肛周肿块", "肛周疼痛"],
    visual: ["局部红肿隆起", "触痛明显"],
    digital: ["触及波动感"],
    anoscopy: ["齿线上黏膜充血"]
  },
  {
    id: "internal-hemorrhoid",
    disease: "内痔",
    label: "内痔",
    chiefComplaint: "间断便血伴肛门坠胀、便不尽感",
    presentIllness: "患者间断出现便时或便后鲜红色便血，伴肛门坠胀、排便不尽感，是否有肿物脱出及回纳情况待结合实际补充。",
    symptoms: ["便血", "肛门坠胀", "便不尽感"],
    visual: ["蹲位或努挣时肛内肿物脱出"],
    digital: ["触及痔核"],
    anoscopy: ["齿线上黏膜隆起"]
  }
];

export const clinicalTemplateOptions = clinicalTemplates.map(item => ({ label: item.label, value: item.id }));
export const clinicalTemplateById = (id: string) => clinicalTemplates.find(item => item.id === id);
export const clinicalTemplateIdsForDiseases = (diseases: unknown) => {
  const values = Array.isArray(diseases) ? diseases : diseases ? [diseases] : [];
  return values.map(value => clinicalTemplates.find(item => item.disease === String(value))?.id).filter(Boolean) as string[];
};

const asList = (value: unknown) => (Array.isArray(value) ? value.map(String).filter(Boolean) : value ? [String(value)] : []);
const mergeList = (current: unknown, next: string[], mode: ClinicalTemplateMode) =>
  mode === "overwrite" ? Array.from(new Set(next)) : Array.from(new Set([...asList(current), ...next]));
const joinUnique = (values: string[]) => Array.from(new Set(values.filter(Boolean))).join("；");

export const applyClinicalTemplate = (
  stage: PreAiStageCode,
  form: Record<string, any>,
  ids: string[],
  mode: ClinicalTemplateMode = "fill"
) => {
  const selected = ids.map(clinicalTemplateById).filter(Boolean) as ClinicalTemplate[];
  if (!selected.length) return {};
  const primary = selected[0];
  const patch: Record<string, any> = {
    clinicalTemplateIds: selected.map(item => item.id),
    clinicalTemplateDiseases: selected.map(item => item.disease),
    clinicalTemplateVersion: CLINICAL_TEMPLATE_VERSION,
    clinicalTemplateAppliedAt: new Date().toISOString()
  };
  if (stage === "REGISTRATION") {
    if (mode === "fill" && !String(form.registrationChiefComplaint || "").trim())
      patch.registrationChiefComplaint = primary.chiefComplaint;
    if (mode === "fill" && !String(form.registrationCurrentIllness || "").trim())
      patch.registrationCurrentIllness = joinUnique(selected.map(item => item.presentIllness));
    if (mode !== "fill") {
      patch.registrationChiefComplaint =
        mode === "append" && form.registrationChiefComplaint
          ? `${form.registrationChiefComplaint}；${primary.chiefComplaint}`
          : primary.chiefComplaint;
      patch.registrationCurrentIllness =
        mode === "append" && form.registrationCurrentIllness
          ? `${form.registrationCurrentIllness}；${joinUnique(selected.map(item => item.presentIllness))}`
          : joinUnique(selected.map(item => item.presentIllness));
    }
  } else if (stage === "INSPECTION") {
    patch.diseaseDirections = mergeList(
      form.diseaseDirections,
      selected.map(item => item.disease),
      mode
    );
    patch.examinationTypes = mergeList(form.examinationTypes, ["VISUAL", "DIGITAL", "ANOSCOPY"], mode);
    patch.visualFindings = mergeList(
      form.visualFindings,
      selected.flatMap(item => item.visual),
      mode
    );
    patch.digitalExamFindings = mergeList(
      form.digitalExamFindings,
      selected.flatMap(item => item.digital),
      mode
    );
    patch.anoscopyFindings = mergeList(
      form.anoscopyFindings,
      selected.flatMap(item => item.anoscopy),
      mode
    );
    if (mode === "overwrite" || !String(form.examinationDirection || "").trim()) patch.examinationDirection = "肛肠";
    if (mode === "overwrite") {
      patch.factualConclusionOverride = undefined;
      patch.factualConclusionSourceHash = undefined;
    }
    patch.factualConclusionConfirmed = false;
  } else if (stage === "RECEPTION") {
    patch.chiefComplaint = mergeList(
      form.chiefComplaint,
      selected.flatMap(item => item.symptoms),
      mode
    );
    if (mode === "fill" || mode === "overwrite") patch.chiefComplaintText = primary.chiefComplaint;
    else if (mode === "append")
      patch.chiefComplaintText = [form.chiefComplaintText, primary.chiefComplaint].filter(Boolean).join("；");
    const presentIllness = joinUnique(selected.map(item => item.presentIllness));
    if (mode !== "fill" || !String(form.presentIllnessOverride || form.presentIllness || "").trim()) {
      patch.presentIllnessOverride =
        mode === "append" && form.presentIllnessOverride ? `${form.presentIllnessOverride}；${presentIllness}` : presentIllness;
      patch.presentIllness = patch.presentIllnessOverride;
      patch.presentIllnessConfirmed = false;
    }
  }
  return patch;
};
