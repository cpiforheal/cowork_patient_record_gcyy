package com.coshare.patientrecord.clinic.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.clinic.service.PatientAnalysisService.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ResponseStatusException;

class PatientAnalysisServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PatientAnalysisService service = new PatientAnalysisService(jdbc, mapper);

    @Test
    void countsVisitsAndPatientsWithoutMultiplicationAndFillsZeroDays() {
        Visit a = visit("a", "p1", "2026-09-01", "男", 43, "甲病");
        Visit b = visit("b", "p1", "2026-09-03", "男", 43, "甲病");
        Visit cancelled = new Visit("c", "p2", LocalDate.parse("2026-09-02"), mapper.createObjectNode(), "CANCELLED");
        Visit withdrawn = new Visit("w", "p3", LocalDate.parse("2026-09-02"), mapper.createObjectNode(), "WITHDRAWN");
        Data data = data(a, b, cancelled, withdrawn);
        Map<String, Object> result = service.analyze(query("granularity", "day"), data, true);
        assertEquals(2, map(result.get("summary")).get("visits"));
        assertEquals(1, map(result.get("summary")).get("patients"));
        List<Map<String, Object>> trend = rows(chart(result, "visits"));
        assertEquals(3, trend.size());
        assertEquals(0, trend.get(1).get("primary"));
        assertEquals(2, service.detailResult(query(), data).get("total"));
        assertEquals(1, service.detailResult(query("metric", "patients"), data).get("total"));
    }

    @Test
    void choosesDemographicSnapshotBeforeDiagnosisFilterAndKeepsStableRegion() {
        Visit old = visit("old", "p1", "2026-09-01", "女", 35, "甲病");
        Visit recent = visit("recent", "p1", "2026-09-03", "男", 45, "乙病");
        Data data = data(old, recent);
        Map<String, Object> result = service.analyze(query("diagnosis", "甲病", "gender", "男", "ageMin", "40"), data, true);
        assertEquals(1, map(result.get("summary")).get("visits"));
        assertEquals(0, map(service.analyze(query("diagnosis", "甲病", "gender", "女"), data, true).get("summary")).get("visits"));
        assertEquals(45, rows(service.detailResult(query("diagnosis", "甲病"), data)).get(0).get("age"));
    }

    @Test
    void parsesAgeAtVisitAndKeepsUnknownAddressLevels() {
        Visit visit = new Visit("a", "p", LocalDate.parse("2026-09-01"),
            mapper.createObjectNode().put("birthDate", "2000-10-01").put("age", "90").put("gender", "待核实").put("address", "江苏省南京市鼓楼区"), "IN_PROGRESS");
        Snapshot snapshot = PatientAnalysisService.snapshot(visit);
        assertEquals(25, snapshot.age());
        assertEquals("未记录", snapshot.gender());
        assertEquals(List.of("鼓楼区", "未记录", "未记录"), snapshot.region());
        assertEquals(List.of("沭阳县", "新河镇", "春生村"), PatientAnalysisService.parseRegion("江苏省宿迁市沭阳县新河镇春生村12号"));
    }

    @Test
    void usesOrWithinDimensionAndAndAcrossDimensions() {
        Data data = data(visit("a", "p1", "2026-09-01", "男", 40, "甲病"),
            visit("b", "p2", "2026-09-02", "男", 50, "乙病"), visit("c", "p3", "2026-09-03", "女", 50, "丙病"));
        assertEquals(2, map(service.analyze(query("diagnosis", "甲病", "diagnosis", "乙病", "gender", "男"), data, true).get("summary")).get("visits"));
        assertEquals(1, map(service.analyze(query("diagnosis", "甲病", "diagnosis", "乙病", "ageMin", "45"), data, true).get("summary")).get("visits"));
    }

    @Test
    void prefersStructuredDiagnosisAndNeverUsesPlannedSurgery() {
        Visit visit = visit("a", "p1", "2026-09-01", "男", 40, "甲病");
        visit.stages.put("DOCTOR", mapper.createObjectNode().put("primaryWesternDiagnosis", "错误的回退").put("plannedPrimaryOperation", "拟行术式"));
        visit.finishFields();
        assertEquals(List.of("甲病"), visit.primary());
        assertTrue(visit.operations.isEmpty());
        visit.stages.put("SURGERY", mapper.createObjectNode().put("actualPrimaryOperation", "实际术式"));
        visit.finishFields();
        assertEquals("实际术式", visit.primaryOperation);
    }

    @Test
    void classifiesRecheckByUnionOfThreeBasesAndFiltersByComplaint() {
        Visit text = visit("a", "p1", "2026-09-01", "男", 40, "甲病");
        text.stages.put("RECEPTION", mapper.createObjectNode().put("chiefComplaintText", "术后复查，切口愈合良好"));
        text.finishFields();
        Visit numbered = visit("b", "p2", "2026-09-02", "女", 50, "乙病");
        numbered.visitNo = 2;
        numbered.finishFields();
        Visit source = visit("c", "p3", "2026-09-03", "男", 60, "丙病");
        ((ObjectNode) source.patient).put("patientSource", "复诊");
        source.finishFields();
        Visit tagged = visit("d", "p4", "2026-09-03", "女", 30, "丁病");
        ((ObjectNode) tagged.patient).putArray("registrationSymptoms").add("便血");
        tagged.finishFields();
        Visit plain = visit("e", "p5", "2026-09-03", "男", 70, "戊病");
        Data data = data(text, numbered, source, tagged, plain);

        Map<String, Object> donut = chart(service.analyze(query("view", "complaints"), data, true), "recheckComposition");
        assertEquals("donut", donut.get("kind"));
        assertEquals(3, donut.get("centerValue"));
        assertEquals(3, rows(donut).stream().filter(row -> "复查".equals(row.get("label"))).findFirst().orElseThrow().get("value"));
        LinkedMultiValueMap<String, String> recheck = params("view", "complaints", "complaint", "复查");
        assertEquals(3, service.detailResult(PatientAnalysisQuery.parse(recheck), data).get("total"));
        assertEquals(1, service.detailResult(PatientAnalysisQuery.parse(params("view", "complaints", "complaint", "便血")), data).get("total"));
        assertEquals(1, service.detailResult(PatientAnalysisQuery.parse(params("view", "complaints", "complaint", "未记录")), data).get("total"));

        Map<String, Object> details = service.detailResult(PatientAnalysisQuery.parse(recheck), data);
        Map<String, Object> first = rows(details).get(0);
        assertFalse(first.get("recheckBasis").toString().isBlank());
        // patientKey "p1" carries no "case:" prefix, so no case id can be exposed.
        assertEquals("", first.get("patientCaseId"));
        Visit caseVisit = visit("f", "case:p9", "2026-09-03", "男", 65, "己病");
        caseVisit.visitNo = 3;
        caseVisit.finishFields();
        assertEquals("p9", rows(service.detailResult(query(), data(caseVisit))).get(0).get("patientCaseId"));
    }

    @Test
    void complaintTagsMergeRegistrationAndReceptionWithoutDuplication() {
        Visit visit = visit("a", "p1", "2026-09-01", "男", 40, "甲病");
        ((ObjectNode) visit.patient).putArray("registrationSymptoms").add("便血").add("肿物脱出");
        visit.stages.put("RECEPTION", mapper.createObjectNode()
            .<ObjectNode>set("chiefComplaint", mapper.createArrayNode().add("便血").add("肛周瘙痒"))
            .put("chiefComplaintText", "便血伴肛周瘙痒"));
        visit.finishFields();
        assertEquals(new LinkedHashSet<>(List.of("便血", "肿物脱出", "肛周瘙痒")), visit.complaintTags);
        assertEquals("便血伴肛周瘙痒", visit.complaintText);
        Map<String, Object> tags = chart(service.analyze(query("view", "complaints"), data(visit), true), "complaintTags");
        assertEquals(3, rows(tags).size());
        LinkedMultiValueMap<String, String> filters = params("view", "complaints");
        filters.add("complaint", "便血");
        assertEquals(1, service.detailResult(PatientAnalysisQuery.parse(filters), data(visit)).get("total"));
    }

    @Test
    void followsNodeDatesNotOriginalEncounterAndUsesFirstContact() {
        Visit original = visit("a", "p1", "2026-01-01", "男", 40, "甲病");
        Data data = new Data(List.of(original),
            List.of(new Node("n1", "a", LocalDate.parse("2026-09-02"), "1"), new Node("n2", "a", null, "2")),
            List.of(new Contact("c1", "n1", LocalDateTime.parse("2026-09-01T09:00:00")),
                new Contact("c2", "n1", LocalDateTime.parse("2026-09-03T09:00:00"))));
        Map<String, Object> due = service.analyze(query("view", "followup"), data, true);
        assertEquals(1, map(due.get("summary")).get("nodes"));
        assertEquals(1L, map(due.get("summary")).get("contactedNodes"));
        assertEquals(1, map(due.get("summary")).get("unscheduledNodes"));
        assertEquals(1, rows(chart(due, "delay")).get(0).get("value"));
        Map<String, Object> contact = service.analyze(query("view", "followup", "basis", "contact"), data, true);
        assertEquals(2, map(contact.get("summary")).get("contacts"));
        assertEquals(2, service.detailResult(query("view", "followup", "basis", "contact"), data).get("total"));
        assertEquals(1, service.detailResult(query("view", "followup", "unscheduled", "true"), data).get("total"));
        assertFalse(contact.toString().contains("completionRate"));
    }

    @Test
    void excludesContactsAfterEndAndDoesNotExpandDateRangeToMonth() {
        Visit visit = visit("a", "p", "2026-01-01", "男", 40, "甲病");
        Data data = new Data(List.of(visit),
            List.of(new Node("n1", "a", LocalDate.parse("2026-09-03"), "1"), new Node("n2", "a", LocalDate.parse("2026-09-20"), "2")),
            List.of(new Contact("c1", "n1", LocalDateTime.parse("2026-09-04T09:00:00"))));
        Map<String, Object> result = service.analyze(query("view", "followup"), data, true);
        assertEquals(1, map(result.get("summary")).get("nodes"));
        assertEquals(0L, map(result.get("summary")).get("contactedNodes"));
    }

    @Test
    void exactAuditIdsDoNotMatchPrefixesAndLoadUsesOnlyReadQueries() {
        when(jdbc.queryForList(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("pre_ai_encounters")) return List.of(Map.of(
                "id", "a", "patient_case_id", "p", "source_patient_id", "", "status", "IN_PROGRESS",
                "patient_json", "{\"patientName\":\"隐私测试姓名\",\"phone\":\"13800009999\",\"visitDate\":\"invalid\"}", "created_at", "2026-09-01"));
            return List.of(
                Map.of("id", "c1", "detail", "已联系 recall:n10 第一次", "created_at", "2026-09-01 10:00:00"),
                Map.of("id", "c2", "detail", "已联系 recall:n1 第一次", "created_at", "2026-09-02 10:00:00"));
        });
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("pre_ai_follow_up_visits")) return List.of(Map.of(
                "id", "n1", "encounter_id", "a", "patient_case_id", "p", "seq", "1", "next_review_date", "2026-09-02"));
            return List.of();
        });
        Data data = service.load(query("view", "followup"));
        assertEquals(1, data.contacts().size());
        assertEquals("c2", data.contacts().get(0).id());
        assertTrue(data.visits().get(0).dateFallback);
        String aggregate = service.analyze(query("view", "followup"), data, true).toString();
        assertFalse(aggregate.contains("隐私测试姓名"));
        assertFalse(aggregate.contains("13800009999"));
        assertFalse(aggregate.contains("patient_json"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void matricesAndBarsDrillIntoExactlyTheirCountsIncludingPrimaryVsSecondaryOperations() {
        Visit a = visit("a", "p1", "2026-09-01", "男", 40, "甲病");
        a.primaryOperation = "主术式A"; a.operations.addAll(List.of("主术式A", "辅助B"));
        Visit b = visit("b", "p2", "2026-09-02", "女", 60, "乙病");
        b.primaryOperation = "辅助B"; b.operations.add("辅助B");
        Data data = data(a, b);
        for (String view : List.of("overview", "population", "clinical", "complaints")) {
            Map<String, Object> result = service.analyze(query("view", view), data, true);
            for (Map<String, Object> chart : charts(result)) {
                if ("trend".equals(chart.get("kind"))) continue;
                for (Map<String, Object> row : rows(chart)) {
                    LinkedMultiValueMap<String, String> params = params("view", view);
                    filters(row).forEach(params::put);
                    assertEquals(row.get("value"), service.detailResult(PatientAnalysisQuery.parse(params), data).get("total"), chart.get("id") + ": " + row);
                }
            }
        }
    }

    @Test
    void topTenOtherKeepsAllCategoriesAndUnknownAndReconciles() {
        List<Visit> visits = new ArrayList<>();
        for (int i = 0; i < 15; i++) visits.add(visit("a" + i, "p" + i, "2026-09-01", "男", 40, "病种" + i));
        visits.add(visit("unknown", "u", "2026-09-02", "女", 50, ""));
        Data data = new Data(visits, List.of(), List.of());
        Map<String, Object> chart = chart(service.analyze(query(), data, true), "diagnosis");
        assertEquals(12, rows(chart).size());
        assertEquals(16, list(chart.get("tableRows")).size());
        Map<String, Object> other = rows(chart).stream().filter(row -> row.get("label").toString().startsWith("其他")).findFirst().orElseThrow();
        LinkedMultiValueMap<String, String> params = params();
        filters(other).forEach(params::put);
        assertEquals(other.get("value"), service.detailResult(PatientAnalysisQuery.parse(params), data).get("total"));
    }

    @Test
    void validatesFiltersAndEnforcesDetailPermissionBeforeReading() {
        assertThrows(ResponseStatusException.class, () -> query("ageMin", "70", "ageMax", "20"));
        assertThrows(ResponseStatusException.class, () -> query("from", "bad-date"));
        assertThrows(ResponseStatusException.class, () -> query("page", "-1"));
        assertThrows(ResponseStatusException.class, () -> service.details(query(), user("doctor")));
        assertThrows(ResponseStatusException.class, () -> service.analysis(query(), user("warehouse")));
        verifyNoInteractions(jdbc);
    }

    @Test
    void paginatesAndSortsWithoutChangingTotals() {
        Data data = data(visit("a", "p1", "2026-09-01", "男", 40, "甲病"), visit("b", "p2", "2026-09-03", "女", 60, "乙病"));
        Map<String, Object> result = service.detailResult(query("pageSize", "1", "page", "2", "sort", "asc"), data);
        assertEquals(2, result.get("total"));
        assertEquals("b", rows(result).get(0).get("encounterId"));
        assertEquals(0, rows(service.detailResult(query("page", "1000000"), data)).size());
    }

    @Test
    void bucketsCrossMonthWeeksOnMondayAndKeepsFullLabels() {
        Data data = data(visit("a", "p", "2026-09-01", "男", 40, "甲病"));
        Map<String, Object> trend = chart(service.analyze(query("granularity", "week"), data, true), "visits");
        assertEquals("2026-08-31", rows(trend).get(0).get("label"));
        assertEquals(List.of("2026-09-01"), filters(rows(trend).get(0)).get("from"));
        assertEquals(List.of("2026-09-03"), filters(rows(trend).get(0)).get("to"));
    }

    @Test
    void stageFunnelWeekdayAndStatusKeepFixedOrderAndDrillExactly() {
        Visit a = visit("a", "p1", "2026-09-01", "男", 40, "甲病");
        a.stageStatuses.put("DOCTOR", "COMPLETED");
        a.stageStatuses.put("REGISTRATION", "COMPLETED");
        Visit b = visit("b", "p2", "2026-09-02", "女", 60, "乙病");
        b.stageStatuses.put("REGISTRATION", "COMPLETED");
        b.stageStatuses.put("SURGERY", "DRAFT");
        Visit c = visit("c", "p3", "2026-09-03", "女", 30, "乙病");
        Data data = data(a, b, c);
        Map<String, Object> overview = service.analyze(query(), data, true);
        Map<String, Object> funnel = chart(overview, "stageFunnel");
        assertEquals(Boolean.TRUE, funnel.get("ordered"));
        assertEquals(List.of("前台登记", "医生诊疗", "无已完成环节"), rows(funnel).stream().map(r -> r.get("label")).toList());
        assertEquals(List.of(2, 1, 1), rows(funnel).stream().map(r -> r.get("value")).toList());
        assertEquals(List.of("周二", "周三", "周四"), rows(chart(overview, "weekday")).stream().map(r -> r.get("label")).toList());
        assertEquals("stack", chart(overview, "status").get("kind"));
        assertEquals(1, service.detailResult(query("stage", "DOCTOR"), data).get("total"));
        assertEquals(1, service.detailResult(query("weekday", "周三"), data).get("total"));
    }

    @Test
    void returnRateUsesFullHistoryAndExcludesPatientsStillInObservation() {
        Visit first = visit("a", "p1", "2026-01-02", "男", 40, "甲病");
        Visit back = visit("b", "p1", "2026-01-20", "男", 40, "甲病");
        Visit once = visit("c", "p2", "2026-01-03", "女", 50, "乙病");
        LinkedMultiValueMap<String, String> params = params();
        params.set("from", "2026-01-01"); params.set("to", "2026-01-10");
        Map<String, Object> summary = map(service.analyze(PatientAnalysisQuery.parse(params), data(first, back, once), true).get("summary"));
        List<Map<String, Object>> rates = list(summary.get("returnRates"));
        assertEquals(30, rates.get(0).get("days"));
        assertEquals(2, rates.get(0).get("eligible"));
        assertEquals(1, rates.get(0).get("returned"));
        assertEquals(50.0, rates.get(0).get("rate"));
        Visit recent = visit("d", "p3", LocalDate.now().toString(), "女", 50, "乙病");
        LinkedMultiValueMap<String, String> today = params();
        today.set("from", LocalDate.now().toString()); today.set("to", LocalDate.now().toString());
        Map<String, Object> pending = list(map(service.analyze(PatientAnalysisQuery.parse(today), data(recent), true).get("summary")).get("returnRates")).get(0);
        assertEquals(0, pending.get("eligible"));
        assertEquals(1, pending.get("pending"));
    }

    @Test
    void overdueNodesCountPastDueWithoutContactRecord() {
        Visit original = visit("a", "p1", "2026-01-01", "男", 40, "甲病");
        Data data = new Data(List.of(original),
            List.of(new Node("n1", "a", LocalDate.parse("2026-09-02"), "1"), new Node("n2", "a", LocalDate.parse("2026-09-03"), "2")),
            List.of(new Contact("c1", "n2", LocalDateTime.parse("2026-09-03T09:00:00"))));
        assertEquals(1L, map(service.analyze(query("view", "followup"), data, true).get("summary")).get("overdueNodes"));
    }

    Visit visit(String id, String patient, String date, String gender, int age, String diagnosis) {
        Visit visit = new Visit(id, patient, LocalDate.parse(date), mapper.createObjectNode()
            .put("patientName", "演示患者" + id).put("gender", gender).put("age", age + "岁")
            .put("address", "江苏省宿迁市沭阳县新河镇春生村"), "IN_PROGRESS");
        if (!diagnosis.isEmpty()) visit.diagnoses.put("WESTERN_PRIMARY", new LinkedHashSet<>(List.of(diagnosis)));
        visit.finishFields();
        return visit;
    }
    Data data(Visit... visits) { return new Data(List.of(visits), List.of(), List.of()); }
    static PatientAnalysisQuery query(String... pairs) { return PatientAnalysisQuery.parse(params(pairs)); }
    static LinkedMultiValueMap<String, String> params(String... pairs) {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.set("from", "2026-09-01"); params.set("to", "2026-09-03");
        for (int i = 0; i < pairs.length; i += 2) {
            if (PatientAnalysisQuery.DIMENSIONS.contains(pairs[i])) params.add(pairs[i], pairs[i + 1]);
            else params.set(pairs[i], pairs[i + 1]);
        }
        return params;
    }
    static SessionUser user(String role) { return new SessionUser("test", "test", "test", role, role, "", "", false, Instant.now().plusSeconds(3600)); }
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> list(Object value) { return (List<Map<String, Object>>) value; }
    static List<Map<String, Object>> rows(Map<String, Object> value) { return list(value.containsKey("rows") ? value.get("rows") : List.of()); }
    static List<Map<String, Object>> charts(Map<String, Object> value) { return list(value.get("charts")); }
    static Map<String, Object> chart(Map<String, Object> value, String id) { return charts(value).stream().filter(c -> id.equals(c.get("id"))).findFirst().orElseThrow(); }
    @SuppressWarnings("unchecked") static Map<String, List<String>> filters(Map<String, Object> row) { return (Map<String, List<String>>) row.get("filters"); }
}
