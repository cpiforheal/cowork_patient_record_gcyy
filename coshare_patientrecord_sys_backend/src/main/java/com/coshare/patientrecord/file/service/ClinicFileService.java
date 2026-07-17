package com.coshare.patientrecord.file.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.file.dto.ClinicFileUploadRequest;
import com.coshare.patientrecord.file.model.ClinicStoredFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClinicFileService {

    private final Path attachmentDir;
    private final long maxSizeBytes;
    private final Set<String> allowedMimeTypes;
    private final Semaphore uploadPermits;

    public ClinicFileService(
        @Value("${clinic.attachment-dir}") String attachmentDir,
        @Value("${clinic.attachment.max-size-bytes:52428800}") long maxSizeBytes,
        @Value("${clinic.attachment.allowed-mime-types:image/jpeg,image/png,image/webp,image/bmp,image/gif,application/pdf}") String allowedMimeTypes,
        @Value("${clinic.attachment.max-concurrent-uploads:4}") int maxConcurrentUploads
    ) {
        this.attachmentDir = Path.of(attachmentDir).toAbsolutePath().normalize();
        this.maxSizeBytes = maxSizeBytes;
        this.allowedMimeTypes = new HashSet<>(
            List.of(allowedMimeTypes.split(",")).stream().map(this::normalizeMimeType).filter(value -> !value.isBlank()).toList()
        );
        this.uploadPermits = new Semaphore(Math.max(1, maxConcurrentUploads), true);
    }

    /** Compatibility path for the legacy Base64 JSON endpoint. */
    public ClinicStoredFile store(ClinicFileUploadRequest request) throws IOException {
        DataUrl dataUrl = parseDataUrl(request.contentDataUrl());
        return storeStream(request.fileName(), request.patientId(), dataUrl.mimeType(), new ByteArrayInputStream(dataUrl.bytes()));
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

    public ClinicStoredFile store(MultipartFile file, String patientId) throws IOException {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容不能为空");
        return storeStream(file.getOriginalFilename(), patientId, file.getContentType(), file.getInputStream());
    }

    private ClinicStoredFile storeStream(String originalFileName, String patientId, String clientMimeType, InputStream input) throws IOException {
        if (!uploadPermits.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "当前上传任务过多，请稍后重试");
        }
        Path temp = null;
        try (InputStream source = input) {
            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String patientSegment = sanitizePathSegment(patientId);
            String safeName = sanitizeFileName(originalFileName);
            String storagePath = String.join(
                "/",
                List.of(datePath, patientSegment, System.currentTimeMillis() + "-" + UUID.randomUUID() + "-" + safeName)
            );
            Path target = attachmentDir.resolve(storagePath).normalize();
            if (!target.startsWith(attachmentDir)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件保存路径不合法");
            }

            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (Exception error) {
                throw new IOException("Failed to initialize file digest", error);
            }
            byte[] header = new byte[16];
            int headerLength = 0;
            long size = 0;
            try (var output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    size += read;
                    if (size > maxSizeBytes) {
                        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过系统允许大小");
                    }
                    int copyLength = Math.min(read, header.length - headerLength);
                    if (copyLength > 0) {
                        System.arraycopy(buffer, 0, header, headerLength, copyLength);
                        headerLength += copyLength;
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (size == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容不能为空");
            String detectedMimeType = detectMimeType(header, headerLength);
            validateFile(safeName, clientMimeType, detectedMimeType);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target);
            }
            temp = null;

            return new ClinicStoredFile(
                safeName,
                "/clinic-api/files/" + storagePath.replace("\\", "/"),
                storagePath.replace("\\", "/"),
                size,
                detectedMimeType,
                hex(digest.digest())
            );
        } finally {
            if (temp != null) Files.deleteIfExists(temp);
            uploadPermits.release();
        }
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
        Map<String, Object> status = summarizeStorage(storagePaths);
        status.put("totalBytes", totalBytes);
        status.put("fileCount", fileCount);
        status.put("missingFileCount", missingCount);
        status.remove("scanSkipped");
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容不能为空");
        }
        int commaIndex = contentDataUrl.indexOf(',');
        if (!contentDataUrl.startsWith("data:") || commaIndex < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容格式不正确");
        }
        String meta = contentDataUrl.substring(5, commaIndex);
        String mimeType = meta.split(";", 2)[0];
        if (!meta.contains(";base64") || mimeType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容不是有效的 Base64 数据");
        }
        try {
            return new DataUrl(mimeType, Base64.getDecoder().decode(contentDataUrl.substring(commaIndex + 1)));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件 Base64 内容无法解析", error);
        }
    }

    private void validateFile(String fileName, String clientMimeType, String detectedMimeType) {
        String normalizedClientMime = normalizeMimeType(clientMimeType);
        String extensionMime = mimeTypeForExtension(fileName);
        if (detectedMimeType == null || extensionMime == null || !detectedMimeType.equals(extensionMime)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "文件扩展名与实际内容不一致");
        }
        if (!normalizedClientMime.isBlank() && !"application/octet-stream".equals(normalizedClientMime)
            && !normalizedClientMime.equals(detectedMimeType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "文件 MIME 与实际内容不一致");
        }
        if (!allowedMimeTypes.contains(detectedMimeType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "文件类型不支持，请上传受支持的图片或 PDF");
        }
    }

    private String detectMimeType(byte[] header, int length) {
        if (length >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff) return "image/jpeg";
        if (length >= 8 && header[0] == (byte) 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') return "image/png";
        if (length >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8') return "image/gif";
        if (length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
            && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') return "image/webp";
        if (length >= 2 && header[0] == 'B' && header[1] == 'M') return "image/bmp";
        if (length >= 5 && header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F' && header[4] == '-') return "application/pdf";
        return null;
    }

    private String mimeTypeForExtension(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return null;
    }

    private String normalizeMimeType(String value) {
        String mimeType = value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return "image/jpg".equals(mimeType) ? "image/jpeg" : mimeType;
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "attachment";
        String baseName;
        try {
            baseName = Path.of(fileName).getFileName().toString();
        } catch (Exception ignored) {
            baseName = fileName;
        }
        String safe = baseName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (safe.length() > 180) safe = safe.substring(safe.length() - 180);
        return safe.isBlank() ? "attachment" : safe;
    }

    private String sanitizePathSegment(String value) {
        String safe = String.valueOf(value == null ? "unassigned" : value).replaceAll("[^A-Za-z0-9._-]", "_").trim();
        return safe.isBlank() ? "unassigned" : safe;
    }

    private String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte item : bytes) builder.append(String.format("%02x", item));
        return builder.toString();
    }

    private record DataUrl(String mimeType, byte[] bytes) {}
}
