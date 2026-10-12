package com.researchdesk.identity;

public final class ApiFailure extends RuntimeException {
    public final int status;
    public final String code;
    public ApiFailure(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
}
