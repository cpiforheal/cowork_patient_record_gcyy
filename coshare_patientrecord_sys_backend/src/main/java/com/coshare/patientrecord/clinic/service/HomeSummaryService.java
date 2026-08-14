package com.coshare.patientrecord.clinic.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 欢迎主页与待办面板共用的全院轻量运营概况：纯 COUNT 聚合，不含任何患者隐私字段，
 * 全部登录角色可读，避免前端为拿几个数字拉整库 JSON。
 */
@Service
@Profile("mysql")
public class HomeSummaryService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public HomeSummaryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        // pre_ai_encounters.created_at 为字符串时间戳，日期前缀匹配兼容 ISO 与空格分隔两种格式。
        result.put("todayRegistered", scalar("""
            SELECT COUNT(*) FROM pre_ai_encounters
            WHERE created_at LIKE CONCAT(CURDATE(), '%') AND status <> 'CANCELLED'
            """));
        result.put("queueWaiting", scalar("""
            SELECT COUNT(*) FROM clinic_queue_tasks t
            JOIN clinic_queue_tickets q ON q.id = t.ticket_id
            WHERE q.business_date = CURDATE() AND t.status = 'WAITING'
            """));
        result.put("queueCompletedToday", scalar(
            "SELECT COUNT(*) FROM clinic_queue_tickets WHERE business_date = CURDATE() AND overall_status = 'COMPLETED'"
        ));
        result.put("tcmReady", scalar(
            "SELECT COUNT(*) FROM tcm_pharmacy_prescriptions WHERE prescription_status IN ('READY', 'CALLED')"
        ));
        result.put("tcmInProgress", scalar(
            "SELECT COUNT(*) FROM tcm_pharmacy_prescriptions WHERE prescription_status IN ('DISPENSING', 'DECOCTING')"
        ));
        result.put("serverTime", LocalDateTime.now().format(DATE_TIME));
        return result;
    }

    private int scalar(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
