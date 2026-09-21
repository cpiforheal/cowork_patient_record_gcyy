<template>
  <div class="health-archive-page">
    <header class="hap-head">
      <div class="hap-title">
        <strong>住院 · 门诊患者健康档案</strong>
        <small>入院确认为住院即由护理部维护跟进 · 门诊患者同步建档 · 按来访时间筛选 · 点击卡片查看梗概与全部来访</small>
      </div>
      <div class="hap-actions">
        <el-input
          v-model="keyword"
          placeholder="按姓名或病例编号搜索"
          clearable
          :prefix-icon="Search"
          style="width: 200px"
        />
        <el-checkbox v-model="onlyFollowed" border size="small">⭐ 只看关注</el-checkbox>
        <el-segmented v-model="careFilter" :options="careFilterOptions" />
        <el-segmented v-model="daysFilter" :options="daysFilterOptions" />
        <el-button :loading="loading" @click="loadCases">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </header>

    <div v-loading="loading" class="hap-carousel-wrap">
      <el-empty
        v-if="!loading && !filteredCases.length"
        description="当前筛选条件下暂无患者，可放宽时间或就诊类型"
        :image-size="72"
      />
      <AppleCardCarousel v-else :initial-scroll="0">
        <AppleCarouselItem
          v-for="(item, index) in filteredCases"
          :key="`${item.id}-${careFilter}-${daysFilter}`"
          :index="index"
        >
          <AppleCard
            :ref="el => setCardRef(el, index)"
            :card="{ title: item.patientName || '待补姓名', category: `${careLabel(item)} · ${item.visitCount} 次来访` }"
            :index="index"
            :gradient="cardGradient(item)"
            @opened="openDetail(item)"
          >
            <!-- 卡面：层级化梗概（emoji + 分色，尽可能多的字段） -->
            <template #category>
              <el-button
                class="hap-star"
                :class="{ 'is-on': isFollowed(item) }"
                size="small"
                text
                @click.stop="toggleFollow(item)"
                >{{ isFollowed(item) ? "★" : "☆" }}</el-button
              >
              <span class="hap-cat-ico">🏥</span>
              <span v-if="lampOf(item)" class="hap-lamp" :class="lampOf(item)!.cls">● {{ lampOf(item)!.text }}</span>
              <el-tag size="small" :type="careTagType(item)" effect="light">{{ careLabel(item) }}</el-tag>
              <el-tag size="small" :type="encounterStatusType(item.latestEncounter?.status)" effect="light">
                {{ encounterStatusLabel[item.latestEncounter?.status || ""] || "状态待定" }}
              </el-tag>
              <span class="hap-cat-visits">{{ item.visitCount }} 次来访</span>
            </template>
            <template #summary>
              <p>
                <i class="sum-ico">📞</i><label class="sum-label">手机号</label>
                <span class="is-phone">{{ item.patient?.phone || "未登记" }}</span>
              </p>
              <p>
                <i class="sum-ico">👤</i><label class="sum-label">性别年龄</label>
                <span class="is-plain">{{ item.patient?.gender || "—" }} · {{ item.patient?.age || "—" }} 岁</span>
              </p>
              <p class="is-block">
                <i class="sum-ico">📝</i><label class="sum-label">主诉摘要</label>
                <span class="is-complaint">{{ complaintOf(item) || "—" }}</span>
              </p>
              <p>
                <i class="sum-ico">🩺</i><label class="sum-label">病种方向</label>
                <span class="is-disease">{{ truncate(diseaseDirectionOf(item), 16) || "—" }}</span>
              </p>
              <p v-if="diseasesOf(item).length">
                <i class="sum-ico">🏷</i><label class="sum-label">病种标签</label>
                <span class="hap-disease-tags">
                  <i v-for="disease in diseasesOf(item)" :key="disease">{{ disease }}</i>
                </span>
              </p>
              <p>
                <i class="sum-ico">🏠</i><label class="sum-label">家庭住址</label>
                <span class="is-plain">{{ truncate(item.patient?.address || "未登记", 18) }}</span>
              </p>
              <p v-if="emotionsOf(item).length" class="is-block">
                <i class="sum-ico">😔</i><label class="sum-label">情绪问题</label>
                <span class="hap-emotion-chips">
                  <i
                    v-for="emotion in emotionsOf(item)"
                    :key="emotion"
                    :title="`心理疏导 · 主要情绪问题：${emotion}`"
                    >{{ emotion }}</i
                  >
                </span>
              </p>
            </template>
            <template #footer>
              <div class="hap-card-badges">
                <span class="hap-date-badge">🗓 {{ visitDate(item).slice(0, 10) || "日期待补" }}</span>
              </div>
              <div class="hap-card-foot">
                <span>{{ item.latestEncounter?.caseToken || "尚无子病历" }}</span>
                <span class="apple-card-enter">查看梗概 ›</span>
              </div>
            </template>

            <!-- 二级弹层 · 上方：主诉居中 + 完整病史 + 影像资料 -->
            <div class="hap-detail-block">
              <div class="hap-detail-caption">📋 病历信息</div>
              <p class="hap-detail-lead">{{ truncate(complaintOf(item), 90) || "暂无登记主诉" }}</p>
              <div v-loading="workspaceLoadingOf(item)" class="hap-history-zone">
                <template v-if="workspaceOf(item)">
                  <div class="hap-history-grid">
                    <div v-for="entry in historyEntriesOf(item)" :key="entry.label" class="hap-history-item">
                      <label>{{ entry.emoji }} {{ entry.label }}</label>
                      <p>{{ entry.value }}</p>
                    </div>
                  </div>
                  <div class="hap-attachments">
                    <div class="hap-detail-caption is-sub">🖼 影像资料 · {{ workspaceOf(item)?.attachments.length || 0 }} 份</div>
                    <AttachmentPreviewGallery
                      v-if="workspaceOf(item)?.attachments.length"
                      :attachments="workspaceOf(item)!.attachments"
                      compact
                      @download="downloadAttachment"
                    />
                    <el-empty v-else :image-size="56" description="该次来访暂无影像资料" />
                  </div>
                </template>
                <el-empty v-else-if="!workspaceLoadingOf(item)" :image-size="56" description="暂无病历详情" />
              </div>
              <div class="hap-detail-facts">
                <div class="hap-fact"><label>🔖 病例标识</label><strong>{{ item.latestEncounter?.caseToken || "—" }}</strong></div>
                <div class="hap-fact"><label>📞 手机号</label><strong>{{ item.patient?.phone || "未登记" }}</strong></div>
                <div class="hap-fact"><label>🗓 最新接诊</label><strong>{{ visitDate(item) || "—" }}</strong></div>
                <div class="hap-fact"><label>🔁 来访次数</label><strong>{{ item.visitCount }} 次</strong></div>
                <div class="hap-fact"><label>🩺 病种方向</label><strong>{{ diseaseDirectionOf(item) || "—" }}</strong></div>
                <div class="hap-fact"><label>🏷 病种标签</label><strong>{{ diseasesOf(item).join(" / ") || "—" }}</strong></div>
              </div>
              <div class="hap-detail-actions">
                <el-button
                  type="primary"
                  size="large"
                  :disabled="!item.latestEncounter"
                  @click="maintainFrom(item, index)"
                >
                  进入健康管理档案维护
                </el-button>
                <small>维护界面左栏填写 · 右栏实时预览合并文档</small>
              </div>
            </div>

            <!-- 二级弹层 · 下方：拆步横向时间轴（codepen dRoMwo 复刻） -->
            <div class="hap-detail-block is-timeline">
              <div class="hap-detail-caption">
                🕰 来访时间轴 · 全部 {{ stepsOf(item).length }} 步（岗位操作轨迹）
                <small v-if="stepsOf(item).length">· 每步已拆分 · 左右箭头翻页</small>
              </div>
              <div v-if="historyLoadingOf(item.id)" v-loading="true" class="hap-timeline-loading" />
              <el-empty v-else-if="!stepsOf(item).length" :image-size="64" description="暂无岗位操作记录" />
              <template v-else>
                <div class="hap-tl-viewport" :ref="el => setViewportRef(el, item.id)">
                  <div class="hap-tl-track" :style="{ left: `-${tlOffsets[item.id] || 0}px` }">
                    <div v-for="(step, stepIndex) in stepsOf(item)" :key="step.key" class="hap-tl-node">
                      <!-- 轴上：大号时间 -->
                      <div class="hap-tl-time-top">{{ step.timePart }}</div>
                      <!-- 轴：双圆点骑在贯穿虚线上 -->
                      <div class="hap-tl-line" />
                      <!-- 轴下：半透明轨迹卡 -->
                      <div class="hap-tl-card">
                        <div class="hap-tl-title">{{ step.role }}</div>
                        <div class="hap-tl-info">{{ step.actions.join("、") }}</div>
                        <div class="hap-tl-name">{{ step.visitType === "INITIAL" ? "初诊" : `第 ${step.visitNo} 次` }} · {{ step.datePart }}</div>
                      </div>
                      <div v-if="stepIndex === stepsOf(item).length - 1" class="hap-tl-visit-ops">
                        <el-button
                          v-for="visit in historyOf(item.id)"
                          :key="visit.id"
                          size="small"
                          text
                          type="primary"
                          @click="maintainVisit(visit)"
                        >
                          {{ visit.visitType === "INITIAL" ? "初诊" : `第 ${visit.visitNo} 次` }}病历 ›
                        </el-button>
                      </div>
                    </div>
                  </div>
                </div>
                <!-- codepen btn1/btn2 复刻：双箭头翻页 -->
                <div class="hap-tl-arrows">
                  <button
                    class="hap-tl-arrow"
                    :class="{ 'is-hidden': (tlOffsets[item.id] || 0) <= 0 }"
                    aria-label="上一页"
                    @click="tlTurn(item, -1, $event)"
                  >
                    <svg viewBox="0 0 284.929 284.929" width="20" height="20">
                      <path
                        fill="currentColor"
                        d="M135.899,167.877c1.902,1.902,4.093,2.851,6.567,2.851s4.661-0.948,6.562-2.851L282.082,34.829 c1.902-1.903,2.847-4.093,2.847-6.567s-0.951-4.665-2.847-6.567L267.808,7.417c-1.902-1.903-4.093-2.853-6.57-2.853 c-2.471,0-4.661,0.95-6.563,2.853L142.466,119.622L30.262,7.417c-1.903-1.903-4.093-2.853-6.567-2.853 c-2.475,0-4.665,0.95-6.567,2.853L2.856,21.695C0.95,23.597,0,25.784,0,28.262c0,2.478,0.953,4.665,2.856,6.567L135.899,167.877z"
                      />
                      <path
                        fill="currentColor"
                        d="M267.808,117.053c-1.902-1.903-4.093-2.853-6.57-2.853c-2.471,0-4.661,0.95-6.563,2.853L142.466,229.257L30.262,117.05 c-1.903-1.903-4.093-2.853-6.567-2.853c-2.475,0-4.665,0.95-6.567,2.853L2.856,131.327C0.95,133.23,0,135.42,0,137.893 c0,2.474,0.953,4.665,2.856,6.57l133.043,133.046c1.902,1.903,4.093,2.854,6.567,2.854s4.661-0.951,6.562-2.854l133.054-133.046 c1.902-1.903,2.847-4.093,2.847-6.565c0-2.474-0.951-4.661-2.847-6.567L267.808,117.053z"
                      />
                    </svg>
                  </button>
                  <button
                    class="hap-tl-arrow"
                    :class="{ 'is-hidden': (tlOffsets[item.id] || 0) >= (tlMaxMap[item.id] ?? 0) }"
                    aria-label="下一页"
                    @click="tlTurn(item, 1, $event)"
                  >
                    <svg viewBox="0 0 284.929 284.929" width="20" height="20">
                      <path
                        fill="currentColor"
                        d="M135.899,167.877c1.902,1.902,4.093,2.851,6.567,2.851s4.661-0.948,6.562-2.851L282.082,34.829 c1.902-1.903,2.847-4.093,2.847-6.567s-0.951-4.665-2.847-6.567L267.808,7.417c-1.902-1.903-4.093-2.853-6.57-2.853 c-2.471,0-4.661,0.95-6.563,2.853L142.466,119.622L30.262,7.417c-1.903-1.903-4.093-2.853-6.567-2.853 c-2.475,0-4.665,0.95-6.567,2.853L2.856,21.695C0.95,23.597,0,25.784,0,28.262c0,2.478,0.953,4.665,2.856,6.567L135.899,167.877z"
                      />
                      <path
                        fill="currentColor"
                        d="M267.808,117.053c-1.902-1.903-4.093-2.853-6.57-2.853c-2.471,0-4.661,0.95-6.563,2.853L142.466,229.257L30.262,117.05 c-1.903-1.903-4.093-2.853-6.567-2.853c-2.475,0-4.665,0.95-6.567,2.853L2.856,131.327C0.95,133.23,0,135.42,0,137.893 c0,2.474,0.953,4.665,2.856,6.57l133.043,133.046c1.902,1.903,4.093,2.854,6.567,2.854s4.661-0.951,6.562-2.854l133.054-133.046 c1.902-1.903,2.847-4.093,2.847-6.565c0-2.474-0.951-4.661-2.847-6.567L267.808,117.053z"
                      />
                    </svg>
                  </button>
                </div>
              </template>
            </div>

            <!-- 二级弹层 · 下方：复诊时间轴（检查室复诊记录 · 琥珀色系与来访轴区分 · 图片重点展示） -->
            <div class="hap-detail-block is-recheck">
              <div class="hap-detail-caption">
                💊 复诊时间轴 · 共 {{ followUpsOf(item.id).length }} 次（检查室复诊记录）
                <small v-if="followUpsOf(item.id).length > 2">· 横向滑动查看 →</small>
              </div>
              <div v-if="followUpLoadingOf(item.id)" v-loading="true" class="hap-timeline-loading" />
              <el-empty
                v-else-if="!followUpsOf(item.id).length"
                :image-size="64"
                description="该患者暂无复诊记录 · 检查室创建复诊后自动入轴"
              />
              <div v-else>
                <div class="hap-fu-viewport" :ref="el => setFuViewportRef(el, item.id)">
                  <div class="hap-fu-track" :style="{ left: `-${fuOffsets[item.id] || 0}px` }">
                <div v-for="visit in followUpsOf(item.id)" :key="visit.id" class="hap-fu-node">
                  <!-- 轴上：创建时间为主索引（系统精确记录） -->
                  <div class="hap-fu-time">{{ visit.createdAt?.slice(0, 10) }}</div>
                  <div class="hap-fu-axis">
                    <i class="hap-fu-dot" />
                  </div>
                  <!-- 轴下：琥珀信息卡（图片重点展示） -->
                  <div class="hap-fu-card">
                    <div class="hap-fu-title">
                      <span class="hap-fu-badge">第 {{ visit.seq }} 次</span>
                      {{ visit.reason }}
                    </div>
                    <div v-if="visit.images.length" class="hap-fu-images">
                      <el-image
                        v-for="image in visit.images"
                        :key="image.id"
                        class="hap-fu-image"
                        :src="followUpImageUrls[image.id]"
                        :preview-src-list="followUpPreviewList(visit)"
                        :initial-index="visit.images.indexOf(image)"
                        :preview-teleported="true"
                        fit="cover"
                        lazy
                      >
                        <template #error>
                          <div class="hap-fu-image-fallback">{{ image.fileName }}</div>
                        </template>
                      </el-image>
                      <span v-if="visit.images.length > 1" class="hap-fu-image-count">{{ visit.images.length }} 图</span>
                    </div>
                    <div v-else class="hap-fu-image-empty">🩹 本次复诊未采集图片</div>
                    <div v-if="visit.conditionNote" class="hap-fu-info">{{ visit.conditionNote }}</div>
                    <div class="hap-fu-meta">
                      <el-tag v-if="visit.nextReviewDate" type="success" effect="light" size="small">
                        下次复查：{{ visit.nextReviewDate }}
                      </el-tag>
                      <span class="hap-fu-by">{{ visit.createdBy }} · {{ visit.createdByRole || "检查室" }}</span>
                    </div>
                  </div>
                </div>
                  </div>
                </div>
                <!-- codepen 双箭头：复诊轴翻页 -->
                <div class="hap-tl-arrows is-amber">
                  <button
                    class="hap-tl-arrow"
                    :class="{ 'is-hidden': (fuOffsets[item.id] || 0) <= 0 }"
                    aria-label="上一页"
                    @click="fuTurn(item, -1)"
                  >
                    <svg viewBox="0 0 284.929 284.929" width="20" height="20">
                      <path fill="currentColor" d="M135.899,167.877c1.902,1.902,4.093,2.851,6.567,2.851s4.661-0.948,6.562-2.851L282.082,34.829 c1.902-1.903,2.847-4.093,2.847-6.567s-0.951-4.665-2.847-6.567L267.808,7.417c-1.902-1.903-4.093-2.853-6.57-2.853 c-2.471,0-4.661,0.95-6.563,2.853L142.466,119.622L30.262,7.417c-1.903-1.903-4.093-2.853-6.567-2.853 c-2.475,0-4.665,0.95-6.567,2.853L2.856,21.695C0.95,23.597,0,25.784,0,28.262c0,2.478,0.953,4.665,2.856,6.567L135.899,167.877z" />
                      <path fill="currentColor" d="M267.808,117.053c-1.902-1.903-4.093-2.853-6.57-2.853c-2.471,0-4.661,0.95-6.563,2.853L142.466,229.257L30.262,117.05 c-1.903-1.903-4.093-2.853-6.567-2.853c-2.475,0-4.665,0.95-6.567,2.853L2.856,131.327C0.95,133.23,0,135.42,0,137.893 c0,2.474,0.953,4.665,2.856,6.57l133.043,133.046c1.902,1.903,4.093,2.854,6.567,2.854s4.661-0.951,6.562-2.854l133.054-133.046 c1.902-1.903,2.847-4.093,2.847-6.565c0-2.474-0.951-4.661-2.847-6.567L267.808,117.053z" />
                    </svg>
                  </button>
                  <button
                    class="hap-tl-arrow"
                    :class="{ 'is-hidden': (fuOffsets[item.id] || 0) >= (fuMaxMap[item.id] ?? 0) }"
                    aria-label="下一页"
                    @click="fuTurn(item, 1)"
                  >
                    <svg viewBox="0 0 284.929 284.929" width="20" height="20">
                      <path fill="currentColor" d="M135.899,167.877c1.902,1.902,4.093,2.851,6.567,2.851s4.661-0.948,6.562-2.851L282.082,34.829 c1.902-1.903,2.847-4.093,2.847-6.567s-0.951-4.665-2.847-6.567L267.808,7.417c-1.902-1.903-4.093-2.853-6.57-2.853 c-2.471,0-4.661,0.95-6.563,2.853L142.466,119.622L30.262,7.417c-1.903-1.903-4.093-2.853-6.567-2.853 c-2.475,0-4.665,0.95-6.567,2.853L2.856,21.695C0.95,23.597,0,25.784,0,28.262c0,2.478,0.953,4.665,2.856,6.567L135.899,167.877z" />
                      <path fill="currentColor" d="M267.808,117.053c-1.902-1.903-4.093-2.853-6.57-2.853c-2.471,0-4.661,0.95-6.563,2.853L142.466,229.257L30.262,117.05 c-1.903-1.903-4.093-2.853-6.567-2.853c-2.475,0-4.665,0.95-6.567,2.853L2.856,131.327C0.95,133.23,0,135.42,0,137.893 c0,2.474,0.953,4.665,2.856,6.57l133.043,133.046c1.902,1.903,4.093,2.854,6.567,2.854s4.661-0.951,6.562-2.854l133.054-133.046 c1.902-1.903,2.847-4.093,2.847-6.565c0-2.474-0.951-4.661-2.847-6.567L267.808,117.053z" />
                    </svg>
                  </button>
                </div>
              </div>
            </div>

            <!-- 弹窗行动条：新增复诊 / 电话话术 / 联系留痕（就地操作，不退出档案） -->
            <div class="hap-action-bar">
              <el-button type="primary" @click="openFollowUpDialog(item)">
                ➕ 新增复诊记录
              </el-button>
              <el-button type="success" plain @click="openScriptDialog(item)">
                🗣️ 查看话术
              </el-button>
              <el-button type="warning" plain @click="markContacted(item)">
                📞 电话已联系
              </el-button>
              <small v-if="contactLog[item.id]" class="hap-action-note">最近联系：{{ contactLog[item.id] }}</small>
              <small v-else class="hap-action-note">操作就地完成 · 记录即时同步到复诊时间轴与召回看板</small>
            </div>
          </AppleCard>
        </AppleCarouselItem>
      </AppleCardCarousel>
    </div>

    <FollowUpCreateDialog
      v-model="followUpDialogVisible"
      :patient-case-id="followUpDialogFor?.id || ''"
      :encounter-id="followUpDialogFor?.latestEncounter?.id"
      :patient-name="followUpDialogFor?.patientName"
      @created="onFollowUpCreated"
    />

    <FollowUpScriptDialog
      v-model:visible="scriptDialogVisible"
      :visit="scriptDialogVisit"
      :patient-case-id="scriptDialogFor?.id"
      :patient-name="scriptDialogFor?.patientName"
      :gender="scriptDialogFor?.gender"
      :user-name="userStore.userInfo.name"
      @open-archive="encounterId => openEncounter(encounterId, scriptDialogFor?.patientName || '')"
    />

    <HealthArchiveDialog
      v-model="archiveVisible"
      :encounter-id="activeEncounterId"
      :encounter-patient-name="activePatientName"
    />
  </div>
</template>

<script setup lang="ts" name="healthArchive">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import { Refresh, Search } from "@element-plus/icons-vue";
import AppleCardCarousel from "@/components/inspira/AppleCardCarousel.vue";
import AppleCarouselItem from "@/components/inspira/AppleCarouselItem.vue";
import AppleCard from "@/components/inspira/AppleCard.vue";
import AttachmentPreviewGallery from "@/views/preAi/encounters/components/AttachmentPreviewGallery.vue";
import HealthArchiveDialog from "@/views/preAi/encounters/components/HealthArchiveDialog.vue";
import { encounterStatusLabel, preAiStages, stageStatusLabel } from "@/views/preAi/encounters/fieldConfig";
import type { PreAiAttachment, PreAiAuditLog, PreAiStageCode, PreAiWorkspace } from "@/api/modules/clinic";
import { getPreAiAttachmentObjectUrlApi } from "@/api/modules/clinic";
import {
  getPreAiEncounterHistoryApi,
  getPreAiPatientCasesApi,
  getPreAiResponsibilityTimelineApi,
  getPreAiWorkspaceApi,
  type PreAiEncounterHistoryItem,
  type PreAiPatientCase
} from "@/api/modules/clinic/preAi";
import { getHealthArchiveEmotionMapApi } from "@/api/modules/clinic/healthArchive";
import { fetchFollowUpImageApi, loadFollowUpVisitsApi, loadRecallSummaryApi, markRecallContactedApi, type FollowUpVisit } from "@/api/modules/clinic/followUp";
import FollowUpCreateDialog from "@/views/preAi/encounters/components/FollowUpCreateDialog.vue";
import FollowUpScriptDialog from "@/views/preAi/encounters/components/FollowUpScriptDialog.vue";
import { useUserStore } from "@/stores/modules/user";

const route = useRoute();
const cases = ref<PreAiPatientCase[]>([]);
const loading = ref(false);
const keyword = ref("");
const careFilter = ref("全部");
const careFilterOptions = ["全部", "住院", "门诊"];
const daysFilter = ref("全部");
const daysFilterOptions = ["全部", "近7天", "近30天", "近90天"];

const archiveVisible = ref(false);
const activeEncounterId = ref("");
const activePatientName = ref("");

// ---------- 基础取值 ----------
const careLabel = (item: PreAiPatientCase) => {
  const careType = String(
    item.latestEncounter?.normalizedCareType || item.latestEncounter?.inventoryCareType || item.latestEncounter?.route || ""
  );
  return careType.includes("inpatient") ? "住院" : "门诊";
};
const careTagType = (item: PreAiPatientCase): "warning" | "success" =>
  careLabel(item) === "住院" ? "warning" : "success";
const cardGradient = (item: PreAiPatientCase) =>
  careLabel(item) === "住院"
    ? "linear-gradient(168deg, #fffdf5 0%, #fdf3d8 58%, #fdeac0 100%)"
    : "linear-gradient(168deg, #f4fbfa 0%, #ddf4f1 58%, #cfeeea 100%)";
const diseasesOf = (item: PreAiPatientCase) => {
  const diseases = item.patient?.clinicalTemplateDiseases;
  return Array.isArray(diseases) ? diseases.map(String).filter(Boolean) : [];
};
const complaintOf = (item: PreAiPatientCase) =>
  String(
    item.patient?.registrationChiefComplaint || item.patient?.registrationSymptoms || item.patient?.chiefComplaint || ""
  ).trim();
const diseaseDirectionOf = (item: PreAiPatientCase) => {
  const directions = Array.isArray(item.patient?.diseaseDirections)
    ? item.patient.diseaseDirections.map(String).filter(Boolean).slice(0, 2).join(" / ")
    : String(item.patient?.diseaseDirection || item.patient?.inspectionDiseaseDirections || "").trim();
  return directions || diseasesOf(item).slice(0, 2).join(" / ");
};
const visitDate = (item: PreAiPatientCase) =>
  String(item.patient?.visitDate || item.latestEncounter?.visitDate || "")
    .replace("T", " ")
    .slice(0, 16);
const truncate = (value: string, maxLength = 60) => {
  const text = String(value || "").trim();
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
};

const DAYS_BY_LABEL: Record<string, number> = { 全部: 0, 近7天: 7, 近30天: 30, 近90天: 90 };

const filteredCases = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  const days = DAYS_BY_LABEL[daysFilter.value] || 0;
  const cutoff = days ? new Date(Date.now() - days * 86400000).toISOString().slice(0, 10) : "";
  return cases.value.filter(item => {
    if (onlyFollowed.value && !followedIds.value.has(item.id)) return false;
    if (careFilter.value !== "全部" && careLabel(item) !== careFilter.value) return false;
    if (cutoff) {
      const dateText = String(visitDate(item)).slice(0, 10);
      if (dateText && dateText < cutoff) return false;
    }
    if (!kw) return true;
    return (
      String(item.patientName || "")
        .toLowerCase()
        .includes(kw) ||
      String(item.latestEncounter?.caseToken || "")
        .toLowerCase()
        .includes(kw)
    );
  });
});

// ---------- P3 医生端即时监督：关注星标 / 预警灯 ----------
const FOLLOW_KEY = "ha-followed-cases";
const followedIds = ref<Set<string>>(new Set(JSON.parse(localStorage.getItem(FOLLOW_KEY) || "[]")));
const isFollowed = (item: PreAiPatientCase) => followedIds.value.has(item.id);
const toggleFollow = (item: PreAiPatientCase) => {
  const next = new Set(followedIds.value);
  if (next.has(item.id)) next.delete(item.id);
  else next.add(item.id);
  followedIds.value = next;
  localStorage.setItem(FOLLOW_KEY, JSON.stringify([...next]));
};
const onlyFollowed = ref(false);

const recallOverdue = ref<Set<string>>(new Set());
const recallDueSoon = ref<Set<string>>(new Set());
const loadRecallLamps = async () => {
  try {
    const { data } = await loadRecallSummaryApi();
    recallOverdue.value = new Set((data.overdue || []).map(row => row.patientCaseId));
    recallDueSoon.value = new Set((data.dueSoon || []).map(row => row.patientCaseId));
  } catch {
    // 预警灯属增强信息，失败不打扰
  }
};
const lampOf = (item: PreAiPatientCase) => {
  if (recallOverdue.value.has(item.id)) return { text: "逾期未复", cls: "is-overdue" };
  if (recallDueSoon.value.has(item.id)) return { text: "即将到期", cls: "is-duesoon" };
  return null;
};

// ---------- 情绪问题速查表（健康管理档案 · 四、心理疏导） ----------
const emotionMap = ref<Record<string, string[]>>({});
const emotionsOf = (item: PreAiPatientCase) => {
  const encounterId = item.latestEncounter?.id;
  return (encounterId && emotionMap.value[encounterId]) || [];
};
const loadEmotionMap = async () => {
  try {
    const { data } = await getHealthArchiveEmotionMapApi();
    emotionMap.value = Object.fromEntries(
      Object.entries(data || {}).map(([encounterId, brief]) => [encounterId, brief.emotionIssues || []])
    );
  } catch {
    // 情绪标记属增强信息，加载失败不打扰主流程
  }
};

// ---------- 病历详情（工作台：完整病史 + 影像资料） ----------
const workspaceMap = ref<Record<string, PreAiWorkspace>>({});
const workspaceLoadingMap = ref<Record<string, boolean>>({});
const workspaceOf = (item: PreAiPatientCase) => {
  const encounterId = item.latestEncounter?.id;
  return (encounterId && workspaceMap.value[encounterId]) || null;
};
const workspaceLoadingOf = (item: PreAiPatientCase) => Boolean(workspaceLoadingMap.value[item.latestEncounter?.id || ""]);
const stageDataOf = (workspace: PreAiWorkspace | null, code: string): Record<string, any> =>
  workspace?.stages.find(stage => stage.stageCode === code)?.data || {};
const textOf = (value: unknown): string => {
  if (Array.isArray(value)) {
    return value
      .map(item => String(item ?? "").trim())
      .filter(Boolean)
      .join("、");
  }
  return String(value ?? "").trim();
};
const firstText = (...values: unknown[]): string => {
  for (const value of values) {
    const text = textOf(value);
    if (text) return text;
  }
  return "";
};
const historyEntriesOf = (item: PreAiPatientCase): Array<{ emoji: string; label: string; value: string }> => {
  const workspace = workspaceOf(item);
  if (!workspace) return [];
  const registration = stageDataOf(workspace, "REGISTRATION");
  const reception = stageDataOf(workspace, "RECEPTION");
  const inspection = stageDataOf(workspace, "INSPECTION");
  const tcm = stageDataOf(workspace, "TCM");
  const doctor = stageDataOf(workspace, "DOCTOR");
  const surgery = stageDataOf(workspace, "SURGERY");
  const entries: Array<{ emoji: string; label: string; value: string }> = [];
  const push = (emoji: string, label: string, value: unknown) => {
    const text = firstText(value);
    if (text) entries.push({ emoji, label, value: text.length > 160 ? `${text.slice(0, 160)}…` : text });
  };
  push("📖", "现病史", reception.presentIllnessOverride || reception.presentIllness);
  push("⚠️", "过敏史", [registration.allergyHistory, registration.allergyHistoryNote]);
  push("📜", "既往疾病史", reception.chronicDiseaseItems);
  push("🏥", "手术史", reception.surgicalHistoryItems);
  push("👤", "个人史", registration.personalHistory);
  push("👨‍👩‍👧", "家族史", reception.familyHistory);
  push("🔍", "专科检查", firstText(inspection.factualConclusion, inspection.inspectionNarrative));
  push("🌿", "中医辨证", [textOf(tcm.tcmDisease), textOf(tcm.primarySyndrome)].filter(Boolean).join(" · "));
  push("🌿", "兼夹证", tcm.concurrentSyndrome);
  push("💊", "西医主诊断", doctor.primaryWesternDiagnosis);
  push("🧭", "治疗路径", doctor.treatmentPath === "SURGICAL" ? "手术治疗" : doctor.treatmentPath === "CONSERVATIVE" ? "保守治疗" : "");
  push("🔪", "拟行主术式", firstText(doctor.plannedPrimaryOperation, doctor.plannedOperationName));
  push("🔪", "实际主术式", firstText(surgery.actualPrimaryOperation, surgery.actualOperationName));
  push("📅", "手术日期", surgery.operationDate);
  return entries;
};
const loadWorkspace = async (encounterId: string) => {
  if (!encounterId || workspaceMap.value[encounterId] || workspaceLoadingMap.value[encounterId]) return;
  workspaceLoadingMap.value[encounterId] = true;
  try {
    const { data } = await getPreAiWorkspaceApi(encounterId);
    workspaceMap.value[encounterId] = data;
  } catch {
    // 详情加载失败时展示空态即可
  } finally {
    workspaceLoadingMap.value[encounterId] = false;
  }
};
const downloadAttachment = async (attachment: PreAiAttachment) => {
  try {
    const url = await getPreAiAttachmentObjectUrlApi(attachment);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = attachment.fileName;
    anchor.click();
  } catch {
    ElMessage.error("附件下载失败");
  }
};

// ---------- 来访历史 + 岗位操作轨迹 ----------
const historyMap = ref<Record<string, PreAiEncounterHistoryItem[]>>({});
const historyLoading = ref<Record<string, boolean>>({});
const historyOf = (patientCaseId: string) => historyMap.value[patientCaseId] || [];
const historyLoadingOf = (patientCaseId: string) => Boolean(historyLoading.value[patientCaseId]);

const ROLE_LABELS: Record<string, string> = {
  frontdesk: "前台登记",
  reception: "接诊",
  inspection: "检查室",
  nurse: "护理与手术",
  doctor: "医生",
  lab: "检验",
  tcm: "中医",
  quality: "质控",
  admin: "管理员"
};
const ACTION_LABELS: Record<string, string> = {
  "encounter.create": "创建病历",
  "registration.complete-and-issue": "登记并发号",
  "encounter.followup.register-and-issue": "复诊登记并发号",
  "stage.save": "保存阶段草稿",
  "stage.complete": "完成并交接阶段",
  "stage.correct": "纠错并重新提交",
  "stage.return": "退回阶段",
  "attachment.upload": "上传附件",
  "attachment.void": "作废附件",
  "lab.report.save": "保存化验报告",
  "lab.complete": "确认化验完成",
  "review.confirm": "完成病历复核",
  "export.generate": "生成病历导出",
  "admission-profile.save": "保存住院补录",
  "admission-profile.complete": "完成住院补录"
};
const actionLabel = (action: string) => ACTION_LABELS[action] || action;
interface ResponsibilityGroup {
  at: string;
  role: string;
  actions: string[];
}
const respGroupsMap = ref<Record<string, ResponsibilityGroup[]>>({});
const respLoadingMap = ref<Record<string, boolean>>({});
const responsibilityGroupsOf = (encounterId: string) => respGroupsMap.value[encounterId] || [];
const respLoadingOf = (encounterId: string) => Boolean(respLoadingMap.value[encounterId]);
const eventTime = (event: PreAiAuditLog) =>
  String(event.submittedAt || event.occurredAt || event.createdAt || "").replace("T", " ").slice(0, 19);
const loadResponsibility = async (encounterId: string, patientCaseId?: string) => {
  if (!encounterId || respGroupsMap.value[encounterId] || respLoadingMap.value[encounterId]) return;
  respLoadingMap.value[encounterId] = true;
  try {
    const { data } = await getPreAiResponsibilityTimelineApi(encounterId);
    const events: PreAiAuditLog[] = data.events || [];
    const groups: ResponsibilityGroup[] = [];
    events.forEach(event => {
      const role = ROLE_LABELS[event.operatorRole || ""] || event.operatorRole || "历史操作";
      const previous = groups[groups.length - 1];
      if (previous && previous.role === role) {
        const label = actionLabel(event.action || "");
        if (!previous.actions.includes(label)) previous.actions.push(label);
        return;
      }
      groups.push({ at: eventTime(event), role, actions: [actionLabel(event.action || "")] });
    });
    respGroupsMap.value[encounterId] = groups;
    if (patientCaseId) await recomputeTlMax(patientCaseId);
  } catch {
    respGroupsMap.value[encounterId] = [];
  } finally {
    respLoadingMap.value[encounterId] = false;
  }
};
const loadVisitHistory = async (item: PreAiPatientCase) => {
  if (!item.id || historyMap.value[item.id] || historyLoading.value[item.id]) return;
  historyLoading.value[item.id] = true;
  try {
    const { data } = await getPreAiEncounterHistoryApi(item.id);
    const encounters = data.encounters || [];
    historyMap.value[item.id] = encounters;
    encounters.forEach(visit => void loadResponsibility(visit.id, item.id));
  } catch (error) {
    ElMessage.error((error as Error).message || "来访历史加载失败");
  } finally {
    historyLoading.value[item.id] = false;
    // 视口在 loading 结束后才渲染，重算必须放 finally 之后
    await recomputeTlMax(item.id);
  }
};

// ---------- 复诊时间轴（检查室复诊记录 · 图片重点展示） ----------
const followUpMap = ref<Record<string, FollowUpVisit[]>>({});
const followUpLoading = ref<Record<string, boolean>>({});
const followUpImageUrls = ref<Record<string, string>>({});
const followUpsOf = (patientCaseId: string) => followUpMap.value[patientCaseId] || [];
const followUpLoadingOf = (patientCaseId: string) => Boolean(followUpLoading.value[patientCaseId]);
const loadFollowUps = async (item: PreAiPatientCase) => {
  if (!item.id || followUpMap.value[item.id] || followUpLoading.value[item.id]) return;
  followUpLoading.value[item.id] = true;
  try {
    const { data } = await loadFollowUpVisitsApi(item.id);
    const visits = data.visits || [];
    followUpMap.value[item.id] = visits;
    // 复诊图片鉴权加载（objectURL 缓存）
    for (const image of visits.flatMap(visit => visit.images)) {
      if (followUpImageUrls.value[image.id]) continue;
      try {
        followUpImageUrls.value[image.id] = await fetchFollowUpImageApi(image.id);
      } catch {
        followUpImageUrls.value[image.id] = "";
      }
    }
  } catch {
    // 复诊记录加载失败时展示空态即可
  } finally {
    followUpLoading.value[item.id] = false;
    // 视口在 loading 结束后才渲染，重算必须放 finally 之后
    await recomputeFuMax(item.id);
  }
};
const followUpPreviewList = (visit: FollowUpVisit) =>
  visit.images.map(image => followUpImageUrls.value[image.id]).filter(Boolean) as string[];

// 行动条：新增复诊对话框 + 联系留痕
const followUpDialogVisible = ref(false);
const followUpTarget = ref<FollowUpVisit | null>(null);
const contactLog = ref<Record<string, string>>({});
const openFollowUpDialog = (item: PreAiPatientCase) => {
  followUpTarget.value = null;
  followUpDialogFor.value = item;
  followUpDialogVisible.value = true;
};
const followUpDialogFor = ref<PreAiPatientCase | null>(null);
const onFollowUpCreated = async (visit: FollowUpVisit) => {
  const owner = followUpDialogFor.value;
  if (owner?.id) {
    followUpMap.value[owner.id] = [...(followUpMap.value[owner.id] || []), visit].sort((a, b) => a.seq - b.seq);
  }
  ElMessage.success(`第 ${visit.seq} 次复诊记录已保存并同步到复诊时间轴`);
};
const markContacted = async (item: PreAiPatientCase) => {
  const latest = (followUpsOf(item.id) || []).slice().sort((a, b) => b.seq - a.seq)[0];
  if (!latest) {
    ElMessage.warning("该患者暂无复诊记录，无法标记联系");
    return;
  }
  try {
    const { data } = await markRecallContactedApi(latest.id);
    contactLog.value[item.id] = `${data.contactedAt} · ${data.contactedBy}`;
    ElMessage.success(`已记录联系 ${item.patientName || ""}`);
  } catch (error: any) {
    ElMessage.error(error?.message || "联系留痕失败");
  }
};

// 随访话术：按最新复诊记录渲染标准话术，支持复制与通话留痕
const userStore = useUserStore();
const scriptDialogVisible = ref(false);
const scriptDialogFor = ref<PreAiPatientCase | null>(null);
const scriptDialogVisit = ref<FollowUpVisit | null>(null);
const openScriptDialog = (item: PreAiPatientCase) => {
  const latest = (followUpsOf(item.id) || []).slice().sort((a, b) => b.seq - a.seq)[0];
  if (!latest) {
    ElMessage.warning("该患者暂无复诊记录，请先新增复诊记录再查看话术");
    return;
  }
  scriptDialogFor.value = item;
  scriptDialogVisit.value = latest;
  scriptDialogVisible.value = true;
};

// 复诊轴翻页（与来访轴同款 codepen 平移）
const fuOffsets = ref<Record<string, number>>({});
const fuViewportRefs = ref<Record<string, HTMLElement | null>>({});
const setFuViewportRef = (el: unknown, patientCaseId: string) => {
  fuViewportRefs.value[patientCaseId] = el instanceof HTMLElement ? el : null;
};
const fuMaxMap = ref<Record<string, number>>({});
const recomputeFuMax = async (patientCaseId: string) => {
  await nextTick();
  const viewport = fuViewportRefs.value[patientCaseId];
  if (!viewport) return;
  const track = viewport.querySelector<HTMLElement>(".hap-fu-track");
  fuMaxMap.value[patientCaseId] = track ? Math.max(0, track.scrollWidth - viewport.clientWidth) : 0;
};
const fuMaxOffset = (patientCaseId: string) => fuMaxMap.value[patientCaseId] ?? 0;
const fuTurn = (item: PreAiPatientCase, direction: 1 | -1) => {
  const patientCaseId = item.id;
  const viewport = fuViewportRefs.value[patientCaseId];
  if (!viewport) return;
  const track = viewport.querySelector<HTMLElement>(".hap-fu-track");
  if (!track) return;
  const page = Math.max(viewport.clientWidth - 60, 200);
  const max = Math.max(0, track.scrollWidth - viewport.clientWidth);
  fuOffsets.value[patientCaseId] = Math.min(Math.max((fuOffsets.value[patientCaseId] || 0) + direction * page, 0), max);
};

// ---------- 拆步横向时间轴（codepen dRoMwo 复刻） ----------
interface TlStep {
  key: string;
  encounterId: string;
  visitNo: number;
  visitType: string;
  datePart: string;
  timePart: string;
  role: string;
  actions: string[];
}
const stepsOf = (item: PreAiPatientCase): TlStep[] => {
  const steps: TlStep[] = [];
  historyOf(item.id).forEach(visit => {
    responsibilityGroupsOf(visit.id).forEach((group, groupIndex) => {
      const at = group.at;
      steps.push({
        key: `${visit.id}-${groupIndex}`,
        encounterId: visit.id,
        visitNo: visit.visitNo,
        visitType: visit.visitType,
        datePart: at.slice(0, 10),
        timePart: at.slice(11) || at,
        role: group.role,
        actions: group.actions
      });
    });
  });
  return steps;
};
const tlOffsets = ref<Record<string, number>>({});
const viewportRefs = ref<Record<string, HTMLElement | null>>({});
const setViewportRef = (el: unknown, patientCaseId: string) => {
  viewportRefs.value[patientCaseId] = el instanceof HTMLElement ? el : null;
};
const NODE_WIDTH = 300;
// 最大可平移距离用响应式表存储：DOM 尺寸非响应式，数据渲染完成后主动重算，
// 否则箭头显隐绑定在首帧求值为 0>=0 而被错误隐藏
const tlMaxMap = ref<Record<string, number>>({});
const recomputeTlMax = async (patientCaseId: string) => {
  await nextTick();
  const viewport = viewportRefs.value[patientCaseId];
  if (!viewport) return;
  const track = viewport.querySelector<HTMLElement>(".hap-tl-track");
  tlMaxMap.value[patientCaseId] = track ? Math.max(0, track.scrollWidth - viewport.clientWidth) : 0;
};
const tlMaxOffset = (patientCaseId: string) => tlMaxMap.value[patientCaseId] ?? 0;
const tlTurn = (item: PreAiPatientCase, direction: 1 | -1, event: MouseEvent) => {
  const patientCaseId = item.id;
  const viewport = viewportRefs.value[patientCaseId];
  if (!viewport) return;
  const track = viewport.querySelector<HTMLElement>(".hap-tl-track");
  if (!track) return;
  const page = Math.max(viewport.clientWidth - 60, 200);
  const max = Math.max(0, track.scrollWidth - viewport.clientWidth);
  const next = Math.min(Math.max((tlOffsets.value[patientCaseId] || 0) + direction * page, 0), max);
  tlOffsets.value[patientCaseId] = next;
  // codepen：翻到边界后隐藏对应箭头（由 is-hidden class 控制）
  void event;
};

// ---------- 弹层打开：并行加载详情 ----------
const activeDetailId = ref("");
const openDetail = (item: PreAiPatientCase) => {
  activeDetailId.value = item.id;
  void loadVisitHistory(item);
  void loadFollowUps(item);
  if (item.latestEncounter?.id) void loadWorkspace(item.latestEncounter.id);
};

// 医生端即时监督：队列/环节交接长轮询事件触发时，刷新列表与打开中的弹窗数据
const refreshOnQueueUpdate = () => {
  void loadCases();
  void loadRecallLamps();
  const id = activeDetailId.value;
  if (id) {
    delete historyMap.value[id];
    delete followUpMap.value[id];
    delete workspaceMap.value[id];
    const owner = cases.value.find(item => item.id === id);
    if (owner) {
      void loadVisitHistory(owner);
      void loadFollowUps(owner);
      if (owner.latestEncounter?.id) void loadWorkspace(owner.latestEncounter.id);
    }
  }
};

// ---------- 弹层内操作 ----------
const cardRefs = ref<{ close: () => void }[]>([]);
const setCardRef = (el: unknown, index: number) => {
  if (el && typeof el === "object" && "close" in el) cardRefs.value[index] = el as { close: () => void };
};
const openEncounter = (encounterId: string, patientName: string) => {
  if (!encounterId) {
    ElMessage.warning("该就诊不可用，无法进入档案维护");
    return;
  }
  activeEncounterId.value = encounterId;
  activePatientName.value = patientName;
  archiveVisible.value = true;
};
const maintainFrom = (item: PreAiPatientCase, index: number) => {
  if (!item.latestEncounter) {
    ElMessage.warning("该患者尚无就诊记录，无法进入档案维护");
    return;
  }
  cardRefs.value[index]?.close();
  openEncounter(item.latestEncounter.id, item.patientName || "");
};
const maintainVisit = (visit: PreAiEncounterHistoryItem) => {
  const owner = filteredCases.value.find(candidate => candidate.id === visit.patientCaseId);
  openEncounter(visit.id, owner?.patientName || "");
};

const routeLabel = (visit: PreAiEncounterHistoryItem) =>
  visit.route === "INPATIENT" || visit.normalizedCareType === "inpatient" || visit.inventoryCareType === "inpatient"
    ? "住院"
    : "门诊";
const encounterStatusType = (status?: string): "success" | "warning" | "danger" | "info" => {
  if (status === "EXPORTED" || status === "REVIEWED") return "success";
  if (status === "PENDING_REVIEW") return "warning";
  if (status === "CANCELLED") return "danger";
  return "info";
};
const stageDotClass = (visit: PreAiEncounterHistoryItem, code: PreAiStageCode) => {
  const status = visit.stageStatuses?.[code];
  if (status === "COMPLETED") return "is-done";
  if (status === "RETURNED") return "is-returned";
  if (status === "SKIPPED") return "is-skipped";
  return "";
};

const loadCases = async () => {
  loading.value = true;
  try {
    const { data } = await getPreAiPatientCasesApi();
    cases.value = data.list || [];
  } catch (error) {
    ElMessage.error((error as Error).message || "患者列表加载失败");
  } finally {
    loading.value = false;
  }
};

onMounted(async () => {
  await loadCases();
  void loadEmotionMap();
  // 消费深链：主档案工作台/地图分析推送的 /health-archive?encounterId=xxx 自动打开对应患者弹窗
  const requested = String(route.query.encounterId || "").trim();
  if (requested) {
    const owner = cases.value.find(item => item.latestEncounter?.id === requested);
    if (owner) openDetail(owner);
  }
  // 随访工作台深链：/health-archive?patientCaseId=xxx 直接定位患者卡片
  const requestedCase = String(route.query.patientCaseId || "").trim();
  if (requestedCase) {
    const owner = cases.value.find(item => item.id === requestedCase);
    if (owner) openDetail(owner);
  }
  void loadRecallLamps();
  window.addEventListener("clinic-queue-updated", refreshOnQueueUpdate);
});

onBeforeUnmount(() => {
  window.removeEventListener("clinic-queue-updated", refreshOnQueueUpdate);
});
</script>

<style scoped lang="scss">
.health-archive-page {
  display: grid;
  // 关键：约束网格列宽，防止横向轨道内容把整行撑到内容总宽（箭头被推到屏幕外）
  grid-template-columns: minmax(0, 1fr);
  gap: 8px;
  min-height: calc(100vh - 160px);
}
.hap-head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  .hap-title {
    display: grid;
    gap: 3px;
    strong {
      font-size: 17px;
      color: var(--el-text-color-primary);
    }
    small {
      color: var(--el-text-color-secondary);
    }
  }
  .hap-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }
}
.hap-carousel-wrap {
  min-width: 0;
  min-height: 480px;
}
.hap-cat-ico {
  font-size: 15px;
}
.hap-star {
  padding: 0 2px;
  font-size: 16px;
  color: var(--el-text-color-placeholder);
  &.is-on {
    color: var(--el-color-warning);
  }
}
.hap-lamp {
  padding: 1px 8px;
  font-size: 11.5px;
  font-weight: 700;
  border-radius: 999px;
  &.is-overdue {
    color: var(--el-color-danger-dark-2);
    background: var(--el-color-danger-light-9);
  }
  &.is-duesoon {
    color: var(--el-color-warning-dark-2);
    background: var(--el-color-warning-light-9);
  }
}
.hap-cat-visits {
  color: var(--el-text-color-placeholder);
}

// 卡面梗概：标头（弱化小字）与标的内容（加大加重/分色）明确分层；主诉完整换行不省略
:deep(.apple-card-summary) {
  p {
    display: grid;
    grid-template-columns: 22px auto minmax(0, 1fr);
    gap: 3px 7px;
    align-items: baseline;
    margin: 0 0 12px;
    .sum-ico {
      font-size: 14px;
    }
    .sum-label {
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.08em;
      color: var(--el-text-color-placeholder);
      white-space: nowrap;
    }
    span {
      overflow: hidden;
      font-size: 14.5px;
      font-weight: 650;
      text-overflow: ellipsis;
      white-space: nowrap;
      &.is-phone {
        color: var(--el-color-primary);
        font-variant-numeric: tabular-nums;
      }
      &.is-disease {
        color: var(--el-color-warning-dark-2);
      }
      &.is-complaint {
        font-size: 12.5px;
        font-weight: 500;
        line-height: 1.65;
        color: var(--el-text-color-regular);
      }
      &.is-plain {
        color: var(--el-text-color-primary);
      }
    }
    // 主诉独立成块：完整换行，不截断
    &.is-block {
      grid-template-columns: 22px minmax(0, 1fr);
      span.is-complaint {
        grid-column: 2;
        overflow: visible;
        text-overflow: clip;
        white-space: normal;
        word-break: break-word;
      }
    }
  }
}
.hap-disease-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  i {
    padding: 1px 8px;
    font-size: 11.5px;
    font-style: normal;
    font-weight: 600;
    color: var(--el-color-warning-dark-2);
    background: var(--el-color-warning-light-9);
    border: 1px solid var(--el-color-warning-light-7);
    border-radius: 999px;
  }
}
.hap-emotion-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  i {
    padding: 1px 8px;
    font-size: 11.5px;
    font-style: normal;
    font-weight: 600;
    color: #86198f;
    background: #fdf4ff;
    border: 1px solid #f5d0fe;
    border-radius: 999px;
  }
}

// 卡面底部：日期徽章（独立 UI 层级）
.hap-card-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.hap-date-badge {
  display: inline-flex;
  gap: 3px;
  align-items: center;
  padding: 3px 10px;
  font-size: 12.5px;
  font-weight: 700;
  color: var(--el-color-success-dark-2);
  font-variant-numeric: tabular-nums;
  background: var(--el-color-success-light-9);
  border: 1px solid var(--el-color-success-light-7);
  border-radius: 999px;
}
.hap-card-foot {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  :deep(.apple-card-enter) {
    display: inline-flex;
    gap: 3px;
    align-items: center;
    font-weight: 600;
    color: var(--el-color-primary);
  }
}

// ---------- 弹层内容通用块（Apple #F5F5F7 风格） ----------
.hap-detail-block {
  display: grid;
  gap: 16px;
  margin-bottom: 16px;
  padding: clamp(22px, 4vw, 44px);
  border-radius: 24px;
  background: #f5f5f7;
}
.hap-detail-caption {
  font-size: 15px;
  font-weight: 650;
  color: var(--el-text-color-primary);
  &.is-sub {
    font-size: 14px;
  }
  small {
    font-size: 12px;
    font-weight: 500;
    color: var(--el-text-color-placeholder);
  }
}
.hap-detail-lead {
  margin: 0 auto;
  max-width: 60rem;
  font-size: clamp(16px, 2.2vw, 24px);
  font-weight: 650;
  line-height: 1.7;
  color: #262626;
  text-align: center;
}
.hap-detail-facts {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  .hap-fact {
    padding: 12px 16px;
    background: #fff;
    border-radius: 14px;
    label {
      display: block;
      margin-bottom: 5px;
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.08em;
      color: #a3a3a3;
    }
    strong {
      overflow: hidden;
      font-size: 15px;
      font-weight: 650;
      color: #262626;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }
}
.hap-detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: center;
  small {
    color: #a3a3a3;
  }
}

// 病史信息 + 影像资料
.hap-history-zone {
  display: grid;
  gap: 16px;
  min-height: 60px;
}
.hap-history-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
  .hap-history-item {
    padding: 12px 16px;
    background: #fff;
    border-radius: 14px;
    label {
      display: block;
      margin-bottom: 5px;
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.08em;
      color: var(--el-text-color-secondary);
    }
    p {
      margin: 0;
      font-size: 13.5px;
      font-weight: 500;
      line-height: 1.65;
      color: var(--el-text-color-primary);
      white-space: pre-wrap;
    }
  }
}
.hap-attachments {
  display: grid;
  gap: 10px;
}

// ---------- 拆步横向时间轴（codepen dRoMwo 复刻） ----------
.is-timeline {
  overflow: hidden;
  background: transparent;
  padding-inline: 0;
  margin-bottom: 6px;
  // 关键：约束网格列，防止横向轨道内容把块撑到内容总宽（箭头/视口被推出面板）
  grid-template-columns: minmax(0, 1fr);
}
.hap-timeline-loading {
  min-height: 160px;
}
.hap-tl-viewport {
  overflow: hidden;
  padding: 2px 2px 4px;
}
.hap-tl-track {
  position: relative;
  display: flex;
  // codepen: transition all .5s linear，箭头翻页靠 left 平移
  transition: all 0.5s linear;
  // 贯穿虚线轴（对应 ul:before 1px dashed）
  &::before {
    position: absolute;
    top: 44px;
    right: 0;
    left: 0;
    height: 0;
    content: "";
    border-top: 1px dashed var(--el-color-primary-light-5);
  }
}
.hap-tl-node {
  position: relative;
  flex: 0 0 300px;
  padding: 0 22px;
}
.hap-tl-time-top {
  height: 34px;
  font-size: 15px;
  font-weight: 750;
  color: var(--el-color-primary);
  text-align: center;
  font-variant-numeric: tabular-nums;
}
// 节点骑轴线：双圆点（对应 span:before/:after）
.hap-tl-line {
  position: relative;
  height: 20px;
  &::before,
  &::after {
    position: absolute;
    top: 50%;
    width: 10px;
    height: 10px;
    content: "";
    background: var(--el-bg-color);
    border: 2px solid var(--el-color-primary);
    border-radius: 50%;
    transform: translateY(-50%);
  }
  &::before {
    left: -5px;
  }
  &::after {
    right: -5px;
  }
}
.hap-tl-card {
  min-height: 118px;
  margin-top: 12px;
  padding: 12px 16px;
  // 半透明染色卡（对应 rgba(255,255,255,.3)）
  background: color-mix(in srgb, var(--el-color-primary) 12%, var(--el-bg-color));
  border-radius: 10px;
  transition: box-shadow 0.2s ease;
  &:hover {
    box-shadow: 0 8px 22px rgb(0 150 136 / 14%);
  }
  .hap-tl-title {
    margin-bottom: 5px;
    font-size: 14px;
    font-weight: 750;
    letter-spacing: 0.04em;
    color: var(--el-text-color-primary);
  }
  .hap-tl-info {
    font-size: 12.5px;
    line-height: 1.65;
    color: var(--el-text-color-regular);
  }
  .hap-tl-name {
    margin-top: 8px;
    font-size: 12px;
    font-style: italic;
    color: var(--el-text-color-secondary);
    text-align: right;
  }
}
.hap-tl-visit-ops {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  justify-content: center;
  width: 100%;
  margin-top: 10px;
}
// codepen btn1/btn2：双箭头翻页 + hover 浮动动画
.hap-tl-arrows {
  display: flex;
  gap: 18px;
  justify-content: center;
  margin-top: 4px;
}
.hap-tl-arrow {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  color: var(--el-color-primary);
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: 50%;
  transition: opacity 0.2s ease;
  svg {
    width: 22px;
    height: 22px;
  }
  // 右箭头朝下→旋转为右向；左箭头镜像（对应 codepen rotateX 翻转）
  &:last-child svg {
    transform: rotate(-90deg);
  }
  &:first-child svg {
    transform: rotate(90deg);
  }
  &:hover:not(.is-hidden) {
    animation: hap-arrow-bob 1s linear infinite;
  }
  &.is-hidden {
    opacity: 0;
    pointer-events: none;
  }
}
.is-amber .hap-tl-arrow {
  color: var(--el-color-warning-dark-2);
}
@keyframes hap-arrow-bob {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(3px);
  }
}

// ---------- 复诊时间轴（琥珀警示色系，与来访轴的主色青绿区分） ----------
.is-recheck {
  overflow: hidden;
  background: transparent;
  padding-inline: 0;
  margin-top: -6px;
  grid-template-columns: minmax(0, 1fr);
  .hap-detail-caption small {
    font-size: 12px;
    font-weight: 500;
    color: var(--el-text-color-placeholder);
  }
}
.hap-fu-viewport {
  overflow: hidden;
  padding: 2px 2px 4px;
}
.hap-fu-track {
  position: relative;
  display: flex;
  transition: all 0.5s linear;
}
.hap-fu-node {
  display: flex;
  flex: 0 0 368px;
  flex-direction: column;
  min-width: 0;
  padding: 0 24px;
}
.hap-fu-time {
  height: 34px;
  font-size: 15px;
  font-weight: 750;
  color: var(--el-color-warning-dark-2);
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.hap-fu-axis {
  position: relative;
  display: flex;
  align-items: center;
  height: 22px;
  // 贯穿实线轴（区别于来访轴的虚线）
  &::before {
    position: absolute;
    top: 50%;
    right: 0;
    left: 0;
    height: 0;
    content: "";
    border-top: 2px solid var(--el-color-warning-light-5);
    transform: translateY(-50%);
  }
  .hap-fu-dot {
    position: relative;
    z-index: 1;
    width: 14px;
    height: 14px;
    margin: 0 auto;
    background: var(--el-color-warning);
    border: 3px solid var(--el-color-warning-light-7);
    border-radius: 50%;
    box-shadow: 0 0 0 3px rgb(255 179 71 / 18%);
  }
  &:first-child::before {
    left: 50%;
  }
  &:last-child::before {
    right: 50%;
  }
}
.hap-fu-card {
  display: grid;
  gap: 8px;
  justify-items: start;
  margin: 16px 8px 0;
  padding: 14px 16px;
  background: color-mix(in srgb, var(--el-color-warning) 10%, var(--el-bg-color));
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 14px;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
  &:hover {
    border-color: var(--el-color-warning-light-3);
    box-shadow: 0 10px 26px rgb(255 179 71 / 18%);
  }
  .hap-fu-title {
    display: flex;
    gap: 6px;
    align-items: center;
    font-size: 14px;
    font-weight: 750;
    color: var(--el-text-color-primary);
    .hap-fu-badge {
      padding: 1px 10px;
      font-size: 12px;
      font-weight: 700;
      color: var(--el-color-warning-dark-2);
      background: var(--el-color-warning-light-8);
      border-radius: 999px;
    }
  }
  // 图片重点展示：大尺寸缩略 + 计数徽标，点击全屏预览
  .hap-fu-images {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    .hap-fu-image {
      width: 104px;
      height: 104px;
      overflow: hidden;
      cursor: zoom-in;
      border: 1px solid var(--el-color-warning-light-5);
      border-radius: 10px;
      transition: transform 0.15s ease;
      &:hover {
        transform: scale(1.04);
      }
    }
    .hap-fu-image-count {
      padding: 2px 9px;
      font-size: 11.5px;
      font-weight: 650;
      color: var(--el-color-warning-dark-2);
      background: var(--el-color-warning-light-8);
      border-radius: 999px;
    }
  }
  .hap-fu-image-empty {
    padding: 14px 16px;
    font-size: 12.5px;
    color: var(--el-text-color-placeholder);
    border: 1px dashed var(--el-color-warning-light-5);
    border-radius: 10px;
  }
  .hap-fu-info {
    font-size: 12.5px;
    line-height: 1.65;
    color: var(--el-text-color-regular);
    word-break: break-word;
  }
  .hap-fu-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    .hap-fu-by {
      font-size: 11.5px;
      font-style: italic;
      color: var(--el-text-color-secondary);
    }
  }
}

// ---------- 弹窗行动条 ----------
.hap-action-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 16px;
  padding: 12px 16px;
  background: color-mix(in srgb, var(--el-color-primary) 5%, var(--el-bg-color));
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 22%, var(--el-border-color-lighter));
  border-radius: 14px;
  .el-button {
    margin: 0;
  }
  .hap-action-note {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
}

// ---------- 暗色主题 ----------
html.dark .hap-detail-block {
  background: #262626;
  .hap-detail-lead {
    color: #fafafa;
  }
  .hap-fact,
  .hap-history-grid .hap-history-item {
    background: #171717;
    strong,
    p {
      color: #e5e5e5;
    }
  }
}
html.dark .hap-tl-card {
  background: color-mix(in srgb, var(--el-color-primary) 18%, #1f1f1f);
}
html.dark .hap-fu-card {
  background: color-mix(in srgb, var(--el-color-warning) 16%, #1f1f1f);
}
</style>
