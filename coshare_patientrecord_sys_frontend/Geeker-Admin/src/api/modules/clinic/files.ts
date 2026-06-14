import type { ResultData } from "@/api/interface";

export interface ClinicStoredFile {
  fileName: string;
  url: string;
  storagePath: string;
  size: number;
  mimeType: string;
}

export interface ClinicStoreFileParams {
  fileName: string;
  contentDataUrl: string;
}

const CLINIC_API_FILES_URL = import.meta.env.VITE_CLINIC_API_FILES_URL || "/clinic-api/files";
const API_UNAVAILABLE_MESSAGE = "本地病历文件服务未连接，请先在项目根目录运行 npm run api 后重试";

const parseClinicFileResponse = async (result: Response) => {
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
      headers: { "Content-Type": "application/json" },
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
