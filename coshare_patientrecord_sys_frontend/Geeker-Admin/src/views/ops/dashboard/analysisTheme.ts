/**
 * 患者与诊疗分析 · 图表视觉主题
 * ECharts 运行在 canvas 上无法读取 CSS 变量，这里集中维护一份与页面 token 对齐的色板，
 * 按亮/暗两套输出，组件内不再出现散落的十六进制颜色。
 */
export interface AnalysisPalette {
  brand: string;
  brandSoft: string;
  /** 单系列连续色阶：浅 → 深，按数值映射 */
  ramp: string[];
  /** 分类色板：低饱和、彼此可区分 */
  categorical: string[];
  unknown: string;
  text: string;
  textMuted: string;
  grid: string;
  surface: string;
  tooltipBg: string;
  tooltipBorder: string;
  tooltipShadow: string;
}

const light: AnalysisPalette = {
  brand: "#1f7a8c",
  brandSoft: "rgba(31, 122, 140, 0.12)",
  ramp: ["#d5ebee", "#9fd0d8", "#5fa9b7", "#2f8a9c", "#1f7a8c", "#165e6c"],
  categorical: ["#1f7a8c", "#6fa8dc", "#e0a458", "#8fbf9f", "#b48ead", "#c97b63"],
  unknown: "#cfd6da",
  text: "#344047",
  textMuted: "#7a868d",
  grid: "#edf1f3",
  surface: "#ffffff",
  tooltipBg: "rgba(255, 255, 255, 0.96)",
  tooltipBorder: "#e3e8eb",
  tooltipShadow: "0 8px 24px rgba(16, 24, 40, 0.08)"
};

const dark: AnalysisPalette = {
  brand: "#4fb3c4",
  brandSoft: "rgba(79, 179, 196, 0.16)",
  ramp: ["#1d3a40", "#215661", "#2a7685", "#3a95a5", "#4fb3c4", "#7ccbd8"],
  categorical: ["#4fb3c4", "#7fb2e5", "#e3ad6a", "#93c7a4", "#bf9cb9", "#d48a73"],
  unknown: "#4a5459",
  text: "#d4dbde",
  textMuted: "#8b979d",
  grid: "#2a3236",
  surface: "#1d2326",
  tooltipBg: "rgba(29, 35, 38, 0.96)",
  tooltipBorder: "#343d42",
  tooltipShadow: "0 8px 24px rgba(0, 0, 0, 0.35)"
};

export const analysisPalette = (isDark: boolean): AnalysisPalette => (isDark ? dark : light);

/** 按数值在 [0, max] 内取连续色阶，最小值也保持可见 */
export function rampColor(palette: AnalysisPalette, value: number, max: number): string {
  if (max <= 0) return palette.ramp[2];
  const ratio = Math.min(1, Math.max(0, value / max));
  // 跳过最浅的两档，保证柱条在白底上有足够对比
  const usable = palette.ramp.slice(2);
  return usable[Math.min(usable.length - 1, Math.round(ratio * (usable.length - 1)))];
}

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
