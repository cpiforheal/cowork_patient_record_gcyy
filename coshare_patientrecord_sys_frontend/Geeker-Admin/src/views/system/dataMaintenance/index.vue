<template>
  <div v-if="!isAdmin" class="access-denied">
    <el-result icon="error" title="无权访问" sub-title="数据清理仅限系统管理员执行。" />
  </div>

  <div v-else class="data-maintenance-page">
    <header class="page-header">
      <div>
        <h2>测试数据清理</h2>
        <p>先生成保护备份，再清理患者、业务流水、测试账号及库存测试数据。</p>
      </div>
      <el-space>
        <el-button v-if="run && !sessionRevoked" :icon="Refresh" :loading="runLoading" @click="loadRun()">刷新执行状态</el-button>
        <el-button v-if="sessionRevoked" type="primary" @click="goToLogin">重新登录</el-button>
        <el-button :icon="Refresh" :loading="previewLoading" :disabled="sessionRevoked" @click="loadPreview">重新预检</el-button>
      </el-space>
    </header>

    <el-alert type="error" :closable="false" show-icon>
      <template #title>这是高风险且不可直接撤销的操作</template>
      执行后业务数据会从当前系统移除。请先核对预检范围和保留项；恢复时必须使用本次生成的保护备份。
    </el-alert>

    <section v-loading="previewLoading" class="maintenance-section">
      <div class="section-header">
        <div>
          <span>第一步</span>
          <h3>核对清理范围</h3>
        </div>
        <el-tag v-if="preview" effect="plain">数据库版本 {{ preview.databaseRevision || "未知" }}</el-tag>
      </div>

      <el-empty v-if="!preview && !previewLoading" description="尚未生成预检结果" />
      <template v-else-if="preview">
        <div class="summary-strip">
          <div>
            <span>待清理记录</span>
            <strong>{{ totalRecords }}</strong>
          </div>
          <div>
            <span>受管文件</span>
            <strong>{{ preview.managedFiles.fileCount }}</strong>
          </div>
          <div>
            <span>文件容量</span>
            <strong>{{ formatBytes(preview.managedFiles.totalBytes) }}</strong>
          </div>
          <div>
            <span>预检有效期</span>
            <strong>{{ formatDateTime(preview.expiresAt) }}</strong>
          </div>
        </div>

        <el-table :data="countRows" size="small" stripe>
          <el-table-column prop="label" label="数据域" min-width="180" />
          <el-table-column prop="count" label="记录数" width="120" align="right" />
        </el-table>

        <div class="detail-grid">
          <div>
            <h4>保留内容</h4>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item v-for="item in retainedRows" :key="item.key" :label="item.label">
                {{ item.value }}
              </el-descriptions-item>
            </el-descriptions>
          </div>
          <div>
            <h4>受管目录</h4>
            <ul class="path-list">
              <li v-for="directory in preview.managedFiles.directories" :key="directory">{{ directory }}</li>
              <li v-if="!preview.managedFiles.directories.length">未发现受管文件目录</li>
            </ul>
          </div>
        </div>

        <el-alert
          v-for="warning in preview.warnings"
          :key="warning"
          :title="warning"
          type="warning"
          :closable="false"
          show-icon
        />
      </template>
    </section>

    <section class="maintenance-section">
      <div class="section-header">
        <div>
          <span>第二步</span>
          <h3>管理员确认并执行</h3>
        </div>
      </div>

      <el-form class="confirm-form" label-position="top">
        <el-form-item label="当前管理员密码">
          <el-input
            v-model="password"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="用于再次验证身份"
          />
        </el-form-item>
        <el-form-item :label="`输入确认文本：${preview?.confirmationText || '请先执行预检'}`">
          <el-input v-model="confirmationText" :disabled="!preview" placeholder="必须逐字一致" />
        </el-form-item>
        <el-button type="danger" :icon="Delete" :loading="executing" :disabled="!canExecute" @click="executePurge">
          生成保护备份并清理
        </el-button>
      </el-form>
    </section>

    <section v-if="run" class="maintenance-section run-section">
      <div class="section-header">
        <div>
          <span>执行记录</span>
          <h3>{{ run.runId }}</h3>
        </div>
        <el-tag :type="runStatusType" effect="plain">{{ runStatusLabel }}</el-tag>
      </div>

      <el-steps :active="runStep" finish-status="success" process-status="process" align-center>
        <el-step title="保护备份" />
        <el-step title="数据库清理" />
        <el-step title="文件隔离" />
        <el-step title="完成" />
      </el-steps>

      <el-descriptions :column="1" border>
        <el-descriptions-item label="备份目录">{{ run.backupDir || "生成中" }}</el-descriptions-item>
        <el-descriptions-item label="备份校验值">{{ run.backupSha256 || "生成中" }}</el-descriptions-item>
        <el-descriptions-item label="数据库提交">{{ run.databaseCommitted ? "已提交" : "未提交" }}</el-descriptions-item>
        <el-descriptions-item label="文件隔离">{{ run.filesQuarantined ? "已完成" : "未完成" }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ formatDateTime(run.updatedAt) }}</el-descriptions-item>
      </el-descriptions>

      <el-alert v-if="run.errorMessage" :title="run.errorMessage" type="error" :closable="false" show-icon />
      <el-alert
        v-if="sessionRevoked"
        title="数据清理已撤销当前会话"
        description="执行结果已保留。请重新登录后返回本页查询状态；如文件隔离待续作，重新登录后可继续处理。"
        type="warning"
        :closable="false"
        show-icon
      />

      <div v-if="canResumeFiles" class="run-actions">
        <el-button type="warning" :loading="runLoading" :disabled="sessionRevoked" @click="resumeFiles"> 继续隔离文件 </el-button>
        <span v-if="sessionRevoked">重新登录后可执行续作</span>
      </div>

      <div v-if="Object.keys(run.recovery || {}).length" class="recovery-panel">
        <h4>恢复信息</h4>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item v-for="item in recoveryRows" :key="item.key" :label="item.label">
            {{ item.value }}
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts" name="dataMaintenance">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { Delete, Refresh } from "@element-plus/icons-vue";
import { LOGIN_URL } from "@/config";
import { clearStoredAuthSession } from "@/api/modules/authToken";
import {
  getDataPurgePreviewApi,
  getDataPurgeRunApi,
  resumeDataPurgeFilesApi,
  startDataPurgeApi,
  type DataPurgePreview,
  type DataPurgeRun
} from "@/api/modules/clinic";
import { useUserStore } from "@/stores/modules/user";

const userStore = useUserStore();
const router = useRouter();
const LAST_PURGE_RUN_KEY = "clinic-last-data-purge-run";
const previewLoading = ref(false);
const runLoading = ref(false);
const executing = ref(false);
const sessionRevoked = ref(false);
const preview = ref<DataPurgePreview>();
const run = ref<DataPurgeRun>();
const password = ref("");
const confirmationText = ref("");

const isAdmin = computed(() => userStore.userInfo.role === "admin");
const totalRecords = computed(() =>
  ["patientBusinessRows", "queueRows", "tcmPharmacyRows", "aiRows", "inventoryRows", "sessions"].reduce(
    (total, key) => total + Number(preview.value?.counts?.[key] || 0),
    Math.max(Number(preview.value?.counts?.accounts || 0) - 1, 0)
  )
);
const countRows = computed(() =>
  Object.entries(preview.value?.counts || {}).map(([key, count]) => ({ key, label: fieldLabel(key), count }))
);
const retainedRows = computed(() => objectRows(preview.value?.retained || {}));
const recoveryRows = computed(() => objectRows(run.value?.recovery || {}));
const normalizedStatus = computed(() => String(run.value?.status || "").toUpperCase());
const canExecute = computed(
  () =>
    Boolean(preview.value?.token) &&
    Boolean(password.value) &&
    confirmationText.value === preview.value?.confirmationText &&
    !executing.value &&
    !sessionRevoked.value
);
const canResumeFiles = computed(
  () =>
    normalizedStatus.value === "FILES_PENDING" ||
    (run.value?.databaseCommitted && !run.value?.filesQuarantined && Boolean(run.value?.recovery?.canResumeFiles))
);
const runStatusLabel = computed(() => {
  const labels: Record<string, string> = {
    PENDING: "等待执行",
    RUNNING: "执行中",
    BACKING_UP: "正在生成保护备份",
    PURGING_DATABASE: "正在清理数据库",
    DATABASE_COMMITTED: "数据库已清理",
    FILES_PENDING: "文件隔离待续作",
    SUCCEEDED: "已完成",
    SUCCESS: "已完成",
    COMPLETED: "已完成",
    FAILED: "执行失败",
    PARTIAL_FAILED: "部分完成"
  };
  return labels[normalizedStatus.value] || run.value?.status || "未知";
});
const runStatusType = computed(() => {
  if (["SUCCEEDED", "SUCCESS", "COMPLETED"].includes(normalizedStatus.value)) return "success";
  if (["FAILED", "PARTIAL_FAILED"].includes(normalizedStatus.value)) return "danger";
  return "warning";
});
const runStep = computed(() => {
  if (["SUCCEEDED", "SUCCESS", "COMPLETED"].includes(normalizedStatus.value)) return 4;
  if (run.value?.filesQuarantined) return 3;
  if (run.value?.databaseCommitted) return 2;
  if (run.value?.backupDir) return 1;
  return 0;
});

const labels: Record<string, string> = {
  patientCases: "患者档案",
  encounters: "就诊记录",
  patientBusinessRows: "患者与病历业务数据",
  queueRows: "叫号与打印任务",
  tcmPharmacyRows: "中药房业务",
  aiRows: "AI 助手日志",
  inventoryRows: "进销存业务",
  patients: "患者与就诊",
  medicalRecords: "病历与归档",
  preAi: "前置事实采集",
  queue: "叫号与打印任务",
  tcmPharmacy: "中药房业务",
  inventory: "进销存业务",
  accounts: "测试账号",
  sessions: "登录会话",
  auditLogs: "业务审计",
  aiLogs: "AI 助手日志",
  snapshots: "数据库快照",
  departments: "科室配置",
  locations: "库存库位"
};

function fieldLabel(key: string) {
  return labels[key] || key.replace(/([A-Z])/g, " $1").trim();
}

function formatValue(value: unknown): string {
  if (Array.isArray(value)) return value.map(formatValue).join("、") || "无";
  if (value && typeof value === "object") return JSON.stringify(value);
  if (typeof value === "boolean") return value ? "是" : "否";
  if (value === null || value === undefined || value === "") return "无";
  return String(value);
}

function objectRows(value: Record<string, unknown>) {
  return Object.entries(value).map(([key, item]) => ({ key, label: fieldLabel(key), value: formatValue(item) }));
}

function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`;
}

function formatDateTime(value?: string) {
  if (!value) return "未记录";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}

const loadRun = async (requestedRunId?: string) => {
  const runId = requestedRunId || run.value?.runId || localStorage.getItem(LAST_PURGE_RUN_KEY) || "";
  if (!runId) return;
  runLoading.value = true;
  try {
    const { data } = await getDataPurgeRunApi(runId);
    run.value = data;
    localStorage.setItem(LAST_PURGE_RUN_KEY, data.runId);
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    runLoading.value = false;
  }
};

const resumeFiles = async () => {
  if (!run.value?.runId || sessionRevoked.value) return;
  runLoading.value = true;
  try {
    const { data } = await resumeDataPurgeFilesApi(run.value.runId);
    run.value = data;
    ElMessage.success("文件隔离续作已完成");
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    runLoading.value = false;
  }
};

const goToLogin = () => router.replace(LOGIN_URL);

const loadPreview = async () => {
  previewLoading.value = true;
  try {
    const { data } = await getDataPurgePreviewApi();
    preview.value = data;
    confirmationText.value = "";
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    previewLoading.value = false;
  }
};

const executePurge = async () => {
  if (!preview.value || !canExecute.value) return;
  await ElMessageBox.confirm("系统将先生成不可自动淘汰的保护备份，再删除当前测试业务数据。确认继续吗？", "最终确认", {
    confirmButtonText: "确认备份并清理",
    cancelButtonText: "取消",
    type: "error"
  });
  executing.value = true;
  try {
    const { data } = await startDataPurgeApi({
      password: password.value,
      previewToken: preview.value.token,
      confirmationText: confirmationText.value
    });
    password.value = "";
    confirmationText.value = "";
    run.value = data;
    localStorage.setItem(LAST_PURGE_RUN_KEY, data.runId);
    executing.value = false;
    sessionRevoked.value = true;
    clearStoredAuthSession();
    if (data.status === "COMPLETED") ElMessage.success("测试数据清理已完成，保护备份已保留");
    else ElMessage.warning(`数据清理返回状态：${runStatusLabel.value}`);
  } catch (error) {
    executing.value = false;
    ElMessage.error((error as Error).message);
  }
};

onMounted(() => {
  if (!isAdmin.value) return;
  loadPreview();
  loadRun();
});
</script>

<style scoped lang="scss">
.access-denied,
.data-maintenance-page {
  min-height: 100%;
}

.data-maintenance-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.page-header,
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.page-header {
  h2,
  p {
    margin: 0;
  }

  h2 {
    font-size: 20px;
    letter-spacing: 0;
  }

  p {
    margin-top: 6px;
    color: var(--el-text-color-secondary);
  }
}

.maintenance-section {
  padding: 18px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;

  > .el-alert {
    margin-top: 12px;
  }
}

.section-header {
  margin-bottom: 16px;

  span {
    display: block;
    margin-bottom: 4px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  h3 {
    margin: 0;
    font-size: 17px;
    letter-spacing: 0;
  }
}

.summary-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-bottom: 16px;
  border: 1px solid var(--el-border-color-lighter);

  div {
    min-width: 0;
    padding: 14px;
    border-right: 1px solid var(--el-border-color-lighter);

    &:last-child {
      border-right: 0;
    }
  }

  span,
  strong {
    display: block;
  }

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  strong {
    margin-top: 6px;
    overflow-wrap: anywhere;
    font-size: 17px;
  }
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 16px;

  h4 {
    margin: 0 0 10px;
  }
}

.path-list {
  min-height: 100px;
  padding: 12px 12px 12px 32px;
  margin: 0;
  color: var(--el-text-color-regular);
  line-height: 1.8;
  background: var(--el-fill-color-lighter);

  li {
    overflow-wrap: anywhere;
  }
}

.confirm-form {
  max-width: 620px;
}

.run-section {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.run-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--el-text-color-secondary);
}

.recovery-panel h4 {
  margin: 0 0 10px;
}

@media (max-width: 820px) {
  .summary-strip,
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .summary-strip div {
    border-right: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);

    &:last-child {
      border-bottom: 0;
    }
  }
}

@media (max-width: 620px) {
  .page-header,
  .section-header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
