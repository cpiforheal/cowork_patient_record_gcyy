<template>
  <div class="table-box">
    <ProTable
      ref="proTable"
      :columns="columns"
      :request-api="getRecycleList"
      :data-callback="dataCallback"
      :search-col="{ xs: 1, sm: 1, md: 2, lg: 3, xl: 3 }"
    >
      <template #operation="{ row }">
        <el-button
          v-auth="'document:restore'"
          type="primary"
          link
          :loading="restoringKey === row.id"
          @click="restoreDocument(row)"
        >
          恢复
        </el-button>
        <el-button v-auth="'document:read'" type="primary" link @click="router.push(`/patients/detail/${row.patientId}`)">
          查看
        </el-button>
      </template>
    </ProTable>
  </div>
</template>

<script setup lang="ts" name="documentRecycle">
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import ProTable from "@/components/ProTable/index.vue";
import { ColumnProps, ProTableInstance } from "@/components/ProTable/interface";
import { getRecycleDocumentListApi, restoreDocumentApi, type RecycleDocumentRow } from "@/api/modules/clinic";
import { roleLabel } from "@/config/fieldPermissions";
import { useUserStore } from "@/stores/modules/user";

const router = useRouter();
const userStore = useUserStore();
const proTable = ref<ProTableInstance>();
const restoringKey = ref("");

const columns = reactive<ColumnProps<RecycleDocumentRow>[]>([
  { type: "index", label: "#", width: 80 },
  { prop: "patient", label: "患者", width: 120, search: { el: "input" } },
  { prop: "visitNo", label: "门诊/住院号", width: 150, search: { el: "input" } },
  { prop: "fileName", label: "资料名称", minWidth: 180, search: { el: "input" } },
  { prop: "fieldLabel", label: "关联字段", width: 130 },
  { prop: "department", label: "上传科室", width: 120 },
  { prop: "reason", label: "作废原因", minWidth: 220 },
  { prop: "operator", label: "操作人", width: 120 },
  { prop: "time", label: "操作时间", width: 170 },
  { prop: "operation", label: "操作", fixed: "right", width: 140 }
]);

const dataCallback = (data: { list: RecycleDocumentRow[]; total: number }) => data;

const getRecycleList = (params: { pageNum: number; pageSize: number; patient?: string; visitNo?: string; fileName?: string }) => {
  const keyword = params.patient || params.visitNo;
  return getRecycleDocumentListApi({ ...params, patient: keyword });
};

const restoreDocument = async (row: RecycleDocumentRow) => {
  restoringKey.value = row.id;
  try {
    await restoreDocumentApi({
      patientId: row.patientId,
      documentKey: row.id,
      role: userStore.userInfo.role || "frontdesk",
      operator: userStore.userInfo.name || roleLabel(userStore.userInfo.role)
    });
    ElMessage.success("资料已恢复，患者附件索引已更新");
    proTable.value?.getTableList();
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    restoringKey.value = "";
  }
};
</script>
