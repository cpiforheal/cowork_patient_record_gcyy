import type { RecordAttachment, UserRole } from "@/config/fieldPermissions";

export interface PatientRow {
  id: string;
  name: string;
  visitNo: string;
  visitDate: string;
  visitType: string;
  doctor: string;
  encounterCount?: number;
  encounterHistory?: PatientEncounter[];
  currentStage: string;
  completedCount: number;
  progressPercent: number;
  status: string;
  riskType: "success" | "warning" | "info" | "danger" | "";
  createdAt: string;
  updatedAt: string;
}

export interface PatientEncounter {
  id: string;
  visitNo: string;
  visitDate: string;
  visitType: string;
  doctor: string;
  createdAt: string;
}

export interface PatientDetail {
  patient: PatientRow;
  fieldValues: Record<string, string>;
  attachments: RecordAttachment[];
  archiveSubmitted: boolean;
  archiveVersion: string;
  generatedAt: string;
}

export interface OperationContext {
  operator?: string;
  role?: string;
}

export interface SystemOperationContext {
  operator?: string;
  operatorRole?: string;
}

export interface CreatePatientParams extends OperationContext {
  name: string;
  visitNo: string;
  visitDate: string;
  visitType: string;
  doctor: string;
  phone?: string;
}

export interface PatientListParams {
  pageNum: number;
  pageSize: number;
  name?: string;
  visitNo?: string;
  visitType?: string;
  status?: string;
  sectionKey?: string;
  visitDateFrom?: string;
  visitDateTo?: string;
}

export interface SaveRecordParams {
  id: string;
  role: string;
  operator?: string;
  values: Record<string, string>;
}

export interface ValidationIssue {
  fieldKey: string;
  fieldLabel: string;
  message: string;
  level: "error" | "warning";
}

export interface AccountRow {
  id: string;
  username: string;
  password?: string;
  name: string;
  department: string;
  role: UserRole;
  roleLabel: string;
  scope: string;
  status: "启用" | "停用";
  createdAt: string;
  updatedAt: string;
}

export interface RoleRow {
  id: string;
  name: string;
  role: UserRole;
  members: number;
  desc: string;
  permissions: string[];
  editableSections: string[];
}

export interface DepartmentRow {
  id: string;
  name: string;
  uploadTypes: string;
  scope: string;
  defaultRole: UserRole;
}

export interface DictRow {
  id: string;
  name: string;
  department: string;
  naming: string;
  required: "是" | "按需";
}

export interface TemplateFieldRule {
  id: string;
  sectionKey: string;
  sectionTitle: string;
  stage: string;
  department: string;
  fieldKey: string;
  fieldLabel: string;
  editors: UserRole[];
  editorLabels: string[];
  required: boolean;
  evidence: string;
  enabled: boolean;
  printable: boolean;
  qualityCheck: boolean;
  sortNo: number;
  updatedAt: string;
}

export interface TemplateFieldRuleParams {
  pageNum: number;
  pageSize: number;
  sectionTitle?: string;
  fieldLabel?: string;
  department?: string;
  editStatus?: string;
  qualityCheck?: string;
}

export interface SharedCaseImportParams extends OperationContext {
  folderName: string;
  patientName: string;
  visitNo: string;
  visitDate: string;
  visitType: string;
  ownerDepartment: string;
  files: Array<string | SharedCaseFileItem>;
}

export interface SharedCaseFileItem {
  fileName: string;
  type?: string;
  typeLabel?: string;
  url?: string;
  contentDataUrl?: string;
  storagePath?: string;
}

export interface SharedCaseImportResult {
  patient: PatientRow;
  documents: RecordAttachment[];
  unassigned: string[];
}

export interface UploadDocumentItem {
  type: string;
  typeLabel: string;
  fileName: string;
  url?: string;
  contentDataUrl?: string;
  storagePath?: string;
}

export interface UploadDocumentsParams {
  patientId: string;
  role: string;
  operator?: string;
  documents: UploadDocumentItem[];
}

export interface UploadDocumentsResult {
  patient: PatientRow;
  documents: RecordAttachment[];
}

export interface AuditLogRow {
  id: string;
  time: string;
  operator: string;
  role: string;
  patient: string;
  patientId?: string;
  module?: string;
  action: string;
  actionCode?: string;
  targetType?: string;
  targetKey?: string;
  targetLabel?: string;
  beforeValue?: string;
  afterValue?: string;
  result?: "success" | "denied";
  detail: string;
}

export interface QualityIssue {
  key: string;
  level: "critical" | "warning";
  levelLabel: string;
  section: string;
  field: string;
  owner: string;
  message: string;
  suggestion: string;
}

export interface QualityReviewRow {
  id: string;
  name: string;
  visitNo: string;
  visitDate: string;
  doctor: string;
  currentStage: string;
  status: string;
  score: number;
  issueCount: number;
  criticalCount: number;
  warningCount: number;
  attachmentCount: number;
  updatedAt: string;
  riskType: PatientRow["riskType"];
}

export interface QualityReviewDetail {
  patient: PatientRow;
  fieldValues: Record<string, string>;
  attachments: RecordAttachment[];
  issues: QualityIssue[];
  score: number;
  criticalCount: number;
  warningCount: number;
  archiveSubmitted: boolean;
  archiveVersion: string;
  generatedAt: string;
}

export interface QualityReviewActionParams {
  id: string;
  role: string;
  operator?: string;
  comment?: string;
}

export interface RecycleDocumentRow {
  id: string;
  patientId: string;
  patient: string;
  visitNo: string;
  fileName: string;
  fieldLabel: string;
  department: string;
  reason: string;
  operator: string;
  time: string;
}

export interface DocumentVoidParams {
  patientId: string;
  documentKey: string;
  reason: string;
  role: string;
  operator?: string;
}

export interface DocumentRestoreParams {
  patientId: string;
  documentKey: string;
  role: string;
  operator?: string;
}

export interface OperationStats {
  totalPatients: number;
  pendingPatients: number;
  reviewPatients: number;
  returnedPatients: number;
  archivedPatients: number;
  overduePatients: number;
  documentCount: number;
  voidedDocumentCount: number;
  qualityPassRate: number;
  averageArchiveHours: number;
  stageBuckets: Array<{ stage: string; count: number }>;
  departmentWorkloads: Array<{ department: string; active: number; voided: number; total: number }>;
}

export interface MaintenanceStatus {
  revision: string;
  checkedAt: string;
  patientCount: number;
  recordCount: number;
  documentCount: number;
  auditLogCount: number;
  snapshotCount: number;
  latestSnapshotAt: string;
  storage: {
    attachmentDir: string;
    totalBytes: number;
    fileCount: number;
    referencedFileCount: number;
    missingFileCount: number;
    usableSpaceBytes: number;
    totalSpaceBytes: number;
  };
}

export interface BackupRunSummary {
  status: "running" | "success" | "failed";
  backupFile: string;
  startedAt: string;
  finishedAt: string;
  sizeBytes: number;
  message: string;
}

export interface BackupStatus {
  backupDir: string;
  enabled: boolean;
  retentionPolicy: string;
  schedule: string;
  running: boolean;
  checkedAt: string;
  backupFileCount: number;
  backupTotalBytes: number;
  usableSpaceBytes: number;
  latestRun?: BackupRunSummary;
}

export interface BackupConfigPayload {
  backupDir: string;
  enabled: boolean;
}

export interface BackupRunResult {
  status: "success";
  backupFile: string;
  sizeBytes: number;
  manifest: {
    backupVersion: number;
    createdAt: string;
    hostName: string;
    revision: string;
    patientCount: number;
    documentCount: number;
    attachmentDir: string;
    database: string;
  };
}

export interface DuplicatePatientGroup {
  key: string;
  reason: "same_name_phone" | "same_visit_no";
  patients: PatientRow[];
}

export interface WorkReminder {
  id: string;
  level: "danger" | "warning" | "info" | "success";
  title: string;
  desc: string;
  path: string;
  count: number;
}

export interface ClinicDb {
  _revision?: string;
  patients: PatientRow[];
  records: Record<string, Record<string, string>>;
  archive: Record<string, { submitted: boolean; version: string; generatedAt: string }>;
  documents?: Record<string, RecordAttachment[]>;
  accounts?: AccountRow[];
  roles?: RoleRow[];
  departments?: DepartmentRow[];
  dictionaries?: DictRow[];
  templateFieldRules?: TemplateFieldRule[];
  auditLogs?: AuditLogRow[];
}
