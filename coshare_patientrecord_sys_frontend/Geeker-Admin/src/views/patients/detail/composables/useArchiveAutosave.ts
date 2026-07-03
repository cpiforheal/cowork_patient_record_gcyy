import { computed, reactive, ref, type ComputedRef, type Ref } from "vue";

import { ElMessage } from "element-plus";

import type { RecordSection } from "@/config/fieldPermissions";

export type AutoSaveStatus = "idle" | "saving" | "saved" | "error" | "conflict";

type ArchiveViewMode = "mine" | "full";

type UseArchiveAutosaveOptions = {
  patientId: ComputedRef<string>;
  fieldValues: Record<string, string>;
  recordViewMode: Ref<ArchiveViewMode>;
  activeSectionKey: Ref<string>;
  recordSectionsByRule: ComputedRef<RecordSection[]>;
  getCurrentDraftValues: () => Record<string, string>;
  syncFollowupRecordsFromField: () => void;
  loadPatientDetail: () => Promise<void>;
};

export const useArchiveAutosave = ({
  patientId,
  fieldValues,
  recordViewMode,
  activeSectionKey,
  recordSectionsByRule,
  getCurrentDraftValues,
  syncFollowupRecordsFromField,
  loadPatientDetail
}: UseArchiveAutosaveOptions) => {
  const autoSaveStatus = ref<AutoSaveStatus>("idle");
  const lastSavedAt = ref("");
  const lastSaveError = ref("");
  const sectionSaveTimes = reactive<Record<string, string>>({});
  const conflictDraftSavedAt = ref("");
  const conflictDraftKey = computed(() => `clinic-record-draft:${patientId.value}`);

  const formatLastSavedAt = () => new Date().toLocaleTimeString("zh-CN", { hour12: false });

  const sectionKeysForValues = (values: Record<string, string>) => {
    const valueKeys = new Set(Object.keys(values));

    return recordSectionsByRule.value

      .filter(section => section.fields.some(field => valueKeys.has(field.key)))

      .map(section => section.key);
  };

  const markRecordSaved = (values: Record<string, string> = {}) => {
    const savedAt = formatLastSavedAt();

    lastSavedAt.value = savedAt;
    lastSaveError.value = "";
    autoSaveStatus.value = "saved";

    sectionKeysForValues(values).forEach(sectionKey => {
      sectionSaveTimes[sectionKey] = savedAt;
    });
  };

  const markRecordSaveError = (error: unknown, fallback = "保存失败，请重试") => {
    lastSaveError.value = (error as Error)?.message || fallback;
    autoSaveStatus.value = "error";
  };

  const markRecordConflict = (message = "数据已被其他终端更新，请查看最新版本后再保存") => {
    autoSaveStatus.value = "conflict";
    lastSaveError.value = message;
  };

  const copySaveError = async () => {
    if (!lastSaveError.value) return;

    try {
      await navigator.clipboard.writeText(lastSaveError.value);

      ElMessage.success("错误信息已复制");
    } catch {
      ElMessage.warning("复制失败，请手动选择错误信息");
    }
  };

  const persistLocalDraft = (values: Record<string, string>) => {
    const savedAt = new Date().toLocaleString();

    localStorage.setItem(
      conflictDraftKey.value,

      JSON.stringify({
        patientId: patientId.value,
        savedAt,
        mode: recordViewMode.value,
        sectionKey: activeSectionKey.value,
        values
      })
    );

    conflictDraftSavedAt.value = savedAt;
  };

  const clearLocalDraft = () => {
    localStorage.removeItem(conflictDraftKey.value);
    conflictDraftSavedAt.value = "";
  };

  const restoreConflictDraft = () => {
    const raw = localStorage.getItem(conflictDraftKey.value);

    if (!raw) {
      ElMessage.warning("未找到本机草稿");

      return;
    }

    try {
      const draft = JSON.parse(raw) as {
        values?: Record<string, string>;
        mode?: ArchiveViewMode;
        sectionKey?: string;
        savedAt?: string;
      };

      if (draft.mode) recordViewMode.value = draft.mode;

      if (draft.sectionKey && recordSectionsByRule.value.some(section => section.key === draft.sectionKey)) {
        activeSectionKey.value = draft.sectionKey;
      }

      Object.assign(fieldValues, draft.values || {});

      syncFollowupRecordsFromField();

      conflictDraftSavedAt.value = draft.savedAt || conflictDraftSavedAt.value;
      autoSaveStatus.value = "error";

      ElMessage.info("草稿已恢复，请重新保存");
    } catch {
      ElMessage.error("本机草稿读取失败");
    }
  };

  const viewServerLatest = async () => {
    persistLocalDraft(getCurrentDraftValues());

    await loadPatientDetail();

    autoSaveStatus.value = "conflict";
  };

  const resetArchiveAutosave = () => {
    autoSaveStatus.value = "idle";
    lastSavedAt.value = "";
    lastSaveError.value = "";
    conflictDraftSavedAt.value = "";

    Object.keys(sectionSaveTimes).forEach(sectionKey => delete sectionSaveTimes[sectionKey]);
  };

  return {
    autoSaveStatus,
    lastSavedAt,
    lastSaveError,
    sectionSaveTimes,
    conflictDraftSavedAt,
    markRecordSaved,
    markRecordSaveError,
    markRecordConflict,
    copySaveError,
    persistLocalDraft,
    clearLocalDraft,
    restoreConflictDraft,
    viewServerLatest,
    resetArchiveAutosave
  };
};
