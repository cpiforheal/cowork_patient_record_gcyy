import { computed, type ComputedRef } from "vue";
import type { PatientLifecycleStage, RecordAttachment, RecordField, RecordSection, UserRole } from "@/config/fieldPermissions";
import type { FieldIssue } from "../components/types";

type SectionCompletion = {
  completed: number;
  requiredMissing: number;
  evidenceCount: number;
  total: number;
};

export type WorkflowStageStatus = "done" | "active" | "attention" | "waiting";

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
  blockingItems: WorkflowFieldTask[];
  attachmentTasks: WorkflowAttachmentTask[];
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

const issueKey = (issue: FieldIssue) => issue.fieldKey;

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

    const fieldTasks = taskSections.flatMap(section => {
      const stage = stageForSection[section.key] || activeLifecycleStage.value;

      return section.fields.map<WorkflowFieldTask>(field => {
        const issue = issuesByField[field.key];

        return {
          fieldKey: field.key,
          fieldLabel: field.label,
          sectionKey: section.key,
          sectionTitle: section.title,
          stageKey: stage.key,
          stageTitle: stage.title,
          department: section.department || stage.department,
          required: Boolean(field.required),
          editable: isFieldEditable(field),
          complete: isFieldComplete(field),
          attachmentCount: attachmentsByField.value[field.key]?.length || 0,
          issueMessage: issue?.message || "",
          issueLevel: issue?.level
        };
      });
    });

    const rankTask = (task: WorkflowFieldTask) =>
      (task.issueLevel === "invalid" ? 0 : task.issueLevel === "missing" ? 1 : 2) + (task.required ? 0 : 1);

    const primaryTasks = fieldTasks
      .filter(task => task.editable && (!task.complete || task.issueMessage))
      .sort((left, right) => rankTask(left) - rankTask(right))
      .slice(0, 8);

    const collaborationTasks = fieldTasks.filter(task => task.editable && task.complete && !task.issueMessage).slice(0, 6);

    const blockingItems = fieldIssues.value
      .map(issue => {
        const section = sectionsByKey[issue.sectionKey];
        const field = section?.fields.find(item => item.key === issue.fieldKey);
        const stage = section ? stageForSection[section.key] || activeLifecycleStage.value : activeLifecycleStage.value;

        return {
          fieldKey: issue.fieldKey,
          fieldLabel: issue.fieldLabel,
          sectionKey: issue.sectionKey,
          sectionTitle: issue.sectionTitle,
          stageKey: stage.key,
          stageTitle: stage.title,
          department: section?.department || stage.department,
          required: Boolean(field?.required),
          editable: field ? isFieldEditable(field) : currentRole.value === "admin",
          complete: field ? isFieldComplete(field) : false,
          attachmentCount: attachmentsByField.value[issue.fieldKey]?.length || 0,
          issueMessage: issue.message,
          issueLevel: issue.level
        };
      })
      .slice(0, 8);

    const reviewTasks = blockingItems.filter(
      task => !task.editable || ["admin", "quality", "doctor"].includes(currentRole.value)
    );

    const attachmentTasks = taskSections
      .flatMap(section =>
        section.fields
          .filter(field => field.kind === "attachment" || field.evidence)
          .map<WorkflowAttachmentTask>(field => ({
            fieldKey: field.key,
            fieldLabel: field.label,
            sectionKey: section.key,
            sectionTitle: section.title,
            attachmentCount: attachmentsByField.value[field.key]?.length || 0,
            required: Boolean(field.required || field.evidence),
            editable: isFieldEditable(field)
          }))
      )
      .slice(0, 6);

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

    const nextStepAdvice = primaryTasks.length
      ? `${roleName.value}先处理 ${primaryTasks[0].sectionTitle}：${primaryTasks[0].fieldLabel}`
      : blockingItems.length
        ? `当前岗位已处理，等待 ${blockingItems[0].department || "相关岗位"} 补齐 ${blockingItems[0].fieldLabel}`
        : archiveReadiness.ready
          ? "字段和必填项已就绪，可继续预览、打印或提交质控归档"
          : `继续推进 ${currentStage.title}，完成后流转到 ${nextStage?.owner || currentStage.owner}`;

    return {
      currentStage,
      nextStage,
      stageNodes,
      primaryTasks,
      collaborationTasks,
      reviewTasks,
      blockingItems,
      attachmentTasks,
      archiveReadiness,
      nextStepAdvice
    };
  });
};
