package org.uacrf.evidence;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class EvidenceExceptionHandler {
  @ExceptionHandler(EvidenceException.class)
  ResponseEntity<EvidenceResponses.Error> handleEvidence(EvidenceException error) {
    return ResponseEntity.status(error.status())
        .body(new EvidenceResponses.Error(error.code(), error.getMessage(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<EvidenceResponses.Error> handleInvalidRequest(
      MethodArgumentNotValidException error) {
    return ResponseEntity.badRequest()
        .body(
            new EvidenceResponses.Error(
                "INVALID_REQUEST", "The request does not match the evidence contract.", UUID.randomUUID().toString()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<EvidenceResponses.Error> handleConflict(DataIntegrityViolationException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new EvidenceResponses.Error(
                "EVIDENCE_CONFLICT", "The evidence record conflicts with current state.", UUID.randomUUID().toString()));
  }
}
