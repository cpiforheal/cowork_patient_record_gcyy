import { authHeaders } from "../authToken";
import { clinicFetch, clinicResponse, parseClinicApiResponse } from "./http";

/** 监控台时间轴行：一条随访操作留痕 */
export interface NursingFollowUpTimelineRow {
  id: string;
  action: string;
  actionLabel: string;
  encounterId: string;
  operator: string;
  operatorRole: string;
  detail: string;
  createdAt: string;
  /** yyyy-MM-dd，用于按自然日分组 */
  dateKey: string;
  /** 服务端按 encounterId 解析的患者姓名，可能为空（病例已删或无关联） */
  patientName?: string;
}

/** 节点状态：每患者最近一次复诊安排 */
export interface NursingFollowUpNodeRow {
  patientCaseId: string;
  patientName: string;
  seq: number;
  dueDate: string;
  reached: boolean;
  lastContactAt: string;
  arrived: boolean;
  arrivedAt: string;
  overdueDays: number;
  lastOperator: string;
  lastActionAt: string;
}

export interface NursingFollowUpSummary {
  /** 所选区间的操作条数 */
  rangeOps: number;
  todayOps: number;
  todayReached: number;
  todayArrived: number;
  overduePending: number;
}

export interface NursingFollowUpMonitorResult {
  from: string;
  to: string;
  total: number;
  pageNum: number;
  pageSize: number;
  summary: NursingFollowUpSummary;
  nodes: NursingFollowUpNodeRow[];
  operators: string[];
  timeline: NursingFollowUpTimelineRow[];
}

export interface NursingFollowUpMonitorQuery {
  from?: string;
  to?: string;
  operator?: string;
  action?: string;
  pageNum?: number;
  pageSize?: number;
}

export const loadNursingFollowUpMonitorApi = async (params: NursingFollowUpMonitorQuery, signal?: AbortSignal) => {
  const query = new URLSearchParams();
  if (params?.from) query.set("from", params.from);
  if (params?.to) query.set("to", params.to);
  if (params?.operator) query.set("operator", params.operator);
  if (params?.action) query.set("action", params.action);
  query.set("pageNum", String(params?.pageNum || 1));
  query.set("pageSize", String(params?.pageSize || 50));
  const result = await clinicFetch(`/nursing/follow-up-monitor?${query.toString()}`, {
    headers: authHeaders(),
    signal
  });
  const data = await parseClinicApiResponse<NursingFollowUpMonitorResult>(result);
  return clinicResponse(data, "护理随访留痕已加载");
};

/** 导出：沿用仓库既有 blob 下载模式 */
const downloadBlob = async (path: string, params: NursingFollowUpMonitorQuery, signal?: AbortSignal) => {
  const query = new URLSearchParams();
  if (params?.from) query.set("from", params.from);
  if (params?.to) query.set("to", params.to);
  if (params?.operator) query.set("operator", params.operator);
  if (params?.action) query.set("action", params.action);
  const result = await clinicFetch(`${path}?${query.toString()}`, { headers: authHeaders(), signal });
  if (!result.ok) {
    // 失败时后端返回 JSON 错误体，需解析出可读信息而不是给出坏文件
    const text = await result.text();
    try {
      const payload = JSON.parse(text) as { msg?: string };
      throw new Error(payload.msg || "导出失败");
    } catch {
      throw new Error("导出失败，请稍后重试");
    }
  }
  const disposition = result.headers.get("Content-Disposition") || "";
  const match = /filename="?([^";]+)"?/.exec(disposition);
  const filename = match ? decodeURIComponent(match[1]) : "护理随访留痕";
  return { blob: await result.blob(), filename };
};

export const downloadNursingFollowUpMonitorXlsxApi = (params: NursingFollowUpMonitorQuery, signal?: AbortSignal) =>
  downloadBlob("/nursing/follow-up-monitor/export.xlsx", params, signal);

export const downloadNursingFollowUpMonitorDocxApi = (params: NursingFollowUpMonitorQuery, signal?: AbortSignal) =>
  downloadBlob("/nursing/follow-up-monitor/export.docx", params, signal);

/** 可选动作筛选项：与后端 ACTION_LABELS 保持一致 */
export const NURSING_FOLLOWUP_ACTIONS = [
  { label: "创建复诊记录", value: "followup.create" },
  { label: "编辑复诊记录", value: "followup.update" },
  { label: "上传复诊图片", value: "followup.image.upload" },
  { label: "删除复诊图片", value: "followup.image.remove" },
  { label: "标记已联系", value: "followup.recall.contact" },
  { label: "确认已回院", value: "followup.recall.arrived" },
  { label: "撤销回院确认", value: "followup.recall.arrived.undo" },
  { label: "导出留痕资料", value: "followup.monitor.export" }
];
