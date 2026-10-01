/**
 * 患者与诊疗分析 · 图表视觉主题
 * ECharts 运行在 canvas 上无法读取 CSS 变量，这里集中维护一份与页面 token 对齐的色板，
 * 按亮/暗两套输出，组件内不再出现散落的十六进制颜色。
 * 取向：以系统青绿为锚点、比 #009688 更深更稳的品牌色；分类色彼此可区分，不使用紫/靛；
 * 深红只留给预警语义，未知类别用灰绿弱化。
 */
export interface AnalysisPalette {
  brand: string;
  brandSoft: string;
  /** 次系列（趋势第二条线） */
  accent: string;
  accentSoft: string;
  /** 热力图连续色阶：浅 → 深 */
  ramp: string[];
  /** 分类色板：饱和、彼此可区分 */
  categorical: string[];
  /** 与 categorical 一一对应的渐变起点（更亮） */
  categoricalLight: string[];
  /** 语义色：积极 / 注意 / 预警 */
  positive: string;
  caution: string;
  cautionSoft: string;
  danger: string;
  unknown: string;
  text: string;
  textMuted: string;
  grid: string;
  track: string;
  surface: string;
  tooltipBg: string;
  tooltipBorder: string;
  tooltipShadow: string;
}

const light: AnalysisPalette = {
  brand: "#0f766e",
  brandSoft: "rgba(15, 118, 110, 0.18)",
  accent: "#f59e0b",
  accentSoft: "rgba(245, 158, 11, 0.14)",
  ramp: ["#ecfdf8", "#b5ecdf", "#5fd3bd", "#14b8a6", "#0f8f84", "#0b5f58"],
  categorical: ["#0d9488", "#0ea5e9", "#f59e0b", "#f97362", "#65a30d", "#2563eb", "#06b6d4", "#eab308", "#14b8a6", "#fb923c"],
  categoricalLight: [
    "#5eead4",
    "#7dd3fc",
    "#fcd34d",
    "#fdb4a8",
    "#a3e635",
    "#93c5fd",
    "#67e8f9",
    "#fde047",
    "#99f6e4",
    "#fdba74"
  ],
  positive: "#10b981",
  caution: "#f59e0b",
  cautionSoft: "#fcd34d",
  danger: "#dc2626",
  unknown: "#b6c3c1",
  text: "#1e293b",
  textMuted: "#64748b",
  grid: "#e6eeec",
  track: "#edf6f4",
  surface: "#ffffff",
  tooltipBg: "rgba(255, 255, 255, 0.97)",
  tooltipBorder: "#cdeae4",
  tooltipShadow: "0 10px 28px rgba(15, 118, 110, 0.14)"
};

const dark: AnalysisPalette = {
  brand: "#2dd4bf",
  brandSoft: "rgba(45, 212, 191, 0.24)",
  accent: "#fbbf24",
  accentSoft: "rgba(251, 191, 36, 0.16)",
  ramp: ["#12302d", "#115e56", "#0f8f84", "#14b8a6", "#5eead4", "#ccfbf1"],
  categorical: ["#2dd4bf", "#38bdf8", "#fbbf24", "#fb8a7a", "#a3e635", "#60a5fa", "#22d3ee", "#facc15", "#5eead4", "#fdba74"],
  categoricalLight: [
    "#99f6e4",
    "#7dd3fc",
    "#fde68a",
    "#fecaca",
    "#d9f99d",
    "#bfdbfe",
    "#a5f3fc",
    "#fef08a",
    "#ccfbf1",
    "#fed7aa"
  ],
  positive: "#34d399",
  caution: "#fbbf24",
  cautionSoft: "#fde68a",
  danger: "#f87171",
  unknown: "#4f5f5e",
  text: "#e2e8f0",
  textMuted: "#94a3b8",
  grid: "#25403d",
  track: "#1d3331",
  surface: "#152523",
  tooltipBg: "rgba(21, 37, 35, 0.97)",
  tooltipBorder: "#2b4a46",
  tooltipShadow: "0 10px 28px rgba(0, 0, 0, 0.45)"
};

export const analysisPalette = (isDark: boolean): AnalysisPalette => (isDark ? dark : light);

/** 分类色的横向渐变（条形图用：左浅右深） */
export function categoricalGradient(palette: AnalysisPalette, index: number, horizontal = true) {
  const i = index % palette.categorical.length;
  return {
    type: "linear" as const,
    x: 0,
    y: horizontal ? 0 : 1,
    x2: horizontal ? 1 : 0,
    y2: 0,
    colorStops: [
      { offset: 0, color: palette.categoricalLight[i] },
      { offset: 1, color: palette.categorical[i] }
    ]
  };
}

/** 首次联系间隔的语义色：提前/当日为积极，晚到逐级加深，8 天以上预警，无留痕/未排期为中性灰 */
export function delayColor(palette: AnalysisPalette, label: string): string | undefined {
  if (label === "提前" || label === "当日") return palette.positive;
  if (label === "晚1-3天") return palette.cautionSoft;
  if (label === "晚4-7天") return palette.caution;
  if (label === "晚8天及以上") return palette.danger;
  if (label === "无联系留痕" || label === "未排期") return palette.unknown;
  return undefined;
}

export const isUnknownLabel = (label: string) => /未知|未记录|未填|无已完成/.test(label);

export const prefersReducedMotion = (): boolean =>
  typeof window !== "undefined" && !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

/** 统一的图表动效：首次进入 480ms，筛选更新只做 300ms 的值过渡 */
export function chartMotion() {
  const reduced = prefersReducedMotion();
  return {
    animation: !reduced,
    animationDuration: 480,
    animationDurationUpdate: 300,
    animationEasing: "cubicOut" as const,
    animationEasingUpdate: "cubicOut" as const
  };
}

const escapeMap: Record<string, string> = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
/** tooltip 走 HTML 渲染，所有来自数据的文本必须转义 */
export const escapeHtml = (value: unknown): string => String(value ?? "").replace(/[&<>"']/g, ch => escapeMap[ch]);
