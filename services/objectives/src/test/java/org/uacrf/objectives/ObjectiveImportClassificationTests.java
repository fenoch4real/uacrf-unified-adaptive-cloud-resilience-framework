package org.uacrf.objectives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.uacrf.objectives.ObjectiveImportClassification.Status;

import org.junit.jupiter.api.Test;

class ObjectiveImportClassificationTests {
  private static final String A = "a".repeat(64);
  private static final String B = "b".repeat(64);
  private static final String C = "c".repeat(64);

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
    assertThatThrownBy(() -> classify("invalid", A, B)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> classify(A, "A".repeat(64), B)).isInstanceOf(IllegalArgumentException.class);
  }

  private Status classify(String base, String local, String source) {
    return ObjectiveImportClassification.classify(base, local, source, true, true, false);
  }
}
