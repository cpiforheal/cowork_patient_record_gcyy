package com.coshare.patientrecord.medicalrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.medicalrecord.dto.DownloadFile;
import com.coshare.patientrecord.medicalrecord.dto.MedicalRecordWorkflowSubmitRequest;
import com.coshare.patientrecord.medicalrecord.ooxml.DocxNodeMapper;
import com.coshare.patientrecord.medicalrecord.ooxml.DocxPackageSanitizer;
import com.coshare.patientrecord.medicalrecord.repository.MedicalRecordWorkflowRepository;
import com.coshare.patientrecord.medicalrecord.repository.MedicalRecordWorkflowRepository.Inspection;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MedicalRecordWorkflowServiceTest {

    @Mock private MedicalRecordWorkflowRepository repository;
    @Mock private ClinicMedicalRecordService medicalRecordService;
    @Mock private MedicalRecordSourceBuilder sourceBuilder;
    @Mock private ThreadPoolTaskExecutor executor;

    @TempDir Path temporaryDirectory;

    @Test
    void inspectUploadRejectsNonAuthorBeforePersistingFile() {
        MedicalRecordWorkflowService service = service();
        MockMultipartFile file = new MockMultipartFile(
            "document",
            "reference.docx",
            "application/octet-stream",
            new byte[] {1}
        );

        assertThatThrownBy(() -> service.inspectUpload("patient-1", "", file, user("nurse")))
            .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN)
            );
        verify(repository, never()).insertAsset(any(), any());
    }

    @Test
    void inspectUploadPersistsAcceptedSourceAssetAndReport() throws Exception {
        MedicalRecordWorkflowService service = service();
        byte[] document = validDocx();
        MockMultipartFile file = new MockMultipartFile(
            "document",
            "reference.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            document
        );

        Map<String, Object> result = service.inspectUpload("patient-1", "", file, user("doctor"));

        assertThat(result)
            .containsEntry("decision", "ACCEPTED")
            .containsEntry("canGenerate", true);
        assertThat(result.get("nodes")).asList().hasSize(1);
        assertThat(result.get("sourceAssetId")).asString().startsWith("mrasset-");
        assertThat(result.get("sanitizedAssetId")).isEqualTo("");
        ArgumentCaptor<MedicalRecordWorkflowRepository.Asset> assetCaptor =
            ArgumentCaptor.forClass(MedicalRecordWorkflowRepository.Asset.class);
        verify(repository).insertAsset(assetCaptor.capture(), any());
        assertThat(assetCaptor.getValue().assetType()).isEqualTo("SOURCE");
        assertThat(assetCaptor.getValue().packageVerified()).isTrue();
        assertThat(Path.of(assetCaptor.getValue().storagePath())).exists();
        verify(repository).insertReport(any(), any(), any(), any(), any());
    }

    @Test
    void submitRejectsLegacyModeWithTargetNodeKeys() {
        MedicalRecordWorkflowService service = service();
        when(repository.loadInspection("report-1")).thenReturn(new Inspection(
            "report-1",
            "source-1",
            "",
            "ACCEPTED",
            "LOW",
            Map.of(),
            Map.of(),
            "patient-1",
            "patient-1",
            ""
        ));

        MedicalRecordWorkflowSubmitRequest request = new MedicalRecordWorkflowSubmitRequest(
            "report-1",
            "record-1",
            "",
            "LEGACY_ORDINAL",
            java.util.List.of("cc:diagnosis")
        );

        assertThatThrownBy(() -> service.submit(request, user("doctor")))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getReason()).contains("不支持指定目标节点");
            });
        verify(repository, never()).insertTask(any(), any());
    }

    @Test
    void downloadAssetRejectsAssetWithoutVerifiedDocxPackage() throws Exception {
        MedicalRecordWorkflowService service = service();
        Path assetPath = temporaryDirectory.resolve("unverified.docx");
        byte[] bytes = validDocx();
        java.nio.file.Files.write(assetPath, bytes);
        when(repository.loadAsset("asset-1")).thenReturn(new MedicalRecordWorkflowRepository.Asset(
            "asset-1", "patient-1", "patient-1", "", "OUTPUT", "record.docx",
            assetPath.toString(),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            bytes.length, "unused", "", true, false, Map.of()
        ));

        assertThatThrownBy(() -> service.downloadAsset("asset-1", user("doctor")))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getReason()).contains("未通过 DOCX 安全校验");
            });
    }

    @Test
    void failedAsyncRunInvokesCompensationBeforeFailingTask() {
        final Runnable[] queuedTask = new Runnable[1];
        org.mockito.Mockito.doAnswer(invocation -> {
            queuedTask[0] = invocation.getArgument(0);
            return null;
        }).when(executor).execute(any(Runnable.class));
        MedicalRecordWorkflowService service = service();
        SessionUser doctor = user("doctor");
        when(repository.loadInspection("report-1")).thenReturn(new Inspection(
            "report-1", "source-1", "", "ACCEPTED", "LOW", Map.of(), Map.of(),
            "patient-1", "patient-1", ""
        ));
        when(repository.loadOwnedTask(any(), any())).thenReturn(new MedicalRecordWorkflowRepository.TaskRow(
            "task-ignored", "patient-1", "patient-1", "", "record-source", "source-1", "", "report-1",
            "", "", "RUNNING", "ASSET_LOADING", "CONTROLLED", "", "", Map.of(), Map.of(), 1, "", "", "",
            "tester", "doctor", "", "", "", ""
        ));
        when(repository.claimTask(any())).thenReturn(true);
        when(repository.loadAsset("source-1")).thenThrow(new IllegalStateException("asset failure"));
        when(repository.compensateFailedRun(any(), any(), any())).thenReturn(true);

        service.submit(new MedicalRecordWorkflowSubmitRequest(
            "report-1", "record-source", "", "CONTROLLED", java.util.List.of()
        ), doctor);
        assertThat(queuedTask[0]).isNotNull();
        queuedTask[0].run();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository);
        order.verify(repository).compensateFailedRun(any(), org.mockito.ArgumentMatchers.eq(""), org.mockito.ArgumentMatchers.eq(""));
        order.verify(repository).failTask(
            any(), org.mockito.ArgumentMatchers.eq("ASSET_LOADING"),
            org.mockito.ArgumentMatchers.eq("ILLEGALSTATEEXCEPTION"),
            org.mockito.ArgumentMatchers.contains("asset failure"), any()
        );
    }

    @Test
    void retryRejectsTaskThatHasNotFailed() {
        MedicalRecordWorkflowService service = service();
        SessionUser doctor = user("doctor");
        when(repository.loadOwnedTask("task-1", doctor)).thenReturn(taskRow("RUNNING"));

        assertThatThrownBy(() -> service.retry("task-1", doctor))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getReason()).contains("只有失败的病历生成任务可以重试");
            });
        verify(repository, never()).insertTask(any(), any());
    }

    @Test
    void downloadAssetRejectsPathOutsideManagedRoot() throws Exception {
        MedicalRecordWorkflowService service = service();
        Path outside = temporaryDirectory.resolveSibling("outside.docx");
        byte[] bytes = validDocx();
        Files.write(outside, bytes);
        when(repository.loadAsset("asset-outside")).thenReturn(asset("asset-outside", outside, bytes));

        assertThatThrownBy(() -> service.downloadAsset("asset-outside", user("doctor")))
            .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)
            );
        Files.deleteIfExists(outside);
    }

    @Test
    void downloadAssetRejectsDigestMismatch() throws Exception {
        MedicalRecordWorkflowService service = service();
        Path path = temporaryDirectory.resolve("changed.docx");
        byte[] bytes = validDocx();
        Files.write(path, bytes);
        MedicalRecordWorkflowRepository.Asset asset = asset("asset-changed", path, bytes);
        when(repository.loadAsset("asset-changed")).thenReturn(new MedicalRecordWorkflowRepository.Asset(
            asset.id(), asset.scopeId(), asset.patientId(), asset.encounterId(), asset.assetType(),
            asset.originalFileName(), asset.storagePath(), asset.mimeType(), asset.fileSize(),
            "0".repeat(64), asset.parentAssetId(), asset.mediaTypeVerified(), asset.packageVerified(), asset.metadata()
        ));

        assertThatThrownBy(() -> service.downloadAsset("asset-changed", user("doctor")))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(error.getReason()).contains("摘要校验失败");
            });
    }

    @Test
    void downloadAssetReturnsVerifiedManagedDocx() throws Exception {
        MedicalRecordWorkflowService service = service();
        SessionUser doctor = user("doctor");
        Path path = temporaryDirectory.resolve("verified.docx");
        byte[] bytes = validDocx();
        Files.write(path, bytes);
        when(repository.loadAsset("asset-verified")).thenReturn(asset("asset-verified", path, bytes));

        DownloadFile download = service.downloadAsset("asset-verified", doctor);

        assertThat(download.fileName()).isEqualTo("record.docx");
        assertThat(download.resource().getFile().toPath()).isEqualTo(path);
        verify(sourceBuilder).assertCanReadScope("patient-1", doctor);
    }

    @Test
    void submitRejectsFailedInspection() {
        MedicalRecordWorkflowService service = service();
        when(repository.loadInspection("report-1")).thenReturn(new Inspection(
            "report-1",
            "source-1",
            "",
            "REJECTED",
            "CRITICAL",
            Map.of(),
            Map.of(),
            "patient-1",
            "patient-1",
            ""
        ));

        MedicalRecordWorkflowSubmitRequest request = new MedicalRecordWorkflowSubmitRequest(
            "report-1",
            "record-1",
            "",
            "CONTROLLED",
            java.util.List.of()
        );
        assertThatThrownBy(() -> service.submit(request, user("doctor")))
            .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
            );
        verify(repository, never()).insertTask(any(), any());
    }

    private MedicalRecordWorkflowRepository.TaskRow taskRow(String status) {
        return new MedicalRecordWorkflowRepository.TaskRow(
            "task-1", "patient-1", "patient-1", "", "record-1", "source-1", "", "report-1",
            "", "", status, "ASSET_LOADING", "CONTROLLED", "", "", Map.of(), Map.of(), 1,
            "", "", "", "tester", "doctor", "", "", "", ""
        );
    }

    private MedicalRecordWorkflowRepository.Asset asset(String id, Path path, byte[] bytes) throws Exception {
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new MedicalRecordWorkflowRepository.Asset(
            id, "patient-1", "patient-1", "", "OUTPUT", "record.docx", path.toString(),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            bytes.length, sha256, "", true, true, Map.of()
        );
    }

    private MedicalRecordWorkflowService service() {
        return new MedicalRecordWorkflowService(
            repository,
            new DocxPackageSanitizer(),
            new DocxNodeMapper(),
            medicalRecordService,
            sourceBuilder,
            executor,
            temporaryDirectory.toString()
        );
    }

    private SessionUser user(String role) {
        return new SessionUser(
            "user-1",
            "tester",
            "测试用户",
            role,
            role,
            "department-1",
            "测试科室",
            false,
            Instant.now().plusSeconds(3600)
        );
    }

    private byte[] validDocx() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """);
            entry(zip, "_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """);
            entry(zip, "word/document.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>参考病历正文</w:t></w:r></w:p></w:body>
                </w:document>
                """);
        }
        return output.toByteArray();
    }

    private void entry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.strip().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
