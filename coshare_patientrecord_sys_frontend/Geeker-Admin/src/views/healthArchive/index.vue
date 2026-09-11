<template>
  <div class="health-archive-page">
    <header class="hap-head">
      <div class="hap-title">
        <strong>住院 · 门诊患者健康档案</strong>
        <small>入院确认为住院即由护理部维护跟进 · 门诊患者同步建档 · 点击卡片背面进入档案维护</small>
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
      <FlipCard v-for="item in filteredCases" :key="item.id" class="hap-flip-card" :data-patient-case-id="item.id">
        <template #front>
          <div class="hap-front">
            <header class="hap-front-head">
              <strong class="hap-name">{{ item.patientName || "待补姓名" }}</strong>
              <el-tag size="small" :type="careTagType(item)" effect="dark">{{ careLabel(item) }}</el-tag>
              <el-tag size="small" effect="plain" round>{{ item.visitCount }} 次来访</el-tag>
            </header>
            <div class="hap-front-lines">
              <span class="hap-line hap-phone">📞 {{ item.patient?.phone || "手机号未登记" }}</span>
              <span class="hap-line hap-address">📍 {{ item.patient?.address || "住址未登记" }}</span>
            </div>
            <div class="hap-front-tags">
              <span v-for="disease in diseasesOf(item)" :key="disease" class="hap-tag-disease">{{ disease }}</span>
            </div>
            <div class="hap-front-date">🗓 接诊日期 {{ visitDate(item) || "—" }}</div>
            <footer class="hap-front-foot">
              <span>{{ item.latestEncounter?.caseToken || "尚无子病历" }}</span>
              <small>{{ item.updatedAt?.replace("T", " ").slice(0, 16) || "" }}</small>
            </footer>
            <small class="hap-front-hint">hover 翻面 · 背面进入健康档案维护</small>
          </div>
        </template>
        <template #back>
          <div class="hap-back">
            <div class="hap-back-facts">
              <p><label>登记主诉</label>{{ truncate(complaintOf(item), 60) || "—" }}</p>
              <p><label>病种方向</label>{{ diseaseDirectionOf(item) || "—" }}</p>
              <p><label>接诊日期</label>{{ visitDate(item) || "—" }}</p>
              <p><label>来访次数</label>{{ item.visitCount }} 次</p>
            </div>
            <div class="hap-back-actions">
              <el-button type="primary" :disabled="!item.latestEncounter" @click="openArchive(item)">
                进入健康管理档案
              </el-button>
            </div>
            <small class="hap-back-note">打开后可维护档案内容、保存草稿并生成合并文档</small>
          </div>
        </template>
      </FlipCard>
    </div>

    <HealthArchiveDialog v-model="archiveVisible" :encounter-id="activeEncounterId" :encounter-patient-name="activePatientName" />
  </div>
</template>

<script setup lang="ts" name="healthArchive">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh, Search } from "@element-plus/icons-vue";
import FlipCard from "@/components/inspira/FlipCard.vue";
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
const diseaseDirectionOf = (item: PreAiPatientCase) => {
  const directions = Array.isArray(item.patient?.diseaseDirections)
    ? item.patient.diseaseDirections.map(String).filter(Boolean).slice(0, 2).join(" / ")
    : String(item.patient?.diseaseDirection || item.patient?.inspectionDiseaseDirections || "").trim();
  return directions || diseasesOf(item).join(" / ");
};
const complaintOf = (item: PreAiPatientCase) =>
  String(
    item.patient?.registrationChiefComplaint || item.patient?.registrationSymptoms || item.patient?.chiefComplaint || ""
  ).trim();
const visitDate = (item: PreAiPatientCase) =>
  String(item.patient?.visitDate || item.latestEncounter?.visitDate || "")
    .replace("T", " ")
    .slice(0, 16);
const truncate = (value: string, maxLength = 60) => {
  const text = String(value || "").trim();
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
};

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
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
  min-height: 300px;
}
.hap-flip-card {
  height: 300px;
  border-radius: 16px;
}
.hap-flip-card :deep(.flip-card-face) {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-left: 4px solid transparent;
  border-radius: 16px;
  box-shadow: 0 10px 24px rgb(15 23 42 / 6%);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}
.hap-flip-card:hover :deep(.flip-card-face) {
  border-color: var(--el-color-primary-light-3);
  box-shadow: 0 14px 30px rgb(0 150 136 / 15%);
}
.hap-front {
  display: grid;
  grid-template-rows: auto auto 1fr auto auto;
  gap: 10px;
  height: 100%;
  padding: 16px;
  cursor: default;
}
.hap-front-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  .hap-name {
    overflow: hidden;
    font-size: 18px;
    color: var(--el-text-color-primary);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
.hap-front-lines {
  display: grid;
  gap: 6px;
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
  color: var(--el-text-color-regular);
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  white-space: normal;
}
.hap-front-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-content: start;
  .hap-tag-disease {
    padding: 3px 10px;
    font-size: 12px;
    font-weight: 600;
    color: #92400e;
    background: #fef3c7;
    border-radius: 999px;
  }
}
.hap-front-date {
  font-size: 13px;
  color: var(--el-color-success);
}
.hap-front-foot {
  display: grid;
  gap: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.hap-front-hint {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  text-align: center;
}
.hap-back {
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto auto;
  gap: 12px;
  height: 100%;
  padding: 16px;
  overflow: hidden;
  background: color-mix(in srgb, var(--el-color-primary) 5%, var(--el-bg-color));
  border-radius: inherit;
}
.hap-back-facts {
  display: grid;
  gap: 8px;
  align-content: start;
  p {
    margin: 0;
    font-size: 13px;
    line-height: 1.6;
    color: var(--el-text-color-primary);
  }
  label {
    margin-right: 8px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}
.hap-back-actions {
  display: flex;
  :deep(.el-button) {
    width: 100%;
  }
}
.hap-back-note {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
</style>
