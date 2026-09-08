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

/** 数据看板 AI 汇总分析：SSE 流式接口（返回原始 Response，由调用方逐块解析 delta 事件） */
export const streamTrendInsightApi = (payload: { days: number; total: number; complaints: string[] }, signal?: AbortSignal) =>
  fetch("/clinic-api/home/trend-insight/stream", {
    method: "POST",
    headers: { ...authHeaders(), "Content-Type": "application/json" },
    body: JSON.stringify(payload),
    signal
  });
