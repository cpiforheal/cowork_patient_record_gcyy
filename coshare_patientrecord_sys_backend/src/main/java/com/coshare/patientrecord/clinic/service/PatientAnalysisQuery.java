package com.coshare.patientrecord.clinic.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

public record PatientAnalysisQuery(
    LocalDate from, LocalDate to, String view, String granularity, String metric, String basis,
    Integer ageMin, Integer ageMax, Map<String, List<String>> filters, int page, int pageSize, String sort
) {
    public static final List<String> DIMENSIONS = List.of(
        "gender", "ageBand", "region", "diagnosis", "operation", "primaryOperation", "status",
        "tcmDisease", "syndrome", "complaint", "contactState", "delayBand"
    );
    private static final Set<String> FOLLOW_UP_FILTERS = Set.of("contactState", "delayBand", "unscheduled");

    public static PatientAnalysisQuery parse(MultiValueMap<String, String> params) {
        LocalDate to = date(params.getFirst("to"), LocalDate.now());
        LocalDate from = date(params.getFirst("from"), to.minusDays(89));
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > 3660) {
            throw badRequest("日期范围无效，最长支持十年");
        }
        String view = option(params, "view", "overview", Set.of("overview", "population", "clinical", "complaints", "followup"));
        String granularity = option(params, "granularity", "week", Set.of("day", "week", "month"));
        if ("day".equals(granularity) && ChronoUnit.DAYS.between(from, to) > 730) {
            throw badRequest("日粒度最多查询两年，请切换周或月");
        }
        String metric = option(params, "metric", "visits", Set.of("visits", "patients"));
        String basis = "followup".equals(view) ? option(params, "basis", "due", Set.of("due", "contact")) : "visit";
        Integer ageMin = optionalInt(params.getFirst("ageMin"), 0, 130);
        Integer ageMax = optionalInt(params.getFirst("ageMax"), 0, 130);
        if (ageMin != null && ageMax != null && ageMin > ageMax) throw badRequest("年龄下限不能大于上限");
        Map<String, List<String>> filters = new LinkedHashMap<>();
        for (String dimension : DIMENSIONS) {
            if (FOLLOW_UP_FILTERS.contains(dimension) && !"followup".equals(view)) continue;
            List<String> values = params.getOrDefault(dimension, List.of()).stream()
                .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
            if (values.size() > 100 || values.stream().anyMatch(value -> value.length() > 1000)) {
                throw badRequest("筛选条件过长");
            }
            if (!values.isEmpty()) filters.put(dimension, values);
        }
        if ("followup".equals(view) && "true".equals(params.getFirst("unscheduled"))) {
            filters.put("unscheduled", List.of("true"));
        }
        return new PatientAnalysisQuery(
            from, to, view, granularity, metric, basis, ageMin, ageMax, Map.copyOf(filters),
            integer(params, "page", 1, 1, 1_000_000), integer(params, "pageSize", 25, 1, 100),
            option(params, "sort", "desc", Set.of("asc", "desc"))
        );
    }

    public boolean includes(LocalDate date) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    public boolean accepts(String dimension, String value) {
        return acceptsAny(dimension, List.of(value));
    }

    public boolean acceptsAny(String dimension, List<String> values) {
        List<String> selected = filters.get(dimension);
        return selected == null || values.stream().anyMatch(selected::contains);
    }

    public boolean unscheduled() {
        return filters.containsKey("unscheduled");
    }

    private static String option(MultiValueMap<String, String> params, String key, String fallback, Set<String> allowed) {
        String value = params.getFirst(key);
        if (value == null || value.isBlank()) return fallback;
        if (!allowed.contains(value)) throw badRequest("无效参数: " + key);
        return value;
    }

    private static LocalDate date(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException error) {
            throw badRequest("日期格式须为 YYYY-MM-DD");
        }
    }

    private static int integer(MultiValueMap<String, String> params, String key, int fallback, int min, int max) {
        Integer value = optionalInt(params.getFirst(key), min, max);
        return value == null ? fallback : value;
    }

    private static Integer optionalInt(String value, int min, int max) {
        if (value == null || value.isBlank()) return null;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) return parsed;
        } catch (NumberFormatException ignored) {
            // Invalid filters must not silently broaden the patient cohort.
        }
        throw badRequest("数值参数超出范围");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
