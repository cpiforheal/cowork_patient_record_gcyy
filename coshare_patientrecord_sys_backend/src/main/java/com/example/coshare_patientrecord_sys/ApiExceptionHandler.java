package com.example.coshare_patientrecord_sys;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResult<Void>> handleResponseStatus(ResponseStatusException error) {
        HttpStatusCode status = error.getStatusCode();
        String message = error.getReason() == null ? "Request failed" : error.getReason();
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

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResult<Void>> handleDatabaseError(Exception error) {
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResult.of(500, "数据库操作失败，请联系管理员检查服务日志", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnexpected(Exception error) {
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResult.of(500, "系统处理失败，请联系管理员检查服务日志", null));
    }
}
