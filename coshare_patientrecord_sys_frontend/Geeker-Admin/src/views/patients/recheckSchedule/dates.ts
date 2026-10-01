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

/** 复查常用间隔，供登记 / 改期快捷选择 */
export const QUICK_OFFSETS = [
  { label: "明天", days: 1 },
  { label: "后天", days: 2 },
  { label: "1 周后", days: 7 },
  { label: "2 周后", days: 14 },
  { label: "1 个月后", days: 30 }
];
