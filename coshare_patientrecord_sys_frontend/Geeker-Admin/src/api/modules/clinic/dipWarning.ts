import { clinicFetch, clinicJsonHeaders, parseClinicApiResponse } from "./http";
import { authHeaders } from "../authToken";

/** DIP 费用预警：病组由主诊断匹配，当前费用来自 HIS（截图识别/人工录入）。 */
export type DipStatus = "NORMAL" | "WARNING" | "DANGER" | "PENDING_FEE" | "UNMATCHED";

export interface DipPatientRow {
  patientId: string;
  patientName: string;
  doctor?: string;
  patientStatus?: string;
  encounterId?: string;
  diagnosis: string;
  encounterUpdatedAt?: string;
  matched: boolean;
  categoryCode?: string;
  categoryName?: string;
  groupCode?: string;
  groupName?: string;
  estimatedFee?: number;
  threshold85?: number;
  threshold95?: number;
  currentFee?: number;
  restTo95?: number;
  feeSource?: string;
  feeUpdatedBy?: string;
  feeUpdatedAt?: string;
  status: DipStatus;
  waterline: { ratio: number; level: string } | null;
}

export interface DipSummary {
  total: number;
  normal: number;
  warning: number;
  danger: number;
  pendingFee: number;
  unmatched: number;
  overrunTotal: number;
  feeRecordedAt: string;
}

export interface DipCoverage {
  diagnosis: string;
  count: number;
}

export interface DipBoard {
  patients: DipPatientRow[];
  summary: DipSummary;
  diagnosisCoverage: DipCoverage[];
}

const BASE = "/dip/warning";

export const getDipBoardApi = async () => {
  const result = await clinicFetch(`${BASE}/patients`, { headers: authHeaders() });
  return parseClinicApiResponse<DipBoard>(result);
};

export const saveDipFeeApi = async (body: { patientId: string; currentFee: number; source?: "MANUAL" | "SCREENSHOT" }) => {
  const result = await clinicFetch(`${BASE}/fee`, {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(body)
  });
  return parseClinicApiResponse<{ patientId: string; currentFee: number; feeSource: string; updatedAt: string }>(result);
};
