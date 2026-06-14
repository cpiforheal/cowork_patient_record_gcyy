# cowork_patient_record_gcyy

## Current working structure

Business development now lives in:

- `coshare_patientrecord_sys_backend/`: Spring Boot backend, MySQL profile enabled by default.
- `coshare_patientrecord_sys_frontend/Geeker-Admin/`: Vue 3 frontend.

Frontend package management is standardized on `pnpm@8.15.9`. Keep `pnpm-lock.yaml` as the only frontend lock file; do not commit `package-lock.json`.

The current MySQL persistence keeps the original `/clinic-api/db` payload contract while also splitting high-value business data into normalized tables:

- `clinic_patients`: patient master rows.
- `clinic_patient_encounters`: repeat outpatient/inpatient visits for the same patient.
- `clinic_record_fields`: compatibility snapshot for record field JSON.
- `clinic_record_field_values`: per-field record values for later permission, quality-control, and search work.
- `clinic_documents`, `clinic_archive`, `clinic_audit_logs`, `clinic_accounts`, `clinic_roles`, `clinic_departments`, `clinic_dictionaries`, `clinic_template_field_rules`: business supporting tables.

协作病历资料系统原型仓库。

当前有效工程已经迁移为前后端分离结构：

- `coshare_patientrecord_sys_backend/`：Spring Boot 后端，默认使用 MySQL，并提供 `/clinic-api` 数据与附件接口。
- `coshare_patientrecord_sys_frontend/Geeker-Admin/`：Vue 3 前端，开发环境通过 Vite 代理访问 `http://localhost:8080/clinic-api`。

根目录下保留的 `Geeker-Admin/`、`server/` 和 `docs/` 是迁移前的 Node 本地数据服务版本，作为历史参考保留；新的业务开发优先进入 `coshare_patientrecord_sys_backend/` 和 `coshare_patientrecord_sys_frontend/`。

## 后端启动

```bash
cd coshare_patientrecord_sys_backend
mvnw.cmd spring-boot:run
```

默认配置见 `src/main/resources/application-mysql.properties`：

- 服务端口：`8080`
- 数据库：`hos_unitywork`
- 用户名：`root`
- 密码：`123456`

可通过环境变量覆盖：`SERVER_PORT`、`MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`、`CLINIC_ATTACHMENT_DIR`。

后端启动后可访问：

- `GET /health`
- `GET /health/db`
- `GET /clinic-api/db`
- `PUT /clinic-api/db`
- `POST /clinic-api/files`
- `GET /clinic-api/files/{storagePath}`

## 前端启动

```bash
cd coshare_patientrecord_sys_frontend/Geeker-Admin
pnpm install
pnpm dev
```

开发环境配置在 `.env.development` 中，`/clinic-api` 会代理到 `http://localhost:8080`。如果只启动前端而没有启动 Spring Boot 后端，上传病历附件和数据库读写会失败。

## 运行数据

本地运行数据和附件不进入 Git：

- `coshare_patientrecord_sys_backend/runtime/`
- `coshare_patientrecord_sys_frontend/server/data/clinic-db.json`
- `coshare_patientrecord_sys_frontend/server/files/`
- `coshare_patientrecord_sys_frontend/Geeker-Admin/server/data/clinic-db.json`
