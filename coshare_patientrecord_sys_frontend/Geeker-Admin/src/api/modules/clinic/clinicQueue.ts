import { clinicFetch, clinicJsonHeaders, clinicResponse, parseClinicApiResponse } from "./http";
import { authHeaders } from "../authToken";

export type QueueStage = "INSPECTION" | "RECEPTION";
export type QueueVisitType = "FIRST_VISIT" | "FOLLOW_UP";
export type QueueTaskStatus =
  | "INACTIVE"
  | "WAITING"
  | "CALLED"
  | "ARRIVED"
  | "IN_SERVICE"
  | "COMPLETED"
  | "MISSED"
  | "TEMPORARILY_AWAY"
  | "INTERRUPTED"
  | "ON_HOLD"
  | "CANCELLED";

export interface QueueTicket {
  id: string;
  encounterId: string;
  businessDate: string;
  publicNo: string;
  visitType: QueueVisitType;
  patientId: string;
  patientName: string;
  maskedName: string;
  overallStatus: string;
  version: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  completedAt?: string;
}

export interface QueueTask {
  id: string;
  ticketId: string;
  stageCode: QueueStage;
  roomCode: string;
  status: QueueTaskStatus;
  version: number;
  priorityLocked: boolean;
  priorityReason: string;
  recallCount: number;
  exceptionReason: string;
  interruptedFromStatus: string;
  queueEnteredAt?: string;
  calledAt?: string;
  arrivedAt?: string;
  serviceStartedAt?: string;
  completedAt?: string;
  updatedAt: string;
}

export interface QueueRoom {
  roomCode: string;
  roomName: string;
  stageCode: QueueStage;
  status: "ACTIVE" | "EMERGENCY_PAUSED" | "MANUAL_PAUSED" | "CLOSED" | "OFFLINE";
  pauseReason: string;
  followUpStreak: number;
  version: number;
  updatedAt: string;
}

export interface QueueAudit {
  id: string;
  ticketId: string;
  taskId: string;
  roomCode: string;
  actionCode: string;
  fromStatus: string;
  toStatus: string;
  operatorName: string;
  operatorRole: string;
  detail: string;
  createdAt: string;
}

export interface QueueAnnouncement {
  id: string;
  ticketId: string;
  taskId: string;
  publicNo: string;
  stageCode: QueueStage;
  roomName: string;
  content: string;
  status: string;
  playCount: number;
  createdAt: string;
  playedAt?: string;
}

export interface QueueCounts {
  inspectionWaiting: number;
  inspectionActive: number;
  receptionWaiting: number;
  receptionActive: number;
  completedToday: number;
  exceptions: number;
}

export interface QueuePrintTemplate {
  institutionName: string;
  title: string;
  paperWidth: 58 | 80;
  numberFontSize: number;
  compact: boolean;
  showMaskedName: boolean;
  showVisitType: boolean;
  showFirstStage: boolean;
  showIssuedAt: boolean;
  showNotice: boolean;
  notice: string;
  secondaryNotice: string;
}

export interface QueuePrintPayload {
  taskId: string;
  executionToken: string;
  terminalId: string;
  printerName: string;
  title: string;
  publicNo: string;
  maskedName: string;
  visitType: string;
  firstStage: string;
  issuedAt: string;
  notice: string;
  reprint: boolean;
  test?: boolean;
  template: QueuePrintTemplate;
}

export interface QueuePrintTask {
  id: string;
  ticketId: string;
  terminalId: string;
  printerName: string;
  status: "PENDING" | "SUCCESS" | "FAILED";
  printType: "INITIAL" | "REPRINT";
  reprintReason: string;
  attemptCount: number;
  payload: QueuePrintPayload;
  createdBy: string;
  createdAt: string;
  completedAt?: string;
  errorMessage: string;
}

export interface QueuePrintTerminal {
  terminalId: string;
  terminalName: string;
  printerName: string;
  agentVersion: string;
  status: string;
  lastSeenAt: string;
}

export interface QueueWorkspace {
  ticket: QueueTicket;
  tasks: QueueTask[];
  audits: QueueAudit[];
  currentUserRole: string;
  newlyIssued?: boolean;
  issueMessage?: string;
}

export interface QueueDashboard {
  tickets: QueueTicket[];
  rooms: QueueRoom[];
  counts: QueueCounts;
  currentUserRole: string;
  serverTime: string;
}

export interface QueueDisplayRow {
  id: string;
  publicNo: string;
  visitType: QueueVisitType;
  status: QueueTaskStatus;
  calledAt?: string;
  updatedAt: string;
}

export interface QueueDisplayRoom {
  room: QueueRoom;
  calling: QueueDisplayRow[];
  waiting: QueueDisplayRow[];
}

export interface QueueDisplaySnapshot {
  inspection: QueueDisplayRoom;
  reception: QueueDisplayRoom;
  counts: QueueCounts;
  serverTime: string;
  refreshSeconds: number;
}

const post = async <T>(path: string, body?: unknown) => {
  const result = await clinicFetch(path, {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return clinicResponse(await parseClinicApiResponse<T>(result));
};

export const getQueueDashboardApi = async (keyword = "") => {
  const query = keyword ? `?keyword=${encodeURIComponent(keyword)}` : "";
  const result = await clinicFetch(`/clinic-queue/dashboard${query}`, { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<QueueDashboard>(result));
};

export const issueQueueTicketApi = (encounterId: string, visitType: QueueVisitType) =>
  post<QueueWorkspace>("/clinic-queue/tickets", { encounterId, visitType });

export const getQueueWorkspaceApi = async (ticketId: string) => {
  const result = await clinicFetch(`/clinic-queue/tickets/${encodeURIComponent(ticketId)}`, { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<QueueWorkspace>(result));
};

export const getQueuePrintTemplateApi = async () => {
  const result = await clinicFetch("/clinic-queue/print-template", { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<{ config: QueuePrintTemplate }>(result));
};

export const saveQueuePrintTemplateApi = (config: QueuePrintTemplate) =>
  post<{ config: QueuePrintTemplate }>("/clinic-queue/print-template", config);

export const getQueueTestPrintPayloadApi = (terminalId: string) =>
  post<{ payload: QueuePrintPayload }>("/clinic-queue/print-template/test-payload", { terminalId, reason: "" });

export const registerQueuePrintTerminalApi = (payload: {
  terminalId: string;
  terminalName: string;
  printerName: string;
  agentVersion: string;
}) => post<QueuePrintTerminal>("/clinic-queue/print-terminals/register", payload);

export const createQueuePrintTaskApi = (ticketId: string, terminalId: string, reason = "") =>
  post<QueuePrintTask>(`/clinic-queue/tickets/${encodeURIComponent(ticketId)}/print-tasks`, { terminalId, reason });

export const completeQueuePrintTaskApi = (
  id: string,
  payload: { status: "SUCCESS" | "FAILED"; printerName: string; errorMessage?: string }
) => post<QueuePrintTask>(`/clinic-queue/print-tasks/${encodeURIComponent(id)}/result`, payload);

export const getQueuePrintTasksApi = async (ticketId: string) => {
  const result = await clinicFetch(`/clinic-queue/tickets/${encodeURIComponent(ticketId)}/print-tasks`, { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<{ rows: QueuePrintTask[] }>(result));
};

export const callNextQueueApi = (stage: QueueStage) => post<QueueWorkspace>(`/clinic-queue/stages/${stage}/call-next`);

export const runQueueTaskActionApi = (taskId: string, action: string, reason = "") =>
  post<QueueWorkspace>(`/clinic-queue/tasks/${encodeURIComponent(taskId)}/${encodeURIComponent(action)}`, { reason });

export const runQueueRoomActionApi = (roomCode: string, action: string, reason = "") =>
  post<QueueDashboard>(`/clinic-queue/rooms/${encodeURIComponent(roomCode)}/${encodeURIComponent(action)}`, { reason });

export const getQueueDisplayApi = async () => {
  const result = await clinicFetch("/clinic-queue/display", { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<QueueDisplaySnapshot>(result));
};

export const getPendingQueueAnnouncementsApi = async () => {
  const result = await clinicFetch("/clinic-queue/announcements/pending", { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<{ rows: QueueAnnouncement[] }>(result));
};

export const markQueueAnnouncementPlayedApi = (id: string) =>
  post<{ id: string; status: string }>(`/clinic-queue/announcements/${encodeURIComponent(id)}/played`);

export const getQueueAuditsApi = async (params: { ticketId?: string; roomCode?: string } = {}) => {
  const query = new URLSearchParams();
  if (params.ticketId) query.set("ticketId", params.ticketId);
  if (params.roomCode) query.set("roomCode", params.roomCode);
  const suffix = query.toString() ? `?${query.toString()}` : "";
  const result = await clinicFetch(`/clinic-queue/audits${suffix}`, { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<{ rows: QueueAudit[] }>(result));
};
