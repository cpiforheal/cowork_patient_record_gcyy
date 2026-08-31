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
