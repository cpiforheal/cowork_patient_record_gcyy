# 协和患者病历协同系统

本项目是面向门诊一线生产场景的患者病历协同系统，当前已重构为前后端分离架构：

- `coshare_patientrecord_sys_backend/`：Spring Boot 后端，负责业务数据、附件上传下载、健康检查和运行维护接口。
- `coshare_patientrecord_sys_frontend/Geeker-Admin/`：Vue 3 前端，负责患者登记、病历填写、附件查看、账号与操作记录等工作台界面。
- `tools/`：内测打包、环境检查等交付工具。
- `docs/`：部署清单、验收动作和运行建议。

## 当前交付定位

系统优先满足内网门诊试运行：一台固定主机部署后端、前端和附件目录，科室电脑通过浏览器访问该主机 IP 使用系统。数据写入 MySQL，病历附件落到指定磁盘目录，便于后续备份、排查和审计。

内测部署包会把前端静态文件和后端 jar 放在同一个交付目录中，目标主机启动后端即可同时访问页面和接口，降低非开发人员部署难度。

## 开发环境

建议准备：

- JDK 17
- MySQL 8.x
- Node.js 18 或更高版本
- pnpm

后端配置主要通过环境变量覆盖：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 系统访问端口 |
| `MYSQL_URL` | `jdbc:mysql://localhost:3306/hos_refactor?...` | MySQL 连接地址 |
| `MYSQL_USERNAME` | `root` | 数据库账号 |
| `MYSQL_PASSWORD` | `123456` | 数据库密码 |
| `CLINIC_ATTACHMENT_DIR` | `runtime/clinic-attachments` | 附件保存目录 |
| `CLINIC_FRONTEND_DIR` | 空 | 内测包中由启动脚本自动指向前端目录 |

## 本地启动

后端：

```powershell
cd coshare_patientrecord_sys_backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=mysql
```

前端：

```powershell
cd coshare_patientrecord_sys_frontend\Geeker-Admin
pnpm install
pnpm dev
```

## 生成内测交付包

在项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\package-pilot.ps1
```

默认输出目录：

```text
release\clinic-pilot-package
```

交付包包含：

- `01-check-host.bat`
- `02-start-system.bat`
- `03-stop-system.bat`
- `config\runtime.env`
- `backend\app.jar`
- `frontend\`
- `logs\`
- `docs\`
- `README.md`

目标主机第一次使用时，先编辑 `config\runtime.env` 中的数据库密码和附件目录，再双击 `01-check-host.bat`。检查通过后双击 `02-start-system.bat`，本机访问 `http://localhost:8080/`，其他内网电脑访问 `http://目标主机IP:8080/`。

更完整的部署步骤见 [docs/pilot-deployment-checklist.md](docs/pilot-deployment-checklist.md)。

## 内测前必须验收

至少走通以下闭环：

1. 登录系统。
2. 新建患者。
3. 填写门诊病历字段并保存。
4. 上传图片或 PDF 附件。
5. 关闭浏览器后重新进入，确认患者、病历和附件仍可查看。
6. 重启系统后再次确认数据未丢失。
7. 在另一台内网电脑访问目标主机 IP，确认查询、保存和上传正常。
8. 运行 `01-check-host.bat`，确认数据库、附件目录和维护状态可检查。

## 运行维护建议

- MySQL 数据库和附件目录必须一起备份，只备份其中一个都不能完整恢复病历资料。
- 附件目录建议放在大容量磁盘，例如 `D:\hos_patient_record_runtime\attachments`。
- 内测主机建议固定 IP，并限制在单位内网访问。
- 每次升级前保留上一版交付包、数据库备份和附件目录备份。
- 如果页面打不开，先运行交付包里的 `01-check-host.bat`，再查看 `logs\backend.err.log`。

## 当前仍需关注

- 内测阶段已尽量降低部署门槛，但目标主机仍需要 Java 17、MySQL 8.x 和基础防火墙放行。
- 医疗资料包含敏感信息，试运行也应明确账号权限、主机访问范围、备份责任和日志留存规则。
- 后续如果进入更大范围生产，建议继续补强自动备份、权限审计、账号密码策略和数据库迁移脚本。
