# Clinic Data Contract

This document records the frontend/backend contract for the outpatient record prototype after the Spring Boot + MySQL migration.

## API Service

Start the backend from `coshare_patientrecord_sys_backend/`:

```bash
mvnw.cmd spring-boot:run
```

The service listens on `http://localhost:8080` by default. The Vue development server proxies `/clinic-api` to this backend in `Geeker-Admin/.env.development`.

All JSON API responses share the `{ code, msg, data }` envelope. `code` mirrors the HTTP status; `data` is `null` on error.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Service health check. |
| `GET` | `/health/db` | MySQL connection health check. |
| `GET` | `/clinic-api/db` | Read the current clinic database as the frontend document shape. |
| `PUT` | `/clinic-api/db` | Replace the current clinic database. The backend persists the document into MySQL tables and writes a snapshot. |
| `POST` | `/clinic-api/files` | Upload one attachment encoded as a data URL. |
| `GET` | `/clinic-api/files/{storagePath}` | Download a previously stored attachment by the `storagePath` returned from upload. |

## Upload / Download

- `POST /clinic-api/files` accepts `{ fileName, contentDataUrl }`, where `contentDataUrl` is a base64 data URL like `data:image/png;base64,...`.
- It responds with `{ fileName, url, storagePath, size, mimeType }`.
- `url` is the public path (`/clinic-api/files/{storagePath}`); `storagePath` is a date-foldered name (`YYYY/MM/DD/<timestamp>-<rand>-<safeName>`).
- Files are stored on disk under `clinic.attachment-dir`, which defaults to `runtime/clinic-attachments` in the backend project.
- The download route validates that the resolved path stays inside the attachments directory, rejecting path traversal attempts.

## Data Shape

The frontend continues to read and write one clinic document:

| Key | Meaning |
| --- | --- |
| `accounts` | Login account data. The current prototype keeps `admin` as the baseline account. |
| `patients` | Patient list rows for workbench, patient list, and encounter views. |
| `records` | Patient record field values keyed by patient id. |
| `documents` | Uploaded evidence and report metadata keyed by patient id. |
| `roles` | Role and permission baseline. |
| `departments` | Department options. |
| `dictionaries` | Common dictionaries and preset options. |
| `templateFieldRules` | Field ownership, required-state, printability, and quality-check rules. |
| `archive` | Submission state and generated record version keyed by patient id. |
| `auditLogs` | Operation trail for upload, edit, review, and archive actions. |

## MySQL Persistence

The backend creates these tables automatically when the `mysql` profile is active:

- `clinic_patients`
- `clinic_record_fields`
- `clinic_archive`
- `clinic_documents`
- `clinic_accounts`
- `clinic_roles`
- `clinic_departments`
- `clinic_dictionaries`
- `clinic_template_field_rules`
- `clinic_audit_logs`
- `clinic_db_snapshots`

The phase-1 migration keeps important query columns plus a `raw_json` column for lossless frontend round-trip compatibility. `PUT /clinic-api/db` currently replaces the table contents and records the submitted payload in `clinic_db_snapshots`.

## Implementation Notes

- Keep role and field ownership in persisted schema data instead of hard-coding it across UI pages.
- Keep uploaded files on disk and store only metadata/path references in MySQL.
- Treat `coshare_patientrecord_sys_frontend/server/` as the historical Node JSON service kept for reference. New development should use the Spring Boot backend.

