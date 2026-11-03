<template>
  <section class="quota-exception-review" v-loading="loading">
    <header class="qer-head">
      <div>
        <strong>定额例外审核</strong>
        <small>科室只能上报事实，最终补扣量由审核人确定；通过多少补扣多少。</small>
      </div>
      <div class="qer-actions">
        <el-date-picker v-model="businessDate" type="date" value-format="YYYY-MM-DD" clearable placeholder="业务日" />
        <el-select v-model="status" clearable placeholder="全部状态" style="width: 140px">
          <el-option label="待审核" value="PENDING" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="部分通过" value="PARTIALLY_APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <el-table :data="rows" size="small" border @row-click="handleRowClick">
      <el-table-column prop="departmentName" label="科室" width="100" />
      <el-table-column prop="businessDate" label="业务日" width="105" />
      <el-table-column prop="itemName" label="耗材" min-width="160" show-overflow-tooltip />
      <el-table-column label="定额" width="90" align="right">
        <template #default="{ row }">{{ formatQuantity(row.quotaQuantity) }}</template>
      </el-table-column>
      <el-table-column label="上报实际" width="100" align="right">
        <template #default="{ row }">{{ formatQuantity(row.reportedActualQuantity) }}</template>
      </el-table-column>
      <el-table-column label="超量" width="90" align="right">
        <template #default="{ row }">{{ formatQuantity(row.extraQuantity) }}</template>
      </el-table-column>
      <el-table-column label="已通过" width="90" align="right">
        <template #default="{ row }">{{ formatQuantity(row.approvedQuantity) }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
      <el-table-column label="照片" width="70" align="center">
        <template #default="{ row }">{{ row.photoCount || 0 }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click.stop="handleRowClick(row)">审核</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!rows.length" :image-size="70" description="没有待处理的定额例外" />

    <el-dialog v-model="detailVisible" title="定额例外审核" width="620px" destroy-on-close>
      <div v-if="detail" class="qer-detail">
        <div class="qer-detail-grid">
          <span>科室</span><b>{{ detail.departmentName }} · {{ detail.businessDate }}</b>
          <span>耗材</span><b>{{ detail.itemName }}（{{ detail.unit }}）</b>
          <span>当日定额</span><b>{{ formatQuantity(detail.quotaQuantity) }}</b>
          <span>上报实际</span><b>{{ formatQuantity(detail.reportedActualQuantity) }}</b>
          <span>上报超量</span><b>{{ formatQuantity(detail.extraQuantity) }}</b>
          <span>免审上浮</span><b>{{ formatQuantity(detail.toleranceQuantity) }}</b>
          <span>原因</span><b>{{ detail.reason }}</b>
        </div>
        <div v-if="photoUrls.length" class="qer-photos">
          <el-image
            v-for="(url, index) in photoUrls"
            :key="index"
            :src="url"
            :preview-src-list="photoUrls"
            fit="cover"
            class="qer-photo"
          />
        </div>
        <el-alert
          v-if="detail.status !== 'PENDING'"
          type="info"
          :closable="false"
          show-icon
          :title="`该例外已${statusLabel(detail.status)}，不能重复审核`"
        />
        <template v-else>
          <el-form label-position="top">
            <el-form-item label="通过补扣量" required>
              <el-input-number v-model="approvedQuantity" :min="0" :max="detail.extraQuantity" :precision="2" controls-position="right" />
              <small class="qer-hint">不能超过上报超量 {{ formatQuantity(detail.extraQuantity) }}；填 0 等同驳回。</small>
            </el-form-item>
            <el-form-item label="审核备注">
              <el-input v-model="reviewNote" type="textarea" :rows="2" placeholder="驳回原因或补充说明" />
            </el-form-item>
          </el-form>
        </template>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <template v-if="detail?.status === 'PENDING'">
          <el-button type="danger" plain :loading="deciding" @click="decide('REJECTED')">驳回</el-button>
          <el-button type="primary" :loading="deciding" @click="decide('APPROVED')">通过并补扣</el-button>
        </template>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import {
  decideInventoryQuotaExceptionApi,
  fetchInventoryQuotaExceptionPhotoApi,
  getInventoryQuotaExceptionDetailApi,
  getInventoryQuotaExceptionsApi,
  type InventoryQuotaException,
  type InventoryQuotaExceptionDetail
} from "@/api/modules/inventory";

const loading = ref(false);
const deciding = ref(false);
const rows = ref<InventoryQuotaException[]>([]);
const status = ref("");
const businessDate = ref("");
const detailVisible = ref(false);
const detail = ref<InventoryQuotaExceptionDetail>();
const approvedQuantity = ref(0);
const reviewNote = ref("");
const photoUrls = ref<string[]>([]);

const statusLabel = (value?: string) => {
  if (value === "APPROVED") return "已通过";
  if (value === "PARTIALLY_APPROVED") return "部分通过";
  if (value === "REJECTED") return "已驳回";
  return "待审核";
};
const statusTag = (value?: string) => {
  if (value === "APPROVED") return "success";
  if (value === "PARTIALLY_APPROVED") return "warning";
  if (value === "REJECTED") return "danger";
  return "primary";
};
const formatQuantity = (value?: number | null) => Number(value || 0).toFixed(2);

const load = async () => {  loading.value = true;
  try {
    const result = await getInventoryQuotaExceptionsApi({
      status: status.value || undefined,
      date: businessDate.value || undefined
    });
    rows.value = result.data.list ?? [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "例外列表加载失败");
  } finally {
    loading.value = false;
  }
};

const handleRowClick = (row: any) => openDetail(row as InventoryQuotaException);

const openDetail = async (row: InventoryQuotaException) => {
  try {
    const result = await getInventoryQuotaExceptionDetailApi(row.id);
    detail.value = result.data;
    approvedQuantity.value = Number(result.data.extraQuantity || 0);
    reviewNote.value = "";
    detailVisible.value = true;
    photoUrls.value = [];
    for (const photo of result.data.photos || []) {
      try {
        photoUrls.value.push(await fetchInventoryQuotaExceptionPhotoApi(photo.id));
      } catch {
        // 单张照片加载失败不阻断审核
      }
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "例外详情加载失败");
  }
};

const decide = async (decision: "APPROVED" | "REJECTED") => {
  if (!detail.value) return;
  deciding.value = true;
  try {
    const result = await decideInventoryQuotaExceptionApi(detail.value.id, {
      status: decision,
      approvedExtraQuantity: decision === "REJECTED" ? 0 : Number(approvedQuantity.value || 0),
      reviewNote: reviewNote.value
    });
    detail.value = result.data;
    ElMessage.success(decision === "REJECTED" ? "例外已驳回" : "已通过并按通过量补扣");
    detailVisible.value = false;
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "审核失败");
  } finally {
    deciding.value = false;
  }
};

onMounted(load);
</script>

<style scoped lang="scss">
.quota-exception-review {
  padding: 14px 16px;
}
.qer-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 12px;
  flex-wrap: wrap;
  strong {
    font-size: 15px;
  }
  small {
    display: block;
    margin-top: 2px;
    color: var(--hos-text-muted);
    font-size: 11px;
  }
}
.qer-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.qer-detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.qer-detail-grid {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 8px 12px;
  font-size: 13px;
  span {
    color: var(--hos-text-muted);
  }
  b {
    font-weight: 500;
    color: var(--hos-text-primary);
  }
}
.qer-photos {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.qer-photo {
  width: 96px;
  height: 96px;
  border-radius: 8px;
  border: 1px solid var(--hos-border-light);
}
.qer-hint {
  display: block;
  margin-top: 4px;
  color: var(--hos-text-muted);
  font-size: 11px;
}
</style>
