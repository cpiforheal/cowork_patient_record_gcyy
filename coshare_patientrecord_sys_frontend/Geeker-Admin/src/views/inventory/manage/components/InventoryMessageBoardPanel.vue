<template>
  <section class="message-board-panel">
    <div class="board-toolbar inventory-panel-card">
      <div class="board-title-group">
        <span class="board-eyebrow">12 科室共享 · 需求与建议</span>
        <h2>留言板</h2>
        <span class="board-title-note">💡 记录新增耗材与改进建议</span>
      </div>
      <div class="board-decor" aria-hidden="true"><span>🩺</span><span>🌿</span></div>
      <el-button type="primary" :icon="EditPen" @click="openCreate">发布</el-button>
    </div>

    <div class="board-filters inventory-panel-card">
      <el-input v-model="filters.keyword" clearable placeholder="搜索标题或正文" :prefix-icon="Search" @keyup.enter="applyFilters" />
      <el-select v-model="filters.category" clearable placeholder="全部分类">
        <el-option v-for="item in categoryOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select v-model="filters.status" clearable placeholder="全部状态">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select v-model="filters.departmentKey" clearable filterable placeholder="全部科室">
        <el-option v-for="item in departmentOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-checkbox v-model="filters.onlyMine">只看我的</el-checkbox>
      <div class="filter-actions">
        <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
        <el-button type="primary" :icon="Search" @click="applyFilters">查询</el-button>
      </div>
    </div>

    <div v-loading="loading" class="board-list" element-loading-text="正在加载留言板...">
      <article
        v-for="post in pageData.list"
        :key="post.id"
        class="board-card"
        :class="{ pinned: post.pinned, hidden: post.hidden }"
        @click="openDetail(post.id)"
      >
        <div class="card-accent" />
        <div class="card-main">
          <div class="card-heading">
            <div class="card-tags">
              <el-tag v-if="post.pinned" type="warning" effect="light">置顶</el-tag>
              <el-tag :type="categoryType(post.category)" effect="plain">{{ categoryLabel(post.category) }}</el-tag>
              <el-tag :type="statusType(post.status)" effect="light">{{ statusLabel(post.status) }}</el-tag>
              <el-tag v-if="post.hidden" type="danger" effect="plain">已隐藏</el-tag>
              <el-tag v-if="post.withdrawn" type="info" effect="plain">已撤回</el-tag>
            </div>
            <span class="card-time">{{ formatTime(post.lastActivityAt || post.updatedAt) }}</span>
          </div>
          <h3>{{ post.title }}</h3>
          <p>{{ post.content }}</p>
          <div class="card-meta">
            <span><el-icon><OfficeBuilding /></el-icon>{{ post.departmentName || "管理端" }}</span>
            <span><el-icon><User /></el-icon>{{ post.authorName }}</span>
            <span><el-icon><ChatDotRound /></el-icon>{{ post.replyCount }} 条回复</span>
          </div>
        </div>
        <el-icon class="card-arrow"><ArrowRight /></el-icon>
      </article>
      <el-empty v-if="!loading && !pageData.list.length" description="当前筛选条件下暂无需求或建议" />
    </div>

    <div v-if="pageData.total > pageData.size" class="board-pagination">
      <el-pagination
        v-model:current-page="pageData.page"
        :page-size="pageData.size"
        :total="pageData.total"
        layout="prev, pager, next, total"
        @current-change="loadPosts"
      />
    </div>

    <el-dialog v-model="editorVisible" :title="editingPostId ? '编辑主题' : '发布需求 / 建议'" width="620px" destroy-on-close>
      <el-form ref="postFormRef" :model="postForm" :rules="postRules" label-position="top">
        <el-form-item label="需求分类" prop="category">
          <el-select v-model="postForm.category" class="full-width" placeholder="请选择分类">
            <el-option v-for="item in categoryOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题" prop="title">
          <el-input v-model="postForm.title" maxlength="100" show-word-limit placeholder="一句话说明需要新增或改正的内容" />
        </el-form-item>
        <el-form-item label="详细说明" prop="content">
          <div ref="postContentBox" class="emoji-textarea">
            <el-input
              v-model="postForm.content"
              type="textarea"
              :rows="8"
              maxlength="2000"
              show-word-limit
              resize="vertical"
              placeholder="请写清使用场景、当前问题以及期望调整方式"
            />
            <el-popover placement="top-end" trigger="click" width="214" popper-class="inventory-emoji-popper">
              <template #reference><el-button class="emoji-trigger" circle text aria-label="插入表情" @click.stop>😊</el-button></template>
              <div class="emoji-grid"><button v-for="emoji in emojis" :key="emoji" type="button" @click="insertEmoji('post', emoji)">{{ emoji }}</button></div>
            </el-popover>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitPost">{{ editingPostId ? "保存修改" : "确认发布" }}</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" size="min(760px, 92vw)" destroy-on-close class="board-detail-drawer">
      <template #header>
        <div class="drawer-title">
          <span>主题详情</span>
          <el-tag v-if="detail?.post.pinned" type="warning">置顶</el-tag>
        </div>
      </template>
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail">
          <div class="detail-post" :class="{ withdrawn: detail.post.withdrawn }">
            <div class="detail-tags">
              <el-tag :type="categoryType(detail.post.category)" effect="plain">{{ categoryLabel(detail.post.category) }}</el-tag>
              <el-tag :type="statusType(detail.post.status)">{{ statusLabel(detail.post.status) }}</el-tag>
              <el-tag v-if="detail.post.hidden" type="danger" effect="plain">科室不可见</el-tag>
            </div>
            <h2>{{ detail.post.title }}</h2>
            <div class="detail-author">
              {{ detail.post.departmentName }} · {{ detail.post.authorName }} · {{ formatTime(detail.post.createdAt) }}
            </div>
            <p class="detail-content">{{ detail.post.content }}</p>
            <div class="detail-actions">
              <el-button v-if="detail.post.canEdit" text type="primary" @click="openEdit(detail.post)">编辑</el-button>
              <el-button v-if="detail.post.canWithdraw" text type="danger" @click="withdrawPost(detail.post.id)">撤回</el-button>
              <el-button v-if="administrator" text @click="openAudit('POST', detail.post.id)">管理记录</el-button>
            </div>
          </div>

          <div v-if="administrator" class="admin-control-card">
            <div class="admin-control-title">管理员处理</div>
            <div class="admin-control-grid">
              <el-select v-model="adminForm.status" placeholder="处理状态">
                <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
              <el-button :loading="adminSaving" type="primary" plain @click="saveAdminStatus">保存状态与说明</el-button>
              <el-switch
                :model-value="detail.post.pinned"
                inline-prompt
                active-text="置顶"
                inactive-text="普通"
                @change="togglePinned"
              />
              <el-switch
                :model-value="detail.post.hidden"
                inline-prompt
                active-text="隐藏"
                inactive-text="显示"
                @change="togglePostVisibility"
              />
            </div>
            <el-input
              v-model="adminForm.handlingNote"
              type="textarea"
              :rows="3"
              maxlength="2000"
              show-word-limit
              placeholder="填写处理进展、结论或不采纳原因"
            />
          </div>

          <div class="reply-section">
            <div class="reply-heading">
              <h3>讨论回复</h3>
              <span>{{ detail.replies.length }} 条</span>
            </div>
            <div class="reply-list">
              <div v-for="reply in detail.replies" :key="reply.id" class="reply-item" :class="{ hidden: reply.hidden }">
                <div class="reply-avatar">{{ (reply.departmentName || reply.authorName || "回").slice(0, 1) }}</div>
                <div class="reply-main">
                  <div class="reply-meta">
                    <strong>{{ reply.authorName }}</strong>
                    <span>{{ reply.departmentName }}</span>
                    <span>{{ formatTime(reply.createdAt) }}</span>
                    <el-tag v-if="reply.hidden" size="small" type="danger" effect="plain">已隐藏</el-tag>
                  </div>
                  <template v-if="editingReplyId === reply.id">
                    <div ref="editingReplyBox" class="emoji-textarea">
                      <el-input v-model="editingReplyContent" type="textarea" :rows="3" maxlength="2000" show-word-limit />
                      <el-popover placement="top-end" trigger="click" width="214" popper-class="inventory-emoji-popper">
                        <template #reference><el-button class="emoji-trigger" circle text aria-label="插入表情" @click.stop>😊</el-button></template>
                        <div class="emoji-grid"><button v-for="emoji in emojis" :key="emoji" type="button" @click="insertEmoji('editing-reply', emoji)">{{ emoji }}</button></div>
                      </el-popover>
                    </div>
                    <div class="reply-edit-actions">
                      <el-button size="small" @click="cancelReplyEdit">取消</el-button>
                      <el-button size="small" type="primary" :loading="submitting" @click="saveReplyEdit(reply.id)">保存</el-button>
                    </div>
                  </template>
                  <p v-else>{{ reply.content }}</p>
                  <div v-if="editingReplyId !== reply.id" class="reply-actions">
                    <el-button v-if="reply.canEdit" link type="primary" @click="startReplyEdit(reply)">编辑</el-button>
                    <el-button v-if="reply.canWithdraw" link type="danger" @click="withdrawReply(reply.id)">撤回</el-button>
                    <el-button v-if="administrator" link type="warning" @click="toggleReplyVisibility(reply)">
                      {{ reply.hidden ? "恢复" : "隐藏" }}
                    </el-button>
                    <el-button v-if="administrator" link @click="openAudit('REPLY', reply.id)">记录</el-button>
                  </div>
                </div>
              </div>
              <el-empty v-if="!detail.replies.length" :image-size="72" description="暂无回复，欢迎补充信息" />
            </div>
            <div v-if="!detail.post.withdrawn && !detail.post.hidden" class="reply-composer">
              <div ref="replyContentBox" class="emoji-textarea">
                <el-input
                  v-model="replyContent"
                  type="textarea"
                  :rows="4"
                  maxlength="2000"
                  show-word-limit
                  resize="vertical"
                  placeholder="补充使用场景、数量口径或改进建议..."
                />
                <el-popover placement="top-end" trigger="click" width="214" popper-class="inventory-emoji-popper">
                  <template #reference><el-button class="emoji-trigger" circle text aria-label="插入表情" @click.stop>😊</el-button></template>
                  <div class="emoji-grid"><button v-for="emoji in emojis" :key="emoji" type="button" @click="insertEmoji('reply', emoji)">{{ emoji }}</button></div>
                </el-popover>
              </div>
              <div class="composer-footer">
                <span>回复内容对 12 科室和管理员共享</span>
                <el-button type="primary" :loading="submitting" @click="submitReply">发布回复</el-button>
              </div>
            </div>
          </div>
        </template>
      </div>
    </el-drawer>

    <el-drawer v-model="auditVisible" title="留言板管理记录" size="min(660px, 90vw)" destroy-on-close>
      <div v-loading="auditLoading" class="audit-list">
        <el-timeline>
          <el-timeline-item v-for="item in auditPage.list" :key="item.id" :timestamp="formatTime(item.createdAt)" placement="top">
            <div class="audit-card">
              <strong>{{ auditActionLabel(item.action) }}</strong>
              <span>{{ item.operatorName }} · {{ item.departmentName }}</span>
              <pre v-if="detailText(item.detail)">{{ detailText(item.detail) }}</pre>
            </div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-if="!auditLoading && !auditPage.list.length" description="暂无管理记录" />
      </div>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from "element-plus";
import { ArrowRight, ChatDotRound, EditPen, OfficeBuilding, Refresh, Search, User } from "@element-plus/icons-vue";
import {
  createInventoryMessageBoardPostApi,
  createInventoryMessageBoardReplyApi,
  getInventoryMessageBoardAuditLogsApi,
  getInventoryMessageBoardPostApi,
  getInventoryMessageBoardPostsApi,
  updateInventoryMessageBoardPinApi,
  updateInventoryMessageBoardPostApi,
  updateInventoryMessageBoardPostVisibilityApi,
  updateInventoryMessageBoardReplyApi,
  updateInventoryMessageBoardReplyVisibilityApi,
  updateInventoryMessageBoardStatusApi,
  withdrawInventoryMessageBoardPostApi,
  withdrawInventoryMessageBoardReplyApi,
  type InventoryMessageBoardAuditPage,
  type InventoryMessageBoardCategory,
  type InventoryMessageBoardDetail,
  type InventoryMessageBoardPage,
  type InventoryMessageBoardPost,
  type InventoryMessageBoardReply,
  type InventoryMessageBoardStatus
} from "@/api/modules/inventory";

const props = defineProps<{ administrator: boolean }>();

const categoryOptions: Array<{ label: string; value: InventoryMessageBoardCategory }> = [
  { label: "新增耗材", value: "NEW_ITEM" },
  { label: "数据 / 定额纠错", value: "DATA_CORRECTION" },
  { label: "使用建议", value: "SUGGESTION" },
  { label: "其他意见", value: "OTHER" }
];
const statusOptions: Array<{ label: string; value: InventoryMessageBoardStatus }> = [
  { label: "待处理", value: "PENDING" },
  { label: "跟进中", value: "FOLLOWING" },
  { label: "已完成", value: "COMPLETED" },
  { label: "不采纳", value: "REJECTED" }
];
const departmentOptions = [
  ["physiotherapy", "理疗室"], ["tcm", "中医科"], ["tcm-pharmacy", "中药房"], ["logistics", "后勤"],
  ["western-pharmacy", "西药房"], ["operating", "手术室"], ["nursing", "护理部"], ["cashier", "收费室"],
  ["inspection", "检查室"], ["laboratory", "检验科"], ["endoscopy", "胃肠镜"], ["anesthesia", "麻醉室"]
].map(item => ({ value: item[0], label: item[1] }));

const loading = ref(false);
const submitting = ref(false);
const detailLoading = ref(false);
const adminSaving = ref(false);
const auditLoading = ref(false);
const editorVisible = ref(false);
const detailVisible = ref(false);
const auditVisible = ref(false);
const editingPostId = ref("");
const editingReplyId = ref("");
const editingReplyContent = ref("");
const replyContent = ref("");
const detail = ref<InventoryMessageBoardDetail>();
const postFormRef = ref<FormInstance>();
const postContentBox = ref<HTMLElement>();
const editingReplyBox = ref<HTMLElement>();
const replyContentBox = ref<HTMLElement>();
const emojis = ["😊", "👍", "💡", "📌", "🩹", "🩺", "✅", "🙏", "🌿", "✨"] as const;
const filters = reactive({ keyword: "", category: "" as InventoryMessageBoardCategory | "", status: "" as InventoryMessageBoardStatus | "", departmentKey: "", onlyMine: false });
const postForm = reactive({ title: "", content: "", category: "NEW_ITEM" as InventoryMessageBoardCategory });
const adminForm = reactive({ status: "PENDING" as InventoryMessageBoardStatus, handlingNote: "" });
const pageData = reactive<InventoryMessageBoardPage>({ list: [], total: 0, page: 1, size: 20 });
const auditPage = reactive<InventoryMessageBoardAuditPage>({ list: [], total: 0, page: 1, size: 20 });
const auditTarget = reactive<{ targetType?: "POST" | "REPLY"; targetId?: string }>({});

const postRules: FormRules = {
  category: [{ required: true, message: "请选择需求分类", trigger: "change" }],
  title: [{ required: true, message: "请填写标题", trigger: "blur" }, { max: 100, message: "标题不能超过 100 字", trigger: "blur" }],
  content: [{ required: true, message: "请填写详细说明", trigger: "blur" }, { max: 2000, message: "正文不能超过 2000 字", trigger: "blur" }]
};

const categoryLabel = (value: InventoryMessageBoardCategory) => categoryOptions.find(item => item.value === value)?.label || value;
const statusLabel = (value: InventoryMessageBoardStatus) => statusOptions.find(item => item.value === value)?.label || value;
const categoryType = (value: InventoryMessageBoardCategory) => value === "NEW_ITEM" ? "success" : value === "DATA_CORRECTION" ? "warning" : value === "SUGGESTION" ? "primary" : "info";
const statusType = (value: InventoryMessageBoardStatus) => value === "COMPLETED" ? "success" : value === "REJECTED" ? "info" : value === "FOLLOWING" ? "warning" : "danger";
const formatTime = (value?: string) => value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "-";
const errorMessage = (error: unknown) => error instanceof Error ? error.message : "操作失败，请稍后重试";
const insertEmoji = async (target: "post" | "editing-reply" | "reply", emoji: string) => {
  const content = target === "post" ? postForm.content : target === "editing-reply" ? editingReplyContent.value : replyContent.value;
  const container = target === "post" ? postContentBox.value : target === "editing-reply" ? editingReplyBox.value : replyContentBox.value;
  const textarea = container?.querySelector<HTMLTextAreaElement>("textarea");
  const start = textarea?.selectionStart ?? content.length;
  const end = textarea?.selectionEnd ?? content.length;
  const nextContent = (content.slice(0, start) + emoji + content.slice(end)).slice(0, 2000);
  if (target === "post") postForm.content = nextContent;
  else if (target === "editing-reply") editingReplyContent.value = nextContent;
  else replyContent.value = nextContent;
  await nextTick();
  const nextTextarea = container?.querySelector<HTMLTextAreaElement>("textarea");
  const cursor = Math.min(start + emoji.length, nextContent.length);
  nextTextarea?.focus();
  nextTextarea?.setSelectionRange(cursor, cursor);
};

const loadPosts = async () => {
  loading.value = true;
  try {
    const result = await getInventoryMessageBoardPostsApi({ ...filters, page: pageData.page, size: pageData.size });
    Object.assign(pageData, result.data);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
};

const applyFilters = () => { pageData.page = 1; void loadPosts(); };
const resetFilters = () => {
  Object.assign(filters, { keyword: "", category: "", status: "", departmentKey: "", onlyMine: false });
  pageData.page = 1;
  void loadPosts();
};
const openCreate = () => {
  editingPostId.value = "";
  Object.assign(postForm, { title: "", content: "", category: "NEW_ITEM" });
  editorVisible.value = true;
};
const openEdit = (post: InventoryMessageBoardPost) => {
  editingPostId.value = post.id;
  Object.assign(postForm, { title: post.title, content: post.content, category: post.category });
  editorVisible.value = true;
};
const submitPost = async () => {
  await postFormRef.value?.validate();
  submitting.value = true;
  try {
    if (editingPostId.value) await updateInventoryMessageBoardPostApi(editingPostId.value, { ...postForm });
    else await createInventoryMessageBoardPostApi({ ...postForm });
    ElMessage.success(editingPostId.value ? "主题已更新" : "需求或建议已发布");
    editorVisible.value = false;
    await loadPosts();
    if (editingPostId.value && detailVisible.value) await openDetail(editingPostId.value);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    submitting.value = false;
  }
};
const openDetail = async (postId: string) => {
  detailVisible.value = true;
  detailLoading.value = true;
  editingReplyId.value = "";
  try {
    const result = await getInventoryMessageBoardPostApi(postId);
    detail.value = result.data;
    adminForm.status = result.data.post.status;
    adminForm.handlingNote = result.data.post.handlingNote || "";
  } catch (error) {
    ElMessage.error(errorMessage(error));
    detailVisible.value = false;
  } finally {
    detailLoading.value = false;
  }
};
const refreshDetail = async () => {
  if (!detail.value) return;
  await openDetail(detail.value.post.id);
  await loadPosts();
};
const withdrawPost = async (postId: string) => {
  await ElMessageBox.confirm("撤回后将保留占位和操作记录，确认继续？", "撤回主题", { type: "warning" });
  try { await withdrawInventoryMessageBoardPostApi(postId); ElMessage.success("主题已撤回"); await refreshDetail(); }
  catch (error) { ElMessage.error(errorMessage(error)); }
};
const submitReply = async () => {
  if (!detail.value || !replyContent.value.trim()) return ElMessage.warning("请填写回复内容");
  submitting.value = true;
  try {
    await createInventoryMessageBoardReplyApi(detail.value.post.id, replyContent.value.trim());
    replyContent.value = "";
    ElMessage.success("回复已发布");
    await refreshDetail();
  } catch (error) { ElMessage.error(errorMessage(error)); }
  finally { submitting.value = false; }
};
const startReplyEdit = (reply: InventoryMessageBoardReply) => { editingReplyId.value = reply.id; editingReplyContent.value = reply.content; };
const cancelReplyEdit = () => { editingReplyId.value = ""; editingReplyContent.value = ""; };
const saveReplyEdit = async (replyId: string) => {
  if (!editingReplyContent.value.trim()) return ElMessage.warning("回复内容不能为空");
  submitting.value = true;
  try { await updateInventoryMessageBoardReplyApi(replyId, editingReplyContent.value.trim()); cancelReplyEdit(); ElMessage.success("回复已更新"); await refreshDetail(); }
  catch (error) { ElMessage.error(errorMessage(error)); }
  finally { submitting.value = false; }
};
const withdrawReply = async (replyId: string) => {
  await ElMessageBox.confirm("撤回后将保留“内容已撤回”占位，确认继续？", "撤回复", { type: "warning" });
  try { await withdrawInventoryMessageBoardReplyApi(replyId); ElMessage.success("回复已撤回"); await refreshDetail(); }
  catch (error) { ElMessage.error(errorMessage(error)); }
};
const saveAdminStatus = async () => {
  if (!detail.value) return;
  adminSaving.value = true;
  try { await updateInventoryMessageBoardStatusApi(detail.value.post.id, { ...adminForm }); ElMessage.success("处理状态已更新"); await refreshDetail(); }
  catch (error) { ElMessage.error(errorMessage(error)); }
  finally { adminSaving.value = false; }
};
const togglePinned = async (value: string | number | boolean) => {
  if (!detail.value) return;
  try { await updateInventoryMessageBoardPinApi(detail.value.post.id, Boolean(value)); await refreshDetail(); }
  catch (error) { ElMessage.error(errorMessage(error)); }
};
const togglePostVisibility = async (value: string | number | boolean) => {
  if (!detail.value) return;
  try { await updateInventoryMessageBoardPostVisibilityApi(detail.value.post.id, Boolean(value)); await refreshDetail(); }
  catch (error) { ElMessage.error(errorMessage(error)); }
};
const toggleReplyVisibility = async (reply: InventoryMessageBoardReply) => {
  try { await updateInventoryMessageBoardReplyVisibilityApi(reply.id, !reply.hidden); await refreshDetail(); }
  catch (error) { ElMessage.error(errorMessage(error)); }
};
const openAudit = async (targetType: "POST" | "REPLY", targetId: string) => {
  Object.assign(auditTarget, { targetType, targetId });
  auditVisible.value = true;
  auditLoading.value = true;
  try {
    const result = await getInventoryMessageBoardAuditLogsApi({ ...auditTarget, page: 1, size: 100 });
    Object.assign(auditPage, result.data);
  } catch (error) { ElMessage.error(errorMessage(error)); }
  finally { auditLoading.value = false; }
};
const auditActionLabel = (action: string) => ({
  CREATE_POST: "发布主题", EDIT_POST: "编辑主题", WITHDRAW_POST: "撤回主题", CREATE_REPLY: "发布回复", EDIT_REPLY: "编辑回复",
  WITHDRAW_REPLY: "撤回复", UPDATE_STATUS: "更新处理状态", PIN_POST: "置顶主题", UNPIN_POST: "取消置顶", HIDE_POST: "隐藏主题",
  RESTORE_POST: "恢复主题", HIDE_REPLY: "隐藏回复", RESTORE_REPLY: "恢复回复"
}[action] || action);
const detailText = (value: Record<string, unknown> | string) => typeof value === "string" ? value : Object.keys(value || {}).length ? JSON.stringify(value, null, 2) : "";

onMounted(loadPosts);
</script>

<style scoped lang="scss">
.message-board-panel { display: grid; gap: 14px; }
.inventory-panel-card { background: #fffdf8; border: 1px solid #efe4d2; border-radius: 14px; box-shadow: 0 8px 22px rgb(112 80 40 / 5%); }
.board-toolbar { position: relative; display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: 14px; padding: 16px 20px; overflow: hidden; }
.board-title-group { display: grid; justify-items: center; gap: 2px; grid-column: 2; min-width: 0; }
.board-toolbar .el-button { justify-self: end; grid-column: 3; grid-row: 1; }
.board-eyebrow { color: #94734e; font-size: 11px; font-weight: 700; letter-spacing: .08em; }
.board-toolbar h2 { margin: 0; color: #4b3828; font-family: "STXingkai", "KaiTi", "Segoe Print", "Bradley Hand", cursive; font-size: 27px; font-weight: 700; letter-spacing: .08em; line-height: 1.2; }
.board-title-note { color: #9b7e5e; font-size: 12px; }
.board-decor { position: absolute; inset: 0 auto 0 18px; display: flex; align-items: center; gap: 4px; pointer-events: none; font-size: 18px; opacity: .82; }
.board-decor span:last-child { margin-top: 14px; font-size: 15px; }
.board-filters { display: grid; grid-template-columns: minmax(200px, 1.5fr) repeat(3, minmax(126px, .8fr)) auto auto; gap: 10px; align-items: center; padding: 12px 14px; }
.filter-actions { display: flex; justify-content: flex-end; gap: 8px; }
.board-list { min-height: 220px; display: grid; gap: 10px; }
.board-card { position: relative; display: grid; grid-template-columns: 5px minmax(0, 1fr) auto; gap: 14px; align-items: center; overflow: hidden; padding: 13px 16px 13px 0; background: #fffdf8; border: 1px solid #efe4d2; border-radius: 12px; box-shadow: 0 3px 10px rgb(112 80 40 / 4%); cursor: pointer; animation: board-fade-in .32s ease both; transition: border-color .18s ease, box-shadow .18s ease, transform .18s ease; }
.board-card:hover { transform: translateY(-2px); border-color: #d7ba92; box-shadow: 0 10px 22px rgb(112 80 40 / 12%); }
.board-card.pinned { border-color: #e4c17e; }
.board-card.hidden { opacity: .72; }
.card-accent { align-self: stretch; background: #d7b17e; }
.board-card.pinned .card-accent { background: #d89c3c; }
.card-main { min-width: 0; }
.card-heading, .card-meta, .detail-tags, .detail-actions, .reply-meta, .reply-actions, .composer-footer, .drawer-title { display: flex; align-items: center; gap: 8px; }
.card-heading { justify-content: space-between; }
.card-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.card-time { flex: 0 0 auto; color: #9b8977; font-size: 12px; }
.board-card h3 { margin: 8px 0 4px; overflow: hidden; color: #49372a; font-size: 16px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.board-card p { display: -webkit-box; margin: 0; overflow: hidden; color: #705f50; font-size: 13px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.card-meta { flex-wrap: wrap; margin-top: 9px; color: #8c7b6a; font-size: 12px; }
.card-meta span { display: inline-flex; align-items: center; gap: 4px; }
.card-arrow { color: #b59d83; font-size: 18px; }
.board-pagination { display: flex; justify-content: flex-end; padding: 2px 0 8px; }
.full-width { width: 100%; }
.emoji-textarea { position: relative; }
.emoji-textarea :deep(.el-textarea__inner) { padding-right: 42px; }
.emoji-trigger { position: absolute; right: 6px; bottom: 6px; z-index: 1; color: #a7743c; font-size: 17px; background: rgb(255 253 248 / 90%); }
.emoji-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 5px; }
.emoji-grid button { width: 32px; height: 32px; padding: 0; font-size: 18px; background: #fffdf8; border: 1px solid #efe4d2; border-radius: 8px; cursor: pointer; transition: transform .15s ease, background-color .15s ease; }
.emoji-grid button:hover { background: #fff1d9; transform: translateY(-1px); }
.detail-body { min-height: 260px; }
.detail-post, .admin-control-card, .reply-section { padding: 18px; border: 1px solid #efe4d2; border-radius: 14px; background: #fffdf8; }
.detail-post.withdrawn { background: var(--el-fill-color-lighter); }
.detail-post h2 { margin: 12px 0 8px; line-height: 1.45; }
.detail-author { color: var(--el-text-color-secondary); font-size: 13px; }
.detail-content { margin: 16px 0 8px; color: var(--el-text-color-primary); line-height: 1.8; white-space: pre-wrap; }
.detail-actions { justify-content: flex-end; }
.admin-control-card { margin-top: 12px; background: #fff7e8; border-color: #edd5ae; }
.admin-control-title { margin-bottom: 10px; color: #9a6228; font-weight: 700; }
.admin-control-grid { display: grid; grid-template-columns: minmax(160px, 1fr) auto auto auto; gap: 10px; align-items: center; margin-bottom: 10px; }
.reply-section { margin-top: 12px; }
.reply-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.reply-heading h3 { margin: 0; }
.reply-heading span { color: var(--el-text-color-secondary); font-size: 13px; }
.reply-list { display: grid; gap: 2px; }
.reply-item { display: grid; grid-template-columns: 36px minmax(0, 1fr); gap: 10px; padding: 13px 0; border-bottom: 1px solid #f1e7d8; }
.reply-item.hidden { opacity: .72; }
.reply-avatar { display: grid; place-items: center; width: 36px; height: 36px; color: #8f6131; font-weight: 700; background: #fff3df; border-radius: 10px; }
.reply-main { min-width: 0; }
.reply-meta { flex-wrap: wrap; color: var(--el-text-color-secondary); font-size: 12px; }
.reply-main p { margin: 7px 0; line-height: 1.7; white-space: pre-wrap; }
.reply-actions { justify-content: flex-end; min-height: 24px; }
.reply-edit-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
.reply-composer { margin-top: 14px; padding-top: 14px; border-top: 1px solid #f1e7d8; }
.composer-footer { justify-content: space-between; margin-top: 8px; color: var(--el-text-color-secondary); font-size: 12px; }
.audit-card { display: grid; gap: 6px; padding: 12px 14px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
.audit-card span { color: var(--el-text-color-secondary); font-size: 12px; }
.audit-card pre { margin: 4px 0 0; padding: 8px; overflow: auto; color: var(--el-text-color-regular); font-size: 12px; white-space: pre-wrap; background: var(--el-fill-color-lighter); border-radius: 6px; }
@keyframes board-fade-in { from { opacity: 0; transform: translateY(7px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 1100px) { .board-filters { grid-template-columns: 1fr 1fr; } .filter-actions { justify-content: flex-start; } }
@media (max-width: 720px) { .board-toolbar { grid-template-columns: 1fr auto; } .board-title-group { grid-column: 1 / -1; grid-row: 1; } .board-toolbar .el-button { grid-column: 2; grid-row: 2; } .board-decor { top: auto; bottom: 8px; } .board-filters { grid-template-columns: 1fr; } .board-card { padding-right: 12px; gap: 10px; } .card-heading { align-items: flex-start; flex-direction: column; gap: 4px; } .admin-control-grid { grid-template-columns: 1fr 1fr; } .composer-footer { align-items: flex-start; flex-direction: column; } }
@media (prefers-reduced-motion: reduce) { .board-card, .emoji-grid button { animation: none; transition: none; } }
</style>
