<template>
  <div class="table-box">
    <ProTable
      :columns="columns"
      :request-api="getSystemLogList"
      :data-callback="dataCallback"
      :search-col="{ xs: 1, sm: 1, md: 2, lg: 3, xl: 3 }"
    />
  </div>
</template>

<script setup lang="ts" name="systemLog">
import { reactive } from "vue";
import ProTable from "@/components/ProTable/index.vue";
import { ColumnProps } from "@/components/ProTable/interface";
import { getAuditLogListApi, type AuditLogRow } from "@/api/modules/clinic";

const columns = reactive<ColumnProps<AuditLogRow>[]>([
  { type: "index", label: "#", width: 80 },
  { prop: "time", label: "时间", width: 170 },
  { prop: "operator", label: "操作人", width: 120, search: { el: "input" } },
  { prop: "role", label: "角色", width: 120 },
  { prop: "action", label: "动作", width: 130, search: { el: "input" } },
  { prop: "targetLabel", label: "对象", width: 150 },
  { prop: "detail", label: "详情", minWidth: 260 }
]);

const dataCallback = (data: { list: AuditLogRow[]; total: number }) => data;

const getSystemLogList = (params: { pageNum: number; pageSize: number; operator?: string; action?: string }) => {
  return getAuditLogListApi({ ...params, module: "system" });
};
</script>
