package org.uacrf.objectives;

import java.util.Objects;

final class ObjectiveImportClassification {
  enum Status {
    NEW, UNCHANGED, CONFLICT, INVALID, WITHDRAWN
  }

  private ObjectiveImportClassification() {}

  static Status classify(
      String baseDigest, String localDigest, String sourceDigest,
      boolean scopeComplete, boolean valid, boolean explicitWithdrawal) {
    if (!scopeComplete || !valid) {
      return Status.INVALID;
    }
    if (explicitWithdrawal) {
      return Status.WITHDRAWN;
    }
    requireDigest(sourceDigest);
    if (baseDigest != null) requireDigest(baseDigest);
    if (localDigest != null) requireDigest(localDigest);
    if (sourceDigest.equals(localDigest) || sourceDigest.equals(baseDigest)) {
      return Status.UNCHANGED;
    }
    if (localDigest == null || (baseDigest != null && Objects.equals(localDigest, baseDigest))) {
      return Status.NEW;
    }
    return Status.CONFLICT;
  }

  private static void requireDigest(String digest) {
    if (digest == null || !digest.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Expected a lowercase SHA-256 definition digest.");
    }
  }
}
