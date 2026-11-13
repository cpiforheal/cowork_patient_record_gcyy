import { clinicFetch, clinicJsonHeaders, parseClinicApiResponse } from "./http";
import { authHeaders } from "../authToken";

export type EyeSide = "LEFT" | "RIGHT" | "BOTH";
export type EyeDropState = "UPCOMING" | "DUE" | "LATE" | "MISSED" | "TAKEN" | "TAKEN_LATE";

export interface EyeDropItem {
  planId: string;
  drugCode: string;
  drugName: string;
  slotCode: string;
  slotLabel: string;
  plannedTime: string;
  windowMinutes: number;
  state: EyeDropState;
  canVoid: boolean;
  logId?: string;
  eyeSide?: EyeSide;
  takenAt?: string;
  late?: boolean;
  minutesLate?: number;
  note?: string;
}

export interface EyeDropMeasurement {
  id: string;
  measuredAt: string;
  iopLeft: number | null;
  iopRight: number | null;
  symptoms: string[];
  note: string;
  doseLogId?: string | null;
}

export interface EyeDropToday {
  date: string;
  now: string;
  items: EyeDropItem[];
  measurements: EyeDropMeasurement[];
  summary: {
    total: number;
    due: number;
    taken: number;
    late: number;
    missed: number;
    upcoming: number;
    adherence: number | null;
  };
}

export interface EyeDropMonthDay {
  date: string;
  day: number;
  total: number;
  due: number;
  taken: number;
  late: number;
  missed: number;
  adherence: number | null;
}

export interface EyeDropMonth {
  month: string;
  monthLabel: string;
  days: EyeDropMonthDay[];
  peak: number;
  summary: { due: number; taken: number; late: number; missed: number; adherence: number | null };
}

const BASE = "/eye-drop";

const send = async <T>(path: string, method: string, body?: unknown) => {
  const result = await clinicFetch(path, {
    method,
    headers: body === undefined ? authHeaders() : clinicJsonHeaders(),
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return parseClinicApiResponse<T>(result);
};

export const getEyeDropTodayApi = async (date?: string) => {
  const query = new URLSearchParams();
  if (date) query.set("date", date);
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return send<EyeDropToday>(`${BASE}/today${suffix}`, "GET");
};

export const getEyeDropMonthApi = async (month: string) => {
  return send<EyeDropMonth>(`${BASE}/month?month=${encodeURIComponent(month)}`, "GET");
};

export const createEyeDropDoseApi = (body: {
  planId: string;
  eyeSide?: EyeSide;
  takenAt?: string;
  note?: string;
  requestId?: string;
}) => send<Record<string, any>>(`${BASE}/doses`, "POST", body);

export const voidEyeDropDoseApi = (id: string) => send<Record<string, any>>(`${BASE}/doses/${id}/void`, "POST");

export const updateEyeDropPlanApi = (planId: string, plannedTime: string) =>
  send<Record<string, any>>(`${BASE}/plan/${planId}`, "PUT", { plannedTime });

export const createEyeDropMeasurementApi = (body: {
  measuredAt?: string;
  iopLeft?: number | null;
  iopRight?: number | null;
  symptoms?: string[];
  note?: string;
  doseLogId?: string;
}) => send<Record<string, any>>(`${BASE}/measurements`, "POST", body);

export const deleteEyeDropMeasurementApi = (id: string) => send<Record<string, any>>(`${BASE}/measurements/${id}`, "DELETE");

export const downloadEyeDropCsv = async (month: string) => {
  const result = await clinicFetch(`${BASE}/export.csv?month=${encodeURIComponent(month)}`, { headers: authHeaders() });
  if (!result.ok) throw new Error("导出失败，请稍后重试");
  const blob = await result.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `眼药水滴注记录-${month.replace("-", "")}.csv`;
  anchor.click();
  URL.revokeObjectURL(url);
};
