export interface ExamPaper {
  id: string;
  code: string;
  title: string;
  description: string;
  questionCount: number;
  pointsPerQuestion: number;
  totalScore: number;
  status: "OPEN" | "CLOSED";
}

export interface ExamQuestion {
  id: string;
  seq: number;
  stem: string;
  options: string[];
  score: number;
}

export interface ExamPaperView {
  paper: ExamPaper;
  departments: string[];
  questions: ExamQuestion[];
}

export interface ExamAttempt {
  id: string;
  name: string;
  department: string;
  status: "IN_PROGRESS" | "SUBMITTED" | "VOID";
  token?: string;
  answers: Record<string, string>;
  score: number;
  correctCount: number;
  startedAt: string;
  lastSavedAt: string;
  submittedAt: string;
  durationSeconds: number;
  voidReason?: string;
}

export interface ExamWrongItem {
  seq: number;
  stem: string;
  chosen: string;
  answer: string;
  explanation: string;
}

export interface ExamResult {
  score: number;
  correctCount: number;
  totalQuestions: number;
  totalScore: number;
  durationSeconds: number;
  submittedAt: string;
  wrongReview: ExamWrongItem[];
}

export interface ExamAttemptResponse {
  attempt: ExamAttempt;
  result: ExamResult | null;
}

const EXAM_BASE = "/exam-api";

const examFetch = async <T>(path: string, init?: RequestInit): Promise<T> => {
  const response = await fetch(`${EXAM_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers || {})
    }
  });
  const text = await response.text();
  let payload: any;
  try {
    payload = JSON.parse(text);
  } catch {
    throw new Error("答题服务返回异常，请稍后重试");
  }
  if (!response.ok || Number(payload.code) < 200 || Number(payload.code) >= 300) {
    const error = new Error(payload.msg || "请求失败，请稍后重试");
    (error as Error & { status?: number }).status = response.status;
    throw error;
  }
  return payload.data as T;
};

export const getExamPaperApi = () => examFetch<ExamPaperView>("/paper");

export const startExamApi = (payload: { name: string; department: string }) =>
  examFetch<ExamAttemptResponse>("/attempts", {
    method: "POST",
    body: JSON.stringify(payload)
  });

export const saveExamDraftApi = (attemptId: string, payload: { token: string; answers: Record<string, string> }) =>
  examFetch<{ savedAt: string }>(`/attempts/${encodeURIComponent(attemptId)}/draft`, {
    method: "PUT",
    body: JSON.stringify(payload)
  });

export const submitExamApi = (attemptId: string, payload: { token: string; answers: Record<string, string> }) =>
  examFetch<ExamAttemptResponse>(`/attempts/${encodeURIComponent(attemptId)}/submit`, {
    method: "POST",
    body: JSON.stringify(payload)
  });

export const getExamAttemptApi = (attemptId: string, token: string) =>
  examFetch<ExamAttemptResponse>(
    `/attempts/${encodeURIComponent(attemptId)}?token=${encodeURIComponent(token)}`
  );