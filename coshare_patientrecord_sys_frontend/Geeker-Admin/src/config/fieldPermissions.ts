export type UserRole = "admin" | "frontdesk" | "lab" | "ecg" | "ultrasound" | "doctor" | "nurse" | "quality";

export type FieldKind = "input" | "textarea" | "select";

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
  lab: "化验室",
  ecg: "心电室",
  ultrasound: "B超/放射",
  doctor: "医生",
  nurse: "护士/治疗室",
  quality: "质控"
};

export const roleLabel = (role?: string) => ROLE_LABELS[(role as UserRole) || "frontdesk"] ?? "前台";

export const canEditField = (role: string | undefined, field: RecordField) => {
  if (role === "admin") return true;
  return field.editors.includes(role as UserRole);
};

export const canEditSection = (role: string | undefined, section: RecordSection) => {
  return section.fields.some(field => canEditField(role, field));
};

export const editorLabels = (editors: UserRole[]) => editors.map(item => ROLE_LABELS[item]).join("、");

export const allRecordFields = () => recordSections.flatMap(section => section.fields);

export const recordAttachments: RecordAttachment[] = [];

const doctor: UserRole[] = ["admin", "doctor"];
const frontdesk: UserRole[] = ["admin", "frontdesk"];
const nurse: UserRole[] = ["admin", "nurse"];
const lab: UserRole[] = ["admin", "lab"];
const ecg: UserRole[] = ["admin", "ecg"];
const ultrasound: UserRole[] = ["admin", "ultrasound"];
const quality: UserRole[] = ["admin", "quality"];

export const recordSections: RecordSection[] = [
  {
    key: "basic",
    title: "一、基础信息",
    stage: "登记",
    owner: "前台",
    department: "前台/收费处",
    status: "done",
    description: "对应模板中的医院名称、入出院信息、联系方式、医保与离院方式。",
    fields: [
      { key: "hospitalName", label: "医院名称", value: "固始中医肛肠医院", kind: "input", editors: frontdesk, required: true },
      { key: "patientName", label: "患者姓名", value: "", kind: "input", editors: frontdesk, required: true },
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
      { key: "visitNo", label: "门诊/住院号", value: "", kind: "input", editors: frontdesk, required: true },
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
    key: "chiefComplaint",
    title: "二、主诉",
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
        editors: doctor,
        required: true,
        placeholder: "例：肛门肿块伴便血 3 天，加重 1 天。"
      }
    ]
  },
  {
    key: "presentIllness",
    title: "三、现病史",
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
        editors: doctor,
        required: true
      },
      {
        key: "symptomPattern",
        label: "症状性质",
        value: "间断发作，伴肛门憋闷坠胀，保守治疗可缓解。",
        kind: "textarea",
        editors: doctor
      },
      {
        key: "aggravation",
        label: "加重及入院原因",
        value: "1 天前症状加重，门诊以混合痔收入院。",
        kind: "textarea",
        editors: doctor
      },
      {
        key: "generalCondition",
        label: "一般情况",
        value: "精神可，饮食可，大便每日 1 次，小便无明显变化，体重无明显下降，无恶寒发热。",
        kind: "textarea",
        editors: doctor
      }
    ]
  },
  {
    key: "history",
    title: "四、既往史",
    stage: "初诊",
    owner: "医生",
    department: "门诊医生",
    status: "waiting",
    description: "覆盖手术史、慢性病、外伤输血、过敏、个人史、婚育家族史。",
    fields: [
      { key: "operationHistory", label: "手术史", value: "否认手术史。", kind: "textarea", editors: doctor },
      {
        key: "chronicDisease",
        label: "慢性病史",
        value: "否认高血压、糖尿病、冠心病等慢性病史。",
        kind: "textarea",
        editors: doctor
      },
      {
        key: "traumaTransfusion",
        label: "外伤/输血史",
        value: "否认重大外伤史，否认输血史；预防接种随社会。",
        kind: "textarea",
        editors: doctor
      },
      { key: "allergyHistory", label: "过敏史", value: "否认药物及食物过敏史。", kind: "textarea", editors: doctor },
      {
        key: "personalHistory",
        label: "个人史",
        value: "生于原籍，无外地长期居住史，无烟酒嗜好，否认特殊接触史，否认冶游史。",
        kind: "textarea",
        editors: doctor
      },
      {
        key: "familyHistory",
        label: "婚育/家族史",
        value: "适龄结婚，配偶及子女体健。否认传染病、遗传病、肿瘤及类似病史。",
        kind: "textarea",
        editors: doctor
      }
    ]
  },
  {
    key: "tcmInspection",
    title: "五、中医四诊",
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
    title: "六、专科检查",
    stage: "专科查体",
    owner: "医生/护士",
    department: "门诊医生/治疗室",
    status: "waiting",
    description: "模板中的截石位、肛指检查、肛门镜内容拆为结构化段落。",
    fields: [
      {
        key: "lithotomyExam",
        label: "截石位所见",
        value: "截石位 3、7、11 点位肛缘可见皮赘样隆起，屏气用腹压可见其缓慢增大，可自行还纳。",
        kind: "textarea",
        editors: ["admin", "doctor", "nurse"]
      },
      {
        key: "analTension",
        label: "肛门括约肌张力",
        value: "肛门括约肌张力可，肛门赘皮轻度疼痛，入指约 7cm。",
        kind: "textarea",
        editors: doctor
      },
      {
        key: "digitalExam",
        label: "肛指检查",
        value: "直肠腔内未触及硬性结节，齿线上方可触及柔软隆起，无明显压痛，指套少量染血。",
        kind: "textarea",
        editors: doctor
      },
      {
        key: "anoscope",
        label: "肛门镜",
        value: "齿线上黏膜隆起、充血，部分糜烂，可见出血点，未见溃疡及占位。",
        kind: "textarea",
        editors: doctor
      }
    ]
  },
  {
    key: "auxiliary",
    title: "七、辅助检查",
    stage: "检查",
    owner: "检查科室",
    department: "化验室/心电室/B超放射",
    status: "active",
    description: "包含尿常规、生化、凝血、术前八项、肠镜、心电图、生命体征等标准项。",
    fields: [
      {
        key: "urineRoutine",
        label: "尿常规",
        value: "尿比重、pH、尿蛋白、尿糖、隐血、白细胞、红细胞、亚硝酸盐、酮体、胆红素、尿胆原、结晶：余无异常。",
        kind: "textarea",
        editors: lab,
        labPanel: "urineRoutine"
      },
      {
        key: "biochemistry",
        label: "生化/糖化",
        value: "ALT、AST、TBil、DBil、IBIL、TP、ALB、GLB、A/G、GGT、ALP、TG、TC、GLU、糖化血红蛋白、CREA、UA、UREA：余无异常。",
        kind: "textarea",
        editors: lab,
        labPanel: "biochemistry"
      },
      {
        key: "coagulation",
        label: "凝血功能",
        value: "PT、APTT、TT、FIB、INR：余无异常。",
        kind: "textarea",
        editors: lab,
        evidence: "凝血功能报告",
        labPanel: "coagulation"
      },
      {
        key: "preOpEight",
        label: "术前八项",
        value: "乙肝五项、丙肝抗体、梅毒抗体、艾滋病抗体：按实际填写，余无异常。",
        kind: "textarea",
        editors: lab,
        evidence: "术前八项报告",
        labPanel: "preOpEight"
      },
      {
        key: "bloodRoutine",
        label: "血常规",
        value: "WBC、PLT、HGB 按实际填写，余无异常。",
        kind: "textarea",
        editors: lab,
        evidence: "血常规报告",
        labPanel: "bloodRoutine"
      },
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
    key: "mainDiagnosis",
    title: "八、中西医主诊断",
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
    title: "九、次诊断",
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
    title: "十、合并病中医病名及证型",
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
    title: "十一、手术",
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
    key: "dip",
    title: "十二、DIP",
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
    title: "十三、查房时序",
    stage: "病程",
    owner: "质控/医生",
    department: "质控/门诊医生",
    status: "waiting",
    description: "按 5/7/10 天质控标准生成病程记录时序。",
    fields: [
      {
        key: "courseSchedule",
        label: "病程记录时序",
        value:
          "入院第 1 天：首次病程；第 2 天：主治医师查房；第 2-3 天：术前讨论、手术医师查房、术前小结；手术日：术后首次病程；术后第 1 天手术医师查房；第 3 天副主任医师查房；第 5 天主治查房；拟定次日出院或出院。",
        kind: "textarea",
        editors: ["admin", "doctor", "quality"]
      }
    ]
  },
  {
    key: "documentScope",
    title: "十四、自动生成文书范围",
    stage: "文书",
    owner: "质控",
    department: "质控/病案",
    status: "waiting",
    description: "用于明确最终生成哪些病历文书。",
    fields: [
      {
        key: "generatedDocuments",
        label: "生成范围",
        value: "入院记录、首次病程、术前讨论、术前小结、手术记录、术后每日病程、出院小结。",
        kind: "textarea",
        editors: quality
      },
      {
        key: "documentStandard",
        label: "格式标准",
        value: "格式、结构、质控标准与院内标准病历一致。",
        kind: "textarea",
        editors: quality
      }
    ]
  },
  {
    key: "qualityCheck",
    title: "十五、质控校验",
    stage: "归档",
    owner: "质控",
    department: "质控/病案",
    status: "locked",
    description: "归档前校验诊断、治法、三级查房、手术与分组是否一致。",
    fields: [
      {
        key: "qualityItems",
        label: "质控项目",
        value:
          "中西医诊断一一对应；治法匹配；三级查房顺序规范；手术与诊断一致；分组合理、不高套；中医特色治疗齐全；合并病记录完整；肠镜按需选择、符合临床逻辑。",
        kind: "textarea",
        editors: quality
      },
      {
        key: "qualityReview",
        label: "质控意见",
        value: "待质控复核。",
        kind: "textarea",
        editors: quality
      }
    ]
  }
];
