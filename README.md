# 协和患者病历协同系统

本仓库当前为前后端分离版本：

- `coshare_patientrecord_sys_backend/`：Spring Boot 后端，使用 MySQL 保存患者、病历、附件索引、账号、角色、审计日志等数据。
- `coshare_patientrecord_sys_frontend/Geeker-Admin/`：Vue 3 前端，面向内部门诊生产场景使用。
- `runtime/`：本机运行期数据目录，建议放置附件等运行文件，不纳入 Git。

## 内测部署边界

目标主机最低需要准备：

1. JDK 17。
2. MySQL 8.x，并创建数据库，例如 `hos_refactor`。
3. 一个可长期保留的附件目录，建议放在大容量磁盘，并纳入备份计划。
4. 前端静态文件部署服务，可使用 Nginx、IIS 或后端同机静态服务。

## 后端启动

进入后端目录：

```bash
cd coshare_patientrecord_sys_backend
```

使用 MySQL profile 启动：

```bash
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=mysql
```

常用配置可通过环境变量覆盖：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 后端端口 |
| `MYSQL_URL` | `jdbc:mysql://localhost:3306/hos_refactor?...` | MySQL 连接地址 |
| `MYSQL_USERNAME` | `root` | MySQL 用户名 |
| `MYSQL_PASSWORD` | `123456` | MySQL 密码 |

后端健康检查：

```bash
curl http://localhost:8080/health/db
```

## 前端打包

进入前端目录：

```bash
cd coshare_patientrecord_sys_frontend/Geeker-Admin
pnpm install
pnpm build:pro
```

生产配置默认访问同源接口：

- `/clinic-api/db`
- `/clinic-api/files`

如前端和后端不在同一域名或端口，需要在网关或静态服务中配置反向代理。

## 数据与附件

- MySQL 保存业务结构化数据。
- 附件文件应存放在目标主机固定磁盘目录，不应放入 Git。
- 医疗资料包含敏感信息，内测阶段也要做账号隔离、主机访问控制、磁盘备份和操作审计。
- 当前附件下载已禁用公共长期缓存，减少病历附件在浏览器或中间代理中长期残留。

## 当前注意事项

- 系统仍保留整库保存接口 `/clinic-api/db`，已增加 `_revision` 版本保护，能降低多终端旧页面覆盖新数据的风险。
- 下一阶段建议把高频写入动作拆成行级接口，例如新建患者、保存病历字段、上传附件、账号角色维护，逐步替代整库 PUT。
- 内测前建议在目标主机做一次完整演练：建库、启动后端、部署前端、创建患者、上传附件、保存病历、重启服务后复查数据。
