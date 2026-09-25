package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 护理随访留痕监控台：管理员面板「患者就诊」子菜单下的独立 dashboard。
 * 以时间戳为主视觉，承担操作留痕检索、节点监控与纸质资料导出。
 */
@RestController
@Profile("mysql")
public class NursingFollowUpMonitorController {

    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final NursingFollowUpMonitorService service;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public NursingFollowUpMonitorController(NursingFollowUpMonitorService service,
                                            ObjectMapper objectMapper,
                                            JdbcTemplate jdbcTemplate) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/clinic-api/nursing/follow-up-monitor")
    public ApiResult<Map<String, Object>> query(
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String operator,
        @RequestParam(required = false) String action,
        @RequestParam(required = false, defaultValue = "1") int pageNum,
        @RequestParam(required = false, defaultValue = "50") int pageSize
    ) {
        SessionUser user = currentUser();
        Map<String, Object> result = service.query(safe(from), safe(to), safe(operator), safe(action),
            pageNum, pageSize, user);
        return ApiResult.success(objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {}));
    }

    @GetMapping("/clinic-api/nursing/follow-up-monitor/export.xlsx")
    public ResponseEntity<byte[]> exportXlsx(
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String operator,
        @RequestParam(required = false) String action
    ) {
        SessionUser user = currentUser();
        NursingFollowUpMonitorService.ExportBundle bundle =
            service.exportXlsx(safe(from), safe(to), safe(operator), safe(action), user);
        String fileName = "护理随访留痕-" + bundle.from() + "至" + bundle.to() + ".xlsx";
        auditExport(user, "XLSX", bundle, operator, action);
        return download(bundle.bytes(), fileName, XLSX_MIME);
    }

    @GetMapping("/clinic-api/nursing/follow-up-monitor/export.docx")
    public ResponseEntity<byte[]> exportDocx(
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String operator,
        @RequestParam(required = false) String action
    ) {
        SessionUser user = currentUser();
        NursingFollowUpMonitorService.ExportBundle bundle =
            service.exportDocx(safe(from), safe(to), safe(operator), safe(action), user);
        String fileName = "护理随访留痕-" + bundle.from() + "至" + bundle.to() + ".docx";
        auditExport(user, "DOCX", bundle, operator, action);
        return download(bundle.bytes(), fileName, DOCX_MIME);
    }

    /** 导出本身也留痕：纸质资料是谁在什么时候导出的，同样需要可追溯。 */
    private void auditExport(SessionUser user, String format,
                             NursingFollowUpMonitorService.ExportBundle bundle,
                             String operator, String action) {
        try {
            String detail = "导出护理随访留痕 " + format + "，区间 " + bundle.from() + "至" + bundle.to()
                + "，共 " + bundle.rows() + " 条"
                + (operator == null || operator.isBlank() ? "" : "，操作人=" + operator)
                + (action == null || action.isBlank() ? "" : "，动作=" + action);
            jdbcTemplate.update(
                "INSERT INTO pre_ai_audit_logs (id, encounter_id, action, stage_code, operator, operator_role, detail, created_at) "
                    + "VALUES (?, '', 'followup.monitor.export', 'FOLLOWUP', ?, ?, ?, ?)",
                "audit-" + UUID.randomUUID(), user.name(), user.role(),
                detail.length() > 900 ? detail.substring(0, 900) : detail,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
        } catch (Exception error) {
            // 导出已成功，审计失败不应让用户拿不到文件，但必须留日志
            org.slf4j.LoggerFactory.getLogger(NursingFollowUpMonitorController.class)
                .warn("护理随访留痕导出审计写入失败，format={}", format, error);
        }
    }

    private ResponseEntity<byte[]> download(byte[] body, String fileName, String mime) {
        String encoded = new String(fileName.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mime))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(encoded).build().toString())
            .body(body);
    }

    private SessionUser currentUser() {
        return AuthPermission.currentUserOrThrow();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
