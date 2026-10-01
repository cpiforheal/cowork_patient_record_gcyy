export const dimensions = [
  "gender",
  "ageBand",
  "region",
  "diagnosis",
  "operation",
  "primaryOperation",
  "status",
  "tcmDisease",
  "syndrome",
  "complaint",
  "contactState",
  "delayBand",
  "patientSource",
  "examType",
  "weekday",
  "stage"
] as const;
export type AnalysisView = "overview" | "population" | "clinical" | "complaints" | "followup";
export type AnalysisDimension = (typeof dimensions)[number];
export interface AnalysisQuery {
  from: string;
  to: string;
  view: AnalysisView;
  granularity: "day" | "week" | "month";
  metric: "visits" | "patients";
  basis: "due" | "contact";
  ageMin?: number;
  ageMax?: number;
  unscheduled: boolean;
  filters: Partial<Record<AnalysisDimension, string[]>>;
}

export const formatDate = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;

export function defaultAnalysisQuery(now = new Date()): AnalysisQuery {
  const start = new Date(now);
  start.setDate(start.getDate() - 89);
  return {
    from: formatDate(start),
    to: formatDate(now),
    view: "overview",
    granularity: "week",
    metric: "visits",
    basis: "due",
    unscheduled: false,
    filters: {}
  };
}

type QueryValues = Record<string, string | null | undefined | (string | null)[]>;
const values = (value: QueryValues[string]): string[] => (Array.isArray(value) ? value : [value]).filter((v): v is string => !!v);
const first = (value: QueryValues[string]) => values(value)[0];
const age = (value: string | undefined) => (value && /^\d{1,3}$/.test(value) ? Number(value) : undefined);

export function readAnalysisQuery(route: QueryValues): AnalysisQuery {
  const query = defaultAnalysisQuery();
  for (const key of ["from", "to"] as const) {
    const value = first(route[key]);
    if (value && /^\d{4}-\d{2}-\d{2}$/.test(value)) query[key] = value;
  }
  const view = first(route.view);
  if (["overview", "population", "clinical", "complaints", "followup"].includes(view)) query.view = view as AnalysisView;
  const granularity = first(route.granularity);
  if (["day", "week", "month"].includes(granularity)) query.granularity = granularity as AnalysisQuery["granularity"];
  query.metric = first(route.metric) === "patients" ? "patients" : "visits";
  query.basis = first(route.basis) === "contact" ? "contact" : "due";
  query.ageMin = age(first(route.ageMin));
  query.ageMax = age(first(route.ageMax));
  query.unscheduled = query.view === "followup" && first(route.unscheduled) === "true";
  dimensions.forEach(key => {
    const selected = [...new Set(values(route[key]))];
    if (selected.length && (query.view === "followup" || !["contactState", "delayBand"].includes(key))) {
      query.filters[key] = selected;
    }
  });
  return query;
}

export function analysisRoute(query: AnalysisQuery): Record<string, string | string[]> {
  const result: Record<string, string | string[]> = {
    from: query.from,
    to: query.to,
    view: query.view,
    granularity: query.granularity,
    metric: query.metric
  };
  if (query.view === "followup") result.basis = query.basis;
  if (query.ageMin !== undefined) result.ageMin = String(query.ageMin);
  if (query.ageMax !== undefined) result.ageMax = String(query.ageMax);
  if (query.unscheduled && query.view === "followup") result.unscheduled = "true";
  dimensions.forEach(key => {
    const selected = query.filters[key];
    if (selected?.length && (query.view === "followup" || !["contactState", "delayBand"].includes(key))) {
      result[key] = [...selected];
    }
  });
  return result;
}

export function analysisSearchParams(query: AnalysisQuery) {
  const params = new URLSearchParams();
  Object.entries(analysisRoute(query)).forEach(([key, value]) => {
    (Array.isArray(value) ? value : [value]).forEach(item => params.append(key, item));
  });
  return params;
}

export function applyChartFilters(query: AnalysisQuery, filters: Record<string, string[]>): AnalysisQuery {
  const next: AnalysisQuery = { ...query, filters: { ...query.filters } };
  Object.entries(filters).forEach(([key, selected]) => {
    if (key === "from" || key === "to") next[key] = selected[0];
    else if (dimensions.includes(key as AnalysisDimension)) next.filters[key as AnalysisDimension] = [...selected];
  });
  return next;
}
