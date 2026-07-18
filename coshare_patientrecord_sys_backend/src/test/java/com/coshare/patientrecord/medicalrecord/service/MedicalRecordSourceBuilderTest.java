package com.coshare.patientrecord.medicalrecord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.clinic.service.ClinicDatabaseService;
import com.coshare.patientrecord.common.privacy.SensitiveDataMasker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class MedicalRecordSourceBuilderTest {

    private static final String PATIENT_ID = "patient-1";

    @Mock
    private ClinicDatabaseService databaseService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ObjectMapper objectMapper;
    private MedicalRecordSourceBuilder sourceBuilder;
    private SessionUser user;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        sourceBuilder = new MedicalRecordSourceBuilder(
            objectMapper,
            databaseService,
            jdbcTemplate,
            new SensitiveDataMasker()
        );
        user = new SessionUser("user-1", "doctor", "Doctor", "doctor", "Doctor", "TCM", false, Instant.now().plusSeconds(3600));
    }

    @Test
    void usesReviewedPreAiTcmFactsWhenTargetRecordFieldsAreBlank() {
        ObjectNode db = databaseWithRecord("", "");
        when(databaseService.readDbForUser(user)).thenReturn(db);
        mockReviewedTcmFacts("舌淡红，苔薄白", "脉弦");

        ObjectNode source = sourceBuilder.readPatientSource(PATIENT_ID, user, false, "template", "v1");

        assertThat(source.path("recordFields").path("tongue").asText()).isEqualTo("舌淡红，苔薄白");
        assertThat(source.path("recordFields").path("pulseCondition").asText()).isEqualTo("脉弦");
    }

    @Test
    void keepsExplicitTargetRecordFieldsInsteadOfOverwritingThem() {
        ObjectNode db = databaseWithRecord("目标病历舌象", "目标病历脉象");
        when(databaseService.readDbForUser(user)).thenReturn(db);
        mockReviewedTcmFacts("前置采集舌象", "前置采集脉象");

        ObjectNode source = sourceBuilder.readPatientSource(PATIENT_ID, user, false, "template", "v1");

        assertThat(source.path("recordFields").path("tongue").asText()).isEqualTo("目标病历舌象");
        assertThat(source.path("recordFields").path("pulseCondition").asText()).isEqualTo("目标病历脉象");
    }

    private ObjectNode databaseWithRecord(String tongue, String pulseCondition) {
        ObjectNode db = objectMapper.createObjectNode();
        ArrayNode patients = db.putArray("patients");
        patients.addObject().put("id", PATIENT_ID).put("name", "Test Patient");
        ObjectNode record = db.putObject("records").putObject(PATIENT_ID);
        record.put("tongue", tongue);
        record.put("pulseCondition", pulseCondition);
        db.putObject("documents").putArray(PATIENT_ID);
        return db;
    }

    @SuppressWarnings("unchecked")
    private void mockReviewedTcmFacts(String tongue, String pulse) {
        ObjectNode facts = objectMapper.createObjectNode();
        facts.put("tongue", tongue);
        facts.put("pulse", pulse);
        doReturn(List.of(facts)).when(jdbcTemplate).query(
            anyString(),
            any(RowMapper.class),
            eq(PATIENT_ID)
        );
    }
}
