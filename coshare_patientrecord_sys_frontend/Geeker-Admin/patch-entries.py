# -*- coding: utf-8 -*-
"""Reroute health-archive entries to the standalone page; remove popup mounts."""
import io

BASE = r"E:\新建文件夹\hos_cowork\cowork_patient_record_gcyy-main\cowork_patient_record_gcyy\coshare_patientrecord_sys_frontend\Geeker-Admin\src"

# ---------- 1. DoctorReviewPanel.vue: remove reviewConfirmed gate ----------
p = BASE + r"\views\preAi\encounters\components\DoctorReviewPanel.vue"
t = io.open(p, encoding="utf-8", newline="").read().replace("\r\n", "\n")
old_btn = """        <el-tooltip :disabled="reviewConfirmed" content="请先完成最终医生复核" placement="top">
          <span>
            <el-button type="warning" plain :disabled="!reviewConfirmed" @click="$emit('openHealthArchive')">
              健康管理档案
            </el-button>
          </span>
        </el-tooltip>"""
new_btn = """        <el-button type="warning" plain @click="$emit('openHealthArchive')"> 健康管理档案 </el-button>"""
assert old_btn in t, "review panel button anchor missing"
t = t.replace(old_btn, new_btn, 1)
io.open(p, "w", encoding="utf-8", newline="").write(t)
print("review panel done")

# ---------- 2. encounters/index.vue: route entries + remove popup ----------
p = BASE + r"\views\preAi\encounters\index.vue"
t = io.open(p, encoding="utf-8", newline="").read().replace("\r\n", "\n")

old_open = """const healthArchiveVisible = ref(false);
const openHealthArchive = () => {
  healthArchiveVisible.value = true;
};"""
new_open = """// 健康管理档案已独立页面化（护理部入院即维护）：统一跳转新入口页
const openHealthArchive = () => {
  router.push({ path: "/health-archive", query: { encounterId: selectedEncounterId.value || "" } });
};"""
assert old_open in t, "openHealthArchive anchor missing"
t = t.replace(old_open, new_open, 1)

old_flip = """const openPatientHealthArchive = async (item: PreAiPatientCase) => {
  if (!item.latestEncounter) return;
  await selectPatientCase(item);
  openHealthArchive();
};"""
new_flip = """const openPatientHealthArchive = (item: PreAiPatientCase) => {
  if (!item.latestEncounter) return;
  router.push({ path: "/health-archive", query: { encounterId: item.latestEncounter.id } });
};"""
assert old_flip in t, "flip anchor missing"
t = t.replace(old_flip, new_flip, 1)

old_mount = """
      <HealthArchiveDialog
        v-model="healthArchiveVisible"
        :encounter-id="selectedEncounterId"
        :encounter-patient-name="recordChatPatientName"
        :workspace="workspace"
        @completed="loadTargetMedicalRecordVersions"
      />
"""
assert old_mount in t, "dialog mount anchor missing"
t = t.replace(old_mount, "\n", 1)

old_import = 'import HealthArchiveDialog from "./components/HealthArchiveDialog.vue";\n'
assert old_import in t, "dialog import anchor missing"
t = t.replace(old_import, "", 1)
io.open(p, "w", encoding="utf-8", newline="").write(t)
print("encounters rerouted")

# ---------- 3. AddressAnalysisPanel.vue: buttons route to new page, popup removed ----------
p = BASE + r"\views\home\components\AddressAnalysisPanel.vue"
t = io.open(p, encoding="utf-8", newline="").read().replace("\r\n", "\n")

old_footer = """          <el-button type="warning" plain :disabled="!coursePatient?.encounterId" @click="openHealthArchive(true)">
            健康管理档案
          </el-button>
          <el-button type="primary" :disabled="!coursePatient?.encounterId" @click="openHealthArchive(false)">
            进入完整档案
          </el-button>"""
new_footer = """          <el-button
            type="primary"
            :disabled="!coursePatient?.encounterId"
            @click="router.push({ path: '/health-archive', query: { encounterId: coursePatient?.encounterId || '' } })"
          >
            健康管理档案
          </el-button>"""
assert old_footer in t, "map footer anchor missing"
t = t.replace(old_footer, new_footer, 1)

# needs router in this component
old_router_imp = 'import { computed, onMounted, ref } from "vue";'
new_router_imp = 'import { computed, onMounted, ref } from "vue";\nimport { useRouter } from "vue-router";'
assert old_router_imp in t
t = t.replace(old_router_imp, new_router_imp, 1)
old_store = "const globalStore = useGlobalStore();"
new_store = "const globalStore = useGlobalStore();\nconst router = useRouter();"
assert old_store in t
t = t.replace(old_store, new_store, 1)

# remove popup mount + state
old_mount = """
      <HealthArchiveDialog
        v-model="healthArchiveVisible"
        :preview-only="healthArchivePreviewOnly"
        :encounter-id="coursePatient?.encounterId || ''"
        :encounter-patient-name="coursePatient?.name"
        :workspace="courseWorkspace || undefined"
      />
"""
assert old_mount in t, "map dialog mount anchor missing"
t = t.replace(old_mount, "\n", 1)

old_state = """const healthArchiveVisible = ref(false);
const healthArchivePreviewOnly = ref(true);
const openHealthArchive = (previewOnly: boolean) => {
  if (!coursePatient.value?.encounterId) return;
  healthArchivePreviewOnly.value = previewOnly;
  healthArchiveVisible.value = true;
};"""
assert old_state in t, "map state anchor missing"
t = t.replace(old_state, "", 1)

old_dlg_imp = 'import HealthArchiveDialog from "@/views/preAi/encounters/components/HealthArchiveDialog.vue";\n'
assert old_dlg_imp in t, "dialog import missing"
t = t.replace(old_dlg_imp, "", 1)
io.open(p, "w", encoding="utf-8", newline="").write(t)
print("map panel rerouted")
print("ALL_PATCHED")
