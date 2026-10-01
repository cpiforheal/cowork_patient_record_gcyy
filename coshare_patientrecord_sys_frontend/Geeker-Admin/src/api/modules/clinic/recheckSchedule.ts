import { clinicFetch, clinicJsonHeaders, parseClinicApiResponse } from "./http";
import { authHeaders } from "../authToken";

/** 复查预约登记：检查室共享登记表（行=日期，格=患者）。 */
export type RecheckStatus = "PLANNED" | "ARRIVED" | "ABSENT" | "RESCHEDULED" | "CANCELLED";

export interface RecheckEntry {
  id: string;
  planDate: string;
  patientName: string;
  phone: string;
  note: string;
  status: RecheckStatus;
  statusNote: string;
  sourceId: string | null;
  rescheduledTo: string | null;
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
}

export interface RecheckStats {
  planned: number;
  arrived: number;
  absent: number;
  pending: number;
  rescheduled: number;
  arrivalRate: number | null;
  unconfirmed: number;
}

export interface RecheckDay {
  date: string;
  weekday: string;
  relation: "past" | "today" | "future";
  stats: RecheckStats;
  entries: RecheckEntry[];
}

export interface RecheckBoard {
  from: string;
  to: string;
  today: string;
  days: RecheckDay[];
  total: RecheckStats;
  unconfirmedPast: number;
  canEdit: boolean;
}

const BASE = "/recheck-schedule";

const send = async <T>(path: string, method: string, body?: unknown) => {
  const result = await clinicFetch(path, {
    method,
    headers: clinicJsonHeaders(),
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return parseClinicApiResponse<T>(result);
};

export const getRecheckBoardApi = async (from: string, to: string) => {
  const query = new URLSearchParams({ from, to });
  const result = await clinicFetch(`${BASE}?${query.toString()}`, { headers: authHeaders() });
  return parseClinicApiResponse<RecheckBoard>(result);
};

export const createRecheckApi = (body: { planDate: string; patientName: string; phone?: string; note?: string }) =>
  send<RecheckEntry>(BASE, "POST", body);

export const updateRecheckApi = (id: string, body: Partial<Pick<RecheckEntry, "planDate" | "patientName" | "phone" | "note">>) =>
  send<RecheckEntry>(`${BASE}/${encodeURIComponent(id)}`, "PUT", body);

export const markRecheckApi = (id: string, status: "ARRIVED" | "ABSENT" | "PLANNED", statusNote = "") =>
  send<RecheckEntry>(`${BASE}/${encodeURIComponent(id)}/status`, "POST", { status, statusNote });

export const rescheduleRecheckApi = (id: string, newDate: string, reason = "") =>
  send<RecheckEntry>(`${BASE}/${encodeURIComponent(id)}/reschedule`, "POST", { newDate, reason });

export const cancelRecheckApi = (id: string) => send<RecheckEntry>(`${BASE}/${encodeURIComponent(id)}/cancel`, "POST");
