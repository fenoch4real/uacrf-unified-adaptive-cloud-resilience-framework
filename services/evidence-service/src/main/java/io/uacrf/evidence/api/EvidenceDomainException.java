package io.uacrf.evidence.api;

import org.springframework.http.HttpStatus;

public class EvidenceDomainException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public EvidenceDomainException(HttpStatus status, String code, String message) {
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
