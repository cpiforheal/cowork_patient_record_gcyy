import { authHeaders } from "../authToken";
import { clinicFetch, clinicJsonHeaders, clinicResponse, parseClinicApiResponse } from "./http";
import type {
  GeneratedMedicalRecord,
  MedicalRecordBuiltinTemplateInspection,
  MedicalRecordDocxDownload,
  MedicalRecordDocxInspection,
  MedicalRecordGenerateResult,
  MedicalRecordPrecheckResult,
  MedicalRecordTemplateStatus,
  MedicalRecordWorkflowMappings,
  MedicalRecordWorkflowSubmitParams,
  MedicalRecordWorkflowTask,
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

const MEDICAL_RECORD_WORKFLOW_TERMINAL_STATUSES = new Set(["SUCCEEDED", "FAILED"]);

const medicalRecordWorkflowAbortError = (message = "病历生成任务轮询已取消") => {
  if (typeof DOMException !== "undefined") return new DOMException(message, "AbortError");
  const error = new Error(message);
  error.name = "AbortError";
  return error;
};

const throwIfMedicalRecordWorkflowAborted = (signal?: AbortSignal) => {
  if (signal?.aborted) throw signal.reason ?? medicalRecordWorkflowAbortError();
};

const waitForMedicalRecordWorkflowPoll = (delayMs: number, signal?: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    throwIfMedicalRecordWorkflowAborted(signal);
    let timer = 0;
    const onAbort = () => {
      window.clearTimeout(timer);
      signal?.removeEventListener("abort", onAbort);
      reject(signal?.reason ?? medicalRecordWorkflowAbortError());
    };
    timer = window.setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, delayMs);
    signal?.addEventListener("abort", onAbort, { once: true });
  });

const parseDownloadFilename = (result: Response, fallback: string) => {
  const disposition = result.headers.get("content-disposition") || "";
  const encodedMatch = disposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (encodedMatch?.[1]) {
    try {
      return decodeURIComponent(encodedMatch[1].replace(/^"|"$/g, ""));
    } catch {
      // Fall through to the regular filename or the safe fallback.
    }
  }
  const match = disposition.match(/filename="?([^";]+)"?/i);
  return match?.[1]?.trim() || fallback;
};

const downloadMedicalRecordV2Docx = async (path: string, fallbackName: string, signal?: AbortSignal) => {
  const result = await clinicFetch(path, { headers: authHeaders(), signal });
  if (!result.ok) {
    await parseClinicApiResponse(result);
    throw new Error("病历 DOCX 下载失败");
  }
  return {
    blob: await result.blob(),
    filename: parseDownloadFilename(result, fallbackName)
  } satisfies MedicalRecordDocxDownload;
};

export interface InspectMedicalRecordDocumentParams extends MedicalRecordGenerationScope {
  document: File;
  signal?: AbortSignal;
}

export const inspectMedicalRecordDocumentV2Api = async (params: InspectMedicalRecordDocumentParams) => {
  const body = new FormData();
  if (params.patientId) body.append("patientId", params.patientId);
  if (params.encounterId) body.append("encounterId", params.encounterId);
  body.append("document", params.document, params.document.name);
  const result = await clinicFetch("/medical-record/v2/inspect", {
    method: "POST",
    headers: authHeaders(),
    body,
    signal: params.signal
  });
  const data = await parseClinicApiResponse<MedicalRecordDocxInspection>(result);
  return clinicResponse(data, "前置病历 DOCX 已完成安全检查");
};

export const submitMedicalRecordWorkflowTaskApi = async (params: MedicalRecordWorkflowSubmitParams, signal?: AbortSignal) => {
  const result = await clinicFetch("/medical-record/v2/tasks", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(params),
    signal
  });
  const data = await parseClinicApiResponse<MedicalRecordWorkflowTask>(result);
  return clinicResponse(data, "病历生成任务已提交");
};

export const inspectBuiltinMedicalRecordTemplateApi = async (params: MedicalRecordGenerationScope, signal?: AbortSignal) => {
  const query = new URLSearchParams(
    params.encounterId ? { encounterId: params.encounterId } : { patientId: params.patientId || "" }
  );
  const result = await clinicFetch(`/medical-record/v2/templates/builtin/inspect?${query.toString()}`, {
    method: "POST",
    headers: authHeaders(),
    signal
  });
  const data = await parseClinicApiResponse<MedicalRecordBuiltinTemplateInspection>(result);
  return clinicResponse(data, "内置住院病历范本已就绪");
};

export const getMedicalRecordWorkflowTaskApi = async (taskId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(`/medical-record/v2/tasks/${encodeURIComponent(taskId)}`, {
    headers: authHeaders(),
    signal
  });
  const data = await parseClinicApiResponse<MedicalRecordWorkflowTask>(result);
  return clinicResponse(data);
};

export const retryMedicalRecordWorkflowTaskApi = async (taskId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(`/medical-record/v2/tasks/${encodeURIComponent(taskId)}/retry`, {
    method: "POST",
    headers: authHeaders(),
    signal
  });
  const data = await parseClinicApiResponse<MedicalRecordWorkflowTask>(result);
  return clinicResponse(data, "病历生成任务已重新提交");
};

export const getMedicalRecordWorkflowMappingsApi = async (taskId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(`/medical-record/v2/tasks/${encodeURIComponent(taskId)}/mappings`, {
    headers: authHeaders(),
    signal
  });
  const data = await parseClinicApiResponse<MedicalRecordWorkflowMappings>(result);
  return clinicResponse(data);
};

export const getGeneratedMedicalRecordVersionsV2Api = async (
  scope: string | MedicalRecordGenerationScope,
  limit = 50,
  signal?: AbortSignal
) => {
  const params = new URLSearchParams(
    typeof scope === "string"
      ? { patientId: scope }
      : scope.encounterId
        ? { encounterId: scope.encounterId }
        : { patientId: scope.patientId || "" }
  );
  if (limit > 0) params.set("limit", String(limit));
  const result = await clinicFetch(`/medical-record/v2/versions?${params.toString()}`, {
    headers: authHeaders(),
    signal
  });
  const data = await parseClinicApiResponse<{ versions: GeneratedMedicalRecord[] }>(result);
  return clinicResponse(data.versions ?? []);
};

export const finalizeMedicalRecordV2Api = async (id: string, signal?: AbortSignal) => {
  const result = await clinicFetch("/medical-record/v2/finalize", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify({ id }),
    signal
  });
  const data = await parseClinicApiResponse<{ record: GeneratedMedicalRecord }>(result);
  return clinicResponse(data.record, "目标病历已定稿");
};

export const downloadMedicalRecordAssetV2Api = (assetId: string, signal?: AbortSignal) =>
  downloadMedicalRecordV2Docx(
    `/medical-record/v2/assets/${encodeURIComponent(assetId)}/download`,
    `病历文档资产-${assetId}.docx`,
    signal
  );

export const downloadGeneratedMedicalRecordV2Api = (recordId: string, signal?: AbortSignal) =>
  downloadMedicalRecordV2Docx(
    `/medical-record/v2/records/${encodeURIComponent(recordId)}/download`,
    `目标病历-${recordId}.docx`,
    signal
  );

export const exportGeneratedMedicalRecordV2Api = (recordId: string, signal?: AbortSignal) =>
  downloadMedicalRecordV2Docx(
    `/medical-record/v2/records/${encodeURIComponent(recordId)}/export`,
    `目标病历-${recordId}.docx`,
    signal
  );

export interface RelayModelsInfo {
  configured: boolean;
  defaultModel: string;
  models: string[];
}

/** 病历对话框模型选择：直连中转可用模型 + 系统默认模型。 */
export const getRelayModelsApi = async (signal?: AbortSignal) => {
  const result = await clinicFetch("/ai/relay/models", { headers: authHeaders(), signal });
  const data = await parseClinicApiResponse<RelayModelsInfo>(result);
  return clinicResponse(data);
};

export interface MedicalRecordWorkflowPollingOptions {
  signal?: AbortSignal;
  intervalMs?: number;
  timeoutMs?: number;
  maxConsecutiveErrors?: number;
  onUpdate?: (task: MedicalRecordWorkflowTask) => void;
}

export const pollMedicalRecordWorkflowTask = async (
  taskId: string,
  options: MedicalRecordWorkflowPollingOptions = {}
): Promise<MedicalRecordWorkflowTask> => {
  const intervalMs = Math.max(250, options.intervalMs ?? 1500);
  const timeoutMs = Math.max(intervalMs, options.timeoutMs ?? 15 * 60 * 1000);
  const maxConsecutiveErrors = Math.max(0, options.maxConsecutiveErrors ?? 2);
  const startedAt = Date.now();
  let consecutiveErrors = 0;

  while (true) {
    throwIfMedicalRecordWorkflowAborted(options.signal);
    if (Date.now() - startedAt >= timeoutMs) throw new Error("病历生成任务轮询超时，请稍后重新查询任务状态");

    try {
      const response = await getMedicalRecordWorkflowTaskApi(taskId, options.signal);
      const task = response.data;
      consecutiveErrors = 0;
      options.onUpdate?.(task);
      if (MEDICAL_RECORD_WORKFLOW_TERMINAL_STATUSES.has(task.status)) return task;
    } catch (error) {
      throwIfMedicalRecordWorkflowAborted(options.signal);
      consecutiveErrors += 1;
      if (consecutiveErrors > maxConsecutiveErrors) throw error;
    }

    const remainingMs = timeoutMs - (Date.now() - startedAt);
    if (remainingMs <= 0) throw new Error("病历生成任务轮询超时，请稍后重新查询任务状态");
    await waitForMedicalRecordWorkflowPoll(Math.min(intervalMs, remainingMs), options.signal);
  }
};
