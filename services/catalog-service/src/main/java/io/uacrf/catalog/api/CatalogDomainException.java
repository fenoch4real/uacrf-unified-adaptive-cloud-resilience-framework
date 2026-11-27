package io.uacrf.catalog.api;

import org.springframework.http.HttpStatus;

public class CatalogDomainException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public CatalogDomainException(HttpStatus status, String code, String message) {
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
