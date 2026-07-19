import { authHeaders } from "../authToken";
import { clinicFetch, clinicJsonHeaders, clinicResponse, parseClinicApiResponse } from "./http";
import type {
  GeneratedMedicalRecord,
  MedicalRecordGenerateResult,
  MedicalRecordPrecheckResult,
  MedicalRecordTemplateStatus,
  MedicalRecordWorkspaceSaveResult
} from "./types";

export const getMedicalRecordTemplateApi = async () => {
  const result = await clinicFetch("/medical-record/templates", { headers: authHeaders() });
  const data = await parseClinicApiResponse<MedicalRecordTemplateStatus>(result);
  return clinicResponse(data);
};

export const getGeneratedMedicalRecordVersionsApi = async (patientId: string, limit = 50) => {
  const params = new URLSearchParams({ patientId });
  if (limit > 0) params.set("limit", String(limit));
  const result = await clinicFetch(`/medical-record/versions?${params.toString()}`, {
    headers: authHeaders()
  });
  const data = await parseClinicApiResponse<{ versions: GeneratedMedicalRecord[] }>(result);
  return clinicResponse(data.versions ?? []);
};

export const precheckMedicalRecordApi = async (patientId: string) => {
  const result = await clinicFetch("/medical-record/precheck", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify({ patientId, mode: "target" })
  });
  const data = await parseClinicApiResponse<MedicalRecordPrecheckResult>(result);
  return clinicResponse(data);
};

export const saveMedicalRecordWorkspaceApi = async (patientId: string, values: Record<string, string>) => {
  const result = await clinicFetch("/medical-record/workspace", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify({ patientId, values })
  });
  const data = await parseClinicApiResponse<MedicalRecordWorkspaceSaveResult>(result);
  return clinicResponse(data, "目标病历填写已保存");
};

export const generateMedicalRecordApi = async (patientId: string) => {
  const result = await clinicFetch("/medical-record/generate", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify({ patientId, mode: "target" })
  });
  const data = await parseClinicApiResponse<MedicalRecordGenerateResult>(result);
  return clinicResponse(data, "目标病历已生成");
};

export const generateInpatientAiMedicalRecordApi = async (params: {
  patientId: string;
  encounterId: string;
  sourceRecordId: string;
  prompt: string;
}) => {
  const result = await clinicFetch("/medical-record/generate-inpatient-ai", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(params)
  });
  const data = await parseClinicApiResponse<MedicalRecordGenerateResult>(result);
  return clinicResponse(data, "豆包住院病历草稿已生成");
};

export const finalizeMedicalRecordApi = async (id: string) => {
  const result = await clinicFetch("/medical-record/finalize", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify({ id })
  });
  const data = await parseClinicApiResponse<{ record: GeneratedMedicalRecord }>(result);
  return clinicResponse(data.record, "目标病历已定稿");
};

export const voidMedicalRecordApi = async (id: string, reason: string) => {
  const result = await clinicFetch("/medical-record/void", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify({ id, reason })
  });
  const data = await parseClinicApiResponse<{ record: GeneratedMedicalRecord }>(result);
  return clinicResponse(data.record, "目标病历版本已作废");
};

export const downloadMedicalRecordApi = async (record: GeneratedMedicalRecord) => {
  const result = await clinicFetch(`/medical-record/download?id=${encodeURIComponent(record.id)}`, {
    headers: authHeaders()
  });
  if (!result.ok) {
    await parseClinicApiResponse(result);
    return clinicResponse(null);
  }
  const blob = await result.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = record.fileName || `医生目标病历-V${record.version}.docx`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 500);
  return clinicResponse(null, "目标病历 docx 已下载");
};
