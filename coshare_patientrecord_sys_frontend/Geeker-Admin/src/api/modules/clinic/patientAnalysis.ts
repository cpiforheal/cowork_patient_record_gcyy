import { authHeaders } from "../authToken";
import { clinicFetch, parseClinicApiResponse } from "./http";
import { analysisSearchParams, type AnalysisQuery } from "@/views/ops/dashboard/analysisQuery";

export interface AnalysisMeta {
  from: string;
  to: string;
  view: string;
  basis: "visit" | "due" | "contact";
  granularity: string;
  metric: string;
  generatedAt: string;
  sampleSize: number;
  unit: string;
  missing: {
    agePatients: number;
    regionPatients: number;
    genderPatients: number;
    diagnosisVisits: number;
    fallbackDates: number;
  };
}

export interface AnalysisRow {
  label: string;
  value?: number;
  primary?: number;
  secondary?: number;
  visits?: number;
  patients?: number;
  denominator?: number;
  share?: number;
  unknownCount?: number;
  x?: string;
  y?: string;
  normal?: number;
  abnormal?: number;
  critical?: number;
  unmarked?: number;
  filters: Record<string, string[]>;
}

export interface AnalysisChartData {
  id: string;
  title: string;
  kind: "bar" | "matrix" | "trend" | "stack";
  unit: string;
  note: string;
  rows: AnalysisRow[];
  tableRows?: AnalysisRow[];
  series?: [string, string];
  denominator?: number;
  regionDepth?: number;
}

export interface AnalysisResult {
  meta: AnalysisMeta;
  summary: {
    visits: number;
    patients: number;
    reports: number;
    nodes: number;
    contacts: number;
    contactedNodes: number;
    unscheduledNodes: number;
  };
  detailsAllowed: boolean;
  charts: AnalysisChartData[];
}

export interface AnalysisFacetResult {
  meta: AnalysisMeta;
  facets: Record<string, Array<{ label: string; value: string }>>;
}

export interface AnalysisDetail {
  id: string;
  encounterId: string;
  name: string;
  date: string;
  gender: string;
  age: number | string;
  region: string;
  diagnosis: string[];
  operations: string[];
  examTypes: string[];
  status: string;
  stageStatuses: Record<string, string>;
  dateFallback: boolean;
  node?: string;
  dueDate?: string;
  firstContactAt?: string;
  labMetric?: string;
  labValue?: string;
  severity?: string;
}

export interface AnalysisDetailResult {
  meta: AnalysisMeta;
  rows: AnalysisDetail[];
  total: number;
  page: number;
  pageSize: number;
}

const fetchAnalysis = async <T>(suffix: string, query: AnalysisQuery, signal?: AbortSignal, extra?: Record<string, string>) => {
  const params = analysisSearchParams(query);
  Object.entries(extra || {}).forEach(([key, value]) => params.set(key, value));
  const response = await clinicFetch(`/ops/analysis${suffix}?${params}`, { headers: authHeaders(), signal });
  return parseClinicApiResponse<T>(response);
};

export const loadPatientAnalysis = (query: AnalysisQuery, signal?: AbortSignal) =>
  fetchAnalysis<AnalysisResult>("", query, signal);
export const loadPatientAnalysisFacets = (query: AnalysisQuery, signal?: AbortSignal) =>
  fetchAnalysis<AnalysisFacetResult>("/facets", query, signal);
export const loadPatientAnalysisDetails = (query: AnalysisQuery, page: number, sort: "asc" | "desc", signal?: AbortSignal) =>
  fetchAnalysis<AnalysisDetailResult>("/details", query, signal, { page: String(page), pageSize: "25", sort });
