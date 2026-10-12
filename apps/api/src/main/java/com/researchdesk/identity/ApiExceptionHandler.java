package com.researchdesk.identity;

import com.researchdesk.Contracts.ApiError;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<ApiError> failure(ApiFailure failure) {
        return ResponseEntity.status(failure.status).header("Cache-Control", "no-store").body(new ApiError(failure.code, failure.getMessage(), UUID.randomUUID().toString()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformed(Exception ignored) {
        return failure(new ApiFailure(400, "INVALID_REQUEST", "请求格式有误，请检查输入。"));
    }
}
