import { computed, type ComputedRef } from "vue";
import type { RecordAttachment, RecordField, RecordSection } from "@/config/fieldPermissions";
import type { FieldIssue } from "../components/types";

export interface ArchiveFieldIndex {
  visibleSections: ComputedRef<RecordSection[]>;
  printableSections: ComputedRef<RecordSection[]>;
  allFields: ComputedRef<RecordField[]>;
  attachmentsByField: ComputedRef<Record<string, RecordAttachment[]>>;
  issuesByField: ComputedRef<Record<string, FieldIssue>>;
  issuesBySection: ComputedRef<Record<string, FieldIssue[]>>;
  completionBySection: ComputedRef<
    Record<string, { completed: number; requiredMissing: number; evidenceCount: number; total: number }>
  >;
  fieldIssues: ComputedRef<FieldIssue[]>;
  completionStats: ComputedRef<{ completed: number; requiredMissing: number; total: number }>;
  matchedAttachments: (fieldKey: string) => RecordAttachment[];
  issueForField: (field: RecordField) => FieldIssue | undefined;
  sectionIssues: (section: RecordSection) => FieldIssue[];
  isRecordFieldComplete: (field: RecordField) => boolean;
  sectionCompletedCount: (section: RecordSection) => number;
  sectionRequiredMissingCount: (section: RecordSection) => number;
  sectionEvidenceCount: (section: RecordSection) => number;
  sectionEvidenceCountForSections: (sections: RecordSection[]) => number;
  isSectionComplete: (section: RecordSection) => boolean;
  isSectionMeaningful: (section: RecordSection) => boolean;
}

export const useArchiveFieldIndex = ({
  sourceSections,
  visibleSectionsSource,
  attachments,
  fieldValues,
  resolveField,
  isFieldVisible,
  isValueComplete,
  validateFieldValue
}: {
  sourceSections: RecordSection[];
  visibleSectionsSource?: ComputedRef<RecordSection[]>;
  attachments: ComputedRef<RecordAttachment[]>;
  fieldValues: Record<string, string>;
  resolveField?: (field: RecordField) => RecordField | null;
  isFieldVisible?: (section: RecordSection, field: RecordField) => boolean;
  isValueComplete: (value: string) => boolean;
  validateFieldValue: (field: RecordField, value?: string) => string;
}): ArchiveFieldIndex => {
  const visibleSections = computed<RecordSection[]>(() =>
    visibleSectionsSource
      ? visibleSectionsSource.value
      : sourceSections
          .map(section => {
            const fields = section.fields.map(field => resolveField?.(field) ?? field).filter(Boolean) as RecordField[];
            return {
              ...section,
              fields: fields.filter(field => isFieldVisible?.(section, field) ?? true)
            };
          })
          .filter(section => section.fields.length)
  );

  const printableSections = computed<RecordSection[]>(() =>
    visibleSections.value
      .map(section => ({
        ...section,
        fields: section.fields.filter(field => field.printable !== false)
      }))
      .filter(section => section.fields.length)
  );

  const allFields = computed(() => visibleSections.value.flatMap(section => section.fields));

  const attachmentsByField = computed(() =>
    attachments.value.reduce<Record<string, RecordAttachment[]>>((index, attachment) => {
      (index[attachment.fieldKey] ||= []).push(attachment);
      return index;
    }, {})
  );

  const matchedAttachments = (fieldKey: string) => attachmentsByField.value[fieldKey] || [];

  const isRecordFieldComplete = (field: RecordField) => {
    if (field.kind === "attachment") return matchedAttachments(field.key).length > 0;
    return isValueComplete(fieldValues[field.key] || "");
  };

  const fieldIssues = computed<FieldIssue[]>(() =>
    visibleSections.value.flatMap(section =>
      section.fields.flatMap((field): FieldIssue[] => {
        const value = fieldValues[field.key] || "";
        if (field.required && !isRecordFieldComplete(field)) {
          return [
            {
              fieldKey: field.key,
              fieldLabel: field.label,
              sectionKey: section.key,
              sectionTitle: section.title,
              message: "必填项待补",
              level: "missing"
            }
          ];
        }

        const invalidMessage = validateFieldValue(field, value);
        return invalidMessage
          ? [
              {
                fieldKey: field.key,
                fieldLabel: field.label,
                sectionKey: section.key,
                sectionTitle: section.title,
                message: invalidMessage,
                level: "invalid"
              }
            ]
          : [];
      })
    )
  );

  const issuesByField = computed(() =>
    fieldIssues.value.reduce<Record<string, FieldIssue>>((index, issue) => {
      index[issue.fieldKey] = issue;
      return index;
    }, {})
  );

  const issuesBySection = computed(() =>
    fieldIssues.value.reduce<Record<string, FieldIssue[]>>((index, issue) => {
      (index[issue.sectionKey] ||= []).push(issue);
      return index;
    }, {})
  );

  const sectionCompletedCount = (section: RecordSection) => section.fields.filter(isRecordFieldComplete).length;

  const sectionRequiredMissingCount = (section: RecordSection) =>
    section.fields.filter(field => field.required && !isRecordFieldComplete(field)).length;

  const sectionEvidenceCount = (section: RecordSection) =>
    section.fields.reduce((count, field) => count + matchedAttachments(field.key).length, 0);

  const completionBySection = computed(() =>
    visibleSections.value.reduce<
      Record<string, { completed: number; requiredMissing: number; evidenceCount: number; total: number }>
    >((index, section) => {
      index[section.key] = {
        completed: sectionCompletedCount(section),
        requiredMissing: sectionRequiredMissingCount(section),
        evidenceCount: sectionEvidenceCount(section),
        total: section.fields.length
      };
      return index;
    }, {})
  );

  const completionStats = computed(() => {
    const stats = Object.values(completionBySection.value).reduce(
      (result, section) => {
        result.completed += section.completed;
        result.requiredMissing += section.requiredMissing;
        result.total += section.total;
        return result;
      },
      { completed: 0, requiredMissing: 0, total: 0 }
    );
    return stats;
  });

  const issueForField = (field: RecordField) => issuesByField.value[field.key];

  const sectionIssues = (section: RecordSection) => issuesBySection.value[section.key] || [];

  const sectionEvidenceCountForSections = (sections: RecordSection[]) =>
    sections.reduce((count, section) => count + sectionEvidenceCount(section), 0);

  const isSectionComplete = (section: RecordSection) =>
    section.fields.length > 0 && sectionCompletedCount(section) === section.fields.length;

  const isSectionMeaningful = (section: RecordSection) =>
    section.fields.some(field => isValueComplete(fieldValues[field.key] || "") || matchedAttachments(field.key).length > 0);

  return {
    visibleSections,
    printableSections,
    allFields,
    attachmentsByField,
    issuesByField,
    issuesBySection,
    completionBySection,
    fieldIssues,
    completionStats,
    matchedAttachments,
    issueForField,
    sectionIssues,
    isRecordFieldComplete,
    sectionCompletedCount,
    sectionRequiredMissingCount,
    sectionEvidenceCount,
    sectionEvidenceCountForSections,
    isSectionComplete,
    isSectionMeaningful
  };
};
