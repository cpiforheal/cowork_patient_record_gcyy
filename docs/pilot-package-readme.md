# 协和患者病历协同系统 - 内测交付包

这份目录可以直接放到内测主机上使用。为了照顾非开发人员，日常操作只保留三个入口：

1. `01-check-host.bat`：检查 Java、MySQL、系统端口、附件目录、后端服务和数据库连接。
2. `02-start-system.bat`：启动系统，并自动打开浏览器。
3. `03-stop-system.bat`：停止系统后台服务。

## 第一次使用

1. 把整个文件夹复制到目标主机，例如 `D:\hos_patient_record_app`。
2. 打开 `config\runtime.env`。
3. 确认数据库密码 `MYSQL_PASSWORD`。
4. 确认附件目录 `CLINIC_ATTACHMENT_DIR`，建议放在大容量磁盘。
5. 双击 `01-check-host.bat`。
6. 检查通过后，双击 `02-start-system.bat`。
7. 本机访问 `http://localhost:8080/`。
8. 其他内网电脑访问 `http://目标主机IP:8080/`。

## 目录说明

- `backend\app.jar`：后端服务。
- `frontend\`：前端页面文件，启动后由后端统一托管。
- `config\runtime.env`：运行配置。
- `runtime\attachments`：默认运行目录；正式内测建议改到大容量磁盘。
- `logs\`：系统运行日志。
- `docs\`：部署清单和常见问题。

## 常见处理

- 页面打不开：先运行 `01-check-host.bat`，再看 `logs\backend.err.log`。
- 数据库不通：确认 MySQL 已启动、库名存在、`runtime.env` 密码正确。
- 上传失败：确认附件目录可写、磁盘空间足够、文件类型为图片或 PDF。
- 内网电脑访问不了：确认目标主机防火墙放行 `SERVER_PORT`，默认 8080。

## 内测验收

上线前至少完成一次：登录、新建患者、填写病历、上传附件、关闭浏览器后复查、重启系统后复查、另一台内网电脑访问复查。
