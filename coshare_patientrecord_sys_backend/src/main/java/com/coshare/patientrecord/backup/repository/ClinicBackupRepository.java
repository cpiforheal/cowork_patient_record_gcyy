package com.coshare.patientrecord.backup.repository;

import com.coshare.patientrecord.backup.entity.BackupConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class ClinicBackupRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ClinicBackupRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public BackupConfig readConfig(String configId) {
        List<BackupConfig> rows = jdbcTemplate.query(
            "SELECT backup_dir, enabled, retention_policy FROM clinic_backup_config WHERE config_id = ?",
            (rs, rowNum) -> new BackupConfig(
                rs.getString("backup_dir") == null ? "" : rs.getString("backup_dir"),
                rs.getBoolean("enabled"),
                rs.getString("retention_policy") == null ? "7d4w12m" : rs.getString("retention_policy")
            ),
            configId
        );
        return rows.isEmpty() ? new BackupConfig("", true, "7d4w12m") : rows.get(0);
    }

    public void saveConfig(String configId, Path backupDir, boolean enabled) {
        jdbcTemplate.update("""
            INSERT INTO clinic_backup_config (config_id, backup_dir, enabled, retention_policy)
            VALUES (?, ?, ?, '7d4w12m')
            ON DUPLICATE KEY UPDATE backup_dir = VALUES(backup_dir), enabled = VALUES(enabled)
            """, configId, backupDir.toString(), enabled);
    }

    public Map<String, Object> latestRun() {
        List<Map<String, Object>> rows = jdbcTemplate.query(
            "SELECT status, backup_file, started_at, finished_at, size_bytes, message FROM clinic_backup_runs ORDER BY started_at DESC, id DESC LIMIT 1",
            (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("status", rs.getString("status"));
                row.put("backupFile", rs.getString("backup_file"));
                row.put("startedAt", timestampText(rs.getTimestamp("started_at")));
                row.put("finishedAt", timestampText(rs.getTimestamp("finished_at")));
                row.put("sizeBytes", rs.getLong("size_bytes"));
                row.put("message", rs.getString("message"));
                return row;
            }
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long createRun(String triggerType, Path backupDir) {
        jdbcTemplate.update(
            "INSERT INTO clinic_backup_runs (trigger_type, status, backup_dir) VALUES (?, 'running', ?)",
            triggerType,
            backupDir.toString()
        );
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void finishRun(long runId, String status, Path backupFile, long sizeBytes, String message, JsonNode manifest) {
        jdbcTemplate.update("""
            UPDATE clinic_backup_runs
            SET status = ?, backup_file = ?, finished_at = CURRENT_TIMESTAMP, size_bytes = ?, message = ?, manifest_json = ?
            WHERE id = ?
            """,
            status,
            backupFile == null ? "" : backupFile.toString(),
            sizeBytes,
            message == null ? "" : message,
            manifest == null ? null : toJson(manifest),
            runId
        );
    }

    private String timestampText(Timestamp timestamp) {
        return timestamp == null ? "" : timestamp.toInstant().toString();
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalArgumentException("Failed to serialize JSON", error);
        }
    }
}
