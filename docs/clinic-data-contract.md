# Clinic Data Contract

This document records the current stable boundary for the outpatient record prototype.

## Runtime

The active implementation is:

- Backend: `coshare_patientrecord_sys_backend/`
- Frontend: `coshare_patientrecord_sys_frontend/Geeker-Admin/`

The backend runs as Spring Boot with the `mysql` profile enabled by default. Historical Node/JSON service code is migration reference only.

## Local API

Start the backend:

```bash
cd coshare_patientrecord_sys_backend
mvnw.cmd spring-boot:run
```

The service listens on `http://localhost:8080` by default. Override with `SERVER_PORT`.

All business responses use `{ code, msg, data }`.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Service health check. |
| `GET` | `/health/db` | MySQL connectivity check. |
| `GET` | `/clinic-api/db` | Read the current clinic database in the frontend-compatible shape. |
| `PUT` | `/clinic-api/db` | Replace the current clinic database in the frontend-compatible shape. |
| `POST` | `/clinic-api/files` | Store one attachment encoded as a data URL. |
| `GET` | `/clinic-api/files/{storagePath}` | Download a stored attachment by returned `storagePath`. |

Runtime business data is stored in MySQL. Uploaded files are stored under `coshare_patientrecord_sys_backend/runtime/clinic-attachments/` by default, or the directory configured by `CLINIC_ATTACHMENT_DIR`.

## Data Shape

`GET /clinic-api/db` and `PUT /clinic-api/db` keep the frontend payload contract:

| Key | Meaning |
| --- | --- |
| `accounts` | Login accounts. |
| `patients` | Patient master rows. |
| `records` | Patient record field values keyed by patient id. |
| `documents` | Uploaded evidence and report metadata keyed by patient id. |
| `roles` | Role and permission rows. |
| `departments` | Department rows. |
| `dictionaries` | Upload and naming dictionaries. |
| `templateFieldRules` | Field ownership, required-state, printability, and quality-check rules. |
| `archive` | Submission state and generated record version keyed by patient id. |
| `auditLogs` | Operation trail for upload, edit, review, and archive actions. |

## MySQL Tables

The backend keeps compatibility JSON snapshots while starting second-phase normalization:

| Table | Purpose |
| --- | --- |
| `clinic_patients` | Patient master rows. |
| `clinic_patient_encounters` | Repeat outpatient/inpatient visits for the same patient. |
| `clinic_record_fields` | Compatibility snapshot of all record fields per patient. |
| `clinic_record_field_values` | Per-field values for future permission, quality-control, and search work. |
| `clinic_documents` | Attachment metadata. |
| `clinic_archive` | Archive state. |
| `clinic_audit_logs` | Operation logs. |
| `clinic_accounts`, `clinic_roles`, `clinic_departments`, `clinic_dictionaries`, `clinic_template_field_rules` | System configuration. |
| `clinic_db_snapshots` | Full payload snapshots after writes. |

## Frontend Package Rule

Frontend package management is pinned to `pnpm@8.15.9`. Keep `pnpm-lock.yaml` as the only frontend lock file; do not commit `package-lock.json`.
