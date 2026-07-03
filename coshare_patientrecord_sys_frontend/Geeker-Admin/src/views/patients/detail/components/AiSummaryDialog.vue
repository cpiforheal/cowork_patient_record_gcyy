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

<style scoped lang="scss">
.ai-summary-dialog {
  display: grid;
  gap: 14px;
  min-height: 220px;
}

.ai-summary-head {
  display: flex;
  gap: 14px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  background: var(--hos-glass);
  border: 1px solid var(--hos-border-light);
  border-radius: 8px;

  div {
    display: grid;
    gap: 4px;
    min-width: 0;
  }

  strong {
    color: var(--hos-text-primary);
    font-size: 16px;
  }

  span {
    color: var(--hos-text-secondary);
    font-size: 12px;
  }
}

.ai-summary-block,
.ai-summary-grid article,
.ai-summary-lists article {
  min-width: 0;
  padding: 14px;
  background: #ffffff;
  border: 1px solid var(--hos-border-light);
  border-radius: 8px;

  h4 {
    margin: 0 0 8px;
    color: var(--hos-text-primary);
    font-size: 14px;
  }

  p {
    margin: 0;
    color: var(--hos-text-regular);
    font-size: 13px;
    line-height: 1.75;
    overflow-wrap: anywhere;
  }
}

.ai-summary-block.portrait {
  background: #eff6ff;
  border-color: #bfdbfe;
}

.ai-summary-grid,
.ai-summary-lists {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.ai-summary-lists {
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));

  ul {
    display: grid;
    gap: 6px;
    padding-left: 18px;
    margin: 0;
    color: var(--hos-text-regular);
    font-size: 13px;
    line-height: 1.65;
  }

  li {
    overflow-wrap: anywhere;
  }
}

.ai-summary-disclaimer {
  margin: 0;
  color: var(--hos-text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 760px) {
  .ai-summary-grid,
  .ai-summary-lists {
    grid-template-columns: 1fr;
  }
}
</style>
