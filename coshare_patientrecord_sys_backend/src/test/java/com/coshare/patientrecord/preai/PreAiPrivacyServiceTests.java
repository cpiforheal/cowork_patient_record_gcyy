package com.coshare.patientrecord.preai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class PreAiPrivacyServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PreAiPrivacyService service = new PreAiPrivacyService(objectMapper);

    @Test
    void masksIdentityAndKeepsClinicalFacts() {
        ObjectNode workspace = sampleWorkspace();

        ObjectNode masked = service.maskWorkspace(workspace);

        assertEquals("周xx", masked.path("patient").path("patientName").asText());
        assertEquals("138****5678", masked.path("patient").path("phone").asText());
        assertFalse(masked.path("patient").has("identityNumber"));
        assertEquals("固始县城关镇", masked.path("patient").path("address").asText());
        assertEquals("便血3月", masked.path("stages").path("RECEPTION").path("chiefComplaint").asText());
        assertFalse(masked.toString().contains("411525199001011234"));
        assertFalse(masked.toString().contains("张医生"));
        assertFalse(masked.toString().contains("原始照片.jpg"));
    }

    @Test
    void rendersValidDocxWithoutSensitiveValuesOrRawImages() throws Exception {
        ObjectNode workspace = sampleWorkspace();
        ObjectNode masked = service.maskWorkspace(workspace);

        byte[] docx = service.renderDocx(masked, workspace);
        String documentXml = unzipEntry(docx, "word/document.xml");

        assertTrue(docx.length > 1000);
        assertTrue(documentXml.contains("脱敏前置病历资料"));
        assertTrue(documentXml.contains("便血3月"));
        assertTrue(documentXml.contains("周xx"));
        assertFalse(documentXml.contains("周明华"));
        assertFalse(documentXml.contains("13812345678"));
        assertFalse(documentXml.contains("411525199001011234"));
        assertFalse(documentXml.contains("幸福路88号"));
        assertFalse(documentXml.contains("原始照片.jpg"));
        assertFalse(documentXml.contains("张医生"));
    }

    @Test
    void masksCompoundSurnameAndShortContactValues() {
        assertEquals("欧阳xx", service.maskName("欧阳明"));
        assertEquals("已脱敏", service.maskPhone("12345"));
        assertEquals("已脱敏", service.coarseAddress("幸福路88号2单元"));
    }

    private ObjectNode sampleWorkspace() {
        ObjectNode workspace = objectMapper.createObjectNode();
        ObjectNode encounter = workspace.putObject("encounter");
        encounter.put("id", "preai-test");
        encounter.put("caseToken", "CASE-20260710-ABC123");
        encounter.put("route", "INPATIENT");
        encounter.put("treatmentPath", "SURGICAL");
        encounter.put("reviewedAt", "2026-07-10 10:00:00");
        encounter.put("reviewedBy", "张医生");
        encounter.put("reviewedByRole", "doctor");
        ObjectNode patient = encounter.putObject("patient");
        patient.put("patientName", "周明华");
        patient.put("gender", "男");
        patient.put("age", "46");
        patient.put("phone", "13812345678");
        patient.put("identityNumber", "411525199001011234");
        patient.put("address", "河南省固始县城关镇幸福路88号2单元");
        patient.put("contactName", "周大成");
        patient.put("contactPhone", "13912345678");
        patient.put("visitNo", "ZY20260710001");
        patient.put("visitDate", "2026-07-10 08:30:00");

        ArrayNode stages = workspace.putArray("stages");
        addStage(stages, "REGISTRATION", "COMPLETED", objectMapper.createObjectNode());
        ObjectNode reception = objectMapper.createObjectNode();
        reception.put("chiefComplaint", "便血3月");
        reception.put("presentIllness", "周明华诉反复便血，联系电话13812345678。ZY20260710001");
        addStage(stages, "RECEPTION", "COMPLETED", reception);
        ObjectNode inspection = objectMapper.createObjectNode();
        inspection.put("factualConclusion", "截石位见肛缘肿物");
        addStage(stages, "INSPECTION", "COMPLETED", inspection);
        ObjectNode tcm = objectMapper.createObjectNode();
        tcm.put("tcmDisease", "痔病");
        tcm.put("primarySyndrome", "湿热下注证");
        tcm.put("inspection", "面色尚可");
        tcm.put("inquiry", "便血色鲜");
        tcm.put("tongue", "舌红苔黄腻");
        tcm.put("pulse", "脉滑数");
        tcm.put("treatmentPrinciple", "清热利湿");
        addStage(stages, "TCM", "COMPLETED", tcm);
        ObjectNode doctor = objectMapper.createObjectNode();
        doctor.put("primaryWesternDiagnosis", "混合痔");
        doctor.put("treatmentPlan", "拟择期手术治疗");
        addStage(stages, "DOCTOR", "COMPLETED", doctor);
        ObjectNode surgery = objectMapper.createObjectNode();
        surgery.put("actualOperationName", "混合痔外剥内扎术");
        surgery.put("operationDate", "2026-07-10");
        surgery.put("intraoperativeFindings", "术中见多发混合痔");
        surgery.put("procedurePerformed", "完成外剥内扎");
        surgery.put("postoperativeHandoff", "生命体征平稳，送回病房");
        addStage(stages, "SURGERY", "COMPLETED", surgery);

        workspace.putArray("auxiliaryTasks");
        ObjectNode attachment = workspace.putArray("attachments").addObject();
        attachment.put("fileName", "周明华-原始照片.jpg");
        attachment.put("uploader", "张医生");
        workspace.putArray("diagnoses");
        workspace.putArray("auditLogs");
        workspace.putArray("exports");
        return workspace;
    }

    private void addStage(ArrayNode stages, String code, String status, ObjectNode data) {
        ObjectNode stage = stages.addObject();
        stage.put("stageCode", code);
        stage.put("status", status);
        stage.set("data", data);
    }

    private String unzipEntry(byte[] bytes, String entryName) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}


