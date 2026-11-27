package io.uacrf.advisory.api;

import org.springframework.http.HttpStatus;

public class AdvisoryDomainException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AdvisoryDomainException(HttpStatus status, String code, String message) {
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
