export type PatientRiskTone = "success" | "warning" | "info" | "danger";

export interface PatientStatusLike {
  status?: string;
  riskType?: PatientRiskTone | "";
  currentStage?: string;
  progressPercent?: number;
}

export interface PatientStatusFlags {
  isLegacyArchived: boolean;
  isPendingArchive: boolean;
  isPending: boolean;
  isReturned: boolean;
  isReviewPending: boolean;
  isAttachmentTodo: boolean;
  isRegistrationTodo: boolean;
  riskTone: PatientRiskTone;
  statusTagType: PatientRiskTone;
}

export const classifyPatientStatus = (patient: PatientStatusLike): PatientStatusFlags => {
  const status = patient.status || "";
  const stage = patient.currentStage || "";
  const progress = patient.progressPercent || 0;
  const riskType = patient.riskType || "info";

  const isLegacyArchived = status === "旧资料已归档";
  const isPendingArchive = status === "待归档";
  const isReturned = status.includes("退回") || riskType === "danger";
  const isReviewPending = status.includes("审核") || isPendingArchive;
  const isAttachmentTodo = stage.includes("检查") || stage.includes("附件") || stage.includes("影像");
  const isRegistrationTodo = stage.includes("前台") || stage.includes("建档") || progress < 25;

  let riskTone: PatientRiskTone = "info";
  if (riskType === "danger" || status.includes("退回") || status.includes("待质控") || status.includes("待档案审核")) {
    riskTone = "danger";
  } else if (riskType === "warning" || progress < 100) {
    riskTone = "warning";
  } else if (riskType === "success" || progress >= 100) {
    riskTone = "success";
  }

  let statusTagType: PatientRiskTone = "info";
  if (status.includes("归档") || status.includes("完整") || status.includes("上传")) {
    statusTagType = "success";
  } else if (status.includes("待") || status.includes("补")) {
    statusTagType = "warning";
  } else if (status.includes("作废")) {
    statusTagType = "danger";
  }

  return {
    isLegacyArchived,
    isPendingArchive,
    isPending: !isLegacyArchived && !isPendingArchive,
    isReturned,
    isReviewPending,
    isAttachmentTodo,
    isRegistrationTodo,
    riskTone,
    statusTagType
  };
};
