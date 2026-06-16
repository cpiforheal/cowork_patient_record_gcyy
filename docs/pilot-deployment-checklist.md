# 内测部署检查清单

本文档用于协和患者病历协同系统的内网试运行。目标是让非开发人员也能按步骤完成部署、检查、启动和基础排错。

## 一、目标主机准备

建议使用一台固定内网 IP 的 Windows 主机，作为系统运行主机。

必须准备：

- JDK 17。
- MySQL 8.x。
- 一个业务数据库，例如 `hos_refactor`。
- 一个长期保存附件的大容量磁盘目录，例如 `D:\hos_patient_record_runtime\attachments`。
- 防火墙放行系统端口，默认 `8080`。

建议准备：

- 独立备份目录或单位已有备份方案。
- 固定内网 IP，避免使用 DHCP 临时地址。
- 普通用户无法随意删除的附件目录权限。

## 二、在开发机生成交付包

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

## 三、目标主机第一次配置

打开：

```text
config\runtime.env
```

重点确认：

| 配置项 | 示例 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 系统访问端口 |
| `MYSQL_URL` | `jdbc:mysql://localhost:3306/hos_refactor?...` | 数据库地址和库名 |
| `MYSQL_USERNAME` | `root` | 数据库账号 |
| `MYSQL_PASSWORD` | `123456` | 数据库密码 |
| `CLINIC_ATTACHMENT_DIR` | `D:\hos_patient_record_runtime\attachments` | 病历附件保存目录 |

如果 MySQL 不在本机，把 `MYSQL_URL` 中的 `localhost` 改成数据库主机 IP。

## 四、检查、启动、访问

按顺序操作：

1. 双击 `01-check-host.bat`。
2. 如果有失败项，按脚本底部“下一步建议”处理。
3. 双击 `02-start-system.bat`。
4. 本机浏览器访问 `http://localhost:8080/`。
5. 其他内网电脑访问 `http://目标主机IP:8080/`。

停止系统时，双击：

```text
03-stop-system.bat
```

## 五、上线前验收动作

至少完整走一遍：

1. 登录系统。
2. 新建患者。
3. 填写门诊病历并保存。
4. 上传一张图片或一个 PDF。
5. 关闭浏览器后重新进入，确认数据仍在。
6. 停止系统再启动，确认数据仍在。
7. 在另一台内网电脑访问主机 IP，确认可以查询和保存。
8. 再次运行 `01-check-host.bat`，确认数据库连接、附件目录和维护状态正常。

## 六、常见问题

### 页面打不开

先运行 `01-check-host.bat`。

常见原因：

- Java 未安装或版本低于 17。
- 系统没有启动。
- 8080 端口被占用。
- Windows 防火墙没有放行 8080。

日志位置：

```text
logs\backend.err.log
logs\backend.out.log
```

### 数据库连接失败

检查：

- MySQL 服务是否启动。
- 数据库 `hos_refactor` 是否存在。
- `config\runtime.env` 中账号密码是否正确。
- `MYSQL_URL` 中的主机、端口、库名是否正确。

### 上传失败

检查：

- `CLINIC_ATTACHMENT_DIR` 是否存在且可写。
- 磁盘空间是否充足。
- 文件是否为允许的图片或 PDF。
- 单个文件是否超过配置大小。

### 内网其他电脑访问不了

检查：

- 是否使用目标主机的真实内网 IP。
- 目标主机防火墙是否放行 `SERVER_PORT`。
- 目标主机和访问电脑是否在同一内网。

## 七、备份和回滚

每次升级前至少备份：

- MySQL 数据库。
- `CLINIC_ATTACHMENT_DIR` 附件目录。
- 上一版 `clinic-pilot-package` 文件夹。

回滚时：

1. 停止当前系统。
2. 恢复上一版交付包。
3. 如有必要，恢复数据库和附件目录。
4. 启动上一版系统并做验收动作。

## 八、内测阶段管理建议

- 明确谁负责目标主机、数据库、附件目录和备份。
- 内测账号只分配给实际参与人员。
- 每周至少检查一次磁盘剩余空间。
- 发生误删、上传失败、无法登录等问题时，先保留日志和截图，再处理。
- 医疗资料属于敏感信息，不建议把交付包、数据库备份或附件目录复制到个人电脑长期保存。
