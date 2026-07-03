package com.coshare.patientrecord.medicalrecord.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.medicalrecord.dto.DownloadFile;
import com.coshare.patientrecord.medicalrecord.dto.FinalizeRequest;
import com.coshare.patientrecord.medicalrecord.dto.GenerateRequest;
import com.coshare.patientrecord.medicalrecord.dto.VoidRequest;
import com.coshare.patientrecord.medicalrecord.dto.WorkspaceSaveRequest;
import com.coshare.patientrecord.medicalrecord.model.TargetField;
import com.coshare.patientrecord.medicalrecord.repository.MedicalRecordSchemaInitializer;
import com.coshare.patientrecord.medicalrecord.repository.MedicalRecordVersionRepository;
import com.coshare.patientrecord.security.AuthPermission;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class ClinicMedicalRecordService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String TEMPLATE_NAME = "医生目标病历模板 v1";
    private static final String TEMPLATE_VERSION = "leader-template-20260623";
    private static final String TEMPLATE_RESOURCE = "medical-record-templates/target-medical-record-template.docx";
    private static final String TEMPLATE_SOURCE_NAME = "周xx病历模版.docx";
    private static final String GENERATOR_NAME = "docx-template";
    private static final String DISCLAIMER = "本目标病历由医生填写的动态字段按固定 docx 模板生成；模板固定文字、格式和页眉页脚不由系统改写。定稿后不可直接修改，只能作废或重新生成新版本。";
    private static final List<TargetField> TARGET_FIELDS = buildTargetFields();

    private final MedicalRecordSchemaInitializer schemaInitializer;
    private final MedicalRecordVersionRepository versionRepository;
    private final ObjectMapper objectMapper;
    private final MedicalRecordTemplateRenderer templateRenderer;
    private final MedicalRecordSourceBuilder sourceBuilder;
    private final Path generatedDir;

    public ClinicMedicalRecordService(
        MedicalRecordSchemaInitializer schemaInitializer,
        MedicalRecordVersionRepository versionRepository,
        ObjectMapper objectMapper,
        MedicalRecordTemplateRenderer templateRenderer,
        MedicalRecordSourceBuilder sourceBuilder,
        @Value("${clinic.generated-medical-record-dir:${clinic.attachment-dir}/../generated-medical-records}") String generatedDir
    ) {
        this.schemaInitializer = schemaInitializer;
        this.versionRepository = versionRepository;
        this.objectMapper = objectMapper;
        this.templateRenderer = templateRenderer;
        this.sourceBuilder = sourceBuilder;
        this.generatedDir = Path.of(generatedDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void initializeSchema() {
        schemaInitializer.initializeSchema();
    }

    public Map<String, Object> templateStatus(SessionUser user) {
        requireReadRole();
        ArrayNode unboundFields = templateRenderer.unboundTemplateFields(TEMPLATE_RESOURCE, TARGET_FIELDS);
        ObjectNode status = objectMapper.createObjectNode();
        status.put("name", TEMPLATE_NAME);
        status.put("templateVersion", TEMPLATE_VERSION);
        status.put("configured", templateRenderer.templateAvailable(TEMPLATE_RESOURCE) && unboundFields.isEmpty());
        status.put("promptConfigurable", false);
        status.put("templateSource", TEMPLATE_SOURCE_NAME);
        status.put("commandSource", "固定 docx 母版 + 医生动态字段填充；本轮不调用 AI");
        status.put("disclaimer", DISCLAIMER);
        status.put("generatedDir", generatedDir.toString());
        ArrayNode sections = status.putArray("sections");
        targetSectionNames().forEach(sections::add);
        ArrayNode required = status.putArray("requiredFields");
        TARGET_FIELDS.stream().filter(TargetField::required).forEach(field -> {
            ObjectNode row = required.addObject();
            row.put("key", field.key());
            row.put("label", field.label());
            row.put("section", field.section());
        });
        status.set("fieldMatrix", targetFieldMatrix());
        status.set("unboundFields", unboundFields);
        return objectMapper.convertValue(status, new TypeReference<Map<String, Object>>() {});
    }

    public Map<String, Object> precheck(GenerateRequest request, SessionUser user) {
        requireReadRole();
        String patientId = safe(request == null ? "" : request.patientId());
        if (patientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者ID");
        ObjectNode source = sourceBuilder.readPatientSource(patientId, user, false, TEMPLATE_NAME, TEMPLATE_VERSION);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode unboundFields = templateRenderer.unboundTemplateFields(TEMPLATE_RESOURCE, TARGET_FIELDS);
        result.set("missingItems", sourceBuilder.missingItems(source, TARGET_FIELDS));
        result.set("unboundFields", unboundFields);
        result.set("fieldMatrix", targetFieldMatrix());
        result.put("ready", result.path("missingItems").isArray() && result.path("missingItems").isEmpty() && unboundFields.isEmpty());
        result.put("disclaimer", DISCLAIMER);
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    @Transactional
    public Map<String, Object> saveWorkspace(WorkspaceSaveRequest request, SessionUser user) {
        requireGenerateRole();
        String patientId = safe(request == null ? "" : request.patientId());
        if (patientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者ID");
        sourceBuilder.assertCanReadPatient(patientId);
        JsonNode values = request == null || request.values() == null ? objectMapper.createObjectNode() : objectMapper.valueToTree(request.values());
        if (!values.isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标病历字段必须是对象");

        Set<String> allowedKeys = targetFieldKeys();
        ObjectNode accepted = objectMapper.createObjectNode();
        values.fields().forEachRemaining(entry -> {
            if (allowedKeys.contains(entry.getKey())) {
                accepted.put(entry.getKey(), safe(entry.getValue().asText("")));
            }
        });
        if (accepted.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有可保存的目标病历字段");
        }

        accepted.fields().forEachRemaining(entry -> versionRepository.upsertRecordField(patientId, entry.getKey(), entry.getValue().asText("")));
        versionRepository.writeAudit(patientId, user, "保存医生目标病历填写", "medical-record.workspace.save", "保存 " + accepted.size() + " 个目标病历动态字段");

        ObjectNode source = sourceBuilder.readPatientSource(patientId, user, false, TEMPLATE_NAME, TEMPLATE_VERSION);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("values", accepted);
        result.set("missingItems", sourceBuilder.missingItems(source, TARGET_FIELDS));
        result.put("disclaimer", DISCLAIMER);
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    public Map<String, Object> versions(String patientId, SessionUser user) {
        return versions(patientId, user, 0);
    }

    public Map<String, Object> versions(String patientId, SessionUser user, int limit) {
        requireReadRole();
        String safePatientId = safe(patientId);
        if (safePatientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者ID");
        sourceBuilder.assertCanReadPatient(safePatientId);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("versions", versionRepository.versionsNode(safePatientId, TEMPLATE_VERSION, GENERATOR_NAME, limit));
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    @Transactional
    public Map<String, Object> generate(GenerateRequest request, SessionUser user) {
        requireGenerateRole();
        String patientId = safe(request == null ? "" : request.patientId());
        if (patientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者ID");
        if (!templateRenderer.templateAvailable(TEMPLATE_RESOURCE)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "医生目标病历模板未配置，请检查后端模板文件");
        }
        ArrayNode unboundFields = templateRenderer.unboundTemplateFields(TEMPLATE_RESOURCE, TARGET_FIELDS);
        if (!unboundFields.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "目标病历模板仍有动态字段未绑定占位符：" + joinArray(unboundFields));
        }

        ObjectNode sourceSnapshot = sourceBuilder.readPatientSource(patientId, user, false, TEMPLATE_NAME, TEMPLATE_VERSION);
        ObjectNode logSnapshot = sourceBuilder.readPatientSource(patientId, user, true, TEMPLATE_NAME, TEMPLATE_VERSION);
        ArrayNode missingItems = sourceBuilder.missingItems(sourceSnapshot, TARGET_FIELDS);
        if (!missingItems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标病历动态字段未补齐：" + joinArray(missingItems));
        }
        JsonNode patient = sourceSnapshot.path("patient");
        Map<String, String> replacements = sourceBuilder.buildTemplateValues(sourceSnapshot, TARGET_FIELDS);

        int version = versionRepository.nextVersion(patientId);
        String id = "medrec-" + UUID.randomUUID();
        byte[] generatedBytes = templateRenderer.renderTemplate(TEMPLATE_RESOURCE, replacements);
        String fileName = sanitizeFileName(patient.path("name").asText("patient")) + "-医生目标病历-V" + version + ".docx";
        Path target = writeGeneratedFile(patientId, id, fileName, generatedBytes);
        String hash = sha256(generatedBytes);
        ObjectNode created = versionRepository.saveVersion(
            patientId,
            id,
            version,
            target,
            fileName,
            hash,
            logSnapshot,
            user,
            "draft",
            "",
            TEMPLATE_NAME,
            TEMPLATE_VERSION,
            GENERATOR_NAME
        );

        ObjectNode result = objectMapper.createObjectNode();
        result.set("record", created);
        result.set("missingItems", missingItems);
        result.put("disclaimer", DISCLAIMER);
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    @Transactional
    public Map<String, Object> finalizeRecord(FinalizeRequest request, SessionUser user) {
        requireFinalizeRole();
        String id = safe(request == null ? "" : request.id());
        if (id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少目标病历版本ID");
        ObjectNode row = loadRecordOrThrow(id);
        sourceBuilder.assertCanReadPatient(text(row, "patientId"));
        if (!"draft".equals(text(row, "status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿版本可以确认定稿");
        }
        row.put("status", "finalized");
        row.put("finalizedAt", now());
        versionRepository.upsertRaw(row);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("record", row);
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    @Transactional
    public Map<String, Object> voidRecord(VoidRequest request, SessionUser user) {
        requireFinalizeRole();
        String id = safe(request == null ? "" : request.id());
        if (id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少目标病历版本ID");
        ObjectNode row = loadRecordOrThrow(id);
        sourceBuilder.assertCanReadPatient(text(row, "patientId"));
        if ("voided".equals(text(row, "status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该版本已作废");
        }
        row.put("status", "voided");
        row.put("voidedAt", now());
        row.put("voidReason", safe(request == null ? "" : request.reason()));
        versionRepository.upsertRaw(row);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("record", row);
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    public DownloadFile download(String id, SessionUser user) {
        requireReadRole();
        ObjectNode row = loadRecordOrThrow(safe(id));
        sourceBuilder.assertCanReadPatient(text(row, "patientId"));
        String filePath = text(row, "filePath");
        if (filePath.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该目标病历暂无可下载文件");
        Path target = Path.of(filePath).toAbsolutePath().normalize();
        if (!target.startsWith(generatedDir) || !Files.isRegularFile(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标病历文件不存在，请重新生成");
        }
        return new DownloadFile(new FileSystemResource(target), text(row, "fileName", "医生目标病历.docx"));
    }

    private ObjectNode loadRecordOrThrow(String id) {
        if (safe(id).isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少目标病历版本ID");
        ObjectNode row = versionRepository.loadRecord(id);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标病历版本不存在");
        return row;
    }

    private Path writeGeneratedFile(String patientId, String id, String fileName, byte[] bytes) {
        try {
            Path dir = generatedDir.resolve(sanitizeFileName(patientId)).normalize();
            if (!dir.startsWith(generatedDir)) throw new IOException("invalid patient directory");
            Files.createDirectories(dir);
            Path target = dir.resolve(id + "-" + sanitizeFileName(fileName)).normalize();
            if (!target.startsWith(generatedDir)) throw new IOException("invalid target file");
            Files.write(target, bytes);
            return target;
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "目标病历文件写入失败：" + error.getMessage(), error);
        }
    }

    private ArrayNode targetFieldMatrix() {
        ArrayNode sections = objectMapper.createArrayNode();
        Map<String, ArrayNode> fieldsBySection = new LinkedHashMap<>();
        for (TargetField field : TARGET_FIELDS) {
            fieldsBySection.computeIfAbsent(field.section(), ignored -> objectMapper.createArrayNode()).add(targetFieldNode(field));
        }
        fieldsBySection.forEach((sectionName, fields) -> {
            ObjectNode section = sections.addObject();
            section.put("section", sectionName);
            section.set("fields", fields);
        });
        return sections;
    }

    private ObjectNode targetFieldNode(TargetField field) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("key", field.key());
        row.put("label", field.label());
        row.put("section", field.section());
        row.put("kind", field.kind());
        row.put("required", field.required());
        row.put("aiPolishable", field.aiPolishable());
        row.put("placeholder", field.placeholder());
        row.set("options", targetFieldOptions(field));
        ArrayNode sources = row.putArray("sources");
        field.sources().forEach(sources::add);
        return row;
    }

    private ArrayNode targetFieldOptions(TargetField field) {
        ArrayNode options = objectMapper.createArrayNode();
        switch (field.key()) {
            case "gender" -> addOptionValues(options, List.of("男", "女", "未说明"));
            case "maritalStatus" -> addOptionValues(options, List.of("未婚", "已婚", "离异", "丧偶", "未说明"));
            case "nation" -> addOptionValues(options, List.of("汉族", "回族", "蒙古族", "满族", "其他"));
            case "historyProvider" -> addOptionValues(options, List.of("患者本人", "患者家属", "陪同人员", "病历资料"));
            case "contactRelation" -> addOptionValues(options, List.of("配偶", "父母", "子女", "兄弟姐妹", "其他"));
            case "historyReliable" -> addOptionValues(options, List.of("是", "基本可靠", "需家属补充", "不确定"));
            case "anesthesiaMethod" -> addOptionValues(options, List.of("骶管内麻醉", "硬膜外麻醉", "静脉麻醉", "局部麻醉", "其他"));
            default -> {
            }
        }
        if ("textarea".equals(field.kind()) || field.aiPolishable()) {
            addOptionValues(options, field.anchors());
        }
        return options;
    }

    private void addOptionValues(ArrayNode options, List<String> values) {
        if (values == null) return;
        for (String value : values) {
            String normalized = safe(value);
            if (normalized.isBlank()) continue;
            boolean exists = false;
            for (JsonNode option : options) {
                if (normalized.equals(option.asText())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) options.add(normalized);
        }
    }

    private List<String> targetSectionNames() {
        List<String> names = new ArrayList<>();
        for (TargetField field : TARGET_FIELDS) {
            if (!names.contains(field.section())) names.add(field.section());
        }
        return names;
    }

    private Set<String> targetFieldKeys() {
        Set<String> keys = new HashSet<>();
        TARGET_FIELDS.forEach(field -> keys.add(field.key()));
        return keys;
    }

    private void requireReadRole() {
        AuthPermission.requireAnyRole("当前账号无权查看目标病历", "admin", "doctor", "quality", "nurse");
    }

    private void requireGenerateRole() {
        AuthPermission.requireAnyRole("当前账号无权生成目标病历", "admin", "doctor");
    }

    private void requireFinalizeRole() {
        AuthPermission.requireAnyRole("当前账号无权定稿目标病历", "admin", "doctor");
    }

    private String joinArray(ArrayNode array) {
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : array) {
            if (!builder.isEmpty()) builder.append("、");
            builder.append(item.asText());
        }
        return builder.isEmpty() ? "无明显缺失项" : builder.toString();
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            return "";
        }
    }

    private String text(JsonNode node, String key) {
        return node == null ? "" : node.path(key).asText("");
    }

    private String text(JsonNode node, String key, String fallback) {
        String value = text(node, key);
        return value.isBlank() ? fallback : value;
    }

    private String sanitizeFileName(String value) {
        String sanitized = safe(value).replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
        return sanitized.isBlank() ? "medical-record" : sanitized;
    }

    private static String safe(String value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }

    private static TargetField tf(
        String section,
        String key,
        String label,
        String kind,
        boolean required,
        boolean aiPolishable,
        String defaultValue,
        String placeholder,
        List<String> sources,
        List<String> anchors
    ) {
        return new TargetField(section, key, label, kind, required, aiPolishable, defaultValue, placeholder, sources, anchors);
    }

    private static List<TargetField> buildTargetFields() {
        return List.of(
            tf("病历首页", "patientName", "姓名", "input", true, false, "", "患者真实姓名", List.of("patient.name"), List.of("周xx")),
            tf("病历首页", "gender", "性别", "select", true, false, "", "男/女", List.of(), List.of()),
            tf("病历首页", "age", "年龄", "input", true, false, "", "例如：33", List.of("patientAge"), List.of("33岁")),
            tf("病历首页", "nativePlace", "籍贯", "input", true, false, "", "例如：河南固始", List.of(), List.of("河南固始")),
            tf("病历首页", "occupation", "职业", "input", true, false, "", "例如：农民", List.of(), List.of("农民")),
            tf("病历首页", "maritalStatus", "婚姻", "input", true, false, "", "例如：已婚", List.of(), List.of("已婚")),
            tf("病历首页", "nation", "民族", "input", true, false, "汉族", "例如：汉族", List.of(), List.of()),
            tf("病历首页", "admissionDate", "入院日期", "date", true, false, "", "YYYY-MM-DD", List.of("patient.visitDate"), List.of("2026-02-24")),
            tf("病历首页", "address", "家庭住址", "textarea", true, false, "", "家庭住址/现住址", List.of(), List.of("三角村")),
            tf("病历首页", "historyCollectedAt", "病史采集日期", "date", true, false, "", "YYYY-MM-DD", List.of("admissionDate", "patient.visitDate"), List.of()),
            tf("病历首页", "contactName", "联系人", "input", true, false, "", "联系人姓名", List.of("familyDecisionMaker"), List.of("杨xx")),
            tf("病历首页", "historyProvider", "病史陈述者", "input", true, false, "患者本人", "例如：患者本人", List.of(), List.of("患者本人")),
            tf("病历首页", "contactRelation", "与患者关系", "input", true, false, "", "例如：配偶", List.of("familyDecisionMaker"), List.of("配偶")),
            tf("病历首页", "contactPhone", "联系人电话", "input", true, false, "", "11位手机号", List.of("phone", "familyContact"), List.of("1xxxxxxxxx5")),
            tf("病历首页", "contactAddress", "联系人地址", "textarea", false, false, "同上", "联系人地址", List.of("address"), List.of("同上")),
            tf("病历首页", "historyReliable", "陈述内容是否可靠", "select", true, false, "是", "是/基本可靠/需补充", List.of(), List.of("是")),
            tf("病历首页", "solarTermOnset", "发病节气", "input", false, false, "", "例如：雨水", List.of(), List.of("雨水")),
            tf("病历首页", "allergyHistory", "过敏药物", "textarea", true, true, "", "过敏药物或否认过敏史", List.of(), List.of("无")),
            tf("主诉与现病史", "chiefComplaintText", "主诉", "textarea", true, true, "", "完整主诉", List.of(), List.of("间断便血伴便时肿物脱出1年，加重1月余")),
            tf("主诉与现病史", "presentIllnessText", "现病史", "textarea", true, true, "", "完整现病史段落", List.of("onset"), List.of("患者自诉3年前曾因便血、脱出于门珍行硬化剂注射治疗，术后恢复良好。1年前至今间断便血，滴下状，色鲜红，无痛，便后即止，数天后自行好转，饮酒或食用辛辣食物后症状明显，反复发作，便不尽感明显，时常久蹲排便，进行性加重，便时肛门肿物脱出休息后可自行回纳；近1月症状加重，肿物脱出后需手托回纳。今来院就诊，门诊检查后以“混合痔、直肠黏膜松弛”收入院。患者近期精神、饮食、小便可，入睡难，大便频数，2-3次/日，体重未见明显变化。")),
            tf("主诉与现病史", "admissionReason", "门诊收入院原因", "textarea", true, true, "", "门诊诊断及收入院原因", List.of(), List.of("门诊检查后以“混合痔、直肠黏膜松弛”收入院")),
            tf("主诉与现病史", "generalConditionText", "精神饮食睡眠大小便体重", "textarea", true, true, "", "近期一般情况", List.of("generalCondition"), List.of("患者近期精神、饮食、小便可，入睡难，大便频数，2-3次/日，体重未见明显变化")),
            tf("既往个人婚育家族史", "pastHistory", "既往史", "textarea", true, true, "", "既往史完整段落", List.of("operationHistory", "chronicDisease"), List.of("既往史：否认外伤及慢性病史，否认输血史，预防接种随社会进行，否认过敏史。")),
            tf("既往个人婚育家族史", "personalHistory", "个人史", "textarea", true, true, "", "个人生活史", List.of(), List.of("生长于原籍，否认长期外地居住史，否认特殊化学品及放射性接触史。否认饮酒、吸烟及其他不良生活习惯。否认冶游史。")),
            tf("既往个人婚育家族史", "marriageBirthHistory", "婚育史", "textarea", true, true, "", "婚育情况", List.of(), List.of("适龄结婚，配偶及子女均体健")),
            tf("既往个人婚育家族史", "familyHistory", "家族史", "textarea", true, true, "", "家族史", List.of(), List.of("家族中无传染病、代谢性、糖尿病、血友病、遗传性、肿瘤及类似病史。")),
            tf("中医四诊", "tcmFourDiagnosisText", "中医四诊观察", "textarea", true, true, "", "神志、精神、面色、形体、语声、气味等", List.of("tcmLook"), List.of("神志清，精神可，面色正常，形体微胖，体态自如，言语清晰，语声适中，未闻及异常气息及气味，口干、舌淡紫、无苔，舌中裂纹，脉弦数。")),
            tf("中医四诊", "tongue", "舌象", "input", true, false, "", "舌质、舌苔", List.of(), List.of("舌淡紫、无苔，舌中裂纹")),
            tf("中医四诊", "pulseCondition", "脉象", "input", true, false, "", "例如：脉弦数", List.of(), List.of("脉弦数")),
            tf("体格检查", "vitalSigns", "生命体征", "input", true, false, "", "T/P/R/BP", List.of(), List.of("36.3", "70", "17", "115", "75")),
            tf("体格检查", "generalExam", "一般状况检查", "textarea", true, true, "", "一般状况", List.of(), List.of("发育正常，体型微胖，步入病房，自主体位，言语流利，神志清楚，查体合作，步态正常。")),
            tf("体格检查", "skinMucosaExam", "皮肤和黏膜检查", "textarea", true, true, "", "皮肤黏膜", List.of(), List.of("全身皮肤及粘膜无发绀、黄染及苍白，无皮疹。未见皮下出血。毛发状况：正常。皮肤湿度正常，弹性正常，无水肿，无肝掌，未见蜘蛛痣。")),
            tf("体格检查", "lymphNodeExam", "浅表淋巴结检查", "textarea", true, true, "", "浅表淋巴结", List.of(), List.of("颈前、腋下及腹股沟淋巴结未触及肿大")),
            tf("体格检查", "headOrganExam", "头部及器官检查", "textarea", true, true, "", "头部及五官", List.of(), List.of("头颅正常无畸形，巩膜无黄染，双侧瞳孔等大等圆，直径约3mm，对光反射及调节均正常。双耳廓无畸形，双侧乳突区无压痛，鼻无畸形，鼻中隔居中，嗅觉正常，鼻窦无压痛，无鼻塞，无异常分泌物。口唇红润，无紫绀，口腔黏膜无溃疡及出血点，伸舌居中，齿龈无红肿溢脓，无义齿，双侧扁桃体无肿大，悬雍垂居中，咽部无充血，声音正常。")),
            tf("体格检查", "neckExam", "颈部检查", "textarea", true, true, "", "颈部", List.of(), List.of("颈软无抵抗，气管居中，颈静脉无怒张，肝颈静脉回流征阴性，颈动脉波动正常，甲状腺未触及肿大。")),
            tf("体格检查", "chestExam", "胸部检查", "textarea", true, true, "", "胸肺心", List.of(), List.of("胸廓对称无畸形，呼吸节律正常，胸痹无压痛，无胸骨叩击痛。双肺呼吸音清，未闻及干湿性啰音及胸膜摩擦音。心率70次/分，律齐，各瓣膜听诊区未闻及病理性杂音。")),
            tf("体格检查", "abdomenExam", "腹部检查", "textarea", true, true, "", "腹部", List.of(), List.of("腹部平坦，未见胃肠型及蠕动波，未见腹壁静脉曲张。腹软，无压痛、反跳痛及肌紧张，未触及腹部肿块。肝、脾肋下未触及。双肾区无叩击痛，肠鸣音正常。肠鸣音正常，无气过水声。")),
            tf("体格检查", "externalGenitaliaExam", "外生殖器检查", "textarea", false, true, "未查", "外生殖器检查", List.of(), List.of("未查")),
            tf("体格检查", "spineLimbsExam", "脊柱四肢检查", "textarea", true, true, "", "脊柱四肢", List.of(), List.of("脊柱正常生理弯曲，活动自如，无压痛及叩击痛。四肢活动自如，无畸形，关节无红肿，活动自如，皮温正常。无杵状指、趾，双下肢无明显水肿。")),
            tf("体格检查", "nervousSystemExam", "神经系统检查", "textarea", false, true, "未查", "神经系统检查", List.of(), List.of("未查。")),
            tf("专科及辅助检查", "specialExamFullText", "专科检查", "textarea", true, true, "", "肛门专科检查完整段落", List.of("lithotomyExam", "digitalExam", "anoscope"), List.of("肛门赘皮环状增生，截石位4-5、7、11-1点明显，屏气用腹压可见其缓慢增大，色青紫，质柔软，镜检示直肠黏膜松弛、层叠状，阻塞视野，4、7、11点内痔粘膜隆起糜烂，退镜时4、11点粘膜可脱出肛外。")),
            tf("专科及辅助检查", "auxiliaryExamSummary", "辅助检查结果", "textarea", true, true, "", "检查检验摘要", List.of("bloodRoutine", "ecgResult"), List.of("WBC:7.32X10^9/L    PLT:321X10^9/L    HGB:153g/L    GLU:5.42mmol/L", "胸部DR：心/肺/隔未见明显异常")),
            tf("诊断", "tcmDiagnosis", "中医诊断", "textarea", true, true, "", "中医诊断及证型", List.of(), List.of("痔病（肝胃不和  气阴两虚）")),
            tf("诊断", "westernDiagnosis", "西医诊断", "textarea", true, true, "", "西医诊断多条", List.of(), List.of("混合痔", "直肠粘膜松弛")),
            tf("首次病程记录", "firstCourseAdmissionTime", "首次病程入院时间", "input", true, false, "", "例如：17:45", List.of(), List.of("17:45")),
            tf("首次病程记录", "firstCourseCaseFeatures", "病例特点", "textarea", true, true, "", "病例特点", List.of("presentIllnessText"), List.of("病例特点：")),
            tf("首次病程记录", "tcmSyndromeBasis", "中医辨病辨证依据", "textarea", true, true, "", "中医辨病辨证依据", List.of(), List.of("中医辨病辨证依据：")),
            tf("首次病程记录", "westernDiagnosisBasis", "西医诊断依据", "textarea", true, true, "", "西医诊断依据", List.of(), List.of("西医诊断依据：")),
            tf("首次病程记录", "tcmDifferentialDiagnosis", "中医鉴别诊断", "textarea", true, true, "", "中医鉴别诊断", List.of(), List.of("中医鉴别诊断：")),
            tf("首次病程记录", "westernDifferentialDiagnosis", "西医鉴别诊断", "textarea", true, true, "", "西医鉴别诊断", List.of(), List.of("西医鉴别诊断：")),
            tf("首次病程记录", "diagnosisTreatmentPlan", "诊疗计划", "textarea", true, true, "", "首次病程诊疗计划", List.of("treatmentPlan"), List.of("诊疗计划：")),
            tf("查房记录", "attendingRoundsJson", "主治/副主任医师查房记录", "textarea", true, true, "", "可填写多条查房记录，含日期时间、医师、职称、意见", List.of(), List.of("主治医师查房记录", "副主任医师查房记录")),
            tf("查房记录", "surgeonPreOpRoundText", "手术医师查房记录", "textarea", true, true, "", "术前手术医师查房", List.of(), List.of("手术医师查房记录")),
            tf("术前小结", "preOpSummary", "术前摘要", "textarea", true, true, "", "术前病情摘要", List.of(), List.of("术前小结")),
            tf("术前小结", "preOpDiagnosis", "术前诊断", "textarea", true, true, "", "术前诊断", List.of("westernDiagnosis"), List.of("术前诊断：1、混合痔")),
            tf("术前小结", "operationIndication", "手术指征", "textarea", true, true, "", "手术指征", List.of(), List.of("手术指征：")),
            tf("术前小结", "operationName", "拟施手术方式", "textarea", true, true, "", "拟施手术方式", List.of("mainOperation"), List.of("一次性肛痔套扎吻合器内痔套扎治疗+痔切除术+直肠粘膜悬吊术+肛周药物注射封闭治疗")),
            tf("术前小结", "anesthesiaMethod", "麻醉方式", "input", true, false, "", "麻醉方式", List.of(), List.of("骶管内麻醉")),
            tf("术前小结", "operationNotes", "注意事项", "textarea", true, true, "", "术中注意事项", List.of(), List.of("注意事项：")),
            tf("术前小结", "preOpPreparation", "术前准备", "textarea", true, true, "", "术前准备", List.of(), List.of("术前准备：")),
            tf("术后首次病程", "surgeryDate", "手术时间", "date", true, false, "", "YYYY-MM-DD", List.of("operationDate"), List.of("2026-02-25")),
            tf("术后首次病程", "intraoperativeDiagnosis", "术中诊断", "textarea", true, true, "", "术中诊断", List.of("westernDiagnosis"), List.of("术中诊断：1、混合痔")),
            tf("术后首次病程", "operationBriefProcess", "手术简要经过", "textarea", true, true, "", "手术简要经过", List.of(), List.of("手术简要经过：")),
            tf("术后首次病程", "postOpTreatmentPlan", "术后诊疗计划", "textarea", true, true, "", "术后诊疗计划", List.of(), List.of("术后诊疗计划：")),
            tf("术后首次病程", "postOpFirstRecordAt", "术后首次记录日期时间", "input", true, false, "", "YYYY-MM-DD HH:mm", List.of(), List.of("记录日期时间：2026-02-25 17:45")),
            tf("术后连续查房", "postOpRoundsJson", "术后连续查房记录", "textarea", true, true, "", "可填写多条术后查房/随访病程", List.of(), List.of("术后第一天", "术后第二天", "术后第三天")),
            tf("出院记录", "dischargeAdmissionSituation", "入院情况", "textarea", true, true, "", "出院记录入院情况", List.of("presentIllnessText"), List.of("入院情况：")),
            tf("出院记录", "dischargeTreatmentResult", "诊治经过及结果", "textarea", true, true, "", "含手术日期名称及结果", List.of(), List.of("诊治经过及结果（含手术日期名称及结果）：")),
            tf("出院记录", "dischargeDiagnosis", "出院诊断", "textarea", true, true, "", "出院诊断", List.of("westernDiagnosis", "tcmDiagnosis"), List.of("出院诊断：")),
            tf("出院记录", "dischargeCondition", "出院情况", "textarea", true, true, "", "出院情况及治疗效果", List.of(), List.of("出院情况（含治疗效果）：")),
            tf("出院记录", "dischargeAdvice", "出院医嘱", "textarea", true, true, "", "饮食、排便、生活、用药、护理、复诊", List.of(), List.of("出院医嘱：")),
            tf("出院记录", "tcmCareAdvice", "中医调护", "textarea", true, true, "", "中医调护", List.of(), List.of("中医调护：")),
            tf("签名与日期", "doctorSignature", "医师签名", "input", false, false, "", "医生签名，不自动伪造", List.of("attendingDoctor", "patient.doctor"), List.of("医师签名：")),
            tf("签名与日期", "seniorDoctorSignature", "副主任医师签名", "input", false, false, "", "副主任医师签名", List.of(), List.of("副主任医师签名："))
        );
    }
}
