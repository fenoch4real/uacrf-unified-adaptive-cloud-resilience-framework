package io.uacrf.recovery.api;

import org.springframework.http.HttpStatus;

public class RecoveryDomainException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public RecoveryDomainException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
