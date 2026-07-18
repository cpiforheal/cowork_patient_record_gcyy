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

export interface AiRecordSummary {
  summary: string;
  patientPortrait?: string;
  clinicalSummary: string;
  managementSummary: string;
  followupSummary: string;
  priorityFocus?: string[];
  overlookedInsights?: string[];
  missingItems: string[];
  riskHints: string[];
  communicationTips?: string[];
  nextFollowupSuggestions?: string[];
  doctorTips: string[];
  disclaimer: string;
  generatedAt: string;
  model: string;
}

export interface AiRecordSummaryParams {
  patientId: string;
  mode?: "summary";
}

export interface AiRuntimeConfig {
  baseUrl: string;
  model: string;
  resourceId?: string;
  voiceType?: string;
  speedRatio?: number;
  enabled: boolean;
  apiKeyConfigured: boolean;
  apiKeyMasked: string;
  usingRuntimeConfig: boolean;
  updatedAt: string;
  updatedBy: string;
}

export interface AiRuntimeConfigPayload {
  baseUrl: string;
  model: string;
  resourceId?: string;
  voiceType?: string;
  speedRatio?: number;
  enabled: boolean;
  apiKey?: string;
  keepExistingApiKey?: boolean;
}

export interface AiModelOption {
  id: string;
  name?: string;
  ownedBy?: string;
  created?: number | string;
}

export interface AiModelDetectionPayload {
  baseUrl: string;
  apiKey?: string;
  keepExistingApiKey?: boolean;
}

export interface AiModelDetectionResult {
  models: AiModelOption[];
  checkedAt: string;
  warning?: string;
}

export interface DoubaoTtsSpeakParams {
  text: string;
  voiceType?: string;
  speedRatio?: number;
}

export interface DoubaoTtsConfigTestPayload extends AiRuntimeConfigPayload {
  text?: string;
}

export interface DoubaoTtsSpeakResult {
  audioBase64: string;
  mimeType: string;
  durationMs?: number;
  model?: string;
  generatedAt?: string;
}

export type AiAssistantType = "public" | "patient" | "quality" | "leader";

export interface AiAssistantMessage {
  role: "user" | "assistant" | "system";
  content: string;
}

export interface AiAssistantAttachment {
  name: string;
  type?: string;
  size?: number;
  dataUrl?: string;
  source?: string;
}

export interface AiAssistantRequest {
  assistantType: AiAssistantType;
  prompt: string;
  messages?: AiAssistantMessage[];
  patientId?: string;
  context?: Record<string, unknown>;
  attachmentIds?: string[];
  attachments?: AiAssistantAttachment[];
}

export interface AiAssistantResponse {
  answer: string;
  model: string;
  generatedAt: string;
  knowledgeSources?: string[];
  warning?: string;
}

export type AiAssistantLogStatus = "success" | "failed";

export interface AiAssistantLog {
  id: string;
  assistantType: AiAssistantType;
  prompt: string;
  promptPreview: string;
  answerPreview?: string;
  answer?: string;
  status: AiAssistantLogStatus;
  operator?: string;
  operatorRole?: string;
  department?: string;
  model?: string;
  latencyMs?: number;
  errorMessage?: string;
  contextSummary?: string;
  systemPromptSummary?: string;
  pageTitle?: string;
  pagePath?: string;
  knowledgeSources?: string[];
  templateCandidate?: boolean;
  sensitive?: boolean;
  createdAt: string;
}

export interface AiAssistantLogListParams {
  pageNum?: number;
  pageSize?: number;
  assistantType?: AiAssistantType | "";
  status?: AiAssistantLogStatus | "";
  role?: string;
  department?: string;
  keyword?: string;
  dateFrom?: string;
  dateTo?: string;
}

export interface AiAssistantLogListResult {
  list: AiAssistantLog[];
  total: number;
  pageNum: number;
  pageSize: number;
  warning?: string;
}

export interface AiAssistantAnalyticsBucket {
  key: string;
  label?: string;
  count: number;
}

export interface AiAssistantFrequentPrompt {
  prompt: string;
  assistantType: AiAssistantType;
  count: number;
  sourceLogId?: string;
}

export interface AiAssistantKnowledgeMiss {
  id?: string;
  keyword: string;
  prompt?: string;
  assistantType?: AiAssistantType;
  count: number;
  latestAt?: string;
  createdAt?: string;
}

export interface AiAssistantAnalytics {
  totalCalls: number;
  todayCalls: number;
  failedCalls: number;
  failureRate: number;
  averageLatencyMs: number;
  assistantTypeBuckets?: AiAssistantAnalyticsBucket[];
  roleBuckets?: AiAssistantAnalyticsBucket[];
  departmentBuckets?: AiAssistantAnalyticsBucket[];
  intentBuckets?: AiAssistantAnalyticsBucket[];
  pageBuckets?: AiAssistantAnalyticsBucket[];
  modelErrorBuckets?: AiAssistantAnalyticsBucket[];
  frequentPrompts?: AiAssistantFrequentPrompt[];
  knowledgeMisses?: AiAssistantKnowledgeMiss[];
  warning?: string;
}

export interface AiPromptTemplateCandidate {
  id: string;
  assistantType: AiAssistantType;
  title: string;
  roleScope?: string;
  recommendedPrompt: string;
  contextNote?: string;
  createdAt?: string;
}

export interface AiPromptTemplateListResult {
  list: AiPromptTemplateCandidate[];
  warning?: string;
}

export interface AiPromptTemplatePayload {
  assistantType: AiAssistantType;
  title: string;
  roleScope?: string;
  recommendedPrompt: string;
  contextNote?: string;
}

export interface AiDocumentTemplate {
  id: string;
  name: string;
  description?: string;
  documentType?: string;
  enabled?: boolean;
}

export interface AiDocumentTemplateResult {
  templates: AiDocumentTemplate[];
  defaultTemplateId?: string;
}

export interface AiDocumentRequestPayload {
  title: string;
  documentType?: string;
  docType?: string;
  content: string;
  templateId?: string;
}

export interface AiDocumentBlock {
  type: "heading" | "paragraph" | "list" | "table" | string;
  text?: string;
  level?: number;
  items?: string[];
  rows?: string[][];
}

export interface AiDocumentPreview {
  title: string;
  documentType?: string;
  docType?: string;
  templateName?: string;
  aiRequired?: boolean;
  paragraphCount?: number;
  headingCount?: number;
  listCount?: number;
  tableCount?: number;
  content?: string;
  blocks: AiDocumentBlock[];
  warning?: string;
}

export interface GeneratedAiDocument {
  id: string;
  title: string;
  documentType?: string;
  docType?: string;
  templateName?: string;
  fileName: string;
  downloadUrl?: string;
  content?: string;
  preview?: AiDocumentBlock[];
  hash?: string;
  generatedAt: string;
  operator?: string;
}

export interface AiDocumentGenerateResult {
  document: GeneratedAiDocument;
  preview?: AiDocumentPreview;
}

export type AiDocumentTaskStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED";

export interface AiDocumentTask {
  taskId: string;
  status: AiDocumentTaskStatus;
  errorMessage?: string;
  attempt: number;
  createdAt: string;
  startedAt?: string;
  finishedAt?: string;
  updatedAt: string;
  result?: AiDocumentGenerateResult;
}

export interface GeneratedMedicalRecord {
  id: string;
  patientId: string;
  version: number;
  status: "draft" | "finalized" | "voided";
  content?: string;
  contentHash: string;
  model: string;
  templateName?: string;
  templateVersion?: string;
  fileName?: string;
  downloadUrl?: string;
  operator: string;
  operatorRole: string;
  generatedAt: string;
  finalizedAt: string;
  voidedAt: string;
  voidReason: string;
  sourceFieldSnapshot?: Record<string, unknown>;
}

export interface MedicalRecordTemplateField {
  key: string;
  label: string;
  section: string;
  kind: "input" | "textarea" | "select" | "date" | string;
  required: boolean;
  aiPolishable: boolean;
  placeholder: string;
  options?: string[];
  defaultValue?: string;
  controlType?: string;
  renderMode?: string;
  templateLocked?: boolean;
  multiple?: boolean;
  joiner?: string;
  inputType?: string;
  targetUse?: "dynamic" | "formOnly" | string;
  sources?: string[];
  viewerRoles?: UserRole[];
  editorRoles?: UserRole[];
  sourceArchiveKeys?: string[];
}

export interface MedicalRecordTemplateSection {
  section: string;
  fields: MedicalRecordTemplateField[];
}

export interface MedicalRecordTemplateStatus {
  name: string;
  templateVersion?: string;
  configured: boolean;
  promptConfigurable: boolean;
  templateSource: string;
  commandSource: string;
  disclaimer: string;
  sections: string[];
  requiredFields?: { key: string; label: string; section?: string }[];
  fieldMatrix?: MedicalRecordTemplateSection[];
  unboundFields?: string[];
}

export interface MedicalRecordGenerateResult {
  record: GeneratedMedicalRecord;
  missingItems: string[];
  disclaimer: string;
}

export interface MedicalRecordPrecheckResult {
  ready: boolean;
  missingItems: string[];
  unboundFields?: string[];
  fieldMatrix?: MedicalRecordTemplateSection[];
  disclaimer: string;
}

export interface MedicalRecordWorkspaceSaveResult {
  values: Record<string, string>;
  missingItems: string[];
  disclaimer: string;
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
  departmentIds?: string[];
  primaryDepartmentId?: string;
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
  code?: string;
  name: string;
  status?: "ACTIVE" | "INACTIVE";
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

export interface SharedCasePreviewParams extends SharedCaseImportParams {}

export interface SharedCaseFileItem {
  fileName: string;
  type?: string;
  typeLabel?: string;
  url?: string;
  contentDataUrl?: string;
  storagePath?: string;
}

export interface LegacyFieldMapping {
  id: string;
  fieldKey: string;
  fieldLabel: string;
  sectionTitle: string;
  currentValue: string;
  importValue: string;
  sourceFile: string;
  confidence: "高" | "中" | "低";
  reason: string;
  conflict: boolean;
  selected: boolean;
}

export interface LegacyAttachmentMapping {
  id: string;
  fileName: string;
  type: string;
  typeLabel: string;
  fieldKey: string;
  fieldLabel: string;
  department: string;
  source: "document" | "report" | "followup" | "unassigned";
  selected: boolean;
}

export interface SharedCasePreviewResult {
  previewId: string;
  patientMatch: "matchedByVisitNo" | "matchedByName" | "newPatient";
  patient?: PatientRow;
  patientName: string;
  visitNo: string;
  visitDate: string;
  visitType: string;
  fieldMappings: LegacyFieldMapping[];
  attachmentMappings: LegacyAttachmentMapping[];
  unassigned: string[];
  status: "待预检" | "已识别待确认" | "已采纳入档" | "部分待分拣";
}

export interface SharedCaseCommitParams extends OperationContext {
  preview: SharedCasePreviewResult;
  folderName: string;
  patientName: string;
  visitNo: string;
  visitDate: string;
  visitType: string;
  ownerDepartment: string;
  files: Array<string | SharedCaseFileItem>;
  acceptedFieldMappingIds: string[];
  acceptedAttachmentMappingIds: string[];
  overwriteConflicts?: boolean;
}

export interface SharedCaseImportResult {
  patient: PatientRow;
  documents: RecordAttachment[];
  unassigned: string[];
  appliedFields?: LegacyFieldMapping[];
  skippedFields?: LegacyFieldMapping[];
  preview?: SharedCasePreviewResult;
}

export interface UploadDocumentItem {
  type: string;
  typeLabel: string;
  fileName: string;
  url?: string;
  contentDataUrl?: string;
  storagePath?: string;
  remark?: string;
}

export interface UploadDocumentsParams {
  patientId: string;
  role: string;
  operator?: string;
  sourceRole?: string;
  batchId?: string;
  batchName?: string;
  autoClassify?: boolean;
  remark?: string;
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

export interface PatientTimelineEvent {
  id: string;
  time: string;
  source: "patient" | "encounter" | "record" | "followup" | "document" | "collaboration" | "archive" | "audit" | "system";
  module: string;
  sourceId: string;
  title: string;
  detail: string;
  level: "primary" | "success" | "warning" | "danger" | "info";
  operator?: string;
  targetLabel?: string;
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
    scanSkipped?: boolean;
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

export interface BackupDirectorySelection {
  backupDir: string;
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
