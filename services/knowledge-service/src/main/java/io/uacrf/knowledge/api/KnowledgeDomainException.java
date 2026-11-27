package io.uacrf.knowledge.api;

import org.springframework.http.HttpStatus;

public class KnowledgeDomainException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public KnowledgeDomainException(HttpStatus status, String code, String message) {
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
