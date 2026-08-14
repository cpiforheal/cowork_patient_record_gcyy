# 正式生产单机上线运行手册

本文用于 Windows 单机、MySQL 8、前后端同机交付。所有路径、主机名和账号均需替换为生产值，禁止把真实密码提交到仓库。

## 1. 上线前门禁

1. 从保护分支或已归档快照确认当前工作区可恢复。
2. 前端执行 `pnpm validate`、`pnpm build-only:pro`、`pnpm exec playwright install chromium` 和 `pnpm test:e2e`。
3. 后端在可用 Docker 环境执行 `mvnw.cmd clean test`。Testcontainers MySQL 失败或未执行均视为门禁失败。
4. 确认 Flyway 脚本已在生产数据库副本演练，历史库从 baseline 1 升级，空库从 V1 初始化。
5. 同一时间点备份数据库、附件目录、当前交付包和脱敏后的配置清单，并完成独立目录恢复演练。
6. 确认代码和数据库副本中不存在原始密码、固定账号密码或可预测 AI 密钥。

## 2. 数据库账号

迁移账号与运行账号必须分离。可由数据库管理员按实际主机范围执行等价授权：

```sql
CREATE USER 'clinic_migrator'@'app-host' IDENTIFIED BY '<generated-secret>' REQUIRE SSL;
CREATE USER 'clinic_app'@'app-host' IDENTIFIED BY '<generated-secret>' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
  ON hos_refactor.* TO 'clinic_migrator'@'app-host';
GRANT SELECT, INSERT, UPDATE, DELETE
  ON hos_refactor.* TO 'clinic_app'@'app-host';
```

迁移完成后，应用连接池只能使用 `clinic_app`。用运行账号尝试 `CREATE TABLE` 必须失败。

## 3. 必需环境变量

以 `config/runtime.env.example` 为模板，生产至少配置：

- `SPRING_PROFILES_ACTIVE=mysql,prod`
- `MYSQL_URL`、`MYSQL_RUNTIME_USERNAME`、`MYSQL_RUNTIME_PASSWORD`
- `MYSQL_MIGRATION_URL`、`MYSQL_MIGRATION_USERNAME`、`MYSQL_MIGRATION_PASSWORD`
- `CLINIC_ATTACHMENT_DIR`、`CLINIC_BACKUP_MYSQLDUMP_PATH`
- `AI_CONFIG_SECRET`：随机生成并由密码库托管，不得与账号、目录或主机名相关

MySQL URL 必须使用 `sslMode=VERIFY_IDENTITY` 或 `sslMode=VERIFY_CA`，并部署受信任证书。生产启动会拒绝缺少安全连接、账号未分离或 AI 配置密钥缺失的配置。

仅空库第一次启动时临时提供 `CLINIC_BOOTSTRAP_ADMIN_PASSWORD`。管理员创建成功后立即移除该变量，并在首次登录时修改密码。已有数据库不得再次设置引导密码。

## 4. 备份与恢复演练

应用内数据库备份通过仅当前用户可读写的临时 option file 向 `mysqldump` 传递密码，不把密码放入进程命令行。人工演练推荐先交互式创建 MySQL login path：

```powershell
mysql_config_editor set --login-path=clinic_backup --host=db.internal.example --user=clinic_backup --password
mysqldump --login-path=clinic_backup --single-transaction --routines --triggers hos_refactor > D:\clinic-backup\db.sql
Compress-Archive -LiteralPath D:\hos_patient_record_runtime\attachments -DestinationPath D:\clinic-backup\attachments.zip
Copy-Item -LiteralPath D:\hos_patient_record_app -Destination D:\clinic-backup\release -Recurse
```

恢复演练必须在隔离数据库和独立附件目录进行：

```powershell
mysql --login-path=clinic_restore -e "CREATE DATABASE hos_refactor_restore CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
mysql --login-path=clinic_restore hos_refactor_restore < D:\clinic-backup\db.sql
Expand-Archive -LiteralPath D:\clinic-backup\attachments.zip -DestinationPath D:\clinic-restore
```

用恢复库启动一套隔离实例，验证登录、患者检索、病历读取、附件下载、打印任务与 `/health`，记录备份时间、恢复耗时、校验人和结果。没有恢复记录的备份不算通过上线门禁。

## 5. 发布与回滚

1. 停止旧服务，记录数据库版本和交付包版本。
2. 完成同一时间点备份，确认文件大小和可读性。
3. 部署新交付包，以迁移账号执行 Flyway，再由低权限运行账号连接。
4. 检查 `/health` 中数据库版本、附件可写空间、最近备份、AI 密钥状态和任务队列状态；响应不得出现密钥、密码或 Token。
5. 按角色验收导航、按钮和首页快捷入口；再验收旧 URL、大屏隐藏入口、会话重启、注销/改密失效、并发保存冲突和上传拦截。

如需回滚，先停止新服务。应用包可回退；数据库迁移不允许依赖 Flyway 自动降级，必须按已审核的恢复方案恢复数据库与同一时间点附件。恢复后再次执行完整业务冒烟测试。

## 6. 日常运行检查

- 每日检查 `/health`、备份结果、磁盘空间、AI 熔断/失败率和异步任务积压。
- 每周抽样恢复一次数据库与附件；定期轮换管理员、数据库及 AI 凭据。
- 账号停用、密码修改或疑似泄露后，验证该用户现有会话立即失效。
- 上传失败、AI 失败和打印失败必须保留页面内失败原因与重试入口，不能只依赖 Toast。
