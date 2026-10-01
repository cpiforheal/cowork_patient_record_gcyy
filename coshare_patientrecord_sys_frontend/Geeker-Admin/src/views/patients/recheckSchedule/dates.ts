import dayjs from "dayjs";

const WEEKDAYS = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

export const fmt = (date: dayjs.Dayjs) => date.format("YYYY-MM-DD");

export const weekday = (date: string) => WEEKDAYS[dayjs(date).day()];

/** 相对今天的口语标签：今天 / 明天 / 后天 / 昨天，其余显示星期。 */
export const relativeLabel = (date: string, today: string) => {
  const diff = dayjs(date).diff(dayjs(today), "day");
  if (diff === 0) return "今天";
  if (diff === 1) return "明天";
  if (diff === 2) return "后天";
  if (diff === -1) return "昨天";
  return weekday(date);
};

/** 例："10/02 周五（明天）" */
export const dateTitle = (date: string, today: string) => {
  const rel = relativeLabel(date, today);
  const base = `${dayjs(date).format("MM/DD")} ${weekday(date)}`;
  return rel.startsWith("周") ? base : `${base}（${rel}）`;
};

export type DateTone = "past" | "today" | "near" | "far";

/**
 * 复查日期的距离层级，用于日期选择器的颜色强调：
 * today 今天 / near 7 天内 / far 更远 / past 已过（补登）。
 */
export const distance = (date: string, today: string): { tone: DateTone; text: string } => {
  if (!date) return { tone: "far", text: "未选日期" };
  const diff = dayjs(date).diff(dayjs(today), "day");
  if (diff < 0) return { tone: "past", text: diff === -1 ? "昨天 · 补登" : `${-diff} 天前 · 补登` };
  if (diff === 0) return { tone: "today", text: "今天" };
  const text = diff === 1 ? "明天" : diff === 2 ? "后天" : diff % 7 === 0 ? `${diff / 7} 周后` : `${diff} 天后`;
  return { tone: diff <= 7 ? "near" : "far", text };
};

/** 月历网格：周一开头，固定 6 周 42 天 */
export const monthGrid = (month: string) => {
  const first = dayjs(month).startOf("month");
  const start = first.subtract((first.day() + 6) % 7, "day");
  return Array.from({ length: 42 }, (_, i) => fmt(start.add(i, "day")));
};

/** 复查常用间隔，供登记 / 改期快捷选择 */
export const QUICK_OFFSETS = [
  { label: "明天", days: 1 },
  { label: "后天", days: 2 },
  { label: "1 周后", days: 7 },
  { label: "2 周后", days: 14 },
  { label: "1 个月后", days: 30 }
];

/** 批量粘贴姓名时的分隔符：顿号、逗号、分号、空白 */
export const NAME_SEPARATORS = /[、，,;；\s]+/;
