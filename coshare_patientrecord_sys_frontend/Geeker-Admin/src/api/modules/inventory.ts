import type { ResultData } from "@/api/interface";
import { authHeaders, handleUnauthorizedResponse } from "./authToken";

const INVENTORY_API_BASE_URL = import.meta.env.VITE_INVENTORY_API_BASE_URL || "/inventory-api";

export interface InventoryItem {
  id: string;
  name: string;
  category: string;
  spec: string;
  unit: string;
  location: string;
  lowStockThreshold: number;
  sensitive: boolean;
  batchRequired: boolean;
  expiryRequired: boolean;
  enabled: boolean;
}

export interface InventoryBatch {
  id: string;
  itemId: string;
  batchNo: string;
  expiryDate: string;
  quantity: number;
  location: string;
  source?: string;
  createdAt?: string;
}

export interface InventoryRequestLine {
  id: string;
  itemId: string;
  quantity: number;
  issuedQuantity?: number;
  status?: "pending" | "approved" | "partially_issued" | "issued" | "received" | "rejected" | "cancelled" | "void";
  batchAllocations?: {
    batchId: string;
    batchNo?: string;
    expiryDate?: string;
    quantity: number;
    issuedAt?: string;
    issuer?: string;
  }[];
}

export interface InventoryRequest {
  id: string;
  itemId: string;
  quantity: number;
  itemCount?: number;
  itemSummary?: string;
  lines?: InventoryRequestLine[];
  issuedQuantity?: number;
  batchId?: string;
  department: string;
  applicant: string;
  owner: string;
  issuer?: string;
  receiver?: string;
  reason: string;
  expectedUseWeek: string;
  status: "pending" | "approved" | "partially_issued" | "issued" | "received" | "rejected" | "cancelled" | "void";
  createdAt: string;
  approvedAt?: string;
  issuedAt?: string;
  receivedAt?: string;
  rejectReason?: string;
  cancelReason?: string;
  voidReason?: string;
}

export interface WeeklyConsumption {
  id: string;
  weekNo: string;
  department: string;
  itemId: string;
  consumedQuantity: number;
  actualConsumedQuantity?: number;
  remainingQuantity: number;
  nextWeekQuantity: number;
  suggestedQuantity?: number;
  adjustedQuantity?: number;
  owner: string;
  abnormalReason: string;
  confirmedAt: string;
}

export interface InventoryCount {
  id: string;
  itemId: string;
  batchId: string;
  bookQuantity: number;
  actualQuantity: number;
  differenceQuantity: number;
  operator: string;
  reason: string;
  countedAt: string;
}

export interface InventoryMovement {
  id: string;
  itemId: string;
  batchId: string;
  type: "inbound" | "issue" | "return" | "scrap" | "count";
  quantity: number;
  department: string;
  operator: string;
  reason: string;
  relatedId: string;
  createdAt: string;
}

export type InventoryCareType = "outpatient" | "inpatient";
export type InventoryPackageStatus = "draft" | "enabled" | "disabled";
// The current automatic-consumption workflow is triggered once per visit.
// Keep the type narrow until admission/day/procedure triggers are implemented.
export type InventoryConsumptionMode = "per_visit";

export interface InventoryPackageLine {
  id?: string;
  itemId: string;
  quantity: number;
  consumptionMode?: InventoryConsumptionMode;
}

export interface InventoryPackage {
  id: string;
  name: string;
  department: string;
  careType: InventoryCareType;
  version?: number | string;
  status: InventoryPackageStatus;
  effectiveDate?: string;
  operator?: string;
  createdAt?: string;
  lines: InventoryPackageLine[];
}

export interface InventoryConsumptionDetail {
  id?: string;
  itemId?: string;
  quantity?: number;
  batchId?: string;
  errorMessage?: string;
}

export interface InventoryConsumptionEvent {
  id: string;
  encounterId: string;
  caseToken?: string;
  route: string;
  department: string;
  visitDate: string;
  packageId?: string;
  packageName?: string;
  status: "pending" | "success" | "succeeded" | "failed" | "reversed";
  errorMessage?: string;
  operator?: string;
  createdAt?: string;
  details?: InventoryConsumptionDetail[];
}

export interface InventoryAuditLog {
  id: string;
  operator: string;
  action: string;
  targetType: string;
  targetLabel: string;
  detail: string;
  createdAt: string;
}

export interface InventorySummary {
  itemCount: number;
  batchCount: number;
  pendingRequestCount: number;
  approvedRequestCount: number;
  lowStockCount: number;
  expirySoonCount: number;
  movementCount: number;
}

export interface InventoryDb {
  items: InventoryItem[];
  batches: InventoryBatch[];
  requests: InventoryRequest[];
  weeklyConsumptions: WeeklyConsumption[];
  counts: InventoryCount[];
  movements: InventoryMovement[];
  packages: InventoryPackage[];
  consumptionEvents: InventoryConsumptionEvent[];
  auditLogs: InventoryAuditLog[];
  summary: InventorySummary;
}

export type InventoryExceptionSeverity = "info" | "warning" | "critical";
export type InventoryExceptionStatus = "open" | "processing" | "resolved" | "ignored";

export interface InventoryWorkflowSnapshot {
  pendingInbound?: number;
  pendingApproval?: number;
  pendingIssue?: number;
  inTransit?: number;
  pendingReceipt?: number;
}

export interface InventoryAutomationSnapshot {
  pending?: number;
  succeededToday?: number;
  failed?: number;
  reversalPending?: number;
}

export interface InventoryWeeklySuggestion {
  id: string;
  departmentId?: string;
  departmentName: string;
  itemId: string;
  itemName: string;
  unit: string;
  actualConsumption: number;
  availableQuantity: number;
  safetyQuantity: number;
  suggestedQuantity: number;
  reason?: string;
}

export interface InventoryWorkbench {
  generatedAt?: string;
  activeDepartmentId?: string;
  activeDepartmentName?: string;
  workflow: InventoryWorkflowSnapshot;
  automation: InventoryAutomationSnapshot;
  centralAvailable?: number;
  departmentAvailable?: number;
  lowStockCount?: number;
  expirySoonCount?: number;
  weeklySuggestions?: InventoryWeeklySuggestion[];
}

export interface InventoryLocationBalance {
  id: string;
  locationId: string;
  locationName: string;
  locationType: "central" | "department" | "transit";
  departmentId?: string;
  departmentName?: string;
  itemId: string;
  itemName: string;
  category?: string;
  spec?: string;
  unit: string;
  batchId?: string;
  batchNo?: string;
  expiryDate?: string;
  availableQuantity: number;
  reservedQuantity: number;
  inTransitQuantity: number;
  lowStockThreshold?: number;
  openingConfirmed?: boolean;
}

export interface InventoryException {
  id: string;
  type: string;
  severity: InventoryExceptionSeverity;
  status: InventoryExceptionStatus;
  departmentId?: string;
  departmentName?: string;
  itemId?: string;
  itemName?: string;
  encounterId?: string;
  stage?: string;
  message: string;
  retryable?: boolean;
  occurredAt?: string;
  resolvedAt?: string;
}

export interface InventoryConsumptionRecord {
  id: string;
  commandId?: string;
  encounterId?: string;
  encounterNo?: string;
  patientDisplayName?: string;
  departmentId?: string;
  departmentName?: string;
  stage?: string;
  itemId: string;
  itemName: string;
  unit: string;
  batchId?: string;
  batchNo?: string;
  packageName?: string;
  packageVersion?: string | number;
  quantity: number;
  reversedQuantity?: number;
  status: "pending" | "succeeded" | "failed" | "reversed" | "partially_reversed";
  source?: "package" | "adjustment" | "reversal";
  consumedAt?: string;
  errorMessage?: string;
}

type InventoryApiList<T> = { list?: T[] };

type InventoryWorkbenchApi = {
  departmentId?: string;
  department?: string;
  balances?: InventoryLocationBalanceApi[];
  exceptions?: InventoryExceptionApi[];
  opening?: { confirmed?: boolean };
  flow?: { status?: string; count?: number }[];
  weeklySuggestions?: InventoryWeeklySuggestion[];
};

type InventoryLocationBalanceApi = Partial<InventoryLocationBalance> & {
  quantity?: number;
  department?: string;
  locationType?: string;
};

type InventoryExceptionApi = Partial<Omit<InventoryException, "severity" | "status">> & {
  commandId?: string;
  exceptionType?: string;
  triggerStage?: string;
  department?: string;
  createdAt?: string;
  retryCount?: number;
  severity?: string;
  status?: string;
};

type InventoryConsumptionApi = Partial<Omit<InventoryConsumptionRecord, "status">> & {
  triggerStage?: string;
  department?: string;
  createdAt?: string;
  eventKind?: string;
  status?: string;
};

type InventoryConsumptionPageApi = InventoryApiList<InventoryConsumptionApi> & {
  page?: number;
  size?: number;
  total?: number;
};

export interface InventoryQueryParams {
  departmentId?: string;
  itemId?: string;
  category?: string;
  stage?: string;
  status?: string;
  from?: string;
  to?: string;
}

export interface DepartmentUsageReportParams extends InventoryQueryParams {
  departmentIds?: string[];
  format: "pdf" | "xlsx";
}

export interface InventoryReportDownload {
  blob: Blob;
  filename: string;
}

export type SaveInventoryItemParams = Partial<InventoryItem> & { operator?: string };

export interface InventoryInboundParams {
  itemId: string;
  quantity: number;
  batchNo?: string;
  expiryDate?: string;
  location?: string;
  source?: string;
  operator?: string;
}

export interface InventoryRequestParams {
  itemId?: string;
  quantity?: number;
  lines?: { itemId: string; quantity: number }[];
  department: string;
  applicant: string;
  owner: string;
  reason: string;
  expectedUseWeek: string;
}

export interface InventoryActionParams {
  id: string;
  operator?: string;
  owner?: string;
  receiver?: string;
  issuedQuantity?: number;
  batchId?: string;
  lineId?: string;
  itemId?: string;
  lines?: { id?: string; itemId?: string; issuedQuantity: number }[];
  reason?: string;
}

export interface WeeklyConsumptionParams {
  weekNo: string;
  department: string;
  itemId: string;
  consumedQuantity?: number;
  remainingQuantity?: number;
  nextWeekQuantity?: number;
  adjustedQuantity?: number;
  owner: string;
  abnormalReason?: string;
  operator?: string;
}

export interface ReturnOrScrapParams {
  type: "return" | "scrap";
  itemId: string;
  batchId?: string;
  quantity: number;
  department?: string;
  operator?: string;
  reason: string;
}

export interface InventoryCountParams {
  itemId: string;
  batchId?: string;
  actualQuantity: number;
  operator?: string;
  reason: string;
}

export interface SaveInventoryPackageParams {
  id?: string;
  name: string;
  department: string;
  careType: InventoryCareType;
  effectiveDate?: string;
  lines: InventoryPackageLine[];
  operator?: string;
}

export interface InventoryPackageActionParams {
  id: string;
  operator?: string;
}

const parseInventoryJson = async (result: Response) => {
  if (result.status === 401) {
    handleUnauthorizedResponse();
  }
  const text = await result.text();
  if (text.trim().startsWith("<")) {
    throw new Error("进销存接口未连通，请确认后端已启动，并检查 /inventory-api 代理或部署转发配置");
  }
  try {
    return JSON.parse(text) as ResultData<InventoryDb>;
  } catch {
    throw new Error("进销存接口返回格式异常，请检查后端服务状态");
  }
};

const parseInventoryResponse = async (result: Response): Promise<InventoryDb> => {
  const payload = await parseInventoryJson(result);
  if (!result.ok || String(payload.code) !== "200") {
    throw new Error(payload.msg || `inventory api failed: ${result.status}`);
  }
  return normalizeDb(payload.data);
};

const parseInventoryDataResponse = async <T>(result: Response): Promise<T> => {
  if (result.status === 401) handleUnauthorizedResponse();
  const text = await result.text();
  if (text.trim().startsWith("<")) {
    throw new Error("进销存接口未连通，请检查 /inventory-api 代理或部署转发配置");
  }
  let payload: ResultData<T>;
  try {
    payload = JSON.parse(text) as ResultData<T>;
  } catch {
    throw new Error("进销存接口返回格式异常，请检查后端服务状态");
  }
  if (!result.ok || String(payload.code) !== "200") {
    throw new Error(payload.msg || `inventory api failed: ${result.status}`);
  }
  return payload.data;
};

const buildInventoryQuery = (params: Record<string, unknown> = {}) => {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === "") return;
    if (Array.isArray(value)) {
      value.filter(Boolean).forEach(item => search.append(key, String(item)));
      return;
    }
    search.set(key, String(value));
  });
  const query = search.toString();
  return query ? `?${query}` : "";
};

const getInventoryData = async <T>(path: string, params?: Record<string, unknown>) => {
  const result = await fetch(`${INVENTORY_API_BASE_URL}${path}${buildInventoryQuery(params)}`, {
    headers: authHeaders()
  });
  return response(await parseInventoryDataResponse<T>(result));
};

const readDownloadFilename = (result: Response, fallback: string) => {
  const disposition = result.headers.get("content-disposition") || "";
  const utf8Match = disposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1]);
    } catch {
      return utf8Match[1];
    }
  }
  return disposition.match(/filename="?([^";]+)"?/i)?.[1] || fallback;
};

const normalizeNumber = (value: unknown) => {
  const numberValue = Number(value ?? 0);
  return Number.isFinite(numberValue) ? numberValue : 0;
};

const normalizeDb = (db: InventoryDb): InventoryDb => ({
  items: (db.items ?? []).map(item => ({
    ...item,
    lowStockThreshold: normalizeNumber(item.lowStockThreshold),
    sensitive: Boolean(item.sensitive),
    batchRequired: Boolean(item.batchRequired),
    expiryRequired: Boolean(item.expiryRequired),
    enabled: item.enabled !== false
  })),
  batches: (db.batches ?? []).map(batch => ({ ...batch, quantity: normalizeNumber(batch.quantity) })),
  requests: (db.requests ?? []).map(request => ({
    ...request,
    quantity: normalizeNumber(request.quantity),
    issuedQuantity: normalizeNumber(request.issuedQuantity),
    lines: (request.lines ?? []).map(line => ({
      ...line,
      quantity: normalizeNumber(line.quantity),
      issuedQuantity: normalizeNumber(line.issuedQuantity),
      batchAllocations: (line.batchAllocations ?? []).map(allocation => ({
        ...allocation,
        quantity: normalizeNumber(allocation.quantity)
      }))
    }))
  })),
  weeklyConsumptions: (db.weeklyConsumptions ?? []).map(row => ({
    ...row,
    consumedQuantity: normalizeNumber(row.consumedQuantity),
    remainingQuantity: normalizeNumber(row.remainingQuantity),
    nextWeekQuantity: normalizeNumber(row.nextWeekQuantity)
  })),
  counts: (db.counts ?? []).map(row => ({
    ...row,
    bookQuantity: normalizeNumber(row.bookQuantity),
    actualQuantity: normalizeNumber(row.actualQuantity),
    differenceQuantity: normalizeNumber(row.differenceQuantity)
  })),
  movements: (db.movements ?? []).map(row => ({ ...row, quantity: normalizeNumber(row.quantity) })),
  packages: (db.packages ?? []).map(row => ({
    ...row,
    lines: (row.lines ?? []).map(line => ({ ...line, quantity: normalizeNumber(line.quantity) }))
  })),
  consumptionEvents: (db.consumptionEvents ?? []).map(row => ({
    ...row,
    details: (row.details ?? []).map(detail => ({ ...detail, quantity: normalizeNumber(detail.quantity) }))
  })),
  auditLogs: db.auditLogs ?? [],
  summary: {
    itemCount: normalizeNumber(db.summary?.itemCount),
    batchCount: normalizeNumber(db.summary?.batchCount),
    pendingRequestCount: normalizeNumber(db.summary?.pendingRequestCount),
    approvedRequestCount: normalizeNumber(db.summary?.approvedRequestCount),
    lowStockCount: normalizeNumber(db.summary?.lowStockCount),
    expirySoonCount: normalizeNumber(db.summary?.expirySoonCount),
    movementCount: normalizeNumber(db.summary?.movementCount)
  }
});

const postInventory = async <T extends object>(path: string, payload: T) => {
  const result = await fetch(`${INVENTORY_API_BASE_URL}${path}`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  return parseInventoryResponse(result);
};

const response = <T>(data: T, msg = "成功") =>
  Promise.resolve({
    code: 200 as unknown as string,
    msg,
    data
  } as ResultData<T>);

export const getInventoryDbApi = async () => {
  const result = await fetch(`${INVENTORY_API_BASE_URL}/db`, { headers: authHeaders() });
  return response(await parseInventoryResponse(result));
};

const normalizeLocationType = (value?: string): InventoryLocationBalance["locationType"] => {
  const normalized = String(value || "").toUpperCase();
  if (normalized === "CENTRAL") return "central";
  if (normalized === "IN_TRANSIT" || normalized === "TRANSIT") return "transit";
  return "department";
};

const normalizeLocationBalance = (row: InventoryLocationBalanceApi): InventoryLocationBalance => {
  const locationType = normalizeLocationType(row.locationType);
  const departmentName = row.departmentName || row.department;
  return {
    id: row.id || `${row.locationId || "location"}:${row.itemId || "item"}:${row.batchId || "batch"}`,
    locationId: row.locationId || "",
    locationName:
      row.locationName ||
      (locationType === "central" ? "中央仓库" : locationType === "transit" ? "配送在途" : `${departmentName || "科室"}库`),
    locationType,
    departmentId: row.departmentId,
    departmentName,
    itemId: row.itemId || "",
    itemName: row.itemName || row.itemId || "未命名物资",
    category: row.category,
    spec: row.spec,
    unit: row.unit || "",
    batchId: row.batchId,
    batchNo: row.batchNo,
    expiryDate: row.expiryDate,
    availableQuantity: normalizeNumber(row.availableQuantity ?? row.quantity),
    reservedQuantity: normalizeNumber(row.reservedQuantity),
    inTransitQuantity: locationType === "transit" ? normalizeNumber(row.availableQuantity ?? row.quantity) : 0,
    lowStockThreshold: row.lowStockThreshold === undefined ? undefined : normalizeNumber(row.lowStockThreshold),
    openingConfirmed: row.openingConfirmed
  };
};

const normalizeExceptionSeverity = (value?: string): InventoryExceptionSeverity => {
  const normalized = String(value || "").toUpperCase();
  if (normalized === "HIGH" || normalized === "CRITICAL") return "critical";
  if (normalized === "MEDIUM" || normalized === "WARNING") return "warning";
  return "info";
};

const normalizeExceptionStatus = (value?: string): InventoryExceptionStatus => {
  const normalized = String(value || "").toUpperCase();
  if (normalized === "RESOLVED") return "resolved";
  if (normalized === "RETRYING" || normalized === "PROCESSING") return "processing";
  if (normalized === "IGNORED") return "ignored";
  return "open";
};

const normalizeInventoryException = (row: InventoryExceptionApi): InventoryException => {
  const status = normalizeExceptionStatus(row.status);
  return {
    id: row.id || row.commandId || "",
    type: row.type || row.exceptionType || "INVENTORY_EXCEPTION",
    severity: normalizeExceptionSeverity(row.severity),
    status,
    departmentId: row.departmentId,
    departmentName: row.departmentName || row.department,
    itemId: row.itemId,
    itemName: row.itemName,
    encounterId: row.encounterId,
    stage: row.stage || row.triggerStage,
    message: row.message || "库存任务执行失败",
    retryable: status === "open" || status === "processing",
    occurredAt: row.occurredAt || row.createdAt,
    resolvedAt: row.resolvedAt
  };
};

const normalizeConsumptionStatus = (value?: string): InventoryConsumptionRecord["status"] => {
  const normalized = String(value || "").toUpperCase();
  if (normalized === "FAILED") return "failed";
  if (normalized === "REVERSED") return "reversed";
  if (normalized === "PARTIALLY_REVERSED") return "partially_reversed";
  if (normalized === "PENDING" || normalized === "RETRY") return "pending";
  return "succeeded";
};

const normalizeConsumption = (row: InventoryConsumptionApi): InventoryConsumptionRecord => ({
  id: row.id || row.commandId || "",
  commandId: row.commandId,
  encounterId: row.encounterId,
  encounterNo: row.encounterNo,
  patientDisplayName: row.patientDisplayName,
  departmentId: row.departmentId,
  departmentName: row.departmentName || row.department,
  stage: row.stage || row.triggerStage,
  itemId: row.itemId || "",
  itemName: row.itemName || row.itemId || "未命名物资",
  unit: row.unit || "",
  batchId: row.batchId,
  batchNo: row.batchNo,
  packageName: row.packageName,
  packageVersion: row.packageVersion,
  quantity: normalizeNumber(row.quantity),
  reversedQuantity: normalizeNumber(row.reversedQuantity),
  status: normalizeConsumptionStatus(row.status),
  source:
    row.source ||
    (String(row.eventKind || "")
      .toUpperCase()
      .includes("REVERS")
      ? "reversal"
      : "package"),
  consumedAt: row.consumedAt || row.createdAt,
  errorMessage: row.errorMessage
});

const flowCount = (rows: InventoryWorkbenchApi["flow"], statuses: string[]) =>
  (rows || [])
    .filter(row => statuses.includes(String(row.status || "").toUpperCase()))
    .reduce((sum, row) => sum + normalizeNumber(row.count), 0);

export const getInventoryWorkbenchApi = async (params: InventoryQueryParams = {}) => {
  const result = await getInventoryData<InventoryWorkbenchApi>("/workbench", params as unknown as Record<string, unknown>);
  const raw = result.data;
  const balances = (raw.balances || []).map(normalizeLocationBalance);
  const exceptions = (raw.exceptions || []).map(normalizeInventoryException);
  return response<InventoryWorkbench>({
    activeDepartmentId: raw.departmentId,
    activeDepartmentName: raw.department,
    workflow: {
      pendingIssue: flowCount(raw.flow, ["RESERVED", "PARTIALLY_IN_TRANSIT"]),
      inTransit: flowCount(raw.flow, ["IN_TRANSIT", "PARTIALLY_IN_TRANSIT"]),
      pendingReceipt: flowCount(raw.flow, ["IN_TRANSIT", "PARTIALLY_IN_TRANSIT"])
    },
    automation: {
      failed: exceptions.filter(row => row.status !== "resolved").length
    },
    centralAvailable: balances.some(row => row.locationType === "central")
      ? balances.filter(row => row.locationType === "central").reduce((sum, row) => sum + row.availableQuantity, 0)
      : undefined,
    departmentAvailable: balances.some(row => row.locationType === "department")
      ? balances.filter(row => row.locationType === "department").reduce((sum, row) => sum + row.availableQuantity, 0)
      : undefined,
    weeklySuggestions: raw.weeklySuggestions || []
  });
};

export const getInventoryLocationBalancesApi = async (params: InventoryQueryParams = {}) => {
  const result = await getInventoryData<InventoryApiList<InventoryLocationBalanceApi>>(
    "/department-balances",
    params as unknown as Record<string, unknown>
  );
  return response((result.data.list || []).map(normalizeLocationBalance));
};

export const getInventoryExceptionsApi = async (params: InventoryQueryParams = {}) => {
  const query = { ...params, status: params.status ? String(params.status).toUpperCase() : "OPEN" };
  const result = await getInventoryData<InventoryApiList<InventoryExceptionApi>>(
    "/exception-tasks",
    query as unknown as Record<string, unknown>
  );
  return response((result.data.list || []).map(normalizeInventoryException));
};

export const getInventoryConsumptionsApi = async (params: InventoryQueryParams = {}) => {
  const result = await getInventoryData<InventoryConsumptionPageApi>(
    "/consumption-events",
    params as unknown as Record<string, unknown>
  );
  return response((result.data.list || []).map(normalizeConsumption));
};

export const downloadDepartmentUsageReportApi = async (params: DepartmentUsageReportParams): Promise<InventoryReportDownload> => {
  const { format, stage, ...filters } = params;
  const query = { ...filters, triggerStage: stage };
  const result = await fetch(
    `${INVENTORY_API_BASE_URL}/reports/department-usage.${format}${buildInventoryQuery(query as unknown as Record<string, unknown>)}`,
    { headers: authHeaders() }
  );
  if (result.status === 401) handleUnauthorizedResponse();
  if (!result.ok) {
    const text = await result.text();
    let message = text;
    try {
      const payload = JSON.parse(text) as { msg?: string; message?: string };
      message = payload.msg || payload.message || text;
    } catch {
      // Keep the server text when the response is not JSON.
    }
    throw new Error(message || `科室耗材报表导出失败: ${result.status}`);
  }
  return {
    blob: await result.blob(),
    filename: readDownloadFilename(result, `department-usage.${format}`)
  };
};

export const saveInventoryItemApi = async (params: SaveInventoryItemParams) =>
  response(await postInventory("/items", params), "物资档案已保存");

export const inboundInventoryApi = async (params: InventoryInboundParams) =>
  response(await postInventory("/inbounds", params), "入库记录已保存");

export const createInventoryRequestApi = async (params: InventoryRequestParams) =>
  response(await postInventory("/requests", params), "申领单已提交");

export const approveInventoryRequestApi = async (params: InventoryActionParams) =>
  response(await postInventory("/requests/approve", params), "申领单已审核");

export const issueInventoryRequestApi = async (params: InventoryActionParams) =>
  response(await postInventory("/requests/issue", params), "物资已发放");

export const receiveInventoryRequestApi = async (params: InventoryActionParams) =>
  response(await postInventory("/requests/receive", params), "领取已确认");

export const rejectInventoryRequestApi = async (params: InventoryActionParams) =>
  response(await postInventory("/requests/reject", params), "申领单已驳回");

export const cancelInventoryRequestApi = async (params: InventoryActionParams) =>
  response(await postInventory("/requests/cancel", params), "申领单已撤销");

export const voidInventoryRequestApi = async (params: InventoryActionParams) =>
  response(await postInventory("/requests/void", params), "申领单已作废");

export const saveWeeklyConsumptionApi = async (params: WeeklyConsumptionParams) =>
  response(await postInventory("/weekly-consumptions", params), "周消耗已确认");

export const returnOrScrapInventoryApi = async (params: ReturnOrScrapParams) =>
  response(await postInventory("/movements/return-or-scrap", params), "库存变更已记录");

export const countInventoryApi = async (params: InventoryCountParams) =>
  response(await postInventory("/counts", params), "盘点结果已记录");

export const saveInventoryPackageApi = async (params: SaveInventoryPackageParams) =>
  response(await postInventory("/packages", params), "使用套餐草稿已保存");

export const enableInventoryPackageApi = async (params: InventoryPackageActionParams) =>
  response(await postInventory("/packages/enable", params), "使用套餐已启用");

export const disableInventoryPackageApi = async (params: InventoryPackageActionParams) =>
  response(await postInventory("/packages/disable", params), "使用套餐已停用");

export const retryInventoryConsumptionEventApi = async (params: InventoryPackageActionParams) =>
  response(await postInventory("/consumption-events/retry", params), "自动消耗事件已重试");
