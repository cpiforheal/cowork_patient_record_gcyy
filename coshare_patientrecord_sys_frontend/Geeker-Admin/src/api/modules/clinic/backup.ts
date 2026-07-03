import { authHeaders } from "../authToken";
import { clinicFetch, clinicJsonHeaders, clinicResponse, parseClinicApiResponse } from "./http";
import type { BackupConfigPayload, BackupDirectorySelection, BackupRunResult, BackupStatus, MaintenanceStatus } from "./types";

export const getMaintenanceStatusApi = async () => {
  const result = await clinicFetch("/maintenance/status", { headers: authHeaders() });
  const data = await parseClinicApiResponse<MaintenanceStatus>(result);
  return clinicResponse(data);
};

export const getMaintenanceSummaryApi = async () => {
  const result = await clinicFetch("/maintenance/status/summary", { headers: authHeaders() });
  const data = await parseClinicApiResponse<MaintenanceStatus>(result);
  return clinicResponse(data);
};

export const createMaintenanceSnapshotApi = async () => {
  const result = await clinicFetch("/maintenance/snapshot", { method: "POST", headers: authHeaders() });
  const data = await parseClinicApiResponse<{ savedAt: string; snapshotCount: number; revision: string }>(result);
  return clinicResponse(data, "系统快照已生成");
};

export const getBackupStatusApi = async () => {
  const result = await clinicFetch("/maintenance/backup/status", { headers: authHeaders() });
  const data = await parseClinicApiResponse<BackupStatus>(result);
  return clinicResponse(data);
};

export const saveBackupConfigApi = async (payload: BackupConfigPayload) => {
  const result = await clinicFetch("/maintenance/backup/config", {
    method: "PUT",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<BackupStatus>(result);
  return clinicResponse(data, "备份配置已保存");
};

export const chooseBackupDirectoryApi = async (initialDir = "") => {
  const result = await clinicFetch("/maintenance/backup/choose-dir", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify({ initialDir })
  });
  const data = await parseClinicApiResponse<BackupDirectorySelection>(result);
  return clinicResponse(data, "备份目录已选择");
};

export const runBackupNowApi = async () => {
  const result = await clinicFetch("/maintenance/backup/run", { method: "POST", headers: authHeaders() });
  const data = await parseClinicApiResponse<BackupRunResult>(result);
  return clinicResponse(data, "备份已完成");
};
