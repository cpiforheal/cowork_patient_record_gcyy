export const selectedText = (value: unknown) =>
  (Array.isArray(value) ? value : value ? [value] : [])
    .map(item => String(item).trim())
    .filter(Boolean)
    .join("、");

export const sentence = (value: string) => {
  const text = value.trim();
  if (!text) return "";
  return /[。；！？]$/.test(text) ? text : `${text}。`;
};

export const buildPresentIllnessText = (form: Record<string, any>) => {
  const chiefComplaint = selectedText(form.chiefComplaint);
  const onset = [form.symptomDuration, form.onsetTrigger].filter(Boolean).join("，");
  const course = [
    selectedText(form.symptomPattern),
    selectedText(form.bleedingFeatures),
    selectedText(form.painFeatures),
    form.prolapseReduction,
    selectedText(form.associatedSymptoms)
  ].filter(Boolean);
  const aggravation = [selectedText(form.aggravatingFactors), form.recentAggravation].filter(Boolean).join("，");
  const treatment = selectedText(form.previousTreatment);
  const general = [
    selectedText(form.generalCondition),
    form.stoolFrequency ? `大便${form.stoolFrequency}` : "",
    selectedText(form.stoolCharacteristics)
  ]
    .filter(Boolean)
    .join("，");

  return [
    sentence(`患者自诉${onset ? `${onset}出现` : "出现"}${chiefComplaint}`),
    sentence(course.join("，")),
    aggravation ? sentence(`症状与${aggravation}`) : "",
    treatment ? sentence(`既往予${treatment}`) : "",
    general ? sentence(`近期${general}`) : ""
  ]
    .filter(Boolean)
    .join("");
};
