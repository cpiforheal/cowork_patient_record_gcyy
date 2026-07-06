import { ResultData } from "@/api/interface";
import {
  allRecordFields,
  canEditField,
  serviceCollaborators,
  recordSections,
  roleLabel,
  type UserRole,
  type RecordAttachment,
  type RecordField
} from "@/config/fieldPermissions";
import { initialFieldValues, roleToDepartment, seedTemplateFieldRules } from "./seed";
import { isValidDataUrl, storeClinicFileApi } from "./files";
import { getClinicApiBaseUrl, patchDb, readDb, writeDb } from "./storage";
import { authHeaders, handleUnauthorizedResponse, isUnauthorizedApiResponse } from "../authToken";
import type {
  AccountRow,
  AiDocumentGenerateResult,
  AiDocumentPreview,
  AiDocumentRequestPayload,
  AiDocumentTemplateResult,
  AiModelDetectionPayload,
  AiModelDetectionResult,
  AiAssistantRequest,
  AiAssistantResponse,
  AiAssistantAnalytics,
  AiAssistantLogListParams,
  AiAssistantLogListResult,
  AiPromptTemplateCandidate,
  AiPromptTemplateListResult,
  AiPromptTemplatePayload,
  AiRuntimeConfig,
  AiRuntimeConfigPayload,
  AiRecordSummary,
  AiRecordSummaryParams,
  DoubaoTtsConfigTestPayload,
  DoubaoTtsSpeakParams,
  DoubaoTtsSpeakResult,
  AuditLogRow,
  BackupConfigPayload,
  BackupDirectorySelection,
  BackupRunResult,
  BackupStatus,
  ClinicDb,
  CreatePatientParams,
  DepartmentRow,
  DocumentRestoreParams,
  DocumentVoidParams,
  DictRow,
  DuplicatePatientGroup,
  LegacyAttachmentMapping,
  LegacyFieldMapping,
  MaintenanceStatus,
  GeneratedAiDocument,
  GeneratedMedicalRecord,
  MedicalRecordGenerateResult,
  MedicalRecordPrecheckResult,
  MedicalRecordTemplateStatus,
  MedicalRecordWorkspaceSaveResult,
  OperationStats,
  PatientDetail,
  PatientListParams,
  PatientRow,
  PatientTimelineEvent,
  QualityIssue,
  QualityReviewActionParams,
  QualityReviewDetail,
  QualityReviewRow,
  RecycleDocumentRow,
  RoleRow,
  SaveRecordParams,
  SharedCaseCommitParams,
  SharedCaseFileItem,
  SharedCaseImportParams,
  SharedCaseImportResult,
  SharedCasePreviewParams,
  SharedCasePreviewResult,
  SystemOperationContext,
  TemplateFieldRule,
  TemplateFieldRuleParams,
  UploadDocumentsParams,
  UploadDocumentsResult,
  ValidationIssue,
  WorkReminder
} from "./types";

export type {
  AccountRow,
  AiDocumentBlock,
  AiDocumentGenerateResult,
  AiDocumentPreview,
  AiDocumentRequestPayload,
  AiDocumentTemplate,
  AiDocumentTemplateResult,
  AiModelDetectionPayload,
  AiModelDetectionResult,
  AiModelOption,
  AiAssistantAttachment,
  AiAssistantMessage,
  AiAssistantRequest,
  AiAssistantResponse,
  AiAssistantType,
  AiAssistantAnalytics,
  AiAssistantAnalyticsBucket,
  AiAssistantFrequentPrompt,
  AiAssistantKnowledgeMiss,
  AiAssistantLog,
  AiAssistantLogListParams,
  AiAssistantLogListResult,
  AiAssistantLogStatus,
  AiPromptTemplateCandidate,
  AiPromptTemplateListResult,
  AiPromptTemplatePayload,
  AiRuntimeConfig,
  AiRuntimeConfigPayload,
  AiRecordSummary,
  AiRecordSummaryParams,
  DoubaoTtsConfigTestPayload,
  DoubaoTtsSpeakParams,
  DoubaoTtsSpeakResult,
  AuditLogRow,
  BackupConfigPayload,
  BackupDirectorySelection,
  BackupRunResult,
  BackupStatus,
  CreatePatientParams,
  DepartmentRow,
  DocumentRestoreParams,
  DocumentVoidParams,
  DictRow,
  DuplicatePatientGroup,
  MaintenanceStatus,
  GeneratedAiDocument,
  GeneratedMedicalRecord,
  MedicalRecordGenerateResult,
  MedicalRecordPrecheckResult,
  MedicalRecordTemplateField,
  MedicalRecordTemplateStatus,
  MedicalRecordWorkspaceSaveResult,
  OperationStats,
  OperationContext,
  PatientDetail,
  PatientListParams,
  PatientRow,
  PatientTimelineEvent,
  QualityIssue,
  QualityReviewActionParams,
  QualityReviewDetail,
  QualityReviewRow,
  RecycleDocumentRow,
  RoleRow,
  SaveRecordParams,
  SharedCaseCommitParams,
  SharedCaseFileItem,
  SharedCaseImportParams,
  SharedCaseImportResult,
  SharedCasePreviewParams,
  SharedCasePreviewResult,
  SystemOperationContext,
  TemplateFieldRule,
  TemplateFieldRuleParams,
  UploadDocumentItem,
  UploadDocumentsParams,
  UploadDocumentsResult,
  ValidationIssue,
  WorkReminder
} from "./types";

const SUCCESS_CODE = 200 as unknown as string;

const now = () => {
  const date = new Date();
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(
    date.getMinutes()
  )}:${pad(date.getSeconds())}`;
};

const response = <T>(data: T, msg = "成功") =>
  Promise.resolve({
    code: SUCCESS_CODE,
    msg,
    data
  } as ResultData<T>);

const temporaryPasswordChars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789@#$%";

const createTemporaryPassword = () => {
  const values = new Uint32Array(12);
  crypto.getRandomValues(values);
  const body = Array.from(values, value => temporaryPasswordChars[value % temporaryPasswordChars.length]).join("");
  return `Tmp@${body}`;
};

const paginate = <T>(list: T[], pageNum: number, pageSize: number) => ({
  list: list.slice((pageNum - 1) * pageSize, pageNum * pageSize),
  total: list.length,
  pageNum,
  pageSize
});

const getPatientOrThrow = (db: ClinicDb, id: string) => {
  const patient = db.patients.find(item => item.id === id);
  if (!patient) throw new Error("患者不存在");
  return patient;
};

const verifyPatientSaved = async (id: string) => getPatientOrThrow(await readDb({ allowLocalFallback: false }), id);

const ensureSystemManager = (role?: string) => {
  if (role !== "admin") {
    throw new Error(`${roleLabel(role)}无权修改系统管理配置`);
  }
};

const valuePreview = (value?: string) => {
  const normalized = String(value ?? "");
  return normalized.length > 120 ? `${normalized.slice(0, 120)}...` : normalized;
};

const stripSystemContext = <T extends SystemOperationContext>(params: T) => {
  const payload = { ...params };
  delete payload.operator;
  delete payload.operatorRole;
  return payload;
};

const appendAuditLog = (db: ClinicDb, params: Omit<AuditLogRow, "id" | "time"> & { time?: string }) => {
  db.auditLogs ??= [];
  db.auditLogs.unshift({
    id: `audit-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    time: now(),
    result: "success",
    ...params
  });
};

const normalizeIdentity = (value?: string) =>
  String(value || "")
    .trim()
    .replace(/\s/g, "");

const isValidMobile = (value?: string) => !value || /^1[3-9]\d{9}$/.test(normalizeIdentity(value));

const patientVisitNos = (patient: PatientRow) => [
  patient.visitNo,
  ...(patient.encounterHistory || []).map(encounter => encounter.visitNo)
];

const ensureEncounterHistory = (patient: PatientRow) => {
  if (!patient.encounterHistory?.length) {
    patient.encounterHistory = [
      {
        id: `enc-${patient.id}-initial`,
        visitNo: patient.visitNo,
        visitDate: patient.visitDate,
        visitType: patient.visitType,
        doctor: patient.doctor,
        createdAt: patient.createdAt
      }
    ];
  }
  patient.encounterCount = patient.encounterHistory.length;
  return patient.encounterHistory;
};

const findPatientByVisitNo = (db: ClinicDb, visitNo: string) =>
  db.patients.find(patient => patientVisitNos(patient).includes(visitNo));

const findPatientByIdentity = (db: ClinicDb, name: string, phone?: string, allowNameOnly = false) => {
  const normalizedName = normalizeIdentity(name);
  const normalizedPhone = normalizeIdentity(phone);
  if (!normalizedName) return undefined;
  if (!normalizedPhone && !allowNameOnly) return undefined;
  return db.patients.find(patient => {
    if (normalizeIdentity(patient.name) !== normalizedName) return false;
    const recordPhone = normalizeIdentity(db.records[patient.id]?.phone);
    return normalizedPhone ? recordPhone === normalizedPhone : true;
  });
};

const appendPatientEncounter = (
  patient: PatientRow,
  params: { visitNo: string; visitDate: string; visitType: string; doctor: string; createdAt: string }
) => {
  const history = ensureEncounterHistory(patient);
  const existed = history.some(encounter => encounter.visitNo === params.visitNo);
  if (!existed) {
    history.push({
      id: `enc-${patient.id}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      visitNo: params.visitNo,
      visitDate: params.visitDate,
      visitType: params.visitType,
      doctor: params.doctor,
      createdAt: params.createdAt
    });
  }
  patient.encounterCount = history.length;
  patient.visitNo = params.visitNo;
  patient.visitDate = params.visitDate;
  patient.visitType = params.visitType;
  patient.doctor = params.doctor;
};

const templateRuleMap = (rules: TemplateFieldRule[] = []) =>
  rules.reduce<Record<string, TemplateFieldRule>>((result, rule) => {
    result[rule.fieldKey] = rule;
    return result;
  }, {});

const canEditByRule = (role: string | undefined, field: RecordField, rules: TemplateFieldRule[] = []) => {
  if (role === "admin") return true;
  const rule = templateRuleMap(rules)[field.key];
  if (!rule) return canEditField(role, field);
  return rule.enabled && Array.isArray(rule.editors) && rule.editors.includes(role as UserRole);
};

const isServiceCollaborativeRule = (field: RecordField, rules: TemplateFieldRule[] = []) => {
  const rule = templateRuleMap(rules)[field.key];
  const editors = Array.isArray(rule?.editors) ? rule.editors : Array.isArray(field.editors) ? field.editors : [];
  return serviceCollaborators.length === editors.length && serviceCollaborators.every(role => editors.includes(role));
};

const activeDocuments = (documents: RecordAttachment[] = []) => documents.filter(document => document.status !== "voided");

const persistDocumentFile = async (document: {
  fileName: string;
  contentDataUrl?: string;
  url?: string;
  storagePath?: string;
  patientId?: string;
  department?: string;
  operator?: string;
  operatorRole?: string;
  type?: string;
  typeLabel?: string;
}) => {
  if (!document.contentDataUrl || !isValidDataUrl(document.contentDataUrl)) {
    if (document.contentDataUrl && !document.url && !document.storagePath) {
      throw new Error("文件内容不是有效的 base64 数据，请重新选择原始文件上传");
    }
    return {
      url: document.url || "",
      storagePath: document.storagePath
    };
  }

  const { data } = await storeClinicFileApi({
    fileName: document.fileName,
    contentDataUrl: document.contentDataUrl,
    patientId: document.patientId,
    department: document.department,
    operator: document.operator,
    operatorRole: document.operatorRole,
    type: document.type,
    typeLabel: document.typeLabel
  });
  return {
    url: data.url,
    storagePath: data.storagePath
  };
};

const isIncompleteValue = (value?: string) => {
  const normalized = String(value || "").trim();
  return !normalized || normalized.includes("____") || normalized.includes("________") || normalized.includes("待回报");
};

const validateFieldMeta = (field: RecordField, value?: string) => {
  const normalized = String(value || "").trim();
  if (!normalized || isIncompleteValue(normalized)) return "";

  if (field.inputType === "number") {
    const numberValue = Number(normalized);
    if (!Number.isFinite(numberValue)) return `${field.label}必须填写数字`;
    if (field.min !== undefined && numberValue < field.min) return `${field.label}不能小于 ${field.min}`;
    if (field.max !== undefined && numberValue > field.max) return `${field.label}不能大于 ${field.max}`;
  }

  if (field.inputType === "date" && !/^\d{4}-\d{2}-\d{2}$/.test(normalized)) {
    return `${field.label}请选择正确日期`;
  }

  if (field.pattern && !new RegExp(field.pattern).test(normalized)) {
    return field.validationMessage || `${field.label}格式不正确`;
  }

  return "";
};

const normalizeRecordFieldValue = (
  field: RecordField,
  value?: string,
  incomingValues: Record<string, string> = {},
  currentRecord: Record<string, string> = {}
) => {
  const normalized = String(value || "").trim();
  if (!normalized || isIncompleteValue(normalized)) return normalized;

  if (field.inputType === "number") {
    if (/^-?\d+(\.\d+)?$/.test(normalized)) return normalized;
    return normalized.match(/-?\d+(\.\d+)?/)?.[0] || normalized;
  }

  if (field.inputType === "date") {
    const dateMatch = normalized.match(/^(\d{4})[年./-](\d{1,2})[月./-](\d{1,2})日?$/);
    if (!dateMatch) return normalized;
    const [, year, month, day] = dateMatch;
    return `${year}-${month.padStart(2, "0")}-${day.padStart(2, "0")}`;
  }

  if (field.inputType === "tel") {
    if (field.key === "contactPhone" && normalized.includes("同患者")) {
      return normalizeIdentity(incomingValues.phone || currentRecord.phone);
    }
    return normalizeIdentity(normalized);
  }

  return normalized;
};

const findFieldSection = (fieldKey: string) =>
  recordSections.find(section => section.fields.some(field => field.key === fieldKey));

const qualityIssueScore = (issues: QualityIssue[]) => {
  const criticalCount = issues.filter(item => item.level === "critical").length;
  const warningCount = issues.filter(item => item.level === "warning").length;
  return Math.max(0, 100 - criticalCount * 12 - warningCount * 4);
};

const buildQualityIssues = (
  values: Record<string, string>,
  documents: RecordAttachment[] = [],
  rules: TemplateFieldRule[] = seedTemplateFieldRules()
) => {
  const issues: QualityIssue[] = [];
  const rulesByField = templateRuleMap(rules);
  const documentMap = activeDocuments(documents).reduce<Record<string, number>>((map, document) => {
    map[document.fieldKey] = (map[document.fieldKey] || 0) + 1;
    return map;
  }, {});

  allRecordFields().forEach(field => {
    const rule = rulesByField[field.key];
    const enabled = rule?.enabled ?? true;
    if (!enabled) return;
    const section = findFieldSection(field.key);
    const fieldLabel = rule?.fieldLabel || field.label;
    const required = rule ? rule.qualityCheck && rule.required : field.required;
    const evidence = rule?.evidence ?? field.evidence;
    if (required && isIncompleteValue(values[field.key])) {
      issues.push({
        key: `required-${field.key}`,
        level: "critical",
        levelLabel: "严重",
        section: section?.title || "档案字段",
        field: fieldLabel,
        owner: section?.owner || "责任岗位",
        message: "必填字段仍为空、保留占位符或待回报。",
        suggestion: `退回${section?.owner || "责任岗位"}补充完整后再复审。`
      });
    }
    if (evidence && !documentMap[field.key]) {
      issues.push({
        key: `evidence-${field.key}`,
        level: "critical",
        levelLabel: "严重",
        section: section?.title || "附件依据",
        field: fieldLabel,
        owner: section?.owner || "检查科室",
        message: `缺少原始附件依据：${evidence}。`,
        suggestion: "退回对应岗位上传原图，归档包需保留附件索引。"
      });
    }
  });

  if (isIncompleteValue(values.hospitalDays) && !isIncompleteValue(values.reasonableDays)) {
    issues.push({
      key: "dip-hospital-days",
      level: "warning",
      levelLabel: "提醒",
      section: "十二、DIP",
      field: "住院天数",
      owner: "前台/质控",
      message: "DIP 已有合理住院天数，但基础信息住院天数仍未补齐。",
      suggestion: "出院前补齐住院天数，避免分组校验缺项。"
    });
  }

  if (!isIncompleteValue(values.westernDiagnosis) && !isIncompleteValue(values.mainOperation)) {
    issues.push({
      key: "diagnosis-operation-check",
      level: "warning",
      levelLabel: "提醒",
      section: "十一、手术",
      field: "诊断与手术",
      owner: "医生/质控",
      message: "主诊断与主要手术需人工确认一致。",
      suggestion: "归档前确认手术记录、术前讨论、DIP 分组使用同一主要诊断。"
    });
  }

  return issues;
};

const buildQualityReviewDetail = (db: ClinicDb, patient: PatientRow): QualityReviewDetail => {
  const archive = db.archive[patient.id] ?? { submitted: false, version: "V0.1-草稿", generatedAt: patient.updatedAt };
  const fieldValues = db.records[patient.id] ?? initialFieldValues();
  const documents = db.documents?.[patient.id] ?? [];
  const issues = buildQualityIssues(fieldValues, documents, db.templateFieldRules ?? seedTemplateFieldRules());
  const criticalCount = issues.filter(item => item.level === "critical").length;
  const warningCount = issues.filter(item => item.level === "warning").length;
  return {
    patient,
    fieldValues,
    attachments: activeDocuments(documents),
    issues,
    score: qualityIssueScore(issues),
    criticalCount,
    warningCount,
    archiveSubmitted: archive.submitted,
    archiveVersion: archive.version,
    generatedAt: archive.generatedAt
  };
};

const ensureQualityManager = (role?: string) => {
  if (!["admin", "quality"].includes(role || "")) {
    throw new Error(`${roleLabel(role)}无权执行档案审核`);
  }
};

const parseTime = (value?: string) => {
  const time = new Date(String(value || "").replace(/-/g, "/")).getTime();
  return Number.isFinite(time) ? time : Date.now();
};

export const validatePatientRecord = (values: Record<string, string>, rules: TemplateFieldRule[] = []) => {
  const issues: ValidationIssue[] = [];
  const rulesByField = templateRuleMap(rules);
  allRecordFields().forEach(field => {
    const rule = rulesByField[field.key];
    const required = rule ? rule.enabled && rule.qualityCheck && rule.required : field.required;
    const value = normalizeRecordFieldValue(field, values[field.key], values, values);
    if (required && (!value || value.includes("____") || value.includes("________"))) {
      issues.push({
        fieldKey: field.key,
        fieldLabel: rule?.fieldLabel || field.label,
        level: "error",
        message: "必填字段未完成"
      });
    }
    const metaMessage = validateFieldMeta(field, value);
    if (metaMessage) {
      issues.push({
        fieldKey: field.key,
        fieldLabel: rule?.fieldLabel || field.label,
        level: "error",
        message: metaMessage
      });
    }
  });
  return issues;
};

export const getPatientListApi = async (params: PatientListParams) => {
  const db = await readDb();
  const sectionIndex = recordSections.findIndex(section => section.key === params.sectionKey);
  const filtered = db.patients.filter(item => {
    ensureEncounterHistory(item);
    const nameMatched = !params.name || item.name.includes(params.name);
    const visitNoMatched = !params.visitNo || patientVisitNos(item).some(visitNo => visitNo.includes(params.visitNo || ""));
    const visitTypeMatched =
      !params.visitType ||
      item.visitType === params.visitType ||
      item.encounterHistory?.some(encounter => encounter.visitType === params.visitType);
    const dateMatched = (item.encounterHistory || []).some(encounter => {
      const fromMatched = !params.visitDateFrom || encounter.visitDate >= params.visitDateFrom;
      const toMatched = !params.visitDateTo || encounter.visitDate <= params.visitDateTo;
      return fromMatched && toMatched;
    });
    const statusMatched = !params.status || item.status === params.status;
    const sectionMatched = sectionIndex < 0 || item.completedCount >= sectionIndex;
    return nameMatched && visitNoMatched && visitTypeMatched && dateMatched && statusMatched && sectionMatched;
  });
  return response(paginate(filtered, params.pageNum, params.pageSize));
};

export const getPatientDetailApi = async (id: string) => {
  const db = await readDb();
  const patient = getPatientOrThrow(db, id);
  ensureEncounterHistory(patient);
  const archive = db.archive[id] ?? { submitted: false, version: "V0.1-草稿", generatedAt: now() };
  return response<PatientDetail>({
    patient,
    fieldValues: db.records[id] ?? initialFieldValues(),
    attachments: activeDocuments(db.documents?.[id] ?? []),
    archiveSubmitted: archive.submitted,
    archiveVersion: archive.version,
    generatedAt: archive.generatedAt
  });
};

export const createPatientApi = async (params: CreatePatientParams) => {
  const name = params.name.trim();
  const visitNo = params.visitNo.trim();
  if (!name) return Promise.reject(new Error("患者姓名不能为空"));
  if (!visitNo) return Promise.reject(new Error("门诊/住院号不能为空"));
  if (!isValidMobile(params.phone)) return Promise.reject(new Error("联系电话需为正确的11位手机号"));

  const db = await readDb();
  if (findPatientByVisitNo(db, visitNo)) {
    return Promise.reject(new Error("门诊/住院号已存在"));
  }

  const createdAt = now();
  const existingPatient = findPatientByIdentity(db, name, params.phone);
  if (existingPatient) {
    appendPatientEncounter(existingPatient, {
      visitNo,
      visitDate: params.visitDate,
      visitType: params.visitType,
      doctor: params.doctor,
      createdAt
    });
    existingPatient.currentStage = "前台登记";
    existingPatient.completedCount = Math.max(existingPatient.completedCount, 1);
    existingPatient.progressPercent = Math.round((existingPatient.completedCount / recordSections.length) * 100);
    existingPatient.status = "新增就诊待补充";
    existingPatient.riskType = "warning";
    existingPatient.updatedAt = createdAt;
    db.records[existingPatient.id] = {
      ...(db.records[existingPatient.id] ?? initialFieldValues()),
      patientName: name,
      visitNo,
      admissionDate: params.visitDate,
      admissionWay: params.visitType,
      phone: params.phone || db.records[existingPatient.id]?.phone || ""
    };
    db.archive[existingPatient.id] = {
      submitted: false,
      version: `V0.${existingPatient.encounterCount || 1}-新增就诊`,
      generatedAt: createdAt
    };
    appendAuditLog(db, {
      operator: params.doctor || "前台账号",
      role: "前台",
      patient: name,
      patientId: existingPatient.id,
      module: "patient",
      actionCode: "patient.encounter.append",
      targetType: "encounter",
      targetKey: existingPatient.id,
      targetLabel: visitNo,
      action: "追加就诊",
      detail: `同一患者追加${params.visitType}就诊，门诊/住院号：${visitNo}，累计 ${existingPatient.encounterCount || 1} 次就诊`
    });
    await writeDb(db);
    return response(await verifyPatientSaved(existingPatient.id), "已追加到既有患者档案");
  }

  const id = String(Date.now());
  const patient: PatientRow = {
    id,
    name,
    visitNo,
    visitDate: params.visitDate,
    visitType: params.visitType,
    doctor: params.doctor,
    encounterCount: 1,
    encounterHistory: [
      {
        id: `enc-${id}-initial`,
        visitNo,
        visitDate: params.visitDate,
        visitType: params.visitType,
        doctor: params.doctor,
        createdAt
      }
    ],
    currentStage: "前台登记",
    completedCount: 1,
    progressPercent: Math.round((1 / recordSections.length) * 100),
    status: "待补充资料",
    riskType: "warning",
    createdAt,
    updatedAt: createdAt
  };

  db.patients.unshift(patient);
  db.records[id] = {
    ...initialFieldValues(),
    patientName: name,
    visitNo,
    admissionDate: params.visitDate,
    admissionWay: params.visitType,
    phone: params.phone || ""
  };
  db.archive[id] = { submitted: false, version: "V0.1-草稿", generatedAt: createdAt };
  appendAuditLog(db, {
    operator: params.doctor || "前台账号",
    role: "前台",
    patient: name,
    patientId: id,
    module: "patient",
    actionCode: "patient.create",
    targetType: "patient",
    targetKey: id,
    targetLabel: visitNo,
    action: "创建患者",
    detail: `创建${params.visitType}患者，门诊/住院号：${visitNo}`
  });
  await writeDb(db);
  return response(await verifyPatientSaved(patient.id), "患者已创建");
};

export const savePatientRecordApi = async (params: SaveRecordParams) => {
  const db = await readDb();
  const patient = getPatientOrThrow(db, params.id);
  const record = db.records[params.id] ?? initialFieldValues();
  const fields = allRecordFields();
  const templateRules = db.templateFieldRules ?? seedTemplateFieldRules();
  const deniedLabels: string[] = [];
  const fieldChanges: Array<{ field: RecordField; beforeValue: string; afterValue: string }> = [];

  Object.entries(params.values).forEach(([key, value]) => {
    const field = fields.find(item => item.key === key);
    if (!field) return;
    if (!canEditByRule(params.role, field, templateRules)) {
      deniedLabels.push(templateRuleMap(templateRules)[field.key]?.fieldLabel || field.label);
      return;
    }
    const normalizedValue = normalizeRecordFieldValue(field, value, params.values, record);
    const beforeValue = record[key] || "";
    if (beforeValue !== normalizedValue) {
      fieldChanges.push({ field, beforeValue, afterValue: normalizedValue });
    }
    record[key] = normalizedValue;
  });

  if (deniedLabels.length) {
    appendAuditLog(db, {
      operator: params.operator || roleLabel(params.role),
      role: roleLabel(params.role),
      patient: patient.name,
      patientId: patient.id,
      module: "record",
      action: "拒绝修改档案字段",
      actionCode: "record.field.denied",
      targetType: "field",
      targetLabel: deniedLabels.join("、"),
      result: "denied",
      detail: `${roleLabel(params.role)}尝试修改无权限字段：${deniedLabels.join("、")}`
    });
    await writeDb(db);
    return Promise.reject(new Error(`${roleLabel(params.role)}无权修改：${deniedLabels.join("、")}`));
  }

  const issues = validatePatientRecord(record, templateRules);
  const invalidInputIssues = issues.filter(
    issue => Object.prototype.hasOwnProperty.call(params.values, issue.fieldKey) && issue.message !== "必填字段未完成"
  );
  if (invalidInputIssues.length) {
    return Promise.reject(new Error(invalidInputIssues.map(issue => `${issue.fieldLabel}：${issue.message}`).join("；")));
  }
  const completedSections = recordSections.filter(section =>
    section.fields.every(field => {
      const value = (record[field.key] || "").trim();
      return value && !value.includes("____") && !value.includes("________");
    })
  ).length;

  patient.name = record.patientName || patient.name;
  patient.visitNo = record.visitNo || patient.visitNo;
  appendPatientEncounter(patient, {
    visitNo: patient.visitNo,
    visitDate: patient.visitDate,
    visitType: record.admissionWay || patient.visitType,
    doctor: patient.doctor,
    createdAt: patient.updatedAt
  });
  patient.completedCount = Math.max(1, completedSections);
  patient.progressPercent = Math.round((patient.completedCount / recordSections.length) * 100);
  patient.currentStage = issues.length ? "档案补全" : "档案审核待审";
  patient.status = issues.length ? "待补充资料" : "可提交档案审核";
  patient.riskType = issues.length ? "warning" : "success";
  patient.updatedAt = now();
  db.records[params.id] = record;
  fieldChanges.forEach(change => {
    const rule = templateRuleMap(templateRules)[change.field.key];
    const isCollaborative = isServiceCollaborativeRule(change.field, templateRules);
    const isCreated = !String(change.beforeValue || "").trim() && Boolean(String(change.afterValue || "").trim());
    const fieldLabel = rule?.fieldLabel || change.field.label;
    appendAuditLog(db, {
      operator: params.operator || roleLabel(params.role),
      role: roleLabel(params.role),
      patient: patient.name,
      patientId: patient.id,
      module: isCollaborative ? "collaboration" : "record",
      action: isCollaborative ? (isCreated ? "协作补充档案字段" : "协作更新档案字段") : "修改档案字段",
      actionCode: isCollaborative ? "record.field.collaborative.update" : "record.field.update",
      targetType: "field",
      targetKey: change.field.key,
      targetLabel: fieldLabel,
      beforeValue: valuePreview(change.beforeValue),
      afterValue: valuePreview(change.afterValue),
      detail: `${roleLabel(params.role)}${isCreated ? "补充" : "更新"}「${fieldLabel}」：${
        valuePreview(change.beforeValue) || "空"
      } -> ${valuePreview(change.afterValue) || "空"}`
    });
  });
  appendAuditLog(db, {
    operator: params.operator || roleLabel(params.role),
    role: roleLabel(params.role),
    patient: patient.name,
    patientId: patient.id,
    module: "record",
    actionCode: "record.section.save",
    targetType: "record",
    targetKey: params.id,
    targetLabel: patient.visitNo,
    action: "保存档案字段",
    detail: `保存 ${Object.keys(params.values).length} 个字段，当前状态：${patient.status}`
  });
  await writeDb(db);
  return response({ patient, issues }, "档案字段已保存");
};

export const submitArchiveApi = async (params: { id: string; role: string; operator?: string }) => {
  if (!["admin", "doctor", "quality"].includes(params.role)) {
    return Promise.reject(new Error(`${roleLabel(params.role)}无权提交档案审核`));
  }

  const db = await readDb();
  const patient = getPatientOrThrow(db, params.id);
  const record = db.records[params.id] ?? initialFieldValues();
  const issues = validatePatientRecord(record, db.templateFieldRules ?? seedTemplateFieldRules());
  if (issues.some(item => item.level === "error")) {
    return Promise.reject(new Error(`仍有 ${issues.length} 个必填字段未完成，不能提交档案审核`));
  }

  const generatedAt = now();
  db.archive[params.id] = { submitted: true, version: "V1.0-待档案审核", generatedAt };
  patient.currentStage = "档案审核";
  patient.completedCount = Math.max(patient.completedCount, 14);
  patient.progressPercent = Math.round((patient.completedCount / recordSections.length) * 100);
  patient.status = "待档案审核";
  patient.riskType = "info";
  patient.updatedAt = generatedAt;
  appendAuditLog(db, {
    operator: params.operator || roleLabel(params.role),
    role: roleLabel(params.role),
    patient: patient.name,
    patientId: patient.id,
    module: "archive",
    actionCode: "archive.submit",
    targetType: "archive",
    targetKey: params.id,
    targetLabel: db.archive[params.id].version,
    afterValue: db.archive[params.id].version,
    action: "提交档案审核",
    detail: `提交患者健康管理档案审核，版本：${db.archive[params.id].version}`
  });
  await writeDb(db);
  return response({ patient, archive: db.archive[params.id] }, "已提交档案审核");
};

export const revokeArchiveApi = async (params: { id: string; role: string; operator?: string }) => {
  if (!["admin", "doctor", "quality"].includes(params.role)) {
    return Promise.reject(new Error(`${roleLabel(params.role)}无权撤回草稿`));
  }

  const db = await readDb();
  const patient = getPatientOrThrow(db, params.id);
  const generatedAt = now();
  db.archive[params.id] = { submitted: false, version: "V0.9-已撤回", generatedAt };
  patient.currentStage = "档案生成";
  patient.status = "档案生成中";
  patient.riskType = "warning";
  patient.updatedAt = generatedAt;
  appendAuditLog(db, {
    operator: params.operator || roleLabel(params.role),
    role: roleLabel(params.role),
    patient: patient.name,
    patientId: patient.id,
    module: "archive",
    actionCode: "archive.revoke",
    targetType: "archive",
    targetKey: params.id,
    targetLabel: db.archive[params.id].version,
    afterValue: db.archive[params.id].version,
    action: "撤回草稿",
    detail: `撤回档案审核提交，版本：${db.archive[params.id].version}`
  });
  await writeDb(db);
  return response({ patient, archive: db.archive[params.id] }, "已撤回草稿");
};

export const getAccountListApi = async (params: {
  pageNum: number;
  pageSize: number;
  name?: string;
  department?: string;
  role?: string[] | string;
  status?: string;
}) => {
  const db = await readDb();
  const normalizeListFilter = (value?: string) => {
    const normalized = String(value ?? "").trim();
    return ["", "all", "ALL", "__all", "全部"].includes(normalized) ? "" : normalized;
  };
  const keyword = String(params.name ?? "").trim();
  const departmentFilter = normalizeListFilter(params.department);
  const statusFilter = normalizeListFilter(params.status);
  const roleFilter = (Array.isArray(params.role) ? params.role : [params.role])
    .map(item => normalizeListFilter(item))
    .filter(Boolean);
  const filtered = (db.accounts ?? []).filter(item => {
    const nameMatched = !keyword || item.name.includes(keyword) || item.username.includes(keyword);
    const departmentMatched = !departmentFilter || item.department === departmentFilter;
    const roleMatched = roleFilter.length === 0 || roleFilter.includes(item.roleLabel) || roleFilter.includes(item.role);
    const statusMatched = !statusFilter || item.status === statusFilter;
    return nameMatched && departmentMatched && roleMatched && statusMatched;
  });
  return response(paginate(filtered, params.pageNum, params.pageSize));
};

export const getAccountDepartmentOptionsApi = async () => {
  const db = await readDb();
  const departmentNames = new Set<string>();
  (db.departments ?? []).forEach(item => {
    const name = String(item.name || "").trim();
    if (name) departmentNames.add(name);
  });
  (db.accounts ?? []).forEach(item => {
    const name = String(item.department || "").trim();
    if (name) departmentNames.add(name);
  });
  return response(Array.from(departmentNames).sort((left, right) => left.localeCompare(right, "zh-Hans-CN")));
};

export const saveAccountApi = async (params: Partial<AccountRow> & SystemOperationContext) => {
  ensureSystemManager(params.operatorRole);
  const payload = stripSystemContext(params);
  const db = await readDb();
  const list = db.accounts ?? [];
  const updatedAt = now();
  let temporaryPassword = "";
  if (params.id) {
    const target = list.find(item => item.id === params.id);
    if (!target) return Promise.reject(new Error("账号不存在"));
    Object.assign(target, payload, { roleLabel: roleLabel(payload.role || target.role), updatedAt });
  } else {
    const role = (params.role || "frontdesk") as UserRole;
    temporaryPassword = params.password || createTemporaryPassword();
    list.unshift({
      id: String(Date.now()),
      username: params.username || `user${Date.now()}`,
      password: temporaryPassword,
      currentPassword: temporaryPassword,
      name: params.name || "新账号",
      department: params.department || roleToDepartment[role],
      role,
      roleLabel: roleLabel(role),
      scope: params.scope || `维护${params.department || roleToDepartment[role]}职责范围内资料`,
      status: params.status || "启用",
      createdAt: updatedAt,
      updatedAt
    });
  }
  db.accounts = list;
  appendAuditLog(db, {
    operator: params.operator || roleLabel(params.operatorRole || "admin"),
    role: roleLabel(params.operatorRole || "admin"),
    patient: "-",
    module: "system",
    action: params.id ? "修改账号" : "新增账号",
    actionCode: params.id ? "account.update" : "account.create",
    targetType: "account",
    targetKey: params.id || list[0]?.id,
    targetLabel: params.name || params.username || "-",
    afterValue: valuePreview(JSON.stringify(params)),
    detail: `${params.id ? "修改" : "新增"}账号：${params.name || params.username || "-"}`
  });
  await writeDb(db);
  return response(
    temporaryPassword ? { temporaryPassword } : null,
    temporaryPassword ? "账号已保存，请记录临时密码" : "账号已保存"
  );
};

export const setAccountStatusApi = async (id: string, status: AccountRow["status"], context: SystemOperationContext = {}) => {
  ensureSystemManager(context.operatorRole);
  const db = await readDb();
  const target = db.accounts?.find(item => item.id === id);
  if (!target) return Promise.reject(new Error("账号不存在"));
  const beforeStatus = target.status;
  target.status = status;
  target.updatedAt = now();
  appendAuditLog(db, {
    operator: context.operator || roleLabel(context.operatorRole || "admin"),
    role: roleLabel(context.operatorRole || "admin"),
    patient: "-",
    module: "system",
    action: "切换账号状态",
    actionCode: "account.status",
    targetType: "account",
    targetKey: target.id,
    targetLabel: target.name,
    beforeValue: beforeStatus,
    afterValue: status,
    detail: `${target.name} 状态调整为 ${status}`
  });
  await writeDb(db);
  return response(null, status === "停用" ? "账号已停用" : "账号已启用");
};

export const resetAccountPasswordApi = async (id: string, context: SystemOperationContext & { password?: string } = {}) => {
  ensureSystemManager(context.operatorRole);
  const db = await readDb();
  const target = db.accounts?.find(item => item.id === id);
  if (!target) return Promise.reject(new Error("账号不存在"));
  const temporaryPassword = context.password?.trim() || createTemporaryPassword();
  target.password = temporaryPassword;
  target.currentPassword = temporaryPassword;
  target.updatedAt = now();
  appendAuditLog(db, {
    operator: context.operator || roleLabel(context.operatorRole || "admin"),
    role: roleLabel(context.operatorRole || "admin"),
    patient: "-",
    module: "system",
    action: "重置账号密码",
    actionCode: "account.password.reset",
    targetType: "account",
    targetKey: target.id,
    targetLabel: target.name,
    detail: `${target.name} 密码已重置为一次性临时密码`
  });
  await writeDb(db);
  return response({ temporaryPassword }, "密码已重置，请记录临时密码");
};

export const deleteAccountApi = async (id: string, context: SystemOperationContext = {}) => {
  ensureSystemManager(context.operatorRole);
  const db = await readDb();
  const list = db.accounts ?? [];
  const target = list.find(item => item.id === id);
  if (!target) return Promise.reject(new Error("账号不存在"));
  if (target.id === "admin" || target.username === "admin") return Promise.reject(new Error("内置管理员账号不能删除"));
  db.accounts = list.filter(item => item.id !== id);
  appendAuditLog(db, {
    operator: context.operator || roleLabel(context.operatorRole || "admin"),
    role: roleLabel(context.operatorRole || "admin"),
    patient: "-",
    module: "system",
    action: "删除账号",
    actionCode: "account.delete",
    targetType: "account",
    targetKey: target.id,
    targetLabel: target.name || target.username,
    beforeValue: valuePreview(JSON.stringify(target)),
    detail: `删除账号：${target.name || target.username}（${target.department}/${target.roleLabel}）`
  });
  await writeDb(db);
  return response(null, "账号已删除");
};

export const getRoleListApi = async (params: { pageNum: number; pageSize: number; name?: string }) => {
  const db = await readDb();
  const filtered = (db.roles ?? []).filter(item => !params.name || item.name.includes(params.name));
  return response(paginate(filtered, params.pageNum, params.pageSize));
};

export const saveRoleApi = async (params: Partial<RoleRow> & SystemOperationContext) => {
  ensureSystemManager(params.operatorRole);
  const payload = stripSystemContext(params);
  const db = await readDb();
  const list = db.roles ?? [];
  if (params.id) {
    const target = list.find(item => item.id === params.id);
    if (!target) return Promise.reject(new Error("角色不存在"));
    Object.assign(target, payload);
  } else {
    const role = (params.role || "frontdesk") as UserRole;
    list.unshift({
      id: String(Date.now()),
      name: params.name || roleLabel(role),
      role,
      members: 0,
      desc: params.desc || "",
      permissions: params.permissions || [],
      editableSections: params.editableSections || []
    });
  }
  db.roles = list;
  appendAuditLog(db, {
    operator: params.operator || roleLabel(params.operatorRole || "admin"),
    role: roleLabel(params.operatorRole || "admin"),
    patient: "-",
    module: "system",
    action: params.id ? "修改角色权限" : "新增角色",
    actionCode: params.id ? "role.update" : "role.create",
    targetType: "role",
    targetKey: params.id || list[0]?.id,
    targetLabel: params.name || roleLabel(params.role),
    afterValue: valuePreview(JSON.stringify(params)),
    detail: `${params.id ? "修改" : "新增"}角色：${params.name || roleLabel(params.role)}`
  });
  await writeDb(db);
  return response(null, "角色已保存");
};

export const deleteRoleApi = async (id: string, context: SystemOperationContext = {}) => {
  ensureSystemManager(context.operatorRole);
  const db = await readDb();
  const list = db.roles ?? [];
  const target = list.find(item => item.id === id);
  if (!target) return Promise.reject(new Error("角色不存在"));
  if (target.role === "admin") return Promise.reject(new Error("内置管理员角色不能删除"));
  const memberCount = (db.accounts ?? []).filter(account => account.role === target.role).length;
  if (memberCount > 0) return Promise.reject(new Error(`该角色仍有 ${memberCount} 个账号使用，请先调整账号角色`));
  db.roles = list.filter(item => item.id !== id);
  appendAuditLog(db, {
    operator: context.operator || roleLabel(context.operatorRole || "admin"),
    role: roleLabel(context.operatorRole || "admin"),
    patient: "-",
    module: "system",
    action: "删除角色",
    actionCode: "role.delete",
    targetType: "role",
    targetKey: target.id,
    targetLabel: target.name,
    beforeValue: valuePreview(JSON.stringify(target)),
    detail: `删除角色：${target.name}（${roleLabel(target.role)}）`
  });
  await writeDb(db);
  return response(null, "角色已删除");
};

export const getDepartmentListApi = async (params: { pageNum: number; pageSize: number; name?: string }) => {
  const db = await readDb();
  const filtered = (db.departments ?? []).filter(item => !params.name || item.name.includes(params.name));
  return response(paginate(filtered, params.pageNum, params.pageSize));
};

export const saveDepartmentApi = async (params: Partial<DepartmentRow> & SystemOperationContext) => {
  ensureSystemManager(params.operatorRole);
  const payload = stripSystemContext(params);
  const db = await readDb();
  const list = db.departments ?? [];
  if (params.id) {
    const target = list.find(item => item.id === params.id);
    if (!target) return Promise.reject(new Error("科室不存在"));
    Object.assign(target, payload);
  } else {
    list.unshift({
      id: String(Date.now()),
      name: params.name || "新科室",
      uploadTypes: params.uploadTypes || "",
      scope: params.scope || "",
      defaultRole: params.defaultRole || "frontdesk"
    });
  }
  db.departments = list;
  appendAuditLog(db, {
    operator: params.operator || roleLabel(params.operatorRole || "admin"),
    role: roleLabel(params.operatorRole || "admin"),
    patient: "-",
    module: "system",
    action: params.id ? "修改科室" : "新增科室",
    actionCode: params.id ? "department.update" : "department.create",
    targetType: "department",
    targetKey: params.id || list[0]?.id,
    targetLabel: params.name || "-",
    afterValue: valuePreview(JSON.stringify(params)),
    detail: `${params.id ? "修改" : "新增"}科室：${params.name || "-"}`
  });
  await writeDb(db);
  return response(null, "科室已保存");
};

export const deleteDepartmentApi = async (id: string, context: SystemOperationContext = {}) => {
  ensureSystemManager(context.operatorRole);
  const db = await readDb();
  const list = db.departments ?? [];
  const target = list.find(item => item.id === id);
  if (!target) return Promise.reject(new Error("科室不存在"));
  const accountCount = (db.accounts ?? []).filter(account => account.department === target.name).length;
  if (accountCount > 0) return Promise.reject(new Error(`该科室仍有 ${accountCount} 个账号使用，请先调整账号所属科室`));
  db.departments = list.filter(item => item.id !== id);
  appendAuditLog(db, {
    operator: context.operator || roleLabel(context.operatorRole || "admin"),
    role: roleLabel(context.operatorRole || "admin"),
    patient: "-",
    module: "system",
    action: "删除科室",
    actionCode: "department.delete",
    targetType: "department",
    targetKey: target.id,
    targetLabel: target.name,
    beforeValue: valuePreview(JSON.stringify(target)),
    detail: `删除科室：${target.name}`
  });
  await writeDb(db);
  return response(null, "科室已删除");
};

export const getDictListApi = async (params: { pageNum: number; pageSize: number; name?: string; department?: string }) => {
  const db = await readDb();
  const filtered = (db.dictionaries ?? []).filter(item => {
    const nameMatched = !params.name || item.name.includes(params.name);
    const departmentMatched = !params.department || item.department === params.department;
    return nameMatched && departmentMatched;
  });
  return response(paginate(filtered, params.pageNum, params.pageSize));
};

export const saveDictApi = async (params: Partial<DictRow> & SystemOperationContext) => {
  ensureSystemManager(params.operatorRole);
  const payload = stripSystemContext(params);
  const db = await readDb();
  const list = db.dictionaries ?? [];
  if (params.id) {
    const target = list.find(item => item.id === params.id);
    if (!target) return Promise.reject(new Error("资料类型不存在"));
    Object.assign(target, payload);
  } else {
    list.unshift({
      id: String(Date.now()),
      name: params.name || "新资料类型",
      department: params.department || "前台",
      naming: params.naming || "患者姓名-门诊号-资料类型-版本",
      required: params.required || "按需"
    });
  }
  db.dictionaries = list;
  appendAuditLog(db, {
    operator: params.operator || roleLabel(params.operatorRole || "admin"),
    role: roleLabel(params.operatorRole || "admin"),
    patient: "-",
    module: "system",
    action: params.id ? "修改资料类型" : "新增资料类型",
    actionCode: params.id ? "dict.update" : "dict.create",
    targetType: "dictionary",
    targetKey: params.id || list[0]?.id,
    targetLabel: params.name || "-",
    afterValue: valuePreview(JSON.stringify(params)),
    detail: `${params.id ? "修改" : "新增"}资料类型：${params.name || "-"}`
  });
  await writeDb(db);
  return response(null, "资料类型已保存");
};

export const getTemplateFieldRulesApi = async () => {
  const db = await readDb();
  const rules = (db.templateFieldRules ?? seedTemplateFieldRules()).slice().sort((a, b) => a.sortNo - b.sortNo);
  return response(rules);
};

export const getTemplateFieldRuleListApi = async (params: TemplateFieldRuleParams) => {
  const db = await readDb();
  const filtered = (db.templateFieldRules ?? seedTemplateFieldRules())
    .slice()
    .sort((a, b) => a.sortNo - b.sortNo)
    .filter(item => {
      const sectionMatched = !params.sectionTitle || item.sectionTitle.includes(params.sectionTitle);
      const fieldMatched = !params.fieldLabel || item.fieldLabel.includes(params.fieldLabel);
      const departmentMatched = !params.department || item.department === params.department;
      const statusMatched =
        !params.editStatus || (params.editStatus === "启用" && item.enabled) || (params.editStatus === "停用" && !item.enabled);
      const qualityMatched =
        !params.qualityCheck ||
        (params.qualityCheck === "纳入" && item.qualityCheck) ||
        (params.qualityCheck === "不纳入" && !item.qualityCheck);
      return sectionMatched && fieldMatched && departmentMatched && statusMatched && qualityMatched;
    });
  return response(paginate(filtered, params.pageNum, params.pageSize));
};

export const saveTemplateFieldRuleApi = async (params: Partial<TemplateFieldRule> & SystemOperationContext) => {
  ensureSystemManager(params.operatorRole);
  const payload = stripSystemContext(params);
  if (!params.id) return Promise.reject(new Error("模板字段不存在"));
  const db = await readDb();
  const list = db.templateFieldRules ?? seedTemplateFieldRules();
  const target = list.find(item => item.id === params.id);
  if (!target) return Promise.reject(new Error("模板字段不存在"));
  const beforeRule = JSON.stringify(target);
  Object.assign(target, payload, {
    editorLabels: (payload.editors || target.editors).map(editor => roleLabel(editor)),
    updatedAt: now()
  });
  db.templateFieldRules = list;
  appendAuditLog(db, {
    operator: params.operator || roleLabel(params.operatorRole || "admin"),
    role: roleLabel(params.operatorRole || "admin"),
    patient: "-",
    module: "template",
    action: "修改模板字段规则",
    actionCode: "template.field.rule.update",
    targetType: "templateFieldRule",
    targetKey: target.fieldKey,
    targetLabel: target.fieldLabel,
    beforeValue: valuePreview(beforeRule),
    afterValue: valuePreview(JSON.stringify(target)),
    detail: `修改字段规则：${target.fieldLabel}`
  });
  await writeDb(db);
  return response(target, "模板字段规则已保存");
};

const inferDocumentType = (fileName: string) => {
  if (/血常规/.test(fileName)) return { fieldKey: "bloodRoutine", fieldLabel: "血常规", department: "化验室" };
  if (/凝血/.test(fileName)) return { fieldKey: "coagulation", fieldLabel: "凝血功能", department: "化验室" };
  if (/心电/.test(fileName)) return { fieldKey: "ecgResult", fieldLabel: "心电图", department: "心电室" };
  if (/B超|彩超|超声/.test(fileName)) return { fieldKey: "colonoscopy", fieldLabel: "影像/肠镜", department: "B超/放射" };
  if (/照片|复查/.test(fileName)) return { fieldKey: "followupRecordsJson", fieldLabel: "复查照片", department: "前台" };
  return null;
};

const inspectionKeywordMeta = (fileName: string) => {
  if (/肛门镜|肛镜|镜检|专科检查|截石|指诊|肛缘|痔核/i.test(fileName)) {
    return { fieldKey: "inspectionImages", fieldLabel: "检查室专科图片", department: roleToDepartment.inspection };
  }
  if (/B超|彩超|超声/i.test(fileName))
    return { fieldKey: "colonoscopy", fieldLabel: "B超/影像", department: roleToDepartment.ultrasound };
  if (/心电|ecg/i.test(fileName)) return { fieldKey: "ecgResult", fieldLabel: "心电图", department: roleToDepartment.ecg };
  if (/血常规/i.test(fileName)) return { fieldKey: "bloodRoutine", fieldLabel: "血常规", department: roleToDepartment.lab };
  if (/凝血/i.test(fileName)) return { fieldKey: "coagulation", fieldLabel: "凝血功能", department: roleToDepartment.lab };
  return null;
};

const documentTypeMeta = (type: string, typeLabel: string, role: string, fileName = "", autoClassify = false) => {
  const fallbackDepartment = roleToDepartment[(role as UserRole) || "frontdesk"] || roleLabel(role);
  if (role === "inspection" && autoClassify) {
    const inferred = inspectionKeywordMeta(`${fileName} ${typeLabel} ${type}`);
    if (inferred) return { ...inferred, classifyStatus: "matched" as const };
  }
  const map: Record<string, { fieldKey: string; fieldLabel: string; department?: string }> = {
    bloodRoutine: { fieldKey: "bloodRoutine", fieldLabel: typeLabel, department: roleToDepartment.lab },
    coagulation: { fieldKey: "coagulation", fieldLabel: typeLabel, department: roleToDepartment.lab },
    labBiochemistry: {
      fieldKey: "biochemistry",
      fieldLabel: typeLabel || "生化肝肾功模板报告",
      department: roleToDepartment.lab
    },
    labHbvFive: { fieldKey: "preOpEight", fieldLabel: typeLabel || "乙肝五项模板报告", department: roleToDepartment.lab },
    labInfectious: { fieldKey: "preOpEight", fieldLabel: typeLabel || "术前感染筛查模板报告", department: roleToDepartment.lab },
    labCrpSaa: { fieldKey: "crpStatus", fieldLabel: typeLabel || "CRP/SAA模板报告", department: roleToDepartment.lab },
    labUrineRoutine: { fieldKey: "urineRoutine", fieldLabel: typeLabel || "尿常规模板报告", department: roleToDepartment.lab },
    labHba1c: { fieldKey: "biochemistry", fieldLabel: typeLabel || "糖化血红蛋白模板报告", department: roleToDepartment.lab },
    labPostprandialGlucose: {
      fieldKey: "postprandialGlucose",
      fieldLabel: typeLabel || "餐后血糖模板报告",
      department: roleToDepartment.lab
    },
    ecg: { fieldKey: "ecgResult", fieldLabel: typeLabel, department: roleToDepartment.ecg },
    ultrasound: { fieldKey: "colonoscopy", fieldLabel: typeLabel, department: roleToDepartment.ultrasound },
    followup: { fieldKey: "followupRecordsJson", fieldLabel: typeLabel, department: roleToDepartment.frontdesk },
    inspectionImage: {
      fieldKey: "inspectionImages",
      fieldLabel: typeLabel || "检查室图片",
      department: roleToDepartment.inspection
    },
    inspectionPending: {
      fieldKey: "inspectionPending",
      fieldLabel: typeLabel || "检查室待分拣",
      department: roleToDepartment.inspection
    }
  };
  const meta =
    map[type] ||
    (role === "inspection"
      ? {
          fieldKey: "inspectionPending",
          fieldLabel: typeLabel || type || "检查室待分拣",
          department: roleToDepartment.inspection
        }
      : {
          fieldKey: "documentScope",
          fieldLabel: typeLabel || type || "资料",
          department: fallbackDepartment
        });
  const classifyStatus = meta.fieldKey === "inspectionPending" ? "pending" : "manual";
  return { ...meta, department: meta.department || fallbackDepartment, classifyStatus: classifyStatus as "pending" | "manual" };
};

const stripHtml = (value: string) =>
  value
    .replace(/<style[\s\S]*?<\/style>/gi, "\n")
    .replace(/<script[\s\S]*?<\/script>/gi, "\n")
    .replace(/<[^>]+>/g, "\n")
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/\u0000/g, "");

const decodeLegacyContent = (contentDataUrl?: string) => {
  const raw = String(contentDataUrl || "");
  if (!raw.startsWith("data:")) return "";
  const commaIndex = raw.indexOf(",");
  if (commaIndex < 0) return "";
  const meta = raw.slice(0, commaIndex);
  const body = raw.slice(commaIndex + 1);
  try {
    if (meta.includes(";base64")) {
      const binary = atob(body);
      const bytes = Uint8Array.from(binary, char => char.charCodeAt(0));
      return stripHtml(new TextDecoder("utf-8", { fatal: false }).decode(bytes));
    }
    return stripHtml(decodeURIComponent(body));
  } catch {
    return "";
  }
};

const normalizeLegacyText = (value: string) =>
  value
    .replace(/\r/g, "\n")
    .replace(/[ \t]+/g, " ")
    .replace(/\n{2,}/g, "\n")
    .trim();

const pickLegacySection = (text: string, headings: string[], stopHeadings: string[]) => {
  const normalized = normalizeLegacyText(text);
  if (!normalized) return "";
  for (const heading of headings) {
    const pattern = new RegExp(`${heading}[：:：\\s]*([\\s\\S]{1,1200}?)(?=\\n?(?:${stopHeadings.join("|")})[：:：\\s]|$)`, "i");
    const matched = normalized.match(pattern)?.[1]?.trim();
    if (matched) return matched.replace(/\n+/g, "；").slice(0, 800);
  }
  return "";
};

const legacyStopHeadings = [
  "主诉",
  "现病史",
  "既往史",
  "过敏史",
  "手术史",
  "专科检查",
  "专科查体",
  "查体",
  "诊断",
  "西医诊断",
  "中医诊断",
  "中医辨证",
  "治疗",
  "处置",
  "医嘱",
  "复查",
  "建议",
  "备注"
];

const fieldMetaByKey = () => {
  const result = new Map<string, { field: RecordField; sectionTitle: string }>();
  recordSections.forEach(section =>
    section.fields.forEach(field => result.set(field.key, { field, sectionTitle: section.title }))
  );
  return result;
};

const appendFieldMapping = (
  rows: LegacyFieldMapping[],
  params: {
    fieldKey: string;
    importValue: string;
    sourceFile: string;
    currentRecord: Record<string, string>;
    confidence: LegacyFieldMapping["confidence"];
    reason: string;
  }
) => {
  const value = params.importValue.trim();
  if (!value) return;
  const meta = fieldMetaByKey().get(params.fieldKey);
  if (!meta) return;
  const currentValue = String(params.currentRecord[params.fieldKey] || "").trim();
  rows.push({
    id: `field-${params.fieldKey}-${rows.length}`,
    fieldKey: params.fieldKey,
    fieldLabel: meta.field.label,
    sectionTitle: meta.sectionTitle,
    currentValue,
    importValue: value,
    sourceFile: params.sourceFile,
    confidence: params.confidence,
    reason: params.reason,
    conflict: Boolean(currentValue),
    selected: !currentValue
  });
};

const buildLegacyTextMappings = (files: Array<string | SharedCaseFileItem>, currentRecord: Record<string, string>) => {
  const mappings: LegacyFieldMapping[] = [];
  files.forEach(fileItem => {
    if (typeof fileItem === "string") return;
    if (!/\.(docx?|html?)$/i.test(fileItem.fileName)) return;
    const text = decodeLegacyContent(fileItem.contentDataUrl);
    if (!text) return;
    appendFieldMapping(mappings, {
      fieldKey: "chiefComplaintText",
      importValue: pickLegacySection(text, ["主诉"], legacyStopHeadings),
      sourceFile: fileItem.fileName,
      currentRecord,
      confidence: "中",
      reason: "从旧门诊病历“主诉”段落识别"
    });
    appendFieldMapping(mappings, {
      fieldKey: "presentIllnessText",
      importValue: pickLegacySection(text, ["现病史"], legacyStopHeadings),
      sourceFile: fileItem.fileName,
      currentRecord,
      confidence: "中",
      reason: "从旧门诊病历“现病史”段落识别"
    });
    appendFieldMapping(mappings, {
      fieldKey: "operationHistory",
      importValue: pickLegacySection(text, ["既往史", "手术史"], legacyStopHeadings),
      sourceFile: fileItem.fileName,
      currentRecord,
      confidence: "低",
      reason: "从旧病历既往/手术相关段落识别，需人工确认"
    });
    appendFieldMapping(mappings, {
      fieldKey: "allergyHistory",
      importValue: pickLegacySection(text, ["过敏史"], legacyStopHeadings),
      sourceFile: fileItem.fileName,
      currentRecord,
      confidence: "中",
      reason: "从旧病历过敏史段落识别"
    });
    appendFieldMapping(mappings, {
      fieldKey: "lithotomyExam",
      importValue: pickLegacySection(text, ["专科检查", "专科查体", "查体"], legacyStopHeadings),
      sourceFile: fileItem.fileName,
      currentRecord,
      confidence: "低",
      reason: "从旧病历专科检查段落识别，可能需要拆分到肛门视诊/指诊/肛门镜"
    });
    appendFieldMapping(mappings, {
      fieldKey: "westernDiagnosis",
      importValue: pickLegacySection(text, ["西医诊断", "诊断"], legacyStopHeadings),
      sourceFile: fileItem.fileName,
      currentRecord,
      confidence: "低",
      reason: "从诊断段落识别，需确认是否为西医诊断"
    });
    appendFieldMapping(mappings, {
      fieldKey: "tcmDiagnosis",
      importValue: pickLegacySection(text, ["中医诊断"], legacyStopHeadings),
      sourceFile: fileItem.fileName,
      currentRecord,
      confidence: "中",
      reason: "从旧病历中医诊断段落识别"
    });
    appendFieldMapping(mappings, {
      fieldKey: "tcmSyndrome",
      importValue: pickLegacySection(text, ["中医辨证", "辨证"], legacyStopHeadings),
      sourceFile: fileItem.fileName,
      currentRecord,
      confidence: "中",
      reason: "从旧病历中医辨证段落识别"
    });
    appendFieldMapping(mappings, {
      fieldKey: "sameDayTreatment",
      importValue: pickLegacySection(text, ["治疗", "处置", "医嘱"], legacyStopHeadings),
      sourceFile: fileItem.fileName,
      currentRecord,
      confidence: "低",
      reason: "从治疗/处置/医嘱段落识别，需人工确认"
    });
  });
  return mappings;
};

const buildLegacyAttachmentMappings = (params: SharedCasePreviewParams) => {
  const mappings: LegacyAttachmentMapping[] = [];
  const unassigned: string[] = [];
  params.files.forEach((fileItem, index) => {
    const fileName = typeof fileItem === "string" ? fileItem : fileItem.fileName;
    const inferred =
      typeof fileItem === "string" || !fileItem.type
        ? inferDocumentType(fileName)
        : documentTypeMeta(fileItem.type, fileItem.typeLabel || fileItem.type, params.ownerDepartment);
    if (!inferred) {
      unassigned.push(fileName);
      return;
    }
    const source =
      /复查|照片/.test(fileName) || inferred.fieldKey === "followupRecordsJson"
        ? "followup"
        : /\.(docx?|html?)$/i.test(fileName)
          ? "document"
          : "report";
    mappings.push({
      id: `attachment-${index}`,
      fileName,
      type: typeof fileItem === "string" ? "" : fileItem.type || "",
      typeLabel: inferred.fieldLabel,
      fieldKey: inferred.fieldKey,
      fieldLabel: inferred.fieldLabel,
      department: inferred.department,
      source,
      selected: true
    });
  });
  return { mappings, unassigned };
};

const ensureLegacyPatient = (db: ClinicDb, params: SharedCasePreviewParams) => {
  let patient = findPatientByVisitNo(db, params.visitNo);
  const createdAt = now();
  if (!patient) {
    patient = findPatientByIdentity(db, params.patientName, undefined, true);
    if (patient) {
      appendPatientEncounter(patient, {
        visitNo: params.visitNo,
        visitDate: params.visitDate,
        visitType: params.visitType,
        doctor: "待分配",
        createdAt
      });
    } else {
      patient = {
        id: String(Date.now()),
        name: params.patientName,
        visitNo: params.visitNo,
        visitDate: params.visitDate,
        visitType: params.visitType,
        doctor: "待分配",
        encounterCount: 1,
        encounterHistory: [
          {
            id: `enc-${Date.now()}-initial`,
            visitNo: params.visitNo,
            visitDate: params.visitDate,
            visitType: params.visitType,
            doctor: "待分配",
            createdAt
          }
        ],
        currentStage: "旧资料预检",
        completedCount: 2,
        progressPercent: Math.round((2 / recordSections.length) * 100),
        status: "已识别待确认",
        riskType: "warning",
        createdAt,
        updatedAt: createdAt
      };
      db.patients.unshift(patient);
    }
  }
  db.records[patient.id] = {
    ...(db.records[patient.id] ?? initialFieldValues()),
    patientName: params.patientName,
    visitNo: params.visitNo,
    admissionDate: params.visitDate,
    admissionWay: params.visitType
  };
  db.archive[patient.id] = db.archive[patient.id] ?? { submitted: false, version: "V0.1-旧资料导入", generatedAt: createdAt };
  patient.currentStage = "旧资料预检";
  patient.status = "已识别待确认";
  patient.riskType = "warning";
  patient.updatedAt = createdAt;
  return patient;
};

export const uploadDocumentsApi = async (params: UploadDocumentsParams) => {
  if (!params.documents.length) return Promise.reject(new Error("请至少上传一份资料"));

  const db = await readDb();
  const patient = getPatientOrThrow(db, params.patientId);
  const uploadedAt = now();
  const uploaded: RecordAttachment[] = [];
  const sourceRole = params.sourceRole || params.role;
  const batchId = params.batchId || `batch-${patient.id}-${Date.now()}`;
  for (const [index, document] of params.documents.entries()) {
    const meta = documentTypeMeta(document.type, document.typeLabel, sourceRole, document.fileName, params.autoClassify);
    const storedFile = await persistDocumentFile({
      ...document,
      patientId: patient.id,
      department: meta.department,
      operator: params.operator,
      operatorRole: params.role
    });
    uploaded.push({
      key: `upload-${patient.id}-${Date.now()}-${index}`,
      title: document.typeLabel || document.fileName,
      department: meta.department,
      fieldKey: meta.fieldKey,
      fieldLabel: meta.fieldLabel,
      fileName: document.fileName,
      url: storedFile.url,
      storagePath: storedFile.storagePath,
      uploadedAt,
      uploader: roleLabel(params.role),
      uploaderRole: params.role,
      sourceRole,
      batchId,
      batchName: params.batchName,
      classifyStatus: meta.classifyStatus,
      remark: document.remark || params.remark,
      status: "active"
    });
  }

  db.documents ??= {};
  db.documents[patient.id] = [...(db.documents[patient.id] ?? []), ...uploaded];
  patient.currentStage = patient.currentStage || "资料上传";
  patient.status = "资料已上传";
  patient.riskType = "info";
  patient.updatedAt = uploadedAt;
  appendAuditLog(db, {
    operator: params.operator || roleLabel(params.role),
    role: roleLabel(params.role),
    patient: patient.name,
    patientId: patient.id,
    module: "document",
    actionCode: "document.upload",
    targetType: "document",
    targetKey: patient.id,
    targetLabel: String(uploaded.length),
    afterValue: uploaded.map(item => item.fileName).join(", "),
    action: "上传资料",
    detail: `上传 ${uploaded.length} 份资料：${uploaded.map(item => item.fileName).join("、")}`
  });
  await writeDb(db);
  return response<UploadDocumentsResult>({ patient, documents: uploaded }, "资料已上传并入档");
};

export const previewSharedCaseImportApi = async (params: SharedCasePreviewParams) => {
  const db = await readDb();
  const patientByVisitNo = findPatientByVisitNo(db, params.visitNo);
  const patientByName = patientByVisitNo ? undefined : findPatientByIdentity(db, params.patientName, undefined, true);
  const patient = patientByVisitNo || patientByName;
  const currentRecord = patient ? (db.records[patient.id] ?? initialFieldValues()) : initialFieldValues();
  const fieldMappings = buildLegacyTextMappings(params.files, currentRecord);
  const { mappings: attachmentMappings, unassigned } = buildLegacyAttachmentMappings(params);
  const preview: SharedCasePreviewResult = {
    previewId: `legacy-preview-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    patientMatch: patientByVisitNo ? "matchedByVisitNo" : patientByName ? "matchedByName" : "newPatient",
    patient,
    patientName: params.patientName,
    visitNo: params.visitNo,
    visitDate: params.visitDate,
    visitType: params.visitType,
    fieldMappings,
    attachmentMappings,
    unassigned,
    status: unassigned.length ? "部分待分拣" : "已识别待确认"
  };
  return response<SharedCasePreviewResult>(preview, "旧共享病历已完成预检，请确认采纳项");
};

export const commitSharedCaseImportApi = async (params: SharedCaseCommitParams) => {
  const db = await readDb();
  const patient = ensureLegacyPatient(db, params);
  const record = db.records[patient.id] ?? initialFieldValues();
  const acceptedFieldIds = new Set(params.acceptedFieldMappingIds);
  const acceptedAttachmentIds = new Set(params.acceptedAttachmentMappingIds);
  const appliedFields: LegacyFieldMapping[] = [];
  const skippedFields: LegacyFieldMapping[] = [];
  params.preview.fieldMappings.forEach(mapping => {
    if (!acceptedFieldIds.has(mapping.id)) {
      skippedFields.push(mapping);
      return;
    }
    const currentValue = String(record[mapping.fieldKey] || "").trim();
    if (currentValue && !params.overwriteConflicts) {
      skippedFields.push(mapping);
      return;
    }
    record[mapping.fieldKey] = mapping.importValue;
    appliedFields.push(mapping);
  });

  const imported: RecordAttachment[] = [];
  const unassigned = [...params.preview.unassigned];
  for (const [index, fileItem] of params.files.entries()) {
    const fileName = typeof fileItem === "string" ? fileItem : fileItem.fileName;
    const mapping = params.preview.attachmentMappings.find(item => item.fileName === fileName);
    if (!mapping || !acceptedAttachmentIds.has(mapping.id)) {
      continue;
    }
    const storedFile =
      typeof fileItem === "string"
        ? { url: "", storagePath: undefined }
        : await persistDocumentFile({
            ...fileItem,
            patientId: patient!.id,
            department: mapping.department,
            operator: params.operator,
            operatorRole: params.role || params.ownerDepartment
          });
    imported.push({
      key: `legacy-${patient!.id}-${Date.now()}-${index}`,
      title: `旧共享资料-${mapping.fieldLabel}`,
      department: mapping.department,
      fieldKey: mapping.fieldKey,
      fieldLabel: mapping.fieldLabel,
      fileName,
      url: storedFile.url,
      storagePath: storedFile.storagePath,
      uploadedAt: now(),
      uploader: params.ownerDepartment,
      status: "active"
    });
  }

  db.documents ??= {};
  db.documents[patient.id] = [...(db.documents[patient.id] ?? []), ...imported];
  const followupAttachments = imported.filter(document => document.fieldKey === "followupRecordsJson");
  if (followupAttachments.length) {
    const previousFollowups = parseFollowupTimelineRecords(record.followupRecordsJson);
    const createdFollowups = followupAttachments.map((document, index) => ({
      id: `legacy-followup-${Date.now()}-${index}`,
      type: "远期复查",
      date: params.visitDate,
      recovery: "旧共享资料导入，需人工补充恢复情况",
      abnormal: "待确认",
      advice: "请结合附件照片回查并补充随访结论",
      nextDate: "",
      onTime: "旧资料补录",
      sourceFile: document.fileName
    }));
    record.followupRecordsJson = JSON.stringify([...previousFollowups, ...createdFollowups]);
  }
  if (imported.some(document => document.fieldKey === "bloodRoutine")) {
    record.bloodRoutineStatus = record.bloodRoutineStatus || "已查";
  }
  if (imported.some(document => document.fieldKey === "ecgResult")) record.ecgStatus = record.ecgStatus || "已查";
  db.records[patient.id] = record;

  patient.completedCount = Math.max(patient.completedCount || 1, 3);
  patient.progressPercent = Math.round((patient.completedCount / recordSections.length) * 100);
  patient.status = unassigned.length ? "部分待分拣" : "已采纳入档";
  patient.currentStage = unassigned.length ? "资料分拣" : "档案生成";
  patient.updatedAt = now();
  appendAuditLog(db, {
    operator: params.operator || params.ownerDepartment,
    role: roleLabel(params.role) || params.ownerDepartment,
    patient: patient.name,
    patientId: patient.id,
    module: "legacy",
    actionCode: "legacy.import.commit",
    targetType: "document",
    targetKey: patient.id,
    targetLabel: params.folderName,
    afterValue: `fields:${appliedFields.length};documents:${imported.length};unassigned:${unassigned.length}`,
    action: "采纳旧共享病历预检",
    detail: `采纳字段 ${appliedFields.length} 项，导入附件 ${imported.length} 个，${unassigned.length} 个待分拣文件`
  });
  await writeDb(db);
  return response<SharedCaseImportResult>(
    {
      patient,
      documents: imported,
      unassigned,
      appliedFields,
      skippedFields,
      preview: { ...params.preview, status: unassigned.length ? "部分待分拣" : "已采纳入档" }
    },
    "旧共享病历已采纳入档"
  );
};

export const importSharedCaseApi = async (params: SharedCaseImportParams) => {
  const { data: preview } = await previewSharedCaseImportApi(params);
  return commitSharedCaseImportApi({
    ...params,
    preview,
    acceptedFieldMappingIds: preview.fieldMappings.filter(item => item.selected).map(item => item.id),
    acceptedAttachmentMappingIds: preview.attachmentMappings.filter(item => item.selected).map(item => item.id),
    overwriteConflicts: false
  });
};

export const voidDocumentApi = async (params: DocumentVoidParams) => {
  const documentVoidRoles: UserRole[] = ["lab", "ecg", "ultrasound", "inspection", ...serviceCollaborators];
  if (!documentVoidRoles.includes(params.role as UserRole)) {
    return Promise.reject(new Error(`${roleLabel(params.role)}无权作废附件`));
  }
  const reason = params.reason.trim();
  if (!reason) return Promise.reject(new Error("请填写作废原因"));

  const db = await readDb();
  const patient = getPatientOrThrow(db, params.patientId);
  const documents = db.documents?.[patient.id] ?? [];
  const target = documents.find(item => item.key === params.documentKey);
  if (!target) return Promise.reject(new Error("附件不存在"));
  if (target.status === "voided") return Promise.reject(new Error("附件已作废"));

  const operatedAt = now();
  target.status = "voided";
  target.voidReason = reason;
  target.voidedAt = operatedAt;
  target.voidedBy = params.operator || roleLabel(params.role);
  patient.status = "资料待核对";
  patient.currentStage = "附件纠错";
  patient.riskType = "warning";
  patient.updatedAt = operatedAt;
  appendAuditLog(db, {
    operator: params.operator || roleLabel(params.role),
    role: roleLabel(params.role),
    patient: patient.name,
    patientId: patient.id,
    module: "document",
    actionCode: "document.void",
    targetType: "document",
    targetKey: target.key,
    targetLabel: target.fileName,
    beforeValue: "active",
    afterValue: "voided",
    action: "作废资料",
    detail: `${target.fileName} 已作废，原因：${reason}`
  });
  await writeDb(db);
  return response({ patient, document: target }, "资料已作废并移入回收站");
};

export const restoreDocumentApi = async (params: DocumentRestoreParams) => {
  if (!["admin", "quality"].includes(params.role)) {
    return Promise.reject(new Error(`${roleLabel(params.role)}无权恢复附件`));
  }

  const db = await readDb();
  const patient = getPatientOrThrow(db, params.patientId);
  const documents = db.documents?.[patient.id] ?? [];
  const target = documents.find(item => item.key === params.documentKey);
  if (!target) return Promise.reject(new Error("附件不存在"));
  if (target.status !== "voided") return Promise.reject(new Error("附件未作废，无需恢复"));

  const operatedAt = now();
  target.status = "active";
  target.restoredAt = operatedAt;
  target.restoredBy = params.operator || roleLabel(params.role);
  const remainingIssues = buildQualityIssues(db.records[patient.id] ?? initialFieldValues(), documents, db.templateFieldRules);
  patient.status = remainingIssues.some(item => item.level === "critical") ? "待补充资料" : "可提交档案审核";
  patient.currentStage = remainingIssues.some(item => item.level === "critical") ? "档案补全" : "档案审核待审";
  patient.riskType = remainingIssues.some(item => item.level === "critical") ? "warning" : "success";
  patient.updatedAt = operatedAt;
  appendAuditLog(db, {
    operator: params.operator || roleLabel(params.role),
    role: roleLabel(params.role),
    patient: patient.name,
    patientId: patient.id,
    module: "document",
    actionCode: "document.restore",
    targetType: "document",
    targetKey: target.key,
    targetLabel: target.fileName,
    beforeValue: "voided",
    afterValue: "active",
    action: "恢复资料",
    detail: `${target.fileName} 已从回收站恢复`
  });
  await writeDb(db);
  return response({ patient, document: target }, "资料已恢复");
};

export const getRecycleDocumentListApi = async (params: {
  pageNum: number;
  pageSize: number;
  patient?: string;
  fileName?: string;
}) => {
  const db = await readDb();
  const rows = db.patients.flatMap(patient =>
    (db.documents?.[patient.id] ?? [])
      .filter(document => document.status === "voided")
      .map<RecycleDocumentRow>(document => ({
        id: document.key,
        patientId: patient.id,
        patient: patient.name,
        visitNo: patient.visitNo,
        fileName: document.fileName,
        fieldLabel: document.fieldLabel,
        department: document.department,
        reason: document.voidReason || "未填写",
        operator: document.voidedBy || document.uploader,
        time: document.voidedAt || document.uploadedAt
      }))
  );
  const filtered = rows.filter(item => {
    const patientMatched = !params.patient || item.patient.includes(params.patient) || item.visitNo.includes(params.patient);
    const fileMatched = !params.fileName || item.fileName.includes(params.fileName);
    return patientMatched && fileMatched;
  });
  return response(paginate(filtered, params.pageNum, params.pageSize));
};

export const getQualityReviewListApi = async (params: {
  pageNum: number;
  pageSize: number;
  keyword?: string;
  status?: string;
}) => {
  const db = await readDb();
  const reviewStatuses = ["待档案审核", "退回整改", "可提交档案审核", "资料待核对"];
  const filtered = db.patients
    .filter(patient => (params.status ? patient.status === params.status : reviewStatuses.includes(patient.status)))
    .map(patient => {
      const detail = buildQualityReviewDetail(db, patient);
      return {
        id: patient.id,
        name: patient.name,
        visitNo: patient.visitNo,
        visitDate: patient.visitDate,
        doctor: patient.doctor,
        currentStage: patient.currentStage,
        status: patient.status,
        score: detail.score,
        issueCount: detail.issues.length,
        criticalCount: detail.criticalCount,
        warningCount: detail.warningCount,
        attachmentCount: detail.attachments.length,
        updatedAt: patient.updatedAt,
        riskType: patient.riskType
      } as QualityReviewRow;
    })
    .filter(row => !params.keyword || `${row.name}${row.visitNo}${row.doctor}${row.status}`.includes(params.keyword))
    .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt));
  return response(paginate(filtered, params.pageNum, params.pageSize));
};

export const getQualityReviewDetailApi = async (id: string) => {
  const db = await readDb();
  return response(buildQualityReviewDetail(db, getPatientOrThrow(db, id)));
};

export const rejectQualityReviewApi = async (params: QualityReviewActionParams) => {
  ensureQualityManager(params.role);
  const db = await readDb();
  const patient = getPatientOrThrow(db, params.id);
  const detail = buildQualityReviewDetail(db, patient);
  const operatedAt = now();
  const comment = params.comment?.trim() || "档案审核退回，请按问题清单补正后重新提交。";
  db.archive[patient.id] = { submitted: false, version: "V0.9-退回整改", generatedAt: operatedAt };
  patient.currentStage = "退回整改";
  patient.status = "退回整改";
  patient.riskType = "danger";
  patient.updatedAt = operatedAt;
  appendAuditLog(db, {
    operator: params.operator || roleLabel(params.role),
    role: roleLabel(params.role),
    patient: patient.name,
    patientId: patient.id,
    module: "archive",
    actionCode: "quality.reject",
    targetType: "qualityReview",
    targetKey: patient.id,
    targetLabel: patient.visitNo,
    beforeValue: `${detail.criticalCount} 个严重问题`,
    afterValue: comment,
    action: "档案审核退回",
    detail: `${comment}；问题数：${detail.issues.length}`
  });
  await writeDb(db);
  return response({ patient, archive: db.archive[patient.id] }, "已退回整改");
};

export const approveQualityReviewApi = async (params: QualityReviewActionParams) => {
  ensureQualityManager(params.role);
  const db = await readDb();
  const patient = getPatientOrThrow(db, params.id);
  const detail = buildQualityReviewDetail(db, patient);
  if (detail.criticalCount) {
    return Promise.reject(new Error(`仍有 ${detail.criticalCount} 个严重问题，不能通过归档`));
  }

  const operatedAt = now();
  const comment = params.comment?.trim() || "档案审核通过，正式归档。";
  db.archive[patient.id] = { submitted: true, version: "V1.0-已归档", generatedAt: operatedAt };
  patient.currentStage = "归档";
  patient.completedCount = recordSections.length;
  patient.progressPercent = 100;
  patient.status = "已归档";
  patient.riskType = "success";
  patient.updatedAt = operatedAt;
  appendAuditLog(db, {
    operator: params.operator || roleLabel(params.role),
    role: roleLabel(params.role),
    patient: patient.name,
    patientId: patient.id,
    module: "archive",
    actionCode: "quality.approve",
    targetType: "qualityReview",
    targetKey: patient.id,
    targetLabel: patient.visitNo,
    beforeValue: detail.archiveVersion,
    afterValue: db.archive[patient.id].version,
    action: "档案审核通过",
    detail: comment
  });
  await writeDb(db);
  return response({ patient, archive: db.archive[patient.id] }, "档案审核已通过并归档");
};

export const getOperationStatsApi = async () => {
  const db = await readDb();
  const documents = Object.values(db.documents ?? {}).flat();
  const archived = db.patients.filter(patient => patient.status === "已归档" || patient.status === "旧资料已归档");
  const returned = db.patients.filter(patient => patient.status === "退回整改");
  const reviewed = archived.length + returned.length;
  const overdue = db.patients.filter(patient => {
    if (["已归档", "旧资料已归档"].includes(patient.status)) return false;
    return Date.now() - parseTime(patient.updatedAt) > 24 * 36e5;
  });
  const averageArchiveHours =
    archived.length === 0
      ? 0
      : Math.round(
          archived.reduce((sum, patient) => sum + (parseTime(patient.updatedAt) - parseTime(patient.createdAt)) / 36e5, 0) /
            archived.length
        );
  const stageBuckets = db.patients.reduce<Array<{ stage: string; count: number }>>((list, patient) => {
    const target = list.find(item => item.stage === patient.currentStage);
    if (target) target.count += 1;
    else list.push({ stage: patient.currentStage, count: 1 });
    return list;
  }, []);
  const departmentNames = Array.from(
    new Set([
      ...(db.departments ?? []).map(department => department.name),
      ...(db.accounts ?? []).map(account => account.department),
      ...documents.map(document => document.department)
    ])
  ).filter(Boolean);
  const departmentWorkloads = departmentNames.map(department => ({
    department,
    active: 0,
    voided: 0,
    total: 0
  }));
  documents.forEach(document => {
    const target =
      departmentWorkloads.find(item => item.department === document.department) ||
      (() => {
        const workload = {
          department: document.department,
          active: 0,
          voided: 0,
          total: 0
        };
        departmentWorkloads.push(workload);
        return workload;
      })();
    if (document.status === "voided") target.voided += 1;
    else target.active += 1;
    target.total += 1;
  });
  return response<OperationStats>({
    totalPatients: db.patients.length,
    pendingPatients: db.patients.filter(patient => !["已归档", "旧资料已归档"].includes(patient.status)).length,
    reviewPatients: db.patients.filter(patient => patient.status === "待档案审核" || patient.status === "待质控审核").length,
    returnedPatients: returned.length,
    archivedPatients: archived.length,
    overduePatients: overdue.length,
    documentCount: documents.filter(document => document.status !== "voided").length,
    voidedDocumentCount: documents.filter(document => document.status === "voided").length,
    qualityPassRate: reviewed ? Math.round((archived.length / reviewed) * 100) : 0,
    averageArchiveHours,
    stageBuckets,
    departmentWorkloads: departmentWorkloads.sort((left, right) => right.total - left.total)
  });
};

export const getAuditLogListApi = async (params: {
  pageNum: number;
  pageSize: number;
  operator?: string;
  patient?: string;
  patientId?: string;
  action?: string;
  module?: string;
  result?: string;
}) => {
  const db = await readDb();
  const filtered = (db.auditLogs ?? []).filter(item => {
    const operatorMatched = !params.operator || item.operator.includes(params.operator);
    const patientMatched = !params.patient || item.patient.includes(params.patient);
    const patientIdMatched = !params.patientId || item.patientId === params.patientId;
    const actionMatched = !params.action || item.action.includes(params.action);
    const moduleMatched = !params.module || item.module === params.module;
    const resultMatched = !params.result || item.result === params.result;
    return operatorMatched && patientMatched && patientIdMatched && actionMatched && moduleMatched && resultMatched;
  });
  return response(paginate(filtered, params.pageNum, params.pageSize));
};

const normalizeTimelineTime = (value?: string) => {
  const normalized = String(value || "").trim();
  if (!normalized) return "0000-00-00 00:00:00";
  return normalized.length === 10 ? `${normalized} 00:00:00` : normalized;
};

const parseFollowupTimelineRecords = (value?: string) => {
  try {
    const parsed = JSON.parse(String(value || "[]"));
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
};

const buildLocalPatientTimeline = (db: ClinicDb, patientId: string): PatientTimelineEvent[] => {
  const patient = getPatientOrThrow(db, patientId);
  const archive = db.archive[patientId];
  const record = db.records[patientId] ?? {};
  const attachments = activeDocuments(db.documents?.[patientId] ?? []);
  const logs = (db.auditLogs ?? []).filter(log => log.patientId === patientId);
  const events: PatientTimelineEvent[] = [];

  events.push({
    id: `patient-created-${patient.id}`,
    time: normalizeTimelineTime(patient.createdAt),
    source: "patient",
    module: "patient",
    sourceId: patient.id,
    title: "创建患者档案",
    detail: "登记患者基础就诊信息",
    level: "success",
    operator: patient.doctor,
    targetLabel: patient.visitNo
  });

  (patient.encounterHistory || []).forEach(encounter => {
    events.push({
      id: `encounter-${encounter.id}`,
      time: normalizeTimelineTime(encounter.createdAt || encounter.visitDate),
      source: "encounter",
      module: "patient",
      sourceId: encounter.id,
      title: "就诊记录",
      detail: `${encounter.visitType}：${encounter.visitNo}`,
      level: "primary",
      operator: encounter.doctor,
      targetLabel: encounter.visitDate
    });
  });

  if (archive?.version) {
    events.push({
      id: `archive-${patientId}-${archive.version}`,
      time: normalizeTimelineTime(archive.generatedAt),
      source: "archive",
      module: "archive",
      sourceId: patientId,
      title: archive.submitted ? "提交档案审核" : "档案版本记录",
      detail: `版本：${archive.version}`,
      level: archive.submitted ? "warning" : "info",
      targetLabel: archive.version
    });
  }

  parseFollowupTimelineRecords(record.followupRecordsJson).forEach((followup, index) => {
    events.push({
      id: `followup-${followup.id || index}`,
      time: normalizeTimelineTime(followup.date || followup.nextDate),
      source: "followup",
      module: "field",
      sourceId: "followupRecordsJson",
      title: followup.type || "复查随访",
      detail: `恢复：${followup.recovery || "待记录"}；异常：${followup.abnormal || "无"}；建议：${followup.advice || "待记录"}`,
      level: "success",
      targetLabel: followup.onTime || ""
    });
  });

  attachments.forEach(attachment => {
    events.push({
      id: `document-upload-${attachment.key}`,
      time: normalizeTimelineTime(attachment.uploadedAt),
      source: "document",
      module: "document",
      sourceId: attachment.key,
      title: "新增检查检验附件",
      detail: `${attachment.fileName}，关联：${attachment.fieldLabel}，科室：${attachment.department}`,
      level: "primary",
      operator: attachment.uploader,
      targetLabel: attachment.fieldLabel
    });
  });

  logs.forEach(log => {
    const isCollaborative = log.actionCode === "record.field.collaborative.update" || log.module === "collaboration";
    events.push({
      id: log.id,
      time: normalizeTimelineTime(log.time),
      source: isCollaborative ? "collaboration" : "audit",
      module: log.module || (isCollaborative ? "collaboration" : "audit"),
      sourceId: log.targetKey || "",
      title: isCollaborative ? log.action || "协作补充档案字段" : log.action || "操作记录",
      detail: isCollaborative
        ? `${log.role || "未知岗位"} ${log.operator || "未知人员"}：${log.detail || log.targetLabel || "更新档案字段"}`
        : log.detail,
      level: log.result === "denied" ? "danger" : isCollaborative ? "success" : "info",
      operator: log.operator,
      targetLabel: log.targetLabel
    });
  });

  const seen = new Set<string>();
  return events
    .filter(event => {
      const key = `${event.source}:${event.id}:${event.time}:${event.title}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .sort((left, right) => right.time.localeCompare(left.time));
};

export const getPatientTimelineApi = async (patientId: string) => {
  try {
    const result = await fetch(`${getClinicApiBaseUrl()}/patients/timeline?patientId=${encodeURIComponent(patientId)}`, {
      headers: authHeaders()
    });
    const data = await parseClinicApiResponse<PatientTimelineEvent[]>(result);
    return response(data);
  } catch {
    const db = await readDb();
    return response(buildLocalPatientTimeline(db, patientId));
  }
};

const parseClinicApiResponse = async <T>(result: Response): Promise<T> => {
  if (result.status === 401) {
    handleUnauthorizedResponse();
  }
  const text = await result.text();
  if (text.trim().startsWith("<")) {
    throw new Error("业务接口未连通，请确认后端已启动，并检查 /clinic-api 代理或部署转发配置");
  }
  let payload: ResultData<T>;
  try {
    payload = JSON.parse(text) as ResultData<T>;
  } catch {
    throw new Error("业务接口返回格式异常，请检查后端服务状态");
  }
  if (isUnauthorizedApiResponse(result, payload)) {
    handleUnauthorizedResponse(payload.msg || "\u767b\u5f55\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55");
  }
  if (!result.ok || String(payload.code) !== "200") {
    throw new Error(payload.msg || `clinic api failed: ${result.status}`);
  }
  return payload.data;
};

export const getMaintenanceStatusApi = async () => {
  const result = await fetch(`${getClinicApiBaseUrl()}/maintenance/status`, { headers: authHeaders() });
  const data = await parseClinicApiResponse<MaintenanceStatus>(result);
  return response(data);
};

export const createMaintenanceSnapshotApi = async () => {
  const result = await fetch(`${getClinicApiBaseUrl()}/maintenance/snapshot`, { method: "POST", headers: authHeaders() });
  const data = await parseClinicApiResponse<{ savedAt: string; snapshotCount: number; revision: string }>(result);
  return response(data, "系统快照已生成");
};

export const getBackupStatusApi = async () => {
  const result = await fetch(`${getClinicApiBaseUrl()}/maintenance/backup/status`, { headers: authHeaders() });
  const data = await parseClinicApiResponse<BackupStatus>(result);
  return response(data);
};

export const saveBackupConfigApi = async (payload: BackupConfigPayload) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/maintenance/backup/config`, {
    method: "PUT",
    headers: {
      ...authHeaders(),
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<BackupStatus>(result);
  return response(data, "备份配置已保存");
};

export const chooseBackupDirectoryApi = async (initialDir = "") => {
  const result = await fetch(`${getClinicApiBaseUrl()}/maintenance/backup/choose-dir`, {
    method: "POST",
    headers: {
      ...authHeaders(),
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ initialDir })
  });
  const data = await parseClinicApiResponse<BackupDirectorySelection>(result);
  return response(data, "备份目录已选择");
};

export const runBackupNowApi = async () => {
  const result = await fetch(`${getClinicApiBaseUrl()}/maintenance/backup/run`, { method: "POST", headers: authHeaders() });
  const data = await parseClinicApiResponse<BackupRunResult>(result);
  return response(data, "备份已完成");
};

export const getDuplicatePatientGroupsApi = async () => {
  const result = await fetch(`${getClinicApiBaseUrl()}/patients/duplicates`, { headers: authHeaders() });
  const data = await parseClinicApiResponse<DuplicatePatientGroup[]>(result);
  return response(data);
};

export const generateRecordAiSummaryApi = async (params: AiRecordSummaryParams) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/record-summary`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(params)
  });
  const data = await parseClinicApiResponse<AiRecordSummary>(result);
  return response(data, "AI总结已生成");
};

export const getMedicalRecordTemplateApi = async () => {
  const result = await fetch(`${getClinicApiBaseUrl()}/medical-record/templates`, { headers: authHeaders() });
  const data = await parseClinicApiResponse<MedicalRecordTemplateStatus>(result);
  return response(data);
};

export const getGeneratedMedicalRecordVersionsApi = async (patientId: string) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/medical-record/versions?patientId=${encodeURIComponent(patientId)}`, {
    headers: authHeaders()
  });
  const data = await parseClinicApiResponse<{ versions: GeneratedMedicalRecord[] }>(result);
  return response(data.versions ?? []);
};

export const precheckMedicalRecordApi = async (patientId: string) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/medical-record/precheck`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify({ patientId, mode: "target" })
  });
  const data = await parseClinicApiResponse<MedicalRecordPrecheckResult>(result);
  return response(data);
};

export const saveMedicalRecordWorkspaceApi = async (patientId: string, values: Record<string, string>) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/medical-record/workspace`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify({ patientId, values })
  });
  const data = await parseClinicApiResponse<MedicalRecordWorkspaceSaveResult>(result);
  return response(data, "目标病历填写已保存");
};

export const generateMedicalRecordApi = async (patientId: string) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/medical-record/generate`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify({ patientId, mode: "target" })
  });
  const data = await parseClinicApiResponse<MedicalRecordGenerateResult>(result);
  return response(data, "目标病历已生成");
};

export const finalizeMedicalRecordApi = async (id: string) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/medical-record/finalize`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify({ id })
  });
  const data = await parseClinicApiResponse<{ record: GeneratedMedicalRecord }>(result);
  return response(data.record, "目标病历已定稿");
};

export const voidMedicalRecordApi = async (id: string, reason: string) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/medical-record/void`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify({ id, reason })
  });
  const data = await parseClinicApiResponse<{ record: GeneratedMedicalRecord }>(result);
  return response(data.record, "目标病历版本已作废");
};

export const downloadMedicalRecordApi = async (record: GeneratedMedicalRecord) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/medical-record/download?id=${encodeURIComponent(record.id)}`, {
    headers: authHeaders()
  });
  if (!result.ok) {
    await parseClinicApiResponse(result);
    return response(null);
  }
  const blob = await result.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = record.fileName || `医生目标病历-V${record.version}.docx`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 500);
  return response(null, "目标病历 docx 已下载");
};

export const getAiDocumentTemplatesApi = async () => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai-document/templates`, { headers: authHeaders() });
  const data = await parseClinicApiResponse<AiDocumentTemplateResult>(result);
  return response(data);
};

export const previewAiDocumentApi = async (payload: AiDocumentRequestPayload) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai-document/preview`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiDocumentPreview>(result);
  return response(data, "文稿预览已生成");
};

export const generateAiDocumentApi = async (payload: AiDocumentRequestPayload) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai-document/generate`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiDocumentGenerateResult>(result);
  return response(data, "DOCX 文稿已生成");
};

export const downloadAiDocumentApi = async (generatedDocument: GeneratedAiDocument) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai-document/download?id=${encodeURIComponent(generatedDocument.id)}`, {
    headers: authHeaders()
  });
  if (!result.ok) {
    await parseClinicApiResponse(result);
    return response(null);
  }
  const blob = await result.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = generatedDocument.fileName || `${generatedDocument.title || "AI文稿"}.docx`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 500);
  return response(null, "DOCX 文稿已下载");
};

export const getAiRuntimeConfigApi = async () => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/config`, { headers: authHeaders() });
  const data = await parseClinicApiResponse<AiRuntimeConfig>(result);
  return response(data);
};

export const saveAiRuntimeConfigApi = async (payload: AiRuntimeConfigPayload) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/config`, {
    method: "PUT",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiRuntimeConfig>(result);
  return response(data, "AI接口配置已保存");
};

export const getDoubaoAiRuntimeConfigApi = async () => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/doubao/config`, { headers: authHeaders() });
  const data = await parseClinicApiResponse<AiRuntimeConfig>(result);
  return response(data);
};

export const saveDoubaoAiRuntimeConfigApi = async (payload: AiRuntimeConfigPayload) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/doubao/config`, {
    method: "PUT",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiRuntimeConfig>(result);
  return response(data, "豆包助手配置已保存");
};

export const getDoubaoTtsConfigApi = async () => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/doubao/tts/config`, { headers: authHeaders() });
  const data = await parseClinicApiResponse<AiRuntimeConfig>(result);
  return response(data);
};

export const saveDoubaoTtsConfigApi = async (payload: AiRuntimeConfigPayload) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/doubao/tts/config`, {
    method: "PUT",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiRuntimeConfig>(result);
  return response(data, "豆包语音朗读配置已保存");
};

export const speakAiSummaryApi = async (payload: DoubaoTtsSpeakParams) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/doubao/tts/speak`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<DoubaoTtsSpeakResult>(result);
  return response(data, "豆包语音朗读已生成");
};

export const testDoubaoTtsConfigApi = async (payload: DoubaoTtsConfigTestPayload) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/doubao/tts/test`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<DoubaoTtsSpeakResult>(result);
  return response(data, "豆包语音朗读检测成功");
};

export const detectDoubaoAiModelsApi = async (payload: AiModelDetectionPayload) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/doubao/models`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiModelDetectionResult>(result);
  return response(data, "豆包模型检测完成");
};

export const askAiAssistantApi = async (payload: AiAssistantRequest) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/assistant`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiAssistantResponse>(result);
  return response(data, "豆包助手已生成回答");
};

export const getAiAssistantLogsApi = async (params: AiAssistantLogListParams = {}) => {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") query.set(key, String(value));
  });
  const suffix = query.toString() ? `?${query.toString()}` : "";
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/assistant/logs${suffix}`, { headers: authHeaders() });
  const data = await parseClinicApiResponse<AiAssistantLogListResult>(result);
  return response(data);
};

export const getAiAssistantAnalyticsApi = async (params: AiAssistantLogListParams = {}) => {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") query.set(key, String(value));
  });
  const suffix = query.toString() ? `?${query.toString()}` : "";
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/assistant/analytics${suffix}`, { headers: authHeaders() });
  const data = await parseClinicApiResponse<AiAssistantAnalytics>(result);
  return response(data);
};

export const getAiAssistantTemplatesApi = async () => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/assistant/templates`, { headers: authHeaders() });
  const data = await parseClinicApiResponse<AiPromptTemplateListResult>(result);
  return response(data);
};

export const markAiAssistantTemplateCandidateApi = async (id: string, payload: AiPromptTemplatePayload) => {
  const result = await fetch(`${getClinicApiBaseUrl()}/ai/assistant/logs/${id}/template-candidate`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiPromptTemplateCandidate>(result);
  return response(data, "模板候选已保存");
};

export const getWorkRemindersApi = async () => {
  const [{ data: stats }, { data: duplicates }, { data: status }] = await Promise.all([
    getOperationStatsApi(),
    getDuplicatePatientGroupsApi(),
    getMaintenanceStatusApi()
  ]);
  const reminders: WorkReminder[] = [
    {
      id: "returned",
      level: stats.returnedPatients ? "danger" : "success",
      title: "审核退回",
      desc: stats.returnedPatients ? "优先补齐退回档案，避免患者资料长时间悬空" : "暂无退回整改",
      path: "/audit/review",
      count: stats.returnedPatients
    },
    {
      id: "review",
      level: stats.reviewPatients ? "warning" : "success",
      title: "待档案审核",
      desc: stats.reviewPatients ? "已有健康档案提交审核，建议当日完成复核" : "暂无待审核档案",
      path: "/audit/review",
      count: stats.reviewPatients
    },
    {
      id: "overdue",
      level: stats.overduePatients ? "warning" : "success",
      title: "超时未闭环",
      desc: stats.overduePatients ? "超过 24 小时未更新，建议查看是否遗漏资料" : "暂无超时未闭环",
      path: "/encounters/active",
      count: stats.overduePatients
    },
    {
      id: "duplicates",
      level: duplicates.length ? "danger" : "success",
      title: "疑似重复患者",
      desc: duplicates.length ? "存在姓名手机号或门诊号重复，需要合并或核对" : "暂无疑似重复",
      path: "/patients/list",
      count: duplicates.length
    },
    {
      id: "missing-files",
      level: status.storage.missingFileCount ? "danger" : "success",
      title: "附件完整性",
      desc: status.storage.missingFileCount ? "存在数据库引用但磁盘缺失的附件" : "附件引用正常",
      path: "/documents/recycle",
      count: status.storage.missingFileCount
    }
  ];
  return response(reminders);
};

export const logPatientExportApi = async (params: {
  id: string;
  role: string;
  operator?: string;
  action: "print" | "export";
}) => {
  const db = await readDb();
  const patient = getPatientOrThrow(db, params.id);
  await patchDb({
    auditLogs: [
      {
        id: `audit-${Date.now()}-${Math.random().toString(16).slice(2)}`,
        time: now(),
        operator: params.operator || roleLabel(params.role),
        role: roleLabel(params.role),
        patient: patient.name,
        patientId: patient.id,
        module: "archive",
        actionCode: params.action === "print" ? "record.print" : "record.export",
        targetType: "record",
        targetKey: patient.id,
        targetLabel: patient.visitNo,
        action: params.action === "print" ? "打印健康档案" : "导出健康档案",
        result: "success",
        detail: `${roleLabel(params.role)}${params.action === "print" ? "打印" : "导出"}健康档案：${patient.name}/${patient.visitNo}`
      }
    ]
  });
  return response(null, params.action === "print" ? "打印操作已记录" : "导出操作已记录");
};
