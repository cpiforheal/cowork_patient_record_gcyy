package com.coshare.patientrecord.preai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

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
        assertEquals("否认输血史", masked.path("stages").path("RECEPTION").path("transfusionHistory").asText());
        assertEquals("适龄结婚", masked.path("stages").path("RECEPTION").path("maritalHistory").path(0).asText());
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
        assertTrue(documentXml.contains("中医肛肠医院住院病历自动生成表"));
        for (String heading : List.of(
            "一、基础信息", "二、主诉", "三、现病史", "四、既往史 / 个人史 / 婚育史 / 家族史", "五、中医四诊", "六、专科检查",
            "七、辅助检查", "八、中西医主诊断", "九、次诊断（已选择）", "十、合并病中医病名及证型", "十一、手术 / 操作信息", "十二、DIP 病组与治疗路径",
            "十三、查房时序", "十四、自动生成文书范围", "十五、质控校验"
        )) assertTrue(documentXml.contains(heading), heading);
        assertTrue(documentXml.contains("<w:tbl>"));
        assertTrue(documentXml.contains("便血3月"));
        assertTrue(documentXml.contains("周xx"));
        assertTrue(documentXml.contains("视诊、指诊"));
        assertFalse(documentXml.contains("VISUAL"));
        assertFalse(documentXml.contains("INPATIENT"));
        assertFalse(documentXml.contains("SURGICAL"));
        assertTrue(documentXml.contains("输血史"));
        assertFalse(documentXml.contains("慢性病及重要既往史"));
        assertFalse(documentXml.contains("□"));
        assertFalse(documentXml.contains("周明华"));
        assertFalse(documentXml.contains("13812345678"));
        assertFalse(documentXml.contains("411525199001011234"));
        assertFalse(documentXml.contains("幸福路88号"));
        assertFalse(documentXml.contains("原始照片.jpg"));
        assertFalse(documentXml.contains("张医生"));
    }

    @Test
    void keepsTemplateSectionsWhenTheirFactsAreEmpty() throws Exception {
        ObjectNode workspace = objectMapper.createObjectNode();
        ObjectNode encounter = workspace.putObject("encounter");
        encounter.put("caseToken", "CASE-EMPTY");
        encounter.put("route", "OUTPATIENT");
        encounter.put("treatmentPath", "CONSERVATIVE");
        encounter.putObject("patient");
        workspace.putArray("stages");
        workspace.putArray("auxiliaryTasks");
        workspace.putArray("labReports");

        String documentXml = unzipEntry(service.renderDocx(service.maskWorkspace(workspace), workspace), "word/document.xml");

        assertTrue(documentXml.contains("一、基础信息"));
        assertTrue(documentXml.contains("七、辅助检查"));
        assertTrue(documentXml.contains("十五、质控校验"));
        assertFalse(documentXml.contains("VISUAL"));
        assertFalse(documentXml.contains("未填写指标"));
    }

    @Test
    void embedsReceptionImagesInDocxWhenAvailable() throws Exception {
        ObjectNode workspace = sampleWorkspace();
        Path directory = Files.createTempDirectory("pre-ai-image-test");
        Field field = PreAiPrivacyService.class.getDeclaredField("attachmentDirectory");
        field.setAccessible(true);
        Object previousDirectory = field.get(service);
        try {
            Files.write(directory.resolve("image.png"), Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
            ));
            field.set(service, directory.toString());
            ObjectNode attachment = workspace.withArray("attachments").addObject();
            attachment.put("stageCode", "INSPECTION");
            attachment.put("mimeType", "image/png");
            attachment.put("storagePath", "image.png");

            String documentXml = unzipEntry(service.renderDocx(service.maskWorkspace(workspace), workspace), "word/document.xml");
            assertTrue(documentXml.contains("接诊/检查图片"));
            assertTrue(documentXml.contains("rIdImage1"));
            assertTrue(hasZipEntry(service.renderDocx(service.maskWorkspace(workspace), workspace), "word/media/image1.png"));
        } finally {
            field.set(service, previousDirectory);
            Files.deleteIfExists(directory.resolve("image.png"));
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void marksNumericAndQualitativeLabAbnormalities() {
        ObjectNode high = objectMapper.createObjectNode();
        high.put("value", "12.5");
        high.put("reference", "4.0-10.0");
        ObjectNode low = objectMapper.createObjectNode();
        low.put("value", "3.2");
        low.put("reference", "4.0-10.0");
        ObjectNode positive = objectMapper.createObjectNode();
        positive.put("value", "阳性");
        positive.put("reference", "阴性");

        assertEquals("偏高", service.labAbnormalLabel(high));
        assertEquals("偏低", service.labAbnormalLabel(low));
        assertEquals("异常", service.labAbnormalLabel(positive));
    }

    @Test
    void docxKeepsOnlyFilledLabMetricsAndHighlightsAbnormalResult() throws Exception {
        ObjectNode workspace = sampleWorkspace();
        ObjectNode labTask = workspace.withArray("auxiliaryTasks").addObject();
        labTask.put("taskType", "LAB");
        labTask.put("status", "COMPLETED");
        ObjectNode report = workspace.withArray("labReports").addObject();
        report.put("templateName", "血常规");
        report.put("reportDate", "2026-07-10");
        ObjectNode abnormal = report.putArray("metrics").addObject();
        abnormal.put("name", "白细胞");
        abnormal.put("value", "12.5");
        abnormal.put("unit", "10^9/L");
        abnormal.put("reference", "4.0-10.0");
        ObjectNode empty = report.withArray("metrics").addObject();
        empty.put("name", "未填写指标");
        empty.put("value", "");
        empty.put("reference", "1-2");
        ObjectNode negative = report.withArray("metrics").addObject();
        negative.put("name", "艾滋病抗体");
        negative.put("value", "阴性");
        negative.put("reference", "阴性");

        ObjectNode masked = service.maskWorkspace(workspace);
        String documentXml = unzipEntry(service.renderDocx(masked, workspace), "word/document.xml");

        assertEquals("偏高", masked.path("labReports").path(0).path("metrics").path(0).path("abnormal").asText());
        assertEquals("ABNORMAL", masked.path("labReports").path(0).path("metrics").path(0).path("severity").asText());
        assertTrue(documentXml.contains("白细胞"));
        assertTrue(documentXml.contains("12.510^9/L"));
        assertTrue(documentXml.contains("【异常·偏高】"));
        assertTrue(documentXml.contains("艾滋病抗体"));
        assertTrue(documentXml.contains("阴性"));
        assertFalse(documentXml.contains("未填写指标"));
    }

    @Test
    void keepsCriticalSeverityAndMarksItInDocx() throws Exception {
        ObjectNode workspace = sampleWorkspace();
        ObjectNode labTask = workspace.withArray("auxiliaryTasks").addObject();
        labTask.put("taskType", "LAB");
        labTask.put("status", "COMPLETED");
        ObjectNode report = workspace.withArray("labReports").addObject();
        report.put("templateName", "血常规");
        report.put("reportDate", "2026-07-10");
        ObjectNode critical = report.putArray("metrics").addObject();
        critical.put("name", "血红蛋白");
        critical.put("value", "45");
        critical.put("unit", "g/L");
        critical.put("reference", "110-160");
        critical.put("critical", true);

        ObjectNode masked = service.maskWorkspace(workspace);
        String documentXml = unzipEntry(service.renderDocx(masked, workspace), "word/document.xml");

        assertEquals("CRITICAL", masked.path("labReports").path(0).path("metrics").path(0).path("severity").asText());
        assertTrue(documentXml.contains("血红蛋白"));
        assertTrue(documentXml.contains("45g/L"));
        assertTrue(documentXml.contains("【危急值·偏低】"));
    }

    @Test
    void masksCompoundSurnameAndShortContactValues() {
        assertEquals("欧阳xx", service.maskName("欧阳明"));
        assertEquals("已脱敏", service.maskPhone("12345"));
        assertEquals("已脱敏", service.coarseAddress("幸福路88号2单元"));
    }

    @Test
    void rendersWhenRawAddressIsAlreadyCoarse() {
        ObjectNode workspace = sampleWorkspace();
        ((ObjectNode) workspace.path("encounter").path("patient")).put("address", "河南省信阳市");

        ObjectNode masked = service.maskWorkspace(workspace);

        assertEquals("河南省信阳市", masked.path("patient").path("address").asText());
        assertTrue(service.renderDocx(masked, workspace).length > 1000);
    }

    @Test
    void rejectsMaskedWorkspaceThatStillContainsDetailedAddress() {
        ObjectNode workspace = sampleWorkspace();
        ObjectNode masked = service.maskWorkspace(workspace);
        ((ObjectNode) masked.path("patient")).put("address", "河南省固始县城关镇幸福路88号2单元");

        assertThrows(ResponseStatusException.class, () -> service.renderDocx(masked, workspace));
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
        reception.put("transfusionHistory", "否认输血史");
        reception.putArray("personalHistory").add("生长于原籍").add("无烟酒嗜好");
        reception.putArray("maritalHistory").add("适龄结婚").add("配偶及子女体健");
        reception.putArray("familyHistory").add("否认遗传病家族史").add("否认肿瘤家族史");
        addStage(stages, "RECEPTION", "COMPLETED", reception);
        ObjectNode inspection = objectMapper.createObjectNode();
        inspection.putArray("examinationTypes").add("VISUAL").add("DIGITAL");
        inspection.put("factualConclusion", "截石位见肛缘肿物");
        addStage(stages, "INSPECTION", "COMPLETED", inspection);
        ObjectNode tcm = objectMapper.createObjectNode();
        tcm.put("tcmDisease", "痔病");
        tcm.put("primarySyndrome", "湿热下注证");
        tcm.putArray("concurrentSyndrome").add("气虚");
        tcm.put("inspection", "面色尚可");
        tcm.put("inquiry", "便血色鲜");
        tcm.put("tongue", "舌红苔黄腻");
        tcm.put("pulse", "脉滑数");
        tcm.put("treatmentPrinciple", "清热利湿");
        addStage(stages, "TCM", "COMPLETED", tcm);
        ObjectNode doctor = objectMapper.createObjectNode();
        doctor.put("finalRoute", "INPATIENT");
        doctor.put("treatmentPath", "SURGICAL");
        doctor.put("primaryWesternDiagnosis", "混合痔");
        doctor.putArray("secondaryWesternDiagnoses").add("直肠炎");
        doctor.put("treatmentPlan", "拟择期手术治疗");
        addStage(stages, "DOCTOR", "COMPLETED", doctor);
        ObjectNode surgery = objectMapper.createObjectNode();
        surgery.put("actualOperationName", "混合痔外剥内扎术");
        surgery.put("operationDate", "2026-07-10");
        surgery.put("intraoperativeFindings", "术中见多发混合痔");
        surgery.put("procedurePerformed", "完成外剥内扎");
        surgery.put("postoperativeHandoff", "生命体征平稳，送回病房");
        addStage(stages, "SURGERY", "COMPLETED", surgery);

        ObjectNode ecgTask = workspace.putArray("auxiliaryTasks").addObject();
        ecgTask.put("taskType", "ECG");
        ecgTask.put("status", "COMPLETED");
        ecgTask.put("title", "心电图");
        ecgTask.putObject("data").put("result", "窦性心律");
        workspace.putArray("labReports");
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

    private boolean hasZipEntry(byte[] bytes, String entryName) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) return true;
            }
        }
        return false;
    }
}


