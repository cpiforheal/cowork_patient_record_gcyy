import { computed, type ComputedRef } from "vue";
import type { MedicalRecordTemplateField, MedicalRecordTemplateSection } from "@/api/modules/clinic/types";
import type { RecordAttachment, RecordSection, UserRole } from "@/config/fieldPermissions";
import type { PatientWorkflowTaskState, WorkflowFieldTask } from "./usePatientWorkflowTasks";

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
  summaryItems: WorkflowRolePreviewField[];
  missingItems: WorkflowRolePreviewField[];
  taskItems: WorkflowFieldTask[];
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
  maintenanceFieldKeys?: string[];
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
            field.editorRoles?.some(role => config.roles.includes(role))
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
        .slice(0, 6);
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
      const statusTone: WorkflowRolePreview["statusTone"] =
        missingCount > 0 ? "warning" : completedCount > 0 || attachments.length > 0 ? "success" : "info";
      const focusFieldKeys = uniqueBy([...config.medicalFieldKeys, ...medicalFieldItems.map(field => field.key)], key => key);

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
        statusLabel: missingCount > 0 ? `${missingCount} 项待补` : completedCount > 0 ? "已形成记录" : "待开始",
        statusTone,
        summaryItems,
        missingItems,
        taskItems: relatedTasks,
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
