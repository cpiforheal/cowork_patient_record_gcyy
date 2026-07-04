import { computed, type ComputedRef } from "vue";
import type { PatientLifecycleStage, RecordAttachment, RecordField, RecordSection, UserRole } from "@/config/fieldPermissions";
import type { FieldIssue } from "../components/types";
import { getArchiveFieldResponsibility, type ArchiveFieldCategory } from "./fieldResponsibilities";

type SectionCompletion = {
  completed: number;
  requiredMissing: number;
  evidenceCount: number;
  total: number;
};

export type WorkflowStageStatus = "done" | "active" | "attention" | "waiting";

export type WorkflowTaskRelation = "owner" | "support" | "review" | "readonly";

export type WorkflowFieldTask = {
  fieldKey: string;
  fieldLabel: string;
  sectionKey: string;
  sectionTitle: string;
  stageKey: string;
  stageTitle: string;
  department: string;
  required: boolean;
  editable: boolean;
  complete: boolean;
  attachmentCount: number;
  relation: WorkflowTaskRelation;
  relationLabel: string;
  category: ArchiveFieldCategory;
  categoryLabel: string;
  critical: boolean;
  archiveRequired: boolean;
  requiresAttachment: boolean;
  primaryOwner: string;
  issueMessage: string;
  issueLevel?: FieldIssue["level"];
};

export type WorkflowStageNode = {
  key: string;
  title: string;
  shortTitle: string;
  owner: string;
  department: string;
  status: WorkflowStageStatus;
  completed: number;
  total: number;
  missing: number;
  evidenceCount: number;
};

export type WorkflowAttachmentTask = {
  fieldKey: string;
  fieldLabel: string;
  sectionKey: string;
  sectionTitle: string;
  attachmentCount: number;
  required: boolean;
  editable: boolean;
};

export type PatientWorkflowTaskState = {
  currentStage: WorkflowStageNode;
  nextStage?: WorkflowStageNode;
  stageNodes: WorkflowStageNode[];
  primaryTasks: WorkflowFieldTask[];
  collaborationTasks: WorkflowFieldTask[];
  reviewTasks: WorkflowFieldTask[];
  readonlyTasks: WorkflowFieldTask[];
  ownerTasks: WorkflowFieldTask[];
  supportTasks: WorkflowFieldTask[];
  blockingItems: WorkflowFieldTask[];
  attachmentTasks: WorkflowAttachmentTask[];
  qualitySummary: {
    blockingCount: number;
    missingCount: number;
    invalidCount: number;
    attachmentMissingCount: number;
    reviewCount: number;
    criticalOpenCount: number;
  };
  archiveReadiness: {
    completed: number;
    total: number;
    missing: number;
    percent: number;
    ready: boolean;
  };
  nextStepAdvice: string;
};

const emptyStageNode: WorkflowStageNode = {
  key: "empty",
  title: "流程待识别",
  shortTitle: "待识别",
  owner: "当前岗位",
  department: "院内协作",
  status: "waiting",
  completed: 0,
  total: 0,
  missing: 0,
  evidenceCount: 0
};

const categoryLabels: Record<ArchiveFieldCategory, string> = {
  fact: "事实",
  judgement: "判断",
  process: "流程",
  quality: "质控"
};

const relationLabels: Record<WorkflowTaskRelation, string> = {
  owner: "我负责填写",
  support: "我可以补充",
  review: "我需要复核",
  readonly: "我只能查看"
};

const roleLabels: Partial<Record<UserRole, string>> = {
  admin: "管理员",
  frontdesk: "前台",
  reception: "接诊",
  lab: "化验室",
  ecg: "心电室",
  ultrasound: "B超/放射",
  inspection: "检查室",
  doctor: "医生",
  nurse: "护士",
  nursing: "护理部",
  manager: "管理",
  quality: "质控"
};

const issueKey = (issue: FieldIssue) => issue.fieldKey;

const rolesInclude = (roles: UserRole[] | undefined, role: UserRole) => Boolean(roles?.includes(role));

const roleNames = (roles: UserRole[] = []) =>
  roles
    .map(role => roleLabels[role])
    .filter(Boolean)
    .join("、");

const rankTask = (task: WorkflowFieldTask) =>
  (task.issueLevel === "invalid" ? 0 : task.issueLevel === "missing" ? 1 : 2) +
  (task.critical ? 0 : 1) +
  (task.required || task.archiveRequired ? 0 : 1);

export const usePatientWorkflowTasks = ({
  sections,
  lifecycleStages,
  activeLifecycleStage,
  currentRole,
  roleName,
  fieldIssues,
  completionBySection,
  attachmentsByField,
  isFieldComplete,
  isFieldEditable
}: {
  sections: ComputedRef<RecordSection[]>;
  lifecycleStages: ComputedRef<PatientLifecycleStage[]>;
  activeLifecycleStage: ComputedRef<PatientLifecycleStage>;
  currentRole: ComputedRef<UserRole>;
  roleName: ComputedRef<string>;
  fieldIssues: ComputedRef<FieldIssue[]>;
  completionBySection: ComputedRef<Record<string, SectionCompletion>>;
  attachmentsByField: ComputedRef<Record<string, RecordAttachment[]>>;
  isFieldComplete: (field: RecordField) => boolean;
  isFieldEditable: (field: RecordField) => boolean;
}): ComputedRef<PatientWorkflowTaskState> => {
  return computed(() => {
    const sectionsByKey = sections.value.reduce<Record<string, RecordSection>>((index, section) => {
      index[section.key] = section;
      return index;
    }, {});

    const issuesByField = fieldIssues.value.reduce<Record<string, FieldIssue>>((index, issue) => {
      index[issueKey(issue)] = issue;
      return index;
    }, {});

    const stageForSection = lifecycleStages.value.reduce<Record<string, PatientLifecycleStage>>((index, stage) => {
      stage.sectionKeys.forEach(sectionKey => {
        index[sectionKey] = stage;
      });
      return index;
    }, {});

    const stagesByKey = lifecycleStages.value.reduce<Record<string, PatientLifecycleStage>>((index, stage) => {
      index[stage.key] = stage;
      return index;
    }, {});

    const stageNodes = lifecycleStages.value.map<WorkflowStageNode>(stage => {
      const stageSections = stage.sectionKeys.map(sectionKey => sectionsByKey[sectionKey]).filter(Boolean);
      const stats = stageSections.reduce(
        (result, section) => {
          const completion = completionBySection.value[section.key];
          if (!completion) return result;
          result.completed += completion.completed;
          result.total += completion.total;
          result.missing += completion.requiredMissing;
          result.evidenceCount += completion.evidenceCount;
          return result;
        },
        { completed: 0, total: 0, missing: 0, evidenceCount: 0 }
      );

      const isActive = stage.key === activeLifecycleStage.value.key;
      const isDone = stats.total > 0 && stats.missing === 0 && stats.completed === stats.total;

      return {
        key: stage.key,
        title: stage.title,
        shortTitle: stage.shortTitle,
        owner: stage.owner,
        department: stage.department,
        status: isActive ? (stats.missing ? "attention" : "active") : isDone ? "done" : "waiting",
        ...stats
      };
    });

    const currentStage =
      stageNodes.find(stage => stage.key === activeLifecycleStage.value.key) || stageNodes[0] || emptyStageNode;
    const currentStageIndex = stageNodes.findIndex(stage => stage.key === currentStage.key);
    const nextStage = stageNodes[currentStageIndex + 1];
    const currentStageSectionKeys = new Set(activeLifecycleStage.value.sectionKeys);
    const currentStageSections = sections.value.filter(section => currentStageSectionKeys.has(section.key));
    const taskSections = currentStageSections.length ? currentStageSections : sections.value;

    const fieldTasks = taskSections.flatMap(section =>
      section.fields.map<WorkflowFieldTask>(field => {
        const responsibility = getArchiveFieldResponsibility(field.key);
        const stage = responsibility
          ? stagesByKey[responsibility.stageKey] || activeLifecycleStage.value
          : stageForSection[section.key];
        const issue = issuesByField[field.key];
        const canEdit = isFieldEditable(field);
        const relation: WorkflowTaskRelation = responsibility
          ? rolesInclude(responsibility.primaryRoles, currentRole.value)
            ? "owner"
            : rolesInclude(responsibility.collaboratorRoles, currentRole.value)
              ? "support"
              : rolesInclude(responsibility.reviewerRoles, currentRole.value) || ["admin", "quality"].includes(currentRole.value)
                ? "review"
                : "readonly"
          : ["admin", "quality"].includes(currentRole.value)
            ? "review"
            : canEdit
              ? "owner"
              : "readonly";
        const category = responsibility?.category || (field.qualityCheck ? "quality" : "fact");

        return {
          fieldKey: field.key,
          fieldLabel: field.label,
          sectionKey: section.key,
          sectionTitle: section.title,
          stageKey: stage?.key || activeLifecycleStage.value.key,
          stageTitle: stage?.title || activeLifecycleStage.value.title,
          department: section.department || stage?.department || activeLifecycleStage.value.department,
          required: Boolean(field.required),
          editable: canEdit,
          complete: isFieldComplete(field),
          attachmentCount: attachmentsByField.value[field.key]?.length || 0,
          relation,
          relationLabel: relationLabels[relation],
          category,
          categoryLabel: categoryLabels[category],
          critical: Boolean(responsibility?.critical),
          archiveRequired: Boolean(responsibility?.archiveRequired || field.required),
          requiresAttachment: Boolean(responsibility?.requiresAttachment || field.kind === "attachment" || field.evidence),
          primaryOwner:
            roleNames(responsibility?.primaryRoles) || section.owner || stage?.owner || activeLifecycleStage.value.owner,
          issueMessage: issue?.message || "",
          issueLevel: issue?.level
        };
      })
    );

    const pendingTasks = fieldTasks.filter(
      task => !task.complete || Boolean(task.issueMessage) || (task.requiresAttachment && task.attachmentCount === 0)
    );

    const ownerTasks = pendingTasks
      .filter(task => task.relation === "owner")
      .sort((left, right) => rankTask(left) - rankTask(right))
      .slice(0, 8);

    const supportTasks = pendingTasks
      .filter(task => task.relation === "support")
      .sort((left, right) => rankTask(left) - rankTask(right))
      .slice(0, 6);

    const reviewTasks = pendingTasks
      .filter(task => task.relation === "review" || task.critical)
      .sort((left, right) => rankTask(left) - rankTask(right))
      .slice(0, 8);

    const readonlyTasks = fieldTasks
      .filter(task => task.relation === "readonly")
      .sort((left, right) => Number(left.complete) - Number(right.complete))
      .slice(0, 6);

    const blockingItems = fieldIssues.value
      .map(issue => {
        const section = sectionsByKey[issue.sectionKey];
        const field = section?.fields.find(item => item.key === issue.fieldKey);
        const task = fieldTasks.find(item => item.fieldKey === issue.fieldKey);
        const stage = section ? stageForSection[section.key] || activeLifecycleStage.value : activeLifecycleStage.value;

        return {
          fieldKey: issue.fieldKey,
          fieldLabel: issue.fieldLabel,
          sectionKey: issue.sectionKey,
          sectionTitle: issue.sectionTitle,
          stageKey: task?.stageKey || stage.key,
          stageTitle: task?.stageTitle || stage.title,
          department: section?.department || stage.department,
          required: Boolean(field?.required),
          editable: field ? isFieldEditable(field) : currentRole.value === "admin",
          complete: field ? isFieldComplete(field) : false,
          attachmentCount: attachmentsByField.value[issue.fieldKey]?.length || 0,
          relation: task?.relation || "review",
          relationLabel: task?.relationLabel || relationLabels.review,
          category: task?.category || "quality",
          categoryLabel: task?.categoryLabel || categoryLabels.quality,
          critical: Boolean(task?.critical),
          archiveRequired: Boolean(task?.archiveRequired),
          requiresAttachment: Boolean(task?.requiresAttachment),
          primaryOwner: task?.primaryOwner || section?.owner || stage.owner,
          issueMessage: issue.message,
          issueLevel: issue.level
        };
      })
      .sort((left, right) => rankTask(left) - rankTask(right))
      .slice(0, 8);

    const attachmentTasks = taskSections
      .flatMap(section =>
        section.fields
          .filter(field => {
            const responsibility = getArchiveFieldResponsibility(field.key);
            return field.kind === "attachment" || field.evidence || responsibility?.requiresAttachment;
          })
          .map<WorkflowAttachmentTask>(field => ({
            fieldKey: field.key,
            fieldLabel: field.label,
            sectionKey: section.key,
            sectionTitle: section.title,
            attachmentCount: attachmentsByField.value[field.key]?.length || 0,
            required: Boolean(field.required || field.evidence || getArchiveFieldResponsibility(field.key)?.requiresAttachment),
            editable: isFieldEditable(field)
          }))
      )
      .slice(0, 6);

    const attachmentMissingCount = attachmentTasks.filter(task => task.required && task.attachmentCount === 0).length;

    const qualitySummary = {
      blockingCount: blockingItems.length,
      missingCount: fieldIssues.value.filter(issue => issue.level === "missing").length,
      invalidCount: fieldIssues.value.filter(issue => issue.level === "invalid").length,
      attachmentMissingCount,
      reviewCount: reviewTasks.length,
      criticalOpenCount: pendingTasks.filter(task => task.critical).length
    };

    const archiveReadiness = Object.values(completionBySection.value).reduce(
      (result, section) => {
        result.completed += section.completed;
        result.total += section.total;
        result.missing += section.requiredMissing;
        return result;
      },
      { completed: 0, total: 0, missing: 0, percent: 0, ready: false }
    );

    archiveReadiness.percent = archiveReadiness.total
      ? Math.round((archiveReadiness.completed / archiveReadiness.total) * 100)
      : 0;
    archiveReadiness.ready = archiveReadiness.missing === 0 && blockingItems.length === 0 && archiveReadiness.total > 0;

    const nextStepAdvice = ownerTasks.length
      ? `${roleName.value}先处理 ${ownerTasks[0].sectionTitle}：${ownerTasks[0].fieldLabel}`
      : supportTasks.length
        ? `${roleName.value}可补充 ${supportTasks[0].sectionTitle}：${supportTasks[0].fieldLabel}`
        : reviewTasks.length
          ? `${roleName.value}重点复核 ${reviewTasks[0].sectionTitle}：${reviewTasks[0].fieldLabel}`
          : blockingItems.length
            ? `当前岗位已处理，等待 ${blockingItems[0].primaryOwner || blockingItems[0].department} 补齐 ${blockingItems[0].fieldLabel}`
            : archiveReadiness.ready
              ? "字段和必填项已就绪，可继续预览、打印或提交质控归档"
              : `继续推进 ${currentStage.title}，完成后流转到 ${nextStage?.owner || currentStage.owner}`;

    return {
      currentStage,
      nextStage,
      stageNodes,
      primaryTasks: ownerTasks,
      collaborationTasks: supportTasks,
      reviewTasks,
      readonlyTasks,
      ownerTasks,
      supportTasks,
      blockingItems,
      attachmentTasks,
      qualitySummary,
      archiveReadiness,
      nextStepAdvice
    };
  });
};
