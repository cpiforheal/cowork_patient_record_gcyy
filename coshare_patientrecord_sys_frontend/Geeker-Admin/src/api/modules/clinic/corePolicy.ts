import { clinicFetch, clinicJsonHeaders, clinicResponse, parseClinicApiResponse } from "./http";
import { authHeaders } from "../authToken";

/** 医疗质量安全核心制度学习（一期）：每日一条 + 打卡 + 每月小测。 */
export interface CorePolicyClause {
  id: string;
  clauseNo: string;
  content: string;
}

export interface CorePolicyToday {
  planDate: string;
  policyId: string;
  code: string;
  title: string;
  summary: string;
  clauseId: string;
  clauseNo: string;
  content: string;
  totalClauses: number;
  rotationIndex: number;
  checked: boolean;
  checkedAt?: string;
  streak: number;
}

export interface CorePolicy {
  id: string;
  code: string;
  title: string;
  summary: string;
  clauses: CorePolicyClause[];
}

export interface CorePolicyQuestion {
  id: string;
  type: "SINGLE" | "JUDGE";
  stem: string;
  options: string[];
  explanation?: string;
  answer?: string;
  questionId?: string;
  chosen?: string;
  correct?: boolean;
}

export interface CorePolicyQuizCurrent {
  attempted: boolean;
  score?: number;
  total?: number;
  submittedAt?: string;
  quizMonth?: string;
  questions?: CorePolicyQuestion[];
}

export interface CorePolicyQuizResult {
  attempted: boolean;
  quizMonth: string;
  score: number;
  total: number;
  review: CorePolicyQuestion[];
}

export const getCorePolicyTodayApi = async (signal?: AbortSignal) => {
  const result = await clinicFetch("/core-policy/today", { headers: authHeaders(), signal });
  return clinicResponse(await parseClinicApiResponse<CorePolicyToday>(result));
};

export const postCorePolicyCheckInApi = async () => {
  const result = await clinicFetch("/core-policy/check-in", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify({})
  });
  return clinicResponse(await parseClinicApiResponse<CorePolicyToday>(result));
};

export const getCorePolicyPoliciesApi = async (signal?: AbortSignal) => {
  const result = await clinicFetch("/core-policy/policies", { headers: authHeaders(), signal });
  return clinicResponse(await parseClinicApiResponse<CorePolicy[]>(result));
};

export const getCorePolicyQuizCurrentApi = async (signal?: AbortSignal) => {
  const result = await clinicFetch("/core-policy/quiz/current", { headers: authHeaders(), signal });
  return clinicResponse(await parseClinicApiResponse<CorePolicyQuizCurrent>(result));
};

export const submitCorePolicyQuizApi = async (answers: Record<string, string>) => {
  const result = await clinicFetch("/core-policy/quiz/submit", {
    method: "POST",
    headers: clinicJsonHeaders(),
    body: JSON.stringify({ answers })
  });
  return clinicResponse(await parseClinicApiResponse<CorePolicyQuizResult>(result));
};
