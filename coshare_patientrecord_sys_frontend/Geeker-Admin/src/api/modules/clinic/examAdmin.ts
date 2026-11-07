import { authHeaders } from "../authToken";
import { clinicFetch, clinicJsonHeaders, parseClinicApiResponse } from "./http";
import type { ExamAttempt, ExamPaper, ExamQuestion } from "@/api/modules/exam/public";

export interface ExamAdminStats {
  total: number;
  inProgress: number;
  submitted: number;
  void: number;
  averageScore: number;
}

export interface ExamAdminPaperView {
  paper: ExamPaper;
  stats: ExamAdminStats;
}

export interface ExamAdminQuestion extends ExamQuestion {
  answer: string[];
  explanation: string;
}

export interface ExamAdminQuestionView {
  paper: ExamPaper;
  questions: ExamAdminQuestion[];
}

export interface ExamRosterItem {
  id: string;
  name: string;
  department: string;
  source: string;
}

export interface ExamRosterView {
  list: ExamRosterItem[];
  departments: string[];
}

export interface ExamAttemptRow {
  id: string;
  name: string;
  department: string;
  status: "IN_PROGRESS" | "SUBMITTED" | "VOID";
  score: number;
  correctCount: number;
  startedAt: string;
  submittedAt: string;
  durationSeconds: number;
  voidReason?: string;
}

export interface ExamAttemptListView {
  total: number;
  list: ExamAttemptRow[];
}

export interface ExamAdminQuestionDetail {
  id: string;
  seq: number;
  stem: string;
  options: string[];
  chosen: string;
  answer: string[];
  explanation: string;
  correct: boolean;
  score: number;
}

export interface ExamAttemptDetailView {
  attempt: ExamAttempt;
  questions: ExamAdminQuestionDetail[];
}

export interface ExamImportResult {
  inserted: number;
  skipped: number;
  errors?: string[];
}

const json = <T>(response: Response) => parseClinicApiResponse<T>(response);

export const getExamAdminPaperApi = async () =>
  json<ExamAdminPaperView>(await clinicFetch("/exam-admin/paper", { headers: clinicJsonHeaders() }));

export const updateExamPaperStatusApi = async (status: "OPEN" | "CLOSED") =>
  json<ExamAdminPaperView>(
    await clinicFetch("/exam-admin/paper/status", {
      method: "PUT",
      headers: clinicJsonHeaders(),
      body: JSON.stringify({ status })
    })
  );

export const getExamAdminQuestionsApi = async () =>
  json<ExamAdminQuestionView>(await clinicFetch("/exam-admin/questions", { headers: clinicJsonHeaders() }));

export const getExamRosterApi = async () =>
  json<ExamRosterView>(await clinicFetch("/exam-admin/roster", { headers: clinicJsonHeaders() }));

export const addExamRosterApi = async (payload: { name: string; department: string }) =>
  json<ExamRosterView>(
    await clinicFetch("/exam-admin/roster", {
      method: "POST",
      headers: clinicJsonHeaders(),
      body: JSON.stringify(payload)
    })
  );

export const deleteExamRosterApi = async (id: string) =>
  json<ExamRosterView>(
    await clinicFetch(`/exam-admin/roster/${encodeURIComponent(id)}`, {
      method: "DELETE",
      headers: clinicJsonHeaders()
    })
  );

export const importExamRosterApi = async (file: File) => {
  const form = new FormData();
  form.append("file", file);
  return json<ExamImportResult>(
    await clinicFetch("/exam-admin/roster/import", {
      method: "POST",
      headers: authHeaders(),
      body: form
    })
  );
};

export const importExamAccountsApi = async () =>
  json<ExamImportResult>(
    await clinicFetch("/exam-admin/roster/import-accounts", {
      method: "POST",
      headers: clinicJsonHeaders()
    })
  );

export const getExamAttemptsApi = async (params: { department?: string; status?: string } = {}) => {
  const query = new URLSearchParams();
  if (params.department) query.set("department", params.department);
  if (params.status) query.set("status", params.status);
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return json<ExamAttemptListView>(await clinicFetch(`/exam-admin/attempts${suffix}`, { headers: clinicJsonHeaders() }));
};

export const getExamAttemptDetailApi = async (id: string) =>
  json<ExamAttemptDetailView>(
    await clinicFetch(`/exam-admin/attempts/${encodeURIComponent(id)}`, { headers: clinicJsonHeaders() })
  );

export const voidExamAttemptApi = async (id: string, reason: string) =>
  json<{ id: string; status: string }>(
    await clinicFetch(`/exam-admin/attempts/${encodeURIComponent(id)}/void`, {
      method: "POST",
      headers: clinicJsonHeaders(),
      body: JSON.stringify({ reason })
    })
  );

export const downloadExamAttemptsApi = async () => {
  const response = await clinicFetch("/exam-admin/attempts/export", { headers: clinicJsonHeaders() });
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new Error(payload?.msg || "导出失败");
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = "exam-attempts.xlsx";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
};