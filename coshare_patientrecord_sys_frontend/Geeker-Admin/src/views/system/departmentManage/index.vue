<template>
  <div class="table-box">
    <ProTable ref="proTable" :columns="columns" :request-api="getDepartmentListApi" :data-callback="dataCallback">
      <template #tableHeader>
        <el-button v-auth="'department:create'" type="primary" :icon="CirclePlus" @click="openDialog()">新增科室</el-button>
      </template>

      <template #defaultRole="{ row }">
        <el-tag effect="plain">{{ roleLabel(row.defaultRole) }}</el-tag>
      </template>

      <template #operation="{ row }">
        <el-button v-auth="'department:update'" type="primary" link @click="openDialog(row)">编辑</el-button>
        <el-button v-auth="'department:delete'" type="danger" link @click="deleteDepartment(row)">删除</el-button>
      </template>
    </ProTable>

    <el-dialog v-model="dialogVisible" :title="departmentForm.id ? '编辑科室' : '新增科室'" width="620px" destroy-on-close>
      <el-form :model="departmentForm" label-width="108px">
        <el-form-item label="科室名称">
          <el-input v-model="departmentForm.name" placeholder="例如 化验室" />
        </el-form-item>
        <el-form-item label="默认角色">
          <el-select v-model="departmentForm.defaultRole" placeholder="请选择默认岗位角色">
            <el-option v-for="item in roleOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="上传类型">
          <el-input v-model="departmentForm.uploadTypes" type="textarea" :rows="3" placeholder="例如 血常规、凝血、术前八项" />
        </el-form-item>
        <el-form-item label="资料范围">
          <el-input v-model="departmentForm.scope" type="textarea" :rows="3" placeholder="说明该科室可维护的资料范围" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveDepartment">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="departmentManage">
import { computed, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { CirclePlus } from "@element-plus/icons-vue";
import ProTable from "@/components/ProTable/index.vue";
import { ColumnProps, ProTableInstance } from "@/components/ProTable/interface";
import { deleteDepartmentApi, getDepartmentListApi, saveDepartmentApi, type DepartmentRow } from "@/api/modules/clinic";
import { USER_ROLE_OPTIONS, roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";

const userStore = useUserStore();
const proTable = ref<ProTableInstance>();
const dialogVisible = ref(false);
const departmentForm = reactive<Partial<DepartmentRow>>({});

const roleOptions = USER_ROLE_OPTIONS;
const operatorRole = computed(() => userStore.userInfo.role || "frontdesk");
const operatorName = computed(() => roleLabel(operatorRole.value));

const columns = reactive<ColumnProps<DepartmentRow>[]>([
  { type: "index", label: "#", width: 80 },
  { prop: "name", label: "科室", width: 140, search: { el: "input" } },
  { prop: "defaultRole", label: "默认角色", width: 130 },
  { prop: "uploadTypes", label: "默认上传类型", minWidth: 260 },
  { prop: "scope", label: "资料范围", minWidth: 260 },
  { prop: "operation", label: "操作", fixed: "right", width: 160 }
]);

const dataCallback = (data: { list: DepartmentRow[]; total: number }) => data;

const refresh = () => proTable.value?.getTableList();

const openDialog = (row?: DepartmentRow) => {
  Object.keys(departmentForm).forEach(key => delete departmentForm[key as keyof DepartmentRow]);
  Object.assign(departmentForm, row || { defaultRole: "frontdesk" });
  dialogVisible.value = true;
};

const saveDepartment = async () => {
  await saveDepartmentApi({ ...departmentForm, operator: operatorName.value, operatorRole: operatorRole.value });
  ElMessage.success("科室配置已保存");
  dialogVisible.value = false;
  refresh();
};

const deleteDepartment = async (row: DepartmentRow) => {
  await ElMessageBox.confirm(`确定删除科室“${row.name}”吗？如仍有关联账号，系统会阻止删除。`, "删除科室", {
    confirmButtonText: "删除",
    cancelButtonText: "取消",
    type: "warning"
  });
  await deleteDepartmentApi(row.id, { operator: operatorName.value, operatorRole: operatorRole.value });
  ElMessage.success("科室已删除");
  refresh();
};
</script>
