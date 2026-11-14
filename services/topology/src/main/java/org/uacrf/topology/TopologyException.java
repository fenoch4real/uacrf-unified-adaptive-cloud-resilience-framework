package org.uacrf.topology;

import org.springframework.http.HttpStatus;

final class TopologyException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  TopologyException(HttpStatus status, String code, String message) {
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
