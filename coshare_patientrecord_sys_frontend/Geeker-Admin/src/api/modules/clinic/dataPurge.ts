import { clinicFetch, clinicJsonHeaders, clinicResponse, parseClinicApiResponse } from "./http";
import { authHeaders } from "../authToken";

export interface DataPurgePreview {
  token: string;
  expiresAt: string;
  confirmationText: string;
  counts: Record<string, number>;
  managedFiles: {
    fileCount: number;
    totalBytes: number;
    directories: string[];
  };
  retained: Record<string, unknown>;
  databaseRevision: string;
  warnings: string[];
}

export interface DataPurgeStartRequest {
  password: string;
  previewToken: string;
  confirmationText: string;
}

export interface DataPurgeRun {
  runId: string;
  status: string;
  databaseCommitted: boolean;
  filesQuarantined: boolean;
  backupDir: string;
  backupSha256: string;
  beforeCounts: Record<string, number>;
  afterCounts: Record<string, number>;
  errorMessage: string;
  createdAt: string;
  updatedAt: string;
  recovery: Record<string, unknown>;
}

export const getDataPurgePreviewApi = async () => {
  const result = await clinicFetch("/maintenance/data-purge/preview", { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<DataPurgePreview>(result));
};

export const startDataPurgeApi = async (payload: DataPurgeStartRequest) => {
  const result = await clinicFetch("/maintenance/data-purge", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload)
  });
  return clinicResponse(await parseClinicApiResponse<DataPurgeRun>(result));
};

export const getDataPurgeRunApi = async (runId: string) => {
  const result = await clinicFetch(`/maintenance/data-purge/runs/${encodeURIComponent(runId)}`, { headers: authHeaders() });
  return clinicResponse(await parseClinicApiResponse<DataPurgeRun>(result));
};

export const resumeDataPurgeFilesApi = async (runId: string) => {
  const result = await clinicFetch(`/maintenance/data-purge/runs/${encodeURIComponent(runId)}/resume-files`, {
    method: "POST",
    headers: authHeaders()
  });
  return clinicResponse(await parseClinicApiResponse<DataPurgeRun>(result));
};
