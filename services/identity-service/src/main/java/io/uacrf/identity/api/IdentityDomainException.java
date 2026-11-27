package io.uacrf.identity.api;

import org.springframework.http.HttpStatus;

public class IdentityDomainException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public IdentityDomainException(HttpStatus status, String code, String message) {
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
