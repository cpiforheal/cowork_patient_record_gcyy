# 内测部署检查清单

本文档用于门诊病历协同系统的内网小范围试运行。目标是让系统在目标主机上稳定启动、可访问、可写入、可追溯，并且出现问题时能够快速定位。

## 一、目标主机最低准备

- Windows 主机一台，建议固定内网 IP。
- JDK 17。
- MySQL 8.x，已创建业务库，例如 `hos_refactor`。
- 大容量数据盘目录，例如 `D:\hos_patient_record_runtime\attachments`，用于保存上传附件。
- 建议准备独立备份目录，或接入单位已有备份策略。

## 二、开发机打包

在项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\package-pilot.ps1
```

默认输出目录：

```text
release\pilot
```

发布包内包含：

- `backend\app.jar`：后端服务。
- `frontend\`：前端静态文件。
- `start-backend.cmd`：后端启动脚本。
- `env.example.txt`：目标主机环境变量示例。
- `tools\check-pilot-host.ps1`：目标主机自检脚本。
- `runtime\`：运行期目录占位。

## 三、目标主机配置

建议将发布包放到固定目录，例如：

```text
D:\hos_patient_record_app
```

启动前确认以下环境变量或启动脚本配置：

| 配置项 | 示例 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 后端端口 |
| `MYSQL_URL` | `jdbc:mysql://localhost:3306/hos_refactor?...` | MySQL 连接地址 |
| `MYSQL_USERNAME` | `root` | MySQL 用户 |
| `MYSQL_PASSWORD` | `123456` | MySQL 密码 |
| `CLINIC_ATTACHMENT_DIR` | `D:\hos_patient_record_runtime\attachments` | 附件保存目录 |

## 四、启动与自检

启动后端：

```cmd
start-backend.cmd
```

在发布包根目录执行目标主机自检：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\check-pilot-host.ps1
```

重点看以下结果：

- Java 是否为 17 或更高兼容版本。
- MySQL 端口是否可连接。
- 附件目录是否可写。
- `/health` 是否返回 `ok`。
- `/health/db` 是否返回 `ok`。

## 五、前端发布

前端静态文件位于：

```text
release\pilot\frontend
```

可使用 Nginx、IIS 或其他静态服务发布。需要把以下请求反向代理到后端：

```text
/clinic-api/*
/health*
```

## 六、试运行验收动作

上线前至少走一遍完整闭环：

1. 登录系统。
2. 新建患者。
3. 填写门诊病历字段。
4. 上传一份附件。
5. 关闭浏览器后重新进入，确认患者、病历、附件仍可查看。
6. 重启后端服务，重复查看。
7. 查看操作日志是否保留关键动作。
8. 使用另一台内网电脑访问目标主机 IP，重复查询与保存动作。

## 七、回滚与备份

- 发布前备份 MySQL 数据库。
- 发布前备份附件目录。
- 每次发布保留上一版 `release` 目录。
- 如果新版本异常，先停止后端服务，再切回上一版 jar 和前端静态文件。

## 八、当前仍需关注

- 当前系统已具备 `_revision` 写入保护，但部分业务仍通过整库保存接口完成，后续建议逐步拆为行级保存接口。
- 前端 Sass 存在旧版 `@import` 构建警告，不影响当前运行，但建议后续升级。
- 内测阶段应明确账号权限、主机访问范围、数据备份频率和附件目录权限，避免敏感资料散落。
