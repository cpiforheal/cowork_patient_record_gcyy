package com.coshare.patientrecord.maintenance.datapurge;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.maintenance.datapurge.dto.DataPurgeExecuteRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
@RequestMapping("/clinic-api/maintenance/data-purge")
public class DataPurgeController {

    private final DataPurgeService service;
    private final ObjectMapper mapper;

    public DataPurgeController(DataPurgeService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/preview")
    public ApiResult<Map<String, Object>> preview() {
        return ApiResult.success(asMap(service.preview()));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> execute(@Valid @RequestBody DataPurgeExecuteRequest request) {
        return ApiResult.of(200, "数据清理任务已执行", asMap(service.execute(request)));
    }

    @GetMapping("/runs/{runId}")
    public ApiResult<Map<String, Object>> run(@PathVariable String runId) {
        return ApiResult.success(asMap(service.run(runId)));
    }

    @PostMapping("/runs/{runId}/resume-files")
    public ApiResult<Map<String, Object>> resumeFiles(@PathVariable String runId) {
        return ApiResult.of(200, "文件隔离续作已执行", asMap(service.resumeFileQuarantine(runId)));
    }

    private Map<String, Object> asMap(Object value) {
        return mapper.convertValue(value, new TypeReference<>() {});
    }
}
