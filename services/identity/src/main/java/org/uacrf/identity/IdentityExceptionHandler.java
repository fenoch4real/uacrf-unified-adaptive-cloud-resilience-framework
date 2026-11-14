package org.uacrf.identity;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class IdentityExceptionHandler {
  @ExceptionHandler(IdentityException.class)
  ResponseEntity<IdentityResponses.Error> handleIdentity(IdentityException error) {
    return ResponseEntity.status(error.status())
        .body(
            new IdentityResponses.Error(
                error.code(), error.getMessage(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler(
      {
        MissingServletRequestParameterException.class,
        MissingRequestHeaderException.class,
        MethodArgumentTypeMismatchException.class,
        MethodArgumentNotValidException.class
      })
  ResponseEntity<IdentityResponses.Error> handleInvalidParameters(Exception error) {
    return ResponseEntity.badRequest()
        .body(
            new IdentityResponses.Error(
                "INVALID_REQUEST",
                "The query parameters do not match the identity contract.",
                UUID.randomUUID().toString()));
  }
}
