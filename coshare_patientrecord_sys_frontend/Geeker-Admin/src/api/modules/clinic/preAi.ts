import { authHeaders, handleUnauthorizedResponse } from "@/api/modules/authToken";
import { getLoginOptionsApi, type LoginAccountOption } from "@/api/modules/login";
import { clinicFetch, clinicJsonHeaders, clinicResponse, parseClinicApiResponse } from "./http";
import type { QueueWorkspace } from "./clinicQueue";

export type PreAiStageCode = "REGISTRATION" | "INSPECTION" | "RECEPTION" | "TCM" | "DOCTOR" | "SURGERY" | "REVIEW";
export type PreAiStageStatus = "DRAFT" | "COMPLETED" | "RETURNED" | "SKIPPED";
export type PreAiEncounterStatus = "IN_PROGRESS" | "PENDING_REVIEW" | "REVIEWED" | "EXPORTED" | "CANCELLED";
export type PreAiEncounterRoute = "" | "OUTPATIENT" | "INPATIENT";
export type PreAiTreatmentPath = "" | "CONSERVATIVE" | "SURGICAL";
export type PreAiAuxiliaryTaskType = "LAB" | "ECG" | "IMAGING" | "VITAL_SIGNS" | "COLONOSCOPY";
export type PreAiDutyCode =
  | "FRONT_DESK"
  | "RECEPTION_DOCTOR"
  | "TCM_DOCTOR"
  | "INSPECTION_DOCTOR"
  | "LAB_STAFF"
  | "BASIC_NURSING"
  | "ATTENDING_DOCTOR"
  | "SURGEON"
  | "OPERATING_ROOM_NURSE"
  | "FINAL_REVIEW_DOCTOR";

export interface PreAiDutyAssignment {
  dutyCode: PreAiDutyCode;
  responsibleUserId?: string;
  responsibleUserName?: string;
  participantUserIds?: string[];
  participantUserNames?: string[];
}

export interface PreAiDutyUserOption extends LoginAccountOption {}

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

export interface PreAiEncounterHistoryItem extends PreAiEncounterSummary {
  visitType: "INITIAL" | "FOLLOW_UP";
  previousEncounterId?: string;
  completedStages: PreAiStageCode[];
  completedStageCount: number;
  visitReason?: string;
  description?: string;
}

export interface PreAiEncounter extends Omit<
  PreAiEncounterSummary,
  "patientName" | "gender" | "age" | "visitDate" | "stageStatuses"
> {
  patient: Record<string, any>;
  visitMeta?: VisitMeta;
  legacyReference?: Record<string, any>;
  dutyAssignments?: PreAiDutyAssignment[];
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

export interface FollowUpRegisterAndIssueRequest extends FollowUpEncounterCreateRequest {
  clientRequestId: string;
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
  updatedBy?: string;
  updatedByRole?: string;
  completedBy?: string;
  completedByRole?: string;
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
  severity?: "NORMAL" | "ABNORMAL" | "CRITICAL";
  critical?: boolean;
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
  templateVersion?: string;
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
  dutyAssignments: PreAiDutyAssignment[];
  stages: PreAiStageSubmission[];
  auxiliaryTasks: PreAiAuxiliaryTask[];
  labReports: LabReportSnapshot[];
  attachments: PreAiAttachment[];
  diagnoses: Array<Record<string, any>>;
  auditLogs: PreAiAuditLog[];
  exports: PreAiExportVersion[];
  currentUserRole: string;
  readOnly?: boolean;
  queueHandoff?: QueueHandoff;
}

export interface QueueHandoff {
  ticketId: string;
  publicNo: string;
  fromStage: "INSPECTION" | "RECEPTION";
  nextStage?: "RECEPTION";
  nextStatus: string;
  transferredAt: string;
}

export interface RegisterAndIssueResult {
  encounterWorkspace: PreAiWorkspace;
  queueWorkspace: QueueWorkspace;
}

export interface PreAiReviewLabMetric {
  reportName: string;
  reportDate: string;
  name: string;
  shortName?: string;
  value: string;
  unit?: string;
  reference?: string;
  abnormal: string;
  severity: "ABNORMAL" | "CRITICAL";
}

export interface PreAiReviewPreview {
  workspace: PreAiWorkspace;
  maskedPreview: Record<string, any>;
  blockers: string[];
  labSummary: {
    abnormalCount: number;
    criticalCount: number;
    abnormalMetrics: PreAiReviewLabMetric[];
  };
  ready: boolean;
  templateVersion?: string;
  effectiveFieldCount?: number;
  documentSections?: PreAiDocumentSection[];
}

export interface PreAiDocumentRow {
  id: string;
  label: string;
  value: string;
  contentType: "TEXT" | "LIST" | "MEASUREMENT" | "IMAGE";
  severity: "NORMAL" | "ABNORMAL" | "CRITICAL";
  emphasis?: boolean;
}

export interface PreAiDocumentSection {
  code: string;
  title: string;
  rows: PreAiDocumentRow[];
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

let dutyUserOptionsCache: PreAiDutyUserOption[] | undefined;
let dutyUserOptionsRequest: Promise<PreAiDutyUserOption[]> | undefined;

export const getPreAiDutyUserOptionsApi = async () => {
  if (!dutyUserOptionsCache) {
    dutyUserOptionsRequest ||= getLoginOptionsApi()
      .then(({ data }) => data.accounts || [])
      .then(accounts => {
        dutyUserOptionsCache = accounts;
        return accounts;
      })
      .catch(error => {
        dutyUserOptionsRequest = undefined;
        throw error;
      });
    await dutyUserOptionsRequest;
  }
  return clinicResponse({ list: dutyUserOptionsCache || [] });
};

export const createPreAiFollowUpApi = (patientCaseId: string, payload: FollowUpEncounterCreateRequest) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/patients/${encodeURIComponent(patientCaseId)}/encounters`, "POST", payload);

export const registerAndIssuePreAiFollowUpApi = (patientCaseId: string, payload: FollowUpRegisterAndIssueRequest) =>
  jsonRequest<RegisterAndIssueResult>(
    `/pre-ai/patients/${encodeURIComponent(patientCaseId)}/encounters/register-and-issue`,
    "POST",
    payload
  );

export const getPreAiEncounterHistoryApi = async (patientCaseId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(`/pre-ai/patients/${encodeURIComponent(patientCaseId)}/encounters/history`, {
    headers: authHeaders(),
    signal
  });
  return clinicResponse(await parseClinicApiResponse<{ patientCaseId: string; encounters: PreAiEncounterHistoryItem[] }>(result));
};

export const getPreAiInspectionTimelineApi = async (patientCaseId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(`/pre-ai/patients/${encodeURIComponent(patientCaseId)}/inspection-timeline`, {
    headers: authHeaders(),
    signal
  });
  return clinicResponse(await parseClinicApiResponse<{ patientCaseId: string; nodes: InspectionTimelineNode[] }>(result));
};

export const getPreAiWorkspaceApi = async (encounterId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(`/pre-ai/encounters/${encodeURIComponent(encounterId)}`, {
    headers: authHeaders(),
    signal
  });
  return clinicResponse(await parseClinicApiResponse<PreAiWorkspace>(result));
};

export const getPreAiReadOnlyWorkspaceApi = async (encounterId: string, patientCaseId: string, signal?: AbortSignal) => {
  const query = new URLSearchParams({ readOnly: "true", patientCaseId });
  const result = await clinicFetch(`/pre-ai/encounters/${encodeURIComponent(encounterId)}?${query}`, {
    headers: authHeaders(),
    signal
  });
  return clinicResponse(await parseClinicApiResponse<PreAiWorkspace>(result));
};

export const createPreAiEncounterApi = (patient: Record<string, any>) =>
  jsonRequest<PreAiWorkspace>("/pre-ai/encounters", "POST", { patient });

export const registerAndIssuePreAiEncounterApi = (patient: Record<string, any>, clientRequestId: string) =>
  jsonRequest<RegisterAndIssueResult>("/pre-ai/encounters/register-and-issue", "POST", { patient, clientRequestId });

export const registerAndIssueExistingPreAiEncounterApi = (
  encounterId: string,
  patient: Record<string, any>,
  clientRequestId: string,
  expectedVersion?: number
) =>
  jsonRequest<RegisterAndIssueResult>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/register-and-issue`, "POST", {
    patient,
    clientRequestId,
    expectedVersion
  });

export const savePreAiVisitMetaApi = (encounterId: string, visitMeta: VisitMeta) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/visit-meta`, "PUT", { visitMeta });

export const savePreAiDutyAssignmentsApi = (encounterId: string, dutyAssignments: PreAiDutyAssignment[]) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/duty-assignments`, "PUT", {
    dutyAssignments
  });

export const importLegacyPreAiEncounterApi = (patientId: string) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/imports/${encodeURIComponent(patientId)}`, "POST");

export const savePreAiStageApi = (
  encounterId: string,
  stageCode: PreAiStageCode,
  data: Record<string, any>,
  expectedVersion: number
) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/stages/${stageCode}`, "PUT", {
    data,
    expectedVersion
  });

export const completePreAiStageApi = (
  encounterId: string,
  stageCode: PreAiStageCode,
  data: Record<string, any>,
  expectedVersion: number
) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/stages/${stageCode}/complete`, "POST", {
    data,
    expectedVersion
  });

export const returnPreAiStageApi = (encounterId: string, stageCode: PreAiStageCode, reason: string, expectedVersion: number) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/stages/${stageCode}/return`, "POST", {
    reason,
    expectedVersion
  });

export const createPreAiAuxiliaryTaskApi = (
  encounterId: string,
  payload: { taskType: PreAiAuxiliaryTaskType; title: string; requiredBeforeExport: boolean }
) => jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/auxiliary-tasks`, "POST", payload);

export const savePreAiAuxiliaryTaskApi = (
  encounterId: string,
  taskId: string,
  payload: { title: string; requiredBeforeExport: boolean; data: Record<string, any>; expectedVersion: number },
  complete = false
) =>
  jsonRequest<PreAiWorkspace>(
    `/pre-ai/encounters/${encodeURIComponent(encounterId)}/auxiliary-tasks/${encodeURIComponent(taskId)}${complete ? "/complete" : ""}`,
    complete ? "POST" : "PUT",
    payload
  );

export const returnPreAiAuxiliaryTaskApi = (encounterId: string, taskId: string, reason: string, expectedVersion: number) =>
  jsonRequest<PreAiWorkspace>(
    `/pre-ai/encounters/${encodeURIComponent(encounterId)}/auxiliary-tasks/${encodeURIComponent(taskId)}/return`,
    "POST",
    { reason, expectedVersion }
  );

export const uploadPreAiAttachmentApi = (
  encounterId: string,
  payload: {
    stageCode?: PreAiStageCode;
    taskId?: string;
    file: File;
    description?: string;
    capturedAt?: string;
    batchId?: string;
    batchName?: string;
    relativePath?: string;
    sequenceNo?: number;
  }
) => {
  const body = new FormData();
  body.append("file", payload.file, payload.file.name);
  for (const [key, value] of Object.entries(payload)) {
    if (key === "file" || value === undefined || value === null || value === "") continue;
    body.append(key, String(value));
  }
  return clinicFetch(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/attachments`, {
    method: "POST",
    headers: authHeaders(),
    body
  })
    .then(parseClinicApiResponse<PreAiWorkspace>)
    .then(clinicResponse);
};

export const savePreAiLabReportApi = (
  encounterId: string,
  payload: {
    templateId: string;
    templateName: string;
    reportDate: string;
    remark?: string;
    metrics: LabReportMetricSnapshot[];
    expectedVersion: number;
  }
) => jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/lab-reports`, "POST", payload);

export const completePreAiLabApi = (encounterId: string, expectedVersion: number) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/lab/complete`, "POST", {
    expectedVersion
  });

export const voidPreAiAttachmentApi = (encounterId: string, attachmentId: string) =>
  jsonRequest<PreAiWorkspace>(
    `/pre-ai/encounters/${encodeURIComponent(encounterId)}/attachments/${encodeURIComponent(attachmentId)}`,
    "DELETE"
  );

export const getPreAiReviewPreviewApi = async (encounterId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/review`, {
    headers: authHeaders(),
    signal
  });
  return clinicResponse(await parseClinicApiResponse<PreAiReviewPreview>(result));
};

export const confirmPreAiReviewApi = (
  encounterId: string,
  statement = "",
  criticalAcknowledged = false,
  expectedVersion: number
) =>
  jsonRequest<PreAiWorkspace>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/review/confirm`, "POST", {
    statement,
    criticalAcknowledged,
    expectedVersion
  });

export const generatePreAiExportApi = (encounterId: string) =>
  jsonRequest<{ export: PreAiExportVersion; workspace: PreAiWorkspace }>(
    `/pre-ai/encounters/${encodeURIComponent(encounterId)}/exports`,
    "POST"
  );

export const normalizePreAiDownloadPath = (path: string) => {
  const value = String(path || "").trim();
  if (!value) return value;
  try {
    const url = new URL(value, window.location.origin);
    return url.pathname.replace(/^(?:\/clinic-api)+(?=\/)/, "") + url.search;
  } catch {
    return value.replace(/^(?:\/clinic-api)+(?=\/)/, "");
  }
};

const downloadAuthenticatedFile = async (path: string, fileName: string) => {
  const result = await clinicFetch(normalizePreAiDownloadPath(path), { headers: authHeaders() });
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

export const getPreAiAttachmentObjectUrlApi = async (attachment: PreAiAttachment, signal?: AbortSignal) => {
  const result = await clinicFetch(normalizePreAiDownloadPath(attachment.downloadUrl), { headers: authHeaders(), signal });
  if (result.status === 401) handleUnauthorizedResponse();
  if (!result.ok) throw new Error((await result.text()) || "图片加载失败");
  return URL.createObjectURL(await result.blob());
};
