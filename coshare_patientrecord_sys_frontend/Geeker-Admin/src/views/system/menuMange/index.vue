<template>
  <div class="table-box">
    <section class="card mb10 page-head">
      <div>
        <h2>菜单权限</h2>
        <p>当前菜单已经收窄到门诊资料流转。后续这里用于给角色分配菜单可见范围。</p>
      </div>
    </section>

    <section class="card">
      <ProTable ref="proTable" title="菜单列表" row-key="path" :indent="20" :columns="columns" :data="menuData">
        <template #icon="scope">
          <el-icon :size="18">
            <component :is="scope.row.meta.icon"></component>
          </el-icon>
        </template>
        <template #operation>
          <el-button v-auth="'menu:update'" type="primary" link :icon="EditPen">配置角色</el-button>
        </template>
      </ProTable>
    </section>
  </div>
</template>

<script setup lang="ts" name="menuMange">
import { ref } from "vue";
import { ColumnProps } from "@/components/ProTable/interface";
import { EditPen } from "@element-plus/icons-vue";
import authMenuList from "@/assets/json/authMenuList.json";
import ProTable from "@/components/ProTable/index.vue";

const proTable = ref();
const menuData = ref(authMenuList.data);

const columns: ColumnProps[] = [
  { prop: "meta.title", label: "菜单名称", align: "left", search: { el: "input" } },
  { prop: "meta.icon", label: "图标" },
  { prop: "name", label: "权限标识", search: { el: "input" } },
  { prop: "path", label: "访问路径", width: 280, search: { el: "input" } },
  { prop: "operation", label: "操作", width: 160, fixed: "right" }
];
</script>

<style scoped lang="scss">
.page-head {
  h2,
  p {
    margin: 0;
  }

  p {
    margin-top: 8px;
    color: var(--el-text-color-regular);
  }
}
</style>
