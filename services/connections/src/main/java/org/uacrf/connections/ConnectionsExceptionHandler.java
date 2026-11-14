package org.uacrf.connections;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ConnectionsExceptionHandler {
  @ExceptionHandler(ConnectionsException.class)
  ResponseEntity<ConnectionsResponses.Error> handleConnections(ConnectionsException error) {
    return ResponseEntity.status(error.status())
        .body(new ConnectionsResponses.Error(error.code(), error.getMessage(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ConnectionsResponses.Error> handleInvalidRequest(
      MethodArgumentNotValidException error) {
    return ResponseEntity.badRequest()
        .body(
            new ConnectionsResponses.Error(
                "INVALID_REQUEST", "The request does not match the connections contract.", UUID.randomUUID().toString()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ConnectionsResponses.Error> handleConflict(DataIntegrityViolationException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new ConnectionsResponses.Error(
                "CONNECTIONS_CONFLICT", "The connections record conflicts with current state.", UUID.randomUUID().toString()));
  }
}
