# Clinic Data Contract

This document records the first stable boundary for the outpatient record prototype in `hos_unitywork`.

## Local API

Start the local JSON service:

```bash
pnpm api
```

The service listens on `http://localhost:7071` by default. Set `CLINIC_API_PORT` to override the port.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Service health check. |
| `GET` | `/clinic-api/db` | Read the current local clinic database. If no runtime database exists, the seed file is copied first. |
| `PUT` | `/clinic-api/db` | Replace the current local clinic database. The payload must contain `patients`, `records`, and `archive`. |
| `GET` | `/clinic-api/schema` | Read role and field-rule metadata for UI permission checks. |
| `POST` | `/clinic-api/reset` | Reset runtime data from `server/data/clinic-db.seed.json`. |
| `GET` | `/record-samples/*` | Serve demo evidence files from `public/record-samples/`. |

Runtime data is written to `server/data/clinic-db.json` and intentionally ignored by Git. The tracked seed file is `server/data/clinic-db.seed.json`.

## Data Shape

The seed keeps the prototype data in one document:

| Key | Meaning |
| --- | --- |
| `roles` | System roles, permissions, and editable sections. |
| `fieldRules` | Field-level ownership, required flags, and evidence expectations. |
| `patients` | Patient list rows for workbench or table views. |
| `records` | Patient record field values keyed by patient id. |
| `documents` | Uploaded evidence and report metadata keyed by patient id. |
| `archive` | Submission state and generated record version keyed by patient id. |
| `auditLogs` | Operation trail for upload, edit, review, and archive actions. |

## Implementation Notes

- Keep role and field ownership in the schema instead of hard-coding it across UI pages.
- Treat `server/data/clinic-db.seed.json` as demo data and `server/data/clinic-db.json` as local runtime state.
- Keep `Geeker-Admin/` as an external reference checkout. Do not use it as the commit target for `hos_unitywork` business changes.
