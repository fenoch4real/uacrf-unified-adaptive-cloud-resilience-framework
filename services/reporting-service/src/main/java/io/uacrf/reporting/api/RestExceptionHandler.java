package io.uacrf.reporting.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(ReportingDomainException.class)
    public ResponseEntity<Map<String, Object>> handleDomain(ReportingDomainException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
                "error", ex.getCode(),
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }
}
