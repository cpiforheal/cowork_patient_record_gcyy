<template>
  <div class="table-box template-page">
    <section class="template-summary">
      <div>
        <h2>病历模板规则中心</h2>
        <p>这里配置字段谁能写、是否必填、是否进质控、是否出现在导出病历中。</p>
      </div>
      <div class="summary-chips">
        <el-tag size="large" effect="plain">规则总数 {{ totalRuleCount }}</el-tag>
        <el-tag size="large" type="success" effect="plain">当前账号 {{ roleName }}</el-tag>
      </div>
    </section>

    <div class="main-box template-workspace">
      <TreeFilter
        id="id"
        label="title"
        title="病历章节"
        :data="sectionTree"
        :default-value="selectedSectionKey"
        @change="changeSection"
      >
        <template #default="{ row }">
          <span class="tree-node">
            <span class="tree-title">{{ row.data.title }}</span>
            <span class="node-count">{{ treeNodeCount(row.data) }}</span>
          </span>
        </template>
      </TreeFilter>

      <section class="table-box template-rule-panel">
        <div class="rule-panel-header">
          <div class="section-context">
            <h3>{{ activeSectionTitle }}</h3>
            <p>{{ activeSectionDesc }}</p>
          </div>
          <div class="rule-counts">
            <el-tag effect="plain">当前 {{ filteredRows.length }}/{{ currentSectionCount }}</el-tag>
            <el-tag type="danger" effect="plain">必填 {{ requiredCount }}</el-tag>
            <el-tag type="warning" effect="plain">质控 {{ qualityCount }}</el-tag>
            <el-tag type="info" effect="plain">停用 {{ disabledCount }}</el-tag>
          </div>
        </div>

        <el-alert
          class="rule-alert"
          title="本页规则会影响后端字段写入权限和质控必填校验；患者详情页的锁定视觉下一步会继续对齐这里。"
          type="info"
          show-icon
          :closable="false"
        />

        <div class="rule-toolbar">
          <el-input v-model="keyword" class="rule-search" clearable placeholder="搜索字段、科室、岗位" />
          <el-select v-model="departmentFilter" class="toolbar-select" clearable filterable placeholder="责任科室">
            <el-option v-for="item in departmentOptions" :key="item" :label="item" :value="item" />
          </el-select>
          <el-select v-model="enabledFilter" class="toolbar-select" clearable placeholder="字段状态">
            <el-option label="启用" value="enabled" />
            <el-option label="停用" value="disabled" />
          </el-select>
          <el-select v-model="qualityFilter" class="toolbar-select" clearable placeholder="质控规则">
            <el-option label="纳入" value="included" />
            <el-option label="不纳入" value="excluded" />
          </el-select>
          <el-button :icon="Refresh" @click="loadRules">刷新</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </div>

        <el-table v-loading="loading" :data="filteredRows" border stripe class="rule-table" empty-text="暂无匹配字段规则">
          <el-table-column type="index" label="#" width="58" />
          <el-table-column prop="sectionTitle" label="病历章节" min-width="160" show-overflow-tooltip />
          <el-table-column prop="fieldLabel" label="字段名称" min-width="150" show-overflow-tooltip />
          <el-table-column prop="department" label="责任科室" min-width="140" show-overflow-tooltip />
          <el-table-column label="可写岗位" min-width="180">
            <template #default="{ row }">
              <el-space wrap>
                <el-tag v-for="editor in editorTags(row)" :key="editor" effect="plain">{{ editor }}</el-tag>
              </el-space>
            </template>
          </el-table-column>
          <el-table-column label="必填" width="86" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.required" type="danger" effect="plain">必填</el-tag>
              <span v-else class="muted">按需</span>
            </template>
          </el-table-column>
          <el-table-column label="质控" width="92" align="center">
            <template #default="{ row }">
              <el-tag :type="row.qualityCheck ? 'warning' : 'info'" effect="plain">
                {{ row.qualityCheck ? "纳入" : "不纳入" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="导出" width="92" align="center">
            <template #default="{ row }">
              <el-tag :type="row.printable ? 'success' : 'info'" effect="plain">
                {{ row.printable ? "导出" : "不导出" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="92" align="center">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" effect="plain">
                {{ row.enabled ? "启用" : "停用" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="附件要求" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">
              <span :class="{ muted: !row.evidence }">{{ row.evidence || "无" }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" fixed="right" width="90">
            <template #default="{ row }">
              <el-button type="primary" link @click="openRuleDrawer(row)">配置</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <el-drawer v-model="drawerVisible" title="字段规则配置" size="580px" destroy-on-close>
      <div class="rule-drawer">
        <section class="rule-identity">
          <div>
            <strong>{{ ruleForm.fieldLabel }}</strong>
            <span>{{ ruleForm.sectionTitle }} · {{ ruleForm.stage }}</span>
          </div>
          <el-tag :type="ruleForm.enabled ? 'success' : 'info'" effect="plain">
            {{ ruleForm.enabled ? "启用" : "停用" }}
          </el-tag>
        </section>

        <el-form :model="ruleForm" label-width="112px">
          <el-form-item label="责任科室">
            <el-select v-model="ruleForm.department" filterable>
              <el-option v-for="item in departmentOptions" :key="item" :label="item" :value="item" />
            </el-select>
          </el-form-item>

          <el-form-item label="可写岗位">
            <el-select v-model="ruleForm.editors" multiple filterable collapse-tags collapse-tags-tooltip>
              <el-option v-for="item in roles" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>

          <el-form-item label="字段开关">
            <el-switch v-model="ruleForm.enabled" active-text="启用" inactive-text="停用" />
          </el-form-item>

          <el-form-item label="必填校验">
            <el-switch v-model="ruleForm.required" active-text="必填" inactive-text="按需" />
          </el-form-item>

          <el-form-item label="质控规则">
            <el-switch v-model="ruleForm.qualityCheck" active-text="纳入质控" inactive-text="不纳入" />
          </el-form-item>

          <el-form-item label="导出病历">
            <el-switch v-model="ruleForm.printable" active-text="导出" inactive-text="不导出" />
          </el-form-item>

          <el-form-item label="附件要求">
            <el-input v-model="ruleForm.evidence" type="textarea" :rows="3" placeholder="例如：需上传血常规原图；无要求可留空" />
          </el-form-item>
        </el-form>
      </div>

      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRule">保存规则</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts" name="recordTemplate">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import TreeFilter from "@/components/TreeFilter/index.vue";
import { getTemplateFieldRulesApi, saveTemplateFieldRuleApi, type TemplateFieldRule } from "@/api/modules/clinic";
import { USER_ROLE_OPTIONS, recordSections, roleLabel, type UserRole } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";

interface SectionTreeNode {
  id: string;
  title: string;
  stage: string;
  owner: string;
  count: number;
}

const userStore = useUserStore();
const drawerVisible = ref(false);
const loading = ref(false);
const ruleRows = ref<TemplateFieldRule[]>([]);
const selectedSectionKey = ref("");
const keyword = ref("");
const departmentFilter = ref("");
const enabledFilter = ref("");
const qualityFilter = ref("");

const currentRole = computed<UserRole>(() => (userStore.userInfo.role as UserRole) || "frontdesk");
const roleName = computed(() => roleLabel(currentRole.value));
const totalRuleCount = computed(() => ruleRows.value.length);

const departments = ["前台", "门诊", "化验室", "心电室", "B超/放射", "治疗室", "质控/病案", "信息/院办"];
const roles = USER_ROLE_OPTIONS;

const ruleForm = reactive<Partial<TemplateFieldRule>>({});

const sectionTree = computed<SectionTreeNode[]>(() =>
  recordSections.map(section => ({
    id: section.key,
    title: section.title,
    stage: section.stage,
    owner: section.owner,
    count: ruleRows.value.filter(rule => rule.sectionKey === section.key).length
  }))
);

const activeSection = computed(() => recordSections.find(section => section.key === selectedSectionKey.value));
const activeSectionTitle = computed(() => activeSection.value?.title || "全部章节");
const activeSectionDesc = computed(
  () => activeSection.value?.description || "按章节定位字段规则，集中管理写入权限、质控和导出规则。"
);

const currentSectionRows = computed(() => {
  if (!selectedSectionKey.value) return ruleRows.value;
  return ruleRows.value.filter(row => row.sectionKey === selectedSectionKey.value);
});

const filteredRows = computed(() => {
  const searchText = keyword.value.trim().toLowerCase();
  return currentSectionRows.value.filter(row => {
    const editorText = editorTags(row).join("、");
    const textMatched =
      !searchText ||
      [row.sectionTitle, row.fieldLabel, row.department, row.stage, editorText].some(item =>
        item.toLowerCase().includes(searchText)
      );
    const departmentMatched = !departmentFilter.value || row.department === departmentFilter.value;
    const enabledMatched =
      !enabledFilter.value ||
      (enabledFilter.value === "enabled" && row.enabled) ||
      (enabledFilter.value === "disabled" && !row.enabled);
    const qualityMatched =
      !qualityFilter.value ||
      (qualityFilter.value === "included" && row.qualityCheck) ||
      (qualityFilter.value === "excluded" && !row.qualityCheck);
    return textMatched && departmentMatched && enabledMatched && qualityMatched;
  });
});

const currentSectionCount = computed(() => currentSectionRows.value.length);
const requiredCount = computed(() => filteredRows.value.filter(row => row.required).length);
const qualityCount = computed(() => filteredRows.value.filter(row => row.qualityCheck).length);
const disabledCount = computed(() => filteredRows.value.filter(row => !row.enabled).length);
const departmentOptions = computed(() =>
  Array.from(new Set([...departments, ...ruleRows.value.map(row => row.department).filter(Boolean)]))
);

const treeNodeCount = (node: Partial<SectionTreeNode>) => (node.id ? node.count || 0 : totalRuleCount.value);

const editorTags = (row: TemplateFieldRule) =>
  row.editorLabels?.length ? row.editorLabels : row.editors.map(editor => roleLabel(editor));

const loadRules = async () => {
  loading.value = true;
  try {
    const { data } = await getTemplateFieldRulesApi();
    ruleRows.value = data;
  } finally {
    loading.value = false;
  }
};

const changeSection = (sectionKey: string) => {
  selectedSectionKey.value = sectionKey || "";
};

const resetFilters = () => {
  keyword.value = "";
  departmentFilter.value = "";
  enabledFilter.value = "";
  qualityFilter.value = "";
};

const openRuleDrawer = (row: TemplateFieldRule) => {
  Object.keys(ruleForm).forEach(key => delete ruleForm[key as keyof TemplateFieldRule]);
  Object.assign(ruleForm, row, { editors: [...row.editors] });
  drawerVisible.value = true;
};

const saveRule = async () => {
  await saveTemplateFieldRuleApi({ ...ruleForm, operator: roleName.value, operatorRole: currentRole.value });
  ElMessage.success("字段规则已保存");
  drawerVisible.value = false;
  await loadRules();
};

onMounted(loadRules);
</script>

<style scoped lang="scss">
.template-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
}

.template-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px;
  background: #ffffff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;

  h2,
  p {
    margin: 0;
  }

  h2 {
    font-size: 20px;
  }

  p {
    margin-top: 6px;
    color: var(--el-text-color-regular);
  }
}

.summary-chips,
.rule-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

.template-workspace {
  align-items: stretch;
  min-height: 0;
}

.template-workspace :deep(.filter) {
  width: 260px;
  min-width: 260px;
  min-height: 520px;
  margin-right: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: none;
}

.template-workspace :deep(.el-tree-node__label) {
  flex: 1;
  min-width: 0;
}

.tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
}

.tree-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-count {
  flex: none;
  min-width: 24px;
  padding: 0 6px;
  color: var(--el-text-color-secondary);
  text-align: center;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 999px;
}

.template-workspace :deep(.el-tree-node.is-current .node-count) {
  color: #ffffff;
  background: rgb(255 255 255 / 18%);
  border-color: rgb(255 255 255 / 30%);
}

.template-rule-panel {
  min-width: 0;
  padding: 14px;
  overflow: hidden;
  background: #ffffff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.rule-panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.section-context {
  min-width: 0;

  h3,
  p {
    margin: 0;
  }

  h3 {
    font-size: 18px;
    color: var(--el-text-color-primary);
  }

  p {
    margin-top: 4px;
    color: var(--el-text-color-regular);
  }
}

.rule-alert {
  margin-bottom: 12px;
}

.rule-toolbar {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) 160px 132px 132px auto auto;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.rule-search,
.toolbar-select {
  width: 100%;
}

.rule-table {
  width: 100%;
}

.muted {
  color: var(--el-text-color-regular);
}

.rule-drawer {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.rule-identity {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 14px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;

  strong,
  span {
    display: block;
  }

  strong {
    color: var(--el-text-color-primary);
    font-size: 18px;
  }

  span {
    margin-top: 4px;
    color: var(--el-text-color-regular);
  }
}

@media (max-width: 1180px) {
  .rule-toolbar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .template-workspace {
    flex-direction: column;
  }

  .template-workspace :deep(.filter) {
    width: 100%;
    min-width: 0;
    height: 300px;
    min-height: 300px;
    margin-right: 0;
    margin-bottom: 10px;
  }

  .rule-panel-header {
    flex-direction: column;
  }

  .rule-counts {
    justify-content: flex-start;
  }
}

@media (max-width: 720px) {
  .template-summary {
    align-items: flex-start;
    flex-direction: column;
  }

  .summary-chips {
    justify-content: flex-start;
  }

  .rule-toolbar {
    grid-template-columns: 1fr;
  }
}
</style>
