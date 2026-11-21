package org.uacrf.objectives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.uacrf.objectives.ObjectiveImportClassification.Status;

import org.junit.jupiter.api.Test;

class ObjectiveImportClassificationTests {
  private static final ObjectiveImportFingerprint.Value A = value("a".repeat(64));
  private static final ObjectiveImportFingerprint.Value B = value("b".repeat(64));
  private static final ObjectiveImportFingerprint.Value C = value("c".repeat(64));

  @Test
  void comparesBaseLocalAndSourceWithoutChoosingAConflictWinner() {
    assertThat(classify(A, A, B)).isEqualTo(Status.NEW);
    assertThat(classify(A, B, C)).isEqualTo(Status.CONFLICT);
    assertThat(classify(A, B, B)).isEqualTo(Status.UNCHANGED);
    assertThat(classify(A, B, A)).isEqualTo(Status.UNCHANGED);
    assertThat(classify(null, null, B)).isEqualTo(Status.NEW);
    assertThat(classify(null, A, B)).isEqualTo(Status.CONFLICT);
  }

  @Test
  void incompleteOrInvalidScopeCannotEstablishWithdrawal() {
    assertThat(ObjectiveImportClassification.classify(A, A, B, false, true, true))
        .isEqualTo(Status.INVALID);
    assertThat(ObjectiveImportClassification.classify(A, A, B, true, false, true))
        .isEqualTo(Status.INVALID);
    assertThat(ObjectiveImportClassification.classify(A, A, null, true, true, true))
        .isEqualTo(Status.WITHDRAWN);
  }

  @Test
  void rejectsMissingOrMalformedDigestsForComparison() {
    assertThatThrownBy(() -> classify(A, A, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> value("invalid")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> value("A".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ObjectiveImportFingerprint.Value("other-version", "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ObjectiveImportFingerprint.Value(null, "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ObjectiveImportFingerprint.Value value(String digest) {
    return new ObjectiveImportFingerprint.Value(ObjectiveImportFingerprint.VERSION, digest);
  }

  private Status classify(ObjectiveImportFingerprint.Value base, ObjectiveImportFingerprint.Value local,
      ObjectiveImportFingerprint.Value source) {
    return ObjectiveImportClassification.classify(base, local, source, true, true, false);
  }
}
