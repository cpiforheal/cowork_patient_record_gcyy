import { computed, type ComputedRef, type Ref } from "vue";

import type { BackupStatus, MaintenanceStatus } from "@/api/modules/clinic";

type UseHomeDashboardOptions = {
  currentRole: ComputedRef<string>;
  maintenanceStatus: Ref<MaintenanceStatus | undefined>;
  backupStatus: Ref<BackupStatus | undefined>;
};

const allQuickEntries = [
  { title: "患者流程看板", desc: "查看今日患者卡片", icon: "Connection", path: "/encounters/active" },
  { title: "今日患者", desc: "按姓名和门诊号查询", icon: "UserFilled", path: "/patients/list" },
  { title: "上传资料", desc: "门诊号识别后一键上传", icon: "UploadFilled", path: "/workbench/upload" },
  { title: "旧资料导入", desc: "迁移共享文件夹资料", icon: "FolderOpened", path: "/workbench/legacy" },
  { title: "字段权限", desc: "查看字段归属", icon: "DocumentCopy", path: "/templates/record" },
  { title: "档案审核", desc: "退回整改或通过归档", icon: "Tickets", path: "/audit/review" },
  { title: "操作日志", desc: "追踪资料改动", icon: "DocumentChecked", path: "/audit/log" }
];

const roleEntries: Record<string, string[]> = {
  admin: [
    "/encounters/active",
    "/patients/list",
    "/workbench/upload",
    "/workbench/legacy",
    "/templates/record",
    "/audit/review",
    "/audit/log"
  ],
  frontdesk: ["/encounters/active", "/patients/list", "/workbench/upload", "/workbench/legacy", "/templates/record"],
  lab: ["/encounters/active", "/patients/list", "/workbench/upload", "/workbench/legacy", "/templates/record"],
  ecg: ["/encounters/active", "/patients/list", "/workbench/upload", "/workbench/legacy", "/templates/record"],
  ultrasound: ["/encounters/active", "/patients/list", "/workbench/upload", "/workbench/legacy", "/templates/record"],
  nurse: ["/encounters/active", "/patients/list", "/workbench/upload", "/workbench/legacy", "/templates/record"],
  doctor: ["/encounters/active", "/patients/list", "/templates/record"],
  quality: ["/encounters/active", "/patients/list", "/workbench/legacy", "/templates/record", "/audit/review", "/audit/log"]
};

const formatBytes = (bytes?: number) => {
  if (!bytes) return "0 MB";

  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unitIndex = 0;

  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }

  return `${value.toFixed(value >= 10 || unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
};

export const useHomeDashboard = ({ currentRole, maintenanceStatus, backupStatus }: UseHomeDashboardOptions) => {
  const quickEntries = computed(() => {
    const allowPaths = roleEntries[currentRole.value] ?? roleEntries.frontdesk;

    return allQuickEntries.filter(item => allowPaths.includes(item.path));
  });

  const storageSummary = computed(() => {
    const storage = maintenanceStatus.value?.storage;

    if (!storage) return "等待巡检";
    if (storage.scanSkipped) return `引用 ${storage.referencedFileCount} 个附件，点击巡检核对缺失`;

    return `${storage.fileCount} 个文件 / ${formatBytes(storage.totalBytes)}，缺失 ${storage.missingFileCount}`;
  });

  const snapshotSummary = computed(() => {
    if (!maintenanceStatus.value) return "等待巡检";

    return `${maintenanceStatus.value.snapshotCount} 个快照`;
  });

  const latestBackupSummary = computed(() => {
    if (backupStatus.value?.running) return "备份进行中";

    const latestRun = backupStatus.value?.latestRun;

    if (!latestRun) return "尚未备份";
    if (latestRun.status === "failed") return "上次失败";
    if (latestRun.status === "running") return "备份进行中";

    return `${formatBytes(latestRun.sizeBytes)} / ${latestRun.finishedAt || latestRun.startedAt}`;
  });

  const backupStorageSummary = computed(() => {
    if (!backupStatus.value) return "等待备份巡检";

    return `${backupStatus.value.backupFileCount} 个备份 / ${formatBytes(
      backupStatus.value.backupTotalBytes
    )}，可用 ${formatBytes(backupStatus.value.usableSpaceBytes)}`;
  });

  const backupHealthItems = computed(() => {
    const latestRun = backupStatus.value?.latestRun;
    const hasPath = Boolean(backupStatus.value?.backupDir);
    const latestOk = latestRun?.status === "success";
    const hasBackupFiles = (backupStatus.value?.backupFileCount || 0) > 0;
    const hasSpace = (backupStatus.value?.usableSpaceBytes || 0) > 1024 * 1024 * 1024;

    return [
      {
        key: "latest",
        label: "最近结果",
        value: backupStatus.value?.running ? "备份中" : latestRun ? latestRun.message || latestRun.status : "未执行",
        level: backupStatus.value?.running ? "info" : latestOk ? "success" : "warning"
      },
      {
        key: "database",
        label: "数据库",
        value: hasBackupFiles || latestOk ? "已纳入备份" : "等待首次备份",
        level: hasBackupFiles || latestOk ? "success" : "warning"
      },
      {
        key: "attachments",
        label: "附件目录",
        value: maintenanceStatus.value?.storage.scanSkipped
          ? "待巡检"
          : maintenanceStatus.value?.storage.attachmentDir
            ? "已巡检"
            : "待巡检",
        level:
          maintenanceStatus.value?.storage.attachmentDir && !maintenanceStatus.value.storage.scanSkipped ? "success" : "warning"
      },
      {
        key: "path",
        label: "备份路径",
        value: hasPath ? (hasSpace ? "可写空间充足" : "空间需关注") : "未配置",
        level: hasPath ? (hasSpace ? "success" : "warning") : "danger"
      }
    ] as const;
  });

  return {
    quickEntries,
    formatBytes,
    storageSummary,
    snapshotSummary,
    latestBackupSummary,
    backupStorageSummary,
    backupHealthItems
  };
};
