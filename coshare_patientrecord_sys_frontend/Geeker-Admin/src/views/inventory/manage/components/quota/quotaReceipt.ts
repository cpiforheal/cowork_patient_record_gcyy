import type {
  InventoryDepartmentDailyReport,
  InventoryDepartmentDailyReportLine
} from "@/api/modules/inventory";

export type ReceiptGroupKey = "per" | "daily" | "period";

export interface ReceiptGroup {
  key: ReceiptGroupKey;
  label: string;
  lines: InventoryDepartmentDailyReportLine[];
}

export interface ReceiptCounts {
  auto: number;
  deducted: number;
  shortage: number;
  onDemand: number;
  exceptions: number;
}

export const formatReceiptQuantity = (value?: number | null) => Number(value || 0).toFixed(2);

const round3 = (value: number) => Math.round(value * 1000) / 1000;

export interface QuotaHeadcountInput {
  outpatient: number;
  inpatient: number;
  manual: number;
  total: number;
}

const headcountForBasis = (basis: string | undefined, headcount: QuotaHeadcountInput) => {
  if (basis === "OUTPATIENT") return headcount.outpatient;
  if (basis === "INPATIENT") return headcount.inpatient;
  if (basis === "COMBINED") return headcount.total;
  return headcount.manual;
};

export const liveQuotaQuantity = (
  line: InventoryDepartmentDailyReportLine,
  headcount: QuotaHeadcountInput
) => {
  if (line.exceptionOnly || line.includedInQuota === false) return 0;
  const numerator = line.sourceNumerator ?? line.standardQuantity;
  const denominator = Number(line.sourceDenominator ?? 1);
  if (numerator == null || denominator <= 0) return Number(line.quotaQuantity || 0);
  const days = Math.max(Number(line.periodDays || 1), 1);
  const isPerPerson = line.bindingType === "PER_PERSON";
  const volume = isPerPerson ? headcountForBasis(line.headcountBasis, headcount) : 1;
  const base = isPerPerson
    ? (Number(numerator) * volume) / denominator
    : Number(numerator) / denominator;
  return Math.max(0, round3(base / days + Number(line.fixedAdjustment || 0)));
};

export const groupReceiptLines = (lines?: InventoryDepartmentDailyReportLine[]): ReceiptGroup[] => {
  const groups: ReceiptGroup[] = [
    { key: "per", label: "按人次计", lines: [] },
    { key: "daily", label: "每日固定", lines: [] },
    { key: "period", label: "周期摊消", lines: [] }
  ];
  for (const line of lines || []) {
    if (line.exceptionOnly || Number(line.quotaQuantity || 0) <= 0) continue;
    const group = line.bindingType === "PER_PERSON" ? groups[0] : Number(line.periodDays || 1) > 1 ? groups[2] : groups[1];
    group.lines.push(line);
  }
  return groups.filter(group => group.lines.length);
};

export const receiptCounts = (report?: InventoryDepartmentDailyReport): ReceiptCounts => {
  const lines = (report?.lines || []).filter(
    line => !line.exceptionOnly && Number(line.quotaQuantity || 0) > 0
  );
  const deducted = lines.filter(
    line => Number(line.quotaDeductedQuantity || 0) >= Number(line.quotaQuantity || 0)
  ).length;
  return {
    auto: lines.length,
    deducted,
    shortage: lines.length - deducted,
    onDemand: (report?.lines || []).filter(line => line.exceptionOnly).length,
    exceptions: Number(report?.pendingExceptions || 0)
  };
};

export const deductionStatus = (line: InventoryDepartmentDailyReportLine) => {
  const quota = Number(line.quotaQuantity || 0);
  const deducted = Number(line.quotaDeductedQuantity || 0);
  if (line.exceptionOnly) return { label: "按需", tone: "muted" };
  if (quota <= 0) return { label: "无应耗", tone: "muted" };
  if (deducted >= quota) return { label: "已扣", tone: "success" };
  if (deducted > 0) return { label: "部分扣减", tone: "warning" };
  return { label: "库存不足", tone: "warning" };
};

export const exceptionStatusLabel = (status?: string | null) => {
  if (status === "PENDING") return "待审核";
  if (status === "APPROVED") return "已通过";
  if (status === "PARTIALLY_APPROVED") return "部分通过";
  if (status === "REJECTED") return "已驳回";
  return "未上报";
};

export const exceptionStatusTone = (status?: string | null) => {
  if (status === "APPROVED") return "success";
  if (status === "PARTIALLY_APPROVED") return "warning";
  if (status === "REJECTED") return "danger";
  return "info";
};

export const quotaDayStatus = (report?: InventoryDepartmentDailyReport) => {
  if (!report?.id) return { label: "未填报", tone: "draft" };
  if (report.locked) return { label: "已锁定", tone: "locked" };
  if (report.status === "DRAFT") return { label: "草稿", tone: "draft" };
  if (report.status === "APPROVED") return { label: "已确认", tone: "success" };
  if (report.status === "SUBMITTED") return { label: "已提交", tone: "success" };
  if (report.status === "PARTIALLY_APPROVED") return { label: "部分确认", tone: "warning" };
  return { label: report.status || "未填报", tone: "info" };
};

