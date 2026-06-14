<template>
  <div class="table-box">
    <ProTable ref="proTable" :columns="columns" :request-api="getDictListApi" :data-callback="dataCallback">
      <template #tableHeader>
        <el-button v-auth="'dict:create'" type="primary" :icon="CirclePlus" @click="openDialog()">新增类型</el-button>
      </template>

      <template #required="{ row }">
        <el-tag :type="row.required === '是' ? 'danger' : 'info'" effect="plain">{{ row.required }}</el-tag>
      </template>

      <template #operation="{ row }">
        <el-button v-auth="'dict:update'" type="primary" link @click="openDialog(row)">编辑</el-button>
      </template>
    </ProTable>

    <el-dialog v-model="dialogVisible" :title="dictForm.id ? '编辑资料类型' : '新增资料类型'" width="640px" destroy-on-close>
      <el-form :model="dictForm" label-width="112px">
        <el-form-item label="资料类型">
          <el-input v-model="dictForm.name" placeholder="例如 血常规" />
        </el-form-item>
        <el-form-item label="负责科室">
          <el-select v-model="dictForm.department" filterable placeholder="请选择负责科室">
            <el-option v-for="item in departments" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="是否必传">
          <el-radio-group v-model="dictForm.required">
            <el-radio-button label="是" />
            <el-radio-button label="按需" />
          </el-radio-group>
        </el-form-item>
        <el-form-item label="命名规则">
          <el-input v-model="dictForm.naming" type="textarea" :rows="3" placeholder="例如 患者姓名-门诊号-资料类型-版本" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveDict">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="dictManage">
import { computed, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { CirclePlus } from "@element-plus/icons-vue";
import ProTable from "@/components/ProTable/index.vue";
import { ColumnProps, ProTableInstance } from "@/components/ProTable/interface";
import { getDictListApi, saveDictApi, type DictRow } from "@/api/modules/clinic";
import { roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";

const userStore = useUserStore();
const proTable = ref<ProTableInstance>();
const dialogVisible = ref(false);
const dictForm = reactive<Partial<DictRow>>({});
const departments = ["前台", "门诊", "化验室", "心电室", "B超/放射", "治疗室", "质控/病案"];

const departmentEnum = departments.map(item => ({ label: item, value: item }));
const operatorRole = computed(() => userStore.userInfo.role || "frontdesk");
const operatorName = computed(() => roleLabel(operatorRole.value));

const columns = reactive<ColumnProps<DictRow>[]>([
  { type: "index", label: "#", width: 80 },
  { prop: "name", label: "资料类型", width: 160, search: { el: "input" } },
  {
    prop: "department",
    label: "负责科室",
    width: 150,
    enum: departmentEnum,
    search: { el: "select" }
  },
  { prop: "naming", label: "系统命名规则", minWidth: 300 },
  { prop: "required", label: "是否必传", width: 120 },
  { prop: "operation", label: "操作", fixed: "right", width: 120 }
]);

const dataCallback = (data: { list: DictRow[]; total: number }) => data;

const refresh = () => proTable.value?.getTableList();

const openDialog = (row?: DictRow) => {
  Object.keys(dictForm).forEach(key => delete dictForm[key as keyof DictRow]);
  Object.assign(dictForm, row || { department: "前台", required: "按需" });
  dialogVisible.value = true;
};

const saveDict = async () => {
  await saveDictApi({ ...dictForm, operator: operatorName.value, operatorRole: operatorRole.value });
  ElMessage.success("资料类型已保存");
  dialogVisible.value = false;
  refresh();
};
</script>
