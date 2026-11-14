package org.uacrf.assessment;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class AssessmentExceptionHandler {
  @ExceptionHandler(AssessmentException.class)
  ResponseEntity<AssessmentResponses.Error> handleAssessment(AssessmentException error) {
    return ResponseEntity.status(error.status())
        .body(new AssessmentResponses.Error(error.code(), error.getMessage(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<AssessmentResponses.Error> handleInvalidRequest(
      MethodArgumentNotValidException error) {
    return ResponseEntity.badRequest()
        .body(
            new AssessmentResponses.Error(
                "INVALID_REQUEST", "The request does not match the assessment contract.", UUID.randomUUID().toString()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<AssessmentResponses.Error> handleConflict(DataIntegrityViolationException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new AssessmentResponses.Error(
                "ASSESSMENT_CONFLICT", "The assessment record conflicts with current state.", UUID.randomUUID().toString()));
  }
}
