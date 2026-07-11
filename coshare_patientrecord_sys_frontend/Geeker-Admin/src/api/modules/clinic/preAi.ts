import { authHeaders, handleUnauthorizedResponse } from "@/api/modules/authToken";
import { clinicFetch, clinicJsonHeaders, clinicResponse, parseClinicApiResponse } from "./http";

export type PreAiStageCode = "REGISTRATION" | "INSPECTION" | "RECEPTION" | "TCM" | "DOCTOR" | "SURGERY" | "REVIEW";
export type PreAiStageStatus = "DRAFT" | "COMPLETED" | "RETURNED" | "SKIPPED";
export type PreAiEncounterStatus = "IN_PROGRESS" | "PENDING_REVIEW" | "REVIEWED" | "EXPORTED" | "CANCELLED";
export type PreAiEncounterRoute = "" | "OUTPATIENT" | "INPATIENT";
export type PreAiTreatmentPath = "" | "CONSERVATIVE" | "SURGICAL";
export type PreAiAuxiliaryTaskType = "LAB" | "ECG" | "IMAGING";

export interface PreAiEncounterSummary {
  id: string;
  sourcePatientId?: string;
  patientCaseId: string;
  visitNo: number;
  followUpOfEncounterId?: string;
  caseToken: string;
  route: PreAiEncounterRoute;
  treatmentPath: PreAiTreatmentPath;
  status: PreAiEncounterStatus;
  currentStage: PreAiStageCode;
  patientName: string;
  gender: string;
  age: string;
  visitDate: string;
  createdAt: string;
  updatedAt: string;
  stageStatuses: Partial<Record<PreAiStageCode, PreAiStageStatus>>;
}

export interface PreAiEncounter extends Omit<
  PreAiEncounterSummary,
  "patientName" | "gender" | "age" | "visitDate" | "stageStatuses"
> {
  patient: Record<string, any>;
  visitMeta?: VisitMeta;
  legacyReference?: Record<string, any>;
  reviewedAt?: string;
  reviewedBy?: string;
  reviewedByRole?: string;
}

export type VisitPaymentStatus = "" | "UNPAID" | "PARTIAL" | "PAID" | "REFUNDED";

export interface VisitMeta {
  visitReason?: string;
  description?: string;
  paymentStatus?: VisitPaymentStatus;
  paymentAmount?: string | number;
  paymentItems?: string | string[];
  paidAt?: string;
  paymentRemark?: string;
}

export interface PreAiPatientCase {
  id: string;
  sourcePatientId?: string;
  patient: Record<string, any>;
  patientName: string;
  gender: string;
  age: string;
  visitCount: number;
  latestEncounter?: PreAiEncounterSummary;
  createdAt: string;
  updatedAt: string;
}

export interface InspectionTimelineNode {
  encounterId: string;
  caseToken: string;
  visitNo: number;
  visitDate: string;
  route: PreAiEncounterRoute;
  status: PreAiEncounterStatus;
  inspectionStatus: PreAiStageStatus;
  inspection: Record<string, any>;
  visitMeta: VisitMeta;
  attachments: PreAiAttachment[];
}

export interface FollowUpEncounterCreateRequest {
  visitDate: string;
  visitMeta: VisitMeta;
}

export interface PreAiStageSubmission {
  encounterId: string;
  stageCode: PreAiStageCode;
  status: PreAiStageStatus;
  version: number;
  data: Record<string, any>;
  returnedReason?: string;
  submittedBy?: string;
  submittedByRole?: string;
  completedAt?: string;
  updatedAt: string;
}

export interface PreAiAuxiliaryTask {
  id: string;
  encounterId: string;
  taskType: PreAiAuxiliaryTaskType;
  title: string;
  ownerRole: string;
  requiredBeforeExport: boolean;
  status: "DRAFT" | "COMPLETED" | "RETURNED";
  data: Record<string, any>;
  version: number;
  completedAt?: string;
  updatedAt: string;
  createdAt: string;
  createdBy: string;
}

export interface PreAiAttachment {
  id: string;
  encounterId: string;
  stageCode?: PreAiStageCode;
  taskId?: string;
  fileName: string;
  mimeType: string;
  fileSize: number;
  description?: string;
  capturedAt?: string;
  uploader?: string;
  uploaderRole?: string;
  batchId?: string;
  batchName?: string;
  relativePath?: string;
  sequenceNo?: number;
  createdAt: string;
  downloadUrl: string;
}

export interface LabReportMetricSnapshot {
  key: string;
  name: string;
  shortName?: string;
  value: string;
  unit?: string;
  reference?: string;
}

export interface LabReportSnapshot {
  id: string;
  encounterId: string;
  templateId: string;
  templateName: string;
  reportDate: string;
  remark?: string;
  metrics: LabReportMetricSnapshot[];
  version: number;
  status: string;
  savedBy?: string;
  savedByRole?: string;
  savedAt: string;
}

export interface PreAiExportVersion {
  id: string;
  encounterId: string;
  version: number;
  status: string;
  caseToken: string;
  fileName: string;
  generatedBy: string;
  generatedByRole: string;
  generatedAt: string;
  downloadUrl: string;
}

export interface PreAiAuditLog {
  id: string;
  action: string;
  stageCode?: PreAiStageCode;
  operator: string;
  operatorRole: string;
  detail: string;
  createdAt: string;
}

export interface PreAiWorkspace {
  encounter: PreAiEncounter;
  stages: PreAiStageSubmission[];
  auxiliaryTasks: PreAiAuxiliaryTask[];
  labReports: LabReportSnapshot[];
  attachments: PreAiAttachment[];
  diagnoses: Array<Record<string, any>>;
  auditLogs: PreAiAuditLog[];
  exports: PreAiExportVersion[];
  currentUserRole: string;
}

export interface PreAiReviewPreview {
  workspace: PreAiWorkspace;
  maskedPreview: Record<string, any>;
  blockers: string[];
  ready: boolean;
}

const jsonRequest = async <T>(path: string, method: "POST" | "PUT" | "DELETE", body?: unknown) => {
  const result = await clinicFetch(path, {
    method,
    headers: clinicJsonHeaders(),
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return clinicResponse(await parseClinicApiResponse<T>(result));
};

export const getPreAiEncountersApi = async () => {
  const result = await clinicFetch("/pre-ai/encounters", { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<{ list: PreAiEncounterSummary[] }>(result));
};

export const getPreAiPatientCasesApi = async () => {
  const result = await clinicFetch("/pre-ai/patients", { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<{ list: PreAiPatientCase[] }>(result));
};

export const createPreAiFollowUpApi = (patientCaseId: string, payload: FollowUpEncounterCreateRequest) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/patients/${encodeURIComponent(patientCaseId)}/encounters`, "POST", payload);

export const getPreAiInspectionTimelineApi = async (patientCaseId: string) => {
  const result = await clinicFetch(`/pre-ai/patients/${encodeURIComponent(patientCaseId)}/inspection-timeline`, {
    headers: authHeaders()
  });
  return clinicResponse(await parseClinicApiResponse<{ patientCaseId: string; nodes: InspectionTimelineNode[] }>(result));
};

export const getPreAiWorkspaceApi = async (encounterId: string) => {
  const result = await clinicFetch(`/pre-ai/encounters/${encodeURIComponent(encounterId)}`, { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<PreAiWorkspace>(result));
};

export const createPreAiEncounterApi = (patient: Record<string, any>) =>
  jsonRequest<PreAiWorkspace>("/pre-ai/encounters", "POST", { patient });

export const savePreAiVisitMetaApi = (encounterId: string, visitMeta: VisitMeta) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/visit-meta`, "PUT", { visitMeta });

export const importLegacyPreAiEncounterApi = (patientId: string) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/imports/${encodeURIComponent(patientId)}`, "POST");

export const savePreAiStageApi = (encounterId: string, stageCode: PreAiStageCode, data: Record<string, any>) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/stages/${stageCode}`, "PUT", { data });

export const completePreAiStageApi = (encounterId: string, stageCode: PreAiStageCode, data: Record<string, any>) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/stages/${stageCode}/complete`, "POST", {
    data
  });

export const returnPreAiStageApi = (encounterId: string, stageCode: PreAiStageCode, reason: string) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/stages/${stageCode}/return`, "POST", {
    reason
  });

export const createPreAiAuxiliaryTaskApi = (
  encounterId: string,
  payload: { taskType: PreAiAuxiliaryTaskType; title: string; requiredBeforeExport: boolean }
) => jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/auxiliary-tasks`, "POST", payload);

export const savePreAiAuxiliaryTaskApi = (
  encounterId: string,
  taskId: string,
  payload: { title: string; requiredBeforeExport: boolean; data: Record<string, any> },
  complete = false
) =>
  jsonRequest<PreAiWorkspace>(
    `/pre-ai/encounters/${encodeURIComponent(encounterId)}/auxiliary-tasks/${encodeURIComponent(taskId)}${complete ? "/complete" : ""}`,
    complete ? "POST" : "PUT",
    payload
  );

export const returnPreAiAuxiliaryTaskApi = (encounterId: string, taskId: string, reason: string) =>
  jsonRequest<PreAiWorkspace>(
    `/pre-ai/encounters/${encodeURIComponent(encounterId)}/auxiliary-tasks/${encodeURIComponent(taskId)}/return`,
    "POST",
    { reason }
  );

export const uploadPreAiAttachmentApi = (
  encounterId: string,
  payload: {
    stageCode?: PreAiStageCode;
    taskId?: string;
    fileName: string;
    contentDataUrl: string;
    description?: string;
    capturedAt?: string;
    batchId?: string;
    batchName?: string;
    relativePath?: string;
    sequenceNo?: number;
  }
) => jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/attachments`, "POST", payload);

export const savePreAiLabReportApi = (
  encounterId: string,
  payload: {
    templateId: string;
    templateName: string;
    reportDate: string;
    remark?: string;
    metrics: LabReportMetricSnapshot[];
  }
) => jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/lab-reports`, "POST", payload);

export const completePreAiLabApi = (encounterId: string) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/lab/complete`, "POST");

export const voidPreAiAttachmentApi = (encounterId: string, attachmentId: string) =>
  jsonRequest<PreAiWorkspace>(
    `/pre-ai/encounters/${encodeURIComponent(encounterId)}/attachments/${encodeURIComponent(attachmentId)}`,
    "DELETE"
  );

export const getPreAiReviewPreviewApi = async (encounterId: string) => {
  const result = await clinicFetch(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/review`, { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<PreAiReviewPreview>(result));
};

export const confirmPreAiReviewApi = (encounterId: string, statement = "") =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/review/confirm`, "POST", { statement });

export const generatePreAiExportApi = (encounterId: string) =>
  jsonRequest<{ export: PreAiExportVersion; workspace: PreAiWorkspace }>(
    `/pre-ai/encounters/${encodeURIComponent(encounterId)}/exports`,
    "POST"
  );

const downloadAuthenticatedFile = async (path: string, fileName: string) => {
  const result = await clinicFetch(path, { headers: authHeaders() });
  if (result.status === 401) handleUnauthorizedResponse();
  if (!result.ok) {
    const message = await result.text();
    throw new Error(message || "文件下载失败");
  }
  const blob = await result.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
};

export const downloadPreAiExportApi = (version: PreAiExportVersion) =>
  downloadAuthenticatedFile(version.downloadUrl, version.fileName);

export const downloadPreAiAttachmentApi = (attachment: PreAiAttachment) =>
  downloadAuthenticatedFile(attachment.downloadUrl, attachment.fileName);

export const getPreAiAttachmentObjectUrlApi = async (attachment: PreAiAttachment) => {
  const result = await clinicFetch(attachment.downloadUrl, { headers: authHeaders() });
  if (result.status === 401) handleUnauthorizedResponse();
  if (!result.ok) throw new Error((await result.text()) || "图片加载失败");
  return URL.createObjectURL(await result.blob());
};
