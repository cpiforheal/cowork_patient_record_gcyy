import type { UploadUserFile } from "element-plus";

const imageFilePattern = /\.(png|jpe?g|gif|webp|bmp|svg)$/i;

const padDateUnit = (value: number) => String(value).padStart(2, "0");

export const useUploadWorkbench = () => {
  const mapWithConcurrency = async <T, R>(items: T[], worker: (item: T, index: number) => Promise<R>, concurrency = 3) => {
    const results: R[] = new Array(items.length);
    let nextIndex = 0;

    const runNext = async () => {
      while (nextIndex < items.length) {
        const currentIndex = nextIndex;
        nextIndex += 1;
        results[currentIndex] = await worker(items[currentIndex], currentIndex);
      }
    };

    await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, runNext));
    return results;
  };

  const readQueryString = (value: unknown) => (Array.isArray(value) ? value[0] : typeof value === "string" ? value : "");

  const timestampName = () => {
    const date = new Date();

    return `${date.getFullYear()}${padDateUnit(date.getMonth() + 1)}${padDateUnit(date.getDate())}-${padDateUnit(
      date.getHours()
    )}${padDateUnit(date.getMinutes())}${padDateUnit(date.getSeconds())}`;
  };

  const photoFileName = (file: File) => {
    const ext = file.name.includes(".") ? file.name.slice(file.name.lastIndexOf(".")) : ".jpg";

    return `现场拍照-${timestampName()}${ext}`;
  };

  const isImageFile = (file?: UploadUserFile["raw"], fileName = "") =>
    Boolean(file?.type?.startsWith("image/") || imageFilePattern.test(fileName));

  const imageOnly = (file: File) => file.type.startsWith("image/") || imageFilePattern.test(file.name);

  const fileToDataUrl = (file?: UploadUserFile["raw"]) =>
    new Promise<string>(resolve => {
      if (!file) {
        resolve("");

        return;
      }

      const reader = new FileReader();

      reader.onload = () => resolve(String(reader.result || ""));
      reader.onerror = () => resolve("");
      reader.readAsDataURL(file);
    });

  const formatFileSize = (size: number) => {
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;

    return `${(size / 1024 / 1024).toFixed(1)} MB`;
  };

  return {
    mapWithConcurrency,
    readQueryString,
    timestampName,
    photoFileName,
    isImageFile,
    imageOnly,
    fileToDataUrl,
    formatFileSize
  };
};
