import type { PreAiStageCode } from "@/api/modules/clinic";

export type ClinicalTemplateMode = "fill" | "append" | "overwrite" | "render";
export type ClinicalDisease = "肛瘘" | "肛周脓肿" | "混合痔" | "肛裂" | "内痔";

export const CLINICAL_TEMPLATE_VERSION = "anorectal-v2.0";

export interface ClinicalTemplateSlot {
  key: string;
  label: string;
  kind: "select" | "multi";
  options: string[];
  default: string | string[];
}

export interface ClinicalTemplate {
  id: string;
  disease: ClinicalDisease;
  label: string;
  chiefComplaint: string;
  presentIllness: string;
  inspectionConclusion: string;
  slots: ClinicalTemplateSlot[];
  symptoms: string[];
  visual: string[];
  digital: string[];
  anoscopy: string[];
}

export const CLOCK_POSITION_OPTIONS = ["1点", "2点", "3点", "4点", "5点", "6点", "7点", "8点", "9点", "10点", "11点", "12点"];
const DURATION_OPTIONS = ["3天", "1周", "2周", "3周", "1月", "3月", "半年", "1年", "2年", "3年", "4年", "5年"];
const AGGRAVATION_OPTIONS = ["3天", "1周", "2周", "1月", "无加重"];
const REDUCTION_OPTIONS = ["可自行回纳", "需手托回纳", "不可回纳"];
const DISTANCE_OPTIONS = ["2cm", "3cm", "4cm", "5cm"];
const COMPANION_OPTIONS = ["直肠黏膜松弛", "肛乳头肥大", "血栓性外痔", "直肠息肉"];

const slot = (key: string, label: string, options: string[], def: string | string[], kind: "select" | "multi" = "select"): ClinicalTemplateSlot => ({
  key,
  label,
  kind,
  options,
  default: def
});

const durationSlot = (def: string) => slot("duration", "病程时长", DURATION_OPTIONS, def);
const aggravationSlot = (def: string) => slot("aggravation", "近期加重", AGGRAVATION_OPTIONS, def);
const companionsSlot = () => slot("companions", "伴随诊断", COMPANION_OPTIONS, [], "multi");
const skinTagSlot = (def: string[]) => slot("skinTags", "外痔/赘皮点位", CLOCK_POSITION_OPTIONS, def, "multi");
const internalSlot = (def: string[]) => slot("internal", "内痔点位", CLOCK_POSITION_OPTIONS, def, "multi");

export const clinicalTemplates: ClinicalTemplate[] = [
  {
    id: "mixed-hemorrhoid",
    disease: "混合痔",
    label: "混合痔",
    chiefComplaint: "间断便血伴肿物脱出{duration}{aggravationPhrase}",
    presentIllness:
      "患者自诉{duration}前无明显诱因出现便时滴血，色鲜红，无痛，便后即止，数天后自行缓解，后间断发作，进行性加重，出现肛门肿物脱出，初起便后可自行回纳，{aggravationClause}脱出后{reduction}，自购药物局部应用效不佳。今来院就诊，门诊检查后以“{diagnosis}”收入院。患者近期精神、睡眠、饮食、大小便可，体重未见明显变化，未见恶寒发热表现。",
    inspectionConclusion:
      "截石位{skinTags}点肛缘赘皮增生，屏气用腹压可见其缓慢增大，色青紫，质柔软；镜检示{internal}点内痔粘膜隆起糜烂，退镜时{internal}点粘膜可脱出肛外。",
    slots: [durationSlot("3年"), aggravationSlot("2周"), skinTagSlot(["4点", "7点", "11点"]), internalSlot(["3点", "7点", "11点"]), slot("reduction", "脱出回纳情况", REDUCTION_OPTIONS, "需手托回纳"), companionsSlot()],
    symptoms: ["便血", "肿物脱出", "肛门坠胀", "便不尽感"],
    visual: ["肛缘赘皮增生", "屏气用腹压可见肿物缓慢增大", "色青紫，质柔软"],
    digital: ["触及颗粒状硬结"],
    anoscopy: ["内痔粘膜隆起糜烂", "退镜时粘膜可脱出肛外", "直肠黏膜松弛、层叠状"]
  },
  {
    id: "internal-hemorrhoid",
    disease: "内痔",
    label: "内痔",
    chiefComplaint: "间断便血伴肛门坠胀、便不尽感{duration}{aggravationPhrase}",
    presentIllness:
      "患者自诉{duration}前无明显诱因出现便时出血，滴下状，色鲜红，无痛，便后即止，数天后自行好转，后间断发作，饮食辛辣刺激或饮酒、劳累后症状明显，{aggravationClause}未予系统检查与治疗。今来院就诊，门诊检查后以“{diagnosis}”收入院。患者近期精神、睡眠、二便可，体重未见明显变化，未见恶寒发热表现。",
    inspectionConclusion: "镜检示{internal}点齿线上黏膜充血隆起，局部糜烂。",
    slots: [durationSlot("1年"), aggravationSlot("1月"), internalSlot(["3点", "7点", "11点"]), companionsSlot()],
    symptoms: ["便血", "肛门坠胀", "便不尽感"],
    visual: ["蹲位或努挣时肛内肿物脱出"],
    digital: ["触及痔核"],
    anoscopy: ["齿线上黏膜充血隆起", "局部糜烂"]
  },
  {
    id: "anal-fissure",
    disease: "肛裂",
    label: "肛裂",
    chiefComplaint: "便时肛门疼痛伴便血{duration}{aggravationPhrase}",
    presentIllness:
      "患者自诉{duration}前无明显诱因出现便时手纸沾染血迹，后间断发作，进行性加重，出现肛缘赘皮增生，便时增大，便后缩小，{aggravationClause}伴有排便习惯改变，便次少，2-3天排便一次，时常干燥。今来院就诊，门诊检查后，以“{diagnosis}”收入院。患者近期精神、睡眠、饮食可，体重未见明显变化。",
    inspectionConclusion:
      "视诊截石位{skinTags}点肛缘赘皮增生，{fissure}点肛管可见裂口，指诊肛门括约肌紧张，镜检示{internal}点内痔痔核隆起。",
    slots: [
      durationSlot("1年"),
      aggravationSlot("1周"),
      skinTagSlot(["6点", "11点"]),
      slot("fissure", "裂口点位", CLOCK_POSITION_OPTIONS, ["6点"], "multi"),
      internalSlot(["3点", "7点", "11点"]),
      companionsSlot()
    ],
    symptoms: ["肛周疼痛", "便血", "排便异常", "肛周瘙痒"],
    visual: ["肛缘赘皮增生", "肛管可见裂口"],
    digital: ["肛门括约肌紧张"],
    anoscopy: ["内痔痔核隆起", "肛隐窝潮红凹陷"]
  },
  {
    id: "anal-fistula",
    disease: "肛瘘",
    label: "肛瘘",
    chiefComplaint: "肛周肿包反复疼痛、溢脓{duration}{aggravationPhrase}",
    presentIllness:
      "患者自诉{duration}前因腹泻肛周突起肿块，疼痛呈持续性，后自行破溃，脓出痛减。后间断脓性分泌物，时多时少，肛门潮湿不洁；平素时有间断便血，手纸沾染血迹或滴血，无痛；反复发作，{aggravationClause}为求进一步诊疗今来院就诊。患者近期精神、睡眠、饮食、小便可，便次频、便稀，2-3次/日，体重未见明显变化，未见恶寒发热表现。门诊检查后以“{diagnosis}”收入院。",
    inspectionConclusion:
      "截石位{fistula}点距肛门约{distance}处瘘口，皮下可触及索状硬结分别止于对应齿线处，肛门镜检可见{internal}点齿线上黏膜充血隆起。",
    slots: [
      durationSlot("2年"),
      aggravationSlot("1周"),
      slot("fistula", "瘘口点位", CLOCK_POSITION_OPTIONS, ["9点"], "multi"),
      slot("distance", "瘘口距肛门", DISTANCE_OPTIONS, "4cm"),
      internalSlot(["3点", "7点", "11点"]),
      companionsSlot()
    ],
    symptoms: ["肛周肿块", "肛周疼痛", "溢脓", "肛周潮湿", "便血"],
    visual: ["肛周可见外口", "外口增生凸起", "少量黄稠脓性分泌物附着"],
    digital: ["触及索状硬结止于齿线处"],
    anoscopy: ["齿线上黏膜充血隆起"]
  },
  {
    id: "perianal-abscess",
    disease: "肛周脓肿",
    label: "肛周脓肿",
    chiefComplaint: "肛周肿痛{duration}{aggravationPhrase}",
    presentIllness:
      "患者自诉{duration}前无明显诱因出现肛周突起肿包，触痛明显，未见出血、溢脓，自用药物无效，{aggravationClause}为求进一步诊疗今来院就诊，门诊检查后以“{diagnosis}”收入院。患者近期精神、睡眠、饮食、大小便可，体重未见明显变化，未见恶寒发热表现。",
    inspectionConclusion:
      "截石位{lump}点肛周肿块，触之疼痛明显，{fluctuation}点可触及波动感，镜检可见{fluctuation}点肛隐窝潮红凹陷明显，{internal}点齿线上黏膜充血隆起，局部糜烂。",
    slots: [
      durationSlot("3周"),
      aggravationSlot("3天"),
      slot("lump", "肿块点位", CLOCK_POSITION_OPTIONS, ["9点", "12点"], "multi"),
      slot("fluctuation", "波动感点位", CLOCK_POSITION_OPTIONS, ["12点"], "multi"),
      internalSlot(["3点", "7点", "10点"]),
      companionsSlot()
    ],
    symptoms: ["肛周肿块", "肛周疼痛"],
    visual: ["局部红肿隆起", "触痛明显"],
    digital: ["触及波动感"],
    anoscopy: ["肛隐窝潮红凹陷", "齿线上黏膜充血隆起"]
  }
];

export const clinicalTemplateOptions = clinicalTemplates.map(item => ({ label: item.label, value: item.id }));
export const clinicalTemplateById = (id: string) => clinicalTemplates.find(item => item.id === id);

export const inferTemplateIdsBySymptoms = (symptoms: unknown): string[] => {
  const picked = (Array.isArray(symptoms) ? symptoms : symptoms ? [symptoms] : [])
    .map(item => String(item || "").trim())
    .filter(Boolean);
  if (picked.length < 2) return [];
  let best: { id: string; score: number; size: number } | null = null;
  for (const item of clinicalTemplates) {
    const score = picked.filter(value => item.symptoms.includes(value)).length;
    if (score < 2) continue;
    if (!best || score > best.score || (score === best.score && item.symptoms.length < best.size)) {
      best = { id: item.id, score, size: item.symptoms.length };
    }
  }
  return best ? [best.id] : [];
};
export const clinicalTemplateIdsForDiseases = (diseases: unknown) => {
  const values = Array.isArray(diseases) ? diseases : diseases ? [diseases] : [];
  return values.map(value => clinicalTemplates.find(item => item.disease === String(value))?.id).filter(Boolean) as string[];
};

export const clinicalTemplateSlotDefaults = (id: string): Record<string, string | string[]> => {
  const template = clinicalTemplateById(id);
  if (!template) return {};
  const defaults: Record<string, string | string[]> = {};
  for (const item of template.slots) defaults[item.key] = Array.isArray(item.default) ? [...item.default] : item.default;
  return defaults;
};

export const mergeClinicalTemplateSlots = (form: Record<string, any>, ids: string[]) => {
  const primary = clinicalTemplateById(ids[0] || "");
  if (!primary) return undefined;
  const saved = form.clinicalTemplateSlots && typeof form.clinicalTemplateSlots === "object" ? form.clinicalTemplateSlots : {};
  const merged: Record<string, string | string[]> = { ...clinicalTemplateSlotDefaults(primary.id) };
  for (const item of primary.slots) {
    const value = saved[item.key];
    if (item.kind === "multi" ? Array.isArray(value) && value.length : String(value || "").trim()) merged[item.key] = value;
  }
  return merged;
};

const asList = (value: unknown) => (Array.isArray(value) ? value.map(String).filter(Boolean) : value ? [String(value)] : []);
const mergeList = (current: unknown, next: string[], mode: ClinicalTemplateMode) =>
  mode === "overwrite" || mode === "render" ? Array.from(new Set(next)) : Array.from(new Set([...asList(current), ...next]));
const joinUnique = (values: string[]) => Array.from(new Set(values.filter(Boolean))).join("、");

const tidy = (text: string) =>
  text
    .replace(/，{2,}/g, "，")
    .replace(/、{2,}/g, "、")
    .replace(/，。/g, "。")
    .replace(/，(?=[。；])/g, "")
    .replace(/。{2,}/g, "。")
    .replace(/；{2,}/g, "；")
    .trim();

const renderTemplate = (template: string, values: Record<string, string>) =>
  tidy(template.replace(/\{(\w+)\}/g, (_, key: string) => values[key] ?? ""));

const deriveSlotValues = (slots: Record<string, any>, selected: ClinicalTemplate[]) => {
  const values: Record<string, string> = {};
  for (const [key, value] of Object.entries(slots)) {
    if (Array.isArray(value)) values[key] = joinUnique(value.map(String));
    else values[key] = String(value ?? "").trim();
  }
  const primary = selected[0];
  const companions = asList(slots.companions).map(String);
  const diagnosis = joinUnique([primary.disease, ...selected.slice(1).map(item => item.disease), ...companions]);
  const aggravation = values.aggravation && values.aggravation !== "无加重" ? values.aggravation : "";
  values.aggravationPhrase = aggravation ? `，加重${aggravation}` : "";
  values.aggravationClause = aggravation ? `近${aggravation}症状加重，` : "";
  values.diagnosis = diagnosis;
  return values;
};

export const applyClinicalTemplate = (
  stage: PreAiStageCode,
  form: Record<string, any>,
  ids: string[],
  mode: ClinicalTemplateMode = "fill"
) => {
  const selected = ids.map(clinicalTemplateById).filter(Boolean) as ClinicalTemplate[];
  if (!selected.length) return {};
  const primary = selected[0];
  const savedSlots = form.clinicalTemplateSlots && typeof form.clinicalTemplateSlots === "object" ? form.clinicalTemplateSlots : {};
  const slots = mergeClinicalTemplateSlots({ clinicalTemplateSlots: savedSlots }, ids) || {};
  const values = deriveSlotValues(slots, selected);
  const patch: Record<string, any> = {
    clinicalTemplateIds: selected.map(item => item.id),
    clinicalTemplateDiseases: selected.map(item => item.disease),
    clinicalTemplateVersion: CLINICAL_TEMPLATE_VERSION,
    clinicalTemplateAppliedAt: new Date().toISOString(),
    clinicalTemplateSlots: slots
  };
  const snapshot = { ...((form.clinicalTemplateRendered && typeof form.clinicalTemplateRendered === "object" ? form.clinicalTemplateRendered : {}) as Record<string, string>) };
  const setTemplateText = (key: string, text: string) => {
    const current = String(form[key] ?? "").trim();
    if (mode === "fill" && current) return;
    if (mode === "render" && current && snapshot[key] !== undefined && current !== snapshot[key]) return;
    if (mode === "append" && current) {
      patch[key] = `${current}；${text}`;
      snapshot[key] = patch[key];
      return;
    }
    patch[key] = text;
    snapshot[key] = text;
  };
  if (stage === "REGISTRATION") {
    if (mode !== "render") patch.registrationSymptoms = mergeList(form.registrationSymptoms, selected.flatMap(item => item.symptoms), mode);
    setTemplateText("registrationChiefComplaint", renderTemplate(primary.chiefComplaint, values));
    setTemplateText("registrationCurrentIllness", renderTemplate(primary.presentIllness, values));
  } else if (stage === "RECEPTION") {
    if (mode !== "render") patch.chiefComplaint = mergeList(form.chiefComplaint, selected.flatMap(item => item.symptoms), mode);
    setTemplateText("chiefComplaintText", renderTemplate(primary.chiefComplaint, values));
    setTemplateText("presentIllnessOverride", renderTemplate(primary.presentIllness, values));
    if (patch.presentIllnessOverride !== undefined) {
      patch.presentIllness = patch.presentIllnessOverride;
      patch.presentIllnessConfirmed = false;
    }
    if (String(slots.duration || "").trim()) {
      const currentDuration = String(form.symptomDuration || "").trim();
      if (mode === "overwrite" || mode === "render" || !currentDuration) patch.symptomDuration = slots.duration;
    }
  } else if (stage === "INSPECTION") {
    if (mode !== "render") {
      patch.diseaseDirections = mergeList(form.diseaseDirections, selected.map(item => item.disease), mode);
      patch.examinationTypes = mergeList(form.examinationTypes, ["VISUAL", "DIGITAL", "ANOSCOPY"], mode);
      patch.visualFindings = mergeList(form.visualFindings, selected.flatMap(item => item.visual), mode);
      patch.digitalExamFindings = mergeList(form.digitalExamFindings, selected.flatMap(item => item.digital), mode);
      patch.anoscopyFindings = mergeList(form.anoscopyFindings, selected.flatMap(item => item.anoscopy), mode);
    }
    if (mode === "overwrite" || !String(form.examinationDirection || "").trim()) patch.examinationDirection = "肛肠";
    setTemplateText("inspectionNarrative", renderTemplate(primary.inspectionConclusion, values));
    if (mode === "overwrite") {
      patch.factualConclusionOverride = undefined;
      patch.factualConclusionSourceHash = undefined;
    }
    patch.factualConclusionConfirmed = false;
  }
  patch.clinicalTemplateRendered = snapshot;
  return patch;
};
