package com.coshare.patientrecord.common.exception;

import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.common.observability.TraceIdFilter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResult<Void>> handleResponseStatus(ResponseStatusException error) {
        HttpStatusCode status = error.getStatusCode();
        String message = error.getReason() == null ? "Request failed" : error.getReason();
        if (status.is5xxServerError()) {
            String requestId = requestId();
            log.warn("API response status error, requestId={}, status={}", requestId, status.value(), error);
            message = message + "，日志编号：" + requestId;
        }
        return ResponseEntity.status(status).body(ApiResult.of(status.value(), message, null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<Void>> handleBusinessBadRequest(Exception error) {
        String message = error.getMessage() == null ? "Invalid request" : error.getMessage();
        return ResponseEntity.badRequest().body(ApiResult.of(400, message, null));
    }

    @ExceptionHandler({ MethodArgumentNotValidException.class, HttpMessageNotReadableException.class })
    public ResponseEntity<ApiResult<Void>> handleInvalidPayload(Exception error) {
        return ResponseEntity.badRequest().body(ApiResult.of(400, "请求参数格式不正确，请检查后重试", null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleNoResource(NoResourceFoundException error) {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ApiResult.of(404, "请求的接口或资源不存在", null));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResult<Void>> handleDatabaseError(Exception error) {
        String requestId = requestId();
        log.error("API database error, requestId={}", requestId, error);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResult.of(500, "数据库操作失败，请联系管理员检查服务日志：" + requestId, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnexpected(Exception error) {
        String requestId = requestId();
        log.error("API unexpected error, requestId={}", requestId, error);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResult.of(500, "系统处理失败，请联系管理员检查服务日志：" + requestId, null));
    }

    private String requestId() {
        String requestId = MDC.get(TraceIdFilter.MDC_KEY);
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }
}
