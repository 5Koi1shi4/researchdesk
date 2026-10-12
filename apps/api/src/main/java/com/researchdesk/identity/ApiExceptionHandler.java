package com.researchdesk.identity;

import com.researchdesk.Contracts.ApiError;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private String requestId() {
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
            var id=servlet.getRequest().getAttribute(RequestAuditFilter.ATTRIBUTE);
            if(id instanceof String value) return value;
        }
        return UUID.randomUUID().toString();
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(Exception ignored) { return failure(new ApiFailure(413, "UPLOAD_TOO_LARGE", "单文件不能超过配置限制（最大 20 MiB），请拆分后上传。")); }
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    ResponseEntity<ApiError> missingFile(Exception ignored) { return failure(new ApiFailure(400, "INVALID_REQUEST", "请选择一个资料文件后上传。")); }
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<ApiError> failure(ApiFailure failure) {
        return ResponseEntity.status(failure.status).header("Cache-Control", "no-store").body(new ApiError(failure.code, failure.getMessage(), requestId()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformed(Exception ignored) {
        return failure(new ApiFailure(400, "INVALID_REQUEST", "请求格式有误，请检查输入。"));
    }
}
