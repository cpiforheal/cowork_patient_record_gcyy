<template>
  <div class="inventory-page">
    <section class="inventory-hero">
      <div>
        <span>{{ currentTabProfile.kicker }}</span>
        <h1>{{ currentTabProfile.title }}</h1>
        <p>{{ currentTabProfile.desc }}</p>
      </div>
      <div class="hero-actions">
        <el-button :icon="Refresh" :loading="loading" @click="loadInventory">刷新</el-button>
        <el-button
          v-for="action in currentTabActions"
          :key="action.label"
          v-bind="action.buttonProps"
          @click="runTabAction(action.action)"
        >
          {{ action.label }}
        </el-button>
      </div>
    </section>

    <section class="task-strip">
      <div class="task-card">
        <span>{{ currentTabProfile.taskLabel }}</span>
        <strong>{{ currentTabProfile.taskTitle }}</strong>
        <small>{{ currentTabProfile.taskDesc }}</small>
      </div>
      <button
        v-for="stat in currentTabStats"
        :key="stat.label"
        class="summary-card"
        :class="stat.tone"
        @click="goTab(stat.tab || activeTab)"
      >
        <span>{{ stat.label }}</span>
        <strong>{{ stat.value }}</strong>
        <small>{{ stat.desc }}</small>
      </button>
    </section>

    <nav class="module-switcher" aria-label="进销存二级功能">
      <button
        v-for="item in visibleTabNavItems"
        :key="item.tab"
        :class="{ active: activeTab === item.tab }"
        @click="goTab(item.tab)"
      >
        <span>{{ item.title }}</span>
        <small>{{ item.desc }}</small>
      </button>
    </nav>

    <div v-loading="loading" class="inventory-workspace" element-loading-text="正在同步库存...">
      <transition name="inventory-fade" mode="out-in">
        <div :key="activeTab" class="workspace-pane">
          <template v-if="activeTab === 'overview'">
            <div class="overview-grid">
              <section class="panel quick-actions-panel">
                <div class="panel-head">
                  <div>
                    <h2>快捷处理</h2>
                    <p>常用动作集中在这里，减少翻页。</p>
                  </div>
                </div>
                <div class="workflow-steps">
                  <button
                    v-for="(step, index) in visibleWorkflowSteps"
                    :key="step.title"
                    class="workflow-step"
                    @click="handleWorkflowStep(step.action)"
                  >
                    <span class="step-index">{{ index + 1 }}</span>
                    <strong>{{ step.title }}</strong>
                    <small>{{ step.desc }}</small>
                  </button>
                </div>
              </section>

              <section class="panel">
                <div class="panel-head">
                  <div>
                    <h2>今日待处理</h2>
                    <p>审核、发放、签收优先处理。</p>
                  </div>
                </div>
                <div v-if="todoRows.length" class="todo-list">
                  <button v-for="row in todoRows" :key="row.id" class="todo-card" @click="openTodo(row)">
                    <el-tag :type="row.level" effect="plain">{{ row.type }}</el-tag>
                    <div>
                      <strong>{{ row.title }}</strong>
                      <small>{{ row.desc }}</small>
                    </div>
                    <span>{{ row.actionLabel }}</span>
                  </button>
                </div>
                <el-empty v-else description="暂无待处理事项" :image-size="72" />
              </section>
            </div>

            <section class="panel role-entry-panel">
              <div class="panel-head">
                <div>
                  <h2>岗位入口</h2>
                  <p>按岗位进入对应工作。</p>
                </div>
              </div>
              <div class="role-entry-grid">
                <button v-for="card in visibleRoleEntryCards" :key="card.title" class="role-entry-card" @click="goTab(card.tab)">
                  <span>{{ card.scene }}</span>
                  <strong>{{ card.title }}</strong>
                  <small>{{ card.desc }}</small>
                </button>
              </div>
            </section>

            <section class="panel">
              <div class="panel-head">
                <div>
                  <h2>风险清单</h2>
                  <p>红色先处理，黄色今日跟进。</p>
                </div>
                <el-button
                  v-if="hasInventoryAuth('inventory:export')"
                  plain
                  :icon="Download"
                  @click="exportCsv(riskRows, 'inventory-risk.csv')"
                >
                  导出
                </el-button>
              </div>
              <el-table :data="riskRows" border>
                <el-table-column prop="type" label="类型" width="120">
                  <template #default="{ row }">
                    <el-tag :type="row.level" effect="plain">{{ row.type }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="subject" label="对象" min-width="160" />
                <el-table-column prop="department" label="科室" width="120" />
                <el-table-column prop="status" label="当前情况" min-width="220" />
                <el-table-column prop="suggestion" label="建议动作" min-width="240" />
                <el-table-column label="处理" width="110" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="activeTab = row.tab">查看</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </section>
          </template>

          <template v-else-if="activeTab === 'executive'">
            <section class="panel executive-signal" :class="executiveSignal.level">
              <div>
                <span>今日红绿灯</span>
                <strong>{{ executiveSignal.title }}</strong>
                <small>{{ executiveSignal.desc }}</small>
              </div>
              <div class="signal-counts">
                <span>紧急 {{ executiveUrgentCount }}</span>
                <span>关注 {{ executiveAttentionCount }}</span>
              </div>
            </section>

            <section class="executive-kpis">
              <button
                v-for="item in executiveKpis"
                :key="item.label"
                class="executive-kpi"
                :class="item.tone"
                @click="goTab(item.label.includes('闭环') ? 'requests' : 'stock')"
              >
                <span>{{ item.label }}</span>
                <strong>{{ item.value }}</strong>
                <small>{{ item.desc }}</small>
              </button>
            </section>

            <div class="executive-grid">
              <section class="panel">
                <div class="panel-head compact">
                  <div>
                    <h2>科室消耗 TOP</h2>
                    <p>基于周消耗记录汇总。</p>
                  </div>
                </div>
                <div v-if="departmentConsumptionTop.length" class="bar-list">
                  <div v-for="row in departmentConsumptionTop" :key="row.department" class="bar-row">
                    <span>{{ row.department }}</span>
                    <div><i :style="{ width: `${Math.max(8, (row.value / maxDepartmentConsumption) * 100)}%` }"></i></div>
                    <strong>{{ row.value }}</strong>
                  </div>
                </div>
                <el-empty v-else description="暂无周消耗数据" :image-size="72" />
              </section>

              <section class="panel">
                <div class="panel-head compact">
                  <div>
                    <h2>待签字事项</h2>
                    <p>审核、发放、签收未完成。</p>
                  </div>
                </div>
                <div v-if="todoRows.length" class="todo-list compact-list">
                  <button v-for="row in todoRows.slice(0, 6)" :key="row.id" class="todo-card" @click="openTodo(row)">
                    <el-tag :type="row.level" effect="plain">{{ row.type }}</el-tag>
                    <div>
                      <strong>{{ row.title }}</strong>
                      <small>{{ row.desc }}</small>
                    </div>
                    <span>{{ row.actionLabel }}</span>
                  </button>
                </div>
                <el-empty v-else description="暂无待签字事项" :image-size="72" />
              </section>
            </div>

            <div class="executive-grid">
              <section class="panel">
                <div class="panel-head compact">
                  <div>
                    <h2>风险明细</h2>
                    <p>仅保留需要跟进的事项。</p>
                  </div>
                </div>
                <el-table :data="riskRows.slice(0, 8)" border>
                  <el-table-column prop="type" label="类型" width="110">
                    <template #default="{ row }">
                      <el-tag :type="row.level" effect="plain">{{ row.type }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="subject" label="对象" min-width="150" />
                  <el-table-column prop="status" label="情况" min-width="220" />
                  <el-table-column label="处理" width="90">
                    <template #default="{ row }">
                      <el-button link type="primary" @click="goTab(row.tab)">查看</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </section>

              <section class="panel">
                <div class="panel-head compact">
                  <div>
                    <h2>周转慢清单</h2>
                    <p>有库存但近期出库少的批次。</p>
                  </div>
                </div>
                <el-table :data="staleBatchRows" border>
                  <el-table-column prop="itemName" label="物资" min-width="140" />
                  <el-table-column prop="quantity" label="库存" width="90" />
                  <el-table-column prop="expiryDate" label="效期" width="120" />
                  <el-table-column prop="lastIssueAt" label="最近出库" min-width="130" />
                </el-table>
              </section>
            </div>
          </template>

          <template v-else-if="activeTab === 'stock'">
            <div class="pane-grid">
              <section class="panel">
                <div class="panel-head">
                  <div>
                    <h2>当前库存</h2>
                    <p>按物资汇总所有批次库存，低库存和临期会突出显示。</p>
                  </div>
                  <el-button
                    v-if="hasInventoryAuth('inventory:export')"
                    type="primary"
                    plain
                    :icon="Download"
                    @click="exportCsv(stockRows, 'inventory-stock.csv')"
                  >
                    导出
                  </el-button>
                </div>
                <div class="table-toolbar">
                  <el-input v-model="stockFilters.keyword" clearable placeholder="搜索物资、规格、位置" />
                  <el-select v-model="stockFilters.category" clearable placeholder="分类">
                    <el-option v-for="item in categoryFilterOptions" :key="item" :label="item" :value="item" />
                  </el-select>
                  <el-select v-model="stockFilters.status" clearable placeholder="状态">
                    <el-option label="低库存" value="low" />
                    <el-option label="敏感物资" value="sensitive" />
                  </el-select>
                </div>
                <el-table :data="filteredStockRows" border height="420">
                  <el-table-column prop="name" label="物资" min-width="150" />
                  <el-table-column prop="category" label="分类" width="110" />
                  <el-table-column prop="spec" label="规格" min-width="130" />
                  <el-table-column prop="stock" label="库存" width="110">
                    <template #default="{ row }">
                      <strong :class="{ danger: row.lowStock }">{{ row.stock }} {{ row.unit }}</strong>
                    </template>
                  </el-table-column>
                  <el-table-column prop="lowStockThreshold" label="预警线" width="100" />
                  <el-table-column prop="location" label="存放位置" width="130" />
                  <el-table-column label="状态" width="160">
                    <template #default="{ row }">
                      <el-tag v-if="row.lowStock" type="danger" effect="plain">低库存</el-tag>
                      <el-tag v-else type="success" effect="plain">正常</el-tag>
                      <el-tag v-if="row.sensitive" class="ml6" type="warning" effect="plain">敏感</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" width="180" fixed="right">
                    <template #default="{ row }">
                      <el-button
                        v-if="hasInventoryAuth('inventory:issue')"
                        link
                        type="primary"
                        @click="openInboundDialog(row.item)"
                      >
                        入库
                      </el-button>
                      <el-button v-if="hasInventoryAuth('inventory:issue')" link @click="openItemDialog(row.item)">
                        编辑
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </section>

              <section class="panel">
                <div class="panel-head">
                  <div>
                    <h2>批次与效期</h2>
                    <p>用于 P2 批号、效期、临期追溯。</p>
                  </div>
                </div>
                <el-table :data="batchRows" border height="420">
                  <el-table-column prop="itemName" label="物资" min-width="140" />
                  <el-table-column prop="batchNo" label="批号" width="120" />
                  <el-table-column prop="quantity" label="数量" width="90" />
                  <el-table-column prop="expiryDate" label="有效期" width="120" />
                  <el-table-column prop="location" label="位置" width="120" />
                  <el-table-column label="提醒" width="110">
                    <template #default="{ row }">
                      <el-tag v-if="row.expired" type="danger" effect="plain">已过期</el-tag>
                      <el-tag v-else-if="row.expirySoon" type="warning" effect="plain">临期</el-tag>
                      <span v-else>-</span>
                    </template>
                  </el-table-column>
                </el-table>
              </section>
            </div>
          </template>

          <template v-else-if="activeTab === 'items'">
            <section class="panel">
              <div class="panel-head">
                <div>
                  <h2>物资字典</h2>
                  <p>统一名称、规格、单位、批号效期要求，后续申领与统计都从这里选择。</p>
                </div>
                <el-button v-if="hasInventoryAuth('inventory:issue')" type="primary" :icon="Plus" @click="openItemDialog()">
                  新增物资
                </el-button>
              </div>
              <div class="table-toolbar">
                <el-input v-model="itemFilters.keyword" clearable placeholder="搜索名称、规格、位置" />
                <el-select v-model="itemFilters.category" clearable placeholder="分类">
                  <el-option v-for="item in categoryFilterOptions" :key="item" :label="item" :value="item" />
                </el-select>
              </div>
              <el-table :data="filteredItemRows" border>
                <el-table-column prop="name" label="名称" min-width="160" />
                <el-table-column prop="category" label="分类" width="120" />
                <el-table-column prop="spec" label="规格" min-width="140" />
                <el-table-column prop="unit" label="单位" width="90" />
                <el-table-column prop="lowStockThreshold" label="预警线" width="90" />
                <el-table-column label="管理要求" min-width="180">
                  <template #default="{ row }">
                    <el-tag v-if="row.batchRequired" effect="plain">批号</el-tag>
                    <el-tag v-if="row.expiryRequired" class="ml6" effect="plain">效期</el-tag>
                    <el-tag v-if="row.sensitive" class="ml6" type="warning" effect="plain">敏感</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="location" label="默认位置" width="140" />
                <el-table-column label="操作" width="90" fixed="right">
                  <template #default="{ row }">
                    <el-button v-if="hasInventoryAuth('inventory:issue')" link type="primary" @click="openItemDialog(row)">
                      编辑
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </section>
          </template>

          <template v-else-if="activeTab === 'requests'">
            <section class="panel">
              <div class="panel-head">
                <div>
                  <h2>科室申领闭环</h2>
                  <p>提交、审核、发放、签收全部留痕，避免口头领用和纸质单据断链。</p>
                </div>
                <el-button v-if="hasInventoryAuth('inventory:request')" type="primary" :icon="Plus" @click="openRequestDialog()">
                  新增申领
                </el-button>
              </div>
              <div class="table-toolbar wide">
                <el-input v-model="requestFilters.keyword" clearable placeholder="搜索物资、科室、理由、负责人" />
                <el-select v-model="requestFilters.status" clearable placeholder="状态">
                  <el-option label="待审核" value="pending" />
                  <el-option label="待发放" value="approved" />
                  <el-option label="部分发放" value="partially_issued" />
                  <el-option label="待签收" value="issued" />
                  <el-option label="已签收" value="received" />
                  <el-option label="已驳回" value="rejected" />
                  <el-option label="已撤销" value="cancelled" />
                  <el-option label="已作废" value="void" />
                </el-select>
                <el-select v-model="requestFilters.department" clearable filterable placeholder="科室">
                  <el-option v-for="item in departmentOptions" :key="item" :label="item" :value="item" />
                </el-select>
                <el-date-picker
                  v-model="requestFilters.dateRange"
                  value-format="YYYY-MM-DD"
                  type="daterange"
                  start-placeholder="开始日期"
                  end-placeholder="结束日期"
                />
              </div>
              <el-table :data="filteredRequestRows" border :row-class-name="requestRowClassName">
                <el-table-column prop="createdAt" label="申请时间" width="160" />
                <el-table-column prop="department" label="科室" width="120" />
                <el-table-column prop="itemSummary" label="物资明细" min-width="220">
                  <template #default="{ row }">
                    <div class="request-line-summary">
                      <strong>{{ row.itemSummary || row.itemName }}</strong>
                      <small>{{ row.itemCount || row.lines?.length || 1 }} 项，合计 {{ row.quantity }}</small>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="发放" width="120">
                  <template #default="{ row }">{{ row.issuedQuantity || 0 }} / {{ row.quantity }}</template>
                </el-table-column>
                <el-table-column prop="reason" label="理由" min-width="220" />
                <el-table-column prop="owner" label="负责人" width="110" />
                <el-table-column label="闭环进度" min-width="250">
                  <template #default="{ row }">
                    <el-steps
                      class="request-steps"
                      :active="requestStepActive(row.status)"
                      finish-status="success"
                      process-status="process"
                      simple
                    >
                      <el-step title="审核" />
                      <el-step title="发放" />
                      <el-step title="签收" />
                    </el-steps>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="120">
                  <template #default="{ row }">
                    <el-tag :type="requestStatusMeta(row.status).type" effect="plain">{{
                      requestStatusMeta(row.status).label
                    }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="230" fixed="right">
                  <template #default="{ row }">
                    <el-button
                      v-if="row.status === 'pending' && hasInventoryAuth('inventory:approve')"
                      link
                      type="primary"
                      @click="approveRequest(row)"
                    >
                      审核
                    </el-button>
                    <el-button
                      v-if="row.status === 'approved' && hasInventoryAuth('inventory:issue')"
                      link
                      type="primary"
                      @click="openIssueDialog(row)"
                    >
                      发放
                    </el-button>
                    <el-button
                      v-if="row.status === 'partially_issued' && hasInventoryAuth('inventory:issue')"
                      link
                      type="warning"
                      @click="openIssueDialog(row)"
                    >
                      继续发
                    </el-button>
                    <el-button
                      v-if="row.status === 'issued' && hasInventoryAuth('inventory:receive')"
                      link
                      type="success"
                      @click="receiveRequest(row)"
                    >
                      签收
                    </el-button>
                    <el-button
                      v-if="row.status === 'pending' && hasInventoryAuth('inventory:approve')"
                      link
                      type="warning"
                      @click="rejectRequest(row)"
                    >
                      驳回
                    </el-button>
                    <el-button
                      v-if="row.status === 'pending' && hasInventoryAuth('inventory:request')"
                      link
                      type="info"
                      @click="cancelRequest(row)"
                    >
                      撤销
                    </el-button>
                    <el-button
                      v-if="['pending', 'approved'].includes(row.status) && hasInventoryAuth('inventory:approve')"
                      link
                      type="danger"
                      @click="voidRequest(row)"
                    >
                      作废
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </section>
          </template>

          <template v-else-if="activeTab === 'weekly'">
            <section class="panel">
              <div class="panel-head">
                <div>
                  <h2>科室周消耗</h2>
                  <p>记录本周实际消耗、剩余数量和下周预计领用，用于 P1 趋势与异常管理。</p>
                </div>
                <el-button v-if="hasInventoryAuth('inventory:request')" type="primary" :icon="Plus" @click="openWeeklyDialog()">
                  新增周消耗
                </el-button>
              </div>
              <el-table :data="weeklyRows" border>
                <el-table-column prop="weekNo" label="周次" width="130" />
                <el-table-column prop="department" label="科室" width="120" />
                <el-table-column prop="itemName" label="物资" min-width="150" />
                <el-table-column prop="consumedQuantity" label="本周消耗" width="110" />
                <el-table-column prop="remainingQuantity" label="科室剩余" width="110" />
                <el-table-column prop="nextWeekQuantity" label="下周预计" width="110" />
                <el-table-column prop="owner" label="负责人" width="110" />
                <el-table-column prop="abnormalReason" label="异常说明" min-width="220" />
                <el-table-column prop="confirmedAt" label="确认时间" width="160" />
              </el-table>
            </section>
          </template>

          <template v-else-if="activeTab === 'controls'">
            <div class="control-grid">
              <section class="panel">
                <div class="panel-head">
                  <div>
                    <h2>盘点差异</h2>
                    <p>账实不一致必须记录原因，形成 P2 盘点追溯。</p>
                  </div>
                  <el-button v-if="hasInventoryAuth('inventory:count')" type="primary" :icon="Plus" @click="openCountDialog()">
                    新增盘点
                  </el-button>
                </div>
                <el-table :data="countRows" border height="360">
                  <el-table-column prop="countedAt" label="时间" width="160" />
                  <el-table-column prop="itemName" label="物资" min-width="150" />
                  <el-table-column prop="bookQuantity" label="账面" width="90" />
                  <el-table-column prop="actualQuantity" label="实盘" width="90" />
                  <el-table-column prop="differenceQuantity" label="差异" width="90" />
                  <el-table-column prop="operator" label="盘点人" width="110" />
                  <el-table-column prop="reason" label="原因" min-width="180" />
                </el-table>
              </section>

              <section class="panel quick-control">
                <div class="panel-head">
                  <div>
                    <h2>退回 / 报废</h2>
                    <p>用于记录科室退回、库存报废和损耗说明。</p>
                  </div>
                </div>
                <el-form ref="returnFormRef" :model="returnForm" :rules="returnFormRules" label-width="96px" status-icon>
                  <el-form-item label="类型">
                    <el-segmented v-model="returnForm.type" :options="availableReturnTypeOptions" />
                  </el-form-item>
                  <el-form-item label="物资" prop="itemId">
                    <el-select v-model="returnForm.itemId" filterable placeholder="请选择物资" @change="returnForm.batchId = ''">
                      <el-option v-for="item in db.items" :key="item.id" :label="item.name" :value="item.id" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="批次">
                    <el-select v-model="returnForm.batchId" clearable filterable placeholder="自动选择或指定批次">
                      <el-option
                        v-for="batch in batchesForItem(returnForm.itemId)"
                        :key="batch.id"
                        :label="batchLabel(batch)"
                        :value="batch.id"
                      />
                    </el-select>
                    <div class="form-hint">退回时可不选批次，系统会自动落到可用批次；没有库存批次时会补录一条退回批次。</div>
                  </el-form-item>
                  <el-form-item label="数量" prop="quantity">
                    <el-input-number v-model="returnForm.quantity" :min="0" :precision="2" />
                  </el-form-item>
                  <el-form-item label="科室">
                    <el-select v-model="returnForm.department" filterable allow-create placeholder="请选择或输入科室">
                      <el-option v-for="item in departmentOptions" :key="item" :label="item" :value="item" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="原因" prop="reason">
                    <el-input
                      v-model="returnForm.reason"
                      type="textarea"
                      :rows="3"
                      placeholder="例如：科室未使用退回 / 临期报废 / 破损损耗"
                    />
                  </el-form-item>
                  <el-form-item>
                    <el-button v-if="canSubmitReturnOrScrap" type="primary" :loading="saving" @click="submitReturnOrScrap">
                      保存变更
                    </el-button>
                  </el-form-item>
                </el-form>
              </section>
            </div>
          </template>

          <template v-else-if="activeTab === 'trace'">
            <section class="panel">
              <div class="panel-head">
                <div>
                  <h2>库存流水与操作日志</h2>
                  <p>按时间倒序记录所有库存变化和关键操作。</p>
                </div>
                <el-button
                  v-if="hasInventoryAuth('inventory:export')"
                  plain
                  :icon="Download"
                  @click="exportCsv(traceRows, 'inventory-trace.csv')"
                >
                  导出
                </el-button>
              </div>
              <div class="table-toolbar wide">
                <el-input v-model="traceFilters.keyword" clearable placeholder="搜索物资、科室、经办人、原因" />
                <el-select v-model="traceFilters.type" clearable placeholder="类型">
                  <el-option label="入库" value="inbound" />
                  <el-option label="发放" value="issue" />
                  <el-option label="退回" value="return" />
                  <el-option label="报废" value="scrap" />
                  <el-option label="盘点" value="count" />
                </el-select>
                <el-select v-model="traceFilters.department" clearable filterable placeholder="科室">
                  <el-option v-for="item in departmentOptions" :key="item" :label="item" :value="item" />
                </el-select>
                <el-date-picker
                  v-model="traceFilters.dateRange"
                  value-format="YYYY-MM-DD"
                  type="daterange"
                  start-placeholder="开始日期"
                  end-placeholder="结束日期"
                />
              </div>
              <el-table :data="filteredTraceRows" border>
                <el-table-column prop="createdAt" label="时间" width="160" />
                <el-table-column prop="typeLabel" label="类型" width="110" />
                <el-table-column prop="itemName" label="物资" min-width="150" />
                <el-table-column prop="quantity" label="数量变化" width="110" />
                <el-table-column prop="department" label="科室" width="120" />
                <el-table-column prop="operator" label="经办人" width="120" />
                <el-table-column prop="reason" label="原因/摘要" min-width="260" />
              </el-table>
            </section>
          </template>
        </div>
      </transition>
    </div>

    <el-dialog v-model="itemDialogVisible" :title="itemForm.id ? '编辑物资' : '新增物资'" width="640px" destroy-on-close>
      <el-form ref="itemFormRef" :model="itemForm" :rules="itemFormRules" label-width="108px" status-icon>
        <el-form-item label="物资名称" prop="name">
          <el-input v-model="itemForm.name" placeholder="例如 一次性手套" />
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-select v-model="itemForm.category" filterable allow-create placeholder="请选择或输入分类">
            <el-option v-for="item in categoryOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="itemForm.spec" placeholder="例如 M号 / 100只/盒" />
        </el-form-item>
        <el-form-item label="单位" prop="unit">
          <el-select v-model="itemForm.unit" filterable allow-create placeholder="请选择或输入单位">
            <el-option v-for="item in unitOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="预警线">
          <el-input-number v-model="itemForm.lowStockThreshold" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="默认位置">
          <el-input v-model="itemForm.location" placeholder="例如 一楼库房 A 架" />
        </el-form-item>
        <el-form-item label="管理要求">
          <el-checkbox v-model="itemForm.batchRequired">需要批号</el-checkbox>
          <el-checkbox v-model="itemForm.expiryRequired">需要效期</el-checkbox>
          <el-checkbox v-model="itemForm.sensitive">敏感物资</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveItem">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="inboundDialogVisible" title="物资入库" width="620px" destroy-on-close>
      <el-form ref="inboundFormRef" :model="inboundForm" :rules="inboundFormRules" label-width="100px" status-icon>
        <el-form-item label="物资" prop="itemId">
          <el-select v-model="inboundForm.itemId" filterable placeholder="请选择物资">
            <el-option v-for="item in db.items" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量" prop="quantity">
          <el-input-number v-model="inboundForm.quantity" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="批号">
          <el-input v-model="inboundForm.batchNo" placeholder="有批号则填写" />
        </el-form-item>
        <el-form-item label="有效期">
          <el-date-picker v-model="inboundForm.expiryDate" value-format="YYYY-MM-DD" type="date" placeholder="选择有效期" />
        </el-form-item>
        <el-form-item label="存放位置">
          <el-input v-model="inboundForm.location" placeholder="默认带出物资位置，可修改" />
        </el-form-item>
        <el-form-item label="来源说明">
          <el-input v-model="inboundForm.source" type="textarea" :rows="3" placeholder="例如 月度采购入库 / 上级调拨" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inboundDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveInbound">保存入库</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="requestDialogVisible" title="新增科室申领" width="760px" destroy-on-close>
      <el-form ref="requestFormRef" :model="requestForm" :rules="requestFormRules" label-width="112px" status-icon>
        <el-form-item label="申领科室" prop="department">
          <el-select v-model="requestForm.department" filterable allow-create placeholder="请选择或输入科室">
            <el-option v-for="item in departmentOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="申领明细" required>
          <div class="request-lines-editor">
            <div v-for="(line, index) in requestForm.lines" :key="line.localId" class="request-line-editor">
              <el-select v-model="line.itemId" filterable placeholder="选择物资">
                <el-option v-for="item in db.items" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
              <el-input-number v-model="line.quantity" :min="0" :precision="2" />
              <span>{{ itemUnit(line.itemId) }}</span>
              <el-button link type="danger" :disabled="requestForm.lines.length === 1" @click="removeRequestLine(index)">
                删除
              </el-button>
            </div>
            <el-button plain :icon="Plus" @click="addRequestLine()">添加一项物资</el-button>
          </div>
        </el-form-item>
        <el-form-item label="申请人">
          <el-input v-model="requestForm.applicant" />
        </el-form-item>
        <el-form-item label="负责人" prop="owner">
          <el-input v-model="requestForm.owner" />
        </el-form-item>
        <el-form-item label="预计使用周">
          <el-date-picker v-model="requestForm.expectedUseWeek" value-format="YYYY-[W]ww" type="week" format="YYYY 第 ww 周" />
        </el-form-item>
        <el-form-item label="申请理由" prop="reason">
          <el-input v-model="requestForm.reason" type="textarea" :rows="3" placeholder="例如 门诊量增加，下周预计消耗上升" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="requestDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRequest">提交申领</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="issueDialogVisible" title="发放物资" width="680px" destroy-on-close>
      <el-form ref="issueFormRef" :model="issueForm" :rules="issueFormRules" label-width="100px" status-icon>
        <el-form-item label="指定批次">
          <el-select v-model="issueForm.batchId" clearable filterable placeholder="不选则系统按效期自动拆批">
            <el-option v-for="batch in activeRequestBatches" :key="batch.id" :label="batchLabel(batch)" :value="batch.id" />
          </el-select>
          <div class="form-hint">建议不指定批次，系统会优先使用更早到期的库存；单批不足时会自动跨批次发放。</div>
        </el-form-item>
        <el-form-item label="发放明细" required>
          <div class="issue-lines-editor">
            <div v-for="line in issueForm.lines" :key="line.id" class="issue-line-editor">
              <div>
                <strong>{{ itemName(line.itemId) }}</strong>
                <small>剩余 {{ line.remaining }} {{ itemUnit(line.itemId) }}</small>
              </div>
              <el-input-number v-model="line.issuedQuantity" :min="0" :max="line.remaining" :precision="2" />
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="issueRequest">确认发放</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="weeklyDialogVisible" title="新增周消耗确认" width="640px" destroy-on-close>
      <el-form ref="weeklyFormRef" :model="weeklyForm" :rules="weeklyFormRules" label-width="112px" status-icon>
        <el-form-item label="周次" prop="weekNo">
          <el-date-picker v-model="weeklyForm.weekNo" value-format="YYYY-[W]ww" type="week" format="YYYY 第 ww 周" />
        </el-form-item>
        <el-form-item label="科室" prop="department">
          <el-select v-model="weeklyForm.department" filterable allow-create placeholder="请选择或输入科室">
            <el-option v-for="item in departmentOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="物资" prop="itemId">
          <el-select v-model="weeklyForm.itemId" filterable placeholder="请选择物资">
            <el-option v-for="item in db.items" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <div v-if="selectedWeeklyItem" class="weekly-assist">
          <div>
            <span>当前库存</span>
            <strong>{{ weeklyItemStock }} {{ selectedWeeklyItem.unit }}</strong>
          </div>
          <div>
            <span>本周已发放</span>
            <strong>{{ weeklyIssuedThisWeek }} {{ selectedWeeklyItem.unit }}</strong>
          </div>
          <div>
            <span>上次剩余</span>
            <strong>{{ weeklyLastRecord ? `${weeklyLastRecord.remainingQuantity} ${selectedWeeklyItem.unit}` : "暂无" }}</strong>
          </div>
          <el-button size="small" plain @click="applyWeeklySuggestion">带入建议</el-button>
        </div>
        <el-form-item label="本周消耗">
          <el-input-number v-model="weeklyForm.consumedQuantity" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="科室剩余">
          <el-input-number v-model="weeklyForm.remainingQuantity" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="下周预计">
          <el-input-number v-model="weeklyForm.nextWeekQuantity" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="weeklyForm.owner" />
        </el-form-item>
        <el-form-item label="异常说明">
          <el-input v-model="weeklyForm.abnormalReason" type="textarea" :rows="3" placeholder="用量明显波动时填写原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="weeklyDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveWeekly">确认消耗</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="countDialogVisible" title="库存盘点" width="580px" destroy-on-close>
      <el-form ref="countFormRef" :model="countForm" :rules="countFormRules" label-width="100px" status-icon>
        <el-form-item label="物资" prop="itemId">
          <el-select v-model="countForm.itemId" filterable placeholder="请选择物资" @change="countForm.batchId = ''">
            <el-option v-for="item in db.items" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="批次">
          <el-select v-model="countForm.batchId" clearable filterable placeholder="可不选，系统自动选择或补录">
            <el-option
              v-for="batch in batchesForItem(countForm.itemId)"
              :key="batch.id"
              :label="batchLabel(batch)"
              :value="batch.id"
            />
          </el-select>
          <div class="form-hint">首次盘点没有批次时可直接填写实盘数量，系统会自动生成盘点补录批次。</div>
        </el-form-item>
        <el-form-item label="实盘数量" prop="actualQuantity">
          <el-input-number v-model="countForm.actualQuantity" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="差异原因">
          <el-input v-model="countForm.reason" type="textarea" :rows="3" placeholder="有差异时必须说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="countDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCount">保存盘点</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="inventoryManage">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from "element-plus";
import { Download, Plus, Refresh } from "@element-plus/icons-vue";
import { useRoute, useRouter } from "vue-router";
import {
  approveInventoryRequestApi,
  cancelInventoryRequestApi,
  countInventoryApi,
  createInventoryRequestApi,
  getInventoryDbApi,
  inboundInventoryApi,
  issueInventoryRequestApi,
  receiveInventoryRequestApi,
  rejectInventoryRequestApi,
  returnOrScrapInventoryApi,
  saveInventoryItemApi,
  saveWeeklyConsumptionApi,
  voidInventoryRequestApi,
  type InventoryBatch,
  type InventoryDb,
  type InventoryItem,
  type InventoryRequestLine,
  type InventoryRequest,
  type ReturnOrScrapParams
} from "@/api/modules/inventory";
import { useAuthStore } from "@/stores/modules/auth";
import { useUserStore } from "@/stores/modules/user";
import { exportCsv } from "./utils";

const emptyDb = (): InventoryDb => ({
  items: [],
  batches: [],
  requests: [],
  weeklyConsumptions: [],
  counts: [],
  movements: [],
  auditLogs: [],
  summary: {
    itemCount: 0,
    batchCount: 0,
    pendingRequestCount: 0,
    approvedRequestCount: 0,
    lowStockCount: 0,
    expirySoonCount: 0,
    movementCount: 0
  }
});

const userStore = useUserStore();
const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const db = ref<InventoryDb>(emptyDb());
const loading = ref(false);
const saving = ref(false);
const activeTab = ref("overview");

const itemDialogVisible = ref(false);
const inboundDialogVisible = ref(false);
const requestDialogVisible = ref(false);
const issueDialogVisible = ref(false);
const weeklyDialogVisible = ref(false);
const countDialogVisible = ref(false);
const activeRequest = ref<InventoryRequest>();
const highlightedRequestId = ref("");

const itemFormRef = ref<FormInstance>();
const inboundFormRef = ref<FormInstance>();
const requestFormRef = ref<FormInstance>();
const issueFormRef = ref<FormInstance>();
const weeklyFormRef = ref<FormInstance>();
const returnFormRef = ref<FormInstance>();
const countFormRef = ref<FormInstance>();

const itemForm = reactive<Partial<InventoryItem> & { operator?: string }>({});
const inboundForm = reactive({
  itemId: "",
  quantity: 0,
  batchNo: "",
  expiryDate: "",
  location: "",
  source: "",
  operator: ""
});
const requestForm = reactive({
  lines: [] as { localId: string; itemId: string; quantity: number }[],
  department: "",
  applicant: "",
  owner: "",
  reason: "",
  expectedUseWeek: ""
});
const issueForm = reactive({
  id: "",
  batchId: "",
  lines: [] as { id: string; itemId: string; remaining: number; issuedQuantity: number }[],
  operator: ""
});
const weeklyForm = reactive({
  weekNo: "",
  department: "",
  itemId: "",
  consumedQuantity: 0,
  remainingQuantity: 0,
  nextWeekQuantity: 0,
  owner: "",
  abnormalReason: "",
  operator: ""
});
const returnForm = reactive<ReturnOrScrapParams>({
  type: "return",
  itemId: "",
  batchId: "",
  quantity: 0,
  department: "",
  operator: "",
  reason: ""
});
const countForm = reactive({
  itemId: "",
  batchId: "",
  actualQuantity: 0,
  operator: "",
  reason: ""
});

const stockFilters = reactive({
  keyword: "",
  category: "",
  status: ""
});
const itemFilters = reactive({
  keyword: "",
  category: ""
});
const requestFilters = reactive({
  status: "",
  department: "",
  keyword: "",
  dateRange: [] as string[]
});
const traceFilters = reactive({
  type: "",
  department: "",
  keyword: "",
  dateRange: [] as string[]
});

const operatorName = computed(() => userStore.userInfo.name || userStore.userInfo.department || "当前账号");
const currentDepartment = computed(() => userStore.userInfo.department || "");
const tabRoutePathMap: Record<string, string> = {
  overview: "/inventory/overview",
  executive: "/inventory/executive",
  requests: "/inventory/requests",
  stock: "/inventory/stock",
  items: "/inventory/items",
  weekly: "/inventory/weekly",
  controls: "/inventory/controls",
  trace: "/inventory/trace"
};
const tabRouteNameMap: Record<string, string> = {
  overview: "inventoryOverview",
  executive: "inventoryExecutive",
  requests: "inventoryRequests",
  stock: "inventoryStock",
  items: "inventoryItems",
  weekly: "inventoryWeekly",
  controls: "inventoryControls",
  trace: "inventoryTrace"
};
const routeTabMap: Record<string, string> = {
  "/inventory": "overview",
  "/inventory/manage": "overview",
  "/inventory/overview": "overview",
  "/inventory/executive": "executive",
  "/inventory/requests": "requests",
  "/inventory/stock": "stock",
  "/inventory/items": "items",
  "/inventory/weekly": "weekly",
  "/inventory/controls": "controls",
  "/inventory/trace": "trace"
};
const categoryOptions = ["医用耗材", "办公物资", "消毒用品", "检验用品", "护理用品", "低值易耗"];
const unitOptions = ["个", "盒", "包", "瓶", "支", "卷", "套", "箱"];
const returnTypeOptions = [
  { label: "退回", value: "return", auth: "inventory:receive" },
  { label: "报废", value: "scrap", auth: "inventory:count" }
];
const tabNavItems = [
  { tab: "overview", title: "主控台", desc: "待办与风险" },
  { tab: "executive", title: "领导驾驶舱", desc: "红绿灯与指标" },
  { tab: "requests", title: "申领审批", desc: "申请到签收" },
  { tab: "stock", title: "库存看板", desc: "批次与效期" },
  { tab: "items", title: "物资档案", desc: "字典与规则" },
  { tab: "weekly", title: "周消耗", desc: "用量与预计" },
  { tab: "controls", title: "盘点处置", desc: "退回与报废" },
  { tab: "trace", title: "追溯流水", desc: "导出与复核" }
] as const;
const workflowSteps = [
  { title: "建物资档案", desc: "统一名称、规格、单位和预警线", action: "item", auth: ["inventory:issue"] },
  { title: "入库形成库存", desc: "记录数量、批号、效期和来源", action: "inbound", auth: ["inventory:issue"] },
  { title: "科室提交申领", desc: "填写用途、数量、负责人和周期", action: "request", auth: ["inventory:request"] },
  { title: "审核与发放", desc: "负责人审核，仓库按批次发放", action: "requests", auth: ["inventory:approve", "inventory:issue"] },
  { title: "科室签收确认", desc: "发放后由领取人确认闭环", action: "requests", auth: ["inventory:receive"] },
  { title: "周消耗填报", desc: "记录本周使用和下周预计", action: "weekly", auth: ["inventory:request"] },
  { title: "盘点与追溯", desc: "差异、退回、报废全部留痕", action: "controls", auth: ["inventory:count"] }
] as const;

const roleEntryCards = [
  {
    scene: "科室人员",
    title: "我要申领或签收",
    desc: "提交领用，确认收货",
    tab: "requests",
    auth: ["inventory:request", "inventory:receive"]
  },
  {
    scene: "库管人员",
    title: "我要入库或发放",
    desc: "看库存，按单发放",
    tab: "stock",
    auth: ["inventory:issue"]
  },
  {
    scene: "科室负责人",
    title: "我要填周消耗",
    desc: "填本周用量和下周预计",
    tab: "weekly",
    auth: ["inventory:request", "inventory:count"]
  },
  {
    scene: "质控/领导",
    title: "我要看异常追溯",
    desc: "先看待办、风险和异常",
    tab: "overview",
    auth: ["inventory:export"]
  }
] as const;

const positiveNumberValidator = (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
  if (Number(value) > 0) return callback();
  callback(new Error("数量必须大于 0"));
};

const itemFormRules = reactive<FormRules>({
  name: [{ required: true, message: "请填写物资名称", trigger: "blur" }],
  category: [{ required: true, message: "请选择或填写分类", trigger: "change" }],
  unit: [{ required: true, message: "请选择单位", trigger: "change" }]
});
const inboundFormRules = reactive<FormRules>({
  itemId: [{ required: true, message: "请选择入库物资", trigger: "change" }],
  quantity: [{ validator: positiveNumberValidator, trigger: "change" }]
});
const requestFormRules = reactive<FormRules>({
  department: [{ required: true, message: "请选择申领科室", trigger: "change" }],
  owner: [{ required: true, message: "请填写负责人", trigger: "blur" }],
  reason: [{ required: true, message: "请填写申请理由", trigger: "blur" }]
});
const issueFormRules = reactive<FormRules>({});
const weeklyFormRules = reactive<FormRules>({
  weekNo: [{ required: true, message: "请选择周次", trigger: "change" }],
  department: [{ required: true, message: "请选择科室", trigger: "change" }],
  itemId: [{ required: true, message: "请选择物资", trigger: "change" }]
});
const returnFormRules = reactive<FormRules>({
  itemId: [{ required: true, message: "请选择物资", trigger: "change" }],
  quantity: [{ validator: positiveNumberValidator, trigger: "change" }],
  reason: [{ required: true, message: "请填写处理原因", trigger: "blur" }]
});
const countFormRules = reactive<FormRules>({
  itemId: [{ required: true, message: "请选择盘点物资", trigger: "change" }],
  actualQuantity: [{ required: true, message: "请填写实盘数量", trigger: "change" }]
});

type WorkflowAction = (typeof workflowSteps)[number]["action"];
type TabAction =
  | "item"
  | "inbound"
  | "request"
  | "weekly"
  | "count"
  | "exportRisk"
  | "exportStock"
  | "exportTrace"
  | "exportWeeklyReport";
type TagLevel = "primary" | "success" | "warning" | "danger" | "info";
type TodoRow = {
  id: string;
  type: string;
  level: TagLevel;
  title: string;
  desc: string;
  actionLabel: string;
  tab: string;
  action?: "approve" | "issue" | "receive";
  request?: InventoryRequest;
};
type TabStat = {
  label: string;
  value: string | number;
  desc: string;
  tone?: "warning" | "danger";
  tab?: string;
};

const tabProfiles = {
  overview: {
    kicker: "进销存管理 / 主控台",
    title: "今天需要处理什么",
    desc: "待办、风险、库存异常集中看。",
    taskLabel: "当前重点",
    taskTitle: "先处理红黄提醒",
    taskDesc: "不用先翻明细，异常和待办会自动靠前。"
  },
  executive: {
    kicker: "进销存管理 / 领导驾驶舱",
    title: "今天物资运行是否安全",
    desc: "用红绿灯、关键指标和科室消耗看清当前风险。",
    taskLabel: "当前结论",
    taskTitle: "先看红绿灯，再看待签字",
    taskDesc: "适合主任、质控和管理岗位快速复核。"
  },
  requests: {
    kicker: "进销存管理 / 科室申领审批",
    title: "申领单流转",
    desc: "提交、审核、发放、签收按顺序闭环。",
    taskLabel: "当前重点",
    taskTitle: "处理待审核、待发放、待签收",
    taskDesc: "每张单只做当前状态允许的动作。"
  },
  stock: {
    kicker: "进销存管理 / 库存与批次",
    title: "库存与批次",
    desc: "看数量、批号、效期和位置。",
    taskLabel: "当前重点",
    taskTitle: "先看低库存和临期",
    taskDesc: "入库时补齐批次、效期和位置。"
  },
  items: {
    kicker: "进销存管理 / 物资档案",
    title: "物资档案",
    desc: "统一名称、规格、单位和规则。",
    taskLabel: "当前重点",
    taskTitle: "先建档，再申领",
    taskDesc: "敏感、批号、效期、预警线提前定义。"
  },
  weekly: {
    kicker: "进销存管理 / 周消耗预计",
    title: "周消耗与预计",
    desc: "记录本周使用、剩余和下周预计。",
    taskLabel: "当前重点",
    taskTitle: "按周确认真实用量",
    taskDesc: "波动明显时补充原因。"
  },
  controls: {
    kicker: "进销存管理 / 盘点退回报废",
    title: "盘点与处置",
    desc: "记录差异、退回和报废。",
    taskLabel: "当前重点",
    taskTitle: "补齐原因和处理人",
    taskDesc: "每次处置都留下可复核记录。"
  },
  trace: {
    kicker: "进销存管理 / 追溯报表",
    title: "追溯流水",
    desc: "倒查入库、发放、退回、报废和盘点。",
    taskLabel: "当前重点",
    taskTitle: "按物资、科室、时间倒查",
    taskDesc: "检查和复核时直接导出。"
  }
} as const;

const itemMap = computed(() => new Map(db.value.items.map(item => [item.id, item])));
const itemName = (itemId?: string) => itemMap.value.get(itemId || "")?.name || itemId || "-";
const itemUnit = (itemId?: string) => itemMap.value.get(itemId || "")?.unit || "";
const currentAuthRouteName = computed(() => tabRouteNameMap[activeTab.value] || String(route.name || ""));
const currentAuthButtons = computed(() => new Set(authStore.authButtonListGet[currentAuthRouteName.value] || []));
const hasInventoryAuth = (code: string) => currentAuthButtons.value.has(code);
const hasAnyInventoryAuth = (codes: readonly string[]) => codes.some(code => hasInventoryAuth(code));
const hasInventoryAuthForTab = (tab: string, code: string) =>
  new Set(authStore.authButtonListGet[tabRouteNameMap[tab]] || []).has(code);
const hasAnyInventoryAuthForTab = (tab: string, codes: readonly string[]) =>
  codes.some(code => hasInventoryAuthForTab(tab, code));
const tabAuthMap: Record<string, readonly string[]> = {
  overview: [
    "inventory:request",
    "inventory:receive",
    "inventory:approve",
    "inventory:issue",
    "inventory:count",
    "inventory:export"
  ],
  executive: ["inventory:export"],
  requests: ["inventory:request", "inventory:receive", "inventory:approve", "inventory:issue"],
  stock: ["inventory:issue", "inventory:export"],
  items: ["inventory:issue"],
  weekly: ["inventory:request", "inventory:count"],
  controls: ["inventory:receive", "inventory:count"],
  trace: ["inventory:export", "inventory:issue", "inventory:count"]
};
const canViewAllDepartments = computed(
  () =>
    userStore.userInfo.role === "admin" ||
    hasAnyInventoryAuth(["inventory:approve", "inventory:issue", "inventory:count", "inventory:export"])
);
const belongsToCurrentDepartment = (department?: string) =>
  canViewAllDepartments.value || !currentDepartment.value || !department || department === currentDepartment.value;
const requireInventoryAuth = (code: string, actionName: string) => {
  if (hasInventoryAuth(code)) return true;
  ElMessage.warning(`当前岗位暂无“${actionName}”权限，请由对应负责人处理`);
  return false;
};
const today = () => new Date().toISOString().slice(0, 10);
const padWeek = (value: number) => String(value).padStart(2, "0");
const currentWeekNo = () => {
  const date = new Date();
  const utcDate = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
  const day = utcDate.getUTCDay() || 7;
  utcDate.setUTCDate(utcDate.getUTCDate() + 4 - day);
  const yearStart = new Date(Date.UTC(utcDate.getUTCFullYear(), 0, 1));
  const week = Math.ceil(((utcDate.getTime() - yearStart.getTime()) / 86400000 + 1) / 7);
  return `${utcDate.getUTCFullYear()}-W${padWeek(week)}`;
};
const daysFromToday = (date?: string) => {
  if (!date) return Number.POSITIVE_INFINITY;
  return Math.ceil((new Date(date).getTime() - new Date(today()).getTime()) / 86400000);
};
const flashRequestRow = (id?: string) => {
  if (!id) return;
  highlightedRequestId.value = id;
  window.setTimeout(() => {
    if (highlightedRequestId.value === id) highlightedRequestId.value = "";
  }, 1200);
};
const requestRowClassName = ({ row }: { row: InventoryRequest }) => (row.id === highlightedRequestId.value ? "row-flash" : "");
const normalizedRequestLines = (row: InventoryRequest): InventoryRequestLine[] => {
  if (row.lines?.length) {
    return row.lines.map(line => ({
      ...line,
      quantity: Number(line.quantity || 0),
      issuedQuantity: Number(line.issuedQuantity || 0)
    }));
  }
  return [
    {
      id: `${row.id}-legacy-line`,
      itemId: row.itemId,
      quantity: Number(row.quantity || 0),
      issuedQuantity: Number(row.issuedQuantity || 0),
      status: row.status
    }
  ];
};
const requestItemSummary = (lines: InventoryRequestLine[]) => {
  const summary = lines
    .slice(0, 3)
    .map(line => `${itemName(line.itemId)} ${line.quantity}${itemUnit(line.itemId)}`)
    .join("、");
  return lines.length > 3 ? `${summary} 等 ${lines.length} 项` : summary;
};
const requestLineRemaining = (line: InventoryRequestLine) =>
  Math.max(0, Number(line.quantity || 0) - Number(line.issuedQuantity || 0));
const requestRemainingLines = (row?: InventoryRequest) =>
  row ? normalizedRequestLines(row).filter(line => requestLineRemaining(line) > 0) : [];
const activeRequestBatches = computed(() => {
  const itemIds = new Set(requestRemainingLines(activeRequest.value).map(line => line.itemId));
  return db.value.batches.filter(batch => itemIds.has(batch.itemId) && Number(batch.quantity || 0) > 0);
});

const departmentOptions = computed(() =>
  Array.from(
    new Set([
      currentDepartment.value,
      ...db.value.requests.map(row => row.department),
      ...db.value.weeklyConsumptions.map(row => row.department),
      ...db.value.movements.map(row => row.department)
    ])
  ).filter(Boolean)
);

const stockRows = computed(() =>
  db.value.items.map(item => {
    const stock = db.value.batches
      .filter(batch => batch.itemId === item.id)
      .reduce((sum, batch) => sum + Number(batch.quantity || 0), 0);
    return {
      ...item,
      item,
      stock,
      lowStock: stock <= Number(item.lowStockThreshold || 0)
    };
  })
);

const batchRows = computed(() =>
  db.value.batches.map(batch => {
    const days = daysFromToday(batch.expiryDate);
    return {
      ...batch,
      itemName: itemName(batch.itemId),
      expired: days < 0,
      expirySoon: days >= 0 && days <= 30
    };
  })
);

const lowStockRows = computed(() => stockRows.value.filter(row => row.lowStock));
const expirySoonRows = computed(() => batchRows.value.filter(row => row.expirySoon));
const categoryFilterOptions = computed(() => Array.from(new Set(db.value.items.map(row => row.category).filter(Boolean))));
const requestRows = computed(() =>
  db.value.requests
    .filter(row => belongsToCurrentDepartment(row.department))
    .map(row => {
      const lines = normalizedRequestLines(row);
      return {
        ...row,
        lines,
        itemName: itemName(row.itemId),
        itemSummary: row.itemSummary || requestItemSummary(lines),
        itemCount: row.itemCount || lines.length,
        unit: itemUnit(row.itemId)
      };
    })
);
const weeklyRows = computed(() =>
  db.value.weeklyConsumptions
    .filter(row => belongsToCurrentDepartment(row.department))
    .map(row => ({
      ...row,
      itemName: itemName(row.itemId)
    }))
);
const countRows = computed(() =>
  db.value.counts.map(row => ({
    ...row,
    itemName: itemName(row.itemId)
  }))
);
const traceRows = computed(() =>
  db.value.movements
    .filter(row => belongsToCurrentDepartment(row.department))
    .map(row => ({
      ...row,
      typeLabel: movementTypeLabel(row.type),
      itemName: itemName(row.itemId)
    }))
);

const pendingRequestRows = computed(() => requestRows.value.filter(row => row.status === "pending"));
const approvedRequestRows = computed(() => requestRows.value.filter(row => row.status === "approved"));
const partiallyIssuedRequestRows = computed(() => requestRows.value.filter(row => row.status === "partially_issued"));
const issuedRequestRows = computed(() => requestRows.value.filter(row => row.status === "issued"));
const expiredRows = computed(() => batchRows.value.filter(row => row.expired));
const countDiffRows = computed(() => countRows.value.filter(row => Number(row.differenceQuantity || 0) !== 0));
const scrapRows = computed(() => traceRows.value.filter(row => row.type === "scrap"));

const containsText = (values: unknown[], keyword: string) => {
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) return true;
  return values.some(value =>
    String(value ?? "")
      .toLowerCase()
      .includes(normalized)
  );
};

const inDateRange = (date: string | undefined, range: string[]) => {
  if (!range?.length || !date) return true;
  const value = date.slice(0, 10);
  return value >= range[0] && value <= range[1];
};

const filteredStockRows = computed(() =>
  stockRows.value.filter(row => {
    if (stockFilters.category && row.category !== stockFilters.category) return false;
    if (stockFilters.status === "low" && !row.lowStock) return false;
    if (stockFilters.status === "sensitive" && !row.sensitive) return false;
    return containsText([row.name, row.category, row.spec, row.location], stockFilters.keyword);
  })
);
const filteredItemRows = computed(() =>
  db.value.items.filter(row => {
    if (itemFilters.category && row.category !== itemFilters.category) return false;
    return containsText([row.name, row.category, row.spec, row.unit, row.location], itemFilters.keyword);
  })
);
const filteredRequestRows = computed(() =>
  requestRows.value.filter(row => {
    if (requestFilters.status && row.status !== requestFilters.status) return false;
    if (requestFilters.department && row.department !== requestFilters.department) return false;
    if (!inDateRange(row.createdAt, requestFilters.dateRange)) return false;
    return containsText(
      [row.itemSummary, row.itemName, row.department, row.reason, row.owner, row.applicant],
      requestFilters.keyword
    );
  })
);
const filteredTraceRows = computed(() =>
  traceRows.value.filter(row => {
    if (traceFilters.type && row.type !== traceFilters.type) return false;
    if (traceFilters.department && row.department !== traceFilters.department) return false;
    if (!inDateRange(row.createdAt, traceFilters.dateRange)) return false;
    return containsText([row.itemName, row.department, row.operator, row.reason, row.relatedId], traceFilters.keyword);
  })
);
const selectedWeeklyItem = computed(() => itemMap.value.get(weeklyForm.itemId));
const weeklyItemStock = computed(() => stockRows.value.find(row => row.id === weeklyForm.itemId)?.stock || 0);
const weeklyLastRecord = computed(
  () =>
    weeklyRows.value
      .filter(
        row => row.department === weeklyForm.department && row.itemId === weeklyForm.itemId && row.weekNo !== weeklyForm.weekNo
      )
      .sort((a, b) => String(b.weekNo || "").localeCompare(String(a.weekNo || "")))[0]
);
const weeklyIssuedThisWeek = computed(() =>
  traceRows.value
    .filter(
      row =>
        row.type === "issue" &&
        row.itemId === weeklyForm.itemId &&
        row.department === weeklyForm.department &&
        String(row.createdAt || "").slice(0, 10) >= today().slice(0, 8) + "01"
    )
    .reduce((sum, row) => sum + Number(row.quantity || 0), 0)
);
const applyWeeklySuggestion = () => {
  if (!selectedWeeklyItem.value) return;
  if (weeklyLastRecord.value) {
    weeklyForm.remainingQuantity = Number(weeklyLastRecord.value.remainingQuantity || 0);
    weeklyForm.nextWeekQuantity = Number(weeklyLastRecord.value.nextWeekQuantity || 0);
    if (!weeklyForm.owner) weeklyForm.owner = weeklyLastRecord.value.owner || operatorName.value;
    return;
  }
  if (!weeklyForm.nextWeekQuantity) weeklyForm.nextWeekQuantity = weeklyIssuedThisWeek.value;
  if (!weeklyForm.owner) weeklyForm.owner = operatorName.value;
};

const executiveUrgentCount = computed(() => expiredRows.value.length + scrapRows.value.filter(row => !row.reason).length);
const executiveAttentionCount = computed(
  () =>
    lowStockRows.value.length +
    expirySoonRows.value.length +
    pendingRequestRows.value.length +
    approvedRequestRows.value.length +
    partiallyIssuedRequestRows.value.length +
    issuedRequestRows.value.length +
    countDiffRows.value.length
);
const executiveSignal = computed(() => {
  if (executiveUrgentCount.value > 0) return { level: "danger", title: "有紧急风险", desc: "先处理过期、报废原因或账实差异。" };
  if (executiveAttentionCount.value > 0)
    return { level: "warning", title: "有关注事项", desc: "低库存、临期和待办需要今天跟进。" };
  return { level: "success", title: "运行正常", desc: "暂无必须立即处理的库存风险。" };
});
const closedRequestCount = computed(() => requestRows.value.filter(row => row.status === "received").length);
const requestClosureRate = computed(() =>
  requestRows.value.length ? Math.round((closedRequestCount.value / requestRows.value.length) * 100) : 100
);
const executiveKpis = computed(() => [
  {
    label: "申领闭环率",
    value: `${requestClosureRate.value}%`,
    desc: `${closedRequestCount.value}/${requestRows.value.length} 单已签收`,
    tone: requestClosureRate.value < 80 ? "warning" : ""
  },
  {
    label: "低库存种类",
    value: lowStockRows.value.length,
    desc: "低于或等于预警线",
    tone: lowStockRows.value.length ? "warning" : ""
  },
  {
    label: "临期/过期批次",
    value: expirySoonRows.value.length + expiredRows.value.length,
    desc: "30 天内临期含已过期",
    tone: expiredRows.value.length ? "danger" : "warning"
  },
  {
    label: "本期报废记录",
    value: scrapRows.value.length,
    desc: "需要原因和责任可追溯",
    tone: scrapRows.value.length ? "danger" : ""
  }
]);
const departmentConsumptionTop = computed(() =>
  Array.from(
    weeklyRows.value.reduce((map, row) => {
      map.set(row.department || "未填科室", (map.get(row.department || "未填科室") || 0) + Number(row.consumedQuantity || 0));
      return map;
    }, new Map<string, number>())
  )
    .map(([department, value]) => ({ department, value }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 6)
);
const maxDepartmentConsumption = computed(() => Math.max(...departmentConsumptionTop.value.map(row => row.value), 1));
const staleBatchRows = computed(() =>
  batchRows.value
    .filter(row => Number(row.quantity || 0) > 0)
    .map(row => {
      const lastIssue = traceRows.value
        .filter(trace => trace.batchId === row.id && trace.type === "issue")
        .sort((a, b) => String(b.createdAt || "").localeCompare(String(a.createdAt || "")))[0];
      return { ...row, lastIssueAt: lastIssue?.createdAt || "暂无出库" };
    })
    .slice(0, 6)
);

const visibleWorkflowSteps = computed(() => workflowSteps.filter(step => hasAnyInventoryAuth(step.auth)));
const visibleRoleEntryCards = computed(() => roleEntryCards.filter(card => hasAnyInventoryAuth(card.auth)));
const visibleTabNavItems = computed(() =>
  tabNavItems.filter(
    item => userStore.userInfo.role === "admin" || hasAnyInventoryAuthForTab(item.tab, tabAuthMap[item.tab] || [])
  )
);
const availableReturnTypeOptions = computed(() => returnTypeOptions.filter(item => hasInventoryAuth(item.auth)));
const canSubmitReturnOrScrap = computed(() =>
  returnForm.type === "return" ? hasInventoryAuth("inventory:receive") : hasInventoryAuth("inventory:count")
);
const currentTabProfile = computed(() => tabProfiles[activeTab.value as keyof typeof tabProfiles] || tabProfiles.overview);
const currentTabActions = computed(() => {
  const actionsByTab: Record<string, { label: string; action: TabAction; auth: string; buttonProps: Record<string, unknown> }[]> =
    {
      overview: [
        { label: "新增申领", action: "request", auth: "inventory:request", buttonProps: { type: "primary", icon: Plus } },
        { label: "导出风险", action: "exportRisk", auth: "inventory:export", buttonProps: { plain: true, icon: Download } }
      ],
      executive: [
        {
          label: "导出周报",
          action: "exportWeeklyReport",
          auth: "inventory:export",
          buttonProps: { type: "primary", icon: Download }
        },
        { label: "导出风险", action: "exportRisk", auth: "inventory:export", buttonProps: { plain: true, icon: Download } }
      ],
      requests: [
        { label: "新增申领", action: "request", auth: "inventory:request", buttonProps: { type: "primary", icon: Plus } }
      ],
      stock: [{ label: "入库", action: "inbound", auth: "inventory:issue", buttonProps: { type: "primary", icon: Plus } }],
      items: [{ label: "新增物资", action: "item", auth: "inventory:issue", buttonProps: { type: "primary", icon: Plus } }],
      weekly: [
        { label: "新增周消耗", action: "weekly", auth: "inventory:request", buttonProps: { type: "primary", icon: Plus } }
      ],
      controls: [{ label: "新增盘点", action: "count", auth: "inventory:count", buttonProps: { type: "primary", icon: Plus } }],
      trace: [
        { label: "导出流水", action: "exportTrace", auth: "inventory:export", buttonProps: { type: "primary", icon: Download } }
      ]
    };
  return (actionsByTab[activeTab.value] || []).filter(action => hasInventoryAuth(action.auth));
});

const todoRows = computed<TodoRow[]>(() =>
  [
    ...pendingRequestRows.value.slice(0, 4).map(row => ({
      id: `approve-${row.id}`,
      type: "待审核",
      level: "warning" as TagLevel,
      title: `${row.department || "未填科室"} 申请 ${row.itemSummary}`,
      desc: `${row.itemCount || 1} 项物资，${row.reason || "无申请理由"}`,
      actionLabel: "审核",
      tab: "requests",
      action: "approve" as const,
      request: row
    })),
    ...approvedRequestRows.value.slice(0, 4).map(row => ({
      id: `issue-${row.id}`,
      type: "待发放",
      level: "primary" as TagLevel,
      title: `${row.department || "未填科室"} 待领 ${row.itemSummary}`,
      desc: `${row.itemCount || 1} 项物资，系统可按效期自动拆批`,
      actionLabel: "发放",
      tab: "requests",
      action: "issue" as const,
      request: row
    })),
    ...partiallyIssuedRequestRows.value.slice(0, 4).map(row => ({
      id: `issue-more-${row.id}`,
      type: "部分发放",
      level: "warning" as TagLevel,
      title: `${row.department || "未填科室"} 仍需补发`,
      desc: `已发 ${row.issuedQuantity || 0} / ${row.quantity}，到货后继续发放`,
      actionLabel: "继续发",
      tab: "requests",
      action: "issue" as const,
      request: row
    })),
    ...issuedRequestRows.value.slice(0, 4).map(row => ({
      id: `receive-${row.id}`,
      type: "待签收",
      level: "success" as TagLevel,
      title: `${row.department || "未填科室"} 待确认 ${row.itemSummary}`,
      desc: `已全部发放，需要领取人确认`,
      actionLabel: "签收",
      tab: "requests",
      action: "receive" as const,
      request: row
    }))
  ].filter(row => {
    if (row.action === "approve") return hasInventoryAuth("inventory:approve");
    if (row.action === "issue") return hasInventoryAuth("inventory:issue");
    if (row.action === "receive") return hasInventoryAuth("inventory:receive");
    return true;
  })
);

const riskRows = computed(() => [
  ...lowStockRows.value.map(row => ({
    id: `low-${row.id}`,
    type: "低库存",
    level: "danger" as TagLevel,
    subject: row.name,
    department: "-",
    status: `当前 ${row.stock}${row.unit}，预警线 ${row.lowStockThreshold}${row.unit}`,
    suggestion: "优先核对库存，必要时补充入库或限制非必要领用",
    tab: "stock"
  })),
  ...expiredRows.value.map(row => ({
    id: `expired-${row.id}`,
    type: "已过期",
    level: "danger" as TagLevel,
    subject: row.itemName,
    department: "-",
    status: `批号 ${row.batchNo || "无批号"}，效期 ${row.expiryDate || "未填"}`,
    suggestion: "暂停发放，按制度做报废或隔离处理",
    tab: "stock"
  })),
  ...expirySoonRows.value.map(row => ({
    id: `expiry-${row.id}`,
    type: "临期",
    level: "warning" as TagLevel,
    subject: row.itemName,
    department: "-",
    status: `批号 ${row.batchNo || "无批号"}，效期 ${row.expiryDate || "未填"}`,
    suggestion: "优先消耗，无法消耗时提前准备退换或报废说明",
    tab: "stock"
  })),
  ...countDiffRows.value.slice(0, 20).map(row => ({
    id: `count-${row.id}`,
    type: "盘点差异",
    level: "warning" as TagLevel,
    subject: row.itemName,
    department: "-",
    status: `账面 ${row.bookQuantity}，实盘 ${row.actualQuantity}，差异 ${row.differenceQuantity}`,
    suggestion: row.reason || "补充差异原因，并由负责人复核",
    tab: "controls"
  })),
  ...scrapRows.value.slice(0, 20).map(row => ({
    id: `scrap-${row.id}`,
    type: "报废",
    level: "danger" as TagLevel,
    subject: row.itemName,
    department: row.department || "-",
    status: `${row.quantity}${itemUnit(row.itemId)}，${row.createdAt || "未记录时间"}`,
    suggestion: row.reason || "补充报废原因和责任确认",
    tab: "trace"
  }))
]);
const currentTabStats = computed<TabStat[]>(() => {
  const statsByTab: Record<string, TabStat[]> = {
    overview: [
      { label: "待审核申领", value: db.value.summary.pendingRequestCount, desc: "等待负责人确认", tab: "requests" },
      {
        label: "待发放申领",
        value: db.value.summary.approvedRequestCount,
        desc: "等待库管发放",
        tone: "warning",
        tab: "requests"
      },
      { label: "低库存物资", value: lowStockRows.value.length, desc: "低于或等于预警线", tone: "danger", tab: "stock" },
      { label: "异常提醒", value: riskRows.value.length, desc: "低库存、临期和差异", tone: "danger", tab: "overview" }
    ],
    executive: [
      {
        label: "紧急风险",
        value: executiveUrgentCount.value,
        desc: "需要立即处理",
        tone: executiveUrgentCount.value ? "danger" : undefined
      },
      {
        label: "关注事项",
        value: executiveAttentionCount.value,
        desc: "今天跟进",
        tone: executiveAttentionCount.value ? "warning" : undefined
      },
      { label: "闭环率", value: `${requestClosureRate.value}%`, desc: "已签收/全部申领", tab: "requests" },
      {
        label: "待签字",
        value: pendingRequestRows.value.length + approvedRequestRows.value.length + partiallyIssuedRequestRows.value.length,
        desc: "审核、发放或补发",
        tone: "warning",
        tab: "requests"
      }
    ],
    requests: [
      { label: "待审核", value: pendingRequestRows.value.length, desc: "负责人处理", tone: "warning" },
      { label: "待发放", value: approvedRequestRows.value.length, desc: "库管处理" },
      { label: "部分发放", value: partiallyIssuedRequestRows.value.length, desc: "到货后补发", tone: "warning" },
      { label: "待签收", value: issuedRequestRows.value.length, desc: "科室确认" }
    ],
    stock: [
      { label: "物资种类", value: db.value.summary.itemCount, desc: "已建档物资" },
      { label: "库存批次", value: db.value.summary.batchCount, desc: "可追溯批次" },
      { label: "低库存", value: lowStockRows.value.length, desc: "需要补库", tone: "danger" },
      { label: "近 30 天临期", value: expirySoonRows.value.length, desc: "优先消耗", tone: "warning" }
    ],
    items: [
      { label: "物资档案", value: db.value.items.length, desc: "统一名称规格" },
      { label: "敏感物资", value: db.value.items.filter(row => row.sensitive).length, desc: "需重点追溯", tone: "warning" },
      { label: "批号管理", value: db.value.items.filter(row => row.batchRequired).length, desc: "入库需填批号" },
      { label: "效期管理", value: db.value.items.filter(row => row.expiryRequired).length, desc: "入库需填效期" }
    ],
    weekly: [
      { label: "周消耗记录", value: weeklyRows.value.length, desc: "科室填报总量" },
      { label: "涉及科室", value: new Set(weeklyRows.value.map(row => row.department).filter(Boolean)).size, desc: "已参与填报" },
      {
        label: "下周预计合计",
        value: weeklyRows.value.reduce((sum, row) => sum + Number(row.nextWeekQuantity || 0), 0),
        desc: "用于备货参考"
      },
      { label: "异常说明", value: weeklyRows.value.filter(row => row.abnormalReason).length, desc: "需要复核", tone: "warning" }
    ],
    controls: [
      { label: "盘点记录", value: countRows.value.length, desc: "账实核对次数" },
      { label: "盘点差异", value: countDiffRows.value.length, desc: "需原因闭环", tone: "warning" },
      { label: "退回记录", value: traceRows.value.filter(row => row.type === "return").length, desc: "科室退回" },
      { label: "报废记录", value: scrapRows.value.length, desc: "需重点说明", tone: "danger" }
    ],
    trace: [
      { label: "库存流水", value: traceRows.value.length, desc: "全部变动记录" },
      { label: "入库", value: traceRows.value.filter(row => row.type === "inbound").length, desc: "来源记录" },
      { label: "发放", value: traceRows.value.filter(row => row.type === "issue").length, desc: "去向记录" },
      { label: "审计日志", value: db.value.auditLogs.length, desc: "操作留痕" }
    ]
  };
  return statsByTab[activeTab.value] || statsByTab.overview;
});

const movementTypeLabel = (type: string) =>
  ({ inbound: "入库", issue: "发放", return: "退回", scrap: "报废", count: "盘点" })[type] || type;

type InventoryTagType = "success" | "warning" | "info" | "primary" | "danger";

const requestStatusMeta = (status: string): { label: string; type: InventoryTagType } => {
  const meta = {
    pending: { label: "待审核", type: "warning" },
    approved: { label: "待发放", type: "primary" },
    partially_issued: { label: "部分发放", type: "warning" },
    issued: { label: "待签收", type: "success" },
    received: { label: "已签收", type: "info" },
    rejected: { label: "已驳回", type: "danger" },
    cancelled: { label: "已撤销", type: "info" },
    void: { label: "已作废", type: "danger" }
  }[status] || { label: status, type: "info" };
  return meta as { label: string; type: InventoryTagType };
};
const requestStepActive = (status: string) => {
  const stepMap: Record<string, number> = {
    pending: 0,
    approved: 1,
    partially_issued: 2,
    issued: 2,
    received: 3
  };
  return stepMap[status] ?? 0;
};

const goTab = (tab: string) => (activeTab.value = tab);

watch(
  () => route.path,
  path => {
    const routeTab = routeTabMap[path];
    if (routeTab && activeTab.value !== routeTab) activeTab.value = routeTab;
  },
  { immediate: true }
);

watch(activeTab, tab => {
  const nextPath = tabRoutePathMap[tab];
  if (nextPath && route.path !== nextPath) router.replace(nextPath);
});

watch(
  visibleTabNavItems,
  items => {
    if (!items.length) return;
    if (!items.some(item => item.tab === activeTab.value)) activeTab.value = items[0].tab;
  },
  { immediate: true }
);

watch(
  availableReturnTypeOptions,
  options => {
    if (!options.some(item => item.value === returnForm.type)) {
      returnForm.type = (options[0]?.value || "return") as ReturnOrScrapParams["type"];
    }
  },
  { immediate: true }
);

watch(
  () => [weeklyForm.department, weeklyForm.itemId],
  () => {
    if (!weeklyForm.department || !weeklyForm.itemId) return;
    if (!weeklyForm.owner) weeklyForm.owner = operatorName.value;
    if (!weeklyForm.remainingQuantity && weeklyLastRecord.value) {
      weeklyForm.remainingQuantity = Number(weeklyLastRecord.value.remainingQuantity || 0);
    }
    if (!weeklyForm.nextWeekQuantity) {
      weeklyForm.nextWeekQuantity = Number(weeklyLastRecord.value?.nextWeekQuantity || weeklyIssuedThisWeek.value || 0);
    }
  }
);

const resetObject = (target: Record<string, unknown>, values: Record<string, unknown>) => {
  Object.keys(target).forEach(key => {
    delete target[key];
  });
  Object.assign(target, values);
};
const newRequestLine = () => ({ localId: `line-${Date.now()}-${Math.random()}`, itemId: "", quantity: 0 });
const addRequestLine = () => requestForm.lines.push(newRequestLine());
const removeRequestLine = (index: number) => {
  if (requestForm.lines.length <= 1) return;
  requestForm.lines.splice(index, 1);
};
const validateRequestLines = () => {
  const lines = requestForm.lines.filter(line => line.itemId && Number(line.quantity || 0) > 0);
  if (!lines.length) {
    ElMessage.warning("请至少添加一项物资，并填写申领数量");
    return false;
  }
  if (lines.length !== requestForm.lines.length) {
    ElMessage.warning("申领明细中有未选择物资或数量为 0 的行");
    return false;
  }
  return true;
};
const validateIssueLines = () => {
  const total = issueForm.lines.reduce((sum, line) => sum + Number(line.issuedQuantity || 0), 0);
  if (total > 0) return true;
  ElMessage.warning("请至少填写一项本次发放数量");
  return false;
};

const loadInventory = async () => {
  loading.value = true;
  try {
    const { data } = await getInventoryDbApi();
    db.value = data;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    loading.value = false;
  }
};

const openItemDialog = (row?: InventoryItem) => {
  if (!requireInventoryAuth("inventory:issue", "维护物资档案")) return;
  resetObject(itemForm, {
    id: row?.id,
    name: row?.name || "",
    category: row?.category || "",
    spec: row?.spec || "",
    unit: row?.unit || "个",
    location: row?.location || "",
    lowStockThreshold: row?.lowStockThreshold ?? 0,
    sensitive: row?.sensitive ?? false,
    batchRequired: row?.batchRequired ?? false,
    expiryRequired: row?.expiryRequired ?? false,
    enabled: row?.enabled ?? true
  });
  itemDialogVisible.value = true;
  activeTab.value = "items";
};

const saveItem = async () => {
  if (!requireInventoryAuth("inventory:issue", "保存物资档案")) return;
  if (!(await itemFormRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  try {
    db.value = (await saveInventoryItemApi({ ...itemForm, operator: operatorName.value })).data;
    ElMessage.success("物资档案已保存");
    itemDialogVisible.value = false;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const openInboundDialog = (item?: InventoryItem) => {
  if (!requireInventoryAuth("inventory:issue", "入库")) return;
  if (!item && !db.value.items.length) {
    ElMessage.warning("请先新增物资档案，再进行入库");
    activeTab.value = "items";
    return;
  }
  Object.assign(inboundForm, {
    itemId: item?.id || "",
    quantity: 0,
    batchNo: "",
    expiryDate: "",
    location: item?.location || "",
    source: "",
    operator: operatorName.value
  });
  inboundDialogVisible.value = true;
  activeTab.value = "stock";
};

const saveInbound = async () => {
  if (!requireInventoryAuth("inventory:issue", "保存入库")) return;
  if (!(await inboundFormRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  try {
    db.value = (await inboundInventoryApi({ ...inboundForm, operator: operatorName.value })).data;
    ElMessage.success("入库记录已保存");
    inboundDialogVisible.value = false;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const openRequestDialog = () => {
  if (!requireInventoryAuth("inventory:request", "提交申领")) return;
  Object.assign(requestForm, {
    lines: [newRequestLine()],
    department: currentDepartment.value,
    applicant: operatorName.value,
    owner: "",
    reason: "",
    expectedUseWeek: ""
  });
  requestDialogVisible.value = true;
  activeTab.value = "requests";
};

const saveRequest = async () => {
  if (!requireInventoryAuth("inventory:request", "提交申领")) return;
  if (!(await requestFormRef.value?.validate().catch(() => false))) return;
  if (!validateRequestLines()) return;
  saving.value = true;
  try {
    db.value = (
      await createInventoryRequestApi({
        ...requestForm,
        lines: requestForm.lines.map(line => ({ itemId: line.itemId, quantity: Number(line.quantity || 0) }))
      })
    ).data;
    ElMessage.success("申领单已提交");
    requestDialogVisible.value = false;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const approveRequest = async (row: InventoryRequest) => {
  if (!requireInventoryAuth("inventory:approve", "审核申领")) return;
  saving.value = true;
  try {
    db.value = (await approveInventoryRequestApi({ id: row.id, operator: operatorName.value, owner: row.owner })).data;
    flashRequestRow(row.id);
    ElMessage.success("申领单已审核");
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const openIssueDialog = (row: InventoryRequest) => {
  if (!requireInventoryAuth("inventory:issue", "发放物资")) return;
  activeRequest.value = row;
  Object.assign(issueForm, {
    id: row.id,
    batchId: "",
    lines: requestRemainingLines(row).map(line => ({
      id: line.id,
      itemId: line.itemId,
      remaining: requestLineRemaining(line),
      issuedQuantity: requestLineRemaining(line)
    })),
    operator: operatorName.value
  });
  issueDialogVisible.value = true;
  activeTab.value = "requests";
};

const issueRequest = async () => {
  if (!requireInventoryAuth("inventory:issue", "确认发放")) return;
  if (!validateIssueLines()) return;
  saving.value = true;
  try {
    db.value = (
      await issueInventoryRequestApi({
        id: issueForm.id,
        batchId: issueForm.batchId,
        operator: operatorName.value,
        lines: issueForm.lines
          .filter(line => Number(line.issuedQuantity || 0) > 0)
          .map(line => ({ id: line.id, itemId: line.itemId, issuedQuantity: Number(line.issuedQuantity || 0) }))
      })
    ).data;
    flashRequestRow(issueForm.id);
    ElMessage.success("物资已发放");
    issueDialogVisible.value = false;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const receiveRequest = async (row: InventoryRequest) => {
  if (!requireInventoryAuth("inventory:receive", "签收物资")) return;
  saving.value = true;
  try {
    db.value = (
      await receiveInventoryRequestApi({ id: row.id, operator: operatorName.value, receiver: operatorName.value })
    ).data;
    flashRequestRow(row.id);
    ElMessage.success("领取已确认");
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const promptRequestReason = async (title: string, placeholder: string) => {
  const result = await ElMessageBox.prompt(placeholder, title, {
    confirmButtonText: "确认",
    cancelButtonText: "取消",
    inputType: "textarea",
    inputPlaceholder: placeholder,
    inputValidator: value => Boolean(String(value || "").trim()) || "请填写原因"
  });
  return String(result.value || "").trim();
};

const rejectRequest = async (row: InventoryRequest) => {
  if (!requireInventoryAuth("inventory:approve", "驳回申领")) return;
  try {
    const reason = await promptRequestReason("驳回申领", "请填写驳回原因，便于科室修改后重新提交");
    saving.value = true;
    db.value = (await rejectInventoryRequestApi({ id: row.id, reason, operator: operatorName.value })).data;
    flashRequestRow(row.id);
    ElMessage.success("申领单已驳回");
  } catch (error) {
    if (error !== "cancel") ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const cancelRequest = async (row: InventoryRequest) => {
  if (!requireInventoryAuth("inventory:request", "撤销申领")) return;
  try {
    const reason = await promptRequestReason("撤销申领", "请填写撤销原因");
    saving.value = true;
    db.value = (await cancelInventoryRequestApi({ id: row.id, reason, operator: operatorName.value })).data;
    flashRequestRow(row.id);
    ElMessage.success("申领单已撤销");
  } catch (error) {
    if (error !== "cancel") ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const voidRequest = async (row: InventoryRequest) => {
  if (!requireInventoryAuth("inventory:approve", "作废申领")) return;
  try {
    const reason = await promptRequestReason("作废申领", "请填写作废原因。已发放申领不能直接作废，需走退回流程。");
    saving.value = true;
    db.value = (await voidInventoryRequestApi({ id: row.id, reason, operator: operatorName.value })).data;
    flashRequestRow(row.id);
    ElMessage.success("申领单已作废");
  } catch (error) {
    if (error !== "cancel") ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const openWeeklyDialog = () => {
  if (!requireInventoryAuth("inventory:request", "填报周消耗")) return;
  Object.assign(weeklyForm, {
    weekNo: currentWeekNo(),
    department: currentDepartment.value,
    itemId: "",
    consumedQuantity: 0,
    remainingQuantity: 0,
    nextWeekQuantity: 0,
    owner: operatorName.value,
    abnormalReason: "",
    operator: operatorName.value
  });
  weeklyDialogVisible.value = true;
  activeTab.value = "weekly";
};

const saveWeekly = async () => {
  if (!requireInventoryAuth("inventory:request", "确认周消耗")) return;
  if (!(await weeklyFormRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  try {
    db.value = (await saveWeeklyConsumptionApi({ ...weeklyForm, operator: operatorName.value })).data;
    ElMessage.success("周消耗已确认");
    weeklyDialogVisible.value = false;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const submitReturnOrScrap = async () => {
  const actionName = returnForm.type === "return" ? "退回物资" : "报废物资";
  const requiredAuth = returnForm.type === "return" ? "inventory:receive" : "inventory:count";
  if (!requireInventoryAuth(requiredAuth, actionName)) return;
  if (!(await returnFormRef.value?.validate().catch(() => false))) return;
  if (returnForm.type === "scrap") {
    const confirmed = await ElMessageBox.confirm("报废会形成不可忽略的追溯记录，请确认原因、数量和物资无误。", "确认报废", {
      confirmButtonText: "确认报废",
      cancelButtonText: "再检查一下",
      type: "warning"
    })
      .then(() => true)
      .catch(() => false);
    if (!confirmed) return;
  }
  saving.value = true;
  try {
    db.value = (await returnOrScrapInventoryApi({ ...returnForm, operator: operatorName.value })).data;
    ElMessage.success("库存变更已保存");
    Object.assign(returnForm, { quantity: 0, reason: "" });
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const openCountDialog = () => {
  if (!requireInventoryAuth("inventory:count", "盘点")) return;
  Object.assign(countForm, {
    itemId: "",
    batchId: "",
    actualQuantity: 0,
    operator: operatorName.value,
    reason: ""
  });
  countDialogVisible.value = true;
  activeTab.value = "controls";
};

const saveCount = async () => {
  if (!requireInventoryAuth("inventory:count", "保存盘点")) return;
  if (!(await countFormRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  try {
    db.value = (await countInventoryApi({ ...countForm, operator: operatorName.value })).data;
    ElMessage.success("盘点结果已记录");
    countDialogVisible.value = false;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
};

const batchesForItem = (itemId?: string) =>
  db.value.batches.filter(batch => batch.itemId === itemId && Number(batch.quantity || 0) > 0);
const batchLabel = (batch: InventoryBatch) =>
  `${batch.batchNo || "无批号"} / ${batch.quantity}${itemUnit(batch.itemId)} / ${batch.expiryDate || "无效期"}`;

const runTabAction = (action: TabAction) => {
  if (action === "item") return openItemDialog();
  if (action === "inbound") return openInboundDialog(db.value.items[0]);
  if (action === "request") return openRequestDialog();
  if (action === "weekly") return openWeeklyDialog();
  if (action === "count") return openCountDialog();
  if (action === "exportRisk") return exportCsv(riskRows.value, "inventory-risk.csv");
  if (action === "exportStock") return exportCsv(stockRows.value, "inventory-stock.csv");
  if (action === "exportTrace") return exportCsv(traceRows.value, "inventory-trace.csv");
  if (action === "exportWeeklyReport") return exportWeeklyReport();
};

const handleWorkflowStep = (action: WorkflowAction) => {
  const step = workflowSteps.find(item => item.action === action);
  if (step && !hasAnyInventoryAuth(step.auth)) {
    ElMessage.warning("当前岗位暂无该流程动作权限，请由对应负责人处理");
    return;
  }
  if (action === "item") return openItemDialog();
  if (action === "inbound") return openInboundDialog(db.value.items[0]);
  if (action === "request") return openRequestDialog();
  if (action === "weekly") return openWeeklyDialog();
  if (action === "controls") return openCountDialog();
  if (action === "requests") return goTab("requests");
};

const openTodo = (row: TodoRow) => {
  if (!row.request) {
    goTab(row.tab);
    return;
  }
  if (row.action === "approve") {
    if (!requireInventoryAuth("inventory:approve", "审核申领")) return;
    approveRequest(row.request);
    return;
  }
  if (row.action === "issue") {
    if (!requireInventoryAuth("inventory:issue", "发放物资")) return;
    openIssueDialog(row.request);
    return;
  }
  if (row.action === "receive") {
    if (!requireInventoryAuth("inventory:receive", "签收物资")) return;
    receiveRequest(row.request);
    return;
  }
  goTab(row.tab);
};

const exportWeeklyReport = () => {
  const rows: Record<string, unknown>[] = [
    { section: "红绿灯", metric: "当前结论", value: executiveSignal.value.title, note: executiveSignal.value.desc },
    { section: "红绿灯", metric: "紧急事项", value: executiveUrgentCount.value, note: "过期、原因缺失或高风险异常" },
    { section: "红绿灯", metric: "关注事项", value: executiveAttentionCount.value, note: "低库存、临期、待办与盘点差异" },
    ...executiveKpis.value.map(item => ({
      section: "关键指标",
      metric: item.label,
      value: item.value,
      note: item.desc
    })),
    ...departmentConsumptionTop.value.map((item, index) => ({
      section: "科室消耗TOP",
      metric: `${index + 1}. ${item.department}`,
      value: item.value,
      note: "基于周消耗记录"
    })),
    ...riskRows.value.slice(0, 12).map(item => ({
      section: "风险清单",
      metric: item.type,
      value: item.subject,
      note: `${item.status}；${item.suggestion}`
    }))
  ];
  exportCsv(rows, "inventory-weekly-report.csv");
};

onMounted(loadInventory);
</script>

<style scoped lang="scss">
.inventory-page {
  display: grid;
  gap: 14px;
  color: #1f2937;
}

.inventory-hero,
.summary-card,
.panel {
  background: #ffffff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: 0 1px 2px rgb(15 23 42 / 4%);
}

.inventory-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 18px;
  background: linear-gradient(90deg, rgb(236 253 245 / 86%), rgb(255 255 255 / 96%) 52%), #ffffff;
  border-color: rgb(20 184 166 / 18%);

  span {
    color: #0f766e;
    font-size: 13px;
    font-weight: 700;
  }

  h1,
  p {
    margin: 0;
  }

  h1 {
    margin-top: 4px;
    font-size: 24px;
    line-height: 1.35;
  }

  p {
    margin-top: 6px;
    color: var(--el-text-color-regular);
  }
}

.hero-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
}

.task-strip {
  display: grid;
  grid-template-columns: minmax(220px, 0.9fr) repeat(4, minmax(120px, 1fr));
  gap: 10px;
}

.task-card {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 11px 13px;
  background: #f8fafc;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;

  span,
  strong,
  small {
    display: block;
  }

  span {
    color: #0f766e;
    font-size: 12px;
    font-weight: 700;
  }

  strong {
    color: #111827;
    font-size: 15px;
    line-height: 1.35;
  }

  small {
    color: var(--el-text-color-secondary);
    line-height: 1.35;
  }
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.summary-card {
  position: relative;
  display: grid;
  gap: 3px;
  min-width: 0;
  min-height: 82px;
  padding: 11px 13px;
  overflow: hidden;
  text-align: left;
  cursor: pointer;

  &::before {
    position: absolute;
    inset: 0 auto 0 0;
    width: 4px;
    content: "";
    background: #0f766e;
  }

  span,
  strong,
  small {
    display: block;
  }

  span,
  small {
    color: var(--el-text-color-secondary);
  }

  strong {
    color: #0f766e;
    font-size: 32px;
    line-height: 1.05;
    font-variant-numeric: tabular-nums;
  }

  &.warning {
    background: #fffbeb;

    &::before {
      background: #d97706;
    }

    strong {
      color: #d97706;
    }
  }

  &.danger {
    background: #fef2f2;

    &::before {
      background: #dc2626;
    }

    strong {
      color: #dc2626;
    }
  }
}

.module-switcher {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 6px;

  button {
    display: grid;
    gap: 4px;
    min-width: 0;
    padding: 9px 10px;
    text-align: left;
    cursor: pointer;
    background: #ffffff;
    border: 1px solid var(--el-border-color-light);
    border-radius: 8px;
    transition:
      border-color 0.16s ease,
      box-shadow 0.16s ease,
      transform 0.16s ease;

    &:hover,
    &.active {
      border-color: rgb(20 184 166 / 40%);
      box-shadow: 0 6px 16px rgb(15 23 42 / 7%);
      transform: translateY(-1px);
    }

    &.active {
      background: linear-gradient(135deg, rgb(236 253 245 / 92%), #ffffff);

      span {
        color: #0f766e;
      }
    }
  }

  span,
  small {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    color: #111827;
    font-size: 14px;
    font-weight: 700;
  }

  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.inventory-workspace {
  display: grid;
  gap: 12px;
}

.workspace-pane {
  display: grid;
  gap: 12px;
}

.inventory-fade-enter-active,
.inventory-fade-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}

.inventory-fade-enter-from,
.inventory-fade-leave-to {
  opacity: 0;
  transform: translateY(4px);
}

.pane-grid,
.control-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(360px, 0.72fr);
  gap: 12px;
}

.panel {
  padding: 14px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;

  h2,
  p {
    margin: 0;
  }

  h2 {
    font-size: 17px;
    line-height: 1.35;
  }

  p {
    margin-top: 4px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }
}

.quick-control {
  align-self: start;
}

.overview-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
  margin-bottom: 12px;
}

.quick-actions-panel {
  .panel-head {
    margin-bottom: 8px;
  }
}

.workflow-steps {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(138px, 1fr));
  gap: 8px;
}

.workflow-step,
.todo-card {
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: #ffffff;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.16s ease;

  &:hover {
    border-color: rgb(20 184 166 / 38%);
    box-shadow: 0 8px 18px rgb(15 23 42 / 8%);
    transform: translateY(-1px);
  }
}

.workflow-step {
  display: grid;
  gap: 6px;
  min-height: 96px;
  padding: 10px;

  strong,
  small {
    display: block;
  }

  strong {
    color: #0f766e;
    font-size: 15px;
    line-height: 1.35;
  }

  small {
    display: none;
    color: var(--el-text-color-secondary);
    line-height: 1.45;
  }
}

.step-index {
  display: inline-grid;
  place-items: center;
  width: 26px;
  height: 26px;
  color: #ffffff;
  font-weight: 700;
  background: #0f766e;
  border-radius: 50%;
}

.todo-list {
  display: grid;
  gap: 10px;
}

.todo-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 11px;

  strong,
  small {
    display: block;
  }

  strong {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    margin-top: 3px;
    color: var(--el-text-color-secondary);
  }

  > span:last-child {
    color: #0f766e;
    font-weight: 700;
  }
}

.role-entry-panel {
  margin-bottom: 12px;

  .panel-head p {
    display: none;
  }
}

.role-entry-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.role-entry-card {
  display: grid;
  gap: 5px;
  min-height: 76px;
  padding: 10px;
  text-align: left;
  cursor: pointer;
  background: linear-gradient(135deg, #ffffff, rgb(236 253 245 / 64%));
  border: 1px solid rgb(20 184 166 / 16%);
  border-radius: 8px;
  transition:
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.16s ease;

  &:hover {
    border-color: rgb(20 184 166 / 42%);
    box-shadow: 0 8px 18px rgb(15 23 42 / 8%);
    transform: translateY(-1px);
  }

  span,
  strong,
  small {
    display: block;
  }

  span {
    color: #0f766e;
    font-size: 12px;
    font-weight: 700;
  }

  strong {
    color: #111827;
    font-size: 14px;
    line-height: 1.35;
  }

  small {
    display: none;
    color: var(--el-text-color-secondary);
    line-height: 1.45;
  }
}

.danger {
  color: #dc2626;
}

.table-toolbar {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(140px, 180px) minmax(140px, 180px);
  gap: 8px;
  margin-bottom: 10px;

  &.wide {
    grid-template-columns: minmax(240px, 1fr) minmax(130px, 160px) minmax(140px, 180px) minmax(260px, 320px);
  }
}

.executive-signal {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 112px;
  border-left: 6px solid #16a34a;
  background: linear-gradient(90deg, rgb(240 253 244 / 90%), #ffffff 64%);

  span,
  strong,
  small {
    display: block;
  }

  span {
    color: #0f766e;
    font-size: 13px;
    font-weight: 700;
  }

  strong {
    margin-top: 4px;
    color: #111827;
    font-size: 34px;
    line-height: 1.15;
    font-variant-numeric: tabular-nums;
  }

  small {
    margin-top: 6px;
    color: var(--el-text-color-regular);
  }

  &.warning {
    border-left-color: #d97706;
    background: linear-gradient(90deg, rgb(255 251 235 / 92%), #ffffff 64%);

    span {
      color: #b45309;
    }
  }

  &.danger {
    border-left-color: #dc2626;
    background: linear-gradient(90deg, rgb(254 242 242 / 92%), #ffffff 64%);

    span {
      color: #b91c1c;
    }
  }
}

.signal-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;

  span {
    padding: 7px 10px;
    color: #334155;
    background: rgb(255 255 255 / 76%);
    border: 1px solid var(--el-border-color-light);
    border-radius: 999px;
  }
}

.executive-kpis {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.executive-kpi {
  display: grid;
  gap: 5px;
  min-height: 118px;
  padding: 14px;
  text-align: left;
  cursor: pointer;
  background: #ffffff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: 0 1px 2px rgb(15 23 42 / 4%);
  transition:
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.16s ease;

  &:hover {
    border-color: rgb(20 184 166 / 36%);
    box-shadow: 0 8px 18px rgb(15 23 42 / 7%);
    transform: translateY(-1px);
  }

  span,
  strong,
  small {
    display: block;
  }

  span,
  small {
    color: var(--el-text-color-secondary);
  }

  strong {
    color: #0f766e;
    font-size: 38px;
    line-height: 1.05;
    font-variant-numeric: tabular-nums;
  }

  &.warning strong {
    color: #d97706;
  }

  &.danger strong {
    color: #dc2626;
  }
}

.executive-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(360px, 0.85fr);
  gap: 12px;
}

.panel-head.compact {
  margin-bottom: 8px;
}

.bar-list {
  display: grid;
  gap: 10px;
}

.bar-row {
  display: grid;
  grid-template-columns: minmax(86px, 120px) minmax(0, 1fr) 60px;
  align-items: center;
  gap: 10px;

  span,
  strong {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  div {
    height: 10px;
    overflow: hidden;
    background: #e2e8f0;
    border-radius: 999px;
  }

  i {
    display: block;
    height: 100%;
    background: linear-gradient(90deg, #0f766e, #14b8a6);
    border-radius: inherit;
  }

  strong {
    color: #0f766e;
    font-variant-numeric: tabular-nums;
    text-align: right;
  }
}

.compact-list {
  gap: 8px;
}

:deep(.row-flash) {
  animation: inventory-row-flash 1.1s ease;
}

@keyframes inventory-row-flash {
  0% {
    background: rgb(220 252 231 / 92%);
  }
  100% {
    background: transparent;
  }
}

.ml6 {
  margin-left: 6px;
}

.form-hint {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.45;
}

.request-steps {
  min-width: 220px;
}

.request-line-summary {
  display: grid;
  gap: 3px;

  strong,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

.request-lines-editor,
.issue-lines-editor {
  display: grid;
  width: 100%;
  gap: 10px;
}

.request-line-editor,
.issue-line-editor {
  display: grid;
  align-items: center;
  gap: 10px;
  padding: 10px;
  background: rgb(248 250 252 / 86%);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.request-line-editor {
  grid-template-columns: minmax(220px, 1fr) 160px 44px 54px;
}

.issue-line-editor {
  grid-template-columns: minmax(220px, 1fr) 180px;

  div {
    display: grid;
    gap: 3px;
  }

  strong,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}

:deep(.request-steps.el-steps--simple) {
  padding: 6px 8px;
  background: rgb(248 250 252 / 92%);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.weekly-assist {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr)) auto;
  gap: 8px;
  align-items: center;
  padding: 10px;
  margin: 0 0 14px 112px;
  background: rgb(240 253 250 / 72%);
  border: 1px solid rgb(20 184 166 / 16%);
  border-radius: 8px;

  div {
    display: grid;
    gap: 2px;
  }

  span {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  strong {
    color: var(--el-text-color-primary);
    font-size: 14px;
    font-variant-numeric: tabular-nums;
  }
}

@media (max-width: 1080px) {
  .inventory-hero,
  .pane-grid,
  .control-grid,
  .overview-grid {
    grid-template-columns: 1fr;
  }

  .inventory-hero {
    display: grid;
  }

  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .task-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .task-card {
    grid-column: 1 / -1;
  }

  .workflow-steps {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .module-switcher {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .role-entry-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .request-line-editor,
  .issue-line-editor {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }

  .task-strip {
    grid-template-columns: 1fr;
  }

  .hero-actions {
    flex-wrap: wrap;
  }

  .workflow-steps,
  .todo-card,
  .module-switcher,
  .role-entry-grid {
    grid-template-columns: 1fr;
  }

  .todo-card > span:last-child {
    justify-self: start;
  }

  .weekly-assist {
    grid-template-columns: 1fr;
    margin-left: 0;
  }
}
</style>
