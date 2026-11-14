package org.uacrf.knowledge;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class KnowledgeExceptionHandler {
  @ExceptionHandler(KnowledgeException.class)
  ResponseEntity<KnowledgeResponses.Error> handleKnowledge(KnowledgeException error) {
    return ResponseEntity.status(error.status())
        .body(new KnowledgeResponses.Error(error.code(), error.getMessage(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<KnowledgeResponses.Error> handleInvalidRequest(MethodArgumentNotValidException error) {
    return ResponseEntity.badRequest()
        .body(
            new KnowledgeResponses.Error(
                "INVALID_REQUEST", "The request does not match the knowledge contract.", UUID.randomUUID().toString()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<KnowledgeResponses.Error> handleConflict(DataIntegrityViolationException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new KnowledgeResponses.Error(
                "KNOWLEDGE_CONFLICT", "The knowledge record conflicts with current state.", UUID.randomUUID().toString()));
  }
}
