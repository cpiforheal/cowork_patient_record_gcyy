export interface LabMetricValue {
  value: string;
  reference?: string;
}

interface ReferenceRange {
  min?: number;
  max?: number;
  exclusiveMin?: boolean;
  exclusiveMax?: boolean;
}

export const parseReferenceRange = (reference = ""): ReferenceRange | undefined => {
  const normalized = reference.replace(/[～—–至]/g, "-").replace(/\s+/g, "");
  const range = normalized.match(/^(-?\d+(?:\.\d+)?)-(-?\d+(?:\.\d+)?)$/);

  if (range) {
    return {
      min: Number(range[1]),
      max: Number(range[2])
    };
  }

  const upper = normalized.match(/^(?:≤|<=|<)(-?\d+(?:\.\d+)?)$/);
  if (upper) {
    return {
      max: Number(upper[1]),
      exclusiveMax: normalized.startsWith("<") && !normalized.startsWith("<=")
    };
  }

  const lower = normalized.match(/^(?:≥|>=|>)(-?\d+(?:\.\d+)?)$/);
  if (lower) {
    return {
      min: Number(lower[1]),
      exclusiveMin: normalized.startsWith(">") && !normalized.startsWith(">=")
    };
  }

  return undefined;
};

export const labMetricAbnormalLabel = (metric: LabMetricValue) => {
  const value = String(metric.value || "").trim();
  const reference = String(metric.reference || "").trim();

  if (!value || !reference || value === "未查") return "";

  const numericValue = Number(value.replace(/,/g, ""));
  const range = parseReferenceRange(reference);

  if (range && Number.isFinite(numericValue)) {
    if (range.min !== undefined && (range.exclusiveMin ? numericValue <= range.min : numericValue < range.min)) {
      return "偏低";
    }
    if (range.max !== undefined && (range.exclusiveMax ? numericValue >= range.max : numericValue > range.max)) {
      return "偏高";
    }
    return "";
  }

  const normalQualitative = ["阴性", "-", "正常"];
  if (normalQualitative.includes(reference)) return normalQualitative.includes(value) ? "" : "异常";

  return "";
};

export const isLabMetricAbnormal = (metric: LabMetricValue) => Boolean(labMetricAbnormalLabel(metric));
