import { clinicFetch, clinicJsonHeaders, clinicResponse, parseClinicApiResponse } from "./http";
import { authHeaders } from "../authToken";

/** 医政早报：每日定时采集的医疗政策/DIP/肛肠学术资讯（管理端）。 */
export interface PolicyBriefItem {
  id: string;
  briefDate: string;
  sourceName: string;
  category: string;
  title: string;
  url: string;
  publishedAt: string;
  aiSummary: string;
  aiModel: string;
  status: "PENDING" | "SUMMARIZED" | "FAILED";
  createdAt: string;
}

export interface PolicyBriefLastRun {
  running: boolean;
  briefDate: string;
  fetched: number;
  fresh: number;
  summarized: number;
  failed: number;
  message: string;
  startedAt: string;
  finishedAt: string;
}

export interface PolicyBriefResult {
  briefDate: string;
  total: number;
  items: PolicyBriefItem[];
  lastRun: PolicyBriefLastRun;
}

export const getPolicyBriefItemsApi = async (date: string, category = "") => {
  const query = new URLSearchParams({ date });
  if (category) query.set("category", category);
  const result = await clinicFetch(`/policy-brief/items?${query.toString()}`, { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<PolicyBriefResult>(result));
};

export const getPolicyBriefLatestApi = async () => {
  const result = await clinicFetch("/policy-brief/latest", { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<PolicyBriefResult>(result));
};

export const triggerPolicyBriefCollectApi = async () => {
  const result = await clinicFetch("/policy-brief/collect", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify({})
  });
  return clinicResponse(await parseClinicApiResponse<{ started: boolean; message: string }>(result));
};
