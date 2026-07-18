# 进销存模块整体说明、PRD 与 SOP

文档版本：v1.0
对应代码提交：`b092a2d`
适用环境：MySQL 8.x + Spring Boot 应用 + Vue 管理端
文档用途：异地部署、压测、流程回看、验收和日常操作依据

## 1. 文档结论

本轮进销存改动完成了一个可运行的“科室使用套餐 + 病历复核自动扣减 + 失败可重试 + 全程留痕”闭环。

当前业务闭环如下：

```text
物资档案 -> 入库批次 -> 科室套餐版本 -> 启用套餐
                                      |
医生完成最终复核 -> 复核事务提交 -> 按次自动扣减 -> 库存流水/消耗明细/审计日志
                                      |
                            库存不足 -> FAILED 事件 -> 补货 -> 管理员重试
```

本版本重点解决：

- 门诊和住院可以分别配置科室使用套餐。
- 套餐按版本管理，同一科室、同一就诊类型同时只有一个启用版本。
- 医生最终复核成功后自动扣减，不需要人工重复录入。
- 同一 `encounterId + route` 重复触发不会重复扣减。
- 多物资扣减先做全量库存预检，库存不足时不发生部分扣减。
- 库存扣减异常不会回滚或阻断病历复核。
- 所有自动扣减都有消耗事件、批次明细、库存流水和审计记录。

## 2. 当前范围与明确边界

### 2.1 本轮已实现

- 物资档案、批次入库、库存查询和原有申领/发放/盘点能力继续保留。
- 套餐草稿保存、启用、停用、版本复制。
- 套餐范围：科室 + 门诊/住院。
- 套餐明细扣减模式：当前只允许 `per_visit`（每次就诊）。
- 医生最终复核提交后的自动扣减。
- 近效期优先（FEFO）批次扣减。
- 幂等、防重复扣减。
- 库存不足、未配置套餐、历史非法扣减模式的失败事件记录。
- 管理员/质控重试失败事件。
- 套餐和消耗事件按权限返回全院或本科室数据。
- 进销存接口的 Spring Boot 4 JSON 请求兼容处理。

### 2.2 本轮没有实现，不应在压测时假定已存在

- `per_admission`：每次入院触发。
- `per_day`：住院日触发。
- `per_procedure`：手术或操作触发。
- 住院床日自动扣减。
- 手术、检查项目级自动扣减。
- 已扣库存的业务撤销/冲正。
- 每日消耗 PDF/DOCX 纸质报表自动生成。
- 采购、供应商、发票、财务结算和成本会计。

如果要扩展以上能力，必须先增加触发事件定义、冲正规则和报表口径，不能只把前端选项重新放开。

## 3. 系统架构

### 3.1 访问入口

当前前端共有九个物资路由，均复用统一的进销存工作台：

- `/inventory/overview`：进销存主控台
- `/inventory/executive`：领导驾驶舱
- `/inventory/requests`：科室申领审批
- `/inventory/stock`：库存与批次
- `/inventory/items`：物资档案
- `/inventory/weekly`：周消耗预估
- `/inventory/packages`：使用套餐与自动扣减
- `/inventory/controls`：盘点与控制
- `/inventory/trace`：全链路追溯

- 后端进销存 API 前缀：`/inventory-api`
- 健康检查：`/health`

Docker 环境默认地址：`http://localhost:18080`。异地部署时将 `localhost` 替换为应用服务器地址。

### 3.2 后端组件

| 组件 | 作用 |
| --- | --- |
| `InventoryApiController` | HTTP 边界、权限校验、请求体转换 |
| `InventoryDatabaseService` | 统一编排库存、套餐、消耗事件读写 |
| `InventoryPackageService` | 套餐版本、启停、自动扣减、重试 |
| `InventoryStockWorkflow` | 物资档案、入库、发放、退回、盘点 |
| `InventoryRequestWorkflow` | 科室申领、审批、发放、签收 |
| `InventoryRepository` | 库存查询、批次锁定、流水和审计写入 |
| Flyway `V8__inventory_packages_and_consumption_events.sql` | 创建并升级套餐和自动消耗相关表；运行期服务不执行 DDL |
| `PreAiEncounterService` | 医生最终复核提交后注册自动扣减回调 |

### 3.3 数据表

已有基础表：

- `inventory_items`：物资档案。
- `inventory_batches`：物资批次和当前数量。
- `inventory_requests`、`inventory_request_lines`：科室申领和明细。
- `inventory_weekly_consumption`：周消耗登记。
- `inventory_counts`：盘点记录。
- `inventory_movements`：库存变动流水。
- `inventory_audit_logs`：审计日志。

本轮新增表：

- `inventory_packages`：套餐主表，包含科室、就诊类型、版本和状态。
- `inventory_package_lines`：套餐物资明细和按次数量。
- `inventory_consumption_events`：一次就诊的自动扣减事件，唯一键为 `encounter_id + route`。
- `inventory_consumption_details`：事件对应的具体物资和批次分配。

表结构由 Flyway 管控。当前完整结构以 `V1` 为基线，物资套餐和自动消耗相关升级位于 `V8`；应用启动时只校验迁移版本，不再通过库存服务执行建表或 `ALTER TABLE`。异地部署应先使用迁移账号完成升级，再使用无 DDL 权限的业务账号启动应用。

## 4. PRD

### 4.1 背景与目标

领导提出的核心目标是：

1. 每个科室提供自己的门诊和住院预估使用清单。
2. 使用清单固化为门诊套餐、住院套餐。
3. 套餐与患者就诊量绑定，自动计算并扣减库存。
4. 每一次扣减都留痕，可追溯、可复核，并为后续纸质日报提供数据基础。

本轮选择“每次就诊扣减”作为最小可验证闭环，先验证数据口径、权限、幂等和异常处理。

### 4.2 用户角色

| 角色 | 当前权限 |
| --- | --- |
| `admin` | 全院查看；维护物资档案、库存与套餐；启用/停用；失败事件重试 |
| `quality` | 全院查看；维护物资档案、库存与套餐；启用/停用；失败事件重试；质控复核 |
| `manager` | 可查看物资档案、驾驶舱、套餐和消耗事件；所有物资档案及库存操作只读 |
| 其他科室人员 | 不显示物资档案和库存维护入口；按角色使用本科室申领、签收、周消耗等已授权功能 |
| 医生 | 通过病历最终复核触发自动扣减，不需要额外点击扣减按钮 |

说明：物资档案新增/编辑、入库以及套餐写入、启停、手工消耗和失败重试接口均由后端强制要求 `admin` 或 `quality`。`manager` 即使直接访问 `/inventory/items` 也只能读取，不能通过直接请求绕过页面权限。医生的自动扣减由病历复核服务内部调用，不依赖医生直接访问库存写接口。

### 4.3 套餐状态机

```text
新建 -> draft -> enabled -> disabled
              ^       |
              |       +-- 同科室同就诊类型启用新版本时，旧版本自动 disabled
              +---------- 不能编辑 enabled，必须创建新版本
```

规则：

- `draft` 可修改。
- `enabled` 不允许直接修改；前端显示“创建新版本”。
- `disabled` 可作为历史版本查看，也可复制为新草稿。
- 同一科室、同一 `careType` 只能有一个启用套餐。
- `careType` 只允许 `outpatient` 或 `inpatient`。
- 套餐至少包含一条物资明细。
- 每条明细数量必须大于 0。
- 每条明细扣减方式固定为 `per_visit`。

### 4.4 自动扣减规则

医生确认最终复核后：

1. 病历复核事务先完成并提交。
2. 事务提交成功后，注册独立库存事务。
3. 按病历 `route` 归一化为 `outpatient` 或 `inpatient`。
4. 按“复核医生科室 + 就诊类型 + 生效日期”查找启用套餐。
5. 按 `encounterId + route` 查询历史事件。
6. 已成功、已处理或已反转事件直接返回，不重复扣减。
7. 读取套餐所有明细，先汇总每种物资的总需求。
8. 按近效期优先锁定批次并预检总库存。
9. 任一物资不足时，写入 `failed` 事件，任何物资都不扣减。
10. 库存足够时逐批扣减，写入库存流水和消耗明细。
11. 最终写入 `succeeded` 事件和审计日志。

### 4.5 失败事件规则

失败事件必须保留以下信息：

- `encounterId`
- `caseToken`
- `route`
- `department`
- `visitDate`
- `packageId`（如果已找到套餐）
- `status=failed`
- `errorMessage`
- 操作人和创建时间

常见失败原因：

- 没有找到该科室和就诊类型的启用套餐。
- 套餐中的某项物资库存不足。
- 历史套餐包含当前不支持的扣减模式。
- 手工重试参数不合法。

补货后由 `admin` 或 `quality` 在“自动消耗事件”列表点击重试。重试使用原事件 ID，不创建重复业务事件。

### 4.6 幂等与一致性要求

- 数据库唯一键：`uk_inventory_consumption_encounter_route (encounter_id, route)`。
- 自动扣减使用独立事务，库存失败不能阻断病历复核。
- 库存扣减前完成全量库存预检，禁止部分成功。
- 批次扣减使用行锁，优先使用临近有效期批次。
- 成功事件必须有至少一条 `inventory_consumption_details`。
- 每条自动扣减明细必须对应一条 `inventory_movements`，流水类型为 `auto_consume`。

## 5. API 对照表

所有接口使用请求头：

```http
x-access-token: <登录返回的 access_token>
Content-Type: application/json
```

### 5.1 查询

`GET /inventory-api/db`

返回当前权限范围内的：

- `items`
- `batches`
- `requests`
- `weeklyConsumptions`
- `counts`
- `movements`
- `packages`
- `consumptionEvents`
- `auditLogs`
- `summary`

### 5.2 物资和入库

`POST /inventory-api/items`

示例：

```json
{
  "id": "item-glove-s",
  "name": "一次性检查手套",
  "category": "耗材",
  "spec": "S",
  "unit": "双",
  "location": "主库",
  "lowStockThreshold": 20,
  "batchRequired": true,
  "expiryRequired": true,
  "enabled": true
}
```

`POST /inventory-api/inbounds`

```json
{
  "itemId": "item-glove-s",
  "quantity": 1000,
  "batchNo": "B20260718",
  "expiryDate": "2027-07-18",
  "location": "主库",
  "source": "供应商入库"
}
```

### 5.3 套餐管理

`POST /inventory-api/packages`

```json
{
  "id": "pkg-surgery-outpatient-v1",
  "name": "外科门诊基础套餐",
  "department": "外科",
  "careType": "outpatient",
  "effectiveDate": "2026-07-18",
  "lines": [
    {
      "id": "pkg-line-1",
      "itemId": "item-glove-s",
      "quantity": 2,
      "consumptionMode": "per_visit"
    }
  ]
}
```

`POST /inventory-api/packages/enable`

```json
{ "id": "pkg-surgery-outpatient-v1" }
```

`POST /inventory-api/packages/disable`

```json
{ "id": "pkg-surgery-outpatient-v1" }
```

### 5.4 自动消耗和重试

`POST /inventory-api/consumption-events`

该接口主要用于管理员/质控手工验证或补偿调用；生产主路径由医生最终复核内部触发。

```json
{
  "encounterId": "enc-20260718-0001",
  "caseToken": "case-20260718-0001",
  "route": "outpatient",
  "department": "外科",
  "visitDate": "2026-07-18"
}
```

`POST /inventory-api/consumption-events/retry`

```json
{ "id": "consume-xxxxxxxx" }
```

### 5.5 原有库存闭环接口

以下接口继续沿用原有库存申领流程：

- `POST /inventory-api/requests`
- `POST /inventory-api/requests/approve`
- `POST /inventory-api/requests/issue`
- `POST /inventory-api/requests/receive`
- `POST /inventory-api/requests/reject`
- `POST /inventory-api/requests/cancel`
- `POST /inventory-api/requests/void`
- `POST /inventory-api/weekly-consumptions`
- `POST /inventory-api/movements/return-or-scrap`
- `POST /inventory-api/counts`

## 6. 岗位 SOP

### 6.1 首次上线准备：管理员/质控

1. 登录系统，打开“进销存管理”。
2. 在“物资档案”建立统一物资编码、名称、规格、单位和库位。
3. 对需要批次或效期管理的物资勾选对应属性。
4. 在“库存与批次”录入初始入库，必须填写批次、数量和效期。
5. 进入“使用套餐与自动扣减”。
6. 按科室分别建立门诊套餐和住院套餐草稿。
7. 每条物资明细填写按次数量，确认数量大于 0。
8. 检查套餐科室、就诊类型、生效日期后启用。
9. 记录套餐版本号和启用时间，作为上线基线。

### 6.2 科室负责人

1. 查看本科室套餐和自动消耗事件。
2. 每周根据实际门诊/住院使用情况提出下一版套餐调整建议。
3. 不直接修改已启用套餐；提交调整内容给管理员或质控建立新版本。
4. 发现物资不足导致失败事件时，确认物资需求和补货优先级。
5. 对异常消耗、超量使用和特殊患者情况保留说明。

### 6.3 医生

1. 按正常流程完成患者事实采集和最终病历复核。
2. 点击“确认复核”后，系统在复核事务提交成功后自动尝试扣减。
3. 医生不需要重复点击库存扣减。
4. 如果库存不足，病历复核仍然完成；异常由管理员/质控在进销存模块处理。

### 6.4 管理员/质控处理失败事件

1. 打开“使用套餐与自动扣减”。
2. 筛选状态为 `failed` 的事件。
3. 查看科室、就诊类型、套餐、物资和失败原因。
4. 若为库存不足，先完成入库，再确认批次数量。
5. 点击“重试”。
6. 确认事件变为 `succeeded`，并检查库存流水和消耗明细。
7. 若仍失败，保留失败原因，不要反复无依据重试。

### 6.5 每日管理动作

| 时间 | 动作 | 结果 |
| --- | --- | --- |
| 上午 | 查看低库存、临期批次和失败事件 | 形成补货/处理清单 |
| 上午 | 查看前一日自动消耗事件 | 确认无大量失败或异常波动 |
| 下午 | 处理补货后的失败事件重试 | 失败事件闭环或留下原因 |
| 下班前 | 查看今日套餐扣减数量和库存余额 | 与业务访问量进行人工抽查 |

### 6.6 每周复核动作

1. 按科室导出或查看本周消耗事件。
2. 对比门诊/住院访问量和套餐理论消耗量。
3. 计算差异：

```text
理论消耗 = 门诊量 × 门诊套餐数量 + 住院量 × 住院套餐数量
差异 = 实际自动扣减量 - 理论消耗
```

4. 对差异较大的科室记录原因：套餐过时、临时加量、库存补录、失败事件未重试等。
5. 需要调整时创建下一版本套餐，不修改历史启用版本。

## 7. 异地部署与上线检查

### 7.1 拉取代码

```bash
git pull origin main
git rev-parse HEAD
```

应包含提交：

```text
b092a2d feat: add department inventory packages and auto consumption
```

### 7.2 Docker 部署

```bash
docker compose build app
docker compose up -d app
docker compose ps
```

确认 `app` 和 `mysql` 均为 `healthy`。

### 7.3 数据库检查

```sql
SHOW TABLES LIKE 'inventory_%';
SELECT COUNT(*) FROM inventory_packages;
SELECT COUNT(*) FROM inventory_consumption_events;
SELECT COUNT(*) FROM inventory_consumption_details;
```

必须存在：

- `inventory_packages`
- `inventory_package_lines`
- `inventory_consumption_events`
- `inventory_consumption_details`

### 7.4 最小上线验收

1. 登录管理员账号。
2. 建立一个临时物资并入库 5 个。
3. 建立每次扣减 2 个的门诊套餐并启用。
4. 对同一 `encounterId` 触发两次自动消耗。
5. 预期库存只减少 2 个，不减少 4 个。
6. 建立包含库存不足物资的套餐。
7. 触发一次，预期生成 `failed` 且其他物资不被部分扣减。
8. 补货后点击重试，预期事件变为 `succeeded`。
9. 清理临时物资、套餐和事件。

## 8. 压测方案

### 8.1 压测目标

压测不是只看接口平均响应时间，还必须验证库存业务不被并发破坏：

- 同一就诊并发请求只扣一次。
- 不同就诊并发请求不超卖。
- 库存不足不发生部分扣减。
- 重试不会创建重复事件。
- 失败事件可查询、可定位、可重试。
- 病历复核接口不被库存异常拖垮。

### 8.2 测试数据准备

建议准备：

- 10 个科室。
- 每个科室 1 个门诊套餐、1 个住院套餐。
- 每个套餐 5～20 条物资明细。
- 每种物资 3～5 个不同效期批次。
- 1 个库存充足场景。
- 1 个库存临界场景。
- 1 个库存不足场景。
- 1 个非法扣减模式历史数据场景。

压测数据必须使用独立前缀，例如 `loadtest-20260718-*`，压测结束按前缀清理，不能污染生产物资和套餐。

### 8.3 压测场景

| 场景 | 并发 | 重点指标 |
| --- | ---: | --- |
| `GET /inventory-api/db` | 20/50/100 | 响应时间、数据库读取压力 |
| 套餐列表读取 | 20/50/100 | JSON 体积、P95 |
| 同一就诊重复自动消耗 | 10/50/100 | 最终只存在 1 个事件、只扣 1 次 |
| 不同就诊自动消耗 | 20/50/100 | 总扣减量、死锁、超卖 |
| 库存不足 | 20/50 | FAILED 数量、零部分扣减 |
| 补货后重试 | 10/20 | 成功率、重复明细数 |
| 医生复核 + 库存异常 | 20/50 | 复核成功率、复核响应时间 |

### 8.4 建议指标

接口指标：

- 吞吐量 RPS。
- P50、P95、P99 响应时间。
- HTTP 4xx/5xx 比例。
- 超时率。

业务指标：

- `succeeded` 事件数。
- `failed` 事件数。
- 每个 `encounterId + route` 的事件数量，必须不超过 1。
- 自动扣减流水总量与消耗明细总量是否一致。
- 库存最终数量是否等于初始库存 + 入库 - 自动扣减 - 发放 + 退回/其他调整。
- 失败事件重试成功率。
- 库存不足场景下是否出现部分扣减。

数据库指标：

- 活跃连接数。
- 锁等待和死锁次数。
- `inventory_batches` 行锁等待。
- 慢查询。
- `inventory_consumption_events` 唯一键冲突。

### 8.5 压测后 SQL 对账

```sql
-- 是否存在重复业务事件
SELECT encounter_id, route, COUNT(*) AS c
FROM inventory_consumption_events
WHERE encounter_id LIKE 'loadtest-%'
GROUP BY encounter_id, route
HAVING COUNT(*) > 1;

-- 事件明细与自动扣减流水是否能对应
SELECT e.id, e.status, COUNT(d.id) AS detail_count
FROM inventory_consumption_events e
LEFT JOIN inventory_consumption_details d ON d.event_id = e.id
WHERE e.encounter_id LIKE 'loadtest-%'
GROUP BY e.id, e.status;

-- 自动扣减流水汇总
SELECT item_id, SUM(-quantity) AS consumed
FROM inventory_movements
WHERE movement_type = 'auto_consume'
  AND related_id LIKE 'consume-%'
GROUP BY item_id;

-- 失败事件
SELECT id, encounter_id, department, route, error_message, created_at
FROM inventory_consumption_events
WHERE encounter_id LIKE 'loadtest-%'
  AND status = 'failed'
ORDER BY created_at DESC;
```

### 8.6 压测通过标准

- 同一 `encounterId + route` 最终事件数量为 1。
- 同一就诊的库存扣减不超过套餐数量。
- 不同就诊合计扣减不超过可用库存。
- 库存不足事件不产生任何对应 `auto_consume` 流水。
- 重试成功后只产生一组成功明细。
- 无未处理死锁，5xx 率和超时率达到项目约定阈值。
- 医生复核成功率不因库存不足显著下降。

## 9. 故障处理与回滚

### 9.1 自动扣减大量失败

1. 先确认 `inventory_consumption_events.error_message` 的主要原因。
2. 如果是未配置套餐，检查科室、就诊类型和生效日期。
3. 如果是库存不足，先补货再重试。
4. 如果是非法扣减模式，不允许直接改启用版本，复制为新版本修正。

### 9.2 出现重复扣减迹象

1. 立即停止压测或手工重试。
2. 查询 `encounter_id + route` 是否出现重复记录。
3. 核对事件明细、库存流水和批次数量。
4. 保留数据库现场，不直接删除流水。
5. 由开发人员基于事件和流水做冲正方案；当前版本没有自动冲正功能。

### 9.3 回滚方式

代码回滚：

```bash
git checkout <previous-known-good-commit>
docker compose build app
docker compose up -d app
```

数据回滚不能通过删除成功事件实现。必须保留审计和流水，并由管理员按正式库存调整/盘点流程处理。

## 10. 验收清单

- [ ] 管理员可以创建物资、入库和套餐。
- [ ] 同科室同就诊类型只能有一个启用套餐。
- [ ] 已启用套餐只能创建新版本。
- [ ] 门诊和住院套餐可以分别配置。
- [ ] 医生最终复核后自动扣减。
- [ ] 同一就诊重复复核不重复扣减。
- [ ] FEFO 批次顺序正确。
- [ ] 库存不足不产生部分扣减。
- [ ] 失败事件能在页面看到失败原因。
- [ ] 补货后管理员/质控可以重试并成功闭环。
- [ ] 库存流水、消耗明细、审计日志能够关联到事件。
- [ ] `manager` 只能查看，不能维护套餐或重试事件。
- [ ] 异地部署后 Flyway 迁移到 `V8`，四张新增表存在且业务运行账号无 DDL 权限。
- [ ] 压测后通过 SQL 对账，无重复事件、超卖或孤立明细。

## 11. 后续迭代建议

建议按以下顺序扩展：

1. 先增加每日自动消耗汇总和 PDF/DOCX 留痕报表。
2. 再增加住院日触发，并定义入院、转科、出院、跨日边界。
3. 再增加手术/操作触发，并定义同一操作的幂等键。
4. 最后增加撤销、冲正和审计复核流程。

每增加一种触发方式，都必须同步补充：事件键、事务边界、库存不足策略、撤销规则、报表口径和压测场景。
