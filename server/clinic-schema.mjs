const UPDATED_AT = "2026-06-10 08:00:00";

const ROLE_LABELS = {
  admin: "管理员",
  frontdesk: "前台",
  lab: "化验室",
  ecg: "心电室",
  ultrasound: "B超/放射",
  doctor: "医生",
  nurse: "护士/治疗室",
  quality: "质控",
};

const roleLabel = (role) => ROLE_LABELS[role] || ROLE_LABELS.frontdesk;

const admin = ["admin"];
const doctor = ["admin", "doctor"];
const frontdesk = ["admin", "frontdesk"];
const nurse = ["admin", "nurse"];
const lab = ["admin", "lab"];
const ecg = ["admin", "ecg"];
const ultrasound = ["admin", "ultrasound"];
const quality = ["admin", "quality"];

const defaultRecordSections = [
  {
    key: "basic",
    title: "一、基础信息",
    stage: "登记",
    department: "前台/收费处",
    fields: [
      ["hospitalName", "医院名称", frontdesk, true],
      ["patientName", "患者姓名", frontdesk, true],
      ["age", "年龄", frontdesk],
      ["visitNo", "门诊/住院号", frontdesk, true],
      ["admissionCount", "第几次入院", frontdesk],
      ["admissionDate", "入院日期", frontdesk],
      ["dischargeDate", "出院日期", frontdesk],
      ["hospitalDays", "住院天数", frontdesk],
      ["phone", "联系电话", frontdesk],
      ["contactPhone", "联系人电话", frontdesk],
      ["admissionWay", "入院方式", frontdesk],
      ["treatmentType", "治疗类别", frontdesk],
      ["insuranceType", "参保险种", frontdesk],
      ["dischargeWay", "离院方式", frontdesk],
    ],
  },
  {
    key: "chiefComplaint",
    title: "二、主诉",
    stage: "初诊",
    department: "门诊医生",
    fields: [["chiefComplaintText", "主诉", doctor, true]],
  },
  {
    key: "presentIllness",
    title: "三、现病史",
    stage: "初诊",
    department: "门诊医生",
    fields: [
      ["onset", "起病经过", doctor, true],
      ["symptomPattern", "症状性质", doctor],
      ["aggravation", "加重及入院原因", doctor],
      ["generalCondition", "一般情况", doctor],
    ],
  },
  {
    key: "history",
    title: "四、既往史",
    stage: "初诊",
    department: "门诊医生",
    fields: [
      ["operationHistory", "手术史", doctor],
      ["chronicDisease", "慢性病史", doctor],
      ["traumaTransfusion", "外伤/输血史", doctor],
      ["allergyHistory", "过敏史", doctor],
      ["personalHistory", "个人史", doctor],
      ["familyHistory", "婚育/家族史", doctor],
    ],
  },
  {
    key: "tcmInspection",
    title: "五、中医四诊",
    stage: "辨证",
    department: "门诊医生",
    fields: [
      ["tcmLook", "望闻问切", doctor],
      ["tongue", "舌象", doctor],
      ["tcmSyndrome", "中医证型", doctor],
      ["tcmTreatment", "治法", doctor],
    ],
  },
  {
    key: "specialExam",
    title: "六、专科检查",
    stage: "专科查体",
    department: "门诊医生/治疗室",
    fields: [
      ["lithotomyExam", "截石位所见", ["admin", "doctor", "nurse"]],
      ["analTension", "肛门括约肌张力", doctor],
      ["digitalExam", "肛指检查", doctor],
      ["anoscope", "肛门镜", doctor],
    ],
  },
  {
    key: "auxiliary",
    title: "七、辅助检查",
    stage: "检查",
    department: "化验室/心电室/B超放射",
    fields: [
      ["urineRoutine", "尿常规", lab],
      ["biochemistry", "生化全套", lab],
      ["coagulation", "凝血功能", lab, false, "凝血功能报告"],
      ["preOpEight", "术前八项", lab, false, "术前八项报告"],
      ["bloodRoutine", "血常规", lab, false, "血常规报告"],
      ["ecgResult", "心电图", ecg, false, "心电图报告"],
      ["colonoscopy", "无痛电子肠镜", ultrasound],
      ["vitalSigns", "生命体征", nurse],
    ],
  },
  {
    key: "mainDiagnosis",
    title: "八、中西医主诊断",
    stage: "诊断",
    department: "门诊医生",
    fields: [
      ["tcmDiagnosis", "中医诊断", doctor, true],
      ["westernDiagnosis", "西医诊断", doctor, true],
      ["otherMainDiagnosis", "模板可选主病", doctor],
    ],
  },
  {
    key: "secondaryDiagnosis",
    title: "九、次诊断",
    stage: "诊断",
    department: "门诊医生",
    fields: [["secondaryDiagnosisList", "次诊断", doctor]],
  },
  {
    key: "comorbidityTcm",
    title: "十、合并病中医病名及证型",
    stage: "合并病",
    department: "门诊医生",
    fields: [
      ["comorbidityDisease", "合并病病名", doctor],
      ["comorbiditySyndrome", "合并病证型", doctor],
    ],
  },
  {
    key: "operation",
    title: "十一、手术",
    stage: "手术方案",
    department: "门诊医生/手术室",
    fields: [
      ["operationGroup", "分组核心选择", doctor],
      ["operationName", "主要手术", doctor],
      ["additionalOperation", "附加操作", doctor],
      ["operationIndication", "手术指征", doctor],
      ["anesthesia", "麻醉方式", ["admin", "doctor", "nurse"]],
      ["operationLevel", "手术等级", quality],
    ],
  },
  {
    key: "dip",
    title: "十二、DIP",
    stage: "分组",
    department: "质控/病案",
    fields: [
      ["dipGroup", "病组匹配", quality],
      ["reasonableDays", "合理住院天数", quality],
      ["dipCompliance", "合规判断", quality],
    ],
  },
  {
    key: "roundSchedule",
    title: "十三、查房时序",
    stage: "病程",
    department: "质控/门诊医生",
    fields: [["courseSchedule", "病程记录时序", ["admin", "doctor", "quality"]]],
  },
  {
    key: "documentScope",
    title: "十四、自动生成文书范围",
    stage: "文书",
    department: "质控/病案",
    fields: [
      ["generatedDocuments", "生成范围", quality],
      ["documentStandard", "格式标准", quality],
    ],
  },
  {
    key: "qualityCheck",
    title: "十五、质控校验",
    stage: "归档",
    department: "质控/病案",
    fields: [
      ["qualityItems", "质控项目", quality],
      ["qualityReview", "质控意见", quality],
    ],
  },
];

export const defaultRoles = () => [
  {
    id: "frontdesk",
    name: "前台",
    role: "frontdesk",
    members: 0,
    desc: "创建患者、维护基础信息、上传前台采集资料。",
    permissions: ["patient:create", "patient:read", "patient:update", "document:upload"],
    editableSections: ["basic"],
  },
  {
    id: "lab",
    name: "化验室",
    role: "lab",
    members: 0,
    desc: "上传和维护本科室检验报告字段。",
    permissions: ["patient:read", "document:upload", "field:edit"],
    editableSections: ["auxiliary"],
  },
  {
    id: "ecg",
    name: "心电室",
    role: "ecg",
    members: 0,
    desc: "上传和维护心电图检查资料。",
    permissions: ["patient:read", "document:upload", "field:edit"],
    editableSections: ["auxiliary"],
  },
  {
    id: "ultrasound",
    name: "B超/放射",
    role: "ultrasound",
    members: 0,
    desc: "上传和维护影像、B超、肠镜等检查资料。",
    permissions: ["patient:read", "document:upload", "field:edit"],
    editableSections: ["auxiliary"],
  },
  {
    id: "doctor",
    name: "医生",
    role: "doctor",
    members: 0,
    desc: "补充病史、诊断、专科检查和手术方案。",
    permissions: ["patient:read", "field:edit", "document:download"],
    editableSections: ["chiefComplaint", "presentIllness", "history", "specialExam", "mainDiagnosis", "operation"],
  },
  {
    id: "nurse",
    name: "护士/治疗室",
    role: "nurse",
    members: 0,
    desc: "维护生命体征、治疗室配合记录和护理相关字段。",
    permissions: ["patient:read", "document:upload", "field:edit"],
    editableSections: ["specialExam", "auxiliary", "operation"],
  },
  {
    id: "quality",
    name: "质控",
    role: "quality",
    members: 0,
    desc: "质控审核、DIP分组、归档复核。",
    permissions: ["patient:read", "audit:read", "document:restore", "role:grant"],
    editableSections: ["dip", "documentScope", "qualityCheck"],
  },
  {
    id: "admin",
    name: "管理员",
    role: "admin",
    members: 1,
    desc: "系统设置、账号、角色、权限、审计。",
    permissions: ["*"],
    editableSections: defaultRecordSections.map((section) => section.key),
  },
];

export const defaultTemplateFieldRules = () =>
  defaultRecordSections.flatMap((section, sectionIndex) =>
    section.fields.map(([fieldKey, fieldLabel, editors, required = false, evidence = ""], fieldIndex) => ({
      id: `${section.key}-${fieldKey}`,
      sectionKey: section.key,
      sectionTitle: section.title,
      stage: section.stage,
      department: section.department,
      fieldKey,
      fieldLabel,
      editors,
      editorLabels: editors.map((editor) => roleLabel(editor)),
      required: Boolean(required),
      evidence,
      enabled: true,
      printable: true,
      qualityCheck: Boolean(required || evidence),
      sortNo: sectionIndex * 100 + fieldIndex + 1,
      updatedAt: UPDATED_AT,
    })),
  );

const isObject = (value) => value && typeof value === "object" && !Array.isArray(value);

const mergeDefaultRoles = (roles) => {
  const defaults = defaultRoles();
  if (!Array.isArray(roles)) return defaults;

  const storedByRole = new Map(roles.filter(isObject).map((role) => [role.role, role]));
  const defaultsByRole = new Set(defaults.map((role) => role.role));
  const mergedDefaults = defaults.map((role) => ({
    ...role,
    ...(storedByRole.get(role.role) || {}),
    id: storedByRole.get(role.role)?.id || role.id,
    role: role.role,
  }));
  const customRoles = roles.filter((role) => isObject(role) && !defaultsByRole.has(role.role));
  return [...mergedDefaults, ...customRoles];
};

const mergeDefaultFieldRules = (rules) => {
  const defaults = defaultTemplateFieldRules();
  if (!Array.isArray(rules)) return defaults;

  const ruleId = (rule) => rule.id || `${rule.sectionKey}-${rule.fieldKey}`;
  const storedById = new Map(rules.filter(isObject).map((rule) => [ruleId(rule), rule]));
  const defaultIds = new Set(defaults.map((rule) => rule.id));
  const mergedDefaults = defaults.map((rule) => ({
    ...rule,
    ...(storedById.get(rule.id) || {}),
    id: rule.id,
    sectionKey: rule.sectionKey,
    fieldKey: rule.fieldKey,
    sortNo: storedById.get(rule.id)?.sortNo ?? rule.sortNo,
  }));
  const customRules = rules.filter((rule) => isObject(rule) && !defaultIds.has(ruleId(rule)));
  return [...mergedDefaults, ...customRules];
};

export const normalizeClinicSchema = (db) => ({
  ...db,
  roles: mergeDefaultRoles(db?.roles),
  templateFieldRules: mergeDefaultFieldRules(db?.templateFieldRules || db?.fieldRules),
});

