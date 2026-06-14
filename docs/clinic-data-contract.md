# Clinic Data Contract

This document records the first stable boundary for the outpatient record prototype in `hos_unitywork`.

## Local API

Start the local JSON service:

```bash
pnpm api
```

The service listens on `http://localhost:7071` by default. Set `CLINIC_API_PORT` to override the port.

All responses share the `{ code, msg, data }` envelope. `code` mirrors the HTTP status; `data` is `null` on error.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Service health check. |
| `GET` | `/clinic-api/db` | Read the current local clinic database. If no runtime database exists, the seed file is copied first. |
| `PUT` | `/clinic-api/db` | Replace the current local clinic database. The payload must contain `patients`, `records`, and `archive`. |
| `GET` | `/clinic-api/schema` | Read role and field-rule metadata for UI permission checks (see notes below). |
| `POST` | `/clinic-api/files` | Upload one attachment encoded as a data URL. Body size is capped at 20 MB. |
| `GET` | `/clinic-api/files/{storagePath}` | Download a previously stored attachment by the `storagePath` returned from upload. |
| `POST` | `/clinic-api/reset` | Reset runtime data from `server/data/clinic-db.seed.json`. |

Runtime data is written to `server/data/clinic-db.json` and intentionally ignored by Git. The tracked seed file is `server/data/clinic-db.seed.json`. Uploaded attachments are written under `server/files/clinic-attachments/` (also ignored by Git).

### Upload / download details

- `POST /clinic-api/files` accepts `{ fileName, contentDataUrl }`, where `contentDataUrl` is a base64 data URL like `data:image/png;base64,...`. It responds with `{ fileName, url, storagePath, size, mimeType }`.
- `url` is the public path (`/clinic-api/files/{storagePath}`); `storagePath` is a date-foldered name (`YYYY/MM/DD/<timestamp>-<rand>-<safeName>`).
- The download route validates that the resolved path stays inside the attachments directory, rejecting path-traversal attempts with `400`.
- `Cache-Control: public, max-age=31536000, immutable` is set on downloads, since `storagePath` is content-unique.

### Schema endpoint

`GET /clinic-api/schema` returns `{ roles, fieldRules }`. The service normalizes the runtime database on read/reset and fills the default role baseline plus template field rules when `roles` or `templateFieldRules` are missing or empty. Existing administrator edits are merged by role/rule key instead of being overwritten.

## Data Shape

The seed keeps an empty business baseline in one document:

| Key | Meaning |
| --- | --- |
| `accounts` | The default login account. The baseline only keeps `admin`. |
| `patients` | Patient list rows for workbench or table views. Starts empty. |
| `records` | Patient record field values keyed by patient id. Starts empty. |
| `documents` | Uploaded evidence and report metadata keyed by patient id. Starts empty. |
| `roles` | Role and permission baseline. The seed keeps the key empty; the API expands it to the default outpatient workflow roles. |
| `templateFieldRules` | Field ownership, required-state, printability, and quality-check rules. The seed keeps the key empty; the API expands it from the default record template. |
| `archive` | Submission state and generated record version keyed by patient id. |
| `auditLogs` | Operation trail for upload, edit, review, and archive actions. Starts empty. |

## Implementation Notes

- Keep role and field ownership in the schema instead of hard-coding it across UI pages.
- Treat `server/data/clinic-db.seed.json` as the empty baseline and `server/data/clinic-db.json` as local runtime state.
- `Geeker-Admin/` is part of this repository (the Vue 3 admin front end). It is not a separate checkout; business changes are committed here directly.
