package com.coshare.patientrecord.exam;

import com.coshare.patientrecord.common.api.ApiResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
@RequestMapping("/exam-api")
public class ExamApiController {

    private final ExamService service;
    private final ObjectMapper objectMapper;

    public ExamApiController(ExamService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/paper")
    public ApiResult<Map<String, Object>> paper() {
        return ApiResult.success(service.paperView());
    }

    @PostMapping("/attempts")
    public ApiResult<Map<String, Object>> start(@RequestBody Map<String, Object> payload) {
        return ApiResult.of(200, "开始答题", service.start(toJson(payload)));
    }

    @PutMapping("/attempts/{id}/draft")
    public ApiResult<Map<String, Object>> draft(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return ApiResult.success(service.saveDraft(id, toJson(payload)));
    }

    @PostMapping("/attempts/{id}/submit")
    public ApiResult<Map<String, Object>> submit(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return ApiResult.of(200, "交卷成功", service.submit(id, toJson(payload)));
    }

    @GetMapping("/attempts/{id}")
    public ApiResult<Map<String, Object>> attempt(@PathVariable String id, @RequestParam String token) {
        return ApiResult.success(service.attempt(id, token));
    }

    private JsonNode toJson(Map<String, Object> payload) {
        return objectMapper.valueToTree(payload == null ? Map.of() : payload);
    }
}