import type { PreAiAuxiliaryTaskType, PreAiStageCode } from "@/api/modules/clinic";

export type PreAiFieldKind =
  | "input"
  | "textarea"
  | "select"
  | "multi"
  | "date"
  | "datetime"
  | "number"
  | "measurement"
  | "repeatable"
  | "template-text";

export type PreAiTemplateGenerator =
  | "chiefComplaint"
  | "presentIllness"
  | "inspectionConclusion"
  | "syndromeBasis"
  | "diagnosisBasis"
  | "treatmentPlan"
  | "surgeryFindings"
  | "procedureSteps"
  | "handoff"
  | "colonoscopyConclusion";

export interface PreAiFieldConfig {
  key: string;
  label: string;
  kind: PreAiFieldKind;
  required?: boolean;
  placeholder?: string;
  options?: Array<{ label: string; value: any }>;
  optionsFor?: (form: Record<string, any>) => Array<{ label: string; value: any }>;
  creatable?: boolean;
  visible?: (form: Record<string, any>) => boolean;
  rows?: number;
  span?: 1 | 2;
  fields?: PreAiFieldConfig[];
  unitOptions?: string[];
  abnormalOptions?: Array<{ label: string; value: string }>;
  templateGenerator?: PreAiTemplateGenerator;
  overrideKey?: string;
  sourceHashKey?: string;
  confirmedKey?: string;
  addLabel?: string;
}

export interface PreAiStageConfig {
  code: PreAiStageCode;
  title: string;
  shortTitle: string;
  owner: string;
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
const ethnicityOptions = options([
  "汉族",
  "回族",
  "藏族",
  "蒙古族",
  "维吾尔族",
  "苗族",
  "彝族",
  "壮族",
  "布依族",
  "朝鲜族",
  "满族",
  "侗族",
  "瑶族",
  "白族",
  "土家族",
  "哈尼族",
  "哈萨克族",
  "傣族",
  "黎族",
  "傈僳族",
  "佤族",
  "畲族",
  "高山族",
  "拉祜族",
  "水族",
  "东乡族",
  "纳西族",
  "景颇族",
  "柯尔克孜族",
  "土族",
  "达斡尔族",
  "仫佬族",
  "羌族",
  "布朗族",
  "撒拉族",
  "毛南族",
  "仡佬族",
  "锡伯族",
  "阿昌族",
  "普米族",
  "塔吉克族",
  "怒族",
  "乌孜别克族",
  "俄罗斯族",
  "鄂温克族",
  "德昂族",
  "保安族",
  "裕固族",
  "京族",
  "塔塔尔族",
  "独龙族",
  "鄂伦春族",
  "赫哲族",
  "门巴族",
  "珞巴族",
  "基诺族",
  "其他",
  "未说明"
]);
const provinceOptions = options([
  "甘肃省",
  "陕西省",
  "四川省",
  "青海省",
  "宁夏回族自治区",
  "新疆维吾尔自治区",
  "北京市",
  "天津市",
  "河北省",
  "山西省",
  "内蒙古自治区",
  "辽宁省",
  "吉林省",
  "黑龙江省",
  "上海市",
  "江苏省",
  "浙江省",
  "安徽省",
  "福建省",
  "江西省",
  "山东省",
  "河南省",
  "湖北省",
  "湖南省",
  "广东省",
  "广西壮族自治区",
  "海南省",
  "重庆市",
  "贵州省",
  "云南省",
  "西藏自治区",
  "香港特别行政区",
  "澳门特别行政区",
  "台湾省",
  "其他"
]);
const durationOptions = options(["1天", "3天", "1周", "2周", "1月", "3月", "半年", "1年", "2年", "5年", "10年以上"]);
const abnormalOptions = options(["正常", "偏高", "偏低", "异常", "危急值", "未判断"]);
const surgeryOptions = options([
  "混合痔外剥内扎术",
  "内痔套扎术",
  "肛瘘切除术",
  "肛裂切除术",
  "肛周脓肿根治术",
  "直肠黏膜悬吊术",
  "直肠前突修补术",
  "经肛直肠息肉切除术",
  "结肠镜下息肉切除术（无痛）"
]);
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

const syndromesByTcmDisease: Record<string, string[]> = {
  痔病: ["湿热下注", "血热肠燥", "气虚下陷", "气滞血瘀"],
  肛裂: ["血热肠燥", "阴虚津亏", "气滞血瘀"],
  肛漏: ["湿热下注", "正虚邪恋", "阴虚毒恋"],
  脱肛: ["脾虚气陷", "肾气不固"],
  肛痈: ["火毒蕴结", "热毒炽盛", "正虚邪恋"],
  息肉痔: ["湿热下注", "气滞血瘀", "脾虚痰湿"],
  便秘: ["血热肠燥", "阴虚津亏", "气虚下陷"],
  泄泻: ["脾虚痰湿", "湿热下注"],
  胃痛: ["气滞血瘀", "脾虚痰湿"],
  虚劳: ["气虚下陷", "阴虚津亏", "脾虚气陷"]
};

const tcmDiseaseByWestern: Record<string, string[]> = {
  高血压: ["眩晕", "头痛"],
  糖尿病: ["消渴"],
  冠心病: ["胸痹", "心痛"],
  慢性胃炎: ["胃痛", "痞满"],
  便秘: ["便秘"],
  直肠炎: ["肠澼", "泄泻"]
};

export const preAiStages: PreAiStageConfig[] = [
  {
    code: "REGISTRATION",
    title: "前台登记",
    shortTitle: "登记",
    owner: "前台",
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
        options: options([
          "居民身份证",
          "港澳居民来往内地通行证",
          "台湾居民来往大陆通行证",
          "外国人永久居留身份证",
          "护照",
          "军官证",
          "出生医学证明",
          "其他"
        ])
      },
      { key: "identityNumber", label: "证件号", kind: "input", placeholder: "仅内部保存，不进入外部文档" },
      { key: "address", label: "常住地址", kind: "input", span: 2 },
      { key: "contactName", label: "联系人姓名", kind: "input" },
      {
        key: "contactRelation",
        label: "联系人关系",
        kind: "select",
        options: options(["配偶", "父亲", "母亲", "子女", "兄弟姐妹", "其他亲属", "朋友", "同事", "本人", "其他"]),
        creatable: true
      },
      { key: "contactPhone", label: "联系人电话", kind: "input" },
      { key: "visitDate", label: "就诊时间", kind: "datetime", required: true },
      {
        key: "nationality",
        label: "民族",
        kind: "select",
        options: ethnicityOptions,
        creatable: true
      },
      {
        key: "nativePlace",
        label: "籍贯",
        kind: "select",
        options: provinceOptions,
        creatable: true
      },
      {
        key: "birthplace",
        label: "出生地",
        kind: "select",
        options: provinceOptions,
        creatable: true
      },
      { key: "maritalStatus", label: "婚姻状态", kind: "select", options: options(["未婚", "已婚", "离异", "丧偶", "未说明"]) },
      {
        key: "admissionMethod",
        label: "入院方式",
        kind: "select",
        options: options(["门诊", "急诊", "转院", "其他"]),
        creatable: true
      },
      {
        key: "insuranceType",
        label: "参保险种",
        kind: "select",
        options: options(["职工医保", "城乡居民医保", "商业保险", "异地医保", "自费", "其他"]),
        creatable: true
      },
      {
        key: "paymentMethod",
        label: "付费方式",
        kind: "select",
        options: options(["医保结算", "现金", "银行卡", "移动支付", "其他"]),
        creatable: true
      },
      {
        key: "patientSource",
        label: "患者来源",
        kind: "select",
        options: options(["门诊转入", "急诊转入", "复诊", "院内转科", "外院转入", "体检发现", "其他"]),
        creatable: true
      },
      { key: "medicalRecordNo", label: "病案号", kind: "input" },
      { key: "inpatientNo", label: "住院号", kind: "input" },
      {
        key: "ward",
        label: "病区",
        kind: "select",
        options: options(["肛肠科病区", "普通外科病区", "日间病房", "门诊观察区", "其他"]),
        creatable: true
      },
      { key: "bedNo", label: "床号", kind: "input" },
      { key: "admissionCount", label: "第几次入院", kind: "number" },
      { key: "registrationNote", label: "登记备注", kind: "textarea", rows: 3, span: 2 }
    ]
  },
  {
    code: "INSPECTION",
    title: "检查室",
    shortTitle: "检查",
    owner: "检查室",
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
      {
        key: "lesionLocation",
        label: "病变位置",
        kind: "select",
        options: options(["肛缘", "肛管", "齿状线", "直肠下段", "会阴部", "骶尾部", "其他"]),
        creatable: true
      },
      {
        key: "clockPosition",
        label: "方向/钟点位",
        kind: "multi",
        options: options(["1点", "2点", "3点", "4点", "5点", "6点", "7点", "8点", "9点", "10点", "11点", "12点"]),
        creatable: true
      },
      { key: "lesionSize", label: "病灶大小", kind: "measurement", unitOptions: ["mm", "cm"], abnormalOptions },
      { key: "lesionExtent", label: "病灶范围", kind: "measurement", unitOptions: ["mm", "cm"], abnormalOptions },
      { key: "lesionDepth", label: "病灶深度", kind: "measurement", unitOptions: ["mm", "cm"], abnormalOptions },
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
      {
        key: "factualConclusion",
        label: "检查事实结论",
        kind: "template-text",
        required: true,
        rows: 4,
        span: 2,
        templateGenerator: "inspectionConclusion",
        overrideKey: "factualConclusionOverride",
        sourceHashKey: "factualConclusionSourceHash",
        confirmedKey: "factualConclusionConfirmed"
      }
    ]
  },
  {
    code: "RECEPTION",
    title: "接诊室",
    shortTitle: "接诊",
    owner: "接诊室 / 医生复核",
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
      {
        key: "symptomDuration",
        label: "主要症状病程",
        kind: "select",
        options: durationOptions,
        creatable: true,
        placeholder: "选择常用病程，或直接输入如“3月余”"
      },
      {
        key: "chiefComplaintText",
        label: "主诉预览",
        kind: "template-text",
        required: true,
        span: 2,
        templateGenerator: "chiefComplaint"
      },
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
      {
        key: "stoolFrequency",
        label: "大便频次",
        kind: "select",
        options: options(["每日1次", "每日2-3次", "每日3次以上", "2日1次", "3日1次", "每周1-2次", "无规律"]),
        creatable: true,
        placeholder: "选择常用频次，或直接输入"
      },
      {
        key: "stoolCharacteristics",
        label: "大便性状",
        kind: "multi",
        options: options(["正常", "干结", "稀溏", "干稀不调", "大便频数", "排便困难", "便意频繁"]),
        creatable: true
      },
      {
        key: "presentIllness",
        label: "现病史自动生成与医生修订",
        kind: "template-text",
        required: true,
        rows: 6,
        span: 2,
        templateGenerator: "presentIllness",
        overrideKey: "presentIllnessOverride",
        sourceHashKey: "presentIllnessSourceHash",
        confirmedKey: "presentIllnessConfirmed"
      },
      {
        key: "chronicDiseaseItems",
        label: "慢性病史明细",
        kind: "repeatable",
        addLabel: "添加慢性病",
        span: 2,
        fields: [
          {
            key: "disease",
            label: "疾病",
            kind: "select",
            options: options(["高血压", "糖尿病", "冠心病", "脑血管病", "慢性胃炎", "慢性肾病", "其他"]),
            creatable: true,
            required: true
          },
          { key: "duration", label: "病程", kind: "select", options: durationOptions, creatable: true },
          {
            key: "treatment",
            label: "治疗情况",
            kind: "select",
            options: options(["未治疗", "规律治疗", "间断治疗", "治疗不详", "其他"]),
            creatable: true
          },
          {
            key: "control",
            label: "控制情况",
            kind: "select",
            options: options(["控制良好", "控制一般", "控制不佳", "不详"]),
            creatable: true
          }
        ]
      },
      {
        key: "surgicalHistoryItems",
        label: "手术史明细",
        kind: "repeatable",
        addLabel: "添加既往手术",
        span: 2,
        fields: [
          { key: "year", label: "年份", kind: "number" },
          { key: "operationName", label: "手术名称", kind: "select", options: surgeryOptions, creatable: true, required: true },
          {
            key: "site",
            label: "部位",
            kind: "select",
            options: options(["肛管", "直肠", "结肠", "肛周", "骶尾部", "腹部", "其他"]),
            creatable: true
          },
          {
            key: "result",
            label: "结果",
            kind: "select",
            options: options(["恢复良好", "症状缓解", "症状复发", "遗留并发症", "不详"]),
            creatable: true
          }
        ]
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
        options: options(["无长期用药", "降压药", "降糖药", "抗凝药", "激素类药物", "中药治疗", "其他"]),
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
      { key: "historySupplement", label: "病史补充", kind: "textarea", rows: 3, span: 2 },
      { key: "specialCircumstances", label: "其他特殊情况", kind: "textarea", rows: 3, span: 2 },
      {
        key: "reviewOpinion",
        label: "检查材料回看意见",
        kind: "select",
        options: options(["无补充", "与检查一致", "需复查", "需补充检查", "存在异常需处理", "其他"]),
        creatable: true
      },
      {
        key: "nextStepRecommendation",
        label: "下一步处置建议",
        kind: "multi",
        options: options(["门诊处理", "住院治疗", "完善化验", "心电", "影像", "肠镜", "手术评估", "保守治疗", "其他"]),
        creatable: true,
        span: 2
      },
      { key: "dispositionSuggestion", label: "建议门诊/住院", kind: "select", required: true, options: routeOptions },
      {
        key: "recommendedAuxiliaryExams",
        label: "建议辅助检查",
        kind: "multi",
        options: options(["化验", "心电", "影像", "肠镜"]),
        creatable: true
      }
    ]
  },
  {
    code: "TCM",
    title: "中医岗位",
    shortTitle: "中医",
    owner: "中医",
    description: "四诊、舌脉和证型均需中医师逐项确认；合并病按疾病逐条辨证。",
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
        optionsFor: form => options(syndromesByTcmDisease[form.tcmDisease] || []),
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
      {
        key: "syndromeBasis",
        label: "辨证依据自动生成与修订",
        kind: "template-text",
        rows: 4,
        span: 2,
        templateGenerator: "syndromeBasis",
        overrideKey: "syndromeBasisOverride",
        sourceHashKey: "syndromeBasisSourceHash",
        confirmedKey: "syndromeBasisConfirmed"
      },
      {
        key: "treatmentPrinciple",
        label: "治法治则",
        kind: "multi",
        required: true,
        options: options(["健脾祛湿", "益气托毒", "活血化瘀", "清热利湿", "润肠通便", "消肿止痛", "益气升提", "养阴生津"]),
        creatable: true,
        span: 2
      },
      {
        key: "comorbidTcmItems",
        label: "合并病中医辨证明细",
        kind: "repeatable",
        addLabel: "添加合并症辨证",
        span: 2,
        fields: [
          {
            key: "westernComorbidity",
            label: "西医合并症",
            kind: "select",
            options: options(["高血压", "糖尿病", "冠心病", "慢性胃炎", "便秘", "直肠炎", "其他"]),
            creatable: true,
            required: true
          },
          {
            key: "includedInTcm",
            label: "是否纳入中医辨证",
            kind: "select",
            options: [
              { label: "是", value: true },
              { label: "否", value: false }
            ],
            required: true
          },
          {
            key: "tcmDisease",
            label: "中医病名",
            kind: "select",
            optionsFor: form => options(tcmDiseaseByWestern[form.westernComorbidity] || []),
            creatable: true,
            visible: form => form.includedInTcm === true
          },
          {
            key: "syndrome",
            label: "证型",
            kind: "select",
            optionsFor: form => options(syndromesByTcmDisease[form.tcmDisease] || []),
            creatable: true,
            visible: form => form.includedInTcm === true
          },
          { key: "note", label: "补充说明", kind: "input" }
        ]
      }
    ]
  },
  {
    code: "DOCTOR",
    title: "医生诊断与治疗方案",
    shortTitle: "医生",
    owner: "主管医生",
    description: "医生确认最终分支、西医诊断、诊断依据和真实治疗计划。",
    fields: [
      { key: "finalRoute", label: "最终门诊/住院分支", kind: "select", required: true, options: routeOptions },
      {
        key: "primaryWesternDiagnosis",
        label: "西医主诊断",
        kind: "select",
        required: true,
        options: options(["混合痔", "内痔", "外痔", "肛裂", "肛瘘", "肛周脓肿", "直肠脱垂", "直肠前突", "直肠息肉", "其他"]),
        creatable: true
      },
      {
        key: "secondaryDiagnosisItems",
        label: "西医次诊断与合并症",
        kind: "repeatable",
        addLabel: "添加次诊断",
        span: 2,
        fields: [
          {
            key: "name",
            label: "诊断名称",
            kind: "select",
            options: options([
              "肛裂",
              "肛瘘",
              "肛周脓肿",
              "直肠息肉",
              "直肠炎",
              "高血压",
              "糖尿病",
              "冠心病",
              "肛门松弛",
              "其他"
            ]),
            creatable: true,
            required: true
          },
          {
            key: "category",
            label: "分类",
            kind: "select",
            options: [
              { label: "局部次诊断", value: "LOCAL" },
              { label: "全身合并症", value: "COMORBIDITY" }
            ],
            required: true
          }
        ]
      },
      {
        key: "diagnosisEvidence",
        label: "诊断依据来源",
        kind: "multi",
        options: options(["主诉符合", "专科检查支持", "肛门镜支持", "化验支持", "肠镜支持", "影像支持", "既往病史支持", "其他"]),
        creatable: true,
        span: 2
      },
      {
        key: "diagnosisBasis",
        label: "诊断依据自动生成与修订",
        kind: "template-text",
        rows: 3,
        span: 2,
        templateGenerator: "diagnosisBasis",
        overrideKey: "diagnosisBasisOverride",
        sourceHashKey: "diagnosisBasisSourceHash",
        confirmedKey: "diagnosisBasisConfirmed"
      },
      {
        key: "differentialDiagnoses",
        label: "待排/鉴别诊断",
        kind: "multi",
        optionsFor: form =>
          options(
            form.primaryWesternDiagnosis === "混合痔"
              ? ["直肠息肉", "直肠癌", "肛裂"]
              : form.primaryWesternDiagnosis === "肛瘘"
                ? ["肛周脓肿", "藏毛窦", "化脓性汗腺炎"]
                : form.primaryWesternDiagnosis === "肛裂"
                  ? ["肛周感染", "克罗恩病相关溃疡", "肛管肿瘤"]
                  : ["直肠癌", "炎症性肠病", "其他"]
          ),
        creatable: true,
        span: 2
      },
      { key: "treatmentPath", label: "治疗方式", kind: "select", required: true, options: treatmentOptions },
      {
        key: "treatmentMeasures",
        label: "主要措施",
        kind: "multi",
        options: options(["饮食及排便指导", "中药内服", "中药熏洗", "局部换药", "止痛", "抗感染", "补液", "手术治疗", "其他"]),
        creatable: true,
        span: 2
      },
      {
        key: "medicationDirections",
        label: "用药方向",
        kind: "multi",
        options: options(["清热利湿", "活血化瘀", "润肠通便", "抗感染", "止血", "止痛", "调节肠道菌群", "其他"]),
        creatable: true
      },
      {
        key: "examPlans",
        label: "检查安排",
        kind: "multi",
        options: options(["血常规", "尿常规", "生化", "凝血", "术前八项", "心电", "影像", "肠镜", "病理", "其他"]),
        creatable: true
      },
      {
        key: "surgeryArrangements",
        label: "手术安排",
        kind: "multi",
        options: options(["暂不手术", "择期手术", "急诊手术", "完善术前检查后手术", "其他"]),
        creatable: true,
        visible: form => form.treatmentPath === "SURGICAL"
      },
      {
        key: "observationFocus",
        label: "观察重点",
        kind: "multi",
        options: options(["便血", "疼痛", "脱出", "排便", "体温", "创面渗血", "尿潴留", "其他"]),
        creatable: true
      },
      { key: "admissionSeverity", label: "入院病情", kind: "select", options: options(["一般", "急", "危", "重"]) },
      { key: "treatmentCategory", label: "治疗类别", kind: "select", options: options(["中医", "西医", "中西医结合"]) },
      {
        key: "treatmentPlan",
        label: "治疗方案自动生成与修订",
        kind: "template-text",
        required: true,
        rows: 4,
        span: 2,
        templateGenerator: "treatmentPlan",
        overrideKey: "treatmentPlanOverride",
        sourceHashKey: "treatmentPlanSourceHash",
        confirmedKey: "treatmentPlanConfirmed"
      },
      {
        key: "plannedPrimaryOperation",
        label: "拟行主术式",
        kind: "select",
        options: surgeryOptions,
        creatable: true,
        required: true,
        visible: form => form.treatmentPath === "SURGICAL"
      },
      {
        key: "plannedSecondaryOperations",
        label: "拟行次术式/附加操作",
        kind: "multi",
        options: surgeryOptions,
        creatable: true,
        visible: form => form.treatmentPath === "SURGICAL"
      },
      {
        key: "operationIndications",
        label: "手术指征",
        kind: "multi",
        options: options([
          "症状反复",
          "保守治疗无效",
          "出血明显",
          "疼痛明显",
          "脱出影响生活",
          "存在感染",
          "存在梗阻风险",
          "病理性质待明确",
          "其他"
        ]),
        creatable: true,
        visible: form => form.treatmentPath === "SURGICAL",
        span: 2
      },
      {
        key: "plannedOperationSite",
        label: "拟手术部位",
        kind: "select",
        options: options(["肛管", "直肠", "结肠", "肛周", "骶尾部", "其他"]),
        creatable: true,
        visible: form => form.treatmentPath === "SURGICAL"
      },
      {
        key: "recommendedAnesthesia",
        label: "麻醉建议",
        kind: "select",
        options: options(["局麻", "骶麻", "硬膜外麻醉", "腰麻", "静脉麻醉", "全麻"]),
        creatable: true,
        visible: form => form.treatmentPath === "SURGICAL"
      },
      {
        key: "operationGrade",
        label: "手术等级",
        kind: "select",
        options: options(["一级", "二级", "三级", "四级", "待确认"]),
        visible: form => form.treatmentPath === "SURGICAL"
      },
      {
        key: "specialOperationPlan",
        label: "特殊计划（可选）",
        kind: "input",
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
    description: "只登记实际发生的手术和术中结果。门诊及非手术患者会自动跳过。",
    fields: [
      {
        key: "actualPrimaryOperation",
        label: "实际主术式",
        kind: "select",
        required: true,
        options: surgeryOptions,
        creatable: true
      },
      {
        key: "actualSecondaryOperations",
        label: "实际次术式及附加操作",
        kind: "multi",
        options: surgeryOptions,
        creatable: true
      },
      { key: "operationDate", label: "手术日期", kind: "date", required: true },
      { key: "operationStartTime", label: "开始时间", kind: "datetime" },
      { key: "operationEndTime", label: "结束时间", kind: "datetime" },
      {
        key: "operationSite",
        label: "手术部位",
        kind: "select",
        options: options(["肛管", "直肠", "结肠", "肛周", "骶尾部", "其他"]),
        creatable: true
      },
      {
        key: "anesthesiaMethod",
        label: "麻醉方式",
        kind: "select",
        options: options(["局麻", "骶麻", "硬膜外麻醉", "静脉麻醉（无痛肠镜）"]),
        creatable: true
      },
      {
        key: "intraoperativeFindingOptions",
        label: "术中所见模板短语",
        kind: "multi",
        options: options([
          "病灶位置与术前检查一致",
          "局部组织充血水肿",
          "病灶边界清楚",
          "未见活动性大出血",
          "括约肌结构可辨",
          "未见明显异常占位",
          "其他"
        ]),
        creatable: true,
        span: 2
      },
      {
        key: "intraoperativeFindings",
        label: "术中所见自动生成与修订",
        kind: "template-text",
        required: true,
        rows: 4,
        span: 2,
        templateGenerator: "surgeryFindings",
        overrideKey: "intraoperativeFindingsOverride",
        sourceHashKey: "intraoperativeFindingsSourceHash",
        confirmedKey: "intraoperativeFindingsConfirmed"
      },
      {
        key: "procedureStepOptions",
        label: "实际实施步骤模板短语",
        kind: "multi",
        options: options([
          "常规消毒铺巾",
          "麻醉满意后开始手术",
          "显露并确认病灶",
          "按计划完成主术式",
          "完成附加操作",
          "彻底止血",
          "清点器械敷料无误",
          "创面覆盖敷料",
          "其他"
        ]),
        creatable: true,
        span: 2
      },
      {
        key: "procedurePerformed",
        label: "实际实施步骤自动生成与修订",
        kind: "template-text",
        required: true,
        rows: 4,
        span: 2,
        templateGenerator: "procedureSteps",
        overrideKey: "procedurePerformedOverride",
        sourceHashKey: "procedurePerformedSourceHash",
        confirmedKey: "procedurePerformedConfirmed"
      },
      {
        key: "specimenPathology",
        label: "标本/病理送检",
        kind: "multi",
        options: options(["未送检", "标本已送病理", "组织已送病理", "息肉已送病理"]),
        creatable: true
      },
      { key: "bloodLossMeasurement", label: "术中出血量", kind: "measurement", unitOptions: ["mL"], abnormalOptions },
      {
        key: "drainageOptions",
        label: "引流",
        kind: "multi",
        options: options(["未留置引流", "留置引流条", "留置引流管", "其他"]),
        creatable: true
      },
      {
        key: "dressingOptions",
        label: "敷料",
        kind: "multi",
        options: options(["敷料包扎固定", "油纱填塞", "纱布覆盖", "其他"]),
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
      {
        key: "postoperativeHandoffOptions",
        label: "术后交接状态",
        kind: "multi",
        options: options([
          "生命体征平稳",
          "意识清楚",
          "创面敷料固定",
          "引流通畅",
          "标本已交接",
          "医嘱已交接",
          "返回病房",
          "留观",
          "其他"
        ]),
        creatable: true,
        span: 2
      },
      {
        key: "postoperativeHandoff",
        label: "术后交接自动生成与修订",
        kind: "template-text",
        required: true,
        rows: 3,
        span: 2,
        templateGenerator: "handoff",
        overrideKey: "postoperativeHandoffOverride",
        sourceHashKey: "postoperativeHandoffSourceHash",
        confirmedKey: "postoperativeHandoffConfirmed"
      },
      {
        key: "physicianConfirmed",
        label: "手术医生确认",
        kind: "select",
        required: true,
        options: [
          { label: "尚未确认", value: false as any },
          { label: "已由手术医生确认", value: true as any }
        ],
        span: 2
      }
    ]
  },
  {
    code: "REVIEW",
    title: "医生最终复核",
    shortTitle: "复核",
    owner: "医生",
    description: "核对各岗位事实，确认后生成脱敏前置资料 DOCX。",
    fields: []
  }
];

export const auxiliaryTaskFields: Record<PreAiAuxiliaryTaskType, PreAiFieldConfig[]> = {
  LAB: [
    {
      key: "project",
      label: "检验项目/检验组",
      kind: "select",
      required: true,
      options: options(["血常规", "尿常规", "生化", "凝血", "术前八项", "血型及Rh", "其他"]),
      creatable: true
    },
    { key: "sampledAt", label: "采样时间", kind: "datetime" },
    { key: "reportedAt", label: "报告时间", kind: "datetime" },
    { key: "result", label: "检验结果", kind: "textarea", required: true, rows: 4, span: 2 },
    { key: "abnormalItems", label: "异常项", kind: "textarea", rows: 3 },
    { key: "conclusion", label: "结论", kind: "textarea", required: true, rows: 3 },
    { key: "rawReport", label: "原始报告全文（兜底）", kind: "textarea", rows: 3, span: 2 }
  ],
  ECG: [
    { key: "examinedAt", label: "检查时间", kind: "datetime", required: true },
    {
      key: "findings",
      label: "主要表现",
      kind: "multi",
      required: true,
      options: options(["窦性心律", "心率正常", "心动过速", "心动过缓", "ST-T改变", "房性早搏", "室性早搏", "传导阻滞", "其他"]),
      creatable: true,
      span: 2
    },
    {
      key: "conclusion",
      label: "结论",
      kind: "select",
      required: true,
      options: options(["正常心电图", "大致正常心电图", "异常心电图", "建议复查", "建议心内科会诊", "其他"]),
      creatable: true,
      span: 2
    },
    { key: "rawReport", label: "原始报告全文（兜底）", kind: "textarea", rows: 3, span: 2 }
  ],
  IMAGING: [
    {
      key: "modality",
      label: "检查类型",
      kind: "select",
      required: true,
      options: options(["超声", "X线", "CT", "MRI", "其他"]),
      creatable: true
    },
    {
      key: "bodyPart",
      label: "检查部位",
      kind: "select",
      required: true,
      options: options(["胸部", "腹部", "盆腔", "肝胆胰脾", "泌尿系", "肛周", "其他"]),
      creatable: true
    },
    { key: "examinedAt", label: "检查时间", kind: "datetime" },
    {
      key: "findings",
      label: "主要表现",
      kind: "multi",
      required: true,
      options: options(["未见明显异常", "炎症表现", "占位性病变", "积液", "结石", "淋巴结增大", "其他"]),
      creatable: true,
      span: 2
    },
    {
      key: "conclusion",
      label: "结论",
      kind: "select",
      required: true,
      options: options(["未见明显异常", "异常，建议结合临床", "建议复查", "建议进一步检查", "其他"]),
      creatable: true,
      span: 2
    },
    { key: "rawReport", label: "原始报告全文（兜底）", kind: "textarea", rows: 3, span: 2 }
  ],
  VITAL_SIGNS: [
    { key: "measuredAt", label: "测量时间", kind: "datetime", required: true },
    { key: "systolicBp", label: "收缩压", kind: "measurement", required: true, unitOptions: ["mmHg"], abnormalOptions },
    { key: "diastolicBp", label: "舒张压", kind: "measurement", required: true, unitOptions: ["mmHg"], abnormalOptions },
    { key: "temperature", label: "体温", kind: "measurement", required: true, unitOptions: ["℃"], abnormalOptions },
    { key: "pulse", label: "脉搏", kind: "measurement", required: true, unitOptions: ["次/分"], abnormalOptions },
    { key: "respiration", label: "呼吸", kind: "measurement", required: true, unitOptions: ["次/分"], abnormalOptions },
    {
      key: "nursingConditions",
      label: "特殊护理情况",
      kind: "multi",
      options: options(["无需特殊护理", "跌倒风险", "压疮风险", "疼痛护理", "术前准备", "术后观察", "其他"]),
      creatable: true,
      span: 2
    },
    { key: "note", label: "异常补充", kind: "textarea", rows: 2, span: 2 }
  ],
  COLONOSCOPY: [
    {
      key: "status",
      label: "肠镜状态",
      kind: "select",
      required: true,
      options: [
        { label: "未查", value: "NOT_DONE" },
        { label: "已查", value: "COMPLETED" },
        { label: "患者拒绝", value: "REFUSED" },
        { label: "暂缓", value: "DEFERRED" }
      ]
    },
    { key: "examinedAt", label: "检查时间", kind: "datetime", visible: form => form.status === "COMPLETED", required: true },
    {
      key: "scope",
      label: "检查范围",
      kind: "select",
      options: options(["直肠", "直乙结肠", "全结肠", "回盲部", "其他"]),
      creatable: true,
      visible: form => form.status === "COMPLETED",
      required: true
    },
    {
      key: "findings",
      label: "肠镜所见",
      kind: "multi",
      options: options(["未见明显异常", "直肠炎", "结肠炎", "息肉", "溃疡", "憩室", "占位", "其他"]),
      creatable: true,
      visible: form => form.status === "COMPLETED",
      required: true,
      span: 2
    },
    {
      key: "lesionLocation",
      label: "病变部位",
      kind: "multi",
      options: options(["直肠", "乙状结肠", "降结肠", "横结肠", "升结肠", "回盲部", "其他"]),
      creatable: true,
      visible: form => form.status === "COMPLETED"
    },
    { key: "lesionCount", label: "数量", kind: "number", visible: form => form.status === "COMPLETED" },
    {
      key: "lesionSize",
      label: "大小",
      kind: "measurement",
      unitOptions: ["mm", "cm"],
      abnormalOptions,
      visible: form => form.status === "COMPLETED"
    },
    {
      key: "lesionMorphology",
      label: "形态",
      kind: "multi",
      options: options(["有蒂", "亚蒂", "广基", "扁平", "表面光滑", "表面充血", "易出血", "其他"]),
      creatable: true,
      visible: form => form.status === "COMPLETED"
    },
    {
      key: "biopsyPerformed",
      label: "活检",
      kind: "select",
      options: options(["未活检", "已活检"]),
      visible: form => form.status === "COMPLETED"
    },
    {
      key: "resectionPerformed",
      label: "切除",
      kind: "select",
      options: options(["未切除", "已切除", "部分切除"]),
      visible: form => form.status === "COMPLETED"
    },
    {
      key: "pathologySubmitted",
      label: "送病理",
      kind: "select",
      options: options(["未送病理", "已送病理"]),
      visible: form => form.status === "COMPLETED"
    },
    {
      key: "conclusion",
      label: "肠镜结论",
      kind: "template-text",
      templateGenerator: "colonoscopyConclusion",
      visible: form => form.status === "COMPLETED",
      required: true,
      span: 2
    },
    {
      key: "abnormalDescription",
      label: "异常特殊描述（可选）",
      kind: "textarea",
      rows: 3,
      visible: form => form.status === "COMPLETED",
      span: 2
    }
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
  IMAGING: "影像",
  VITAL_SIGNS: "生命体征",
  COLONOSCOPY: "肠镜"
};
