export type UserRole =
  | "admin"
  | "frontdesk"
  | "reception"
  | "lab"
  | "ecg"
  | "ultrasound"
  | "inspection"
  | "doctor"
  | "nurse"
  | "nursing"
  | "manager"
  | "quality";

export type FieldKind = "input" | "textarea" | "select" | "attachment";

export type FieldInputType = "text" | "date" | "number" | "tel";

export type LabPanelKey = "urineRoutine" | "biochemistry" | "coagulation" | "preOpEight" | "bloodRoutine";

export interface RecordField {
  key: string;
  label: string;
  value: string;
  kind: FieldKind;
  inputType?: FieldInputType;
  editors: UserRole[];
  required?: boolean;
  enabled?: boolean;
  printable?: boolean;
  qualityCheck?: boolean;
  options?: string[];
  evidence?: string;
  placeholder?: string;
  printLabel?: string;
  unit?: string;
  min?: number;
  max?: number;
  maxLength?: number;
  pattern?: string;
  validationMessage?: string;
  labPanel?: LabPanelKey;
}

export interface RecordSection {
  key: string;
  title: string;
  stage: string;
  owner: string;
  department: string;
  status: "done" | "active" | "waiting" | "locked";
  description: string;
  fields: RecordField[];
}

export interface PatientLifecycleStage {
  key: string;
  title: string;
  shortTitle: string;
  owner: string;
  department: string;
  roles: UserRole[];
  sectionKeys: string[];
  stageKeywords: string[];
  skipForVisitTypes?: string[];
}

export interface RecordAttachment {
  key: string;
  title: string;
  department: string;
  fieldKey: string;
  fieldLabel: string;
  fileName: string;
  url: string;
  storagePath?: string;
  uploadedAt: string;
  uploader: string;
  uploaderRole?: string;
  sourceRole?: string;
  batchId?: string;
  batchName?: string;
  classifyStatus?: "matched" | "pending" | "manual";
  remark?: string;
  status?: "active" | "voided";
  voidReason?: string;
  voidedAt?: string;
  voidedBy?: string;
  restoredAt?: string;
  restoredBy?: string;
}

export const ROLE_LABELS: Record<UserRole, string> = {
  admin: "管理员",
  frontdesk: "前台",
  reception: "接诊",
  lab: "化验室",
  ecg: "心电室",
  ultrasound: "B超/放射",
  inspection: "检查室",
  doctor: "医生",
  nurse: "护士/治疗室",
  nursing: "护理部",
  manager: "院办/管理",
  quality: "质控"
};

export const USER_ROLES: UserRole[] = [
  "admin",
  "frontdesk",
  "reception",
  "inspection",
  "lab",
  "ecg",
  "ultrasound",
  "doctor",
  "nurse",
  "nursing",
  "manager",
  "quality"
];

export const USER_ROLE_OPTIONS: { label: string; value: UserRole }[] = USER_ROLES.map(value => ({
  label: ROLE_LABELS[value],
  value
}));

export const roleLabel = (role?: string) => ROLE_LABELS[(role as UserRole) || "frontdesk"] ?? "前台";

export const canEditField = (role: string | undefined, field: RecordField) => {
  if (role === "admin") return true;
  return Array.isArray(field.editors) && field.editors.includes(role as UserRole);
};

export const canEditSection = (role: string | undefined, section: RecordSection) => {
  return section.fields.some(field => canEditField(role, field));
};

export const editorLabels = (editors: UserRole[] = []) =>
  editors
    .map(item => ROLE_LABELS[item])
    .filter(Boolean)
    .join("、");

export const allRecordFields = () => [...recordSections.flatMap(section => section.fields), ...labReportRecordFields];

export const recordAttachments: RecordAttachment[] = [];

const doctor: UserRole[] = ["admin", "doctor"];
const frontdesk: UserRole[] = ["admin", "frontdesk"];
const reception: UserRole[] = ["admin", "frontdesk", "reception"];
const firstVisitCollaborators: UserRole[] = ["admin", "frontdesk", "reception", "doctor"];
const nurse: UserRole[] = ["admin", "nurse"];
const nursing: UserRole[] = ["admin", "nurse", "nursing"];
const inspection: UserRole[] = ["admin", "inspection"];
const lab: UserRole[] = ["admin", "lab"];
const ecg: UserRole[] = ["admin", "ecg"];
const ultrasound: UserRole[] = ["admin", "ultrasound"];
const quality: UserRole[] = ["admin", "quality"];
export const healthArchiveCollaborators: UserRole[] = [
  "admin",
  "frontdesk",
  "reception",
  "doctor",
  "nurse",
  "nursing",
  "lab",
  "ecg",
  "ultrasound",
  "inspection",
  "quality"
];
export const serviceCollaborators: UserRole[] = ["admin", "frontdesk", "reception", "doctor", "nurse", "nursing", "quality"];
export const screeningCollaborators: UserRole[] = [
  "admin",
  "lab",
  "ecg",
  "ultrasound",
  "inspection",
  "doctor",
  "nurse",
  "nursing",
  "quality"
];

const labReportEditors: UserRole[] = ["admin", "doctor", "lab"];
const labReportSummaryEditors: UserRole[] = ["admin", "doctor", "lab", "ecg", "nurse"];

export const labReportRecordFields: RecordField[] = [
  { key: "bloodRoutine", label: "血常规报告摘要", value: "", kind: "textarea", editors: labReportEditors },
  { key: "bloodRoutineStatus", label: "血常规状态", value: "", kind: "select", editors: labReportEditors },
  { key: "bloodWbc", label: "WBC", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "bloodNeuPercent", label: "NeU%", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "bloodLymPercent", label: "Lym%", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "bloodMonPercent", label: "Mon%", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "bloodRbc", label: "RBC", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "bloodHgb", label: "HGB", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "bloodPlt", label: "PLT", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_bloodRoutine_neuCount", label: "NeU#", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_bloodRoutine_lymCount", label: "Lym#", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_bloodRoutine_monCount", label: "Mon#", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "biochemistry", label: "生化肝肾功摘要", value: "", kind: "textarea", editors: labReportEditors },
  { key: "liverFunctionStatus", label: "肝功能状态", value: "", kind: "select", editors: labReportEditors },
  { key: "renalFunctionStatus", label: "肾功能状态", value: "", kind: "select", editors: labReportEditors },
  { key: "fastingGlucoseStatus", label: "空腹血糖状态", value: "", kind: "select", editors: labReportEditors },
  { key: "bloodLipidStatus", label: "血脂状态", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_biochemistry_glu", label: "Glu", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_tbil", label: "T-Bil", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_dbil", label: "D-Bil", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_alt", label: "ALT", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_ast", label: "AST", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_alp", label: "ALP", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_ggt", label: "GGT", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_tp", label: "TP", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_alb", label: "ALB", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_glo", label: "Glo", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_ag", label: "A/G", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_tg", label: "TG", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_tc", label: "TC", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_crea", label: "CREA", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_ua", label: "UA", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_urea", label: "UREA", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_k", label: "K", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_na", label: "Na", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_cl", label: "CL", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_biochemistry_ca", label: "Ca", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "preOpEight", label: "术前筛查摘要", value: "", kind: "textarea", editors: labReportEditors },
  { key: "preOpEightStatus", label: "术前筛查状态", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_hbvFive_hbsag", label: "HBsAg", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_hbvFive_hbsab", label: "HBsAb", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_hbvFive_hbeag", label: "HBeAg", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_hbvFive_hbeab", label: "HBeAb", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_hbvFive_hbcab", label: "HBcAb", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_infectious_hiv", label: "HIV", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_infectious_tppa", label: "TPPA", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_infectious_hcv", label: "HCV", value: "", kind: "select", editors: labReportEditors },
  { key: "crpStatus", label: "CRP/SAA状态", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_crpSaa_crp", label: "CRP", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_crpSaa_saa", label: "SAA", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "urineRoutine", label: "尿常规摘要", value: "", kind: "textarea", editors: labReportEditors },
  { key: "urineRoutineStatus", label: "尿常规状态", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_urineRoutine_wbc", label: "LEU", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_urineRoutine_nit", label: "NIT", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_urineRoutine_uro", label: "URO", value: "", kind: "input", editors: labReportEditors },
  { key: "lab_urineRoutine_pro", label: "PRO", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_urineRoutine_ph", label: "PH", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_urineRoutine_bld", label: "BLD", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_urineRoutine_sg", label: "SG", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "lab_urineRoutine_ket", label: "KET", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_urineRoutine_bil", label: "BIL", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_urineRoutine_glu", label: "GLU", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_urineRoutine_vc", label: "VC", value: "", kind: "select", editors: labReportEditors },
  { key: "postprandialGlucose", label: "餐后血糖", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "postprandialGlucoseStatus", label: "餐后血糖状态", value: "", kind: "select", editors: labReportEditors },
  { key: "lab_hba1c_hba1c", label: "HbA1c", value: "", kind: "input", inputType: "number", editors: labReportEditors },
  { key: "auxiliaryExamSummary", label: "辅助检查自动摘要", value: "", kind: "textarea", editors: labReportSummaryEditors }
];

export const isCollaborativeField = (field: RecordField) =>
  Array.isArray(field.editors) &&
  healthArchiveCollaborators.length === field.editors.length &&
  healthArchiveCollaborators.every(role => field.editors.includes(role));

export const patientLifecycleStages: PatientLifecycleStage[] = [
  {
    key: "registration",
    title: "前台建档与分诊",
    shortTitle: "建档",
    owner: "前台",
    department: "前台/收费处",
    roles: ["admin", "frontdesk"],
    sectionKeys: ["basic", "arrivalSource"],
    stageKeywords: ["登记", "建档", "前台", "资料上传", "旧资料预检"]
  },
  {
    key: "reception",
    title: "接诊问诊与症状采集",
    shortTitle: "接诊",
    owner: "接诊室",
    department: "接诊室/门诊",
    roles: ["admin", "reception", "doctor"],
    sectionKeys: ["specialNeeds", "chiefComplaint", "presentIllness", "history"],
    stageKeywords: ["接诊", "初诊", "问诊", "症状", "基础诊疗"]
  },
  {
    key: "inspection",
    title: "检查室初检与影像采集",
    shortTitle: "初检",
    owner: "检查室",
    department: "检查室",
    roles: ["admin", "inspection", "doctor", "nurse"],
    sectionKeys: ["specialExam"],
    stageKeywords: ["检查室", "专科查体", "专科检查", "影像", "肛门镜", "指检", "初检"]
  },
  {
    key: "decision",
    title: "医师诊断与去向决策",
    shortTitle: "诊断",
    owner: "医生",
    department: "门诊医生",
    roles: ["admin", "doctor"],
    sectionKeys: ["tcmInspection", "mainDiagnosis", "secondaryDiagnosis", "comorbidityTcm", "treatmentPlanManagement"],
    stageKeywords: ["诊断", "辨证", "合并病", "去向", "分流", "治疗方案"]
  },
  {
    key: "screening",
    title: "辅助/术前检查完善",
    shortTitle: "筛查",
    owner: "检查科室",
    department: "化验室/心电室/B超放射",
    roles: ["admin", "lab", "ecg", "ultrasound", "inspection", "doctor", "nurse"],
    sectionKeys: ["auxiliary", "preOpScreening"],
    stageKeywords: ["辅助检查", "术前", "检查", "准入", "筛查"]
  },
  {
    key: "outpatientTreatment",
    title: "门诊治疗或中医方案",
    shortTitle: "门诊",
    owner: "医生/治疗室",
    department: "门诊医生/治疗室",
    roles: ["admin", "doctor", "nurse"],
    sectionKeys: ["tcmHealthManagement", "supplementNotes"],
    stageKeywords: ["门诊", "中医健康管理", "补充记录", "治疗", "中医方案"]
  },
  {
    key: "nursingPrep",
    title: "住院护理准备与宣教",
    shortTitle: "护理",
    owner: "护理部",
    department: "护理部",
    roles: ["admin", "nurse", "nursing"],
    sectionKeys: ["basic", "treatmentPlanManagement"],
    stageKeywords: ["护理", "宣教", "院前", "入院", "术前准备"],
    skipForVisitTypes: ["门诊", "门诊医保"]
  },
  {
    key: "operationRecord",
    title: "手术/治疗执行记录",
    shortTitle: "执行",
    owner: "医生/手术室",
    department: "门诊医生/手术室",
    roles: ["admin", "doctor", "nurse"],
    sectionKeys: ["operation", "roundSchedule"],
    stageKeywords: ["手术", "病程", "治疗记录", "执行"]
  },
  {
    key: "followupClosure",
    title: "出院复查与健康管理",
    shortTitle: "随访",
    owner: "治疗室/医生",
    department: "治疗室/门诊医生",
    roles: ["admin", "doctor", "nurse", "nursing", "frontdesk"],
    sectionKeys: ["followup", "patientFeedback", "familyRelationship", "trustCooperation"],
    stageKeywords: ["出院", "复查", "随访", "患者反馈", "健康管理", "关系维护"]
  },
  {
    key: "qualityArchive",
    title: "质控审核与归档",
    shortTitle: "归档",
    owner: "质控",
    department: "质控/病案",
    roles: ["admin", "quality"],
    sectionKeys: ["dip", "documentScope", "qualityCheck"],
    stageKeywords: ["质控", "档案审核", "归档", "档案生成", "档案补全", "退回整改"]
  }
];

export const isLifecycleStageSkipped = (stage: PatientLifecycleStage, visitType?: string) =>
  Boolean(visitType && stage.skipForVisitTypes?.some(item => visitType.includes(item)));

export const recordSections: RecordSection[] = [
  {
    key: "basic",
    title: "一、基础诊疗信息",
    stage: "登记",
    owner: "前台",
    department: "前台/收费处",
    status: "done",
    description: "保留原病历填写所需的基础诊疗信息，作为健康管理档案的医学基础资料。",
    fields: [
      { key: "hospitalName", label: "医院名称", value: "固始中医肛肠医院", kind: "input", editors: frontdesk, required: true },
      { key: "patientName", label: "患者姓名", value: "", kind: "input", editors: frontdesk, required: true },
      {
        key: "gender",
        label: "性别",
        value: "",
        kind: "select",
        options: ["男", "女", "未说明"],
        editors: frontdesk
      },
      {
        key: "age",
        label: "年龄",
        value: "____岁",
        kind: "input",
        inputType: "number",
        editors: frontdesk,
        min: 0,
        max: 120,
        unit: "岁",
        placeholder: "请输入年龄"
      },
      {
        key: "address",
        label: "户籍地/现住址",
        value: "",
        kind: "textarea",
        editors: frontdesk,
        placeholder: "填写户籍地、现住址或常住区域"
      },
      { key: "visitNo", label: "门诊/住院号", value: "", kind: "input", editors: frontdesk, required: true },
      { key: "archiveCreatedAt", label: "建档（首诊）日期", value: "", kind: "input", inputType: "date", editors: frontdesk },
      {
        key: "departmentName",
        label: "就诊科室",
        value: "肛肠科",
        kind: "input",
        editors: frontdesk,
        placeholder: "例如：肛肠科、胃肠镜室"
      },
      {
        key: "admissionCount",
        label: "第几次入院",
        value: "第____次",
        kind: "input",
        inputType: "number",
        editors: frontdesk,
        min: 1,
        max: 99,
        unit: "次",
        placeholder: "请输入次数"
      },
      { key: "admissionDate", label: "入院日期", value: "", kind: "input", inputType: "date", editors: frontdesk },
      { key: "dischargeDate", label: "出院日期", value: "____年__月__日", kind: "input", inputType: "date", editors: frontdesk },
      {
        key: "hospitalDays",
        label: "住院天数",
        value: "____天",
        kind: "input",
        inputType: "number",
        editors: frontdesk,
        min: 0,
        max: 3650,
        unit: "天",
        placeholder: "请输入住院天数"
      },
      {
        key: "phone",
        label: "联系电话",
        value: "",
        kind: "input",
        inputType: "tel",
        editors: frontdesk,
        maxLength: 11,
        pattern: "^1[3-9]\\d{9}$",
        placeholder: "请输入11位手机号",
        validationMessage: "请输入正确的11位手机号"
      },
      {
        key: "contactPhone",
        label: "联系人电话",
        value: "________",
        kind: "input",
        inputType: "tel",
        editors: frontdesk,
        maxLength: 11,
        pattern: "^1[3-9]\\d{9}$",
        placeholder: "请输入11位联系人手机号",
        validationMessage: "请输入正确的11位联系人手机号"
      },
      {
        key: "historyProvider",
        label: "病史陈述者",
        value: "患者本人",
        kind: "input",
        editors: firstVisitCollaborators,
        placeholder: "例如：患者本人、配偶、子女、陪同家属"
      },
      {
        key: "historyReliable",
        label: "陈述内容是否可靠",
        value: "是",
        kind: "select",
        options: ["是", "基本可靠", "需家属补充", "不确定"],
        editors: firstVisitCollaborators
      },
      {
        key: "admissionAssessment",
        label: "护理部入院评估",
        value: "",
        kind: "textarea",
        editors: nursing,
        placeholder: "护理部记录入院状态、生命体征、配合度、护理风险和重点观察事项"
      },
      {
        key: "nursingObservation",
        label: "护理观察记录",
        value: "",
        kind: "textarea",
        editors: nursing,
        placeholder: "记录术前/术后护理观察、疼痛、排便、换药和异常反馈"
      },
      {
        key: "admissionWay",
        label: "入院方式",
        value: "门诊",
        kind: "select",
        options: ["门诊", "急诊", "转入", "其他"],
        editors: frontdesk
      },
      {
        key: "treatmentType",
        label: "治疗类别",
        value: "中西医结合",
        kind: "select",
        options: ["中西医结合", "中医", "西医"],
        editors: frontdesk
      },
      {
        key: "insuranceType",
        label: "参保险种",
        value: "居民医保",
        kind: "select",
        options: ["职工医保", "居民医保", "自费", "其他"],
        editors: frontdesk
      },
      {
        key: "initialRechargeAmount",
        label: "本次建档充值",
        value: "",
        kind: "input",
        inputType: "number",
        editors: frontdesk,
        min: 0,
        unit: "元",
        placeholder: "如无可留空"
      },
      {
        key: "healthManager",
        label: "健康管理专员",
        value: "",
        kind: "input",
        editors: ["admin", "frontdesk", "nurse"],
        placeholder: "负责跟进建档、复诊提醒和闭环"
      },
      {
        key: "attendingDoctor",
        label: "主诊医师",
        value: "",
        kind: "input",
        editors: ["admin", "frontdesk", "doctor"]
      },
      {
        key: "responsibleNurse",
        label: "责任护士",
        value: "",
        kind: "input",
        editors: ["admin", "frontdesk", "nurse"]
      },
      {
        key: "overallRiskLevel",
        label: "整体病情风险评级",
        value: "",
        kind: "select",
        options: ["低风险", "中风险", "高风险", "需重点跟进", "待评估"],
        editors: ["admin", "frontdesk", "doctor", "nurse", "quality"]
      },
      {
        key: "dischargeWay",
        label: "离院方式",
        value: "医嘱离院",
        kind: "select",
        options: ["医嘱离院", "自动离院", "转院", "死亡", "其他"],
        editors: frontdesk
      }
    ]
  },
  {
    key: "arrivalSource",
    title: "二、来院与来源",
    stage: "前台登记",
    owner: "前台",
    department: "前台/客服",
    status: "active",
    description: "记录患者从首次接触到到院的来源、路径、动机和治疗意向。",
    fields: [
      {
        key: "arrivalPath",
        label: "来院途径",
        value: "",
        kind: "select",
        options: ["朋友介绍", "老患者推荐", "线上咨询", "医生转诊", "自然到院", "合作渠道", "其他"],
        editors: frontdesk
      },
      {
        key: "sourceChannel",
        label: "来源渠道",
        value: "",
        kind: "input",
        editors: frontdesk,
        placeholder: "例如：公众号、电话咨询、转诊医生、活动渠道"
      },
      {
        key: "referralRecord",
        label: "转介绍记录",
        value: "",
        kind: "textarea",
        editors: reception,
        placeholder: "记录转介绍人、关系、来源说明、回访或维护备注"
      },
      { key: "firstContactAt", label: "首次接触时间", value: "", kind: "input", inputType: "date", editors: frontdesk },
      { key: "firstContactPerson", label: "首次接触人", value: "", kind: "input", editors: frontdesk },
      {
        key: "visitMotivation",
        label: "到院动机",
        value: "",
        kind: "textarea",
        editors: frontdesk,
        placeholder: "患者为什么来院，最想解决什么问题"
      },
      {
        key: "treatmentIntent",
        label: "治疗意向",
        value: "",
        kind: "select",
        options: ["明确治疗", "倾向治疗", "先咨询", "价格观望", "需家属决策", "暂不确定"],
        editors: frontdesk
      }
    ]
  },
  {
    key: "specialNeeds",
    title: "三、特殊诉求",
    stage: "需求评估",
    owner: "前台/医生",
    department: "前台/门诊医生",
    status: "active",
    description: "记录患者治疗目标、顾虑、隐私、沟通偏好和期望管理事项。",
    fields: [
      {
        key: "specialRequirements",
        label: "特殊要求",
        value: "",
        kind: "textarea",
        editors: ["admin", "frontdesk", "doctor"],
        placeholder: "例如：尽量少请假、关注疼痛、希望女医生沟通、隐私保护等"
      },
      {
        key: "treatmentExpectation",
        label: "治疗目标",
        value: "",
        kind: "textarea",
        editors: ["admin", "frontdesk", "doctor"],
        placeholder: "患者期望达到的效果，以及需要提前管理的预期"
      },
      {
        key: "primaryConcern",
        label: "重点顾虑",
        value: "",
        kind: "textarea",
        editors: ["admin", "frontdesk", "doctor"],
        placeholder: "费用、时间、疼痛、复发、恢复速度、术后影响等"
      },
      {
        key: "communicationPreference",
        label: "沟通偏好",
        value: "",
        kind: "select",
        options: ["电话", "微信", "现场沟通", "家属代沟通", "仅本人沟通", "其他"],
        editors: frontdesk
      },
      {
        key: "privacyRequirement",
        label: "隐私要求",
        value: "",
        kind: "textarea",
        editors: ["admin", "frontdesk", "doctor"],
        placeholder: "是否避免家属知情、是否限制联系时段等"
      }
    ]
  },
  {
    key: "familyRelationship",
    title: "四、家属与关系",
    stage: "关系维护",
    owner: "前台/护士",
    department: "前台/治疗室",
    status: "waiting",
    description: "记录陪同、探望、决策人、家属态度和潜在分歧。",
    fields: [
      {
        key: "familyVisited",
        label: "是否有家人陪同/探望",
        value: "",
        kind: "select",
        options: ["无", "有陪同", "有探望", "电话参与", "未知"],
        editors: ["admin", "frontdesk", "nurse"]
      },
      {
        key: "familyDecisionMaker",
        label: "主要决策人",
        value: "",
        kind: "input",
        editors: ["admin", "frontdesk", "nurse"],
        placeholder: "本人/配偶/子女/父母/其他"
      },
      {
        key: "familyContact",
        label: "家属联系方式",
        value: "",
        kind: "input",
        inputType: "tel",
        editors: ["admin", "frontdesk", "nurse"],
        maxLength: 11,
        pattern: "^1[3-9]\\d{9}$",
        validationMessage: "请输入正确的11位家属手机号"
      },
      {
        key: "familyAttitude",
        label: "家属态度",
        value: "",
        kind: "select",
        options: ["支持", "观望", "担心费用", "担心风险", "意见不一致", "未参与"],
        editors: ["admin", "frontdesk", "nurse"]
      },
      {
        key: "familyConflict",
        label: "家庭意见分歧",
        value: "",
        kind: "textarea",
        editors: ["admin", "frontdesk", "nurse"],
        placeholder: "如无分歧可填写无"
      }
    ]
  },
  {
    key: "trustCooperation",
    title: "五、信任与配合",
    stage: "关系维护",
    owner: "前台/医生/护士",
    department: "前台/门诊/治疗室",
    status: "waiting",
    description: "记录患者对医院和医生的信任度、配合度、情绪状态和维护重点。",
    fields: [
      {
        key: "hospitalTrustLevel",
        label: "对医院信任度",
        value: "",
        kind: "select",
        options: ["高", "中", "低", "需重点维护"],
        editors: ["admin", "frontdesk", "doctor", "nurse"]
      },
      {
        key: "doctorTrustLevel",
        label: "对医生信任度",
        value: "",
        kind: "select",
        options: ["高", "中", "低", "需再次沟通"],
        editors: ["admin", "frontdesk", "doctor", "nurse"]
      },
      {
        key: "treatmentCooperation",
        label: "治疗配合度",
        value: "",
        kind: "select",
        options: ["积极配合", "基本配合", "偶有抵触", "明显抵触", "待观察"],
        editors: ["admin", "doctor", "nurse"]
      },
      {
        key: "emotionStatus",
        label: "情绪状态",
        value: "",
        kind: "select",
        options: ["稳定", "紧张", "焦虑", "抗拒", "不满", "低落"],
        editors: ["admin", "frontdesk", "doctor", "nurse"]
      },
      {
        key: "relationshipRisk",
        label: "不满点/风险点",
        value: "",
        kind: "textarea",
        editors: ["admin", "frontdesk", "doctor", "nurse"],
        placeholder: "记录投诉、不满、犹豫点或需要重点维护的事项"
      },
      {
        key: "relationshipMaintenance",
        label: "维护措施",
        value: "",
        kind: "textarea",
        editors: ["admin", "frontdesk", "doctor", "nurse"],
        placeholder: "下一步由谁跟进、怎么沟通、何时回访"
      }
    ]
  },
  {
    key: "chiefComplaint",
    title: "六、主诉",
    stage: "初诊",
    owner: "医生",
    department: "门诊医生",
    status: "active",
    description: "还原模板中的“肛门____伴____，____年/月/天，加重____天”。",
    fields: [
      {
        key: "chiefComplaintText",
        label: "主诉",
        value: "肛门肿块伴便血 3 天，加重 1 天。",
        kind: "textarea",
        editors: firstVisitCollaborators,
        required: true,
        placeholder: "例：肛门肿块伴便血 3 天，加重 1 天。"
      }
    ]
  },
  {
    key: "presentIllness",
    title: "七、现病史",
    stage: "初诊",
    owner: "医生",
    department: "门诊医生",
    status: "active",
    description: "将诱因、肛门症状、便血、疼痛、坠胀、大便和全身情况合成病历段落。",
    fields: [
      {
        key: "onset",
        label: "起病经过",
        value: "患者 3 天前无明显诱因出现肛门肿块，伴便血，色鲜红，呈滴下状。",
        kind: "textarea",
        editors: firstVisitCollaborators,
        required: true
      },
      {
        key: "symptomPattern",
        label: "症状性质",
        value: "间断发作，伴肛门憋闷坠胀，保守治疗可缓解。",
        kind: "textarea",
        editors: firstVisitCollaborators
      },
      {
        key: "aggravation",
        label: "加重及入院原因",
        value: "1 天前症状加重，门诊以混合痔收入院。",
        kind: "textarea",
        editors: firstVisitCollaborators
      },
      {
        key: "generalCondition",
        label: "一般情况",
        value: "精神可，饮食可，大便每日 1 次，小便无明显变化，体重无明显下降，无恶寒发热。",
        kind: "textarea",
        editors: firstVisitCollaborators
      }
    ]
  },
  {
    key: "history",
    title: "八、既往史",
    stage: "初诊",
    owner: "医生",
    department: "门诊医生",
    status: "waiting",
    description: "覆盖手术史、慢性病、外伤输血、过敏、个人史、婚育家族史。",
    fields: [
      { key: "operationHistory", label: "手术史", value: "否认手术史。", kind: "textarea", editors: firstVisitCollaborators },
      {
        key: "chronicDisease",
        label: "慢性病史",
        value: "否认高血压、糖尿病、冠心病等慢性病史。",
        kind: "textarea",
        editors: firstVisitCollaborators
      },
      {
        key: "traumaTransfusion",
        label: "外伤/输血史",
        value: "否认重大外伤史，否认输血史；预防接种随社会。",
        kind: "textarea",
        editors: firstVisitCollaborators
      },
      { key: "allergyHistory", label: "过敏史", value: "否认药物及食物过敏史。", kind: "textarea", editors: firstVisitCollaborators },
      {
        key: "personalHistory",
        label: "个人史",
        value: "生于原籍，无外地长期居住史，无烟酒嗜好，否认特殊接触史，否认冶游史。",
        kind: "textarea",
        editors: firstVisitCollaborators
      },
      {
        key: "familyHistory",
        label: "婚育/家族史",
        value: "适龄结婚，配偶及子女体健。否认传染病、遗传病、肿瘤及类似病史。",
        kind: "textarea",
        editors: firstVisitCollaborators
      }
    ]
  },
  {
    key: "tcmInspection",
    title: "九、中医四诊",
    stage: "辨证",
    owner: "医生",
    department: "门诊医生",
    status: "waiting",
    description: "还原神志、面色、形体、舌象、中医证型与治法选择。",
    fields: [
      { key: "tcmLook", label: "望闻问切", value: "神志清，精神可，面色正常，形体适中。", kind: "textarea", editors: doctor },
      { key: "tongue", label: "舌象", value: "舌质红，苔薄黄，舌中可见裂纹，边有齿痕。", kind: "textarea", editors: doctor },
      {
        key: "tcmSyndrome",
        label: "中医证型",
        value: "湿热下注",
        kind: "select",
        options: [
          "湿热下注",
          "血热肠燥",
          "气虚下陷",
          "气滞血瘀",
          "阴虚津亏",
          "正虚邪恋",
          "脾虚气陷",
          "肾气不固",
          "火毒蕴结",
          "热毒炽盛"
        ],
        editors: doctor
      },
      {
        key: "tcmTreatment",
        label: "治法",
        value: "清热利湿，消肿止痛。",
        kind: "textarea",
        editors: doctor,
        placeholder: "例：健脾祛湿、益气托毒、活血化瘀、清热利湿、润肠通便、消肿止痛。"
      }
    ]
  },
  {
    key: "specialExam",
    title: "十、专科检查",
    stage: "检查室初检",
    owner: "检查室/医生",
    department: "检查室/门诊医生/治疗室",
    status: "waiting",
    description: "检查室先完成影像、指检、肛门镜等证据采集和简短备注，医生再补充诊断性描述。",
    fields: [
      {
        key: "inspectionImages",
        label: "检查室图片/视频证据",
        value: "",
        kind: "attachment",
        editors: inspection,
        evidence: "检查室图片/视频",
        placeholder: "检查室可只上传摄像头、肛门镜、指检相关图片或视频；无需填写大段文字。"
      },
      {
        key: "inspectionBriefNote",
        label: "检查室简短备注",
        value: "",
        kind: "textarea",
        editors: ["admin", "inspection", "doctor"],
        placeholder: "可选：一句话记录体位、疼痛、出血、图片编号或需医生重点查看的位置。"
      },
      {
        key: "lithotomyExam",
        label: "截石位所见",
        value: "截石位 3、7、11 点位肛缘可见皮赘样隆起，屏气用腹压可见其缓慢增大，可自行还纳。",
        kind: "textarea",
        editors: ["admin", "doctor", "nurse", "inspection"]
      },
      {
        key: "analTension",
        label: "肛门括约肌张力",
        value: "肛门括约肌张力可，肛门赘皮轻度疼痛，入指约 7cm。",
        kind: "textarea",
        editors: ["admin", "doctor", "inspection"]
      },
      {
        key: "digitalExam",
        label: "肛指检查",
        value: "直肠腔内未触及硬性结节，齿线上方可触及柔软隆起，无明显压痛，指套少量染血。",
        kind: "textarea",
        editors: ["admin", "doctor", "inspection"]
      },
      {
        key: "anoscope",
        label: "肛门镜",
        value: "齿线上黏膜隆起、充血，部分糜烂，可见出血点，未见溃疡及占位。",
        kind: "textarea",
        editors: ["admin", "doctor", "inspection"],
        evidence: "肛门镜图片"
      }
    ]
  },
  {
    key: "auxiliary",
    title: "十一、辅助检查",
    stage: "检查",
    owner: "检查科室",
    department: "化验室/心电室/B超放射",
    status: "active",
    description: "化验室检验结果由检验报告模板维护；本节仅保留心电图、肠镜、生命体征等非化验室冗余字段。",
    fields: [
      {
        key: "ecgResult",
        label: "心电图",
        value: "窦性心律，ST-T 改变按实际填写。",
        kind: "textarea",
        editors: ecg,
        evidence: "心电图报告"
      },
      {
        key: "colonoscopy",
        label: "无痛电子肠镜",
        value: "未见异常 / 结直肠炎 / 直肠息肉 / 结肠息肉 / 息肉切除术，按实际选择。",
        kind: "textarea",
        editors: ultrasound
      },
      {
        key: "vitalSigns",
        label: "生命体征",
        value: "T：____℃，P：____次/分，R：____次/分，BP：____/____mmHg。",
        kind: "input",
        pattern: "^T：\\d{2}(\\.\\d)?℃，P：\\d{2,3}次/分，R：\\d{1,2}次/分，BP：\\d{2,3}/\\d{2,3}mmHg。$",
        placeholder: "例：T：36.5℃，P：78次/分，R：18次/分，BP：120/80mmHg。",
        validationMessage: "生命体征请按 T：36.5℃，P：78次/分，R：18次/分，BP：120/80mmHg。格式填写",
        editors: nurse
      }
    ]
  },
  {
    key: "preOpScreening",
    title: "十二、术前检验筛查汇总",
    stage: "手术准入评估",
    owner: "检查科室/医生",
    department: "化验室/心电室/B超放射/门诊医生",
    status: "active",
    description: "化验室状态由检验报告模板自动同步；本节仅保留非化验设备状态和医生补充说明。",
    fields: [
      {
        key: "ecgStatus",
        label: "心电图状态",
        value: "",
        kind: "select",
        options: ["未查", "已查", "异常"],
        editors: ["admin", "ecg", "doctor", "nurse"],
        evidence: "心电图报告"
      },
      {
        key: "hpTestStatus",
        label: "幽门螺杆菌检测状态",
        value: "",
        kind: "select",
        options: ["未查", "已查", "异常"],
        editors: ["admin", "lab", "doctor", "nurse"],
        evidence: "幽门螺杆菌检测报告"
      },
      {
        key: "gastroscopyStatus",
        label: "电子胃镜状态",
        value: "",
        kind: "select",
        options: ["未查", "已查", "异常"],
        editors: ["admin", "ultrasound", "doctor", "nurse"],
        evidence: "电子胃镜报告"
      },
      {
        key: "colonoscopyStatus",
        label: "电子肠镜状态",
        value: "",
        kind: "select",
        options: ["未查", "已查", "异常"],
        editors: ["admin", "ultrasound", "doctor", "nurse"],
        evidence: "电子肠镜报告"
      },
      {
        key: "drChestStatus",
        label: "DR胸片状态",
        value: "",
        kind: "select",
        options: ["未查", "已查", "异常"],
        editors: ["admin", "ultrasound", "doctor", "nurse"],
        evidence: "DR胸片报告"
      },
      {
        key: "uncheckedItemsNote",
        label: "未查项目说明",
        value: "",
        kind: "textarea",
        editors: ["admin", "doctor", "nurse", "quality"],
        placeholder: "例如：术后按需择期补查，或因患者原因暂缓检查"
      }
    ]
  },
  {
    key: "mainDiagnosis",
    title: "十三、中西医主诊断",
    stage: "诊断",
    owner: "医生",
    department: "门诊医生",
    status: "waiting",
    description: "中医诊断、西医诊断、证型要一一对应。",
    fields: [
      { key: "tcmDiagnosis", label: "中医诊断", value: "痔病（湿热下注证）", kind: "input", editors: doctor, required: true },
      { key: "westernDiagnosis", label: "西医诊断", value: "混合痔", kind: "input", editors: doctor, required: true },
      {
        key: "otherMainDiagnosis",
        label: "模板可选主病",
        value: "直肠黏膜脱垂、慢性胃炎等按实际补充。",
        kind: "textarea",
        editors: doctor
      }
    ]
  },
  {
    key: "secondaryDiagnosis",
    title: "十三、次诊断",
    stage: "诊断",
    owner: "医生",
    department: "门诊医生",
    status: "waiting",
    description: "对应模板中的可多选次诊断。",
    fields: [
      {
        key: "secondaryDiagnosisList",
        label: "次诊断",
        value: "肛门湿疹、血栓外痔、肠易激综合征、直肠黏膜松驰、耻骨直肠肌痉挛、肛门松弛、结直肠息肉，其他：________。",
        kind: "textarea",
        editors: doctor
      }
    ]
  },
  {
    key: "comorbidityTcm",
    title: "十四、合并病中医病名及证型",
    stage: "合并病",
    owner: "医生",
    department: "门诊医生",
    status: "waiting",
    description: "覆盖高血压、眩晕、胃炎、肠炎、便秘、胸痹等证型库。",
    fields: [
      {
        key: "comorbidityDisease",
        label: "合并病病名",
        value: "肠炎（直肠炎/慢性肠炎）或其他合并病按实际填写。",
        kind: "textarea",
        editors: doctor
      },
      {
        key: "comorbiditySyndrome",
        label: "合并病证型",
        value: "肝阳上亢、痰湿中阻、瘀血阻窍、气血亏虚、肝肾阴虚、阴虚燥热、湿热困脾、肝胃不和、脾胃虚弱、大肠湿热等按实际选择。",
        kind: "textarea",
        editors: doctor
      }
    ]
  },
  {
    key: "operation",
    title: "十五、治疗与手术",
    stage: "手术方案",
    owner: "医生/护士",
    department: "门诊医生/手术室",
    status: "waiting",
    description: "还原模板中的分组选择、术式、附加操作、手术指征、麻醉和等级。",
    fields: [
      {
        key: "operationGroup",
        label: "分组核心选择",
        value: "肛肠手术 + 无痛结肠镜检查",
        kind: "select",
        options: ["纯肛肠手术（不做肠镜）", "肛肠手术 + 无痛肠镜", "无痛肠镜下息肉切除"],
        editors: doctor
      },
      {
        key: "operationName",
        label: "主要手术",
        value: "痔切除术",
        kind: "select",
        options: [
          "痔切除术",
          "内镜下内痔套扎术",
          "肛瘘切除术",
          "肛裂切除术",
          "肛周脓肿根治术",
          "直肠前突修补术",
          "经肛直肠息肉切除术",
          "结肠镜下息肉切除术（无痛）"
        ],
        editors: doctor
      },
      {
        key: "additionalOperation",
        label: "附加操作",
        value: "内痔套扎术、外痔切除术按实际选择。",
        kind: "textarea",
        editors: doctor
      },
      {
        key: "operationIndication",
        label: "手术指征",
        value: "反复脱出、保守治疗无效、便血反复发作，需手术治疗。",
        kind: "textarea",
        editors: doctor
      },
      {
        key: "anesthesia",
        label: "麻醉方式",
        value: "硬膜外麻醉",
        kind: "select",
        options: ["硬膜外麻醉", "静脉麻醉（无痛肠镜）", "局部麻醉", "其他"],
        editors: ["admin", "doctor", "nurse"]
      },
      {
        key: "operationLevel",
        label: "手术等级",
        value: "____级",
        kind: "select",
        options: ["一类", "二类", "三类", "四类"],
        editors: quality
      }
    ]
  },
  {
    key: "treatmentPlanManagement",
    title: "十六、术前医患沟通与治疗方案管理",
    stage: "治疗方案",
    owner: "医生/护士/前台",
    department: "门诊医生/治疗室/前台",
    status: "active",
    description: "对齐健康管理登记表中的手术可行性评估、核心诉求、术中特殊交代和当日处置。",
    fields: [
      {
        key: "surgeryFeasibility",
        label: "手术可行性评估",
        value: "",
        kind: "textarea",
        editors: ["admin", "doctor", "nurse"],
        placeholder: "结合检查结果、基础病、麻醉风险和患者意愿记录"
      },
      {
        key: "intraoperativeNotice",
        label: "术中特殊交代",
        value: "",
        kind: "textarea",
        editors: ["admin", "doctor", "nurse"],
        placeholder: "记录术中可能追加处理、特殊体位、沟通禁忌或患者特别要求"
      },
      {
        key: "sameDayTreatment",
        label: "当日处置",
        value: "",
        kind: "textarea",
        editors: ["admin", "doctor", "nurse", "frontdesk"],
        placeholder: "记录当日检查、治疗、缴费、宣教、预约等处置"
      }
    ]
  },
  {
    key: "followup",
    title: "十七、术后分级复诊健康管理台账",
    stage: "复查随访",
    owner: "护士/医生",
    department: "治疗室/门诊医生",
    status: "active",
    description: "按时间记录每次复查、恢复情况、异常处理和下次随访安排。",
    fields: [
      {
        key: "followupRecordsJson",
        label: "复查随访记录",
        value: "[]",
        kind: "textarea",
        editors: ["admin", "doctor", "nurse"],
        printable: true
      },
      {
        key: "recoverySummary",
        label: "恢复情况总览",
        value: "",
        kind: "textarea",
        editors: ["admin", "doctor", "nurse"],
        placeholder: "概括术后恢复趋势、异常情况和处理结果"
      },
      {
        key: "nextFollowupAt",
        label: "下次复查时间",
        value: "",
        kind: "input",
        inputType: "date",
        editors: ["admin", "doctor", "nurse"]
      },
      {
        key: "followupCompliance",
        label: "复查依从性",
        value: "",
        kind: "select",
        options: ["按时复查", "延迟复查", "未复查", "电话随访", "需再次提醒"],
        editors: ["admin", "doctor", "nurse"]
      }
    ]
  },
  {
    key: "patientFeedback",
    title: "十七、患者主观反馈",
    stage: "患者反馈",
    owner: "前台/护士/医生",
    department: "前台/治疗室/门诊医生",
    status: "waiting",
    description: "记录患者对疼痛、恢复、服务和治疗结果的主观感受。",
    fields: [
      {
        key: "patientPainLevel",
        label: "疼痛程度",
        value: "",
        kind: "select",
        options: ["无痛", "轻度", "中度", "重度", "无法判断"],
        editors: ["admin", "frontdesk", "doctor", "nurse"]
      },
      {
        key: "patientSatisfaction",
        label: "恢复满意度",
        value: "",
        kind: "select",
        options: ["满意", "基本满意", "一般", "不满意", "待观察"],
        editors: ["admin", "frontdesk", "doctor", "nurse"]
      },
      {
        key: "psychologicalFeeling",
        label: "心理感受",
        value: "",
        kind: "textarea",
        editors: ["admin", "frontdesk", "doctor", "nurse"],
        placeholder: "患者焦虑、担心、安心程度等主观表达"
      },
      {
        key: "patientConcerns",
        label: "当前担忧",
        value: "",
        kind: "textarea",
        editors: ["admin", "frontdesk", "doctor", "nurse"],
        placeholder: "患者现在最担心的问题"
      },
      {
        key: "serviceFeedback",
        label: "服务评价",
        value: "",
        kind: "textarea",
        editors: ["admin", "frontdesk", "nurse"],
        placeholder: "对接待、治疗、沟通、复查提醒等评价"
      },
      {
        key: "nextPatientRequest",
        label: "下一步诉求",
        value: "",
        kind: "textarea",
        editors: ["admin", "frontdesk", "doctor", "nurse"]
      }
    ]
  },
  {
    key: "tcmHealthManagement",
    title: "十八、中医特色健康管理专栏",
    stage: "中医健康管理",
    owner: "医生/护士",
    department: "门诊医生/治疗室",
    status: "active",
    description: "对齐二级中医肛肠标配模块，记录中药坐浴、内服调理、饮食宣教、提肛训练和慢病干预。",
    fields: [
      {
        key: "tcmSitzBathPlan",
        label: "术后中药坐浴方案",
        value: "",
        kind: "textarea",
        editors: ["admin", "doctor", "nurse"],
        placeholder: "填写已开具/待开具、方药和使用频次"
      },
      {
        key: "tcmOralRegulation",
        label: "中医内服调理",
        value: "",
        kind: "select",
        options: ["益气通便", "清热燥湿", "活血化瘀", "润肠生肌", "暂不需要", "其他"],
        editors: ["admin", "doctor", "nurse"]
      },
      {
        key: "dietEducation",
        label: "饮食禁忌宣教",
        value: "",
        kind: "textarea",
        editors: ["admin", "doctor", "nurse"],
        placeholder: "辛辣、饮酒、久坐、饮水、膳食纤维等宣教记录"
      },
      {
        key: "analFunctionExercise",
        label: "肛门功能锻炼指导",
        value: "",
        kind: "select",
        options: ["已宣教", "待宣教", "不适用", "需复查时强化"],
        editors: ["admin", "doctor", "nurse"]
      },
      {
        key: "chronicDiseaseIntervention",
        label: "慢病预防干预",
        value: "",
        kind: "textarea",
        editors: ["admin", "doctor", "nurse"],
        placeholder: "便秘、糖尿病、高血压、胃肠慢病等干预建议"
      },
      {
        key: "archiveClosedSignature",
        label: "档案闭环签字",
        value: "",
        kind: "textarea",
        editors: ["admin", "doctor", "nurse", "quality"],
        placeholder: "健康管理师、医师签字和归档日期"
      }
    ]
  },
  {
    key: "supplementNotes",
    title: "十九、备注与补充记录",
    stage: "补充记录",
    owner: "医生/护士/前台",
    department: "门诊医生/治疗室/前台",
    status: "waiting",
    description: "用于后续补充并发症、用药记录、患者依从性记录和其他特殊情况。",
    fields: [
      {
        key: "complicationRecord",
        label: "并发症记录",
        value: "",
        kind: "textarea",
        editors: ["admin", "doctor", "nurse"],
        placeholder: "如无可填写无"
      },
      {
        key: "medicationRecord",
        label: "用药记录",
        value: "",
        kind: "textarea",
        editors: ["admin", "doctor", "nurse"],
        placeholder: "记录院内用药、出院带药或患者自述用药"
      },
      {
        key: "patientComplianceRecord",
        label: "患者依从性记录",
        value: "",
        kind: "textarea",
        editors: ["admin", "frontdesk", "doctor", "nurse"],
        placeholder: "复诊、换药、饮食、坐浴、运动等执行情况"
      }
    ]
  },
  {
    key: "dip",
    title: "二十、DIP",
    stage: "分组",
    owner: "质控",
    department: "质控/病案",
    status: "waiting",
    description: "对应模板中的病组自动匹配、合理住院天数、合规判断。",
    fields: [
      {
        key: "dipGroup",
        label: "病组匹配",
        value: "痔手术组",
        kind: "select",
        options: [
          "纯肛肠组",
          "痔手术组",
          "肛瘘手术组",
          "肛裂手术组",
          "肛周脓肿手术组",
          "直肠黏膜脱垂组",
          "直肠前突组",
          "经肛息肉手术组",
          "肠镜联合组",
          "结直肠息肉手术组（肠镜下切除）"
        ],
        editors: quality
      },
      {
        key: "reasonableDays",
        label: "合理住院天数",
        value: "5-7 天",
        kind: "select",
        options: ["3-5 天", "5-7 天", "7-10 天", "10-14 天", "14 天以上"],
        editors: quality
      },
      {
        key: "dipCompliance",
        label: "合规判断",
        value: "无需调整分组，按临床实际选择。",
        kind: "textarea",
        editors: quality
      }
    ]
  },
  {
    key: "roundSchedule",
    title: "十九、治疗记录时序",
    stage: "病程",
    owner: "质控/医生",
    department: "质控/门诊医生",
    status: "waiting",
    description: "保留治疗过程和病程记录时序，便于院内档案审核与后续追踪。",
    fields: [
      {
        key: "courseSchedule",
        label: "治疗/病程记录时序",
        value:
          "入院第 1 天：首次病程；第 2 天：主治医师查房；第 2-3 天：术前讨论、手术医师查房、术前小结；手术日：术后首次病程；术后第 1 天手术医师查房；第 3 天副主任医师查房；第 5 天主治查房；拟定次日出院或出院。",
        kind: "textarea",
        editors: ["admin", "doctor", "quality"]
      }
    ]
  },
  {
    key: "documentScope",
    title: "二十、档案输出范围",
    stage: "档案审核",
    owner: "档案审核",
    department: "质控/病案",
    status: "waiting",
    description: "用于明确本系统输出哪些院内健康管理档案内容，不替代 HIS 官方病历。",
    fields: [
      {
        key: "generatedDocuments",
        label: "档案输出范围",
        value: "基础诊疗信息、来院来源、特殊诉求、治疗记录、复查随访、患者反馈、附件索引。",
        kind: "textarea",
        editors: quality
      },
      {
        key: "documentStandard",
        label: "格式标准",
        value: "作为院内患者健康管理档案使用，不替代 HIS 官方病历和病历质控文书。",
        kind: "textarea",
        editors: quality
      }
    ]
  },
  {
    key: "qualityCheck",
    title: "二十一、档案审核",
    stage: "档案审核",
    owner: "档案审核",
    department: "质控/病案",
    status: "locked",
    description: "归档前校验档案完整性、复查闭环、附件和关键风险事项。",
    fields: [
      {
        key: "qualityItems",
        label: "档案审核项目",
        value:
          "基础诊疗信息完整；来源与诉求清楚；治疗记录与附件匹配；复查随访有闭环；患者反馈和风险事项已记录；不作为 HIS 官方病历替代。",
        kind: "textarea",
        editors: quality
      },
      {
        key: "qualityReview",
        label: "档案审核意见",
        value: "待档案审核。",
        kind: "textarea",
        editors: quality
      }
    ]
  }
];
