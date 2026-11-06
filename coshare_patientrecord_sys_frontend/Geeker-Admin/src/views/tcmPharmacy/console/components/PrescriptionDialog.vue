<template>
  <el-dialog
    :model-value="modelValue"
    title="整方录入"
    width="680px"
    append-to-body
    class="prd"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="prd-body">
      <el-input
        v-model="text"
        type="textarea"
        :rows="9"
        placeholder="粘贴处方，每行一味，例如：&#10;黄芪 15g&#10;当归 10g&#10;川芎 6g&#10;甘草 6g（先煎）"
      />
      <div class="prd-tools">
        <el-button text @click="fillSample">填入示例</el-button>
        <el-button text @click="text = ''">清空</el-button>
        <span>支持按换行、逗号、顿号切分；自动忽略剂量和先煎/后下等标注。</span>
      </div>

      <div class="prd-preview">
        <header>
          <strong>匹配结果</strong>
          <span>{{ result.matches.length }} 味已匹配 · {{ result.unmatched.length }} 项未匹配</span>
        </header>
        <div v-if="result.matches.length" class="prd-matches">
          <span
            v-for="item in result.matches"
            :key="item.slot.herbCode || `${item.slot.shelfNo}-${item.slot.layerNo}-${item.slot.columnNo}`"
            class="prd-match"
          >
            {{ item.name }}
            <small>{{ item.slot.shelfNo }}架{{ item.slot.layerNo }}层{{ item.slot.columnNo }}格</small>
          </span>
        </div>
        <p v-else class="prd-empty">还没有匹配到药名。</p>
        <div v-if="result.unmatched.length" class="prd-unmatched">
          <span class="prd-unmatched-label">未匹配：</span>
          <el-tag v-for="term in result.unmatched" :key="term" size="small" type="danger" effect="light">
            {{ term }}
          </el-tag>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!result.matches.length" @click="apply">载入取药清单</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { ElMessage } from "element-plus";
import type { HerbSearchItem, PrescriptionParseResult } from "../composables/usePharmacyConsole";

const props = defineProps<{
  modelValue: boolean;
  parse: (text: string) => PrescriptionParseResult;
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "apply", matches: HerbSearchItem[]): void;
}>();

const text = ref("");
const result = computed(() => props.parse(text.value));

const fillSample = () => {
  text.value = "黄芪 15g\n当归 10g\n川芎 6g\n白芍 12g\n熟地黄 15g\n甘草 6g（先煎）\n陈皮 9g\n茯苓 12g\n未知药材 9g";
};

const apply = () => {
  if (!result.value.matches.length) {
    ElMessage.warning("没有匹配到药名");
    return;
  }
  emit("apply", result.value.matches);
  emit("update:modelValue", false);
};
</script>

<style scoped lang="scss">
.prd-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.prd-tools {
  display: flex;
  gap: 4px;
  align-items: center;
  color: #8a7a67;
  font-size: 11px;

  span {
    margin-left: auto;
  }
}

.prd-preview {
  padding: 12px 14px;
  background: #fbf6ee;
  border: 1px solid #e8dfd2;
  border-radius: 10px;

  header {
    display: flex;
    gap: 8px;
    align-items: baseline;
    justify-content: space-between;
    margin-bottom: 8px;

    strong {
      color: #2f2a24;
      font-size: 13px;
    }

    span {
      color: #8a7a67;
      font-size: 11px;
    }
  }
}

.prd-matches {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.prd-match {
  padding: 3px 8px;
  color: #473a2c;
  font-size: 12px;
  background: #fffdf9;
  border: 1px solid #e8dfd2;
  border-radius: 999px;

  small {
    margin-left: 4px;
    color: #8a7a67;
    font-size: 10px;
  }
}

.prd-empty {
  margin: 4px 0;
  color: #8a7a67;
  font-size: 12px;
}

.prd-unmatched {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 8px;
}

.prd-unmatched-label {
  color: #b04a4a;
  font-size: 11px;
}
</style>
