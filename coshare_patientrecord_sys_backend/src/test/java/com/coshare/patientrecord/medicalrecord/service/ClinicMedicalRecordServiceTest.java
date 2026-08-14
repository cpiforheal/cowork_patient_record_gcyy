package com.coshare.patientrecord.medicalrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.medicalrecord.dto.WorkspaceSaveRequest;
import com.coshare.patientrecord.medicalrecord.ooxml.DocxControlledEditor;
import com.coshare.patientrecord.medicalrecord.ooxml.DocxNodeMapper;
import com.coshare.patientrecord.medicalrecord.repository.MedicalRecordVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ClinicMedicalRecordServiceTest {

    @Mock
    private MedicalRecordVersionRepository versionRepository;

    @Mock
    private MedicalRecordTemplateRenderer templateRenderer;

    @Mock
    private DocxNodeMapper nodeMapper;

    @Mock
    private DocxControlledEditor controlledEditor;

    @Mock
    private MedicalRecordSourceBuilder sourceBuilder;

    @Mock
    private InpatientRecordAiService inpatientRecordAiService;

    private ObjectMapper objectMapper;
    private ClinicMedicalRecordService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ClinicMedicalRecordService(
            versionRepository,
            objectMapper,
            templateRenderer,
            nodeMapper,
            controlledEditor,
            sourceBuilder,
            inpatientRecordAiService,
            "build/generated-medical-record-test"
        );
        doReturn(Set.of()).when(templateRenderer).templatePlaceholderKeys(anyString());
    }

    @Test
    void labWorkspaceSaveUsesPreAiScopeInsteadOfLegacyPatientLookup() {
        SessionUser lab = user("lab");
        ObjectNode source = objectMapper.createObjectNode();
        source.putObject("patient");
        source.putObject("recordFields");
        doReturn(source).when(sourceBuilder).readEncounterSource(
            eq("encounter-1"),
            eq(lab),
            eq(false),
            anyString(),
            anyString()
        );
        doReturn(objectMapper.createArrayNode()).when(sourceBuilder).missingItems(any(ObjectNode.class), anyList());

        Map<String, Object> result = service.saveWorkspace(
            new WorkspaceSaveRequest("preai:encounter-1", Map.of("patientName", "Alice")),
            lab
        );

        assertThat(result).containsKey("values");
        verify(sourceBuilder).assertCanReadScope("preai:encounter-1", lab);
        verify(sourceBuilder, never()).assertCanReadPatient(anyString(), any());
        verify(versionRepository).upsertRecordField("preai:encounter-1", "patientName", "Alice");
        verify(versionRepository).writeAudit(eq("preai:encounter-1"), eq(lab), anyString(), eq("medical-record.workspace.save"), anyString());
    }

    @Test
    void preAiWorkspaceSaveDoesNotFailWhenEncounterIsNotReadyForTargetGeneration() {
        SessionUser lab = user("lab");
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "not reviewed")).when(sourceBuilder).readEncounterSource(
            eq("encounter-1"),
            eq(lab),
            eq(false),
            anyString(),
            anyString()
        );

        Map<String, Object> result = service.saveWorkspace(
            new WorkspaceSaveRequest("preai:encounter-1", Map.of("patientName", "Alice")),
            lab
        );

        assertThat(result).containsKey("missingItems");
        verify(versionRepository).upsertRecordField("preai:encounter-1", "patientName", "Alice");
    }

    private SessionUser user(String role) {
        return new SessionUser("user-1", role, role, role, role, "dept-user", "clinic", false, Instant.now().plusSeconds(3600));
    }
}
