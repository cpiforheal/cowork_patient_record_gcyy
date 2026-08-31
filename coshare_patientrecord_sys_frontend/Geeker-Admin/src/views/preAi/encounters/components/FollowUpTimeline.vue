<template>
  <section class="follow-up-section">
    <div class="fu-heading">
      <div>
        <strong>复诊管理</strong>
        <small>检查室随检查创建复诊记录；后置科室可查看，不进入前置病历与 AI 成档内容</small>
      </div>
      <el-tag type="warning" effect="plain">{{ visits.length }} 次复诊</el-tag>
    </div>

    <div v-if="loading" class="fu-loading" v-loading="loading" element-loading-text="复诊记录加载中…" />

    <template v-else>
      <el-timeline v-if="visits.length" class="fu-timeline">
        <el-timeline-item
          v-for="visit in visits"
          :key="visit.id"
          :timestamp="`第 ${visit.seq} 次复诊 · ${visit.createdAt}`"
          :type="visit.seq === maxSeq ? 'primary' : undefined"
          :hollow="visit.seq !== maxSeq"
          placement="top"
        >
          <article class="fu-card" :class="{ 'is-latest': visit.seq === maxSeq }">
            <div class="fu-card-head">
              <span class="fu-badge">第 {{ visit.seq }} 次</span>
              <strong class="fu-reason">{{ visit.reason }}</strong>
              <el-tag v-if="visit.nextReviewDate" type="success" effect="light" size="small">
                下次复查：{{ visit.nextReviewDate }}
              </el-tag>
            </div>
            <p v-if="visit.conditionNote" class="fu-note">{{ visit.conditionNote }}</p>
            <div v-if="visit.images.length" class="fu-images">
              <el-image
                v-for="image in visit.images"
                :key="image.id"
                class="fu-image"
                :src="imageUrls[image.id]"
                :preview-src-list="previewList(visit)"
                :initial-index="visit.images.indexOf(image)"
                fit="cover"
                lazy
              >
                <template #error>
                  <div class="fu-image-fallback">{{ image.fileName }}</div>
                </template>
              </el-image>
            </div>
            <div class="fu-card-foot">
              <small>{{ visit.createdBy }} · {{ visit.createdByRole || "检查室" }}</small>
              <el-button
                v-if="canManage && visit.images.length"
                size="small"
                type="danger"
                plain
                @click="removeLastImage(visit)"
              >
                删除末张图片
              </el-button>
            </div>
          </article>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无复诊记录，可从下方新增" :image-size="64" />

      <div v-if="canManage" class="fu-create" :class="{ 'is-open': createOpen }">
        <button v-if="!createOpen" class="fu-create-trigger" type="button" @click="createOpen = true">
          <span class="fu-create-plus">＋</span>
          <span class="fu-create-copy">
            <strong>新增复诊</strong>
            <small>复诊原因 · 图片信息采集 · 病情描述 · 下次复查安排</small>
          </span>
        </button>
        <div v-else class="fu-create-form">
          <div class="fu-form-head">
            <strong>新增复诊记录</strong>
            <el-button size="small" text @click="resetCreate">收起</el-button>
          </div>
          <label class="fu-label">复诊原因（必填）</label>
          <el-select v-model="createForm.reason" filterable allow-create placeholder="选择常用原因或直接输入">
            <el-option v-for="item in REASONS" :key="item" :label="item" :value="item" />
          </el-select>
          <label class="fu-label">病情描述</label>
          <el-input
            v-model="createForm.conditionNote"
            type="textarea"
            :rows="4"
            placeholder="本次复诊查体所见、创面情况、处理措施等"
          />
          <label class="fu-label">下次安排复查时间</label>
          <el-date-picker
            v-model="createForm.nextReviewDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择或留空"
            style="width: 100%"
          />
          <label class="fu-label">图片信息采集</label>
          <el-upload
            :file-list="imageFileList"
            list-type="picture-card"
            multiple
            accept="image/*"
            :auto-upload="false"
            :on-change="onImageChange"
            :on-remove="onImageRemove"
          >
            <el-icon><Plus /></el-icon>
          </el-upload>
          <div class="fu-form-actions">
            <el-button @click="resetCreate">取消</el-button>
            <el-button type="primary" :loading="creating" @click="submitCreate">保存复诊记录</el-button>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts" name="FollowUpTimeline">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import {
  addFollowUpVisitImageApi,
  createFollowUpVisitApi,
  fetchFollowUpImageApi,
  loadFollowUpVisitsApi,
  removeFollowUpVisitImageApi,
  type FollowUpVisit
} from "@/api/modules/clinic/followUp";

const REASONS = ["术后复查", "换药", "拆线", "不适随诊", "复查结果解读", "其他"];

const props = defineProps<{ patientCaseId: string; encounterId?: string; canManage: boolean }>();

const visits = ref<FollowUpVisit[]>([]);
const loading = ref(false);
const creating = ref(false);
const createOpen = ref(false);
const imageUrls = ref<Record<string, string>>({});
const imageFileList = ref<{ name: string; raw: File }[]>([]);
const createForm = reactive({ reason: "", conditionNote: "", nextReviewDate: "" });

const maxSeq = computed(() => visits.value.reduce((max, visit) => Math.max(max, visit.seq), 0));

const previewList = (visit: FollowUpVisit) =>
  visit.images.map(image => imageUrls.value[image.id]).filter(Boolean) as string[];

const load = async () => {
  if (!props.patientCaseId) return;
  loading.value = true;
  try {
    const { data } = await loadFollowUpVisitsApi(props.patientCaseId);
    visits.value = data.visits || [];
    await hydrateImages();
  } catch (error: any) {
    ElMessage.error(error?.message || "复诊记录加载失败");
  } finally {
    loading.value = false;
  }
};

const hydrateImages = async () => {
  const pending = visits.value.flatMap(visit => visit.images).filter(image => !imageUrls.value[image.id]);
  for (const image of pending) {
    try {
      imageUrls.value[image.id] = await fetchFollowUpImageApi(image.id);
    } catch {
      imageUrls.value[image.id] = "";
    }
  }
};

const onImageChange = (_file: any, fileList: any[]) => {
  imageFileList.value = fileList.map(item => ({ name: item.name, raw: item.raw as File }));
};
const onImageRemove = (_file: any, fileList: any[]) => {
  imageFileList.value = fileList.map(item => ({ name: item.name, raw: item.raw as File }));
};

const readAsDataUrl = (file: File) =>
  new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(new Error("图片读取失败"));
    reader.readAsDataURL(file);
  });

const resetCreate = () => {
  createOpen.value = false;
  createForm.reason = "";
  createForm.conditionNote = "";
  createForm.nextReviewDate = "";
  imageFileList.value = [];
};

const submitCreate = async () => {
  if (!createForm.reason.trim()) {
    ElMessage.warning("请填写复诊原因");
    return;
  }
  creating.value = true;
  try {
    const { data } = await createFollowUpVisitApi({
      patientCaseId: props.patientCaseId,
      encounterId: props.encounterId || undefined,
      reason: createForm.reason.trim(),
      conditionNote: createForm.conditionNote.trim(),
      nextReviewDate: createForm.nextReviewDate || "",
      images: []
    });
    for (const item of imageFileList.value) {
      const dataUrl = await readAsDataUrl(item.raw);
      await addFollowUpVisitImageApi(data.id, { fileName: item.name, dataUrl });
    }
    ElMessage.success(`第 ${data.seq} 次复诊记录已保存`);
    resetCreate();
    await load();
  } catch (error: any) {
    ElMessage.error(error?.message || "复诊记录保存失败");
  } finally {
    creating.value = false;
  }
};

const removeLastImage = async (visit: FollowUpVisit) => {
  const last = visit.images[visit.images.length - 1];
  if (!last) return;
  try {
    await ElMessageBox.confirm(`确认删除复诊记录「第 ${visit.seq} 次」的图片「${last.fileName}」？`, "删除复诊图片", {
      type: "warning",
      confirmButtonText: "确认删除",
      cancelButtonText: "取消"
    });
  } catch {
    return;
  }
  try {
    await removeFollowUpVisitImageApi(visit.id, last.id);
    delete imageUrls.value[last.id];
    await load();
    ElMessage.success("复诊图片已删除");
  } catch (error: any) {
    ElMessage.error(error?.message || "复诊图片删除失败");
  }
};

watch(
  () => [props.patientCaseId, props.canManage],
  () => {
    imageUrls.value = {};
    void load();
  },
  { immediate: true, deep: false }
);
</script>

<style scoped lang="scss">
.follow-up-section {
  margin-top: 6px;
  padding: 14px 16px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 10px;
  background: var(--el-color-warning-light-9);
}
.fu-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;

  strong {
    font-size: 15px;
    color: var(--el-text-color-primary);
  }

  small {
    display: block;
    color: var(--el-text-color-secondary);
  }
}
.fu-loading {
  min-height: 120px;
}
.fu-timeline {
  padding-left: 4px;
}
.fu-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-bg-color);
  padding: 12px 14px;
  display: grid;
  gap: 8px;

  &.is-latest {
    border-color: var(--el-color-primary-light-5);
    box-shadow: 0 4px 12px rgb(0 150 136 / 10%);
  }
}
.fu-card-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.fu-badge {
  padding: 1px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-8);
}
.fu-reason {
  font-size: 14px;
  color: var(--el-text-color-primary);
}
.fu-note {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
}
.fu-images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.fu-image {
  width: 84px;
  height: 84px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
}
.fu-image-fallback {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  padding: 4px;
  text-align: center;
}
.fu-card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;

  small {
    color: var(--el-text-color-secondary);
  }
}
.fu-create {
  margin-top: 14px;
}
.fu-create-trigger {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 14px 18px;
  border: 2px dashed var(--el-color-primary-light-5);
  border-radius: 12px;
  background: var(--el-color-primary-light-9);
  cursor: pointer;
  text-align: left;
  transition: all 0.18s linear;

  &:hover {
    border-color: var(--el-color-primary);
    background: var(--el-color-primary-light-8);
  }
}
.fu-create-plus {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  font-size: 22px;
  font-weight: 700;
  color: var(--el-color-primary);
  background: #fff;
  box-shadow: 0 2px 8px rgb(0 150 136 / 14%);
}
.fu-create-copy {
  display: grid;
  gap: 2px;

  strong {
    font-size: 15px;
    color: var(--el-text-color-primary);
  }

  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}
.fu-create-form {
  border: 1px solid var(--el-color-primary-light-5);
  border-radius: 12px;
  background: var(--el-bg-color);
  padding: 14px 16px;
}
.fu-form-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;

  strong {
    font-size: 14px;
  }
}
.fu-label {
  display: block;
  margin: 10px 0 4px;
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.fu-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
</style>
