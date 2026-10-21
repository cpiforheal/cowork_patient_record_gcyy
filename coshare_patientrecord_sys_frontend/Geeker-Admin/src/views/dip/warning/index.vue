<template>
  <div class="dip">
    <header class="head">
      <div class="head-main">
        <h2>DIP 费用预警</h2>
        <p>当前费用 / 85% 限制 / 95% 限制 · 达 95% 应停计费并安排出院</p>
      </div>
      <div class="head-side">
        <span class="scope">可见 {{ allRows.length }} 例 · 需处理 {{ pendingCount }} 例</span>
        <el-button v-if="canCreatePatient" size="small" @click="openPatientDialog">新增患者</el-button>
        <el-button size="small" @click="ocrVisible = true">导入 HIS 费用截图</el-button>
        <el-button v-if="isAdmin" size="small" plain @click="openCatalog">病组目录</el-button>
      </div>
    </header>

    <ProTable
      v-if="loaded"
      ref="proTable"
      :columns="columns"
      :request-api="tableRequest"
      row-key="patientId"
      :search-col="{ xs: 1, sm: 2, md: 2, lg: 3, xl: 4 }"
    >
      <!-- 患者 -->
      <template #patient="{ row }">
        <div class="cell-patient">
          <strong>{{ row.name }}</strong>
          <span class="sub">{{ patientMeta(row) }}</span>
          <span class="diag">{{ row.diagnosis || "—" }}</span>
        </div>
      </template>

      <!-- 风险 -->
      <template #risk="{ row }">
        <span class="risk" :class="`is-${row.level}`">{{ levelText(row.level) }}</span>
      </template>

      <!-- 主视觉：三个数字 -->
      <template #waterline="{ row }">
        <div v-if="row.level === 'pending' || row.level === 'unmatched'" class="num-cell is-empty">
          <template v-if="row.level === 'pending'">
            <span class="empty-title">{{ row.feeVoided ? "费用已作废" : "待录入费用" }}</span>
            <span v-if="row.matched" class="empty-sub">病组标准 {{ money(row.groupStandardFee) }}</span>
            <span v-if="row.feeVoided" class="empty-sub">
              {{ blank(row.voidedBy) }} · {{ blank(row.voidedAt) }}<template v-if="row.voidReason"> · {{ row.voidReason }}</template>
            </span>
          </template>
          <template v-else>
            <span class="empty-title">未匹配病组</span>
            <button type="button" class="link" @click="openGroupDialog(row)">指定病组</button>
          </template>
        </div>
        <div v-else class="num-cell" :class="`is-${row.level}`">
          <div class="num-main">
            <span class="num-label">当前费用</span>
            <b class="num-now">{{ money(row.currentFee) }}</b>
          </div>
          <div class="num-limits">
            <div class="limit">
              <span>85% 限制</span>
              <b>{{ money(row.threshold85) }}</b>
            </div>
            <div class="limit">
              <span>95% 限制</span>
              <b>{{ money(row.threshold95) }}</b>
            </div>
          </div>
          <div class="track">
            <i class="fill" :style="{ width: `${Math.min(row.ratio, 100)}%` }" />
            <i class="tick" style="left: 85%" />
            <i class="tick" style="left: 95%" />
          </div>
          <div class="num-hint" :class="`t-${row.level}`">{{ suggestion(row) }}</div>
        </div>
      </template>

      <!-- 病组 -->
      <template #group="{ row }">
        <div v-if="row.matched" class="cell-group">
          <span class="group-name">{{ row.dipName }}</span>
          <span class="sub">
            {{ row.dipCode }}
            <i v-if="row.groupSource === 'MANUAL'" class="tag-manual">已改选</i>
            <i v-if="row.feeSourceType === 'PATIENT'" class="tag-manual is-fee">患者费用</i>
          </span>
        </div>
        <div v-else class="cell-group">
          <span class="sub is-muted">病组表未覆盖该诊断</span>
          <button type="button" class="link" @click="openGroupDialog(row)">指定病组</button>
        </div>
      </template>

      <!-- 处理 -->
      <template #operation="{ row }">
        <div class="cell-action">
          <el-button
            v-if="row.level === 'pending' || row.level === 'unmatched'"
            link
            type="primary"
            size="small"
            @click="openFeeDialog(row)"
          >
            录费用
          </el-button>
          <el-button v-else link type="primary" size="small" @click="openDetail(row)">编辑</el-button>
        </div>
      </template>
    </ProTable>

    <!-- HIS 截图导入：只识别最小字段，确认后才写入费用 -->
    <el-dialog v-model="ocrVisible" title="导入 HIS 费用截图" width="720px">
      <div class="ocr">
        <el-upload drag :auto-upload="false" :show-file-list="false" accept="image/png,image/jpeg" :on-change="handleOcrFile">
          <div class="ocr-upload">
            <p>拖入或点击选择 HIS 住院费用列表截图</p>
            <small>仅提取住院号、姓名、未结算费用、押金余额；截图不保存到病历附件。</small>
          </div>
        </el-upload>

        <div v-if="ocrLoading" class="ocr-status">正在识别，请稍候…</div>

        <template v-if="ocrItems.length">
          <div class="ocr-summary">
            识别 {{ ocrRecognized }} 条 · 已匹配 {{ ocrMatched }} 条 · 需确认 {{ ocrPending }} 条
          </div>
          <el-table :data="ocrItems" size="small" max-height="300" @selection-change="onOcrSelection">
            <el-table-column type="selection" width="42" :selectable="row => row.matchStatus === 'MATCHED'" />
            <el-table-column label="识别" width="150">
              <template #default="{ row }">
                <div class="ocr-cell">
                  <span>{{ row.ocrName || "—" }}</span>
                  <span class="sub">{{ row.ocrAdmissionNo || "无住院号" }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="匹配患者" width="150">
              <template #default="{ row }">
                <div class="ocr-cell">
                  <span>{{ row.patientName || "—" }}</span>
                  <span class="sub">{{ row.matchReason }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="未结算费用" width="120" align="right">
              <template #default="{ row }">{{ money(row.unsettledFee) }}</template>
            </el-table-column>
            <el-table-column label="原费用" width="110" align="right">
              <template #default="{ row }">{{ money(row.previousFee) }}</template>
            </el-table-column>
            <el-table-column label="识别后水位" width="110" align="right">
              <template #default="{ row }">
                <span v-if="row.waterlineRatio !== null">{{ row.waterlineRatio.toFixed(1) }}%</span>
                <span v-else class="sub">—</span>
              </template>
            </el-table-column>
            <el-table-column label="置信度" width="90" align="right">
              <template #default="{ row }">{{ Math.round(row.confidence * 100) }}%</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
      <template #footer>
        <el-button @click="ocrVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!ocrChecked.length" :loading="ocrWriting" @click="confirmOcr">
          写入选中 {{ ocrChecked.length }} 条
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情：编辑与查的入口 -->
    <el-drawer v-model="detailVisible" :title="detailTarget ? `${detailTarget.name} · 费用水位` : '费用水位'" size="440px">
      <div v-if="detailTarget" class="detail">
        <section class="detail-hero" :class="`is-${detailTarget.level}`">
          <div class="hero-line">
            <span class="hero-label">当前费用</span>
            <b class="hero-now">{{ detailTarget.level === "pending" ? "待录入" : money(detailTarget.currentFee) }}</b>
          </div>
          <div class="hero-limits">
            <div class="hero-limit">
              <span>85% 限制</span>
              <b>{{ detailTarget.matched ? money(detailTarget.threshold85) : "—" }}</b>
            </div>
            <div class="hero-limit">
              <span>95% 限制</span>
              <b>{{ detailTarget.matched ? money(detailTarget.threshold95) : "—" }}</b>
            </div>
          </div>
          <div v-if="hasWater(detailTarget)" class="track is-tall">
            <i class="fill" :style="{ width: `${Math.min(detailTarget.ratio, 100)}%` }" />
            <i class="tick" style="left: 85%" />
            <i class="tick is-super" style="left: 95%" />
          </div>
          <p class="hero-tip">
            {{ detailTip(detailTarget) }}
            <span v-if="hasWater(detailTarget)" class="sub">水位 {{ detailTarget.ratio.toFixed(1) }}%</span>
          </p>
        </section>

        <div class="detail-actions">
          <el-button size="small" @click="openFeeDialog(detailTarget)">修改费用</el-button>
          <el-button size="small" @click="openGroupDialog(detailTarget)">
            {{ detailTarget.matched ? "改选病组" : "指定病组" }}
          </el-button>
          <el-button size="small" :disabled="!detailTarget.matched" @click="openGroupFeeDialog(detailTarget)">
            调整病组费用
          </el-button>
          <el-button
            v-if="!detailTarget.feeVoided && detailTarget.level !== 'pending'"
            size="small"
            type="danger"
            plain
            @click="voidFee(detailTarget)"
          >
            作废费用
          </el-button>
        </div>

        <el-descriptions :column="1" border size="small" class="detail-table">
          <el-descriptions-item label="诊断">{{ detailTarget.diagnosis || "—" }}</el-descriptions-item>
          <el-descriptions-item label="DIP 病组">
            {{ detailTarget.dipName || "—" }}
            <span class="sub"> {{ detailTarget.dipCode }}</span>
            <span v-if="detailTarget.feeSourceType === 'PATIENT'" class="sub"> · 患者级费用</span>
          </el-descriptions-item>
          <el-descriptions-item label="病组费用">
            有效 {{ detailTarget.matched ? money(detailTarget.estimatedFee) : "—" }}
            <span class="sub"> · 目录标准 {{ detailTarget.matched ? money(detailTarget.groupStandardFee) : "—" }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="剩余到 95%">{{ money(detailTarget.remainTo95) }}</el-descriptions-item>
          <el-descriptions-item label="住院号">{{ blank(detailTarget.admissionNo) }}</el-descriptions-item>
          <el-descriptions-item label="病人类型">{{ blank(detailTarget.patientType) }}</el-descriptions-item>
          <el-descriptions-item label="床号 / 护理">{{ pair(detailTarget.bedNo, detailTarget.nursingLevel) }}</el-descriptions-item>
          <el-descriptions-item label="押金（总额 / 余额）">
            {{ pair(detailTarget.depositTotal, detailTarget.depositBalance) }}
          </el-descriptions-item>
          <el-descriptions-item label="入院日期 / 天数">
            {{ pair(detailTarget.admissionDate, detailTarget.hospitalDays) }}
          </el-descriptions-item>
          <el-descriptions-item label="管床医生">{{ blank(detailTarget.attendingDoctor) }}</el-descriptions-item>
          <el-descriptions-item label="费用更新">
            {{ detailTarget.updatedAt || "—"
            }}<span v-if="detailTarget.feeUpdatedBy" class="sub"> · {{ detailTarget.feeUpdatedBy }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detailTarget.feeVoided" label="作废记录">
            {{ blank(detailTarget.voidedBy) }} · {{ blank(detailTarget.voidedAt) }}
            <span v-if="detailTarget.voidReason" class="sub"> · {{ detailTarget.voidReason }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-collapse v-model="historyOpen" class="history" @change="handleHistoryChange">
          <el-collapse-item title="变更历史" name="history">
            <div v-if="historyLoading" class="sub">加载中…</div>
            <div v-else-if="historyRows.length" class="history-list">
              <div v-for="item in historyRows" :key="item.id" class="history-item">
                <div class="history-head">
                  <span class="history-scope">{{ scopeText(item.scope) }}</span>
                  <span class="sub">{{ item.createdAt }} · {{ blank(item.operator) }}</span>
                </div>
                <div class="history-values">
                  <span class="sub">前</span>
                  <b>{{ blank(item.beforeValue) }}</b>
                  <span class="sub">后</span>
                  <b>{{ blank(item.afterValue) }}</b>
                </div>
                <div v-if="item.note" class="sub">{{ item.note }}</div>
              </div>
            </div>
            <div v-else class="sub">暂无变更记录</div>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-drawer>

    <!-- 录入 / 修改费用 -->
    <el-dialog v-model="feeDialogVisible" :title="feeDialogTitle" width="430px">
      <el-form v-if="feeTarget" label-width="92px">
        <el-form-item label="患者">
          <span>{{ feeTarget.name }}<span v-if="feeTarget.admissionNo" class="sub"> · {{ feeTarget.admissionNo }}</span></span>
        </el-form-item>
        <el-form-item label="未结算费用">
          <el-input-number v-model="feeInput" :min="0" :precision="2" :step="100" controls-position="right" />
        </el-form-item>
        <el-form-item v-if="feeTarget.matched" label="录入后">
          <b :class="`t-${previewLevel}`">{{ money(feeInput) }}</b>
          <span class="sub">
            85% = {{ money(feeTarget.threshold85) }}，95% = {{ money(feeTarget.threshold95) }}
          </span>
        </el-form-item>
        <el-collapse v-model="morePanel" class="more">
          <el-collapse-item title="HIS 附加信息（可选）" name="more">
            <el-form-item label="住院号">
              <el-input v-model="feeForm.admissionNo" placeholder="HIS 住院号" />
            </el-form-item>
            <el-form-item label="押金余额">
              <el-input-number v-model="feeForm.depositBalance" :precision="2" :step="100" controls-position="right" />
            </el-form-item>
            <el-form-item label="病人类型">
              <el-input v-model="feeForm.patientType" placeholder="如 国家医保（居民）" />
            </el-form-item>
            <el-form-item label="床号">
              <el-input v-model="feeForm.bedNo" placeholder="HIS 床号" />
            </el-form-item>
            <el-form-item label="管床医生">
              <el-input v-model="feeForm.attendingDoctor" placeholder="HIS 管床医生" />
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button @click="feeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingFee" @click="submitFee">保存</el-button>
      </template>
    </el-dialog>

    <!-- 改选病组 -->
    <el-dialog v-model="groupDialogVisible" title="改选病组" width="500px">
      <div v-if="groupTarget" class="group-dialog">
        <p class="sub">{{ groupTarget.name }} · {{ groupTarget.diagnosis }}</p>
        <el-select v-model="groupInput" filterable placeholder="选择病组" class="full">
          <el-option-group v-for="group in groupOptionsGrouped" :key="group.categoryCode" :label="group.categoryName">
            <el-option
              v-for="option in group.options"
              :key="option.groupCode"
              :label="`${option.groupName} · ${option.groupCode}`"
              :value="option.groupCode"
            />
          </el-option-group>
        </el-select>
        <p v-if="selectedGroup" class="sub">
          预估 <b class="t-normal">{{ money(selectedGroup.estimatedFee) }}</b>
          <template v-if="groupTarget.currentFee > 0">
            → 改选后水位 <b :class="`t-${previewGroupLevel}`">{{ previewGroupRatio.toFixed(1) }}%</b>
          </template>
        </p>
        <el-button v-if="groupTarget.groupSource === 'MANUAL'" link type="primary" size="small" @click="groupInput = ''">
          恢复主诊断自动匹配
        </el-button>
      </div>
      <template #footer>
        <el-button @click="groupDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingGroup" @click="submitGroup">保存</el-button>
      </template>
    </el-dialog>

    <!-- 调整病组费用 -->
    <el-dialog v-model="groupFeeVisible" title="调整病组费用" width="430px">
      <el-form v-if="groupFeeTarget" label-width="104px">
        <el-form-item label="患者">
          <span>{{ groupFeeTarget.name }}<span class="sub"> · {{ groupFeeTarget.dipName }}</span></span>
        </el-form-item>
        <el-form-item label="目录标准费用">
          <b>{{ money(groupFeeTarget.groupStandardFee) }}</b>
        </el-form-item>
        <el-form-item label="患者病组费用">
          <el-input-number v-model="groupFeeInput" :min="0.01" :precision="2" :step="100" controls-position="right" />
        </el-form-item>
        <p class="sub">调整后 85% / 95% 限制按该费用重算，仅对当前患者生效。</p>
      </el-form>
      <template #footer>
        <el-button @click="groupFeeVisible = false">取消</el-button>
        <el-button :loading="savingGroupFee" @click="restoreGroupFee">恢复目录标准</el-button>
        <el-button type="primary" :loading="savingGroupFee" @click="submitGroupFee">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新增患者 -->
    <el-dialog v-model="patientDialogVisible" title="新增患者" width="560px">
      <el-form label-width="92px">
        <el-form-item label="姓名" required>
          <el-input v-model="patientForm.patientName" placeholder="患者姓名" />
        </el-form-item>
        <el-form-item label="西医主诊断" required>
          <el-input v-model="patientForm.diagnosis" placeholder="决定 DIP 病组匹配，如 混合痔" />
        </el-form-item>
        <el-form-item label="性别">
          <el-select v-model="patientForm.gender" clearable placeholder="选择性别" style="width: 100%">
            <el-option label="男" value="男" />
            <el-option label="女" value="女" />
          </el-select>
        </el-form-item>
        <el-form-item label="年龄">
          <el-input v-model="patientForm.age" placeholder="如 56" />
        </el-form-item>
        <el-form-item label="住院号">
          <el-input v-model="patientForm.admissionNo" placeholder="HIS 住院号（可选）" />
        </el-form-item>
        <el-form-item label="入院日期">
          <el-input v-model="patientForm.admissionDate" placeholder="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="管床医生">
          <el-input v-model="patientForm.attendingDoctor" placeholder="管床医生" />
        </el-form-item>
        <el-form-item label="DIP 病组">
          <el-select v-model="patientForm.groupCode" filterable clearable placeholder="按主诊断自动匹配，可手动指定" style="width: 100%">
            <el-option-group v-for="group in groupOptionsGrouped" :key="group.categoryCode" :label="group.categoryName">
              <el-option
                v-for="option in group.options"
                :key="option.groupCode"
                :label="`${option.groupName} · ${option.groupCode}`"
                :value="option.groupCode"
              />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="初始费用">
          <el-input-number v-model="patientForm.unsettledFee" :min="0" :precision="2" :step="100" controls-position="right" />
        </el-form-item>
        <el-form-item label="押金余额">
          <el-input-number v-model="patientForm.depositBalance" :precision="2" :step="100" controls-position="right" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="patientDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingPatient" @click="submitPatient">保存</el-button>
      </template>
    </el-dialog>

    <!-- 病组目录（管理员） -->
    <el-drawer v-model="catalogVisible" title="DIP 病组目录" size="760px">
      <div class="catalog">
        <div class="catalog-head">
          <el-input v-model="catalogKeyword" placeholder="搜索病组名称 / 编码 / 大类" clearable style="width: 260px" />
          <el-button size="small" type="primary" @click="openCatalogForm()">新增病组</el-button>
        </div>
        <el-table :data="catalogFiltered" size="small" v-loading="catalogLoading" max-height="560">
          <el-table-column label="大类" min-width="130">
            <template #default="{ row }">
              <div class="catalog-cell">
                <span>{{ row.categoryName }}</span>
                <span class="sub">{{ row.categoryCode }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="病组" min-width="190">
            <template #default="{ row }">
              <div class="catalog-cell">
                <span>{{ row.groupName }}</span>
                <span class="sub">{{ row.groupCode }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="标准费用" width="110" align="right">
            <template #default="{ row }">{{ money(row.estimatedFee) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <span class="risk" :class="row.status === 'ACTIVE' ? 'is-normal' : 'is-pending'">
                {{ row.status === "ACTIVE" ? "启用" : "停用" }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <div class="cell-action">
                <el-button link type="primary" size="small" @click="openCatalogForm(row)">编辑</el-button>
                <el-button link type="primary" size="small" @click="openCatalogHistory(row)">历史</el-button>
                <el-button link :type="row.status === 'ACTIVE' ? 'danger' : 'success'" size="small" @click="toggleCatalog(row)">
                  {{ row.status === "ACTIVE" ? "停用" : "启用" }}
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <!-- 病组新增 / 编辑 -->
    <el-dialog v-model="catalogFormVisible" :title="catalogMode === 'create' ? '新增病组' : '编辑病组'" width="560px">
      <el-form label-width="96px">
        <el-form-item label="大类编码" required>
          <el-input v-model="catalogForm.categoryCode" placeholder="如 K64" />
        </el-form-item>
        <el-form-item label="大类名称" required>
          <el-input v-model="catalogForm.categoryName" placeholder="如 痔疮与肛周静脉血栓形成" />
        </el-form-item>
        <el-form-item label="病组编码" required>
          <el-input v-model="catalogForm.groupCode" :disabled="catalogMode === 'edit'" placeholder="如 K64:49.4500x004" />
        </el-form-item>
        <el-form-item label="病组名称" required>
          <el-input v-model="catalogForm.groupName" placeholder="如 内镜下内痔套扎治疗" />
        </el-form-item>
        <el-form-item label="标准费用" required>
          <el-input-number v-model="catalogForm.estimatedFee" :min="0.01" :precision="2" :step="100" controls-position="right" />
        </el-form-item>
        <el-form-item label="匹配关键词">
          <el-input v-model="catalogForm.matchKeywords" placeholder="主诊断关键词，逗号分隔" />
        </el-form-item>
        <el-form-item label="关键词优先级">
          <el-input-number v-model="catalogForm.keywordPriority" :min="0" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number v-model="catalogForm.sortNo" :min="0" :step="1" controls-position="right" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="catalogFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingCatalog" @click="submitCatalog">保存</el-button>
      </template>
    </el-dialog>

    <!-- 病组目录历史 -->
    <el-dialog v-model="catalogHistoryVisible" :title="catalogHistoryTitle" width="640px">
      <div v-if="catalogHistoryRows.length" class="history-list">
        <div v-for="item in catalogHistoryRows" :key="item.id" class="history-item">
          <div class="history-head">
            <span class="history-scope">{{ scopeText(item.scope) }}</span>
            <span class="sub">{{ item.createdAt }} · {{ blank(item.operator) }}</span>
          </div>
          <div class="history-values">
            <span class="sub">前</span>
            <b>{{ blank(item.beforeValue) }}</b>
            <span class="sub">后</span>
            <b>{{ blank(item.afterValue) }}</b>
          </div>
          <div v-if="item.note" class="sub">{{ item.note }}</div>
        </div>
      </div>
      <div v-else class="sub">暂无变更记录</div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="dipWarning">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import ProTable from "@/components/ProTable/index.vue";
import type { ColumnProps, ProTableInstance } from "@/components/ProTable/interface";
import { useUserStore } from "@/stores/modules/user";
import {
  createDipCatalogGroupApi,
  createDipPatientApi,
  getDipBoardApi,
  getDipCatalogApi,
  getDipCatalogHistoryApi,
  getDipGroupsApi,
  getDipHistoryApi,
  parseDipScreenshotApi,
  saveDipFeeApi,
  saveDipGroupApi,
  saveDipGroupFeeApi,
  toggleDipCatalogGroupApi,
  updateDipCatalogGroupApi,
  voidDipFeeApi,
  type DipCatalogGroup,
  type DipChangeLog,
  type DipGroupOption,
  type DipOcrItem,
  type DipPatientRow
} from "@/api/modules/clinic/dipWarning";

type Level = "normal" | "warning" | "danger" | "pending" | "unmatched";

interface Row {
  patientId: string;
  name: string;
  gender: string;
  age: number | null;
  diagnosis: string;
  matched: boolean;
  dipCode: string;
  dipName: string;
  categoryName: string;
  groupSource: "AUTO" | "MANUAL";
  estimatedFee: number;
  groupStandardFee: number;
  feeOverride: number | null;
  feeSourceType: "GROUP" | "PATIENT";
  currentFee: number;
  threshold85: number;
  threshold95: number;
  ratio: number;
  remainTo95: number;
  level: Level;
  updatedAt: string;
  feeUpdatedBy: string;
  admissionNo: string;
  patientType: string;
  bedNo: string;
  nursingLevel: string;
  depositTotal: number | null;
  depositBalance: number | null;
  admissionDate: string;
  hospitalDays: number | null;
  attendingDoctor: string;
  feeVoided: boolean;
  voidedAt: string;
  voidedBy: string;
  voidReason: string;
}

const userStore = useUserStore();
const role = computed(() => userStore.userInfo.role || "");
const isAdmin = computed(() => role.value === "admin");
const canCreatePatient = computed(() => ["doctor", "admin"].includes(role.value));

const proTable = ref<ProTableInstance>();
const loaded = ref(false);
const allRows = ref<Row[]>([]);

const money = (value: number | null | undefined) =>
  value === null || value === undefined ? "—" : `¥${Number(value).toFixed(2)}`;
const blank = (value: string | null | undefined) => (value && String(value).trim() ? String(value) : "—");
const pair = (a: string | number | null | undefined, b: string | number | null | undefined) => {
  const left = a === null || a === undefined || a === "" ? "—" : a;
  const right = b === null || b === undefined || b === "" ? "—" : b;
  return `${left} / ${right}`;
};
const levelOf = (ratio: number): Level => (ratio >= 95 ? "danger" : ratio >= 85 ? "warning" : "normal");
const levelText = (level: Level) =>
  level === "danger" ? "超限" : level === "warning" ? "预警" : level === "pending" ? "待录入" : level === "unmatched" ? "未匹配" : "正常";
const hasWater = (row: Row) => row.level !== "pending" && row.level !== "unmatched";

const scopeText = (scope: DipChangeLog["scope"]) =>
  scope === "PATIENT_CREATE"
    ? "新增患者"
    : scope === "FEE"
      ? "费用变更"
      : scope === "FEE_VOID"
        ? "费用作废"
        : scope === "GROUP"
          ? "病组改选"
          : scope === "GROUP_FEE"
            ? "病组费用"
            : "目录维护";

const toRow = (item: DipPatientRow): Row => {
  const estimatedFee = Number(item.estimatedFee ?? 0);
  const hasFee =
    item.unsettledFee !== null && item.unsettledFee !== undefined && !item.feeVoided;
  const currentFee = hasFee ? Number(item.unsettledFee) : 0;
  const threshold85 = Number(item.threshold85 ?? 0);
  const threshold95 = Number(item.threshold95 ?? 0);
  const ratio = estimatedFee > 0 && hasFee ? (currentFee / estimatedFee) * 100 : 0;
  const level: Level = !item.matched ? "unmatched" : !hasFee ? "pending" : levelOf(ratio);
  return {
    patientId: item.patientId,
    name: item.patientName,
    gender: item.gender ?? "",
    age: item.age ?? null,
    diagnosis: item.diagnosis ?? "",
    matched: item.matched,
    dipCode: item.groupCode ?? "",
    dipName: item.groupName ?? "",
    categoryName: item.categoryName ?? "",
    groupSource: (item.groupSource as "AUTO" | "MANUAL") ?? "AUTO",
    estimatedFee,
    groupStandardFee: Number(item.groupStandardFee ?? item.estimatedFee ?? 0),
    feeOverride: item.feeOverride === null || item.feeOverride === undefined ? null : Number(item.feeOverride),
    feeSourceType: item.feeSourceType === "PATIENT" ? "PATIENT" : "GROUP",
    currentFee,
    threshold85,
    threshold95,
    ratio,
    remainTo95: Number((threshold95 - currentFee).toFixed(2)),
    level,
    updatedAt: item.feeUpdatedAt ?? "",
    feeUpdatedBy: item.feeUpdatedBy ?? "",
    admissionNo: item.admissionNo ?? "",
    patientType: item.patientType ?? "",
    bedNo: item.bedNo ?? "",
    nursingLevel: item.nursingLevel ?? "",
    depositTotal: item.depositTotal ?? null,
    depositBalance: item.depositBalance ?? null,
    admissionDate: item.admissionDate ?? "",
    hospitalDays: item.hospitalDays ?? null,
    attendingDoctor: item.attendingDoctor ?? "",
    feeVoided: Boolean(item.feeVoided),
    voidedAt: item.voidedAt ?? "",
    voidedBy: item.voidedBy ?? "",
    voidReason: item.voidReason ?? ""
  };
};

const levelOptions = [
  { label: "超限", value: "danger", tagType: "danger" },
  { label: "预警", value: "warning", tagType: "warning" },
  { label: "待录入", value: "pending", tagType: "info" },
  { label: "未匹配", value: "unmatched", tagType: "info" },
  { label: "正常", value: "normal", tagType: "success" }
];

const columns = reactive<ColumnProps<Row>[]>([]);

const buildColumns = () => {
  const categories = [...new Set(allRows.value.map(row => row.categoryName).filter(Boolean))].sort();
  const next: ColumnProps<Row>[] = [
    {
      prop: "patient",
      label: "患者",
      minWidth: 190,
      search: { el: "input", key: "keyword", order: 1, props: { placeholder: "姓名 / 住院号 / 诊断 / 病组" } }
    },
    {
      prop: "risk",
      label: "风险",
      width: 92,
      enum: levelOptions,
      isFilterEnum: false,
      search: { el: "select", key: "risk", order: 2, props: { placeholder: "风险状态" } }
    },
    { prop: "waterline", label: "费用水位", minWidth: 320 },
    {
      prop: "group",
      label: "DIP 病组",
      minWidth: 210,
      enum: categories.map(name => ({ label: name, value: name })),
      ...(categories.length
        ? { search: { el: "select" as const, key: "categoryName", order: 3, props: { placeholder: "病组大类" } } }
        : {})
    },
    {
      prop: "his",
      label: "管床医生",
      hide: true,
      search: { el: "input", key: "attendingDoctor", order: 4, props: { placeholder: "管床医生" } }
    },
    { prop: "operation", label: "处理", width: 130, fixed: "right" }
  ];
  columns.splice(0, columns.length, ...next);
};

const matches = (row: Row, params: Record<string, any>) => {
  const keyword = String(params.keyword ?? "").trim().toLowerCase();
  if (keyword) {
    const hay = [row.name, row.admissionNo, row.diagnosis, row.dipCode, row.dipName].join(" ").toLowerCase();
    if (!hay.includes(keyword)) return false;
  }
  if (params.risk && row.level !== params.risk) return false;
  if (params.categoryName && row.categoryName !== params.categoryName) return false;
  const doctor = String(params.attendingDoctor ?? "").trim();
  if (doctor && !row.attendingDoctor.includes(doctor)) return false;
  return true;
};

const rankOf = (level: Level) =>
  level === "danger" ? 0 : level === "warning" ? 1 : level === "pending" ? 2 : level === "unmatched" ? 4 : 3;

const tableRequest = async (params: Record<string, any>) => {
  const list = allRows.value
    .filter(row => matches(row, params))
    .sort((a, b) => {
      const rank = rankOf(a.level) - rankOf(b.level);
      return rank !== 0 ? rank : b.ratio - a.ratio;
    });
  const pageNum = Number(params.pageNum ?? 1);
  const pageSize = Number(params.pageSize ?? 10);
  const start = (pageNum - 1) * pageSize;
  return { data: { list: list.slice(start, start + pageSize), total: list.length } };
};

const pendingCount = computed(() => allRows.value.filter(row => row.level !== "normal").length);

const reload = async () => {
  try {
    const board = await getDipBoardApi();
    allRows.value = (board.patients ?? []).map(toRow);
    buildColumns();
    if (!loaded.value) {
      loaded.value = true;
      return;
    }
    proTable.value?.getTableList();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "费用预警数据加载失败");
  }
};

const patientMeta = (row: Row) =>
  [row.gender, row.age === null ? "" : `${row.age}岁`, row.admissionNo ? `住院号 ${row.admissionNo}` : ""]
    .filter(Boolean)
    .join(" · ") || "—";

const suggestion = (row: Row) => {
  if (row.level === "danger") return `已超 95% 线 ${money(row.currentFee - row.threshold95)}`;
  return `距 95% 线尚余 ${money(row.threshold95 - row.currentFee)}`;
};

const detailTip = (row: Row) => {
  if (row.feeVoided) return "费用记录已作废，可重新录入后再参与水位计算。";
  if (row.level === "danger") return "已达 95% 超限线：如无特殊情况应停止计费并安排出院。";
  if (row.level === "warning") return "已达 85% 预警线：请关注后续费用，提前核对诊疗项目。";
  if (row.level === "pending") return "尚未录入 HIS 未结算费用，录入后才参与水位计算。";
  if (row.level === "unmatched") return "主诊断未匹配到病组，指定病组后才能估算费用。";
  return "费用水位正常。";
};

/* ---------- 详情 ---------- */
const detailVisible = ref(false);
const detailTarget = ref<Row | null>(null);
const historyOpen = ref<string[]>([]);
const historyRows = ref<DipChangeLog[]>([]);
const historyLoading = ref(false);
const historyLoaded = ref(false);

const openDetail = (row: Row) => {
  detailTarget.value = row;
  historyOpen.value = [];
  historyRows.value = [];
  historyLoaded.value = false;
  detailVisible.value = true;
};

const loadHistory = async (patientId: string) => {
  historyLoading.value = true;
  try {
    historyRows.value = await getDipHistoryApi(patientId);
    historyLoaded.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "变更历史加载失败");
  } finally {
    historyLoading.value = false;
  }
};

const handleHistoryChange = (names: string[] | string) => {
  const opened = Array.isArray(names) ? names.includes("history") : names === "history";
  if (!opened || historyLoaded.value || !detailTarget.value) return;
  loadHistory(detailTarget.value.patientId);
};

/* ---------- 费用 ---------- */
const feeDialogVisible = ref(false);
const savingFee = ref(false);
const feeTarget = ref<Row | null>(null);
const feeInput = ref(0);
const morePanel = ref<string[]>([]);
const feeForm = reactive({
  admissionNo: "",
  patientType: "",
  bedNo: "",
  attendingDoctor: "",
  depositBalance: null as number | null
});

const feeDialogTitle = computed(() => (feeTarget.value && feeTarget.value.currentFee > 0 ? "修改未结算费用" : "录入未结算费用"));

const openFeeDialog = (row: Row) => {
  feeTarget.value = row;
  feeInput.value = row.currentFee;
  feeForm.admissionNo = row.admissionNo;
  feeForm.patientType = row.patientType;
  feeForm.bedNo = row.bedNo;
  feeForm.attendingDoctor = row.attendingDoctor;
  feeForm.depositBalance = row.depositBalance;
  morePanel.value = [];
  feeDialogVisible.value = true;
};

const previewLevel = computed(() => levelOf(feeTarget.value?.estimatedFee ? (feeInput.value / feeTarget.value.estimatedFee) * 100 : 0));

const submitFee = async () => {
  const target = feeTarget.value;
  if (!target) return;
  savingFee.value = true;
  try {
    await saveDipFeeApi({
      patientId: target.patientId,
      unsettledFee: feeInput.value,
      source: "MANUAL",
      admissionNo: feeForm.admissionNo,
      patientType: feeForm.patientType,
      bedNo: feeForm.bedNo,
      attendingDoctor: feeForm.attendingDoctor,
      depositBalance: feeForm.depositBalance
    });
    feeDialogVisible.value = false;
    detailVisible.value = false;
    await reload();
    ElMessage.success(`${target.name} 费用已更新`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "费用保存失败");
  } finally {
    savingFee.value = false;
  }
};

const voidFee = async (row: Row) => {
  try {
    const { value } = await ElMessageBox.prompt("填写作废原因，作废后可重新录入费用。", "作废费用", {
      inputPlaceholder: "如：HIS 重复录入 / 金额录错",
      confirmButtonText: "作废",
      cancelButtonText: "取消",
      inputValidator: (input: string) => (input && input.trim() ? true : "请填写作废原因")
    });
    await voidDipFeeApi({ patientId: row.patientId, reason: value.trim() });
    detailVisible.value = false;
    await reload();
    ElMessage.success("费用记录已作废");
  } catch (error: any) {
    if (error === "cancel" || error === "close") return;
    if (error instanceof Error) ElMessage.error(error.message);
  }
};

/* ---------- 改选病组 ---------- */
const groupDialogVisible = ref(false);
const savingGroup = ref(false);
const groupTarget = ref<Row | null>(null);
const groupInput = ref("");
const groupOptions = ref<DipGroupOption[]>([]);

const groupOptionsGrouped = computed(() => {
  const map = new Map<string, { categoryCode: string; categoryName: string; options: DipGroupOption[] }>();
  groupOptions.value.forEach(option => {
    const entry = map.get(option.categoryCode) ?? {
      categoryCode: option.categoryCode,
      categoryName: option.categoryName,
      options: []
    };
    entry.options.push(option);
    map.set(option.categoryCode, entry);
  });
  return [...map.values()];
});

const selectedGroup = computed(
  () => groupOptions.value.find(option => option.groupCode === groupInput.value) ?? null
);

const previewGroupRatio = computed(() => {
  const target = groupTarget.value;
  const option = selectedGroup.value;
  if (!target || !option || !option.estimatedFee || target.currentFee <= 0) return 0;
  return (target.currentFee / option.estimatedFee) * 100;
});
const previewGroupLevel = computed(() => levelOf(previewGroupRatio.value));

const ensureGroupOptions = async (force = false) => {
  if (groupOptions.value.length && !force) return;
  try {
    groupOptions.value = await getDipGroupsApi();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "病组加载失败");
  }
};

const openGroupDialog = async (row: Row) => {
  groupTarget.value = row;
  groupInput.value = row.dipCode;
  groupDialogVisible.value = true;
  await ensureGroupOptions();
};

const submitGroup = async () => {
  const target = groupTarget.value;
  if (!target) return;
  try {
    if (!groupInput.value && target.groupSource === "MANUAL") {
      const message =
        target.feeOverride !== null
          ? "恢复主诊断自动匹配将同时清除患者级病组费用调整，是否继续？"
          : "恢复主诊断自动匹配？";
      await ElMessageBox.confirm(message, "恢复自动匹配", { confirmButtonText: "继续", cancelButtonText: "取消" });
    }
    savingGroup.value = true;
    await saveDipGroupApi({ patientId: target.patientId, groupCode: groupInput.value });
    groupDialogVisible.value = false;
    detailVisible.value = false;
    await reload();
    ElMessage.success(groupInput.value ? "病组已更新" : "已恢复自动匹配");
  } catch (error: any) {
    if (error === "cancel" || error === "close") return;
    if (error instanceof Error) ElMessage.error(error.message);
  } finally {
    savingGroup.value = false;
  }
};

/* ---------- 病组费用覆盖 ---------- */
const groupFeeVisible = ref(false);
const savingGroupFee = ref(false);
const groupFeeTarget = ref<Row | null>(null);
const groupFeeInput = ref(0);

const openGroupFeeDialog = (row: Row) => {
  groupFeeTarget.value = row;
  groupFeeInput.value = row.feeOverride ?? row.groupStandardFee;
  groupFeeVisible.value = true;
};

const submitGroupFee = async () => {
  const target = groupFeeTarget.value;
  if (!target) return;
  savingGroupFee.value = true;
  try {
    await saveDipGroupFeeApi({ patientId: target.patientId, estimatedFeeOverride: groupFeeInput.value });
    groupFeeVisible.value = false;
    detailVisible.value = false;
    await reload();
    ElMessage.success("病组费用已更新");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "病组费用保存失败");
  } finally {
    savingGroupFee.value = false;
  }
};

const restoreGroupFee = async () => {
  const target = groupFeeTarget.value;
  if (!target) return;
  savingGroupFee.value = true;
  try {
    await saveDipGroupFeeApi({ patientId: target.patientId, estimatedFeeOverride: null });
    groupFeeVisible.value = false;
    detailVisible.value = false;
    await reload();
    ElMessage.success("已恢复病组目录标准费用");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "恢复失败");
  } finally {
    savingGroupFee.value = false;
  }
};

/* ---------- 新增患者 ---------- */
const patientDialogVisible = ref(false);
const savingPatient = ref(false);
const patientForm = reactive({
  patientName: "",
  diagnosis: "",
  gender: "",
  age: "",
  admissionNo: "",
  admissionDate: "",
  attendingDoctor: "",
  groupCode: "",
  unsettledFee: 0 as number | null,
  depositBalance: null as number | null
});

const openPatientDialog = async () => {
  Object.assign(patientForm, {
    patientName: "",
    diagnosis: "",
    gender: "",
    age: "",
    admissionNo: "",
    admissionDate: "",
    attendingDoctor: "",
    groupCode: "",
    unsettledFee: 0,
    depositBalance: null
  });
  patientDialogVisible.value = true;
  await ensureGroupOptions();
};

const submitPatient = async () => {
  if (!patientForm.patientName.trim()) {
    ElMessage.warning("请填写患者姓名");
    return;
  }
  if (!patientForm.diagnosis.trim()) {
    ElMessage.warning("请填写西医主诊断");
    return;
  }
  savingPatient.value = true;
  try {
    await createDipPatientApi({
      patientName: patientForm.patientName.trim(),
      diagnosis: patientForm.diagnosis.trim(),
      gender: patientForm.gender,
      age: patientForm.age,
      admissionNo: patientForm.admissionNo,
      admissionDate: patientForm.admissionDate,
      attendingDoctor: patientForm.attendingDoctor,
      groupCode: patientForm.groupCode,
      unsettledFee: patientForm.unsettledFee,
      depositBalance: patientForm.depositBalance
    });
    patientDialogVisible.value = false;
    await reload();
    ElMessage.success("患者已新增");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "新增患者失败");
  } finally {
    savingPatient.value = false;
  }
};

/* ---------- 病组目录 ---------- */
const catalogVisible = ref(false);
const catalogLoading = ref(false);
const catalogRows = ref<DipCatalogGroup[]>([]);
const catalogKeyword = ref("");
const catalogFormVisible = ref(false);
const catalogMode = ref<"create" | "edit">("create");
const savingCatalog = ref(false);
const catalogHistoryVisible = ref(false);
const catalogHistoryRows = ref<DipChangeLog[]>([]);
const catalogHistoryTitle = ref("病组变更历史");
const catalogForm = reactive({
  categoryCode: "",
  categoryName: "",
  groupCode: "",
  groupName: "",
  estimatedFee: 0.01,
  matchKeywords: "",
  keywordPriority: 0,
  sortNo: 0
});

const catalogFiltered = computed(() => {
  const keyword = catalogKeyword.value.trim().toLowerCase();
  if (!keyword) return catalogRows.value;
  return catalogRows.value.filter(row =>
    [row.groupName, row.groupCode, row.categoryName, row.categoryCode].join(" ").toLowerCase().includes(keyword)
  );
});

const loadCatalog = async () => {
  catalogLoading.value = true;
  try {
    catalogRows.value = await getDipCatalogApi();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "病组目录加载失败");
  } finally {
    catalogLoading.value = false;
  }
};

const openCatalog = async () => {
  catalogVisible.value = true;
  catalogKeyword.value = "";
  await loadCatalog();
};

const openCatalogForm = (row?: DipCatalogGroup) => {
  catalogMode.value = row ? "edit" : "create";
  Object.assign(catalogForm, {
    categoryCode: row?.categoryCode ?? "",
    categoryName: row?.categoryName ?? "",
    groupCode: row?.groupCode ?? "",
    groupName: row?.groupName ?? "",
    estimatedFee: row?.estimatedFee ?? 0.01,
    matchKeywords: row?.matchKeywords ?? "",
    keywordPriority: row?.keywordPriority ?? 0,
    sortNo: row?.sortNo ?? 0
  });
  catalogFormVisible.value = true;
};

const submitCatalog = async () => {
  if (!catalogForm.categoryCode.trim() || !catalogForm.categoryName.trim()) {
    ElMessage.warning("请填写大类编码与名称");
    return;
  }
  if (!catalogForm.groupCode.trim() || !catalogForm.groupName.trim()) {
    ElMessage.warning("请填写病组编码与名称");
    return;
  }
  savingCatalog.value = true;
  try {
    const payload = {
      categoryCode: catalogForm.categoryCode.trim(),
      categoryName: catalogForm.categoryName.trim(),
      groupCode: catalogForm.groupCode.trim(),
      groupName: catalogForm.groupName.trim(),
      estimatedFee: catalogForm.estimatedFee,
      matchKeywords: catalogForm.matchKeywords,
      keywordPriority: catalogForm.keywordPriority,
      sortNo: catalogForm.sortNo
    };
    if (catalogMode.value === "create") {
      await createDipCatalogGroupApi(payload);
    } else {
      await updateDipCatalogGroupApi(payload.groupCode, payload);
    }
    catalogFormVisible.value = false;
    await Promise.all([loadCatalog(), ensureGroupOptions(true), reload()]);
    ElMessage.success("病组目录已更新");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "病组目录保存失败");
  } finally {
    savingCatalog.value = false;
  }
};

const toggleCatalog = async (row: DipCatalogGroup) => {
  const next = row.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
  try {
    if (next === "DISABLED") {
      await ElMessageBox.confirm(
        "停用后该病组不再参与自动匹配；已选该组的患者将回落到主诊断匹配。",
        "停用病组",
        { confirmButtonText: "停用", cancelButtonText: "取消", type: "warning" }
      );
    }
    await toggleDipCatalogGroupApi(row.groupCode, next);
    await Promise.all([loadCatalog(), ensureGroupOptions(true), reload()]);
    ElMessage.success(next === "ACTIVE" ? "病组已启用" : "病组已停用");
  } catch (error: any) {
    if (error === "cancel" || error === "close") return;
    if (error instanceof Error) ElMessage.error(error.message);
  }
};

const openCatalogHistory = async (row: DipCatalogGroup) => {
  catalogHistoryTitle.value = `${row.groupName} · 变更历史`;
  catalogHistoryRows.value = [];
  catalogHistoryVisible.value = true;
  try {
    catalogHistoryRows.value = await getDipCatalogHistoryApi(row.groupCode);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "病组历史加载失败");
  }
};

/* ---------- HIS 截图识别 ---------- */
const ocrVisible = ref(false);
const ocrLoading = ref(false);
const ocrWriting = ref(false);
const ocrItems = ref<DipOcrItem[]>([]);
const ocrChecked = ref<DipOcrItem[]>([]);
const ocrRecognized = ref(0);
const ocrMatched = ref(0);
const ocrPending = ref(0);

const onOcrSelection = (rows: DipOcrItem[]) => {
  ocrChecked.value = rows;
};

const handleOcrFile = async (file: { raw?: File }) => {
  if (!file.raw) return;
  if (!/^image\/(png|jpeg)$/.test(file.raw.type)) {
    ElMessage.error("仅支持 PNG / JPG 截图");
    return;
  }
  ocrLoading.value = true;
  ocrItems.value = [];
  ocrChecked.value = [];
  try {
    const result = await parseDipScreenshotApi(file.raw);
    ocrItems.value = result.items;
    ocrRecognized.value = result.recognized;
    ocrMatched.value = result.matched;
    ocrPending.value = result.pending;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "识别失败，请重试或手动录入");
  } finally {
    ocrLoading.value = false;
  }
};

const confirmOcr = async () => {
  if (!ocrChecked.value.length) return;
  ocrWriting.value = true;
  let written = 0;
  const failed: string[] = [];
  for (const item of ocrChecked.value) {
    if (!item.patientId || item.unsettledFee === null) continue;
    try {
      await saveDipFeeApi({
        patientId: item.patientId,
        unsettledFee: item.unsettledFee,
        source: "SCREENSHOT",
        admissionNo: item.ocrAdmissionNo || undefined,
        depositBalance: item.depositBalance
      });
      written += 1;
    } catch {
      failed.push(item.ocrName || item.patientName || item.patientId);
    }
  }
  ocrWriting.value = false;
  ocrVisible.value = false;
  await reload();
  if (failed.length) {
    ElMessage.warning(`已写入 ${written} 条，${failed.length} 条失败：${failed.join("、")}`);
  } else {
    ElMessage.success(`已写入 ${written} 条费用`);
  }
};

onMounted(reload);
</script>

<style scoped lang="scss">
.dip {
  --ok: var(--el-color-success);
  --warn: var(--el-color-warning);
  --danger: var(--el-color-danger);

  padding: 16px 20px 28px;
  display: flex;
  flex-direction: column;
  gap: 12px;

  .head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;

    h2 {
      margin: 0 0 3px;
      font-size: 18px;
      font-weight: 600;
    }

    p {
      margin: 0;
      font-size: 12.5px;
      color: var(--el-text-color-secondary);
    }
  }

  .head-side {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-shrink: 0;
  }

  .scope {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .t-warn {
    color: var(--warn);
  }

  .t-danger,
  .t-warning {
    color: var(--warn);
  }

  .t-danger {
    color: var(--danger);
  }

  .t-normal {
    color: var(--ok);
  }

  .sub {
    font-size: 11.5px;
    color: var(--el-text-color-secondary);

    &.is-muted {
      color: var(--el-text-color-placeholder);
    }
  }

  .cell-patient {
    display: flex;
    flex-direction: column;
    text-align: left;

    strong {
      font-size: 14.5px;
      font-weight: 600;
      line-height: 1.3;
    }

    .diag {
      font-size: 12.5px;
      color: var(--el-text-color-regular);
    }
  }

  .risk {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 52px;
    padding: 1px 8px;
    border-radius: 10px;
    font-size: 12px;
    line-height: 20px;
    background: var(--el-fill-color-light);
    color: var(--el-text-color-regular);

    &.is-danger {
      color: var(--danger);
      background: var(--el-color-danger-light-9);
    }

    &.is-warning {
      color: var(--warn);
      background: var(--el-color-warning-light-9);
    }

    &.is-normal {
      color: var(--ok);
      background: var(--el-color-success-light-9);
    }

    &.is-pending,
    &.is-unmatched {
      color: var(--el-text-color-secondary);
    }
  }

  /* 主视觉：三个数字 */
  .num-cell {
    display: flex;
    flex-direction: column;
    gap: 4px;
    text-align: left;

    &.is-empty {
      gap: 2px;
      padding: 2px 0;
    }
  }

  .num-main {
    display: flex;
    align-items: baseline;
    gap: 8px;
  }

  .num-label {
    font-size: 11.5px;
    color: var(--el-text-color-secondary);
  }

  .num-now {
    font-size: 20px;
    font-weight: 600;
    line-height: 1.2;
    font-variant-numeric: tabular-nums;
    letter-spacing: 0.2px;
  }

  .num-limits {
    display: flex;
    gap: 18px;
    font-variant-numeric: tabular-nums;

    .limit {
      display: flex;
      align-items: baseline;
      gap: 5px;

      span {
        font-size: 11.5px;
        color: var(--el-text-color-secondary);
      }

      b {
        font-size: 13px;
        font-weight: 500;
        color: var(--el-text-color-regular);
      }
    }
  }

  .is-normal .num-now {
    color: var(--el-text-color-primary);
  }

  .is-warning .num-now {
    color: var(--warn);
  }

  .is-danger .num-now {
    color: var(--danger);
  }

  .num-hint {
    font-size: 11px;
    font-variant-numeric: tabular-nums;
  }

  .empty-title {
    font-size: 13px;
    color: var(--el-text-color-regular);
  }

  .empty-sub {
    font-size: 11px;
    color: var(--el-text-color-secondary);
  }

  .track {
    position: relative;
    height: 6px;
    margin-top: 1px;
    border-radius: 3px;
    background: var(--el-fill-color-darker);
    overflow: hidden;

    &.is-tall {
      height: 8px;
      border-radius: 4px;
      margin-top: 8px;
    }
  }

  .fill {
    position: absolute;
    inset: 0 auto 0 0;
    background: var(--ok);

    .is-warning & {
      background: var(--warn);
    }

    .is-danger & {
      background: var(--danger);
    }
  }

  .tick {
    position: absolute;
    top: 0;
    bottom: 0;
    width: 1px;
    background: var(--el-bg-color);
    opacity: 0.95;
  }

  .cell-group {
    display: flex;
    flex-direction: column;
    gap: 1px;
    text-align: left;

    .group-name {
      font-size: 12.5px;
      color: var(--el-text-color-regular);
    }
  }

  .tag-manual {
    font-style: normal;
    font-size: 10.5px;
    padding: 0 4px;
    margin-left: 4px;
    border-radius: 2px;
    color: var(--warn);
    background: var(--el-color-warning-light-9);

    &.is-fee {
      color: var(--el-color-primary);
      background: var(--el-color-primary-light-9);
    }
  }

  .link {
    border: none;
    background: none;
    padding: 0;
    font-size: 11.5px;
    color: var(--el-color-primary);
    cursor: pointer;

    &:hover {
      text-decoration: underline;
    }
  }

  .cell-action {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 6px;
  }

  /* 详情 */
  .detail {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .detail-hero {
    padding: 12px 14px;
    border-radius: 10px;
    background: var(--el-fill-color-lighter);

    &.is-danger {
      background: var(--el-color-danger-light-9);
    }

    &.is-warning {
      background: var(--el-color-warning-light-9);
    }

    .hero-line {
      display: flex;
      align-items: baseline;
      gap: 10px;
    }

    .hero-label {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }

    .hero-now {
      font-size: 24px;
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }

    &.is-danger .hero-now {
      color: var(--danger);
    }

    &.is-warning .hero-now {
      color: var(--warn);
    }

    .hero-limits {
      display: flex;
      gap: 22px;
      margin-top: 6px;
      font-variant-numeric: tabular-nums;
    }

    .hero-limit {
      display: flex;
      align-items: baseline;
      gap: 6px;

      span {
        font-size: 12px;
        color: var(--el-text-color-secondary);
      }

      b {
        font-size: 16px;
        font-weight: 500;
        color: var(--el-text-color-primary);
      }
    }

    .hero-tip {
      margin: 10px 0 0;
      font-size: 12px;
      color: var(--el-text-color-regular);

      .sub {
        margin-left: 8px;
      }
    }
  }

  .detail-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .detail-table {
    margin-top: 2px;
  }

  .history {
    border-top: 1px solid var(--el-border-color-lighter);
    border-bottom: none;
  }

  .history-list {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .history-item {
    display: flex;
    flex-direction: column;
    gap: 3px;
    padding-bottom: 8px;
    border-bottom: 1px dashed var(--el-border-color-lighter);

    &:last-child {
      border-bottom: none;
      padding-bottom: 0;
    }
  }

  .history-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;
  }

  .history-scope {
    font-size: 12.5px;
    color: var(--el-text-color-primary);
  }

  .history-values {
    display: flex;
    align-items: baseline;
    gap: 6px;
    font-size: 12px;
    font-variant-numeric: tabular-nums;

    b {
      font-weight: 500;
    }
  }

  .group-dialog {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .full {
    width: 100%;
  }

  .more {
    border-top: 1px solid var(--el-border-color-lighter);
    border-bottom: none;
  }

  .catalog {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .catalog-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }

  .catalog-cell {
    display: flex;
    flex-direction: column;
    text-align: left;
    font-size: 12.5px;
  }

  .ocr {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .ocr-upload {
    padding: 8px 0;

    p {
      margin: 0 0 4px;
      font-size: 13px;
      color: var(--el-text-color-regular);
    }

    small {
      font-size: 11.5px;
      color: var(--el-text-color-secondary);
    }
  }

  .ocr-status {
    font-size: 12.5px;
    color: var(--el-color-primary);
  }

  .ocr-summary {
    font-size: 12.5px;
    color: var(--el-text-color-regular);
  }

  .ocr-cell {
    display: flex;
    flex-direction: column;
    text-align: left;
    font-size: 12.5px;
  }
}
</style>
