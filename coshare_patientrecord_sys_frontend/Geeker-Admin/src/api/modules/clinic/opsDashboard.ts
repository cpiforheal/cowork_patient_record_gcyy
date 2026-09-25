import { authHeaders } from "../authToken";
import { clinicFetch, clinicResponse, parseClinicApiResponse } from "./http";

/** 环比指标：previous 为 0 时 deltaRate 为 null（前端显示"—"而不是 Infinity） */
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

/** 运营数据看板：面向管理层的只读聚合，全部为计数类指标，不含患者隐私字段 */
export const loadOpsDashboardApi = async (months = 12, signal?: AbortSignal) => {
  const result = await clinicFetch(`/ops/dashboard?months=${encodeURIComponent(String(months))}`, {
    headers: authHeaders(),
    signal
  });
  const data = await parseClinicApiResponse<OpsDashboardResult>(result);
  return clinicResponse(data, "运营数据已加载");
};
