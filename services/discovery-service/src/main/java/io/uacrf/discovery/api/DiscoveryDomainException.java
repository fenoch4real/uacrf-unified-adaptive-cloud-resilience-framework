package io.uacrf.discovery.api;

import org.springframework.http.HttpStatus;

public class DiscoveryDomainException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public DiscoveryDomainException(HttpStatus status, String code, String message) {
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
