<template>
  <el-drawer
    :model-value="modelValue"
    title="进销存操作说明"
    size="min(720px, 92vw)"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-scrollbar class="help-scrollbar">
      <el-collapse v-model="openSections">
        <el-collapse-item title="闭环总览" name="overview">
          <ol>
            <li>建立物资档案、单位换算和科室期初库存。</li>
            <li>按科室、门诊/住院和关键阶段配置启用套餐。</li>
            <li>仓库入库，科室完成申领、审批、发放和签收。</li>
            <li>患者登记计入患者量；关键阶段完成后自动扣减。</li>
            <li>周末生成并确认快照，导出后线下签字归档。</li>
          </ol>
        </el-collapse-item>

        <el-collapse-item title="功能入口" name="navigation">
          <p>日常先看“今日待办”；主管查看整体趋势和风险进入“管理看板”；科室申领和签收进入“申领与签收”。</p>
          <p>仓库入库和批次库存进入“入库与库存”；患者套餐与扣减失败进入“患者耗材套餐”。</p>
          <p>周末生成、确认和导出快照进入“周用量核对”。</p>
          <p>盘点、退回和报废进入“盘点与报损”；查询历史流水进入“出入库记录”；基础资料维护进入“物资设置”。</p>
        </el-collapse-item>

        <el-collapse-item title="管理员初始化" name="admin">
          <p>先维护物资名称、规格、基础单位、换算关系和安全库存，再确认各科室期初库存。</p>
          <p>套餐按科室、门诊/住院、阶段和生效日期维护。只有最新启用且处于生效期的版本参与匹配。</p>
        </el-collapse-item>

        <el-collapse-item title="仓库与科室日常操作" name="daily">
          <p>仓库先入库形成批次库存。科室提交申领后，依次完成审批、发放和签收；每一步都保留操作人与时间。</p>
          <p>申领作废只终止申领单，不会冲销患者自动耗用。患者耗用冲销由医疗阶段退回或纠错触发。</p>
        </el-collapse-item>

        <el-collapse-item title="患者自动扣减" name="deduction">
          <p>患者登记只增加患者量和预估量，不立即减少库存。检查、中医、医生或手术阶段完成后，系统投递幂等耗用任务。</p>
          <p>
            每个“病历 + 阶段 + 完成版本 + 命令类型”只保留一条有效任务。库存按现有 FEFO
            顺序分配：有效期靠前的批次优先，无有效期批次排在最后。
          </p>
          <p>期初未确认、套餐缺失、套餐为空或库存不足时整套失败，不做部分扣减，也不阻断医疗阶段完成。</p>
        </el-collapse-item>

        <el-collapse-item title="异常处理" name="exceptions">
          <p>先按异常提示补齐期初确认、启用套餐或可用库存，再在“患者耗材套餐 / 扣减失败任务”中重试。</p>
          <p>阶段退回时，未执行任务取消；已执行任务按原批次冲销。纠错重做会先冲销旧完成版本，再按新版本补扣。</p>
        </el-collapse-item>

        <el-collapse-item title="周用量核对与签字" name="weekly">
          <p>计算患者量优先使用本周实际患者量，实际为零时回退计划患者量。预估量 = 每患者标准量 × 计算患者量 × 换算系数。</p>
          <p>实际量 = 成功耗用流水 - 冲销流水；差异 = 预估量 - 实际量，负数表示实际耗用高于预估。</p>
          <p>确认前导出标记为草稿，确认后导出正式版。XLSX、PDF 和 DOCX 均保留制表、科室负责人、复核和日期签字栏。</p>
        </el-collapse-item>

        <el-collapse-item title="当前边界" name="boundaries">
          <p>
            空就诊类型或非 inpatient 类型当前归入门诊口径。现有 FEFO
            查询按效期排序，但未单独自动排除过期批次，操作人员仍需核对效期。
          </p>
        </el-collapse-item>
      </el-collapse>
    </el-scrollbar>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref } from "vue";

defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean] }>();
const openSections = ref(["overview", "deduction"]);
</script>

<style scoped lang="scss">
.help-scrollbar {
  height: calc(100vh - 96px);
  padding-right: 12px;
}

ol {
  padding-left: 22px;
  margin: 0;
}

li,
p {
  color: var(--el-text-color-regular);
  font-size: 14px;
  line-height: 1.75;
}

li + li {
  margin-top: 4px;
}

p {
  margin: 0 0 8px;
}
</style>
