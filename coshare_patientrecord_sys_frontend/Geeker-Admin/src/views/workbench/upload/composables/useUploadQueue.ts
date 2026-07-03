import { computed, onScopeDispose, ref, type Ref } from "vue";
import type { UploadUserFile } from "element-plus";
import type { PatientRow } from "@/api/modules/clinic";

export type UploadQueueStatus = "pending" | "uploading" | "success" | "failed" | "retrying";

export interface UploadItem {
  id: string;
  type: string;
  files: UploadUserFile[];
}

export interface FailedUploadSummary {
  id: string;
  patientId: string;
  patientName: string;
  visitNo: string;
  failedAt: string;
  message: string;
  fileNames: string[];
}

export interface FailedUpload extends Omit<FailedUploadSummary, "patientId" | "patientName" | "visitNo"> {
  patient: PatientRow;
  keyword: string;
  items: UploadItem[];
}

interface UseUploadQueueOptions {
  keyword: Ref<string>;
  selectedPatient: Ref<PatientRow | undefined>;
  storageKey: string;
}

const createUploadItem = (): UploadItem => ({ id: `upload-${Date.now()}`, type: "", files: [] });

export const useUploadQueue = ({ keyword, selectedPatient, storageKey }: UseUploadQueueOptions) => {
  const uploadStatus = ref<UploadQueueStatus>("pending");
  const uploadProgress = ref(0);
  const uploadItems = ref<UploadItem[]>([createUploadItem()]);
  const failedUploads = ref<FailedUpload[]>([]);
  const staleFailedUploadSummaries = ref<FailedUploadSummary[]>([]);
  let persistTimer: number | undefined;
  const uploading = computed(() => uploadStatus.value === "uploading" || uploadStatus.value === "retrying");
  const uploadSuccess = computed(() => uploadStatus.value === "success");

  const addUploadItem = () => {
    uploadItems.value.push(createUploadItem());
  };

  const removeUploadItem = (index: number) => {
    uploadItems.value.splice(index, 1);
  };

  const resetUploadItems = () => {
    uploadItems.value = [createUploadItem()];
  };

  const cloneUploadItems = (items: UploadItem[]) =>
    items.map(item => ({
      id: `${item.id}-${Date.now()}`,
      type: item.type,
      files: item.files.map(file => ({ ...file }))
    }));

  const collectUploadFileNames = (items: UploadItem[]) => items.flatMap(item => item.files.map(file => file.name));

  const loadFailedUploadSummaries = () => {
    try {
      const raw = sessionStorage.getItem(storageKey);
      staleFailedUploadSummaries.value = raw ? (JSON.parse(raw) as FailedUploadSummary[]) : [];
    } catch {
      staleFailedUploadSummaries.value = [];
    }
  };

  const persistFailedUploadSummaries = () => {
    if (persistTimer) {
      window.clearTimeout(persistTimer);
      persistTimer = undefined;
    }
    const summaries: FailedUploadSummary[] = failedUploads.value.map(failure => ({
      id: failure.id,
      patientId: failure.patient.id,
      patientName: failure.patient.name,
      visitNo: failure.patient.visitNo,
      failedAt: failure.failedAt,
      message: failure.message,
      fileNames: failure.fileNames
    }));
    sessionStorage.setItem(storageKey, JSON.stringify([...summaries, ...staleFailedUploadSummaries.value]));
  };

  const schedulePersistFailedUploadSummaries = () => {
    if (persistTimer) window.clearTimeout(persistTimer);
    persistTimer = window.setTimeout(persistFailedUploadSummaries, 150);
  };

  const rememberFailedUpload = (error: unknown) => {
    if (!selectedPatient.value) return;
    const failure: FailedUpload = {
      id: `failure-${Date.now()}`,
      patient: selectedPatient.value,
      keyword: keyword.value,
      items: cloneUploadItems(uploadItems.value),
      failedAt: new Date().toLocaleString("zh-CN", { hour12: false }),
      message: (error as Error)?.message || "上传失败，请稍后重试",
      fileNames: collectUploadFileNames(uploadItems.value)
    };
    failedUploads.value = [failure, ...failedUploads.value].slice(0, 5);
    schedulePersistFailedUploadSummaries();
  };

  const dismissFailedUpload = (id: string) => {
    failedUploads.value = failedUploads.value.filter(item => item.id !== id);
    schedulePersistFailedUploadSummaries();
  };

  const dismissStaleFailure = (id: string) => {
    staleFailedUploadSummaries.value = staleFailedUploadSummaries.value.filter(item => item.id !== id);
    schedulePersistFailedUploadSummaries();
  };

  window.addEventListener("beforeunload", persistFailedUploadSummaries);
  onScopeDispose(() => {
    window.removeEventListener("beforeunload", persistFailedUploadSummaries);
    persistFailedUploadSummaries();
  });

  const beginUpload = (retrying = false) => {
    uploadStatus.value = retrying ? "retrying" : "uploading";
    uploadProgress.value = 15;
    window.setTimeout(() => {
      if (uploading.value) uploadProgress.value = 55;
    }, 160);
  };

  const markUploadSuccess = () => {
    uploadProgress.value = 100;
    uploadStatus.value = "success";
  };

  const markUploadFailed = () => {
    uploadProgress.value = 0;
    uploadStatus.value = "failed";
  };

  const resetUploadState = () => {
    uploadProgress.value = 0;
    uploadStatus.value = "pending";
  };

  const restoreFailedUploadItems = (failure: FailedUpload) => {
    uploadItems.value = cloneUploadItems(failure.items);
    resetUploadState();
  };

  return {
    uploadStatus,
    uploading,
    uploadSuccess,
    uploadProgress,
    uploadItems,
    failedUploads,
    staleFailedUploadSummaries,
    addUploadItem,
    removeUploadItem,
    resetUploadItems,
    loadFailedUploadSummaries,
    rememberFailedUpload,
    restoreFailedUploadItems,
    dismissFailedUpload,
    dismissStaleFailure,
    beginUpload,
    markUploadSuccess,
    markUploadFailed,
    resetUploadState
  };
};
