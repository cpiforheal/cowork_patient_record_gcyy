import { authHeaders, handleUnauthorizedResponse } from "../authToken";
import { clinicFetch, clinicJsonHeaders, clinicResponse, parseClinicApiResponse } from "./http";

export interface FollowUpImage {
  id: string;
  fileName: string;
  url?: string;
}

export interface FollowUpVisit {
  id: string;
  encounterId?: string;
  seq: number;
  reason: string;
  conditionNote: string;
  nextReviewDate: string;
  status: string;
  createdBy: string;
  createdByRole: string;
  createdAt: string;
  /** 最近一次医生校准留痕（内容编辑不改创建时间） */
  updatedBy?: string;
  updatedByRole?: string;
  updatedAt?: string;
  images: FollowUpImage[];
}

export interface FollowUpCreatePayload {
  patientCaseId: string;
  encounterId?: string;
  reason: string;
  conditionNote: string;
  nextReviewDate: string;
  images?: { fileName: string; dataUrl: string }[];
}

export const loadFollowUpVisitsApi = async (patientCaseId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(`/follow-up/visits?patientCaseId=${encodeURIComponent(patientCaseId)}`, {
    headers: authHeaders(),
    signal
  });
  const data = await parseClinicApiResponse<{
    encounterId: string;
    canManage: boolean;
    visits: FollowUpVisit[];
  }>(result);
  return clinicResponse(data, "复诊记录已加载");
};

export const createFollowUpVisitApi = async (payload: FollowUpCreatePayload, signal?: AbortSignal) => {
  const result = await clinicFetch("/follow-up/visits", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload),
    signal
  });
  const data = await parseClinicApiResponse<FollowUpVisit>(result);
  return clinicResponse(data, "复诊记录已创建");
};

export interface FollowUpUpdatePayload {
  reason: string;
  conditionNote: string;
  nextReviewDate: string;
}

/** 编辑复诊记录内容（医生岗回看校准）：仅内容字段，创建时间由系统精确记录不可改 */
export const updateFollowUpVisitApi = async (visitId: string, payload: FollowUpUpdatePayload, signal?: AbortSignal) => {
  const result = await clinicFetch(`/follow-up/visits/${encodeURIComponent(visitId)}`, {
    method: "PUT",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload),
    signal
  });
  const data = await parseClinicApiResponse<FollowUpVisit>(result);
  return clinicResponse(data, "复诊记录已更新");
};

export const addFollowUpVisitImageApi = async (
  visitId: string,
  payload: { fileName: string; dataUrl: string },
  signal?: AbortSignal
) => {
  const result = await clinicFetch(`/follow-up/visits/${encodeURIComponent(visitId)}/images`, {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload),
    signal
  });
  const data = await parseClinicApiResponse<FollowUpVisit>(result);
  return clinicResponse(data, "复诊图片已上传");
};

export const removeFollowUpVisitImageApi = async (visitId: string, imageId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(
    `/follow-up/visits/${encodeURIComponent(visitId)}/images/${encodeURIComponent(imageId)}`,
    { method: "DELETE", headers: authHeaders(), signal }
  );
  const data = await parseClinicApiResponse<FollowUpVisit>(result);
  return clinicResponse(data, "复诊图片已删除");
};

export const fetchFollowUpImageApi = async (imageId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(`/follow-up/visits/images/${encodeURIComponent(imageId)}/file`, {
    headers: authHeaders(),
    signal
  });
  if (result.status === 401) {
    handleUnauthorizedResponse();
  }
  if (!result.ok) {
    throw new Error("复诊图片加载失败");
  }
  const blob = await result.blob();
  return URL.createObjectURL(blob);
};

export interface RecallRow {
  visitId: string;
  patientCaseId: string;
  seq: number;
  dueDate: string;
  overdueDays: number;
  reason: string;
  conditionNote: string;
  createdBy: string;
  name: string;
  phone: string;
  address: string;
  age: string;
  gender: string;
  surgery: string;
  lastContactAt?: string | null;
  priority?: "CRITICAL" | "TODAY" | "TOMORROW" | "UPCOMING";
  node?: string;
  department?: string;
  responsible?: string;
}

export interface RecallSummary {
  overdue: RecallRow[];
  dueSoon: RecallRow[];
  upcomingCount: number;
  generatedAt: string;
}

/** 复查召回看板：到期/逾期未复查患者清单（医生/管理员/检查室） */
export const loadRecallSummaryApi = async (signal?: AbortSignal) => {
  const result = await clinicFetch("/follow-up/recall/summary", { headers: authHeaders(), signal });
  const data = await parseClinicApiResponse<RecallSummary>(result);
  return clinicResponse(data, "复查召回清单已加载");
};

/** 标记已联系（审计留痕，避免重复打扰） */
export const markRecallContactedApi = async (visitId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(`/follow-up/recall/visits/${encodeURIComponent(visitId)}/contact`, {
    method: "POST",
    headers: authHeaders(),
    signal
  });
  const data = await parseClinicApiResponse<{ visitId: string; contactedAt: string; contactedBy: string }>(result);
  return clinicResponse(data, "已标记联系");
};

export interface FollowUpStatistics {
  basis: "due" | "contact";
  from: string;
  to: string;
  total: number;
  completed: number;
  onTime: number;
  overduePending: number;
  completionRate: number;
  onTimeRate: number;
  trend: Array<{ label: string; total: number; completed: number; onTime: number }>;
  departments: Array<{ label: string; total: number; completed: number; onTime: number }>;
  operators: Array<{ label: string; total: number; completed: number; onTime: number }>;
  details: Array<RecallRow & { contactedAt?: string | null; onTime: boolean; operator?: string }>;
}

export const loadFollowUpStatisticsApi = async (params: { from?: string; to?: string; basis?: "due" | "contact" }, signal?: AbortSignal) => {
  const query = new URLSearchParams();
  if (params?.from) query.set("from", params.from);
  if (params?.to) query.set("to", params.to);
  query.set("basis", params?.basis || "due");
  const result = await clinicFetch(`/follow-up/statistics?${query.toString()}`, { headers: authHeaders(), signal });
  const data = await parseClinicApiResponse<FollowUpStatistics>(result);
  return clinicResponse(data, "随访统计已加载");
};
