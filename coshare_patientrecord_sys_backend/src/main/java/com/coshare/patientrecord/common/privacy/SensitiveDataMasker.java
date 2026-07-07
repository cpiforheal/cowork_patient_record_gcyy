package com.coshare.patientrecord.common.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SensitiveDataMasker {

    private static final String POLICY_VERSION = "patient-ai-mask-v1";
    private static final Pattern MOBILE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{6}(?:19|20)\\d{2}\\d{7}[0-9Xx](?!\\d)");
    private static final Pattern LONG_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{15,18}(?!\\d)");
    private static final Pattern LABELED_NAME_PATTERN = Pattern.compile("(姓名|患者姓名|联系人)[：:\\s]*[\\u4e00-\\u9fa5A-Za-z·]{1,12}");
    private static final Pattern LABELED_ADDRESS_PATTERN = Pattern.compile("(地址|住址|现住址|家庭住址)[：:\\s]*[^，。；;\\n\\r]{3,80}");
    private static final List<String> SENSITIVE_KEYWORDS = List.of(
        "name",
        "姓名",
        "phone",
        "mobile",
        "telephone",
        "tel",
        "电话",
        "手机号",
        "idcard",
        "idno",
        "idnumber",
        "identity",
        "身份证",
        "cardno",
        "证件",
        "address",
        "addr",
        "住址",
        "地址",
        "contact",
        "联系人",
        "fileName",
        "filename",
        "文件名",
        "附件"
    );

    public String policyVersion() {
        return POLICY_VERSION;
    }

    public String maskPatientId(String patientId) {
        String value = safe(patientId);
        if (value.isBlank()) return "";
        if (value.length() <= 4) return "***";
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    public String maskFieldValue(String key, String value) {
        String text = maskText(value);
        if (safe(key).isBlank() || text.isBlank()) return text;
        if (!isSensitiveKey(key)) return text;
        if (isPhoneKey(key)) return maskPhone(text);
        if (isIdKey(key)) return maskId(text);
        if (isAttachmentKey(key)) return maskAttachmentName(text);
        if (isNameKey(key)) return maskName(text);
        if (isAddressKey(key)) return maskAddress(text);
        return text.length() <= 2 ? "*" : text.substring(0, 1) + "***";
    }

    public String maskText(String value) {
        String text = safe(value);
        if (text.isBlank()) return "";
        text = MOBILE_PATTERN.matcher(text).replaceAll(match -> {
            String raw = match.group();
            return raw.substring(0, 3) + "****" + raw.substring(7);
        });
        text = ID_CARD_PATTERN.matcher(text).replaceAll(match -> {
            String raw = match.group();
            return raw.substring(0, 6) + "********" + raw.substring(raw.length() - 4);
        });
        text = LONG_CARD_PATTERN.matcher(text).replaceAll(match -> {
            String raw = match.group();
            if (raw.length() <= 8) return raw;
            return raw.substring(0, 4) + "********" + raw.substring(raw.length() - 2);
        });
        text = LABELED_NAME_PATTERN.matcher(text).replaceAll(match -> match.group(1) + "：**");
        text = LABELED_ADDRESS_PATTERN.matcher(text).replaceAll(match -> match.group(1) + "：***");
        return text.replaceAll("(visitNo|就诊号|门诊号|住院号)[：:\\s]*[A-Za-z0-9_-]+", "$1：**");
    }

    public JsonNode maskJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return node;
        JsonNode copy = node.deepCopy();
        maskJsonInPlace(copy, "");
        return copy;
    }

    private void maskJsonInPlace(JsonNode node, String parentKey) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    object.put(entry.getKey(), maskFieldValue(entry.getKey(), value.asText("")));
                } else {
                    maskJsonInPlace(value, entry.getKey());
                }
            }
            return;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (JsonNode item : array) maskJsonInPlace(item, parentKey);
        }
    }

    private boolean isSensitiveKey(String key) {
        String normalized = normalizeKey(key);
        return SENSITIVE_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private boolean isPhoneKey(String key) {
        String normalized = normalizeKey(key);
        return normalized.contains("phone") || normalized.contains("mobile") || normalized.contains("tel") || normalized.contains("电话");
    }

    private boolean isIdKey(String key) {
        String normalized = normalizeKey(key);
        return normalized.contains("idcard") || normalized.contains("idno") || normalized.contains("idnumber") || normalized.contains("identity") || normalized.contains("身份证");
    }

    private boolean isNameKey(String key) {
        String normalized = normalizeKey(key);
        return normalized.contains("name") || normalized.contains("姓名");
    }

    private boolean isAddressKey(String key) {
        String normalized = normalizeKey(key);
        return normalized.contains("address") || normalized.contains("addr") || normalized.contains("地址") || normalized.contains("住址");
    }

    private boolean isAttachmentKey(String key) {
        String normalized = normalizeKey(key);
        return normalized.contains("filename") || normalized.contains("文件名") || normalized.contains("附件");
    }

    private String maskPhone(String value) {
        return MOBILE_PATTERN.matcher(value).replaceAll(match -> {
            String raw = match.group();
            return raw.substring(0, 3) + "****" + raw.substring(7);
        });
    }

    private String maskId(String value) {
        return ID_CARD_PATTERN.matcher(value).replaceAll(match -> {
            String raw = match.group();
            return raw.substring(0, 6) + "********" + raw.substring(raw.length() - 4);
        });
    }

    private String maskName(String value) {
        String text = safe(value);
        if (text.isBlank()) return "";
        if (text.length() == 1) return "*";
        return text.substring(0, 1) + "*".repeat(Math.min(text.length() - 1, 3));
    }

    private String maskAddress(String value) {
        String text = safe(value);
        if (text.isBlank()) return "";
        return text.length() <= 6 ? "***" : text.substring(0, Math.min(6, text.length())) + "***";
    }

    private String maskAttachmentName(String value) {
        String text = safe(value);
        if (text.isBlank()) return "";
        int dotIndex = text.lastIndexOf('.');
        String suffix = dotIndex >= 0 ? text.substring(dotIndex) : "";
        String stem = dotIndex >= 0 ? text.substring(0, dotIndex) : text;
        String maskedStem = maskText(stem);
        if (maskedStem.equals(stem) && stem.length() > 4) maskedStem = stem.substring(0, 2) + "***";
        return maskedStem + suffix;
    }

    private String normalizeKey(String key) {
        return safe(key).replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static String safe(Object value) {
        return String.valueOf(value == null ? "" : value).trim();
    }
}
