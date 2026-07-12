import type { PreAiAuxiliaryTaskType, PreAiStageCode } from "@/api/modules/clinic";

export type PreAiFieldKind = "input" | "textarea" | "select" | "multi" | "date" | "datetime" | "number";

export interface PreAiFieldConfig {
  key: string;
  label: string;
  kind: PreAiFieldKind;
  required?: boolean;
  placeholder?: string;
  options?: Array<{ label: string; value: string }>;
  optionsFor?: (form: Record<string, any>) => Array<{ label: string; value: string }>;
  creatable?: boolean;
  visible?: (form: Record<string, any>) => boolean;
  rows?: number;
  span?: 1 | 2;
}

export interface PreAiStageConfig {
  code: PreAiStageCode;
  title: string;
  shortTitle: string;
  owner: string;
  roles: string[];
  description: string;
  fields: PreAiFieldConfig[];
}

const routeOptions = [
  { label: "门诊", value: "OUTPATIENT" },
  { label: "住院", value: "INPATIENT" }
];

const treatmentOptions = [
  { label: "保守治疗", value: "CONSERVATIVE" },
  { label: "手术治疗", value: "SURGICAL" }
];

const examinationOptions = [
  { label: "外观检查", value: "VISUAL" },
  { label: "指检", value: "DIGITAL" },
  { label: "肛门镜/镜下检查", value: "ANOSCOPY" },
  { label: "其他检查", value: "OTHER" }
];

const options = (values: string[]) => values.map(value => ({ label: value, value }));
const diseaseOptions = options([
  "痔",
  "肛裂",
  "肛瘘",
  "肛周脓肿",
  "直肠脱垂",
  "直肠前突",
  "化脓性汗腺炎",
  "坏死性筋膜炎",
  "藏毛窦",
  "其他"
]);
const visualByDisease: Record<string, string[]> = {
  痔: ["肛缘皮赘样隆起", "蹲位或努挣时肛内肿物脱出", "可自行还纳", "需手托还纳"],
  肛裂: ["可见裂口", "裂口色鲜红", "裂口灰白", "边缘增厚", "伴前哨痔", "伴肛乳头肥大"],
  肛瘘: ["肛周可见外口", "乳头状隆起", "外口凹陷", "外口溢脓", "挤压可见脓性分泌物", "周围皮肤色素沉着"],
  肛周脓肿: ["局部红肿隆起", "皮温高", "触痛明显", "无破溃", "已破溃流脓"],
  直肠脱垂: ["努挣时直肠黏膜脱出", "全层脱出肛外", "环形脱出", "宝塔状脱出", "可自行回纳", "需手推回纳"],
  直肠前突: ["肛门外观无明显异常", "无脱出", "无红肿"],
  化脓性汗腺炎: ["肛周及会阴多发红肿结节", "脓疱", "破溃流脓", "窦道瘢痕", "皮肤增厚"],
  坏死性筋膜炎: ["肛周会阴广泛红肿青紫", "皮肤坏死", "水疱", "渗液", "皮下捻发感"],
  藏毛窦: ["骶尾部正中凹陷小孔", "多发外口", "局部毛发较多", "挤压溢皮脂或脓液", "局部红肿"]
};
const digitalCommon = [
  "括约肌正常",
  "括约肌紧张",
  "括约肌松弛",
  "触及痔核",
  "触及肛乳头",
  "触及息肉",
  "触及波动感",
  "触及条索硬结",
  "指套无染血",
  "指套染血",
  "脓性分泌物",
  "血性恶臭分泌物",
  "未触及硬性肿物"
];
const anoscopyCommon = [
  "齿线上黏膜充血",
  "黏膜糜烂",
  "黏膜隆起",
  "黏膜堆积下垂",
  "伴渗血",
  "未见溃疡",
  "未见占位",
  "肛窦充血凹陷",
  "对应肛窦溢脓",
  "肛管及直肠黏膜未见明显异常"
];
const selectedDiseaseOptions = (form: Record<string, any>, dictionary: Record<string, string[]>, fallback: string[]) => {
  const selected = Array.isArray(form.diseaseDirections)
    ? form.diseaseDirections
    : form.diseaseDirections
      ? [form.diseaseDirections]
      : [];
  return options(Array.from(new Set([...selected.flatMap((item: string) => dictionary[item] || []), ...fallback])));
};

const hasExam = (form: Record<string, any>, type: string) =>
  Array.isArray(form.examinationTypes) && form.examinationTypes.includes(type);

export const preAiStages: PreAiStageConfig[] = [
  {
    code: "REGISTRATION",
    title: "前台登记",
    shortTitle: "登记",
    owner: "前台",
    roles: ["admin", "frontdesk"],
    description: "内部保存真实身份信息；导出的 DOCX 会自动脱敏。",
    fields: [
      { key: "patientName", label: "姓名", kind: "input", required: true },
      {
        key: "gender",
        label: "性别",
        kind: "select",
        required: true,
        options: ["男", "女", "其他", "待核实"].map(value => ({ label: value, value }))
      },
      { key: "birthDate", label: "出生日期", kind: "date" },
      { key: "age", label: "年龄", kind: "input", placeholder: "出生日期与年龄至少填写一项" },
      { key: "phone", label: "手机号", kind: "input" },
      {
        key: "identityType",
        label: "证件类型",
        kind: "select",
        options: ["居民身份证", "护照", "其他"].map(value => ({ label: value, value }))
      },
      { key: "identityNumber", label: "证件号", kind: "input", placeholder: "仅内部保存，不进入外部文档" },
      { key: "address", label: "常住地址", kind: "input", span: 2 },
      { key: "contactName", label: "联系人姓名", kind: "input" },
      { key: "contactRelation", label: "联系人关系", kind: "input" },
      { key: "contactPhone", label: "联系人电话", kind: "input" },
      { key: "visitDate", label: "就诊时间", kind: "datetime", required: true },
      { key: "patientSource", label: "患者来源", kind: "input" },
      { key: "registrationNote", label: "登记备注", kind: "textarea", rows: 3, span: 2 }
    ]
  },
  {
    code: "INSPECTION",
    title: "检查室",
    shortTitle: "检查",
    owner: "检查室",
    roles: ["admin", "inspection"],
    description: "只记录客观所见，不填写最终诊断。先选检查类型，再出现对应字段。",
    fields: [
      {
        key: "examinationDirection",
        label: "检查方向",
        kind: "select",
        required: true,
        options: ["肛肠", "胃肠", "其他"].map(value => ({ label: value, value }))
      },
      {
        key: "diseaseDirections",
        label: "病种方向",
        kind: "multi",
        required: true,
        options: diseaseOptions,
        creatable: true,
        span: 2
      },
      { key: "examinationTypes", label: "已完成检查", kind: "multi", required: true, options: examinationOptions, span: 2 },
      { key: "lesionLocation", label: "病变位置", kind: "input" },
      { key: "clockPosition", label: "方向/钟点位", kind: "input" },
      {
        key: "visualFindings",
        label: "外观所见",
        kind: "multi",
        optionsFor: form => selectedDiseaseOptions(form, visualByDisease, []),
        creatable: true,
        visible: form => hasExam(form, "VISUAL"),
        span: 2
      },
      {
        key: "digitalExamFindings",
        label: "指检所见",
        kind: "multi",
        options: options(digitalCommon),
        creatable: true,
        visible: form => hasExam(form, "DIGITAL"),
        span: 2
      },
      {
        key: "anoscopyFindings",
        label: "镜下/肛门镜所见",
        kind: "multi",
        options: options(anoscopyCommon),
        creatable: true,
        visible: form => hasExam(form, "ANOSCOPY"),
        span: 2
      },
      {
        key: "otherFindings",
        label: "其他客观表现",
        kind: "textarea",
        rows: 3,
        visible: form => hasExam(form, "OTHER"),
        span: 2
      },
      { key: "factualConclusion", label: "检查事实结论", kind: "textarea", required: true, rows: 4, span: 2 }
    ]
  },
  {
    code: "RECEPTION",
    title: "接诊室",
    shortTitle: "接诊",
    owner: "接诊室 / 医生复核",
    roles: ["admin", "reception", "doctor"],
    description: "按模板采集现病史及各类病史事实；结构化选择可生成原文，医生仍可修改最终文本。",
    fields: [
      {
        key: "chiefComplaint",
        label: "主诉症状",
        kind: "multi",
        required: true,
        options: options([
          "肛周肿块",
          "肛周疼痛",
          "溢脓",
          "便血",
          "肿物脱出",
          "肛周潮湿",
          "肛门坠胀",
          "肛周瘙痒",
          "排便异常",
          "便不尽感"
        ]),
        creatable: true,
        span: 2
      },
      { key: "symptomDuration", label: "主要症状病程", kind: "input", placeholder: "如：1年、3月余、20年前开始" },
      {
        key: "onsetTrigger",
        label: "起病诱因",
        kind: "select",
        options: options(["无明显诱因", "饮酒后", "进食辛辣后", "便秘后", "腹泻后", "劳累后", "妊娠或分娩后", "外伤或手术后"]),
        creatable: true
      },
      {
        key: "symptomPattern",
        label: "症状发作方式",
        kind: "multi",
        options: options(["持续性", "间歇性", "反复发作", "进行性加重", "自行缓解", "便后缓解", "休息后缓解"]),
        creatable: true
      },
      {
        key: "aggravatingFactors",
        label: "加重诱因",
        kind: "multi",
        options: options(["饮酒", "辛辣饮食", "便秘", "腹泻", "久坐", "久蹲排便", "劳累", "无明显诱因"]),
        creatable: true
      },
      {
        key: "bleedingFeatures",
        label: "便血特征",
        kind: "multi",
        options: options([
          "色鲜红",
          "色暗红",
          "手纸带血",
          "滴血",
          "喷射状出血",
          "便表面附血",
          "与大便混合",
          "便后即止",
          "无便血"
        ]),
        creatable: true,
        span: 2
      },
      {
        key: "painFeatures",
        label: "疼痛特征",
        kind: "multi",
        options: options(["无痛", "便时疼痛", "便后疼痛", "持续性疼痛", "间歇性疼痛", "胀痛", "灼痛", "刺痛", "跳痛"]),
        creatable: true
      },
      {
        key: "prolapseReduction",
        label: "脱出与回纳",
        kind: "select",
        options: options(["无脱出", "便时脱出便后自行回纳", "休息后自行回纳", "需手托回纳", "不能回纳", "平时亦可脱出"]),
        creatable: true
      },
      {
        key: "associatedSymptoms",
        label: "伴随症状",
        kind: "multi",
        options: options(["肛周潮湿", "肛周瘙痒", "溢脓", "便不尽感", "肛门坠胀", "恶寒发热", "无恶寒发热"]),
        creatable: true,
        span: 2
      },
      {
        key: "recentAggravation",
        label: "近期加重情况",
        kind: "select",
        options: options(["近期无明显变化", "近3天加重", "近1周加重", "近1月加重", "症状加重伴疼痛", "症状加重伴出血"]),
        creatable: true
      },
      {
        key: "previousTreatment",
        label: "既往相关治疗",
        kind: "multi",
        options: options([
          "未治疗",
          "口服药物",
          "外用药物",
          "坐浴",
          "输液治疗",
          "硬化剂注射",
          "既往手术治疗",
          "保守治疗效果不佳",
          "治疗后好转"
        ]),
        creatable: true
      },
      {
        key: "generalCondition",
        label: "一般情况",
        kind: "multi",
        options: options([
          "精神可",
          "精神差",
          "饮食可",
          "饮食差",
          "睡眠可",
          "入睡困难",
          "睡眠差",
          "小便正常",
          "小便不畅",
          "体重无明显变化",
          "体重下降"
        ]),
        creatable: true,
        span: 2
      },
      { key: "stoolFrequency", label: "大便频次", kind: "input", placeholder: "如：每日1次、每日2-3次" },
      {
        key: "stoolCharacteristics",
        label: "大便性状",
        kind: "multi",
        options: options(["正常", "干结", "稀溏", "干稀不调", "大便频数", "排便困难", "便意频繁"]),
        creatable: true
      },
      {
        key: "presentIllness",
        label: "现病史最终文本（医生可修改）",
        kind: "textarea",
        required: true,
        rows: 6,
        placeholder: "可由上方结构化事实生成，也可由接诊人员或医生直接修订。",
        span: 2
      },
      {
        key: "pastHistory",
        label: "慢性病及重要既往史",
        kind: "multi",
        options: options(["否认慢性病史", "高血压", "糖尿病", "冠心病", "脑血管病", "慢性胃炎", "传染病史", "其他慢性病"]),
        creatable: true,
        span: 2
      },
      {
        key: "surgicalHistory",
        label: "手术史",
        kind: "multi",
        options: options(["否认手术史", "既往肛肠手术", "既往腹部手术", "其他手术"]),
        creatable: true
      },
      { key: "traumaHistory", label: "外伤史", kind: "select", options: options(["否认外伤史", "有外伤史"]), creatable: true },
      {
        key: "transfusionHistory",
        label: "输血史",
        kind: "select",
        options: options(["否认输血史", "有输血史", "不详"]),
        creatable: true
      },
      {
        key: "vaccinationHistory",
        label: "预防接种史",
        kind: "select",
        options: options(["预防接种随社会进行", "按计划接种", "接种不全", "不详"]),
        creatable: true
      },
      {
        key: "medicationHistory",
        label: "用药史",
        kind: "multi",
        options: options(["无长期用药", "降压药", "降糖药", "抗凝药", "激素类药物", "中药治疗"]),
        creatable: true
      },
      {
        key: "allergyHistory",
        label: "过敏史",
        kind: "multi",
        options: options(["否认药物及食物过敏史", "药物过敏", "食物过敏", "其他过敏", "过敏原不详"]),
        creatable: true
      },
      {
        key: "personalHistory",
        label: "个人史",
        kind: "multi",
        options: options([
          "生长于原籍",
          "否认长期外地居住史",
          "无烟酒嗜好",
          "少量吸烟",
          "长期吸烟",
          "少量饮酒",
          "长期饮酒",
          "否认特殊化学品接触史",
          "否认放射性接触史",
          "否认冶游史"
        ]),
        creatable: true,
        span: 2
      },
      {
        key: "maritalHistory",
        label: "婚育史",
        kind: "multi",
        options: options(["适龄结婚", "未婚", "已婚", "离异", "丧偶", "配偶体健", "子女体健", "婚育史无特殊"]),
        creatable: true
      },
      {
        key: "familyHistory",
        label: "家族史",
        kind: "multi",
        options: options([
          "家族史无特殊",
          "否认传染病家族史",
          "否认遗传病家族史",
          "否认代谢性疾病家族史",
          "否认糖尿病家族史",
          "否认血友病家族史",
          "否认肿瘤家族史",
          "否认类似病史"
        ]),
        creatable: true
      },
      { key: "historySupplement", label: "病史补充原文（可选）", kind: "textarea", rows: 3, span: 2 },
      { key: "reviewOpinion", label: "检查材料回看意见", kind: "textarea", rows: 3 },
      { key: "nextStepRecommendation", label: "下一步处置建议", kind: "textarea", rows: 3, span: 2 },
      { key: "dispositionSuggestion", label: "建议门诊/住院", kind: "select", required: true, options: routeOptions },
      {
        key: "recommendedAuxiliaryExams",
        label: "建议辅助检查",
        kind: "multi",
        options: options(["化验室检验"]),
        creatable: true
      }
    ]
  },
  {
    code: "TCM",
    title: "中医岗位",
    shortTitle: "中医",
    owner: "中医",
    roles: ["admin", "tcm"],
    description: "记录辨证事实，不自动生成方剂和长篇病程。",
    fields: [
      {
        key: "tcmDisease",
        label: "中医病名",
        kind: "select",
        required: true,
        options: options(["痔病", "肛裂", "肛漏", "脱肛", "肛痈", "息肉痔", "便秘", "泄泻", "胃痛", "虚劳"]),
        creatable: true
      },
      {
        key: "primarySyndrome",
        label: "主证",
        kind: "select",
        required: true,
        options: options([
          "湿热下注",
          "血热肠燥",
          "气虚下陷",
          "气滞血瘀",
          "阴虚津亏",
          "正虚邪恋",
          "阴虚毒恋",
          "脾虚气陷",
          "肾气不固",
          "火毒蕴结",
          "热毒炽盛",
          "脾虚痰湿"
        ]),
        creatable: true
      },
      {
        key: "concurrentSyndrome",
        label: "兼证",
        kind: "multi",
        options: options(["湿热", "血瘀", "气虚", "阴虚", "血虚", "痰湿", "阳虚", "气滞", "其他"]),
        creatable: true
      },
      {
        key: "inspection",
        label: "望诊",
        kind: "multi",
        required: true,
        options: options(["神志清", "精神可", "精神差", "面色正常", "面色少华", "面色萎黄", "形体适中", "形体偏胖", "形体偏瘦"]),
        creatable: true
      },
      {
        key: "auscultationOlfaction",
        label: "闻诊",
        kind: "multi",
        options: options(["语声清晰", "呼吸平稳", "无特殊气味", "语声低微"]),
        creatable: true
      },
      {
        key: "inquiry",
        label: "问诊",
        kind: "multi",
        required: true,
        options: options([
          "饮食可",
          "饮食差",
          "睡眠可",
          "睡眠差",
          "小便正常",
          "小便不畅",
          "大便干结",
          "大便稀溏",
          "大便正常",
          "体重无明显变化",
          "体重下降"
        ]),
        creatable: true
      },
      {
        key: "palpation",
        label: "切诊",
        kind: "multi",
        options: options(["腹部柔软", "腹部无压痛", "局部压痛", "局部触痛明显"]),
        creatable: true
      },
      {
        key: "tongue",
        label: "舌象",
        kind: "multi",
        required: true,
        options: options(["舌质红", "舌质淡红", "舌质紫暗", "舌质淡胖", "苔薄白", "苔黄腻", "苔白腻", "舌中裂纹", "舌边齿痕"]),
        creatable: true
      },
      {
        key: "pulse",
        label: "脉象",
        kind: "multi",
        required: true,
        options: options(["弦数", "濡数", "细弱", "沉细", "涩", "弦细"]),
        creatable: true
      },
      { key: "syndromeBasis", label: "辨证依据", kind: "textarea", rows: 4, span: 2 },
      {
        key: "treatmentPrinciple",
        label: "治法治则",
        kind: "multi",
        required: true,
        options: options(["健脾祛湿", "益气托毒", "活血化瘀", "清热利湿", "润肠通便", "消肿止痛"]),
        creatable: true,
        span: 2
      }
    ]
  },
  {
    code: "DOCTOR",
    title: "医生诊断与治疗方案",
    shortTitle: "医生",
    owner: "医生",
    roles: ["admin", "doctor"],
    description: "医生确认最终分支，填写西医诊断与真实治疗计划。",
    fields: [
      { key: "finalRoute", label: "最终门诊/住院分支", kind: "select", required: true, options: routeOptions },
      { key: "routeOverrideReason", label: "更改接诊建议的原因", kind: "input", span: 2 },
      {
        key: "primaryWesternDiagnosis",
        label: "西医主诊断",
        kind: "select",
        required: true,
        options: options(["混合痔", "肛瘘", "肛裂", "直肠黏膜脱垂", "直肠息肉", "结肠息肉", "直肠前突", "肛周脓肿"]),
        creatable: true,
        span: 2
      },
      {
        key: "secondaryWesternDiagnoses",
        label: "西医次诊断",
        kind: "multi",
        options: options([
          "肛乳头肥大",
          "直肠炎",
          "肛门湿疹",
          "血栓外痔",
          "内痔",
          "便秘",
          "慢性胃炎",
          "肠易激综合征",
          "高血压",
          "糖尿病",
          "冠心病",
          "肛门松弛"
        ]),
        creatable: true,
        placeholder: "可直接输入后回车添加",
        span: 2
      },
      { key: "diagnosisBasis", label: "诊断依据", kind: "textarea", rows: 3, span: 2 },
      { key: "differentialDiagnoses", label: "待排/鉴别诊断", kind: "textarea", rows: 3, span: 2 },
      { key: "treatmentPath", label: "治疗方式", kind: "select", required: true, options: treatmentOptions },
      { key: "treatmentPlan", label: "治疗方案", kind: "textarea", required: true, rows: 4, span: 2 },
      {
        key: "plannedOperationName",
        label: "拟行手术名称",
        kind: "multi",
        options: options([
          "混合痔外剥内扎术",
          "内痔套扎术",
          "肛瘘切除术",
          "肛裂切除术",
          "肛周脓肿根治术",
          "直肠黏膜悬吊术",
          "直肠前突修补术",
          "经肛直肠息肉切除术",
          "结肠镜下息肉切除术（无痛）"
        ]),
        creatable: true,
        required: true,
        visible: form => form.treatmentPath === "SURGICAL",
        span: 2
      },
      { key: "plannedOperationSite", label: "拟手术部位", kind: "input", visible: form => form.treatmentPath === "SURGICAL" },
      {
        key: "plannedOperationPlan",
        label: "手术计划",
        kind: "textarea",
        rows: 3,
        visible: form => form.treatmentPath === "SURGICAL",
        span: 2
      }
    ]
  },
  {
    code: "SURGERY",
    title: "手术室登记",
    shortTitle: "手术",
    owner: "手术室护士",
    roles: ["admin", "nurse", "nursing"],
    description: "只登记实际发生的手术和术中结果。门诊及非手术患者会自动跳过。",
    fields: [
      {
        key: "actualOperationName",
        label: "实际手术名称",
        kind: "multi",
        required: true,
        options: options([
          "混合痔外剥内扎术",
          "内痔套扎术",
          "肛瘘切除术",
          "肛裂切除术",
          "肛周脓肿根治术",
          "直肠黏膜悬吊术",
          "直肠前突修补术",
          "经肛直肠息肉切除术",
          "结肠镜下息肉切除术（无痛）"
        ]),
        creatable: true,
        span: 2
      },
      { key: "operationDate", label: "手术日期", kind: "date", required: true },
      { key: "operationStartTime", label: "开始时间", kind: "datetime" },
      { key: "operationEndTime", label: "结束时间", kind: "datetime" },
      { key: "operationSite", label: "手术部位", kind: "input" },
      {
        key: "anesthesiaMethod",
        label: "麻醉方式",
        kind: "select",
        options: options(["局麻", "骶麻", "硬膜外麻醉", "静脉麻醉（无痛肠镜）"]),
        creatable: true
      },
      { key: "intraoperativeFindings", label: "术中所见", kind: "textarea", required: true, rows: 4, span: 2 },
      { key: "procedurePerformed", label: "实际实施步骤", kind: "textarea", required: true, rows: 4, span: 2 },
      {
        key: "specimenPathology",
        label: "标本/病理送检",
        kind: "multi",
        options: options(["未送检", "标本已送病理", "组织已送病理", "息肉已送病理"]),
        creatable: true
      },
      {
        key: "bloodLossDrainDressing",
        label: "出血、引流和敷料",
        kind: "multi",
        options: options(["术中出血少", "已彻底止血", "留置引流", "未留置引流", "敷料包扎固定", "油纱填塞"]),
        creatable: true
      },
      {
        key: "complications",
        label: "异常或并发症",
        kind: "multi",
        options: options(["无明显异常", "无并发症", "术中出血", "局部组织损伤", "麻醉相关异常"]),
        creatable: true
      },
      {
        key: "postoperativeDestination",
        label: "术后去向",
        kind: "select",
        options: options(["返回病房", "留观室观察", "转上级医院", "其他"]),
        creatable: true
      },
      { key: "postoperativeHandoff", label: "术后交接说明", kind: "textarea", required: true, rows: 3, span: 2 }
    ]
  },
  {
    code: "REVIEW",
    title: "医生最终复核",
    shortTitle: "复核",
    owner: "医生",
    roles: ["admin", "doctor"],
    description: "核对各岗位事实，确认后生成脱敏前置资料 DOCX。",
    fields: []
  }
];

export const auxiliaryTaskFields: Record<PreAiAuxiliaryTaskType, PreAiFieldConfig[]> = {
  LAB: [
    { key: "project", label: "检验项目", kind: "input", required: true },
    { key: "sampledAt", label: "采样时间", kind: "datetime" },
    { key: "reportedAt", label: "报告时间", kind: "datetime" },
    { key: "result", label: "检验结果", kind: "textarea", required: true, rows: 4, span: 2 },
    { key: "abnormalItems", label: "异常项", kind: "textarea", rows: 3 },
    { key: "conclusion", label: "结论", kind: "textarea", required: true, rows: 3 }
  ],
  ECG: [
    { key: "examinedAt", label: "检查时间", kind: "datetime", required: true },
    { key: "findings", label: "主要表现", kind: "textarea", required: true, rows: 4, span: 2 },
    { key: "conclusion", label: "结论", kind: "textarea", required: true, rows: 3, span: 2 }
  ],
  IMAGING: [
    { key: "modality", label: "检查类型", kind: "input", required: true },
    { key: "bodyPart", label: "检查部位", kind: "input", required: true },
    { key: "examinedAt", label: "检查时间", kind: "datetime" },
    { key: "findings", label: "主要表现", kind: "textarea", required: true, rows: 4, span: 2 },
    { key: "conclusion", label: "结论", kind: "textarea", required: true, rows: 3, span: 2 }
  ]
};

export const stageByCode = (code: PreAiStageCode) => preAiStages.find(stage => stage.code === code)!;

export const stageStatusLabel: Record<string, string> = {
  DRAFT: "待填写",
  COMPLETED: "已完成",
  RETURNED: "已退回",
  SKIPPED: "已跳过"
};

export const encounterStatusLabel: Record<string, string> = {
  IN_PROGRESS: "流程进行中",
  PENDING_REVIEW: "待医生复核",
  REVIEWED: "已复核",
  EXPORTED: "已生成资料",
  CANCELLED: "已取消"
};

export const auxiliaryTaskLabel: Record<PreAiAuxiliaryTaskType, string> = {
  LAB: "检验",
  ECG: "心电",
  IMAGING: "影像"
};
