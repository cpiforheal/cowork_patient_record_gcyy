package com.coshare.patientrecord.inventory.service.workflow;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("mysql")
public class InventoryRequestWorkflow {

    private final InventoryRepository repository;

    public InventoryRequestWorkflow(InventoryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ObjectNode createRequest(JsonNode payload, SessionUser user) {
        ObjectNode row = repository.object(payload).deepCopy();
        repository.applyOperator(row, user);
        repository.applyUserDepartment(row, user);
        row.put("applicant", user.name());
        if (repository.text(row, "department").isBlank()) throw new IllegalArgumentException("申领科室不能为空");
        if (repository.text(row, "reason").isBlank()) throw new IllegalArgumentException("申请理由不能为空");
        row.put("id", "req-" + UUID.randomUUID());
        row.put("status", "pending");
        row.put("createdAt", repository.now());
        ArrayNode lines = repository.normalizeRequestLines(row, "pending");
        row.set("lines", lines);
        repository.applyRequestPrimaryFields(row, lines);
        repository.upsertRequest(row);
        repository.log(
            repository.text(row, "applicant", repository.text(row, "operator")),
            "发起申领",
            "request",
            repository.requestLineSummary(lines),
            repository.text(row, "department") + " 申领 " + lines.size() + " 项物资"
        );
        return repository.readDbForUser(user);
    }

    @Transactional
    public ObjectNode approveRequest(JsonNode payload, SessionUser user) {
        ObjectNode request = repository.loadRequest(repository.text(payload, "id"));
        repository.assertStatus(request, List.of("pending"), "只有待审核申领单可以审核");
        repository.updateLineStatuses(request, "approved");
        request.put("status", "approved");
        request.put("owner", user.name());
        request.put("approvedAt", repository.now());
        repository.upsertRequest(request);
        repository.log(user.name(), "负责人审核", "request", repository.requestLineSummary(repository.requestLines(request)), "申领单已审核通过");
        return repository.readDbForUser(user);
    }

    @Transactional
    public ObjectNode issueRequest(JsonNode payload, SessionUser user) {
        ObjectNode request = repository.loadRequest(repository.text(payload, "id"));
        repository.assertStatus(request, List.of("approved", "partially_issued"), "只有已审核或部分发放的申领单可以发放");
        ArrayNode lines = repository.requestLines(request);
        JsonNode issueLines = payload.path("lines");
        BigDecimal totalIssuedNow = BigDecimal.ZERO;
        String batchId = repository.text(payload, "batchId");
        for (JsonNode lineNode : lines) {
            ObjectNode line = repository.object(lineNode);
            BigDecimal remaining = repository.quantity(line, "quantity").subtract(repository.quantity(line, "issuedQuantity"));
            if (remaining.signum() <= 0) continue;
            BigDecimal requestedIssue = repository.requestedIssueQuantity(payload, issueLines, line, remaining);
            if (requestedIssue.signum() <= 0) continue;
            if (requestedIssue.compareTo(remaining) > 0) requestedIssue = remaining;
            BigDecimal issued = repository.issueLine(line, request, requestedIssue, batchId, user);
            totalIssuedNow = totalIssuedNow.add(issued);
        }
        if (totalIssuedNow.signum() <= 0) throw new IllegalArgumentException("当前没有可发放库存，请先入库或调整发放数量");

        repository.applyRequestStatusFromLines(request, lines);
        request.put("issuedAt", repository.now());
        request.put("issuer", user.name());
        repository.putQuantity(request, "issuedQuantity", repository.totalIssuedQuantity(lines));
        repository.upsertRequest(request);
        repository.log(
            user.name(),
            "仓库发放",
            "request",
            repository.requestLineSummary(lines),
            repository.text(request, "department") + " 本次发放 " + totalIssuedNow + "，状态：" + repository.text(request, "status")
        );
        return repository.readDbForUser(user);
    }

    @Transactional
    public ObjectNode receiveRequest(JsonNode payload, SessionUser user) {
        ObjectNode request = repository.loadRequest(repository.text(payload, "id"));
        repository.assertStatus(request, List.of("issued"), "只有已发放申领单可以签收");
        if (!repository.sameDepartment(user, request) && !repository.isInventoryManager(user)) {
            throw new IllegalArgumentException("只能签收本科室申领单");
        }
        repository.updateLineStatuses(request, "received");
        request.put("status", "received");
        request.put("receivedAt", repository.now());
        request.put("receiver", user.name());
        repository.upsertRequest(request);
        repository.log(user.name(), "领取确认", "request", repository.requestLineSummary(repository.requestLines(request)), "申领单已签收");
        return repository.readDbForUser(user);
    }

    @Transactional
    public ObjectNode rejectRequest(JsonNode payload, SessionUser user) {
        ObjectNode request = repository.loadRequest(repository.text(payload, "id"));
        repository.assertStatus(request, List.of("pending"), "只有待审核申领单可以驳回");
        String reason = repository.text(payload, "reason");
        if (reason.isBlank()) throw new IllegalArgumentException("驳回必须填写原因");
        repository.updateLineStatuses(request, "rejected");
        request.put("status", "rejected");
        request.put("rejectedAt", repository.now());
        request.put("rejector", user.name());
        request.put("rejectReason", reason);
        repository.upsertRequest(request);
        repository.log(user.name(), "驳回申领", "request", repository.requestLineSummary(repository.requestLines(request)), reason);
        return repository.readDbForUser(user);
    }

    @Transactional
    public ObjectNode cancelRequest(JsonNode payload, SessionUser user) {
        ObjectNode request = repository.loadRequest(repository.text(payload, "id"));
        repository.assertStatus(request, List.of("pending"), "只有待审核申领单可以撤销");
        if (!repository.sameDepartment(user, request) && !repository.isInventoryManager(user)) {
            throw new IllegalArgumentException("只能撤销本科室申领单");
        }
        String reason = repository.text(payload, "reason", "申请人撤销");
        repository.updateLineStatuses(request, "cancelled");
        request.put("status", "cancelled");
        request.put("cancelledAt", repository.now());
        request.put("cancelledBy", user.name());
        request.put("cancelReason", reason);
        repository.upsertRequest(request);
        repository.log(user.name(), "撤销申领", "request", repository.requestLineSummary(repository.requestLines(request)), reason);
        return repository.readDbForUser(user);
    }

    @Transactional
    public ObjectNode voidRequest(JsonNode payload, SessionUser user) {
        ObjectNode request = repository.loadRequest(repository.text(payload, "id"));
        repository.assertStatus(request, List.of("pending", "approved"), "只有待审核或待发放申领单可以作废");
        String reason = repository.text(payload, "reason");
        if (reason.isBlank()) throw new IllegalArgumentException("作废必须填写原因");
        repository.updateLineStatuses(request, "void");
        request.put("status", "void");
        request.put("voidedAt", repository.now());
        request.put("voidedBy", user.name());
        request.put("voidReason", reason);
        repository.upsertRequest(request);
        repository.log(user.name(), "作废申领", "request", repository.requestLineSummary(repository.requestLines(request)), reason);
        return repository.readDbForUser(user);
    }
}
