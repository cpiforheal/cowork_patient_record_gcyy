# 协和患者病历协同系统

本项目是前后端分离的患者病历协同系统：

- 后端：`coshare_patientrecord_sys_backend`，Spring Boot，默认端口 `8080`
- 前端：`coshare_patientrecord_sys_frontend\Geeker-Admin`，Vue 3 + Vite，默认端口 `8848`
- 本机数据库：MySQL，脚本默认使用 `127.0.0.1:3307`
- 本机运行目录：`%USERPROFILE%\hos_cowork_runtime`

## 一键启动

在项目根目录双击：

```text
start-local.bat
```

或在 PowerShell 中执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-local.ps1
```

脚本会自动完成：

1. 初始化并启动本机 MySQL `3307`
2. 创建数据库 `hos_refactor`
3. 创建数据库账号 `cowork / Cowork_2026!`
4. 启动后端 `http://localhost:8080/`
5. 补齐管理员账号
6. 启动前端 `http://localhost:8848/`

登录账号：

```text
admin / Init@Coshare2026!
```

## 前置依赖

本机需要已有：

- Java 17
- MySQL Server 8 或 9
- Node.js
- pnpm

当前机器已验证这些依赖可用。

## 免环境便携包

如需部署到单位内网公用主机，可在开发机项目根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\package-portable.ps1
```

脚本会生成：

```text
release\clinic-portable
```

该目录包含便携 JDK、便携 MySQL、后端 jar、前端静态文件和启动脚本。复制整个 `clinic-portable` 目录到目标主机后，目标主机只需双击：

```text
start.bat
```

内网电脑访问：

```text
http://目标主机IP:8848/
```

如果开发机没有可复制的 JDK 或 MySQL 运行目录，可手动传入压缩包：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\package-portable.ps1 `
  -JdkZip D:\runtime\jdk-17.zip `
  -MysqlZip D:\runtime\mysql-8.zip
```

便携包数据保存在 `data\mysql` 和 `data\attachments`，不会因为免安装而变成临时数据。

## 物理备份

管理员登录后，首页会显示“物理备份”配置。填写目标主机可写的本机路径或 UNC 共享路径，例如：

```text
D:\clinic-backup
\\192.168.1.10\clinic-backup
```

保存后系统每天 `02:00` 自动备份，也可以点击“立即备份”。备份 zip 包包含：

- 数据库 SQL dump
- 附件目录
- 当前业务 JSON 快照
- manifest 元数据

当前保留策略为：最近 7 天、最近 4 周每周 1 份、最近 12 月每月 1 份。

## 常用地址

```text
前端页面：http://localhost:8848/
后端接口：http://localhost:8080/
运行日志：%USERPROFILE%\hos_cowork_runtime\logs
附件目录：%USERPROFILE%\hos_cowork_runtime\clinic-attachments
```

## 手动启动方式

通常不需要手动启动。若脚本失败，优先查看：

```text
%USERPROFILE%\hos_cowork_runtime\logs\backend.err.log
%USERPROFILE%\hos_cowork_runtime\logs\frontend.err.log
%USERPROFILE%\hos_cowork_runtime\logs\mysql.err.log
```

前端单独启动：

```powershell
cd .\coshare_patientrecord_sys_frontend\Geeker-Admin
pnpm install
pnpm dev -- --host 0.0.0.0 --port 8848
```

后端单独启动需要 MySQL 已可用：

```powershell
cd .\coshare_patientrecord_sys_backend
java -jar .\target\coshare_patientrecord_sys-0.0.1-SNAPSHOT.jar `
  --server.port=8080 `
  --spring.profiles.active=mysql `
  --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/hos_refactor?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" `
  --spring.datasource.username=cowork `
  --spring.datasource.password=Cowork_2026!
```
