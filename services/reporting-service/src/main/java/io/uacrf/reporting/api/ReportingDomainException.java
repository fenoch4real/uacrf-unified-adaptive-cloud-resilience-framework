package io.uacrf.reporting.api;

import org.springframework.http.HttpStatus;

public class ReportingDomainException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ReportingDomainException(HttpStatus status, String code, String message) {
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
