package com.coshare.patientrecord.file.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.file.dto.ClinicFileUploadRequest;
import com.coshare.patientrecord.file.model.ClinicStoredFile;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClinicFileService {

    private final Path attachmentDir;
    private final long maxSizeBytes;
    private final Set<String> allowedMimeTypes;

    public ClinicFileService(
        @Value("${clinic.attachment-dir}") String attachmentDir,
        @Value("${clinic.attachment.max-size-bytes:52428800}") long maxSizeBytes,
        @Value("${clinic.attachment.allowed-mime-types:image/jpeg,image/jpg,image/png,image/webp,image/bmp,image/gif,application/pdf,text/html}") String allowedMimeTypes
    ) {
        this.attachmentDir = Path.of(attachmentDir).toAbsolutePath().normalize();
        this.maxSizeBytes = maxSizeBytes;
        this.allowedMimeTypes = new HashSet<>(
            List.of(allowedMimeTypes.split(",")).stream().map(String::trim).filter(value -> !value.isBlank()).toList()
        );
    }

    public ClinicStoredFile store(ClinicFileUploadRequest request) throws IOException {
        DataUrl dataUrl = parseDataUrl(request.contentDataUrl());
        validateFile(dataUrl);
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String patientSegment = sanitizePathSegment(request.patientId());
        String safeName = sanitizeFileName(request.fileName());
        String storagePath = String.join(
            "/",
            List.of(datePath, patientSegment, System.currentTimeMillis() + "-" + UUID.randomUUID() + "-" + safeName)
        );
        Path target = attachmentDir.resolve(storagePath).normalize();
        if (!target.startsWith(attachmentDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件保存路径不合法");
        }

        Files.createDirectories(target.getParent());
        Files.write(target, dataUrl.bytes());

        return new ClinicStoredFile(
            request.fileName(),
            "/clinic-api/files/" + storagePath.replace("\\", "/"),
            storagePath.replace("\\", "/"),
            dataUrl.bytes().length,
            dataUrl.mimeType(),
            sha256(dataUrl.bytes())
        );
    }

    public ClinicStoredFile store(ClinicFileUploadRequest request, SessionUser user) throws IOException {
        ClinicFileUploadRequest trustedRequest = new ClinicFileUploadRequest(
            request.fileName(),
            request.contentDataUrl(),
            request.patientId(),
            user.department(),
            user.name(),
            user.role(),
            request.type(),
            request.typeLabel()
        );
        return store(trustedRequest);
    }

    public FileSystemResource load(String rawStoragePath) {
        String storagePath = URLDecoder.decode(rawStoragePath, StandardCharsets.UTF_8);
        Path target = attachmentDir.resolve(storagePath).normalize();
        if (!target.startsWith(attachmentDir) || !Files.exists(target) || !Files.isRegularFile(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        return new FileSystemResource(target);
    }

    public Map<String, Object> inspectStorage(List<String> storagePaths) throws IOException {
        Files.createDirectories(attachmentDir);
        long totalBytes = 0;
        long fileCount = 0;
        try (var paths = Files.walk(attachmentDir)) {
            var iterator = paths.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                fileCount += 1;
                totalBytes += Files.size(file);
            }
        }

        long missingCount = storagePaths.stream()
            .filter(path -> path != null && !path.isBlank())
            .filter(path -> {
                Path target = attachmentDir.resolve(path).normalize();
                return !target.startsWith(attachmentDir) || !Files.exists(target) || !Files.isRegularFile(target);
            })
            .count();

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("attachmentDir", attachmentDir.toString());
        status.put("totalBytes", totalBytes);
        status.put("fileCount", fileCount);
        status.put("referencedFileCount", storagePaths.size());
        status.put("missingFileCount", missingCount);
        status.put("usableSpaceBytes", attachmentDir.toFile().getUsableSpace());
        status.put("totalSpaceBytes", attachmentDir.toFile().getTotalSpace());
        return status;
    }

    public Map<String, Object> summarizeStorage(List<String> storagePaths) throws IOException {
        Files.createDirectories(attachmentDir);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("attachmentDir", attachmentDir.toString());
        status.put("totalBytes", 0);
        status.put("fileCount", 0);
        status.put("referencedFileCount", storagePaths.size());
        status.put("missingFileCount", 0);
        status.put("usableSpaceBytes", attachmentDir.toFile().getUsableSpace());
        status.put("totalSpaceBytes", attachmentDir.toFile().getTotalSpace());
        status.put("scanSkipped", true);
        return status;
    }

    public Path attachmentDir() {
        return attachmentDir;
    }

    private DataUrl parseDataUrl(String contentDataUrl) {
        if (contentDataUrl == null || contentDataUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容不能为空，请重新选择原始文件上传");
        }
        int commaIndex = contentDataUrl.indexOf(',');
        if (!contentDataUrl.startsWith("data:") || commaIndex < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容格式不正确，请重新选择原始文件上传");
        }

        String meta = contentDataUrl.substring(5, commaIndex);
        String mimeType = meta.split(";")[0];
        if (!meta.contains(";base64") || mimeType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容不是有效的 base64 数据，请重新选择原始文件上传");
        }

        try {
            return new DataUrl(mimeType, Base64.getDecoder().decode(contentDataUrl.substring(commaIndex + 1)));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件 base64 内容无法解析，请重新选择原始文件上传", error);
        }
    }

    private void validateFile(DataUrl dataUrl) {
        if (dataUrl.bytes().length > maxSizeBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过系统允许大小");
        }
        if (!allowedMimeTypes.contains(dataUrl.mimeType())) {
            throw new ResponseStatusException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "文件类型不支持，请上传图片、PDF 或系统生成的检验报告"
            );
        }
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "attachment";
        }
        String safe = fileName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isBlank() ? "attachment" : safe;
    }

    private String sanitizePathSegment(String value) {
        String safe = String.valueOf(value == null ? "unassigned" : value).replaceAll("[^A-Za-z0-9._-]", "_").trim();
        return safe.isBlank() ? "unassigned" : safe;
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder();
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to hash file", error);
        }
    }

    private record DataUrl(String mimeType, byte[] bytes) {
    }
}
