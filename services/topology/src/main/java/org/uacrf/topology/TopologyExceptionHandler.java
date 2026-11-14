package org.uacrf.topology;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class TopologyExceptionHandler {
  @ExceptionHandler(TopologyException.class)
  ResponseEntity<TopologyResponses.Error> handleTopology(TopologyException error) {
    return ResponseEntity.status(error.status())
        .body(new TopologyResponses.Error(error.code(), error.getMessage(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<TopologyResponses.Error> handleInvalidRequest(
      MethodArgumentNotValidException error) {
    return ResponseEntity.badRequest()
        .body(
            new TopologyResponses.Error(
                "INVALID_REQUEST", "The request does not match the topology contract.", UUID.randomUUID().toString()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<TopologyResponses.Error> handleConflict(DataIntegrityViolationException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new TopologyResponses.Error(
                "TOPOLOGY_CONFLICT", "The topology record conflicts with current state.", UUID.randomUUID().toString()));
  }
}
