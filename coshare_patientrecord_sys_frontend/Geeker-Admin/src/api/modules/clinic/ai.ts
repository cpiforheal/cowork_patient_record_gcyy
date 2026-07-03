import { authHeaders } from "../authToken";
import { clinicFetch, clinicJsonHeaders, clinicResponse, parseClinicApiResponse } from "./http";
import type {
  AiAssistantAnalytics,
  AiAssistantLogListParams,
  AiAssistantLogListResult,
  AiAssistantRequest,
  AiAssistantResponse,
  AiDocumentGenerateResult,
  AiDocumentPreview,
  AiDocumentRequestPayload,
  AiDocumentTemplateResult,
  AiModelDetectionPayload,
  AiModelDetectionResult,
  AiPromptTemplateCandidate,
  AiPromptTemplateListResult,
  AiPromptTemplatePayload,
  AiRecordSummary,
  AiRecordSummaryParams,
  AiRuntimeConfig,
  AiRuntimeConfigPayload,
  DoubaoTtsConfigTestPayload,
  DoubaoTtsSpeakParams,
  DoubaoTtsSpeakResult,
  GeneratedAiDocument
} from "./types";

const buildQuerySuffix = (params: object = {}) => {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") query.set(key, String(value));
  });
  return query.toString() ? `?${query.toString()}` : "";
};

export const generateRecordAiSummaryApi = async (params: AiRecordSummaryParams) => {
  const result = await clinicFetch("/ai/record-summary", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(params)
  });
  const data = await parseClinicApiResponse<AiRecordSummary>(result);
  return clinicResponse(data, "AI总结已生成");
};

export const getAiDocumentTemplatesApi = async () => {
  const result = await clinicFetch("/ai-document/templates", { headers: authHeaders() });
  const data = await parseClinicApiResponse<AiDocumentTemplateResult>(result);
  return clinicResponse(data);
};

export const previewAiDocumentApi = async (payload: AiDocumentRequestPayload) => {
  const result = await clinicFetch("/ai-document/preview", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiDocumentPreview>(result);
  return clinicResponse(data, "文稿预览已生成");
};

export const generateAiDocumentApi = async (payload: AiDocumentRequestPayload) => {
  const result = await clinicFetch("/ai-document/generate", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiDocumentGenerateResult>(result);
  return clinicResponse(data, "DOCX 文稿已生成");
};

export const downloadAiDocumentApi = async (generatedDocument: GeneratedAiDocument) => {
  const result = await clinicFetch(`/ai-document/download?id=${encodeURIComponent(generatedDocument.id)}`, {
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
  link.download = generatedDocument.fileName || `${generatedDocument.title || "AI文稿"}.docx`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 500);
  return clinicResponse(null, "DOCX 文稿已下载");
};

export const getAiRuntimeConfigApi = async () => {
  const result = await clinicFetch("/ai/config", { headers: authHeaders() });
  const data = await parseClinicApiResponse<AiRuntimeConfig>(result);
  return clinicResponse(data);
};

export const saveAiRuntimeConfigApi = async (payload: AiRuntimeConfigPayload) => {
  const result = await clinicFetch("/ai/config", {
    method: "PUT",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiRuntimeConfig>(result);
  return clinicResponse(data, "AI接口配置已保存");
};

export const getDoubaoAiRuntimeConfigApi = async () => {
  const result = await clinicFetch("/ai/doubao/config", { headers: authHeaders() });
  const data = await parseClinicApiResponse<AiRuntimeConfig>(result);
  return clinicResponse(data);
};

export const saveDoubaoAiRuntimeConfigApi = async (payload: AiRuntimeConfigPayload) => {
  const result = await clinicFetch("/ai/doubao/config", {
    method: "PUT",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiRuntimeConfig>(result);
  return clinicResponse(data, "豆包助手配置已保存");
};

export const getDoubaoTtsConfigApi = async () => {
  const result = await clinicFetch("/ai/doubao/tts/config", { headers: authHeaders() });
  const data = await parseClinicApiResponse<AiRuntimeConfig>(result);
  return clinicResponse(data);
};

export const saveDoubaoTtsConfigApi = async (payload: AiRuntimeConfigPayload) => {
  const result = await clinicFetch("/ai/doubao/tts/config", {
    method: "PUT",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiRuntimeConfig>(result);
  return clinicResponse(data, "豆包语音朗读配置已保存");
};

export const speakAiSummaryApi = async (payload: DoubaoTtsSpeakParams) => {
  const result = await clinicFetch("/ai/doubao/tts/speak", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<DoubaoTtsSpeakResult>(result);
  return clinicResponse(data, "豆包语音朗读已生成");
};

export const testDoubaoTtsConfigApi = async (payload: DoubaoTtsConfigTestPayload) => {
  const result = await clinicFetch("/ai/doubao/tts/test", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<DoubaoTtsSpeakResult>(result);
  return clinicResponse(data, "豆包语音朗读检测成功");
};

export const detectDoubaoAiModelsApi = async (payload: AiModelDetectionPayload) => {
  const result = await clinicFetch("/ai/doubao/models", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiModelDetectionResult>(result);
  return clinicResponse(data, "豆包模型检测完成");
};

export const askAiAssistantApi = async (payload: AiAssistantRequest) => {
  const result = await clinicFetch("/ai/assistant", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiAssistantResponse>(result);
  return clinicResponse(data, "豆包助手已生成回答");
};

export const getAiAssistantLogsApi = async (params: AiAssistantLogListParams = {}) => {
  const result = await clinicFetch(`/ai/assistant/logs${buildQuerySuffix(params)}`, { headers: authHeaders() });
  const data = await parseClinicApiResponse<AiAssistantLogListResult>(result);
  return clinicResponse(data);
};

export const getAiAssistantAnalyticsApi = async (params: AiAssistantLogListParams = {}) => {
  const result = await clinicFetch(`/ai/assistant/analytics${buildQuerySuffix(params)}`, { headers: authHeaders() });
  const data = await parseClinicApiResponse<AiAssistantAnalytics>(result);
  return clinicResponse(data);
};

export const getAiAssistantTemplatesApi = async () => {
  const result = await clinicFetch("/ai/assistant/templates", { headers: authHeaders() });
  const data = await parseClinicApiResponse<AiPromptTemplateListResult>(result);
  return clinicResponse(data);
};

export const markAiAssistantTemplateCandidateApi = async (id: string, payload: AiPromptTemplatePayload) => {
  const result = await clinicFetch(`/ai/assistant/logs/${id}/template-candidate`, {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload)
  });
  const data = await parseClinicApiResponse<AiPromptTemplateCandidate>(result);
  return clinicResponse(data, "模板候选已保存");
};
