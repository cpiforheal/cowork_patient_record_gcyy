package com.example.coshare_patientrecord_sys;

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

    @ExceptionHandler({ MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class })
    public ResponseEntity<ApiResult<Void>> handleBadRequest(Exception error) {
        String message = error.getMessage() == null ? "Invalid request" : error.getMessage();
        return ResponseEntity.badRequest().body(ApiResult.of(400, message, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnexpected(Exception error) {
        String message = error.getMessage() == null ? "Internal server error" : error.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResult.of(500, message, null));
    }
}
