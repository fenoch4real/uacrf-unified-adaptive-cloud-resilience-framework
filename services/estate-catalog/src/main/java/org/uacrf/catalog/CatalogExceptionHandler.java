package org.uacrf.catalog;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class CatalogExceptionHandler {
  @ExceptionHandler(CatalogException.class)
  ResponseEntity<CatalogResponses.Error> handleCatalog(CatalogException error) {
    return ResponseEntity.status(error.status())
        .body(new CatalogResponses.Error(error.code(), error.getMessage(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<CatalogResponses.Error> handleInvalidRequest(
      MethodArgumentNotValidException error) {
    return ResponseEntity.badRequest()
        .body(
            new CatalogResponses.Error(
                "INVALID_REQUEST", "The request does not match the catalog contract.", UUID.randomUUID().toString()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<CatalogResponses.Error> handleConflict(DataIntegrityViolationException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new CatalogResponses.Error(
                "CATALOG_CONFLICT", "The catalog record conflicts with current state.", UUID.randomUUID().toString()));
  }
}
