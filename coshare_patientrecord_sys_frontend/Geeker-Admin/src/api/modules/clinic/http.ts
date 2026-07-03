import type { ResultData } from "@/api/interface";
import { authHeaders, handleUnauthorizedResponse, isUnauthorizedApiResponse } from "../authToken";
import { getClinicApiBaseUrl } from "./storage";

const SUCCESS_CODE = 200 as unknown as string;

export const clinicResponse = <T>(data: T, msg = "成功") =>
  Promise.resolve({
    code: SUCCESS_CODE,
    msg,
    data
  } as ResultData<T>);

export const clinicJsonHeaders = () => authHeaders({ "Content-Type": "application/json" });

export const parseClinicApiResponse = async <T>(result: Response): Promise<T> => {
  if (result.status === 401) {
    handleUnauthorizedResponse();
  }
  const text = await result.text();
  if (text.trim().startsWith("<")) {
    throw new Error("业务接口未连通，请确认后端已启动，并检查 /clinic-api 代理或部署转发配置");
  }
  let payload: ResultData<T>;
  try {
    payload = JSON.parse(text) as ResultData<T>;
  } catch {
    throw new Error("业务接口返回格式异常，请检查后端服务状态");
  }
  if (isUnauthorizedApiResponse(result, payload)) {
    handleUnauthorizedResponse(payload.msg || "登录已失效，请重新登录");
  }
  if (!result.ok || String(payload.code) !== "200") {
    throw new Error(payload.msg || `clinic api failed: ${result.status}`);
  }
  return payload.data;
};

export const clinicFetch = (path: string, init?: RequestInit) => fetch(`${getClinicApiBaseUrl()}${path}`, init);
