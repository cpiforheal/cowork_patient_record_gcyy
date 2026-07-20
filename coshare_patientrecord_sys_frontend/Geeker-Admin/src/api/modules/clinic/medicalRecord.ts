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

export interface MedicalRecordGenerationScope {
  patientId?: string;
  encounterId?: string;
  patientCaseId?: string;
}

export const getGeneratedMedicalRecordVersionsApi = async (scope: string | MedicalRecordGenerationScope, limit = 50) => {
  const params = new URLSearchParams(
    typeof scope === "string"
      ? { patientId: scope }
      : scope.encounterId
        ? { encounterId: scope.encounterId }
        : { patientId: scope.patientId || "" }
  );
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

export const generateMedicalRecordApi = async (scope: string | MedicalRecordGenerationScope) => {
  const generationScope = typeof scope === "string" ? { patientId: scope } : scope;
  const result = await clinicFetch("/medical-record/generate", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify({ ...generationScope, mode: "target" })
  });
  const data = await parseClinicApiResponse<MedicalRecordGenerateResult>(result);
  return clinicResponse(data, "目标病历已生成");
};

export const generateInpatientAiMedicalRecordApi = async (params: {
  patientId?: string;
  encounterId?: string;
  sourceRecordId: string;
  prompt: string;
  referenceDocument: File;
}) => {
  const body = new FormData();
  if (params.patientId) body.append("patientId", params.patientId);
  if (params.encounterId) body.append("encounterId", params.encounterId);
  body.append("sourceRecordId", params.sourceRecordId);
  body.append("prompt", params.prompt);
  body.append("referenceDocument", params.referenceDocument, params.referenceDocument.name);
  const result = await clinicFetch("/medical-record/generate-inpatient-ai", {
    method: "POST",
    headers: authHeaders(),
    body
  });
  const data = await parseClinicApiResponse<MedicalRecordGenerateResult>(result);
  return clinicResponse(data, "AI 住院病历草稿已生成");
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

export const deleteMedicalRecordApi = async (id: string) => {
  const result = await clinicFetch(`/medical-record/${encodeURIComponent(id)}`, {
    method: "DELETE",
    headers: authHeaders()
  });
  const data = await parseClinicApiResponse<{ id: string; version: number; fileDeleted: boolean }>(result);
  return clinicResponse(data, "目标病历历史版本及对应文件已删除");
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
