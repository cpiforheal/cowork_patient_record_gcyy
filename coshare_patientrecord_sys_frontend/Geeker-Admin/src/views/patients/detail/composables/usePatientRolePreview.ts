import { computed, type ComputedRef } from "vue";
import type { MedicalRecordTemplateField, MedicalRecordTemplateSection } from "@/api/modules/clinic/types";
import type { RecordAttachment, RecordSection, UserRole } from "@/config/fieldPermissions";
import {
  isWorkflowBlockingTask,
  type PatientWorkflowTaskState,
  type WorkflowDecisionSummary,
  type WorkflowFieldTask,
  type WorkflowStageNode,
  type WorkflowStageRuntimeStatus
} from "./usePatientWorkflowTasks";

type RolePreviewTarget = "medicalRecord" | "archive" | "attachments";

export type WorkflowRolePreviewField = {
  key: string;
  label: string;
  section: string;
  value: string;
  source: "medicalRecord" | "archive";
  editable: boolean;
  required: boolean;
};

export type WorkflowRolePreviewAttachment = RecordAttachment & {
  isImage: boolean;
};

export type WorkflowRoleTaskSection = {
  key: "required" | "optional" | "reference";
  title: string;
  description: string;
  fieldKeys: string[];
  tasks: WorkflowFieldTask[];
  items: WorkflowRolePreviewField[];
};

export type WorkflowRoleReportSection = {
  key: string;
  title: string;
  summary: string;
  items: WorkflowRolePreviewField[];
  emptyText: string;
};

export type WorkflowRoleReviewAdvice = {
  statusLabel: string;
  statusTone: "success" | "warning" | "danger" | "info";
  issues: Array<{
    key: string;
    label: string;
    reason: string;
    fieldKey?: string;
    tone: "success" | "warning" | "danger" | "info";
  }>;
  recommendation: string;
  nextStep: string;
  focusFieldKeys: string[];
};

export type WorkflowRolePreview = {
  key: string;
  title: string;
  subtitle: string;
  description: string;
  roles: UserRole[];
  sectionKeys: string[];
  completedCount: number;
  totalCount: number;
  missingCount: number;
  attachmentCount: number;
  imageCount: number;
  statusLabel: string;
  statusTone: "success" | "warning" | "danger" | "info";
  runtimeStatus: WorkflowStageRuntimeStatus;
  runtimeStatusLabel: string;
  stageOwner: string;
  stageDepartment: string;
  taskSummary: WorkflowStageNode["taskSummary"];
  decisionSummary: WorkflowDecisionSummary;
  processGoal: string;
  keyConclusion: string;
  blockingReason: string;
  nextAction: string;
  summaryItems: WorkflowRolePreviewField[];
  missingItems: WorkflowRolePreviewField[];
  taskItems: WorkflowFieldTask[];
  requiredTasks: WorkflowRoleTaskSection;
  optionalTasks: WorkflowRoleTaskSection;
  referenceItems: WorkflowRoleTaskSection;
  reportTitle: string;
  reportSections: WorkflowRoleReportSection[];
  reviewAdvice: WorkflowRoleReviewAdvice;
  contextTitle: string;
  contextItems: WorkflowRolePreviewField[];
  attachments: WorkflowRolePreviewAttachment[];
  focusFieldKeys: string[];
  maintenanceFieldKeys: string[];
  canEdit: boolean;
  primaryTarget: RolePreviewTarget;
  primaryActionLabel: string;
};

type RolePreviewConfig = {
  key: string;
  title: string;
  subtitle: string;
  description: string;
  roles: UserRole[];
  sectionKeys: string[];
  medicalSections: string[];
  archiveFieldKeys: string[];
  medicalFieldKeys: string[];
  contextTitle?: string;
  contextArchiveFieldKeys?: string[];
  contextMedicalFieldKeys?: string[];
  contextLimit?: number;
  maintenanceFieldKeys?: string[];
  includeRoleOwnedMedicalFields?: boolean;
  primaryTarget: RolePreviewTarget;
  primaryActionLabel: string;
};

const rolePreviewConfigs: RolePreviewConfig[] = [
  {
    key: "frontdeskNurse",
    title: "前台/护士站",
    subtitle: "基础建档与入院信息",
    description: "负责患者身份、就诊号、联系方式、入院日期等基础信息；门诊/住院未确定的扩展项只作补充。",
    roles: ["frontdesk", "nurse", "nursing"],
    sectionKeys: ["basic", "arrivalSource"],
    medicalSections: ["病历首页"],
    archiveFieldKeys: ["patientName", "visitNo", "gender", "patientAge", "phone", "visitDate", "visitType"],
    medicalFieldKeys: [
      "patientName",
      "gender",
      "age",
      "nativePlace",
      "occupation",
      "maritalStatus",
      "nation",
      "admissionDate",
      "address",
      "contactName",
      "contactPhone"
    ],
    primaryTarget: "medicalRecord",
    primaryActionLabel: "进入目标病历维护基础信息"
  },
  {
    key: "inspection",
    title: "检查室/影像",
    subtitle: "图片上传与初步检查所见",
    description:
      "先完成图片/视频上传和初步检查所见记录，例如截石位、指检、肛门镜、心电或影像提示；这里只沉淀事实证据，不承担最终诊断。",
    roles: ["inspection", "ecg", "ultrasound"],
    sectionKeys: ["specialExam", "preOpScreening", "documentScope"],
    medicalSections: ["专科及辅助检查", "术前筛查"],
    archiveFieldKeys: [
      "inspectionImages",
      "inspectionBriefNote",
      "lithotomyExam",
      "digitalExam",
      "anoscope",
      "ecgResult",
      "colonoscopy"
    ],
    medicalFieldKeys: [
      "specialExamFullText",
      "ecgResult",
      "colonoscopy",
      "ecgStatus",
      "gastroscopyStatus",
      "colonoscopyStatus",
      "drChestStatus"
    ],
    maintenanceFieldKeys: [
      "inspectionImages",
      "inspectionBriefNote",
      "lithotomyExam",
      "analTension",
      "digitalExam",
      "anoscope",
      "ecgResult",
      "colonoscopy"
    ],
    primaryTarget: "medicalRecord",
    primaryActionLabel: "进入目标病历维护检查所见"
  },
  {
    key: "reception",
    title: "接诊/问诊",
    subtitle: "依据检查结果给出下一步建议",
    description: "接诊室先读取检查室上传的图片和初步所见，再补充主诉、现病史、病史采集，并给出下一步行动建议。",
    roles: ["reception", "doctor"],
    sectionKeys: ["chiefComplaint", "presentIllness", "history", "specialNeeds"],
    medicalSections: ["主诉与现病史", "既往个人婚育家族史", "中医四诊"],
    archiveFieldKeys: ["chiefComplaint", "onset", "operationHistory", "chronicDisease", "tcmLook"],
    medicalFieldKeys: [
      "chiefComplaintText",
      "presentIllnessText",
      "admissionReason",
      "generalConditionText",
      "pastHistory",
      "personalHistory",
      "marriageBirthHistory",
      "familyHistory",
      "tcmFourDiagnosisText",
      "tongue",
      "pulseCondition"
    ],
    contextTitle: "检查室传出结果",
    contextArchiveFieldKeys: ["inspectionBriefNote", "lithotomyExam", "digitalExam", "anoscope", "ecgResult", "colonoscopy"],
    contextMedicalFieldKeys: ["specialExamFullText", "ecgResult", "colonoscopy"],
    primaryTarget: "medicalRecord",
    primaryActionLabel: "进入目标病历维护接诊建议"
  },
  {
    key: "lab",
    title: "化验室",
    subtitle: "检验指标与术前筛查",
    description: "维护血常规、凝血、术前八项、尿常规、生化/糖化等检验内容，医生端实时作为目标病历依据。",
    roles: ["lab"],
    sectionKeys: ["preOpScreening", "auxiliary"],
    medicalSections: ["专科及辅助检查", "术前筛查"],
    archiveFieldKeys: ["bloodRoutine", "coagulation", "preOpEight", "urineRoutine", "biochemistry", "postprandialGlucose"],
    medicalFieldKeys: [
      "bloodRoutine",
      "bloodWbc",
      "bloodNeuPercent",
      "bloodLymPercent",
      "bloodMonPercent",
      "bloodRbc",
      "bloodHgb",
      "bloodPlt",
      "coagulation",
      "preOpEight",
      "urineRoutine",
      "biochemistry",
      "postprandialGlucose",
      "bloodRoutineStatus",
      "coagulationStatus",
      "preOpEightStatus",
      "liverFunctionStatus",
      "renalFunctionStatus",
      "fastingGlucoseStatus",
      "postprandialGlucoseStatus",
      "urineRoutineStatus"
    ],
    maintenanceFieldKeys: [
      "bloodRoutine",
      "bloodRoutineStatus",
      "bloodWbc",
      "bloodNeuPercent",
      "bloodLymPercent",
      "bloodMonPercent",
      "bloodRbc",
      "bloodHgb",
      "bloodPlt",
      "coagulation",
      "coagulationStatus",
      "preOpEight",
      "preOpEightStatus",
      "urineRoutine",
      "urineRoutineStatus",
      "biochemistry",
      "liverFunctionStatus",
      "renalFunctionStatus",
      "postprandialGlucose",
      "postprandialGlucoseStatus"
    ],
    primaryTarget: "medicalRecord",
    primaryActionLabel: "进入目标病历维护化验字段"
  },
  {
    key: "doctorDecision",
    title: "医生诊疗决策",
    subtitle: "中西医诊断、辨证与治疗方案",
    description: "承接检查室、接诊室和化验室的上游信息，形成西医诊断、中医辨证和治疗/手术方案。",
    roles: ["doctor"],
    sectionKeys: ["mainDiagnosis", "secondaryDiagnosis", "comorbidityTcm", "tcmInspection", "treatmentPlanManagement"],
    medicalSections: ["诊断", "首次病程记录", "术前小结"],
    archiveFieldKeys: [
      "westernDiagnosis",
      "secondaryDiagnosisList",
      "otherMainDiagnosis",
      "surgeryFeasibility",
      "tcmDiagnosis",
      "tcmSyndrome",
      "tcmTreatment",
      "comorbidityDisease",
      "comorbiditySyndrome"
    ],
    medicalFieldKeys: [
      "westernDiagnosis",
      "tcmDiagnosis",
      "tcmSyndromeBasis",
      "westernDiagnosisBasis",
      "tcmDifferentialDiagnosis",
      "westernDifferentialDiagnosis",
      "diagnosisTreatmentPlan",
      "preOpDiagnosis",
      "operationIndication"
    ],
    contextTitle: "上游检查、问诊与化验依据",
    contextArchiveFieldKeys: [
      "inspectionBriefNote",
      "lithotomyExam",
      "analTension",
      "digitalExam",
      "anoscope",
      "ecgResult",
      "colonoscopy",
      "chiefComplaint",
      "onset",
      "bloodRoutine",
      "coagulation",
      "preOpEight",
      "urineRoutine",
      "biochemistry"
    ],
    contextMedicalFieldKeys: [
      "specialExamFullText",
      "ecgResult",
      "colonoscopy",
      "chiefComplaintText",
      "presentIllnessText",
      "bloodRoutine",
      "coagulation",
      "preOpEight",
      "urineRoutine",
      "biochemistry"
    ],
    contextLimit: 12,
    maintenanceFieldKeys: [
      "westernDiagnosis",
      "secondaryDiagnosisList",
      "otherMainDiagnosis",
      "surgeryFeasibility",
      "tcmDiagnosis",
      "tcmSyndrome",
      "tcmTreatment",
      "comorbidityDisease",
      "comorbiditySyndrome"
    ],
    includeRoleOwnedMedicalFields: false,
    primaryTarget: "medicalRecord",
    primaryActionLabel: "进入目标病历汇总/医生工作台"
  },
  {
    key: "treatment",
    title: "治疗/手术",
    subtitle: "术前、术中与术后处理",
    description: "维护手术方式、麻醉、术后处理和治疗执行信息，形成医生病历生成的治疗主线。",
    roles: ["doctor", "nurse"],
    sectionKeys: ["operation", "treatmentPlanManagement"],
    medicalSections: ["术前小结", "术后首次病程", "术后连续查房"],
    archiveFieldKeys: ["mainOperation", "operationDate", "anesthesiaMethod", "treatmentPlan"],
    medicalFieldKeys: [
      "preOpSummary",
      "preOpDiagnosis",
      "operationIndication",
      "operationName",
      "anesthesiaMethod",
      "operationNotes",
      "preOpPreparation",
      "surgeryDate",
      "intraoperativeDiagnosis",
      "operationBriefProcess",
      "postOpTreatmentPlan",
      "postOpFirstRecordAt",
      "postOpRoundsJson"
    ],
    primaryTarget: "medicalRecord",
    primaryActionLabel: "进入目标病历维护治疗记录"
  },
  {
    key: "followup",
    title: "护理/随访",
    subtitle: "护理宣教、反馈与随访",
    description: "补充生命体征、护理宣教、出院建议和随访反馈，保留完整病程闭环。",
    roles: ["nurse", "nursing", "frontdesk", "doctor"],
    sectionKeys: ["followup", "patientFeedback", "tcmHealthManagement"],
    medicalSections: ["体格检查", "出院记录"],
    archiveFieldKeys: ["followupRecords", "patientSatisfaction", "patientPainLevel", "careAdvice"],
    medicalFieldKeys: [
      "vitalSigns",
      "dischargeAdmissionSituation",
      "dischargeTreatmentResult",
      "dischargeDiagnosis",
      "dischargeCondition",
      "dischargeAdvice",
      "tcmCareAdvice"
    ],
    primaryTarget: "medicalRecord",
    primaryActionLabel: "进入目标病历维护护理随访"
  },
  {
    key: "quality",
    title: "质控/归档",
    subtitle: "完整度、阻塞项和归档准备",
    description: "检查必填缺失、附件证据、关键字段和归档准备度，完整档案仍作为兜底审核入口。",
    roles: ["quality", "admin", "doctor"],
    sectionKeys: ["qualityCheck", "documentScope", "dip"],
    medicalSections: ["DIP", "签名与日期"],
    archiveFieldKeys: ["archiveQuality", "documentScope", "dipGroup"],
    medicalFieldKeys: ["doctorSignature", "seniorDoctorSignature"],
    primaryTarget: "archive",
    primaryActionLabel: "进入完整档案质控"
  }
];

const roleCanEdit = (role: UserRole, roles: UserRole[]) => role === "admin" || roles.includes(role);

const isValueFilled = (value: string) => {
  const normalized = String(value || "").trim();
  return Boolean(normalized) && normalized !== "待补充" && normalized !== "未见记录" && !normalized.includes("____");
};

const isImageAttachment = (attachment: RecordAttachment) =>
  /\.(png|jpe?g|gif|webp|bmp|heic|heif)$/i.test(attachment.fileName || attachment.url || "");

const uniqueBy = <T>(items: T[], keyOf: (item: T) => string) => {
  const index = new Map<string, T>();
  items.forEach(item => {
    const key = keyOf(item);
    if (!index.has(key)) index.set(key, item);
  });
  return Array.from(index.values());
};

const findBestStage = (stages: WorkflowStageNode[], sectionKeys: string[]) => {
  const rankedStages = stages
    .map(stage => ({
      stage,
      score: sectionKeys.filter(sectionKey =>
        [stage.key, stage.shortTitle, stage.title].some(value => value.includes(sectionKey))
      ).length
    }))
    .filter(item => item.score > 0);

  return rankedStages[0]?.stage;
};

const findStageByTask = (stages: WorkflowStageNode[], tasks: WorkflowFieldTask[], sectionKeys: string[]) => {
  const taskStageKey = tasks.find(task => sectionKeys.includes(task.sectionKey))?.stageKey;
  return stages.find(stage => stage.key === taskStageKey);
};

const fallbackStageSummary = (previewKey: string) => {
  const labels: Record<string, string> = {
    frontdeskNurse: "完成身份建档和基础信息确认",
    inspection: "先上传图片/视频，再补充镜下所见和初步检查描述",
    reception: "读取检查室结果，补充问诊并给出下一步建议",
    lab: "维护检验指标，形成医生可用的化验依据",
    doctorDecision: "整合上游结果，完成中西医诊断和治疗方案判断",
    treatment: "补充治疗、手术和病程执行记录",
    followup: "补充护理宣教、出院建议和随访闭环",
    quality: "检查阻塞项、附件证据和归档准备度"
  };

  return labels[previewKey] || "补齐本岗位负责资料";
};

const reportTitleByRole: Record<string, string> = {
  frontdeskNurse: "前台接诊建档报告",
  inspection: "检查室检查报告",
  reception: "接诊室反馈报告",
  lab: "化验室检验报告",
  doctorDecision: "医生诊疗决策报告",
  treatment: "治疗执行报告",
  followup: "护理随访报告",
  quality: "医生归档质控报告"
};

const fieldEditableByRole = (field: MedicalRecordTemplateField, role: UserRole) => {
  if (role === "admin") return true;
  if (!field.editorRoles?.length) return role === "doctor";
  return field.editorRoles.includes(role);
};

const toPreviewField = (field: MedicalRecordTemplateField, value: string, role: UserRole): WorkflowRolePreviewField => ({
  key: field.key,
  label: field.label,
  section: field.section,
  value,
  source: "medicalRecord",
  editable: fieldEditableByRole(field, role),
  required: field.required
});

const uniqueFieldKeys = (items: Array<string | undefined>) =>
  items.filter((item): item is string => Boolean(item)).filter((item, index, array) => array.indexOf(item) === index);

const taskRequiresAction = (task: WorkflowFieldTask) => task.status !== "complete";

const taskIsRequired = (task: WorkflowFieldTask) =>
  taskRequiresAction(task) &&
  (isWorkflowBlockingTask(task) ||
    task.required ||
    task.archiveRequired ||
    task.critical ||
    task.status === "invalid" ||
    task.status === "attachment" ||
    task.status === "review");

const requiredFieldPriorityByRole: Record<string, string[]> = {
  frontdeskNurse: ["patientName", "visitNo", "gender", "patientAge", "phone", "visitDate", "visitType"],
  inspection: ["inspectionImages", "inspectionBriefNote", "lithotomyExam", "digitalExam", "anoscope"],
  reception: ["chiefComplaint", "chiefComplaintText", "presentIllnessText", "admissionReason", "onset"],
  lab: ["bloodRoutine", "bloodRoutineStatus", "coagulation", "preOpEight", "urineRoutine", "biochemistry"],
  doctorDecision: ["westernDiagnosis", "tcmDiagnosis", "tcmSyndrome", "tcmTreatment", "diagnosisTreatmentPlan"],
  treatment: ["treatmentPlan", "mainOperation", "operationDate", "operationName", "postOpTreatmentPlan"],
  followup: ["vitalSigns", "dischargeAdvice", "tcmCareAdvice", "followupRecords", "careAdvice"],
  quality: ["archiveQuality", "documentScope", "doctorSignature", "seniorDoctorSignature"]
};

const taskSectionCopyByRole: Record<string, { required: string; optional: string; reference: string }> = {
  frontdeskNurse: {
    required: "优先确认身份、就诊号、联系方式和就诊入口，保证后续岗位能准确追踪患者。",
    optional: "补充住院/门诊扩展信息，提升后续报告完整度。",
    reference: "只展示当前建档和流程判断需要的基础参考。"
  },
  inspection: {
    required: "优先完成图片/视频、镜下所见和关键检查记录，避免接诊室无法判断。",
    optional: "补充视频、备注或其他辅助检查资料，便于医生复核。",
    reference: "上游基础信息只作为检查资料归属和核对依据。"
  },
  reception: {
    required: "优先补齐主诉、现病史和下一步建议，让后续中医或治疗岗位有明确依据。",
    optional: "补充既往史、个人史和一般情况，减少医生二次追问。",
    reference: "集中显示检查室已经形成的关键结果。"
  },
  doctorDecision: {
    required: "优先形成西医诊断、中医诊断、辨证依据和治疗方案判断。",
    optional: "补充鉴别诊断、合并症和手术适应证等医生判断依据。",
    reference: "集中查看检查、问诊和化验形成的上游证据。"
  },
  treatment: {
    required: "优先补齐治疗方案、手术/治疗执行和术后处理记录。",
    optional: "补充麻醉、术中经过和连续查房等过程性资料。",
    reference: "参考诊疗决策和术前信息，辅助治疗记录闭环。"
  },
  quality: {
    required: "优先处理签名、归档范围和阻塞项，判断是否可收束归档。",
    optional: "补充 DIP、归档备注和质控说明。",
    reference: "只展示会影响归档判断的关键上下游信息。"
  }
};

const sortByPriority = (items: WorkflowRolePreviewField[], priorityKeys: string[]) =>
  [...items].sort((left, right) => {
    const leftIndex = priorityKeys.includes(left.key) ? priorityKeys.indexOf(left.key) : Number.MAX_SAFE_INTEGER;
    const rightIndex = priorityKeys.includes(right.key) ? priorityKeys.indexOf(right.key) : Number.MAX_SAFE_INTEGER;
    if (leftIndex !== rightIndex) return leftIndex - rightIndex;
    return left.label.localeCompare(right.label, "zh-Hans-CN");
  });

const buildTaskSections = ({
  previewKey,
  allFields,
  contextItems,
  missingItems,
  relatedTasks
}: {
  previewKey: string;
  allFields: WorkflowRolePreviewField[];
  contextItems: WorkflowRolePreviewField[];
  missingItems: WorkflowRolePreviewField[];
  relatedTasks: WorkflowFieldTask[];
}) => {
  const priorityKeys = requiredFieldPriorityByRole[previewKey] || [];
  const copy = taskSectionCopyByRole[previewKey] || {
    required: "影响流转、归档或医生判断的字段会集中在这里。",
    optional: "补充后能让报告更完整，但第一版不作为真实流程推进条件。",
    reference: "上游岗位或患者基础信息，只用于辅助当前岗位判断。"
  };
  const requiredTasks = relatedTasks.filter(taskIsRequired).slice(0, 8);
  const priorityMissingKeys = allFields
    .filter(item => priorityKeys.includes(item.key) && !isValueFilled(item.value))
    .map(item => item.key);
  const requiredFieldKeys = new Set([
    ...priorityMissingKeys,
    ...missingItems.map(item => item.key),
    ...requiredTasks.map(task => task.fieldKey),
    ...allFields.filter(item => item.required && !isValueFilled(item.value)).map(item => item.key)
  ]);
  const optionalTasks = relatedTasks
    .filter(task => taskRequiresAction(task) && !requiredFieldKeys.has(task.fieldKey))
    .slice(0, 5);
  const optionalFieldKeys = new Set([
    ...optionalTasks.map(task => task.fieldKey),
    ...allFields
      .filter(item => !item.required && !isValueFilled(item.value) && !requiredFieldKeys.has(item.key))
      .slice(0, 5)
      .map(item => item.key)
  ]);
  const requiredItems = sortByPriority(
    allFields.filter(item => requiredFieldKeys.has(item.key)),
    priorityKeys
  );
  const optionalItems = sortByPriority(
    allFields.filter(item => optionalFieldKeys.has(item.key)),
    priorityKeys
  );

  return {
    required: {
      key: "required" as const,
      title: "必须完成",
      description: copy.required,
      fieldKeys: uniqueFieldKeys([...Array.from(requiredFieldKeys), ...requiredTasks.map(task => task.fieldKey)]),
      tasks: requiredTasks,
      items: requiredItems
    },
    optional: {
      key: "optional" as const,
      title: "建议补充",
      description: copy.optional,
      fieldKeys: uniqueFieldKeys([...Array.from(optionalFieldKeys), ...optionalTasks.map(task => task.fieldKey)]),
      tasks: optionalTasks,
      items: optionalItems
    },
    reference: {
      key: "reference" as const,
      title: "参考信息",
      description: copy.reference,
      fieldKeys: contextItems.map(item => item.key),
      tasks: [],
      items: contextItems
    }
  };
};

const takeFilled = (items: WorkflowRolePreviewField[], keys: string[], limit = 6) =>
  items.filter(item => keys.includes(item.key) && isValueFilled(item.value)).slice(0, limit);

const takeMissing = (items: WorkflowRolePreviewField[], keys: string[], limit = 6) =>
  items.filter(item => keys.includes(item.key)).slice(0, limit);

const reportStatusSummary = (missingItems: WorkflowRolePreviewField[], fallbackComplete: string) =>
  missingItems.length ? `当前仍缺 ${missingItems.map(item => item.label).join("、")}，建议先补齐后再流转。` : fallbackComplete;

const buildReportSections = ({
  previewKey,
  allFields,
  summaryItems,
  contextItems,
  missingItems,
  attachments
}: {
  previewKey: string;
  allFields: WorkflowRolePreviewField[];
  summaryItems: WorkflowRolePreviewField[];
  contextItems: WorkflowRolePreviewField[];
  missingItems: WorkflowRolePreviewField[];
  attachments: WorkflowRolePreviewAttachment[];
}): WorkflowRoleReportSection[] => {
  const completenessSummary = missingItems.length
    ? `仍有 ${missingItems.length} 项资料待补齐。`
    : attachments.length
      ? "资料主体已形成，并已关联附件证据。"
      : "资料主体已形成，暂无附件证据。";

  if (previewKey === "inspection") {
    return [
      {
        key: "inspection-evidence",
        title: "检查资料",
        summary: attachments.length ? `已上传 ${attachments.length} 份检查资料。` : "尚未上传检查图片或视频资料。",
        items: takeFilled(allFields, ["inspectionImages", "ecgResult", "colonoscopy"]),
        emptyText: "请先上传图片/视频或补充检查资料说明。"
      },
      {
        key: "inspection-findings",
        title: "检查所见",
        summary: "仅记录事实证据和初步所见，最终诊断由医生诊疗决策节点完成。",
        items: takeFilled(allFields, ["inspectionBriefNote", "lithotomyExam", "analTension", "digitalExam", "anoscope"], 8),
        emptyText: "镜下所见、截石位所见、肛指或肛门镜内容尚未形成。"
      },
      {
        key: "inspection-completeness",
        title: "资料完整性 / 待补事项",
        summary: completenessSummary,
        items: missingItems,
        emptyText: "暂无集中待补事项。"
      }
    ];
  }

  if (previewKey === "reception") {
    return [
      {
        key: "reception-upstream",
        title: "检查结果摘要",
        summary: "接诊判断优先读取检查室传出的图片所见和初步检查描述。",
        items: contextItems.slice(0, 6),
        emptyText: "尚未读取到检查室形成的关键结果。"
      },
      {
        key: "reception-history",
        title: "患者主诉与病史",
        summary: "汇总接诊问诊形成的主诉、现病史和病史补充。",
        items: takeFilled(
          allFields,
          ["chiefComplaint", "chiefComplaintText", "presentIllnessText", "onset", "operationHistory"],
          6
        ),
        emptyText: "接诊问诊内容尚未形成。"
      },
      {
        key: "reception-next",
        title: "接诊判断与下一步建议",
        summary: reportStatusSummary(missingItems, "接诊资料已形成，可进入中医辨证、治疗或医生综合判断。"),
        items: summaryItems.filter(item => !contextItems.some(context => context.key === item.key)).slice(0, 4),
        emptyText: "尚未形成明确的下一步建议。"
      }
    ];
  }

  if (previewKey === "frontdeskNurse") {
    return [
      {
        key: "frontdesk-identity",
        title: "患者基础信息",
        summary: "用于确认患者身份、就诊号和后续全流程追踪。",
        items: takeFilled(allFields, ["patientName", "visitNo", "gender", "patientAge", "phone", "visitDate", "visitType"], 7),
        emptyText: "患者基础信息尚未形成。"
      },
      {
        key: "frontdesk-completeness",
        title: "建档完整性",
        summary: reportStatusSummary(missingItems, "基础建档已满足后续岗位流转需要。"),
        items: takeMissing(
          missingItems,
          ["patientName", "visitNo", "gender", "patientAge", "phone", "visitDate", "visitType"],
          6
        ),
        emptyText: "暂无集中待补事项。"
      }
    ];
  }

  if (previewKey === "doctorDecision") {
    return [
      {
        key: "doctor-upstream",
        title: "上游依据",
        summary: "整合检查、问诊和化验结果，作为医生诊疗判断依据。",
        items: contextItems.slice(0, 8),
        emptyText: "尚未形成足够上游依据。"
      },
      {
        key: "doctor-diagnosis",
        title: "中西医诊断与辨证",
        summary: "集中展示西医诊断、中医诊断、证型和辨证依据。",
        items: takeFilled(
          allFields,
          ["westernDiagnosis", "tcmDiagnosis", "tcmSyndrome", "tcmSyndromeBasis", "westernDiagnosisBasis"],
          7
        ),
        emptyText: "医生诊断和辨证内容尚未形成。"
      },
      {
        key: "doctor-plan",
        title: "治疗方案建议",
        summary: reportStatusSummary(missingItems, "医生诊疗决策已形成，可进入治疗或归档准备。"),
        items: takeFilled(allFields, ["tcmTreatment", "diagnosisTreatmentPlan", "preOpDiagnosis", "operationIndication"], 6),
        emptyText: "尚未形成治疗方案建议。"
      }
    ];
  }

  if (previewKey === "treatment") {
    return [
      {
        key: "treatment-plan",
        title: "治疗方案",
        summary: "展示门诊/住院治疗、手术和治疗执行的核心安排。",
        items: takeFilled(allFields, ["treatmentPlan", "mainOperation", "operationName", "anesthesiaMethod", "operationDate"], 7),
        emptyText: "治疗或手术方案尚未形成。"
      },
      {
        key: "treatment-record",
        title: "执行与术后记录",
        summary: reportStatusSummary(missingItems, "治疗执行资料已形成，可进入随访或归档准备。"),
        items: takeFilled(
          allFields,
          ["operationNotes", "operationBriefProcess", "postOpTreatmentPlan", "postOpFirstRecordAt"],
          6
        ),
        emptyText: "暂无治疗执行或术后记录。"
      }
    ];
  }

  if (previewKey === "followup") {
    return [
      {
        key: "followup-care",
        title: "护理与出院建议",
        summary: "展示护理宣教、出院建议和中医调护建议。",
        items: takeFilled(allFields, ["vitalSigns", "dischargeAdvice", "tcmCareAdvice", "careAdvice"], 6),
        emptyText: "护理宣教或出院建议尚未形成。"
      },
      {
        key: "followup-feedback",
        title: "随访与患者反馈",
        summary: reportStatusSummary(missingItems, "随访闭环资料已形成。"),
        items: takeFilled(allFields, ["followupRecords", "patientSatisfaction", "patientPainLevel"], 6),
        emptyText: "暂无随访或患者反馈记录。"
      }
    ];
  }

  if (previewKey === "quality") {
    return [
      {
        key: "quality-readiness",
        title: "归档准备度",
        summary: reportStatusSummary(missingItems, "当前资料满足归档准备，可进入医生最终收束。"),
        items: takeFilled(
          allFields,
          ["archiveQuality", "documentScope", "dipGroup", "doctorSignature", "seniorDoctorSignature"],
          6
        ),
        emptyText: "尚未形成归档准备结论。"
      },
      {
        key: "quality-issues",
        title: "缺失 / 阻塞项",
        summary: completenessSummary,
        items: missingItems,
        emptyText: "暂无集中缺失或阻塞项。"
      }
    ];
  }

  return [
    {
      key: "role-conclusion",
      title: "岗位结论",
      summary: summaryItems[0] ? `${summaryItems[0].label}：${summaryItems[0].value}` : "尚未形成明确岗位结论。",
      items: summaryItems.slice(0, 6),
      emptyText: "该岗位尚未形成可预览内容。"
    },
    {
      key: "role-context",
      title: "上游参考",
      summary: contextItems.length ? "以下内容用于辅助医生或监管审阅。" : "暂无上游参考内容。",
      items: contextItems,
      emptyText: "暂无上游参考内容。"
    },
    {
      key: "role-completeness",
      title: "资料完整性 / 待补事项",
      summary: completenessSummary,
      items: missingItems,
      emptyText: "暂无集中待补事项。"
    }
  ];
};

const buildReviewAdvice = ({
  missingItems,
  relatedTasks,
  blockingReason,
  nextAction,
  fallbackAction
}: {
  missingItems: WorkflowRolePreviewField[];
  relatedTasks: WorkflowFieldTask[];
  blockingReason: string;
  nextAction: string;
  fallbackAction: string;
}): WorkflowRoleReviewAdvice => {
  const taskIssues = relatedTasks
    .filter(taskRequiresAction)
    .slice(0, 5)
    .map(task => ({
      key: `task-${task.sectionKey}-${task.fieldKey}`,
      label: task.fieldLabel,
      reason: task.reason || task.statusLabel,
      fieldKey: task.fieldKey,
      tone: task.statusTone
    }));
  const missingIssues = missingItems.slice(0, Math.max(0, 5 - taskIssues.length)).map(item => ({
    key: `missing-${item.key}`,
    label: item.label,
    reason: `${item.section} · 必填缺失`,
    fieldKey: item.key,
    tone: "warning" as const
  }));
  const issues = [...taskIssues, ...missingIssues];
  const blockingIssues = relatedTasks.filter(isWorkflowBlockingTask);
  const statusTone: WorkflowRoleReviewAdvice["statusTone"] = blockingIssues.length
    ? "danger"
    : issues.length
      ? "warning"
      : "success";
  const statusLabel = blockingIssues.length ? "需补充后审阅" : issues.length ? "可补充" : "可继续流转";

  return {
    statusLabel,
    statusTone,
    issues,
    recommendation: issues.length ? blockingReason : "当前岗位资料未发现明确阻塞项。",
    nextStep: nextAction || fallbackAction,
    focusFieldKeys: uniqueFieldKeys(issues.map(issue => issue.fieldKey))
  };
};

export const usePatientRolePreview = ({
  sections,
  medicalRecordSections,
  workflowTasks,
  attachmentsByField,
  currentRole,
  fieldValues
}: {
  sections: ComputedRef<RecordSection[]>;
  medicalRecordSections: ComputedRef<MedicalRecordTemplateSection[]>;
  workflowTasks: ComputedRef<PatientWorkflowTaskState>;
  attachmentsByField: ComputedRef<Record<string, RecordAttachment[]>>;
  currentRole: ComputedRef<UserRole>;
  fieldValues: Record<string, string>;
}) =>
  computed<WorkflowRolePreview[]>(() => {
    const archiveFields = sections.value.flatMap(section =>
      section.fields.map(field => ({
        field,
        section
      }))
    );
    const medicalFields = medicalRecordSections.value.flatMap(section => section.fields);
    const taskItems = [
      ...workflowTasks.value.ownerTasks,
      ...workflowTasks.value.supportTasks,
      ...workflowTasks.value.reviewTasks,
      ...workflowTasks.value.blockingItems
    ];

    return rolePreviewConfigs.map(config => {
      const archiveFieldItems = archiveFields
        .filter(({ field, section }) => config.sectionKeys.includes(section.key) || config.archiveFieldKeys.includes(field.key))
        .map<WorkflowRolePreviewField>(({ field, section }) => ({
          key: field.key,
          label: field.label,
          section: section.title,
          value: String(fieldValues[field.key] || ""),
          source: "archive",
          editable: currentRole.value === "admin" || field.editors.includes(currentRole.value),
          required: Boolean(field.required)
        }));

      const medicalFieldItems = medicalFields
        .filter(
          field =>
            config.medicalSections.includes(field.section) ||
            config.medicalFieldKeys.includes(field.key) ||
            (config.includeRoleOwnedMedicalFields !== false && field.editorRoles?.some(role => config.roles.includes(role)))
        )
        .map(field => toPreviewField(field, String(fieldValues[field.key] || ""), currentRole.value));

      const allFields = uniqueBy([...medicalFieldItems, ...archiveFieldItems], item => item.key);
      const contextArchiveItems = archiveFields
        .filter(({ field }) => config.contextArchiveFieldKeys?.includes(field.key))
        .map<WorkflowRolePreviewField>(({ field, section }) => ({
          key: field.key,
          label: field.label,
          section: section.title,
          value: String(fieldValues[field.key] || ""),
          source: "archive",
          editable: currentRole.value === "admin" || field.editors.includes(currentRole.value),
          required: false
        }));
      const contextMedicalItems = medicalFields
        .filter(field => config.contextMedicalFieldKeys?.includes(field.key))
        .map(field => toPreviewField(field, String(fieldValues[field.key] || ""), currentRole.value));
      const contextItems = uniqueBy([...contextMedicalItems, ...contextArchiveItems], item => item.key)
        .filter(item => isValueFilled(item.value))
        .slice(0, config.contextLimit || 6);
      const summaryItems = allFields.filter(item => isValueFilled(item.value)).slice(0, 8);
      const missingItems = allFields.filter(item => item.required && !isValueFilled(item.value)).slice(0, 8);
      const relatedFieldKeys = new Set([
        ...config.archiveFieldKeys,
        ...config.medicalFieldKeys,
        ...allFields.map(field => field.key),
        ...medicalFields
          .filter(field => config.medicalFieldKeys.includes(field.key))
          .flatMap(field => field.sourceArchiveKeys || field.sources || [])
      ]);
      const attachments = uniqueBy(
        Array.from(relatedFieldKeys)
          .flatMap(fieldKey => attachmentsByField.value[fieldKey] || [])
          .filter(attachment => attachment.status !== "voided")
          .map(attachment => ({ ...attachment, isImage: isImageAttachment(attachment) })),
        attachment => attachment.key
      );
      const relatedTasks = uniqueBy(
        taskItems.filter(
          task =>
            config.sectionKeys.includes(task.sectionKey) ||
            config.archiveFieldKeys.includes(task.fieldKey) ||
            config.medicalFieldKeys.includes(task.fieldKey)
        ),
        task => `${task.sectionKey}-${task.fieldKey}`
      ).slice(0, 8);
      const completedCount = allFields.filter(item => isValueFilled(item.value)).length;
      const totalCount = Math.max(allFields.length, relatedTasks.length);
      const missingCount = missingItems.length + relatedTasks.filter(task => task.status !== "complete").length;
      const imageCount = attachments.filter(attachment => attachment.isImage).length;
      const relatedStage =
        findStageByTask(workflowTasks.value.stageNodes, relatedTasks, config.sectionKeys) ||
        findBestStage(workflowTasks.value.stageNodes, config.sectionKeys) ||
        workflowTasks.value.stageNodes.find(stage => config.sectionKeys.includes(stage.key));
      const stageSummary = relatedStage?.taskSummary;
      const statusTone: WorkflowRolePreview["statusTone"] =
        relatedStage?.runtimeStatusTone ||
        (missingCount > 0 ? "warning" : completedCount > 0 || attachments.length > 0 ? "success" : "info");
      const focusFieldKeys = uniqueBy([...config.medicalFieldKeys, ...medicalFieldItems.map(field => field.key)], key => key);
      const firstConclusion = contextItems[0] || summaryItems[0];
      const blockingReason =
        stageSummary?.blockingReason ||
        relatedTasks.find(task => task.status !== "complete")?.reason ||
        missingItems[0]?.label ||
        "暂无明确阻塞项";
      const nextAction =
        stageSummary?.nextAction || (missingItems[0] ? `优先补齐 ${missingItems[0].label}` : fallbackStageSummary(config.key));
      const taskSections = buildTaskSections({
        previewKey: config.key,
        allFields,
        contextItems,
        missingItems,
        relatedTasks
      });
      const reportSections = buildReportSections({
        previewKey: config.key,
        allFields,
        summaryItems,
        contextItems,
        missingItems,
        attachments
      });
      const reviewAdvice = buildReviewAdvice({
        missingItems,
        relatedTasks,
        blockingReason,
        nextAction,
        fallbackAction: fallbackStageSummary(config.key)
      });

      return {
        key: config.key,
        title: config.title,
        subtitle: config.subtitle,
        description: config.description,
        roles: config.roles,
        sectionKeys: config.sectionKeys,
        completedCount,
        totalCount,
        missingCount,
        attachmentCount: attachments.length,
        imageCount,
        statusLabel:
          relatedStage?.runtimeStatusLabel ||
          (missingCount > 0 ? `${missingCount} 项待补` : completedCount > 0 ? "已形成记录" : "待开始"),
        statusTone,
        runtimeStatus: relatedStage?.runtimeStatus || "notStarted",
        runtimeStatusLabel: relatedStage?.runtimeStatusLabel || "未开始",
        stageOwner: relatedStage?.owner || config.roles.map(role => role).join("、"),
        stageDepartment: relatedStage?.department || config.title,
        taskSummary: stageSummary || {
          todoCount: relatedTasks.filter(task => task.status !== "complete").length,
          blockingCount: relatedTasks.filter(isWorkflowBlockingTask).length,
          attachmentMissingCount: relatedTasks.filter(task => task.status === "attachment").length,
          primaryTodo: missingItems[0]?.label,
          blockingReason,
          nextAction: missingItems[0] ? `优先补齐 ${missingItems[0].label}` : fallbackStageSummary(config.key),
          canHandle: roleCanEdit(currentRole.value, config.roles)
        },
        decisionSummary: workflowTasks.value.decisionSummary,
        processGoal: fallbackStageSummary(config.key),
        keyConclusion: firstConclusion ? `${firstConclusion.label}：${firstConclusion.value}` : "暂无已形成的关键结论",
        blockingReason,
        nextAction,
        summaryItems,
        missingItems,
        taskItems: relatedTasks,
        requiredTasks: taskSections.required,
        optionalTasks: taskSections.optional,
        referenceItems: taskSections.reference,
        reportTitle: reportTitleByRole[config.key] || `${config.title}报告`,
        reportSections,
        reviewAdvice,
        contextTitle: config.contextTitle || "上游参考",
        contextItems,
        attachments: attachments.slice(0, 6),
        focusFieldKeys,
        maintenanceFieldKeys: config.maintenanceFieldKeys || config.archiveFieldKeys,
        canEdit: roleCanEdit(currentRole.value, config.roles) || allFields.some(field => field.editable),
        primaryTarget: config.primaryTarget,
        primaryActionLabel: config.primaryActionLabel
      };
    });
  });
