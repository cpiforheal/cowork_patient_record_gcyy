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
  /** 是否已触达（存在联系记录） */
  reached?: boolean;
  /** 回院确认日期，空表示未回院（A2） */
  arrivedAt?: string;
  arrivedBy?: string;
  arrivedEncounterId?: string;
  priority?: "CRITICAL" | "TODAY" | "TOMORROW" | "UPCOMING";
  node?: string;
  department?: string;
  responsible?: string;
}

export interface RecallSummary {
  overdue: RecallRow[];
  dueSoon: RecallRow[];
  /** 已确认回院的节点（A2）：单独列出便于核对与撤销 */
  arrived?: RecallRow[];
  upcomingCount: number;
  generatedAt: string;
}

/** 复查召回看板：到期/逾期未复查患者清单（医生/管理员/检查室） */
export const loadRecallSummaryApi = async (signal?: AbortSignal) => {
  const result = await clinicFetch("/follow-up/recall/summary", { headers: authHeaders(), signal });
  const data = await parseClinicApiResponse<RecallSummary>(result);
  return clinicResponse(data, "复查召回清单已加载");
};

/** 标记已联系（审计留痕，避免重复打扰）。注意：只表示"打过电话"，不代表随访完成。 */
export const markRecallContactedApi = async (visitId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(`/follow-up/recall/visits/${encodeURIComponent(visitId)}/contact`, {
    method: "POST",
    headers: authHeaders(),
    signal
  });
  const data = await parseClinicApiResponse<{ visitId: string; contactedAt: string; contactedBy: string }>(result);
  return clinicResponse(data, "已标记联系");
};

/** 回院确认（A2）：记录患者实际回院，是依从性统计的事实来源。日期留空表示按今天。 */
export const markRecallArrivedApi = async (visitId: string, arrivedAt?: string, encounterId?: string, signal?: AbortSignal) => {
  const result = await clinicFetch(`/follow-up/recall/visits/${encodeURIComponent(visitId)}/arrived`, {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify({ arrivedAt: arrivedAt || "", encounterId: encounterId || "" }),
    signal
  });
  const data = await parseClinicApiResponse<{ visitId: string; arrivedAt: string; arrivedBy: string }>(result);
  return clinicResponse(data, "已确认回院");
};

/** 撤销回院确认（误点纠正）。 */
export const undoRecallArrivedApi = async (visitId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(`/follow-up/recall/visits/${encodeURIComponent(visitId)}/arrived`, {
    method: "DELETE",
    headers: authHeaders(),
    signal
  });
  const data = await parseClinicApiResponse<{ visitId: string; arrivedAt: null }>(result);
  return clinicResponse(data, "已撤销回院确认");
};

export interface FollowUpStatistics {
  /** 归集口径：节点到期月 / 实际联系月 / 实际回院月 */
  basis: "due" | "contact" | "arrival";
  from: string;
  to: string;
  /** 分母：应随访节点数。以下四个率共用此分母，因此可横向比较 */
  total: number;
  reached: number;
  /** 已回院数（= 完成数） */
  arrived: number;
  arrivedOnTime: number;
  notArrived: number;
  /** 触达率 = reached / total */
  reachRate: number;
  /** 回院率 = arrived / total（依从性主指标） */
  arrivalRate: number;
  /** 按时回院率 = arrivedOnTime / total */
  onTimeArrivalRate: number;
  trend: Array<{ label: string; total: number; reached?: number; arrived?: number; arrivedOnTime?: number; completed?: number; onTime?: number }>;
  departments: Array<{ label: string; total: number; reached?: number; arrived?: number; arrivedOnTime?: number; completed?: number; onTime?: number }>;
  operators: Array<{ label: string; total: number; reached?: number; arrived?: number; arrivedOnTime?: number; completed?: number; onTime?: number }>;
  details: Array<RecallRow & { contactedAt?: string | null; arrivedAt?: string; reached?: boolean; completed?: boolean; onTime: boolean; operator?: string }>;
}

export const loadFollowUpStatisticsApi = async (
  params: { from?: string; to?: string; basis?: "due" | "contact" | "arrival" },
  signal?: AbortSignal
) => {
  const query = new URLSearchParams();
  if (params?.from) query.set("from", params.from);
  if (params?.to) query.set("to", params.to);
  query.set("basis", params?.basis || "due");
  const result = await clinicFetch(`/follow-up/statistics?${query.toString()}`, { headers: authHeaders(), signal });
  const data = await parseClinicApiResponse<FollowUpStatistics>(result);
  return clinicResponse(data, "随访统计已加载");
};
