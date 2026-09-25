package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 护理随访留痕监控台：以时间戳为主视觉的操作留痕检索 + 节点状态监控 + 纸质导出。
 *
 * 数据边界（重要）：
 *  - 操作留痕来自 pre_ai_audit_logs 的 followup.* 动作，服务端已入库，可追溯、可导出。
 *  - 通话内容（接通状态/患者反馈/下次跟进）当前仅存于前端 localStorage，不在服务端，
 *    因此本监控台的"记录留存"只覆盖操作留痕与节点状态，通话内容待后续落库后接入。
 */
@Service
@Profile("mysql")
public class NursingFollowUpMonitorService {

    private static final Logger log = LoggerFactory.getLogger(NursingFollowUpMonitorService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DATE_ONLY = "yyyy-MM-dd";
    private static final int MAX_EXPORT_ROWS = 10000;
    private static final Set<String> VIEW_ROLES =
        Set.of("admin", "doctor", "nurse", "nursing", "inspection", "quality");

    /** 随访动作 → 中文标签。未登记的动作回落为原动作码，保证不丢数据。 */
    private static final Map<String, String> ACTION_LABELS = Map.ofEntries(
        Map.entry("followup.create", "创建复诊记录"),
        Map.entry("followup.update", "编辑复诊记录"),
        Map.entry("followup.image.upload", "上传复诊图片"),
        Map.entry("followup.image.remove", "删除复诊图片"),
        Map.entry("followup.recall.contact", "标记已联系"),
        Map.entry("followup.recall.arrived", "确认已回院"),
        Map.entry("followup.recall.arrived.undo", "撤销回院确认"),
        Map.entry("followup.monitor.export", "导出留痕资料")
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public NursingFollowUpMonitorService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- query

    public Map<String, Object> query(String from, String to, String operator, String action,
                                     int pageNum, int pageSize, SessionUser user) {
        requireViewRole(user);
        LocalDate today = LocalDate.now();
        LocalDate start = parseDate(from, today.minusDays(6));
        LocalDate end = parseDate(to, today);
        if (end.isBefore(start)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 200);
        String fromTime = start.format(DateTimeFormatter.ofPattern(DATE_ONLY)) + " 00:00:00";
        String toTime = end.format(DateTimeFormatter.ofPattern(DATE_ONLY)) + " 23:59:59";

        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(
            "FROM pre_ai_audit_logs WHERE action LIKE 'followup.%' AND action <> 'followup.recall.query' ");
        where.append("AND created_at BETWEEN ? AND ? ");
        args.add(fromTime);
        args.add(toTime);
        if (operator != null && !operator.isBlank()) {
            where.append("AND operator = ? ");
            args.add(operator.trim());
        }
        if (action != null && !action.isBlank()) {
            where.append("AND action = ? ");
            args.add(action.trim());
        }

        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + where, Integer.class, args.toArray());
        int totalRows = total == null ? 0 : total;

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((safePage - 1) * safeSize);
        List<ObjectNode> timeline = new ArrayList<>();
        jdbcTemplate.query(
            "SELECT id, action, encounter_id, operator, operator_role, detail, created_at " + where
                + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            (RowCallbackHandler) resultSet -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("id", resultSet.getString("id"));
                row.put("action", resultSet.getString("action"));
                row.put("actionLabel", label(resultSet.getString("action")));
                row.put("encounterId", resultSet.getString("encounter_id"));
                String operatorName = resultSet.getString("operator");
                row.put("operator", operatorName == null || operatorName.isBlank() ? "系统" : operatorName);
                row.put("operatorRole", safe(resultSet.getString("operator_role")));
                row.put("detail", safe(resultSet.getString("detail")));
                row.put("createdAt", safe(resultSet.getString("created_at")));
                row.put("dateKey", createdDate(resultSet.getString("created_at")));
                timeline.add(row);
            },
            pageArgs.toArray()
        );

        return Map.of(
            "from", start.toString(),
            "to", end.toString(),
            "total", totalRows,
            "pageNum", safePage,
            "pageSize", safeSize,
            "summary", summary(start, end, today),
            "nodes", nodes(today),
            "operators", operators(),
            "timeline", withPatientNames(timeline)
        );
    }

    /** 时间轴补齐患者姓名（页面与导出共用同一实现，避免两处口径不一致）。 */
    private List<ObjectNode> withPatientNames(List<ObjectNode> timeline) {
        if (timeline.isEmpty()) return timeline;
        // 路径一：有 encounterId 的记录（随访创建/编辑）
        Set<String> encounterIds = new LinkedHashSet<>();
        Set<String> caseIds = new LinkedHashSet<>();
        for (ObjectNode item : timeline) {
            String encounterId = item.path("encounterId").asText("");
            if (!encounterId.isBlank()) encounterIds.add(encounterId);
            String caseId = item.path("patientCaseId").asText("");
            if (!caseId.isBlank()) caseIds.add(caseId);
        }
        // 路径二：召回类动作的审计只带 recall:<visitId>，需先转成病历号
        Map<String, String> visitToCase = resolveVisitToCase(timeline);
        caseIds.addAll(visitToCase.values().stream().filter(v -> v != null && !v.isBlank()).toList());

        Map<String, String> names = resolvePatientNames(encounterIds, caseIds);
        for (ObjectNode item : timeline) {
            String name = names.getOrDefault(item.path("encounterId").asText(""), "");
            if (name.isBlank()) {
                String visitId = visitIdOf(item.path("detail").asText(""));
                name = names.getOrDefault(visitToCase.getOrDefault(visitId, ""), "");
            }
            item.put("patientName", name.isBlank() ? "未知患者" : name);
        }
        return timeline;
    }

    /** 从审计详情中提取 recall:<visitId>。 */
    private String visitIdOf(String detail) {
        if (detail == null) return "";
        int index = detail.indexOf("recall:");
        if (index < 0) return "";
        String rest = detail.substring(index + "recall:".length()).trim();
        int end = 0;
        while (end < rest.length() && rest.charAt(end) != ' ' && rest.charAt(end) != '（') end++;
        return rest.substring(0, end);
    }

    /** visitId → patientCaseId 批量映射，供无 encounterId 的召回类留痕回溯患者。 */
    private Map<String, String> resolveVisitToCase(List<ObjectNode> timeline) {
        Set<String> visitIds = new LinkedHashSet<>();
        for (ObjectNode item : timeline) {
            String visitId = visitIdOf(item.path("detail").asText(""));
            if (!visitId.isBlank()) visitIds.add(visitId);
        }
        Map<String, String> result = new LinkedHashMap<>();
        if (visitIds.isEmpty()) return result;
        String placeholders = String.join(",", java.util.Collections.nCopies(visitIds.size(), "?"));
        jdbcTemplate.query(
            "SELECT id, patient_case_id FROM pre_ai_follow_up_visits WHERE id IN (" + placeholders + ")",
            (RowCallbackHandler) resultSet -> {
                String caseId = resultSet.getString("patient_case_id");
                result.put(resultSet.getString("id"), caseId == null ? "" : caseId);
            },
            visitIds.toArray()
        );
        return result;
    }

    /** 由 encounterId 批量解析患者姓名并在需要时补查病历号；encounterId 优先，空则回落病历号。 */
    private Map<String, String> resolvePatientNames(Set<String> encounterIds, Set<String> caseIds) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!encounterIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(encounterIds.size(), "?"));
            jdbcTemplate.query(
                "SELECT e.id AS encounter_id, c.id AS case_id, c.patient_json FROM pre_ai_encounters e "
                    + "LEFT JOIN pre_ai_patient_cases c ON c.id = e.patient_case_id "
                    + "WHERE e.id IN (" + placeholders + ")",
                (RowCallbackHandler) resultSet -> {
                    result.put(resultSet.getString("encounter_id"), readName(resultSet.getString("patient_json")));
                    String caseId = resultSet.getString("case_id");
                    if (caseId != null && !caseId.isBlank()) {
                        result.putIfAbsent(caseId, readName(resultSet.getString("patient_json")));
                    }
                },
                encounterIds.toArray()
            );
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String caseId : caseIds) {
            if (caseId != null && !caseId.isBlank() && result.getOrDefault(caseId, "").isBlank()) missing.add(caseId);
        }
        if (!missing.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(missing.size(), "?"));
            jdbcTemplate.query(
                "SELECT id, patient_json FROM pre_ai_patient_cases WHERE id IN (" + placeholders + ")",
                (RowCallbackHandler) resultSet ->
                    result.put(resultSet.getString("id"), readName(resultSet.getString("patient_json"))),
                missing.toArray()
            );
        }
        return result;
    }

    /** 从患者档案 JSON 中读姓名；解析失败只记日志，不影响其它行。 */
    private String readName(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return "";
        try {
            JsonNode patient = objectMapper.readTree(rawJson);
            String name = patient.path("name").asText(patient.path("patientName").asText(""));
            return name.isBlank() ? "" : name;
        } catch (Exception error) {
            log.warn("解析患者档案姓名失败", error);
            return "";
        }
    }

    /** 今日速览：只做紧凑计数条，不与时间轴抢视觉焦点。 */
    public Map<String, Object> summary(LocalDate start, LocalDate end, LocalDate today) {
        String fromTime = start.format(DateTimeFormatter.ofPattern(DATE_ONLY)) + " 00:00:00";
        String toTime = end.format(DateTimeFormatter.ofPattern(DATE_ONLY)) + " 23:59:59";
        Integer ops = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pre_ai_audit_logs WHERE action LIKE 'followup.%' "
                + "AND action <> 'followup.recall.query' AND created_at BETWEEN ? AND ?",
            Integer.class, fromTime, toTime);
        String todayStart = today.format(DateTimeFormatter.ofPattern(DATE_ONLY)) + " 00:00:00";
        String todayEnd = today.format(DateTimeFormatter.ofPattern(DATE_ONLY)) + " 23:59:59";
        Integer todayOps = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pre_ai_audit_logs WHERE action LIKE 'followup.%' "
                + "AND action <> 'followup.recall.query' AND created_at BETWEEN ? AND ?",
            Integer.class, todayStart, todayEnd);
        Integer todayReached = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pre_ai_audit_logs WHERE action = 'followup.recall.contact' "
                + "AND created_at BETWEEN ? AND ?",
            Integer.class, todayStart, todayEnd);
        Integer todayArrived = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pre_ai_follow_up_visits WHERE arrived_at = ?",
            Integer.class, today.toString());
        Integer overduePending = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pre_ai_follow_up_visits WHERE next_review_date IS NOT NULL "
                + "AND next_review_date <> '' AND next_review_date < ? "
                + "AND (arrived_at IS NULL OR arrived_at = '')",
            Integer.class, today.toString());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rangeOps", ops == null ? 0 : ops);
        result.put("todayOps", todayOps == null ? 0 : todayOps);
        result.put("todayReached", todayReached == null ? 0 : todayReached);
        result.put("todayArrived", todayArrived == null ? 0 : todayArrived);
        result.put("overduePending", overduePending == null ? 0 : overduePending);
        return result;
    }

    /** 节点状态监控：每患者取最近一次复诊安排，判定触达/回院/逾期。 */
    public List<Map<String, Object>> nodes(LocalDate today) {
        Map<String, Object[]> latestByCase = new LinkedHashMap<>();
        jdbcTemplate.query(
            "SELECT id, patient_case_id, seq, next_review_date, arrived_at, arrived_by, updated_by, updated_at "
                + "FROM pre_ai_follow_up_visits WHERE next_review_date IS NOT NULL AND next_review_date <> '' "
                + "ORDER BY patient_case_id, seq ASC",
            (RowCallbackHandler) resultSet -> latestByCase.put(resultSet.getString("patient_case_id"), new Object[] {
                resultSet.getString("id"), resultSet.getString("patient_case_id"), resultSet.getInt("seq"),
                safeDate(resultSet.getString("next_review_date")),
                safeDate(resultSet.getString("arrived_at")),
                safe(resultSet.getString("arrived_by")),
                safe(resultSet.getString("updated_by")),
                safe(resultSet.getString("updated_at"))
            })
        );
        Map<String, String> names = resolveNamesByCase(latestByCase.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] latest : latestByCase.values()) {
            String visitId = String.valueOf(latest[0]);
            String caseId = String.valueOf(latest[1]);
            String dueDate = String.valueOf(latest[3]);
            String arrivedAt = String.valueOf(latest[4]);
            String lastContact = lastContact(visitId);
            boolean reached = !lastContact.isBlank();
            boolean arrived = !arrivedAt.isBlank();
            long overdueDays = arrived ? 0
                : Math.max(0, ChronoUnit.DAYS.between(LocalDate.parse(dueDate), today));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("patientCaseId", caseId);
            row.put("patientName", names.getOrDefault(caseId, "未知患者"));
            row.put("seq", latest[2]);
            row.put("dueDate", dueDate);
            row.put("reached", reached);
            row.put("lastContactAt", lastContact);
            row.put("arrived", arrived);
            row.put("arrivedAt", arrivedAt);
            row.put("overdueDays", overdueDays);
            row.put("lastOperator", arrived && !String.valueOf(latest[5]).isBlank()
                ? String.valueOf(latest[5])
                : (String.valueOf(latest[6]).isBlank() ? "未记录" : String.valueOf(latest[6])));
            row.put("lastActionAt", String.valueOf(latest[7]));
            rows.add(row);
        }
        rows.sort((a, b) -> Long.compare((Long) b.get("overdueDays"), (Long) a.get("overdueDays")));
        return rows;
    }

    /** 操作人下拉选项：仅取随访相关动作的操作人去重。 */
    public List<String> operators() {
        List<String> result = new ArrayList<>();
        jdbcTemplate.query(
            "SELECT DISTINCT operator FROM pre_ai_audit_logs WHERE action LIKE 'followup.%' "
                + "AND operator IS NOT NULL AND operator <> '' ORDER BY operator ASC",
            (RowCallbackHandler) resultSet -> result.add(resultSet.getString("operator"))
        );
        return result;
    }

    // --------------------------------------------------------------- export

    /** XLSX：操作留痕 + 节点状态 两个页签，供护理部二次统计。 */
    public ExportBundle exportXlsx(String from, String to, String operator, String action, SessionUser user) {
        List<ObjectNode> timeline = exportRows(from, to, operator, action, user);
        List<Map<String, Object>> nodes = nodes(LocalDate.now());
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            Sheet logSheet = workbook.createSheet("操作留痕");
            writeRow(logSheet, 0, header, "时间", "动作", "患者", "操作人", "角色", "详情");
            int r = 1;
            for (ObjectNode item : timeline) {
                writeRow(logSheet, r++, null,
                    item.path("createdAt").asText(""),
                    item.path("actionLabel").asText(""),
                    item.path("patientName").asText("未知患者"),
                    item.path("operator").asText(""),
                    item.path("operatorRole").asText(""),
                    item.path("detail").asText(""));
            }
            autoWidth(logSheet, 6);

            Sheet nodeSheet = workbook.createSheet("节点状态");
            writeRow(nodeSheet, 0, header, "患者", "第N次", "应复查日", "已触达", "已回院", "回院日期", "逾期天数", "最近操作人", "最近操作时间");
            r = 1;
            for (Map<String, Object> node : nodes) {
                writeRow(nodeSheet, r++, null,
                    String.valueOf(node.get("patientName")),
                    String.valueOf(node.get("seq")),
                    String.valueOf(node.get("dueDate")),
                    Boolean.TRUE.equals(node.get("reached")) ? "是" : "否",
                    Boolean.TRUE.equals(node.get("arrived")) ? "是" : "否",
                    String.valueOf(node.get("arrivedAt")),
                    String.valueOf(node.get("overdueDays")),
                    String.valueOf(node.get("lastOperator")),
                    String.valueOf(node.get("lastActionAt")));
            }
            autoWidth(nodeSheet, 9);
            workbook.write(output);
            LocalDate[] range = resolveRange(from, to);
            return new ExportBundle(output.toByteArray(), range[0].toString(), range[1].toString(), timeline.size());
        } catch (Exception error) {
            throw new IllegalStateException("护理随访留痕 XLSX 生成失败", error);
        }
    }

    /** DOCX：可签字归档的纸质件（含签字栏），用于留存纸质资料。 */
    public ExportBundle exportDocx(String from, String to, String operator, String action, SessionUser user) {
        List<ObjectNode> timeline = exportRows(from, to, operator, action, user);
        List<Map<String, Object>> nodes = nodes(LocalDate.now());
        LocalDate[] range = resolveRange(from, to);
        String rangeText = range[0] + " 至 " + range[1];
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph title = document.createParagraph();
            title.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
            XWPFRun titleRun = title.createRun();
            titleRun.setBold(true);
            titleRun.setFontSize(16);
            titleRun.setText("护理随访留痕与节点监控表");

            XWPFParagraph meta = document.createParagraph();
            XWPFRun metaRun = meta.createRun();
            metaRun.setFontSize(9);
            metaRun.setText("统计区间：" + rangeText
                + "    操作留痕 " + timeline.size() + " 条    节点 " + nodes.size() + " 条"
                + "    导出人：" + user.name() + "    导出时间：" + TIME.format(LocalDateTime.now()));

            XWPFParagraph h1 = document.createParagraph();
            XWPFRun h1Run = h1.createRun();
            h1Run.setBold(true);
            h1Run.setFontSize(12);
            h1Run.setText("一、随访操作留痕");

            if (timeline.isEmpty()) {
                XWPFParagraph empty = document.createParagraph();
                empty.createRun().setText("所选区间内没有随访操作记录。");
            } else {
                XWPFTable table = document.createTable(timeline.size() + 1, 5);
                setCell(table, 0, 0, "时间");
                setCell(table, 0, 1, "动作");
                setCell(table, 0, 2, "患者");
                setCell(table, 0, 3, "操作人");
                setCell(table, 0, 4, "详情");
                int r = 1;
                for (ObjectNode item : timeline) {
                    setCell(table, r, 0, item.path("createdAt").asText(""));
                    setCell(table, r, 1, item.path("actionLabel").asText(""));
                    setCell(table, r, 2, item.path("patientName").asText("未知患者"));
                    setCell(table, r, 3, item.path("operator").asText(""));
                    setCell(table, r, 4, item.path("detail").asText(""));
                    r++;
                }
            }

            XWPFParagraph h2 = document.createParagraph();
            XWPFRun h2Run = h2.createRun();
            h2Run.setBold(true);
            h2Run.setFontSize(12);
            h2Run.setText("二、随访节点状态");

            if (nodes.isEmpty()) {
                XWPFParagraph empty = document.createParagraph();
                empty.createRun().setText("当前没有已排期的随访节点。");
            } else {
                XWPFTable table = document.createTable(nodes.size() + 1, 7);
                String[] head = {"患者", "第N次", "应复查日", "已触达", "已回院", "回院日期", "逾期天数"};
                for (int i = 0; i < head.length; i++) setCell(table, 0, i, head[i]);
                int r = 1;
                for (Map<String, Object> node : nodes) {
                    setCell(table, r, 0, String.valueOf(node.get("patientName")));
                    setCell(table, r, 1, String.valueOf(node.get("seq")));
                    setCell(table, r, 2, String.valueOf(node.get("dueDate")));
                    setCell(table, r, 3, Boolean.TRUE.equals(node.get("reached")) ? "是" : "否");
                    setCell(table, r, 4, Boolean.TRUE.equals(node.get("arrived")) ? "是" : "否");
                    setCell(table, r, 5, String.valueOf(node.get("arrivedAt")));
                    setCell(table, r, 6, String.valueOf(node.get("overdueDays")));
                    r++;
                }
            }

            XWPFParagraph sign = document.createParagraph();
            sign.createRun().setFontSize(10);
            sign.createRun().setText("护理部核对：____________    护士长签字：____________    日期：____________");

            document.write(output);
            return new ExportBundle(output.toByteArray(), range[0].toString(), range[1].toString(), timeline.size());
        } catch (Exception error) {
            throw new IllegalStateException("护理随访留痕 DOCX 生成失败", error);
        }
    }

    /** 解析实际生效区间：默认最近 30 天，并对调颠倒的起止。 */
    private LocalDate[] resolveRange(String from, String to) {
        LocalDate today = LocalDate.now();
        LocalDate start = parseDate(from, today.minusDays(29));
        LocalDate end = parseDate(to, today);
        if (end.isBefore(start)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        return new LocalDate[] { start, end };
    }

    /** 导出返回体：附带**实际生效**的区间，避免回执与页面填写不一致。 */
    public record ExportBundle(byte[] bytes, String from, String to, int rows) {}

    /** 导出统一取数：区间内全部随访动作（时间倒序），上限保护。 */
    private List<ObjectNode> exportRows(String from, String to, String operator, String action, SessionUser user) {
        requireViewRole(user);
        LocalDate today = LocalDate.now();
        LocalDate start = parseDate(from, today.minusDays(29));
        LocalDate end = parseDate(to, today);
        String fromTime = start.format(DateTimeFormatter.ofPattern(DATE_ONLY)) + " 00:00:00";
        String toTime = end.format(DateTimeFormatter.ofPattern(DATE_ONLY)) + " 23:59:59";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(
            "FROM pre_ai_audit_logs WHERE action LIKE 'followup.%' AND action <> 'followup.recall.query' "
                + "AND created_at BETWEEN ? AND ? ");
        args.add(fromTime);
        args.add(toTime);
        if (operator != null && !operator.isBlank()) {
            where.append("AND operator = ? ");
            args.add(operator.trim());
        }
        if (action != null && !action.isBlank()) {
            where.append("AND action = ? ");
            args.add(action.trim());
        }
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) " + where, Integer.class, args.toArray());
        if (count != null && count > MAX_EXPORT_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "导出记录超过 " + MAX_EXPORT_ROWS + " 条，请缩小日期范围或增加筛选条件");
        }
        List<ObjectNode> rows = new ArrayList<>();
        jdbcTemplate.query(
            "SELECT id, action, encounter_id, operator, operator_role, detail, created_at " + where
                + "ORDER BY created_at DESC, id DESC",
            (RowCallbackHandler) resultSet -> {
                ObjectNode row = objectMapper.createObjectNode();
                row.put("id", resultSet.getString("id"));
                row.put("action", resultSet.getString("action"));
                row.put("actionLabel", label(resultSet.getString("action")));
                row.put("encounterId", resultSet.getString("encounter_id"));
                String operatorName = resultSet.getString("operator");
                row.put("operator", operatorName == null || operatorName.isBlank() ? "系统" : operatorName);
                row.put("operatorRole", safe(resultSet.getString("operator_role")));
                row.put("detail", safe(resultSet.getString("detail")));
                row.put("createdAt", safe(resultSet.getString("created_at")));
                rows.add(row);
            },
            args.toArray()
        );
        return withPatientNames(rows);
    }

    // ---------------------------------------------------------------- utils

    private void requireViewRole(SessionUser user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        if (!VIEW_ROLES.contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权查看护理随访留痕监控台");
        }
    }

    private String lastContact(String visitId) {
        try {
            String value = jdbcTemplate.queryForObject(
                "SELECT MAX(created_at) FROM pre_ai_audit_logs WHERE action = 'followup.recall.contact' "
                    + "AND detail LIKE ?",
                String.class, "%recall:" + visitId + "%");
            return value == null ? "" : value;
        } catch (Exception error) {
            // 考核数据不可静默降级为"未触达"，必须留痕
            log.warn("查询随访触达时间失败，visitId={}，该节点触达状态将不可信", visitId, error);
            return "";
        }
    }

    /**
     * 由 encounterId 批量解析患者姓名；encounterId 与 patientCaseId 均无时返回空串，
     * 由调用方回落为"未知患者"。
     */
    private Map<String, String> resolveNamesForTimeline(List<ObjectNode> timeline) {
        if (timeline.isEmpty()) return Map.of();
        Set<String> encounterIds = new LinkedHashSet<>();
        Set<String> caseIds = new LinkedHashSet<>();
        for (ObjectNode item : timeline) {
            String encounterId = item.path("encounterId").asText("");
            if (!encounterId.isBlank()) encounterIds.add(encounterId);
            String caseId = item.path("patientCaseId").asText("");
            if (!caseId.isBlank()) caseIds.add(caseId);
        }
        caseIds.addAll(resolveVisitToCase(timeline).values());
        return resolvePatientNames(encounterIds, caseIds);
    }
    private Map<String, String> resolveNamesByCase(Set<String> caseIds) {
        Map<String, String> result = new LinkedHashMap<>();
        if (caseIds.isEmpty()) return result;
        String placeholders = String.join(",", java.util.Collections.nCopies(caseIds.size(), "?"));
        jdbcTemplate.query(
            "SELECT id, patient_json FROM pre_ai_patient_cases WHERE id IN (" + placeholders + ")",
            (RowCallbackHandler) resultSet -> {
                String name = "";
                String raw = resultSet.getString("patient_json");
                if (raw != null) {
                    try {
                        JsonNode patient = objectMapper.readTree(raw);
                        name = patient.path("name").asText(patient.path("patientName").asText(""));
                    } catch (Exception error) {
                        log.warn("解析患者档案失败，caseId={}", resultSet.getString("id"), error);
                    }
                }
                result.put(resultSet.getString("id"), name.isBlank() ? "未知患者" : name);
            },
            caseIds.toArray()
        );
        return result;
    }

    private String label(String action) {
        if (action == null) return "";
        return ACTION_LABELS.getOrDefault(action, action);
    }

    private String createdDate(String createdAt) {
        String value = safe(createdAt);
        return value.length() >= 10 ? value.substring(0, 10) : "";
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        try {
            return value == null || value.isBlank() ? fallback : LocalDate.parse(value.trim());
        } catch (Exception error) {
            return fallback;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeDate(String value) {
        String text = safe(value);
        return text.length() >= 10 ? text.substring(0, 10) : text;
    }

    // ---- XLSX helpers ----

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private void writeRow(Sheet sheet, int index, CellStyle style, String... values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(values[i] == null ? "" : values[i]);
            if (style != null) cell.setCellStyle(style);
        }
    }

    private void autoWidth(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.setColumnWidth(i, 20 * 256);
        }
    }

    // ---- DOCX helpers ----

    private void setCell(XWPFTable table, int row, int col, String value) {
        if (table.getRow(row) == null) return;
        if (table.getRow(row).getCell(col) == null) return;
        var cell = table.getRow(row).getCell(col);
        var paragraph = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        var run = paragraph.createRun();
        run.setFontSize(9);
        run.setText(value == null ? "" : value);
    }
}
