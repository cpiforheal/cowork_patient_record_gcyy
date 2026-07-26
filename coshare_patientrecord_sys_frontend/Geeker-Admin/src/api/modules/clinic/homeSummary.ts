import { clinicFetch, clinicResponse, parseClinicApiResponse } from "./http";
import { authHeaders } from "../authToken";

/** 全院轻量运营概况：纯计数、无隐私字段，所有登录岗位可读。 */
export interface HomeSummary {
  /** 今日登记就诊数 */
  todayRegistered: number;
  /** 当前候诊人数（检查+接诊） */
  queueWaiting: number;
  /** 今日完成就诊数 */
  queueCompletedToday: number;
  /** 中药房待取药份数 */
  tcmReady: number;
  /** 中药房制作中份数（调剂+代煎） */
  tcmInProgress: number;
  serverTime: string;
}

export const getHomeSummaryApi = async () => {
  const result = await clinicFetch("/home/summary", { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<HomeSummary>(result));
};
