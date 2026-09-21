import type { ResultData } from "@/api/interface";
import { authHeaders, handleUnauthorizedResponse } from "../authToken";
import { clinicFetch, clinicJsonHeaders, clinicResponse, parseClinicApiResponse } from "./http";

export interface HealthArchiveAuto {
  name: string;
  gender: string;
  age: string;
  phone: string;
  address: string;
  insurance: string;
  westernDx: string;
  tcmDx: string;
}

export interface HealthArchiveRecoveryRow {
  timeNode: string;
  wound: string;
  pain: string;
  bowel: string;
  edema: string;
  medication: string;
  training: string;
  remark: string;
}

export interface HealthArchiveFollowUpRow {
  timeNode: string;
  method: string;
  recovery: string;
  adherence: string;
  diet: string;
  review: string;
  feedback: string;
  visitor: string;
  /** 实际随访日期（增量优化：与应随访日期区分，记录实际执行） */
  actualDate: string;
  /** 随访结果状态：正常完成 / 患者失访 / 拒绝随访 / 延期随访 / 无应答 */
  resultStatus: string;
}

export interface HealthArchiveForm {
  basic: HealthArchiveAuto;
  sourceChannel: string;
  sourceChannelsOther: string;
  visitMotivation: string;
  visitMotivationsOther: string;
  pastHistory: string;
  specialExam: {
    anusVisual: string;
    digitalRectal: string;
    anoscope: string;
    positiveSigns: string;
  };
  tcmConstitution: string[];
  tcmConstitutionOther: string;
  crowdCategory: string;
  treatmentPath: string;
  surgeryDate: string;
  interventions: string[];
  recoveryRows: HealthArchiveRecoveryRow[];
  emotionIssues: string[];
  emotionOther: string;
  psychInterventions: string[];
  counselingRecord: string;
  educationItems: string[];
  patientUnderstood: string;
  followUpRows: HealthArchiveFollowUpRow[];
  /** 下次复诊预约结构化日期（增量优化） */
  nextVisitDate: string;
  /** 患者触达渠道：电话 / 微信 / 短信 / 家属转达 / 其他 */
  reachChannel: string;
  /** 触达确认：已确认可触达 / 暂未确认 / 无法触达 */
  reachConfirmed: string;
  /** 转介绍意向：有意向 / 待观察 / 暂无意向 */
  referralIntention: string;
  adjustmentRecord: string;
  signFiledBy: string;
  signAttending: string;
  signQc: string;
}

export interface HealthArchiveVersionItem {
  id: string;
  version: number;
  model: string;
  status: string;
  fileName: string;
  generatedAt: string;
  operatorRole: string;
}

export interface HealthArchiveDocumentItem {
  id: string;
  version: number;
  fileName: string;
  status: string;
  createdAt: string;
  createdByRole: string;
  downloadUrl: string;
}

export interface HealthArchiveDraft {
  encounterId: string;
  id: string;
  status: string;
  archiveNo: string;
  revision: number;
  sourceRecordId: string;
  updatedAt: string;
  updatedBy: string;
  completedAt: string;
  form: HealthArchiveForm;
}

export interface HealthArchiveLoadResult {
  encounterId: string;
  auto: HealthArchiveAuto;
  aiVersions: HealthArchiveVersionItem[];
  documents: HealthArchiveDocumentItem[];
  draft: HealthArchiveDraft;
}

export interface HealthArchiveDocumentCreated {
  document: { id: string; version: number; fileName: string; createdAt: string; downloadUrl: string };
  aiRecordFileName: string;
}

export interface HealthArchiveEmotionBrief {
  emotionIssues: string[];
}

/** 情绪问题速查表：encounterId → 主要情绪问题（取自各档案草稿"四、心理疏导"） */
export const getHealthArchiveEmotionMapApi = async (signal?: AbortSignal) => {
  const result = await clinicFetch("/health-archive/emotion-map", { headers: authHeaders(), signal });
  const data = await parseClinicApiResponse<Record<string, HealthArchiveEmotionBrief>>(result);
  return clinicResponse(data, "情绪问题速查表已加载");
};

export const loadHealthArchiveApi = async (encounterId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(`/health-archive?encounterId=${encodeURIComponent(encounterId)}`, {
    headers: authHeaders(),
    signal
  });
  const data = await parseClinicApiResponse<HealthArchiveLoadResult>(result);
  return clinicResponse(data, "健康管理档案已加载");
};

export const saveHealthArchiveDraftApi = async (
  payload: { encounterId: string; sourceRecordId: string; form: HealthArchiveForm },
  signal?: AbortSignal
) => {
  const result = await clinicFetch("/health-archive", {
    method: "PUT",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload),
    signal
  });
  const data = await parseClinicApiResponse<{ draft: HealthArchiveDraft }>(result);
  return clinicResponse(data, "健康管理档案草稿已保存");
};

export const completeHealthArchiveApi = async (
  payload: { encounterId: string; sourceRecordId: string; form: HealthArchiveForm },
  signal?: AbortSignal
) => {
  const result = await clinicFetch("/health-archive/complete", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify(payload),
    signal
  });
  const data = await parseClinicApiResponse<HealthArchiveDocumentCreated>(result);
  return clinicResponse(data, "健康管理档案合并文档已生成");
};

export const downloadHealthArchiveDocumentApi = async (documentId: string, signal?: AbortSignal) => {
  const result = await clinicFetch(
    `/health-archive/documents/${encodeURIComponent(documentId)}/download`,
    { headers: authHeaders(), signal }
  );
  if (result.status === 401) {
    handleUnauthorizedResponse();
  }
  if (!result.ok) {
    throw new Error("健康管理档案合并文档下载失败");
  }
  const blob = await result.blob();
  const disposition = result.headers.get("content-disposition") || "";
  const match = disposition.match(/filename="?([^";]+)"?/);
  const filename = match ? decodeURIComponent(match[1]) : `健康管理档案合并文档-${documentId}.docx`;
  return { blob, filename };
};
