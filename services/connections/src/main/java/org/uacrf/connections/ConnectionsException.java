package org.uacrf.connections;

import org.springframework.http.HttpStatus;

final class ConnectionsException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  ConnectionsException(HttpStatus status, String code, String message) {
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
