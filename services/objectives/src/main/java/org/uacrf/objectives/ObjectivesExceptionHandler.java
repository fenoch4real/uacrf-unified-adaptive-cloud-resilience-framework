package org.uacrf.objectives;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ObjectivesExceptionHandler {
  @ExceptionHandler(ObjectivesException.class)
  ResponseEntity<ObjectivesResponses.Error> handleObjectives(ObjectivesException error) {
    return ResponseEntity.status(error.status())
        .body(new ObjectivesResponses.Error(error.code(), error.getMessage(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ObjectivesResponses.Error> handleInvalidRequest(
      MethodArgumentNotValidException error) {
    return ResponseEntity.badRequest()
        .body(
            new ObjectivesResponses.Error(
                "INVALID_REQUEST", "The request does not match the objectives contract.", UUID.randomUUID().toString()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ObjectivesResponses.Error> handleConflict(DataIntegrityViolationException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new ObjectivesResponses.Error(
                "OBJECTIVE_CONFLICT", "The objectives record conflicts with current state.", UUID.randomUUID().toString()));
  }
}
