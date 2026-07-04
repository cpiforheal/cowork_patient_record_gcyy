import type { UserRole } from "@/config/fieldPermissions";

export type ArchiveFieldCategory = "fact" | "judgement" | "process" | "quality";

export type ArchiveFieldResponsibility = {
  stageKey: string;
  category: ArchiveFieldCategory;
  firstFillRoles: UserRole[];
  primaryRoles: UserRole[];
  collaboratorRoles: UserRole[];
  reviewerRoles: UserRole[];
  viewerRoles: UserRole[];
  critical?: boolean;
  archiveRequired?: boolean;
  requiresAttachment?: boolean;
};

export const archiveFieldResponsibilities: Record<string, ArchiveFieldResponsibility> = {
  patientName: {
    stageKey: "registration",
    category: "fact",
    firstFillRoles: ["frontdesk"],
    primaryRoles: ["frontdesk"],
    collaboratorRoles: ["reception"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["doctor", "nurse", "nursing"],
    critical: true,
    archiveRequired: true
  },
  visitNo: {
    stageKey: "registration",
    category: "fact",
    firstFillRoles: ["frontdesk"],
    primaryRoles: ["frontdesk"],
    collaboratorRoles: ["reception"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["doctor", "nurse", "nursing"],
    critical: true,
    archiveRequired: true
  },
  phone: {
    stageKey: "registration",
    category: "fact",
    firstFillRoles: ["frontdesk"],
    primaryRoles: ["frontdesk"],
    collaboratorRoles: ["reception"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["doctor", "nurse"],
    archiveRequired: true
  },
  admissionDate: {
    stageKey: "registration",
    category: "process",
    firstFillRoles: ["frontdesk"],
    primaryRoles: ["frontdesk"],
    collaboratorRoles: ["nursing"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["doctor", "nurse"],
    critical: true
  },
  admissionWay: {
    stageKey: "registration",
    category: "process",
    firstFillRoles: ["frontdesk"],
    primaryRoles: ["frontdesk"],
    collaboratorRoles: ["reception"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["doctor", "nurse", "nursing"],
    critical: true
  },
  treatmentType: {
    stageKey: "registration",
    category: "process",
    firstFillRoles: ["frontdesk"],
    primaryRoles: ["frontdesk"],
    collaboratorRoles: ["reception", "doctor"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["nurse", "nursing"],
    critical: true
  },
  arrivalPath: {
    stageKey: "registration",
    category: "fact",
    firstFillRoles: ["frontdesk"],
    primaryRoles: ["frontdesk"],
    collaboratorRoles: ["reception"],
    reviewerRoles: ["admin"],
    viewerRoles: ["doctor", "quality"]
  },
  sourceChannel: {
    stageKey: "registration",
    category: "fact",
    firstFillRoles: ["frontdesk"],
    primaryRoles: ["frontdesk"],
    collaboratorRoles: ["reception"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["doctor"]
  },
  visitMotivation: {
    stageKey: "registration",
    category: "judgement",
    firstFillRoles: ["frontdesk"],
    primaryRoles: ["frontdesk"],
    collaboratorRoles: ["reception", "doctor"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["nurse", "nursing"],
    critical: true
  },
  treatmentIntent: {
    stageKey: "registration",
    category: "process",
    firstFillRoles: ["frontdesk"],
    primaryRoles: ["frontdesk"],
    collaboratorRoles: ["reception", "doctor"],
    reviewerRoles: ["admin"],
    viewerRoles: ["quality"]
  },
  specialRequirements: {
    stageKey: "reception",
    category: "judgement",
    firstFillRoles: ["frontdesk"],
    primaryRoles: ["frontdesk", "doctor"],
    collaboratorRoles: ["reception"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["nurse", "nursing"],
    critical: true
  },
  chiefComplaintText: {
    stageKey: "reception",
    category: "judgement",
    firstFillRoles: ["reception"],
    primaryRoles: ["reception", "doctor"],
    collaboratorRoles: ["frontdesk"],
    reviewerRoles: ["doctor", "admin", "quality"],
    viewerRoles: ["inspection", "nurse"],
    critical: true,
    archiveRequired: true
  },
  onset: {
    stageKey: "reception",
    category: "fact",
    firstFillRoles: ["reception"],
    primaryRoles: ["reception"],
    collaboratorRoles: ["doctor"],
    reviewerRoles: ["doctor", "admin"],
    viewerRoles: ["inspection", "quality"]
  },
  primaryConcern: {
    stageKey: "reception",
    category: "judgement",
    firstFillRoles: ["frontdesk", "reception"],
    primaryRoles: ["reception"],
    collaboratorRoles: ["doctor", "frontdesk"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["nurse", "nursing"]
  },
  inspectionImages: {
    stageKey: "inspection",
    category: "fact",
    firstFillRoles: ["inspection"],
    primaryRoles: ["inspection"],
    collaboratorRoles: ["doctor"],
    reviewerRoles: ["doctor", "admin", "quality"],
    viewerRoles: ["reception", "nurse"],
    critical: true,
    requiresAttachment: true
  },
  inspectionBriefNote: {
    stageKey: "inspection",
    category: "fact",
    firstFillRoles: ["inspection"],
    primaryRoles: ["inspection"],
    collaboratorRoles: ["doctor"],
    reviewerRoles: ["doctor", "admin", "quality"],
    viewerRoles: ["reception", "nurse"],
    critical: true
  },
  bloodRoutineStatus: {
    stageKey: "screening",
    category: "process",
    firstFillRoles: ["lab"],
    primaryRoles: ["lab"],
    collaboratorRoles: ["doctor", "nurse"],
    reviewerRoles: ["doctor", "admin", "quality"],
    viewerRoles: ["inspection", "nursing"],
    critical: true,
    requiresAttachment: true
  },
  coagulationStatus: {
    stageKey: "screening",
    category: "process",
    firstFillRoles: ["lab"],
    primaryRoles: ["lab"],
    collaboratorRoles: ["doctor", "nurse"],
    reviewerRoles: ["doctor", "admin", "quality"],
    viewerRoles: ["inspection", "nursing"],
    critical: true,
    requiresAttachment: true
  },
  preOpEightStatus: {
    stageKey: "screening",
    category: "process",
    firstFillRoles: ["lab"],
    primaryRoles: ["lab"],
    collaboratorRoles: ["doctor", "nurse"],
    reviewerRoles: ["doctor", "admin", "quality"],
    viewerRoles: ["inspection", "nursing"],
    critical: true,
    requiresAttachment: true
  },
  ecgStatus: {
    stageKey: "screening",
    category: "process",
    firstFillRoles: ["ecg"],
    primaryRoles: ["ecg"],
    collaboratorRoles: ["doctor"],
    reviewerRoles: ["doctor", "admin", "quality"],
    viewerRoles: ["nurse", "nursing"],
    critical: true,
    requiresAttachment: true
  },
  liverFunctionStatus: {
    stageKey: "screening",
    category: "process",
    firstFillRoles: ["lab"],
    primaryRoles: ["lab"],
    collaboratorRoles: ["doctor", "nurse"],
    reviewerRoles: ["doctor", "admin", "quality"],
    viewerRoles: ["inspection", "nursing"],
    critical: true,
    requiresAttachment: true
  },
  renalFunctionStatus: {
    stageKey: "screening",
    category: "process",
    firstFillRoles: ["lab"],
    primaryRoles: ["lab"],
    collaboratorRoles: ["doctor", "nurse"],
    reviewerRoles: ["doctor", "admin", "quality"],
    viewerRoles: ["inspection", "nursing"],
    critical: true,
    requiresAttachment: true
  },
  fastingGlucoseStatus: {
    stageKey: "screening",
    category: "process",
    firstFillRoles: ["lab"],
    primaryRoles: ["lab"],
    collaboratorRoles: ["doctor", "nurse"],
    reviewerRoles: ["doctor", "admin", "quality"],
    viewerRoles: ["inspection", "nursing"],
    critical: true,
    requiresAttachment: true
  },
  bloodLipidStatus: {
    stageKey: "screening",
    category: "process",
    firstFillRoles: ["lab"],
    primaryRoles: ["lab"],
    collaboratorRoles: ["doctor", "nurse"],
    reviewerRoles: ["doctor", "admin", "quality"],
    viewerRoles: ["inspection", "nursing"],
    critical: true,
    requiresAttachment: true
  },
  urineRoutineStatus: {
    stageKey: "screening",
    category: "process",
    firstFillRoles: ["lab"],
    primaryRoles: ["lab"],
    collaboratorRoles: ["doctor", "nurse"],
    reviewerRoles: ["doctor", "admin", "quality"],
    viewerRoles: ["inspection", "nursing"],
    critical: true,
    requiresAttachment: true
  },
  tcmDiagnosis: {
    stageKey: "decision",
    category: "judgement",
    firstFillRoles: ["doctor"],
    primaryRoles: ["doctor"],
    collaboratorRoles: ["reception"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["frontdesk", "nurse", "nursing"],
    critical: true,
    archiveRequired: true
  },
  westernDiagnosis: {
    stageKey: "decision",
    category: "judgement",
    firstFillRoles: ["doctor"],
    primaryRoles: ["doctor"],
    collaboratorRoles: ["reception"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["frontdesk", "nurse", "nursing"],
    critical: true,
    archiveRequired: true
  },
  surgeryFeasibility: {
    stageKey: "decision",
    category: "judgement",
    firstFillRoles: ["doctor"],
    primaryRoles: ["doctor"],
    collaboratorRoles: ["reception", "nurse"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["frontdesk", "nursing"],
    critical: true
  },
  sameDayTreatment: {
    stageKey: "decision",
    category: "process",
    firstFillRoles: ["doctor"],
    primaryRoles: ["doctor"],
    collaboratorRoles: ["frontdesk", "nurse"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["reception", "nursing"],
    critical: true
  },
  operationName: {
    stageKey: "operationRecord",
    category: "judgement",
    firstFillRoles: ["doctor"],
    primaryRoles: ["doctor"],
    collaboratorRoles: ["nurse"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["frontdesk", "nursing"],
    critical: true,
    archiveRequired: true
  },
  admissionAssessment: {
    stageKey: "nursingPrep",
    category: "fact",
    firstFillRoles: ["nursing"],
    primaryRoles: ["nursing"],
    collaboratorRoles: ["nurse", "doctor"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["frontdesk", "reception"],
    critical: true
  },
  nursingObservation: {
    stageKey: "nursingPrep",
    category: "process",
    firstFillRoles: ["nursing"],
    primaryRoles: ["nursing"],
    collaboratorRoles: ["nurse", "doctor"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["frontdesk", "reception"]
  },
  followupRecordsJson: {
    stageKey: "followupClosure",
    category: "process",
    firstFillRoles: ["nurse", "doctor"],
    primaryRoles: ["nurse", "doctor"],
    collaboratorRoles: ["frontdesk", "nursing"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["reception"],
    critical: true
  },
  nextFollowupAt: {
    stageKey: "followupClosure",
    category: "process",
    firstFillRoles: ["nurse", "doctor"],
    primaryRoles: ["nurse", "doctor"],
    collaboratorRoles: ["frontdesk", "nursing"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["reception"],
    critical: true
  },
  recoverySummary: {
    stageKey: "followupClosure",
    category: "judgement",
    firstFillRoles: ["doctor", "nurse"],
    primaryRoles: ["doctor"],
    collaboratorRoles: ["nurse", "nursing"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["frontdesk", "reception"],
    critical: true
  },
  patientPainLevel: {
    stageKey: "followupClosure",
    category: "fact",
    firstFillRoles: ["nurse", "frontdesk"],
    primaryRoles: ["nurse", "doctor"],
    collaboratorRoles: ["frontdesk"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["reception", "nursing"],
    critical: true
  },
  patientSatisfaction: {
    stageKey: "followupClosure",
    category: "quality",
    firstFillRoles: ["frontdesk", "nurse"],
    primaryRoles: ["frontdesk", "nurse"],
    collaboratorRoles: ["doctor"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["reception", "nursing"],
    critical: true
  },
  archiveClosedSignature: {
    stageKey: "qualityArchive",
    category: "quality",
    firstFillRoles: ["quality"],
    primaryRoles: ["quality"],
    collaboratorRoles: ["doctor", "nurse"],
    reviewerRoles: ["admin", "doctor"],
    viewerRoles: ["frontdesk", "reception", "nursing"],
    critical: true,
    archiveRequired: true
  },
  dipGroup: {
    stageKey: "qualityArchive",
    category: "quality",
    firstFillRoles: ["quality"],
    primaryRoles: ["quality"],
    collaboratorRoles: ["doctor", "admin"],
    reviewerRoles: ["admin", "doctor"],
    viewerRoles: ["frontdesk", "nurse", "nursing"],
    critical: true
  },
  dipCompliance: {
    stageKey: "qualityArchive",
    category: "quality",
    firstFillRoles: ["quality"],
    primaryRoles: ["quality"],
    collaboratorRoles: ["doctor", "admin"],
    reviewerRoles: ["admin", "doctor"],
    viewerRoles: ["frontdesk", "nurse", "nursing"],
    critical: true
  },
  courseSchedule: {
    stageKey: "operationRecord",
    category: "process",
    firstFillRoles: ["doctor"],
    primaryRoles: ["doctor"],
    collaboratorRoles: ["quality", "admin"],
    reviewerRoles: ["admin", "quality"],
    viewerRoles: ["frontdesk", "nurse", "nursing"],
    critical: true
  },
  generatedDocuments: {
    stageKey: "qualityArchive",
    category: "quality",
    firstFillRoles: ["quality"],
    primaryRoles: ["quality"],
    collaboratorRoles: ["doctor", "admin"],
    reviewerRoles: ["admin", "doctor"],
    viewerRoles: ["frontdesk", "nurse", "nursing"],
    critical: true,
    archiveRequired: true
  },
  documentStandard: {
    stageKey: "qualityArchive",
    category: "quality",
    firstFillRoles: ["quality"],
    primaryRoles: ["quality"],
    collaboratorRoles: ["doctor", "admin"],
    reviewerRoles: ["admin", "doctor"],
    viewerRoles: ["frontdesk", "nurse", "nursing"],
    archiveRequired: true
  },
  qualityItems: {
    stageKey: "qualityArchive",
    category: "quality",
    firstFillRoles: ["quality"],
    primaryRoles: ["quality"],
    collaboratorRoles: ["doctor", "admin"],
    reviewerRoles: ["admin", "doctor"],
    viewerRoles: ["frontdesk", "nurse", "nursing"],
    critical: true,
    archiveRequired: true
  },
  qualityReview: {
    stageKey: "qualityArchive",
    category: "quality",
    firstFillRoles: ["quality"],
    primaryRoles: ["quality"],
    collaboratorRoles: ["doctor", "admin"],
    reviewerRoles: ["admin", "doctor"],
    viewerRoles: ["frontdesk", "nurse", "nursing"],
    critical: true,
    archiveRequired: true
  }
};

export const getArchiveFieldResponsibility = (fieldKey: string) => archiveFieldResponsibilities[fieldKey];
