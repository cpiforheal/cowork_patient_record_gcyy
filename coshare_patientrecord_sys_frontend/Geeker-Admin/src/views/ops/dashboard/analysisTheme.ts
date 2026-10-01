/**
 * 患者与诊疗分析 · 图表视觉主题
 * ECharts 运行在 canvas 上无法读取 CSS 变量，这里集中维护一份与页面 token 对齐的色板，
 * 按亮/暗两套输出，组件内不再出现散落的十六进制颜色。
 * 取向：高饱和、积极明快；每个分类有自己的渐变色对，未知类别用浅蓝灰弱化。
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
  brand: "#4f46e5",
  brandSoft: "rgba(79, 70, 229, 0.22)",
  accent: "#f97316",
  accentSoft: "rgba(249, 115, 22, 0.16)",
  ramp: ["#e0f7ff", "#a5e8fb", "#38bdf8", "#3b82f6", "#6366f1", "#7c3aed"],
  categorical: ["#4f46e5", "#06b6d4", "#10b981", "#f59e0b", "#f43f5e", "#8b5cf6", "#0ea5e9", "#ec4899", "#84cc16", "#f97316"],
  categoricalLight: [
    "#818cf8",
    "#67e8f9",
    "#6ee7b7",
    "#fcd34d",
    "#fda4af",
    "#c4b5fd",
    "#7dd3fc",
    "#f9a8d4",
    "#bef264",
    "#fdba74"
  ],
  unknown: "#b8c4d6",
  text: "#1e293b",
  textMuted: "#64748b",
  grid: "#e8edf5",
  track: "#eef2ff",
  surface: "#ffffff",
  tooltipBg: "rgba(255, 255, 255, 0.97)",
  tooltipBorder: "#e0e7ff",
  tooltipShadow: "0 10px 28px rgba(79, 70, 229, 0.14)"
};

const dark: AnalysisPalette = {
  brand: "#818cf8",
  brandSoft: "rgba(129, 140, 248, 0.28)",
  accent: "#fb923c",
  accentSoft: "rgba(251, 146, 60, 0.2)",
  ramp: ["#1e2a4a", "#1d4ed8", "#2563eb", "#38bdf8", "#818cf8", "#c084fc"],
  categorical: ["#818cf8", "#22d3ee", "#34d399", "#fbbf24", "#fb7185", "#a78bfa", "#38bdf8", "#f472b6", "#a3e635", "#fb923c"],
  categoricalLight: [
    "#a5b4fc",
    "#67e8f9",
    "#6ee7b7",
    "#fde68a",
    "#fda4af",
    "#c4b5fd",
    "#7dd3fc",
    "#f9a8d4",
    "#d9f99d",
    "#fdba74"
  ],
  unknown: "#55607a",
  text: "#e2e8f0",
  textMuted: "#94a3b8",
  grid: "#2a3350",
  track: "#232c47",
  surface: "#1a2035",
  tooltipBg: "rgba(26, 32, 53, 0.97)",
  tooltipBorder: "#343f63",
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

export const isUnknownLabel = (label: string) => /未知|未记录|未填/.test(label);

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
