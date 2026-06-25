import type { ResultData } from "@/api/interface";
import { authHeaders, handleUnauthorizedResponse } from "../authToken";

export interface ClinicStoredFile {
  fileName: string;
  url: string;
  storagePath: string;
  size: number;
  mimeType: string;
  sha256?: string;
}

export interface ClinicStoreFileParams {
  fileName: string;
  contentDataUrl: string;
  patientId?: string;
  department?: string;
  operator?: string;
  operatorRole?: string;
  type?: string;
  typeLabel?: string;
}

const CLINIC_API_FILES_URL = import.meta.env.VITE_CLINIC_API_FILES_URL || "/clinic-api/files";
const API_UNAVAILABLE_MESSAGE = "本地病历文件服务未连接，请确认后端服务正在运行";

const parseClinicFileResponse = async (result: Response) => {
  if (result.status === 401) {
    handleUnauthorizedResponse();
  }
  const text = await result.text();
  if (!text.trim()) {
    throw new Error(result.ok ? "本地病历文件服务返回为空" : `${API_UNAVAILABLE_MESSAGE}（HTTP ${result.status}）`);
  }
  try {
    return JSON.parse(text) as ResultData<ClinicStoredFile>;
  } catch {
    throw new Error(result.ok ? "本地病历文件服务返回格式异常" : `${API_UNAVAILABLE_MESSAGE}（HTTP ${result.status}）`);
  }
};

export const storeClinicFileApi = async (params: ClinicStoreFileParams) => {
  let result: Response;
  try {
    result = await fetch(CLINIC_API_FILES_URL, {
      method: "POST",
      headers: authHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify(params)
    });
  } catch {
    throw new Error(API_UNAVAILABLE_MESSAGE);
  }
  const payload = await parseClinicFileResponse(result);
  if (!result.ok) throw new Error(payload.msg || "文件写入失败");
  if (!payload.data) throw new Error("本地病历文件服务未返回文件索引");
  return payload;
};

export const fetchClinicFileBlobUrl = async (url: string) => {
  let result: Response;
  try {
    result = await fetch(url, { headers: authHeaders() });
  } catch {
    throw new Error(API_UNAVAILABLE_MESSAGE);
  }
  if (result.status === 401) {
    handleUnauthorizedResponse();
  }
  if (!result.ok) {
    throw new Error(`附件读取失败（HTTP ${result.status}）`);
  }
  const blob = await result.blob();
  return URL.createObjectURL(blob);
};
