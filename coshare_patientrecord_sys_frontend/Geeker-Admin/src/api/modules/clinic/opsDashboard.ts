import { authHeaders } from "../authToken";
import { clinicFetch, clinicResponse, parseClinicApiResponse } from "./http";

export interface OpsMetric {
  current: number;
  previous: number;
  delta: number;
  deltaRate: number | null;
}

export interface OpsDashboardKpi {
  visitsThisMonth: OpsMetric;
  visitsLastMonth: number;
  followUpDue: number;
  followUpArrived: number;
  followUpOverdue: number;
  followUpArrivalRate: number;
  patientCases: number;
  newCasesThisMonth: number;
}

export interface OpsTrendPoint {
  month: string;
  visits: number;
}

export interface OpsPatientPoint {
  month: string;
  patients: number;
}

export interface OpsStatusSlice {
  status: string;
  label: string;
  count: number;
}

export interface OpsFollowUp {
  dueTotal: number;
  notScheduled: number;
  reached: number;
  arrived: number;
  overdue: number;
  dueToday: number;
  upcoming: number;
  reachRate: number;
  arrivalRate: number;
}

export interface OpsDepartment {
  department: string;
  count: number;
}

export interface OpsDashboardResult {
  generatedAt: string;
  from: string;
  to: string;
  kpi: OpsDashboardKpi;
  trend: OpsTrendPoint[];
  statusDistribution: OpsStatusSlice[];
  followUp: OpsFollowUp;
  departments: OpsDepartment[];
  monthlyPatients: OpsPatientPoint[];
}

export type AddressAnalysisLevel = "COUNTY" | "TOWNSHIP" | "VILLAGE" | "PATIENT";

export interface AddressAnalysisNode {
  key: string;
  label: string;
  level: AddressAnalysisLevel | "PATIENT";
  patientCount: number;
  visitCount: number;
  share: number;
  hasChildren: boolean;
}

export interface AddressExamSummary {
  reports?: number;
  abnormal?: number;
  critical?: number;
}

export interface AddressPatientCard {
  id: string;
  name: string;
  gender: string;
  age: string;
  phone: string;
  address: string;
  county: string;
  township: string;
  village: string;
  visitCount: number;
  latestVisitDate: string;
  encounterId: string;
  examSummary: Record<string, AddressExamSummary>;
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
  patients?: AddressPatientCard[];
  patientTotal?: number;
}

export const loadOpsDashboardApi = async (months = 12, signal?: AbortSignal) => {
  const result = await clinicFetch(`/ops/dashboard?months=${encodeURIComponent(String(months))}`, {
    headers: authHeaders(),
    signal
  });
  const data = await parseClinicApiResponse<OpsDashboardResult>(result);
  return clinicResponse(data, "Operations dashboard loaded");
};

export const loadAddressAnalysisApi = async (
  params: {
    from: string;
    to: string;
    parentKey?: string;
    level?: AddressAnalysisLevel;
    metric?: "patients" | "visits";
    keyword?: string;
    page?: number;
    pageSize?: number;
  },
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
  const result = await clinicFetch(`/ops/dashboard/address-analysis?${query.toString()}`, {
    headers: authHeaders(),
    signal
  });
  const data = await parseClinicApiResponse<AddressAnalysisResult>(result);
  return clinicResponse(data, "Address analysis loaded");
};
