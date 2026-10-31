package com.campusmarket.auth;

public final class ApiException extends RuntimeException {
    public final int status;
    public final int code;
    public ApiException(int status, int code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
