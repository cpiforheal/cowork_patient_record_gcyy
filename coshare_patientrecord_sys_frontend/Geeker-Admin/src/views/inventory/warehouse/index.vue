<template>
  <div class="warehouse-console" v-loading="loading">
    <header class="console-head">
      <div>
        <span class="eyebrow">进销存 · 库管台</span>
        <h1>库管台</h1>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" :loading="loading" aria-label="刷新" @click="refreshAll" />
        <el-button v-if="activeTab === 'inbound'" type="primary" :icon="Plus" @click="openInbound">新建入库</el-button>
        <el-button v-if="activeTab === 'inbound'" plain @click="openBulk">批量补货</el-button>
        <el-button v-if="activeTab === 'inbound'" plain @click="importVisible = true">期初导入</el-button>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="console-tabs" @tab-change="onTabChange">
      <el-tab-pane label="进货" name="inbound">
        <div class="filter-bar">
          <el-date-picker v-model="inboundRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" @change="loadInbound" />
          <el-input v-model="inboundSupplier" clearable placeholder="供应商" @keyup.enter="loadInbound" />
          <el-select v-model="inboundStatus" clearable placeholder="状态" @change="loadInbound">
            <el-option label="有效" value="ACTIVE" />
            <el-option label="已红冲" value="REVERSED" />
          </el-select>
          <el-button @click="loadInbound">查询</el-button>
        </div>
        <el-table :data="inboundOrders" border height="calc(100vh - 360px)" empty-text="还没有入库单">
          <el-table-column prop="orderNo" label="入库单号" width="160" />
          <el-table-column prop="supplier" label="供应商" min-width="150" show-overflow-tooltip />
          <el-table-column prop="purchaseDate" label="进货日期" width="110" />
          <el-table-column label="行数/数量" width="110">
            <template #default="{ row }">{{ row.lineCount }} 行 / {{ formatNumber(row.totalQuantity) }}</template>
          </el-table-column>
          <el-table-column prop="supplierDocumentNo" label="供货单号" min-width="130" show-overflow-tooltip />
          <el-table-column prop="createdBy" label="操作人" width="100" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'REVERSED' ? 'info' : 'success'" effect="plain" size="small">
                {{ row.status === "REVERSED" ? "已红冲" : "有效" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openOrder(row)">明细</el-button>
              <el-button v-if="row.status === 'ACTIVE'" link type="danger" @click="reverseOrder(row)">红冲</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="库存" name="stock">
        <div v-if="alerts.centralLow.length || alerts.expiring.length" class="alert-banner">
          <span>低库存 {{ alerts.centralLow.length }} 项 · 临期批次 {{ alerts.expiring.length }} 批</span>
          <el-button link type="warning" @click="stockFilter = 'LOW'; loadStock()">查看低库存</el-button>
        </div>
        <div class="filter-bar">
          <el-input v-model="stockKeyword" clearable placeholder="物资 / 规格 / 分类" @keyup.enter="loadStock" />
          <el-select v-model="stockCategory" clearable filterable placeholder="分类" @change="loadStock">
            <el-option v-for="category in categoryOptions" :key="category" :label="category" :value="category" />
          </el-select>
          <el-select v-model="stockFilter" clearable placeholder="筛选" @change="loadStock">
            <el-option label="仅低库存" value="LOW" />
          </el-select>
          <el-button @click="loadStock">查询</el-button>
          <el-button plain @click="openItemDialog()">新增物资</el-button>
        </div>
        <el-table :data="stockItems" border height="calc(100vh - 360px)" empty-text="还没有库存，先入库或导入期初">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="batch-panel">
                <el-table :data="batchesOf(row.itemId)" size="small" border empty-text="没有批次明细">
                  <el-table-column prop="batchNo" label="批号" min-width="120" />
                  <el-table-column prop="expiryDate" label="有效期" width="110" />
                  <el-table-column prop="quantity" label="数量" width="100" />
                  <el-table-column prop="locationType" label="库位" width="90">
                    <template #default="{ row: batch }">
                      {{ batch.locationType === "DEPARTMENT" ? batch.departmentName || "科室库" : "中心库" }}
                    </template>
                  </el-table-column>
                  <el-table-column prop="supplier" label="供应商" min-width="140" />
                  <el-table-column prop="purchaseDate" label="进货日期" width="110" />
                  <el-table-column prop="orderNo" label="入库单" width="150" />
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="物资" min-width="160" />
          <el-table-column prop="spec" label="规格" min-width="120" show-overflow-tooltip />
          <el-table-column prop="category" label="分类" width="110" />
          <el-table-column label="中心库" width="100">
            <template #default="{ row }">
              <strong :class="{ danger: row.lowStock }">{{ formatNumber(row.centralQuantity) }} {{ row.baseUnit || row.unit }}</strong>
            </template>
          </el-table-column>
          <el-table-column label="科室合计" width="100">
            <template #default="{ row }">{{ formatNumber(row.departmentQuantity) }}</template>
          </el-table-column>
          <el-table-column prop="warningLine" label="预警线" width="90" />
          <el-table-column label="最近进货" width="150">
            <template #default="{ row }">{{ row.latestInboundAt ? row.latestInboundAt.slice(0, 16).replace("T", " ") : "—" }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.lowStock" type="warning" effect="plain" size="small">低库存</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openItemDialog(row.itemId)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="申领" name="requests">
        <div class="filter-bar">
          <el-select v-model="requestStatus" clearable placeholder="状态" @change="loadRequests">
            <el-option label="待审核" value="pending" />
            <el-option label="部分发放" value="partially_issued" />
            <el-option label="已发放" value="received" />
            <el-option label="已驳回" value="rejected" />
          </el-select>
          <el-input v-model="requestKeyword" clearable placeholder="科室 / 物资 / 申请人" @keyup.enter="loadRequests" />
          <el-button @click="loadRequests">查询</el-button>
        </div>
        <el-table :data="filteredRequests" border height="calc(100vh - 360px)" empty-text="没有待处理的申领">
          <el-table-column prop="createdAt" label="申请时间" width="160">
            <template #default="{ row }">{{ row.createdAt?.slice(0, 16).replace("T", " ") }}</template>
          </el-table-column>
          <el-table-column prop="department" label="科室" width="110" />
          <el-table-column prop="itemSummary" label="物资" min-width="220" show-overflow-tooltip />
          <el-table-column prop="applicant" label="申请人" width="100" />
          <el-table-column prop="reason" label="理由" min-width="180" show-overflow-tooltip />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="requestTone(row.status)" effect="plain" size="small">{{ requestLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="['pending', 'approved', 'partially_issued'].includes(row.status)"
                link
                type="primary"
                @click="openRequest(row)"
              >
                确认发放
              </el-button>
              <el-button v-else link @click="openRequest(row)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>


    </el-tabs>

    <el-drawer v-model="inboundVisible" title="新建入库单" size="72%" append-to-body>
      <div class="drawer-form">
        <div class="form-row">
          <el-date-picker v-model="inboundForm.purchaseDate" type="date" value-format="YYYY-MM-DD" placeholder="进货日期" />
          <el-autocomplete
            v-model="inboundForm.supplier"
            :fetch-suggestions="querySuppliers"
            clearable
            placeholder="供应商（选填）"
            class="grow"
          />
          <el-input v-model="inboundForm.supplierDocumentNo" clearable placeholder="供货单号（选填）" />
        </div>
        <el-input v-model="inboundForm.note" clearable placeholder="备注（选填）" />
        <div class="inbound-tools">
          <el-input
            v-model="inboundSearch"
            clearable
            placeholder="输入物资名称 / 拼音首字母，回车添加"
            @keydown="onInboundSearchKeydown"
          />
          <el-button @click="openPaste">批量粘贴</el-button>
          <el-button @click="loadLastInbound">载入上次单</el-button>
        </div>
        <div v-if="searchResults.length" class="search-results">
          <button
            v-for="(item, index) in searchResults"
            :key="item.itemId"
            type="button"
            :class="{ 'is-active': index === searchIndex }"
            @click="pickSearchItem(item)"
          >
            <span>{{ item.name }}<small v-if="item.spec">（{{ item.spec }}）</small></span>
            <em>{{ item.issueUnit || item.unit }}</em>
          </button>
        </div>
        <div v-if="inboundForm.lines.length" class="line-table">
          <div class="line-head">
            <span>物资</span><span>领用数量</span><span>换算</span><span>折算</span><span>批号</span><span>有效期</span><span>备注</span><span />
          </div>
          <div v-for="(line, index) in inboundForm.lines" :key="line.key" class="line-row">
            <span class="line-item-name">
              {{ line.itemName }}
              <small v-if="line.mergeCount > 1" :title="line.sources.join('；')">由 {{ line.mergeCount }} 行合并</small>
            </span>
            <el-input-number v-model="line.issueQuantity" :min="0.01" :precision="2" :controls="false" />
            <el-input-number v-model="line.conversionFactor" :min="0.0001" :precision="4" :controls="false" />
            <span class="line-base">{{ formatNumber(Number(line.issueQuantity || 0) * Number(line.conversionFactor || 1)) }}</span>
            <el-input v-model="line.batchNo" :placeholder="line.batchRequired ? '必填批号' : '批号'" />
            <el-date-picker v-model="line.expiryDate" type="date" value-format="YYYY-MM-DD" :placeholder="line.expiryRequired ? '必填效期' : '有效期'" />
            <el-input v-model="line.remark" placeholder="备注" />
            <el-button link type="danger" @click="removeInboundLine(index)">删除</el-button>
          </div>
        </div>
        <div class="drawer-foot">
          <span>{{ inboundForm.lines.length }} 行待入库{{ inboundForm.lines.reduce((sum, line) => sum + Math.max(0, line.mergeCount - 1), 0) ? ` · 已合并 ${inboundForm.lines.reduce((sum, line) => sum + Math.max(0, line.mergeCount - 1), 0)} 行` : "" }}</span>
          <el-button @click="inboundVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="saveInbound">保存入库</el-button>
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="pasteVisible" title="批量粘贴入库" width="820px" append-to-body>
      <el-input
        v-model="pasteText"
        type="textarea"
        :rows="5"
        placeholder="每行一条：物资名称 [Tab/逗号] 数量 [Tab/逗号] 批号 有效期（可选）"
      />
      <div class="paste-actions">
        <el-button @click="previewPaste">解析预览</el-button>
        <span>数量按领用单位；批号/效期必填物资缺字段会标红</span>
      </div>
      <el-table v-if="pasteRows.length" :data="pasteRows" border size="small" max-height="360">
        <el-table-column label="名称" min-width="150">
          <template #default="{ row }">
            <el-input v-model="row.name" @change="rematchPaste" />
          </template>
        </el-table-column>
        <el-table-column label="数量" width="100">
          <template #default="{ row }"><el-input-number v-model="row.quantity" :min="0.01" :precision="2" :controls="false" /></template>
        </el-table-column>
        <el-table-column label="匹配" min-width="180">
          <template #default="{ row }">
            <el-select
              v-if="row.candidates?.length"
              :model-value="row.item?.itemId || ''"
              placeholder="选择规格"
              @change="(id: string) => pickPasteCandidate(row, id)"
            >
              <el-option v-for="candidate in row.candidates" :key="candidate.itemId" :label="`${candidate.name}${candidate.spec ? '（' + candidate.spec + '）' : ''}`" :value="candidate.itemId" />
            </el-select>
            <span v-else-if="row.item">{{ row.item.name }}<small v-if="row.item.spec">（{{ row.item.spec }}）</small></span>
            <span v-else class="danger">{{ row.error || "未匹配" }}</span>
          </template>
        </el-table-column>
        <el-table-column label="批号" width="120">
          <template #default="{ row }"><el-input v-model="row.batchNo" /></template>
        </el-table-column>
        <el-table-column label="有效期" width="140">
          <template #default="{ row }"><el-input v-model="row.expiryDate" placeholder="YYYY-MM-DD" /></template>
        </el-table-column>
      </el-table>
      <template #footer>
        <span class="paste-summary">{{ pasteRows.filter(row => row.item).length }} / {{ pasteRows.length }} 行可导入</span>
        <el-button @click="pasteVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!pasteRows.length || pasteRows.some(row => !row.item)" @click="importPaste">导入到清单</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="bulkVisible" title="批量补货 / 库存初始化" size="94%" append-to-body>
      <div class="bulk-shell" v-loading="bulkLoading">
        <div class="bulk-toolbar">
          <el-date-picker v-model="bulkForm.purchaseDate" type="date" value-format="YYYY-MM-DD" placeholder="进货日期" />
          <el-autocomplete v-model="bulkForm.supplier" :fetch-suggestions="querySuppliers" clearable placeholder="供应商（选填）" class="grow" />
          <el-input v-model="bulkForm.supplierDocumentNo" clearable placeholder="供货单号（选填）" />
          <el-input v-model="bulkForm.note" clearable placeholder="备注（选填）" />
        </div>
        <div class="bulk-filter">
          <el-input v-model="bulkKeyword" clearable placeholder="搜索物资 / 规格 / 分类" />
          <el-select v-model="bulkFilter" clearable placeholder="筛选">
            <el-option label="近30天常用" value="COMMON" />
            <el-option label="低库存" value="LOW" />
            <el-option label="未填数量" value="EMPTY" />
          </el-select>
          <span class="bulk-summary">已填 {{ bulkFilledCount }} 项 · 共 {{ bulkRows.length }} 项；数量为 0 的行不会提交</span>
        </div>
        <el-table :data="bulkVisibleRows" border height="calc(100vh - 330px)" row-key="itemId">
          <el-table-column prop="name" label="物资" min-width="180">
            <template #default="{ row }">
              <strong>{{ row.name }}</strong>
              <small v-if="row.spec">（{{ row.spec }}）</small>
              <el-tag v-if="row.lowStock" type="warning" size="small" effect="plain">低库存</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="category" label="分类" width="100" />
          <el-table-column label="当前库存" width="110">
            <template #default="{ row }">{{ formatNumber(row.centralQuantity) }} {{ row.baseUnit }}</template>
          </el-table-column>
          <el-table-column label="本次进货" width="150" label-class-name="bulk-qty-head" class-name="bulk-qty-cell">
            <template #default="{ row }">
              <el-input-number v-model="row.quantity" :min="0" :precision="2" :controls="false" class="bulk-qty" />
              <small class="bulk-qty-unit">{{ row.issueUnit }}</small>
            </template>
          </el-table-column>
          <el-table-column label="折算" width="100">
            <template #default="{ row }">{{ formatNumber(Number(row.quantity || 0) * row.factor) }} {{ row.baseUnit }}</template>
          </el-table-column>
          <el-table-column label="批号（选填）" width="130">
            <template #default="{ row }"><el-input v-model="row.batchNo" placeholder="批号" /></template>
          </el-table-column>
          <el-table-column label="有效期（选填）" width="140">
            <template #default="{ row }"><el-input v-model="row.expiryDate" placeholder="YYYY-MM-DD" /></template>
          </el-table-column>
          <el-table-column label="备注（选填）" min-width="130">
            <template #default="{ row }"><el-input v-model="row.remark" placeholder="备注" /></template>
          </el-table-column>
        </el-table>
        <div class="bulk-foot">
          <span>批号/效期留空时生成“常备库存”批次；供应商和日期也可不填。</span>
          <el-button @click="bulkVisible = false">取消</el-button>
          <el-button type="primary" :loading="bulkSaving" :disabled="!bulkFilledCount" @click="saveBulk">
            保存入库（{{ bulkFilledCount }} 项）
          </el-button>
        </div>
      </div>
    </el-drawer>

    <el-drawer v-model="orderVisible" :title="currentOrder?.orderNo || '入库单明细'" size="58%" append-to-body>
      <template v-if="currentOrder">
        <div class="order-meta">
          <span>供应商：{{ currentOrder.supplier || "未填" }}</span>
          <span>进货日期：{{ currentOrder.purchaseDate }}</span>
          <span>状态：{{ currentOrder.status === "REVERSED" ? "已红冲" : "有效" }}</span>
        </div>
        <el-table :data="currentOrder.lines || []" border size="small">
          <el-table-column prop="itemName" label="物资" min-width="150" />
          <el-table-column prop="issueQuantity" label="数量" width="90" />
          <el-table-column prop="conversionFactor" label="换算" width="80" />
          <el-table-column prop="quantity" label="基准数量" width="100" />
          <el-table-column prop="batchNo" label="批号" width="120" />
          <el-table-column prop="expiryDate" label="有效期" width="110" />
        </el-table>
        <p v-if="currentOrder.status === 'REVERSED'" class="reversed-note">
          红冲原因：{{ currentOrder.reverseReason }}（{{ currentOrder.reversedBy }}）
        </p>
      </template>
    </el-drawer>

    <el-drawer v-model="requestVisible" title="确认发放" size="60%" append-to-body>
      <template v-if="currentRequest">
        <div class="order-meta">
          <span>{{ currentRequest.department }}</span>
          <span>{{ currentRequest.applicant }}</span>
          <span>{{ requestLabel(currentRequest.status) }}</span>
        </div>
        <el-table :data="requestDraft" border size="small">
          <el-table-column label="物资" min-width="150">
            <template #default="{ row }">{{ itemName(row.itemId) }}</template>
          </el-table-column>
          <el-table-column prop="quantity" label="申请" width="80" />
          <el-table-column label="中心可用" width="90">
            <template #default="{ row }">{{ formatNumber(centralOf(row.itemId)) }}</template>
          </el-table-column>
          <el-table-column label="本次发放" width="130">
            <template #default="{ row }">
              <el-input-number
                v-model="row.issuedQuantity"
                :min="0"
                :max="Number(row.quantity)"
                :precision="2"
                :controls="false"
                :disabled="!['pending', 'approved', 'partially_issued'].includes(currentRequest.status)"
              />
            </template>
          </el-table-column>
        </el-table>
        <div class="drawer-foot">
          <span>发放后直接进入科室库</span>
          <el-button v-if="currentRequest.status === 'pending'" type="danger" plain @click="rejectCurrent">驳回</el-button>
          <el-button
            v-if="['pending', 'approved', 'partially_issued'].includes(currentRequest.status)"
            type="primary"
            :loading="saving"
            @click="fulfillCurrent"
          >
            确认发放
          </el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="itemVisible" :title="itemForm.id ? '编辑物资' : '新增物资'" width="640px" append-to-body>
      <el-form label-position="top" class="item-form">
        <div class="form-row">
          <el-form-item label="物资名称" required><el-input v-model="itemForm.name" /></el-form-item>
          <el-form-item label="规格"><el-input v-model="itemForm.spec" /></el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="分类"><el-input v-model="itemForm.category" /></el-form-item>
          <el-form-item label="默认位置"><el-input v-model="itemForm.location" /></el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="单位"><el-input v-model="itemForm.unit" /></el-form-item>
          <el-form-item label="基准单位"><el-input v-model="itemForm.baseUnit" /></el-form-item>
          <el-form-item label="领用单位"><el-input v-model="itemForm.issueUnit" /></el-form-item>
          <el-form-item label="预警线"><el-input-number v-model="itemForm.lowStockThreshold" :min="0" :precision="2" :controls="false" /></el-form-item>
        </div>
        <div class="form-row">
          <el-checkbox v-model="itemForm.batchRequired">入库必须批号</el-checkbox>
          <el-checkbox v-model="itemForm.expiryRequired">入库必须效期</el-checkbox>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="itemVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingItem" @click="saveItem">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="期初库存导入" width="760px" append-to-body>
      <div class="import-bar">
        <el-button plain @click="downloadTemplate">下载模板</el-button>
        <input type="file" accept=".xlsx" @change="onImportFile" />
        <el-button :disabled="!importFile" :loading="importing" @click="previewImport">预览</el-button>
        <el-button type="primary" :disabled="!importFile || !importPreview?.rows?.length" :loading="importing" @click="commitImport">
          确认导入
        </el-button>
      </div>
      <el-alert v-if="importPreview?.errors?.length" type="warning" :closable="false" show-icon title="有错误行，需修正后重新上传">
        <p v-for="error in importPreview.errors" :key="error.row">{{ error.row }} 行 {{ error.name }}：{{ error.message }}</p>
      </el-alert>
      <el-table v-if="importPreview" :data="importPreview.rows" border size="small" max-height="360">
        <el-table-column prop="row" label="行" width="60" />
        <el-table-column prop="name" label="物资" min-width="130" />
        <el-table-column prop="spec" label="规格" min-width="100" />
        <el-table-column prop="issueUnit" label="领用单位" width="90" />
        <el-table-column prop="factor" label="换算" width="80" />
        <el-table-column prop="baseQuantity" label="基准数量" width="100" />
        <el-table-column prop="status" label="匹配" width="90">
          <template #default="{ row }">{{ row.status === "MATCHED" ? "已有物资" : "新建物资" }}</template>
        </el-table-column>
        <el-table-column prop="warningLine" label="预警线" width="90" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import dayjs from "dayjs";
import { Plus, Refresh } from "@element-plus/icons-vue";
import {
  commitInventoryOpeningImportApi,
  createInventoryInboundOrderApi,
  downloadInventoryOpeningTemplateApi,
  fulfillInventoryRequestApi,
  getInventoryDbApi,
  getInventoryInboundOrderApi,
  getInventoryInboundOrdersApi,
  getInventoryInboundSuppliersApi,
  getInventoryItemOptionsApi,
  getInventoryWarehouseAlertsApi,
  getInventoryWarehouseStockApi,
  previewInventoryOpeningImportApi,
  rejectInventoryRequestApi,
  reverseInventoryInboundOrderApi,
  saveInventoryItemApi,
  type InventoryInboundOrder,
  type InventoryItemOption,
  type InventoryRequest
} from "@/api/modules/inventory";
import { conversionFactor, matchBulkRows, parseBulkText, type ParsedBulkRow } from "@/views/inventory/manage/composables/bulkItemImport";

const activeTab = ref<"inbound" | "stock" | "requests">("inbound");
const loading = ref(false);
const saving = ref(false);
const items = ref<InventoryItemOption[]>([]);
const requests = ref<InventoryRequest[]>([]);
const suppliers = ref<string[]>([]);

const inboundOrders = ref<InventoryInboundOrder[]>([]);
const inboundRange = ref<[string, string]>([monthStart(), new Date().toISOString().slice(0, 10)]);
const inboundSupplier = ref("");
const inboundStatus = ref("");

const stockKeyword = ref("");
const stockCategory = ref("");
const stockFilter = ref("");
const stockItems = ref<any[]>([]);
const stockBatches = ref<any[]>([]);
const alerts = reactive<{ centralLow: any[]; departmentLow: any[]; expiring: any[] }>({
  centralLow: [],
  departmentLow: [],
  expiring: []
});

const requestStatus = ref("");
const requestKeyword = ref("");

const inboundVisible = ref(false);
const inboundSearch = ref("");
const searchIndex = ref(0);
const pasteVisible = ref(false);
const pasteText = ref("");
const pasteRows = ref<ParsedBulkRow[]>([]);
const pasting = ref(false);
const orderVisible = ref(false);
const requestVisible = ref(false);
const importVisible = ref(false);
const currentOrder = ref<InventoryInboundOrder | null>(null);
const currentRequest = ref<InventoryRequest | null>(null);
const requestDraft = ref<Array<{ id: string; itemId: string; quantity: number; issuedQuantity: number }>>([]);
let inboundKey = 1;

const inboundForm = reactive({
  purchaseDate: new Date().toISOString().slice(0, 10),
  supplier: "",
  supplierDocumentNo: "",
  note: "",
  lines: [newInboundLine()]
});
const searchResults = computed(() => {
  const keyword = inboundSearch.value.trim().toLowerCase();
  if (!keyword) return [];
  return items.value
    .filter(item => `${item.name} ${item.spec || ""}`.toLowerCase().includes(keyword))
    .slice(0, 8);
});

const itemVisible = ref(false);
const savingItem = ref(false);
const itemForm = reactive({
  id: "",
  name: "",
  spec: "",
  category: "",
  unit: "",
  baseUnit: "",
  issueUnit: "",
  location: "",
  lowStockThreshold: 0,
  batchRequired: false,
  expiryRequired: false
});
const importFile = ref<File | null>(null);
const importPreview = ref<{ rows: any[]; errors: Array<{ row: number; name: string; message: string }> } | null>(null);
const importing = ref(false);
const bulkVisible = ref(false);
const bulkLoading = ref(false);
const bulkSaving = ref(false);
const bulkKeyword = ref("");
const bulkFilter = ref("ALL");
const bulkForm = reactive({
  purchaseDate: new Date().toISOString().slice(0, 10),
  supplier: "",
  supplierDocumentNo: "",
  note: ""
});
const bulkRows = ref<Array<{
  itemId: string; name: string; spec: string; category: string; unit: string; baseUnit: string; issueUnit: string;
  centralQuantity: number; lowStock: boolean; latestInboundAt: string; factor: number;
  quantity: number; batchNo: string; expiryDate: string; remark: string;
}>>([]);

const bulkVisibleRows = computed(() => {
  const keyword = bulkKeyword.value.trim().toLowerCase();
  return bulkRows.value.filter(row => {
    if (keyword && !`${row.name} ${row.spec} ${row.category}`.toLowerCase().includes(keyword)) return false;
    if (bulkFilter.value === "COMMON") return Boolean(row.latestInboundAt && dayjs(row.latestInboundAt).isAfter(dayjs().subtract(30, "day")));
    if (bulkFilter.value === "LOW") return row.lowStock;
    if (bulkFilter.value === "EMPTY") return Number(row.quantity || 0) <= 0;
    return true;
  });
});
const bulkFilledCount = computed(() => bulkRows.value.filter(row => Number(row.quantity || 0) > 0).length);

const categoryOptions = computed(() => Array.from(new Set(items.value.map(item => item.category).filter(Boolean))));
const filteredRequests = computed(() => {
  const keyword = requestKeyword.value.trim().toLowerCase();
  return requests.value.filter(request => {
    if (requestStatus.value && request.status !== requestStatus.value) return false;
    if (!keyword) return true;
    return `${request.department} ${request.applicant} ${request.itemSummary || ""} ${request.reason || ""}`.toLowerCase().includes(keyword);
  });
});

function monthStart() {
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-01`;
}

function newInboundLine(item?: InventoryItemOption) {
  return {
    key: inboundKey++,
    itemId: item?.itemId || "",
    itemName: item?.name || "",
    issueQuantity: 1,
    conversionFactor: conversionFactor(item),
    batchNo: "",
    expiryDate: "",
    remark: "",
    batchRequired: Boolean(item?.batchRequired),
    expiryRequired: Boolean(item?.expiryRequired),
    mergeCount: 1,
    sources: [] as string[]
  };
}

const formatNumber = (value?: number | string) => Number(value || 0).toLocaleString("zh-CN", { maximumFractionDigits: 2 });
const itemName = (itemId: string) => items.value.find(item => item.itemId === itemId)?.name || itemId;

const addInboundItem = (item: InventoryItemOption, quantity = 1, batchNo = "", expiryDate = "", remark = "") => {
  const key = `${item.itemId}|${batchNo.trim().toLowerCase()}|${expiryDate.trim()}`;
  const existing = inboundForm.lines.find(line =>
    `${line.itemId}|${line.batchNo.trim().toLowerCase()}|${line.expiryDate.trim()}` === key
  );
  if (existing) {
    existing.issueQuantity = Number(existing.issueQuantity || 0) + Number(quantity || 0);
    existing.mergeCount += 1;
    existing.sources.push(`${formatNumber(quantity)} ${item.issueUnit || item.unit || ""}`);
    return;
  }
  const line = newInboundLine(item);
  line.issueQuantity = Number(quantity || 1);
  line.batchNo = batchNo;
  line.expiryDate = expiryDate;
  line.remark = remark;
  line.sources = [`${formatNumber(quantity)} ${item.issueUnit || item.unit || ""}`];
  inboundForm.lines.push(line);
};

const pickSearchItem = (item: InventoryItemOption) => {
  addInboundItem(item, 1);
  inboundSearch.value = "";
  searchIndex.value = 0;
};

const onInboundSearchKeydown = (event: KeyboardEvent) => {
  if (!searchResults.value.length) return;
  if (event.key === "ArrowDown") {
    event.preventDefault();
    searchIndex.value = (searchIndex.value + 1) % searchResults.value.length;
  } else if (event.key === "ArrowUp") {
    event.preventDefault();
    searchIndex.value = (searchIndex.value - 1 + searchResults.value.length) % searchResults.value.length;
  } else if (event.key === "Enter") {
    event.preventDefault();
    pickSearchItem(searchResults.value[searchIndex.value] || searchResults.value[0]);
  } else if (event.key === "Escape") {
    inboundSearch.value = "";
  }
};

const openPaste = () => {
  pasteText.value = "";
  pasteRows.value = [];
  pasteVisible.value = true;
};

const previewPaste = () => {
  pasteRows.value = matchBulkRows(parseBulkText(pasteText.value, true), items.value);
};

const rematchPaste = () => {
  pasteRows.value = matchBulkRows(
    pasteRows.value.map(row => ({ ...row, item: undefined, candidates: [], error: undefined })),
    items.value
  );
};

const pickPasteCandidate = (row: ParsedBulkRow, itemId: string) => {
  const item = items.value.find(option => option.itemId === itemId);
  if (!item) return;
  row.item = item;
  row.candidates = [];
  row.error = "";
};

const importPaste = () => {
  const ready = pasteRows.value.filter(row => row.item && row.quantity > 0 && !row.error);
  if (!ready.length) return ElMessage.warning("没有可导入的匹配行");
  ready.forEach(row => addInboundItem(row.item!, row.quantity, row.batchNo, row.expiryDate));
  pasteVisible.value = false;
  ElMessage.success(`已导入 ${ready.length} 行，重复物资已合并`);
};

const loadLastInbound = async () => {
  const list = await getInventoryInboundOrdersApi({ status: "ACTIVE" });
  const last = list.data?.list?.[0];
  if (!last) return ElMessage.info("还没有可复用的入库单");
  const detail = (await getInventoryInboundOrderApi(last.id)).data;
  if (!detail) return;
  inboundForm.supplier = detail.supplier || "";
  inboundForm.supplierDocumentNo = detail.supplierDocumentNo || "";
  inboundForm.purchaseDate = new Date().toISOString().slice(0, 10);
  inboundForm.lines = [];
  (detail.lines ?? []).forEach(line => {
    const option = items.value.find(item => item.itemId === line.itemId);
    if (option) addInboundItem(option, Number(line.issueQuantity || 0), line.batchNo || "", line.expiryDate || "", line.remark || "");
  });
  ElMessage.success("已载入上次入库单");
};
const batchesOf = (itemId: string) => stockBatches.value.filter(batch => batch.itemId === itemId);
const centralOf = (itemId: string) =>
  stockItems.value.find(item => item.itemId === itemId)?.centralQuantity ?? 0;

const requestLabel = (status: string) =>
  ({ pending: "待审核", approved: "已通过待发放", partially_issued: "部分发放", issued: "已发放", received: "已发放", rejected: "已驳回", cancelled: "已撤销", void: "已作废" })[status] || status;
const requestTone = (status: string) =>
  status === "rejected" ? "danger" : status === "received" ? "success" : status === "partially_issued" ? "warning" : "info";

const loadDb = async () => {
  const [options, db] = await Promise.all([getInventoryItemOptionsApi(), getInventoryDbApi()]);
  items.value = options.data ?? [];
  requests.value = db.data?.requests ?? [];
};

const loadInbound = async () => {
  const result = await getInventoryInboundOrdersApi({
    from: inboundRange.value?.[0],
    to: inboundRange.value?.[1],
    supplier: inboundSupplier.value || undefined,
    status: inboundStatus.value || undefined
  });
  inboundOrders.value = result.data?.list ?? [];
};

const loadStock = async () => {
  const result = await getInventoryWarehouseStockApi({
    keyword: stockKeyword.value || undefined,
    category: stockCategory.value || undefined,
    filter: stockFilter.value || undefined
  });
  stockItems.value = result.data?.items ?? [];
  stockBatches.value = result.data?.batches ?? [];
};

const loadRequests = async () => {
  const result = await getInventoryDbApi();
  requests.value = result.data?.requests ?? [];
  if (!requestStatus.value) {
    requests.value = requests.value.filter(request => ["pending", "approved", "partially_issued", "received", "rejected"].includes(request.status));
  }
};

const loadAlerts = async () => {
  const result = await getInventoryWarehouseAlertsApi();
  alerts.centralLow = result.data?.centralLow ?? [];
  alerts.departmentLow = result.data?.departmentLow ?? [];
  alerts.expiring = result.data?.expiring ?? [];
};

const refreshAll = async () => {
  loading.value = true;
  try {
    await Promise.all([loadDb(), loadInbound(), loadStock(), loadAlerts(), loadRequests()]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取库管台失败");
  } finally {
    loading.value = false;
  }
};

const onTabChange = async (name: string | number) => {
  if (name === "inbound") await loadInbound();
  else if (name === "stock") await loadStock();
  else await loadRequests();
};

const querySuppliers = (query: string, callback: (rows: Array<{ value: string }>) => void) => {
  const keyword = query.trim().toLowerCase();
  callback(suppliers.value.filter(name => !keyword || name.toLowerCase().includes(keyword)).map(name => ({ value: name })));
};

const openBulk = async () => {
  bulkLoading.value = true;
  try {
    await loadStock();
    bulkRows.value = stockItems.value.map(item => ({
      itemId: item.itemId,
      name: item.name,
      spec: item.spec || "",
      category: item.category || "未分类",
      unit: item.unit || "",
      baseUnit: item.baseUnit || item.unit || "",
      issueUnit: item.issueUnit || item.unit || "",
      centralQuantity: Number(item.centralQuantity || 0),
      lowStock: Boolean(item.lowStock),
      latestInboundAt: item.latestInboundAt || "",
      factor: Number(item.conversionFactor || 1) || 1,
      quantity: 0,
      batchNo: "",
      expiryDate: "",
      remark: ""
    }));
    bulkForm.purchaseDate = new Date().toISOString().slice(0, 10);
    bulkForm.supplier = "";
    bulkForm.supplierDocumentNo = "";
    bulkForm.note = "";
    bulkKeyword.value = "";
    bulkFilter.value = "ALL";
    suppliers.value = (await getInventoryInboundSuppliersApi()).data ?? [];
    bulkVisible.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "读取耗材目录失败");
  } finally {
    bulkLoading.value = false;
  }
};

const saveBulk = async () => {
  const lines = bulkRows.value.filter(row => Number(row.quantity || 0) > 0);
  if (!lines.length) return ElMessage.warning("请至少填写一项进货数量");
  bulkSaving.value = true;
  try {
    await createInventoryInboundOrderApi({
      bulkMode: true,
      purchaseDate: bulkForm.purchaseDate,
      supplier: bulkForm.supplier,
      supplierDocumentNo: bulkForm.supplierDocumentNo,
      note: bulkForm.note || "批量补货/库存初始化",
      lines: lines.map(row => ({
        itemId: row.itemId,
        issueQuantity: row.quantity,
        conversionFactor: row.factor,
        batchNo: row.batchNo,
        expiryDate: row.expiryDate,
        remark: row.remark
      }))
    });
    ElMessage.success(`已批量入库 ${lines.length} 项`);
    bulkVisible.value = false;
    await Promise.all([loadInbound(), loadStock(), loadAlerts()]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "批量入库失败");
  } finally {
    bulkSaving.value = false;
  }
};

const openInbound = async () => {
  inboundForm.purchaseDate = new Date().toISOString().slice(0, 10);
  inboundForm.supplier = "";
  inboundForm.supplierDocumentNo = "";
  inboundForm.note = "";
  inboundForm.lines = [];
  suppliers.value = (await getInventoryInboundSuppliersApi()).data ?? [];
  inboundVisible.value = true;
};

const removeInboundLine = (index: number) => inboundForm.lines.splice(index, 1);

const saveInbound = async () => {
  const lines = inboundForm.lines.filter(line => line.itemId && Number(line.issueQuantity) > 0);
  if (!lines.length) return ElMessage.warning("请至少添加一行入库物资");
  const invalid = lines.find(line =>
    Number(line.issueQuantity) <= 0 ||
    (line.batchRequired && !line.batchNo.trim()) ||
    (line.expiryRequired && !line.expiryDate)
  );
  if (invalid) return ElMessage.warning(`${invalid.itemName} 的数量、批号或有效期不完整`);
  saving.value = true;
  try {
    await createInventoryInboundOrderApi({
      purchaseDate: inboundForm.purchaseDate,
      supplier: inboundForm.supplier,
      supplierDocumentNo: inboundForm.supplierDocumentNo,
      note: inboundForm.note,
      lines: lines.map(line => ({
        itemId: line.itemId,
        issueQuantity: line.issueQuantity,
        conversionFactor: line.conversionFactor,
        batchNo: line.batchNo,
        expiryDate: line.expiryDate,
        remark: line.remark
      }))
    });
    ElMessage.success("入库单已保存");
    inboundVisible.value = false;
    await Promise.all([loadInbound(), loadStock(), loadAlerts()]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "入库失败");
  } finally {
    saving.value = false;
  }
};

const openOrder = async (row: InventoryInboundOrder) => {
  const result = await getInventoryInboundOrderApi(row.id);
  currentOrder.value = result.data ?? row;
  orderVisible.value = true;
};

const reverseOrder = async (row: InventoryInboundOrder) => {
  try {
    const { value } = await ElMessageBox.prompt(`红冲 ${row.orderNo}，请填写原因。已出库/已使用的批次不能红冲。`, "红冲入库单", {
      confirmButtonText: "确认红冲",
      cancelButtonText: "取消",
      inputValidator: value => (value && value.trim() ? true : "必须填写原因")
    });
    await reverseInventoryInboundOrderApi(row.id, String(value).trim());
    ElMessage.success("已红冲");
    await Promise.all([loadInbound(), loadStock(), loadAlerts()]);
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(error instanceof Error ? error.message : "红冲失败");
  }
};

const openRequest = (row: InventoryRequest) => {
  currentRequest.value = row;
  requestDraft.value = (row.lines ?? []).map(line => ({
    id: line.id,
    itemId: line.itemId,
    quantity: Number(line.quantity || 0),
    issuedQuantity: Math.min(Number(line.quantity || 0), centralOf(line.itemId))
  }));
  requestVisible.value = true;
};

const fulfillCurrent = async () => {
  if (!currentRequest.value) return;
  saving.value = true;
  try {
    await fulfillInventoryRequestApi({
      id: currentRequest.value.id,
      lines: requestDraft.value.map(line => ({ id: line.id, issuedQuantity: line.issuedQuantity }))
    });
    ElMessage.success("已发放到科室库");
    requestVisible.value = false;
    await Promise.all([loadRequests(), loadStock(), loadAlerts()]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "发放失败");
  } finally {
    saving.value = false;
  }
};

const rejectCurrent = async () => {
  if (!currentRequest.value) return;
  try {
    const { value } = await ElMessageBox.prompt("请填写驳回原因。", "驳回申领", {
      confirmButtonText: "确认驳回",
      cancelButtonText: "取消",
      inputValidator: value => (value && value.trim() ? true : "必须填写原因")
    });
    await rejectInventoryRequestApi({ id: currentRequest.value.id, reason: String(value).trim() });
    ElMessage.success("已驳回");
    requestVisible.value = false;
    await loadRequests();
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(error instanceof Error ? error.message : "驳回失败");
  }
};

const openItemDialog = (itemId?: string) => {
  const item = items.value.find(entry => entry.id === itemId);
  itemForm.id = item?.id || "";
  itemForm.name = item?.name || "";
  itemForm.spec = item?.spec || "";
  itemForm.category = item?.category || "";
  itemForm.unit = item?.unit || "";
  itemForm.baseUnit = item?.baseUnit || item?.unit || "";
  itemForm.issueUnit = item?.issueUnit || item?.unit || "";
  itemForm.location = item?.location || "中心库";
  itemForm.lowStockThreshold = Number(item?.lowStockThreshold || 0);
  itemForm.batchRequired = Boolean(item?.batchRequired);
  itemForm.expiryRequired = Boolean(item?.expiryRequired);
  itemVisible.value = true;
};

const saveItem = async () => {
  if (!itemForm.name.trim()) return ElMessage.warning("请填写物资名称");
  savingItem.value = true;
  try {
    await saveInventoryItemApi({
      id: itemForm.id || undefined,
      name: itemForm.name.trim(),
      spec: itemForm.spec.trim(),
      category: itemForm.category.trim(),
      unit: itemForm.unit.trim() || itemForm.baseUnit.trim(),
      baseUnit: itemForm.baseUnit.trim() || itemForm.unit.trim(),
      issueUnit: itemForm.issueUnit.trim() || itemForm.unit.trim(),
      location: itemForm.location.trim(),
      lowStockThreshold: itemForm.lowStockThreshold,
      batchRequired: itemForm.batchRequired,
      expiryRequired: itemForm.expiryRequired,
      enabled: true
    });
    ElMessage.success("物资已保存");
    itemVisible.value = false;
    await Promise.all([loadDb(), loadStock()]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "保存物资失败");
  } finally {
    savingItem.value = false;
  }
};

const downloadTemplate = async () => {
  const file = await downloadInventoryOpeningTemplateApi();
  const url = URL.createObjectURL(file.blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = file.filename;
  link.click();
  URL.revokeObjectURL(url);
};

const onImportFile = (event: Event) => {
  const target = event.target as HTMLInputElement;
  importFile.value = target.files?.[0] ?? null;
  importPreview.value = null;
};

const previewImport = async () => {
  if (!importFile.value) return;
  importing.value = true;
  try {
    const result = await previewInventoryOpeningImportApi(importFile.value);
    importPreview.value = result.data ?? null;
    if (result.data?.errors?.length) ElMessage.warning("有错误行，请修正后重新上传");
    else ElMessage.success(`可导入 ${result.data?.rows?.length || 0} 行`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "预览失败");
  } finally {
    importing.value = false;
  }
};

const commitImport = async () => {
  if (!importFile.value) return;
  importing.value = true;
  try {
    const result = await commitInventoryOpeningImportApi(importFile.value);
    ElMessage.success(`已导入 ${result.data?.rows || 0} 行`);
    importVisible.value = false;
    importPreview.value = null;
    importFile.value = null;
    await Promise.all([loadDb(), loadStock(), loadAlerts()]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "导入失败");
  } finally {
    importing.value = false;
  }
};

onMounted(() => {
  void refreshAll();
});
</script>

<style scoped lang="scss">
.warehouse-console {
  padding: 18px 20px;
  min-height: calc(100vh - 150px);
  background: #faf7f1;
}

.console-head {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;

  .eyebrow {
    color: #347d68;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.08em;
  }

  h1 {
    margin: 2px 0 0;
    color: #2f2d27;
    font-size: 22px;
  }
}

.head-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.console-tabs {
  padding: 10px 16px 16px;
  background: #fffdf9;
  border: 1px solid #efe8dd;
  border-radius: 14px;
}

.alert-banner {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 8px 10px;
  margin-bottom: 8px;
  color: #8a5a12;
  background: #fff8e8;
  border: 1px solid #f0dcb4;
  border-radius: 10px;
  font-size: 12px;

  .el-button {
    margin-left: auto;
  }
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;

  .el-input {
    width: 220px;
  }
}

.batch-panel {
  padding: 8px 16px;
  background: #fbf9f5;
}

.alert-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.alert-card {
  padding: 10px;
  background: #fffdf9;
  border: 1px solid #efe8dd;
  border-radius: 12px;

  &.wide {
    grid-column: 1 / -1;
  }

  header {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    margin-bottom: 6px;

    strong {
      color: #2f2d27;
      font-size: 13px;
    }

    span {
      color: #9a9285;
      font-size: 11px;
    }
  }
}

.drawer-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;

  .grow {
    flex: 1 1 220px;
  }
}

.line-table {
  border: 1px solid #efe8dd;
  border-radius: 10px;
}

.line-head,
.line-row {
  display: grid;
  grid-template-columns: 1.4fr 0.7fr 0.6fr 0.6fr 0.9fr 1fr 0.9fr 0.3fr;
  gap: 6px;
  align-items: center;
  padding: 6px 8px;
}

.line-item-name {
  display: flex;
  flex-direction: column;
  gap: 1px;
  color: #2f2d27;
  font-size: 12px;

  small {
    color: #b45309;
    font-size: 10px;
  }
}

.line-base {
  color: #6d675c;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.inbound-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;

  .el-input {
    flex: 1 1 320px;
  }
}

.search-results {
  display: flex;
  flex-direction: column;
  margin-top: -4px;
  overflow: hidden;
  background: #fffdf9;
  border: 1px solid #efe8dd;
  border-radius: 10px;

  button {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 7px 10px;
    color: #2f2d27;
    background: transparent;
    border: 0;
    cursor: pointer;
    text-align: left;

    small {
      color: #9a9285;
    }

    em {
      color: #9a9285;
      font-style: normal;
      font-size: 11px;
    }

    &:hover,
    &.is-active {
      background: #edf7f1;
    }
  }
}

.paste-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 8px 0;

  span {
    color: #9a9285;
    font-size: 11px;
  }
}

.paste-summary {
  margin-right: auto;
  color: #9a9285;
  font-size: 12px;
}

.line-head {
  color: #9a9285;
  font-size: 11px;
  background: #fbf9f5;
}

.line-row + .line-row {
  border-top: 1px solid #f1ebe1;
}

.drawer-foot {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  padding-top: 10px;
  border-top: 1px solid #f1ebe1;

  span {
    margin-right: auto;
    color: #9a9285;
    font-size: 12px;
  }
}

.order-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 10px;
  color: #6d675c;
  font-size: 12px;
}

.reversed-note {
  margin-top: 10px;
  color: #c45454;
  font-size: 12px;
}

.import-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}

.bulk-shell {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
}

.bulk-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;

  .grow {
    flex: 1 1 220px;
  }
}

.bulk-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;

  .el-input {
    width: 240px;
  }

  .bulk-summary {
    margin-left: auto;
    color: #9a9285;
    font-size: 12px;
  }
}

.bulk-foot {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  padding-top: 10px;
  border-top: 1px solid #f1ebe1;

  span {
    margin-right: auto;
    color: #9a9285;
    font-size: 11px;
  }
}

:deep(.bulk-qty-head) {
  color: #fff !important;
  background: #347d68 !important;
}

:deep(.bulk-qty-cell) {
  background: #f2faf5;
}

.bulk-qty {
  width: 92px;
}

.bulk-qty-unit {
  margin-left: 4px;
  color: #6d675c;
  font-size: 11px;
}

.danger {
  color: #c45454;
}

.item-form {
  .form-row {
    align-items: flex-start;
  }

  .el-form-item {
    margin-bottom: 10px;
  }
}
</style>
