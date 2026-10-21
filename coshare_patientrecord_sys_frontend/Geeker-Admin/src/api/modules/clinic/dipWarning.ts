import { clinicFetch, clinicJsonHeaders, parseClinicApiResponse } from "./http";
import { authHeaders } from "../authToken";

/** DIP 费用预警：字段对齐 HIS 住院费用界面，费用水位在本系统内计算与着色。 */
export type DipStatus = "NORMAL" | "WARNING" | "DANGER" | "PENDING_FEE" | "UNMATCHED";

export interface DipPatientRow {
  patientId: string;
  patientName: string;
  gender?: string;
  age?: number | null;
  diagnosis: string;
  matched: boolean;
  groupSource?: "AUTO" | "MANUAL";
  autoGroupCode?: string | null;
  autoGroupName?: string | null;
  attendingDoctor?: string;
  admissionDate?: string;
  hospitalDays?: number | null;
  admissionNo?: string;
  patientType?: string;
  bedNo?: string;
  nursingLevel?: string;
  depositTotal?: number | null;
  depositBalance?: number | null;
  categoryCode?: string;
  categoryName?: string;
  groupCode?: string;
  groupName?: string;
  estimatedFee?: number;
  groupStandardFee?: number;
  feeOverride?: number | null;
  feeSourceType?: "GROUP" | "PATIENT";
  threshold85?: number;
  threshold95?: number;
  unsettledFee?: number;
  restTo95?: number | null;
  feeSource?: string;
  feeUpdatedBy?: string;
  feeUpdatedAt?: string;
  feeVoided?: boolean;
  voidedAt?: string;
  voidedBy?: string;
  voidReason?: string;
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
}

export interface DipBoard {
  patients: DipPatientRow[];
  summary: DipSummary;
  generatedAt: string;
}

export interface DipGroupOption {
  categoryCode: string;
  categoryName: string;
  groupCode: string;
  groupName: string;
  estimatedFee: number;
}

export interface DipFeePayload {
  patientId: string;
  unsettledFee: number;
  source?: "MANUAL" | "SCREENSHOT";
  admissionNo?: string;
  patientType?: string;
  bedNo?: string;
  nursingLevel?: string;
  depositTotal?: number | null;
  depositBalance?: number | null;
  admissionDate?: string;
  attendingDoctor?: string;
  remark?: string;
}

const BASE = "/dip/warning";

export const getDipBoardApi = async () => {
  const result = await clinicFetch(`${BASE}/patients`, { headers: authHeaders() });
  return parseClinicApiResponse<DipBoard>(result);
};

export const getDipGroupsApi = async () => {
  const result = await clinicFetch(`${BASE}/groups`, { headers: authHeaders() });
  return parseClinicApiResponse<DipGroupOption[]>(result);
};

export const saveDipFeeApi = async (body: DipFeePayload) => {
  const result = await clinicFetch(`${BASE}/fee`, {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(body)
  });
  return parseClinicApiResponse<{ patientId: string; unsettledFee: number; updatedAt: string }>(result);
};

export const saveDipGroupApi = async (body: { patientId: string; groupCode: string; note?: string }) => {
  const result = await clinicFetch(`${BASE}/group`, {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(body)
  });
  return parseClinicApiResponse<{ patientId: string; groupCode: string }>(result);
};

/** DIP 工作台新增患者（HIS 之外）。 */
export interface DipCreatePatientPayload {
  patientName: string;
  diagnosis: string;
  gender?: string;
  age?: string;
  admissionNo?: string;
  admissionDate?: string;
  attendingDoctor?: string;
  groupCode?: string;
  unsettledFee?: number | null;
  depositBalance?: number | null;
  note?: string;
}

export const createDipPatientApi = async (body: DipCreatePatientPayload) => {
  const result = await clinicFetch(`${BASE}/patients`, {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(body)
  });
  return parseClinicApiResponse<{ patientId: string; encounterId: string }>(result);
};

export const voidDipFeeApi = async (body: { patientId: string; reason: string }) => {
  const result = await clinicFetch(`${BASE}/fee/void`, {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(body)
  });
  return parseClinicApiResponse<{ patientId: string; recordStatus: string; voidedAt: string }>(result);
};

export const saveDipGroupFeeApi = async (body: {
  patientId: string;
  estimatedFeeOverride: number | null;
  note?: string;
}) => {
  const result = await clinicFetch(`${BASE}/group-fee`, {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(body)
  });
  return parseClinicApiResponse<{ patientId: string; estimatedFeeOverride: number | null }>(result);
};

export interface DipChangeLog {
  id: string;
  patientId: string;
  scope: "PATIENT_CREATE" | "FEE" | "FEE_VOID" | "GROUP" | "GROUP_FEE" | "CATALOG";
  targetCode: string;
  beforeValue: string;
  afterValue: string;
  note: string;
  operator: string;
  operatorRole: string;
  createdAt: string;
}

export const getDipHistoryApi = async (patientId: string) => {
  const result = await clinicFetch(`${BASE}/history?patientId=${encodeURIComponent(patientId)}`, {
    headers: authHeaders()
  });
  return parseClinicApiResponse<DipChangeLog[]>(result);
};

/** 管理员病组目录。 */
export interface DipCatalogGroup {
  id: string;
  categoryCode: string;
  categoryName: string;
  groupCode: string;
  groupName: string;
  estimatedFee: number;
  matchKeywords: string;
  keywordPriority: number;
  sortNo: number;
  status: "ACTIVE" | "DISABLED";
  updatedAt: string;
}

export interface DipCatalogGroupPayload {
  categoryCode: string;
  categoryName: string;
  groupCode: string;
  groupName: string;
  estimatedFee: number;
  matchKeywords: string;
  keywordPriority: number;
  sortNo: number;
}

const CATALOG = "/dip/catalog/groups";

export const getDipCatalogApi = async () => {
  const result = await clinicFetch(CATALOG, { headers: authHeaders() });
  return parseClinicApiResponse<DipCatalogGroup[]>(result);
};

export const createDipCatalogGroupApi = async (body: DipCatalogGroupPayload) => {
  const result = await clinicFetch(CATALOG, {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(body)
  });
  return parseClinicApiResponse<DipCatalogGroup>(result);
};

export const updateDipCatalogGroupApi = async (groupCode: string, body: DipCatalogGroupPayload) => {
  const result = await clinicFetch(`${CATALOG}/${encodeURIComponent(groupCode)}`, {
    method: "PUT",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(body)
  });
  return parseClinicApiResponse<DipCatalogGroup>(result);
};

export const toggleDipCatalogGroupApi = async (groupCode: string, status: "ACTIVE" | "DISABLED") => {
  const result = await clinicFetch(`${CATALOG}/${encodeURIComponent(groupCode)}/status`, {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify({ status })
  });
  return parseClinicApiResponse<DipCatalogGroup>(result);
};

export const getDipCatalogHistoryApi = async (groupCode: string) => {
  const result = await clinicFetch(`${CATALOG}/${encodeURIComponent(groupCode)}/history`, {
    headers: authHeaders()
  });
  return parseClinicApiResponse<DipChangeLog[]>(result);
};

/** HIS 费用截图识别结果项；仅用于预览，写入仍走 saveDipFeeApi。 */
export interface DipOcrItem {
  ocrAdmissionNo: string;
  ocrName: string;
  unsettledFee: number | null;
  depositBalance: number | null;
  recordedAt: string;
  confidence: number;
  matchStatus: "MATCHED" | "AMBIGUOUS" | "UNMATCHED";
  matchReason: string;
  patientId: string;
  patientName: string;
  diagnosis?: string;
  groupName?: string;
  previousFee?: number | null;
  waterlineRatio?: number | null;
}

export interface DipOcrResult {
  items: DipOcrItem[];
  recognized: number;
  matched: number;
  pending: number;
}

export const parseDipScreenshotApi = async (file: File) => {
  const form = new FormData();
  form.append("file", file);
  const result = await clinicFetch(`${BASE}/ocr`, {
    method: "POST",
    headers: authHeaders(),
    body: form
  });
  return parseClinicApiResponse<DipOcrResult>(result);
};
