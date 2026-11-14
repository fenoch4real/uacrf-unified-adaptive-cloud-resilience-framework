package org.uacrf.identity;

import org.springframework.http.HttpStatus;

final class IdentityException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  IdentityException(HttpStatus status, String code, String message) {
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
