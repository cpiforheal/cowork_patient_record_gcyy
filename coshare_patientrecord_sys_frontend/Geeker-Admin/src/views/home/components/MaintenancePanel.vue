<template>
  <div class="maintenance-panel">
    <div class="panel-head compact">
      <div>
        <h2>生产维护</h2>
        <p>存储巡检、数据快照与物理备份（仅管理员可见）。</p>
      </div>
      <el-button :icon="Refresh" link :loading="maintenanceLoading" @click="$emit('refresh')">巡检</el-button>
    </div>

    <div class="maintenance-card">
      <div class="maintenance-summary">
        <span>附件存储</span>
        <strong>{{ storageSummary }}</strong>
        <small>{{ maintenanceStatus?.storage.attachmentDir || "等待后端巡检" }}</small>
      </div>
      <div class="maintenance-summary">
        <span>数据快照</span>
        <strong>{{ snapshotSummary }}</strong>
        <small>{{ maintenanceStatus?.latestSnapshotAt || "尚未生成快照" }}</small>
      </div>
      <el-button type="primary" plain :loading="maintenanceLoading" @click="$emit('createSnapshot')">生成快照</el-button>
    </div>

    <div class="backup-card">
      <div class="backup-head">
        <div>
          <span>物理备份</span>
          <strong>{{ latestBackupSummary }}</strong>
          <small>{{ backupStatus?.schedule || "每天 02:00" }}，保留 7 天 / 4 周 / 12 月</small>
        </div>
        <el-switch
          :model-value="backupEnabled"
          active-text="自动"
          inactive-text="停用"
          :disabled="backupLoading || backupStatus?.running"
          @update:model-value="$emit('update:backupEnabled', Boolean($event))"
        />
      </div>
      <el-input
        :model-value="backupPath"
        clearable
        :disabled="backupLoading || choosingBackupDir || backupStatus?.running"
        placeholder="例如：\\192.168.1.10\clinic-backup 或 D:\clinic-backup"
        @update:model-value="$emit('update:backupPath', String($event || ''))"
      >
        <template #append>
          <el-button
            :loading="choosingBackupDir"
            :disabled="backupLoading || backupStatus?.running"
            @click="$emit('chooseBackupDirectory')"
          >
            选择目录
          </el-button>
        </template>
      </el-input>
      <div class="backup-meta">
        <span>{{ backupStorageSummary }}</span>
        <small>{{ backupStatus?.backupDir || "尚未设置备份路径" }}</small>
      </div>
      <div class="backup-health-grid">
        <article v-for="item in backupHealthItems" :key="item.key" :class="`is-${item.level}`">
          <i aria-hidden="true"></i>
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
        </article>
      </div>
      <div class="backup-actions">
        <el-button :loading="backupLoading" :disabled="backupStatus?.running" @click="$emit('saveBackupConfig')">
          保存路径
        </el-button>
        <el-button type="primary" :loading="backupLoading || backupStatus?.running" @click="$emit('runBackupNow')">
          立即备份
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Refresh } from "@element-plus/icons-vue";
import type { BackupStatus, MaintenanceStatus } from "@/api/modules/clinic";

type BackupHealthItem = {
  key: string;
  label: string;
  value: string;
  level: "success" | "warning" | "danger" | "info";
};

defineProps<{
  maintenanceLoading: boolean;
  storageSummary: string;
  snapshotSummary: string;
  maintenanceStatus?: MaintenanceStatus;
  latestBackupSummary: string;
  backupStatus?: BackupStatus;
  backupEnabled: boolean;
  backupPath: string;
  backupLoading: boolean;
  choosingBackupDir: boolean;
  backupStorageSummary: string;
  backupHealthItems: readonly BackupHealthItem[];
}>();

defineEmits<{
  refresh: [];
  createSnapshot: [];
  chooseBackupDirectory: [];
  saveBackupConfig: [];
  runBackupNow: [];
  "update:backupEnabled": [value: boolean];
  "update:backupPath": [value: string];
}>();
</script>

<style scoped lang="scss">
.maintenance-panel {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.panel-head.compact {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  h2,
  p {
    margin: 0;
  }
  h2 {
    font-size: 16px;
    line-height: 1.35;
  }
  p {
    margin-top: 4px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}
.maintenance-card {
  display: grid;
  gap: 10px;
  margin-top: 12px;
  padding: 12px;
  background: #f7fbfa;
  border: 1px solid rgb(20 184 166 / 16%);
  border-radius: 6px;
  span,
  strong,
  small {
    display: block;
  }
  span,
  small {
    color: var(--el-text-color-secondary);
  }
  strong {
    margin-top: 2px;
    color: var(--el-text-color-primary);
  }
  small {
    overflow: hidden;
    margin-top: 2px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
.backup-card {
  display: grid;
  gap: 10px;
  margin-top: 10px;
  padding: 12px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  span,
  strong,
  small {
    display: block;
  }
  span,
  small {
    color: var(--el-text-color-secondary);
  }
  strong {
    margin-top: 2px;
    color: var(--el-text-color-primary);
    line-height: 1.35;
  }
  small {
    margin-top: 2px;
    line-height: 1.45;
  }
}
.backup-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.backup-meta {
  display: grid;
  gap: 2px;
  min-width: 0;
  span,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
.backup-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.backup-health-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  article {
    display: grid;
    grid-template-columns: 8px minmax(0, 1fr);
    gap: 2px 8px;
    align-items: center;
    padding: 9px 10px;
    background: var(--el-fill-color-light);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;
    i {
      width: 8px;
      height: 8px;
      background: var(--clinic-info, #0f766e);
      border-radius: 50%;
      box-shadow: 0 0 0 4px rgb(15 118 110 / 10%);
    }
    span,
    strong {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    span {
      color: var(--el-text-color-secondary);
      font-size: 12px;
    }
    strong {
      grid-column: 2;
      color: var(--el-text-color-primary);
      font-size: 13px;
    }
    &.is-success i {
      background: var(--clinic-success, #15803d);
      box-shadow: 0 0 0 4px rgb(22 163 74 / 10%);
    }
    &.is-warning i {
      background: var(--clinic-warning, #b45309);
      box-shadow: 0 0 0 4px rgb(217 119 6 / 12%);
    }
    &.is-danger i {
      background: var(--clinic-danger, #b91c1c);
      box-shadow: 0 0 0 4px rgb(220 38 38 / 10%);
    }
  }
}
@media (max-width: 760px) {
  .backup-health-grid {
    grid-template-columns: 1fr;
  }
}
</style>
