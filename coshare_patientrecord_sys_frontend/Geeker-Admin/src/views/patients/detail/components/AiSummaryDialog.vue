<template>
  <el-dialog
    :model-value="visible"
    title="AI健康档案总结"
    width="760px"
    append-to-body
    destroy-on-close
    @update:model-value="$emit('update:visible', $event)"
    @closed="$emit('closed')"
  >
    <div v-loading="loading" class="ai-summary-dialog" element-loading-text="正在生成AI总结...">
      <el-alert title="AI输出仅供院内辅助阅读，不替代医生判断和 HIS 官方病历质控。" type="warning" :closable="false" show-icon />

      <el-empty v-if="!summary && !loading" description="暂无AI总结">
        <el-button type="primary" @click="$emit('generate')">生成总结</el-button>
      </el-empty>

      <template v-if="summary">
        <section class="ai-summary-head">
          <div>
            <strong>{{ patientName || "当前患者" }}</strong>

            <span>{{ visitNo }} · {{ summary.generatedAt }}</span>
          </div>

          <el-tag effect="plain">{{ summary.model }}</el-tag>
        </section>

        <section class="ai-summary-block">
          <h4>患者概况</h4>

          <p>{{ summary.summary }}</p>
        </section>

        <section v-if="summary.patientPortrait" class="ai-summary-block portrait">
          <h4>一句话患者画像</h4>

          <p>{{ summary.patientPortrait }}</p>
        </section>

        <section class="ai-summary-grid">
          <article>
            <h4>诊疗摘要</h4>

            <p>{{ summary.clinicalSummary }}</p>
          </article>

          <article>
            <h4>管理随访摘要</h4>

            <p>{{ summary.managementSummary }}</p>
          </article>

          <article>
            <h4>复查随访</h4>

            <p>{{ summary.followupSummary }}</p>
          </article>
        </section>

        <section class="ai-summary-lists">
          <article v-if="summary.priorityFocus?.length">
            <h4>优先关注</h4>

            <ul>
              <li v-for="item in summary.priorityFocus" :key="item">{{ item }}</li>
            </ul>
          </article>

          <article v-if="summary.overlookedInsights?.length">
            <h4>容易忽略</h4>

            <ul>
              <li v-for="item in summary.overlookedInsights" :key="item">{{ item }}</li>
            </ul>
          </article>

          <article>
            <h4>缺失/待补充</h4>

            <ul>
              <li v-for="item in summary.missingItems" :key="item">{{ item }}</li>
            </ul>
          </article>

          <article>
            <h4>风险提醒</h4>

            <ul>
              <li v-for="item in summary.riskHints" :key="item">{{ item }}</li>
            </ul>
          </article>

          <article v-if="summary.communicationTips?.length">
            <h4>沟通建议</h4>

            <ul>
              <li v-for="item in summary.communicationTips" :key="item">{{ item }}</li>
            </ul>
          </article>

          <article v-if="summary.nextFollowupSuggestions?.length">
            <h4>下一步随访</h4>

            <ul>
              <li v-for="item in summary.nextFollowupSuggestions" :key="item">{{ item }}</li>
            </ul>
          </article>

          <article>
            <h4>医生提醒</h4>

            <ul>
              <li v-for="item in summary.doctorTips" :key="item">{{ item }}</li>
            </ul>
          </article>
        </section>

        <p class="ai-summary-disclaimer">{{ summary.disclaimer }}</p>
      </template>
    </div>

    <template #footer>
      <el-button @click="$emit('close')">关闭</el-button>

      <el-button :disabled="!summary || loading" :loading="speaking" @click="$emit('toggleSpeech')">
        {{ speaking ? "停止朗读" : "朗读总结" }}
      </el-button>

      <el-button :disabled="!summary" @click="$emit('copy')">复制总结</el-button>

      <el-button type="primary" :loading="loading" @click="$emit('generate')">重新生成</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { AiRecordSummary } from "@/api/modules/clinic";

defineProps<{
  visible: boolean;
  loading: boolean;
  speaking: boolean;
  summary?: AiRecordSummary;
  patientName: string;
  visitNo: string;
}>();

defineEmits<{
  "update:visible": [visible: boolean];
  closed: [];
  close: [];
  generate: [];
  toggleSpeech: [];
  copy: [];
}>();
</script>
