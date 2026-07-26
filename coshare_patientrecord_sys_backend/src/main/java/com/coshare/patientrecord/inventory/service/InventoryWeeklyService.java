package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository;
import com.coshare.patientrecord.inventory.repository.InventoryWeeklyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class InventoryWeeklyService {

    private static final String DEFAULT_POLICY = "EXPECTED_PLUS_SAFETY_MINUS_AVAILABLE";

    private final InventoryWeeklyRepository weekly;
    private final InventoryLedgerRepository ledger;

    public InventoryWeeklyService(InventoryWeeklyRepository weekly, InventoryLedgerRepository ledger) {
        this.weekly = weekly;
        this.ledger = ledger;
    }

    public ObjectNode listStandards() {
        ObjectNode result = weekly.mapper().createObjectNode();
        result.set("list", weekly.standards());
        return result;
    }

    public ObjectNode getStandard(String id) {
        return weekly.standard(required(id, "标准版本ID不能为空"));
    }

    @Transactional
    public ObjectNode saveStandard(JsonNode payload, SessionUser user) {
        String id = text(payload, "id");
        String code = text(payload, "standardCode", "STD-WEEKLY").trim();
        String name = required(text(payload, "name"), "标准名称不能为空");
        String effectiveWeek = validateWeek(required(text(payload, "effectiveWeek"), "生效周不能为空"));
        String expiresWeek = text(payload, "expiresWeek");
        if (!expiresWeek.isBlank()) expiresWeek = validateWeek(expiresWeek);
        if (!expiresWeek.isBlank() && expiresWeek.compareTo(effectiveWeek) < 0) {
            throw new IllegalArgumentException("失效周不能早于生效周");
        }
        String timezone = text(payload, "hospitalTimezone", "Asia/Shanghai");
        ArrayNode lines = payload == null ? null : payload.withArray("lines");
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("标准清单至少需要一行物资");
        ObjectNode policy = weekly.mapper().createObjectNode();
        policy.put("calculationVersion", "weekly-v1");
        policy.put("defaultCalculationPolicy", text(payload, "calculationPolicy", DEFAULT_POLICY));
        policy.put("source", "ledger+standard+patientVolume");
        policy.put("careTypes", joinDistinct(lines, "careType"));
        policy.put("strictValidation", true);

        if (id.isBlank()) {
            id = "weekly-standard-" + UUID.randomUUID();
            weekly.insertStandard(id, code, weekly.nextVersion(code), name, effectiveWeek, expiresWeek, timezone, policy, user.name(), user.role());
            weekly.audit(null, null, null, "STANDARD_CREATED", user.name(), user.role(), null, auditDetail("standardId", id));
        } else {
            weekly.updateStandard(id, name, effectiveWeek, expiresWeek, timezone, policy);
            weekly.audit(null, null, null, "STANDARD_UPDATED", user.name(), user.role(), null, auditDetail("standardId", id));
        }
        insertValidatedLines(id, lines, text(payload, "calculationPolicy", DEFAULT_POLICY));
        return weekly.standard(id);
    }

    @Transactional
    public ObjectNode publishStandard(JsonNode payload, SessionUser user) {
        String id = required(text(payload, "id"), "标准版本ID不能为空");
        ObjectNode standard = weekly.standard(id);
        if (standard.withArray("lines").isEmpty()) throw new IllegalStateException("没有清单行的标准不能发布");
        weekly.publish(id, user.name(), user.role());
        weekly.audit(null, null, null, "STANDARD_PUBLISHED", user.name(), user.role(), null, auditDetail("standardId", id));
        return weekly.standard(id);
    }

    @Transactional
    public ObjectNode deleteStandard(JsonNode payload, SessionUser user) {
        String id = required(text(payload, "id"), "标准版本ID不能为空");
        weekly.deleteDraft(id);
        weekly.audit(null, null, null, "STANDARD_DELETED", user.name(), user.role(), null, auditDetail("standardId", id));
        ObjectNode result = weekly.mapper().createObjectNode();
        result.put("deleted", id);
        return result;
    }

    public ObjectNode listSnapshots(SessionUser user, String requestedDepartmentId, String weekNo) {
        String scopedDepartment = canCrossDepartment(user) ? normalizeDepartment(requestedDepartmentId, user) : scopedDepartmentId(user, requestedDepartmentId);
        ObjectNode result = weekly.mapper().createObjectNode();
        result.set("list", weekly.snapshots(weekNo == null || weekNo.isBlank() ? null : validateWeek(weekNo), scopedDepartment));
        return result;
    }

    public ObjectNode getSnapshot(String id, SessionUser user) {
        ObjectNode snapshot = weekly.snapshot(required(id, "快照ID不能为空"));
        assertSnapshotReadable(snapshot, user);
        snapshot.set("auditEvents", weekly.auditEvents(id));
        return snapshot;
    }

    public void assertSnapshotReadable(ObjectNode snapshot, SessionUser user) {
        if (canCrossDepartment(user)) return;
        String allowedDepartmentId = scopedDepartmentId(user, "");
        if (!allowedDepartmentId.equals(snapshot.path("departmentId").asText())) {
            throw new org.springframework.security.access.AccessDeniedException("无权访问其他科室的周度库存快照");
        }
    }

    @Transactional
    public ObjectNode generateSnapshot(JsonNode payload, SessionUser user) {
        String weekNo = validateWeek(required(text(payload, "weekNo"), "周次不能为空"));
        String departmentId = scopedDepartmentId(user, text(payload, "departmentId"));
        String idempotencyKey = idempotencyKey(payload, "GENERATE", weekNo, departmentId);
        String requestHash = sha256(canonical(payload));
        ObjectNode existing = weekly.command(idempotencyKey);
        if (existing != null) return completedCommand(existing, requestHash);
        String commandId = "weekly-command-" + UUID.randomUUID();
        weekly.beginCommand(commandId, idempotencyKey, "GENERATE", weekNo, departmentId, null, requestHash, payload, user.name(), user.role());
        ObjectNode snapshot = createSnapshot(weekNo, departmentId, payload, user, commandId, null, null, null);
        weekly.completeCommand(commandId, snapshot.path("id").asText(), snapshot);
        weekly.audit(snapshot.path("id").asText(), commandId, null, "SNAPSHOT_GENERATED", user.name(), user.role(), departmentId, auditDetail("weekNo", weekNo));
        return snapshot;
    }

    @Transactional
    public ObjectNode confirmSnapshot(JsonNode payload, SessionUser user) {
        String snapshotId = required(text(payload, "id"), "快照ID不能为空");
        ObjectNode snapshot = weekly.snapshot(snapshotId);
        assertSnapshotReadable(snapshot, user);
        String weekNo = snapshot.path("weekNo").asText();
        String departmentId = snapshot.path("departmentId").asText();
        String idempotencyKey = idempotencyKey(payload, "CONFIRM", weekNo, departmentId);
        String requestHash = sha256(canonical(payload));
        ObjectNode existing = weekly.command(idempotencyKey);
        if (existing != null) return completedCommand(existing, requestHash);
        Integer expectedRevision = payload.hasNonNull("expectedRevision") ? payload.path("expectedRevision").asInt() : null;
        if (expectedRevision != null && expectedRevision != snapshot.path("revision").asInt()) {
            throw new IllegalStateException("快照版本已变化，请刷新后重试");
        }
        String commandId = "weekly-command-" + UUID.randomUUID();
        weekly.beginCommand(commandId, idempotencyKey, "CONFIRM", weekNo, departmentId, expectedRevision, requestHash, payload, user.name(), user.role());
        weekly.confirmSnapshot(snapshotId, text(payload, "confirmationNote"), user.name(), user.role());
        ObjectNode confirmed = weekly.snapshot(snapshotId);
        weekly.completeCommand(commandId, snapshotId, confirmed);
        weekly.audit(snapshotId, commandId, null, "SNAPSHOT_CONFIRMED", user.name(), user.role(), departmentId, auditDetail("revision", String.valueOf(confirmed.path("revision").asInt())));
        return confirmed;
    }

    @Transactional
    public ObjectNode reviseSnapshot(JsonNode payload, SessionUser user) {
        String previousId = required(text(payload, "id"), "原快照ID不能为空");
        ObjectNode previous = weekly.snapshot(previousId);
        assertSnapshotReadable(previous, user);
        String weekNo = previous.path("weekNo").asText();
        String departmentId = previous.path("departmentId").asText();
        String reason = required(text(payload, "revisionReason"), "更正原因不能为空");
        int currentRevision = previous.path("revision").asInt();
        Integer expectedRevision = payload.hasNonNull("expectedRevision") ? payload.path("expectedRevision").asInt() : null;
        if (!"CONFIRMED".equals(previous.path("status").asText())) {
            throw new IllegalStateException("仅已确认的周度快照可以创建更正版本");
        }
        if (expectedRevision == null || expectedRevision != currentRevision || weekly.latestRevision(weekNo, departmentId) != currentRevision) {
            throw new IllegalStateException("快照版本已变化，请刷新后重试");
        }
        String idempotencyKey = idempotencyKey(payload, "REVISE", weekNo, departmentId);
        String requestHash = sha256(canonical(payload));
        ObjectNode existing = weekly.command(idempotencyKey);
        if (existing != null) return completedCommand(existing, requestHash);
        Map<String, Adjustment> overrides = adjustmentOverrides(payload.withArray("lines"));
        String commandId = "weekly-command-" + UUID.randomUUID();
        weekly.beginCommand(commandId, idempotencyKey, "REVISE", weekNo, departmentId, expectedRevision, requestHash, payload, user.name(), user.role());
        ObjectNode revised = createRevisionFromSnapshot(
            previous, previousId, user, commandId, reason, overrides
        );
        weekly.completeCommand(commandId, revised.path("id").asText(), revised);
        weekly.audit(revised.path("id").asText(), commandId, null, "SNAPSHOT_REVISED", user.name(), user.role(), departmentId, auditDetail("previousSnapshotId", previousId));
        return revised;
    }

    private ObjectNode createSnapshot(
        String weekNo,
        String departmentId,
        JsonNode payload,
        SessionUser user,
        String commandId,
        String previousId,
        String rootId,
        RevisionContext revisionContext
    ) {
        ObjectNode standard = weekly.activeStandard(weekNo, departmentId);
        LocalDate start = weekStart(weekNo);
        LocalDate end = start.plusDays(6);
        LocalDateTime weekExclusiveEnd = end.plusDays(1).atStartOfDay();
        LocalDateTime cutoff = LocalDateTime.now().isBefore(weekExclusiveEnd) ? LocalDateTime.now() : weekExclusiveEnd;
        ArrayNode linesJson = weekly.mapper().createArrayNode();
        List<InventoryWeeklyRepository.SnapshotLine> snapshotLines = new ArrayList<>();
        BigDecimal totalExpected = BigDecimal.ZERO;
        BigDecimal totalActual = BigDecimal.ZERO;
        BigDecimal totalAdjusted = BigDecimal.ZERO;
        for (InventoryWeeklyRepository.StandardLine standardLine : weekly.standardLines(standard.path("id").asText(), departmentId)) {
            if (!standardLine.enabled()) throw new IllegalStateException("标准包含已停用物资：" + standardLine.itemName());
            JsonNode linePolicy = standardLine.linePolicy();
            String rawCareType = standardLine.careType() == null || standardLine.careType().isBlank()
                ? linePolicy.path("careType").asText("outpatient")
                : standardLine.careType();
            String careType = normalizeCareType(rawCareType);
            InventoryWeeklyRepository.FlowSummary flow = weekly.flow(departmentId, standardLine.itemId(), careType, start, end, cutoff);
            BigDecimal conversionFactor = decimal(linePolicy.path("conversionFactor"), BigDecimal.ONE);
            BigDecimal baseExpected = decimal(linePolicy.path("standardQuantity"), standardLine.expected());
            int plannedVolume = linePolicy.path("plannedPatientVolume").asInt(linePolicy.path("businessVolume").asInt(0));
            int actualPatientVolume = flow.actualBusinessVolume();
            int calculationVolume = actualPatientVolume > 0 ? actualPatientVolume : Math.max(plannedVolume, 0);
            BigDecimal expected = scale(baseExpected.multiply(BigDecimal.valueOf(calculationVolume)).multiply(conversionFactor));
            BigDecimal safety = decimal(linePolicy.path("safetyStockQuantity"), standardLine.safety());
            BigDecimal suggested = suggestedQuantity(standardLine.calculationPolicy(), expected, safety, flow.available(), flow.actualConsumed());
            Adjustment override = revisionContext == null ? null : revisionContext.overrides().get(lineKey(standardLine.itemId(), careType));
            BigDecimal adjusted = override == null ? suggested : scale(override.adjustedQuantity());
            String adjustmentReason = override == null ? text(payload, "adjustmentReason") : override.reason();
            BigDecimal expectedActualVariance = scale(expected.subtract(flow.actualConsumed()));
            BigDecimal adjustmentVariance = scale(adjusted.subtract(suggested));
            ObjectNode source = weekly.mapper().createObjectNode();
            source.put("calculationVersion", "weekly-v1");
            source.put("weekStart", start.toString());
            source.put("weekEnd", end.toString());
            source.put("sourceCutoffAt", cutoff.toString());
            source.put("careType", careType);
            source.put("patientVolumeSource", actualPatientVolume > 0 ? "pre_ai_encounters" : "weekly_standard_lines");
            source.put("actualBusinessVolume", actualPatientVolume);
            source.put("actualPatientVolume", actualPatientVolume);
            source.put("plannedPatientVolume", plannedVolume);
            source.put("businessVolume", calculationVolume);
            source.put("consumptionEventVolume", flow.consumptionEventVolume());
            source.put("standardQuantity", baseExpected);
            source.put("perPatientStandardQuantity", baseExpected);
            source.put("conversionFactor", conversionFactor);
            source.put("standardUnit", linePolicy.path("standardUnit").asText(standardLine.itemUnit()));
            source.put("baseUnit", linePolicy.path("baseUnit").asText(standardLine.itemUnit()));
            source.put("movementCount", flow.movementCount());
            source.put("source", "pre_ai_encounters + inventory_ledger_movements + inventory_consumption_events + inventory_weekly_standard_lines");
            if (actualPatientVolume == 0 && flow.actualConsumed().signum() > 0) {
                source.put("varianceFlag", "CONSUMED_WITHOUT_REGISTERED_PATIENT_VOLUME");
            }
            source.set("linePolicy", linePolicy);
            InventoryWeeklyRepository.SnapshotLine snapshotLine = new InventoryWeeklyRepository.SnapshotLine(
                "weekly-line-" + UUID.randomUUID(), standardLine.id(), careType, standardLine.itemId(), standardLine.itemName(), standardLine.itemUnit(),
                flow, expected, expectedActualVariance, safety, suggested, adjusted, adjustmentVariance, adjustmentReason, source
            );
            snapshotLines.add(snapshotLine);
            totalExpected = totalExpected.add(expected);
            totalActual = totalActual.add(flow.actualConsumed());
            totalAdjusted = totalAdjusted.add(adjusted);
            ObjectNode digestLine = linesJson.addObject();
            digestLine.put("itemId", standardLine.itemId());
            digestLine.put("careType", careType);
            digestLine.put("expected", expected);
            digestLine.put("actual", flow.actualConsumed());
            digestLine.put("adjusted", adjusted);
            digestLine.put("available", flow.available());
            digestLine.put("actualPatientVolume", actualPatientVolume);
            digestLine.put("plannedPatientVolume", plannedVolume);
        }
        if (snapshotLines.isEmpty()) throw new IllegalStateException("当前标准没有该科室的有效清单行");
        String snapshotId = "weekly-snapshot-" + UUID.randomUUID();
        int revision = weekly.nextRevision(weekNo, departmentId);
        String departmentName = ledger.departmentName(departmentId);
        String digest = sha256(linesJson.toString());
        weekly.insertSnapshot(snapshotId, weekNo, departmentId, departmentName, standard.path("id").asText(),
            standard.path("version").asInt(), revision, previousId, rootId, "DRAFT", cutoff,
            standard.path("hospitalTimezone").asText("Asia/Shanghai"), digest, snapshotLines.size(),
            scale(totalExpected), scale(totalActual), scale(totalAdjusted),
            revisionContext == null ? text(payload, "revisionReason") : revisionContext.reason(), user.name(), user.role());
        for (InventoryWeeklyRepository.SnapshotLine line : snapshotLines) weekly.insertSnapshotLine(snapshotId, line);
        ObjectNode snapshot = weekly.snapshot(snapshotId);
        snapshot.put("commandId", commandId);
        return snapshot;
    }

    private ObjectNode createRevisionFromSnapshot(
        ObjectNode previous,
        String previousId,
        SessionUser user,
        String commandId,
        String reason,
        Map<String, Adjustment> overrides
    ) {
        ArrayNode previousLines = previous.withArray("lines");
        ArrayNode digestLines = weekly.mapper().createArrayNode();
        BigDecimal totalAdjusted = BigDecimal.ZERO;
        for (JsonNode line : previousLines) {
            String itemId = line.path("itemId").asText();
            String careType = normalizeCareType(line.path("careType").asText("outpatient"));
            BigDecimal suggested = decimal(line.path("suggestedQuantity"), BigDecimal.ZERO);
            String lineKey = lineKey(itemId, careType);
            BigDecimal adjusted = overrides.containsKey(lineKey)
                ? scale(overrides.get(lineKey).adjustedQuantity())
                : decimal(line.path("adjustedQuantity"), suggested);
            ObjectNode digestLine = digestLines.addObject();
            digestLine.put("itemId", itemId);
            digestLine.put("careType", careType);
            digestLine.put("expected", decimal(line.path("expectedQuantity"), BigDecimal.ZERO));
            digestLine.put("actual", decimal(line.path("consumedQuantity"), BigDecimal.ZERO).subtract(decimal(line.path("reversalQuantity"), BigDecimal.ZERO)));
            digestLine.put("adjusted", adjusted);
            digestLine.put("available", decimal(line.path("availableQuantity"), BigDecimal.ZERO));
            totalAdjusted = totalAdjusted.add(adjusted);
        }
        if (previousLines.isEmpty()) throw new IllegalStateException("原快照没有明细行");
        String snapshotId = "weekly-snapshot-" + UUID.randomUUID();
        String rootId = previous.path("rootSnapshotId").asText("");
        weekly.insertSnapshot(snapshotId, previous.path("weekNo").asText(), previous.path("departmentId").asText(),
            previous.path("departmentName").asText(), previous.path("standardId").asText(), previous.path("standardVersion").asInt(),
            previous.path("revision").asInt() + 1, previousId, rootId.isBlank() ? previousId : rootId, "DRAFT",
            LocalDateTime.parse(previous.path("sourceCutoffAt").asText()), previous.path("hospitalTimezone").asText("Asia/Shanghai"),
            sha256(digestLines.toString()), previousLines.size(), decimal(previous.path("totalExpectedQuantity"), BigDecimal.ZERO),
            decimal(previous.path("totalActualConsumedQuantity"), BigDecimal.ZERO), scale(totalAdjusted), reason, user.name(), user.role());
        weekly.copySnapshotLines(previousId, snapshotId);
        for (Map.Entry<String, Adjustment> entry : overrides.entrySet()) {
            String[] key = splitLineKey(entry.getKey());
            weekly.updateSnapshotLineAdjustment(snapshotId, key[0], key[1], scale(entry.getValue().adjustedQuantity()), entry.getValue().reason());
        }
        ObjectNode revised = weekly.snapshot(snapshotId);
        revised.put("commandId", commandId);
        return revised;
    }

    private void insertValidatedLines(String standardId, ArrayNode lines, String defaultCalculationPolicy) {
        Map<String, Boolean> uniqueScope = new HashMap<>();
        for (JsonNode line : lines) {
            String departmentId = required(text(line, "departmentId"), "标准行科室ID不能为空");
            String itemId = required(text(line, "itemId"), "标准行物资ID不能为空");
            String careType = normalizeCareType(text(line, "careType", "outpatient"));
            String key = departmentId + "|" + itemId + "|" + careType;
            if (uniqueScope.put(key, true) != null) throw new IllegalArgumentException("同一标准版本中科室+物资+门诊/住院类型不能重复");
            String departmentName = ledger.departmentName(departmentId);
            ObjectNode item = item(itemId);
            String itemName = item.path("name").asText(itemId);
            String itemUnit = item.path("unit").asText("");
            BigDecimal standardQuantity = decimal(line.path("standardQuantity"), decimal(line.path("expectedQuantity"), BigDecimal.ZERO));
            BigDecimal conversionFactor = decimal(line.path("conversionFactor"), BigDecimal.ONE);
            BigDecimal businessVolume = decimal(line.path("plannedPatientVolume"), decimal(line.path("businessVolume"), BigDecimal.ONE));
            BigDecimal safety = decimal(line.path("safetyStockQuantity"), decimal(line.path("safetyQuantity"), BigDecimal.ZERO));
            if (standardQuantity.signum() < 0 || conversionFactor.signum() <= 0 || businessVolume.signum() < 0 || safety.signum() < 0) {
                throw new IllegalArgumentException("每患者标准量、计划患者数、安全库存和换算系数必须为非负数，换算系数需大于0");
            }
            ObjectNode policy = weekly.mapper().createObjectNode();
            policy.put("careType", careType);
            policy.put("businessVolume", businessVolume.intValue());
            policy.put("plannedPatientVolume", businessVolume.intValue());
            policy.put("standardQuantity", standardQuantity);
            policy.put("perPatientStandardQuantity", standardQuantity);
            policy.put("standardUnit", text(line, "standardUnit", itemUnit));
            policy.put("conversionFactor", conversionFactor);
            policy.put("baseUnit", text(line, "baseUnit", itemUnit));
            policy.put("safetyStockQuantity", safety);
            policy.put("strictValidation", true);
            String calculationPolicy = text(line, "calculationPolicy", defaultCalculationPolicy);
            weekly.insertStandardLine("weekly-standard-line-" + UUID.randomUUID(), standardId, departmentId, departmentName,
                itemId, itemName, itemUnit, careType, standardQuantity, safety, calculationPolicy, policy);
        }
    }

    private ObjectNode item(String itemId) {
        ArrayNode rows = ledger.queryJson("SELECT id, name, unit, enabled FROM inventory_items WHERE id = ?", itemId);
        if (rows.isEmpty()) throw new IllegalArgumentException("标准行物资不存在：" + itemId);
        ObjectNode item = (ObjectNode) rows.get(0);
        if (!item.path("enabled").asBoolean(true)) throw new IllegalArgumentException("标准行物资已停用：" + item.path("name").asText(itemId));
        return item;
    }

    private ObjectNode completedCommand(ObjectNode command, String requestHash) {
        if (!requestHash.equals(command.path("requestHash").asText())) {
            throw new IllegalStateException("幂等键已被不同请求使用");
        }
        if (!"COMPLETED".equals(command.path("status").asText())) {
            throw new IllegalStateException("相同幂等命令正在处理中，请稍后重试");
        }
        JsonNode response = command.path("responseJson");
        if (response.isObject()) return (ObjectNode) response;
        String snapshotId = command.path("snapshotId").asText("");
        return snapshotId.isBlank() ? command : weekly.snapshot(snapshotId);
    }

    private Map<String, Adjustment> adjustmentOverrides(ArrayNode lines) {
        Map<String, Adjustment> overrides = new HashMap<>();
        for (JsonNode line : lines) {
            String itemId = required(text(line, "itemId"), "更正行物资ID不能为空");
            BigDecimal adjusted = decimal(line.path("adjustedQuantity"), BigDecimal.ZERO);
            if (adjusted.signum() < 0) throw new IllegalArgumentException("更正调整量不能为负数");
            String careType = normalizeCareType(text(line, "careType", "outpatient"));
            overrides.put(lineKey(itemId, careType), new Adjustment(adjusted, text(line, "adjustmentReason")));
        }
        return overrides;
    }

    private static String lineKey(String itemId, String careType) {
        return itemId + "|" + careType;
    }

    private static String[] splitLineKey(String key) {
        int index = key.lastIndexOf('|');
        if (index < 0) return new String[] { key, "outpatient" };
        return new String[] { key.substring(0, index), key.substring(index + 1) };
    }

    private BigDecimal suggestedQuantity(String policy, BigDecimal expected, BigDecimal safety, BigDecimal available, BigDecimal actual) {
        String normalized = policy == null ? DEFAULT_POLICY : policy.toUpperCase(Locale.ROOT);
        BigDecimal value = switch (normalized) {
            case "ACTUAL_PLUS_SAFETY_MINUS_AVAILABLE" -> actual.add(safety).subtract(available);
            case "EXPECTED_MINUS_AVAILABLE" -> expected.subtract(available);
            default -> expected.add(safety).subtract(available);
        };
        return value.signum() < 0 ? scale(BigDecimal.ZERO) : scale(value);
    }

    private String scopedDepartmentId(SessionUser user, String requestedDepartmentId) {
        if (canCrossDepartment(user) && requestedDepartmentId != null && !requestedDepartmentId.isBlank()) {
            return ledger.resolveDepartmentId(requestedDepartmentId, requestedDepartmentId);
        }
        if (user.activeDepartmentId() != null && !user.activeDepartmentId().isBlank()) {
            return ledger.resolveDepartmentId(user.activeDepartmentId(), user.department());
        }
        return ledger.resolveDepartmentId("", user.department());
    }

    private String normalizeDepartment(String requestedDepartmentId, SessionUser user) {
        if (requestedDepartmentId == null || requestedDepartmentId.isBlank()) return null;
        return scopedDepartmentId(user, requestedDepartmentId);
    }

    private boolean canCrossDepartment(SessionUser user) {
        return List.of("admin", "quality", "manager").contains(user.role());
    }

    private static String normalizeCareType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("outpatient", "门诊").contains(normalized)) return "outpatient";
        if (List.of("inpatient", "住院").contains(normalized)) return "inpatient";
        throw new IllegalArgumentException("暂仅支持门诊/住院 care type");
    }

    private static String validateWeek(String value) {
        if (value == null || !value.matches("\\d{4}-W\\d{2}")) throw new IllegalArgumentException("周次格式需为 YYYY-Www");
        int week = Integer.parseInt(value.substring(6));
        if (week < 1 || week > 53) throw new IllegalArgumentException("周次范围不正确");
        return value;
    }

    private static LocalDate weekStart(String weekNo) {
        int year = Integer.parseInt(weekNo.substring(0, 4));
        int week = Integer.parseInt(weekNo.substring(6));
        return LocalDate.of(year, 1, 4)
            .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static BigDecimal decimal(JsonNode node, BigDecimal fallback) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()) return scale(fallback);
        try { return scale(node.decimalValue()); }
        catch (Exception ignored) { return scale(fallback); }
    }

    private static BigDecimal scale(BigDecimal value) {
        return InventoryLedgerRepository.scale(value);
    }

    private static String text(JsonNode node, String field) {
        return text(node, field, "");
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.path(field).isNull()) return fallback;
        String value = node.path(field).asText(fallback);
        return value == null ? fallback : value.trim();
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static String idempotencyKey(JsonNode payload, String action, String weekNo, String departmentId) {
        String explicit = text(payload, "idempotencyKey");
        return explicit.isBlank() ? action + ":" + weekNo + ":" + departmentId + ":" + sha256(canonical(payload)).substring(0, 16) : explicit;
    }

    private static String canonical(JsonNode payload) {
        return payload == null ? "{}" : payload.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 计算失败", error);
        }
    }

    private ObjectNode auditDetail(String key, String value) {
        ObjectNode detail = weekly.mapper().createObjectNode();
        detail.put(key, value);
        return detail;
    }

    private static String joinDistinct(ArrayNode rows, String field) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (JsonNode row : rows) {
            String value = text(row, field);
            if (!value.isBlank()) values.add(value);
        }
        return String.join(",", values);
    }

    private record RevisionContext(String reason, Map<String, Adjustment> overrides) {}
    public record Adjustment(BigDecimal adjustedQuantity, String reason) {}
}
