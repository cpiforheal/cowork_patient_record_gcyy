<template>
  <el-drawer
    :model-value="modelValue"
    :title="slot?.herbCode ? '编辑药品' : '放入新药材'"
    size="480px"
    append-to-body
    :close-on-click-modal="false"
    class="hed"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="hed-body">
      <p class="hed-location">{{ positionLabel }}</p>

      <el-form label-position="top">
        <el-form-item label="药名" required>
          <el-input v-model="form.name" maxlength="80" placeholder="例如：当归" />
        </el-form-item>
        <el-form-item label="别名">
          <el-input v-model="form.alias" maxlength="160" placeholder="多个别名用逗号分隔，例如：秦归,云归" />
          <span class="hed-hint">搜索会同时匹配别名。</span>
        </el-form-item>
        <el-form-item label="拼音">
          <el-input v-model="form.pinyin" maxlength="160" placeholder="留空则按药名自动生成" />
          <span class="hed-hint">用于全拼检索；多音字可在这里手工覆盖。</span>
        </el-form-item>
        <div class="hed-grid">
          <el-form-item label="库存">
            <el-input-number v-model="form.stock" :min="0" :precision="0" controls-position="right" />
          </el-form-item>
          <el-form-item label="预警阈值">
            <el-input-number v-model="form.warnThreshold" :min="0" :precision="0" controls-position="right" />
          </el-form-item>
        </div>
      </el-form>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="库存为演示数据"
        description="本期库存和阈值仅用于验证地图预警效果，后续由真实库存接口覆盖。"
      />
    </div>

    <template #footer>
      <div class="hed-footer">
        <el-button v-if="slot?.herbCode" text type="danger" @click="clearSlot">清空此格</el-button>
        <span v-else />
        <div class="hed-footer-main">
          <el-button @click="emit('update:modelValue', false)">取消</el-button>
          <el-button type="primary" @click="save">保存到地图</el-button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { reactive, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import type { TcmHerbSlot } from "@/api/modules/clinic/tcmPharmacyConsole";
import type { HerbSlotPatch } from "../composables/usePharmacyConsole";

const props = defineProps<{
  modelValue: boolean;
  slot: TcmHerbSlot | null;
  positionLabel: string;
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "save", patch: HerbSlotPatch): void;
  (event: "clear"): void;
}>();

const form = reactive({
  name: "",
  alias: "",
  pinyin: "",
  stock: 0,
  warnThreshold: 0
});

const reset = () => {
  form.name = props.slot?.name || "";
  form.alias = props.slot?.alias || "";
  form.pinyin = props.slot?.pinyin || "";
  form.stock = Number(props.slot?.stock || 0);
  form.warnThreshold = Number(props.slot?.warnThreshold || 0);
};

watch(
  () => props.modelValue,
  visible => {
    if (visible) reset();
  }
);

watch(
  () => props.slot,
  () => {
    if (props.modelValue) reset();
  }
);

const save = () => {
  if (!form.name.trim()) {
    ElMessage.warning("请填写药名");
    return;
  }
  emit("save", {
    name: form.name,
    alias: form.alias,
    pinyin: form.pinyin,
    stock: Number(form.stock || 0),
    warnThreshold: Number(form.warnThreshold || 0)
  });
};

const clearSlot = async () => {
  try {
    await ElMessageBox.confirm("清空后该格位变为空位，保存地图后生效。", "清空此格？", {
      type: "warning",
      confirmButtonText: "清空",
      cancelButtonText: "取消"
    });
    emit("clear");
  } catch {
    // 用户取消
  }
};
</script>

<style scoped lang="scss">
.hed-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.hed-location {
  margin: 0;
  padding: 8px 12px;
  color: #2f6b58;
  font-size: 12px;
  font-weight: 600;
  background: #e9f1ed;
  border-radius: 8px;
}

.hed-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.hed-hint {
  display: block;
  margin-top: 2px;
  color: #8a7a67;
  font-size: 11px;
}

.hed-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.hed-footer-main {
  display: flex;
  gap: 8px;
}
</style>
