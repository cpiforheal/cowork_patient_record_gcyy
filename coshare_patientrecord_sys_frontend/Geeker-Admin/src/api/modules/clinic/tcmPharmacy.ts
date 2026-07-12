import { clinicFetch, clinicJsonHeaders, clinicResponse, parseClinicApiResponse } from "./http";
import { authHeaders } from "../authToken";

export type TcmDispenseType = "SELF_DECOCTION" | "HOSPITAL_DECOCTION";
export type TcmReviewDecision = "APPROVE" | "RETURN" | "HOLD";

export interface TcmHerbItem {
  name: string;
  dose: number;
  unit: string;
  method?: string;
  remark?: string;
}

export interface TcmPrescriptionPayload {
  patientId?: string;
  patientName: string;
  visitNo?: string;
  dispenseType: TcmDispenseType;
  doseCount: number;
  amount: number;
  items: TcmHerbItem[];
  requirements: Record<string, unknown>;
}

export interface TcmPrescription extends TcmPrescriptionPayload {
  id: string;
  prescriptionNo: string;
  versionNo: number;
  maskedName: string;
  doctorName: string;
  prescriptionStatus: string;
  chargeStatus: string;
  reviewStatus: string;
  dispensingStatus: string;
  decoctionStatus: string;
  pickupStatus: string;
  pickupNo: string;
  herbCount: number;
  exceptionReason?: string;
  createdAt: string;
  updatedAt: string;
  submittedAt?: string;
  chargedAt?: string;
  reviewedAt?: string;
  readyAt?: string;
  collectedAt?: string;
}

export interface TcmAuditLog {
  id: string;
  actionCode: string;
  fromStatus: string;
  toStatus: string;
  operatorName: string;
  operatorRole: string;
  detail: string;
  createdAt: string;
}

export interface TcmAnnouncement {
  id: string;
  prescriptionId: string;
  pickupNo: string;
  maskedName: string;
  content: string;
  status: string;
  playCount: number;
  createdAt: string;
  playedAt?: string;
}

export interface TcmDisplayRow {
  id: string;
  pickupNo: string;
  maskedName: string;
  dispenseType: TcmDispenseType;
  prescriptionStatus: string;
  pickupStatus: string;
  readyAt?: string;
  updatedAt: string;
}

export interface TcmStatusCounts {
  waitingCharge: number;
  waitingReview: number;
  dispensing: number;
  decocting: number;
  ready: number;
  collectedToday: number;
  exception: number;
}

export interface TcmDisplaySnapshot {
  ready: TcmDisplayRow[];
  waiting: TcmDisplayRow[];
  counts: TcmStatusCounts;
  serverTime: string;
  refreshSeconds: number;
}

export interface TcmWorkspace {
  prescription: TcmPrescription;
  audits: TcmAuditLog[];
  announcements: TcmAnnouncement[];
  currentUserRole: string;
}

const request = async <T>(path: string, method: "POST" | "PUT", body?: unknown) => {
  const result = await clinicFetch(path, {
    method,
    headers: clinicJsonHeaders(),
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return clinicResponse(await parseClinicApiResponse<T>(result));
};

export const getTcmDashboardApi = async () => {
  const result = await clinicFetch("/tcm-pharmacy/dashboard", { headers: authHeaders() });
  return clinicResponse(
    await parseClinicApiResponse<{
      counts: TcmStatusCounts;
      recent: TcmPrescription[];
      display: TcmDisplaySnapshot;
      currentUserRole: string;
    }>(result)
  );
};

export const getTcmPrescriptionsApi = async (params: { status?: string; keyword?: string } = {}) => {
  const query = new URLSearchParams();
  if (params.status) query.set("status", params.status);
  if (params.keyword) query.set("keyword", params.keyword);
  const suffix = query.toString() ? `?${query.toString()}` : "";
  const result = await clinicFetch(`/tcm-pharmacy/prescriptions${suffix}`, { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<{ rows: TcmPrescription[]; counts: TcmStatusCounts }>(result));
};

export const getTcmWorkspaceApi = async (id: string) => {
  const result = await clinicFetch(`/tcm-pharmacy/prescriptions/${encodeURIComponent(id)}`, { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<TcmWorkspace>(result));
};

export const createTcmPrescriptionApi = (payload: TcmPrescriptionPayload) =>
  request<TcmWorkspace>("/tcm-pharmacy/prescriptions", "POST", payload);

export const saveTcmPrescriptionApi = (id: string, payload: TcmPrescriptionPayload) =>
  request<TcmWorkspace>(`/tcm-pharmacy/prescriptions/${encodeURIComponent(id)}`, "PUT", payload);

export const submitTcmPrescriptionApi = (id: string) =>
  request<TcmWorkspace>(`/tcm-pharmacy/prescriptions/${encodeURIComponent(id)}/submit`, "POST");

export const confirmTcmChargeApi = (id: string, reason = "") =>
  request<TcmWorkspace>(`/tcm-pharmacy/prescriptions/${encodeURIComponent(id)}/charge`, "POST", { reason });

export const reviewTcmPrescriptionApi = (id: string, decision: TcmReviewDecision, reason = "") =>
  request<TcmWorkspace>(`/tcm-pharmacy/prescriptions/${encodeURIComponent(id)}/review`, "POST", { decision, reason });

export const advanceTcmDispensingApi = (id: string, action: "start" | "complete" | "verify", reason = "") =>
  request<TcmWorkspace>(`/tcm-pharmacy/prescriptions/${encodeURIComponent(id)}/dispensing/${action}`, "POST", { reason });

export const advanceTcmDecoctionApi = (id: string, action: "soak" | "decoct" | "pack" | "complete" | "verify", reason = "") =>
  request<TcmWorkspace>(`/tcm-pharmacy/prescriptions/${encodeURIComponent(id)}/decoction/${action}`, "POST", { reason });

export const callTcmPickupApi = (id: string) =>
  request<TcmWorkspace>(`/tcm-pharmacy/prescriptions/${encodeURIComponent(id)}/call`, "POST");

export const collectTcmPickupApi = (id: string, reason = "身份已核验") =>
  request<TcmWorkspace>(`/tcm-pharmacy/prescriptions/${encodeURIComponent(id)}/collect`, "POST", { reason });

export const markTcmExceptionApi = (id: string, reason: string) =>
  request<TcmWorkspace>(`/tcm-pharmacy/prescriptions/${encodeURIComponent(id)}/exception`, "POST", { reason });

export const getTcmDisplayApi = async () => {
  const result = await clinicFetch("/tcm-pharmacy/display", { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<TcmDisplaySnapshot>(result));
};

export const getPendingTcmAnnouncementsApi = async () => {
  const result = await clinicFetch("/tcm-pharmacy/announcements/pending", { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<{ rows: TcmAnnouncement[] }>(result));
};

export const markTcmAnnouncementPlayedApi = (id: string) =>
  request<{ id: string; status: string }>(`/tcm-pharmacy/announcements/${encodeURIComponent(id)}/played`, "POST");

export const resetTcmDemoApi = () => request("/tcm-pharmacy/demo/reset", "POST");
