# 环境适配与部署交接

本文面向信息科、系统管理员和交付人员，用于把系统交到内网主机上运行。

## 推荐部署方式

第一轮内测建议采用单机内网部署：

- 一台固定 Windows 主机运行后端服务。
- 前端静态文件由后端统一托管。
- 业务数据写入 MySQL。
- 病历附件保存到指定磁盘目录。
- 科室电脑通过浏览器访问内测主机 IP。

这种方式部署简单、排错路径短，适合第一轮交付和小范围内测。

## 目标主机要求

| 项目 | 要求 |
| --- | --- |
| 操作系统 | Windows 主机优先 |
| Java | JDK 17 |
| 数据库 | MySQL 8.x |
| 浏览器 | Chrome 或 Edge 最新稳定版 |
| 网络 | 固定内网 IP，科室电脑可访问 |
| 端口 | 默认 `8080`，需防火墙放行 |
| 磁盘 | 预留附件目录，建议放在大容量磁盘 |

Node.js 和 pnpm 只在开发机或打包机需要。目标内测主机如果只运行交付包，不需要安装 Node.js 和 pnpm。

## 交付包生成

在项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\package-pilot.ps1
```

生成目录：

```text
release\clinic-pilot-package
```

把整个 `clinic-pilot-package` 文件夹复制到目标主机，例如：

```text
D:\hos_patient_record_app
```

## 运行配置

目标主机第一次使用前，打开：

```text
config\runtime.env
```

重点确认：

| 配置项 | 说明 |
| --- | --- |
| `SERVER_PORT` | 系统访问端口，默认 `8080` |
| `MYSQL_URL` | MySQL 地址、端口和数据库名 |
| `MYSQL_USERNAME` | 数据库账号 |
| `MYSQL_PASSWORD` | 数据库密码 |
| `CLINIC_ATTACHMENT_DIR` | 病历附件保存目录 |
| `CLINIC_FRONTEND_DIR` | 交付包通常由启动脚本自动指定 |

附件目录建议使用绝对路径，例如：

```text
D:\hos_patient_record_runtime\attachments
```

## 启动与停止

按顺序操作：

1. 双击 `01-check-host.bat`。
2. 检查通过后双击 `02-start-system.bat`。
3. 本机访问 `http://localhost:8080/`。
4. 其他内网电脑访问 `http://目标主机IP:8080/`。
5. 停止系统时双击 `03-stop-system.bat`。

如果检查脚本提示失败，先按脚本底部建议处理，再启动系统。

## 必须备份的内容

每次升级前、内测阶段每周至少备份一次：

- MySQL 数据库。
- `CLINIC_ATTACHMENT_DIR` 指向的附件目录。
- 当前正在使用的交付包目录。
- `config\runtime.env` 配置文件。

只备份数据库不能恢复附件，只备份附件也不能恢复业务记录，两者必须成套保存。

## 常见问题排查

### 页面打不开

先运行 `01-check-host.bat`，再查看：

```text
logs\backend.err.log
logs\backend.out.log
```

常见原因：

- Java 版本低于 17。
- 服务没有启动。
- `8080` 端口被占用。
- 防火墙未放行。

### 数据库连接失败

检查：

- MySQL 服务是否启动。
- 数据库名是否存在。
- `MYSQL_URL` 是否指向正确主机和端口。
- 数据库账号和密码是否正确。

### 附件上传失败

检查：

- `CLINIC_ATTACHMENT_DIR` 是否存在。
- 当前用户是否有写入权限。
- 磁盘空间是否充足。
- 文件类型是否为系统允许的图片或 PDF。

### 其他电脑访问不了

检查：

- 是否使用目标主机真实内网 IP。
- 访问电脑和目标主机是否在同一内网。
- Windows 防火墙是否放行 `SERVER_PORT`。
- 后端服务是否仍在运行。

## 回滚原则

如果升级后出现影响业务使用的问题：

1. 停止当前系统。
2. 恢复上一版交付包。
3. 必要时恢复对应数据库和附件目录备份。
4. 重新启动并走一遍登录、新建患者、保存病历、上传附件、进销存闭环验收。

更详细的部署步骤可继续参考 [内测部署检查清单](../pilot-deployment-checklist.md) 和 [内测交付包说明](../pilot-package-readme.md)。

后端请求定位、健康检查和启动自检可参考 [后端上线排障与可观测性清单](../backend-observability-checklist.md)。
