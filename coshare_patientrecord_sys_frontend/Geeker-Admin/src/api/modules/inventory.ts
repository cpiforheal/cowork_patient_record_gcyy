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
  remainingQuantity: number;
  nextWeekQuantity: number;
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
  consumedQuantity: number;
  remainingQuantity: number;
  nextWeekQuantity: number;
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
