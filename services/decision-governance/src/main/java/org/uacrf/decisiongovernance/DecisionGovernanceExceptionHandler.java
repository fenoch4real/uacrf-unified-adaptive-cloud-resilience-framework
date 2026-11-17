package org.uacrf.decisiongovernance;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class DecisionGovernanceExceptionHandler {
  @ExceptionHandler(DecisionGovernanceException.class)
  ResponseEntity<ErrorResponse> handleDecisionGovernance(DecisionGovernanceException error) {
    return ResponseEntity.status(error.status())
        .body(new ErrorResponse(error.code(), error.getMessage(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ErrorResponse> handleInvalidRequest(Exception error) {
    return ResponseEntity.badRequest()
        .body(
            new ErrorResponse(
                "INVALID_REQUEST",
                "The request does not match the authorization-policy contract.",
                UUID.randomUUID().toString()));
  }

  record ErrorResponse(String code, String message, String traceId) {}
}
