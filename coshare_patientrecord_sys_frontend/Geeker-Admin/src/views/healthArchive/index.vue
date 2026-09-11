<template>
  <div class="health-archive-page">
    <header class="hap-head">
      <div class="hap-title">
        <strong>住院 · 门诊患者健康档案</strong>
        <small>入院确认为住院即由护理部维护跟进 · 门诊患者同步建档 · 点击卡片进入档案维护</small>
      </div>
      <div class="hap-actions">
        <el-input v-model="keyword" placeholder="按姓名或病例编号搜索" clearable :prefix-icon="Search" style="width: 220px" />
        <el-segmented v-model="careFilter" :options="careFilterOptions" />
        <el-button :loading="loading" @click="loadCases">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </header>

    <div v-loading="loading" class="hap-grid">
      <el-empty v-if="!loading && !filteredCases.length" description="暂无患者，可点刷新或调整筛选" :image-size="72" />
      <FeyCard
        v-for="item in filteredCases"
        :key="item.id"
        class="hap-card"
        :class="{ 'is-disabled': !item.latestEncounter }"
        @click="openArchive(item)"
      >
        <header class="hap-head-row">
          <strong class="hap-name">{{ item.patientName || "待补姓名" }}</strong>
          <el-tag size="small" :type="careTagType(item)" effect="dark">{{ careLabel(item) }}</el-tag>
          <el-tag size="small" effect="plain" round>{{ item.visitCount }} 次来访</el-tag>
        </header>
        <div class="hap-lines">
          <span class="hap-line hap-phone">📞 {{ item.patient?.phone || "手机号未登记" }}</span>
          <span class="hap-line hap-address">📍 {{ item.patient?.address || "住址未登记" }}</span>
        </div>
        <div class="hap-tags">
          <span v-for="disease in diseasesOf(item)" :key="disease" class="hap-tag-disease">{{ disease }}</span>
        </div>
        <div class="hap-date">🗓 接诊日期 {{ visitDate(item) || "—" }}</div>
        <footer class="hap-foot">
          <span class="hap-token">{{ item.latestEncounter?.caseToken || "尚无子病历" }}</span>
          <small>{{ item.updatedAt?.replace("T", " ").slice(0, 16) || "" }}</small>
          <span class="hap-entry"
            >进入健康档案 <el-icon><ArrowRight /></el-icon
          ></span>
        </footer>
      </FeyCard>
    </div>

    <HealthArchiveDialog v-model="archiveVisible" :encounter-id="activeEncounterId" :encounter-patient-name="activePatientName" />
  </div>
</template>

<script setup lang="ts" name="healthArchive">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { ArrowRight, Refresh, Search } from "@element-plus/icons-vue";
import FeyCard from "@/components/inspira/FeyCard.vue";
import HealthArchiveDialog from "@/views/preAi/encounters/components/HealthArchiveDialog.vue";
import { getPreAiPatientCasesApi, type PreAiPatientCase } from "@/api/modules/clinic/preAi";

const cases = ref<PreAiPatientCase[]>([]);
const loading = ref(false);
const keyword = ref("");
const careFilter = ref("全部");
const careFilterOptions = ["全部", "住院", "门诊"];

const archiveVisible = ref(false);
const activeEncounterId = ref("");
const activePatientName = ref("");

const careTypeOf = (item: PreAiPatientCase) =>
  String(
    item.latestEncounter?.normalizedCareType || item.latestEncounter?.inventoryCareType || item.latestEncounter?.route || ""
  );
const careLabel = (item: PreAiPatientCase) => (careTypeOf(item).includes("inpatient") ? "住院" : "门诊");
const careTagType = (item: PreAiPatientCase): "warning" | "success" =>
  careTypeOf(item).includes("inpatient") ? "warning" : "success";
const diseasesOf = (item: PreAiPatientCase) => {
  const diseases = item.patient?.clinicalTemplateDiseases;
  return Array.isArray(diseases) ? diseases.map(String).filter(Boolean) : [];
};
const visitDate = (item: PreAiPatientCase) =>
  String(item.patient?.visitDate || item.latestEncounter?.visitDate || "")
    .replace("T", " ")
    .slice(0, 16);

const filteredCases = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  return cases.value.filter(item => {
    if (careFilter.value !== "全部" && careLabel(item) !== careFilter.value) return false;
    if (!kw) return true;
    return (
      String(item.patientName || "")
        .toLowerCase()
        .includes(kw) ||
      String(item.latestEncounter?.caseToken || "")
        .toLowerCase()
        .includes(kw)
    );
  });
});

const openArchive = (item: PreAiPatientCase) => {
  if (!item.latestEncounter) return;
  activeEncounterId.value = item.latestEncounter.id;
  activePatientName.value = item.patientName || "";
  archiveVisible.value = true;
};

const loadCases = async () => {
  loading.value = true;
  try {
    const { data } = await getPreAiPatientCasesApi();
    cases.value = data.list || [];
  } catch (error) {
    ElMessage.error((error as Error).message || "患者列表加载失败");
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  void loadCases();
});
</script>

<style scoped lang="scss">
.health-archive-page {
  display: grid;
  gap: 14px;
}
.hap-head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  .hap-title {
    display: grid;
    gap: 3px;
    strong {
      font-size: 17px;
      color: var(--el-text-color-primary);
    }
    small {
      color: var(--el-text-color-secondary);
    }
  }
  .hap-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }
}
.hap-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(310px, 1fr));
  gap: 14px;
  min-height: 300px;
}

// Fey Card 患者卡：点阵底 + 聚光灯 + hover 揭示入口
.hap-card {
  --fey-dot-color: rgb(15 23 42 / 9%);
  --fey-spotlight: color-mix(in srgb, var(--el-color-primary) 14%, transparent);

  min-height: 210px;
  cursor: pointer;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 16px;
  box-shadow: 0 10px 24px rgb(15 23 42 / 6%);
  transition:
    border-color 0.25s ease,
    box-shadow 0.25s ease,
    transform 0.25s ease;
  &:hover {
    border-color: color-mix(in srgb, var(--el-color-primary) 45%, var(--el-border-color-lighter));
    box-shadow: 0 16px 34px color-mix(in srgb, var(--el-color-primary) 16%, transparent);
    transform: translateY(-2px);
    .hap-entry {
      gap: 8px;
      color: var(--el-color-primary);
    }
  }
  &.is-disabled {
    cursor: not-allowed;
    opacity: 0.6;
  }
}
.hap-head-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.hap-name {
  overflow: hidden;
  font-size: 18px;
  font-weight: 700;
  color: var(--el-text-color-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hap-lines {
  display: grid;
  gap: 6px;
  margin-top: 2px;
}
.hap-line {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hap-phone {
  font-weight: 600;
  color: var(--el-color-primary);
}
.hap-address {
  display: -webkit-box;
  color: var(--el-text-color-secondary);
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  white-space: normal;
}
.hap-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  .hap-tag-disease {
    padding: 3px 10px;
    font-size: 12px;
    font-weight: 600;
    color: #92400e;
    background: #fef3c7;
    border-radius: 999px;
  }
}
.hap-date {
  font-size: 12px;
  color: var(--el-color-success);
}
.hap-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding-top: 10px;
  border-top: 1px dashed var(--el-border-color-lighter);
  .hap-token {
    overflow: hidden;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  small {
    color: var(--el-text-color-placeholder);
  }
  .hap-entry {
    display: inline-flex;
    flex: 0 0 auto;
    gap: 2px;
    align-items: center;
    font-size: 12px;
    font-weight: 600;
    color: var(--el-text-color-secondary);
    transition:
      color var(--motion-control, 180ms) var(--ease-out, ease),
      gap var(--motion-control, 180ms) var(--ease-out, ease);
  }
}
</style>
