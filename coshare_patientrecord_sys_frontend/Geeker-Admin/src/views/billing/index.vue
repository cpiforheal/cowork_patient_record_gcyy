<template>
  <div class="billing-workspace">
    <header class="billing-header">
      <div>
        <h2>患者收费信息</h2>
        <p>仅展示收费登记所需的四项基础信息；临床资料不对收费岗位开放。</p>
      </div>
      <el-input
        v-model="keyword"
        class="billing-search"
        clearable
        placeholder="按姓名或身份证号搜索"
        :prefix-icon="Search"
        @input="onSearch"
      />
    </header>

    <div v-if="loading" class="billing-loading" v-loading="loading" element-loading-text="正在查询患者信息…" />

    <el-empty v-else-if="!patients.length" description="未找到患者：请确认姓名或身份证号输入无误" />

    <div v-else class="billing-cards">
      <article v-for="patient in patients" :key="patient.id" class="billing-card">
        <div class="card-head">
          <span class="patient-name">{{ patient.patientName || "（未登记姓名）" }}</span>
          <el-tag effect="plain" type="info">可收费登记</el-tag>
        </div>
        <dl class="info-grid">
          <div class="info-item identity">
            <dt>身份证号</dt>
            <dd>
              <strong>{{ patient.identityNumber || "待登记" }}</strong>
              <el-button
                v-if="patient.identityNumber"
                link
                type="primary"
                :icon="CopyDocument"
                @click="copyText(patient.identityNumber, '身份证号')"
                >复制</el-button
              >
            </dd>
          </div>
          <div class="info-item">
            <dt>家庭住址</dt>
            <dd>
              <strong>{{ patient.address || "待登记" }}</strong>
              <el-button
                v-if="patient.address"
                link
                type="primary"
                :icon="CopyDocument"
                @click="copyText(patient.address, '家庭住址')"
                >复制</el-button
              >
            </dd>
          </div>
          <div class="info-item">
            <dt>联系电话</dt>
            <dd>
              <strong>{{ patient.phone || "待登记" }}</strong>
              <el-button
                v-if="patient.phone"
                link
                type="primary"
                :icon="CopyDocument"
                @click="copyText(patient.phone, '联系电话')"
                >复制</el-button
              >
            </dd>
          </div>
        </dl>
        <footer class="card-foot">信息更新于 {{ formatTime(patient.updatedAt) }}</footer>
      </article>
    </div>
  </div>
</template>

<script setup lang="ts" name="billingPatients">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { CopyDocument, Search } from "@element-plus/icons-vue";
import { getBillingPatientsApi, type BillingPatientInfo } from "@/api/modules/clinic/billing";

const keyword = ref("");
const patients = ref<BillingPatientInfo[]>([]);
const loading = ref(false);
let searchTimer: ReturnType<typeof setTimeout> | undefined;

const load = async () => {
  loading.value = true;
  try {
    const { data } = await getBillingPatientsApi(keyword.value.trim());
    patients.value = data.patients || [];
  } catch (error: any) {
    ElMessage.error(error?.message || "患者信息查询失败");
  } finally {
    loading.value = false;
  }
};

const onSearch = () => {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(load, 350);
};

const copyText = async (text: string, label: string) => {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success(`${label}已复制`);
  } catch {
    ElMessage.warning("复制失败，请手动选择文本复制");
  }
};

const formatTime = (value: string) => (value || "").replace("T", " ").slice(0, 16) || "—";

onMounted(load);
</script>

<style scoped lang="scss">
.billing-workspace {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 18px 22px;
}

.billing-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;

  h2 {
    margin: 0;
    font-size: 22px;
    color: var(--el-text-color-primary);
  }

  p {
    margin: 6px 0 0;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }

  .billing-search {
    width: min(360px, 100%);
  }
}

.billing-loading {
  min-height: 320px;
  border-radius: 12px;
  background: var(--el-fill-color-lighter);
}

.billing-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 14px;
}

.billing-card {
  padding: 16px 18px;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  border-left: 5px solid var(--el-color-primary);
  border-radius: 12px;
  transition: box-shadow 0.2s ease;

  &:hover {
    box-shadow: var(--el-box-shadow-light);
  }

  .card-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    margin-bottom: 12px;
    padding-bottom: 10px;
    border-bottom: 1px dashed var(--el-color-primary-light-5);

    .patient-name {
      font-size: 26px;
      font-weight: 700;
      color: var(--el-color-primary-dark-2);
      letter-spacing: 2px;
    }
  }

  .info-grid {
    display: grid;
    gap: 10px;
    margin: 0;
  }

  .info-item {
    dt {
      margin-bottom: 2px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }

    dd {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0;

      strong {
        font-size: 16px;
        font-variant-numeric: tabular-nums;
        word-break: break-all;
        color: var(--el-text-color-primary);
      }
    }

    &.identity strong {
      font-family: "JetBrains Mono", Consolas, monospace;
      letter-spacing: 1px;
    }
  }

  .card-foot {
    margin-top: 10px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}
</style>
