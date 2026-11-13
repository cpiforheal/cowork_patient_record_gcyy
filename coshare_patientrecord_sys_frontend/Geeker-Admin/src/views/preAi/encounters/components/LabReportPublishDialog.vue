<template>
  <el-dialog v-model="visible" title="发布检验报告给患者" width="460px" destroy-on-close>
    <div class="lp-report">
      <strong>{{ reportLabel }}</strong>
      <span>患者扫码后仅可查看本份报告，有效期 24 小时</span>
    </div>

    <el-alert type="warning" :closable="false" show-icon class="lp-warn">
      <template #title>报告中含敏感医疗信息。发布前请确认指标与参考范围已核对，危急值已按院内流程处理。</template>
    </el-alert>

    <div v-if="qrDataUrl" class="lp-qr">
      <img :src="qrDataUrl" alt="患者查看二维码" />
      <div class="lp-qr-side">
        <label>患者链接</label>
        <el-input :model-value="publishedUrl" readonly size="small" />
        <div class="lp-qr-actions">
          <el-button size="small" @click="copyLink">复制链接</el-button>
          <el-button size="small" type="danger" plain @click="revoke">撤销访问</el-button>
        </div>
        <small>失效时间：{{ publishedExpiresAt }}</small>
        <small>推送状态：{{ pushLabel }}</small>
      </div>
    </div>

    <div v-else-if="latest" class="lp-current">
      <span>当前已有发布记录</span>
      <small>有效期至 {{ latest.expiresAt }} · 推送状态：{{ pushStatusLabel(latest.pushStatus) }}</small>
      <div class="lp-current-actions">
        <el-button size="small" @click="loadLatest">刷新状态</el-button>
        <el-button size="small" type="danger" plain @click="revoke">撤销访问</el-button>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :loading="publishing" @click="publish">
        {{ latest ? "重新生成二维码" : "生成二维码" }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts" name="LabReportPublishDialog">
import { computed, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import QRCode from "qrcode";
import {
  getLabPortalPublicationsApi,
  publishLabReportApi,
  revokeLabPortalPublicationApi,
  type LabPortalPublicationRow
} from "@/api/modules/clinic/preAi";

const props = defineProps<{
  modelValue: boolean;
  encounterId: string;
  reportId: string;
  reportLabel: string;
}>();

const emit = defineEmits<{ "update:modelValue": [value: boolean] }>();

const visible = computed({
  get: () => props.modelValue,
  set: value => emit("update:modelValue", value)
});

const latest = ref<LabPortalPublicationRow>();
const qrDataUrl = ref("");
const publishedUrl = ref("");
const publishedExpiresAt = ref("");
const pushStatus = ref("");
const publishing = ref(false);

const pushStatusLabel = (status: string) =>
  ({ PENDING: "待推送", SENT: "已推送", FAILED: "推送失败" })[status] || status || "—";
const pushLabel = computed(() => pushStatusLabel(pushStatus.value));

watch(
  () => props.modelValue,
  value => {
    if (!value) return;
    qrDataUrl.value = "";
    publishedUrl.value = "";
    void loadLatest();
  }
);

const loadLatest = async () => {
  if (!props.encounterId) return;
  try {
    const { data } = await getLabPortalPublicationsApi(props.encounterId);
    latest.value = data.list.find(item => item.reportId === props.reportId);
    if (latest.value) pushStatus.value = latest.value.pushStatus;
  } catch (error: any) {
    ElMessage.error(error?.message || "发布状态加载失败");
  }
};

const publish = async () => {
  if (!props.encounterId || !props.reportId) return;
  if (latest.value && !latest.value.revokedAt) {
    try {
      await ElMessageBox.confirm("重新生成后，之前发给患者的二维码会立即失效。是否继续？", "重新生成二维码", {
        type: "warning",
        confirmButtonText: "继续",
        cancelButtonText: "取消"
      });
    } catch {
      return;
    }
  }
  publishing.value = true;
  try {
    const { data } = await publishLabReportApi(props.encounterId, props.reportId);
    publishedUrl.value = data.url;
    publishedExpiresAt.value = data.expiresAt;
    pushStatus.value = data.pushStatus;
    qrDataUrl.value = await QRCode.toDataURL(data.url, { width: 520, margin: 1 });
    ElMessage.success("已生成患者二维码");
    await loadLatest();
  } catch (error: any) {
    ElMessage.error(error?.message || "发布失败");
  } finally {
    publishing.value = false;
  }
};

const revoke = async () => {
  if (!latest.value) return;
  try {
    await ElMessageBox.confirm("撤销后患者链接会立即失效，是否继续？", "撤销患者访问", {
      type: "warning",
      confirmButtonText: "撤销",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  try {
    await revokeLabPortalPublicationApi(latest.value.id);
    qrDataUrl.value = "";
    publishedUrl.value = "";
    ElMessage.success("已撤销");
    await loadLatest();
  } catch (error: any) {
    ElMessage.error(error?.message || "撤销失败");
  }
};

const copyLink = async () => {
  if (!publishedUrl.value) return;
  try {
    await navigator.clipboard.writeText(publishedUrl.value);
    ElMessage.success("链接已复制");
  } catch {
    ElMessage.warning("复制失败，请手动选择链接复制");
  }
};
</script>

<style scoped lang="scss">
.lp-report {
  display: grid;
  gap: 4px;
  margin-bottom: 12px;
  strong {
    font-size: 15px;
  }
  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}
.lp-warn {
  margin-bottom: 14px;
}
.lp-qr {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 14px;
  align-items: center;
  img {
    width: 180px;
    height: 180px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 10px;
    padding: 6px;
    background: #fff;
  }
}
.lp-qr-side {
  display: grid;
  gap: 8px;
  label {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}
.lp-qr-actions,
.lp-current-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.lp-current {
  display: grid;
  gap: 6px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}
.lp-current-actions {
  margin-top: 4px;
}
@media (max-width: 520px) {
  .lp-qr {
    grid-template-columns: 1fr;
    justify-items: center;
  }
}
</style>
