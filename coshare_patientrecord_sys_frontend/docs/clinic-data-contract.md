# Clinic Data Contract

This document records the current API boundary for the outpatient record system after the split Spring Boot + MySQL refactor.

## Backend API

Start the backend with the `mysql` profile:

```bash
cd coshare_patientrecord_sys_backend
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=mysql
```

The service listens on `http://localhost:8080` by default. Set `SERVER_PORT` to override the port.

All responses share the `{ code, msg, data }` envelope. `code` mirrors the HTTP status; `data` is `null` on error.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Service health check. |
| `GET` | `/health/db` | MySQL profile and connection health check. |
| `GET` | `/clinic-api/db` | Read the current clinic database from MySQL. The response includes `_revision`. |
| `POST` | `/clinic-api/db/merge` | Merge the submitted clinic database payload into MySQL without clearing unrelated rows. The frontend uses this for normal saves. |
| `PUT` | `/clinic-api/db` | Legacy full replacement save. Keep it for controlled migration only; it clears and rewrites the normalized tables. |
| `POST` | `/clinic-api/db/patch` | Compatibility patch endpoint for small partial updates. |
| `GET` | `/clinic-api/maintenance/status` | Runtime status, including MySQL counts, snapshots, attachment directory usage, and missing attachment references. |
| `POST` | `/clinic-api/maintenance/snapshot` | Save a JSON snapshot of the current database state. |
| `GET` | `/clinic-api/patients/duplicates` | Detect possible duplicate patient rows by name+phone or visit number. |
| `POST` | `/clinic-api/files` | Upload one attachment encoded as a data URL. |
| `GET` | `/clinic-api/files/{storagePath}` | Download a previously stored attachment by the `storagePath` returned from upload. |

Runtime business data is written to MySQL. Uploaded attachments are written to the configured backend file storage directory and intentionally ignored by Git.

### Upload / download details

- `POST /clinic-api/files` accepts `{ fileName, contentDataUrl, patientId?, department?, operator?, operatorRole?, type?, typeLabel? }`, where `contentDataUrl` is a base64 data URL like `data:image/png;base64,...`.
- The default accepted MIME types are JPEG, PNG, WebP, BMP, GIF, and PDF. The default max size is 50 MB and can be changed with `CLINIC_ATTACHMENT_MAX_SIZE_BYTES`.
- It responds with `{ fileName, url, storagePath, size, mimeType, sha256 }`.
- `url` is the public path (`/clinic-api/files/{storagePath}`); `storagePath` is a date and patient foldered name (`YYYY/MM/DD/<patientId>/<timestamp>-<rand>-<safeName>`).
- The download route validates that the resolved path stays inside the attachments directory, rejecting path-traversal attempts with `400`.
- Downloads use private no-store cache headers because attachments may contain sensitive medical information.

### Revision protection

`GET /clinic-api/db` returns `_revision`. The normal frontend save path now uses `POST /clinic-api/db/merge`, which merges submitted patients, records, archive rows, documents, system dictionaries, and audit logs without deleting rows that are absent from the caller's stale snapshot.

Use `PUT /clinic-api/db` only for deliberate migration or recovery because it clears and rewrites the compatibility tables.

## Data Shape

The current compatibility payload keeps the existing frontend data shape:

| Key | Meaning |
| --- | --- |
| `_revision` | Server-side optimistic version for write protection. |
| `accounts` | Login account and department account baseline. |
| `patients` | Patient list rows for workbench or table views. |
| `records` | Patient record field values keyed by patient id. |
| `documents` | Uploaded evidence and report metadata keyed by patient id. |
| `roles` | Role and permission baseline. |
| `templateFieldRules` | Field ownership, required-state, printability, and quality-check rules. |
| `archive` | Submission state and generated record version keyed by patient id. |
| `auditLogs` | Operation trail for upload, edit, review, and archive actions. |

## Implementation Notes

- Keep role and field ownership in the schema instead of hard-coding it across UI pages.
- The current save path is still a compatibility layer around the existing frontend data shape, but the default operation is merge/upsert rather than clear-and-rewrite.
- The next persistence step should split high-frequency commands into row-level endpoints for patients, records, documents, accounts, roles, dictionaries, and audit logs.
- `coshare_patientrecord_sys_frontend/Geeker-Admin/` is part of this repository. It is not a separate checkout; business changes are committed here directly.
