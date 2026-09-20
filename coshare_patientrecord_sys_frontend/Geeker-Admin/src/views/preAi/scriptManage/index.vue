<template>
  <div class="script-manage">
    <header class="sm-head">
      <div>
        <h2>随访话术模板库</h2>
        <p>话术保存后即时生效（无缓存），随访弹窗按最新库内模板渲染；模板内可用自动填充占位符（患者尊称、复诊原因、健康指导、下次复查、护士姓名、科室），缺值段落整句跳过。</p>
      </div>
      <el-button v-if="canManage" type="primary" @click="openCreate">新建模板</el-button>
    </header>

    <section class="sm-section">
      <h3>正式模板（{{ activeTemplates.length }}）</h3>
      <el-table :data="activeTemplates" border :row-key="(row: any) => row.id">
        <el-table-column prop="label" label="模板名称" min-width="140" />
        <el-table-column prop="content" label="话术内容" min-width="380" show-overflow-tooltip />
        <el-table-column prop="sortOrder" label="排序" width="70" />
        <el-table-column label="最近操作" width="200">
          <template #default="{ row }">{{ (row as ScriptTemplateRow).updatedBy || "—" }} · {{ (row as ScriptTemplateRow).updatedAt || "—" }}</template>
        </el-table-column>
        <el-table-column v-if="canManage" label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row as ScriptTemplateRow)">编辑</el-button>
            <el-button link type="warning" @click="changeStatus(row as ScriptTemplateRow, 'archive')">归档</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section v-if="archivedTemplates.length" class="sm-section">
      <h3>已归档（{{ archivedTemplates.length }}）</h3>
      <el-table :data="archivedTemplates" border :row-key="(row: any) => row.id">
        <el-table-column prop="label" label="模板名称" min-width="140" />
        <el-table-column prop="content" label="话术内容" min-width="380" show-overflow-tooltip />
        <el-table-column prop="updatedAt" label="归档时间" width="170" />
        <el-table-column v-if="canManage" label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="success" @click="changeStatus(row as ScriptTemplateRow, 'activate')">重新启用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="editorVisible" :title="editingId ? '编辑话术模板' : '新建话术模板'" width="720px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="模板名称" required>
          <el-input v-model="form.label" placeholder="如：术后第7天电访话术" maxlength="50" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="999" />
        </el-form-item>
        <el-form-item label="话术内容" required>
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="12"
            placeholder="支持占位符：患者尊称、复诊原因、健康指导、下次复查、护士姓名、科室（用双花括号包裹，如 {{患者尊称}}）；空行分段，【异常分支】开头的段落在弹窗中隐藏"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存并即时生效</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="followUpScriptManage">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { useUserStore } from "@/stores/modules/user";
import { clinicFetch, parseClinicApiResponse } from "@/api/modules/clinic/http";
import { authHeaders } from "@/api/modules/authToken";

interface ScriptTemplateRow {
  id: string;
  nodeKey: string;
  label: string;
  content: string;
  status: string;
  sortOrder: number;
  updatedBy?: string;
  updatedAt?: string;
}

const userStore = useUserStore();
const role = computed(() => userStore.userInfo.role || "");
const canManage = computed(() => ["nurse", "nursing", "doctor", "admin"].includes(role.value));

const templates = ref<ScriptTemplateRow[]>([]);
const editorVisible = ref(false);
const saving = ref(false);
const editingId = ref("");

const form = reactive({ label: "", content: "", sortOrder: 0 });

const activeTemplates = computed(() => templates.value.filter(item => item.status === "ACTIVE"));
const archivedTemplates = computed(() => templates.value.filter(item => item.status === "ARCHIVED"));

const load = async () => {
  const result = await clinicFetch("/follow-up-script-templates/manage", { headers: authHeaders() });
  const data = await parseClinicApiResponse<{ list?: ScriptTemplateRow[] } & Partial<ScriptTemplateRow[]>>(result);
  templates.value = (Array.isArray(data) ? data : (data as any).list || []) as ScriptTemplateRow[];
};

const openCreate = () => {
  editingId.value = "";
  form.label = "";
  form.content = "";
  form.sortOrder = 0;
  editorVisible.value = true;
};

const openEdit = (row: ScriptTemplateRow) => {
  editingId.value = row.id;
  form.label = row.label;
  form.content = row.content;
  form.sortOrder = row.sortOrder;
  editorVisible.value = true;
};

const save = async () => {
  if (!form.label.trim()) {
    ElMessage.warning("请填写模板名称");
    return;
  }
  if (!form.content.trim()) {
    ElMessage.warning("请填写话术内容");
    return;
  }
  saving.value = true;
  try {
    const url = editingId.value
      ? `/follow-up-script-templates/${encodeURIComponent(editingId.value)}`
      : "/follow-up-script-templates";
    const result = await clinicFetch(url, {
      method: editingId.value ? "PUT" : "POST",
      headers: { "Content-Type": "application/json", ...authHeaders() },
      body: JSON.stringify({ label: form.label.trim(), content: form.content, sortOrder: form.sortOrder })
    });
    await parseClinicApiResponse(result);
    ElMessage.success("模板已保存并即时生效");
    editorVisible.value = false;
    await load();
  } catch (error: any) {
    ElMessage.error(error?.message || "保存失败");
  } finally {
    saving.value = false;
  }
};

const changeStatus = async (row: ScriptTemplateRow, action: "archive" | "activate") => {
  try {
    const result = await clinicFetch(`/follow-up-script-templates/${encodeURIComponent(row.id)}/${action}`, {
      method: "POST",
      headers: authHeaders()
    });
    await parseClinicApiResponse(result);
    ElMessage.success(action === "archive" ? "模板已归档" : "模板已重新启用");
    await load();
  } catch (error: any) {
    ElMessage.error(error?.message || "操作失败");
  }
};

onMounted(() => {
  void load();
});
</script>

<style scoped lang="scss">
.script-manage {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
}

.sm-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;

  h2 {
    margin: 0 0 6px;
    font-size: 18px;
  }

  p {
    margin: 0;
    color: var(--el-text-color-secondary);
    font-size: 12px;
    line-height: 1.6;
    max-width: 760px;
  }
}

.sm-section {
  h3 {
    margin: 0 0 10px;
    font-size: 14px;
    color: var(--el-text-color-primary);
  }
}
</style>
