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

export const storeClinicFileApi = async (params: ClinicStoreFileParams) => {
  const result = await fetch(CLINIC_API_FILES_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(params)
  });
  const payload = (await result.json()) as ResultData<ClinicStoredFile>;
  if (!result.ok) throw new Error(payload.msg || "文件写入失败");
  return payload;
};
