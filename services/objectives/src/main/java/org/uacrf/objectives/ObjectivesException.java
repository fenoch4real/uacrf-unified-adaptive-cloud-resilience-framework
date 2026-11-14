package org.uacrf.objectives;

import org.springframework.http.HttpStatus;

final class ObjectivesException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  ObjectivesException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  HttpStatus status() {
    return status;
  }

  String code() {
    return code;
  }
}
