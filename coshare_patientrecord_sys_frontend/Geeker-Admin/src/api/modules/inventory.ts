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
  bindingType?: InventoryQuotaBindingType;
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

export interface InventoryDepartmentDraftHistoryDay {
  businessDate: string;
  revision: number;
  operator?: string;
  updatedAt?: string;
  lineCount?: number;
  filledCount?: number;
}

export interface InventoryDepartmentDailyDraftHistory {
  departmentKey: string;
  departmentName: string;
  from: string;
  to: string;
  days: InventoryDepartmentDraftHistoryDay[];
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

export type InventoryQuotaBindingType = "PER_PERSON" | "FIXED_DAILY" | "ON_DEMAND" | "EQUIPMENT";

export interface InventoryQuotaRule {
  id: string;
  versionId: string;
  departmentKey: string;
  departmentName: string;
  sourceRow: number;
  serviceGroup: string;
  careType: string;
  materialName: string;
  itemId?: string | null;
  unit: string;
  standardQuantity: number | null;
  fixedAdjustment: number;
  measurementScope: "OUTPATIENT" | "INPATIENT" | "COMBINED" | "OTHER";
  bindingType: InventoryQuotaBindingType;
  commonItem?: boolean;
  enabled: boolean;
}

export interface InventoryQuotaGovernance {
  queryDate: string;
  activeVersion: InventoryQuotaVersion | null;
  versions: InventoryQuotaVersion[];
  rules: InventoryQuotaRule[];
}

export interface InventoryQuotaMappingRule {
  ruleId: string;
  departmentKey: string;
  departmentName: string;
  sourceRow: number;
  serviceGroup: string;
  careType?: string;
  materialName: string;
  unit: string;
  standardQuantity: number | null;
  bindingType?: InventoryQuotaBindingType;
  commonItem: boolean;
}

export interface InventoryQuotaMappingWorklist {
  unmappedCount: number;
  rules: InventoryQuotaMappingRule[];
  items: InventoryPatientAvailableItem[];
}

export interface InventoryQuotaRuleCreatePayload {
  departmentKey: string;
  materialName: string;
  unit: string;
  serviceGroup: string;
  careType: string;
  standardQuantity: number | null;
  fixedAdjustment: number;
  measurementScope: InventoryQuotaRule["measurementScope"];
  bindingType?: InventoryQuotaBindingType;
  enabled?: boolean;
}

export interface InventoryQuotaApplyTodayResult {
  versionId: string;
  versionCode: string;
  effectiveDate: string;
  seededDepartments: string[];
  seededCount: number;
  skippedCount: number;
}

export interface InventoryQuotaConsoleSaveResult extends InventoryQuotaGovernance {
  savedVersionId?: string;
  savedVersionCode?: string;
  savedEffectiveDate?: string;
  applyTodayRequested?: boolean;
  applyTodayResult?: InventoryQuotaApplyTodayResult;
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
    bindingType?: InventoryQuotaBindingType;
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

export interface InventoryDepartmentDailyReportLine {
  lineId?: string;
  itemId: string;
  itemName: string;
  unit: string;
  presetQuantity?: number | null;
  actualQuantity: number;
  source: "TEMPLATE" | "MANUAL";
  exceptionReason?: string;
  reviewStatus?: string;
  reviewNote?: string;
}

export interface InventoryDepartmentDailyReport {
  id: string;
  departmentKey: string;
  departmentId: string;
  departmentName: string;
  businessDate: string;
  entryMode: "PATIENT" | "DAILY";
  status: string;
  revision: number;
  note: string;
  submittedBy?: string;
  submittedAt?: string;
  zeroUsage?: boolean;
  locked: boolean;
  closure?: { status: string; submittedBy?: string; submittedAt?: string; lockedAt?: string };
  lines: InventoryDepartmentDailyReportLine[];
}

export type InventoryPatientDocumentStatus =
  | "NOT_STARTED"
  | "DRAFT"
  | "SUBMITTED"
  | "PARTIALLY_APPROVED"
  | "RETURNED"
  | "APPROVED";

export interface InventoryPatientWorkbenchRow {
  serviceInstanceId: string;
  sourceType?: string;
  patientId: string;
  patientName: string;
  visitNo?: string;
  encounterId: string;
  careType?: string;
  departmentId?: string;
  departmentKey?: string;
  departmentName?: string;
  serviceCode?: string;
  serviceName?: string;
  stageCode?: string;
  serviceStartedAt?: string;
  arrivalAt?: string;
  patientState?: "" | "NOT_ARRIVED" | "DEFERRED";
  statusSince?: string;
  statusAgeMinutes?: number;
  businessDate?: string;
  status: InventoryPatientDocumentStatus;
  documentId: string;
  revision: number;
  submittedAt?: string;
  submittedBy?: string;
  totalQuantity?: number;
  lineCount?: number;
  noConsumption?: boolean;
  specialNote?: string;
  attentionFlag?: boolean;
}

export interface InventoryPatientWorkbench {
  departmentKey: string;
  departmentName: string;
  businessDate: string;
  cutoverDate?: string;
  patients: InventoryPatientWorkbenchRow[];
  summary: {
    total: number;
    draft: number;
    submitted: number;
    approved: number;
    returned: number;
    notStarted: number;
    noConsumption?: number;
    entered?: number;
    missing?: number;
    expected?: number;
    notArrived?: number;
    deferred?: number;
    pendingSlaWarn?: number;
    pendingSlaOverdue?: number;
    returnedSlaWarn?: number;
    returnedSlaOverdue?: number;
    progress?: number;
  };
}

export interface InventoryPatientPresetItem {
  ruleId: string;
  itemId: string;
  itemName: string;
  unit: string;
  materialName?: string;
  presetQuantity: number;
  serviceGroup?: string;
  careType?: string;
  bindingType?: string;
  triggerEvent?: string;
  commonItem: boolean;
  matchesService: boolean;
  aliases?: string[];
}

export interface InventoryPatientAvailableItem {
  itemId: string;
  itemName: string;
  unit: string;
  spec?: string;
  category?: string;
  aliases?: string[];
}

export interface InventoryPatientConsumptionLine {
  lineId: string;
  itemId: string;
  itemName: string;
  unit: string;
  ruleId?: string;
  presetQuantity?: number | null;
  actualQuantity: number;
  source?: string;
  exceptionReason?: string;
  reviewStatus: "PENDING" | "APPROVED" | "REJECTED";
  reviewNote?: string;
  lineRevision: number;
  approvedQuantity?: number | null;
  consumptionEventId?: string;
}

export interface InventoryPatientDetail extends InventoryPatientWorkbenchRow {
  noConsumption?: boolean;
  presetItems: { items: InventoryPatientPresetItem[] };
  availableItems: InventoryPatientAvailableItem[];
  lines: InventoryPatientConsumptionLine[];
}

export interface InventoryPatientDocumentSavePayload {
  serviceInstanceId: string;
  revision: number;
  noConsumption?: boolean;
  correctionReason?: string;
  lines: Array<{
    itemId: string;
    itemName?: string;
    unit?: string;
    ruleId?: string;
    presetQuantity?: number;
    actualQuantity: number;
    source: "COMMON" | "PRESET" | "MANUAL";
    exceptionReason?: string;
  }>;
}

export interface InventoryPatientBatchSubmitResult {
  businessDate: string;
  total: number;
  submitted: number;
  blocked: boolean;
  missing: Array<{ serviceInstanceId: string; patientName: string; reason: string }>;
  failed: Array<{ serviceInstanceId: string; patientName: string; reason: string }>;
}

export interface InventoryPatientRecentItems {
  items: Array<{
    itemId: string;
    itemName: string;
    unit: string;
    usageCount: number;
    lastDate?: string;
    aliases?: string[];
  }>;
}

export interface InventoryPatientLastCombination {
  items: Array<{
    itemId: string;
    itemName: string;
    unit: string;
    actualQuantity: number;
    source?: string;
    aliases?: string[];
  }>;
}

export interface InventoryPatientConsumablePackageItem {
  itemId: string;
  itemName: string;
  unit: string;
  quantity: number;
  source?: string;
  exceptionReason?: string;
  mappedItemName?: string;
  mappingConflict?: boolean;
}

export interface InventoryPatientConsumablePackage {
  id: string;
  departmentKey: string;
  departmentName: string;
  name: string;
  createdBy: string;
  updatedAt?: string;
  items: InventoryPatientConsumablePackageItem[];
}

export interface InventoryPatientConsumablePackageList {
  packages: InventoryPatientConsumablePackage[];
}

export interface InventoryPatientSearchResult {
  list: Array<{
    source: string;
    encounterId: string;
    patientId: string;
    patientName: string;
    visitNo?: string;
    careType?: string;
    currentStage?: string;
    departmentId?: string;
    departmentName?: string;
    visitDate?: string;
    caseToken?: string;
  }>;
}

export interface InventoryPatientSourceRow {
  patientId: string;
  patientName: string;
  visitNo: string;
  visitDate: string;
  visitType: string;
  status: string;
  encounterCount: number;
  encounterId: string;
  encounterVisitNo: string;
  encounterVisitType: string;
  encounterVisitDate: string;
  caseToken: string;
  currentStage: string;
  departmentName: string;
}

export interface InventoryPatientSourceList {
  total: number;
  pageNum: number;
  pageSize: number;
  departments?: string[];
  list: InventoryPatientSourceRow[];
}

export interface InventoryPatientReviewBatch {
  batchId: string;
  departmentId: string;
  departmentKey: string;
  departmentName: string;
  batchType?: "PATIENT" | "DAILY";
  businessDate: string;
  status: string;
  documentCount: number;
  lineCount: number;
  approvedLineCount: number;
  rejectedLineCount: number;
  reviewedBy?: string;
  reviewedAt?: string;
}

export interface InventoryPatientReviewBatchList {
  businessDate: string;
  summary: {
    total: number;
    pending: number;
    partiallyApproved: number;
    approved: number;
    returned: number;
  };
  list: InventoryPatientReviewBatch[];
}

export interface InventoryPatientReviewDetail {
  batch: InventoryPatientReviewBatch;
  documents: Array<{
    documentId: string;
    serviceInstanceId: string;
    patientId: string;
    patientName: string;
    visitNo?: string;
    encounterId: string;
    careType?: string;
    serviceCode?: string;
    serviceName?: string;
    stageCode?: string;
    sourceType?: string;
    serviceStartedAt?: string;
    status: string;
    revision: number;
    submittedAt?: string;
    submittedBy?: string;
    lines: InventoryPatientConsumptionLine[];
  }>;
  errors?: Array<{ lineId: string; message: string }>;
  deferred?: Array<{ lineId: string; itemId?: string; itemName?: string; message: string }>;
  deferredLineCount?: number;
  deducted?: number;
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

const deleteInventoryData = async <T>(path: string) => {
  const result = await fetch(`${INVENTORY_API_BASE_URL}${path}`, { method: "DELETE", headers: authHeaders() });
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

export const getInventoryDepartmentDailyReportApi = async (params: { departmentKey: string; date: string }) =>
  getInventoryData<InventoryDepartmentDailyReport>("/department-daily-reports", params);

export interface InventoryDepartmentDailyReportCalendarDay {
  date: string;
  reportId: string;
  status: string;
  lineCount: number;
  patientCount: number;
  patientTotal?: number;
  entered?: number;
  draft?: number;
  returned?: number;
  missing?: number;
  noConsumption?: number;
  submittedAt?: string;
  submittedBy?: string;
}

export interface InventoryDepartmentDailyReportCalendar {
  departmentKey: string;
  departmentName: string;
  entryMode: "PATIENT" | "DAILY";
  month: string;
  today: string;
  expectedFrom?: string;
  days: InventoryDepartmentDailyReportCalendarDay[];
}

export const getInventoryDepartmentDailyReportCalendarApi = async (params: {
  departmentKey: string;
  month: string;
}) => getInventoryData<InventoryDepartmentDailyReportCalendar>("/department-daily-reports/calendar", params);

export interface InventoryFixedConsumable {
  itemId: string;
  itemName: string;
  unit: string;
  standardQuantity?: number | null;
  yesterdayQuantity?: number | null;
  actualQuantity: number;
  source: "TEMPLATE" | "MANUAL";
  reviewStatus?: string;
}

export interface InventoryFixedConsumables {
  departmentKey: string;
  departmentName: string;
  entryMode: "PATIENT" | "DAILY";
  businessDate: string;
  reportId: string;
  status: string;
  revision: number;
  zeroUsage: boolean;
  locked: boolean;
  items: InventoryFixedConsumable[];
}

export const getInventoryFixedConsumablesApi = async (params: { departmentKey: string; date: string }) =>
  getInventoryData<InventoryFixedConsumables>("/patient-workbench/fixed-consumables", params);

export interface InventoryFixedHistory {
  departmentKey: string;
  departmentName: string;
  from: string;
  to: string;
  days: Array<{
    date: string;
    reportId: string;
    status: string;
    itemCount: number;
    totalQuantity: number;
    submittedAt: string;
  }>;
  items: Array<{
    itemId: string;
    itemName: string;
    unit: string;
    totalQuantity: number;
    daysUsed: number;
  }>;
}

export const getInventoryFixedConsumablesHistoryApi = async (params: {
  departmentKey: string;
  from?: string;
  to?: string;
}) => getInventoryData<InventoryFixedHistory>("/patient-workbench/fixed-consumables/history", params);

export interface InventoryFollowupAppointment {
  recheckId: string;
  planDate: string;
  timeSlot: "AM" | "PM" | "EVENING" | "";
  patientName: string;
  phone: string;
  note: string;
  status: string;
  matchType: "SOURCE" | "NAME_PHONE" | "PENDING";
  matched: boolean;
  patientId: string;
  encounterId: string;
  visitNo: string;
}

export interface InventoryFollowupSlot {
  slot: string;
  label: string;
  items: InventoryFollowupAppointment[];
}

export interface InventoryFollowupAppointments {
  businessDate: string;
  departmentKey: string;
  slots: InventoryFollowupSlot[];
}

export const getInventoryFollowupAppointmentsApi = async (params: { departmentKey: string; date: string }) =>
  getInventoryData<InventoryFollowupAppointments>("/patient-workbench/followup-appointments", params);

export const arriveInventoryFollowupAppointmentApi = async (
  recheckId: string,
  payload: { departmentKey: string; businessDate: string }
) =>
  postInventoryData<InventoryPatientDetail, typeof payload>(
    `/patient-workbench/followup-appointments/${encodeURIComponent(recheckId)}/arrive`,
    payload
  );

export const saveInventoryDepartmentDailyReportApi = async (payload: {
  departmentKey: string;
  businessDate: string;
  revision: number;
  note: string;
  zeroUsage?: boolean;
  lines: Array<{
    itemId: string;
    itemName?: string;
    unit?: string;
    presetQuantity?: number | null;
    actualQuantity: number;
    source: "TEMPLATE" | "MANUAL";
    exceptionReason?: string;
  }>;
}) =>
  putInventoryData<InventoryDepartmentDailyReport, typeof payload>("/department-daily-reports", payload);

export const withdrawInventoryDepartmentZeroUseApi = async (reportId: string) =>
  postInventoryData<InventoryDepartmentDailyReport, Record<string, unknown>>(
    `/department-daily-reports/${encodeURIComponent(reportId)}/zero-use/withdraw`,
    {}
  );

export const submitInventoryDepartmentDailyReportApi = async (reportId: string) =>
  postInventoryData<InventoryDepartmentDailyReport, Record<string, unknown>>(
    `/department-daily-reports/${encodeURIComponent(reportId)}/submit`,
    {}
  );

export const downloadInventoryDepartmentDailyReportApi = async (reportId: string, format: "xlsx" | "csv") =>
  downloadInventoryFile(
    `/department-daily-reports/${encodeURIComponent(reportId)}/export/${format}`,
    `department-daily-report-${reportId}.${format}`
  );

export const getInventoryDepartmentDailyDraftSummaryApi = async (date: string) =>
  getInventoryData<InventoryDepartmentDailyDraftSummary>("/department-daily-drafts/summary", { date });

export const getInventoryDepartmentDailyDraftHistoryApi = async (params: { departmentKey: string; from: string; to: string }) =>
  getInventoryData<InventoryDepartmentDailyDraftHistory>("/department-daily-drafts/history", params);

export const getInventoryDepartmentDailyRollupApi = async (query: InventoryDailyRollupQuery) =>
  getInventoryData<InventoryAdminDepartmentDailyRollup>("/department-daily-drafts/admin-rollup", query);

export const getInventoryQuotaGovernanceApi = async (date?: string, versionId?: string) =>
  getInventoryData<InventoryQuotaGovernance>(
    "/quota-governance",
    { ...(date ? { date } : {}), ...(versionId ? { versionId } : {}) }
  );

export const getInventoryQuotaMappingWorklistApi = async (params: {
  departmentKey?: string;
  keyword?: string;
} = {}) => getInventoryData<InventoryQuotaMappingWorklist>("/quota-governance/mapping-worklist", params);

export const saveInventoryQuotaMappingsApi = async (payload: {
  mappings: Array<{ ruleId: string; itemId: string; commonItem?: boolean }>;
}) =>
  putInventoryData<InventoryQuotaMappingWorklist, typeof payload>(
    "/quota-governance/mapping-worklist",
    payload
  );

export const createInventoryQuotaVersionApi = async (payload: {
  versionCode: string;
  effectiveDate: string;
  baseVersionId?: string;
}) => postInventoryData<InventoryQuotaGovernance, typeof payload>("/quota-governance/versions", payload);

export const updateInventoryQuotaRuleApi = async (
  ruleId: string,
  payload: Pick<InventoryQuotaRule, "standardQuantity" | "fixedAdjustment" | "measurementScope" | "bindingType" | "enabled">
) => putInventoryData<InventoryQuotaGovernance, typeof payload>(`/quota-governance/rules/${encodeURIComponent(ruleId)}`, payload);

export const createInventoryQuotaRuleApi = async (payload: InventoryQuotaRuleCreatePayload & { versionId?: string }) =>
  postInventoryData<InventoryQuotaGovernance, typeof payload>("/quota-governance/rules", payload);

export const deleteInventoryQuotaRuleApi = async (ruleId: string) =>
  deleteInventoryData<InventoryQuotaGovernance>(`/quota-governance/rules/${encodeURIComponent(ruleId)}`);

export const updateInventoryQuotaRulesBatchApi = async (payload: {
  rules: Array<Pick<InventoryQuotaRule, "id" | "standardQuantity" | "fixedAdjustment" | "measurementScope" | "bindingType" | "enabled">>;
}) => putInventoryData<InventoryQuotaGovernance, typeof payload>("/quota-governance/rules/batch", payload);

export const consoleSaveInventoryQuotaApi = async (payload: {
  versionId?: string;
  effectiveDate?: string;
  versionCode?: string;
  baseVersionId?: string;
  updates?: Array<Pick<InventoryQuotaRule, "id" | "standardQuantity" | "fixedAdjustment" | "measurementScope" | "bindingType" | "enabled">>;
  creates?: InventoryQuotaRuleCreatePayload[];
  deletes?: string[];
  applyToday?: boolean;
}) => postInventoryData<InventoryQuotaConsoleSaveResult, typeof payload>("/quota-governance/console-save", payload);

export const applyInventoryQuotaTodayApi = async (versionId: string) =>
  postInventoryData<InventoryQuotaApplyTodayResult, { versionId: string }>("/quota-governance/apply-today", { versionId });

export interface InventoryQuotaAuditEntry {
  id: string;
  versionId: string;
  versionCode: string;
  departmentKey: string;
  departmentName: string;
  materialName: string;
  unit: string;
  serviceGroup: string;
  action: "CREATE" | "UPDATE" | "DELETE" | string;
  beforeStandardQuantity: number | null;
  afterStandardQuantity: number | null;
  beforeFixedAdjustment: number | null;
  afterFixedAdjustment: number | null;
  beforeEnabled: boolean | null;
  afterEnabled: boolean | null;
  beforeMeasurementScope: string | null;
  afterMeasurementScope: string | null;
  beforeBindingType: string | null;
  afterBindingType: string | null;
  operatorUsername: string;
  operatorName: string;
  createdAt: string;
}

export const getInventoryQuotaAuditLogApi = async (params: { versionId?: string; limit?: number } = {}) =>
  getInventoryData<{ list: InventoryQuotaAuditEntry[] }>("/quota-governance/audit-log", params);

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

export const getInventoryPatientWorkbenchApi = async (params: { departmentKey: string; date?: string }) =>
  getInventoryData<InventoryPatientWorkbench>("/patient-workbench", params);

export const getInventoryPatientDetailApi = async (serviceInstanceId: string) =>
  getInventoryData<InventoryPatientDetail>("/patient-workbench/detail", { serviceInstanceId });

export const searchInventoryPatientsApi = async (keyword?: string) =>
  getInventoryData<InventoryPatientSearchResult>("/patient-workbench/patients", { keyword });

export const getInventoryPatientSourceApi = async (params: {
  keyword?: string;
  visitType?: string;
  date?: string;
  department?: string;
  page?: number;
  size?: number;
}) => getInventoryData<InventoryPatientSourceList>("/patient-workbench/patient-source", params);

export const saveInventoryPatientNoteApi = async (payload: {
  serviceInstanceId: string;
  specialNote: string;
  attentionFlag: boolean;
}) =>
  putInventoryData<{ serviceInstanceId: string; specialNote: string; attentionFlag: boolean }, typeof payload>(
    "/patient-workbench/notes",
    payload
  );

export const addInventoryManualPatientApi = async (payload: {
  departmentKey: string;
  encounterId: string;
  patientId?: string;
  patientName?: string;
  visitNo?: string;
  careType?: string;
  serviceCode?: string;
  serviceName?: string;
  serviceStartedAt?: string;
  businessDate?: string;
  stageCode?: string;
  sourceType?: "REGISTRATION" | "MANUAL";
  lateReason?: string;
  note?: string;
}) => postInventoryData<InventoryPatientDetail, typeof payload>("/patient-workbench/manual-patients", payload);

export const saveInventoryPatientDocumentApi = async (payload: InventoryPatientDocumentSavePayload) =>
  putInventoryData<InventoryPatientDetail, InventoryPatientDocumentSavePayload>("/patient-workbench/documents", payload);

export const submitInventoryPatientDocumentApi = async (documentId: string) =>
  postInventoryData<InventoryPatientDetail, Record<string, unknown>>(
    `/patient-workbench/documents/${encodeURIComponent(documentId)}/submit`,
    {}
  );

export const submitInventoryPatientBatchApi = async (payload: { departmentKey: string; businessDate: string }) =>
  postInventoryData<InventoryPatientBatchSubmitResult, typeof payload>("/patient-workbench/batches/submit", payload);

export const setInventoryPatientDayStatusApi = async (payload: {
  departmentKey: string;
  businessDate: string;
  patientIds: string[];
  status: "NOT_ARRIVED" | "DEFERRED";
}) => putInventoryData<{ updated: number; status: string }, typeof payload>("/patient-workbench/day-status", payload);

export const restoreInventoryPatientDayStatusApi = async (payload: {
  departmentKey: string;
  businessDate: string;
  patientIds: string[];
}) => putInventoryData<{ restored: number }, typeof payload>("/patient-workbench/day-status/restore", payload);

export const batchInventoryPatientZeroUseApi = async (payload: {
  departmentKey: string;
  businessDate: string;
  patients: Array<{
    patientId: string;
    encounterId?: string;
    patientName?: string;
    visitNo?: string;
    careType?: string;
  }>;
}) => postInventoryData<{ updated: number; errors: Array<{ patientName: string; message: string }> }, typeof payload>(
  "/patient-workbench/zero-use",
  payload
);

export const withdrawInventoryPatientZeroUseApi = async (payload: {
  departmentKey: string;
  businessDate: string;
  patientIds: string[];
}) => postInventoryData<{ withdrawn: number }, typeof payload>("/patient-workbench/zero-use/withdraw", payload);

export const getInventoryPatientRecentItemsApi = async (params: { departmentKey: string; limit?: number }) =>
  getInventoryData<InventoryPatientRecentItems>("/patient-workbench/recent-items", params);

export const getInventoryPatientLastCombinationApi = async (params: { patientId: string; departmentKey: string }) =>
  getInventoryData<InventoryPatientLastCombination>("/patient-workbench/last-combination", params);

export const getInventoryPatientPackagesApi = async (params: { departmentKey: string }) =>
  getInventoryData<InventoryPatientConsumablePackageList>("/patient-workbench/packages", params);

export const getAllInventoryPatientPackagesApi = async () =>
  getInventoryData<InventoryPatientConsumablePackageList>("/patient-workbench/packages/all");

export const saveInventoryPatientPackageApi = async (payload: {
  departmentKey: string;
  name: string;
  lines: Array<{
    itemId: string;
    itemName: string;
    unit: string;
    quantity: number;
    source?: string;
    exceptionReason?: string;
  }>;
}) =>
  postInventoryData<InventoryPatientConsumablePackageList, typeof payload>(
    "/patient-workbench/packages",
    payload
  );

export const deleteInventoryPatientPackageApi = async (packageId: string) =>
  deleteInventoryData<InventoryPatientConsumablePackageList>(
    `/patient-workbench/packages/${encodeURIComponent(packageId)}`
  );

export const getInventoryPatientTraceApi = async (patientId: string) =>
  getInventoryData<{
    patientId: string;
    cutoverDate?: string;
    instances: InventoryPatientDetail[];
  }>("/patient-trace", { patientId });

export interface InventoryConsumableUsageItem {
  itemId: string;
  itemName: string;
  unit: string;
  quantity: number;
  patientCount: number;
  approvedQuantity: number;
  pendingQuantity: number;
}

export interface InventoryConsumableUsagePatient {
  patientId?: string;
  patientName: string;
  visitNo?: string;
  departmentName: string;
  serviceName?: string;
  quantity: number;
  reviewStatus: string;
  documentStatus?: string;
  attentionFlag?: boolean;
  sourceType?: "PATIENT" | "DAILY";
}

export interface InventoryConsumableUsageTotals {
  itemCount: number;
  totalQuantity: number;
  patientCount: number;
  patientTotal?: number;
  entered?: number;
  draft?: number;
  returned?: number;
  missingPatientCount?: number;
  noConsumption?: number;
  reportTotal?: number;
  reportSubmitted?: number;
  reportMissing?: number;
  missingCount?: number;
}

export interface InventoryConsumableUsageMissingPatient {
  departmentKey: string;
  departmentName: string;
  serviceInstanceId: string;
  patientName: string;
  visitNo?: string;
  serviceName?: string;
  reason: string;
}

export interface InventoryConsumableUsageMissingReport {
  departmentKey: string;
  departmentName: string;
  reportId?: string;
  status: string;
  reason: string;
}

export interface InventoryConsumableUsageClosure {
  status: string;
  submittedBy?: string;
  submittedAt?: string;
  lockedAt?: string;
  locked?: boolean;
  closedDepartments?: number;
  totalDepartments?: number;
}

export interface InventoryConsumableUsage {
  businessDate: string;
  departmentKey: string;
  mode?: "PATIENT" | "DAILY" | "ALL";
  totals?: InventoryConsumableUsageTotals;
  closure?: InventoryConsumableUsageClosure;
  items?: InventoryConsumableUsageItem[];
  itemId?: string;
  patients?: InventoryConsumableUsagePatient[];
  missingPatients?: InventoryConsumableUsageMissingPatient[];
  missingReports?: InventoryConsumableUsageMissingReport[];
}

export interface InventoryDayCloseResult {
  businessDate: string;
  departmentKey: string;
  totalDepartments: number;
  closedDepartments: number;
  submittedDocuments: number;
  submittedReports: number;
  blocked: boolean;
  message: string;
  missingPatients: InventoryConsumableUsageMissingPatient[];
  failedPatients: InventoryConsumableUsageMissingPatient[];
  missingReports: InventoryConsumableUsageMissingReport[];
  closed: string[];
}

export const getInventoryConsumableUsageApi = async (params: {
  date?: string;
  departmentKey?: string;
  itemId?: string;
}) => getInventoryData<InventoryConsumableUsage>("/consumable-usage", params);

export const closeInventoryConsumableUsageDayApi = async (payload: {
  businessDate: string;
  departmentKey?: string;
}) =>
  postInventoryData<InventoryDayCloseResult, typeof payload>("/consumable-usage/close", payload);

export const downloadInventoryConsumableUsageDayApi = async (
  params: { date: string; departmentKey?: string },
  format: "xlsx" | "csv"
) =>
  downloadInventoryFile(
    `/consumable-usage/export/${format}${buildInventoryQuery(params)}`,
    `consumable-day-${params.date}.${format}`
  );

export interface InventoryAdminAnomalyPatient {
  serviceInstanceId: string;
  patientName: string;
  visitNo?: string;
  status: string;
  submittedAt?: string;
}

export interface InventoryAdminAnomaly {
  id: string;
  type: string;
  severity: "BLOCKING" | "ANOMALY" | "TIMELINESS";
  departmentKey: string;
  departmentName: string;
  businessDate: string;
  patientId?: string;
  patientName: string;
  visitNo?: string;
  serviceInstanceId?: string;
  documentId?: string;
  reportId?: string;
  status?: string;
  reason: string;
  currentValue?: string;
  thresholdValue?: string;
  submittedAt?: string;
  operatorName?: string;
  patients: InventoryAdminAnomalyPatient[];
}

export interface InventoryAdminAnomalyResponse {
  date: string;
  range: number;
  from: string;
  to: string;
  summary: { total: number; blocking: number; anomaly: number; timeliness: number; byType?: Record<string, number> };
  items: InventoryAdminAnomaly[];
}

export interface InventoryAdminQualityItem {
  key: string;
  label: string;
  count: number;
  severity: string;
  detail: string;
  route: string;
}

export interface InventoryAdminQuality {
  ready: boolean;
  items: InventoryAdminQualityItem[];
}

export interface InventoryAdminMatrix {
  departmentKey: string;
  departmentName: string;
  entryMode: "PATIENT" | "DAILY";
  metric: "QUANTITY" | "PER_PATIENT";
  from: string;
  to: string;
  dates: string[];
  patientCounts: Record<string, number>;
  reportDays: Record<string, number>;
  items: Array<{ itemId: string; itemName: string; unit: string; cells: Record<string, number | null> }>;
}

export interface InventoryAdminTrend {
  from: string;
  to: string;
  previousFrom: string;
  previousTo: string;
  items: Array<Record<string, unknown>>;
  anomalies: Array<Record<string, unknown>>;
  presetDeviations: Array<Record<string, unknown>>;
}

export interface InventoryAdminThresholdRow {
  scope: "GLOBAL" | "DEPARTMENT" | "ITEM";
  departmentKey: string;
  itemId: string;
  key: string;
  value: number;
  updatedBy?: string;
  updatedAt?: string;
}

export interface InventoryAdminThresholds {
  items: InventoryAdminThresholdRow[];
  departments: Array<{ id: string; key: string; name: string; entryMode: string }>;
}

export interface InventoryAdminReportSnapshot {
  id: string;
  reportType: "WEEK" | "MONTH";
  periodStart: string;
  periodEnd: string;
  status: string;
  generatedBy: string;
  generatedAt: string;
  summary?: {
    patients: number;
    reportDays: number;
    itemCount: number;
    totalQuantity: number;
    previousQuantity: number;
    deltaQuantity: number;
    changeRate?: number | null;
  };
  departments?: Array<{ departmentKey: string; departmentName: string; quantity: number; patients: number }>;
  items?: Array<{ itemId: string; itemName: string; unit: string; quantity: number }>;
  anomalies?: InventoryAdminAnomaly[];
  trends?: InventoryAdminTrend;
}

export interface InventoryAdminReportList {
  items: InventoryAdminReportSnapshot[];
}

export const getInventoryAdminAnomaliesApi = async (params: {
  date?: string;
  range?: number;
  type?: string;
  departmentKey?: string;
}) => getInventoryData<InventoryAdminAnomalyResponse>("/admin-review/anomalies", params);

export const getInventoryAdminDataQualityApi = async () =>
  getInventoryData<InventoryAdminQuality>("/admin-review/data-quality");

export const getInventoryAdminMatrixApi = async (params: {
  departmentKey?: string;
  from?: string;
  to?: string;
  metric?: "QUANTITY" | "PER_PATIENT";
}) => getInventoryData<InventoryAdminMatrix>("/admin-review/matrix", params);

export const getInventoryAdminTrendsApi = async (params: {
  periodStart?: string;
  periodEnd?: string;
  departmentKey?: string;
}) => getInventoryData<InventoryAdminTrend>("/admin-review/trends", params);

export const getInventoryAdminThresholdsApi = async () =>
  getInventoryData<InventoryAdminThresholds>("/admin-review/thresholds");

export const saveInventoryAdminThresholdsApi = async (payload: {
  items: Array<{
    scope: "GLOBAL" | "DEPARTMENT" | "ITEM";
    departmentKey?: string;
    itemId?: string;
    key: string;
    value: number;
  }>;
}) => putInventoryData<InventoryAdminThresholds, typeof payload>("/admin-review/thresholds", payload);

export const generateInventoryAdminReportApi = async (payload: {
  reportType: "WEEK" | "MONTH";
  periodStart?: string;
  periodEnd?: string;
}) => postInventoryData<InventoryAdminReportSnapshot, typeof payload>("/admin-review/reports", payload);

export const listInventoryAdminReportsApi = async () =>
  getInventoryData<InventoryAdminReportList>("/admin-review/reports");

export const getInventoryAdminReportApi = async (id: string) =>
  getInventoryData<InventoryAdminReportSnapshot>(`/admin-review/reports/${encodeURIComponent(id)}`);

export const downloadInventoryAdminReportApi = async (id: string, format: "xlsx" | "csv" | "pdf") =>
  downloadInventoryFile(
    `/admin-review/reports/${encodeURIComponent(id)}/export/${format}`,
    `admin-review-report-${id}.${format}`
  );

export const getInventoryPatientDashboardApi = async (date?: string) =>
  getInventoryData<{
    businessDate: string;
    cutoverDate?: string;
    summary: {
      totalPatients: number;
      submittedDocuments: number;
      approvedDocuments: number;
      returnedDocuments: number;
      draftDocuments: number;
      lineCount: number;
      approvedLines: number;
      rejectedLines: number;
      pendingLines: number;
    };
    byDepartment: Array<{
      departmentKey: string;
      departmentName: string;
      patientCount: number;
      lineCount: number;
      approvedLines: number;
    }>;
    topItems: Array<{ itemId: string; itemName: string; unit: string; quantity: number }>;
  }>("/patient-dashboard", { date });

export const getInventoryRuntimeSettingsApi = async () =>
  getInventoryData<{ patientDetailCutoverDate: string }>("/runtime-settings");

export const saveInventoryCutoverDateApi = async (date: string) =>
  putInventoryData<{ patientDetailCutoverDate: string }, { date: string }>("/runtime-settings/cutover-date", { date });

export const getInventoryPatientReviewBatchesApi = async (params: {
  date?: string;
  departmentKey?: string;
  status?: string;
}) => getInventoryData<InventoryPatientReviewBatchList>("/patient-review/batches", params);

export const getInventoryPatientReviewBatchApi = async (batchId: string) =>
  getInventoryData<InventoryPatientReviewDetail>(`/patient-review/batches/${encodeURIComponent(batchId)}`);

export const decideInventoryPatientReviewApi = async (
  batchId: string,
  payload: { decisions: Array<{ lineId: string; decision: "APPROVED" | "REJECTED"; note?: string }> }
) =>
  postInventoryData<InventoryPatientReviewDetail, typeof payload>(
    `/patient-review/batches/${encodeURIComponent(batchId)}/decisions`,
    payload
  );

export const retryInventoryReviewDeductionsApi = async (batchId: string) =>
  postInventoryData<InventoryPatientReviewDetail, Record<string, never>>(
    `/patient-review/batches/${encodeURIComponent(batchId)}/retry-deductions`,
    {}
  );

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

export const downloadInventoryQuotaGovernanceXlsxApi = async (params: { date?: string; versionId?: string }) =>
  downloadInventoryFile(
    `/quota-governance/export.xlsx${buildInventoryQuery(params)}`,
    `全院耗材每人次定额总表-${params.date || "当前版本"}.xlsx`
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

export interface InventoryInboundOrder {
  id: string;
  orderNo: string;
  supplier: string;
  purchaseDate: string;
  supplierDocumentNo?: string;
  status: "ACTIVE" | "REVERSED";
  note?: string;
  createdBy: string;
  createdAt: string;
  lineCount?: number;
  totalQuantity?: number;
  reversedBy?: string;
  reverseReason?: string;
  reversedAt?: string;
  lines?: Array<{
    id: string;
    itemId: string;
    itemName: string;
    unit: string;
    batchId: string;
    batchNo: string;
    expiryDate: string;
    issueQuantity: number;
    conversionFactor: number;
    quantity: number;
    remark?: string;
  }>;
}

export const getInventoryInboundOrdersApi = async (params: { from?: string; to?: string; supplier?: string; status?: string } = {}) =>
  getInventoryData<{ from: string; to: string; list: InventoryInboundOrder[] }>("/warehouse/inbound-orders", params);

export const getInventoryInboundOrderApi = async (orderId: string) =>
  getInventoryData<InventoryInboundOrder>(`/warehouse/inbound-orders/${encodeURIComponent(orderId)}`);

export const createInventoryInboundOrderApi = async (payload: Record<string, unknown>) =>
  postInventoryData<InventoryInboundOrder, Record<string, unknown>>("/warehouse/inbound-orders", payload);

export const reverseInventoryInboundOrderApi = async (orderId: string, reason: string) =>
  postInventoryData<InventoryInboundOrder, { reason: string }>(
    `/warehouse/inbound-orders/${encodeURIComponent(orderId)}/reverse`,
    { reason }
  );

export const getInventoryInboundSuppliersApi = async () =>
  getInventoryData<string[]>("/warehouse/suppliers");

export const getInventoryWarehouseStockApi = async (params: { keyword?: string; category?: string; filter?: string } = {}) =>
  getInventoryData<{ items: any[]; batches: any[]; today: string }>("/warehouse/stock", params);

export const getInventoryWarehouseAlertsApi = async () =>
  getInventoryData<{ centralLow: any[]; departmentLow: any[]; expiring: any[] }>("/warehouse/alerts");

export const getInventoryDepartmentStockApi = async (departmentId?: string) =>
  getInventoryData<{ departmentId: string; departmentName: string; month: string; items: any[] }>(
    "/department-stock",
    departmentId ? { departmentId } : undefined
  );

export const fulfillInventoryRequestApi = async (payload: { id: string; lines?: Array<{ id: string; issuedQuantity: number }> }) =>
  postInventoryData<Record<string, unknown>, typeof payload>("/requests/fulfill", payload);

const uploadInventoryFile = async <T>(path: string, file: File): Promise<T> => {
  const form = new FormData();
  form.append("file", file);
  const result = await fetch(`${INVENTORY_API_BASE_URL}${path}`, {
    method: "POST",
    headers: authHeaders(),
    body: form
  });
  return parseInventoryDataResponse<T>(result);
};

export const previewInventoryOpeningImportApi = async (file: File) =>
  uploadInventoryFile<{ rowCount: number; rows: any[]; errors: Array<{ row: number; name: string; message: string }> }>(
    "/warehouse/opening-import/preview",
    file
  );

export const commitInventoryOpeningImportApi = async (file: File) =>
  uploadInventoryFile<{ rows: number; created: number; updated: number }>("/warehouse/opening-import/commit", file);

export const downloadInventoryOpeningTemplateApi = async () =>
  downloadInventoryFile("/warehouse/opening-template", "期初库存导入模板.xlsx");
