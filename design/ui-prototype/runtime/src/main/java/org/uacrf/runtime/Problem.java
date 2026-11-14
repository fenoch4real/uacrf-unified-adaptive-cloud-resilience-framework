package org.uacrf.runtime;

final class Problem extends RuntimeException {
  final int status;

  Problem(int status, String message) {
    super(message);
    this.status = status;
  }
}
