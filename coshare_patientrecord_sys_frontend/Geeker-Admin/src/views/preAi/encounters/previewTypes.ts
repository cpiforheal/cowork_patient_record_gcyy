export interface DocumentPreviewRow {
  key: string;
  label: string;
  value: string;
  empty: boolean;
  wide: boolean;
  abnormal?: boolean;
}

export interface DocumentPreviewLabMetric {
  key: string;
  name: string;
  shortName: string;
  value: string;
  unit: string;
  reference: string;
  abnormal: string;
  severity: "NORMAL" | "ABNORMAL" | "CRITICAL";
}

export interface DocumentPreviewLabReport {
  key: string;
  title: string;
  reportDate: string;
  abnormalMetrics: DocumentPreviewLabMetric[];
  normalMetrics: DocumentPreviewLabMetric[];
}

export interface DocumentPreviewSection {
  key: string;
  title: string;
  note?: string;
  rows: DocumentPreviewRow[];
  labReports?: DocumentPreviewLabReport[];
}
