import { authHeaders } from "../authToken";
import { clinicFetch, clinicJsonHeaders, clinicResponse, parseClinicApiResponse } from "./http";
import type {
  AiModelDetectionPayload,
  AiModelDetectionResult,
  AiRecordSummary,
  AiRecordSummaryParams,
  AiRuntimeConfig,
  AiRuntimeConfigPayload,
  DoubaoTtsConfigTestPayload,
  DoubaoTtsSpeakParams,
  DoubaoTtsSpeakResult
} from "./types";

export const generateRecordAiSummaryApi = async (params: AiRecordSummaryParams) => {
  const result = await clinicFetch("/ai/record-summary", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(params)
  });
  const data = await parseClinicApiResponse<AiRecordSummary>(result);
  return clinicResponse(data, "AI总结已生成");
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
