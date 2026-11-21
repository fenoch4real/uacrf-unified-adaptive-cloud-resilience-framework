package org.uacrf.objectives;

import java.util.Objects;

final class ObjectiveImportClassification {
  enum Status {
    NEW, UNCHANGED, CONFLICT, INVALID, WITHDRAWN
  }

  private ObjectiveImportClassification() {}

  static Status classify(
      ObjectiveImportFingerprint.Value baseDigest, ObjectiveImportFingerprint.Value localDigest,
      ObjectiveImportFingerprint.Value sourceDigest,
      boolean scopeComplete, boolean valid, boolean explicitWithdrawal) {
    if (!scopeComplete || !valid) {
      return Status.INVALID;
    }
    if (explicitWithdrawal) {
      return Status.WITHDRAWN;
    }
    if (sourceDigest == null) {
      throw new IllegalArgumentException("Expected a versioned source definition digest.");
    }
    if (sourceDigest.equals(localDigest) || sourceDigest.equals(baseDigest)) {
      return Status.UNCHANGED;
    }
    if (localDigest == null || (baseDigest != null && Objects.equals(localDigest, baseDigest))) {
      return Status.NEW;
    }
    return Status.CONFLICT;
  }

}
