import { computed, nextTick, ref, type ComputedRef, type Ref } from "vue";

import type { RecordAttachment, RecordField, RecordSection } from "@/config/fieldPermissions";

import type { PrintPreflightItem } from "../components/PrintPreflightDialog.vue";
import type { PreviewPageNav } from "../components/RecordPreviewOverlay.vue";
import type { FieldIssue, FollowupRecord } from "../components/types";
import type { AutoSaveStatus } from "./useArchiveAutosave";

type UseRecordPrintPreviewOptions = {
  fieldValues: Record<string, string>;
  clinicalArchiveSections: ComputedRef<RecordSection[]>;
  managementArchiveSections: ComputedRef<RecordSection[]>;
  auditArchiveSections: ComputedRef<RecordSection[]>;
  followupRecords: Ref<FollowupRecord[]>;
  currentAttachments: Ref<RecordAttachment[]>;
  fieldIssues: ComputedRef<FieldIssue[]>;
  invalidAttachmentCount: ComputedRef<number>;
  autoSaveStatus: Ref<AutoSaveStatus>;
  matchedAttachments: (fieldKey: string) => RecordAttachment[];
  logPrintAction: () => Promise<void>;
};

const isMeaningfulPreviewValue = (value?: string) => {
  const normalized = String(value || "").trim();

  return Boolean(normalized) && normalized !== "____" && normalized !== "________" && !/^[_\s年月日-]+$/.test(normalized);
};

export const useRecordPrintPreview = ({
  fieldValues,
  clinicalArchiveSections,
  managementArchiveSections,
  auditArchiveSections,
  followupRecords,
  currentAttachments,
  fieldIssues,
  invalidAttachmentCount,
  autoSaveStatus,
  matchedAttachments,
  logPrintAction
}: UseRecordPrintPreviewOptions) => {
  const previewVisible = ref(false);
  const previewActivePage = ref(1);
  const printPreflightVisible = ref(false);

  const sectionHasPreviewContent = (section: RecordSection) =>
    section.fields.some((field: RecordField) => {
      if (field.key === "followupRecordsJson") return followupRecords.value.length > 0;

      return isMeaningfulPreviewValue(fieldValues[field.key]) || matchedAttachments(field.key).length > 0;
    });

  const clinicalPreviewHasContent = computed(() => clinicalArchiveSections.value.some(sectionHasPreviewContent));

  const managementPreviewHasContent = computed(
    () =>
      managementArchiveSections.value.some(sectionHasPreviewContent) ||
      auditArchiveSections.value.some(sectionHasPreviewContent) ||
      followupRecords.value.length > 0
  );

  const previewNavigation = computed<PreviewPageNav[]>(() => {
    const pages: PreviewPageNav[] = [{ key: "cover", label: "封面", page: 1 }];

    pages.push({ key: "registration", label: "登记表", page: pages.length + 1 });

    if (clinicalPreviewHasContent.value) pages.push({ key: "clinical", label: "诊疗检查", page: pages.length + 1 });

    if (managementPreviewHasContent.value) pages.push({ key: "management", label: "管理随访", page: pages.length + 1 });

    if (currentAttachments.value.length) pages.push({ key: "attachments", label: "附件", page: pages.length + 1 });

    return pages;
  });

  const previewPageByKey = (key: string) => previewNavigation.value.find(page => page.key === key);

  const coverPreviewPage = computed(() => previewPageByKey("cover") || { key: "cover", label: "封面", page: 1 });

  const registrationPreviewPage = computed(
    () => previewPageByKey("registration") || { key: "registration", label: "登记表", page: 2 }
  );

  const clinicalPreviewPage = computed(() => previewPageByKey("clinical"));
  const managementPreviewPage = computed(() => previewPageByKey("management"));
  const attachmentIndexPreviewPage = computed(() => previewPageByKey("attachments"));

  const attachmentPreviewStartPage = computed(() =>
    attachmentIndexPreviewPage.value ? attachmentIndexPreviewPage.value.page + 1 : previewNavigation.value.length + 1
  );

  const previewPageCount = computed(() => previewNavigation.value.length + currentAttachments.value.length);

  const printFileTitle = computed(() =>
    [fieldValues.hospitalName, fieldValues.patientName, fieldValues.visitNo, new Date().toISOString().slice(0, 10)]

      .filter(Boolean)

      .join("_")
  );

  const printPreflightItems = computed<PrintPreflightItem[]>(() => [
    {
      key: "required",
      level: fieldIssues.value.length ? "warning" : "success",
      label: "必填字段",
      value: fieldIssues.value.length ? `仍有 ${fieldIssues.value.length} 项待补齐` : "已补齐"
    },
    {
      key: "attachments",
      level: invalidAttachmentCount.value ? "warning" : "success",
      label: "附件状态",
      value: invalidAttachmentCount.value ? `${invalidAttachmentCount.value} 份附件需重新确认` : "附件可打开"
    },
    {
      key: "draft",
      level: ["saving", "error", "conflict"].includes(autoSaveStatus.value) ? "warning" : "success",
      label: "保存状态",
      value:
        autoSaveStatus.value === "conflict"
          ? "存在冲突草稿"
          : autoSaveStatus.value === "saving"
            ? "正在自动保存"
            : autoSaveStatus.value === "error"
              ? "保存失败待重试"
              : "已同步"
    }
  ]);

  const scrollPreviewPage = async (page: number) => {
    previewActivePage.value = page;

    await nextTick();

    document.querySelector<HTMLElement>(`[data-preview-page="${page}"]`)?.scrollIntoView({
      behavior: "smooth",
      block: "start"
    });
  };

  const executePrint = async () => {
    printPreflightVisible.value = false;

    await logPrintAction();

    await nextTick();

    const originalTitle = document.title;

    if (printFileTitle.value) document.title = printFileTitle.value;

    window.setTimeout(() => {
      window.print();

      window.setTimeout(() => {
        document.title = originalTitle;
      }, 800);
    }, 160);
  };

  const openPrintPreflight = async () => {
    previewVisible.value = true;
    printPreflightVisible.value = true;

    await nextTick();
  };

  const resetPrintPreview = () => {
    previewVisible.value = false;
    previewActivePage.value = 1;
    printPreflightVisible.value = false;
  };

  return {
    previewVisible,
    previewActivePage,
    printPreflightVisible,
    previewNavigation,
    coverPreviewPage,
    registrationPreviewPage,
    clinicalPreviewPage,
    managementPreviewPage,
    attachmentIndexPreviewPage,
    attachmentPreviewStartPage,
    previewPageCount,
    printFileTitle,
    printPreflightItems,
    scrollPreviewPage,
    executePrint,
    openPrintPreflight,
    printRecord: openPrintPreflight,
    openPreviewThenPrint: openPrintPreflight,
    resetPrintPreview
  };
};
