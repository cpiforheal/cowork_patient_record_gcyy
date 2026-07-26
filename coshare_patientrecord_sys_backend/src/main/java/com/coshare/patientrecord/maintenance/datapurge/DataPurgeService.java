package com.coshare.patientrecord.maintenance.datapurge;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.backup.service.ClinicBackupService;
import com.coshare.patientrecord.backup.service.ClinicBackupService.ProtectedBackup;
import com.coshare.patientrecord.maintenance.datapurge.dto.DataPurgeExecuteRequest;
import com.coshare.patientrecord.security.AuthPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class DataPurgeService {

    public static final String CONFIRMATION_TEXT = "清空全部测试数据";
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(5);
    private static final DateTimeFormatter DIRECTORY_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Shanghai");

    private static final List<String> INVENTORY_TABLES = List.of(
        "inventory_weekly_audit_events", "inventory_weekly_exports", "inventory_weekly_commands",
        "inventory_weekly_snapshot_lines", "inventory_weekly_snapshots", "inventory_weekly_standard_lines",
        "inventory_weekly_standards", "inventory_exception_tasks", "inventory_stage_consumption_commands",
        "inventory_opening_suggestions", "inventory_ledger_movements", "inventory_transfer_lines",
        "inventory_transfers", "inventory_batch_balances", "inventory_consumption_details",
        "inventory_consumption_events", "inventory_package_lines", "inventory_packages",
        "inventory_request_lines", "inventory_requests", "inventory_counts", "inventory_movements",
        "inventory_weekly_consumption", "inventory_audit_logs", "inventory_batches", "inventory_items"
    );

    private static final List<String> PATIENT_TABLES = List.of(
        "clinic_medical_record_node_mappings", "clinic_medical_record_task_events",
        "clinic_medical_record_version_assets", "clinic_medical_record_generation_tasks",
        "clinic_medical_record_sanitization_reports", "clinic_medical_record_document_assets",
        "clinic_ai_document_tasks", "clinic_generated_ai_documents", "clinic_ai_assistant_logs",
        "clinic_generated_medical_records", "pre_ai_encounter_department_grants",
        "pre_ai_stage_submissions", "pre_ai_auxiliary_tasks", "pre_ai_attachments",
        "pre_ai_lab_reports", "pre_ai_diagnoses", "pre_ai_audit_logs", "pre_ai_exports",
        "clinic_queue_print_tasks", "clinic_queue_audit_logs", "clinic_queue_emergencies",
        "clinic_queue_announcements", "clinic_queue_tasks", "clinic_queue_tickets",
        "tcm_pharmacy_audit_logs", "tcm_pharmacy_prescriptions", "tcm_pharmacy_announcements",
        "clinic_record_field_values", "clinic_record_fields", "clinic_archive", "clinic_documents", "clinic_patient_encounters",
        "clinic_patients", "pre_ai_encounters", "pre_ai_patient_cases", "clinic_audit_logs", "clinic_db_snapshots"
    );

    private static final Set<String> QUEUE_TABLES = Set.of(
        "clinic_queue_print_tasks", "clinic_queue_audit_logs", "clinic_queue_emergencies",
        "clinic_queue_announcements", "clinic_queue_tasks", "clinic_queue_tickets"
    );
    private static final Set<String> TCM_TABLES = Set.of(
        "tcm_pharmacy_audit_logs", "tcm_pharmacy_prescriptions", "tcm_pharmacy_announcements"
    );
    private static final Set<String> AI_TABLES = Set.of(
        "clinic_ai_document_tasks", "clinic_generated_ai_documents", "clinic_ai_assistant_logs"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final ClinicBackupService backupService;
    private final TransactionTemplate transactionTemplate;
    private final DataPurgeMaintenanceState maintenanceState;
    private final Path dataRoot;
    private final Map<String, Path> managedDirectories;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, PreviewGrant> previewGrants = new ConcurrentHashMap<>();

    static List<String> inventoryTablesForPurge() {
        return INVENTORY_TABLES;
    }

    static List<String> patientTablesForPurge() {
        return PATIENT_TABLES;
    }

    public DataPurgeService(
        JdbcTemplate jdbc,
        ObjectMapper mapper,
        PasswordEncoder passwordEncoder,
        ClinicBackupService backupService,
        PlatformTransactionManager transactionManager,
        DataPurgeMaintenanceState maintenanceState,
        @Value("${clinic.data-dir:${clinic.attachment-dir}/..}") String dataRoot,
        @Value("${clinic.attachment-dir}") String attachmentDir,
        @Value("${clinic.generated-pre-ai-dir:${clinic.attachment-dir}/../generated-pre-ai}") String generatedPreAiDir,
        @Value("${clinic.generated-medical-record-dir:${clinic.attachment-dir}/../generated-medical-records}") String generatedMedicalRecordDir,
        @Value("${clinic.medical-record-workflow-dir:${clinic.attachment-dir}/../medical-record-workflow}") String medicalRecordWorkflowDir,
        @Value("${clinic.generated-ai-document-dir:${clinic.attachment-dir}/../generated-ai-documents}") String generatedAiDocumentDir
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.backupService = backupService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maintenanceState = maintenanceState;
        this.dataRoot = Path.of(dataRoot).toAbsolutePath().normalize();
        Map<String, Path> directories = new LinkedHashMap<>();
        directories.put("attachments", normalized(attachmentDir));
        directories.put("generated-pre-ai", normalized(generatedPreAiDir));
        directories.put("generated-medical-records", normalized(generatedMedicalRecordDir));
        directories.put("medical-record-workflow", normalized(medicalRecordWorkflowDir));
        directories.put("generated-ai-documents", normalized(generatedAiDocumentDir));
        this.managedDirectories = Map.copyOf(directories);
    }

    public ObjectNode preview() {
        SessionUser user = requireAdmin();
        purgeExpiredPreviewGrants();
        Instant expiresAt = Instant.now().plus(PREVIEW_TTL);
        String token = newPreviewToken();
        previewGrants.put(sha256(token), new PreviewGrant(user.id(), expiresAt));

        ObjectNode result = mapper.createObjectNode();
        result.put("token", token);
        result.put("expiresAt", expiresAt.toString());
        result.put("confirmationText", CONFIRMATION_TEXT);
        result.set("counts", collectCounts());
        result.set("managedFiles", managedFileSummary());
        result.set("retained", retainedSummary());
        result.put("databaseRevision", databaseRevision());
        ArrayNode warnings = result.putArray("warnings");
        warnings.add("执行后仅保留当前管理员账号，所有现有会话会被撤销");
        warnings.add("患者、队列、中药房、AI 日志和进销存业务数据将被清空");
        warnings.add("执行前会创建不可自动淘汰的数据库与文件保护备份");
        return result;
    }

    public ObjectNode execute(DataPurgeExecuteRequest request) {
        SessionUser user = requireAdmin();
        validateConfirmation(request, user);
        verifyPassword(user.id(), request.password());
        if (!maintenanceState.tryLock()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有数据维护任务正在执行");
        }

        String runId = "purge-" + UUID.randomUUID();
        Instant createdAt = Instant.now();
        Path backupDir = protectedBackupDirectory(runId);
        boolean databaseCommitted = false;
        boolean runInserted = false;
        ProtectedBackup protectedBackup = null;

        try {
            ObjectNode beforeCounts = collectCounts();
            insertRun(runId, user, beforeCounts, createdAt);
            runInserted = true;
            validateManagedDirectoryBoundaries(backupDir);
            updateRun(runId, "BACKING_UP", backupDir, "", false, false, null, null);
            ProtectedBackup backup = backupService.createProtectedBackup(backupDir, managedDirectories, beforeCounts);
            protectedBackup = backup;
            updateRun(runId, "PURGING_DATABASE", backup.directory(), backup.sha256(), false, false, null, null);

            ObjectNode afterCounts = transactionTemplate.execute(status -> {
                purgeDatabase(user);
                ObjectNode counts = collectCounts();
                updateRun(runId, "DATABASE_COMMITTED", backup.directory(), backup.sha256(), true, false, counts, null);
                return counts;
            });
            databaseCommitted = true;

            try {
                quarantineManagedFiles(runId);
                updateRun(runId, "COMPLETED", backup.directory(), backup.sha256(), true, true, afterCounts, null);
            } catch (Exception fileError) {
                updateRun(runId, "FILES_PENDING", backup.directory(), backup.sha256(), true, false, afterCounts, safeMessage(fileError));
            }
            return run(runId);
        } catch (Exception error) {
            if (runInserted && !databaseCommitted) {
                updateRun(
                    runId,
                    "FAILED",
                    protectedBackup == null ? (Files.exists(backupDir) ? backupDir : null) : protectedBackup.directory(),
                    protectedBackup == null ? "" : protectedBackup.sha256(),
                    false,
                    false,
                    null,
                    safeMessage(error)
                );
            }
            if (error instanceof ResponseStatusException responseStatusException) throw responseStatusException;
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "数据清理失败：" + safeMessage(error), error);
        } finally {
            maintenanceState.unlock();
        }
    }

    public ObjectNode resumeFileQuarantine(String runId) {
        requireAdmin();
        ObjectNode current = run(runId);
        if (!current.path("databaseCommitted").asBoolean(false)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "数据库清理尚未提交，不能执行文件隔离续作");
        }
        if (current.path("filesQuarantined").asBoolean(false)) return current;
        if (!maintenanceState.tryLock()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有数据维护任务正在执行");
        }
        try {
            quarantineManagedFiles(runId);
            jdbc.update(
                "UPDATE clinic_data_purge_runs SET status = 'COMPLETED', files_quarantined = TRUE, error_message = NULL, updated_at = CURRENT_TIMESTAMP(6) WHERE run_id = ?",
                runId
            );
            return run(runId);
        } catch (Exception error) {
            jdbc.update(
                "UPDATE clinic_data_purge_runs SET status = 'FILES_PENDING', error_message = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE run_id = ?",
                safeMessage(error), runId
            );
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文件隔离续作失败：" + safeMessage(error), error);
        } finally {
            maintenanceState.unlock();
        }
    }

    public ObjectNode run(String runId) {
        requireAdmin();
        List<ObjectNode> rows = jdbc.query(
            "SELECT * FROM clinic_data_purge_runs WHERE run_id = ?",
            (rs, rowNum) -> mapRun(rs),
            runId
        );
        return rows.stream().findFirst().orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据清理任务不存在")
        );
    }

    private SessionUser requireAdmin() {
        AuthPermission.requireAnyRole("仅系统管理员可以执行数据清理", "admin");
        return AuthPermission.currentUserOrThrow();
    }

    private void validateConfirmation(DataPurgeExecuteRequest request, SessionUser user) {
        if (!CONFIRMATION_TEXT.equals(request.confirmationText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "确认文本不正确");
        }
        PreviewGrant grant = previewGrants.remove(sha256(request.previewToken()));
        if (grant == null || !grant.userId().equals(user.id()) || grant.expiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预检令牌无效或已过期，请重新预检");
        }
    }

    private void verifyPassword(String userId, String password) {
        List<String> hashes = jdbc.query(
            "SELECT JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.passwordHash')) FROM clinic_accounts WHERE id = ? AND role = 'admin' LIMIT 1",
            (rs, rowNum) -> rs.getString(1),
            userId
        );
        String hash = hashes.stream().findFirst().orElse("");
        if (hash.isBlank() || password == null || !passwordEncoder.matches(password, hash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "当前密码不正确");
        }
    }

    private void purgeDatabase(SessionUser retainedAdmin) {
        // Break the two self-referential workflow chains before child-first deletion.
        executeIfTableExists("clinic_medical_record_generation_tasks", "UPDATE clinic_medical_record_generation_tasks SET retry_of_task_id = NULL");
        executeIfTableExists("clinic_medical_record_document_assets", "UPDATE clinic_medical_record_document_assets SET parent_asset_id = NULL");
        executeIfTableExists("inventory_weekly_snapshots", "UPDATE inventory_weekly_snapshots SET previous_snapshot_id = NULL, root_snapshot_id = NULL");

        deleteAllExisting(INVENTORY_TABLES);
        deleteAllExisting(PATIENT_TABLES);

        executeIfTableExists("clinic_auth_sessions", "DELETE FROM clinic_auth_sessions");
        executeIfTableExists("clinic_login_failures", "DELETE FROM clinic_login_failures");
        if (tableExists("clinic_account_departments")) {
            jdbc.update("DELETE FROM clinic_account_departments WHERE account_id <> ?", retainedAdmin.id());
        }
        if (tableExists("clinic_accounts")) {
            jdbc.update("DELETE FROM clinic_accounts WHERE id <> ?", retainedAdmin.id());
            jdbc.update(
                """
                UPDATE clinic_accounts
                SET username = 'admin', role = 'admin', status = '启用',
                    raw_json = JSON_SET(raw_json, '$.username', 'admin', '$.role', 'admin', '$.roleLabel', '系统管理员', '$.status', '启用')
                WHERE id = ?
                """,
                retainedAdmin.id()
            );
        }
        normalizeLocations();
    }

    private void normalizeLocations() {
        if (!tableExists("inventory_locations")) return;
        jdbc.update(
            """
            DELETE l FROM inventory_locations l
            LEFT JOIN clinic_departments d ON d.id = l.department_id AND d.status = 'ACTIVE'
            WHERE (l.location_type = 'DEPARTMENT' AND d.id IS NULL)
               OR l.location_type NOT IN ('CENTRAL', 'IN_TRANSIT', 'DEPARTMENT')
               OR (l.location_type = 'CENTRAL' AND l.id <> 'loc-central')
               OR (l.location_type = 'IN_TRANSIT' AND l.id <> 'loc-in-transit')
            """
        );
        jdbc.update(
            """
            INSERT INTO inventory_locations (id, location_type, name, status, opening_confirmed)
            VALUES ('loc-central', 'CENTRAL', '中央仓库', 'ACTIVE', TRUE),
                   ('loc-in-transit', 'IN_TRANSIT', '配送在途', 'ACTIVE', TRUE)
            ON DUPLICATE KEY UPDATE status = 'ACTIVE', opening_confirmed = TRUE,
              opening_confirmed_by = NULL, opening_confirmed_at = NULL
            """
        );
        jdbc.update(
            """
            INSERT INTO inventory_locations (
              id, location_type, department_id, department_name_snapshot, name, status, opening_confirmed
            )
            SELECT CONCAT('loc-dept-', LEFT(SHA2(d.id, 256), 32)), 'DEPARTMENT', d.id, d.name,
                   CONCAT(d.name, '科室库'), 'ACTIVE', FALSE
            FROM clinic_departments d
            WHERE d.status = 'ACTIVE'
            ON DUPLICATE KEY UPDATE department_name_snapshot = VALUES(department_name_snapshot),
              name = VALUES(name), status = 'ACTIVE', opening_confirmed = FALSE,
              opening_confirmed_by = NULL, opening_confirmed_at = NULL
            """
        );
        jdbc.update(
            "UPDATE inventory_locations SET opening_confirmed = FALSE, opening_confirmed_by = NULL, opening_confirmed_at = NULL WHERE location_type = 'DEPARTMENT'"
        );
    }

    private void deleteAllExisting(List<String> orderedTables) {
        for (String table : orderedTables) {
            if (tableExists(table)) jdbc.update("DELETE FROM " + table);
        }
    }

    private void executeIfTableExists(String table, String sql) {
        if (tableExists(table)) jdbc.update(sql);
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
            Integer.class,
            table
        );
        return count != null && count > 0;
    }

    private ObjectNode collectCounts() {
        ObjectNode counts = mapper.createObjectNode();
        counts.put("patientCases", count("pre_ai_patient_cases"));
        counts.put("encounters", count("pre_ai_encounters") + count("clinic_patient_encounters"));
        counts.put("patientBusinessRows", sumCounts(PATIENT_TABLES, table -> !QUEUE_TABLES.contains(table) && !TCM_TABLES.contains(table) && !AI_TABLES.contains(table)));
        counts.put("queueRows", sumCounts(PATIENT_TABLES, QUEUE_TABLES::contains));
        counts.put("tcmPharmacyRows", sumCounts(PATIENT_TABLES, TCM_TABLES::contains));
        counts.put("aiRows", sumCounts(PATIENT_TABLES, AI_TABLES::contains));
        counts.put("inventoryRows", sumCounts(INVENTORY_TABLES, table -> true));
        counts.put("accounts", count("clinic_accounts"));
        counts.put("sessions", count("clinic_auth_sessions"));
        counts.put("locations", count("inventory_locations"));
        return counts;
    }

    private long sumCounts(List<String> tables, java.util.function.Predicate<String> include) {
        long total = 0;
        for (String table : tables) if (include.test(table)) total += count(table);
        return total;
    }

    private long count(String table) {
        if (!tableExists(table)) return 0;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    private ObjectNode managedFileSummary() {
        ObjectNode summary = mapper.createObjectNode();
        ArrayNode directories = summary.putArray("directories");
        ArrayNode directoryDetails = summary.putArray("directoryDetails");
        long totalFiles = 0;
        long totalBytes = 0;
        for (Map.Entry<String, Path> entry : managedDirectories.entrySet()) {
            FileSummary fileSummary = summarize(entry.getValue());
            directories.add(entry.getValue().toString());
            ObjectNode directory = directoryDetails.addObject();
            directory.put("name", entry.getKey());
            directory.put("path", entry.getValue().toString());
            directory.put("fileCount", fileSummary.fileCount());
            directory.put("totalBytes", fileSummary.totalBytes());
            totalFiles += fileSummary.fileCount();
            totalBytes += fileSummary.totalBytes();
        }
        summary.put("fileCount", totalFiles);
        summary.put("totalBytes", totalBytes);
        return summary;
    }

    private ObjectNode retainedSummary() {
        ObjectNode retained = mapper.createObjectNode();
        retained.put("accounts", 1);
        retained.put("accountRule", "仅保留当前 admin");
        retained.put("inventoryLocations", "中央仓、配送在途和有效科室库");
        retained.put("referenceConfiguration", "科室、字典、字段规则、AI 配置、队列房间与打印配置");
        retained.put("existingOfflineBackups", true);
        return retained;
    }

    private String databaseRevision() {
        if (tableExists("flyway_schema_history")) {
            List<String> versions = jdbc.query(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",
                (rs, rowNum) -> rs.getString(1)
            );
            if (!versions.isEmpty()) return versions.get(0);
        }
        return "unknown";
    }

    private FileSummary summarize(Path directory) {
        if (!Files.isDirectory(directory)) return new FileSummary(0, 0);
        long files = 0;
        long bytes = 0;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files++;
                bytes += Files.size(path);
            }
            return new FileSummary(files, bytes);
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法统计受管文件目录：" + directory, error);
        }
    }

    void quarantineManagedFiles(String runId) throws IOException {
        Path quarantineRoot = dataRoot.resolve("quarantine").resolve(runId).normalize();
        ensureChildOfDataRoot(quarantineRoot);
        Files.createDirectories(quarantineRoot);
        for (Map.Entry<String, Path> entry : managedDirectories.entrySet()) {
            Path source = entry.getValue();
            Path destination = quarantineRoot.resolve(entry.getKey()).normalize();
            ensureChildOfDataRoot(destination);
            if (Files.exists(source)) moveDirectoryContents(source, destination);
            Files.createDirectories(source);
        }
    }

    private void moveDirectoryContents(Path source, Path destination) throws IOException {
        Files.createDirectories(destination);
        List<Path> children;
        try (var paths = Files.list(source)) {
            children = paths.toList();
        }
        for (Path child : children) {
            Path target = destination.resolve(child.getFileName()).normalize();
            if (Files.exists(target)) throw new IOException("Quarantine target already exists: " + target);
            try {
                Files.move(child, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(child, target);
            }
        }
    }

    private void ensureChildOfDataRoot(Path path) throws IOException {
        if (!path.startsWith(dataRoot) || path.equals(dataRoot)) throw new IOException("Unsafe maintenance path: " + path);
    }

    private void validateManagedDirectoryBoundaries(Path backupDir) throws IOException {
        Path quarantineRoot = dataRoot.resolve("quarantine").normalize();
        for (Map.Entry<String, Path> entry : managedDirectories.entrySet()) {
            Path directory = entry.getValue();
            if (directory.getParent() == null || directory.equals(dataRoot) || backupDir.startsWith(directory) || quarantineRoot.startsWith(directory)) {
                throw new IOException("Unsafe managed-directory configuration for " + entry.getKey() + ": " + directory);
            }
            for (Map.Entry<String, Path> other : managedDirectories.entrySet()) {
                if (!entry.getKey().equals(other.getKey()) && other.getValue().startsWith(directory)) {
                    throw new IOException("Managed directories overlap: " + entry.getKey() + " and " + other.getKey());
                }
            }
        }
    }

    private Path protectedBackupDirectory(String runId) {
        String timestamp = LocalDateTime.now(CLINIC_ZONE).format(DIRECTORY_TIME);
        Path directory = dataRoot.resolve("manual-clean-backups").resolve("purge-" + timestamp + "-" + runId.substring(runId.length() - 8)).normalize();
        if (!directory.startsWith(dataRoot) || directory.equals(dataRoot)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保护备份目录配置不安全");
        }
        return directory;
    }

    private void insertRun(String runId, SessionUser user, ObjectNode beforeCounts, Instant createdAt) {
        jdbc.update(
            """
            INSERT INTO clinic_data_purge_runs (
              run_id, operator_id, operator_name, status, before_counts_json, database_committed,
              files_quarantined, created_at, updated_at
            ) VALUES (?, ?, ?, 'PREPARED', CAST(? AS JSON), FALSE, FALSE, ?, ?)
            """,
            runId, user.id(), user.name(), json(beforeCounts), Timestamp.from(createdAt), Timestamp.from(createdAt)
        );
    }

    private void updateRun(
        String runId,
        String status,
        Path backupDir,
        String backupSha256,
        boolean databaseCommitted,
        boolean filesQuarantined,
        ObjectNode afterCounts,
        String errorMessage
    ) {
        jdbc.update(
            """
            UPDATE clinic_data_purge_runs
            SET status = ?, backup_dir = ?, backup_sha256 = ?, database_committed = ?, files_quarantined = ?,
                after_counts_json = CASE WHEN ? IS NULL THEN after_counts_json ELSE CAST(? AS JSON) END,
                error_message = ?, updated_at = CURRENT_TIMESTAMP(6)
            WHERE run_id = ?
            """,
            status,
            backupDir == null ? null : backupDir.toString(),
            backupSha256 == null ? "" : backupSha256,
            databaseCommitted,
            filesQuarantined,
            afterCounts == null ? null : json(afterCounts),
            afterCounts == null ? null : json(afterCounts),
            errorMessage,
            runId
        );
    }

    private ObjectNode mapRun(ResultSet rs) throws SQLException {
        ObjectNode run = mapper.createObjectNode();
        run.put("runId", rs.getString("run_id"));
        run.put("operatorId", rs.getString("operator_id"));
        run.put("operatorName", rs.getString("operator_name"));
        run.put("status", rs.getString("status"));
        run.put("backupDir", nullToEmpty(rs.getString("backup_dir")));
        run.put("backupSha256", nullToEmpty(rs.getString("backup_sha256")));
        run.put("databaseCommitted", rs.getBoolean("database_committed"));
        run.put("filesQuarantined", rs.getBoolean("files_quarantined"));
        setJson(run, "beforeCounts", rs.getString("before_counts_json"));
        setJson(run, "afterCounts", rs.getString("after_counts_json"));
        run.put("errorMessage", nullToEmpty(rs.getString("error_message")));
        run.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        run.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
        ObjectNode recovery = run.putObject("recovery");
        recovery.put("restoreScript", run.path("backupDir").asText("").isBlank() ? "" : Path.of(run.path("backupDir").asText()).resolve("restore-protected-backup.ps1").toString());
        recovery.put("fileQuarantine", dataRoot.resolve("quarantine").resolve(run.path("runId").asText()).toString());
        recovery.put("canResumeFiles", run.path("databaseCommitted").asBoolean() && !run.path("filesQuarantined").asBoolean());
        return run;
    }

    private void setJson(ObjectNode target, String field, String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            target.set(field, mapper.createObjectNode());
            return;
        }
        try {
            target.set(field, mapper.readTree(rawJson));
        } catch (Exception error) {
            target.put(field, rawJson);
        }
    }

    private String json(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalArgumentException("Failed to serialize purge state", error);
        }
    }

    private String newPreviewToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void purgeExpiredPreviewGrants() {
        Instant now = Instant.now();
        previewGrants.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private Path normalized(String rawPath) {
        return Path.of(rawPath).toAbsolutePath().normalize();
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 2000));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record PreviewGrant(String userId, Instant expiresAt) {
    }

    private record FileSummary(long fileCount, long totalBytes) {
    }
}
