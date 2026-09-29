import { authHeaders } from "../authToken";
import { clinicFetch, clinicResponse, parseClinicApiResponse } from "./http";

export interface OpsDashboardAnalysisResult {
  period: { from: string; to: string; generatedAt: string };
  kpi: { visits: number; uniquePatients: number; newPatients: number; overdueFollowUps: number };
  trend: Array<{ period: string; visits: number; uniquePatients: number }>;
  attention: { overdueFollowUps: number; dueTodayFollowUps: number; unidentifiedAddresses: number };
  departments: Array<{ department: string; visits: number; uniquePatients: number; share: number }>;
  view: string;
}

export type AddressAnalysisLevel = "COUNTY" | "TOWNSHIP" | "VILLAGE" | "PATIENT";
export interface AddressAnalysisNode { key: string; label: string; level: AddressAnalysisLevel; patientCount: number; visitCount: number; share: number; hasChildren: boolean; }
export interface AddressPatientCard {
  id: string;
  name: string;
  age: string;
  township: string;
  visitCount: number;
  latestVisitDate: string;
  encounterId: string;
  patientCaseId?: string;
  phone?: string;
  address?: string;
}
export interface AddressAnalysisResult {
  generatedAt: string;
  from: string;
  to: string;
  level: AddressAnalysisLevel;
  parentKey: string;
  metric: "patients" | "visits";
  breadcrumb: Array<{ key: string; label: string; level: string }>;
  summary: { patientCount: number; visitCount: number; unidentifiedCount: number };
  nodes: AddressAnalysisNode[];
  total: number;
  page: number;
  pageSize: number;
  patientAccess?: boolean;
  patients?: AddressPatientCard[];
  patientTotal?: number;
}

export const loadOpsDashboardApi = async (params: {
  from?: string;
  to?: string;
  granularity?: "day" | "week" | "month";
  view?: string;
  months?: number;
} = {}, signal?: AbortSignal) => {
  const query = new URLSearchParams();
  if (params.from) query.set("from", params.from);
  if (params.to) query.set("to", params.to);
  query.set("granularity", params.granularity || "day");
  query.set("view", params.view || "overview");
  query.set("months", String(params.months || 1));
  const result = await clinicFetch(`/ops/dashboard?${query.toString()}`, { headers: authHeaders(), signal });
  const data = await parseClinicApiResponse<OpsDashboardAnalysisResult>(result);
  return clinicResponse(data, "运营概览已加载");
};

export const loadAddressAnalysisApi = async (
  params: { from: string; to: string; parentKey?: string; level?: AddressAnalysisLevel; metric?: "patients" | "visits"; keyword?: string; page?: number; pageSize?: number },
  signal?: AbortSignal
) => {
  const query = new URLSearchParams({
    from: params.from,
    to: params.to,
    parentKey: params.parentKey || "",
    level: params.level || "COUNTY",
    metric: params.metric || "visits",
    keyword: params.keyword || "",
    page: String(params.page || 1),
    pageSize: String(params.pageSize || 50)
  });
  const result = await clinicFetch(`/ops/dashboard/address-analysis?${query.toString()}`, { headers: authHeaders(), signal });
  const data = await parseClinicApiResponse<AddressAnalysisResult>(result);
  return clinicResponse(data, "人群分析已加载");
};

