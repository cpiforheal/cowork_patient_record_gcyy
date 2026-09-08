# -*- coding: utf-8 -*-
"""One-shot patcher for disease AI tagging frontend changes."""
import io

BASE = r"E:\新建文件夹\hos_cowork\cowork_patient_record_gcyy-main\cowork_patient_record_gcyy\coshare_patientrecord_sys_frontend\Geeker-Admin\src"

# 1. preAi.ts API
p = BASE + r"\api\modules\clinic\preAi.ts"
t = io.open(p, encoding="utf-8").read()
anchor = (
    'export const withdrawPreAiFollowUpApi = (encounterId: string) =>\n'
    '  jsonRequest<unknown>(`/pre-ai/encounters/${encodeURIComponent(encounterId)}/follow-up/withdraw`, "POST", {});'
)
add = anchor + '\n\n/** AI 病种归类（仅管理员）：批量阅读登记主诉归类预置病种并缓存标签 */\nexport const runDiseaseTaggingApi = () =>\n  jsonRequest<{ started: boolean; message: string }>("/pre-ai/patients/disease-tags/run", "POST", {});'
assert anchor in t, "preAi anchor missing"
t = t.replace(anchor, add, 1)
io.open(p, "w", encoding="utf-8", newline="").write(t)
print("preAi.ts patched")

# 2. home/index.vue
p = BASE + r"\views\home\index.vue"
t = io.open(p, encoding="utf-8").read()

old_map = """const diseasesKeyMap = computed(() => {
  const map = new Map<string, string[]>();
  preAiCases.value.forEach(cases => {
    const diseases = cases.patient?.clinicalTemplateDiseases;
    if (!Array.isArray(diseases) || !diseases.length) return;
    const names = diseases.map(item => String(item)).filter(Boolean);
    if (!names.length) return;
    if (cases.sourcePatientId) map.set(cases.sourcePatientId, names);
    if (cases.patientName) map.set(cases.patientName, names);
  });
  return map;
});
const diseaseStats = computed(() => {
  if (!preAiCases.value.length) return [];
  const patientsByDisease = new Map<string, Set<string>>();
  for (let offset = trendRange.value - 1; offset >= 0; offset--) {
    const date = new Date();
    date.setDate(date.getDate() - offset);
    const dateText = toDateText(date);
    for (const patient of patientsByEncounterDate.value.get(dateText) || []) {
      const key = patient.id || patient.name;
      for (const disease of diseasesKeyMap.value.get(patient.id) || diseasesKeyMap.value.get(patient.name) || []) {
        if (!patientsByDisease.has(disease)) patientsByDisease.set(disease, new Set());
        patientsByDisease.get(disease)!.add(key);
      }
    }
  }
  return [...patientsByDisease.entries()]
    .map(([disease, patients]) => ({ disease, count: patients.size }))
    .sort((a, b) => b.count - a.count);
});"""
new_map = """const diseasesKeyMap = computed(() => {
  const map = new Map<string, string[]>();
  preAiCases.value.forEach(cases => {
    const templateDiseases = Array.isArray(cases.patient?.clinicalTemplateDiseases)
      ? cases.patient.clinicalTemplateDiseases.map(String)
      : [];
    const aiTags = Array.isArray(cases.patient?.aiDiseaseTags) ? cases.patient.aiDiseaseTags.map(String) : [];
    // 模板明确分类（医生确认）与 AI 归类标签合并去重
    const names = [...new Set([...templateDiseases, ...aiTags])].filter(Boolean);
    if (!names.length) return;
    if (cases.sourcePatientId) map.set(cases.sourcePatientId, names);
    if (cases.patientName) map.set(cases.patientName, names);
  });
  return map;
});
const diseaseStats = computed(() => {
  if (!preAiCases.value.length) return [];
  const windowKeys = new Set<string>();
  const patientsByDisease = new Map<string, Set<string>>();
  for (let offset = trendRange.value - 1; offset >= 0; offset--) {
    const date = new Date();
    date.setDate(date.getDate() - offset);
    const dateText = toDateText(date);
    for (const patient of patientsByEncounterDate.value.get(dateText) || []) {
      const key = patient.id || patient.name;
      windowKeys.add(key);
      for (const disease of diseasesKeyMap.value.get(patient.id) || diseasesKeyMap.value.get(patient.name) || []) {
        if (!patientsByDisease.has(disease)) patientsByDisease.set(disease, new Set());
        patientsByDisease.get(disease)!.add(key);
      }
    }
  }
  const stats = [...patientsByDisease.entries()]
    .map(([disease, patients]) => ({ disease, count: patients.size }))
    .sort((a, b) => b.count - a.count);
  // 待归类兜底：窗口总人数 - 已有任一病种标签的人数，保证与窗口合计对账
  const tagged = new Set<string>();
  patientsByDisease.values().forEach(keys => keys.forEach(key => tagged.add(key)));
  if (windowKeys.size > tagged.size) stats.push({ disease: "待归类", count: windowKeys.size - tagged.size });
  return stats;
});
const loadPreAiCases = async () => {
  try {
    const { data } = await getPreAiPatientCasesApi();
    preAiCases.value = data.list || [];
  } catch {
    preAiCases.value = [];
  }
};
const onRetagDiseases = async () => {
  try {
    const { data } = await runDiseaseTaggingApi();
    ElMessage[data.started ? "success" : "warning"](data.message || "AI 归类任务已启动");
    if (data.started) window.setTimeout(() => void loadPreAiCases(), 90_000);
  } catch (error) {
    ElMessage.error((error as Error).message || "触发 AI 归类失败");
  }
};"""
assert old_map in t, "home map anchor missing"
t = t.replace(old_map, new_map, 1)

old_load = """  // 管理员额外加载前置病例（登记主诉）供曲线悬浮词典卡使用；失败静默不打扰主视图
  if (isAdmin.value) {
    try {
      const { data } = await getPreAiPatientCasesApi();
      preAiCases.value = data.list || [];
    } catch {
      preAiCases.value = [];
    }
  }
};"""
new_load = """  // 管理员额外加载前置病例（登记主诉/病种标签）供曲线词典卡与病种分布使用；失败静默不打扰主视图
  if (isAdmin.value) await loadPreAiCases();
};"""
assert old_load in t, "home load anchor missing"
t = t.replace(old_load, new_load, 1)

old_imp = 'import { getPreAiPatientCasesApi, type PreAiPatientCase } from "@/api/modules/clinic/preAi";'
new_imp = 'import { getPreAiPatientCasesApi, runDiseaseTaggingApi, type PreAiPatientCase } from "@/api/modules/clinic/preAi";'
assert old_imp in t, "home import anchor missing"
t = t.replace(old_imp, new_imp, 1)

old_curve = '<DailyPatientCurve :items="dailyCurveItems" :disease-stats="diseaseStats" />'
new_curve = '<DailyPatientCurve :items="dailyCurveItems" :disease-stats="diseaseStats" is-admin @retag="onRetagDiseases" />'
assert old_curve in t, "home curve anchor missing"
t = t.replace(old_curve, new_curve, 1)
io.open(p, "w", encoding="utf-8", newline="").write(t)
print("home patched")

# 3. DailyPatientCurve.vue
p = BASE + r"\views\home\components\DailyPatientCurve.vue"
t = io.open(p, encoding="utf-8").read()

old_strip = """    <div v-if="diseaseStats?.length" class="disease-strip">
      <span class="disease-strip-title">病种分布</span>
      <span v-for="stat in diseaseStats" :key="stat.disease" class="disease-chip">
        {{ stat.disease }} <b>{{ stat.count }}</b> 人
      </span>
    </div>"""
new_strip = """    <div v-if="diseaseStats?.length" class="disease-strip">
      <span class="disease-strip-title">
        病种分布
        <button v-if="isAdmin" type="button" class="disease-retag" @click="emit('retag')">AI 归类</button>
      </span>
      <span
        v-for="stat in diseaseStats"
        :key="stat.disease"
        class="disease-chip"
        :class="{ 'is-pending': stat.disease === '待归类' }"
      >
        {{ stat.disease }} <b>{{ stat.count }}</b> 人
      </span>
    </div>"""
assert old_strip in t, "curve strip anchor missing"
t = t.replace(old_strip, new_strip, 1)

old_props = """    items: DailyCurveItem[];
    /** 病种分布统计（窗口内来访患者按预置病种模板分类去重计数，降序；仅管理员提供） */
    diseaseStats?: { disease: string; count: number }[];
  }>(),
  {
    title: "每日患者趋势图",
    subtitle: "按就诊日期统计 · 与每日患者数据粒度一致",
    diseaseStats: () => []
  }
);"""
new_props = """    items: DailyCurveItem[];
    /** 病种分布统计（窗口内来访患者按预置病种模板+AI归类合并去重计数，降序；仅管理员提供） */
    diseaseStats?: { disease: string; count: number }[];
    /** 是否管理员（决定 AI 归类按钮是否显示） */
    isAdmin?: boolean;
  }>(),
  {
    title: "每日患者趋势图",
    subtitle: "按就诊日期统计 · 与每日患者数据粒度一致",
    diseaseStats: () => [],
    isAdmin: false
  }
);
const emit = defineEmits<{ (event: "retag"): void }>();"""
assert old_props in t, "curve props anchor missing"
t = t.replace(old_props, new_props, 1)

old_style = """    b {
      margin: 0 2px;
      color: var(--el-color-primary);
      font-variant-numeric: tabular-nums;
    }
  }
}"""
new_style = """    &.is-pending {
      color: var(--hos-chart-muted, #74777d);
      border-style: dashed;
      b {
        color: var(--hos-chart-muted, #74777d);
      }
    }
    b {
      margin: 0 2px;
      color: var(--el-color-primary);
      font-variant-numeric: tabular-nums;
    }
  }
  .disease-retag {
    margin-left: 6px;
    padding: 2px 8px;
    font-size: 12px;
    color: var(--el-color-primary);
    cursor: pointer;
    background: transparent;
    border: 1px solid color-mix(in srgb, var(--el-color-primary) 40%, transparent);
    border-radius: 999px;
    &:hover {
      background: color-mix(in srgb, var(--el-color-primary) 8%, transparent);
    }
  }
}"""
assert old_style in t, "curve style anchor missing"
t = t.replace(old_style, new_style, 1)
io.open(p, "w", encoding="utf-8", newline="").write(t)
print("curve patched")
print("ALL_PATCHED")
