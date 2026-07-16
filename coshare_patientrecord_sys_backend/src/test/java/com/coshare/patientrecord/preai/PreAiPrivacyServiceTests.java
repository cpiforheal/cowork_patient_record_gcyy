package com.coshare.patientrecord.preai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
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
        assertTrue(documentXml.contains("<w:tbl>"));
        for (String section : new String[] {
            "一、基础及住院信息", "二、主诉", "三、现病史", "四、既往史、个人史、婚育史及家族史",
            "五、中医四诊", "六、专科检查", "八、中西医主诊断及合并症",
            "十一、治疗及手术/操作信息", "十二、DIP分组提示（非阻断）"
        }) {
            assertTrue(documentXml.contains(section), section);
        }
        assertTrue(documentXml.contains("便血3月"));
        assertTrue(documentXml.contains("周xx"));
        assertTrue(documentXml.contains("视诊、指诊"));
        assertTrue(documentXml.contains("窦性心律"));
        assertFalse(documentXml.contains("VISUAL"));
        assertFalse(documentXml.contains("DIGITAL"));
        assertFalse(documentXml.contains("INPATIENT"));
        assertFalse(documentXml.contains("SURGICAL"));
        assertFalse(documentXml.contains("周明华"));
        assertFalse(documentXml.contains("13812345678"));
        assertFalse(documentXml.contains("411525199001011234"));
        assertFalse(documentXml.contains("幸福路88号"));
        assertFalse(documentXml.contains("原始照片.jpg"));
        assertFalse(documentXml.contains("张医生"));
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
        ObjectNode negative = report.withArray("metrics").addObject();
        negative.put("name", "艾滋病抗体");
        negative.put("value", "阴性");
        negative.put("reference", "阴性");
        ObjectNode empty = report.withArray("metrics").addObject();
        empty.put("name", "未填写指标");
        empty.put("value", "");
        empty.put("reference", "1-2");

        ObjectNode masked = service.maskWorkspace(workspace);
        String documentXml = unzipEntry(service.renderDocx(masked, workspace), "word/document.xml");

        assertEquals("偏高", masked.path("labReports").path(0).path("metrics").path(0).path("abnormal").asText());
        assertEquals("ABNORMAL", masked.path("labReports").path(0).path("metrics").path(0).path("severity").asText());
        assertTrue(documentXml.contains("白细胞：12.510^9/L【异常·偏高】"));
        assertTrue(documentXml.contains("艾滋病抗体：阴性"));
        assertFalse(documentXml.contains("艾滋病抗体：阴性【异常"));
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
        assertTrue(documentXml.contains("血红蛋白：45g/L【危急值·偏低】"));
    }

    @Test
    void masksCompoundSurnameAndShortContactValues() {
        assertEquals("欧阳xx", service.maskName("欧阳明"));
        assertEquals("已脱敏", service.maskPhone("12345"));
        assertEquals("已脱敏", service.coarseAddress("幸福路88号2单元"));
    }

    @Test
    void rendersStructuredFactsWithoutRawJsonAndKeepsDiagnosisOperationAndBloodTypeMappings() throws Exception {
        ObjectNode workspace = sampleWorkspace();
        ObjectNode reception = stageData(workspace, "RECEPTION");
        ObjectNode chronic = reception.putArray("chronicDiseaseItems").addObject();
        chronic.put("disease", "高血压");
        chronic.put("duration", "5年");
        chronic.put("control", "控制良好");

        ObjectNode tcm = stageData(workspace, "TCM");
        ObjectNode comorbid = tcm.putArray("comorbidTcmItems").addObject();
        comorbid.put("westernComorbidity", "高血压");
        comorbid.put("includedInTcm", true);
        comorbid.put("tcmDisease", "眩晕");
        comorbid.put("syndrome", "肝阳上亢证");

        ObjectNode doctor = stageData(workspace, "DOCTOR");
        ObjectNode local = doctor.putArray("secondaryDiagnosisItems").addObject();
        local.put("name", "肛乳头肥大");
        local.put("category", "LOCAL");
        ObjectNode systemic = doctor.withArray("secondaryDiagnosisItems").addObject();
        systemic.put("name", "高血压");
        systemic.put("category", "COMORBIDITY");
        doctor.put("plannedPrimaryOperation", "混合痔外剥内扎术");
        doctor.putArray("plannedSecondaryOperations").add("肛乳头切除术");

        ObjectNode surgery = stageData(workspace, "SURGERY");
        surgery.put("actualPrimaryOperation", "混合痔外剥内扎术");
        surgery.putArray("actualSecondaryOperations").add("肛乳头切除术");

        ObjectNode vitalTask = workspace.withArray("auxiliaryTasks").addObject();
        vitalTask.put("taskType", "VITAL_SIGNS");
        vitalTask.put("status", "COMPLETED");
        ObjectNode vitalData = vitalTask.putObject("data");
        vitalData.put("measuredAt", "2026-07-10 08:40:00");
        ObjectNode systolic = vitalData.putObject("systolicBp");
        systolic.put("value", "120");
        systolic.put("unit", "mmHg");
        systolic.put("status", "正常");

        ObjectNode colonoscopyTask = workspace.withArray("auxiliaryTasks").addObject();
        colonoscopyTask.put("taskType", "COLONOSCOPY");
        colonoscopyTask.put("status", "COMPLETED");
        ObjectNode colonoscopyData = colonoscopyTask.putObject("data");
        colonoscopyData.put("status", "COMPLETED");
        colonoscopyData.put("scope", "全结肠");
        colonoscopyData.put("resectionPerformed", "已切除");

        ObjectNode labTask = workspace.withArray("auxiliaryTasks").addObject();
        labTask.put("taskType", "LAB");
        labTask.put("status", "COMPLETED");
        ObjectNode report = workspace.withArray("labReports").addObject();
        report.put("templateName", "血型检查");
        report.put("reportDate", "2026-07-10");
        ObjectNode abo = report.putArray("metrics").addObject();
        abo.put("name", "ABO血型");
        abo.put("value", "A型");
        ObjectNode rh = report.withArray("metrics").addObject();
        rh.put("name", "血型及Rh(D)");
        rh.put("shortName", "RhD");
        rh.put("value", "阳性");

        ObjectNode masked = service.maskWorkspace(workspace);
        String documentXml = unzipEntry(service.renderDocx(masked, workspace), "word/document.xml");

        assertTableValue(documentXml, "血型", "A型");
        assertTableValue(documentXml, "Rh血型", "阳性");
        assertTrue(documentXml.contains("收缩压：120mmHg"));
        assertTableValue(documentXml, "慢性病史明细", "疾病：高血压；病程：5年；控制情况：控制良好");
        assertTableValue(documentXml, "局部次诊断", "肛乳头肥大");
        assertTableValue(documentXml, "全身合并症", "高血压");
        assertTrue(documentXml.contains("高血压 → 眩晕（肝阳上亢证）"));
        assertTableValue(documentXml, "拟行主术式", "混合痔外剥内扎术");
        assertTableValue(documentXml, "实际主术式", "混合痔外剥内扎术");
        assertTableValue(documentXml, "实际次术式/附加操作", "肛乳头切除术");
        assertTableValue(documentXml, "提示性质", "仅供复核，不阻断事实包生成，最终以正式编码及DIP规则为准");
        assertFalse(documentXml.contains("{&quot;value&quot;"));
    }

    private void assertTableValue(String documentXml, String label, String value) {
        assertTrue(documentXml.contains(">" + label + "<"), label);
        assertTrue(documentXml.contains(">" + value + "<"), value);
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

    private ObjectNode stageData(ObjectNode workspace, String code) {
        for (JsonNode stage : workspace.path("stages")) {
            if (code.equals(stage.path("stageCode").asText())) return (ObjectNode) stage.path("data");
        }
        return objectMapper.createObjectNode();
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


