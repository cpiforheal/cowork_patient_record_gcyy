# Performance Baseline

Date: 2026-07-02

This document records the current optimization baseline for the patient record system. It is intended for engineering comparison only and does not add user-visible performance text.

## 2026-07-03 Deploy Readiness Pass

User-perceived loading improvements before deployment:

- Home dashboard primary data and maintenance/backup data now refresh in parallel, with local loading masks on task areas instead of making the whole page feel blocked.
- Patient detail shows an initial skeleton while the first archive payload is loading, then keeps the existing workspace behavior unchanged after data arrives.
- Upload actions now enter a preparation state before file data URL conversion starts, allowing buttons and progress bars to paint before larger batches are read.
- Inventory management shows an initial skeleton while the first inventory snapshot is loading, avoiding an empty workspace during startup.

## Build Baseline

Command:

```bash
pnpm run build:pro
```

Result: passed.

Known warnings retained for later专项:

- Sass `@import` deprecation warnings.
- Rollup pure annotation warnings from third-party or generated chunks.

Largest production chunks after this pass:

| Chunk                                                                                |      Size |
| ------------------------------------------------------------------------------------ | --------: |
| `dist/assets/js/index-CLFpfm-k.js`                                                   | 136.82 kB |
| `dist/assets/js/index.vue_vue_type_script_setup_true_name_ProTable_lang-DqBGAqxf.js` | 130.56 kB |
| `dist/assets/js/vue-vendor-CIiE41Ma.js`                                              | 116.35 kB |
| `dist/assets/js/index-C7-2NGqM.js`                                                   |  96.75 kB |
| `dist/assets/js/el-date-picker-JpfS7n5E.js`                                          |  84.04 kB |
| `dist/assets/js/el-table-column-CUzDda48.js`                                         |  80.72 kB |
| `dist/assets/js/index-Cvf-SKZi.js`                                                   |  64.27 kB |
| `dist/assets/js/index-DbyWp-se.js`                                                   |  54.85 kB |
| `dist/assets/js/el-popper-BkYysIdZ.js`                                               |  47.35 kB |
| `dist/assets/js/legacy-YXCS9Mnm.js`                                                  |  44.73 kB |

Lazy chunks verified after this pass:

| Chunk                                |     Size |
| ------------------------------------ | -------: |
| `performanceTrace-mpD0_CcX.js`       |  0.04 kB |
| `TimelineWorkbench-BD4q0arP.js`      |  1.44 kB |
| `AttachmentWorkbench-CMQ8ZnaR.js`    |  1.53 kB |
| `RecordPreviewOverlay-CPYjIMI7.js`   |  1.64 kB |
| `PrintPreflightDialog-C9WNiO4V.js`   |  1.71 kB |
| `VoidAttachmentDialog-BFWw1XFa.js`   |  1.89 kB |
| `ArchivePrecheckDialog--XheCdjy.js`  |  1.90 kB |
| `AiSummaryDialog-OWP7hska.js`        |  4.91 kB |
| `MedicalRecordWorkbench-ClwZ3NcY.js` | 14.35 kB |
| `LegacyImportPanel-BOYwOWCx.js`      | 12.49 kB |

## Hotspot Inventory

Frontend long files after the structural refactor baseline:

| File                                        | Approx. lines | Risk                                                |
| ------------------------------------------- | ------------: | --------------------------------------------------- |
| `src/views/patients/detail/index.vue`       |         10483 | Patient detail first screen, print preview, dialogs |
| `src/views/inventory/manage/index.vue`      |          2281 | Tab switching, repeated derived inventory lists     |
| `src/views/home/index.vue`                  |          1606 | Dashboard loading and maintenance checks            |
| `src/components/AiAssistantPanel/index.vue` |          1171 | Message list and attachment context                 |
| `src/views/workbench/upload/index.vue`      |           930 | File reads, directory imports, failed retry state   |

Backend hotspots after package refactor baseline:

| File                              | Approx. lines | Risk                                                   |
| --------------------------------- | ------------: | ------------------------------------------------------ |
| `InventoryRepository.java`        |           677 | Full inventory reads and request workflow data reloads |
| `ClinicDbWriter.java`             |           622 | Bulk JSON/table writes                                 |
| `ClinicBackupService.java`        |           541 | File-system work and compression                       |
| `ClinicAiDocumentService.java`    |           531 | AI document generation and template data               |
| `ClinicMedicalRecordService.java` |           509 | Docx generation and version workflow                   |

## Manual Timings To Capture

Use browser DevTools Performance or console timing during smoke testing:

| Scenario                                  | Baseline   | After change |
| ----------------------------------------- | ---------- | ------------ |
| Patient detail first render               | To capture | To capture   |
| Patient detail open print preview         | To capture | To capture   |
| Patient detail open AI summary            | To capture | To capture   |
| Upload page select large directory        | To capture | To capture   |
| Quick upload batch submit preparation     | To capture | To capture   |
| Inventory switch to stock tab             | To capture | To capture   |
| Inventory filter stock rows               | To capture | To capture   |
| `/health` response                        | To capture | To capture   |
| `/clinic-api/maintenance/status` response | To capture | To capture   |

## Changes In This Performance Pass

- Patient detail: low-frequency dialogs and overlays are loaded and mounted only when opened.
- Upload workbench: file-to-dataURL conversion is limited to small concurrent batches.
- Upload queue: failed upload summary writes are throttled and flushed on unload/dispose.
- Inventory manage: stock is indexed by item, tab stats are computed only for the active tab, and repeated weekly/item/trace counters are centralized.
- Medical record versions: added a compatible optional `limit` query parameter; the frontend defaults to recent 50 versions while old calls without `limit` keep the previous full response behavior.
- Maintenance dashboard: homepage default loading uses `/clinic-api/maintenance/status/summary`, which skips attachment directory walking; the existing full `/clinic-api/maintenance/status` scan is kept for explicit巡检.
- Developer diagnostics: patient detail loading and upload file preparation now emit dev-only console timing via `traceAsync`.

## Verification

- Frontend `pnpm run type:check`: passed.
- Frontend `pnpm run lint:eslint:check`: passed.
- Frontend `pnpm run lint:prettier:check`: passed.
- Frontend `pnpm run build:pro`: passed.
- Backend `./mvnw -DskipTests compile` in Java 17 Maven container: passed.
- Backend `./mvnw test` in Java 17 Maven container with MySQL env: passed.

## Follow-up Candidates

- Add optional limit/page parameters to medical record versions and inventory request views without changing existing default responses.
- Capture real browser timing numbers after the local smoke flow is stable.
