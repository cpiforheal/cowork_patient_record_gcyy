import type {
  InventoryDepartmentDailyReport,
  InventoryDepartmentDailyReportCalendarDay,
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

export interface MonthReviewStats {
  submittedDays: number;
  averageHeadcount: number | null;
  deductedLines: number;
  exceptions: number;
  pendingExceptions: number;
  draftDays: number;
  state: "ok" | "future" | "before";
}

const SUBMITTED_STATUSES = new Set(["SUBMITTED", "APPROVED", "PARTIALLY_APPROVED"]);

export const formatReceiptQuantity = (value?: number | null) => Number(value || 0).toFixed(2);

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

export const monthReviewStats = (
  days: InventoryDepartmentDailyReportCalendarDay[],
  month: string,
  today: string,
  expectedFrom?: string
): MonthReviewStats => {
  const monthKey = month.slice(0, 7);
  const inMonth = days.filter(day => day.date.slice(0, 7) === monthKey);
  const inScope = inMonth.filter(
    day => (!expectedFrom || day.date >= expectedFrom) && day.date <= today
  );
  const submitted = inScope.filter(
    day => day.reportMode === "QUOTA" && SUBMITTED_STATUSES.has(day.status)
  );
  const headcountDays = submitted.filter(day => Number(day.headcountTotal || 0) > 0);
  const headcountTotal = headcountDays.reduce((sum, day) => sum + Number(day.headcountTotal || 0), 0);
  return {
    submittedDays: submitted.length,
    averageHeadcount: headcountDays.length ? Math.round(headcountTotal / headcountDays.length) : null,
    deductedLines: submitted.reduce((sum, day) => sum + Number(day.lineCount || 0), 0),
    exceptions: submitted.reduce((sum, day) => sum + Number(day.exceptionCount || 0), 0),
    pendingExceptions: submitted.reduce((sum, day) => sum + Number(day.pendingExceptionCount || 0), 0),
    draftDays: inScope.filter(
      day => day.reportMode === "QUOTA" && ["DRAFT", "RETURNED"].includes(day.status)
    ).length,
    state:
      monthKey > today.slice(0, 7)
        ? "future"
        : expectedFrom && monthKey < expectedFrom.slice(0, 7)
          ? "before"
          : "ok"
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

export type CalendarTone = "ok" | "warm" | "draft" | "muted" | "off";

export interface CalendarStatusMeta {
  label: string;
  glyph: string;
  tone: CalendarTone;
  disabled: boolean;
}

export const calendarStatusMeta = (
  day: InventoryDepartmentDailyReportCalendarDay | undefined,
  date: string,
  today: string,
  expectedFrom?: string
): CalendarStatusMeta => {
  if (expectedFrom && date < expectedFrom) {
    return { label: "未启用", glyph: "–", tone: "off", disabled: true };
  }
  if (date > today) {
    return { label: "", glyph: "", tone: "off", disabled: true };
  }
  if (!day || !day.reportId) {
    return { label: "待填", glyph: "○", tone: "warm", disabled: false };
  }
  if (day.reportMode && day.reportMode !== "QUOTA") {
    return { label: "旧口径", glyph: "·", tone: "muted", disabled: false };
  }
  if (day.status === "DRAFT" || day.status === "RETURNED") {
    return { label: "草稿", glyph: "◌", tone: "draft", disabled: false };
  }
  if (day.status === "SUBMITTED") {
    return { label: "已提交", glyph: "✓", tone: "ok", disabled: false };
  }
  if (day.status === "APPROVED" || day.status === "PARTIALLY_APPROVED") {
    return { label: "已确认", glyph: "✓", tone: "ok", disabled: false };
  }
  return { label: "待填", glyph: "○", tone: "warm", disabled: false };
};

export interface CalendarCapsule {
  text: string;
  full: string;
  tone: CalendarTone;
}

export const calendarThirdCapsule = (
  day: InventoryDepartmentDailyReportCalendarDay | undefined
): CalendarCapsule | null => {
  if (!day || day.reportMode !== "QUOTA") return null;
  const pending = Number(day.pendingExceptionCount || 0);
  if (pending > 0) {
    return { text: `例外${pending}`, full: `待审例外 ${pending} 条`, tone: "warm" };
  }
  const shortage = Number(day.shortageCount || 0);
  if (shortage > 0) {
    return { text: `未足额${shortage}`, full: `${shortage} 项未足额扣减`, tone: "warm" };
  }
  const per = Number(day.perPersonCount || 0);
  const fixed = Number(day.fixedCount || 0);
  const period = Number(day.periodCount || 0);
  const onDemand = Number(day.onDemandCount || 0);
  const parts: string[] = [];
  if (fixed) parts.push(`固${fixed}`);
  if (period) parts.push(`周${period}`);
  if (onDemand) parts.push(`按${onDemand}`);
  if (parts.length) {
    return {
      text: parts.join("·"),
      full: `按人次 ${per} 项，每日固定 ${fixed} 项，周期摊消 ${period} 项，按需 ${onDemand} 项`,
      tone: "muted"
    };
  }
  if (per > 0) {
    return { text: `人次${per}`, full: `按人次计 ${per} 项`, tone: "muted" };
  }
  if (Number(day.lineCount || 0) > 0) {
    return { text: `${day.lineCount}项`, full: `当日 ${day.lineCount} 项耗材`, tone: "muted" };
  }
  return null;
};

