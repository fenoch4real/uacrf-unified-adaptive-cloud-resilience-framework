package org.uacrf.assessment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

final class AssessmentRequests {
  private AssessmentRequests() {}
  enum Comparator { LTE, GTE }
  enum Mode { SHADOW, DRY_RUN, LIVE }
  record ObjectiveRef(@NotBlank @Size(max=200) String objectiveId, @Min(1) long revision,
      @NotBlank @Size(max=200) String digest) {}
  record GraphRef(@NotBlank @Size(max=200) String graphId, @Min(1) long revision,
      @NotBlank @Size(max=200) String digest) {}
  record CreateCase(@NotBlank @Size(max=200) String workloadId, @NotBlank @Size(max=200) String purpose,
      @NotBlank @Size(max=100) String metric, @NotNull Comparator comparator, @NotNull BigDecimal threshold,
      @NotBlank @Size(max=30) String unit, @Min(1) @Max(31536000) int windowSeconds,
      @NotNull @Valid ObjectiveRef objectiveRef, @NotNull @Valid GraphRef graphRef,
      @NotEmpty @Size(max=100) List<@NotBlank @Size(max=200) String> requiredSourceKinds) {}
  record Transition(@Min(1) long expectedRevision) {}
  record Measurement(@NotBlank @Size(max=200) String sourceReceiptId,
      @NotBlank @Size(max=200) String sourceKind, @NotNull Instant observedAt,
      @NotNull BigDecimal value, @NotBlank @Size(max=30) String unit) {}
  record Evaluate(@NotNull Instant observationCutoff, @NotNull Mode mode,
      @NotNull @Size(max=1000) List<@NotNull @Valid Measurement> measurements) {}
}
