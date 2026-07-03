package com.coshare.patientrecord.common.api;

public record ApiResult<T>(int code, String msg, T data) {

    public static <T> ApiResult<T> success(T data) {
        return of(200, "success", data);
    }

    public static <T> ApiResult<T> of(int code, String msg, T data) {
        return new ApiResult<>(code, msg, data);
    }
}
