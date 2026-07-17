package com.coshare.patientrecord.backup.service;

import com.coshare.patientrecord.backup.repository.ClinicBackupRepository;
import com.coshare.patientrecord.backup.entity.BackupConfig;
import com.coshare.patientrecord.clinic.service.ClinicDatabaseService;
import com.coshare.patientrecord.file.service.ClinicFileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.swing.JFileChooser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class ClinicBackupService {

    private static final DateTimeFormatter BACKUP_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String CONFIG_ID = "primary";
    private static final String BACKUP_PREFIX = "clinic-backup-";
    private static final String BACKUP_SUFFIX = ".zip";

    private final ClinicBackupRepository backupRepository;
    private final ObjectMapper objectMapper;
    private final ClinicDatabaseService databaseService;
    private final ClinicFileService fileService;
    private final AtomicBoolean backupRunning = new AtomicBoolean(false);
    private final String datasourceUrl;
    private final String datasourceUsername;
    private final String datasourcePassword;
    private final String mysqlDumpPath;
    private final String mysqlDataDir;

    public ClinicBackupService(
        ClinicBackupRepository backupRepository,
        ObjectMapper objectMapper,
        ClinicDatabaseService databaseService,
        ClinicFileService fileService,
        @Value("${spring.datasource.url}") String datasourceUrl,
        @Value("${spring.datasource.username}") String datasourceUsername,
        @Value("${spring.datasource.password:}") String datasourcePassword,
        @Value("${clinic.backup.mysqldump-path:}") String mysqlDumpPath,
        @Value("${clinic.mysql-data-dir:}") String mysqlDataDir
    ) {
        this.backupRepository = backupRepository;
        this.objectMapper = objectMapper;
        this.databaseService = databaseService;
        this.fileService = fileService;
        this.datasourceUrl = datasourceUrl;
        this.datasourceUsername = datasourceUsername;
        this.datasourcePassword = datasourcePassword == null ? "" : datasourcePassword;
        this.mysqlDumpPath = mysqlDumpPath == null ? "" : mysqlDumpPath.trim();
        this.mysqlDataDir = mysqlDataDir == null ? "" : mysqlDataDir.trim();
    }

    public ObjectNode status() {
        BackupConfig config = readConfig();
        ObjectNode status = objectMapper.createObjectNode();
        status.put("backupDir", config.backupDir());
        status.put("enabled", config.enabled());
        status.put("retentionPolicy", config.retentionPolicy());
        status.put("schedule", "每天 02:00");
        status.put("running", backupRunning.get());
        status.put("checkedAt", Instant.now().toString());

        if (!config.backupDir().isBlank()) {
            Path dir = Path.of(config.backupDir()).toAbsolutePath().normalize();
            status.put("backupFileCount", countBackupFiles(dir));
            status.put("backupTotalBytes", sumBackupFiles(dir));
            status.put("usableSpaceBytes", safeUsableSpace(dir));
        } else {
            status.put("backupFileCount", 0);
            status.put("backupTotalBytes", 0);
            status.put("usableSpaceBytes", 0);
        }

        Map<String, Object> latestRun = backupRepository.latestRun();
        if (latestRun != null) {
            status.set("latestRun", objectMapper.valueToTree(latestRun));
        }
        return status;
    }

    public ObjectNode updateConfig(String rawBackupDir, boolean enabled) {
        Path backupDir = validateBackupDir(rawBackupDir);
        testWritable(backupDir);
        backupRepository.saveConfig(CONFIG_ID, backupDir, enabled);
        return status();
    }

    public ObjectNode runManualBackup() {
        return runBackup("manual");
    }

    public ObjectNode chooseBackupDirectory(String initialDir) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前后端运行环境无法打开本机目录选择器，请手动填写备份路径");
        }
        JFileChooser chooser = new JFileChooser(resolveInitialDirectory(initialDir));
        chooser.setDialogTitle("选择物理备份目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);

        int selectionResult = chooser.showOpenDialog(null);
        if (selectionResult != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已取消选择备份目录");
        }
        Path backupDir = validateBackupDir(chooser.getSelectedFile().toPath().toString());
        ObjectNode result = objectMapper.createObjectNode();
        result.put("backupDir", backupDir.toString());
        return result;
    }

    @Scheduled(cron = "${clinic.backup.cron:0 0 2 * * *}", zone = "${clinic.backup.zone:Asia/Shanghai}")
    public void runScheduledBackup() {
        BackupConfig config = readConfig();
        if (!config.enabled() || config.backupDir().isBlank()) {
            return;
        }
        try {
            runBackup("scheduled");
        } catch (Exception ignored) {
            // The run table stores the error for administrators; do not stop the scheduler.
        }
    }

    private ObjectNode runBackup(String triggerType) {
        BackupConfig config = readConfig();
        if (config.backupDir().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先设置备份路径");
        }
        Path backupDir = validateBackupDir(config.backupDir());
        testWritable(backupDir);
        if (!backupRunning.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有备份任务正在执行");
        }

        long runId = backupRepository.createRun(triggerType, backupDir);
        Path stagingDir = backupDir.resolve(".clinic-backup-staging-" + UUID.randomUUID()).normalize();
        String backupName = BACKUP_PREFIX + LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(BACKUP_NAME_FORMAT) + BACKUP_SUFFIX;
        Path tempZip = backupDir.resolve(backupName + ".tmp").normalize();
        Path finalZip = backupDir.resolve(backupName).normalize();

        try {
            Files.createDirectories(stagingDir);
            BackupPayload payload = prepareBackupPayload(stagingDir);
            writeZip(stagingDir, tempZip);
            moveBackupZip(tempZip, finalZip);
            long size = Files.size(finalZip);
            pruneBackups(backupDir);
            backupRepository.finishRun(runId, "success", finalZip, size, "备份完成", payload.manifest());

            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "success");
            result.put("backupFile", finalZip.toString());
            result.put("sizeBytes", size);
            result.set("manifest", payload.manifest());
            return result;
        } catch (Exception error) {
            deleteIfExists(tempZip);
            backupRepository.finishRun(runId, "failed", null, 0, error.getMessage(), null);
            if (error instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "备份失败：" + error.getMessage(), error);
        } finally {
            deleteDirectory(stagingDir);
            backupRunning.set(false);
        }
    }

    private BackupPayload prepareBackupPayload(Path stagingDir) throws IOException, InterruptedException {
        ObjectNode db = databaseService.readDb();
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("backupVersion", 1);
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("hostName", hostName());
        manifest.put("revision", db.path("_revision").asText(""));
        manifest.put("patientCount", db.path("patients").size());
        manifest.put("documentCount", db.path("documents").size());
        manifest.put("attachmentDir", fileService.attachmentDir().toString());
        manifest.put("database", parseJdbcTarget().database());

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(stagingDir.resolve("manifest.json").toFile(), manifest);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(stagingDir.resolve("clinic-db.json").toFile(), db);
        writeDatabaseDump(stagingDir.resolve("database.sql"));
        copyAttachments(stagingDir.resolve("attachments"));
        return new BackupPayload(manifest);
    }

    private void writeDatabaseDump(Path target) throws IOException, InterruptedException {
        JdbcTarget jdbcTarget = parseJdbcTarget();
        String dumpExecutable = resolveMysqlDumpExecutable();
        Path optionFile = null;
        List<String> command = new ArrayList<>();
        command.add(dumpExecutable);
        if (!datasourcePassword.isBlank()) {
            optionFile = Files.createTempFile(target.getParent(), ".mysqldump-", ".cnf");
            String escapedPassword = datasourcePassword.replace("\\", "\\\\").replace("\"", "\\\"");
            Files.writeString(optionFile, "[client]\npassword=\"" + escapedPassword + "\"\n", StandardCharsets.UTF_8);
            optionFile.toFile().setReadable(false, false);
            optionFile.toFile().setWritable(false, false);
            optionFile.toFile().setReadable(true, true);
            optionFile.toFile().setWritable(true, true);
            command.add("--defaults-extra-file=" + optionFile.toAbsolutePath());
        }
        command.add("--host=" + jdbcTarget.host());
        command.add("--port=" + jdbcTarget.port());
        command.add("--user=" + datasourceUsername);
        command.add("--single-transaction");
        command.add("--quick");
        command.add("--default-character-set=utf8mb4");
        command.add(jdbcTarget.database());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(target.toFile());
        Path errorLog = target.resolveSibling("database-dump.err.log");
        builder.redirectError(errorLog.toFile());
        try {
            Process process = builder.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String error = Files.exists(errorLog) ? Files.readString(errorLog, StandardCharsets.UTF_8) : "";
                throw new IOException("mysqldump 执行失败：" + error.strip());
            }
        } finally {
            if (optionFile != null) deleteIfExists(optionFile);
        }
        deleteIfExists(errorLog);
    }

    private void copyAttachments(Path targetDir) throws IOException {
        Path sourceDir = fileService.attachmentDir();
        Files.createDirectories(targetDir);
        if (!Files.exists(sourceDir)) {
            return;
        }
        Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(targetDir.resolve(sourceDir.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceDir.relativize(file);
                Path target = targetDir.resolve(relative.toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void writeZip(Path sourceDir, Path targetZip) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(targetZip))) {
            try (var paths = Files.walk(sourceDir)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String entryName = sourceDir.relativize(path).toString().replace("\\", "/");
                    zip.putNextEntry(new ZipEntry(entryName));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        }
    }

    private void moveBackupZip(Path tempZip, Path finalZip) throws IOException {
        try {
            Files.move(tempZip, finalZip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(tempZip, finalZip, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path validateBackupDir(String rawBackupDir) {
        if (rawBackupDir == null || rawBackupDir.trim().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备份路径不能为空");
        }
        Path backupDir = Path.of(rawBackupDir.trim()).toAbsolutePath().normalize();
        if (backupDir.getParent() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能把磁盘根目录作为备份路径");
        }
        Path attachmentDir = fileService.attachmentDir().toAbsolutePath().normalize();
        if (backupDir.equals(attachmentDir) || backupDir.startsWith(attachmentDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备份路径不能放在附件目录内");
        }
        if (!mysqlDataDir.isBlank()) {
            Path dataDir = Path.of(mysqlDataDir).toAbsolutePath().normalize();
            if (backupDir.equals(dataDir) || backupDir.startsWith(dataDir)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备份路径不能放在 MySQL 数据目录内");
            }
        }
        return backupDir;
    }

    private void testWritable(Path backupDir) {
        try {
            Files.createDirectories(backupDir);
            Path testFile = backupDir.resolve(".clinic-backup-write-test-" + UUID.randomUUID() + ".tmp");
            Files.writeString(testFile, "ok", StandardCharsets.UTF_8);
            Files.delete(testFile);
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备份路径不可写：" + error.getMessage(), error);
        }
    }

    private String resolveInitialDirectory(String initialDir) {
        if (initialDir != null && !initialDir.isBlank()) {
            Path path = Path.of(initialDir.trim()).toAbsolutePath().normalize();
            Path candidate = Files.isDirectory(path) ? path : path.getParent();
            if (candidate != null) return candidate.toString();
        }
        return new File(System.getProperty("user.home", ".")).getAbsolutePath();
    }

    private BackupConfig readConfig() {
        return backupRepository.readConfig(CONFIG_ID);
    }

    private void pruneBackups(Path backupDir) throws IOException {
        if (!Files.isDirectory(backupDir)) {
            return;
        }
        List<BackupFile> backups;
        try (var paths = Files.list(backupDir)) {
            backups = paths
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith(BACKUP_PREFIX) && name.endsWith(BACKUP_SUFFIX);
                })
                .map(path -> new BackupFile(path, extractBackupTime(path)))
                .sorted(Comparator.comparing(BackupFile::createdAt).reversed())
                .toList();
        }

        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(zone);
        Set<Path> keep = new HashSet<>();
        Set<String> weeklyKeys = new HashSet<>();
        Set<YearMonth> monthlyKeys = new HashSet<>();

        for (BackupFile backup : backups) {
            LocalDate backupDate = backup.createdAt().atZone(zone).toLocalDate();
            if (!backupDate.isBefore(today.minusDays(6))) {
                keep.add(backup.path());
                continue;
            }

            if (!backupDate.isBefore(today.minusWeeks(4))) {
                String weekKey = backupDate.get(IsoFields.WEEK_BASED_YEAR) + "-W" + backupDate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                if (weeklyKeys.add(weekKey)) {
                    keep.add(backup.path());
                }
                continue;
            }

            if (!backupDate.isBefore(today.minusMonths(12))) {
                YearMonth monthKey = YearMonth.from(backupDate);
                if (monthlyKeys.add(monthKey)) {
                    keep.add(backup.path());
                }
            }
        }

        for (BackupFile backup : backups) {
            if (!keep.contains(backup.path())) {
                Files.deleteIfExists(backup.path());
            }
        }
    }

    private Instant extractBackupTime(Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith(BACKUP_PREFIX) && name.endsWith(BACKUP_SUFFIX)) {
            String timestamp = name.substring(BACKUP_PREFIX.length(), name.length() - BACKUP_SUFFIX.length());
            try {
                return LocalDateTime.parse(timestamp, BACKUP_NAME_FORMAT).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
            } catch (Exception ignored) {
            }
        }
        return lastModifiedTime(path);
    }

    private Instant lastModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException error) {
            return Instant.EPOCH;
        }
    }

    private int countBackupFiles(Path backupDir) {
        if (!Files.isDirectory(backupDir)) return 0;
        try (var paths = Files.list(backupDir)) {
            return (int) paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith(BACKUP_PREFIX))
                .count();
        } catch (IOException error) {
            return 0;
        }
    }

    private long sumBackupFiles(Path backupDir) {
        if (!Files.isDirectory(backupDir)) return 0;
        try (var paths = Files.list(backupDir)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith(BACKUP_PREFIX))
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException error) {
                        return 0;
                    }
                })
                .sum();
        } catch (IOException error) {
            return 0;
        }
    }

    private long safeUsableSpace(Path dir) {
        try {
            Files.createDirectories(dir);
            return dir.toFile().getUsableSpace();
        } catch (Exception error) {
            return 0;
        }
    }

    private String resolveMysqlDumpExecutable() {
        List<Path> candidates = new ArrayList<>();
        if (!mysqlDumpPath.isBlank()) {
            candidates.add(Path.of(mysqlDumpPath));
        }
        String mysqlHome = System.getenv("MYSQL_HOME");
        if (mysqlHome != null && !mysqlHome.isBlank()) {
            candidates.add(Path.of(mysqlHome, "bin", "mysqldump.exe"));
            candidates.add(Path.of(mysqlHome, "bin", "mysqldump"));
        }
        candidates.add(Path.of("C:\\Program Files\\MySQL\\MySQL Server 9.5\\bin\\mysqldump.exe"));
        candidates.add(Path.of("C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysqldump.exe"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return "mysqldump";
    }

    private JdbcTarget parseJdbcTarget() {
        try {
            String raw = datasourceUrl.replaceFirst("^jdbc:", "");
            URI uri = URI.create(raw);
            String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
            int port = uri.getPort() < 0 ? 3306 : uri.getPort();
            String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
            String database = path.isBlank() ? "hos_refactor" : path;
            return new JdbcTarget(host, port, database);
        } catch (Exception error) {
            return new JdbcTarget("127.0.0.1", 3306, "hos_refactor");
        }
    }

    private String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception error) {
            return "unknown";
        }
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private record BackupPayload(ObjectNode manifest) {
    }

    private record JdbcTarget(String host, int port, String database) {
    }

    private record BackupFile(Path path, Instant createdAt) {
    }
}
