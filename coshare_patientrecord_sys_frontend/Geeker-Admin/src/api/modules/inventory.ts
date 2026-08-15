import type { ResultData } from "@/api/interface";
import { authHeaders, handleUnauthorizedResponse } from "./authToken";

const INVENTORY_API_BASE_URL = import.meta.env.VITE_INVENTORY_API_BASE_URL || "/inventory-api";

export interface InventoryItem {
  id: string;
  name: string;
  category: string;
  spec: string;
  unit: string;
  baseUnit?: string;
  issueUnit?: string;
  quantityPrecision?: number;
  normalizationStatus?: "standard" | "pending" | "conflict";
  effectiveLifeManaged?: boolean;
  location: string;
  lowStockThreshold: number;
  sensitive: boolean;
  batchRequired: boolean;
  expiryRequired: boolean;
  enabled: boolean;
}

export type InventoryDepartmentDraftCareType = "outpatient" | "inpatient" | "other";

export interface InventoryDepartmentDraftLine {
  id: string;
  sourceRow?: number;
  serviceGroup: string;
  careType: InventoryDepartmentDraftCareType;
  materialName: string;
  unit: string;
  standardQuantity: number | null;
  unitPrice?: number | null;
  volumeOverride?: number | null;
  manualAdjustment?: number;
  fixedAdjustment?: number;
  measurementScope?: "OUTPATIENT" | "INPATIENT" | "COMBINED" | "OTHER";
  lineKey?: string;
  actualQuantity?: number | null;
  referenceQuantity?: number;
  calculatedQuantity?: number;
  volume?: number;
  isSupplemental?: boolean;
  isSpecial?: boolean;
  specialAdminNote?: string;
  specialDailyNote?: string;
}

export interface InventoryDepartmentDailyDraft {
  id?: string;
  exists?: boolean;
  departmentKey: string;
  departmentName: string;
  businessDate: string;
  templateVersion?: string;
  quotaVersionId?: string;
  quotaVersionCode?: string;
  quotaEffectiveDate?: string;
  frozenQuota?: boolean;
  monthDays?: number;
  revision: number;
  operator?: string;
  operatorUsername?: string;
  updatedAt?: string;
  groupVolumes?: Record<string, number>;
  lines?: InventoryDepartmentDraftLine[];
}

export interface InventoryDepartmentDailyDraftExportPayload {
  id?: string;
  exists?: boolean;
  departmentKey: string;
  departmentName: string;
  businessDate: string;
  templateVersion?: string;
  monthDays: number;
  revision: number;
  groupVolumes: Record<string, number>;
  lines: InventoryDepartmentDraftLine[];
}

export interface InventoryDepartmentDailyDraftSummary {
  businessDate: string;
  list: InventoryDepartmentDailyDraft[];
}

export interface InventoryQuotaVersion {
  id: string;
  versionCode: string;
  effectiveDate: string;
  status: string;
  createdBy?: string;
  confirmedBy?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface InventoryQuotaRule {
  id: string;
  versionId: string;
  departmentKey: string;
  departmentName: string;
  sourceRow: number;
  serviceGroup: string;
  careType: string;
  materialName: string;
  unit: string;
  standardQuantity: number | null;
  fixedAdjustment: number;
  measurementScope: "OUTPATIENT" | "INPATIENT" | "COMBINED" | "OTHER";
  enabled: boolean;
}

export interface InventoryQuotaGovernance {
  queryDate: string;
  activeVersion: InventoryQuotaVersion | null;
  versions: InventoryQuotaVersion[];
  rules: InventoryQuotaRule[];
}

export interface InventoryDailyRollupQuery {
  [key: string]: string | undefined;
  date?: string;
  from?: string;
  to?: string;
}

export type InventoryAdminRiskLevel =
  | "NORMAL"
  | "ATTENTION"
  | "ABNORMAL"
  | "UNVERIFIED"
  | "SPECIAL"
  | "SPECIAL_PENDING_NOTE"
  | "HISTORICAL_UNFROZEN";

export type InventoryDepartmentDayStatus = "MISSING" | "ZERO_VOLUME" | "SUBMITTED" | "ATTENTION" | "ABNORMAL";

export interface InventoryAdminMaterialSummary {
  materialName: string;
  unit: string;
  theoreticalQuantity: number;
  actualQuantity: number | null;
  mainQuantity: number;
  specialLineCount: number;
  lineCount: number;
  reportedLineCount: number;
  filledActualLineCount: number;
  unverifiedCount: number;
  attentionCount: number;
  abnormalCount: number;
  specialPendingNoteCount: number;
  pricedLineCount: number;
  pricedActualLineCount: number;
  unpricedLineCount: number;
  actualCoverageRate: number;
  pricingCoverageRate: number;
  theoreticalPricingCoverageRate: number;
  theoreticalAmount: number | null;
  actualAmount: number | null;
  mainAmount: number | null;
  amountDifference: number | null;
  amountDeviationRate: number | null;
  departmentCount: number;
  departments: string[];
}

export interface InventoryAdminDepartmentDailyRollup {
  /** Single-day compatibility field; empty for a multi-day range. */
  businessDate: string;
  periodStart: string;
  periodEnd: string;
  departmentCount: number;
  savedDepartmentCount: number;
  savedDraftCount?: number;
  missingDepartments: string[];
  departments: Array<{
    departmentKey: string;
    departmentName: string;
    status: "SUBMITTED" | "MISSING";
    submittedDayCount?: number;
    businessVolume: number | null;
    operator?: string;
    operatorUsername?: string;
    updatedAt?: string;
  }>;
  departmentDays: Array<{
    businessDate: string;
    departmentKey: string;
    departmentName: string;
    status: InventoryDepartmentDayStatus;
    businessVolume: number | null;
    riskCount: number;
    unverifiedCount: number;
    attentionCount: number;
    abnormalCount: number;
    specialPendingNoteCount: number;
    operator?: string;
    updatedAt?: string;
  }>;
  summary: InventoryAdminMaterialSummary[];
  details: Array<{
    businessDate: string;
    departmentKey: string;
    departmentName: string;
    lineKey: string;
    materialName: string;
    unit: string;
    serviceGroup?: string;
    careType?: string;
    measurementScope?: string;
    volume?: number;
    standardQuantity?: number | null;
    fixedAdjustment?: number;
    theoreticalQuantity: number;
    actualQuantity?: number | null;
    mainQuantity?: number;
    unitPrice?: number | null;
    theoreticalAmount?: number | null;
    actualAmount?: number | null;
    mainAmount?: number | null;
    actualStatus: "UNVERIFIED" | "REPORTED";
    difference?: number | null;
    deviationRate?: number | null;
    riskLevel: InventoryAdminRiskLevel;
    isSpecial?: boolean;
    specialAdminNote?: string;
    specialDailyNote?: string;
    quotaVersionCode?: string;
    frozenQuota?: boolean;
    reviewStatus?: "PENDING" | "EXPLAINED" | "REVIEWED" | "CLOSED";
    reviewNote?: string;
    reviewerUsername?: string;
    reviewerName?: string;
    reviewedAt?: string;
    isSupplemental?: boolean;
    operator?: string;
    operatorUsername?: string;
    updatedAt?: string;
  }>;
  dashboard: {
    periodStart: string;
    periodEnd: string;
    expectedDepartmentDays: number;
    submittedDepartmentDays: number;
    completionRate: number;
    missingDepartmentDays: number;
    zeroVolumeDepartmentDays: number;
    unverifiedCount: number;
    attentionCount: number;
    abnormalCount: number;
    specialPendingNoteCount: number;
    reportedLineCount: number;
    pricedActualLineCount: number;
    actualAmount: number | null;
    pricingCoverageRate: number;
    dailyTrend: Array<{
      businessDate: string;
      expectedDepartmentDays: number;
      submittedDepartmentDays: number;
      missingDepartmentDays: number;
      zeroVolumeDepartmentDays: number;
      completionRate: number;
      lineCount: number;
      reportedLineCount: number;
      pricedLineCount: number;
      pricedActualLineCount: number;
      unpricedLineCount: number;
      unverifiedCount: number;
      attentionCount: number;
      abnormalCount: number;
      specialPendingNoteCount: number;
      pricingCoverageRate: number;
      theoreticalAmount: number | null;
      actualAmount: number | null;
    }>;
    departmentRisk: Array<{
      departmentKey: string;
      departmentName: string;
      unverifiedCount: number;
      attentionCount: number;
      abnormalCount: number;
      specialPendingNoteCount: number;
      riskTotal: number;
    }>;
    materialAmountTop: InventoryAdminMaterialSummary[];
    materialDeviationTop: InventoryAdminMaterialSummary[];
  };
}

export interface InventoryDepartmentPeriodReport {
  departmentKey: string;
  departmentName: string;
  periodType: "week" | "month";
  periodStart: string;
  periodEnd: string;
  savedDraftCount: number;
  outpatientVolume: number;
  inpatientVolume: number;
  summary: Array<{ materialName: string; unit: string; quantity: number; activeDays: number }>;
  dailyAudit: Array<Record<string, string | number>>;
}

export interface InventoryDepartmentAllocationPlanLine {
  materialName: string;
  unit: string;
  allocatedQuantity: number;
  sourceType: "COUNT" | "MANUAL" | "PREVIOUS_MONTH";
  countReference?: string;
  manualAdjustment: number;
  warningThreshold?: number | null;
  suggestedWarningThreshold?: number;
  previousMonthSuggestedQuantity?: number;
  monthUsedQuantity?: number;
  monthRemainingQuantity?: number;
  status?: "PENDING" | "NORMAL" | "WARNING";
}

export interface InventoryDepartmentAllocationPlan {
  departmentKey: string;
  departmentName: string;
  month: string;
  throughDate: string;
  exists: boolean;
  revision: number;
  operator?: string;
  operatorUsername?: string;
  updatedAt?: string;
  lines: InventoryDepartmentAllocationPlanLine[];
  usage?: Array<{ materialName: string; unit: string; quantity: number; activeDays?: number }>;
  previousUsage?: Array<{ materialName: string; unit: string; quantity: number; activeDays?: number }>;
}

export interface InventoryPatientConsumptionDraftLine {
  id: string;
  serviceItemId: string;
  serviceItemName: string;
  materialName: string;
  unit: string;
  standardQuantity: number | null;
  actualQuantity: number;
  exceptionReason?: string;
}

export interface InventoryPatientConsumptionDraft {
  id?: string;
  exists?: boolean;
  departmentKey: string;
  departmentName: string;
  patientId: string;
  patientName?: string;
  encounterId: string;
  visitNo?: string;
  businessDate: string;
  serviceAt: string;
  serviceItems: { id: string; name: string }[];
  templateVersion: string;
  revision: number;
  operator?: string;
  operatorUsername?: string;
  updatedAt?: string;
  lines: InventoryPatientConsumptionDraftLine[];
}

export interface InventoryPatientConsumptionDraftList {
  list: InventoryPatientConsumptionDraft[];
}

export interface InventoryBatch {
  id: string;
  itemId: string;
  batchNo: string;
  expiryDate: string;
  quantity: number;
  location: string;
  source?: string;
  createdAt?: string;
}

export interface InventoryRequestLine {
  id: string;
  itemId: string;
  quantity: number;
  issuedQuantity?: number;
  status?: "pending" | "approved" | "partially_issued" | "issued" | "received" | "rejected" | "cancelled" | "void";
  batchAllocations?: {
    batchId: string;
    batchNo?: string;
    expiryDate?: string;
    quantity: number;
    issuedAt?: string;
    issuer?: string;
  }[];
}

export interface InventoryRequest {
  id: string;
  itemId: string;
  quantity: number;
  itemCount?: number;
  itemSummary?: string;
  lines?: InventoryRequestLine[];
  issuedQuantity?: number;
  batchId?: string;
  department: string;
  applicant: string;
  owner: string;
  issuer?: string;
  receiver?: string;
  reason: string;
  expectedUseWeek: string;
  status: "pending" | "approved" | "partially_issued" | "issued" | "received" | "rejected" | "cancelled" | "void";
  createdAt: string;
  approvedAt?: string;
  issuedAt?: string;
  receivedAt?: string;
  rejectReason?: string;
  cancelReason?: string;
  voidReason?: string;
}

export interface WeeklyConsumption {
  id: string;
  weekNo: string;
  department: string;
  itemId: string;
  consumedQuantity: number;
  actualConsumedQuantity?: number;
  remainingQuantity: number;
  nextWeekQuantity: number;
  suggestedQuantity?: number;
  adjustedQuantity?: number;
  owner: string;
  abnormalReason: string;
  confirmedAt: string;
}

export interface InventoryCount {
  id: string;
  itemId: string;
  batchId: string;
  bookQuantity: number;
  actualQuantity: number;
  differenceQuantity: number;
  operator: string;
  reason: string;
  countedAt: string;
}

export interface InventoryMovement {
  id: string;
  itemId: string;
  batchId: string;
  type: "inbound" | "issue" | "return" | "scrap" | "count";
  quantity: number;
  department: string;
  operator: string;
  reason: string;
  relatedId: string;
  createdAt: string;
}

export type InventoryCareType = "outpatient" | "inpatient";
export type InventoryTriggerStage = "INSPECTION" | "TCM" | "DOCTOR" | "SURGERY";
export type InventoryPackageStatus = "draft" | "enabled" | "disabled";
// One package line is consumed for each effective completion version of its configured stage.
export type InventoryConsumptionMode = "per_visit";

export interface InventoryPackageLine {
  id?: string;
  itemId: string;
  quantity: number;
  consumptionMode?: InventoryConsumptionMode;
}

export interface InventoryPackage {
  id: string;
  name: string;
  department: string;
  careType: InventoryCareType;
  triggerStage: InventoryTriggerStage;
  version?: number | string;
  status: InventoryPackageStatus;
  effectiveDate?: string;
  operator?: string;
  createdAt?: string;
  lines: InventoryPackageLine[];
}

export interface InventoryPackageCoverage {
  departmentId: string;
  department: string;
  careType: InventoryCareType;
  triggerStage: InventoryTriggerStage;
  packageId?: string;
  packageName?: string;
  packageVersion?: number;
  lineCount: number;
  covered: boolean;
}

export interface InventoryRoleDescriptor {
  code: string;
  name: string;
  responsibility: string;
  dataScope: string;
  permissions: string[];
  memberCount: number;
  systemAssigned: boolean;
}

export interface InventoryAccountAssignment {
  id: string;
  username: string;
  name: string;
  clinicalRole: string;
  department: string;
  status: string;
  inventoryRole: string;
  inventoryRoleLabel: string;
  systemAssigned: boolean;
}

export interface InventoryPortalAccount {
  id: string;
  username: string;
  name: string;
  departmentKey: string;
  department: string;
  portalRole: "admin" | "inventory_reporter";
  portalRoleLabel: string;
  status: "启用" | "停用";
  mustChangePassword: boolean;
  displayOrder: number;
}

export interface InventoryPortalAccountCatalog {
  accounts: InventoryPortalAccount[];
  departments: Array<{ key: string; name: string }>;
}

export type InventoryMessageBoardCategory = "NEW_ITEM" | "DATA_CORRECTION" | "SUGGESTION" | "OTHER";
export type InventoryMessageBoardStatus = "PENDING" | "FOLLOWING" | "COMPLETED" | "REJECTED";

export interface InventoryMessageBoardPost {
  id: string;
  title: string;
  content: string;
  category: InventoryMessageBoardCategory;
  status: InventoryMessageBoardStatus;
  handlingNote?: string;
  authorId: string;
  authorUsername: string;
  authorName: string;
  departmentKey: string;
  departmentName: string;
  pinned: boolean;
  hidden: boolean;
  withdrawn: boolean;
  replyCount: number;
  createdAt: string;
  updatedAt: string;
  lastActivityAt: string;
  mine: boolean;
  canEdit: boolean;
  canWithdraw: boolean;
}

export interface InventoryMessageBoardReply {
  id: string;
  postId: string;
  content: string;
  authorId: string;
  authorUsername: string;
  authorName: string;
  departmentKey: string;
  departmentName: string;
  hidden: boolean;
  withdrawn: boolean;
  createdAt: string;
  updatedAt: string;
  mine: boolean;
  canEdit: boolean;
  canWithdraw: boolean;
}

export interface InventoryMessageBoardPage {
  list: InventoryMessageBoardPost[];
  total: number;
  page: number;
  size: number;
}

export interface InventoryMessageBoardDetail {
  post: InventoryMessageBoardPost;
  replies: InventoryMessageBoardReply[];
}

export interface InventoryMessageBoardAuditLog {
  id: number;
  targetType: "POST" | "REPLY";
  targetId: string;
  action: string;
  operatorId: string;
  operatorUsername: string;
  operatorName: string;
  departmentKey: string;
  departmentName: string;
  detail: Record<string, unknown> | string;
  createdAt: string;
}

export interface InventoryMessageBoardAuditPage {
  list: InventoryMessageBoardAuditLog[];
  total: number;
  page: number;
  size: number;
}
export type InventoryMappingRuleType = "患者单次套餐" | "条件套餐" | "待核定（非固定）" | "固定运行消耗" | "按需申领";
export type InventoryMappingStatus = "pending" | "confirmed" | "held";
export type InventoryConsumptionScope = "PATIENT_RELATED" | "NON_PATIENT_RELATED";

export interface InventoryMappingCount {
  label: string;
  total: number;
}

export interface InventoryMappingSummary {
  total: number;
  patientOnce?: number;
  conditionalPackage?: number;
  fixedRunning?: number;
  onDemand?: number;
  pendingPatientBinding?: number;
  patientVariableConfirmed?: number;
  patientVariablePending?: number;
  nonPatient?: number;
  patientRelated?: number;
  nonPatientRelated?: number;
  reviewRequired?: number;
  canCreatePackageDraft?: number;
  needsSupplement?: number;
  batchId?: string;
  byRuleType?: InventoryMappingCount[];
  byStatus?: InventoryMappingCount[];
}

export interface InventoryMappingEntry {
  id: string;
  batchId?: string;
  sourceSheet?: string;
  sourceRow?: number;
  department: string;
  departmentId?: string;
  sourceScenario?: string;
  sourceItemName: string;
  sourceUsage?: string;
  sourceNote?: string;
  ruleType: InventoryMappingRuleType | string;
  consumptionScope?: InventoryConsumptionScope;
  sourceClassification?: InventoryMappingRuleType | string;
  reviewRequired?: boolean;
  reviewNote?: string;
  careType?: InventoryCareType | string;
  triggerStage?: InventoryTriggerStage | string;
  condition?: string;
  suggestedQuantity?: number;
  suggestedUnit?: string;
  matchedItemId?: string;
  matchedItemName?: string;
  matchedItemUnit?: string;
  matchedItemEnabled?: boolean;
  status: InventoryMappingStatus | string;
  importStatus?: string;
  suggestion?: string;
  cannotPublishReason?: string;
  canCreatePackageDraft?: boolean;
  draftPackageId?: string;
  maturity?: string;
  note?: string;
  operator?: string;
  confirmedAt?: string;
}

export interface InventoryMappingEntriesPage {
  total: number;
  page: number;
  size: number;
  list: InventoryMappingEntry[];
}

export interface InventoryMappingEntryQueryParams {
  ruleType?: string;
  status?: string;
  businessGroup?: "automatic" | "pending" | "nonpatient" | "patient-related" | "nonpatient-related" | "review-required" | "";
  department?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface ConfirmInventoryMappingEntriesParams {
  id?: string;
  ids?: string[];
  itemId?: string;
  matchedItemId?: string;
  department?: string;
  departmentId?: string;
  careType?: InventoryCareType | string;
  triggerStage?: InventoryTriggerStage | string;
  suggestedQuantity?: number;
  suggestedUnit?: string;
  note?: string;
}

export interface InventoryMappingActionParams {
  id?: string;
  ids?: string[];
  reason?: string;
  name?: string;
}

export interface InventoryItemAlias {
  id?: string;
  itemId?: string;
  itemName?: string;
  aliasName: string;
  normalizedAlias?: string;
  sourceName?: string;
  status?: "pending" | "active" | "held" | string;
  createdAt?: string;
}

export interface InventoryUnitConversion {
  id?: string;
  itemId?: string;
  itemName?: string;
  sourceUnit: string;
  targetUnit: string;
  factor: number;
  status?: "active" | "inactive" | string;
  createdAt?: string;
}

export interface InventoryConsumptionDetail {
  id?: string;
  itemId?: string;
  quantity?: number;
  batchId?: string;
  errorMessage?: string;
}

export interface InventoryConsumptionEvent {
  id: string;
  commandId?: string;
  encounterId: string;
  careEncounterId?: string;
  caseToken?: string;
  route: string;
  careType?: InventoryCareType;
  department: string;
  visitDate: string;
  packageId?: string;
  packageName?: string;
  packageVersion?: number;
  triggerStage?: string;
  completionVersion?: number;
  eventKind?: string;
  reversalOfEventId?: string;
  status: "pending" | "success" | "succeeded" | "failed" | "reversed";
  errorMessage?: string;
  operator?: string;
  createdAt?: string;
  details?: InventoryConsumptionDetail[];
}

export interface InventoryLedgerMovement {
  id: string;
  itemId: string;
  itemName: string;
  unit?: string;
  batchId?: string;
  batchNo?: string;
  expiryDate?: string;
  fromLocationId?: string;
  fromLocationName?: string;
  toLocationId?: string;
  toLocationName?: string;
  movementType: string;
  quantity: number;
  departmentId?: string;
  department?: string;
  operator?: string;
  reason?: string;
  occurredAt: string;
}

export interface InventoryAuditLog {
  id: string;
  operator: string;
  action: string;
  targetType: string;
  targetLabel: string;
  detail: string;
  createdAt: string;
}

export interface InventorySummary {
  itemCount: number;
  batchCount: number;
  pendingRequestCount: number;
  approvedRequestCount: number;
  lowStockCount: number;
  expirySoonCount: number;
  movementCount: number;
}

export interface InventoryDb {
  items: InventoryItem[];
  batches: InventoryBatch[];
  requests: InventoryRequest[];
  weeklyConsumptions: WeeklyConsumption[];
  counts: InventoryCount[];
  movements: InventoryMovement[];
  packages: InventoryPackage[];
  packageCoverage: InventoryPackageCoverage[];
  consumptionEvents: InventoryConsumptionEvent[];
  auditLogs: InventoryAuditLog[];
  summary: InventorySummary;
}

export type InventoryExceptionSeverity = "info" | "warning" | "critical";
export type InventoryExceptionStatus = "open" | "processing" | "resolved" | "ignored";

export interface InventoryWorkflowSnapshot {
  pendingInbound?: number;
  pendingApproval?: number;
  pendingIssue?: number;
  inTransit?: number;
  pendingReceipt?: number;
}

export interface InventoryAutomationSnapshot {
  pending?: number;
  succeededToday?: number;
  failed?: number;
  reversalPending?: number;
}

export interface InventoryWeeklySuggestion {
  id: string;
  departmentId?: string;
  departmentName: string;
  itemId: string;
  itemName: string;
  unit: string;
  actualConsumption: number;
  availableQuantity: number;
  safetyQuantity: number;
  suggestedQuantity: number;
  reason?: string;
}

export interface InventoryWorkbench {
  generatedAt?: string;
  activeDepartmentId?: string;
  activeDepartmentName?: string;
  workflow: InventoryWorkflowSnapshot;
  automation: InventoryAutomationSnapshot;
  centralAvailable?: number;
  departmentAvailable?: number;
  lowStockCount?: number;
  expirySoonCount?: number;
  weeklySuggestions?: InventoryWeeklySuggestion[];
}

export interface InventoryLocationBalance {
  id: string;
  locationId: string;
  locationName: string;
  locationType: "central" | "department" | "transit";
  departmentId?: string;
  departmentName?: string;
  itemId: string;
  itemName: string;
  category?: string;
  spec?: string;
  unit: string;
  batchId?: string;
  batchNo?: string;
  expiryDate?: string;
  availableQuantity: number;
  reservedQuantity: number;
  inTransitQuantity: number;
  lowStockThreshold?: number;
  openingConfirmed?: boolean;
}

export interface InventoryException {
  id: string;
  type: string;
  severity: InventoryExceptionSeverity;
  status: InventoryExceptionStatus;
  departmentId?: string;
  departmentName?: string;
  itemId?: string;
  itemName?: string;
  encounterId?: string;
  stage?: string;
  message: string;
  retryable?: boolean;
  occurredAt?: string;
  resolvedAt?: string;
}

export interface InventoryConsumptionRecord {
  id: string;
  commandId?: string;
  encounterId?: string;
  careType?: InventoryCareType | string;
  encounterNo?: string;
  patientDisplayName?: string;
  departmentId?: string;
  departmentName?: string;
  stage?: string;
  itemId: string;
  itemName: string;
  unit: string;
  batchId?: string;
  batchNo?: string;
  packageName?: string;
  packageVersion?: string | number;
  quantity: number;
  reversedQuantity?: number;
  status: "pending" | "succeeded" | "failed" | "reversed" | "partially_reversed";
  source?: "package" | "adjustment" | "reversal";
  consumedAt?: string;
  errorMessage?: string;
}

export type InventoryWeeklyStandardStatus = "DRAFT" | "PUBLISHED" | "RETIRED";
export type InventoryWeeklySnapshotStatus = "DRAFT" | "CONFIRMED" | "REVISED";
export type InventoryWeeklyExportFormat = "xlsx" | "pdf" | "docx";

export interface InventoryWeeklyLinePolicy {
  careType?: InventoryCareType;
  plannedPatientVolume?: number;
  actualPatientVolume?: number;
  patientVolumeSource?: string;
  consumptionEventVolume?: number;
  perPatientStandardQuantity?: number;
  varianceFlag?: string;
  businessVolume?: number;
  standardQuantity?: number;
  standardUnit?: string;
  conversionFactor?: number;
  baseUnit?: string;
  expectedOverrideQuantity?: number;
  safetyStockQuantity?: number;
  strictValidation?: boolean;
}

export interface InventoryWeeklyStandardLine {
  id?: string;
  standardId?: string;
  departmentId: string;
  departmentName?: string;
  itemId: string;
  itemName?: string;
  itemUnit?: string;
  expectedQuantity?: number;
  safetyStockQuantity?: number;
  calculationPolicy?: string;
  linePolicy?: InventoryWeeklyLinePolicy;
  status?: string;
  createdAt?: string;
  careType?: InventoryCareType;
  plannedPatientVolume?: number;
  businessVolume?: number;
  standardQuantity?: number;
  standardUnit?: string;
  conversionFactor?: number;
  baseUnit?: string;
}

export interface InventoryWeeklyStandard {
  id: string;
  standardCode: string;
  version: number;
  name: string;
  status: InventoryWeeklyStandardStatus;
  effectiveWeek: string;
  expiresWeek?: string;
  hospitalTimezone?: string;
  policy?: Record<string, unknown>;
  lineCount?: number;
  publishedBy?: string;
  publishedAt?: string;
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
  lines?: InventoryWeeklyStandardLine[];
}

export interface InventoryWeeklySnapshotLine {
  id: string;
  snapshotId?: string;
  standardLineId?: string;
  careType?: InventoryCareType;
  itemId: string;
  itemName: string;
  itemUnit?: string;
  openingQuantity?: number;
  inboundQuantity?: number;
  transferInQuantity?: number;
  transferOutQuantity?: number;
  consumedQuantity?: number;
  reversalQuantity?: number;
  returnedQuantity?: number;
  scrappedQuantity?: number;
  countAdjustmentQuantity?: number;
  closingQuantity?: number;
  reservedQuantity?: number;
  availableQuantity?: number;
  expectedQuantity?: number;
  expectedActualVariance?: number;
  safetyStockQuantity?: number;
  suggestedQuantity?: number;
  adjustedQuantity?: number;
  adjustmentVariance?: number;
  adjustmentReason?: string;
  sourceSummary?: Record<string, unknown> &
    InventoryWeeklyLinePolicy & {
      actualBusinessVolume?: number;
      movementCount?: number;
    };
}

export interface InventoryWeeklyAuditEvent {
  id: string;
  actionCode: string;
  actorName?: string;
  actorRole?: string;
  departmentId?: string;
  detail?: Record<string, unknown>;
  occurredAt?: string;
}

export interface InventoryWeeklySnapshot {
  id: string;
  weekNo: string;
  departmentId: string;
  departmentName?: string;
  standardId?: string;
  standardVersion?: number;
  revision: number;
  previousSnapshotId?: string;
  rootSnapshotId?: string;
  status: InventoryWeeklySnapshotStatus;
  validityStatus?: "CURRENT" | "INVALIDATED";
  invalidatedAt?: string;
  invalidatedReason?: string;
  sourceCutoffAt?: string;
  hospitalTimezone?: string;
  calculationVersion?: string;
  sourceDigest?: string;
  lineCount?: number;
  totalExpectedQuantity?: number;
  totalActualConsumedQuantity?: number;
  totalAdjustedQuantity?: number;
  revisionReason?: string;
  confirmationNote?: string;
  confirmedBy?: string;
  confirmedAt?: string;
  createdBy?: string;
  createdAt?: string;
  commandId?: string;
  lines?: InventoryWeeklySnapshotLine[];
  auditEvents?: InventoryWeeklyAuditEvent[];
}

type InventoryApiList<T> = { list?: T[] };

type InventoryWorkbenchApi = {
  departmentId?: string;
  department?: string;
  balances?: InventoryLocationBalanceApi[];
  exceptions?: InventoryExceptionApi[];
  opening?: { confirmed?: boolean };
  flow?: { status?: string; count?: number }[];
  weeklySuggestions?: InventoryWeeklySuggestion[];
};

type InventoryLocationBalanceApi = Partial<InventoryLocationBalance> & {
  quantity?: number;
  department?: string;
  locationType?: string;
};

type InventoryExceptionApi = Partial<Omit<InventoryException, "severity" | "status">> & {
  commandId?: string;
  exceptionType?: string;
  triggerStage?: string;
  department?: string;
  createdAt?: string;
  retryCount?: number;
  severity?: string;
  status?: string;
};

type InventoryConsumptionApi = Partial<Omit<InventoryConsumptionRecord, "status">> & {
  triggerStage?: string;
  route?: string;
  department?: string;
  createdAt?: string;
  eventKind?: string;
  status?: string;
};

type InventoryConsumptionPageApi = InventoryApiList<InventoryConsumptionApi> & {
  page?: number;
  size?: number;
  total?: number;
};

export interface InventoryQueryParams {
  departmentId?: string;
  itemId?: string;
  category?: string;
  stage?: string;
  status?: string;
  from?: string;
  to?: string;
  patientOnly?: boolean;
}

export interface InventoryLedgerMovementQuery {
  departmentId?: string;
  itemId?: string;
  movementType?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

type InventoryLedgerMovementPageApi = InventoryApiList<InventoryLedgerMovement> & {
  page?: number;
  size?: number;
  total?: number;
};

export interface DepartmentUsageReportParams extends InventoryQueryParams {
  departmentIds?: string[];
  format: "pdf" | "xlsx";
}

export interface InventoryDepartmentUsageReport {
  from: string;
  to: string;
  triggerStage?: string;
  patientOnly?: boolean;
  summary: Array<{
    departmentId: string;
    department: string;
    itemId: string;
    itemName: string;
    unit: string;
    openingQuantity: number;
    consumedQuantity: number;
    reversalQuantity: number;
    closingQuantity: number;
  }>;
  details: Array<{
    departmentId: string;
    department: string;
    visitDate: string;
    encounterId: string;
    triggerStage: string;
    itemId: string;
    itemName: string;
    unit: string;
    quantity: number;
  }>;
}

export interface InventoryReportDownload {
  blob: Blob;
  filename: string;
}

export type SaveInventoryItemParams = Partial<InventoryItem> & { operator?: string };

export interface InventoryInboundParams {
  itemId: string;
  quantity: number;
  batchNo?: string;
  expiryDate?: string;
  location?: string;
  source?: string;
  operator?: string;
}

export interface InventoryRequestParams {
  itemId?: string;
  quantity?: number;
  lines?: { itemId: string; quantity: number }[];
  department: string;
  applicant: string;
  owner: string;
  reason: string;
  expectedUseWeek: string;
}

export interface InventoryActionParams {
  id: string;
  operator?: string;
  owner?: string;
  receiver?: string;
  issuedQuantity?: number;
  batchId?: string;
  lineId?: string;
  itemId?: string;
  lines?: { id?: string; itemId?: string; issuedQuantity: number }[];
  reason?: string;
}

export interface WeeklyConsumptionParams {
  weekNo: string;
  department: string;
  itemId: string;
  consumedQuantity?: number;
  remainingQuantity?: number;
  nextWeekQuantity?: number;
  adjustedQuantity?: number;
  owner: string;
  abnormalReason?: string;
  operator?: string;
}

export interface ReturnOrScrapParams {
  type: "return" | "scrap";
  itemId: string;
  batchId?: string;
  quantity: number;
  department?: string;
  operator?: string;
  reason: string;
}

export interface InventoryCountParams {
  itemId: string;
  batchId?: string;
  actualQuantity: number;
  operator?: string;
  reason: string;
}

export interface SaveInventoryPackageParams {
  id?: string;
  name: string;
  department: string;
  careType: InventoryCareType;
  triggerStage: InventoryTriggerStage;
  effectiveDate?: string;
  lines: InventoryPackageLine[];
  operator?: string;
}

export interface InventoryPackageActionParams {
  id: string;
  operator?: string;
}

export interface SaveInventoryWeeklyStandardParams {
  id?: string;
  standardCode?: string;
  name: string;
  effectiveWeek: string;
  expiresWeek?: string;
  hospitalTimezone?: string;
  calculationPolicy?: string;
  lines: InventoryWeeklyStandardLine[];
}

export interface InventoryWeeklySnapshotQueryParams {
  weekNo?: string;
  departmentId?: string;
}

export interface GenerateInventoryWeeklySnapshotParams {
  weekNo: string;
  departmentId?: string;
  adjustmentReason?: string;
  idempotencyKey?: string;
}

export interface ConfirmInventoryWeeklySnapshotParams {
  id: string;
  expectedRevision?: number;
  confirmationNote?: string;
  idempotencyKey?: string;
}

export interface ReviseInventoryWeeklySnapshotParams {
  id: string;
  expectedRevision?: number;
  revisionReason: string;
  idempotencyKey?: string;
  lines: { itemId: string; careType?: InventoryCareType; adjustedQuantity: number; adjustmentReason?: string }[];
}

const parseInventoryJson = async (result: Response) => {
  if (result.status === 401) {
    handleUnauthorizedResponse();
  }
  const text = await result.text();
  if (text.trim().startsWith("<")) {
    throw new Error("进销存接口未连通，请确认后端已启动，并检查 /inventory-api 代理或部署转发配置");
  }
  try {
    return JSON.parse(text) as ResultData<InventoryDb>;
  } catch {
    throw new Error("进销存接口返回格式异常，请检查后端服务状态");
  }
};

const parseInventoryResponse = async (result: Response): Promise<InventoryDb> => {
  const payload = await parseInventoryJson(result);
  if (!result.ok || String(payload.code) !== "200") {
    throw new Error(payload.msg || `inventory api failed: ${result.status}`);
  }
  return normalizeDb(payload.data);
};

const parseInventoryDataResponse = async <T>(result: Response): Promise<T> => {
  if (result.status === 401) handleUnauthorizedResponse();
  const text = await result.text();
  if (text.trim().startsWith("<")) {
    throw new Error("进销存接口未连通，请检查 /inventory-api 代理或部署转发配置");
  }
  let payload: ResultData<T>;
  try {
    payload = JSON.parse(text) as ResultData<T>;
  } catch {
    throw new Error("进销存接口返回格式异常，请检查后端服务状态");
  }
  if (!result.ok || String(payload.code) !== "200") {
    throw new Error(payload.msg || `inventory api failed: ${result.status}`);
  }
  return payload.data;
};

const buildInventoryQuery = (params: Record<string, unknown> = {}) => {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === "") return;
    if (Array.isArray(value)) {
      value.filter(Boolean).forEach(item => search.append(key, String(item)));
      return;
    }
    search.set(key, String(value));
  });
  const query = search.toString();
  return query ? `?${query}` : "";
};

const getInventoryData = async <T>(path: string, params?: Record<string, unknown>) => {
  const result = await fetch(`${INVENTORY_API_BASE_URL}${path}${buildInventoryQuery(params)}`, {
    headers: authHeaders()
  });
  return response(await parseInventoryDataResponse<T>(result));
};

const readDownloadFilename = (result: Response, fallback: string) => {
  const disposition = result.headers.get("content-disposition") || "";
  const utf8Match = disposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1]);
    } catch {
      return utf8Match[1];
    }
  }
  return disposition.match(/filename="?([^";]+)"?/i)?.[1] || fallback;
};

const normalizeNumber = (value: unknown) => {
  const numberValue = Number(value ?? 0);
  return Number.isFinite(numberValue) ? numberValue : 0;
};

const normalizeDb = (db: InventoryDb): InventoryDb => ({
  items: (db.items ?? []).map(item => ({
    ...item,
    baseUnit: item.baseUnit || item.unit,
    issueUnit: item.issueUnit || item.unit,
    quantityPrecision: normalizeNumber(item.quantityPrecision ?? 2),
    normalizationStatus: item.normalizationStatus || "standard",
    effectiveLifeManaged: Boolean(item.effectiveLifeManaged),
    lowStockThreshold: normalizeNumber(item.lowStockThreshold),
    sensitive: Boolean(item.sensitive),
    batchRequired: Boolean(item.batchRequired),
    expiryRequired: Boolean(item.expiryRequired),
    enabled: item.enabled !== false
  })),
  batches: (db.batches ?? []).map(batch => ({ ...batch, quantity: normalizeNumber(batch.quantity) })),
  requests: (db.requests ?? []).map(request => ({
    ...request,
    quantity: normalizeNumber(request.quantity),
    issuedQuantity: normalizeNumber(request.issuedQuantity),
    lines: (request.lines ?? []).map(line => ({
      ...line,
      quantity: normalizeNumber(line.quantity),
      issuedQuantity: normalizeNumber(line.issuedQuantity),
      batchAllocations: (line.batchAllocations ?? []).map(allocation => ({
        ...allocation,
        quantity: normalizeNumber(allocation.quantity)
      }))
    }))
  })),
  weeklyConsumptions: (db.weeklyConsumptions ?? []).map(row => ({
    ...row,
    consumedQuantity: normalizeNumber(row.consumedQuantity),
    remainingQuantity: normalizeNumber(row.remainingQuantity),
    nextWeekQuantity: normalizeNumber(row.nextWeekQuantity)
  })),
  counts: (db.counts ?? []).map(row => ({
    ...row,
    bookQuantity: normalizeNumber(row.bookQuantity),
    actualQuantity: normalizeNumber(row.actualQuantity),
    differenceQuantity: normalizeNumber(row.differenceQuantity)
  })),
  movements: (db.movements ?? []).map(row => ({ ...row, quantity: normalizeNumber(row.quantity) })),
  packages: (db.packages ?? []).map(row => ({
    ...row,
    lines: (row.lines ?? []).map(line => ({ ...line, quantity: normalizeNumber(line.quantity) }))
  })),
  packageCoverage: (db.packageCoverage ?? []).map(row => ({
    ...row,
    packageVersion: row.packageVersion == null ? undefined : normalizeNumber(row.packageVersion),
    lineCount: normalizeNumber(row.lineCount),
    covered: Boolean(row.covered)
  })),
  consumptionEvents: (db.consumptionEvents ?? []).map(row => ({
    ...row,
    details: (row.details ?? []).map(detail => ({ ...detail, quantity: normalizeNumber(detail.quantity) }))
  })),
  auditLogs: db.auditLogs ?? [],
  summary: {
    itemCount: normalizeNumber(db.summary?.itemCount),
    batchCount: normalizeNumber(db.summary?.batchCount),
    pendingRequestCount: normalizeNumber(db.summary?.pendingRequestCount),
    approvedRequestCount: normalizeNumber(db.summary?.approvedRequestCount),
    lowStockCount: normalizeNumber(db.summary?.lowStockCount),
    expirySoonCount: normalizeNumber(db.summary?.expirySoonCount),
    movementCount: normalizeNumber(db.summary?.movementCount)
  }
});

const postInventory = async <T extends object>(path: string, payload: T) => {
  const result = await fetch(`${INVENTORY_API_BASE_URL}${path}`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  return parseInventoryResponse(result);
};

const postInventoryData = async <T, P extends object>(path: string, payload: P) => {
  const result = await fetch(`${INVENTORY_API_BASE_URL}${path}`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  return response(await parseInventoryDataResponse<T>(result));
};

const putInventoryData = async <T, P extends object>(path: string, payload: P) => {
  const result = await fetch(`${INVENTORY_API_BASE_URL}${path}`, {
    method: "PUT",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  return response(await parseInventoryDataResponse<T>(result));
};

const response = <T>(data: T, msg = "成功") =>
  Promise.resolve({
    code: 200 as unknown as string,
    msg,
    data
  } as ResultData<T>);

export const getInventoryDbApi = async () => {
  const result = await fetch(`${INVENTORY_API_BASE_URL}/db`, { headers: authHeaders() });
  return response(await parseInventoryResponse(result));
};

export const getInventoryDepartmentDailyDraftApi = async (params: { departmentKey: string; date: string }) =>
  getInventoryData<InventoryDepartmentDailyDraft>("/department-daily-drafts", params);

export const getInventoryDepartmentDailyDraftSummaryApi = async (date: string) =>
  getInventoryData<InventoryDepartmentDailyDraftSummary>("/department-daily-drafts/summary", { date });

export const getInventoryDepartmentDailyRollupApi = async (query: InventoryDailyRollupQuery) =>
  getInventoryData<InventoryAdminDepartmentDailyRollup>("/department-daily-drafts/admin-rollup", query);

export const getInventoryQuotaGovernanceApi = async (date?: string) =>
  getInventoryData<InventoryQuotaGovernance>("/quota-governance", date ? { date } : undefined);

export const createInventoryQuotaVersionApi = async (payload: {
  versionCode: string;
  effectiveDate: string;
  baseVersionId?: string;
}) => postInventoryData<InventoryQuotaGovernance, typeof payload>("/quota-governance/versions", payload);

export const updateInventoryQuotaRuleApi = async (
  ruleId: string,
  payload: Pick<InventoryQuotaRule, "standardQuantity" | "fixedAdjustment" | "measurementScope" | "enabled">
) => putInventoryData<InventoryQuotaGovernance, typeof payload>(`/quota-governance/rules/${encodeURIComponent(ruleId)}`, payload);

export const saveInventoryQuotaReviewApi = async (payload: {
  businessDate: string;
  departmentKey: string;
  lineKey: string;
  materialName: string;
  unit: string;
  reviewStatus: "PENDING" | "EXPLAINED" | "REVIEWED" | "CLOSED";
  reviewNote?: string;
}) => putInventoryData<Record<string, string>, typeof payload>("/quota-governance/reviews", payload);

export const saveInventoryDepartmentDailyDraftApi = async (payload: InventoryDepartmentDailyDraft) =>
  putInventoryData<InventoryDepartmentDailyDraft, InventoryDepartmentDailyDraft>("/department-daily-drafts", payload);

export const getInventoryDepartmentPeriodReportApi = async (params: {
  departmentKey: string;
  periodType: "week" | "month";
  anchorDate: string;
}) => getInventoryData<InventoryDepartmentPeriodReport>("/department-period-reports", params);

export const getInventoryDepartmentAllocationPlanApi = async (params: {
  departmentKey: string;
  month: string;
  throughDate?: string;
}) => getInventoryData<InventoryDepartmentAllocationPlan>("/department-allocation-plans", params);

export const saveInventoryDepartmentAllocationPlanApi = async (payload: InventoryDepartmentAllocationPlan) =>
  putInventoryData<InventoryDepartmentAllocationPlan, InventoryDepartmentAllocationPlan>("/department-allocation-plans", payload);

export const getInventoryPatientConsumptionDraftApi = async (id: string) =>
  getInventoryData<InventoryPatientConsumptionDraft>("/patient-consumption-drafts/detail", { id });

export const listInventoryPatientConsumptionDraftsApi = async (params: {
  departmentKey: string;
  date?: string;
  patientId?: string;
}) => getInventoryData<InventoryPatientConsumptionDraftList>("/patient-consumption-drafts", params);

export const saveInventoryPatientConsumptionDraftApi = async (payload: InventoryPatientConsumptionDraft) =>
  putInventoryData<InventoryPatientConsumptionDraft, InventoryPatientConsumptionDraft>("/patient-consumption-drafts", payload);

const normalizeLocationType = (value?: string): InventoryLocationBalance["locationType"] => {
  const normalized = String(value || "").toUpperCase();
  if (normalized === "CENTRAL") return "central";
  if (normalized === "IN_TRANSIT" || normalized === "TRANSIT") return "transit";
  return "department";
};

const normalizeLocationBalance = (row: InventoryLocationBalanceApi): InventoryLocationBalance => {
  const locationType = normalizeLocationType(row.locationType);
  const departmentName = row.departmentName || row.department;
  return {
    id: row.id || `${row.locationId || "location"}:${row.itemId || "item"}:${row.batchId || "batch"}`,
    locationId: row.locationId || "",
    locationName:
      row.locationName ||
      (locationType === "central" ? "中央仓库" : locationType === "transit" ? "配送在途" : `${departmentName || "科室"}库`),
    locationType,
    departmentId: row.departmentId,
    departmentName,
    itemId: row.itemId || "",
    itemName: row.itemName || row.itemId || "未命名物资",
    category: row.category,
    spec: row.spec,
    unit: row.unit || "",
    batchId: row.batchId,
    batchNo: row.batchNo,
    expiryDate: row.expiryDate,
    availableQuantity: normalizeNumber(row.availableQuantity ?? row.quantity),
    reservedQuantity: normalizeNumber(row.reservedQuantity),
    inTransitQuantity: locationType === "transit" ? normalizeNumber(row.availableQuantity ?? row.quantity) : 0,
    lowStockThreshold: row.lowStockThreshold === undefined ? undefined : normalizeNumber(row.lowStockThreshold),
    openingConfirmed: row.openingConfirmed
  };
};

const normalizeExceptionSeverity = (value?: string): InventoryExceptionSeverity => {
  const normalized = String(value || "").toUpperCase();
  if (normalized === "HIGH" || normalized === "CRITICAL") return "critical";
  if (normalized === "MEDIUM" || normalized === "WARNING") return "warning";
  return "info";
};

const normalizeExceptionStatus = (value?: string): InventoryExceptionStatus => {
  const normalized = String(value || "").toUpperCase();
  if (normalized === "RESOLVED") return "resolved";
  if (normalized === "RETRYING" || normalized === "PROCESSING") return "processing";
  if (normalized === "IGNORED") return "ignored";
  return "open";
};

const normalizeInventoryException = (row: InventoryExceptionApi): InventoryException => {
  const status = normalizeExceptionStatus(row.status);
  return {
    id: row.id || row.commandId || "",
    type: row.type || row.exceptionType || "INVENTORY_EXCEPTION",
    severity: normalizeExceptionSeverity(row.severity),
    status,
    departmentId: row.departmentId,
    departmentName: row.departmentName || row.department,
    itemId: row.itemId,
    itemName: row.itemName,
    encounterId: row.encounterId,
    stage: row.stage || row.triggerStage,
    message: row.message || "库存任务执行失败",
    retryable: status === "open" || status === "processing",
    occurredAt: row.occurredAt || row.createdAt,
    resolvedAt: row.resolvedAt
  };
};

const normalizeConsumptionStatus = (value?: string): InventoryConsumptionRecord["status"] => {
  const normalized = String(value || "").toUpperCase();
  if (normalized === "FAILED") return "failed";
  if (normalized === "REVERSED") return "reversed";
  if (normalized === "PARTIALLY_REVERSED") return "partially_reversed";
  if (normalized === "PENDING" || normalized === "RETRY") return "pending";
  return "succeeded";
};

const normalizeConsumption = (row: InventoryConsumptionApi): InventoryConsumptionRecord => ({
  id: row.id || row.commandId || "",
  commandId: row.commandId,
  encounterId: row.encounterId,
  careType: row.careType || row.route,
  encounterNo: row.encounterNo,
  patientDisplayName: row.patientDisplayName,
  departmentId: row.departmentId,
  departmentName: row.departmentName || row.department,
  stage: row.stage || row.triggerStage,
  itemId: row.itemId || "",
  itemName: row.itemName || row.itemId || "未命名物资",
  unit: row.unit || "",
  batchId: row.batchId,
  batchNo: row.batchNo,
  packageName: row.packageName,
  packageVersion: row.packageVersion,
  quantity: normalizeNumber(row.quantity),
  reversedQuantity: normalizeNumber(row.reversedQuantity),
  status: normalizeConsumptionStatus(row.status),
  source:
    row.source ||
    (String(row.eventKind || "")
      .toUpperCase()
      .includes("REVERS")
      ? "reversal"
      : "package"),
  consumedAt: row.consumedAt || row.createdAt,
  errorMessage: row.errorMessage
});

const flowCount = (rows: InventoryWorkbenchApi["flow"], statuses: string[]) =>
  (rows || [])
    .filter(row => statuses.includes(String(row.status || "").toUpperCase()))
    .reduce((sum, row) => sum + normalizeNumber(row.count), 0);

export const getInventoryWorkbenchApi = async (params: InventoryQueryParams = {}) => {
  const result = await getInventoryData<InventoryWorkbenchApi>("/workbench", params as unknown as Record<string, unknown>);
  const raw = result.data;
  const balances = (raw.balances || []).map(normalizeLocationBalance);
  const exceptions = (raw.exceptions || []).map(normalizeInventoryException);
  return response<InventoryWorkbench>({
    activeDepartmentId: raw.departmentId,
    activeDepartmentName: raw.department,
    workflow: {
      pendingIssue: flowCount(raw.flow, ["RESERVED", "PARTIALLY_IN_TRANSIT"]),
      inTransit: flowCount(raw.flow, ["IN_TRANSIT", "PARTIALLY_IN_TRANSIT"]),
      pendingReceipt: flowCount(raw.flow, ["IN_TRANSIT", "PARTIALLY_IN_TRANSIT"])
    },
    automation: {
      failed: exceptions.filter(row => row.status !== "resolved").length
    },
    centralAvailable: balances.some(row => row.locationType === "central")
      ? balances.filter(row => row.locationType === "central").reduce((sum, row) => sum + row.availableQuantity, 0)
      : undefined,
    departmentAvailable: balances.some(row => row.locationType === "department")
      ? balances.filter(row => row.locationType === "department").reduce((sum, row) => sum + row.availableQuantity, 0)
      : undefined,
    weeklySuggestions: raw.weeklySuggestions || []
  });
};

export const getInventoryLocationBalancesApi = async (params: InventoryQueryParams = {}) => {
  const result = await getInventoryData<InventoryApiList<InventoryLocationBalanceApi>>(
    "/department-balances",
    params as unknown as Record<string, unknown>
  );
  return response((result.data.list || []).map(normalizeLocationBalance));
};

export const getInventoryExceptionsApi = async (params: InventoryQueryParams = {}) => {
  const query = { ...params, status: params.status ? String(params.status).toUpperCase() : "OPEN" };
  const result = await getInventoryData<InventoryApiList<InventoryExceptionApi>>(
    "/exception-tasks",
    query as unknown as Record<string, unknown>
  );
  return response((result.data.list || []).map(normalizeInventoryException));
};

export const getInventoryConsumptionsApi = async (params: InventoryQueryParams = {}) => {
  const result = await getInventoryData<InventoryConsumptionPageApi>(
    "/consumption-events",
    params as unknown as Record<string, unknown>
  );
  return response((result.data.list || []).map(normalizeConsumption));
};

export const getInventoryLedgerMovementsApi = async (params: InventoryLedgerMovementQuery = {}) => {
  const result = await getInventoryData<InventoryLedgerMovementPageApi>(
    "/ledger-movements",
    params as unknown as Record<string, unknown>
  );
  return response({
    page: result.data.page || 1,
    size: result.data.size || params.size || 50,
    total: result.data.total || 0,
    list: result.data.list || []
  });
};

const downloadInventoryFile = async (path: string, fallback: string): Promise<InventoryReportDownload> => {
  const result = await fetch(`${INVENTORY_API_BASE_URL}${path}`, { headers: authHeaders() });
  if (result.status === 401) handleUnauthorizedResponse();
  if (!result.ok) {
    const text = await result.text();
    let message = text;
    try {
      const payload = JSON.parse(text) as { msg?: string; message?: string };
      message = payload.msg || payload.message || text;
    } catch {
      // Keep the server text when the response is not JSON.
    }
    throw new Error(message || `文件导出失败: ${result.status}`);
  }
  return {
    blob: await result.blob(),
    filename: readDownloadFilename(result, fallback)
  };
};

const downloadInventoryPostFile = async <T>(path: string, payload: T, fallback: string): Promise<InventoryReportDownload> => {
  const result = await fetch(`${INVENTORY_API_BASE_URL}${path}`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(payload)
  });
  if (result.status === 401) handleUnauthorizedResponse();
  if (!result.ok) {
    const text = await result.text();
    let message = text;
    try {
      const error = JSON.parse(text) as { msg?: string; message?: string };
      message = error.msg || error.message || text;
    } catch {
      // Keep the server text when the response is not JSON.
    }
    throw new Error(message || `文件导出失败: ${result.status}`);
  }
  return { blob: await result.blob(), filename: readDownloadFilename(result, fallback) };
};

export const downloadInventoryPatientConsumptionDraftsApi = async (
  kind: "details" | "summary",
  params: { departmentKey: string; date: string }
) =>
  downloadInventoryFile(
    `/patient-consumption-drafts/export/${kind}${buildInventoryQuery(params)}`,
    `patient-consumption-${kind}-${params.date}.csv`
  );

export const downloadInventoryDepartmentDailyDraftApi = async (
  kind: "details" | "summary",
  payload: InventoryDepartmentDailyDraftExportPayload
) =>
  downloadInventoryPostFile(
    `/department-daily-drafts/export/${kind}`,
    payload,
    `department-daily-${kind}-${payload.businessDate}.csv`
  );

const inventoryDailyRollupFilename = (query: InventoryDailyRollupQuery) => query.date || `${query.from || ""}至${query.to || ""}`;

export const downloadInventoryDepartmentDailyRollupApi = async (query: InventoryDailyRollupQuery) =>
  downloadInventoryFile(
    `/department-daily-drafts/admin-rollup/export${buildInventoryQuery(query)}`,
    `管理员12科室耗材日报-${inventoryDailyRollupFilename(query)}.csv`
  );

export const downloadInventoryDepartmentDailyRollupXlsxApi = async (query: InventoryDailyRollupQuery) =>
  downloadInventoryFile(
    `/department-daily-drafts/admin-rollup/export.xlsx${buildInventoryQuery(query)}`,
    `管理员12科室耗材日报-${inventoryDailyRollupFilename(query)}.xlsx`
  );

export const downloadInventoryDepartmentPeriodReportApi = async (params: {
  departmentKey: string;
  periodType: "week" | "month";
  anchorDate: string;
  format?: "xlsx" | "csv";
}) =>
  downloadInventoryFile(
    `/department-period-reports/export${buildInventoryQuery({ ...params, format: params.format || "xlsx" })}`,
    `department-${params.periodType}-${params.anchorDate}.${params.format === "csv" ? "zip" : "xlsx"}`
  );

export const downloadDepartmentUsageReportApi = async (params: DepartmentUsageReportParams): Promise<InventoryReportDownload> => {
  const { format, stage, ...filters } = params;
  const query = { ...filters, triggerStage: stage };
  return downloadInventoryFile(
    `/reports/department-usage.${format}${buildInventoryQuery(query as unknown as Record<string, unknown>)}`,
    `department-usage.${format}`
  );
};

export const saveInventoryItemApi = async (params: SaveInventoryItemParams) =>
  response(await postInventory("/items", params), "物资档案已保存");

export const getInventoryPortalAccountsApi = async () => getInventoryData<InventoryPortalAccountCatalog>("/portal-accounts");

export const updateInventoryPortalAccountApi = async (
  accountId: string,
  payload: Pick<InventoryPortalAccount, "portalRole" | "status" | "departmentKey">
) =>
  putInventoryData<InventoryPortalAccountCatalog, Pick<InventoryPortalAccount, "portalRole" | "status" | "departmentKey">>(
    `/portal-accounts/${accountId}`,
    payload
  );

export const resetInventoryPortalAccountPasswordApi = async (accountId: string) =>
  postInventoryData<InventoryPortalAccountCatalog, Record<string, never>>(`/portal-accounts/${accountId}/reset-password`, {});

export const getInventoryMessageBoardPostsApi = async (
  params: {
    keyword?: string;
    category?: InventoryMessageBoardCategory | "";
    status?: InventoryMessageBoardStatus | "";
    departmentKey?: string;
    onlyMine?: boolean;
    page?: number;
    size?: number;
  } = {}
) => getInventoryData<InventoryMessageBoardPage>("/message-board/posts", params as Record<string, unknown>);

export const getInventoryMessageBoardPostApi = async (postId: string) =>
  getInventoryData<InventoryMessageBoardDetail>(`/message-board/posts/${encodeURIComponent(postId)}`);

export const createInventoryMessageBoardPostApi = async (payload: {
  title: string;
  content: string;
  category: InventoryMessageBoardCategory;
}) => postInventoryData<InventoryMessageBoardPost, typeof payload>("/message-board/posts", payload);

export const updateInventoryMessageBoardPostApi = async (
  postId: string,
  payload: { title: string; content: string; category: InventoryMessageBoardCategory }
) => putInventoryData<InventoryMessageBoardPost, typeof payload>(`/message-board/posts/${encodeURIComponent(postId)}`, payload);

export const withdrawInventoryMessageBoardPostApi = async (postId: string) =>
  postInventoryData<{ id: string; withdrawn: boolean }, Record<string, never>>(
    `/message-board/posts/${encodeURIComponent(postId)}/withdraw`,
    {}
  );

export const createInventoryMessageBoardReplyApi = async (postId: string, content: string) =>
  postInventoryData<InventoryMessageBoardReply, { content: string }>(
    `/message-board/posts/${encodeURIComponent(postId)}/replies`,
    { content }
  );

export const updateInventoryMessageBoardReplyApi = async (replyId: string, content: string) =>
  putInventoryData<InventoryMessageBoardReply, { content: string }>(`/message-board/replies/${encodeURIComponent(replyId)}`, {
    content
  });

export const withdrawInventoryMessageBoardReplyApi = async (replyId: string) =>
  postInventoryData<{ id: string; withdrawn: boolean }, Record<string, never>>(
    `/message-board/replies/${encodeURIComponent(replyId)}/withdraw`,
    {}
  );

export const updateInventoryMessageBoardStatusApi = async (
  postId: string,
  payload: { status: InventoryMessageBoardStatus; handlingNote: string }
) =>
  putInventoryData<InventoryMessageBoardPost, typeof payload>(
    `/message-board/admin/posts/${encodeURIComponent(postId)}/status`,
    payload
  );

export const updateInventoryMessageBoardPinApi = async (postId: string, pinned: boolean) =>
  putInventoryData<InventoryMessageBoardPost, { pinned: boolean }>(
    `/message-board/admin/posts/${encodeURIComponent(postId)}/pin`,
    { pinned }
  );

export const updateInventoryMessageBoardPostVisibilityApi = async (postId: string, hidden: boolean) =>
  putInventoryData<InventoryMessageBoardPost, { hidden: boolean }>(
    `/message-board/admin/posts/${encodeURIComponent(postId)}/visibility`,
    { hidden }
  );

export const updateInventoryMessageBoardReplyVisibilityApi = async (replyId: string, hidden: boolean) =>
  putInventoryData<InventoryMessageBoardReply, { hidden: boolean }>(
    `/message-board/admin/replies/${encodeURIComponent(replyId)}/visibility`,
    { hidden }
  );

export const getInventoryMessageBoardAuditLogsApi = async (
  params: {
    targetType?: "POST" | "REPLY";
    targetId?: string;
    page?: number;
    size?: number;
  } = {}
) => getInventoryData<InventoryMessageBoardAuditPage>("/message-board/admin/audit-logs", params as Record<string, unknown>);

export const getInventoryRoleManagementApi = async () =>
  getInventoryData<{ roles: InventoryRoleDescriptor[]; accounts: InventoryAccountAssignment[] }>("/role-management");

export const assignInventoryAccountRoleApi = async (params: { accountId: string; roleCode: string }) =>
  postInventoryData<
    { roles: InventoryRoleDescriptor[]; accounts: InventoryAccountAssignment[] },
    { accountId: string; roleCode: string }
  >("/role-management/assign", params);

export const inboundInventoryApi = async (params: InventoryInboundParams) =>
  response(await postInventory("/inbounds", params), "入库记录已保存");

export const createInventoryRequestApi = async (params: InventoryRequestParams) =>
  response(await postInventory("/requests", params), "申领单已提交");

export const approveInventoryRequestApi = async (params: InventoryActionParams) =>
  response(await postInventory("/requests/approve", params), "申领单已审核");

export const issueInventoryRequestApi = async (params: InventoryActionParams) =>
  response(await postInventory("/requests/issue", params), "物资已发放");

export const receiveInventoryRequestApi = async (params: InventoryActionParams) =>
  response(await postInventory("/requests/receive", params), "领取已确认");

export const rejectInventoryRequestApi = async (params: InventoryActionParams) =>
  response(await postInventory("/requests/reject", params), "申领单已驳回");

export const cancelInventoryRequestApi = async (params: InventoryActionParams) =>
  response(await postInventory("/requests/cancel", params), "申领单已撤销");

export const voidInventoryRequestApi = async (params: InventoryActionParams) =>
  response(await postInventory("/requests/void", params), "申领单已作废");

export const saveWeeklyConsumptionApi = async (params: WeeklyConsumptionParams) =>
  response(await postInventory("/weekly-consumptions", params), "周消耗已确认");

export const getInventoryWeeklyStandardsApi = async () => {
  const result = await getInventoryData<InventoryApiList<InventoryWeeklyStandard>>("/weekly/standards");
  return response(result.data.list || []);
};

export const getInventoryWeeklyStandardApi = async (id: string) =>
  getInventoryData<InventoryWeeklyStandard>("/weekly/standards/detail", { id });

export const saveInventoryWeeklyStandardApi = async (params: SaveInventoryWeeklyStandardParams) =>
  postInventoryData<InventoryWeeklyStandard, SaveInventoryWeeklyStandardParams>("/weekly/standards", params);

export const publishInventoryWeeklyStandardApi = async (id: string) =>
  postInventoryData<InventoryWeeklyStandard, { id: string }>("/weekly/standards/publish", { id });

export const deleteInventoryWeeklyStandardApi = async (id: string) =>
  postInventoryData<{ deleted: string }, { id: string }>("/weekly/standards/delete", { id });

export const getInventoryWeeklySnapshotsApi = async (params: InventoryWeeklySnapshotQueryParams = {}) => {
  const result = await getInventoryData<InventoryApiList<InventoryWeeklySnapshot>>(
    "/weekly/snapshots",
    params as Record<string, unknown>
  );
  return response(result.data.list || []);
};

export const getInventoryWeeklySnapshotApi = async (id: string) =>
  getInventoryData<InventoryWeeklySnapshot>("/weekly/snapshots/detail", { id });

export const generateInventoryWeeklySnapshotApi = async (params: GenerateInventoryWeeklySnapshotParams) =>
  postInventoryData<InventoryWeeklySnapshot, GenerateInventoryWeeklySnapshotParams>("/weekly/snapshots/generate", params);

export const confirmInventoryWeeklySnapshotApi = async (params: ConfirmInventoryWeeklySnapshotParams) =>
  postInventoryData<InventoryWeeklySnapshot, ConfirmInventoryWeeklySnapshotParams>("/weekly/snapshots/confirm", params);

export const reviseInventoryWeeklySnapshotApi = async (params: ReviseInventoryWeeklySnapshotParams) =>
  postInventoryData<InventoryWeeklySnapshot, ReviseInventoryWeeklySnapshotParams>("/weekly/snapshots/revise", params);

export const downloadInventoryWeeklySnapshotApi = async (id: string, format: InventoryWeeklyExportFormat) =>
  downloadInventoryFile(`/weekly/snapshots/export${buildInventoryQuery({ id, format })}`, `inventory-weekly-snapshot.${format}`);

export const returnOrScrapInventoryApi = async (params: ReturnOrScrapParams) =>
  response(await postInventory("/movements/return-or-scrap", params), "库存变更已记录");

export const countInventoryApi = async (params: InventoryCountParams) =>
  response(await postInventory("/counts", params), "盘点结果已记录");

export const saveInventoryPackageApi = async (params: SaveInventoryPackageParams) =>
  response(await postInventory("/packages", params), "使用套餐草稿已保存");

export const enableInventoryPackageApi = async (params: InventoryPackageActionParams) =>
  response(await postInventory("/packages/enable", params), "使用套餐已启用");

export const disableInventoryPackageApi = async (params: InventoryPackageActionParams) =>
  response(await postInventory("/packages/disable", params), "使用套餐已停用");

export const retryInventoryConsumptionEventApi = async (params: InventoryPackageActionParams) =>
  response(await postInventory("/consumption-events/retry", params), "自动消耗事件已重试");

const normalizeMappingEntry = (row: InventoryMappingEntry): InventoryMappingEntry => ({
  ...row,
  sourceRow: normalizeNumber(row.sourceRow),
  suggestedQuantity: row.suggestedQuantity === undefined ? undefined : normalizeNumber(row.suggestedQuantity),
  canCreatePackageDraft: Boolean(row.canCreatePackageDraft)
});

export const getInventoryMappingSummaryApi = async () => getInventoryData<InventoryMappingSummary>("/mapping/summary");

export const getInventoryMappingEntriesApi = async (params: InventoryMappingEntryQueryParams = {}) => {
  const result = await getInventoryData<InventoryMappingEntriesPage>("/mapping/entries", params as Record<string, unknown>);
  return response<InventoryMappingEntriesPage>({
    total: normalizeNumber(result.data.total),
    page: normalizeNumber(result.data.page || params.page || 1),
    size: normalizeNumber(result.data.size || params.size || 50),
    list: (result.data.list || []).map(normalizeMappingEntry)
  });
};

export const getInventoryDepartmentUsageReportApi = async (params: Omit<DepartmentUsageReportParams, "format">) => {
  const { stage, ...filters } = params;
  return getInventoryData<InventoryDepartmentUsageReport>("/reports/department-usage", {
    ...filters,
    triggerStage: stage
  });
};

export const confirmInventoryMappingEntriesApi = async (params: ConfirmInventoryMappingEntriesParams) =>
  postInventoryData<{ updated: number; list: InventoryMappingEntry[] }, ConfirmInventoryMappingEntriesParams>(
    "/mapping/entries/confirm",
    params
  );

export const holdInventoryMappingEntriesApi = async (params: InventoryMappingActionParams) =>
  postInventoryData<{ updated: number; list: InventoryMappingEntry[] }, InventoryMappingActionParams>(
    "/mapping/entries/hold",
    params
  );

export const createInventoryMappingPackageDraftApi = async (params: InventoryMappingActionParams) =>
  postInventoryData<
    { created: boolean; draftPackageId: string; mappingCount: number; package?: InventoryPackage },
    InventoryMappingActionParams
  >("/mapping/entries/create-package-draft", params);

export const getInventoryItemAliasesApi = async (params: { itemId?: string; status?: string; keyword?: string } = {}) => {
  const result = await getInventoryData<InventoryApiList<InventoryItemAlias> & { total?: number }>(
    "/mapping/aliases",
    params as Record<string, unknown>
  );
  return response(result.data.list || []);
};

export const saveInventoryItemAliasesApi = async (params: { aliases: InventoryItemAlias[]; itemId?: string }) =>
  postInventoryData<
    InventoryApiList<InventoryItemAlias> & { total?: number },
    { aliases: InventoryItemAlias[]; itemId?: string }
  >("/mapping/aliases", params);

export const getInventoryUnitConversionsApi = async (params: { itemId?: string; status?: string; keyword?: string } = {}) => {
  const result = await getInventoryData<InventoryApiList<InventoryUnitConversion> & { total?: number }>(
    "/mapping/unit-conversions",
    params as Record<string, unknown>
  );
  return response((result.data.list || []).map(row => ({ ...row, factor: normalizeNumber(row.factor) })));
};

export const saveInventoryUnitConversionsApi = async (params: { unitConversions: InventoryUnitConversion[]; itemId?: string }) =>
  postInventoryData<
    InventoryApiList<InventoryUnitConversion> & { total?: number },
    { unitConversions: InventoryUnitConversion[]; itemId?: string }
  >("/mapping/unit-conversions", params);
